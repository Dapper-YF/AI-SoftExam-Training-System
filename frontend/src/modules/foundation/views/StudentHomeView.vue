<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowRight,
  Collection,
  DocumentChecked,
  Files,
  Memo,
  Monitor,
} from '@element-plus/icons-vue'
import QualificationState from '@/components/common/QualificationState.vue'
import type { LevelCode } from '@/modules/foundation/types'
import { getPracticeGateway } from '@/modules/practice/api/gateway'
import type { PracticeHistoryRecord, PracticeMode } from '@/modules/practice/types'
import { usePracticeStore } from '@/stores/practice'
import { useQualificationStore } from '@/stores/qualification'

const router = useRouter()
const qualificationStore = useQualificationStore()
const practiceStore = usePracticeStore()
const selectedLevel = computed(() => qualificationStore.selectedLevelCode)

const levels: Array<{ code: LevelCode; label: string }> = [
  { code: 'PRIMARY', label: '初级' },
  { code: 'INTERMEDIATE', label: '中级' },
  { code: 'ADVANCED', label: '高级' },
]

const qualificationsForLevel = computed(() => qualificationStore.qualificationsForSelectedLevel)
const continueAttempt = ref<PracticeHistoryRecord>()
let historyEpoch = 0
const sourceLabels: Record<PracticeHistoryRecord['sourceType'], string> = {
  KNOWLEDGE: '知识点练习',
  YEAR: '年份练习',
  PAPER: '历年真题',
  DAILY: '每日练习',
}
const quickActions = [
  { label: '历年真题', icon: DocumentChecked, mode: 'PAST_EXAM' as PracticeMode },
  { label: '章节练习', icon: Files, mode: 'CHAPTER' as PracticeMode },
  { label: '知识点练习', icon: Collection, mode: 'KNOWLEDGE_POINT' as PracticeMode },
  { label: '机考模拟', icon: Monitor, path: '/exam-simulator' },
]

async function loadConfirmedPracticeScope(): Promise<void> {
  const selectedSubject = qualificationStore.selectedSubject
  if (
    qualificationStore.subjectStatus !== 'success'
    || !qualificationStore.selectedQualificationId
    || selectedSubject?.qualificationId !== qualificationStore.selectedQualificationId
  ) {
    continueAttempt.value = undefined
    return
  }
  const qualificationId = qualificationStore.selectedQualificationId
  const subjectId = selectedSubject.id
  const epoch = ++historyEpoch
  const history = getPracticeGateway().listAttemptHistory({
    qualificationId,
    subjectId,
    status: 'IN_PROGRESS',
    page: 1,
    pageSize: 1,
  }).then((result) => {
    if (epoch === historyEpoch) continueAttempt.value = result.items[0]
  }).catch(() => {
    if (epoch === historyEpoch) continueAttempt.value = undefined
  })
  await Promise.all([practiceStore.loadPapers(qualificationId, subjectId), history])
}

async function selectLevel(levelCode: LevelCode): Promise<void> {
  practiceStore.resetPaperScope()
  await qualificationStore.selectLevel(levelCode)
  await loadConfirmedPracticeScope()
}

async function selectQualification(id: string): Promise<void> {
  if (id === qualificationStore.selectedQualificationId) return
  practiceStore.resetPaperScope()
  await qualificationStore.selectQualification(id)
  await loadConfirmedPracticeScope()
}

async function goPractice(mode: PracticeMode): Promise<void> {
  practiceStore.filters.mode = mode
  await router.push('/app/practice')
}

async function continuePractice(): Promise<void> {
  if (!continueAttempt.value) return
  await router.push(`/app/practice/attempts/${continueAttempt.value.id}`)
}

function formatDate(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(value))
}

onMounted(async () => {
  if (qualificationStore.status === 'idle') await qualificationStore.load()
  await loadConfirmedPracticeScope()
})
</script>

<template>
  <div class="student-dashboard" data-testid="student-home-page">
    <QualificationState>
      <section class="dashboard-panel qualification-panel" aria-labelledby="qualification-title">
        <h1 id="qualification-title">选择考试范围</h1>
        <div class="level-segmented" role="tablist" aria-label="考试级别">
          <button v-for="level in levels" :key="level.code" type="button" :data-level-code="level.code" :class="{ 'is-active': selectedLevel === level.code }" @click="selectLevel(level.code)">
            {{ level.label }}
          </button>
        </div>
        <div v-if="qualificationsForLevel.length" class="qualification-options">
          <button
            v-for="qualification in qualificationsForLevel"
            :key="qualification.id"
            type="button"
            :class="{ 'is-active': qualificationStore.selectedQualificationId === qualification.id }"
            @click="selectQualification(qualification.id)"
          >
            <el-icon><Memo /></el-icon>
            <span>{{ qualification.name }}</span>
          </button>
        </div>
        <p v-else class="level-empty">该级别暂无可用资格</p>
      </section>
    </QualificationState>

    <section class="dashboard-panel quick-panel" aria-labelledby="quick-title">
      <h2 id="quick-title">快速练习</h2>
      <div class="quick-action-grid">
        <button v-for="action in quickActions" :key="action.label" type="button" @click="action.path ? router.push(action.path) : goPractice(action.mode!)">
          <el-icon><component :is="action.icon" /></el-icon>
          <span>{{ action.label }}</span>
        </button>
      </div>
    </section>

    <section v-if="continueAttempt" class="dashboard-panel continue-panel" aria-labelledby="continue-title">
      <h2 id="continue-title">继续上次练习</h2>
      <div class="continue-content">
        <span class="continue-icon"><el-icon><Memo /></el-icon></span>
        <div class="continue-info">
          <strong>未完成的普通练习</strong>
          <span>{{ sourceLabels[continueAttempt.sourceType] }} · {{ formatDate(continueAttempt.startedAt) }}</span>
          <small>共 {{ continueAttempt.questionCount }} 题，作答状态以服务器记录为准</small>
        </div>
        <el-button type="primary" @click="continuePractice">继续上次练习<el-icon><ArrowRight /></el-icon></el-button>
      </div>
    </section>
  </div>
</template>
