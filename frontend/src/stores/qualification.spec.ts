import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { toSafeUserMessage } from '@/api/client'
import type { PlatformAuthGateway } from '@/modules/foundation/api/platform-auth.gateway'
import type { PlatformFoundationGateway } from '@/modules/foundation/api/platform-foundation.gateway'
import { setPlatformAuthGatewayForTests, setPlatformFoundationGatewayForTests } from '@/modules/foundation/api/gateway'
import type { ActiveExamRule, Qualification, Subject } from '@/modules/foundation/types'
import { useAuthStore } from './auth'
import { QUALIFICATION_STORAGE_KEY, useQualificationStore } from './qualification'

const anonymousScopeKey = `${QUALIFICATION_STORAGE_KEY}:anonymous`
const userScopeKey = (userId: string) => `${QUALIFICATION_STORAGE_KEY}:user:${userId}`
const authStorageKey = 'platform-auth-context-v1'

interface Deferred<T> {
  promise: Promise<T>
  resolve: (value: T) => void
  reject: (reason?: unknown) => void
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve
    reject = promiseReject
  })
  return { promise, resolve, reject }
}

const qualifications: Qualification[] = [
  { id: 'q-advanced', code: 'HIGH_PM', name: '信息系统项目管理师', levelCode: 'ADVANCED', status: 'ENABLED' },
  { id: 'q-advanced-alt', code: 'HIGH_ARCHITECT', name: '系统架构设计师', levelCode: 'ADVANCED', status: 'ENABLED' },
  { id: 'q-intermediate', code: 'MIDDLE_SD', name: '软件设计师', levelCode: 'INTERMEDIATE', status: 'ENABLED' },
  { id: 'q-primary', code: 'LOW_PROGRAMMER', name: '程序员', levelCode: 'PRIMARY', status: 'ENABLED' },
]

const subjectsByQualification: Record<string, Subject[]> = {
  'q-advanced': [
    { id: 's-advanced-comprehensive', qualificationId: 'q-advanced', code: 'COMPREHENSIVE', name: '综合知识', type: 'COMPREHENSIVE', sortOrder: 1, status: 'ENABLED' },
    { id: 's-advanced-case', qualificationId: 'q-advanced', code: 'CASE', name: '案例分析', type: 'CASE', sortOrder: 2, status: 'ENABLED' },
    { id: 's-advanced-essay', qualificationId: 'q-advanced', code: 'ESSAY', name: '论文', type: 'ESSAY', sortOrder: 3, status: 'ENABLED' },
  ],
  'q-advanced-alt': [
    { id: 's-alt-comprehensive', qualificationId: 'q-advanced-alt', code: 'COMPREHENSIVE', name: '综合知识', type: 'COMPREHENSIVE', sortOrder: 1, status: 'ENABLED' },
  ],
  'q-intermediate': [
    { id: 's-intermediate-comprehensive', qualificationId: 'q-intermediate', code: 'COMPREHENSIVE', name: '基础知识', type: 'COMPREHENSIVE', sortOrder: 1, status: 'ENABLED' },
    { id: 's-intermediate-application', qualificationId: 'q-intermediate', code: 'APPLICATION', name: '应用技术', type: 'APPLICATION', sortOrder: 2, status: 'ENABLED' },
  ],
  'q-primary': [
    { id: 's-primary-comprehensive', qualificationId: 'q-primary', code: 'COMPREHENSIVE', name: '基础知识', type: 'COMPREHENSIVE', sortOrder: 1, status: 'ENABLED' },
    { id: 's-primary-application', qualificationId: 'q-primary', code: 'APPLICATION', name: '应用技术', type: 'APPLICATION', sortOrder: 2, status: 'ENABLED' },
  ],
}

function examRule(qualificationId: string): ActiveExamRule {
  return {
    ruleId: `rule-${qualificationId}`,
    ruleCode: `RULE-${qualificationId}`,
    ruleVersion: '1',
    schemaVersion: '1.0',
    qualificationId,
    periodCode: '2026-H2',
    effectiveFrom: '2026-07-01T00:00:00+08:00',
    effectiveTo: '2026-12-31T23:59:59+08:00',
    totalDurationMinutes: 240,
    deadlinePolicy: 'SHARED_TOTAL_DEADLINE',
    subjects: [],
    sourceVerifiedAt: '2026-01-01T00:00:00+08:00',
  }
}

function gateway(overrides: Partial<PlatformFoundationGateway> = {}): PlatformFoundationGateway {
  return {
    listQualifications: async () => structuredClone(qualifications),
    listSubjects: async (qualificationId) => structuredClone(subjectsByQualification[qualificationId] ?? []),
    listChapterNodes: async () => [],
    listSyllabusNodes: async () => [],
    ...overrides,
  }
}

