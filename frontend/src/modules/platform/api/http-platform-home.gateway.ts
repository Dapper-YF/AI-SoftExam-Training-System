import { ApiClientError, platformApiClient, unwrapApiResponse } from '@/api/client'
import type { ModuleHomeSnapshot } from '../types'
import type { PlatformHomeGateway } from './platform-home.gateway'

export class HttpPlatformHomeGateway implements PlatformHomeGateway {
  async getModuleHomeSnapshot(): Promise<ModuleHomeSnapshot> {
    try {
      return await unwrapApiResponse(platformApiClient.get('/platform/module-home'))
    } catch (error) {
      if (error instanceof ApiClientError && error.status === 404) {
        return {
          recentTasks: [],
          stats: [],
          sourceLabel: '真实学习数据尚未接入',
        }
      }
      throw error
    }
  }
}
