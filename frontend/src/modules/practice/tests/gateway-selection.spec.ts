import { afterEach, describe, expect, it, vi } from 'vitest'

interface GatewayCase {
  name: string
  dev: boolean
  platformFlag?: string
  practiceFlag?: string
  expected: 'http' | 'mock'
}

const cases: GatewayCase[] = [
  { name: 'development + practice true', dev: true, practiceFlag: 'true', expected: 'mock' },
  { name: 'development + platform true', dev: true, platformFlag: 'true', expected: 'mock' },
  { name: 'development + flags missing', dev: true, expected: 'http' },
  { name: 'development + flags false', dev: true, platformFlag: 'false', practiceFlag: 'false', expected: 'http' },
  { name: 'production + platform true', dev: false, platformFlag: 'true', expected: 'http' },
  { name: 'production + practice true', dev: false, practiceFlag: 'true', expected: 'http' },
  { name: 'production + both true', dev: false, platformFlag: 'true', practiceFlag: 'true', expected: 'http' },
]

async function loadActualGateway(testCase: GatewayCase) {
  vi.unstubAllEnvs()
  vi.resetModules()
  vi.stubEnv('DEV', testCase.dev)
  vi.stubEnv('VITE_PLATFORM_MOCK', testCase.platformFlag)
  vi.stubEnv('VITE_PRACTICE_MOCK', testCase.practiceFlag)

  const { getPracticeGateway } = await import('../api/gateway')
  const gateway = getPracticeGateway()
  const { HttpPracticeGateway } = await import('../api/http-practice.gateway')
  const { MockPracticeGateway } = await import('../api/mock-practice.gateway')
  return { gateway, HttpPracticeGateway, MockPracticeGateway }
}

describe.sequential('actual practice gateway production gate', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    vi.unstubAllEnvs()
    vi.resetModules()
  })

  it.each(cases)('$name selects the real $expected gateway instance', async (testCase) => {
    const actual = await loadActualGateway(testCase)
    if (testCase.expected === 'mock') {
      expect(actual.gateway).toBeInstanceOf(actual.MockPracticeGateway)
      expect(actual.gateway).not.toBeInstanceOf(actual.HttpPracticeGateway)
    } else {
      expect(actual.gateway).toBeInstanceOf(actual.HttpPracticeGateway)
      expect(actual.gateway).not.toBeInstanceOf(actual.MockPracticeGateway)
    }
  }, 15_000)
})
