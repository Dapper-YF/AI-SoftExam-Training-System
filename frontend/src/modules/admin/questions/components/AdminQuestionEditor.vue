<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { platformApiClient, unwrapApiResponse } from '@/api/client'
import {
  ADMIN_COPYRIGHT_STATUSES,
  ADMIN_DIFFICULTIES,
  ADMIN_QUESTION_TYPES,
  ADMIN_SOURCE_TYPES,
  ADMIN_SOURCE_TYPE_LABELS,
} from '../types'
import {
  blankCaseSubQuestion,
  blankScoringPoint,
  type AdminQuestionFormModel,
  type ScoringPointForm,
} from '../model/question-form'

const props = withDefaults(defineProps<{
  readonly?: boolean
  lockIdentity?: boolean
}>(), {
  readonly: false,
  lockIdentity: false,
})

const form = defineModel<AdminQuestionFormModel>({ required: true })
interface KnowledgeTag { id: number; tagCode: string; tagName: string; tagType: string; status: string }
interface KnowledgeTagPage { items?: KnowledgeTag[]; records?: KnowledgeTag[] }
interface FoundationOption { id: number; name: string }
interface FoundationPage { items?: FoundationOption[]; records?: FoundationOption[] }
const knowledgeTags = ref<KnowledgeTag[]>([])
const qualifications = ref<FoundationOption[]>([])
const subjects = ref<FoundationOption[]>([])
const selectedTagIds = computed<number[]>({
  get: () => form.value.tagIds.split(/[,，\s]+/).map(Number).filter((id) => Number.isSafeInteger(id) && id > 0),
  set: (ids) => { form.value.tagIds = ids.join(',') },
})
async function loadKnowledgeTags(): Promise<void> {
  knowledgeTags.value = []
  if (!form.value.qualificationId || !form.value.subjectId) return
  try {
    const page = await unwrapApiResponse<KnowledgeTagPage>(platformApiClient.get('/foundation/knowledge-tags', {
      params: { qualificationId: form.value.qualificationId, subjectId: form.value.subjectId, pageNo: 1, pageSize: 500 },
    }))
    knowledgeTags.value = page.items ?? page.records ?? []
  } catch { knowledgeTags.value = [] }
}
async function loadQualifications(): Promise<void> {
  const page = await unwrapApiResponse<FoundationPage>(platformApiClient.get('/foundation/qualifications', {
    params: { pageNo: 1, pageSize: 100 },
  }))
  qualifications.value = page.items ?? page.records ?? []
}
async function loadSubjects(qualificationId: number | null | undefined): Promise<void> {
  subjects.value = []
  if (!qualificationId) return
  const page = await unwrapApiResponse<FoundationPage>(platformApiClient.get(
    `/foundation/qualifications/${qualificationId}/subjects`,
    { params: { pageNo: 1, pageSize: 100 } },
  ))
  subjects.value = page.items ?? page.records ?? []
}
void loadQualifications()
watch(() => [form.value.qualificationId, form.value.subjectId], () => { void loadKnowledgeTags() }, { immediate: true })
watch(() => form.value.qualificationId, async value => {
  await loadSubjects(value)
  if (!subjects.value.some(item => item.id === form.value.subjectId) && !props.lockIdentity) form.value.subjectId = 0
}, { immediate: true })

const typeLabels = {
  SINGLE_CHOICE: '单选题',
  MULTI_CHOICE: '多选题',
  JUDGE: '判断题',
  CASE: '案例分析题',
  ESSAY: '论文题',
} as const
const difficultyLabels = { EASY: '简单', MEDIUM: '中等', HARD: '困难' } as const
const copyrightLabels = {
  UNVERIFIED: '待核验',
  ORIGINAL_DECLARED: '原创声明',
  AUTHORIZED: '已获授权',
} as const

const caseTotalScore = computed(() => form.value.caseSubQuestions.reduce((sum, item) => sum + Number(item.score || 0), 0))
const essayDimensionScore = computed(() => form.value.essayScoringDimensions.reduce((sum, item) => sum + Number(item.score || 0), 0))

function move<T>(items: T[], index: number, offset: -1 | 1): void {
  const target = index + offset
  if (target < 0 || target >= items.length) return
  const current = items[index]
  const replacement = items[target]
  if (current === undefined || replacement === undefined) return
  items[index] = replacement
  items[target] = current
}

