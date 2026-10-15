<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Collection, Connection, EditPen, Plus, Refresh, Tickets } from '@element-plus/icons-vue'
import { platformApiClient, unwrapApiResponse } from '@/api/client'

type Page<T> = { items?: T[]; records?: T[] }
type Qualification = { id: number; qualificationName: string }
type Subject = { id: number; subjectName: string }
type Chapter = { id: number; parentId: number | null; chapterCode: string; chapterName: string; levelNo: number; sortOrder: number | null; status: string }
type SyllabusNode = { id: number; parentId: number | null; syllabusVersion: string; nodeCode: string; nodeName: string; levelNo: number; sortOrder: number | null; status: string; children?: SyllabusNode[] }
type Tag = { id: number; parentId: number | null; syllabusVersion: string; tagCode: string; tagName: string; tagType: string; levelNo: number; sortOrder: number; status: string }
type EditorKind = 'chapter' | 'tag'

const qualifications = ref<Qualification[]>([])
const subjects = ref<Subject[]>([])
const chapters = ref<Chapter[]>([])
const tags = ref<Tag[]>([])
const qualificationId = ref<number>()
const subjectId = ref<number>()
const loading = ref(false)
const saving = ref(false)
const editorOpen = ref(false)
const editorKind = ref<EditorKind>('chapter')
const chapterEditingId = ref<number | null>(null)
const tagEditingId = ref<number | null>(null)
const chapterForm = reactive({ parentId: undefined as number | undefined, chapterCode: '', chapterName: '', levelNo: 1, sortOrder: 0, status: 'ENABLED' })
const tagForm = reactive({ parentId: undefined as number | undefined, syllabusVersion: '2026', tagCode: '', tagName: '', tagType: 'KNOWLEDGE_POINT', levelNo: 1, sortOrder: 0, status: 'ENABLED' })

const list = <T,>(page: Page<T>): T[] => page.items ?? page.records ?? []
const flattenSyllabus = (nodes: SyllabusNode[]): Tag[] => nodes.flatMap(node => [{
  id: node.id,
  parentId: node.parentId,
  syllabusVersion: node.syllabusVersion,
  tagCode: node.nodeCode,
  tagName: node.nodeName,
  tagType: 'KNOWLEDGE_POINT',
  levelNo: node.levelNo,
  sortOrder: node.sortOrder ?? 0,
  status: node.status,
}, ...flattenSyllabus(node.children ?? [])])
const chapterLabel = (chapter: Chapter): string => `${chapter.chapterCode} · ${chapter.chapterName}`
const activeChapters = computed(() => chapters.value.filter(item => item.status === 'ENABLED').length)
const activeTags = computed(() => tags.value.filter(item => item.status === 'ENABLED').length)
const editorTitle = computed(() => editorKind.value === 'chapter'
  ? (chapterEditingId.value ? '编辑章节' : '新增章节')
  : (tagEditingId.value ? '编辑知识点' : '新增知识点'))

async function loadQualifications() {
  const page = await unwrapApiResponse<Page<Qualification>>(platformApiClient.get('/foundation/qualifications', { params: { pageNo: 1, pageSize: 100 } }))
  qualifications.value = list(page)
  if (!qualificationId.value && qualifications.value[0]) qualificationId.value = qualifications.value[0].id
}

async function loadSubjects() {
  subjects.value = []
  subjectId.value = undefined
  if (!qualificationId.value) return
  const page = await unwrapApiResponse<Page<Subject>>(platformApiClient.get(`/foundation/qualifications/${qualificationId.value}/subjects`, { params: { pageNo: 1, pageSize: 100 } }))
  subjects.value = list(page)
  if (subjects.value[0]) subjectId.value = subjects.value[0].id
}

