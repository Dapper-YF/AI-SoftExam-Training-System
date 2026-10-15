import { platformApiClient, unwrapApiResponse } from '@/api/client'

interface DevVerificationCodePayload {
  verificationCode: string
}

function isPayload(value: unknown): value is DevVerificationCodePayload {
  return typeof value === 'object'
    && value !== null
    && 'verificationCode' in value
    && typeof value.verificationCode === 'string'
    && value.verificationCode.trim().length > 0
}

export async function readDevVerificationCode(challengeId: string): Promise<string> {
  const payload = await unwrapApiResponse<unknown>(
    platformApiClient.get(`/auth/dev/challenges/${challengeId}/verification-code`),
  )
  if (!isPayload(payload)) throw new Error('开发验证码响应格式错误')
  return payload.verificationCode.trim()
}
