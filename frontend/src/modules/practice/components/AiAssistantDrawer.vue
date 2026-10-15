<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Close, FolderOpened, Loading, MagicStick, Promotion } from '@element-plus/icons-vue'
import { platformApiClient, toSafeUserMessage, unwrapApiResponse } from '@/api/client'

type Citation = { fragmentId?: string; documentTitle: string; excerpt: string }
type Message = { id: string; role: string; content: string; answerStatus?: string; citations?: Citation[]; createdAt: string; imageUrl?: string }
type Session = { id: string }
type Attachment = { id: string; ocrStatus: string; ocrText?: string; ocrConfidence?: number }

const open = defineModel<boolean>({ default: false })
const session = ref<Session | null>(null)
const messages = ref<Message[]>([])
const draft = ref('')
const loading = ref(false)
const uploadFile = ref<File | null>(null)
const attachment = ref<Attachment | null>(null)
const ocrDraft = ref('')
const confirmingOcr = ref(false)
const previewUrl = ref('')
const fileInput = ref<HTMLInputElement | null>(null)
const isDragging = ref(false)

async function ensureSession() {
  if (!session.value) {
    await restoreCurrentSession()
  }
}

async function restoreCurrentSession() {
  session.value = await unwrapApiResponse<Session>(platformApiClient.post('/ai-assistant/current-session'))
  const detail = await unwrapApiResponse<{ session: Session; messages: Message[] }>(platformApiClient.get(`/ai-assistant/sessions/${session.value.id}`))
  session.value = detail.session
  messages.value = detail.messages
}

async function load() { await restoreCurrentSession() }

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
    const body = new FormData()
    body.append('file', file)
    attachment.value = await unwrapApiResponse<Attachment>(platformApiClient.post(`/ai-assistant/sessions/${session.value.id}/attachments`, body))
    ocrDraft.value = attachment.value.ocrText ?? ''
  } catch (error) {
    clearAttachment()
    ElMessage.error(toSafeUserMessage(error))
  }
}

async function selectFile(file: File | null) {
  if (!file || !file.type.startsWith('image/')) return
  setPreview(file)
  await ensureSession()
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
  if (!draft.value.trim() && !attachment.value) return
  const text = draft.value.trim() || '请帮我解答这道题。'
  const image = previewUrl.value
  loading.value = true
  try {
    await ensureSession()
    if (attachment.value && !(await confirmOcr(false))) return
    const answer = await unwrapApiResponse<Message>(platformApiClient.post(`/ai-assistant/sessions/${session.value!.id}/messages`, { content: text, attachmentId: attachment.value?.id }))
    messages.value.push({ id: `local-${Date.now()}`, role: 'USER', content: text, createdAt: new Date().toISOString(), imageUrl: image }, answer)
    draft.value = ''
    clearAttachment()
  } catch (error) { ElMessage.error(toSafeUserMessage(error)) } finally { loading.value = false }
}

async function handlePaste(event: ClipboardEvent) {
  const image = Array.from(event.clipboardData?.items ?? []).find(item => item.type.startsWith('image/'))?.getAsFile()
  if (!image) return
  event.preventDefault()
  const pasted = new File([image], `pasted-question-${Date.now()}.png`, { type: image.type || 'image/png' })
  await selectFile(pasted)
}

async function handleDrop(event: DragEvent) {
  isDragging.value = false
  const file = Array.from(event.dataTransfer?.files ?? []).find(item => item.type.startsWith('image/'))
  await selectFile(file ?? null)
}

function handleOpen(event: Event): void {
  const wasOpen = open.value
  void event
  open.value = true
  if (wasOpen) void load()
}

onMounted(() => window.addEventListener('ai-assistant:open', handleOpen))
onUnmounted(() => {
  window.removeEventListener('ai-assistant:open', handleOpen)
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
})
watch(open, (value) => { if (value) void load() })
</script>

