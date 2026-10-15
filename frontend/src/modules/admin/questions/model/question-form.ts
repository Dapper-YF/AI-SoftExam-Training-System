import type {
  AdminCopyrightStatus,
  AdminQuestionDetail,
  AdminQuestionDifficulty,
  AdminQuestionPayload,
  AdminQuestionSourceType,
  AdminQuestionType,
} from '../types'

export interface QuestionOptionForm {
  content: string
  correct: boolean
}

export interface ScoringPointForm {
  code: string
  text: string
  score: number
}

export interface CaseSubQuestionForm {
  key: string
  content: string
  score: number
  referenceAnswer: string
  analysis: string
  scoringPoints: ScoringPointForm[]
}

export interface AdminQuestionFormModel {
  qualificationId: number | null
  subjectId: number | null
  questionType: AdminQuestionType
  title: string
  difficulty: AdminQuestionDifficulty
  sourceType: AdminQuestionSourceType
  sourceRef: string
  yearNo: number | null
  copyrightStatus: AdminCopyrightStatus
  usageRestriction: string
  stem: string
  subQuestionPrompt: string
  analysis: string
  knowledgeIds: string
  tagIds: string
  options: QuestionOptionForm[]
  judgeAnswer: boolean
  caseMaterial: string
  caseSubQuestions: CaseSubQuestionForm[]
  essayBackground: string
  essayRequirement: string
  essayPrompt: string
  essayMinWords: number
  essayMaxWords: number
  essayRequiredStructures: string[]
  essayReferenceAnswer: string
  essayScore: number
  essayScoringDimensions: ScoringPointForm[]
}

let localKey = 0

function nextKey(prefix: string): string {
  localKey += 1
  return `${prefix}-${localKey}`
}

export function blankScoringPoint(index = 0): ScoringPointForm {
  return { code: `P${index + 1}`, text: '', score: 1 }
}

export function blankCaseSubQuestion(index = 0): CaseSubQuestionForm {
  return {
    key: nextKey('case-sub'),
    content: '',
    score: 5,
    referenceAnswer: '',
    analysis: '',
    scoringPoints: [{ code: `P${index + 1}-1`, text: '', score: 5 }],
  }
}

export function createBlankQuestionForm(questionType: AdminQuestionType = 'SINGLE_CHOICE'): AdminQuestionFormModel {
  return {
    qualificationId: null,
    subjectId: null,
    questionType,
    title: '',
    difficulty: 'MEDIUM',
    sourceType: 'MANUAL',
    sourceRef: '',
    yearNo: null,
    copyrightStatus: 'UNVERIFIED',
    usageRestriction: 'INTERNAL_ONLY',
    stem: '',
    subQuestionPrompt: '',
    analysis: '',
    options: [
      { content: '', correct: true },
      { content: '', correct: false },
    ],
    judgeAnswer: true,
    knowledgeIds: '',
    tagIds: '',
    caseMaterial: '',
    caseSubQuestions: [blankCaseSubQuestion(0), blankCaseSubQuestion(1)],
    essayBackground: '',
    essayRequirement: '',
    essayPrompt: '',
    essayMinWords: 800,
    essayMaxWords: 1200,
    essayRequiredStructures: ['摘要', '正文', '结语'],
    essayReferenceAnswer: '按题目要求完成结构化论文写作。',
    essayScore: 30,
    essayScoringDimensions: [
      { code: 'TOPIC', text: '切合主题', score: 10 },
      { code: 'CONTENT', text: '内容完整', score: 10 },
      { code: 'STRUCTURE', text: '结构清晰', score: 10 },
    ],
  }
}

function parseIds(value: string): number[] {
  return [...new Set(value.split(/[，,\s]+/).map(Number).filter((item) => Number.isSafeInteger(item) && item > 0))]
}

function bindingsOf(form: AdminQuestionFormModel): AdminQuestionPayload['knowledgeBindings'] {
  const knowledge = parseIds(form.knowledgeIds).map((knowledgeId, index) => ({
    targetType: 'QUESTION',
    targetId: null,
    knowledgeType: 'SYLLABUS',
    knowledgeId,
    sortOrder: index + 1,
  }))
  const tags = parseIds(form.tagIds).map((knowledgeId, index) => ({
    targetType: 'QUESTION',
    targetId: null,
    knowledgeType: 'TAG',
    knowledgeId,
    sortOrder: knowledge.length + index + 1,
  }))
  return [...knowledge, ...tags]
}

