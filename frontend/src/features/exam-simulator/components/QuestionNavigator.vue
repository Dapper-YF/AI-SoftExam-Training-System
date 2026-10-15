<script setup lang="ts">
import { computed } from 'vue'
import type { ExamQuestion } from '../types'

const props = defineProps<{
  questions: ExamQuestion[]
  currentIndex: number
  answeredIds: Set<number>
  markedIds: Set<number>
}>()

const emit = defineEmits<{
  select: [index: number]
}>()

const groups = computed(() => {
  const grouped = new Map<string, Array<{ question: ExamQuestion; index: number }>>()
  props.questions.forEach((question, index) => {
    const items = grouped.get(question.groupName) ?? []
    items.push({ question, index })
    grouped.set(question.groupName, items)
  })
  return [...grouped.entries()].map(([name, items]) => ({ name, items }))
})
</script>

<template>
  <aside class="question-navigator" aria-label="答题卡">
    <div v-for="group in groups" :key="group.name" class="question-navigator__group">
      <h2>{{ group.name }}</h2>
      <div class="question-navigator__grid">
        <button
          v-for="item in group.items"
          :key="item.question.questionId"
          type="button"
          class="question-number"
          :class="{
            'question-number--current': item.index === currentIndex,
            'question-number--answered': answeredIds.has(item.question.questionId),
            'question-number--marked': markedIds.has(item.question.questionId),
          }"
          :aria-current="item.index === currentIndex ? 'step' : undefined"
          :data-testid="`question-number-${item.question.displayNo}`"
          @click="emit('select', item.index)"
        >
          {{ item.question.displayNo }}
        </button>
      </div>
    </div>
    <div class="question-navigator__legend">
      <span><i class="legend-box legend-box--current" />当前题</span>
      <span><i class="legend-box" />未作答</span>
      <span><i class="legend-box legend-box--answered" />已作答</span>
      <span><i class="legend-flag" />已标记</span>
    </div>
  </aside>
</template>

<style scoped>
.question-navigator {
  height: 100%;
  padding: 18px 20px;
  background: #edf7ff;
  border-right: 1px solid #bbd9ef;
  overflow: auto;
}

.question-navigator__group + .question-navigator__group {
  margin-top: 22px;
}

.question-navigator h2 {
  margin: 0 0 14px;
  color: #25364a;
  font-size: 16px;
  font-weight: 600;
}

.question-navigator__grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(32px, 1fr));
  gap: 9px;
}

.question-number {
  position: relative;
  min-width: 0;
  height: 34px;
  padding: 0;
  border: 1px solid #cbd5df;
  border-radius: 1px;
  color: #334155;
  background: #fff;
  cursor: pointer;
  font-size: 14px;
}

.question-number:hover {
  border-color: #168de2;
}

.question-number--answered {
  border-color: #168de2;
  background: #168de2;
  color: #fff;
}

.question-number--current {
  border: 2px solid #ff9800;
  background: #fff7e8;
  color: #c96d00;
  font-weight: 700;
}

.question-number--marked::after {
  content: '';
  position: absolute;
  top: -3px;
  right: -3px;
  width: 0;
  height: 0;
  border-top: 9px solid #ff9800;
  border-left: 9px solid transparent;
}

.question-navigator__legend {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-top: 24px;
  padding-top: 18px;
  border-top: 1px solid #cbddeb;
  color: #526174;
  font-size: 12px;
}

.question-navigator__legend span {
  display: flex;
  align-items: center;
  gap: 7px;
}

.legend-box {
  width: 12px;
  height: 12px;
  border: 1px solid #cbd5df;
  background: #fff;
}

.legend-box--current {
  border: 2px solid #ff9800;
  background: #fff7e8;
}

.legend-box--answered {
  border-color: #168de2;
  background: #168de2;
}

.legend-flag {
  width: 0;
  height: 0;
  border-top: 11px solid #ff9800;
  border-left: 11px solid transparent;
}

@media (max-width: 1450px) {
  .question-navigator {
    padding: 14px;
  }
  .question-navigator__grid {
    gap: 7px;
  }
  .question-number {
    height: 30px;
    font-size: 12px;
  }
}
</style>
