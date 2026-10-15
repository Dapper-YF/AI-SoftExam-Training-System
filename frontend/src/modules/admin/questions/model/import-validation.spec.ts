import { describe, expect, it } from 'vitest'
import { validateQuestionImportFile } from './import-validation'

describe('question import file validation', () => {
  it('accepts a real question-import/1.0 JSON file', async () => {
    const file = new File(
      [JSON.stringify({ schemaVersion: 'question-import/1.0', qualificationCode: 'HIGH_PM', subjectCode: 'CASE', questions: [] })],
      'temporary-questions.json',
      { type: 'application/json' },
    )
    await expect(validateQuestionImportFile(file)).resolves.toBeNull()
  })

  it('rejects unsupported, malformed and oversized files before upload', async () => {
    await expect(validateQuestionImportFile(new File(['x'], 'questions.txt', { type: 'text/plain' }))).resolves.toContain('仅支持')
    await expect(validateQuestionImportFile(new File(['{'], 'questions.json', { type: 'application/json' }))).resolves.toContain('无法解析')
    await expect(validateQuestionImportFile(new File([new Uint8Array(10 * 1024 * 1024 + 1)], 'questions.xlsx', {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    }))).resolves.toContain('10MB')
  })
})
