<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { DataAnalysis, Menu, SwitchButton, UserFilled } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

const menuOpen = ref(false)
const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const loginTarget = computed(() => ({
  path: '/login',
  query: route.path === '/' ? {} : { redirect: route.fullPath },
}))
const workspaceTarget = computed(() => '/app/profile')
const canEnterAdmin = computed(() =>
  authStore.roles.includes('ADMIN') && authStore.permissions.includes('admin:home:view'),
)

function closeMenu(): void {
  menuOpen.value = false
}

async function logout(): Promise<void> {
  try {
    await authStore.logout()
  } finally {
    closeMenu()
    await router.replace('/')
  }
}

onMounted(() => {
  void authStore.initialize().catch(() => undefined)
})
</script>

<template>
  <header class="public-portal-header">
    <div class="public-portal-header__inner">
      <router-link class="public-portal-brand" to="/" aria-label="AI高项培训系统首页" @click="closeMenu">
        <span class="public-portal-brand__mark" aria-hidden="true"><el-icon><DataAnalysis /></el-icon></span>
        <strong>AI高项培训系统</strong>
      </router-link>

      <button
        type="button"
        class="public-mobile-menu"
        data-testid="public-mobile-menu-trigger"
        aria-label="打开公共导航"
        :aria-expanded="menuOpen"
        @click="menuOpen = !menuOpen"
      >
        <el-icon><Menu /></el-icon>
      </button>

      <nav
        class="public-primary-nav"
        :class="{ 'is-open': menuOpen }"
        data-testid="public-primary-nav"
        aria-label="公共导航"
      >
        <router-link to="/news" @click="closeMenu">考试资讯</router-link>
        <router-link to="/resources" @click="closeMenu">学习资源</router-link>
        <router-link to="/practice?mode=DAILY" @click="closeMenu">免费题库</router-link>
        <router-link to="/tools" @click="closeMenu">学习工具</router-link>
        <router-link to="/ai" @click="closeMenu">AI辅助</router-link>
        <router-link to="/exam-simulator" @click="closeMenu">模拟考试</router-link>
      </nav>

      <div v-if="!authStore.isAuthenticated" class="public-account-nav" :class="{ 'is-open': menuOpen }" aria-label="账号入口">
        <router-link data-testid="public-login-link" :to="loginTarget" @click="closeMenu">登录</router-link>
        <router-link data-testid="public-register-link" class="public-account-nav__register" to="/register" @click="closeMenu">注册</router-link>
      </div>
      <div v-else class="public-account-nav public-account-nav--authenticated" :class="{ 'is-open': menuOpen }" aria-label="个人入口" data-testid="public-authenticated-nav">
        <RouterLink :to="workspaceTarget" data-testid="public-workspace-link" @click="closeMenu">
          <el-icon><UserFilled /></el-icon><span>{{ authStore.user?.displayName }}</span>
        </RouterLink>
        <RouterLink
          v-if="canEnterAdmin"
          to="/admin/home"
          data-testid="public-admin-link"
          @click="closeMenu"
        >
          管理后台
        </RouterLink>
        <button type="button" aria-label="退出登录" data-testid="public-logout" @click="logout">
          <el-icon><SwitchButton /></el-icon>
        </button>
      </div>
    </div>
  </header>
</template>

<style scoped>
.public-portal-header {
  position: sticky;
  z-index: 30;
  top: 0;
  height: 56px;
  background: #0d469a;
  box-shadow: 0 1px 0 rgb(255 255 255 / 14%);
  color: #fff;
}

.public-portal-header__inner {
  display: grid;
  width: min(100%, 1488px);
  height: 100%;
  padding: 0 22px;
  margin: 0 auto;
  align-items: center;
  grid-template-columns: 260px minmax(650px, 1fr) auto;
}

.public-portal-brand {
  display: inline-flex;
  width: max-content;
  align-items: center;
  gap: 9px;
  color: #fff;
  font-size: 21px;
  text-decoration: none;
  white-space: nowrap;
}

