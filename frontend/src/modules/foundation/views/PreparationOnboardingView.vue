<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowRight, Check, Close, DataAnalysis, Location, Medal } from '@element-plus/icons-vue'
import { toSafeUserMessage } from '@/api/client'
import { getPlatformAuthGateway, getPlatformFoundationGateway } from '@/modules/foundation/api/gateway'
import { EXAM_REGIONS } from '@/modules/foundation/exam-regions'
import { useAuthStore } from '@/stores/auth'
import type { LevelCode, PlatformUserProfile, Qualification } from '@/modules/foundation/types'

const router = useRouter()
const auth = useAuthStore()
const qualifications = ref<Qualification[]>([])
const selectedLevel = ref<LevelCode | ''>('')
const qualificationId = ref('')
const regionCode = ref('')
const profile = ref<PlatformUserProfile | null>(null)
const loading = ref(true)
const saving = ref(false)
const errorMessage = ref('')

const levels: Array<{ value: LevelCode; label: string; description: string }> = [
  { value: 'PRIMARY', label: '初级', description: '基础职业能力' },
  { value: 'INTERMEDIATE', label: '中级', description: '专业技术能力' },
  { value: 'ADVANCED', label: '高级', description: '项目与管理能力' },
]

const levelQualifications = computed(() =>
  qualifications.value.filter(item => item.levelCode === selectedLevel.value),
)
const selectedQualification = computed(() =>
  qualifications.value.find(item => item.id === qualificationId.value),
)
const selectedRegionName = computed(() =>
  EXAM_REGIONS.find(item => item[0] === regionCode.value)?.[1] ?? '',
)
const canSave = computed(() =>
  Boolean(qualificationId.value && regionCode.value && profile.value && !loading.value),
)

function selectLevel(level: LevelCode): void {
  if (selectedLevel.value === level) return
  selectedLevel.value = level
  qualificationId.value = ''
}

async function close(): Promise<void> {
  await auth.logout()
  await router.replace('/')
}

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const [nextProfile, nextQualifications] = await Promise.all([
      getPlatformAuthGateway().getProfile(),
      getPlatformFoundationGateway().listQualifications(),
    ])
    profile.value = nextProfile
    qualifications.value = nextQualifications.filter(item => item.status === 'ENABLED')
    if (nextProfile.preparationQualificationId) {
      const current = qualifications.value.find(item => item.id === nextProfile.preparationQualificationId)
      if (current) {
        selectedLevel.value = current.levelCode
        qualificationId.value = current.id
      }
    }
    regionCode.value = nextProfile.examRegionCode ?? ''
  } catch (error) {
    profile.value = null
    errorMessage.value = toSafeUserMessage(error)
  } finally {
    loading.value = false
  }
}

async function save(): Promise<void> {
  if (!profile.value || !canSave.value) return
  saving.value = true
  errorMessage.value = ''
  try {
    await getPlatformAuthGateway().updateProfile({
      displayName: profile.value.displayName,
      preparationQualificationId: qualificationId.value,
      examRegionCode: regionCode.value,
      version: profile.value.version,
    })
    await auth.refreshPreparationStatus()
    ElMessage.success('备考方向已保存')
    await router.replace('/')
  } catch (error) {
    errorMessage.value = toSafeUserMessage(error)
  } finally {
    saving.value = false
  }
}

onMounted(() => void load())
</script>

