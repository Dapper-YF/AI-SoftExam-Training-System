<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, CircleCheck, Clock, EditPen, Flag, Fold, Star, MagicStick } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { toSafeUserMessage } from '@/api/client'
import { getPracticeGateway } from '../api/gateway'
import { collapseRepeatedQuestionText, isSameQuestionText } from '../question-content'
import { PracticeAnswerMutationController, type PracticeAnswerMutationOutcome } from '../state/practice-answer-mutation'
import { buildVisibleQuestionIndexes, deriveQuestionState } from '../state/practice-attempt-state'
import type {
  PracticeAnswerPayload,
  PracticeAnswerValue,
  PracticeQuestion,
  PracticeQuestionState,
  PracticeSubQuestion,
} from '../types'
import { usePracticeStore } from '@/stores/practice'
import { readPracticeSessionPresentation } from '../session-presentation'
import QuestionRichContent from '../components/QuestionRichContent.vue'
import StructuredDiagramEditor from '../components/StructuredDiagramEditor.vue'
import StructuredAnswerEditor from '../components/StructuredAnswerEditor.vue'

type SaveState = 'idle' | 'saving' | 'saved' | 'failed' | 'conflict'

const route = useRoute()
const router = useRouter()
const practiceStore = usePracticeStore()
const gateway = getPracticeGateway()
const noteDialogVisible = ref(false)
const noteDraft = ref('')
const fontScale = ref<'small' | 'medium' | 'large'>('medium')
const saveStates = reactive<Record<string, SaveState>>({})
const saveErrors = reactive<Record<string, string>>({})
const draftAnswers = reactive<Record<string, PracticeAnswerValue>>({})
const persistedQuestionIds = ref(new Set<string>())
const confirmedQuestionIds = ref(new Set<string>())
const activeSubQuestionIndex = ref(0)
const submitting = ref(false)

const mutationController = new PracticeAnswerMutationController({
  send: (snapshot) => gateway.saveAnswer(
    snapshot.attemptId,
    snapshot.questionId,
    snapshot.subQuestionId,
    snapshot.answerPayload as PracticeAnswerPayload,
    snapshot.marked,
    { baseVersion: snapshot.baseVersion, clientMutationId: snapshot.clientMutationId, usedSeconds: snapshot.usedSeconds },
  ),
})

const attemptId = computed(() => String(route.params.attemptId))
const sessionPresentation = computed(() => readPracticeSessionPresentation(attemptId.value))
const practiceReturnPath = computed(() => sessionPresentation.value?.returnPath ?? '/practice')
const isWholePaper = computed(() => practiceStore.attempt?.feedbackMode === 'WHOLE_PAPER')
const singleItemSettlementAvailable = computed(() => practiceStore.attempt?.feedbackMode === 'PER_QUESTION')
const isTerminal = computed(() => practiceStore.attempt?.status !== 'IN_PROGRESS')
const currentQuestion = computed(() => practiceStore.currentQuestion)
const currentSubQuestion = computed(() => currentQuestion.value?.subQuestions[activeSubQuestionIndex.value])
const selectedAnswer = computed(() => {
  const sub = currentSubQuestion.value
  return sub ? draftAnswers[sub.subQuestionId] ?? practiceStore.attempt?.answers[sub.subQuestionId] : undefined
})
const currentWordCount = computed(() => {
  const value = selectedAnswer.value
  return typeof value === 'string' ? value.trim().length : 0
})
const isCurrentConfirmed = computed(() => Boolean(currentQuestion.value && confirmedQuestionIds.value.has(currentQuestion.value.questionId)))
const canShowAnalysis = computed(() => Boolean(
  currentQuestion.value
  && (isTerminal.value || currentQuestion.value.settlementStatus === 'SETTLED')
  && currentSubQuestion.value?.standardAnswer !== undefined,
))
const supportedAnswerType = computed(() => Boolean(
  currentSubQuestion.value
  && ['OPTION', 'BOOLEAN', 'TEXT', 'NUMBER', 'JSON'].includes(subAnswerFormat(currentSubQuestion.value)),
))
const visibleQuestionIndexes = computed(() => buildVisibleQuestionIndexes(practiceStore.attempt?.questions.length ?? 0))
const saveState = computed<SaveState>(() => currentSubQuestion.value ? saveStates[currentSubQuestion.value.subQuestionId] ?? 'idle' : 'idle')
const saveError = computed(() => currentSubQuestion.value ? saveErrors[currentSubQuestion.value.subQuestionId] ?? '' : '')
const saveLabel = computed(() => ({ idle: '未保存', saving: '保存中', saved: '已保存', failed: '保存失败', conflict: '版本冲突' })[saveState.value])
const gradingStatus = computed(() => currentSubQuestion.value?.gradingStatus)
const gradingLabel = computed(() => ({
  CORRECT: '回答正确',
  WRONG: '回答错误',
  PENDING_REVIEW: '待人工复核',
  SAVED: '待判定',
})[gradingStatus.value ?? 'SAVED'])
const attemptStatusLabel = computed(() => ({
  IN_PROGRESS: '进行中',
  SUBMITTED: '已提交',
  PENDING_REVIEW: '待复核',
})[practiceStore.attempt?.status ?? 'IN_PROGRESS'])
const visibleQuestionStem = computed(() => collapseRepeatedQuestionText(currentQuestion.value?.stem ?? ''))
const visibleSubQuestionContent = computed(() => {
  const content = currentSubQuestion.value?.content ?? ''
  return currentQuestion.value && isSameQuestionText(visibleQuestionStem.value, content) ? '' : content
})
const hasPendingSave = computed(() => Object.values(saveStates).some((state) => state === 'saving'))
const caseLikeQuestion = computed(() => currentQuestion.value?.questionType === 'CASE' || currentQuestion.value?.questionType === 'ESSAY')
const isEssayExam = computed(() => currentQuestion.value?.questionType === 'ESSAY')
const isDiagramQuestion = computed(() => currentSubQuestion.value?.type === 'FLOWCHART' || currentSubQuestion.value?.type === 'UML_GRAPH')
const isStructuredQuestion = computed(() => ['CODE_GAP', 'FILL_BLANK', 'REQUIREMENT_SPEC', 'ARCHITECTURE_DESIGN', 'SERVICE_DESIGN', 'METRIC_CONFIGURATION'].includes(currentSubQuestion.value?.type ?? ''))
const currentDiagramMode = computed<'FLOWCHART' | 'UML_GRAPH'>(() =>
  currentSubQuestion.value?.type === 'UML_GRAPH' ? 'UML_GRAPH' : 'FLOWCHART')
