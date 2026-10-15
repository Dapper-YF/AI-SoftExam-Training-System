import { isExplicitMockEnabled } from '@/modules/foundation/api/mock-mode'
import { distinctQuestionTexts } from '../question-content'
import type {
  PagedResult,
  PracticeAnswerPayload,
  PracticeAnswerValue,
  PracticeAttempt,
  PracticeCatalog,
  PracticeAttemptStatus,
  PracticeCreateAttemptInput,
  PracticeHistoryQuery,
  PracticeHistoryRecord,
  PracticeListQuery,
  PracticeMistakeQuery,
  PracticeMistakeRecord,
  PracticeFavoriteRecord,
  PracticeMutationResult,
  PracticeOption,
  PracticePaperSummary,
  PracticeQuestion,
  PracticeQuestionExplanation,
  PracticeExplanationStatus,
  PracticeQuestionType,
  PracticeScoringPoint,
  PracticeSaveAnswerResult,
  PracticeSubQuestion,
} from '../types'

export type PracticeGatewayKind = 'http' | 'mock'

const ATTEMPT_STATUSES = new Set<PracticeAttemptStatus>([
  'IN_PROGRESS', 'SUBMITTED', 'PENDING_REVIEW',
])
const QUESTION_TYPES = new Set<PracticeQuestionType>([
  'SINGLE_CHOICE', 'MULTI_CHOICE', 'JUDGE', 'CASE', 'ESSAY',
  'SHORT_ANSWER', 'ESSAY_WRITING', 'CALCULATION', 'CODE_GAP', 'FILL_BLANK', 'FLOWCHART', 'UML_GRAPH',
  'REQUIREMENT_SPEC', 'ARCHITECTURE_DESIGN', 'SERVICE_DESIGN', 'METRIC_CONFIGURATION',
])
const EXPLANATION_STATUSES = new Set<PracticeExplanationStatus>(['READY', 'IMPROVING'])
const ANSWER_FORMATS = new Set(['OPTION', 'BOOLEAN', 'TEXT', 'NUMBER', 'JSON'] as const)
const QUESTION_TYPE_LABELS: Record<PracticeQuestionType, string> = {
  SINGLE_CHOICE: '单选题',
  MULTI_CHOICE: '多选题',
  JUDGE: '判断题',
  CASE: '案例分析',
  ESSAY: '论文题',
  SHORT_ANSWER: '简答题',
  ESSAY_WRITING: '论文写作',
  CALCULATION: '计算题',
  CODE_GAP: '代码补全',
  FILL_BLANK: '填空题',
  FLOWCHART: '流程图题',
  UML_GRAPH: 'UML 图题',
  REQUIREMENT_SPEC: '需求规格题',
  ARCHITECTURE_DESIGN: '架构方案题',
  SERVICE_DESIGN: '服务方案题',
  METRIC_CONFIGURATION: '服务指标题',
}

export function resolvePracticeGatewayKind(
  platformMock: string | undefined,
  practiceMock: string | undefined,
): PracticeGatewayKind {
  return isExplicitMockEnabled(platformMock) || isExplicitMockEnabled(practiceMock) ? 'mock' : 'http'
}

export function buildListParams(query: PracticeListQuery): Record<string, unknown> {
  const params: Record<string, unknown> = {
    qualificationId: query.qualificationId,
    subjectId: query.subjectId,
    pageNo: query.page,
    pageSize: query.pageSize,
    paperType: query.mode === 'PAST_EXAM' ? 'REAL' : undefined,
  }
  if (query.year && query.year !== 'ALL') {
    const yearNo = Number.parseInt(query.year, 10)
    if (Number.isInteger(yearNo) && yearNo > 0) params.yearNo = yearNo
  }
  return compactParams(params)
}

