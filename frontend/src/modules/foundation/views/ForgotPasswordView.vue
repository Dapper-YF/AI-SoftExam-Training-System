<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { ArrowLeft, Check, Key, Lock, Message, Phone, Reading } from '@element-plus/icons-vue'
import { toSafeUserMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import type { PlatformAuthCapabilities } from '../types'
import { createRequestId } from '../api/request-id'
import {
  credentialPolicyUnavailableError,
  mainlandMobileError,
  normalizeEmail,
  normalizeMainlandPhone,
  optionalEmailError,
  PASSWORD_REQUIREMENTS_MESSAGE,
  passwordError,
  resolveCredentialPolicy,
} from '../auth-validation'

const authStore = useAuthStore()
const route = useRoute()
const formRef = ref<FormInstance>()
const loading = ref(false)
const challengeLoading = ref(false)
const successMessage = ref('')
const errorMessage = ref('')
const challengeId = ref('')
const devVerificationCode = ref('')
const capabilities = ref<PlatformAuthCapabilities | null>(authStore.capabilities)
const capabilitiesLoading = ref(true)
const requestId = ref(createRequestId())
const form = reactive({
  channel: 'SMS' as 'SMS' | 'EMAIL',
  identifier: '',
  verificationCode: '',
  newPassword: '',
  confirmPassword: '',
})

const smsConfigured = computed(() => capabilities.value?.passwordResetSms === true)
const emailConfigured = computed(() => capabilities.value?.passwordResetEmail === true)
const resetAvailable = computed(() => smsConfigured.value || emailConfigured.value)
const identifierLabel = computed(() => form.channel === 'EMAIL' ? '邮箱' : '手机号')
const identifierPlaceholder = computed(() => form.channel === 'EMAIL' ? '请输入邮箱' : '请输入大陆手机号')
const credentialPolicy = computed(() => resolveCredentialPolicy(capabilities.value))
const credentialPolicyUnavailable = computed(() => !capabilitiesLoading.value && !credentialPolicy.value)
const devChallengeLookupEnabled = import.meta.env.DEV
const DevVerificationCodePanel = devChallengeLookupEnabled
  ? defineAsyncComponent(() => import('@/modules/foundation/views/DevVerificationCodePanel.vue'))
  : null

const rules = computed<FormRules>(() => ({
  identifier: [
    {
      validator: (_rule, value: string, callback) => {
        const message = form.channel === 'EMAIL'
          ? optionalEmailError(value) || (!value.trim() ? '请输入邮箱' : undefined)
          : mainlandMobileError(value)
        if (message) callback(new Error(message))
        else callback()
      },
      trigger: 'blur',
    },
  ],
  verificationCode: [{ required: true, message: '请输入验证码', trigger: 'blur' }],
  newPassword: [{
    validator: (_rule, value: string, callback) => {
      const policy = credentialPolicy.value
      const message = policy
        ? passwordError(value, policy.password)
        : credentialPolicyUnavailableError()
      if (message) callback(new Error(message))
      else callback()
    },
    trigger: 'blur',
  }],
  confirmPassword: [{
    validator: (_rule, value, callback) => {
      if (value !== form.newPassword) callback(new Error('两次输入的密码不一致'))
      else callback()
    },
    trigger: 'blur',
  }],
}))

function captureError(error: unknown): void {
  errorMessage.value = toSafeUserMessage(error)
}

function normalizedIdentifier(): string {
  if (form.channel === 'EMAIL') return normalizeEmail(form.identifier) ?? form.identifier.trim()
  return normalizeMainlandPhone(form.identifier) ?? form.identifier.trim()
}

async function requestChallenge(): Promise<void> {
  if (!resetAvailable.value) return
  if (!(await formRef.value?.validateField('identifier').catch(() => false))) return
  errorMessage.value = ''
  devVerificationCode.value = ''
  challengeLoading.value = true
  try {
    const result = await authStore.requestPasswordResetChallenge({
      identifier: normalizedIdentifier(),
      channel: form.channel,
      requestId: createRequestId(),
    })
    challengeId.value = result.challengeId
    if (devChallengeLookupEnabled) {
      const { readDevVerificationCode } = await import('@/modules/foundation/api/dev-auth-challenge')
      devVerificationCode.value = await readDevVerificationCode(challengeId.value)
    }
  } catch (error) {
    captureError(error)
  } finally {
    challengeLoading.value = false
  }
}

async function submit(): Promise<void> {
  if (!(await formRef.value?.validate().catch(() => false))) return
  loading.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    await authStore.resetPassword({
      challengeId: challengeId.value,
      verificationCode: form.verificationCode.trim(),
      newPassword: form.newPassword,
      requestId: requestId.value,
    })
    successMessage.value = '密码已重置，请返回登录'
    form.newPassword = ''
    form.confirmPassword = ''
    form.verificationCode = ''
  } catch (error) {
    captureError(error)
    requestId.value = createRequestId()
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  const routeIdentifier = route.query.identifier
  if (typeof routeIdentifier === 'string' && routeIdentifier.trim()) {
    form.identifier = routeIdentifier.trim()
  }
  try {
    capabilities.value = await authStore.loadCapabilities()
  } catch (error) {
    captureError(error)
  } finally {
    capabilitiesLoading.value = false
  }
})

