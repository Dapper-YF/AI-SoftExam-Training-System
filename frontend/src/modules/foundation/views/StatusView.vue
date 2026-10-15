<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowLeft,
  House,
  Key,
  Refresh,
  Search,
  Warning,
} from '@element-plus/icons-vue'

const props = defineProps<{ code: '403' | '404' | '500'; title: string; description: string }>()
const router = useRouter()

const statusMeta = computed(() => {
  if (props.code === '403') {
    return {
      eyebrow: '访问权限受限',
      hint: '当前账号可能没有访问该功能的权限，请确认登录身份或联系管理员。',
      icon: Key,
      actionLabel: '返回首页',
    }
  }
  if (props.code === '500') {
    return {
      eyebrow: '服务暂时异常',
      hint: '系统未能完成本次请求，你可以稍后重试，学习数据不会因此丢失。',
      icon: Warning,
      actionLabel: '刷新页面',
    }
  }
  return {
    eyebrow: '页面未找到',
    hint: '访问地址可能已变更或内容已被移动，可以返回首页继续浏览。',
    icon: Search,
    actionLabel: '返回首页',
  }
})

function goHome(): void {
  void router.push('/')
}

function primaryAction(): void {
  if (props.code === '500') {
    window.location.reload()
    return
  }
  goHome()
}
</script>

<template>
  <main class="status-page" :class="`status-page--${code}`" :data-testid="`status-${code}`">
    <section class="status-shell">
      <div class="status-content">
        <router-link class="status-brand" to="/">
          <span><el-icon><House /></el-icon></span>
          <strong>AI高项培训系统</strong>
        </router-link>

        <div class="status-copy">
          <span class="status-eyebrow">{{ statusMeta.eyebrow }}</span>
          <div class="status-code">{{ code }}</div>
          <h1>{{ title }}</h1>
          <p>{{ description }}</p>
          <small>{{ statusMeta.hint }}</small>
        </div>

        <div class="status-actions">
          <el-button class="back-button" :icon="ArrowLeft" @click="router.back()">返回上一页</el-button>
          <el-button
            type="primary"
            :icon="code === '500' ? Refresh : House"
            data-testid="status-home-link"
            @click="primaryAction"
          >
            {{ statusMeta.actionLabel }}
          </el-button>
        </div>

        <span class="status-footnote">如问题持续出现，请稍后重试或联系平台管理员</span>
      </div>

      <div class="status-visual" aria-hidden="true">
        <div class="visual-orbit visual-orbit--outer"></div>
        <div class="visual-orbit visual-orbit--inner"></div>
        <span class="visual-dot visual-dot--one"></span>
        <span class="visual-dot visual-dot--two"></span>
        <div class="visual-core">
          <el-icon><component :is="statusMeta.icon" /></el-icon>
        </div>
        <span class="visual-code">{{ code }}</span>
      </div>
    </section>
  </main>
</template>

<style scoped>
.status-page {
  display: grid;
  min-height: 100vh;
  padding: 32px;
  place-items: center;
  overflow: hidden;
  background:
    radial-gradient(circle at 15% 8%, rgb(37 99 235 / 7%), transparent 30rem),
    radial-gradient(circle at 88% 90%, rgb(15 118 110 / 6%), transparent 27rem),
    #f4f7fb;
  color: #172033;
}

.status-shell {
  position: relative;
  display: grid;
  width: min(1040px, 100%);
  min-height: 580px;
  padding: 54px 58px;
  grid-template-columns: minmax(0, 1fr) minmax(340px, .78fr);
  align-items: center;
  gap: 48px;
  overflow: hidden;
  border: 1px solid rgb(203 213 225 / 78%);
  border-radius: 24px;
  background: rgb(255 255 255 / 96%);
  box-shadow: 0 30px 80px rgb(15 23 42 / 12%), 0 4px 16px rgb(15 23 42 / 5%);
}

.status-content {
  position: relative;
  z-index: 2;
  display: flex;
  min-height: 470px;
  flex-direction: column;
  align-items: flex-start;
}

.status-brand {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  color: #1e293b;
  text-decoration: none;
}

.status-brand > span {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  border-radius: 11px;
  background: #eff6ff;
  color: #2563eb;
  font-size: 17px;
}

.status-brand strong { font-size: 14px; }
.status-copy { margin: auto 0; }

.status-eyebrow {
  display: inline-flex;
  padding: 6px 9px;
  border-radius: 999px;
  background: #eff6ff;
  color: #2563eb;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: .08em;
}

.status-code {
  margin-top: 16px;
  color: #2563eb;
  font-size: clamp(54px, 7vw, 82px);
  font-weight: 780;
  line-height: 1;
  letter-spacing: -.06em;
}

.status-copy h1 {
  margin: 13px 0 10px;
  color: #172033;
  font-size: clamp(25px, 3vw, 34px);
  line-height: 1.25;
  letter-spacing: -.025em;
}

