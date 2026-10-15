import type { Page, Route } from '@playwright/test'
import { expect, test } from './fixtures'

const adminAccount = {
  identifier: process.env.VITE_MOCK_ADMIN_IDENTIFIER ?? 'admin@example.com',
  password: process.env.VITE_MOCK_ADMIN_PASSWORD ?? 'e2e-admin-password',
}

const adminQuestion = {
  questionId: 201,
  versionId: 301,
  qualificationId: 1,
  subjectId: 11,
  questionType: 'SINGLE_CHOICE',
  title: '范围管理单选题',
  yearNo: 2026,
  difficulty: 'MEDIUM',
  status: 'IN_REVIEW',
  contentVersion: 2,
  baseVersion: 12,
  versionNo: 2,
  submittedBy: 'mock-admin-1',
  submittedByName: '平台管理员',
  latestReviewDecision: 'PENDING',
  sourceType: 'ORIGINAL',
}

const draftQuestion = {
  ...adminQuestion,
  questionId: 202,
  versionId: 302,
  title: '待编辑草稿题',
  status: 'DRAFT',
  baseVersion: 3,
  submittedBy: 'author-2',
}

const publishableQuestion = {
  ...adminQuestion,
  questionId: 203,
  versionId: 303,
  title: '已审核待发布题',
  latestReviewDecision: 'APPROVED',
  submittedBy: 'author-3',
}

function envelope(data: unknown, message = 'OK') {
  return { success: true, code: 'OK', message, traceId: 'e2e-admin-question', data }
}

function errorEnvelope(message: string) {
  return { success: false, code: 'ADMIN_QUESTION_UNAVAILABLE', message, traceId: 'e2e-admin-question-error' }
}

function questionPage(items: unknown[]) {
  return { items, pageNo: 1, pageSize: 20, total: items.length }
}

async function fulfillJson(route: Route, data: unknown, status = 200): Promise<void> {
  await route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(data),
  })
}

async function loginAsAdmin(page: Page): Promise<void> {
  await page.goto('/login')
  await page.getByTestId('platform-identifier').fill(adminAccount.identifier)
  await page.getByTestId('platform-password').fill(adminAccount.password)
  await page.getByTestId('platform-login-submit').click()
  await expect(page).toHaveURL(/\/admin\/home$/)
}

async function openQuestionAdmin(page: Page): Promise<void> {
  await page.getByRole('menuitem', { name: '题库管理' }).click()
  await expect(page).toHaveURL(/\/admin\/questions$/)
  await expect(page.getByTestId('admin-question-page')).toBeVisible()
}

test('admin questions uses production HTTP boundaries for empty and error states', async ({ page }) => {
  let mode: 'empty' | 'error' = 'empty'
  await page.route('**/api/v1/admin/questions**', async (route) => {
    if (route.request().method() === 'GET') {
      await fulfillJson(route, mode === 'empty'
        ? envelope(questionPage([]))
        : errorEnvelope('题库聚合接口暂不可用'))
      return
    }
    await fulfillJson(route, errorEnvelope('当前端点未开放'), 501)
  })

  await loginAsAdmin(page)
  await openQuestionAdmin(page)

  await expect(page.getByText('HTTP 真实接口')).toBeVisible()
  await expect(page.getByText('DEV Mock 预览')).toHaveCount(0)
  await expect(page.getByTestId('admin-question-empty')).toContainText('暂无题目')

  mode = 'error'
  await page.getByRole('button', { name: /筛选/ }).click()
  await expect(page.getByTestId('admin-question-error')).toContainText('题库聚合接口暂不可用')
})

