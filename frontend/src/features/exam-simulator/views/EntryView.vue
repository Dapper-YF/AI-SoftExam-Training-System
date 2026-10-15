<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { Document, EditPen } from '@element-plus/icons-vue'
import SimulatorBrandHeader from '../components/SimulatorBrandHeader.vue'
import { useExamSimulatorStore } from '../stores/exam-simulator'
import { useQualificationStore } from '@/stores/qualification'

const router = useRouter()
const examStore = useExamSimulatorStore()
const qualificationStore = useQualificationStore()
const qualification = computed(() => qualificationStore.selectedQualification)
const isSenior = computed(() => qualification.value?.levelCode === 'ADVANCED')
const supported = computed(() => qualification.value?.levelCode === 'ADVANCED' || qualification.value?.levelCode === 'INTERMEDIATE')
const combinedTitle = computed(() => '综合知识 + 案例分析')
const combinedDescription = computed(() => '联考科目，综合知识交卷后剩余时间继续用于案例分析。')

function openEntry(entryCode: 'LINKED' | 'ESSAY' | 'BASIC_KNOWLEDGE' | 'APPLICATION_TECH'): void {
  if (!qualification.value || !supported.value) return
  examStore.clearClientSession()
  void router.push({ name: 'exam-login', params: { entryCode } })
}
</script>

<template>
  <main class="screen-page entry-view" data-testid="p1-entry-page">
    <button type="button" class="entry-view__return" data-testid="entry-return-practice" @click="router.push('/practice')">返回刷题</button>
    <SimulatorBrandHeader />
    <section class="entry-view__content">
      <template v-if="qualificationStore.status === 'loading'">
        <div class="entry-view__state">正在读取当前资格…</div>
      </template>
      <template v-else-if="qualificationStore.status === 'error'">
        <div class="entry-view__state entry-view__state--error"><strong>资格读取失败</strong><span>{{ qualificationStore.errorMessage }}</span><el-button @click="qualificationStore.load()">重试</el-button></div>
      </template>
      <template v-else-if="!qualification">
        <div class="entry-view__state"><strong>请先登录并完成备考资格设置</strong><el-button type="primary" @click="router.push('/login?redirect=/exam-simulator')">前往登录</el-button></div>
      </template>
      <template v-else-if="!supported || qualification.available === false">
        <div class="entry-view__state"><strong>{{ qualification.name }}暂未开放模拟考试</strong><span>{{ qualification.unavailableReason || '当前资格暂未配置模拟考试场次。' }}</span></div>
      </template>
      <template v-else>
        <div class="entry-view__notice">
          <h1>{{ qualification.name }}模拟考试说明</h1>
          <ol>
            <li>本系统用于熟悉计算机化考试环境和作答方式，题型、题量、分值及页面内容以正式考试安排为准。</li>
            <li>本系统使用模拟准考证号和证件号登录，不接入真实报名或准考证数据。</li>
            <li>{{ isSenior ? '上午综合知识与案例分析采用联考；下午论文为独立考试场次。' : '基础知识为上午考试场次，应用技术为下午考试场次，两场分别独立计时和交卷。' }}</li>
            <li>考试过程中不提供即时答案、解析、AI 答疑或学习导航内容。</li>
          </ol>
        </div>
        <div class="entry-view__choices" aria-label="选择当前资格的模拟考试场次">
          <button v-if="isSenior" type="button" class="entry-choice" data-testid="entry-linked" @click="openEntry('LINKED')">
            <el-icon><Document /></el-icon><span><strong>{{ combinedTitle }}</strong><small>{{ combinedDescription }}</small></span>
          </button>
          <button v-if="isSenior" type="button" class="entry-choice" data-testid="entry-essay" @click="openEntry('ESSAY')">
            <el-icon><EditPen /></el-icon><span><strong>论文</strong><small>独立考试场次，使用独立倒计时和交卷规则。</small></span>
          </button>
          <button v-if="!isSenior" type="button" class="entry-choice" data-testid="entry-basic" @click="openEntry('BASIC_KNOWLEDGE')">
            <el-icon><Document /></el-icon><span><strong>基础知识</strong><small>上午科目，独立考试场次与倒计时。</small></span>
          </button>
          <button v-if="!isSenior" type="button" class="entry-choice" data-testid="entry-application" @click="openEntry('APPLICATION_TECH')">
            <el-icon><EditPen /></el-icon><span><strong>应用技术</strong><small>下午科目，独立考试场次与倒计时。</small></span>
          </button>
        </div>
      </template>
    </section>
  </main>
</template>

<style scoped>
.entry-view { position: relative; min-height: 100%; background: #fff; }
.entry-view__return { position: absolute; top: 18px; left: 24px; z-index: 2; border: 1px solid #8bc8f4; border-radius: 2px; color: #0877c6; background: #fff; padding: 7px 12px; cursor: pointer; }
.entry-view__content { width: min(1380px, calc(100% - 80px)); margin: 0 auto; padding: clamp(46px, 7vh, 86px) 24px 40px; }
.entry-view__notice h1 { margin: 0 0 28px; color: #1f2937; font-size: 26px; }
.entry-view__notice ol { margin: 0; padding-left: 34px; color: #303846; font-size: clamp(17px, 1.2vw, 21px); line-height: 2.25; }
.entry-view__choices { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 46px; width: min(930px, 100%); margin: 62px auto 0; }
.entry-view__choices--single { grid-template-columns: minmax(0, 480px); justify-content: center; }
.entry-choice { min-height: 112px; display: grid; grid-template-columns: 54px minmax(0, 1fr); gap: 18px; align-items: center; padding: 20px 26px; border: 1px solid #8bc8f4; border-radius: 2px; color: #0877c6; background: #e5f4ff; cursor: pointer; text-align: left; }
.entry-choice:hover { border-color: #0877c6; background: #d7efff; }
.entry-choice .el-icon { font-size: 42px; }
.entry-choice strong, .entry-choice small { display: block; }
.entry-choice strong { font-size: 23px; font-weight: 600; }
.entry-choice small { margin-top: 8px; color: #5c6f82; font-size: 13px; line-height: 1.5; }
.entry-view__state { display: grid; justify-items: start; gap: 16px; padding: 80px 0; color: #526174; }
.entry-view__state--error strong { color: #b42318; }
@media (max-width: 700px) { .entry-view__content { width: min(100% - 32px, 1380px); padding-top: 60px; } .entry-view__choices { grid-template-columns: 1fr; gap: 18px; margin-top: 36px; } .entry-view__notice ol { font-size: 16px; line-height: 1.9; } }
</style>
