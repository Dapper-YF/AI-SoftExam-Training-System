<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Check, CopyDocument, Delete, Filter, Plus, Rank, Refresh, Search } from '@element-plus/icons-vue'
import { platformApiClient, unwrapApiResponse } from '@/api/client'
import { useQualificationStore } from '@/stores/qualification'

interface Question { questionId: number; questionType: string; title: string; yearNo?: number | null; difficulty?: string | null; status: string; subjectId: number }
interface QuestionDetail { questionId: number; questionType: string; title: string; stems?: { stemId: number; stemType: string; content: string; sortOrder: number }[]; options?: { optionId: number; optionKey: string; optionContent: string; sortOrder: number }[]; subQuestions?: { subQuestionId: number; subQuestionNo: number; subQuestionType: string; content: string; score: number; options?: { optionId: number; optionKey: string; optionContent: string; sortOrder: number }[] }[]; solutions?: { solutionId: number; subQuestionId: number | null; solutionType: string; content: string }[] }
interface PaperQuestion { questionId: number; questionNo: number; questionType: string; title: string; score: number; subjectId: number; contentVersion?: number }
interface Paper { paperId: number; qualificationId: number; subjectId: number | null; paperName: string; paperType: string; yearNo: number | null; totalScore: number; durationMinutes: number; status: string; questions: PaperQuestion[] }
interface Page<T> { items?: T[]; records?: T[]; total?: number }

const route = useRoute(); const router = useRouter(); const paperId = computed(() => Number(route.params.paperId || 0))
const qualificationStore = useQualificationStore()
const loading = ref(false); const saving = ref(false); const questions = ref<Question[]>([]); const total = ref(0); const selected = ref<Question[]>([])
const previewOpen = ref(false)
// 拖拽排序
const dragIndex = ref<number | null>(null)
const dragOverIndex = ref<number | null>(null)
// 题目详情预览
const previewQuestionOpen = ref(false)
const previewQuestionData = ref<QuestionDetail | null>(null)
const previewLoading = ref(false)
// 批量改分
const batchScoreOpen = ref(false)
const batchScoreValue = ref(2)
const selectedForBatch = ref<Set<number>>(new Set())
const allSelected = ref(false)
const paper = reactive<Paper>({ paperId: 0, qualificationId: 0, subjectId: null, paperName: '', paperType: 'MOCK', yearNo: null, totalScore: 0, durationMinutes: 150, status: 'DRAFT', questions: [] })
const query = reactive({ keyword: '', questionType: '', status: 'PUBLISHED', pageNo: 1, pageSize: 10 })
const rule = reactive({ questionType: 'SINGLE_CHOICE', count: 5, score: 2, difficulty: '', yearNo: null as number | null, excludeUsed: true })
const typeLabels: Record<string, string> = { SINGLE_CHOICE: '单选题', MULTI_CHOICE: '多选题', JUDGE: '判断题', CASE: '案例题', ESSAY: '论文题' }
const difficultyLabels: Record<string, string> = { EASY: '简单', MEDIUM: '中等', HARD: '困难' }
const computedScore = computed(() => paper.questions.reduce((sum, item) => sum + Number(item.score || 0), 0))
const duplicateIds = computed(() => new Set(paper.questions.map(item => item.questionId)))
const duplicateQuestionCount = computed(() => paper.questions.length - duplicateIds.value.size)
const validationItems = computed(() => [
  { label: '试卷名称', ok: Boolean(paper.paperName.trim()), detail: paper.paperName.trim() ? '已填写' : '请输入试卷名称' },
  { label: '题目数量', ok: paper.questions.length > 0, detail: paper.questions.length ? `${paper.questions.length} 道题` : '至少需要一道题' },
  { label: '题号连续', ok: paper.questions.every((item, index) => item.questionNo === index + 1), detail: '题号从 1 开始连续排列' },
  { label: '重复题目', ok: duplicateQuestionCount.value === 0, detail: duplicateQuestionCount.value ? `发现 ${duplicateQuestionCount.value} 道重复题` : '未发现重复题' },
  { label: '总分合计', ok: computedScore.value > 0, detail: `${computedScore.value} 分` },
])
const validationPassed = computed(() => validationItems.value.every(item => item.ok))
const list = <T,>(page: Page<T>): T[] => page.items ?? page.records ?? []

