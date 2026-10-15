import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import { ElMessage } from 'element-plus'
import { examApiClient } from '@/api/client'
import ExamView from '../views/ExamView.vue'
import EntryView from '../views/EntryView.vue'
import { createMockCandidate, createMockPaper } from '../mock/scenario'
import { getExamSimulatorGateway, setExamSimulatorGatewayForTests } from '../api/gateway'
import { HttpExamSimulatorGateway } from '../api/http-exam-simulator.gateway'
import { useExamSimulatorStore } from '../stores/exam-simulator'
import type { ExamSimulatorGateway } from '../api/exam-simulator.gateway'
import type { MockInstance } from 'vitest'

const push = vi.fn()
const defaultGateway = getExamSimulatorGateway()
const unsupportedGatewayCall = async (): Promise<never> => {
  throw new Error('unsupported gateway call in this test')
}

function createGatewayStub(overrides: Partial<ExamSimulatorGateway>): ExamSimulatorGateway {
  return {
    login: unsupportedGatewayCall,
    getCandidate: unsupportedGatewayCall,
    confirmCandidate: unsupportedGatewayCall,
    getRules: unsupportedGatewayCall,
    acknowledgeRules: unsupportedGatewayCall,
    getState: unsupportedGatewayCall,
    enter: unsupportedGatewayCall,
    getPaper: unsupportedGatewayCall,
    submit: unsupportedGatewayCall,
    getDemoContext: () => null,
    ...overrides,
  }
}

function inProgressState() {
  return {
    sessionId: 'test-session',
    state: 'IN_PROGRESS' as const,
    serverNow: new Date().toISOString(),
    scheduledStartAt: new Date(Date.now() - 1_000).toISOString(),
    actualStartAt: new Date(Date.now() - 1_000).toISOString(),
    deadlineAt: new Date(Date.now() + 7_200_000).toISOString(),
    durationSeconds: 9_000,
    paperLoaded: true,
  }
}

function submittedState() {
  return {
    ...inProgressState(),
    state: 'SUBMITTED' as const,
    serverNow: '2026-07-14T07:30:00.000Z',
  }
}

function mountExam(): VueWrapper {
  const store = useExamSimulatorStore()
  store.sessionId = 'test-session'
  store.candidate = createMockCandidate('LINKED')
  store.paper = createMockPaper('LINKED')
  store.clockState = inProgressState()
  return mount(ExamView, {
    global: {
      plugins: [ElementPlus],
    },
  })
}

vi.mock('vue-router', () => ({
  useRouter: () => ({ push }),
}))

vi.mock('@/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/client')>()
  return {
    ...actual,
    examApiClient: {
      get: vi.fn(),
      post: vi.fn(),
    },
  }
})

