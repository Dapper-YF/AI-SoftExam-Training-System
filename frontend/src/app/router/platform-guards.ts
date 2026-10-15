import type { PlatformRole } from '@/modules/foundation/types'

export interface PlatformAccessMeta {
  requiresAuth?: boolean
  roles?: PlatformRole[]
  permissions?: string[]
  featureFlag?: string
}

export interface PlatformAccessContext {
  isAuthenticated: boolean
  roles: PlatformRole[]
  permissions: string[]
  featureFlags: Record<string, boolean>
}

export function resolvePlatformAccess(
  meta: PlatformAccessMeta,
  context: PlatformAccessContext,
  fullPath: string,
): string | null {
  if (meta.requiresAuth && !context.isAuthenticated) {
    return `/login?redirect=${encodeURIComponent(fullPath)}`
  }
  if (!context.isAuthenticated) return null
  if (meta.roles?.length && !meta.roles.some((role) => context.roles.includes(role))) {
    return '/403'
  }
  if (
    meta.permissions?.length &&
    !meta.permissions.every((permission) => context.permissions.includes(permission))
  ) {
    return '/403'
  }
  if (meta.featureFlag && context.featureFlags[meta.featureFlag] !== true) {
    return '/403'
  }
  return null
}
