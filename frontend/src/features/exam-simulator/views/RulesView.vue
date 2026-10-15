<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { toSafeUserMessage } from '@/api/client'
import { CircleCheckFilled, Clock } from '@element-plus/icons-vue'
import CandidateSummary from '../components/CandidateSummary.vue'
import ForceStartDialog from '../components/ForceStartDialog.vue'
import { useAuthoritativeClock } from '../composables/use-authoritative-clock'
import { useExamSimulatorStore } from '../stores/exam-simulator'
import { formatDuration } from '../utils/time'

const router = useRouter()
const store = useExamSimulatorStore()
const activeTab = ref('RULES')
const hasScrolledToBottom = ref(false)
const entering = ref(false)

useAuthoritativeClock()

const canAcknowledge = computed(
  () => !store.rules?.requireScrollToEnd || hasScrolledToBottom.value,
)

const waitingText = computed(() => formatDuration(store.waitingSeconds))

onMounted(async () => {
  try {
    await store.loadRules()
  } catch (error) {
    ElMessage.error(toSafeUserMessage(error))
  }
})

async function enterWhenReady(): Promise<void> {
  if (!store.rulesAcknowledged || !store.examHasStarted || entering.value) return
  entering.value = true
  try {
    await store.enterExam()
    await router.replace({ name: 'exam-workspace', params: { sessionId: store.sessionId } })
  } catch (error) {
    entering.value = false
    ElMessage.error(toSafeUserMessage(error))
  }
}

watch(
  () => [store.rulesAcknowledged, store.examHasStarted] as const,
  () => void enterWhenReady(),
)

async function acknowledgeRules(): Promise<void> {
  try {
    const canEnterNow = await store.acknowledgeRules()
    if (canEnterNow) await enterWhenReady()
  } catch (error) {
    ElMessage.error(toSafeUserMessage(error))
  }
}

function handleRulesScroll(event: Event): void {
  const element = event.currentTarget as HTMLElement
  hasScrolledToBottom.value = element.scrollTop + element.clientHeight >= element.scrollHeight - 8
}
</script>

<template>
  <main class="screen-page rules-view" data-testid="p4-rules-page">
    <header v-if="store.candidate" class="rules-view__candidate-bar">
      <CandidateSummary :candidate="store.candidate" compact />
      <div class="rules-view__exam-status">
        <div>
          <span>考试类型</span>
          <strong>流程演练</strong>
        </div>
        <div>
          <span>考试时间</span>
          <strong>{{ Math.round((store.clockState?.durationSeconds ?? 0) / 60) }} 分钟</strong>
        </div>
        <div>
          <span>试卷状态</span>
          <strong>{{ store.paperLoadProgress === 100 ? '已加载' : '待开考' }}</strong>
        </div>
      </div>
      <div class="rules-view__countdown" data-testid="waiting-countdown">
        <span><el-icon><Clock /></el-icon> 开考倒计时</span>
        <strong>{{ waitingText }}</strong>
        <el-progress
          :percentage="store.examHasStarted ? 100 : Math.max(8, 100 - Math.min(100, store.waitingSeconds * 5))"
          :show-text="false"
          color="#ff9800"
        />
        <small>{{ store.examHasStarted ? '考试已开始计时' : '请阅读规则并等待开考' }}</small>
      </div>
    </header>

    <section class="rules-view__body">
      <el-tabs v-model="activeTab" type="card" class="rules-tabs">
        <el-tab-pane
          v-for="tab in store.rules?.tabs ?? []"
          :key="tab.code"
          :label="tab.title"
          :name="tab.code"
        >
          <div class="rules-scroll" data-testid="rules-scroll" @scroll="handleRulesScroll">
            <p v-for="paragraph in tab.paragraphs" :key="paragraph">{{ paragraph }}</p>
          </div>
        </el-tab-pane>
      </el-tabs>

      <div class="rules-view__acknowledge">
        <div v-if="store.rulesAcknowledged" class="rules-view__acknowledged">
          <el-icon><CircleCheckFilled /></el-icon>
          已阅读，等待系统进入考试
        </div>
        <el-button
          v-else
          type="primary"
          class="exam-action-button rules-view__ack-button"
          :disabled="!canAcknowledge"
          :loading="store.loading"
          data-testid="acknowledge-rules"
          @click="acknowledgeRules"
        >
          我已阅读
        </el-button>
        <p v-if="store.rules?.requireScrollToEnd && !hasScrolledToBottom">请滚动阅读至底部后确认</p>
      </div>
    </section>

    <ForceStartDialog
      :visible="store.shouldForceRuleReminder"
      @confirm="store.acknowledgeForceReminder"
    />

    <div v-if="entering" class="paper-loading" data-testid="paper-loading">
      <div>
        <h2>正在加载试卷</h2>
        <el-progress :percentage="store.paperLoadProgress" color="#ff9800" />
        <p>考试时间已按计划开考时刻计算，请勿关闭页面。</p>
      </div>
    </div>
  </main>
