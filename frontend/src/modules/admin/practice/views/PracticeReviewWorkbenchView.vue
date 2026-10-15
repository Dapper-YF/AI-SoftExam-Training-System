<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Check, DocumentChecked, EditPen, Refresh, Search, User } from '@element-plus/icons-vue'
import { platformApiClient, unwrapApiResponse } from '@/api/client'

type ReviewRow = {
  reviewId: number; attemptId: number; questionId: number; subQuestionId: number
  studentUserId: number; qualificationId: number; subjectId?: number
  status: string; score?: number; feedback?: string; version: number
  answerSnapshotJson?: string; questionSnapshotJson?: string
}
type FoundationOption = { id: number; name: string }
type FoundationPage = { items?: FoundationOption[]; records?: FoundationOption[] }
const rows = ref<ReviewRow[]>([])
const qualifications = ref<FoundationOption[]>([])
const subjects = ref<FoundationOption[]>([])
const loading = ref(false)
const submitting = ref(false)
const selected = ref<ReviewRow>()
const score = ref<number | undefined>()
const feedback = ref('')
const scoringPointScores = ref<Record<string, number>>({})
const status = ref('PENDING')
const qualificationId = ref<number>()
const subjectId = ref<number>()
const questionId = ref<number>()
const statusLabel = computed(() => status.value === 'COMPLETED' ? '已完成' : status.value === 'RETURNED' ? '已退回' : '待评分')
const selectedStatusLabel = computed(() => selected.value?.status === 'COMPLETED' ? '已完成' : selected.value?.status === 'RETURNED' ? '已退回' : '待评分')

async function load() {
  loading.value = true
  try { rows.value = await unwrapApiResponse<ReviewRow[]>(platformApiClient.get('/admin/practice-reviews', { params: { status: status.value, qualificationId: qualificationId.value, subjectId: subjectId.value, questionId: questionId.value } })) }
  finally { loading.value = false }
}
async function loadQualifications() {
  const page = await unwrapApiResponse<FoundationPage>(platformApiClient.get('/foundation/qualifications', { params: { pageNo: 1, pageSize: 100 } }))
  qualifications.value = page.items ?? page.records ?? []
}
async function loadSubjects(value?: number) {
  subjects.value = []
  if (!value) return
  const page = await unwrapApiResponse<FoundationPage>(platformApiClient.get(`/foundation/qualifications/${value}/subjects`, { params: { pageNo: 1, pageSize: 100 } }))
  subjects.value = page.items ?? page.records ?? []
}
const scoringPoints = computed(() => {
  if (!selected.value?.questionSnapshotJson) return []
  try {
    const root = JSON.parse(selected.value.questionSnapshotJson) as { subQuestions?: Array<{ subQuestionId?: number; scoringPoints?: Array<{ scoringPointId?: number; pointCode?: string; pointText?: string; score?: number }> }> }
    return root.subQuestions?.find((item) => String(item.subQuestionId) === String(selected.value?.subQuestionId))?.scoringPoints ?? []
  } catch { return [] }
})
const pointScoreTotal = computed(() => Object.values(scoringPointScores.value).reduce((total, value) => total + (Number(value) || 0), 0))
watch(pointScoreTotal, (value) => { if (scoringPoints.value.length) score.value = value })
watch(qualificationId, value => {
  if (!subjects.value.some(item => item.id === subjectId.value)) subjectId.value = undefined
  void loadSubjects(value)
})
function select(row: ReviewRow) {
  selected.value = row; score.value = row.score; feedback.value = row.feedback ?? ''; scoringPointScores.value = {}
  try {
    const root = JSON.parse(row.questionSnapshotJson ?? '') as { subQuestions?: Array<{ subQuestionId?: number; scoringPoints?: Array<{ scoringPointId?: number }> }> }
    const points = root.subQuestions?.find((item) => String(item.subQuestionId) === String(row.subQuestionId))?.scoringPoints ?? []
    points.forEach((point) => { if (point.scoringPointId != null) scoringPointScores.value[String(point.scoringPointId)] = 0 })
  } catch { /* historical snapshots may be manually scored without point details */ }
}
async function complete() {
  if (!selected.value || score.value == null) return
  const scoringPointsPayload = Object.fromEntries(Object.entries(scoringPointScores.value).map(([pointId, pointScore]) => [pointId, { score: Number(pointScore) || 0 }]))
  submitting.value = true
  try {
    await unwrapApiResponse(platformApiClient.post(`/admin/practice-reviews/${selected.value.reviewId}/complete`, {
      score: score.value, feedback: feedback.value, scoringPoints: scoringPointsPayload, version: selected.value.version,
    }))
    ElMessage.success('评分已提交')
    selected.value = undefined
    await load()
  } finally { submitting.value = false }
}
async function returnReview() {
  if (!selected.value) return
  submitting.value = true
  try {
    await unwrapApiResponse(platformApiClient.post(`/admin/practice-reviews/${selected.value.reviewId}/return`, { feedback: feedback.value, version: selected.value.version }))
    ElMessage.success('已退回补充')
    selected.value = undefined
    await load()
  } finally { submitting.value = false }
}
onMounted(() => { void Promise.all([load(), loadQualifications()]) })
</script>

