<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, ChatDotRound, Check, Download, EditPen, Refresh, Right } from '@element-plus/icons-vue'
import { ApiClientError } from '@/api/client'
import { getEssayGateway } from '@/modules/essay/api/gateway'
import { errorPresentation } from '@/modules/essay/essay-state'
import { createReviewQuestionRequest } from '@/modules/essay/review-binding'
import type { EssayReportData, ReviewIssue } from '@/modules/essay/types'

defineOptions({ name: 'EssayReportsSlotView' })
const router = useRouter()
const report = ref<EssayReportData | null>(null)
const selectedIssueId = ref('')
const loadError = ref<string | null>(null)
const question = ref('')
const followup = ref('')
const acceptedRewrite = ref('')
const sourceMismatch = ref(false)
const selectedIssue = computed(() => report.value?.issues.find((item) => item.id === selectedIssueId.value) ?? report.value?.issues[0])
const severityCounts = computed(() => ({
  serious: report.value?.issues.filter((item) => item.severity === '严重问题').length ?? 0,
  general: report.value?.issues.filter((item) => item.severity === '一般问题').length ?? 0,
  optimized: report.value?.issues.filter((item) => item.severity === '已优化建议').length ?? 0,
}))

async function load(): Promise<void> {
  try {
    report.value = await (await getEssayGateway()).loadReport()
    selectedIssueId.value = report.value.issues[0]?.id ?? ''
  } catch (reason) { loadError.value = errorPresentation(reason instanceof ApiClientError ? reason.status : (reason as { status?: number }).status).message }
}
function selectIssue(issue: ReviewIssue): void { selectedIssueId.value = issue.id; sourceMismatch.value = !report.value?.paragraphs.some((item) => item.id === issue.paragraphId) }
function selectSource(issueId: string): void {
  const issue = report.value?.issues.find((item) => item.id === issueId)
  if (issue) selectIssue(issue)
  else sourceMismatch.value = true
}
function acceptRewrite(copy: string): void { acceptedRewrite.value = copy }
function askIssue(): void {
  if (!question.value.trim() || !selectedIssue.value || !report.value) return
  const request = createReviewQuestionRequest(report.value, selectedIssue.value.id, question.value)
  followup.value = `已绑定 reviewTaskId=${request.reviewTaskId} · resultId=${request.resultId} · issueId=${request.issueId}。追问接口尚未联调，本次未发送模型请求。`
  question.value = ''
}
onMounted(load)
</script>

