<script setup lang="ts">
import { computed, onMounted, ref, watch, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowRight,
  Calendar,
  DataAnalysis,
  Reading,
  RefreshRight,
  Tickets,
} from '@element-plus/icons-vue'
import { toSafeUserMessage } from '@/api/client'
import type { FoundationKnowledgeNode, LevelCode } from '@/modules/foundation/types'
import { getPlatformFoundationGateway } from '@/modules/foundation/api/gateway'
import { getPracticeGateway } from '@/modules/practice/api/gateway'
import type {
  PracticeAttemptSource,
  PracticeCatalog,
  PracticeHistoryRecord,
  PracticeMode,
  PracticePaperSummary,
  PracticeSubjectType,
} from '../types'
import { usePracticeStore } from '@/stores/practice'
import { useQualificationStore } from '@/stores/qualification'
import { useAuthStore } from '@/stores/auth'
import { rememberPracticeSessionPresentation } from '../session-presentation'

const router = useRouter()
const route = useRoute()
const practiceStore = usePracticeStore()
const qualificationStore = useQualificationStore()
const authStore = useAuthStore()
const startError = ref('')
const startingSubjectId = ref('')
const catalog = ref<PracticeCatalog>()
const catalogStatus = ref<'idle' | 'loading' | 'success' | 'empty' | 'error'>('idle')
type KnowledgeScope = FoundationKnowledgeNode & {
  subjectId: string
  knowledgeType: 'CHAPTER' | 'SYLLABUS'
}

const knowledgeScopes = ref<KnowledgeScope[]>([])
const knowledgeStatus = ref<'idle' | 'loading' | 'success' | 'empty' | 'error'>('idle')
const knowledgeError = ref('')
let catalogEpoch = 0
let knowledgeEpoch = 0
let restoringAction = false
const levelLabels: Record<LevelCode, string> = { ADVANCED: '高级', INTERMEDIATE: '中级', PRIMARY: '初级' }
const modes: Array<{ label: string; value: PracticeMode; description: string; icon: Component }> = [
  { label: '每日练习', value: 'DAILY', description: '逐题作答，及时巩固', icon: Calendar },
  { label: '历年真题', value: 'PAST_EXAM', description: '整卷训练，熟悉考法', icon: Tickets },
  { label: '章节练习', value: 'CHAPTER', description: '按章节系统推进', icon: Reading },
  { label: '知识点专项', value: 'KNOWLEDGE_POINT', description: '聚焦薄弱考点训练', icon: DataAnalysis },
]

const selectedMode = computed(() => practiceStore.filters.mode ?? 'DAILY')
const selectedModeCopy = computed(() => modes.find((item) => item.value === selectedMode.value) ?? modes[0]!)
const confirmedQualificationId = computed(() => qualificationStore.selectedQualificationId)
const currentLevelLabel = computed(() => {
  const levelCode = qualificationStore.selectedQualification?.levelCode
  return levelCode ? levelLabels[levelCode] : '软考'
})
const catalogSubjects = computed(() => qualificationStore.subjects.map((subject) => {
  const summary = catalog.value?.subjects.find((item) => item.subjectId === subject.id)
  return {
    subject,
    available: summary?.available === true,
    unavailableReason: summary?.unavailableReason,
    questionCount: summary?.questionCount ?? 0,
    questionTypes: summary?.questionTypes ?? [],
  }
}))

function scopeSubjectName(subjectId: string): string {
  return qualificationStore.subjects.find((subject) => subject.id === subjectId)?.name ?? '当前科目'
}
function paperTypeLabel(value: string): string {
  return value === 'REAL' ? '历年真题' : value === 'MOCK' ? '模拟试卷' : '练习试卷'
}

function subjectTypeLabel(type: PracticeSubjectType): string {
  return ({
    COMPREHENSIVE: '综合知识',
    CASE: '案例分析',
    ESSAY: '论文',
    APPLICATION: '应用技术',
  })[type]
}

function sameSource(record: PracticeHistoryRecord, source: PracticeAttemptSource): boolean {
  if (record.sourceType !== source.type) return false
  if (source.type === 'DAILY') return true
  if (source.type === 'PAPER') return String(record.sourceSpec.paperId ?? '') === source.paperId
  if (source.type === 'YEAR') return Number(record.sourceSpec.yearNo) === source.yearNo
  const knowledgeIds = Array.isArray(record.sourceSpec.knowledgeIds)
    ? record.sourceSpec.knowledgeIds.map(String).sort()
    : []
  return record.sourceSpec.knowledgeType === source.knowledgeType
    && knowledgeIds.join(',') === source.knowledgeIds.map(String).sort().join(',')
}

