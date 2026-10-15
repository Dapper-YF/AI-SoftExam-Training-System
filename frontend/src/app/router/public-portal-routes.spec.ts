import { describe, expect, it } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import { createAppRouter } from './index'

describe('public portal route boundaries', () => {
  it('uses the public top-navigation shell for anonymous catalog browsing', () => {
    const router = createAppRouter(createMemoryHistory())
    const home = router.resolve('/')
    const qualifications = router.resolve('/qualifications')
    const practice = router.resolve('/practice?mode=CHAPTER')

    for (const route of [home, qualifications, practice]) {
      expect(route.matched.some((record) => record.meta.appShell === 'public')).toBe(true)
      expect(route.matched.some((record) => record.meta.requiresAuth)).toBe(false)
    }
    expect(practice.matched.some((record) => record.meta.requiresQualification)).toBe(true)
  })

  it('keeps attempt, report and admin workspaces behind their dedicated shells and auth guards', () => {
    const router = createAppRouter(createMemoryHistory())
    const attempt = router.resolve('/app/practice/attempts/attempt-1')
    const reports = router.resolve('/app/practice/reports')
    const admin = router.resolve('/admin/home')

    expect(attempt.matched.some((record) => record.meta.requiresAuth)).toBe(true)
    expect(attempt.meta.appShell).toBe('answer')
    expect(reports.matched.some((record) => record.meta.requiresAuth)).toBe(true)
    expect(reports.meta.appShell).toBe('learning')
    expect(admin.matched.some((record) => record.meta.appShell === 'admin')).toBe(true)
    expect(admin.matched.some((record) => record.meta.requiresAuth)).toBe(true)
  })

  it('covers personal, essay, admin and exam representatives without sharing the public catalog shell', () => {
    const router = createAppRouter(createMemoryHistory())

    expect(router.resolve('/app/profile').meta.appShell).toBe('learning')
    expect(router.resolve('/app/essay/editor').meta.appShell).toBe('learning')
    expect(router.resolve('/admin/questions').meta.appShell).toBe('admin')
    expect(router.resolve('/exam-simulator/session/session-1/exam').meta.appShell).toBe('exam')
  })

  it('keeps auth aliases and legacy deep links resolvable', () => {
    const router = createAppRouter(createMemoryHistory())

    expect(router.resolve('/auth/login').matched.at(-1)?.name).toBe('platform-login')
    expect(router.resolve('/app/modules').matched.at(-1)?.name).toBe('legacy-module-home')
    expect(router.resolve('/app/practice/session/attempt-1?question=2').matched.at(-1)?.name).toBe('legacy-practice-session')
  })
})
