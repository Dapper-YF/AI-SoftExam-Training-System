export const ADMIN_QUESTION_TYPES = ['SINGLE_CHOICE', 'MULTI_CHOICE', 'JUDGE', 'CASE', 'ESSAY'] as const
export const ADMIN_QUESTION_STATES = ['DRAFT', 'IN_REVIEW', 'PUBLISHED', 'RETIRED'] as const
export const ADMIN_DIFFICULTIES = ['EASY', 'MEDIUM', 'HARD'] as const
export const ADMIN_SOURCE_TYPES = ['MANUAL', 'ORIGINAL', 'AUTHORIZED_IMPORT', 'SOURCE_DOCUMENT_IMPORT'] as const
export const ADMIN_SOURCE_TYPE_LABELS: Record<typeof ADMIN_SOURCE_TYPES[number], string> = {
  MANUAL: '手工录入',
  ORIGINAL: '原创',
  AUTHORIZED_IMPORT: '授权导入',
  SOURCE_DOCUMENT_IMPORT: '文档导入',
}
export const ADMIN_COPYRIGHT_STATUSES = ['UNVERIFIED', 'ORIGINAL_DECLARED', 'AUTHORIZED'] as const

export type AdminQuestionType = typeof ADMIN_QUESTION_TYPES[number]
export type AdminQuestionState = typeof ADMIN_QUESTION_STATES[number]
export type AdminQuestionDifficulty = typeof ADMIN_DIFFICULTIES[number]
export type AdminQuestionSourceType = typeof ADMIN_SOURCE_TYPES[number]
export type AdminCopyrightStatus = typeof ADMIN_COPYRIGHT_STATUSES[number]
export type AdminReviewDecision = 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN'
export type AdminReviewCommandDecision = 'APPROVED' | 'REJECTED'
export type AdminQuestionGatewayKind = 'http' | 'mock'
export type AdminQuestionAnomalyType = 'MISSING_QUESTION_NO' | 'INCOMPLETE_STEM' | 'INCOMPLETE_OPTION' | 'MISSING_IMAGE'
export type AdminQuestionAnomalyStatus = 'OPEN' | 'IN_PROGRESS' | 'FIXED' | 'IGNORED' | 'FALSE_POSITIVE'
export interface AdminQuestionAnomaly {
  anomalyId: number
  questionId: number
  questionVersionId: number
  questionType: string
  title: string
  anomalyType: AdminQuestionAnomalyType
  status: AdminQuestionAnomalyStatus
  originalQuestionNo?: number | null
  updatedAt?: string
}
export type AdminAnswerFormat = 'OPTION_KEYS' | 'BOOLEAN' | 'TEXT' | 'NUMBER' | 'JSON'
export type AdminGradingMode = 'AUTO' | 'MANUAL' | 'AI_ASSISTED'

export interface AdminQuestionListQuery {
  pageNo: number
  pageSize: number
  questionId?: number
  keyword?: string
  qualificationId?: number
  subjectId?: number
  questionType?: AdminQuestionType | 'ALL'
  sourceType?: AdminQuestionSourceType | 'ALL'
  status?: AdminQuestionState | 'ALL'
  copyrightStatus?: AdminCopyrightStatus | 'ALL'
}

export interface AdminQuestionSummary {
  questionId: number
  questionVersionId: number
  versionNo: number
  qualificationId: number
  subjectId: number
  questionType: AdminQuestionType
  title: string
  yearNo?: number | null
  difficulty?: AdminQuestionDifficulty | null
  status: AdminQuestionState
  rowVersion: number
  authorId?: number | null
  submittedBy?: number | null
  reviewedBy?: number | null
  reviewDecision?: AdminReviewDecision | null
  publishedBy?: number | null
  retiredBy?: number | null
  sourceType?: AdminQuestionSourceType | null
  paperKind?: string | null
  copyrightStatus?: AdminCopyrightStatus | null
  usageRestriction?: string | null
  currentWorking?: boolean
  currentPublished?: boolean
  updatedAt?: string
}

export interface AdminQuestionPage {
  items: AdminQuestionSummary[]
  pageNo: number
  pageSize: number
  total: number
}

