import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

describe('vite dev proxy config', () => {
  it('keeps port 5173 as the strict default while allowing an isolated W1 port', () => {
    const source = readFileSync(join(process.cwd(), 'vite.config.ts'), 'utf8')
    const packageJson = readFileSync(join(process.cwd(), 'package.json'), 'utf8')

    expect(source).toContain("process.env.VITE_DEV_PORT ?? '5173'")
    expect(source).toMatch(/port:\s*devPort/)
    expect(source).toMatch(/strictPort:\s*true/)
    expect(packageJson).toContain('"dev": "vite --host 127.0.0.1"')
    expect(source).toContain("'/__runtime/commit'")
  })

  it('rewrites the dev proxy origin for backend same-origin csrf validation', () => {
    const source = readFileSync(join(process.cwd(), 'vite.config.ts'), 'utf8')

    expect(source).toContain("process.env.VITE_API_PROXY_TARGET ?? 'http://127.0.0.1:8000'")
    expect(source).toContain('origin: apiProxyOrigin')
    expect(source).toContain("'x-forwarded-proto': 'http'")
    expect(source).toMatch(/target:\s*apiProxyTarget/)
    expect(source).toMatch(/changeOrigin:\s*true/)
  })
})
