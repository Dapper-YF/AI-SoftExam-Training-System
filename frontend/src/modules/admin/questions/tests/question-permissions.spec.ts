import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '@/stores/auth'
import { setAdminQuestionGatewayForTests } from '../api/gateway'
import type { AdminQuestionGateway } from '../api/admin-question.gateway'
import type { AdminQuestionSummary } from '../types'
import AdminQuestionManagementView from '../views/AdminQuestionManagementView.vue'

const draft: AdminQuestionSummary = {
  questionId: 1,
  questionVersionId: 101,
  versionNo: 1,
  qualificationId: 1,
  subjectId: 11,
  questionType: 'SINGLE_CHOICE',
  title: '草稿题',
  difficulty: 'MEDIUM',
  status: 'DRAFT',
  rowVersion: 2,
  submittedBy: null,
  sourceType: 'MANUAL',
  currentWorking: true,
}

const approved: AdminQuestionSummary = {
  ...draft,
  questionId: 2,
  questionVersionId: 102,
  title: '审核通过待发布',
  status: 'IN_REVIEW',
  submittedBy: 9,
  reviewDecision: 'APPROVED',
}

const published: AdminQuestionSummary = {
  ...draft,
  questionId: 3,
  questionVersionId: 103,
  title: '已发布题',
  status: 'PUBLISHED',
  submittedBy: 8,
  reviewDecision: 'APPROVED',
  currentWorking: false,
  currentPublished: true,
}

function gateway(): AdminQuestionGateway {
  return {
    getDashboard: vi.fn(),
    listQuestions: async () => ({ items: [draft, approved, published], pageNo: 1, pageSize: 20, total: 3 }),
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

async function mountList() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/admin/questions', component: { template: '<div />' } },
      { path: '/admin/questions/new', component: { template: '<div />' } },
      { path: '/admin/questions/:questionId/versions/:versionId', component: { template: '<div />' } },
      { path: '/admin/question-imports', component: { template: '<div />' } },
    ],
  })
  await router.push('/admin/questions')
  await router.isReady()
  const wrapper = mount(AdminQuestionManagementView, { global: { plugins: [ElementPlus, router] } })
  await flushPromises()
  return wrapper
}

describe('question workflow button permissions', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    setAdminQuestionGatewayForTests(gateway())
  })

  it('allows the author to edit/submit/copy but never review, publish or retire', async () => {
    const auth = useAuthStore()
    auth.user = { id: '7', username: 'admin', displayName: '题库管理员', status: 'ENABLED' }
    auth.roles = ['ADMIN']
    auth.permissions = ['question:read', 'question:create', 'question:update', 'question:submit-review', 'question:import']
    const wrapper = await mountList()

    expect(wrapper.get('[data-testid="edit-101"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.get('[data-testid="submit-101"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.get('[data-testid="approve-102"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="publish-102"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="copy-103"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.get('[data-testid="retire-103"]').attributes('disabled')).toBeDefined()
  })

  it('lets reviewer publish/retire another author but disables self-review and self-publish', async () => {
    const auth = useAuthStore()
    auth.user = { id: '9', username: 'question_reviewer', displayName: '审核人', status: 'ENABLED' }
    auth.roles = ['QUESTION_REVIEWER']
    auth.permissions = ['question:read', 'question:review', 'question:publish']
    const wrapper = await mountList()

    expect(wrapper.get('[data-testid="approve-102"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="publish-102"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="copy-103"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="retire-103"]').attributes('disabled')).toBeUndefined()
  })
})
