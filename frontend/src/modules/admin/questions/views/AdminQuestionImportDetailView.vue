<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowLeft,
  ArrowRight,
  CircleCheck,
  CloseBold,
  Download,
  Files,
  Refresh,
  RefreshLeft,
  Warning,
} from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { getAdminQuestionGateway } from '../api/gateway'
import { resolveAdminQuestionError, resolveImportItemError } from '../model/error-messages'
import type { AdminQuestionImportBatchDetail } from '../types'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const gateway = getAdminQuestionGateway()
const detail = ref<AdminQuestionImportBatchDetail | null>(null)
const loading = ref(false)
const actionLoading = ref(false)
const errorMessage = ref('')
const rollbackMutationId = ref(createMutationId())
const publishMutationId = ref(createMutationId())
const batchId = computed(() => Number(route.params.batchId))
const failedItems = computed(() => detail.value?.items.filter((item) => !item.eligible || item.errorCode) ?? [])
const duplicateItems = computed(() => failedItems.value.filter((item) => item.errorCode?.includes('DUPLICATE')))
const progress = computed(() => {
  const batch = detail.value?.batch
  if (!batch || batch.eligibleCount <= 0) return batch?.status === 'ROLLED_BACK' ? 0 : 100
  return Math.min(100, Math.round(batch.importedCount / batch.eligibleCount * 100))
})
const canRollback = computed(() => Boolean(
  ['PENDING_REVIEW', 'CONFIRMED'].includes(detail.value?.batch.status ?? '')
  && authStore.hasPermission('question:import'),
))
const canPublish = computed(() => Boolean(
  ['PENDING_REVIEW', 'CONFIRMED'].includes(detail.value?.batch.status ?? '')
  && authStore.hasPermission('question:publish'),
))
const statusLabel = computed(() => {
  const status = detail.value?.batch.status
  if (status === 'PENDING_REVIEW') return '待审核'
  if (status === 'PUBLISHED') return '已发布'
  if (status === 'REJECTED') return '已驳回'
  if (status === 'ROLLED_BACK') return '已回滚'
  if (status === 'CONFIRMED') return '已确认'
  if (status === 'FAILED') return '处理失败'
  return status ?? '加载中'
})
const statusTone = computed(() => (detail.value?.batch.status ?? 'unknown').toLowerCase())