<template>
  <main class="onboarding-page">
    <el-dialog
      :model-value="true"
      class="preparation-dialog"
      width="760px"
      :show-close="false"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      :teleported="false"
      align-center
    >
      <template #header>
        <header class="dialog-heading">
          <div class="dialog-brand">
            <span><el-icon><DataAnalysis /></el-icon></span>
            <div>
              <h1>设置你的备考方向</h1>
              <p>完成一次设置，系统将围绕你的考试目标提供内容</p>
            </div>
          </div>
          <button type="button" aria-label="退出登录" title="退出登录" @click="close">
            <el-icon><Close /></el-icon>
          </button>
        </header>
      </template>

      <div class="onboarding-content">
        <aside class="onboarding-intro">
          <span class="intro-mark"><el-icon><Medal /></el-icon></span>
          <h2>让每次学习都更聚焦</h2>
          <p>资格与考区将决定你的核心题库、学习报告和考试时间信息。</p>
          <ul>
            <li><el-icon><Check /></el-icon><span>匹配当前资格的练习内容</span></li>
            <li><el-icon><Check /></el-icon><span>展示对应省份的重要节点</span></li>
            <li><el-icon><Check /></el-icon><span>保持跨页面一致的学习方向</span></li>
          </ul>
          <small>退出将结束当前登录状态</small>
        </aside>

        <section class="onboarding-form" aria-label="备考信息设置">
          <template v-if="loading">
            <div class="form-loading">
              <el-skeleton :rows="7" animated />
              <span>正在加载考试资格与考区</span>
            </div>
          </template>

          <div v-else-if="errorMessage && !profile" class="form-error">
            <span><el-icon><DataAnalysis /></el-icon></span>
            <strong>备考信息加载失败</strong>
            <p>{{ errorMessage }}</p>
            <el-button type="primary" @click="load">重新加载</el-button>
          </div>

          <template v-else>
            <div class="field-section">
              <div class="field-heading">
                <div><strong>考试级别</strong><small>选择你当前准备参加的考试层级</small></div>
              </div>
              <div class="level-options" role="radiogroup" aria-label="考试级别">
                <button
                  v-for="item in levels"
                  :key="item.value"
                  type="button"
                  role="radio"
                  :aria-checked="selectedLevel === item.value"
                  :class="{ 'is-active': selectedLevel === item.value }"
                  @click="selectLevel(item.value)"
                >
                  <span>{{ item.label }}</span>
                  <small>{{ item.description }}</small>
                  <el-icon v-if="selectedLevel === item.value"><Check /></el-icon>
                </button>
              </div>
            </div>

            <div class="field-section">
              <div class="field-heading">
                <div><strong>备考资格</strong><small>选择后将作为你的固定学习方向</small></div>
              </div>
              <el-select
                v-model="qualificationId"
                class="onboarding-select"
                placeholder="请先选择考试级别"
                filterable
                :disabled="!selectedLevel"
              >
                <el-option v-for="item in levelQualifications" :key="item.id" :value="item.id" :label="item.name" />
              </el-select>
            </div>

            <div class="field-section">
              <div class="field-heading">
                <div><strong>省级考区</strong><small>用于匹配报名、准考证和考试时间</small></div>
                <span class="field-icon"><el-icon><Location /></el-icon></span>
              </div>
              <el-select v-model="regionCode" class="onboarding-select" placeholder="请选择省级考区" filterable>
                <el-option v-for="item in EXAM_REGIONS" :key="item[0]" :value="item[0]" :label="item[1]" />
              </el-select>
            </div>

            <el-alert
              v-if="errorMessage"
              class="save-error"
              :title="errorMessage"
              type="error"
              :closable="false"
              show-icon
            />

            <div v-if="canSave" class="selection-summary">
              <span><el-icon><Check /></el-icon></span>
              <div><small>即将保存</small><strong>{{ selectedQualification?.name }} · {{ selectedRegionName }}</strong></div>
            </div>

            <footer class="form-actions">
              <el-button @click="close">暂不登录</el-button>
              <el-button type="primary" :disabled="!canSave" :loading="saving" @click="save">
                确认并进入<el-icon><ArrowRight /></el-icon>
              </el-button>
            </footer>
          </template>
        </section>
      </div>
    </el-dialog>
  </main>
</template>

<style scoped>
.onboarding-page {
  display: grid;
  min-height: 100vh;
  place-items: center;
  background: #f6f8fa;
  color: #172033;
}

.onboarding-page::before {
  position: fixed;
  inset: 0;
  background: rgb(15 23 42 / 32%);
  backdrop-filter: blur(7px);
  content: '';
}

:deep(.el-overlay) {
  background: transparent;
}

:deep(.preparation-dialog) {
  max-width: calc(100vw - 32px);
  padding: 0;
  overflow: hidden;
  border: 1px solid rgb(148 163 184 / 28%);
  border-radius: 20px;
  background: #fff;
  box-shadow: 0 28px 80px rgb(15 23 42 / 22%), 0 5px 18px rgb(15 23 42 / 8%);
}

:deep(.preparation-dialog .el-dialog__header) {
  padding: 0;
  margin: 0;
}

:deep(.preparation-dialog .el-dialog__body) {
  padding: 0;
}

.dialog-heading {
  display: flex;
  min-height: 76px;
  padding: 15px 20px;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e2e8f0;
}

.dialog-brand {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 12px;
}

.dialog-brand > span {
  display: grid;
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  place-items: center;
  border-radius: 12px;
  background: #e7f2ff;
  color: #2563eb;
  font-size: 20px;
}

.dialog-brand h1 {
  margin: 0;
  color: #172033;
  font-size: 18px;
  line-height: 1.35;
}

.dialog-brand p {
  margin: 4px 0 0;
  color: #64748b;
  font-size: 12px;
}

.dialog-heading > button {
  display: grid;
  width: 36px;
  height: 36px;
  padding: 0;
  place-items: center;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: #64748b;
  cursor: pointer;
  font-size: 18px;
}

.dialog-heading > button:hover {
  background: #f1f5f9;
  color: #172033;
}

.onboarding-content {
  display: grid;
  min-height: 510px;
  grid-template-columns: 236px minmax(0, 1fr);
}

.onboarding-intro {
  display: flex;
  padding: 28px 24px;
  background: #172033;
  color: #fff;
  flex-direction: column;
}

.intro-mark {
  display: grid;
  width: 46px;
  height: 46px;
  place-items: center;
  border-radius: 14px;
  background: rgb(255 255 255 / 10%);
  color: #a8c7ff;
  font-size: 23px;
}

.onboarding-intro h2 {
  margin: 24px 0 10px;
  font-size: 21px;
  line-height: 1.4;
}

.onboarding-intro > p {
  margin: 0;
  color: #b7c4d6;
  font-size: 13px;
  line-height: 1.75;
}

