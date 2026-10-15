<script setup lang="ts">
import PracticeHistorySlotView from './PracticeHistorySlotView.vue'

defineOptions({ name: 'PracticeReportsSlotView' })
</script>

<template>
  <section data-testid="practice-reports-slot" data-route-identity="practice.reports">
    <PracticeHistorySlotView />
  </section>
</template>
