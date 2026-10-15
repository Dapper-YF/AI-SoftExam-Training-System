<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowRight,
  Collection,
  DataAnalysis,
  Edit,
  Location,
  Lock,
  Medal,
  Refresh,
  Setting,
  UserFilled,
} from '@element-plus/icons-vue'
import { toSafeUserMessage } from '@/api/client'
import { getPlatformAuthGateway, getPlatformFoundationGateway } from '@/modules/foundation/api/gateway'
import { EXAM_REGIONS } from '@/modules/foundation/exam-regions'
import type { PlatformUserProfile, Qualification } from '@/modules/foundation/types'
import { getPlatformHomeGateway } from '@/modules/platform/api/gateway'
import type { PlatformStat } from '@/modules/platform/types'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const router = useRouter()
const loading = ref(false)
const saving = ref(false)
const errorMessage = ref('')
const profile = ref<PlatformUserProfile | null>(null)
const learningStats = ref<PlatformStat[]>([])
const form = reactive({ displayName: '', preparationQualificationId: '', examRegionCode: '' })
const qualifications = ref<Qualification[]>([])
const regions = EXAM_REGIONS

const canSave = computed(() => Boolean(profile.value)
  && form.displayName.trim().length > 0
  && form.displayName.trim().length <= 64
  && (form.displayName.trim() !== profile.value?.displayName
    || form.preparationQualificationId !== (profile.value?.preparationQualificationId ?? '')
    || form.examRegionCode !== (profile.value?.examRegionCode ?? '')))

const profileInitial = computed(() => (form.displayName.trim() || authStore.user?.displayName || 'U').slice(0, 1).toUpperCase())
const accountStatus = computed(() => profile.value?.status === 'ENABLED' ? '正常' : '受限')
const qualificationName = computed(() => qualifications.value.find(item => item.id === form.preparationQualificationId)?.name || '暂未设置')
const regionName = computed(() => regions.find(item => item[0] === form.examRegionCode)?.[1] || '暂未设置')
const statValue = (id: string): string => learningStats.value.find(stat => stat.id === id)?.value ?? '0'

async function loadProfile(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const nextProfile = await getPlatformAuthGateway().getProfile()
    profile.value = nextProfile
    form.displayName = nextProfile.displayName
    form.preparationQualificationId = nextProfile.preparationQualificationId ?? ''
    form.examRegionCode = nextProfile.examRegionCode ?? ''
    try {
      learningStats.value = (await getPlatformHomeGateway().getModuleHomeSnapshot()).stats
    } catch {
      learningStats.value = []
    }
  } catch (error) {
    profile.value = null
    errorMessage.value = toSafeUserMessage(error)
  } finally {
    loading.value = false
  }
}

async function saveProfile(): Promise<void> {
  if (!profile.value || !canSave.value) return
  saving.value = true
  errorMessage.value = ''
  try {
    const qualificationChanged = form.preparationQualificationId !== (profile.value.preparationQualificationId ?? '')
    const regionChanged = form.examRegionCode !== (profile.value.examRegionCode ?? '')
    const nextProfile = await getPlatformAuthGateway().updateProfile({
      displayName: form.displayName.trim(),
      version: profile.value.version,
      ...(qualificationChanged ? { preparationQualificationId: form.preparationQualificationId || null } : {}),
      ...(regionChanged ? { examRegionCode: form.examRegionCode || null } : {}),
    })
    profile.value = nextProfile
    form.displayName = nextProfile.displayName
    form.preparationQualificationId = nextProfile.preparationQualificationId ?? ''
    form.examRegionCode = nextProfile.examRegionCode ?? ''
    authStore.updateDisplayName(nextProfile.displayName)
    ElMessage.success('个人资料已更新')
  } catch (error) {
    errorMessage.value = toSafeUserMessage(error)
  } finally {
    saving.value = false
  }
}

function openSecuritySettings(): void {
  void router.push({
    path: '/forgot-password',
    query: { identifier: profile.value?.username },
  })
}

