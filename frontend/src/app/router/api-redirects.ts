import type { RouteLocationRaw } from 'vue-router'

export function platformUnauthorizedTarget(currentPath: string): RouteLocationRaw {
  return {
    path: '/login',
    query: currentPath === '/login' ? {} : { redirect: currentPath },
  }
}

export function examUnauthorizedTarget(): RouteLocationRaw {
  return '/exam-simulator'
}
