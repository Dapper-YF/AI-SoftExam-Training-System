import { describe, expect, it, vi } from 'vitest'
import { platformApiClient } from '@/api/client'
import { HttpPracticeGateway } from '../api/http-practice.gateway'
import {
  buildHistoryParams,
  buildListParams,
  buildNoteBody,
  buildSaveAnswerBody,
  adaptPracticeAttempt,
  adaptPracticeCatalog,
  adaptPracticeHistory,
  adaptPracticePaperPage,
  resolvePracticeGatewayKind,
} from '../api/practice-contract-adapter'

describe('practice HTTP contract adapter', () => {
  it('defaults to HTTP and enables Mock only through an explicit practice or platform switch', () => {
    expect(resolvePracticeGatewayKind(undefined, undefined)).toBe('http')
    expect(resolvePracticeGatewayKind('false', 'false')).toBe('http')
    expect(resolvePracticeGatewayKind('true', undefined)).toBe('mock')
    expect(resolvePracticeGatewayKind(undefined, 'true')).toBe('mock')
  })

  it('maps frontend pagination to the frozen pageNo contract', () => {
    expect(buildListParams({
      qualificationId: 'qualification-1',
      subjectId: 'subject-45',
      mode: 'PAST_EXAM',
      page: 3,
      pageSize: 20,
    })).toEqual({
      qualificationId: 'qualification-1',
      subjectId: 'subject-45',
      pageNo: 3,
      pageSize: 20,
      paperType: 'REAL',
    })
    expect(buildHistoryParams(2, 10)).toEqual({ pageNo: 2, pageSize: 10 })
  })

  it('sends the server-confirmed subjectId through the real HTTP gateway without a legacy subject type', async () => {
    const get = vi.spyOn(platformApiClient, 'get').mockResolvedValue({
      data: {
        success: true,
        code: 'OK',
        message: 'success',
        traceId: 'trace-practice-list',
        data: { items: [], pageNo: 1, pageSize: 6, hasNext: false },
      },
    })

    await new HttpPracticeGateway().listPapers({
      qualificationId: 'qualification-1',
      subjectId: 'subject-45',
      mode: 'PAST_EXAM',
      page: 1,
      pageSize: 6,
    })

    expect(get).toHaveBeenCalledOnce()
    expect(get).toHaveBeenCalledWith('/public/papers', {
      params: {
        qualificationId: 'qualification-1',
        subjectId: 'subject-45',
        paperType: 'REAL',
        pageNo: 1,
        pageSize: 6,
      },
    })
    expect(get.mock.calls[0]?.[1]?.params).not.toHaveProperty('subjectType')
  })

  it('adapts the public paper page without accepting inventory totals', () => {
    expect(adaptPracticePaperPage({
      items: [{
        paperId: 10,
        qualificationId: 1,
        subjectId: 2,
        paperName: '2026 模拟卷',
        paperType: 'MOCK',
        yearNo: 2026,
        durationMinutes: 120,
        status: 'PUBLISHED',
      }],
      pageNo: 1,
      pageSize: 6,
      hasNext: false,
    })).toMatchObject({
      items: [{ id: '10', title: '2026 模拟卷', qualificationId: '1', subjectId: '2' }],
      page: 1,
      pageSize: 6,
    })

    expect(() => adaptPracticePaperPage({
      items: [],
      total: 0,
      pageNo: 1,
      pageSize: 6,
      hasNext: false,
    })).toThrow(/库存|汇总字段|total/)
    expect(() => adaptPracticePaperPage({
      items: [{ paperId: 10, qualificationId: 1, paperName: '卷', paperType: 'MOCK', totalScore: 75, status: 'PUBLISHED' }],
      pageNo: 1,
      pageSize: 6,
      hasNext: false,
    })).toThrow(/库存|汇总字段|totalScore/)
  })

  it('builds the frozen save-answer union without legacy answer fields', () => {
    const body = buildSaveAnswerBody({
      answerPayload: { optionKey: 'b' },
      marked: true,
      usedSeconds: 0,
      baseVersion: 4,
      clientMutationId: '70fd2ae0-4ccd-4f63-b47c-f2a348b568df',
    })

    expect(body).toEqual({
      answerPayload: { optionKey: 'B' },
      usedSeconds: 0,
      marked: true,
      baseVersion: 4,
      clientMutationId: '70fd2ae0-4ccd-4f63-b47c-f2a348b568df',
    })
    expect(body).not.toHaveProperty('answerContent')
    expect(body).not.toHaveProperty('answerJson')
  })

  it('maps note content to noteContent', () => {
    expect(buildNoteBody('question-1', '复习 WBS')).toEqual({
      targetType: 'QUESTION',
      targetId: 'question-1',
      noteContent: '复习 WBS',
    })
  })

  it('adapts the real practice catalog with subject and question-type counts', () => {
    expect(adaptPracticeCatalog({
      qualificationId: 1,
      available: true,
      questionCount: 74,
      paperCount: 0,
      paperYears: [],
      subjects: [{
        subjectId: 11,
        subjectName: '综合知识',
        subjectType: 'COMPREHENSIVE',
        available: true,
        questionCount: 71,
        questionTypes: [{ questionType: 'SINGLE_CHOICE', count: 71 }],
      }, {
        subjectId: 12,
        subjectName: '案例分析',
        subjectType: 'CASE',
        available: true,
        questionCount: 3,
        questionTypes: [{ questionType: 'CASE', count: 3 }],
      }],
    })).toMatchObject({
      qualificationId: '1',
      available: true,
      questionCount: 74,
      subjects: [{ subjectId: '11', questionCount: 71 }, { subjectId: '12', questionCount: 3 }],
    })
  })

  it('adapts the frozen attempt DTO without exposing analysis while in progress', () => {
    const attempt = adaptPracticeAttempt({
      attemptId: '42', title: '普通练习', status: 'IN_PROGRESS', qualificationId: '1', subjectId: '2',
      startedAt: '2026-07-12T10:00:00Z', version: 3, feedbackMode: 'PER_QUESTION', sourceSpec: {},
      questions: [{
        questionOrder: 1, questionId: '101', questionVersionId: '1001', questionType: 'SINGLE_CHOICE',
        settlementStatus: 'UNSETTLED', settlementVersion: 0,
        stems: [
          { stemId: '201', sortOrder: 1, content: '题干' },
          { stemId: '202', sortOrder: 2, content: '  题干  ' },
        ],
        knowledgeBindings: [{ knowledgeType: 'CHAPTER', knowledgeId: '9', knowledgeName: '范围管理' }],
        subQuestions: [{
          subQuestionId: '301', subQuestionType: 'SINGLE_CHOICE', answerFormat: 'OPTION', content: '请选择最佳答案',
          options: [{ optionKey: 'A', content: '选项 A', sortOrder: 1 }],
          answer: { answerPayload: { optionKey: 'A' }, answerVersion: 2, marked: true },
        }],
      }],
    })

    expect(attempt.id).toBe('42')
    expect(attempt.answers).toEqual({ '301': 'A' })
    expect(attempt.answerVersions).toEqual({ '301': 2 })
    expect(attempt.markedQuestionIds).toEqual(['101'])
    expect(attempt.questions[0]?.stem).toBe('题干')
    expect(attempt.questions[0]?.subQuestions[0]?.content).toBe('请选择最佳答案')
    expect(attempt.questions[0]?.typeLabel).toBe('单选题')
    expect(attempt.questions[0]).not.toHaveProperty('correctAnswer')
    expect(attempt.questions[0]).not.toHaveProperty('explanation')
  })

  it('rejects pre-submit CASE or ESSAY attempts that leak scoring points', () => {
    expect(() => adaptPracticeAttempt({
      attemptId: '42', title: '案例练习', status: 'IN_PROGRESS', qualificationId: '1', subjectId: '2',
      startedAt: '2026-07-18T10:00:00Z', version: 3, feedbackMode: 'WHOLE_PAPER', sourceSpec: {},
      questions: [{
        questionOrder: 1, questionId: '101', questionVersionId: '1001', questionType: 'CASE',
        settlementStatus: 'UNSETTLED', settlementVersion: 0,
        stems: [{ stemId: '201', sortOrder: 1, content: '案例材料' }],
        knowledgeBindings: [],
        subQuestions: [{
          subQuestionId: '301', subQuestionType: 'SHORT_ANSWER', answerFormat: 'TEXT', content: '请回答',
          options: [],
          scoringPoints: [{ scoringPointId: 'p1', pointCode: 'P1', pointText: '泄漏要点', score: 5, sortOrder: 1 }],
        }],
      }],
    })).toThrow(/不得返回标准答案、分数或解析/)
  })

  it('rejects detail DTOs without a frozen question version', () => {
    const detail = validDetailQuestion()
    delete detail.questionVersionId

    expect(() => adaptPracticeAttempt(validAttempt(detail))).toThrow(/questionVersionId/)
  })

  it('rejects detail DTOs without a valid answer format', () => {
    const missing = validDetailQuestion()
    delete missing.subQuestions[0].answerFormat
    expect(() => adaptPracticeAttempt(validAttempt(missing))).toThrow(/answerFormat/)

    for (const answerFormat of [null, '', 'LEGACY_OPTION']) {
      const invalid = validDetailQuestion()
      invalid.subQuestions[0].answerFormat = answerFormat
      expect(() => adaptPracticeAttempt(validAttempt(invalid))).toThrow(/answerFormat/)
    }
  })

  it('preserves the authoritative CASE and ESSAY version and answer formats', () => {
    const caseQuestion = validDetailQuestion({
      questionId: '101', questionVersionId: '2001', questionType: 'CASE',
      subQuestions: [{
        subQuestionId: '301', subQuestionType: 'SHORT_ANSWER', answerFormat: 'TEXT',
        content: '请分析风险', options: [],
      }],
    })
    const essayQuestion = validDetailQuestion({
      questionId: '102', questionVersionId: '3001', questionType: 'ESSAY',
      subQuestions: [{
        subQuestionId: '302', subQuestionType: 'ESSAY_WRITING', answerFormat: 'JSON',
        content: '请撰写论文', options: [],
      }],
    })

    const attempt = adaptPracticeAttempt(validAttempt(caseQuestion, essayQuestion))

    expect(attempt.questions.map((question) => question.questionVersionId)).toEqual([
      '2001', '3001',
    ])
    expect(attempt.questions.map((question) => question.subQuestions[0]?.answerFormat)).toEqual(['TEXT', 'JSON'])
  })

  it('adapts history pageNo and only documented attempt fields', () => {
    expect(adaptPracticeHistory({
      pageNo: 2, pageSize: 10, total: 1,
      items: [{ attemptId: '42', status: 'SUBMITTED', sourceType: 'PAPER', questionCount: 75, startedAt: '2026-07-12T10:00:00Z', submittedAt: '2026-07-12T10:30:00Z', durationSeconds: 1800, totalScore: 60, maxScore: 75 }],
    })).toEqual({
      page: 2, pageSize: 10, total: 1,
      items: [{ id: '42', status: 'SUBMITTED', sourceType: 'PAPER', sourceSpec: {}, questionCount: 75, startedAt: '2026-07-12T10:00:00Z', submittedAt: '2026-07-12T10:30:00Z', durationSeconds: 1800, totalScore: 60, maxScore: 75 }],
    })
  })

})

function validAttempt(...questions: Record<string, unknown>[]): Record<string, unknown> {
  return {
    attemptId: '1', title: '合同练习', status: 'IN_PROGRESS', qualificationId: '1', subjectId: '2',
    startedAt: '2026-07-18T10:00:00Z', version: 1, feedbackMode: 'WHOLE_PAPER', sourceSpec: {}, questions,
  }
}

function validDetailQuestion(overrides: Record<string, unknown> = {}): Record<string, any> {
  return {
    questionOrder: 1, questionId: '101', questionVersionId: '1001', questionType: 'SINGLE_CHOICE',
    settlementStatus: 'UNSETTLED', settlementVersion: 0,
    stems: [{ stemId: '201', sortOrder: 1, content: '题干' }], knowledgeBindings: [],
    subQuestions: [{
      subQuestionId: '301', subQuestionType: 'SINGLE_CHOICE', answerFormat: 'OPTION', content: '请选择',
      options: [{ optionKey: 'A', content: 'A', sortOrder: 1 }],
    }],
    ...overrides,
  }
}
