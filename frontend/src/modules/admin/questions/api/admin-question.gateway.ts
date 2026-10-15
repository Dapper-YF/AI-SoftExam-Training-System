import type {
  AdminQuestionActionResult,
  AdminQuestionAnomaly,
  AdminQuestionAuditEvent,
  AdminQuestionDetail,
  AdminQuestionDashboard,
  AdminQuestionImportBatchDetail,
  AdminQuestionImportBatchPage,
  AdminQuestionImportConfirmCommand,
  AdminQuestionImportPublishCommand,
  AdminQuestionImportRejectCommand,
  AdminQuestionImportPayload,
  AdminQuestionImportRollbackCommand,
  AdminQuestionImportUploadResult,
  AdminQuestionListQuery,
  AdminQuestionMutationCommand,
  AdminQuestionPage,
  AdminQuestionRejectCommand,
  AdminQuestionReviewRecord,
  AdminQuestionVersionHistory,
  AdminQuestionWriteCommand,
  AdminImportStatus,
} from '../types'

export interface AdminQuestionGateway {
  getDashboard(): Promise<AdminQuestionDashboard>
  listQuestions(query: AdminQuestionListQuery): Promise<AdminQuestionPage>
  getQuestion(questionId: number, questionVersionId: number): Promise<AdminQuestionDetail>
  createQuestion(command: AdminQuestionWriteCommand): Promise<AdminQuestionActionResult>
  createRevision(questionId: number, command: AdminQuestionWriteCommand): Promise<AdminQuestionActionResult>
  submitReview(command: AdminQuestionMutationCommand): Promise<AdminQuestionActionResult>
  rejectReview(command: AdminQuestionRejectCommand): Promise<AdminQuestionActionResult>
  approveReview(command: AdminQuestionMutationCommand): Promise<AdminQuestionActionResult>
  publishQuestion(command: AdminQuestionMutationCommand): Promise<AdminQuestionActionResult>
  retireQuestion(command: AdminQuestionMutationCommand): Promise<AdminQuestionActionResult>
  copyVersion(command: AdminQuestionMutationCommand): Promise<AdminQuestionActionResult>
  listVersions(questionId: number): Promise<AdminQuestionVersionHistory[]>
  listReviews(questionId: number, questionVersionId: number): Promise<AdminQuestionReviewRecord[]>
  listAuditEvents(questionId: number): Promise<AdminQuestionAuditEvent[]>
  preflightImport(payload: AdminQuestionImportPayload): Promise<AdminQuestionImportUploadResult>
  confirmImport(command: AdminQuestionImportConfirmCommand): Promise<AdminQuestionImportUploadResult>
  listImportBatches(query: { pageNo: number, pageSize: number, status?: AdminImportStatus | 'ALL' }): Promise<AdminQuestionImportBatchPage>
  getImportBatch(batchId: number): Promise<AdminQuestionImportBatchDetail>
  rollbackImport(command: AdminQuestionImportRollbackCommand): Promise<AdminQuestionImportBatchDetail>
  publishImport(command: AdminQuestionImportPublishCommand): Promise<AdminQuestionImportBatchDetail>
  rejectImport(command: AdminQuestionImportRejectCommand): Promise<AdminQuestionImportBatchDetail>
  downloadImportErrors(batchId: number): Promise<Blob>
  listAnomalies?(query?: { anomalyType?: string, status?: string }): Promise<AdminQuestionAnomaly[]>
  resolveAnomaly?(anomalyId: number, status: string, note?: string): Promise<void>
}
