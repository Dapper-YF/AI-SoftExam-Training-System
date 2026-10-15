<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ArrowRight, Calendar, Clock, Document } from '@element-plus/icons-vue'
import { getPortalContentGateway } from '../api/portal-content'
import type { PortalContentItem, PortalNewsCategory } from '../api/portal-content.gateway'
import { portalTimeline } from '../public-portal.fixtures'

const tab = ref<PortalNewsCategory>('REGISTRATION')
const items = ref<PortalContentItem[]>([])
const status = ref<'loading' | 'ready' | 'empty' | 'error'>('loading')
const tabs = [
  { value: 'REGISTRATION' as const, label: '报名动态' },
  { value: 'POLICY' as const, label: '政策资讯' },
]
const tabLabel = computed(() => tabs.find((item) => item.value === tab.value)?.label ?? '考试资讯')

async function loadNews(): Promise<void> {
  status.value = 'loading'
  try {
    items.value = await getPortalContentGateway().listNews(tab.value)
    status.value = items.value.length ? 'ready' : 'empty'
  } catch {
    items.value = []
    status.value = 'error'
  }
}

onMounted(() => void loadNews())
watch(tab, () => void loadNews())
</script>

<template>
  <main class="news-page" data-testid="public-news-page">
    <section class="news-page__hero">
      <div>
        <span class="news-page__eyebrow">EXAM INFORMATION</span>
        <h1>考试资讯</h1>
        <p>集中查看报名政策、考试安排与备考动态，重要节点以官方公告为准。</p>
      </div>
      <div class="news-page__hero-stat"><strong>官方资讯</strong><span>持续更新</span></div>
    </section>
    <div class="news-page__grid">
      <section class="news-panel">
        <header class="news-panel__header">
          <div><span class="panel-kicker">LATEST</span><h2>{{ tabLabel }}</h2></div>
          <div class="news-tabs" role="tablist" aria-label="考试资讯分类">
            <button v-for="item in tabs" :key="item.value" type="button" role="tab" :aria-selected="tab === item.value" :class="{ 'is-active': tab === item.value }" @click="tab = item.value">{{ item.label }}</button>
          </div>
        </header>
        <ul v-if="status === 'ready'" class="news-page__list">
          <li v-for="item in items" :key="item.id">
            <span class="news-page__bullet" aria-hidden="true" />
            <div><a :href="item.sourceUrl" target="_blank" rel="noopener noreferrer">{{ item.title }}</a><p>{{ item.summary }}</p></div>
            <time>{{ item.publishedAt.slice(0, 10) }}</time>
          </li>
        </ul>
        <p v-else-if="status === 'loading'" class="news-page__state">正在加载官方资讯</p>
        <p v-else-if="status === 'empty'" class="news-page__state">暂无最新官方资讯</p>
        <p v-else class="news-page__state news-page__state--error">官方资讯暂时无法加载</p>
      </section>
      <aside class="news-page__aside">
        <section class="news-panel schedule-panel">
          <header><h2><el-icon><Clock /></el-icon>考试倒计时</h2><span>官方安排</span></header>
          <div class="schedule-panel__empty"><strong>下一场考试安排</strong><p>时间安排以官方公告为准</p></div>
        </section>
        <section class="news-panel timeline-panel">
          <header><h2><el-icon><Calendar /></el-icon>重要时间节点</h2><span>参考信息</span></header>
          <ol><li v-for="item in portalTimeline" :key="item.id"><span class="timeline-dot" /><div><strong>{{ item.title }}</strong><small>{{ item.schedule }}</small></div><em>{{ item.status }}</em></li></ol>
        </section>
        <router-link class="news-page__resource-link" to="/resources"><el-icon><Document /></el-icon><span><strong>开始系统学习</strong><small>进入学习资源中心</small></span><el-icon><ArrowRight /></el-icon></router-link>
      </aside>
    </div>
  </main>
</template>

