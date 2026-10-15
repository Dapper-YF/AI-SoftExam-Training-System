import { HttpPortalContentGateway } from './http-portal-content.gateway'
import type { PortalContentGateway } from './portal-content.gateway'

let portalContentGateway: PortalContentGateway = new HttpPortalContentGateway()

export function getPortalContentGateway(): PortalContentGateway {
  return portalContentGateway
}

export function setPortalContentGatewayForTests(gateway: PortalContentGateway): void {
  portalContentGateway = gateway
}
