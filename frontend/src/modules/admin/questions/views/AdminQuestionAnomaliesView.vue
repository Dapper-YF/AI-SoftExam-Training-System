<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { CircleCheck, DocumentChecked, Refresh, WarningFilled } from '@element-plus/icons-vue'
import { getAdminQuestionGateway } from '../api/gateway'
import type { AdminQuestionAnomaly, AdminQuestionAnomalyStatus, AdminQuestionAnomalyType } from '../types'

const router = useRouter()
const gateway = getAdminQuestionGateway()
const rows = ref<AdminQuestionAnomaly[]>([])
const loading = ref(false)
const resolvingId = ref<number | null>(null)
const error = ref('')
const type = ref<AdminQuestionAnomalyType | 'ALL'>('ALL')
const status = ref<AdminQuestionAnomalyStatus | 'ALL'>('OPEN')

const labels: Record<string, string> = {
  MISSING_QUESTION_NO: '题号缺失', INCOMPLETE_STEM: '题目不完整', INCOMPLETE_OPTION: '选项不完整', MISSING_IMAGE: '图片缺失',
  OPEN: '待处理', IN_PROGRESS: '处理中', FIXED: '已修复', IGNORED: '已忽略', FALSE_POSITIVE: '误报',
}
const anomalyTypes: AdminQuestionAnomalyType[] = ['MISSING_QUESTION_NO', 'INCOMPLETE_STEM', 'INCOMPLETE_OPTION', 'MISSING_IMAGE']
const statuses: Array<AdminQuestionAnomalyStatus | 'ALL'> = ['ALL', 'OPEN', 'IN_PROGRESS', 'FIXED', 'IGNORED', 'FALSE_POSITIVE']
const openCount = computed(() => rows.value.filter(row => row.status === 'OPEN').length)
const progressCount = computed(() => rows.value.filter(row => row.status === 'IN_PROGRESS').length)
const fixedCount = computed(() => rows.value.filter(row => row.status === 'FIXED').length)

function statusLabel(value: string): string { return labels[value] ?? value }
function typeLabel(value: string): string { return labels[value] ?? value }
function formatDate(value?: string): string {
  if (!value) return '未提供更新时间'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}
