<template>
  <main class="bootstrap-view" aria-label="正在进入系统">
    <el-icon class="is-loading"><Loading /></el-icon>
  </main>
</template>

<script setup lang="ts">
import { Loading } from '@element-plus/icons-vue'
</script>

<style scoped>
.bootstrap-view {
  display: grid;
  min-height: 100vh;
  place-items: center;
  color: #1677d2;
  font-size: 28px;
}
</style>
