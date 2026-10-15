<script setup lang="ts">
import { computed } from 'vue'
import { ArrowLeft, DataAnalysis } from '@element-plus/icons-vue'
import PracticeSessionView from '@/modules/practice/views/PracticeSessionView.vue'
import { usePracticeStore } from '@/stores/practice'
import { useQualificationStore } from '@/stores/qualification'
import { readPracticeSessionPresentation } from '@/modules/practice/session-presentation'

const practiceStore = usePracticeStore()
const qualificationStore = useQualificationStore()
const practiceReturnPath = computed(() => {
  const presentation = readPracticeSessionPresentation(practiceStore.attempt?.id ?? '')
  return presentation?.returnPath ?? '/practice'
})
const currentQualificationName = computed(() =>
  qualificationStore.selectedQualification?.name
  ?? (practiceStore.attempt?.qualificationName && !/^\d+$/.test(practiceStore.attempt.qualificationName)
    ? practiceStore.attempt.qualificationName
    : '当前练习资格'),
)
</script>

<template>
  <div class="answer-shell" data-testid="answer-layout">
    <header class="answer-shell__header">
      <RouterLink class="answer-shell__brand" to="/" aria-label="AI高项培训系统首页">
        <span aria-hidden="true"><el-icon><DataAnalysis /></el-icon></span>
        <strong>AI高项培训系统</strong>
      </RouterLink>
      <RouterLink class="answer-shell__exit" :to="practiceReturnPath" data-testid="answer-back-catalog">
        <el-icon><ArrowLeft /></el-icon><span>返回题库</span>
      </RouterLink>
      <p data-testid="answer-qualification-name">{{ currentQualificationName }}</p>
    </header>
    <main class="answer-shell__content"><PracticeSessionView /></main>
  </div>
</template>

<style scoped>
.answer-shell { min-width: 0; max-width: 100%; min-height: 100vh; overflow-x: clip; background: #f5f7fa; }
.answer-shell__header { display: grid; min-height: 56px; padding: 0 22px; align-items: center; grid-template-columns: minmax(220px, 1fr) auto minmax(220px, 1fr); gap: 16px; border-bottom: 1px solid #d7e1ef; background: #0d469a; color: #fff; }
.answer-shell__brand, .answer-shell__exit { display: inline-flex; width: fit-content; align-items: center; gap: 9px; color: #fff; text-decoration: none; white-space: nowrap; }
.answer-shell__brand > span { display: grid; width: 32px; height: 32px; place-items: center; border: 2px solid rgb(255 255 255 / 88%); border-radius: 50%; }
.answer-shell__exit { min-height: 36px; padding: 0 12px; border: 1px solid rgb(255 255 255 / 62%); border-radius: 6px; }
.answer-shell__header p { margin: 0; justify-self: end; color: rgb(255 255 255 / 78%); font-size: 12px; }
.answer-shell__content { min-width: 0; }

@media (max-width: 767px) {
  .answer-shell__header { min-height: 54px; padding: 0 12px; grid-template-columns: minmax(0, 1fr) auto; gap: 8px; }
  .answer-shell__brand strong { overflow: hidden; text-overflow: ellipsis; }
  .answer-shell__header p { display: none; }
  .answer-shell__exit span { display: none; }
  .answer-shell__exit { width: 38px; padding: 0; justify-content: center; }
}
</style>