watch(emailConfigured, (enabled) => {
  if (!smsConfigured.value && enabled) form.channel = 'EMAIL'
})
</script>

<template>
  <main class="auth-page" data-testid="forgot-password-page">
    <section class="auth-shell">
      <aside class="auth-brand" aria-label="密码安全说明">
        <router-link class="brand-logo" to="/" aria-label="返回首页">
          <span><el-icon><Reading /></el-icon></span>
          <strong>AI高项培训系统</strong>
        </router-link>
        <div class="brand-content">
          <span class="brand-eyebrow">账号安全中心</span>
          <h1>安全找回账号，<br />继续你的学习进度</h1>
          <p>通过已验证的手机号或邮箱确认身份，设置新密码后即可恢复访问。</p>
          <div class="security-notes">
            <div><span><el-icon><Check /></el-icon></span><p><strong>身份验证</strong><small>验证码仅用于本次密码重置</small></p></div>
            <div><span><el-icon><Lock /></el-icon></span><p><strong>凭据保护</strong><small>新密码将按照安全策略加密保存</small></p></div>
          </div>
        </div>
        <p class="brand-footnote">请勿向他人透露短信或邮箱验证码</p>
      </aside>

      <section class="auth-panel">
        <header class="auth-heading">
          <span class="heading-icon"><el-icon><Key /></el-icon></span>
          <div>
            <span>账号恢复</span>
            <h2>找回密码</h2>
            <p>验证身份并设置新的登录密码</p>
          </div>
        </header>

        <div class="reset-progress" aria-label="密码重置流程">
          <div class="is-active"><span>1</span><strong>验证身份</strong></div>
          <i></i>
          <div :class="{ 'is-active': challengeId }"><span>2</span><strong>设置密码</strong></div>
        </div>

        <el-alert
          v-if="!capabilitiesLoading && !resetAvailable"
          title="找回密码服务暂不可用"
          type="warning"
          :closable="false"
          show-icon
          data-testid="forgot-provider-disabled"
        />
        <el-alert
          v-if="credentialPolicyUnavailable"
          title="认证服务暂不可用，请稍后重试"
          type="error"
          :closable="false"
          show-icon
          data-testid="forgot-policy-unavailable"
        />
        <el-alert v-if="successMessage" :title="successMessage" type="success" :closable="false" show-icon data-testid="forgot-success" />
        <el-alert v-if="errorMessage" :title="errorMessage" type="error" :closable="false" show-icon data-testid="forgot-error" />

        <el-form ref="formRef" class="auth-form" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
          <el-form-item v-if="emailConfigured" label="找回方式" prop="channel">
            <el-radio-group v-model="form.channel" data-testid="forgot-channel">
              <el-radio-button v-if="smsConfigured" value="SMS">手机号</el-radio-button>
              <el-radio-button value="EMAIL">邮箱</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item :label="identifierLabel" prop="identifier">
            <el-input
              v-model="form.identifier"
              :prefix-icon="form.channel === 'EMAIL' ? Message : Phone"
              data-testid="forgot-identifier"
              :autocomplete="form.channel === 'EMAIL' ? 'email' : 'tel'"
              :placeholder="identifierPlaceholder"
              @input="challengeId = ''"
            />
          </el-form-item>
          <el-form-item label="验证码" prop="verificationCode">
            <div class="code-row">
              <el-input v-model="form.verificationCode" :prefix-icon="Message" data-testid="forgot-code" inputmode="numeric" maxlength="6" placeholder="请输入验证码" />
              <el-button :disabled="!resetAvailable" :loading="challengeLoading" data-testid="request-forgot-code" @click="requestChallenge">
                获取验证码
              </el-button>
            </div>
            <component
              :is="DevVerificationCodePanel"
              v-if="DevVerificationCodePanel && devVerificationCode"
              kind="forgot"
              :verification-code="devVerificationCode"
              @fill="form.verificationCode = $event"
            />
          </el-form-item>
          <div class="password-divider"><span>设置新密码</span></div>
          <el-form-item label="新密码" prop="newPassword">
            <el-input v-model="form.newPassword" :prefix-icon="Lock" data-testid="forgot-new-password" type="password" show-password autocomplete="new-password" placeholder="请输入新密码" :maxlength="credentialPolicy?.password.maxLength" />
          </el-form-item>
          <p class="password-hint">{{ PASSWORD_REQUIREMENTS_MESSAGE }}</p>
          <el-form-item label="确认新密码" prop="confirmPassword">
            <el-input v-model="form.confirmPassword" :prefix-icon="Lock" data-testid="forgot-confirm-password" type="password" show-password autocomplete="new-password" placeholder="请再次输入新密码" :maxlength="credentialPolicy?.password.maxLength" />
          </el-form-item>
          <el-button class="auth-submit" type="primary" native-type="submit" :loading="loading || capabilitiesLoading" :disabled="capabilitiesLoading || !credentialPolicy" data-testid="forgot-submit" @click.prevent="submit">
            重置密码
          </el-button>
        </el-form>
        <router-link class="auth-back" to="/login"><el-icon><ArrowLeft /></el-icon>返回登录</router-link>
      </section>
    </section>
  </main>
