<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowRight,
  Clock,
  Collection,
  DataAnalysis,
  Download,
  FolderOpened,
  RefreshRight,
  StarFilled,
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { toSafeUserMessage } from '@/api/client'
import { getPracticeGateway } from '@/modules/practice/api/gateway'
import { rememberPracticeSessionPresentation } from '@/modules/practice/session-presentation'
import QuestionRichContent from '@/modules/practice/components/QuestionRichContent.vue'
import type {
  PracticeAnswerValue,
  PracticeFavoriteRecord,
  PracticeLoadStatus,
  PracticeMistakeReason,
  PracticeMistakeReasonCode,
  PracticeMistakeReasonStat,
  PracticeMistakeRecord,
  PracticeHistoryRecord,
  PracticeQuestion,
  PracticeRecommendation,
} from '@/modules/practice/types'
import { useQualificationStore } from '@/stores/qualification'

defineOptions({ name: 'LearningToolsView' })

const router = useRouter()
const route = useRoute()
const qualificationStore = useQualificationStore()
type ToolSection = 'mistakes' | 'favorites' | 'dashboard'
const activeSection = ref<ToolSection>('mistakes')
const mistakes = ref<PracticeMistakeRecord[]>([])
const favorites = ref<PracticeFavoriteRecord[]>([])
const mistakesTotal = ref(0)
const favoritesTotal = ref(0)
const status = ref<PracticeLoadStatus>('idle')
const errorMessage = ref('')
const mistakeStatus = ref('ACTIVE')
const favoriteGroup = ref('ALL')
const groupDraft = ref('')
const categoryDialog = ref<PracticeFavoriteRecord | null>(null)
const categorySaving = ref(false)
const redoQuestionId = ref('')
const reviewDialog = ref(false)
const reviewLoading = ref(false)
const reviewError = ref('')
const reviewRecord = ref<PracticeMistakeRecord | null>(null)
const reviewQuestion = ref<PracticeQuestion | null>(null)
const reviewUserAnswer = ref<PracticeAnswerValue | undefined>()
const reviewCorrectAnswer = ref<PracticeAnswerValue | undefined>()
const reviewExplanation = ref('')
const reviewInsightsLoading = ref(false)
const reviewInsightError = ref('')
const reviewReasons = ref<PracticeMistakeReason[]>([])
const selectedReasonCodes = ref<PracticeMistakeReasonCode[]>([])
const reasonSaving = ref(false)
const recommendations = ref<PracticeRecommendation[]>([])
const recommendationQuestionId = ref('')
const reasonStats = ref<PracticeMistakeReasonStat[]>([])
const history = ref<PracticeHistoryRecord[]>([])
const historyTotal = ref(0)
const historyStatus = ref<PracticeLoadStatus>('idle')
const historyError = ref('')
const knownGroups = computed(() => [...new Set(favorites.value.map((row) => row.favoriteGroup).filter((value): value is string => Boolean(value)))])
const displayedFavorites = computed(() => favoriteGroup.value === 'ALL' ? favorites.value : favorites.value.filter((row) => (row.favoriteGroup || '未分类') === favoriteGroup.value))
const reasonTotal = computed(() => reasonStats.value.reduce((total, item) => total + item.count, 0))
const leadingReason = computed(() => [...reasonStats.value].sort((left, right) => right.count - left.count)[0])
const leadingReasonName = computed(() => leadingReason.value ? reasonLabel(leadingReason.value.reasonCode) : '待诊断')
const leadingReasonSummary = computed(() => leadingReason.value ? `主要错因：${reasonLabel(leadingReason.value.reasonCode)}` : '等待错因诊断')
const leadingReasonCountSummary = computed(() => leadingReason.value ? `${reasonLabel(leadingReason.value.reasonCode)} ${leadingReason.value.count} 题` : '从错题复盘开始诊断')
const completedHistory = computed(() => history.value.filter((item) => item.status !== 'IN_PROGRESS'))
const practicedQuestions = computed(() => history.value.reduce((sum, item) => sum + item.questionCount, 0))
const scoredHistory = computed(() => completedHistory.value.filter((item) => item.totalScore != null && item.maxScore != null && item.maxScore > 0))
const overallAccuracy = computed(() => {
  const maxScore = scoredHistory.value.reduce((sum, item) => sum + (item.maxScore ?? 0), 0)
  const score = scoredHistory.value.reduce((sum, item) => sum + (item.totalScore ?? 0), 0)
  return maxScore ? Math.round(score / maxScore * 100) : null
})
const learningMinutes = computed(() => completedHistory.value.reduce((sum, item) => {
  if (item.durationSeconds != null) return sum + Math.max(0, Math.min(Math.round(item.durationSeconds / 60), 480))
  if (!item.submittedAt) return sum
  const minutes = Math.round((new Date(item.submittedAt).getTime() - new Date(item.startedAt).getTime()) / 60000)
  return sum + Math.max(0, Math.min(minutes, 480))
}, 0))
const recentHistory = computed(() => history.value.slice(0, 3))
const trendHistory = computed(() => [...scoredHistory.value].slice(0, 8).reverse().map((item) => ({
  ...item,
  accuracy: Math.round((item.totalScore ?? 0) / (item.maxScore ?? 1) * 100),
})))
const weeklyRecords = computed(() => recordsSince(7))
const monthlyRecords = computed(() => recordsSince(30))
const qualificationDimensions = computed(() => {
  const name = qualificationStore.selectedQualification?.name ?? ''
  if (name.includes('信息系统项目管理师') || name.includes('高项')) return ['整合', '范围', '进度', '成本', '质量', '资源', '沟通', '风险', '采购', '干系人']
  if (name.includes('系统集成项目管理工程师') || name.includes('中项')) return ['项目基础', '立项', '整合', '范围', '进度', '成本', '质量', '风险']
  if (name.includes('软件设计师') || name.includes('软设')) return ['计算机基础', '程序设计', '数据结构', '数据库', '软件工程', '系统设计', '网络安全']
  if (name.includes('系统规划与管理师') || name.includes('系规')) return ['规划设计', '部署实施', '服务运营', '持续改进', '监督管理']
  if (name.includes('系统分析师') || name.includes('系分')) return ['需求获取', '需求分析', '架构设计', '详细设计', '验证评估']
  return ['基础知识', '专业知识', '案例应用', '综合能力']
})
const priorityMistakes = computed(() => mistakes.value.slice(0, 4))
const favoritePreview = computed(() => displayedFavorites.value.slice(0, 3))
const accuracyDonutStyle = computed(() => ({
  background: `conic-gradient(#2563eb 0 ${overallAccuracy.value ?? 0}%, #e8eef6 ${overallAccuracy.value ?? 0}% 100%)`,
}))
const favoriteDistributionStyle = computed(() => {
  const total = Math.max(favorites.value.length, 1)
  const first = favorites.value.filter((item) => Boolean(item.favoriteGroup)).length / total * 100
  return { background: `conic-gradient(#d79a32 0 ${first}%, #f2d39b ${first}% 78%, #edf1f5 78% 100%)` }
})
const weeklySchedule = computed(() => Array.from({ length: 7 }, (_, index) => {
  const day = new Date(); day.setHours(0, 0, 0, 0); day.setDate(day.getDate() - (6 - index))
  const count = history.value.filter((item) => {
    const started = new Date(item.startedAt); started.setHours(0, 0, 0, 0)
    return started.getTime() === day.getTime()
  }).length
  return {
    label: new Intl.DateTimeFormat('zh-CN', { weekday: 'short' }).format(day),
    date: new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric' }).format(day),
    count,
    width: `${Math.max(count ? Math.min(30 + count * 14, 100) : 8, 8)}%`,
  }
}))

const reasonOptions: Array<{ code: PracticeMistakeReasonCode; label: string; description: string }> = [
  { code: 'CONCEPT', label: '概念不清', description: '知识点理解或记忆不牢' },
  { code: 'CALCULATION', label: '计算错误', description: '公式、步骤或数值计算有误' },
  { code: 'MISREADING', label: '审题失误', description: '遗漏条件或误解题意' },
]

function date(value: string): string { return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'short', day: 'numeric' }).format(new Date(value)) }
function typeLabel(value: string): string { return ({ SINGLE_CHOICE: '单选题', MULTI_CHOICE: '多选题', JUDGE: '判断题', CASE: '案例题', CALCULATION: '计算题' } as Record<string, string>)[value] ?? value }
function difficultyLabel(value?: string): string { return ({ EASY: '简单', MEDIUM: '中等', HARD: '困难' } as Record<string, string>)[value ?? ''] ?? value ?? '难度未标注' }
function reasonLabel(value: PracticeMistakeReasonCode): string { return reasonOptions.find((item) => item.code === value)?.label ?? value }
function isSystemSuggested(code: PracticeMistakeReasonCode): boolean { return reviewReasons.value.some((item) => item.reasonCode === code && item.source === 'SYSTEM') }
function historyTypeLabel(value: PracticeHistoryRecord['sourceType']): string { return ({ KNOWLEDGE: '知识点练习', YEAR: '年份练习', PAPER: '历年真题', DAILY: '每日练习' })[value] }
function historyStatusLabel(value: PracticeHistoryRecord['status']): string { return ({ IN_PROGRESS: '进行中', SUBMITTED: '已提交', PENDING_REVIEW: '待复核' })[value] }
function historyAccuracy(item: PracticeHistoryRecord): string { return item.totalScore == null || !item.maxScore ? '待生成' : `${Math.round(item.totalScore / item.maxScore * 100)}%` }
function recordsSince(days: number): PracticeHistoryRecord[] { const cutoff = Date.now() - days * 86400000; return history.value.filter((item) => new Date(item.startedAt).getTime() >= cutoff) }
function reportSummary(items: PracticeHistoryRecord[]): string {
  if (!items.length) return '本周期暂无练习记录，完成练习后将自动生成学习摘要。'
  const questions = items.reduce((sum, item) => sum + item.questionCount, 0)
  const scored = items.filter((item) => item.totalScore != null && item.maxScore)
  const max = scored.reduce((sum, item) => sum + (item.maxScore ?? 0), 0)
  const score = scored.reduce((sum, item) => sum + (item.totalScore ?? 0), 0)
  return `完成 ${items.length} 次练习、累计 ${questions} 题${max ? `，综合正确率约 ${Math.round(score / max * 100)}%` : ''}。`
}

