import { shallowMount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import type { Component } from 'vue'
import { describe, expect, it } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import { productNavigation } from '@/modules/platform/navigation'
import { createAppRouter } from './index'

interface AsyncComponentModule {
  default: Component
}

const expectedNavigation = [
  { code: 'practice.home', label: '题库目录', routePath: '/practice' },
  { code: 'practice.start', label: '刷题练习', routePath: '/practice' },
  { code: 'practice.exam', label: '模拟考试', routePath: '/exam-simulator' },
  { code: 'practice.review', label: '学习工具', routePath: '/tools' },
  { code: 'practice.reports', label: '练习历史', routePath: '/app/practice/history' },
  { code: 'practice.resources', label: '学习资源', routePath: '/app/practice/resources' },
] as const

const slotRoutes = [
  { path: '/app/practice/history', testId: 'practice-history-slot', routeIdentity: 'practice.history', name: 'PracticeHistorySlotView' },
] as const

describe('practice navigation completion', () => {
  it('freezes the six primary practice entries in the approved order', () => {
    expect(productNavigation.practice.items.map(({ code, label, routePath }) => ({ code, label, routePath }))).toEqual(expectedNavigation)
  })

  it('binds protected personal-learning routes to their own asynchronous slot identity', async () => {
    const router = createAppRouter(createMemoryHistory())
    const resolvedComponents: Component[] = []

    for (const expected of slotRoutes) {
      const route = router.getRoutes().find((item) => item.path === expected.path)
      const loader = route?.components?.default
      expect(typeof loader).toBe('function')

      const loaded = await (loader as () => Promise<AsyncComponentModule>)()
      resolvedComponents.push(loaded.default)
      const wrapper = shallowMount(loaded.default, {
        global: {
          plugins: [createPinia()],
          stubs: {
            ElEmpty: { template: '<div><slot /></div>' },
            ElButton: { template: '<button><slot /></button>' },
            ElIcon: { template: '<i><slot /></i>' },
            ElSelect: { template: '<select><slot /></select>' },
            ElOption: { template: '<option />' },
            ElPagination: { template: '<div />' },
            RouterLink: { template: '<a><slot /></a>' },
          },
        },
      })

      const root = wrapper.get(`[data-testid="${expected.testId}"]`)
      expect(root.attributes('data-route-identity')).toBe(expected.routeIdentity)
      expect((loaded.default as Component & { __name?: string }).__name).toBe(expected.name)
    }

    expect(new Set(resolvedComponents).size).toBe(slotRoutes.length)
  })

  it('redirects retired review and report addresses into learning tools', () => {
    const router = createAppRouter(createMemoryHistory())
    const review = router.getRoutes().find((item) => item.path === '/app/practice/review')
    const report = router.getRoutes().find((item) => item.path === '/app/practice/reports')

    expect(typeof review?.redirect).toBe('function')
    expect(typeof report?.redirect).toBe('function')
  })

  it('keeps simulated exams and daily attempts in their dedicated shells', () => {
    const router = createAppRouter(createMemoryHistory())

    expect(router.resolve('/exam-simulator').meta.appShell).toBe('exam')
    expect(router.resolve('/app/practice/attempts/attempt-1').meta.appShell).toBe('answer')
    expect(router.resolve('/app/practice/attempts/attempt-1').meta.examStage).toBeUndefined()
  })
})
