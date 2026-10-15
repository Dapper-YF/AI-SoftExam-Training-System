import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import QuestionRichContent from '../components/QuestionRichContent.vue'

describe('QuestionRichContent', () => {
  it('renders safe question image markers through the public asset endpoint', () => {
    const wrapper = mount(QuestionRichContent, {
      props: { content: '请阅读图示。\n[图片:7de8ec1675d24fd8.png]' },
    })

    expect(wrapper.text()).toContain('请阅读图示。')
    expect(wrapper.get('img').attributes('src')).toBe('/api/v1/public/question-assets/7de8ec1675d24fd8.png')
  })

  it('keeps unsafe image markers as text', () => {
    const wrapper = mount(QuestionRichContent, {
      props: { content: '[图片:../../secret.png]' },
    })

    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.text()).toContain('[图片:../../secret.png]')
  })
})
