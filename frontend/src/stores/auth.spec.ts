import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiClientError, PLATFORM_TOKEN_STORAGE_KEY } from '@/api/client'
import type { PlatformAuthGateway } from '@/modules/foundation/api/platform-auth.gateway'
import { setPlatformAuthGatewayForTests } from '@/modules/foundation/api/gateway'
import type { PlatformAuthContext, PlatformLoginResult } from '@/modules/foundation/types'
import { PLATFORM_AUTH_STORAGE_KEY, useAuthStore } from './auth'

const studentContext: PlatformAuthContext = {
  user: { id: 'student-1', username: 'student', displayName: '学习学员', status: 'ENABLED' },
  roles: ['STUDENT'],
  permissions: ['student:home:view', 'exam-simulator:enter'],
  featureFlags: { 'student.portal': true, 'exam.simulator': true },
  menus: [
    { code: 'student.home', name: '首页', routePath: '/app/home' },
    { code: 'exam.simulator', name: '机考', routePath: '/exam-simulator' },
    { code: 'unsafe.remote', name: '远程组件', routePath: '/remote-component' },
  ],
}

function gateway(overrides: Partial<PlatformAuthGateway> = {}): PlatformAuthGateway {
  return {
    getCapabilities: async () => ({
      usernamePassword: true,
      mobilePassword: true,
      emailPassword: false,
      wechatQr: false,
      registrationSms: true,
      registrationVerificationRequired: true,
      passwordResetSms: true,
      smsLogin: true,
      passwordResetEmail: false,
    }),
    requestRegistrationChallenge: async () => ({
      challengeId: 'challenge-1',
      expiresAt: '2026-07-16T10:10:00+08:00',
    }),
    register: async (request) => ({
      userId: 'registered-user',
      username: request.phone,
      registrationStatus: 'REGISTERED',
      createdAt: '2026-07-16T10:00:00+08:00',
    }),
    login: async (): Promise<PlatformLoginResult> => ({
      ...studentContext,
      accessExpiresAt: '2026-07-16T10:30:00+08:00',
      refreshExpiresAt: '2026-07-23T10:00:00+08:00',
    }),
    requestSmsLoginChallenge: async () => ({
      challengeId: 'sms-challenge-1',
      expiresAt: '2026-07-16T10:10:00+08:00',
    }),
    smsLogin: async (): Promise<PlatformLoginResult> => ({
      ...studentContext,
      accessExpiresAt: '2026-07-16T10:30:00+08:00',
      refreshExpiresAt: '2026-07-23T10:00:00+08:00',
    }),
    refresh: async () => ({
      accessExpiresAt: '2026-07-16T10:30:00+08:00',
      refreshExpiresAt: '2026-07-23T10:00:00+08:00',
    }),
    logout: async () => undefined,
    getCurrentUser: async () => studentContext,
    getProfile: async () => ({
      userId: studentContext.user.id,
      username: studentContext.user.username,
      displayName: studentContext.user.displayName,
      maskedMobile: null,
      maskedEmail: null,
      status: studentContext.user.status,
      version: 0,
      updatedAt: '2026-07-16T10:00:00+08:00',
    }),
    updateProfile: async (request) => ({
      userId: studentContext.user.id,
      username: studentContext.user.username,
      displayName: request.displayName,
      maskedMobile: null,
      maskedEmail: null,
      status: studentContext.user.status,
      version: request.version + 1,
      updatedAt: '2026-07-16T10:01:00+08:00',
    }),
    requestPasswordResetChallenge: async () => ({
      challengeId: 'reset-challenge-1',
      expiresAt: '2026-07-16T10:10:00+08:00',
    }),
    resetPassword: async () => undefined,
    getDemoAccounts: () => [
      { role: 'STUDENT', identifier: '13800138000', password: 'fixture-password', displayName: '学习学员' },
    ],
    ...overrides,
  }
}

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve
    reject = promiseReject
  })
  return { promise, resolve, reject }
}

