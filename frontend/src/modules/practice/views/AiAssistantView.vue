<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Calendar,
  Close,
  DataAnalysis,
  FolderOpened,
  Loading,
  MagicStick,
  Promotion,
} from '@element-plus/icons-vue'
import { platformApiClient, toSafeUserMessage, unwrapApiResponse } from '@/api/client'
import { useQualificationStore } from '@/stores/qualification'
import AiPracticePlannerView from './AiPracticePlannerView.vue'

type Citation = { fragmentId?: string; documentTitle: string; excerpt: string }
type Message = { id: string; role: string; content: string; answerStatus?: string; citations?: Citation[]; createdAt: string; imageUrl?: string }
type Session = { id: string; qualificationId?: string; subjectId?: string; questionId?: string; title?: string }
type Attachment = { id: string; ocrStatus: string; ocrText?: string; detectedQuestionType?: string; ocrConfidence?: number; ocrEngine?: string }

const route = useRoute()
const router = useRouter()
const qualification = useQualificationStore()
const session = ref<Session | null>(null)
const messages = ref<Message[]>([])
const draft = ref('')
const loading = ref(false)
const uploadFile = ref<File | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const attachment = ref<Attachment | null>(null)
const ocrDraft = ref('')
const confirmingOcr = ref(false)
const previewUrl = ref('')
const isDragging = ref(false)
const tabs = [
  { value: 'assistant', label: '智能答疑', description: '文字与图片题目解析', icon: MagicStick },
  { value: 'question', label: '智能出题', description: '知识点与薄弱点专项练习', icon: DataAnalysis },
  { value: 'paper', label: '智能组卷', description: '组卷计划与阶段重点', icon: Calendar },
] as const
type AiTab = typeof tabs[number]['value']
const activeTab = computed<AiTab>(() => {
  const tab = String(route.query.tab ?? 'assistant')
  return tabs.some((item) => item.value === tab) ? tab as AiTab : 'assistant'
})
const currentScopeLabel = computed(() => [
  qualification.selectedQualification?.name,
  qualification.selectedSubject?.name,
].filter(Boolean).join(' · ') || '当前备考方向')

async function restoreCurrentSession() {
  session.value = await unwrapApiResponse<Session>(platformApiClient.post('/ai-assistant/current-session'))
}

async function selectTab(tab: AiTab) {
  await router.replace({ query: { ...route.query, tab } })
}

async function load() {
  if (!session.value) return
  const detail = await unwrapApiResponse<{ session: Session; messages: Message[] }>(platformApiClient.get(`/ai-assistant/sessions/${session.value.id}`))
  session.value = detail.session
  messages.value = detail.messages
}

function setPreview(file: File) {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  uploadFile.value = file
  previewUrl.value = URL.createObjectURL(file)
}

function clearAttachment() {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  uploadFile.value = null
  attachment.value = null
  ocrDraft.value = ''
  previewUrl.value = ''
  if (fileInput.value) fileInput.value.value = ''
}

async function upload(file = uploadFile.value) {
  if (!session.value || !file) return
  try {
    const body = new FormData(); body.append('file', file)
    attachment.value = await unwrapApiResponse<Attachment>(platformApiClient.post(`/ai-assistant/sessions/${session.value.id}/attachments`, body))
    ocrDraft.value = attachment.value.ocrText ?? ''
  } catch (error) { clearAttachment(); ElMessage.error(toSafeUserMessage(error)) }
}

async function selectFile(file: File | null) {
  if (!file || !file.type.startsWith('image/')) return
  setPreview(file)
  await upload(file)
}

async function confirmOcr(showSuccess = true) {
  if (!attachment.value || attachment.value.ocrStatus === 'CONFIRMED') return true
  if (!ocrDraft.value.trim()) {
    ElMessage.warning('未识别出题目文字，请手动补充后再发送')
    return false
  }
  confirmingOcr.value = true
  try {
    const confidence = attachment.value.ocrConfidence
    const confirmed = await unwrapApiResponse<Attachment>(platformApiClient.put(
      `/ai-assistant/attachments/${attachment.value.id}/ocr`,
      ocrDraft.value.trim(),
      { headers: { 'Content-Type': 'text/plain' } },
    ))
    attachment.value = { ...attachment.value, ...confirmed, ocrConfidence: confidence }
    ocrDraft.value = confirmed.ocrText ?? ocrDraft.value.trim()
    if (showSuccess) ElMessage.success('题目文字已确认')
    return true
  } catch (error) {
    ElMessage.error(toSafeUserMessage(error))
    return false
  } finally {
    confirmingOcr.value = false
  }
}

