<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  ArrowRight,
  Calendar,
  CircleCheck,
  DataAnalysis,
  MagicStick,
  Reading,
  RefreshRight,
} from '@element-plus/icons-vue'
import { platformApiClient, toSafeUserMessage, unwrapApiResponse } from '@/api/client'
import { useQualificationStore } from '@/stores/qualification'

const props = withDefaults(defineProps<{
  section?: 'all' | 'question' | 'paper'
  embedded?: boolean
}>(), {
  section: 'all',
  embedded: false,
})

type WeakPoint = { knowledgeType: string; knowledgeId: string; knowledgeName: string; mistakeCount: number }
type GeneratedQuestion = { id: string; difficulty: string; status: string; question: { stem: string; options: Array<{ key: string; content: string }>; answer: string; analysis: string } }
type SmartPaper = { id: string; paperName: string; questionIds: string[]; weakPoints: string[]; plan: Array<{ stage: string; startDate: string; endDate: string; focus: string }> }

const qualification = useQualificationStore()
const weakPoints = ref<WeakPoint[]>([])
const mode = ref<'KNOWLEDGE' | 'WEAKNESS' | 'ADAPTIVE'>('WEAKNESS')
const difficulty = ref<'EASY' | 'MEDIUM' | 'HARD'>('MEDIUM')
const selectedWeakPoint = ref<WeakPoint | null>(null)
const question = ref<GeneratedQuestion | null>(null)
const paper = ref<SmartPaper | null>(null)
const generating = ref(false)
const planning = ref(false)
const targetExamDate = ref('')
const weakPointStatus = ref<'idle' | 'loading' | 'success' | 'empty' | 'error'>('idle')
const weakPointError = ref('')

const qualificationId = computed(() => Number(qualification.selectedQualificationId))
const subjectId = computed(() => Number(qualification.selectedSubjectId))
const scopeReady = computed(() => Number.isFinite(qualificationId.value) && qualificationId.value > 0 && Number.isFinite(subjectId.value) && subjectId.value > 0)
const currentScopeLabel = computed(() => [
  qualification.selectedQualification?.name,
  qualification.selectedSubject?.name,
].filter(Boolean).join(' · ') || '当前备考方向')
const modes = [
  { value: 'WEAKNESS' as const, label: '薄弱点专项', description: '围绕近期错题集中训练' },
  { value: 'ADAPTIVE' as const, label: '自适应难度', description: '根据学习表现动态调整' },
  { value: 'KNOWLEDGE' as const, label: '知识点练习', description: '指定考点进行定向练习' },
]
const difficulties = [
  { value: 'EASY' as const, label: '基础', description: '巩固概念' },
  { value: 'MEDIUM' as const, label: '中等', description: '贴近常规考法' },
  { value: 'HARD' as const, label: '提高', description: '强化综合运用' },
]
const difficultyLabels: Record<string, string> = { EASY: '基础', MEDIUM: '中等', HARD: '提高' }
const generatedStatusLabels: Record<string, string> = {
  GENERATED: '已生成',
  PUBLISHED: '已发布',
  DRAFT: '草稿',
  FAILED: '生成失败',
}

async function loadWeakPoints(): Promise<void> {
  if (!scopeReady.value) return
  weakPointStatus.value = 'loading'
  weakPointError.value = ''
  try {
    weakPoints.value = await unwrapApiResponse<WeakPoint[]>(platformApiClient.get('/ai-question-generation/weak-points', { params: { qualificationId: qualificationId.value, subjectId: subjectId.value } }))
    selectedWeakPoint.value = weakPoints.value[0] ?? null
    weakPointStatus.value = weakPoints.value.length ? 'success' : 'empty'
  } catch (error) {
    weakPoints.value = []
    selectedWeakPoint.value = null
    weakPointStatus.value = 'error'
    weakPointError.value = toSafeUserMessage(error)
  }
}

