import ElementPlus from 'element-plus'
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import DevVerificationCodePanel from './DevVerificationCodePanel.vue'

describe('DevVerificationCodePanel', () => {
  it('shows a locally delivered code and emits it for one-click fill', async () => {
    const wrapper = mount(DevVerificationCodePanel, {
      props: {
        kind: 'register',
        verificationCode: '864209',
      },
      global: { plugins: [ElementPlus] },
    })

    expect(wrapper.get('[data-testid="register-dev-code"]').text()).toContain('864209')
    await wrapper.get('[data-testid="fill-register-dev-code"]').trigger('click')
    expect(wrapper.emitted('fill')).toEqual([['864209']])
  })
})