function recoveryQuery(
  mode: PracticeMode,
  subjectId: string | undefined,
  source: PracticeAttemptSource,
): Record<string, string> {
  const query: Record<string, string> = {
    mode,
    qualificationId: confirmedQualificationId.value ?? '',
    resumeAction: '1',
    sourceType: source.type,
  }
  if (subjectId) query.subjectId = subjectId
  if (source.type === 'PAPER') query.paperId = source.paperId
  if (source.type === 'YEAR') query.yearNo = String(source.yearNo)
  if (source.type === 'KNOWLEDGE') {
    query.knowledgeType = source.knowledgeType
    query.knowledgeId = source.knowledgeIds[0] ?? ''
  }
  return query
}

function practiceReturnPath(mode: PracticeMode, subjectId?: string): string {
  const query = new URLSearchParams({ mode })
  if (subjectId) query.set('subjectId', subjectId)
  return `/practice?${query.toString()}`
}

function sourceFromRecoveryQuery(): PracticeAttemptSource | null {
  const sourceType = String(route.query.sourceType ?? '')
  if (sourceType === 'DAILY') return { type: 'DAILY' }
  if (sourceType === 'PAPER' && route.query.paperId) {
    return { type: 'PAPER', paperId: String(route.query.paperId) }
  }
  if (sourceType === 'YEAR' && Number.isInteger(Number(route.query.yearNo))) {
    return { type: 'YEAR', yearNo: Number(route.query.yearNo) }
  }
  if (
    sourceType === 'KNOWLEDGE'
    && (route.query.knowledgeType === 'CHAPTER' || route.query.knowledgeType === 'SYLLABUS' || route.query.knowledgeType === 'TAG')
    && route.query.knowledgeId
  ) {
    return {
      type: 'KNOWLEDGE',
      knowledgeType: route.query.knowledgeType,
      knowledgeIds: [String(route.query.knowledgeId)],
    }
  }
  return null
}

async function redirectToLoginForAction(
  mode: PracticeMode,
  subjectId: string | undefined,
  source: PracticeAttemptSource,
): Promise<void> {
  const target = router.resolve({
    path: '/app/practice/start',
    query: recoveryQuery(mode, subjectId, source),
  }).fullPath
  await router.push({ path: '/login', query: { redirect: target } })
}

function selectSubject(subjectId: string): void {
  qualificationStore.selectSubject(subjectId)
}

async function loadCatalog(): Promise<void> {
  const requestEpoch = ++catalogEpoch
  const qualificationId = confirmedQualificationId.value
  if (!qualificationId) {
    catalog.value = undefined
    catalogStatus.value = 'idle'
    return
  }
  catalogStatus.value = 'loading'
  try {
    const result = await getPracticeGateway().getCatalog(qualificationId)
    if (requestEpoch !== catalogEpoch || qualificationId !== confirmedQualificationId.value) return
    catalog.value = result
    catalogStatus.value = result.available ? 'success' : 'empty'
  } catch (error) {
    if (requestEpoch !== catalogEpoch || qualificationId !== confirmedQualificationId.value) return
    catalog.value = undefined
    catalogStatus.value = 'error'
    startError.value = toSafeUserMessage(error)
  }
}

