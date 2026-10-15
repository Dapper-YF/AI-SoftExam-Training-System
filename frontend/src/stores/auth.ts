import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { ApiClientError } from '@/api/client'
import { sanitizePlatformMenus } from '@/app/router/platform-route-whitelist'
import { getPlatformAuthGateway } from '@/modules/foundation/api/gateway'
import { usePlatformHomeStore } from '@/modules/platform/stores/platform-home'
import { usePracticeStore } from '@/stores/practice'
import { useQualificationStore } from '@/stores/qualification'
import type {
  PlatformAuthContext,
  PlatformAuthCapabilities,
  PlatformDemoAccount,
  PlatformLoginRequest,
  PlatformMenu,
  PlatformPasswordResetChallengeRequest,
  PlatformPasswordResetRequest,
  PlatformRegistrationChallenge,
  PlatformRegistrationChallengeRequest,
  PlatformRegisterRequest,
  PlatformRegistrationResult,
  PlatformRole,
  PlatformSmsLoginChallengeRequest,
  PlatformSmsLoginRequest,
  PlatformUser,
} from '@/modules/foundation/types'

export const PLATFORM_AUTH_STORAGE_KEY = 'platform-auth-context-v1'

interface PersistedAuthContext {
  user: PlatformUser
  roles: PlatformRole[]
  permissions: string[]
  menus: PlatformMenu[]
  featureFlags: Record<string, boolean>
}

