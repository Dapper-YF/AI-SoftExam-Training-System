<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { toSafeUserMessage } from '@/api/client'
import { Connection, Flag, UserFilled } from '@element-plus/icons-vue'
import DisabledTools from '../components/DisabledTools.vue'
import QuestionNavigator from '../components/QuestionNavigator.vue'
import { useAuthoritativeClock } from '../composables/use-authoritative-clock'
import { useExamSimulatorStore } from '../stores/exam-simulator'
import { formatDuration } from '../utils/time'

const store = useExamSimulatorStore()
useAuthoritativeClock()

const editorRef = ref<HTMLDivElement>()
const subjectiveWorkspaceRef = ref<HTMLElement>()
const promptFontSize = ref(16)
const lineHeight = ref(1.75)
const alignment = ref<'left' | 'center' | 'right'>('left')
const formulaDialogVisible = ref(false)
const formulaValue = ref('')
const historyVisible = ref(false)
const historyByQuestion = ref<Record<string, string[]>>({})
const editorFullscreen = ref(false)

const selectedAnswer = computed(() => {
  if (!store.currentQuestion) return ''
  return store.answers[String(store.currentQuestion.subQuestionId)] ?? ''
})

const currentMarked = computed(() =>
  store.currentQuestion ? store.markedQuestionIds.has(store.currentQuestion.questionId) : false,
)

const answerWordCount = computed(() => stripHtml(selectedAnswer.value).replace(/\s/g, '').length)
const workspaceType = computed(() => store.clockState?.workspaceType ?? (store.entryCode === 'ESSAY' ? 'ESSAY' : 'SENIOR_UNION'))
const isEssay = computed(() => workspaceType.value === 'ESSAY')
const isCase = computed(() => {
  const subjectCode = store.clockState?.subjectCode
  return !isEssay.value && (
    subjectCode === 'CASE_ANALYSIS'
    || subjectCode === 'APPLICATION_TECH'
    || store.currentQuestion?.questionType !== 'SINGLE_CHOICE'
  )
})
const isSubjective = computed(() => isEssay.value || isCase.value)
const caseObjective = computed(() => isCase.value && store.currentQuestion?.questionType === 'SINGLE_CHOICE')
const currentHistory = computed(() => {
  const questionId = store.currentQuestion?.subQuestionId
  return questionId ? historyByQuestion.value[String(questionId)] ?? [] : []
})

onMounted(async () => {
  document.addEventListener('fullscreenchange', syncFullscreenState)
  if (!store.paper) {
    try {
      await store.enterExam()
    } catch (error) {
      ElMessage.error(toSafeUserMessage(error))
    }
  }
  await nextTick()
  syncEditorContent()
})

onBeforeUnmount(() => {
  document.removeEventListener('fullscreenchange', syncFullscreenState)
})

watch(
  () => [store.currentQuestion?.subQuestionId, selectedAnswer.value],
  async () => {
    await nextTick()
    syncEditorContent()
  },
)

async function answer(value: string | number | boolean | undefined): Promise<void> {
  if (!store.currentQuestion || value === undefined) return
  await store.saveAnswer(store.currentQuestion.subQuestionId, String(value))
}

function previous(): void {
  store.goToQuestion(store.currentQuestionIndex - 1)
}

function next(): void {
  store.goToQuestion(store.currentQuestionIndex + 1)
}

function toggleMark(): void {
  if (store.currentQuestion) store.toggleMark(store.currentQuestion.questionId)
}

async function submitExam(): Promise<void> {
  try {
    const result = await store.submitExam()
    if (result !== 'submitted' || store.submitStatus !== 'submitted') return
    ElMessage.success('交卷成功')
  } catch (error) {
    if (store.submitStatus === 'conflict') {
      ElMessage.warning(toSafeUserMessage(error))
      return
    }
    if (store.submitStatus === 'submitting') {
      ElMessage.info('交卷处理中，请稍后确认')
      return
    }
    ElMessage.error(toSafeUserMessage(error))
  }
}

async function continueToNextSubject(): Promise<void> {
  try {
    await store.continueNextSubject()
    ElMessage.success('\u5df2\u8fdb\u5165\u6848\u4f8b\u5206\u6790')
  } catch (error) {
    ElMessage.error(toSafeUserMessage(error))
  }
}

