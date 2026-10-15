<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, Check, Clock, Close, Document, Link, List, MagicStick, MoreFilled, Plus, Promotion, Refresh, RefreshLeft, RefreshRight, Warning } from '@element-plus/icons-vue'
import { ApiClientError } from '@/api/client'
import { getEssayGateway } from '@/modules/essay/api/gateway'
import type { EssayGateway } from '@/modules/essay/api/essay.gateway'
import {
  EssayAutosaveController,
  readEssayLocalSnapshot,
  type EssayAutosaveState,
} from '@/modules/essay/autosave-controller'
import { applyParagraphSuggestion, errorPresentation } from '@/modules/essay/essay-state'
import type { EssayEditorData, EssaySuggestion } from '@/modules/essay/types'

defineOptions({ name: 'EssayEditorSlotView' })
const router = useRouter()
const editor = ref<EssayEditorData | null>(null)
const activeParagraphId = ref('')
const autosaveState = ref<EssayAutosaveState | null>(null)
const loadError = ref<string | null>(null)
const question = ref('')
const answer = ref('')
const localRecovery = ref(false)
const reviewDialog = ref(false)
let saveTimer: number | undefined
let gateway: EssayGateway | null = null
let autosave: EssayAutosaveController | null = null

const saveState = computed(() => autosaveState.value?.status ?? 'saved')
const conflict = computed(() => autosaveState.value?.conflict)
const canRetryConflict = computed(() => autosave?.canRetryConflict ?? false)
const activeParagraph = computed(() => editor.value?.paragraphs.find((item) => item.id === activeParagraphId.value) ?? editor.value?.paragraphs[2])
const suggestions = computed(() => editor.value?.suggestions.filter((item) => item.paragraphId === activeParagraph.value?.id) ?? [])
const wordCount = computed(() => editor.value?.paragraphs.reduce((sum, item) => sum + item.text.replace(/\s/g, '').length, 0) ?? 0)
const saveLabel = computed(() => ({
  saved: '自动保存成功', dirty: '修改已在本地保护', saving: '正在自动保存', failed: '保存失败，已保护本地内容',
  conflict: '版本冲突，等待比较', unconfirmed: '已上传，等待版本确认；本地副本保留',
})[saveState.value])

async function load(): Promise<void> {
  try {
    gateway = await getEssayGateway()
    const next = await gateway.loadEditor()
    const storageKey = `essay-local-${next.id}`
    const protectedCopy = readEssayLocalSnapshot(sessionStorage, storageKey)
    autosave = new EssayAutosaveController({
      storage: sessionStorage,
      storageKey,
      initialDraft: next,
      restoredSnapshot: protectedCopy,
      supportsConflictRetry: gateway.capabilities.conflictRetry,
      save: (request) => gateway!.saveDraft(request.draft, request.baseVersion, request.localRevision),
      onChange: (state) => {
        autosaveState.value = state
        if (state.status === 'saved' && editor.value) editor.value.version = state.baseVersion
      },
    })
    autosaveState.value = structuredClone(autosave.state)
    editor.value = structuredClone(autosave.state.draft)
    localRecovery.value = Boolean(protectedCopy)
    activeParagraphId.value = editor.value?.paragraphs[2]?.id ?? editor.value?.paragraphs[0]?.id ?? ''
  } catch (reason) {
    loadError.value = errorPresentation(reason instanceof ApiClientError ? reason.status : (reason as { status?: number }).status).message
  }
}

function queueSave(): void {
  if (!editor.value || !autosave) return
  autosave.capture(editor.value)
  window.clearTimeout(saveTimer)
  saveTimer = window.setTimeout(() => autosave?.flush(), 650)
}
function updateParagraph(event: Event, paragraphId: string): void {
  const paragraph = editor.value?.paragraphs.find((item) => item.id === paragraphId)
  const target = event.target as HTMLElement
  if (paragraph) { paragraph.text = target.innerText ?? target.textContent ?? ''; activeParagraphId.value = paragraphId; queueSave() }
}
function selectParagraph(paragraphId: string): void {
  autosave?.flush()
  activeParagraphId.value = paragraphId
}
function applySuggestion(suggestion: EssaySuggestion, mode: 'replace' | 'insert-after'): void {
  if (!editor.value) return
  editor.value = { ...editor.value, ...applyParagraphSuggestion(editor.value, suggestion.paragraphId, suggestion.content, mode) }
  queueSave()
}
function keepReference(suggestion: EssaySuggestion): void { answer.value = `已保留“${suggestion.category}”建议，正文未发生变更。` }
function ask(): void {
  if (!question.value.trim() || !activeParagraph.value) return
  answer.value = `已绑定 ${activeParagraph.value.id} · v${editor.value?.version}。追问接口尚未联调，本次未发送模型请求。`
  question.value = ''
}
function useServerSnapshot(): void {
  if (!autosave?.useServerSnapshot()) return
  editor.value = structuredClone(autosave.state.draft)
}
function keepLocalSnapshot(): void {
  autosave?.keepLocalForComparison()
}
function retryLocalSnapshot(): void {
  autosave?.retryLocalAgainstServer()
}
function retryAutosave(): void {
  autosave?.retry()
}
onMounted(load)
onBeforeUnmount(() => {
  window.clearTimeout(saveTimer)
  autosave?.flush()
})
</script>

