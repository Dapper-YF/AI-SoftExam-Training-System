export interface ApiEnvelope<T> {
  success: boolean
  code?: string
  errorCode?: string
  message: string
  data: T
  traceId: string
}

export interface ApiErrorPayload {
  field?: string
  reason?: string
}