onMounted(() => {
  void loadProfile()
  void getPlatformFoundationGateway().listQualifications().then(items => {
    qualifications.value = items.filter(item => item.status === 'ENABLED')
  }).catch(() => {
    qualifications.value = []
  })
})
</script>

<template>
  <section class="profile-page" data-testid="profile-page">
    <header class="profile-header">
      <div>
        <p class="profile-eyebrow">账号中心</p>
        <h1>个人资料</h1>
        <span>管理身份信息、备考方向与账号安全</span>
      </div>
      <el-button class="refresh-button" :loading="loading" data-testid="profile-refresh" @click="loadProfile">
        <el-icon><Refresh /></el-icon><span>刷新资料</span>
      </el-button>
    </header>

    <el-alert
      v-if="errorMessage"
      :title="errorMessage"
      type="error"
      :closable="false"
      show-icon
      data-testid="profile-error"
    />

    <main v-loading="loading" class="profile-body" :aria-busy="loading">
      <section class="identity-card" data-testid="profile-summary">
        <div class="identity-main">
          <span class="profile-avatar">{{ profileInitial }}</span>
          <div class="identity-copy">
            <span class="identity-label">学习账号</span>
            <strong>{{ profile?.displayName || authStore.user?.displayName || '未命名用户' }}</strong>
            <span>@{{ profile?.username || authStore.user?.username }}</span>
          </div>
        </div>
        <div class="identity-context">
          <div>
            <span class="context-icon context-icon--blue"><el-icon><Medal /></el-icon></span>
            <p><small>当前备考资格</small><strong>{{ qualificationName }}</strong></p>
          </div>
          <div>
            <span class="context-icon context-icon--green"><el-icon><Location /></el-icon></span>
            <p><small>考试地区</small><strong>{{ regionName }}</strong></p>
          </div>
        </div>
        <div class="profile-status" :class="{ 'is-limited': profile?.status !== 'ENABLED' }">
          <span></span>{{ accountStatus }}
        </div>
      </section>

      <div class="profile-columns">
        <section class="profile-panel profile-form-panel">
          <div class="panel-heading">
            <div><span class="panel-kicker">资料设置</span><h2>个人与备考信息</h2><p>这些信息会用于展示学习内容和推荐练习。</p></div>
            <span class="heading-icon"><el-icon><Edit /></el-icon></span>
          </div>

          <el-form v-if="profile" label-position="top" class="profile-form" @submit.prevent>
            <div class="form-section">
              <div class="form-section__title"><span>01</span><div><strong>基础信息</strong><small>设置你在系统中显示的名称</small></div></div>
              <div class="form-grid">
                <el-form-item label="显示名称">
                  <el-input v-model="form.displayName" maxlength="64" show-word-limit data-testid="profile-display-name" />
                </el-form-item>
                <el-form-item label="登录账号">
                  <el-input :model-value="profile.username" disabled />
                </el-form-item>
              </div>
            </div>

            <div v-if="authStore.roles.includes('STUDENT')" class="form-section">
              <div class="form-section__title"><span>02</span><div><strong>备考方向</strong><small>资格与地区将影响题库及学习内容</small></div></div>
              <div class="form-grid">
                <el-form-item label="备考资格">
                  <el-select v-model="form.preparationQualificationId" placeholder="请选择备考资格">
                    <el-option v-for="item in qualifications" :key="item.id" :value="item.id" :label="item.name" />
                  </el-select>
                </el-form-item>
                <el-form-item label="省级考区">
                  <el-select v-model="form.examRegionCode" placeholder="请选择考试地区">
                    <el-option v-for="item in regions" :key="item[0]" :value="item[0]" :label="item[1]" />
                  </el-select>
                </el-form-item>
              </div>
            </div>

            <div class="form-actions">
              <span>{{ canSave ? '资料已发生变更，请保存后生效' : '当前资料已是最新状态' }}</span>
              <el-button type="primary" :disabled="!canSave" :loading="saving" data-testid="profile-save" @click="saveProfile">
                保存修改
              </el-button>
            </div>
          </el-form>
        </section>

        <aside class="profile-aside">
          <section class="profile-panel security-panel">
            <div class="panel-heading panel-heading--compact">
              <div><span class="panel-kicker">账号安全</span><h2>绑定与验证</h2></div>
              <span class="heading-icon heading-icon--warm"><el-icon><Lock /></el-icon></span>
            </div>
            <dl class="account-details">
              <div>
                <dt>手机号码</dt>
                <dd>{{ profile?.maskedMobile || '暂未绑定' }}</dd>
                <span>用于登录及安全验证</span>
              </div>
              <div>
                <dt>电子邮箱</dt>
                <dd>{{ profile?.maskedEmail || '暂未绑定' }}</dd>
                <span>用于接收账号通知</span>
              </div>
            </dl>
            <button class="security-action" type="button" @click="openSecuritySettings">
              <span><el-icon><Setting /></el-icon>修改密码与安全设置</span><el-icon><ArrowRight /></el-icon>
            </button>
          </section>

          <section class="profile-panel account-note">
            <span class="heading-icon heading-icon--green"><el-icon><UserFilled /></el-icon></span>
            <div><strong>账号资料同步</strong><p>备考方向保存后，将同步影响首页、题库和学习工作台展示的内容。</p></div>
          </section>
        </aside>
      </div>

      <section class="profile-panel learning-overview">
        <div class="panel-heading panel-heading--compact">
          <div><span class="panel-kicker">学习概览</span><h2>我的学习数据</h2></div>
          <span class="overview-note">数据来自当前账号</span>
        </div>
        <div class="learning-stats">
          <div>
            <span class="stat-icon stat-icon--blue"><el-icon><DataAnalysis /></el-icon></span>
            <p><strong>{{ statValue('practice-count') }}</strong><small>累计练习</small></p>
          </div>
          <div>
            <span class="stat-icon stat-icon--amber"><el-icon><Collection /></el-icon></span>
            <p><strong>{{ statValue('practice-favorites') }}</strong><small>收藏题目</small></p>
          </div>
          <div>
            <span class="stat-icon stat-icon--purple"><el-icon><UserFilled /></el-icon></span>
            <p><strong>{{ profile?.version ?? 0 }}</strong><small>资料版本</small></p>
          </div>
        </div>
      </section>
    </main>
  </section>
