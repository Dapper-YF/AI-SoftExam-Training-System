<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Close, Document, UploadFilled } from '@element-plus/icons-vue'
import { platformApiClient, unwrapApiResponse } from '@/api/client'
import { useQualificationStore } from '@/stores/qualification'
import QuestionRichContent from '@/modules/practice/components/QuestionRichContent.vue'
import { getAdminQuestionGateway } from '../api/gateway'
import { resolveAdminQuestionError } from '../model/error-messages'

type QuestionType = 'SINGLE_CHOICE' | 'MULTI_CHOICE' | 'JUDGE'
type Difficulty = 'EASY' | 'MEDIUM' | 'HARD'
type Candidate = {
  questionNo: number
  questionType: QuestionType
  content: string
  options: string[]
  answer: string | null
  solution: string | null
  score: number
  imageFileNames: string[]
  warnings: string[]
  difficulty: Difficulty
  knowledgeTagIds: number[]
  chapterId: number | null
}
type CandidateAsset = {
  assetToken: string
  sourceRelationId: string
  sourceOrder: number
  fileName: string
  previewUrl: string
  mediaType: string
  byteSize: number
  width: number | null
  height: number | null
  assignedQuestionNo: number | null
}
type Diagnostics = {
  detectedQuestionCount: number
  imageCount: number
  answerCount: number
  missingQuestionNos: number[]
  duplicateQuestionNos: number[]
  questionsWithoutOptions: number[]
  questionsWithoutAnswers: number[]
  unassignedImageCount: number
  readyForReview: boolean
}
type Preview = {
  uploadToken: string
  fileName: string
  format: string
  fileSha256: string
  questions: Candidate[]
  assets: CandidateAsset[]
  unassignedAssets: CandidateAsset[]
  diagnostics: Diagnostics
  warnings: string[]
}
type KnowledgeTag = { id: number; tagCode: string; tagName: string; status: string }
type Chapter = { id: number; chapterCode: string; chapterName: string; status: string }
type Page<T> = { items?: T[]; records?: T[] }

const emit = defineEmits<{ close: []; imported: [{ batchId?: number | null; paperId?: number | null }] }>()
const qualificationStore = useQualificationStore()
const gateway = getAdminQuestionGateway()
const file = ref<File | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const preview = ref<Preview | null>(null)
const parsing = ref(false)
const submitting = ref(false)
const message = ref('')
const onlyProblems = ref(false)
const verified = ref(false)
const selectedQuestionNo = ref<number | null>(null)
const selectedAsset = ref<CandidateAsset | null>(null)
const assetViewerOpen = ref(false)
const bulkKnowledgeTagIds = ref<number[]>([])
const knowledgeTags = ref<KnowledgeTag[]>([])
const chapters = ref<Chapter[]>([])
const form = reactive({
  qualificationId: '',
  subjectId: '',
  paperName: '',
  yearNo: new Date().getFullYear(),
  semester: 'FIRST_HALF',
  paperKind: 'REAL_PAPER',
  totalScore: 75,
  durationMinutes: 150,
  syllabusBasis: 'Word/PDF 试卷导入',
  sourceName: '',
})
const subjects = ref<Array<{ id: string; code: string; name: string }>>([])

const assignedCount = computed(() => preview.value?.assets.filter(asset => asset.assignedQuestionNo != null).length ?? 0)
const problemQuestionNos = computed(() => new Set(reviewIssues.value
  .map(issue => issue.questionNo)
  .filter((value): value is number => value != null)))
