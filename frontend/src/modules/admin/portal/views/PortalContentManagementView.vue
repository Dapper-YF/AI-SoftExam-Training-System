<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowRight, DocumentChecked, Filter, Link, Refresh, Search } from '@element-plus/icons-vue'
import { platformApiClient, unwrapApiResponse } from '@/api/client'

type Status = 'PENDING_REVIEW' | 'PUBLISHED' | 'REJECTED' | 'OFFLINE'
type Category = 'REGISTRATION' | 'POLICY' | 'OFFICIAL_RESOURCE'
type ContentType = 'NEWS' | 'RESOURCE'
interface ContentItem {
  id: number
  sourceName: string
  sourceUrl: string
  title: string
  summary: string
  originalTitle: string
  originalSummary: string
  publishedAt: string
  sourcePublishedAt: string
  category: Category
  contentType: ContentType
  reviewCategory: Category
  reviewContentType: ContentType
  status: Status
  pinned: boolean
  pinOrder: number
  changeSummary?: string | null
  lastCrawledAt: string
  updatedAt: string
}
interface ContentPage { items: ContentItem[]; pageNo: number; pageSize: number; total: number }

const statuses: Status[] = ['PENDING_REVIEW', 'PUBLISHED', 'REJECTED', 'OFFLINE']
const statusLabels: Record<Status, string> = {
  PENDING_REVIEW: '待审核', PUBLISHED: '已发布', REJECTED: '已驳回', OFFLINE: '已下线',
}
const categoryLabels: Record<Category, string> = {
  REGISTRATION: '报名动态', POLICY: '政策资讯', OFFICIAL_RESOURCE: '官方资源',
}
const typeLabels: Record<ContentType, string> = { NEWS: '资讯', RESOURCE: '资源' }
function categoryLabel(value: unknown): string { return categoryLabels[value as Category] ?? String(value) }
function typeLabel(value: unknown): string { return typeLabels[value as ContentType] ?? String(value) }
function statusLabel(value: unknown): string { return statusLabels[value as Status] ?? String(value) }
function safeSummary(value?: string): string {
  if (!value) return ''
  if (value.includes('%PDF-')) return 'PDF 原文待重新提取，请通过原文链接查看。'
  return value.replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, '').slice(0, 300)
}
function formatDateTime(value: string | null | undefined): string {
  if (!value) return '未提供'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', { hour12: false })
}
const rows = ref<ContentItem[]>([])
const total = ref(0)
const loading = ref(false)
const submitting = ref(false)
const error = ref('')
const dialogOpen = ref(false)
const selected = ref<ContentItem | null>(null)
const query = reactive<{ pageNo: number; pageSize: number; status: Status | 'ALL' }>({ pageNo: 1, pageSize: 20, status: 'PENDING_REVIEW' })
const activeView = ref<'PENDING_REVIEW' | 'PUBLISHED'>('PENDING_REVIEW')
const publishedCategory = ref<Category | 'ALL'>('ALL')
const keyword = ref('')
const form = reactive<{ title: string; summary: string; category: Category; contentType: ContentType }>({
  title: '', summary: '', category: 'POLICY', contentType: 'NEWS',
})
const hasChanges = computed(() => selected.value && (
  form.title !== selected.value.title || form.summary !== selected.value.summary
  || form.category !== selected.value.category || form.contentType !== selected.value.contentType
))
const pinnedCount = computed(() => rows.value.filter(item => item.pinned).length)
const currentViewLabel = computed(() => activeView.value === 'PENDING_REVIEW' ? '待审核内容' : '已发布内容')

