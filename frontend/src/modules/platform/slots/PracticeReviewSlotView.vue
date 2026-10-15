<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ArrowRight, Clock, Collection, DataAnalysis, RefreshRight } from '@element-plus/icons-vue'
import { toSafeUserMessage } from '@/api/client'
import { getPracticeGateway } from '@/modules/practice/api/gateway'
import type { PracticeLoadStatus, PracticeMistakeRecord } from '@/modules/practice/types'
import { useQualificationStore } from '@/stores/qualification'

defineOptions({ name: 'PracticeReviewSlotView' })

const qualificationStore = useQualificationStore()
const records = ref<PracticeMistakeRecord[]>([])
const status = ref<PracticeLoadStatus>('idle')
const errorMessage = ref('')
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const selectedStatus = ref('ACTIVE')
const scopeReady = computed(() => qualificationStore.subjectStatus === 'success'
  && Boolean(qualificationStore.selectedQualificationId)
  && Boolean(qualificationStore.selectedSubjectId))
const scopeLabel = computed(() => [
  qualificationStore.selectedQualification?.name,
  qualificationStore.selectedSubject?.name,
].filter(Boolean).join(' · ') || '当前备考方向')
const visibleMistakeCount = computed(() => records.value.reduce((sum, record) => sum + record.mistakeCount, 0))
const latestWrongAt = computed(() => records.value
  .map((record) => record.lastWrongAt)
  .filter(Boolean)
  .sort((left, right) => new Date(right).getTime() - new Date(left).getTime())[0])

function typeLabel(value: string): string {
  return ({ SINGLE_CHOICE: '单选题', MULTI_CHOICE: '多选题', JUDGE: '判断题', CASE: '案例题', CALCULATION: '计算题' } as Record<string, string>)[value] ?? value
}

function statusLabel(value: string): string {
  return value === 'MASTERED' ? '已掌握' : value === 'ACTIVE' ? '待巩固' : value
}

function formatDate(value: string): string {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—'
}

async function loadMistakes(): Promise<void> {
  if (!scopeReady.value) { records.value = []; total.value = 0; status.value = 'idle'; return }
  status.value = 'loading'; errorMessage.value = ''
  try {
    const result = await getPracticeGateway().listMistakes({
      page: page.value,
      pageSize: pageSize.value,
      qualificationId: qualificationStore.selectedQualificationId ?? undefined,
      subjectId: qualificationStore.selectedSubjectId ?? undefined,
      status: selectedStatus.value || undefined,
    })
    records.value = result.items; total.value = result.total
    status.value = records.value.length ? 'success' : 'empty'
  } catch (error) {
    records.value = []; total.value = 0; status.value = 'error'
    errorMessage.value = toSafeUserMessage(error)
  }
}

async function changePage(next: number): Promise<void> { page.value = next; await loadMistakes() }
async function changePageSize(next: number): Promise<void> { pageSize.value = next; page.value = 1; await loadMistakes() }

watch(
  () => [qualificationStore.selectedQualificationId, qualificationStore.selectedSubjectId, qualificationStore.subjectStatus, selectedStatus.value],
  () => { page.value = 1; void loadMistakes() },
  { flush: 'post' },
)
onMounted(() => void loadMistakes())
</script>

