<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import {
  Check,
  DataAnalysis,
  Lock,
  Message,
  Phone,
  Reading,
  User,
} from '@element-plus/icons-vue'
import { toSafeUserMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { loginIdentifierError, mainlandMobileError, normalizeLoginIdentifier, normalizeMainlandPhone } from '../auth-validation'
import { createRequestId } from '../api/request-id'
import { isExplicitMockEnabled } from '../api/mock-mode'
import type { PlatformAuthCapabilities } from '../types'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const formRef = ref<FormInstance>()
const form = reactive({
  identifier: '',
  password: '',
  mobile: '',
  verificationCode: '',
  registerPhone: '',
  registerVerificationCode: '',
})
const errorMessage = ref('')
const identifierClientError = ref('')
const capabilities = ref<PlatformAuthCapabilities | null>(null)
const loginMode = ref<'password' | 'sms'>('password')
const authMode = ref<'login' | 'register'>(route.query.mode === 'register' ? 'register' : 'login')
const challengeId = ref('')
const registerChallengeId = ref('')
const retryAfterSeconds = ref(0)
const registerRetryAfterSeconds = ref(0)
const devChallengeLookupEnabled = import.meta.env.DEV
const devMockEnabled = import.meta.env.DEV && isExplicitMockEnabled(import.meta.env.VITE_PLATFORM_MOCK)
let retryTimer: ReturnType<typeof setInterval> | undefined
let registerRetryTimer: ReturnType<typeof setInterval> | undefined

function identifierError(value: string): string | undefined {
  const error = loginIdentifierError(value)
  if (error) return error
  const isSeedUsername = ['student', 'admin', 'question_reviewer'].includes(value.trim())
  return isSeedUsername && capabilities.value?.devSeedUsernameLoginEnabled !== true
    ? '手机号或邮箱格式不正确'
    : undefined
}

const rules: FormRules = {
  identifier: [{
    validator: (_rule, value: string, callback) => {
      const message = identifierError(value)
      if (message) callback(new Error(message))
      else callback()
    },
    trigger: 'blur',
  }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  mobile: [{
    validator: (_rule, value: string, callback) => {
      const message = mainlandMobileError(value)
      if (message) callback(new Error(message))
      else callback()
    },
    trigger: 'blur',
  }],
  verificationCode: [{ required: true, message: '请输入短信验证码', trigger: 'blur' }],
  registerPhone: [{
    validator: (_rule, value: string, callback) => {
      const message = mainlandMobileError(value)
      if (message) callback(new Error(message))
      else callback()
    },
    trigger: 'blur',
  }],
  registerVerificationCode: [{ required: true, message: '请输入短信验证码', trigger: 'blur' }],
}

const registeredMessage = computed(() => route.query.registered === '1')
const passwordLoginUnavailable = computed(() =>
  capabilities.value !== null && !capabilities.value.usernamePassword
)
const smsLoginUnavailable = computed(() => capabilities.value !== null && !capabilities.value.smsLogin)
const registrationUnavailable = computed(() =>
  capabilities.value !== null
  && (capabilities.value.registrationVerificationRequired !== false && !capabilities.value.registrationSms)
)

function selectAuthMode(mode: 'login' | 'register'): void {
  authMode.value = mode
  errorMessage.value = ''
  identifierClientError.value = ''
  formRef.value?.clearValidate()
}

function selectLoginMode(mode: 'password' | 'sms'): void {
  loginMode.value = mode
  errorMessage.value = ''
  identifierClientError.value = ''
  formRef.value?.clearValidate()
}

function safeRedirect(): string {
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : ''
  if (!redirect || !redirect.startsWith('/') || redirect.startsWith('//') || redirect.startsWith('/login') || redirect.startsWith('/register')) {
    return authStore.homePath
  }
  return redirect
}

async function submit(): Promise<void> {
  if (loginMode.value === 'password') {
    const identifierMessage = identifierError(form.identifier)
    if (identifierMessage) {
      identifierClientError.value = identifierMessage
      return
    }
  }
  identifierClientError.value = ''
  const fields = loginMode.value === 'password'
    ? ['identifier', 'password']
    : ['mobile', 'verificationCode']
  if (!(await formRef.value?.validateField(fields).then(() => true).catch(() => false))) return
  errorMessage.value = ''
  try {
    if (loginMode.value === 'password') {
      await authStore.login({
        identifier: normalizeLoginIdentifier(form.identifier),
        password: form.password,
      })
    } else {
      if (!challengeId.value) {
        errorMessage.value = '请先获取短信验证码'
        return
      }
      await authStore.smsLogin({
        mobile: normalizeMainlandPhone(form.mobile)!,
        challengeId: challengeId.value,
        verificationCode: form.verificationCode.trim(),
        requestId: createRequestId(),
      })
    }
    await router.replace(safeRedirect())
  } catch (error) {
    errorMessage.value = toSafeUserMessage(error)
  }
}

function startCountdown(seconds: number): void {
  if (retryTimer) clearInterval(retryTimer)
  retryAfterSeconds.value = Math.max(1, seconds)
  retryTimer = setInterval(() => {
    retryAfterSeconds.value -= 1
    if (retryAfterSeconds.value <= 0 && retryTimer) {
      clearInterval(retryTimer)
      retryTimer = undefined
    }
  }, 1000)
}

function startRegisterCountdown(seconds: number): void {
  if (registerRetryTimer) clearInterval(registerRetryTimer)
  registerRetryAfterSeconds.value = Math.max(1, seconds)
  registerRetryTimer = setInterval(() => {
    registerRetryAfterSeconds.value -= 1
    if (registerRetryAfterSeconds.value <= 0 && registerRetryTimer) {
      clearInterval(registerRetryTimer)
      registerRetryTimer = undefined
    }
  }, 1000)
}

async function requestSmsCode(): Promise<void> {
  const mobile = normalizeMainlandPhone(form.mobile)
  if (!mobile) {
    await formRef.value?.validateField('mobile').catch(() => undefined)
    return
  }
  errorMessage.value = ''
  try {
    const challenge = await authStore.requestSmsLoginChallenge({
      mobile,
      requestId: createRequestId(),
    })
    challengeId.value = challenge.challengeId
    if (devMockEnabled) {
      form.verificationCode = '000000'
    } else if (devChallengeLookupEnabled) {
      const { readDevVerificationCode } = await import('@/modules/foundation/api/dev-auth-challenge')
      form.verificationCode = await readDevVerificationCode(challengeId.value)
    }
    startCountdown(challenge.retryAfterSeconds ?? 60)
  } catch (error) {
    errorMessage.value = toSafeUserMessage(error)
  }
}

async function requestRegisterCode(): Promise<void> {
  const phone = normalizeMainlandPhone(form.registerPhone)
  if (!phone) {
    await formRef.value?.validateField('registerPhone').catch(() => undefined)
    return
  }
  errorMessage.value = ''
  try {
    const challenge = await authStore.requestRegistrationChallenge({
      phone,
      requestId: createRequestId(),
    })
    registerChallengeId.value = challenge.challengeId
    if (devMockEnabled) {
      form.registerVerificationCode = '000000'
    } else if (devChallengeLookupEnabled) {
      const { readDevVerificationCode } = await import('@/modules/foundation/api/dev-auth-challenge')
      form.registerVerificationCode = await readDevVerificationCode(registerChallengeId.value)
    }
    startRegisterCountdown(challenge.retryAfterSeconds ?? 60)
  } catch (error) {
    errorMessage.value = toSafeUserMessage(error)
  }
}

function createRegistrationIdentity(phone: string): { nickname: string; password: string } {
  const randomValues = crypto.getRandomValues(new Uint32Array(2))
  const firstRandom = randomValues[0] ?? 0
  const secondRandom = randomValues[1] ?? 0
  const suffix = `${firstRandom.toString(36)}${secondRandom.toString(36)}`.slice(0, 8)
  return {
    nickname: `用户${phone.slice(-4)}${suffix}`,
    password: `Aa1!${suffix}${secondRandom.toString(16)}`,
  }
}

async function submitRegistration(): Promise<void> {
  const phone = normalizeMainlandPhone(form.registerPhone)
  if (!phone) {
    await formRef.value?.validateField('registerPhone').catch(() => undefined)
    return
  }
  if (!registerChallengeId.value) {
    errorMessage.value = '请先获取短信验证码'
    return
  }
  if (!form.registerVerificationCode.trim()) {
    await formRef.value?.validateField('registerVerificationCode').catch(() => undefined)
    return
  }
  errorMessage.value = ''
  const identity = createRegistrationIdentity(phone)
  try {
    await authStore.register({
      nickname: identity.nickname,
      phone,
      password: identity.password,
      challengeId: registerChallengeId.value,
      verificationCode: form.registerVerificationCode.trim(),
      requestId: createRequestId(),
    })
    await authStore.login({ identifier: phone, password: identity.password })
    form.registerVerificationCode = ''
    await router.replace(authStore.homePath)
  } catch (error) {
    errorMessage.value = toSafeUserMessage(error)
  }
}

onMounted(async () => {
  try {
    capabilities.value = await authStore.loadCapabilities()
  } catch (error) {
    errorMessage.value = toSafeUserMessage(error)
  }
})

onBeforeUnmount(() => {
  if (retryTimer) clearInterval(retryTimer)
  if (registerRetryTimer) clearInterval(registerRetryTimer)
})
</script>

<template>
  <main class="platform-login-page" data-testid="platform-login-page">
    <section class="login-shell">
      <aside class="login-brand" aria-label="平台介绍">
        <router-link class="brand-logo" to="/" aria-label="返回首页">
          <span><el-icon><Reading /></el-icon></span>
          <strong>AI高项培训系统</strong>
        </router-link>

        <div class="brand-content">
          <span class="brand-eyebrow">AI 驱动的备考工作台</span>
          <h1>让每一次练习，<br />都更接近考试目标</h1>
          <p>围绕你的资格方向，整合题库训练、学习数据与智能辅助，形成持续可追踪的备考路径。</p>

          <div class="brand-features">
            <div>
              <span><el-icon><Check /></el-icon></span>
              <p><strong>个性化学习路径</strong><small>按资格与薄弱点推荐练习内容</small></p>
            </div>
            <div>
              <span><el-icon><DataAnalysis /></el-icon></span>
              <p><strong>学习进度持续追踪</strong><small>集中查看练习表现与知识点掌握度</small></p>
            </div>
          </div>
        </div>

        <p class="brand-footnote">专注软考学习与能力提升</p>
      </aside>

      <section class="login-panel" aria-labelledby="login-title">
        <header class="login-heading">
          <span class="mobile-brand-mark" aria-hidden="true"><el-icon><Reading /></el-icon></span>
          <div>
            <span>{{ authMode === 'login' ? '欢迎回来' : '创建学习账号' }}</span>
            <h2 id="login-title">{{ authMode === 'login' ? '登录你的学习账号' : '手机号快速注册' }}</h2>
            <p>{{ authMode === 'login' ? '继续上次的学习进度' : '验证手机号后即可开始学习' }}</p>
          </div>
        </header>

        <el-alert
          v-if="registeredMessage"
          title="注册成功，请使用手机号或邮箱和密码登录"
          type="success"
          :closable="false"
          show-icon
          data-testid="login-registered-message"
        />

        <div class="login-method-title" data-testid="login-methods">{{ authMode === 'login' ? '登录' : '注册' }}</div>

        <div v-if="authMode === 'login'" class="login-mode-switch" data-testid="login-mode-switch" aria-label="登录方式">
          <button
            type="button"
            :class="{ 'is-active': loginMode === 'password' }"
            data-testid="login-mode-password"
            @click="selectLoginMode('password')"
          >
            账号密码登录
          </button>
          <button
            type="button"
            :class="{ 'is-active': loginMode === 'sms' }"
            data-testid="login-mode-sms"
            @click="selectLoginMode('sms')"
          >
            短信验证码登录
          </button>
        </div>

        <el-alert
          v-if="errorMessage"
          :title="errorMessage"
          type="error"
          :closable="false"
          show-icon
          data-testid="login-error"
        />

        <el-alert
          v-if="loginMode === 'password' && passwordLoginUnavailable"
          title="账号登录服务暂不可用"
          type="warning"
          :closable="false"
          show-icon
          data-testid="password-login-unavailable"
        />

        <el-alert
          v-if="loginMode === 'sms' && smsLoginUnavailable"
          title="短信验证码登录服务暂不可用"
          type="warning"
          :closable="false"
          show-icon
          data-testid="sms-login-unavailable"
        />

        <el-alert
          v-if="authMode === 'register' && registrationUnavailable"
          title="短信注册服务暂不可用"
          type="warning"
          :closable="false"
          show-icon
          data-testid="register-provider-disabled"
        />

        <el-form
          ref="formRef"
          class="auth-form"
          :model="form"
          :rules="rules"
          label-position="top"
          @submit.prevent="authMode === 'login' ? submit() : submitRegistration()"
        >
          <template v-if="authMode === 'login' && loginMode === 'password'">
            <el-form-item label="手机号或邮箱" prop="identifier" :class="{ 'is-error': identifierClientError }">
              <el-input
                v-model="form.identifier"
                :prefix-icon="User"
                autocomplete="username"
                data-testid="platform-identifier"
                placeholder="请输入手机号或邮箱"
                @input="identifierClientError = ''"
              />
              <p
                v-if="identifierClientError"
                class="identifier-client-error"
                role="alert"
                data-testid="identifier-client-error"
              >
                {{ identifierClientError }}
              </p>
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input
                v-model="form.password"
                :prefix-icon="Lock"
                autocomplete="current-password"
                data-testid="platform-password"
                placeholder="请输入密码"
                show-password
                type="password"
                @keyup.enter="submit"
              />
            </el-form-item>
          </template>

          <template v-else-if="authMode === 'login'">
            <el-form-item label="手机号" prop="mobile">
              <el-input
                v-model="form.mobile"
                :prefix-icon="Phone"
                autocomplete="tel"
                data-testid="sms-login-mobile"
                placeholder="请输入大陆手机号"
                @input="challengeId = ''"
              />
            </el-form-item>
            <el-form-item label="短信验证码" prop="verificationCode">
              <div class="code-row">
                <el-input
                  v-model="form.verificationCode"
                  :prefix-icon="Message"
                  autocomplete="one-time-code"
                  inputmode="numeric"
                  maxlength="6"
                  data-testid="sms-login-code"
                  placeholder="请输入6位验证码"
                />
                <el-button
                  :disabled="smsLoginUnavailable || retryAfterSeconds > 0"
                  data-testid="sms-login-request-code"
                  @click.prevent="requestSmsCode"
                >
                  {{ retryAfterSeconds > 0 ? `${retryAfterSeconds}s` : '获取验证码' }}
                </el-button>
              </div>
            </el-form-item>
          </template>

          <template v-else>
            <el-form-item label="手机号" prop="registerPhone">
              <el-input
                v-model="form.registerPhone"
                :prefix-icon="Phone"
                autocomplete="tel"
                data-testid="register-phone"
                placeholder="请输入大陆手机号"
                @input="registerChallengeId = ''"
              />
            </el-form-item>
            <el-form-item label="短信验证码" prop="registerVerificationCode">
              <div class="code-row">
                <el-input
                v-model="form.registerVerificationCode"
                  :prefix-icon="Message"
                  autocomplete="one-time-code"
                  inputmode="numeric"
                  maxlength="6"
                  data-testid="register-code"
                  placeholder="请输入6位验证码"
                />
                <el-button
                  :disabled="registrationUnavailable || registerRetryAfterSeconds > 0"
                  data-testid="request-register-code"
                  @click.prevent="requestRegisterCode"
                >
                  {{ registerRetryAfterSeconds > 0 ? `${registerRetryAfterSeconds}s` : '获取验证码' }}
                </el-button>
              </div>
            </el-form-item>
          </template>

          <div v-if="authMode === 'login'" class="password-helper">
            <span>安全登录，学习数据实时同步</span>
            <router-link to="/forgot-password" data-testid="forgot-password-link">忘记密码</router-link>
          </div>

          <el-button
            class="login-submit"
            type="primary"
            native-type="submit"
            :loading="authStore.loading"
            :disabled="authMode === 'register'
              ? registrationUnavailable
              : (loginMode === 'password' ? passwordLoginUnavailable : smsLoginUnavailable)"
            data-testid="platform-login-submit"
            @click.prevent="authMode === 'login' ? submit() : submitRegistration()"
          >
            {{ authMode === 'login' ? '登录' : '注册并开始学习' }}
          </el-button>
        </el-form>

        <div class="register-prompt">
          <span>{{ authMode === 'login' ? '还没有学习账号？' : '已经有学习账号？' }}</span>
          <button
            v-if="authMode === 'login'"
            type="button"
            data-testid="register-link"
            @click="selectAuthMode('register')"
          >
            注册账号
          </button>
          <button v-else type="button" data-testid="back-to-login" @click="selectAuthMode('login')">返回登录</button>
        </div>

        <footer class="login-links">
          <router-link to="/" data-testid="login-home-link">返回首页</router-link>
          <span>登录即表示你已阅读并同意平台使用规范</span>
        </footer>
      </section>
    </section>
  </main>
</template>

<style scoped>
.platform-login-page {
  display: grid;
  min-height: 100vh;
  padding: 30px;
  place-items: center;
  overflow: hidden;
  background:
    radial-gradient(circle at 12% 8%, rgb(37 99 235 / 8%), transparent 30rem),
    radial-gradient(circle at 90% 92%, rgb(15 118 110 / 7%), transparent 28rem),
    #f4f7fb;
}
.login-shell {
  display: grid;
  width: min(1080px, 100%);
  min-height: 660px;
  grid-template-columns: minmax(0, 1.08fr) minmax(430px, .92fr);
  overflow: hidden;
  border: 1px solid rgb(203 213 225 / 78%);
  border-radius: 24px;
  background: white;
  box-shadow: 0 30px 80px rgb(15 23 42 / 14%), 0 4px 16px rgb(15 23 42 / 5%);
}
.login-brand {
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
.login-brand::before, .login-brand::after {
  position: absolute;
  border: 1px solid rgb(255 255 255 / 10%);
  border-radius: 50%;
  content: "";
}
.login-brand::before { right: -150px; bottom: -165px; width: 430px; height: 430px; }
.login-brand::after { right: -80px; bottom: -90px; width: 285px; height: 285px; }
.brand-logo { position: relative; z-index: 1; display: inline-flex; align-self: flex-start; align-items: center; gap: 11px; color: white; text-decoration: none; }
.brand-logo > span, .mobile-brand-mark {
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
.brand-eyebrow { display: inline-flex; padding: 6px 10px; border: 1px solid rgb(191 219 254 / 25%); border-radius: 999px; background: rgb(255 255 255 / 8%); color: #bfdbfe; font-size: 10px; font-weight: 650; letter-spacing: .1em; }
.brand-content h1 { margin: 20px 0 15px; font-size: clamp(32px, 4vw, 45px); line-height: 1.28; letter-spacing: -.035em; }
.brand-content > p { max-width: 470px; margin: 0; color: #cbd5e1; font-size: 13px; line-height: 1.9; }
.brand-features { display: grid; margin-top: 32px; gap: 14px; }
.brand-features > div { display: flex; align-items: center; gap: 12px; }
.brand-features > div > span { display: grid; width: 34px; height: 34px; flex: 0 0 auto; place-items: center; border-radius: 10px; background: rgb(255 255 255 / 11%); color: #bfdbfe; }
.brand-features p { display: grid; margin: 0; gap: 3px; }
.brand-features strong { font-size: 12px; }
.brand-features small { color: #aebed5; font-size: 10px; }
.brand-footnote { position: relative; z-index: 1; margin: 0; color: #93c5fd; font-size: 10px; letter-spacing: .12em; }
.login-panel {
  display: flex;
  min-width: 0;
  padding: 50px 52px 34px;
  flex-direction: column;
  background: #fff;
}
.login-heading { display: flex; margin-bottom: 25px; align-items: center; gap: 13px; }
.mobile-brand-mark { display: none; border-color: #bfdbfe; background: #eff6ff; color: #2563eb; }
.login-heading > div { display: grid; gap: 4px; }
.login-heading span { color: #2563eb; font-size: 11px; font-weight: 700; letter-spacing: .08em; }
.login-heading h2 { margin: 0; color: #172033; font-size: 25px; line-height: 1.3; }
.login-heading p { margin: 0; color: #94a3b8; font-size: 11px; }
.login-method-title { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); }
.login-mode-switch {
  display: grid;
  grid-template-columns: 1fr 1fr;
  margin-bottom: 20px;
  padding: 4px;
  border-radius: 11px;
  background: #f1f5f9;
}
.login-mode-switch button {
  min-height: 39px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #64748b;
  cursor: pointer;
  font-size: 12px;
  transition: .18s ease;
}
.login-mode-switch button.is-active { background: white; box-shadow: 0 3px 9px rgb(15 23 42 / 8%); color: #1d4ed8; font-weight: 700; }
.login-mode-switch button:focus-visible { outline: 3px solid rgb(37 99 235 / 16%); outline-offset: 1px; }
.login-panel .el-alert { margin-bottom: 16px; border-radius: 9px; }
.auth-form { margin-top: 2px; }
.auth-form :deep(.el-form-item) { margin-bottom: 24px; }
.auth-form :deep(.el-form-item__label) { padding-bottom: 7px; color: #475569; font-size: 12px; font-weight: 650; line-height: 1.2; }
.auth-form :deep(.el-form-item__content) { min-height: 66px; padding-bottom: 22px; }
.auth-form :deep(.el-form-item__error) { top: auto; bottom: 0; min-height: 20px; padding-top: 4px; line-height: 16px; }
.identifier-client-error { position: absolute; bottom: 0; left: 0; min-height: 20px; margin: 0; padding-top: 4px; color: var(--el-color-danger); font-size: 12px; line-height: 16px; }
.auth-form :deep(.el-input__wrapper) {
  width: 100%;
  min-height: 46px;
  box-sizing: border-box;
  padding: 0 13px;
  border: 1px solid #d8e1ec;
  border-radius: 10px;
  background: #fbfdff;
  box-shadow: none !important;
  transition: border-color .16s ease, box-shadow .16s ease;
}
.auth-form :deep(.el-input__wrapper.is-focus) { border-color: #2563eb; box-shadow: 0 0 0 3px rgb(37 99 235 / 9%) !important; }
.auth-form :deep(.is-error .el-input__wrapper) { border-color: #ef5b5b; box-shadow: 0 0 0 2px rgb(239 91 91 / 10%) !important; }
.auth-form :deep(.el-input__inner) { height: 44px; border: 0; outline: 0; box-shadow: none; line-height: 44px; }
.auth-form :deep(.el-input__inner:focus-visible) { outline: 0; }
.code-row { display: grid; width: 100%; grid-template-columns: minmax(0, 1fr) 112px; gap: 10px; }
.code-row .el-button { height: 46px; border-radius: 10px; }
.password-helper { display: flex; margin: -5px 0 16px; align-items: center; justify-content: space-between; gap: 12px; color: #94a3b8; font-size: 10px; }
.password-helper a { color: #2563eb; font-size: 11px; font-weight: 600; text-decoration: none; }
.login-submit { width: 100%; height: 46px; margin-top: 2px; border-radius: 10px; box-shadow: 0 8px 18px rgb(37 99 235 / 20%); font-weight: 650; }
.register-prompt { display: flex; margin-top: 20px; align-items: center; justify-content: center; gap: 5px; color: #64748b; font-size: 11px; }
.register-prompt button { padding: 0; border: 0; background: transparent; color: #2563eb; cursor: pointer; font-size: 11px; font-weight: 700; }
.login-links { display: flex; margin-top: auto; padding-top: 24px; align-items: center; justify-content: space-between; gap: 15px; border-top: 1px solid #edf1f6; }
.login-links a { color: #64748b; font-size: 10px; text-decoration: none; }
.login-links a:hover, .password-helper a:hover, .register-prompt button:hover { color: #1d4ed8; }
.login-links span { color: #b0bac7; font-size: 9px; text-align: right; }
@media (max-width: 860px) {
  .platform-login-page { padding: 20px; }
  .login-shell { width: min(500px, 100%); min-height: 0; grid-template-columns: 1fr; }
  .login-brand { display: none; }
  .login-panel { min-height: 620px; padding: 40px 38px 28px; }
  .mobile-brand-mark { display: grid; }
}
@media (max-width: 480px) {
  .platform-login-page { padding: 0; place-items: stretch; background: white; }
  .login-shell { width: 100%; min-height: 100vh; border: 0; border-radius: 0; box-shadow: none; }
  .login-panel { min-height: 100vh; padding: 32px 20px 22px; }
  .login-heading h2 { font-size: 22px; }
  .login-mode-switch { grid-template-columns: 1fr 1fr; }
  .code-row { grid-template-columns: minmax(0, 1fr) 104px; }
  .password-helper > span { display: none; }
  .password-helper { justify-content: flex-end; }
}
</style>