export interface AdminQuestionCountByKey {
  key: string
  label?: string | null
  count: number
}

export interface AdminQuestionDashboard {
  totalCount: number
  draftCount: number
  inReviewCount: number
  publishedCount: number
  retiredCount: number
  byQualification: AdminQuestionCountByKey[]
  byQuestionType: AdminQuestionCountByKey[]
  recentImports: AdminQuestionImportBatchSummary[]
  pendingReviews: AdminQuestionSummary[]
}

export interface AdminQuestionStem {
  stemType: 'MAIN_STEM' | 'CASE_MATERIAL' | 'ATTACHMENT' | 'ESSAY_REQUIREMENT'
  content?: string | null
  attachmentUrl?: string | null
  sortOrder: number
  stemKey?: string | null
  resourceId?: number | null
}

export interface AdminQuestionOption {
  optionKey: string
  optionContent: string
  sortOrder: number
}

export interface AdminQuestionAnswerKey {
  answerFormat: AdminAnswerFormat
  answerValue?: string | null
  answerJson?: string | null
}

export interface AdminQuestionSolution {
  solutionType: 'STANDARD' | 'REFERENCE_ANSWER' | 'ANALYSIS' | 'AI_ENHANCED'
  content: string
  sortOrder: number
}

export interface AdminQuestionScoringRule {
  ruleType: AdminGradingMode
  ruleJson: string
  sortOrder: number
}

export interface AdminQuestionScoringPoint {
  pointCode: string
  pointText: string
  score: number
  sortOrder: number
}

export interface AdminQuestionKnowledgeBinding {
  targetType?: string | null
  targetId?: number | null
  knowledgeType: 'CHAPTER' | 'SYLLABUS' | 'TAG' | string
  knowledgeId: number
  knowledgeCode?: string | null
  weight?: number | null
  sortOrder?: number | null
}

export interface AdminQuestionSubQuestion {
  subQuestionNo: number
  subQuestionType: 'SINGLE_CHOICE' | 'MULTI_CHOICE' | 'JUDGE' | 'CALCULATION' | 'SHORT_ANSWER' | 'ESSAY_WRITING'
  content: string
  score: number
  answerFormat: AdminAnswerFormat
  gradingMode: AdminGradingMode
  sortOrder: number
  metadataJson?: string | null
  options: AdminQuestionOption[]
  answerKeys: AdminQuestionAnswerKey[]
  solutions: AdminQuestionSolution[]
  scoringRules: AdminQuestionScoringRule[]
  scoringPoints: AdminQuestionScoringPoint[]
  knowledgeBindings: AdminQuestionKnowledgeBinding[]
}

/** Exact payload consumed by QuestionCreateRequest in V11. */
export interface AdminQuestionPayload {
  qualificationId: number
  subjectId: number
  questionType: AdminQuestionType
  title?: string | null
  yearNo?: number | null
  difficulty?: AdminQuestionDifficulty | null
  sourceType?: AdminQuestionSourceType | null
  sourceRef?: string | null
  stems: AdminQuestionStem[]
  subQuestions: AdminQuestionSubQuestion[]
  knowledgeBindings: AdminQuestionKnowledgeBinding[]
}

export interface AdminQuestionWriteCommand {
  clientMutationId: string
  baseVersionId: number | null
  payload: AdminQuestionPayload
  copyrightStatus?: AdminCopyrightStatus
  usageRestriction?: string
}

export interface AdminQuestionActionResult {
  questionId: number
  questionVersionId: number
  versionNo: number
  status: AdminQuestionState
  rowVersion: number
  replayed: boolean
  changedAt: string
}

export interface AdminQuestionDetail {
  questionId: number
  questionVersionId: number
  versionNo: number
  status: AdminQuestionState
  rowVersion: number
  authorId?: number | null
  submittedBy?: number | null
  reviewedBy?: number | null
  reviewDecision?: AdminReviewDecision | null
  publishedBy?: number | null
  retiredBy?: number | null
  contentHash?: string | null
  sourceFile?: string | null
  sourceSha256?: string | null
  sourceLocator?: string | null
  originalQuestionNo?: number | null
  paperKind?: string | null
  copyrightStatus?: AdminCopyrightStatus | null
  usageRestriction?: string | null
  createdAt?: string
  updatedAt?: string
  payload: AdminQuestionPayload
}

