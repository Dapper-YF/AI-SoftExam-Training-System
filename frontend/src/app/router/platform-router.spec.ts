import { createPinia, setActivePinia } from 'pinia'
import { mount } from '@vue/test-utils'
import { h } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, RouterView } from 'vue-router'
import { ApiClientError } from '@/api/client'
import { setPlatformAuthGatewayForTests, setPlatformFoundationGatewayForTests } from '@/modules/foundation/api/gateway'
import type { PlatformAuthGateway } from '@/modules/foundation/api/platform-auth.gateway'
import type { PlatformFoundationGateway } from '@/modules/foundation/api/platform-foundation.gateway'
import type { PlatformAuthContext, Qualification, Subject } from '@/modules/foundation/types'
import { useAuthStore } from '@/stores/auth'
import { useQualificationStore } from '@/stores/qualification'
import { createAppRouter } from './index'

const practiceAttemptDetailProbe = vi.hoisted(() => vi.fn())

vi.mock('@/modules/practice/views/PracticeSessionView.vue', () => ({
  default: {
    name: 'PracticeSessionViewProbe',
    setup() {
      practiceAttemptDetailProbe()
      return () => null
    },
  },
}))

const studentContext: PlatformAuthContext = {
  user: { id: 'student-1', username: 'student', displayName: '张同学', status: 'ENABLED' },
  roles: ['STUDENT'],
  permissions: ['student:home:view', 'platform:module-home:view', 'practice:workspace:view', 'essay:workspace:view'],
  featureFlags: {
    'student.portal': true,
    'practice.workspace': true,
    'essay.workspace': false,
  },
  menus: [],
}

const adminQuestionContext: PlatformAuthContext = {
  user: { id: 'admin-question-1', username: 'question-admin', displayName: '题库管理员', status: 'ENABLED' },
  roles: ['ADMIN'],
  permissions: [
    'admin:home:view',
    'question:read',
    'question:create',
    'question:update',
    'question:submit-review',
    'question:review',
    'question:publish',
    'question:admin',
    'question:import',
  ],
  featureFlags: {
    'admin.portal': true,
    'admin.questions': true,
  },
  menus: [
    { code: 'admin.home', name: '管理首页', routePath: '/admin/home' },
    { code: 'admin.questions', name: '题库管理', routePath: '/admin/questions' },
  ],
}

const reviewerQuestionContext: PlatformAuthContext = {
  user: { id: 'reviewer-1', username: 'question_reviewer', displayName: '题库验收审核发布人', status: 'ENABLED' },
  roles: ['QUESTION_REVIEWER', 'QUESTION_PUBLISHER'],
  permissions: ['question:read', 'question:review', 'question:publish'],
  featureFlags: { 'admin.questions': true },
  menus: [],
}

const qualifications: Qualification[] = [
  {
    id: 'qualification-high-ipmp',
    code: 'IPMP',
    name: '信息系统项目管理师',
    levelCode: 'ADVANCED',
    status: 'ENABLED',
  },
]

const subjects: Subject[] = [
  {
    id: 'ipmp-knowledge',
    qualificationId: 'qualification-high-ipmp',
    code: 'COMPREHENSIVE',
    name: '综合知识',
    type: 'COMPREHENSIVE',
    status: 'ENABLED',
  },
  {
    id: 'ipmp-essay',
    qualificationId: 'qualification-high-ipmp',
    code: 'ESSAY',
    name: '论文',
    type: 'ESSAY',
    status: 'ENABLED',
  },
]