</template>

<style scoped>
.auth-page {
  display: grid;
  min-height: 100vh;
  padding: 32px 20px;
  place-items: center;
  background: #eef2f6;
}

.auth-panel {
  width: min(500px, 100%);
  padding: 28px 32px;
  border: 1px solid #d9e0e8;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 10px 24px rgb(37 54 72 / 8%);
}

h1 {
  margin: 0;
  color: #173b7a;
  font-size: 24px;
}

p {
  margin: 6px 0 18px;
  color: #526173;
}

.auth-panel .el-alert {
  margin-bottom: 14px;
}

.auth-form :deep(.el-form-item) {
  margin-bottom: 24px;
}

.auth-form :deep(.el-form-item__label) {
  padding-bottom: 6px;
  color: #374151;
  font-weight: 700;
}

.auth-form :deep(.el-form-item__content) {
  min-height: 64px;
  padding-bottom: 22px;
}

.auth-form :deep(.el-form-item__error) {
  top: auto;
  bottom: 0;
  min-height: 20px;
  padding-top: 4px;
  line-height: 16px;
}

.auth-form :deep(.el-input__wrapper) {
  width: 100%;
  min-height: 42px;
  border: 1px solid #cfd8e3;
  border-radius: 6px;
  box-shadow: none !important;
}

.auth-form :deep(.el-input__wrapper.is-focus) {
  border-color: #1769e0;
  box-shadow: none !important;
}

.auth-form :deep(.is-error .el-input__wrapper) {
  border-color: #ef5b5b;
  box-shadow: 0 0 0 2px rgb(239 91 91 / 10%) !important;
}

