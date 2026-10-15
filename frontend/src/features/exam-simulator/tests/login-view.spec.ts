import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import LoginView from '../views/LoginView.vue'
import {
  MockExamSimulatorGateway,
  clearMockExamSimulatorState,
} from '../api/mock-exam-simulator.gateway'
import { setExamSimulatorGatewayForTests } from '../api/gateway'
import type { ExamSimulatorGateway } from '../api/exam-simulator.gateway'
import {
  SHARED_DEMO_ADMISSION_TICKET,
  SHARED_DEMO_IDENTITY_NUMBER,
} from '../config/shared-demo-credential'

const push = vi.hoisted(() => vi.fn())

vi.mock('vue-router', () => ({
  useRouter: () => ({ push }),
}))

function mountLoginView(): VueWrapper {
  return mount(LoginView, {
    props: { entryCode: 'LINKED' },
    global: {
      plugins: [ElementPlus],
    },
  })
}

async function setCredentials(wrapper: VueWrapper, admissionTicket: string, identityNumber: string): Promise<void> {
  await wrapper.get('[data-testid="admission-ticket-input"]').setValue(admissionTicket)
  await wrapper.get('[data-testid="identity-number-input"]').setValue(identityNumber)
}

async function submit(wrapper: VueWrapper): Promise<void> {
  await wrapper.get('[data-testid="login-submit"]').trigger('click')
  await flushPromises()
  await flushPromises()
}

function createHttpGateway(): ExamSimulatorGateway {
  return {
    login: async () => ({
      sessionId: 'http-session',
      sessionToken: 'http-token',
      state: 'CANDIDATE_LOGGED_IN',
    }),
    getCandidate: async () => ({
      maskedAdmissionTicket: '2026****0001',
      name: '\u6d4b\u8bd5\u8003\u751f',
      gender: '',
      qualification: '\u4fe1\u606f\u7cfb\u7edf\u9879\u76ee\u7ba1\u7406\u5e08',
      subject: '\u7efc\u5408\u77e5\u8bc6',
      maskedIdentityNumber: '110101********1234',
      stationNo: '01',
    }),
    confirmCandidate: async () => undefined,
    getRules: async () => ({
      version: '2026-H2-v1',
      region: '\u5f00\u53d1\u6d4b\u8bd5',
      examYear: 2026,
      requireScrollToEnd: false,
      tabs: [],
    }),
    acknowledgeRules: async () => undefined,
    getState: async () => ({
      sessionId: 'http-session',
      state: 'CANDIDATE_LOGGED_IN',
      serverNow: new Date().toISOString(),
      scheduledStartAt: new Date().toISOString(),
      actualStartAt: '',
      deadlineAt: new Date(Date.now() + 100_000).toISOString(),
      durationSeconds: 100,
      paperLoaded: false,
    }),
    enter: async () => ({
      state: 'IN_PROGRESS',
      enteredAt: new Date().toISOString(),
      deadlineAt: new Date(Date.now() + 100_000).toISOString(),
    }),
    getPaper: async () => {
      throw new Error('unused')
    },
    submit: async () => ({
      state: 'SUBMITTED',
      submittedAt: new Date().toISOString(),
    }),
    getDemoContext: () => null,
  }
}

