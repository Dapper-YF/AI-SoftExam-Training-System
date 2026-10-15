import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiClientError } from '@/api/client'
import { setPlatformAuthGatewayForTests } from '../api/gateway'
import ForgotPasswordView from './ForgotPasswordView.vue'
import LoginView from './LoginView.vue'
import StatusView from './StatusView.vue'
import type { PlatformAuthGateway } from '../api/platform-auth.gateway'
import type { PlatformAuthContext } from '../types'

const readDevVerificationCode = vi.hoisted(() => vi.fn())

vi.mock('../api/dev-auth-challenge', () => ({
  readDevVerificationCode,
}))

const authViewFiles = [
  resolve('src/modules/foundation/views/LoginView.vue'),
  resolve('src/modules/foundation/views/ForgotPasswordView.vue'),
]
const globalStyleFile = resolve('src/styles/global.css')

const authContext: PlatformAuthContext = {
  user: { id: 'student-1', username: 'student', displayName: '学习学员', status: 'ENABLED' },
  roles: ['STUDENT'],
  permissions: ['platform:module-home:view'],
  menus: [],
  featureFlags: { 'student.portal': true },
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
      credentialPolicy: {
        nickname: { minLength: 1, maxLength: 32 },
        password: { minLength: 8, maxLength: 64 },
      },
    }),
    requestRegistrationChallenge: vi.fn(async () => ({ challengeId: 'challenge-1', expiresAt: '2026-07-16T10:10:00+08:00' })),
    register: vi.fn(async (request) => ({ userId: 'user-1', username: request.phone, registrationStatus: 'REGISTERED' as const, createdAt: '2026-07-16T10:00:00+08:00' })),
    login: vi.fn(async () => authContext),
    requestSmsLoginChallenge: vi.fn(async () => ({ challengeId: 'sms-1', expiresAt: '2026-07-16T10:10:00+08:00', retryAfterSeconds: 60 })),
    smsLogin: vi.fn(async () => authContext),
    refresh: vi.fn(async () => ({ accessExpiresAt: '2026-07-16T10:30:00+08:00', refreshExpiresAt: '2026-07-23T10:00:00+08:00' })),
    logout: vi.fn(async () => undefined),
    getCurrentUser: vi.fn(async () => authContext),
    getProfile: vi.fn(async () => ({
      userId: authContext.user.id,
      username: authContext.user.username,
      displayName: authContext.user.displayName,
      maskedMobile: null,
      maskedEmail: null,
      status: authContext.user.status,
      version: 0,
      updatedAt: '2026-07-16T10:00:00+08:00',
    })),
    updateProfile: vi.fn(async (request) => ({
      userId: authContext.user.id,
      username: authContext.user.username,
      displayName: request.displayName,
      maskedMobile: null,
      maskedEmail: null,
      status: authContext.user.status,
      version: request.version + 1,
      updatedAt: '2026-07-16T10:01:00+08:00',
    })),
    requestPasswordResetChallenge: vi.fn(async () => ({ challengeId: 'reset-1', expiresAt: '2026-07-16T10:10:00+08:00' })),
    resetPassword: vi.fn(async () => undefined),
    getDemoAccounts: () => [{ role: 'STUDENT', identifier: 'demo@example.com', password: 'fixture-password', displayName: '演示账号' }],
    ...overrides,
  }
}

function mountWithRouter(component: object, initialRoute = '/login') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/login', component: LoginView },
      { path: '/register', redirect: '/login?mode=register' },
      { path: '/forgot-password', component: ForgotPasswordView },
      { path: '/app/modules', component: { template: '<div />' } },
      { path: '/app/practice/start', component: { template: '<div />' } },
    ],
  })
  router.push(initialRoute)

  return mount(component, {
    global: { plugins: [ElementPlus, router] },
  })
}

