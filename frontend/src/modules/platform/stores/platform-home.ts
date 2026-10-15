import { ref } from 'vue'
import { defineStore } from 'pinia'
import { toSafeUserMessage } from '@/api/client'
import { getPlatformHomeGateway } from '../api/gateway'
import type { AsyncContentStatus, PlatformStat, RecentTask } from '../types'

export const usePlatformHomeStore = defineStore('platform-home', () => {
  const recentTasks = ref<RecentTask[]>([])
  const stats = ref<PlatformStat[]>([])
  const sourceLabel = ref('')
  const recentTasksStatus = ref<AsyncContentStatus>('idle')
  const errorMessage = ref('')
  const errorTraceId = ref<string>()

  async function load(): Promise<void> {
    recentTasksStatus.value = 'loading'
    errorMessage.value = ''
    errorTraceId.value = undefined
    try {
      const snapshot = await getPlatformHomeGateway().getModuleHomeSnapshot()
      recentTasks.value = snapshot.recentTasks
      stats.value = snapshot.stats
      sourceLabel.value = snapshot.sourceLabel ?? ''
      recentTasksStatus.value = snapshot.recentTasks.length ? 'success' : 'empty'
    } catch (error) {
      recentTasks.value = []
      stats.value = []
      sourceLabel.value = ''
      recentTasksStatus.value = 'error'
      errorMessage.value = '模块首页数据加载失败'
      errorTraceId.value = error instanceof Error
        ? (error as Error & { traceId?: string }).traceId
        : undefined
    }
  }

  function clear(status: AsyncContentStatus): void {
    recentTasks.value = []
    stats.value = []
    sourceLabel.value = ''
    recentTasksStatus.value = status
    errorMessage.value = ''
    errorTraceId.value = undefined
  }

  function showAnonymous(): void {
    clear('empty')
  }

  function reset(): void {
    clear('idle')
  }

  return { recentTasks, stats, sourceLabel, recentTasksStatus, errorMessage, errorTraceId, load, showAnonymous, reset }
})
