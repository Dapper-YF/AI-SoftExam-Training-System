import { expect, test } from './fixtures'

const authE2E = {
  nickname: process.env.AUTH_E2E_NICKNAME ?? `auth_e2e_${Date.now()}`,
  phone: process.env.AUTH_E2E_PHONE ?? '',
  email: process.env.AUTH_E2E_EMAIL ?? '',
  password: process.env.AUTH_E2E_PASSWORD ?? 'Auth-e2e-password-2026',
  verificationCode: process.env.AUTH_E2E_VERIFICATION_CODE ?? '',
  wrongPassword: process.env.AUTH_E2E_WRONG_PASSWORD ?? 'wrong-password-2026',
}

test.describe.configure({ mode: 'serial' })

test('real auth flow: public action, register, login, refresh restore, forgot entry, logout', async ({ page }) => {
  test.skip(!authE2E.phone || !authE2E.verificationCode, 'AUTH_E2E_PHONE and AUTH_E2E_VERIFICATION_CODE are required for the real backend flow.')

  await page.goto('/')
  await expect(page.getByTestId('public-home-page')).toBeVisible()
  await page.getByTestId('public-practice-action').click()
  await expect(page).toHaveURL(/\/login\?redirect=%2Fapp%2Fpractice%2Fstart/)

  await page.getByTestId('register-link').click()
  await expect(page.getByTestId('register-page')).toBeVisible()
  await page.getByTestId('register-nickname').fill(authE2E.nickname)
  await page.getByTestId('register-phone').fill(authE2E.phone)
  if (authE2E.email) await page.getByTestId('register-email').fill(authE2E.email)
  await page.getByTestId('register-password').fill(authE2E.password)
  await page.getByTestId('register-confirm-password').fill(authE2E.password)
  await page.getByTestId('request-register-code').click()
  await page.getByTestId('register-code').fill(authE2E.verificationCode)
  await page.getByTestId('register-submit').click()
  await expect(page).toHaveURL(/\/login\?registered=1/)
  await expect(page.getByTestId('login-registered-message')).toContainText('注册成功')

  await page.getByTestId('platform-identifier').fill(authE2E.phone)
  await page.getByTestId('platform-password').fill(authE2E.wrongPassword)
  await page.getByTestId('platform-login-submit').click()
  await expect(page.getByTestId('login-error')).toContainText('手机号或邮箱或密码错误')

  await page.getByTestId('platform-password').fill(authE2E.password)
  await page.getByTestId('platform-login-submit').click()
  await expect(page).toHaveURL(/\/app\/modules$/)
  await page.reload()
  await expect(page.getByTestId('module-home')).toBeVisible()

  await page.goto('/forgot-password')
  await expect(page.getByTestId('forgot-password-page')).toBeVisible()

  await page.goto('/app/modules')
  await page.getByTestId('platform-user-menu').click()
  await page.getByTestId('platform-logout').click()
  await page.goto('/app/modules')
  await expect(page).toHaveURL(/\/login\?redirect=%2Fapp%2Fmodules/)
})
