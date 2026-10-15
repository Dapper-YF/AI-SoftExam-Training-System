import {
  createRouter,
  createWebHistory,
  type RouteLocationNormalized,
  type RouterHistory,
} from 'vue-router'
import { useExamSimulatorStore } from '@/features/exam-simulator/stores/exam-simulator'
import type { ExamEntryCode } from '@/features/exam-simulator/types'
import type { PlatformRole } from '@/modules/foundation/types'
import { useAuthStore } from '@/stores/auth'
import { useQualificationStore } from '@/stores/qualification'
import { resolveExamGuardRedirect, routeSessionMatches } from './guards'
import { resolvePlatformAccess, type PlatformAccessMeta } from './platform-guards'
import './types'

function platformMeta(to: RouteLocationNormalized): PlatformAccessMeta {
  const records = to.matched.map((record) => record.meta)
  return {
    requiresAuth: records.some((meta) => meta.requiresAuth),
    roles: records.flatMap((meta) => meta.roles ?? []),
    permissions: records.flatMap((meta) => meta.permissions ?? []),
    featureFlag: records.map((meta) => meta.featureFlag).filter(Boolean).at(-1),
  }
}

function preserveLocation(
  path: string,
  to: { query: RouteLocationNormalized['query']; hash: string },
) {
  return { path, query: to.query, hash: to.hash }
}

const studentPracticeMeta = {
  appShell: 'learning' as const,
  requiresAuth: true,
  roles: ['STUDENT'] as PlatformRole[],
  permissions: ['practice:workspace:view'],
  currentProduct: 'practice' as const,
}

