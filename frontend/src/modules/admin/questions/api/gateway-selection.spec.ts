import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { resolveAdminQuestionGatewayKind } from './gateway'

describe('admin question gateway selection', () => {
  it.each([
    [undefined, 'development'],
    ['false', 'development'],
    ['true', 'development'],
    ['true', 'production'],
  ] as const)('always uses the real HTTP gateway for %s in %s', (value, mode) => {
    expect(resolveAdminQuestionGatewayKind(value, mode)).toBe('http')
  })

  it('does not statically import a question fixture gateway into production', () => {
    const source = readFileSync(join(process.cwd(), 'src/modules/admin/questions/api/gateway.ts'), 'utf8')
    expect(source).not.toContain('mock-admin-question.gateway')
    expect(source).not.toContain('MockAdminQuestionGateway')
  })
})