describe('platform auth store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    sessionStorage.clear()
    setPlatformAuthGatewayForTests(gateway())
  })

  it('logs in through cookie session, filters backend menus and never persists token or password', async () => {
    const store = useAuthStore()

    await store.login({ identifier: '13800138000', password: 'fixture-password' })

    expect(store.isAuthenticated).toBe(true)
    expect(store.homePath).toBe('/app/modules')
    expect(store.menus.map((menu) => menu.code)).toEqual(['student.home', 'exam.simulator'])
    expect(localStorage.getItem(PLATFORM_TOKEN_STORAGE_KEY)).toBeNull()
    expect(localStorage.getItem(PLATFORM_AUTH_STORAGE_KEY)).not.toContain('fixture-password')
  })

  it('restores the authoritative current user with /auth/me semantics without local token', async () => {
    const getCurrentUser = vi.fn(async () => studentContext)
    setPlatformAuthGatewayForTests(gateway({ getCurrentUser }))
    const store = useAuthStore()

    await store.initialize()

    expect(getCurrentUser).toHaveBeenCalledOnce()
    expect(store.user?.displayName).toBe('学习学员')
    expect(store.isAuthenticated).toBe(true)
  })

  it('caches and coalesces the runtime capabilities request across auth routes', async () => {
    let resolveCapabilities!: (value: Awaited<ReturnType<PlatformAuthGateway['getCapabilities']>>) => void
    const getCapabilities = vi.fn(() => new Promise<Awaited<ReturnType<PlatformAuthGateway['getCapabilities']>>>((resolve) => {
      resolveCapabilities = resolve
    }))
    setPlatformAuthGatewayForTests(gateway({ getCapabilities }))
    const store = useAuthStore()

    const loginRequest = store.loadCapabilities()
    const registerRequest = store.loadCapabilities()
    resolveCapabilities({
      usernamePassword: true,
      mobilePassword: true,
      emailPassword: true,
      wechatQr: false,
      registrationSms: true,
      registrationVerificationRequired: true,
      passwordResetSms: true,
      passwordResetEmail: true,
    })

    await expect(Promise.all([loginRequest, registerRequest])).resolves.toHaveLength(2)
    await store.loadCapabilities()
    expect(getCapabilities).toHaveBeenCalledOnce()
  })

  it('does not cache a failed capabilities request', async () => {
    const getCapabilities = vi
      .fn<PlatformAuthGateway['getCapabilities']>()
      .mockRejectedValueOnce(new ApiClientError('temporary', 503, 'SERVICE_UNAVAILABLE'))
      .mockResolvedValueOnce(await gateway().getCapabilities())
    setPlatformAuthGatewayForTests(gateway({ getCapabilities }))
    const store = useAuthStore()

    await expect(store.loadCapabilities()).rejects.toMatchObject({ status: 503 })
    await expect(store.loadCapabilities()).resolves.toMatchObject({ mobilePassword: true })
    expect(getCapabilities).toHaveBeenCalledTimes(2)
  })

  it('lands the dedicated reviewer in the permitted question workspace', async () => {
    const reviewerContext: PlatformAuthContext = {
      user: { id: 'reviewer-1', username: 'question_reviewer', displayName: '题库验收审核发布人', status: 'ENABLED' },
      roles: ['QUESTION_REVIEWER', 'QUESTION_PUBLISHER'],
      permissions: ['question:read', 'question:review', 'question:publish'],
      featureFlags: { 'admin.questions': true },
      menus: [],
    }
    setPlatformAuthGatewayForTests(gateway({
      login: async () => reviewerContext,
      getCurrentUser: async () => reviewerContext,
    }))
    const store = useAuthStore()

    await store.login({ identifier: '13800138000', password: 'fixture-password' })

    expect(store.homePath).toBe('/admin/questions')
    expect(store.menus.map((menu) => menu.routePath)).toEqual(['/admin/questions', '/admin/practice-reviews'])
  })

  it('tries refresh once when /auth/me initially fails', async () => {
    const refresh = vi.fn(async () => ({
      accessExpiresAt: '2026-07-16T10:30:00+08:00',
      refreshExpiresAt: '2026-07-23T10:00:00+08:00',
    }))
    const getCurrentUser = vi
      .fn<() => Promise<PlatformAuthContext>>()
      .mockRejectedValueOnce(new ApiClientError('登录状态已失效', 401, 'AUTH_SESSION_EXPIRED'))
      .mockResolvedValueOnce(studentContext)
    setPlatformAuthGatewayForTests(gateway({ getCurrentUser, refresh }))
    const store = useAuthStore()

    await store.initialize()

    expect(refresh).toHaveBeenCalledOnce()
    expect(getCurrentUser).toHaveBeenCalledTimes(2)
    expect(store.isAuthenticated).toBe(true)
  })

  it('coalesces concurrent normal and forced initialization into one request', async () => {
    const currentUser = deferred<PlatformAuthContext>()
    const getCurrentUser = vi.fn(() => currentUser.promise)
    const refresh = vi.fn()
    setPlatformAuthGatewayForTests(gateway({ getCurrentUser, refresh }))
    const store = useAuthStore()

    const normal = store.initialize()
    const forced = store.initialize({ force: true })
    expect(getCurrentUser).toHaveBeenCalledOnce()

    currentUser.resolve(studentContext)
    await Promise.all([normal, forced])

    expect(getCurrentUser).toHaveBeenCalledOnce()
    expect(refresh).not.toHaveBeenCalled()
    expect(store.isAuthenticated).toBe(true)
  })

  it('shares one refresh rotation across concurrent forced initialization', async () => {
    const refreshResult = deferred<Awaited<ReturnType<PlatformAuthGateway['refresh']>>>()
    const getCurrentUser = vi
      .fn<() => Promise<PlatformAuthContext>>()
      .mockRejectedValueOnce(new ApiClientError('expired', 401, 'AUTH_SESSION_EXPIRED'))
      .mockResolvedValueOnce(studentContext)
    const refresh = vi.fn(() => refreshResult.promise)
    setPlatformAuthGatewayForTests(gateway({ getCurrentUser, refresh }))
    const store = useAuthStore()

    const first = store.initialize({ force: true })
    const second = store.initialize({ force: true })
    await Promise.resolve()
    expect(refresh).toHaveBeenCalledOnce()

    refreshResult.resolve({
      accessExpiresAt: '2026-07-16T10:30:00+08:00',
      refreshExpiresAt: '2026-07-23T10:00:00+08:00',
    })
    await Promise.all([first, second])

    expect(getCurrentUser).toHaveBeenCalledTimes(2)
    expect(refresh).toHaveBeenCalledOnce()
    expect(store.isAuthenticated).toBe(true)
  })

  it('does not restore a user when initialize resolves after logout', async () => {
    const currentUser = deferred<PlatformAuthContext>()
    const getCurrentUser = vi.fn(() => currentUser.promise)
    const logout = vi.fn(async () => undefined)
    setPlatformAuthGatewayForTests(gateway({ getCurrentUser, logout }))
    const store = useAuthStore()

    const pending = store.initialize({ force: true })
    await store.logout()
    currentUser.resolve(studentContext)
    await pending

    expect(store.isAuthenticated).toBe(false)
    expect(store.user).toBeNull()
    expect(store.initialized).toBe(true)
  })

  it('does not let an old initialize result overwrite a newer login', async () => {
    const currentUser = deferred<PlatformAuthContext>()
    const newerContext: PlatformAuthContext = {
      ...studentContext,
      user: { ...studentContext.user, id: 'student-new', displayName: 'New session user' },
    }
    const getCurrentUser = vi.fn(() => currentUser.promise)
    const login = vi.fn(async () => newerContext)
    setPlatformAuthGatewayForTests(gateway({ getCurrentUser, login }))
    const store = useAuthStore()

    const pending = store.initialize({ force: true })
    await store.login({ identifier: '13800138000', password: 'fixture-password' })
    currentUser.resolve(studentContext)
    await pending

    expect(store.user?.id).toBe('student-new')
    expect(store.user?.displayName).toBe('New session user')
  })

  it('starts a fresh server validation after the previous forced request completes', async () => {
    const getCurrentUser = vi.fn(async () => studentContext)
    setPlatformAuthGatewayForTests(gateway({ getCurrentUser }))
    const store = useAuthStore()

    await store.initialize({ force: true })
    await store.initialize({ force: true })

    expect(getCurrentUser).toHaveBeenCalledTimes(2)
  })

  it('preserves the current context and remains retryable after a transient bootstrap failure', async () => {
    const getCurrentUser = vi.fn(async () => {
      throw new ApiClientError('系统暂时不可用', 503, 'SERVICE_UNAVAILABLE')
    })
    const refresh = vi.fn()
    setPlatformAuthGatewayForTests(gateway({ getCurrentUser, refresh }))
    const store = useAuthStore()
    await store.login({ identifier: '13800138000', password: 'fixture-password' })
    store.initialized = false

    await expect(store.initialize()).rejects.toMatchObject({ status: 503 })

    expect(refresh).not.toHaveBeenCalled()
    expect(store.isAuthenticated).toBe(true)
    expect(store.initialized).toBe(false)
  })

  it('registers without creating an authenticated frontend session', async () => {
    const store = useAuthStore()

    const result = await store.register({
      nickname: 'new-user',
      phone: '+8613800138000',
      password: 'secure-password',
      challengeId: 'challenge-1',
      verificationCode: '246810',
      requestId: 'request-1',
    })

    expect(result.registrationStatus).toBe('REGISTERED')
    expect(store.isAuthenticated).toBe(false)
    expect(localStorage.getItem(PLATFORM_TOKEN_STORAGE_KEY)).toBeNull()
  })

  it('clears token, user, permissions and menus after logout', async () => {
    const logout = vi.fn(async () => undefined)
    setPlatformAuthGatewayForTests(gateway({ logout }))
    const store = useAuthStore()
    await store.login({ identifier: '13800138000', password: 'fixture-password' })

    await store.logout()

    expect(logout).toHaveBeenCalledOnce()
    expect(localStorage.getItem(PLATFORM_TOKEN_STORAGE_KEY)).toBeNull()
    expect(localStorage.getItem(PLATFORM_AUTH_STORAGE_KEY)).toBeNull()
    expect(store.user).toBeNull()
    expect(store.permissions).toEqual([])
    expect(store.menus).toEqual([])
  })
})
