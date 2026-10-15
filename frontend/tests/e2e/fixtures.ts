import { expect, test as base } from '@playwright/test'

export const test = base.extend<{ runtimeErrorCheck: void }>({
  runtimeErrorCheck: [
    async ({ page }, use) => {
      const errors: string[] = []
      page.on('console', (message) => {
        if (message.type() === 'error') errors.push(`console: ${message.text()}`)
      })
      page.on('pageerror', (error) => errors.push(`pageerror: ${error.message}`))
      page.on('requestfailed', (request) => {
        const reason = request.failure()?.errorText ?? 'unknown error'
        if (!reason.includes('ERR_ABORTED')) errors.push(`requestfailed: ${request.method()} ${request.url()} ${reason}`)
      })

      await use()

      expect(errors).toEqual([])
    },
    { auto: true },
  ],
})

export { expect }
