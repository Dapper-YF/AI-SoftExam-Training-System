import type { Page } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, test } from './fixtures'

const demoAdmissionTicket = '202607100001'
const demoIdentityNumber = '430102199001011234'
const screenshotRoot = resolve('tests/e2e/screenshots')
const mockStudent = {
  identifier: process.env.VITE_MOCK_STUDENT_IDENTIFIER ?? '',
  password: process.env.VITE_MOCK_STUDENT_PASSWORD ?? '',
}

async function loginAsStudent(page: Page): Promise<void> {
  await page.goto('/login')
  await page.getByTestId('platform-identifier').fill(mockStudent.identifier)
  await page.getByTestId('platform-password').fill(mockStudent.password)
  await page.getByTestId('platform-login-submit').click()
  await expect(page).toHaveURL(/\/app\/modules$/)
}

async function openLogin(page: Page): Promise<void> {
  await page.goto('/exam-simulator')
  await page.getByTestId('entry-linked').click()
  await expect(page.getByTestId('p2-login-page')).toBeVisible()
}

async function login(page: Page): Promise<void> {
  await page.getByTestId('admission-ticket-input').fill(demoAdmissionTicket)
  await page.getByTestId('identity-number-input').fill(demoIdentityNumber)
  await page.getByTestId('login-submit').click()
  await expect(page.getByTestId('p3-candidate-page')).toBeVisible()
}

async function reachRules(page: Page): Promise<void> {
  await openLogin(page)
  await login(page)
  await page.getByTestId('candidate-confirm').click()
  await expect(page.getByTestId('p4-rules-page')).toBeVisible()
}

async function acknowledgeForcedReminderAndEnter(page: Page): Promise<void> {
  const dialog = page.getByTestId('force-start-dialog')
  await expect(dialog).toBeVisible({ timeout: 6_000 })
  await page.keyboard.press('Escape')
  await expect(dialog).toBeVisible()
  await page.mouse.click(4, 4)
  await expect(dialog).toBeVisible()
  await page.getByTestId('force-confirm').click()
  await expect(dialog).toBeHidden()
  await page.getByTestId('acknowledge-rules').click()
  await expect(page.getByTestId('p6-exam-page')).toBeVisible({ timeout: 8_000 })
}

function parseClock(value: string): number {
  const [hours = 0, minutes = 0, seconds = 0] = value.split(':').map(Number)
  return hours * 3_600 + minutes * 60 + seconds
}

test('P1-P6 flow enforces gates, preserves time, answers and marks', async ({ page }) => {
  await openLogin(page)

  await page.getByTestId('admission-ticket-input').fill('wrong')
  await page.getByTestId('identity-number-input').fill('wrong')
  await page.getByTestId('login-reset').click()
  await expect(page.getByTestId('admission-ticket-input')).toHaveValue('')
  await expect(page.getByTestId('identity-number-input')).toHaveValue('')

  await page.getByTestId('admission-ticket-input').fill('wrong')
  await page.getByTestId('identity-number-input').fill('wrong')
  await page.getByTestId('login-submit').click()
  await expect(page.getByText('准考证号或证件号不正确')).toBeVisible()
  await expect(page).toHaveURL(/\/login\/LINKED$/)

  await login(page)
  const candidateUrl = page.url()
  const sessionId = candidateUrl.match(/session\/([^/]+)/)?.[1]
  expect(sessionId).toBeTruthy()

  await page.goto(`/exam-simulator/session/${sessionId}/rules`)
  await expect(page.getByTestId('p3-candidate-page')).toBeVisible()
  await page.getByTestId('candidate-confirm').click()
  await expect(page.getByTestId('p4-rules-page')).toBeVisible()

  await page.goto(`/exam-simulator/session/${sessionId}/exam`)
  await expect(page.getByTestId('p4-rules-page')).toBeVisible()
  await acknowledgeForcedReminderAndEnter(page)

  await expect.poll(async () =>
    parseClock(await page.getByTestId('exam-remaining-time').innerText()),
  ).toBeLessThan(9_000)

  await page.getByTestId('answer-B').click()
  await page.getByTestId('toggle-mark').click()
  await page.getByTestId('question-number-2').click()
  await expect(page.getByTestId('question-number-1')).toHaveClass(/question-number--answered/)
  await expect(page.getByTestId('question-number-1')).toHaveClass(/question-number--marked/)

  await page.reload()
  await expect(page.getByTestId('p6-exam-page')).toBeVisible()
  await page.getByTestId('question-number-1').click()
  await expect(page.getByTestId('answer-B')).toHaveClass(/is-checked/)
  await expect(page.getByTestId('toggle-mark')).toContainText('取消标记')
})

