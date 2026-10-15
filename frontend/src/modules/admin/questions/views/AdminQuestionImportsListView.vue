<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowLeft,
  ArrowRight,
  Check,
  CircleCheck,
  Close,
  Document,
  Files,
  Refresh,
  UploadFilled,
  Warning,
} from '@element-plus/icons-vue'
import { getAdminQuestionGateway } from '../api/gateway'
import { resolveAdminQuestionError, resolveImportItemError } from '../model/error-messages'
import { validateQuestionImportFile } from '../model/import-validation'
import type { AdminImportStatus, AdminQuestionImportBatchSummary, AdminQuestionImportUploadResult } from '../types'

const router = useRouter()
const gateway = getAdminQuestionGateway()
const batches = ref<AdminQuestionImportBatchSummary[]>([])
const total = ref(0)
const loading = ref(false)
const submitting = ref(false)
const errorMessage = ref('')
const fileError = ref('')
const selectedFile = ref<File | null>(null)
const preflight = ref<AdminQuestionImportUploadResult | null>(null)
const fileInput = ref<HTMLInputElement>()
const dragging = ref(false)
const query = reactive<{ pageNo: number, pageSize: number, status: AdminImportStatus | 'ALL' }>({ pageNo: 1, pageSize: 20, status: 'ALL' })
const mutationId = () => globalThis.crypto?.randomUUID?.() ?? `import-${Date.now()}-${Math.random().toString(16).slice(2)}`
let preflightMutationId = mutationId()
const importStatusLabels: Record<AdminImportStatus, string> = {
  PREFLIGHT_FAILED: '预检失败',
  READY_TO_CONFIRM: '待确认',
  CONFIRMED: '已确认',
  PENDING_REVIEW: '待审核',
  PUBLISHED: '已发布',
  REJECTED: '已驳回',
  COMPLETED: '已完成',
  FAILED: '处理失败',
  ROLLED_BACK: '已回滚',
}

const currentStep = computed(() => preflight.value?.status === 'READY_TO_CONFIRM' ? 3 : selectedFile.value ? 2 : 1)
const selectedFileSize = computed(() => {
  if (!selectedFile.value) return ''
  const bytes = selectedFile.value.size
  return bytes >= 1024 * 1024
    ? `${(bytes / (1024 * 1024)).toFixed(1)} MB`
    : `${Math.max(1, Math.round(bytes / 1024))} KB`
})

