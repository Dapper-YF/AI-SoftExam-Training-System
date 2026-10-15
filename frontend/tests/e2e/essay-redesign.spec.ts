import type { Page } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, test } from './fixtures'

const screenshotRoot = resolve('tests/e2e/screenshots/essay-redesign')
const student = {
  identifier: process.env.VITE_MOCK_STUDENT_IDENTIFIER ?? '',
  password: process.env.VITE_MOCK_STUDENT_PASSWORD ?? '',
}

async function loginAndEnterEssay(page: Page): Promise<void> {
  await page.goto('/login')
  await page.getByTestId('platform-identifier').fill(student.identifier)
  await page.getByTestId('platform-password').fill(student.password)
  await page.getByTestId('platform-login-submit').click()
  await expect(page).toHaveURL(/\/app\/modules$/)
  await expect(page.getByTestId('essay-product-entry')).toHaveAttribute('data-state', 'preview')
  await page.getByTestId('essay-product-entry').click()
  await expect(page).toHaveURL(/\/app\/essay\/home$/)
  await expect(page.getByTestId('essay-home')).toBeVisible()
  await expect(page.getByTestId('essay-workflow-step')).toHaveCount(6)
}

async function expectNoOverflow(page: Page): Promise<void> {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
  expect(overflow).toBeLessThanOrEqual(0)
}

test('essay task flow is interactive and remains context bound', async ({ page }) => {
  await loginAndEnterEssay(page)
  await page.getByTestId('continue-draft').click()
  await expect(page).toHaveURL(/\/app\/essay\/editor$/)
  await expect(page.getByTestId('essay-editor-grid')).toBeVisible()
  await expect(page.getByTestId('essay-ai-context')).toContainText('p-3')
  await page.getByTestId('submit-review').click()
  await expect(page.getByTestId('review-submit-boundary')).toContainText('批改接口尚未联调')
  await page.getByRole('button', { name: '关闭' }).click()
  await page.getByRole('button', { name: '保留参考' }).first().click()
  await expect(page.getByText(/正文未发生变更/)).toBeVisible()

  await page.getByRole('menuitem', { name: '评阅报告' }).click()
  await expect(page).toHaveURL(/\/app\/essay\/reports$/)
  await page.getByTestId('report-issue-2').click()
  await expect(page.getByTestId('report-source-2')).toHaveClass(/is-active/)
  await expect(page.getByTestId('issue-question-context')).toContainText('issue-2')
})

test('captures the three essay pages at all acceptance viewports', async ({ page }) => {
  mkdirSync(screenshotRoot, { recursive: true })
  const viewports = [
    { width: 1440, height: 900 },
    { width: 1366, height: 768 },
    { width: 1280, height: 720 },
  ]

  for (const viewport of viewports) {
    await page.setViewportSize(viewport)
    await loginAndEnterEssay(page)
    const folder = resolve(screenshotRoot, `${viewport.width}x${viewport.height}`)
    mkdirSync(folder, { recursive: true })
    await expectNoOverflow(page)
    await page.screenshot({ path: resolve(folder, 'essay-home.png'), fullPage: false })

    await page.goto('/app/essay/editor')
    await expect(page.getByTestId('essay-editor-grid')).toBeVisible()
    await expectNoOverflow(page)
    await page.screenshot({ path: resolve(folder, 'essay-editor.png'), fullPage: false })

    await page.goto('/app/essay/reports')
    await expect(page.getByTestId('report-issue-2')).toBeVisible()
    await page.getByTestId('report-issue-2').click()
    await expectNoOverflow(page)
    await page.screenshot({ path: resolve(folder, 'essay-report.png'), fullPage: false })

    await page.getByTestId('platform-user-menu').click()
    await page.getByTestId('platform-logout').click()
  }
})
