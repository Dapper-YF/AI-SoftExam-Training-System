import type {
  PagedResult,
  PracticeAnswerPayload,
  PracticeAttempt,
  PracticeCreateCaseAttemptInput,
  PracticeCatalog,
  PracticeCreateAttemptInput,
  PracticeListQuery,
  PracticeHistoryQuery,
  PracticeHistoryRecord,
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

export interface PracticeGateway {
  getCatalog(qualificationId: string): Promise<PracticeCatalog>
  listPapers(query: PracticeListQuery): Promise<PagedResult<PracticePaperSummary>>
  createAttempt(input: PracticeCreateAttemptInput): Promise<PracticeAttempt>
  createCaseAttempt(input: PracticeCreateCaseAttemptInput): Promise<PracticeAttempt>
  getAttempt(attemptId: string): Promise<PracticeAttempt>
  listAttemptHistory(query: PracticeHistoryQuery): Promise<PagedResult<PracticeHistoryRecord>>
  listMistakes(query: PracticeMistakeQuery): Promise<PagedResult<PracticeMistakeRecord>>
  listFavorites(page: number, pageSize: number): Promise<PagedResult<PracticeFavoriteRecord>>
  updateFavoriteGroup(questionId: string, favoriteGroup?: string): Promise<PracticeFavoriteRecord>
  saveAnswer(
    attemptId: string,
    questionId: string,
    subQuestionId: string,
    answer: PracticeAnswerPayload,
    marked: boolean,
    options?: PracticeSaveAnswerOptions,
  ): Promise<PracticeSaveAnswerResult>
  settleQuestion(
    attemptId: string,
    questionId: string,
    baseVersion: number,
    clientMutationId?: string,
  ): Promise<PracticeMutationResult>
  submitAttempt(
    attemptId: string,
    baseVersion: number,
    clientMutationId?: string,
  ): Promise<PracticeMutationResult>
  getExplanation(attemptId: string, questionId: string): Promise<PracticeQuestionExplanation>
  listRecommendations(questionId: string, limit?: number): Promise<PracticeRecommendation[]>
  replaceMistakeReasons(questionId: string, reasonCodes: PracticeMistakeReasonCode[]): Promise<void>
  getMistakeReasons(questionId: string): Promise<PracticeMistakeReason[]>
  listMistakeReasonStatistics(): Promise<PracticeMistakeReasonStat[]>
  setFavorite(questionId: string, favorite: boolean): Promise<void>
  saveNote(questionId: string, content: string): Promise<void>
}