function createMutationId(): string {
  return globalThis.crypto?.randomUUID?.() ?? `question-import-rollback-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

async function load(): Promise<void> {
  if (!Number.isSafeInteger(batchId.value) || batchId.value <= 0) {
    errorMessage.value = '导入批次地址无效'
    return
  }
  loading.value = true
  errorMessage.value = ''
  try {
    detail.value = await gateway.getImportBatch(batchId.value)
  } catch (error) {
    detail.value = null
    errorMessage.value = resolveAdminQuestionError(error, '导入批次加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

async function rollback(): Promise<void> {
  if (!canRollback.value) return
  try {
    await ElMessageBox.confirm(
      '仅仍处于草稿且未被修改的导入题目可以回滚。确认继续？',
      '回滚导入批次',
      { type: 'warning', confirmButtonText: '确认回滚', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  actionLoading.value = true
  errorMessage.value = ''
  try {
    detail.value = await gateway.rollbackImport({
      batchId: batchId.value,
      clientMutationId: rollbackMutationId.value,
    })
    rollbackMutationId.value = createMutationId()
    ElMessage.success('导入批次已回滚')
  } catch (error) {
    errorMessage.value = resolveAdminQuestionError(error, '导入批次回滚失败，请刷新后重试')
  } finally {
    actionLoading.value = false
  }
}

async function publish(): Promise<void> {
  if (!canPublish.value) return
  try {
    await ElMessageBox.confirm(
      '系统将再次校验题号、答案、分值、知识点和图片，并在同一事务中发布全部题目和整张试卷。确认继续？',
      '审核通过并发布整卷',
      { type: 'warning', confirmButtonText: '审核通过并发布', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  actionLoading.value = true
  errorMessage.value = ''
  try {
    detail.value = await gateway.publishImport({
      batchId: batchId.value,
      clientMutationId: publishMutationId.value,
    })
    publishMutationId.value = createMutationId()
    ElMessage.success('题目和试卷已完整发布')
  } catch (error) {
    errorMessage.value = resolveAdminQuestionError(error, '导入批次发布失败，请刷新后重试')
  } finally {
    actionLoading.value = false
  }
}

async function reject(): Promise<void> {
  if (!canPublish.value) return
  let reason = ''
  try {
    const result = await ElMessageBox.prompt(
      '请填写驳回原因。草稿题目和草稿试卷将被撤销，审核记录会保留。',
      '驳回整卷',
      {
        type: 'warning',
        inputPlaceholder: '例如：题号缺失、答案无法核对',
        inputValidator: value => value.trim().length > 0 || '请填写驳回原因',
        confirmButtonText: '确认驳回',
        cancelButtonText: '取消',
      },
    )
    reason = result.value.trim()
  } catch {
    return
  }
  actionLoading.value = true
  errorMessage.value = ''
  try {
    detail.value = await gateway.rejectImport({
      batchId: batchId.value,
      clientMutationId: createMutationId(),
      reason,
    })
    ElMessage.success('整卷已驳回')
  } catch (error) {
    errorMessage.value = resolveAdminQuestionError(error, '整卷驳回失败，请刷新后重试')
  } finally {
    actionLoading.value = false
  }
}

async function downloadErrors(): Promise<void> {
  if (!detail.value || !failedItems.value.length) return
  actionLoading.value = true
  try {
    const blob = await gateway.downloadImportErrors(detail.value.batch.batchId)
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `question-import-${detail.value.batch.batchId}-errors.csv`
    anchor.click()
    URL.revokeObjectURL(url)
  } catch (error) {
    errorMessage.value = resolveAdminQuestionError(error, '错误明细下载失败，请稍后重试')
  } finally {
    actionLoading.value = false
  }
}

watch(batchId, () => { void load() })
onMounted(() => { void load() })
</script>

<template>
  <section class="import-detail-page" data-testid="question-import-detail-page" :aria-busy="loading">
    <header class="detail-header">
      <div><p>题库管理 <span>/</span> 导入批次</p><h1>批次 #{{ batchId }}</h1><small>核对导入结果并完成整批题目的审核处理</small></div>
      <div class="detail-actions">
        <el-button class="back-button" @click="router.push('/admin/question-imports')"><el-icon><ArrowLeft /></el-icon>返回批次列表</el-button>
        <el-button :disabled="!failedItems.length" :loading="actionLoading" data-testid="download-import-errors" @click="downloadErrors"><el-icon><Download /></el-icon>下载失败明细</el-button>
        <el-button :loading="loading" @click="load"><el-icon><Refresh /></el-icon>刷新</el-button>
      </div>
    </header>

    <el-alert v-if="errorMessage" :title="errorMessage" type="error" :closable="false" show-icon data-testid="import-detail-error" />

    <template v-if="detail">
      <section class="batch-status-panel">
        <div class="status-summary">
          <span class="status-icon" :class="`tone-${statusTone}`"><el-icon><component :is="detail.batch.status === 'PUBLISHED' ? CircleCheck : detail.batch.status === 'FAILED' ? Warning : Files" /></el-icon></span>
          <div><small>当前批次状态</small><strong>{{ statusLabel }}</strong><p>Schema {{ detail.batch.schemaVersion }} · 批次 ID {{ detail.batch.batchId }}</p></div>
          <span class="status-pill" :class="`tone-${statusTone}`"><i></i>{{ statusLabel }}</span>
        </div>
        <div class="review-actions">
          <div><strong>批次审核操作</strong><small>发布、驳回和回滚会影响整批题目，请确认明细后操作。</small></div>
          <el-button type="success" :disabled="!canPublish" :loading="actionLoading" data-testid="publish-import" @click="publish"><el-icon><CircleCheck /></el-icon>审核通过并发布</el-button>
          <el-button type="danger" plain :disabled="!canPublish" :loading="actionLoading" @click="reject"><el-icon><CloseBold /></el-icon>驳回整卷</el-button>
          <el-button class="rollback-button" :disabled="!canRollback" :loading="actionLoading" data-testid="rollback-import" @click="rollback"><el-icon><RefreshLeft /></el-icon>回滚批次</el-button>
        </div>
      </section>

      <section class="batch-summary" data-testid="import-batch-summary">
        <div class="summary-primary"><span>题目总数</span><strong>{{ detail.batch.totalCount }}</strong><small>本批次全部记录</small></div>
        <div><span>可导入</span><strong>{{ detail.batch.eligibleCount }}</strong><small>通过格式与字段校验</small></div>
        <div><span>已导入</span><strong>{{ detail.batch.importedCount }}</strong><small>已生成题目草稿</small></div>
        <div :class="{ 'has-warning': failedItems.length }"><span>失败</span><strong>{{ failedItems.length }}</strong><small>需要人工检查</small></div>
        <div :class="{ 'has-warning': duplicateItems.length }"><span>重复项</span><strong>{{ duplicateItems.length }}</strong><small>内容或业务键重复</small></div>
        <div><span>关联试卷</span><strong>{{ detail.batch.paperId ?? '—' }}</strong><small>整卷导入关联标识</small></div>
      </section>

      <section class="progress-panel">
        <div class="progress-heading"><div><span class="panel-icon"><el-icon><Refresh /></el-icon></span><p><small>处理进度</small><strong>批次入库进度</strong></p></div><span>{{ progress }}%</span></div>
        <el-progress :percentage="progress" :status="detail.batch.status === 'FAILED' ? 'exception' : detail.batch.status === 'ROLLED_BACK' ? 'warning' : progress === 100 ? 'success' : undefined" />
        <footer><span>文件摘要</span><code>{{ detail.batch.inputSha256 || '无文件摘要' }}</code></footer>
      </section>

      <section class="item-panel" data-testid="import-item-details">
        <div class="item-panel__head">
          <div><span class="panel-icon panel-icon--slate"><el-icon><Files /></el-icon></span><div class="heading-copy"><small>逐题核对</small><h2>导入明细</h2></div></div>
          <span>失败行可下载为 CSV，内容摘要用于重复项核对</span>
        </div>
        <el-table :data="detail.items" row-key="itemId" empty-text="暂无导入明细">
          <el-table-column prop="lineNo" label="行" width="70" />
          <el-table-column prop="externalKey" label="业务键" min-width="180" />
          <el-table-column prop="itemStatus" label="状态" width="140"><template #default="scope"><span class="item-status">{{ scope.row.itemStatus }}</span></template></el-table-column>
          <el-table-column label="校验" width="100"><template #default="scope"><span class="validation-tag" :class="{ 'is-error': !scope.row.eligible }"><i></i>{{ scope.row.eligible ? '通过' : '失败' }}</span></template></el-table-column>
          <el-table-column label="失败原因" min-width="210"><template #default="scope"><span :class="{ 'error-reason': !scope.row.eligible || scope.row.errorCode }">{{ scope.row.eligible && !scope.row.errorCode ? '—' : resolveImportItemError(scope.row.errorCode) }}</span></template></el-table-column>
          <el-table-column prop="contentHash" label="内容摘要" min-width="230" show-overflow-tooltip />
          <el-table-column prop="questionId" label="题目 ID" width="100" />
          <el-table-column prop="questionVersionId" label="版本 ID" width="110" />
          <el-table-column label="操作" width="110" align="right">
            <template #default="scope">
              <el-button
                v-if="scope.row.questionId && scope.row.questionVersionId"
                link
                type="primary"
                @click="router.push(`/admin/questions/${scope.row.questionId}/versions/${scope.row.questionVersionId}`)"
              >查看修改<el-icon><ArrowRight /></el-icon></el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </template>
  </section>
</template>

<style scoped>
.import-detail-page {
  display: grid;
  gap: 18px;
  min-height: calc(100vh - var(--platform-header-height));
  padding: 28px clamp(20px, 3vw, 46px) 48px;
  background: #f5f7fb;
}

.detail-header,
.detail-actions,
.item-panel__head,
.progress-heading,
.progress-heading > div,
.item-panel__head > div,
.status-summary,
.review-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.detail-header,
.item-panel__head,
.progress-heading {
  justify-content: space-between;
}

.detail-header p {
  margin: 0 0 7px;
  color: #7b879d;
  font-size: 13px;
}

.detail-header p span {
  margin: 0 5px;
  color: #b0b9c8;
}

.detail-header h1,
.item-panel h2 {
  margin: 0;
  color: #172033;
}

.detail-header h1 {
  font-size: 28px;
  line-height: 1.25;
}

.detail-header small {
  display: block;
  margin-top: 6px;
  color: #748198;
  font-size: 13px;
}

.detail-actions {
  flex-wrap: wrap;
  justify-content: flex-end;
}

.detail-actions :deep(.el-button) {
  min-height: 38px;
  margin-left: 0;
  border-radius: 9px;
}

.back-button {
  color: #344057;
}

.batch-status-panel,
.progress-panel,
.item-panel {
  overflow: hidden;
  border: 1px solid #dfe5ef;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 10px 30px rgba(31, 45, 75, 0.05);
}

.status-summary {
  padding: 22px 24px;
}

.status-summary > div {
  display: grid;
  gap: 3px;
}

.status-summary small,
.heading-copy small,
.progress-heading small {
  color: #748198;
  font-size: 12px;
}

.status-summary strong {
  color: #172033;
  font-size: 20px;
}

.status-summary p {
  margin: 0;
  color: #8a95a8;
  font-size: 12px;
}

.status-icon,
.panel-icon {
  display: inline-grid;
  width: 42px;
  height: 42px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 12px;
  background: #eaf1ff;
  color: #2563eb;
  font-size: 20px;
}

.status-icon.tone-published,
.status-icon.tone-confirmed {
  background: #e8f8ef;
  color: #168653;
}

.status-icon.tone-failed,
.status-icon.tone-rejected {
  background: #fff0f0;
  color: #d94b4b;
}

.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  margin-left: auto;
  padding: 7px 11px;
  border-radius: 999px;
  background: #edf3ff;
  color: #2563eb;
  font-size: 12px;
  font-weight: 600;
}

.status-pill i,
.validation-tag i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}

.status-pill.tone-published,
.status-pill.tone-confirmed {
  background: #e9f8f0;
  color: #168653;
}

.status-pill.tone-failed,
.status-pill.tone-rejected {
  background: #fff0f0;
  color: #d94b4b;
}

.status-pill.tone-rolled_back {
  background: #f1f3f6;
  color: #687387;
}

.review-actions {
  padding: 16px 24px;
  border-top: 1px solid #edf0f5;
  background: #fafbfc;
}

.review-actions > div {
  display: grid;
  flex: 1;
  gap: 3px;
}

.review-actions strong {
  color: #273247;
  font-size: 14px;
}

.review-actions small {
  color: #7b879b;
}

.review-actions :deep(.el-button) {
  min-height: 38px;
  margin-left: 0;
  border-radius: 9px;
}

.batch-summary {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 12px;
}

.batch-summary div {
  display: grid;
  gap: 5px;
  min-height: 108px;
  padding: 16px 18px;
  border: 1px solid #e0e6ef;
  border-radius: 13px;
  background: #fff;
  box-shadow: 0 6px 18px rgba(31, 45, 75, 0.035);
}

.batch-summary span,
.item-panel__head span,
.progress-panel span {
  color: #748198;
  font-size: 12px;
}

.batch-summary strong {
  color: #172033;
  font-size: 25px;
  line-height: 1.1;
}

.batch-summary small {
  color: #909aad;
  font-size: 11px;
}

.batch-summary .summary-primary {
  border-color: #cddcff;
  background: #f7faff;
}

.batch-summary .summary-primary strong {
  color: #2563eb;
}

.batch-summary .has-warning {
  border-color: #f2d8d8;
  background: #fffafa;
}

.batch-summary .has-warning strong {
  color: #d94b4b;
}

.progress-panel,
.item-panel {
  display: grid;
  gap: 16px;
  padding: 20px 22px;
}

.progress-heading strong {
  display: block;
  margin-top: 2px;
  color: #273247;
  font-size: 15px;
}

.progress-heading p {
  margin: 0;
}

.progress-heading > span {
  color: #2563eb;
  font-size: 20px;
  font-weight: 700;
}

.panel-icon {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  font-size: 17px;
}

.panel-icon--slate {
  background: #eef1f5;
  color: #526078;
}

.progress-panel :deep(.el-progress-bar__outer) {
  background: #edf1f6;
}

.progress-panel footer {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
  padding-top: 13px;
  border-top: 1px solid #edf0f5;
}

.progress-panel code {
  overflow: hidden;
  color: #657188;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.item-panel {
  padding: 0;
  gap: 0;
}

.item-panel__head {
  padding: 20px 22px;
  border-bottom: 1px solid #edf0f5;
}

.heading-copy {
  display: grid;
  gap: 2px;
}

.item-panel h2 {
  font-size: 17px;
}

.item-panel :deep(.el-table th.el-table__cell) {
  height: 48px;
  background: #f8f9fb;
  color: #657188;
  font-weight: 600;
}

.item-panel :deep(.el-table td.el-table__cell) {
  height: 52px;
  color: #344057;
}

.item-panel :deep(.el-table__inner-wrapper::before) {
  display: none;
}

.item-status {
  display: inline-flex;
  padding: 4px 8px;
  border-radius: 6px;
  background: #f0f3f8;
  color: #56637a;
  font-size: 12px;
}

.validation-tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #168653;
  font-size: 12px;
  font-weight: 600;
}

.validation-tag.is-error,
.error-reason {
  color: #d94b4b;
}

@media (max-width: 1100px) {
  .batch-summary {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .review-actions {
    flex-wrap: wrap;
  }

  .review-actions > div {
    flex-basis: 100%;
    margin-bottom: 4px;
  }
}

@media (max-width: 720px) {
  .import-detail-page {
    padding: 20px 14px 36px;
  }

  .detail-header,
  .item-panel__head {
    align-items: flex-start;
    flex-direction: column;
  }

  .detail-actions {
    justify-content: flex-start;
    width: 100%;
  }

  .batch-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .review-actions :deep(.el-button) {
    flex: 1;
  }

  .status-summary {
    padding: 18px;
  }

  .review-actions {
    padding: 15px 18px;
  }
}

@media (max-width: 460px) {
  .batch-summary {
    grid-template-columns: 1fr;
  }

  .status-pill {
    display: none;
  }
}
</style>
