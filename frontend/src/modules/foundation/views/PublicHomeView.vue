<script setup lang="ts">
import { computed, onMounted, ref, watch, type Component } from 'vue'
import type { RouteLocationRaw } from 'vue-router'
import {
  ArrowRight,
  Calendar,
  Clock,
  DataAnalysis,
  Document,
  EditPen,
  Monitor,
  Reading,
  RefreshRight,
  Select,
  Tickets,
  TrendCharts,
} from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { getPlatformAuthGateway } from '../api/gateway'
import { getPortalContentGateway } from '../api/portal-content'
import type { PortalContentItem, PortalNewsCategory } from '../api/portal-content.gateway'
import { listRegionalSchedules, type RegionalSchedule, type ScheduleNodeType } from '../api/regional-schedule'
import { EXAM_REGIONS } from '../exam-regions'

interface PracticeAction {
  label: string
  description: string
  icon: Component
  tone: string
  to: RouteLocationRaw
  testId?: string
}

const authStore = useAuthStore()
const selectedRegion = ref(localStorage.getItem('portal-region-code') ?? '')
const scheduleItems = ref<RegionalSchedule[]>([])
const regions = [['', '选择省份'], ...EXAM_REGIONS]
const scheduleLabels: Record<ScheduleNodeType, string> = { REGISTRATION: '报名时间', ADMISSION_TICKET: '准考证打印', EXAM: '考试时间', RESULT: '成绩查询' }
const timelineItems = computed(() => (['REGISTRATION', 'ADMISSION_TICKET', 'EXAM', 'RESULT'] as ScheduleNodeType[]).map(type => {
  const item = scheduleItems.value.find(value => value.nodeType === type)
  return { id: type, title: scheduleLabels[type], schedule: item?.scheduledAt ? new Date(item.scheduledAt).toLocaleDateString('zh-CN') : '以官方公告为准', status: item?.scheduledAt ? '已发布' : '待公布' }
}))
const publishedExamSchedule = computed(() => scheduleItems.value.find(item => item.nodeType === 'EXAM' && item.scheduledAt) ?? null)
const examCountdownDays = computed<number | null>(() => {
  const value = publishedExamSchedule.value?.scheduledAt
  if (!value) return null
  const parts = value.slice(0, 10).split('-').map(Number)
  const year = parts[0] ?? 0
  const month = parts[1] ?? 0
  const day = parts[2] ?? 0
  const examDate = new Date(year, month - 1, day)
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return Math.max(0, Math.ceil((examDate.getTime() - today.getTime()) / 86_400_000))
})
const examCountdownLabel = computed(() => {
  const period = publishedExamSchedule.value?.examPeriodCode
  if (!period) return '距离下一场考试'
  const [year, half] = period.split('-')
  return `距${year}${half === 'H1' ? '上半年' : '下半年'}软考`
})
const newsTab = ref<PortalNewsCategory>('REGISTRATION')
const newsTabs: Array<{ value: PortalNewsCategory; label: string }> = [
  { value: 'REGISTRATION', label: '报名动态' },
  { value: 'POLICY', label: '政策资讯' },
]
const newsItems = ref<PortalContentItem[]>([])
const resourceItems = ref<PortalContentItem[]>([])
const newsStatus = ref<'loading' | 'ready' | 'empty' | 'error'>('loading')
const resourceStatus = ref<'loading' | 'ready' | 'empty' | 'error'>('loading')

const visibleNews = computed(() => newsItems.value)

const practiceActions: PracticeAction[] = [
  { label: '每日练习', description: '用短题组保持节奏', icon: Select, tone: 'blue', to: { path: '/practice', query: { mode: 'DAILY' } }, testId: 'practice-daily-link' },
  { label: '章节练习', description: '按教材章节系统推进', icon: Reading, tone: 'indigo', to: { path: '/practice', query: { mode: 'CHAPTER' } }, testId: 'practice-chapter-link' },
  { label: '知识点专项', description: '聚焦薄弱考点练习', icon: DataAnalysis, tone: 'teal', to: { path: '/practice', query: { mode: 'KNOWLEDGE_POINT' } }, testId: 'practice-knowledge-link' },
  { label: '历年真题', description: '熟悉真实试题风格', icon: Tickets, tone: 'orange', to: { path: '/practice', query: { mode: 'PAST_EXAM' } }, testId: 'practice-paper-link' },
  { label: '模拟试题', description: '进行阶段性综合检测', icon: EditPen, tone: 'violet', to: '/exam-simulator' },
  { label: '机考模拟', description: '提前适应考试流程', icon: Monitor, tone: 'blue', to: '/exam-simulator' },
  { label: '错题复习', description: '登录后回顾薄弱题目', icon: RefreshRight, tone: 'red', to: { path: '/login', query: { redirect: '/app/practice/review' } }, testId: 'practice-mistake-link' },
]

const capabilities = [
  { title: '智能学习路径', description: '基于薄弱点推荐学习顺序', icon: Reading, tone: 'blue' },
  { title: 'AI答案解析', description: '梳理解题思路与关键考点', icon: Document, tone: 'indigo' },
  { title: '学习进度跟踪', description: '登录后查看真实学习记录', icon: TrendCharts, tone: 'teal' },
  { title: '多端同步学习', description: '支持常用设备连续学习', icon: Monitor, tone: 'blue' },
]