<template>
  <main class="essay-report" data-testid="essay-reports-slot" data-route-identity="essay.reports">
    <header class="report-topbar">
      <button type="button" @click="router.push('/app/essay/editor')"><el-icon><ArrowLeft /></el-icon>返回草稿</button>
      <template v-if="report"><h1>论文批改报告</h1><strong>· {{ report.title }}</strong><span>评分规则版本：{{ report.rubricVersion }}</span><span>批改时间：{{ report.reviewedAt }}</span><button class="continue-button" @click="router.push('/app/essay/editor')">继续修改</button></template>
    </header>
    <div v-if="loadError" class="report-error">{{ loadError }}。报告接口未返回真实结果，当前不展示模拟评分。</div>
    <template v-if="report">
      <section class="score-summary">
        <div class="total-score"><span>总得分</span><strong>{{ report.score }}</strong><b>/ 100</b><small>良好　相比上次 ↑ {{ report.previousDelta }} 分</small></div>
        <div v-for="dimension in report.dimensions" :key="dimension.label" class="dimension" :class="`tone-${dimension.tone}`"><span>{{ dimension.label }}</span><p><strong>{{ dimension.score }}</strong> / {{ dimension.total }}</p><i><b :style="{width:`${dimension.score/dimension.total*100}%`}" /></i><small>{{ Math.round(dimension.score/dimension.total*100) }}%</small></div>
        <div class="overall"><b>总体评语</b><p>{{ report.summary }}</p><button>查看详细评语 <el-icon><Right /></el-icon></button></div>
      </section>

      <section class="report-workspace">
        <article class="source-panel">
          <header><h2>原文内容</h2><div><button>查看全文</button><label><input type="checkbox">只看问题</label></div></header>
          <div class="source-copy">
            <section v-for="paragraph in report.paragraphs" :key="paragraph.id" :class="{ 'is-active': paragraph.issueIds.includes(selectedIssueId) }" :data-testid="paragraph.issueIds.includes('issue-2') ? 'report-source-2' : undefined">
              <b>{{ paragraph.index }}</b><p>{{ paragraph.text }} <button v-for="issueId in paragraph.issueIds" :key="issueId" :class="{ 'is-selected': issueId === selectedIssueId }" @click="selectSource(issueId)">{{ report.issues.findIndex(item=>item.id===issueId)+1 }}</button></p>
            </section>
          </div>
          <footer><span><i class="severity-serious"/>严重问题</span><span><i class="severity-general"/>一般问题</span><span><i class="severity-optimized"/>已优化建议</span><span><i class="severity-current"/>当前查看</span></footer>
        </article>

        <aside class="issues-panel">
          <section class="issues-list"><header><h2>问题列表 <small>（共 {{ report.issues.length }} 条）</small></h2><div><span>全部 {{ report.issues.length }}</span><span>严重问题 {{ severityCounts.serious }}</span><span>一般问题 {{ severityCounts.general }}</span><span>已优化建议 {{ severityCounts.optimized }}</span></div></header>
            <button v-for="(issue,index) in report.issues" :key="issue.id" :data-testid="issue.id==='issue-2' ? 'report-issue-2' : undefined" :aria-current="issue.id===selectedIssueId ? 'true' : undefined" :class="[`severity-${issue.severity==='严重问题'?'serious':issue.severity==='一般问题'?'general':'optimized'}`, { 'is-active': issue.id===selectedIssueId }]" @click="selectIssue(issue)"><b>{{ index+1 }}</b><span>{{ issue.severity }}</span><strong>{{ issue.title }}</strong><em>{{ issue.deduction }} 分</em><small>第 {{ report.paragraphs.find(item=>item.id===issue.paragraphId)?.index ?? '-' }} 段</small></button>
          </section>
          <section v-if="selectedIssue" class="issue-detail">
            <header><h3>当前查看问题：<b>{{ selectedIssue.title }}</b></h3><span>建议扣分：<b>{{ selectedIssue.deduction }} 分</b></span></header>
            <div v-if="sourceMismatch" class="source-mismatch">原文版本不匹配，无法可靠定位。</div>
            <dl><div><dt>原文片段</dt><dd>{{ selectedIssue.source }}</dd></div><div><dt>扣分原因</dt><dd>{{ selectedIssue.reason }}</dd></div><div><dt>修改建议</dt><dd>{{ selectedIssue.suggestion }}</dd></div></dl>
            <div class="rewrites"><header><b>参考改写（可选其一）</b><button><el-icon><Refresh /></el-icon>换一批</button></header><button v-for="(copy,index) in selectedIssue.rewrites" :key="copy" :class="{ 'is-accepted': copy===acceptedRewrite }" @click="acceptRewrite(copy)"><b>{{ index+1 }}</b><span>{{ copy }}</span><el-icon v-if="copy===acceptedRewrite"><Check /></el-icon></button></div>
            <section class="issue-question" data-testid="issue-question-context"><header><b>针对该问题追问</b><span>{{ report.reviewTaskId }} / {{ report.resultId }} / {{ selectedIssue.id }}</span></header><p v-if="followup">{{ followup }}</p><form data-testid="issue-question-form" @submit.prevent="askIssue"><input v-model="question" data-testid="issue-question-input" placeholder="仅追问当前批改问题"><button aria-label="发送问题"><el-icon><ChatDotRound /></el-icon></button></form></section>
          </section>
        </aside>
      </section>

      <footer class="report-actions"><button><el-icon><Download /></el-icon>批改报告下载</button><button @click="router.push('/app/essay/editor')"><el-icon><ArrowLeft /></el-icon>返回草稿</button><button><el-icon><EditPen /></el-icon>逐条修改</button><button>上一条问题</button><button>下一条问题 <el-icon><Right /></el-icon></button><button class="primary" @click="router.push('/app/essay/editor')">保存并继续修改</button></footer>
    </template>
  </main>
</template>

