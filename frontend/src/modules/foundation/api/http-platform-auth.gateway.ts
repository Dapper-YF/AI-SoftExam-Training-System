import { ApiClientError, platformApiClient, unwrapApiResponse } from '@/api/client'
import type {
  EntityStatus,
  PlatformAuthCapabilities,
  PlatformAuthContext,
  PlatformDemoAccount,
  PlatformLoginRequest,
  PlatformLoginResult,
  PlatformMenu,
  PlatformPasswordResetChallengeRequest,
  PlatformPasswordResetRequest,
  PlatformRegistrationChallenge,
  PlatformRegistrationChallengeRequest,
  PlatformRegisterRequest,
  PlatformRegistrationResult,
  PlatformRole,
  PlatformSessionExpiry,
  PlatformSmsLoginChallengeRequest,
  PlatformSmsLoginRequest,
  PlatformUser,
  PlatformUserProfile,
  PlatformUserProfileUpdateRequest,
} from '../types'
import type { PlatformAuthGateway } from './platform-auth.gateway'

type JsonObject = Record<string, unknown>

const PLATFORM_ROLES = new Set<PlatformRole>([
  'STUDENT',
  'ADMIN',
  'QUESTION_AUTHOR',
  'QUESTION_REVIEWER',
  'QUESTION_PUBLISHER',
])
const ENTITY_STATUSES = new Set<EntityStatus>(['ENABLED', 'DISABLED', 'LOCKED'])

export const defaultAuthCapabilities: PlatformAuthCapabilities = {
  usernamePassword: false,
  mobilePassword: false,
  emailPassword: false,
  wechatQr: false,
  registrationSms: false,
  registrationVerificationRequired: true,
  adminRegistrationEnabled: false,
  passwordResetSms: false,
  smsLogin: false,
  passwordResetEmail: false,
}