function authGateway(overrides: Partial<PlatformAuthGateway> = {}): PlatformAuthGateway {
  return {
    getCapabilities: async () => ({ usernamePassword: true, mobilePassword: true, emailPassword: false, wechatQr: false, registrationSms: false, registrationVerificationRequired: true, passwordResetSms: false }),
    requestRegistrationChallenge: async () => ({ challengeId: 'challenge-1', expiresAt: '2026-07-16T10:10:00+08:00' }),
    register: async () => ({ userId: 'registered-user', username: 'registered-user', registrationStatus: 'REGISTERED', createdAt: '2026-07-16T10:00:00+08:00' }),
    login: async () => ({ ...studentContext, accessExpiresAt: '2026-07-16T10:30:00+08:00', refreshExpiresAt: '2026-07-23T10:00:00+08:00' }),
    refresh: async () => ({ accessExpiresAt: '2026-07-16T10:30:00+08:00', refreshExpiresAt: '2026-07-23T10:00:00+08:00' }),
    logout: async () => undefined,
    getCurrentUser: async () => studentContext,
    getProfile: async () => ({
      userId: studentContext.user.id,
      username: studentContext.user.username,
      displayName: studentContext.user.displayName,
      maskedMobile: null,
      maskedEmail: null,
      status: studentContext.user.status,
      version: 0,
      updatedAt: '2026-07-16T10:00:00+08:00',
    }),
    updateProfile: async (request) => ({
      userId: studentContext.user.id,
      username: studentContext.user.username,
      displayName: request.displayName,
      maskedMobile: null,
      maskedEmail: null,
      status: studentContext.user.status,
      version: request.version + 1,
      updatedAt: '2026-07-16T10:01:00+08:00',
    }),
    requestPasswordResetChallenge: async () => ({ challengeId: 'reset-challenge-1', expiresAt: '2026-07-16T10:10:00+08:00' }),
    resetPassword: async () => undefined,
    getDemoAccounts: () => [],
    ...overrides,
  }
}

function adminQuestionAuthGateway(): PlatformAuthGateway {
  return {
    getCapabilities: async () => ({ usernamePassword: true, mobilePassword: true, emailPassword: false, wechatQr: false, registrationSms: false, registrationVerificationRequired: true, passwordResetSms: false }),
    requestRegistrationChallenge: async () => ({ challengeId: 'challenge-1', expiresAt: '2026-07-16T10:10:00+08:00' }),
    register: async () => ({ userId: 'registered-user', username: 'registered-user', registrationStatus: 'REGISTERED', createdAt: '2026-07-16T10:00:00+08:00' }),
    login: async () => ({ ...adminQuestionContext, accessExpiresAt: '2026-07-16T10:30:00+08:00', refreshExpiresAt: '2026-07-23T10:00:00+08:00' }),
    refresh: async () => ({ accessExpiresAt: '2026-07-16T10:30:00+08:00', refreshExpiresAt: '2026-07-23T10:00:00+08:00' }),
    logout: async () => undefined,
    getCurrentUser: async () => adminQuestionContext,
    getProfile: async () => ({
      userId: adminQuestionContext.user.id,
      username: adminQuestionContext.user.username,
      displayName: adminQuestionContext.user.displayName,
      maskedMobile: null,
      maskedEmail: null,
      status: adminQuestionContext.user.status,
      version: 0,
      updatedAt: '2026-07-16T10:00:00+08:00',
    }),
    updateProfile: async (request) => ({
      userId: adminQuestionContext.user.id,
      username: adminQuestionContext.user.username,
      displayName: request.displayName,
      maskedMobile: null,
      maskedEmail: null,
      status: adminQuestionContext.user.status,
      version: request.version + 1,
      updatedAt: '2026-07-16T10:01:00+08:00',
    }),
    requestPasswordResetChallenge: async () => ({ challengeId: 'reset-challenge-1', expiresAt: '2026-07-16T10:10:00+08:00' }),
    resetPassword: async () => undefined,
    getDemoAccounts: () => [],
  }
}

function reviewerQuestionAuthGateway(): PlatformAuthGateway {
  return authGateway({
    login: async () => reviewerQuestionContext,
    getCurrentUser: async () => reviewerQuestionContext,
  })
}

function foundationGateway(overrides: Partial<PlatformFoundationGateway> = {}): PlatformFoundationGateway {
  return {
    listQualifications: async () => qualifications,
    listSubjects: async () => subjects,
    listChapterNodes: async () => [],
    listSyllabusNodes: async () => [],
    ...overrides,
  }
}

async function flushRouter(): Promise<void> {
  await Promise.resolve()
  await new Promise((resolve) => setTimeout(resolve, 0))
}

