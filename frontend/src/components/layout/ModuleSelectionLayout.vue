<script setup lang="ts">
import PlatformHeader from './PlatformHeader.vue'
</script>

<template>
  <div class="platform-shell platform-shell--modules" data-testid="module-selection-layout">
    <PlatformHeader variant="student" />
    <main class="module-content"><RouterView /></main>
  </div>
</template>
