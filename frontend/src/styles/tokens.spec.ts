import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const tokensCss = readFileSync(resolve(__dirname, './tokens.css'), 'utf8')

describe('shared design tokens', () => {
  it('freezes the required A-palette core color tokens', () => {
    expect(tokensCss).toContain('--color-indigo-deep: #173B7A;')
    expect(tokensCss).toContain('--color-cobalt: #1769E0;')
    expect(tokensCss).toContain('--color-teal: #12A89D;')
    expect(tokensCss).toContain('--color-amber: #F59E0B;')
    expect(tokensCss).toContain('--color-coral: #EF5B5B;')
    expect(tokensCss).toContain('--color-cool-gray: #F4F7FB;')
    expect(tokensCss).toContain('--color-ink: #172033;')
  })

  it('maps the palette into semantic variables instead of leaving product colors hard-coded in components', () => {
    expect(tokensCss).toContain('--color-brand: var(--color-indigo-deep);')
    expect(tokensCss).toContain('--color-practice-accent: var(--color-cobalt);')
    expect(tokensCss).toContain('--color-essay-accent: var(--color-indigo-deep);')
    expect(tokensCss).toContain('--color-success: var(--color-teal);')
    expect(tokensCss).toContain('--color-warning: var(--color-amber);')
    expect(tokensCss).toContain('--color-danger: var(--color-coral);')
    expect(tokensCss).toContain('--color-page-canvas: var(--color-cool-gray);')
    expect(tokensCss).toContain('--color-text-primary: var(--color-ink);')
  })

  it('freezes one shared 72px header height without component hardcoding', () => {
    expect(tokensCss).toContain('--platform-header-height: 72px;')

    const platformStyles = readFileSync(resolve(__dirname, './platform.css'), 'utf8')
    expect(platformStyles).not.toMatch(/(?:height|min-height|top):\s*(?:64|74)px/)
    expect(platformStyles).toContain('height: var(--platform-header-height);')
    expect(platformStyles).toContain('min-height: calc(100vh - var(--platform-header-height));')
  })
})