const visibleQuestions = computed(() => {
  const questions = preview.value?.questions ?? []
  return onlyProblems.value ? questions.filter(item => problemQuestionNos.value.has(item.questionNo)) : questions
})
const scoreTotal = computed(() => preview.value?.questions.reduce((sum, item) => sum + Number(item.score || 0), 0) ?? 0)
const reviewIssues = computed<Array<{ questionNo: number | null; message: string }>>(() => {
  if (!preview.value) return []
  const issues: Array<{ questionNo: number | null; message: string }> = []
  const questions = [...preview.value.questions].sort((left, right) => left.questionNo - right.questionNo)
  const counts = new Map<number, number>()
  questions.forEach(item => counts.set(item.questionNo, (counts.get(item.questionNo) ?? 0) + 1))
  questions.forEach((item) => {
    if (!Number.isInteger(item.questionNo) || item.questionNo < 1) issues.push({ questionNo: item.questionNo, message: '题号必须是大于 0 的整数' })
    if ((counts.get(item.questionNo) ?? 0) > 1) issues.push({ questionNo: item.questionNo, message: '题号重复' })
    if (!item.content.trim()) issues.push({ questionNo: item.questionNo, message: '题干不能为空' })
    if (item.questionType !== 'JUDGE' && item.options.filter(Boolean).length < 2) issues.push({ questionNo: item.questionNo, message: '至少需要两个选项' })
    const answerKeys = normalizeAnswer(item.answer)
    if (item.questionType === 'SINGLE_CHOICE' && answerKeys.length !== 1) issues.push({ questionNo: item.questionNo, message: '单选题必须填写一个答案' })
    if (item.questionType === 'MULTI_CHOICE' && answerKeys.length < 2) issues.push({ questionNo: item.questionNo, message: '多选题至少填写两个答案' })
    if (item.questionType === 'JUDGE' && !['A', 'B', 'TRUE', 'FALSE', '正确', '错误'].includes((item.answer ?? '').trim().toUpperCase())) issues.push({ questionNo: item.questionNo, message: '判断题答案请填写“正确/错误”或 A/B' })
    if (!Number.isFinite(Number(item.score)) || Number(item.score) <= 0) issues.push({ questionNo: item.questionNo, message: '分值必须大于 0' })
    if (!item.knowledgeTagIds.length) issues.push({ questionNo: item.questionNo, message: '至少选择一个知识点标签' })
  })
  if (form.paperKind === 'REAL_PAPER') {
    questions.forEach((item, index) => {
      if (item.questionNo !== index + 1) issues.push({ questionNo: item.questionNo, message: `真题题号应连续，当前位置应为第 ${index + 1} 题` })
    })
  }
  if (form.paperKind === 'REAL_PAPER' && questions.length !== 75) {
    issues.push({ questionNo: null, message: `历史真题必须完整识别 75 题，当前仅识别 ${questions.length} 题` })
  }
  if (form.totalScore > 0 && Math.abs(scoreTotal.value - form.totalScore) > 0.01) {
    issues.push({ questionNo: null, message: `题目分值合计 ${scoreTotal.value} 分，与试卷总分 ${form.totalScore} 分不一致` })
  }
  return issues
})
const canConfirm = computed(() => Boolean(
  preview.value
  && form.qualificationId
  && form.subjectId
  && form.paperName.trim()
  && verified.value
  && reviewIssues.value.length === 0,
))

watch(() => form.qualificationId, async (id) => {
  form.subjectId = ''
  subjects.value = []
  knowledgeTags.value = []
  chapters.value = []
  if (!id) return
  await qualificationStore.selectQualification(id)
  subjects.value = qualificationStore.subjects
})
watch(() => form.subjectId, () => { void loadTaxonomy() })
onMounted(async () => {
  if (!qualificationStore.qualifications.length) await qualificationStore.load()
  form.qualificationId = qualificationStore.qualifications[0]?.id ?? ''
})

function choose(event: Event): void {
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null
  preview.value = null
  message.value = ''
  verified.value = false
}

function openAsset(asset: CandidateAsset): void {
  selectedAsset.value = asset
  assetViewerOpen.value = true
}

async function parse(): Promise<void> {
  // 解析包含 OCR，耗时较长。按钮被点击后立即锁定，避免双击或重复事件再次发起请求。
  if (!file.value || parsing.value) return
  parsing.value = true
  message.value = ''
  try {
    const body = new FormData()
    body.append('file', file.value)
    const parsed = await unwrapApiResponse<Preview>(
      // Word/PDF files with scanned pages invoke local OCR and take longer
      // than ordinary API calls. Keep the longer wait limited to this action.
      platformApiClient.post('/admin/question-imports/document-preview', body, {
        timeout: 180_000,
      }),
    )
    parsed.questions = parsed.questions.map(item => ({
      ...item,
      questionType: item.questionType || 'SINGLE_CHOICE',
      options: [...(item.options ?? [])],
      imageFileNames: [...(item.imageFileNames ?? [])],
      warnings: [...(item.warnings ?? [])],
      difficulty: 'MEDIUM',
      knowledgeTagIds: [],
      chapterId: null,
      score: Number(item.score || 1),
    }))
    parsed.assets = parsed.assets.map(asset => ({ ...asset, assignedQuestionNo: asset.assignedQuestionNo ?? null }))
    preview.value = parsed
    if (form.paperKind !== 'REAL_PAPER') {
      form.totalScore = parsed.questions.reduce((sum, item) => sum + Number(item.score || 0), 0)
    }
    form.paperName ||= file.value.name.replace(/\.(docx?|pdf)$/i, '')
    selectedQuestionNo.value = parsed.questions[0]?.questionNo ?? null
  } catch (error) {
    message.value = resolveAdminQuestionError(error, '试卷文件解析失败，请确认文件未损坏')
  } finally {
    parsing.value = false
  }
}

