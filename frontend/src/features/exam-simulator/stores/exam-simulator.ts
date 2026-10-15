import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { toSafeUserMessage } from '@/api/client'
import { getExamSimulatorGateway } from '../api/gateway'
import type {
  DemoExamContext,
  ExamCandidate,
  ExamEntryCode,
  ExamLoginRequest,
  ExamPaper,
  ExamRules,
  ExamSessionClockState,
  PersistedExamClientState,
} from '../types'

const CLIENT_STORAGE_KEY = 'exam-simulator-client-state-v1'
const TOKEN_STORAGE_KEY = 'exam-simulator-token'
type ExamSubmitStatus = 'idle' | 'submitting' | 'submitted' | 'failed' | 'conflict'
type ExamSubmitFinalStatus = Exclude<ExamSubmitStatus, 'idle' | 'submitting'>

class ExamSubmitStatusError extends Error {
  readonly code = 'SUBMIT_NOT_CONFIRMED'

  constructor(readonly status: Exclude<ExamSubmitFinalStatus, 'submitted'>) {
    super(status === 'conflict' ? '交卷状态冲突，请稍后重试' : '交卷尚未获得服务端终态确认')
    this.name = 'ExamSubmitStatusError'
  }
}

function initialPersistedState(): PersistedExamClientState {
  return {
    sessionId: null,
    sessionToken: null,
    entryCode: null,
    candidateConfirmed: false,
    rulesAcknowledged: false,
    forceReminderAcknowledged: false,
    candidate: null,
    rules: null,
    clockState: null,
    paper: null,
    submittedAt: null,
    currentQuestionIndex: 0,
    answers: {},
    marks: [],
    sessionCode: null,
    subjectCode: null,
    localAnswerVersions: {},
  }
}

