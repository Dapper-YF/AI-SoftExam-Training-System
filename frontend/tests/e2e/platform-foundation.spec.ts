import type { Page } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, test } from './fixtures'

const screenshotFolder = resolve('tests/e2e/screenshots/fe-redesign-01')
const mockAccounts = {
  student: { identifier: process.env.VITE_MOCK_STUDENT_IDENTIFIER ?? '', password: process.env.VITE_MOCK_STUDENT_PASSWORD ?? '' },
  admin: { identifier: process.env.VITE_MOCK_ADMIN_IDENTIFIER ?? '', password: process.env.VITE_MOCK_ADMIN_PASSWORD ?? '' },
}

async function loginAs(page: Page, role: 'student' | 'admin'): Promise<void> {
  const account = mockAccounts[role]
  await page.goto('/login')
  await page.getByTestId('platform-identifier').fill(account.identifier)
  await page.getByTestId('platform-password').fill(account.password)
  await page.getByTestId('platform-login-submit').click()
}

async function expectNoOverflow(page: Page): Promise<void> {
  const measurements = await page.evaluate(() => ({
    documentOverflow: document.documentElement.scrollWidth - window.innerWidth,
    clippedElements: [...document.querySelectorAll<HTMLElement>('body *')].filter((element) => {
      const rect = element.getBoundingClientRect()
      return rect.right > window.innerWidth + 1 || rect.left < -1
    }).length,
  }))
  expect(measurements.documentOverflow).toBeLessThanOrEqual(0)
  expect(measurements.clippedElements).toBe(0)
}

test('login lands on module home and workspace history stays stable', async ({ page }) => {
  await page.goto('/app/modules')
  await expect(page.getByTestId('platform-login-page')).toBeVisible()
  expect(new URL(page.url()).searchParams.get('redirect')).toBe('/app/modules')

  await page.getByTestId('platform-identifier').fill(mockAccounts.student.identifier)
  await page.getByTestId('platform-password').fill(mockAccounts.student.password)
  await page.getByTestId('platform-login-submit').click()
  await expect(page).toHaveURL(/\/app\/modules$/)
  await expect(page.getByTestId('module-home')).toBeVisible()
  await expect(page.getByTestId('practice-product-entry')).toBeVisible()
  await expect(page.getByTestId('essay-product-entry')).toBeVisible()
  await expect(page.getByTestId('current-product')).toHaveCount(0)
  await expect(page.getByTestId('platform-sidebar')).toHaveCount(0)

  await page.getByTestId('practice-product-entry').click()
  await expect(page).toHaveURL(/\/app\/practice\/home$/)
  await expect(page.getByTestId('current-product')).toHaveText('软考刷题')
  await page.getByRole('button', { name: /返回模块首页/ }).click()
  await expect(page).toHaveURL(/\/app\/modules$/)

  await page.goBack()
  await expect(page).toHaveURL(/\/app\/practice\/home$/)
  await page.goForward()
  await expect(page).toHaveURL(/\/app\/modules$/)

  await page.goto('/exam-simulator')
  await expect(page.getByTestId('p1-entry-page')).toBeVisible()
  await expect(page.getByTestId('platform-header')).toHaveCount(0)
  await expect(page.getByTestId('platform-sidebar')).toHaveCount(0)
})

test('essay entry opens the development preview without a browser storage switch', async ({ page }) => {
  await loginAs(page, 'student')
  await expect(page).toHaveURL(/\/app\/modules$/)
  const essayEntry = page.getByTestId('essay-product-entry')
  await expect(essayEntry).toHaveAttribute('data-state', 'preview')
  await expect(page.getByTestId('essay-product-entry')).toContainText('开发预览')
  await page.getByTestId('essay-product-entry').click()
  await expect(page).toHaveURL(/\/app\/essay\/home$/)
  await expect(page.getByTestId('workspace-layout')).toBeVisible()
  await expect(page.getByTestId('current-product')).toHaveText('AI 论文')

  await page.goto('/not-a-real-route')
  await expect(page.getByTestId('status-404')).toBeVisible()
})

test('admin permissions remain isolated from student workspaces', async ({ page }) => {
  await loginAs(page, 'admin')
  await expect(page).toHaveURL(/\/admin\/home$/)
  await page.goto('/app/modules')
  await expect(page.getByTestId('status-403')).toBeVisible()
})

test('captures module home at the three acceptance viewports without overflow', async ({ page }) => {
  mkdirSync(screenshotFolder, { recursive: true })
  await loginAs(page, 'student')
  await expect(page.getByTestId('module-home')).toBeVisible()

  for (const viewport of [
    { width: 1440, height: 900 },
    { width: 1366, height: 768 },
    { width: 1280, height: 720 },
  ]) {
    await page.setViewportSize(viewport)
    await page.goto('/app/modules')
    await expect(page.getByTestId('module-home')).toBeVisible()
    await expectNoOverflow(page)
    await page.screenshot({
      path: resolve(screenshotFolder, `module-home-${viewport.width}x${viewport.height}.png`),
      fullPage: false,
    })
  }
})
