import { describe, expect, it } from 'vitest'
import { buildQuestionPayload, createBlankQuestionForm, validateQuestionForm } from './question-form'
import type { AdminQuestionType } from '../types'

function validBase(type: AdminQuestionType) {
  const form = createBlankQuestionForm(type)
  form.qualificationId = 1
  form.subjectId = type === 'CASE' ? 12 : type === 'ESSAY' ? 13 : 11
  form.title = `${type} 测试题`
  form.stem = '客观题题干'
  form.analysis = '客观题解析'
  form.options[0]!.content = '选项 A'
  form.options[1]!.content = '选项 B'
  form.caseMaterial = '案例材料正文'
  form.caseSubQuestions.forEach((sub, index) => {
    sub.content = `案例子题 ${index + 1}`
    sub.referenceAnswer = `参考答案 ${index + 1}`
    sub.analysis = `解析 ${index + 1}`
    sub.score = 5
    sub.scoringPoints = [{ code: `Q${index + 1}P1`, text: '评分点', score: 5 }]
  })
  form.essayBackground = '论文背景材料'
  form.essayRequirement = '结合项目经验完成论文写作'
  form.essayPrompt = '论信息系统项目的范围管理'
  return form
}

describe('question form canonical DTO', () => {
  it('builds a V11 single-choice payload with ordered options and one answer', () => {
    const form = validBase('SINGLE_CHOICE')
    form.options.push({ content: '选项 C', correct: false })
    form.options[0]!.correct = false
    form.options[1]!.correct = true

    expect(validateQuestionForm(form)).toEqual([])
    const payload = buildQuestionPayload(form)
    expect(payload.subQuestions[0]).toMatchObject({
      subQuestionType: 'SINGLE_CHOICE',
      answerFormat: 'OPTION_KEYS',
      gradingMode: 'AUTO',
      options: [
        { optionKey: 'A', sortOrder: 1 },
        { optionKey: 'B', sortOrder: 2 },
        { optionKey: 'C', sortOrder: 3 },
      ],
      answerKeys: [{ answerFormat: 'OPTION_KEYS', answerValue: 'B', answerJson: '["B"]' }],
      scoringRules: [{ ruleType: 'AUTO', ruleJson: '{"mode":"EXACT"}', sortOrder: 1 }],
    })
  })

  it('deduplicates multi-choice answers structurally and enforces two correct options', () => {
    const form = validBase('MULTI_CHOICE')
    expect(validateQuestionForm(form)).toContain('多选题至少需要两个正确选项')
    form.options[1]!.correct = true
    form.options.push({ content: '选项 C', correct: true })

    expect(validateQuestionForm(form)).toEqual([])
    const subQuestion = buildQuestionPayload(form).subQuestions[0]!
    expect(subQuestion.answerKeys[0]).toMatchObject({
      answerValue: 'A,B,C',
      answerJson: '["A","B","C"]',
    })
    expect(subQuestion.scoringRules).toEqual([
      { ruleType: 'AUTO', ruleJson: '{"mode":"SET_EQUAL"}', sortOrder: 1 },
    ])
  })

  it('builds judge without a meaningless option collection', () => {
    const form = validBase('JUDGE')
    form.judgeAnswer = false

    expect(validateQuestionForm(form)).toEqual([])
    expect(buildQuestionPayload(form).subQuestions[0]).toMatchObject({
      subQuestionType: 'JUDGE',
      answerFormat: 'BOOLEAN',
      options: [],
      answerKeys: [{ answerFormat: 'BOOLEAN', answerValue: 'false', answerJson: null }],
    })
  })

  it('closes case scoring points against every sub-question score', () => {
    const form = validBase('CASE')
    form.caseSubQuestions[0]!.scoringPoints[0]!.score = 4
    expect(validateQuestionForm(form)).toContain('案例子题 1评分点合计必须等于子题分值')
    form.caseSubQuestions[0]!.scoringPoints[0]!.score = 5

    expect(validateQuestionForm(form)).toEqual([])
    const payload = buildQuestionPayload(form)
    expect(payload.stems).toEqual([{ stemType: 'CASE_MATERIAL', content: '案例材料正文', sortOrder: 1 }])
    expect(payload.subQuestions).toHaveLength(2)
    expect(payload.subQuestions[1]).toMatchObject({
      subQuestionNo: 2,
      subQuestionType: 'SHORT_ANSWER',
      score: 5,
      gradingMode: 'MANUAL',
      scoringPoints: [{ pointCode: 'Q2P1', pointText: '评分点', score: 5, sortOrder: 1 }],
    })
  })

  it('persists essay word limits, required structure and manual rubric without AI results', () => {
    const form = validBase('ESSAY')
    expect(validateQuestionForm(form)).toEqual([])
    const payload = buildQuestionPayload(form)
    const sub = payload.subQuestions[0]!
    expect(payload.stems.map((item) => item.stemType)).toEqual(['MAIN_STEM', 'ESSAY_REQUIREMENT'])
    expect(JSON.parse(sub.metadataJson ?? '{}')).toEqual({
      minWords: 800,
      maxWords: 1200,
      requiredStructures: ['摘要', '正文', '结语'],
    })
    expect(sub.gradingMode).toBe('MANUAL')
    expect(sub.scoringPoints.reduce((sum, item) => sum + item.score, 0)).toBe(30)
    expect(JSON.stringify(payload)).not.toContain('AI_ASSISTED')
    expect(JSON.stringify(payload)).not.toContain('aiScore')
  })
})