const sessionTitle = computed(() => {
  const attempt = practiceStore.attempt
  if (!attempt) return ''
  const mode = sessionPresentation.value?.mode ?? attempt.mode
  const scopeName = sessionPresentation.value?.scopeName
    ?? (typeof attempt.sourceSpec?.knowledgeName === 'string' ? attempt.sourceSpec.knowledgeName : undefined)
    ?? (mode === 'CHAPTER' || mode === 'KNOWLEDGE_POINT' ? currentQuestion.value?.knowledgePoint : undefined)
  if (mode === 'CHAPTER' && scopeName) return `章节练习/${scopeName}`
  if (mode === 'KNOWLEDGE_POINT' && scopeName) return `知识点专项/${scopeName}`
  if (mode === 'DAILY') return '每日练习'
  return attempt.title
})
const improvingExplanationLabel = '解析正在完善'

function askAiAboutCurrentQuestion(): void {
  if (!currentQuestion.value || !practiceStore.attempt) return
  window.dispatchEvent(new CustomEvent('ai-assistant:open', {
    detail: { questionId: currentQuestion.value.questionId },
  }))
}

let routeLoadEpoch = 0

watch(() => practiceStore.currentIndex, () => { activeSubQuestionIndex.value = 0 })

function replaceSet(source: Set<string>, value: string, add: boolean): Set<string> {
  const next = new Set(source)
  if (add) next.add(value); else next.delete(value)
  return next
}

function hasAnswer(value: PracticeAnswerValue | undefined): boolean {
  return Array.isArray(value) ? value.length > 0 : typeof value === 'string' ? Boolean(value.trim()) : typeof value === 'boolean' || typeof value === 'number' || (typeof value === 'object' && value !== null)
}

function answerPayload(sub: PracticeSubQuestion, value: PracticeAnswerValue): PracticeAnswerPayload {
  const format = subAnswerFormat(sub)
  if (isOptionAnswerFormat(format) && sub.type === 'SINGLE_CHOICE' && typeof value === 'string') return { optionKey: value }
  if (isOptionAnswerFormat(format) && Array.isArray(value) && value.length) return { optionKeys: value }
  if (format === 'BOOLEAN' && typeof value === 'boolean') return { value }
  if (format === 'JSON' && typeof value === 'object' && value !== null && !Array.isArray(value)) return { structured: value }
  if (['TEXT', 'JSON'].includes(format) && typeof value === 'string' && value.trim()) return { text: value.trim() }
  if (format === 'NUMBER' && (typeof value === 'string' || typeof value === 'number') && String(value).trim()) return { text: String(value).trim() }
  throw new Error('当前答案与题型不匹配')
}

function answerLabel(value: PracticeAnswerValue | undefined): string {
  if (Array.isArray(value)) return value.join('、')
  if (typeof value === 'boolean') return value ? '正确' : '错误'
  if (typeof value === 'number') return String(value)
  if (typeof value === 'object' && value !== null) return '结构化答案'
  return value ?? '—'
}

function solutionLabel(sub: PracticeSubQuestion): string {
  if (sub.solutionStatus === 'IMPROVING') return improvingExplanationLabel
  return sub.solution || '该来源未提供解析。'
}

function subAnswerFormat(sub: PracticeSubQuestion): PracticeSubQuestion['answerFormat'] {
  return sub.answerFormat
}

function isOptionAnswerFormat(format: PracticeSubQuestion['answerFormat']): boolean {
  return format === 'OPTION'
}

function answerFormatLabel(sub: PracticeSubQuestion): string {
  const format = subAnswerFormat(sub)
  if (isOptionAnswerFormat(format)) return sub.type === 'MULTI_CHOICE' ? '多选' : '单选'
  if (format === 'BOOLEAN') return '判断'
  if (format === 'NUMBER') return '数字作答'
  if (format === 'JSON') return '结构化作答'
  return sub.type === 'ESSAY_WRITING' ? '论文写作' : '文本作答'
}

function metadataNumber(sub: PracticeSubQuestion, keys: string[]): number | undefined {
  for (const key of keys) {
    const value = sub.metadata?.[key]
    if (typeof value === 'number' && Number.isFinite(value) && value >= 0) return value
    if (typeof value === 'string' && /^\d+$/.test(value)) return Number(value)
  }
  return undefined
}

function wordRangeLabel(sub: PracticeSubQuestion): string {
  const min = metadataNumber(sub, ['minWords', 'minWordCount', 'wordMin'])
  const max = metadataNumber(sub, ['maxWords', 'maxWordCount', 'wordMax', 'wordLimit'])
  if (min != null && max != null) return ` / ${min}-${max}`
  if (max != null) return ` / ${max}`
  if (min != null) return ` / 不少于 ${min}`
  return ''
}

function questionState(index: number): PracticeQuestionState {
  const question = practiceStore.attempt?.questions[index]
  if (!question) return 'unanswered'
  const gradingStatuses = question.subQuestions.map((sub) => sub.gradingStatus)
  const result = gradingStatuses.includes('WRONG')
    ? 'WRONG'
    : gradingStatuses.length > 0 && gradingStatuses.every((status) => status === 'CORRECT')
      ? 'CORRECT'
      : undefined
  return deriveQuestionState({
    current: index === practiceStore.currentIndex,
    marked: practiceStore.attempt?.markedQuestionIds.includes(question.questionId) ?? false,
    persisted: persistedQuestionIds.value.has(question.questionId),
    confirmed: confirmedQuestionIds.value.has(question.questionId),
    result,
  })
}

function refreshQuestionPersistence(question: PracticeQuestion): void {
  const attempt = practiceStore.attempt
  if (!attempt) return
  const complete = question.subQuestions.every((sub) => hasAnswer(attempt.answers[sub.subQuestionId]))
  persistedQuestionIds.value = replaceSet(persistedQuestionIds.value, question.questionId, complete)
}

function applySuccessfulMutation(outcome: Extract<PracticeAnswerMutationOutcome, { kind: 'succeeded' }>): boolean {
  const attempt = practiceStore.attempt
  if (!attempt) return false
  const snapshot = outcome.snapshot
  const payload = snapshot.answerPayload
  attempt.answers[snapshot.subQuestionId] = 'optionKey' in payload
    ? payload.optionKey
    : 'optionKeys' in payload ? [...payload.optionKeys]
      : 'value' in payload ? payload.value
        : 'structured' in payload ? payload.structured : payload.text
  attempt.answerVersions[snapshot.subQuestionId] = outcome.result.answerVersion
  attempt.markedQuestionIds = snapshot.marked
    ? Array.from(new Set([...attempt.markedQuestionIds, snapshot.questionId]))
    : attempt.markedQuestionIds.filter((id) => id !== snapshot.questionId)
  const question = attempt.questions.find((item) => item.questionId === snapshot.questionId)
  if (question) refreshQuestionPersistence(question)
  saveStates[snapshot.subQuestionId] = outcome.nextPending ? 'saving' : 'saved'
  delete saveErrors[snapshot.subQuestionId]
  return true
}

