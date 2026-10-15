import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiClientError } from '@/api/client'
import { setPlatformFoundationGatewayForTests } from '@/modules/foundation/api/gateway'
import type { PlatformFoundationGateway } from '@/modules/foundation/api/platform-foundation.gateway'
import type { Qualification, Subject } from '@/modules/foundation/types'
import { MockPlatformFoundationGateway } from '@/modules/foundation/api/mock-platform-foundation.gateway'
import { useAuthStore } from '@/stores/auth'
import { useQualificationStore } from '@/stores/qualification'
import ModuleHomeView from '@/modules/platform/views/ModuleHomeView.vue'
import PlatformHeader from './PlatformHeader.vue'
import PracticeScopeSelector from './PracticeScopeSelector.vue'

const qualifications: Qualification[] = [
  { id: 'q-advanced', code: 'HIGH_PM', name: '信息系统项目管理师', levelCode: 'ADVANCED', status: 'ENABLED' },
  { id: 'q-intermediate', code: 'MIDDLE_SD', name: '软件设计师', levelCode: 'INTERMEDIATE', status: 'ENABLED' },
]

const subjects: Subject[] = [
  { id: 's-comprehensive', qualificationId: 'q-advanced', code: 'COMPREHENSIVE', name: '综合知识', type: 'COMPREHENSIVE', status: 'ENABLED' },
  { id: 's-case', qualificationId: 'q-advanced', code: 'CASE', name: '案例分析', type: 'CASE', status: 'ENABLED' },
  { id: 's-essay', qualificationId: 'q-advanced', code: 'ESSAY', name: '论文', type: 'ESSAY', status: 'ENABLED' },
]

function gateway(overrides: Partial<PlatformFoundationGateway> = {}): PlatformFoundationGateway {
  return {
    listQualifications: async () => structuredClone(qualifications),
    listSubjects: async (qualificationId) => qualificationId === 'q-advanced' ? structuredClone(subjects) : [],
    listChapterNodes: async () => [],
    listSyllabusNodes: async () => [],
    ...overrides,
  }
}

function mountSelector() {
  const pinia = createPinia()
  setActivePinia(pinia)
  return mount(PracticeScopeSelector, { global: { plugins: [pinia, ElementPlus] } })
}

