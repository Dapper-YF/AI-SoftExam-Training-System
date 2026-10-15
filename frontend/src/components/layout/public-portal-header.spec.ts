import ElementPlus from 'element-plus'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'
import PublicPortalHeader from './PublicPortalHeader.vue'
import { useAuthStore } from '@/stores/auth'

async function mountHeader() {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().initialized = true
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/news', component: { template: '<div />' } },
      { path: '/resources', component: { template: '<div />' } },
      { path: '/news', component: { template: '<div />' } },
      { path: '/resources', component: { template: '<div />' } },
      { path: '/practice', component: { template: '<div />' } },
      { path: '/ai', component: { template: '<div />' } },
      { path: '/qualifications', component: { template: '<div />' } },
      { path: '/exam-simulator', component: { template: '<div />' } },
      { path: '/login', component: { template: '<div />' } },
      { path: '/register', component: { template: '<div />' } },
      { path: '/app/practice/reports', component: { template: '<div />' } },
      { path: '/admin/home', component: { template: '<div />' } },
      { path: '/admin/questions', component: { template: '<div />' } },
    ],
  })
  await router.push('/')
  await router.isReady()
  return { wrapper: mount(PublicPortalHeader, { global: { plugins: [pinia, router, ElementPlus] } }), pinia }
}

describe('PublicPortalHeader', () => {
  it('exposes the approved public navigation without a sidebar', async () => {
    const { wrapper } = await mountHeader()
    const links = wrapper.findAll('[data-testid="public-primary-nav"] a')

    expect(links.map((link) => link.text())).toEqual([
      '考试资讯',
      '学习资源',
      '免费题库',
      '学习工具',
      'AI辅助',
      '模拟考试',
    ])
    expect(links.map((link) => link.attributes('href'))).toEqual([
      '/news',
      '/resources',
      '/practice?mode=DAILY',
      '/tools',
      '/ai',
      '/exam-simulator',
    ])
    expect(wrapper.get('[data-testid="public-login-link"]').attributes('href')).toBe('/login')
    expect(wrapper.get('[data-testid="public-register-link"]').attributes('href')).toBe('/register')
    expect(wrapper.find('.platform-sidebar').exists()).toBe(false)
  })

  it('provides an operable mobile menu trigger', async () => {
    const { wrapper } = await mountHeader()
    const trigger = wrapper.get('[data-testid="public-mobile-menu-trigger"]')

    expect(trigger.attributes('aria-expanded')).toBe('false')
    await trigger.trigger('click')
    expect(trigger.attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('[data-testid="public-primary-nav"]').classes()).toContain('is-open')
  })

  it('keeps the same public header and exposes the personal entry after login', async () => {
    const { wrapper, pinia } = await mountHeader()
    const authStore = useAuthStore(pinia)
    authStore.user = { id: 'student-1', username: 'student', displayName: '张同学', status: 'ENABLED' }
    authStore.roles = ['STUDENT']
    authStore.permissions = ['practice:workspace:view']
    await wrapper.vm.$nextTick()

    expect(wrapper.get('[data-testid="public-authenticated-nav"]').text()).toContain('张同学')
    expect(wrapper.get('[data-testid="public-workspace-link"]').attributes('href')).toBe('/app/profile')
    expect(wrapper.find('[data-testid="public-admin-link"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="public-login-link"]').exists()).toBe(false)
    expect(wrapper.get('.public-portal-brand').text()).toContain('AI高项培训系统')
  })

  it('shows the admin entry only for ADMIN accounts with the admin home permission', async () => {
    const { wrapper, pinia } = await mountHeader()
    const authStore = useAuthStore(pinia)
    authStore.user = { id: 'admin-1', username: 'admin', displayName: '管理员', status: 'ENABLED' }
    authStore.roles = ['ADMIN']
    authStore.permissions = ['admin:home:view']
    await wrapper.vm.$nextTick()

    expect(wrapper.get('[data-testid="public-admin-link"]').attributes('href')).toBe('/admin/home')

    authStore.roles = ['QUESTION_REVIEWER']
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="public-admin-link"]').exists()).toBe(false)
  })
})