async function generate(): Promise<void> {
  if (!scopeReady.value) return
  generating.value = true
  try {
    const point = selectedWeakPoint.value
    question.value = await unwrapApiResponse<GeneratedQuestion>(platformApiClient.post('/ai-question-generation', {
      qualificationId: qualificationId.value,
      subjectId: subjectId.value,
      generationMode: mode.value,
      difficulty: difficulty.value,
      knowledgeType: point?.knowledgeType ?? 'SYLLABUS',
      knowledgeId: point?.knowledgeId,
      knowledgeName: point?.knowledgeName,
      weakPointSummary: point ? `${point.knowledgeName} 错题 ${point.mistakeCount} 次` : undefined,
    }))
  } catch (error) { ElMessage.error(toSafeUserMessage(error)) } finally { generating.value = false }
}

async function createPlan(): Promise<void> {
  if (!scopeReady.value) return
  planning.value = true
  try {
    paper.value = await unwrapApiResponse<SmartPaper>(platformApiClient.post('/ai-smart-papers', {
      qualificationId: qualificationId.value, subjectId: subjectId.value, questionCount: 20,
      targetExamDate: targetExamDate.value || undefined,
    }))
  } catch (error) { ElMessage.error(toSafeUserMessage(error)) } finally { planning.value = false }
}

onMounted(async () => { if (qualification.status === 'idle') await qualification.load(); await loadWeakPoints() })
watch([() => qualification.selectedQualificationId, () => qualification.selectedSubjectId], () => { void loadWeakPoints() })
</script>

