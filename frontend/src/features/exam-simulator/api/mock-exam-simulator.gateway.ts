import type {
  DemoExamContext,
  ExamCandidate,
  ExamEnterResult,
  ExamEntryCode,
  ExamLoginRequest,
  ExamLoginResult,
  ExamPaper,
  ExamRules,
  ExamSessionClockState,
  ExamSessionSnapshot,
  ExamSessionStage,
  ExamSubmitResult,
} from '../types'
import { createMockCandidate, createMockPaper, demoExamContext, getStartDelaySeconds, mockRules } from '../mock/scenario'
import { ExamSimulatorGatewayError, type ExamSimulatorGateway } from './exam-simulator.gateway'
import {
  isEighteenDigits,
  isSharedDemoAdmissionTicket,
  isSharedDemoIdentityNumber,
  isTwelveDigits,
} from '../config/shared-demo-credential'

const MOCK_BACKEND_STORAGE_KEY = 'exam-simulator-mock-backend-v1'
const EXAM_DURATION_SECONDS = 9_000

interface MockSessionRecord {
  sessionId: string
  entryCode: ExamEntryCode
  sessionToken: string
  candidateConfirmed: boolean
  rulesAcknowledged: boolean
  entered: boolean
  submittedAt: string | null
  scheduledStartAt: string
  deadlineAt: string
  sessionCode: string
  subjectCode: string
  subjectOrder: number
  subjectCount: number
  workspaceType: 'MIDDLE_UNION' | 'SENIOR_UNION' | 'ESSAY'
  subjectSubmitted: boolean
  sessionSubmitted: boolean
}

interface MockBackendState {
  sessions: Record<string, MockSessionRecord>
}

function readBackendState(): MockBackendState {
  const raw = sessionStorage.getItem(MOCK_BACKEND_STORAGE_KEY)
  if (!raw) return { sessions: {} }
  try {
    return JSON.parse(raw) as MockBackendState
  } catch {
    return { sessions: {} }
  }
}

function writeBackendState(state: MockBackendState): void {
  sessionStorage.setItem(MOCK_BACKEND_STORAGE_KEY, JSON.stringify(state))
}

function getRecord(sessionId: string): MockSessionRecord {
  const record = readBackendState().sessions[sessionId]
  if (!record) {
    throw new ExamSimulatorGatewayError('\u8003\u8bd5\u4f1a\u8bdd\u4e0d\u5b58\u5728\u6216\u5df2\u5931\u6548', 'SESSION_NOT_FOUND')
  }
  return record
}

function updateRecord(sessionId: string, patch: Partial<MockSessionRecord>): MockSessionRecord {
  const state = readBackendState()
  const current = state.sessions[sessionId]
  if (!current) {
    throw new ExamSimulatorGatewayError('\u8003\u8bd5\u4f1a\u8bdd\u4e0d\u5b58\u5728\u6216\u5df2\u5931\u6548', 'SESSION_NOT_FOUND')
  }
  const next = { ...current, ...patch }
  state.sessions[sessionId] = next
  writeBackendState(state)
  return next
}

function stageOf(record: MockSessionRecord): ExamSessionStage {
  if (record.submittedAt) return 'SUBMITTED'
  if (record.entered) return 'IN_PROGRESS'
  if (record.rulesAcknowledged) return 'WAITING_START'
  if (record.candidateConfirmed) return 'RULE_READING'
  return 'CANDIDATE_LOGGED_IN'
}

function randomId(): string {
  return `mock-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`
}

export function clearMockExamSimulatorState(): void {
  sessionStorage.removeItem(MOCK_BACKEND_STORAGE_KEY)
}

export class MockExamSimulatorGateway implements ExamSimulatorGateway {
  getDemoContext(): DemoExamContext {
    return demoExamContext
  }

