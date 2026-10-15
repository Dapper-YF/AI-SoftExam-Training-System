<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Calendar, CircleCheck, Clock, Refresh, Tickets } from '@element-plus/icons-vue'
import { platformApiClient, unwrapApiResponse } from '@/api/client'
import type { RegionalSchedule, ScheduleNodeType } from '@/modules/foundation/api/regional-schedule'

const regionCode = ref('GD')
const examPeriodCode = ref('2026-H2')
const items = ref<RegionalSchedule[]>([])
const loading = ref(false)
const savingType = ref<ScheduleNodeType | null>(null)
const publishingId = ref<string | null>(null)
const error = ref('')

const regions = [
  ['BJ', '北京市'], ['SH', '上海市'], ['GD', '广东省'], ['JS', '江苏省'], ['ZJ', '浙江省'],
  ['SC', '四川省'], ['HB', '湖北省'], ['HN', '湖南省'], ['SD', '山东省'], ['HA', '河南省'],
]
const nodeTypes: Array<{ value: ScheduleNodeType; label: string; description: string }> = [
  { value: 'REGISTRATION', label: '报名时间', description: '考生开放报名的官方时间' },
  { value: 'ADMISSION_TICKET', label: '准考证打印', description: '准考证下载与打印开放时间' },
  { value: 'EXAM', label: '考试时间', description: '本考期正式考试时间' },
  { value: 'RESULT', label: '成绩查询', description: '考试成绩开放查询时间' },
]

const selectedRegionName = computed(() => regions.find(item => item[0] === regionCode.value)?.[1] ?? regionCode.value)
const publishedCount = computed(() => items.value.filter(item => item.status === 'PUBLISHED').length)
const configuredCount = computed(() => nodeTypes.filter(node => Boolean(itemFor(node.value)?.scheduledAt)).length)

