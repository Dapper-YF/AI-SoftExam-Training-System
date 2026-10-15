<script setup lang="ts">
import { WarningFilled } from '@element-plus/icons-vue'

defineProps<{
  visible: boolean
}>()

const emit = defineEmits<{
  confirm: []
}>()
</script>

<template>
  <el-dialog
    :model-value="visible"
    width="520px"
    class="force-start-dialog"
    :show-close="false"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    :modal="true"
    :before-close="() => undefined"
    align-center
    data-testid="force-start-dialog"
  >
    <template #header>
      <div class="force-start-dialog__header">考试开始提醒</div>
    </template>
    <div class="force-start-dialog__body">
      <el-icon><WarningFilled /></el-icon>
      <p>考试已经开始倒计时，请尽快阅读考场规则并点击“我已阅读”按钮，进入作答界面。</p>
    </div>
    <template #footer>
      <el-button type="primary" size="large" data-testid="force-confirm" @click="emit('confirm')">
        确认
      </el-button>
    </template>
  </el-dialog>
</template>

<style>
.force-start-dialog {
  border-radius: 2px !important;
  padding: 0 !important;
}

.force-start-dialog .el-dialog__header {
  margin: 0;
  padding: 18px 24px;
  background: #168de2;
  color: #fff;
}

.force-start-dialog .el-dialog__body {
  padding: 34px 40px 20px;
}

.force-start-dialog .el-dialog__footer {
  padding: 0 40px 28px;
  text-align: center;
}

.force-start-dialog__header {
  text-align: center;
  font-size: 20px;
  font-weight: 600;
}

.force-start-dialog__body {
  display: grid;
  grid-template-columns: 54px minmax(0, 1fr);
  gap: 18px;
  align-items: start;
}

.force-start-dialog__body .el-icon {
  color: #ff9800;
  font-size: 46px;
}

.force-start-dialog__body p {
  margin: 0;
  color: #d97000;
  font-size: 17px;
  line-height: 1.8;
}

.force-start-dialog .el-button {
  width: 150px;
  border-radius: 2px;
}
</style>