async function selectSection(section: ToolSection): Promise<void> {
  activeSection.value = section
}

function answerLabel(value: PracticeAnswerValue | undefined): string {
  if (value == null || value === '') return '未记录'
  if (Array.isArray(value)) return value.join('、')
  if (typeof value === 'boolean') return value ? '正确' : '错误'
  return String(value)
}

async function load(): Promise<void> {
  status.value = 'loading'; errorMessage.value = ''
  try {
    const scope = { qualificationId: qualificationStore.selectedQualificationId ?? undefined, subjectId: qualificationStore.selectedSubjectId ?? undefined }
    const [mistakePage, favoritePage, statistics] = await Promise.all([
      getPracticeGateway().listMistakes({ page: 1, pageSize: 100, status: mistakeStatus.value || undefined, ...scope }),
      getPracticeGateway().listFavorites(1, 100),
      getPracticeGateway().listMistakeReasonStatistics().catch(() => []),
    ])
    mistakes.value = mistakePage.items; mistakesTotal.value = mistakePage.total
    favorites.value = favoritePage.items; favoritesTotal.value = favoritePage.total
    reasonStats.value = statistics
    status.value = 'success'
  } catch (error) { status.value = 'error'; errorMessage.value = toSafeUserMessage(error) }
}

async function redo(record: PracticeMistakeRecord): Promise<void> {
  redoQuestionId.value = record.questionId
  try {
    const attempt = await getPracticeGateway().createCaseAttempt({ qualificationId: record.qualificationId, subjectId: record.subjectId, questionId: record.questionId })
    rememberPracticeSessionPresentation(attempt.id, {
      mode: 'KNOWLEDGE_POINT',
      scopeName: '错题重做',
      returnPath: `/tools?reviewQuestionId=${record.questionId}`,
    })
    await router.push(`/app/practice/attempts/${attempt.id}`)
  } catch (error) { ElMessage.error(toSafeUserMessage(error)) }
  finally { redoQuestionId.value = '' }
}

async function openReview(record: PracticeMistakeRecord): Promise<void> {
  reviewDialog.value = true; reviewLoading.value = true; reviewError.value = ''; reviewRecord.value = record
  reviewQuestion.value = null; reviewUserAnswer.value = undefined; reviewCorrectAnswer.value = undefined; reviewExplanation.value = ''
  reviewInsightsLoading.value = true; reviewInsightError.value = ''; reviewReasons.value = []; selectedReasonCodes.value = []; recommendations.value = []
  const gateway = getPracticeGateway()
  try {
    const [attempt, reasons, related] = await Promise.all([
      gateway.getAttempt(record.lastAttemptId),
      gateway.getMistakeReasons(record.questionId).catch((error) => {
        reviewInsightError.value = toSafeUserMessage(error)
        return []
      }),
      gateway.listRecommendations(record.questionId, 6).catch((error) => {
        reviewInsightError.value ||= toSafeUserMessage(error)
        return []
      }),
    ])
    reviewReasons.value = reasons
    selectedReasonCodes.value = reasons.map((item) => item.reasonCode)
    recommendations.value = related
    const question = attempt.questions.find((item) => item.questionId === record.questionId)
    if (!question) throw new Error('原答题记录中未找到该题目')
    const subQuestion = question.subQuestions[0]
    if (!subQuestion) throw new Error('原答题记录中未找到子题答案')
    reviewQuestion.value = question
    reviewUserAnswer.value = attempt.answers[subQuestion.subQuestionId]
    reviewCorrectAnswer.value = subQuestion.standardAnswer ?? question.correctAnswer
    reviewExplanation.value = subQuestion.solution ?? question.explanation ?? ''
    if (reviewCorrectAnswer.value === undefined || !reviewExplanation.value) {
      const explanation = await gateway.getExplanation(record.lastAttemptId, record.questionId)
      const detail = explanation.subQuestions.find((item) => item.subQuestionId === subQuestion.subQuestionId) ?? explanation.subQuestions[0]
      if (detail) {
        reviewCorrectAnswer.value ??= detail.standardAnswer
        reviewExplanation.value ||= detail.solution ?? ''
      }
    }
  } catch (error) { reviewError.value = toSafeUserMessage(error) }
  finally { reviewLoading.value = false; reviewInsightsLoading.value = false }
}

async function loadHistory(): Promise<void> {
  historyStatus.value = 'loading'; historyError.value = ''
  try {
    const result = await getPracticeGateway().listAttemptHistory({
      page: 1,
      pageSize: 100,
      qualificationId: qualificationStore.selectedQualificationId ?? undefined,
      subjectId: qualificationStore.selectedSubjectId ?? undefined,
    })
    history.value = result.items
    historyTotal.value = result.total
    historyStatus.value = history.value.length ? 'success' : 'empty'
  } catch (error) {
    history.value = []; historyTotal.value = 0; historyStatus.value = 'error'; historyError.value = toSafeUserMessage(error)
  }
}

async function saveMistakeReasons(): Promise<void> {
  const record = reviewRecord.value
  if (!record || reasonSaving.value) return
  reasonSaving.value = true
  try {
    const gateway = getPracticeGateway()
    await gateway.replaceMistakeReasons(record.questionId, selectedReasonCodes.value)
    reviewReasons.value = await gateway.getMistakeReasons(record.questionId)
    reasonStats.value = await gateway.listMistakeReasonStatistics().catch(() => reasonStats.value)
    ElMessage.success(selectedReasonCodes.value.length ? '错因已保存' : '已清除错因')
  } catch (error) { ElMessage.error(toSafeUserMessage(error)) }
  finally { reasonSaving.value = false }
}

async function startRecommendation(item: PracticeRecommendation): Promise<void> {
  recommendationQuestionId.value = item.questionId
  try {
    const attempt = await getPracticeGateway().createCaseAttempt({
      qualificationId: item.qualificationId,
      subjectId: item.subjectId,
      questionId: item.questionId,
    })
    rememberPracticeSessionPresentation(attempt.id, {
      mode: 'KNOWLEDGE_POINT',
      scopeName: '同类题巩固',
      returnPath: reviewRecord.value ? `/tools?reviewQuestionId=${reviewRecord.value.questionId}` : '/tools',
    })
    await router.push(`/app/practice/attempts/${attempt.id}`)
  } catch (error) { ElMessage.error(toSafeUserMessage(error)) }
  finally { recommendationQuestionId.value = '' }
}

function download(name: string, columns: string[], rows: string[][]): void {
  const csv = [columns, ...rows].map((row) => row.map((value) => `"${String(value).replaceAll('"', '""')}"`).join(',')).join('\n')
  const link = document.createElement('a'); link.href = URL.createObjectURL(new Blob(['\uFEFF', csv], { type: 'text/csv;charset=utf-8' })); link.download = name; link.click(); URL.revokeObjectURL(link.href)
}

function exportMistakes(): void { download('错题本.csv', ['题目', '题型', '错题次数', '最近答错', '状态'], mistakes.value.map((r) => [r.questionTitle, typeLabel(r.questionType), String(r.mistakeCount), date(r.lastWrongAt), r.status])) }
function exportFavorites(): void { download('收藏题.csv', ['题目', '分类', '收藏时间'], displayedFavorites.value.map((r) => [r.questionTitle, r.favoriteGroup || '未分类', date(r.createdAt)])) }
function openCategory(record: PracticeFavoriteRecord): void { categoryDialog.value = record; groupDraft.value = record.favoriteGroup || '' }
async function saveCategory(): Promise<void> {
  if (!categoryDialog.value) return
  categorySaving.value = true
  try {
    const saved = await getPracticeGateway().updateFavoriteGroup(categoryDialog.value.questionId, groupDraft.value.trim() || undefined)
    favorites.value = favorites.value.map((row) => row.questionId === saved.questionId ? saved : row)
    categoryDialog.value = null; ElMessage.success('分类已更新')
  } catch (error) { ElMessage.error(toSafeUserMessage(error)) }
  finally { categorySaving.value = false }
}

