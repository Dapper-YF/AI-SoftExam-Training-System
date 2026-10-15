import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import AdminQuestionEditor from '../components/AdminQuestionEditor.vue'
import { createBlankQuestionForm } from '../model/question-form'
import type { AdminQuestionType } from '../types'

function mountEditor(type: AdminQuestionType) {
  return mount(AdminQuestionEditor, {
    props: { modelValue: createBlankQuestionForm(type) },
    global: { plugins: [ElementPlus] },
  })
}

describe('AdminQuestionEditor', () => {
  it.each([
    ['SINGLE_CHOICE', 'choice-editor'],
    ['MULTI_CHOICE', 'choice-editor'],
    ['JUDGE', 'judge-editor'],
    ['CASE', 'case-editor'],
    ['ESSAY', 'essay-editor'],
  ] as const)('renders the dedicated %s editor', (type, testId) => {
    const wrapper = mountEditor(type)
    expect(wrapper.get(`[data-testid="${testId}"]`)).toBeTruthy()
  })

  it('does not render option controls for judge', () => {
    const wrapper = mountEditor('JUDGE')
    expect(wrapper.find('[data-testid="choice-options"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="judge-answer"]').text()).toContain('正确')
    expect(wrapper.get('[data-testid="judge-answer"]').text()).toContain('错误')
  })

  it('shows complete case and essay previews', () => {
    const caseForm = createBlankQuestionForm('CASE')
    caseForm.caseMaterial = '真实案例材料'
    caseForm.caseSubQuestions[0]!.content = '子题一'
    const caseWrapper = mount(AdminQuestionEditor, { props: { modelValue: caseForm }, global: { plugins: [ElementPlus] } })
    expect(caseWrapper.get('[data-testid="case-preview"]').text()).toContain('真实案例材料')
    expect(caseWrapper.get('[data-testid="case-preview"]').text()).toContain('子题一')

    const essayForm = createBlankQuestionForm('ESSAY')
    essayForm.title = '范围管理论文'
    const essayWrapper = mount(AdminQuestionEditor, { props: { modelValue: essayForm }, global: { plugins: [ElementPlus] } })
    expect(essayWrapper.get('[data-testid="essay-preview"]').text()).toContain('范围管理论文')
    expect(essayWrapper.get('[data-testid="essay-preview"]').text()).toContain('800—1200')
  })
})