export const useExamSimulatorStore = defineStore('exam-simulator', () => {
  const sessionId = ref<string | null>(null)
  const sessionToken = ref<string | null>(null)
  const entryCode = ref<ExamEntryCode | null>(null)
  const candidateConfirmed = ref(false)
  const rulesAcknowledged = ref(false)
  const forceReminderAcknowledged = ref(false)
  const candidate = ref<ExamCandidate | null>(null)
  const rules = ref<ExamRules | null>(null)
  const clockState = ref<ExamSessionClockState | null>(null)
  const paper = ref<ExamPaper | null>(null)
  const submittedAt = ref<string | null>(null)
  const currentQuestionIndex = ref(0)
  const answers = ref<Record<string, string>>({})
  const marks = ref<number[]>([])
  const sessionCode = ref<string | null>(null)
  const subjectCode = ref<string | null>(null)
  const localAnswerVersions = ref<Record<string, number>>({})
  const clockTick = ref(Date.now())
  const serverOffsetMs = ref(0)
  const loading = ref(false)
  const saving = ref(false)
  const submitStatus = ref<ExamSubmitStatus>('idle')
  const paperLoadProgress = ref(0)
  const errorMessage = ref('')
  let restored = false

  const gateway = computed(() => getExamSimulatorGateway())
  const demoContext = computed<DemoExamContext | null>(() => gateway.value.getDemoContext())
  const hasSession = computed(() => Boolean(sessionId.value && sessionToken.value))
  const authoritativeNowMs = computed(() => clockTick.value + serverOffsetMs.value)
  const waitingSeconds = computed(() => {
    if (!clockState.value) return 0
    return Math.max(
      0,
      Math.ceil((Date.parse(clockState.value.scheduledStartAt) - authoritativeNowMs.value) / 1_000),
    )
  })
  const remainingSeconds = computed(() => {
    if (!clockState.value) return 0
    return Math.max(
      0,
      Math.ceil((Date.parse(clockState.value.deadlineAt) - authoritativeNowMs.value) / 1_000),
    )
  })
  const examHasStarted = computed(() => Boolean(clockState.value) && waitingSeconds.value === 0)
  const shouldForceRuleReminder = computed(
    () =>
      candidateConfirmed.value &&
      examHasStarted.value &&
      !rulesAcknowledged.value &&
      !forceReminderAcknowledged.value,
  )
  const currentQuestion = computed(() => paper.value?.questions[currentQuestionIndex.value] ?? null)
  const answeredQuestionIds = computed(() =>
    new Set(
      paper.value?.questions
        .filter((question) => Boolean(answers.value[String(question.subQuestionId)]?.trim()))
        .map((question) => question.questionId) ?? [],
    ),
  )
  const markedQuestionIds = computed(() => new Set(marks.value))
  const canContinue = computed(() => Boolean(clockState.value?.canContinue))

  function serialize(): PersistedExamClientState {
    return {
      sessionId: sessionId.value,
      sessionToken: sessionToken.value,
      entryCode: entryCode.value,
      candidateConfirmed: candidateConfirmed.value,
      rulesAcknowledged: rulesAcknowledged.value,
      forceReminderAcknowledged: forceReminderAcknowledged.value,
      candidate: candidate.value,
      rules: rules.value,
      clockState: clockState.value,
      paper: paper.value,
      submittedAt: submittedAt.value,
      currentQuestionIndex: currentQuestionIndex.value,
      answers: answers.value,
      marks: marks.value,
      sessionCode: sessionCode.value,
      subjectCode: subjectCode.value,
      localAnswerVersions: localAnswerVersions.value,
    }
  }

  function persist(): void {
    sessionStorage.setItem(CLIENT_STORAGE_KEY, JSON.stringify(serialize()))
    if (sessionToken.value) sessionStorage.setItem(TOKEN_STORAGE_KEY, sessionToken.value)
  }

  function hydrate(state: PersistedExamClientState): void {
    sessionId.value = state.sessionId
    sessionToken.value = state.sessionToken
    entryCode.value = state.entryCode
    candidateConfirmed.value = state.candidateConfirmed
    rulesAcknowledged.value = state.rulesAcknowledged
    forceReminderAcknowledged.value = state.forceReminderAcknowledged
    candidate.value = state.candidate
    rules.value = state.rules
    clockState.value = state.clockState
    paper.value = state.paper
    submittedAt.value = state.submittedAt ?? null
    submitStatus.value = state.submittedAt ? 'submitted' : 'idle'
    currentQuestionIndex.value = state.currentQuestionIndex
    answers.value = state.answers
    marks.value = state.marks
    sessionCode.value = state.sessionCode ?? null
    subjectCode.value = state.subjectCode ?? null
    localAnswerVersions.value = state.localAnswerVersions ?? {}
  }

  function restore(): void {
    if (restored) return
    restored = true
    const raw = sessionStorage.getItem(CLIENT_STORAGE_KEY)
    if (!raw) return
    try {
      hydrate(JSON.parse(raw) as PersistedExamClientState)
      if (clockState.value) {
        serverOffsetMs.value = Date.parse(clockState.value.serverNow) - Date.now()
      }
    } catch {
      clearClientSession()
    }
  }

  function clearClientSession(): void {
    hydrate(initialPersistedState())
    sessionStorage.removeItem(CLIENT_STORAGE_KEY)
    sessionStorage.removeItem(TOKEN_STORAGE_KEY)
  }

  function updateClock(): void {
    clockTick.value = Date.now()
  }

  async function syncClockState(): Promise<void> {
    if (!sessionId.value) return
    const state = await gateway.value.getState(sessionId.value)
    clockState.value = state
    sessionCode.value = state.sessionCode ?? null
    subjectCode.value = state.subjectCode ?? null
    serverOffsetMs.value = Date.parse(state.serverNow) - Date.now()
    updateClock()
    persist()
  }

  async function confirmSubmissionState(): Promise<boolean> {
    if (!sessionId.value) return false
    try {
      await syncClockState()
    } catch {
      return false
    }
    const state = clockState.value
    if (!state || !['SUBMITTED', 'SUBJECT_TRANSITION', 'COMPLETED'].includes(state.state)) return false
    submittedAt.value ??= state.serverNow
    submitStatus.value = 'submitted'
    persist()
    return true
  }

  async function login(request: ExamLoginRequest): Promise<void> {
    loading.value = true
    errorMessage.value = ''
    try {
      clearClientSession()
      entryCode.value = request.entryCode
      const result = await gateway.value.login(request)
      sessionId.value = result.sessionId
      sessionToken.value = result.sessionToken
      candidate.value = await gateway.value.getCandidate(result.sessionId)
      await syncClockState()
      persist()
    } catch (error) {
      errorMessage.value = toSafeUserMessage(error)
      throw error
    } finally {
      loading.value = false
    }
  }

  async function loadCandidate(): Promise<void> {
    if (!sessionId.value) return
    candidate.value = await gateway.value.getCandidate(sessionId.value)
    persist()
  }

  async function confirmCandidate(): Promise<void> {
    if (!sessionId.value) throw new Error('考试会话不存在')
    loading.value = true
    try {
      await gateway.value.confirmCandidate(sessionId.value)
      candidateConfirmed.value = true
      rules.value = await gateway.value.getRules(sessionId.value)
      await syncClockState()
      persist()
    } finally {
      loading.value = false
    }
  }

  async function loadRules(): Promise<void> {
    if (!sessionId.value || !candidateConfirmed.value) return
    rules.value = await gateway.value.getRules(sessionId.value)
    await syncClockState()
    persist()
  }

  function acknowledgeForceReminder(): void {
    forceReminderAcknowledged.value = true
    persist()
  }

  async function acknowledgeRules(): Promise<boolean> {
    if (!sessionId.value) throw new Error('考试会话不存在')
    loading.value = true
    try {
      await gateway.value.acknowledgeRules(sessionId.value)
      rulesAcknowledged.value = true
      await syncClockState()
      persist()
      return examHasStarted.value
    } finally {
      loading.value = false
    }
  }

  async function enterExam(): Promise<void> {
    if (!sessionId.value) throw new Error('考试会话不存在')
    loading.value = true
    paperLoadProgress.value = 18
    try {
      await gateway.value.enter(sessionId.value)
      paperLoadProgress.value = 58
      await new Promise((resolve) => window.setTimeout(resolve, 120))
      paper.value = await gateway.value.getPaper(sessionId.value)
      paperLoadProgress.value = 100
      await syncClockState()
      persist()
    } finally {
      loading.value = false
    }
  }

  async function restoreRemoteSession(): Promise<void> {
    restore()
    if (!sessionId.value) return
    try {
      await loadCandidate()
      await syncClockState()
      if (candidateConfirmed.value && !rules.value) {
        rules.value = await gateway.value.getRules(sessionId.value)
      }
      if (clockState.value?.state === 'IN_PROGRESS' && !paper.value) {
        paper.value = await gateway.value.getPaper(sessionId.value)
      }
      if (clockState.value?.state === 'SUBMITTED') {
        submittedAt.value ??= clockState.value.serverNow
        submitStatus.value = 'submitted'
      }
      persist()
    } catch {
      clearClientSession()
    }
  }

  function goToQuestion(index: number): void {
    if (!paper.value || index < 0 || index >= paper.value.questions.length) return
    currentQuestionIndex.value = index
    persist()
  }

  async function saveAnswer(subQuestionId: number, value: string): Promise<void> {
    saving.value = true
    try {
      if (sessionId.value) {
        await gateway.value.saveAnswer?.(
          sessionId.value,
          subQuestionId,
          value,
          `answer-${subQuestionId}-${Date.now()}-${Math.random().toString(16).slice(2)}`,
        )
      }
      answers.value = { ...answers.value, [String(subQuestionId)]: value }
      localAnswerVersions.value = {
        ...localAnswerVersions.value,
        [String(subQuestionId)]: (localAnswerVersions.value[String(subQuestionId)] ?? 0) + 1,
      }
      persist()
    } finally {
      saving.value = false
    }
  }

  function toggleMark(questionId: number): void {
    const marked = marks.value.includes(questionId)
    marks.value = marked
      ? marks.value.filter((id) => id !== questionId)
      : [...marks.value, questionId]
    persist()
    if (sessionId.value) {
      void gateway.value.toggleMark?.(
        sessionId.value,
        questionId,
        !marked,
        `mark-${questionId}-${Date.now()}-${Math.random().toString(16).slice(2)}`,
      )
    }
  }

  async function submitExam(): Promise<'submitted'> {
    if (!sessionId.value) throw new Error('考试会话不存在')
    if (submitStatus.value === 'submitted') return 'submitted'
    submitStatus.value = 'submitting'
    try {
      const result = await gateway.value.submit(sessionId.value, `submit-${sessionId.value}`, 'MANUAL')
      if (result.state === 'SUBMITTED' && Number.isFinite(Date.parse(result.submittedAt))) {
        submittedAt.value = result.submittedAt
      }
      if (await confirmSubmissionState()) {
        return 'submitted'
      }
      submitStatus.value = 'failed'
      persist()
      throw new ExamSubmitStatusError('failed')
    } catch (error) {
      const code = error instanceof Error && 'code' in error ? String(error.code) : ''
      const status = error instanceof Error && 'status' in error ? Number(error.status) : undefined
      if (await confirmSubmissionState()) return 'submitted'
      submitStatus.value = code.includes('CONFLICT') || code.includes('ALREADY') || status === 409 ? 'conflict' : 'failed'
      throw new ExamSubmitStatusError(submitStatus.value)
    }
  }

  async function continueNextSubject(): Promise<void> {
    if (!sessionId.value) throw new Error('考试会话不存在')
    const continueSubject = gateway.value.continueNextSubject
    if (!continueSubject) throw new Error('当前考试网关不支持科目切换')
    const state = await continueSubject(
      sessionId.value,
      `continue-${sessionId.value}-${Date.now()}`,
    )
    clockState.value = state
    sessionCode.value = state.sessionCode ?? null
    subjectCode.value = state.subjectCode ?? null
    paper.value = await gateway.value.getPaper(sessionId.value)
    submittedAt.value = null
    submitStatus.value = 'idle'
    currentQuestionIndex.value = 0
    persist()
  }

  return {
    sessionId,
    sessionToken,
    entryCode,
    candidateConfirmed,
    rulesAcknowledged,
    forceReminderAcknowledged,
    candidate,
    rules,
    clockState,
    paper,
    submittedAt,
    currentQuestionIndex,
    answers,
    marks,
    loading,
    saving,
    submitStatus,
    paperLoadProgress,
    errorMessage,
    demoContext,
    hasSession,
    waitingSeconds,
    remainingSeconds,
    examHasStarted,
    shouldForceRuleReminder,
    currentQuestion,
    answeredQuestionIds,
    markedQuestionIds,
    canContinue,
    sessionCode,
    subjectCode,
    localAnswerVersions,
    restore,
    clearClientSession,
    updateClock,
    syncClockState,
    login,
    loadCandidate,
    confirmCandidate,
    loadRules,
    acknowledgeForceReminder,
    acknowledgeRules,
    enterExam,
    restoreRemoteSession,
    goToQuestion,
    saveAnswer,
    toggleMark,
    submitExam,
    continueNextSubject,
    persist,
  }
})
