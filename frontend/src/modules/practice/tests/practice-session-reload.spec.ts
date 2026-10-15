import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiClientError } from '@/api/client'
import { setPracticeGatewayForTests } from '../api/gateway'
import type { PracticeGateway } from '../api/practice.gateway'
import type { PracticeAnswerPayload, PracticeAttempt, PracticeSaveAnswerOptions } from '../types'

const push = vi.fn()
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { attemptId: 'attempt-1' }, query: {} }),
  useRouter: () => ({ push }),
}))

function attempt(answerVersion = 3, stem = 'stem', subQuestionContent = ''): PracticeAttempt {
  return {
    id: 'attempt-1', paperId: 'paper-1', title: 'Practice', qualificationName: 'Q', subjectName: 'S',
    createdAt: '2026-07-13T00:00:00Z', status: 'IN_PROGRESS', version: 0, feedbackMode: 'PER_QUESTION',
    questions: [{
      questionId: 'question-1', questionVersionId: 'question-version-1', subQuestionId: 'sub-1', number: 1, typeLabel: 'single',
      questionType: 'SINGLE_CHOICE', settlementStatus: 'UNSETTLED', settlementVersion: 0,
      knowledgePoint: 'point', stem, options: [{ code: 'A', content: 'a' }, { code: 'B', content: 'b' }],
      subQuestions: [{
        subQuestionId: 'sub-1', type: 'SINGLE_CHOICE', answerFormat: 'OPTION', content: subQuestionContent,
        options: [{ code: 'A', content: 'a' }, { code: 'B', content: 'b' }],
        answerVersion, marked: false,
      }],
    }],
    answers: {}, answerVersions: { 'sub-1': answerVersion }, markedQuestionIds: [], favoriteQuestionIds: [], notes: {},
  }
}

function settledAttempt(answerVersion = 8): PracticeAttempt {
  const value = attempt(answerVersion)
  value.questions[0]!.settlementStatus = 'SETTLED'
  value.questions[0]!.settlementVersion = 1
  value.answers['sub-1'] = 'B'
  value.questions[0]!.subQuestions[0]!.standardAnswer = ['B']
  value.questions[0]!.subQuestions[0]!.gradingStatus = 'CORRECT'
  value.questions[0]!.subQuestions[0]!.solution = '服务端权威解析'
  return value
}

function gateway(overrides: Partial<PracticeGateway>): PracticeGateway {
  return {
    getCatalog: vi.fn(), listPapers: vi.fn(), createAttempt: vi.fn(), createCaseAttempt: vi.fn(),
    getAttempt: vi.fn(), listAttemptHistory: vi.fn(), listMistakes: vi.fn(), saveAnswer: vi.fn(),
    settleQuestion: vi.fn(), submitAttempt: vi.fn(), getExplanation: vi.fn(),
    setFavorite: vi.fn(), saveNote: vi.fn(),
    ...overrides,
  } as PracticeGateway
}