function addOption(): void {
  if (form.value.options.length >= 26) return
  form.value.options.push({ content: '', correct: false })
}

function removeOption(index: number): void {
  if (form.value.options.length <= 2) return
  form.value.options.splice(index, 1)
  if (form.value.questionType === 'SINGLE_CHOICE' && !form.value.options.some((item) => item.correct)) {
    const first = form.value.options[0]
    if (first) first.correct = true
  }
}

function selectCorrectOption(index: number, checked: boolean): void {
  if (form.value.questionType !== 'SINGLE_CHOICE' || !checked) return
  form.value.options.forEach((item, itemIndex) => { item.correct = itemIndex === index })
}

function addCaseSubQuestion(): void {
  if (form.value.caseSubQuestions.length >= 4) return
  form.value.caseSubQuestions.push(blankCaseSubQuestion(form.value.caseSubQuestions.length))
}

function removeCaseSubQuestion(index: number): void {
  if (form.value.caseSubQuestions.length <= 2) return
  form.value.caseSubQuestions.splice(index, 1)
}

function addPoint(points: ScoringPointForm[], prefix: string): void {
  const next = blankScoringPoint(points.length)
  next.code = `${prefix}${points.length + 1}`
  points.push(next)
}

function removePoint(points: ScoringPointForm[], index: number): void {
  if (points.length <= 1) return
  points.splice(index, 1)
}

function addStructure(): void {
  form.value.essayRequiredStructures.push('')
}

function removeStructure(index: number): void {
  if (form.value.essayRequiredStructures.length <= 1) return
  form.value.essayRequiredStructures.splice(index, 1)
}
</script>

