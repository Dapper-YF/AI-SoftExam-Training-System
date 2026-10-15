import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { toSafeUserMessage } from '@/api/client'
import { getPlatformAuthGateway, getPlatformFoundationGateway } from '@/modules/foundation/api/gateway'
import type { ActiveExamRule, LevelCode, Qualification, Subject } from '@/modules/foundation/types'

export const QUALIFICATION_STORAGE_KEY = 'platform-practice-scope-v1'
const PLATFORM_AUTH_STORAGE_KEY = 'platform-auth-context-v1'

type LoadStatus = 'idle' | 'loading' | 'success' | 'empty' | 'error'
type RuleLoadStatus = LoadStatus | 'unavailable'

export const ACTIVE_EXAM_PERIOD_CODE = '2026-H2'

interface PracticeScopeCache {
  selectedLevelCode: LevelCode
  selectedQualificationId: string
  selectedSubjectId: string | null
  serverConfirmedAt: number
}

function errorDetails(error: unknown, anonymous = false): { message: string; traceId?: string } {
  const status = (error as { status?: unknown } | null)?.status
  return {
    message: anonymous && status === 401
      ? '公开资格与题库目录暂不可用，请稍后重试'
      : toSafeUserMessage(error),
    traceId: (error as { traceId?: string } | null)?.traceId,
  }
}

function isLevelCode(value: unknown): value is LevelCode {
  return value === 'ADVANCED' || value === 'INTERMEDIATE' || value === 'PRIMARY'
}

function compareByOrderAndId<T extends { id: string; sortOrder?: number }>(left: T, right: T): number {
  return (left.sortOrder ?? Number.MAX_SAFE_INTEGER) - (right.sortOrder ?? Number.MAX_SAFE_INTEGER)
    || left.id.localeCompare(right.id)
}

function activeScopeStorageKey(): string {
  const value = localStorage.getItem(PLATFORM_AUTH_STORAGE_KEY)
  if (!value) return `${QUALIFICATION_STORAGE_KEY}:anonymous`
  try {
    const parsed = JSON.parse(value) as { user?: { id?: unknown } }
    const id = parsed.user?.id
    if (typeof id === 'string' && id.trim()) return `${QUALIFICATION_STORAGE_KEY}:user:${id.trim()}`
  } catch {
    // Ignore malformed auth cache and fall back to anonymous scope.
  }
  return `${QUALIFICATION_STORAGE_KEY}:anonymous`
}

function hasPersistedAuthenticatedUser(): boolean {
  const value = localStorage.getItem(PLATFORM_AUTH_STORAGE_KEY)
  if (!value) return false
  try {
    const parsed = JSON.parse(value) as { user?: { id?: unknown } }
    return typeof parsed.user?.id === 'string' && Boolean(parsed.user.id.trim())
  } catch {
    return false
  }
}

function readCache(): PracticeScopeCache | null {
  const storageKey = activeScopeStorageKey()
  const value = localStorage.getItem(storageKey) ?? localStorage.getItem(QUALIFICATION_STORAGE_KEY)
  if (!value) return null
  try {
    const parsed = JSON.parse(value) as Partial<PracticeScopeCache>
    if (
      !isLevelCode(parsed.selectedLevelCode)
      || typeof parsed.selectedQualificationId !== 'string'
      || (parsed.selectedSubjectId !== null && typeof parsed.selectedSubjectId !== 'string')
      || typeof parsed.serverConfirmedAt !== 'number'
      || !Number.isFinite(parsed.serverConfirmedAt)
    ) {
      throw new Error('invalid practice scope cache')
    }
    if (storageKey !== QUALIFICATION_STORAGE_KEY) {
      localStorage.setItem(storageKey, JSON.stringify(parsed))
      localStorage.removeItem(QUALIFICATION_STORAGE_KEY)
    }
    return parsed as PracticeScopeCache
  } catch {
    localStorage.removeItem(storageKey)
    localStorage.removeItem(QUALIFICATION_STORAGE_KEY)
    return null
  }
}

