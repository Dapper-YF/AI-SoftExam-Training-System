import { describe, expect, it } from 'vitest'
import {
  deriveQuestionState,
  buildVisibleQuestionIndexes,
  isSingleItemSettlementAvailable,
} from '../state/practice-attempt-state'

describe('practice attempt presentation state', () => {
  it('does not mark a locally selected answer as answered before server persistence succeeds', () => {
    expect(deriveQuestionState({
      current: false,
      marked: false,
      persisted: false,
      confirmed: false,
    })).toBe('unanswered')
  })

  it('marks an answer only after server persistence succeeds and keeps current visually distinct', () => {
    expect(deriveQuestionState({
      current: false,
      marked: false,
      persisted: true,
      confirmed: false,
    })).toBe('answered')
    expect(deriveQuestionState({
      current: true,
      marked: false,
      persisted: true,
      confirmed: false,
    })).toBe('current')
  })

  it('opens single-item settlement only for explicit Mock previews with the feature flag', () => {
    expect(isSingleItemSettlementAvailable({ mockEnabled: false, featureEnabled: true })).toBe(false)
    expect(isSingleItemSettlementAvailable({ mockEnabled: true, featureEnabled: false })).toBe(false)
    expect(isSingleItemSettlementAvailable({ mockEnabled: true, featureEnabled: true })).toBe(true)
  })

  it('uses green and red states for settled correct and wrong answers', () => {
    expect(deriveQuestionState({ current: false, marked: false, persisted: true, confirmed: true, result: 'CORRECT' })).toBe('correct')
    expect(deriveQuestionState({ current: false, marked: false, persisted: true, confirmed: true, result: 'WRONG' })).toBe('wrong')
    expect(deriveQuestionState({ current: false, marked: true, persisted: true, confirmed: true, result: 'WRONG' })).toBe('wrong-marked')
  })

  it('keeps every question reachable through the answer-card scroll region', () => {
    expect(buildVisibleQuestionIndexes(0)).toEqual([])
    expect(buildVisibleQuestionIndexes(80)).toEqual(Array.from({ length: 80 }, (_, index) => index))
  })
})
