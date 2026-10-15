import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiClientError, platformApiClient } from '@/api/client'
import { HttpPlatformHomeGateway } from './http-platform-home.gateway'

describe('HTTP platform home gateway', () => {
  afterEach(() => vi.restoreAllMocks())

  it('returns a real empty state when the optional aggregation endpoint is absent', async () => {
    vi.spyOn(platformApiClient, 'get').mockRejectedValue(
      new ApiClientError('请求的资源不存在', 404, 'NOT_FOUND', 'trace-home-gap'),
    )

    await expect(new HttpPlatformHomeGateway().getModuleHomeSnapshot()).resolves.toEqual({
      recentTasks: [],
      stats: [],
      sourceLabel: '真实学习数据尚未接入',
    })
  })

  it('does not hide unexpected server failures', async () => {
    vi.spyOn(platformApiClient, 'get').mockRejectedValue(
      new ApiClientError('系统暂时不可用', 503, 'INTERNAL_ERROR', 'trace-home-503'),
    )

    await expect(new HttpPlatformHomeGateway().getModuleHomeSnapshot()).rejects.toMatchObject({
      status: 503,
      traceId: 'trace-home-503',
    })
  })
})
