import { afterEach, describe, expect, it, vi } from 'vitest'
import { MockPlatformAuthGateway } from './mock-platform-auth.gateway'

afterEach(() => {
  vi.unstubAllEnvs()
})

describe('mock platform auth credentials', () => {
  it('exposes no accounts and reports a clear configuration error when credentials are absent', async () => {
    vi.stubEnv('VITE_MOCK_STUDENT_IDENTIFIER', '')
    vi.stubEnv('VITE_MOCK_STUDENT_PASSWORD', '')
    vi.stubEnv('VITE_MOCK_ADMIN_IDENTIFIER', '')
    vi.stubEnv('VITE_MOCK_ADMIN_PASSWORD', '')
    const gateway = new MockPlatformAuthGateway()

    expect(gateway.getDemoAccounts()).toEqual([])
    await expect(gateway.login({ identifier: 'missing@example.com', password: 'missing' })).rejects.toMatchObject({
      code: 'MOCK_AUTH_NOT_CONFIGURED',
    })
  })

  it('uses only credentials supplied through explicit mock environment variables', async () => {
    vi.stubEnv('VITE_MOCK_STUDENT_IDENTIFIER', '13800138000')
    vi.stubEnv('VITE_MOCK_STUDENT_PASSWORD', 'fixture-student-pass')
    vi.stubEnv('VITE_MOCK_ADMIN_IDENTIFIER', 'admin@example.com')
    vi.stubEnv('VITE_MOCK_ADMIN_PASSWORD', 'fixture-admin-pass')
    const gateway = new MockPlatformAuthGateway()

    expect(gateway.getDemoAccounts().map((account) => account.identifier)).toEqual([
      '13800138000',
      'admin@example.com',
    ])
    await expect(
      gateway.login({ identifier: '13800138000', password: 'fixture-student-pass' }),
    ).resolves.toMatchObject({ roles: ['STUDENT'] })
  })
})
