import { describe, expect, it } from 'vitest'
import {
  applyParagraphSuggestion,
  errorPresentation,
  isEssayPreviewEnabled,
  taskPresentation,
  type EssayDraft,
} from './essay-state'

const draft: EssayDraft = {
  id: 'draft-1',
  title: '项目管理能力提升研究与实践',
  version: 12,
  paragraphs: [
    { id: 'p-1', outlineId: 'outline-1', text: '原始第一段。' },
    { id: 'p-2', outlineId: 'outline-2', text: '原始第二段。' },
  ],
}

describe('essay development and API boundaries', () => {
  it('opens preview by default in development and allows an explicit false override', () => {
    expect(isEssayPreviewEnabled(true, undefined)).toBe(true)
    expect(isEssayPreviewEnabled(true, 'true')).toBe(true)
    expect(isEssayPreviewEnabled(true, 'false')).toBe(false)
    expect(isEssayPreviewEnabled(true, '')).toBe(true)
    expect(isEssayPreviewEnabled(false, 'true')).toBe(false)
    expect(isEssayPreviewEnabled(false, undefined)).toBe(false)
  })

  it.each([
    [401, '登录状态已失效', false],
    [403, '无权访问当前论文', false],
    [404, '论文资源不存在', false],
    [409, '检测到版本冲突', true],
    [429, '请求过于频繁', true],
    [500, '论文服务暂时异常', true],
  ])('maps HTTP %i into a safe user state', (status, message, retryable) => {
    expect(errorPresentation(status)).toMatchObject({ message, retryable })
  })
})

describe('essay task state', () => {
  it('stops local polling for CANCELLED without claiming backend cancellation', () => {
    expect(taskPresentation('CANCELLED')).toEqual({
      label: '已停止等待',
      poll: false,
      canRetry: false,
      detail: '仅停止此页面的状态轮询，未向后端声明任务已取消。',
    })
  })

  it('only exposes retry for retryable failures', () => {
    expect(taskPresentation('FAILED', true).canRetry).toBe(true)
    expect(taskPresentation('FAILED', false).canRetry).toBe(false)
  })
})

describe('bound AI suggestion application', () => {
  it('creates a new revision when replacing the bound paragraph', () => {
    const next = applyParagraphSuggestion(draft, 'p-2', '替换后的第二段。', 'replace')
    expect(next.version).toBe(13)
    expect(next.paragraphs.map((item) => item.text)).toEqual(['原始第一段。', '替换后的第二段。'])
    expect(draft.version).toBe(12)
  })

  it('inserts after the bound paragraph without changing another paragraph', () => {
    const next = applyParagraphSuggestion(draft, 'p-1', '补充论证段。', 'insert-after')
    expect(next.paragraphs.map((item) => item.text)).toEqual([
      '原始第一段。',
      '补充论证段。',
      '原始第二段。',
    ])
    expect(next.paragraphs[1]?.id).toContain('p-1-insert-')
  })

  it('rejects suggestions without a stable paragraph anchor', () => {
    expect(() => applyParagraphSuggestion(draft, 'missing', '越界内容', 'replace'))
      .toThrow('未找到绑定段落')
  })
})