function authGatewayForUser(userId: string, preparationQualificationId?: string): PlatformAuthGateway {
  const context = {
    user: { id: userId, username: userId, displayName: userId, status: 'ENABLED' as const },
    roles: ['STUDENT' as const],
    permissions: ['student:home:view'],
    menus: [],
    featureFlags: {},
  }
  return {
    getCapabilities: async () => ({
      usernamePassword: true,
      mobilePassword: true,
      emailPassword: false,
      wechatQr: false,
      registrationSms: false,
      registrationVerificationRequired: false,
      passwordResetSms: false,
      passwordResetEmail: false,
    }),
    requestRegistrationChallenge: async () => ({ challengeId: 'unused', expiresAt: '2026-07-19T00:00:00Z' }),
    register: async () => ({ userId, username: userId, registrationStatus: 'REGISTERED', createdAt: '2026-07-19T00:00:00Z' }),
    login: async () => ({
      ...context,
      accessExpiresAt: '2026-07-19T01:00:00Z',
      refreshExpiresAt: '2026-07-26T00:00:00Z',
    }),
    refresh: async () => ({ accessExpiresAt: '2026-07-19T01:00:00Z', refreshExpiresAt: '2026-07-26T00:00:00Z' }),
    logout: async () => undefined,
    getCurrentUser: async () => context,
    getProfile: async () => ({
      userId,
      username: userId,
      displayName: userId,
      maskedMobile: null,
      maskedEmail: null,
      preparationQualificationId: preparationQualificationId ?? null,
      status: 'ENABLED',
      version: 0,
      updatedAt: '2026-07-19T00:00:00Z',
    }),
    updateProfile: async (request) => ({
      userId,
      username: userId,
      displayName: request.displayName,
      maskedMobile: null,
      maskedEmail: null,
      status: 'ENABLED',
      version: request.version + 1,
      updatedAt: '2026-07-19T00:01:00Z',
    }),
    requestPasswordResetChallenge: async () => ({ challengeId: 'unused', expiresAt: '2026-07-19T00:00:00Z' }),
    resetPassword: async () => undefined,
    getDemoAccounts: () => [],
  }
}