async function send() {
  if (!session.value || (!draft.value.trim() && !attachment.value)) return
  const text = draft.value.trim() || '请帮我解答这道题。'
  const image = previewUrl.value
  loading.value = true
  try {
    if (attachment.value && !(await confirmOcr(false))) return
    const answer = await unwrapApiResponse<Message>(platformApiClient.post(`/ai-assistant/sessions/${session.value.id}/messages`, { content: text, attachmentId: attachment.value?.id }))
    messages.value.push({ id: `local-${Date.now()}`, role: 'USER', content: text, createdAt: new Date().toISOString(), imageUrl: image }, answer)
    draft.value = ''
    clearAttachment()
  } catch (error) { ElMessage.error(toSafeUserMessage(error)) } finally { loading.value = false }
}

async function handlePaste(event: ClipboardEvent) {
  const image = Array.from(event.clipboardData?.items ?? []).find(item => item.type.startsWith('image/'))?.getAsFile()
  if (!image) return
  event.preventDefault()
  await selectFile(new File([image], `pasted-question-${Date.now()}.png`, { type: image.type || 'image/png' }))
}

function handleWindowPaste(event: ClipboardEvent) {
  const target = event.target as HTMLElement | null
  if (target?.tagName === 'TEXTAREA' || target?.tagName === 'INPUT') return
  void handlePaste(event)
}

async function handleDrop(event: DragEvent) {
  isDragging.value = false
  const file = Array.from(event.dataTransfer?.files ?? []).find(item => item.type.startsWith('image/'))
  await selectFile(file ?? null)
}

async function feedback(message: Message, feedbackCode: 'HELPFUL' | 'NOT_HELPFUL' | 'REPORT') {
  try { await unwrapApiResponse(platformApiClient.post(`/ai-assistant/messages/${message.id}/feedback`, { feedbackCode })); ElMessage.success('反馈已记录') }
  catch (error) { ElMessage.error(toSafeUserMessage(error)) }
}

onMounted(async () => {
  window.addEventListener('paste', handleWindowPaste)
  try { await restoreCurrentSession(); await load() } catch (error) { ElMessage.error(toSafeUserMessage(error)) }
})
onUnmounted(() => { window.removeEventListener('paste', handleWindowPaste); if (previewUrl.value) URL.revokeObjectURL(previewUrl.value) })
</script>

