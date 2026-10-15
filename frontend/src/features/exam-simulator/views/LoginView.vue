<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { toSafeUserMessage } from '@/api/client'
import SimulatorBrandHeader from '../components/SimulatorBrandHeader.vue'
import { useExamSimulatorStore } from '../stores/exam-simulator'
import type { ExamEntryCode } from '../types'
import { useQualificationStore } from '@/stores/qualification'
import {
  isEighteenDigits,
  isSharedDemoAdmissionTicket,
  isSharedDemoIdentityNumber,
  isTwelveDigits,
  SHARED_DEMO_ADMISSION_TICKET,
  SHARED_DEMO_IDENTITY_NUMBER,
} from '../config/shared-demo-credential'

const props = defineProps<{
  entryCode: ExamEntryCode
}>()

const router = useRouter()
const store = useExamSimulatorStore()
const qualificationStore = useQualificationStore()
const formRef = ref<FormInstance>()
const form = reactive({ admissionTicket: '', identityNumber: '' })

const copy = {
  title: '\u6a21\u62df\u8003\u8bd5\u767b\u5f55',
  essaySubject: '\u8bba\u6587',
  linkedSubject: '\u7efc\u5408\u77e5\u8bc6 + \u6848\u4f8b\u5206\u6790',
  demoTitle: '\u901a\u7528\u6a21\u62df\u51ed\u8bc1',
  admissionLabel: '\u6a21\u62df\u51c6\u8003\u8bc1\u53f7',
  identityLabel: '\u6a21\u62df\u8eab\u4efd\u53f7',
  fillDemo: '\u4e00\u952e\u586b\u5165',
  admissionPlaceholder: '12\u4f4d\u6570\u5b57',
  identityPlaceholder: '18\u4f4d\u6570\u5b57',
  enterExam: '\u8fdb\u5165\u6a21\u62df\u8003\u8bd5',
  reset: '\u6e05\u7a7a',
  admissionFormatError: '\u6a21\u62df\u51c6\u8003\u8bc1\u53f7\u5fc5\u987b\u4e3a12\u4f4d\u6570\u5b57',
  identityFormatError: '\u6a21\u62df\u8eab\u4efd\u53f7\u5fc5\u987b\u4e3a18\u4f4d\u6570\u5b57',
  credentialMismatchError: '\u8bf7\u4f7f\u7528\u9875\u9762\u5c55\u793a\u7684\u901a\u7528\u6a21\u62df\u51ed\u8bc1',
} as const

function validateAdmissionTicket(_rule: unknown, value: string, callback: (error?: Error) => void): void {
  const normalized = value.trim()
  if (!isTwelveDigits(normalized)) {
    callback(new Error(copy.admissionFormatError))
    return
  }
  if (store.demoContext && !isSharedDemoAdmissionTicket(normalized)) {
    callback(new Error(copy.credentialMismatchError))
    return
  }
  callback()
}

function validateIdentityNumber(_rule: unknown, value: string, callback: (error?: Error) => void): void {
  const normalized = value.trim()
  if (!isEighteenDigits(normalized)) {
    callback(new Error(copy.identityFormatError))
    return
  }
  if (store.demoContext && !isSharedDemoIdentityNumber(normalized)) {
    callback(new Error(copy.credentialMismatchError))
    return
  }
  callback()
}

const rules: FormRules = {
  admissionTicket: [{ validator: validateAdmissionTicket, trigger: ['blur', 'change'] }],
  identityNumber: [{ validator: validateIdentityNumber, trigger: ['blur', 'change'] }],
}

async function submit(): Promise<void> {
  const admissionTicket = form.admissionTicket.trim()
  const identityNumber = form.identityNumber.trim()

  const admissionValid = isTwelveDigits(admissionTicket)
  const identityValid = isEighteenDigits(identityNumber)
  const demoMismatch =
    store.demoContext &&
    (!isSharedDemoAdmissionTicket(admissionTicket) || !isSharedDemoIdentityNumber(identityNumber))

  if (formRef.value) {
    await Promise.all(
      formRef.value.fields.map(async (field) => {
        try {
          await field.validate('')
        } catch {
          // field.validate writes the visible Element Plus error state.
        }
      }),
    )
  }

  if (!admissionValid || !identityValid || demoMismatch) return

  try {
    await store.login({
      entryCode: props.entryCode,
      qualificationCode: qualificationStore.selectedQualification?.code
        ?? (props.entryCode === 'ESSAY' || props.entryCode === 'LINKED' ? 'HIGH_PM' : 'MIDDLE_SOFTWARE_DESIGNER'),
      ruleVersion: qualificationStore.activeExamRule?.ruleVersion,
      admissionTicket,
      identityNumber,
    })
    await router.push({
      name: 'exam-candidate',
      params: { sessionId: store.sessionId },
    })
  } catch (error) {
    ElMessage.error(toSafeUserMessage(error))
  }
}

function reset(): void {
  formRef.value?.resetFields()
  form.admissionTicket = ''
  form.identityNumber = ''
  formRef.value?.clearValidate()
  store.errorMessage = ''
}

function fillSharedCredential(): void {
  form.admissionTicket = SHARED_DEMO_ADMISSION_TICKET
  form.identityNumber = SHARED_DEMO_IDENTITY_NUMBER
  formRef.value?.clearValidate()
}
</script>

