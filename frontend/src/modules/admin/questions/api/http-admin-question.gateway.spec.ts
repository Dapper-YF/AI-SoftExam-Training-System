import { afterEach, describe, expect, it, vi } from 'vitest'
import { platformApiClient } from '@/api/client'
import { HttpAdminQuestionGateway } from './http-admin-question.gateway'
import { buildQuestionPayload, createBlankQuestionForm } from '../model/question-form'

function envelope<T>(data: T) {
  return {
    data: {
      success: true,
      errorCode: 'OK',
      message: 'success',
      data,
      traceId: 'trace-question-admin',
    },
  }
}

function actionResult(overrides: Record<string, unknown> = {}) {
  return {
    questionId: 101,
    questionVersionId: 501,
    versionNo: 2,
    status: 'DRAFT',
    rowVersion: 7,
    replayed: false,
    changedAt: '2026-07-17T10:00:00Z',
    ...overrides,
  }
}

function validPayload() {
  const form = createBlankQuestionForm('SINGLE_CHOICE')
  form.qualificationId = 1
  form.subjectId = 11
  form.title = '范围管理单选题'
  form.stem = '项目范围说明书的主要作用是什么？'
  form.options[0]!.content = '定义项目边界'
  form.options[1]!.content = '替代验收标准'
  form.analysis = '范围说明书用于明确项目边界。'
  return buildQuestionPayload(form)
}

