import type { PracticeQuestionState } from '../types'

export function deriveQuestionState(input: {
  current: boolean
  marked: boolean
  persisted: boolean
  confirmed: boolean
  result?: 'CORRECT' | 'WRONG'
}): PracticeQuestionState {
  if (input.current) return 'current'
  if (input.persisted && input.result === 'WRONG' && input.marked) return 'wrong-marked'
  if (input.persisted && input.result === 'WRONG') return 'wrong'
  if (input.persisted && input.result === 'CORRECT' && input.marked) return 'correct-marked'
  if (input.persisted && input.result === 'CORRECT') return 'correct'
  if (input.persisted && input.marked) return 'answered-marked'
  if (input.persisted) return 'answered'
  if (input.marked) return 'marked'
  return 'unanswered'
}

export function isSingleItemSettlementAvailable(input: {
  mockEnabled: boolean
  featureEnabled: boolean
}): boolean {
  return input.mockEnabled && input.featureEnabled
}

export function buildVisibleQuestionIndexes(count: number): number[] {
  return Array.from({ length: count }, (_, index) => index)
}