export function createAppRouter(history: RouterHistory = createWebHistory()) {
  const router = createRouter({
    history,
    routes: [
      {
        path: '/',
        component: () => import('@/components/layout/PublicPortalLayout.vue'),
        meta: { appShell: 'public' },
        children: [
          { path: '', name: 'platform-root', component: () => import('@/modules/foundation/views/PublicHomeView.vue'), meta: { appShell: 'public', requiresQualification: true, pageTitle: '首页' } },
          { path: 'news', name: 'public-news', redirect: { path: '/', hash: '#news' }, meta: { appShell: 'public', pageTitle: '考试资讯' } },
          { path: 'resources', name: 'public-resources', component: () => import('@/modules/foundation/views/PublicLearningResourcesView.vue'), meta: { appShell: 'public', pageTitle: '学习资源' } },
          { path: 'practice', name: 'public-practice', component: () => import('@/modules/practice/views/PracticeListView.vue'), meta: { appShell: 'public', requiresQualification: true, currentProduct: 'practice', pageTitle: '免费题库' } },
          { path: 'tools', name: 'learning-tools', component: () => import('@/modules/practice/views/LearningToolsView.vue'), meta: { appShell: 'public', requiresAuth: true, roles: ['STUDENT'], permissions: ['practice:workspace:view'], currentProduct: 'practice', pageTitle: '学习工具' } },
          { path: 'ai', name: 'ai-assistance', component: () => import('@/modules/practice/views/AiAssistantView.vue'), meta: { appShell: 'public', requiresAuth: true, roles: ['STUDENT'], permissions: ['practice:workspace:view'], requiresQualification: true, currentProduct: 'practice', pageTitle: 'AI辅助' } },
          { path: '403', name: 'forbidden', component: () => import('@/modules/foundation/views/StatusView.vue'), props: { code: '403', title: '无权访问', description: '当前账号没有访问该页面的权限，或对应功能尚未开放。' }, meta: { appShell: 'public', pageTitle: '无权访问' } },
          { path: '404', name: 'not-found-page', component: () => import('@/modules/foundation/views/StatusView.vue'), props: { code: '404', title: '页面不存在', description: '访问地址无效，页面可能已移动或被移除。' }, meta: { appShell: 'public', pageTitle: '页面不存在' } },
          { path: '500', name: 'server-error', component: () => import('@/modules/foundation/views/StatusView.vue'), props: { code: '500', title: '系统异常', description: '系统暂时无法完成请求，请稍后重试。' }, meta: { appShell: 'public', pageTitle: '系统异常' } },
          { path: ':pathMatch(.*)*', name: 'not-found', component: () => import('@/modules/foundation/views/StatusView.vue'), props: { code: '404', title: '页面不存在', description: '访问地址无效，页面可能已移动或被移除。' }, meta: { appShell: 'public', pageTitle: '页面不存在' } },
        ],
      },
      { path: '/login', alias: '/auth/login', name: 'platform-login', component: () => import('@/modules/foundation/views/LoginView.vue'), meta: { appShell: 'public' } },
      { path: '/register', alias: '/auth/register', name: 'platform-register', redirect: { path: '/login', query: { mode: 'register' } }, meta: { appShell: 'public' } },
      { path: '/forgot-password', alias: '/auth/forgot-password', name: 'platform-forgot-password', component: () => import('@/modules/foundation/views/ForgotPasswordView.vue'), meta: { appShell: 'public' } },
      { path: '/onboarding/preparation', name: 'preparation-onboarding', component: () => import('@/modules/foundation/views/PreparationOnboardingView.vue'), meta: { appShell: 'public', requiresAuth: true } },
      {
        path: '/app/practice/attempts/:attemptId',
        name: 'practice-attempt',
        component: () => import('@/components/layout/AnswerLayout.vue'),
        meta: { ...studentPracticeMeta, appShell: 'answer', pageTitle: '在线作答' },
      },
      {
        path: '/app',
        component: () => import('@/components/layout/WorkspaceLayout.vue'),
        meta: { appShell: 'learning' },
        children: [
          { path: '', redirect: (to) => preserveLocation('/', to) },
          { path: 'home', name: 'legacy-student-home', redirect: (to) => preserveLocation('/', to) },
          { path: 'modules', name: 'legacy-module-home', redirect: (to) => preserveLocation('/', to) },
          { path: 'profile', name: 'user-profile', component: () => import('@/modules/foundation/views/UserProfileView.vue'), meta: { appShell: 'learning', requiresAuth: true, pageTitle: '个人资料' } },
          { path: 'practice', redirect: (to) => preserveLocation('/practice', to) },
          { path: 'practice/home', name: 'legacy-practice-home', redirect: (to) => preserveLocation('/practice', to) },
          { path: 'practice/start', name: 'legacy-practice-start', redirect: (to) => preserveLocation('/practice', to) },
          { path: 'practice/list', name: 'practice-list', redirect: (to) => preserveLocation('/practice', to) },
          { path: 'practice/library', name: 'practice-library', redirect: (to) => preserveLocation('/practice', to) },
          { path: 'practice/resources', name: 'practice-resources', redirect: (to) => ({ path: '/resources', query: to.query }) },
          { path: 'practice/review', name: 'practice-review', redirect: (to) => ({ path: '/tools', query: { ...to.query, section: 'mistakes' }, hash: to.hash }), meta: { ...studentPracticeMeta, pageTitle: '错题本' } },
          { path: 'practice/reports', name: 'practice-reports', redirect: (to) => ({ path: '/tools', query: { ...to.query, section: 'dashboard' }, hash: to.hash }), meta: { ...studentPracticeMeta, pageTitle: '学习数据' } },
          { path: 'practice/ai-assistant', name: 'practice-ai-assistant', redirect: (to) => ({ path: '/ai', query: { ...to.query, tab: 'assistant' }, hash: to.hash }), meta: { ...studentPracticeMeta, pageTitle: 'AI辅助' } },
          { path: 'practice/ai-practice', name: 'practice-ai-practice', redirect: (to) => ({ path: '/ai', query: { ...to.query, tab: 'question' }, hash: to.hash }), meta: { ...studentPracticeMeta, pageTitle: 'AI辅助' } },
          { path: 'practice/history', name: 'practice-history', component: () => import('@/modules/platform/slots/PracticeHistorySlotView.vue'), meta: { ...studentPracticeMeta, pageTitle: '练习历史' } },
          { path: 'practice/mistakes', name: 'practice-mistakes', redirect: (to) => ({ path: '/tools', query: { ...to.query, section: 'mistakes' }, hash: to.hash }), meta: { ...studentPracticeMeta } },
          { path: 'practice/favorites', name: 'practice-favorites', redirect: (to) => ({ path: '/tools', query: { ...to.query, section: 'favorites' }, hash: to.hash }), meta: { ...studentPracticeMeta } },
          { path: 'practice/session/:attemptId', name: 'legacy-practice-session', redirect: (to) => ({ path: `/app/practice/attempts/${String(to.params.attemptId)}`, query: to.query, hash: to.hash }), meta: { ...studentPracticeMeta, appShell: 'answer' } },
          { path: 'essay', redirect: '/app/essay/home', meta: { appShell: 'learning', requiresAuth: true } },
          { path: 'essay/home', name: 'essay-home', component: () => import('@/modules/platform/views/EssayWorkspaceHomeView.vue'), meta: { appShell: 'learning', requiresAuth: true, roles: ['STUDENT'], permissions: ['essay:workspace:view'], currentProduct: 'essay', pageTitle: '论文训练' } },
          { path: 'essay/editor', name: 'essay-editor', component: () => import('@/modules/platform/slots/EssayEditorSlotView.vue'), meta: { appShell: 'learning', requiresAuth: true, roles: ['STUDENT'], permissions: ['essay:workspace:view'], currentProduct: 'essay', pageTitle: '论文写作' } },
          { path: 'essay/reports', name: 'essay-reports', component: () => import('@/modules/platform/slots/EssayReportsSlotView.vue'), meta: { appShell: 'learning', requiresAuth: true, roles: ['STUDENT'], permissions: ['essay:workspace:view'], currentProduct: 'essay', pageTitle: '论文报告' } },
        ],
      },
      {
        path: '/admin', component: () => import('@/components/layout/AdminLayout.vue'), meta: { appShell: 'admin', requiresAuth: true, roles: ['ADMIN', 'QUESTION_AUTHOR', 'QUESTION_REVIEWER', 'QUESTION_PUBLISHER'] },
        children: [
          { path: '', redirect: '/admin/home' },
          { path: 'home', name: 'admin-home', component: () => import('@/modules/foundation/views/AdminHomeView.vue'), meta: { appShell: 'admin', requiresAuth: true, roles: ['ADMIN'], permissions: ['admin:home:view'], featureFlag: 'admin.portal' } },
          { path: 'contents', name: 'admin-contents', component: () => import('@/modules/admin/portal/views/PortalContentManagementView.vue'), meta: { appShell: 'admin', requiresAuth: true, roles: ['ADMIN'], permissions: ['admin:home:view'], featureFlag: 'admin.portal' } },
          { path: 'learning-resources', name: 'admin-learning-resources', component: () => import('@/modules/admin/learning/views/LearningResourceManagementView.vue'), meta: { appShell: 'admin', requiresAuth: true, roles: ['ADMIN'], permissions: ['admin:home:view'], featureFlag: 'admin.portal' } },
          { path: 'exam-schedules', name: 'admin-exam-schedules', component: () => import('@/modules/admin/schedule/views/RegionalScheduleManagementView.vue'), meta: { appShell: 'admin', requiresAuth: true, roles: ['ADMIN'], permissions: ['admin:home:view'], featureFlag: 'admin.portal' } },
          { path: 'knowledge-documents', name: 'admin-knowledge-documents', component: () => import('@/modules/admin/knowledge/views/KnowledgeDocumentManagementView.vue'), meta: { appShell: 'admin', requiresAuth: true, roles: ['ADMIN'], permissions: ['admin:home:view'], featureFlag: 'admin.portal' } },
          { path: 'questions', name: 'admin-questions', component: () => import('@/modules/admin/questions/views/AdminQuestionManagementView.vue'), meta: { appShell: 'admin', requiresAuth: true, permissions: ['question:read'], featureFlag: 'admin.questions' } },
          { path: 'questions/anomalies', name: 'admin-question-anomalies', component: () => import('@/modules/admin/questions/views/AdminQuestionAnomaliesView.vue'), meta: { appShell: 'admin', requiresAuth: true, permissions: ['question:read'], featureFlag: 'admin.questions' } },
          { path: 'practice-reviews', name: 'admin-practice-reviews', component: () => import('@/modules/admin/practice/views/PracticeReviewWorkbenchView.vue'), meta: { appShell: 'admin', requiresAuth: true, permissions: ['question:review'], featureFlag: 'admin.questions' } },
          { path: 'practice-taxonomy', name: 'admin-practice-taxonomy', component: () => import('@/modules/admin/questions/views/AdminPracticeTaxonomyView.vue'), meta: { appShell: 'admin', requiresAuth: true, roles: ['ADMIN'], permissions: ['admin:home:view'], featureFlag: 'admin.questions' } },
          { path: 'papers', name: 'admin-papers', component: () => import('@/modules/admin/questions/views/AdminPaperManagementView.vue'), meta: { appShell: 'admin', requiresAuth: true, roles: ['ADMIN'], permissions: ['admin:home:view'], featureFlag: 'admin.questions' } },
          { path: 'papers/new', name: 'admin-paper-new', component: () => import('@/modules/admin/questions/views/AdminPaperEditorView.vue'), meta: { appShell: 'admin', requiresAuth: true, roles: ['ADMIN'], permissions: ['admin:home:view'], featureFlag: 'admin.questions' } },
          { path: 'papers/:paperId/edit', name: 'admin-paper-edit', component: () => import('@/modules/admin/questions/views/AdminPaperEditorView.vue'), meta: { appShell: 'admin', requiresAuth: true, roles: ['ADMIN'], permissions: ['admin:home:view'], featureFlag: 'admin.questions' } },
          { path: 'questions/new', name: 'admin-question-new', component: () => import('@/modules/admin/questions/views/AdminQuestionEditorView.vue'), meta: { appShell: 'admin', requiresAuth: true, permissions: ['question:create'], featureFlag: 'admin.questions' } },
          { path: 'questions/:questionId/versions/:versionId', name: 'admin-question-detail', component: () => import('@/modules/admin/questions/views/AdminQuestionDetailView.vue'), meta: { appShell: 'admin', requiresAuth: true, permissions: ['question:read'], featureFlag: 'admin.questions' } },
          { path: 'question-imports', name: 'admin-question-imports', component: () => import('@/modules/admin/questions/views/AdminQuestionImportsView.vue'), meta: { appShell: 'admin', requiresAuth: true, permissions: ['question:import'], featureFlag: 'admin.questions' } },
          { path: 'question-imports/:batchId', name: 'admin-question-import-detail', component: () => import('@/modules/admin/questions/views/AdminQuestionImportDetailView.vue'), meta: { appShell: 'admin', requiresAuth: true, permissions: ['question:import'], featureFlag: 'admin.questions' } },
        ],
      },
      { path: '/exam-simulator', name: 'exam-entry', component: () => import('@/features/exam-simulator/views/EntryView.vue'), meta: { appShell: 'exam', examStage: 'ENTRY', requiresQualification: true } },
      { path: '/exam-simulator/login/:entryCode', name: 'exam-login', component: () => import('@/features/exam-simulator/views/LoginView.vue'), props: (route) => ({ entryCode: route.params.entryCode as ExamEntryCode }), meta: { appShell: 'exam', examStage: 'LOGIN', requiresQualification: true } },
      { path: '/exam-simulator/session/:sessionId/candidate', name: 'exam-candidate', component: () => import('@/features/exam-simulator/views/CandidateView.vue'), meta: { appShell: 'exam', examStage: 'CANDIDATE' } },
      { path: '/exam-simulator/session/:sessionId/rules', name: 'exam-rules', component: () => import('@/features/exam-simulator/views/RulesView.vue'), meta: { appShell: 'exam', examStage: 'RULES' } },
      { path: '/exam-simulator/session/:sessionId/exam', name: 'exam-workspace', component: () => import('@/features/exam-simulator/views/ExamView.vue'), meta: { appShell: 'exam', examStage: 'EXAM' } },
    ],
  })

  router.beforeEach(async (to) => {
    if (to.meta.examStage) {
      const examStore = useExamSimulatorStore()
      await examStore.restoreRemoteSession()
      if (!routeSessionMatches(to, examStore.sessionId)) return '/exam-simulator'
      return resolveExamGuardRedirect(to.meta.examStage, examStore) ?? true
    }

    const authStore = useAuthStore()
    const requiresAuth = to.matched.some((record) => record.meta.requiresAuth)
    if (requiresAuth) {
      try {
        await authStore.initialize({ force: true })
      } catch {
        return '/500'
      }
      const originalTarget = to.redirectedFrom?.fullPath ?? to.fullPath
      const accessRedirect = resolvePlatformAccess(platformMeta(to), authStore, originalTarget)
      if (accessRedirect) return accessRedirect
    } else if (!authStore.initialized) {
      await authStore.initialize().catch(() => undefined)
    }
    if (
      authStore.isAuthenticated
      && authStore.roles.includes('STUDENT')
      && !authStore.preparationCompleted
      && to.name !== 'preparation-onboarding'
    ) {
      return '/onboarding/preparation'
    }
    if (to.matched.some((record) => record.meta.requiresQualification)) {
      const qualificationStore = useQualificationStore()
      if (qualificationStore.status === 'idle') {
        await qualificationStore.load({ anonymous: !authStore.isAuthenticated })
      }
    }
    return true
  })

  return router
}

export default createAppRouter()
