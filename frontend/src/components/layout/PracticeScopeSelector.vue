<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { RefreshRight } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useQualificationStore } from '@/stores/qualification'
import type { LevelCode } from '@/modules/foundation/types'

defineOptions({ name: 'PracticeScopeSelector' })

const store = useQualificationStore()
const authStore = useAuthStore()
const levelLabels: Record<LevelCode, string> = {
  ADVANCED: '高级',
  INTERMEDIATE: '中级',
  PRIMARY: '初级',
}
const levelOptions = computed(() => store.availableLevelCodes.map((value) => ({
  value,
  label: levelLabels[value],
})))
const qualificationLoading = computed(() => store.status === 'loading')
const subjectLoading = computed(() => store.subjectStatus === 'loading')
const isEmpty = computed(() => store.status === 'empty')
const isError = computed(() => store.status === 'error' || store.subjectStatus === 'error')

function loadScope(): Promise<void> {
  return store.load({ anonymous: !authStore.isAuthenticated })
}

onMounted(() => {
  if (store.status === 'idle') void loadScope()
})
</script>

<template>
  <div class="practice-scope-selector" data-testid="practice-scope-selector" aria-label="刷题范围">
    <label class="practice-scope-field practice-scope-field--level">
      <span>级别</span>
      <el-select
        aria-label="级别"
        :model-value="store.selectedLevelCode"
        :loading="qualificationLoading"
        :disabled="qualificationLoading || isEmpty || isError"
        placeholder="选择级别"
        @change="store.selectLevel"
      >
        <el-option v-for="level in levelOptions" :key="level.value" :label="level.label" :value="level.value" />
      </el-select>
    </label>

    <label class="practice-scope-field practice-scope-field--qualification">
      <span>资格</span>
      <el-select
        aria-label="资格"
        :model-value="store.selectedQualificationId"
        :loading="qualificationLoading || subjectLoading"
        :disabled="qualificationLoading || subjectLoading || !store.selectedLevelCode || isEmpty || store.status === 'error'"
        placeholder="选择资格"
        @change="store.selectQualification"
      >
        <el-option
          v-for="qualification in store.qualificationsForSelectedLevel"
          :key="qualification.id"
          :label="qualification.name"
          :value="qualification.id"
        />
      </el-select>
    </label>

    <p v-if="isEmpty" class="practice-scope-feedback" data-testid="practice-scope-empty">暂无可用资格</p>
    <p v-else-if="store.subjectStatus === 'empty'" class="practice-scope-feedback" data-testid="practice-scope-empty">该资格暂无可用科目</p>
    <p v-else-if="isError" class="practice-scope-feedback practice-scope-feedback--error" data-testid="practice-scope-error">
      <span>{{ store.errorMessage }}</span>
      <button type="button" data-testid="practice-scope-retry" @click="loadScope">
        <el-icon><RefreshRight /></el-icon>重试
      </button>
    </p>
  </div>
</template>

<style scoped>
.practice-scope-selector {
  display: grid;
  width: min(454px, 38vw);
  min-width: 390px;
  margin-left: 12px;
  grid-template-columns: 122px minmax(250px, 320px);
  grid-template-rows: 34px 16px;
  align-items: center;
  gap: 0 8px;
}
.practice-scope-field {
  display: grid;
  min-width: 0;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: 6px;
  color: var(--color-text-secondary);
  font-size: 12px;
  white-space: nowrap;
}
.practice-scope-field :deep(.el-select) { min-width: 0; }
.practice-scope-field :deep(.el-select__wrapper) { min-height: 32px; border-radius: 6px; }
.practice-scope-feedback {
  min-width: 0;
  margin: 0 0 0 34px;
  grid-column: 1 / -1;
  overflow: hidden;
  color: var(--color-text-muted);
  font-size: 11px;
  line-height: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.practice-scope-feedback--error { display: flex; align-items: center; gap: 8px; color: var(--color-danger); }
.practice-scope-feedback--error span { overflow: hidden; text-overflow: ellipsis; }
.practice-scope-feedback button {
  display: inline-flex;
  padding: 0;
  flex: 0 0 auto;
  align-items: center;
  gap: 3px;
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  font: inherit;
}
@media (max-width: 1366px) {
  .practice-scope-selector {
    width: 420px;
    min-width: 380px;
    margin-left: 10px;
    grid-template-columns: 112px minmax(250px, 1fr);
    gap: 0 6px;
  }
}
@media (max-width: 1280px) {
  .practice-scope-selector {
    width: 380px;
    min-width: 360px;
    margin-left: 8px;
    grid-template-columns: 104px minmax(240px, 1fr);
  }
}
@media (max-width: 760px) {
  .practice-scope-selector {
    width: 100%;
    min-width: 0;
    margin-left: 0;
    grid-template-columns: 1fr;
    grid-template-rows: auto auto 16px;
    gap: 6px;
  }
  .practice-scope-feedback { margin-left: 0; }
}
</style>
