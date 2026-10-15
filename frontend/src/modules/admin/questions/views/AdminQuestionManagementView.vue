<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  CirclePlus,
  DocumentAdd,
  Filter,
  Refresh,
  Search,
  UploadFilled,
  Warning,
} from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { platformApiClient, unwrapApiResponse } from '@/api/client'
import { getAdminQuestionGateway } from '../api/gateway'
import { resolveAdminQuestionError } from '../model/error-messages'
import {
  ADMIN_COPYRIGHT_STATUSES,
  ADMIN_QUESTION_STATES,
  ADMIN_QUESTION_TYPES,
  ADMIN_SOURCE_TYPES,
  ADMIN_SOURCE_TYPE_LABELS,
  type AdminQuestionListQuery,
  type AdminQuestionMutationCommand,
  type AdminQuestionState,
  type AdminQuestionSummary,
  type AdminQuestionType,
} from '../types'

const typeLabels: Record<AdminQuestionType, string> = {
  SINGLE_CHOICE: '单选题',
  MULTI_CHOICE: '多选题',
  JUDGE: '判断题',
  CASE: '案例分析题',
  ESSAY: '论文题',
}
function sourceTypeLabel(value: keyof typeof ADMIN_SOURCE_TYPE_LABELS | null | undefined): string {
  return value ? ADMIN_SOURCE_TYPE_LABELS[value] : '-'
}

const stateLabels: Record<AdminQuestionState, string> = {
  DRAFT: '草稿',
  IN_REVIEW: '待审核',
  PUBLISHED: '已发布',
  RETIRED: '已退役',
}

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const gateway = getAdminQuestionGateway()
const loading = ref(false)
const actionLoading = ref('')
const errorMessage = ref('')
const rows = ref<AdminQuestionSummary[]>([])
const total = ref(0)
type FoundationOption = { id: number; name: string }
type FoundationPage = { items?: FoundationOption[]; records?: FoundationOption[] }
const qualifications = ref<FoundationOption[]>([])
const subjects = ref<FoundationOption[]>([])
const rejectOpen = ref(false)
const rejectReason = ref('')
const rejectTarget = ref<AdminQuestionSummary | null>(null)
const mutationIds = reactive<Record<string, string>>({})
const query = reactive<AdminQuestionListQuery>({
  pageNo: 1,
  pageSize: 20,
  questionId: undefined,
  keyword: '',
  qualificationId: undefined,
  subjectId: undefined,
  questionType: 'ALL',
  sourceType: 'ALL',
  status: routeStatus(route.query.status),
  copyrightStatus: 'ALL',
})

const canCreate = computed(() => authStore.hasPermission('question:create'))
const canImport = computed(() => authStore.hasPermission('question:import'))
const activeFilterCount = computed(() => [
  query.questionId,
  query.keyword?.trim(),
  query.qualificationId,
  query.subjectId,
  query.questionType !== 'ALL',
  query.sourceType !== 'ALL',
  query.status !== 'ALL',
  query.copyrightStatus !== 'ALL',
].filter(Boolean).length)

function routeStatus(value: unknown): AdminQuestionState | 'ALL' {
  const candidate = Array.isArray(value) ? value[0] : value
  return ADMIN_QUESTION_STATES.includes(candidate as AdminQuestionState)
    ? candidate as AdminQuestionState
    : 'ALL'
}

function currentUserIsSubmitter(row: AdminQuestionSummary): boolean {
  return row.submittedBy != null && String(row.submittedBy) === String(authStore.user?.id)
}

function canEdit(row: AdminQuestionSummary): boolean {
  return row.status === 'DRAFT' && authStore.hasPermission('question:update')
}

function canSubmit(row: AdminQuestionSummary): boolean {
  return row.status === 'DRAFT' && authStore.hasPermission('question:submit-review')
}

