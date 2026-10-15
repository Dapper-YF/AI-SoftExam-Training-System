export type ProductCode = 'practice' | 'essay'

export interface WorkspaceNavigationItem {
  code: string
  label: string
  routePath: string
  icon: 'home' | 'library' | 'history' | 'edit' | 'report' | 'exam' | 'review' | 'resources'
}

export interface ProductNavigation {
  code: ProductCode
  label: string
  homePath: string
  items: WorkspaceNavigationItem[]
}
