import type { ModuleHomeSnapshot } from '../types'

export interface PlatformHomeGateway {
  getModuleHomeSnapshot(): Promise<ModuleHomeSnapshot>
}