async function mountHeader(path: string) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/login', component: { template: '<div />' } },
      { path: '/register', component: { template: '<div />' } },
      { path: '/app/modules', component: { template: '<div />' } },
      { path: '/app/practice/home', component: { template: '<div />' } },
      { path: '/app/practice/attempts/:attemptId', name: 'practice-attempt', component: { template: '<div />' } },
      { path: '/app/essay/home', component: { template: '<div />' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  return mount(PlatformHeader, {
    props: { variant: 'student' },
    global: { plugins: [pinia, router, ElementPlus] },
  })
}

describe('PracticeScopeSelector', () => {
  beforeEach(() => {
    localStorage.clear()
    setPlatformFoundationGatewayForTests(gateway())
  })

  it('renders only level and qualification controls while keeping subject scope internal', async () => {
    const wrapper = mountSelector()
    await flushPromises()

    expect(wrapper.get('[data-testid="practice-scope-selector"]').attributes('aria-label')).toBe('刷题范围')
    expect(wrapper.text()).toContain('级别')
    expect(wrapper.text()).toContain('资格')
    expect(wrapper.text()).not.toContain('科目')
    expect(wrapper.text()).not.toContain('刷题范围已同步')
    expect(wrapper.findAllComponents({ name: 'ElSelect' })).toHaveLength(2)
    expect(wrapper.text()).toContain('信息系统项目管理师')
  })

  it('disables downstream controls while qualifications are loading', async () => {
    let resolveQualifications!: (value: Qualification[]) => void
    const pending = new Promise<Qualification[]>((resolve) => { resolveQualifications = resolve })
    setPlatformFoundationGatewayForTests(gateway({ listQualifications: () => pending }))

    const wrapper = mountSelector()
    await wrapper.vm.$nextTick()
    const selects = wrapper.findAllComponents({ name: 'ElSelect' })

    expect(selects[0]?.props('loading')).toBe(true)
    expect(selects[1]?.props('disabled')).toBe(true)
    resolveQualifications([])
    await flushPromises()
  })

  it('exposes the frozen V9 catalogue as 5 advanced, 11 intermediate, 4 primary and 45 internal subjects', async () => {
    const mock = new MockPlatformFoundationGateway()
    const items = await mock.listQualifications()
    const allSubjects = (await Promise.all(items.map((item) => mock.listSubjects(item.id)))).flat()

    expect(items.filter((item) => item.levelCode === 'ADVANCED')).toHaveLength(5)
    expect(items.filter((item) => item.levelCode === 'INTERMEDIATE')).toHaveLength(11)
    expect(items.filter((item) => item.levelCode === 'PRIMARY')).toHaveLength(4)
    expect(allSubjects).toHaveLength(45)
  })

  it('shows explicit empty and error states with an executable retry', async () => {
    setPlatformFoundationGatewayForTests(gateway({ listQualifications: async () => [] }))
    const emptyWrapper = mountSelector()
    await flushPromises()
    expect(emptyWrapper.get('[data-testid="practice-scope-empty"]').text()).toContain('暂无可用资格')

    setPlatformFoundationGatewayForTests(gateway({ listQualifications: async () => { throw new Error('资格服务不可用') } }))
    const errorWrapper = mountSelector()
    await flushPromises()
    expect(errorWrapper.get('[data-testid="practice-scope-error"]').text()).toContain('系统暂时不可用，请稍后重试')
    expect(errorWrapper.get('[data-testid="practice-scope-error"]').text()).not.toContain('资格服务不可用')
    expect(errorWrapper.get('[data-testid="practice-scope-retry"]').attributes('type')).toBe('button')
  })

  it('is not rendered by the global workspace header', async () => {
    const practiceHeader = await mountHeader('/app/practice/home')
    await flushPromises()
    expect(practiceHeader.find('[data-testid="practice-scope-selector"]').exists()).toBe(false)

    const essayHeader = await mountHeader('/app/essay/home')
    await flushPromises()
    expect(essayHeader.find('[data-testid="practice-scope-selector"]').exists()).toBe(false)
  })

  it('hides the global scope selector inside an immutable practice attempt', async () => {
    const attemptHeader = await mountHeader('/app/practice/attempts/attempt-1')
    await flushPromises()

    expect(attemptHeader.find('[data-testid="practice-scope-selector"]').exists()).toBe(false)
  })

  it('removes the brand tagline from the header', async () => {
    const header = await mountHeader('/app/practice/home')
    await flushPromises()

    expect(header.text()).not.toContain('专业 · 高效 · 智能')
  })

  it('keeps the workspace brand logo anchored to the new public home', async () => {
    const header = await mountHeader('/app/practice/home')
    const authStore = useAuthStore()
    authStore.user = { id: 'student-1', username: 'student', displayName: '学习用户', status: 'ENABLED' }

    await header.get('[data-testid="platform-brand-home"]').trigger('click')
    await flushPromises()

    expect(header.vm.$router.currentRoute.value.fullPath).toBe('/')
  })

  it('keeps anonymous qualification retries in the public-safe error mode', async () => {
    const listQualifications = vi.fn(async () => {
      throw new ApiClientError('登录状态已失效，请重新登录', 401, 'AUTH_SESSION_EXPIRED')
    })
    setPlatformFoundationGatewayForTests(gateway({ listQualifications }))
    const wrapper = mountSelector()
    await flushPromises()

    expect(wrapper.get('[data-testid="practice-scope-error"]').text()).toContain('公开资格与题库目录暂不可用')
    await wrapper.get('[data-testid="practice-scope-retry"]').trigger('click')
    await flushPromises()

    expect(listQualifications).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[data-testid="practice-scope-error"]').text()).toContain('公开资格与题库目录暂不可用')
    expect(wrapper.text()).not.toContain('登录状态已失效')
  })

  it('keeps the public module-home retry in anonymous mode', async () => {
    const listQualifications = vi.fn(async () => {
      throw new ApiClientError('登录状态已失效，请重新登录', 401, 'AUTH_SESSION_EXPIRED')
    })
    setPlatformFoundationGatewayForTests(gateway({ listQualifications }))
    const pinia = createPinia()
    setActivePinia(pinia)
    const qualificationStore = useQualificationStore()
    await qualificationStore.load({ anonymous: true })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/app/modules', component: { template: '<div />' } }],
    })
    await router.push('/app/modules')
    await router.isReady()
    const wrapper = mount(ModuleHomeView, { global: { plugins: [pinia, router, ElementPlus] } })
    await flushPromises()

    await wrapper.get('[data-testid="module-qualification-retry"]').trigger('click')
    await flushPromises()

    expect(listQualifications).toHaveBeenCalledTimes(2)
    expect(qualificationStore.errorMessage).toBe('公开资格与题库目录暂不可用，请稍后重试')
    expect(router.currentRoute.value.fullPath).toBe('/app/modules')
  })

  it('shows login and registration links instead of a synthetic user for anonymous visitors', async () => {
    const header = await mountHeader('/app/practice/home')
    await flushPromises()

    expect(header.get('[data-testid="platform-login-link"]').attributes('href')).toContain('/login')
    expect(header.get('[data-testid="platform-register-link"]').attributes('href')).toBe('/register')
    expect(header.find('[data-testid="platform-user-menu"]').exists()).toBe(false)
  })

  it('clears qualification scope and navigates to login even when server logout fails', async () => {
    const header = await mountHeader('/app/practice/home')
    const authStore = useAuthStore()
    const qualificationStore = useQualificationStore()
    qualificationStore.qualifications = structuredClone(qualifications)
    qualificationStore.selectedQualificationId = 'q-advanced'
    authStore.logout = vi.fn(async () => {
      authStore.clearSession()
      throw new Error('logout unavailable')
    })
    const replace = vi.spyOn(header.vm.$router, 'replace')

    await (header.vm as unknown as { logout: () => Promise<void> }).logout()

    expect(qualificationStore.qualifications).toEqual([])
    expect(qualificationStore.selectedQualificationId).toBeNull()
    expect(replace).toHaveBeenCalledWith('/login')
  })
})

