<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ArrowLeft, Menu } from '@element-plus/icons-vue'
import { useRoute } from 'vue-router'
import PublicPortalHeader from './PublicPortalHeader.vue'

const route = useRoute()
const menuOpen = ref(false)
const pageTitle = computed(() => route.meta.pageTitle ?? '学习中心')
const isProfilePage = computed(() => route.name === 'user-profile')
const backTarget = computed(() => route.path.startsWith('/app/practice') ? '/practice' : '/')

watch(() => route.fullPath, () => { menuOpen.value = false })
</script>

<template>
  <div class="learning-shell" data-testid="personal-learning-layout">
    <PublicPortalHeader />
    <div v-if="!isProfilePage" class="learning-shell__bar">
      <div class="learning-shell__bar-inner">
        <RouterLink class="learning-back" :to="backTarget" aria-label="返回上级页面">
          <el-icon><ArrowLeft /></el-icon><span>返回</span>
        </RouterLink>
        <nav class="learning-nav" :class="{ 'is-open': menuOpen }" aria-label="个人学习导航" data-testid="personal-learning-nav">
          <RouterLink to="/practice">题库目录</RouterLink>
          <RouterLink to="/tools">学习工具</RouterLink>
          <RouterLink to="/app/practice/history">练习历史</RouterLink>
          <RouterLink to="/ai">AI辅助</RouterLink>
          <RouterLink to="/app/essay/home">论文训练</RouterLink>
          <RouterLink to="/app/profile">个人资料</RouterLink>
        </nav>
        <button
          class="learning-menu-trigger"
          type="button"
          aria-label="打开个人学习导航"
          :aria-expanded="menuOpen"
          data-testid="personal-mobile-menu-trigger"
          @click="menuOpen = !menuOpen"
        >
          <el-icon><Menu /></el-icon>
        </button>
      </div>
    </div>
    <div v-if="!isProfilePage" class="learning-breadcrumb" aria-label="面包屑">
      <RouterLink to="/">首页</RouterLink><span>/</span><span>学习中心</span><span>/</span><strong>{{ pageTitle }}</strong>
    </div>
    <main class="learning-content">
      <RouterView v-slot="{ Component, route: currentRoute }">
        <component :is="Component" :key="currentRoute.path" />
      </RouterView>
    </main>
  </div>
</template>

<style scoped>
.learning-shell { min-width: 0; min-height: 100vh; background: var(--color-page-canvas); color: var(--color-text-primary); }
.learning-shell__bar { border-bottom: 1px solid var(--color-border); background: #fff; }
.learning-shell__bar-inner { display: flex; width: min(100%, 1440px); min-height: 52px; padding: 0 24px; margin: 0 auto; align-items: center; gap: 24px; }
.learning-back { display: inline-flex; min-height: 36px; align-items: center; gap: 6px; color: var(--color-text-secondary); font-size: 14px; text-decoration: none; }
.learning-back:hover { color: var(--color-practice-accent); }
.learning-nav { display: flex; min-width: 0; align-self: stretch; align-items: center; gap: 28px; }
.learning-nav a { display: inline-flex; height: 100%; align-items: center; border-bottom: 2px solid transparent; color: var(--color-text-secondary); font-size: 14px; font-weight: 600; text-decoration: none; white-space: nowrap; }
.learning-nav a:hover, .learning-nav a.router-link-active { border-bottom-color: var(--color-practice-accent); color: var(--color-brand); }
.learning-menu-trigger { display: none; }
.learning-breadcrumb { display: flex; width: min(100%, 1440px); min-height: 46px; padding: 0 28px; margin: 0 auto; align-items: center; gap: 8px; color: var(--color-text-muted); font-size: 13px; }
.learning-breadcrumb a { color: var(--color-text-secondary); text-decoration: none; }
.learning-breadcrumb strong { color: var(--color-text-primary); font-weight: 600; }
.learning-content { min-width: 0; }

@media (max-width: 900px) {
  .learning-shell__bar-inner { position: relative; min-height: 48px; padding: 0 14px; justify-content: space-between; }
  .learning-menu-trigger { display: inline-grid; width: 38px; height: 38px; padding: 0; place-items: center; border: 1px solid var(--color-border); border-radius: 6px; background: #fff; color: var(--color-brand); font-size: 20px; }
  .learning-nav { position: absolute; z-index: 25; top: 47px; right: 10px; left: 10px; display: none; height: auto; padding: 8px; border: 1px solid var(--color-border); background: #fff; box-shadow: var(--platform-shadow); grid-template-columns: 1fr 1fr; }
  .learning-nav.is-open { display: grid; }
  .learning-nav a { min-height: 42px; padding: 0 10px; border-bottom: 0; }
  .learning-breadcrumb { min-height: 42px; padding: 0 14px; overflow: hidden; white-space: nowrap; }
  .learning-breadcrumb strong { overflow: hidden; text-overflow: ellipsis; }
}
</style>
