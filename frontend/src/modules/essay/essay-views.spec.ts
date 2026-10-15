import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import EssayWorkspaceHomeView from '@/modules/platform/views/EssayWorkspaceHomeView.vue'
import EssayEditorSlotView from '@/modules/platform/slots/EssayEditorSlotView.vue'
import EssayReportsSlotView from '@/modules/platform/slots/EssayReportsSlotView.vue'

function mountOptions() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  return {
  global: {
    plugins: [router],
    stubs: {
      RouterLink: { template: '<a><slot /></a>' },
      ElIcon: { template: '<i><slot /></i>' },
      ElTooltip: { template: '<span><slot /></span>' },
      ElProgress: { template: '<span class="progress-stub" />' },
    },
  },
  }
}

describe('essay target pages', () => {
  beforeEach(() => {
    vi.stubEnv('VITE_ESSAY_DEV_PREVIEW', 'true')
    sessionStorage.clear()
  })

  it('renders the six-step home workflow and development boundary', async () => {
    const wrapper = mount(EssayWorkspaceHomeView, mountOptions())
    await flushPromises()
    await vi.waitFor(() => expect(wrapper.findAll('[data-testid="essay-workflow-step"]')).toHaveLength(6))
    expect(wrapper.get('[data-testid="essay-home"]').text()).toContain('开发环境，当前 AI 训练与批改不产生费用')
    expect(wrapper.findAll('[data-testid="essay-workflow-step"]')).toHaveLength(6)
    expect(wrapper.get('[data-testid="continue-draft"]').text()).toContain('继续写作')
  })

  it('renders a stable three-column editor with bound suggestions', async () => {
    const wrapper = mount(EssayEditorSlotView, mountOptions())
    await flushPromises()
    await vi.waitFor(() => expect(wrapper.find('[data-testid="essay-editor-grid"]').exists()).toBe(true))
    expect(wrapper.get('[data-testid="essay-editor-grid"]').classes()).toContain('essay-editor__grid')
    expect(wrapper.get('[data-testid="essay-save-state"]').text()).toContain('自动保存')
    expect(wrapper.get('[data-testid="essay-ai-context"]').text()).toContain('当前绑定')
    await wrapper.get('[data-testid="submit-review"]').trigger('click')
    expect(wrapper.get('[data-testid="review-submit-boundary"]').text()).toContain('批改接口尚未联调')
  })

  it('links report issues and source markers in both directions', async () => {
    const wrapper = mount(EssayReportsSlotView, mountOptions())
    await flushPromises()
    await vi.waitFor(() => expect(wrapper.find('[data-testid="report-issue-2"]').exists()).toBe(true))
    await wrapper.get('[data-testid="report-issue-2"]').trigger('click')
    expect(wrapper.get('[data-testid="report-issue-2"]').attributes('aria-current')).toBe('true')
    expect(wrapper.get('[data-testid="report-source-2"]').classes()).toContain('is-active')
    expect(wrapper.get('[data-testid="issue-question-context"]').text()).toContain('issue-2')
    await wrapper.get('[data-testid="issue-question-input"]').setValue('为什么需要量化？')
    await wrapper.get('[data-testid="issue-question-form"]').trigger('submit')
    expect(wrapper.get('[data-testid="issue-question-context"]').text()).toContain('reviewTaskId=review-task-preview-1')
    expect(wrapper.get('[data-testid="issue-question-context"]').text()).toContain('resultId=result-preview-1')
  })
})
