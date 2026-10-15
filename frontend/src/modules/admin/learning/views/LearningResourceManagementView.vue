<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import {
  CircleCheck,
  Collection,
  Document,
  Edit,
  FolderOpened,
  Plus,
  Refresh,
  Search,
  Upload,
} from "@element-plus/icons-vue";
import { ElMessage, ElMessageBox } from "element-plus";
import type { UploadFile } from "element-plus";
import { platformApiClient, unwrapApiResponse } from "@/api/client";

type ResourceStatus = "DRAFT" | "PUBLISHED" | "OFFLINE";
type ResourceType =
  | "OFFICIAL_TUTORIAL"
  | "COURSE_HANDOUT"
  | "MIND_MAP"
  | "QUICK_REVIEW"
  | "ESSAY_MATERIAL";
interface Resource {
  id: number;
  qualificationId: number;
  resourceCode: string;
  resourceType: ResourceType;
  title: string;
  summary: string | null;
  status: ResourceStatus;
  copyrightStatus: string;
  currentVersionId: number | null;
  sortOrder: number;
  updatedAt: string;
}
interface PageResult {
  items: Resource[];
  total: number;
}
interface KnowledgePoint {
  id: number;
  pointCode: string;
  pointName: string;
  parentId: number | null;
  levelNo: number;
  status: string;
}
interface Version {
  id: number;
  versionNo: number;
  versionLabel: string | null;
  sourceName: string | null;
  fileName: string;
  fileUrl: string;
  pageCount: number | null;
  status: string;
}
interface Chapter {
  id: number;
  chapterCode: string;
  chapterTitle: string;
  levelNo: number;
  pageNo: number;
  endPageNo: number | null;
  sortOrder: number;
}
interface ExtractedChapter {
  chapterCode: string;
  chapterTitle: string;
  parentChapterCode: string | null;
  levelNo: number;
  pageNo: number;
  endPageNo: number | null;
  sortOrder: number;
}

const typeOptions: Array<{ value: ResourceType; label: string }> = [
  { value: "OFFICIAL_TUTORIAL", label: "官方教程" },
  { value: "COURSE_HANDOUT", label: "课程讲义" },
  { value: "MIND_MAP", label: "思维导图" },
  { value: "QUICK_REVIEW", label: "高频考点" },
  { value: "ESSAY_MATERIAL", label: "论文资料" },
];
const qualificationOptions = [
  { value: 1, label: "高项" },
  { value: 2, label: "中项" },
  { value: 3, label: "软设" },
  { value: 4, label: "系规" },
  { value: 5, label: "系分" },
];
const resources = ref<Resource[]>([]);
const total = ref(0);
const loading = ref(false);
const error = ref("");
const filters = ref({
  keyword: "",
  qualificationId: undefined as number | undefined,
  type: undefined as ResourceType | undefined,
  status: undefined as ResourceStatus | undefined,
});
const editorOpen = ref(false);
const editorMode = ref<"create" | "edit">("create");
const selected = ref<Resource | null>(null);
const form = ref({
  qualificationId: 1,
  resourceCode: "",
  resourceType: "OFFICIAL_TUTORIAL" as ResourceType,
  title: "",
  summary: "",
  copyrightStatus: "UNVERIFIED",
  sortOrder: 0,
});
const knowledgePoints = ref<KnowledgePoint[]>([]);
const selectedChapterId = ref<number | null>(null);
const chapterKnowledgePointIds = ref<number[]>([]);
const knowledgePointSaving = ref(false);
const versions = ref<Version[]>([]);
const chapters = ref<Chapter[]>([]);
const extractedChapters = ref<ExtractedChapter[]>([]);
const chaptersExtracting = ref(false);
const chaptersSaving = ref(false);
const activeTab = ref("resource");
const versionForm = ref({
  versionLabel: "",
  sourceName: "",
  sourceRef: "",
  fileUrl: "",
  fileName: "",
  mimeType: "application/pdf",
  fileSize: 1,
  fileSha256: "",
  pageCount: undefined as number | undefined,
  coverUrl: "",
  usageRestriction: "INTERNAL_ONLY",
});
const pdfFile = ref<File | null>(null);
const pdfUploading = ref(false);
const previewRebuilding = ref(false);
const saving = ref(false);
const publishedCount = computed(
  () => resources.value.filter((item) => item.status === "PUBLISHED").length,
);
const draftCount = computed(
  () => resources.value.filter((item) => item.status === "DRAFT").length,
);
const offlineCount = computed(
  () => resources.value.filter((item) => item.status === "OFFLINE").length,
);
const filtered = computed(() =>
  resources.value.filter(
    (item) =>
      !filters.value.keyword.trim() ||
      `${item.title}${item.resourceCode}`.includes(
        filters.value.keyword.trim(),
      ),
  ),
);
const typeLabel = (value: ResourceType) =>
  typeOptions.find((item) => item.value === value)?.label ?? value;
