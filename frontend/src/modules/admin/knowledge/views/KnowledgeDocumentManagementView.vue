<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { CircleCheck, Collection, Document as DocumentIcon, FolderOpened, Plus, Refresh, UploadFilled } from '@element-plus/icons-vue'
import { platformApiClient, toSafeUserMessage, unwrapApiResponse } from '@/api/client'

type Document = {
  id: string; documentCode: string; title: string; status: string; fragmentCount: number
  indexedCount: number; reviewedCount: number; failedCount: number; processingStatus: string; updatedAt?: string
}
type Fragment = {
  id: string; fragmentNo: number; fragmentKey: string; content: string; status: string
  indexStatus: string; qualityStatus: string; contentKind?: string; sourcePages?: string
  embeddingModel?: string; embeddingVersion?: string; embeddingDimension?: number
  qdrantPointId?: string; embeddedAt?: string
}
type Page<T> = { items: T[] }
type Qualification = { id: number; name: string }
type Subject = { id: number; name: string }
const documents = ref<Document[]>([])
const qualifications = ref<Qualification[]>([])
const subjects = ref<Subject[]>([])
const loading = ref(false)
const reindexing = ref(false)
const formOpen = ref(false)
const uploadFile = ref<File | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const detailOpen = ref(false)
const selectedDocument = ref<Document | null>(null)
const fragments = ref<Fragment[]>([])
const fragmentsLoading = ref(false)
const publishedCount = computed(() => documents.value.filter(item => item.status === 'PUBLISHED').length)
const draftCount = computed(() => documents.value.filter(item => item.status !== 'PUBLISHED' && item.status !== 'OFFLINE').length)
const fragmentTotal = computed(() => documents.value.reduce((total, item) => total + item.fragmentCount, 0))
const indexedTotal = computed(() => documents.value.reduce((total, item) => total + item.indexedCount, 0))
const form = reactive({ qualificationId: 1, subjectId: 1, documentCode: '', title: '', sourceName: '', sourceUrl: '', copyrightBasis: '', content: '', knowledgeType: '', knowledgeId: undefined as number | undefined })
async function loadQualifications() {
  const page = await unwrapApiResponse<Page<Qualification>>(platformApiClient.get('/foundation/qualifications', { params: { pageNo: 1, pageSize: 100 } }))
  qualifications.value = page.items
  if (!qualifications.value.some(item => item.id === form.qualificationId) && qualifications.value[0]) {
    form.qualificationId = qualifications.value[0].id
  }
}
async function loadSubjects(qualificationId: number) {
  const page = await unwrapApiResponse<Page<Subject>>(platformApiClient.get(`/foundation/qualifications/${qualificationId}/subjects`, { params: { pageNo: 1, pageSize: 100 } }))
  subjects.value = page.items
  if (!subjects.value.some(item => item.id === form.subjectId) && subjects.value[0]) {
    form.subjectId = subjects.value[0].id
  }
}
function openCreatePanel() { formOpen.value = true }
function closeCreatePanel() { formOpen.value = false; uploadFile.value = null }
async function load() {
  loading.value = true
  try {
    documents.value = await unwrapApiResponse<Document[]>(platformApiClient.get('/admin/knowledge-documents'))
    if (selectedDocument.value) {
      selectedDocument.value = documents.value.find(item => String(item.id) === String(selectedDocument.value?.id)) ?? null
    }
  } finally { loading.value = false }
}
async function create() {
  if (!form.documentCode || !form.title || !form.sourceName || !form.copyrightBasis || !form.content) { ElMessage.warning('请填写编码、标题、来源、授权依据和正文'); return }
  loading.value = true
  try { await unwrapApiResponse(platformApiClient.post('/admin/knowledge-documents', form)); Object.assign(form, { documentCode: '', title: '', sourceName: '', sourceUrl: '', copyrightBasis: '', content: '', knowledgeType: '', knowledgeId: undefined }); await load(); ElMessage.success('资料已完成清洗、切片和向量化') } catch (error) { ElMessage.error(toSafeUserMessage(error)) } finally { loading.value = false }
}
async function upload() {
  if (!uploadFile.value || !form.documentCode || !form.title || !form.sourceName || !form.copyrightBasis) {
    ElMessage.warning('请先填写资料元数据并选择 PDF 或 TXT 文件')
    return
  }
  loading.value = true
  try {
    const body = new FormData()
    body.append('qualificationId', String(form.qualificationId)); body.append('subjectId', String(form.subjectId))
    body.append('documentCode', form.documentCode); body.append('title', form.title); body.append('sourceName', form.sourceName)
    body.append('sourceUrl', form.sourceUrl); body.append('copyrightBasis', form.copyrightBasis)
    if (form.knowledgeType) body.append('knowledgeType', form.knowledgeType)
    if (form.knowledgeId) body.append('knowledgeId', String(form.knowledgeId))
    body.append('file', uploadFile.value)
    await unwrapApiResponse(platformApiClient.post('/admin/knowledge-documents/upload', body))
    uploadFile.value = null; await load(); ElMessage.success('文件已完成解析、切片和向量化')
  } catch (error) { ElMessage.error(toSafeUserMessage(error)) } finally { loading.value = false }
}
function chooseFile(event: Event) {
  uploadFile.value = (event.target as HTMLInputElement).files?.[0] ?? null
}
const statusLabel = (status: string) => status === 'PUBLISHED' ? '已发布' : status === 'OFFLINE' ? '已下架' : '草稿'
const processingLabel = (status: string) => ({
  INDEXED: '已索引', PROCESSING: '处理中', FAILED: '处理失败',
  PARTIAL: '部分完成', INVALIDATED: '已失效', READY: '待处理',
} as Record<string, string>)[status] ?? status
async function openFragments(document: Document) {
  selectedDocument.value = document
  detailOpen.value = true
  fragmentsLoading.value = true
  try {
    fragments.value = await unwrapApiResponse<Fragment[]>(platformApiClient.get(`/admin/knowledge-documents/${document.id}/fragments`))
  } catch (error) {
    fragments.value = []
    ElMessage.error(toSafeUserMessage(error))
  } finally { fragmentsLoading.value = false }
}
async function reindex(document: Document) {
  reindexing.value = true
  try {
    const updated = await unwrapApiResponse<Document>(platformApiClient.post(`/admin/knowledge-documents/${document.id}/reindex`))
    ElMessage.success('重新向量索引已完成')
    await load()
    await openFragments(updated)
  } catch (error) {
    ElMessage.error(toSafeUserMessage(error))
  } finally { reindexing.value = false }
}
async function review(document: Document) {
  loading.value = true
  try {
    await unwrapApiResponse<Document>(platformApiClient.post(`/admin/knowledge-documents/${document.id}/review`))
    ElMessage.success('全部知识切片已审核通过')
    await load()
    const refreshed = documents.value.find(item => String(item.id) === String(document.id))
    if (detailOpen.value && refreshed) await openFragments(refreshed)
  } catch (error) {
    ElMessage.error(toSafeUserMessage(error))
  } finally { loading.value = false }
}
async function changeStatus(document: Document, action: 'publish' | 'offline') { try { await unwrapApiResponse(platformApiClient.post(`/admin/knowledge-documents/${document.id}/${action}`)); await load(); ElMessage.success(action === 'publish' ? '资料已发布' : '资料已下架') } catch (error) { ElMessage.error(toSafeUserMessage(error)) } }
watch(() => form.qualificationId, value => {
  loadSubjects(value).catch(error => ElMessage.error(toSafeUserMessage(error)))
})
onMounted(() => {
  Promise.all([load(), loadQualifications()])
    .catch(error => ElMessage.error(toSafeUserMessage(error)))
})
</script>