function objectiveSubQuestion(form: AdminQuestionFormModel): AdminQuestionPayload['subQuestions'][number] {
  const type = form.questionType as 'SINGLE_CHOICE' | 'MULTI_CHOICE' | 'JUDGE'
  const optionKeys = form.options
    .map((option, index) => ({ option, key: String.fromCharCode(65 + index) }))
    .filter(({ option }) => option.correct)
    .map(({ key }) => key)
  const isChoice = type === 'SINGLE_CHOICE' || type === 'MULTI_CHOICE'
  const answerValue = type === 'JUDGE' ? String(form.judgeAnswer) : optionKeys.join(',')
  return {
    subQuestionNo: 1,
    subQuestionType: type,
    content: form.subQuestionPrompt.trim() || form.stem.trim(),
    score: 1,
    answerFormat: isChoice ? 'OPTION_KEYS' : 'BOOLEAN',
    gradingMode: 'AUTO',
    sortOrder: 1,
    metadataJson: null,
    options: isChoice
      ? form.options.map((option, index) => ({
        optionKey: String.fromCharCode(65 + index),
        optionContent: option.content.trim(),
        sortOrder: index + 1,
      }))
      : [],
    answerKeys: [{
      answerFormat: isChoice ? 'OPTION_KEYS' : 'BOOLEAN',
      answerValue,
      answerJson: isChoice ? JSON.stringify(optionKeys) : null,
    }],
    solutions: form.analysis.trim()
      ? [{ solutionType: 'ANALYSIS', content: form.analysis.trim(), sortOrder: 1 }]
      : [],
    scoringRules: [{
      ruleType: 'AUTO',
      ruleJson: JSON.stringify({ mode: type === 'MULTI_CHOICE' ? 'SET_EQUAL' : 'EXACT' }),
      sortOrder: 1,
    }],
    scoringPoints: [],
    knowledgeBindings: [],
  }
}

function caseSubQuestions(form: AdminQuestionFormModel): AdminQuestionPayload['subQuestions'] {
  return form.caseSubQuestions.map((sub, index) => ({
    subQuestionNo: index + 1,
    subQuestionType: 'SHORT_ANSWER',
    content: sub.content.trim(),
    score: Number(sub.score),
    answerFormat: 'TEXT',
    gradingMode: 'MANUAL',
    sortOrder: index + 1,
    metadataJson: null,
    options: [],
    answerKeys: [{ answerFormat: 'TEXT', answerValue: sub.referenceAnswer.trim(), answerJson: null }],
    solutions: [
      { solutionType: 'REFERENCE_ANSWER', content: sub.referenceAnswer.trim(), sortOrder: 1 },
      ...(sub.analysis.trim() ? [{ solutionType: 'ANALYSIS' as const, content: sub.analysis.trim(), sortOrder: 2 }] : []),
    ],
    scoringRules: [{ ruleType: 'MANUAL', ruleJson: '{"mode":"MANUAL"}', sortOrder: 1 }],
    scoringPoints: sub.scoringPoints.map((point, pointIndex) => ({
      pointCode: point.code.trim() || `Q${index + 1}P${pointIndex + 1}`,
      pointText: point.text.trim(),
      score: Number(point.score),
      sortOrder: pointIndex + 1,
    })),
    knowledgeBindings: [],
  }))
}

function essaySubQuestion(form: AdminQuestionFormModel): AdminQuestionPayload['subQuestions'][number] {
  return {
    subQuestionNo: 1,
    subQuestionType: 'ESSAY_WRITING',
    content: form.essayPrompt.trim() || form.title.trim(),
    score: Number(form.essayScore),
    answerFormat: 'TEXT',
    gradingMode: 'MANUAL',
    sortOrder: 1,
    metadataJson: JSON.stringify({
      minWords: Number(form.essayMinWords),
      maxWords: Number(form.essayMaxWords),
      requiredStructures: form.essayRequiredStructures.map((item) => item.trim()).filter(Boolean),
    }),
    options: [],
    answerKeys: [{ answerFormat: 'TEXT', answerValue: form.essayReferenceAnswer.trim(), answerJson: null }],
    solutions: [{ solutionType: 'REFERENCE_ANSWER', content: form.essayReferenceAnswer.trim(), sortOrder: 1 }],
    scoringRules: [{ ruleType: 'MANUAL', ruleJson: '{"mode":"MANUAL"}', sortOrder: 1 }],
    scoringPoints: form.essayScoringDimensions.map((point, index) => ({
      pointCode: point.code.trim() || `D${index + 1}`,
      pointText: point.text.trim(),
      score: Number(point.score),
      sortOrder: index + 1,
    })),
    knowledgeBindings: [],
  }
}

