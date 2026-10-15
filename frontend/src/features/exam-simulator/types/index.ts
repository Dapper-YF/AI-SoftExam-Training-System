export type ExamEntryCode = string

export type ExamSessionStage =
  | 'CANDIDATE_LOGGED_IN'
  | 'CANDIDATE_CONFIRMED'
  | 'RULE_READING'
  | 'WAITING_START'
  | 'LOADING_PAPER'
  | 'IN_PROGRESS'
  | 'SUBMITTED'
  | 'SUBJECT_TRANSITION'
  | 'COMPLETED'

export interface DemoExamContext {
  stationNo: string
  admissionTicket: string
  identityNumber: string
  hint: string
}

export interface ExamLoginRequest {
  entryCode: ExamEntryCode
  qualificationCode?: string
  ruleVersion?: string
  admissionTicket: string
  identityNumber: string
}

export interface ExamLoginResult {
  sessionId: string
  sessionToken: string
  state: ExamSessionStage
}

export interface ExamCandidate {
  avatarUrl?: string
  maskedAdmissionTicket: string
  name: string
  gender: string
  qualification: string
  subject: string
  maskedIdentityNumber: string
  stationNo: string
}

export interface ExamRuleTab {
  code: 'RULES' | 'GUIDE'
  title: string
  paragraphs: string[]
}

export interface ExamRules {
  version: string
  region: string
  examYear: number
  requireScrollToEnd: boolean
  tabs: ExamRuleTab[]
}

export interface ExamSessionClockState {
  sessionId: string
  state: ExamSessionStage
  serverNow: string
  scheduledStartAt: string
  actualStartAt: string
  deadlineAt: string
  durationSeconds: number
  paperLoaded: boolean
  workspaceType?: ExamWorkspaceType
  sessionCode?: string
  subjectCode?: string
  subjectOrder?: number
  subjectCount?: number
  subjectSubmitted?: boolean
  sessionSubmitted?: boolean
  canContinue?: boolean
}

export type ExamWorkspaceType = 'MIDDLE_UNION' | 'SENIOR_UNION' | 'ESSAY'
export type ExamSessionGroup = 'COMBINED' | 'INDEPENDENT'

export interface ExamSubjectSnapshot {
  subjectCode: string
  subjectName: string
  subjectOrder: number
  subjectType: 'OBJECTIVE' | 'CASE' | 'ESSAY'
  maxDurationMinutes: number
  minWorkMinutesBeforeSubmit: number
  minRemainingTimeThreshold: number
  allowEarlySubmit: boolean
  fullScore: number
  questionCount?: number
  isUnverified?: boolean
}

export interface ExamSessionSnapshot {
  sessionCode: string
  sessionGroup: ExamSessionGroup
  workspaceType: ExamWorkspaceType
  totalDurationMinutes: number
  shareTotalCountdown: boolean
  independentFromOtherSessions: boolean
  transitionPolicy: 'ONE_WAY_ONLY' | 'NO_TRANSITION'
  subjects: ExamSubjectSnapshot[]
}

export interface ExamEnterResult {
  state: 'IN_PROGRESS'
  enteredAt: string
  deadlineAt: string
}

export interface ExamSubmitResult {
  state: 'SUBMITTED'
  submittedAt: string
}

export interface ExamChoiceOption {
  key: string
  content: string
}

export interface ExamQuestion {
  questionId: number
  subQuestionId: number
  displayNo: number
  questionType: 'SINGLE_CHOICE' | 'ESSAY_WRITING'
  groupName: string
  prompt: string
  score: number
  options: ExamChoiceOption[]
  wordLimit?: number
}

export interface ExamPaper {
  paperId: number
  title: string
  qualification: string
  subject: string
  instructions: string[]
  questions: ExamQuestion[]
  sessionCode?: string
  subjectCode?: string
  workspaceType?: ExamWorkspaceType
}

export interface PersistedExamClientState {
  sessionId: string | null
  sessionToken: string | null
  entryCode: ExamEntryCode | null
  candidateConfirmed: boolean
  rulesAcknowledged: boolean
  forceReminderAcknowledged: boolean
  candidate: ExamCandidate | null
  rules: ExamRules | null
  clockState: ExamSessionClockState | null
  paper: ExamPaper | null
  submittedAt: string | null
  currentQuestionIndex: number
  answers: Record<string, string>
  marks: number[]
  sessionCode?: string | null
  subjectCode?: string | null
  localAnswerVersions?: Record<string, number>
}