</template>

<style scoped>
.rules-view {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  overflow: hidden;
  background: #fff;
}

.rules-view__candidate-bar {
  display: grid;
  grid-template-columns: minmax(520px, 1.35fr) minmax(260px, 0.65fr) minmax(300px, 0.7fr);
  gap: 30px;
  align-items: center;
  min-height: 202px;
  padding: 22px clamp(28px, 8vw, 180px);
  color: #fff;
  background: #168de2;
}

.rules-view__candidate-bar :deep(dt) {
  color: #e5f4ff;
}

.rules-view__candidate-bar :deep(dd) {
  color: #fff;
}

.rules-view__exam-status {
  display: grid;
  gap: 10px;
  font-size: 14px;
}

.rules-view__exam-status > div {
  display: grid;
  grid-template-columns: 82px minmax(0, 1fr);
  gap: 10px;
}

.rules-view__exam-status span {
  color: #dff2ff;
}

.rules-view__countdown {
  min-width: 0;
}

.rules-view__countdown > span {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #eaf6ff;
  font-size: 18px;
}

.rules-view__countdown > strong {
  display: block;
  margin: 6px 0 12px;
  color: #ff9800;
  font-size: clamp(34px, 3vw, 52px);
  font-variant-numeric: tabular-nums;
  letter-spacing: 0;
}

.rules-view__countdown small {
  color: #eaf6ff;
}

.rules-view__body {
  min-height: 0;
  padding: 34px clamp(34px, 10vw, 220px) 24px;
  overflow: hidden;
}

.rules-tabs {
  height: calc(100% - 100px);
}

.rules-tabs :deep(.el-tabs__content),
.rules-tabs :deep(.el-tab-pane) {
  height: calc(100% - 1px);
}

.rules-tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}

.rules-tabs :deep(.el-tabs__item) {
  min-width: 150px;
  height: 46px;
  border-radius: 0 !important;
  font-size: 17px;
}

.rules-scroll {
  height: 100%;
  padding: 26px 40px;
  border: 1px solid #cfd6de;
  overflow: auto;
}

.rules-scroll p {
  margin: 0 0 14px;
  color: #303846;
  font-size: 16px;
  line-height: 1.9;
}

.rules-view__acknowledge {
  height: 94px;
  display: grid;
  place-items: center;
}

.rules-view__ack-button {
  width: 330px;
}

.rules-view__acknowledged {
  display: flex;
  align-items: center;
  gap: 10px;
  color: #0877c6;
  font-size: 18px;
}

.rules-view__acknowledged .el-icon {
  color: #19a866;
  font-size: 24px;
}

.rules-view__acknowledge p {
  margin: -22px 0 0;
  color: #b55f00;
  font-size: 13px;
}

.paper-loading {
  position: fixed;
  inset: 0;
  z-index: 3000;
  display: grid;
  place-items: center;
  background: rgb(17 24 39 / 56%);
}

.paper-loading > div {
  width: 480px;
  padding: 28px 34px;
  background: #fff;
  border: 1px solid #bfc8d3;
}

.paper-loading h2 {
  margin: 0 0 20px;
  text-align: center;
}

.paper-loading p {
  margin: 16px 0 0;
  color: #697586;
  text-align: center;
}

@media (max-width: 1450px), (max-height: 820px) {
  .rules-view__candidate-bar {
    min-height: 168px;
    grid-template-columns: minmax(500px, 1.4fr) minmax(220px, 0.6fr) minmax(260px, 0.7fr);
    padding: 14px 40px;
  }
  .rules-view__body {
    padding: 20px 60px 12px;
  }
  .rules-scroll {
    padding: 18px 28px;
  }
  .rules-scroll p {
    font-size: 14px;
    line-height: 1.7;
  }
}
</style>
