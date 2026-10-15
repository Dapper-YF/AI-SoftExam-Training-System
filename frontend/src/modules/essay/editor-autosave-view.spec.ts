import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import EssayEditorSlotView from '@/modules/platform/slots/EssayEditorSlotView.vue'
import { PreviewEssayGateway } from './api/preview-essay.gateway'
import { setEssayGatewayForTests } from './api/gateway'
import type { EssayGateway } from './api/essay.gateway'
import { EssaySaveConflictError } from './autosave-controller'
import { editorPreview, homePreview, reportPreview } from './preview-data'

function options() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  return { global: { plugins: [router], stubs: { ElIcon: { template: '<i><slot /></i>' } } } }
}

async function mountEditor(gateway: EssayGateway): Promise<VueWrapper> {
  setEssayGatewayForTests(gateway)
  const wrapper = mount(EssayEditorSlotView, options())
  await flushPromises()
  await vi.waitFor(() => expect(wrapper.find('[data-testid="essay-editor-grid"]').exists()).toBe(true))
  return wrapper
}

async function editCurrentParagraph(wrapper: VueWrapper, text: string): Promise<void> {
  const paragraph = wrapper.get('[data-paragraph-id="p-3"]')
  paragraph.element.textContent = text
  await paragraph.trigger('input')
  await vi.advanceTimersByTimeAsync(651)
  await flushPromises()
}

describe('editor autosave conflict UI', () => {
  beforeEach(() => {
    vi.stubEnv('VITE_ESSAY_DEV_PREVIEW', 'true')
    sessionStorage.clear()
  })
  afterEach(() => {
    setEssayGatewayForTests(null)
    vi.useRealTimers()
  })

  it('applies a real server snapshot, baseVersion and archive through the server button', async () => {
    const wrapper = await mountEditor(new PreviewEssayGateway())
    vi.useFakeTimers()
    sessionStorage.setItem('essay-preview-save-mode', 'conflict')
    await editCurrentParagraph(wrapper, '冲突前的本地正文')

    expect(wrapper.get('[data-testid="essay-conflict-dialog"]').text()).toContain('服务器版本 v14')
    await wrapper.get('[data-testid="use-server-snapshot"]').trigger('click')
    expect(wrapper.get('[data-paragraph-id="p-3"]').text()).toContain('服务器版本：调研样本为72份')
    expect(wrapper.get('[data-testid="essay-save-state"]').text()).toContain('自动保存成功')
    expect(wrapper.text()).toContain('版本：v14')
    expect(sessionStorage.getItem('essay-local-draft-preview-1')).toBeNull()
    expect(sessionStorage.getItem('essay-local-draft-preview-1:conflict:1')).toContain('冲突前的本地正文')
  })

  it('keeps the local snapshot and retries it against the server baseVersion in preview', async () => {
    const wrapper = await mountEditor(new PreviewEssayGateway())
    vi.useFakeTimers()
    sessionStorage.setItem('essay-preview-save-mode', 'conflict')
    await editCurrentParagraph(wrapper, '必须保留的本地正文')

    await wrapper.get('[data-testid="keep-local-snapshot"]').trigger('click')
    expect(wrapper.get('[data-testid="essay-conflict-dialog"]').text()).toContain('本地副本仍受保护，冲突尚未解决')
    expect(wrapper.get('[data-paragraph-id="p-3"]').text()).toContain('必须保留的本地正文')
    expect(sessionStorage.getItem('essay-local-draft-preview-1')).toContain('必须保留的本地正文')
    await wrapper.get('[data-testid="retry-local-snapshot"]').trigger('click')
    await vi.advanceTimersByTimeAsync(181)
    await flushPromises()
    expect(wrapper.get('[data-testid="essay-save-state"]').text()).toContain('自动保存成功')
    expect(wrapper.text()).toContain('版本：v15')
    expect(sessionStorage.getItem('essay-local-draft-preview-1')).toBeNull()
  })

  it('disables server resolution and requires reload when 409 has no snapshot', async () => {
    const gateway: EssayGateway = {
      capabilities: { conflictRetry: false, reviewQuestions: false },
      loadHome: async () => structuredClone(homePreview),
      loadEditor: async () => structuredClone(editorPreview),
      loadReport: async () => structuredClone(reportPreview),
      saveDraft: async () => { throw new EssaySaveConflictError('版本冲突') },
      askReviewQuestion: async () => { throw new Error('未联调') },
    }
    const wrapper = await mountEditor(gateway)
    vi.useFakeTimers()
    await editCurrentParagraph(wrapper, '无服务器快照的本地正文')
    expect(wrapper.get('[data-testid="use-server-snapshot"]').attributes()).toHaveProperty('disabled')
    expect(wrapper.get('[data-testid="essay-conflict-dialog"]').text()).toContain('需重新加载服务器版本')
    expect(sessionStorage.getItem('essay-local-draft-preview-1')).toContain('无服务器快照的本地正文')
  })

  it('flushes protected content on paragraph switch and exposes an executable retry after failure', async () => {
    let attempts = 0
    const gateway: EssayGateway = {
      capabilities: { conflictRetry: false, reviewQuestions: false },
      loadHome: async () => structuredClone(homePreview),
      loadEditor: async () => structuredClone(editorPreview),
      loadReport: async () => structuredClone(reportPreview),
      saveDraft: async (_draft, baseVersion, localRevision) => {
        attempts += 1
        if (attempts === 1) throw new Error('temporary failure')
        return { confirmedRevision: localRevision, baseVersion: baseVersion + 1, updatedAt: '2026-07-13T10:00:00Z' }
      },
      askReviewQuestion: async () => { throw new Error('未联调') },
    }
    const wrapper = await mountEditor(gateway)
    const paragraph = wrapper.get('[data-paragraph-id="p-3"]')
    paragraph.element.textContent = '切换段落前必须保护的正文'
    await paragraph.trigger('input')
    await wrapper.get('[data-paragraph-id="p-2"]').trigger('focus')
    await vi.waitFor(() => expect(wrapper.get('[data-testid="essay-save-state"]').text()).toContain('保存失败'))
    expect(sessionStorage.getItem('essay-local-draft-preview-1')).toContain('切换段落前必须保护的正文')
    await wrapper.get('[data-testid="retry-autosave"]').trigger('click')
    await vi.waitFor(() => expect(wrapper.get('[data-testid="essay-save-state"]').text()).toContain('自动保存成功'))
    expect(attempts).toBe(2)
    expect(sessionStorage.getItem('essay-local-draft-preview-1')).toBeNull()
  })
})
