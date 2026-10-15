import { platformApiClient, unwrapApiResponse } from '@/api/client'
import type {
  ActiveExamRule,
  EntityStatus,
  ExamDeadlinePolicy,
  ExamFeedbackPolicy,
  ExamQuestionType,
  ExamRuleSubject,
  FoundationKnowledgeNode,
  LevelCode,
  Qualification,
  QualificationUnavailableReason,
  Subject,
  SubjectType,
} from '../types'
import type { PlatformFoundationGateway } from './platform-foundation.gateway'

type JsonObject = Record<string, unknown>

const LEVEL_CODES = new Set<LevelCode>(['ADVANCED', 'INTERMEDIATE', 'PRIMARY'])
const ENTITY_STATUSES = new Set<EntityStatus>(['ENABLED', 'DISABLED', 'LOCKED'])
const SUBJECT_TYPES = new Set<SubjectType>(['COMPREHENSIVE', 'CASE', 'ESSAY', 'APPLICATION'])
const UNAVAILABLE_REASONS = new Set<QualificationUnavailableReason>([
  'UNVERIFIED',
  'VERIFICATION_EXPIRED',
  'QUALIFICATION_DISABLED',
  'NO_PUBLISHED_RULE',
  'OUTSIDE_EFFECTIVE_RANGE',
])
const DEADLINE_POLICIES = new Set<ExamDeadlinePolicy>([
  'INDEPENDENT_SUBJECTS',
  'SHARED_TOTAL_DEADLINE',
])
const FEEDBACK_POLICIES = new Set<ExamFeedbackPolicy>([
  'AFTER_QUESTION',
  'AFTER_ATTEMPT',
  'AFTER_EXAM',
])
const QUESTION_TYPES = new Set<ExamQuestionType>([
  'SINGLE_CHOICE',
  'MULTI_CHOICE',
  'JUDGE',
  'CASE',
  'ESSAY',
])
const RULE_FIELDS = new Set([
  'schemaVersion',
  'ruleVersion',
  'qualificationCode',
  'examCycle',
  'periodCode',
  'effectiveFrom',
  'effectiveTo',
  'enabled',
  'totalDurationMinutes',
  'deadlinePolicy',
  'sharedForceSubmitMinutes',
  'subjects',
  'verification',
])
const RULE_SUBJECT_FIELDS = new Set([
  'subjectCode',
  'subjectType',
  'displayOrder',
  'durationMinutes',
  'fullScore',
  'passScore',
  'questionTypes',
  'earliestSubmitMinutes',
  'forceSubmitMinutes',
  'feedbackPolicy',
  'linkedGroup',
  'sequence',
])
const VERIFICATION_FIELDS = new Set([
  'sourceUrl',
  'verifiedAt',
  'sourceTitle',
  'sourceHash',
])

