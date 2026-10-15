import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MockPracticeGateway } from '@/modules/practice/api/mock-practice.gateway'
import { setPracticeGatewayForTests } from '@/modules/practice/api/gateway'
import type { PracticeGateway } from '@/modules/practice/api/practice.gateway'
import type { PagedResult, PracticeAttempt, PracticeCreateAttemptInput, PracticePaperSummary } from '@/modules/practice/types'
import { usePracticeStore } from './practice'

interface Deferred<T> {
  promise: Promise<T>
  resolve: (value: T) => void
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

function paper(id: string, subjectId: string): PracticePaperSummary {
  return {
    id,
    title: id,
    qualificationId: 'qualification-high-ipmp',
    subjectId,
    paperType: 'REAL',
    yearNo: 2025,
    status: 'PUBLISHED',
  }
}

function createInput(paperId = '1001'): PracticeCreateAttemptInput {
  return {
    qualificationId: 'qualification-high-ipmp',
    subjectId: 'ipmp-knowledge',
    mode: 'PAST_EXAM',
    feedbackMode: 'PER_QUESTION',
    count: 6,
    source: { type: 'PAPER', paperId },
    clientMutationId: `mutation-${paperId}`,
  }
}

function restoredAttempt(id: string): PracticeAttempt {
  return {
    id,
    paperId: '',
    title: id,
    qualificationName: '信息系统项目管理师',
    subjectName: '综合知识',
    createdAt: '2026-07-17T00:00:00Z',
    status: 'IN_PROGRESS',
    version: 0,
    feedbackMode: 'PER_QUESTION',
    questions: [{
      questionId: `${id}-question`, questionVersionId: `${id}-question-version`, subQuestionId: `${id}-sub`, number: 1, typeLabel: '单选题',
      questionType: 'SINGLE_CHOICE', knowledgePoint: '项目管理', stem: id, options: [],
      settlementStatus: 'UNSETTLED', settlementVersion: 0,
      subQuestions: [{ subQuestionId: `${id}-sub`, type: 'SINGLE_CHOICE', answerFormat: 'OPTION', content: '', options: [], answerVersion: 0, marked: false }],
    }],
    answers: {}, answerVersions: {}, markedQuestionIds: [], favoriteQuestionIds: [], notes: {},
  }
}

describe('practice store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    sessionStorage.clear()
    setPracticeGatewayForTests(new MockPracticeGateway())
  })

  it('loads a paper page and keeps filter state when changing pages', async () => {
    const store = usePracticeStore()
    store.filters.keyword = '综合知识'
    store.pageSize = 2

    await store.loadPapers('qualification-high-ipmp', 'ipmp-knowledge')
    await store.changePage(2)

    expect(store.filters.keyword).toBe('综合知识')
    expect(store.page).toBe(2)
    expect(store.papers.length).toBeGreaterThan(0)
  })

  it('keeps only the latest subject result when an older list request resolves late', async () => {
    const oldResult = deferred<PagedResult<PracticePaperSummary>>()
    const latestResult = deferred<PagedResult<PracticePaperSummary>>()
    const listPapers = vi.fn()
      .mockReturnValueOnce(oldResult.promise)
      .mockReturnValueOnce(latestResult.promise)
    const gateway = new MockPracticeGateway() as PracticeGateway
    gateway.listPapers = listPapers
    setPracticeGatewayForTests(gateway)
    const store = usePracticeStore()

    const oldRequest = store.loadPapers('qualification-high-ipmp', 'subject-old')
    const latestRequest = store.loadPapers('qualification-high-ipmp', 'subject-latest')
    latestResult.resolve({ items: [paper('latest-paper', 'subject-latest')], total: 1, page: 1, pageSize: 6 })
    await latestRequest
    oldResult.resolve({ items: [paper('old-paper', 'subject-old')], total: 1, page: 1, pageSize: 6 })
    await oldRequest

    expect(listPapers.mock.calls.map(([query]) => query.subjectId)).toEqual(['subject-old', 'subject-latest'])
    expect(store.papers.map((item) => item.id)).toEqual(['latest-paper'])
  })

  it('keeps only the latest attempt when an older route request resolves late', async () => {
    const oldResult = deferred<PracticeAttempt>()
    const latestResult = deferred<PracticeAttempt>()
    const gateway = new MockPracticeGateway() as PracticeGateway
    gateway.getAttempt = vi.fn((id: string) => id === 'attempt-old' ? oldResult.promise : latestResult.promise)
    setPracticeGatewayForTests(gateway)
    const store = usePracticeStore()

    const oldRequest = store.restoreAttempt('attempt-old')
    const latestRequest = store.restoreAttempt('attempt-latest')
    latestResult.resolve(restoredAttempt('attempt-latest'))
    expect(await latestRequest).toBe(true)
    oldResult.resolve(restoredAttempt('attempt-old'))
    expect(await oldRequest).toBe(false)

    expect(store.attempt?.id).toBe('attempt-latest')
    expect(store.attemptStatus).toBe('success')
  })

  it('resets qualification-bound paper state and filters before a new scope loads', async () => {
    const store = usePracticeStore()
    await store.loadPapers('qualification-high-ipmp', 'ipmp-knowledge')
    store.page = 3
    store.filters.chapter = '第6章'
    store.filters.knowledgePoint = '范围规划'
    store.filters.year = '2025年上半年'
    store.filters.difficulty = 'HARD'
    store.filters.keyword = '旧资格'

    store.resetPaperScope()

    expect(store.papers).toEqual([])
    expect(store.total).toBe(0)
    expect(store.page).toBe(1)
    expect(store.listStatus).toBe('idle')
    expect(store.filters).toMatchObject({
      chapter: 'ALL',
      knowledgePoint: 'ALL',
      year: 'ALL',
      keyword: '',
    })
    expect(store.filters.difficulty).toBeUndefined()
  })

  it('submits an answer and synchronizes answered, current and marked states', async () => {
    const store = usePracticeStore()
    await store.startPaper(createInput())

    await store.answerCurrent('B')
    await store.toggleCurrentMarked()
    await store.goToQuestion(1)

    expect(store.questionState(0)).toBe('answered-marked')
    expect(store.questionState(1)).toBe('current')
    expect(store.currentQuestion?.number).toBe(2)
  })

  it('keeps analysis hidden until settlement and saves favorite and note state', async () => {
    const store = usePracticeStore()
    await store.startPaper(createInput())

    expect(store.canShowAnalysis).toBe(false)
    await store.answerCurrent('B')
    await store.toggleCurrentFavorite()
    await store.saveCurrentNote('范围管理复习笔记')

    expect(store.canShowAnalysis).toBe(false)
    expect(store.isCurrentFavorite).toBe(true)
    expect(store.currentNote).toBe('范围管理复习笔记')
  })
})