.onboarding-intro ul {
  display: grid;
  padding: 0;
  margin: 28px 0 0;
  gap: 15px;
  list-style: none;
}

.onboarding-intro li {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  color: #d9e3ef;
  font-size: 12px;
  line-height: 1.55;
}

.onboarding-intro li .el-icon {
  margin-top: 2px;
  flex: 0 0 auto;
  color: #73d8ca;
}

.onboarding-intro > small {
  margin-top: auto;
  color: #8190a5;
  font-size: 11px;
}

.onboarding-form {
  display: flex;
  padding: 24px 26px 22px;
  flex-direction: column;
}

.field-section + .field-section {
  margin-top: 19px;
}

.field-heading {
  display: flex;
  margin-bottom: 9px;
  align-items: center;
  justify-content: space-between;
}

.field-heading > div {
  display: grid;
  gap: 3px;
}

.field-heading strong {
  color: #273244;
  font-size: 14px;
}

.field-heading small {
  color: #7c8898;
  font-size: 11px;
}

.field-icon {
  color: #2563eb;
  font-size: 17px;
}

.level-options {
  display: grid;
  gap: 8px;
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.level-options button {
  position: relative;
  display: grid;
  min-height: 64px;
  padding: 10px 11px;
  align-content: center;
  gap: 4px;
  border: 1px solid #dce4ee;
  border-radius: 10px;
  background: #fff;
  color: #334155;
  cursor: pointer;
  text-align: left;
  transition: border-color .18s ease, background .18s ease, box-shadow .18s ease;
}

.level-options button:hover {
  border-color: #a9c8f6;
}

.level-options button.is-active {
  border-color: #2563eb;
  background: #eff6ff;
  box-shadow: 0 0 0 2px rgb(37 99 235 / 7%);
}

.level-options button > span {
  font-size: 14px;
  font-weight: 700;
}

.level-options button small {
  color: #7c8898;
  font-size: 10px;
}

.level-options button .el-icon {
  position: absolute;
  top: 8px;
  right: 8px;
  color: #2563eb;
}

.onboarding-select {
  width: 100%;
}

:deep(.onboarding-select .el-select__wrapper) {
  min-height: 42px;
  border-radius: 10px;
  box-shadow: 0 0 0 1px #dce4ee inset;
}

:deep(.onboarding-select .el-select__wrapper.is-focused) {
  box-shadow: 0 0 0 1px #2563eb inset, 0 0 0 3px rgb(37 99 235 / 9%);
}

.selection-summary {
  display: flex;
  min-height: 54px;
  padding: 9px 12px;
  margin-top: 18px;
  align-items: center;
  gap: 10px;
  border: 1px solid #cce9e2;
  border-radius: 10px;
  background: #f0faf7;
}

.selection-summary > span {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border-radius: 9px;
  background: #0f766e;
  color: #fff;
}

.selection-summary div {
  display: grid;
  gap: 2px;
}

.selection-summary small {
  color: #64748b;
  font-size: 10px;
}

.selection-summary strong {
  color: #28584f;
  font-size: 13px;
}

.save-error {
  margin-top: 16px;
}

.form-actions {
  display: flex;
  padding-top: 18px;
  margin-top: auto;
  justify-content: flex-end;
  gap: 8px;
  border-top: 1px solid #e7edf4;
}

.form-actions :deep(.el-button) {
  min-width: 112px;
  height: 40px;
  border-radius: 10px;
}

.form-actions :deep(.el-button--primary) {
  border-color: #172033;
  background: #172033;
}

.form-loading,
.form-error {
  display: grid;
  min-height: 430px;
  align-content: center;
  justify-items: center;
  gap: 12px;
  color: #64748b;
  text-align: center;
}

.form-loading :deep(.el-skeleton) {
  width: 100%;
}

.form-error > span {
  display: grid;
  width: 44px;
  height: 44px;
  place-items: center;
  border-radius: 13px;
  background: #fff1f2;
  color: #dc2626;
  font-size: 21px;
}

.form-error strong {
  color: #172033;
}

.form-error p {
  margin: 0;
  font-size: 13px;
}

@media (max-width: 680px) {
  :deep(.preparation-dialog) {
    max-height: calc(100vh - 20px);
    max-width: calc(100vw - 20px);
    overflow-y: auto;
    border-radius: 16px;
  }

  .dialog-heading {
    min-height: 70px;
    padding: 13px 15px;
  }

  .dialog-brand p {
    display: none;
  }

  .onboarding-content {
    display: block;
  }

  .onboarding-intro {
    padding: 18px;
  }

  .intro-mark,
  .onboarding-intro > p,
  .onboarding-intro ul,
  .onboarding-intro > small {
    display: none;
  }

  .onboarding-intro h2 {
    margin: 0;
    font-size: 16px;
  }

  .onboarding-form {
    padding: 20px 16px 16px;
  }

  .level-options {
    grid-template-columns: 1fr;
  }

  .level-options button {
    min-height: 54px;
  }

  .form-actions {
    position: sticky;
    bottom: 0;
    padding-top: 14px;
    background: #fff;
  }

  .form-actions :deep(.el-button) {
    min-width: 0;
    flex: 1;
  }
}
</style>