<template>
  <main class="essay-editor" data-testid="essay-editor-slot" data-route-identity="essay.editor">
    <header class="editor-topbar">
      <button type="button" @click="router.push('/app/essay/home')"><el-icon><ArrowLeft /></el-icon>返回</button>
      <p v-if="editor"><span>论文标题：</span>{{ editor.title }}</p>
      <div v-if="editor" class="editor-selects"><span>资格：<b>{{ editor.qualification }}</b></span><span>主题：<b>{{ editor.topic }}</b></span></div>
    </header>
    <section v-if="editor" class="editor-meta">
      <span>字数：<b>{{ wordCount.toLocaleString() }}</b> / 5,000</span><span><el-icon><Clock /></el-icon>写作时长：02:14:37</span>
      <span data-testid="essay-save-state" :class="`save-${saveState}`"><el-icon><Check v-if="saveState==='saved'"/><Refresh v-else-if="saveState==='saving'"/><Warning v-else/></el-icon>{{ saveLabel }}</span><button v-if="saveState==='failed' || saveState==='unconfirmed'" data-testid="retry-autosave" type="button" @click="retryAutosave">重试保存</button>
      <span>版本：v{{ editor.version }}</span><button class="submit-review" data-testid="submit-review" type="button" @click="reviewDialog=true">提交批改</button>
    </section>
    <div v-if="localRecovery" class="recovery-banner">已恢复上次未成功上传的本地正文，请确认后重新保存。</div>
    <div v-if="loadError" class="editor-load-error">{{ loadError }}。当前页面不会展示伪造正文。</div>

    <section v-if="editor" class="essay-editor__grid" data-testid="essay-editor-grid">
      <aside class="editor-left">
        <section class="outline-panel"><header><h2>论文大纲</h2><el-icon><List /></el-icon></header><button v-for="item in editor.outline" :key="item.id" :class="{ 'is-child': item.level, 'is-active': item.id === activeParagraph?.outlineId }"><span>{{ item.label }}</span><small>{{ item.words }}字</small></button></section>
        <section class="background-panel"><header><h2>项目背景 <small>（已绑定）</small></h2><button>查看详情</button></header><p>{{ editor.background }}</p></section>
        <section class="materials-panel"><header><h2>已绑定素材</h2><button aria-label="添加素材"><el-icon><Plus /></el-icon></button></header><ul><li v-for="material in editor.materials" :key="material.id"><el-icon><Document /></el-icon><span>{{ material.title }}</span><small>{{ material.type }}</small></li></ul></section>
      </aside>

      <article class="editor-document">
        <div class="editor-toolbar"><button aria-label="撤销"><el-icon><RefreshLeft /></el-icon></button><button aria-label="重做"><el-icon><RefreshRight /></el-icon></button><span>正文</span><span>18</span><button><b>B</b></button><button><i>I</i></button><button><u>U</u></button><button><el-icon><Link /></el-icon></button><button><el-icon><MoreFilled /></el-icon></button></div>
        <div class="document-body">
          <section v-for="paragraph in editor.paragraphs" :key="paragraph.id" :class="{ 'is-selected': paragraph.id === activeParagraph?.id }" :data-paragraph-id="paragraph.id" contenteditable="true" spellcheck="false" @focus="selectParagraph(paragraph.id)" @input="updateParagraph($event, paragraph.id)">{{ paragraph.text }}</section>
        </div>
        <footer><span>当前：第3章 · 3.2 现状调研与问题识别</span><span>拼写检查：关闭 · 字数统计</span></footer>
      </article>

      <aside class="editor-ai">
        <header class="ai-context" data-testid="essay-ai-context"><span>当前绑定：{{ activeParagraph?.outlineId }} · {{ activeParagraph?.id }}</span><el-icon><MagicStick /></el-icon></header>
        <nav><button class="is-active">AI 建议</button><button>问题检查 <b>{{ suggestions.length }}</b></button><button>上下文追问</button></nav>
        <div class="suggestion-list">
          <article v-for="(suggestion,index) in suggestions" :key="suggestion.id" class="suggestion-card"><header><span>建议{{ index+1 }}</span><b>{{ suggestion.category }}</b></header><h3>理由</h3><p>{{ suggestion.reason }}</p><h3>建议内容</h3><blockquote>{{ suggestion.content }}</blockquote><footer><button @click="applySuggestion(suggestion,'replace')">替换当前段落</button><button @click="applySuggestion(suggestion,'insert-after')">插入当前段落后</button><button @click="keepReference(suggestion)">保留参考</button></footer></article>
          <div v-if="!suggestions.length" class="no-suggestion">当前段落暂无建议。AI 建议不会自动写入正文。</div>
        </div>
        <section class="context-question"><header><h2>上下文追问</h2><span>基于当前段落</span></header><div class="quick-questions"><button @click="question='如何补充调研数据？'">如何补充调研数据？</button><button @click="question='怎样增强段落衔接？'">怎样增强段落衔接？</button></div><p v-if="answer">{{ answer }}</p><form @submit.prevent="ask"><input v-model="question" :disabled="!activeParagraph" placeholder="输入针对当前段落的问题"><button aria-label="发送追问"><el-icon><Promotion /></el-icon></button></form></section>
      </aside>
    </section>

    <div v-if="saveState==='conflict'" class="conflict-dialog" role="dialog" aria-label="版本冲突" data-testid="essay-conflict-dialog"><section><h2>检测到版本冲突</h2><p>服务器已有更新版本。系统保留了本地正文，不会静默覆盖任一版本。</p><p v-if="conflict?.serverSnapshot">服务器版本 v{{ conflict.serverSnapshot.baseVersion }} · {{ conflict.serverSnapshot.updatedAt }}</p><p v-else>服务器未返回可比较快照，需重新加载服务器版本后再选择。</p><p v-if="conflict?.choice==='local'">本地副本仍受保护，冲突尚未解决；比较副本已经归档。</p><div><button data-testid="use-server-snapshot" :disabled="!conflict?.serverSnapshot" @click="useServerSnapshot">{{ conflict?.serverSnapshot ? '使用服务器版本' : '需重新加载' }}</button><button data-testid="keep-local-snapshot" @click="keepLocalSnapshot">保留本地副本</button><button v-if="conflict?.choice==='local'" data-testid="retry-local-snapshot" :disabled="!canRetryConflict" @click="retryLocalSnapshot">{{ canRetryConflict ? `以服务器 v${conflict?.serverSnapshot?.baseVersion} 重新提交` : '版本重提接口未联调' }}</button></div></section></div>
    <div v-if="reviewDialog && editor" class="review-boundary-dialog" role="dialog" aria-label="提交批改确认" data-testid="review-submit-boundary">
      <section><header><div><small>AI 批改任务</small><h2>提交前确认</h2></div><button aria-label="关闭" @click="reviewDialog=false"><el-icon><Close /></el-icon></button></header>
        <dl><div><dt>草稿</dt><dd>{{ editor.title }}</dd></div><div><dt>资格</dt><dd>{{ editor.qualification }}</dd></div><div><dt>评分规则</dt><dd>等待后端返回可用规则版本</dd></div><div><dt>内容快照</dt><dd>v{{ editor.version }} · {{ wordCount.toLocaleString() }} 字</dd></div></dl>
        <p><el-icon><Warning /></el-icon><span><b>批改接口尚未联调。</b>当前不会创建任务、伪造评分或调用模型 SDK。开发预览不产生费用；生产环境的权益与费用确认由后端能力开放后执行。</span></p>
        <footer><button @click="reviewDialog=false">返回编辑</button><button disabled>接口建设中</button></footer>
      </section>
    </div>
  </main>
