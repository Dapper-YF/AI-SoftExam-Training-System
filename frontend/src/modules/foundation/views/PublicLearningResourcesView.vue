<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  ArrowRight,
  Collection,
  Document,
  Download,
  Reading,
  Search,
} from '@element-plus/icons-vue'
import { platformApiClient, toSafeUserMessage, unwrapApiResponse } from '@/api/client'
import { useQualificationStore } from '@/stores/qualification'

type ResourceKind = '官方教程' | '电子资料'

interface ResourceChapter {
  title: string
  page: number
}

interface ResourceItem {
  id: number
  qualificationId: number
  title: string
  qualification: string
  kind: ResourceKind
  description: string
  chapters: ResourceChapter[]
  updated: string
  pageCount: number
  coverUrl: string
  pdfUrl: string
  previewEnabled: boolean
}

interface ResourceDto {
  id: number
  qualificationId: number
  title: string
  summary: string | null
  resourceType: string
  resourceCode: string
  updatedAt: string
}

interface VersionDto {
  id: number
  fileUrl: string
  pageCount: number | null
  coverUrl: string | null
  status: string
}

interface ChapterDto {
  chapterTitle: string
  pageNo: number
}

interface SearchHit {
  pageNo: number
  snippet: string
}

const qualificationStore = useQualificationStore()
const selectedQualification = ref('ALL')
const keyword = ref('')
const reading = ref<ResourceItem | null>(null)
const resources = ref<ResourceItem[]>([])
const loading = ref(false)
const errorMessage = ref('')
const selectedPage = ref(1)
const searching = ref(false)
const searchHits = ref<SearchHit[]>([])

const qualificationOptions = computed(() => [
  { id: 'ALL', label: '全部资格' },
  ...qualificationStore.enabledQualifications.map(item => ({ id: item.id, label: item.name })),
])

const filteredResources = computed(() => {
  const query = keyword.value.trim().toLocaleLowerCase()
  return resources.value.filter(item =>
    (selectedQualification.value === 'ALL' || String(item.qualificationId) === selectedQualification.value)
    && (!query || `${item.title}${item.description}${item.qualification}${item.kind}`.toLocaleLowerCase().includes(query)),
  )
})

const totalPages = computed(() => filteredResources.value.reduce((total, item) => total + item.pageCount, 0))
const tutorialCount = computed(() => filteredResources.value.filter(item => item.kind === '官方教程').length)
const activeQualificationLabel = computed(() =>
  qualificationOptions.value.find(item => item.id === selectedQualification.value)?.label ?? '全部资格',
)
const readerSource = computed(() =>
  reading.value ? `${reading.value.pdfUrl}#page=${selectedPage.value}&view=FitH` : '',
)

function qualificationName(id: number): string {
  return qualificationStore.enabledQualifications.find(item => String(item.id) === String(id))?.name ?? `资格 ${id}`
}

function resourceKind(type: string): ResourceKind {
  return type === 'OFFICIAL_TUTORIAL' ? '官方教程' : '电子资料'
}

function openReader(item: ResourceItem): void {
  if (!item.previewEnabled) {
    const link = document.createElement('a')
    link.href = item.pdfUrl
    link.download = item.title.endsWith('.pdf') ? item.title : `${item.title}.pdf`
    link.click()
    return
  }
  reading.value = item
  selectedPage.value = 1
  searchHits.value = []
}

function closeReader(): void {
  reading.value = null
  searchHits.value = []
}

function selectChapter(chapter: ResourceChapter): void {
  selectedPage.value = chapter.page
}

async function searchFullText(): Promise<void> {
  const query = keyword.value.trim()
  if (!query || !reading.value) return
  searching.value = true
  try {
    searchHits.value = await unwrapApiResponse<SearchHit[]>(
      platformApiClient.get(`/public/learning/resources/${reading.value.id}/search`, { params: { q: query } }),
    )
    if (searchHits.value[0]) {
      selectedPage.value = searchHits.value[0].pageNo
      ElMessage.success(`已定位到第 ${searchHits.value[0].pageNo} 页`)
    } else {
      ElMessage.info('没有找到匹配内容')
    }
  } catch (error) {
    ElMessage.error(toSafeUserMessage(error))
  } finally {
    searching.value = false
  }
}