<template>
  <main class="screen-page login-view" data-testid="p2-login-page">
    <SimulatorBrandHeader :station-no="store.demoContext?.stationNo" />
    <section class="login-view__stage">
      <div class="login-panel">
        <h1>{{ copy.title }} <span>LOGIN</span></h1>
        <p class="login-panel__subject">
          {{ qualificationStore.selectedQualification?.name ?? copy.linkedSubject }}
        </p>
        <div v-if="store.demoContext" class="login-panel__demo" data-testid="demo-credentials">
          <div>
            <strong>{{ copy.demoTitle }}</strong>
            <small>{{ store.demoContext.hint }}</small>
          </div>
          <el-button plain data-testid="fill-demo-credential" @click="fillSharedCredential">
            {{ copy.fillDemo }}
          </el-button>
          <dl>
            <div>
              <dt>{{ copy.admissionLabel }}</dt>
              <dd data-testid="demo-admission-ticket">{{ store.demoContext.admissionTicket }}</dd>
            </div>
            <div>
              <dt>{{ copy.identityLabel }}</dt>
              <dd data-testid="demo-identity-number">{{ store.demoContext.identityNumber }}</dd>
            </div>
          </dl>
        </div>
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
          <el-form-item :label="copy.admissionLabel" prop="admissionTicket">
            <el-input
              v-model="form.admissionTicket"
              size="large"
              autocomplete="off"
              inputmode="numeric"
              maxlength="12"
              :placeholder="copy.admissionPlaceholder"
              data-testid="admission-ticket-input"
            />
          </el-form-item>
          <el-form-item :label="copy.identityLabel" prop="identityNumber">
            <el-input
              v-model="form.identityNumber"
              size="large"
              autocomplete="off"
              inputmode="numeric"
              maxlength="18"
              :placeholder="copy.identityPlaceholder"
              data-testid="identity-number-input"
            />
          </el-form-item>
          <div class="login-panel__actions">
            <el-button
              type="primary"
              class="exam-action-button"
              :loading="store.loading"
              data-testid="login-submit"
              @click="submit"
            >
              {{ copy.enterExam }}
            </el-button>
            <el-button
              class="exam-action-button exam-action-button--orange"
              data-testid="login-reset"
              @click="reset"
            >
              {{ copy.reset }}
            </el-button>
          </div>
        </el-form>
      </div>
    </section>
  </main>
</template>

<style scoped>
.login-view {
  background: linear-gradient(to bottom, #168de2 0 42%, #fff 42% 100%);
}

.login-view__stage {
  display: grid;
  place-items: start center;
  padding-top: 60px;
}

.login-panel {
  width: 520px;
  max-width: calc(100vw - 32px);
  padding: 34px 40px 26px;
  background: #fff;
  border: 1px solid #d7dee7;
  box-shadow: 0 10px 26px rgb(13 82 132 / 14%);
}

.login-panel h1 {
  margin: 0;
  color: #168de2;
  text-align: center;
  font-size: 22px;
}

.login-panel h1 span {
  color: #7b8794;
  font-size: 15px;
}

.login-panel__subject {
  margin: 8px 0 20px;
  color: #526174;
  text-align: center;
}

.login-panel__demo {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 14px 18px;
  margin: 0 0 24px;
  padding: 16px;
  border: 1px solid #c9dcff;
  background: #f5f9ff;
}

.login-panel__demo strong {
  display: block;
  color: #115cb9;
  font-size: 15px;
}

.login-panel__demo small {
  display: block;
  margin-top: 4px;
  color: #526174;
  line-height: 1.5;
}

.login-panel__demo dl {
  grid-column: 1 / -1;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin: 0;
}

.login-panel__demo dl div {
  min-width: 0;
  padding: 10px 12px;
  background: #fff;
  border: 1px solid #dce7f7;
}

.login-panel__demo dt {
  color: #6b7788;
  font-size: 12px;
}

.login-panel__demo dd {
  margin: 4px 0 0;
  color: #172033;
  font-family: ui-monospace, SFMono-Regular, Consolas, 'Liberation Mono', monospace;
  font-size: 15px;
  overflow-wrap: anywhere;
}

.login-panel :deep(.el-input__wrapper) {
  min-height: 52px;
  border-radius: 2px;
}

.login-panel :deep(.el-form-item) {
  margin-bottom: 20px;
}

.login-panel :deep(.el-form-item__error) {
  position: static;
  margin-top: 6px;
  line-height: 1.4;
}

.login-panel__actions {
  display: flex;
  justify-content: center;
  gap: 20px;
  flex-wrap: wrap;
  margin-top: 28px;
}

@media (max-height: 800px) {
  .login-view__stage {
    padding-top: 28px;
  }
  .login-panel {
    padding-top: 24px;
  }
}

@media (max-width: 480px) {
  .login-view__stage {
    padding: 24px 16px 40px;
  }

  .login-panel {
    width: 100%;
    padding: 24px 18px 22px;
  }

  .login-panel__demo {
    grid-template-columns: 1fr;
  }

  .login-panel__demo dl {
    grid-template-columns: 1fr;
  }

  .login-panel__actions {
    display: grid;
    grid-template-columns: 1fr;
  }
}
</style>