describe('ExamView', () => {
  let wrapper: VueWrapper | undefined
  let successSpy: MockInstance<typeof ElMessage.success>
  let errorSpy: MockInstance<typeof ElMessage.error>
  let warningSpy: MockInstance<typeof ElMessage.warning>
  let infoSpy: MockInstance<typeof ElMessage.info>

  beforeEach(() => {
    sessionStorage.clear()
    push.mockReset()
    setActivePinia(createPinia())
    successSpy = vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never)
    errorSpy = vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
    warningSpy = vi.spyOn(ElMessage, 'warning').mockImplementation(() => undefined as never)
    infoSpy = vi.spyOn(ElMessage, 'info').mockImplementation(() => undefined as never)
  })

  afterEach(() => {
    wrapper?.unmount()
    setExamSimulatorGatewayForTests(defaultGateway)
    successSpy.mockRestore()
    errorSpy.mockRestore()
    warningSpy.mockRestore()
    infoSpy.mockRestore()
  })

  it('links answer, mark and question-number states', async () => {
    const store = useExamSimulatorStore()
    store.candidate = createMockCandidate('LINKED')
    store.paper = createMockPaper('LINKED')
    store.clockState = {
      sessionId: 'test-session',
      state: 'IN_PROGRESS',
      serverNow: new Date().toISOString(),
      scheduledStartAt: new Date(Date.now() - 1_000).toISOString(),
      actualStartAt: new Date(Date.now() - 1_000).toISOString(),
      deadlineAt: new Date(Date.now() + 7_200_000).toISOString(),
      durationSeconds: 9_000,
      paperLoaded: true,
    }

    wrapper = mount(ExamView, {
      global: {
        plugins: [ElementPlus],
      },
    })

    await wrapper.get('[data-testid="answer-B"] input').setValue(true)
    await flushPromises()
    expect(store.answers['20000']).toBe('B')

    await wrapper.get('[data-testid="toggle-mark"]').trigger('click')
    expect(store.marks).toContain(10_000)

    await wrapper.get('[data-testid="question-number-2"]').trigger('click')
    expect(store.currentQuestionIndex).toBe(1)
    expect(wrapper.get('[data-testid="question-number-1"]').classes()).toContain('question-number--answered')
    expect(wrapper.get('[data-testid="question-number-1"]').classes()).toContain('question-number--marked')
  })

  it('provides the subjective question workspace with prompt and editor controls', async () => {
    const store = useExamSimulatorStore()
    store.entryCode = 'ESSAY'
    store.candidate = createMockCandidate('ESSAY')
    store.paper = createMockPaper('ESSAY')
    store.clockState = {
      ...inProgressState(),
      workspaceType: 'ESSAY',
    }

    wrapper = mount(ExamView, {
      global: {
        plugins: [ElementPlus],
      },
    })

    expect(wrapper.find('[data-testid="subjective-workspace"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="essay-answer"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="prompt-font-increase"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="prompt-highlight"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="editor-align"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="editor-line-height"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="editor-superscript"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="editor-subscript"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="editor-formula"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="editor-history"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="editor-fullscreen"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="prompt-highlight"]').text()).toBe('强调显示')

    const editor = wrapper.get('[data-testid="essay-answer"]')
    editor.element.innerHTML = '<div>论文作答内容</div>'
    await editor.trigger('input')
    await flushPromises()
    expect(store.answers['40001']).toContain('论文作答内容')
  })

  it('does not expose a normal practice return while the exam is still in progress', async () => {
    const store = useExamSimulatorStore()
    store.candidate = createMockCandidate('LINKED')
    store.paper = createMockPaper('LINKED')
    store.clockState = {
      sessionId: 'test-session',
      state: 'IN_PROGRESS',
      serverNow: new Date().toISOString(),
      scheduledStartAt: new Date(Date.now() - 1_000).toISOString(),
      actualStartAt: new Date(Date.now() - 1_000).toISOString(),
      deadlineAt: new Date(Date.now() + 7_200_000).toISOString(),
      durationSeconds: 9_000,
      paperLoaded: true,
    }

    wrapper = mount(ExamView, {
      global: {
        plugins: [ElementPlus],
      },
    })

    expect(wrapper.find('[data-testid="exam-return-practice"]').exists()).toBe(false)
  })

  it('shows the practice return only after server-confirmed final submission', async () => {
    setExamSimulatorGatewayForTests(createGatewayStub({
      submit: async () => ({ state: 'SUBMITTED', submittedAt: '2026-07-14T07:30:00.000Z' }),
      getState: async () => ({
        sessionId: 'test-session',
        state: 'SUBMITTED',
        serverNow: '2026-07-14T07:30:00.000Z',
        scheduledStartAt: '2026-07-14T06:00:00.000Z',
        actualStartAt: '2026-07-14T06:00:00.000Z',
        deadlineAt: '2026-07-14T08:30:00.000Z',
        durationSeconds: 9_000,
        paperLoaded: true,
      }),
    }))
    const store = useExamSimulatorStore()
    store.sessionId = 'test-session'
    store.candidate = createMockCandidate('LINKED')
    store.paper = createMockPaper('LINKED')
    store.clockState = {
      sessionId: 'test-session',
      state: 'IN_PROGRESS',
      serverNow: new Date().toISOString(),
      scheduledStartAt: new Date(Date.now() - 1_000).toISOString(),
      actualStartAt: new Date(Date.now() - 1_000).toISOString(),
      deadlineAt: new Date(Date.now() + 7_200_000).toISOString(),
      durationSeconds: 9_000,
      paperLoaded: true,
    }

    wrapper = mount(ExamView, {
      global: {
        plugins: [ElementPlus],
      },
    })

    await wrapper.get('[data-testid="exam-submit"]').trigger('click')
    await flushPromises()

    expect(successSpy).toHaveBeenCalledOnce()
    expect(wrapper.get('[data-testid="exam-return-practice"]').attributes('href')).toBe('/practice')
  })

  it('keeps the practice return hidden when submission is rejected by the server', async () => {
    setExamSimulatorGatewayForTests(createGatewayStub({
      submit: async () => {
        throw Object.assign(new Error('交卷状态冲突'), { code: 'SUBMIT_CONFLICT' })
      },
    }))
    const store = useExamSimulatorStore()
    store.sessionId = 'test-session'
    store.candidate = createMockCandidate('LINKED')
    store.paper = createMockPaper('LINKED')
    store.clockState = {
      sessionId: 'test-session',
      state: 'IN_PROGRESS',
      serverNow: new Date().toISOString(),
      scheduledStartAt: new Date(Date.now() - 1_000).toISOString(),
      actualStartAt: new Date(Date.now() - 1_000).toISOString(),
      deadlineAt: new Date(Date.now() + 7_200_000).toISOString(),
      durationSeconds: 9_000,
      paperLoaded: true,
    }

    wrapper = mount(ExamView, {
      global: {
        plugins: [ElementPlus],
      },
    })

    await wrapper.get('[data-testid="exam-submit"]').trigger('click')
    await flushPromises()

    expect(store.submitStatus).toBe('conflict')
    expect(successSpy).not.toHaveBeenCalled()
    expect(warningSpy).toHaveBeenCalled()
    expect(wrapper.find('[data-testid="exam-return-practice"]').exists()).toBe(false)
  })

  it('keeps the practice return hidden when submit resolves to a non-final state', async () => {
    setExamSimulatorGatewayForTests(createGatewayStub({
      submit: async () => ({ state: 'PROCESSING', submittedAt: '2026-07-14T07:30:00.000Z' } as never),
      getState: async () => inProgressState(),
    }))
    const store = useExamSimulatorStore()
    wrapper = mountExam()

    await wrapper.get('[data-testid="exam-submit"]').trigger('click')
    await flushPromises()

    expect(store.submitStatus).toBe('failed')
    expect(successSpy).not.toHaveBeenCalled()
    expect(errorSpy).toHaveBeenCalled()
    expect(wrapper.find('[data-testid="exam-return-practice"]').exists()).toBe(false)
  })

  it('throws an explicit non-submitted result when authoritative state is not final', async () => {
    setExamSimulatorGatewayForTests(createGatewayStub({
      submit: async () => ({ state: 'PROCESSING', submittedAt: '2026-07-14T07:30:00.000Z' } as never),
      getState: async () => inProgressState(),
    }))
    const store = useExamSimulatorStore()
    store.sessionId = 'test-session'

    await expect(store.submitExam()).rejects.toMatchObject({
      code: 'SUBMIT_NOT_CONFIRMED',
      status: 'failed',
    })
  })

  it('does not show success when submit response is lost and authoritative state is not final', async () => {
    setExamSimulatorGatewayForTests(createGatewayStub({
      submit: async () => {
        throw Object.assign(new Error('network lost after submit'), { code: 'NETWORK_ERROR' })
      },
      getState: async () => inProgressState(),
    }))
    const store = useExamSimulatorStore()
    wrapper = mountExam()

    await wrapper.get('[data-testid="exam-submit"]').trigger('click')
    await flushPromises()

    expect(store.submitStatus).toBe('failed')
    expect(successSpy).not.toHaveBeenCalled()
    expect(errorSpy).toHaveBeenCalled()
    expect(wrapper.find('[data-testid="exam-return-practice"]').exists()).toBe(false)
  })

  it('recovers the practice return when submit response is lost but authoritative state is submitted', async () => {
    setExamSimulatorGatewayForTests(createGatewayStub({
      submit: async () => {
        throw Object.assign(new Error('network lost after submit'), { code: 'NETWORK_ERROR' })
      },
      getState: async () => submittedState(),
    }))
    const store = useExamSimulatorStore()
    wrapper = mountExam()

    await wrapper.get('[data-testid="exam-submit"]').trigger('click')
    await flushPromises()

    expect(store.submitStatus).toBe('submitted')
    expect(successSpy).toHaveBeenCalledOnce()
    expect(wrapper.get('[data-testid="exam-return-practice"]').attributes('href')).toBe('/practice')
  })

  it('recovers the practice return when already-submitted conflict is confirmed by authoritative state', async () => {
    setExamSimulatorGatewayForTests(createGatewayStub({
      submit: async () => {
        throw Object.assign(new Error('already submitted'), { code: 'EXAM_ALREADY_SUBMITTED' })
      },
      getState: async () => submittedState(),
    }))
    const store = useExamSimulatorStore()
    wrapper = mountExam()

    await wrapper.get('[data-testid="exam-submit"]').trigger('click')
    await flushPromises()

    expect(store.submitStatus).toBe('submitted')
    expect(successSpy).toHaveBeenCalledOnce()
    expect(wrapper.get('[data-testid="exam-return-practice"]').attributes('href')).toBe('/practice')
  })

  it('keeps the practice return hidden when a 409 conflict is not confirmed as submitted', async () => {
    setExamSimulatorGatewayForTests(createGatewayStub({
      submit: async () => {
        throw Object.assign(new Error('conflict'), { status: 409, code: 'SUBMIT_CONFLICT' })
      },
      getState: async () => inProgressState(),
    }))
    const store = useExamSimulatorStore()
    wrapper = mountExam()

    await wrapper.get('[data-testid="exam-submit"]').trigger('click')
    await flushPromises()

    expect(store.submitStatus).toBe('conflict')
    expect(successSpy).not.toHaveBeenCalled()
    expect(warningSpy).toHaveBeenCalled()
    expect(wrapper.find('[data-testid="exam-return-practice"]').exists()).toBe(false)
  })

  it('returns from the entry page to the practice workspace without clearing the exam session', async () => {
    const store = useExamSimulatorStore()
    store.sessionId = 'test-session'
    store.sessionToken = 'test-token'
    store.entryCode = 'LINKED'
    store.candidate = createMockCandidate('LINKED')
    store.persist()

    wrapper = mount(EntryView, {
      global: {
        plugins: [ElementPlus],
      },
    })

    await wrapper.get('[data-testid="entry-return-practice"]').trigger('click')

    expect(push).toHaveBeenCalledWith('/practice')
    expect(store.sessionId).toBe('test-session')
    expect(sessionStorage.getItem('exam-simulator-client-state-v1')).toContain('test-session')
  })
})