async function loadResources(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const page = await unwrapApiResponse<{ items: ResourceDto[] }>(
      platformApiClient.get('/public/learning/resources', { params: { pageNo: 1, pageSize: 100 } }),
    )
    resources.value = (await Promise.all(page.items.map(async item => {
      const versions = await unwrapApiResponse<VersionDto[]>(
        platformApiClient.get(`/public/learning/resources/${item.id}/versions`),
      )
      const version = versions[0]
      const chapters = version
        ? await unwrapApiResponse<ChapterDto[]>(
            platformApiClient.get(`/public/learning/resources/${item.id}/versions/${version.id}/chapters`),
          )
        : []
      return {
        id: item.id,
        qualificationId: item.qualificationId,
        title: item.title,
        qualification: qualificationName(item.qualificationId),
        kind: resourceKind(item.resourceType),
        description: item.summary ?? '',
        chapters: chapters.map(chapter => ({ title: chapter.chapterTitle, page: chapter.pageNo })),
        updated: item.updatedAt?.slice(0, 10) ?? '',
        pageCount: version?.pageCount ?? 0,
        coverUrl: version?.coverUrl ?? '',
        pdfUrl: version?.fileUrl ?? '',
        previewEnabled: ['HIGH-PROJECT-MANAGER-4TH', 'HIGH-ESSAY-FOUNDATION-2026'].includes(item.resourceCode),
      }
    }))).filter(item => item.pdfUrl)
  } catch (error) {
    resources.value = []
    errorMessage.value = toSafeUserMessage(error)
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await qualificationStore.load({ anonymous: true }).catch(() => undefined)
  await loadResources()
})
</script>

<template>
  <main class="resources-page" data-testid="public-learning-resources-page">
    <section class="resources-shell">
      <section class="resources-toolbar" aria-label="资源筛选">
        <div class="qualification-tabs" role="tablist" aria-label="考试资格">
          <button
            v-for="item in qualificationOptions"
            :key="item.id"
            type="button"
            role="tab"
            :aria-selected="selectedQualification === item.id"
            :class="{ 'is-active': selectedQualification === item.id }"
            @click="selectedQualification = item.id"
          >
            {{ item.label }}
          </button>
        </div>
        <label class="resource-search">
          <el-icon><Search /></el-icon>
          <input
            v-model="keyword"
            type="search"
            placeholder="搜索教程、资料或知识点"
            aria-label="搜索学习资源"
            @keyup.enter="reading && searchFullText()"
          />
          <button v-if="keyword" type="button" aria-label="清空搜索" @click="keyword = ''">清除</button>
        </label>
      </section>

      <el-alert
        v-if="errorMessage"
        class="resource-alert"
        :title="errorMessage"
        type="error"
        :closable="false"
        show-icon
      >
        <template #default>
          <button type="button" class="retry-link" @click="loadResources">重新加载</button>
        </template>
      </el-alert>

      <section class="resource-workspace">
        <header class="section-heading">
          <div>
            <span class="section-kicker">{{ activeQualificationLabel }}</span>
            <h2>资源资料库</h2>
            <p>选择一本资料开始阅读，或直接下载到本地。</p>
          </div>
          <div class="section-stats">
            <span><strong>{{ filteredResources.length }}</strong> 项资料</span>
            <span><strong>{{ tutorialCount }}</strong> 本教程</span>
            <span><strong>{{ totalPages.toLocaleString() }}</strong> 页内容</span>
          </div>
        </header>

        <div v-loading="loading" class="resource-content" :aria-busy="loading">
          <div v-if="filteredResources.length" class="resource-card-grid">
            <article v-for="item in filteredResources" :key="item.id" class="resource-card">
              <button
                type="button"
                class="resource-cover"
                :aria-label="`${item.previewEnabled ? '阅读' : '下载'} ${item.title}`"
                @click="openReader(item)"
              >
                <img v-if="item.coverUrl" :src="item.coverUrl" :alt="`${item.title}封面`" />
                <span v-else><el-icon><Document /></el-icon><small>PDF</small></span>
                <em>{{ item.kind }}</em>
              </button>

              <div class="resource-card__body">
                <div class="resource-meta">
                  <span>{{ item.qualification }}</span>
                  <small>{{ item.updated ? `${item.updated} 更新` : '持续更新' }}</small>
                </div>
                <h3>{{ item.title }}</h3>
                <p>{{ item.description || '暂无资料简介，可打开资料查看完整内容。' }}</p>
                <div class="resource-details">
                  <span><el-icon><Collection /></el-icon>{{ item.pageCount }} 页</span>
                  <span><el-icon><Reading /></el-icon>{{ item.chapters.length }} 个章节</span>
                </div>
                <button type="button" class="resource-action" @click="openReader(item)">
                  <el-icon><component :is="item.previewEnabled ? Reading : Download" /></el-icon>
                  {{ item.previewEnabled ? '在线阅读' : '下载资料' }}
                  <el-icon class="action-arrow"><ArrowRight /></el-icon>
                </button>
              </div>
            </article>
          </div>

          <div v-else-if="!loading" class="resource-empty">
            <span><el-icon><Search /></el-icon></span>
            <strong>{{ errorMessage ? '资源暂时无法加载' : '没有找到匹配的资料' }}</strong>
            <p>{{ errorMessage ? '请稍后重试。' : '可以更换考试资格，或尝试使用更简短的关键词。' }}</p>
            <button v-if="keyword || selectedQualification !== 'ALL'" type="button" @click="keyword = ''; selectedQualification = 'ALL'">
              查看全部资源
            </button>
          </div>
        </div>
      </section>
    </section>

    <el-dialog
      :model-value="Boolean(reading)"
      width="min(1180px, calc(100% - 28px))"
      class="resource-reader-dialog"
      destroy-on-close
      append-to-body
      @close="closeReader"
    >
      <template #header>
        <div v-if="reading" class="reader-heading">
          <span class="heading-icon"><el-icon><Reading /></el-icon></span>
          <div><small>{{ reading.qualification }} · {{ reading.kind }}</small><strong>{{ reading.title }}</strong></div>
        </div>
      </template>

      <div v-if="reading" class="reader">
        <aside class="reader-catalog">
          <div class="catalog-heading"><span>章节目录</span><small>{{ reading.chapters.length }} 章</small></div>
          <div v-if="reading.chapters.length" class="catalog-list">
            <button
              v-for="(chapter, index) in reading.chapters"
              :key="`${chapter.title}-${chapter.page}`"
              type="button"
              :class="{ 'is-active': selectedPage === chapter.page }"
              @click="selectChapter(chapter)"
            >
              <span>{{ String(index + 1).padStart(2, '0') }}</span>
              <strong>{{ chapter.title }}</strong>
              <small>P{{ chapter.page }}</small>
            </button>
          </div>
          <div v-else class="catalog-empty">该资料暂未生成章节目录</div>
        </aside>

        <article class="reader-main">
          <div class="reader-bar">
            <div>
              <span>第 {{ selectedPage }} 页</span>
              <small>共 {{ reading.pageCount }} 页</small>
            </div>
            <a :href="reading.pdfUrl" download target="_blank" rel="noopener noreferrer">
              <el-icon><Download /></el-icon>下载 PDF
            </a>
          </div>
          <iframe :key="readerSource" class="reader-pdf" :src="readerSource" :title="`${reading.title} PDF 阅读器`" />
        </article>
      </div>
    </el-dialog>
  </main>
