import type { PracticeMode } from './types'

const STORAGE_KEY = 'platform-practice-session-presentation-v1'

export interface PracticeSessionPresentation {
  mode: PracticeMode
  scopeName?: string
  returnPath?: string
}

function readAll(): Record<string, PracticeSessionPresentation> {
  const raw = sessionStorage.getItem(STORAGE_KEY)
  if (!raw) return {}
  try {
    return JSON.parse(raw) as Record<string, PracticeSessionPresentation>
  } catch {
    return {}
  }
}

export function rememberPracticeSessionPresentation(
  attemptId: string,
  presentation: PracticeSessionPresentation,
): void {
  sessionStorage.setItem(STORAGE_KEY, JSON.stringify({
    ...readAll(),
    [attemptId]: presentation,
  }))
}

export function readPracticeSessionPresentation(attemptId: string): PracticeSessionPresentation | undefined {
  const presentation = readAll()[attemptId]
  if (!presentation || !['DAILY', 'PAST_EXAM', 'CHAPTER', 'KNOWLEDGE_POINT'].includes(presentation.mode)) return undefined
  return presentation
}