<template>
  <main class="ai-module-page" data-testid="ai-assistance-page">
    <header class="ai-module-heading">
      <div>
        <span class="ai-module-heading__eyebrow">AI 学习助手</span>
        <h1>AI辅助</h1>
        <p>从即时答疑到专项出题与智能组卷，围绕当前备考方向提供完整辅助。</p>
      </div>
      <div class="ai-module-scope">
        <span><DataAnalysis /></span>
        <div><small>当前学习范围</small><strong>{{ currentScopeLabel }}</strong></div>
      </div>
    </header>

    <nav class="ai-module-tabs" aria-label="AI辅助功能">
      <button
        v-for="item in tabs"
        :key="item.value"
        type="button"
        :class="{ 'is-active': activeTab === item.value }"
        @click="selectTab(item.value)"
      >
        <span><component :is="item.icon" /></span>
        <div><strong>{{ item.label }}</strong><small>{{ item.description }}</small></div>
      </button>
    </nav>

    <section v-if="activeTab === 'assistant'" class="assistant-workspace">
      <section class="assistant-page">
        <header class="assistant-header">
          <div class="assistant-title"><span class="assistant-mark"><MagicStick /></span><div><h2>智能答疑</h2><p>文字问答、题目解析与知识点讲解</p></div></div>
          <span class="assistant-online"><i></i>AI 助教在线</span>
        </header>
        <main class="assistant-thread" aria-live="polite">
          <div v-if="!messages.length" class="assistant-empty">
            <span class="empty-mark"><MagicStick /></span>
            <strong>今天想解决什么问题？</strong>
            <p>输入问题，或拖入、粘贴题目图片</p>
            <div><span>概念对比</span><span>题目详解</span><span>计算题分步讲解</span></div>
          </div>
          <article v-for="message in messages" :key="message.id" class="assistant-message" :class="`assistant-message--${message.role.toLowerCase()}`">
            <strong>{{ message.role === 'USER' ? '我' : 'AI 助教' }}</strong>
            <img v-if="message.imageUrl" class="message-image" :src="message.imageUrl" alt="题目图片" />
            <p>{{ message.content }}</p>
            <div v-if="message.role === 'ASSISTANT'" class="assistant-feedback">
              <button type="button" @click="feedback(message, 'HELPFUL')">有帮助</button>
              <button type="button" @click="feedback(message, 'NOT_HELPFUL')">需改进</button>
              <button type="button" @click="feedback(message, 'REPORT')">报告问题</button>
            </div>
          </article>
          <div v-if="loading" class="assistant-thinking"><Loading class="spin" /> 正在识别题目并组织解析...</div>
        </main>
        <section
          class="assistant-compose"
          :class="{ 'is-dragging': isDragging }"
          @dragover.prevent="isDragging = true"
          @dragleave.prevent="isDragging = false"
          @drop.prevent="handleDrop"
        >
          <div v-if="attachment && previewUrl" class="attachment-preview">
            <img :src="previewUrl" alt="待解答题目" />
            <div><strong>题目图片</strong><small>发送后将自动识别题型并生成解析</small></div>
            <button class="remove-button" type="button" aria-label="移除题目图片" title="移除图片" @click="clearAttachment"><Close /></button>
          </div>
          <textarea v-model="draft" rows="3" placeholder="输入问题，或直接粘贴题目图片" @paste="handlePaste" @keydown.enter.exact.prevent="send" />
          <div class="composer-actions">
            <input ref="fileInput" class="visually-hidden" type="file" accept="image/png,image/jpeg,image/gif,image/webp" @change="selectFile(($event.target as HTMLInputElement).files?.[0] ?? null)" />
            <button class="icon-button" type="button" aria-label="添加题目图片" title="添加题目图片" @click="fileInput?.click()"><FolderOpened /></button>
            <small>Enter 发送</small>
            <button class="send-button" type="button" :disabled="loading || (!draft.trim() && !attachment)" @click="send"><Loading v-if="loading" class="spin" /><Promotion v-else /><span>{{ loading ? '分析中' : '发送' }}</span></button>
          </div>
        </section>
      </section>
    </section>

    <section v-else class="planner-embedded">
      <AiPracticePlannerView embedded :section="activeTab === 'question' ? 'question' : 'paper'" />
    </section>
  </main>
</template>

