import { platformApiClient, unwrapApiResponse } from '@/api/client'
import type {
  AdminAnswerFormat,
  AdminCopyrightStatus,
  AdminGradingMode,
  AdminImportStatus,
  AdminQuestionActionResult,
  AdminQuestionAnomaly,
  AdminQuestionAuditEvent,
  AdminQuestionDetail,
  AdminQuestionDashboard,
  AdminQuestionDifficulty,
  AdminQuestionImportBatchDetail,
  AdminQuestionImportBatchPage,
  AdminQuestionImportBatchSummary,
  AdminQuestionImportConfirmCommand,
  AdminQuestionImportPublishCommand,
  AdminQuestionImportRejectCommand,
  AdminQuestionImportItem,
  AdminQuestionImportPayload,
  AdminQuestionImportRollbackCommand,
  AdminQuestionImportUploadResult,
  AdminQuestionListQuery,
  AdminQuestionMutationCommand,
  AdminQuestionPage,
  AdminQuestionPayload,
  AdminQuestionRejectCommand,
  AdminQuestionReviewRecord,
  AdminQuestionSourceType,
  AdminQuestionState,
  AdminQuestionSummary,
  AdminQuestionType,
  AdminQuestionVersionHistory,
  AdminQuestionWriteCommand,
} from '../types'
import type { AdminQuestionGateway } from './admin-question.gateway'

type JsonObject = Record<string, unknown>

interface BackendPage<T> {
  records?: T[]
  items?: T[]
  pageNo?: number
  pageSize?: number
  total?: number
}

const QUESTION_TYPES = new Set<AdminQuestionType>(['SINGLE_CHOICE', 'MULTI_CHOICE', 'JUDGE', 'CASE', 'ESSAY'])
const QUESTION_STATES = new Set<AdminQuestionState>(['DRAFT', 'IN_REVIEW', 'PUBLISHED', 'RETIRED'])
const DIFFICULTIES = new Set<AdminQuestionDifficulty>(['EASY', 'MEDIUM', 'HARD'])
const REVIEW_DECISIONS = new Set(['PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'])

function objectOf(value: unknown, label: string): JsonObject {
  if (typeof value === 'object' && value !== null && !Array.isArray(value)) return value as JsonObject
  throw new Error(`${label}响应格式错误`)
}

function numberOf(value: unknown, label: string, fallback?: number): number {
  if ((value === undefined || value === null) && fallback !== undefined) return fallback
  const parsed = typeof value === 'number' ? value : Number(value)
  if (Number.isFinite(parsed)) return parsed
  throw new Error(`${label}响应格式错误`)
}

