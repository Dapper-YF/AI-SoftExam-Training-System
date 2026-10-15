import type { Page } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, test } from './fixtures'

const screenshotRoot = resolve('tests/e2e/screenshots/visual-realignment')
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
  await expect(page.getByTestId('module-home')).toBeVisible()
}

async function enterPracticeStart(page: Page): Promise<void> {
  await page.getByTestId('practice-product-entry').click()
  await expect(page).toHaveURL(/\/app\/practice\/home$/)
  await expect(page.getByTestId('current-product')).toHaveText('软考刷题')
  await expect(page.getByTestId('practice-home-page')).toBeVisible()
  await page.getByRole('menuitem', { name: '刷题练习' }).click()
  await expect(page).toHaveURL(/\/app\/practice\/start$/)
  await expect(page.getByTestId('practice-list-page')).toBeVisible()
}

async function confirmCurrentAnswer(page: Page, option = 'B'): Promise<void> {
  await page.getByTestId(`practice-answer-${option}`).click()
  await expect(page.getByTestId('question-analysis')).toHaveCount(0)
  await expect(page.getByText('已保存').first()).toBeVisible()
  await page.getByTestId('confirm-answer').click()
  await expect(page.getByTestId('question-analysis')).toBeVisible()
}

async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
  expect(overflow).toBeLessThanOrEqual(0)
}

async function startFirstPractice(page: Page): Promise<void> {
  await page.getByTestId('practice-paper-row').first().getByRole('button').click()
  await expect(page).toHaveURL(/\/app\/practice\/attempts\/attempt-/)
  await expect(page.getByTestId('practice-session-page')).toBeVisible()
}

test('module selection, practice workspace, list and answer flow remain interactive', async ({ page }) => {
  await loginAsStudent(page)
  await enterPracticeStart(page)
  await expect(page.getByTestId('practice-paper-row')).toHaveCount(6)

  await page.getByPlaceholder('关键词搜索').fill('2024')
  await page.getByPlaceholder('关键词搜索').press('Enter')
  await expect(page.getByTestId('practice-paper-row')).toHaveCount(2)

  await startFirstPractice(page)
  await confirmCurrentAnswer(page)
  await expect(page.getByText('回答正确')).toBeVisible()
  await page.getByRole('button', { name: '标记' }).click()
  await page.getByRole('button', { name: '收藏' }).click()
  await page.getByRole('button', { name: '笔记' }).click()
  await page.getByPlaceholder('记录解题思路、易错点或复习提醒').fill('WBS 分解层级要适度。')
  await page.getByRole('dialog', { name: '题目笔记' }).getByRole('button', { name: '保存笔记' }).click()
  await page.getByRole('button', { name: '下一题' }).click()
  await expect(page.getByText('项目进度管理')).toBeVisible()
})

test('captures practice home and daily practice at three target viewports through the real product-entry flow', async ({ page }) => {
  mkdirSync(screenshotRoot, { recursive: true })
  const viewports = [
    { width: 1280, height: 720 },
    { width: 1366, height: 768 },
    { width: 1440, height: 900 },
  ]

  for (const viewport of viewports) {
    await page.setViewportSize(viewport)
    await loginAsStudent(page)
    const folder = resolve(screenshotRoot, `${viewport.width}x${viewport.height}`)
    mkdirSync(folder, { recursive: true })
    await page.getByTestId('practice-product-entry').click()
    await expect(page).toHaveURL(/\/app\/practice\/home$/)
    await expect(page.getByTestId('practice-home-page')).toBeVisible()
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: resolve(folder, 'practice-home.png'), fullPage: false })

    await page.getByRole('menuitem', { name: '刷题练习' }).click()
    await expect(page).toHaveURL(/\/app\/practice\/start$/)
    await expect(page.getByTestId('practice-list-page')).toBeVisible()
    await expect(page.getByTestId('practice-paper-row')).toHaveCount(6)
    await startFirstPractice(page)
    await confirmCurrentAnswer(page)
    await expectNoHorizontalOverflow(page)
    await page.screenshot({ path: resolve(folder, 'practice-session.png'), fullPage: false })

    await page.getByTestId('platform-user-menu').click()
    await page.getByTestId('platform-logout').click()
  }
})

