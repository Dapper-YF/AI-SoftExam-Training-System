import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { setPlatformAuthGatewayForTests } from '@/modules/foundation/api/gateway'
import { setPlatformHomeGatewayForTests } from '@/modules/platform/api/gateway'
import type { PlatformHomeGateway } from '@/modules/platform/api/platform-home.gateway'
import type { PlatformAuthGateway } from '@/modules/foundation/api/platform-auth.gateway'
import type { PlatformUserProfile } from '@/modules/foundation/types'
import { useAuthStore } from '@/stores/auth'
import UserProfileView from './UserProfileView.vue'

const profile: PlatformUserProfile = {
  userId: '1001',
  username: 'student',
  displayName: 'Student One',
  maskedMobile: '+86139****2001',
  maskedEmail: 's***@aiexam.local',
  status: 'ENABLED',
  version: 7,
  updatedAt: '2026-07-19T00:00:00Z',
}

function gateway(): PlatformAuthGateway {
  return {
    getCapabilities: vi.fn(),
    requestRegistrationChallenge: vi.fn(),
    register: vi.fn(),
    login: vi.fn(),
    refresh: vi.fn(),
    logout: vi.fn(),
    getCurrentUser: vi.fn(),
    getProfile: vi.fn(async () => structuredClone(profile)),
    updateProfile: vi.fn(async (request) => ({
      ...structuredClone(profile),
      displayName: request.displayName,
      version: request.version + 1,
      updatedAt: '2026-07-19T00:01:00Z',
    })),
    requestPasswordResetChallenge: vi.fn(),
    resetPassword: vi.fn(),
    getDemoAccounts: vi.fn(() => []),
  } as unknown as PlatformAuthGateway
}

describe('UserProfileView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    const authStore = useAuthStore()
    authStore.user = { id: '1001', username: 'student', displayName: 'Student One', status: 'ENABLED' }
    authStore.roles = ['STUDENT']
    authStore.permissions = ['student:home:view']
    setPlatformHomeGatewayForTests({
      getModuleHomeSnapshot: vi.fn(async () => ({
        recentTasks: [],
        stats: [{ id: 'practice-count', label: '累计练习', value: '12' }],
      })),
    } as unknown as PlatformHomeGateway)
  })

  it('loads masked profile fields and saves display name with row version', async () => {
    const authGateway = gateway()
    setPlatformAuthGatewayForTests(authGateway)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/app/profile', component: UserProfileView },
        { path: '/forgot-password', component: { template: '<div />' } },
      ],
    })
    await router.push('/app/profile')
    await router.isReady()
    const wrapper = mount(UserProfileView, { global: { plugins: [ElementPlus, router] } })
    await flushPromises()

    expect(wrapper.get('[data-testid="profile-summary"]').text()).toContain('Student One')
    expect(wrapper.text()).toContain('+86139****2001')
    expect(wrapper.text()).toContain('s***@aiexam.local')

    await wrapper.get('[data-testid="profile-display-name"]').setValue('Student Prime')
    await wrapper.get('[data-testid="profile-save"]').trigger('click')
    await flushPromises()

    expect(authGateway.updateProfile).toHaveBeenCalledWith({ displayName: 'Student Prime', version: 7 })
    expect(useAuthStore().user?.displayName).toBe('Student Prime')
    expect(wrapper.text()).toContain('8')
  })
})