describe('platform route table', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    sessionStorage.clear()
    setPlatformAuthGatewayForTests(authGateway())
    setPlatformFoundationGatewayForTests(foundationGateway())
  })

  it('keeps platform, status and exam routes in one independent router', () => {
    const router = createAppRouter(createMemoryHistory())
    const routes = router.getRoutes()
    const paths = routes.map((route) => route.path)

    expect(paths).toContain('/login')
    expect(paths).toContain('/register')
    expect(paths).toContain('/forgot-password')
    expect(paths).toContain('/app/home')
    expect(paths).toContain('/app/modules')
    expect(paths).toContain('/app/practice/home')
    expect(paths).toContain('/app/practice/list')
    expect(paths).toContain('/app/essay/home')
    expect(paths).toContain('/app/practice/attempts/:attemptId')
    expect(paths).toContain('/admin/home')
    expect(paths).toContain('/admin/questions')
    expect(paths).toContain('/admin/questions/new')
    expect(paths).toContain('/admin/questions/:questionId/versions/:versionId')
    expect(paths).toContain('/admin/question-imports')
    expect(paths).toContain('/admin/question-imports/:batchId')
    expect(paths).toContain('/403')
    expect(paths).toContain('/404')
    expect(paths).toContain('/500')
    expect(paths).toContain('/exam-simulator')
  })

  it('resolves every user-facing page without falling through to the 404 route', () => {
    const router = createAppRouter(createMemoryHistory())
    const pagePaths = [
      '/',
      '/login',
      '/register',
      '/forgot-password',
      '/app',
      '/app/home',
      '/app/modules',
      '/app/practice',
      '/app/practice/home',
      '/app/practice/start',
      '/app/practice/list',
      '/app/practice/review',
      '/app/practice/reports',
      '/app/practice/resources',
      '/app/practice/library',
      '/app/practice/history',
      '/app/practice/attempts/attempt-1',
      '/app/practice/session/attempt-1',
      '/app/essay',
      '/app/essay/home',
      '/app/essay/editor',
      '/app/essay/reports',
      '/admin',
      '/admin/home',
      '/admin/questions',
      '/admin/questions/new',
      '/admin/questions/101/versions/501',
      '/admin/question-imports',
      '/admin/question-imports/42',
      '/403',
      '/404',
      '/500',
      '/exam-simulator',
      '/exam-simulator/login/KNOWLEDGE_CASE',
      '/exam-simulator/session/session-1/candidate',
      '/exam-simulator/session/session-1/rules',
      '/exam-simulator/session/session-1/exam',
    ]

    for (const path of pagePaths) {
      const resolved = router.resolve(path)
      expect(resolved.matched.length, path).toBeGreaterThan(0)
      expect(resolved.matched.at(-1)?.name, path).not.toBe('not-found')
    }
  })

  it('redirects platform shell roots and legacy practice paths to usable pages', () => {
    const router = createAppRouter(createMemoryHistory())

    expect(router.resolve('/app').redirectedFrom).toBeUndefined()
    expect(typeof router.getRoutes().find((route) => route.path === '/app')?.redirect).toBe('function')
    expect(router.getRoutes().find((route) => route.path === '/admin')?.redirect).toBe('/admin/home')
    expect(typeof router.getRoutes().find((route) => route.path === '/app/practice/list')?.redirect).toBe('function')
  })

  it('marks exam routes as fullscreen instead of using a platform layout', () => {
    const router = createAppRouter(createMemoryHistory())
    const examRoutes = router.getRoutes().filter((route) => route.path.startsWith('/exam-simulator'))

    expect(examRoutes.length).toBeGreaterThan(0)
    expect(examRoutes.every((route) => route.meta.appShell === 'exam')).toBe(true)
  })

  it('uses a real 404 page for unknown addresses', () => {
    const router = createAppRouter(createMemoryHistory())
    const catchAll = router.getRoutes().find((route) => route.path.includes(':pathMatch'))

    expect(catchAll?.redirect).toBeUndefined()
    expect(catchAll?.name).toBe('not-found')
  })

  it('keeps module and practice browsing public without auth bootstrap', async () => {
    const getCurrentUser = vi.fn(async () => {
      throw new ApiClientError('no session', 401, 'AUTH_SESSION_EXPIRED')
    })
    const refresh = vi.fn(async () => {
      throw new ApiClientError('no refresh', 401, 'AUTH_SESSION_EXPIRED')
    })
    setPlatformAuthGatewayForTests(authGateway({
      getCurrentUser,
      refresh,
    } as Partial<PlatformAuthGateway>))
    const router = createAppRouter(createMemoryHistory())

    const publicAndLegacyTargets: Array<[string, string]> = [
      ['/', '/'],
      ['/practice', '/practice'],
      ['/app/modules', '/'],
      ['/app/practice/home', '/practice'],
      ['/app/practice/start?mode=PAST_EXAM', '/practice?mode=PAST_EXAM'],
      ['/app/practice/resources', '/resources'],
    ]
    for (const [target, expected] of publicAndLegacyTargets) {
      await router.push(target)
      await flushRouter()
      expect(router.currentRoute.value.fullPath).toBe(expected)
    }
    expect(getCurrentUser).not.toHaveBeenCalled()
    expect(refresh).not.toHaveBeenCalled()
  })

  it('keeps the root path public for browsing before protected actions', async () => {
    setPlatformAuthGatewayForTests(authGateway({
      getCurrentUser: async () => {
        throw new ApiClientError('backend unavailable', 503, 'SERVICE_UNAVAILABLE')
      },
    } as Partial<PlatformAuthGateway>))
    const router = createAppRouter(createMemoryHistory())

    await router.push('/')
    await flushRouter()

    expect(router.currentRoute.value.fullPath).toBe('/')
  })

  it('keeps public pages available when session bootstrap fails and redirects only protected actions', async () => {
    const getCurrentUser = vi.fn(async () => { throw new Error('backend unavailable') })
    const refresh = vi.fn(async () => { throw new Error('backend unavailable') })
    setPlatformAuthGatewayForTests(authGateway({ getCurrentUser, refresh }))
    const router = createAppRouter(createMemoryHistory())

    await router.push('/resources')
    await flushRouter()
    expect(router.currentRoute.value.fullPath).toBe('/resources')
    expect(getCurrentUser).not.toHaveBeenCalled()

    await router.push('/app/practice/attempts/attempt-1')
    await flushRouter()
    expect(router.currentRoute.value.path).toBe('/500')
  })

  it.each([
    '/app/practice/review',
    '/app/practice/reports',
    '/app/practice/attempts/attempt-1',
    '/app/practice/session/attempt-1?question=2',
    '/app/practice/history?status=COMPLETED',
    '/app/practice/mistakes?subject=subject-1',
    '/app/practice/favorites?page=2',
    '/admin/home',
  ])('redirects anonymous protected action %s to login with its original target', async (target) => {
    setPlatformAuthGatewayForTests(authGateway({
      getCurrentUser: async () => { throw new ApiClientError('expired', 401, 'AUTH_SESSION_EXPIRED') },
      refresh: async () => { throw new ApiClientError('expired', 401, 'AUTH_SESSION_EXPIRED') },
    }))
    const router = createAppRouter(createMemoryHistory())

    await router.push(target)
    await flushRouter()

    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe(target)
  })

  it('revalidates stale client auth before mounting a protected attempt route', async () => {
    const router = createAppRouter(createMemoryHistory())
    const authStore = useAuthStore()
    await authStore.login({ identifier: 'student', password: 'password' })
    expect(authStore.isAuthenticated).toBe(true)

    const getCurrentUser = vi.fn(async () => {
      throw new ApiClientError('expired', 401, 'AUTH_SESSION_EXPIRED')
    })
    const refresh = vi.fn(async () => {
      throw new ApiClientError('expired', 401, 'AUTH_SESSION_EXPIRED')
    })
    setPlatformAuthGatewayForTests(authGateway({ getCurrentUser, refresh }))
    practiceAttemptDetailProbe.mockClear()

    await router.push('/app/practice/attempts/7?from=history')
    await router.isReady()
    const wrapper = router.currentRoute.value.name === 'practice-attempt'
      ? mount({ render: () => h(RouterView) }, { global: { plugins: [router] } })
      : undefined
    await flushRouter()

    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe('/app/practice/attempts/7?from=history')
    expect(getCurrentUser).toHaveBeenCalledTimes(1)
    expect(practiceAttemptDetailProbe).not.toHaveBeenCalled()
    wrapper?.unmount()
  })

  it('keeps an anonymous public workspace in place when the protected foundation endpoint returns 401', async () => {
    const getCurrentUser = vi.fn()
    const refresh = vi.fn()
    setPlatformAuthGatewayForTests(authGateway({ getCurrentUser, refresh }))
    setPlatformFoundationGatewayForTests(foundationGateway({
      listQualifications: async () => {
        throw new ApiClientError('登录状态已失效，请重新登录', 401, 'AUTH_SESSION_EXPIRED')
      },
    }))
    const router = createAppRouter(createMemoryHistory())

    await router.push('/app/practice/home')
    await flushRouter()

    expect(router.currentRoute.value.fullPath).toBe('/practice')
    expect(getCurrentUser).not.toHaveBeenCalled()
    expect(refresh).not.toHaveBeenCalled()
    expect(useQualificationStore().errorMessage).toBe('公开资格与题库目录暂不可用，请稍后重试')
  })

  it('routes transient auth bootstrap failures to the safe server-error page', async () => {
    setPlatformAuthGatewayForTests(authGateway({
      getCurrentUser: async () => {
        throw new ApiClientError('backend unavailable', 503, 'SERVICE_UNAVAILABLE')
      },
    } as Partial<PlatformAuthGateway>))
    const router = createAppRouter(createMemoryHistory())

    await router.push('/app/profile')
    await flushRouter()

    expect(router.currentRoute.value.fullPath).toBe('/500')
  })

  it('keeps login public when users visit it directly', async () => {
    const router = createAppRouter(createMemoryHistory())

    await router.push('/login')
    await flushRouter()

    expect(router.currentRoute.value.fullPath).toBe('/login')
  })

  it('redirects retired home and practice-list URLs to the P1 public routes', async () => {
    const router = createAppRouter(createMemoryHistory())

    await router.push('/app/home')
    await flushRouter()
    expect(router.currentRoute.value.fullPath).toBe('/')

    await router.push('/app/practice/list?mode=PAST_EXAM')
    await flushRouter()
    expect(router.currentRoute.value.fullPath).toBe('/practice?mode=PAST_EXAM')
  })

  it('keeps browser back and forward stable across canonicalized legacy entries', async () => {
    const router = createAppRouter(createMemoryHistory())

    await router.push('/app/practice/home')
    await flushRouter()
    await router.push('/app/modules')
    await flushRouter()

    router.back()
    await flushRouter()
    expect(router.currentRoute.value.fullPath).toBe('/practice')

    router.forward()
    await flushRouter()
    expect(router.currentRoute.value.fullPath).toBe('/')
  })

  it('guards admin question management with explicit question permissions', async () => {
    setPlatformAuthGatewayForTests(adminQuestionAuthGateway())
    const router = createAppRouter(createMemoryHistory())

    await router.push('/admin/questions')
    await flushRouter()

    expect(router.currentRoute.value.fullPath).toBe('/admin/questions')
    const questionRoute = router.getRoutes().find((route) => route.path === '/admin/questions')
    expect(questionRoute?.meta.permissions).toContain('question:read')
    expect(questionRoute?.meta.featureFlag).toBe('admin.questions')
  })

  it('lets the dedicated reviewer enter the question workspace but not the author-only routes', async () => {
    setPlatformAuthGatewayForTests(reviewerQuestionAuthGateway())
    const router = createAppRouter(createMemoryHistory())

    await router.push('/admin/questions')
    await flushRouter()
    expect(router.currentRoute.value.fullPath).toBe('/admin/questions')

    await router.push('/admin/questions/101/versions/501')
    await flushRouter()
    expect(router.currentRoute.value.fullPath).toBe('/admin/questions/101/versions/501')

    await router.push('/admin/questions/new')
    await flushRouter()
    expect(router.currentRoute.value.fullPath).toBe('/403')
  })

  it('redirects the admin shell root to the first accessible admin page', async () => {
    setPlatformAuthGatewayForTests(adminQuestionAuthGateway())
    const router = createAppRouter(createMemoryHistory())

    await router.push('/admin')
    await flushRouter()

    expect(router.currentRoute.value.fullPath).toBe('/admin/home')
  })
})