  async login(request: ExamLoginRequest): Promise<ExamLoginResult> {
    if (!isTwelveDigits(request.admissionTicket) || !isEighteenDigits(request.identityNumber)) {
      throw new ExamSimulatorGatewayError(
        '\u6a21\u62df\u51ed\u8bc1\u683c\u5f0f\u4e0d\u6b63\u786e',
        'INVALID_CREDENTIAL_FORMAT',
      )
    }
    if (
      !isSharedDemoAdmissionTicket(request.admissionTicket) ||
      !isSharedDemoIdentityNumber(request.identityNumber)
    ) {
      throw new ExamSimulatorGatewayError(
        '\u6a21\u62df\u51c6\u8003\u8bc1\u53f7\u6216\u6a21\u62df\u8eab\u4efd\u53f7\u4e0d\u6b63\u786e',
        'INVALID_CREDENTIALS',
      )
    }

    const scheduledStart = Date.now() + getStartDelaySeconds() * 1_000
    const sessionId = randomId()
    const isMiddle = request.entryCode === 'BASIC_KNOWLEDGE' || request.entryCode === 'APPLICATION_TECH'
    const record: MockSessionRecord = {
      sessionId,
      entryCode: request.entryCode,
      sessionToken: `token-${sessionId}`,
      candidateConfirmed: false,
      rulesAcknowledged: false,
      entered: false,
      submittedAt: null,
      scheduledStartAt: new Date(scheduledStart).toISOString(),
      deadlineAt: new Date(scheduledStart + EXAM_DURATION_SECONDS * 1_000).toISOString(),
      sessionCode: request.entryCode === 'ESSAY' ? 'SENIOR_AFTERNOON_ESSAY' : (isMiddle ? `MIDDLE_${request.entryCode}` : 'SENIOR_MORNING_COMB'),
      subjectCode: request.entryCode === 'ESSAY' ? 'ESSAY_WRITING' : (isMiddle ? request.entryCode : 'COMPREHENSIVE_KNOWLEDGE'),
      subjectOrder: 1,
      subjectCount: request.entryCode === 'LINKED' ? 2 : 1,
      workspaceType: request.entryCode === 'ESSAY' ? 'ESSAY' : (isMiddle ? 'MIDDLE_UNION' : 'SENIOR_UNION'),
      subjectSubmitted: false,
      sessionSubmitted: false,
    }
    const state = readBackendState()
    state.sessions[sessionId] = record
    writeBackendState(state)
    return {
      sessionId,
      sessionToken: record.sessionToken,
      state: 'CANDIDATE_LOGGED_IN',
    }
  }

  async getCandidate(sessionId: string): Promise<ExamCandidate> {
    const record = getRecord(sessionId)
    const candidate = createMockCandidate(record.entryCode)
    const subject = record.entryCode === 'BASIC_KNOWLEDGE'
      ? '基础知识'
      : record.entryCode === 'APPLICATION_TECH'
        ? '应用技术'
        : undefined
    return subject ? { ...candidate, subject } : candidate
  }

  async confirmCandidate(sessionId: string): Promise<void> {
    updateRecord(sessionId, { candidateConfirmed: true })
  }

  async getRules(sessionId: string): Promise<ExamRules> {
    const record = getRecord(sessionId)
    if (!record.candidateConfirmed) {
      throw new ExamSimulatorGatewayError('\u8bf7\u5148\u786e\u8ba4\u8003\u751f\u8eab\u4efd', 'CANDIDATE_NOT_CONFIRMED')
    }
    return mockRules
  }

  async acknowledgeRules(sessionId: string): Promise<void> {
    const record = getRecord(sessionId)
    if (!record.candidateConfirmed) {
      throw new ExamSimulatorGatewayError('\u8bf7\u5148\u786e\u8ba4\u8003\u751f\u8eab\u4efd', 'CANDIDATE_NOT_CONFIRMED')
    }
    updateRecord(sessionId, { rulesAcknowledged: true })
  }

  async getState(sessionId: string): Promise<ExamSessionClockState> {
    const record = getRecord(sessionId)
    return {
      sessionId,
      state: stageOf(record),
      serverNow: new Date().toISOString(),
      scheduledStartAt: record.scheduledStartAt,
      actualStartAt: record.scheduledStartAt,
      deadlineAt: record.deadlineAt,
      durationSeconds: EXAM_DURATION_SECONDS,
      paperLoaded: record.entered,
      workspaceType: record.workspaceType,
      sessionCode: record.sessionCode,
      subjectCode: record.subjectCode,
      subjectOrder: record.subjectOrder,
      subjectCount: record.subjectCount,
      subjectSubmitted: record.subjectSubmitted,
      sessionSubmitted: record.sessionSubmitted,
      canContinue: record.subjectOrder < record.subjectCount,
    }
  }

  async enter(sessionId: string): Promise<ExamEnterResult> {
    const record = getRecord(sessionId)
    if (!record.candidateConfirmed) {
      throw new ExamSimulatorGatewayError('\u8bf7\u5148\u786e\u8ba4\u8003\u751f\u8eab\u4efd', 'CANDIDATE_NOT_CONFIRMED')
    }
    if (!record.rulesAcknowledged) {
      throw new ExamSimulatorGatewayError('\u8bf7\u5148\u9605\u8bfb\u5e76\u786e\u8ba4\u8003\u573a\u89c4\u5219', 'RULES_NOT_ACKNOWLEDGED')
    }
    if (Date.now() < Date.parse(record.scheduledStartAt)) {
      throw new ExamSimulatorGatewayError('\u8003\u8bd5\u5c1a\u672a\u5f00\u59cb', 'EXAM_NOT_STARTED')
    }
    const next = updateRecord(sessionId, { entered: true })
    return {
      state: 'IN_PROGRESS',
      enteredAt: new Date().toISOString(),
      deadlineAt: next.deadlineAt,
    }
  }