async function loadPaper(): Promise<void> {
  if (!paperId.value) return
  const detail = await unwrapApiResponse<Paper>(platformApiClient.get(`/admin/papers/${paperId.value}`)); Object.assign(paper, detail); paper.questions = detail.questions ?? []
}
async function loadQuestions(): Promise<void> {
  loading.value = true
  try { const page = await unwrapApiResponse<Page<Question>>(platformApiClient.get('/admin/questions', { params: { ...query, qualificationId: paper.qualificationId || undefined, subjectId: paper.subjectId || undefined } })); questions.value = list(page); total.value = page.total ?? 0 } finally { loading.value = false }
}
function addSelected(): void {
  paper.questions.push(...selected.value.filter(item => !duplicateIds.value.has(item.questionId)).map((item, index) => ({ questionId: item.questionId, questionNo: paper.questions.length + index + 1, questionType: item.questionType, title: item.title, score: item.questionType === 'ESSAY' ? 30 : item.questionType === 'CASE' ? 10 : 2, subjectId: item.subjectId }))); selected.value = []; recalc()
}
function removeQuestion(index: number): void { paper.questions.splice(index, 1); paper.questions.forEach((item, i) => { item.questionNo = i + 1 }); recalc() }
function recalc(): void { paper.totalScore = computedScore.value }
async function generateByRule(): Promise<void> {
  query.questionType = rule.questionType; query.pageNo = 1; query.pageSize = 100; await loadQuestions()
  const candidates = questions.value.filter(item => (!rule.excludeUsed || !duplicateIds.value.has(item.questionId)) && (!rule.difficulty || item.difficulty === rule.difficulty) && (!rule.yearNo || item.yearNo === rule.yearNo))
  if (candidates.length < rule.count) { ElMessage.warning(`当前筛选条件只有 ${candidates.length} 道可用题目`); return }
  const shuffled = [...candidates].sort(() => Math.random() - 0.5).slice(0, rule.count)
  paper.questions.push(...shuffled.map((item, index) => ({ questionId: item.questionId, questionNo: paper.questions.length + index + 1, questionType: item.questionType, title: item.title, score: rule.score, subjectId: item.subjectId }))); recalc(); ElMessage.success(`已生成 ${shuffled.length} 道候选题，请检查后保存`)
}
// ── 拖拽排序 ──
function onDragStart(index: number): void { dragIndex.value = index }
function onDragOver(index: number): void { if (dragIndex.value !== null && dragIndex.value !== index) dragOverIndex.value = index }
function onDrop(index: number): void {
  if (dragIndex.value === null || dragIndex.value === index) { dragIndex.value = null; dragOverIndex.value = null; return }
  const moved = paper.questions.splice(dragIndex.value, 1)[0]
  if (!moved) return
  paper.questions.splice(index, 0, moved)
  paper.questions.forEach((item, i) => { item.questionNo = i + 1 })
  recalc(); dragIndex.value = null; dragOverIndex.value = null
}
function onDragEnd(): void { dragIndex.value = null; dragOverIndex.value = null }
// ── 题目详情预览 ──
async function viewQuestionDetail(questionId: number): Promise<void> {
  previewLoading.value = true; previewQuestionOpen.value = true
  try {
    const detail = await unwrapApiResponse<QuestionDetail>(platformApiClient.get(`/questions/${questionId}`))
    previewQuestionData.value = detail
  } catch { ElMessage.error('加载题目详情失败'); previewQuestionOpen.value = false }
  finally { previewLoading.value = false }
}
// ── 批量改分 ──
function toggleSelectAll(): void {
  allSelected.value = !allSelected.value
  if (allSelected.value) { paper.questions.forEach(item => selectedForBatch.value.add(item.questionId)) }
  else { selectedForBatch.value.clear() }
}
function toggleSelect(questionId: number): void {
  if (selectedForBatch.value.has(questionId)) selectedForBatch.value.delete(questionId)
  else selectedForBatch.value.add(questionId)
  allSelected.value = selectedForBatch.value.size === paper.questions.length
}
function openBatchScore(): void { batchScoreValue.value = 2; batchScoreOpen.value = true }
function applyBatchScore(): void {
  paper.questions.forEach(item => { if (selectedForBatch.value.has(item.questionId)) item.score = batchScoreValue.value })
  recalc(); ElMessage.success(`已更新 ${selectedForBatch.value.size} 道题的分值`); batchScoreOpen.value = false; selectedForBatch.value.clear(); allSelected.value = false
}
async function save(): Promise<boolean> {
  if (!paper.paperName.trim()) { ElMessage.warning('请输入试卷名称'); return false }
  if (!paper.questions.length) { ElMessage.warning('请至少加入一道题目'); return false }
  saving.value = true
  try {
    const payload = { paper: { qualificationId: paper.qualificationId || undefined, subjectId: paper.subjectId || undefined, paperName: paper.paperName, paperType: paper.paperType, yearNo: paper.yearNo, totalScore: paper.totalScore, durationMinutes: paper.durationMinutes }, questions: paper.questions.map(item => ({ questionId: item.questionId, questionNo: item.questionNo, sectionCode: item.questionType, score: item.score })) }
    if (!paper.paperId) { const created = await unwrapApiResponse<Paper>(platformApiClient.post('/admin/papers', payload.paper)); paper.paperId = created.paperId }
    await unwrapApiResponse(platformApiClient.put(`/admin/papers/${paper.paperId}/review-draft`, payload)); ElMessage.success('试卷草稿已保存'); if (!paperId.value) await router.replace(`/admin/papers/${paper.paperId}/edit`); return true
  } catch { ElMessage.error('保存失败，请检查试卷信息和题目状态'); return false } finally { saving.value = false }
}
async function publish(): Promise<void> { if (!await save() || !paper.paperId) return; try { await ElMessageBox.confirm('发布后试卷将进入正式使用范围，确认发布？', '发布试卷', { type: 'warning' }); await unwrapApiResponse(platformApiClient.post(`/admin/papers/${paper.paperId}/publish`)); ElMessage.success('试卷已发布'); await router.push('/admin/papers') } catch { /* cancel or server validation */ } }
onMounted(async () => { try { await loadPaper(); if (!paper.paperId) { if (qualificationStore.status === 'idle') await qualificationStore.load(); const qualificationId = qualificationStore.selectedQualificationId || qualificationStore.qualifications[0]?.id; if (qualificationId) { paper.qualificationId = Number(qualificationId); if (qualificationStore.selectedQualificationId !== qualificationId) await qualificationStore.selectQualification(qualificationId); paper.subjectId = Number(qualificationStore.selectedSubjectId || qualificationStore.subjects[0]?.id || 0) } } await loadQuestions() } catch { ElMessage.error('试卷加载失败') } })
</script>

