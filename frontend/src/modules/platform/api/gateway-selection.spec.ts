import { describe, expect, it } from 'vitest'
import { resolveGatewayKind } from '@/modules/foundation/api/mock-mode'

describe('platform home gateway selection', () => {
  it('uses HTTP when the Mock switch is missing', () => {
    expect(resolveGatewayKind(undefined)).toBe('http')
  })

  it('uses HTTP when the Mock switch is false', () => {
    expect(resolveGatewayKind('false')).toBe('http')
  })

  it('uses Mock only when the Mock switch is true', () => {
    expect(resolveGatewayKind('true')).toBe('mock')
  })
})
