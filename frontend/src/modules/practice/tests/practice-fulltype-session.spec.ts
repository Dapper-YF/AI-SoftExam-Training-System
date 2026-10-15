import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { setPracticeGatewayForTests } from '../api/gateway'
import type { PracticeGateway } from '../api/practice.gateway'
import type { PracticeAttempt, PracticeQuestion } from '../types'

const push = vi.fn()
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { attemptId: 'attempt-fulltype' }, query: {} }),
  useRouter: () => ({ push }),
}))

function question(overrides: Partial<PracticeQuestion> = {}): PracticeQuestion {
  const { questionVersionId = 'case-version-1', ...remainingOverrides } = overrides
  return {
    questionId: 'case-q',
    questionVersionId,
    subQuestionId: 'case-sub-1',
    number: 1,
    typeLabel: '案例分析',
    questionType: 'CASE',
    knowledgePoint: '项目治理',
    stem: '某项目出现范围蔓延，请阅读材料后回答。',
    options: [],
    settlementStatus: 'UNSETTLED',
    settlementVersion: 0,
    subQuestions: [{
      subQuestionId: 'case-sub-1',
      type: 'SHORT_ANSWER',
      answerFormat: 'TEXT',
      content: '指出一个管理问题。',
      options: [],
      answerVersion: 0,
      marked: false,
      score: 5,
    }, {
      subQuestionId: 'case-sub-2',
      type: 'CALCULATION',
      answerFormat: 'NUMBER',
      content: '计算偏差值。',
      options: [],
      answerVersion: 0,
      marked: false,
      score: 5,
    }],
    ...remainingOverrides,
  }
}

function attempt(status: PracticeAttempt['status'] = 'IN_PROGRESS', q = question()): PracticeAttempt {
  return {
    id: 'attempt-fulltype',
    paperId: '',
    title: '案例分析练习',
    qualificationName: '1',
    subjectName: '2',
    createdAt: '2026-07-18T00:00:00Z',
    status,
    version: status === 'IN_PROGRESS' ? 1 : 2,
    feedbackMode: 'WHOLE_PAPER',
    questions: [q],
    answers: {},
    answerVersions: { 'case-sub-1': 0, 'case-sub-2': 0 },
    markedQuestionIds: [],
    favoriteQuestionIds: [],
    notes: {},
  }
}

function gateway(overrides: Partial<PracticeGateway> = {}): PracticeGateway {
  return {
    getCatalog: vi.fn(),
    listPapers: vi.fn(),
    createAttempt: vi.fn(),
    createCaseAttempt: vi.fn(),
    getAttempt: vi.fn().mockResolvedValue(attempt()),
    listAttemptHistory: vi.fn(),
    listMistakes: vi.fn(),
    saveAnswer: vi.fn().mockResolvedValue({ answerVersion: 1, savedAt: '2026-07-18T00:01:00Z' }),
    settleQuestion: vi.fn(),
    submitAttempt: vi.fn(),
    getExplanation: vi.fn(),
    setFavorite: vi.fn(),
    saveNote: vi.fn(),
    ...overrides,
  } as PracticeGateway
}