.auth-form :deep(.el-input__inner) {
  border: 0;
  outline: 0;
  box-shadow: none;
}

.auth-form :deep(.el-input__inner:focus-visible) {
  outline: 0;
}

.code-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 116px;
  gap: 10px;
}

.password-hint {
  margin: -4px 0 8px;
  color: #6b7280;
  font-size: 12px;
  line-height: 18px;
}

.auth-submit {
  width: 100%;
  height: 42px;
}

.auth-back {
  display: inline-block;
  margin-top: 16px;
  color: #1769e0;
  font-weight: 700;
  text-decoration: none;
}

.auth-page {
  padding: 30px;
  overflow: hidden;
  background:
    radial-gradient(circle at 12% 8%, rgb(37 99 235 / 8%), transparent 30rem),
    radial-gradient(circle at 90% 92%, rgb(15 118 110 / 7%), transparent 28rem),
    #f4f7fb;
}

.auth-shell {
  display: grid;
  width: min(1080px, 100%);
  min-height: 680px;
  grid-template-columns: minmax(0, 1.08fr) minmax(430px, .92fr);
  overflow: hidden;
  border: 1px solid rgb(203 213 225 / 78%);
  border-radius: 24px;
  background: white;
  box-shadow: 0 30px 80px rgb(15 23 42 / 14%), 0 4px 16px rgb(15 23 42 / 5%);
}

