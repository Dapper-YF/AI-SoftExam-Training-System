import { describe, expect, it } from 'vitest'
import { resolvePlatformErrorMessage } from './client'

describe('platform auth error messages', () => {
  it('maps backend auth codes to safe Chinese messages instead of raw debug text', () => {
    expect(resolvePlatformErrorMessage('AUTH_INVALID_CREDENTIALS', 401)).toBe('手机号或邮箱或密码错误')
    expect(resolvePlatformErrorMessage('AUTH_ACCOUNT_DISABLED', 403)).toBe('手机号或邮箱或密码错误')
    expect(resolvePlatformErrorMessage('AUTH_CHALLENGE_INVALID', 400)).toBe('验证码错误或已过期，请重新获取')
    expect(resolvePlatformErrorMessage('AUTH_USERNAME_CONFLICT', 409)).toBe('注册信息已被使用，请更换后重试')
    expect(resolvePlatformErrorMessage('AUTH_MOBILE_CONFLICT', 409)).toBe('手机号已被注册')
    expect(resolvePlatformErrorMessage('AUTH_REQUEST_INVALID', 400)).toBe('提交的信息有误，请检查后重试')
    expect(resolvePlatformErrorMessage('AUTH_REQUEST_PROCESSING', 409)).toBe('请求正在处理中，请稍后重试')
    expect(resolvePlatformErrorMessage('AUTH_PASSWORD_POLICY_VIOLATION', 422)).toBe('密码不符合当前安全策略，请检查后重试')
    expect(resolvePlatformErrorMessage('CSRF_INVALID', 403)).toContain('刷新页面')
    expect(resolvePlatformErrorMessage('AUTH_RATE_LIMITED', 429)).toBe('请求过于频繁，请稍后重试')
    expect(resolvePlatformErrorMessage('UNMAPPED_SQL_EXCEPTION', 500)).toBe('系统暂时不可用，请稍后重试')

    const loginFailureMessage = resolvePlatformErrorMessage('AUTH_ACCOUNT_DISABLED', 403)
    expect(loginFailureMessage).not.toContain('账号已停用')
    expect(loginFailureMessage).not.toContain('trace-')
    expect(loginFailureMessage).not.toContain('SQL')
  })

  it('maps question 403, 409, 422 and 500 responses without exposing backend details', () => {
    expect(resolvePlatformErrorMessage('QUESTION_SELF_REVIEW_FORBIDDEN', 403)).toContain('不能审核自己')
    expect(resolvePlatformErrorMessage('QUESTION_VERSION_CONFLICT', 409)).toContain('刷新详情')
    expect(resolvePlatformErrorMessage('QUESTION_IMPORT_FILE_MIME_INVALID', 400)).toContain('MIME')
    expect(resolvePlatformErrorMessage('QUESTION_IMPORT_FILE_SIGNATURE_INVALID', 400)).toContain('JSON/XLSX')
    expect(resolvePlatformErrorMessage(undefined, 422)).toBe('提交内容不符合要求，请检查后重试')
    expect(resolvePlatformErrorMessage('UNMAPPED_STACK_TRACE', 500)).toBe('系统暂时不可用，请稍后重试')
  })
})