const qualificationLabel = (value: number) =>
  qualificationOptions.find((item) => item.value === value)?.label ??
  `资格 ${value}`;
const statusLabel = (value: ResourceStatus) =>
  ({ DRAFT: "草稿", PUBLISHED: "已发布", OFFLINE: "已下架" })[value];
async function load(): Promise<void> {
  loading.value = true;
  error.value = "";
  try {
    const result = await unwrapApiResponse<PageResult>(
      platformApiClient.get("/admin/learning/resources", {
        params: { pageNo: 1, pageSize: 100, ...filters.value },
      }),
    );
    resources.value = result.items;
    total.value = result.total;
  } catch {
    error.value = "资源列表加载失败，请稍后重试";
  } finally {
    loading.value = false;
  }
}
function openCreate(): void {
  editorMode.value = "create";
  selected.value = null;
  activeTab.value = "resource";
  versions.value = [];
  chapters.value = [];
  form.value = {
    qualificationId: 1,
    resourceCode: "",
    resourceType: "OFFICIAL_TUTORIAL",
    title: "",
    summary: "",
    copyrightStatus: "UNVERIFIED",
    sortOrder: 0,
  };
  editorOpen.value = true;
}
async function openEdit(item: Resource): Promise<void> {
  editorMode.value = "edit";
  selected.value = item;
  form.value = {
    qualificationId: item.qualificationId,
    resourceCode: item.resourceCode,
    resourceType: item.resourceType,
    title: item.title,
    summary: item.summary ?? "",
    copyrightStatus: item.copyrightStatus,
    sortOrder: item.sortOrder,
  };
  activeTab.value = "resource";
  editorOpen.value = true;
  await Promise.all([loadVersions(), loadChapters()]);
}
async function save(): Promise<void> {
  saving.value = true;
  try {
    if (editorMode.value === "create")
      await unwrapApiResponse(
        platformApiClient.post("/admin/learning/resources", form.value),
      );
    else if (selected.value)
      await unwrapApiResponse(
        platformApiClient.put(
          `/admin/learning/resources/${selected.value.id}`,
          form.value,
        ),
      );
    ElMessage.success("资源信息已保存");
    editorOpen.value = false;
    await load();
  } catch {
    ElMessage.error("保存失败，请检查填写内容");
  } finally {
    saving.value = false;
  }
}
async function changeStatus(
  item: Resource,
  status: "publish" | "offline",
): Promise<void> {
  try {
    await unwrapApiResponse(
      platformApiClient.post(`/admin/learning/resources/${item.id}/${status}`),
    );
    ElMessage.success(status === "publish" ? "资源已发布" : "资源已下架");
    await load();
  } catch {
    ElMessage.error("状态更新失败");
  }
}
async function remove(item: Resource): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定删除“${item.title}”？`, "删除资源", {
      type: "warning",
    });
    await unwrapApiResponse(
      platformApiClient.delete(`/admin/learning/resources/${item.id}`),
    );
    ElMessage.success("资源已删除");
    await load();
  } catch {
    /* cancel is intentionally silent */
  }
}
async function addVersion(): Promise<void> {
  if (!selected.value) return;
  try {
    await unwrapApiResponse(
      platformApiClient.post(
        `/admin/learning/resources/${selected.value.id}/versions`,
        versionForm.value,
      ),
    );
    ElMessage.success("资源版本已新增");
    await load();
  } catch {
    ElMessage.error("版本保存失败，请确认 PDF 元数据完整");
  }
}
function choosePdf(file: File | null): void {
  if (!file) {
    pdfFile.value = null;
    return;
  }
  if (
    !file.name.toLowerCase().endsWith(".pdf") ||
    file.type !== "application/pdf"
  ) {
    ElMessage.error("仅支持 PDF 文件");
    pdfFile.value = null;
    return;
  }
  if (file.size > 200 * 1024 * 1024) {
    ElMessage.error("PDF 文件不能超过 200 MiB");
    pdfFile.value = null;
    return;
  }
  pdfFile.value = file;
  versionForm.value.fileName = file.name;
}
async function uploadVersion(): Promise<void> {
  if (!selected.value || !pdfFile.value) {
    ElMessage.warning("请先选择 PDF 文件");
    return;
  }
  const data = new FormData();
  data.append("file", pdfFile.value);
  if (versionForm.value.versionLabel)
    data.append("versionLabel", versionForm.value.versionLabel);
  if (versionForm.value.sourceName)
    data.append("sourceName", versionForm.value.sourceName);
  pdfUploading.value = true;
  try {
    await unwrapApiResponse(
      platformApiClient.post(
        `/admin/learning/resources/${selected.value.id}/versions/upload`,
        data,
      ),
    );
    ElMessage.success("PDF 版本已上传");
    pdfFile.value = null;
    await loadVersions();
  } catch {
    ElMessage.error("PDF 上传失败，请确认文件格式和大小");
  } finally {
    pdfUploading.value = false;
  }
}
async function rebuildPreview(): Promise<void> {
  if (!selected.value || !versions.value[0]) {
    ElMessage.warning("当前资源没有可处理的 PDF 版本");
    return;
  }
  previewRebuilding.value = true;
  try {
    await unwrapApiResponse(
      platformApiClient.post(
        `/admin/learning/resources/${selected.value.id}/versions/${versions.value[0].id}/rebuild-preview`,
      ),
    );
    ElMessage.success("封面和页数已重新生成");
    await loadVersions();
  } catch {
    ElMessage.error("预览生成失败，请确认该版本是本地 PDF 文件");
  } finally {
    previewRebuilding.value = false;
  }
}
async function loadVersions(): Promise<void> {
  if (!selected.value) return;
  try {
    versions.value = await unwrapApiResponse<Version[]>(
      platformApiClient.get(
        `/admin/learning/resources/${selected.value.id}/versions`,
      ),
    );
  } catch {
    versions.value = [];
  }
}
async function loadChapters(): Promise<void> {
  const versionId = versions.value[0]?.id;
  if (!versionId) {
    chapters.value = [];
    return;
  }
  try {
    chapters.value = await unwrapApiResponse<Chapter[]>(
      platformApiClient.get(
        `/admin/learning/resources/versions/${versionId}/chapters`,
      ),
    );
    if (!selectedChapterId.value && chapters.value[0])
      selectedChapterId.value = chapters.value[0].id;
  } catch {
    chapters.value = [];
  }
}
async function extractChapters(): Promise<void> {
  const versionId = versions.value[0]?.id;
  if (!selected.value || !versionId) {
    ElMessage.warning("请先上传 PDF 版本");
    return;
  }
  chaptersExtracting.value = true;
  try {
    extractedChapters.value = await unwrapApiResponse<ExtractedChapter[]>(
      platformApiClient.post(
        `/admin/learning/resources/${selected.value.id}/versions/${versionId}/extract-chapters`,
      ),
    );
    ElMessage.success(`已提取 ${extractedChapters.value.length} 个候选章节`);
  } catch {
    ElMessage.error("目录提取失败，请确认 PDF 包含可识别文字");
  } finally {
    chaptersExtracting.value = false;
  }
}
async function saveExtractedChapters(): Promise<void> {
  const versionId = versions.value[0]?.id;
  if (!versionId || !extractedChapters.value.length) return;
  chaptersSaving.value = true;
  try {
    chapters.value = await unwrapApiResponse<Chapter[]>(
      platformApiClient.put(
        `/admin/learning/resources/versions/${versionId}/chapters`,
        extractedChapters.value,
      ),
    );
    extractedChapters.value = [];
    ElMessage.success("教程目录已保存");
  } catch {
    ElMessage.error("目录保存失败");
  } finally {
    chaptersSaving.value = false;
  }
}
async function loadKnowledgePoints(): Promise<void> {
  try {
    knowledgePoints.value = await unwrapApiResponse<KnowledgePoint[]>(
      platformApiClient.get("/admin/learning/resources/knowledge-points", {
        params: { qualificationId: form.value.qualificationId },
      }),
    );
  } catch {
    knowledgePoints.value = [];
  }
}
async function loadChapterKnowledgePoints(): Promise<void> {
  if (!selectedChapterId.value) return;
  try {
    const points = await unwrapApiResponse<KnowledgePoint[]>(
      platformApiClient.get(
        `/admin/learning/resources/chapters/${selectedChapterId.value}/knowledge-points`,
      ),
    );
    chapterKnowledgePointIds.value = points.map((point) => point.id);
  } catch {
    chapterKnowledgePointIds.value = [];
  }
}
async function saveChapterKnowledgePoints(): Promise<void> {
  if (!selectedChapterId.value) return;
  knowledgePointSaving.value = true;
  try {
    await unwrapApiResponse(
      platformApiClient.put(
        `/admin/learning/resources/chapters/${selectedChapterId.value}/knowledge-points`,
        {
          knowledgePointIds: chapterKnowledgePointIds.value,
        },
      ),
    );
    ElMessage.success("章节知识点标注已保存");
  } catch {
    ElMessage.error("章节知识点标注保存失败");
  } finally {
    knowledgePointSaving.value = false;
  }
}
onMounted(load);
</script>

<template>
  <main class="resource-admin" data-testid="learning-resource-management-page">
    <header class="resource-admin__heading">
      <div>
        <span class="eyebrow">资料管理 <i>/</i> 学习资源</span>
        <h1>学习资源管理</h1>
        <p>统一维护教材、讲义、考点资料及其 PDF 版本、章节目录与知识点关联。</p>
      </div>
      <div class="heading-actions">
        <el-button :icon="Refresh" @click="load">刷新</el-button
        ><el-button type="primary" :icon="Plus" @click="openCreate"
          >新增资源</el-button
        >
      </div>
    </header>

    <section class="resource-overview">
      <div class="overview-copy">
        <span class="overview-icon"><el-icon><Collection /></el-icon></span>
        <div><small>资源内容库</small><strong>{{ total }} 项学习资料</strong><p>按资格独立管理，并通过版本与目录组织内容</p></div>
      </div>
      <div class="overview-metric is-published"><span><el-icon><CircleCheck /></el-icon>已发布</span><strong>{{ publishedCount }}</strong></div>
      <div class="overview-metric"><span><el-icon><Edit /></el-icon>草稿</span><strong>{{ draftCount }}</strong></div>
      <div class="overview-metric is-offline"><span><el-icon><Document /></el-icon>已下架</span><strong>{{ offlineCount }}</strong></div>
    </section>

    <section class="resource-admin__toolbar">
      <div class="toolbar-heading"><span><el-icon><Search /></el-icon></span><div><strong>筛选资源</strong><small>按标题、编码或归属快速定位</small></div></div>
      <div class="toolbar-fields"><el-input
        v-model="filters.keyword"
        clearable
        placeholder="搜索标题或资源编码"
        @keyup.enter="load"
      ><template #prefix><el-icon><Search /></el-icon></template></el-input><el-select
        v-model="filters.qualificationId"
        clearable
        placeholder="全部资格"
        @change="load"
        ><el-option
          v-for="item in qualificationOptions"
          :key="item.value"
          v-bind="item" /></el-select
      ><el-select
        v-model="filters.type"
        clearable
        placeholder="全部类型"
        @change="load"
        ><el-option
          v-for="item in typeOptions"
          :key="item.value"
          v-bind="item" /></el-select
      ><el-select
        v-model="filters.status"
        clearable
        placeholder="全部状态"
        @change="load"
        ><el-option label="草稿" value="DRAFT" /><el-option
          label="已发布"
          value="PUBLISHED" /><el-option
          label="已下架"
          value="OFFLINE" /></el-select
      ><el-button type="primary" @click="load"><el-icon><Search /></el-icon>查询</el-button></div>
    </section>
    <el-alert v-if="error" :title="error" type="error" show-icon />
    <section class="resource-admin__table">
      <div class="table-heading"><div><span class="table-icon"><el-icon><Document /></el-icon></span><div><small>资源清单</small><h2>学习资料</h2></div></div><span>共 {{ total }} 个资源</span></div>
      <el-table v-loading="loading" :data="filtered" row-key="id"
        ><el-table-column
          prop="title"
          label="资源标题"
          min-width="240"
        ><template #default="scope"><div class="resource-title"><strong>{{ scope.row.title }}</strong><small>{{ scope.row.summary || "暂无资源简介" }}</small></div></template></el-table-column><el-table-column label="资格" width="90"
          ><template #default="scope">{{
            qualificationLabel(scope.row.qualificationId)
          }}</template></el-table-column
        ><el-table-column label="类型" width="120"
          ><template #default="scope">{{
            typeLabel(scope.row.resourceType)
          }}</template></el-table-column
        ><el-table-column
          prop="resourceCode"
          label="编码"
          width="150"
        /><el-table-column label="状态" width="95"
          ><template #default="scope"
            ><el-tag
              size="small"
              :type="
                scope.row.status === 'PUBLISHED'
                  ? 'success'
                  : scope.row.status === 'OFFLINE'
                    ? 'info'
                    : 'warning'
              "
              >{{ statusLabel(scope.row.status) }}</el-tag
            ></template
          ></el-table-column
        ><el-table-column label="操作" width="300" fixed="right"
          ><template #default="scope"
            ><el-button
              link
              type="primary"
              :icon="Edit"
              @click="openEdit(scope.row)"
              >编辑</el-button
            ><el-button
              v-if="scope.row.status !== 'PUBLISHED'"
              link
              type="success"
              @click="changeStatus(scope.row, 'publish')"
              >发布</el-button
            ><el-button v-else link @click="changeStatus(scope.row, 'offline')"
              >下架</el-button
            ><el-button link type="danger" @click="remove(scope.row)"
              >删除</el-button
            ></template
          ></el-table-column
        ></el-table
      >
      <div class="table-summary">当前显示 {{ filtered.length }} 项，资源总数 {{ total }} 项</div>
    </section>
    <el-dialog
      v-model="editorOpen"
      :title="editorMode === 'create' ? '新增学习资源' : '编辑学习资源'"
      width="min(900px, calc(100% - 24px))"
      destroy-on-close
    >
      <el-tabs v-model="activeTab"
        ><el-tab-pane label="基础信息" name="resource"
          ><el-form label-width="92px"
            ><el-form-item label="资格归属"
              ><el-select v-model="form.qualificationId"
                ><el-option
                  v-for="item in qualificationOptions"
                  :key="item.value"
                  v-bind="item" /></el-select></el-form-item
            ><el-form-item label="资源类型"
              ><el-select v-model="form.resourceType"
                ><el-option
                  v-for="item in typeOptions"
                  :key="item.value"
                  v-bind="item" /></el-select></el-form-item
            ><el-form-item label="资源编码"
              ><el-input
                v-model="form.resourceCode"
                :disabled="editorMode === 'edit'" /></el-form-item
            ><el-form-item label="标题"
              ><el-input v-model="form.title" /></el-form-item
            ><el-form-item label="简介"
              ><el-input
                v-model="form.summary"
                type="textarea"
                :rows="3" /></el-form-item
            ><el-form-item label="版权状态"
              ><el-select v-model="form.copyrightStatus"
                ><el-option label="未核验" value="UNVERIFIED" /><el-option
                  label="已授权"
                  value="AUTHORIZED" /><el-option
                  label="内部使用"
                  value="INTERNAL_ONLY" /><el-option
                  label="受限"
                  value="RESTRICTED" /></el-select></el-form-item
            ><el-form-item label="排序"
              ><el-input-number
                v-model="form.sortOrder"
                :min="0" /></el-form-item></el-form></el-tab-pane
        ><el-tab-pane
          label="PDF 版本"
          name="version"
          :disabled="editorMode === 'create'"
          ><div class="version-tools">
            <el-upload
              :auto-upload="false"
              :show-file-list="false"
              accept="application/pdf,.pdf"
              :on-change="
                (uploadFile: UploadFile) => choosePdf(uploadFile.raw ?? null)
              "
            >
              <el-button :icon="Upload">选择 PDF</el-button>
            </el-upload>
            <el-button
              type="primary"
              :loading="pdfUploading"
              :disabled="!pdfFile"
              @click="uploadVersion"
              >上传 PDF 版本</el-button
            ><el-button plain @click="addVersion">保存元数据</el-button
            ><el-button
              plain
              :loading="previewRebuilding"
              :disabled="!versions.length"
              @click="rebuildPreview"
              >重新生成封面</el-button
            ><span>{{ pdfFile?.name ?? "未选择文件" }}</span>
          </div>
          <el-table :data="versions" size="small"
            ><el-table-column
              prop="versionNo"
              label="版本"
              width="70" /><el-table-column
              prop="versionLabel"
              label="名称" /><el-table-column
              prop="fileName"
              label="文件" /><el-table-column
              prop="pageCount"
              label="页数"
              width="70" /><el-table-column
              prop="status"
              label="状态"
              width="90" /></el-table
          ><el-form label-width="92px" class="version-form"
            ><el-form-item label="版本名称"
              ><el-input v-model="versionForm.versionLabel" /></el-form-item
            ><el-form-item label="文件名"
              ><el-input
                v-model="versionForm.fileName"
                placeholder="教材.pdf" /></el-form-item
            ><el-form-item label="PDF 地址"
              ><el-input v-model="versionForm.fileUrl" /></el-form-item
            ><el-form-item label="来源"
              ><el-input v-model="versionForm.sourceName" /></el-form-item
            ><el-form-item label="文件 SHA256"
              ><el-input v-model="versionForm.fileSha256" /></el-form-item
            ><el-form-item label="页数"
              ><el-input-number
                v-model="versionForm.pageCount"
                :min="1" /></el-form-item></el-form></el-tab-pane
        ><el-tab-pane
          label="官方教程目录"
          name="chapters"
          :disabled="editorMode === 'create'"
          ><div class="version-tools">
            <el-button :loading="chaptersExtracting" @click="extractChapters"
              >从 PDF 提取目录</el-button
            ><el-button
              v-if="extractedChapters.length"
              type="primary"
              :loading="chaptersSaving"
              @click="saveExtractedChapters"
              >确认保存提取结果</el-button
            ><span>先提取候选目录，确认后再写入正式目录。</span>
          </div>
          <el-alert
            v-if="extractedChapters.length"
            title="以下为候选目录，请确认识别结果后保存"
            type="warning"
            :closable="false"
            class="chapter-preview-alert" />
          <el-table
            v-if="extractedChapters.length"
            :data="extractedChapters"
            size="small"
            class="chapter-preview-table"
          >
            <el-table-column prop="chapterCode" label="编码" width="120" />
            <el-table-column prop="chapterTitle" label="候选章节" />
            <el-table-column prop="levelNo" label="层级" width="70" />
            <el-table-column prop="pageNo" label="页码" width="70" />
          </el-table>
          <el-table :data="chapters" size="small"
            ><el-table-column
              prop="chapterCode"
              label="编码"
              width="120" /><el-table-column
              prop="chapterTitle"
              label="章节" /><el-table-column
              prop="levelNo"
              label="层级"
              width="70" /><el-table-column
              prop="pageNo"
              label="起始页"
              width="80" /><el-table-column
              prop="endPageNo"
              label="结束页"
              width="80" /></el-table></el-tab-pane
        ><el-tab-pane
          label="知识点树"
          name="knowledge"
          :disabled="editorMode === 'create'"
          ><div class="version-tools">
            <el-button :icon="FolderOpened" @click="loadKnowledgePoints"
              >刷新知识点</el-button
            ><el-select
              v-model="selectedChapterId"
              placeholder="选择教程章节"
              clearable
              @change="loadChapterKnowledgePoints"
            >
              <el-option
                v-for="chapter in chapters"
                :key="chapter.id"
                :label="`${chapter.chapterCode} ${chapter.chapterTitle}`"
                :value="chapter.id"
              /> </el-select
            ><el-button
              type="primary"
              :loading="knowledgePointSaving"
              :disabled="!selectedChapterId"
              @click="saveChapterKnowledgePoints"
              >保存章节标注</el-button
            ><span>按资格独立维护知识点，后续可关联教程章节。</span>
          </div>
          <el-checkbox-group
            v-model="chapterKnowledgePointIds"
            class="knowledge-point-list"
          >
            <el-checkbox
              v-for="point in knowledgePoints"
              :key="point.id"
              :value="point.id"
              >{{ point.pointCode }} {{ point.pointName }}</el-checkbox
            >
          </el-checkbox-group></el-tab-pane
        ></el-tabs
      ><template #footer
        ><el-button @click="editorOpen = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="save"
          >保存基础信息</el-button
        ></template
      >
    </el-dialog>
  </main>
</template>

<style scoped>
.resource-admin {
  min-height: 100%;
  padding: 34px 38px 48px;
  background: #f4f7fb;
  color: #2b3b55;
}
.resource-admin__heading {
  display: flex;
  max-width: 1380px;
  margin: 0 auto 24px;
  align-items: end;
  justify-content: space-between;
}
.eyebrow {
  color: #2f78be;
  font-size: 11px;
  letter-spacing: 0.12em;
}
.resource-admin h1 {
  margin: 7px 0 6px;
  color: #173d78;
  font-size: 30px;
}
.resource-admin__heading p {
  margin: 0;
  color: #7f8ca0;
  font-size: 13px;
}
.heading-actions {
  display: flex;
  gap: 10px;
}
.resource-admin__toolbar,
.resource-admin__table {
  max-width: 1380px;
  padding: 18px;
  margin: 0 auto 16px;
  border: 1px solid #dbe5f0;
  background: #fff;
}
.resource-admin__toolbar {
  display: grid;
  grid-template-columns: minmax(260px, 1fr) 150px 160px 140px auto;
  gap: 10px;
}
.resource-admin__table {
  padding: 0 18px 14px;
}
.table-summary {
  padding-top: 14px;
  color: #8b98aa;
  font-size: 12px;
}
.version-tools {
  display: flex;
  margin-bottom: 16px;
  align-items: center;
  gap: 12px;
  color: #8a97a8;
  font-size: 12px;
}
.version-form {
  max-width: 720px;
}
.resource-admin :deep(.el-alert) {
  max-width: 1380px;
  margin: 0 auto 16px;
}
.resource-admin :deep(.el-dialog__body) {
  padding-top: 8px;
}
.resource-admin :deep(.el-tree) {
  padding: 12px;
  border: 1px solid #e3eaf2;
}
.knowledge-point-list {
  display: grid;
  max-height: 360px;
  padding: 14px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 18px;
  overflow: auto;
  border: 1px solid #e3eaf2;
}
.knowledge-point-list :deep(.el-checkbox) {
  margin-right: 0;
}
.resource-admin :deep(.el-select),
.resource-admin :deep(.el-input-number) {
  width: 100%;
}
@media (max-width: 800px) {
  .resource-admin {
    padding: 20px 14px 32px;
  }
  .resource-admin__heading {
    align-items: start;
    flex-direction: column;
    gap: 16px;
  }
  .resource-admin__toolbar {
    grid-template-columns: 1fr 1fr;
  }
  .resource-admin__toolbar > :first-child,
  .resource-admin__toolbar > :last-child {
    grid-column: 1 / -1;
  }
}

/* 本页视觉升级：保留原有管理与上传流程，仅重组信息层级。 */
.resource-admin {
  min-height: calc(100vh - var(--platform-header-height));
  padding: 28px clamp(20px, 3vw, 46px) 48px;
  background: #f5f7fb;
  color: #172033;
}
.resource-admin__heading { margin-bottom: 18px; align-items: center; }
.eyebrow { color: #748198; font-size: 13px; letter-spacing: 0; }
.eyebrow i { margin: 0 5px; color: #b0b9c8; font-style: normal; }
.resource-admin h1 { margin: 6px 0 5px; color: #172033; font-size: 28px; line-height: 1.2; }
.resource-admin__heading p { color: #748198; }
.heading-actions :deep(.el-button) { min-height: 39px; margin: 0; border-radius: 9px; }
.resource-overview {
  display: flex;
  max-width: 1380px;
  padding: 20px 24px;
  margin: 0 auto 16px;
  align-items: center;
  gap: 22px;
  border: 1px solid #dce4f0;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 10px 30px rgba(31, 45, 75, 0.05);
}
.overview-copy { display: flex; flex: 1; align-items: center; gap: 13px; }
.overview-copy > div { display: grid; gap: 2px; }
.overview-icon, .toolbar-heading > span, .table-icon {
  display: grid;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 12px;
  background: #eaf1ff;
  color: #2563eb;
}
.overview-icon { width: 44px; height: 44px; font-size: 20px; }
.overview-copy small, .overview-metric span { color: #7b879b; font-size: 12px; }
.overview-copy strong { color: #172033; font-size: 19px; }
.overview-copy p { margin: 0; color: #909aac; font-size: 12px; }
.overview-metric { display: grid; min-width: 112px; padding-left: 22px; gap: 4px; border-left: 1px solid #e7ebf1; }
.overview-metric span { display: flex; align-items: center; gap: 5px; }
.overview-metric strong { color: #334158; font-size: 24px; }
.overview-metric.is-published strong { color: #168653; }
.overview-metric.is-offline strong { color: #8490a3; }
.resource-admin__toolbar, .resource-admin__table {
  overflow: hidden;
  border: 1px solid #dfe5ef;
  border-radius: 15px;
  background: #fff;
  box-shadow: 0 8px 26px rgba(31, 45, 75, 0.04);
}
.resource-admin__toolbar { display: flex; padding: 16px 20px; align-items: center; justify-content: space-between; gap: 20px; }
.toolbar-heading, .toolbar-fields, .table-heading, .table-heading > div { display: flex; align-items: center; }
.toolbar-heading { min-width: max-content; gap: 10px; }
.toolbar-heading > span, .table-icon { width: 36px; height: 36px; border-radius: 10px; }
.toolbar-heading > div { display: grid; gap: 2px; }
.toolbar-heading strong { font-size: 14px; }
.toolbar-heading small { color: #8792a4; font-size: 11px; }
.toolbar-fields { flex: 1; justify-content: flex-end; gap: 8px; }
.toolbar-fields > :first-child { width: min(280px, 28%); }
.toolbar-fields .el-select { width: 130px; }
.toolbar-fields :deep(.el-input__wrapper), .toolbar-fields :deep(.el-select__wrapper) { min-height: 38px; border-radius: 9px; }
.toolbar-fields :deep(.el-button) { min-height: 38px; margin: 0; border-radius: 9px; }
.resource-admin__table { padding: 0 0 14px; }
.table-heading { min-height: 66px; padding: 0 20px; justify-content: space-between; border-bottom: 1px solid #edf0f5; }
.table-heading > div { gap: 10px; }
.table-heading > div > div { display: grid; gap: 1px; }
.table-heading small, .table-heading > span { color: #8190a4; font-size: 11px; }
.table-heading h2 { margin: 0; color: #273247; font-size: 16px; }
.resource-admin__table :deep(.el-table th.el-table__cell) { height: 48px; color: #68758a; background: #f8f9fb; font-weight: 600; }
.resource-admin__table :deep(.el-table td.el-table__cell) { height: 58px; color: #344057; }
.resource-admin__table :deep(.el-table__inner-wrapper::before) { display: none; }
.resource-title { display: grid; gap: 3px; }
.resource-title strong { color: #2c384d; font-size: 13px; }
.resource-title small { overflow: hidden; max-width: 420px; color: #8a95a7; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.table-summary { padding: 14px 20px 0; border-top: 1px solid #f0f2f6; }
.version-tools { padding: 12px; flex-wrap: wrap; border: 1px solid #e2e7ef; border-radius: 10px; background: #f8f9fb; }
.resource-admin :deep(.el-dialog) { overflow: hidden; border: 1px solid rgba(148, 163, 184, 0.24); border-radius: 18px; box-shadow: 0 24px 70px rgba(15, 23, 42, 0.18); }
.resource-admin :deep(.el-dialog__header) { padding: 20px 22px 16px; border-bottom: 1px solid #edf0f5; }
.resource-admin :deep(.el-dialog__title) { color: #172033; font-weight: 650; }
.resource-admin :deep(.el-dialog__footer) { padding: 14px 22px 18px; border-top: 1px solid #edf0f5; }
.resource-admin :deep(.el-input__wrapper), .resource-admin :deep(.el-select__wrapper), .resource-admin :deep(.el-textarea__inner) { border-radius: 9px; }
@media (max-width: 1050px) {
  .resource-admin__toolbar { align-items: flex-start; flex-direction: column; }
  .toolbar-fields { width: 100%; justify-content: flex-start; flex-wrap: wrap; }
  .toolbar-fields > :first-child { width: min(320px, 100%); }
}
@media (max-width: 800px) {
  .resource-overview { align-items: flex-start; flex-wrap: wrap; }
  .overview-copy { flex-basis: 100%; }
  .overview-metric { min-width: calc(33.333% - 15px); padding: 10px 0 0; border-top: 1px solid #e7ebf1; border-left: 0; }
  .toolbar-fields > :first-child, .toolbar-fields .el-select { width: calc(50% - 4px); }
  .toolbar-fields :deep(.el-button) { width: 100%; }
  .knowledge-point-list { grid-template-columns: 1fr; }
}
</style>