describe.sequential('foundation gateway production gate', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    vi.unstubAllEnvs()
    vi.resetModules()
  })

  it.each([
    { dev: true, flag: 'true', expected: 'mock' },
    { dev: true, flag: undefined, expected: 'http' },
    { dev: true, flag: 'false', expected: 'http' },
    { dev: false, flag: 'true', expected: 'http' },
  ])('selects $expected when DEV=$dev and explicit flag=$flag', async ({ dev, flag, expected }) => {
    vi.unstubAllEnvs()
    vi.resetModules()
    vi.stubEnv('DEV', dev)
    vi.stubEnv('VITE_PLATFORM_MOCK', flag)

    const { getPlatformFoundationGateway } = await import('@/modules/foundation/api/gateway')
    const { HttpPlatformFoundationGateway } = await import('@/modules/foundation/api/http-platform-foundation.gateway')
    const { MockPlatformFoundationGateway } = await import('@/modules/foundation/api/mock-platform-foundation.gateway')
    const selected = getPlatformFoundationGateway()

    if (expected === 'mock') {
      expect(selected).toBeInstanceOf(MockPlatformFoundationGateway)
      expect(selected).not.toBeInstanceOf(HttpPlatformFoundationGateway)
    } else {
      expect(selected).toBeInstanceOf(HttpPlatformFoundationGateway)
      expect(selected).not.toBeInstanceOf(MockPlatformFoundationGateway)
    }
  }, 15_000)
})
