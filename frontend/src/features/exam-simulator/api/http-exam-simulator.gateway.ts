import { ApiClientError, examApiClient, unwrapApiResponse } from '@/api/client'
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
import type { ExamSimulatorGateway } from './exam-simulator.gateway'

export class HttpExamSimulatorGateway implements ExamSimulatorGateway {
  login(request: ExamLoginRequest): Promise<ExamLoginResult> {
    return unwrapApiResponse(examApiClient.post('/exam-simulator/login', {
      ...request,
      qualificationCode: request.qualificationCode
        ?? (request.entryCode === 'ESSAY' || request.entryCode === 'LINKED' ? 'HIGH_PM' : 'MIDDLE_SOFTWARE_DESIGNER'),
    }))
  }

  getCandidate(sessionId: string): Promise<ExamCandidate> {
    return unwrapApiResponse(examApiClient.get(`/exam-simulator/sessions/${sessionId}/candidate`))
  }

  async confirmCandidate(sessionId: string): Promise<void> {
    await unwrapApiResponse(examApiClient.post(`/exam-simulator/sessions/${sessionId}/confirm-candidate`))
  }

  getRules(sessionId: string): Promise<ExamRules> {
    return unwrapApiResponse(examApiClient.get(`/exam-simulator/sessions/${sessionId}/rules`))
  }

  async acknowledgeRules(sessionId: string): Promise<void> {
    await unwrapApiResponse(examApiClient.post(`/exam-simulator/sessions/${sessionId}/acknowledge-rules`))
  }

  getState(sessionId: string): Promise<ExamSessionClockState> {
    return unwrapApiResponse(examApiClient.get(`/exam-simulator/sessions/${sessionId}/state`))
  }

  enter(sessionId: string): Promise<ExamEnterResult> {
    return unwrapApiResponse(examApiClient.post(`/exam-simulator/sessions/${sessionId}/enter`))
  }

  getPaper(sessionId: string): Promise<ExamPaper> {
    return unwrapApiResponse(examApiClient.get(`/exam-simulator/sessions/${sessionId}/paper`))
  }

  getSessions(sessionId: string): Promise<ExamSessionSnapshot[]> {
    return unwrapApiResponse(examApiClient.get(`/exam-simulator/sessions/${sessionId}/sessions`))
  }

  saveAnswer(sessionId: string, subQuestionId: number, value: string, clientMutationId: string): Promise<void> {
    return unwrapApiResponse(examApiClient.put(`/exam-simulator/sessions/${sessionId}/answers/${subQuestionId}`, { value, clientMutationId }))
  }

  toggleMark(sessionId: string, questionId: number, marked: boolean, clientMutationId: string): Promise<void> {
    return unwrapApiResponse(examApiClient.put(`/exam-simulator/sessions/${sessionId}/marks/${questionId}`, { marked, clientMutationId }))
  }

  continueNextSubject(sessionId: string, clientMutationId: string): Promise<ExamSessionClockState> {
    return unwrapApiResponse(examApiClient.post(`/exam-simulator/sessions/${sessionId}/continue-next-subject`, { clientMutationId }))
  }

  async submit(sessionId: string, clientMutationId = '', mode: 'MANUAL' | 'FORCED' = 'MANUAL'): Promise<ExamSubmitResult> {
    const payload = await unwrapApiResponse<unknown>(examApiClient.post(`/exam-simulator/sessions/${sessionId}/submit`, { clientMutationId, mode }))
    if (!isSubmittedResult(payload)) {
      throw new ApiClientError('交卷结果不是服务端确认终态', undefined, 'INVALID_SUBMIT_RESULT')
    }
    return payload
  }

  getDemoContext(): DemoExamContext | null {
    return null
  }
}

function isSubmittedResult(value: unknown): value is ExamSubmitResult {
  if (!value || typeof value !== 'object') return false
  const candidate = value as { state?: unknown; submittedAt?: unknown }
  return (
    candidate.state === 'SUBMITTED' &&
    typeof candidate.submittedAt === 'string' &&
    Number.isFinite(Date.parse(candidate.submittedAt))
  )
}