function messageOf(reason: unknown, fallback: string): string { return reason instanceof Error && reason.message ? reason.message : fallback }
async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    if (!gateway.listAnomalies) throw new Error('异常题功能暂不可用')
    rows.value = await gateway.listAnomalies({ anomalyType: type.value, status: status.value })
  } catch (reason) {
    rows.value = []
    error.value = messageOf(reason, '异常题加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}
async function resolve(row: AdminQuestionAnomaly, next: 'IGNORED' | 'FALSE_POSITIVE'): Promise<void> {
  const note = await ElMessageBox.prompt('请填写处理说明，说明会写入异常题处理记录。', next === 'IGNORED' ? '忽略异常题' : '标记为误报', { inputPattern: /\S+/, inputErrorMessage: '处理说明不能为空', confirmButtonText: '确认提交', cancelButtonText: '取消' }).then(result => result.value).catch(() => null)
  if (note == null || !gateway.resolveAnomaly) return
  resolvingId.value = row.anomalyId
  try {
    await gateway.resolveAnomaly(row.anomalyId, next, note)
    ElMessage.success('处理结果已保存')
    await load()
  } catch (reason) {
    ElMessage.error(messageOf(reason, '处理失败，请稍后重试'))
  } finally {
    resolvingId.value = null
  }
}
function openQuestion(row: AdminQuestionAnomaly): void { void router.push(`/admin/questions/${row.questionId}/versions/${row.questionVersionId}`) }
function changeFilters(): void { void load() }

onMounted(() => void load())
</script>

<template>
  <section class="anomaly-page">
    <header class="page-header">
      <div><p>题库管理 / 质量巡检</p><h1>异常题处理</h1><span>定位导入与编辑过程中识别出的异常，修订后进入题目审核流程。</span></div>
      <div class="header-actions"><el-button :icon="Refresh" :loading="loading" @click="load">刷新列表</el-button><el-button @click="router.push('/admin/questions')">返回题库</el-button></div>
    </header>

    <section class="quality-overview">
      <article><span class="metric-icon is-amber"><el-icon><WarningFilled /></el-icon></span><div><small>当前列表</small><strong>{{ rows.length }}</strong><p>条异常记录</p></div></article>
      <article><span class="metric-icon is-blue"><el-icon><DocumentChecked /></el-icon></span><div><small>待处理</small><strong>{{ openCount }}</strong><p>需要进入题目修订</p></div></article>
      <article><span class="metric-icon is-green"><el-icon><CircleCheck /></el-icon></span><div><small>已修复</small><strong>{{ fixedCount }}</strong><p>本次筛选结果</p></div></article>
      <article><span class="metric-icon is-purple"><el-icon><DocumentChecked /></el-icon></span><div><small>处理中</small><strong>{{ progressCount }}</strong><p>仍待完成核验</p></div></article>
    </section>

    <section class="filter-panel">
      <div class="filter-heading"><span class="filter-icon"><el-icon><WarningFilled /></el-icon></span><div><strong>异常筛选</strong><small>按异常类型或处理状态聚焦待办任务</small></div></div>
      <div class="filter-controls">
        <el-select v-model="type" @change="changeFilters"><el-option label="全部异常类型" value="ALL" /><el-option v-for="key in anomalyTypes" :key="key" :label="labels[key]" :value="key" /></el-select>
        <el-select v-model="status" @change="changeFilters"><el-option v-for="key in statuses" :key="key" :label="key === 'ALL' ? '全部处理状态' : labels[key]" :value="key" /></el-select>
        <el-button type="primary" @click="load">查询异常</el-button>
      </div>
    </section>

    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />

    <section class="anomaly-table-panel">
      <header class="panel-heading"><div><small>异常记录</small><h2>质量巡检结果</h2></div><span>共 {{ rows.length }} 条</span></header>
      <el-table v-loading="loading" :data="rows" empty-text="暂无符合条件的异常题">
        <el-table-column label="题目" min-width="340"><template #default="scope"><button class="question-link" type="button" @click="openQuestion(scope.row)"><strong>{{ scope.row.title || '未命名题目' }}</strong><small>题目 ID {{ scope.row.questionId }} · 版本 {{ scope.row.questionVersionId }}{{ scope.row.originalQuestionNo ? ` · 原题号 ${scope.row.originalQuestionNo}` : '' }}</small></button></template></el-table-column>
        <el-table-column label="异常类型" width="145"><template #default="scope"><span class="type-pill" :class="`is-${scope.row.anomalyType.toLowerCase()}`">{{ typeLabel(scope.row.anomalyType) }}</span></template></el-table-column>
        <el-table-column label="处理状态" width="115"><template #default="scope"><span class="status-pill" :class="`is-${scope.row.status.toLowerCase()}`"><i></i>{{ statusLabel(scope.row.status) }}</span></template></el-table-column>
        <el-table-column label="最近更新" min-width="160"><template #default="scope"><span class="date-cell">{{ formatDate(scope.row.updatedAt) }}</span></template></el-table-column>
        <el-table-column label="操作" width="260" fixed="right" align="right"><template #default="scope"><el-button link type="primary" @click="openQuestion(scope.row)">进入修复</el-button><el-button link :loading="resolvingId === scope.row.anomalyId" @click="resolve(scope.row, 'IGNORED')">忽略</el-button><el-button link :loading="resolvingId === scope.row.anomalyId" @click="resolve(scope.row, 'FALSE_POSITIVE')">标记误报</el-button></template></el-table-column>
      </el-table>
    </section>
  </section>
</template>

<style scoped>
.anomaly-page{display:grid;gap:18px;min-width:0;min-height:calc(100vh - var(--platform-header-height));padding:28px clamp(20px,3vw,46px) 48px;color:#172033;background:#f5f7fb}.page-header,.header-actions,.quality-overview,.quality-overview article,.filter-panel,.filter-heading,.filter-controls,.panel-heading{display:flex;align-items:center}.page-header,.filter-panel,.panel-heading{justify-content:space-between;gap:16px}.page-header>div:first-child{display:grid;gap:3px}.page-header p{margin:0;color:#76859b;font-size:11px}.page-header h1{margin:0;font-size:28px;letter-spacing:-.02em}.page-header span{color:#7e8ba0;font-size:12px}.header-actions{gap:10px}.header-actions :deep(.el-button){min-height:38px;border-radius:9px}
.quality-overview{display:grid;grid-template-columns:repeat(4,1fr);gap:12px}.quality-overview article,.filter-panel,.anomaly-table-panel{border:1px solid #dfe6f0;border-radius:16px;background:#fff;box-shadow:0 9px 28px rgba(31,45,75,.045)}.quality-overview article{gap:12px;padding:17px 18px}.metric-icon{display:grid;width:40px;height:40px;flex:0 0 auto;place-items:center;border-radius:11px}.metric-icon.is-amber{color:#a56b12;background:#fff3dd}.metric-icon.is-blue{color:#2563eb;background:#eaf1ff}.metric-icon.is-green{color:#159166;background:#e8f8f1}.metric-icon.is-purple{color:#7354d8;background:#f0edff}.quality-overview article>div{display:grid;gap:2px}.quality-overview small,.filter-heading small,.panel-heading small{color:#8290a4;font-size:10px}.quality-overview strong{color:#2d3b53;font-size:21px}.quality-overview p{margin:0;color:#96a1b1;font-size:10px}
.filter-panel{padding:14px 18px}.filter-heading{gap:10px}.filter-heading>div{display:grid;gap:2px}.filter-heading strong{color:#344057;font-size:13px}.filter-icon{display:grid;width:34px;height:34px;place-items:center;border-radius:10px;color:#a56b12;background:#fff3dd}.filter-controls{gap:10px}.filter-controls :deep(.el-select){width:180px}.filter-controls :deep(.el-button){min-height:35px;border-radius:8px}
.anomaly-table-panel{min-width:0;overflow:hidden}.panel-heading{min-height:66px;padding:0 18px;border-bottom:1px solid #edf0f5}.panel-heading>div{display:grid;gap:2px}.panel-heading h2{margin:0;color:#29364c;font-size:15px}.panel-heading>span{color:#8290a4;font-size:10px}.anomaly-table-panel :deep(.el-table th.el-table__cell){height:46px;color:#6b788c;background:#f8f9fb;font-size:11px}.anomaly-table-panel :deep(.el-table td.el-table__cell){height:66px;color:#344057}.anomaly-table-panel :deep(.el-table__inner-wrapper::before){display:none}.question-link{display:grid;min-width:0;gap:4px;padding:0;border:0;color:inherit;background:transparent;cursor:pointer;text-align:left}.question-link strong,.question-link small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.question-link strong{color:#344057;font-size:12px}.question-link:hover strong{color:#2563eb}.question-link small,.date-cell{color:#96a1b2;font-size:10px}.type-pill,.status-pill{display:inline-flex;align-items:center;gap:5px;padding:5px 8px;border-radius:999px;font-size:10px;font-weight:650}.type-pill.is-missing_question_no{color:#7653d0;background:#f0edff}.type-pill.is-incomplete_stem,.type-pill.is-incomplete_option{color:#a56b12;background:#fff3dd}.type-pill.is-missing_image{color:#197aa2;background:#e7f6fb}.status-pill i{width:6px;height:6px;border-radius:50%;background:currentColor}.status-pill.is-open{color:#a46f1d;background:#fff6e5}.status-pill.is-in_progress{color:#2563eb;background:#eaf1ff}.status-pill.is-fixed{color:#168653;background:#e9f8f0}.status-pill.is-ignored,.status-pill.is-false_positive{color:#7b8798;background:#f0f3f6}
@media(max-width:1050px){.quality-overview{grid-template-columns:repeat(2,1fr)}.filter-panel{align-items:flex-start;flex-direction:column}.filter-controls{width:100%;flex-wrap:wrap}.filter-controls :deep(.el-select){flex:1;width:auto}.anomaly-table-panel :deep(.el-table){max-height:520px}}
@media(max-width:700px){.anomaly-page{padding:20px 14px 36px}.page-header{align-items:flex-start;flex-direction:column}.header-actions{width:100%}.header-actions :deep(.el-button){flex:1}.quality-overview{grid-template-columns:1fr}.filter-controls{align-items:stretch;flex-direction:column}.filter-controls :deep(.el-select),.filter-controls :deep(.el-button){width:100%}}
</style>
