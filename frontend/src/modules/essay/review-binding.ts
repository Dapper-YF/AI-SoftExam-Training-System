import type { EssayReportData, EssayReviewQuestionRequest } from './types'

export function createReviewQuestionRequest(
  report: EssayReportData,
  issueId: string,
  question: string,
): EssayReviewQuestionRequest {
  if (!report.reviewTaskId.trim() || !report.resultId.trim()) throw new Error('批改任务或结果身份缺失')
  if (!report.issues.some((item) => item.id === issueId)) throw new Error('批改问题不属于当前报告')
  const normalized = question.trim()
  if (!normalized) throw new Error('追问内容不能为空')
  return {
    reviewTaskId: report.reviewTaskId,
    resultId: report.resultId,
    issueId,
    question: normalized,
  }
}