export interface AdminQuestionMutationCommand {
  questionId: number
  questionVersionId: number
  expectedRowVersion: number
  clientMutationId: string
  reason?: string
}

export interface AdminQuestionRejectCommand extends AdminQuestionMutationCommand {
  reason: string
}

export interface AdminQuestionVersionHistory {
  questionId: number
  questionVersionId: number
  versionNo: number
  status: AdminQuestionState
  rowVersion: number
  authorId?: number | null
  submittedBy?: number | null
  reviewedBy?: number | null
  reviewDecision?: AdminReviewDecision | null
  publishedBy?: number | null
  retiredBy?: number | null
  currentWorking: boolean
  currentPublished: boolean
  deleted: boolean
  createdAt: string
  updatedAt: string
}

export interface AdminQuestionReviewRecord {
  reviewId: number
  questionId: number
  questionVersionId: number
  submittedBy: number
  reviewerId: number
  decision: AdminReviewDecision
  reason?: string | null
  createdAt: string
}

export interface AdminQuestionAuditEvent {
  eventId: number
  questionId: number
  questionVersionId?: number | null
  eventSequence: number
  eventType: string
  actorUserId: number
  clientMutationId: string
  eventPayloadJson: string
  createdAt: string
}

export type AdminImportStatus = 'PREFLIGHT_FAILED' | 'READY_TO_CONFIRM' | 'CONFIRMED' | 'PENDING_REVIEW' | 'PUBLISHED' | 'REJECTED' | 'COMPLETED' | 'FAILED' | 'ROLLED_BACK'

export interface AdminQuestionImportItem {
  itemId?: number
  lineNo: number
  externalKey: string
  contentHash?: string | null
  eligible: boolean
  itemStatus?: string | null
  questionId?: number | null
  questionVersionId?: number | null
  errorCode?: string | null
  createdAt?: string
}

export interface AdminQuestionImportBatchSummary {
  batchId: number
  actorUserId: number
  clientMutationId: string
  paperId?: number | null
  inputPath?: string | null
  inputSha256?: string | null
  sourceFileName?: string | null
  sourceFileSha256?: string | null
  schemaVersion: string
  rightsStatus?: string | null
  usageRestriction?: string | null
  status: AdminImportStatus
  totalCount: number
  eligibleCount: number
  importedCount: number
  createdAt: string
  completedAt?: string | null
  rolledBackAt?: string | null
  reviewedBy?: number | null
  reviewedAt?: string | null
  reviewNote?: string | null
  publishedAt?: string | null
}

export interface AdminQuestionImportBatchPage {
  items: AdminQuestionImportBatchSummary[]
  pageNo: number
  pageSize: number
  total: number
}

export interface AdminQuestionImportBatchDetail {
  batch: AdminQuestionImportBatchSummary
  items: AdminQuestionImportItem[]
}

export interface AdminQuestionImportUploadResult {
  token: string
  schemaVersion: 'question-import/1.0'
  status: AdminImportStatus
  batchId?: number | null
  paperId?: number | null
  inputSha256?: string | null
  totalCount: number
  eligibleCount: number
  ineligibleCount: number
  importedCount: number
  reusedCount: number
  conflictCount: number
  replayed: boolean
  rightsStatus?: string | null
  usageRestriction?: string | null
  items: AdminQuestionImportItem[]
}

export interface AdminQuestionImportPayload {
  schemaVersion: 'question-import/1.0'
  clientMutationId: string
  file: File
}

export interface AdminQuestionImportConfirmCommand {
  token: string
  baseVersion?: number | null
  clientMutationId: string
}

export interface AdminQuestionImportRollbackCommand {
  batchId: number
  clientMutationId: string
}

export interface AdminQuestionImportPublishCommand {
  batchId: number
  clientMutationId: string
}

export interface AdminQuestionImportRejectCommand {
  batchId: number
  clientMutationId: string
  reason: string
}
