<script setup lang="ts">
import PublicPortalHeader from './PublicPortalHeader.vue'
</script>

<template>
  <div class="public-portal-shell">
    <PublicPortalHeader />
    <RouterView />
  </div>
</template>

<style scoped>
.public-portal-shell {
  min-height: 100vh;
  background: #f5f7fa;
  color: var(--color-text-primary);
}
</style>
