import { describe, expect, it } from 'vitest'
import { MockPracticeGateway } from '../api/mock-practice.gateway'

describe('mock practice gateway', () => {
  it('filters paper list and returns stable pagination metadata', async () => {
    const gateway = new MockPracticeGateway()

    const result = await gateway.listPapers({
      qualificationId: 'qualification-high-ipmp',
      subjectId: 'ipmp-knowledge',
      mode: 'PAST_EXAM',
      year: '2024',
      page: 1,
      pageSize: 2,
    })

    expect(result.items).toHaveLength(1)
    expect(result.total).toBe(1)
    expect(result.items.every((item) => item.title.includes('2024'))).toBe(true)
  })

  it('persists answers, marks, favorites and notes in the current mock attempt', async () => {
    const gateway = new MockPracticeGateway()
    const attempt = await gateway.createAttempt({
      qualificationId: 'qualification-high-ipmp',
      subjectId: 'ipmp-knowledge',
      mode: 'PAST_EXAM',
      feedbackMode: 'PER_QUESTION',
      count: 1,
      source: { type: 'PAPER', paperId: '1001' },
      clientMutationId: 'mock-attempt-1',
    })
    const question = attempt.questions[0]!

    await gateway.saveAnswer(attempt.id, question.questionId, question.subQuestionId, { optionKey: 'B' }, true)
    await gateway.setFavorite(question.questionId, true)
    await gateway.saveNote(question.questionId, 'WBS 底层应支持责任分配。')

    const restored = await gateway.getAttempt(attempt.id)
    expect(restored.answers[question.subQuestionId]).toBe('B')
    expect(restored.markedQuestionIds).toContain(question.questionId)
    expect(restored.favoriteQuestionIds).toContain(question.questionId)
    expect(restored.notes[question.questionId]).toBe('WBS 底层应支持责任分配。')
  })

  it('exposes the high-PM essay recall paper and starts a single manual-writing attempt', async () => {
    const gateway = new MockPracticeGateway()
    const papers = await gateway.listPapers({
      qualificationId: 'qualification-high-ipmp',
      subjectId: 'ipmp-essay',
      mode: 'PAST_EXAM',
      page: 1,
      pageSize: 10,
    })

    expect(papers.items).toHaveLength(1)
    expect(papers.items[0]?.title).toContain('2025年5月')

    const attempt = await gateway.createAttempt({
      qualificationId: 'qualification-high-ipmp',
      subjectId: 'ipmp-essay',
      mode: 'PAST_EXAM',
      feedbackMode: 'WHOLE_PAPER',
      count: 1,
      source: { type: 'PAPER', paperId: papers.items[0]!.id },
    })

    expect(attempt.questions).toHaveLength(1)
    expect(attempt.questions[0]?.questionType).toBe('ESSAY')
    expect(attempt.questions[0]?.subQuestions[0]?.type).toBe('ESSAY_WRITING')
  })
})