export const useQualificationStore = defineStore('qualification', () => {
  const qualifications = ref<Qualification[]>([])
  const subjects = ref<Subject[]>([])
  const selectedLevelCode = ref<LevelCode | null>(null)
  const selectedQualificationId = ref<string | null>(null)
  const selectedSubjectId = ref<string | null>(null)
  const status = ref<LoadStatus>('idle')
  const subjectStatus = ref<LoadStatus>('idle')
  const errorMessage = ref('')
  const errorTraceId = ref<string>()
  const activeExamRule = ref<ActiveExamRule | null>(null)
  const ruleStatus = ref<RuleLoadStatus>('idle')
  const ruleErrorMessage = ref('')
  let qualificationEpoch = 0
  let subjectEpoch = 0
  let ruleEpoch = 0
  let anonymousLoad = false

  const enabledQualifications = computed(() =>
    qualifications.value.filter((item) => item.status === 'ENABLED').sort(compareByOrderAndId),
  )
  const availableLevelCodes = computed<LevelCode[]>(() => {
    const seen = new Set<LevelCode>()
    return enabledQualifications.value.reduce<LevelCode[]>((levels, qualification) => {
      if (!seen.has(qualification.levelCode)) {
        seen.add(qualification.levelCode)
        levels.push(qualification.levelCode)
      }
      return levels
    }, [])
  })
  const qualificationsForSelectedLevel = computed(() =>
    enabledQualifications.value.filter((item) => item.levelCode === selectedLevelCode.value),
  )
  const selectedQualification = computed(() =>
    enabledQualifications.value.find((item) => item.id === selectedQualificationId.value),
  )
  const selectedSubject = computed(() =>
    subjects.value.find((item) => item.id === selectedSubjectId.value),
  )

  function persistSelection(): void {
    if (!selectedLevelCode.value || !selectedQualificationId.value) {
      localStorage.removeItem(activeScopeStorageKey())
      localStorage.removeItem(QUALIFICATION_STORAGE_KEY)
      return
    }
    const cache: PracticeScopeCache = {
      selectedLevelCode: selectedLevelCode.value,
      selectedQualificationId: selectedQualificationId.value,
      selectedSubjectId: selectedSubjectId.value,
      serverConfirmedAt: Date.now(),
    }
    localStorage.setItem(activeScopeStorageKey(), JSON.stringify(cache))
    localStorage.removeItem(QUALIFICATION_STORAGE_KEY)
  }

  async function loadSubjects(qualificationId: string, preferredSubjectId?: string | null): Promise<void> {
    const requestEpoch = ++subjectEpoch
    subjectStatus.value = 'loading'
    subjects.value = []
    selectedSubjectId.value = null
    errorMessage.value = ''
    errorTraceId.value = undefined
    try {
      const items = await getPlatformFoundationGateway().listSubjects(qualificationId)
      if (requestEpoch !== subjectEpoch || selectedQualificationId.value !== qualificationId) return
      subjects.value = items
        .filter((item) => item.status === 'ENABLED' && item.qualificationId === qualificationId)
        .sort(compareByOrderAndId)
      selectedSubjectId.value = subjects.value.some((item) => item.id === preferredSubjectId)
        ? preferredSubjectId ?? null
        : subjects.value[0]?.id ?? null
      subjectStatus.value = subjects.value.length ? 'success' : 'empty'
      persistSelection()
    } catch (error) {
      if (requestEpoch !== subjectEpoch || selectedQualificationId.value !== qualificationId) return
      const details = errorDetails(error, anonymousLoad)
      subjectStatus.value = 'error'
      errorMessage.value = details.message
      errorTraceId.value = details.traceId
      subjects.value = []
      selectedSubjectId.value = null
    }
  }

  async function loadExamRule(qualificationId: string): Promise<void> {
    const requestEpoch = ++ruleEpoch
    activeExamRule.value = null
    ruleErrorMessage.value = ''
    const qualification = enabledQualifications.value.find((item) => item.id === qualificationId)
    if (qualification?.available === false) {
      ruleStatus.value = 'unavailable'
      return
    }
    const gateway = getPlatformFoundationGateway()
    if (!gateway.getActiveExamRule) {
      ruleStatus.value = 'unavailable'
      ruleErrorMessage.value = '考试规则接口尚未联调'
      return
    }
    ruleStatus.value = 'loading'
    try {
      const rule = await gateway.getActiveExamRule(qualificationId, ACTIVE_EXAM_PERIOD_CODE)
      if (requestEpoch !== ruleEpoch || selectedQualificationId.value !== qualificationId) return
      activeExamRule.value = rule
      ruleStatus.value = rule ? 'success' : 'unavailable'
    } catch (error) {
      if (requestEpoch !== ruleEpoch || selectedQualificationId.value !== qualificationId) return
      ruleStatus.value = 'error'
      ruleErrorMessage.value = errorDetails(error, anonymousLoad).message
    }
  }

  async function load(options: { anonymous?: boolean } = {}): Promise<void> {
    anonymousLoad = options.anonymous === true
    const requestEpoch = ++qualificationEpoch
    ++subjectEpoch
    ++ruleEpoch
    status.value = 'loading'
    subjectStatus.value = 'idle'
    errorMessage.value = ''
    errorTraceId.value = undefined
    qualifications.value = []
    subjects.value = []
    selectedLevelCode.value = null
    selectedQualificationId.value = null
    selectedSubjectId.value = null
    activeExamRule.value = null
    ruleStatus.value = 'idle'
    ruleErrorMessage.value = ''
    const cache = readCache()
    try {
      const items = await getPlatformFoundationGateway().listQualifications()
      if (requestEpoch !== qualificationEpoch) return
      qualifications.value = items
      if (!enabledQualifications.value.length) {
        status.value = 'empty'
        localStorage.removeItem(activeScopeStorageKey())
        localStorage.removeItem(QUALIFICATION_STORAGE_KEY)
        return
      }

      const preparationQualificationId = !anonymousLoad && hasPersistedAuthenticatedUser()
        ? await getPlatformAuthGateway().getProfile()
          .then((profile) => profile.preparationQualificationId ?? null)
          // 资格目录仍应可在个人资料临时不可用时加载；此时回退到该账户自己的缓存。
          .catch(() => null)
        : null
      if (requestEpoch !== qualificationEpoch) return
      const preparationQualification = enabledQualifications.value.find((item) =>
        item.id === preparationQualificationId,
      )
      selectedLevelCode.value = preparationQualification?.levelCode
        ?? (availableLevelCodes.value.includes(cache?.selectedLevelCode as LevelCode)
          ? cache?.selectedLevelCode ?? availableLevelCodes.value[0] ?? null
          : availableLevelCodes.value[0] ?? null)
      const cachedQualification = enabledQualifications.value.find((item) =>
        item.id === cache?.selectedQualificationId && item.levelCode === selectedLevelCode.value,
      )
      selectedQualificationId.value = preparationQualification?.id
        ?? cachedQualification?.id
        ?? qualificationsForSelectedLevel.value[0]?.id
        ?? null
      status.value = 'success'
      if (selectedQualificationId.value) {
        await Promise.all([
          loadSubjects(selectedQualificationId.value, cache?.selectedSubjectId),
          loadExamRule(selectedQualificationId.value),
        ])
      }
    } catch (error) {
      if (requestEpoch !== qualificationEpoch) return
      const details = errorDetails(error, anonymousLoad)
      status.value = 'error'
      subjectStatus.value = 'idle'
      errorMessage.value = details.message
      errorTraceId.value = details.traceId
      qualifications.value = []
      subjects.value = []
      selectedLevelCode.value = null
      selectedQualificationId.value = null
      selectedSubjectId.value = null
      activeExamRule.value = null
      ruleStatus.value = 'idle'
    }
  }

  async function selectLevel(levelCode: LevelCode): Promise<void> {
    if (!availableLevelCodes.value.includes(levelCode)) return
    ++subjectEpoch
    ++ruleEpoch
    selectedLevelCode.value = levelCode
    subjects.value = []
    selectedSubjectId.value = null
    selectedQualificationId.value = qualificationsForSelectedLevel.value[0]?.id ?? null
    subjectStatus.value = selectedQualificationId.value ? 'loading' : 'empty'
    activeExamRule.value = null
    ruleStatus.value = selectedQualificationId.value ? 'loading' : 'empty'
    if (selectedQualificationId.value) {
      await Promise.all([
        loadSubjects(selectedQualificationId.value),
        loadExamRule(selectedQualificationId.value),
      ])
    }
    else persistSelection()
  }

  async function selectQualification(qualificationId: string): Promise<void> {
    const qualification = enabledQualifications.value.find((item) =>
      item.id === qualificationId && item.levelCode === selectedLevelCode.value,
    )
    if (!qualification || qualificationId === selectedQualificationId.value) return
    selectedQualificationId.value = qualificationId
    subjects.value = []
    selectedSubjectId.value = null
    activeExamRule.value = null
    await Promise.all([
      loadSubjects(qualificationId),
      loadExamRule(qualificationId),
    ])
  }

  function selectSubject(subjectId: string): void {
    if (!subjects.value.some((item) => item.id === subjectId)) return
    selectedSubjectId.value = subjectId
    persistSelection()
  }

  function clear(): void {
    ++qualificationEpoch
    ++subjectEpoch
    ++ruleEpoch
    qualifications.value = []
    subjects.value = []
    selectedLevelCode.value = null
    selectedQualificationId.value = null
    selectedSubjectId.value = null
    status.value = 'idle'
    subjectStatus.value = 'idle'
    errorMessage.value = ''
    errorTraceId.value = undefined
    activeExamRule.value = null
    ruleStatus.value = 'idle'
    ruleErrorMessage.value = ''
    anonymousLoad = false
    localStorage.removeItem(activeScopeStorageKey())
    localStorage.removeItem(QUALIFICATION_STORAGE_KEY)
  }

  function resetSessionState(): void {
    ++qualificationEpoch
    ++subjectEpoch
    ++ruleEpoch
    qualifications.value = []
    subjects.value = []
    selectedLevelCode.value = null
    selectedQualificationId.value = null
    selectedSubjectId.value = null
    status.value = 'idle'
    subjectStatus.value = 'idle'
    errorMessage.value = ''
    errorTraceId.value = undefined
    activeExamRule.value = null
    ruleStatus.value = 'idle'
    ruleErrorMessage.value = ''
    anonymousLoad = false
  }

  return {
    qualifications,
    subjects,
    selectedLevelCode,
    selectedQualificationId,
    selectedSubjectId,
    status,
    subjectStatus,
    errorMessage,
    errorTraceId,
    activeExamRule,
    ruleStatus,
    ruleErrorMessage,
    enabledQualifications,
    availableLevelCodes,
    qualificationsForSelectedLevel,
    selectedQualification,
    selectedSubject,
    load,
    selectLevel,
    selectQualification,
    selectSubject,
    clear,
    resetSessionState,
  }
})
