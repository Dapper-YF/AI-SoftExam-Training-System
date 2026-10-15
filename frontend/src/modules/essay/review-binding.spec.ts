import { describe, expect, it, vi } from 'vitest'
import { platformApiClient } from '@/api/client'
import { HttpEssayGateway } from './api/http-essay.gateway'
import { reportPreview } from './preview-data'
import { createReviewQuestionRequest } from './review-binding'

describe('review question identity binding', () => {
  it('keeps reviewTaskId and resultId distinct', () => {
    expect(reportPreview.reviewTaskId).toBe('review-task-preview-1')
    expect(reportPreview.resultId).toBe('result-preview-1')
    expect(reportPreview.reviewTaskId).not.toBe(reportPreview.resultId)
  })

  it('builds a strongly bound request for the selected issue', () => {
    expect(createReviewQuestionRequest(reportPreview, 'issue-2', '为什么需要量化？')).toEqual({
      reviewTaskId: 'review-task-preview-1',
      resultId: 'result-preview-1',
      issueId: 'issue-2',
      question: '为什么需要量化？',
    })
  })

  it('rejects an issue outside the report and an empty question', () => {
    expect(() => createReviewQuestionRequest(reportPreview, 'missing', '解释一下')).toThrow('批改问题不属于当前报告')
    expect(() => createReviewQuestionRequest(reportPreview, 'issue-1', '  ')).toThrow('追问内容不能为空')
  })

  it('keeps the default HTTP boundary unsent while the review question contract is unavailable', async () => {
    const post = vi.spyOn(platformApiClient, 'post')
    const request = createReviewQuestionRequest(reportPreview, 'issue-1', '请解释扣分原因')
    await expect(new HttpEssayGateway().askReviewQuestion(request)).rejects.toMatchObject({
      status: 501,
      code: 'ESSAY_REVIEW_QUESTION_NOT_CONNECTED',
    })
    expect(post).not.toHaveBeenCalled()
  })
})
