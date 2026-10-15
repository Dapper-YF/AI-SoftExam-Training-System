<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Check, Document, Files, FolderOpened, InfoFilled, Reading, TrendCharts } from '@element-plus/icons-vue'
import { ApiClientError } from '@/api/client'
import { getEssayGateway } from '@/modules/essay/api/gateway'
import { errorPresentation } from '@/modules/essay/essay-state'
import type { EssayHomeData } from '@/modules/essay/types'

defineOptions({ name: 'EssayWorkspaceHomeView' })
const router = useRouter()
const data = ref<EssayHomeData | null>(null)
const loading = ref(true)
const errorState = ref<{ message: string; retryable: boolean } | null>(null)
const isPreview = computed(() => import.meta.env.DEV && import.meta.env.VITE_ESSAY_DEV_PREVIEW === 'true')

async function load(): Promise<void> {
  loading.value = true
  errorState.value = null
  try { data.value = await (await getEssayGateway()).loadHome() }
  catch (reason) { errorState.value = errorPresentation(reason instanceof ApiClientError ? reason.status : (reason as { status?: number }).status) }
  finally { loading.value = false }
}
function open(path: string): void { void router.push(path) }
onMounted(load)
</script>

<template>
  <main class="essay-home" data-testid="essay-home">
    <header class="essay-home__heading">
      <div><p>AI 论文工作区</p><h1>论文首页</h1></div>
      <span class="essay-home__qualification">高级信息系统项目管理师</span>
    </header>

    <div v-if="isPreview" class="dev-notice" role="status"><el-icon><InfoFilled /></el-icon><span>开发环境，当前 AI 训练与批改不产生费用</span></div>
    <div v-else class="dev-notice dev-notice--neutral" role="status"><el-icon><InfoFilled /></el-icon><span>AI 生成与批改接口尚未开放；背景、素材与草稿将按真实服务状态呈现</span></div>

    <section v-if="loading" class="essay-state">正在加载论文工作区…</section>
    <section v-else-if="errorState" class="essay-state essay-state--error">
      <strong>{{ errorState.message }}</strong><button v-if="errorState.retryable" type="button" @click="load">重新加载</button>
    </section>
    <template v-else-if="data">
      <section class="workflow-card" aria-label="论文训练六步流程">
        <ol>
          <li v-for="(step, index) in data.workflow" :key="step.label" :class="`is-${step.state}`" data-testid="essay-workflow-step">
            <span><el-icon v-if="step.state === 'done'"><Check /></el-icon><b v-else>{{ index + 1 }}</b></span>
            <strong>{{ step.label }}</strong><small>{{ step.state === 'done' ? '已完成' : step.state === 'active' ? '进行中' : '待开始' }}</small>
          </li>
        </ol>
      </section>

      <section class="essay-home__focus-grid">
        <article class="focus-card focus-card--draft">
          <h2>继续当前论文</h2>
          <template v-if="data.currentDraft">
            <div class="draft-summary"><span class="draft-icon"><el-icon><Document /></el-icon></span><div><h3>{{ data.currentDraft.title }}</h3><p>绑定项目背景：{{ data.currentDraft.background }}</p><dl><div><dt>当前字数</dt><dd>{{ data.currentDraft.words.toLocaleString() }} 字</dd></div><div><dt>最后保存</dt><dd>{{ data.currentDraft.updatedAt }}</dd></div></dl></div></div>
            <button data-testid="continue-draft" type="button" @click="open('/app/essay/editor')">继续写作 <el-icon><ArrowRight /></el-icon></button>
          </template>
          <div v-else class="empty-copy">暂无草稿。可先完成项目背景与素材准备。</div>
        </article>

        <article class="focus-card focus-card--review">
          <header><h2>最近一次批改</h2><time v-if="data.latestReview">{{ data.latestReview.reviewedAt }}</time></header>
          <template v-if="data.latestReview">
            <div class="review-summary"><div class="score-ring"><strong>{{ data.latestReview.score }}</strong><span>分 / 100</span></div><div><p><b>总体评价：</b>{{ data.latestReview.summary }}</p><hr><b>主要问题</b><ul><li v-for="issue in data.latestReview.issues" :key="issue">{{ issue }}</li></ul></div></div>
            <button class="outline-button" type="button" @click="open('/app/essay/reports')">查看报告 <el-icon><ArrowRight /></el-icon></button>
          </template>
          <div v-else class="empty-copy">尚无批改结果。异步批改接口开放后可在此查看。</div>
        </article>
      </section>

      <section class="essay-home__lower-grid">
        <article class="list-card"><header><h2><el-icon><Reading /></el-icon>近期草稿</h2><button @click="open('/app/essay/editor')">全部草稿 <el-icon><ArrowRight /></el-icon></button></header><div class="table-head"><span>论文标题</span><span>最后编辑</span><span>字数</span></div><button v-for="draft in data.drafts" :key="draft.id" class="table-row" @click="open('/app/essay/editor')"><span><el-icon><Document /></el-icon>{{ draft.title }}</span><time>{{ draft.updatedAt }}</time><b>{{ draft.words.toLocaleString() }}</b></button></article>
        <article class="list-card"><header><h2><el-icon><FolderOpened /></el-icon>项目背景</h2><button>全部项目背景 <el-icon><ArrowRight /></el-icon></button></header><div class="table-head table-head--two"><span>项目名称</span><span>更新时间</span></div><div v-for="background in data.backgrounds" :key="background.id" class="table-row table-row--two"><span><el-icon><FolderOpened /></el-icon>{{ background.title }}</span><time>{{ background.updatedAt }}</time></div></article>
        <article class="material-card"><header><h2><el-icon><Files /></el-icon>素材数量</h2><span>最近更新</span></header><dl><div><el-icon><Files /></el-icon><dt>素材总数</dt><dd>{{ data.materialStats.total.toLocaleString() }}</dd></div><div><el-icon><TrendCharts /></el-icon><dt>过程实践</dt><dd>{{ data.materialStats.process }}</dd></div><div><el-icon><Document /></el-icon><dt>论据资料</dt><dd>{{ data.materialStats.evidence }}</dd></div></dl><p>素材 CRUD 已按现有 HTTP 契约接入；AI 生成素材仍由功能开关控制。</p></article>
      </section>
    </template>
  </main>
