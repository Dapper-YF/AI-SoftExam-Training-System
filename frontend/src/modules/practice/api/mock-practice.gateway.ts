import { ApiClientError } from '@/api/client'
import type {
  PagedResult,
  PracticeAnswerPayload,
  PracticeAnswerValue,
  PracticeAttempt,
  PracticeCatalog,
  PracticeCreateCaseAttemptInput,
  PracticeCreateAttemptInput,
  PracticeHistoryQuery,
  PracticeHistoryRecord,
  PracticeListQuery,
  PracticeMistakeQuery,
  PracticeMistakeRecord,
  PracticeFavoriteRecord,
  PracticeMutationResult,
  PracticePaperSummary,
  PracticeQuestionExplanation,
  PracticeRecommendation,
  PracticeMistakeReasonCode,
  PracticeMistakeReason,
  PracticeMistakeReasonStat,
  PracticeSaveAnswerOptions,
  PracticeSaveAnswerResult,
} from '../types'
import type { PracticeGateway } from './practice.gateway'

const STORAGE_KEY = 'platform-practice-mock-attempts-v1'

async function loadPracticeFixtures(): Promise<typeof import('../mock/fixtures')> {
  if (!import.meta.env.DEV) {
    throw new ApiClientError('生产构建不包含刷题 Mock 数据', 503, 'PRACTICE_MOCK_NOT_AVAILABLE')
  }
  return import('../mock/fixtures')
}

function readAttempts(): Record<string, PracticeAttempt> {
  const raw = sessionStorage.getItem(STORAGE_KEY)
  if (!raw) return {}
  try { return JSON.parse(raw) as Record<string, PracticeAttempt> } catch { return {} }
}

function writeAttempts(attempts: Record<string, PracticeAttempt>): void {
  sessionStorage.setItem(STORAGE_KEY, JSON.stringify(attempts))
}

function valueFromPayload(payload: PracticeAnswerPayload): PracticeAnswerValue {
  if ('optionKey' in payload) return payload.optionKey
  if ('optionKeys' in payload) return payload.optionKeys
  if ('value' in payload) return payload.value
  if ('structured' in payload) return payload.structured
  return payload.text
}

function answersMatch(actual: PracticeAnswerValue | undefined, expected: PracticeAnswerValue | undefined): boolean {
  if (Array.isArray(actual) || Array.isArray(expected)) {
    if (!Array.isArray(actual) || !Array.isArray(expected)) return false
    return [...actual].sort().join(',') === [...expected].sort().join(',')
  }
  return actual === expected
}

export class MockPracticeGateway implements PracticeGateway {
  async getCatalog(qualificationId: string): Promise<PracticeCatalog> {
    const { practicePapers, practiceQuestions, essayPracticePaper, essayPracticeQuestion } = await loadPracticeFixtures()
    const allPapers = [...practicePapers, essayPracticePaper]
    const subjects = new Map<string, PracticeCatalog['subjects'][number]>()
    allPapers.filter((paper) => paper.qualificationId === qualificationId).forEach((paper) => {
      if (!paper.subjectId || subjects.has(paper.subjectId)) return
      const isEssay = paper.subjectId === essayPracticePaper.subjectId
      subjects.set(paper.subjectId, {
        subjectId: paper.subjectId,
        subjectName: isEssay ? '论文' : paper.subjectId,
        subjectType: isEssay ? 'ESSAY' : 'COMPREHENSIVE',
        available: isEssay || practiceQuestions.length > 0,
        questionCount: isEssay ? 1 : practiceQuestions.length,
        questionTypes: [{ questionType: isEssay ? 'ESSAY' : 'SINGLE_CHOICE', count: isEssay ? 1 : practiceQuestions.length }],
      })
    })
    const paperCount = allPapers.filter((paper) => paper.qualificationId === qualificationId).length
    return {
      qualificationId,
      available: subjects.size > 0 || paperCount > 0,
      questionCount: [...subjects.values()].reduce((sum, subject) => sum + subject.questionCount, 0),
      paperCount,
      paperYears: [...new Set(allPapers.map((paper) => paper.yearNo).filter((year): year is number => year != null))],
      subjects: [...subjects.values()],
      papers: { available: paperCount > 0 },
    }
  }

  async listPapers(query: PracticeListQuery): Promise<PagedResult<PracticePaperSummary>> {
    const { practicePapers, essayPracticePaper } = await loadPracticeFixtures()
    const filtered = [...practicePapers, essayPracticePaper].filter((paper) =>
      paper.qualificationId === query.qualificationId
      && (!query.subjectId || paper.subjectId === query.subjectId)
      && (!query.year || query.year === 'ALL' || paper.yearNo === Number.parseInt(query.year, 10)),
    )
    const start = (query.page - 1) * query.pageSize
    return { items: structuredClone(filtered.slice(start, start + query.pageSize)), total: filtered.length, page: query.page, pageSize: query.pageSize }
  }