const global = {
  stubs: {
    ElIcon: { template: '<i><slot /></i>' },
    ElSkeleton: { template: '<div />' },
    ElButton: { props: ['disabled', 'loading'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>' },
    ElDropdown: { template: '<div><slot /></div>' },
    ElDropdownMenu: { template: '<div><slot /></div>' },
    ElDropdownItem: { template: '<span><slot /></span>' },
    ElDialog: { template: '<div><slot /><slot name="footer" /></div>' },
    ElInput: { props: ['modelValue'], emits: ['update:modelValue'], template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  },
}

describe('PracticeSessionView CASE and ESSAY real attempt flow', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    push.mockReset()
  })

  it('renders CASE materials, saves NUMBER answers, and hides scoring points before submit', async () => {
    const saveAnswer = vi.fn().mockResolvedValue({ answerVersion: 2, savedAt: '2026-07-18T00:02:00Z' })
    setPracticeGatewayForTests(gateway({ saveAnswer }))
    const { default: View } = await import('../views/PracticeSessionView.vue')
    const wrapper = mount(View, { global })
    await flushPromises()

    expect(wrapper.get('[data-testid="fulltype-material"]').text()).toContain('案例材料')
    expect(wrapper.text()).not.toContain('评分点自评')
    await wrapper.findAll('.mode-segmented button').find((button) => button.text().includes('子题 2'))!.trigger('click')
    await wrapper.get('[data-testid="number-answer-input"]').setValue('12.5')
    await wrapper.vm.$nextTick()
    await wrapper.findAll('button').find((button) => button.text().includes('保存当前子题'))!.trigger('click')
    await flushPromises()

    expect(saveAnswer).toHaveBeenCalledWith(
      'attempt-fulltype',
      'case-q',
      'case-sub-2',
      { text: '12.5' },
      false,
      expect.objectContaining({ baseVersion: 0 }),
    )
    expect(wrapper.find('[data-testid="scoring-points"]').exists()).toBe(false)
  })

  it('loads submitted CASE reference as read-only scoring points', async () => {
    const submitted = attempt('SUBMITTED', question({ settlementStatus: 'SETTLED' }))
    const getExplanation = vi.fn().mockResolvedValue({
      attemptId: 'attempt-fulltype',
      questionId: 'case-q',
      subQuestions: [{
        subQuestionId: 'case-sub-1',
        standardAnswer: '参考答案',
        solution: '案例解析',
        scoringPoints: [{ scoringPointId: '100', pointCode: 'P1', pointText: '识别范围蔓延', score: 5, sortOrder: 1 }],
      }],
    })
    setPracticeGatewayForTests(gateway({
      getAttempt: vi.fn().mockResolvedValue(submitted),
      getExplanation,
    }))
    const { default: View } = await import('../views/PracticeSessionView.vue')
    const wrapper = mount(View, { global })
    await flushPromises()

    expect(getExplanation).toHaveBeenCalledWith('attempt-fulltype', 'case-q')
    expect(wrapper.get('[data-testid="scoring-points"]').text()).toContain('识别范围蔓延')
    expect(wrapper.find('[data-testid="scoring-points"] button').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('自评选择本轮暂未开放持久化')
    expect(wrapper.text()).not.toContain('保存自评')
  })

  it('shows an improving placeholder for unsafe explanations without rendering raw template text', async () => {
    const submitted = attempt('SUBMITTED', question({ settlementStatus: 'SETTLED' }))
    setPracticeGatewayForTests(gateway({
      getAttempt: vi.fn().mockResolvedValue(submitted),
      getExplanation: vi.fn().mockResolvedValue({
        attemptId: 'attempt-fulltype',
        questionId: 'case-q',
        status: 'IMPROVING',
        subQuestions: [{
          subQuestionId: 'case-sub-1',
          standardAnswer: '参考答案',
          solutionStatus: 'IMPROVING',
          scoringPoints: [],
        }],
      }),
    }))
    const { default: View } = await import('../views/PracticeSessionView.vue')
    const wrapper = mount(View, { global })
    await flushPromises()

    expect(wrapper.text()).toContain('解析正在完善')
    expect(wrapper.text()).not.toContain('CHOICE_GENERIC')
    expect(wrapper.text()).not.toContain('frozen answer')
  })

  it('renders ESSAY word count before submit and reference structure after submit', async () => {
    const essayQuestion = question({
      questionId: 'essay-q',
      subQuestionId: 'essay-sub-1',
      typeLabel: '论文题',
      questionType: 'ESSAY',
      stem: '论信息系统项目的范围管理。',
      subQuestions: [{
        subQuestionId: 'essay-sub-1',
        type: 'ESSAY_WRITING',
        answerFormat: 'TEXT',
        content: '结合项目背景完成论文。',
        options: [],
        answerVersion: 0,
        marked: false,
        score: 75,
        metadata: { maxWords: 2500 },
      }],
    })
    setPracticeGatewayForTests(gateway({
      getAttempt: vi.fn().mockResolvedValue(attempt('SUBMITTED', { ...essayQuestion, settlementStatus: 'SETTLED' })),
      getExplanation: vi.fn().mockResolvedValue({
        attemptId: 'attempt-fulltype',
        questionId: 'essay-q',
        subQuestions: [{
          subQuestionId: 'essay-sub-1',
          standardAnswer: '参考结构',
          solution: '摘要、正文、结尾结构完整。',
          scoringPoints: [{ scoringPointId: '200', pointCode: 'STRUCTURE', pointText: '结构完整', score: 20, sortOrder: 1 }],
        }],
      }),
    }))
    const { default: View } = await import('../views/PracticeSessionView.vue')
    const wrapper = mount(View, { global })
    await flushPromises()

    expect(wrapper.get('[data-testid="fulltype-material"]').text()).toContain('论文背景与写作要求')
    expect(wrapper.text()).toContain('参考结构')
    expect(wrapper.text()).toContain('评分维度')
    expect(wrapper.text()).not.toContain('AI评分')
  })
})
