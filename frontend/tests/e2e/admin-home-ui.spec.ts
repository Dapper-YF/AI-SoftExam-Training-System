import type { Page, Route } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, test } from './fixtures'

const screenshotFolder = resolve('tests/e2e/screenshots/fast-admin-home-ui-02')
const adminAccount = {
  identifier: process.env.VITE_MOCK_ADMIN_IDENTIFIER ?? 'admin@example.com',
  password: process.env.VITE_MOCK_ADMIN_PASSWORD ?? 'e2e-admin-password',
}

const questionItems = [
  { questionId: 201, versionId: 301, qualificationId: 1, subjectId: 11, questionType: 'SINGLE_CHOICE', title: '范围管理单选题', status: 'IN_REVIEW', contentVersion: 2, baseVersion: 12, latestReviewDecision: 'PENDING', sourceType: 'ORIGINAL' },
  { questionId: 202, versionId: 302, qualificationId: 1, subjectId: 12, questionType: 'CASE', title: '案例材料草稿', status: 'DRAFT', contentVersion: 1, baseVersion: 3, latestReviewDecision: 'PENDING', sourceType: 'AUTHORIZED_IMPORT' },
  { questionId: 203, versionId: 303, qualificationId: 1, subjectId: 13, questionType: 'ESSAY', title: '论文写作要求', status: 'PUBLISHED', contentVersion: 2, baseVersion: 4, latestReviewDecision: 'APPROVED', sourceType: 'ORIGINAL' },
  { questionId: 204, versionId: 304, qualificationId: 1, subjectId: 11, questionType: 'MULTI_CHOICE', title: '多选待审题', status: 'IN_REVIEW', contentVersion: 1, baseVersion: 5, latestReviewDecision: 'PENDING', sourceType: 'ORIGINAL' },
  { questionId: 205, versionId: 305, qualificationId: 1, subjectId: 11, questionType: 'JUDGE', title: '判断停用题', status: 'RETIRED', contentVersion: 1, baseVersion: 2, latestReviewDecision: 'APPROVED', sourceType: 'AUTHORIZED_IMPORT' },
]

function envelope(data: unknown) {
  return { success: true, code: 'OK', message: 'OK', traceId: 'e2e-admin-home', data }
}

async function fulfillJson(route: Route, data: unknown): Promise<void> {
  await route.fulfill({
    status: 200,
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

async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  const measurements = await page.evaluate(() => ({
    overflow: document.documentElement.scrollWidth - window.innerWidth,
    clipped: [...document.querySelectorAll<HTMLElement>('body *')].filter((element) => {
      const rect = element.getBoundingClientRect()
      return rect.right > window.innerWidth + 1 || rect.left < -1
    }).length,
  }))
  expect(measurements.overflow).toBeLessThanOrEqual(0)
  expect(measurements.clipped).toBe(0)
}

test('admin home workbench remains compact across three viewports', async ({ page }) => {
  mkdirSync(screenshotFolder, { recursive: true })
  await page.route('**/api/v1/admin/questions**', async (route) => {
    if (route.request().method() === 'GET') {
      await fulfillJson(route, envelope({ items: questionItems, pageNo: 1, pageSize: 100, total: questionItems.length }))
      return
    }
    await route.fallback()
  })

  await loginAsAdmin(page)

  for (const viewport of [
    { width: 1440, height: 900 },
    { width: 1366, height: 768 },
    { width: 1280, height: 720 },
  ]) {
    await page.setViewportSize(viewport)
    await page.goto('/admin/home')
    await expect(page.getByTestId('admin-home-page')).toContainText('管理工作台')
    await expect(page.getByTestId('admin-identity-strip')).toContainText('ADMIN')
    await expect(page.getByTestId('admin-identity-strip')).toContainText('题库管理已授权')
    await expect(page.getByTestId('question-workbench-entry')).toContainText('题库管理')
    await expect(page.getByTestId('question-status-summary')).toContainText('待审核')
    await expect(page.getByTestId('question-type-strip')).toContainText('论文题')
    await expect(page.getByTestId('recent-import-state')).toContainText('授权导入')
    await expectNoHorizontalOverflow(page)
    await page.screenshot({
      path: resolve(screenshotFolder, `admin-home-${viewport.width}x${viewport.height}.png`),
      fullPage: false,
    })
  }
})