<template>
  <main class="review-page" data-testid="practice-review-slot" data-route-identity="practice.review">
    <header class="review-heading">
      <div>
        <span class="review-heading__eyebrow">学习复盘</span>
        <h1>错题记录</h1>
        <p>{{ scopeLabel }} · 系统根据真实作答结果自动整理</p>
      </div>
      <nav class="review-heading__actions" aria-label="错题记录快捷入口">
        <RouterLink to="/tools"><el-icon><DataAnalysis /></el-icon>学习工具</RouterLink>
        <RouterLink class="is-primary" to="/practice?mode=DAILY">继续练习<el-icon><ArrowRight /></el-icon></RouterLink>
      </nav>
    </header>

    <section class="review-summary" aria-label="错题概览">
      <article>
        <span><el-icon><Collection /></el-icon></span>
        <div><small>{{ selectedStatus === 'MASTERED' ? '已掌握错题' : selectedStatus === 'ACTIVE' ? '待巩固错题' : '全部错题' }}</small><strong>{{ total }}</strong><em>当前筛选结果</em></div>
      </article>
      <article class="is-count">
        <span><el-icon><DataAnalysis /></el-icon></span>
        <div><small>本页累计错误</small><strong>{{ visibleMistakeCount }}</strong><em>用于识别反复失分题</em></div>
      </article>
      <article class="is-time">
        <span><el-icon><Clock /></el-icon></span>
        <div><small>最近答错</small><strong class="is-date">{{ latestWrongAt ? formatDate(latestWrongAt) : '暂无记录' }}</strong><em>按最近作答持续更新</em></div>
      </article>
    </section>

    <section class="review-workspace">
      <header class="review-workspace__header">
        <div><span>错题清单</span><h2>定位薄弱题目，回看作答过程</h2></div>
        <div class="review-filter">
          <label for="mistake-status">记录状态</label>
          <el-select id="mistake-status" v-model="selectedStatus" aria-label="错题状态">
            <el-option label="待巩固" value="ACTIVE" />
            <el-option label="已掌握" value="MASTERED" />
            <el-option label="全部" value="" />
          </el-select>
          <button type="button" title="刷新错题记录" aria-label="刷新错题记录" :disabled="status === 'loading'" @click="loadMistakes"><el-icon><RefreshRight /></el-icon></button>
        </div>
      </header>

      <div v-if="!scopeReady" class="review-state"><span><el-icon><Collection /></el-icon></span><h2>请先选择资格与科目</h2><p>错题按当前资格和科目隔离展示。</p></div>
      <div v-else-if="status === 'loading'" class="review-loading" data-testid="practice-mistakes-loading" aria-label="正在加载错题">
        <i v-for="index in 6" :key="index"></i>
      </div>
      <div v-else-if="status === 'error'" class="review-state is-error" data-testid="practice-mistakes-error"><span><el-icon><Collection /></el-icon></span><h2>错题暂不可用</h2><p>{{ errorMessage }}</p><button type="button" @click="loadMistakes"><el-icon><RefreshRight /></el-icon>重新加载</button></div>
      <div v-else-if="status === 'empty' || status === 'idle'" class="review-state" data-testid="practice-mistakes-empty"><span><el-icon><Collection /></el-icon></span><h2>{{ selectedStatus === 'MASTERED' ? '暂无已掌握错题' : '暂无错题记录' }}</h2><p>完成逐题结算或提交整卷后，答错的客观题会自动出现在这里。</p><RouterLink to="/practice?mode=DAILY">开始一次练习<el-icon><ArrowRight /></el-icon></RouterLink></div>
      <template v-else>
        <div class="review-list" role="table" aria-label="错题列表">
          <div class="review-list__head" role="row"><span>题目内容</span><span>错误情况</span><span>最近答错</span><span>状态</span><span>操作</span></div>
          <article v-for="record in records" :key="record.questionId" class="review-record" role="row" data-testid="practice-mistake-row">
            <div class="review-record__question"><span>{{ typeLabel(record.questionType) }}</span><strong>{{ record.questionTitle }}</strong></div>
            <div class="review-record__count"><small>累计答错</small><strong>{{ record.mistakeCount }} 次</strong></div>
            <time :datetime="record.lastWrongAt">{{ formatDate(record.lastWrongAt) }}</time>
            <span class="review-record__status" :class="{ 'is-mastered': record.status === 'MASTERED' }"><i></i>{{ statusLabel(record.status) }}</span>
            <RouterLink :to="`/app/practice/attempts/${record.lastAttemptId}`">查看作答<el-icon><ArrowRight /></el-icon></RouterLink>
          </article>
        </div>
        <footer class="review-pagination"><span>共 {{ total }} 条记录</span><el-pagination :current-page="page" :page-size="pageSize" :page-sizes="[10, 20, 50]" :total="total" background layout="prev, pager, next, sizes" @current-change="changePage" @size-change="changePageSize" /></footer>
      </template>
    </section>
  </main>
</template>

