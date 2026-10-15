import { shallowMount } from '@vue/test-utils'
import type { Component } from 'vue'
import { describe, expect, it } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import { createAppRouter } from './index'

interface AsyncComponentModule {
  default: Component
}

const essaySlotRoutes = [
  { path: '/app/essay/editor', testId: 'essay-editor-slot', routeIdentity: 'essay.editor', name: 'EssayEditorSlotView' },
  { path: '/app/essay/reports', testId: 'essay-reports-slot', routeIdentity: 'essay.reports', name: 'EssayReportsSlotView' },
] as const

describe('workspace slot route ownership', () => {
  it('keeps the frozen essay slots independent after practice legacy routes redirect to their new owners', async () => {
    const router = createAppRouter(createMemoryHistory())
    const resolvedComponents: Component[] = []

    expect(typeof router.getRoutes().find((route) => route.path === '/app/practice/history')?.redirect).toBe('function')

    for (const expected of essaySlotRoutes) {
      const route = router.getRoutes().find((item) => item.path === expected.path)
      const loader = route?.components?.default
      expect(typeof loader).toBe('function')

      const loaded = await (loader as () => Promise<AsyncComponentModule>)()
      resolvedComponents.push(loaded.default)
      const wrapper = shallowMount(loaded.default)

      const slotRoot = wrapper.get(`[data-testid="${expected.testId}"]`)
      expect(slotRoot.attributes('data-route-identity')).toBe(expected.routeIdentity)
      expect((loaded.default as Component & { __name?: string }).__name).toBe(expected.name)
    }

    expect(new Set(resolvedComponents).size).toBe(essaySlotRoutes.length)
  })
})
