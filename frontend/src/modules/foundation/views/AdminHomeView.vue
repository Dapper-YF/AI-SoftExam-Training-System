<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowRight,
  Checked,
  CircleCheck,
  Clock,
  Document,
  EditPen,
  Files,
  FolderOpened,
  Plus,
  Refresh,
  UploadFilled,
  UserFilled,
} from '@element-plus/icons-vue'
import { getAdminQuestionGateway } from '@/modules/admin/questions/api/gateway'
import { resolveAdminQuestionError } from '@/modules/admin/questions/model/error-messages'
import type { AdminQuestionDashboard, AdminQuestionType } from '@/modules/admin/questions/types'
import { useAuthStore } from '@/stores/auth'
import { useQualificationStore } from '@/stores/qualification'

const typeLabels: Record<AdminQuestionType, string> = {
  SINGLE_CHOICE: '单选题',
  MULTI_CHOICE: '多选题',
  JUDGE: '判断题',
  CASE: '案例分析题',
  ESSAY: '论文题',
}

const router = useRouter()
const authStore = useAuthStore()
const qualificationStore = useQualificationStore()
const gateway = getAdminQuestionGateway()
const loading = ref(false)
const errorMessage = ref('')
const dashboard = ref<AdminQuestionDashboard | null>(null)

const identityText = computed(() => authStore.user?.displayName || authStore.user?.username || '管理账号')
const qualificationLabels = computed(() => new Map(
  qualificationStore.qualifications.map(item => [item.id, item.name]),
))

const metrics = computed(() => {
  const value = dashboard.value
  return [
    { label: '题目总数', value: value?.totalCount ?? 0, query: {}, icon: Files, tone: 'blue', note: '全部题库内容' },
    { label: '草稿', value: value?.draftCount ?? 0, query: { status: 'DRAFT' }, icon: EditPen, tone: 'slate', note: '尚未提交审核' },
    { label: '待审核', value: value?.inReviewCount ?? 0, query: { status: 'IN_REVIEW' }, icon: Clock, tone: 'amber', note: '需要及时处理' },
    { label: '已发布', value: value?.publishedCount ?? 0, query: { status: 'PUBLISHED' }, icon: CircleCheck, tone: 'green', note: '当前有效题目' },
    { label: '已退役', value: value?.retiredCount ?? 0, query: { status: 'RETIRED' }, icon: FolderOpened, tone: 'purple', note: '停止对外使用' },
  ]
})
const importStatusLabels: Record<string, string> = {
  PREFLIGHT_FAILED: '预检失败', READY_TO_CONFIRM: '待确认', CONFIRMED: '已确认',
  PENDING_REVIEW: '待审核', PUBLISHED: '已发布', REJECTED: '已驳回',
  COMPLETED: '已完成', FAILED: '处理失败', ROLLED_BACK: '已回滚',
}

const quickActions = computed(() => [
  { label: '录入新题', description: '创建并编辑一道新题目', path: '/admin/questions/new', permission: 'question:create', icon: Plus, tone: 'blue' },
  { label: '批量导入', description: '通过标准模板批量入库', path: '/admin/question-imports', permission: 'question:import', icon: UploadFilled, tone: 'green' },
  { label: '待审核任务', description: '处理提交审核的题目', path: '/admin/questions?status=IN_REVIEW', permission: 'question:review', icon: Checked, tone: 'amber' },
  { label: '已发布题库', description: '浏览当前线上题目', path: '/admin/questions?status=PUBLISHED', permission: 'question:read', icon: Document, tone: 'purple' },
].filter(item => authStore.hasPermission(item.permission)))

const publishedRate = computed(() => {
  if (!dashboard.value?.totalCount) return 0
  return Math.round((dashboard.value.publishedCount / dashboard.value.totalCount) * 100)
})

function qualificationName(key: string): string {
  return qualificationLabels.value.get(key) ?? `资格 ${key}`
}

function typeName(key: string): string {
  return typeLabels[key as AdminQuestionType] ?? key
}

