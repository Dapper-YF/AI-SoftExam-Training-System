import type {
  DemoExamContext,
  ExamCandidate,
  ExamEnterResult,
  ExamLoginRequest,
  ExamLoginResult,
  ExamPaper,
  ExamRules,
  ExamSessionClockState,
  ExamSubmitResult,
  ExamSessionSnapshot,
} from '../types'

export class ExamSimulatorGatewayError extends Error {
  constructor(
    message: string,
    readonly code: string,
  ) {
    super(message)
    this.name = 'ExamSimulatorGatewayError'
  }
}

export interface ExamSimulatorGateway {
  login(request: ExamLoginRequest): Promise<ExamLoginResult>
  getCandidate(sessionId: string): Promise<ExamCandidate>
  confirmCandidate(sessionId: string): Promise<void>
  getRules(sessionId: string): Promise<ExamRules>
  acknowledgeRules(sessionId: string): Promise<void>
  getState(sessionId: string): Promise<ExamSessionClockState>
  enter(sessionId: string): Promise<ExamEnterResult>
  getPaper(sessionId: string): Promise<ExamPaper>
  getSessions?(sessionId: string): Promise<ExamSessionSnapshot[]>
  saveAnswer?(sessionId: string, subQuestionId: number, value: string, mutationId: string): Promise<void>
  toggleMark?(sessionId: string, questionId: number, marked: boolean, mutationId: string): Promise<void>
  continueNextSubject?(sessionId: string, mutationId: string): Promise<ExamSessionClockState>
  submit(sessionId: string, mutationId?: string, mode?: 'MANUAL' | 'FORCED'): Promise<ExamSubmitResult>
  getDemoContext(): DemoExamContext | null
}
