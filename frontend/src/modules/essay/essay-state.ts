export type EssayTaskState = 'IDLE' | 'VALIDATING' | 'SUBMITTING' | 'QUEUED' | 'RUNNING' | 'AI_SUCCEEDED' | 'MATERIALIZING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'

export interface EssayParagraph {
  id: string
  outlineId: string
  text: string
}

export interface EssayDraft {
  id: string
  title: string
  version: number
  paragraphs: EssayParagraph[]
}

export function isEssayPreviewEnabled(dev: boolean, value: string | undefined): boolean {
  return dev && value !== 'false'
}

export function errorPresentation(status?: number): { message: string; retryable: boolean } {
  const states: Record<number, { message: string; retryable: boolean }> = {
    401: { message: '登录状态已失效', retryable: false },
    403: { message: '无权访问当前论文', retryable: false },
    404: { message: '论文资源不存在', retryable: false },
    409: { message: '检测到版本冲突', retryable: true },
    429: { message: '请求过于频繁', retryable: true },
    500: { message: '论文服务暂时异常', retryable: true },
  }
  return states[status ?? 0] ?? { message: '论文数据加载失败', retryable: true }
}

export function taskPresentation(state: EssayTaskState, retryable = false): {
  label: string
  detail: string
  poll: boolean
  canRetry: boolean
} {
  const labels: Record<EssayTaskState, string> = {
    IDLE: '尚未开始', VALIDATING: '正在校验配置', SUBMITTING: '正在创建任务', QUEUED: '已进入队列',
    RUNNING: 'AI 正在处理', AI_SUCCEEDED: 'AI 结果已生成，正在整理', MATERIALIZING: '正在保存结果',
    SUCCEEDED: '已完成', FAILED: '处理失败', CANCELLED: '已停止等待',
  }
  const poll = ['QUEUED', 'RUNNING', 'AI_SUCCEEDED', 'MATERIALIZING'].includes(state)
  return {
    label: labels[state],
    detail: state === 'CANCELLED' ? '仅停止此页面的状态轮询，未向后端声明任务已取消。' : labels[state],
    poll,
    canRetry: state === 'FAILED' && retryable,
  }
}

export function applyParagraphSuggestion(
  draft: EssayDraft,
  paragraphId: string,
  suggestion: string,
  mode: 'replace' | 'insert-after',
): EssayDraft {
  const index = draft.paragraphs.findIndex((item) => item.id === paragraphId)
  if (index < 0) throw new Error('未找到绑定段落')
  const paragraphs = draft.paragraphs.map((item) => ({ ...item }))
  if (mode === 'replace') paragraphs[index] = { ...paragraphs[index]!, text: suggestion }
  else {
    paragraphs.splice(index + 1, 0, {
      id: `${paragraphId}-insert-${draft.version + 1}`,
      outlineId: paragraphs[index]!.outlineId,
      text: suggestion,
    })
  }
  return { ...draft, version: draft.version + 1, paragraphs }
}