function stripHtml(value: string): string {
  return value.replace(/<[^>]*>/g, ' ').replace(/&nbsp;/g, ' ')
}

function editorHtml(value: string): string {
  if (/<\/?(div|p|br|span|b|strong|i|em|u|s|sup|sub)(\s|>)/i.test(value)) return value
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/\n/g, '<br>')
}

function syncEditorContent(): void {
  const editor = editorRef.value
  if (!editor || !isSubjective.value || document.activeElement === editor) return
  const nextValue = editorHtml(selectedAnswer.value)
  if (editor.innerHTML !== nextValue) editor.innerHTML = nextValue
  ensureHistorySnapshot(selectedAnswer.value)
}

function ensureHistorySnapshot(value: string): void {
  const questionId = store.currentQuestion?.subQuestionId
  if (!questionId) return
  const key = String(questionId)
  const history = historyByQuestion.value[key] ?? []
  if (history.length === 0) historyByQuestion.value = { ...historyByQuestion.value, [key]: [value] }
}

function recordHistory(value: string): void {
  const questionId = store.currentQuestion?.subQuestionId
  if (!questionId) return
  const key = String(questionId)
  const history = historyByQuestion.value[key] ?? []
  if (history.at(-1) === value) return
  historyByQuestion.value = { ...historyByQuestion.value, [key]: [...history, value].slice(-20) }
}

async function saveSubjectiveAnswer(): Promise<void> {
  const value = editorRef.value?.innerHTML ?? ''
  recordHistory(value)
  try {
    await answer(value)
  } catch (error) {
    ElMessage.error(toSafeUserMessage(error))
  }
}

function focusEditor(): HTMLDivElement | undefined {
  editorRef.value?.focus()
  return editorRef.value
}

function formatEditor(command: string): void {
  if (!focusEditor()) return
  document.execCommand(command, false)
  void saveSubjectiveAnswer()
}

function setLineHeight(value: number): void {
  lineHeight.value = value
  focusEditor()
}

function cycleAlignment(): void {
  const nextAlignment = alignment.value === 'left' ? 'center' : alignment.value === 'center' ? 'right' : 'left'
  alignment.value = nextAlignment
  formatEditor(`justify${nextAlignment.charAt(0).toUpperCase()}${nextAlignment.slice(1)}`)
}

function cycleLineHeight(): void {
  const nextLineHeight = lineHeight.value === 1.5 ? 1.75 : lineHeight.value === 1.75 ? 2 : 1.5
  setLineHeight(nextLineHeight)
}

function emphasizeSelectedPrompt(): void {
  const prompt = document.querySelector('[data-testid="subjective-prompt"]')
  const selection = window.getSelection()
  if (!prompt || !selection || selection.rangeCount === 0 || selection.isCollapsed) {
    ElMessage.info('请先选中题干文字，再点击强调显示')
    return
  }
  const range = selection.getRangeAt(0)
  if (!prompt.contains(range.commonAncestorContainer)) {
    ElMessage.info('请先在题干区域选中文字')
    return
  }
  const mark = document.createElement('mark')
  mark.className = 'exam-prompt-emphasis'
  mark.appendChild(range.extractContents())
  range.insertNode(mark)
  selection.removeAllRanges()
}

function clearPromptEmphasis(): void {
  const prompt = document.querySelector('[data-testid="subjective-prompt"]')
  if (!prompt) return
  prompt.querySelectorAll('mark.exam-prompt-emphasis').forEach((mark) => {
    const parent = mark.parentNode
    if (!parent) return
    while (mark.firstChild) parent.insertBefore(mark.firstChild, mark)
    parent.removeChild(mark)
  })
}

function insertFormula(): void {
  const value = formulaValue.value.trim()
  if (!value || !focusEditor()) return
  const safeValue = value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  document.execCommand('insertHTML', false, `<span class="exam-formula">${safeValue}</span>`)
  formulaValue.value = ''
  formulaDialogVisible.value = false
  void saveSubjectiveAnswer()
}