describe('PracticeSessionView conflict reload recovery', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    push.mockReset()
  })

  it('renders an atomic choice stem once when the main stem and sub-question are identical', async () => {
    setPracticeGatewayForTests(gateway({
      getAttempt: vi.fn().mockResolvedValue(attempt(3, '信息系统生命周期的正确顺序是（ ）。', ' 信息系统生命周期的正确顺序是（ ）。 ')),
    }))
    const { default: View } = await import('../views/PracticeSessionView.vue')
    const wrapper = mount(View, {
      global: { stubs: {
        ElIcon: { template: '<i><slot /></i>' }, ElSkeleton: { template: '<div />' },
        ElButton: { emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>' },
        ElDropdown: { template: '<div><slot /></div>' }, ElDropdownMenu: { template: '<div><slot /></div>' },
        ElDropdownItem: { template: '<span><slot /></span>' }, ElDialog: { template: '<div><slot /><slot name="footer" /></div>' },
        ElInput: { template: '<textarea />' },
      } },
    })
    await flushPromises()

    expect(wrapper.findAll('.question-stem')).toHaveLength(1)
    expect(wrapper.get('.question-stem').text()).toBe('信息系统生命周期的正确顺序是（ ）。')
  })

  it('collapses a repeated main stem and its duplicate sub-question to one visible sentence', async () => {
    const sentence = '7、（ ）不是大数据的主要特征。'
    setPracticeGatewayForTests(gateway({
      getAttempt: vi.fn().mockResolvedValue(attempt(3, `${sentence} ${sentence}`, sentence)),
    }))
    const { default: View } = await import('../views/PracticeSessionView.vue')
    const wrapper = mount(View, {
      global: { stubs: {
        ElIcon: { template: '<i><slot /></i>' }, ElSkeleton: { template: '<div />' },
        ElButton: { emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>' },
        ElDropdown: { template: '<div><slot /></div>' }, ElDropdownMenu: { template: '<div><slot /></div>' },
        ElDropdownItem: { template: '<span><slot /></span>' }, ElDialog: { template: '<div><slot /><slot name="footer" /></div>' },
        ElInput: { template: '<textarea />' },
      } },
    })
    await flushPromises()

    expect(wrapper.findAll('.question-stem')).toHaveLength(1)
    expect(wrapper.get('.question-stem').text()).toBe(sentence)
  })

  it('retains latest intent after reload failure and rebuilds it once after a later successful restore', async () => {
    const getAttempt = vi.fn()
      .mockResolvedValueOnce(attempt(3))
      .mockRejectedValueOnce(new TypeError('restore failed'))
      .mockResolvedValueOnce(attempt(8))
    const saveCalls: Array<{ answer: PracticeAnswerPayload; options?: PracticeSaveAnswerOptions }> = []
    const saveAnswer = vi.fn(async (_attemptId, _questionId, _subQuestionId, answer, _marked, options) => {
      saveCalls.push({ answer, options })
      if (saveCalls.length === 1) throw new ApiClientError('conflict', 409, 'PRACTICE_ANSWER_VERSION_CONFLICT')
      return { answerVersion: 9, savedAt: '2026-07-13T00:00:00Z' }
    })
    setPracticeGatewayForTests(gateway({ getAttempt, saveAnswer }))
    const { default: View } = await import('../views/PracticeSessionView.vue')
    const wrapper = mount(View, {
      global: { stubs: {
        ElIcon: { template: '<i><slot /></i>' }, ElSkeleton: { template: '<div />' },
        ElButton: { emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>' },
        ElDropdown: { template: '<div><slot /></div>' }, ElDropdownMenu: { template: '<div><slot /></div>' },
        ElDropdownItem: { template: '<span><slot /></span>' }, ElDialog: { template: '<div><slot /><slot name="footer" /></div>' },
        ElInput: { template: '<textarea />' },
      } },
    })
    await flushPromises()

    await wrapper.get('[data-testid="practice-answer-A"]').trigger('click')
    await flushPromises()
    const conflictButton = () => wrapper.findAll('button').find((button) => button.text().includes('重新加载服务器版本'))
    expect(conflictButton()?.exists()).toBe(true)
    const firstId = saveCalls[0]!.options!.clientMutationId

    await conflictButton()!.trigger('click')
    await flushPromises()
    expect(saveCalls).toHaveLength(1)
    expect(conflictButton()?.exists()).toBe(true)

    await wrapper.get('[data-testid="practice-answer-B"]').trigger('click')
    await flushPromises()
    expect(saveCalls).toHaveLength(1)

    await conflictButton()!.trigger('click')
    await flushPromises()
    expect(saveCalls).toHaveLength(2)
    expect(saveCalls[1]).toMatchObject({ answer: { optionKey: 'B' }, options: { baseVersion: 8 } })
    expect(saveCalls[1]!.options!.clientMutationId).not.toBe(firstId)
    expect(wrapper.text()).toContain('已保存')
  })

  it('accepts a settled server answer and clears the conflict instead of replaying a stale save', async () => {
    const getAttempt = vi.fn()
      .mockResolvedValueOnce(attempt(3))
      .mockResolvedValueOnce(settledAttempt(8))
    const saveAnswer = vi.fn()
      .mockRejectedValueOnce(new ApiClientError('conflict', 409, 'PRACTICE_ANSWER_VERSION_CONFLICT'))
    setPracticeGatewayForTests(gateway({ getAttempt, saveAnswer }))
    const { default: View } = await import('../views/PracticeSessionView.vue')
    const wrapper = mount(View, {
      global: { stubs: {
        ElIcon: { template: '<i><slot /></i>' }, ElSkeleton: { template: '<div />' },
        ElButton: { emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>' },
        ElDropdown: { template: '<div><slot /></div>' }, ElDropdownMenu: { template: '<div><slot /></div>' },
        ElDropdownItem: { template: '<span><slot /></span>' }, ElDialog: { template: '<div><slot /><slot name="footer" /></div>' },
        ElInput: { template: '<textarea />' },
      } },
    })
    await flushPromises()

    await wrapper.get('[data-testid="practice-answer-A"]').trigger('click')
    await flushPromises()
    const reloadButton = () => wrapper.findAll('button').find((button) => button.text().includes('重新加载服务器版本'))
    expect(reloadButton()?.exists()).toBe(true)

    await reloadButton()!.trigger('click')
    await flushPromises()

    expect(saveAnswer).toHaveBeenCalledOnce()
    expect(reloadButton()).toBeUndefined()
    expect(wrapper.text()).not.toContain('版本冲突')
    expect(wrapper.text()).toContain('已保存')
    expect(wrapper.text()).toContain('已结算')
  })

  it('renders the authoritative server grading instead of recalculating string and array answers', async () => {
    setPracticeGatewayForTests(gateway({
      getAttempt: vi.fn().mockResolvedValue(settledAttempt()),
    }))
    const { default: View } = await import('../views/PracticeSessionView.vue')
    const wrapper = mount(View, {
      global: { stubs: {
        ElIcon: { template: '<i><slot /></i>' }, ElSkeleton: { template: '<div />' },
        ElButton: { emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>' },
        ElDropdown: { template: '<div><slot /></div>' }, ElDropdownMenu: { template: '<div><slot /></div>' },
        ElDropdownItem: { template: '<span><slot /></span>' }, ElDialog: { template: '<div><slot /><slot name="footer" /></div>' },
        ElInput: { template: '<textarea />' },
      } },
    })
    await flushPromises()

    expect(wrapper.get('[data-testid="question-analysis"]').text()).toContain('回答正确')
    expect(wrapper.get('[data-testid="question-analysis"]').text()).not.toContain('回答错误')
    expect(wrapper.get('[data-testid="question-analysis"]').text()).toContain('标准答案：B')
  })
})
