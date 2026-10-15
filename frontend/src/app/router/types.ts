import type { PlatformRole } from '@/modules/foundation/types'
import type { ProductCode } from '@/types/navigation'
import type { useExamSimulatorStore } from '@/features/exam-simulator/stores/exam-simulator'

export type ReturnTypeOfExamStore = ReturnType<typeof useExamSimulatorStore>
export type AppShell = 'public' | 'learning' | 'answer' | 'admin' | 'exam'

declare module 'vue-router' {
  interface RouteMeta {
    appShell?: AppShell
    examStage?: import('./guards').ExamRouteStage
    requiresAuth?: boolean
    requiresQualification?: boolean
    roles?: PlatformRole[]
    permissions?: string[]
    featureFlag?: string
    currentProduct?: ProductCode
    pageTitle?: string
  }
}