<template>
  <section class="knowledge-page">
    <header class="knowledge-header"><div><p>知识库管理 <span>/</span> 检索资料</p><h1>AI 知识库</h1><small>维护 AI 答疑与题目解析使用的可信资料来源</small></div><div class="header-actions"><el-button :loading="loading" @click="load"><el-icon><Refresh /></el-icon>刷新资料</el-button><el-button type="primary" @click="openCreatePanel"><el-icon><Plus /></el-icon>新增资料</el-button></div></header>

    <section class="knowledge-overview">
      <div class="overview-copy"><span class="overview-icon"><el-icon><Collection /></el-icon></span><div><small>检索知识库</small><strong>{{ documents.length }} 份资料</strong><p>仅已发布且具备授权依据的内容参与 AI 检索</p></div></div>
      <div class="overview-metric is-published"><span>已发布</span><strong>{{ publishedCount }}</strong></div>
      <div class="overview-metric"><span>待完善草稿</span><strong>{{ draftCount }}</strong></div>
      <div class="overview-metric"><span>切片总量</span><strong>{{ fragmentTotal }}</strong></div>
      <div class="overview-metric is-indexed"><span>已向量化</span><strong>{{ indexedTotal }}</strong></div>
    </section>

    <div v-if="formOpen" class="knowledge-workspace">
      <section class="knowledge-form">
        <div class="panel-heading"><div><span class="panel-icon"><el-icon><DocumentIcon /></el-icon></span><div><small>资料录入</small><h2>新增知识资料</h2></div></div><el-button link @click="closeCreatePanel">收起</el-button></div>
        <el-form label-position="top" class="form-grid">
          <el-form-item label="考试资格"><el-select v-model="form.qualificationId"><el-option v-for="item in qualifications" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
          <el-form-item label="考试科目"><el-select v-model="form.subjectId"><el-option v-for="item in subjects" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
          <el-form-item label="资料编码"><el-input v-model="form.documentCode" placeholder="例如 PM-GUIDE-001" /></el-form-item>
          <el-form-item label="资料标题"><el-input v-model="form.title" placeholder="输入便于识别的资料名称" /></el-form-item>
          <el-form-item label="资料来源"><el-input v-model="form.sourceName" placeholder="出版社、机构或内部资料名称" /></el-form-item>
          <el-form-item label="来源 URL"><el-input v-model="form.sourceUrl" placeholder="可选，填写公开来源地址" /></el-form-item>
          <el-form-item label="授权依据" class="wide"><el-input v-model="form.copyrightBasis" placeholder="例如：自有讲义、已取得授权、公开许可" /></el-form-item>
          <el-form-item label="资料正文" class="wide"><el-input v-model="form.content" type="textarea" :rows="8" placeholder="粘贴已审核资料正文，系统保存后将自动切分检索片段" /></el-form-item>
        </el-form>
        <div class="form-action"><span>保存后自动清洗、切片并向量化，审核发布前不会参与正式检索。</span><el-button type="primary" :loading="loading" @click="create">保存并处理</el-button></div>
      </section>

      <aside class="upload-panel">
        <div class="panel-heading"><div><span class="panel-icon panel-icon--green"><el-icon><UploadFilled /></el-icon></span><div><small>文件解析</small><h2>导入资料文件</h2></div></div></div>
        <p>复用左侧已填写的资料元数据，上传后自动完成正文提取、清洗、切片和向量化。</p>
        <input ref="fileInput" class="native-file-input" type="file" accept="application/pdf,text/plain,application/vnd.openxmlformats-officedocument.wordprocessingml.document,.pdf,.docx,.txt" @change="chooseFile">
        <button class="file-picker" type="button" @click="fileInput?.click()"><el-icon><FolderOpened /></el-icon><span><strong>{{ uploadFile?.name || '选择资料文件' }}</strong><small>支持 PDF、DOCX、UTF-8 TXT</small></span></button>
        <el-button type="primary" :loading="loading" :disabled="!uploadFile" @click="upload"><el-icon><UploadFilled /></el-icon>上传并处理</el-button>
        <div class="upload-note"><el-icon><CircleCheck /></el-icon><span>上传前请确认资料来源及使用授权，发布后内容将进入 AI 检索范围。</span></div>
      </aside>
    </div>

    <section class="knowledge-list">
      <div class="panel-heading"><div><span class="panel-icon panel-icon--slate"><el-icon><Collection /></el-icon></span><div><small>内容资产</small><h2>知识资料列表</h2></div></div><span>共 {{ documents.length }} 份资料</span></div>
      <el-table v-loading="loading" :data="documents" row-key="id" :max-height="560" empty-text="暂无 AI 知识资料">
        <el-table-column prop="documentCode" label="资料编码" min-width="160" />
        <el-table-column prop="title" label="资料标题" min-width="260"><template #default="scope"><div class="document-title"><strong>{{ scope.row.title }}</strong><small>ID {{ scope.row.id }}</small></div></template></el-table-column>
        <el-table-column label="状态" width="120"><template #default="scope"><span class="status-tag" :class="`is-${scope.row.status.toLowerCase()}`"><i></i>{{ statusLabel(scope.row.status) }}</span></template></el-table-column>
        <el-table-column label="处理状态" width="120"><template #default="scope"><span class="index-tag" :class="`is-${scope.row.processingStatus.toLowerCase()}`">{{ processingLabel(scope.row.processingStatus) }}</span></template></el-table-column>
        <el-table-column label="索引 / 审核" width="145"><template #default="scope"><strong class="index-count">{{ scope.row.indexedCount }}</strong><span class="index-total"> / {{ scope.row.reviewedCount }} / {{ scope.row.fragmentCount }}</span></template></el-table-column>
        <el-table-column label="操作" width="310" align="right"><template #default="scope"><el-button link @click="openFragments(scope.row)">查看切片</el-button><el-button v-if="scope.row.processingStatus !== 'INDEXED'" link :loading="reindexing" @click="reindex(scope.row)">重试索引</el-button><el-button v-if="scope.row.processingStatus === 'INDEXED' && scope.row.reviewedCount !== scope.row.fragmentCount" link @click="review(scope.row)">审核通过</el-button><el-button v-if="scope.row.status !== 'PUBLISHED'" link type="primary" :disabled="scope.row.processingStatus !== 'INDEXED' || scope.row.reviewedCount !== scope.row.fragmentCount" @click="changeStatus(scope.row, 'publish')">发布</el-button><el-button v-else link @click="changeStatus(scope.row, 'offline')">下架</el-button></template></el-table-column>
      </el-table>
    </section>

    <el-dialog
      v-model="detailOpen"
      width="min(960px, calc(100vw - 32px))"
      class="fragment-dialog"
      append-to-body
      align-center
      destroy-on-close
    >
      <template #header><div class="drawer-heading"><small>知识切片明细</small><h2>{{ selectedDocument?.title }}</h2><p>{{ selectedDocument?.documentCode }} · {{ selectedDocument?.indexedCount }} 已索引 · {{ selectedDocument?.reviewedCount }} 已审核</p></div></template>
      <div class="drawer-actions"><span>可查看清洗切片、Embedding 模型与 Qdrant 映射，不展示原始向量数组。</span><el-button :loading="reindexing" @click="selectedDocument && reindex(selectedDocument)"><el-icon><Refresh /></el-icon>重新向量化</el-button></div>
      <el-table v-loading="fragmentsLoading" :data="fragments" row-key="id" empty-text="暂无知识切片" max-height="min(560px, calc(100vh - 260px))">
        <el-table-column prop="fragmentNo" label="#" width="58" />
        <el-table-column label="切片正文" min-width="280"><template #default="scope"><div class="fragment-content"><p>{{ scope.row.content }}</p><small>{{ scope.row.fragmentKey }}</small></div></template></el-table-column>
        <el-table-column label="索引" width="105"><template #default="scope"><span class="index-tag" :class="`is-${scope.row.indexStatus.toLowerCase()}`">{{ processingLabel(scope.row.indexStatus) }}</span></template></el-table-column>
        <el-table-column label="向量信息" min-width="180"><template #default="scope"><div class="vector-meta"><strong>{{ scope.row.embeddingModel || '—' }}</strong><small>{{ scope.row.embeddingDimension ? `${scope.row.embeddingDimension} 维` : '未生成向量' }}</small><code>{{ scope.row.qdrantPointId || '无 Point ID' }}</code></div></template></el-table-column>
      </el-table>
    </el-dialog>
  </section>
