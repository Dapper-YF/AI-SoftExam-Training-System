<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Calendar, Check, Clock, Collection, DataAnalysis, Document, EditPen, Medal, Refresh, Tickets } from '@element-plus/icons-vue'
import practiceVisual from '@/assets/platform/practice-module-visual.webp'
import essayVisual from '@/assets/platform/essay-module-visual.webp'
import { useAuthStore } from '@/stores/auth'
import { useQualificationStore } from '@/stores/qualification'
import { isEssayPreviewEnabled } from '@/modules/essay/essay-state'
import { resolveProductAvailability } from '../home-state'
import { usePlatformHomeStore } from '../stores/platform-home'

const router = useRouter()
const authStore = useAuthStore()
const qualificationStore = useQualificationStore()
const homeStore = usePlatformHomeStore()
const essayPreviewEnabled = isEssayPreviewEnabled(
  import.meta.env.DEV,
  import.meta.env.VITE_ESSAY_DEV_PREVIEW,
)
const productNotice = ref('')

const availabilityContext = computed(() => ({
  isAuthenticated: authStore.isAuthenticated,
  featureFlags: authStore.featureFlags,
  permissions: authStore.permissions,
  subjects: qualificationStore.subjects,
  essayPreviewEnabled,
}))
const practiceAvailability = computed(() => resolveProductAvailability('practice', availabilityContext.value))
const essayAvailability = computed(() => resolveProductAvailability('essay', availabilityContext.value))
const statIcons = [Clock, Collection, Document, DataAnalysis, Calendar]

function enter(path: string, canEnter: boolean, unavailableMessage?: string): void {
  productNotice.value = ''
  if (canEnter) {
    void router.push(path)
    return
  }
  productNotice.value = unavailableMessage ?? '当前产品暂未开通，请联系管理员。'
}

function reloadQualifications(): Promise<void> {
  return qualificationStore.load({ anonymous: !authStore.isAuthenticated })
}

function formatRecentTaskTime(value?: string): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)
}

onMounted(() => {
  if (authStore.isAuthenticated) void homeStore.load()
  else homeStore.showAnonymous()
})
</script>