describe('HttpExamSimulatorGateway submit parsing', () => {
  afterEach(() => {
    vi.mocked(examApiClient.post).mockReset()
  })

  function mockSubmitPayload(data: unknown): void {
    vi.mocked(examApiClient.post).mockResolvedValue({
      data: {
        success: true,
        code: 'OK',
        message: 'ok',
        data,
        traceId: 'trace-1',
      },
    })
  }

  it('accepts only a submitted payload with a valid submittedAt timestamp', async () => {
    mockSubmitPayload({ state: 'SUBMITTED', submittedAt: '2026-07-14T07:30:00.000Z' })

    await expect(new HttpExamSimulatorGateway().submit('s-1')).resolves.toEqual({
      state: 'SUBMITTED',
      submittedAt: '2026-07-14T07:30:00.000Z',
    })
  })

  it.each([
    [{ state: 'PROCESSING', submittedAt: '2026-07-14T07:30:00.000Z' }, 'PROCESSING'],
    [{ state: 'IN_PROGRESS', submittedAt: '2026-07-14T07:30:00.000Z' }, 'IN_PROGRESS'],
    [{ state: 'SUBMITTED' }, 'missing submittedAt'],
    [{ state: 'SUBMITTED', submittedAt: 'not-a-date' }, 'invalid submittedAt'],
  ])('rejects non-final or malformed submit success payloads: %s', async (payload, _label) => {
    mockSubmitPayload(payload)

    await expect(new HttpExamSimulatorGateway().submit('s-1')).rejects.toMatchObject({
      code: 'INVALID_SUBMIT_RESULT',
    })
  })
})