describe('real auth public views', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    sessionStorage.clear()
    readDevVerificationCode.mockReset()
    readDevVerificationCode.mockResolvedValue('246810')
  })

  it('shows the phone or email login identifier and never renders demo credentials', async () => {
    setPlatformAuthGatewayForTests(gateway())
    const wrapper = mountWithRouter(LoginView)
    await flushPromises()

    expect(wrapper.get('[data-testid="login-methods"]').text()).toBe('登录')
    expect(wrapper.get('[data-testid="login-mode-switch"]').text()).toContain('账号密码登录')
    expect(wrapper.get('[data-testid="login-mode-switch"]').text()).toContain('短信验证码登录')
    expect(wrapper.get('[data-testid="platform-identifier"]').attributes('placeholder')).toBe('请输入手机号或邮箱')
    expect(wrapper.findAll('.el-form-item__label').map((label) => label.text())).toContain('手机号或邮箱')
    expect(wrapper.text()).not.toContain('手机号或邮箱登录')
    expect(wrapper.text()).not.toContain('真实平台账号登录')
    expect(wrapper.text()).not.toContain('大临手机号')
    expect(wrapper.text()).not.toContain('账号/用户名')
    expect(wrapper.text()).not.toContain('微信扫码')
    expect(wrapper.text()).not.toContain('开发账号')
    expect(wrapper.get('[data-testid="register-link"]').text()).toBe('注册账号')
    expect(wrapper.get('[data-testid="forgot-password-link"]').text()).toBe('忘记密码')
    expect(wrapper.text()).not.toContain('fixture-password')
    expect(wrapper.find('[data-testid="login-mode-development"]').exists()).toBe(false)
  })

  it('points every visible public-home exit at the new root portal', async () => {
    setPlatformAuthGatewayForTests(gateway())
    const login = mountWithRouter(LoginView)
    await flushPromises()
    expect(login.get('[data-testid="login-home-link"]').attributes('href')).toBe('/')

    const register = mountWithRouter(LoginView, '/login?mode=register')
    await flushPromises()
    expect(register.get('[data-testid="login-home-link"]').attributes('href')).toBe('/')

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div data-testid="new-public-home" />' } },
        { path: '/404', component: { template: '<div />' } },
      ],
    })
    await router.push('/404')
    await router.isReady()
    const status = mount(StatusView, {
      props: { code: '404', title: '页面不存在', description: '访问地址无效' },
      global: { plugins: [ElementPlus, router] },
    })

    await status.get('[data-testid="status-home-link"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/')
  })

  it('keeps phone and email password login available independently of delivery providers', async () => {
    setPlatformAuthGatewayForTests(gateway({
      getCapabilities: async () => ({
        usernamePassword: true,
        mobilePassword: false,
        emailPassword: false,
        wechatQr: false,
        registrationSms: false,
        registrationVerificationRequired: true,
        passwordResetSms: false,
        passwordResetEmail: false,
        devSeedUsernameLoginEnabled: false,
        credentialPolicy: {
          nickname: { minLength: 1, maxLength: 32 },
          password: { minLength: 8, maxLength: 64 },
        },
      }),
    }))
    const wrapper = mountWithRouter(LoginView)
    await flushPromises()

    expect(wrapper.find('[data-testid="password-login-unavailable"]').exists()).toBe(false)
  })

  it('never exposes development accounts even when the backend capability is enabled', async () => {
    setPlatformAuthGatewayForTests(gateway({
      getCapabilities: async () => ({
        usernamePassword: true,
        mobilePassword: true,
        emailPassword: false,
        wechatQr: false,
        registrationSms: false,
        registrationVerificationRequired: false,
        passwordResetSms: false,
        passwordResetEmail: false,
        devSeedUsernameLoginEnabled: true,
        credentialPolicy: {
          nickname: { minLength: 1, maxLength: 32 },
          password: { minLength: 8, maxLength: 64 },
        },
      }),
    }))
    const wrapper = mountWithRouter(LoginView)
    await flushPromises()

    expect(wrapper.text()).not.toContain('开发账号')
    expect(wrapper.find('[data-testid="login-mode-development"]').exists()).toBe(false)
  })

  it('rejects usernames when the development login capability is disabled', async () => {
    const login = vi.fn(async () => authContext)
    setPlatformAuthGatewayForTests(gateway({ login }))
    const wrapper = mountWithRouter(LoginView)
    await flushPromises()

    expect(wrapper.find('[data-testid="login-mode-development"]').exists()).toBe(false)
    await wrapper.get('[data-testid="platform-identifier"]').setValue('student')
    await wrapper.get('[data-testid="platform-password"]').setValue('local-password')
    await wrapper.get('[data-testid="platform-login-submit"]').trigger('click')
    await flushPromises()

    expect(login).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('手机号或邮箱格式不正确')
  })

  it('requests and submits SMS login using only a mainland mobile number', async () => {
    const requestSmsLoginChallenge = vi.fn(async () => ({
      challengeId: 'sms-1',
      expiresAt: '2026-07-16T10:10:00+08:00',
      retryAfterSeconds: 60,
    }))
    const smsLogin = vi.fn(async () => authContext)
    setPlatformAuthGatewayForTests(gateway({ requestSmsLoginChallenge, smsLogin }))
    const wrapper = mountWithRouter(LoginView)
    await flushPromises()

    await wrapper.get('[data-testid="login-mode-sms"]').trigger('click')
    await wrapper.get('[data-testid="sms-login-mobile"]').setValue('13800138000')
    await wrapper.get('[data-testid="sms-login-request-code"]').trigger('click')
    await flushPromises()
    expect((wrapper.get('[data-testid="sms-login-code"]').element as HTMLInputElement).value).toBe('246810')
    await wrapper.get('[data-testid="platform-login-submit"]').trigger('click')
    await flushPromises()

    expect(requestSmsLoginChallenge).toHaveBeenCalledWith(expect.objectContaining({ mobile: '+8613800138000' }))
    expect(smsLogin).toHaveBeenCalledWith(expect.objectContaining({
      mobile: '+8613800138000',
      challengeId: 'sms-1',
      verificationCode: '246810',
    }))
  })

  it('renders a safe Chinese login error without trace or server details', async () => {
    setPlatformAuthGatewayForTests(gateway({
      login: vi.fn(async () => {
        throw new ApiClientError('internal SQL repository detail', 401, 'AUTH_INVALID_CREDENTIALS', 'trace-login-1')
      }),
    }))
    const wrapper = mountWithRouter(LoginView)
    await flushPromises()

    await wrapper.get('[data-testid="platform-identifier"]').setValue('13800138000')
    await wrapper.get('[data-testid="platform-password"]').setValue('bad-password')
    await wrapper.get('[data-testid="platform-login-submit"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="login-error"]').text()).toContain('手机号或邮箱或密码错误')
    expect(wrapper.text()).not.toContain('trace-login-1')
    expect(wrapper.text()).not.toContain('SQL')
  })

  it('trims valid mainland mobile or email login identifiers', async () => {
    const login = vi.fn(async () => authContext)
    setPlatformAuthGatewayForTests(gateway({ login }))
    const wrapper = mountWithRouter(LoginView)
    await flushPromises()

    await wrapper.get('[data-testid="platform-identifier"]').setValue(' 13800138000 ')
    await wrapper.get('[data-testid="platform-password"]').setValue('password-value')
    await wrapper.get('[data-testid="platform-login-submit"]').trigger('click')
    await flushPromises()
    expect(login).toHaveBeenCalledWith({ identifier: '+8613800138000', password: 'password-value' })

    await wrapper.get('[data-testid="platform-identifier"]').setValue('learner@example.com')
    await wrapper.get('[data-testid="platform-login-submit"]').trigger('click')
    await flushPromises()
    expect(login).toHaveBeenLastCalledWith({ identifier: 'learner@example.com', password: 'password-value' })

  })

  it('restores the recoverable practice action address after login', async () => {
    setPlatformAuthGatewayForTests(gateway())
    const target = '/app/practice/start?mode=DAILY&qualificationId=q-1&subjectId=s-1&resumeAction=1&sourceType=DAILY'
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div />' } },
        { path: '/login', component: LoginView },
        { path: '/register', component: { template: '<div />' } },
        { path: '/forgot-password', component: { template: '<div />' } },
        { path: '/app/practice/start', component: { template: '<div />' } },
      ],
    })
    await router.push(`/login?redirect=${encodeURIComponent(target)}`)
    await router.isReady()
    const wrapper = mount(LoginView, { global: { plugins: [ElementPlus, router] } })
    await flushPromises()

    await wrapper.get('[data-testid="platform-identifier"]').setValue('13800138000')
    await wrapper.get('[data-testid="platform-password"]').setValue('Password123')
    await wrapper.get('[data-testid="platform-login-submit"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe(target)
  })

  it('registers by phone challenge from the shared login page and signs in', async () => {
    const requestRegistrationChallenge = vi.fn(async () => ({ challengeId: 'challenge-1', expiresAt: '2026-07-16T10:10:00+08:00' }))
    const register = vi.fn(async (request) => ({ userId: 'user-1', username: request.phone, registrationStatus: 'REGISTERED' as const, createdAt: '2026-07-16T10:00:00+08:00' }))
    const login = vi.fn(async () => authContext)
    setPlatformAuthGatewayForTests(gateway({ requestRegistrationChallenge, register, login }))
    const wrapper = mountWithRouter(LoginView)
    await flushPromises()
    await wrapper.get('[data-testid="register-link"]').trigger('click')

    await wrapper.get('[data-testid="register-phone"]').setValue('13800138000')
    await wrapper.get('[data-testid="request-register-code"]').trigger('click')
    await flushPromises()
    expect((wrapper.get('[data-testid="register-code"]').element as HTMLInputElement).value).toBe('246810')
    await wrapper.get('[data-testid="platform-login-submit"]').trigger('click')
    await flushPromises()

    expect(requestRegistrationChallenge).toHaveBeenCalledOnce()
    expect(requestRegistrationChallenge).toHaveBeenCalledWith(expect.objectContaining({
      phone: '+8613800138000',
    }))
    expect(register).toHaveBeenCalledWith(expect.objectContaining({
      phone: '+8613800138000',
      challengeId: 'challenge-1',
      verificationCode: '246810',
    }))
    expect(register.mock.calls[0]?.[0].nickname).toMatch(/^用户8000/)
    expect(register.mock.calls[0]?.[0].password).toMatch(/^Aa1!/)
    expect(login).toHaveBeenCalledWith({
      identifier: '+8613800138000',
      password: register.mock.calls[0]?.[0].password,
    })
  })

  it('shows only phone and verification code in registration mode', async () => {
    setPlatformAuthGatewayForTests(gateway())
    const wrapper = mountWithRouter(LoginView)
    await flushPromises()
    await wrapper.get('[data-testid="register-link"]').trigger('click')

    expect(wrapper.find('[data-testid="register-phone"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="register-code"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="register-nickname"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="register-email"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="register-password"]').exists()).toBe(false)
  })

  it('sends the password-reset identifier and uses the local reset verification code', async () => {
    const requestPasswordResetChallenge = vi.fn(async () => ({
      challengeId: 'reset-1',
      expiresAt: '2026-07-16T10:10:00+08:00',
    }))
    const resetPassword = vi.fn(async () => undefined)
    setPlatformAuthGatewayForTests(gateway({
      requestPasswordResetChallenge,
      resetPassword,
    }))
    const wrapper = mountWithRouter(ForgotPasswordView, '/forgot-password')
    await flushPromises()

    expect(wrapper.find('[data-testid="forgot-channel"]').exists()).toBe(false)
    await wrapper.get('[data-testid="forgot-identifier"]').setValue('13800138000')
    await wrapper.get('[data-testid="forgot-new-password"]').setValue('Newsecure123')
    await wrapper.get('[data-testid="forgot-confirm-password"]').setValue('Newsecure123')
    await wrapper.get('[data-testid="request-forgot-code"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="forgot-code"]').setValue('135790')
    await wrapper.get('[data-testid="forgot-submit"]').trigger('click')
    await flushPromises()

    expect(requestPasswordResetChallenge).toHaveBeenCalledWith(expect.objectContaining({
      identifier: '+8613800138000',
      channel: 'SMS',
    }))
    expect(resetPassword).toHaveBeenCalledWith(expect.objectContaining({
      challengeId: 'reset-1',
      verificationCode: '135790',
      newPassword: 'Newsecure123',
    }))
  })

  it('shows email password reset only when the backend provider is available', async () => {
    const requestPasswordResetChallenge = vi.fn(async () => ({
      challengeId: 'email-reset-1',
      expiresAt: '2026-07-16T10:10:00+08:00',
    }))
    setPlatformAuthGatewayForTests(gateway({
      getCapabilities: async () => ({
        usernamePassword: false,
        mobilePassword: false,
        emailPassword: true,
        wechatQr: false,
        registrationSms: false,
        registrationVerificationRequired: true,
        passwordResetSms: false,
        passwordResetEmail: true,
        credentialPolicy: {
          nickname: { minLength: 1, maxLength: 32 },
          password: { minLength: 8, maxLength: 64 },
        },
      }),
      requestPasswordResetChallenge,
    }))
    const wrapper = mountWithRouter(ForgotPasswordView, '/forgot-password')
    await flushPromises()

    expect(wrapper.get('[data-testid="forgot-channel"]').text()).toContain('邮箱')
    await wrapper.get('[data-testid="forgot-identifier"]').setValue(' User@Example.COM ')
    await wrapper.get('[data-testid="request-forgot-code"]').trigger('click')
    await flushPromises()

    expect(requestPasswordResetChallenge).toHaveBeenCalledWith(expect.objectContaining({
      identifier: 'User@example.com',
      channel: 'EMAIL',
    }))
  })

  it('keeps auth input border and focus geometry on the Element Plus wrapper only', () => {
    for (const file of authViewFiles) {
      const source = readFileSync(file, 'utf8')
      expect(source).toContain('.auth-form :deep(.el-input__wrapper)')
      expect(source).toContain('.auth-form :deep(.el-input__wrapper.is-focus)')
      expect(source).toContain('.auth-form :deep(.is-error .el-input__wrapper)')
      expect(source).toContain('.auth-form :deep(.el-input__inner:focus-visible)')
      expect(source).toMatch(/\.el-input__inner[\s\S]*outline:\s*0/)
      expect(source).toMatch(/\.el-form-item\)[\s\S]*margin-bottom:\s*24px/)
      expect(source).toMatch(/\.el-form-item__error\)[\s\S]*padding-top:\s*4px/)
      expect(source).toMatch(/\.el-form-item__error\)[\s\S]*line-height:\s*16px/)
    }
    const globalStyle = readFileSync(globalStyleFile, 'utf8')
    expect(globalStyle).toContain('.el-input__inner:focus-visible')
    expect(globalStyle).toContain('.el-input__wrapper:has(.el-input__inner:focus-visible)')
  })

  it('does not embed a development password in the production login component', () => {
    const source = readFileSync(resolve('src/modules/foundation/views/LoginView.vue'), 'utf8')
    expect(source).not.toMatch(/(?:student|admin)[\s\S]{0,80}(?:password|密码)\s*[:=]\s*['"][^'"]+['"]/i)
  })
})
