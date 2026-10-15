import type { EssayDraft, EssayTaskState } from './essay-state'

export interface EssayHomeData {
  workflow: Array<{ label: string; state: 'done' | 'active' | 'pending' }>
  currentDraft: { id: string; title: string; background: string; words: number; updatedAt: string } | null
  latestReview: { score: number; summary: string; issues: string[]; reviewedAt: string } | null
  drafts: Array<{ id: string; title: string; words: number; updatedAt: string }>
  backgrounds: Array<{ id: string; title: string; updatedAt: string }>
  materialStats: { total: number; process: number; evidence: number }
}

export interface EssayOutlineItem { id: string; label: string; words: number; level: number }
export interface EssayMaterial { id: string; title: string; type: string }
export interface EssaySuggestion { id: string; paragraphId: string; category: string; reason: string; content: string }
export interface EssayEditorData extends EssayDraft {
  qualification: string
  topic: string
  outline: EssayOutlineItem[]
  background: string
  materials: EssayMaterial[]
  suggestions: EssaySuggestion[]
}

export interface ReviewIssue {
  id: string
  paragraphId: string
  severity: '严重问题' | '一般问题' | '已优化建议'
  title: string
  deduction: number
  source: string
  reason: string
  suggestion: string
  rewrites: string[]
}
export interface EssayReportData {
  reviewTaskId: string
  resultId: string
  draftId: string
  title: string
  score: number
  previousDelta: number
  rubricVersion: string
  reviewedAt: string
  summary: string
  dimensions: Array<{ label: string; score: number; total: number; tone: 'primary' | 'teal' | 'amber' | 'coral' }>
  paragraphs: Array<{ id: string; index: number; text: string; issueIds: string[] }>
  issues: ReviewIssue[]
  taskState: EssayTaskState
}

export interface EssayReviewQuestionRequest {
  reviewTaskId: string
  resultId: string
  issueId: string
  question: string
}