</template>

<style scoped>
.profile-page {
  min-height: calc(100vh - var(--platform-header-height));
  padding: 28px var(--platform-page-padding) 52px;
  background:
    radial-gradient(circle at 88% 4%, rgb(37 99 235 / 5%), transparent 25rem),
    var(--color-page-canvas);
  color: var(--color-text-primary);
}
.profile-header, .profile-body, .profile-page > .el-alert { max-width: 1180px; margin-right: auto; margin-left: auto; }
.profile-header { display: flex; margin-bottom: 22px; align-items: flex-end; justify-content: space-between; gap: 20px; }
.profile-eyebrow, .panel-kicker { margin: 0; color: #2563eb; font-size: 12px; font-weight: 700; letter-spacing: .08em; }
.profile-header h1 { margin: 6px 0 0; color: #172033; font-size: 30px; line-height: 1.2; }
.profile-header > div > span { display: block; margin-top: 8px; color: #64748b; font-size: 13px; }
.refresh-button { min-height: 38px; border-color: #dbe4f0; border-radius: 10px; color: #334155; }
.profile-page > .el-alert { margin-bottom: 16px; border-radius: 10px; }
.profile-body { display: grid; gap: 18px; }
.identity-card, .profile-panel { border: 1px solid rgb(203 213 225 / 78%); background: rgb(255 255 255 / 96%); box-shadow: 0 12px 34px rgb(15 23 42 / 6%); }
.identity-card { position: relative; display: grid; min-height: 146px; padding: 26px 30px; grid-template-columns: minmax(250px, .9fr) minmax(410px, 1.25fr); align-items: center; gap: 30px; overflow: hidden; border-radius: 18px; }
.identity-card::after { position: absolute; top: -70px; right: -40px; width: 250px; height: 250px; border-radius: 50%; background: #eff6ff; content: ""; opacity: .72; pointer-events: none; }
.identity-main, .identity-context, .identity-context > div { display: flex; align-items: center; }
.identity-main { gap: 18px; }
.profile-avatar { display: grid; width: 74px; height: 74px; flex: 0 0 auto; place-items: center; border: 1px solid #bfdbfe; border-radius: 22px; background: linear-gradient(145deg, #2563eb, #1d4ed8); box-shadow: 0 12px 24px rgb(37 99 235 / 22%); color: white; font-size: 30px; font-weight: 700; }
.identity-copy { display: grid; gap: 4px; }
.identity-label { color: #64748b; font-size: 12px; }
.identity-copy strong { color: #172033; font-size: 22px; }
.identity-copy > span:last-child { color: #64748b; font-size: 13px; }
.identity-context { position: relative; z-index: 1; padding: 12px 58px 12px 24px; gap: 30px; border-left: 1px solid #e2e8f0; }
.identity-context > div { min-width: 0; flex: 1; gap: 12px; }
.context-icon, .heading-icon, .stat-icon { display: grid; flex: 0 0 auto; place-items: center; }
.context-icon { width: 42px; height: 42px; border-radius: 13px; font-size: 19px; }
.context-icon--blue { background: #eff6ff; color: #2563eb; }
.context-icon--green { background: #ecfdf5; color: #059669; }
.identity-context p { display: grid; min-width: 0; margin: 0; gap: 4px; }
.identity-context small { color: #64748b; font-size: 11px; }
.identity-context strong { overflow: hidden; color: #1e293b; font-size: 14px; text-overflow: ellipsis; white-space: nowrap; }
.profile-status { position: absolute; z-index: 2; top: 18px; right: 20px; display: inline-flex; padding: 5px 9px; align-items: center; gap: 6px; border-radius: 999px; background: #ecfdf5; color: #047857; font-size: 11px; font-weight: 600; }
.profile-status > span { width: 6px; height: 6px; border-radius: 50%; background: #10b981; }
.profile-status.is-limited { background: #fff7ed; color: #c2410c; }
.profile-status.is-limited > span { background: #f97316; }
.profile-columns { display: grid; grid-template-columns: minmax(0, 1.55fr) minmax(300px, .75fr); gap: 18px; }
.profile-panel { border-radius: 16px; }
.profile-form-panel, .security-panel { padding: 25px 27px; }
.profile-aside { display: grid; align-content: start; gap: 18px; }
.panel-heading { display: flex; margin-bottom: 24px; align-items: flex-start; justify-content: space-between; gap: 18px; }
.panel-heading--compact { margin-bottom: 18px; }
.panel-heading h2 { margin: 5px 0 0; color: #172033; font-size: 18px; }
.panel-heading p { margin: 7px 0 0; color: #64748b; font-size: 12px; }
.heading-icon { width: 40px; height: 40px; border-radius: 12px; background: #eff6ff; color: #2563eb; font-size: 18px; }
.heading-icon--warm { background: #fff7ed; color: #ea580c; }
.heading-icon--green { background: #ecfdf5; color: #059669; }
.profile-form { display: grid; gap: 22px; }
.form-section { padding-top: 21px; border-top: 1px solid #edf1f6; }
.form-section:first-child { padding-top: 0; border-top: 0; }
.form-section__title { display: flex; margin-bottom: 17px; align-items: center; gap: 11px; }
.form-section__title > span { display: grid; width: 28px; height: 28px; place-items: center; border-radius: 9px; background: #eff6ff; color: #2563eb; font-size: 11px; font-weight: 700; }
.form-section__title > div { display: grid; gap: 2px; }
.form-section__title strong { color: #334155; font-size: 13px; }
.form-section__title small { color: #94a3b8; font-size: 11px; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.profile-form :deep(.el-form-item) { margin-bottom: 0; }
.profile-form :deep(.el-form-item__label) { padding-bottom: 7px; color: #475569; font-size: 12px; font-weight: 600; line-height: 1.2; }
.profile-form :deep(.el-input__wrapper), .profile-form :deep(.el-select__wrapper) { min-height: 42px; border-radius: 10px; box-shadow: 0 0 0 1px #dbe4f0 inset; }
.profile-form :deep(.el-input__wrapper.is-focus), .profile-form :deep(.el-select__wrapper.is-focused) { box-shadow: 0 0 0 1px #2563eb inset, 0 0 0 3px rgb(37 99 235 / 9%); }
.profile-form :deep(.el-select) { width: 100%; }
.form-actions { display: flex; padding-top: 18px; align-items: center; justify-content: space-between; gap: 18px; border-top: 1px solid #edf1f6; }
.form-actions > span { color: #94a3b8; font-size: 11px; }
.form-actions .el-button { min-width: 108px; min-height: 40px; border-radius: 10px; }
.account-details { display: grid; margin: 0; gap: 8px; }
.account-details > div { padding: 14px 15px; border: 1px solid #e7edf5; border-radius: 12px; background: #f8fafc; }
.account-details dt { margin-bottom: 6px; color: #64748b; font-size: 11px; }
.account-details dd { margin: 0 0 4px; color: #1e293b; font-size: 14px; font-weight: 650; }
.account-details span { color: #94a3b8; font-size: 11px; }
.security-action { display: flex; width: 100%; min-height: 42px; margin-top: 16px; padding: 0 14px; align-items: center; justify-content: space-between; border: 1px solid #dbe4f0; border-radius: 11px; background: white; color: #334155; cursor: pointer; font-size: 12px; transition: .18s ease; }
.security-action > span { display: inline-flex; align-items: center; gap: 8px; }
.security-action:hover { border-color: #93b4f5; background: #f8fbff; color: #2563eb; transform: translateY(-1px); }
.security-action:focus-visible { outline: 3px solid rgb(37 99 235 / 18%); outline-offset: 2px; }
.account-note { display: flex; padding: 20px; align-items: flex-start; gap: 14px; }
.account-note strong { color: #334155; font-size: 13px; }
.account-note p { margin: 7px 0 0; color: #64748b; font-size: 11px; line-height: 1.7; }
.learning-overview { padding: 23px 27px 25px; }
.overview-note { color: #94a3b8; font-size: 11px; }
.learning-stats { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.learning-stats > div { display: flex; min-height: 86px; padding: 16px 18px; align-items: center; gap: 14px; border: 1px solid #e7edf5; border-radius: 13px; background: #fbfdff; }
.stat-icon { width: 42px; height: 42px; border-radius: 12px; font-size: 19px; }
.stat-icon--blue { background: #eff6ff; color: #2563eb; }
.stat-icon--amber { background: #fff7ed; color: #ea580c; }
.stat-icon--purple { background: #f5f3ff; color: #7c3aed; }
.learning-stats p { display: grid; margin: 0; gap: 3px; }
.learning-stats strong { color: #172033; font-size: 22px; line-height: 1; }
.learning-stats small { color: #64748b; font-size: 11px; }
@media (max-width: 900px) {
  .identity-card { grid-template-columns: 1fr; gap: 18px; }
  .identity-context { padding: 18px 0 0; border-top: 1px solid #e2e8f0; border-left: 0; }
  .profile-columns { grid-template-columns: 1fr; }
  .profile-aside { grid-template-columns: minmax(0, 1.2fr) minmax(240px, .8fr); }
}
@media (max-width: 640px) {
  .profile-page { padding: 22px 14px 34px; }
  .profile-header { align-items: flex-start; }
  .profile-header h1 { font-size: 26px; }
  .refresh-button span { display: none; }
  .identity-card { padding: 22px 18px; }
  .profile-avatar { width: 62px; height: 62px; border-radius: 18px; font-size: 25px; }
  .identity-copy strong { font-size: 19px; }
  .identity-context { display: grid; gap: 15px; }
  .profile-form-panel, .security-panel, .learning-overview { padding: 21px 18px; }
  .form-grid, .profile-aside, .learning-stats { grid-template-columns: 1fr; }
  .form-actions { align-items: stretch; flex-direction: column; }
  .form-actions .el-button { width: 100%; margin: 0; }
}
</style>
