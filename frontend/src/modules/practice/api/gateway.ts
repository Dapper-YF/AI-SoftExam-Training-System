import { HttpPracticeGateway } from './http-practice.gateway'
import { MockPracticeGateway } from './mock-practice.gateway'
import { resolvePracticeGatewayKind } from './practice-contract-adapter'
import type { PracticeGateway } from './practice.gateway'

const useMock = import.meta.env.DEV && resolvePracticeGatewayKind(
  import.meta.env.VITE_PLATFORM_MOCK,
  import.meta.env.VITE_PRACTICE_MOCK,
) === 'mock'
let gateway: PracticeGateway = useMock ? new MockPracticeGateway() : new HttpPracticeGateway()

export function getPracticeGateway(): PracticeGateway {
  return gateway
}

export function setPracticeGatewayForTests(next: PracticeGateway): void {
  gateway = next
}