  async createAttempt(input: PracticeCreateAttemptInput): Promise<PracticeAttempt> {
    const { practicePapers, practiceQuestions, essayPracticePaper, essayPracticeQuestion } = await loadPracticeFixtures()
    const allPapers = [...practicePapers, essayPracticePaper]
    const paperId = input.source.type === 'PAPER' ? input.source.paperId : ''
    const paper = paperId ? allPapers.find((item) => item.id === paperId) : undefined
    if (paperId && !paper) throw new ApiClientError('练习试卷不存在', 404, 'PAPER_NOT_FOUND', 'mock-practice-paper')
    const attempts = readAttempts()
    const attemptId = `attempt-${paperId || input.clientMutationId || crypto.randomUUID()}`
    const existing = attempts[attemptId]
    if (existing) return structuredClone(existing)
    const attempt: PracticeAttempt = {
      id: attemptId,
      paperId,
      title: paper?.title ?? '普通练习（显式 DEV Mock）',
      mode: input.mode,
      sourceType: input.source.type,
      sourceSpec: input.source.type === 'KNOWLEDGE'
        ? { knowledgeType: input.source.knowledgeType, knowledgeIds: input.source.knowledgeIds }
        : input.source.type === 'PAPER' ? { paperId: input.source.paperId }
          : input.source.type === 'YEAR' ? { yearNo: input.source.yearNo }
            : { daily: true },
      qualificationName: input.qualificationId,
      subjectName: input.subjectId ?? '整卷',
      createdAt: new Date().toISOString(),
      status: 'IN_PROGRESS',
      version: 0,
      feedbackMode: input.feedbackMode,
      questions: structuredClone(input.subjectId === essayPracticePaper.subjectId || paperId === essayPracticePaper.id
        ? [essayPracticeQuestion]
        : practiceQuestions.slice(0, input.count)),
      answers: {},
      answerVersions: {},
      markedQuestionIds: [],
      favoriteQuestionIds: [],
      notes: {},
    }
    attempts[attempt.id] = attempt
    writeAttempts(attempts)
    return structuredClone(attempt)
  }

  async createCaseAttempt(input: PracticeCreateCaseAttemptInput): Promise<PracticeAttempt> {
    return this.createAttempt({
      qualificationId: input.qualificationId,
      subjectId: input.subjectId,
      mode: 'KNOWLEDGE_POINT',
      feedbackMode: 'WHOLE_PAPER',
      count: 1,
      source: { type: 'KNOWLEDGE', knowledgeType: 'CHAPTER', knowledgeIds: [input.questionId] },
      clientMutationId: input.clientMutationId,
    })
  }

  async getAttempt(attemptId: string): Promise<PracticeAttempt> {
    const attempt = readAttempts()[attemptId]
    if (!attempt) throw new ApiClientError('练习记录不存在', 404, 'ATTEMPT_NOT_FOUND', 'mock-practice-attempt')
    return structuredClone(attempt)
  }

  async listAttemptHistory(query: PracticeHistoryQuery): Promise<PagedResult<PracticeHistoryRecord>> {
    const all = Object.values(readAttempts()).map((attempt): PracticeHistoryRecord => ({
      id: attempt.id, status: attempt.status,
      sourceType: (attempt.sourceType as PracticeHistoryRecord['sourceType'])
        ?? (attempt.paperId ? 'PAPER' : 'DAILY'),
      sourceSpec: attempt.sourceSpec
        ?? (attempt.paperId ? { paperId: attempt.paperId } : { daily: true }),
      questionCount: attempt.questions.length, startedAt: attempt.createdAt,
    })).sort((left, right) => right.startedAt.localeCompare(left.startedAt))
    const start = (query.page - 1) * query.pageSize
    return { items: all.slice(start, start + query.pageSize), total: all.length, page: query.page, pageSize: query.pageSize }
  }

  async listMistakes(query: PracticeMistakeQuery): Promise<PagedResult<PracticeMistakeRecord>> {
    return { items: [], total: 0, page: query.page, pageSize: query.pageSize }
  }

  async listFavorites(page: number, pageSize: number): Promise<PagedResult<PracticeFavoriteRecord>> {
    const rows: PracticeFavoriteRecord[] = []
    const start = (page - 1) * pageSize
    return { items: rows.slice(start, start + pageSize), total: rows.length, page, pageSize }
  }

