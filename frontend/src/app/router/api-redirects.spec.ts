import { describe, expect, it } from 'vitest'
import { examUnauthorizedTarget, platformUnauthorizedTarget } from './api-redirects'

describe('API unauthorized redirect targets', () => {
  it('preserves a platform address when redirecting to login', () => {
    expect(platformUnauthorizedTarget('/app/home?tab=plan')).toEqual({
      path: '/login',
      query: { redirect: '/app/home?tab=plan' },
    })
  })

  it('does not create a login redirect loop', () => {
    expect(platformUnauthorizedTarget('/login')).toEqual({ path: '/login', query: {} })
  })

  it('returns an exam 401 to the standalone exam entry', () => {
    expect(examUnauthorizedTarget()).toBe('/exam-simulator')
  })
})
