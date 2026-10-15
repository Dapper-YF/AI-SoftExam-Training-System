import { describe, expect, it } from 'vitest'
import type { Subject } from '@/modules/foundation/types'
import { resolveProductAvailability } from './home-state'

const practiceAndEssaySubjects: Subject[] = [
  {
    id: 'knowledge',
    qualificationId: 'qualification-high-ipmp',
    code: 'COMPREHENSIVE',
    name: '综合知识',
    type: 'COMPREHENSIVE',
    status: 'ENABLED',
  },
  {
    id: 'essay',
    qualificationId: 'qualification-high-ipmp',
    code: 'ESSAY',
    name: '论文',
    type: 'ESSAY',
    status: 'ENABLED',
  },
]

describe('module product availability', () => {
  it('opens practice for a V9 comprehensive subject', () => {
    expect(
      resolveProductAvailability('practice', {
        isAuthenticated: true,
        featureFlags: { 'practice.workspace': true },
        permissions: ['practice:workspace:view'],
        subjects: practiceAndEssaySubjects,
        essayPreviewEnabled: false,
      }),
    ).toMatchObject({
      state: 'available',
      canEnter: true,
    })
  })

  it('keeps production essay fail-closed when the HTTP capability is unavailable', () => {
    expect(
      resolveProductAvailability('essay', {
        isAuthenticated: true,
        featureFlags: { 'essay.workspace': false },
        permissions: ['essay:workspace:view'],
        subjects: practiceAndEssaySubjects,
        essayPreviewEnabled: false,
      }),
    ).toMatchObject({
      state: 'unavailable',
      canEnter: false,
      label: '服务不可用',
    })
  })

  it('opens a controlled development preview when the preview flag is enabled', () => {
    expect(
      resolveProductAvailability('essay', {
        isAuthenticated: true,
        featureFlags: { 'essay.workspace': false },
        permissions: ['essay:workspace:view'],
        subjects: practiceAndEssaySubjects,
        essayPreviewEnabled: true,
      }),
    ).toMatchObject({
      state: 'preview',
      canEnter: true,
      label: '开发预览',
    })
  })

  it('blocks a product when the current qualification does not grant the needed subject scope', () => {
    expect(
      resolveProductAvailability('essay', {
        isAuthenticated: true,
        featureFlags: { 'essay.workspace': true },
        permissions: ['essay:workspace:view'],
        subjects: practiceAndEssaySubjects.filter((subject) => subject.type !== 'ESSAY'),
        essayPreviewEnabled: false,
      }),
    ).toMatchObject({
      state: 'forbidden',
      canEnter: false,
    })
  })

  it('opens production essay only when capability, permission, and subject scope are all present', () => {
    expect(resolveProductAvailability('essay', {
      isAuthenticated: true,
      featureFlags: { 'essay.workspace': true },
      permissions: ['essay:workspace:view'],
      subjects: practiceAndEssaySubjects,
      essayPreviewEnabled: false,
    })).toEqual({ state: 'available', canEnter: true, label: '进入工作区' })

    expect(resolveProductAvailability('essay', {
      isAuthenticated: true,
      featureFlags: { 'essay.workspace': true },
      permissions: [],
      subjects: practiceAndEssaySubjects,
      essayPreviewEnabled: false,
    })).toMatchObject({ state: 'forbidden', canEnter: false })
  })

  it('allows anonymous visitors to browse practice without granting personal features', () => {
    expect(resolveProductAvailability('practice', {
      isAuthenticated: false,
      featureFlags: {},
      permissions: [],
      subjects: practiceAndEssaySubjects,
      essayPreviewEnabled: false,
    })).toEqual({ state: 'available', canEnter: true, label: '浏览工作区' })

    expect(resolveProductAvailability('essay', {
      isAuthenticated: false,
      featureFlags: {},
      permissions: [],
      subjects: practiceAndEssaySubjects,
      essayPreviewEnabled: false,
    })).toEqual({ state: 'forbidden', canEnter: false, label: '登录后使用' })
  })
})