function applyMutationOutcome(outcome: PracticeAnswerMutationOutcome, subQuestionId: string): boolean {
  if (outcome.kind === 'succeeded') return applySuccessfulMutation(outcome)
  if (outcome.kind === 'failed') { saveStates[subQuestionId] = 'failed'; saveErrors[subQuestionId] = toSafeUserMessage(outcome.error) }
  else if (outcome.kind === 'conflict') { saveStates[subQuestionId] = 'conflict'; saveErrors[subQuestionId] = toSafeUserMessage(outcome.error) }
  else if (outcome.kind === 'reload-required') { saveStates[subQuestionId] = 'conflict'; saveErrors[subQuestionId] = toSafeUserMessage(outcome.error) }
  else if (outcome.kind === 'none') { saveStates[subQuestionId] = 'idle'; delete saveErrors[subQuestionId] }
  else if (outcome.kind === 'inflight') saveStates[subQuestionId] = 'saving'
  return false
}

async function persistAnswer(value = selectedAnswer.value, marked?: boolean): Promise<boolean> {
  const attempt = practiceStore.attempt
  const question = currentQuestion.value
  const sub = currentSubQuestion.value
  if (!attempt || !question || !sub || value === undefined || isTerminal.value || isCurrentConfirmed.value) return false
  const subQuestionId = sub.subQuestionId
  saveStates[subQuestionId] = 'saving'
  delete saveErrors[subQuestionId]
  try {
    const outcome = await mutationController.begin({
      attemptId: attempt.id,
      questionId: question.questionId,
      subQuestionId,
      answerPayload: answerPayload(sub, value),
      marked: marked ?? attempt.markedQuestionIds.includes(question.questionId),
      usedSeconds: 0,
      baseVersion: attempt.answerVersions[subQuestionId] ?? 0,
    })
    return applyMutationOutcome(outcome, subQuestionId)
  } catch (error) {
    saveStates[subQuestionId] = 'failed'
    saveErrors[subQuestionId] = toSafeUserMessage(error)
    return false
  }
}

async function retryPendingCurrent(): Promise<void> {
  const id = currentSubQuestion.value?.subQuestionId
  if (!id || saveStates[id] === 'conflict') return
  saveStates[id] = 'saving'; delete saveErrors[id]
  applyMutationOutcome(await mutationController.retry(id), id)
}

async function selectAnswer(answer: PracticeAnswerValue): Promise<void> {
  const sub = currentSubQuestion.value
  if (!sub || isTerminal.value || isCurrentConfirmed.value) return
  draftAnswers[sub.subQuestionId] = answer
  const saved = await persistAnswer(answer)
  if (saved && singleItemSettlementAvailable.value && ['SINGLE_CHOICE', 'JUDGE'].includes(sub.type)) {
    await confirmAnswer()
  }
}

async function toggleMultiAnswer(code: string): Promise<void> {
  const current = Array.isArray(selectedAnswer.value) ? selectedAnswer.value : []
  const next = current.includes(code) ? current.filter((item) => item !== code) : [...current, code]
  if (!next.length) {
    const sub = currentSubQuestion.value
    if (sub) draftAnswers[sub.subQuestionId] = []
    return
  }
  await selectAnswer(next)
}

async function saveTextAnswer(): Promise<void> { await persistAnswer() }

async function loadExplanation(question: PracticeQuestion): Promise<void> {
  const explanation = await gateway.getExplanation(attemptId.value, question.questionId)
  if (!explanation?.subQuestions) return
  explanation.subQuestions.forEach((row) => {
    const sub = question.subQuestions.find((item) => item.subQuestionId === row.subQuestionId)
    if (!sub) return
    sub.standardAnswer = row.standardAnswer
    sub.solution = row.solution
    sub.solutionStatus = row.solutionStatus ?? (row.solution ? 'READY' : undefined)
    sub.scoringPoints = row.scoringPoints
  })
  const first = question.subQuestions[0]
  question.correctAnswer = first?.standardAnswer
  question.explanation = question.subQuestions.map((sub) => sub.solution).filter(Boolean).join('\n') || undefined
}

async function confirmAnswer(): Promise<void> {
  const attempt = practiceStore.attempt
  const question = currentQuestion.value
  if (!attempt || !question || !persistedQuestionIds.value.has(question.questionId) || !singleItemSettlementAvailable.value) return
  try {
    const result = await gateway.settleQuestion(attempt.id, question.questionId, question.settlementVersion)
    attempt.version = result.version
    const restoredLatest = await practiceStore.restoreAttempt(attempt.id)
    if (!restoredLatest || practiceStore.attempt?.id !== attempt.id) {
      ElMessage.error('题目已结算，但最新题目状态加载失败，请重试。')
      return
    }
    const restored = practiceStore.attempt?.questions.find((item) => item.questionId === question.questionId)
    if (restored) await loadExplanation(restored)
    hydrateAttemptState()
    ElMessage.success('题目已结算')
  } catch (error) {
    ElMessage.error(toSafeUserMessage(error))
  }
}

async function toggleMarked(): Promise<void> {
  const attempt = practiceStore.attempt
  const question = currentQuestion.value
  if (!attempt || !question || !hasAnswer(selectedAnswer.value) || isTerminal.value || isCurrentConfirmed.value) {
    ElMessage.info('请先保存当前子题答案。'); return
  }
  await persistAnswer(selectedAnswer.value, !attempt.markedQuestionIds.includes(question.questionId))
}

async function reloadServerVersion(): Promise<void> {
  const id = currentSubQuestion.value?.subQuestionId
  if (!id) return
  mutationController.prepareReload(id); saveStates[id] = 'saving'; delete saveErrors[id]
  const restoredLatest = await practiceStore.restoreAttempt(attemptId.value)
  if (!restoredLatest || practiceStore.attempt?.id !== attemptId.value) {
    const error = new Error('服务器版本重新加载失败，请稍后重试。')
    mutationController.markReloadFailed(id, error); saveStates[id] = 'conflict'; saveErrors[id] = toSafeUserMessage(error); return
  }
  hydrateAttemptState(true)
  const restoredQuestion = practiceStore.attempt.questions.find((question) =>
    question.subQuestions.some((subQuestion) => subQuestion.subQuestionId === id))
  if (practiceStore.attempt.status !== 'IN_PROGRESS' || restoredQuestion?.settlementStatus === 'SETTLED') {
    mutationController.clear(id)
    delete draftAnswers[id]
    delete saveErrors[id]
    saveStates[id] = hasAnswer(practiceStore.attempt.answers[id]) ? 'saved' : 'idle'
    return
  }
  applyMutationOutcome(await mutationController.resumeAfterReload(id, practiceStore.attempt?.answerVersions[id] ?? 0), id)
}

