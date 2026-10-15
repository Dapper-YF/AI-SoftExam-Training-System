import { onBeforeUnmount, onMounted } from 'vue'
import { useExamSimulatorStore } from '../stores/exam-simulator'

export function useAuthoritativeClock(syncEveryMs = 15_000): void {
  const store = useExamSimulatorStore()
  let tickTimer: number | undefined
  let syncTimer: number | undefined

  onMounted(() => {
    store.updateClock()
    tickTimer = window.setInterval(() => store.updateClock(), 250)
    syncTimer = window.setInterval(() => {
      void store.syncClockState()
    }, syncEveryMs)
  })

  onBeforeUnmount(() => {
    if (tickTimer) window.clearInterval(tickTimer)
    if (syncTimer) window.clearInterval(syncTimer)
  })
}