.public-portal-brand__mark {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 2px solid rgb(255 255 255 / 88%);
  border-radius: 50%;
  color: #fff;
}

.public-portal-brand__mark .el-icon {
  font-size: 21px;
}

.public-primary-nav {
  display: flex;
  height: 100%;
  align-items: center;
  justify-content: center;
  gap: clamp(20px, 2.5vw, 40px);
}

.public-primary-nav a,
.public-account-nav a {
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  text-decoration: none;
  white-space: nowrap;
}

.public-primary-nav a {
  display: inline-flex;
  height: 100%;
  align-items: center;
  border-bottom: 2px solid transparent;
}

.public-primary-nav a:hover,
.public-primary-nav a:focus-visible,
.public-primary-nav a.router-link-active {
  border-bottom-color: #fff;
}

.public-account-nav {
  display: flex;
  align-items: center;
  gap: 10px;
}

.public-account-nav--authenticated a { min-width: 112px; padding: 0 12px; gap: 7px; }
.public-account-nav button { display: inline-grid; width: 36px; height: 36px; padding: 0; place-items: center; border: 1px solid rgb(255 255 255 / 78%); border-radius: 6px; background: transparent; color: #fff; cursor: pointer; font-size: 17px; }

.public-account-nav a {
  display: inline-flex;
  min-width: 76px;
  height: 36px;
  align-items: center;
  justify-content: center;
  border: 1px solid rgb(255 255 255 / 78%);
  border-radius: 6px;
}

.public-account-nav .public-account-nav__register {
  border-color: #fff;
  background: #fff;
  color: #0d55c4;
}

.public-mobile-menu {
  display: none;
}

@media (max-width: 1180px) {
  .public-portal-header__inner {
    grid-template-columns: 230px minmax(590px, 1fr) auto;
  }

  .public-primary-nav {
    gap: 22px;
  }
}

@media (max-width: 900px) {
  .public-portal-header {
    height: 56px;
  }

  .public-portal-header__inner {
    position: relative;
    display: flex;
    padding: 0 14px;
    justify-content: space-between;
  }

  .public-portal-brand {
    font-size: 17px;
  }

  .public-portal-brand__mark {
    width: 30px;
    height: 30px;
  }

  .public-mobile-menu {
    display: inline-grid;
    width: 40px;
    height: 40px;
    padding: 0;
    place-items: center;
    border: 1px solid rgb(255 255 255 / 54%);
    border-radius: 6px;
    background: transparent;
    color: #fff;
    font-size: 22px;
  }

  .public-primary-nav,
  .public-account-nav {
    position: absolute;
    right: 10px;
    left: 10px;
    display: none;
    height: auto;
    border: 1px solid #d7e1ef;
    background: #fff;
    box-shadow: 0 10px 28px rgb(20 46 86 / 18%);
  }

  .public-primary-nav.is-open,
  .public-account-nav.is-open {
    display: grid;
  }

  .public-primary-nav {
    top: 50px;
    padding: 8px;
    gap: 0;
    border-radius: 6px 6px 0 0;
    grid-template-columns: 1fr 1fr;
  }

  .public-primary-nav a {
    height: 42px;
    padding: 0 12px;
    border-bottom: 0;
    color: #173b7a;
  }

  .public-account-nav {
    top: 226px;
    padding: 8px;
    border-top: 0;
    border-radius: 0 0 6px 6px;
    grid-template-columns: 1fr 1fr;
  }

  .public-account-nav a,
  .public-account-nav .public-account-nav__register {
    border-color: #b8cae5;
    color: #175fc7;
  }

  .public-account-nav--authenticated { grid-template-columns: minmax(0, 1fr) 44px; }
  .public-account-nav--authenticated a { min-width: 0; }
  .public-account-nav--authenticated button { border-color: #b8cae5; color: #175fc7; }

  .public-account-nav .public-account-nav__register {
    background: #1769e0;
    color: #fff;
  }
}
</style>