<template>
  <button v-if="!open" class="ai-fab" type="button" aria-label="打开 AI 答疑" @click="open = true"><MagicStick /><span>AI</span></button>
  <div v-else class="ai-layer" @click.self="open = false">
    <aside class="ai-drawer" aria-label="AI 答疑面板">
      <header class="ai-header">
        <div class="ai-brand"><span class="ai-brand-mark"><MagicStick /></span><div><strong>AI 答疑</strong><small>智能解析题目与知识点</small></div></div>
        <button class="icon-button" type="button" aria-label="关闭 AI 答疑" title="关闭" @click="open = false"><Close /></button>
      </header>
      <main class="ai-messages" aria-live="polite">
        <div v-if="!messages.length" class="ai-empty"><span class="empty-mark"><MagicStick /></span><strong>开始 AI 答题</strong><p>粘贴题目图片，或输入你的问题</p></div>
        <article v-for="message in messages" :key="message.id" :class="`ai-message ai-message--${message.role.toLowerCase()}`">
          <b>{{ message.role === 'USER' ? '我' : 'AI 助教' }}</b>
          <img v-if="message.imageUrl" class="message-image" :src="message.imageUrl" alt="题目图片" />
          <p>{{ message.content }}</p>
        </article>
        <div v-if="loading" class="ai-thinking"><Loading class="spin" /> 正在分析题目...</div>
      </main>
      <footer class="ai-composer" :class="{ 'is-dragging': isDragging }" @dragover.prevent="isDragging = true" @dragleave.prevent="isDragging = false" @drop.prevent="handleDrop">
        <div v-if="attachment && previewUrl" class="attachment-preview"><img :src="previewUrl" alt="待解答题目" /><div><strong>题目图片</strong><small>发送后将自动识别题型并生成解析</small></div><button class="remove-button" type="button" aria-label="移除题目图片" title="移除图片" @click="clearAttachment"><Close /></button></div>
        <textarea v-model="draft" rows="3" placeholder="输入问题，或直接粘贴题目图片" @paste="handlePaste" @keydown.enter.exact.prevent="send" />
        <div class="composer-actions">
          <input ref="fileInput" class="visually-hidden" type="file" accept="image/png,image/jpeg,image/gif,image/webp" @change="selectFile(($event.target as HTMLInputElement).files?.[0] ?? null)" />
          <button class="icon-button composer-tool" type="button" aria-label="添加题目图片" title="添加题目图片" @click="fileInput?.click()"><FolderOpened /></button>
          <button class="send-button" type="button" :disabled="loading || (!draft.trim() && !attachment)" @click="send"><Loading v-if="loading" class="spin" /><Promotion v-else /> <span>{{ loading ? '分析中' : '发送' }}</span></button>
        </div>
      </footer>
    </aside>
  </div>
</template>