<template>
  <main class="planner-page" :class="{ 'is-embedded': props.embedded }" data-testid="ai-practice-planner-page">
    <header v-if="!props.embedded" class="planner-heading">
      <div>
        <span class="planner-heading__eyebrow">AI 学习规划</span>
        <h1>智能练习</h1>
        <p>结合错题表现与备考时间，生成更聚焦的专项训练。</p>
      </div>
      <div class="planner-scope">
        <span><el-icon><DataAnalysis /></el-icon></span>
        <div><small>当前训练范围</small><strong>{{ currentScopeLabel }}</strong></div>
      </div>
    </header>

    <section v-if="!scopeReady" class="planner-empty">
      <span><el-icon><DataAnalysis /></el-icon></span>
      <strong>训练范围尚未准备好</strong>
      <p>完成备考方向设置并加载科目后，即可使用智能练习。</p>
    </section>

    <div v-else class="planner-layout" :class="{ 'is-single': props.section !== 'all' }">
      <section v-if="props.section !== 'paper'" class="planner-panel planner-panel--question">
        <header class="planner-panel__heading">
          <div><span><el-icon><MagicStick /></el-icon></span><div><h2>薄弱点专项出题</h2><p>根据当前学习记录生成一道针对性练习题</p></div></div>
          <button type="button" :disabled="weakPointStatus === 'loading'" @click="loadWeakPoints">
            <el-icon><RefreshRight /></el-icon>刷新
          </button>
        </header>

        <div class="planner-form">
          <div class="planner-field">
            <div class="planner-field__label"><strong>薄弱知识点</strong><small>优先选择近期反复出错的考点</small></div>
            <el-select
              v-model="selectedWeakPoint"
              value-key="knowledgeId"
              class="planner-control"
              placeholder="由系统自动选择"
              :loading="weakPointStatus === 'loading'"
              :disabled="weakPointStatus === 'loading'"
              clearable
            >
              <el-option
                v-for="item in weakPoints"
                :key="`${item.knowledgeType}-${item.knowledgeId}`"
                :value="item"
                :label="`${item.knowledgeName}（错 ${item.mistakeCount} 次）`"
              />
            </el-select>
            <p v-if="weakPointStatus === 'empty'" class="planner-field__hint is-empty">暂无错题薄弱点，将由系统综合选择考点。</p>
            <p v-else-if="weakPointStatus === 'error'" class="planner-field__hint is-error">{{ weakPointError }}</p>
          </div>

          <div class="planner-field">
            <div class="planner-field__label"><strong>出题模式</strong><small>选择本次练习的生成策略</small></div>
            <div class="planner-option-grid">
              <button
                v-for="item in modes"
                :key="item.value"
                type="button"
                :class="{ 'is-active': mode === item.value }"
                @click="mode = item.value"
              >
                <span>{{ item.label }}</span><small>{{ item.description }}</small><el-icon v-if="mode === item.value"><CircleCheck /></el-icon>
              </button>
            </div>
          </div>

          <div class="planner-field">
            <div class="planner-field__label"><strong>题目难度</strong><small>{{ mode === 'ADAPTIVE' ? '自适应模式将自动决定难度' : '选择符合当前训练阶段的难度' }}</small></div>
            <div class="planner-difficulty">
              <button
                v-for="item in difficulties"
                :key="item.value"
                type="button"
                :disabled="mode === 'ADAPTIVE'"
                :class="{ 'is-active': difficulty === item.value }"
                @click="difficulty = item.value"
              >
                <strong>{{ item.label }}</strong><small>{{ item.description }}</small>
              </button>
            </div>
          </div>

          <el-button class="planner-primary" type="primary" :loading="generating" :disabled="!scopeReady" @click="generate">
            {{ generating ? 'AI 正在生成题目' : '生成一道练习题' }}<el-icon v-if="!generating"><ArrowRight /></el-icon>
          </el-button>
        </div>

        <article v-if="question" class="generated-question">
          <header>
            <span><el-icon><MagicStick /></el-icon>AI 专项练习</span>
            <div><em>{{ difficultyLabels[question.difficulty] ?? '未标注难度' }}</em><em>{{ generatedStatusLabels[question.status] ?? '处理中' }}</em></div>
          </header>
          <h3>{{ question.question.stem }}</h3>
          <ol>
            <li v-for="option in question.question.options" :key="option.key"><b>{{ option.key }}</b><span>{{ option.content }}</span></li>
          </ol>
          <section class="generated-answer">
            <div><small>参考答案</small><strong>{{ question.question.answer }}</strong></div>
            <div><small>题目解析</small><p>{{ question.question.analysis }}</p></div>
          </section>
        </article>
      </section>

      <section v-if="props.section !== 'question'" class="planner-panel planner-panel--plan">
        <header class="planner-panel__heading">
          <div><span class="is-teal"><el-icon><Calendar /></el-icon></span><div><h2>智能组卷与计划</h2><p>生成 20 题练习，并安排阶段性复习重点</p></div></div>
        </header>

        <div class="planner-form planner-form--plan">
          <div class="planner-field">
            <div class="planner-field__label"><strong>目标考试日期</strong><small>选填，用于调整计划节奏与阶段长度</small></div>
            <el-date-picker
              v-model="targetExamDate"
              class="planner-control"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="选择考试日期"
              :clearable="true"
            />
          </div>

          <div class="plan-preview">
            <span><el-icon><Reading /></el-icon></span>
            <div><strong>20 题智能练习</strong><p>薄弱点优先 · 难度均衡 · 阶段规划</p></div>
          </div>

          <el-button class="planner-primary planner-primary--dark" type="primary" :loading="planning" @click="createPlan">
            {{ planning ? '正在制定练习计划' : '生成智能练习计划' }}<el-icon v-if="!planning"><ArrowRight /></el-icon>
          </el-button>
        </div>

        <article v-if="paper" class="generated-plan">
          <header><span><el-icon><CircleCheck /></el-icon></span><div><small>计划已生成</small><h3>{{ paper.paperName }}</h3></div></header>
          <div class="generated-plan__summary">
            <span><small>练习题量</small><strong>{{ paper.questionIds.length }} 题</strong></span>
            <span><small>当前重点</small><strong>{{ paper.weakPoints.join('、') || '综合复习' }}</strong></span>
          </div>
          <ol class="plan-timeline">
            <li v-for="(stage, index) in paper.plan" :key="stage.stage">
              <span>{{ index + 1 }}</span>
              <div><strong>{{ stage.stage }}</strong><small>{{ stage.startDate }} 至 {{ stage.endDate }}</small><p>{{ stage.focus }}</p></div>
            </li>
          </ol>
        </article>
      </section>
    </div>
  </main>
