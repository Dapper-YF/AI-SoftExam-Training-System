export type PlatformRole =
  | 'STUDENT'
  | 'ADMIN'
  | 'QUESTION_AUTHOR'
  | 'QUESTION_REVIEWER'
  | 'QUESTION_PUBLISHER'
export type EntityStatus = 'ENABLED' | 'DISABLED' | 'LOCKED'
export type LevelCode = 'ADVANCED' | 'INTERMEDIATE' | 'PRIMARY'
export type SubjectType = 'COMPREHENSIVE' | 'CASE' | 'ESSAY' | 'APPLICATION'
export type QualificationUnavailableReason =
  | 'UNVERIFIED'
  | 'VERIFICATION_EXPIRED'
  | 'QUALIFICATION_DISABLED'
  | 'NO_PUBLISHED_RULE'
  | 'OUTSIDE_EFFECTIVE_RANGE'

export interface PlatformUser {
  id: string
  username: string
  displayName: string
  maskedMobile?: string | null
  status: EntityStatus
}

export interface PlatformUserProfile {
  userId: string
  username: string
  displayName: string
  maskedMobile?: string | null
  maskedEmail?: string | null
  preparationQualificationId?: string | null
  preparationQualificationName?: string | null
  examRegionCode?: string | null
  examRegionName?: string | null
  status: EntityStatus
  version: number
  updatedAt: string
}

export interface PlatformUserProfileUpdateRequest {
  displayName: string
  preparationQualificationId?: string | null
  examRegionCode?: string | null
  version: number
}

export interface PlatformMenu {
  code: string
  name: string
  routePath: string
  icon?: string
}

export interface PlatformAuthContext {
  user: PlatformUser
  roles: PlatformRole[]
  permissions: string[]
  menus: PlatformMenu[]
  featureFlags: Record<string, boolean>
  accessExpiresAt?: string
  refreshExpiresAt?: string
}

export interface PlatformLoginRequest {
  identifier: string
  password: string
}

export interface PlatformSmsLoginChallengeRequest {
  mobile: string
  requestId: string
  role?: 'ADMIN'
}

export interface PlatformSmsLoginRequest {
  mobile: string
  challengeId: string
  verificationCode: string
  requestId: string
}

export type PlatformLoginResult = PlatformAuthContext

export interface PlatformSessionExpiry {
  accessExpiresAt: string
  refreshExpiresAt: string
}

export interface PlatformRegistrationChallengeRequest {
  phone: string
  requestId: string
}

export interface PlatformRegistrationChallenge {
  challengeId: string
  expiresAt: string
  retryAfterSeconds?: number
}

export interface PlatformRegisterRequest {
  nickname: string
  password: string
  phone: string
  email?: string
  challengeId?: string
  verificationCode?: string
  requestId: string
}

export interface PlatformRegistrationResult {
  userId: string
  username: string
  registrationStatus: 'REGISTERED'
  createdAt: string
}

export interface PlatformPasswordResetChallengeRequest {
  identifier: string
  channel: 'SMS' | 'EMAIL'
  requestId: string
}

export interface PlatformPasswordResetRequest {
  challengeId: string
  verificationCode: string
  newPassword: string
  requestId: string
}

export interface PlatformAuthCapabilities {
  usernamePassword: boolean
  mobilePassword: boolean
  emailPassword: boolean
  wechatQr: boolean
  registrationSms: boolean
  registrationVerificationRequired: boolean
  passwordResetSms: boolean
  smsLogin?: boolean
  passwordResetEmail?: boolean
  devSeedUsernameLoginEnabled?: boolean
  adminRegistrationEnabled?: boolean
  credentialPolicy?: {
    nickname: {
      minLength: number
      maxLength: number
    }
    password: {
      minLength: number
      maxLength: number
    }
  }
}

export interface PlatformDemoAccount {
  role: PlatformRole
  identifier: string
  password: string
  displayName: string
}

export interface Qualification {
  id: string
  code: string
  name: string
  levelCode: LevelCode
  status: EntityStatus
  sortOrder?: number
  available?: boolean
  unavailableReason?: QualificationUnavailableReason
}

export interface Subject {
  id: string
  qualificationId: string
  code: string
  name: string
  type: SubjectType
  status: EntityStatus
  sortOrder?: number
}

export interface FoundationKnowledgeNode {
  id: string
  parentId?: string
  code: string
  name: string
  levelNo: number
  children: FoundationKnowledgeNode[]
}

export type ExamDeadlinePolicy = 'INDEPENDENT_SUBJECTS' | 'SHARED_TOTAL_DEADLINE'
export type ExamFeedbackPolicy = 'AFTER_QUESTION' | 'AFTER_ATTEMPT' | 'AFTER_EXAM'
export type ExamQuestionType = 'SINGLE_CHOICE' | 'MULTI_CHOICE' | 'JUDGE' | 'CASE' | 'ESSAY'

export interface ExamRuleSubject {
  subjectCode: string
  subjectType: SubjectType
  displayOrder: number
  durationMinutes: number
  fullScore: number
  passScore: number
  questionTypes: ExamQuestionType[]
  earliestSubmitMinutes: number
  feedbackPolicy: ExamFeedbackPolicy
  linkedGroup?: string
  sequence?: number
  forceSubmitMinutes?: number
}

export interface ActiveExamRule {
  ruleId: string
  ruleCode: string
  ruleVersion: string
  schemaVersion: string
  qualificationId: string
  periodCode: string
  effectiveFrom: string
  effectiveTo: string
  totalDurationMinutes: number
  deadlinePolicy: ExamDeadlinePolicy
  sharedForceSubmitMinutes?: number
  subjects: ExamRuleSubject[]
  sourceVerifiedAt: string
}
