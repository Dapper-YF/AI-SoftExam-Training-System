<script setup lang="ts">
import { Brush, Grid, Operation, Setting } from '@element-plus/icons-vue'

const tools = [
  { label: '计算器', icon: Grid },
  { label: '画图', icon: Brush },
  { label: '输入法', icon: Operation },
  { label: '设置', icon: Setting },
]
</script>

<template>
  <div class="disabled-tools" aria-label="考试工具区">
    <el-tooltip v-for="tool in tools" :key="tool.label" content="P1-P6阶段暂不可用" placement="top">
      <button type="button" disabled :aria-label="`${tool.label}，暂不可用`">
        <el-icon><component :is="tool.icon" /></el-icon>
        <span>{{ tool.label }}</span>
        <small>不可用</small>
      </button>
    </el-tooltip>
  </div>
</template>

<style scoped>
.disabled-tools {
  display: flex;
  align-items: center;
  gap: 10px;
}

.disabled-tools button {
  height: 48px;
  min-width: 102px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  padding: 0 12px;
  border: 1px solid #c9d3dd;
  border-radius: 2px;
  color: #8c98a7;
  background: #f3f5f7;
  cursor: not-allowed;
}

.disabled-tools small {
  font-size: 10px;
  color: #a1aab5;
}

@media (max-width: 1450px) {
  .disabled-tools {
    gap: 6px;
  }
  .disabled-tools button {
    min-width: 80px;
    padding: 0 8px;
  }
  .disabled-tools small {
    display: none;
  }
}
</style>