function messageOf(reason: unknown, fallback: string): string {
  return reason instanceof Error && reason.message ? reason.message : fallback
}
async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const page = await unwrapApiResponse<ContentPage>(platformApiClient.get('/admin/portal/contents', {
      params: {
        pageNo: query.pageNo,
        pageSize: query.pageSize,
        status: activeView.value === 'PUBLISHED' ? 'PUBLISHED' : query.status === 'ALL' ? undefined : query.status,
        category: activeView.value === 'PUBLISHED' && publishedCategory.value !== 'ALL' ? publishedCategory.value : undefined,
        keyword: keyword.value.trim() || undefined,
      },
    }))
    rows.value = page.items
    total.value = page.total
  } catch (reason) {
    rows.value = []
    total.value = 0
    error.value = messageOf(reason, '资讯审核列表加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}
async function togglePin(item: ContentItem): Promise<void> {
  try {
    const action = item.pinned ? 'unpin' : 'pin'
    await unwrapApiResponse<ContentItem>(platformApiClient.post(`/admin/portal/contents/${item.id}/${action}`))
    ElMessage.success(item.pinned ? '已取消置顶' : '已置顶展示')
    await load()
  } catch (reason) {
    ElMessage.error(messageOf(reason, '置顶操作失败，请稍后重试'))
  }
}
function selectView(view: 'PENDING_REVIEW' | 'PUBLISHED'): void {
  activeView.value = view
  query.status = view
  query.pageNo = 1
  void load()
}
async function open(item: ContentItem): Promise<void> {
  try {
    const detail = await unwrapApiResponse<ContentItem>(platformApiClient.get(`/admin/portal/contents/${item.id}`))
    selected.value = detail
    Object.assign(form, { title: detail.title, summary: detail.summary, category: detail.category, contentType: detail.contentType })
    dialogOpen.value = true
  } catch (reason) {
    ElMessage.error(messageOf(reason, '资讯详情加载失败'))
  }
}
async function save(): Promise<boolean> {
  if (!selected.value) return false
  submitting.value = true
  try {
    const updated = await unwrapApiResponse<ContentItem>(platformApiClient.put(`/admin/portal/contents/${selected.value.id}`, form))
    selected.value = updated
    Object.assign(form, { title: updated.title, summary: updated.summary, category: updated.category, contentType: updated.contentType })
    await load()
    return true
  } catch (reason) {
    ElMessage.error(messageOf(reason, '保存失败，请稍后重试'))
    return false
  } finally {
    submitting.value = false
  }
}
async function transition(action: 'publish' | 'reject' | 'offline'): Promise<void> {
  if (!selected.value) return
  if (hasChanges.value && !(await save())) return
  submitting.value = true
  try {
    const updated = await unwrapApiResponse<ContentItem>(platformApiClient.post(`/admin/portal/contents/${selected.value.id}/${action}`))
    selected.value = updated
    dialogOpen.value = false
    ElMessage.success(action === 'publish' ? '资讯已发布' : action === 'reject' ? '资讯已驳回' : '资讯已下线')
    await load()
  } catch (reason) {
    ElMessage.error(messageOf(reason, '状态更新失败，请稍后重试'))
  } finally {
    submitting.value = false
  }
}
function filter(): void { query.pageNo = 1; void load() }
onMounted(() => { void load() })
</script>

<template>
  <section class="portal-admin-page" data-testid="portal-content-admin-page">
    <header class="page-header">
      <div><p>内容运营 / 资讯审核</p><h1>资讯内容审核</h1><span>核验抓取内容、调整展示信息，并管理发布与置顶状态。</span></div>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新列表</el-button>
    </header>

    <section class="content-overview">
      <div class="overview-primary"><span class="overview-icon"><el-icon><DocumentChecked /></el-icon></span><div><small>当前工作区</small><strong>{{ currentViewLabel }}</strong><p>{{ activeView === 'PENDING_REVIEW' ? '优先处理待核验内容，发布前确认标题、摘要和来源。' : '维护已上线内容的栏目、置顶和展示状态。' }}</p></div></div>
      <div class="overview-metric"><small>符合条件</small><strong>{{ total }}</strong><span>条内容</span></div>
      <div class="overview-metric"><small>本页置顶</small><strong>{{ pinnedCount }}</strong><span>条内容</span></div>
    </section>

    <section class="toolbar">
      <div class="view-switch">
        <button type="button" :class="{ active: activeView === 'PENDING_REVIEW' }" @click="selectView('PENDING_REVIEW')">待审核</button>
        <button type="button" :class="{ active: activeView === 'PUBLISHED' }" @click="selectView('PUBLISHED')">已发布</button>
      </div>
      <div class="filter-controls">
        <span class="filter-label"><el-icon><Filter /></el-icon>筛选</span>
        <el-select v-if="activeView === 'PUBLISHED'" v-model="publishedCategory" data-testid="portal-category-filter" @change="filter">
          <el-option label="全部已发布资讯" value="ALL" />
          <el-option v-for="(label, key) in categoryLabels" :key="key" :label="label" :value="key" />
        </el-select>
        <el-select v-else v-model="query.status" data-testid="portal-status-filter" @change="filter">
          <el-option label="全部状态" value="ALL" />
          <el-option v-for="status in statuses" :key="status" :label="statusLabels[status]" :value="status" />
        </el-select>
        <el-input v-model="keyword" class="keyword-input" :prefix-icon="Search" clearable placeholder="搜索标题或来源网站" @clear="filter" @keyup.enter="filter" />
        <el-button type="primary" @click="filter">查询内容</el-button>
      </div>
    </section>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <section class="content-table">
      <header class="table-heading"><div><small>内容列表</small><h2>{{ currentViewLabel }}</h2></div><span>共 {{ total }} 条</span></header>
      <el-table v-loading="loading" :data="rows" row-key="id" empty-text="暂无符合条件的资讯">
        <el-table-column label="资讯内容" min-width="320"><template #default="scope"><div class="content-title"><strong>{{ scope.row.title }}</strong><small>{{ safeSummary(scope.row.summary) || '暂无摘要' }}</small></div></template></el-table-column>
        <el-table-column label="栏目 / 类型" width="150"><template #default="scope"><div class="classification"><span>{{ categoryLabel(scope.row.reviewCategory) }}</span><small>{{ typeLabel(scope.row.reviewContentType) }}</small></div></template></el-table-column>
        <el-table-column label="状态" width="110"><template #default="scope"><span class="status-pill" :class="`is-${scope.row.status.toLowerCase()}`"><i></i>{{ statusLabel(scope.row.status) }}</span></template></el-table-column>
        <el-table-column label="官方来源" min-width="180" show-overflow-tooltip><template #default="scope"><a class="source-link" :href="scope.row.sourceUrl" target="_blank" rel="noreferrer"><el-icon><Link /></el-icon>{{ scope.row.sourceName || '未提供' }}</a></template></el-table-column>
        <el-table-column label="发布时间" min-width="165"><template #default="scope"><div class="date-cell"><strong>{{ formatDateTime(scope.row.sourcePublishedAt) }}</strong><small>抓取于 {{ formatDateTime(scope.row.lastCrawledAt) }}</small></div></template></el-table-column>
        <el-table-column label="展示" width="90"><template #default="scope"><span v-if="scope.row.pinned" class="pin-pill">置顶</span><span v-else class="muted">常规</span></template></el-table-column>
        <el-table-column label="操作" width="200" fixed="right" align="right"><template #default="scope"><el-button v-if="scope.row.status === 'PUBLISHED'" link type="warning" @click="togglePin(scope.row)">{{ scope.row.pinned ? '取消置顶' : '设为置顶' }}</el-button><el-button link type="primary" @click="open(scope.row)">审核详情<el-icon><ArrowRight /></el-icon></el-button></template></el-table-column>
      </el-table>
      <footer class="table-footer"><span>每页显示 {{ query.pageSize }} 条</span><el-pagination v-model:current-page="query.pageNo" v-model:page-size="query.pageSize" :total="total" :page-sizes="[10,20,50]" layout="total, sizes, prev, pager, next" @current-change="load" @size-change="query.pageNo=1; load()" /></footer>
    </section>
    <el-dialog v-model="dialogOpen" class="content-review-dialog" width="min(980px, calc(100vw - 32px))" destroy-on-close align-center>
      <template v-if="selected">
        <div class="dialog-intro"><div><small>内容审核</small><h3>{{ selected.title }}</h3><p>{{ selected.changeSummary || '请对照官方来源核验标题与摘要后再发布。' }}</p></div><span class="status-pill" :class="`is-${selected.status.toLowerCase()}`"><i></i>{{ statusLabel(selected.status) }}</span></div>
        <div class="meta"><span>{{ selected.sourceName || '未提供来源' }}</span><a :href="selected.sourceUrl" target="_blank" rel="noreferrer"><el-icon><Link /></el-icon>打开官方来源</a><span>{{ formatDateTime(selected.sourcePublishedAt) }}</span></div>
        <div class="comparison">
          <section class="original-content"><div class="section-label"><small>只读对照</small><h3>抓取原文</h3></div><h4>{{ selected.originalTitle }}</h4><p>{{ safeSummary(selected.originalSummary) || '原文未提供摘要' }}</p></section>
          <section class="editable-content"><div class="section-label"><small>用户端展示</small><h3>发布内容</h3></div><label>标题</label><el-input v-model="form.title" maxlength="500" show-word-limit /><label>摘要</label><el-input v-model="form.summary" type="textarea" :rows="7" maxlength="1000" show-word-limit /></section>
        </div>
        <div class="form-row"><label>展示栏目<el-select v-model="form.category"><el-option v-for="(label,key) in categoryLabels" :key="key" :label="label" :value="key" /></el-select></label><label>内容类型<el-select v-model="form.contentType"><el-option v-for="(label,key) in typeLabels" :key="key" :label="label" :value="key" /></el-select></label></div>
      </template>
      <template #footer><div class="dialog-footer"><span>{{ hasChanges ? '存在尚未保存的修改' : '当前内容已保存' }}</span><div><el-button :loading="submitting" :disabled="!hasChanges" @click="save">保存修改</el-button><el-button v-if="selected?.status === 'PENDING_REVIEW'" :loading="submitting" @click="transition('reject')">驳回</el-button><el-button v-if="selected?.status === 'PENDING_REVIEW'" type="primary" :loading="submitting" @click="transition('publish')">审核并发布</el-button><el-button v-if="selected?.status === 'PUBLISHED'" type="danger" plain :loading="submitting" @click="transition('offline')">下线内容</el-button></div></div></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.portal-admin-page{display:grid;gap:18px;min-width:0;min-height:calc(100vh - var(--platform-header-height));padding:28px clamp(20px,3vw,46px) 48px;color:#172033;background:#f5f7fb}.page-header,.content-overview,.overview-primary,.toolbar,.filter-controls,.table-heading,.table-footer,.meta,.form-row,.dialog-intro,.dialog-footer,.source-link{display:flex;align-items:center}.page-header,.toolbar,.table-heading,.table-footer,.dialog-intro,.dialog-footer{justify-content:space-between;gap:16px}.page-header>div{display:grid;gap:3px}.page-header p{margin:0;color:#76859b;font-size:11px}.page-header h1{margin:0;color:#172033;font-size:28px;letter-spacing:-.02em}.page-header span{color:#7e8ba0;font-size:12px}.page-header :deep(.el-button){min-height:38px;border-radius:9px}
.content-overview,.toolbar,.content-table{border:1px solid #dfe6f0;border-radius:16px;background:#fff;box-shadow:0 9px 28px rgba(31,45,75,.045)}.content-overview{gap:18px;padding:18px 22px}.overview-primary{min-width:0;flex:1;gap:12px}.overview-primary>div{display:grid;min-width:0;gap:2px}.overview-icon{display:grid;width:42px;height:42px;flex:0 0 auto;place-items:center;border-radius:11px;color:#2563eb;background:#eaf1ff;font-size:20px}.overview-primary small,.overview-metric small{color:#8290a4;font-size:10px}.overview-primary strong{color:#2a374d;font-size:17px}.overview-primary p{overflow:hidden;margin:0;color:#8b97a9;font-size:10px;text-overflow:ellipsis;white-space:nowrap}.overview-metric{display:grid;min-width:120px;padding-left:20px;gap:2px;border-left:1px solid #e8edf3}.overview-metric strong{color:#2f3e57;font-size:22px}.overview-metric span{color:#98a3b4;font-size:10px}
.toolbar{padding:12px 15px}.view-switch{display:flex;padding:3px;border-radius:10px;background:#f0f3f8}.view-switch button{min-width:88px;padding:8px 14px;border:0;border-radius:8px;color:#6f7d91;background:transparent;cursor:pointer;font-size:12px}.view-switch button.active{color:#1f56c5;background:#fff;box-shadow:0 2px 8px rgba(31,45,75,.09);font-weight:650}.filter-controls{min-width:0;justify-content:flex-end;gap:9px}.filter-label{display:flex;align-items:center;gap:5px;color:#8995a7;font-size:11px}.filter-controls :deep(.el-select){width:180px}.keyword-input{width:min(300px,26vw)}.filter-controls :deep(.el-button){min-height:34px;border-radius:8px}
.content-table{min-width:0;overflow:hidden}.table-heading{min-height:66px;padding:0 18px;border-bottom:1px solid #edf0f5}.table-heading>div{display:grid;gap:2px}.table-heading small,.table-heading>span{color:#8290a4;font-size:10px}.table-heading h2{margin:0;color:#29364c;font-size:15px}.content-table :deep(.el-table th.el-table__cell){height:46px;color:#6b788c;background:#f8f9fb;font-size:11px}.content-table :deep(.el-table td.el-table__cell){height:66px;color:#344057}.content-table :deep(.el-table__inner-wrapper::before){display:none}.content-title,.classification,.date-cell{display:grid;min-width:0;gap:4px}.content-title strong,.content-title small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.content-title strong{color:#344057;font-size:12px}.content-title small,.classification small,.date-cell small{color:#96a1b2;font-size:10px}.classification span,.date-cell strong{color:#46536a;font-size:11px}.status-pill,.pin-pill{display:inline-flex;align-items:center;gap:5px;padding:5px 8px;border-radius:999px;font-size:10px;font-weight:650}.status-pill i{width:6px;height:6px;border-radius:50%;background:currentColor}.status-pill.is-pending_review{color:#a46f1d;background:#fff6e5}.status-pill.is-published{color:#168653;background:#e9f8f0}.status-pill.is-rejected{color:#b94a48;background:#fff0ef}.status-pill.is-offline{color:#778397;background:#f0f3f6}.pin-pill{color:#a76a12;background:#fff3dd}.muted{color:#a0a9b7;font-size:10px}.source-link{display:inline-flex;gap:5px;color:#3b68bd;text-decoration:none;font-size:11px}.source-link:hover{text-decoration:underline}.table-footer{min-height:62px;padding:0 18px;border-top:1px solid #edf0f5}.table-footer>span{color:#97a2b3;font-size:10px}
.dialog-intro{align-items:flex-start;padding:0 2px 14px;border-bottom:1px solid #e8edf3}.dialog-intro>div{display:grid;gap:3px}.dialog-intro small{color:#2563eb;font-size:10px;font-weight:650}.dialog-intro h3{margin:0;color:#29364c;font-size:19px}.dialog-intro p{margin:0;color:#8290a4;font-size:11px}.meta{flex-wrap:wrap;margin:14px 0;color:#7d899b;font-size:11px}.meta a{display:flex;align-items:center;gap:4px;color:#2563eb;text-decoration:none}.comparison{display:grid;grid-template-columns:1fr 1fr;gap:14px}.comparison section{min-width:0;padding:17px;border:1px solid #dfe6f0;border-radius:12px}.section-label{margin-bottom:14px}.section-label small{color:#8290a4;font-size:10px}.section-label h3{margin:2px 0 0;color:#344057;font-size:14px}.original-content{background:#f7f9fc}.original-content h4{margin:0 0 10px;color:#354259;font-size:14px;line-height:1.6}.original-content p{margin:0;color:#69778c;font-size:12px;line-height:1.75;white-space:pre-wrap}.editable-content{display:grid;gap:8px}.editable-content label,.form-row label{display:grid;gap:6px;color:#667389;font-size:11px}.form-row{margin-top:14px;gap:12px}.form-row label{min-width:190px}.form-row :deep(.el-select){width:100%}.dialog-footer>span{color:#8b97a9;font-size:10px}.dialog-footer>div{display:flex;gap:8px}
:global(.content-review-dialog.el-dialog){overflow:hidden;border-radius:18px;box-shadow:0 24px 70px rgba(15,23,42,.18)}:global(.content-review-dialog .el-dialog__header){padding:17px 22px 10px}:global(.content-review-dialog .el-dialog__body){max-height:calc(90vh - 130px);overflow-y:auto;padding:8px 22px 18px}:global(.content-review-dialog .el-dialog__footer){padding:14px 22px;border-top:1px solid #e8edf3;background:#fafbfc}
@media(max-width:1050px){.content-overview{flex-wrap:wrap}.overview-primary{flex-basis:100%}.overview-metric{min-width:calc(50% - 9px);padding:10px 0 0;border-top:1px solid #e8edf3;border-left:0}.toolbar{align-items:flex-start;flex-direction:column}.filter-controls{width:100%;justify-content:flex-start;flex-wrap:wrap}.keyword-input{flex:1;width:auto}}
@media(max-width:700px){.portal-admin-page{padding:20px 14px 36px}.page-header{align-items:flex-start;flex-direction:column}.page-header :deep(.el-button){width:100%}.toolbar{align-items:stretch}.view-switch button{flex:1}.filter-label{display:none}.filter-controls{align-items:stretch;flex-direction:column}.filter-controls :deep(.el-select),.keyword-input,.filter-controls :deep(.el-button){width:100%}.comparison{grid-template-columns:1fr}.form-row,.dialog-footer{align-items:stretch;flex-direction:column}.form-row label{min-width:0}.dialog-footer>div{display:grid;grid-template-columns:1fr 1fr}.table-footer{align-items:flex-start;flex-direction:column;padding:12px 14px;gap:8px}.content-table :deep(.el-pagination){transform:scale(.88);transform-origin:left top}}
</style>