onMounted(async () => {
  await Promise.all([load(), loadHistory()])
  const questionId = typeof route.query.reviewQuestionId === 'string' ? route.query.reviewQuestionId : ''
  const record = mistakes.value.find((item) => item.questionId === questionId)
  if (record) await openReview(record)
  const section = String(route.query.section ?? '').replace('dashboard', 'learning-data')
  if (section) {
    await nextTick()
    document.getElementById(section)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
})
</script>

<template>
  <main class="tools-page" data-testid="learning-tools-page">
    <header class="tools-heading">
      <div>
        <span class="tools-heading__eyebrow">个人学习驾驶舱</span>
        <h1>学习工作台</h1>
        <p>{{ qualificationStore.selectedQualification?.name ?? '当前备考方向' }} · 聚合复盘任务、学习表现与备考节奏</p>
      </div>
      <nav class="tools-heading__actions" aria-label="学习工具快捷入口">
        <RouterLink to="/app/practice/history"><el-icon><Clock /></el-icon>练习历史</RouterLink>
        <RouterLink class="is-primary" to="/practice?mode=DAILY">开始刷题<el-icon><ArrowRight /></el-icon></RouterLink>
      </nav>
    </header>

    <section class="workbench-overview" aria-label="学习概览">
      <div class="overview-score">
        <div class="overview-donut" :style="accuracyDonutStyle"><span><strong>{{ overallAccuracy ?? '—' }}</strong><small>{{ overallAccuracy == null ? '待生成' : '% 正确率' }}</small></span></div>
        <div><span>综合学习表现</span><h2>{{ scoredHistory.length ? '保持节奏，持续巩固薄弱项' : '完成练习后生成表现分析' }}</h2><p>基于当前资格下最近 {{ history.length }} 次练习实时汇总</p></div>
      </div>
      <div class="overview-metrics">
        <article><small>累计练习</small><strong>{{ historyTotal }}</strong><em>次</em><span>当前资格范围</span></article>
        <article><small>累计刷题</small><strong>{{ practicedQuestions }}</strong><em>题</em><span>最近记录汇总</span></article>
        <article><small>学习时长</small><strong>{{ Math.round(learningMinutes / 60 * 10) / 10 }}</strong><em>小时</em><span>按练习起止统计</span></article>
        <article class="is-alert"><small>待巩固错题</small><strong>{{ mistakesTotal }}</strong><em>题</em><span>{{ leadingReasonSummary }}</span></article>
      </div>
      <div class="overview-spark" aria-label="最近正确率趋势">
        <div><span>近期表现</span><small>最近 {{ trendHistory.length }} 次有效成绩</small></div>
        <div class="spark-bars">
          <i v-for="item in trendHistory" :key="item.id" :style="{ height: `${Math.max(item.accuracy, 8)}%` }" :title="`${item.accuracy}%`"></i>
          <span v-if="!trendHistory.length">暂无趋势数据</span>
        </div>
      </div>
    </section>

    <div class="workbench-main-grid">
      <section id="mistakes" class="workbench-panel mistakes-panel">
        <header class="workbench-panel__header">
          <div><span>优先任务</span><h2>待巩固错题</h2><p>优先处理反复失分和近期答错题目</p></div>
          <div class="panel-actions">
            <el-radio-group v-model="mistakeStatus" size="small" @change="load"><el-radio-button value="ACTIVE">待巩固</el-radio-button><el-radio-button value="MASTERED">已掌握</el-radio-button></el-radio-group>
            <button type="button" :disabled="!mistakes.length" @click="exportMistakes"><el-icon><Download /></el-icon>导出</button>
          </div>
        </header>
        <div v-if="status === 'loading'" class="compact-state">正在整理错题...</div>
        <div v-else-if="status === 'error'" class="compact-state is-error">{{ errorMessage }}<button type="button" @click="load">重试</button></div>
        <div v-else-if="priorityMistakes.length" class="mistake-priority-list">
          <article v-for="record in priorityMistakes" :key="record.questionId">
            <span class="mistake-index">{{ String(priorityMistakes.indexOf(record) + 1).padStart(2, '0') }}</span>
            <div class="mistake-copy"><div><span>{{ typeLabel(record.questionType) }}</span><em>累计错 {{ record.mistakeCount }} 次</em></div><h3>{{ record.questionTitle }}</h3><p>最近答错于 {{ date(record.lastWrongAt) }}</p></div>
            <div class="mistake-reason"><small>错因提示</small><strong>{{ leadingReasonName }}</strong></div>
            <div class="mistake-actions"><button type="button" @click="openReview(record)">查看解析</button><button class="is-primary" type="button" :disabled="Boolean(redoQuestionId)" @click="redo(record)">{{ redoQuestionId === record.questionId ? '创建中' : '立即重做' }}<el-icon><ArrowRight /></el-icon></button></div>
          </article>
        </div>
        <div v-else class="compact-state">当前没有待巩固错题，继续保持。</div>
      </section>

      <section id="favorites" class="workbench-panel favorites-panel">
        <header class="workbench-panel__header"><div><span>个人题库</span><h2>收藏题</h2><p>沉淀典型题与高价值题目</p></div><button class="panel-export" type="button" :disabled="!displayedFavorites.length" @click="exportFavorites"><el-icon><Download /></el-icon></button></header>
        <div class="favorite-insight">
          <div class="favorite-donut" :style="favoriteDistributionStyle"><span><strong>{{ favoritesTotal }}</strong><small>收藏题</small></span></div>
          <div><small>收藏分类</small><strong>{{ knownGroups.length || 0 }} 个</strong><p>{{ knownGroups.slice(0, 3).join('、') || '尚未建立分类' }}</p></div>
        </div>
        <div class="favorite-filter"><el-select v-model="favoriteGroup" aria-label="收藏分类"><el-option label="全部分类" value="ALL" /><el-option label="未分类" value="未分类" /><el-option v-for="group in knownGroups" :key="group" :label="group" :value="group" /></el-select></div>
        <div v-if="favoritePreview.length" class="favorite-preview-list">
          <article v-for="record in favoritePreview" :key="record.id"><span><el-icon><StarFilled /></el-icon></span><div><strong>{{ record.questionTitle }}</strong><small>{{ record.favoriteGroup || '未分类' }} · {{ date(record.createdAt) }}</small></div><button type="button" title="分类管理" @click="openCategory(record)"><el-icon><FolderOpened /></el-icon></button></article>
        </div>
        <div v-else class="compact-state">暂无收藏题，刷题时可收藏典型题目。</div>
      </section>
    </div>

    <section id="learning-data" class="workbench-analytics">
      <header class="analytics-heading"><div><span>学习表现</span><h2>从练习结果看见能力变化</h2></div><button type="button" :disabled="historyStatus === 'loading'" @click="loadHistory"><el-icon><RefreshRight /></el-icon>更新数据</button></header>
      <div v-if="historyStatus === 'error'" class="compact-state is-error">{{ historyError }}<button type="button" @click="loadHistory">重试</button></div>
      <div v-else class="analytics-grid">
        <section class="analytics-card trend-card"><header><div><span>正确率趋势</span><h3>最近有效练习</h3></div><small>成绩百分比</small></header><div v-if="trendHistory.length" class="trend-chart" aria-label="正确率趋势图"><div v-for="item in trendHistory" :key="item.id" class="trend-column"><strong>{{ item.accuracy }}%</strong><div><i :style="{ height: `${Math.max(item.accuracy, 4)}%` }"></i></div><small>{{ new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric' }).format(new Date(item.startedAt)) }}</small></div></div><div v-else class="dashboard-empty">完成有成绩的练习后生成趋势。</div></section>
        <section class="analytics-card mastery-card"><header><div><span>资格能力维度</span><h3>{{ qualificationStore.selectedQualification?.name ?? '当前资格' }}</h3></div><small>独立统计</small></header><div class="mastery-radar"><div class="radar-visual"><i></i><i></i><i></i><span>待积累</span></div><div class="radar-legend"><div v-for="dimension in qualificationDimensions" :key="dimension"><span>{{ dimension }}</span><i></i><em>—</em></div></div></div><p>完成对应知识点练习后，将依据真实掌握数据形成能力图谱。</p></section>
        <section class="analytics-card schedule-card"><header><div><span>学习节奏</span><h3>近七天任务轨迹</h3></div><small>练习频次</small></header><div class="schedule-timeline"><div v-for="day in weeklySchedule" :key="day.date"><span><strong>{{ day.label }}</strong><small>{{ day.date }}</small></span><div><i :style="{ width: day.width }"></i></div><em>{{ day.count ? `${day.count} 次练习` : '休整' }}</em></div></div></section>
        <section class="analytics-card report-card"><header><div><span>周期总结</span><h3>自动学习摘要</h3></div><small>实时汇总</small></header><div class="report-cards"><article><small>近 7 天</small><strong>学习周报</strong><p>{{ reportSummary(weeklyRecords) }}</p></article><article><small>近 30 天</small><strong>学习月报</strong><p>{{ reportSummary(monthlyRecords) }}</p></article></div></section>
      </div>
    </section>

    <section class="workbench-recent">
      <header><div><span>最近练习</span><h2>继续或回看最近记录</h2></div><RouterLink to="/app/practice/history">查看全部<el-icon><ArrowRight /></el-icon></RouterLink></header>
      <div v-if="recentHistory.length" class="recent-list"><RouterLink v-for="item in recentHistory" :key="item.id" :to="`/app/practice/attempts/${item.id}`"><span><el-icon><Clock /></el-icon></span><div><strong>{{ historyTypeLabel(item.sourceType) }}</strong><small>{{ date(item.startedAt) }} · {{ item.questionCount }} 题</small></div><em>{{ item.status === 'IN_PROGRESS' ? historyStatusLabel(item.status) : historyAccuracy(item) }}</em><el-icon><ArrowRight /></el-icon></RouterLink></div>
      <div v-else class="compact-state">暂无练习记录，完成第一次练习后会显示在这里。</div>
    </section>

    <section v-if="false" class="tools-summary" aria-label="学习工具概览">
      <article class="is-mistake">
        <span><el-icon><Collection /></el-icon></span>
        <div><small>待巩固错题</small><strong>{{ mistakesTotal }}</strong><em>集中处理薄弱项</em></div>
      </article>
      <article class="is-favorite">
        <span><el-icon><StarFilled /></el-icon></span>
        <div><small>收藏典型题</small><strong>{{ favoritesTotal }}</strong><em>沉淀高价值题目</em></div>
      </article>
      <article class="is-group">
        <span><el-icon><FolderOpened /></el-icon></span>
        <div><small>收藏分类</small><strong>{{ knownGroups.length }}</strong><em>建立个人题目索引</em></div>
      </article>
      <article class="is-diagnosis">
        <span><el-icon><DataAnalysis /></el-icon></span>
        <div>
          <small>已确认错因</small>
          <strong>{{ reasonTotal }}</strong>
          <em>{{ leadingReasonCountSummary }}</em>
        </div>
      </article>
    </section>

    <section v-if="false" class="tools-workspace">
      <header class="tools-workspace__header">
        <div class="tools-tabs" role="tablist" aria-label="学习记录分类">
          <button
            type="button"
            role="tab"
            :aria-selected="activeSection === 'mistakes'"
            :class="{ 'is-active': activeSection === 'mistakes' }"
            @click="selectSection('mistakes')"
          >
            <el-icon><Collection /></el-icon><span>错题本</span><b>{{ mistakesTotal }}</b>
          </button>
          <button
            type="button"
            role="tab"
            :aria-selected="activeSection === 'favorites'"
            :class="{ 'is-active': activeSection === 'favorites' }"
            @click="selectSection('favorites')"
          >
            <el-icon><StarFilled /></el-icon><span>收藏题</span><b>{{ favoritesTotal }}</b>
          </button>
          <button
            type="button"
            role="tab"
            :aria-selected="activeSection === 'dashboard'"
            :class="{ 'is-active': activeSection === 'dashboard' }"
            @click="selectSection('dashboard')"
          >
            <el-icon><DataAnalysis /></el-icon><span>学习数据</span>
          </button>
        </div>
        <button class="tools-icon-button" type="button" title="刷新学习记录" :disabled="status === 'loading'" @click="load">
          <el-icon><RefreshRight /></el-icon>
        </button>
      </header>

      <div v-if="activeSection !== 'dashboard' && status === 'loading'" class="tools-state">
        <span class="tools-state__icon is-loading"><el-icon><RefreshRight /></el-icon></span>
        <strong>正在同步学习记录</strong>
        <p>请稍候，正在整理你的错题与收藏。</p>
      </div>
      <div v-else-if="activeSection !== 'dashboard' && status === 'error'" class="tools-state tools-state--error">
        <span class="tools-state__icon"><el-icon><RefreshRight /></el-icon></span>
        <strong>学习记录加载失败</strong>
        <p>{{ errorMessage }}</p>
        <button type="button" @click="load">重新加载</button>
      </div>

      <template v-else-if="activeSection === 'mistakes'">
        <div class="tools-toolbar">
          <div>
            <strong>错题状态</strong>
            <el-radio-group v-model="mistakeStatus" size="small" @change="load">
              <el-radio-button value="ACTIVE">待巩固</el-radio-button>
              <el-radio-button value="MASTERED">已掌握</el-radio-button>
              <el-radio-button value="">全部</el-radio-button>
            </el-radio-group>
          </div>
          <button class="tools-action" type="button" :disabled="!mistakes.length" @click="exportMistakes">
            <el-icon><Download /></el-icon>导出错题
          </button>
        </div>
        <div v-if="mistakes.length" class="tool-list">
          <article v-for="record in mistakes" :key="record.questionId">
            <span class="tool-list__icon"><el-icon><DataAnalysis /></el-icon></span>
            <div class="tool-list__copy">
              <div class="tool-list__meta">
                <span class="tool-label">{{ typeLabel(record.questionType) }}</span>
                <span>累计错 {{ record.mistakeCount }} 次</span>
              </div>
              <h2>{{ record.questionTitle }}</h2>
              <p>最近答错于 {{ date(record.lastWrongAt) }}</p>
            </div>
            <div class="tool-list__actions">
              <button type="button" class="tools-action" @click="openReview(record)">查看解析</button>
              <button
                type="button"
                class="tools-action tools-action--primary"
                :disabled="Boolean(redoQuestionId)"
                @click="redo(record)"
              >
                {{ redoQuestionId === record.questionId ? '正在创建...' : '重做此题' }}<el-icon><ArrowRight /></el-icon>
              </button>
            </div>
          </article>
        </div>
        <div v-else class="tools-state">
          <span class="tools-state__icon tools-state__icon--success"><el-icon><Collection /></el-icon></span>
          <strong>{{ mistakeStatus === 'ACTIVE' ? '当前没有待巩固错题' : '暂无符合条件的错题' }}</strong>
          <p>完成练习后，答错的题目会自动归入这里。</p>
          <RouterLink to="/practice?mode=DAILY">开始一次练习</RouterLink>
        </div>
      </template>

      <template v-else-if="activeSection === 'favorites'">
        <div class="tools-toolbar">
          <div>
            <strong>收藏分类</strong>
            <el-select v-model="favoriteGroup" aria-label="收藏分类">
              <el-option label="全部分类" value="ALL" />
              <el-option label="未分类" value="未分类" />
              <el-option v-for="group in knownGroups" :key="group" :label="group" :value="group" />
            </el-select>
          </div>
          <button class="tools-action" type="button" :disabled="!displayedFavorites.length" @click="exportFavorites">
            <el-icon><Download /></el-icon>导出收藏
          </button>
        </div>
        <div v-if="displayedFavorites.length" class="tool-list">
          <article v-for="record in displayedFavorites" :key="record.id">
            <span class="tool-list__icon tool-list__icon--favorite"><el-icon><StarFilled /></el-icon></span>
            <div class="tool-list__copy">
              <div class="tool-list__meta">
                <span class="tool-label tool-label--favorite">{{ record.favoriteGroup || '未分类' }}</span>
              </div>
              <h2>{{ record.questionTitle }}</h2>
              <p>收藏于 {{ date(record.createdAt) }}</p>
            </div>
            <div class="tool-list__actions">
              <button type="button" class="tools-action" @click="openCategory(record)">
                <el-icon><FolderOpened /></el-icon>分类管理
              </button>
            </div>
          </article>
        </div>
        <div v-else class="tools-state">
          <span class="tools-state__icon tools-state__icon--favorite"><el-icon><StarFilled /></el-icon></span>
          <strong>还没有符合条件的收藏题</strong>
          <p>刷题时收藏典型题，之后可以在这里集中复习。</p>
          <RouterLink to="/practice?mode=DAILY">去题库看看</RouterLink>
        </div>
      </template>

      <template v-else>
        <div v-if="historyStatus === 'loading'" class="tools-state">
          <span class="tools-state__icon is-loading"><el-icon><RefreshRight /></el-icon></span>
          <strong>正在生成学习数据</strong>
          <p>正在汇总练习记录与当前资格维度。</p>
        </div>
        <div v-else-if="historyStatus === 'error'" class="tools-state tools-state--error">
          <span class="tools-state__icon"><el-icon><DataAnalysis /></el-icon></span>
          <strong>学习数据暂不可用</strong>
          <p>{{ historyError }}</p>
          <button type="button" @click="loadHistory">重新加载</button>
        </div>
        <div v-else class="dashboard-content">
          <section class="dashboard-metrics" aria-label="学习核心数据">
            <article><small>累计练习</small><strong>{{ historyTotal }}</strong><span>次</span><em>当前资格范围</em></article>
            <article><small>累计刷题</small><strong>{{ practicedQuestions }}</strong><span>题</span><em>最近 {{ history.length }} 次记录</em></article>
            <article><small>综合正确率</small><strong>{{ overallAccuracy ?? '—' }}</strong><span>{{ overallAccuracy == null ? '' : '%' }}</span><em>{{ scoredHistory.length ? `${scoredHistory.length} 次有效成绩` : '等待成绩数据' }}</em></article>
            <article><small>学习时长</small><strong>{{ Math.round(learningMinutes / 60 * 10) / 10 }}</strong><span>小时</span><em>按练习起止时间统计</em></article>
          </section>

          <div class="dashboard-grid">
            <section class="dashboard-panel dashboard-trend">
              <header><div><span>正确率趋势</span><h3>最近有效练习表现</h3></div><small>按完成时间排列</small></header>
              <div v-if="trendHistory.length" class="trend-chart" aria-label="正确率趋势图">
                <div v-for="item in trendHistory" :key="item.id" class="trend-column">
                  <strong>{{ item.accuracy }}%</strong>
                  <div><i :style="{ height: `${Math.max(item.accuracy, 4)}%` }"></i></div>
                  <small>{{ new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric' }).format(new Date(item.startedAt)) }}</small>
                </div>
              </div>
              <div v-else class="dashboard-empty">完成有成绩的练习后，将在这里形成正确率趋势。</div>
            </section>

            <section class="dashboard-panel dashboard-mastery">
              <header><div><span>知识点掌握度</span><h3>{{ qualificationStore.selectedQualification?.name ?? '当前资格' }}能力维度</h3></div><small>按资格独立</small></header>
              <div class="mastery-list">
                <div v-for="dimension in qualificationDimensions" :key="dimension"><span>{{ dimension }}</span><div><i></i></div><em>待积累</em></div>
              </div>
              <p>当前练习接口尚未返回知识点掌握分值；维度已按资格适配，待统计数据接入后展示真实进度。</p>
            </section>

            <section class="dashboard-panel dashboard-report">
              <header><div><span>周期报告</span><h3>自动学习摘要</h3></div><small>实时汇总</small></header>
              <div class="report-cards">
                <article><small>近 7 天</small><strong>学习周报</strong><p>{{ reportSummary(weeklyRecords) }}</p></article>
                <article><small>近 30 天</small><strong>学习月报</strong><p>{{ reportSummary(monthlyRecords) }}</p></article>
              </div>
            </section>

            <section class="dashboard-panel dashboard-recent">
              <header><div><span>最近练习</span><h3>继续或回看最近记录</h3></div><RouterLink to="/app/practice/history">查看全部<el-icon><ArrowRight /></el-icon></RouterLink></header>
              <div v-if="recentHistory.length" class="recent-list">
                <RouterLink v-for="item in recentHistory" :key="item.id" :to="`/app/practice/attempts/${item.id}`">
                  <span><el-icon><Clock /></el-icon></span>
                  <div><strong>{{ historyTypeLabel(item.sourceType) }}</strong><small>{{ date(item.startedAt) }} · {{ item.questionCount }} 题</small></div>
                  <em>{{ item.status === 'IN_PROGRESS' ? historyStatusLabel(item.status) : historyAccuracy(item) }}</em>
                  <el-icon><ArrowRight /></el-icon>
                </RouterLink>
              </div>
              <div v-else class="dashboard-empty">暂无练习记录，完成第一次练习后会显示在这里。</div>
            </section>
          </div>
        </div>
      </template>
    </section>

    <el-dialog v-model="categoryDialog" class="tool-dialog" title="收藏题分类" width="420px">
      <el-form label-position="top">
        <el-form-item label="分类名称">
          <el-input v-model="groupDraft" maxlength="96" placeholder="例如：高频易错、案例分析" />
        </el-form-item>
        <div v-if="knownGroups.length" class="tool-group-options">
          <small>已有分类</small>
          <div><button v-for="group in knownGroups" :key="group" type="button" @click="groupDraft = group">{{ group }}</button></div>
        </div>
      </el-form>
      <template #footer>
        <el-button :disabled="categorySaving" @click="categoryDialog = null">取消</el-button>
        <el-button type="primary" :loading="categorySaving" @click="saveCategory">保存分类</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="reviewDialog" class="tool-dialog review-dialog" :title="reviewRecord?.questionTitle || '错题复盘'" width="min(960px, calc(100% - 28px))">
      <div v-if="reviewLoading" class="review-state">正在读取当次作答...</div>
      <div v-else-if="reviewError" class="review-state review-state--error">
        <strong>作答记录加载失败</strong><span>{{ reviewError }}</span>
      </div>
      <div v-else-if="reviewQuestion" class="review-content">
        <QuestionRichContent class="review-question" :content="reviewQuestion.stem" />
        <div class="review-answer-grid">
          <section class="review-answer review-answer--wrong"><small>我的答案</small><strong>{{ answerLabel(reviewUserAnswer) }}</strong></section>
          <section class="review-answer review-answer--correct"><small>正确答案</small><strong>{{ answerLabel(reviewCorrectAnswer) }}</strong></section>
        </div>
        <section class="review-explanation"><h3>解析</h3><p>{{ reviewExplanation || '暂无解析，请进入重做页面查看完整题目反馈。' }}</p></section>
        <section class="review-diagnosis">
          <header>
            <div><span>错因诊断</span><h3>这道题为什么会做错？</h3></div>
            <small>可多选，保存后用于汇总薄弱项</small>
          </header>
          <div class="reason-options">
            <label v-for="item in reasonOptions" :key="item.code" :class="{ 'is-selected': selectedReasonCodes.includes(item.code) }">
              <input v-model="selectedReasonCodes" type="checkbox" :value="item.code">
              <span><strong>{{ item.label }}</strong><small>{{ item.description }}</small></span>
              <em v-if="isSystemSuggested(item.code)">系统建议</em>
              <em v-else-if="selectedReasonCodes.includes(item.code)">已确认</em>
            </label>
          </div>
          <div class="diagnosis-actions">
            <span v-if="reviewInsightError">部分诊断数据加载失败：{{ reviewInsightError }}</span>
            <span v-else>{{ selectedReasonCodes.length ? `已选择 ${selectedReasonCodes.length} 项错因` : '未选择错因' }}</span>
            <el-button type="primary" plain :loading="reasonSaving" @click="saveMistakeReasons">保存错因</el-button>
          </div>
        </section>
        <section class="review-recommendations">
          <header>
            <div><span>同类巩固</span><h3>围绕这道错题继续练习</h3></div>
            <small>按知识点重合度推荐</small>
          </header>
          <div v-if="reviewInsightsLoading" class="recommendation-state">正在匹配同类题...</div>
          <div v-else-if="recommendations.length" class="recommendation-list">
            <article v-for="item in recommendations" :key="item.questionId">
              <div>
                <span>{{ typeLabel(item.questionType) }}</span>
                <span>{{ difficultyLabel(item.difficulty) }}</span>
                <span>知识点重合 {{ item.knowledgeOverlap }}</span>
              </div>
              <h4>{{ item.title || `题目 ${item.questionId}` }}</h4>
              <button type="button" :disabled="Boolean(recommendationQuestionId)" @click="startRecommendation(item)">
                {{ recommendationQuestionId === item.questionId ? '正在创建...' : '开始巩固' }}<el-icon><ArrowRight /></el-icon>
              </button>
            </article>
          </div>
          <div v-else class="recommendation-state">暂未找到合适的同类题，之后再来看看。</div>
        </section>
      </div>
      <template #footer>
        <el-button @click="reviewDialog = false">关闭</el-button>
        <el-button v-if="reviewRecord" type="primary" :loading="redoQuestionId === reviewRecord.questionId" @click="redo(reviewRecord)">重做此题</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.tools-page {
  min-height: calc(100vh - 56px);
  padding: 26px 24px 52px;
  color: #172033;
  background: #f5f7fa;
}

.tools-heading,
.tools-summary,
.tools-workspace,
.workbench-overview,
.workbench-main-grid,
.workbench-analytics,
.workbench-recent {
  width: min(1180px, 100%);
  margin-right: auto;
  margin-left: auto;
}

.tools-heading {
  display: flex;
  min-height: 82px;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
}

.tools-heading__eyebrow {
  color: #2563eb;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: .06em;
}

.tools-heading h1 {
  margin: 5px 0 4px;
  color: #172033;
  font-size: 28px;
  line-height: 1.25;
  letter-spacing: -.025em;
}

.tools-heading p {
  margin: 0;
  color: #6b788b;
  font-size: 13px;
}

.tools-heading__actions {
  display: flex;
  flex: 0 0 auto;
  gap: 8px;
}

.tools-heading__actions a,
.tools-action {
  display: inline-flex;
  min-height: 38px;
  padding: 0 14px;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 1px solid #d5deea;
  border-radius: 9px;
  color: #475569;
  background: #fff;
  text-decoration: none;
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  transition: border-color .16s ease, color .16s ease, background .16s ease, box-shadow .16s ease;
}

.tools-heading__actions a:hover,
.tools-action:hover:not(:disabled) {
  border-color: #a9c1ef;
  color: #2563eb;
  background: #f8fbff;
}

.tools-heading__actions a.is-primary,
.tools-action--primary {
  border-color: #2563eb;
  color: #fff;
  background: #2563eb;
  box-shadow: 0 7px 16px rgba(37, 99, 235, .16);
}

.tools-heading__actions a.is-primary:hover,
.tools-action--primary:hover:not(:disabled) {
  border-color: #1d4ed8;
  color: #fff;
  background: #1d4ed8;
}

.workbench-overview {
  display: grid;
  min-height: 184px;
  margin-top: 14px;
  padding: 20px 22px;
  grid-template-columns: 1.25fr 2fr .9fr;
  align-items: stretch;
  gap: 22px;
  border: 1px solid #dce4ee;
  border-radius: 18px;
  background: #fff;
  box-shadow: 0 12px 34px rgba(15, 23, 42, .055);
}

.overview-score {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 16px;
}

.overview-donut,
.favorite-donut {
  position: relative;
  display: grid;
  width: 104px;
  height: 104px;
  flex: 0 0 104px;
  place-items: center;
  border-radius: 50%;
}

.overview-donut::after,
.favorite-donut::after {
  position: absolute;
  width: 76px;
  height: 76px;
  border-radius: 50%;
  background: #fff;
  content: '';
}

.overview-donut > span,
.favorite-donut > span {
  z-index: 1;
  display: grid;
  justify-items: center;
  gap: 2px;
}

.overview-donut strong { color: #1e293b; font-size: 25px; }.overview-donut small { color: #64748b; font-size: 9px; }
.overview-score > div:last-child { display: grid; min-width: 0; gap: 5px; }.overview-score > div:last-child > span { color: #2563eb; font-size: 10px; font-weight: 700; letter-spacing: .06em; }.overview-score h2 { margin: 0; color: #273244; font-size: 15px; line-height: 1.5; }.overview-score p { margin: 0; color: #8b97a8; font-size: 10px; line-height: 1.6; }
.overview-metrics { display: grid; padding: 2px 0; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.overview-metrics article { display: grid; min-width: 0; padding: 12px 14px; grid-template-columns: auto 1fr; align-content: center; align-items: baseline; gap: 2px 5px; border-left: 2px solid #dbe7fb; border-radius: 0 9px 9px 0; background: #f8fafc; }.overview-metrics article.is-alert { border-left-color: #efb1b1; }.overview-metrics small,.overview-metrics span { grid-column: 1 / -1; color: #7b8798; font-size: 9px; }.overview-metrics strong { color: #1e293b; font-size: 23px; }.overview-metrics em { color: #64748b; font-size: 10px; font-style: normal; }.overview-metrics span { overflow: hidden; color: #94a3b8; text-overflow: ellipsis; white-space: nowrap; }
.overview-spark { display: grid; padding-left: 20px; grid-template-rows: auto 1fr; border-left: 1px solid #edf1f5; }.overview-spark > div:first-child { display: grid; gap: 3px; }.overview-spark > div:first-child span { color: #334155; font-size: 12px; font-weight: 700; }.overview-spark small { color: #94a3b8; font-size: 9px; }.spark-bars { display: flex; min-height: 92px; padding-top: 13px; align-items: end; justify-content: center; gap: 5px; }.spark-bars i { width: 8px; min-height: 5px; border-radius: 4px 4px 2px 2px; background: linear-gradient(180deg, #72a1f3, #2563eb); }.spark-bars span { align-self: center; color: #94a3b8; font-size: 9px; }

.workbench-main-grid { display: grid; margin-top: 14px; grid-template-columns: minmax(0, 1.75fr) minmax(300px, .85fr); align-items: stretch; gap: 12px; }
.workbench-panel,.workbench-analytics,.workbench-recent { overflow: hidden; border: 1px solid #dce4ee; border-radius: 16px; background: #fff; box-shadow: 0 8px 26px rgba(15, 23, 42, .04); }
.workbench-panel__header,.analytics-heading,.workbench-recent > header,.analytics-card > header { display: flex; min-height: 76px; padding: 14px 18px; align-items: center; justify-content: space-between; gap: 14px; border-bottom: 1px solid #e8edf3; }.workbench-panel__header > div:first-child,.analytics-heading > div,.workbench-recent > header > div,.analytics-card > header > div { display: grid; gap: 3px; }.workbench-panel__header span,.analytics-heading span,.workbench-recent > header span,.analytics-card > header span { color: #2563eb; font-size: 9px; font-weight: 700; letter-spacing: .08em; }.workbench-panel__header h2,.analytics-heading h2,.workbench-recent h2 { margin: 0; color: #263247; font-size: 15px; }.workbench-panel__header p { margin: 0; color: #8b97a8; font-size: 10px; }
.panel-actions { display: flex; align-items: center; gap: 8px; }.panel-actions > button,.panel-export,.analytics-heading > button { display: inline-flex; min-height: 34px; padding: 0 10px; align-items: center; gap: 5px; border: 1px solid #d7e0eb; border-radius: 8px; color: #64748b; background: #fff; cursor: pointer; font-size: 10px; }.panel-actions > button:disabled,.panel-export:disabled { cursor: not-allowed; opacity: .45; }
.mistake-priority-list { padding: 2px 18px 10px; }.mistake-priority-list article { display: grid; min-height: 93px; padding: 14px 0; grid-template-columns: 34px minmax(0, 1fr) 78px auto; align-items: center; gap: 12px; border-bottom: 1px solid #edf1f5; }.mistake-priority-list article:last-child { border-bottom: 0; }.mistake-index { display: grid; width: 32px; height: 32px; place-items: center; border-radius: 9px; color: #b85454; background: #fdf1f1; font-size: 10px; font-weight: 700; }.mistake-copy { min-width: 0; }.mistake-copy > div { display: flex; align-items: center; gap: 7px; }.mistake-copy > div span { padding: 3px 7px; border-radius: 999px; color: #1d4ed8; background: #edf4ff; font-size: 9px; }.mistake-copy em { color: #b56a34; font-size: 9px; font-style: normal; }.mistake-copy h3 { margin: 7px 0 4px; overflow: hidden; color: #334155; font-size: 12px; line-height: 1.5; text-overflow: ellipsis; white-space: nowrap; }.mistake-copy p { margin: 0; color: #94a3b8; font-size: 9px; }.mistake-reason { display: grid; gap: 4px; }.mistake-reason small { color: #94a3b8; font-size: 8px; }.mistake-reason strong { color: #8b5a36; font-size: 10px; }.mistake-actions { display: flex; gap: 6px; }.mistake-actions button { display: inline-flex; min-height: 32px; padding: 0 9px; align-items: center; gap: 4px; border: 1px solid #d7e0eb; border-radius: 8px; color: #526078; background: #fff; cursor: pointer; font-size: 10px; }.mistake-actions button.is-primary { border-color: #2563eb; color: #fff; background: #2563eb; }.mistake-actions button:disabled { cursor: wait; opacity: .5; }
.favorite-insight { display: flex; min-height: 130px; padding: 16px 18px; align-items: center; gap: 18px; border-bottom: 1px solid #edf1f5; }.favorite-donut { width: 86px; height: 86px; flex-basis: 86px; }.favorite-donut::after { width: 62px; height: 62px; }.favorite-donut strong { color: #5f4a29; font-size: 20px; }.favorite-donut small { color: #9a7a46; font-size: 8px; }.favorite-insight > div:last-child { display: grid; min-width: 0; gap: 4px; }.favorite-insight > div:last-child small { color: #8b97a8; font-size: 9px; }.favorite-insight > div:last-child strong { color: #334155; font-size: 16px; }.favorite-insight > div:last-child p { margin: 0; overflow: hidden; color: #a28a64; font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }.favorite-filter { padding: 10px 18px; border-bottom: 1px solid #edf1f5; background: #fbfcfe; }.favorite-filter :deep(.el-select) { width: 100%; }.favorite-filter :deep(.el-select__wrapper) { min-height: 34px; border-radius: 8px; }.favorite-preview-list { padding: 2px 18px 12px; }.favorite-preview-list article { display: grid; min-height: 62px; grid-template-columns: 32px minmax(0, 1fr) 30px; align-items: center; gap: 9px; border-bottom: 1px solid #edf1f5; }.favorite-preview-list article:last-child { border-bottom: 0; }.favorite-preview-list article > span { display: grid; width: 30px; height: 30px; place-items: center; border-radius: 8px; color: #b7791f; background: #fff6e5; }.favorite-preview-list article > div { display: grid; min-width: 0; gap: 4px; }.favorite-preview-list strong { overflow: hidden; color: #334155; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }.favorite-preview-list small { color: #94a3b8; font-size: 8px; }.favorite-preview-list button { display: grid; width: 28px; height: 28px; padding: 0; place-items: center; border: 0; border-radius: 7px; color: #64748b; background: transparent; cursor: pointer; }
.compact-state { display: flex; min-height: 150px; padding: 24px; align-items: center; justify-content: center; gap: 8px; color: #8b97a8; font-size: 11px; text-align: center; }.compact-state.is-error { color: #b04d4d; }.compact-state button { padding: 5px 9px; border: 1px solid #d8e1ec; border-radius: 7px; background: #fff; cursor: pointer; }

.workbench-analytics { margin-top: 14px; }.analytics-heading > button { color: #2563eb; }.analytics-grid { display: grid; padding: 12px; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; background: #f8fafc; }.analytics-card { overflow: hidden; min-height: 275px; border: 1px solid #e0e7f0; border-radius: 13px; background: #fff; }.analytics-card > header { min-height: 64px; padding: 12px 15px; }.analytics-card h3 { margin: 0; color: #334155; font-size: 13px; }.analytics-card header > small { color: #94a3b8; font-size: 9px; }
.mastery-radar { display: grid; min-height: 175px; padding: 14px 16px 6px; grid-template-columns: 150px 1fr; align-items: center; gap: 18px; }.radar-visual { position: relative; display: grid; width: 138px; height: 138px; place-items: center; border-radius: 50%; background: repeating-radial-gradient(circle, transparent 0 20px, #dfe7f0 21px 22px), conic-gradient(from 18deg, rgba(37,99,235,.12), rgba(15,118,110,.18), rgba(37,99,235,.12)); clip-path: polygon(50% 0, 97% 35%, 79% 91%, 21% 91%, 3% 35%); }.radar-visual i { position: absolute; width: 1px; height: 100%; background: rgba(148,163,184,.25); }.radar-visual i:nth-child(2) { transform: rotate(72deg); }.radar-visual i:nth-child(3) { transform: rotate(144deg); }.radar-visual span { z-index: 1; padding: 4px 7px; border-radius: 999px; color: #64748b; background: rgba(255,255,255,.88); font-size: 8px; }.radar-legend { display: grid; gap: 6px; }.radar-legend > div { display: grid; grid-template-columns: 64px 1fr 14px; align-items: center; gap: 7px; }.radar-legend span,.radar-legend em { color: #64748b; font-size: 9px; font-style: normal; }.radar-legend i { height: 5px; border-radius: 999px; background: #edf1f5; }.mastery-card > p { margin: 0 16px 13px; color: #94a3b8; font-size: 8px; line-height: 1.5; }
.schedule-timeline { display: grid; padding: 13px 16px 16px; gap: 8px; }.schedule-timeline > div { display: grid; grid-template-columns: 58px 1fr 54px; align-items: center; gap: 9px; }.schedule-timeline > div > span { display: grid; gap: 2px; }.schedule-timeline strong { color: #526078; font-size: 9px; }.schedule-timeline small { color: #94a3b8; font-size: 8px; }.schedule-timeline > div > div { height: 15px; padding: 3px; border-radius: 5px; background: #eef2f7; }.schedule-timeline i { display: block; height: 100%; border-radius: 3px; background: linear-gradient(90deg, #6a9cf1, #3e7be8); }.schedule-timeline em { color: #7b8798; font-size: 8px; font-style: normal; text-align: right; }
.workbench-recent { margin-top: 14px; }.workbench-recent > header > a { display: inline-flex; align-items: center; gap: 4px; color: #2563eb; font-size: 10px; text-decoration: none; }.workbench-recent .recent-list { display: grid; padding: 4px 18px 14px; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }.workbench-recent .recent-list > a { min-height: 76px; padding: 10px; border: 1px solid #e2e8f0; border-radius: 10px; }

.tools-summary {
  display: grid;
  margin-top: 14px;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.tools-summary article {
  display: flex;
  min-height: 106px;
  padding: 18px 20px;
  align-items: center;
  gap: 15px;
  border: 1px solid #dde5ef;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 5px 18px rgba(15, 23, 42, .035);
}

.tools-summary article > span {
  display: grid;
  width: 44px;
  height: 44px;
  flex: 0 0 44px;
  place-items: center;
  border-radius: 12px;
  color: #2563eb;
  background: #edf4ff;
  font-size: 20px;
}

.tools-summary article.is-favorite > span {
  color: #b7791f;
  background: #fff6e5;
}

.tools-summary article.is-group > span {
  color: #0f766e;
  background: #eaf8f5;
}

.tools-summary article.is-diagnosis > span {
  color: #7c3aed;
  background: #f3efff;
}

.tools-summary article > div {
  display: grid;
  min-width: 0;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: baseline;
  gap: 3px 10px;
}

.tools-summary small {
  color: #64748b;
  font-size: 12px;
}

.tools-summary strong {
  grid-row: 1 / 3;
  grid-column: 1;
  color: #1e293b;
  font-size: 27px;
  line-height: 1;
}

.tools-summary small,
.tools-summary em {
  grid-column: 2;
}

.tools-summary em {
  overflow: hidden;
  color: #94a3b8;
  font-size: 11px;
  font-style: normal;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tools-workspace {
  overflow: hidden;
  min-height: 430px;
  margin-top: 14px;
  border: 1px solid #dce4ee;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 10px 30px rgba(15, 23, 42, .045);
}

.tools-workspace__header {
  display: flex;
  min-height: 66px;
  padding: 0 18px;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e7edf4;
}

.tools-tabs {
  display: flex;
  align-self: stretch;
  gap: 6px;
}

.tools-tabs button {
  position: relative;
  display: flex;
  min-width: 112px;
  height: 100%;
  padding: 0 13px;
  align-items: center;
  justify-content: center;
  gap: 7px;
  border: 0;
  color: #718096;
  background: transparent;
  cursor: pointer;
  font-size: 14px;
}

.tools-tabs button::after {
  position: absolute;
  right: 13px;
  bottom: 0;
  left: 13px;
  height: 3px;
  border-radius: 3px 3px 0 0;
  background: transparent;
  content: '';
}

.tools-tabs button.is-active {
  color: #2563eb;
  font-weight: 700;
}

.tools-tabs button.is-active::after {
  background: #2563eb;
}

.tools-tabs b {
  min-width: 20px;
  padding: 2px 6px;
  border-radius: 999px;
  color: #64748b;
  background: #f1f5f9;
  font-size: 10px;
}

.tools-tabs button.is-active b {
  color: #1d4ed8;
  background: #eaf1ff;
}

.tools-icon-button {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  border: 0;
  border-radius: 9px;
  color: #64748b;
  background: transparent;
  cursor: pointer;
  font-size: 17px;
}

.tools-icon-button:hover:not(:disabled) {
  color: #2563eb;
  background: #f1f5f9;
}

.tools-icon-button:disabled {
  cursor: wait;
  opacity: .5;
}

.tools-toolbar {
  display: flex;
  min-height: 64px;
  padding: 11px 18px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-bottom: 1px solid #edf1f5;
  background: #fbfcfe;
}

.tools-toolbar > div {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 12px;
}

.tools-toolbar > div > strong {
  color: #526078;
  font-size: 12px;
  white-space: nowrap;
}

.tools-toolbar :deep(.el-radio-button__inner) {
  min-height: 34px;
  padding: 9px 13px;
  border-color: #dbe3ed;
  box-shadow: none;
}

.tools-toolbar :deep(.el-radio-button:first-child .el-radio-button__inner) {
  border-radius: 8px 0 0 8px;
}

.tools-toolbar :deep(.el-radio-button:last-child .el-radio-button__inner) {
  border-radius: 0 8px 8px 0;
}

.tools-toolbar :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) {
  border-color: #2563eb;
  background: #2563eb;
  box-shadow: -1px 0 0 0 #2563eb;
}

.tools-toolbar :deep(.el-select) {
  width: 180px;
}

.tools-toolbar :deep(.el-select__wrapper) {
  min-height: 36px;
  border-radius: 8px;
}

.tools-action {
  min-height: 36px;
  padding: 0 13px;
  font-size: 12px;
}

.tools-action:disabled {
  cursor: not-allowed;
  opacity: .5;
  box-shadow: none;
}

.tool-list {
  padding: 4px 18px 16px;
}

.tool-list article {
  display: grid;
  min-height: 98px;
  padding: 16px 2px;
  grid-template-columns: 42px minmax(0, 1fr) auto;
  align-items: center;
  gap: 14px;
  border-bottom: 1px solid #edf1f5;
}

.tool-list article:last-child {
  border-bottom: 0;
}

.tool-list__icon {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  border-radius: 11px;
  color: #dc2626;
  background: #fef2f2;
  font-size: 18px;
}

.tool-list__icon--favorite {
  color: #b7791f;
  background: #fff6e5;
}

.tool-list__copy {
  min-width: 0;
}

.tool-list__meta {
  display: flex;
  align-items: center;
  gap: 9px;
  color: #94a3b8;
  font-size: 11px;
}

.tool-label {
  display: inline-flex;
  min-height: 22px;
  padding: 0 8px;
  align-items: center;
  border-radius: 999px;
  color: #1d4ed8;
  background: #edf4ff;
  font-size: 10px;
  font-weight: 600;
}

.tool-label--favorite {
  color: #9a6815;
  background: #fff4df;
}

.tool-list h2 {
  margin: 7px 0 5px;
  overflow: hidden;
  color: #263247;
  font-size: 14px;
  line-height: 1.5;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-list p {
  margin: 0;
  color: #8b97a8;
  font-size: 11px;
}

.tool-list__actions {
  display: flex;
  gap: 8px;
}

.dashboard-content {
  padding: 18px;
  background: #f8fafc;
}

.dashboard-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.dashboard-metrics article {
  display: grid;
  min-height: 108px;
  padding: 16px;
  grid-template-columns: auto 1fr;
  align-content: center;
  align-items: baseline;
  gap: 4px 6px;
  border: 1px solid #e0e7f0;
  border-radius: 12px;
  background: #fff;
}

.dashboard-metrics small,
.dashboard-metrics em {
  grid-column: 1 / -1;
  color: #7b8798;
  font-size: 10px;
  font-style: normal;
}

.dashboard-metrics strong {
  color: #1e293b;
  font-size: 27px;
  line-height: 1.1;
}

.dashboard-metrics article > span {
  color: #64748b;
  font-size: 11px;
}

.dashboard-metrics em {
  color: #94a3b8;
}

.dashboard-grid {
  display: grid;
  margin-top: 12px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.dashboard-panel {
  overflow: hidden;
  min-height: 268px;
  border: 1px solid #e0e7f0;
  border-radius: 13px;
  background: #fff;
}

.dashboard-panel > header {
  display: flex;
  min-height: 68px;
  padding: 13px 16px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-bottom: 1px solid #edf1f5;
}

.dashboard-panel > header > div {
  display: grid;
  gap: 3px;
}

.dashboard-panel > header span {
  color: #2563eb;
  font-size: 9px;
  font-weight: 700;
  letter-spacing: .08em;
}

.dashboard-panel h3 {
  margin: 0;
  color: #334155;
  font-size: 14px;
}

.dashboard-panel header > small,
.dashboard-panel header > a {
  color: #8b97a8;
  font-size: 10px;
}

.dashboard-panel header > a {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #2563eb;
  text-decoration: none;
}

.trend-chart {
  display: flex;
  height: 198px;
  padding: 24px 18px 14px;
  align-items: end;
  justify-content: space-around;
  gap: 8px;
}

.trend-column {
  display: grid;
  height: 100%;
  flex: 1;
  grid-template-rows: 18px 1fr 18px;
  justify-items: center;
  gap: 5px;
}

.trend-column strong,
.trend-column small {
  color: #64748b;
  font-size: 9px;
  font-weight: 500;
}

.trend-column > div {
  position: relative;
  width: min(28px, 72%);
  height: 100%;
  overflow: hidden;
  border-radius: 6px 6px 3px 3px;
  background: #eef2f7;
}

.trend-column i {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  border-radius: 6px 6px 3px 3px;
  background: linear-gradient(180deg, #5b8def, #2563eb);
}

.mastery-list {
  display: grid;
  padding: 14px 16px 8px;
  gap: 8px;
}

.mastery-list > div {
  display: grid;
  grid-template-columns: 72px 1fr 42px;
  align-items: center;
  gap: 8px;
}

.mastery-list span,
.mastery-list em {
  color: #64748b;
  font-size: 10px;
  font-style: normal;
}

.mastery-list em {
  color: #94a3b8;
  text-align: right;
}

.mastery-list > div > div {
  height: 6px;
  overflow: hidden;
  border-radius: 999px;
  background: #edf1f5;
}

.mastery-list i {
  display: block;
  width: 0;
  height: 100%;
}

.dashboard-mastery > p {
  margin: 5px 16px 14px;
  color: #94a3b8;
  font-size: 9px;
  line-height: 1.6;
}

.report-cards {
  display: grid;
  padding: 14px 16px 16px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.report-cards article {
  min-height: 156px;
  padding: 16px;
  border: 1px solid #e0e7f0;
  border-radius: 11px;
  background: #fbfcfe;
}

.report-cards small {
  display: inline-flex;
  padding: 3px 7px;
  border-radius: 999px;
  color: #0f766e;
  background: #eaf8f5;
  font-size: 9px;
}

.report-cards strong {
  display: block;
  margin-top: 11px;
  color: #334155;
  font-size: 14px;
}

.report-cards p {
  margin: 8px 0 0;
  color: #718096;
  font-size: 11px;
  line-height: 1.7;
}

.recent-list {
  padding: 5px 16px 12px;
}

.recent-list > a {
  display: grid;
  min-height: 61px;
  padding: 8px 0;
  grid-template-columns: 34px minmax(0, 1fr) auto 16px;
  align-items: center;
  gap: 10px;
  border-bottom: 1px solid #edf1f5;
  color: inherit;
  text-decoration: none;
}

.recent-list > a:last-child {
  border-bottom: 0;
}

.recent-list > a > span {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 9px;
  color: #2563eb;
  background: #edf4ff;
}

.recent-list > a > div {
  display: grid;
  min-width: 0;
  gap: 4px;
}

.recent-list strong {
  color: #334155;
  font-size: 12px;
}

.recent-list small,
.recent-list em {
  color: #94a3b8;
  font-size: 9px;
  font-style: normal;
}

.recent-list em {
  color: #2563eb;
  font-size: 11px;
}

.dashboard-empty {
  display: grid;
  min-height: 190px;
  padding: 24px;
  place-items: center;
  color: #94a3b8;
  font-size: 11px;
  text-align: center;
}

.tools-state {
  display: grid;
  min-height: 300px;
  padding: 36px 24px;
  place-items: center;
  align-content: center;
  gap: 7px;
  color: #8290a3;
  text-align: center;
}

.tools-state__icon {
  display: grid;
  width: 48px;
  height: 48px;
  margin-bottom: 6px;
  place-items: center;
  border-radius: 14px;
  color: #64748b;
  background: #f1f5f9;
  font-size: 21px;
}

.tools-state__icon.is-loading .el-icon {
  animation: tools-spin 1.2s linear infinite;
}

.tools-state__icon--success {
  color: #15803d;
  background: #eef9f1;
}

.tools-state__icon--favorite {
  color: #b7791f;
  background: #fff6e5;
}

.tools-state strong {
  color: #334155;
  font-size: 15px;
}

.tools-state p {
  margin: 0 0 8px;
  font-size: 12px;
}

.tools-state > button,
.tools-state > a {
  padding: 7px 12px;
  border: 1px solid #c8d8f6;
  border-radius: 8px;
  color: #2563eb;
  background: #f7faff;
  cursor: pointer;
  font-size: 12px;
  text-decoration: none;
}

.tools-state--error .tools-state__icon {
  color: #dc2626;
  background: #fef2f2;
}

.tools-state--error strong {
  color: #991b1b;
}

.tool-group-options {
  display: grid;
  gap: 8px;
}

.tool-group-options small {
  color: #7b8798;
}

.tool-group-options > div {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.tool-group-options button {
  padding: 6px 9px;
  border: 1px solid #d6e2f2;
  border-radius: 7px;
  color: #2563eb;
  background: #f7fbff;
  cursor: pointer;
  font-size: 12px;
}

:deep(.tool-dialog) {
  overflow: hidden;
  border: 1px solid rgba(148, 163, 184, .28);
  border-radius: 16px;
  box-shadow: 0 24px 64px rgba(15, 23, 42, .18);
}

:deep(.tool-dialog .el-dialog__header) {
  padding: 19px 22px;
  margin: 0;
  border-bottom: 1px solid #e7edf4;
}

:deep(.tool-dialog .el-dialog__title) {
  color: #172033;
  font-size: 16px;
  font-weight: 700;
}

:deep(.tool-dialog .el-dialog__body) {
  padding: 22px;
}

:deep(.tool-dialog .el-dialog__footer) {
  padding: 14px 22px 18px;
  border-top: 1px solid #edf1f5;
}

:deep(.tool-dialog .el-button) {
  min-width: 88px;
  border-radius: 9px;
}

.review-content {
  display: grid;
  gap: 16px;
}

.review-question {
  margin: 0;
  padding: 16px;
  border: 1px solid #e4eaf1;
  border-radius: 11px;
  color: #334155;
  background: #f8fafc;
  font-size: 14px;
  line-height: 1.75;
}

.review-answer-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.review-answer {
  display: grid;
  min-height: 86px;
  padding: 14px 16px;
  align-content: center;
  gap: 8px;
  border: 1px solid #e1e7ee;
  border-radius: 11px;
  background: #fbfcfe;
}

.review-answer small {
  color: #7e8ba0;
  font-size: 11px;
}

.review-answer strong {
  color: #344a6b;
  font-size: 18px;
}

.review-answer--wrong {
  border-color: #f3cccc;
  background: #fff7f7;
}

.review-answer--wrong strong {
  color: #b54b4b;
}

.review-answer--correct {
  border-color: #cce7dc;
  background: #f3fbf7;
}

.review-answer--correct strong {
  color: #237c5e;
}

.review-explanation {
  padding: 17px;
  border: 1px solid #e4eaf1;
  border-radius: 11px;
}

.review-explanation h3 {
  margin: 0 0 8px;
  color: #263247;
  font-size: 14px;
}

.review-explanation p {
  margin: 0;
  color: #5f6f85;
  font-size: 13px;
  line-height: 1.8;
  white-space: pre-wrap;
}

.review-diagnosis,
.review-recommendations {
  overflow: hidden;
  border: 1px solid #e4eaf1;
  border-radius: 12px;
}

.review-diagnosis > header,
.review-recommendations > header {
  display: flex;
  min-height: 70px;
  padding: 14px 17px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  border-bottom: 1px solid #e8edf3;
  background: #fbfcfe;
}

.review-diagnosis > header div,
.review-recommendations > header div {
  display: grid;
  gap: 3px;
}

.review-diagnosis header span,
.review-recommendations header span {
  color: #2563eb;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: .08em;
}

.review-diagnosis h3,
.review-recommendations h3 {
  margin: 0;
  color: #263247;
  font-size: 14px;
}

.review-diagnosis header > small,
.review-recommendations header > small {
  color: #8b97a8;
  font-size: 11px;
}

.reason-options {
  display: grid;
  padding: 14px 17px;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.reason-options label {
  position: relative;
  display: flex;
  min-height: 72px;
  padding: 12px;
  align-items: flex-start;
  gap: 9px;
  border: 1px solid #dfe6ef;
  border-radius: 10px;
  background: #fff;
  cursor: pointer;
  transition: border-color .16s ease, background .16s ease, box-shadow .16s ease;
}

.reason-options label.is-selected {
  border-color: #8fb0ef;
  background: #f5f8ff;
  box-shadow: inset 0 0 0 1px rgba(37, 99, 235, .08);
}

.reason-options input {
  margin-top: 3px;
  accent-color: #2563eb;
}

.reason-options label > span {
  display: grid;
  min-width: 0;
  gap: 4px;
}

.reason-options strong {
  color: #334155;
  font-size: 13px;
}

.reason-options small {
  color: #8b97a8;
  font-size: 10px;
  line-height: 1.5;
}

.reason-options em {
  position: absolute;
  top: 8px;
  right: 8px;
  padding: 2px 5px;
  border-radius: 999px;
  color: #7c3aed;
  background: #f2edff;
  font-size: 9px;
  font-style: normal;
}

.diagnosis-actions {
  display: flex;
  min-height: 54px;
  padding: 8px 17px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-top: 1px solid #edf1f5;
  background: #fbfcfe;
}

.diagnosis-actions > span {
  color: #7b8798;
  font-size: 11px;
}

.recommendation-list {
  display: grid;
  padding: 14px 17px 17px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.recommendation-list article {
  position: relative;
  min-height: 116px;
  padding: 14px 116px 14px 14px;
  border: 1px solid #e0e7f0;
  border-radius: 10px;
  background: #fff;
}

.recommendation-list article > div {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.recommendation-list article > div span {
  padding: 3px 6px;
  border-radius: 999px;
  color: #64748b;
  background: #f1f5f9;
  font-size: 9px;
}

.recommendation-list h4 {
  margin: 10px 0 0;
  overflow: hidden;
  color: #334155;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.6;
  text-overflow: ellipsis;
}

.recommendation-list button {
  position: absolute;
  right: 13px;
  bottom: 13px;
  display: inline-flex;
  min-height: 34px;
  padding: 0 10px;
  align-items: center;
  gap: 4px;
  border: 1px solid #b9ccf2;
  border-radius: 8px;
  color: #2563eb;
  background: #f7faff;
  cursor: pointer;
  font-size: 11px;
  font-weight: 600;
}

.recommendation-list button:disabled {
  cursor: wait;
  opacity: .55;
}

.recommendation-state {
  display: grid;
  min-height: 92px;
  padding: 18px;
  place-items: center;
  color: #8b97a8;
  font-size: 12px;
}

.review-state {
  display: grid;
  min-height: 200px;
  place-items: center;
  align-content: center;
  gap: 7px;
  color: #8995a6;
  font-size: 13px;
}

.review-state--error {
  color: #b04d4d;
}

@keyframes tools-spin {
  to { transform: rotate(360deg); }
}

@media (max-width: 760px) {
  .tools-page {
    padding: 16px 12px 34px;
  }

  .tools-heading {
    min-height: 0;
    align-items: flex-start;
    flex-direction: column;
    gap: 14px;
  }

  .tools-heading__actions {
    width: 100%;
  }

  .tools-heading__actions a {
    flex: 1;
  }

  .workbench-overview {
    padding: 16px;
    grid-template-columns: 1fr;
    gap: 16px;
  }

  .overview-score {
    align-items: flex-start;
  }

  .overview-donut {
    width: 88px;
    height: 88px;
    flex-basis: 88px;
  }

  .overview-donut::after {
    width: 64px;
    height: 64px;
  }

  .overview-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .overview-spark {
    min-height: 120px;
    padding: 14px 0 0;
    border-top: 1px solid #edf1f5;
    border-left: 0;
  }

  .workbench-main-grid,
  .analytics-grid {
    grid-template-columns: 1fr;
  }

  .workbench-panel__header,
  .analytics-heading {
    align-items: flex-start;
    flex-direction: column;
  }

  .panel-actions {
    width: 100%;
    justify-content: space-between;
  }

  .mistake-priority-list article {
    grid-template-columns: 32px minmax(0, 1fr);
  }

  .mistake-reason {
    grid-column: 2;
  }

  .mistake-actions {
    grid-column: 1 / -1;
    justify-content: flex-end;
  }

  .mastery-radar {
    grid-template-columns: 1fr;
    justify-items: center;
  }

  .radar-legend {
    width: 100%;
  }

  .workbench-recent .recent-list {
    grid-template-columns: 1fr;
  }

  .tools-summary {
    grid-template-columns: 1fr;
    gap: 9px;
  }

  .tools-summary article {
    min-height: 82px;
    padding: 13px 15px;
  }

  .tools-workspace__header {
    min-height: 60px;
    padding: 0 10px;
  }

  .tools-tabs button {
    min-width: 88px;
    padding: 0 8px;
  }

  .tools-tabs {
    overflow-x: auto;
  }

  .tools-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .tools-toolbar > div {
    align-items: stretch;
    flex-direction: column;
    gap: 7px;
  }

  .tools-toolbar :deep(.el-select) {
    width: 100%;
  }

  .tools-toolbar > .tools-action {
    align-self: flex-end;
  }

  .tool-list {
    padding: 2px 14px 14px;
  }

  .tool-list article {
    padding: 16px 0;
    grid-template-columns: 38px minmax(0, 1fr);
    align-items: start;
    gap: 11px;
  }

  .tool-list__icon {
    width: 38px;
    height: 38px;
  }

  .tool-list__actions {
    width: 100%;
    grid-column: 1 / -1;
    justify-content: flex-end;
  }

  .tool-list h2 {
    white-space: normal;
  }

  .dashboard-content {
    padding: 10px;
  }

  .dashboard-metrics,
  .dashboard-grid,
  .report-cards {
    grid-template-columns: 1fr;
  }

  .dashboard-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .dashboard-metrics article {
    min-height: 96px;
    padding: 13px;
  }

  .trend-chart {
    padding-right: 10px;
    padding-left: 10px;
    gap: 4px;
  }

  .mastery-list > div {
    grid-template-columns: 64px 1fr 40px;
  }

  .review-answer-grid {
    grid-template-columns: 1fr;
  }

  .reason-options,
  .recommendation-list {
    grid-template-columns: 1fr;
  }

  .review-diagnosis > header,
  .review-recommendations > header,
  .diagnosis-actions {
    align-items: flex-start;
    flex-direction: column;
  }

  .diagnosis-actions :deep(.el-button) {
    width: 100%;
  }

  .recommendation-list article {
    padding-right: 14px;
    padding-bottom: 58px;
  }
}

@media (min-width: 761px) and (max-width: 1080px) {
  .tools-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (prefers-reduced-motion: reduce) {
  .tools-heading__actions a,
  .tools-action {
    transition: none;
  }

  .tools-state__icon.is-loading .el-icon {
    animation: none;
  }
}
</style>
