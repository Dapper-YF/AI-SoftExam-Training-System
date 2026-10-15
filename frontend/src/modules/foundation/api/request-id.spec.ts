import { describe, expect, it, vi } from 'vitest'
import { createRequestId } from './request-id'

describe('auth request id', () => {
  it('always returns an RFC 4122 version-4 UUID accepted by the backend', () => {
    expect(createRequestId()).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    )
  })

  it('keeps UUID shape when randomUUID is unavailable', () => {
    const randomUuid = vi.spyOn(globalThis.crypto, 'randomUUID')
      .mockImplementation(() => undefined as never)
    try {
      expect(createRequestId()).toMatch(
        /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
      )
    } finally {
      randomUuid.mockRestore()
    }
  })
})
