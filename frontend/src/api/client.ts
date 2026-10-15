import axios, { AxiosError, type AxiosInstance } from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiEnvelope, ApiErrorPayload } from './types'

declare module 'axios' {
  export interface AxiosRequestConfig {
    suppressGlobalErrorRedirect?: boolean
  }
}

export const PLATFORM_TOKEN_STORAGE_KEY = 'platform-access-token'
export const EXAM_TOKEN_STORAGE_KEY = 'exam-simulator-token'

type ApiScope = 'platform' | 'exam'

interface ApiClientHandlers {
  onPlatformUnauthorized: () => void
  onExamUnauthorized: () => void
  onForbidden: (traceId?: string) => void
  onServerError: (traceId?: string) => void
}

const handlers: ApiClientHandlers = {
  onPlatformUnauthorized: () => undefined,
  onExamUnauthorized: () => undefined,
  onForbidden: () => undefined,
  onServerError: () => undefined,
}

export function configureApiClientHandlers(next: Partial<ApiClientHandlers>): void {
  Object.assign(handlers, next)
}

export class ApiClientError extends Error {
  constructor(
    message: string,
    readonly status?: number,
    readonly code?: string,
    readonly traceId?: string,
    readonly details?: ApiErrorPayload,
  ) {
    super(message)
    this.name = 'ApiClientError'
  }
}

const GENERIC_SERVICE_ERROR = '系统暂时不可用，请稍后重试'

function createTraceId(): string {
  const random = Math.random().toString(36).slice(2, 10)
  return `web-${Date.now().toString(36)}-${random}`
}

