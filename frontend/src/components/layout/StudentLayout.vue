<script setup lang="ts">
import { ref } from 'vue'
import PlatformHeader from './PlatformHeader.vue'
import PlatformSidebar from './PlatformSidebar.vue'

const collapsed = ref(false)
</script>

<template>
  <div class="platform-shell" data-testid="student-layout">
    <PlatformHeader variant="student" />
    <div class="platform-shell__body">
      <PlatformSidebar :collapsed="collapsed" variant="student" @toggle="collapsed = !collapsed" />
      <main class="platform-content"><RouterView /></main>
    </div>
  </div>
</template>
