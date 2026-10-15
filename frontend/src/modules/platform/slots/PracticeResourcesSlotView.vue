<script setup lang="ts">
import { Reading } from '@element-plus/icons-vue'

defineOptions({ name: 'PracticeResourcesSlotView' })
</script>

<template>
  <section class="practice-route-slot" data-testid="practice-resources-slot" data-route-identity="practice.resources" data-api-boundary="501">
    <el-empty :image-size="76">
      <template #image><el-icon><Reading /></el-icon></template>
      <template #description>
        <div class="practice-route-slot__copy">
          <h1>学习资料</h1>
          <p>学习资料服务尚未开放，当前不展示推测的资料列表或推荐内容。</p>
          <small>功能状态：501 Not Implemented</small>
        </div>
      </template>
    </el-empty>
  </section>
</template>