</template>

<style scoped>
.essay-editor{height:calc(100vh - var(--platform-header-height));min-height:648px;overflow:hidden;background:#f6f8fb;color:var(--color-text-primary)}button{font:inherit}.editor-topbar{display:flex;height:52px;padding:0 20px;align-items:center;gap:18px;border-bottom:1px solid var(--color-border);background:#fff}.editor-topbar>button{display:flex;height:34px;padding:0 12px;align-items:center;gap:6px;border:1px solid var(--color-border);border-radius:5px;background:#fff}.editor-topbar p{min-width:0;margin:0;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:13px}.editor-topbar p span{color:var(--color-text-muted)}.editor-selects{display:flex;gap:10px;color:var(--color-text-secondary);font-size:12px}.editor-selects span{padding:8px 10px;border:1px solid var(--color-border);border-radius:5px}.editor-meta{display:flex;height:48px;padding:0 22px 0 280px;align-items:center;gap:28px;border-bottom:1px solid var(--color-border);background:#fff;color:var(--color-text-secondary);font-size:12px}.editor-meta span{display:flex;align-items:center;gap:6px}.editor-meta b,.save-saved{color:var(--color-success)}.save-failed,.save-conflict{color:var(--color-danger)}.submit-review{height:34px;margin-left:auto;padding:0 22px;border:0;border-radius:5px;background:var(--color-cobalt);color:#fff;font-weight:700}.recovery-banner,.editor-load-error{padding:7px 16px;text-align:center;font-size:12px}.recovery-banner{background:#fff6df;color:#8a5600}.editor-load-error{background:#fff1f1;color:var(--color-danger)}
.essay-editor__grid{display:grid;height:calc(100% - 100px);padding:10px;grid-template-columns:286px minmax(430px,1fr) 350px;gap:10px}.editor-left,.editor-document,.editor-ai{min-width:0;overflow:hidden;border:1px solid var(--color-border);border-radius:7px;background:#fff}.editor-left{overflow-y:auto}.editor-left section+section{border-top:1px solid var(--color-border)}.editor-left header{display:flex;height:44px;padding:0 14px;align-items:center;justify-content:space-between}.editor-left h2{margin:0;font-size:14px}.editor-left h2 small{color:var(--color-text-muted);font-size:11px}.outline-panel>button{display:flex;width:100%;height:31px;padding:0 14px;align-items:center;justify-content:space-between;border:0;background:#fff;color:var(--color-text-secondary);font-size:11px;text-align:left}.outline-panel>button.is-child{padding-left:29px}.outline-panel>button.is-active{background:#edf3ff;color:var(--color-cobalt)}.outline-panel>button span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.outline-panel>button small{color:var(--color-text-muted)}.background-panel header button,.materials-panel header button{border:0;background:transparent;color:var(--color-cobalt);font-size:11px}.background-panel p{margin:0;padding:0 14px 14px;color:var(--color-text-secondary);font-size:11px;line-height:1.6}.materials-panel ul{margin:0;padding:0 12px 12px;list-style:none}.materials-panel li{display:grid;height:35px;grid-template-columns:20px minmax(0,1fr) auto;align-items:center;gap:5px;font-size:10px}.materials-panel li span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.materials-panel li small{padding:3px 5px;border-radius:4px;background:#edf8f7;color:var(--color-success)}
.editor-document{display:grid;grid-template-rows:44px minmax(0,1fr) 36px}.editor-toolbar{display:flex;padding:0 12px;align-items:center;gap:4px;border-bottom:1px solid var(--color-border)}.editor-toolbar button,.editor-toolbar span{min-width:30px;padding:6px;border:0;border-radius:4px;background:#fff;text-align:center}.editor-toolbar button:hover{background:var(--color-page-canvas)}.document-body{overflow-y:auto;padding:28px 34px;font-family:"Microsoft YaHei",sans-serif}.document-body section{padding:5px 8px;white-space:pre-line;font-size:14px;line-height:2;outline:0}.document-body section:first-child{font-size:20px;font-weight:700}.document-body section.is-selected{background:#eef5ff;box-shadow:inset 3px 0 var(--color-cobalt)}.editor-document>footer{display:flex;padding:0 14px;align-items:center;justify-content:space-between;border-top:1px solid var(--color-border);color:var(--color-text-muted);font-size:10px}
.editor-ai{display:grid;grid-template-rows:38px 42px minmax(0,1fr) auto}.ai-context{display:flex;padding:0 14px;align-items:center;justify-content:space-between;background:var(--color-success);color:#fff;font-size:12px;font-weight:700}.editor-ai>nav{display:grid;grid-template-columns:repeat(3,1fr);border-bottom:1px solid var(--color-border)}.editor-ai>nav button{border:0;background:#fff;color:var(--color-text-secondary);font-size:11px}.editor-ai>nav button.is-active{border-bottom:2px solid var(--color-cobalt);color:var(--color-cobalt);font-weight:700}.editor-ai>nav b{display:inline-grid;width:16px;height:16px;place-items:center;border-radius:50%;background:var(--color-coral);color:#fff}.suggestion-list{overflow-y:auto;padding:9px}.suggestion-card{padding:10px;border:1px solid var(--color-border);border-radius:6px}.suggestion-card+.suggestion-card{margin-top:8px}.suggestion-card header{display:flex;gap:8px;font-size:11px}.suggestion-card header span{font-weight:700}.suggestion-card header b{padding:2px 6px;border-radius:4px;background:#e8f8f6;color:var(--color-success)}.suggestion-card h3{margin:9px 0 3px;font-size:10px}.suggestion-card p{margin:0;color:var(--color-text-secondary);font-size:10px;line-height:1.5}.suggestion-card blockquote{margin:0;padding:7px;border:1px solid #b9dfdc;border-radius:4px;background:#f1fbfa;color:#27625d;font-size:10px;line-height:1.5}.suggestion-card footer{display:flex;margin-top:8px;gap:5px}.suggestion-card footer button{height:26px;padding:0 7px;border:1px solid var(--color-border);border-radius:4px;background:#fff;color:var(--color-text-secondary);font-size:9px}.suggestion-card footer button:first-child{border-color:var(--color-cobalt);background:var(--color-cobalt);color:#fff}.no-suggestion{padding:28px 12px;color:var(--color-text-muted);font-size:11px;text-align:center}.context-question{padding:9px 10px;border-top:1px solid var(--color-border)}.context-question header{display:flex;align-items:center;justify-content:space-between}.context-question h2{margin:0;font-size:12px}.context-question header span{color:var(--color-text-muted);font-size:9px}.quick-questions{display:flex;margin:7px 0;gap:5px}.quick-questions button{padding:5px 7px;border:0;border-radius:4px;background:#edf3ff;color:var(--color-text-secondary);font-size:9px}.context-question p{margin:5px 0;padding:6px;border-radius:4px;background:#f7f9fc;color:var(--color-text-secondary);font-size:9px}.context-question form{display:flex;border:1px solid var(--color-border);border-radius:5px}.context-question input{min-width:0;height:30px;padding:0 8px;flex:1;border:0;outline:0;font-size:10px}.context-question form button{width:30px;border:0;background:transparent;color:var(--color-cobalt)}
.conflict-dialog{position:fixed;z-index:50;inset:0;display:grid;place-items:center;background:rgb(15 31 55 / 42%)}.conflict-dialog section{width:420px;padding:22px;border-radius:8px;background:#fff}.conflict-dialog h2{margin:0 0 10px}.conflict-dialog p{color:var(--color-text-secondary);font-size:13px}.conflict-dialog div{display:flex;justify-content:flex-end;gap:8px}.conflict-dialog button{padding:8px 12px;border:1px solid var(--color-border);border-radius:5px;background:#fff}.conflict-dialog button:last-child{border-color:var(--color-cobalt);background:var(--color-cobalt);color:#fff}
.review-boundary-dialog{position:fixed;z-index:50;inset:0;display:grid;place-items:center;background:rgb(15 31 55 / 42%)}.review-boundary-dialog>section{width:520px;padding:22px;border-radius:8px;background:#fff}.review-boundary-dialog header{display:flex;align-items:start;justify-content:space-between}.review-boundary-dialog header small{color:var(--color-success);font-weight:700}.review-boundary-dialog h2{margin:3px 0 0}.review-boundary-dialog header>button{border:0;background:transparent;color:var(--color-text-muted);font-size:24px}.review-boundary-dialog dl{display:grid;margin:18px 0;grid-template-columns:1fr 1fr;gap:10px}.review-boundary-dialog dl div{padding:10px;border:1px solid var(--color-border);border-radius:5px}.review-boundary-dialog dt{color:var(--color-text-muted);font-size:10px}.review-boundary-dialog dd{margin:4px 0 0;font-size:12px;font-weight:600}.review-boundary-dialog>section>p{display:flex;padding:11px;gap:9px;border:1px solid #f2d49b;border-radius:5px;background:#fff9eb;color:#74501b;font-size:11px;line-height:1.55}.review-boundary-dialog footer{display:flex;justify-content:flex-end;gap:8px}.review-boundary-dialog footer button{height:34px;padding:0 15px;border:1px solid var(--color-border);border-radius:5px;background:#fff}.review-boundary-dialog footer button:disabled{border-color:var(--color-border);background:#edf1f6;color:var(--color-text-muted)}
@media(max-width:1366px){.essay-editor__grid{grid-template-columns:250px minmax(400px,1fr) 310px}.editor-meta{padding-left:240px;gap:20px}.document-body{padding:22px 24px}.editor-topbar{padding-inline:14px}}
@media(max-width:1280px){.essay-editor__grid{grid-template-columns:226px minmax(390px,1fr) 278px;gap:7px;padding:7px}.editor-meta{padding-left:205px;gap:13px}.editor-selects span{padding-inline:7px}.document-body{padding:18px}.suggestion-card footer{flex-wrap:wrap}.suggestion-card footer button{flex:1}.materials-panel li{height:31px}}
</style>