<template>
  <section class="editor" v-loading="saving">
    <header class="editor-header"><div><el-button link @click="router.push('/admin/papers')"><el-icon><ArrowLeft /></el-icon>返回试卷管理</el-button><h1>{{ paper.paperId ? '编辑组卷' : '新建组卷' }}</h1><p>手工选择题目，调整顺序和分值后保存为试卷草稿。</p></div><div class="actions"><el-button :icon="Refresh" @click="loadPaper">刷新</el-button><el-button @click="generateByRule">按规则生成 5 道</el-button><el-button @click="previewOpen = true">预览与校验</el-button><el-button @click="save">保存草稿</el-button><el-button type="primary" :icon="Check" :disabled="!paper.questions.length" @click="publish">发布试卷</el-button></div></header>
    <section class="settings"><el-form inline><el-form-item label="试卷名称"><el-input v-model="paper.paperName" placeholder="例如：2026 高项模拟卷一" /></el-form-item><el-form-item label="类型"><el-select v-model="paper.paperType"><el-option label="模拟卷" value="MOCK" /><el-option label="专项卷" value="SMART" /><el-option label="每日练习" value="DAILY" /></el-select></el-form-item><el-form-item label="年份"><el-input-number v-model="paper.yearNo" :min="2000" :max="2100" /></el-form-item><el-form-item label="时长"><el-input-number v-model="paper.durationMinutes" :min="1" /><span class="unit">分钟</span></el-form-item></el-form></section>
    <section class="rule-toolbar"><strong>规则组卷</strong><el-select v-model="rule.questionType" placeholder="题型"><el-option v-for="(label, value) in typeLabels" :key="value" :label="label" :value="value" /></el-select><el-input-number v-model="rule.count" :min="1" :max="100" /><el-input-number v-model="rule.score" :min="0.5" :max="100" :step="0.5" /><el-select v-model="rule.difficulty" clearable placeholder="全部难度"><el-option label="简单" value="EASY" /><el-option label="中等" value="MEDIUM" /><el-option label="困难" value="HARD" /></el-select><el-input-number v-model="rule.yearNo" :min="2000" :max="2100" placeholder="年份" /><el-checkbox v-model="rule.excludeUsed">排除已选题</el-checkbox><el-button type="success" @click="generateByRule">生成候选题</el-button></section>
    <div class="summary"><span>题目 <b>{{ paper.questions.length }}</b></span><span>总分 <b>{{ computedScore }}</b></span><span :class="computedScore === Number(paper.totalScore) ? 'ok' : 'warn'">{{ computedScore === Number(paper.totalScore) ? '分值校验通过' : '总分按题目分值计算' }}</span></div>
    <main class="workspace"><section class="panel selected-panel"><header><div><small>当前试卷</small><h2>已选题目</h2></div><div class="header-actions"><el-tag>{{ paper.questions.length }} 题</el-tag><el-button v-if="selectedForBatch.size" size="small" :icon="CopyDocument" @click="openBatchScore">批量改分</el-button></div></header><el-empty v-if="!paper.questions.length" description="从右侧题库选择题目" /><div v-for="(item, index) in paper.questions" :key="item.questionId" class="selected-row" :class="{ 'drag-over': dragOverIndex === index, 'is-dragging': dragIndex === index }" draggable="true" @dragstart="onDragStart(index)" @dragover.prevent="onDragOver(index)" @drop.prevent="onDrop(index)" @dragend="onDragEnd"><el-checkbox v-if="paper.questions.length > 1" :model-value="selectedForBatch.has(item.questionId)" @change="toggleSelect(item.questionId)" size="small" class="batch-cb" /><span class="drag-handle"><el-icon><Rank /></el-icon></span><span class="number">{{ item.questionNo }}</span><div class="question-copy"><strong>{{ item.title || '无标题题目' }}</strong><small>{{ typeLabels[item.questionType] || item.questionType }} · ID {{ item.questionId }}</small></div><el-input-number v-model="item.score" :min="0.5" :max="100" :step="0.5" size="small" @change="recalc" /><el-button link type="danger" :icon="Delete" @click="removeQuestion(index)" /></div></section><section class="panel bank-panel" v-loading="loading"><header><div><small>题库筛选</small><h2>选择题目</h2></div><el-button type="primary" :icon="Plus" :disabled="!selected.length" @click="addSelected">加入试卷</el-button></header><div class="filters"><el-input v-model="query.keyword" placeholder="搜索题干" clearable @keyup.enter="loadQuestions"><template #prefix><el-icon><Search /></el-icon></template></el-input><el-select v-model="query.questionType" clearable placeholder="全部题型"><el-option v-for="(label, value) in typeLabels" :key="value" :label="label" :value="value" /></el-select><el-button :icon="Filter" @click="loadQuestions">筛选</el-button></div><el-table :data="questions" @selection-change="selected = $event"><el-table-column type="selection" width="44" :selectable="(row: Question) => !duplicateIds.has(row.questionId)" /><el-table-column label="题目" min-width="260"><template #default="scope"><div class="bank-question"><strong class="preview-link" @click="viewQuestionDetail(scope.row.questionId)">{{ scope.row.title || '无标题题目' }}</strong><small>{{ typeLabels[scope.row.questionType] || scope.row.questionType }} · {{ difficultyLabels[scope.row.difficulty || ''] || '未标注难度' }} · {{ scope.row.yearNo || '未知年份' }}</small></div></template></el-table-column><el-table-column label="状态" width="90" prop="status" /><el-table-column label="操作" width="60" align="right"><template #default="scope"><el-button link type="primary" size="small" @click="viewQuestionDetail(scope.row.questionId)">预览</el-button></template></el-table-column></el-table><el-pagination v-model:current-page="query.pageNo" :page-size="query.pageSize" :total="total" layout="total, prev, pager, next" @current-change="loadQuestions" /></section></main>
    <el-dialog v-model="previewOpen" title="试卷预览与发布检查" width="min(760px, calc(100vw - 28px))"><div class="validation-list"><div v-for="item in validationItems" :key="item.label" class="validation-row"><el-tag :type="item.ok ? 'success' : 'danger'">{{ item.ok ? '通过' : '需处理' }}</el-tag><strong>{{ item.label }}</strong><span>{{ item.detail }}</span></div></div><el-divider /><div class="preview-paper"><h2>{{ paper.paperName || '未命名试卷' }}</h2><p>共 {{ paper.questions.length }} 题 · {{ computedScore }} 分 · {{ paper.durationMinutes }} 分钟</p><div v-for="item in paper.questions" :key="`preview-${item.questionId}`" class="preview-row"><b>{{ item.questionNo }}.</b><span>{{ item.title || '无标题题目' }}</span><em>{{ item.score }} 分</em></div></div><template #footer><el-button @click="previewOpen = false">关闭</el-button><el-button type="primary" :disabled="!validationPassed" @click="previewOpen = false; publish()">发布试卷</el-button></template></el-dialog>

    <el-dialog v-model="previewQuestionOpen" title="题目详情" width="min(640px, calc(100vw - 28px))" v-loading="previewLoading"><template v-if="previewQuestionData"><div class="qdetail-type"><el-tag>{{ typeLabels[previewQuestionData.questionType] || previewQuestionData.questionType }}</el-tag></div><div class="qdetail-stem"><strong>题干</strong><p>{{ previewQuestionData.title }}</p></div><template v-if="previewQuestionData.stems?.length"><div v-for="stem in previewQuestionData.stems" :key="stem.stemId" class="qdetail-stem"><p>{{ stem.content }}</p></div></template><template v-if="previewQuestionData.options?.length"><div class="qdetail-options"><strong>选项</strong><div v-for="opt in previewQuestionData.options" :key="opt.optionId" class="qdetail-option"><span class="option-key">{{ opt.optionKey }}.</span><span>{{ opt.optionContent }}</span></div></div></template><template v-if="previewQuestionData.subQuestions?.length"><div class="qdetail-sub"><strong>子题</strong><div v-for="sub in previewQuestionData.subQuestions" :key="sub.subQuestionId" class="qdetail-sub-item"><b>{{ sub.subQuestionNo }}.</b><span>{{ sub.content }}</span><em v-if="sub.score">{{ sub.score }} 分</em><template v-if="sub.options?.length"><div v-for="opt in sub.options" :key="opt.optionId" class="qdetail-option"><span class="option-key">{{ opt.optionKey }}.</span><span>{{ opt.optionContent }}</span></div></template></div></div></template><template v-if="previewQuestionData.solutions?.length"><div class="qdetail-solutions"><strong>参考答案</strong><div v-for="sol in previewQuestionData.solutions" :key="sol.solutionId" class="qdetail-solution"><span>{{ sol.content }}</span></div></div></template></template><template #footer><el-button @click="previewQuestionOpen = false">关闭</el-button></template></el-dialog>

    <el-dialog v-model="batchScoreOpen" title="批量改分" width="360px"><p class="batch-score-hint">已选 <b>{{ selectedForBatch.size }}</b> 道题，统一设置分值为：</p><el-input-number v-model="batchScoreValue" :min="0.5" :max="100" :step="0.5" style="width:100%" /><template #footer><el-button @click="batchScoreOpen = false">取消</el-button><el-button type="primary" @click="applyBatchScore">确认修改</el-button></template></el-dialog>
  </section>