function isObject(value: unknown): value is JsonObject {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function requiredString(value: unknown, label: string): string {
  if (typeof value === 'string' && value.trim()) return value.trim()
  throw new Error(`认证响应格式错误：${label} 缺失`)
}

function optionalString(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined
}

function requiredId(value: unknown, label: string): string {
  if (typeof value === 'string' && value.trim()) return value.trim()
  if (typeof value === 'number' && Number.isSafeInteger(value) && value > 0) return String(value)
  throw new Error(`认证响应格式错误：${label} 无效`)
}

function stringArray(value: unknown, label: string): string[] {
  if (!Array.isArray(value) || value.some((item) => typeof item !== 'string')) {
    throw new Error(`认证响应格式错误：${label} 必须是字符串数组`)
  }
  return value.map((item) => item as string)
}

function providerAvailable(value: unknown): boolean {
  if (typeof value === 'boolean') return value
  return isObject(value) && value.status === 'AVAILABLE'
}

function mapLengthPolicy(value: unknown): { minLength: number; maxLength: number } | undefined {
  if (!isObject(value)
    || typeof value.minLength !== 'number'
    || !Number.isInteger(value.minLength)
    || value.minLength <= 0
    || typeof value.maxLength !== 'number'
    || !Number.isInteger(value.maxLength)
    || value.maxLength < value.minLength) {
    return undefined
  }
  return { minLength: value.minLength, maxLength: value.maxLength }
}

function mapCapabilities(value: unknown): PlatformAuthCapabilities {
  if (!isObject(value)) throw new Error('认证能力响应格式错误')
  const usernamePassword = providerAvailable(value.usernamePassword)
  const mobileDelivery = providerAvailable(value.mobile)
  const emailDelivery = providerAvailable(value.email)
  const credentialPolicySource = isObject(value.credentialPolicy) ? value.credentialPolicy : undefined
  const nicknamePolicy = mapLengthPolicy(credentialPolicySource?.nickname)
  const passwordPolicy = mapLengthPolicy(credentialPolicySource?.password)
  const credentialPolicy = nicknamePolicy && passwordPolicy
    ? { nickname: nicknamePolicy, password: passwordPolicy }
    : undefined
  return {
    usernamePassword,
    mobilePassword: typeof value.mobilePassword === 'boolean'
      ? value.mobilePassword
      : mobileDelivery,
    emailPassword: typeof value.emailPassword === 'boolean'
      ? value.emailPassword
      : emailDelivery,
    wechatQr: typeof value.wechatQr === 'boolean'
      ? value.wechatQr
      : providerAvailable(value.wechat),
    registrationSms: typeof value.registrationSms === 'boolean'
      ? value.registrationSms
      : mobileDelivery,
    registrationVerificationRequired: typeof value.registrationVerificationRequired === 'boolean'
      ? value.registrationVerificationRequired
      : true,
    passwordResetSms: typeof value.passwordResetSms === 'boolean'
      ? value.passwordResetSms
      : mobileDelivery,
    smsLogin: mobileDelivery,
    passwordResetEmail: typeof value.passwordResetEmail === 'boolean'
      ? value.passwordResetEmail
      : emailDelivery,
    devSeedUsernameLoginEnabled: typeof value.devSeedUsernameLoginEnabled === 'boolean'
      ? value.devSeedUsernameLoginEnabled
      : undefined,
    adminRegistrationEnabled: value.adminRegistrationEnabled === true,
    credentialPolicy,
  }
}

function mapUser(value: unknown): PlatformUser {
  if (!isObject(value)) throw new Error('认证响应格式错误：user 必须是对象')
  const status = requiredString(value.status, 'user.status')
  if (!ENTITY_STATUSES.has(status as EntityStatus)) {
    throw new Error('认证响应格式错误：user.status 无效')
  }
  return {
    id: requiredId(value.id, 'user.id'),
    username: requiredString(value.username, 'user.username'),
    displayName: requiredString(value.displayName, 'user.displayName'),
    maskedMobile: value.maskedMobile === null ? null : optionalString(value.maskedMobile),
    status: status as EntityStatus,
  }
}

function mapProfile(value: unknown): PlatformUserProfile {
  if (!isObject(value)) throw new Error('profile response must be an object')
  const status = requiredString(value.status, 'profile.status')
  if (!ENTITY_STATUSES.has(status as EntityStatus)) {
    throw new Error('profile.status is invalid')
  }
  const version = value.version
  if (typeof version !== 'number' || !Number.isSafeInteger(version) || version < 0) {
    throw new Error('profile.version is invalid')
  }
  return {
    userId: requiredId(value.userId, 'profile.userId'),
    username: requiredString(value.username, 'profile.username'),
    displayName: requiredString(value.displayName, 'profile.displayName'),
    maskedMobile: value.maskedMobile === null ? null : optionalString(value.maskedMobile),
    maskedEmail: value.maskedEmail === null ? null : optionalString(value.maskedEmail),
    preparationQualificationId: value.preparationQualificationId == null ? null : requiredId(value.preparationQualificationId, 'profile.preparationQualificationId'),
    preparationQualificationName: value.preparationQualificationName === null ? null : optionalString(value.preparationQualificationName),
    examRegionCode: value.examRegionCode === null ? null : optionalString(value.examRegionCode),
    examRegionName: value.examRegionName === null ? null : optionalString(value.examRegionName),
    status: status as EntityStatus,
    version,
    updatedAt: requiredString(value.updatedAt, 'profile.updatedAt'),
  }
}

function mapMenu(value: unknown): PlatformMenu {
  if (!isObject(value)) throw new Error('认证响应格式错误：menus 记录无效')
  return {
    code: requiredString(value.code, 'menu.code'),
    name: requiredString(value.name, 'menu.name'),
    routePath: requiredString(value.routePath, 'menu.routePath'),
    icon: optionalString(value.icon),
  }
}

function mapAuthContext(value: unknown): PlatformAuthContext {
  if (!isObject(value)) throw new Error('认证响应格式错误：上下文必须是对象')
  const roles = stringArray(value.roles, 'roles')
  if (roles.some((role) => !PLATFORM_ROLES.has(role as PlatformRole))) {
    throw new Error('认证响应格式错误：roles 包含未知角色')
  }
  if (!Array.isArray(value.menus)) throw new Error('认证响应格式错误：menus 必须是数组')
  if (!isObject(value.featureFlags)) {
    throw new Error('认证响应格式错误：featureFlags 必须是对象')
  }
  const featureFlags: Record<string, boolean> = {}
  for (const [key, enabled] of Object.entries(value.featureFlags)) {
    if (typeof enabled !== 'boolean') {
      throw new Error('认证响应格式错误：featureFlags 值必须是布尔值')
    }
    featureFlags[key] = enabled
  }
  return {
    user: mapUser(value.user),
    roles: roles.map((role) => role as PlatformRole),
    permissions: stringArray(value.permissions, 'permissions'),
    menus: value.menus.map(mapMenu),
    featureFlags,
    accessExpiresAt: optionalString(value.accessExpiresAt),
    refreshExpiresAt: optionalString(value.refreshExpiresAt),
  }
}

function mapChallenge(value: unknown): PlatformRegistrationChallenge {
  if (!isObject(value)) throw new Error('认证响应格式错误：验证码挑战必须是对象')
  const retryAfterSeconds = value.retryAfterSeconds
  return {
    challengeId: requiredString(value.challengeId, 'challengeId'),
    expiresAt: requiredString(value.expiresAt, 'expiresAt'),
    retryAfterSeconds: typeof retryAfterSeconds === 'number' && retryAfterSeconds >= 0
      ? retryAfterSeconds
      : undefined,
  }
}

function mapRegistrationResult(value: unknown): PlatformRegistrationResult {
  if (!isObject(value)) throw new Error('认证响应格式错误：注册结果必须是对象')
  if (value.registrationStatus !== 'REGISTERED') {
    throw new Error('认证响应格式错误：registrationStatus 无效')
  }
  return {
    userId: requiredId(value.userId, 'userId'),
    username: requiredString(value.username, 'username'),
    registrationStatus: 'REGISTERED',
    createdAt: requiredString(value.createdAt, 'createdAt'),
  }
}

function mapSessionExpiry(value: unknown): PlatformSessionExpiry {
  if (!isObject(value)) throw new Error('认证响应格式错误：会话期限必须是对象')
  return {
    accessExpiresAt: requiredString(value.accessExpiresAt, 'accessExpiresAt'),
    refreshExpiresAt: requiredString(value.refreshExpiresAt, 'refreshExpiresAt'),
  }
}

export class HttpPlatformAuthGateway implements PlatformAuthGateway {
  private csrfRequest: Promise<void> | null = null

  private async ensureFreshCsrf(): Promise<void> {
    if (!this.csrfRequest) {
      this.csrfRequest = platformApiClient
        // 内网穿透可能保留旧的浏览器缓存，而访问/刷新 Cookie 仍然有效。
        // 使用唯一查询参数，确保每次危险请求前都重新签发匹配的 CSRF Cookie。
        .get('/auth/csrf', {
          params: { _: Date.now() },
          headers: { 'Cache-Control': 'no-store' },
        })
        .then(() => undefined)
        .finally(() => {
          this.csrfRequest = null
        })
    }
    await this.csrfRequest
  }

  private async postEnvelope(url: string, body?: unknown): Promise<unknown> {
    await this.ensureFreshCsrf()
    try {
      return await unwrapApiResponse<unknown>(platformApiClient.post(url, body))
    } catch (error) {
      if (!(error instanceof ApiClientError) || error.code !== 'CSRF_INVALID') throw error
      await this.ensureFreshCsrf()
      return unwrapApiResponse<unknown>(platformApiClient.post(url, body))
    }
  }

  private async putEnvelope(url: string, body?: unknown): Promise<unknown> {
    await this.ensureFreshCsrf()
    return unwrapApiResponse<unknown>(platformApiClient.put(url, body))
  }

  private async postNoContent(url: string, body?: unknown): Promise<void> {
    await this.ensureFreshCsrf()
    await platformApiClient.post(url, body)
  }

  async getCapabilities(): Promise<PlatformAuthCapabilities> {
    try {
      return mapCapabilities(
        await unwrapApiResponse<unknown>(platformApiClient.get('/auth/capabilities')),
      )
    } catch {
      return { ...defaultAuthCapabilities }
    }
  }

  async requestRegistrationChallenge(
    request: PlatformRegistrationChallengeRequest,
  ): Promise<PlatformRegistrationChallenge> {
    return mapChallenge(await this.postEnvelope('/auth/registration-challenges', request))
  }

  async register(request: PlatformRegisterRequest): Promise<PlatformRegistrationResult> {
    return mapRegistrationResult(await this.postEnvelope('/auth/register', request))
  }

  async login(request: PlatformLoginRequest): Promise<PlatformLoginResult> {
    return mapAuthContext(await this.postEnvelope('/auth/login', {
      identifier: request.identifier,
      password: request.password,
    }))
  }

  async requestSmsLoginChallenge(
    request: PlatformSmsLoginChallengeRequest,
  ): Promise<PlatformRegistrationChallenge> {
    return mapChallenge(await this.postEnvelope('/auth/sms-login-challenges', request))
  }

  async smsLogin(request: PlatformSmsLoginRequest): Promise<PlatformLoginResult> {
    return mapAuthContext(await this.postEnvelope('/auth/sms-login', request))
  }

  async refresh(): Promise<PlatformSessionExpiry> {
    return mapSessionExpiry(await this.postEnvelope('/auth/refresh'))
  }

  async logout(): Promise<void> {
    await this.postNoContent('/auth/logout')
  }

  async getCurrentUser(): Promise<PlatformAuthContext> {
    return mapAuthContext(
      await unwrapApiResponse<unknown>(platformApiClient.get('/auth/me')),
    )
  }

  async getProfile(): Promise<PlatformUserProfile> {
    return mapProfile(await unwrapApiResponse<unknown>(platformApiClient.get('/profile/me')))
  }

  async updateProfile(request: PlatformUserProfileUpdateRequest): Promise<PlatformUserProfile> {
    return mapProfile(await this.putEnvelope('/profile/me', request))
  }

  getDemoAccounts(): PlatformDemoAccount[] {
    return []
  }

  async requestPasswordResetChallenge(
    request: PlatformPasswordResetChallengeRequest,
  ): Promise<PlatformRegistrationChallenge> {
    return mapChallenge(await this.postEnvelope('/auth/password-reset-challenges', request))
  }

  async resetPassword(request: PlatformPasswordResetRequest): Promise<void> {
    await this.postNoContent('/auth/password/reset', request)
  }

}