async function loadTaxonomy(): Promise<void> {
  knowledgeTags.value = []
  chapters.value = []
  if (!form.qualificationId || !form.subjectId) return
  try {
    const [tagPage, chapterPage] = await Promise.all([
      unwrapApiResponse<Page<KnowledgeTag>>(platformApiClient.get('/foundation/knowledge-tags', {
        params: { qualificationId: form.qualificationId, subjectId: form.subjectId, pageNo: 1, pageSize: 500 },
      })),
      unwrapApiResponse<Page<Chapter>>(platformApiClient.get(`/foundation/subjects/${form.subjectId}/chapter-nodes`, {
        params: { pageNo: 1, pageSize: 500 },
      })),
    ])
    knowledgeTags.value = (tagPage.items ?? tagPage.records ?? []).filter(item => item.status === 'ENABLED')
    chapters.value = (chapterPage.items ?? chapterPage.records ?? []).filter(item => item.status === 'ENABLED')
  } catch {
    ElMessage.warning('知识点和章节加载失败，请稍后刷新')
  }
}

function normalizeAnswer(value: string | null): string[] {
  return [...new Set((value ?? '').toUpperCase().replace(/[^A-H]/g, '').split('').filter(Boolean))]
}

function stripImageMarker(content: string, fileName: string): string {
  return content.replaceAll(`[图片:${fileName}]`, '').replace(/\n{3,}/g, '\n\n').trim()
}

function assignAsset(asset: CandidateAsset, questionNo: number | null): void {
  if (!preview.value) return
  preview.value.questions.forEach(item => { item.content = stripImageMarker(item.content, asset.fileName) })
  asset.assignedQuestionNo = questionNo
  if (questionNo != null) {
    const target = preview.value.questions.find(item => item.questionNo === questionNo)
    if (target) {
      target.content = `${target.content.trim()}\n[图片:${asset.fileName}]`.trim()
      target.imageFileNames = [...new Set([...target.imageFileNames, asset.fileName])]
    }
  }
}

function onAssetQuestionChange(asset: CandidateAsset, value: unknown): void {
  assignAsset(asset, value == null || value === '' ? null : Number(value))
}

function applyKnowledgeTagsToVisible(): void {
  if (!bulkKnowledgeTagIds.value.length) return
  visibleQuestions.value.forEach((question) => {
    question.knowledgeTagIds = [...new Set([...question.knowledgeTagIds, ...bulkKnowledgeTagIds.value])]
  })
  ElMessage.success(`已为 ${visibleQuestions.value.length} 道题追加知识点标签`)
}

function addQuestion(copy?: Candidate): void {
  if (!preview.value) return
  const nextNo = Math.max(0, ...preview.value.questions.map(item => item.questionNo)) + 1
  const candidate: Candidate = copy
    ? {
        ...structuredClone(copy),
        questionNo: nextNo,
        content: copy.content.replace(/\[图片:[^\]]+]/g, '').trim(),
        imageFileNames: [],
      }
    : {
        questionNo: nextNo,
        questionType: 'SINGLE_CHOICE',
        content: '',
        options: ['A. ', 'B. ', 'C. ', 'D. '],
        answer: null,
        solution: null,
        score: 1,
        imageFileNames: [],
        warnings: [],
        difficulty: 'MEDIUM',
        knowledgeTagIds: [],
        chapterId: null,
      }
  preview.value.questions.push(candidate)
  selectedQuestionNo.value = nextNo
}

function removeQuestion(question: Candidate): void {
  if (!preview.value) return
  preview.value.assets.filter(asset => asset.assignedQuestionNo === question.questionNo)
    .forEach(asset => { asset.assignedQuestionNo = null })
  preview.value.questions = preview.value.questions.filter(item => item !== question)
}

function addOption(question: Candidate): void {
  const key = String.fromCharCode(65 + question.options.length)
  if (key <= 'H') question.options.push(`${key}. `)
}

function removeOption(question: Candidate, index: number): void {
  question.options.splice(index, 1)
  question.options = question.options.map((value, optionIndex) =>
    `${String.fromCharCode(65 + optionIndex)}. ${value.replace(/^[A-H][.、．)）]\s*/, '')}`)
}

function optionText(value: string): string {
  return value.replace(/^[A-H][.、．)）]\s*/, '')
}

function knowledgeBindings(item: Candidate) {
  const bindings: Array<{ knowledgeType: string; knowledgeCode: string; weight: number; sortOrder: number }> = []
  item.knowledgeTagIds.forEach((id) => {
    const tag = knowledgeTags.value.find(value => value.id === id)
    if (tag) bindings.push({ knowledgeType: 'TAG', knowledgeCode: tag.tagCode, weight: 1, sortOrder: bindings.length + 1 })
  })
  if (item.chapterId != null) {
    const chapter = chapters.value.find(value => value.id === item.chapterId)
    if (chapter) bindings.push({ knowledgeType: 'CHAPTER_NODE', knowledgeCode: chapter.chapterCode, weight: 1, sortOrder: bindings.length + 1 })
  }
  return bindings
}