export function buildHistoryParams(
  queryOrPage: PracticeHistoryQuery | number,
  legacyPageSize?: number,
): Record<string, unknown> {
  const query: PracticeHistoryQuery = typeof queryOrPage === 'number'
    ? { page: queryOrPage, pageSize: legacyPageSize ?? 20 }
    : queryOrPage
  return compactParams({
    pageNo: query.page,
    pageSize: query.pageSize,
    qualificationId: query.qualificationId,
    subjectId: query.subjectId,
    status: query.status,
  })
}

export function buildMistakeParams(query: PracticeMistakeQuery): Record<string, unknown> {
  return compactParams({
    pageNo: query.page,
    pageSize: query.pageSize,
    qualificationId: query.qualificationId,
    subjectId: query.subjectId,
    status: query.status,
  })
}

export function buildCreateAttemptBody(input: PracticeCreateAttemptInput): Record<string, unknown> {
  const source = input.source.type === 'KNOWLEDGE'
    ? {
        type: input.source.type,
        knowledgeType: input.source.knowledgeType,
        knowledgeIds: input.source.knowledgeIds.map(positiveIdNumber),
      }
    : input.source.type === 'YEAR'
      ? { type: input.source.type, yearNo: input.source.yearNo }
      : input.source.type === 'PAPER'
        ? { type: input.source.type, paperId: positiveIdNumber(input.source.paperId) }
        : { type: input.source.type }
  return compactParams({
    clientMutationId: input.clientMutationId ?? crypto.randomUUID(),
    qualificationId: positiveIdNumber(input.qualificationId),
    subjectId: input.subjectId ? positiveIdNumber(input.subjectId) : undefined,
    mode: input.mode,
    feedbackMode: input.feedbackMode,
    count: input.count,
    source,
  })
}

export function buildCreateCaseAttemptBody(input: {
  qualificationId: string
  subjectId: string
  questionId: string
  clientMutationId?: string
}): Record<string, unknown> {
  return {
    clientMutationId: input.clientMutationId ?? crypto.randomUUID(),
    qualificationId: positiveIdNumber(input.qualificationId),
    subjectId: positiveIdNumber(input.subjectId),
    questionId: positiveIdNumber(input.questionId),
  }
}

export function buildSaveAnswerBody(input: {
  answerPayload: PracticeAnswerPayload
  marked: boolean
  usedSeconds?: number
  baseVersion: number
  clientMutationId: string
}): Record<string, unknown> {
  return {
    clientMutationId: input.clientMutationId,
    baseVersion: input.baseVersion,
    answerPayload: normalizeAnswerPayload(input.answerPayload),
    marked: input.marked,
    usedSeconds: input.usedSeconds ?? 0,
  }
}

export function buildVersionMutationBody(baseVersion: number, clientMutationId?: string): Record<string, unknown> {
  return { clientMutationId: clientMutationId ?? crypto.randomUUID(), baseVersion }
}

export function buildNoteBody(questionId: string, content: string): Record<string, string> {
  return { targetType: 'QUESTION', targetId: questionId, noteContent: content }
}

export function adaptPracticePaperPage(payload: unknown): PagedResult<PracticePaperSummary> {
  const raw = requiredObject(payload, '试卷')
  rejectPublicInventoryKeys(raw, '公开试卷目录')
  const pageNo = requiredPositiveInteger(raw.pageNo ?? raw.page, '试卷 pageNo')
  const pageSize = requiredPositiveInteger(raw.pageSize, '试卷 pageSize')
  const hasNext = requiredBoolean(raw.hasNext, '试卷 hasNext')
  const items = requiredArray(raw.items, '试卷 items').map((value) => {
    const row = requiredObject(value, '试卷记录')
    rejectPublicInventoryKeys(row, '公开试卷记录')
    const yearNo = optionalInteger(row.yearNo, '试卷 yearNo')
    const durationMinutes = optionalInteger(row.durationMinutes, '试卷 durationMinutes')
    return compactObject({
      id: requiredId(row.paperId, '试卷 paperId'),
      title: requiredString(row.paperName, '试卷 paperName'),
      qualificationId: requiredId(row.qualificationId, '试卷 qualificationId'),
      subjectId: row.subjectId == null ? undefined : requiredId(row.subjectId, '试卷 subjectId'),
      paperType: requiredString(row.paperType, '试卷 paperType'),
      yearNo,
      durationMinutes,
      status: requiredString(row.status, '试卷 status'),
    }) as unknown as PracticePaperSummary
  })
  return {
    items,
    total: hasNext ? pageNo * pageSize + 1 : Math.max(0, (pageNo - 1) * pageSize + items.length),
    page: pageNo,
    pageSize,
  }
}