test('admin question workflow covers filters, mutations, self-review and import result', async ({ page }) => {
  const requestedUrls: string[] = []
  await page.route('**/api/v1/admin/questions**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    requestedUrls.push(`${request.method()} ${url.pathname}${url.search}`)

    if (request.method() === 'GET' && url.pathname.endsWith('/admin/questions')) {
      await fulfillJson(route, envelope(questionPage([adminQuestion, draftQuestion, publishableQuestion])))
      return
    }
    if (request.method() === 'POST' && url.pathname.endsWith('/admin/questions')) {
      const body = request.postDataJSON()
      expect(body).toMatchObject({ questionType: 'CASE', title: '案例题新草稿', baseVersion: 0 })
      expect(body.clientMutationId).toEqual(expect.any(String))
      await fulfillJson(route, envelope({ ...draftQuestion, questionId: 204, title: body.title, questionType: body.questionType }), 201)
      return
    }
    if (request.method() === 'PUT' && url.pathname.endsWith('/admin/questions/202/versions/302')) {
      const body = request.postDataJSON()
      expect(body).toMatchObject({ baseVersion: 3, payload: { title: '编辑后的草稿题' } })
      expect(body.clientMutationId).toEqual(expect.any(String))
      await fulfillJson(route, envelope({ ...draftQuestion, title: body.payload.title }))
      return
    }
    if (request.method() === 'POST' && url.pathname.endsWith('/admin/questions/203/versions/303/publish')) {
      const body = request.postDataJSON()
      expect(body).toMatchObject({ baseVersion: 12 })
      expect(body.clientMutationId).toEqual(expect.any(String))
      await fulfillJson(route, envelope({ ...publishableQuestion, status: 'PUBLISHED' }))
      return
    }
    await fulfillJson(route, envelope(adminQuestion))
  })

  await page.route('**/api/v1/admin/question-imports/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    requestedUrls.push(`${request.method()} ${url.pathname}`)

    if (request.method() === 'POST' && url.pathname.endsWith('/admin/question-imports/preflight')) {
      const headers = request.headers()
      expect(headers['content-type']).toContain('multipart/form-data')
      await fulfillJson(route, envelope({
        batchId: 'e2e-import-batch',
        status: 'READY_TO_CONFIRM',
        totalCount: 2,
        validCount: 1,
        warningCount: 1,
        errorCount: 0,
        items: [
          { itemNo: 1, status: 'VALID', sourceLocator: 'JSON#1', title: '导入单选题', questionType: 'SINGLE_CHOICE', messages: [] },
          { itemNo: 2, status: 'WARNING', sourceLocator: 'JSON#2', title: '导入案例题', questionType: 'CASE', messages: ['重复内容预警'] },
        ],
      }))
      return
    }
    await fulfillJson(route, envelope(adminQuestion))
  })

  await loginAsAdmin(page)
  await openQuestionAdmin(page)

  for (const label of ['单选题', '多选题', '判断题', '案例题', '论文题']) {
    await expect(page.getByText(label, { exact: true }).first()).toBeVisible()
  }

  await page.getByText('多选题', { exact: true }).click()
  await expect.poll(() => requestedUrls.some((url) => url.includes('questionType=MULTI_CHOICE'))).toBe(true)

  const row = page.getByTestId('admin-question-row-201')
  await expect(row).toContainText('范围管理单选题')
  await expect(row.getByTestId('self-review-boundary')).toContainText('禁止自审')
  await expect(row.getByTestId('approve-question-201')).toBeDisabled()
  await expect(row.getByTestId('publish-question-201')).toBeDisabled()

  await page.getByTestId('create-question').click()
  await page.getByTestId('question-form-type').selectOption('CASE')
  await page.getByTestId('question-form-title').fill('案例题新草稿')
  await page.getByTestId('question-form-stem').fill('案例材料正文')
  await page.getByTestId('question-form-sub-question').fill('案例子题正文')
  await page.getByTestId('submit-question-form').click()
  await expect.poll(() => requestedUrls.some((url) => url === 'POST /api/v1/admin/questions')).toBe(true)

  const draftRow = page.getByTestId('admin-question-row-202')
  await draftRow.getByRole('button', { name: '编辑' }).click()
  await page.getByTestId('question-form-title').fill('编辑后的草稿题')
  await page.getByTestId('submit-question-form').click()
  await expect.poll(() => requestedUrls.some((url) => url === 'PUT /api/v1/admin/questions/202/versions/302')).toBe(true)

  await page.getByTestId('publish-question-203').click()
  await expect.poll(() => requestedUrls.some((url) => url === 'POST /api/v1/admin/questions/203/versions/303/publish')).toBe(true)

  await page.getByTestId('open-import-wizard').click()
  await expect(page.getByTestId('import-wizard')).toContainText('question-import/1.0')
  await page.setInputFiles('[data-testid="import-file-input"]', {
    name: 'questions.json',
    mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify({ schemaVersion: 'question-import/1.0', questions: [] })),
  })
  await page.getByTestId('preflight-import').click()
  await expect(page.getByTestId('import-result')).toContainText('READY_TO_CONFIRM')
  await expect.poll(() => requestedUrls.some((url) => url === 'POST /api/v1/admin/question-imports/preflight')).toBe(true)
})
