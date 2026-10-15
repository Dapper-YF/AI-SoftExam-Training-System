<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { CirclePlus, Collection, Delete, DocumentAdd, EditPen, Filter, Refresh, Search, Timer, UploadFilled, View } from '@element-plus/icons-vue'
import { platformApiClient, unwrapApiResponse } from '@/api/client'
import { useQualificationStore } from '@/stores/qualification'
import AdminPaperImportPanel from '../components/AdminPaperImportPanel.vue'

interface Paper { paperId: number; qualificationId: number; subjectId: number | null; paperName: string; paperType: string; yearNo: number | null; totalScore: number; durationMinutes: number; status: string; questions?: PaperQuestion[] }
interface PaperQuestion { questionId: number; questionNo: number; questionType: string; title: string; score: number; subjectId: number; contentVersion?: number }
interface Page { items?: Paper[]; records?: Paper[]; total?: number }

const papers = ref<Paper[]>([])
const total = ref(0)
const loading = ref(false)
const showImport = ref(false)
const detailDialogVisible = ref(false)
const detailPaper = ref<Paper | null>(null)
const detailQuestions = ref<PaperQuestion[]>([])
const loadingDetail = ref(false)
const editingPaperId = ref<number | null>(null)
const editingName = ref('')
const editingInputRef = ref<HTMLInputElement | null>(null)
const router = useRouter()
const qualificationStore = useQualificationStore()
const query = reactive({ status: '', paperType: '', qualificationId: undefined as string | undefined, subjectId: undefined as string | undefined, keyword: '', yearNo: undefined as number | undefined, pageNo: 1, pageSize: 20 })
const publishedCount = computed(() => papers.value.filter(item => item.status === 'PUBLISHED').length)
const draftCount = computed(() => papers.value.filter(item => item.status === 'DRAFT').length)
const realCount = computed(() => papers.value.filter(item => item.paperType === 'REAL').length)
const activeFilterCount = computed(() => [query.status, query.paperType, query.qualificationId, query.subjectId, query.keyword, query.yearNo].filter(v => v !== undefined && v !== '').length)

