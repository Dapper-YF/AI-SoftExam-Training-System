<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Clock, DocumentCopy, EditPen, QuestionFilled } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import AdminQuestionEditor from '../components/AdminQuestionEditor.vue'
import { getAdminQuestionGateway } from '../api/gateway'
import { buildQuestionPayload, questionFormFromDetail, validateQuestionForm, type AdminQuestionFormModel } from '../model/question-form'
import { resolveAdminQuestionError } from '../model/error-messages'
import type {
  AdminQuestionAuditEvent,
  AdminQuestionDetail,
  AdminQuestionMutationCommand,
  AdminQuestionReviewRecord,
  AdminQuestionVersionHistory,
} from '../types'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const gateway = getAdminQuestionGateway()
const detail = ref<AdminQuestionDetail | null>(null)
const form = ref<AdminQuestionFormModel | null>(null)
const versions = ref<AdminQuestionVersionHistory[]>([])
const reviews = ref<AdminQuestionReviewRecord[]>([])
const audits = ref<AdminQuestionAuditEvent[]>([])
const loading = ref(false)
const actionLoading = ref('')
const editing = ref(false)
const errors = ref<string[]>([])
const rejectOpen = ref(false)
const rejectReason = ref('')
const mutationIds = reactive<Record<string, string>>({})

const questionId = computed(() => Number(route.params.questionId))
const questionVersionId = computed(() => Number(route.params.versionId))
const currentUserId = computed(() => authStore.user?.id == null ? null : String(authStore.user.id))
const isSelfReview = computed(() => detail.value?.submittedBy != null && String(detail.value.submittedBy) === currentUserId.value)
const currentVersionHistory = computed(() => versions.value.find((item) => item.questionVersionId === detail.value?.questionVersionId))
const isCurrentWorking = computed(() => currentVersionHistory.value?.currentWorking === true && currentVersionHistory.value.deleted !== true)
const isCurrentPublished = computed(() => currentVersionHistory.value?.currentPublished === true && currentVersionHistory.value.deleted !== true)
const hasWorkingVersion = computed(() => versions.value.some((item) => item.currentWorking && !item.deleted))
const canEdit = computed(() => Boolean(detail.value?.status === 'DRAFT' && isCurrentWorking.value && authStore.hasPermission('question:update')))
const canSubmit = computed(() => Boolean(detail.value?.status === 'DRAFT' && isCurrentWorking.value && authStore.hasPermission('question:submit-review')))
const canReview = computed(() => Boolean(detail.value?.status === 'IN_REVIEW' && isCurrentWorking.value && authStore.hasPermission('question:review') && !isSelfReview.value))
const canPublish = computed(() => Boolean(
  detail.value?.status === 'IN_REVIEW'
  && isCurrentWorking.value
  && detail.value.reviewDecision === 'APPROVED'
  && authStore.hasPermission('question:publish')
  && !isSelfReview.value,
))
const canRetire = computed(() => Boolean(detail.value?.status === 'PUBLISHED' && isCurrentPublished.value && authStore.hasPermission('question:publish')))
const canCopy = computed(() => Boolean(
  detail.value
  && ['PUBLISHED', 'RETIRED'].includes(detail.value.status)
  && currentVersionHistory.value?.deleted !== true
  && !hasWorkingVersion.value
  && authStore.hasPermission('question:update'),
))
const statusLabels: Record<string, string> = { DRAFT: '草稿', IN_REVIEW: '待审核', PUBLISHED: '已发布', RETIRED: '已退役' }
const decisionLabels: Record<string, string> = { PENDING: '待审核', APPROVED: '审核通过', REJECTED: '已退回', WITHDRAWN: '已撤回' }
const statusLabel = computed(() => statusLabels[detail.value?.status ?? ''] ?? detail.value?.status ?? '加载中')
const decisionLabel = computed(() => decisionLabels[detail.value?.reviewDecision ?? ''] ?? '尚未审核')