test('practice header keeps the qualification catalogue usable at three desktop viewports', async ({ page }) => {
  test.setTimeout(90_000)
  const screenshotRoot = resolve('tests/e2e/screenshots/practice-header-scope-hotfix04')
  mkdirSync(screenshotRoot, { recursive: true })
  const viewports = [
    { width: 1280, height: 720 },
    { width: 1366, height: 768 },
    { width: 1440, height: 900 },
  ]

  for (const viewport of viewports) {
    await page.setViewportSize(viewport)
    await loginAsStudent(page)
    await page.getByTestId('practice-product-entry').click()
    await expect(page).toHaveURL(/\/app\/practice\/home$/)
    await expect(page.getByTestId('practice-home-page')).toBeVisible()
    await expect(
      page.getByTestId('practice-scope-selector').getByRole('combobox', { name: '科目', exact: true }),
    ).toHaveCount(0)
    await expect(page.getByText('专业 · 高效 · 智能')).toHaveCount(0)
    await expectNoHorizontalOverflow(page)
    const scopeSelector = page.getByTestId('practice-scope-selector')
    const levelSelect = scopeSelector.locator('.practice-scope-field--level .el-select')
    const qualificationSelect = scopeSelector.locator('.practice-scope-field--qualification .el-select')

    const headerLayout = await page.getByTestId('platform-header').evaluate((header) => {
      const brand = header.querySelector('.platform-brand')?.getBoundingClientRect()
      const scope = header.querySelector('.practice-scope-selector')?.getBoundingClientRect()
      const actions = header.querySelector('.header-actions')?.getBoundingClientRect()
      const qualification = header.querySelector('.practice-scope-field--qualification .el-select__selected-item') as HTMLElement | null
      return {
        ordered: Boolean(brand && scope && actions && brand.right <= scope.left && scope.right <= actions.left),
        qualificationClipped: Boolean(qualification && qualification.scrollWidth > qualification.clientWidth),
      }
    })
    expect(headerLayout).toEqual({ ordered: true, qualificationClipped: false })

    for (const [levelLabel, expectedCount] of [['高级', 5], ['中级', 11], ['初级', 4]] as const) {
      await levelSelect.click()
      await page.getByRole('option', { name: levelLabel, exact: true }).click()
      await qualificationSelect.click()
      await expect(page.getByRole('option')).toHaveCount(expectedCount)
      await page.keyboard.press('Escape')
    }

    await levelSelect.click()
    await page.getByRole('option', { name: '高级', exact: true }).click()
    await page.keyboard.press('Escape')
    await expect(page.getByRole('option')).toHaveCount(0)
    await expect(page.getByTestId('qualification-workspace-title')).toContainText('信息系统项目管理师')
    await page.screenshot({ path: resolve(screenshotRoot, `practice-header-${viewport.width}x${viewport.height}.png`), fullPage: false })
    if (viewport.width === 1440) {
      await qualificationSelect.click()
      await expect(page.getByRole('option')).toHaveCount(5)
      const qualificationPopup = page.locator('.el-select__popper:visible')
      await expect(qualificationPopup).toBeVisible()
      await expect.poll(async () => Number(await qualificationPopup.evaluate((element) => getComputedStyle(element).opacity))).toBe(1)
      await page.screenshot({ path: resolve(screenshotRoot, 'practice-header-1440x900-qualification-open.png'), fullPage: false })
      await page.keyboard.press('Escape')
    }

    await page.getByTestId('platform-user-menu').click()
    await page.getByTestId('platform-logout').click()
  }
})

test('captures design QA states through module and workspace navigation', async ({ page }) => {
  const folder = resolve('tests/e2e/screenshots/design-qa')
  mkdirSync(folder, { recursive: true })

  await page.setViewportSize({ width: 1440, height: 900 })
  await loginAsStudent(page)
  await page.getByTestId('practice-product-entry').click()
  await expect(page.getByTestId('practice-home-page')).toBeVisible()
  await page.screenshot({ path: resolve(folder, 'practice-home-1440x900.png') })
  await page.getByRole('menuitem', { name: '刷题练习' }).click()
  await expect(page).toHaveURL(/\/app\/practice\/start$/)
  await expect(page.getByTestId('practice-list-page')).toBeVisible()
  await expect(page.getByTestId('practice-paper-row')).toHaveCount(6)
  await startFirstPractice(page)
  await page.getByTestId('practice-answer-A').click()
  await expect(page.getByTestId('question-analysis')).toHaveCount(0)
  await expect(page.getByText('已保存').first()).toBeVisible()
  await page.screenshot({ path: resolve(folder, 'practice-session-unconfirmed-1440x900.png'), fullPage: false })
  await page.getByTestId('confirm-answer').click()
  await expect(page.getByTestId('question-analysis')).toBeVisible()
  await page.screenshot({ path: resolve(folder, 'practice-session-1440x900.png'), fullPage: false })
})

test('six primary entries use their frozen paths and keep exams outside daily practice', async ({ page }) => {
  await loginAsStudent(page)
  await page.getByTestId('practice-product-entry').click()
  await expect(page).toHaveURL(/\/app\/practice\/home$/)

  for (const label of ['首页', '刷题练习', '模拟考试', '错题与收藏', '学习报告', '学习资料']) {
    await expect(page.getByRole('menuitem', { name: label })).toBeVisible()
  }

  await page.getByRole('menuitem', { name: '刷题练习' }).click()
  await expect(page).toHaveURL(/\/app\/practice\/start$/)
  await startFirstPractice(page)
  await expect(page).toHaveURL(/\/app\/practice\/attempts\/attempt-/)

  await page.goBack()
  await expect(page).toHaveURL(/\/app\/practice\/start$/)
  await page.getByRole('menuitem', { name: '错题与收藏' }).click()
  await expect(page).toHaveURL(/\/app\/practice\/review$/)
  await expect(page.getByTestId('practice-review-slot')).toHaveAttribute('data-api-boundary', '501')

  await page.getByRole('menuitem', { name: '学习报告' }).click()
  await expect(page).toHaveURL(/\/app\/practice\/reports$/)
  await expect(page.getByTestId('practice-reports-slot')).toBeVisible()

  await page.getByRole('menuitem', { name: '学习资料' }).click()
  await expect(page).toHaveURL(/\/app\/practice\/resources$/)
  await expect(page.getByTestId('practice-resources-slot')).toHaveAttribute('data-api-boundary', '501')

  await page.getByRole('menuitem', { name: '模拟考试' }).click()
  await expect(page).toHaveURL(/\/exam-simulator$/)
  await expect(page.getByTestId('p1-entry-page')).toBeVisible()
  await expect(page.getByTestId('platform-header')).toHaveCount(0)
  await expect(page.getByTestId('platform-sidebar')).toHaveCount(0)
})