describe('Exam simulator LoginView', () => {
  let wrapper: VueWrapper | undefined

  beforeEach(() => {
    sessionStorage.clear()
    push.mockReset()
    clearMockExamSimulatorState()
    setActivePinia(createPinia())
    setExamSimulatorGatewayForTests(new MockExamSimulatorGateway())
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
  })

  it('shows the shared simulated credential and does not ask for real identity data', () => {
    wrapper = mountLoginView()

    expect(wrapper.get('[data-testid="demo-credentials"]').text()).toContain('\u901a\u7528\u6a21\u62df\u51ed\u8bc1')
    expect(wrapper.get('[data-testid="demo-admission-ticket"]').text()).toBe(SHARED_DEMO_ADMISSION_TICKET)
    expect(wrapper.get('[data-testid="demo-identity-number"]').text()).toBe(SHARED_DEMO_IDENTITY_NUMBER)
    expect(wrapper.text()).not.toContain('\u771f\u5b9e\u8eab\u4efd\u8bc1')
    expect(wrapper.text()).not.toContain('\u8bf7\u8f93\u5165\u771f\u5b9e\u8eab\u4efd\u8bc1')
  })

  it('fills the shared credential without entering until the user submits', async () => {
    wrapper = mountLoginView()

    await wrapper.get('[data-testid="fill-demo-credential"]').trigger('click')
    await flushPromises()

    expect((wrapper.get('[data-testid="admission-ticket-input"]').element as HTMLInputElement).value).toBe(
      SHARED_DEMO_ADMISSION_TICKET,
    )
    expect((wrapper.get('[data-testid="identity-number-input"]').element as HTMLInputElement).value).toBe(
      SHARED_DEMO_IDENTITY_NUMBER,
    )
    expect(push).not.toHaveBeenCalled()

    await submit(wrapper)

    expect(push).toHaveBeenCalledWith({
      name: 'exam-candidate',
      params: { sessionId: expect.any(String) },
    })
  })

  it('resets the credential fields to empty values', async () => {
    wrapper = mountLoginView()

    await wrapper.get('[data-testid="fill-demo-credential"]').trigger('click')
    await wrapper.get('[data-testid="login-reset"]').trigger('click')

    expect((wrapper.get('[data-testid="admission-ticket-input"]').element as HTMLInputElement).value).toBe('')
    expect((wrapper.get('[data-testid="identity-number-input"]').element as HTMLInputElement).value).toBe('')
  })

  it('keeps the login controls available in a 390px viewport structure', () => {
    window.innerWidth = 390
    wrapper = mountLoginView()

    const panel = wrapper.get('.login-panel')
    const demo = wrapper.get('[data-testid="demo-credentials"]')
    const actions = wrapper.get('.login-panel__actions')

    expect(panel.attributes('style') ?? '').not.toContain('1180')
    expect(panel.classes()).toContain('login-panel')
    expect(demo.classes()).toContain('login-panel__demo')
    expect(wrapper.find('[data-testid="fill-demo-credential"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="admission-ticket-input"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="identity-number-input"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="login-submit"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="login-reset"]').exists()).toBe(true)
    expect(actions.classes()).toContain('login-panel__actions')
  })

  it.each([
    ['short credential', '111', SHARED_DEMO_IDENTITY_NUMBER],
    ['non-digit credential', '11111111111x', SHARED_DEMO_IDENTITY_NUMBER],
    ['other valid-length values', '222222222222', '222222222222222222'],
  ])('rejects %s', async (_label, admissionTicket, identityNumber) => {
    wrapper = mountLoginView()

    await setCredentials(wrapper, admissionTicket, identityNumber)
    await submit(wrapper)

    expect(push).not.toHaveBeenCalled()
  })

  it('accepts real credential format in HTTP mode without showing demo credentials', async () => {
    setExamSimulatorGatewayForTests(createHttpGateway())
    wrapper = mountLoginView()

    expect(wrapper.find('[data-testid="demo-credentials"]').exists()).toBe(false)
    await setCredentials(wrapper, '202600000001', '110101199001011234')
    await submit(wrapper)

    expect(push).toHaveBeenCalledWith({
      name: 'exam-candidate',
      params: { sessionId: 'http-session' },
    })
  })

  it('keeps credential format validation in HTTP mode', async () => {
    setExamSimulatorGatewayForTests(createHttpGateway())
    wrapper = mountLoginView()

    await setCredentials(wrapper, '123', SHARED_DEMO_IDENTITY_NUMBER)
    await submit(wrapper)

    expect(push).not.toHaveBeenCalled()
  })
})
