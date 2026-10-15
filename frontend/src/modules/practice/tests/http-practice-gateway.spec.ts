import { afterEach, describe, expect, it, vi } from 'vitest'
import { platformApiClient } from '@/api/client'
import { HttpPracticeGateway } from '../api/http-practice.gateway'

describe('HTTP practice gateway capability boundary', () => {
  afterEach(() => vi.restoreAllMocks())

  it('loads the published question catalog for the selected qualification', async () => {
    const get = vi.spyOn(platformApiClient, 'get').mockResolvedValue({ data: { success: true, data: {
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
      }],
    } } })

    await expect(new HttpPracticeGateway().getCatalog('1')).resolves.toMatchObject({
      qualificationId: '1', questionCount: 74,
    })
    expect(get).toHaveBeenCalledWith('/public/practice/catalog', { params: { qualificationId: '1' } })
  })

  it('sends the complete frozen create-attempt context instead of a legacy paperId-only body', async () => {
    const post = vi.spyOn(platformApiClient, 'post').mockResolvedValue({ data: { success: true, data: {
      attemptId: 42,
      title: '普通练习',
      qualificationId: 1,
      subjectId: 2,
      sourceSpec: { paperId: 3 },
      feedbackMode: 'WHOLE_PAPER',
      status: 'IN_PROGRESS',
      startedAt: '2026-07-17T00:00:00Z',
      version: 0,
      questions: [{
        questionId: 4,
        questionVersionId: 40,
        questionOrder: 1,
        questionType: 'SINGLE_CHOICE',
        settlementStatus: 'UNSETTLED',
        settlementVersion: 0,
        stems: [{ stemId: 5, sortOrder: 1, content: '题干' }],
        subQuestions: [{
          subQuestionId: 6,
          subQuestionType: 'SINGLE_CHOICE',
          answerFormat: 'OPTION',
          content: '请选择最佳答案',
          options: [{ optionKey: 'A', content: '选项 A', sortOrder: 1 }],
        }],
        knowledgeBindings: [],
      }],
    } } })
    const gateway = new HttpPracticeGateway()

    await gateway.createAttempt({
      qualificationId: '1',
      subjectId: '2',
      mode: 'PAST_EXAM',
      feedbackMode: 'WHOLE_PAPER',
      count: 1,
      source: { type: 'PAPER', paperId: '3' },
      clientMutationId: '70fd2ae0-4ccd-4f63-b47c-f2a348b568df',
    })
    expect(post).toHaveBeenCalledWith('/practice/attempts', {
      clientMutationId: '70fd2ae0-4ccd-4f63-b47c-f2a348b568df',
      qualificationId: 1,
      subjectId: 2,
      mode: 'PAST_EXAM',
      feedbackMode: 'WHOLE_PAPER',
      count: 1,
      source: { type: 'PAPER', paperId: 3 },
    })
  })

  it('sends history filters through the real query contract', async () => {
    const get = vi.spyOn(platformApiClient, 'get').mockResolvedValue({
      data: { success: true, data: { items: [], total: 0, pageNo: 1, pageSize: 20 } },
    })
    const gateway = new HttpPracticeGateway()

    await gateway.listAttemptHistory({
      qualificationId: '1', subjectId: '2', status: 'IN_PROGRESS', page: 1, pageSize: 20,
    })
    expect(get).toHaveBeenCalledWith('/practice/attempts', {
      params: { qualificationId: '1', subjectId: '2', status: 'IN_PROGRESS', pageNo: 1, pageSize: 20 },
    })
  })

  it('sends the exact authoritative PUT path and six-key body without subQuestionId', async () => {
    const put = vi.spyOn(platformApiClient, 'put').mockResolvedValue({
      data: { success: true, data: { answerVersion: 4, savedAt: '2026-07-13T00:00:00Z' } },
    })
    const gateway = new HttpPracticeGateway()
    const options = { baseVersion: 3, clientMutationId: 'mutation-1', usedSeconds: 12 }

    await gateway.saveAnswer('attempt-1', 'question-1', 'sub-1', { optionKey: ' a ' }, true, options)
    await gateway.saveAnswer('attempt-1', 'question-1', 'sub-1', { optionKey: ' a ' }, true, options)

    const expectedBody = {
      clientMutationId: 'mutation-1',
      baseVersion: 3,
      answerPayload: { optionKey: 'A' },
      usedSeconds: 12,
      marked: true,
    }
    expect(put).toHaveBeenNthCalledWith(1, '/practice/attempts/attempt-1/answers/sub-1', expectedBody)
    expect(put).toHaveBeenNthCalledWith(2, '/practice/attempts/attempt-1/answers/sub-1', expectedBody)
    expect(put.mock.calls[1]![1]).toEqual(put.mock.calls[0]![1])
    expect(Object.keys(put.mock.calls[0]![1] as object).sort()).toEqual(Object.keys(expectedBody).sort())
    expect(put.mock.calls[0]![1]).not.toHaveProperty('subQuestionId')
  })

  it('creates a published CASE attempt through the frozen case endpoint', async () => {
    const post = vi.spyOn(platformApiClient, 'post').mockResolvedValue({ data: { success: true, data: {
      attemptId: 42,
      title: '案例练习',
      qualificationId: 1,
      subjectId: 2,
      sourceSpec: {},
      feedbackMode: 'WHOLE_PAPER',
      status: 'IN_PROGRESS',
      startedAt: '2026-07-18T00:00:00Z',
      version: 0,
      questions: [{
        questionId: 9,
        questionVersionId: 90,
        questionOrder: 1,
        questionType: 'CASE',
        settlementStatus: 'UNSETTLED',
        settlementVersion: 0,
        stems: [{ stemId: 1, sortOrder: 1, content: '案例材料' }],
        subQuestions: [{
          subQuestionId: 10,
          subQuestionType: 'SHORT_ANSWER',
          answerFormat: 'TEXT',
          content: '请回答',
          options: [],
        }],
        knowledgeBindings: [],
      }],
    } } })

    await new HttpPracticeGateway().createCaseAttempt({
      qualificationId: '1',
      subjectId: '2',
      questionId: '9',
      clientMutationId: 'case-create-1',
    })

    expect(post).toHaveBeenCalledWith('/practice/case-attempts', {
      clientMutationId: 'case-create-1',
      qualificationId: 1,
      subjectId: 2,
      questionId: 9,
    })
  })

  it('loads submitted CASE reference from the authoritative explanation endpoint only', async () => {
    const get = vi.spyOn(platformApiClient, 'get').mockResolvedValueOnce({ data: { success: true, data: {
      attemptId: 42,
      questionId: 9,
      questionVersionId: 5,
      contentHash: 'hash-q',
      subQuestions: [{
        subQuestionId: 10,
        standardAnswer: { text: '参考答案' },
        solution: '解析',
        scoringPoints: [{ scoringPointId: 100, pointCode: 'P1', pointText: '要点一', score: 5, sortOrder: 0, contentHash: 'hash-p1' }],
      }],
    } } })
    const put = vi.spyOn(platformApiClient, 'put')
    const gateway = new HttpPracticeGateway()

    await expect(gateway.getExplanation('42', '9')).resolves.toMatchObject({
      attemptId: '42',
      subQuestions: [{ scoringPoints: [{ scoringPointId: '100', pointText: '要点一' }] }],
    })

    expect(get).toHaveBeenCalledWith('/practice/attempts/42/questions/9/explanation')
    expect(put).not.toHaveBeenCalled()
  })

  it('accepts the safe improving explanation state without requiring raw solution text', async () => {
    vi.spyOn(platformApiClient, 'get').mockResolvedValueOnce({ data: { success: true, data: {
      attemptId: 42,
      questionId: 9,
      status: 'IMPROVING',
      subQuestions: [{
        subQuestionId: 10,
        standardAnswer: { optionKey: 'A' },
        solution: null,
        solutionStatus: 'IMPROVING',
        scoringPoints: [],
      }],
    } } })

    await expect(new HttpPracticeGateway().getExplanation('42', '9')).resolves.toMatchObject({
      status: 'IMPROVING',
      subQuestions: [{ solution: undefined, solutionStatus: 'IMPROVING' }],
    })
  })

  it('loads mistake reason statistics for the learning tools summary', async () => {
    const get = vi.spyOn(platformApiClient, 'get').mockResolvedValueOnce({ data: { success: true, data: [
      { reasonCode: 'CONCEPT', count: 12 },
      { reasonCode: 'MISREADING', count: 7 },
    ] } })

    await expect(new HttpPracticeGateway().listMistakeReasonStatistics()).resolves.toEqual([
      { reasonCode: 'CONCEPT', count: 12 },
      { reasonCode: 'MISREADING', count: 7 },
    ])
    expect(get).toHaveBeenCalledWith('/practice/mistake-reasons/statistics', { suppressGlobalErrorRedirect: true })
  })
})
