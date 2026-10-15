import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { PlatformFoundationGateway } from '@/modules/foundation/api/platform-foundation.gateway'
import { setPlatformFoundationGatewayForTests } from '@/modules/foundation/api/gateway'
import type { LevelCode, Qualification, Subject } from '@/modules/foundation/types'
import type { PracticeGateway } from '../api/practice.gateway'
import { setPracticeGatewayForTests } from '../api/gateway'
import PracticeListView from '../views/PracticeListView.vue'
import StudentHomeView from '@/modules/foundation/views/StudentHomeView.vue'
import { usePracticeStore } from '@/stores/practice'
import { useQualificationStore } from '@/stores/qualification'
import { useAuthStore } from '@/stores/auth'
import type { PagedResult, PracticePaperSummary } from '../types'

interface Deferred<T> {
  promise: Promise<T>
  resolve: (value: T) => void
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

function paper(id: string, subjectId: string): PracticePaperSummary {
  return {
    id,
    title: id,
    qualificationId: 'q-advanced',
    subjectId,
    paperType: 'REAL',
    yearNo: 2025,
    status: 'PUBLISHED',
  }
}

function gateway(listPapers = vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 6 })): PracticeGateway {
  return {
    getCatalog: vi.fn(async (qualificationId: string) => ({
      qualificationId,
      available: true,
      questionCount: 10,
      paperCount: 0,
      paperYears: [],
      subjects: [{
        subjectId: 'subject-initial', subjectName: '综合知识', subjectType: 'COMPREHENSIVE',
        available: true,
        questionCount: 10, questionTypes: [{ questionType: 'SINGLE_CHOICE', count: 10 }],
      }],
    })),
    listPapers,
    createAttempt: vi.fn(),
    getAttempt: vi.fn(),
    listAttemptHistory: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 20 }),
    listMistakes: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 20 }),
    saveAnswer: vi.fn(),
    settleQuestion: vi.fn(),
    submitAttempt: vi.fn(),
    getExplanation: vi.fn(),
    setFavorite: vi.fn(),
    saveNote: vi.fn(),
  } as unknown as PracticeGateway
}

async function mountList(
  listPapers: ReturnType<typeof vi.fn>,
  mode = 'PAST_EXAM',
  practiceGateway = gateway(listPapers),
) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const qualificationStore = useQualificationStore()
  const practiceStore = usePracticeStore()
  const authStore = useAuthStore()
  authStore.user = { id: 'student-1', username: 'student', displayName: 'student', status: 'ENABLED' }
  authStore.roles = ['STUDENT']
  authStore.permissions = ['practice:workspace:view']
  qualificationStore.qualifications = [
    { id: 'q-advanced', code: 'HIGH_PM', name: '信息系统项目管理师', levelCode: 'ADVANCED', status: 'ENABLED' },
  ]
  qualificationStore.status = 'success'
  qualificationStore.selectedLevelCode = 'ADVANCED'
  qualificationStore.selectedQualificationId = 'q-advanced'
  qualificationStore.subjects = [
    { id: 'subject-initial', qualificationId: 'q-advanced', code: 'COMPREHENSIVE', name: '综合知识', type: 'COMPREHENSIVE', status: 'ENABLED' },
  ]
  qualificationStore.selectedSubjectId = 'subject-initial'
  qualificationStore.subjectStatus = 'success'
  setPracticeGatewayForTests(practiceGateway)
  setPlatformFoundationGatewayForTests({
    listQualifications: vi.fn(async () => []),
    listSubjects: vi.fn(async () => []),
    listChapterNodes: vi.fn(async () => []),
    listSyllabusNodes: vi.fn(async () => []),
    listKnowledgeTags: vi.fn(async () => []),
  } satisfies PlatformFoundationGateway)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/app/practice/list', component: PracticeListView },
      { path: '/app/practice/attempts/:attemptId', component: { template: '<div />' } },
    ],
  })
  await router.push(`/app/practice/list?mode=${mode}`)
  await router.isReady()
  const wrapper = mount(PracticeListView, { global: { plugins: [pinia, router, ElementPlus] } })
  await flushPromises()
  return { wrapper, qualificationStore, practiceStore }
}

