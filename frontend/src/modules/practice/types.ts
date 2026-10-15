export type PracticeSubjectType = 'COMPREHENSIVE' | 'CASE' | 'ESSAY' | 'APPLICATION'
export type PracticeMode = 'DAILY' | 'PAST_EXAM' | 'CHAPTER' | 'KNOWLEDGE_POINT'
export type PracticeDifficulty = 'EASY' | 'MEDIUM' | 'HARD'
export type PracticeLoadStatus = 'idle' | 'loading' | 'success' | 'empty' | 'error'
export type PracticeAttemptStatus = 'IN_PROGRESS' | 'SUBMITTED' | 'PENDING_REVIEW'
export type PracticeFeedbackMode = 'PER_QUESTION' | 'WHOLE_PAPER'
export type PracticeSettlementStatus = 'UNSETTLED' | 'SETTLED'
export type PracticeQuestionType =
  | 'SINGLE_CHOICE'
  | 'MULTI_CHOICE'
  | 'JUDGE'
  | 'CASE'
  | 'ESSAY'
  | 'SHORT_ANSWER'
  | 'ESSAY_WRITING'
  | 'CALCULATION'
  | 'CODE_GAP'
  | 'FILL_BLANK'
  | 'FLOWCHART'
  | 'UML_GRAPH'
  | 'REQUIREMENT_SPEC'
  | 'ARCHITECTURE_DESIGN'
  | 'SERVICE_DESIGN'
  | 'METRIC_CONFIGURATION'
export type PracticeAnswerFormat = 'OPTION' | 'BOOLEAN' | 'TEXT' | 'NUMBER' | 'JSON'
export type PracticeExplanationStatus = 'READY' | 'IMPROVING'

export type PracticeAnswerPayload =
  | { optionKey: string }
  | { optionKeys: string[] }
  | { value: boolean }
  | { text: string }
  | { structured: Record<string, unknown> }

export type PracticeAnswerValue = string | string[] | boolean | number | Record<string, unknown>

export interface PracticeScoringPoint {
  scoringPointId: string
  pointCode: string
  pointText: string
  score: number
  sortOrder: number
  contentHash?: string
}

export type PracticeAttemptSource =
  | { type: 'DAILY' }
  | { type: 'YEAR'; yearNo: number }
  | { type: 'PAPER'; paperId: string }
  | { type: 'KNOWLEDGE'; knowledgeType: 'CHAPTER' | 'SYLLABUS' | 'TAG'; knowledgeIds: string[] }

export interface PracticeCreateAttemptInput {
  qualificationId: string
  subjectId?: string
  mode: PracticeMode
  feedbackMode: PracticeFeedbackMode
  count: number
  source: PracticeAttemptSource
  clientMutationId?: string
}

export interface PagedResult<T> {
  items: T[]
  total: number
  page: number
  pageSize: number
}

export interface PracticeListQuery {
  qualificationId: string
  subjectId?: string
  mode?: PracticeMode
  chapter?: string
  knowledgePoint?: string
  year?: string
  difficulty?: PracticeDifficulty
  keyword?: string
  page: number
  pageSize: number
}

export interface PracticePaperSummary {
  id: string
  title: string
  qualificationId: string
  subjectId?: string
  paperType: string
  yearNo?: number
  totalScore?: number
  durationMinutes?: number
  status: string
}

export interface PracticeCatalogQuestionType {
  questionType: PracticeQuestionType
  count: number
}

export interface PracticeCatalogSubject {
  subjectId: string
  subjectName: string
  subjectType: PracticeSubjectType
  available: boolean
  unavailableReason?: string
  questionCount: number
  questionTypes: PracticeCatalogQuestionType[]
}

export interface PracticeCatalog {
  qualificationId: string
  available: boolean
  unavailableReason?: string
  questionCount: number
  paperCount: number
  paperYears: number[]
  subjects: PracticeCatalogSubject[]
  papers?: {
    available: boolean
    unavailableReason?: string
  }
}

export interface PracticeOption {
  code: string
  content: string
}

export interface PracticeSubQuestion {
  subQuestionId: string
  type: PracticeQuestionType
  answerFormat: PracticeAnswerFormat
  content: string
  metadata?: Record<string, unknown>
  options: PracticeOption[]
  answerVersion: number
  marked: boolean
  score?: number
  gradingStatus?: 'SAVED' | 'CORRECT' | 'WRONG' | 'PENDING_REVIEW'
  standardAnswer?: PracticeAnswerValue
  earnedScore?: number
  solution?: string
  solutionStatus?: PracticeExplanationStatus
  scoringPoints?: PracticeScoringPoint[]
}

