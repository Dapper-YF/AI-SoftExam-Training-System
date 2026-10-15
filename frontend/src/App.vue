<script setup lang="ts">
import { onMounted, onUnmounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import AiAssistantDrawer from '@/modules/practice/components/AiAssistantDrawer.vue'
import { ref } from 'vue'

const route = useRoute()
const aiOpen = ref(false)
function openAiAssistant(): void { aiOpen.value = true }
const showAiAssistant = () => route.meta.appShell !== 'exam'
  && !['platform-login', 'platform-register', 'platform-forgot-password'].includes(String(route.name))
function syncExamShellClasses(): void {
  const isExamShell = route.meta.appShell === 'exam'
  const isDesktopExamStage = isExamShell
    && ['CANDIDATE', 'RULES', 'EXAM'].includes(String(route.meta.examStage ?? ''))
  document.body.classList.toggle('exam-app-active', isExamShell)
  document.body.classList.toggle('exam-app-desktop-stage', isDesktopExamStage)
}

const stop = watch(
  () => [route.meta.appShell, route.meta.examStage],
  syncExamShellClasses,
  { immediate: true },
)

onMounted(() => window.addEventListener('ai-assistant:open', openAiAssistant))

onUnmounted(() => {
  stop()
  window.removeEventListener('ai-assistant:open', openAiAssistant)
  document.body.classList.remove('exam-app-active')
  document.body.classList.remove('exam-app-desktop-stage')
})
</script>

<template>
  <RouterView />
  <AiAssistantDrawer v-if="showAiAssistant()" v-model="aiOpen" />
</template>
