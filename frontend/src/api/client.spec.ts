import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ApiClientError,
  EXAM_TOKEN_STORAGE_KEY,
  PLATFORM_TOKEN_STORAGE_KEY,
  configureApiClientHandlers,
  examApiClient,
  platformApiClient,
  toSafeUserMessage,
} from './client'
import type { ApiEnvelope, ApiErrorPayload } from './types'

function successAdapter(config: InternalAxiosRequestConfig): Promise<AxiosResponse> {
  return Promise.resolve({
    data: { success: true },
    status: 200,
    statusText: 'OK',
    headers: {},
    config,
  } as AxiosResponse)
}

function errorAdapter(status: number, traceId: string) {
  return (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
    const data: ApiEnvelope<ApiErrorPayload> = {
      success: false,
      code: status === 401 ? 'UNAUTHORIZED' : status === 404 ? 'NOT_FOUND' : 'FORBIDDEN',
      message: status === 401 ? '登录已失效' : status === 404 ? '资源不存在' : '无权访问',
      data: {},
      traceId,
    }
    return Promise.reject(
      new AxiosError('request failed', 'ERR_BAD_RESPONSE', config, undefined, {
        data,
        status,
        statusText: 'Error',
        headers: {},
        config,
      } as AxiosResponse),
    )
  }
}