export function adaptPracticeCatalog(payload: unknown): PracticeCatalog {
  const raw = requiredObject(payload, '题库目录')
  const subjects = requiredArray(raw.subjects, '题库 subjects').map((value) => {
    const subject = requiredObject(value, '题库科目')
    const questionCount = optionalNonNegativeInteger(subject.questionCount, '题库科目 questionCount') ?? 0
    return {
      subjectId: requiredId(subject.subjectId, '题库科目 subjectId'),
      subjectName: requiredString(subject.subjectName, '题库科目 subjectName'),
      subjectType: requiredEnum(
        subject.subjectType,
        new Set(['COMPREHENSIVE', 'CASE', 'ESSAY', 'APPLICATION'] as const),
        '题库科目 subjectType',
      ),
      available: typeof subject.available === 'boolean' ? subject.available : questionCount > 0,
      unavailableReason: optionalString(subject.unavailableReason, '题库科目 unavailableReason'),
      questionCount,
      questionTypes: Array.isArray(subject.questionTypes)
        ? subject.questionTypes.map((typeValue) => {
            const type = requiredObject(typeValue, '题库题型')
            return {
              questionType: requiredQuestionType(type.questionType, '题库题型 questionType'),
              count: requiredNonNegativeInteger(type.count, '题库题型 count'),
            }
          })
        : [],
    }
  })
  const questionCount = optionalNonNegativeInteger(raw.questionCount, '题库 questionCount') ?? 0
  const paperCount = optionalNonNegativeInteger(raw.paperCount, '题库 paperCount') ?? 0
  const papers = optionalObject(raw.papers, '题库 papers')
  const available = typeof raw.available === 'boolean'
    ? raw.available
    : subjects.some((subject) => subject.available) || paperCount > 0
  return {
    qualificationId: requiredId(raw.qualificationId, '题库 qualificationId'),
    available,
    unavailableReason: optionalString(raw.unavailableReason, '题库 unavailableReason'),
    questionCount,
    paperCount,
    paperYears: Array.isArray(raw.paperYears)
      ? raw.paperYears.map((year) => requiredPositiveInteger(year, '题库 paperYear'))
      : [],
    subjects,
    papers: papers
      ? {
          available: requiredBoolean(papers.available, '题库 papers available'),
          unavailableReason: optionalString(papers.unavailableReason, '题库 papers unavailableReason'),
        }
      : undefined,
  }
}

