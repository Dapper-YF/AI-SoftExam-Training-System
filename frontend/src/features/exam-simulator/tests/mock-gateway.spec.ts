import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MockExamSimulatorGateway, clearMockExamSimulatorState } from '../api/mock-exam-simulator.gateway'
import {
  SHARED_DEMO_ADMISSION_TICKET,
  SHARED_DEMO_IDENTITY_NUMBER,
} from '../config/shared-demo-credential'

describe('MockExamSimulatorGateway', () => {
  beforeEach(() => {
    sessionStorage.clear()
    clearMockExamSimulatorState()
    vi.unstubAllEnvs()
  })

  it('creates a local simulated session with the shared demo credential', async () => {
    const gateway = new MockExamSimulatorGateway()

    await expect(
      gateway.login({
        entryCode: 'LINKED',
        admissionTicket: SHARED_DEMO_ADMISSION_TICKET,
        identityNumber: SHARED_DEMO_IDENTITY_NUMBER,
      }),
    ).resolves.toMatchObject({ state: 'CANDIDATE_LOGGED_IN' })
  })

  it('rejects credentials with invalid length or non-digits without creating a session', async () => {
    const gateway = new MockExamSimulatorGateway()

    await expect(
      gateway.login({
        entryCode: 'LINKED',
        admissionTicket: 'wrong',
        identityNumber: 'wrong',
      }),
    ).rejects.toMatchObject({ code: 'INVALID_CREDENTIAL_FORMAT' })
  })

  it('rejects other 12-digit and 18-digit values without creating a session', async () => {
    const gateway = new MockExamSimulatorGateway()

    await expect(
      gateway.login({
        entryCode: 'LINKED',
        admissionTicket: '222222222222',
        identityNumber: '222222222222222222',
      }),
    ).rejects.toMatchObject({ code: 'INVALID_CREDENTIALS' })
  })

  it('keeps the original scheduled start after gateway recreation', async () => {
    vi.stubEnv('VITE_EXAM_SIMULATOR_START_DELAY_SECONDS', '30')
    const firstGateway = new MockExamSimulatorGateway()
    const demo = firstGateway.getDemoContext()
    const login = await firstGateway.login({
      entryCode: 'LINKED',
      admissionTicket: demo.admissionTicket,
      identityNumber: demo.identityNumber,
    })
    const firstState = await firstGateway.getState(login.sessionId)

    const recreatedGateway = new MockExamSimulatorGateway()
    const restoredState = await recreatedGateway.getState(login.sessionId)

    expect(restoredState.scheduledStartAt).toBe(firstState.scheduledStartAt)
    expect(restoredState.deadlineAt).toBe(firstState.deadlineAt)
  })

  it('enforces candidate and rule confirmation before entering', async () => {
    vi.stubEnv('VITE_EXAM_SIMULATOR_START_DELAY_SECONDS', '0')
    const gateway = new MockExamSimulatorGateway()
    const demo = gateway.getDemoContext()
    const login = await gateway.login({
      entryCode: 'LINKED',
      admissionTicket: demo.admissionTicket,
      identityNumber: demo.identityNumber,
    })

    await expect(gateway.getRules(login.sessionId)).rejects.toMatchObject({
      code: 'CANDIDATE_NOT_CONFIRMED',
    })
    await gateway.confirmCandidate(login.sessionId)
    await expect(gateway.enter(login.sessionId)).rejects.toMatchObject({
      code: 'RULES_NOT_ACKNOWLEDGED',
    })
    await gateway.acknowledgeRules(login.sessionId)
    await expect(gateway.enter(login.sessionId)).resolves.toMatchObject({ state: 'IN_PROGRESS' })
  })

  it('keeps intermediate subjects as separate sessions while retaining senior union flow', async () => {
    vi.stubEnv('VITE_EXAM_SIMULATOR_START_DELAY_SECONDS', '0')
    const gateway = new MockExamSimulatorGateway()
    const demo = gateway.getDemoContext()

    const basicLogin = await gateway.login({
      entryCode: 'BASIC_KNOWLEDGE',
      admissionTicket: demo.admissionTicket,
      identityNumber: demo.identityNumber,
    })
    const basicState = await gateway.getState(basicLogin.sessionId)
    expect(basicState).toMatchObject({
      sessionCode: 'MIDDLE_BASIC_KNOWLEDGE',
      subjectCode: 'BASIC_KNOWLEDGE',
      subjectCount: 1,
      canContinue: false,
    })

    const applicationLogin = await gateway.login({
      entryCode: 'APPLICATION_TECH',
      admissionTicket: demo.admissionTicket,
      identityNumber: demo.identityNumber,
    })
    const applicationPaper = await gateway.getState(applicationLogin.sessionId)
    expect(applicationPaper).toMatchObject({
      sessionCode: 'MIDDLE_APPLICATION_TECH',
      subjectCode: 'APPLICATION_TECH',
      subjectCount: 1,
      canContinue: false,
    })

    const seniorLogin = await gateway.login({
      entryCode: 'LINKED',
      admissionTicket: demo.admissionTicket,
      identityNumber: demo.identityNumber,
    })
    const seniorState = await gateway.getState(seniorLogin.sessionId)
    expect(seniorState).toMatchObject({
      sessionCode: 'SENIOR_MORNING_COMB',
      subjectCode: 'COMPREHENSIVE_KNOWLEDGE',
      subjectCount: 2,
      canContinue: true,
    })
  })
})