describe('platform and exam API clients', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    configureApiClientHandlers({
      onPlatformUnauthorized: () => undefined,
      onExamUnauthorized: () => undefined,
      onForbidden: () => undefined,
      onServerError: () => undefined,
    })
  })

  it('uses cookie credentials for platform requests and bearer tokens for exam requests', async () => {
    localStorage.setItem(PLATFORM_TOKEN_STORAGE_KEY, 'platform-token')
    sessionStorage.setItem(EXAM_TOKEN_STORAGE_KEY, 'exam-token')

    const platformResponse = await platformApiClient.get('/probe', { adapter: successAdapter })
    const examResponse = await examApiClient.get('/probe', { adapter: successAdapter })

    expect(platformResponse.config.withCredentials).toBe(true)
    expect(platformResponse.config.withXSRFToken).toBe(true)
    expect(platformResponse.config.xsrfCookieName).toBe('XSRF-TOKEN')
    expect(platformResponse.config.xsrfHeaderName).toBe('X-XSRF-TOKEN')
    expect(platformResponse.config.headers.Authorization).toBeUndefined()
    expect(examResponse.config.headers.Authorization).toBe('Bearer exam-token')
    expect(platformResponse.config.headers['X-Trace-Id']).toMatch(/^web-/)
    expect(examResponse.config.headers['X-Trace-Id']).toMatch(/^web-/)
  })

  it('clears and redirects only the platform session after a platform 401', async () => {
    const onPlatformUnauthorized = vi.fn()
    const onExamUnauthorized = vi.fn()
    configureApiClientHandlers({ onPlatformUnauthorized, onExamUnauthorized })
    localStorage.setItem(PLATFORM_TOKEN_STORAGE_KEY, 'platform-token')
    sessionStorage.setItem(EXAM_TOKEN_STORAGE_KEY, 'exam-token')

    await expect(
      platformApiClient.get('/protected', { adapter: errorAdapter(401, 'trace-platform-401') }),
    ).rejects.toBeInstanceOf(ApiClientError)

    expect(localStorage.getItem(PLATFORM_TOKEN_STORAGE_KEY)).toBeNull()
    expect(sessionStorage.getItem(EXAM_TOKEN_STORAGE_KEY)).toBe('exam-token')
    expect(onPlatformUnauthorized).toHaveBeenCalledOnce()
    expect(onExamUnauthorized).not.toHaveBeenCalled()
  })

  it('lets the auth bootstrap own /auth 401 recovery without a global redirect race', async () => {
    const onPlatformUnauthorized = vi.fn()
    const onExamUnauthorized = vi.fn()
    configureApiClientHandlers({ onPlatformUnauthorized, onExamUnauthorized })

    await expect(
      platformApiClient.get('/auth/me', { adapter: errorAdapter(401, 'trace-auth-bootstrap') }),
    ).rejects.toBeInstanceOf(ApiClientError)

    expect(onPlatformUnauthorized).not.toHaveBeenCalled()
    expect(onExamUnauthorized).not.toHaveBeenCalled()
  })

  it('lets auth views render safe 403 and 5xx errors without global route replacement', async () => {
    const onForbidden = vi.fn()
    const onServerError = vi.fn()
    configureApiClientHandlers({ onForbidden, onServerError })

    await platformApiClient
      .post('/auth/register', {}, { adapter: errorAdapter(403, 'trace-auth-403') })
      .catch(() => undefined)
    await platformApiClient
      .post('/auth/login', {}, { adapter: errorAdapter(500, 'trace-auth-500') })
      .catch(() => undefined)
    await platformApiClient
      .get('/auth/capabilities', { adapter: errorAdapter(500, 'trace-auth-capabilities-500') })
      .catch(() => undefined)

    expect(onForbidden).not.toHaveBeenCalled()
    expect(onServerError).not.toHaveBeenCalled()
  })

  it('leaves auth bootstrap 5xx routing to the router guard without an interceptor race', async () => {
    const onPlatformUnauthorized = vi.fn()
    const onServerError = vi.fn()
    configureApiClientHandlers({ onPlatformUnauthorized, onServerError })

    await platformApiClient
      .get('/auth/me', { adapter: errorAdapter(500, 'trace-auth-bootstrap-500') })
      .catch(() => undefined)

    expect(onPlatformUnauthorized).not.toHaveBeenCalled()
    expect(onServerError).not.toHaveBeenCalled()
  })

  it('clears and redirects only the exam session after an exam 401', async () => {
    const onPlatformUnauthorized = vi.fn()
    const onExamUnauthorized = vi.fn()
    configureApiClientHandlers({ onPlatformUnauthorized, onExamUnauthorized })
    localStorage.setItem(PLATFORM_TOKEN_STORAGE_KEY, 'platform-token')
    sessionStorage.setItem(EXAM_TOKEN_STORAGE_KEY, 'exam-token')

    await expect(
      examApiClient.get('/protected', { adapter: errorAdapter(401, 'trace-exam-401') }),
    ).rejects.toBeInstanceOf(ApiClientError)

    expect(sessionStorage.getItem(EXAM_TOKEN_STORAGE_KEY)).toBeNull()
    expect(localStorage.getItem(PLATFORM_TOKEN_STORAGE_KEY)).toBe('platform-token')
    expect(onExamUnauthorized).toHaveBeenCalledOnce()
    expect(onPlatformUnauthorized).not.toHaveBeenCalled()
  })

  it('preserves traceId and sends 403 to the permission handler', async () => {
    const onForbidden = vi.fn()
    configureApiClientHandlers({ onForbidden })

    const error = await platformApiClient
      .get('/forbidden', { adapter: errorAdapter(403, 'trace-forbidden') })
      .catch((reason: unknown) => reason)

    expect(error).toBeInstanceOf(ApiClientError)
    expect((error as ApiClientError).traceId).toBe('trace-forbidden')
    expect(onForbidden).toHaveBeenCalledWith('trace-forbidden')
  })

  it('reads the backend errorCode field and never exposes its raw internal message', async () => {
    const adapter = (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => Promise.reject(
      new AxiosError('request failed', 'ERR_BAD_RESPONSE', config, undefined, {
        data: {
          success: false,
          errorCode: 'AUTH_SESSION_EXPIRED',
          message: 'internal repository detail must stay hidden',
          data: {},
          traceId: 'trace-backend-error-code',
        },
        status: 401,
        statusText: 'Unauthorized',
        headers: {},
        config,
      } as AxiosResponse),
    )

    const error = await platformApiClient
      .get('/protected', { adapter })
      .catch((reason: unknown) => reason)

    expect(error).toBeInstanceOf(ApiClientError)
    expect(error).toMatchObject({
      code: 'AUTH_SESSION_EXPIRED',
      message: '登录状态已失效，请重新登录',
      traceId: 'trace-backend-error-code',
    })
  })

  it('suppresses global 404 toasts only for registered capability gaps', async () => {
    const message = vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)

    await platformApiClient
      .get('/platform/module-home', { adapter: errorAdapter(404, 'trace-home-gap') })
      .catch(() => undefined)
    await platformApiClient
      .get('/practice/attempts', { adapter: errorAdapter(404, 'trace-history-gap') })
      .catch(() => undefined)

    expect(message).not.toHaveBeenCalled()

    await platformApiClient
      .get('/unexpected-resource', { adapter: errorAdapter(404, 'trace-unexpected') })
      .catch(() => undefined)

    expect(message).toHaveBeenCalledWith('请求的资源不存在')
  })

  it.each([
    [new ApiClientError('raw', 401, 'AUTH_INVALID_CREDENTIALS'), '手机号或邮箱或密码错误'],
    [new ApiClientError('raw', 400, 'AUTH_REQUEST_INVALID'), '提交的信息有误，请检查后重试'],
    [new ApiClientError('raw', 401, 'AUTH_SESSION_EXPIRED'), '登录状态已失效，请重新登录'],
    [new ApiClientError('raw', 403, 'FORBIDDEN'), '暂无权限执行此操作'],
    [new Error('SQL connection stack trace'), '系统暂时不可用，请稍后重试'],
  ])('maps unsafe error details to a stable user message', (error, expected) => {
    expect(toSafeUserMessage(error)).toBe(expected)
  })
})
