import type { ExamSimulatorGateway } from './exam-simulator.gateway'
import { HttpExamSimulatorGateway } from './http-exam-simulator.gateway'
import { MockExamSimulatorGateway } from './mock-exam-simulator.gateway'
import { isExplicitMockEnabled } from '@/modules/foundation/api/mock-mode'

const useMock = isExplicitMockEnabled(import.meta.env.VITE_EXAM_SIMULATOR_MOCK)
let currentGateway: ExamSimulatorGateway = useMock
  ? new MockExamSimulatorGateway()
  : new HttpExamSimulatorGateway()

export function getExamSimulatorGateway(): ExamSimulatorGateway {
  return currentGateway
}

export function setExamSimulatorGatewayForTests(gateway: ExamSimulatorGateway): void {
  currentGateway = gateway
}