.status-copy p {
  max-width: 500px;
  margin: 0;
  color: #475569;
  font-size: 14px;
  line-height: 1.75;
}

.status-copy small {
  display: block;
  max-width: 500px;
  margin-top: 9px;
  color: #94a3b8;
  font-size: 11px;
  line-height: 1.7;
}

.status-actions { display: flex; gap: 10px; }
.status-actions .el-button { min-width: 116px; min-height: 42px; margin: 0; border-radius: 10px; font-weight: 600; }
.status-actions .el-button--primary { box-shadow: 0 8px 18px rgb(37 99 235 / 20%); }
.back-button { border-color: #d8e1ec; color: #475569; }
.status-footnote { margin-top: 18px; color: #b0bac7; font-size: 9px; }

.status-visual {
  position: relative;
  display: grid;
  width: min(390px, 34vw);
  aspect-ratio: 1;
  place-items: center;
  justify-self: center;
}

.status-visual::before {
  position: absolute;
  width: 78%;
  height: 78%;
  border-radius: 50%;
  background: radial-gradient(circle, rgb(37 99 235 / 11%), rgb(37 99 235 / 2%) 55%, transparent 72%);
  content: "";
  filter: blur(2px);
}

.visual-orbit {
  position: absolute;
  border: 1px solid rgb(37 99 235 / 16%);
  border-radius: 50%;
}

.visual-orbit--outer { width: 92%; height: 92%; }
.visual-orbit--inner { width: 65%; height: 65%; border-style: dashed; }

.visual-core {
  position: relative;
  z-index: 2;
  display: grid;
  width: 112px;
  height: 112px;
  place-items: center;
  border: 1px solid rgb(191 219 254 / 90%);
  border-radius: 34px;
  background: linear-gradient(145deg, #fff, #eaf2ff);
  box-shadow: 0 24px 50px rgb(37 99 235 / 16%);
  color: #2563eb;
  font-size: 45px;
  transform: rotate(-5deg);
}

.visual-core .el-icon { transform: rotate(5deg); }
.visual-code { position: absolute; right: 3%; bottom: 18%; color: rgb(37 99 235 / 13%); font-size: 54px; font-weight: 800; letter-spacing: -.06em; }
.visual-dot { position: absolute; z-index: 3; width: 11px; height: 11px; border: 3px solid white; border-radius: 50%; background: #2563eb; box-shadow: 0 3px 10px rgb(37 99 235 / 30%); }
.visual-dot--one { top: 15%; right: 23%; }
.visual-dot--two { bottom: 16%; left: 20%; width: 8px; height: 8px; background: #0f766e; }

.status-page--403 .status-eyebrow { background: #fff7ed; color: #c2410c; }
.status-page--403 .status-code, .status-page--403 .visual-core { color: #ea580c; }
.status-page--403 .visual-core { border-color: #fed7aa; background: linear-gradient(145deg, #fff, #fff7ed); box-shadow: 0 24px 50px rgb(234 88 12 / 16%); }
.status-page--403 .visual-orbit { border-color: rgb(234 88 12 / 16%); }
.status-page--403 .status-visual::before { background: radial-gradient(circle, rgb(234 88 12 / 10%), transparent 70%); }
.status-page--403 .visual-dot { background: #ea580c; }
.status-page--403 .visual-code { color: rgb(234 88 12 / 12%); }

.status-page--500 .status-eyebrow { background: #fef2f2; color: #dc2626; }
.status-page--500 .status-code, .status-page--500 .visual-core { color: #dc2626; }
.status-page--500 .visual-core { border-color: #fecaca; background: linear-gradient(145deg, #fff, #fef2f2); box-shadow: 0 24px 50px rgb(220 38 38 / 14%); }
.status-page--500 .visual-orbit { border-color: rgb(220 38 38 / 15%); }
.status-page--500 .status-visual::before { background: radial-gradient(circle, rgb(220 38 38 / 9%), transparent 70%); }
.status-page--500 .visual-dot { background: #dc2626; }
.status-page--500 .visual-code { color: rgb(220 38 38 / 11%); }

@media (max-width: 780px) {
  .status-page { padding: 20px; }
  .status-shell { min-height: 620px; padding: 42px 38px; grid-template-columns: 1fr; }
  .status-content { min-height: 520px; }
  .status-visual { position: absolute; right: -100px; bottom: -100px; width: 340px; opacity: .42; }
}

@media (max-width: 480px) {
  .status-page { padding: 0; place-items: stretch; background: white; }
  .status-shell { min-height: 100vh; padding: 28px 20px; border: 0; border-radius: 0; box-shadow: none; }
  .status-content { min-height: calc(100vh - 56px); }
  .status-actions { width: 100%; align-items: stretch; flex-direction: column-reverse; }
  .status-actions .el-button { width: 100%; }
  .status-visual { right: -155px; bottom: -110px; opacity: .3; }
}
</style>
