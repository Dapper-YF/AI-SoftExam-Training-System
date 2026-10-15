import type { PlatformAuthCapabilities } from './types'

export interface PasswordPolicy {
  minLength: number
  maxLength: number
}

const MAINLAND_MOBILE_DIGITS_PATTERN = /^1[3-9]\d{9}$/
const LIGHTWEIGHT_EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const DEV_SEED_USERNAMES = new Set(['student', 'admin', 'question_reviewer'])
export const PASSWORD_REQUIREMENTS_MESSAGE = '密码至少8位，且包含大写字母、小写字母和数字'
export const PASSWORD_TOO_LONG_MESSAGE = '密码过长，请缩短后重试'

export function normalizeMainlandPhone(value: string): string | undefined {
  const compact = value.trim().replace(/[\s-]/g, '')
  const digits = compact.startsWith('+86')
    ? compact.slice(3)
    : compact.startsWith('86') && compact.length === 13
      ? compact.slice(2)
      : compact
  return MAINLAND_MOBILE_DIGITS_PATTERN.test(digits) ? `+86${digits}` : undefined
}

export function normalizeEmail(value: string): string | undefined {
  const trimmed = value.trim()
  if (!LIGHTWEIGHT_EMAIL_PATTERN.test(trimmed)) return undefined
  const [localPart, domainPart] = trimmed.split('@')
  if (!localPart || !domainPart) return undefined
  return `${localPart}@${domainPart.toLowerCase()}`
}

export function normalizeLoginIdentifier(value: string): string {
  return normalizeMainlandPhone(value) ?? normalizeEmail(value) ?? value.trim()
}

export function loginIdentifierError(value: string): string | undefined {
  const identifier = value.trim()
  if (!identifier) return '请输入手机号或邮箱'
  if (normalizeMainlandPhone(identifier) || normalizeEmail(identifier) || DEV_SEED_USERNAMES.has(identifier)) return undefined
  return '手机号或邮箱格式不正确'
}

export function mainlandMobileError(value: string): string | undefined {
  if (!value.trim()) return '请输入手机号'
  return normalizeMainlandPhone(value) ? undefined : '请输入有效的大陆手机号'
}

export function optionalEmailError(value: string): string | undefined {
  if (!value.trim()) return undefined
  return normalizeEmail(value) ? undefined : '请输入有效的邮箱地址'
}

export function resolveCredentialPolicy(capabilities: PlatformAuthCapabilities | null): {
  nickname: PasswordPolicy
  password: PasswordPolicy
} | null {
  return capabilities?.credentialPolicy ?? null
}

export function passwordError(value: string, policy: PasswordPolicy): string | undefined {
  const length = Array.from(value).length
  if (!value) return '请输入密码'
  if (length > policy.maxLength) return PASSWORD_TOO_LONG_MESSAGE
  if (
    length < policy.minLength
    || !/[A-Z]/.test(value)
    || !/[a-z]/.test(value)
    || !/[0-9]/.test(value)
  ) {
    return PASSWORD_REQUIREMENTS_MESSAGE
  }
  return undefined
}

export function credentialPolicyUnavailableError(): string {
  return '认证服务暂不可用，请稍后重试'
}

export function nicknameError(value: string, policy: PasswordPolicy): string | undefined {
  const length = Array.from(value.trim()).length
  if (!value.trim()) return '请输入昵称'
  if (length < policy.minLength || length > policy.maxLength) {
    return `昵称长度需为${policy.minLength}至${policy.maxLength}个字符`
  }
  return undefined
}