async function loadKnowledgeNodes(): Promise<void> {
  const requestEpoch = ++knowledgeEpoch
  const mode = selectedMode.value
  if (!['CHAPTER', 'KNOWLEDGE_POINT'].includes(mode)) {
    knowledgeScopes.value = []
    knowledgeStatus.value = 'idle'
    return
  }
  knowledgeStatus.value = 'loading'
  knowledgeError.value = ''
  try {
    const foundationGateway = getPlatformFoundationGateway()
    const scopes = await Promise.all(catalogSubjects.value
      .filter((item) => item.available)
      .map(async ({ subject }) => {
        let nodes = mode === 'CHAPTER'
          ? await foundationGateway.listChapterNodes(subject.id)
          : await foundationGateway.listSyllabusNodes(subject.id)
        let knowledgeType: KnowledgeScope['knowledgeType'] = mode === 'CHAPTER' ? 'CHAPTER' : 'SYLLABUS'
        if (mode === 'CHAPTER' && nodes.length === 0) {
          nodes = await foundationGateway.listSyllabusNodes(subject.id)
          knowledgeType = 'SYLLABUS'
        }
        return nodes.map((node) => ({ ...node, subjectId: subject.id, knowledgeType }))
      }))
    if (requestEpoch !== knowledgeEpoch || mode !== selectedMode.value) return
    knowledgeScopes.value = scopes.flat()
    knowledgeStatus.value = knowledgeScopes.value.length ? 'success' : 'empty'
  } catch (error) {
    if (requestEpoch !== knowledgeEpoch || mode !== selectedMode.value) return
    knowledgeScopes.value = []
    knowledgeStatus.value = 'error'
    knowledgeError.value = toSafeUserMessage(error)
  }
}

async function hydrateScopeFromRoute(): Promise<void> {
  if (qualificationStore.status === 'idle') {
    await qualificationStore.load()
  }
}

async function loadModeData(): Promise<void> {
  const qualificationId = confirmedQualificationId.value
  if (!qualificationId) return
  if (selectedMode.value === 'PAST_EXAM') await practiceStore.loadPapers(qualificationId)
  else practiceStore.clearPaperList()
  await loadKnowledgeNodes()
}

async function selectMode(mode: PracticeMode): Promise<void> {
  if (selectedMode.value === mode) return
  practiceStore.filters.mode = mode
  startError.value = ''
  await loadModeData()
}

async function resumeOrStart(
  subjectId: string | undefined,
  source: PracticeAttemptSource,
  mode: PracticeMode = selectedMode.value,
  scopeName?: string,
): Promise<void> {
  const qualificationId = confirmedQualificationId.value
  if (!qualificationId) return
  if (!authStore.isAuthenticated) {
    await redirectToLoginForAction(mode, subjectId, source)
    return
  }
  startError.value = ''
  startingSubjectId.value = source.type === 'PAPER' ? `paper:${source.paperId}` : subjectId ?? ''
  try {
    const history = await getPracticeGateway().listAttemptHistory({
      qualificationId,
      ...(subjectId ? { subjectId } : {}),
      status: 'IN_PROGRESS',
      page: 1,
      pageSize: 20,
    })
    const resumable = history.items.find((item) => sameSource(item, source))
    if (resumable) {
      rememberPracticeSessionPresentation(resumable.id, {
        mode,
        scopeName,
        returnPath: practiceReturnPath(mode, subjectId),
      })
      await router.push(`/app/practice/attempts/${resumable.id}`)
      return
    }
    const available = source.type === 'PAPER'
      ? (catalog.value?.papers?.available ?? true)
      : catalog.value?.subjects.find((item) => item.subjectId === subjectId)?.available === true
    if (!available) throw new Error('当前范围暂未开放')
    const attempt = await practiceStore.startPaper({
      qualificationId,
      ...(subjectId ? { subjectId } : {}),
      mode,
      feedbackMode: mode === 'PAST_EXAM' ? 'WHOLE_PAPER' : 'PER_QUESTION',
      count: 100,
      source,
    })
    rememberPracticeSessionPresentation(attempt.id, {
      mode,
      scopeName,
      returnPath: practiceReturnPath(mode, subjectId),
    })
    await router.push(`/app/practice/attempts/${attempt.id}`)
  } catch (error) {
    startError.value = toSafeUserMessage(error)
  } finally {
    startingSubjectId.value = ''
  }
}