async function loadNews(): Promise<void> {
  newsStatus.value = 'loading'
  try {
    newsItems.value = await getPortalContentGateway().listNews(newsTab.value)
    newsStatus.value = newsItems.value.length ? 'ready' : 'empty'
  } catch {
    newsItems.value = []
    newsStatus.value = 'error'
  }
}

async function loadResources(): Promise<void> {
  resourceStatus.value = 'loading'
  try {
    const gateway = getPortalContentGateway()
    resourceItems.value = await gateway.listResources()
    if (resourceItems.value.length < 6) {
      try {
        const policyItems = await gateway.listNews('POLICY', 6)
        const knownIds = new Set(resourceItems.value.map((item) => item.id))
        resourceItems.value = [...resourceItems.value, ...policyItems.filter((item) => !knownIds.has(item.id))].slice(0, 6)
      } catch {
        // The primary official-resource response remains usable when the supplement is unavailable.
      }
    }
    resourceStatus.value = resourceItems.value.length ? 'ready' : 'empty'
  } catch {
    resourceItems.value = []
    resourceStatus.value = 'error'
  }
}

async function loadRegionalSchedule(regionCode?: string): Promise<void> {
  const profile = !regionCode && authStore.isAuthenticated && authStore.roles.includes('STUDENT') ? await getPlatformAuthGateway().getProfile().catch(() => null) : null
  const code = regionCode ?? profile?.examRegionCode ?? selectedRegion.value
  if (!code) { scheduleItems.value = []; return }
  selectedRegion.value = code
  if (!profile?.examRegionCode) localStorage.setItem('portal-region-code', code)
  scheduleItems.value = await listRegionalSchedules(code).catch(() => [])
}

onMounted(() => {
  void loadNews()
  void loadResources()
  void loadRegionalSchedule()
})

watch(newsTab, () => void loadNews())
</script>

<template>
  <main class="public-home" data-testid="public-home-page">
    <div class="public-portal-grid" data-testid="public-portal-grid">
      <section class="portal-center-column">
        <article id="practice-start" class="portal-card practice-entry-card">
          <div class="practice-entry-main">
            <header class="portal-section-heading">
              <div><h2>选择练习方式</h2></div>
              <small>公开浏览，作答与记录时登录</small>
            </header>
            <div class="practice-action-grid">
              <router-link
                v-for="action in practiceActions"
                :key="action.label"
                :to="action.to"
                :data-testid="action.testId"
                class="practice-action"
              >
                <span class="practice-action__icon" :data-tone="action.tone">
                  <el-icon><component :is="action.icon" /></el-icon>
                </span>
                <span><strong>{{ action.label }}</strong><small>{{ action.description }}</small></span>
              </router-link>
            </div>
          </div>
          <aside class="quick-start-panel">
            <span>推荐起步</span>
            <h2>从每日练习开始</h2>
            <el-icon class="quick-start-panel__watermark"><Select /></el-icon>
            <p>用短题组快速进入状态，再逐步深入章节与专项训练。</p>
            <router-link to="/practice?mode=DAILY">开始练习<el-icon><ArrowRight /></el-icon></router-link>
          </aside>
        </article>

        <article id="news" class="portal-card news-card">
          <header class="tabbed-heading">
            <div><span class="section-step">备考动态</span><div role="tablist" aria-label="资讯分类"><button v-for="tab in newsTabs" :key="tab.value" type="button" role="tab" :aria-selected="newsTab === tab.value" :class="{ 'is-active': newsTab === tab.value }" @click="newsTab = tab.value">{{ tab.label }}</button></div></div>
          </header>
          <ul v-if="newsStatus === 'ready'" class="news-list" data-testid="portal-news-list">
            <li v-for="item in visibleNews" :key="item.id"><span class="news-list__bullet" aria-hidden="true" /><a :href="item.sourceUrl" target="_blank" rel="noopener noreferrer" :title="item.summary">{{ item.title }}</a><em>{{ item.tag }}</em><time>{{ item.publishedAt.slice(0, 10) }}</time></li>
          </ul>
          <p v-else-if="newsStatus === 'loading'" class="portal-content-state">正在加载官方资讯</p>
          <p v-else-if="newsStatus === 'empty'" class="portal-content-state">暂无最新官方资讯</p>
          <p v-else class="portal-content-state portal-content-state--error">官方资讯暂时无法加载</p>
        </article>

        <article id="resources" class="portal-card resources-card">
          <header class="portal-section-heading portal-section-heading--tabs">
            <div><span class="section-step">学习资料</span><h2>官方资料与备考参考</h2></div>
            <router-link to="/#resources">更多资料<el-icon><ArrowRight /></el-icon></router-link>
          </header>
          <ul v-if="resourceStatus === 'ready'" class="resource-list" data-testid="portal-resource-list">
            <li v-for="item in resourceItems" :key="item.id">
              <el-icon><Document /></el-icon>
              <a :href="item.sourceUrl" target="_blank" rel="noopener noreferrer" :title="item.summary">{{ item.title }}</a>
              <em :data-tag="item.tag">{{ item.tag }}</em>
              <time>{{ item.publishedAt.slice(0, 10) }}</time>
            </li>
          </ul>
          <p v-else-if="resourceStatus === 'loading'" class="portal-content-state">正在加载官方资料</p>
          <p v-else-if="resourceStatus === 'empty'" class="portal-content-state">暂无官方资料</p>
          <p v-else class="portal-content-state portal-content-state--error">官方资料暂时无法加载</p>
        </article>
      </section>

      <aside class="portal-right-column">
        <article class="portal-card exam-schedule-card">
          <header class="portal-section-heading">
            <h2><el-icon><Clock /></el-icon>考试倒计时</h2>
            <router-link to="/#timeline">更多考试<el-icon><ArrowRight /></el-icon></router-link>
          </header>
          <div v-if="examCountdownDays !== null" class="schedule-countdown" data-testid="exam-countdown">
            <strong>{{ examCountdownLabel }}</strong>
            <div class="schedule-countdown__value"><span v-for="(digit, index) in String(examCountdownDays).padStart(3, '0')" :key="index">{{ digit }}</span><em>天</em></div>
            <small>考试时间：{{ timelineItems.find(item => item.id === 'EXAM')?.schedule }}</small>
          </div>
          <div v-else class="schedule-placeholder">
            <strong>考试时间安排</strong>
            <div class="schedule-placeholder__steps" aria-label="考试阶段">
              <span>报名</span><span>准考证</span><span>考试</span><span>查分</span>
            </div>
            <p>时间安排以官方公告为准</p>
          </div>
        </article>

        <article id="timeline" class="portal-card timeline-card">
          <header class="portal-section-heading">
            <h2><el-icon><Calendar /></el-icon>重要时间节点</h2>
            <el-select v-model="selectedRegion" class="timeline-region" placeholder="选择省份" @change="loadRegionalSchedule">
              <el-option v-for="item in regions" :key="item[0]" :value="item[0]" :label="item[1]" />
            </el-select>
          </header>
          <ol class="timeline-list">
            <li v-for="item in timelineItems" :key="item.id">
              <span class="timeline-list__dot" aria-hidden="true" />
              <div><strong>{{ item.title }}</strong><small>{{ item.schedule }}</small></div>
              <em>{{ item.status }}</em>
            </li>
          </ol>
        </article>

        <article class="portal-card study-plan-card" data-testid="anonymous-study-plan">
          <header class="portal-section-heading">
            <h2>每日学习计划</h2>
            <router-link :to="{ path: '/login', query: { redirect: '/app/practice/reports' } }">登录制定</router-link>
          </header>
          <div class="study-plan-placeholder">
            <el-icon><Reading /></el-icon>
            <strong>登录后生成个性化学习计划</strong>
            <p>基于真实练习记录安排每日练习、章节复习、知识点专项与错题回顾。</p>
          </div>
          <router-link class="study-plan-login" :to="{ path: '/login', query: { redirect: '/app/practice/reports' } }">
            登录查看计划
          </router-link>
        </article>
      </aside>
    </div>

    <section class="portal-capabilities" aria-label="平台能力">
      <article v-for="item in capabilities" :key="item.title">
        <span class="capability-icon" :data-tone="item.tone"><el-icon><component :is="item.icon" /></el-icon></span>
        <div><strong>{{ item.title }}</strong><p>{{ item.description }}</p></div>
      </article>
    </section>
  </main>