function buildQuestion(item: Candidate) {
  const type = item.questionType
  const options = type === 'JUDGE' ? [] : item.options.map((value, index) => ({
    optionKey: String.fromCharCode(65 + index),
    content: optionText(value),
    sortOrder: index + 1,
  }))
  const answerKeys = normalizeAnswer(item.answer)
  const judgeValue = ['A', 'TRUE', '正确'].includes((item.answer ?? '').trim().toUpperCase())
  const bindings = knowledgeBindings(item)
  return {
    externalKey: `${preview.value?.fileSha256.slice(0, 16)}-${item.questionNo}`,
    questionType: type,
    title: `第 ${item.questionNo} 题`,
    yearNo: form.yearNo,
    difficulty: item.difficulty,
    source: {
      sourceType: 'AUTHORIZED_IMPORT',
      sourceRef: form.sourceName || preview.value?.fileName,
      syllabusBasis: `${form.syllabusBasis}；${form.semester === 'FIRST_HALF' ? '上半年' : '下半年'}`,
      copyrightStatus: 'AUTHORIZED',
      usageRestriction: 'INTERNAL_ONLY',
    },
    stems: [{
      stemKey: 'STEM_1',
      stemType: 'MAIN_STEM',
      content: item.content.trim(),
      attachmentUrl: null,
      resourceId: null,
      sortOrder: 1,
    }],
    subQuestions: [{
      subQuestionNo: 1,
      subQuestionType: type,
      content: item.content.trim(),
      score: Number(item.score),
      answerFormat: type === 'JUDGE' ? 'BOOLEAN' : 'OPTION_KEYS',
      gradingMode: 'AUTO',
      sortOrder: 1,
      options,
      answers: [{
        answerKind: type === 'JUDGE' ? 'BOOLEAN' : 'OPTION_KEYS',
        optionKeys: type === 'JUDGE' ? null : answerKeys,
        booleanValue: type === 'JUDGE' ? judgeValue : null,
        textValue: null,
        numberValue: null,
        structuredValue: null,
        sortOrder: 1,
      }],
      solutions: [{
        solutionType: item.solution?.trim() ? 'ANALYSIS' : 'REFERENCE_ANSWER',
        content: item.solution?.trim() || `正确答案：${item.answer}`,
        sortOrder: 1,
      }],
      scoringRules: [{
        ruleType: 'AUTO',
        ruleJson: {
          mode: type === 'MULTI_CHOICE' ? 'SET_EQUAL' : 'EXACT',
          tolerance: null,
          caseSensitive: null,
        },
        sortOrder: 1,
      }],
      scoringPoints: [],
      knowledgeBindings: [],
    }],
    knowledgeBindings: bindings,
  }
}

async function confirmImport(): Promise<void> {
  if (!preview.value || !canConfirm.value) {
    message.value = reviewIssues.value[0]?.message ?? '请先完成试卷信息和题目核验'
    return
  }
  const qualification = qualificationStore.qualifications.find(item => item.id === form.qualificationId)
  const subject = subjects.value.find(item => item.id === form.subjectId)
  if (!qualification || !subject) {
    message.value = '资格或科目数据已失效，请重新选择'
    return
  }
  const questions = [...preview.value.questions]
    .sort((left, right) => left.questionNo - right.questionNo)
    .map(buildQuestion)
  const data = {
    schemaVersion: 'question-import/1.0',
    qualificationCode: qualification.code,
    subjectCode: subject.code,
    paperKind: form.paperKind,
    paperName: form.paperName.trim(),
    yearNo: form.yearNo,
    totalScore: form.totalScore,
    durationMinutes: form.durationMinutes,
    sourceFileName: preview.value.fileName,
    sourceFileSha256: preview.value.fileSha256,
    questions,
  }
  submitting.value = true
  message.value = ''
  try {
    const generated = new File([JSON.stringify(data)], `${form.paperName || '试卷'}.json`, { type: 'application/json' })
    const preflight = await gateway.preflightImport({
      schemaVersion: 'question-import/1.0',
      clientMutationId: crypto.randomUUID(),
      file: generated,
    })
    if (preflight.status !== 'READY_TO_CONFIRM') {
      message.value = '预检未通过，请根据错误提示修正题目后重试'
      return
    }
    const result = await gateway.confirmImport({
      token: preflight.token,
      baseVersion: null,
      clientMutationId: crypto.randomUUID(),
    })
    ElMessage.success('试卷和题目已创建为待审核草稿')
    emit('imported', { batchId: result.batchId, paperId: result.paperId })
  } catch (error) {
    message.value = resolveAdminQuestionError(error, '试卷导入失败，请检查填写信息和 Word 内容')
  } finally {
    submitting.value = false
  }
}