export interface PracticeQuestion {
  questionId: string
  questionVersionId: string
  subQuestionId: string
  number: number
  typeLabel: string
  questionType: PracticeQuestionType
  knowledgePoint: string
  stem: string
  options: PracticeOption[]
  settlementStatus: PracticeSettlementStatus
  settlementVersion: number
  subQuestions: PracticeSubQuestion[]
  correctAnswer?: PracticeAnswerValue
  explanation?: string
  source?: string
}

export interface PracticeAttempt {
  id: string
  paperId: string
  title: string
  mode?: PracticeMode
  sourceType?: PracticeAttemptSource['type'] | string
  sourceSpec?: Record<string, unknown>
  qualificationName: string
  subjectName: string
  createdAt: string
  status: PracticeAttemptStatus
  version: number
  feedbackMode: PracticeFeedbackMode
  questions: PracticeQuestion[]
  answers: Record<string, PracticeAnswerValue>
  answerVersions: Record<string, number>
  markedQuestionIds: string[]
  favoriteQuestionIds: string[]
  notes: Record<string, string>
  totalScore?: number
  maxScore?: number
  correctCount?: number
  wrongCount?: number
  pendingReviewCount?: number
  submittedAt?: string
}

export interface PracticeSaveAnswerOptions {
  baseVersion?: number
  clientMutationId?: string
  usedSeconds?: number
}

export interface PracticeSaveAnswerResult {
  answerVersion: number
  savedAt: string
}

export interface PracticeMutationResult {
  attemptId: string
  questionId?: string
  status: PracticeAttemptStatus
  version: number
  totalScore?: number
  maxScore?: number
  correctCount?: number
  wrongCount?: number
  pendingReviewCount?: number
  submittedAt?: string
  idempotentReplay: boolean
}

export interface PracticeQuestionExplanation {
  attemptId: string
  questionId: string
  questionVersionId?: string
  contentHash?: string
  status?: PracticeExplanationStatus
  subQuestions: Array<{
    subQuestionId: string
    standardAnswer: PracticeAnswerValue
    solution?: string
    solutionStatus?: PracticeExplanationStatus
    scoringPoints?: PracticeScoringPoint[]
  }>
  recommendations?: PracticeRecommendation[]
}

export interface PracticeRecommendation {
  questionId: string
  qualificationId: string
  subjectId: string
  questionType: PracticeQuestionType
  title?: string
  difficulty?: string
  knowledgeOverlap: number
}
export type PracticeMistakeReasonCode = 'CONCEPT' | 'CALCULATION' | 'MISREADING'
export interface PracticeMistakeReason { reasonCode: PracticeMistakeReasonCode; source: 'SYSTEM' | 'STUDENT'; suggestedAt?: string; confirmedAt?: string }
export interface PracticeMistakeReasonStat { reasonCode: PracticeMistakeReasonCode; count: number }

export interface PracticeCreateCaseAttemptInput {
  qualificationId: string
  subjectId: string
  questionId: string
  clientMutationId?: string
}

export interface PracticeHistoryRecord {
  id: string
  status: PracticeAttemptStatus
  sourceType: 'KNOWLEDGE' | 'YEAR' | 'PAPER' | 'DAILY'
  sourceSpec: Record<string, unknown>
  questionCount: number
  startedAt: string
  submittedAt?: string
  durationSeconds?: number
  totalScore?: number
  maxScore?: number
}

export interface PracticeHistoryQuery {
  page: number
  pageSize: number
  qualificationId?: string
  subjectId?: string
  status?: PracticeAttemptStatus
}

export interface PracticeMistakeRecord {
  questionId: string
  qualificationId: string
  subjectId: string
  questionType: PracticeQuestionType
  questionTitle: string
  lastAttemptId: string
  mistakeCount: number
  lastWrongAt: string
  status: string
}

export interface PracticeMistakeQuery {
  page: number
  pageSize: number
  qualificationId?: string
  subjectId?: string
  status?: string
}

export interface PracticeFavoriteRecord {
  id: string
  questionId: string
  qualificationId: string
  subjectId?: string
  questionTitle: string
  favoriteGroup?: string
  createdAt: string
}

export type PracticeQuestionState =
  | 'current'
  | 'answered'
  | 'correct'
  | 'wrong'
  | 'marked'
  | 'answered-marked'
  | 'correct-marked'
  | 'wrong-marked'
  | 'unanswered'
