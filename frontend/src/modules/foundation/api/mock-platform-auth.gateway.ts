import { ApiClientError } from '@/api/client'
import type {
  PlatformAuthContext,
  PlatformAuthCapabilities,
  PlatformDemoAccount,
  PlatformLoginRequest,
  PlatformLoginResult,
  PlatformPasswordResetChallengeRequest,
  PlatformPasswordResetRequest,
  PlatformRegistrationChallenge,
  PlatformRegistrationChallengeRequest,
  PlatformRegisterRequest,
  PlatformRegistrationResult,
  PlatformSessionExpiry,
  PlatformSmsLoginChallengeRequest,
  PlatformSmsLoginRequest,
  PlatformRole,
  PlatformUserProfile,
  PlatformUserProfileUpdateRequest,
} from '../types'
import type { PlatformAuthGateway } from './platform-auth.gateway'

const contexts: Partial<Record<PlatformRole, PlatformAuthContext>> = {
  STUDENT: {
    user: { id: 'mock-student-1', username: 'student', displayName: '演示学员', status: 'ENABLED' },
    roles: ['STUDENT'],
    permissions: [
      'platform:module-home:view',
      'student:home:view',
      'practice:workspace:view',
      'essay:workspace:view',
      'practice:read',
      'practice:answer',
      'exam-simulator:enter',
    ],
    featureFlags: {
      'student.portal': true,
      'admin.portal': false,
      'exam.simulator': true,
      'practice.enabled': true,
      'essay.enabled': false,
      'practice.workspace': true,
      'essay.workspace': false,
    },
    menus: [
      { code: 'student.home', name: '学习首页', routePath: '/app/home' },
      { code: 'practice.start', name: '刷题练习', routePath: '/app/practice/start' },
      { code: 'exam.simulator', name: '机考模拟', routePath: '/exam-simulator' },
    ],
  },
  ADMIN: {
    user: { id: 'mock-admin-1', username: 'admin', displayName: '平台管理员', status: 'ENABLED' },
    roles: ['ADMIN'],
    permissions: [
      'admin:home:view',
      'foundation:read',
      'foundation:write',
      'question:read',
      'question:create',
      'question:update',
      'question:submit-review',
      'question:review',
      'question:publish',
      'question:admin',
      'question:import',
    ],
    featureFlags: {
      'student.portal': false,
      'admin.portal': true,
      'admin.questions': true,
      'exam.simulator': false,
      'practice.enabled': false,
      'essay.enabled': false,
    },
    menus: [
      { code: 'admin.home', name: '管理首页', routePath: '/admin/home' },
      { code: 'admin.questions', name: '题库管理', routePath: '/admin/questions' },
    ],
  },
}

function readMockAccounts(): PlatformDemoAccount[] {
  const studentIdentifier = import.meta.env.VITE_MOCK_STUDENT_IDENTIFIER?.trim()
  const studentPassword = import.meta.env.VITE_MOCK_STUDENT_PASSWORD?.trim()
  const adminIdentifier = import.meta.env.VITE_MOCK_ADMIN_IDENTIFIER?.trim()
  const adminPassword = import.meta.env.VITE_MOCK_ADMIN_PASSWORD?.trim()
  const accounts: PlatformDemoAccount[] = []

  if (studentIdentifier && studentPassword) {
    accounts.push({
      role: 'STUDENT',
      identifier: studentIdentifier,
      password: studentPassword,
      displayName: import.meta.env.VITE_MOCK_STUDENT_DISPLAY_NAME?.trim() || '本地测试学员',
    })
  }
  if (adminIdentifier && adminPassword) {
    accounts.push({
      role: 'ADMIN',
      identifier: adminIdentifier,
      password: adminPassword,
      displayName: import.meta.env.VITE_MOCK_ADMIN_DISPLAY_NAME?.trim() || '本地测试管理员',
    })
  }
  return accounts
}

function contextFor(account: PlatformDemoAccount): PlatformAuthContext {
  const configuredContext = contexts[account.role]
  if (!configuredContext) throw mockConfigurationError()
  const context = structuredClone(configuredContext)
  context.user.username = account.identifier
  context.user.displayName = account.displayName
  return context
}

function mockConfigurationError(): ApiClientError {
  return new ApiClientError(
    '本地 Mock 登录未配置，请设置 VITE_MOCK_* 环境变量',
    503,
    'MOCK_AUTH_NOT_CONFIGURED',
    'mock-auth-config',
  )
}

