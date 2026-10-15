import type { EssayEditorData, EssayHomeData, EssayReportData } from '../types'
import type { EssayGateway } from './essay.gateway'
import { editorPreview, homePreview, reportPreview } from '../preview-data'
import { EssaySaveConflictError, type EssaySaveResult } from '../autosave-controller'
import type { EssayReviewQuestionRequest } from '../types'

export class PreviewEssayGateway implements EssayGateway {
  readonly capabilities = { conflictRetry: true, reviewQuestions: false }
  loadHome(): Promise<EssayHomeData> { return Promise.resolve(structuredClone(homePreview)) }
  loadEditor(): Promise<EssayEditorData> { return Promise.resolve(structuredClone(editorPreview)) }
  loadReport(): Promise<EssayReportData> { return Promise.resolve(structuredClone(reportPreview)) }
  saveDraft(_draft: EssayEditorData, baseVersion: number, localRevision: number): Promise<EssaySaveResult> {
    if (sessionStorage.getItem('essay-preview-save-mode') === 'conflict') {
      sessionStorage.removeItem('essay-preview-save-mode')
      const server = structuredClone(editorPreview.paragraphs)
      server[2]!.text = '服务器版本：调研样本为72份，并保留了上一轮审核意见。'
      return Promise.reject(new EssaySaveConflictError('检测到版本冲突', {
        paragraphs: server,
        baseVersion: baseVersion + 2,
        updatedAt: '2026-07-12T22:00:00Z',
      }))
    }
    return new Promise((resolve) => window.setTimeout(() => resolve({
      confirmedRevision: localRevision,
      baseVersion: baseVersion + 1,
      updatedAt: new Date().toISOString(),
    }), 180))
  }
  askReviewQuestion(_request: EssayReviewQuestionRequest): Promise<{ answer: string }> {
    return Promise.reject(new Error('批改问题追问接口尚未联调'))
  }
}