</template>

<style scoped>
.knowledge-page { display:block; width:100%; min-width:0; padding:28px clamp(20px,3vw,46px) 48px; color:#172033; background:#f5f7fb; }
.knowledge-page > * + * { margin-top:18px; }
.knowledge-header,.knowledge-overview,.panel-heading,.panel-heading>div,.form-action,.upload-note { display:flex; align-items:center; }
.knowledge-header,.panel-heading,.form-action { justify-content:space-between; }
.knowledge-header > .header-actions { display:flex; flex:0 0 auto; gap:10px; }
.knowledge-header>div { display:grid; gap:5px; }.knowledge-header p { margin:0; color:#748198; font-size:13px; }.knowledge-header p span { margin:0 5px; color:#b0b9c8; }.knowledge-header h1 { margin:0; font-size:28px; }.knowledge-header small { color:#748198; font-size:13px; }.knowledge-header :deep(.el-button) { min-height:38px; border-radius:9px; }
.knowledge-overview { padding:20px 24px; gap:22px; border:1px solid #dce4f0; border-radius:16px; background:#fff; box-shadow:0 10px 30px rgba(31,45,75,.05); }
.overview-copy { display:flex; align-items:center; flex:1; gap:13px; }.overview-copy>div { display:grid; gap:2px; }.overview-icon,.panel-icon { display:grid; flex:0 0 auto; place-items:center; border-radius:12px; color:#2563eb; background:#eaf1ff; }.overview-icon { width:44px; height:44px; font-size:20px; }.overview-copy small,.overview-metric span { color:#7b879b; font-size:12px; }.overview-copy strong { font-size:19px; }.overview-copy p { margin:0; color:#909aac; font-size:12px; }
.overview-metric { display:grid; min-width:120px; padding-left:22px; gap:4px; border-left:1px solid #e7ebf1; }.overview-metric strong { color:#334158; font-size:24px; }.overview-metric.is-published strong { color:#168653; }.overview-metric.is-indexed strong { color:#2563eb; }
.knowledge-workspace { display:grid; min-width:0; grid-template-columns:minmax(0,1fr) 330px; gap:18px; align-items:start; }.knowledge-form,.upload-panel,.knowledge-list { min-width:0; overflow:hidden; border:1px solid #dfe5ef; border-radius:16px; background:#fff; box-shadow:0 8px 26px rgba(31,45,75,.04); }
.panel-heading { min-height:66px; padding:0 20px; border-bottom:1px solid #edf0f5; }.panel-heading>div { gap:10px; }.panel-heading>div>div { display:grid; gap:1px; }.panel-heading small,.panel-heading>span { color:#8190a4; font-size:11px; }.panel-heading h2 { margin:0; color:#273247; font-size:16px; }.panel-icon { width:36px; height:36px; border-radius:10px; }.panel-icon--green { color:#168653; background:#e8f8ef; }.panel-icon--slate { color:#60708a; background:#eef2f7; }
.form-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:0 14px; padding:20px 20px 4px; }.form-grid .wide { grid-column:1/-1; }.knowledge-page :deep(.el-form-item__label) { color:#536078; font-size:12px; }.knowledge-page :deep(.el-input-number) { width:100%; }.knowledge-page :deep(.el-input__wrapper),.knowledge-page :deep(.el-textarea__inner) { border-radius:9px; }.form-action { padding:15px 20px; border-top:1px solid #edf0f5; background:#fafbfc; }.form-action span { color:#7d899c; font-size:11px; }.form-action :deep(.el-button) { min-height:39px; border-radius:9px; }
.upload-panel { display:flex; flex-direction:column; gap:15px; padding:0 18px 18px; }.upload-panel .panel-heading { margin:0 -18px; }.upload-panel>p { margin:2px 0 0; color:#748198; font-size:12px; line-height:1.7; }.native-file-input { position:absolute; width:1px; height:1px; opacity:0; pointer-events:none; }.file-picker { display:flex; width:100%; min-height:98px; padding:16px; align-items:center; gap:12px; border:1px dashed #b9c9e5; border-radius:12px; color:#2563eb; background:#f7faff; cursor:pointer; text-align:left; }.file-picker>.el-icon { flex:0 0 auto; font-size:24px; }.file-picker span { display:grid; min-width:0; gap:4px; }.file-picker strong { overflow:hidden; color:#344057; font-size:13px; text-overflow:ellipsis; white-space:nowrap; }.file-picker small { color:#8290a5; }.upload-panel>:deep(.el-button) { min-height:40px; border-radius:9px; }.upload-note { align-items:flex-start; gap:7px; padding:11px; border-radius:9px; color:#657188; background:#f5f7fa; font-size:11px; line-height:1.6; }.upload-note .el-icon { flex:0 0 auto; margin-top:2px; color:#168653; }
.knowledge-list { width:100%; min-width:0; contain:layout paint; }.knowledge-list :deep(.el-table) { width:100%; max-width:100%; }.knowledge-list :deep(.el-table th.el-table__cell) { height:48px; color:#68758a; background:#f8f9fb; }.knowledge-list :deep(.el-table td.el-table__cell) { height:56px; color:#344057; }.knowledge-list :deep(.el-table__inner-wrapper::before) { display:none; }.document-title { display:grid; gap:3px; min-width:0; }.document-title strong { overflow:hidden; color:#2c384d; text-overflow:ellipsis; white-space:nowrap; }.document-title small { color:#909aac; font-size:10px; }
.status-tag { display:inline-flex; align-items:center; gap:6px; padding:5px 9px; border-radius:999px; color:#b77815; background:#fff6e5; font-size:11px; font-weight:600; }.status-tag i { width:6px; height:6px; border-radius:50%; background:currentColor; }.status-tag.is-published { color:#168653; background:#e9f8f0; }.status-tag.is-offline { color:#6f7b8e; background:#f0f2f5; }
.index-tag { display:inline-flex; padding:5px 8px; border-radius:6px; color:#6f7b8e; background:#f0f2f5; font-size:11px; font-weight:600; }.index-tag.is-indexed { color:#176f49; background:#e9f8f0; }.index-tag.is-failed { color:#c54646; background:#fff0f0; }.index-tag.is-processing,.index-tag.is-partial { color:#9b6818; background:#fff6e5; }.index-count { color:#2563eb; }.index-total { color:#98a2b2; font-size:11px; }
:global(.fragment-dialog.el-dialog) { overflow:hidden; border:1px solid #dfe5ef; border-radius:18px; box-shadow:0 24px 70px rgba(15,23,42,.18); }.fragment-dialog :deep(.el-dialog__header) { padding:20px 22px 16px; margin:0; border-bottom:1px solid #edf0f5; }.fragment-dialog :deep(.el-dialog__body) { padding:0 20px 20px; background:#f8f9fb; }.drawer-heading { display:grid; gap:3px; }.drawer-heading small { color:#748198; font-size:11px; }.drawer-heading h2 { margin:0; color:#172033; font-size:19px; }.drawer-heading p { margin:0; color:#8792a4; font-size:11px; }.drawer-actions { display:flex; min-height:58px; align-items:center; justify-content:space-between; gap:14px; }.drawer-actions span { color:#748198; font-size:11px; }.drawer-actions :deep(.el-button) { flex:0 0 auto; border-radius:8px; }.fragment-dialog :deep(.el-table) { border:1px solid #e1e6ee; border-radius:12px; }.fragment-content { display:grid; gap:5px; }.fragment-content p { display:-webkit-box; overflow:hidden; margin:0; color:#3d4960; line-height:1.55; -webkit-box-orient:vertical; -webkit-line-clamp:3; }.fragment-content small { overflow:hidden; color:#98a2b2; font-size:10px; text-overflow:ellipsis; white-space:nowrap; }.vector-meta { display:grid; gap:3px; }.vector-meta strong { overflow:hidden; color:#4d5a70; font-size:11px; text-overflow:ellipsis; white-space:nowrap; }.vector-meta small { color:#7d899c; font-size:10px; }.vector-meta code { overflow:hidden; color:#9aa3b2; font-size:9px; text-overflow:ellipsis; white-space:nowrap; }
@media(max-width:1050px){.knowledge-workspace{grid-template-columns:1fr}.upload-panel{min-height:auto}}
@media(max-width:700px){.knowledge-page{padding:20px 14px 36px}.knowledge-header{align-items:flex-start;flex-direction:column;gap:14px}.header-actions{width:100%}.header-actions :deep(.el-button){flex:1}.knowledge-overview{align-items:flex-start;flex-wrap:wrap}.overview-copy{flex-basis:100%}.overview-metric{min-width:calc(50% - 11px);padding:10px 0 0;border-top:1px solid #e7ebf1;border-left:0}.form-grid{grid-template-columns:1fr}.form-grid .wide{grid-column:auto}.form-action{align-items:flex-start;flex-direction:column;gap:10px}.form-action :deep(.el-button){width:100%}.panel-heading>span{display:none}.drawer-actions{align-items:flex-start;flex-direction:column;padding:12px 0}.drawer-actions :deep(.el-button){width:100%}}
</style>