test('approved practice exits are limited to entry and server-confirmed submission', async ({ page }) => {
  await loginAsStudent(page)
  await page.getByTestId('practice-product-entry').click()
  await expect(page).toHaveURL(/\/app\/practice\/home$/)
  await page.getByRole('menuitem').nth(2).click()
  await expect(page.getByTestId('p1-entry-page')).toBeVisible()

  await page.getByTestId('entry-linked').click()
  await login(page)
  const sessionId = page.url().match(/session\/([^/]+)/)?.[1]
  expect(sessionId).toBeTruthy()

  await page.goto('/exam-simulator')
  await page.getByTestId('entry-return-practice').click()
  await expect(page).toHaveURL(/\/app\/practice\/home$/)
  await page.goto(`/exam-simulator/session/${sessionId}/candidate`)
  await expect(page.getByTestId('p3-candidate-page')).toBeVisible()

  await page.getByTestId('candidate-confirm').click()
  await expect(page.getByTestId('p4-rules-page')).toBeVisible()
  await acknowledgeForcedReminderAndEnter(page)
  await expect(page.getByTestId('exam-return-practice')).toHaveCount(0)

  await page.getByTestId('exam-submit').click()
  await expect(page.getByTestId('exam-return-practice')).toBeVisible()
  await page.getByTestId('exam-return-practice').click()
  await expect(page).toHaveURL(/\/app\/practice\/home$/)
})

for (const viewport of [
  { width: 1280, height: 720 },
  { width: 1366, height: 768 },
  { width: 1440, height: 900 },
  { width: 1536, height: 1024 },
  { width: 1920, height: 1080 },
]) {
  test(`P1-P6 screenshots and overflow at ${viewport.width}x${viewport.height}`, async ({ page }) => {
    await page.setViewportSize(viewport)
    const folder = resolve(screenshotRoot, `${viewport.width}x${viewport.height}`)
    mkdirSync(folder, { recursive: true })

    await page.goto('/exam-simulator')
    await expect(page.getByTestId('p1-entry-page')).toBeVisible()
    await page.screenshot({ path: resolve(folder, 'p1-entry.png') })

    await page.getByTestId('entry-linked').click()
    await expect(page.getByTestId('p2-login-page')).toBeVisible()
    await page.screenshot({ path: resolve(folder, 'p2-login.png') })

    await login(page)
    await page.screenshot({ path: resolve(folder, 'p3-candidate.png') })

    await page.getByTestId('candidate-confirm').click()
    await expect(page.getByTestId('p4-rules-page')).toBeVisible()
    await page.screenshot({ path: resolve(folder, 'p4-rules.png') })

    await expect(page.getByTestId('force-start-dialog')).toBeVisible({ timeout: 6_000 })
    await page.screenshot({ path: resolve(folder, 'p5-force-reminder.png') })
    await page.getByTestId('force-confirm').click()
    await page.getByTestId('acknowledge-rules').click()
    await expect(page.getByTestId('p6-exam-page')).toBeVisible({ timeout: 8_000 })
    await page.screenshot({ path: resolve(folder, 'p6-exam.png') })

    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
    expect(overflow).toBeLessThanOrEqual(0)
  })
}
