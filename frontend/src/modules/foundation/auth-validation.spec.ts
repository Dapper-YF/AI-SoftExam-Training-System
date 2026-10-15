import { describe, expect, it } from 'vitest'
import {
  loginIdentifierError,
  normalizeLoginIdentifier,
  PASSWORD_REQUIREMENTS_MESSAGE,
  PASSWORD_TOO_LONG_MESSAGE,
  passwordError,
  resolveCredentialPolicy,
} from './auth-validation'

describe('auth validation', () => {
  it('accepts a mainland mobile or a lightweight email identifier after trimming', () => {
    expect(normalizeLoginIdentifier(' 13800138000 ')).toBe('+8613800138000')
    expect(normalizeLoginIdentifier(' 86 138-0013-8000 ')).toBe('+8613800138000')
    expect(normalizeLoginIdentifier(' Learner@Example.COM ')).toBe('Learner@example.com')
    expect(loginIdentifierError('13800138000')).toBeUndefined()
    expect(loginIdentifierError('learner@example.com')).toBeUndefined()
    expect(loginIdentifierError('learner.example.com')).toBe('手机号或邮箱格式不正确')
    expect(loginIdentifierError('learner@')).toBe('手机号或邮箱格式不正确')
    expect(loginIdentifierError('')).toBe('请输入手机号或邮箱')
  })

  it('enforces the frozen 8-to-64 ASCII composition policy', () => {
    const policy = {
      nickname: { minLength: 1, maxLength: 32 },
      password: { minLength: 8, maxLength: 64 },
    }
    expect(passwordError('Aa12345', policy.password)).toBe(PASSWORD_REQUIREMENTS_MESSAGE)
    expect(passwordError('aa345678', policy.password)).toBe(PASSWORD_REQUIREMENTS_MESSAGE)
    expect(passwordError('AA345678', policy.password)).toBe(PASSWORD_REQUIREMENTS_MESSAGE)
    expect(passwordError('Aaabcdef', policy.password)).toBe(PASSWORD_REQUIREMENTS_MESSAGE)
    expect(passwordError('Aa345678', policy.password)).toBeUndefined()
    expect(passwordError('Aa12345!', policy.password)).toBeUndefined()
    expect(passwordError(`Aa1${'x'.repeat(61)}`, policy.password)).toBeUndefined()
    expect(passwordError(`Aa1${'x'.repeat(62)}`, policy.password)).toBe(PASSWORD_TOO_LONG_MESSAGE)
    expect(PASSWORD_REQUIREMENTS_MESSAGE).toBe('密码至少8位，且包含大写字母、小写字母和数字')
    expect(PASSWORD_REQUIREMENTS_MESSAGE).not.toMatch(/8[-至]64|最多64/)
    expect(resolveCredentialPolicy({
      usernamePassword: true,
      mobilePassword: true,
      emailPassword: false,
      wechatQr: false,
      registrationSms: true,
      registrationVerificationRequired: true,
      passwordResetSms: true,
      passwordResetEmail: false,
      credentialPolicy: policy,
    })).toEqual(policy)
    expect(resolveCredentialPolicy(null)).toBeNull()
  })
})