export function buildQuestionPayload(form: AdminQuestionFormModel): AdminQuestionPayload {
  const common = {
    qualificationId: Number(form.qualificationId),
    subjectId: Number(form.subjectId),
    questionType: form.questionType,
    title: form.title.trim(),
    yearNo: form.yearNo == null ? null : Number(form.yearNo),
    difficulty: form.difficulty,
    sourceType: form.sourceType,
    sourceRef: form.sourceRef.trim() || null,
    knowledgeBindings: bindingsOf(form),
  }
  if (form.questionType === 'CASE') {
    return {
      ...common,
      stems: [{ stemType: 'CASE_MATERIAL', content: form.caseMaterial.trim(), sortOrder: 1 }],
      subQuestions: caseSubQuestions(form),
    }
  }
  if (form.questionType === 'ESSAY') {
    return {
      ...common,
      stems: [
        { stemType: 'MAIN_STEM', content: form.essayBackground.trim(), sortOrder: 1 },
        { stemType: 'ESSAY_REQUIREMENT', content: form.essayRequirement.trim(), sortOrder: 2 },
      ],
      subQuestions: [essaySubQuestion(form)],
    }
  }
  return {
    ...common,
    stems: [{ stemType: 'MAIN_STEM', content: form.stem.trim(), sortOrder: 1 }],
    subQuestions: [objectiveSubQuestion(form)],
  }
}

function scoreMatches(score: number, points: ScoringPointForm[]): boolean {
  const total = points.reduce((sum, point) => sum + Number(point.score || 0), 0)
  return Math.abs(total - Number(score)) < 0.001
}

export function validateQuestionForm(form: AdminQuestionFormModel): string[] {
  const errors: string[] = []
  if (!form.qualificationId || form.qualificationId <= 0) errors.push('请选择有效资格')
  if (!form.subjectId || form.subjectId <= 0) errors.push('请选择有效科目')
  if (!form.title.trim()) errors.push('请输入题目标题')
  if (form.sourceType === 'AUTHORIZED_IMPORT' && !form.sourceRef.trim()) errors.push('授权导入必须填写来源说明')
  if (form.sourceType === 'AUTHORIZED_IMPORT' && form.copyrightStatus !== 'AUTHORIZED') errors.push('授权导入的权利状态必须为 AUTHORIZED')
  if (form.sourceType === 'ORIGINAL' && form.copyrightStatus !== 'ORIGINAL_DECLARED') errors.push('原创题目的权利状态必须为 ORIGINAL_DECLARED')
  if (form.sourceType === 'SOURCE_DOCUMENT_IMPORT' && form.copyrightStatus !== 'UNVERIFIED') errors.push('来源文档导入的权利状态必须为 UNVERIFIED')
  if (form.yearNo != null && (form.yearNo < 2000 || form.yearNo > 2100)) errors.push('年份必须在 2000 至 2100 之间')
  if (form.questionType === 'SINGLE_CHOICE' || form.questionType === 'MULTI_CHOICE') {
    if (!form.stem.trim()) errors.push('请输入题干')
    if (form.options.length < 2 || form.options.some((option) => !option.content.trim())) errors.push('选择题至少需要两个非空选项')
    const answerCount = form.options.filter((option) => option.correct).length
    if (form.questionType === 'SINGLE_CHOICE' && answerCount !== 1) errors.push('单选题必须且只能有一个正确选项')
    if (form.questionType === 'MULTI_CHOICE' && answerCount < 2) errors.push('多选题至少需要两个正确选项')
  } else if (form.questionType === 'JUDGE') {
    if (!form.stem.trim()) errors.push('请输入判断题题干')
  } else if (form.questionType === 'CASE') {
    if (!form.caseMaterial.trim()) errors.push('请输入案例材料')
    if (form.caseSubQuestions.length < 2 || form.caseSubQuestions.length > 4) errors.push('案例题必须包含 2 至 4 个子题')
    form.caseSubQuestions.forEach((sub, index) => {
      const label = `案例子题 ${index + 1}`
      if (!sub.content.trim()) errors.push(`${label}题干不能为空`)
      if (!sub.referenceAnswer.trim()) errors.push(`${label}参考答案不能为空`)
      if (!(sub.score > 0)) errors.push(`${label}分值必须大于 0`)
      if (!sub.scoringPoints.length || sub.scoringPoints.some((point) => !point.text.trim() || !(point.score > 0))) {
        errors.push(`${label}评分点必须完整`)
      } else if (!scoreMatches(sub.score, sub.scoringPoints)) {
        errors.push(`${label}评分点合计必须等于子题分值`)
      }
    })
  } else {
    if (!form.essayBackground.trim()) errors.push('请输入论文背景材料')
    if (!form.essayRequirement.trim()) errors.push('请输入论文写作要求')
    if (!(form.essayMinWords > 0) || form.essayMaxWords < form.essayMinWords) errors.push('论文最大字数必须不小于最小字数')
    if (!form.essayRequiredStructures.some((item) => item.trim())) errors.push('论文至少需要一个必答结构')
    if (!form.essayReferenceAnswer.trim()) errors.push('请输入论文参考作答说明')
    if (!form.essayScoringDimensions.length || form.essayScoringDimensions.some((point) => !point.text.trim() || !(point.score > 0))) {
      errors.push('论文评分维度必须完整')
    } else if (!scoreMatches(form.essayScore, form.essayScoringDimensions)) {
      errors.push('论文评分维度合计必须等于总分')
    }
  }
  return [...new Set(errors)]
}

