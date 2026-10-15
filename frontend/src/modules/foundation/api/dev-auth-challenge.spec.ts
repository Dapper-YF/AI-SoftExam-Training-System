import { afterEach, describe, expect, it, vi } from 'vitest'
import { platformApiClient } from '@/api/client'
import { readDevVerificationCode } from './dev-auth-challenge'

describe('development auth challenge lookup', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('reads the controlled local verification code endpoint without hardcoding a code', async () => {
    const get = vi.spyOn(platformApiClient, 'get').mockResolvedValue({
      data: {
        success: true,
        errorCode: 'OK',
        message: 'success',
        data: { verificationCode: '864209' },
        traceId: 'trace-dev-code',
      },
    } as never)

    await expect(readDevVerificationCode('challenge-1')).resolves.toBe('864209')
    expect(get).toHaveBeenCalledWith('/auth/dev/challenges/challenge-1/verification-code')
  })

  it('rejects a malformed local verification response', async () => {
    vi.spyOn(platformApiClient, 'get').mockResolvedValue({
      data: {
        success: true,
        errorCode: 'OK',
        message: 'success',
        data: {},
        traceId: 'trace-dev-code',
      },
    } as never)

    await expect(readDevVerificationCode('challenge-1')).rejects.toThrow('响应格式错误')
  })
})
