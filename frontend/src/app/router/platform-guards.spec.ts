import { describe, expect, it } from 'vitest'
import { resolvePlatformAccess, type PlatformAccessContext } from './platform-guards'
import { adminShellMenus, sanitizePlatformMenus } from './platform-route-whitelist'

const authenticated: PlatformAccessContext = {
  isAuthenticated: true,
  roles: ['STUDENT'],
  permissions: ['student:home:view', 'exam-simulator:enter'],
  featureFlags: { 'student.portal': true, 'exam.simulator': true },
}

describe('platform route access', () => {
  it('keeps the requested address when redirecting an anonymous user', () => {
    expect(
      resolvePlatformAccess(
        { requiresAuth: true },
        { ...authenticated, isAuthenticated: false },
        '/app/home?tab=plan',
      ),
    ).toBe('/login?redirect=%2Fapp%2Fhome%3Ftab%3Dplan')
  })

  it('blocks role, permission and feature flag mismatches', () => {
    expect(
      resolvePlatformAccess({ requiresAuth: true, roles: ['ADMIN'] }, authenticated, '/admin/home'),
    ).toBe('/403')
    expect(
      resolvePlatformAccess(
        { requiresAuth: true, permissions: ['student:profile:edit'] },
        authenticated,
        '/app/profile',
      ),
    ).toBe('/403')
    expect(
      resolvePlatformAccess(
        { requiresAuth: true, featureFlag: 'essay.enabled' },
        authenticated,
        '/app/essay',
      ),
    ).toBe('/403')
  })

  it('allows a route only when every declared requirement passes', () => {
    expect(
      resolvePlatformAccess(
        {
          requiresAuth: true,
          roles: ['STUDENT'],
          permissions: ['student:home:view'],
          featureFlag: 'student.portal',
        },
        authenticated,
        '/app/home',
      ),
    ).toBeNull()
  })
})

describe('platform menu whitelist', () => {
  it('keeps student functions out of the admin shell even for a dual-role account', () => {
    expect(adminShellMenus([
      { code: 'admin.home', name: '管理首页', routePath: '/admin/home' },
      { code: 'admin.questions', name: '题库管理', routePath: '/admin/questions' },
      { code: 'exam.simulator', name: '机考模拟', routePath: '/exam-simulator' },
      { code: 'practice.start', name: '刷题练习', routePath: '/app/practice/start' },
      { code: 'student.home', name: '学习首页', routePath: '/app/home' },
    ]).map((menu) => menu.code)).toEqual(['admin.home', 'admin.questions'])
  })

  it('drops arbitrary backend routes and disabled features', () => {
    const menus = sanitizePlatformMenus(
      [
        { code: 'student.home', name: '首页', routePath: '/app/home' },
        { code: 'exam.simulator', name: '机考模拟', routePath: '/exam-simulator' },
        { code: 'unsafe.remote', name: '任意组件', routePath: '/remote-component' },
      ],
      authenticated,
    )

    expect(menus.map((menu) => menu.code)).toEqual(['student.home', 'exam.simulator'])
  })

  it('adds the local question-management route for an authorized reviewer without a MENU row', () => {
    const menus = sanitizePlatformMenus([], {
      isAuthenticated: true,
      roles: ['QUESTION_REVIEWER', 'QUESTION_PUBLISHER'],
      permissions: ['question:read', 'question:review', 'question:publish'],
      featureFlags: { 'admin.questions': true },
    })

    expect(menus).toEqual([{
      code: 'admin.questions',
      name: '题库管理',
      routePath: '/admin/questions',
      icon: 'Collection',
    }, {
      code: 'admin.practice-reviews',
      name: '主观题评分',
      routePath: '/admin/practice-reviews',
      icon: 'review',
    }])
  })
})
