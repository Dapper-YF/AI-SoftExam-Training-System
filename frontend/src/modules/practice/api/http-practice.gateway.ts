import { platformApiClient, unwrapApiResponse } from '@/api/client'
import type {
  PagedResult,
  PracticeAnswerPayload,
  PracticeAttempt,
  PracticeCreateCaseAttemptInput,
  PracticeCatalog,
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
import {
  adaptPracticeAttempt,
  adaptPracticeCatalog,
  adaptPracticeExplanation,
  adaptPracticeHistory,
  adaptPracticeMistakes,
  adaptPracticeMutationResult,
  adaptPracticePaperPage,
  adaptPracticeSaveAnswerResult,
  adaptPracticeFavorites,
  buildCreateCaseAttemptBody,
  buildCreateAttemptBody,
  buildHistoryParams,
  buildListParams,
  buildMistakeParams,
  buildNoteBody,
  buildSaveAnswerBody,
  buildVersionMutationBody,
} from './practice-contract-adapter'

export class HttpPracticeGateway implements PracticeGateway {
  async getCatalog(qualificationId: string): Promise<PracticeCatalog> {
    return adaptPracticeCatalog(await unwrapApiResponse<unknown>(platformApiClient.get('/public/practice/catalog', {
      params: { qualificationId },
    })))
  }

  async listPapers(query: PracticeListQuery): Promise<PagedResult<PracticePaperSummary>> {
    return adaptPracticePaperPage(await unwrapApiResponse<unknown>(
      platformApiClient.get('/public/papers', { params: buildListParams(query) }),
    ))
  }

  async createAttempt(input: PracticeCreateAttemptInput): Promise<PracticeAttempt> {
    return adaptPracticeAttempt(await unwrapApiResponse<unknown>(
      platformApiClient.post('/practice/attempts', buildCreateAttemptBody(input)),
    ))
  }

  async createCaseAttempt(input: PracticeCreateCaseAttemptInput): Promise<PracticeAttempt> {
    return adaptPracticeAttempt(await unwrapApiResponse<unknown>(
      platformApiClient.post('/practice/case-attempts', buildCreateCaseAttemptBody(input)),
    ))
  }

  async getAttempt(attemptId: string): Promise<PracticeAttempt> {
    return adaptPracticeAttempt(await unwrapApiResponse<unknown>(
      platformApiClient.get(`/practice/attempts/${attemptId}`),
    ))
  }

  async listAttemptHistory(query: PracticeHistoryQuery): Promise<PagedResult<PracticeHistoryRecord>> {
    return adaptPracticeHistory(await unwrapApiResponse<unknown>(platformApiClient.get('/practice/attempts', {
      params: buildHistoryParams(query),
    })))
  }

  async listMistakes(query: PracticeMistakeQuery): Promise<PagedResult<PracticeMistakeRecord>> {
    return adaptPracticeMistakes(await unwrapApiResponse<unknown>(platformApiClient.get('/practice/mistakes', {
      params: buildMistakeParams(query),
    })))
  }

  async listFavorites(page: number, pageSize: number): Promise<PagedResult<PracticeFavoriteRecord>> {
    return adaptPracticeFavorites(await unwrapApiResponse<unknown>(platformApiClient.get('/practice/favorites', { params: { pageNo: page, pageSize } })))
  }

  async updateFavoriteGroup(questionId: string, favoriteGroup?: string): Promise<PracticeFavoriteRecord> {
    const result = await unwrapApiResponse<unknown>(platformApiClient.put(`/practice/favorites/${questionId}/group`, { questionId, favoriteGroup }))
    return adaptPracticeFavorites({ items: [result], total: 1, pageNo: 1, pageSize: 1 }).items[0]!
  }

  async saveAnswer(
    attemptId: string,
    _questionId: string,
    subQuestionId: string,
    answer: PracticeAnswerPayload,
    marked: boolean,
    options: PracticeSaveAnswerOptions = {},
  ): Promise<PracticeSaveAnswerResult> {
    return adaptPracticeSaveAnswerResult(await unwrapApiResponse<unknown>(platformApiClient.put(
      `/practice/attempts/${attemptId}/answers/${subQuestionId}`,
      buildSaveAnswerBody({
        answerPayload: answer,
        marked,
        usedSeconds: options.usedSeconds,
        baseVersion: options.baseVersion ?? 0,
        clientMutationId: options.clientMutationId ?? crypto.randomUUID(),
      }),
    )))
  }

  async settleQuestion(
    attemptId: string,
    questionId: string,
    baseVersion: number,
    clientMutationId?: string,
  ): Promise<PracticeMutationResult> {
    return adaptPracticeMutationResult(await unwrapApiResponse<unknown>(platformApiClient.post(
      `/practice/attempts/${attemptId}/questions/${questionId}/settle`,
      buildVersionMutationBody(baseVersion, clientMutationId),
    )))
  }

  async submitAttempt(
    attemptId: string,
    baseVersion: number,
    clientMutationId?: string,
  ): Promise<PracticeMutationResult> {
    return adaptPracticeMutationResult(await unwrapApiResponse<unknown>(platformApiClient.post(
      `/practice/attempts/${attemptId}/submit`,
      buildVersionMutationBody(baseVersion, clientMutationId),
    )))
  }

  async getExplanation(attemptId: string, questionId: string): Promise<PracticeQuestionExplanation> {
    return adaptPracticeExplanation(await unwrapApiResponse<unknown>(platformApiClient.get(
      `/practice/attempts/${attemptId}/questions/${questionId}/explanation`,
    )))
  }

  async listRecommendations(questionId: string, limit = 6): Promise<PracticeRecommendation[]> {
    return unwrapApiResponse<PracticeRecommendation[]>(platformApiClient.get(
      `/practice/questions/${questionId}/recommendations`, { params: { limit }, suppressGlobalErrorRedirect: true },
    ))
  }

  async replaceMistakeReasons(questionId: string, reasonCodes: PracticeMistakeReasonCode[]): Promise<void> {
    await unwrapApiResponse(platformApiClient.put(`/practice/mistake-reasons/${questionId}`, { reasonCodes }))
  }
  async getMistakeReasons(questionId: string): Promise<PracticeMistakeReason[]> {
    return unwrapApiResponse<PracticeMistakeReason[]>(platformApiClient.get(
      `/practice/mistake-reasons/${questionId}`, { suppressGlobalErrorRedirect: true },
    ))
  }
  async listMistakeReasonStatistics(): Promise<PracticeMistakeReasonStat[]> {
    return unwrapApiResponse<PracticeMistakeReasonStat[]>(platformApiClient.get(
      '/practice/mistake-reasons/statistics', { suppressGlobalErrorRedirect: true },
    ))
  }

  setFavorite(questionId: string, favorite: boolean): Promise<void> {
    return favorite
      ? unwrapApiResponse(platformApiClient.post('/practice/favorites', { questionId }))
      : unwrapApiResponse(platformApiClient.delete(`/practice/favorites/${questionId}`))
  }

  saveNote(questionId: string, content: string): Promise<void> {
    return unwrapApiResponse(platformApiClient.post('/practice/notes', buildNoteBody(questionId, content)))
  }
}
