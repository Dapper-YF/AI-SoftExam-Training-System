import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { PlatformFoundationGateway } from '@/modules/foundation/api/platform-foundation.gateway'
import { setPlatformFoundationGatewayForTests } from '@/modules/foundation/api/gateway'
import { setPortalContentGatewayForTests } from '@/modules/foundation/api/portal-content'
import type { PortalContentGateway, PortalContentItem } from '@/modules/foundation/api/portal-content.gateway'
import type { Qualification, Subject } from '@/modules/foundation/types'
import PublicHomeView from './PublicHomeView.vue'

const qualifications: Qualification[] = [
  { id: 'q-advanced', code: 'HIGH_PM', name: '信息系统项目管理师', levelCode: 'ADVANCED', status: 'ENABLED', sortOrder: 10 },
  { id: 'q-intermediate', code: 'MIDDLE_SD', name: '软件设计师', levelCode: 'INTERMEDIATE', status: 'ENABLED', sortOrder: 10 },
  { id: 'q-primary', code: 'LOW_PROGRAMMER', name: '程序员', levelCode: 'PRIMARY', status: 'ENABLED', sortOrder: 10 },
]

const subjects: Subject[] = [
  { id: 's-advanced', qualificationId: 'q-advanced', code: 'COMPREHENSIVE', name: '综合知识', type: 'COMPREHENSIVE', status: 'ENABLED' },
]

const portalItems: PortalContentItem[] = [{
  id: '1001',
  title: '2026年上半年软考报名通知',
  summary: '官方报名安排摘要',
  publishedAt: '2026-07-01T00:00:00Z',
  category: 'REGISTRATION',
  tag: '报名动态',
  sourceName: '中国计算机技术职业资格网',
  sourceUrl: 'https://www.ruankao.org.cn/notice/1001',
}]

function gateway(overrides: Partial<PlatformFoundationGateway> = {}): PlatformFoundationGateway {
  return {
    listQualifications: vi.fn(async () => structuredClone(qualifications)),
    listSubjects: vi.fn(async () => structuredClone(subjects)),
    listChapterNodes: vi.fn(async () => []),
    listSyllabusNodes: vi.fn(async () => []),
    ...overrides,
  }
}

function portalGateway(overrides: Partial<PortalContentGateway> = {}): PortalContentGateway {
  const resources: PortalContentItem[] = [{
    id: '1002',
    category: 'OFFICIAL_RESOURCE',
    tag: '官方资料',
    title: '计算机技术资格考试大纲',
    summary: '官方考试大纲摘要',
    publishedAt: '2026-07-01T00:00:00Z',
    sourceName: '中国计算机技术职业资格网',
    sourceUrl: 'https://www.ruankao.org.cn/notice/1002',
  }]
  return {
    listNews: vi.fn(async () => structuredClone(portalItems)),
    listResources: vi.fn(async () => structuredClone(resources)),
    ...overrides,
  }
}

async function mountHome(testGateway = gateway(), testPortalGateway = portalGateway()) {
  const pinia = createPinia()
  setActivePinia(pinia)
  setPlatformFoundationGatewayForTests(testGateway)
  setPortalContentGatewayForTests(testPortalGateway)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: PublicHomeView },
      { path: '/practice', component: { template: '<div />' } },
      { path: '/login', component: { template: '<div />' } },
      { path: '/qualifications', component: { template: '<div />' } },
      { path: '/exam-simulator', component: { template: '<div />' } },
    ],
  })
  await router.push('/')
  await router.isReady()
  const wrapper = mount(PublicHomeView, { global: { plugins: [pinia, router, ElementPlus] } })
  await flushPromises()
  return { wrapper, router }
}