async function toggleFavorite(): Promise<void> {
  const attempt = practiceStore.attempt; const question = currentQuestion.value
  if (!attempt || !question) return
  const next = !attempt.favoriteQuestionIds.includes(question.questionId)
  try {
    await gateway.setFavorite(question.questionId, next)
    attempt.favoriteQuestionIds = next ? [...attempt.favoriteQuestionIds, question.questionId] : attempt.favoriteQuestionIds.filter((id) => id !== question.questionId)
    ElMessage.success(next ? '已收藏' : '已取消收藏')
  } catch (error) { ElMessage.error(toSafeUserMessage(error)) }
}

function openNote(): void {
  const question = currentQuestion.value
  noteDraft.value = question ? practiceStore.attempt?.notes[question.questionId] ?? '' : ''
  noteDialogVisible.value = true
}

async function saveNote(): Promise<void> {
  const attempt = practiceStore.attempt; const question = currentQuestion.value
  if (!attempt || !question) return
  try {
    const content = noteDraft.value.trim(); await gateway.saveNote(question.questionId, content)
    attempt.notes[question.questionId] = content; noteDialogVisible.value = false; ElMessage.success('笔记已保存')
  } catch (error) { ElMessage.error(toSafeUserMessage(error)) }
}

async function finishPractice(): Promise<void> {
  const attempt = practiceStore.attempt
  if (!attempt || isTerminal.value) { await router.push('/app/practice/reports'); return }
  if (hasPendingSave.value) {
    ElMessage.info('答案仍在保存，请稍候再提交。')
    return
  }
  try {
    await ElMessageBox.confirm('提交后答案将锁定，确定提交整份练习吗？', '提交练习', { confirmButtonText: '确认提交', cancelButtonText: '继续作答', type: 'warning' })
  } catch {
    return
  }
  submitting.value = true
  try {
    const result = await gateway.submitAttempt(attempt.id, attempt.version)
    attempt.version = result.version; attempt.status = result.status
    const restoredLatest = await practiceStore.restoreAttempt(attempt.id)
    if (!restoredLatest || practiceStore.attempt?.id !== attempt.id) {
      ElMessage.error('练习已提交，但最新结果加载失败，请重试。')
      return
    }
    const restored = practiceStore.currentQuestion
    if (restored) await loadExplanation(restored)
    hydrateAttemptState()
    ElMessage.success(result.status === 'PENDING_REVIEW' ? '已提交，主观题待复核' : '练习已提交')
  } catch (error) { ElMessage.error(toSafeUserMessage(error)) }
  finally { submitting.value = false }
}

function goToQuestion(index: number): void { activeSubQuestionIndex.value = 0; void practiceStore.goToQuestion(index) }

function hydrateAttemptState(preserveReload = false): void {
  const attempt = practiceStore.attempt
  if (!attempt) return
  if (!preserveReload) {
    mutationController.clearAll()
    for (const key of Object.keys(saveStates)) delete saveStates[key]
    for (const key of Object.keys(saveErrors)) delete saveErrors[key]
    for (const key of Object.keys(draftAnswers)) delete draftAnswers[key]
  }
  const persisted = new Set<string>(); const confirmed = new Set<string>()
  attempt.questions.forEach((question) => {
    if (question.subQuestions.every((sub) => hasAnswer(attempt.answers[sub.subQuestionId]))) persisted.add(question.questionId)
    if (question.settlementStatus === 'SETTLED') confirmed.add(question.questionId)
    question.subQuestions.forEach((sub) => {
      if (hasAnswer(attempt.answers[sub.subQuestionId])) { mutationController.markSaved(sub.subQuestionId); saveStates[sub.subQuestionId] = 'saved' }
      else saveStates[sub.subQuestionId] = 'idle'
      delete saveErrors[sub.subQuestionId]; delete draftAnswers[sub.subQuestionId]
    })
  })
  persistedQuestionIds.value = persisted; confirmedQuestionIds.value = confirmed
}

function isDailyAttempt(attempt: NonNullable<typeof practiceStore.attempt>): boolean {
  return attempt.mode === 'DAILY' || attempt.sourceSpec?.daily === true
}

function isObjectiveQuestion(question: PracticeQuestion): boolean {
  return question.subQuestions.every((sub) => ['SINGLE_CHOICE', 'MULTI_CHOICE', 'JUDGE'].includes(sub.type))
}

async function settleSavedDailyQuestions(): Promise<boolean> {
  const attempt = practiceStore.attempt
  if (!attempt || attempt.status !== 'IN_PROGRESS' || !isDailyAttempt(attempt)) return false
  const pending = attempt.questions.filter((question) =>
    question.settlementStatus !== 'SETTLED'
    && isObjectiveQuestion(question)
    && question.subQuestions.every((sub) => hasAnswer(attempt.answers[sub.subQuestionId])),
  )
  if (!pending.length) return false
  try {
    for (const question of pending) {
      const result = await gateway.settleQuestion(attempt.id, question.questionId, question.settlementVersion)
      attempt.version = result.version
    }
    return true
  } catch {
    return false
  }
}

async function loadRouteAttempt(id: string): Promise<void> {
  const requestEpoch = ++routeLoadEpoch
  const restoredLatest = await practiceStore.restoreAttempt(id)
  if (
    requestEpoch !== routeLoadEpoch
    || !restoredLatest
    || attemptId.value !== id
    || practiceStore.attempt?.id !== id
  ) return
  activeSubQuestionIndex.value = 0
  hydrateAttemptState()
  if (await settleSavedDailyQuestions()) {
    const refreshed = await practiceStore.restoreAttempt(id)
    if (!refreshed || requestEpoch !== routeLoadEpoch || practiceStore.attempt?.id !== id) return
    hydrateAttemptState()
  }
  const restored = practiceStore.currentQuestion
  if (restored && (practiceStore.attempt?.status !== 'IN_PROGRESS' || restored.settlementStatus === 'SETTLED')) {
    try {
      await loadExplanation(restored)
    } catch {
      // 恢复已提交记录时，参考答案接口失败不能让已保存作答白屏。
    }
  }
}

watch(attemptId, (id) => { void loadRouteAttempt(id) }, { immediate: true })

onBeforeUnmount(() => {
  ++routeLoadEpoch
})
</script>