function solutionContent(sub: AdminQuestionPayload['subQuestions'][number], type: string): string {
  return sub.solutions.find((item) => item.solutionType === type)?.content ?? ''
}

function parseOptionKeys(sub: AdminQuestionPayload['subQuestions'][number]): Set<string> {
  const answer = sub.answerKeys[0]
  if (answer?.answerJson) {
    try {
      const parsed = JSON.parse(answer.answerJson) as unknown
      if (Array.isArray(parsed)) return new Set(parsed.map(String))
    } catch {
      // Fall through to the canonical comma-separated representation.
    }
  }
  return new Set((answer?.answerValue ?? '').split(',').map((item) => item.trim()).filter(Boolean))
}

export function questionFormFromDetail(detail: AdminQuestionDetail): AdminQuestionFormModel {
  const payload = detail.payload
  const form = createBlankQuestionForm(payload.questionType)
  form.qualificationId = payload.qualificationId
  form.subjectId = payload.subjectId
  form.title = payload.title ?? ''
  form.yearNo = payload.yearNo ?? null
  form.difficulty = payload.difficulty ?? 'MEDIUM'
  form.sourceType = payload.sourceType ?? 'MANUAL'
  form.sourceRef = payload.sourceRef ?? ''
  form.copyrightStatus = detail.copyrightStatus ?? 'UNVERIFIED'
  form.usageRestriction = detail.usageRestriction ?? 'INTERNAL_ONLY'
  form.knowledgeIds = payload.knowledgeBindings.filter((item) => item.knowledgeType !== 'TAG').map((item) => item.knowledgeId).join(',')
  form.tagIds = payload.knowledgeBindings.filter((item) => item.knowledgeType === 'TAG').map((item) => item.knowledgeId).join(',')
  const first = payload.subQuestions[0]
  if (payload.questionType === 'CASE') {
    form.caseMaterial = payload.stems.find((item) => item.stemType === 'CASE_MATERIAL')?.content ?? ''
    form.caseSubQuestions = payload.subQuestions.map((sub) => ({
      key: nextKey('case-sub'),
      content: sub.content,
      score: Number(sub.score),
      referenceAnswer: sub.answerKeys[0]?.answerValue ?? solutionContent(sub, 'REFERENCE_ANSWER'),
      analysis: solutionContent(sub, 'ANALYSIS'),
      scoringPoints: sub.scoringPoints.map((point) => ({ code: point.pointCode, text: point.pointText, score: Number(point.score) })),
    }))
  } else if (payload.questionType === 'ESSAY') {
    form.essayBackground = payload.stems.find((item) => item.stemType === 'MAIN_STEM')?.content ?? ''
    form.essayRequirement = payload.stems.find((item) => item.stemType === 'ESSAY_REQUIREMENT')?.content ?? ''
    form.essayPrompt = first?.content ?? ''
    form.essayReferenceAnswer = first?.answerKeys[0]?.answerValue ?? ''
    form.essayScore = Number(first?.score ?? 30)
    form.essayScoringDimensions = first?.scoringPoints.map((point) => ({ code: point.pointCode, text: point.pointText, score: Number(point.score) })) ?? []
    try {
      const metadata = JSON.parse(first?.metadataJson ?? '{}') as { minWords?: number, maxWords?: number, requiredStructures?: string[] }
      form.essayMinWords = Number(metadata.minWords ?? 800)
      form.essayMaxWords = Number(metadata.maxWords ?? 1200)
      form.essayRequiredStructures = Array.isArray(metadata.requiredStructures) ? metadata.requiredStructures.map(String) : []
    } catch {
      form.essayRequiredStructures = []
    }
  } else if (first) {
    form.stem = payload.stems.find((item) => item.stemType === 'MAIN_STEM')?.content ?? ''
    form.subQuestionPrompt = first.content
    form.analysis = solutionContent(first, 'ANALYSIS')
    if (payload.questionType === 'JUDGE') {
      form.judgeAnswer = first.answerKeys[0]?.answerValue?.toLowerCase() === 'true'
    } else {
      const answers = parseOptionKeys(first)
      form.options = first.options.map((option) => ({ content: option.optionContent, correct: answers.has(option.optionKey) }))
    }
  }
  return form
}