describe('PublicHomeView', () => {
  beforeEach(() => {
    localStorage.clear()
    setPortalContentGatewayForTests(portalGateway())
  })

  it('renders the real public qualification API response in the approved three-column portal', async () => {
    const testGateway = gateway()
    const { wrapper } = await mountHome(testGateway)

    expect(testGateway.listQualifications).toHaveBeenCalledOnce()
    expect(testGateway.listSubjects).toHaveBeenCalledWith('q-advanced')
    expect(wrapper.get('[data-testid="public-portal-grid"]')).toBeTruthy()
    expect(wrapper.text()).toContain('信息系统项目管理师')
    expect(wrapper.text()).toContain('软件设计师')
    expect(wrapper.text()).toContain('程序员')
    expect(wrapper.get('[data-testid="public-qualification-source"]').text()).toContain('公开目录')
    expect(wrapper.text()).not.toMatch(/(?:题量|库存|下载量|907\s*人|¥|￥)/)
  })

  it('loads official portal content and opens original announcements safely', async () => {
    const contentGateway = portalGateway()
    const { wrapper } = await mountHome(gateway(), contentGateway)

    expect(contentGateway.listNews).toHaveBeenCalledWith('REGISTRATION')
    expect(contentGateway.listResources).toHaveBeenCalled()
    const news = wrapper.get('[data-testid="portal-news-list"] a')
    expect(news.attributes('href')).toBe('https://www.ruankao.org.cn/notice/1001')
    expect(news.attributes('target')).toBe('_blank')
    expect(news.attributes('rel')).toContain('noopener')
    expect(wrapper.get('[data-testid="portal-resource-list"]')).toBeTruthy()
    expect(wrapper.text()).not.toContain('演示资讯')
    expect(wrapper.text()).not.toContain('演示资料')
  })

  it('keeps portal content failures distinct from empty content', async () => {
    const { wrapper } = await mountHome(gateway(), portalGateway({
      listNews: vi.fn(async () => { throw new Error('network unavailable') }),
      listResources: vi.fn(async () => []),
    }))

    expect(wrapper.text()).toContain('官方资讯暂时无法加载')
    expect(wrapper.text()).toContain('暂无官方资料')
  })

  it('shows a local retryable qualification error instead of disguising an API failure as empty data', async () => {
    const { wrapper } = await mountHome(gateway({
      listQualifications: vi.fn(async () => { throw new Error('network unavailable') }),
    }))

    expect(wrapper.get('[data-testid="public-qualification-error"]').text()).toContain('资格目录加载失败')
    expect(wrapper.get('[data-testid="public-qualification-retry"]')).toBeTruthy()
    expect(wrapper.text()).not.toContain('当前没有题目')
  })

  it('shows a distinct safe qualification empty state without inventing catalog content', async () => {
    const { wrapper } = await mountHome(gateway({
      listQualifications: vi.fn(async () => []),
    }))

    expect(wrapper.text()).toContain('资格目录暂未开放')
    expect(wrapper.find('[data-testid="public-qualification-error"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('当前没有题目')
  })

  it('uses a non-numeric anonymous learning-plan placeholder with a clear login redirect', async () => {
    const { wrapper } = await mountHome()
    const plan = wrapper.get('[data-testid="anonymous-study-plan"]')

    expect(plan.text()).toContain('登录后生成个性化学习计划')
    expect(plan.text()).not.toMatch(/\d+\s*\/\s*\d+|已完成\s*\d+/)
    expect(plan.get('a').attributes('href')).toContain('/login?redirect=')
  })

  it('keeps browsing anonymous and sends only personal actions to login', async () => {
    const { wrapper } = await mountHome()

    expect(wrapper.get('[data-testid="practice-daily-link"]').attributes('href')).toBe('/practice?mode=DAILY')
    expect(wrapper.get('[data-testid="practice-chapter-link"]').attributes('href')).toBe('/practice?mode=CHAPTER')
    expect(wrapper.get('[data-testid="practice-knowledge-link"]').attributes('href')).toBe('/practice?mode=KNOWLEDGE_POINT')
    expect(wrapper.get('[data-testid="practice-paper-link"]').attributes('href')).toBe('/practice?mode=PAST_EXAM')
    expect(wrapper.get('[data-testid="practice-mistake-link"]').attributes('href')).toContain('/login?redirect=')
  })
})