async function restoreRequestedAction(): Promise<void> {
  if (route.query.resumeAction !== '1' || restoringAction) return
  if (!authStore.isAuthenticated) {
    await router.replace({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  const source = sourceFromRecoveryQuery()
  if (!source) return
  const subjectId = route.query.subjectId ? String(route.query.subjectId) : undefined
  const mode = modes.some((item) => item.value === route.query.mode)
    ? route.query.mode as PracticeMode
    : selectedMode.value
  restoringAction = true
  const cleanQuery = { ...route.query }
  delete cleanQuery.resumeAction
  delete cleanQuery.sourceType
  delete cleanQuery.paperId
  delete cleanQuery.yearNo
  delete cleanQuery.knowledgeType
  delete cleanQuery.knowledgeId
  await router.replace({ path: '/app/practice/start', query: cleanQuery })
  try {
    await resumeOrStart(subjectId, source, mode)
  } finally {
    restoringAction = false
  }
}

async function startDaily(subjectId: string): Promise<void> {
  selectSubject(subjectId)
  await resumeOrStart(subjectId, { type: 'DAILY' }, 'DAILY')
}

async function startKnowledgePractice(scope: KnowledgeScope): Promise<void> {
  await resumeOrStart(scope.subjectId, {
    type: 'KNOWLEDGE',
    knowledgeType: scope.knowledgeType,
    knowledgeIds: [scope.id],
  }, selectedMode.value, scope.name)
}

async function startPaper(paper: PracticePaperSummary): Promise<void> {
  if (paper.subjectId) selectSubject(paper.subjectId)
  await resumeOrStart(paper.subjectId, { type: 'PAPER', paperId: paper.id }, 'PAST_EXAM')
}

const routeMode = String(route.query.mode ?? 'DAILY')
if (modes.some((item) => item.value === routeMode)) practiceStore.filters.mode = routeMode as PracticeMode
onMounted(() => { void hydrateScopeFromRoute() })

watch(
  () => [qualificationStore.selectedQualificationId, qualificationStore.subjectStatus] as const,
  async ([qualificationId, subjectStatus]) => {
    practiceStore.resetPaperScope()
    if (!qualificationId || subjectStatus !== 'success') return
    const requestedSubjectId = String(route.query.subjectId ?? '')
    if (requestedSubjectId && qualificationStore.subjects.some((subject) => subject.id === requestedSubjectId)) {
      qualificationStore.selectSubject(requestedSubjectId)
    }
    await loadCatalog()
    await loadModeData()
    await restoreRequestedAction()
  },
  { immediate: true, flush: 'sync' },
)
</script>

<template>
  <div class="practice-list-page" data-testid="practice-list-page">
    <header class="practice-current-scope" aria-label="当前考试方向">
      <div class="practice-current-scope__main">
        <span class="practice-current-scope__icon"><el-icon><DataAnalysis /></el-icon></span>
        <span class="practice-current-scope__copy">
          <small>当前考试方向</small>
          <span>
            <strong>{{ qualificationStore.selectedQualification?.name ?? '正在加载考试方向' }}</strong>
            <em>{{ currentLevelLabel }}</em>
          </span>
        </span>
      </div>
      <span class="practice-current-scope__mode">{{ selectedModeCopy.label }}</span>
    </header>

    <nav class="practice-mode-navigation" aria-label="刷题方式">
      <button
        v-for="item in modes"
        :key="item.value"
        type="button"
        :class="{ 'is-active': selectedMode === item.value }"
        @click="selectMode(item.value)"
      >
        <span class="practice-mode-navigation__icon"><el-icon><component :is="item.icon" /></el-icon></span>
        <span class="practice-mode-navigation__copy"><strong>{{ item.label }}</strong><small>{{ item.description }}</small></span>
        <el-icon class="practice-mode-navigation__arrow"><ArrowRight /></el-icon>
      </button>
    </nav>

    <div v-if="startError" class="practice-list-alert" role="alert">
      <span><strong>暂时无法进入刷题</strong><small>{{ startError }}</small></span>
      <button type="button" @click="startError = ''">关闭</button>
    </div>

    <section v-if="selectedMode === 'DAILY'" key="DAILY" class="practice-mode-content practice-table-panel practice-subject-browser" aria-labelledby="subject-practice-title">
      <div class="practice-section-heading">
        <div><span class="practice-section-heading__icon"><el-icon><Calendar /></el-icon></span><div><h2 id="subject-practice-title">选择刷题科目</h2><small>系统会优先恢复该科目未完成的练习</small></div></div>
        <span class="practice-section-heading__meta">{{ currentLevelLabel }}题库</span>
      </div>
      <div v-if="catalogStatus === 'loading' || catalogStatus === 'idle'" class="practice-table-loading"><el-skeleton :rows="4" /></div>
      <div v-else-if="catalogStatus === 'error'" class="practice-state practice-state--error">
        <span class="practice-state__icon"><el-icon><RefreshRight /></el-icon></span>
        <strong>题库加载失败</strong>
        <p>{{ startError || '暂时无法获取题库内容，请稍后重试。' }}</p>
        <el-button :icon="RefreshRight" @click="loadCatalog">重新加载</el-button>
      </div>
      <div v-else-if="catalogStatus === 'empty'" class="practice-state">
        <span class="practice-state__icon"><el-icon><Reading /></el-icon></span>
        <strong>当前资格暂无已发布题目</strong>
        <p>题目发布后会自动出现在这里。</p>
      </div>
      <div v-else class="practice-subject-list">
        <div v-for="item in catalogSubjects" :key="item.subject.id" class="practice-subject-row">
          <span class="practice-subject-row__icon"><el-icon><Reading /></el-icon></span>
          <div class="practice-subject-row__title">
            <strong>{{ item.subject.name }}</strong>
            <span>{{ subjectTypeLabel(item.subject.type) }}</span>
          </div>
          <p data-testid="subject-practice-availability" :class="{ 'is-available': item.available }">
            <i></i>{{ item.available ? '专项练习已开放' : '暂未开放' }}
          </p>
          <el-button type="primary" :loading="startingSubjectId === item.subject.id" :disabled="!item.available" @click="startDaily(item.subject.id)">
            {{ item.available ? '开始刷题' : '暂未开放' }}<el-icon><ArrowRight /></el-icon>
          </el-button>
        </div>
      </div>
    </section>

    <section v-else-if="selectedMode === 'PAST_EXAM'" key="PAST_EXAM" class="practice-mode-content practice-table-panel" aria-labelledby="paper-list-title">
      <div class="practice-section-heading">
        <div><span class="practice-section-heading__icon practice-section-heading__icon--orange"><el-icon><Tickets /></el-icon></span><div><h2 id="paper-list-title">历年真题卷</h2><small>完整试卷作答，交卷后统一查看答案解析</small></div></div>
        <span class="practice-section-heading__meta">{{ practiceStore.papers.length }} 份试卷</span>
      </div>
      <div v-if="practiceStore.listStatus === 'loading'" class="practice-table-loading"><el-skeleton :rows="6" /></div>
      <div v-else-if="practiceStore.listStatus === 'error'" class="practice-state practice-state--error">
        <span class="practice-state__icon"><el-icon><RefreshRight /></el-icon></span>
        <strong>真题卷加载失败</strong><p>{{ practiceStore.errorMessage }}</p><el-button :icon="RefreshRight" @click="loadModeData">重试</el-button>
      </div>
      <div v-else-if="practiceStore.listStatus === 'empty'" class="practice-history-empty" data-testid="past-paper-unavailable">
        <span class="practice-state__icon"><el-icon><Tickets /></el-icon></span>
        <strong>完整年份试卷整理中</strong>
        <p>可先进行专项练习。</p>
      </div>
      <div v-else class="practice-table" role="table">
        <div class="practice-table__row practice-table__head" role="row"><span>试卷名称</span><span>类型</span><span>年份</span><span>总分</span><span>时长</span><span>状态</span><span>操作</span></div>
        <div v-for="paper in practiceStore.papers" :key="paper.id" class="practice-table__row" role="row">
          <strong>{{ paper.title }}</strong><span data-label="类型">{{ paperTypeLabel(paper.paperType) }}</span><span data-label="年份">{{ paper.yearNo ?? '—' }}</span><span data-label="总分">{{ paper.totalScore ?? '—' }}</span><span data-label="时长">{{ paper.durationMinutes == null ? '—' : `${paper.durationMinutes} 分钟` }}</span><span class="practice-paper-status" data-label="状态"><i></i>已发布</span>
          <el-button type="primary" size="small" :loading="startingSubjectId === `paper:${paper.id}`" @click="startPaper(paper)">进入试卷</el-button>
        </div>
      </div>
    </section>

    <section v-else :key="selectedMode" class="practice-mode-content practice-filter-panel practice-scope-browser" aria-labelledby="scope-practice-title">
      <div class="practice-section-heading">
        <div>
          <span class="practice-section-heading__icon"><el-icon><component :is="selectedMode === 'CHAPTER' ? Reading : DataAnalysis" /></el-icon></span>
          <div><h2 id="scope-practice-title">{{ selectedMode === 'CHAPTER' ? '章节练习' : '知识点专项' }}</h2><small>选择训练范围后直接开始练习</small></div>
        </div>
        <span class="practice-section-heading__meta">{{ knowledgeScopes.length }} 个范围</span>
      </div>
      <div v-if="knowledgeStatus === 'loading'" class="practice-table-loading"><el-skeleton :rows="8" /></div>
      <div v-else-if="knowledgeStatus === 'success'" class="practice-scope-list">
        <div v-for="scope in knowledgeScopes" :key="scope.id" class="practice-scope-item">
          <span class="practice-scope-item__icon"><el-icon><component :is="selectedMode === 'CHAPTER' ? Reading : DataAnalysis" /></el-icon></span>
          <span class="practice-scope-item__copy"><strong>{{ scope.name }}</strong><small>{{ scopeSubjectName(scope.subjectId) }}</small></span>
          <el-button type="primary" plain :loading="startingSubjectId === scope.subjectId" @click="startKnowledgePractice(scope)">
            开始练习<el-icon><ArrowRight /></el-icon>
          </el-button>
        </div>
      </div>
      <div v-else-if="knowledgeStatus === 'empty'" class="practice-state">
        <span class="practice-state__icon"><el-icon><Reading /></el-icon></span>
        <strong>暂无可用的{{ selectedMode === 'CHAPTER' ? '章节' : '知识点' }}目录</strong>
        <p>相关训练内容发布后会自动出现在这里。</p>
      </div>
      <div v-else-if="knowledgeStatus === 'error'" class="practice-state practice-state--error">
        <span class="practice-state__icon"><el-icon><RefreshRight /></el-icon></span>
        <strong>训练范围加载失败</strong><p>{{ knowledgeError }}</p><el-button :icon="RefreshRight" @click="loadKnowledgeNodes">重试</el-button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.practice-list-page {
  width: min(1180px, calc(100% - 40px));
  margin: 0 auto;
  padding: 22px 0 48px;
  gap: 14px;
  color: #172033;
}

.practice-current-scope {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 68px;
  padding: 11px 16px;
  border: 1px solid #dce5f2;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 5px 18px rgba(15, 23, 42, 0.035);
}

.practice-current-scope__main {
  display: flex;
  min-width: 0;
  align-items: center;
}

.practice-current-scope__icon {
  display: grid;
  flex: 0 0 40px;
  width: 40px;
  height: 40px;
  margin-right: 12px;
  place-items: center;
  border-radius: 11px;
  color: #fff;
  background: #2563eb;
  box-shadow: 0 6px 14px rgba(37, 99, 235, 0.18);
}

.practice-current-scope__icon .el-icon {
  font-size: 18px;
}

.practice-current-scope__copy {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.practice-current-scope small {
  color: #64748b;
  font-size: 11px;
}

.practice-current-scope__copy > span {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 9px;
}

.practice-current-scope strong {
  overflow: hidden;
  color: #1e293b;
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.practice-current-scope em {
  align-self: center;
  padding: 2px 7px;
  border-radius: 999px;
  color: #1d4ed8;
  background: #eaf1ff;
  font-size: 11px;
  font-style: normal;
  font-weight: 700;
}

.practice-current-scope__mode {
  flex: 0 0 auto;
  padding: 5px 10px;
  border-radius: 999px;
  color: #64748b;
  background: #f1f5f9;
  font-size: 12px;
}

.practice-mode-navigation {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  padding: 0;
  border: 0;
  background: transparent;
  box-shadow: none;
}

.practice-mode-navigation button {
  position: relative;
  display: grid;
  min-width: 0;
  min-height: 92px;
  padding: 16px;
  grid-template-columns: 42px minmax(0, 1fr) 16px;
  gap: 12px;
  align-items: center;
  border: 1px solid #dde5ef;
  border-radius: 14px;
  color: #475569;
  background: #fff;
  text-align: left;
  box-shadow: 0 5px 18px rgba(15, 23, 42, 0.035);
  cursor: pointer;
  transition: border-color 160ms ease, box-shadow 160ms ease, transform 160ms ease;
}

.practice-mode-navigation button:hover {
  z-index: 1;
  border-color: #b9cdf8;
  box-shadow: 0 10px 24px rgba(37, 99, 235, 0.08);
  transform: translateY(-2px);
}

.practice-mode-navigation button:focus-visible {
  z-index: 2;
  outline: 3px solid rgba(37, 99, 235, 0.2);
  outline-offset: 2px;
}

.practice-mode-navigation button.is-active {
  border-color: #2563eb;
  color: #172033;
  background: linear-gradient(145deg, #fff, #f5f8ff);
  box-shadow: 0 9px 26px rgba(37, 99, 235, 0.12);
}

.practice-mode-navigation button.is-active::after {
  position: absolute;
  right: 18px;
  bottom: -1px;
  left: 18px;
  height: 3px;
  border-radius: 3px 3px 0 0;
  background: #2563eb;
  content: '';
}

.practice-mode-navigation__icon,
.practice-section-heading__icon,
.practice-subject-row__icon,
.practice-scope-item__icon {
  display: grid;
  place-items: center;
  border-radius: 11px;
  color: #2563eb;
  background: #edf4ff;
}

.practice-mode-navigation__icon {
  width: 42px;
  height: 42px;
  font-size: 19px;
}

.is-active .practice-mode-navigation__icon {
  color: #fff;
  background: #2563eb;
}

.practice-mode-navigation__copy {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.practice-mode-navigation__copy strong {
  color: inherit;
  font-size: 15px;
}

.practice-mode-navigation__copy small {
  overflow: hidden;
  color: #7b8798;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.practice-mode-navigation__arrow {
  color: #9aa7b8;
}

.is-active .practice-mode-navigation__arrow {
  color: #2563eb;
}

.practice-mode-content {
  overflow: hidden;
  min-height: 330px;
  border: 1px solid #dde5ef;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 10px 30px rgba(15, 23, 42, 0.045);
}

.practice-section-heading {
  min-height: 78px;
  padding: 18px 22px;
  border-bottom: 1px solid #e8edf3;
  background: #fff;
}

.practice-section-heading > div {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: 12px;
}

.practice-section-heading__icon {
  flex: 0 0 40px;
  width: 40px;
  height: 40px;
  font-size: 18px;
}

.practice-section-heading__icon--orange {
  color: #c2410c;
  background: #fff1e8;
}

.practice-section-heading h2 {
  margin: 0 0 4px;
  color: #172033;
  font-size: 17px;
  line-height: 1.25;
}

.practice-section-heading small {
  color: #7b8798;
  font-size: 12px;
}

.practice-section-heading__meta {
  flex: 0 0 auto;
  padding: 5px 10px;
  border-radius: 999px;
  color: #64748b;
  background: #f1f5f9;
  font-size: 12px;
}

.practice-subject-list {
  padding: 8px 20px 20px;
}

.practice-subject-row {
  display: grid;
  min-height: 80px;
  padding: 14px 4px;
  grid-template-columns: 42px minmax(220px, 1fr) minmax(150px, 0.5fr) auto;
  gap: 14px;
  align-items: center;
  border-bottom: 1px solid #edf1f5;
  transition: background 160ms ease;
}

.practice-subject-row:last-child {
  border-bottom: 0;
}

.practice-subject-row:hover {
  background: #fbfcfe;
}

.practice-subject-row__icon {
  width: 42px;
  height: 42px;
  font-size: 18px;
}

.practice-subject-row__title {
  display: grid;
  gap: 4px;
}

.practice-subject-row__title strong {
  color: #1e293b;
  font-size: 15px;
}

.practice-subject-row__title span {
  color: #8491a3;
  font-size: 12px;
}

.practice-subject-row > p {
  display: inline-flex;
  align-items: center;
  margin: 0;
  color: #94a3b8;
  font-size: 12px;
}

.practice-subject-row > p i,
.practice-paper-status i {
  width: 6px;
  height: 6px;
  margin-right: 7px;
  border-radius: 50%;
  background: #cbd5e1;
}

.practice-subject-row > p.is-available,
.practice-paper-status {
  color: #15803d;
}

.practice-subject-row > p.is-available i,
.practice-paper-status i {
  background: #22c55e;
  box-shadow: 0 0 0 3px rgba(34, 197, 94, 0.12);
}

.practice-subject-row :deep(.el-button),
.practice-scope-item :deep(.el-button),
.practice-table__row :deep(.el-button) {
  min-width: 104px;
  border-radius: 9px;
  font-weight: 600;
}

.practice-list-alert {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 13px 16px;
  border: 1px solid #fecaca;
  border-radius: 12px;
  color: #991b1b;
  background: #fff7f7;
}

.practice-list-alert > span {
  display: grid;
  gap: 3px;
}

.practice-list-alert strong {
  font-size: 13px;
}

.practice-list-alert small {
  color: #b45353;
}

.practice-list-alert button {
  border: 0;
  color: #b91c1c;
  background: transparent;
  cursor: pointer;
}

.practice-table-loading {
  padding: 26px 24px;
}

.practice-state,
.practice-history-empty {
  display: flex;
  min-height: 250px;
  padding: 36px 20px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
}

.practice-state__icon {
  display: grid;
  width: 48px;
  height: 48px;
  margin-bottom: 14px;
  place-items: center;
  border-radius: 14px;
  color: #64748b;
  background: #f1f5f9;
  font-size: 21px;
}

.practice-state strong,
.practice-history-empty strong {
  color: #334155;
  font-size: 15px;
}

.practice-state p,
.practice-history-empty p {
  margin: 7px 0 16px;
  color: #8290a3;
  font-size: 13px;
}

.practice-state--error .practice-state__icon {
  color: #dc2626;
  background: #fef2f2;
}

.practice-table {
  padding: 0 20px 20px;
}

.practice-table__row {
  display: grid;
  min-height: 64px;
  padding: 12px 6px;
  grid-template-columns: minmax(240px, 2fr) minmax(90px, 0.8fr) 70px 64px 90px 76px 104px;
  gap: 12px;
  align-items: center;
  border-bottom: 1px solid #edf1f5;
  color: #64748b;
  font-size: 13px;
}

.practice-table__row:not(.practice-table__head):hover {
  background: #fbfcfe;
}

.practice-table__row > strong {
  color: #253044;
  font-size: 14px;
}

.practice-table__head {
  min-height: 48px;
  color: #8290a3;
  font-size: 12px;
  font-weight: 600;
}

.practice-paper-status {
  display: inline-flex;
  align-items: center;
}

.practice-scope-list {
  display: grid;
  padding: 20px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.practice-scope-item {
  display: grid;
  min-height: 78px;
  padding: 14px;
  grid-template-columns: 40px minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  border: 1px solid #e3e9f1;
  border-radius: 12px;
  background: #fff;
  transition: border-color 160ms ease, box-shadow 160ms ease, transform 160ms ease;
}

.practice-scope-item:hover {
  border-color: #bfd0f5;
  box-shadow: 0 8px 20px rgba(37, 99, 235, 0.07);
  transform: translateY(-1px);
}

.practice-scope-item__icon {
  width: 40px;
  height: 40px;
  font-size: 17px;
}

.practice-scope-item__copy {
  display: grid;
  min-width: 0;
  gap: 4px;
}

.practice-scope-item__copy strong {
  overflow: hidden;
  color: #2a3548;
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.practice-scope-item__copy small {
  overflow: hidden;
  color: #8a96a8;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 980px) {
  .practice-mode-navigation {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .practice-table {
    overflow-x: auto;
  }

  .practice-table__row {
    min-width: 880px;
  }
}

@media (max-width: 760px) {
  .practice-list-page {
    width: min(100% - 24px, 1180px);
    padding: 12px 0 32px;
    gap: 10px;
  }

  .practice-current-scope {
    min-height: 62px;
    padding: 10px 12px;
  }

  .practice-current-scope__mode {
    display: none;
  }

  .practice-current-scope strong {
    max-width: min(60vw, 360px);
  }

  .practice-mode-navigation {
    grid-template-columns: 1fr;
    gap: 9px;
  }

  .practice-mode-navigation button {
    min-height: 76px;
    padding: 13px 14px;
  }

  .practice-section-heading {
    padding: 16px;
  }

  .practice-section-heading__meta {
    display: none;
  }

  .practice-subject-list {
    padding: 4px 16px 16px;
  }

  .practice-subject-row {
    padding: 16px 0;
    grid-template-columns: 40px minmax(0, 1fr);
    gap: 10px 12px;
  }

  .practice-subject-row > p {
    grid-column: 2;
  }

  .practice-subject-row :deep(.el-button) {
    width: 100%;
    grid-column: 1 / -1;
  }

  .practice-scope-list {
    padding: 14px;
    grid-template-columns: 1fr;
  }

  .practice-scope-item {
    grid-template-columns: 38px minmax(0, 1fr);
  }

  .practice-scope-item :deep(.el-button) {
    width: 100%;
    grid-column: 1 / -1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .practice-mode-navigation button,
  .practice-scope-item {
    transition: none;
  }
}
</style>