<template>
  <div class="practice-session-page" :class="[`font-scale--${fontScale}`, { 'practice-session-page--essay-exam': isEssayExam }]" data-testid="practice-session-page">
    <div v-if="practiceStore.attemptStatus === 'loading' && !practiceStore.attempt" class="session-loading"><el-skeleton :rows="10" /></div>
    <div v-else-if="practiceStore.attemptStatus === 'error' && !practiceStore.attempt" class="practice-state practice-state--error" data-testid="practice-attempt-error"><strong>练习记录加载失败</strong><p>{{ practiceStore.errorMessage }}</p><el-button @click="practiceStore.restoreAttempt(attemptId)">重试</el-button></div>
    <template v-else-if="practiceStore.attempt && currentQuestion && currentSubQuestion">
      <section class="practice-session-toolbar">
        <div class="session-toolbar__main">
          <button class="session-back" type="button" @click="router.push(practiceReturnPath)"><el-icon><ArrowLeft /></el-icon><span>返回题库</span></button>
          <div class="session-toolbar__title">
            <strong class="session-title">{{ sessionTitle }}</strong>
            <span class="feedback-badge">{{ isWholePaper ? '整卷反馈' : '逐题反馈' }}</span>
          </div>
        </div>
        <div class="session-toolbar__status">
          <span class="session-position">第 <b>{{ currentQuestion.number }}</b> 题 / 共 {{ practiceStore.attempt.questions.length }} 题</span>
          <span class="session-timer"><el-icon><Clock /></el-icon>{{ attemptStatusLabel }}</span>
          <span class="save-indicator" :class="`is-${saveState}`"><el-icon v-if="saveState === 'saved'"><CircleCheck /></el-icon>{{ saveLabel }}</span>
        </div>
        <div class="session-toolbar__actions">
          <el-dropdown @command="fontScale = $event"><button class="toolbar-text-button" type="button">Aa <span>字体</span></button><template #dropdown><el-dropdown-menu><el-dropdown-item command="small">小号</el-dropdown-item><el-dropdown-item command="medium">标准</el-dropdown-item><el-dropdown-item command="large">大号</el-dropdown-item></el-dropdown-menu></template></el-dropdown>
          <el-button type="primary" plain :loading="submitting" :disabled="hasPendingSave" @click="finishPractice">{{ isTerminal ? '返回历史' : '提交练习' }}</el-button>
        </div>
      </section>
      <div v-if="saveState === 'failed' || saveState === 'conflict'" class="session-save-alert" role="alert"><span>{{ saveError }}。{{ saveState === 'conflict' ? '请重新加载服务端最新版本。' : '本地输入仍保留。' }}</span><el-button v-if="saveState === 'conflict'" size="small" @click="reloadServerVersion">重新加载服务器版本</el-button><el-button v-else size="small" @click="retryPendingCurrent">重试</el-button></div>
      <div class="practice-session-grid">
        <main :key="`${practiceStore.attempt.id}:${currentQuestion.questionId}:${currentSubQuestion.subQuestionId}:${currentQuestion.settlementVersion}`" class="question-workspace">
          <div v-if="isEssayExam" class="essay-exam-meta"><span>时长 {{ currentSubQuestion.metadata?.durationMinutes ?? 120 }} 分钟</span><span>满分 {{ currentSubQuestion.score ?? 75 }} 分</span></div>
          <div class="question-heading-row"><span class="question-number">{{ currentQuestion.number }}</span><strong>{{ currentQuestion.typeLabel }}</strong><span class="knowledge-tag">知识点：{{ currentQuestion.knowledgePoint }}</span></div>
          <section v-if="caseLikeQuestion" class="fulltype-material-panel" :data-question-type="currentQuestion.questionType" data-testid="fulltype-material">
            <strong>{{ currentQuestion.questionType === 'ESSAY' ? '论文背景与写作要求' : '案例材料' }}</strong>
            <QuestionRichContent :content="visibleQuestionStem" />
          </section>
          <QuestionRichContent v-else class="question-stem" :content="visibleQuestionStem" />
          <div v-if="currentQuestion.subQuestions.length > 1" class="mode-segmented" aria-label="案例子题">
            <button v-for="(sub, index) in currentQuestion.subQuestions" :key="sub.subQuestionId" type="button" :class="{ 'is-active': activeSubQuestionIndex === index }" @click="activeSubQuestionIndex = index">子题 {{ index + 1 }}</button>
          </div>
          <QuestionRichContent v-if="visibleSubQuestionContent" class="question-stem question-sub-stem" :content="visibleSubQuestionContent" />
          <div class="fulltype-answer-meta" :data-testid="`answer-format-${subAnswerFormat(currentSubQuestion)}`">
            <span>{{ answerFormatLabel(currentSubQuestion) }}</span>
            <span v-if="currentSubQuestion.score != null">{{ currentSubQuestion.score }} 分</span>
            <span v-if="subAnswerFormat(currentSubQuestion) === 'TEXT' && currentSubQuestion.type === 'ESSAY_WRITING'">字数 {{ currentWordCount }}{{ wordRangeLabel(currentSubQuestion) }}</span>
          </div>
          <div v-if="isOptionAnswerFormat(subAnswerFormat(currentSubQuestion)) && currentSubQuestion.type === 'SINGLE_CHOICE'" class="question-options" role="radiogroup" aria-label="答案选项"><button v-for="option in currentSubQuestion.options" :key="option.code" type="button" :data-testid="`practice-answer-${option.code}`" :disabled="isTerminal || isCurrentConfirmed" :class="{ 'is-selected': selectedAnswer === option.code }" @click="selectAnswer(option.code)"><span class="option-code">{{ option.code }}</span><span>{{ option.content }}</span></button></div>
          <div v-else-if="isOptionAnswerFormat(subAnswerFormat(currentSubQuestion))" class="question-options" aria-label="多选答案"><button v-for="option in currentSubQuestion.options" :key="option.code" type="button" :disabled="isTerminal || isCurrentConfirmed" :class="{ 'is-selected': Array.isArray(selectedAnswer) && selectedAnswer.includes(option.code) }" @click="toggleMultiAnswer(option.code)"><span class="option-code">{{ option.code }}</span><span>{{ option.content }}</span></button></div>
          <div v-else-if="subAnswerFormat(currentSubQuestion) === 'BOOLEAN'" class="question-options"><button type="button" :disabled="isTerminal || isCurrentConfirmed" :class="{ 'is-selected': selectedAnswer === true }" @click="selectAnswer(true)">正确</button><button type="button" :disabled="isTerminal || isCurrentConfirmed" :class="{ 'is-selected': selectedAnswer === false }" @click="selectAnswer(false)">错误</button></div>
          <div v-else-if="isDiagramQuestion" class="practice-filter-panel">
            <StructuredDiagramEditor
              :model-value="typeof selectedAnswer === 'object' && selectedAnswer !== null && !Array.isArray(selectedAnswer) ? selectedAnswer : undefined"
              :mode="currentDiagramMode"
              :readonly="isTerminal || isCurrentConfirmed"
              @update:model-value="draftAnswers[currentSubQuestion.subQuestionId] = $event"
            />
            <el-button type="primary" :disabled="!hasAnswer(selectedAnswer) || isTerminal || isCurrentConfirmed" @click="saveTextAnswer">保存图形答案</el-button>
          </div>
          <div v-else-if="subAnswerFormat(currentSubQuestion) === 'NUMBER'" class="practice-filter-panel"><input v-model="draftAnswers[currentSubQuestion.subQuestionId]" class="fulltype-number-input" data-testid="number-answer-input" type="number" :disabled="isTerminal || isCurrentConfirmed" placeholder="请输入数字答案"><el-button type="primary" :disabled="!hasAnswer(selectedAnswer) || isTerminal || isCurrentConfirmed" @click="saveTextAnswer">保存当前子题</el-button></div>
          <div v-else-if="isStructuredQuestion" class="practice-filter-panel"><StructuredAnswerEditor :type="currentSubQuestion.type" :model-value="typeof selectedAnswer === 'object' && selectedAnswer !== null && !Array.isArray(selectedAnswer) ? selectedAnswer : undefined" :readonly="isTerminal || isCurrentConfirmed" @update:model-value="draftAnswers[currentSubQuestion.subQuestionId] = $event" /><el-button type="primary" :disabled="!hasAnswer(selectedAnswer) || isTerminal || isCurrentConfirmed" @click="saveTextAnswer">保存结构化答案</el-button></div>
          <div v-else-if="supportedAnswerType" class="practice-filter-panel" :class="{ 'essay-exam-editor': isEssayExam }">
            <div v-if="isEssayExam" class="essay-exam-editor__toolbar" aria-label="论文编辑工具栏"><span>正文</span><span>段落</span><span>引用</span><span>项目符号</span></div>
            <el-input v-model="draftAnswers[currentSubQuestion.subQuestionId]" type="textarea" :rows="currentSubQuestion.type === 'ESSAY_WRITING' ? 18 : 8" :disabled="isTerminal || isCurrentConfirmed" placeholder="请输入论文正文" data-testid="essay-writing-input" />
            <div class="essay-exam-editor__footer"><span v-if="isEssayExam">当前已输入 {{ currentWordCount }} 个字符，最多 {{ currentSubQuestion.metadata?.maxWords ?? 6000 }} 个字符。</span><el-button type="primary" :disabled="!hasAnswer(selectedAnswer) || isTerminal || isCurrentConfirmed" @click="saveTextAnswer">保存进度</el-button></div>
          </div>
          <div v-else class="settlement-unavailable" data-testid="unsupported-question-type"><strong>当前题型暂不支持在线作答</strong><span>系统不会把该题型伪装成选择题，请返回并选择其他练习。</span></div>
          <div class="question-tools"><button v-if="canShowAnalysis" type="button" @click="askAiAboutCurrentQuestion"><el-icon><MagicStick /></el-icon>问 AI</button><button type="button" :class="{ 'is-active': practiceStore.isCurrentMarked }" @click="toggleMarked"><el-icon><Flag /></el-icon>标记</button><button type="button" :class="{ 'is-active': practiceStore.isCurrentFavorite }" @click="toggleFavorite"><el-icon><Star /></el-icon>收藏</button><button type="button" :class="{ 'is-active': Boolean(practiceStore.currentNote) }" @click="openNote"><el-icon><EditPen /></el-icon>笔记</button></div>
          <div class="question-actions"><el-button :disabled="practiceStore.currentIndex === 0" @click="goToQuestion(practiceStore.currentIndex - 1)">上一题</el-button><el-button v-if="singleItemSettlementAvailable" type="primary" data-testid="confirm-answer" :disabled="!persistedQuestionIds.has(currentQuestion.questionId) || isCurrentConfirmed || isTerminal" @click="confirmAnswer">{{ isCurrentConfirmed ? '已结算' : '结算本题' }}</el-button><el-button :disabled="practiceStore.currentIndex >= practiceStore.attempt.questions.length - 1" @click="goToQuestion(practiceStore.currentIndex + 1)">下一题</el-button></div>
          <p v-if="isWholePaper && !isTerminal" class="settlement-unavailable">整卷提交前不显示标准答案、判分状态或解析。</p>
          <section v-if="canShowAnalysis" class="question-analysis" data-testid="question-analysis"><div class="analysis-result" :class="{ 'is-wrong': gradingStatus === 'WRONG' }" :data-grading-status="gradingStatus"><strong>{{ gradingLabel }}</strong><span>标准答案：{{ answerLabel(currentSubQuestion.standardAnswer) }}</span></div><div class="analysis-section"><h3>{{ currentQuestion.questionType === 'ESSAY' ? '参考结构' : '标准解析' }}</h3><p>{{ solutionLabel(currentSubQuestion) }}</p></div><div v-if="currentSubQuestion.scoringPoints?.length" class="analysis-section fulltype-scoring-points" data-testid="scoring-points"><h3>{{ currentQuestion.questionType === 'ESSAY' ? '评分维度' : '评分点' }}</h3><button v-for="point in currentSubQuestion.scoringPoints" :key="point.scoringPointId" type="button" disabled><span>{{ point.pointCode }}</span><strong>{{ point.pointText }}</strong><em>{{ point.score }} 分</em></button><p v-if="currentQuestion.questionType === 'CASE'" class="self-review-unavailable">自评选择本轮暂未开放持久化，仅展示评分点供复核。</p></div></section>
        </main>
        <aside class="answer-card-panel" aria-label="答题卡"><div class="answer-card-heading"><strong>答题卡</strong><el-icon><Fold /></el-icon></div><div class="answer-card-meta"><span>{{ isWholePaper ? '整卷反馈' : '逐题反馈' }}</span><span><el-icon><CircleCheck /></el-icon>{{ saveLabel }}</span></div><div class="answer-legend"><span><i class="answer-legend-swatch legend-empty"></i>未答</span><span><i class="answer-legend-swatch legend-correct"></i>正确</span><span><i class="answer-legend-swatch legend-wrong"></i>错误</span><span><i class="answer-legend-swatch legend-current"></i>当前</span><span><el-icon><Flag /></el-icon>标记</span></div><div class="answer-number-scroll"><p class="answer-card-rule">{{ isWholePaper ? '提交前不显示答案或解析' : '保存全部子题后可结算本题' }}</p><div class="answer-number-grid"><button v-for="index in visibleQuestionIndexes" :key="index" type="button" :class="[`is-${questionState(index)}`]" :aria-label="`跳转到第${index + 1}题`" @click="goToQuestion(index)">{{ index + 1 }}<el-icon v-if="questionState(index).includes('marked')"><Flag /></el-icon></button></div></div><div class="answer-card-summary"><span>已答 <strong>{{ persistedQuestionIds.size }}</strong> / {{ practiceStore.attempt.questions.length }} 题</span><span>已结算 <strong>{{ confirmedQuestionIds.size }}</strong> 题</span></div></aside>
      </div>
    </template>
    <el-dialog v-model="noteDialogVisible" title="题目笔记" width="520px" :close-on-click-modal="false"><el-input v-model="noteDraft" type="textarea" :rows="6" maxlength="1000" show-word-limit placeholder="记录解题思路、易错点或复习提醒" /><template #footer><el-button @click="noteDialogVisible = false">取消</el-button><el-button type="primary" @click="saveNote">保存笔记</el-button></template></el-dialog>
  </div>