<style scoped>
.essay-report{height:calc(100vh - var(--platform-header-height));min-height:648px;overflow:hidden;background:#f6f8fb;color:var(--color-text-primary)}button{font:inherit}.report-topbar{display:flex;height:62px;padding:0 20px;align-items:center;gap:15px;border-bottom:1px solid var(--color-border);background:#fff}.report-topbar>button:first-child{display:flex;height:34px;padding:0 12px;align-items:center;gap:6px;border:1px solid var(--color-border);border-radius:5px;background:#fff}.report-topbar h1{margin:0 0 0 8px;font-size:20px}.report-topbar>strong{font-size:12px}.report-topbar>span{color:var(--color-text-muted);font-size:10px}.continue-button{height:38px;margin-left:auto;padding:0 22px;border:0;border-radius:5px;background:var(--color-cobalt);color:#fff;font-weight:700}.report-error{display:grid;height:120px;place-items:center;color:var(--color-danger)}
.score-summary{display:grid;height:138px;margin:10px 20px;padding:12px 16px;grid-template-columns:190px repeat(5,minmax(100px,1fr)) 2fr;gap:14px;border:1px solid var(--color-border);border-radius:7px;background:#fff}.total-score{display:grid;grid-template-columns:auto 1fr;align-content:center;border-right:1px solid var(--color-border)}.total-score>span{grid-column:1/-1;font-size:12px}.total-score>strong{color:#ff3d3d;font-size:46px;line-height:1}.total-score>b{align-self:end;margin-bottom:7px;color:var(--color-text-secondary);font-size:20px}.total-score>small{grid-column:1/-1;margin-top:8px;color:var(--color-text-muted)}.dimension{display:grid;align-content:center;gap:8px;color:var(--color-text-secondary);font-size:10px}.dimension>span{font-size:11px}.dimension p{margin:0}.dimension p strong{color:var(--color-cobalt);font-size:20px}.dimension>i{height:4px;border-radius:4px;background:var(--color-border)}.dimension>i b{display:block;height:100%;border-radius:4px;background:var(--color-cobalt)}.dimension.tone-teal p strong{color:var(--color-success)}.dimension.tone-teal>i b{background:var(--color-success)}.dimension.tone-amber p strong{color:var(--color-amber)}.dimension.tone-amber>i b{background:var(--color-amber)}.dimension.tone-coral p strong{color:var(--color-coral)}.dimension.tone-coral>i b{background:var(--color-coral)}.overall{padding-left:14px;border-left:1px solid var(--color-border)}.overall>b{font-size:12px}.overall p{margin:8px 0;color:var(--color-text-secondary);font-size:10px;line-height:1.6}.overall button{float:right;border:0;background:transparent;color:var(--color-cobalt);font-size:10px}
.report-workspace{display:grid;height:calc(100% - 274px);margin:0 20px;grid-template-columns:1.02fr 1.18fr;gap:14px}.source-panel,.issues-panel{min-width:0;overflow:hidden;border:1px solid var(--color-border);border-radius:7px;background:#fff}.source-panel{display:grid;grid-template-rows:46px minmax(0,1fr) 40px}.source-panel>header{display:flex;padding:0 15px;align-items:center;justify-content:space-between;border-bottom:1px solid var(--color-border)}.source-panel h2,.issues-list h2{margin:0;font-size:15px}.source-panel>header div{display:flex;align-items:center;gap:12px;font-size:10px}.source-panel>header button{padding:6px 10px;border:1px solid var(--color-border);border-radius:4px;background:#fff;color:var(--color-cobalt)}.source-copy{overflow-y:auto;padding:12px 16px}.source-copy section{display:grid;padding:9px 7px;grid-template-columns:27px 1fr;border-radius:5px}.source-copy section.is-active{background:#edf5ff;box-shadow:inset 3px 0 var(--color-cobalt)}.source-copy section>b{display:grid;width:20px;height:20px;place-items:center;border:1px solid var(--color-border-strong);border-radius:4px;color:var(--color-text-secondary);font-size:10px}.source-copy p{margin:0;color:var(--color-text-secondary);font-size:12px;line-height:2}.source-copy p button{display:inline-grid;width:20px;height:20px;margin:0 3px;place-items:center;border:0;border-radius:4px;background:var(--color-amber);color:#fff;font-size:10px;font-weight:700}.source-copy p button:first-of-type{background:var(--color-coral)}.source-copy p button.is-selected{outline:2px solid var(--color-cobalt);outline-offset:2px}.source-panel>footer{display:flex;padding:0 15px;align-items:center;gap:20px;border-top:1px solid var(--color-border);font-size:9px}.source-panel>footer span{display:flex;align-items:center;gap:5px}.source-panel>footer i{width:10px;height:10px;border-radius:2px;background:var(--color-coral)}.source-panel>footer .severity-general{background:var(--color-amber)}.source-panel>footer .severity-optimized{background:var(--color-success)}.source-panel>footer .severity-current{background:var(--color-cobalt)}
.issues-panel{display:grid;grid-template-rows:230px minmax(0,1fr)}.issues-list{overflow-y:auto;border-bottom:1px solid var(--color-border)}.issues-list>header{display:flex;height:46px;padding:0 12px;align-items:center;justify-content:space-between}.issues-list>header div{display:flex;gap:5px}.issues-list>header div span{padding:4px 7px;border-radius:4px;background:#edf3ff;color:var(--color-cobalt);font-size:9px}.issues-list>button{display:grid;width:100%;height:36px;padding:0 10px;grid-template-columns:24px 66px minmax(0,1fr) 44px 36px;align-items:center;border:0;border-top:1px solid var(--color-border);background:#fff;color:var(--color-text-secondary);font-size:9px;text-align:left}.issues-list>button>b{display:grid;width:18px;height:18px;place-items:center;border-radius:4px;background:var(--color-coral);color:#fff}.issues-list>button>span{color:var(--color-coral)}.issues-list>button>strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.issues-list>button>em{color:var(--color-coral);font-style:normal;text-align:center}.issues-list>button>small{text-align:right}.issues-list>button.severity-general>b{background:var(--color-amber)}.issues-list>button.severity-general>span{color:var(--color-amber)}.issues-list>button.severity-optimized>b{background:var(--color-success)}.issues-list>button.severity-optimized>span{color:var(--color-success)}.issues-list>button.is-active{background:#edf5ff;outline:2px solid var(--color-cobalt);outline-offset:-2px}.issue-detail{overflow-y:auto;padding:10px 14px}.issue-detail>header{display:flex;align-items:center;justify-content:space-between}.issue-detail h3{margin:0;font-size:11px}.issue-detail>header span{font-size:10px}.issue-detail>header span b{color:var(--color-coral)}.source-mismatch{margin-top:8px;padding:6px;border-radius:4px;background:#fff1f1;color:var(--color-coral);font-size:10px}.issue-detail dl{display:grid;margin:9px 0;gap:6px}.issue-detail dl div{display:grid;grid-template-columns:62px 1fr;font-size:10px}.issue-detail dt{font-weight:700}.issue-detail dd{margin:0;color:var(--color-text-secondary)}.issue-detail dl div:first-child dd{padding:5px;border:1px solid #ffc8c8;border-radius:4px;background:#fff7f7}.rewrites header,.issue-question header{display:flex;align-items:center;justify-content:space-between;font-size:10px}.rewrites header button{border:0;background:transparent;color:var(--color-cobalt)}.rewrites>button{display:grid;width:100%;min-height:25px;padding:3px 6px;grid-template-columns:20px 1fr 18px;align-items:center;border:1px solid #b9dfdc;border-top:0;background:#f5fbfa;color:var(--color-text-secondary);font-size:9px;text-align:left}.rewrites>button:first-of-type{border-top:1px solid #b9dfdc;border-radius:4px 4px 0 0}.rewrites>button:last-of-type{border-radius:0 0 4px 4px}.rewrites>button>b{display:grid;width:16px;height:16px;place-items:center;border-radius:50%;background:var(--color-success);color:#fff}.rewrites>button.is-accepted{background:#e5f7f4}.issue-question{margin-top:9px;padding-top:8px;border-top:1px solid var(--color-border)}.issue-question header span{color:var(--color-text-muted)}.issue-question p{margin:6px 0;padding:6px;border-radius:4px;background:#f6f8fb;color:var(--color-text-secondary);font-size:9px}.issue-question form{display:flex;margin-top:6px;border:1px solid var(--color-border);border-radius:4px}.issue-question input{height:27px;padding:0 7px;flex:1;border:0;outline:0;font-size:9px}.issue-question form button{width:30px;border:0;background:transparent;color:var(--color-cobalt)}
.report-actions{display:grid;height:54px;margin-top:10px;padding:0 20px;grid-template-columns:150px 150px 150px 1fr 1fr 180px;align-items:center;gap:14px;border-top:1px solid var(--color-border);background:#fff}.report-actions button{display:flex;height:34px;align-items:center;justify-content:center;gap:7px;border:1px solid var(--color-border);border-radius:5px;background:#fff;color:var(--color-text-secondary)}.report-actions button.primary{border-color:var(--color-cobalt);background:var(--color-cobalt);color:#fff}
@media(max-width:1366px){.score-summary{margin-inline:14px;grid-template-columns:160px repeat(5,minmax(86px,1fr)) 1.8fr;gap:9px}.report-workspace{margin-inline:14px;gap:9px}.report-topbar{padding-inline:14px;gap:10px}.report-actions{padding-inline:14px;grid-template-columns:130px 130px 130px 1fr 1fr 165px;gap:8px}}
@media(max-width:1280px){.score-summary{height:126px;margin:7px 10px;padding:9px;grid-template-columns:130px repeat(5,minmax(75px,1fr)) 1.65fr;gap:6px}.total-score>strong{font-size:38px}.dimension p strong{font-size:17px}.overall p{line-height:1.35}.report-workspace{height:calc(100% - 250px);margin-inline:10px;gap:7px;grid-template-columns:1fr 1.12fr}.report-actions{height:48px;margin-top:7px;padding-inline:10px;grid-template-columns:110px 110px 110px 1fr 1fr 150px;gap:6px}.report-actions button{height:31px;font-size:10px}.source-copy{padding:8px}.issue-detail{padding:8px 10px}}
</style>
