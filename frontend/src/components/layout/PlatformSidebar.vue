<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Clock, Collection, Document, Expand, Fold, Grid, HomeFilled, List, Monitor, Reading, StarFilled } from '@element-plus/icons-vue'
import { useProductShellStore } from '@/stores/product-shell'
import { useAuthStore } from '@/stores/auth'
import { adminShellMenus } from '@/app/router/platform-route-whitelist'

const props = defineProps<{ collapsed: boolean; variant: 'student' | 'admin' }>()
const emit = defineEmits<{ toggle: [] }>()
const route = useRoute()
const router = useRouter()
const productShell = useProductShellStore()
const authStore = useAuthStore()
const iconMap = {
  Collection,
  Grid,
  HomeFilled,
  Monitor,
  home: HomeFilled,
  library: Collection,
  history: Clock,
  edit: Document,
  report: List,
  exam: Monitor,
  review: StarFilled,
  resources: Reading,
}
const activePath = computed(() => route.path)
const sidebarLabel = computed(() => props.variant === 'admin' ? '管理端' : productShell.navigation?.label)
const navigationItems = computed(() => props.variant === 'admin'
  ? adminShellMenus(authStore.menus).map((item) => ({
    code: item.code,
    label: item.name,
    routePath: item.routePath,
    icon: item.icon,
  }))
  : productShell.navigation?.items ?? [])
const footerTarget = computed(() => {
  if (props.variant !== 'admin') return '/app/modules'
  return '/app/home'
})
const footerLabel = computed(() => {
  if (props.variant !== 'admin') return '返回模块首页'
  return '返回用户首页'
})

function navigate(path: string): void {
  if (route.path !== path) void router.push(path)
}
</script>

<template>
  <aside class="platform-sidebar" :class="{ 'platform-sidebar--collapsed': props.collapsed }" data-testid="platform-sidebar">
    <div class="sidebar-product">
      <el-icon><Reading /></el-icon><span v-if="!props.collapsed">{{ sidebarLabel }}</span>
    </div>
    <el-menu :default-active="activePath" :collapse="props.collapsed" class="platform-menu">
      <el-menu-item
        v-for="item in navigationItems"
        :key="item.code"
        :index="item.routePath"
        @click="navigate(item.routePath)"
      >
        <el-icon><component :is="iconMap[item.icon as keyof typeof iconMap] ?? Collection" /></el-icon>
        <template #title>{{ item.label }}</template>
      </el-menu-item>
    </el-menu>
    <div class="sidebar-footer">
      <button class="module-return" type="button" @click="navigate(footerTarget)">
        <el-icon><ArrowLeft /></el-icon><span v-if="!props.collapsed">{{ footerLabel }}</span>
      </button>
      <button class="sidebar-toggle" type="button" aria-label="切换侧栏" @click="emit('toggle')">
        <el-icon><Expand v-if="props.collapsed" /><Fold v-else /></el-icon>
      </button>
    </div>
  </aside>
</template>