<template>
  <div class="question-editor" data-testid="question-editor">
    <section class="editor-section">
      <h2>基础信息</h2>
      <div class="editor-grid editor-grid--four">
        <label>
          <span>考试资格</span>
          <select v-model.number="form.qualificationId" :disabled="readonly || lockIdentity" data-testid="question-qualification">
            <option disabled :value="0">请选择考试资格</option>
            <option v-for="item in qualifications" :key="item.id" :value="item.id">{{ item.name }}</option>
          </select>
        </label>
        <label>
          <span>考试科目</span>
          <select v-model.number="form.subjectId" :disabled="readonly || lockIdentity || !form.qualificationId" data-testid="question-subject">
            <option disabled :value="0">请选择考试科目</option>
            <option v-for="item in subjects" :key="item.id" :value="item.id">{{ item.name }}</option>
          </select>
        </label>
        <label>
          <span>题型</span>
          <select v-model="form.questionType" :disabled="readonly || lockIdentity" data-testid="question-type">
            <option v-for="type in ADMIN_QUESTION_TYPES" :key="type" :value="type">{{ typeLabels[type] }}</option>
          </select>
        </label>
        <label>
          <span>难度</span>
          <select v-model="form.difficulty" :disabled="readonly" data-testid="question-difficulty">
            <option v-for="difficulty in ADMIN_DIFFICULTIES" :key="difficulty" :value="difficulty">{{ difficultyLabels[difficulty] }}</option>
          </select>
        </label>
        <label class="editor-grid__wide">
          <span>题目标题</span>
          <input v-model="form.title" :disabled="readonly" maxlength="180" data-testid="question-title" />
        </label>
        <label>
          <span>年份</span>
          <input v-model.number="form.yearNo" type="number" min="2000" max="2100" :disabled="readonly" data-testid="question-year" />
        </label>
        <label>
          <span>来源类型</span>
          <select v-model="form.sourceType" :disabled="readonly" data-testid="question-source-type">
            <option v-for="source in ADMIN_SOURCE_TYPES" :key="source" :value="source">{{ ADMIN_SOURCE_TYPE_LABELS[source] }}</option>
          </select>
        </label>
        <label class="editor-grid__wide">
          <span>来源说明</span>
          <input v-model="form.sourceRef" :disabled="readonly" maxlength="255" data-testid="question-source-ref" />
        </label>
        <label>
          <span>权利状态</span>
          <select v-model="form.copyrightStatus" :disabled="readonly" data-testid="question-copyright-status">
            <option v-for="status in ADMIN_COPYRIGHT_STATUSES" :key="status" :value="status">{{ copyrightLabels[status] }}</option>
          </select>
        </label>
        <label>
          <span>可见范围</span>
          <input v-model="form.usageRestriction" :disabled="readonly" maxlength="255" data-testid="question-usage-restriction" />
        </label>
        <label class="editor-grid__wide">
          <span>知识点 ID（逗号分隔）</span>
          <input v-model="form.knowledgeIds" :disabled="readonly" data-testid="question-knowledge-ids" />
        </label>
        <label class="editor-grid__wide">
          <span>标签 ID（逗号分隔）</span>
          <el-select v-model="selectedTagIds" multiple filterable collapse-tags :disabled="readonly" data-testid="question-tag-ids" placeholder="先选择资格和科目">
            <el-option v-for="tag in knowledgeTags" :key="tag.id" :label="`${tag.tagCode} ${tag.tagName}`" :value="tag.id" />
          </el-select>
        </label>
      </div>
    </section>

    <section v-if="form.questionType === 'SINGLE_CHOICE' || form.questionType === 'MULTI_CHOICE'" class="editor-section" data-testid="choice-editor">
      <h2>{{ typeLabels[form.questionType] }}内容</h2>
      <label>
        <span>题干</span>
        <textarea v-model="form.stem" :disabled="readonly" rows="4" data-testid="objective-stem" />
      </label>
      <label>
        <span>作答提示</span>
        <input v-model="form.subQuestionPrompt" :disabled="readonly" placeholder="可留空，默认使用题干" data-testid="objective-prompt" />
      </label>
      <div class="option-list" data-testid="choice-options">
        <div v-for="(option, index) in form.options" :key="index" class="option-row">
          <strong>{{ String.fromCharCode(65 + index) }}</strong>
          <input v-model="option.content" :disabled="readonly" :data-testid="`option-content-${index}`" />
          <el-checkbox
            v-model="option.correct"
            :disabled="readonly"
            :data-testid="`option-correct-${index}`"
            @change="selectCorrectOption(index, Boolean($event))"
          >正确答案</el-checkbox>
          <el-button :disabled="readonly || index === 0" size="small" @click="move(form.options, index, -1)">上移</el-button>
          <el-button :disabled="readonly || index === form.options.length - 1" size="small" @click="move(form.options, index, 1)">下移</el-button>
          <el-button :disabled="readonly || form.options.length <= 2" size="small" type="danger" plain @click="removeOption(index)">删除</el-button>
        </div>
      </div>
      <el-button :disabled="readonly || form.options.length >= 26" data-testid="add-option" @click="addOption">添加选项</el-button>
      <label>
        <span>解析</span>
        <textarea v-model="form.analysis" :disabled="readonly" rows="3" data-testid="objective-analysis" />
      </label>
    </section>

    <section v-else-if="form.questionType === 'JUDGE'" class="editor-section" data-testid="judge-editor">
      <h2>判断题内容</h2>
      <label>
        <span>题干</span>
        <textarea v-model="form.stem" :disabled="readonly" rows="4" data-testid="judge-stem" />
      </label>
      <label>
        <span>作答提示</span>
        <input v-model="form.subQuestionPrompt" :disabled="readonly" placeholder="可留空，默认使用题干" />
      </label>
      <div class="inline-field">
        <span>正确答案</span>
        <el-radio-group v-model="form.judgeAnswer" :disabled="readonly" data-testid="judge-answer">
          <el-radio :value="true">正确</el-radio>
          <el-radio :value="false">错误</el-radio>
        </el-radio-group>
      </div>
      <label>
        <span>解析</span>
        <textarea v-model="form.analysis" :disabled="readonly" rows="3" data-testid="judge-analysis" />
      </label>
    </section>

    <section v-else-if="form.questionType === 'CASE'" class="editor-section" data-testid="case-editor">
      <div class="section-heading">
        <h2>案例材料与子题</h2>
        <span>总分 {{ caseTotalScore }}</span>
      </div>
      <label>
        <span>案例材料</span>
        <textarea v-model="form.caseMaterial" :disabled="readonly" rows="7" data-testid="case-material" />
      </label>
      <article v-for="(sub, subIndex) in form.caseSubQuestions" :key="sub.key" class="sub-editor" :data-testid="`case-sub-${subIndex}`">
        <div class="section-heading">
          <h3>子题 {{ subIndex + 1 }}</h3>
          <div>
            <el-button :disabled="readonly || subIndex === 0" size="small" @click="move(form.caseSubQuestions, subIndex, -1)">上移</el-button>
            <el-button :disabled="readonly || subIndex === form.caseSubQuestions.length - 1" size="small" @click="move(form.caseSubQuestions, subIndex, 1)">下移</el-button>
            <el-button :disabled="readonly || form.caseSubQuestions.length <= 2" size="small" type="danger" plain @click="removeCaseSubQuestion(subIndex)">删除</el-button>
          </div>
        </div>
        <label><span>子题题干</span><textarea v-model="sub.content" :disabled="readonly" rows="3" :data-testid="`case-content-${subIndex}`" /></label>
        <label class="short-field"><span>分值</span><input v-model.number="sub.score" :disabled="readonly" type="number" min="0.01" step="0.01" :data-testid="`case-score-${subIndex}`" /></label>
        <label><span>参考答案</span><textarea v-model="sub.referenceAnswer" :disabled="readonly" rows="3" :data-testid="`case-answer-${subIndex}`" /></label>
        <label><span>解析</span><textarea v-model="sub.analysis" :disabled="readonly" rows="2" /></label>
        <div class="point-list">
          <strong>评分点</strong>
          <div v-for="(point, pointIndex) in sub.scoringPoints" :key="pointIndex" class="point-row">
            <input v-model="point.code" :disabled="readonly" aria-label="评分点编码" />
            <input v-model="point.text" :disabled="readonly" aria-label="评分点内容" />
            <input v-model.number="point.score" :disabled="readonly" type="number" min="0.01" step="0.01" aria-label="评分点分值" />
            <el-button :disabled="readonly || pointIndex === 0" size="small" @click="move(sub.scoringPoints, pointIndex, -1)">上移</el-button>
            <el-button :disabled="readonly || pointIndex === sub.scoringPoints.length - 1" size="small" @click="move(sub.scoringPoints, pointIndex, 1)">下移</el-button>
            <el-button :disabled="readonly || sub.scoringPoints.length <= 1" size="small" type="danger" plain @click="removePoint(sub.scoringPoints, pointIndex)">删除</el-button>
          </div>
          <el-button :disabled="readonly" size="small" @click="addPoint(sub.scoringPoints, `Q${subIndex + 1}P`)">添加评分点</el-button>
        </div>
      </article>
      <el-button :disabled="readonly || form.caseSubQuestions.length >= 4" data-testid="add-case-sub" @click="addCaseSubQuestion">添加子题</el-button>
      <aside class="question-preview" data-testid="case-preview">
        <h3>完整预览</h3>
        <p>{{ form.caseMaterial || '尚未填写案例材料' }}</p>
        <ol>
          <li v-for="sub in form.caseSubQuestions" :key="sub.key">{{ sub.content || '尚未填写子题' }}（{{ sub.score }} 分）</li>
        </ol>
      </aside>
    </section>

    <section v-else class="editor-section" data-testid="essay-editor">
      <h2>论文题内容</h2>
      <label><span>背景材料</span><textarea v-model="form.essayBackground" :disabled="readonly" rows="5" data-testid="essay-background" /></label>
      <label><span>写作要求</span><textarea v-model="form.essayRequirement" :disabled="readonly" rows="5" data-testid="essay-requirement" /></label>
      <label><span>作答题目 / 提示</span><textarea v-model="form.essayPrompt" :disabled="readonly" rows="2" data-testid="essay-prompt" /></label>
      <div class="editor-grid editor-grid--four">
        <label><span>最少字数</span><input v-model.number="form.essayMinWords" :disabled="readonly" type="number" min="1" data-testid="essay-min-words" /></label>
        <label><span>最多字数</span><input v-model.number="form.essayMaxWords" :disabled="readonly" type="number" min="1" data-testid="essay-max-words" /></label>
        <label><span>总分</span><input v-model.number="form.essayScore" :disabled="readonly" type="number" min="0.01" step="0.01" data-testid="essay-score" /></label>
        <div class="score-closure"><span>维度合计</span><strong>{{ essayDimensionScore }}</strong></div>
      </div>
      <div class="point-list" data-testid="essay-structures">
        <strong>必答结构</strong>
        <div v-for="(_item, index) in form.essayRequiredStructures" :key="index" class="structure-row">
          <input v-model="form.essayRequiredStructures[index]" :disabled="readonly" :data-testid="`essay-structure-${index}`" />
          <el-button :disabled="readonly || index === 0" size="small" @click="move(form.essayRequiredStructures, index, -1)">上移</el-button>
          <el-button :disabled="readonly || index === form.essayRequiredStructures.length - 1" size="small" @click="move(form.essayRequiredStructures, index, 1)">下移</el-button>
          <el-button :disabled="readonly || form.essayRequiredStructures.length <= 1" size="small" type="danger" plain @click="removeStructure(index)">删除</el-button>
        </div>
        <el-button :disabled="readonly" size="small" @click="addStructure">添加结构</el-button>
      </div>
      <label><span>参考作答说明（非 AI 批改结果）</span><textarea v-model="form.essayReferenceAnswer" :disabled="readonly" rows="3" data-testid="essay-reference" /></label>
      <div class="point-list" data-testid="essay-dimensions">
        <strong>评分维度与规则</strong>
        <div v-for="(point, index) in form.essayScoringDimensions" :key="index" class="point-row">
          <input v-model="point.code" :disabled="readonly" aria-label="评分维度编码" />
          <input v-model="point.text" :disabled="readonly" aria-label="评分维度内容" />
          <input v-model.number="point.score" :disabled="readonly" type="number" min="0.01" step="0.01" aria-label="评分维度分值" />
          <el-button :disabled="readonly || index === 0" size="small" @click="move(form.essayScoringDimensions, index, -1)">上移</el-button>
          <el-button :disabled="readonly || index === form.essayScoringDimensions.length - 1" size="small" @click="move(form.essayScoringDimensions, index, 1)">下移</el-button>
          <el-button :disabled="readonly || form.essayScoringDimensions.length <= 1" size="small" type="danger" plain @click="removePoint(form.essayScoringDimensions, index)">删除</el-button>
        </div>
        <el-button :disabled="readonly" size="small" @click="addPoint(form.essayScoringDimensions, 'D')">添加评分维度</el-button>
      </div>
      <aside class="question-preview" data-testid="essay-preview">
        <h3>{{ form.title || '论文题预览' }}</h3>
        <p>{{ form.essayBackground }}</p>
        <p>{{ form.essayRequirement }}</p>
        <p>字数：{{ form.essayMinWords }}—{{ form.essayMaxWords }}；结构：{{ form.essayRequiredStructures.filter(Boolean).join('、') }}</p>
      </aside>
    </section>
  </div>