export const useAuthStore = defineStore('platform-auth', () => {
  const user = ref<PlatformUser | null>(null)
  const roles = ref<PlatformRole[]>([])
  const permissions = ref<string[]>([])
  const menus = ref<PlatformMenu[]>([])
  const featureFlags = ref<Record<string, boolean>>({})
  const initialized = ref(false)
  const loading = ref(false)
  const capabilities = ref<PlatformAuthCapabilities | null>(null)
  const preparationCompleted = ref(true)
  let capabilitiesRequest: Promise<PlatformAuthCapabilities> | undefined
  let initializationRequest: Promise<void> | undefined
  let initializationGeneration = 0
  let activeInitializationGeneration = 0
  let sessionEpoch = 0
  let loadingOperations = 0

  const isAuthenticated = computed(() => Boolean(user.value))
  const homePath = computed(() => {
    if (roles.value.includes('ADMIN') && permissions.value.includes('admin:home:view')) {
      return '/admin/home'
    }
    if (permissions.value.includes('question:read')) return '/admin/questions'
    return '/app/modules'
  })
  const demoAccounts = computed<PlatformDemoAccount[]>(() =>
    getPlatformAuthGateway().getDemoAccounts(),
  )

  function persist(): void {
    if (!user.value) return
    const payload: PersistedAuthContext = {
      user: user.value,
      roles: roles.value,
      permissions: permissions.value,
      menus: menus.value,
      featureFlags: featureFlags.value,
    }
    localStorage.setItem(PLATFORM_AUTH_STORAGE_KEY, JSON.stringify(payload))
  }

  function applyContext(context: PlatformAuthContext): void {
    user.value = context.user
    roles.value = [...context.roles]
    permissions.value = [...context.permissions]
    featureFlags.value = { ...context.featureFlags }
    menus.value = sanitizePlatformMenus(context.menus, {
      isAuthenticated: true,
      roles: roles.value,
      permissions: permissions.value,
      featureFlags: featureFlags.value,
    })
    persist()
  }

  function beginLoading(): void {
    loadingOperations += 1
    loading.value = true
  }

  function endLoading(): void {
    loadingOperations = Math.max(0, loadingOperations - 1)
    loading.value = loadingOperations > 0
  }

  function clearSession(): void {
    sessionEpoch += 1
    localStorage.removeItem(PLATFORM_AUTH_STORAGE_KEY)
    user.value = null
    roles.value = []
    permissions.value = []
    menus.value = []
    featureFlags.value = {}
    usePlatformHomeStore().reset()
    usePracticeStore().resetPersonalState()
    useQualificationStore().resetSessionState()
  }

  async function login(request: PlatformLoginRequest): Promise<void> {
    const loginEpoch = ++sessionEpoch
    useQualificationStore().resetSessionState()
    beginLoading()
    try {
      const result = await getPlatformAuthGateway().login(request)
      if (loginEpoch !== sessionEpoch) return
      applyContext(result)
      await refreshPreparationStatus()
      initialized.value = true
    } finally {
      endLoading()
    }
  }

  function requestSmsLoginChallenge(
    request: PlatformSmsLoginChallengeRequest,
  ): Promise<PlatformRegistrationChallenge> {
    const gateway = getPlatformAuthGateway()
    if (!gateway.requestSmsLoginChallenge) throw new Error('短信登录服务暂不可用')
    return gateway.requestSmsLoginChallenge(request)
  }

  async function smsLogin(request: PlatformSmsLoginRequest): Promise<void> {
    const gateway = getPlatformAuthGateway()
    if (!gateway.smsLogin) throw new Error('短信登录服务暂不可用')
    const loginEpoch = ++sessionEpoch
    useQualificationStore().resetSessionState()
    beginLoading()
    try {
      const result = await gateway.smsLogin(request)
      if (loginEpoch !== sessionEpoch) return
      applyContext(result)
      await refreshPreparationStatus()
      initialized.value = true
    } finally {
      endLoading()
    }
  }

  async function runInitialization(epoch: number): Promise<void> {
    let context: PlatformAuthContext
    try {
      try {
        context = await getPlatformAuthGateway().getCurrentUser()
      } catch (error) {
        if (epoch !== sessionEpoch) return
        if (!(error instanceof ApiClientError) || error.status !== 401) throw error
        try {
          await getPlatformAuthGateway().refresh()
          if (epoch !== sessionEpoch) return
          context = await getPlatformAuthGateway().getCurrentUser()
        } catch (refreshError) {
          if (epoch !== sessionEpoch) return
          if (!(refreshError instanceof ApiClientError) || refreshError.status !== 401) {
            throw refreshError
          }
          clearSession()
          initialized.value = true
          return
        }
      }

      if (epoch !== sessionEpoch) return
      applyContext(context)
      await refreshPreparationStatus()
      initialized.value = true
    } finally {
      endLoading()
    }
  }

  function initialize(options: { force?: boolean } = {}): Promise<void> {
    if (initialized.value && !options.force) return Promise.resolve()
    if (initializationRequest) return initializationRequest

    const epoch = sessionEpoch
    const generation = ++initializationGeneration
    activeInitializationGeneration = generation
    beginLoading()
    initializationRequest = runInitialization(epoch).finally(() => {
      if (activeInitializationGeneration === generation) {
        initializationRequest = undefined
      }
    })
    return initializationRequest
  }

  async function logout(): Promise<void> {
    sessionEpoch += 1
    try {
      await getPlatformAuthGateway().logout()
    } finally {
      clearSession()
      initialized.value = true
    }
  }

  function hasRole(role: PlatformRole): boolean {
    return roles.value.includes(role)
  }

  function updateDisplayName(displayName: string): void {
    if (!user.value) return
    user.value.displayName = displayName
    persist()
  }

  async function refreshPreparationStatus(): Promise<void> {
    const privileged = roles.value.some((role) => ['ADMIN', 'QUESTION_AUTHOR', 'QUESTION_REVIEWER', 'QUESTION_PUBLISHER'].includes(role))
    if (!user.value || !roles.value.includes('STUDENT') || privileged) {
      preparationCompleted.value = true
      return
    }
    const profile = await getPlatformAuthGateway().getProfile()
    preparationCompleted.value = Boolean(profile.preparationQualificationId && profile.examRegionCode)
  }

  function hasPermission(permission: string): boolean {
    return permissions.value.includes(permission)
  }

  function isFeatureEnabled(featureFlag: string): boolean {
    return featureFlags.value[featureFlag] === true
  }

  async function loadCapabilities(): Promise<PlatformAuthCapabilities> {
    if (capabilities.value) return capabilities.value
    if (!capabilitiesRequest) {
      const request = getPlatformAuthGateway().getCapabilities()
        .then((result) => {
          capabilities.value = result
          return result
        })
        .finally(() => {
          if (capabilitiesRequest === request) capabilitiesRequest = undefined
        })
      capabilitiesRequest = request
    }
    return capabilitiesRequest
  }

  function requestRegistrationChallenge(request: PlatformRegistrationChallengeRequest): Promise<PlatformRegistrationChallenge> {
    return getPlatformAuthGateway().requestRegistrationChallenge(request)
  }

  function register(request: PlatformRegisterRequest): Promise<PlatformRegistrationResult> {
    return getPlatformAuthGateway().register(request)
  }

  function requestPasswordResetChallenge(request: PlatformPasswordResetChallengeRequest): Promise<PlatformRegistrationChallenge> {
    return getPlatformAuthGateway().requestPasswordResetChallenge(request)
  }

  function resetPassword(request: PlatformPasswordResetRequest): Promise<void> {
    return getPlatformAuthGateway().resetPassword(request)
  }

  return {
    user,
    roles,
    permissions,
    menus,
    featureFlags,
    capabilities,
    preparationCompleted,
    initialized,
    loading,
    isAuthenticated,
    homePath,
    demoAccounts,
    login,
    requestSmsLoginChallenge,
    smsLogin,
    loadCapabilities,
    requestRegistrationChallenge,
    register,
    requestPasswordResetChallenge,
    resetPassword,
    initialize,
    refreshPreparationStatus,
    logout,
    clearSession,
    updateDisplayName,
    hasRole,
    hasPermission,
    isFeatureEnabled,
  }
})