</template>

<style scoped>
.planner-page {
  width: min(1180px, calc(100% - 40px));
  margin: 0 auto;
  padding: 26px 0 52px;
  color: #172033;
}

.planner-page.is-embedded {
  width: 100%;
  padding: 0;
}

.planner-page.is-embedded .planner-layout {
  margin-top: 0;
}

.planner-layout.is-single {
  grid-template-columns: minmax(0, 1fr);
}

.planner-heading {
  display: flex;
  min-height: 84px;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
}

.planner-heading__eyebrow {
  color: #2563eb;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: .06em;
}

.planner-heading h1,
.planner-panel h2,
.generated-question h3,
.generated-plan h3 {
  margin: 0;
}

.planner-heading h1 {
  margin-top: 5px;
  font-size: 28px;
  letter-spacing: -.025em;
}

.planner-heading p {
  margin: 5px 0 0;
  color: #6b788b;
  font-size: 13px;
}

.planner-scope {
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

.planner-scope > span {
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

.planner-scope > div {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.planner-scope small {
  color: #7b8798;
  font-size: 10px;
}

.planner-scope strong {
  overflow: hidden;
  color: #273244;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.planner-layout {
  display: grid;
  margin-top: 16px;
  grid-template-columns: minmax(0, 1.18fr) minmax(360px, .82fr);
  align-items: start;
  gap: 14px;
}

.planner-panel {
  overflow: hidden;
  border: 1px solid #dce4ee;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 10px 30px rgba(15, 23, 42, .045);
}

.planner-panel__heading {
  display: flex;
  min-height: 76px;
  padding: 16px 18px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-bottom: 1px solid #e7edf4;
}

.planner-panel__heading > div {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 12px;
}

.planner-panel__heading > div > span {
  display: grid;
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  place-items: center;
  border-radius: 11px;
  color: #2563eb;
  background: #edf4ff;
  font-size: 18px;
}

.planner-panel__heading > div > span.is-teal {
  color: #0f766e;
  background: #eaf8f5;
}

.planner-panel__heading h2 {
  color: #263247;
  font-size: 16px;
}

.planner-panel__heading p {
  margin: 4px 0 0;
  color: #8190a3;
  font-size: 11px;
}

.planner-panel__heading > button {
  display: inline-flex;
  min-height: 34px;
  padding: 0 10px;
  align-items: center;
  gap: 5px;
  border: 0;
  border-radius: 8px;
  color: #64748b;
  background: #f1f5f9;
  cursor: pointer;
  font-size: 12px;
}

.planner-panel__heading > button:hover:not(:disabled) {
  color: #2563eb;
  background: #edf4ff;
}

.planner-panel__heading > button:disabled {
  cursor: wait;
  opacity: .55;
}

.planner-form {
  display: grid;
  padding: 20px;
  gap: 20px;
}

.planner-form--plan {
  gap: 18px;
}

.planner-field {
  display: grid;
  gap: 9px;
}

.planner-field__label {
  display: grid;
  gap: 3px;
}

.planner-field__label strong {
  color: #334155;
  font-size: 13px;
}

.planner-field__label small {
  color: #8a96a8;
  font-size: 10px;
}

.planner-control {
  width: 100%;
}

:deep(.planner-control .el-select__wrapper),
:deep(.planner-control.el-date-editor) {
  min-height: 42px;
  border-radius: 10px;
  box-shadow: 0 0 0 1px #dce4ee inset;
}

:deep(.planner-control .el-select__wrapper.is-focused),
:deep(.planner-control.el-date-editor.is-active) {
  box-shadow: 0 0 0 1px #2563eb inset, 0 0 0 3px rgba(37, 99, 235, .08);
}

.planner-field__hint {
  margin: 0;
  color: #7b8798;
  font-size: 11px;
}

.planner-field__hint.is-error {
  color: #dc2626;
}

.planner-option-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.planner-option-grid button {
  position: relative;
  display: grid;
  min-height: 76px;
  padding: 12px;
  align-content: center;
  gap: 5px;
  border: 1px solid #dde5ef;
  border-radius: 10px;
  color: #475569;
  background: #fff;
  cursor: pointer;
  text-align: left;
}

.planner-option-grid button:hover {
  border-color: #abc3f2;
}

.planner-option-grid button.is-active {
  border-color: #2563eb;
  color: #1e293b;
  background: #f2f7ff;
  box-shadow: 0 0 0 2px rgba(37, 99, 235, .06);
}

.planner-option-grid button > span {
  font-size: 13px;
  font-weight: 700;
}

.planner-option-grid button small {
  color: #8491a3;
  font-size: 10px;
  line-height: 1.4;
}

.planner-option-grid button .el-icon {
  position: absolute;
  top: 9px;
  right: 9px;
  color: #2563eb;
}

.planner-difficulty {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.planner-difficulty button {
  display: grid;
  min-height: 55px;
  padding: 9px 12px;
  align-content: center;
  gap: 3px;
  border: 1px solid #dce4ee;
  border-radius: 9px;
  color: #526078;
  background: #fff;
  cursor: pointer;
  text-align: left;
}

.planner-difficulty button.is-active {
  border-color: #2563eb;
  color: #1d4ed8;
  background: #f3f7ff;
}

.planner-difficulty button:disabled {
  cursor: not-allowed;
  opacity: .45;
}

.planner-difficulty small {
  color: #8a96a8;
  font-size: 10px;
}

.planner-primary {
  width: 100%;
  height: 44px;
  margin: 0;
  border-radius: 10px;
  font-weight: 700;
}

.planner-primary--dark {
  border-color: #172033;
  background: #172033;
}

.plan-preview {
  display: flex;
  min-height: 72px;
  padding: 13px;
  align-items: center;
  gap: 12px;
  border: 1px solid #e2e8f0;
  border-radius: 11px;
  background: #f8fafc;
}

.plan-preview > span {
  display: grid;
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  place-items: center;
  border-radius: 11px;
  color: #0f766e;
  background: #e7f7f3;
}

.plan-preview strong {
  color: #334155;
  font-size: 13px;
}

.plan-preview p {
  margin: 4px 0 0;
  color: #8491a3;
  font-size: 10px;
}

.generated-question,
.generated-plan {
  margin: 0 20px 20px;
  overflow: hidden;
  border: 1px solid #dfe7f1;
  border-radius: 13px;
  background: #fbfcfe;
}

.generated-question > header {
  display: flex;
  min-height: 48px;
  padding: 0 15px;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e7edf4;
}

.generated-question > header > span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #2563eb;
  font-size: 12px;
  font-weight: 700;
}

.generated-question > header > div {
  display: flex;
  gap: 5px;
}

.generated-question em {
  padding: 3px 7px;
  border-radius: 999px;
  color: #64748b;
  background: #eef2f7;
  font-size: 10px;
  font-style: normal;
}

.generated-question > h3 {
  padding: 18px 18px 12px;
  color: #263247;
  font-size: 15px;
  line-height: 1.75;
}

.generated-question ol {
  display: grid;
  padding: 0 18px 18px;
  margin: 0;
  gap: 8px;
  list-style: none;
}

.generated-question li {
  display: flex;
  min-height: 44px;
  padding: 8px 11px;
  align-items: center;
  gap: 10px;
  border: 1px solid #e2e8f0;
  border-radius: 9px;
  color: #526078;
  background: #fff;
  font-size: 12px;
  line-height: 1.55;
}

.generated-question li b {
  display: grid;
  width: 26px;
  height: 26px;
  flex: 0 0 26px;
  place-items: center;
  border-radius: 7px;
  color: #2563eb;
  background: #edf4ff;
}

.generated-answer {
  display: grid;
  padding: 15px 18px;
  gap: 13px;
  border-top: 1px solid #dfe7f1;
  background: #f4faf7;
}

.generated-answer > div {
  display: grid;
  gap: 5px;
}

.generated-answer small {
  color: #0f766e;
  font-size: 10px;
  font-weight: 700;
}

.generated-answer strong {
  color: #166b5f;
  font-size: 15px;
}

.generated-answer p {
  margin: 0;
  color: #526078;
  font-size: 12px;
  line-height: 1.75;
}

.generated-plan {
  padding: 17px;
}

.generated-plan > header {
  display: flex;
  align-items: center;
  gap: 11px;
}

.generated-plan > header > span {
  display: grid;
  width: 38px;
  height: 38px;
  flex: 0 0 38px;
  place-items: center;
  border-radius: 11px;
  color: #fff;
  background: #0f766e;
}

.generated-plan > header small {
  color: #0f766e;
  font-size: 10px;
}

.generated-plan > header h3 {
  margin-top: 3px;
  color: #263247;
  font-size: 14px;
}

.generated-plan__summary {
  display: grid;
  margin-top: 15px;
  grid-template-columns: .7fr 1.3fr;
  gap: 8px;
}

.generated-plan__summary > span {
  display: grid;
  min-width: 0;
  padding: 10px;
  gap: 4px;
  border-radius: 9px;
  background: #f1f5f9;
}

.generated-plan__summary small {
  color: #8491a3;
  font-size: 9px;
}

.generated-plan__summary strong {
  overflow: hidden;
  color: #334155;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.plan-timeline {
  display: grid;
  padding: 0;
  margin: 17px 0 0;
  gap: 0;
  list-style: none;
}

.plan-timeline li {
  position: relative;
  display: grid;
  padding-bottom: 16px;
  grid-template-columns: 28px minmax(0, 1fr);
  gap: 10px;
}

.plan-timeline li:last-child {
  padding-bottom: 0;
}

.plan-timeline li::before {
  position: absolute;
  top: 27px;
  bottom: 0;
  left: 13px;
  width: 1px;
  background: #dbe5e2;
  content: '';
}

.plan-timeline li:last-child::before {
  display: none;
}

.plan-timeline li > span {
  z-index: 1;
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  border-radius: 50%;
  color: #0f766e;
  background: #e7f7f3;
  font-size: 10px;
  font-weight: 700;
}

.plan-timeline div {
  display: grid;
  gap: 3px;
}

.plan-timeline strong {
  color: #334155;
  font-size: 12px;
}

.plan-timeline small {
  color: #94a3b8;
  font-size: 9px;
}

.plan-timeline p {
  margin: 2px 0 0;
  color: #64748b;
  font-size: 10px;
  line-height: 1.55;
}

.planner-empty {
  display: grid;
  min-height: 340px;
  margin-top: 16px;
  place-items: center;
  align-content: center;
  gap: 8px;
  border: 1px solid #dce4ee;
  border-radius: 16px;
  background: #fff;
  color: #8290a3;
  text-align: center;
}

.planner-empty > span {
  display: grid;
  width: 50px;
  height: 50px;
  margin-bottom: 4px;
  place-items: center;
  border-radius: 14px;
  color: #64748b;
  background: #f1f5f9;
  font-size: 22px;
}

.planner-empty strong {
  color: #334155;
  font-size: 15px;
}

.planner-empty p {
  margin: 0;
  font-size: 12px;
}

@media (max-width: 980px) {
  .planner-layout {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 680px) {
  .planner-page {
    width: calc(100% - 24px);
    padding: 16px 0 36px;
  }

  .planner-heading {
    align-items: stretch;
    flex-direction: column;
    gap: 14px;
  }

  .planner-scope {
    width: 100%;
  }

  .planner-option-grid {
    grid-template-columns: 1fr;
  }

  .planner-option-grid button {
    min-height: 64px;
  }

  .planner-difficulty {
    grid-template-columns: 1fr;
  }

  .planner-difficulty button {
    min-height: 50px;
  }

  .planner-panel__heading {
    padding: 14px;
  }

  .planner-panel__heading p {
    display: none;
  }

  .planner-form {
    padding: 16px;
  }

  .generated-question,
  .generated-plan {
    margin: 0 16px 16px;
  }
}
</style>