</template>

<style scoped>
.essay-home{min-height:calc(100vh - var(--platform-header-height));padding:22px 30px 28px;background:#f7f9fc;color:var(--color-text-primary)}
.essay-home__heading{display:flex;align-items:end;justify-content:space-between;margin-bottom:14px}.essay-home__heading p{margin:0 0 3px;color:var(--color-success);font-size:12px;font-weight:700}.essay-home h1{margin:0;font-size:26px}.essay-home__qualification{padding:9px 14px;border:1px solid var(--color-border);border-radius:6px;background:#fff;color:var(--color-text-secondary);font-size:13px}
.dev-notice{display:flex;height:40px;padding:0 15px;align-items:center;gap:10px;border:1px solid #b9dfdc;border-radius:7px;background:#f1fbfa;color:#177f79;font-size:13px}.dev-notice--neutral{border-color:var(--color-border);background:#fff;color:var(--color-text-secondary)}
.essay-state{display:grid;min-height:240px;margin-top:14px;place-items:center;border:1px solid var(--color-border);border-radius:8px;background:#fff;color:var(--color-text-muted)}.essay-state--error{align-content:center;gap:12px;color:var(--color-danger)}.essay-state button{padding:8px 14px;border:0;border-radius:5px;background:var(--color-essay-accent);color:#fff}
.workflow-card{margin-top:14px;padding:17px 24px 13px;border:1px solid var(--color-border);border-radius:8px;background:#fff;box-shadow:0 5px 16px rgb(23 59 122 / 5%)}.workflow-card ol{display:grid;margin:0;padding:0;grid-template-columns:repeat(6,1fr);list-style:none}.workflow-card li{position:relative;display:grid;place-items:center;gap:4px;color:var(--color-text-muted);font-size:13px}.workflow-card li:not(:last-child)::before{position:absolute;z-index:0;top:16px;left:58%;width:84%;height:2px;background:var(--color-border);content:""}.workflow-card li.is-done:not(:last-child)::before,.workflow-card li.is-active:not(:last-child)::before{background:var(--color-success)}.workflow-card li>span{z-index:1;display:grid;width:34px;height:34px;place-items:center;border:1px solid var(--color-border-strong);border-radius:50%;background:#edf1f6}.workflow-card .is-done>span,.workflow-card .is-active>span{border-color:var(--color-success);background:var(--color-success);color:#fff}.workflow-card .is-active strong,.workflow-card .is-active small{color:var(--color-success)}.workflow-card small{font-size:12px}
.essay-home__focus-grid{display:grid;margin-top:14px;grid-template-columns:1.08fr 1fr;gap:14px}.focus-card,.list-card,.material-card{min-width:0;border:1px solid var(--color-border);border-radius:8px;background:#fff;box-shadow:0 5px 16px rgb(23 59 122 / 5%)}.focus-card{min-height:250px;padding:17px 20px}.focus-card h2,.list-card h2,.material-card h2{margin:0;font-size:16px}.focus-card>header,.list-card>header,.material-card>header{display:flex;align-items:center;justify-content:space-between}.focus-card time{color:var(--color-text-muted);font-size:12px}.draft-summary{display:grid;margin:24px 0 23px;grid-template-columns:58px 1fr;gap:14px}.draft-icon{display:grid;width:54px;height:54px;place-items:center;border-radius:7px;background:var(--color-success);color:#fff;font-size:28px}.draft-summary h3{margin:0 0 7px;font-size:15px}.draft-summary p{margin:0;color:var(--color-text-secondary);font-size:12px}.draft-summary dl{display:flex;margin:16px 0 0;gap:28px}.draft-summary dl div{display:flex;gap:7px;font-size:12px}.draft-summary dt{color:var(--color-text-muted)}.draft-summary dd{margin:0;font-weight:600}.focus-card>button{display:flex;height:38px;align-items:center;justify-content:center;gap:10px;border:0;border-radius:5px;background:var(--color-success);color:#fff;font-weight:700}.focus-card--draft>button{width:190px}.focus-card .outline-button{width:100%;border:1px solid var(--color-cobalt);background:#fff;color:var(--color-cobalt)}.review-summary{display:grid;margin:19px 0 16px;grid-template-columns:126px 1fr;gap:14px}.score-ring{display:grid;width:98px;height:98px;align-content:center;justify-items:center;border:9px solid #ff7a00;border-left-color:#ffe2d1;border-radius:50%}.score-ring strong{font-size:34px}.score-ring span{font-size:11px}.review-summary p{margin:2px 0 9px;color:var(--color-text-secondary);font-size:12px;line-height:1.55}.review-summary hr{border:0;border-top:1px solid var(--color-border)}.review-summary b{font-size:12px}.review-summary ul{margin:6px 0 0;padding-left:16px;color:var(--color-text-secondary);font-size:12px}.review-summary li::marker{color:var(--color-coral)}
.essay-home__lower-grid{display:grid;margin-top:14px;grid-template-columns:1.02fr 1fr 1.04fr;gap:14px}.list-card,.material-card{min-height:235px;overflow:hidden}.list-card header,.material-card header{height:48px;padding:0 17px}.list-card h2,.material-card h2{display:flex;align-items:center;gap:7px}.list-card header button{display:flex;align-items:center;gap:4px;border:0;background:transparent;color:var(--color-cobalt);font-size:12px}.table-head,.table-row{display:grid;grid-template-columns:minmax(0,1fr) 88px 62px;align-items:center}.table-head{height:30px;padding:0 14px;background:#f6f8fb;color:var(--color-text-muted);font-size:11px}.table-row{width:100%;min-height:40px;padding:0 14px;border:0;border-top:1px solid var(--color-border);background:#fff;color:var(--color-text-secondary);font-size:11px;text-align:left}.table-row span{display:flex;min-width:0;align-items:center;gap:7px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.table-row time{text-align:center}.table-row b{text-align:right}.table-head--two,.table-row--two{grid-template-columns:minmax(0,1fr) 92px}.material-card dl{display:grid;margin:0 12px 13px;grid-template-columns:repeat(3,1fr);gap:9px}.material-card dl div{display:grid;padding:12px 8px;grid-template-columns:28px 1fr;align-items:center;border:1px solid var(--color-border);border-radius:6px;background:#f8fafc}.material-card dl .el-icon{grid-row:1/3;color:var(--color-success);font-size:21px}.material-card dt{font-size:10px}.material-card dd{margin:1px 0 0;font-size:20px;font-weight:700}.material-card p{margin:0;padding:14px 16px;border-top:1px solid var(--color-border);color:var(--color-text-muted);font-size:11px;line-height:1.6}.empty-copy{display:grid;min-height:130px;place-items:center;color:var(--color-text-muted);font-size:13px}
@media(max-width:1366px){.essay-home{padding:18px 22px}.workflow-card{padding-inline:16px}.focus-card{padding:14px 16px}.essay-home__lower-grid{gap:10px}.material-card dl{gap:5px}.material-card dl div{padding-inline:5px}}
@media(max-width:1280px){.essay-home{padding:16px}.essay-home__heading{margin-bottom:10px}.workflow-card{margin-top:10px;padding-top:12px}.essay-home__focus-grid,.essay-home__lower-grid{margin-top:10px;gap:9px}.focus-card{min-height:218px;padding:12px}.draft-summary{margin:12px 0}.review-summary{margin:12px 0 9px;grid-template-columns:105px 1fr}.score-ring{width:82px;height:82px}.essay-home__lower-grid{grid-template-columns:1fr 1fr}.list-card,.material-card{min-height:175px}.material-card{grid-column:1/-1}.material-card p{display:none}}
</style>
