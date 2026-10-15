import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import PracticeWorkspaceHomeView from '@/modules/platform/views/PracticeWorkspaceHomeView.vue'
import PracticeHistorySlotView from '@/modules/platform/slots/PracticeHistorySlotView.vue'
import PracticeListView from '@/modules/practice/views/PracticeListView.vue'
import { setPlatformFoundationGatewayForTests } from '@/modules/foundation/api/gateway'
import type { PlatformFoundationGateway } from '@/modules/foundation/api/platform-foundation.gateway'
import { setPracticeGatewayForTests } from '@/modules/practice/api/gateway'
import type { PracticeGateway } from '@/modules/practice/api/practice.gateway'
import { setPlatformHomeGatewayForTests } from '@/modules/platform/api/gateway'
import { useQualificationStore } from '@/stores/qualification'
import { useAuthStore } from '@/stores/auth'

const global = {
  stubs: {
    RouterLink: { template: '<a><slot /></a>' },
    ElIcon: { template: '<i><slot /></i>' },
    ElButton: { template: '<button><slot /></button>' },
    ElSkeleton: { template: '<div />' },
  },
}

describe('practice workspace pages', () => {
  beforeEach(() => {
    localStorage.clear()
    setPlatformHomeGatewayForTests({
      getModuleHomeSnapshot: vi.fn().mockResolvedValue({ recentTasks: [], stats: [] }),
    })
  })

  function authenticateStudent(): void {
    const authStore = useAuthStore()
    authStore.user = { id: 'student-1', username: 'student', displayName: 'student', status: 'ENABLED' }
    authStore.roles = ['STUDENT']
    authStore.permissions = ['practice:workspace:view']
  }

  it('shows practice availability without exposing inventory counts and retains personal learning stats', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authenticateStudent()
    const qualificationStore = useQualificationStore()
    qualificationStore.qualifications = [
      { id: 'q-advanced', code: 'HIGH_PM', name: '信息系统项目管理师', levelCode: 'ADVANCED', status: 'ENABLED', available: true },
    ]
    qualificationStore.selectedLevelCode = 'ADVANCED'
    qualificationStore.selectedQualificationId = 'q-advanced'
    qualificationStore.subjects = [
      { id: 's-advanced', qualificationId: 'q-advanced', code: 'COMPREHENSIVE_KNOWLEDGE', name: '综合知识', type: 'COMPREHENSIVE', status: 'ENABLED', sortOrder: 1 },
    ]
    qualificationStore.selectedSubjectId = 's-advanced'
    qualificationStore.status = 'success'
    qualificationStore.subjectStatus = 'success'
    qualificationStore.ruleStatus = 'success'
    qualificationStore.activeExamRule = { qualificationId: 'q-advanced' } as never
    setPracticeGatewayForTests({
      getCatalog: vi.fn().mockResolvedValue({
        qualificationId: 'q-advanced', available: true, questionCount: 71, paperCount: 0, paperYears: [],
        subjects: [{ subjectId: 's-advanced', subjectName: '综合知识', subjectType: 'COMPREHENSIVE', available: true, questionCount: 71, questionTypes: [{ questionType: 'SINGLE_CHOICE', count: 71 }] }],
      }),
      listPapers: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 6 }),
      listAttemptHistory: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 1 }),
    } as unknown as PracticeGateway)
    setPlatformHomeGatewayForTests({
      getModuleHomeSnapshot: vi.fn().mockResolvedValue({
        recentTasks: [],
        stats: [
          { id: 'practice-count', value: '8' },
          { id: 'mistake-count', value: '5' },
          { id: 'favorite-count', value: '3' },
        ],
      }),
    })
    const wrapper = mount(PracticeWorkspaceHomeView, { global: { ...global, plugins: [pinia] } })
    await flushPromises()

    expect(wrapper.find('[data-testid="practice-home-page"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('开始今天的刷题')
    expect(wrapper.text()).toContain('每日练习')
    expect(wrapper.text()).toContain('章节练习')
    expect(wrapper.text()).toContain('知识点专项')
    expect(wrapper.text()).toContain('学习概览')
    expect(wrapper.text()).toContain('科目专项')
    expect(wrapper.text()).toContain('专项练习已开放')
    expect(wrapper.text()).toContain('完整年份试卷整理中，可先进行专项练习。')
    expect(wrapper.text()).toContain('专项刷题')
    expect(wrapper.text()).toContain('8')
    expect(wrapper.text()).toContain('5')
    expect(wrapper.text()).toContain('3')
    expect(wrapper.text()).not.toMatch(/71\s*(?:道|题)|已发布\s*71|共\s*\d+\s*题/)
    expect(wrapper.get('[data-testid="subject-practice-availability"]').text()).not.toMatch(/\d+\s*(?:道|题)/)
    expect(wrapper.get('[data-testid="past-paper-unavailable"]').text()).not.toMatch(/\d+\s*(?:道|题)/)
    expect(wrapper.text()).not.toContain('机考模拟')
    expect(wrapper.text()).not.toContain('反馈方式')
    expect(wrapper.text()).not.toContain('创建练习')
  })

  it('keeps the public practice home off protected history and personal-stat APIs', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const qualificationStore = useQualificationStore()
    qualificationStore.qualifications = [
      { id: 'q-public', code: 'HIGH_PM', name: '信息系统项目管理师', levelCode: 'ADVANCED', status: 'ENABLED', available: true },
    ]
    qualificationStore.selectedLevelCode = 'ADVANCED'
    qualificationStore.selectedQualificationId = 'q-public'
    qualificationStore.subjects = [
      { id: 's-public', qualificationId: 'q-public', code: 'COMPREHENSIVE_KNOWLEDGE', name: '综合知识', type: 'COMPREHENSIVE', status: 'ENABLED' },
    ]
    qualificationStore.selectedSubjectId = 's-public'
    qualificationStore.status = 'success'
    qualificationStore.subjectStatus = 'success'

    const getCatalog = vi.fn().mockResolvedValue({
      qualificationId: 'q-public', available: true, questionCount: 20, paperCount: 0, paperYears: [],
      subjects: [{ subjectId: 's-public', subjectName: '综合知识', subjectType: 'COMPREHENSIVE', available: true, questionCount: 20, questionTypes: [] }],
    })
    const listPapers = vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 6 })
    const listAttemptHistory = vi.fn()
    const getModuleHomeSnapshot = vi.fn()
    setPracticeGatewayForTests({ getCatalog, listPapers, listAttemptHistory } as unknown as PracticeGateway)
    setPlatformHomeGatewayForTests({ getModuleHomeSnapshot })

    const wrapper = mount(PracticeWorkspaceHomeView, { global: { ...global, plugins: [pinia] } })
    await flushPromises()

    expect(getCatalog).toHaveBeenCalledWith('q-public')
    expect(listPapers).toHaveBeenCalled()
    expect(listAttemptHistory).not.toHaveBeenCalled()
    expect(getModuleHomeSnapshot).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('登录后保存学习记录')
    expect(wrapper.text()).not.toContain('题库加载失败')
  })

  it('gates an anonymous start action and restores it after login', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const qualificationStore = useQualificationStore()
    qualificationStore.qualifications = [
      { id: 'q-public', code: 'HIGH_PM', name: '信息系统项目管理师', levelCode: 'ADVANCED', status: 'ENABLED', available: true },
    ]
    qualificationStore.selectedLevelCode = 'ADVANCED'
    qualificationStore.selectedQualificationId = 'q-public'
    qualificationStore.subjects = [
      { id: 's-public', qualificationId: 'q-public', code: 'COMPREHENSIVE_KNOWLEDGE', name: '综合知识', type: 'COMPREHENSIVE', status: 'ENABLED' },
    ]
    qualificationStore.selectedSubjectId = 's-public'
    qualificationStore.status = 'success'
    qualificationStore.subjectStatus = 'success'

    const listAttemptHistory = vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 20 })
    const createAttempt = vi.fn().mockResolvedValue({ id: 'attempt-restored' })
    setPracticeGatewayForTests({
      getCatalog: vi.fn().mockResolvedValue({
        qualificationId: 'q-public', available: true, questionCount: 20, paperCount: 0, paperYears: [],
        subjects: [{ subjectId: 's-public', subjectName: '综合知识', subjectType: 'COMPREHENSIVE', available: true, questionCount: 20, questionTypes: [] }],
      }),
      listPapers: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 10 }),
      listAttemptHistory,
      createAttempt,
    } as unknown as PracticeGateway)

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/login', component: { template: '<div />' } },
        { path: '/app/practice/start', component: { template: '<div />' } },
        { path: '/app/practice/attempts/:attemptId', component: { template: '<div />' } },
      ],
    })
    await router.push('/app/practice/start?mode=DAILY&subjectId=s-public')
    await router.isReady()
    const viewStubs = {
      ElButton: { template: '<button @click="$emit(\'click\')"><slot /></button>' },
      ElIcon: { template: '<i><slot /></i>' },
      ElSkeleton: { template: '<div />' },
      ElSelect: { template: '<select><slot /></select>' },
      ElOption: { template: '<option />' },
    }
    const wrapper = mount(PracticeListView, { global: { plugins: [pinia, router], stubs: viewStubs } })
    await flushPromises()
    const startButton = wrapper.findAll('button').find((button) => button.text().includes('开始刷题'))
    expect(startButton).toBeDefined()
    await startButton!.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/login')
    const recoveryTarget = String(router.currentRoute.value.query.redirect)
    expect(recoveryTarget).toContain('/app/practice/start?')
    expect(recoveryTarget).toContain('resumeAction=1')
    expect(recoveryTarget).toContain('qualificationId=q-public')
    expect(recoveryTarget).toContain('subjectId=s-public')
    expect(listAttemptHistory).not.toHaveBeenCalled()
    expect(createAttempt).not.toHaveBeenCalled()

    wrapper.unmount()
    const authStore = useAuthStore()
    authStore.user = { id: 'student-1', username: 'student', displayName: '学习学员', status: 'ENABLED' }
    authStore.roles = ['STUDENT']
    authStore.permissions = ['practice:workspace:view']
    authStore.initialized = true
    await router.push(recoveryTarget)
    const restoredWrapper = mount(PracticeListView, { global: { plugins: [pinia, router], stubs: viewStubs } })
    await flushPromises()
    await flushPromises()

    expect(listAttemptHistory).toHaveBeenCalled()
    expect(createAttempt).toHaveBeenCalledWith(expect.objectContaining({
      qualificationId: 'q-public',
      subjectId: 's-public',
      mode: 'DAILY',
      source: { type: 'DAILY' },
    }))
    expect(router.currentRoute.value.fullPath).toBe('/app/practice/attempts/attempt-restored')
    restoredWrapper.unmount()
  })

  it('clears personal home state when a logged-in learner logs out before browsing the public page', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authenticateStudent()
    const qualificationStore = useQualificationStore()
    qualificationStore.qualifications = [
      { id: 'q-advanced', code: 'HIGH_PM', name: '信息系统项目管理师', levelCode: 'ADVANCED', status: 'ENABLED', available: true },
    ]
    qualificationStore.selectedLevelCode = 'ADVANCED'
    qualificationStore.selectedQualificationId = 'q-advanced'
    qualificationStore.subjects = [
      { id: 's-advanced', qualificationId: 'q-advanced', code: 'COMPREHENSIVE_KNOWLEDGE', name: '综合知识', type: 'COMPREHENSIVE', status: 'ENABLED', sortOrder: 1 },
    ]
    qualificationStore.selectedSubjectId = 's-advanced'
    qualificationStore.status = 'success'
    qualificationStore.subjectStatus = 'success'
    qualificationStore.ruleStatus = 'success'
    qualificationStore.activeExamRule = { qualificationId: 'q-advanced' } as never
    const catalog = {
      qualificationId: 'q-advanced', available: true, questionCount: 71, paperCount: 0, paperYears: [],
      subjects: [{ subjectId: 's-advanced', subjectName: '综合知识', subjectType: 'COMPREHENSIVE', available: true, questionCount: 71, questionTypes: [{ questionType: 'SINGLE_CHOICE', count: 71 }] }],
    }
    setPracticeGatewayForTests({
      getCatalog: vi.fn().mockResolvedValue(catalog),
      listPapers: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 6 }),
      listAttemptHistory: vi.fn().mockResolvedValue({
        items: [{ id: 'attempt-1', status: 'IN_PROGRESS', sourceType: 'DAILY', questionCount: 20, startedAt: '2026-07-18T08:00:00Z' }],
        total: 1,
        page: 1,
        pageSize: 1,
      }),
    } as unknown as PracticeGateway)
    setPlatformHomeGatewayForTests({
      getModuleHomeSnapshot: vi.fn().mockResolvedValue({
        recentTasks: [],
        stats: [
          { id: 'practice-count', value: '8' },
          { id: 'mistake-count', value: '5' },
          { id: 'favorite-count', value: '3' },
        ],
      }),
    })

    const wrapper = mount(PracticeWorkspaceHomeView, { global: { ...global, plugins: [pinia] } })
    await flushPromises()
    expect(wrapper.text()).toContain('继续上次练习')
    expect(wrapper.text()).toContain('学习概览')
    expect(wrapper.text()).toContain('练习记录')
    expect(wrapper.text()).toContain('待复习错题')
    expect(wrapper.text()).toContain('收藏题目')

    useAuthStore().clearSession()
    await wrapper.vm.$nextTick()
    await flushPromises()

    expect(wrapper.text()).toContain('当前资格暂无已发布题目')
    expect(wrapper.text()).not.toContain('继续上次练习')
    expect(wrapper.text()).not.toContain('学习概览')
    expect(wrapper.text()).not.toContain('练习记录')
    expect(wrapper.text()).not.toContain('待复习错题')
    expect(wrapper.text()).not.toContain('收藏题目')
  })

  it('gives practice history a real empty state and a stable entry action', () => {
    const wrapper = mount(PracticeHistorySlotView, { global })

    expect(wrapper.find('[data-testid="practice-history-slot"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('练习历史')
    expect(wrapper.text()).toContain('开始一次练习')
    expect(wrapper.text()).not.toContain('由 FE-REDESIGN-02')
  })

  it('rebuilds the workspace and paper scope when the authoritative qualification changes', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authenticateStudent()
    const qualifications = [
      { id: 'q-advanced', code: 'HIGH_PM', name: '信息系统项目管理师', levelCode: 'ADVANCED', status: 'ENABLED', sortOrder: 10, available: true },
      { id: 'q-intermediate', code: 'MIDDLE_SOFTWARE_DESIGNER', name: '软件设计师', levelCode: 'INTERMEDIATE', status: 'ENABLED', sortOrder: 110, available: true },
      { id: 'q-unavailable', code: 'HIGH_SYSTEM_PLANNING_MANAGER', name: '系统规划与管理师', levelCode: 'ADVANCED', status: 'ENABLED', sortOrder: 20, available: false, unavailableReason: 'NO_PUBLISHED_RULE' },
    ] as const
    const subjects = {
      'q-advanced': [
        { id: 's-advanced-comprehensive', qualificationId: 'q-advanced', code: 'COMPREHENSIVE_KNOWLEDGE', name: '综合知识', type: 'COMPREHENSIVE', status: 'ENABLED', sortOrder: 1 },
        { id: 's-advanced-case', qualificationId: 'q-advanced', code: 'CASE_ANALYSIS', name: '案例分析', type: 'CASE', status: 'ENABLED', sortOrder: 2 },
        { id: 's-advanced-essay', qualificationId: 'q-advanced', code: 'ESSAY', name: '论文', type: 'ESSAY', status: 'ENABLED', sortOrder: 3 },
      ],
      'q-intermediate': [
        { id: 's-intermediate-basic', qualificationId: 'q-intermediate', code: 'BASIC_KNOWLEDGE', name: '基础知识', type: 'COMPREHENSIVE', status: 'ENABLED', sortOrder: 1 },
        { id: 's-intermediate-application', qualificationId: 'q-intermediate', code: 'APPLIED_TECHNOLOGY', name: '应用技术', type: 'APPLICATION', status: 'ENABLED', sortOrder: 2 },
      ],
      'q-unavailable': [
        { id: 's-unavailable-comprehensive', qualificationId: 'q-unavailable', code: 'COMPREHENSIVE_KNOWLEDGE', name: '综合知识', type: 'COMPREHENSIVE', status: 'ENABLED', sortOrder: 1 },
      ],
    } as const
    const getActiveExamRule = vi.fn(async (qualificationId: string) => qualificationId === 'q-unavailable' ? null : ({
      qualificationId,
      periodCode: '2026-H2',
      totalDurationMinutes: qualificationId === 'q-advanced' ? 360 : 240,
      deadlinePolicy: 'SHARED_TOTAL_DEADLINE',
    }))
    let resolveIntermediateSubjects!: (value: typeof subjects['q-intermediate']) => void
    const intermediateSubjects = new Promise<typeof subjects['q-intermediate']>((resolve) => {
      resolveIntermediateSubjects = resolve
    })
    setPlatformFoundationGatewayForTests({
      listQualifications: vi.fn(async () => structuredClone(qualifications)),
      listSubjects: vi.fn(async (qualificationId: string) => qualificationId === 'q-intermediate'
        ? intermediateSubjects
        : structuredClone(subjects[qualificationId as keyof typeof subjects] ?? [])),
      getActiveExamRule,
    } as unknown as PlatformFoundationGateway)
    const listPapers = vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 6 })
    setPracticeGatewayForTests({
      getCatalog: vi.fn(async (qualificationId: string) => ({
        qualificationId,
        available: true,
        questionCount: qualificationId === 'q-intermediate' ? 75 : 74,
        paperCount: 0,
        paperYears: [],
        subjects: (subjects[qualificationId as keyof typeof subjects] ?? []).map((subject, index) => ({
          subjectId: subject.id,
          subjectName: subject.name,
          subjectType: subject.type,
          available: true,
          questionCount: index === 0 ? (qualificationId === 'q-intermediate' ? 75 : 71) : 3,
          questionTypes: [{ questionType: index === 1 ? 'CASE' : 'SINGLE_CHOICE', count: index === 0 ? (qualificationId === 'q-intermediate' ? 75 : 71) : 3 }],
        })),
      })),
      listPapers,
      createAttempt: vi.fn(),
      getAttempt: vi.fn(),
      listAttemptHistory: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 1 }),
      saveAnswer: vi.fn(),
      setFavorite: vi.fn(),
      saveNote: vi.fn(),
    } as unknown as PracticeGateway)
    const qualificationStore = useQualificationStore()
    await qualificationStore.load()
    const wrapper = mount(PracticeWorkspaceHomeView, { global: { ...global, plugins: [pinia] } })
    await flushPromises()

    expect(wrapper.get('[data-testid="qualification-workspace-title"]').text()).toContain('信息系统项目管理师')
    expect(wrapper.text()).toContain('综合知识')
    expect(wrapper.text()).toContain('案例分析')
    expect(wrapper.text()).toContain('论文')
    expect(wrapper.text()).toContain('科目专项')
    expect(getActiveExamRule).toHaveBeenCalledWith('q-advanced', '2026-H2')
    expect(listPapers.mock.calls.at(-1)?.[0]).toMatchObject({ qualificationId: 'q-advanced' })
    expect(listPapers.mock.calls.at(-1)?.[0]).not.toHaveProperty('subjectId')

    const switchingLevel = qualificationStore.selectLevel('INTERMEDIATE')
    await wrapper.vm.$nextTick()

    expect(qualificationStore.subjects).toEqual([])
    expect(wrapper.text()).toContain('正在加载真实题库')
    expect(wrapper.text()).not.toContain('案例分析')
    expect(wrapper.text()).not.toContain('论文 120 分钟')

    resolveIntermediateSubjects(structuredClone(subjects['q-intermediate']))
    await switchingLevel
    await flushPromises()

    expect(wrapper.get('[data-testid="qualification-workspace-title"]').text()).toContain('软件设计师')
    expect(wrapper.text()).toContain('基础知识')
    expect(wrapper.text()).toContain('应用技术')
    expect(wrapper.text()).not.toContain('论文 120 分钟')
    expect(listPapers.mock.calls.at(-1)?.[0]).toMatchObject({ qualificationId: 'q-intermediate' })

    await qualificationStore.selectLevel('ADVANCED')
    await qualificationStore.selectQualification('q-unavailable')
    await flushPromises()

    expect(wrapper.text()).not.toContain('本考期未开放')
    expect(wrapper.text()).toContain('每日练习')
  })

  it('keeps published questions available when only the exam-period rule is unavailable', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authenticateStudent()
    const qualificationStore = useQualificationStore()
    qualificationStore.qualifications = [
      { id: 'q-unavailable', code: 'HIGH_SYSTEM_PLANNING_MANAGER', name: '系统规划与管理师', levelCode: 'ADVANCED', status: 'ENABLED', available: false, unavailableReason: 'NO_PUBLISHED_RULE' },
    ]
    qualificationStore.selectedLevelCode = 'ADVANCED'
    qualificationStore.selectedQualificationId = 'q-unavailable'
    qualificationStore.status = 'success'
    qualificationStore.subjectStatus = 'success'
    qualificationStore.ruleStatus = 'unavailable'
    qualificationStore.activeExamRule = null
    setPracticeGatewayForTests({
      getCatalog: vi.fn().mockResolvedValue({
        qualificationId: 'q-unavailable', available: true, questionCount: 12, paperCount: 0, paperYears: [],
        subjects: [{ subjectId: 's-unavailable-comprehensive', subjectName: '综合知识', subjectType: 'COMPREHENSIVE', available: true, questionCount: 12, questionTypes: [{ questionType: 'SINGLE_CHOICE', count: 12 }] }],
      }),
      listPapers: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 6 }),
      listAttemptHistory: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 1 }),
    } as unknown as PracticeGateway)
    const wrapper = mount(PracticeWorkspaceHomeView, {
      global: {
        plugins: [pinia],
        stubs: {
          ...global.stubs,
          RouterLink: {
            props: ['to'],
            template: '<a :href="typeof to === \'string\' ? to : to?.path"><slot /></a>',
          },
        },
      },
    })
    await flushPromises()

    expect(wrapper.text()).not.toContain('本考期未开放')
    expect(wrapper.text()).not.toMatch(/12\s*(?:道|题)|已发布\s*12|共\s*12\s*题/)
    expect(wrapper.text()).toContain('每日练习')
    expect(wrapper.text()).toContain('历年真题')
  })

  it('keeps production double-mock flags on HTTP gateways and renders no preview synthesis', async () => {
    vi.stubEnv('DEV', false)
    vi.stubEnv('VITE_PLATFORM_MOCK', 'true')
    vi.stubEnv('VITE_PRACTICE_MOCK', 'true')
    vi.resetModules()

    try {
      const [
        { default: ProductionWorkspace },
        practiceGatewayModule,
        { HttpPracticeGateway },
        foundationGatewayModule,
        { HttpPlatformFoundationGateway },
        { useQualificationStore: useProductionQualificationStore },
      ] = await Promise.all([
        import('@/modules/platform/views/PracticeWorkspaceHomeView.vue'),
        import('@/modules/practice/api/gateway'),
        import('@/modules/practice/api/http-practice.gateway'),
        import('@/modules/foundation/api/gateway'),
        import('@/modules/foundation/api/http-platform-foundation.gateway'),
        import('@/stores/qualification'),
      ])

      expect(practiceGatewayModule.getPracticeGateway()).toBeInstanceOf(HttpPracticeGateway)
      expect(foundationGatewayModule.getPlatformFoundationGateway()).toBeInstanceOf(HttpPlatformFoundationGateway)

      practiceGatewayModule.setPracticeGatewayForTests({
        getCatalog: vi.fn().mockResolvedValue({
          qualificationId: 'q-advanced', available: true, questionCount: 71, paperCount: 0, paperYears: [],
          subjects: [{ subjectId: 's-advanced', subjectName: '综合知识', subjectType: 'COMPREHENSIVE', available: true, questionCount: 71, questionTypes: [{ questionType: 'SINGLE_CHOICE', count: 71 }] }],
        }),
        listPapers: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 6 }),
        listAttemptHistory: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 1 }),
      } as unknown as PracticeGateway)
      const pinia = createPinia()
      setActivePinia(pinia)
      const qualificationStore = useProductionQualificationStore()
      qualificationStore.qualifications = [
        { id: 'q-advanced', code: 'HIGH_PM', name: '信息系统项目管理师', levelCode: 'ADVANCED', status: 'ENABLED', available: true },
      ]
      qualificationStore.selectedLevelCode = 'ADVANCED'
      qualificationStore.selectedQualificationId = 'q-advanced'
      qualificationStore.subjects = [
        { id: 's-advanced', qualificationId: 'q-advanced', code: 'COMPREHENSIVE_KNOWLEDGE', name: '综合知识', type: 'COMPREHENSIVE', status: 'ENABLED', sortOrder: 1 },
      ]
      qualificationStore.selectedSubjectId = 's-advanced'
      qualificationStore.status = 'success'
      qualificationStore.subjectStatus = 'success'
      qualificationStore.ruleStatus = 'success'
      qualificationStore.activeExamRule = { qualificationId: 'q-advanced' } as never
      const wrapper = mount(ProductionWorkspace, { global: { ...global, plugins: [pinia] } })
      await flushPromises()

      expect(wrapper.text()).not.toContain('DEV 规则预览')
      expect(wrapper.text()).not.toContain('第 6 章')
      expect(wrapper.text()).not.toContain('较昨日 +12')
      expect(wrapper.text()).not.toContain('2025 上半年')
      expect(wrapper.text()).toContain('开始今天的刷题')
      expect(wrapper.text()).toContain('专项练习已开放')
      expect(wrapper.text()).not.toMatch(/71\s*(?:道|题)|已发布\s*71|共\s*71\s*题/)
    } finally {
      vi.unstubAllEnvs()
      vi.resetModules()
    }
  })
})
