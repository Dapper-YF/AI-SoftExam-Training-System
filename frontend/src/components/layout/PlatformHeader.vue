<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowDown, Bell, QuestionFilled, Reading, SwitchButton, UserFilled } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useProductShellStore } from '@/stores/product-shell'
import { useQualificationStore } from '@/stores/qualification'

const { variant } = defineProps<{ variant: 'student' | 'admin' }>()

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const productShell = useProductShellStore()
const qualificationStore = useQualificationStore()
const productLabel = computed(() => productShell.navigation?.label)
const loginTarget = computed(() => ({
  path: '/login',
  query: route.fullPath === '/app/modules' ? {} : { redirect: route.fullPath },
}))

async function logout(): Promise<void> {
  try {
    await authStore.logout()
  } catch {
    ElMessage.warning('服务端退出未完成，本地登录状态已清除')
  } finally {
    qualificationStore.clear()
    await router.replace('/login')
  }
}
</script>

<template>
  <header
    class="platform-header"
    :class="[`platform-header--${variant}`]"
    data-testid="platform-header"
  >
    <button class="platform-brand" type="button" data-testid="platform-brand-home" aria-label="返回公共首页" @click="router.push('/')">
      <span class="brand-mark" aria-hidden="true"><el-icon><Reading /></el-icon></span>
      <strong>AI 高项培训系统</strong>
    </button>

    <span v-if="variant === 'student' && productLabel" class="current-product" data-testid="current-product">
      {{ productLabel }}
    </span>

    <div class="header-actions">
      <el-tooltip content="暂无新通知" placement="bottom">
        <button class="header-icon-button" type="button" aria-label="通知"><el-icon><Bell /></el-icon></button>
      </el-tooltip>
      <el-tooltip content="帮助与支持" placement="bottom">
        <button class="header-icon-button" type="button" aria-label="帮助"><el-icon><QuestionFilled /></el-icon></button>
      </el-tooltip>
      <div v-if="!authStore.isAuthenticated" class="header-auth-entry" data-testid="platform-anonymous-entry">
        <RouterLink class="user-menu" :to="loginTarget" data-testid="platform-login-link">
          <span class="user-avatar"><el-icon><UserFilled /></el-icon></span>
          <span class="user-name">登录</span>
        </RouterLink>
        <RouterLink class="header-register-link" to="/register" data-testid="platform-register-link">注册</RouterLink>
      </div>
      <el-dropdown v-else trigger="click">
        <button class="user-menu" type="button" data-testid="platform-user-menu" @click="router.push('/app/profile')">
          <span class="user-avatar"><el-icon><UserFilled /></el-icon></span>
          <span class="user-name">{{ authStore.user?.displayName }}</span>
          <el-icon><ArrowDown /></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item data-testid="platform-profile" @click="router.push('/app/profile')">
              <el-icon><UserFilled /></el-icon>Profile
            </el-dropdown-item>
            <el-dropdown-item data-testid="platform-logout" @click="logout">
              <el-icon><SwitchButton /></el-icon>退出登录
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </header>
</template>

<style scoped>
.header-auth-entry { display: flex; align-items: center; gap: 10px; }
.header-register-link { color: #1769e0; font-size: 14px; font-weight: 700; text-decoration: none; white-space: nowrap; }
.current-product { margin-left: 16px; padding-left: 16px; white-space: nowrap; }
.header-login-button {
  height: 34px;
  padding: 0 14px;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  background: #fff;
  color: var(--color-primary);
  cursor: pointer;
  font-weight: 700;
}
@media (max-width: 1280px) {
  .current-product { margin-left: 8px; padding-left: 8px; }
  .user-name { display: none; }
}
</style>
