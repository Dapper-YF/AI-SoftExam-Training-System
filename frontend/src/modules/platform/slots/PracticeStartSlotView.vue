<script setup lang="ts">
import PracticeListView from '@/modules/practice/views/PracticeListView.vue'

defineOptions({ name: 'PracticeStartSlotView' })
</script>

<template>
  <section data-testid="practice-start-slot" data-route-identity="practice.start">
    <PracticeListView />
  </section>
</template>