export function adaptPracticeAttempt(payload: unknown): PracticeAttempt {
  const raw = requiredObject(payload, '练习记录')
  const status = requiredEnum(raw.status, ATTEMPT_STATUSES, '练习状态')
  const feedbackMode = requiredEnum(raw.feedbackMode, new Set(['PER_QUESTION', 'WHOLE_PAPER'] as const), '反馈模式')
  const terminal = status !== 'IN_PROGRESS'
  const answers: Record<string, PracticeAnswerValue> = {}
  const answerVersions: Record<string, number> = {}
  const markedQuestionIds = new Set<string>()
  const rawQuestions = requiredArray(raw.questions, '练习 questions')
  const questions = rawQuestions.map((value, questionIndex): PracticeQuestion => {
    const question = requiredObject(value, `第 ${questionIndex + 1} 题`)
    const questionId = requiredId(question.questionId, '题目 questionId')
    const questionType = requiredQuestionType(question.questionType, '题目 questionType')
    const settlementStatus = requiredEnum(
      question.settlementStatus,
      new Set(['UNSETTLED', 'SETTLED'] as const),
      '题目 settlementStatus',
    )
    const reveal = terminal || settlementStatus === 'SETTLED'
    const rawSubQuestions = requiredArray(question.subQuestions, '题目 subQuestions')
    if (!rawSubQuestions.length) throw contractError('题目 subQuestions 不能为空')
    const subQuestions = rawSubQuestions.map((subValue, subIndex): PracticeSubQuestion => {
      const sub = requiredObject(subValue, `第 ${questionIndex + 1} 题子题 ${subIndex + 1}`)
      const subQuestionId = requiredId(sub.subQuestionId, '子题 subQuestionId')
      const answer = optionalObject(sub.answer, '子题 answer')
      const grading = optionalObject(sub.grading, '子题 grading')
      if (!reveal && ((grading && Object.keys(grading).length) || sub.solution != null)) {
        throw contractError('未结算题目不得返回标准答案、分数或解析')
      }
      if (!reveal && Array.isArray(sub.scoringPoints) && sub.scoringPoints.length) {
        throw contractError('未结算题目不得返回标准答案、分数或解析')
      }
      if (!reveal && answer?.gradingStatus != null) {
        throw contractError('未结算答案不得返回 gradingStatus')
      }
      if (answer) {
        const answerValue = readAnswerValue(answer.answerPayload, '已保存答案')
        answers[subQuestionId] = answerValue
        answerVersions[subQuestionId] = requiredNonNegativeInteger(answer.answerVersion, '答案 answerVersion')
        if (answer.marked === true) markedQuestionIds.add(questionId)
      }
      const options = requiredArray(sub.options, '子题 options').map(mapOption)
      const mapped: PracticeSubQuestion = {
        subQuestionId,
        type: requiredQuestionType(sub.subQuestionType, '子题类型'),
        answerFormat: requiredAnswerFormat(sub.answerFormat, '子题 answerFormat'),
        content: optionalString(sub.content, '子题 content') ?? '',
        metadata: optionalObject(sub.metadata, '子题 metadata'),
        options,
        answerVersion: answerVersions[subQuestionId] ?? 0,
        marked: answer?.marked === true,
      }
      if (sub.score != null) mapped.score = requiredNumber(sub.score, '子题 score')
      if (reveal && grading) {
        mapped.standardAnswer = readAnswerValue(grading.standardAnswer, '标准答案')
        mapped.score = requiredNumber(grading.score, '子题 score')
        if (grading.earnedScore != null) mapped.earnedScore = requiredNumber(grading.earnedScore, '子题 earnedScore')
      }
      if (reveal && typeof sub.solution === 'string') mapped.solution = sub.solution
      if (reveal && typeof sub.solutionStatus === 'string') {
        mapped.solutionStatus = requiredEnum(sub.solutionStatus, EXPLANATION_STATUSES, 'subQuestion solutionStatus')
      }
      if (reveal && Array.isArray(sub.scoringPoints)) {
        mapped.scoringPoints = sub.scoringPoints.map(mapScoringPoint)
      }
      if (reveal && answer?.gradingStatus != null) {
        mapped.gradingStatus = requiredEnum(
          answer.gradingStatus,
          new Set(['SAVED', 'CORRECT', 'WRONG', 'PENDING_REVIEW'] as const),
          '答案 gradingStatus',
        )
      }
      return mapped
    })
    const stems = distinctQuestionTexts(requiredArray(question.stems, '题目 stems')
      .map((stem) => requiredString(requiredObject(stem, '题干').content, '题干 content'))
    )
    const bindings = requiredArray(question.knowledgeBindings, '题目 knowledgeBindings')
    const firstBinding = bindings.length ? requiredObject(bindings[0], '知识点') : undefined
    const knowledgeName = firstBinding
      ? optionalString(firstBinding.knowledgeName, '知识点 knowledgeName')
      : undefined
    const knowledgePoint = knowledgeName ?? (firstBinding ? '知识点名称待同步' : '未标注知识点')
    const first = subQuestions[0]!
    const explanation = subQuestions.map((sub) => sub.solution).filter(Boolean).join('\n')
    return compactObject({
      questionId,
      subQuestionId: first.subQuestionId,
      number: requiredPositiveInteger(question.questionOrder, '题目 questionOrder'),
      questionVersionId: requiredId(question.questionVersionId, '题目 questionVersionId'),
      typeLabel: QUESTION_TYPE_LABELS[questionType],
      questionType,
      knowledgePoint,
      stem: stems.length
        ? stems.join('\n')
        : distinctQuestionTexts(subQuestions.map((sub) => sub.content)).join('\n'),
      options: first.options,
      settlementStatus,
      settlementVersion: requiredNonNegativeInteger(question.settlementVersion, '题目 settlementVersion'),
      subQuestions,
      correctAnswer: first.standardAnswer,
      explanation: explanation || undefined,
      source: knowledgePoint,
    }) as unknown as PracticeQuestion
  })
  const sourceSpec = optionalObject(raw.sourceSpec, '练习 sourceSpec') ?? {}
  const attempt: PracticeAttempt = {
    id: requiredId(raw.attemptId, '练习 attemptId'),
    paperId: sourceSpec.paperId == null ? '' : requiredId(sourceSpec.paperId, '练习 paperId'),
    title: requiredString(raw.title, '练习 title'),
    mode: typeof raw.mode === 'string' ? raw.mode as PracticeAttempt['mode'] : undefined,
    sourceType: typeof raw.sourceType === 'string' ? raw.sourceType : undefined,
    sourceSpec,
    qualificationName: typeof raw.qualificationName === 'string'
      ? raw.qualificationName
      : requiredId(raw.qualificationId, '练习 qualificationId'),
    subjectName: raw.subjectId == null ? '整卷' : requiredId(raw.subjectId, '练习 subjectId'),
    createdAt: requiredString(raw.startedAt, '练习 startedAt'),
    status,
    version: requiredNonNegativeInteger(raw.version, '练习 version'),
    feedbackMode,
    questions,
    answers,
    answerVersions,
    markedQuestionIds: [...markedQuestionIds],
    favoriteQuestionIds: [],
    notes: {},
  }
  assignOptionalAttemptResult(attempt, raw)
  return attempt
}

