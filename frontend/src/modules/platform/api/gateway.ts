import { MockPlatformHomeGateway } from './mock-platform-home.gateway'
import { HttpPlatformHomeGateway } from './http-platform-home.gateway'
import { resolveGatewayKind } from '@/modules/foundation/api/mock-mode'
import type { PlatformHomeGateway } from './platform-home.gateway'

const gatewayKind = resolveGatewayKind(import.meta.env.VITE_PLATFORM_MOCK)
let platformHomeGateway: PlatformHomeGateway = gatewayKind === 'mock'
  ? new MockPlatformHomeGateway()
  : new HttpPlatformHomeGateway()

export function getPlatformHomeGateway(): PlatformHomeGateway {
  return platformHomeGateway
}

export function setPlatformHomeGatewayForTests(gateway: PlatformHomeGateway): void {
  platformHomeGateway = gateway
}