<template>
  <section class="review-workbench">
    <header class="review-workbench__header">
      <div><p class="eyebrow">题目审核 <span>/</span> 主观题评分</p><h1>人工评分工作台</h1><small>集中核对学员作答，按评分点完成客观、可追溯的人工评分</small></div>
      <el-button :loading="loading" @click="load"><el-icon><Refresh /></el-icon>刷新队列</el-button>
    </header>

    <section class="queue-overview">
      <div class="queue-overview__copy"><span class="overview-icon"><el-icon><DocumentChecked /></el-icon></span><div><small>当前评分队列</small><strong>{{ statusLabel }}</strong><p>筛选条件下共有 {{ rows.length }} 条评分记录</p></div></div>
      <div class="queue-metric"><span>当前记录</span><strong>{{ rows.length }}</strong><small>条</small></div>
      <div class="queue-metric"><span>已选批改项</span><strong>{{ selected?.reviewId ?? '—' }}</strong></div>
    </section>

    <section class="review-filters">
      <div class="filter-heading"><span><el-icon><Search /></el-icon></span><div><strong>筛选评分队列</strong><small>可按资格、科目、题目与处理状态快速定位</small></div></div>
      <div class="filter-fields">
        <el-select v-model="qualificationId" placeholder="全部资格" clearable><el-option v-for="item in qualifications" :key="item.id" :label="item.name" :value="item.id" /></el-select>
        <el-select v-model="subjectId" placeholder="全部科目" clearable :disabled="!qualificationId"><el-option v-for="item in subjects" :key="item.id" :label="item.name" :value="item.id" /></el-select>
        <el-input v-model="questionId" placeholder="题目 ID" clearable />
        <el-select v-model="status" @change="load"><el-option label="待评分" value="PENDING" /><el-option label="已完成" value="COMPLETED" /><el-option label="已退回" value="RETURNED" /></el-select>
        <el-button type="primary" @click="load"><el-icon><Search /></el-icon>查询</el-button>
      </div>
    </section>

    <div class="review-workbench__body">
      <section class="review-list">
        <div class="panel-heading"><div><span class="panel-icon"><el-icon><EditPen /></el-icon></span><div><small>评分任务</small><h2>待处理记录</h2></div></div><span>点击表格记录进入右侧评分</span></div>
        <el-table v-loading="loading" :data="rows" row-key="reviewId" highlight-current-row empty-text="当前筛选条件下暂无评分记录" @row-click="select">
          <el-table-column prop="reviewId" label="批改项" width="90" />
          <el-table-column prop="questionId" label="题目" width="90" />
          <el-table-column prop="subQuestionId" label="子题" width="90" />
          <el-table-column prop="studentUserId" label="学员" width="100"><template #default="scope"><span class="student-cell"><el-icon><User /></el-icon>{{ scope.row.studentUserId }}</span></template></el-table-column>
          <el-table-column prop="status" label="状态"><template #default="scope"><span class="status-tag" :class="`is-${scope.row.status.toLowerCase()}`"><i></i>{{ scope.row.status === 'COMPLETED' ? '已完成' : scope.row.status === 'RETURNED' ? '已退回' : '待评分' }}</span></template></el-table-column>
        </el-table>
      </section>
      <aside v-if="selected" class="review-panel">
        <div class="panel-heading review-panel__heading"><div><span class="panel-icon panel-icon--green"><el-icon><Check /></el-icon></span><div><small>批改项 #{{ selected.reviewId }}</small><h2>评分详情</h2></div></div><span class="status-tag" :class="`is-${selected.status.toLowerCase()}`"><i></i>{{ selectedStatusLabel }}</span></div>
        <div class="question-meta"><span>题目 {{ selected.questionId }}</span><i></i><span>子题 {{ selected.subQuestionId }}</span><i></i><span>学员 {{ selected.studentUserId }}</span></div>
        <label class="field-label">学员作答</label>
        <el-input v-model="selected.answerSnapshotJson" type="textarea" :rows="7" readonly />
        <div v-if="scoringPoints.length" class="scoring-point-editor"><div class="scoring-title"><strong>评分点明细</strong><small>逐项给分后自动汇总</small></div><div v-for="point in scoringPoints" :key="point.scoringPointId" class="scoring-point-row"><span><b>{{ point.pointCode }}</b>{{ point.pointText }}</span><el-input-number v-model="scoringPointScores[String(point.scoringPointId)]" :min="0" :max="point.score ?? 0" :precision="2" controls-position="right" /></div><div class="score-total"><span>评分点合计</span><strong>{{ pointScoreTotal }}</strong></div></div>
        <div class="score-field"><label class="field-label">最终得分</label><el-input-number v-model="score" :min="0" :precision="2" controls-position="right" /></div>
        <div><label class="field-label">评分反馈</label><el-input v-model="feedback" type="textarea" :rows="4" placeholder="填写评分依据或需要学员补充的内容" /></div>
        <div class="review-actions"><el-button type="primary" :loading="submitting" :disabled="score == null" @click="complete"><el-icon><Check /></el-icon>完成评分</el-button><el-button :loading="submitting" @click="returnReview">退回补充</el-button></div>
      </aside>
      <div v-else class="review-panel review-panel--empty"><span><el-icon><EditPen /></el-icon></span><strong>选择一条评分记录</strong><p>学员作答与评分点将在这里展开，不会离开当前队列。</p></div>
    </div>
  </section>