const platformErrorMessages: Record<string, string> = {
  AUTH_INVALID_CREDENTIALS: '手机号或邮箱或密码错误',
  AUTH_ACCOUNT_DISABLED: '手机号或邮箱或密码错误',
  AUTH_SESSION_EXPIRED: '登录状态已失效，请重新登录',
  AUTH_CHALLENGE_INVALID: '验证码错误或已过期，请重新获取',
  AUTH_REQUEST_INVALID: '提交的信息有误，请检查后重试',
  AUTH_ROLE_FIELD_FORBIDDEN: '提交的信息有误，请检查后重试',
  AUTH_USERNAME_CONFLICT: '注册信息已被使用，请更换后重试',
  AUTH_MOBILE_CONFLICT: '手机号已被注册',
  AUTH_IDEMPOTENCY_CONFLICT: '请求参数与上一次提交不一致，请刷新后重试',
  AUTH_REQUEST_PROCESSING: '请求正在处理中，请稍后重试',
  AUTH_PASSWORD_POLICY_VIOLATION: '密码不符合当前安全策略，请检查后重试',
  AUTH_RATE_LIMITED: '请求过于频繁，请稍后重试',
  AUTH_ACCOUNT_LOCKED: '账号已被锁定，请联系管理员',
  AUTH_CAPACITY_BUSY: '认证服务繁忙，请稍后重试',
  AUTH_WRITE_BUSY: '认证服务繁忙，请稍后重试',
  AUTH_SMS_UNAVAILABLE: '验证码服务暂不可用',
  AUTH_PROVIDER_UNAVAILABLE: '认证服务暂不可用',
  CSRF_INVALID: '页面安全校验已失效，请刷新页面后重试',
  FORBIDDEN: '暂无权限执行此操作',
  QUESTION_ADMIN_FORBIDDEN: '当前账号没有执行此题库操作的权限',
  QUESTION_SELF_REVIEW_FORBIDDEN: '提交人不能审核自己提交的题目，请切换审核账号',
  QUESTION_SELF_PUBLISH_FORBIDDEN: '提交人不能发布自己提交的题目，请切换发布账号',
  QUESTION_VERSION_CONFLICT: '题目版本已发生变化，请刷新详情后重试',
  QUESTION_MUTATION_CONFLICT: '相同操作标识对应的请求内容不一致，请重新发起操作',
  QUESTION_MUTATION_IN_PROGRESS: '相同操作正在处理中，请稍后刷新结果',
  QUESTION_ADMIN_VALIDATION_ERROR: '题目内容校验未通过，请检查必填项和题型规则',
  QUESTION_IMPORT_FILE_TYPE_INVALID: '仅支持 question-import/1.0 JSON 或九 Sheet Excel 文件',
  QUESTION_IMPORT_FILE_SIZE_INVALID: '导入文件不能超过 10 MiB',
  QUESTION_IMPORT_FILE_EMPTY: '导入文件不能为空',
  QUESTION_IMPORT_FILE_NAME_INVALID: '导入文件名不安全，请重命名后重试',
  QUESTION_IMPORT_FILE_MIME_INVALID: '导入文件的 MIME 类型与扩展名不一致',
  QUESTION_IMPORT_FILE_SIGNATURE_INVALID: '文件内容与 JSON/XLSX 格式不一致',
  QUESTION_IMPORT_SCHEMA_VERSION_INVALID: '导入文件必须使用 question-import/1.0',
  QUESTION_IMPORT_UPLOAD_NOT_FOUND: '预检文件已失效，请重新选择文件',
  QUESTION_IMPORT_NO_ELIGIBLE_ROWS: '没有可导入的题目，请先修正失败明细',
  QUESTION_IMPORT_ROLLBACK_NOT_PRISTINE: '该批次题目已被修改、审核或发布，不能直接回滚',
  QUESTION_IMPORT_BATCH_NOT_FOUND: '导入批次不存在或已不可访问',
  EXAM_INVALID_CREDENTIALS: '\u51c6\u8003\u8bc1\u53f7\u6216\u8bc1\u4ef6\u53f7\u6709\u8bef',
  EXAM_QUALIFICATION_MISMATCH: '\u5f53\u524d\u51c6\u8003\u8bc1\u4e0e\u8003\u8bd5\u8d44\u683c\u4e0d\u5339\u914d',
  EXAM_ENTRY_NOT_FOUND: '\u5f53\u524d\u8003\u573a\u672a\u5f00\u653e\u6216\u8bd5\u5377\u672a\u914d\u7f6e\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5',
  EXAM_ENTRY_NOT_PUBLISHED: '\u5f53\u524d\u8003\u573a\u672a\u5f00\u653e\u6216\u8bd5\u5377\u672a\u914d\u7f6e\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5',
  EXAM_ENTRY_SUBJECT_NOT_CONFIGURED: '\u5f53\u524d\u8003\u573a\u672a\u5f00\u653e\u6216\u8bd5\u5377\u672a\u914d\u7f6e\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5',
  EXAM_REAL_PAPER_REQUIRED: '\u5f53\u524d\u8003\u573a\u672a\u5f00\u653e\u6216\u8bd5\u5377\u672a\u914d\u7f6e\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5',
  EXAM_LOGIN_WINDOW_CLOSED: '\u5f53\u524d\u8003\u573a\u672a\u5f00\u653e\u6216\u8bd5\u5377\u672a\u914d\u7f6e\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5',
  EXAM_STATE_CONFLICT: '\u5f53\u524d\u4f1a\u8bdd\u72b6\u6001\u4e0d\u5141\u8bb8\u8be5\u64cd\u4f5c',
  ANSWERS_LOCKED: '\u5f53\u524d\u79d1\u76ee\u5df2\u9501\u5b9a\u7b54\u6848',
  SUB_QUESTION_NOT_FOUND: '\u9898\u76ee\u4e0d\u5b58\u5728\u6216\u4e0d\u5c5e\u4e8e\u5f53\u524d\u8bd5\u5377',
  EXAM_TOKEN_REQUIRED: '\u767b\u5f55\u72b6\u6001\u5df2\u5931\u6548\uff0c\u8bf7\u91cd\u65b0\u8fdb\u5165\u6a21\u62df\u8003\u8bd5',
  EXAM_TOKEN_INVALID: '\u767b\u5f55\u72b6\u6001\u5df2\u5931\u6548\uff0c\u8bf7\u91cd\u65b0\u8fdb\u5165\u6a21\u62df\u8003\u8bd5',
  EXAM_CREDENTIAL_KEY_UNAVAILABLE: '\u6a21\u62df\u8003\u8bd5\u51ed\u636e\u670d\u52a1\u6682\u4e0d\u53ef\u7528\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5',
  EXAM_LOGIN_RATE_LIMITED: '\u64cd\u4f5c\u8fc7\u4e8e\u9891\u7e41\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5',
  QUALIFICATION_NOT_BOUND: '\u5f53\u524d\u8d26\u53f7\u5c1a\u672a\u9009\u62e9\u8be5\u8003\u8bd5\u8d44\u683c',
  PREPARATION_PROFILE_REQUIRED: '\u5f53\u524d\u8d26\u53f7\u5c1a\u672a\u9009\u62e9\u8be5\u8003\u8bd5\u8d44\u683c',
}