function canReview(row: AdminQuestionSummary): boolean {
  return row.status === 'IN_REVIEW' && authStore.hasPermission('question:review') && !currentUserIsSubmitter(row)
}

function canPublish(row: AdminQuestionSummary): boolean {
  return row.status === 'IN_REVIEW' && row.reviewDecision === 'APPROVED' && authStore.hasPermission('question:publish') && !currentUserIsSubmitter(row)
}

function canCopy(row: AdminQuestionSummary): boolean {
  return authStore.hasPermission('question:update')
    && (row.status === 'PUBLISHED' || row.status === 'RETIRED')
    && row.currentWorking !== true
}

function canRetire(row: AdminQuestionSummary): boolean {
  return row.status === 'PUBLISHED' && authStore.hasPermission('question:publish')
}

function questionTypeLabel(row: AdminQuestionSummary): string {
  return typeLabels[row.questionType]
}

function stateLabel(row: AdminQuestionSummary): string {
  return stateLabels[row.status]
}

function createMutationId(): string {
  return globalThis.crypto?.randomUUID?.() ?? `question-list-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function mutationCommand(row: AdminQuestionSummary, action: string): AdminQuestionMutationCommand {
  const key = `${action}:${row.questionId}:${row.questionVersionId}`
  mutationIds[key] ??= createMutationId()
  return {
    questionId: row.questionId,
    questionVersionId: row.questionVersionId,
    expectedRowVersion: row.rowVersion,
    clientMutationId: mutationIds[key],
  }
}

function finishMutation(row: AdminQuestionSummary, action: string): void {
  delete mutationIds[`${action}:${row.questionId}:${row.questionVersionId}`]
}

async function loadQuestions(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const page = await gateway.listQuestions(query)
    rows.value = page.items
    total.value = page.total
  } catch (error) {
    rows.value = []
    total.value = 0
    errorMessage.value = resolveAdminQuestionError(error, '题库列表加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

function search(): void {
  query.pageNo = 1
  void loadQuestions()
}

function resetFilters(): void {
  Object.assign(query, {
    pageNo: 1,
    pageSize: 20,
    questionId: undefined,
    keyword: '',
    qualificationId: undefined,
    subjectId: undefined,
    questionType: 'ALL',
    sourceType: 'ALL',
    status: 'ALL',
    copyrightStatus: 'ALL',
  })
  void loadQuestions()
}

async function loadQualifications(): Promise<void> {
  const page = await unwrapApiResponse<FoundationPage>(platformApiClient.get('/foundation/qualifications', {
    params: { pageNo: 1, pageSize: 100 },
  }))
  qualifications.value = page.items ?? page.records ?? []
}

async function loadSubjects(qualificationId?: number): Promise<void> {
  subjects.value = []
  if (!qualificationId) return
  const page = await unwrapApiResponse<FoundationPage>(platformApiClient.get(
    `/foundation/qualifications/${qualificationId}/subjects`,
    { params: { pageNo: 1, pageSize: 100 } },
  ))
  subjects.value = page.items ?? page.records ?? []
}

function openDetail(row: AdminQuestionSummary): void {
  void router.push(`/admin/questions/${row.questionId}/versions/${row.questionVersionId}`)
}

async function runAction(row: AdminQuestionSummary, action: 'submit' | 'approve' | 'publish' | 'copy' | 'retire' | 'reject'): Promise<void> {
  actionLoading.value = `${action}:${row.questionVersionId}`
  try {
    const command = mutationCommand(row, action)
    const result = action === 'submit'
      ? await gateway.submitReview(command)
      : action === 'approve'
        ? await gateway.approveReview(command)
        : action === 'publish'
          ? await gateway.publishQuestion(command)
          : action === 'copy'
            ? await gateway.copyVersion(command)
            : action === 'retire'
              ? await gateway.retireQuestion(command)
              : await gateway.rejectReview({ ...command, reason: rejectReason.value.trim() })
    finishMutation(row, action)
    rejectOpen.value = false
    rejectReason.value = ''
    rejectTarget.value = null
    ElMessage.success(result.replayed ? '已恢复此前操作结果' : '操作成功')
    if (action === 'copy') {
      await router.push(`/admin/questions/${result.questionId}/versions/${result.questionVersionId}`)
      return
    }
    await loadQuestions()
  } catch (error) {
    ElMessage.error(resolveAdminQuestionError(error))
  } finally {
    actionLoading.value = ''
  }
}

function openReject(row: AdminQuestionSummary): void {
  if (!canReview(row)) return
  rejectTarget.value = row
  rejectReason.value = ''
  rejectOpen.value = true
}

function confirmReject(): void {
  if (!rejectReason.value.trim()) {
    ElMessage.warning('请填写审核退回原因')
    return
  }
  if (rejectTarget.value) void runAction(rejectTarget.value, 'reject')
}

watch(() => route.query.status, (value) => {
  const next = routeStatus(value)
  if (next === query.status) return
  query.status = next
  query.pageNo = 1
  void loadQuestions()
})
watch(() => query.qualificationId, value => {
  if (!subjects.value.some(item => item.id === query.subjectId)) query.subjectId = undefined
  void loadSubjects(value)
})

onMounted(() => { void Promise.all([loadQuestions(), loadQualifications()]) })
</script>

<template>
  <section class="admin-question-page" data-testid="admin-question-page">
    <header class="page-header">
      <div>
        <p>题库运营中心</p>
        <h1>题库管理</h1>
        <span>统一管理题目录入、审核、发布与版本修订</span>
      </div>
      <div class="header-actions">
        <el-button class="header-secondary" data-testid="open-anomalies" @click="router.push('/admin/questions/anomalies')">
          <el-icon><Warning /></el-icon>异常题处理
        </el-button>
        <el-button class="header-secondary" :disabled="!canImport" data-testid="open-imports" @click="router.push('/admin/question-imports')">
          <el-icon><UploadFilled /></el-icon>批量导入
        </el-button>
        <el-button type="primary" :disabled="!canCreate" data-testid="create-question" @click="router.push('/admin/questions/new')">
          <el-icon><CirclePlus /></el-icon>录入题目
        </el-button>
      </div>
    </header>

    <section class="filter-panel" aria-label="题库组合筛选" data-testid="question-filters">
      <header>
        <div><span class="filter-icon"><el-icon><Filter /></el-icon></span><p><strong>筛选题目</strong><small>支持按范围、题型和工作流状态组合查询</small></p></div>
        <span v-if="activeFilterCount" class="active-filter-count">{{ activeFilterCount }} 项条件已启用</span>
      </header>
      <div class="filter-bar">
        <label class="filter-field filter-field--keyword">
          <span>关键词</span>
          <el-input v-model="query.keyword" :prefix-icon="Search" clearable placeholder="搜索题目标题" data-testid="filter-keyword" @keyup.enter="search" />
        </label>
        <label class="filter-field">
          <span>题目 ID</span>
          <el-input-number v-model="query.questionId" :min="1" :controls="false" placeholder="输入 ID" data-testid="filter-question-id" />
        </label>
        <label class="filter-field">
          <span>考试资格</span>
          <el-select v-model="query.qualificationId" clearable placeholder="全部资格" data-testid="filter-qualification">
            <el-option v-for="item in qualifications" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </label>
        <label class="filter-field">
          <span>考试科目</span>
          <el-select v-model="query.subjectId" clearable :disabled="!query.qualificationId" placeholder="全部科目" data-testid="filter-subject">
            <el-option v-for="item in subjects" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </label>
        <label class="filter-field">
          <span>题型</span>
          <el-select v-model="query.questionType" data-testid="filter-type">
            <el-option label="全部题型" value="ALL" />
            <el-option v-for="type in ADMIN_QUESTION_TYPES" :key="type" :label="typeLabels[type]" :value="type" />
          </el-select>
        </label>
        <label class="filter-field">
          <span>来源</span>
          <el-select v-model="query.sourceType" data-testid="filter-source">
            <el-option label="全部来源" value="ALL" />
            <el-option v-for="source in ADMIN_SOURCE_TYPES" :key="source" :label="ADMIN_SOURCE_TYPE_LABELS[source]" :value="source" />
          </el-select>
        </label>
        <label class="filter-field">
          <span>工作流状态</span>
          <el-select v-model="query.status" data-testid="filter-status">
            <el-option label="全部状态" value="ALL" />
            <el-option v-for="status in ADMIN_QUESTION_STATES" :key="status" :label="stateLabels[status]" :value="status" />
          </el-select>
        </label>
        <label class="filter-field">
          <span>权利状态</span>
          <el-select v-model="query.copyrightStatus" data-testid="filter-copyright">
            <el-option label="全部权利状态" value="ALL" />
            <el-option v-for="status in ADMIN_COPYRIGHT_STATUSES" :key="status" :label="status" :value="status" />
          </el-select>
        </label>
        <div class="filter-actions">
          <el-button class="reset-button" @click="resetFilters"><el-icon><Refresh /></el-icon>重置</el-button>
          <el-button type="primary" data-testid="apply-filters" @click="search"><el-icon><Search /></el-icon>查询题目</el-button>
        </div>
      </div>
    </section>

    <el-alert v-if="errorMessage" :title="errorMessage" type="error" :closable="false" show-icon data-testid="admin-question-error" />

    <section class="question-table" :aria-busy="loading">
      <header class="table-heading">
        <div><span class="table-heading-icon"><el-icon><DocumentAdd /></el-icon></span><p><strong>题目列表</strong><small>共 {{ total }} 条题目记录</small></p></div>
        <span>第 {{ query.pageNo }} 页</span>
      </header>
      <el-table v-loading="loading" :data="rows" row-key="questionVersionId" empty-text="暂无符合条件的题目" data-testid="question-table">
        <el-table-column label="题目" min-width="260">
          <template #default="scope">
            <button class="question-link" type="button" @click="openDetail(scope.row)">{{ scope.row.title }}</button>
            <small class="question-meta">题目 #{{ scope.row.questionId }} · 版本 #{{ scope.row.questionVersionId }}</small>
            <small v-if="currentUserIsSubmitter(scope.row)" class="self-review">当前用户为提交人，禁止自审</small>
          </template>
        </el-table-column>
        <el-table-column label="范围" min-width="160">
          <template #default="scope"><span class="scope-text">资格 {{ scope.row.qualificationId }}<i></i>科目 {{ scope.row.subjectId }}</span></template>
        </el-table-column>
        <el-table-column label="题型" width="120"><template #default="scope"><span class="type-tag">{{ questionTypeLabel(scope.row) }}</span></template></el-table-column>
        <el-table-column label="来源" width="150"><template #default="scope">{{ sourceTypeLabel(scope.row.sourceType) }}</template></el-table-column>
        <el-table-column label="状态" width="110"><template #default="scope"><span class="state-tag" :class="`state-${scope.row.status.toLowerCase()}`"><i></i>{{ stateLabel(scope.row) }}</span></template></el-table-column>
        <el-table-column label="版本" width="80"><template #default="scope">v{{ scope.row.versionNo }}</template></el-table-column>
        <el-table-column label="操作" min-width="470" fixed="right">
          <template #default="scope">
            <div class="row-actions">
              <el-button link type="primary" @click="openDetail(scope.row)">查看</el-button>
              <el-button link :disabled="!canEdit(scope.row)" :data-testid="`edit-${scope.row.questionVersionId}`" @click="openDetail(scope.row)">编辑</el-button>
              <el-button size="small" plain :disabled="!canSubmit(scope.row)" :loading="actionLoading === `submit:${scope.row.questionVersionId}`" :data-testid="`submit-${scope.row.questionVersionId}`" @click="runAction(scope.row, 'submit')">提交审核</el-button>
              <el-tooltip :content="currentUserIsSubmitter(scope.row) ? '禁止自审' : '审核通过'">
                <span><el-button size="small" type="success" plain :disabled="!canReview(scope.row)" :loading="actionLoading === `approve:${scope.row.questionVersionId}`" :data-testid="`approve-${scope.row.questionVersionId}`" @click="runAction(scope.row, 'approve')">通过</el-button></span>
              </el-tooltip>
              <el-button size="small" plain :disabled="!canReview(scope.row)" @click="openReject(scope.row)">退回</el-button>
              <el-tooltip :content="currentUserIsSubmitter(scope.row) ? '提交人不能发布自己提交的版本' : '仅审核通过的待审核版本可以发布'">
                <span><el-button size="small" type="primary" :disabled="!canPublish(scope.row)" :loading="actionLoading === `publish:${scope.row.questionVersionId}`" :data-testid="`publish-${scope.row.questionVersionId}`" @click="runAction(scope.row, 'publish')">发布</el-button></span>
              </el-tooltip>
              <el-button size="small" plain :disabled="!canCopy(scope.row)" :loading="actionLoading === `copy:${scope.row.questionVersionId}`" :data-testid="`copy-${scope.row.questionVersionId}`" @click="runAction(scope.row, 'copy')">创建修订</el-button>
              <el-button size="small" type="danger" plain :disabled="!canRetire(scope.row)" :loading="actionLoading === `retire:${scope.row.questionVersionId}`" :data-testid="`retire-${scope.row.questionVersionId}`" @click="runAction(scope.row, 'retire')">退役</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="query.pageNo"
        v-model:page-size="query.pageSize"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        data-testid="question-pagination"
        @current-change="loadQuestions"
        @size-change="query.pageNo = 1; loadQuestions()"
      />
    </section>

    <el-dialog v-model="rejectOpen" title="审核退回" width="min(520px, calc(100% - 28px))" class="reject-dialog" data-testid="list-reject-dialog">
      <p class="reject-description">请说明题目需要修改的具体内容，提交人将根据该原因进行修订。</p>
      <label class="reject-field"><span>退回原因 <em>必填</em></span><textarea v-model="rejectReason" rows="5" maxlength="500" placeholder="请输入清晰、可执行的修改意见" data-testid="list-reject-reason" /></label>
      <template #footer>
        <el-button @click="rejectOpen = false">取消</el-button>
        <el-button type="primary" :loading="actionLoading.startsWith('reject:')" @click="confirmReject">确认退回</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.admin-question-page {
  display: grid;
  gap: 14px;
  min-height: calc(100vh - var(--platform-header-height));
  padding: var(--platform-page-padding);
}

.page-header,
.header-actions,
.filter-bar,
.row-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.page-header {
  justify-content: space-between;
}

.page-header p {
  margin: 0 0 4px;
  color: var(--color-text-muted);
  font-size: 12px;
}

.page-header h1 {
  margin: 0;
  color: var(--color-brand);
}

.filter-bar,
.question-table {
  padding: 12px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-surface);
}

.filter-bar {
  flex-wrap: wrap;
}

.filter-bar > * {
  width: 150px;
}

.filter-bar > :first-child {
  width: 130px;
}

.filter-bar > :nth-child(2) {
  width: 220px;
}

.question-table {
  display: grid;
  gap: 12px;
  min-width: 0;
}

.question-link {
  display: block;
  overflow: hidden;
  max-width: 100%;
  border: 0;
  padding: 0;
  background: transparent;
  color: var(--color-primary);
  font: inherit;
  font-weight: 700;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}

.question-table small {
  display: block;
  margin-top: 3px;
  color: var(--color-text-muted);
}

.question-table .self-review {
  color: var(--color-warning);
}

.row-actions {
  flex-wrap: wrap;
}

.reject-field {
  display: grid;
  gap: 7px;
}

.reject-field textarea {
  box-sizing: border-box;
  width: 100%;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  padding: 10px;
}

.admin-question-page {
  gap: 18px;
  padding: 28px var(--platform-page-padding) 48px;
  background:
    radial-gradient(circle at 90% 1%, rgb(37 99 235 / 5%), transparent 28rem),
    var(--color-page-canvas);
  color: #172033;
}

.page-header,
.filter-panel,
.question-table,
.admin-question-page > .el-alert {
  width: min(1500px, 100%);
  margin-right: auto;
  margin-left: auto;
}

.page-header { align-items: flex-end; gap: 24px; }
.page-header p { color: #2563eb; font-size: 11px; font-weight: 700; letter-spacing: .1em; }
.page-header h1 { color: #172033; font-size: 29px; letter-spacing: -.025em; }
.page-header > div:first-child > span { display: block; margin-top: 7px; color: #64748b; font-size: 12px; }
.header-actions { flex-wrap: wrap; justify-content: flex-end; }
.header-actions .el-button { min-height: 40px; margin: 0; border-radius: 10px; }
.header-secondary { border-color: #dbe4f0; color: #475569; }

.filter-panel,
.question-table {
  border: 1px solid #dce5f0;
  border-radius: 15px;
  background: white;
  box-shadow: 0 10px 28px rgb(15 23 42 / 4%);
}

.filter-panel { padding: 20px 22px 22px; }
.filter-panel > header, .table-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.filter-panel > header { margin-bottom: 18px; }
.filter-panel > header > div, .table-heading > div { display: flex; align-items: center; gap: 11px; }
.filter-icon, .table-heading-icon { display: grid; width: 37px; height: 37px; flex: 0 0 auto; place-items: center; border-radius: 11px; background: #eff6ff; color: #2563eb; font-size: 17px; }
.filter-panel header p, .table-heading p { display: grid; margin: 0; gap: 3px; }
.filter-panel header strong, .table-heading strong { color: #334155; font-size: 13px; }
.filter-panel header small, .table-heading small { color: #94a3b8; font-size: 9px; }
.active-filter-count { padding: 5px 8px; border-radius: 7px; background: #eff6ff; color: #2563eb; font-size: 9px; font-weight: 650; }

.filter-bar {
  display: grid;
  padding: 0;
  grid-template-columns: minmax(220px, 1.35fr) repeat(3, minmax(120px, .7fr)) repeat(4, minmax(135px, .82fr));
  align-items: end;
  gap: 13px;
  border: 0;
  border-radius: 0;
  background: transparent;
}
.filter-bar > * { width: auto; }
.filter-bar > :first-child, .filter-bar > :nth-child(2) { width: auto; }
.filter-field { display: grid; min-width: 0; gap: 7px; }
.filter-field > span { color: #64748b; font-size: 10px; font-weight: 600; }
.filter-field :deep(.el-input-number), .filter-field :deep(.el-select) { width: 100%; }
.filter-field :deep(.el-input__wrapper), .filter-field :deep(.el-select__wrapper) { min-height: 39px; border-radius: 9px; box-shadow: 0 0 0 1px #dbe4f0 inset; }
.filter-field :deep(.el-input__wrapper.is-focus), .filter-field :deep(.el-select__wrapper.is-focused) { box-shadow: 0 0 0 1px #2563eb inset, 0 0 0 3px rgb(37 99 235 / 8%); }
.filter-actions { display: flex; grid-column: -3 / -1; justify-content: flex-end; gap: 8px; }
.filter-actions .el-button { min-height: 39px; margin: 0; border-radius: 9px; }
.reset-button { border-color: #dbe4f0; color: #64748b; }
.admin-question-page > .el-alert { border-radius: 10px; }

.question-table { padding: 20px 22px 16px; gap: 15px; }
.table-heading > span { color: #94a3b8; font-size: 9px; }
.question-table :deep(.el-table) { --el-table-border-color: #edf1f6; --el-table-header-bg-color: #f8fafc; --el-table-row-hover-bg-color: #f7faff; border-radius: 10px; color: #475569; font-size: 11px; }
.question-table :deep(.el-table th.el-table__cell) { height: 42px; color: #64748b; font-size: 10px; font-weight: 650; }
.question-table :deep(.el-table td.el-table__cell) { padding: 13px 0; }
.question-link { color: #1e3a8a; font-size: 12px; line-height: 1.5; }
.question-link:hover { color: #2563eb; }
.question-table .question-meta { margin-top: 5px; color: #94a3b8; font-size: 8px; }
.question-table .self-review { margin-top: 5px; color: #d97706; font-size: 8px; }
.scope-text { display: inline-flex; align-items: center; gap: 7px; color: #64748b; font-size: 10px; }
.scope-text i { width: 3px; height: 3px; border-radius: 50%; background: #cbd5e1; }
.type-tag { padding: 5px 7px; border-radius: 6px; background: #f1f5f9; color: #475569; font-size: 9px; }
.state-tag { display: inline-flex; padding: 5px 7px; align-items: center; gap: 5px; border-radius: 999px; background: #f1f5f9; color: #64748b; font-size: 9px; font-weight: 600; }
.state-tag i { width: 5px; height: 5px; border-radius: 50%; background: currentColor; }
.state-in_review { background: #fff7ed; color: #c2410c; }
.state-published { background: #ecfdf5; color: #047857; }
.state-retired { background: #f5f3ff; color: #6d28d9; }
.row-actions { gap: 5px; flex-wrap: nowrap; }
.row-actions .el-button { margin: 0; border-radius: 7px; font-size: 9px; }
.question-table :deep(.el-pagination) { justify-content: flex-end; padding-top: 2px; }
.question-table :deep(.el-pagination button), .question-table :deep(.el-pager li) { border-radius: 7px; }

.reject-description { margin: 0 0 16px; color: #64748b; font-size: 11px; line-height: 1.65; }
.reject-field { gap: 8px; }
.reject-field > span { color: #334155; font-size: 11px; font-weight: 650; }
.reject-field em { margin-left: 4px; color: #dc2626; font-size: 9px; font-style: normal; }
.reject-field textarea { min-height: 120px; padding: 11px 12px; border-color: #dbe4f0; border-radius: 10px; color: #334155; font-family: inherit; resize: vertical; }
.reject-field textarea:focus { border-color: #2563eb; outline: 3px solid rgb(37 99 235 / 8%); }

@media (max-width: 1320px) {
  .filter-bar { grid-template-columns: repeat(4, minmax(150px, 1fr)); }
  .filter-field--keyword { grid-column: span 2; }
  .filter-actions { grid-column: span 2; }
}

@media (max-width: 820px) {
  .admin-question-page { padding: 22px 14px 36px; }
  .page-header { align-items: flex-start; flex-direction: column; }
  .header-actions { width: 100%; justify-content: flex-start; }
  .filter-bar { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .filter-field--keyword { grid-column: 1 / -1; }
  .filter-actions { grid-column: 1 / -1; }
  .question-table { padding: 17px 12px 14px; overflow: hidden; }
  .question-table :deep(.el-pagination) { justify-content: flex-start; overflow-x: auto; }
}

@media (max-width: 520px) {
  .page-header h1 { font-size: 25px; }
  .header-actions .el-button { flex: 1; padding: 8px; }
  .filter-panel { padding: 17px 14px; }
  .filter-bar { grid-template-columns: 1fr; }
  .filter-field--keyword, .filter-actions { grid-column: auto; }
  .filter-actions .el-button { flex: 1; }
  .active-filter-count { display: none; }
}
</style>