</template>

<style scoped>
.practice-session-page {
  padding: 16px 20px 28px;
  gap: 14px;
  color: #172033;
  background: #f4f7fa;
}

.practice-session-toolbar {
  display: grid;
  min-height: 72px;
  padding: 10px 12px;
  grid-template-columns: minmax(260px, 1fr) auto auto;
  align-items: center;
  gap: 18px;
  border: 1px solid #dce4ee;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 7px 24px rgba(15, 23, 42, 0.05);
}

.session-toolbar__main,
.session-toolbar__status,
.session-toolbar__actions,
.session-toolbar__title {
  display: flex;
  min-width: 0;
  align-items: center;
}

.session-toolbar__main {
  gap: 14px;
}

.session-toolbar__title {
  gap: 9px;
  overflow: hidden;
}

.session-toolbar__status {
  gap: 16px;
}

.session-toolbar__actions {
  gap: 8px;
}

.session-back {
  height: 38px;
  padding: 0 12px 0 8px;
  flex: 0 0 auto;
  border: 0;
  border-right: 1px solid #e2e8f0;
  color: #475569;
  background: transparent;
  font-weight: 500;
  transition: color 150ms ease;
}

.session-back:hover {
  color: #2563eb;
}

.session-title {
  color: #172033;
  font-size: 16px;
  font-weight: 700;
}