export function resolvePlatformErrorMessage(code?: string, status?: number): string | undefined {
  if (code && platformErrorMessages[code]) return platformErrorMessages[code]
  if (status === 422) return '提交内容不符合要求，请检查后重试'
  if (status === 400 || status === 409) return '提交的信息有误，请检查后重试'
  if (status === 401) return '登录状态已失效，请重新登录'
  if (status === 403) return '暂无权限执行此操作'
  if (status === 404) return '请求的资源不存在'
  if (status === 429) return '操作过于频繁，请稍后重试'
  if (status && status >= 500) return GENERIC_SERVICE_ERROR
  return undefined
}

export function toSafeUserMessage(error: unknown): string {
  const details = error as { status?: unknown; code?: unknown }
  const status = typeof details?.status === 'number' ? details.status : undefined
  const code = typeof details?.code === 'string' ? details.code : undefined
  return resolvePlatformErrorMessage(code, status) ?? GENERIC_SERVICE_ERROR
}

function readToken(scope: ApiScope): string | null {
  return scope === 'platform' ? null : sessionStorage.getItem(EXAM_TOKEN_STORAGE_KEY)
}

function clearToken(scope: ApiScope): void {
  if (scope === 'platform') {
    localStorage.removeItem(PLATFORM_TOKEN_STORAGE_KEY)
  } else {
    sessionStorage.removeItem(EXAM_TOKEN_STORAGE_KEY)
  }
}

function createApiClient(scope: ApiScope): AxiosInstance {
  const client = axios.create({
    baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api/v1',
    timeout: 8_000,
    withCredentials: scope === 'platform',
    withXSRFToken: scope === 'platform',
    xsrfCookieName: 'XSRF-TOKEN',
    xsrfHeaderName: 'X-XSRF-TOKEN',
    responseEncoding: 'utf8',
  })

  client.interceptors.request.use((config) => {
    const token = readToken(scope)
    if (scope === 'exam' && token) config.headers.Authorization = `Bearer ${token}`
    config.headers['X-Trace-Id'] = createTraceId()
    return config
  })

  client.interceptors.response.use(
    (response) => response,
    (error: AxiosError<ApiEnvelope<ApiErrorPayload>>) => {
      const status = error.response?.status
      const body = error.response?.data
      const errorCode = body?.errorCode ?? body?.code
      const requestUrl = error.config?.url ?? ''
      const interactiveAuthRequest = scope === 'platform' && [
        '/auth/csrf',
        '/auth/capabilities',
        '/auth/login',
        '/auth/sms-login-challenges',
        '/auth/sms-login',
        '/auth/register',
        '/auth/registration-challenges',
        '/auth/password-reset-challenges',
        '/auth/password/reset',
        '/auth/logout',
      ].some((path) => requestUrl.startsWith(path))
      const sessionBootstrapRequest = scope === 'platform'
        && ['/auth/me', '/auth/refresh'].some((path) => requestUrl.startsWith(path))
      const authFlowHandlesError = interactiveAuthRequest || sessionBootstrapRequest
      const knownCapabilityGapRequest = scope === 'platform' && (
        requestUrl === '/platform/module-home'
        || requestUrl === '/practice/attempts'
      )
      const message = scope === 'platform'
        ? resolvePlatformErrorMessage(errorCode, status) ?? GENERIC_SERVICE_ERROR
        : resolvePlatformErrorMessage(errorCode, status) ?? GENERIC_SERVICE_ERROR
      const apiError = new ApiClientError(message, status, errorCode, body?.traceId, body?.data)

      if (status === 401) {
        clearToken(scope)
        if (scope === 'platform') {
          if (!authFlowHandlesError) handlers.onPlatformUnauthorized()
        } else {
          handlers.onExamUnauthorized()
        }
      } else if (status === 403) {
        if (!authFlowHandlesError) handlers.onForbidden(body?.traceId)
      } else if (status && status >= 500) {
        if (!authFlowHandlesError && !error.config?.suppressGlobalErrorRedirect) handlers.onServerError(body?.traceId)
      } else if (status === 404 || status === 429) {
        if (!authFlowHandlesError && !(status === 404 && knownCapabilityGapRequest)) {
          ElMessage.error(message)
        }
      }

      return Promise.reject(apiError)
    },
  )

  return client
}

export const platformApiClient = createApiClient('platform')
export const examApiClient = createApiClient('exam')

export async function unwrapApiResponse<T>(request: Promise<{ data: ApiEnvelope<T> }>): Promise<T> {
  const response = await request
  if (!response.data.success) {
    const errorCode = response.data.errorCode ?? response.data.code
    throw new ApiClientError(
      resolvePlatformErrorMessage(errorCode) ?? GENERIC_SERVICE_ERROR,
      undefined,
      errorCode,
      response.data.traceId,
      response.data.data as ApiErrorPayload,
    )
  }
  return response.data.data
}