export function adaptPracticeMutationResult(payload: unknown): PracticeMutationResult {
  const raw = requiredObject(payload, '练习结算结果')
  const result: PracticeMutationResult = {
    attemptId: requiredId(raw.attemptId, '结算 attemptId'),
    status: requiredEnum(raw.status, ATTEMPT_STATUSES, '结算 status'),
    version: requiredNonNegativeInteger(raw.version, '结算 version'),
    idempotentReplay: requiredBoolean(raw.idempotentReplay, '结算 idempotentReplay'),
  }
  if (raw.questionId != null) result.questionId = requiredId(raw.questionId, '结算 questionId')
  assignOptionalAttemptResult(result, raw)
  return result
}

export function adaptPracticeSaveAnswerResult(payload: unknown): PracticeSaveAnswerResult {
  const raw = requiredObject(payload, '答案保存结果')
  return {
    answerVersion: requiredNonNegativeInteger(raw.answerVersion, '答案 answerVersion'),
    savedAt: requiredString(raw.savedAt, '答案 savedAt'),
  }
}

export function adaptPracticeExplanation(payload: unknown): PracticeQuestionExplanation {
  const raw = requiredObject(payload, '题目解析')
  return {
    attemptId: requiredId(raw.attemptId, '解析 attemptId'),
    questionId: requiredId(raw.questionId, '解析 questionId'),
    questionVersionId: raw.questionVersionId == null ? undefined : requiredId(raw.questionVersionId, '解析 questionVersionId'),
    contentHash: optionalString(raw.contentHash, '解析 contentHash'),
    status: raw.status == null
      ? undefined
      : requiredEnum(raw.status, EXPLANATION_STATUSES, 'explanation status'),
    subQuestions: requiredArray(raw.subQuestions, '解析 subQuestions').map((value) => {
      const sub = requiredObject(value, '子题解析')
      return {
        subQuestionId: requiredId(sub.subQuestionId, '解析 subQuestionId'),
        standardAnswer: readAnswerValue(sub.standardAnswer, '解析 standardAnswer'),
        solution: optionalString(sub.solution, '解析 solution'),
        solutionStatus: sub.solutionStatus == null
          ? undefined
          : requiredEnum(sub.solutionStatus, EXPLANATION_STATUSES, 'explanation solutionStatus'),
        scoringPoints: Array.isArray(sub.scoringPoints) ? sub.scoringPoints.map(mapScoringPoint) : undefined,
      }
    }),
  }
}

