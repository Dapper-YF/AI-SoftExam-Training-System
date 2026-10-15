<script setup lang="ts">
defineProps<{ title: string; owner: string }>()
</script>

<template>
  <section class="workspace-placeholder">
    <p class="eyebrow">路由插槽</p><h1>{{ title }}</h1><p>该业务页面由 {{ owner }} 独立实现。</p>
  </section>
</template>
