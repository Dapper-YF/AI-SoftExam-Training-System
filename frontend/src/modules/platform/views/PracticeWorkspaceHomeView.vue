<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  ArrowRight,
  Calendar,
  CircleCheck,
  Collection,
  DocumentChecked,
  EditPen,
  Files,
  Histogram,
  Reading,
  Star,
} from '@element-plus/icons-vue'
import { getPracticeGateway } from '@/modules/practice/api/gateway'
import { toSafeUserMessage } from '@/api/client'
import type { PracticeCatalog, PracticeHistoryRecord, PracticeMode, PracticeSubjectType } from '@/modules/practice/types'
import { usePlatformHomeStore } from '@/modules/platform/stores/platform-home'
import { useQualificationStore } from '@/stores/qualification'
import { usePracticeStore } from '@/stores/practice'
import { useAuthStore } from '@/stores/auth'

defineOptions({ name: 'PracticeWorkspaceHomeView' })

const qualificationStore = useQualificationStore()
const practiceStore = usePracticeStore()
const platformHomeStore = usePlatformHomeStore()
const authStore = useAuthStore()
const catalog = ref<PracticeCatalog>()
const catalogStatus = ref<'idle' | 'loading' | 'success' | 'empty' | 'error'>('idle')
const catalogError = ref('')
const continueAttempt = ref<PracticeHistoryRecord>()
let loadEpoch = 0

const selectedQualification = computed(() => qualificationStore.selectedQualification)
const selectedSubjects = computed(() => qualificationStore.subjects)
const workspaceReady = computed(() =>
  qualificationStore.subjectStatus === 'success' && Boolean(qualificationStore.selectedQualificationId))
const subjectRows = computed(() => selectedSubjects.value.map((subject) => {
  const summary = catalog.value?.subjects.find((item) => item.subjectId === subject.id)
  return { subject, summary, available: summary?.available === true }
}))
const practiceStats = computed(() => ({
  attempts: platformHomeStore.stats.find((item) => item.id === 'practice-count')?.value ?? '0',
  mistakes: platformHomeStore.stats.find((item) => item.id === 'mistake-count')?.value ?? '0',
  favorites: platformHomeStore.stats.find((item) => item.id === 'favorite-count')?.value ?? '0',
}))
const taskCards: Array<{
  title: string
  description: string
  icon: typeof Calendar
  tone: string
  mode: PracticeMode
}> = [
  { title: '每日练习', description: '逐题作答，立即查看答案解析', icon: Calendar, tone: 'blue', mode: 'DAILY' },
  { title: '历年真题', description: '按完整试卷作答，交卷后看解析', icon: DocumentChecked, tone: 'teal', mode: 'PAST_EXAM' },
  { title: '章节练习', description: '按章节集中巩固', icon: Files, tone: 'amber', mode: 'CHAPTER' },
  { title: '知识点专项', description: '针对具体考点训练', icon: Histogram, tone: 'green', mode: 'KNOWLEDGE_POINT' },
]

function practiceQuery(mode: PracticeMode, subjectId?: string) {
  return {
    mode,
    qualificationId: qualificationStore.selectedQualificationId ?? undefined,
    subjectId,
  }
}

function formatDate(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(new Date(value))
}

function subjectTypeLabel(type: PracticeSubjectType): string {
  return ({
    COMPREHENSIVE: '综合知识',
    CASE: '案例分析',
    ESSAY: '论文',
    APPLICATION: '应用技术',
  })[type]
}

async function loadWorkspace(): Promise<void> {
  const qualificationId = qualificationStore.selectedQualificationId
  if (!qualificationId || qualificationStore.subjectStatus !== 'success') return
  const epoch = ++loadEpoch
  catalogStatus.value = 'loading'
  catalogError.value = ''
  continueAttempt.value = undefined
  if (!authStore.isAuthenticated) {
    platformHomeStore.reset()
  }
  try {
    const historyPromise: Promise<{ items: PracticeHistoryRecord[] }> = authStore.isAuthenticated
      ? getPracticeGateway().listAttemptHistory({
          qualificationId,
          status: 'IN_PROGRESS',
          page: 1,
          pageSize: 1,
        })
      : Promise.resolve({ items: [], total: 0, page: 1, pageSize: 1 })
    const homePromise = authStore.isAuthenticated ? platformHomeStore.load() : Promise.resolve()
    const [nextCatalog, history] = await Promise.all([
      getPracticeGateway().getCatalog(qualificationId),
      historyPromise,
      practiceStore.loadPapers(qualificationId),
      homePromise,
    ])
    if (epoch !== loadEpoch || qualificationId !== qualificationStore.selectedQualificationId) return
    catalog.value = nextCatalog
    catalogStatus.value = nextCatalog.available ? 'success' : 'empty'
    continueAttempt.value = history.items[0]
  } catch (error) {
    if (epoch !== loadEpoch || qualificationId !== qualificationStore.selectedQualificationId) return
    catalog.value = undefined
    catalogStatus.value = 'error'
    catalogError.value = toSafeUserMessage(error)
  }
}