export function adaptPracticeHistory(payload: unknown): PagedResult<PracticeHistoryRecord> {
  return adaptPage(payload, '练习历史', (value) => {
    const row = requiredObject(value, '练习历史记录')
    const sourceType = requiredEnum(
      row.sourceType,
      new Set(['KNOWLEDGE', 'YEAR', 'PAPER', 'DAILY'] as const),
      '历史 sourceType',
    )
    const sourceSpec = optionalObject(row.sourceSpec, '历史 sourceSpec') ?? {}
    return compactObject({
      id: requiredId(row.attemptId, '历史 attemptId'),
      status: requiredEnum(row.status, ATTEMPT_STATUSES, '历史 status'),
      sourceType,
      sourceSpec,
      questionCount: requiredNonNegativeInteger(row.questionCount, '历史 questionCount'),
      startedAt: requiredString(row.startedAt, '历史 startedAt'),
      submittedAt: optionalString(row.submittedAt, '历史 submittedAt'),
      durationSeconds: optionalNumber(row.durationSeconds, '历史 durationSeconds'),
      totalScore: optionalNumber(row.totalScore, '历史 totalScore'),
      maxScore: optionalNumber(row.maxScore, '历史 maxScore'),
    }) as unknown as PracticeHistoryRecord
  })
}

export function adaptPracticeMistakes(payload: unknown): PagedResult<PracticeMistakeRecord> {
  return adaptPage(payload, '错题', (value) => {
    const row = requiredObject(value, '错题记录')
    return {
      questionId: requiredId(row.questionId, '错题 questionId'),
      qualificationId: requiredId(row.qualificationId, '错题 qualificationId'),
      subjectId: requiredId(row.subjectId, '错题 subjectId'),
      questionType: requiredQuestionType(row.questionType, '错题 questionType'),
      questionTitle: requiredString(row.questionTitle ?? row.title, '错题 questionTitle'),
      lastAttemptId: requiredId(row.lastAttemptId, '错题 lastAttemptId'),
      mistakeCount: requiredPositiveInteger(row.mistakeCount, '错题 mistakeCount'),
      lastWrongAt: requiredString(row.lastWrongAt, '错题 lastWrongAt'),
      status: requiredString(row.status, '错题 status'),
    }
  })
}

export function adaptPracticeFavorites(payload: unknown): PagedResult<PracticeFavoriteRecord> {
  return adaptPage(payload, '收藏题', (value) => {
    const row = requiredObject(value, '收藏题记录')
    return compactObject({
      id: requiredId(row.id, '收藏 id'), questionId: requiredId(row.questionId, '收藏 questionId'),
      qualificationId: requiredId(row.qualificationId, '收藏 qualificationId'),
      subjectId: row.subjectId == null ? undefined : requiredId(row.subjectId, '收藏 subjectId'),
      questionTitle: requiredString(row.questionTitle, '收藏 questionTitle'),
      favoriteGroup: optionalString(row.favoriteGroup, '收藏 favoriteGroup'),
      createdAt: requiredString(row.createdAt, '收藏 createdAt'),
    }) as PracticeFavoriteRecord
  })
}