async function loadDashboard(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    dashboard.value = await gateway.getDashboard()
  } catch (error) {
    dashboard.value = null
    errorMessage.value = resolveAdminQuestionError(error, '题库工作台加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

function open(path: string): void {
  void router.push(path)
}

onMounted(() => { void loadDashboard() })
</script>

<template>
  <section class="admin-workbench" data-testid="admin-home-page">
    <header class="workbench-header">
      <div>
        <p class="header-eyebrow">题库运营后台</p>
        <h1>管理工作台</h1>
        <span>集中处理题目录入、审核发布与批量导入任务</span>
      </div>
      <div class="header-actions">
        <el-button class="refresh-button" :loading="loading" @click="loadDashboard">
          <el-icon><Refresh /></el-icon>刷新数据
        </el-button>
        <div class="identity" data-testid="admin-identity-strip">
          <span class="identity-icon"><el-icon><UserFilled /></el-icon></span>
          <p><small>当前管理员</small><strong>{{ identityText }}</strong></p>
          <em>{{ authStore.roles.join('、') }}</em>
        </div>
      </div>
    </header>

    <el-alert
      v-if="errorMessage"
      :title="errorMessage"
      type="error"
      :closable="false"
      show-icon
      data-testid="admin-home-error"
    >
      <template #default>
        <el-button size="small" data-testid="admin-home-retry" @click="loadDashboard">
          <el-icon><Refresh /></el-icon>重试
        </el-button>
      </template>
    </el-alert>

    <main v-else v-loading="loading" class="workbench-body" :aria-busy="loading">
      <section class="metrics" data-testid="question-status-summary" aria-label="题目状态统计">
        <button
          v-for="item in metrics"
          :key="item.label"
          type="button"
          :class="`tone-${item.tone}`"
          @click="router.push({ path: '/admin/questions', query: item.query })"
        >
          <span class="metric-icon"><el-icon><component :is="item.icon" /></el-icon></span>
          <p><small>{{ item.label }}</small><strong>{{ item.value }}</strong><em>{{ item.note }}</em></p>
          <el-icon class="metric-arrow"><ArrowRight /></el-icon>
        </button>
      </section>

      <section class="workspace-grid">
        <div class="workspace-main">
          <section class="workbench-section review-section" data-testid="pending-review-tasks">
            <header>
              <div>
                <span class="section-icon section-icon--amber"><el-icon><Clock /></el-icon></span>
                <div class="section-title-copy"><small>审核工作流</small><h2>待处理审核</h2></div>
              </div>
              <button type="button" @click="open('/admin/questions?status=IN_REVIEW')">查看全部<el-icon><ArrowRight /></el-icon></button>
            </header>
            <el-table :data="dashboard?.pendingReviews ?? []" empty-text="暂无待审核题目">
              <el-table-column prop="title" label="题目" min-width="260" show-overflow-tooltip />
              <el-table-column label="题型" width="130"><template #default="scope"><span class="table-tag">{{ typeName(scope.row.questionType) }}</span></template></el-table-column>
              <el-table-column prop="versionNo" label="版本" width="80"><template #default="scope">V{{ scope.row.versionNo }}</template></el-table-column>
              <el-table-column label="操作" width="90" align="right">
                <template #default="scope">
                  <el-button link type="primary" @click="open(`/admin/questions/${scope.row.questionId}/versions/${scope.row.questionVersionId}`)">处理</el-button>
                </template>
              </el-table-column>
            </el-table>
          </section>

          <section class="workbench-section" data-testid="recent-import-state">
            <header>
              <div>
                <span class="section-icon section-icon--green"><el-icon><UploadFilled /></el-icon></span>
                <div class="section-title-copy"><small>数据导入</small><h2>最近导入批次</h2></div>
              </div>
              <button type="button" @click="open('/admin/question-imports')">查看全部<el-icon><ArrowRight /></el-icon></button>
            </header>
            <el-table :data="dashboard?.recentImports ?? []" empty-text="暂无导入批次">
              <el-table-column prop="batchId" label="批次" width="90"><template #default="scope">#{{ scope.row.batchId }}</template></el-table-column>
              <el-table-column prop="status" label="状态" width="120"><template #default="scope"><span class="table-status">{{ importStatusLabels[scope.row.status] ?? '未知状态' }}</span></template></el-table-column>
              <el-table-column prop="totalCount" label="总数" width="80" />
              <el-table-column prop="importedCount" label="已导入" width="90" />
              <el-table-column prop="createdAt" label="创建时间" min-width="180" />
              <el-table-column label="操作" width="90" align="right">
                <template #default="scope"><el-button link type="primary" @click="open(`/admin/question-imports/${scope.row.batchId}`)">详情</el-button></template>
              </el-table-column>
            </el-table>
          </section>
        </div>

        <aside class="workspace-side">
          <section class="quick-panel">
            <header><div><small>常用操作</small><h2>快捷入口</h2></div><span>{{ quickActions.length }} 项</span></header>
            <nav class="quick-actions" aria-label="题库快捷入口" data-testid="admin-quick-actions">
              <button v-for="item in quickActions" :key="item.path" type="button" @click="open(item.path)">
                <span :class="`quick-icon tone-${item.tone}`"><el-icon><component :is="item.icon" /></el-icon></span>
                <p><strong>{{ item.label }}</strong><small>{{ item.description }}</small></p>
                <el-icon><ArrowRight /></el-icon>
              </button>
            </nav>
          </section>

          <section class="health-panel">
            <div class="health-ring" :style="{ '--progress': `${publishedRate * 3.6}deg` }"><strong>{{ publishedRate }}%</strong><span>发布占比</span></div>
            <div><small>题库健康度</small><h3>当前内容状态良好</h3><p>已发布题目占全部题目的比例</p></div>
          </section>
        </aside>
      </section>

      <section class="workbench-section distribution" data-testid="question-distribution">
        <header>
          <div>
            <span class="section-icon"><el-icon><Files /></el-icon></span>
              <div class="section-title-copy"><small>内容结构</small><h2>题库分布</h2></div>
          </div>
          <span>按资格与题型统计</span>
        </header>
        <div class="distribution-grid">
          <div>
            <h3>资格分布</h3>
            <ul>
              <li v-for="item in dashboard?.byQualification" :key="item.key">
                <span>{{ item.label || qualificationName(item.key) }}</span>
                <i><b :style="{ width: `${Math.min(100, (item.count / Math.max(1, dashboard?.totalCount ?? 1)) * 100)}%` }"></b></i>
                <strong>{{ item.count }}</strong>
              </li>
              <li v-if="!dashboard?.byQualification.length" class="empty">暂无数据</li>
            </ul>
          </div>
          <div>
            <h3>题型分布</h3>
            <ul>
              <li v-for="item in dashboard?.byQuestionType" :key="item.key">
                <span>{{ item.label || typeName(item.key) }}</span>
                <i><b :style="{ width: `${Math.min(100, (item.count / Math.max(1, dashboard?.totalCount ?? 1)) * 100)}%` }"></b></i>
                <strong>{{ item.count }}</strong>
              </li>
              <li v-if="!dashboard?.byQuestionType.length" class="empty">暂无数据</li>
            </ul>
          </div>
        </div>
      </section>
    </main>
  </section>
</template>

<style scoped>
.admin-workbench {
  min-height: calc(100vh - var(--platform-header-height));
  padding: 28px var(--platform-page-padding) 48px;
  background:
    radial-gradient(circle at 90% 2%, rgb(37 99 235 / 5%), transparent 28rem),
    var(--color-page-canvas);
  color: #172033;
}
.workbench-header, .workbench-body, .admin-workbench > .el-alert { max-width: 1400px; margin-right: auto; margin-left: auto; }
.workbench-header { display: flex; margin-bottom: 22px; align-items: flex-end; justify-content: space-between; gap: 24px; }
.header-eyebrow { margin: 0; color: #2563eb; font-size: 11px; font-weight: 700; letter-spacing: .1em; }
.workbench-header h1 { margin: 6px 0 0; color: #172033; font-size: 29px; letter-spacing: -.025em; }
.workbench-header > div:first-child > span { display: block; margin-top: 7px; color: #64748b; font-size: 12px; }
.header-actions { display: flex; align-items: center; gap: 10px; }
.refresh-button { min-height: 42px; border-color: #dbe4f0; border-radius: 11px; color: #475569; }
.identity { display: flex; min-height: 54px; padding: 7px 10px; align-items: center; gap: 10px; border: 1px solid #dbe4f0; border-radius: 13px; background: white; box-shadow: 0 7px 20px rgb(15 23 42 / 4%); }
.identity-icon { display: grid; width: 36px; height: 36px; place-items: center; border-radius: 10px; background: #eff6ff; color: #2563eb; }
.identity p { display: grid; margin: 0; gap: 2px; }
.identity small { color: #94a3b8; font-size: 9px; }
.identity strong { color: #334155; font-size: 12px; }
.identity em { padding: 4px 6px; border-radius: 6px; background: #f1f5f9; color: #64748b; font-size: 8px; font-style: normal; }
.admin-workbench > .el-alert { margin-bottom: 16px; border-radius: 11px; }
.workbench-body { display: grid; gap: 18px; }
.metrics { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 12px; }
.metrics button { position: relative; display: flex; min-width: 0; min-height: 112px; padding: 17px; align-items: center; gap: 12px; overflow: hidden; border: 1px solid #e0e7f0; border-radius: 14px; background: white; cursor: pointer; text-align: left; transition: .18s ease; }
.metrics button:hover { border-color: #b9cdf0; box-shadow: 0 12px 28px rgb(30 64 175 / 8%); transform: translateY(-2px); }
.metric-icon, .section-icon, .quick-icon { display: grid; flex: 0 0 auto; place-items: center; }
.metric-icon { width: 42px; height: 42px; border-radius: 12px; background: #eff6ff; color: #2563eb; font-size: 19px; }
.metrics p { display: grid; min-width: 0; margin: 0; gap: 2px; }
.metrics small { color: #64748b; font-size: 10px; }
.metrics strong { color: #172033; font-size: 24px; line-height: 1.15; }
.metrics em { overflow: hidden; color: #a0acba; font-size: 8px; font-style: normal; text-overflow: ellipsis; white-space: nowrap; }
.metric-arrow { position: absolute; top: 12px; right: 11px; color: #cbd5e1; font-size: 12px; }
.tone-slate .metric-icon { background: #f1f5f9; color: #64748b; }
.tone-amber .metric-icon, .quick-icon.tone-amber { background: #fff7ed; color: #ea580c; }
.tone-green .metric-icon, .quick-icon.tone-green { background: #ecfdf5; color: #059669; }
.tone-purple .metric-icon, .quick-icon.tone-purple { background: #f5f3ff; color: #7c3aed; }
.workspace-grid { display: grid; grid-template-columns: minmax(0, 1.55fr) minmax(300px, .65fr); gap: 18px; }
.workspace-main, .workspace-side { display: grid; align-content: start; gap: 18px; }
.workbench-section, .quick-panel, .health-panel { min-width: 0; border: 1px solid #e0e7f0; border-radius: 15px; background: white; box-shadow: 0 10px 28px rgb(15 23 42 / 4%); }
.workbench-section { padding: 21px 23px; }
.workbench-section > header, .quick-panel > header { display: flex; margin-bottom: 16px; align-items: center; justify-content: space-between; gap: 15px; }
.workbench-section header > div { display: flex; align-items: center; gap: 11px; }
.section-title-copy { display: grid; gap: 3px; }
.workbench-section h2, .quick-panel h2 { margin: 0; color: #1e293b; font-size: 15px; }
.workbench-section header small, .quick-panel header small { color: #94a3b8; font-size: 9px; }
.section-icon { width: 37px; height: 37px; border-radius: 11px; background: #eff6ff; color: #2563eb; font-size: 17px; }
.section-icon--amber { background: #fff7ed; color: #ea580c; }
.section-icon--green { background: #ecfdf5; color: #059669; }
.workbench-section header button { display: inline-flex; padding: 5px 0; align-items: center; gap: 4px; border: 0; background: transparent; color: #2563eb; cursor: pointer; font-size: 10px; }
.workbench-section > header > span { color: #94a3b8; font-size: 10px; }
.workbench-section :deep(.el-table) { --el-table-border-color: #edf1f6; --el-table-header-bg-color: #f8fafc; --el-table-row-hover-bg-color: #f7faff; border-radius: 9px; color: #475569; font-size: 11px; }
.workbench-section :deep(.el-table th.el-table__cell) { color: #64748b; font-size: 10px; font-weight: 650; }
.table-tag { padding: 4px 7px; border-radius: 6px; background: #eff6ff; color: #2563eb; font-size: 9px; }
.table-status { padding: 4px 7px; border-radius: 6px; background: #ecfdf5; color: #047857; font-size: 9px; }
.quick-panel { padding: 20px; }
.quick-panel > header { margin-bottom: 12px; }
.quick-panel header > div { display: grid; gap: 3px; }
.quick-panel header > span { color: #94a3b8; font-size: 9px; }
.quick-actions { display: grid; gap: 7px; }
.quick-actions button { display: grid; min-height: 65px; padding: 10px; grid-template-columns: 38px minmax(0, 1fr) auto; align-items: center; gap: 10px; border: 1px solid transparent; border-radius: 11px; background: #f8fafc; color: #334155; cursor: pointer; text-align: left; transition: .18s ease; }
.quick-actions button:hover { border-color: #d7e4f8; background: #f4f8ff; transform: translateX(2px); }
.quick-icon { width: 38px; height: 38px; border-radius: 10px; background: #eff6ff; color: #2563eb; font-size: 17px; }
.quick-actions p { display: grid; margin: 0; gap: 4px; }
.quick-actions strong { font-size: 11px; }
.quick-actions small { color: #94a3b8; font-size: 8px; }
.quick-actions button > .el-icon { color: #cbd5e1; font-size: 11px; }
.health-panel { display: flex; padding: 19px; align-items: center; gap: 16px; background: linear-gradient(135deg, #172554, #1e40af); color: white; }
.health-ring { display: grid; width: 78px; height: 78px; flex: 0 0 auto; place-content: center; border-radius: 50%; background: radial-gradient(circle closest-side, #1d3f93 77%, transparent 79%), conic-gradient(#60a5fa var(--progress), rgb(255 255 255 / 13%) 0); text-align: center; }
.health-ring strong { font-size: 17px; }
.health-ring span { color: #bfdbfe; font-size: 7px; }
.health-panel > div:last-child small { color: #93c5fd; font-size: 8px; letter-spacing: .08em; }
.health-panel h3 { margin: 5px 0; font-size: 12px; }
.health-panel p { margin: 0; color: #bfdbfe; font-size: 8px; line-height: 1.5; }
.distribution { padding-bottom: 24px; }
.distribution-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 35px; }
.distribution h3 { margin: 0 0 10px; color: #475569; font-size: 11px; }
.distribution ul { display: grid; margin: 0; padding: 0; gap: 4px; list-style: none; }
.distribution li { display: grid; min-height: 31px; padding: 5px 0; grid-template-columns: minmax(120px, .8fr) minmax(100px, 1fr) 38px; align-items: center; gap: 12px; color: #64748b; font-size: 10px; }
.distribution li > span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.distribution li i { height: 5px; overflow: hidden; border-radius: 999px; background: #edf2f7; }
.distribution li i b { display: block; height: 100%; border-radius: inherit; background: linear-gradient(90deg, #60a5fa, #2563eb); }
.distribution li strong { color: #334155; text-align: right; }
.distribution .empty { display: block; color: #94a3b8; }
@media (max-width: 1100px) {
  .metrics { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .workspace-grid { grid-template-columns: 1fr; }
  .workspace-side { grid-template-columns: 1fr 1fr; }
}
@media (max-width: 760px) {
  .admin-workbench { padding: 22px 14px 36px; }
  .workbench-header { align-items: flex-start; flex-direction: column; }
  .header-actions { width: 100%; }
  .identity { margin-left: auto; }
  .metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .metrics button:first-child { grid-column: 1 / -1; }
  .workspace-side, .distribution-grid { grid-template-columns: 1fr; }
  .workbench-section { padding: 18px 14px; overflow: hidden; }
  .distribution li { grid-template-columns: minmax(100px, .8fr) minmax(80px, 1fr) 32px; }
}
@media (max-width: 480px) {
  .refresh-button { width: 42px; padding: 0; }
  .refresh-button :deep(span) { display: none; }
  .identity { flex: 1; }
  .metrics { grid-template-columns: 1fr 1fr; }
  .metrics button { min-height: 102px; padding: 13px; }
  .metric-icon { width: 36px; height: 36px; }
  .metrics strong { font-size: 21px; }
  .health-panel { display: none; }
}
</style>