describe('PracticeListView confirmed subject scope', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('loads complete papers by qualification instead of splitting them by subject', async () => {
    const listPapers = vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 6 })
    await mountList(listPapers)

    expect(listPapers).toHaveBeenCalledOnce()
    expect(listPapers.mock.calls[0]?.[0]).toMatchObject({
      qualificationId: 'q-advanced',
    })
    expect(listPapers.mock.calls[0]?.[0]).not.toHaveProperty('subjectId')
  })

  it('clears old rows and waits for the latest confirmed subject during upstream changes', async () => {
    const listPapers = vi.fn()
      .mockResolvedValueOnce({
        items: [paper('old-qualification-paper', 'subject-initial')],
        total: 1,
        page: 1,
        pageSize: 6,
      })
      .mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 6 })
    const { wrapper, qualificationStore, practiceStore } = await mountList(listPapers)
    expect(wrapper.text()).toContain('old-qualification-paper')
    practiceStore.page = 3
    practiceStore.filters.chapter = '旧章节'
    practiceStore.filters.knowledgePoint = '旧知识点'
    practiceStore.filters.year = '2025年上半年'
    practiceStore.filters.keyword = '旧资格'
    listPapers.mockClear()

    qualificationStore.subjectStatus = 'loading'
    qualificationStore.subjects = []
    qualificationStore.selectedSubjectId = null
    expect(practiceStore.papers).toEqual([])
    expect(practiceStore.listStatus).toBe('idle')
    expect(listPapers).not.toHaveBeenCalled()

    qualificationStore.selectedQualificationId = 'q-intermediate'
    qualificationStore.selectedSubjectId = 'subject-stale-cache'
    expect(practiceStore.papers).toEqual([])
    expect(practiceStore.page).toBe(1)
    expect(practiceStore.filters).toMatchObject({
      chapter: 'ALL',
      knowledgePoint: 'ALL',
      year: 'ALL',
      keyword: '',
    })
    await flushPromises()
    expect(listPapers).not.toHaveBeenCalled()

    qualificationStore.subjects = [
      { id: 'subject-latest', qualificationId: 'q-intermediate', code: 'APPLICATION', name: '应用技术', type: 'APPLICATION', status: 'ENABLED' },
    ]
    qualificationStore.selectedSubjectId = 'subject-latest'
    qualificationStore.subjectStatus = 'success'
    await flushPromises()

    expect(listPapers).toHaveBeenCalledOnce()
    expect(listPapers.mock.calls[0]?.[0]).toMatchObject({
      qualificationId: 'q-intermediate',
    })
    expect(listPapers.mock.calls[0]?.[0]).not.toHaveProperty('subjectId')
  })

  it('hides implementation settings and fixes daily practice to per-question feedback', async () => {
    const listPapers = vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 6 })
    const dailyGateway = gateway(listPapers)
    const createAttempt = vi.fn().mockResolvedValue({ id: 'attempt-1' })
    dailyGateway.createAttempt = createAttempt
    const { wrapper } = await mountList(listPapers, 'DAILY', dailyGateway)

    expect(wrapper.text()).not.toContain('题量')
    expect(wrapper.text()).not.toContain('反馈方式')
    expect(wrapper.text()).not.toContain('创建练习')
    expect(wrapper.text()).toContain('专项练习已开放')
    expect(wrapper.text()).not.toMatch(/10\s*(?:道|题)|已发布\s*10|共\s*10\s*题/)
    expect(wrapper.get('[data-testid="subject-practice-availability"]').text()).not.toMatch(/\d+\s*(?:道|题)/)
    const start = wrapper.findAll('button').find((button) => button.text().includes('专项刷题'))
    expect(start).toBeDefined()
    await start!.trigger('click')
    await flushPromises()

    expect(createAttempt).toHaveBeenCalledWith(expect.objectContaining({
      qualificationId: 'q-advanced',
      subjectId: 'subject-initial',
      mode: 'DAILY',
      feedbackMode: 'PER_QUESTION',
      count: 100,
      source: { type: 'DAILY' },
    }))
  })

  it('starts a qualification-wide paper without assigning a fake subject', async () => {
    const listPapers = vi.fn().mockResolvedValue({
      items: [{
        id: 'paper-complete', title: '完整真题卷', qualificationId: 'q-advanced',
        paperType: 'REAL', yearNo: 2025, status: 'PUBLISHED',
      }],
      total: 1,
      page: 1,
      pageSize: 6,
    })
    const paperGateway = gateway(listPapers)
    const createAttempt = vi.fn().mockResolvedValue({ id: 'attempt-paper' })
    paperGateway.createAttempt = createAttempt
    const { wrapper } = await mountList(listPapers, 'PAST_EXAM', paperGateway)

    await wrapper.get('button.el-button--primary').trigger('click')
    await flushPromises()

    expect(createAttempt).toHaveBeenCalledWith(expect.objectContaining({
      qualificationId: 'q-advanced',
      mode: 'PAST_EXAM',
      feedbackMode: 'WHOLE_PAPER',
      count: 100,
      source: { type: 'PAPER', paperId: 'paper-complete' },
    }))
    expect(createAttempt.mock.calls[0]?.[0]).not.toHaveProperty('subjectId')
  })

  it('does not reload a complete paper list when only the selected specialty subject changes', async () => {
    const oldResponse = deferred<PagedResult<PracticePaperSummary>>()
    const listPapers = vi.fn().mockReturnValueOnce(oldResponse.promise)
    const { qualificationStore } = await mountList(listPapers)

    qualificationStore.subjects = [
      ...qualificationStore.subjects,
      { id: 'subject-application', qualificationId: 'q-advanced', code: 'APPLICATION', name: '应用技术', type: 'APPLICATION', status: 'ENABLED' },
    ]
    qualificationStore.selectSubject('subject-application')
    await flushPromises()

    expect(listPapers).toHaveBeenCalledOnce()

    oldResponse.resolve({
      items: [paper('complete-paper', 'subject-initial')],
      total: 1,
      page: 1,
      pageSize: 6,
    })
    await flushPromises()
  })

  it('fully replaces the previous mode content when switching practice modes', async () => {
    const listPapers = vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 6 })
    const { wrapper } = await mountList(listPapers, 'DAILY')

    expect(wrapper.find('.practice-mode-content').text()).toContain('选择刷题科目')
    const chapterButton = wrapper.findAll('.practice-mode-navigation button')
      .find((button) => button.text().includes('章节练习'))
    await chapterButton!.trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.practice-mode-content')).toHaveLength(1)
    expect(wrapper.find('.practice-mode-content').text()).toContain('章节练习')
    expect(wrapper.find('.practice-mode-content').text()).not.toContain('选择刷题科目')

    const paperButton = wrapper.findAll('.practice-mode-navigation button')
      .find((button) => button.text().includes('历年真题'))
    await paperButton!.trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.practice-mode-content')).toHaveLength(1)
    expect(wrapper.find('.practice-mode-content').text()).toContain('历年真题卷')
    expect(wrapper.find('.practice-mode-content').text()).toContain('完整年份试卷整理中')
    expect(wrapper.find('.practice-mode-content').text()).toContain('可先进行专项练习')
    expect(wrapper.get('[data-testid="past-paper-unavailable"]').text()).not.toMatch(/\d+\s*(?:道|题)/)
    expect(wrapper.find('.practice-mode-content').text()).not.toContain('章节练习先选择科目')
  })
})