function adaptPage<T>(payload: unknown, label: string, mapper: (value: unknown) => T): PagedResult<T> {
  const raw = requiredObject(payload, label)
  return {
    items: requiredArray(raw.items, `${label} items`).map(mapper),
    total: requiredNonNegativeInteger(raw.total, `${label} total`),
    page: requiredPositiveInteger(raw.pageNo ?? raw.page, `${label} pageNo`),
    pageSize: requiredPositiveInteger(raw.pageSize, `${label} pageSize`),
  }
}

function assignOptionalAttemptResult(target: object, raw: Record<string, unknown>): void {
  const record = target as Record<string, unknown>
  for (const key of ['totalScore', 'maxScore'] as const) {
    if (raw[key] != null) record[key] = requiredNumber(raw[key], key)
  }
  for (const key of ['correctCount', 'wrongCount', 'pendingReviewCount'] as const) {
    if (raw[key] != null) record[key] = requiredNonNegativeInteger(raw[key], key)
  }
  if (raw.submittedAt != null) record.submittedAt = requiredString(raw.submittedAt, 'submittedAt')
}

function mapOption(value: unknown): PracticeOption {
  const raw = requiredObject(value, '选项')
  return {
    code: requiredString(raw.optionKey, '选项 optionKey'),
    content: requiredString(raw.content, '选项 content'),
  }
}

function mapScoringPoint(value: unknown): PracticeScoringPoint {
  const raw = requiredObject(value, '评分点')
  return {
    scoringPointId: requiredId(raw.scoringPointId, '评分点 scoringPointId'),
    pointCode: requiredString(raw.pointCode, '评分点 pointCode'),
    pointText: requiredString(raw.pointText, '评分点 pointText'),
    score: requiredNumber(raw.score, '评分点 score'),
    sortOrder: requiredNonNegativeInteger(raw.sortOrder, '评分点 sortOrder'),
    contentHash: optionalString(raw.contentHash, '评分点 contentHash'),
  }
}

function readAnswerValue(value: unknown, label: string): PracticeAnswerValue {
  const payload = normalizeAnswerPayload(requiredObject(value, label) as PracticeAnswerPayload)
  if ('optionKey' in payload) return payload.optionKey
  if ('optionKeys' in payload) return payload.optionKeys
  if ('value' in payload) return payload.value
  if ('structured' in payload) return payload.structured
  return payload.text
}

function normalizeAnswerPayload(value: PracticeAnswerPayload): PracticeAnswerPayload {
  const raw = requiredObject(value, 'answerPayload')
  const branches = ['optionKey', 'optionKeys', 'value', 'text', 'structured'].filter((key) => raw[key] != null)
  if (branches.length !== 1) throw contractError('answerPayload 必须且只能包含一个答案分支')
  if (branches[0] === 'optionKey') return { optionKey: requiredString(raw.optionKey, 'answerPayload optionKey').toUpperCase() }
  if (branches[0] === 'optionKeys') {
    const keys = requiredArray(raw.optionKeys, 'answerPayload optionKeys')
      .map((key) => requiredString(key, 'answerPayload optionKey').toUpperCase())
    if (!keys.length || new Set(keys).size !== keys.length) throw contractError('answerPayload optionKeys 无效')
    return { optionKeys: [...keys].sort() }
  }
  if (branches[0] === 'value') return { value: requiredBoolean(raw.value, 'answerPayload value') }
  if (branches[0] === 'structured') {
    const structured = requiredObject(raw.structured, 'answerPayload structured')
    if (typeof structured.schemaVersion !== 'string' || !structured.schemaVersion.trim()) {
      throw contractError('structured 答案必须包含 schemaVersion')
    }
    return { structured }
  }
  return { text: requiredString(raw.text, 'answerPayload text') }
}

