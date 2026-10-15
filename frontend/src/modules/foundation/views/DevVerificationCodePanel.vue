<script setup lang="ts">
const props = defineProps<{
  kind: 'register' | 'forgot'
  verificationCode: string
}>()

const emit = defineEmits<{
  fill: [verificationCode: string]
}>()
</script>

<template>
  <div class="dev-code-row" :data-testid="`${kind}-dev-code`">
    <span>本机验证码：{{ verificationCode }}</span>
    <el-button
      link
      type="primary"
      :data-testid="`fill-${kind}-dev-code`"
      @click="emit('fill', props.verificationCode)"
    >
      一键填入
    </el-button>
  </div>
</template>

<style scoped>
.dev-code-row {
  display: flex;
  min-height: 32px;
  margin-top: 8px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: #8a5a00;
  font-size: 13px;
}
</style>