</template>

<style scoped>
.editor{min-height:100%;padding:26px clamp(18px,3vw,44px) 46px;background:#f5f7fb;color:#172033}.editor-header,.actions,.summary,.workspace,.selected-row,.rule-toolbar{display:flex;align-items:center}.editor-header{justify-content:space-between;gap:20px}.editor-header h1{margin:12px 0 4px;font-size:28px}.editor-header p{margin:0;color:#7b8799;font-size:13px}.actions{gap:8px}.settings,.panel,.rule-toolbar{border:1px solid #dfe6f0;border-radius:12px;background:#fff;box-shadow:0 8px 24px #1f2d4b0b}.settings{margin-top:18px;padding:16px 20px}.settings :deep(.el-form-item){margin-bottom:0}.settings :deep(.el-input){width:260px}.settings :deep(.el-select){width:150px}.unit{margin-left:6px;color:#7b8799}.rule-toolbar{flex-wrap:wrap;gap:8px;margin-top:16px;padding:12px 16px}.rule-toolbar strong{margin-right:auto;color:#344057;font-size:13px}.rule-toolbar :deep(.el-select){width:130px}.rule-toolbar :deep(.el-input-number){width:100px}.summary{gap:26px;margin:16px 0;color:#76859b;font-size:13px}.summary b{color:#26334a;font-size:20px}.summary .ok{color:#168653}.summary .warn{color:#a46f1d}.workspace{align-items:flex-start;gap:18px}.panel{min-width:0;overflow:hidden}.selected-panel{flex:1}.bank-panel{flex:1.15}.panel>header{display:flex;align-items:center;justify-content:space-between;padding:16px 18px;border-bottom:1px solid #edf0f5}.panel header small{color:#8b98aa;font-size:11px}.panel h2{margin:3px 0 0;font-size:16px}.selected-row{gap:10px;padding:13px 16px;border-bottom:1px solid #eef1f5}.number{display:grid;flex:0 0 28px;width:28px;height:28px;place-items:center;border-radius:7px;background:#eaf1ff;color:#2563eb;font-weight:700}.question-copy,.bank-question{display:grid;min-width:0;flex:1;gap:4px}.question-copy strong,.bank-question strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:13px}.question-copy small,.bank-question small{color:#8b98aa;font-size:11px}.selected-row :deep(.el-input-number){width:104px}.filters{display:flex;gap:8px;padding:14px 16px}.filters .el-input{flex:1}.filters .el-select{width:130px}.bank-panel :deep(.el-table){font-size:12px}.bank-panel :deep(.el-pagination){padding:14px 16px}.panel :deep(.el-empty){padding:70px 0}
.drag-handle{cursor:grab;color:#b0bac9;display:grid;place-items:center;flex:0 0 22px;font-size:14px}.selected-row.drag-over{border-top:2px solid #2563eb}.selected-row.is-dragging{opacity:.5}.selected-row .batch-cb{margin-right:2px}.header-actions{display:flex;align-items:center;gap:8px}.preview-link{cursor:pointer;color:#2563eb;transition:color .15s}.preview-link:hover{color:#1d4ed8;text-decoration:underline}.qdetail-type{margin-bottom:12px}.qdetail-stem strong,.qdetail-options strong,.qdetail-sub strong,.qdetail-solutions strong{display:block;margin-bottom:6px;color:#344057;font-size:13px}.qdetail-stem p{margin:0 0 8px;color:#4a5a73;font-size:14px;line-height:1.6;white-space:pre-wrap}.qdetail-options,.qdetail-sub{margin-top:12px}.qdetail-option{display:flex;gap:6px;padding:4px 0;font-size:13px;color:#4a5a73}.option-key{font-weight:650;color:#2563eb;flex-shrink:0}.qdetail-sub-item{padding:8px 0;border-bottom:1px solid #eef1f5;font-size:13px}.qdetail-sub-item>span{color:#4a5a73}.qdetail-sub-item>em{margin-left:8px;color:#2563eb;font-style:normal;font-size:11px}.qdetail-solutions{margin-top:12px;padding:10px;border-radius:8px;background:#f0f9f4}.qdetail-solution{font-size:13px;color:#168653;padding:2px 0}.batch-score-hint{margin:0 0 12px;color:#4a5a73;font-size:14px}.batch-score-hint b{color:#2563eb}
@media(max-width:900px){.workspace{flex-direction:column}.selected-panel,.bank-panel{width:100%}.editor-header{align-items:flex-start;flex-direction:column}.actions{width:100%}.actions .el-button{flex:1}.settings :deep(.el-form){align-items:flex-start;flex-wrap:wrap;gap:8px}.settings :deep(.el-form-item){margin:0}.rule-toolbar{align-items:stretch}.rule-toolbar strong{flex-basis:100%}.filters{flex-wrap:wrap}.filters .el-input{flex-basis:100%}}
.validation-list{display:grid;gap:8px}.validation-row{display:flex;align-items:center;gap:10px;padding:8px 10px;border-bottom:1px solid #eef1f5}.validation-row strong{width:90px;color:#344057}.validation-row span{color:#7b8799;font-size:12px}.preview-paper h2{margin:0 0 4px}.preview-paper p{margin:0 0 14px;color:#7b8799;font-size:12px}.preview-row{display:flex;align-items:center;gap:10px;padding:8px 0;border-bottom:1px solid #eef1f5}.preview-row b{width:28px}.preview-row span{flex:1}.preview-row em{color:#2563eb;font-style:normal;font-size:12px}
</style>