</template>

<style scoped>
.public-home {
  width: 100%;
  padding: 12px 16px 18px;
  background: #f5f7fa;
  color: #273244;
}

.public-portal-grid {
  display: grid;
  width: min(100%, 1456px);
  margin: 0 auto;
  align-items: stretch;
  gap: 12px;
  grid-template-columns: minmax(0, 1fr) minmax(320px, 368px);
}

.portal-card {
  overflow: hidden;
  border: 1px solid #dfe5ec;
  border-radius: 6px;
  background: #fff;
  box-shadow: 0 1px 3px rgb(26 55 91 / 4%);
}

.portal-content-state { margin: 18px 8px; color: #687386; font-size: 14px; }
.portal-content-state--error { color: #b54708; }

.qualification-directory {
  display: none;
  min-height: 808px;
  padding: 12px 16px 14px;
  flex-direction: column;
}

.qualification-source {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}

.portal-section-heading small {
  color: #8490a0;
  font-size: 12px;
}

.qualification-group {
  padding: 12px 0 8px;
  border-bottom: 1px solid #e5e9ef;
}

.qualification-group header {
  display: flex;
  align-items: center;
  gap: 10px;
}

.qualification-group h2,
.portal-section-heading h2,
.tabbed-heading button {
  margin: 0;
  color: #1d2736;
  font-size: 17px;
  font-weight: 700;
}

.qualification-group__icon,
.practice-action__icon,
.capability-icon {
  display: grid;
  place-items: center;
  background: #1769e0;
  color: #fff;
}

.qualification-group__icon {
  width: 24px;
  height: 24px;
  border-radius: 6px;
}

.qualification-group__icon[data-tone="intermediate"] { background: #0eb58b; }
.qualification-group__icon[data-tone="primary"] { background: #ff7a1a; }

.qualification-group ul,
.news-list,
.resource-list,
.timeline-list {
  padding: 0;
  margin: 8px 0 0;
  list-style: none;
}

.qualification-group li + li { margin-top: 1px; }

.qualification-group button {
  display: grid;
  width: 100%;
  min-height: 31px;
  padding: 0 6px;
  align-items: center;
  border: 1px solid transparent;
  border-radius: 4px;
  background: transparent;
  color: #394557;
  cursor: pointer;
  grid-template-columns: 12px minmax(0, 1fr) auto;
  text-align: left;
}

.qualification-group button:hover,
.qualification-group button.is-active {
  border-color: #b8d0f5;
  background: #f2f7ff;
  color: #1261cd;
}

.qualification-group button strong {
  overflow: hidden;
  font-size: 14px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.qualification-group button small {
  padding: 1px 4px;
  border-radius: 3px;
  background: #ff4d4f;
  color: #fff;
  font-size: 10px;
}

.qualification-all-link {
  display: inline-flex;
  width: 100%;
  min-height: 38px;
  margin-top: auto;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px solid #bcd3f6;
  border-radius: 5px;
  color: #1769e0;
  font-size: 14px;
  font-weight: 600;
  text-decoration: none;
}

.qualification-local-state {
  display: grid;
  min-height: 260px;
  padding: 20px 4px;
  align-content: center;
  gap: 8px;
  color: #637083;
  text-align: center;
}

.qualification-local-state--error strong { color: #c24142; }

.qualification-local-state button {
  width: max-content;
  min-height: 34px;
  padding: 0 16px;
  margin: 4px auto 0;
  border: 1px solid #1769e0;
  border-radius: 5px;
  background: #fff;
  color: #1769e0;
}

.portal-center-column,
.portal-right-column {
  display: grid;
  align-content: start;
  gap: 12px;
}

.tabbed-heading,
.portal-section-heading {
  display: flex;
  min-height: 44px;
  align-items: center;
  border-bottom: 1px solid #e3e8ef;
}

.tabbed-heading {
  padding: 0 12px;
  justify-content: space-between;
}

.tabbed-heading > div {
  display: flex;
  height: 44px;
  align-items: stretch;
  gap: 28px;
}

.tabbed-heading button {
  position: relative;
  padding: 0 4px;
  border: 0;
  background: transparent;
  cursor: pointer;
}

.tabbed-heading button.is-active { color: #1769e0; }
.tabbed-heading button.is-active::after {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  height: 2px;
  background: #1769e0;
  content: '';
}

.demo-label {
  display: inline-flex;
  min-height: 21px;
  padding: 0 7px;
  align-items: center;
  border: 1px solid #b9d2f5;
  border-radius: 4px;
  background: #f2f7ff;
  color: #3975c7;
  font-size: 11px;
  font-style: normal;
  white-space: nowrap;
}

.news-card { min-height: 274px; }
.news-list { padding: 7px 14px 10px; }
.news-list li,
.resource-list li {
  display: grid;
  min-height: 34px;
  align-items: center;
}

.news-list li {
  gap: 9px;
  grid-template-columns: 7px minmax(0, 1fr) auto 76px;
}

.news-list__bullet {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #ff4d4f;
}

.news-list li:nth-child(n+4) .news-list__bullet { background: #536176; }

.news-list a,
.resource-list a {
  overflow: hidden;
  color: #344052;
  font-size: 14px;
  font-weight: 500;
  text-decoration: none;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.news-list em,
.resource-list em {
  padding: 2px 5px;
  border-radius: 3px;
  background: #fff1f0;
  color: #f04d4f;
  font-size: 11px;
  font-style: normal;
}

.news-list time,
.resource-list time {
  color: #8b96a5;
  font-size: 12px;
  text-align: right;
  white-space: nowrap;
}

.practice-entry-card {
  display: grid;
  min-height: 238px;
  padding: 10px 10px 10px 16px;
  grid-template-columns: minmax(0, 1fr) 214px;
}

.practice-entry-main { padding-right: 12px; }
.portal-section-heading { justify-content: space-between; }
.portal-section-heading h2 { font-size: 16px; }
.portal-section-heading h2 .el-icon { margin-right: 8px; color: #1769e0; vertical-align: -2px; }
.portal-section-heading a {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  color: #758196;
  font-size: 12px;
  text-decoration: none;
}

.practice-action-grid {
  display: grid;
  padding-top: 10px;
  grid-template-columns: repeat(4, 1fr);
}

.practice-action {
  display: flex;
  min-height: 88px;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border-right: 1px solid #e6ebf1;
  color: #303b4c;
  flex-direction: column;
  text-decoration: none;
}

.practice-action:nth-child(4n) { border-right: 0; }
.practice-action:nth-child(n+5) { border-top: 1px solid #e6ebf1; }
.practice-action__icon {
  width: 38px;
  height: 38px;
  border-radius: 6px;
  font-size: 25px;
}
.practice-action__icon[data-tone="indigo"] { background: #5578ea; }
.practice-action__icon[data-tone="teal"] { background: #11ae8a; }
.practice-action__icon[data-tone="orange"] { background: #ff7a1a; }
.practice-action__icon[data-tone="violet"] { background: #6c5ce7; }
.practice-action__icon[data-tone="red"] { border: 2px solid #ff565e; background: #fff; color: #ff565e; }
.practice-action strong { font-size: 14px; font-weight: 500; }

.quick-start-panel {
  position: relative;
  display: flex;
  min-width: 0;
  padding: 16px;
  overflow: hidden;
  justify-content: space-between;
  border-radius: 5px;
  background: #1769e0;
  color: #fff;
  flex-direction: column;
}

.quick-start-panel h2 { position: relative; z-index: 1; margin: 0; font-size: 17px; }
.quick-start-panel p { position: relative; z-index: 1; width: 148px; margin: 0; color: #dceaff; font-size: 12px; line-height: 1.7; }
.quick-start-panel__watermark { position: absolute; top: 38px; right: 12px; color: rgb(255 255 255 / 12%); font-size: 76px; }
.quick-start-panel a {
  position: relative;
  z-index: 1;
  display: inline-flex;
  min-height: 38px;
  align-items: center;
  justify-content: center;
  gap: 12px;
  border-radius: 5px;
  background: #fff;
  color: #1769e0;
  font-weight: 700;
  text-decoration: none;
}

.resources-card { min-height: 272px; padding: 0 12px 10px; }
.portal-section-heading--tabs { gap: 16px; }
.portal-section-heading--tabs > a { margin-left: auto; }
.resource-list { margin-top: 6px; }
.resource-list li { gap: 10px; grid-template-columns: 18px minmax(0, 1fr) auto 76px; }
.resource-list .el-icon { color: #1769e0; font-size: 18px; }
.resource-list em[data-tag="教程"], .resource-list em[data-tag="图谱"] { background: #e9faf5; color: #0d9d78; }
.resource-list em[data-tag="模板"], .resource-list em[data-tag="真题"] { background: #f0efff; color: #6254d7; }

.portal-right-column .portal-card { padding: 0 12px 12px; }
.exam-schedule-card { min-height: 218px; }
.schedule-placeholder { padding: 16px 8px 0; text-align: center; }
.schedule-placeholder > strong { display: block; color: #334052; font-size: 15px; }
.schedule-placeholder__steps { display: grid; margin: 14px 0 10px; gap: 10px; grid-template-columns: repeat(4, 1fr); }
.schedule-placeholder__steps span {
  display: grid;
  height: 48px;
  place-items: center;
  border-radius: 5px;
  background: #1769e0;
  color: #fff;
  font-size: 13px;
  font-weight: 700;
}
.schedule-placeholder p { margin: 0 0 9px; color: #647185; font-size: 13px; }
.schedule-countdown { display: grid; padding: 16px 8px 8px; justify-items: center; gap: 12px; text-align: center; }
.schedule-countdown > strong { color: #334052; font-size: 15px; }
.schedule-countdown__value { display: flex; align-items: center; gap: 6px; }
.schedule-countdown__value span { display: grid; width: 34px; height: 44px; place-items: center; border-radius: 4px; background: #eef3fa; color: #1769e0; font-size: 32px; font-weight: 800; line-height: 1; }
.schedule-countdown__value em { margin-left: 2px; color: #334052; font-size: 14px; font-style: normal; }
.schedule-countdown small { color: #647185; font-size: 12px; }

.timeline-card { min-height: 310px; }
.timeline-card .portal-section-heading { gap: 10px; }
.timeline-card .portal-section-heading h2 { flex: 1 1 auto; min-width: 0; white-space: nowrap; }
.timeline-region { width: 118px; flex: 0 0 118px; }
.timeline-list { position: relative; padding: 4px 0 0 24px; }
.timeline-list::before { position: absolute; top: 20px; bottom: 21px; left: 8px; width: 1px; background: #cbd8e8; content: ''; }
.timeline-list li { position: relative; display: grid; min-height: 59px; align-items: center; gap: 8px; grid-template-columns: minmax(0, 1fr) auto; }
.timeline-list__dot { position: absolute; z-index: 1; top: 21px; left: -20px; width: 9px; height: 9px; border: 2px solid #fff; border-radius: 50%; background: #1769e0; box-shadow: 0 0 0 1px #1769e0; }
.timeline-list li:first-child .timeline-list__dot { background: #0bae86; box-shadow: 0 0 0 1px #0bae86; }
.timeline-list div { display: grid; gap: 4px; }
.timeline-list strong { font-size: 14px; }
.timeline-list small { color: #8a95a4; font-size: 12px; }
.timeline-list em { padding: 3px 7px; border-radius: 4px; background: #f1f3f6; color: #7e8997; font-size: 11px; font-style: normal; }

.study-plan-card { min-height: 256px; }
.study-plan-placeholder { display: grid; padding: 17px 9px 12px; justify-items: center; gap: 8px; text-align: center; }
.study-plan-placeholder .el-icon { color: #1769e0; font-size: 29px; }
.study-plan-placeholder strong { font-size: 14px; }
.study-plan-placeholder p { margin: 0; color: #6e7b8d; font-size: 12px; line-height: 1.65; }
.study-plan-login { display: flex; min-height: 38px; align-items: center; justify-content: center; border-radius: 5px; background: #1769e0; color: #fff; font-size: 14px; font-weight: 700; text-decoration: none; }

.portal-capabilities {
  display: grid;
  width: min(100%, 1456px);
  min-height: 100px;
  margin: 16px auto 0;
  border: 1px solid #dfe5ec;
  border-radius: 6px;
  background: #fff;
  grid-template-columns: repeat(4, 1fr);
}
.portal-capabilities article { display: flex; min-width: 0; padding: 18px 24px; align-items: center; gap: 18px; border-right: 1px solid #e1e6ed; }
.portal-capabilities article:last-child { border-right: 0; }
.capability-icon { width: 42px; height: 48px; flex: 0 0 42px; border-radius: 6px; font-size: 26px; }
.capability-icon[data-tone="indigo"] { background: #5578ea; }
.capability-icon[data-tone="teal"] { background: #0fb38b; }
.portal-capabilities div { min-width: 0; }
.portal-capabilities strong { font-size: 16px; }
.portal-capabilities p { overflow: hidden; margin: 7px 0 0; color: #697588; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }

@media (max-width: 1180px) {
  .public-portal-grid { grid-template-columns: minmax(250px, 280px) minmax(540px, 1fr) minmax(300px, 330px); }
  .qualification-directory { padding-right: 12px; padding-left: 12px; }
  .practice-entry-card { grid-template-columns: minmax(0, 1fr) 190px; }
  .practice-action { min-height: 84px; }
  .quick-start-panel { padding: 14px; }
  .portal-capabilities article { padding: 16px 18px; gap: 12px; }
}

@media (max-width: 1120px) {
  .public-portal-grid { grid-template-columns: 280px minmax(0, 1fr); }
  .portal-right-column { grid-column: 1 / -1; grid-template-columns: repeat(3, 1fr); }
  .qualification-directory { min-height: auto; }
}

@media (max-width: 760px) {
  .public-home { padding: 10px; }
  .public-portal-grid { display: flex; width: 100%; flex-direction: column; gap: 10px; }
  .qualification-directory { min-height: auto; order: 1; }
  .portal-center-column { display: contents; }
  .news-card { min-height: 0; order: 2; }
  .practice-entry-card { order: 3; }
  .resources-card { min-height: 0; order: 5; }
  .portal-right-column { display: contents; }
  .exam-schedule-card { min-height: 0; order: 4; }
  .timeline-card { min-height: 0; order: 6; }
  .study-plan-card { min-height: 0; order: 7; }
  .qualification-group ul { display: grid; grid-template-columns: 1fr 1fr; }
  .qualification-all-link { margin-top: 12px; }
  .tabbed-heading { align-items: flex-start; flex-direction: column; }
  .tabbed-heading > div { width: 100%; gap: 0; }
  .tabbed-heading button { flex: 1; font-size: 14px; }
  .tabbed-heading .demo-label { margin: 6px 12px; }
  .news-list li { min-height: 42px; grid-template-columns: 7px minmax(0, 1fr) auto; }
  .news-list time { display: none; }
  .practice-entry-card { display: block; padding: 10px; }
  .practice-entry-main { padding: 0; }
  .practice-action-grid { grid-template-columns: repeat(3, 1fr); }
  .practice-action:nth-child(4n) { border-right: 1px solid #e6ebf1; }
  .practice-action:nth-child(3n) { border-right: 0; }
  .practice-action:nth-child(4) { border-top: 1px solid #e6ebf1; }
  .quick-start-panel { min-height: 180px; margin-top: 10px; }
  .quick-start-panel p { width: 76%; }
  .resource-list li { min-height: 42px; grid-template-columns: 18px minmax(0, 1fr) auto; }
  .resource-list time { display: none; }
  .portal-capabilities { grid-template-columns: 1fr; }
  .portal-capabilities article { border-right: 0; border-bottom: 1px solid #e1e6ed; }
  .portal-capabilities article:last-child { border-bottom: 0; }
}

@media (max-width: 420px) {
  .portal-section-heading small { display: none; }
  .practice-action { min-height: 82px; }
  .schedule-placeholder__steps { gap: 6px; }
  .schedule-placeholder__steps span { font-size: 12px; }
}

/* Stage 1: public learning portal visual refresh. */
.public-home {
  min-height: calc(100vh - 64px);
  padding: 24px clamp(16px, 3vw, 40px) 36px;
  background: #f6f8fa;
  color: #172033;
}

.portal-hero,
.public-portal-grid,
.portal-capabilities {
  width: min(100%, 1320px);
  margin-right: auto;
  margin-left: auto;
}

.portal-hero {
  display: grid;
  min-height: 208px;
  margin-bottom: 20px;
  padding: 30px 32px;
  align-items: center;
  gap: 28px;
  border: 1px solid #dce4ee;
  border-radius: 18px;
  background: #fff;
  box-shadow: 0 12px 32px rgb(15 23 42 / 6%);
  grid-template-columns: minmax(0, 1fr) minmax(280px, 340px);
}

.portal-hero__copy { max-width: 720px; }
.portal-eyebrow,
.section-step {
  display: inline-flex;
  color: #2563eb;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: .08em;
}
.portal-hero h1 { margin: 10px 0 12px; color: #172033; font-size: clamp(27px, 3vw, 38px); line-height: 1.22; letter-spacing: -.03em; }
.portal-hero__description { max-width: 620px; margin: 0; color: #64748b; font-size: 15px; line-height: 1.75; }
.portal-hero__actions { display: flex; margin-top: 23px; align-items: center; gap: 12px; flex-wrap: wrap; }
.portal-primary-action,
.portal-secondary-action {
  display: inline-flex;
  min-height: 42px;
  padding: 0 16px;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border-radius: 10px;
  font-size: 14px;
  font-weight: 700;
  text-decoration: none;
  transition: transform .18s ease, box-shadow .18s ease, border-color .18s ease;
}
.portal-primary-action { background: #172033; color: #fff; box-shadow: 0 8px 16px rgb(23 32 51 / 16%); }
.portal-primary-action:hover { transform: translateY(-1px); box-shadow: 0 12px 20px rgb(23 32 51 / 20%); }
.portal-secondary-action { border: 1px solid #dce4ee; color: #475569; }
.portal-secondary-action:hover { border-color: #a9c8f6; color: #2563eb; }
.portal-hero__status { display: flex; min-height: 116px; padding: 18px; align-items: flex-start; gap: 13px; border: 1px solid #d9e7fa; border-radius: 14px; background: #f3f8ff; }
.portal-hero__status-icon { display: grid; width: 38px; height: 38px; flex: 0 0 38px; place-items: center; border-radius: 11px; background: #2563eb; color: #fff; font-size: 19px; }
.portal-hero__status div { display: grid; gap: 5px; }.portal-hero__status small { color: #64748b; font-size: 12px; }.portal-hero__status strong { overflow: hidden; color: #172033; font-size: 16px; text-overflow: ellipsis; white-space: nowrap; }.portal-hero__status p { margin: 0; color: #64748b; font-size: 12px; line-height: 1.55; }

.public-portal-grid { display: grid; align-items: start; gap: 20px; grid-template-columns: minmax(0, 1fr) minmax(280px, 310px); }
.portal-card { border-color: #dce4ee; border-radius: 12px; box-shadow: 0 5px 16px rgb(15 23 42 / 4%); }
.portal-center-column,.portal-right-column { gap: 20px; }
.qualification-directory { display: flex; position: sticky; top: 84px; min-height: 0; padding: 18px; border-radius: 12px; }
.qualification-heading { display: grid; padding-bottom: 14px; gap: 6px; border-bottom: 1px solid #e7edf4; }.qualification-heading h2 { margin: 0; color: #172033; font-size: 18px; }.qualification-heading p { margin: 0; color: #7c8898; font-size: 12px; line-height: 1.5; }
.qualification-group { padding: 16px 0 11px; }.qualification-group header { gap: 9px; }.qualification-group h2 { font-size: 14px; }.qualification-group__icon { width: 28px; height: 28px; border-radius: 9px; }.qualification-group button { min-height: 36px; padding: 0 8px; border-radius: 8px; }.qualification-group button:hover,.qualification-group button.is-active { border-color: #bfd7f7; background: #f1f7ff; }.qualification-group button small { border-radius: 5px; background: #2563eb; }.qualification-all-link { min-height: 34px; margin-top: 10px; justify-content: flex-start; border: 0; color: #64748b; font-size: 12px; font-weight: 500; }.qualification-local-state { min-height: 280px; }

.practice-entry-card { min-height: 0; padding: 0; overflow: visible; border-radius: 14px; grid-template-columns: minmax(0, 1fr) 224px; }.practice-entry-main { padding: 0 20px 20px; }.portal-section-heading { min-height: 68px; border-bottom-color: #e7edf4; }.portal-section-heading > div { display: grid; gap: 3px; }.portal-section-heading h2 { color: #172033; font-size: 18px; }.portal-section-heading small { color: #7c8898; }.practice-action-grid { padding-top: 17px; gap: 10px; grid-template-columns: repeat(2, minmax(0, 1fr)); }.practice-action { min-height: 82px; padding: 12px; align-items: flex-start; justify-content: flex-start; gap: 10px; border: 1px solid #e3eaf2; border-radius: 10px; flex-direction: row; transition: transform .18s ease, border-color .18s ease, box-shadow .18s ease; }.practice-action:nth-child(4n),.practice-action:nth-child(n+5) { border: 1px solid #e3eaf2; }.practice-action:hover { transform: translateY(-2px); border-color: #a9c8f6; box-shadow: 0 8px 16px rgb(37 99 235 / 8%); }.practice-action__icon { width: 36px; height: 36px; flex: 0 0 36px; border-radius: 10px; font-size: 20px; }.practice-action > span:last-child { display: grid; min-width: 0; gap: 4px; }.practice-action strong { color: #273244; font-size: 14px; font-weight: 700; }.practice-action small { overflow: hidden; color: #7c8898; font-size: 12px; line-height: 1.35; text-overflow: ellipsis; white-space: nowrap; }
.quick-start-panel { min-height: 100%; padding: 22px 18px; border-radius: 0 13px 13px 0; background: #172033; }.quick-start-panel > span { position: relative; z-index: 1; color: #9eb5d5; font-size: 12px; font-weight: 700; letter-spacing: .07em; }.quick-start-panel h2 { font-size: 20px; line-height: 1.35; }.quick-start-panel p { width: auto; color: #c9d5e5; font-size: 13px; }.quick-start-panel a { min-height: 40px; border-radius: 9px; background: #fff; color: #172033; }.quick-start-panel__watermark { top: 58px; color: rgb(255 255 255 / 7%); }

.news-card,.resources-card,.portal-right-column .portal-card { padding: 0 18px 14px; }.news-card { min-height: 0; }.tabbed-heading { min-height: 68px; padding: 0; border-bottom-color: #e7edf4; }.tabbed-heading > div { display: grid; height: auto; gap: 5px; }.tabbed-heading [role="tablist"] { display: flex; height: 30px; gap: 22px; }.tabbed-heading button { color: #7c8898; font-size: 15px; }.tabbed-heading button.is-active { color: #172033; }.tabbed-heading button.is-active::after { height: 2px; background: #2563eb; }.news-list { padding: 8px 0 0; }.news-list li,.resource-list li { min-height: 39px; }.news-list a,.resource-list a { color: #334155; }.news-list a:hover,.resource-list a:hover { color: #2563eb; }.news-list__bullet { background: #2563eb; }.news-list li:nth-child(n+4) .news-list__bullet { background: #94a3b8; }.news-list em,.resource-list em { border-radius: 5px; background: #fff4e7; color: #b45309; }.resources-card { min-height: 0; }.resources-card .portal-section-heading { min-height: 64px; }.resource-list { margin-top: 8px; }.resource-list .el-icon { color: #2563eb; }.portal-content-state { margin: 22px 0; color: #7c8898; }.portal-content-state--error { color: #dc2626; }

.exam-schedule-card,.timeline-card,.study-plan-card { min-height: 0; }.portal-right-column .portal-section-heading { min-height: 60px; }.portal-section-heading h2 .el-icon { color: #2563eb; }.schedule-placeholder { padding-top: 20px; }.schedule-placeholder__steps span { height: 42px; border-radius: 9px; background: #eff6ff; color: #2563eb; }.schedule-countdown { padding-top: 20px; }.schedule-countdown__value span { width: 36px; height: 47px; border-radius: 9px; background: #eff6ff; color: #2563eb; }.timeline-region { width: 116px; }.timeline-list { padding-left: 25px; }.timeline-list::before { background: #d7e2ef; }.timeline-list__dot { background: #2563eb; box-shadow: 0 0 0 1px #2563eb; }.timeline-list li:first-child .timeline-list__dot { background: #0f766e; box-shadow: 0 0 0 1px #0f766e; }.timeline-list em { border-radius: 6px; }.study-plan-placeholder { padding-top: 22px; }.study-plan-placeholder .el-icon { color: #2563eb; }.study-plan-login { min-height: 40px; border-radius: 9px; background: #172033; }

.portal-capabilities { min-height: 0; margin-top: 20px; overflow: hidden; border-radius: 12px; box-shadow: 0 5px 16px rgb(15 23 42 / 4%); }.portal-capabilities article { padding: 20px; gap: 13px; }.capability-icon { width: 40px; height: 40px; flex-basis: 40px; border-radius: 11px; background: #2563eb; font-size: 21px; }.portal-capabilities strong { color: #334155; font-size: 14px; }.portal-capabilities p { margin-top: 4px; color: #7c8898; font-size: 12px; }

@media (max-width: 1180px) {
  .public-portal-grid { grid-template-columns: minmax(0, 1fr) minmax(270px, 290px); }.portal-right-column { grid-column: auto; grid-template-columns: 1fr; }.practice-entry-card { grid-template-columns: minmax(0, 1fr) 200px; }
}

@media (max-width: 880px) {
  .portal-hero { grid-template-columns: 1fr; }.portal-hero__status { max-width: 520px; }.public-portal-grid { grid-template-columns: 1fr; }.qualification-directory { position: static; order: 2; }.portal-center-column { display: contents; }.practice-entry-card { order: 1; }.news-card { order: 3; }.resources-card { order: 4; }.portal-right-column { display: grid; grid-column: auto; grid-template-columns: repeat(3, minmax(0, 1fr)); order: 5; }.qualification-group ul { display: grid; gap: 4px 8px; grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 680px) {
  .public-home { padding: 14px 12px 24px; }.portal-hero { min-height: 0; margin-bottom: 14px; padding: 23px 20px; gap: 20px; border-radius: 14px; }.portal-hero h1 { font-size: 28px; }.portal-hero__description { font-size: 14px; }.portal-hero__actions { margin-top: 18px; }.portal-primary-action,.portal-secondary-action { width: 100%; }.public-portal-grid { gap: 14px; }.portal-card { border-radius: 12px; }.practice-entry-card { display: block; }.practice-entry-main { padding: 0 14px 14px; }.portal-section-heading { min-height: 60px; }.practice-action-grid { gap: 8px; grid-template-columns: 1fr; }.practice-action { min-height: 68px; }.quick-start-panel { min-height: 194px; margin-top: 0; border-radius: 0 0 11px 11px; }.portal-right-column { display: grid; grid-template-columns: 1fr; gap: 14px; }.qualification-directory,.news-card,.resources-card,.portal-right-column .portal-card { padding-right: 14px; padding-left: 14px; }.qualification-group ul { grid-template-columns: 1fr; }.news-list li { grid-template-columns: 7px minmax(0, 1fr) auto; }.news-list time,.resource-list time { display: none; }.portal-capabilities { margin-top: 14px; }.portal-capabilities article { padding: 16px; }.portal-capabilities article:not(:last-child) { border-bottom: 1px solid #e1e6ed; }.portal-capabilities p { white-space: normal; }
}
</style>
