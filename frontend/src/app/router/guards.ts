import type { RouteLocationNormalized } from 'vue-router'
import type { ReturnTypeOfExamStore } from './types'

export type ExamRouteStage = 'ENTRY' | 'LOGIN' | 'CANDIDATE' | 'RULES' | 'EXAM'

export function resolveExamGuardRedirect(
  targetStage: ExamRouteStage | undefined,
  store: ReturnTypeOfExamStore,
): string | null {
  if (!targetStage || targetStage === 'ENTRY' || targetStage === 'LOGIN') return null
  if (!store.hasSession) return '/exam-simulator'
  if (targetStage === 'CANDIDATE') return null
  if (!store.candidateConfirmed) {
    return `/exam-simulator/session/${store.sessionId}/candidate`
  }
  if (targetStage === 'RULES') return null
  if (!store.rulesAcknowledged || !store.examHasStarted) {
    return `/exam-simulator/session/${store.sessionId}/rules`
  }
  return null
}

export function routeSessionMatches(to: RouteLocationNormalized, sessionId: string | null): boolean {
  const routeSessionId = typeof to.params.sessionId === 'string' ? to.params.sessionId : null
  return !routeSessionId || routeSessionId === sessionId
}