describe('qualification store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    setPlatformFoundationGatewayForTests(gateway())
    setPlatformAuthGatewayForTests(authGatewayForUser('test-user'))
  })

  it('selects the first server-provided level, qualification, and subject as one context', async () => {
    const store = useQualificationStore()

    await store.load()

    expect(store.selectedLevelCode).toBe('ADVANCED')
    expect(store.selectedQualificationId).toBe('q-advanced')
    expect(store.selectedSubjectId).toBe('s-advanced-comprehensive')
    expect(store.selectedSubject?.qualificationId).toBe(store.selectedQualificationId)
  })

  it('changes level and selects only a qualification and subject from that level', async () => {
    const store = useQualificationStore()
    await store.load()

    await store.selectLevel('INTERMEDIATE')

    expect(store.selectedLevelCode).toBe('INTERMEDIATE')
    expect(store.selectedQualification?.levelCode).toBe('INTERMEDIATE')
    expect(store.selectedSubject?.qualificationId).toBe(store.selectedQualificationId)
    expect(store.selectedSubject?.type).toBe('COMPREHENSIVE')
  })

  it('selects the default enabled subject by stable sortOrder and id', async () => {
    const unordered = [
      { id: 's-z', qualificationId: 'q-advanced', code: 'ESSAY', name: '论文', type: 'ESSAY', sortOrder: 3, status: 'ENABLED' },
      { id: 's-b', qualificationId: 'q-advanced', code: 'CASE', name: '案例分析', type: 'CASE', sortOrder: 1, status: 'ENABLED' },
      { id: 's-a', qualificationId: 'q-advanced', code: 'COMPREHENSIVE', name: '综合知识', type: 'COMPREHENSIVE', sortOrder: 1, status: 'ENABLED' },
    ] as unknown as Subject[]
    setPlatformFoundationGatewayForTests(gateway({
      listSubjects: async () => structuredClone(unordered),
    }))
    const store = useQualificationStore()

    await store.load()

    expect(store.subjects.map((item) => item.id)).toEqual(['s-a', 's-b', 's-z'])
    expect(store.selectedSubjectId).toBe('s-a')
  })

  it('clears incompatible downstream choices before loading the new qualification subjects', async () => {
    const nextSubjects = deferred<Subject[]>()
    setPlatformFoundationGatewayForTests(gateway({
      listSubjects: async (qualificationId) => qualificationId === 'q-intermediate'
        ? nextSubjects.promise
        : structuredClone(subjectsByQualification[qualificationId] ?? []),
    }))
    const store = useQualificationStore()
    await store.load()

    const selection = store.selectLevel('INTERMEDIATE')

    expect(store.selectedLevelCode).toBe('INTERMEDIATE')
    expect(store.selectedQualificationId).toBe('q-intermediate')
    expect(store.selectedSubjectId).toBeNull()
    expect(store.subjects).toEqual([])
    nextSubjects.resolve(structuredClone(subjectsByQualification['q-intermediate'] ?? []))
    await selection
  })

  it('ignores a stale subject response after a newer upstream selection wins', async () => {
    const staleSubjects = deferred<Subject[]>()
    setPlatformFoundationGatewayForTests(gateway({
      listSubjects: async (qualificationId) => qualificationId === 'q-advanced-alt'
        ? staleSubjects.promise
        : structuredClone(subjectsByQualification[qualificationId] ?? []),
    }))
    const store = useQualificationStore()
    await store.load()

    const staleSelection = store.selectQualification('q-advanced-alt')
    await store.selectLevel('INTERMEDIATE')
    staleSubjects.resolve(structuredClone(subjectsByQualification['q-advanced-alt'] ?? []))
    await staleSelection

    expect(store.selectedLevelCode).toBe('INTERMEDIATE')
    expect(store.selectedQualificationId).toBe('q-intermediate')
    expect(store.selectedSubjectId).toBe('s-intermediate-comprehensive')
    expect(store.subjects.every((subject) => subject.qualificationId === 'q-intermediate')).toBe(true)
  })

  it('clears the previous rule immediately and ignores a stale rule response', async () => {
    const staleRule = deferred<ActiveExamRule | null>()
    setPlatformFoundationGatewayForTests(gateway({
      getActiveExamRule: async (qualificationId) => qualificationId === 'q-advanced-alt'
        ? staleRule.promise
        : examRule(qualificationId),
    }))
    const store = useQualificationStore()
    await store.load()
    expect(store.activeExamRule?.qualificationId).toBe('q-advanced')

    const staleSelection = store.selectQualification('q-advanced-alt')

    expect(store.activeExamRule).toBeNull()
    expect(store.ruleStatus).toBe('loading')

    await store.selectLevel('INTERMEDIATE')
    staleRule.resolve(examRule('q-advanced-alt'))
    await staleSelection

    expect(store.selectedQualificationId).toBe('q-intermediate')
    expect(store.activeExamRule?.qualificationId).toBe('q-intermediate')
    expect(store.ruleStatus).toBe('success')
  })

  it('removes an invalid cache and persists only IDs with a server-confirmed timestamp', async () => {
    localStorage.setItem(QUALIFICATION_STORAGE_KEY, '{not-json')
    const store = useQualificationStore()

    await store.load()

    const cached = JSON.parse(localStorage.getItem(anonymousScopeKey) ?? '{}') as Record<string, unknown>
    expect(cached).toMatchObject({
      selectedLevelCode: 'ADVANCED',
      selectedQualificationId: 'q-advanced',
      selectedSubjectId: 's-advanced-comprehensive',
    })
    expect(cached.serverConfirmedAt).toEqual(expect.any(Number))
    expect(cached).not.toHaveProperty('qualification')
    expect(cached).not.toHaveProperty('subject')
    expect(localStorage.getItem(QUALIFICATION_STORAGE_KEY)).toBeNull()
  })

  it('discards cached IDs that no longer exist on the server', async () => {
    localStorage.setItem(QUALIFICATION_STORAGE_KEY, JSON.stringify({
      selectedLevelCode: 'ADVANCED',
      selectedQualificationId: 'q-removed',
      selectedSubjectId: 's-removed',
      serverConfirmedAt: 1,
    }))
    const store = useQualificationStore()

    await store.load()

    expect(store.selectedQualificationId).toBe('q-advanced')
    expect(store.selectedSubjectId).toBe('s-advanced-comprehensive')
    expect(localStorage.getItem(anonymousScopeKey)).not.toContain('removed')
  })

  it('isolates cached qualification selections by authenticated user', async () => {
    localStorage.setItem(authStorageKey, JSON.stringify({ user: { id: 'user-a' } }))
    localStorage.setItem(userScopeKey('user-a'), JSON.stringify({
      selectedLevelCode: 'INTERMEDIATE',
      selectedQualificationId: 'q-intermediate',
      selectedSubjectId: 's-intermediate-application',
      serverConfirmedAt: 1,
    }))
    localStorage.setItem(userScopeKey('user-b'), JSON.stringify({
      selectedLevelCode: 'PRIMARY',
      selectedQualificationId: 'q-primary',
      selectedSubjectId: 's-primary-application',
      serverConfirmedAt: 1,
    }))
    const store = useQualificationStore()

    await store.load()

    expect(store.selectedQualificationId).toBe('q-intermediate')
    expect(store.selectedSubjectId).toBe('s-intermediate-application')

    store.clear()
    setActivePinia(createPinia())
    localStorage.setItem(authStorageKey, JSON.stringify({ user: { id: 'user-b' } }))
    const nextStore = useQualificationStore()

    await nextStore.load()

    expect(nextStore.selectedQualificationId).toBe('q-primary')
    expect(nextStore.selectedSubjectId).toBe('s-primary-application')
  })

  it('uses the authenticated user preparation qualification before that user cache', async () => {
    localStorage.setItem(authStorageKey, JSON.stringify({ user: { id: 'user-a' } }))
    localStorage.setItem(userScopeKey('user-a'), JSON.stringify({
      selectedLevelCode: 'ADVANCED',
      selectedQualificationId: 'q-advanced',
      selectedSubjectId: 's-advanced-comprehensive',
      serverConfirmedAt: 1,
    }))
    setPlatformAuthGatewayForTests(authGatewayForUser('user-a', 'q-advanced-alt'))
    const store = useQualificationStore()

    await store.load()

    expect(store.selectedLevelCode).toBe('ADVANCED')
    expect(store.selectedQualificationId).toBe('q-advanced-alt')
    expect(store.selectedSubjectId).toBe('s-alt-comprehensive')
  })

  it('isolates A and B in one Pinia instance and ignores A responses after session reset', async () => {
    const delayedASelection = deferred<Subject[]>()
    localStorage.setItem(authStorageKey, JSON.stringify({ user: { id: 'user-a' } }))
    localStorage.setItem(userScopeKey('user-a'), JSON.stringify({
      selectedLevelCode: 'ADVANCED',
      selectedQualificationId: 'q-advanced',
      selectedSubjectId: 's-advanced-comprehensive',
      serverConfirmedAt: 1,
    }))
    localStorage.setItem(userScopeKey('user-b'), JSON.stringify({
      selectedLevelCode: 'PRIMARY',
      selectedQualificationId: 'q-primary',
      selectedSubjectId: 's-primary-application',
      serverConfirmedAt: 1,
    }))
    localStorage.setItem(anonymousScopeKey, JSON.stringify({
      selectedLevelCode: 'ADVANCED',
      selectedQualificationId: 'q-advanced-alt',
      selectedSubjectId: 's-alt-comprehensive',
      serverConfirmedAt: 1,
    }))
    setPlatformFoundationGatewayForTests(gateway({
      listSubjects: async (qualificationId) => qualificationId === 'q-intermediate'
        ? delayedASelection.promise
        : structuredClone(subjectsByQualification[qualificationId] ?? []),
    }))
    const authStore = useAuthStore()
    authStore.user = { id: 'user-a', username: 'user-a', displayName: 'User A', status: 'ENABLED' }
    const qualificationStore = useQualificationStore()
    await qualificationStore.load()
    expect(qualificationStore.selectedQualificationId).toBe('q-advanced')

    const staleARequest = qualificationStore.selectLevel('INTERMEDIATE')
    expect(qualificationStore.selectedQualificationId).toBe('q-intermediate')

    authStore.clearSession()
    expect(qualificationStore.status).toBe('idle')
    expect(qualificationStore.selectedQualificationId).toBeNull()
    expect(qualificationStore.selectedSubjectId).toBeNull()

    setPlatformAuthGatewayForTests(authGatewayForUser('user-b'))
    await authStore.login({ identifier: 'user-b', password: 'secret' })
    await qualificationStore.load()
    expect(qualificationStore.selectedQualificationId).toBe('q-primary')
    expect(qualificationStore.selectedSubjectId).toBe('s-primary-application')

    delayedASelection.resolve(structuredClone(subjectsByQualification['q-intermediate'] ?? []))
    await staleARequest

    expect(qualificationStore.selectedQualificationId).toBe('q-primary')
    expect(qualificationStore.selectedSubjectId).toBe('s-primary-application')
    expect(localStorage.getItem(userScopeKey('user-a'))).not.toBeNull()
    expect(localStorage.getItem(userScopeKey('user-b'))).not.toBeNull()
    expect(localStorage.getItem(anonymousScopeKey)).not.toBeNull()
  })

  it('keeps the selected qualification but exposes an error when subjects fail', async () => {
    const unsafeError = new Error('科目服务暂时不可用') as Error & { traceId?: string }
    unsafeError.traceId = 'trace-subject-error'
    setPlatformFoundationGatewayForTests(gateway({
      listSubjects: async () => {
        throw unsafeError
      },
    }))
    const store = useQualificationStore()

    await store.load()

    expect(store.status).toBe('success')
    expect(store.subjectStatus).toBe('error')
    expect(store.selectedQualificationId).toBe('q-advanced')
    expect(store.selectedSubjectId).toBeNull()
    expect(store.errorMessage).toBe(toSafeUserMessage(unsafeError))
    expect(store.errorTraceId).toBe('trace-subject-error')
  })
})