async function loadBatches(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const page = await gateway.listImportBatches(query)
    batches.value = page.items
    total.value = page.total
  } catch (error) {
    errorMessage.value = resolveAdminQuestionError(error, '导入批次加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

async function acceptFile(file?: File): Promise<void> {
  selectedFile.value = null
  preflight.value = null
  fileError.value = ''
  if (!file) return
  const validation = await validateQuestionImportFile(file)
  if (validation) {
    fileError.value = validation
    return
  }
  selectedFile.value = file
  preflightMutationId = mutationId()
}

async function selectFile(event: Event): Promise<void> {
  await acceptFile((event.target as HTMLInputElement).files?.[0])
}

async function handleDrop(event: DragEvent): Promise<void> {
  dragging.value = false
  await acceptFile(event.dataTransfer?.files?.[0])
}

function clearFile(): void {
  selectedFile.value = null
  preflight.value = null
  fileError.value = ''
  if (fileInput.value) fileInput.value.value = ''
}

async function runPreflight(): Promise<void> {
  if (!selectedFile.value) return
  submitting.value = true
  fileError.value = ''
  try {
    preflight.value = await gateway.preflightImport({
      schemaVersion: 'question-import/1.0',
      clientMutationId: preflightMutationId,
      file: selectedFile.value,
    })
  } catch (error) {
    fileError.value = resolveAdminQuestionError(error, '文件预检失败，请检查模板内容')
  } finally {
    submitting.value = false
  }
}

async function confirm(): Promise<void> {
  if (!preflight.value || preflight.value.status !== 'READY_TO_CONFIRM') return
  submitting.value = true
  try {
    const result = await gateway.confirmImport({
      token: preflight.value.token,
      baseVersion: null,
      clientMutationId: mutationId(),
    })
    if (result.batchId) await router.push(`/admin/question-imports/${result.batchId}`)
  } catch (error) {
    fileError.value = resolveAdminQuestionError(error, '确认导入失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}

onMounted(() => { void loadBatches() })
</script>

<template>
  <section class="page" data-testid="question-imports-page">
    <header class="page-header">
      <div>
        <p>题库管理 <span>/</span> 批量导入</p>
        <h1>批量导入题目</h1>
        <small>使用标准模板批量创建题目草稿，并在入库前完成数据预检</small>
      </div>
      <el-button class="back-button" @click="router.push('/admin/questions')"><el-icon><ArrowLeft /></el-icon>返回题库</el-button>
    </header>

    <section class="import-panel">
      <header class="panel-heading">
        <div><span class="heading-icon"><el-icon><UploadFilled /></el-icon></span><div class="heading-copy"><small>新建导入</small><h2>上传结构化题目文件</h2></div></div>
        <span class="template-note"><el-icon><Document /></el-icon>支持 JSON / Excel 标准模板</span>
      </header>

      <div class="import-steps" aria-label="导入流程">
        <div v-for="(label, index) in ['选择文件', '检查内容', '确认入库']" :key="label" :class="{ 'is-active': currentStep >= index + 1, 'is-current': currentStep === index + 1 }">
          <span><el-icon v-if="currentStep > index + 1"><Check /></el-icon><template v-else>{{ index + 1 }}</template></span>
          <strong>{{ label }}</strong>
          <i v-if="index < 2"></i>
        </div>
      </div>

      <div class="upload-workspace">
        <input ref="fileInput" class="file-input" type="file" accept=".json,.xlsx,application/json,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" @change="selectFile" />
        <div
          v-if="!selectedFile"
          class="drop-zone"
          :class="{ 'is-dragging': dragging }"
          role="button"
          tabindex="0"
          @click="fileInput?.click()"
          @keydown.enter="fileInput?.click()"
          @dragenter.prevent="dragging = true"
          @dragover.prevent="dragging = true"
          @dragleave.prevent="dragging = false"
          @drop.prevent="handleDrop"
        >
          <span class="upload-icon"><el-icon><UploadFilled /></el-icon></span>
          <strong>拖拽题目文件到这里</strong>
          <p>或点击选择本地文件，单个文件需符合 question-import/1.0 模板规范</p>
          <button type="button">选择文件</button>
        </div>

        <div v-else class="selected-file">
          <span class="file-icon"><el-icon><Files /></el-icon></span>
          <div><strong>{{ selectedFile.name }}</strong><small>{{ selectedFileSize }} · 等待内容检查</small></div>
          <span class="file-ready"><el-icon><CircleCheck /></el-icon>格式有效</span>
          <button type="button" aria-label="移除文件" @click="clearFile"><el-icon><Close /></el-icon></button>
        </div>

        <aside class="upload-guidance">
          <span><el-icon><Warning /></el-icon></span>
          <p><strong>导入前请确认</strong><small>该入口用于 JSON 或 Excel 结构化题目。Word 真题卷请使用“导入一整套试卷”功能。</small></p>
        </aside>
      </div>

      <el-alert v-if="fileError" :title="fileError" type="error" :closable="false" show-icon />

      <div class="actions">
        <span>{{ preflight ? '文件检查已完成，请确认可导入数据' : '文件不会在确认入库前创建题目' }}</span>
        <el-button :disabled="!selectedFile" :loading="submitting" @click="runPreflight"><el-icon><Refresh /></el-icon>{{ preflight ? '重新检查' : '检查文件' }}</el-button>
        <el-button type="primary" :disabled="preflight?.status !== 'READY_TO_CONFIRM'" :loading="submitting" @click="confirm">
          确认导入为草稿<el-icon><ArrowRight /></el-icon>
        </el-button>
      </div>

      <section v-if="preflight" class="result">
        <header>
          <div><span class="result-icon"><el-icon><CircleCheck /></el-icon></span><p><strong>文件检查结果</strong><small>系统已完成结构和字段校验</small></p></div>
          <div class="result-metrics">
            <span><strong>{{ preflight.totalCount }}</strong><small>文件题目</small></span>
            <span class="is-success"><strong>{{ preflight.eligibleCount }}</strong><small>可导入</small></span>
            <span :class="{ 'is-warning': preflight.ineligibleCount }"><strong>{{ preflight.ineligibleCount }}</strong><small>需处理</small></span>
          </div>
        </header>
        <el-table :data="preflight.items" max-height="320">
          <el-table-column prop="lineNo" label="行号" width="80" />
          <el-table-column prop="externalKey" label="题目标识" min-width="180" />
          <el-table-column label="检查结果" min-width="240">
            <template #default="scope"><span class="result-tag" :class="{ 'is-error': !scope.row.eligible }">{{ scope.row.eligible ? '可导入' : resolveImportItemError(scope.row.errorCode) }}</span></template>
          </el-table-column>
        </el-table>
      </section>
    </section>

    <section class="history-panel">
      <header class="panel-heading">
        <div><span class="heading-icon heading-icon--slate"><el-icon><Files /></el-icon></span><div class="heading-copy"><small>历史记录</small><h2>导入批次</h2></div></div>
        <el-select v-model="query.status" class="status-filter" @change="loadBatches">
          <el-option label="全部状态" value="ALL" />
          <el-option label="已确认" value="CONFIRMED" />
          <el-option label="已回滚" value="ROLLED_BACK" />
        </el-select>
      </header>
      <el-alert v-if="errorMessage" :title="errorMessage" type="error" :closable="false" show-icon />
      <el-table v-loading="loading" :data="batches" empty-text="暂无导入记录">
        <el-table-column prop="batchId" label="批次" width="100"><template #default="scope"><strong class="batch-id">#{{ scope.row.batchId }}</strong></template></el-table-column>
        <el-table-column prop="status" label="状态" width="130"><template #default="scope"><span class="batch-status" :class="`status-${scope.row.status.toLowerCase()}`"><i></i>{{ importStatusLabels[scope.row.status as AdminImportStatus] ?? '未知状态' }}</span></template></el-table-column>
        <el-table-column prop="totalCount" label="题目数" width="100" />
        <el-table-column prop="importedCount" label="已导入" width="100" />
        <el-table-column prop="createdAt" label="创建时间" min-width="180" />
        <el-table-column label="操作" width="100" align="right">
          <template #default="scope"><el-button link type="primary" @click="router.push(`/admin/question-imports/${scope.row.batchId}`)">查看详情<el-icon><ArrowRight /></el-icon></el-button></template>
        </el-table-column>
      </el-table>
      <footer><span>共 {{ total }} 个导入批次</span></footer>
    </section>
  </section>
</template>

<style scoped>
.page {
  display: grid;
  min-height: calc(100vh - var(--platform-header-height));
  padding: 28px var(--platform-page-padding) 50px;
  gap: 18px;
  background:
    radial-gradient(circle at 90% 1%, rgb(37 99 235 / 5%), transparent 28rem),
    var(--color-page-canvas);
  color: #172033;
}
.page-header, .import-panel, .history-panel { width: min(1320px, 100%); margin-right: auto; margin-left: auto; box-sizing: border-box; }
.page-header, .panel-heading, .actions, .result > header { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.page-header { align-items: flex-end; }
.page-header p { margin: 0; color: #2563eb; font-size: 11px; font-weight: 700; letter-spacing: .08em; }
.page-header p span { padding: 0 4px; color: #cbd5e1; }
.page-header h1 { margin: 6px 0 0; color: #172033; font-size: 29px; letter-spacing: -.025em; }
.page-header small { display: block; margin-top: 7px; color: #64748b; font-size: 11px; }
.back-button { min-height: 40px; border-color: #dbe4f0; border-radius: 10px; color: #475569; }
.import-panel, .history-panel { padding: 23px 25px; border: 1px solid #dce5f0; border-radius: 16px; background: white; box-shadow: 0 10px 28px rgb(15 23 42 / 4%); }
.panel-heading { margin-bottom: 20px; }
.panel-heading > div, .result > header > div:first-child { display: flex; align-items: center; gap: 11px; }
.heading-copy, .result header p { display: grid; margin: 0; gap: 3px; }
.panel-heading small, .result header p small { color: #94a3b8; font-size: 9px; }
.panel-heading h2 { margin: 0; color: #1e293b; font-size: 15px; }
.heading-icon, .result-icon { display: grid; width: 38px; height: 38px; flex: 0 0 auto; place-items: center; border-radius: 11px; background: #eff6ff; color: #2563eb; font-size: 17px; }
.heading-icon--slate { background: #f1f5f9; color: #64748b; }
.template-note { display: inline-flex; padding: 6px 9px; align-items: center; gap: 5px; border-radius: 7px; background: #f8fafc; color: #64748b; font-size: 9px; }
.import-steps { display: grid; margin-bottom: 18px; padding: 13px 16px; grid-template-columns: repeat(3, 1fr); border: 1px solid #e7edf5; border-radius: 11px; background: #f8fafc; }
.import-steps > div { position: relative; display: flex; align-items: center; gap: 7px; color: #94a3b8; }
.import-steps > div:nth-child(2) { justify-content: center; }
.import-steps > div:nth-child(3) { justify-content: flex-end; }
.import-steps span { display: grid; width: 24px; height: 24px; place-items: center; border-radius: 50%; background: #e7edf4; font-size: 9px; }
.import-steps strong { font-size: 9px; }
.import-steps i { position: absolute; right: 8px; width: calc(50% - 44px); height: 1px; background: #dbe4f0; }
.import-steps > div:nth-child(2) i { right: -8px; }
.import-steps .is-active { color: #2563eb; }
.import-steps .is-active span { background: #2563eb; color: white; }
.import-steps .is-active i { background: #93b4f5; }
.upload-workspace { position: relative; }
.file-input { position: absolute; width: 1px; height: 1px; overflow: hidden; opacity: 0; pointer-events: none; }
.drop-zone { display: grid; min-height: 225px; padding: 28px; place-content: center; justify-items: center; border: 1px dashed #b9c9dc; border-radius: 14px; background: #fbfdff; cursor: pointer; text-align: center; transition: .18s ease; }
.drop-zone:hover, .drop-zone.is-dragging { border-color: #2563eb; background: #f4f8ff; box-shadow: 0 0 0 4px rgb(37 99 235 / 5%); }
.upload-icon { display: grid; width: 56px; height: 56px; margin-bottom: 13px; place-items: center; border-radius: 17px; background: #e8f1ff; color: #2563eb; font-size: 25px; }
.drop-zone strong { color: #334155; font-size: 14px; }
.drop-zone p { margin: 7px 0 14px; color: #94a3b8; font-size: 10px; }
.drop-zone button { min-height: 34px; padding: 0 13px; border: 1px solid #bfdbfe; border-radius: 8px; background: white; color: #2563eb; cursor: pointer; font-size: 10px; font-weight: 650; }
.selected-file { display: flex; min-height: 96px; padding: 16px 18px; align-items: center; gap: 13px; border: 1px solid #bfdbfe; border-radius: 13px; background: #f7faff; }
.file-icon { display: grid; width: 46px; height: 46px; flex: 0 0 auto; place-items: center; border-radius: 13px; background: #e8f1ff; color: #2563eb; font-size: 21px; }
.selected-file > div { display: grid; min-width: 0; flex: 1; gap: 4px; }
.selected-file strong { overflow: hidden; color: #334155; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.selected-file small { color: #94a3b8; font-size: 9px; }
.file-ready { display: inline-flex; padding: 5px 8px; align-items: center; gap: 4px; border-radius: 999px; background: #ecfdf5; color: #047857; font-size: 9px; }
.selected-file > button { display: grid; width: 30px; height: 30px; place-items: center; border: 0; border-radius: 8px; background: transparent; color: #94a3b8; cursor: pointer; }
.selected-file > button:hover { background: #fee2e2; color: #dc2626; }
.upload-guidance { display: flex; margin-top: 12px; padding: 10px 12px; align-items: flex-start; gap: 9px; border-radius: 9px; background: #fffaf0; color: #a16207; }
.upload-guidance > span { padding-top: 1px; }
.upload-guidance p { display: grid; margin: 0; gap: 3px; }
.upload-guidance strong { font-size: 9px; }
.upload-guidance small { color: #a68654; font-size: 8px; line-height: 1.5; }
.import-panel > .el-alert { margin-top: 13px; border-radius: 9px; }
.actions { margin-top: 16px; padding-top: 16px; justify-content: flex-end; border-top: 1px solid #edf1f6; }
.actions > span { margin-right: auto; color: #94a3b8; font-size: 9px; }
.actions .el-button { min-height: 38px; margin: 0; border-radius: 9px; }
.result { display: grid; margin-top: 18px; padding-top: 19px; gap: 14px; border-top: 1px solid #e7edf5; }
.result-icon { background: #ecfdf5; color: #059669; }
.result header p strong { color: #334155; font-size: 12px; }
.result-metrics { display: flex; gap: 8px; }
.result-metrics > span { display: grid; min-width: 72px; padding: 8px 10px; gap: 2px; border-radius: 8px; background: #f8fafc; text-align: center; }
.result-metrics strong { color: #334155; font-size: 15px; }
.result-metrics small { color: #94a3b8; font-size: 8px; }
.result-metrics .is-success strong { color: #059669; }
.result-metrics .is-warning strong { color: #d97706; }
.result :deep(.el-table), .history-panel :deep(.el-table) { --el-table-border-color: #edf1f6; --el-table-header-bg-color: #f8fafc; --el-table-row-hover-bg-color: #f7faff; border-radius: 9px; color: #475569; font-size: 10px; }
.result :deep(.el-table th.el-table__cell), .history-panel :deep(.el-table th.el-table__cell) { color: #64748b; font-size: 9px; }
.result-tag { padding: 4px 7px; border-radius: 6px; background: #ecfdf5; color: #047857; font-size: 8px; }
.result-tag.is-error { background: #fef2f2; color: #dc2626; }
.status-filter { width: 145px; }
.status-filter :deep(.el-select__wrapper) { min-height: 36px; border-radius: 9px; box-shadow: 0 0 0 1px #dbe4f0 inset; }
.history-panel > .el-alert { margin-bottom: 12px; }
.batch-id { color: #1e3a8a; font-size: 10px; }
.batch-status { display: inline-flex; padding: 5px 7px; align-items: center; gap: 5px; border-radius: 999px; background: #ecfdf5; color: #047857; font-size: 8px; }
.batch-status i { width: 5px; height: 5px; border-radius: 50%; background: currentColor; }
.batch-status.status-rolled_back { background: #f1f5f9; color: #64748b; }
.history-panel footer { padding-top: 13px; color: #94a3b8; font-size: 9px; text-align: right; }
@media (max-width: 760px) {
  .page { padding: 22px 14px 38px; }
  .page-header { align-items: flex-start; flex-direction: column; }
  .import-panel, .history-panel { padding: 19px 15px; }
  .template-note { display: none; }
  .actions { align-items: stretch; flex-direction: column; }
  .actions > span { margin: 0; }
  .actions .el-button { width: 100%; }
  .result > header { align-items: flex-start; flex-direction: column; }
}
@media (max-width: 500px) {
  .page-header h1 { font-size: 25px; }
  .import-steps { padding: 11px 9px; }
  .import-steps i { display: none; }
  .drop-zone { min-height: 210px; padding: 22px 12px; }
  .file-ready { display: none; }
  .result-metrics { width: 100%; }
  .result-metrics > span { min-width: 0; flex: 1; }
}
</style>