.feedback-badge {
  flex: 0 0 auto;
  padding: 4px 8px;
  border: 0;
  border-radius: 999px;
  color: #1d4ed8;
  background: #edf4ff;
  font-size: 11px;
  font-weight: 600;
}

.session-position,
.session-timer,
.save-indicator {
  color: #64748b;
  font-size: 12px;
}

.session-position b {
  color: #2563eb;
  font-size: 17px;
}

.save-indicator {
  padding: 5px 9px;
  border-radius: 999px;
  background: #f1f5f9;
}

.save-indicator.is-saved {
  color: #15803d;
  background: #edf9f0;
}

.save-indicator.is-saving {
  color: #b45309;
  background: #fff7e8;
}

.save-indicator.is-failed,
.save-indicator.is-conflict {
  color: #dc2626;
  background: #fef2f2;
}

.toolbar-text-button {
  height: 38px;
  padding: 0 12px;
  border: 1px solid #dce4ee;
  border-radius: 9px;
  color: #475569;
  background: #fff;
}

.session-toolbar__actions :deep(.el-button) {
  height: 38px;
  margin: 0;
  border-radius: 9px;
  font-weight: 600;
}

.session-save-alert {
  min-height: 46px;
  padding: 8px 14px;
  border-radius: 11px;
}

.practice-session-grid {
  grid-template-columns: minmax(0, 1fr) 306px;
  gap: 14px;
}

.question-workspace {
  min-height: 650px;
  padding: 30px 34px 26px;
  border: 1px solid #dce4ee;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 10px 30px rgba(15, 23, 42, 0.045);
}

.question-heading-row {
  gap: 11px;
}

.question-number {
  width: 42px;
  height: 42px;
  border-radius: 12px;
  background: #2563eb;
  box-shadow: 0 7px 16px rgba(37, 99, 235, 0.2);
  font-size: 19px;
}

.question-heading-row > strong {
  color: #263247;
  font-size: 15px;
}

.knowledge-tag {
  min-height: 28px;
  padding: 0 9px;
  border: 0;
  border-radius: 999px;
  color: #52647c;
  background: #f1f5f9;
  font-size: 12px;
}

.question-stem {
  margin: 24px 2px 22px;
  color: #1e293b;
  font-size: 17px;
  line-height: 1.9;
}

.question-sub-stem {
  margin-top: 4px;
  padding: 14px 16px;
  border-left: 3px solid #bfdbfe;
  border-radius: 0 10px 10px 0;
  background: #f8fafc;
}

.fulltype-material-panel {
  margin-top: 22px;
  padding: 20px;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: #f8fafc;
}

.fulltype-material-panel > strong {
  color: #334155;
}

.mode-segmented {
  padding: 4px;
  border-radius: 10px;
  background: #f1f5f9;
}

.mode-segmented button {
  border-radius: 7px;
}

.fulltype-answer-meta {
  margin-bottom: 12px;
  color: #64748b;
}

.question-options {
  gap: 10px;
}

.question-options button {
  min-height: 60px;
  padding: 11px 15px;
  border: 1px solid #dce4ee;
  border-radius: 12px;
  color: #405069;
  background: #fff;
  line-height: 1.65;
  box-shadow: 0 2px 6px rgba(15, 23, 42, 0.025);
  transition: border-color 150ms ease, background 150ms ease, box-shadow 150ms ease, transform 150ms ease;
}

.question-options button:hover:not(:disabled) {
  border-color: #9fbcf4;
  background: #f8fbff;
  box-shadow: 0 5px 14px rgba(37, 99, 235, 0.07);
  transform: translateY(-1px);
}

.question-options button.is-selected {
  border-color: #2563eb;
  color: #1e293b;
  background: #f4f8ff;
  box-shadow: 0 0 0 2px rgba(37, 99, 235, 0.08);
}

.option-code {
  width: 34px;
  height: 34px;
  flex: 0 0 34px;
  border-color: #cbd5e1;
  border-radius: 10px;
  color: #475569;
  background: #f8fafc;
  font-weight: 700;
}

.question-options button.is-selected .option-code {
  border-color: #2563eb;
  background: #2563eb;
  box-shadow: 0 5px 12px rgba(37, 99, 235, 0.18);
}

.practice-filter-panel {
  padding: 14px;
  border-radius: 12px;
  background: #fbfcfe;
}

.practice-filter-panel :deep(.el-textarea__inner),
.fulltype-number-input {
  border-radius: 10px;
}

.question-tools {
  margin-top: 18px;
  gap: 8px;
  flex-wrap: wrap;
}