function optionalNumber(value: unknown): number | null {
  if (value === undefined || value === null || value === '') return null
  const parsed = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

function stringOf(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback
}

function enumOf<T extends string>(value: unknown, allowed: ReadonlySet<T>, fallback: T): T {
  return typeof value === 'string' && allowed.has(value as T) ? value as T : fallback
}

function normalizeSummary(value: unknown): AdminQuestionSummary {
  const item = objectOf(value, '题目列表')
  const versionNo = numberOf(item.versionNo, '题目版本', 1)
  return {
    questionId: numberOf(item.questionId ?? item.id, '题目 ID'),
    questionVersionId: numberOf(item.questionVersionId ?? item.versionId, '题目版本 ID', versionNo),
    versionNo,
    qualificationId: numberOf(item.qualificationId, '资格 ID'),
    subjectId: numberOf(item.subjectId, '科目 ID'),
    questionType: enumOf(item.questionType, QUESTION_TYPES, 'SINGLE_CHOICE'),
    title: stringOf(item.title, '未命名题目'),
    yearNo: optionalNumber(item.yearNo),
    difficulty: item.difficulty == null ? null : enumOf(item.difficulty, DIFFICULTIES, 'MEDIUM'),
    status: enumOf(item.status, QUESTION_STATES, 'DRAFT'),
    rowVersion: numberOf(item.rowVersion, '行版本', 0),
    authorId: optionalNumber(item.authorId),
    submittedBy: optionalNumber(item.submittedBy),
    reviewedBy: optionalNumber(item.reviewedBy),
    reviewDecision: typeof item.reviewDecision === 'string' && REVIEW_DECISIONS.has(item.reviewDecision)
      ? item.reviewDecision as AdminQuestionSummary['reviewDecision']
      : null,
    publishedBy: optionalNumber(item.publishedBy),
    retiredBy: optionalNumber(item.retiredBy),
    sourceType: (typeof item.sourceType === 'string' ? item.sourceType : null) as AdminQuestionSourceType | null,
    paperKind: typeof item.paperKind === 'string' ? item.paperKind : null,
    copyrightStatus: (typeof item.copyrightStatus === 'string' ? item.copyrightStatus : null) as AdminCopyrightStatus | null,
    usageRestriction: typeof item.usageRestriction === 'string' ? item.usageRestriction : null,
    currentWorking: typeof item.currentWorking === 'boolean' ? item.currentWorking : undefined,
    currentPublished: typeof item.currentPublished === 'boolean' ? item.currentPublished : undefined,
    updatedAt: typeof item.updatedAt === 'string' ? item.updatedAt : undefined,
  }
}

function normalizePayload(value: unknown): AdminQuestionPayload {
  const payload = objectOf(value, '题目内容')
  const stems = Array.isArray(payload.stems) ? payload.stems : []
  const subQuestions = Array.isArray(payload.subQuestions) ? payload.subQuestions : []
  return {
    qualificationId: numberOf(payload.qualificationId, '资格 ID'),
    subjectId: numberOf(payload.subjectId, '科目 ID'),
    questionType: enumOf(payload.questionType, QUESTION_TYPES, 'SINGLE_CHOICE'),
    title: typeof payload.title === 'string' ? payload.title : null,
    yearNo: optionalNumber(payload.yearNo),
    difficulty: payload.difficulty == null ? null : enumOf(payload.difficulty, DIFFICULTIES, 'MEDIUM'),
    sourceType: (typeof payload.sourceType === 'string' ? payload.sourceType : 'MANUAL') as AdminQuestionSourceType,
    sourceRef: typeof payload.sourceRef === 'string' ? payload.sourceRef : null,
    stems: stems.map((raw, index) => {
      const stem = objectOf(raw, '题干')
      return {
        stemType: stringOf(stem.stemType, 'MAIN_STEM') as AdminQuestionPayload['stems'][number]['stemType'],
        content: typeof stem.content === 'string' ? stem.content : null,
        attachmentUrl: typeof stem.attachmentUrl === 'string' ? stem.attachmentUrl : null,
        sortOrder: numberOf(stem.sortOrder, '题干顺序', index + 1),
        stemKey: typeof stem.stemKey === 'string' ? stem.stemKey : null,
        resourceId: optionalNumber(stem.resourceId),
      }
    }),
    subQuestions: subQuestions.map((raw, index) => {
      const sub = objectOf(raw, '子题')
      const options = Array.isArray(sub.options) ? sub.options : []
      const answers = Array.isArray(sub.answerKeys) ? sub.answerKeys : []
      const solutions = Array.isArray(sub.solutions) ? sub.solutions : []
      const rules = Array.isArray(sub.scoringRules) ? sub.scoringRules : []
      const points = Array.isArray(sub.scoringPoints) ? sub.scoringPoints : []
      const bindings = Array.isArray(sub.knowledgeBindings) ? sub.knowledgeBindings : []
      return {
        subQuestionNo: numberOf(sub.subQuestionNo, '子题序号', index + 1),
        subQuestionType: stringOf(sub.subQuestionType, 'SHORT_ANSWER') as AdminQuestionPayload['subQuestions'][number]['subQuestionType'],
        content: stringOf(sub.content),
        score: numberOf(sub.score, '子题分值', 1),
        answerFormat: stringOf(sub.answerFormat, 'TEXT') as AdminAnswerFormat,
        gradingMode: stringOf(sub.gradingMode, 'MANUAL') as AdminGradingMode,
        sortOrder: numberOf(sub.sortOrder, '子题顺序', index + 1),
        metadataJson: typeof sub.metadataJson === 'string' ? sub.metadataJson : null,
        options: options.map((rawOption, optionIndex) => {
          const option = objectOf(rawOption, '选项')
          return {
            optionKey: stringOf(option.optionKey, String.fromCharCode(65 + optionIndex)),
            optionContent: stringOf(option.optionContent),
            sortOrder: numberOf(option.sortOrder, '选项顺序', optionIndex + 1),
          }
        }),
        answerKeys: answers.map((rawAnswer) => {
          const answer = objectOf(rawAnswer, '答案')
          return {
            answerFormat: stringOf(answer.answerFormat, 'TEXT') as AdminAnswerFormat,
            answerValue: typeof answer.answerValue === 'string' ? answer.answerValue : null,
            answerJson: typeof answer.answerJson === 'string' ? answer.answerJson : null,
          }
        }),
        solutions: solutions.map((rawSolution, solutionIndex) => {
          const solution = objectOf(rawSolution, '解析')
          return {
            solutionType: stringOf(solution.solutionType, 'ANALYSIS') as AdminQuestionPayload['subQuestions'][number]['solutions'][number]['solutionType'],
            content: stringOf(solution.content),
            sortOrder: numberOf(solution.sortOrder, '解析顺序', solutionIndex + 1),
          }
        }),
        scoringRules: rules.map((rawRule, ruleIndex) => {
          const rule = objectOf(rawRule, '评分规则')
          return {
            ruleType: stringOf(rule.ruleType, 'MANUAL') as AdminGradingMode,
            ruleJson: stringOf(rule.ruleJson, '{"mode":"MANUAL"}'),
            sortOrder: numberOf(rule.sortOrder, '评分规则顺序', ruleIndex + 1),
          }
        }),
        scoringPoints: points.map((rawPoint, pointIndex) => {
          const point = objectOf(rawPoint, '评分点')
          return {
            pointCode: stringOf(point.pointCode, `P${pointIndex + 1}`),
            pointText: stringOf(point.pointText),
            score: numberOf(point.score, '评分点分值', 1),
            sortOrder: numberOf(point.sortOrder, '评分点顺序', pointIndex + 1),
          }
        }),
        knowledgeBindings: normalizeBindings(bindings),
      }
    }),
    knowledgeBindings: normalizeBindings(Array.isArray(payload.knowledgeBindings) ? payload.knowledgeBindings : []),
  }
}

function normalizeBindings(values: unknown[]) {
  return values.map((raw, index) => {
    const binding = objectOf(raw, '知识点')
    return {
      targetType: typeof binding.targetType === 'string' ? binding.targetType : null,
      targetId: optionalNumber(binding.targetId),
      knowledgeType: stringOf(binding.knowledgeType, 'SYLLABUS'),
      knowledgeId: numberOf(binding.knowledgeId, '知识点 ID'),
      knowledgeCode: typeof binding.knowledgeCode === 'string' ? binding.knowledgeCode : null,
      weight: optionalNumber(binding.weight),
      sortOrder: numberOf(binding.sortOrder, '知识点顺序', index + 1),
    }
  })
}

function normalizeAction(value: unknown): AdminQuestionActionResult {
  const result = objectOf(value, '题目操作')
  return {
    questionId: numberOf(result.questionId, '题目 ID'),
    questionVersionId: numberOf(result.questionVersionId, '题目版本 ID'),
    versionNo: numberOf(result.versionNo, '题目版本号'),
    status: enumOf(result.status, QUESTION_STATES, 'DRAFT'),
    rowVersion: numberOf(result.rowVersion, '行版本', 0),
    replayed: result.replayed === true,
    changedAt: stringOf(result.changedAt),
  }
}

function normalizeImportItem(value: unknown): AdminQuestionImportItem {
  const item = objectOf(value, '导入明细')
  return {
    itemId: optionalNumber(item.itemId) ?? undefined,
    lineNo: numberOf(item.lineNo ?? item.itemNo, '导入行号', 0),
    externalKey: stringOf(item.externalKey ?? item.sourceLocator, `ROW-${String(item.lineNo ?? item.itemNo ?? 0)}`),
    contentHash: typeof item.contentHash === 'string' ? item.contentHash : null,
    eligible: typeof item.eligible === 'boolean' ? item.eligible : item.status !== 'ERROR',
    itemStatus: typeof item.itemStatus === 'string' ? item.itemStatus : stringOf(item.status) || null,
    questionId: optionalNumber(item.questionId),
    questionVersionId: optionalNumber(item.questionVersionId),
    errorCode: typeof item.errorCode === 'string'
      ? item.errorCode
      : Array.isArray(item.messages) && item.messages.length ? String(item.messages[0]) : null,
    createdAt: typeof item.createdAt === 'string' ? item.createdAt : undefined,
  }
}

function normalizeUploadResult(value: unknown): AdminQuestionImportUploadResult {
  const result = objectOf(value, '导入预检')
  const eligibleCount = numberOf(result.eligibleCount ?? result.validCount, '可导入数', 0)
  const ineligibleCount = numberOf(result.ineligibleCount ?? result.errorCount, '失败数', 0)
  return {
    token: stringOf(result.token ?? result.batchId),
    schemaVersion: 'question-import/1.0',
    status: stringOf(result.status, ineligibleCount > 0 ? 'PREFLIGHT_FAILED' : 'READY_TO_CONFIRM') as AdminImportStatus,
    batchId: optionalNumber(result.batchId),
    paperId: optionalNumber(result.paperId),
    inputSha256: typeof result.inputSha256 === 'string' ? result.inputSha256 : null,
    totalCount: numberOf(result.totalCount, '导入总数', eligibleCount + ineligibleCount),
    eligibleCount,
    ineligibleCount,
    importedCount: numberOf(result.importedCount, '已导入数', 0),
    reusedCount: numberOf(result.reusedCount, '复用题目数', 0),
    conflictCount: numberOf(result.conflictCount, '冲突题目数', 0),
    replayed: result.replayed === true,
    rightsStatus: typeof result.rightsStatus === 'string' ? result.rightsStatus : null,
    usageRestriction: typeof result.usageRestriction === 'string' ? result.usageRestriction : null,
    items: (Array.isArray(result.items) ? result.items : []).map(normalizeImportItem),
  }
}

function normalizeBatchSummary(value: unknown): AdminQuestionImportBatchSummary {
  const batch = objectOf(value, '导入批次')
  return {
    batchId: numberOf(batch.batchId, '批次 ID'),
    actorUserId: numberOf(batch.actorUserId, '操作人 ID', 0),
    clientMutationId: stringOf(batch.clientMutationId),
    paperId: optionalNumber(batch.paperId),
    inputPath: typeof batch.inputPath === 'string' ? batch.inputPath : null,
    inputSha256: typeof batch.inputSha256 === 'string' ? batch.inputSha256 : null,
    sourceFileName: typeof batch.sourceFileName === 'string' ? batch.sourceFileName : null,
    sourceFileSha256: typeof batch.sourceFileSha256 === 'string' ? batch.sourceFileSha256 : null,
    schemaVersion: stringOf(batch.schemaVersion, 'question-import/1.0'),
    rightsStatus: typeof batch.rightsStatus === 'string' ? batch.rightsStatus : null,
    usageRestriction: typeof batch.usageRestriction === 'string' ? batch.usageRestriction : null,
    status: stringOf(batch.status, 'FAILED') as AdminImportStatus,
    totalCount: numberOf(batch.totalCount, '导入总数', 0),
    eligibleCount: numberOf(batch.eligibleCount, '可导入数', 0),
    importedCount: numberOf(batch.importedCount, '已导入数', 0),
    createdAt: stringOf(batch.createdAt),
    completedAt: typeof batch.completedAt === 'string' ? batch.completedAt : null,
    rolledBackAt: typeof batch.rolledBackAt === 'string' ? batch.rolledBackAt : null,
    reviewedBy: optionalNumber(batch.reviewedBy),
    reviewedAt: typeof batch.reviewedAt === 'string' ? batch.reviewedAt : null,
    reviewNote: typeof batch.reviewNote === 'string' ? batch.reviewNote : null,
    publishedAt: typeof batch.publishedAt === 'string' ? batch.publishedAt : null,
  }
}

export class HttpAdminQuestionGateway implements AdminQuestionGateway {
  async getDashboard(): Promise<AdminQuestionDashboard> {
    const value = objectOf(await unwrapApiResponse(platformApiClient.get('/admin/questions/dashboard')), '题库工作台')
    const counts = (raw: unknown) => (Array.isArray(raw) ? raw : []).map((item) => {
      const count = objectOf(item, '题库统计')
      return {
        key: count.key != null || count.qualificationId != null || count.questionType != null
          ? String(count.key ?? count.qualificationId ?? count.questionType)
          : '',
        label: typeof count.label === 'string' ? count.label : null,
        count: numberOf(count.count, '题库统计数量', 0),
      }
    })
    return {
      totalCount: numberOf(value.totalCount, '题目总数', 0),
      draftCount: numberOf(value.draftCount, '草稿数', 0),
      inReviewCount: numberOf(value.inReviewCount, '待审核数', 0),
      publishedCount: numberOf(value.publishedCount, '已发布数', 0),
      retiredCount: numberOf(value.retiredCount, '已退役数', 0),
      byQualification: counts(value.byQualification),
      byQuestionType: counts(value.byQuestionType),
      recentImports: (Array.isArray(value.recentImports) ? value.recentImports : []).map(normalizeBatchSummary),
      pendingReviews: (Array.isArray(value.pendingReviews) ? value.pendingReviews : []).map(normalizeSummary),
    }
  }

  async listQuestions(query: AdminQuestionListQuery): Promise<AdminQuestionPage> {
    const params = {
      pageNo: query.pageNo,
      pageSize: query.pageSize,
      questionId: query.questionId || undefined,
      keyword: query.keyword?.trim() || undefined,
      qualificationId: query.qualificationId || undefined,
      subjectId: query.subjectId || undefined,
      questionType: query.questionType && query.questionType !== 'ALL' ? query.questionType : undefined,
      sourceType: query.sourceType && query.sourceType !== 'ALL' ? query.sourceType : undefined,
      status: query.status && query.status !== 'ALL' ? query.status : undefined,
      copyrightStatus: query.copyrightStatus && query.copyrightStatus !== 'ALL' ? query.copyrightStatus : undefined,
    }
    const page = await unwrapApiResponse<BackendPage<unknown>>(platformApiClient.get('/admin/questions', { params }))
    const items = (page.items ?? page.records ?? []).map(normalizeSummary)
    return {
      items,
      pageNo: numberOf(page.pageNo, '分页页码', query.pageNo),
      pageSize: numberOf(page.pageSize, '分页大小', query.pageSize),
      total: numberOf(page.total, '分页总数', items.length),
    }
  }

  async getQuestion(questionId: number, questionVersionId: number): Promise<AdminQuestionDetail> {
    const raw = objectOf(await unwrapApiResponse(platformApiClient.get(`/admin/questions/${questionId}/versions/${questionVersionId}`)), '题目详情')
    return {
      questionId: numberOf(raw.questionId, '题目 ID'),
      questionVersionId: numberOf(raw.questionVersionId, '题目版本 ID'),
      versionNo: numberOf(raw.versionNo, '题目版本号'),
      status: enumOf(raw.status, QUESTION_STATES, 'DRAFT'),
      rowVersion: numberOf(raw.rowVersion, '行版本', 0),
      authorId: optionalNumber(raw.authorId),
      submittedBy: optionalNumber(raw.submittedBy),
      reviewedBy: optionalNumber(raw.reviewedBy),
      reviewDecision: typeof raw.reviewDecision === 'string' && REVIEW_DECISIONS.has(raw.reviewDecision)
        ? raw.reviewDecision as AdminQuestionDetail['reviewDecision']
        : null,
      publishedBy: optionalNumber(raw.publishedBy),
      retiredBy: optionalNumber(raw.retiredBy),
      contentHash: typeof raw.contentHash === 'string' ? raw.contentHash : null,
      sourceFile: typeof raw.sourceFile === 'string' ? raw.sourceFile : null,
      sourceSha256: typeof raw.sourceSha256 === 'string' ? raw.sourceSha256 : null,
      sourceLocator: typeof raw.sourceLocator === 'string' ? raw.sourceLocator : null,
      originalQuestionNo: optionalNumber(raw.originalQuestionNo),
      paperKind: typeof raw.paperKind === 'string' ? raw.paperKind : null,
      copyrightStatus: (typeof raw.copyrightStatus === 'string' ? raw.copyrightStatus : null) as AdminCopyrightStatus | null,
      usageRestriction: typeof raw.usageRestriction === 'string' ? raw.usageRestriction : null,
      createdAt: typeof raw.createdAt === 'string' ? raw.createdAt : undefined,
      updatedAt: typeof raw.updatedAt === 'string' ? raw.updatedAt : undefined,
      payload: normalizePayload(raw.payload),
    }
  }

  async createQuestion(command: AdminQuestionWriteCommand): Promise<AdminQuestionActionResult> {
    return normalizeAction(await unwrapApiResponse(platformApiClient.post('/admin/questions', command)))
  }

  async createRevision(questionId: number, command: AdminQuestionWriteCommand): Promise<AdminQuestionActionResult> {
    return normalizeAction(await unwrapApiResponse(platformApiClient.put(`/admin/questions/${questionId}/versions`, command)))
  }

  submitReview(command: AdminQuestionMutationCommand): Promise<AdminQuestionActionResult> {
    return this.workflow(command, 'submit-review')
  }

  async rejectReview(command: AdminQuestionRejectCommand): Promise<AdminQuestionActionResult> {
    return normalizeAction(await unwrapApiResponse(platformApiClient.post(
      `/admin/questions/${command.questionId}/versions/${command.questionVersionId}/review`,
      this.workflowBody(command, { decision: 'REJECTED', reason: command.reason }),
    )))
  }

  async approveReview(command: AdminQuestionMutationCommand): Promise<AdminQuestionActionResult> {
    return normalizeAction(await unwrapApiResponse(platformApiClient.post(
      `/admin/questions/${command.questionId}/versions/${command.questionVersionId}/review`,
      this.workflowBody(command, { decision: 'APPROVED', reason: command.reason }),
    )))
  }

  publishQuestion(command: AdminQuestionMutationCommand): Promise<AdminQuestionActionResult> {
    return this.workflow(command, 'publish')
  }

  retireQuestion(command: AdminQuestionMutationCommand): Promise<AdminQuestionActionResult> {
    return this.workflow(command, 'retire')
  }

  async copyVersion(command: AdminQuestionMutationCommand): Promise<AdminQuestionActionResult> {
    return normalizeAction(await unwrapApiResponse(platformApiClient.post(
      `/admin/questions/${command.questionId}/versions/${command.questionVersionId}/copy`,
      { clientMutationId: command.clientMutationId },
    )))
  }

  async listVersions(questionId: number): Promise<AdminQuestionVersionHistory[]> {
    const values = await unwrapApiResponse<unknown[]>(platformApiClient.get(`/admin/questions/${questionId}/versions`))
    return values.map((value) => {
      const row = objectOf(value, '版本历史')
      return {
        questionId: numberOf(row.questionId, '题目 ID'),
        questionVersionId: numberOf(row.questionVersionId, '题目版本 ID'),
        versionNo: numberOf(row.versionNo, '版本号'),
        status: enumOf(row.status, QUESTION_STATES, 'DRAFT'),
        rowVersion: numberOf(row.rowVersion, '行版本', 0),
        authorId: optionalNumber(row.authorId),
        submittedBy: optionalNumber(row.submittedBy),
        reviewedBy: optionalNumber(row.reviewedBy),
        reviewDecision: typeof row.reviewDecision === 'string' ? row.reviewDecision as AdminQuestionVersionHistory['reviewDecision'] : null,
        publishedBy: optionalNumber(row.publishedBy),
        retiredBy: optionalNumber(row.retiredBy),
        currentWorking: row.currentWorking === true,
        currentPublished: row.currentPublished === true,
        deleted: row.deleted === true,
        createdAt: stringOf(row.createdAt),
        updatedAt: stringOf(row.updatedAt),
      }
    })
  }

  async listReviews(questionId: number, questionVersionId: number): Promise<AdminQuestionReviewRecord[]> {
    return unwrapApiResponse<AdminQuestionReviewRecord[]>(platformApiClient.get(
      `/admin/questions/${questionId}/versions/${questionVersionId}/reviews`,
    ))
  }

  async listAuditEvents(questionId: number): Promise<AdminQuestionAuditEvent[]> {
    return unwrapApiResponse<AdminQuestionAuditEvent[]>(platformApiClient.get(`/admin/questions/${questionId}/audit-events`))
  }

  async preflightImport(payload: AdminQuestionImportPayload): Promise<AdminQuestionImportUploadResult> {
    const formData = new FormData()
    formData.append('schemaVersion', payload.schemaVersion)
    formData.append('clientMutationId', payload.clientMutationId)
    formData.append('file', payload.file)
    return normalizeUploadResult(await unwrapApiResponse(platformApiClient.post('/admin/question-imports/preflight', formData)))
  }

  async confirmImport(command: AdminQuestionImportConfirmCommand): Promise<AdminQuestionImportUploadResult> {
    return normalizeUploadResult(await unwrapApiResponse(platformApiClient.post(
      `/admin/question-imports/${encodeURIComponent(command.token)}/confirm`,
      { clientMutationId: command.clientMutationId, baseVersion: command.baseVersion ?? null },
    )))
  }

  async listImportBatches(query: { pageNo: number, pageSize: number, status?: AdminImportStatus | 'ALL' }): Promise<AdminQuestionImportBatchPage> {
    const page = await unwrapApiResponse<BackendPage<unknown>>(platformApiClient.get('/admin/question-imports', {
      params: { pageNo: query.pageNo, pageSize: query.pageSize, status: query.status === 'ALL' ? undefined : query.status },
    }))
    const items = (page.items ?? page.records ?? []).map(normalizeBatchSummary)
    return {
      items,
      pageNo: numberOf(page.pageNo, '分页页码', query.pageNo),
      pageSize: numberOf(page.pageSize, '分页大小', query.pageSize),
      total: numberOf(page.total, '分页总数', items.length),
    }
  }

  async getImportBatch(batchId: number): Promise<AdminQuestionImportBatchDetail> {
    const value = objectOf(await unwrapApiResponse(platformApiClient.get(`/admin/question-imports/${batchId}`)), '导入批次')
    return {
      batch: normalizeBatchSummary(value.batch),
      items: (Array.isArray(value.items) ? value.items : []).map(normalizeImportItem),
    }
  }

  async rollbackImport(command: AdminQuestionImportRollbackCommand): Promise<AdminQuestionImportBatchDetail> {
    await unwrapApiResponse(platformApiClient.post(`/admin/question-imports/${command.batchId}/rollback`, {
      clientMutationId: command.clientMutationId,
    }))
    return this.getImportBatch(command.batchId)
  }

  async publishImport(command: AdminQuestionImportPublishCommand): Promise<AdminQuestionImportBatchDetail> {
    await unwrapApiResponse(platformApiClient.post(`/admin/question-imports/${command.batchId}/publish`, {
      clientMutationId: command.clientMutationId,
    }))
    return this.getImportBatch(command.batchId)
  }

  async rejectImport(command: AdminQuestionImportRejectCommand): Promise<AdminQuestionImportBatchDetail> {
    await unwrapApiResponse(platformApiClient.post(`/admin/question-imports/${command.batchId}/reject`, {
      clientMutationId: command.clientMutationId,
      reason: command.reason,
    }))
    return this.getImportBatch(command.batchId)
  }

  async downloadImportErrors(batchId: number): Promise<Blob> {
    const response = await platformApiClient.get<Blob>(`/admin/question-imports/${batchId}/errors`, { responseType: 'blob' })
    return response.data
  }

  async listAnomalies(query: { anomalyType?: string, status?: string } = {}): Promise<AdminQuestionAnomaly[]> {
    return unwrapApiResponse<AdminQuestionAnomaly[]>(platformApiClient.get('/admin/questions/anomalies', { params: query }))
  }

  async resolveAnomaly(anomalyId: number, status: string, note?: string): Promise<void> {
    await unwrapApiResponse(platformApiClient.post(`/admin/questions/anomalies/${anomalyId}/resolve`, null, { params: { status, note } }))
  }

  private async workflow(command: AdminQuestionMutationCommand, action: 'submit-review' | 'publish' | 'retire'): Promise<AdminQuestionActionResult> {
    return normalizeAction(await unwrapApiResponse(platformApiClient.post(
      `/admin/questions/${command.questionId}/versions/${command.questionVersionId}/${action}`,
      this.workflowBody(command, { reason: command.reason }),
    )))
  }

  private workflowBody<T extends Record<string, unknown>>(
    command: AdminQuestionMutationCommand,
    extra: T,
  ): { clientMutationId: string, expectedRowVersion: number } & T {
    return {
      clientMutationId: command.clientMutationId,
      expectedRowVersion: command.expectedRowVersion,
      ...extra,
    }
  }
}