</template>

<style scoped>
.resources-page {
  min-height: calc(100vh - 56px);
  padding: 28px 24px 52px;
  background:
    radial-gradient(circle at 88% 2%, rgb(37 99 235 / 6%), transparent 28rem),
    #f5f7fb;
  color: #172033;
}
.resources-shell { width: min(1380px, 100%); margin: 0 auto; }
.section-kicker { color: #2563eb; font-size: 11px; font-weight: 750; letter-spacing: .1em; }
.resources-toolbar {
  display: flex;
  margin-top: 18px;
  padding: 12px 14px;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  border: 1px solid #dce5f0;
  border-radius: 14px;
  background: rgb(255 255 255 / 94%);
  box-shadow: 0 8px 24px rgb(15 23 42 / 4%);
}
.qualification-tabs { display: flex; min-width: 0; flex-wrap: wrap; gap: 5px; }
.qualification-tabs button {
  min-height: 36px;
  padding: 0 14px;
  border: 1px solid transparent;
  border-radius: 9px;
  background: transparent;
  color: #64748b;
  cursor: pointer;
  font-size: 12px;
  transition: .18s ease;
}
.qualification-tabs button:hover { background: #f8fafc; color: #2563eb; }
.qualification-tabs button.is-active { border-color: #bfdbfe; background: #eff6ff; color: #1d4ed8; font-weight: 700; }
.qualification-tabs button:focus-visible, .resource-action:focus-visible, .resource-search button:focus-visible {
  outline: 3px solid rgb(37 99 235 / 16%);
  outline-offset: 2px;
}
.resource-search {
  display: flex;
  width: min(340px, 32vw);
  min-height: 40px;
  padding: 0 12px;
  align-items: center;
  gap: 8px;
  border: 1px solid #dbe4f0;
  border-radius: 10px;
  background: #f8fafc;
  color: #94a3b8;
}
.resource-search:focus-within { border-color: #93b4f5; box-shadow: 0 0 0 3px rgb(37 99 235 / 8%); }
.resource-search input { min-width: 0; flex: 1; border: 0; outline: 0; background: transparent; color: #334155; font-size: 12px; }
.resource-search button { padding: 0; border: 0; background: transparent; color: #2563eb; cursor: pointer; font-size: 11px; }
.resource-alert { margin-top: 16px; border-radius: 11px; }
.retry-link { margin-left: 10px; border: 0; background: transparent; color: #dc2626; cursor: pointer; text-decoration: underline; }
.resource-workspace {
  margin-top: 18px;
  padding: 27px 28px 30px;
  border: 1px solid #dce5f0;
  border-radius: 18px;
  background: #fff;
  box-shadow: 0 14px 38px rgb(15 23 42 / 5%);
}
.section-heading { display: flex; margin-bottom: 22px; align-items: flex-end; justify-content: space-between; gap: 24px; }
.section-heading h2 { margin: 5px 0 0; color: #172033; font-size: 21px; }
.section-heading p { margin: 6px 0 0; color: #64748b; font-size: 12px; }
.section-stats { display: flex; gap: 8px; }
.section-stats span { padding: 7px 10px; border-radius: 8px; background: #f8fafc; color: #64748b; font-size: 11px; }
.section-stats strong { margin-right: 3px; color: #334155; }
.resource-content { min-height: 220px; }
.resource-card-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; }
.resource-card {
  display: flex;
  min-width: 0;
  min-height: 276px;
  padding: 19px;
  gap: 19px;
  border: 1px solid #e1e8f1;
  border-radius: 15px;
  background: #fff;
  transition: border-color .18s ease, box-shadow .18s ease, transform .18s ease;
}
.resource-card:hover { border-color: #bfd3f5; box-shadow: 0 14px 30px rgb(30 64 175 / 8%); transform: translateY(-2px); }
.resource-cover {
  position: relative;
  width: 150px;
  height: 214px;
  flex: 0 0 150px;
  padding: 0;
  overflow: hidden;
  border: 1px solid #d8e1ec;
  border-radius: 9px;
  background: linear-gradient(145deg, #edf4ff, #f8fafc);
  cursor: pointer;
  box-shadow: 0 8px 18px rgb(15 23 42 / 9%);
}
.resource-cover img { width: 100%; height: 100%; object-fit: contain; vertical-align: middle; }
.resource-cover > span { display: grid; height: 100%; place-content: center; gap: 8px; color: #2563eb; text-align: center; }
.resource-cover > span .el-icon { font-size: 34px; }
.resource-cover > span small { color: #64748b; font-size: 11px; letter-spacing: .14em; }
.resource-cover em { position: absolute; top: 9px; left: 9px; padding: 4px 7px; border-radius: 6px; background: rgb(23 32 51 / 82%); color: white; font-size: 9px; font-style: normal; }
.resource-card__body { display: flex; min-width: 0; flex: 1; flex-direction: column; }
.resource-meta { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.resource-meta span { padding: 4px 7px; border-radius: 6px; background: #eff6ff; color: #2563eb; font-size: 10px; font-weight: 650; }
.resource-meta small { color: #94a3b8; font-size: 10px; }
.resource-card h3 { margin: 13px 0 7px; color: #1e293b; font-size: 16px; line-height: 1.48; }
.resource-card p { display: -webkit-box; margin: 0; overflow: hidden; color: #64748b; font-size: 12px; line-height: 1.7; -webkit-box-orient: vertical; -webkit-line-clamp: 3; }
.resource-details { display: flex; margin-top: 13px; gap: 15px; color: #94a3b8; font-size: 10px; }
.resource-details span { display: inline-flex; align-items: center; gap: 4px; }
.resource-action {
  display: flex;
  min-height: 39px;
  margin-top: auto;
  padding: 0 12px;
  align-items: center;
  gap: 7px;
  border: 1px solid #d7e4f8;
  border-radius: 9px;
  background: #f4f8ff;
  color: #2563eb;
  cursor: pointer;
  font-size: 12px;
  font-weight: 650;
  transition: .18s ease;
}
.resource-action:hover { border-color: #2563eb; background: #2563eb; color: white; }
.action-arrow { margin-left: auto; }
.resource-empty { display: grid; min-height: 300px; place-content: center; justify-items: center; text-align: center; }
.resource-empty > span { display: grid; width: 58px; height: 58px; margin-bottom: 14px; place-items: center; border-radius: 18px; background: #eff6ff; color: #2563eb; font-size: 23px; }
.resource-empty strong { color: #334155; font-size: 15px; }
.resource-empty p { margin: 7px 0 15px; color: #94a3b8; font-size: 12px; }
.resource-empty button { padding: 8px 13px; border: 1px solid #bfdbfe; border-radius: 8px; background: #eff6ff; color: #2563eb; cursor: pointer; font-size: 11px; }
:global(.resource-reader-dialog) { overflow: hidden; border-radius: 18px; box-shadow: 0 28px 80px rgb(15 23 42 / 22%); }
:global(.resource-reader-dialog .el-dialog__header) { padding: 18px 22px; border-bottom: 1px solid #e5eaf1; }
:global(.resource-reader-dialog .el-dialog__body) { padding: 0; }
.reader-heading { display: flex; padding-right: 38px; align-items: center; gap: 12px; }
.heading-icon { display: grid; width: 40px; height: 40px; flex: 0 0 auto; place-items: center; border-radius: 12px; background: #eff6ff; color: #2563eb; font-size: 18px; }
.reader-heading > div { display: grid; min-width: 0; gap: 3px; }
.reader-heading small { color: #64748b; font-size: 10px; }
.reader-heading strong { overflow: hidden; color: #1e293b; font-size: 15px; text-overflow: ellipsis; white-space: nowrap; }
.reader { display: grid; height: min(740px, calc(100vh - 150px)); grid-template-columns: 250px minmax(0, 1fr); }
.reader-catalog { padding: 18px 12px; overflow-y: auto; border-right: 1px solid #e5eaf1; background: #f8fafc; }
.catalog-heading { display: flex; padding: 0 7px 12px; align-items: center; justify-content: space-between; color: #334155; font-size: 12px; font-weight: 700; }
.catalog-heading small { color: #94a3b8; font-size: 10px; font-weight: 400; }
.catalog-list { display: grid; gap: 4px; }
.catalog-list button { display: grid; width: 100%; padding: 10px 8px; grid-template-columns: 24px minmax(0, 1fr) auto; align-items: start; gap: 5px; border: 0; border-radius: 8px; background: transparent; color: #64748b; cursor: pointer; text-align: left; }
.catalog-list button:hover { background: #eef2f7; }
.catalog-list button.is-active { background: #e8f1ff; color: #1d4ed8; }
.catalog-list button > span, .catalog-list button > small { padding-top: 2px; color: #94a3b8; font-size: 9px; }
.catalog-list button strong { font-size: 11px; font-weight: 550; line-height: 1.5; }
.catalog-empty { padding: 28px 10px; color: #94a3b8; text-align: center; font-size: 11px; }
.reader-main { display: grid; min-width: 0; padding: 12px 16px 16px; grid-template-rows: 40px minmax(0, 1fr); gap: 8px; background: #eef1f5; }
.reader-bar { display: flex; padding: 0 4px; align-items: center; justify-content: space-between; }
.reader-bar > div { display: flex; align-items: baseline; gap: 7px; color: #334155; font-size: 12px; }
.reader-bar small { color: #94a3b8; font-size: 10px; }
.reader-bar a { display: inline-flex; align-items: center; gap: 5px; color: #2563eb; font-size: 11px; text-decoration: none; }
.reader-pdf { width: 100%; height: 100%; border: 1px solid #d7dee8; border-radius: 8px; background: white; }
@media (max-width: 900px) {
  .resources-page { padding: 18px 14px 36px; }
  .resources-toolbar { align-items: stretch; flex-direction: column; }
  .resource-search { width: 100%; }
  .section-heading { align-items: flex-start; flex-direction: column; }
  .resource-card-grid { grid-template-columns: 1fr; }
  .reader { grid-template-columns: 190px minmax(0, 1fr); }
}
@media (max-width: 600px) {
  .resources-page { padding-right: 10px; padding-left: 10px; }
  .qualification-tabs { flex-wrap: nowrap; overflow-x: auto; }
  .qualification-tabs button { flex: 0 0 auto; }
  .resource-workspace { padding: 22px 14px; border-radius: 15px; }
  .section-stats { width: 100%; overflow-x: auto; }
  .section-stats span { flex: 0 0 auto; }
  .resource-card { min-height: 0; padding: 14px; gap: 14px; }
  .resource-cover { width: 104px; height: 150px; flex-basis: 104px; }
  .resource-card h3 { margin-top: 10px; font-size: 14px; }
  .resource-card p { -webkit-line-clamp: 2; }
  .resource-details { display: none; }
  .resource-action { margin-top: 12px; }
  .reader { grid-template-columns: 1fr; }
  .reader-catalog { display: none; }
}
</style>