</template>

<style scoped>
.review-workbench { display:grid; gap:18px; min-height:calc(100vh - var(--platform-header-height)); padding:28px clamp(20px,3vw,46px) 48px; color:#172033; background:#f5f7fb; }
.review-workbench__header,.queue-overview,.review-filters,.filter-fields,.filter-heading,.panel-heading,.panel-heading>div,.student-cell,.status-tag,.question-meta,.review-actions { display:flex; align-items:center; }
.review-workbench__header,.panel-heading { justify-content:space-between; }
.review-workbench__header>div { display:grid; gap:5px; }
.review-workbench__header :deep(.el-button) { min-height:38px; border-radius:9px; }
.eyebrow { margin:0; color:#748198; font-size:13px; }.eyebrow span { margin:0 5px; color:#b0b9c8; }
.review-workbench h1 { margin:0; font-size:28px; line-height:1.2; }.review-workbench__header small { color:#748198; font-size:13px; }
.queue-overview { gap:22px; padding:20px 24px; border:1px solid #dce4f0; border-radius:16px; background:#fff; box-shadow:0 10px 30px rgba(31,45,75,.05); }
.queue-overview__copy { display:flex; align-items:center; flex:1; gap:13px; }.queue-overview__copy>div { display:grid; gap:2px; }.queue-overview__copy small,.queue-metric span,.queue-metric small { color:#7a879c; font-size:12px; }.queue-overview__copy strong { font-size:19px; }.queue-overview__copy p { margin:0; color:#8b96a8; font-size:12px; }
.overview-icon,.panel-icon,.filter-heading>span { display:grid; flex:0 0 auto; place-items:center; border-radius:12px; background:#eaf1ff; color:#2563eb; }
.overview-icon { width:44px; height:44px; font-size:20px; }.queue-metric { min-width:130px; padding-left:22px; border-left:1px solid #e7ebf1; }.queue-metric strong { margin:0 4px 0 8px; color:#172033; font-size:22px; }
.review-filters { justify-content:space-between; gap:22px; padding:16px 20px; border:1px solid #e0e6ef; border-radius:14px; background:#fff; }
.filter-heading { gap:11px; min-width:max-content; }.filter-heading>span { width:36px; height:36px; border-radius:10px; }.filter-heading>div { display:grid; gap:2px; }.filter-heading strong { font-size:14px; }.filter-heading small { color:#8490a3; font-size:11px; }
.filter-fields { justify-content:flex-end; gap:8px; flex:1; }.filter-fields .el-input { width:112px; }.filter-fields .el-select { width:120px; }.filter-fields :deep(.el-input__wrapper),.filter-fields :deep(.el-select__wrapper) { min-height:38px; border-radius:9px; }.filter-fields :deep(.el-button) { min-height:38px; border-radius:9px; }
.review-workbench__body { display:grid; grid-template-columns:minmax(0,1fr) minmax(380px,440px); gap:18px; align-items:start; }
.review-list,.review-panel { overflow:hidden; border:1px solid #dfe5ef; border-radius:16px; background:#fff; box-shadow:0 8px 26px rgba(31,45,75,.04); }
.panel-heading { min-height:64px; padding:0 20px; border-bottom:1px solid #edf0f5; }.panel-heading>div { gap:10px; }.panel-heading>div>div { display:grid; gap:1px; }.panel-heading small,.panel-heading>span { color:#8190a4; font-size:11px; }.panel-heading h2 { margin:0; color:#273247; font-size:16px; }
.panel-icon { width:36px; height:36px; border-radius:10px; }.panel-icon--green { color:#168653; background:#e8f8ef; }
.review-list :deep(.el-table th.el-table__cell) { height:48px; color:#68758a; background:#f8f9fb; }.review-list :deep(.el-table td.el-table__cell) { height:54px; color:#344057; cursor:pointer; }.review-list :deep(.el-table__inner-wrapper::before) { display:none; }
.student-cell { gap:6px; }.student-cell .el-icon { color:#8290a5; }
.status-tag { display:inline-flex; width:max-content; gap:6px; padding:5px 9px; border-radius:999px; color:#b77815; background:#fff6e5; font-size:11px; font-weight:600; }.status-tag i { width:6px; height:6px; border-radius:50%; background:currentColor; }.status-tag.is-completed { color:#168653; background:#e9f8f0; }.status-tag.is-returned { color:#d65353; background:#fff0f0; }
.review-panel { display:flex; flex-direction:column; gap:15px; padding:0 20px 20px; }.review-panel__heading { margin:0 -20px; }.question-meta { gap:9px; padding:11px 13px; border-radius:9px; color:#617087; background:#f6f8fb; font-size:12px; }.question-meta i { width:3px; height:3px; border-radius:50%; background:#b8c0cd; }
.field-label { display:block; margin-bottom:7px; color:#46536a; font-size:12px; font-weight:600; }.review-panel :deep(.el-textarea__inner),.review-panel :deep(.el-input-number) { border-radius:9px; }.review-panel :deep(.el-textarea__inner) { color:#4b5870; background:#fafbfc; box-shadow:0 0 0 1px #dfe5ed inset; }
.scoring-point-editor { display:flex; flex-direction:column; gap:9px; padding:14px; border:1px solid #dfe5ee; border-radius:11px; background:#fafbfc; }.scoring-title { display:flex; justify-content:space-between; }.scoring-title strong { font-size:13px; }.scoring-title small { color:#8994a6; font-size:11px; }
.scoring-point-row { display:grid; grid-template-columns:minmax(0,1fr) 110px; gap:8px; align-items:center; font-size:12px; }.scoring-point-row span { display:flex; gap:6px; overflow-wrap:anywhere; color:#59667b; }.scoring-point-row b { color:#2563eb; }.score-total { display:flex; justify-content:space-between; padding-top:9px; border-top:1px solid #e3e8ef; color:#647187; font-size:12px; }.score-total strong { color:#172033; font-size:16px; }
.score-field { display:flex; align-items:center; justify-content:space-between; }.score-field .field-label { margin:0; }.review-actions { gap:9px; padding-top:2px; }.review-actions :deep(.el-button) { flex:1; min-height:40px; margin:0; border-radius:9px; }
.review-panel--empty { min-height:330px; align-items:center; justify-content:center; text-align:center; color:#7d899d; }.review-panel--empty>span { display:grid; width:48px; height:48px; place-items:center; border-radius:14px; color:#60708a; background:#eef2f7; font-size:20px; }.review-panel--empty strong { color:#344057; }.review-panel--empty p { max-width:270px; margin:0; font-size:12px; line-height:1.7; }
@media (max-width:1100px) { .review-filters { align-items:flex-start; flex-direction:column; }.filter-fields { justify-content:flex-start; width:100%; flex-wrap:wrap; }.review-workbench__body { grid-template-columns:1fr; }.review-panel--empty { min-height:220px; } }
@media (max-width:720px) { .review-workbench { padding:20px 14px 36px; }.review-workbench__header { align-items:flex-start; flex-direction:column; }.queue-overview { align-items:flex-start; flex-wrap:wrap; }.queue-overview__copy { flex-basis:100%; }.queue-metric { min-width:calc(50% - 11px); padding:10px 0 0; border-top:1px solid #e7ebf1; border-left:0; }.filter-fields .el-input,.filter-fields .el-select { width:calc(50% - 4px); }.filter-fields :deep(.el-button) { width:100%; }.panel-heading>span { display:none; } }
</style>
