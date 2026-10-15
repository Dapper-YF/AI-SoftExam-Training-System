import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { EssayEditorData } from './types'
import {
  EssayAutosaveController,
  EssaySaveConflictError,
  type EssaySaveRequest,
  type EssaySaveResult,
  type EssayServerSnapshot,
} from './autosave-controller'

interface Deferred<T> {
  promise: Promise<T>
  resolve: (value: T) => void
  reject: (reason: unknown) => void
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((accept, decline) => { resolve = accept; reject = decline })
  return { promise, resolve, reject }
}

function draft(text: string, version = 12): EssayEditorData {
  return {
    id: 'draft-1', title: '竞态测试论文', version, qualification: '高级项目经理', topic: '项目管理',
    outline: [{ id: 'o-1', label: '第一章', words: text.length, level: 0 }], background: '测试背景', materials: [], suggestions: [],
    paragraphs: [{ id: 'p-1', outlineId: 'o-1', text }],
  }
}

function stored(key = 'essay-local-draft-1'): { revision: number; draft: EssayEditorData } | null {
  const raw = sessionStorage.getItem(key)
  return raw ? JSON.parse(raw) : null
}

beforeEach(() => sessionStorage.clear())

describe('EssayAutosaveController single-flight queue', () => {
  it('coalesces continuous edits into one next request while keeping one save in flight', async () => {
    const pending = [deferred<EssaySaveResult>(), deferred<EssaySaveResult>()]
    const calls: EssaySaveRequest[] = []
    let active = 0
    let maxActive = 0
    const controller = new EssayAutosaveController({
      storage: sessionStorage, storageKey: 'essay-local-draft-1', initialDraft: draft('初始'),
      save: (request) => {
        calls.push(structuredClone(request))
        active += 1
        maxActive = Math.max(maxActive, active)
        return pending[calls.length - 1]!.promise.finally(() => { active -= 1 })
      },
    })

    controller.capture(draft('第一版'))
    controller.flush()
    controller.capture(draft('第二版'))
    controller.flush()
    controller.capture(draft('第三版'))
    controller.flush()

    expect(calls).toHaveLength(1)
    expect(stored()?.revision).toBe(3)
    pending[0]!.resolve({ confirmedRevision: 1, baseVersion: 13, updatedAt: '2026-07-12T21:00:00Z' })
    await pending[0]!.promise
    await vi.waitFor(() => expect(calls).toHaveLength(2))
    expect(calls[1]!.draft.paragraphs[0]!.text).toBe('第三版')
    expect(calls[1]!.localRevision).toBe(3)
    expect(maxActive).toBe(1)

    pending[1]!.resolve({ confirmedRevision: 3, baseVersion: 14, updatedAt: '2026-07-12T21:01:00Z' })
    await pending[1]!.promise
    await vi.waitFor(() => expect(controller.state.status).toBe('saved'))
    expect(sessionStorage.getItem('essay-local-draft-1')).toBeNull()
  })

  it('keeps the latest revision when an old success is followed by a new failure', async () => {
    const oldSave = deferred<EssaySaveResult>()
    const newSave = deferred<EssaySaveResult>()
    const calls: EssaySaveRequest[] = []
    const controller = new EssayAutosaveController({
      storage: sessionStorage, storageKey: 'essay-local-draft-1', initialDraft: draft('初始'),
      save: (request) => { calls.push(structuredClone(request)); return calls.length === 1 ? oldSave.promise : newSave.promise },
    })

    controller.capture(draft('旧请求正文'))
    controller.flush()
    controller.capture(draft('最新正文'))
    controller.flush()
    oldSave.resolve({ confirmedRevision: 1, baseVersion: 13, updatedAt: '2026-07-12T21:00:00Z' })
    await oldSave.promise
    await vi.waitFor(() => expect(calls).toHaveLength(2))
    expect(stored()?.draft.paragraphs[0]?.text).toBe('最新正文')

    newSave.reject(new Error('network failed'))
    await expect(newSave.promise).rejects.toThrow('network failed')
    await vi.waitFor(() => expect(controller.state.status).toBe('failed'))
    expect(controller.state.canRetry).toBe(true)
    expect(stored()?.revision).toBe(2)
    expect(stored()?.draft.paragraphs[0]?.text).toBe('最新正文')
  })

  it('does not clear a newer local revision when an older response arrives late', async () => {
    const oldSave = deferred<EssaySaveResult>()
    const controller = new EssayAutosaveController({
      storage: sessionStorage, storageKey: 'essay-local-draft-1', initialDraft: draft('初始'),
      save: () => oldSave.promise,
    })
    controller.capture(draft('旧正文'))
    controller.flush()
    controller.capture(draft('新正文'))
    oldSave.resolve({ confirmedRevision: 1, baseVersion: 13, updatedAt: '2026-07-12T21:00:00Z' })
    await oldSave.promise
    await vi.waitFor(() => expect(controller.state.status).toBe('dirty'))
    expect(stored()?.revision).toBe(2)
    expect(stored()?.draft.paragraphs[0]?.text).toBe('新正文')
  })

  it('retains the local copy when HTTP cannot confirm the client revision', async () => {
    const controller = new EssayAutosaveController({
      storage: sessionStorage, storageKey: 'essay-local-draft-1', initialDraft: draft('初始'),
      save: async () => ({ baseVersion: 12, updatedAt: '2026-07-12T21:00:00Z' }),
    })
    controller.capture(draft('未确认正文'))
    controller.flush()
    await vi.waitFor(() => expect(controller.state.status).toBe('unconfirmed'))
    expect(stored()?.draft.paragraphs[0]?.text).toBe('未确认正文')
  })
})

