import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { setPlatformHomeGatewayForTests } from './api/gateway'
import { usePlatformHomeStore } from './stores/platform-home'

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

describe('platform home store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('tracks loading and loaded recent-task states', async () => {
    const pending = deferred<{
      recentTasks: Array<{ id: string; title: string; product: 'practice' | 'essay' }>
      stats: Array<{ id: string; label: string; value: string }>
    }>()
    setPlatformHomeGatewayForTests({
      getModuleHomeSnapshot: () => pending.promise,
    })
    const store = usePlatformHomeStore()
    const loadPromise = store.load()

    expect(store.recentTasksStatus).toBe('loading')

    pending.resolve({
      recentTasks: [{ id: 'task-1', title: '第 5 章 进度管理', product: 'practice' }],
      stats: [{ id: 'accuracy', label: '正确率', value: '68%' }],
    })
    await loadPromise

    expect(store.recentTasksStatus).toBe('success')
    expect(store.recentTasks).toHaveLength(1)
    expect(store.stats).toHaveLength(1)
  })

  it('surfaces an explicit empty state when no recent tasks are available', async () => {
    setPlatformHomeGatewayForTests({
      getModuleHomeSnapshot: async () => ({
        recentTasks: [],
        stats: [],
      }),
    })
    const store = usePlatformHomeStore()

    await store.load()

    expect(store.recentTasksStatus).toBe('empty')
    expect(store.recentTasks).toEqual([])
  })

  it('captures error details instead of fabricating module-home data', async () => {
    setPlatformHomeGatewayForTests({
      getModuleHomeSnapshot: async () => {
        const error = new Error('模块首页数据加载失败')
        ;(error as Error & { traceId?: string }).traceId = 'trace-platform-home'
        throw error
      },
    })
    const store = usePlatformHomeStore()

    await store.load()

    expect(store.recentTasksStatus).toBe('error')
    expect(store.errorMessage).toBe('模块首页数据加载失败')
    expect(store.errorTraceId).toBe('trace-platform-home')
  })
})
