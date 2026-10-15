import { HttpPlatformAuthGateway } from './http-platform-auth.gateway'
import { HttpPlatformFoundationGateway } from './http-platform-foundation.gateway'
import { MockPlatformAuthGateway } from './mock-platform-auth.gateway'
import { MockPlatformFoundationGateway } from './mock-platform-foundation.gateway'
import { isExplicitMockEnabled } from './mock-mode'
import type { PlatformAuthGateway } from './platform-auth.gateway'
import type { PlatformFoundationGateway } from './platform-foundation.gateway'

const useMock = import.meta.env.DEV && isExplicitMockEnabled(import.meta.env.VITE_PLATFORM_MOCK)
let authGateway: PlatformAuthGateway = useMock
  ? new MockPlatformAuthGateway()
  : new HttpPlatformAuthGateway()
let foundationGateway: PlatformFoundationGateway = useMock
  ? new MockPlatformFoundationGateway()
  : new HttpPlatformFoundationGateway()

export function getPlatformFoundationGateway(): PlatformFoundationGateway {
  return foundationGateway
}

export function getPlatformAuthGateway(): PlatformAuthGateway {
  return authGateway
}

export function setPlatformAuthGatewayForTests(gateway: PlatformAuthGateway): void {
  authGateway = gateway
}

export function setPlatformFoundationGatewayForTests(
  gateway: PlatformFoundationGateway,
): void {
  foundationGateway = gateway
}
