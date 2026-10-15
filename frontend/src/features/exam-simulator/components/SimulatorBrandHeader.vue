<script setup lang="ts">
defineProps<{
  stationNo?: string
}>()
</script>

<template>
  <header class="simulator-brand-header">
    <div class="simulator-brand-header__title">
      AI高项培训系统 · 计算机技术与软件专业技术资格考试模拟作答系统
    </div>
    <div v-if="stationNo" class="simulator-brand-header__station">
      机位号：<strong>{{ stationNo }}</strong>
    </div>
  </header>
</template>

<style scoped>
.simulator-brand-header {
  min-height: 114px;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  padding: 22px 44px;
  background: #168de2;
  color: #fff;
  border-bottom: 1px solid #0877c6;
}

.simulator-brand-header__title {
  font-size: clamp(24px, 2.4vw, 42px);
  font-weight: 700;
  line-height: 1.3;
  text-align: center;
}

.simulator-brand-header__station {
  position: absolute;
  right: 44px;
  bottom: 14px;
  font-size: 18px;
}

.simulator-brand-header__station strong {
  color: #ff9f0a;
  font-size: 28px;
}

@media (max-width: 1450px) {
  .simulator-brand-header {
    min-height: 92px;
  }
  .simulator-brand-header__station {
    right: 24px;
    font-size: 15px;
  }
  .simulator-brand-header__station strong {
    font-size: 23px;
  }
}
</style>
