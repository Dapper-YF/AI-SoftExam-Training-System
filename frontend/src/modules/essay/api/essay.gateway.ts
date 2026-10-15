import type { EssaySaveResult } from '../autosave-controller'
import type { EssayEditorData, EssayHomeData, EssayReportData, EssayReviewQuestionRequest } from '../types'

export interface EssayGatewayCapabilities {
  conflictRetry: boolean
  reviewQuestions: boolean
}

export interface EssayGateway {
  readonly capabilities: EssayGatewayCapabilities
  loadHome(): Promise<EssayHomeData>
  loadEditor(): Promise<EssayEditorData>
  loadReport(): Promise<EssayReportData>
  saveDraft(draft: EssayEditorData, baseVersion: number, localRevision: number): Promise<EssaySaveResult>
  askReviewQuestion(request: EssayReviewQuestionRequest): Promise<{ answer: string }>
}