  async updateFavoriteGroup(questionId: string, favoriteGroup?: string): Promise<PracticeFavoriteRecord> {
    throw new ApiClientError(`收藏题 ${questionId} 不存在，无法归类为 ${favoriteGroup ?? '未分类'}`, 404, 'FAVORITE_NOT_FOUND')
  }

  async saveAnswer(
    attemptId: string,
    questionId: string,
    subQuestionId: string,
    answer: PracticeAnswerPayload,
    marked: boolean,
    _options: PracticeSaveAnswerOptions = {},
  ): Promise<PracticeSaveAnswerResult> {
    const attempts = readAttempts()
    const attempt = attempts[attemptId]
    if (!attempt) throw new ApiClientError('练习记录不存在', 404, 'ATTEMPT_NOT_FOUND', 'mock-practice-answer')
    attempt.answers[subQuestionId] = valueFromPayload(answer)
    attempt.answerVersions[subQuestionId] = (attempt.answerVersions[subQuestionId] ?? 0) + 1
    attempt.markedQuestionIds = marked
      ? Array.from(new Set([...attempt.markedQuestionIds, questionId]))
      : attempt.markedQuestionIds.filter((id) => id !== questionId)
    writeAttempts(attempts)
    return { answerVersion: attempt.answerVersions[subQuestionId]!, savedAt: new Date().toISOString() }
  }

  async settleQuestion(attemptId: string, questionId: string): Promise<PracticeMutationResult> {
    const attempts = readAttempts()
    const attempt = attempts[attemptId]
    if (!attempt) throw new ApiClientError('练习记录不存在', 404, 'ATTEMPT_NOT_FOUND')
    const question = attempt.questions.find((item) => item.questionId === questionId)
    if (!question) throw new ApiClientError('题目不存在', 404, 'QUESTION_NOT_FOUND')
    question.settlementStatus = 'SETTLED'
    question.settlementVersion += 1
    question.subQuestions.forEach((sub) => {
      sub.gradingStatus = answersMatch(attempt.answers[sub.subQuestionId], sub.standardAnswer) ? 'CORRECT' : 'WRONG'
    })
    attempt.version += 1
    writeAttempts(attempts)
    return { attemptId, questionId, status: attempt.status, version: attempt.version, idempotentReplay: false }
  }

  async submitAttempt(attemptId: string): Promise<PracticeMutationResult> {
    const attempts = readAttempts()
    const attempt = attempts[attemptId]
    if (!attempt) throw new ApiClientError('练习记录不存在', 404, 'ATTEMPT_NOT_FOUND')
    attempt.status = 'SUBMITTED'
    attempt.version += 1
    attempt.submittedAt = new Date().toISOString()
    writeAttempts(attempts)
    return { attemptId, status: attempt.status, version: attempt.version, submittedAt: attempt.submittedAt, idempotentReplay: false }
  }

  async getExplanation(attemptId: string, questionId: string): Promise<PracticeQuestionExplanation> {
    const attempt = await this.getAttempt(attemptId)
    const question = attempt.questions.find((item) => item.questionId === questionId)
    if (!question || (attempt.status === 'IN_PROGRESS' && question.settlementStatus !== 'SETTLED')) {
      throw new ApiClientError('题目尚未结算', 403, 'PRACTICE_EXPLANATION_NOT_AVAILABLE')
    }
    return {
      attemptId,
      questionId,
      subQuestions: question.subQuestions.map((sub) => ({
        subQuestionId: sub.subQuestionId,
        standardAnswer: sub.standardAnswer ?? '',
        solution: sub.solution ?? '',
      })),
    }
  }

  async listRecommendations(_questionId: string, _limit = 6): Promise<PracticeRecommendation[]> { return [] }
  async replaceMistakeReasons(_questionId: string, _reasonCodes: PracticeMistakeReasonCode[]): Promise<void> { return undefined }
  async getMistakeReasons(_questionId: string): Promise<PracticeMistakeReason[]> { return [] }
  async listMistakeReasonStatistics(): Promise<PracticeMistakeReasonStat[]> { return [] }

  async setFavorite(questionId: string, favorite: boolean): Promise<void> {
    const attempts = readAttempts()
    Object.values(attempts).forEach((attempt) => {
      if (!attempt.questions.some((question) => question.questionId === questionId)) return
      attempt.favoriteQuestionIds = favorite
        ? Array.from(new Set([...attempt.favoriteQuestionIds, questionId]))
        : attempt.favoriteQuestionIds.filter((id) => id !== questionId)
    })
    writeAttempts(attempts)
  }

  async saveNote(questionId: string, content: string): Promise<void> {
    const attempts = readAttempts()
    Object.values(attempts).forEach((attempt) => {
      if (attempt.questions.some((question) => question.questionId === questionId)) attempt.notes[questionId] = content
    })
    writeAttempts(attempts)
  }
}
