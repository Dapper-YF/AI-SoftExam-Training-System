import { HttpAdminQuestionGateway } from './http-admin-question.gateway'
import type { AdminQuestionGatewayKind } from '../types'
import type { AdminQuestionGateway } from './admin-question.gateway'

export function resolveAdminQuestionGatewayKind(
  _mockFlag: string | undefined,
  _mode = import.meta.env.MODE,
): AdminQuestionGatewayKind {
  return 'http'
}

const gatewayKind = resolveAdminQuestionGatewayKind(import.meta.env.VITE_ADMIN_QUESTION_MOCK)

let adminQuestionGateway: AdminQuestionGateway = new HttpAdminQuestionGateway()

export function getAdminQuestionGateway(): AdminQuestionGateway {
  return adminQuestionGateway
}

export function getAdminQuestionGatewayKind(): AdminQuestionGatewayKind {
  return gatewayKind
}

export function setAdminQuestionGatewayForTests(gateway: AdminQuestionGateway): void {
  adminQuestionGateway = gateway
}
