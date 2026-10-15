import { describe, expect, it } from 'vitest'
import { resolveExamGuardRedirect } from './guards'
import type { ReturnTypeOfExamStore } from './types'

function fakeStore(overrides: Partial<Record<string, unknown>> = {}): ReturnTypeOfExamStore {
  return {
    hasSession: false,
    sessionId: null,
    candidateConfirmed: false,
    rulesAcknowledged: false,
    examHasStarted: false,
    ...overrides,
  } as unknown as ReturnTypeOfExamStore
}

describe('exam simulator route guards', () => {
  it('blocks protected routes without a session', () => {
    expect(resolveExamGuardRedirect('EXAM', fakeStore())).toBe('/exam-simulator')
  })

  it('blocks rules before candidate confirmation', () => {
    expect(
      resolveExamGuardRedirect(
        'RULES',
        fakeStore({ hasSession: true, sessionId: 's-1' }),
      ),
    ).toBe('/exam-simulator/session/s-1/candidate')
  })

  it('blocks exam before rules are acknowledged or start time arrives', () => {
    const store = fakeStore({
      hasSession: true,
      sessionId: 's-1',
      candidateConfirmed: true,
    })
    expect(resolveExamGuardRedirect('EXAM', store)).toBe('/exam-simulator/session/s-1/rules')
  })

  it('allows exam only after all gates pass', () => {
    const store = fakeStore({
      hasSession: true,
      sessionId: 's-1',
      candidateConfirmed: true,
      rulesAcknowledged: true,
      examHasStarted: true,
    })
    expect(resolveExamGuardRedirect('EXAM', store)).toBeNull()
  })
})
