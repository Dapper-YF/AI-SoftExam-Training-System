import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { shallowMount, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'
import { useAuthStore } from '@/stores/auth'
import AnswerLayout from './AnswerLayout.vue'
import WorkspaceLayout from './WorkspaceLayout.vue'

async function mountLearningShell(path = '/app/practice/history') {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().initialized = true
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/practice', component: { template: '<div />' } },
      { path: '/tools', component: { template: '<div />' } },
      { path: '/qualifications', component: { template: '<div />' } },
      { path: '/exam-simulator', component: { template: '<div />' } },
      { path: '/login', component: { template: '<div />' } },
      { path: '/register', component: { template: '<div />' } },
      { path: '/app/practice/history', component: { template: '<div data-testid="history-page" />' }, meta: { pageTitle: '练习历史' } },
      { path: '/app/essay/home', component: { template: '<div />' } },
      { path: '/app/profile', component: { template: '<div />' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  return mount(WorkspaceLayout, { global: { plugins: [pinia, router, ElementPlus] } })
}

describe('unified learning shells', () => {
  it('renders the personal navigation and breadcrumb under the P1 brand header', async () => {
    const wrapper = await mountLearningShell()

    expect(wrapper.get('.public-portal-brand').text()).toContain('AI高项培训系统')
    expect(wrapper.get('[data-testid="personal-learning-nav"]').text()).toContain('学习工具')
    expect(wrapper.get('.learning-breadcrumb').text()).toContain('学习中心/练习历史')
  })

  it('opens the personal mobile navigation without hiding its destinations', async () => {
    const wrapper = await mountLearningShell()
    const trigger = wrapper.get('[data-testid="personal-mobile-menu-trigger"]')

    expect(trigger.attributes('aria-expanded')).toBe('false')
    await trigger.trigger('click')
    expect(trigger.attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('[data-testid="personal-learning-nav"]').classes()).toContain('is-open')
  })

  it('keeps answer sessions in a dedicated shell with a direct catalog exit', () => {
    const wrapper = shallowMount(AnswerLayout, {
      global: {
        stubs: {
          RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
          PracticeSessionView: { template: '<div data-testid="practice-session-stub" />' },
          ElIcon: { template: '<i><slot /></i>' },
        },
      },
    })

    expect(wrapper.get('[data-testid="answer-layout"]').attributes('data-testid')).toBe('answer-layout')
    expect(wrapper.get('[data-testid="answer-back-catalog"]').attributes('href')).toBe('/practice')
    expect(wrapper.find('[data-testid="personal-learning-nav"]').exists()).toBe(false)
  })
})
