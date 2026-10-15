import { computed, reactive, ref } from 'vue'
import { defineStore } from 'pinia'
import { toSafeUserMessage } from '@/api/client'
import { getPracticeGateway } from '@/modules/practice/api/gateway'
import type {
  PracticeAttempt,
  PracticeAnswerPayload,
  PracticeAnswerValue,
  PracticeCreateAttemptInput,
  PracticeListQuery,
  PracticeLoadStatus,
  PracticePaperSummary,
  PracticeQuestionState,
} from '@/modules/practice/types'

function errorDetails(error: unknown): { message: string; traceId?: string } {
  return {
    message: toSafeUserMessage(error),
    traceId: (error as { traceId?: string } | null)?.traceId,
  }
}

export const usePracticeStore = defineStore('practice', () => {
  const papers = ref<PracticePaperSummary[]>([])
  const total = ref(0)
  const page = ref(1)
  const pageSize = ref(6)
  const listStatus = ref<PracticeLoadStatus>('idle')
  const attemptStatus = ref<PracticeLoadStatus>('idle')
  const errorMessage = ref('')
  const errorTraceId = ref<string>()
  const attempt = ref<PracticeAttempt | null>(null)
  const currentIndex = ref(0)
  const lastScope = ref<{ qualificationId: string; subjectId?: string } | null>(null)
  let listEpoch = 0
  let attemptEpoch = 0
  const filters = reactive<Omit<PracticeListQuery, 'qualificationId' | 'subjectId' | 'page' | 'pageSize'>>({
    mode: 'PAST_EXAM',
    chapter: 'ALL',
    knowledgePoint: 'ALL',
    year: 'ALL',
    difficulty: undefined,
    keyword: '',
  })

  const currentQuestion = computed(() => attempt.value?.questions[currentIndex.value])
  const selectedAnswer = computed(() => currentQuestion.value
    ? attempt.value?.answers[currentQuestion.value.subQuestionId]
    : undefined)
  const canShowAnalysis = computed(() => Boolean(
    attempt.value && currentQuestion.value
    && (attempt.value.status !== 'IN_PROGRESS' || currentQuestion.value.settlementStatus === 'SETTLED'),
  ))
  const isCurrentMarked = computed(() => Boolean(
    currentQuestion.value && attempt.value?.markedQuestionIds.includes(currentQuestion.value.questionId),
  ))
  const isCurrentFavorite = computed(() => Boolean(
    currentQuestion.value && attempt.value?.favoriteQuestionIds.includes(currentQuestion.value.questionId),
  ))
  const currentNote = computed(() => currentQuestion.value
    ? attempt.value?.notes[currentQuestion.value.questionId] ?? ''
    : '')

  function clearPaperList(): void {
    ++listEpoch
    lastScope.value = null
    papers.value = []
    total.value = 0
    page.value = 1
    listStatus.value = 'idle'
    errorMessage.value = ''
    errorTraceId.value = undefined
  }

  function resetPaperScope(): void {
    clearPaperList()
    filters.chapter = 'ALL'
    filters.knowledgePoint = 'ALL'
    filters.year = 'ALL'
    filters.difficulty = undefined
    filters.keyword = ''
  }

  function resetPersonalState(): void {
    ++attemptEpoch
    attemptStatus.value = 'idle'
    attempt.value = null
    currentIndex.value = 0
    errorMessage.value = ''
    errorTraceId.value = undefined
  }

  async function loadPapers(qualificationId: string, subjectId?: string): Promise<void> {
    if (!qualificationId) {
      clearPaperList()
      return
    }
    const requestEpoch = ++listEpoch
    lastScope.value = { qualificationId, subjectId }
    papers.value = []
    total.value = 0
    listStatus.value = 'loading'
    errorMessage.value = ''
    errorTraceId.value = undefined
    try {
      const result = await getPracticeGateway().listPapers({
        qualificationId,
        ...(subjectId ? { subjectId } : {}),
        ...filters,
        page: page.value,
        pageSize: pageSize.value,
      })
      if (
        requestEpoch !== listEpoch
        || lastScope.value?.qualificationId !== qualificationId
        || lastScope.value?.subjectId !== subjectId
      ) return
      papers.value = result.items
      total.value = result.total
      listStatus.value = result.items.length ? 'success' : 'empty'
    } catch (error) {
      if (
        requestEpoch !== listEpoch
        || lastScope.value?.qualificationId !== qualificationId
        || lastScope.value?.subjectId !== subjectId
      ) return
      const details = errorDetails(error)
      papers.value = []
      total.value = 0
      listStatus.value = 'error'
      errorMessage.value = details.message
      errorTraceId.value = details.traceId
    }
  }

  async function applyFilters(): Promise<void> {
    page.value = 1
    if (lastScope.value) await loadPapers(lastScope.value.qualificationId, lastScope.value.subjectId)
  }

  async function changePage(next: number): Promise<void> {
    page.value = next
    if (lastScope.value) await loadPapers(lastScope.value.qualificationId, lastScope.value.subjectId)
  }

  async function changePageSize(next: number): Promise<void> {
    pageSize.value = next
    page.value = 1
    if (lastScope.value) await loadPapers(lastScope.value.qualificationId, lastScope.value.subjectId)
  }

  async function startPaper(input: PracticeCreateAttemptInput): Promise<PracticeAttempt> {
    const requestEpoch = ++attemptEpoch
    attemptStatus.value = 'loading'
    try {
      const created = await getPracticeGateway().createAttempt(input)
      if (requestEpoch !== attemptEpoch) return created
      attempt.value = created
      currentIndex.value = 0
      attemptStatus.value = 'success'
      return created
    } catch (error) {
      if (requestEpoch !== attemptEpoch) throw error
      const details = errorDetails(error)
      attemptStatus.value = 'error'
      errorMessage.value = details.message
      errorTraceId.value = details.traceId
      throw error
    }
  }

  async function restoreAttempt(attemptId: string): Promise<boolean> {
    const requestEpoch = ++attemptEpoch
    if (attempt.value?.id !== attemptId) {
      attempt.value = null
      currentIndex.value = 0
    }
    attemptStatus.value = 'loading'
    errorMessage.value = ''
    errorTraceId.value = undefined
    try {
      const restored = await getPracticeGateway().getAttempt(attemptId)
      if (requestEpoch !== attemptEpoch) return false
      attempt.value = restored
      currentIndex.value = Math.min(currentIndex.value, Math.max(0, restored.questions.length - 1))
      attemptStatus.value = 'success'
      return true
    } catch (error) {
      if (requestEpoch !== attemptEpoch) return false
      const details = errorDetails(error)
      attemptStatus.value = 'error'
      errorMessage.value = details.message
      errorTraceId.value = details.traceId
      return false
    }
  }

  function answerPayload(questionType: string, answer: PracticeAnswerValue): PracticeAnswerPayload {
    if (questionType === 'MULTI_CHOICE' && Array.isArray(answer)) return { optionKeys: answer }
    if (questionType === 'JUDGE' && typeof answer === 'boolean') return { value: answer }
    if (['SHORT_ANSWER', 'ESSAY_WRITING', 'CALCULATION'].includes(questionType) && typeof answer === 'string') return { text: answer }
    if (typeof answer === 'string') return { optionKey: answer }
    throw new Error('当前答案与题型不匹配')
  }

  async function answerCurrent(answer: PracticeAnswerValue): Promise<void> {
    const question = currentQuestion.value
    if (!attempt.value || !question) return
    attempt.value.answers[question.subQuestionId] = answer
    const result = await getPracticeGateway().saveAnswer(
      attempt.value.id,
      question.questionId,
      question.subQuestionId,
      answerPayload(question.subQuestions[0]?.type ?? question.questionType, answer),
      isCurrentMarked.value,
      { baseVersion: attempt.value.answerVersions[question.subQuestionId] ?? 0 },
    )
    attempt.value.answerVersions[question.subQuestionId] = result.answerVersion
  }

  async function toggleCurrentMarked(): Promise<void> {
    const question = currentQuestion.value
    if (!attempt.value || !question) return
    attempt.value.markedQuestionIds = isCurrentMarked.value
      ? attempt.value.markedQuestionIds.filter((id) => id !== question.questionId)
      : [...attempt.value.markedQuestionIds, question.questionId]
    const result = await getPracticeGateway().saveAnswer(
      attempt.value.id,
      question.questionId,
      question.subQuestionId,
      answerPayload(
        question.subQuestions[0]?.type ?? question.questionType,
        selectedAnswer.value ?? '',
      ),
      isCurrentMarked.value,
      { baseVersion: attempt.value.answerVersions[question.subQuestionId] ?? 0 },
    )
    attempt.value.answerVersions[question.subQuestionId] = result.answerVersion
  }

  async function toggleCurrentFavorite(): Promise<void> {
    const question = currentQuestion.value
    if (!attempt.value || !question) return
    const next = !isCurrentFavorite.value
    attempt.value.favoriteQuestionIds = next
      ? [...attempt.value.favoriteQuestionIds, question.questionId]
      : attempt.value.favoriteQuestionIds.filter((id) => id !== question.questionId)
    await getPracticeGateway().setFavorite(question.questionId, next)
  }

  async function saveCurrentNote(content: string): Promise<void> {
    const question = currentQuestion.value
    if (!attempt.value || !question) return
    attempt.value.notes[question.questionId] = content
    await getPracticeGateway().saveNote(question.questionId, content)
  }

  async function goToQuestion(index: number): Promise<void> {
    if (!attempt.value) return
    currentIndex.value = Math.min(Math.max(0, index), attempt.value.questions.length - 1)
  }

  function questionState(index: number): PracticeQuestionState {
    const question = attempt.value?.questions[index]
    if (!question) return 'unanswered'
    if (index === currentIndex.value) return 'current'
    const answered = question.subQuestions.some((sub) => attempt.value?.answers[sub.subQuestionId] !== undefined)
    const marked = Boolean(attempt.value?.markedQuestionIds.includes(question.questionId))
    if (answered && marked) return 'answered-marked'
    if (answered) return 'answered'
    if (marked) return 'marked'
    return 'unanswered'
  }

  return {
    papers,
    total,
    page,
    pageSize,
    filters,
    listStatus,
    attemptStatus,
    errorMessage,
    errorTraceId,
    attempt,
    currentIndex,
    currentQuestion,
    selectedAnswer,
    canShowAnalysis,
    isCurrentMarked,
    isCurrentFavorite,
    currentNote,
    clearPaperList,
    resetPaperScope,
    resetPersonalState,
    loadPapers,
    applyFilters,
    changePage,
    changePageSize,
    startPaper,
    restoreAttempt,
    answerCurrent,
    toggleCurrentMarked,
    toggleCurrentFavorite,
    saveCurrentNote,
    goToQuestion,
    questionState,
  }
})
