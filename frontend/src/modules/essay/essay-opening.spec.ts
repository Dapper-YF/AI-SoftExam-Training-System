import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import ModuleHomeView from '@/modules/platform/views/ModuleHomeView.vue'
import EssayWorkspaceHomeView from '@/modules/platform/views/EssayWorkspaceHomeView.vue'
import EssayEditorSlotView from '@/modules/platform/slots/EssayEditorSlotView.vue'
import EssayReportsSlotView from '@/modules/platform/slots/EssayReportsSlotView.vue'
import { setPlatformHomeGatewayForTests } from '@/modules/platform/api/gateway'
import { useAuthStore } from '@/stores/auth'
import { useQualificationStore } from '@/stores/qualification'

const essaySubject = {
  id: 'essay', qualificationId: 'qualification-high-ipmp', code: 'ESSAY', name: '论文',
  type: 'ESSAY' as const, status: 'ENABLED' as const,
}

function stubOptions() {
  return {
    global: {
      stubs: {
        ElIcon: { template: '<i><slot /></i>' },
        ElButton: { template: '<button><slot /></button>' },
        ElSkeleton: { template: '<span />' },
        ElProgress: { template: '<span />' },
        ElTooltip: { template: '<span><slot /></span>' },
      },
    },
  }
}

describe('essay opening product track', () => {
  beforeEach(() => {
    sessionStorage.clear()
    localStorage.clear()
    vi.unstubAllEnvs()
    setActivePinia(createPinia())
    setPlatformHomeGatewayForTests({
      getModuleHomeSnapshot: async () => ({ recentTasks: [], stats: [] }),
    })
  })

  it('enters essay from module home in DEV without a sessionStorage switch', async () => {
    const auth = useAuthStore()
    const qualification = useQualificationStore()
    auth.user = { id: 'student-1', username: 'student', displayName: '张同学', status: 'ENABLED' }
    auth.permissions = ['essay:workspace:view']
    auth.featureFlags = { 'essay.workspace': false }
    qualification.subjects = [essaySubject]

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/app/modules', component: ModuleHomeView },
        { path: '/app/essay/home', component: { template: '<main data-testid="essay-home-route" />' } },
      ],
    })
    await router.push('/app/modules')
    const wrapper = mount(ModuleHomeView, { ...stubOptions(), global: { ...stubOptions().global, plugins: [router] } })
    await flushPromises()

    expect(sessionStorage.getItem('platform-essay-dev-preview')).toBeNull()
    expect(wrapper.get('[data-testid="essay-product-entry"]').attributes('data-state')).toBe('preview')
    expect(wrapper.get('[data-testid="essay-entry-status"]').text()).toContain('开发预览')
    await wrapper.get('[data-testid="essay-product-entry"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/app/essay/home')
  })

  it.each([
    ['/app/essay/home', 'essay-home'],
    ['/app/essay/editor', 'essay-editor-slot'],
    ['/app/essay/reports', 'essay-reports-slot'],
  ])('renders the existing page at %s', async (path, testId) => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/app/essay/home', component: EssayWorkspaceHomeView },
        { path: '/app/essay/editor', component: EssayEditorSlotView },
        { path: '/app/essay/reports', component: EssayReportsSlotView },
      ],
    })
    await router.push(path)
    const wrapper = mount(RouterView, { ...stubOptions(), global: { ...stubOptions().global, plugins: [router] } })
    await flushPromises()
    await vi.waitFor(() => expect(wrapper.find(`[data-testid="${testId}"]`).exists()).toBe(true))
  })

  it('keeps Preview fixture data behind a dynamic-only gateway edge', () => {
    const gatewayPath = resolve('src/modules/essay/api/gateway.ts')
    const previewPath = resolve('src/modules/essay/api/preview-essay.gateway.ts')
    const gatewaySource = readFileSync(gatewayPath, 'utf8')
    const previewSource = readFileSync(previewPath, 'utf8')

    expect(gatewaySource).toContain("await import('./preview-essay.gateway')")
    expect(gatewaySource).not.toMatch(/^import .*preview-essay\.gateway/m)
    expect(previewSource).toContain("from '../preview-data'")
  })
})