.auth-brand {
  position: relative;
  display: flex;
  padding: 38px 46px;
  overflow: hidden;
  flex-direction: column;
  background:
    radial-gradient(circle at 84% 18%, rgb(96 165 250 / 23%), transparent 18rem),
    linear-gradient(145deg, #172554 0%, #1e3a8a 52%, #1d4ed8 100%);
  color: white;
}

.auth-brand::before,
.auth-brand::after {
  position: absolute;
  border: 1px solid rgb(255 255 255 / 10%);
  border-radius: 50%;
  content: "";
}

.auth-brand::before { right: -150px; bottom: -165px; width: 430px; height: 430px; }
.auth-brand::after { right: -80px; bottom: -90px; width: 285px; height: 285px; }

.brand-logo {
  position: relative;
  z-index: 1;
  display: inline-flex;
  align-self: flex-start;
  align-items: center;
  gap: 11px;
  color: white;
  text-decoration: none;
}

.brand-logo > span {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  border: 1px solid rgb(255 255 255 / 28%);
  border-radius: 13px;
  background: rgb(255 255 255 / 10%);
  font-size: 21px;
}

.brand-logo strong { font-size: 17px; letter-spacing: .02em; }
.brand-content { position: relative; z-index: 1; margin: auto 0; }

.brand-eyebrow {
  display: inline-flex;
  padding: 6px 10px;
  border: 1px solid rgb(191 219 254 / 25%);
  border-radius: 999px;
  background: rgb(255 255 255 / 8%);
  color: #bfdbfe;
  font-size: 10px;
  font-weight: 650;
  letter-spacing: .1em;
}

.brand-content h1 {
  margin: 20px 0 15px;
  color: white;
  font-size: clamp(32px, 4vw, 45px);
  line-height: 1.28;
  letter-spacing: -.035em;
}

.brand-content > p { max-width: 470px; margin: 0; color: #cbd5e1; font-size: 13px; line-height: 1.9; }
.security-notes { display: grid; margin-top: 32px; gap: 14px; }
.security-notes > div { display: flex; align-items: center; gap: 12px; }

.security-notes > div > span {
  display: grid;
  width: 34px;
  height: 34px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 10px;
  background: rgb(255 255 255 / 11%);
  color: #bfdbfe;
}

.security-notes p { display: grid; margin: 0; gap: 3px; }
.security-notes strong { font-size: 12px; }
.security-notes small { color: #aebed5; font-size: 10px; }
.brand-footnote { position: relative; z-index: 1; margin: 0; color: #93c5fd; font-size: 10px; letter-spacing: .08em; }

.auth-panel {
  display: flex;
  width: auto;
  min-width: 0;
  padding: 42px 52px 30px;
  border: 0;
  border-radius: 0;
  flex-direction: column;
  box-shadow: none;
}

.auth-heading { display: flex; margin-bottom: 19px; align-items: center; gap: 13px; }
.heading-icon { display: grid; width: 42px; height: 42px; flex: 0 0 auto; place-items: center; border: 1px solid #bfdbfe; border-radius: 13px; background: #eff6ff; color: #2563eb; font-size: 20px; }
.auth-heading > div { display: grid; gap: 3px; }
.auth-heading > div > span { color: #2563eb; font-size: 10px; font-weight: 700; letter-spacing: .08em; }
.auth-heading h2 { margin: 0; color: #172033; font-size: 24px; }
.auth-heading p { margin: 0; color: #94a3b8; font-size: 10px; }

.reset-progress { display: grid; margin-bottom: 18px; grid-template-columns: 1fr 36px 1fr; align-items: center; }
.reset-progress > div { display: flex; align-items: center; gap: 7px; color: #94a3b8; }
.reset-progress > div:last-child { justify-content: flex-end; }
.reset-progress span { display: grid; width: 23px; height: 23px; place-items: center; border-radius: 50%; background: #e9eef5; font-size: 9px; }
.reset-progress strong { font-size: 10px; font-weight: 600; }
.reset-progress i { height: 1px; background: #dbe4f0; }
.reset-progress .is-active { color: #2563eb; }
.reset-progress .is-active span { background: #2563eb; color: white; }

.auth-panel .el-alert { margin-bottom: 13px; border-radius: 9px; }
.auth-form :deep(.el-form-item__label) { padding-bottom: 7px; color: #475569; font-size: 11px; font-weight: 650; line-height: 1.2; }
.auth-form :deep(.el-input__wrapper) {
  min-height: 44px;
  padding: 0 13px;
  border-color: #d8e1ec;
  border-radius: 10px;
  background: #fbfdff;
}
.auth-form :deep(.el-input__wrapper.is-focus) { border-color: #2563eb; box-shadow: 0 0 0 3px rgb(37 99 235 / 9%) !important; }
.auth-form :deep(.el-input__inner) { height: 42px; line-height: 42px; }
.auth-form :deep(.el-radio-group) { width: 100%; }
.auth-form :deep(.el-radio-button) { flex: 1; }
.auth-form :deep(.el-radio-button__inner) { width: 100%; border-radius: 8px; font-size: 11px; }
.code-row .el-button { height: 44px; border-radius: 10px; }

.password-divider {
  display: flex;
  margin: -2px 0 15px;
  align-items: center;
  gap: 10px;
  color: #94a3b8;
  font-size: 10px;
}
.password-divider::before, .password-divider::after { height: 1px; flex: 1; background: #edf1f6; content: ""; }
.password-hint { margin: -16px 0 12px; color: #94a3b8; font-size: 9px; line-height: 1.5; }
.auth-submit { height: 46px; border-radius: 10px; box-shadow: 0 8px 18px rgb(37 99 235 / 20%); font-weight: 650; }
.auth-back { display: inline-flex; margin: 18px auto 0; align-items: center; gap: 5px; color: #64748b; font-size: 10px; font-weight: 600; }
.auth-back:hover { color: #2563eb; }

@media (max-width: 860px) {
  .auth-page { padding: 20px; }
  .auth-shell { width: min(500px, 100%); min-height: 0; grid-template-columns: 1fr; }
  .auth-brand { display: none; }
  .auth-panel { min-height: 680px; padding: 38px 38px 28px; }
}

@media (max-width: 480px) {
  .auth-page { padding: 0; place-items: stretch; background: white; }
  .auth-shell { width: 100%; min-height: 100vh; border: 0; border-radius: 0; box-shadow: none; }
  .auth-panel { min-height: 100vh; padding: 30px 20px 22px; }
  .auth-heading h2 { font-size: 22px; }
  .code-row { grid-template-columns: minmax(0, 1fr) 104px; }
}
</style>
