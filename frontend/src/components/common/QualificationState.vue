<script setup lang="ts">
import { RefreshRight, Warning } from '@element-plus/icons-vue'
import { useQualificationStore } from '@/stores/qualification'

const store = useQualificationStore()
</script>

<template>
  <div v-if="store.status === 'loading'" class="qualification-state" data-testid="qualification-loading">
    <el-skeleton :rows="2" animated />
  </div>
  <div v-else-if="store.status === 'error'" class="qualification-state qualification-state--error" data-testid="qualification-error">
    <el-icon><Warning /></el-icon>
    <div>
      <strong>资格信息加载失败</strong>
      <p>{{ store.errorMessage }}</p>
    </div>
    <el-button :icon="RefreshRight" @click="store.load()">重试</el-button>
  </div>
  <el-empty v-else-if="store.status === 'empty'" description="当前没有可用资格" :image-size="72" />
  <slot v-else />
</template>

<style scoped>
.qualification-state {
  max-width: 760px;
  padding: 20px 0;
}

.qualification-state--error {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  color: #b42318;
}

.qualification-state--error > .el-icon {
  margin-top: 3px;
  font-size: 22px;
}

.qualification-state--error p {
  margin: 6px 0;
  color: #6b7280;
}

.qualification-state--error .el-button {
  margin-left: auto;
}
</style>