.question-tools button {
  min-height: 38px;
  padding: 0 12px;
  border-color: #dce4ee;
  border-radius: 9px;
  color: #526078;
  background: #fff;
  transition: border-color 150ms ease, color 150ms ease, background 150ms ease;
}

.question-tools button:hover,
.question-tools button.is-active {
  border-color: #afc5f2;
  color: #2563eb;
  background: #f5f8ff;
}

.question-actions {
  min-height: 70px;
  margin-top: 16px;
  padding: 12px;
  border: 0;
  border-radius: 12px;
  background: #f7f9fc;
}

.question-actions :deep(.el-button) {
  height: 42px;
  border-radius: 9px;
  font-weight: 600;
}

.settlement-unavailable {
  border-radius: 10px;
}

.question-analysis {
  margin-top: 20px;
  border-color: #cde6d4;
  border-radius: 14px;
  background: #fff;
}

.analysis-result {
  min-height: 60px;
  padding: 0 20px;
  background: #f3fbf5;
}

.analysis-result.is-wrong {
  background: #fff5f5;
}

.analysis-section {
  padding: 18px 20px;
}

.analysis-section h3 {
  color: #263247;
  font-size: 15px;
}

.analysis-section p {
  color: #526078;
  line-height: 1.85;
}

.answer-card-panel {
  top: 14px;
  height: calc(100vh - var(--platform-header-height) - 104px);
  max-height: 650px;
  padding: 0 16px 16px;
  border: 1px solid #dce4ee;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 10px 30px rgba(15, 23, 42, 0.045);
}

.answer-card-heading {
  min-height: 58px;
  color: #263247;
  font-size: 17px;
}

.answer-card-heading .el-icon {
  color: #94a3b8;
}

.answer-card-meta {
  min-height: 44px;
}

.answer-card-meta span:first-child {
  padding: 4px 8px;
  border-radius: 999px;
  color: #1d4ed8;
  background: #edf4ff;
}

.answer-legend {
  margin: 6px 0 12px;
  gap: 8px 5px;
}

.answer-legend-swatch {
  border-radius: 5px;
}

.answer-number-scroll {
  padding-top: 12px;
}

.answer-card-rule {
  padding: 9px 10px;
  border-radius: 8px;
  color: #64748b;
  background: #f8fafc;
  line-height: 1.5;
}

.answer-number-grid {
  gap: 9px 7px;
}

.answer-number-grid button {
  width: 40px;
  height: 38px;
  border-color: #cfd8e4;
  border-radius: 9px;
  color: #475569;
  transition: border-color 140ms ease, box-shadow 140ms ease, transform 140ms ease;
}

.answer-number-grid button:hover {
  border-color: #8faef0;
  box-shadow: 0 4px 10px rgba(37, 99, 235, 0.1);
  transform: translateY(-1px);
}

.answer-number-grid button.is-current {
  border: 2px solid #2563eb;
  color: #2563eb;
  box-shadow: inset 0 0 0 2px #edf4ff;
}

.answer-card-summary {
  min-height: 58px;
  flex-basis: 58px;
}

.answer-card-summary strong {
  color: #2563eb;
}

.session-loading,
.practice-state {
  border-radius: 14px;
  background: #fff;
}

.practice-session-page--essay-exam {
  background: #eef2f6;
}

.practice-session-page--essay-exam .practice-session-toolbar,
.practice-session-page--essay-exam .question-workspace {
  border-radius: 14px;
}

.practice-session-page--essay-exam .practice-session-grid {
  grid-template-columns: 270px minmax(0, 1fr);
}

.practice-session-page--essay-exam .question-workspace {
  padding: 26px;
}

@media (max-width: 1366px) {
  .practice-session-toolbar {
    grid-template-columns: minmax(220px, 1fr) auto auto;
    gap: 12px;
  }

  .session-toolbar__status {
    gap: 10px;
  }

  .session-timer {
    display: none;
  }

  .practice-session-grid {
    grid-template-columns: minmax(0, 1fr) 286px;
  }

  .question-workspace {
    padding: 26px 28px 24px;
  }
}

@media (max-width: 1100px) {
  .practice-session-toolbar {
    grid-template-columns: minmax(220px, 1fr) auto;
  }

  .session-toolbar__status {
    display: none;
  }

  .practice-session-grid,
  .practice-session-page--essay-exam .practice-session-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .practice-session-page--essay-exam .answer-card-panel {
    grid-column: auto;
    grid-row: auto;
  }

  .answer-card-panel {
    position: relative;
    top: auto;
    height: auto;
    max-height: none;
  }

  .answer-number-grid {
    grid-template-columns: repeat(10, minmax(36px, 1fr));
  }

  .answer-number-scroll {
    overflow: visible;
  }
}

@media (max-width: 680px) {
  .practice-session-page {
    padding: 10px;
    gap: 10px;
  }

  .practice-session-toolbar {
    display: flex;
    min-height: 64px;
    padding: 9px 10px;
    flex-wrap: nowrap;
    gap: 8px;
  }

  .session-toolbar__main {
    min-width: 0;
    flex: 1 1 auto;
    gap: 8px;
  }

  .session-back {
    width: 36px;
    padding: 0;
    justify-content: center;
    border-right: 0;
  }

  .session-back span,
  .feedback-badge,
  .toolbar-text-button span {
    display: none;
  }

  .session-toolbar__actions {
    flex: 0 0 auto;
  }

  .session-toolbar__actions :deep(.el-button) {
    padding: 0 10px;
  }

  .toolbar-text-button {
    width: 38px;
    padding: 0;
  }

  .question-workspace {
    padding: 20px 16px 18px;
    border-radius: 13px;
  }

  .question-heading-row {
    flex-wrap: wrap;
  }

  .knowledge-tag {
    max-width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .question-stem {
    margin: 20px 0 18px;
    font-size: 16px;
  }

  .question-options button {
    min-height: 56px;
    padding: 10px 12px;
  }

  .question-actions {
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 7px;
  }

  .question-actions :deep(.el-button) {
    width: 100%;
    min-width: 0;
    padding: 0 7px;
  }

  .answer-card-panel {
    padding: 0 14px 14px;
    border-radius: 13px;
  }

  .answer-number-grid {
    grid-template-columns: repeat(5, minmax(36px, 1fr));
  }

  .answer-number-grid button {
    width: 38px;
  }

  .analysis-result {
    padding: 10px 14px;
    align-items: flex-start;
    flex-direction: column;
    justify-content: center;
    gap: 4px;
  }

  .essay-exam-editor__footer {
    align-items: stretch;
    flex-direction: column;
    gap: 8px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .question-options button,
  .question-tools button,
  .answer-number-grid button {
    transition: none;
  }
}
</style>