function isObject(value: unknown): value is JsonObject {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function hasOwn(source: JsonObject, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(source, key)
}

function assertOnlyKeys(source: JsonObject, allowed: ReadonlySet<string>, label: string): void {
  const unknown = Object.keys(source).find((key) => !allowed.has(key))
  if (unknown) throw new Error(`${label}响应格式错误：未知字段 ${unknown}`)
}

function itemsFrom(payload: unknown, label: string): unknown[] {
  if (Array.isArray(payload)) return payload
  if (isObject(payload) && Array.isArray(payload.items)) return payload.items
  if (payload === null || payload === undefined) return []
  throw new Error(`${label}响应格式错误：items 必须是数组`)
}

function requiredId(value: unknown, label: string): string {
  if (typeof value === 'string' && value.trim()) return value.trim()
  if (typeof value === 'number' && Number.isSafeInteger(value) && value > 0) return String(value)
  throw new Error(`${label}响应格式错误：id 无效`)
}

function requiredString(source: JsonObject, keys: string[], label: string): string {
  for (const key of keys) {
    const value = source[key]
    if (typeof value === 'string' && value.trim()) return value.trim()
  }
  throw new Error(`${label}响应格式错误：${keys[0]} 缺失`)
}

function requiredUri(source: JsonObject, key: string, label: string): string {
  const value = requiredString(source, [key], label)
  try {
    const parsed = new URL(value)
    if (parsed.protocol) return value
  } catch {
    // The stable public error below intentionally omits parser internals.
  }
  throw new Error(`${label}响应格式错误：${key} 必须是绝对 URI`)
}

function requiredSha256(source: JsonObject, key: string, label: string): string {
  const value = requiredString(source, [key], label)
  if (/^sha256:[0-9a-f]{64}$/.test(value)) return value
  throw new Error(`${label}响应格式错误：${key} 必须是 sha256 小写摘要`)
}

function optionalNonNegativeNumber(source: JsonObject, key: string, label: string): number | undefined {
  const value = source[key]
  if (value === undefined || value === null) return undefined
  if (typeof value === 'number' && Number.isFinite(value) && value >= 0) return value
  throw new Error(`${label}响应格式错误：${key} 无效`)
}

function requiredPositiveInteger(source: JsonObject, key: string, label: string): number {
  const value = source[key]
  if (typeof value === 'number' && Number.isSafeInteger(value) && value > 0) return value
  throw new Error(`${label}响应格式错误：${key} 无效`)
}

function requiredNonNegativeInteger(source: JsonObject, key: string, label: string): number {
  const value = source[key]
  if (typeof value === 'number' && Number.isSafeInteger(value) && value >= 0) return value
  throw new Error(`${label}响应格式错误：${key} 无效`)
}

function requiredEnum<T extends string>(
  value: unknown,
  allowed: ReadonlySet<T>,
  label: string,
): T {
  if (typeof value === 'string' && allowed.has(value as T)) return value as T
  throw new Error(`${label}响应格式错误`)
}

function optionalEnum<T extends string>(
  value: unknown,
  allowed: ReadonlySet<T>,
): T | undefined {
  return typeof value === 'string' && allowed.has(value as T) ? value as T : undefined
}

function mapQualification(value: unknown): Qualification {
  if (!isObject(value)) throw new Error('资格响应格式错误：记录必须是对象')
  return {
    id: requiredId(value.id, '资格'),
    code: requiredString(value, ['qualificationCode', 'code'], '资格'),
    name: requiredString(value, ['qualificationName', 'name'], '资格'),
    levelCode: requiredEnum(value.levelCode, LEVEL_CODES, '资格 levelCode'),
    status: requiredEnum(value.status, ENTITY_STATUSES, '资格 status'),
    sortOrder: optionalNonNegativeNumber(value, 'sortOrder', '资格'),
    available: typeof value.available === 'boolean' ? value.available : undefined,
    unavailableReason: optionalEnum(value.unavailableReason, UNAVAILABLE_REASONS),
  }
}

function mapSubject(value: unknown): Subject {
  if (!isObject(value)) throw new Error('科目响应格式错误：记录必须是对象')
  return {
    id: requiredId(value.id, '科目'),
    qualificationId: requiredId(value.qualificationId, '科目 qualificationId'),
    code: requiredString(value, ['subjectCode', 'code'], '科目'),
    name: requiredString(value, ['subjectName', 'name'], '科目'),
    type: requiredEnum(value.subjectType ?? value.type, SUBJECT_TYPES, '科目 subjectType'),
    status: requiredEnum(value.status, ENTITY_STATUSES, '科目 status'),
    sortOrder: optionalNonNegativeNumber(value, 'sortOrder', '科目'),
  }
}

function mapKnowledgeNode(value: unknown, kind: 'chapter' | 'syllabus'): FoundationKnowledgeNode {
  if (!isObject(value)) throw new Error('知识树响应格式错误：记录必须是对象')
  const nameKey = kind === 'chapter' ? 'chapterName' : 'nodeName'
  const codeKey = kind === 'chapter' ? 'chapterCode' : 'nodeCode'
  const rawChildren = value.children
  if (rawChildren !== undefined && !Array.isArray(rawChildren)) {
    throw new Error('知识树响应格式错误：children 必须是数组')
  }
  const levelNo = value.levelNo
  if (typeof levelNo !== 'number' || !Number.isSafeInteger(levelNo) || levelNo < 0) {
    throw new Error('知识树响应格式错误：levelNo 无效')
  }
  return {
    id: requiredId(value.id, '知识树'),
    ...(value.parentId == null ? {} : { parentId: requiredId(value.parentId, '知识树 parentId') }),
    code: requiredString(value, [codeKey], '知识树'),
    name: requiredString(value, [nameKey], '知识树'),
    levelNo,
    children: (rawChildren ?? []).map((child) => mapKnowledgeNode(child, kind)),
  }
}

function mapKnowledgeTag(value: unknown): FoundationKnowledgeNode {
  if (!isObject(value)) throw new Error('知识点响应格式错误：记录必须是对象')
  return {
    id: requiredId(value.id, '知识点'),
    code: requiredString(value, ['tagCode'], '知识点'),
    name: requiredString(value, ['tagName'], '知识点'),
    levelNo: 1,
    children: [],
  }
}

function parseRuleJson(value: unknown): JsonObject {
  if (isObject(value)) return value
  if (typeof value !== 'string' || !value.trim()) {
    throw new Error('考试规则响应格式错误：ruleJson 缺失')
  }
  try {
    const parsed: unknown = JSON.parse(value)
    if (isObject(parsed)) return parsed
  } catch {
    // The single public error below keeps raw JSON details out of the UI.
  }
  throw new Error('考试规则响应格式错误：ruleJson 无效')
}

function mapRuleSubject(value: unknown, deadlinePolicy: ExamDeadlinePolicy): ExamRuleSubject {
  if (!isObject(value)) throw new Error('考试规则响应格式错误：subjects 记录无效')
  assertOnlyKeys(value, RULE_SUBJECT_FIELDS, '考试规则科目')
  const questionTypes = value.questionTypes
  if (
    !Array.isArray(questionTypes)
    || !questionTypes.length
  ) {
    throw new Error('考试规则响应格式错误：questionTypes 无效')
  }
  const mappedQuestionTypes = questionTypes.map((item) =>
    requiredEnum(item, QUESTION_TYPES, '考试规则 questionTypes')
  )
  if (new Set(mappedQuestionTypes).size !== mappedQuestionTypes.length) {
    throw new Error('考试规则响应格式错误：questionTypes 不得重复')
  }

  const durationMinutes = requiredPositiveInteger(value, 'durationMinutes', '考试规则科目')
  const fullScore = requiredNonNegativeInteger(value, 'fullScore', '考试规则科目')
  const passScore = requiredNonNegativeInteger(value, 'passScore', '考试规则科目')
  if (passScore > fullScore) {
    throw new Error('考试规则响应格式错误：passScore 不得大于 fullScore')
  }
  const earliestSubmitMinutes = requiredNonNegativeInteger(
    value,
    'earliestSubmitMinutes',
    '考试规则科目',
  )
  if (earliestSubmitMinutes > durationMinutes) {
    throw new Error('考试规则响应格式错误：earliestSubmitMinutes 超出科目时长')
  }
  const base = {
    subjectCode: requiredString(value, ['subjectCode'], '考试规则科目'),
    subjectType: requiredEnum(value.subjectType, SUBJECT_TYPES, '考试规则 subjectType'),
    displayOrder: requiredPositiveInteger(value, 'displayOrder', '考试规则科目'),
    durationMinutes,
    fullScore,
    passScore,
    questionTypes: mappedQuestionTypes,
    earliestSubmitMinutes,
    feedbackPolicy: requiredEnum(
      value.feedbackPolicy,
      FEEDBACK_POLICIES,
      '考试规则 feedbackPolicy',
    ),
  }

  if (deadlinePolicy === 'INDEPENDENT_SUBJECTS') {
    if (hasOwn(value, 'linkedGroup') || hasOwn(value, 'sequence')) {
      throw new Error('考试规则响应格式错误：独立科目规则禁止 linkedGroup/sequence')
    }
    const forceSubmitMinutes = requiredPositiveInteger(
      value,
      'forceSubmitMinutes',
      '考试规则科目',
    )
    if (forceSubmitMinutes > durationMinutes) {
      throw new Error('考试规则响应格式错误：forceSubmitMinutes 超出科目时长')
    }
    return { ...base, forceSubmitMinutes }
  }

  if (hasOwn(value, 'forceSubmitMinutes')) {
    throw new Error('考试规则响应格式错误：共享截止规则禁止 forceSubmitMinutes')
  }
  return {
    ...base,
    linkedGroup: requiredString(value, ['linkedGroup'], '考试规则科目'),
    sequence: requiredPositiveInteger(value, 'sequence', '考试规则科目'),
  }
}

function validateRuleAggregation(
  ruleJson: JsonObject,
  deadlinePolicy: ExamDeadlinePolicy,
  totalDurationMinutes: number,
  subjects: ExamRuleSubject[],
): number | undefined {
  const subjectCodes = subjects.map((subject) => subject.subjectCode)
  if (new Set(subjectCodes).size !== subjectCodes.length) {
    throw new Error('考试规则响应格式错误：subjectCode 不得重复')
  }
  const displayOrders = subjects.map((subject) => subject.displayOrder)
  if (new Set(displayOrders).size !== displayOrders.length) {
    throw new Error('考试规则响应格式错误：displayOrder 不得重复')
  }

  const durationSum = subjects.reduce((sum, subject) => sum + subject.durationMinutes, 0)
  if (deadlinePolicy === 'INDEPENDENT_SUBJECTS') {
    if (hasOwn(ruleJson, 'sharedForceSubmitMinutes')) {
      throw new Error('考试规则响应格式错误：独立科目规则禁止 sharedForceSubmitMinutes')
    }
    if (totalDurationMinutes !== durationSum) {
      throw new Error('考试规则响应格式错误：独立科目总时长必须等于科目时长之和')
    }
    return undefined
  }

  if (subjects.length < 2) {
    throw new Error('考试规则响应格式错误：共享截止规则至少需要两个科目')
  }
  const sharedForceSubmitMinutes = requiredPositiveInteger(
    ruleJson,
    'sharedForceSubmitMinutes',
    '考试规则',
  )
  if (sharedForceSubmitMinutes > totalDurationMinutes) {
    throw new Error('考试规则响应格式错误：sharedForceSubmitMinutes 超出总时长')
  }

  const linkedGroups = subjects.map((subject) => subject.linkedGroup)
  if (new Set(linkedGroups).size !== 1) {
    throw new Error('考试规则响应格式错误：共享截止规则必须使用同一个 linkedGroup')
  }
  const sequences = subjects.map((subject) => subject.sequence).sort((left, right) =>
    (left as number) - (right as number)
  )
  if (sequences.some((sequence, index) => sequence !== index + 1)) {
    throw new Error('考试规则响应格式错误：共享截止规则 sequence 必须从 1 唯一连续')
  }

  const maximumDuration = Math.max(...subjects.map((subject) => subject.durationMinutes))
  if (totalDurationMinutes < maximumDuration || totalDurationMinutes > durationSum) {
    throw new Error('考试规则响应格式错误：共享截止规则总时长超出允许范围')
  }
  return sharedForceSubmitMinutes
}

function parseInstant(value: string, label: string): number {
  if (
    !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/.test(value)
  ) {
    throw new Error(`考试规则响应格式错误：${label} 必须是带时区的 ISO-8601 时间`)
  }
  const timestamp = Date.parse(value)
  if (!Number.isFinite(timestamp)) throw new Error(`考试规则响应格式错误：${label} 无效`)
  return timestamp
}

function mapActiveExamRule(
  value: unknown,
  qualificationId: string,
  periodCode: string,
  now: number,
): ActiveExamRule | null {
  if (!isObject(value)) throw new Error('考试规则响应格式错误：记录必须是对象')
  const ruleJson = parseRuleJson(value.ruleJson)
  assertOnlyKeys(ruleJson, RULE_FIELDS, '考试规则')
  const schemaVersion = requiredString(ruleJson, ['schemaVersion'], '考试规则')
  if (schemaVersion !== '1.0') throw new Error('考试规则响应格式错误：schemaVersion 不受支持')
  const mappedPeriodCode = requiredString(ruleJson, ['periodCode'], '考试规则')
  if (mappedPeriodCode !== periodCode) return null
  if (typeof ruleJson.enabled !== 'boolean') {
    throw new Error('考试规则响应格式错误：enabled 必须是布尔值')
  }
  if (!ruleJson.enabled) return null

  const effectiveFrom = requiredString(ruleJson, ['effectiveFrom'], '考试规则')
  const effectiveTo = requiredString(ruleJson, ['effectiveTo'], '考试规则')
  const effectiveFromTime = parseInstant(effectiveFrom, 'effectiveFrom')
  const effectiveToTime = parseInstant(effectiveTo, 'effectiveTo')
  if (effectiveFromTime >= effectiveToTime) {
    throw new Error('考试规则响应格式错误：有效期范围无效')
  }
  if (now < effectiveFromTime || now >= effectiveToTime) return null

  const subjects = ruleJson.subjects
  if (!Array.isArray(subjects) || !subjects.length) {
    throw new Error('考试规则响应格式错误：subjects 必须是非空数组')
  }

  const mappedQualificationId = requiredId(
    value.qualificationId,
    '考试规则 qualificationId',
  )
  if (mappedQualificationId !== qualificationId) {
    throw new Error('考试规则响应与当前资格不匹配')
  }
  requiredString(ruleJson, ['qualificationCode'], '考试规则')
  requiredString(ruleJson, ['examCycle'], '考试规则')
  const dtoRuleVersion = requiredString(value, ['ruleVersion'], '考试规则')
  const jsonRuleVersion = requiredString(ruleJson, ['ruleVersion'], '考试规则')
  if (dtoRuleVersion !== jsonRuleVersion) {
    throw new Error('考试规则响应格式错误：ruleVersion 锚点不一致')
  }
  const deadlinePolicy = requiredEnum(
    ruleJson.deadlinePolicy,
    DEADLINE_POLICIES,
    '考试规则 deadlinePolicy',
  )
  const totalDurationMinutes = requiredPositiveInteger(
    ruleJson,
    'totalDurationMinutes',
    '考试规则',
  )
  const mappedSubjects = subjects.map((subject) => mapRuleSubject(subject, deadlinePolicy))
  const sharedForceSubmitMinutes = validateRuleAggregation(
    ruleJson,
    deadlinePolicy,
    totalDurationMinutes,
    mappedSubjects,
  )
  const verification = ruleJson.verification
  if (!isObject(verification)) {
    throw new Error('考试规则响应格式错误：verification 必须是对象')
  }
  assertOnlyKeys(verification, VERIFICATION_FIELDS, '考试规则 verification')
  requiredUri(verification, 'sourceUrl', '考试规则 verification')
  requiredString(verification, ['sourceTitle'], '考试规则 verification')
  requiredSha256(verification, 'sourceHash', '考试规则 verification')
  const sourceVerifiedAt = requiredString(verification, ['verifiedAt'], '考试规则 verification')
  parseInstant(sourceVerifiedAt, 'verification.verifiedAt')

  return {
    ruleId: requiredId(value.id ?? ruleJson.ruleId, '考试规则'),
    ruleCode: requiredString(value, ['ruleCode'], '考试规则'),
    ruleVersion: dtoRuleVersion,
    schemaVersion,
    qualificationId: mappedQualificationId,
    periodCode: mappedPeriodCode,
    effectiveFrom,
    effectiveTo,
    totalDurationMinutes,
    deadlinePolicy,
    ...(sharedForceSubmitMinutes === undefined ? {} : { sharedForceSubmitMinutes }),
    subjects: mappedSubjects,
    sourceVerifiedAt,
  }
}

export class HttpPlatformFoundationGateway implements PlatformFoundationGateway {
  async listQualifications(): Promise<Qualification[]> {
    const payload = await unwrapApiResponse<unknown>(
      platformApiClient.get('/foundation/qualifications', {
        params: { pageNo: 1, pageSize: 100 },
      }),
    )
    return itemsFrom(payload, '资格').map(mapQualification)
  }

  async listSubjects(qualificationId: string): Promise<Subject[]> {
    const payload = await unwrapApiResponse<unknown>(
      platformApiClient.get(`/foundation/qualifications/${qualificationId}/subjects`, {
        params: { pageNo: 1, pageSize: 100 },
      }),
    )
    return itemsFrom(payload, '科目').map(mapSubject)
  }

  async listChapterNodes(subjectId: string): Promise<FoundationKnowledgeNode[]> {
    const payload = await unwrapApiResponse<unknown>(platformApiClient.get(
      `/foundation/subjects/${subjectId}/chapter-nodes`,
      { params: { pageNo: 1, pageSize: 100 } },
    ))
    return itemsFrom(payload, '章节树').map((item) => mapKnowledgeNode(item, 'chapter'))
  }

  async listSyllabusNodes(subjectId: string): Promise<FoundationKnowledgeNode[]> {
    const payload = await unwrapApiResponse<unknown>(platformApiClient.get(
      `/foundation/subjects/${subjectId}/syllabus-nodes`,
      { params: { pageNo: 1, pageSize: 100 } },
    ))
    return itemsFrom(payload, '大纲树').map((item) => mapKnowledgeNode(item, 'syllabus'))
  }

  async listKnowledgeTags(qualificationId: string, subjectId: string): Promise<FoundationKnowledgeNode[]> {
    const payload = await unwrapApiResponse<unknown>(platformApiClient.get(
      '/foundation/knowledge-tags',
      { params: { qualificationId, subjectId, pageNo: 1, pageSize: 100 } },
    ))
    return itemsFrom(payload, '知识点').map(mapKnowledgeTag)
  }

  async getActiveExamRule(qualificationId: string, periodCode: string): Promise<ActiveExamRule | null> {
    const payload = await unwrapApiResponse<unknown>(
      platformApiClient.get('/foundation/exam-rules', {
        params: { qualificationId, pageNo: 1, pageSize: 100 },
      }),
    )
    const active = itemsFrom(payload, '考试规则').filter((item) =>
      isObject(item) && item.status === 'PUBLISHED',
    ).map((item) => mapActiveExamRule(item, qualificationId, periodCode, Date.now()))
      .filter((item): item is ActiveExamRule => item !== null)
    const selected = active[0]
    if (!selected) return null
    if (active.length > 1) throw new Error('当前资格存在多条有效考试规则，无法确定唯一活动规则')
    return selected
  }
}
