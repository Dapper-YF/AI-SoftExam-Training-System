import { ApiClientError, platformApiClient, unwrapApiResponse } from '@/api/client'
import { EssaySaveConflictError, type EssaySaveResult, type EssayServerSnapshot } from '../autosave-controller'
import type { EssayEditorData, EssayHomeData, EssayReportData, EssayReviewQuestionRequest } from '../types'
import type { EssayGateway } from './essay.gateway'

interface Page<T> { items: T[]; total?: number }
interface DraftDto {
  id: number; qualificationId: number; backgroundId?: number; title: string; outline?: string; content?: string
  wordCount?: number; writingSeconds?: number; status?: string; materials?: MaterialDto[]; updatedAt?: string
}
interface BackgroundDto { id: number; title: string; backgroundContent?: string; updatedAt?: string }
interface MaterialDto { id: number; title: string; materialType: string }
interface ReviewSummaryDto { taskId: number; totalScore: number; summary: string; createdAt: string }

function date(value?: string): string { return value ? value.replace('T', ' ').slice(0, 16) : '—' }

export class HttpEssayGateway implements EssayGateway {
  readonly capabilities = { conflictRetry: false, reviewQuestions: false }
  async loadHome(): Promise<EssayHomeData> {
    const [drafts, backgrounds, materials, reviews] = await Promise.all([
      unwrapApiResponse<Page<DraftDto>>(platformApiClient.get('/essay/drafts', { params: { pageNo: 1, pageSize: 5 } })),
      unwrapApiResponse<Page<BackgroundDto>>(platformApiClient.get('/essay/backgrounds', { params: { pageNo: 1, pageSize: 5 } })),
      unwrapApiResponse<Page<MaterialDto>>(platformApiClient.get('/essay/materials', { params: { pageNo: 1, pageSize: 100 } })),
      unwrapApiResponse<Page<ReviewSummaryDto>>(platformApiClient.get('/essay/review-results', { params: { pageNo: 1, pageSize: 1 } })).catch(() => ({ items: [] })),
    ])
    const current = drafts.items[0]
    const review = reviews.items[0]
    return {
      workflow: ['项目背景', '素材库', '论文写作', 'AI 批改', '修订', '学习'].map((label, index) => ({ label, state: index < 2 ? 'done' : index === 2 ? 'active' : 'pending' })),
      currentDraft: current ? { id: String(current.id), title: current.title, background: backgrounds.items.find((item) => item.id === current.backgroundId)?.title ?? '未绑定背景', words: current.wordCount ?? 0, updatedAt: date(current.updatedAt) } : null,
      latestReview: review ? { score: review.totalScore, summary: review.summary, issues: [], reviewedAt: date(review.createdAt) } : null,
      drafts: drafts.items.map((item) => ({ id: String(item.id), title: item.title, words: item.wordCount ?? 0, updatedAt: date(item.updatedAt) })),
      backgrounds: backgrounds.items.map((item) => ({ id: String(item.id), title: item.title, updatedAt: date(item.updatedAt).slice(0, 10) })),
      materialStats: {
        total: materials.total ?? materials.items.length,
        process: materials.items.filter((item) => item.materialType.includes('PROCESS')).length,
        evidence: materials.items.filter((item) => item.materialType.includes('EVIDENCE')).length,
      },
    }
  }

  async loadEditor(): Promise<EssayEditorData> {
    const page = await unwrapApiResponse<Page<DraftDto>>(platformApiClient.get('/essay/drafts', { params: { pageNo: 1, pageSize: 1 } }))
    if (!page.items[0]) throw Object.assign(new Error('论文资源不存在'), { status: 404 })
    const draft = await unwrapApiResponse<DraftDto>(platformApiClient.get(`/essay/drafts/${page.items[0].id}`))
    const background = draft.backgroundId
      ? await unwrapApiResponse<BackgroundDto>(platformApiClient.get(`/essay/backgrounds/${draft.backgroundId}`)).catch(() => null)
      : null
    const outlineLines = (draft.outline ?? '').split('\n').map((line) => line.trim()).filter(Boolean)
    const contentParts = (draft.content ?? '').split(/\n\s*\n/).map((line) => line.trim()).filter(Boolean)
    return {
      id: String(draft.id), title: draft.title, version: 0,
      qualification: `资格 ${draft.qualificationId}`, topic: '以草稿绑定主题为准',
      outline: outlineLines.map((label, index) => ({ id: `outline-${index + 1}`, label, words: 0, level: /^\d+\./.test(label) ? 1 : 0 })),
      background: background?.backgroundContent ?? '未绑定可读取的项目背景',
      materials: (draft.materials ?? []).map((item) => ({ id: String(item.id), title: item.title, type: item.materialType })),
      paragraphs: (contentParts.length ? contentParts : ['']).map((text, index) => ({ id: `paragraph-${index + 1}`, outlineId: `outline-${Math.min(index + 1, Math.max(outlineLines.length, 1))}`, text })),
      suggestions: [],
    }
  }

  async loadReport(): Promise<EssayReportData> {
    const page = await unwrapApiResponse<Page<ReviewSummaryDto>>(platformApiClient.get('/essay/review-results', { params: { pageNo: 1, pageSize: 1 } }))
    if (!page.items[0]) throw Object.assign(new Error('论文资源不存在'), { status: 404 })
    return unwrapApiResponse(platformApiClient.get(`/essay/review-tasks/${page.items[0].taskId}/result`))
  }

  async saveDraft(draft: EssayEditorData, baseVersion: number, _localRevision: number): Promise<EssaySaveResult> {
    try {
      const response = await unwrapApiResponse<DraftDto>(platformApiClient.put(`/essay/drafts/${draft.id}`, {
        title: draft.title,
        outline: draft.outline.map((item) => item.label).join('\n'),
        content: draft.paragraphs.map((item) => item.text).join('\n\n'),
        status: 'DRAFTING',
      }))
      return { baseVersion, updatedAt: response.updatedAt ?? new Date().toISOString() }
    } catch (reason) {
      if (reason instanceof ApiClientError && reason.status === 409) {
        throw new EssaySaveConflictError(reason.message, this.readServerSnapshot(reason, draft))
      }
      throw reason
    }
  }

  askReviewQuestion(_request: EssayReviewQuestionRequest): Promise<{ answer: string }> {
    return Promise.reject(new ApiClientError(
      '批改问题追问接口尚未联调',
      501,
      'ESSAY_REVIEW_QUESTION_NOT_CONNECTED',
      'essay-review-question-boundary',
    ))
  }

  private readServerSnapshot(error: ApiClientError, draft: EssayEditorData): EssayServerSnapshot | undefined {
    const details = error.details as unknown as {
      serverSnapshot?: { content?: unknown; baseVersion?: unknown; updatedAt?: unknown }
    }
    const raw = details?.serverSnapshot
    if (!raw || typeof raw.content !== 'string' || typeof raw.baseVersion !== 'number' || typeof raw.updatedAt !== 'string') return undefined
    const parts = raw.content.split(/\n\s*\n/)
    return {
      paragraphs: parts.map((text, index) => ({
        id: draft.paragraphs[index]?.id ?? `server-paragraph-${index + 1}`,
        outlineId: draft.paragraphs[index]?.outlineId ?? `server-outline-${index + 1}`,
        text,
      })),
      baseVersion: raw.baseVersion,
      updatedAt: raw.updatedAt,
    }
  }
}
