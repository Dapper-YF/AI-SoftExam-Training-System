import type { ProductCode } from '@/types/navigation'

export type AsyncContentStatus = 'idle' | 'loading' | 'success' | 'empty' | 'error'
export type ProductAvailabilityState = 'available' | 'preview' | 'unavailable' | 'forbidden'

export interface ProductAvailability {
  state: ProductAvailabilityState
  canEnter: boolean
  label: string
}

export interface RecentTask {
  id: string
  title: string
  product: ProductCode
  description?: string
  updatedAt?: string
  routePath?: string
  progress?: string
  score?: string
}

export interface PlatformStat {
  id: string
  label: string
  value: string
}

export interface ModuleHomeSnapshot {
  recentTasks: RecentTask[]
  stats: PlatformStat[]
  sourceLabel?: string
}