function itemFor(type: ScheduleNodeType): RegionalSchedule | undefined {
  return items.value.find(item => item.nodeType === type)
}
function statusLabel(status?: string): string {
  return status === 'PUBLISHED' ? '已发布' : status === 'OFFLINE' ? '已撤回' : '草稿'
}
function formatDateTime(value?: string | null): string {
  if (!value) return '尚未设置'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', { hour12: false })
}
function messageOf(reason: unknown, fallback: string): string {
  return reason instanceof Error && reason.message ? reason.message : fallback
}
async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    items.value = await unwrapApiResponse<RegionalSchedule[]>(platformApiClient.get('/admin/exam-schedules', {
      params: { regionCode: regionCode.value, examPeriodCode: examPeriodCode.value },
    }))
  } catch (reason) {
    items.value = []
    error.value = messageOf(reason, '考试时间加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}
async function save(type: ScheduleNodeType, value: string | null): Promise<void> {
  savingType.value = type
  try {
    await unwrapApiResponse(platformApiClient.put('/admin/exam-schedules', {
      regionCode: regionCode.value,
      examPeriodCode: examPeriodCode.value,
      nodeType: type,
      scheduledAt: value ? new Date(value).toISOString() : null,
    }))
    ElMessage.success('时间节点已保存')
    await load()
  } catch (reason) {
    ElMessage.error(messageOf(reason, '保存失败，请稍后重试'))
  } finally {
    savingType.value = null
  }
}
async function toggle(item: RegionalSchedule, publish: boolean): Promise<void> {
  publishingId.value = item.id
  try {
    await unwrapApiResponse(platformApiClient.post(`/admin/exam-schedules/${item.id}/${publish ? 'publish' : 'offline'}`))
    ElMessage.success(publish ? '时间节点已发布' : '时间节点已撤回')
    await load()
  } catch (reason) {
    ElMessage.error(messageOf(reason, '状态更新失败，请稍后重试'))
  } finally {
    publishingId.value = null
  }
}
function switchScope(): void {
  void load()
}

onMounted(() => void load())
</script>

<template>
  <section class="schedule-admin">
    <header class="page-header">
      <div>
        <p>考试运营 / 地区时间</p>
        <h1>考试安排管理</h1>
        <span>按地区和考期维护报名、准考证、考试及成绩查询时间。</span>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新安排</el-button>
    </header>

    <section class="scope-panel">
      <div class="scope-heading">
        <span class="scope-icon"><el-icon><Calendar /></el-icon></span>
        <div><small>当前维护范围</small><strong>{{ selectedRegionName }} · {{ examPeriodCode }}</strong><p>切换范围后将自动载入该地区对应考期的时间节点。</p></div>
      </div>
      <div class="scope-controls">
        <label>考试地区<el-select v-model="regionCode" @change="switchScope"><el-option v-for="item in regions" :key="item[0]" :value="item[0]" :label="item[1]" /></el-select></label>
        <label>考试批次<el-input v-model="examPeriodCode" placeholder="例如 2026-H2" @change="switchScope" @keyup.enter="switchScope" /></label>
      </div>
    </section>

    <section class="schedule-overview">
      <article><span class="metric-icon is-blue"><el-icon><Tickets /></el-icon></span><div><small>时间节点</small><strong>{{ configuredCount }}<em>/ 4</em></strong><p>已完成时间配置</p></div></article>
      <article><span class="metric-icon is-green"><el-icon><CircleCheck /></el-icon></span><div><small>发布进度</small><strong>{{ publishedCount }}<em>/ 4</em></strong><p>已对用户开放</p></div></article>
      <article><span class="metric-icon is-purple"><el-icon><Clock /></el-icon></span><div><small>维护状态</small><strong class="text-value">{{ configuredCount === 4 ? '时间完整' : '待补充' }}</strong><p>{{ configuredCount === 4 ? '所有节点均已设置' : `还有 ${4 - configuredCount} 个节点未设置` }}</p></div></article>
    </section>

    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />

    <section class="timeline-panel">
      <header class="panel-heading">
        <div><small>官方时间线</small><h2>{{ selectedRegionName }}考试节点</h2></div>
        <span>修改时间后自动保存，发布后用户端可见</span>
      </header>

      <div v-loading="loading" class="node-list">
        <article v-for="(node, index) in nodeTypes" :key="node.value" class="schedule-node">
          <div class="node-sequence"><span>{{ index + 1 }}</span><i v-if="index < nodeTypes.length - 1"></i></div>
          <div class="node-info"><strong>{{ node.label }}</strong><small>{{ node.description }}</small></div>
          <div class="node-time">
            <small>官方时间</small>
            <el-date-picker
              :model-value="itemFor(node.value)?.scheduledAt ?? null"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ss"
              placeholder="选择日期和时间"
              :disabled="savingType === node.value"
              @change="(value: string | null) => save(node.value, value)"
            />
            <span>{{ formatDateTime(itemFor(node.value)?.scheduledAt) }}</span>
          </div>
          <div class="node-status">
            <small>展示状态</small>
            <span class="status-pill" :class="`is-${(itemFor(node.value)?.status ?? 'DRAFT').toLowerCase()}`"><i></i>{{ statusLabel(itemFor(node.value)?.status) }}</span>
          </div>
          <div class="node-action">
            <el-button
              v-if="itemFor(node.value)"
              :type="itemFor(node.value)?.status === 'PUBLISHED' ? 'default' : 'primary'"
              :plain="itemFor(node.value)?.status === 'PUBLISHED'"
              :loading="publishingId === itemFor(node.value)?.id"
              @click="toggle(itemFor(node.value)!, itemFor(node.value)?.status !== 'PUBLISHED')"
            >
              {{ itemFor(node.value)?.status === 'PUBLISHED' ? '撤回展示' : '发布节点' }}
            </el-button>
            <span v-else>请先设置时间</span>
          </div>
        </article>
      </div>
    </section>
  </section>
</template>

<style scoped>
.schedule-admin{display:grid;gap:18px;min-width:0;min-height:calc(100vh - var(--platform-header-height));padding:28px clamp(20px,3vw,46px) 48px;color:#172033;background:#f5f7fb}.page-header,.scope-panel,.scope-heading,.scope-controls,.schedule-overview,.schedule-overview article,.panel-heading,.schedule-node{display:flex;align-items:center}.page-header,.scope-panel,.panel-heading{justify-content:space-between;gap:18px}.page-header>div{display:grid;gap:3px}.page-header p{margin:0;color:#76859b;font-size:11px}.page-header h1{margin:0;font-size:28px;letter-spacing:-.02em}.page-header span{color:#7e8ba0;font-size:12px}.page-header :deep(.el-button){min-height:38px;border-radius:9px}
.scope-panel,.schedule-overview article,.timeline-panel{border:1px solid #dfe6f0;border-radius:16px;background:#fff;box-shadow:0 9px 28px rgba(31,45,75,.045)}.scope-panel{padding:18px 22px}.scope-heading{min-width:0;gap:12px}.scope-icon{display:grid;width:42px;height:42px;flex:0 0 auto;place-items:center;border-radius:11px;color:#2563eb;background:#eaf1ff;font-size:20px}.scope-heading>div{display:grid;min-width:0;gap:2px}.scope-heading small,.scope-controls label,.schedule-overview small,.panel-heading small,.node-time>small,.node-status>small{color:#8290a4;font-size:10px}.scope-heading strong{color:#2a374d;font-size:16px}.scope-heading p{margin:0;color:#8b97a9;font-size:10px}.scope-controls{flex:0 0 auto;gap:10px}.scope-controls label{display:grid;gap:5px}.scope-controls :deep(.el-select){width:150px}.scope-controls :deep(.el-input){width:150px}
.schedule-overview{display:grid;grid-template-columns:repeat(3,1fr);gap:12px}.schedule-overview article{gap:12px;padding:17px 18px}.metric-icon{display:grid;width:40px;height:40px;flex:0 0 auto;place-items:center;border-radius:11px}.metric-icon.is-blue{color:#2563eb;background:#eaf1ff}.metric-icon.is-green{color:#159166;background:#e8f8f1}.metric-icon.is-purple{color:#7354d8;background:#f0edff}.schedule-overview article>div{display:grid;gap:2px}.schedule-overview strong{color:#2d3b53;font-size:21px}.schedule-overview strong em{color:#9ca7b7;font-size:11px;font-style:normal;font-weight:500}.schedule-overview .text-value{font-size:16px}.schedule-overview p{margin:0;color:#96a1b1;font-size:10px}
.timeline-panel{min-width:0;overflow:hidden}.panel-heading{min-height:68px;padding:0 20px;border-bottom:1px solid #edf0f5}.panel-heading>div{display:grid;gap:2px}.panel-heading h2{margin:0;color:#29364c;font-size:15px}.panel-heading>span{color:#8c98a9;font-size:10px}.node-list{min-height:300px;padding:0 20px}.schedule-node{position:relative;min-height:104px;gap:16px;border-bottom:1px solid #edf0f5}.schedule-node:last-child{border-bottom:0}.node-sequence{position:relative;display:grid;width:34px;align-self:stretch;flex:0 0 auto;place-items:center}.node-sequence span{position:relative;z-index:2;display:grid;width:28px;height:28px;place-items:center;border:4px solid #f2f6fc;border-radius:50%;color:#2563eb;background:#dfeaff;font-size:10px;font-weight:700}.node-sequence i{position:absolute;top:58%;bottom:-42%;width:1px;background:#dce4ef}.node-info{display:grid;width:170px;flex:0 0 auto;gap:4px}.node-info strong{color:#344057;font-size:13px}.node-info small,.node-time>span,.node-action>span{color:#929dae;font-size:10px}.node-time{display:grid;min-width:240px;flex:1;gap:5px}.node-time :deep(.el-date-editor){width:230px}.node-status{display:grid;width:100px;gap:7px}.status-pill{display:inline-flex;width:max-content;align-items:center;gap:5px;padding:5px 8px;border-radius:999px;font-size:10px;font-weight:650}.status-pill i{width:6px;height:6px;border-radius:50%;background:currentColor}.status-pill.is-published{color:#168653;background:#e9f8f0}.status-pill.is-offline{color:#7b8798;background:#f0f3f6}.status-pill.is-draft{color:#a46f1d;background:#fff6e5}.node-action{width:100px;text-align:right}.node-action :deep(.el-button){min-width:88px;border-radius:8px}
@media(max-width:1050px){.scope-panel{align-items:flex-start;flex-direction:column}.scope-controls{width:100%}.scope-controls label{flex:1}.scope-controls :deep(.el-select),.scope-controls :deep(.el-input){width:100%}.schedule-node{flex-wrap:wrap;padding:16px 0}.node-sequence{min-height:70px}.node-sequence i{display:none}.node-info{width:calc(100% - 50px)}.node-time{margin-left:50px}.node-status{width:110px}.node-action{width:100px}}
@media(max-width:700px){.schedule-admin{padding:20px 14px 36px}.page-header{align-items:flex-start;flex-direction:column}.page-header :deep(.el-button){width:100%}.scope-controls{align-items:stretch;flex-direction:column}.schedule-overview{grid-template-columns:1fr}.panel-heading{align-items:flex-start;flex-direction:column;padding:14px 16px}.node-list{padding:0 14px}.node-time{min-width:calc(100% - 50px)}.node-time :deep(.el-date-editor){width:100%}.node-status{margin-left:50px;flex:1}.node-action{width:auto}}
</style>