<style scoped>
.review-page { min-height: calc(100vh - 56px); padding: 26px 24px 52px; color: #172033; background: #f5f7fa; }
.review-heading,.review-summary,.review-workspace { width: min(1180px,100%); margin-right: auto; margin-left: auto; }
.review-heading { display: flex; min-height: 82px; align-items: center; justify-content: space-between; gap: 24px; }
.review-heading__eyebrow { color: #2563eb; font-size: 12px; font-weight: 700; letter-spacing: .06em; }
.review-heading h1 { margin: 5px 0 4px; color: #172033; font-size: 28px; line-height: 1.25; letter-spacing: -.025em; }
.review-heading p { margin: 0; color: #6b788b; font-size: 13px; }
.review-heading__actions { display: flex; flex: 0 0 auto; gap: 8px; }
.review-heading__actions a { display: inline-flex; min-height: 38px; padding: 0 14px; align-items: center; justify-content: center; gap: 6px; border: 1px solid #d5deea; border-radius: 9px; color: #475569; background: #fff; text-decoration: none; font-size: 13px; font-weight: 600; transition: .16s ease; }
.review-heading__actions a:hover { border-color: #a9c1ef; color: #2563eb; background: #f8fbff; }
.review-heading__actions a.is-primary { border-color: #2563eb; color: #fff; background: #2563eb; box-shadow: 0 7px 16px rgba(37,99,235,.16); }
.review-summary { display: grid; margin-top: 14px; grid-template-columns: repeat(3,minmax(0,1fr)); gap: 12px; }
.review-summary article { display: flex; min-height: 104px; padding: 18px 20px; align-items: center; gap: 15px; border: 1px solid #dde5ef; border-radius: 14px; background: #fff; box-shadow: 0 5px 18px rgba(15,23,42,.035); }
.review-summary article > span { display: grid; width: 44px; height: 44px; flex: 0 0 44px; place-items: center; border-radius: 12px; color: #dc2626; background: #fef2f2; font-size: 20px; }
.review-summary article.is-count > span { color: #7c3aed; background: #f3efff; }.review-summary article.is-time > span { color: #0f766e; background: #eaf8f5; }
.review-summary article > div { display: grid; min-width: 0; gap: 3px; }.review-summary small { color: #64748b; font-size: 11px; }.review-summary strong { color: #1e293b; font-size: 27px; line-height: 1.15; }.review-summary strong.is-date { font-size: 14px; }.review-summary em { overflow: hidden; color: #94a3b8; font-size: 10px; font-style: normal; text-overflow: ellipsis; white-space: nowrap; }
.review-workspace { overflow: hidden; min-height: 440px; margin-top: 14px; border: 1px solid #dce4ee; border-radius: 16px; background: #fff; box-shadow: 0 10px 30px rgba(15,23,42,.045); }
.review-workspace__header { display: flex; min-height: 74px; padding: 12px 18px; align-items: center; justify-content: space-between; gap: 18px; border-bottom: 1px solid #e7edf4; }
.review-workspace__header > div:first-child { display: grid; gap: 4px; }.review-workspace__header > div:first-child span { color: #2563eb; font-size: 10px; font-weight: 700; letter-spacing: .08em; }.review-workspace__header h2 { margin: 0; color: #263247; font-size: 15px; }
.review-filter { display: flex; align-items: center; gap: 9px; }.review-filter label { color: #7b8798; font-size: 11px; }.review-filter :deep(.el-select) { width: 132px; }.review-filter :deep(.el-select__wrapper) { min-height: 36px; border-radius: 9px; }.review-filter > button { display: grid; width: 36px; height: 36px; padding: 0; place-items: center; border: 0; border-radius: 9px; color: #64748b; background: #f1f5f9; cursor: pointer; }.review-filter > button:hover:not(:disabled) { color: #2563eb; }.review-filter > button:disabled { cursor: wait; opacity: .5; }
.review-list { padding: 0 18px; }.review-list__head,.review-record { display: grid; grid-template-columns: minmax(260px,1fr) 100px 150px 90px 112px; align-items: center; gap: 18px; }.review-list__head { min-height: 48px; padding: 0 4px; border-bottom: 1px solid #e8edf3; color: #8b97a8; font-size: 10px; }.review-record { min-height: 92px; padding: 14px 4px; border-bottom: 1px solid #edf1f5; }.review-record:last-child { border-bottom: 0; }
.review-record__question { display: grid; min-width: 0; gap: 7px; }.review-record__question > span { width: max-content; padding: 3px 7px; border-radius: 999px; color: #1d4ed8; background: #edf4ff; font-size: 9px; }.review-record__question strong { overflow: hidden; color: #334155; font-size: 13px; font-weight: 600; line-height: 1.55; text-overflow: ellipsis; white-space: nowrap; }.review-record__count { display: grid; gap: 3px; }.review-record__count small { color: #94a3b8; font-size: 9px; }.review-record__count strong { color: #b45309; font-size: 13px; }.review-record time { color: #64748b; font-size: 11px; }.review-record__status { display: inline-flex; width: max-content; align-items: center; gap: 6px; color: #15803d; font-size: 11px; }.review-record__status i { width: 6px; height: 6px; border-radius: 50%; background: #22c55e; box-shadow: 0 0 0 3px rgba(34,197,94,.12); }.review-record__status.is-mastered { color: #64748b; }.review-record__status.is-mastered i { background: #94a3b8; box-shadow: 0 0 0 3px rgba(148,163,184,.14); }.review-record > a { display: inline-flex; min-height: 34px; padding: 0 10px; align-items: center; justify-content: center; gap: 4px; border: 1px solid #b9ccf2; border-radius: 8px; color: #2563eb; background: #f7faff; text-decoration: none; font-size: 11px; font-weight: 600; }
.review-pagination { display: flex; min-height: 68px; padding: 10px 18px; align-items: center; justify-content: space-between; gap: 16px; border-top: 1px solid #e7edf4; background: #fbfcfe; }.review-pagination > span { color: #7b8798; font-size: 11px; }
.review-state { display: grid; min-height: 330px; padding: 36px 24px; place-items: center; align-content: center; gap: 7px; color: #8290a3; text-align: center; }.review-state > span { display: grid; width: 50px; height: 50px; margin-bottom: 7px; place-items: center; border-radius: 14px; color: #64748b; background: #f1f5f9; font-size: 22px; }.review-state h2 { margin: 0; color: #334155; font-size: 15px; }.review-state p { margin: 0 0 8px; font-size: 12px; }.review-state > a,.review-state > button { display: inline-flex; min-height: 34px; padding: 0 12px; align-items: center; gap: 5px; border: 1px solid #c8d8f6; border-radius: 8px; color: #2563eb; background: #f7faff; cursor: pointer; text-decoration: none; font-size: 12px; }.review-state.is-error > span { color: #dc2626; background: #fef2f2; }.review-state.is-error h2 { color: #991b1b; }
.review-loading { display: grid; padding: 18px; gap: 10px; }.review-loading i { height: 74px; border-radius: 11px; background: linear-gradient(90deg,#f3f6f9 25%,#e9eef4 37%,#f3f6f9 63%); background-size: 400% 100%; animation: review-shimmer 1.35s ease infinite; }
@keyframes review-shimmer { 0% { background-position: 100% 0; } 100% { background-position: 0 0; } }
@media (max-width: 760px) { .review-page { padding: 16px 12px 34px; }.review-heading { min-height: 0; align-items: flex-start; flex-direction: column; gap: 14px; }.review-heading__actions { width: 100%; }.review-heading__actions a { flex: 1; }.review-summary { grid-template-columns: 1fr; gap: 9px; }.review-summary article { min-height: 82px; padding: 13px 15px; }.review-workspace__header { align-items: stretch; flex-direction: column; }.review-filter { width: 100%; }.review-filter :deep(.el-select) { flex: 1; width: auto; }.review-list__head { display: none; }.review-list { padding: 0 14px; }.review-record { min-height: 0; padding: 16px 0; grid-template-columns: 1fr auto; gap: 12px; }.review-record__question { grid-column: 1 / -1; }.review-record__question strong { white-space: normal; }.review-record__count,.review-record time,.review-record__status { align-self: center; }.review-record > a { grid-column: 2; grid-row: 2 / 4; }.review-pagination { align-items: flex-start; flex-direction: column; overflow-x: auto; }.review-pagination :deep(.el-pagination) { min-width: max-content; } }
@media (prefers-reduced-motion: reduce) { .review-heading__actions a { transition: none; }.review-loading i { animation: none; } }
</style>