describe('EssayAutosaveController 409 snapshots', () => {
  const serverSnapshot: EssayServerSnapshot = {
    paragraphs: [{ id: 'p-1', outlineId: 'o-1', text: '服务器正文' }],
    baseVersion: 22,
    updatedAt: '2026-07-12T21:05:00Z',
  }

  it('applies the server snapshot and archives the local snapshot before saved', async () => {
    const controller = new EssayAutosaveController({
      storage: sessionStorage, storageKey: 'essay-local-draft-1', initialDraft: draft('初始'), supportsConflictRetry: true,
      save: async () => { throw new EssaySaveConflictError('版本冲突', serverSnapshot) },
    })
    controller.capture(draft('本地正文'))
    controller.flush()
    await vi.waitFor(() => expect(controller.state.status).toBe('conflict'))
    expect(controller.state.conflict?.localSnapshot.draft.paragraphs[0]?.text).toBe('本地正文')
    expect(controller.state.conflict?.serverSnapshot?.paragraphs[0]?.text).toBe('服务器正文')

    expect(controller.useServerSnapshot()).toBe(true)
    expect(controller.state.draft.paragraphs[0]?.text).toBe('服务器正文')
    expect(controller.state.baseVersion).toBe(22)
    expect(controller.state.status).toBe('saved')
    expect(sessionStorage.getItem('essay-local-draft-1')).toBeNull()
    expect(sessionStorage.getItem('essay-local-draft-1:conflict:1')).toContain('本地正文')
  })

  it('cannot claim resolution when the conflict has no server snapshot', async () => {
    const controller = new EssayAutosaveController({
      storage: sessionStorage, storageKey: 'essay-local-draft-1', initialDraft: draft('初始'),
      save: async () => { throw new EssaySaveConflictError('版本冲突') },
    })
    controller.capture(draft('本地正文'))
    controller.flush()
    await vi.waitFor(() => expect(controller.state.status).toBe('conflict'))
    expect(controller.useServerSnapshot()).toBe(false)
    expect(controller.state.status).toBe('conflict')
    expect(controller.state.conflict?.requiresReload).toBe(true)
    expect(stored()?.draft.paragraphs[0]?.text).toBe('本地正文')
  })

  it('keeps local content for comparison and retries it against the server baseVersion', async () => {
    const retry = deferred<EssaySaveResult>()
    const calls: EssaySaveRequest[] = []
    const controller = new EssayAutosaveController({
      storage: sessionStorage, storageKey: 'essay-local-draft-1', initialDraft: draft('初始'), supportsConflictRetry: true,
      save: (request) => {
        calls.push(structuredClone(request))
        if (calls.length === 1) return Promise.reject(new EssaySaveConflictError('版本冲突', serverSnapshot))
        return retry.promise
      },
    })
    controller.capture(draft('保留的本地正文'))
    controller.flush()
    await vi.waitFor(() => expect(controller.state.status).toBe('conflict'))
    controller.keepLocalForComparison()
    expect(controller.state.status).toBe('conflict')
    expect(controller.state.conflict?.choice).toBe('local')
    expect(stored()?.draft.paragraphs[0]?.text).toBe('保留的本地正文')

    expect(controller.retryLocalAgainstServer()).toBe(true)
    await vi.waitFor(() => expect(calls).toHaveLength(2))
    expect(calls[1]?.baseVersion).toBe(22)
    expect(calls[1]?.draft.paragraphs[0]?.text).toBe('保留的本地正文')
    retry.resolve({ confirmedRevision: 1, baseVersion: 23, updatedAt: '2026-07-12T21:06:00Z' })
    await retry.promise
    await vi.waitFor(() => expect(controller.state.status).toBe('saved'))
    expect(sessionStorage.getItem('essay-local-draft-1')).toBeNull()
  })
})