</template>

<style scoped>
.question-editor {
  display: grid;
  gap: 14px;
}

.editor-section {
  display: grid;
  gap: 12px;
  padding: 16px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-surface);
}

.editor-section h2,
.editor-section h3 {
  margin: 0;
  color: var(--color-brand);
}

.editor-section label {
  display: grid;
  gap: 5px;
  color: var(--color-text-secondary);
  font-size: 13px;
  font-weight: 700;
}

.editor-section input,
.editor-section select,
.editor-section textarea {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  padding: 9px 10px;
  background: var(--color-surface);
  color: var(--color-text);
  font: inherit;
}

.editor-grid {
  display: grid;
  gap: 10px;
}

.editor-grid--four {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.editor-grid__wide {
  grid-column: span 2;
}

.section-heading,
.inline-field {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.option-list,
.point-list,
.sub-editor {
  display: grid;
  gap: 9px;
}

.option-row {
  display: grid;
  grid-template-columns: 28px minmax(180px, 1fr) auto auto auto auto;
  align-items: center;
  gap: 8px;
}

.point-row {
  display: grid;
  grid-template-columns: 110px minmax(180px, 1fr) 100px auto auto auto;
  align-items: center;
  gap: 8px;
}

.structure-row {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) auto auto auto;
  align-items: center;
  gap: 8px;
}

.sub-editor {
  padding: 14px 0;
  border-top: 1px solid var(--color-border);
}

.short-field {
  max-width: 180px;
}

.question-preview {
  padding: 14px;
  border-left: 3px solid var(--color-primary);
  background: var(--color-page-canvas);
}

.question-preview p {
  white-space: pre-wrap;
}

.score-closure {
  display: grid;
  align-content: center;
  gap: 4px;
  color: var(--color-text-secondary);
  font-size: 13px;
}

@media (max-width: 1100px) {
  .editor-grid--four {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .option-row,
  .point-row {
    grid-template-columns: 28px minmax(180px, 1fr) auto;
  }
}
</style>
