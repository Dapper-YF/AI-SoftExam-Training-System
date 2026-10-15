import { describe, expect, it } from 'vitest'
import { ApiClientError } from '@/api/client'
import { resolveAdminQuestionError } from './error-messages'

describe('admin question safe Chinese errors', () => {
  it.each([
    [401, '登录状态已失效'],
    [403, '无权'],
    [409, '状态已变化'],
    [422, '校验失败'],
    [500, '暂时不可用'],
  ])('maps HTTP %s without exposing server details', (status, expected) => {
    const error = new ApiClientError('SQL stack and repository path', status, 'UNMAPPED_INTERNAL')
    const message = resolveAdminQuestionError(error)
    expect(message).toContain(expected)
    expect(message).not.toContain('SQL')
    expect(message).not.toContain('repository')
  })

  it('maps question validation codes to actionable text', () => {
    expect(resolveAdminQuestionError(new ApiClientError('internal', 400, 'QUESTION_IMPORT_SCORE_CLOSURE_INVALID')))
      .toContain('评分点合计')
  })
})
