<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { toSafeUserMessage } from '@/api/client'
import CandidateSummary from '../components/CandidateSummary.vue'
import SimulatorBrandHeader from '../components/SimulatorBrandHeader.vue'
import { useExamSimulatorStore } from '../stores/exam-simulator'

const router = useRouter()
const store = useExamSimulatorStore()

onMounted(() => {
  if (!store.candidate) void store.loadCandidate()
})

async function confirm(): Promise<void> {
  try {
    await store.confirmCandidate()
    await router.push({ name: 'exam-rules', params: { sessionId: store.sessionId } })
  } catch (error) {
    ElMessage.error(toSafeUserMessage(error))
  }
}

function back(): void {
  void router.push({
    name: 'exam-login',
    params: { entryCode: store.entryCode ?? 'LINKED' },
  })
}
</script>

<template>
  <main class="screen-page candidate-view" data-testid="p3-candidate-page">
    <SimulatorBrandHeader :station-no="store.candidate?.stationNo" />
    <section class="candidate-view__stage">
      <div v-if="store.candidate" class="candidate-panel">
        <div class="candidate-panel__tip">请仔细核对个人信息，如有错误请返回重新登录。</div>
        <CandidateSummary :candidate="store.candidate" />
        <div class="candidate-panel__actions">
          <el-button
            type="primary"
            class="exam-action-button"
            :loading="store.loading"
            data-testid="candidate-confirm"
            @click="confirm"
          >
            确认登录
          </el-button>
          <el-button class="exam-action-button exam-action-button--orange" data-testid="candidate-back" @click="back">
            返回
          </el-button>
        </div>
      </div>
    </section>
  </main>
</template>

<style scoped>
.candidate-view {
  background: linear-gradient(to bottom, #168de2 0 42%, #fff 42% 100%);
}

.candidate-view__stage {
  display: grid;
  place-items: start center;
  padding-top: 52px;
}

.candidate-panel {
  width: 660px;
  padding: 28px 34px 32px;
  background: #fff;
  border: 1px solid #d7dee7;
  box-shadow: 0 10px 26px rgb(13 82 132 / 14%);
}

.candidate-panel__tip {
  margin-bottom: 26px;
  padding: 10px 12px;
  color: #d56e00;
  background: #fff8e8;
  border-left: 3px solid #ff9800;
}

.candidate-panel__actions {
  display: flex;
  justify-content: center;
  gap: 42px;
  margin-top: 30px;
}

@media (max-height: 800px) {
  .candidate-view__stage {
    padding-top: 24px;
  }
}
</style>