function scrollToQuestion(questionNo: number | null): void {
  if (questionNo == null) return
  document.getElementById(`paper-question-${questionNo}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
</script>

<template>
  <section class="import-panel">
    <div class="panel-heading">
      <div class="heading-title">
        <span class="heading-icon"><el-icon><Document /></el-icon></span>
        <div>
          <p>试卷管理 · 导入工作流</p>
          <h2>导入试卷</h2>
          <span>解析、核对、确认后生成待审核试卷，不会直接发布。</span>
        </div>
      </div>
      <el-button class="close-button" circle aria-label="关闭导入弹窗" @click="emit('close')"><el-icon><Close /></el-icon></el-button>
    </div>

    <section class="step-card upload-card">
      <div class="step-title"><div><small>第 1 步</small><strong>选择并解析试卷</strong></div><span>支持 Word（.doc、.docx）和 PDF，扫描型 PDF 将自动调用 OCR。</span></div>
      <input ref="fileInput" class="native-file-input" type="file" :disabled="parsing" accept=".doc,.docx,.pdf,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/pdf" @change="choose">
      <button class="upload-dropzone" type="button" :disabled="parsing" @click="fileInput?.click()">
        <span class="upload-icon"><el-icon><UploadFilled /></el-icon></span>
        <span class="upload-copy">
          <strong>{{ file ? file.name : '选择本地试卷文件' }}</strong>
          <small>{{ file ? '文件已就绪，可开始解析' : '点击选择 Word 或 PDF 文件' }}</small>
        </span>
        <span class="select-file-action">{{ file ? '重新选择' : '选择文件' }}</span>
      </button>
      <div class="upload-actions">
        <span>建议使用可复制文字的规范试卷，解析结果更准确。</span>
        <el-button type="primary" :disabled="!file || parsing" :loading="parsing" @click="parse">开始解析</el-button>
      </div>
      <el-alert v-if="message && !preview" :title="message" type="error" :closable="false" show-icon />
    </section>

    <template v-if="preview">
      <section class="diagnostics">
        <div><span>识别题目</span><strong>{{ preview.diagnostics.detectedQuestionCount }}</strong></div>
        <div><span>内嵌图片</span><strong>{{ preview.diagnostics.imageCount }}</strong></div>
        <div><span>识别答案</span><strong>{{ preview.diagnostics.answerCount }}</strong></div>
        <div><span>已分配图片</span><strong>{{ assignedCount }}/{{ preview.assets.length }}</strong></div>
        <div><span>待解决问题</span><strong :class="{ danger: reviewIssues.length }">{{ reviewIssues.length }}</strong></div>
      </section>
      <el-alert
        v-for="warning in preview.warnings"
        :key="warning"
        :title="warning"
        type="warning"
        :closable="false"
        show-icon
      />

      <section class="step-card meta-grid">
        <div class="step-title full"><strong>2. 填写试卷信息</strong><span>所有名称均使用管理人员可读的中文</span></div>
        <el-form-item label="考试资格"><el-select v-model="form.qualificationId"><el-option v-for="item in qualificationStore.qualifications" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="考试科目"><el-select v-model="form.subjectId"><el-option v-for="item in subjects" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="试卷名称"><el-input v-model="form.paperName" /></el-form-item>
        <el-form-item label="试卷性质"><el-select v-model="form.paperKind"><el-option label="历史真题（可用于机考）" value="REAL_PAPER" /><el-option label="模拟题（仅用于练习）" value="MOCK_PAPER" /></el-select></el-form-item>
        <el-form-item label="考试年份"><el-input-number v-model="form.yearNo" :min="2000" :max="2100" /></el-form-item>
        <el-form-item label="考试批次"><el-select v-model="form.semester"><el-option label="上半年" value="FIRST_HALF" /><el-option label="下半年" value="SECOND_HALF" /></el-select></el-form-item>
        <el-form-item label="试卷总分"><el-input-number v-model="form.totalScore" :min="1" /></el-form-item>
        <el-form-item label="考试时长（分钟）"><el-input-number v-model="form.durationMinutes" :min="1" /></el-form-item>
        <el-form-item label="官方来源"><el-input v-model="form.sourceName" placeholder="如：中国计算机技术职业资格网" /></el-form-item>
        <el-form-item label="教材或大纲依据"><el-input v-model="form.syllabusBasis" /></el-form-item>
      </section>

      <section class="step-card">
        <div class="step-title">
        <div><strong>3. 查看来源页面与 OCR 资料</strong><span>Word 图片和扫描 PDF 页面仅用于后台核对，不会直接展示给刷题用户；无需逐张分配到题目。</span></div>
        </div>
        <div class="asset-grid">
          <article v-for="asset in preview.assets" :key="asset.assetToken" class="asset-card" role="button" tabindex="0" @click="openAsset(asset)" @keydown.enter="openAsset(asset)">
            <img class="asset-thumbnail" :src="asset.previewUrl" :alt="`来源图片 ${asset.sourceOrder}`">
            <div><span>图片 {{ asset.sourceOrder }}</span><small>{{ asset.width ?? '?' }} × {{ asset.height ?? '?' }}</small></div>
            <small>来源图片 {{ asset.sourceOrder }}，可放大后与 OCR 结果对照</small>
          </article>
        </div>
      </section>

      <section class="step-card review-workspace">
        <div class="review-toolbar">
          <div><strong>4. 逐题核对并修改</strong><span>题干、图片、选项、答案、解析、分值和知识点都会写入题库。</span></div>
          <div>
            <el-switch v-model="onlyProblems" active-text="只看有问题的题目" />
            <el-select v-model="bulkKnowledgeTagIds" multiple filterable collapse-tags placeholder="批量追加知识点" class="bulk-tag-select">
              <el-option v-for="tag in knowledgeTags" :key="tag.id" :label="tag.tagName" :value="tag.id" />
            </el-select>
            <el-button :disabled="!bulkKnowledgeTagIds.length" @click="applyKnowledgeTagsToVisible">应用到当前题目</el-button>
            <el-select v-model="selectedQuestionNo" placeholder="按题号跳转" class="jump-select" @change="scrollToQuestion">
              <el-option v-for="item in preview.questions" :key="item.questionNo" :label="`第 ${item.questionNo} 题`" :value="item.questionNo" />
            </el-select>
            <el-button @click="addQuestion()">新增题目</el-button>
          </div>
        </div>

        <article v-for="question in visibleQuestions" :id="`paper-question-${question.questionNo}`" :key="question.questionNo" class="question-card">
          <header>
            <div class="question-meta">
              <h3>第 {{ question.questionNo }} 题</h3>
              <el-form-item label="题号"><el-input-number v-model="question.questionNo" :min="1" /></el-form-item>
              <el-form-item label="题型"><el-select v-model="question.questionType"><el-option label="单选题" value="SINGLE_CHOICE" /><el-option label="多选题" value="MULTI_CHOICE" /><el-option label="判断题" value="JUDGE" /></el-select></el-form-item>
              <el-form-item label="分值"><el-input-number v-model="question.score" :min="0.1" :step="0.5" /></el-form-item>
              <el-form-item label="难度"><el-select v-model="question.difficulty"><el-option label="简单" value="EASY" /><el-option label="中等" value="MEDIUM" /><el-option label="困难" value="HARD" /></el-select></el-form-item>
            </div>
            <div><el-button link @click="addQuestion(question)">复制</el-button><el-button link type="danger" @click="removeQuestion(question)">删除</el-button></div>
          </header>
          <el-alert v-for="issue in reviewIssues.filter(value => value.questionNo === question.questionNo)" :key="issue.message" :title="issue.message" type="error" :closable="false" />
          <el-form-item label="题干与图片标记"><el-input v-model="question.content" type="textarea" :rows="5" /></el-form-item>
          <div class="question-preview"><span>用户端预览</span><QuestionRichContent :content="question.content" /></div>
          <template v-if="question.questionType !== 'JUDGE'">
            <div class="option-list">
              <div v-for="(_, index) in question.options" :key="index">
                <strong>{{ String.fromCharCode(65 + index) }}</strong>
                <el-input v-model="question.options[index]" />
                <el-button link type="danger" @click="removeOption(question, index)">删除</el-button>
              </div>
              <el-button :disabled="question.options.length >= 8" @click="addOption(question)">新增选项</el-button>
            </div>
          </template>
          <div class="question-fields">
            <el-form-item label="正确答案"><el-input v-model="question.answer" :placeholder="question.questionType === 'MULTI_CHOICE' ? '如 AC' : question.questionType === 'JUDGE' ? '正确或错误' : '如 A'" /></el-form-item>
            <el-form-item label="知识点标签（必填）"><el-select v-model="question.knowledgeTagIds" multiple filterable><el-option v-for="tag in knowledgeTags" :key="tag.id" :label="tag.tagName" :value="tag.id" /></el-select></el-form-item>
            <el-form-item label="章节归属（可选）"><el-select v-model="question.chapterId" clearable filterable><el-option v-for="chapter in chapters" :key="chapter.id" :label="`${chapter.chapterCode} ${chapter.chapterName}`" :value="chapter.id" /></el-select></el-form-item>
          </div>
          <el-form-item label="答案解析"><el-input v-model="question.solution" type="textarea" :rows="3" placeholder="填写该题的答案依据和解析" /></el-form-item>
        </article>
        <el-empty v-if="!visibleQuestions.length" description="当前没有需要显示的题目" />
      </section>

      <section class="step-card final-check">
        <div>
          <strong>5. 完整性检查</strong>
          <span>当前共 {{ preview.questions.length }} 题，分值合计 {{ scoreTotal }} 分。</span>
        </div>
        <el-alert v-for="(issue, index) in reviewIssues.slice(0, 20)" :key="`${issue.questionNo}-${index}`" :title="`${issue.questionNo == null ? '整卷' : `第 ${issue.questionNo} 题`}：${issue.message}`" type="error" :closable="false" />
        <el-alert v-if="reviewIssues.length > 20" :title="`另有 ${reviewIssues.length - 20} 项问题，请使用“只看有问题的题目”逐项处理`" type="warning" :closable="false" />
        <el-checkbox v-model="verified">我已对照原卷核对题号、答案、图片、分值和知识点</el-checkbox>
        <el-alert v-if="message" :title="message" type="error" :closable="false" show-icon />
        <el-button type="primary" size="large" :disabled="!canConfirm" :loading="submitting" @click="confirmImport">确认并创建待审核试卷</el-button>
      </section>
    </template>

    <el-dialog v-model="assetViewerOpen" :title="selectedAsset ? `来源图片 ${selectedAsset.sourceOrder}` : '来源图片'" width="min(960px, 92vw)" destroy-on-close>
      <template v-if="selectedAsset">
        <div class="asset-viewer-meta">图片 {{ selectedAsset.sourceOrder }} · {{ selectedAsset.width ?? '?' }} × {{ selectedAsset.height ?? '?' }}</div>
        <img class="asset-viewer-image" :src="selectedAsset.previewUrl" :alt="`来源图片 ${selectedAsset.sourceOrder}`">
        <a class="asset-original-link" :href="selectedAsset.previewUrl" target="_blank" rel="noopener">在新窗口打开原图</a>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.import-panel{display:grid;max-height:min(900px,92vh);overflow-y:auto;overscroll-behavior:contain;color:#172033;background:#f5f7fb}
.panel-heading,.heading-title,.step-title,.upload-actions,.review-toolbar,.question-card header,.question-meta,.asset-card>div,.final-check>div{display:flex;align-items:center;gap:12px}
.panel-heading{position:sticky;z-index:12;top:0;justify-content:space-between;min-height:82px;padding:0 24px;border-bottom:1px solid #e7ebf1;background:rgba(255,255,255,.96);backdrop-filter:blur(12px)}
.heading-title{min-width:0}.heading-title>div{display:grid;min-width:0;gap:2px}.heading-icon{display:grid;width:42px;height:42px;flex:0 0 auto;place-items:center;border-radius:12px;color:#2563eb;background:#eaf1ff;font-size:20px}.panel-heading p,.panel-heading span,.step-title span,.review-toolbar span,.asset-card small,.final-check span{margin:0;color:#76859b}.panel-heading p{font-size:11px}.panel-heading h2{margin:0;color:#26334a;font-size:20px}.panel-heading .heading-title>div>span{font-size:12px}.close-button{flex:0 0 auto}
.step-card,.diagnostics,.import-panel>.el-alert{margin:16px 22px 0}.step-card{display:grid;gap:16px;padding:20px;border:1px solid #dfe6f0;border-radius:14px;background:#fff;box-shadow:0 8px 24px rgba(31,45,75,.035)}
.step-title{justify-content:space-between}.step-title>div{display:grid;gap:3px}.step-title small{color:#2563eb;font-size:10px;font-weight:700}.step-title strong{color:#2a374d;font-size:15px}.step-title span{font-size:11px}
.native-file-input{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0,0,0,0)}
.upload-dropzone{display:flex;width:100%;min-height:100px;align-items:center;gap:16px;padding:18px;border:1px dashed #b9c9e4;border-radius:13px;color:inherit;background:#f8fbff;cursor:pointer;text-align:left;transition:.18s ease}.upload-dropzone:hover,.upload-dropzone:focus-visible{border-color:#2563eb;background:#f2f7ff;box-shadow:0 0 0 3px rgba(37,99,235,.08);outline:0}.upload-dropzone:disabled{cursor:not-allowed;opacity:.65}.upload-icon{display:grid;width:46px;height:46px;flex:0 0 auto;place-items:center;border-radius:12px;color:#2563eb;background:#e7efff;font-size:22px}.upload-copy{display:grid;min-width:0;flex:1;gap:5px}.upload-copy strong{overflow:hidden;color:#2a374d;font-size:14px;text-overflow:ellipsis;white-space:nowrap}.upload-copy small{color:#8a97aa;font-size:11px}.select-file-action{flex:0 0 auto;padding:8px 12px;border:1px solid #d5dfef;border-radius:8px;color:#2563eb;background:#fff;font-size:12px;font-weight:650}.upload-actions{justify-content:space-between}.upload-actions>span{color:#929dae;font-size:11px}
.diagnostics{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:10px}.diagnostics div{display:grid;gap:4px;padding:14px 16px;border:1px solid #dfe6f0;border-radius:12px;background:#fff}.diagnostics span{color:#8290a4;font-size:10px}.diagnostics strong{color:#2f3e57;font-size:22px}.diagnostics .danger{color:#dc2626}
.meta-grid{grid-template-columns:repeat(4,minmax(160px,1fr))}.meta-grid .full{grid-column:1/-1}.meta-grid :deep(.el-form-item){margin:0}.meta-grid :deep(.el-form-item__label){color:#657288;font-size:11px}.meta-grid :deep(.el-select),.meta-grid :deep(.el-input-number){width:100%}
.asset-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(190px,1fr));gap:12px}.asset-card{display:grid;gap:9px;padding:10px;border:1px solid #e1e7f0;border-radius:11px;cursor:zoom-in;transition:.18s ease}.asset-card:hover{border-color:#8db2f7;box-shadow:0 8px 20px rgba(31,45,75,.07)}.asset-thumbnail{width:100%;height:140px;object-fit:contain;border-radius:7px;background:#f5f7fa}.asset-card>div{justify-content:space-between}.asset-viewer-meta{margin-bottom:12px;color:#76859b}.asset-viewer-image{display:block;width:100%;max-height:70vh;object-fit:contain;background:#f5f7fa}.asset-original-link{display:inline-block;margin-top:12px;color:#2563eb}
.review-workspace{padding:0 0 20px;overflow:hidden}.review-toolbar{position:sticky;z-index:8;top:82px;justify-content:space-between;padding:16px 18px;border-bottom:1px solid #e8edf4;background:rgba(255,255,255,.97);backdrop-filter:blur(10px)}.review-toolbar>div{display:flex;align-items:center;gap:10px}.review-toolbar>div:first-child{display:grid;gap:3px}.review-toolbar strong{font-size:15px}.review-toolbar span{font-size:10px}.jump-select{width:140px}.bulk-tag-select{width:210px}.question-card{display:grid;gap:14px;margin:16px 18px 0;padding:18px;border:1px solid #dfe6f0;border-radius:13px;background:#fff;scroll-margin-top:160px}.question-card header{justify-content:space-between}.question-card h3{width:100%;margin:0;color:#263b67;font-size:17px}.question-meta{flex-wrap:wrap}.question-meta :deep(.el-form-item){width:142px;margin:0}.question-meta :deep(.el-form-item__label){font-size:11px}.question-meta :deep(.el-select),.question-meta :deep(.el-input-number){width:100%}
.question-preview{display:grid;gap:8px;padding:13px;border:1px solid #e2e9f4;border-radius:9px;background:#f7f9fc}.question-preview>span{color:#8290a4;font-size:10px}
.option-list{display:grid;gap:8px}.option-list>div{display:grid;grid-template-columns:28px 1fr auto;align-items:center;gap:8px}.question-fields{display:grid;grid-template-columns:180px 1fr 1fr;gap:12px}.question-fields :deep(.el-form-item){margin:0}.question-fields :deep(.el-select){width:100%}
.final-check{margin-bottom:22px;border-color:#cbd9ee;background:#fbfdff}.final-check>div{justify-content:space-between}.final-check>.el-button{min-height:42px;justify-self:start;border-radius:9px}
@media(max-width:1100px){.diagnostics{grid-template-columns:repeat(3,1fr)}.meta-grid{grid-template-columns:repeat(2,1fr)}.question-fields{grid-template-columns:1fr 1fr}.review-toolbar{align-items:flex-start;flex-direction:column}.review-toolbar>div:last-child{width:100%;flex-wrap:wrap}}
@media(max-width:700px){.import-panel{max-height:100vh}.panel-heading{min-height:72px;padding:0 15px}.heading-title>div>span{display:none}.step-card,.diagnostics,.import-panel>.el-alert{margin-right:12px;margin-left:12px}.step-card{padding:15px}.step-title,.upload-actions,.question-card header{align-items:flex-start;flex-direction:column}.upload-dropzone{align-items:flex-start}.select-file-action{display:none}.diagnostics,.meta-grid,.question-fields{grid-template-columns:1fr}.meta-grid .full{grid-column:auto}.review-toolbar{top:72px}.review-toolbar>div:last-child{align-items:stretch;flex-direction:column}.jump-select,.bulk-tag-select{width:100%}.question-card{margin:12px;padding:14px}.question-meta :deep(.el-form-item){width:100%}}
</style>