async function loadData() {
  if (!qualificationId.value || !subjectId.value) return
  loading.value = true
  try {
    const [chapterPage, syllabusPage] = await Promise.all([
      unwrapApiResponse<Page<Chapter>>(platformApiClient.get(`/foundation/subjects/${subjectId.value}/chapter-nodes`, { params: { pageNo: 1, pageSize: 500 } })),
      unwrapApiResponse<Page<SyllabusNode>>(platformApiClient.get(`/foundation/subjects/${subjectId.value}/syllabus-nodes`, { params: { pageNo: 1, pageSize: 500 } })),
    ])
    chapters.value = list(chapterPage)
    tags.value = flattenSyllabus(list(syllabusPage))
  } catch {
    ElMessage.error('章节或知识点加载失败')
  } finally {
    loading.value = false
  }
}

function resetChapter() {
  chapterEditingId.value = null
  Object.assign(chapterForm, { parentId: undefined, chapterCode: '', chapterName: '', levelNo: 1, sortOrder: 0, status: 'ENABLED' })
}

function resetTag() {
  tagEditingId.value = null
  Object.assign(tagForm, { parentId: undefined, syllabusVersion: '2026', tagCode: '', tagName: '', tagType: 'KNOWLEDGE_POINT', levelNo: 1, sortOrder: 0, status: 'ENABLED' })
}

function openChapterCreate() { resetChapter(); editorKind.value = 'chapter'; editorOpen.value = true }
function openTagCreate() { resetTag(); editorKind.value = 'tag'; editorOpen.value = true }
function editChapter(row: Chapter) {
  chapterEditingId.value = row.id
  Object.assign(chapterForm, { parentId: row.parentId ?? undefined, chapterCode: row.chapterCode, chapterName: row.chapterName, levelNo: row.levelNo, sortOrder: row.sortOrder ?? 0, status: row.status })
  editorKind.value = 'chapter'
  editorOpen.value = true
}
function editTag(row: Tag) {
  tagEditingId.value = row.id
  Object.assign(tagForm, { parentId: row.parentId ?? undefined, syllabusVersion: row.syllabusVersion, tagCode: row.tagCode, tagName: row.tagName, tagType: row.tagType, levelNo: row.levelNo, sortOrder: row.sortOrder, status: row.status })
  editorKind.value = 'tag'
  editorOpen.value = true
}

async function saveChapter(closeAfterSave = true) {
  if (!qualificationId.value || !subjectId.value || !chapterForm.chapterCode.trim() || !chapterForm.chapterName.trim()) {
    ElMessage.warning('请填写章节编码和名称')
    return
  }
  saving.value = true
  try {
    const body = { qualificationId: qualificationId.value, subjectId: subjectId.value, ...chapterForm, chapterCode: chapterForm.chapterCode.trim(), chapterName: chapterForm.chapterName.trim() }
    if (chapterEditingId.value) await unwrapApiResponse(platformApiClient.put(`/admin/chapter-nodes/${chapterEditingId.value}`, body))
    else await unwrapApiResponse(platformApiClient.post('/admin/chapter-nodes', body))
    ElMessage.success('章节已保存')
    if (closeAfterSave) editorOpen.value = false
    resetChapter()
    await loadData()
  } catch {
    ElMessage.error('章节保存失败')
  } finally {
    saving.value = false
  }
}

async function saveTag(closeAfterSave = true) {
  if (!qualificationId.value || !subjectId.value || !tagForm.tagCode.trim() || !tagForm.tagName.trim()) {
    ElMessage.warning('请填写知识点编码和名称')
    return
  }
  saving.value = true
  try {
    const body = {
      qualificationId: qualificationId.value,
      subjectId: subjectId.value,
      parentId: tagForm.parentId,
      syllabusVersion: tagForm.syllabusVersion.trim(),
      nodeCode: tagForm.tagCode.trim(),
      nodeName: tagForm.tagName.trim(),
      levelNo: tagForm.levelNo,
      sortOrder: tagForm.sortOrder,
      status: tagForm.status,
    }
    if (tagEditingId.value) await unwrapApiResponse(platformApiClient.put(`/admin/syllabus-nodes/${tagEditingId.value}`, body))
    else await unwrapApiResponse(platformApiClient.post('/admin/syllabus-nodes', body))
    ElMessage.success('知识点已保存')
    if (closeAfterSave) editorOpen.value = false
    resetTag()
    await loadData()
  } catch {
    ElMessage.error('知识点保存失败')
  } finally {
    saving.value = false
  }
}