function cleanParams(): Record<string, unknown> {
  const params: Record<string, unknown> = {}
  if (query.status) params.status = query.status
  if (query.paperType) params.paperType = query.paperType
  if (query.qualificationId) params.qualificationId = Number(query.qualificationId)
  if (query.subjectId) params.subjectId = Number(query.subjectId)
  if (query.keyword) params.keyword = query.keyword
  if (query.yearNo) params.yearNo = query.yearNo
  params.pageNo = query.pageNo
  params.pageSize = query.pageSize
  return params
}
async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await unwrapApiResponse<Page>(platformApiClient.get('/admin/papers', { params: cleanParams() }))
    papers.value = page.items ?? page.records ?? []
    total.value = page.total ?? 0
  } catch {
    ElMessage.error('试卷列表加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}
function search(): void { query.pageNo = 1; void load() }
function resetFilters(): void { query.status = ''; query.paperType = ''; query.qualificationId = undefined; query.subjectId = undefined; query.keyword = ''; query.yearNo = undefined; query.pageNo = 1; void load() }
async function onQualificationChange(id: string | undefined): Promise<void> { query.subjectId = undefined; if (id && id !== qualificationStore.selectedQualificationId) await qualificationStore.selectQualification(id); search() }
function onImported(): void { showImport.value = false; void load() }
const typeLabels: Record<string, string> = { SINGLE_CHOICE: '单选题', MULTI_CHOICE: '多选题', JUDGE: '判断题', CASE: '案例题', ESSAY: '论文题' }
async function viewDetail(paper: Paper): Promise<void> {
  loadingDetail.value = true; detailDialogVisible.value = true
  try {
    const detail = await unwrapApiResponse<Paper>(platformApiClient.get(`/admin/papers/${paper.paperId}`))
    detailPaper.value = detail; detailQuestions.value = detail.questions ?? []
  } catch { ElMessage.error('加载试卷详情失败'); detailDialogVisible.value = false }
  finally { loadingDetail.value = false }
}
async function discardPaper(paperId: number, paperName: string): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认废弃「${paperName}」？废弃后不可恢复。`, '废弃试卷', { type: 'warning', confirmButtonText: '确认废弃', cancelButtonText: '取消' })
    await unwrapApiResponse(platformApiClient.delete(`/admin/papers/${paperId}`))
    ElMessage.success('试卷已废弃'); void load()
  } catch { /* cancelled */ }
}
function startEdit(paper: Paper): void {
  if (paper.status !== 'DRAFT') return
  editingPaperId.value = paper.paperId; editingName.value = paper.paperName
  nextTick(() => editingInputRef.value?.focus())
}
async function saveEdit(paper: Paper): Promise<void> {
  const name = editingName.value.trim()
  if (!name) { cancelEdit(); return }
  try {
    await unwrapApiResponse(platformApiClient.put(`/admin/papers/${paper.paperId}`, { paperName: name }))
    paper.paperName = name; ElMessage.success('名称已更新')
  } catch { ElMessage.error('保存失败') } finally { cancelEdit() }
}
function cancelEdit(): void { editingPaperId.value = null; editingName.value = '' }
async function copyPaper(paperId: number): Promise<void> {
  try { const copy = await unwrapApiResponse<{ paperId: number }>(platformApiClient.post(`/admin/papers/${paperId}/copy`)); ElMessage.success('试卷副本已创建'); await router.push(`/admin/papers/${copy.paperId}/edit`) } catch { ElMessage.error('复制试卷失败') }
}
async function continueReview(paperId: number): Promise<void> {
  try {
    const detail = await unwrapApiResponse<{ batch: { batchId: number } }>(platformApiClient.get(`/admin/question-imports/by-paper/${paperId}`))
    await router.push(`/admin/question-imports/${detail.batch.batchId}`)
  } catch {
    ElMessage.error('未找到该试卷对应的审核批次')
  }
}
const typeLabel = (paper: Paper) => paper.paperType === 'REAL' ? '历史真题' : '模拟卷'
onMounted(async () => { try { if (qualificationStore.status === 'idle') await qualificationStore.load() } catch { /* optional preload */ } void load() })
</script>

<template>
  <section class="paper-page" v-loading="loading">
    <header class="paper-header">
      <div>
        <p>题库运营中心 <span>/</span> 试卷资产</p>
        <h1>试卷管理</h1>
        <small>集中管理历史真题、模拟卷及其导入审核进度。</small>
      </div>
      <div class="header-actions"><el-button @click="load"><el-icon><Refresh /></el-icon>刷新</el-button><el-button type="primary" @click="router.push('/admin/papers/new')"><el-icon><CirclePlus /></el-icon>新建组卷</el-button><el-button @click="showImport = true"><el-icon><UploadFilled /></el-icon>导入试卷</el-button></div>
    </header>

    <section class="paper-overview">
      <div class="overview-primary"><span class="overview-icon"><el-icon><Collection /></el-icon></span><div><small>当前试卷资产</small><strong>{{ total }} 份试卷</strong><p>导入完成后需审核，符合条件的历史真题可用于机考。</p></div></div>
      <div class="overview-metric"><small>本页已发布</small><strong>{{ publishedCount }}</strong><span>可进入正式使用范围</span></div>
      <div class="overview-metric"><small>本页待审核</small><strong>{{ draftCount }}</strong><span>可继续检查题目内容</span></div>
      <div class="overview-metric"><small>本页历史真题</small><strong>{{ realCount }}</strong><span>满足条件后可用于机考</span></div>
    </section>

    <section class="filter-panel">
      <div class="filter-heading"><span class="filter-icon"><el-icon><Filter /></el-icon></span><div><strong>筛选试卷</strong><small v-if="activeFilterCount">已应用 {{ activeFilterCount }} 个条件</small><small v-else>按类型与审核状态快速定位</small></div></div>
      <div class="filter-controls">
        <el-input v-model="query.keyword" placeholder="搜索试卷名称" clearable @keyup.enter="search" style="width:172px"><template #prefix><el-icon><Search /></el-icon></template></el-input>
        <el-select v-model="query.qualificationId" clearable placeholder="全部资格" style="width:150px" @change="onQualificationChange"><el-option v-for="q in qualificationStore.qualifications" :key="q.id" :label="q.name" :value="q.id" /></el-select>
        <el-select v-model="query.subjectId" clearable placeholder="全部科目" style="width:150px" :disabled="!query.qualificationId"><el-option v-for="s in qualificationStore.subjects" :key="s.id" :label="s.name" :value="s.id" /></el-select>
        <el-select v-model="query.paperType" clearable placeholder="全部试卷类型" style="width:140px"><el-option label="历史真题" value="REAL" /><el-option label="模拟卷" value="MOCK" /></el-select>
        <el-select v-model="query.status" clearable placeholder="全部状态" style="width:150px"><el-option label="草稿 / 待审核" value="DRAFT" /><el-option label="已发布" value="PUBLISHED" /></el-select>
        <el-input-number v-model="query.yearNo" placeholder="年份" :min="2000" :max="2100" controls-position="right" style="width:120px" />
        <el-button type="primary" @click="search">查询</el-button><el-button :disabled="!activeFilterCount" @click="resetFilters">重置</el-button>
      </div>
    </section>

    <section class="paper-table-panel">
      <header class="panel-heading"><div><span class="panel-icon"><el-icon><DocumentAdd /></el-icon></span><div><small>试卷目录</small><h2>全部试卷</h2></div></div><span>共 {{ total }} 份</span></header>
      <el-table :data="papers" row-key="paperId" empty-text="暂无试卷" :max-height="560">
        <el-table-column label="试卷" min-width="290"><template #default="scope"><div class="paper-title"><template v-if="editingPaperId === scope.row.paperId"><el-input ref="editingInputRef" v-model="editingName" size="small" @keyup.enter="saveEdit(scope.row)" @blur="saveEdit(scope.row)" @keyup.esc="cancelEdit" /></template><strong v-else class="name-cell" :class="{ 'is-editable': scope.row.status === 'DRAFT' }" @click="startEdit(scope.row)">{{ scope.row.paperName }}</strong><small>{{ typeLabel(scope.row) }}<template v-if="scope.row.yearNo"> · {{ scope.row.yearNo }} 年</template> · ID {{ scope.row.paperId }}</small></div></template></el-table-column>
        <el-table-column label="类型" width="110"><template #default="scope"><span class="type-tag" :class="scope.row.paperType === 'REAL' ? 'is-real' : 'is-mock'">{{ typeLabel(scope.row) }}</span></template></el-table-column>
        <el-table-column label="试卷规格" width="145"><template #default="scope"><div class="paper-spec"><span>{{ scope.row.totalScore }} 分</span><small><el-icon><Timer /></el-icon>{{ scope.row.durationMinutes }} 分钟</small></div></template></el-table-column>
        <el-table-column label="审核状态" width="120"><template #default="scope"><span class="status-tag" :class="scope.row.status === 'PUBLISHED' ? 'is-published' : 'is-draft'"><i></i>{{ scope.row.status === 'PUBLISHED' ? '已发布' : '待审核' }}</span></template></el-table-column>
        <el-table-column label="机考资格" min-width="145"><template #default="scope"><span class="exam-ready" :class="scope.row.status === 'PUBLISHED' && scope.row.paperType === 'REAL' ? 'is-ready' : ''"><i></i>{{ scope.row.status === 'PUBLISHED' && scope.row.paperType === 'REAL' ? '可用于机考' : '待审核后启用' }}</span></template></el-table-column>
        <el-table-column label="操作" width="200" align="right"><template #default="scope"><div class="action-group"><el-button v-if="scope.row.status === 'DRAFT'" link type="primary" @click="continueReview(scope.row.paperId)"><el-icon><EditPen /></el-icon>继续审核</el-button><el-button v-if="scope.row.status === 'PUBLISHED'" link type="primary" @click="viewDetail(scope.row)"><el-icon><View /></el-icon>查看详情</el-button><el-button v-if="scope.row.status === 'DRAFT'" link type="danger" @click="discardPaper(scope.row.paperId, scope.row.paperName)"><el-icon><Delete /></el-icon>废弃</el-button><span v-if="scope.row.status === 'PUBLISHED' && scope.row.paperType !== 'REAL'" class="muted-action">可复制</span></div></template></el-table-column>
        <el-table-column label="复制" width="90" align="right"><template #default="scope"><el-button v-if="scope.row.status === 'PUBLISHED'" link type="primary" @click="copyPaper(scope.row.paperId)">复制组卷</el-button></template></el-table-column>
      </el-table>
      <footer class="table-footer"><span>每页 {{ query.pageSize }} 条</span><el-pagination v-model:current-page="query.pageNo" :page-size="query.pageSize" :total="total" layout="total, prev, pager, next" @current-change="load" /></footer>
    </section>

    <el-dialog v-model="detailDialogVisible" title="试卷详情" width="min(700px, calc(100vw - 28px)" v-loading="loadingDetail">
      <template v-if="detailPaper">
        <div class="detail-header"><h2>{{ detailPaper.paperName }}</h2><p><span>共 {{ detailQuestions.length }} 题</span><span>{{ detailPaper.totalScore }} 分</span><span>{{ detailPaper.durationMinutes }} 分钟</span><span>{{ detailPaper.paperType === 'REAL' ? '历史真题' : '模拟卷' }}</span><template v-if="detailPaper.yearNo"><span>{{ detailPaper.yearNo }} 年</span></template></p></div>
        <el-table :data="detailQuestions" max-height="460" empty-text="暂无题目">
          <el-table-column label="题号" width="60" prop="questionNo" />
          <el-table-column label="题型" width="90"><template #default="scope">{{ typeLabels[scope.row.questionType] || scope.row.questionType }}</template></el-table-column>
          <el-table-column label="题目" min-width="240"><template #default="scope"><span class="detail-title">{{ scope.row.title }}</span></template></el-table-column>
          <el-table-column label="分值" width="80" prop="score" />
        </el-table>
      </template>
      <template #footer><el-button @click="detailDialogVisible = false">关闭</el-button></template>
    </el-dialog>

    <el-dialog
      v-model="showImport"
      class="paper-import-dialog"
      width="min(1180px, calc(100vw - 32px))"
      :show-close="false"
      append-to-body
      align-center
      destroy-on-close
    >
      <AdminPaperImportPanel @close="showImport = false" @imported="onImported" />
    </el-dialog>
  </section>
</template>

<style scoped>
.paper-page { min-width:0; padding:28px clamp(20px,3vw,46px) 48px; color:#172033; background:#f5f7fb; }.paper-page>*+* { margin-top:18px; }
.paper-header,.header-actions,.paper-overview,.overview-primary,.filter-panel,.filter-heading,.filter-controls,.panel-heading,.panel-heading>div,.table-footer,.paper-spec { display:flex; align-items:center; }.paper-header,.panel-heading,.table-footer { justify-content:space-between; gap:16px; }.paper-header p { margin:0; color:#76859b; font-size:12px; }.paper-header p span { margin:0 6px; color:#b0bac9; }.paper-header h1 { margin:5px 0; font-size:28px; letter-spacing:-.02em; }.paper-header small { color:#748198; font-size:13px; }.header-actions { flex:0 0 auto; gap:10px; }.paper-header :deep(.el-button) { min-height:38px; border-radius:9px; }
.paper-overview,.filter-panel,.paper-table-panel { border:1px solid #dfe6f0; border-radius:16px; background:#fff; box-shadow:0 9px 28px rgba(31,45,75,.045); }.paper-overview { gap:18px; padding:19px 22px; }.overview-primary { min-width:0; flex:1; gap:12px; }.overview-primary>div { display:grid; min-width:0; gap:2px; }.overview-icon,.filter-icon,.panel-icon { display:grid; flex:0 0 auto; place-items:center; border-radius:11px; }.overview-icon { width:42px; height:42px; background:#eaf1ff; color:#2563eb; font-size:20px; }.overview-primary small,.overview-metric small { color:#8290a4; font-size:11px; }.overview-primary strong { overflow:hidden; color:#26334a; font-size:18px; text-overflow:ellipsis; white-space:nowrap; }.overview-primary p { overflow:hidden; margin:0; color:#8c98aa; font-size:11px; text-overflow:ellipsis; white-space:nowrap; }.overview-metric { display:grid; min-width:132px; padding-left:20px; gap:4px; border-left:1px solid #e9edf3; }.overview-metric strong { color:#2f3e57; font-size:23px; }.overview-metric span { color:#98a3b4; font-size:10px; }
.filter-panel { display:flex; padding:14px 18px; align-items:center; justify-content:space-between; gap:18px; }.filter-heading { gap:10px; }.filter-heading>div { display:grid; gap:2px; }.filter-heading strong { color:#344057; font-size:13px; }.filter-heading small { color:#8a97a9; font-size:10px; }.filter-icon { width:34px; height:34px; background:#f0edff; color:#7054d9; }.filter-controls { flex:0 0 auto; gap:8px; }.filter-controls :deep(.el-button) { min-height:35px; border-radius:8px; }
.paper-table-panel { min-width:0; overflow:hidden; }.panel-heading { min-height:66px; padding:0 18px; border-bottom:1px solid #edf0f5; }.panel-heading>div { gap:10px; }.panel-heading>div>div { display:grid; gap:2px; }.panel-heading small,.panel-heading>span { color:#8290a4; font-size:11px; }.panel-heading h2 { margin:0; color:#29364c; font-size:16px; }.panel-icon { width:36px; height:36px; background:#eaf1ff; color:#2563eb; }.paper-table-panel :deep(.el-table) { width:100%; }.paper-table-panel :deep(.el-table th.el-table__cell) { height:46px; color:#6b788c; background:#f8f9fb; font-size:11px; }.paper-table-panel :deep(.el-table td.el-table__cell) { height:60px; color:#344057; }.paper-table-panel :deep(.el-table__inner-wrapper::before) { display:none; }.paper-title { display:grid; min-width:0; gap:4px; }.paper-title strong,.paper-title small { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }.paper-title strong { color:#344057; font-size:13px; }.paper-title small { color:#95a0b1; font-size:10px; }.type-tag,.status-tag,.exam-ready { display:inline-flex; align-items:center; gap:5px; padding:5px 8px; border-radius:999px; font-size:10px; font-weight:650; }.type-tag.is-real { color:#2563eb; background:#eaf1ff; }.type-tag.is-mock { color:#7255d8; background:#f0edff; }.paper-spec { align-items:flex-start; flex-direction:column; gap:3px; color:#3e4c64; font-size:12px; font-weight:650; }.paper-spec small { display:flex; align-items:center; gap:3px; color:#8a97a9; font-size:10px; font-weight:400; }.status-tag i,.exam-ready i { width:6px; height:6px; border-radius:50%; background:currentColor; }.status-tag.is-published { color:#168653; background:#e9f8f0; }.status-tag.is-draft { color:#a46f1d; background:#fff6e5; }.exam-ready { color:#8995a7; background:#f1f4f7; }.exam-ready.is-ready { color:#16734d; background:#e9f8f0; }.muted-action { color:#9aa5b5; font-size:11px; }
.name-cell.is-editable { cursor:pointer; transition:color .15s; border-bottom:1px dashed transparent; }
.name-cell.is-editable:hover { color:#2563eb; border-bottom-color:#2563eb; }
.action-group { display:flex; align-items:center; gap:6px; white-space:nowrap; }
.detail-header { margin-bottom:16px; }.detail-header h2 { margin:0 0 6px; font-size:20px; }.detail-header p { display:flex; flex-wrap:wrap; gap:12px; margin:0; color:#7b8799; font-size:13px; }.detail-title { overflow:hidden; display:inline-block; max-width:360px; text-overflow:ellipsis; white-space:nowrap; }.table-footer { min-height:62px; padding:0 18px; border-top:1px solid #edf0f5; }.table-footer>span { color:#97a2b3; font-size:11px; }
:global(.paper-import-dialog.el-dialog) { overflow:hidden; margin:0; padding:0; border:1px solid rgba(148,163,184,.24); border-radius:20px; background:#fff; box-shadow:0 28px 80px rgba(15,23,42,.2),0 6px 20px rgba(15,23,42,.08); }
:global(.paper-import-dialog .el-dialog__header) { display:none; }
:global(.paper-import-dialog .el-dialog__body) { padding:0; }
@media(max-width:1200px){.filter-panel{align-items:flex-start;flex-direction:column}.filter-controls{width:100%;flex-wrap:wrap}.filter-controls>*{flex:1;min-width:120px!important;max-width:200px!important}}
@media(max-width:1080px){.paper-overview{align-items:flex-start;flex-wrap:wrap}.overview-primary{flex-basis:100%}.overview-metric{min-width:calc(33.333% - 12px);padding:10px 0 0;border-top:1px solid #e9edf3;border-left:0}.filter-controls{width:100%;flex-wrap:wrap}.filter-controls>*{flex:1;min-width:100px!important}.paper-table-panel :deep(.el-table){max-height:480px}}
@media(max-width:680px){.paper-page{padding:20px 14px 36px}.paper-header{align-items:flex-start;flex-direction:column}.header-actions{width:100%}.header-actions :deep(.el-button){flex:1}.overview-metric{min-width:calc(50% - 9px)}.filter-controls{align-items:stretch;flex-direction:column}.filter-controls>*{width:100%!important}.table-footer{align-items:flex-start;flex-direction:column;padding:12px 14px;gap:8px}.paper-table-panel :deep(.el-pagination){transform:scale(.88);transform-origin:left top}}
</style>