function formatDateTime(value: string | null | undefined): string {
  if (!value) return '未提供'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

function createMutationId(): string {
  return globalThis.crypto?.randomUUID?.() ?? `question-action-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function mutationIdFor(action: string): string {
  mutationIds[action] ??= createMutationId()
  return mutationIds[action]
}

function finishMutation(action: string): void {
  delete mutationIds[action]
}

function mutationCommand(action: string): AdminQuestionMutationCommand {
  if (!detail.value) throw new Error('detail unavailable')
  return {
    questionId: detail.value.questionId,
    questionVersionId: detail.value.questionVersionId,
    expectedRowVersion: detail.value.rowVersion,
    clientMutationId: mutationIdFor(action),
  }
}

async function load(): Promise<void> {
  if (!Number.isSafeInteger(questionId.value) || questionId.value <= 0 || !Number.isSafeInteger(questionVersionId.value) || questionVersionId.value <= 0) {
    errors.value = ['题目地址无效，请返回列表重新选择']
    return
  }
  loading.value = true
  errors.value = []
  editing.value = false
  try {
    const [nextDetail, nextVersions, nextReviews, nextAudits] = await Promise.all([
      gateway.getQuestion(questionId.value, questionVersionId.value),
      gateway.listVersions(questionId.value),
      gateway.listReviews(questionId.value, questionVersionId.value),
      gateway.listAuditEvents(questionId.value),
    ])
    detail.value = nextDetail
    form.value = questionFormFromDetail(nextDetail)
    versions.value = nextVersions
    reviews.value = nextReviews
    audits.value = nextAudits
  } catch (error) {
    detail.value = null
    form.value = null
    errors.value = [resolveAdminQuestionError(error, '题目详情加载失败，请稍后重试')]
  } finally {
    loading.value = false
  }
}

async function saveRevision(): Promise<void> {
  if (!detail.value || !form.value || !canEdit.value) return
  errors.value = validateQuestionForm(form.value)
  if (errors.value.length) return
  actionLoading.value = 'save'
  try {
    const result = await gateway.createRevision(detail.value.questionId, {
      clientMutationId: mutationIdFor('save'),
      baseVersionId: detail.value.questionVersionId,
      payload: buildQuestionPayload(form.value),
      copyrightStatus: form.value.copyrightStatus,
      usageRestriction: form.value.usageRestriction,
    })
    finishMutation('save')
    ElMessage.success(result.replayed ? '已恢复此前保存结果' : '已保存为新草稿版本')
    await router.replace(`/admin/questions/${result.questionId}/versions/${result.questionVersionId}`)
    await load()
  } catch (error) {
    errors.value = [resolveAdminQuestionError(error, '保存草稿失败，请稍后重试')]
  } finally {
    actionLoading.value = ''
  }
}

async function runWorkflow(action: 'submit' | 'approve' | 'reject' | 'publish' | 'retire' | 'copy'): Promise<void> {
  if (!detail.value) return
  actionLoading.value = action
  errors.value = []
  try {
    const command = mutationCommand(action)
    const result = action === 'submit'
      ? await gateway.submitReview(command)
      : action === 'approve'
        ? await gateway.approveReview(command)
        : action === 'reject'
          ? await gateway.rejectReview({ ...command, reason: rejectReason.value.trim() })
          : action === 'publish'
            ? await gateway.publishQuestion(command)
            : action === 'retire'
              ? await gateway.retireQuestion(command)
              : await gateway.copyVersion(command)
    finishMutation(action)
    rejectOpen.value = false
    rejectReason.value = ''
    const messages = {
      submit: '已提交审核',
      approve: '已审核通过',
      reject: '已退回草稿',
      publish: '已发布题目',
      retire: '已退役题目',
      copy: '已复制为新版本草稿',
    }
    ElMessage.success(result.replayed ? '已恢复此前操作结果' : messages[action])
    if (result.questionVersionId !== questionVersionId.value) {
      await router.replace(`/admin/questions/${result.questionId}/versions/${result.questionVersionId}`)
    }
    await load()
  } catch (error) {
    errors.value = [resolveAdminQuestionError(error)]
  } finally {
    actionLoading.value = ''
  }
}

function openReject(): void {
  if (!canReview.value) return
  rejectReason.value = ''
  rejectOpen.value = true
}

function confirmReject(): void {
  if (!rejectReason.value.trim()) {
    ElMessage.warning('请填写审核退回原因')
    return
  }
  void runWorkflow('reject')
}

function reviewDisabledReason(): string {
  if (isSelfReview.value) return '禁止自审：提交人与审核人不能相同'
  if (!authStore.hasPermission('question:review')) return '当前账号没有审核权限'
  return '只有待审核题目可以审核'
}

watch([questionId, questionVersionId], () => { void load() })
onMounted(() => { void load() })
</script>

<template>
  <section class="question-detail-page" data-testid="admin-question-detail-page" :aria-busy="loading">
    <header class="detail-header">
      <div>
        <p>题库管理 / 题目 {{ questionId }} / 版本 {{ questionVersionId }}</p>
        <h1>{{ detail?.payload.title || '题目详情' }}</h1>
        <span>查看题目内容、执行审核流程，并追踪版本与操作记录。</span>
      </div>
      <el-button :icon="ArrowLeft" @click="router.push('/admin/questions')">返回题库</el-button>
    </header>

    <section v-if="detail" class="workflow-panel">
      <div class="workflow-summary">
        <span class="question-icon"><el-icon><QuestionFilled /></el-icon></span>
        <div><small>当前版本状态</small><strong>{{ statusLabel }}</strong><p>版本 v{{ detail.versionNo }} · 题目 ID {{ detail.questionId }} · 版本 ID {{ detail.questionVersionId }}</p></div>
      </div>
      <div class="detail-actions">
        <el-button v-if="canEdit && !editing" data-testid="edit-draft" @click="editing = true">编辑草稿</el-button>
        <el-button v-if="editing" @click="editing = false; form = detail ? questionFormFromDetail(detail) : form">取消编辑</el-button>
        <el-button v-if="editing" type="primary" :loading="actionLoading === 'save'" data-testid="save-revision" @click="saveRevision">保存新版本</el-button>
        <el-button :disabled="!canSubmit" :loading="actionLoading === 'submit'" data-testid="submit-review" @click="runWorkflow('submit')">提交审核</el-button>
        <el-tooltip :content="canReview ? '审核通过' : reviewDisabledReason()">
          <span><el-button type="success" :disabled="!canReview" :loading="actionLoading === 'approve'" data-testid="approve-review" @click="runWorkflow('approve')">审核通过</el-button></span>
        </el-tooltip>
        <el-tooltip :content="canReview ? '填写原因并退回' : reviewDisabledReason()">
          <span><el-button :disabled="!canReview" data-testid="reject-review" @click="openReject">审核退回</el-button></span>
        </el-tooltip>
        <el-tooltip :content="isSelfReview ? '提交人不能发布自己提交的版本' : '仅审核通过的待审核版本可以发布'">
          <span><el-button type="primary" :disabled="!canPublish" :loading="actionLoading === 'publish'" data-testid="publish-question" @click="runWorkflow('publish')">发布</el-button></span>
        </el-tooltip>
        <el-button :disabled="!canCopy" :loading="actionLoading === 'copy'" data-testid="copy-version" @click="runWorkflow('copy')">复制为新版本</el-button>
        <el-button type="danger" plain :disabled="!canRetire" :loading="actionLoading === 'retire'" data-testid="retire-question" @click="runWorkflow('retire')">退役</el-button>
      </div>
    </section>

    <el-alert v-if="isSelfReview" title="禁止自审：当前账号是本版本提交人。审核、退回和发布操作不可用。" type="warning" :closable="false" show-icon data-testid="self-review-warning" />
    <el-alert v-if="errors.length" type="error" :closable="false" show-icon data-testid="question-detail-errors">
      <template #title>{{ errors.join('；') }}</template>
    </el-alert>

    <section v-if="detail" class="detail-meta">
      <article><span class="meta-icon is-blue"><el-icon><DocumentCopy /></el-icon></span><div><small>版本信息</small><strong>v{{ detail.versionNo }}</strong><p>并发版本 {{ detail.rowVersion }}</p></div></article>
      <article><span class="meta-icon is-green"><el-icon><Clock /></el-icon></span><div><small>审核结论</small><strong>{{ decisionLabel }}</strong><p>{{ isSelfReview ? '当前账号不可自审' : '权限校验正常' }}</p></div></article>
      <article><span class="meta-icon is-purple"><el-icon><EditPen /></el-icon></span><div><small>版权状态</small><strong>{{ detail.copyrightStatus || 'UNVERIFIED' }}</strong><p>{{ detail.usageRestriction || 'INTERNAL_ONLY' }}</p></div></article>
    </section>

    <section class="editor-panel">
      <header class="section-heading"><div><small>题目内容</small><h2>{{ editing ? '编辑草稿版本' : '题目内容预览' }}</h2></div><span>{{ editing ? '保存时会生成新的草稿版本' : '当前为只读模式' }}</span></header>
      <div class="editor-body"><AdminQuestionEditor v-if="form" v-model="form" :readonly="!editing" lock-identity /><el-skeleton v-else :rows="8" animated /></div>
    </section>

    <div class="record-grid">
      <section class="record-panel" data-testid="version-history">
        <header class="record-heading"><div><small>版本轨迹</small><h2>版本历史</h2></div><span>{{ versions.length }} 个版本</span></header>
        <el-table :data="versions" size="small" empty-text="暂无版本记录">
          <el-table-column label="版本" width="70"><template #default="scope"><strong class="version-label">v{{ scope.row.versionNo }}</strong></template></el-table-column>
          <el-table-column label="状态" width="110"><template #default="scope"><span class="status-pill" :class="`is-${scope.row.status.toLowerCase()}`"><i></i>{{ statusLabels[scope.row.status] || scope.row.status }}</span></template></el-table-column>
          <el-table-column prop="rowVersion" label="行版本" width="80" />
          <el-table-column label="当前位置" min-width="150">
            <template #default="scope">
              {{ scope.row.deleted ? '已被新草稿替换' : scope.row.currentWorking ? '当前工作版本' : scope.row.currentPublished ? '当前发布版本' : '-' }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="scope">
              <el-button link type="primary" @click="router.push(`/admin/questions/${scope.row.questionId}/versions/${scope.row.questionVersionId}`)">查看</el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section class="record-panel" data-testid="review-records">
        <header class="record-heading"><div><small>审核轨迹</small><h2>审核记录</h2></div><span>{{ reviews.length }} 条记录</span></header>
        <el-table :data="reviews" size="small" empty-text="暂无审核记录">
          <el-table-column label="结果" width="110"><template #default="scope">{{ decisionLabels[scope.row.decision] || scope.row.decision }}</template></el-table-column>
          <el-table-column prop="reviewerId" label="审核人" width="100" />
          <el-table-column prop="reason" label="原因" min-width="180" />
          <el-table-column label="时间" min-width="170"><template #default="scope">{{ formatDateTime(scope.row.createdAt) }}</template></el-table-column>
        </el-table>
      </section>

      <section class="record-panel record-panel--wide" data-testid="audit-events">
        <header class="record-heading"><div><small>安全审计</small><h2>操作审计</h2></div><span>{{ audits.length }} 条事件</span></header>
        <el-table :data="audits" size="small" empty-text="暂无审计记录">
          <el-table-column prop="eventSequence" label="#" width="60" />
          <el-table-column prop="eventType" label="事件" min-width="160" />
          <el-table-column prop="actorUserId" label="操作人" width="100" />
          <el-table-column prop="questionVersionId" label="版本 ID" width="110" />
          <el-table-column prop="clientMutationId" label="幂等标识" min-width="220" />
          <el-table-column label="时间" min-width="170"><template #default="scope">{{ formatDateTime(scope.row.createdAt) }}</template></el-table-column>
        </el-table>
      </section>
    </div>

    <el-dialog v-model="rejectOpen" class="reject-dialog" title="审核退回" width="min(520px, calc(100vw - 32px))" align-center data-testid="reject-dialog">
      <label class="reject-field">
        <span>退回原因</span>
        <small>请明确指出需要修正的内容，方便录入人员快速处理。</small>
        <el-input v-model="rejectReason" type="textarea" :rows="5" maxlength="500" show-word-limit placeholder="填写题干、答案、解析或知识点等具体问题" data-testid="reject-reason" />
      </label>
      <template #footer>
        <el-button @click="rejectOpen = false">取消</el-button>
        <el-button type="primary" :loading="actionLoading === 'reject'" data-testid="confirm-reject" @click="confirmReject">确认退回</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.question-detail-page{display:grid;gap:18px;min-width:0;min-height:calc(100vh - var(--platform-header-height));padding:28px clamp(20px,3vw,46px) 48px;color:#172033;background:#f5f7fb}.detail-header,.workflow-panel,.workflow-summary,.detail-actions,.detail-meta article,.section-heading,.record-heading{display:flex;align-items:center}.detail-header,.workflow-panel,.section-heading,.record-heading{justify-content:space-between;gap:16px}.detail-header>div{display:grid;min-width:0;gap:3px}.detail-header p{margin:0;color:#76859b;font-size:11px}.detail-header h1{overflow:hidden;margin:0;font-size:28px;letter-spacing:-.02em;text-overflow:ellipsis;white-space:nowrap}.detail-header span{color:#7e8ba0;font-size:12px}.detail-header :deep(.el-button){min-height:38px;border-radius:9px}
.workflow-panel,.detail-meta article,.editor-panel,.record-panel{border:1px solid #dfe6f0;border-radius:16px;background:#fff;box-shadow:0 9px 28px rgba(31,45,75,.045)}.workflow-panel{padding:17px 20px}.workflow-summary{min-width:0;gap:12px}.question-icon{display:grid;width:42px;height:42px;flex:0 0 auto;place-items:center;border-radius:11px;color:#2563eb;background:#eaf1ff;font-size:20px}.workflow-summary>div{display:grid;min-width:0;gap:2px}.workflow-summary small,.detail-meta small,.section-heading small,.record-heading small{color:#8290a4;font-size:10px}.workflow-summary strong{color:#2a374d;font-size:17px}.workflow-summary p{margin:0;color:#8d98a9;font-size:10px}.detail-actions{flex-wrap:wrap;justify-content:flex-end;gap:8px}.detail-actions :deep(.el-button){margin:0;min-height:34px;border-radius:8px;font-size:11px}
.detail-meta{display:grid;grid-template-columns:repeat(3,1fr);gap:12px}.detail-meta article{gap:12px;padding:16px 18px}.meta-icon{display:grid;width:38px;height:38px;flex:0 0 auto;place-items:center;border-radius:10px}.meta-icon.is-blue{color:#2563eb;background:#eaf1ff}.meta-icon.is-green{color:#159166;background:#e8f8f1}.meta-icon.is-purple{color:#7354d8;background:#f0edff}.detail-meta article>div{display:grid;min-width:0;gap:2px}.detail-meta strong{overflow:hidden;color:#344057;font-size:13px;text-overflow:ellipsis;white-space:nowrap}.detail-meta p{overflow:hidden;margin:0;color:#96a1b1;font-size:10px;text-overflow:ellipsis;white-space:nowrap}
.editor-panel{min-width:0;overflow:hidden}.section-heading{min-height:67px;padding:0 20px;border-bottom:1px solid #edf0f5}.section-heading>div,.record-heading>div{display:grid;gap:2px}.section-heading h2,.record-heading h2{margin:0;color:#29364c;font-size:15px}.section-heading>span,.record-heading>span{color:#8290a4;font-size:10px}.editor-body{min-width:0;padding:20px;background:#fff}.editor-body :deep(.question-editor){box-shadow:none}
.record-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.record-panel{min-width:0;overflow:hidden}.record-panel--wide{grid-column:1/-1}.record-heading{min-height:62px;padding:0 16px;border-bottom:1px solid #edf0f5}.record-panel :deep(.el-table th.el-table__cell){height:42px;color:#6b788c;background:#f8f9fb;font-size:10px}.record-panel :deep(.el-table td.el-table__cell){height:52px;color:#46536a;font-size:11px}.record-panel :deep(.el-table__inner-wrapper::before){display:none}.version-label{color:#315fae;font-size:11px}.status-pill{display:inline-flex;align-items:center;gap:5px;padding:5px 8px;border-radius:999px;font-size:9px;font-weight:650}.status-pill i{width:6px;height:6px;border-radius:50%;background:currentColor}.status-pill.is-draft{color:#a46f1d;background:#fff6e5}.status-pill.is-in_review{color:#2563eb;background:#eaf1ff}.status-pill.is-published{color:#168653;background:#e9f8f0}.status-pill.is-retired{color:#7b8798;background:#f0f3f6}
.reject-field{display:grid;gap:7px}.reject-field>span{color:#344057;font-size:13px;font-weight:650}.reject-field>small{margin-bottom:5px;color:#8290a4;font-size:11px}:global(.reject-dialog.el-dialog){border-radius:16px;box-shadow:0 24px 70px rgba(15,23,42,.18)}
@media(max-width:1200px){.workflow-panel{align-items:flex-start;flex-direction:column}.detail-actions{width:100%;justify-content:flex-start}.record-grid{grid-template-columns:1fr}.record-panel--wide{grid-column:auto}}
@media(max-width:700px){.question-detail-page{padding:20px 14px 36px}.detail-header{align-items:flex-start;flex-direction:column}.detail-header :deep(.el-button){width:100%}.workflow-summary{align-items:flex-start}.detail-actions{display:grid;grid-template-columns:1fr 1fr}.detail-actions>span,.detail-actions :deep(.el-button){width:100%}.detail-meta{grid-template-columns:1fr}.section-heading{align-items:flex-start;flex-direction:column;padding:14px 16px}.editor-body{padding:12px}}
</style>