<style scoped>
.news-page { min-height: calc(100vh - 56px); padding: 30px 24px 48px; background: #f5f7fa; color: #273244; }
.news-page__hero, .news-page__grid { width: min(1180px, 100%); margin: 0 auto; }
.news-page__hero { display: flex; justify-content: space-between; align-items: end; padding: 24px 28px; border: 1px solid #dce5f0; background: #fff; }
.news-page__eyebrow, .panel-kicker { color: #1769c2; font-size: 11px; font-weight: 700; letter-spacing: .12em; }
.news-page h1 { margin: 8px 0 6px; font-size: 30px; color: #16396e; }
.news-page__hero p { margin: 0; color: #6a778a; font-size: 14px; }
.news-page__hero-stat { display: grid; padding-left: 26px; gap: 5px; border-left: 3px solid #2c9b71; color: #16396e; }.news-page__hero-stat span { color: #7c8798; font-size: 12px; }
.news-page__grid { display: grid; margin-top: 18px; grid-template-columns: minmax(0, 1fr) 330px; gap: 18px; }.news-page__aside { display: grid; align-content: start; gap: 18px; }.news-panel { border: 1px solid #dce5f0; background: #fff; }.news-panel__header, .schedule-panel header, .timeline-panel header { display: flex; padding: 18px 20px; align-items: center; justify-content: space-between; border-bottom: 1px solid #e8eef5; }.news-panel h2 { margin: 4px 0 0; color: #193d76; font-size: 17px; }.news-tabs { display: flex; gap: 6px; }.news-tabs button { padding: 7px 11px; border: 1px solid transparent; background: transparent; color: #77849a; cursor: pointer; font-size: 12px; }.news-tabs button.is-active { border-color: #cbdcf3; background: #eef5ff; color: #1769c2; font-weight: 700; }.news-page__list { padding: 0 20px; margin: 0; list-style: none; }.news-page__list li { display: grid; padding: 17px 0; grid-template-columns: 8px minmax(0, 1fr) 88px; gap: 12px; align-items: start; border-bottom: 1px solid #edf1f6; }.news-page__list li:last-child { border-bottom: 0; }.news-page__bullet { width: 7px; height: 7px; margin-top: 7px; border-radius: 50%; background: #2784da; }.news-page__list a { color: #263e62; font-size: 14px; font-weight: 600; text-decoration: none; }.news-page__list a:hover { color: #1769c2; }.news-page__list p { margin: 6px 0 0; color: #8792a1; font-size: 12px; line-height: 1.5; }.news-page__list time { color: #98a3b3; font-size: 12px; text-align: right; }.news-page__state { min-height: 220px; display: grid; place-items: center; color: #8a96a8; font-size: 13px; }.news-page__state--error { color: #c45656; }.schedule-panel header span, .timeline-panel header span { color: #94a0b0; font-size: 12px; }.schedule-panel header h2, .timeline-panel header h2 { display: flex; margin: 0; align-items: center; gap: 7px; color: #193d76; font-size: 15px; }.schedule-panel__empty { padding: 24px 20px; }.schedule-panel__empty strong { color: #3b4d68; font-size: 14px; }.schedule-panel__empty p { margin: 8px 0 0; color: #8a96a8; font-size: 12px; }.timeline-panel ol { padding: 13px 20px 17px; margin: 0; list-style: none; }.timeline-panel li { display: grid; padding: 10px 0; grid-template-columns: 10px minmax(0, 1fr) auto; gap: 10px; align-items: start; }.timeline-dot { width: 8px; height: 8px; margin-top: 5px; border: 2px solid #418bd3; border-radius: 50%; }.timeline-panel li strong, .timeline-panel li small { display: block; }.timeline-panel li strong { color: #465570; font-size: 12px; }.timeline-panel li small { margin-top: 3px; color: #97a2b0; font-size: 11px; }.timeline-panel li em { color: #2f946d; font-size: 11px; font-style: normal; }.news-page__resource-link { display: flex; min-height: 66px; padding: 0 16px; align-items: center; gap: 10px; border: 1px solid #cbdcf3; background: #eef5ff; color: #1769c2; text-decoration: none; }.news-page__resource-link > span { display: grid; flex: 1; gap: 3px; }.news-page__resource-link small { color: #7890af; font-size: 11px; }.news-page__resource-link strong { font-size: 13px; }
@media (max-width: 760px) { .news-page { padding: 16px 12px 32px; }.news-page__hero { align-items: start; gap: 18px; padding: 20px; }.news-page__hero-stat { display: none; }.news-page h1 { font-size: 26px; }.news-page__grid { grid-template-columns: 1fr; }.news-page__aside { grid-template-columns: 1fr; }.news-panel__header { display: block; }.news-tabs { margin-top: 14px; overflow-x: auto; }.news-page__list { padding: 0 14px; }.news-page__list li { grid-template-columns: 8px minmax(0, 1fr); }.news-page__list time { grid-column: 2; text-align: left; } }
</style>