async function resetEditor(): Promise<void> {
  try {
    await ElMessageBox.confirm('重置会清空当前题的全部作答内容，是否继续？', '重置作答', {
      confirmButtonText: '重置',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  if (editorRef.value) editorRef.value.innerHTML = ''
  await saveSubjectiveAnswer()
}

async function restoreHistory(value: string): Promise<void> {
  if (editorRef.value) editorRef.value.innerHTML = value
  historyVisible.value = false
  await saveSubjectiveAnswer()
}

async function toggleFullscreen(): Promise<void> {
  const workspace = subjectiveWorkspaceRef.value
  if (!workspace) return
  try {
    if (document.fullscreenElement) await document.exitFullscreen()
    else await workspace.requestFullscreen()
  } catch {
    editorFullscreen.value = !editorFullscreen.value
  }
}

function syncFullscreenState(): void {
  editorFullscreen.value = document.fullscreenElement === subjectiveWorkspaceRef.value
}
</script>

<template>
  <main class="exam-view" data-testid="p6-exam-page">
    <header class="exam-header">
      <div class="exam-header__candidate">
        <div class="exam-header__avatar"><el-icon><UserFilled /></el-icon></div>
        <div>
          <span>准考证号：{{ store.candidate?.maskedAdmissionTicket }}</span>
          <span>姓名：{{ store.candidate?.name }}</span>
        </div>
      </div>
      <div class="exam-header__title">
        <strong>AI高项培训系统 · 机考模拟作答系统</strong>
        <span>{{ store.paper?.qualification }}（{{ store.paper?.subject }}）</span>
      </div>
      <div class="exam-header__status">
        <div><span>机位：{{ store.candidate?.stationNo }}</span></div>
        <div class="exam-header__time">
          <span>剩余时间</span>
          <strong data-testid="exam-remaining-time">{{ formatDuration(store.remainingSeconds) }}</strong>
        </div>
          <el-button
            v-if="store.submitStatus === 'submitted' && store.canContinue"
            type="primary"
            class="exam-header__submit"
            data-testid="exam-continue-subject"
            :loading="store.loading"
            @click="continueToNextSubject"
          >
            进入案例分析
          </el-button>
          <a
            v-else-if="store.submitStatus === 'submitted'"
            href="/practice"
            class="exam-header__return"
            data-testid="exam-return-practice"
          >
            返回刷题工作区
          </a>
          <el-button
            v-else-if="!store.canContinue"
            type="warning"
            class="exam-header__submit"
            data-testid="exam-submit"
            :loading="store.submitStatus === 'submitting'"
            @click="submitExam"
        >
          交卷
        </el-button>
      </div>
    </header>

    <template v-if="store.paper && store.currentQuestion">
      <div class="exam-body">
        <QuestionNavigator
          :questions="store.paper.questions"
          :current-index="store.currentQuestionIndex"
          :answered-ids="store.answeredQuestionIds"
          :marked-ids="store.markedQuestionIds"
          @select="store.goToQuestion"
        />

        <section class="exam-workspace">
          <div class="exam-workspace__instructions">
            <p v-for="instruction in store.paper.instructions" :key="instruction">{{ instruction }}</p>
          </div>

          <div class="question-area" :class="{ 'question-area--subjective': isSubjective }">
            <div class="question-area__meta">
              <span class="workspace-badge">{{ workspaceType }}</span>
              <span>{{ store.clockState?.subjectOrder ?? 1 }} / {{ store.clockState?.subjectCount ?? 1 }}</span>
              <span>第 {{ store.currentQuestion.displayNo }} 题</span>
              <span>{{ store.currentQuestion.score }} 分</span>
              <span :class="{ 'save-state--saving': store.saving }">
                <el-icon><Connection /></el-icon>
                {{ store.saving ? '保存中' : '已保存' }}
              </span>
            </div>

            <section
              v-if="isSubjective"
              ref="subjectiveWorkspaceRef"
              class="subjective-workspace"
              :class="{ 'subjective-workspace--fullscreen': editorFullscreen }"
              data-testid="subjective-workspace"
            >
              <article class="subjective-prompt-panel">
                <header class="subjective-panel__header">
                  <strong>{{ isEssay ? '论文题目' : '案例材料与题目' }}</strong>
                  <div class="prompt-tools" aria-label="题干阅读工具">
                    <button type="button" title="缩小题干文字" data-testid="prompt-font-decrease" @click="promptFontSize = Math.max(14, promptFontSize - 1)">A-</button>
                    <button type="button" title="放大题干文字" data-testid="prompt-font-increase" @click="promptFontSize = Math.min(24, promptFontSize + 1)">A+</button>
                    <button type="button" title="强调选中的题干文字" data-testid="prompt-highlight" @mousedown.prevent="emphasizeSelectedPrompt">强调显示</button>
                    <button type="button" title="取消题干文字强调" data-testid="prompt-highlight-clear" @click="clearPromptEmphasis">取消强调</button>
                  </div>
                </header>
                <div
                  class="subjective-prompt-panel__content"
                  :style="{ fontSize: `${promptFontSize}px` }"
                  data-testid="subjective-prompt"
                >
                  <p class="subjective-prompt-panel__score">本题 {{ store.currentQuestion.score }} 分</p>
                  <div>{{ store.currentQuestion.prompt }}</div>
                  <p v-if="isEssay" class="subjective-prompt-panel__notice">请按题目要求完成论文作答，系统不会显示参考答案或解析。</p>
                </div>
              </article>

              <article class="subjective-editor-panel" :class="{ 'subjective-editor-panel--case-objective': caseObjective }">
                <header class="subjective-panel__header">
                  <strong>{{ isEssay ? '论文作答区' : `第 ${store.currentQuestion.displayNo} 题作答区` }}</strong>
                  <span>答题编辑工具</span>
                </header>
                <div v-if="!caseObjective" class="editor-toolbar" aria-label="作答编辑工具">
                  <div class="editor-toolbar__group">
                    <button type="button" title="切换对齐方式" data-testid="editor-align" @click="cycleAlignment">对齐</button>
                  </div>
                  <div class="editor-toolbar__group">
                    <button type="button" title="切换行距" data-testid="editor-line-height" @click="cycleLineHeight">行距</button>
                  </div>
                  <div class="editor-toolbar__group">
                    <button type="button" title="上标" data-testid="editor-superscript" @click="formatEditor('superscript')">上标</button>
                    <button type="button" title="下标" data-testid="editor-subscript" @click="formatEditor('subscript')">下标</button>
                    <button type="button" class="tool-button--disabled" title="绘图功能暂不开发" disabled>绘图</button>
                    <button type="button" title="插入公式" data-testid="editor-formula" @click="formulaDialogVisible = true">公式</button>
                  </div>
                  <div class="editor-toolbar__group editor-toolbar__group--end">
                    <button type="button" title="重置当前作答" data-testid="editor-reset" @click="resetEditor">重置</button>
                    <button type="button" title="查看历史记录" data-testid="editor-history" @click="historyVisible = true">历史记录</button>
                    <button type="button" title="全屏作答" data-testid="editor-fullscreen" @click="toggleFullscreen">{{ editorFullscreen ? '退出全屏' : '全屏' }}</button>
                    <span class="editor-word-count">字数：{{ answerWordCount }}{{ store.currentQuestion.wordLimit ? ` / ${store.currentQuestion.wordLimit}` : '' }}</span>
                  </div>
                </div>
                <el-radio-group
                  v-if="caseObjective"
                  :model-value="selectedAnswer"
                  class="case-objective-options"
                  data-testid="case-question-options"
                  @change="answer"
                >
                  <el-radio
                    v-for="option in store.currentQuestion.options"
                    :key="option.key"
                    :value="option.key"
                    :data-testid="`case-answer-${option.key}`"
                  >
                    <strong>{{ option.key }}</strong>
                    <span>{{ option.content }}</span>
                  </el-radio>
                </el-radio-group>
                <div
                  v-else
                  ref="editorRef"
                  class="subjective-editor"
                  :style="{ lineHeight: String(lineHeight) }"
                  contenteditable="true"
                  role="textbox"
                  aria-multiline="true"
                  :data-placeholder="isEssay ? '请输入论文正文' : '请输入案例分析答案'"
                  :data-testid="isEssay ? 'essay-answer' : 'case-answer'"
                  @input="saveSubjectiveAnswer"
                />
              </article>
            </section>

            <template v-else>
              <h1>{{ store.currentQuestion.prompt }}</h1>
              <el-radio-group
                :model-value="selectedAnswer"
                class="question-options"
                data-testid="question-options"
                @change="answer"
              >
                <el-radio
                  v-for="option in store.currentQuestion.options"
                  :key="option.key"
                  :value="option.key"
                  :data-testid="`answer-${option.key}`"
                >
                  <strong>{{ option.key }}</strong>
                  <span>{{ option.content }}</span>
                </el-radio>
              </el-radio-group>
            </template>
          </div>

          <footer class="exam-toolbar">
            <div class="exam-toolbar__navigation">
              <el-button size="large" :disabled="store.currentQuestionIndex === 0" @click="previous">上一题</el-button>
              <el-button
                type="primary"
                size="large"
                :disabled="store.currentQuestionIndex === store.paper.questions.length - 1"
                @click="next"
              >
                下一题
              </el-button>
            </div>
            <el-button
              size="large"
              :class="{ 'mark-button--active': currentMarked }"
              data-testid="toggle-mark"
              @click="toggleMark"
            >
              <el-icon><Flag /></el-icon>
              {{ currentMarked ? '取消标记' : '标记' }}
            </el-button>
            <DisabledTools v-if="!isSubjective" />
            <span v-else class="exam-toolbar__subjective-tip">作答内容会自动保存</span>
          </footer>
        </section>
      </div>
    </template>
    <div v-else class="exam-view__loading">
      <el-progress type="circle" :percentage="store.paperLoadProgress" color="#ff9800" />
      <p>正在加载试卷，考试计时不会暂停</p>
    </div>

    <el-dialog v-model="formulaDialogVisible" title="插入公式" width="420px" append-to-body>
      <el-input v-model="formulaValue" placeholder="例如：PV = FV / (1 + r)^n" @keyup.enter="insertFormula" />
      <template #footer>
        <el-button @click="formulaDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="insertFormula">插入</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="historyVisible" title="当前题历史记录" width="620px" append-to-body>
      <div class="answer-history">
        <button
          v-for="(item, index) in currentHistory"
          :key="`${index}-${item.length}`"
          type="button"
          @click="restoreHistory(item)"
        >
          <span>版本 {{ index + 1 }}</span>
          <small>{{ stripHtml(item).trim() || '空白作答' }}</small>
        </button>
        <p v-if="currentHistory.length === 0">当前题暂无历史记录。</p>
      </div>
    </el-dialog>
  </main>
</template>

<style scoped>
.exam-view {
  width: 100%;
  height: 100%;
  display: grid;
  grid-template-rows: 112px minmax(0, 1fr);
  color: #1f2937;
  background: #fff;
  overflow: hidden;
}

.exam-header {
  display: grid;
  grid-template-columns: minmax(300px, 0.8fr) minmax(440px, 1.25fr) minmax(370px, 0.9fr);
  align-items: center;
  min-width: 0;
  color: #fff;
  background: #168de2;
  border-bottom: 1px solid #0877c6;
}

.exam-header__candidate {
  min-width: 0;
  height: 100%;
  display: grid;
  grid-template-columns: 86px minmax(0, 1fr);
  align-items: center;
  gap: 16px;
}

.exam-header__avatar {
  height: 100%;
  display: grid;
  place-items: center;
  color: #fff;
  background: #d7d9dc;
}

.exam-header__avatar .el-icon { font-size: 64px; }
.exam-header__candidate > div:last-child { display: grid; gap: 10px; font-size: 14px; }
.exam-header__title { min-width: 0; padding: 0 20px; text-align: center; }
.exam-header__title strong, .exam-header__title span { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.exam-header__title strong { font-size: clamp(19px, 1.6vw, 28px); }
.exam-header__title span { margin-top: 8px; font-size: 17px; }

.exam-header__status {
  display: grid;
  grid-template-columns: auto minmax(126px, 1fr) 150px;
  gap: 18px;
  align-items: center;
  padding-right: 22px;
}
.exam-header__time span, .exam-header__time strong { display: block; }
.exam-header__time strong { margin-top: 3px; color: #ffb12a; font-size: clamp(24px, 2vw, 34px); font-variant-numeric: tabular-nums; white-space: nowrap; }
.exam-header__submit { width: 130px; height: 54px; border-radius: 2px; font-size: 19px; }
.exam-header__return { width: 150px; height: 54px; display: inline-flex; align-items: center; justify-content: center; border: 1px solid #fff; border-radius: 2px; color: #0877c6; background: #fff; font-size: 16px; font-weight: 600; text-decoration: none; }

.exam-body { min-height: 0; display: grid; grid-template-columns: clamp(244px, 17vw, 310px) minmax(0, 1fr); overflow: hidden; }
.exam-workspace { min-width: 0; min-height: 0; display: grid; grid-template-rows: auto minmax(0, 1fr) 88px; overflow: hidden; background: #f2f9ff; }
.exam-workspace__instructions { padding: 12px 28px; background: #dff1ff; border-bottom: 1px solid #bedcf2; }
.exam-workspace__instructions p { margin: 3px 0; font-size: 14px; font-weight: 600; }

.question-area { min-height: 0; padding: 26px 34px; background: #f5fbff; overflow: auto; }
.question-area--subjective { display: grid; grid-template-rows: auto minmax(0, 1fr); gap: 14px; overflow: hidden; padding-bottom: 18px; }
.question-area__meta { display: flex; align-items: center; gap: 18px; color: #526174; font-size: 14px; }
.workspace-badge { padding: 4px 8px; border: 1px solid #8bc8f4; border-radius: 2px; color: #0877c6; background: #eaf6ff; font-weight: 700; }
.question-area__meta span:last-child { display: inline-flex; align-items: center; gap: 6px; margin-left: auto; color: #199560; }
.save-state--saving { color: #c96d00 !important; }
.question-area h1 { max-width: 1100px; margin: 22px 0 28px; font-size: clamp(16px, 1.25vw, 21px); font-weight: 500; line-height: 1.8; }

.question-options { display: grid; gap: 18px; align-items: start; }
.question-options :deep(.el-radio) { width: fit-content; max-width: 100%; height: auto; min-height: 42px; display: flex; align-items: flex-start; margin-right: 0; padding: 9px 14px; border: 1px solid transparent; border-radius: 2px; white-space: normal; }
.question-options :deep(.el-radio:hover), .question-options :deep(.el-radio.is-checked) { border-color: #8bc8f4; background: #eaf6ff; }
.question-options :deep(.el-radio__label) { display: flex; gap: 16px; color: #27364a; line-height: 1.6; white-space: normal; }

.subjective-workspace { min-height: 0; display: grid; grid-template-columns: minmax(300px, 0.92fr) minmax(380px, 1.08fr); border: 1px solid #aac9df; background: #fff; overflow: hidden; }
.subjective-workspace:fullscreen, .subjective-workspace--fullscreen { height: 100%; background: #f5fbff; }
.subjective-prompt-panel, .subjective-editor-panel { min-width: 0; min-height: 0; display: grid; grid-template-rows: auto minmax(0, 1fr); }
.subjective-prompt-panel { border-right: 1px solid #aac9df; background: #f8fcff; }
.subjective-editor-panel { grid-template-rows: auto auto minmax(0, 1fr); background: #fff; }
.subjective-editor-panel--case-objective { grid-template-rows: auto minmax(0, 1fr); }
.subjective-panel__header { min-height: 42px; display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 8px 12px; border-bottom: 1px solid #aac9df; color: #21354a; background: #e4f4ff; font-size: 14px; }
.subjective-panel__header > span { color: #526174; font-size: 12px; white-space: nowrap; }
.prompt-tools, .editor-toolbar, .editor-toolbar__group { display: flex; align-items: center; gap: 4px; }
.prompt-tools button, .editor-toolbar button { height: 26px; padding: 0 8px; border: 1px solid #b7cee0; border-radius: 2px; color: #3f546b; background: #fff; font: inherit; font-size: 12px; cursor: pointer; }
.prompt-tools button:hover, .editor-toolbar button:hover, .tool-button--active { border-color: #168de2 !important; color: #0877c6 !important; background: #eaf6ff !important; }
.editor-toolbar button:disabled { cursor: not-allowed; opacity: 0.52; }
.tool-button--disabled { color: #8794a3 !important; background: #f3f6f8 !important; }
.subjective-prompt-panel__content { min-height: 0; padding: 16px 18px 22px; overflow: auto; color: #1f2937; line-height: 1.8; white-space: pre-wrap; }
.subjective-prompt-panel__score { margin: 0 0 12px; color: #0877c6; font-size: 13px; font-weight: 700; }
.subjective-prompt-panel__notice { margin: 22px 0 0; padding-top: 12px; border-top: 1px dashed #b7cee0; color: #5a6877; font-size: 13px; }
.subjective-prompt-panel__content :deep(.exam-prompt-emphasis) { padding: 0 2px; color: inherit; background: #fff36a; font-weight: 700; }
.editor-toolbar { min-height: 42px; flex-wrap: wrap; padding: 7px 10px; border-bottom: 1px solid #c8dbe9; background: #f8fcff; }
.editor-toolbar__group { padding-right: 8px; border-right: 1px solid #d2e1ec; }
.editor-toolbar__group--end { margin-left: auto; padding-right: 0; border-right: 0; }
.editor-word-count { margin-left: 8px; color: #667085; font-size: 12px; white-space: nowrap; }
.subjective-editor { min-height: 0; padding: 16px 18px 42px; overflow: auto; outline: none; color: #1f2937; font-size: 15px; white-space: pre-wrap; }
.subjective-editor:empty::before { content: attr(data-placeholder); color: #96a1ad; pointer-events: none; }
.subjective-editor :deep(.exam-formula) { display: inline-block; margin: 0 3px; padding: 1px 5px; border: 1px solid #8bc8f4; color: #075c98; background: #eaf6ff; font-family: Cambria, serif; }
.case-objective-options { min-height: 0; display: grid; align-content: start; gap: 12px; padding: 18px; overflow: auto; }
.case-objective-options :deep(.el-radio) { height: auto; min-height: 44px; display: flex; align-items: flex-start; margin: 0; padding: 10px 12px; border: 1px solid #c8dbe9; border-radius: 2px; white-space: normal; }
.case-objective-options :deep(.el-radio:hover), .case-objective-options :deep(.el-radio.is-checked) { border-color: #168de2; background: #eaf6ff; }
.case-objective-options :deep(.el-radio__label) { display: flex; gap: 12px; color: #26384b; line-height: 1.65; white-space: normal; }

.exam-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 14px 22px; background: #fff; border-top: 1px solid #cbddeb; }
.exam-toolbar__navigation { display: flex; gap: 14px; }
.exam-toolbar__navigation .el-button { min-width: 150px; border-radius: 2px; }
.mark-button--active { border-color: #ff9800; color: #c96d00; background: #fff7e8; }
.exam-toolbar__subjective-tip { margin-left: auto; color: #64748b; font-size: 13px; }
.exam-view__loading { display: grid; place-content: center; gap: 18px; text-align: center; }

.answer-history { display: grid; gap: 8px; max-height: 420px; overflow: auto; }
.answer-history button { display: grid; grid-template-columns: 76px minmax(0, 1fr); gap: 12px; align-items: start; width: 100%; padding: 10px; border: 1px solid #c8dbe9; border-radius: 2px; color: #26384b; background: #f8fcff; text-align: left; cursor: pointer; }
.answer-history button:hover { border-color: #168de2; background: #eaf6ff; }
.answer-history small { overflow: hidden; color: #64748b; line-height: 1.5; text-overflow: ellipsis; white-space: nowrap; }
.answer-history p { margin: 0; color: #64748b; }

@media (max-width: 1450px), (max-height: 820px) {
  .exam-view { grid-template-rows: 92px minmax(0, 1fr); }
  .exam-header { grid-template-columns: minmax(280px, 0.75fr) minmax(400px, 1.2fr) minmax(350px, 0.9fr); }
  .exam-header__candidate { grid-template-columns: 70px minmax(0, 1fr); }
  .exam-header__avatar .el-icon { font-size: 50px; }
  .exam-header__status { gap: 10px; }
  .exam-header__submit { width: 128px; height: 48px; }
  .exam-header__return { width: 128px; height: 48px; font-size: 14px; }
  .exam-workspace { grid-template-rows: auto minmax(0, 1fr) 72px; }
  .question-area { padding: 18px 26px; }
  .exam-toolbar { padding: 10px 16px; }
  .exam-toolbar__navigation .el-button { min-width: 110px; }
  .subjective-panel__header { min-height: 36px; padding: 5px 8px; }
  .subjective-prompt-panel__content, .subjective-editor { padding: 12px; }
  .editor-toolbar { min-height: 36px; padding: 4px 6px; }
}

@media (max-width: 900px) {
  .exam-body { grid-template-columns: 1fr; }
  .exam-body > :first-child { display: none; }
  .subjective-workspace { grid-template-columns: 1fr; grid-template-rows: minmax(200px, 0.75fr) minmax(280px, 1fr); overflow: auto; }
  .subjective-prompt-panel { border-right: 0; border-bottom: 1px solid #aac9df; }
}
</style>