async function toggleChapter(row: Chapter) {
  chapterEditingId.value = row.id
  Object.assign(chapterForm, { parentId: row.parentId ?? undefined, chapterCode: row.chapterCode, chapterName: row.chapterName, levelNo: row.levelNo, sortOrder: row.sortOrder ?? 0, status: row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED' })
  await saveChapter(false)
}

async function toggleTag(row: Tag) {
  tagEditingId.value = row.id
  Object.assign(tagForm, { parentId: row.parentId ?? undefined, syllabusVersion: row.syllabusVersion, tagCode: row.tagCode, tagName: row.tagName, tagType: row.tagType, levelNo: row.levelNo, sortOrder: row.sortOrder, status: row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED' })
  await saveTag(false)
}

watch(qualificationId, () => { void loadSubjects() })
watch(subjectId, () => { void loadData() })
onMounted(() => { void loadQualifications() })
</script>

<template>
  <section class="taxonomy-page" v-loading="loading" data-testid="practice-taxonomy-page">
    <header class="taxonomy-header">
      <div>
        <p>练习内容配置 <span>/</span> 基础目录</p>
        <h1>章节与知识点</h1>
        <small>维护题库归类、章节练习与知识点专项所使用的基础数据。</small>
      </div>
      <el-button :loading="loading" @click="loadData"><el-icon><Refresh /></el-icon>刷新数据</el-button>
    </header>

    <section class="scope-panel">
      <div class="scope-copy"><span class="scope-icon"><el-icon><Connection /></el-icon></span><div><small>当前维护范围</small><strong>{{ qualifications.find(item => item.id === qualificationId)?.qualificationName || '请选择资格' }}</strong><p>{{ subjects.find(item => item.id === subjectId)?.subjectName || '请选择科目后加载目录' }}</p></div></div>
      <div class="scope-controls">
        <el-select v-model="qualificationId" placeholder="选择资格"><el-option v-for="item in qualifications" :key="item.id" :label="item.qualificationName" :value="item.id" /></el-select>
        <el-select v-model="subjectId" placeholder="选择科目" :disabled="!qualificationId"><el-option v-for="item in subjects" :key="item.id" :label="item.subjectName" :value="item.id" /></el-select>
      </div>
    </section>

    <section class="summary-grid">
      <div><span class="summary-icon is-blue"><el-icon><Collection /></el-icon></span><small>章节总数</small><strong>{{ chapters.length }}</strong><em>{{ activeChapters }} 个已启用</em></div>
      <div><span class="summary-icon is-violet"><el-icon><Tickets /></el-icon></span><small>知识点总数</small><strong>{{ tags.length }}</strong><em>{{ activeTags }} 个已启用</em></div>
      <div><span class="summary-icon is-green"><el-icon><Connection /></el-icon></span><small>当前科目</small><strong class="summary-subject">{{ subjects.find(item => item.id === subjectId)?.subjectName || '—' }}</strong><em>切换范围后自动刷新</em></div>
    </section>

    <div class="taxonomy-workspace">
      <section class="data-panel">
        <header class="panel-heading"><div><span class="panel-icon"><el-icon><Collection /></el-icon></span><div><small>学习目录</small><h2>章节结构</h2></div></div><el-button type="primary" plain @click="openChapterCreate"><el-icon><Plus /></el-icon>新增章节</el-button></header>
        <el-table :data="chapters" row-key="id" empty-text="当前科目暂无章节" :max-height="520">
          <el-table-column label="章节" min-width="260"><template #default="scope"><div class="item-title"><strong>{{ scope.row.chapterName }}</strong><small>{{ scope.row.chapterCode }} · 第 {{ scope.row.levelNo }} 层 · 排序 {{ scope.row.sortOrder ?? 0 }}</small></div></template></el-table-column>
          <el-table-column label="状态" width="112"><template #default="scope"><span class="state-tag" :class="scope.row.status === 'ENABLED' ? 'is-enabled' : 'is-disabled'"><i></i>{{ scope.row.status === 'ENABLED' ? '已启用' : '已停用' }}</span></template></el-table-column>
          <el-table-column label="操作" width="150" align="right"><template #default="scope"><el-button link type="primary" @click="editChapter(scope.row)"><el-icon><EditPen /></el-icon>编辑</el-button><el-button link @click="toggleChapter(scope.row)">{{ scope.row.status === 'ENABLED' ? '停用' : '启用' }}</el-button></template></el-table-column>
        </el-table>
      </section>

      <section class="data-panel">
        <header class="panel-heading"><div><span class="panel-icon panel-icon--violet"><el-icon><Tickets /></el-icon></span><div><small>专项训练标签</small><h2>知识点库</h2></div></div><el-button type="primary" plain @click="openTagCreate"><el-icon><Plus /></el-icon>新增知识点</el-button></header>
        <el-table :data="tags" row-key="id" empty-text="当前科目暂无知识点" :max-height="520">
          <el-table-column label="知识点" min-width="220"><template #default="scope"><div class="item-title"><strong>{{ scope.row.tagName }}</strong><small>{{ scope.row.tagCode }}</small></div></template></el-table-column>
          <el-table-column label="层级" width="114"><template #default="scope"><span class="type-tag">第 {{ scope.row.levelNo }} 层</span></template></el-table-column>
          <el-table-column label="状态" width="100"><template #default="scope"><span class="state-tag" :class="scope.row.status === 'ENABLED' ? 'is-enabled' : 'is-disabled'"><i></i>{{ scope.row.status === 'ENABLED' ? '启用' : '停用' }}</span></template></el-table-column>
          <el-table-column label="操作" width="150" align="right"><template #default="scope"><el-button link type="primary" @click="editTag(scope.row)"><el-icon><EditPen /></el-icon>编辑</el-button><el-button link @click="toggleTag(scope.row)">{{ scope.row.status === 'ENABLED' ? '停用' : '启用' }}</el-button></template></el-table-column>
        </el-table>
      </section>
    </div>

    <el-dialog v-model="editorOpen" :title="editorTitle" width="min(560px, calc(100vw - 32px))" append-to-body destroy-on-close class="taxonomy-dialog">
      <p class="dialog-description">{{ editorKind === 'chapter' ? '章节用于组织章节练习；顶级章节无需填写父章节 ID。' : '知识点用于专项练习、题目归类和学习数据统计。' }}</p>
      <el-form v-if="editorKind === 'chapter'" label-position="top" class="editor-form">
        <el-form-item label="父章节 ID（可选）"><el-input v-model.number="chapterForm.parentId" placeholder="顶级章节留空" /></el-form-item>
        <el-form-item label="章节编码"><el-input v-model="chapterForm.chapterCode" placeholder="例如 PM-SCOPE-01" /></el-form-item>
        <el-form-item label="章节名称"><el-input v-model="chapterForm.chapterName" placeholder="请输入章节名称" /></el-form-item>
        <el-form-item label="层级"><el-input-number v-model="chapterForm.levelNo" :min="1" controls-position="right" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="chapterForm.sortOrder" :min="0" controls-position="right" /></el-form-item>
        <el-form-item label="状态"><el-radio-group v-model="chapterForm.status"><el-radio-button value="ENABLED">启用</el-radio-button><el-radio-button value="DISABLED">停用</el-radio-button></el-radio-group></el-form-item>
      </el-form>
      <el-form v-else label-position="top" class="editor-form">
        <el-form-item label="父知识点 ID（可选）"><el-input v-model.number="tagForm.parentId" placeholder="顶级知识点留空" /></el-form-item>
        <el-form-item label="考纲版本"><el-input v-model="tagForm.syllabusVersion" placeholder="例如 2026" /></el-form-item>
        <el-form-item label="知识点编码"><el-input v-model="tagForm.tagCode" placeholder="例如 SYL-04-01" /></el-form-item>
        <el-form-item label="知识点名称"><el-input v-model="tagForm.tagName" placeholder="请输入知识点名称" /></el-form-item>
        <el-form-item label="层级"><el-input-number v-model="tagForm.levelNo" :min="1" controls-position="right" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="tagForm.sortOrder" :min="0" controls-position="right" /></el-form-item>
        <el-form-item label="状态"><el-radio-group v-model="tagForm.status"><el-radio-button value="ENABLED">启用</el-radio-button><el-radio-button value="DISABLED">停用</el-radio-button></el-radio-group></el-form-item>
      </el-form>
      <template #footer><el-button @click="editorOpen = false">取消</el-button><el-button type="primary" :loading="saving" @click="editorKind === 'chapter' ? saveChapter() : saveTag()">保存</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.taxonomy-page { min-width:0; padding:28px clamp(20px,3vw,46px) 48px; color:#172033; background:#f5f7fb; }.taxonomy-page > * + * { margin-top:18px; }
.taxonomy-header,.scope-panel,.scope-copy,.scope-controls,.panel-heading,.panel-heading>div { display:flex; align-items:center; }.taxonomy-header,.panel-heading { justify-content:space-between; gap:16px; }.taxonomy-header p { margin:0; color:#76859b; font-size:12px; }.taxonomy-header p span { margin:0 6px; color:#b1bdcd; }.taxonomy-header h1 { margin:5px 0; font-size:28px; letter-spacing:-.02em; }.taxonomy-header small { color:#748198; font-size:13px; }.taxonomy-header :deep(.el-button),.panel-heading :deep(.el-button) { min-height:38px; border-radius:9px; }
.scope-panel,.summary-grid>div,.data-panel { border:1px solid #dfe6f0; border-radius:16px; background:#fff; box-shadow:0 9px 28px rgba(31,45,75,.045); }.scope-panel { justify-content:space-between; gap:22px; padding:18px 22px; }.scope-copy { min-width:0; gap:12px; }.scope-copy>div { display:grid; min-width:0; gap:2px; }.scope-icon,.panel-icon,.summary-icon { display:grid; flex:0 0 auto; place-items:center; border-radius:11px; }.scope-icon { width:42px; height:42px; background:#eaf1ff; color:#2563eb; font-size:20px; }.scope-copy small,.scope-copy p { color:#8491a5; font-size:11px; }.scope-copy strong { overflow:hidden; color:#26334a; font-size:16px; text-overflow:ellipsis; white-space:nowrap; }.scope-copy p { overflow:hidden; margin:0; text-overflow:ellipsis; white-space:nowrap; }.scope-controls { flex:0 0 auto; gap:10px; }.scope-controls :deep(.el-select) { width:190px; }
.summary-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:14px; }.summary-grid>div { display:grid; min-width:0; padding:16px 18px; grid-template-columns:42px minmax(0,1fr) auto; grid-template-rows:auto auto; align-items:center; column-gap:11px; }.summary-icon { width:40px; height:40px; grid-row:1 / 3; background:#eaf1ff; color:#2563eb; font-size:18px; }.summary-icon.is-violet { background:#f0edff; color:#7155d9; }.summary-icon.is-green { background:#e8f8ef; color:#168653; }.summary-grid small { color:#8290a4; font-size:11px; }.summary-grid strong { overflow:hidden; color:#27344a; font-size:23px; text-overflow:ellipsis; white-space:nowrap; }.summary-grid .summary-subject { font-size:15px; }.summary-grid em { grid-column:2 / 4; overflow:hidden; color:#98a3b4; font-size:10px; font-style:normal; text-overflow:ellipsis; white-space:nowrap; }
.taxonomy-workspace { display:grid; min-width:0; grid-template-columns:repeat(2,minmax(0,1fr)); gap:18px; align-items:start; }.data-panel { min-width:0; overflow:hidden; }.panel-heading { min-height:66px; padding:0 18px; border-bottom:1px solid #edf0f5; }.panel-heading>div { min-width:0; gap:10px; }.panel-heading>div>div { display:grid; gap:2px; }.panel-heading small { color:#8190a4; font-size:11px; }.panel-heading h2 { margin:0; color:#29364c; font-size:16px; }.panel-icon { width:36px; height:36px; color:#2563eb; background:#eaf1ff; }.panel-icon--violet { color:#7155d9; background:#f0edff; }.data-panel :deep(.el-table) { width:100%; }.data-panel :deep(.el-table th.el-table__cell) { height:46px; color:#6b788c; background:#f8f9fb; font-size:11px; }.data-panel :deep(.el-table td.el-table__cell) { height:58px; color:#344057; }.data-panel :deep(.el-table__inner-wrapper::before) { display:none; }.item-title { display:grid; min-width:0; gap:4px; }.item-title strong,.item-title small { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }.item-title strong { color:#344057; font-size:13px; }.item-title small { color:#95a0b1; font-size:10px; }
.state-tag,.type-tag { display:inline-flex; align-items:center; gap:5px; padding:5px 8px; border-radius:999px; font-size:10px; font-weight:650; }.state-tag i { width:6px; height:6px; border-radius:50%; background:currentColor; }.state-tag.is-enabled { color:#168653; background:#e9f8f0; }.state-tag.is-disabled { color:#758196; background:#f0f3f6; }.type-tag { color:#526178; background:#edf2f8; }
.dialog-description { margin:0 0 16px; padding:10px 12px; border-radius:9px; background:#f4f7fb; color:#728096; font-size:12px; line-height:1.6; }.editor-form { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:0 14px; }.editor-form :deep(.el-form-item:nth-child(2)),.editor-form :deep(.el-form-item:nth-child(3)),.editor-form :deep(.el-form-item:last-child) { grid-column:1 / -1; }.editor-form :deep(.el-input-number),.editor-form :deep(.el-select) { width:100%; }.editor-form :deep(.el-input__wrapper),.editor-form :deep(.el-input-number),.editor-form :deep(.el-radio-button__inner) { border-radius:8px; }.taxonomy-dialog :deep(.el-dialog__body) { padding-top:18px; }.taxonomy-dialog :deep(.el-dialog__footer) { border-top:1px solid #edf0f5; }.taxonomy-dialog :deep(.el-dialog) { border-radius:16px; }
@media(max-width:1120px){.taxonomy-workspace{grid-template-columns:1fr}.data-panel :deep(.el-table){max-height:440px}.summary-grid{grid-template-columns:1fr 1fr}.summary-grid>div:last-child{grid-column:1 / -1}.scope-panel{align-items:flex-start;flex-direction:column}.scope-controls{width:100%}.scope-controls :deep(.el-select){flex:1;width:auto}}
@media(max-width:680px){.taxonomy-page{padding:20px 14px 36px}.taxonomy-header{align-items:flex-start;flex-direction:column}.taxonomy-header :deep(.el-button){width:100%}.scope-controls{align-items:stretch;flex-direction:column}.summary-grid{grid-template-columns:1fr}.summary-grid>div:last-child{grid-column:auto}.editor-form{grid-template-columns:1fr}.editor-form :deep(.el-form-item){grid-column:auto!important}.panel-heading{align-items:flex-start;padding:14px}.panel-heading :deep(.el-button){margin-top:1px}.data-panel :deep(.el-table){font-size:11px}}
</style>