let activeMockRole: PlatformRole | null = null

export class MockPlatformAuthGateway implements PlatformAuthGateway {
  async getCapabilities(): Promise<PlatformAuthCapabilities> {
    return {
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
    }
  }

  async requestRegistrationChallenge(request: PlatformRegistrationChallengeRequest): Promise<PlatformRegistrationChallenge> {
    return {
      challengeId: `mock-registration-${request.requestId}`,
      expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
      retryAfterSeconds: 60,
    }
  }

  async register(request: PlatformRegisterRequest): Promise<PlatformRegistrationResult> {
    return {
      userId: `mock-user-${request.phone}`,
      username: request.phone,
      registrationStatus: 'REGISTERED',
      createdAt: new Date().toISOString(),
    }
  }

  async login(request: PlatformLoginRequest): Promise<PlatformLoginResult> {
    const accounts = readMockAccounts()
    if (!accounts.length) throw mockConfigurationError()
    const account = accounts.find(
      (item) => item.identifier === request.identifier && item.password === request.password,
    )
    if (!account) {
      throw new ApiClientError('手机号或邮箱或密码错误', 401, 'UNAUTHORIZED', 'mock-auth-login')
    }
    activeMockRole = account.role
    return {
      ...contextFor(account),
      accessExpiresAt: new Date(Date.now() + 30 * 60_000).toISOString(),
      refreshExpiresAt: new Date(Date.now() + 7 * 24 * 60 * 60_000).toISOString(),
    }
  }

  async requestSmsLoginChallenge(request: PlatformSmsLoginChallengeRequest): Promise<PlatformRegistrationChallenge> {
    return {
      challengeId: `mock-sms-login-${request.requestId}`,
      expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
      retryAfterSeconds: 60,
    }
  }

  async smsLogin(_request: PlatformSmsLoginRequest): Promise<PlatformLoginResult> {
    const account = readMockAccounts().find((item) => item.role === 'STUDENT')
    if (!account) throw mockConfigurationError()
    activeMockRole = account.role
    return {
      ...contextFor(account),
      accessExpiresAt: new Date(Date.now() + 30 * 60_000).toISOString(),
      refreshExpiresAt: new Date(Date.now() + 7 * 24 * 60 * 60_000).toISOString(),
    }
  }

  async refresh(): Promise<PlatformSessionExpiry> {
    return {
      accessExpiresAt: new Date(Date.now() + 30 * 60_000).toISOString(),
      refreshExpiresAt: new Date(Date.now() + 7 * 24 * 60 * 60_000).toISOString(),
    }
  }

  async logout(): Promise<void> {
    activeMockRole = null
  }

  async getCurrentUser(): Promise<PlatformAuthContext> {
    const role = activeMockRole
    if (!role) {
      throw new ApiClientError('登录状态已失效', 401, 'UNAUTHORIZED', 'mock-auth-me')
    }
    const account = readMockAccounts().find((item) => item.role === role)
    if (!account) throw mockConfigurationError()
    return contextFor(account)
  }

  async getProfile(): Promise<PlatformUserProfile> {
    const context = await this.getCurrentUser()
    return {
      userId: context.user.id,
      username: context.user.username,
      displayName: context.user.displayName,
      maskedMobile: context.user.maskedMobile ?? null,
      maskedEmail: context.user.username.includes('@') ? context.user.username.replace(/^(.).+@/, '$1***@') : null,
      status: context.user.status,
      version: 0,
      updatedAt: new Date().toISOString(),
    }
  }

  async updateProfile(request: PlatformUserProfileUpdateRequest): Promise<PlatformUserProfile> {
    const profile = await this.getProfile()
    return {
      ...profile,
      displayName: request.displayName.trim(),
      version: request.version + 1,
      updatedAt: new Date().toISOString(),
    }
  }

  getDemoAccounts(): PlatformDemoAccount[] {
    return structuredClone(readMockAccounts())
  }

  async requestPasswordResetChallenge(request: PlatformPasswordResetChallengeRequest): Promise<PlatformRegistrationChallenge> {
    return {
      challengeId: `mock-reset-${request.requestId}`,
      expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
      retryAfterSeconds: 60,
    }
  }

  async resetPassword(_request: PlatformPasswordResetRequest): Promise<void> {}
}
