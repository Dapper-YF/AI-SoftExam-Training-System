import { describe, expect, it } from 'vitest'
import { isExplicitMockEnabled } from './mock-mode'

describe('mock mode selection', () => {
  it('enables mock mode only for the explicit true value', () => {
    expect(isExplicitMockEnabled(undefined)).toBe(false)
    expect(isExplicitMockEnabled('')).toBe(false)
    expect(isExplicitMockEnabled('false')).toBe(false)
    expect(isExplicitMockEnabled('TRUE')).toBe(false)
    expect(isExplicitMockEnabled('true')).toBe(true)
  })
})
