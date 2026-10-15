import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiClientError, platformApiClient } from '@/api/client'
import { HttpPlatformAuthGateway } from './http-platform-auth.gateway'

function envelope<T>(data: T) {
  return {
    data: {
      success: true,
      errorCode: 'OK',
      message: 'success',
      data,
      traceId: 'trace-auth',
    },
  }
}

function authContext() {
  return {
    user: {
      id: 101,
      username: 'student-user',
      displayName: '学习学员',
      maskedMobile: '138****8000',
      status: 'ENABLED',
    },
    roles: ['STUDENT'],
    permissions: ['platform:module-home:view', 'practice:workspace:view'],
    menus: [{ code: 'practice.start', name: '刷题练习', routePath: '/app/practice/start' }],
    featureFlags: { 'student.portal': true, 'practice.workspace': true },
    accessExpiresAt: '2026-07-16T11:00:00Z',
    refreshExpiresAt: '2026-07-23T10:30:00Z',
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

describe('HttpPlatformAuthGateway', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('maps the backend provider capability objects to stable frontend booleans', async () => {
    vi.spyOn(platformApiClient, 'get').mockResolvedValue(envelope({
      usernamePassword: { provider: 'USERNAME_PASSWORD', status: 'AVAILABLE', message: '可用' },
      mobile: { provider: 'DEV_MOBILE', status: 'AVAILABLE', message: '本地投递' },
      email: { provider: 'EMAIL', status: 'DISABLED', message: '未启用' },
      wechat: { provider: 'WECHAT', status: 'UNAVAILABLE', message: '未配置' },
      credentialPolicy: {
        nickname: { minLength: 1, maxLength: 32 },
        password: { minLength: 15, maxLength: 64 },
      },
    }) as never)

    await expect(new HttpPlatformAuthGateway().getCapabilities()).resolves.toEqual({
      usernamePassword: true,
      mobilePassword: true,
      emailPassword: false,
      wechatQr: false,
      registrationSms: true,
      registrationVerificationRequired: true,
      passwordResetSms: true,
      smsLogin: true,
      passwordResetEmail: false,
      devSeedUsernameLoginEnabled: undefined,
      adminRegistrationEnabled: false,
      credentialPolicy: {
        nickname: { minLength: 1, maxLength: 32 },
        password: { minLength: 15, maxLength: 64 },
      },
    })
  })

  it('does not use username-password capability as phone or email login availability', async () => {
    vi.spyOn(platformApiClient, 'get').mockResolvedValue(envelope({
      usernamePassword: { provider: 'USERNAME_PASSWORD', status: 'AVAILABLE' },
      mobile: { provider: 'DEV_MOBILE', status: 'DISABLED' },
      email: { provider: 'EMAIL', status: 'DISABLED' },
    }) as never)

    await expect(new HttpPlatformAuthGateway().getCapabilities()).resolves.toMatchObject({
      usernamePassword: true,
      mobilePassword: false,
      emailPassword: false,
    })
  })

  it('accepts the dedicated question reviewer and publisher roles from the real auth context', async () => {
    vi.spyOn(platformApiClient, 'get').mockResolvedValue(envelope({
      ...authContext(),
      user: {
        ...authContext().user,
        username: 'question_reviewer',
        displayName: '题库验收审核发布人',
      },
      roles: ['QUESTION_REVIEWER', 'QUESTION_PUBLISHER'],
      permissions: ['question:read', 'question:review', 'question:publish'],
      menus: [],
      featureFlags: { 'admin.questions': true },
    }) as never)

    const context = await new HttpPlatformAuthGateway().getCurrentUser()

    expect(context.roles).toEqual(['QUESTION_REVIEWER', 'QUESTION_PUBLISHER'])
    expect(context.permissions).toEqual(['question:read', 'question:review', 'question:publish'])
  })

  it('gets a fresh csrf cookie before each sequential unsafe auth request', async () => {
    const calls: string[] = []
    vi.spyOn(platformApiClient, 'get').mockImplementation(async (url: string) => {
      calls.push(`GET ${url}`)
      return { data: '' } as never
    })
    vi.spyOn(platformApiClient, 'post').mockImplementation(async (url: string) => {
      calls.push(`POST ${url}`)
      return url === '/auth/login'
        ? envelope(authContext()) as never
        : envelope({
            accessExpiresAt: '2026-07-16T11:00:00Z',
            refreshExpiresAt: '2026-07-23T10:30:00Z',
          }) as never
    })
    const gateway = new HttpPlatformAuthGateway()

    const login = await gateway.login({ identifier: '13800138000', password: 'password-value' })
    await gateway.refresh()

    expect(login.user.id).toBe('101')
    expect(calls).toEqual([
      'GET /auth/csrf',
      'POST /auth/login',
      'GET /auth/csrf',
      'POST /auth/refresh',
    ])
    expect(platformApiClient.post).toHaveBeenCalledWith('/auth/login', {
      identifier: '13800138000',
      password: 'password-value',
    })
  })

  it('shares only an in-flight csrf request across concurrent unsafe writes', async () => {
    const csrf = deferred<never>()
    const get = vi.spyOn(platformApiClient, 'get').mockReturnValue(csrf.promise)
    const post = vi.spyOn(platformApiClient, 'post').mockImplementation(async (url: string) =>
      url === '/auth/login'
        ? envelope(authContext()) as never
        : envelope({
            accessExpiresAt: '2026-07-16T11:00:00Z',
            refreshExpiresAt: '2026-07-23T10:30:00Z',
          }) as never)
    const gateway = new HttpPlatformAuthGateway()

    const login = gateway.login({ identifier: '13800138000', password: 'password-value' })
    const refresh = gateway.refresh()

    expect(get).toHaveBeenCalledTimes(1)
    expect(post).not.toHaveBeenCalled()
    csrf.resolve({ data: '' } as never)
    await Promise.all([login, refresh])
    expect(post).toHaveBeenCalledTimes(2)
  })

  it('refreshes the csrf cookie and retries when a stale tunnel cookie is rejected', async () => {
    const get = vi.spyOn(platformApiClient, 'get').mockResolvedValue({ data: '' } as never)
    const post = vi.spyOn(platformApiClient, 'post')
      .mockRejectedValueOnce(new ApiClientError('csrf failed', 403, 'CSRF_INVALID'))
      .mockResolvedValueOnce(envelope(authContext()) as never)

    const result = await new HttpPlatformAuthGateway().login({
      identifier: '13800138000',
      password: 'password-value',
    })

    expect(result.user.id).toBe('101')
    expect(get).toHaveBeenCalledTimes(2)
    expect(post).toHaveBeenCalledTimes(2)
  })

  it('uses dedicated SMS login endpoints and maps the formal auth context', async () => {
    vi.spyOn(platformApiClient, 'get').mockResolvedValue({ data: '' } as never)
    const post = vi.spyOn(platformApiClient, 'post').mockImplementation(async (url: string) =>
      url === '/auth/sms-login-challenges'
        ? envelope({ challengeId: 'challenge-1', expiresAt: '2026-07-16T11:00:00Z', retryAfterSeconds: 60 }) as never
        : envelope(authContext()) as never)
    const gateway = new HttpPlatformAuthGateway()

    await gateway.requestSmsLoginChallenge({ mobile: '+8613800138000', requestId: 'request-1' })
    const context = await gateway.smsLogin({
      mobile: '+8613800138000',
      challengeId: 'challenge-1',
      verificationCode: '246810',
      requestId: 'request-2',
    })

    expect(context.user.id).toBe('101')
    expect(post).toHaveBeenNthCalledWith(1, '/auth/sms-login-challenges', expect.any(Object))
    expect(post).toHaveBeenNthCalledWith(2, '/auth/sms-login', expect.any(Object))
  })

  it('accepts backend 204 responses for logout and password reset', async () => {
    const get = vi.spyOn(platformApiClient, 'get').mockResolvedValue({ data: '' } as never)
    const post = vi.spyOn(platformApiClient, 'post').mockResolvedValue({
      data: '',
      status: 204,
    } as never)
    const gateway = new HttpPlatformAuthGateway()

    await expect(gateway.logout()).resolves.toBeUndefined()
    await expect(gateway.resetPassword({
      challengeId: '00000000-0000-0000-0000-000000000001',
      verificationCode: '246810',
      newPassword: 'long-password-value',
      requestId: '00000000-0000-0000-0000-000000000002',
    })).resolves.toBeUndefined()

    expect(get).toHaveBeenCalledTimes(2)
    expect(post).toHaveBeenNthCalledWith(1, '/auth/logout', undefined)
    expect(post).toHaveBeenNthCalledWith(2, '/auth/password/reset', expect.any(Object))
  })
})
