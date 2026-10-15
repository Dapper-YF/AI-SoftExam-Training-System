import { afterEach, describe, expect, it, vi } from 'vitest'

async function selectedGatewayName(value: string | undefined): Promise<string> {
  vi.resetModules()
  vi.unstubAllEnvs()
  vi.stubEnv('VITE_EXAM_SIMULATOR_MOCK', value)

  const { getExamSimulatorGateway } = await import('../api/gateway')
  return getExamSimulatorGateway().constructor.name
}

describe('exam simulator gateway selection', () => {
  afterEach(() => {
    vi.unstubAllEnvs()
    vi.resetModules()
  })

  it(
    'uses HTTP when the mock switch is missing',
    async () => {
      await expect(selectedGatewayName(undefined)).resolves.toBe('HttpExamSimulatorGateway')
    },
    30_000,
  )

  it(
    'uses HTTP when the mock switch is false',
    async () => {
      await expect(selectedGatewayName('false')).resolves.toBe('HttpExamSimulatorGateway')
    },
    30_000,
  )

  it(
    'uses local simulator only when the mock switch is true',
    async () => {
      await expect(selectedGatewayName('true')).resolves.toBe('MockExamSimulatorGateway')
    },
    30_000,
  )
})