describe('legacy student home practice scope', () => {
  const qualifications: Qualification[] = [
    { id: 'q-advanced-default', code: 'HIGH_PM', name: '信息系统项目管理师', levelCode: 'ADVANCED', status: 'ENABLED', sortOrder: 10 },
    { id: 'q-advanced', code: 'HIGH_ARCHITECT', name: '系统架构设计师', levelCode: 'ADVANCED', status: 'ENABLED', sortOrder: 20 },
    { id: 'q-intermediate-default', code: 'MIDDLE_NETWORK', name: '网络工程师', levelCode: 'INTERMEDIATE', status: 'ENABLED', sortOrder: 10 },
    { id: 'q-intermediate', code: 'MIDDLE_SD', name: '软件设计师', levelCode: 'INTERMEDIATE', status: 'ENABLED', sortOrder: 20 },
    { id: 'q-primary-default', code: 'LOW_INFORMATION', name: '信息处理技术员', levelCode: 'PRIMARY', status: 'ENABLED', sortOrder: 10 },
    { id: 'q-primary', code: 'LOW_PROGRAMMER', name: '程序员', levelCode: 'PRIMARY', status: 'ENABLED', sortOrder: 20 },
  ]
  const subjectsByQualification: Record<string, Subject[]> = Object.fromEntries(
    qualifications.map((qualification) => [
      qualification.id,
      [{
        id: `s-${qualification.id.slice(2)}`,
        qualificationId: qualification.id,
        code: 'COMPREHENSIVE',
        name: '综合知识',
        type: 'COMPREHENSIVE',
        status: 'ENABLED',
      } satisfies Subject],
    ]),
  )

  it.each([
    { levelLabel: '高级', levelCode: 'ADVANCED', qualificationId: 'q-advanced', qualificationName: '系统架构设计师', subjectId: 's-advanced' },
    { levelLabel: '中级', levelCode: 'INTERMEDIATE', qualificationId: 'q-intermediate', qualificationName: '软件设计师', subjectId: 's-intermediate' },
    { levelLabel: '初级', levelCode: 'PRIMARY', qualificationId: 'q-primary', qualificationName: '程序员', subjectId: 's-primary' },
  ] satisfies Array<{
    levelLabel: string
    levelCode: LevelCode
    qualificationId: string
    qualificationName: string
    subjectId: string
  }>)('synchronizes $levelCode and switches the confirmed practice scope', async ({ levelLabel, levelCode, qualificationId, qualificationName, subjectId }) => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const qualificationStore = useQualificationStore()
    qualificationStore.status = 'success'
    qualificationStore.qualifications = structuredClone(qualifications)
    qualificationStore.selectedLevelCode = 'ADVANCED'
    qualificationStore.selectedQualificationId = 'q-advanced-default'
    qualificationStore.subjects = structuredClone(subjectsByQualification['q-advanced-default'] ?? [])
    qualificationStore.selectedSubjectId = 's-advanced-default'
    qualificationStore.subjectStatus = 'success'
    const listSubjects = vi.fn(async (selectedQualificationId: string) =>
      structuredClone(subjectsByQualification[selectedQualificationId] ?? []))
    setPlatformFoundationGatewayForTests({
      listQualifications: vi.fn(async () => structuredClone(qualifications)),
      listSubjects,
      listChapterNodes: vi.fn(async () => []),
      listSyllabusNodes: vi.fn(async () => []),
    } satisfies PlatformFoundationGateway)
    const listPapers = vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 6 })
    setPracticeGatewayForTests(gateway(listPapers))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/app/home', component: StudentHomeView }],
    })
    await router.push('/app/home')
    await router.isReady()
    const wrapper = mount(StudentHomeView, { global: { plugins: [pinia, router, ElementPlus] } })
    await flushPromises()
    listPapers.mockClear()
    listSubjects.mockClear()

    await wrapper.get(`[data-level-code="${levelCode}"]`).trigger('click')
    await flushPromises()

    expect(qualificationStore.selectedLevelCode).toBe(levelCode)
    expect(wrapper.get(`[data-level-code="${levelCode}"]`).classes()).toContain('is-active')
    expect(wrapper.text()).toContain(qualificationName)
    await wrapper.get('.qualification-options').findAll('button')
      .find((button) => button.text().includes(qualificationName))!
      .trigger('click')
    await flushPromises()

    expect(qualificationStore.selectedQualificationId).toBe(qualificationId)
    expect(qualificationStore.selectedSubjectId).toBe(subjectId)
    expect(listSubjects).toHaveBeenCalledTimes(2)
    expect(listPapers).toHaveBeenCalledTimes(2)
    expect(listPapers.mock.calls.at(-1)?.[0]).toMatchObject({ qualificationId, subjectId })
    expect(JSON.stringify(listPapers.mock.calls)).not.toMatch(/"(?:HIGH|MIDDLE|LOW)"/)
  })

  it('does not load papers from non-confirmed subject IDs while subjects are loading', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const qualificationStore = useQualificationStore()
    qualificationStore.status = 'success'
    qualificationStore.qualifications = [
      { id: 'q-latest', code: 'HIGH_PM', name: '信息系统项目管理师', levelCode: 'ADVANCED', status: 'ENABLED' },
    ]
    qualificationStore.selectedLevelCode = 'ADVANCED'
    qualificationStore.selectedQualificationId = 'q-latest'
    qualificationStore.selectedSubjectId = 'subject-stale'
    qualificationStore.subjects = []
    qualificationStore.subjectStatus = 'loading'
    const listPapers = vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 6 })
    setPracticeGatewayForTests(gateway(listPapers))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/app/home', component: StudentHomeView }],
    })
    await router.push('/app/home')
    await router.isReady()

    mount(StudentHomeView, { global: { plugins: [pinia, router, ElementPlus] } })
    await flushPromises()

    expect(listPapers).not.toHaveBeenCalled()
  })
})