<style scoped>
.assistant-page { width: min(960px, calc(100% - 32px)); min-height: calc(100vh - 48px); margin: 24px auto; padding: 24px; border: 1px solid rgba(148, 163, 184, .3); border-radius: 20px; background: #f6f8fa; box-shadow: 0 18px 50px rgba(15, 23, 42, .08); }.assistant-header { display: flex; align-items: center; padding-bottom: 20px; border-bottom: 1px solid #e2e8f0; }.assistant-title { display: flex; align-items: center; gap: 12px; }.assistant-title > div { display: grid; gap: 3px; }.assistant-title h1 { margin: 0; color: #172033; font-size: 24px; }.assistant-title p,.assistant-empty p { margin: 0; color: #64748b; font-size: 13px; }.assistant-mark,.empty-mark { display: grid; place-items: center; width: 40px; height: 40px; border-radius: 12px; background: #e7f2ff; color: #2563eb; }.assistant-mark :deep(svg),.empty-mark :deep(svg) { width: 19px; }.assistant-thread { min-height: 440px; padding: 24px 6px; }.assistant-empty { display: grid; place-items: center; align-content: center; min-height: 420px; color: #172033; text-align: center; }.empty-mark { width: 46px; height: 46px; margin-bottom: 14px; border-radius: 14px; }.assistant-empty strong { font-size: 17px; }.assistant-message { max-width: 82%; padding: 14px 16px; margin: 12px 0; border: 1px solid #e2e8f0; border-radius: 14px 14px 14px 5px; background: #fff; box-shadow: 0 4px 13px rgba(15, 23, 42, .04); }.assistant-message--user { margin-left: auto; border-color: #c9ddf5; border-radius: 14px 14px 5px 14px; background: #eaf3ff; }.assistant-message strong { color: #64748b; font-size: 12px; }.assistant-message p { margin: 8px 0 0; color: #172033; white-space: pre-wrap; line-height: 1.65; }.message-image { display: block; max-width: min(360px, 100%); max-height: 240px; margin-top: 10px; border-radius: 9px; object-fit: contain; }.assistant-feedback { display: flex; gap: 8px; margin-top: 12px; }.assistant-feedback button { padding: 5px 8px; border: 0; border-radius: 7px; background: #f1f5f9; color: #64748b; cursor: pointer; }.assistant-thinking { display: flex; align-items: center; gap: 7px; color: #64748b; font-size: 13px; }.assistant-thinking :deep(svg) { width: 16px; }.spin { animation: spin 1s linear infinite; }@keyframes spin { to { transform: rotate(360deg); } }.assistant-compose { display: grid; gap: 10px; padding: 12px; border: 1px solid #dbe4ee; border-radius: 16px; background: #fff; box-shadow: 0 8px 24px rgba(15, 23, 42, .07); transition: border-color .18s, background .18s; }.assistant-compose.is-dragging { border-color: #2563eb; background: #f0f7ff; }.assistant-compose textarea { width: 100%; min-height: 76px; padding: 8px 4px; border: 0; outline: 0; background: transparent; color: #172033; font: inherit; line-height: 1.6; resize: vertical; }.composer-actions { display: flex; align-items: center; justify-content: space-between; }.icon-button,.remove-button { display: grid; place-items: center; width: 34px; height: 34px; padding: 0; border: 0; border-radius: 10px; background: transparent; color: #64748b; cursor: pointer; }.icon-button:hover,.remove-button:hover { background: #eef2f6; color: #172033; }.icon-button :deep(svg),.remove-button :deep(svg) { width: 18px; }.send-button { display: inline-flex; align-items: center; gap: 6px; height: 36px; padding: 0 14px; border: 0; border-radius: 10px; background: #172033; color: #fff; cursor: pointer; }.send-button :deep(svg) { width: 16px; }.send-button:disabled { cursor: not-allowed; opacity: .4; }.attachment-preview { display: flex; align-items: center; gap: 10px; padding: 7px; border-radius: 10px; background: #f6f8fa; }.attachment-preview img { width: 52px; height: 52px; border-radius: 7px; object-fit: cover; }.attachment-preview div { display: grid; gap: 4px; min-width: 0; }.attachment-preview strong { color: #172033; font-size: 13px; }.attachment-preview small { color: #64748b; font-size: 12px; }.remove-button { margin-left: auto; }.visually-hidden { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; clip-path: inset(50%); }
.ocr-review { display: grid; gap: 8px; padding: 10px; border: 1px solid #dbe4ee; border-radius: 10px; background: #f8fafc; }.ocr-review__header { display: flex; justify-content: space-between; color: #475569; font-size: 12px; }.ocr-review__header .warning { color: #c2410c; }.ocr-review textarea { min-height: 92px; padding: 8px; border: 1px solid #dbe4ee; border-radius: 8px; background: #fff; font-size: 13px; }.ocr-review button { justify-self: end; padding: 6px 12px; border: 0; border-radius: 8px; background: #2563eb; color: #fff; cursor: pointer; }.ocr-review button:disabled { opacity: .45; cursor: not-allowed; }
@media (max-width: 640px) { .assistant-page { width: calc(100% - 24px); min-height: calc(100vh - 24px); margin: 12px auto; padding: 16px; border-radius: 16px; }.assistant-thread { padding: 18px 0; }.assistant-message { max-width: 100%; } }

.ai-module-page {
  width: min(1180px, calc(100% - 40px));
  margin: 0 auto;
  padding: 26px 0 52px;
  color: #172033;
}

.ai-module-heading {
  display: flex;
  min-height: 84px;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
}

.ai-module-heading__eyebrow {
  color: #2563eb;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: .06em;
}

.ai-module-heading h1 {
  margin: 5px 0 0;
  color: #172033;
  font-size: 28px;
  letter-spacing: -.025em;
}

.ai-module-heading p {
  margin: 5px 0 0;
  color: #6b788b;
  font-size: 13px;
}

.ai-module-scope {
  display: flex;
  width: min(360px, 42%);
  min-height: 62px;
  padding: 10px 14px;
  align-items: center;
  gap: 11px;
  border: 1px solid #dbe5f2;
  border-radius: 13px;
  background: #fff;
}

.ai-module-scope > span {
  display: grid;
  width: 38px;
  height: 38px;
  flex: 0 0 38px;
  place-items: center;
  border-radius: 10px;
  color: #fff;
  background: #2563eb;
  box-shadow: 0 6px 14px rgba(37, 99, 235, .18);
}

.ai-module-scope > span :deep(svg) {
  width: 18px;
}

.ai-module-scope > div {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.ai-module-scope small {
  color: #7b8798;
  font-size: 10px;
}

.ai-module-scope strong {
  overflow: hidden;
  color: #273244;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-module-tabs {
  display: grid;
  margin-top: 16px;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.ai-module-tabs > button {
  position: relative;
  display: grid;
  min-height: 74px;
  padding: 12px 14px;
  grid-template-columns: 40px minmax(0, 1fr);
  align-items: center;
  gap: 11px;
  border: 1px solid #dce4ee;
  border-radius: 13px;
  color: #526078;
  background: #fff;
  cursor: pointer;
  text-align: left;
  box-shadow: 0 4px 16px rgba(15, 23, 42, .03);
  transition: border-color .16s ease, box-shadow .16s ease, transform .16s ease;
}

.ai-module-tabs > button:hover {
  border-color: #b3c8f1;
  box-shadow: 0 7px 20px rgba(37, 99, 235, .07);
  transform: translateY(-1px);
}

.ai-module-tabs > button.is-active {
  border-color: #2563eb;
  color: #1e293b;
  background: #f7faff;
  box-shadow: 0 8px 22px rgba(37, 99, 235, .1);
}

.ai-module-tabs > button.is-active::after {
  position: absolute;
  right: 16px;
  bottom: -1px;
  left: 16px;
  height: 3px;
  border-radius: 3px 3px 0 0;
  background: #2563eb;
  content: '';
}

.ai-module-tabs > button > span {
  display: grid;
  width: 40px;
  height: 40px;
  place-items: center;
  border-radius: 11px;
  color: #2563eb;
  background: #edf4ff;
}

.ai-module-tabs > button > span :deep(svg) {
  width: 18px;
}

.ai-module-tabs > button.is-active > span {
  color: #fff;
  background: #2563eb;
}

.ai-module-tabs > button > div {
  display: grid;
  min-width: 0;
  gap: 4px;
}

.ai-module-tabs strong {
  font-size: 14px;
}

.ai-module-tabs small {
  overflow: hidden;
  color: #8491a3;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.assistant-workspace {
  min-height: 650px;
  margin-top: 14px;
}

.assistant-page {
  display: grid;
  width: 100%;
  min-height: 650px;
  margin: 0;
  padding: 0;
  grid-template-rows: auto minmax(390px, 1fr) auto;
  overflow: hidden;
  border: 1px solid #dce4ee;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 10px 30px rgba(15, 23, 42, .045);
}

.assistant-header {
  min-height: 66px;
  padding: 12px 18px;
  justify-content: space-between;
  border-bottom: 1px solid #e7edf4;
}

.assistant-title h2 {
  margin: 0;
  color: #172033;
  font-size: 16px;
}

.assistant-title p {
  font-size: 11px;
}

.assistant-online {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #15803d;
  font-size: 10px;
}

.assistant-online i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #22c55e;
  box-shadow: 0 0 0 3px rgba(34, 197, 94, .12);
}

.assistant-thread {
  min-height: 390px;
  max-height: 560px;
  padding: 22px 20px;
  overflow-y: auto;
  background: #f8fafc;
}

.assistant-empty {
  min-height: 380px;
}

.assistant-empty > div {
  display: flex;
  margin-top: 15px;
  flex-wrap: wrap;
  justify-content: center;
  gap: 6px;
}

.assistant-empty > div span {
  padding: 5px 8px;
  border-radius: 999px;
  color: #64748b;
  background: #eef2f7;
  font-size: 10px;
}

.assistant-message {
  border-radius: 14px 14px 14px 5px;
}

.assistant-message--user {
  border-radius: 14px 14px 5px 14px;
}

.assistant-compose {
  margin: 12px 16px 16px;
  border-radius: 14px;
  box-shadow: 0 6px 20px rgba(15, 23, 42, .06);
}

.composer-actions {
  gap: 8px;
}

.composer-actions > small {
  margin-right: auto;
  color: #94a3b8;
  font-size: 10px;
}

.planner-embedded {
  margin-top: 14px;
}

@media (max-width: 760px) {
  .ai-module-page {
    width: calc(100% - 24px);
    padding: 16px 0 36px;
  }

  .ai-module-heading {
    align-items: stretch;
    flex-direction: column;
    gap: 13px;
  }

  .ai-module-scope {
    width: 100%;
  }

  .ai-module-tabs {
    grid-template-columns: 1fr;
    gap: 8px;
  }

  .ai-module-tabs > button {
    min-height: 64px;
  }

  .assistant-page {
    min-height: calc(100vh - 120px);
    margin: 0;
    padding: 0;
  }

  .assistant-thread {
    padding: 18px 12px;
  }

  .assistant-message {
    max-width: 100%;
  }

  .assistant-compose {
    margin: 10px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .ai-module-tabs > button {
    transition: none;
  }
}
</style>
