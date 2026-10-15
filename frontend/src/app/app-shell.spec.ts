import { afterEach, describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import App from '@/App.vue'

async function mountAt(path: string) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/exam-simulator/login/:entryCode',
        component: { template: '<div data-testid="login-route" />' },
        meta: { appShell: 'exam', examStage: 'LOGIN' },
      },
      {
        path: '/exam-simulator/session/:sessionId/exam',
        component: { template: '<div data-testid="exam-route" />' },
        meta: { appShell: 'exam', examStage: 'EXAM' },
      },
    ],
  })
  router.push(path)
  await router.isReady()
  const wrapper = mount(App, { global: { plugins: [pinia, router] } })
  await flushPromises()
  return { wrapper, router }
}

describe('App exam shell classes', () => {
  afterEach(() => {
    document.body.classList.remove('exam-app-active')
    document.body.classList.remove('exam-app-desktop-stage')
  })

  it('does not force the 1180px desktop canvas on the exam login route', async () => {
    const { wrapper } = await mountAt('/exam-simulator/login/LINKED')

    expect(document.body.classList.contains('exam-app-active')).toBe(true)
    expect(document.body.classList.contains('exam-app-desktop-stage')).toBe(false)

    wrapper.unmount()
  })

  it('keeps the desktop exam canvas on the formal exam route', async () => {
    const { wrapper } = await mountAt('/exam-simulator/session/session-1/exam')

    expect(document.body.classList.contains('exam-app-active')).toBe(true)
    expect(document.body.classList.contains('exam-app-desktop-stage')).toBe(true)

    wrapper.unmount()
  })
})