  async getPaper(sessionId: string): Promise<ExamPaper> {
    const record = getRecord(sessionId)
    if (!record.entered) {
      throw new ExamSimulatorGatewayError('\u5c1a\u672a\u8fdb\u5165\u8003\u8bd5', 'EXAM_NOT_ENTERED')
    }
    const paper = createMockPaper(record.entryCode)
    if (record.subjectCode === 'BASIC_KNOWLEDGE') {
      return {
        ...paper,
        subject: '基础知识',
        sessionCode: record.sessionCode,
        subjectCode: record.subjectCode,
        workspaceType: record.workspaceType,
      }
    }
    if (record.subjectCode === 'CASE_ANALYSIS' || record.subjectCode === 'APPLICATION_TECH') {
      return {
        ...paper,
        subject: record.subjectCode === 'APPLICATION_TECH' ? '应用技术' : '案例分析',
        sessionCode: record.sessionCode,
        subjectCode: record.subjectCode,
        workspaceType: record.workspaceType,
        questions: [{
          questionId: 50_001,
          subQuestionId: 60_001,
          displayNo: 1,
          questionType: 'ESSAY_WRITING',
          groupName: '案例分析',
          prompt: '请结合案例材料完成分析、计算并给出解决方案。',
          score: 25,
          options: [],
        }],
      }
    }
    return { ...paper, sessionCode: record.sessionCode, subjectCode: record.subjectCode, workspaceType: record.workspaceType }
  }

  async getSessions(sessionId: string): Promise<ExamSessionSnapshot[]> {
    const record = getRecord(sessionId)
    return [{
      sessionCode: record.sessionCode,
      sessionGroup: record.subjectCount > 1 ? 'COMBINED' : 'INDEPENDENT',
      workspaceType: record.workspaceType,
      totalDurationMinutes: EXAM_DURATION_SECONDS / 60,
      shareTotalCountdown: record.subjectCount > 1,
      independentFromOtherSessions: true,
      transitionPolicy: record.subjectCount > 1 ? 'ONE_WAY_ONLY' : 'NO_TRANSITION',
      subjects: [],
    }]
  }

  async saveAnswer(sessionId: string, _subQuestionId: number, _value: string, _mutationId: string): Promise<void> {
    const record = getRecord(sessionId)
    if (record.submittedAt) throw new ExamSimulatorGatewayError('考试已经交卷', 'EXAM_SESSION_COMPLETED')
  }

  async toggleMark(sessionId: string, _questionId: number, _marked: boolean, _mutationId: string): Promise<void> {
    const record = getRecord(sessionId)
    if (record.submittedAt) throw new ExamSimulatorGatewayError('考试已经交卷', 'EXAM_SESSION_COMPLETED')
  }

  async continueNextSubject(sessionId: string, _mutationId: string): Promise<ExamSessionClockState> {
    const record = getRecord(sessionId)
    if (record.subjectOrder >= record.subjectCount) throw new ExamSimulatorGatewayError('没有下一科目', 'NO_NEXT_SUBJECT')
      const nextSubject = record.subjectOrder + 1
    updateRecord(sessionId, {
      subjectOrder: nextSubject,
      subjectCode: record.workspaceType === 'MIDDLE_UNION' ? 'APPLICATION_TECH' : 'CASE_ANALYSIS',
      subjectSubmitted: false,
      submittedAt: null,
      sessionSubmitted: false,
    })
    return this.getState(sessionId)
  }

  async submit(sessionId: string, _mutationId = '', _mode: 'MANUAL' | 'FORCED' = 'MANUAL'): Promise<ExamSubmitResult> {
    const record = getRecord(sessionId)
    if (!record.entered) {
      throw new ExamSimulatorGatewayError('\u5c1a\u672a\u8fdb\u5165\u8003\u8bd5', 'EXAM_NOT_ENTERED')
    }
    if (record.submittedAt) {
      throw new ExamSimulatorGatewayError('\u8003\u8bd5\u5df2\u7ecf\u4ea4\u5377', 'EXAM_ALREADY_SUBMITTED')
    }
    const submittedAt = new Date().toISOString()
    updateRecord(sessionId, {
      submittedAt,
      subjectSubmitted: true,
      sessionSubmitted: record.subjectOrder >= record.subjectCount,
    })
    return {
      state: 'SUBMITTED',
      submittedAt,
    }
  }
}