<template>
  <div class="module-home" data-testid="module-home">
    <section class="module-hero" aria-labelledby="module-heading">
      <div>
        <p class="eyebrow">学习工作台</p>
        <h1 id="module-heading">选择学习模块</h1>
        <p>可随时切换模块，继续你的最近任务</p>
      </div>
    </section>

    <section v-if="qualificationStore.status === 'error'" class="module-boundary module-boundary--error" role="alert">
      <strong>学习权限加载失败</strong>
      <span>{{ qualificationStore.errorMessage }}</span>
      <el-button text data-testid="module-qualification-retry" @click="reloadQualifications"><el-icon><Refresh /></el-icon>重试</el-button>
    </section>

    <section class="product-grid" aria-label="产品模块">
      <button
        class="product-entry product-entry--practice"
        data-testid="practice-product-entry"
        :class="{ 'is-disabled': !practiceAvailability.canEnter }"
        type="button"
        :data-state="practiceAvailability.state"
        @click="enter('/practice', practiceAvailability.canEnter, '软考刷题暂未开通。')"
      >
        <span class="product-entry__content">
          <span class="product-entry__title"><i><el-icon><Tickets /></el-icon></i><strong>软考刷题</strong></span>
          <span class="product-entry__tag">知识巩固</span>
          <span>按章节、题型与薄弱点持续训练，形成稳定的应试节奏。</span>
          <span class="product-entry__features"><i><el-icon><Check /></el-icon></i>章节练习，覆盖考点全面练习<br><i><el-icon><Check /></el-icon></i>智能组卷，个性化生成试卷<br><i><el-icon><Check /></el-icon></i>错题管理，精准分析遗漏补缺</span>
          <span class="product-entry__action">{{ practiceAvailability.label }}<el-icon><ArrowRight /></el-icon></span>
        </span>
        <span class="product-entry__visual"><img :src="practiceVisual" alt="刷题清单与学习分析主题插画"></span>
      </button>

      <button
        class="product-entry product-entry--essay"
        data-testid="essay-product-entry"
        :class="{ 'is-disabled': !essayAvailability.canEnter }"
        type="button"
        :data-state="essayAvailability.state"
        @click="enter('/app/essay/home', essayAvailability.canEnter, '论文服务不可用。')"
      >
        <span class="product-entry__content">
          <span class="product-entry__title"><i><el-icon><EditPen /></el-icon></i><strong>AI 论文</strong></span>
          <span class="product-entry__tag">AI 辅助写作</span>
          <span>从选题、提纲到评阅建议，按考试要求逐步完成论文训练。</span>
          <span class="product-entry__features"><i><el-icon><Check /></el-icon></i>热点追踪，选题方向智能推荐<br><i><el-icon><Check /></el-icon></i>大纲生成，结构化论文框架<br><i><el-icon><Check /></el-icon></i>智能润色，提升论文质量水平</span>
          <span class="product-entry__action" data-testid="essay-entry-status">{{ essayAvailability.label }}<el-icon v-if="essayAvailability.canEnter"><ArrowRight /></el-icon></span>
        </span>
        <span class="product-entry__visual"><img :src="essayVisual" alt="论文文稿与智能分析主题插画"></span>
      </button>
    </section>

    <p v-if="productNotice" class="product-notice" role="alert" data-testid="product-unavailable-feedback">
      {{ productNotice }}
    </p>

    <section class="home-lower-grid">
      <div class="recent-tasks">
        <div class="section-heading">
          <div><p class="eyebrow">继续学习</p><h2>最近任务</h2></div>
          <span v-if="homeStore.sourceLabel" class="source-badge">{{ homeStore.sourceLabel }}</span>
        </div>

        <div v-if="homeStore.recentTasksStatus === 'loading'" class="task-state" aria-live="polite">
          <el-skeleton :rows="2" animated />
        </div>
        <div v-else-if="homeStore.recentTasksStatus === 'error'" class="task-state task-state--error" role="alert">
          <strong>最近任务加载失败</strong><span>{{ homeStore.errorMessage }}</span>
          <small v-if="homeStore.errorTraceId">Trace ID：{{ homeStore.errorTraceId }}</small>
          <el-button text @click="homeStore.load()">重新加载</el-button>
        </div>
        <div v-else-if="homeStore.recentTasksStatus === 'empty'" class="task-state">
          <el-icon><Document /></el-icon><strong>还没有最近任务</strong><span>进入任一产品后，学习进度会显示在这里。</span>
        </div>
        <div v-else class="task-list">
          <div class="task-list__head" aria-hidden="true"><span>任务名称</span><span>所属模块</span><span>进度</span><span>正确率 / 得分</span><span>上次学习</span><span>操作</span></div>
          <button
            v-for="task in homeStore.recentTasks"
            :key="task.id"
            type="button"
            @click="task.routePath && router.push(task.routePath)"
          >
            <span class="task-name"><strong>{{ task.title }}</strong><small>{{ task.description }}</small></span>
            <span class="task-product" :class="`task-product--${task.product}`">{{ task.product === 'practice' ? '软考刷题' : 'AI 论文' }}</span>
            <span>{{ task.progress }}</span><span>{{ task.score }}</span><time :datetime="task.updatedAt">{{ formatRecentTaskTime(task.updatedAt) }}</time><span class="task-action">继续学习<el-icon><ArrowRight /></el-icon></span>
          </button>
        </div>
      </div>

      <aside class="global-stats" aria-label="全局学习统计">
        <div class="section-heading"><div><p class="eyebrow">学习概览</p><h2>本期数据</h2></div></div>
        <dl>
          <div v-for="(stat, index) in homeStore.stats" :key="stat.id"><el-icon><component :is="statIcons[index] ?? Medal" /></el-icon><div class="stat-copy"><dt>{{ stat.label }}</dt><dd>{{ stat.value }}</dd></div></div>
        </dl>
      </aside>
    </section>
  </div>
</template>