function positiveIdNumber(value: string): number {
  if (!/^\d+$/.test(value)) throw contractError('ID 必须是正整数')
  const number = Number(value)
  if (!Number.isSafeInteger(number) || number < 1) throw contractError('ID 必须是安全正整数')
  return number
}

function compactParams(value: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(Object.entries(value).filter(([, item]) => item !== undefined && item !== ''))
}

function compactObject<T extends Record<string, unknown>>(value: T): Partial<T> {
  return Object.fromEntries(Object.entries(value).filter(([, item]) => item !== undefined)) as Partial<T>
}

function requiredObject(value: unknown, label: string): Record<string, unknown> {
  if (value && typeof value === 'object' && !Array.isArray(value)) return value as Record<string, unknown>
  throw contractError(`${label} 必须是对象`)
}

function optionalObject(value: unknown, label: string): Record<string, unknown> | undefined {
  if (value == null) return undefined
  return requiredObject(value, label)
}

function requiredArray(value: unknown, label: string): unknown[] {
  if (Array.isArray(value)) return value
  throw contractError(`${label} 必须是数组`)
}

function requiredString(value: unknown, label: string): string {
  if (typeof value === 'string' && value.trim()) return value.trim()
  throw contractError(`${label} 缺失或无效`)
}

function optionalString(value: unknown, label: string): string | undefined {
  if (value == null) return undefined
  return requiredString(value, label)
}

function requiredId(value: unknown, label: string): string {
  if (typeof value === 'number' && Number.isSafeInteger(value) && value > 0) return String(value)
  if (typeof value === 'string' && /^\d+$/.test(value) && Number(value) > 0) return value
  throw contractError(`${label} 必须是正整数 ID`)
}

function requiredNumber(value: unknown, label: string): number {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  throw contractError(`${label} 必须是有限数字`)
}

function optionalNumber(value: unknown, label: string): number | undefined {
  return value == null ? undefined : requiredNumber(value, label)
}

function requiredPositiveInteger(value: unknown, label: string): number {
  const number = requiredNumber(value, label)
  if (Number.isSafeInteger(number) && number > 0) return number
  throw contractError(`${label} 必须是正整数`)
}

function requiredNonNegativeInteger(value: unknown, label: string): number {
  const number = requiredNumber(value, label)
  if (Number.isSafeInteger(number) && number >= 0) return number
  throw contractError(`${label} 必须是非负整数`)
}

function optionalNonNegativeInteger(value: unknown, label: string): number | undefined {
  return value == null ? undefined : requiredNonNegativeInteger(value, label)
}

function optionalInteger(value: unknown, label: string): number | undefined {
  if (value == null) return undefined
  const number = requiredNumber(value, label)
  if (Number.isSafeInteger(number)) return number
  throw contractError(`${label} 必须是整数`)
}

function requiredBoolean(value: unknown, label: string): boolean {
  if (typeof value === 'boolean') return value
  throw contractError(`${label} 必须是布尔值`)
}

function rejectPublicInventoryKeys(value: Record<string, unknown>, label: string): void {
  for (const key of Object.keys(value)) {
    if (/total|count|questionCount|paperCount|questionTypes/i.test(key)) {
      throw contractError(`${label} 不得返回库存或汇总字段 ${key}`)
    }
  }
}

function requiredEnum<T extends string>(value: unknown, allowed: ReadonlySet<T>, label: string): T {
  if (typeof value === 'string' && allowed.has(value as T)) return value as T
  throw contractError(`${label} 不受支持`)
}

function requiredQuestionType(value: unknown, label: string): PracticeQuestionType {
  return requiredEnum(value, QUESTION_TYPES, label)
}

function requiredAnswerFormat(value: unknown, label: string): PracticeSubQuestion['answerFormat'] {
  return requiredEnum(value, ANSWER_FORMATS, label)
}

function contractError(message: string): Error {
  return new Error(`练习接口响应格式错误：${message}`)
}
