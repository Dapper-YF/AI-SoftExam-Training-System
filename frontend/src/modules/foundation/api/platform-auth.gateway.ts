import type {
  PlatformAuthContext,
  PlatformAuthCapabilities,
  PlatformDemoAccount,
  PlatformLoginRequest,
  PlatformLoginResult,
  PlatformPasswordResetChallengeRequest,
  PlatformPasswordResetRequest,
  PlatformRegistrationChallenge,
  PlatformRegistrationChallengeRequest,
  PlatformRegisterRequest,
  PlatformRegistrationResult,
  PlatformSessionExpiry,
  PlatformSmsLoginChallengeRequest,
  PlatformSmsLoginRequest,
  PlatformUserProfile,
  PlatformUserProfileUpdateRequest,
} from '../types'

export interface PlatformAuthGateway {
  getCapabilities(): Promise<PlatformAuthCapabilities>
  requestRegistrationChallenge(request: PlatformRegistrationChallengeRequest): Promise<PlatformRegistrationChallenge>
  register(request: PlatformRegisterRequest): Promise<PlatformRegistrationResult>
  login(request: PlatformLoginRequest): Promise<PlatformLoginResult>
  requestSmsLoginChallenge?(request: PlatformSmsLoginChallengeRequest): Promise<PlatformRegistrationChallenge>
  smsLogin?(request: PlatformSmsLoginRequest): Promise<PlatformLoginResult>
  refresh(): Promise<PlatformSessionExpiry>
  logout(): Promise<void>
  getCurrentUser(): Promise<PlatformAuthContext>
  getProfile(): Promise<PlatformUserProfile>
  updateProfile(request: PlatformUserProfileUpdateRequest): Promise<PlatformUserProfile>
  requestPasswordResetChallenge(request: PlatformPasswordResetChallengeRequest): Promise<PlatformRegistrationChallenge>
  resetPassword(request: PlatformPasswordResetRequest): Promise<void>
  getDemoAccounts(): PlatformDemoAccount[]
}
