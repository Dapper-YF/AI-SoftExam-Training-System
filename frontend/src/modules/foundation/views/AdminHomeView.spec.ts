import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AdminHomeView from './AdminHomeView.vue'
import { setAdminQuestionGatewayForTests } from '@/modules/admin/questions/api/gateway'
import { useAuthStore } from '@/stores/auth'
import type { AdminQuestionGateway } from '@/modules/admin/questions/api/admin-question.gateway'
import type { AdminQuestionDashboard } from '@/modules/admin/questions/types'

const dashboard: AdminQuestionDashboard = {
  totalCount: 149,
  draftCount: 3,
  inReviewCount: 2,
  publishedCount: 143,
  retiredCount: 1,
  byQualification: [{ key: '1', label: '信息系统项目管理师', count: 149 }],
  byQuestionType: [
    { key: 'SINGLE_CHOICE', count: 80 },
    { key: 'MULTI_CHOICE', count: 40 },
    { key: 'JUDGE', count: 20 },
    { key: 'CASE', count: 5 },
    { key: 'ESSAY', count: 4 },
  ],
  recentImports: [{
    batchId: 12,
    actorUserId: 1,
    clientMutationId: 'batch-12',
    schemaVersion: 'question-import/1.0',
    status: 'CONFIRMED',
    totalCount: 3,
    eligibleCount: 3,
    importedCount: 3,
    createdAt: '2026-07-17T08:00:00Z',
  }],
  pendingReviews: [{
    questionId: 101,
    questionVersionId: 501,
    versionNo: 2,
    qualificationId: 1,
    subjectId: 11,
    questionType: 'CASE',
    title: '项目范围管理案例',
    status: 'IN_REVIEW',
    rowVersion: 7,
  }],
}

function gatewayWith(result: AdminQuestionDashboard | Error): AdminQuestionGateway {
  return {
    getDashboard: result instanceof Error ? vi.fn(async () => { throw result }) : vi.fn(async () => result),
    listQuestions: vi.fn(),
    getQuestion: vi.fn(),
    createQuestion: vi.fn(),
    createRevision: vi.fn(),
    submitReview: vi.fn(),
    rejectReview: vi.fn(),
    approveReview: vi.fn(),
    publishQuestion: vi.fn(),
    retireQuestion: vi.fn(),
    copyVersion: vi.fn(),
    listVersions: vi.fn(),
    listReviews: vi.fn(),
    listAuditEvents: vi.fn(),
    preflightImport: vi.fn(),
    confirmImport: vi.fn(),
    listImportBatches: vi.fn(),
    getImportBatch: vi.fn(),
    rollbackImport: vi.fn(),
    publishImport: vi.fn(),
    rejectImport: vi.fn(),
    downloadImportErrors: vi.fn(),
  }
}

function mountAdminHome() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/admin/questions', component: { template: '<div />' } },
      { path: '/admin/questions/new', component: { template: '<div />' } },
      { path: '/admin/question-imports', component: { template: '<div />' } },
    ],
  })
  return mount(AdminHomeView, { global: { plugins: [ElementPlus, router] } })
}

describe('AdminHomeView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    const authStore = useAuthStore()
    authStore.user = { id: '1', username: 'admin', displayName: '题库管理员', status: 'ENABLED' }
    authStore.roles = ['ADMIN']
    authStore.permissions = ['admin:home:view', 'question:read', 'question:create', 'question:review', 'question:publish', 'question:import']
    authStore.featureFlags = { 'admin.portal': true, 'admin.questions': true }
  })

  it('renders real aggregate metrics, distributions, imports, pending reviews and shortcuts', async () => {
    setAdminQuestionGatewayForTests(gatewayWith(dashboard))
    const wrapper = mountAdminHome()
    await flushPromises()

    expect(wrapper.get('[data-testid="question-status-summary"]').text()).toContain('149')
    expect(wrapper.get('[data-testid="question-status-summary"]').text()).toContain('已退役')
    expect(wrapper.get('[data-testid="question-distribution"]').text()).toContain('案例分析题')
    expect(wrapper.get('[data-testid="pending-review-tasks"]').text()).toContain('项目范围管理案例')
    expect(wrapper.get('[data-testid="recent-import-state"]').text()).toContain('12')
    expect(wrapper.get('[data-testid="admin-quick-actions"]').text()).toContain('批量导入')
  })

  it('keeps a safe Chinese retry state when the aggregate API fails', async () => {
    const gateway = gatewayWith(new Error('SQL stack and repository path'))
    setAdminQuestionGatewayForTests(gateway)
    const wrapper = mountAdminHome()
    await flushPromises()

    expect(wrapper.get('[data-testid="admin-home-error"]').text()).toContain('题库工作台加载失败')
    expect(wrapper.get('[data-testid="admin-home-error"]').text()).not.toContain('SQL')
    expect(wrapper.find('[data-testid="question-status-summary"]').exists()).toBe(false)
    await wrapper.get('[data-testid="admin-home-retry"]').trigger('click')
    await flushPromises()
    expect(gateway.getDashboard).toHaveBeenCalledTimes(2)
  })
})