describe('HttpAdminQuestionGateway V11 contract', () => {
  afterEach(() => { vi.restoreAllMocks() })

  it('normalizes dashboard qualificationId and questionType dimensions to keys', async () => {
    vi.spyOn(platformApiClient, 'get').mockResolvedValue(envelope({
      totalCount: 9,
      draftCount: 2,
      inReviewCount: 1,
      publishedCount: 5,
      retiredCount: 1,
      byQualification: [{ qualificationId: 101, label: '信息系统项目管理师', count: 9 }],
      byQuestionType: [{ questionType: 'CASE', count: 3 }],
      recentImports: [],
      pendingReviews: [],
    }) as never)

    await expect(new HttpAdminQuestionGateway().getDashboard()).resolves.toMatchObject({
      totalCount: 9,
      byQualification: [{ key: '101', label: '信息系统项目管理师', count: 9 }],
      byQuestionType: [{ key: 'CASE', count: 3 }],
    })
  })

  it('maps questionVersionId and rowVersion and sends every supported list filter', async () => {
    const get = vi.spyOn(platformApiClient, 'get').mockResolvedValue(envelope({
      items: [{
        questionId: 101,
        questionVersionId: 501,
        versionNo: 2,
        qualificationId: 1,
        subjectId: 11,
        questionType: 'SINGLE_CHOICE',
        title: '范围管理单选题',
        difficulty: 'MEDIUM',
        status: 'IN_REVIEW',
        rowVersion: 7,
        submittedBy: 9,
        reviewDecision: 'APPROVED',
        sourceType: 'MANUAL',
        copyrightStatus: 'UNVERIFIED',
        usageRestriction: 'INTERNAL_ONLY',
        currentWorking: true,
      }],
      pageNo: 2,
      pageSize: 20,
      total: 41,
    }) as never)

    await expect(new HttpAdminQuestionGateway().listQuestions({
      pageNo: 2,
      pageSize: 20,
      questionId: 101,
      keyword: '范围',
      qualificationId: 1,
      subjectId: 11,
      questionType: 'SINGLE_CHOICE',
      sourceType: 'MANUAL',
      status: 'IN_REVIEW',
      copyrightStatus: 'UNVERIFIED',
    })).resolves.toMatchObject({
      total: 41,
      items: [{ questionVersionId: 501, rowVersion: 7, reviewDecision: 'APPROVED' }],
    })

    expect(get).toHaveBeenCalledWith('/admin/questions', { params: {
      pageNo: 2,
      pageSize: 20,
      questionId: 101,
      keyword: '范围',
      qualificationId: 1,
      subjectId: 11,
      questionType: 'SINGLE_CHOICE',
      sourceType: 'MANUAL',
      status: 'IN_REVIEW',
      copyrightStatus: 'UNVERIFIED',
    } })
  })

  it('wraps create and createRevision payloads with clientMutationId and baseVersionId', async () => {
    const post = vi.spyOn(platformApiClient, 'post').mockResolvedValue(envelope(actionResult()) as never)
    const put = vi.spyOn(platformApiClient, 'put').mockResolvedValue(envelope(actionResult({ questionVersionId: 502, versionNo: 3 })) as never)
    const payload = validPayload()
    const createCommand = {
      clientMutationId: '10000000-0000-4000-8000-000000000001',
      baseVersionId: null,
      payload,
      copyrightStatus: 'ORIGINAL_DECLARED' as const,
      usageRestriction: 'INTERNAL_ONLY',
    }
    const gateway = new HttpAdminQuestionGateway()

    await gateway.createQuestion(createCommand)
    await gateway.createRevision(101, { ...createCommand, baseVersionId: 501 })

    expect(post).toHaveBeenCalledWith('/admin/questions', createCommand)
    expect(put).toHaveBeenCalledWith('/admin/questions/101/versions', { ...createCommand, baseVersionId: 501 })
  })

  it('uses expectedRowVersion and the backend APPROVED/REJECTED decisions', async () => {
    const post = vi.spyOn(platformApiClient, 'post').mockResolvedValue(envelope(actionResult({ status: 'IN_REVIEW' })) as never)
    const gateway = new HttpAdminQuestionGateway()
    const command = {
      questionId: 101,
      questionVersionId: 501,
      expectedRowVersion: 7,
      clientMutationId: '10000000-0000-4000-8000-000000000002',
    }

    await gateway.submitReview(command)
    await gateway.approveReview(command)
    await gateway.rejectReview({ ...command, reason: '来源说明不完整' })
    await gateway.publishQuestion(command)
    await gateway.retireQuestion(command)

    expect(post).toHaveBeenNthCalledWith(1, '/admin/questions/101/versions/501/submit-review', {
      clientMutationId: command.clientMutationId,
      expectedRowVersion: 7,
      reason: undefined,
    })
    expect(post).toHaveBeenNthCalledWith(2, '/admin/questions/101/versions/501/review', {
      clientMutationId: command.clientMutationId,
      expectedRowVersion: 7,
      decision: 'APPROVED',
      reason: undefined,
    })
    expect(post).toHaveBeenNthCalledWith(3, '/admin/questions/101/versions/501/review', {
      clientMutationId: command.clientMutationId,
      expectedRowVersion: 7,
      decision: 'REJECTED',
      reason: '来源说明不完整',
    })
  })

  it('uses upload token for confirm and normalizes eligible/ineligible counts', async () => {
    const post = vi.spyOn(platformApiClient, 'post')
      .mockResolvedValueOnce(envelope({
        token: '20000000-0000-4000-8000-000000000001',
        schemaVersion: 'question-import/1.0',
        status: 'READY_TO_CONFIRM',
        batchId: null,
        totalCount: 2,
        eligibleCount: 1,
        ineligibleCount: 1,
        importedCount: 0,
        replayed: false,
        items: [{ lineNo: 2, externalKey: 'Q-2', eligible: false, errorCode: 'QUESTION_IMPORT_OPTIONS_INVALID' }],
      }) as never)
      .mockResolvedValueOnce(envelope({
        token: '20000000-0000-4000-8000-000000000001',
        schemaVersion: 'question-import/1.0',
        status: 'COMPLETED',
        batchId: 88,
        totalCount: 1,
        eligibleCount: 1,
        ineligibleCount: 0,
        importedCount: 1,
        replayed: true,
        items: [],
      }) as never)
    const gateway = new HttpAdminQuestionGateway()
    const file = new File([JSON.stringify({ schemaVersion: 'question-import/1.0', questions: [] })], 'questions.json', { type: 'application/json' })

    const preflight = await gateway.preflightImport({
      schemaVersion: 'question-import/1.0',
      clientMutationId: '20000000-0000-4000-8000-000000000002',
      file,
    })
    const confirmed = await gateway.confirmImport({
      token: preflight.token,
      clientMutationId: '20000000-0000-4000-8000-000000000003',
      baseVersion: null,
    })

    expect(preflight).toMatchObject({ eligibleCount: 1, ineligibleCount: 1 })
    expect(confirmed).toMatchObject({ batchId: 88, importedCount: 1, replayed: true })
    expect(post.mock.calls[0]?.[1]).toBeInstanceOf(FormData)
    expect(post).toHaveBeenNthCalledWith(2, '/admin/question-imports/20000000-0000-4000-8000-000000000001/confirm', {
      clientMutationId: '20000000-0000-4000-8000-000000000003',
      baseVersion: null,
    })
  })
})
