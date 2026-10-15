<script setup lang="ts">
import { getCurrentInstance, onMounted, ref } from 'vue'
import { Clock, Document, Plus, RefreshRight } from '@element-plus/icons-vue'
import { toSafeUserMessage } from '@/api/client'
import { getPracticeGateway } from '@/modules/practice/api/gateway'
import type { PracticeHistoryRecord, PracticeLoadStatus } from '@/modules/practice/types'

defineOptions({ name: 'PracticeHistorySlotView' })

const records = ref<PracticeHistoryRecord[]>([])
const status = ref<PracticeLoadStatus>('idle')
const errorMessage = ref('')
const capabilityUnavailable = ref(false)
const hasRouterContext = Boolean(getCurrentInstance()?.appContext.config.globalProperties.$router)

const sourceLabels: Record<PracticeHistoryRecord['sourceType'], string> = {
  KNOWLEDGE: '知识点练习', YEAR: '年份练习', PAPER: '历年真题', DAILY: '每日练习',
}
const statusLabels: Record<PracticeHistoryRecord['status'], string> = {
  IN_PROGRESS: '进行中', SUBMITTED: '已提交', PENDING_REVIEW: '待复核',
}

function formatDate(value: string): string {
  return value ? new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value)) : '—'
}

async function loadHistory(): Promise<void> {
  status.value = 'loading'
  errorMessage.value = ''
  capabilityUnavailable.value = false
  try {
    const result = await getPracticeGateway().listAttemptHistory({ page: 1, pageSize: 20 })
    records.value = result.items
    status.value = records.value.length ? 'success' : 'empty'
  } catch (error) {
    const details = error as { status?: number }
    const fallback: Record<number, string> = {
      401: '登录状态已失效，请重新登录后返回本页。',
      403: '当前资格无权查看练习历史。',
      404: '练习历史不可访问。',
      409: '记录版本发生冲突，请刷新后重试。',
      429: '请求过于频繁，请稍后重试。',
      503: '练习历史服务暂未开放或暂不可用。',
    }
    status.value = 'error'
    errorMessage.value = details.status ? fallback[details.status] ?? toSafeUserMessage(error) : toSafeUserMessage(error)
    capabilityUnavailable.value = details.status === 503
  }
}

onMounted(() => { if (hasRouterContext) void loadHistory() })
</script>

<template>
  <section class="practice-history-page" data-testid="practice-history-slot" data-route-identity="practice.history">
    <header class="practice-page-heading">
      <div><p class="eyebrow">练习记录</p><h1>练习历史</h1><p>查看普通练习的真实记录与完成状态。</p></div>
      <a class="practice-primary-link" href="/practice"><component :is="Plus" class="practice-icon" />开始一次练习</a>
    </header>
    <div class="practice-history-summary"><span><component :is="Clock" class="practice-icon" />记录按最近更新时间排列</span><span>仅展示后端已返回的状态和统计</span></div>
    <div v-if="status === 'loading'" class="practice-history-loading" data-testid="practice-history-loading" aria-label="正在加载练习历史"><i v-for="index in 7" :key="index"></i></div>
    <div v-else-if="status === 'error'" class="practice-history-empty practice-history-error" data-testid="practice-history-error">
      <component :is="Document" class="practice-history-empty-icon" /><h2>{{ capabilityUnavailable ? '练习历史尚未开放' : '练习历史暂不可用' }}</h2><p>{{ errorMessage }}</p><button v-if="!capabilityUnavailable" class="practice-secondary-link" type="button" @click="loadHistory"><component :is="RefreshRight" class="practice-icon" />重试</button>
    </div>
    <div v-else-if="status === 'empty' || status === 'idle'" class="practice-history-empty" data-testid="practice-history-empty">
      <component :is="Document" class="practice-history-empty-icon" />
      <h2>暂无练习记录</h2>
      <p>完成或保存一次普通练习后，可在这里继续或查看状态。</p>
      <a class="practice-secondary-link" href="/practice">开始一次练习</a>
    </div>
    <div v-else class="practice-history-table" role="table" aria-label="练习历史列表">
      <div class="practice-history-row practice-history-row--head" role="row"><span>练习类型</span><span>开始时间</span><span>题量</span><span>状态</span><span>成绩</span><span>操作</span></div>
      <div v-for="record in records" :key="record.id" class="practice-history-row" role="row" data-testid="practice-history-row">
        <strong>{{ sourceLabels[record.sourceType] }}</strong><span>{{ formatDate(record.startedAt) }}</span><span>{{ record.questionCount }} 题</span><span class="practice-history-status" :class="`is-${record.status.toLowerCase()}`">{{ statusLabels[record.status] }}</span><span>{{ record.totalScore == null ? '—' : `${record.totalScore} / ${record.maxScore ?? '—'}` }}</span><a :href="`/app/practice/attempts/${record.id}`">{{ record.status === 'IN_PROGRESS' ? '继续练习' : '查看记录' }}</a>
      </div>
    </div>
  </section>
</template>