onMounted(() => {
  if (qualificationStore.status === 'idle') void qualificationStore.load()
})

watch(
  () => [qualificationStore.selectedQualificationId, qualificationStore.subjectStatus, authStore.isAuthenticated] as const,
  () => { void loadWorkspace() },
  { immediate: true, flush: 'post' },
)
</script>

<template>
  <div class="practice-home-page" data-testid="practice-home-page">
    <header class="practice-home-heading">
      <div>
        <p class="eyebrow">软考刷题</p>
        <h1 data-testid="qualification-workspace-title">{{ selectedQualification?.name || '刷题首页' }}</h1>
        <p>从已有题库直接开始，作答进度实时保存</p>
      </div>
    </header>

    <div v-if="qualificationStore.subjectStatus === 'loading' || catalogStatus === 'loading'" class="practice-panel practice-inline-empty">正在加载真实题库…</div>
    <div v-else-if="qualificationStore.subjectStatus === 'error' || catalogStatus === 'error'" class="practice-panel practice-state practice-state--error">
      <strong>题库加载失败</strong><p>{{ qualificationStore.errorMessage || catalogError }}</p><el-button @click="loadWorkspace">重试</el-button>
    </div>
    <div v-else-if="!workspaceReady || catalogStatus === 'empty'" class="practice-panel practice-history-empty">
      <strong>当前资格暂无已发布题目</strong><p>题目审核发布后，这里会自动开放刷题入口。</p>
    </div>

    <template v-else>
      <section class="practice-panel practice-recent" aria-labelledby="recent-practice-title">
        <div class="practice-section-heading">
          <div><h2 id="recent-practice-title">{{ continueAttempt ? '继续上次练习' : '开始今天的刷题' }}</h2><small>不需要设置题量，退出后可从上次位置继续</small></div>
        </div>
        <div v-if="continueAttempt" class="recent-task-body">
          <div class="recent-task-icon"><el-icon><Reading /></el-icon></div>
          <div class="recent-task-copy">
            <strong>未完成练习 <i>进度已保存</i></strong>
            <span>{{ formatDate(continueAttempt.startedAt) }} 开始</span>
            <small>服务器已保留作答、标记和当前题号</small>
          </div>
          <RouterLink class="practice-primary-link" :to="`/app/practice/attempts/${continueAttempt.id}`">继续刷题 <el-icon><ArrowRight /></el-icon></RouterLink>
        </div>
        <div v-else class="recent-task-body">
          <div class="recent-task-icon"><el-icon><EditPen /></el-icon></div>
          <div class="recent-task-copy">
            <strong>从已开放练习开始</strong>
            <span>选择科目后系统自动出题，答题后立即显示解析</span>
            <small>没有人为的“今日必须完成题数”</small>
          </div>
          <RouterLink class="practice-primary-link" :to="{ path: '/app/practice/start', query: practiceQuery('DAILY') }">进入刷题 <el-icon><ArrowRight /></el-icon></RouterLink>
        </div>
      </section>

      <section class="practice-task-grid practice-task-grid--four" aria-label="刷题方式">
        <RouterLink
          v-for="task in taskCards"
          :key="task.title"
          class="practice-task-card"
          :class="`practice-task-card--${task.tone}`"
          :to="{ path: '/app/practice/start', query: practiceQuery(task.mode, qualificationStore.selectedSubjectId ?? undefined) }"
        >
          <span class="practice-task-icon"><el-icon><component :is="task.icon" /></el-icon></span>
          <span class="practice-task-copy"><strong>{{ task.title }}</strong><small>{{ task.description }}</small></span>
          <el-icon class="practice-task-arrow"><ArrowRight /></el-icon>
        </RouterLink>
      </section>

      <section class="practice-panel ai-practice-entry" aria-label="AI智能练习">
        <div>
          <h2>AI 智能练习</h2>
          <p>根据薄弱知识点生成专项题，支持难度自适应和 20 题智能组卷。</p>
        </div>
        <RouterLink class="practice-primary-link" to="/app/practice/ai-practice">进入智能练习 <el-icon><ArrowRight /></el-icon></RouterLink>
      </section>

      <div class="practice-home-layout">
        <div class="practice-home-main">
          <section class="practice-panel practice-subject-overview" aria-labelledby="subject-overview-title">
            <div class="practice-section-heading">
              <div><h2 id="subject-overview-title">科目专项</h2><small>案例、论文等题型只在对应科目中练习</small></div>
            </div>
            <div class="practice-subject-list">
              <div v-for="item in subjectRows" :key="item.subject.id" class="practice-subject-row">
                <div><strong>{{ item.subject.name }}</strong><span>{{ subjectTypeLabel(item.subject.type) }}</span></div>
                <p data-testid="subject-practice-availability">{{ item.available ? '专项练习已开放' : '暂未开放' }}</p>
                <RouterLink v-if="item.available" class="practice-secondary-link" :to="{ path: '/app/practice/start', query: practiceQuery('DAILY', item.subject.id) }">专项刷题</RouterLink>
                <span v-else class="practice-unavailable-label">暂未开放</span>
              </div>
            </div>
          </section>

          <section class="practice-panel practice-papers" aria-labelledby="past-paper-title">
            <div class="practice-section-heading">
              <div><h2 id="past-paper-title">历年真题卷</h2><small>完整试卷可包含多个科目和题型</small></div>
              <RouterLink :to="{ path: '/app/practice/start', query: practiceQuery('PAST_EXAM') }">查看全部 <el-icon><ArrowRight /></el-icon></RouterLink>
            </div>
            <div v-if="practiceStore.papers.length" class="practice-paper-list">
              <div class="practice-paper-row practice-paper-row--head"><span>年份</span><span>试卷名称</span><span>总分</span><span>时长</span><span>操作</span></div>
              <div v-for="paper in practiceStore.papers.slice(0, 4)" :key="paper.id" class="practice-paper-row">
                <span>{{ paper.yearNo ?? '—' }}</span><strong>{{ paper.title }}</strong><span>{{ paper.totalScore ?? '—' }}</span><span>{{ paper.durationMinutes ? `${paper.durationMinutes} 分钟` : '—' }}</span>
                <RouterLink :to="{ path: '/app/practice/start', query: practiceQuery('PAST_EXAM') }">进入试卷</RouterLink>
              </div>
            </div>
            <div v-else class="practice-inline-empty" data-testid="past-paper-unavailable">
              <span>完整年份试卷整理中，可先进行专项练习。</span>
              <RouterLink :to="{ path: '/app/practice/start', query: practiceQuery('DAILY') }">专项刷题</RouterLink>
            </div>
          </section>
        </div>

        <aside v-if="authStore.isAuthenticated" class="practice-home-aside">
          <section class="practice-panel practice-today" aria-labelledby="learning-summary-title">
            <div class="practice-section-heading"><h2 id="learning-summary-title">学习概览</h2></div>
            <div class="practice-metric"><el-icon><EditPen /></el-icon><span>练习记录</span><strong>{{ practiceStats.attempts }}<small>次</small></strong></div>
            <div class="practice-metric"><el-icon><CircleCheck /></el-icon><span>待复习错题</span><strong>{{ practiceStats.mistakes }}<small>题</small></strong></div>
            <div class="practice-metric"><el-icon><Star /></el-icon><span>收藏题目</span><strong>{{ practiceStats.favorites }}<small>题</small></strong></div>
          </section>

          <section class="practice-panel practice-review" aria-labelledby="review-title">
            <div class="practice-section-heading"><h2 id="review-title">复习入口</h2><RouterLink to="/app/practice/reports">学习报告</RouterLink></div>
            <div class="practice-review-grid">
              <RouterLink to="/app/practice/review"><el-icon><Collection /></el-icon><span>错题与收藏<strong>{{ practiceStats.mistakes }}</strong></span><ArrowRight class="review-arrow" /></RouterLink>
              <RouterLink to="/app/practice/reports"><el-icon><Histogram /></el-icon><span>练习历史<strong>{{ practiceStats.attempts }}</strong></span><ArrowRight class="review-arrow" /></RouterLink>
            </div>
          </section>
        </aside>
        <aside v-else class="practice-home-aside">
          <section class="practice-panel practice-today" aria-labelledby="anonymous-learning-title">
            <div class="practice-section-heading"><h2 id="anonymous-learning-title">登录后保存学习记录</h2></div>
            <p>开始作答、继续练习、错题收藏和历史报告需要登录。</p>
            <RouterLink class="practice-primary-link" :to="{ path: '/login', query: { redirect: '/app/practice/start' } }">登录并开始刷题</RouterLink>
          </section>
        </aside>
      </div>
    </template>
  </div>
</template>
