import type { Subject } from '@/modules/foundation/types'
import type { ProductCode } from '@/types/navigation'
import type { ProductAvailability } from './types'

interface AvailabilityContext {
  isAuthenticated: boolean
  featureFlags: Record<string, boolean>
  permissions: string[]
  subjects: Subject[]
  essayPreviewEnabled: boolean
}

const requirements = {
  practice: { permission: 'practice:workspace:view', flag: 'practice.workspace', subject: 'COMPREHENSIVE' },
  essay: { permission: 'essay:workspace:view', flag: 'essay.workspace', subject: 'ESSAY' },
} as const

export function resolveProductAvailability(
  product: ProductCode,
  context: AvailabilityContext,
): ProductAvailability {
  const requirement = requirements[product]
  const hasSubjectScope = context.subjects.some((subject) =>
    subject.type === requirement.subject && subject.status === 'ENABLED')

  if (!context.isAuthenticated) {
    if (product === 'practice' && hasSubjectScope) {
      return { state: 'available', canEnter: true, label: '浏览工作区' }
    }
    return { state: 'forbidden', canEnter: false, label: '登录后使用' }
  }

  const hasScope = context.permissions.includes(requirement.permission) && hasSubjectScope

  if (!hasScope) return { state: 'forbidden', canEnter: false, label: '暂无权限' }
  if (context.featureFlags[requirement.flag]) return { state: 'available', canEnter: true, label: '进入工作区' }
  if (product === 'essay' && context.essayPreviewEnabled) {
    return { state: 'preview', canEnter: true, label: '开发预览' }
  }
  return { state: 'unavailable', canEnter: false, label: '服务不可用' }
}