<style scoped>
.ai-fab { position: fixed; z-index: 80; right: 24px; bottom: 28px; display: inline-flex; align-items: center; justify-content: center; gap: 6px; width: 58px; height: 58px; border: 0; border-radius: 50%; background: #172033; color: #fff; box-shadow: 0 12px 32px rgba(15, 23, 42, .22); font-weight: 700; cursor: pointer; }
.ai-fab :deep(svg) { width: 17px; }
.ai-layer { position: fixed; z-index: 81; inset: 0; display: grid; place-items: center; padding: 24px; background: rgba(15, 23, 42, .34); backdrop-filter: blur(7px); }
.ai-drawer { display: grid; width: min(680px, 100%); height: min(760px, 100%); grid-template-rows: auto 1fr auto; overflow: hidden; border: 1px solid rgba(148, 163, 184, .3); border-radius: 20px; background: #f6f8fa; box-shadow: 0 28px 80px rgba(15, 23, 42, .2), 0 4px 16px rgba(15, 23, 42, .08); }
.ai-header { display: flex; align-items: center; justify-content: space-between; padding: 18px 22px; border-bottom: 1px solid #e2e8f0; background: rgba(255, 255, 255, .94); }
.ai-brand { display: flex; align-items: center; gap: 11px; }.ai-brand > div { display: grid; gap: 3px; }.ai-brand strong { color: #172033; font-size: 17px; }.ai-brand small { color: #64748b; font-size: 12px; }.ai-brand-mark,.empty-mark { display: grid; place-items: center; width: 34px; height: 34px; border-radius: 11px; background: #e7f2ff; color: #2563eb; }.ai-brand-mark :deep(svg) { width: 17px; }.icon-button,.remove-button { display: grid; place-items: center; width: 34px; height: 34px; padding: 0; border: 0; border-radius: 10px; background: transparent; color: #64748b; cursor: pointer; }.icon-button:hover,.remove-button:hover { background: #eef2f6; color: #172033; }.icon-button :deep(svg),.remove-button :deep(svg) { width: 18px; }
.ai-messages { overflow: auto; padding: 24px; }.ai-empty { display: grid; place-items: center; align-content: center; min-height: 100%; color: #172033; text-align: center; }.empty-mark { width: 44px; height: 44px; margin-bottom: 14px; border-radius: 14px; }.ai-empty p { margin: 7px 0 0; color: #64748b; font-size: 13px; }.ai-message { max-width: 84%; padding: 13px 15px; margin-bottom: 14px; border: 1px solid #e2e8f0; border-radius: 14px 14px 14px 5px; background: #fff; box-shadow: 0 4px 13px rgba(15, 23, 42, .04); }.ai-message--user { margin-left: auto; border-color: #c9ddf5; border-radius: 14px 14px 5px 14px; background: #eaf3ff; }.ai-message b { color: #64748b; font-size: 12px; }.ai-message p { margin: 7px 0 0; color: #172033; white-space: pre-wrap; line-height: 1.65; }.message-image { display: block; max-width: min(280px, 100%); max-height: 200px; margin-top: 9px; border-radius: 9px; object-fit: contain; }.ai-thinking { display: flex; align-items: center; gap: 7px; color: #64748b; font-size: 13px; }.ai-thinking :deep(svg) { width: 16px; }.spin { animation: spin 1s linear infinite; }@keyframes spin { to { transform: rotate(360deg); } }
.ai-composer { display: grid; gap: 10px; margin: 0 16px 16px; padding: 12px; border: 1px solid #dbe4ee; border-radius: 16px; background: #fff; box-shadow: 0 8px 24px rgba(15, 23, 42, .07); transition: border-color .18s, background .18s; }.ai-composer.is-dragging { border-color: #2563eb; background: #f0f7ff; }.ai-composer textarea { width: 100%; min-height: 76px; padding: 8px 4px; border: 0; outline: 0; background: transparent; color: #172033; font: inherit; line-height: 1.6; resize: vertical; }.composer-actions { display: flex; align-items: center; justify-content: space-between; }.composer-tool { color: #64748b; }.send-button { display: inline-flex; align-items: center; gap: 6px; height: 36px; padding: 0 14px; border: 0; border-radius: 10px; background: #172033; color: #fff; cursor: pointer; }.send-button :deep(svg) { width: 16px; }.send-button:disabled { cursor: not-allowed; opacity: .4; }.attachment-preview { display: flex; align-items: center; gap: 10px; padding: 7px; border-radius: 10px; background: #f6f8fa; }.attachment-preview img { width: 52px; height: 52px; border-radius: 7px; object-fit: cover; }.attachment-preview div { display: grid; gap: 4px; min-width: 0; }.attachment-preview strong { color: #172033; font-size: 13px; }.attachment-preview small { color: #64748b; font-size: 12px; }.remove-button { margin-left: auto; }.visually-hidden { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; clip-path: inset(50%); }
.ocr-review { display: grid; gap: 8px; padding: 10px; border: 1px solid #dbe4ee; border-radius: 10px; background: #f8fafc; }.ocr-review__header { display: flex; justify-content: space-between; color: #475569; font-size: 12px; }.ocr-review__header .warning { color: #c2410c; }.ocr-review textarea { min-height: 92px; padding: 8px; border: 1px solid #dbe4ee; border-radius: 8px; background: #fff; font-size: 13px; }.ocr-review button { justify-self: end; padding: 6px 12px; border: 0; border-radius: 8px; background: #2563eb; color: #fff; cursor: pointer; }.ocr-review button:disabled { opacity: .45; cursor: not-allowed; }
@media (max-width: 640px) { .ai-fab { right: 14px; bottom: 18px; }.ai-layer { padding: 0; align-items: end; }.ai-drawer { width: 100%; height: min(760px, 94vh); border-radius: 20px 20px 0 0; }.ai-header { padding: 16px 18px; }.ai-messages { padding: 18px; }.ai-composer { margin: 0 10px 10px; } }
</style>
