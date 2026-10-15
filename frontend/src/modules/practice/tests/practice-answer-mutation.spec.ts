import { describe, expect, it } from 'vitest'
import { ApiClientError } from '@/api/client'
import { PracticeAnswerMutationController, type PracticeAnswerMutationSnapshot } from '../state/practice-answer-mutation'
import type { PracticeSaveAnswerResult } from '../types'

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

function action(answer: string, marked = false, usedSeconds = 12) {
  return { attemptId: 'attempt-1', questionId: 'question-1', subQuestionId: 'sub-1', answer, marked, usedSeconds, baseVersion: 3 }
}

const result = (answerVersion: number): PracticeSaveAnswerResult => ({ answerVersion, savedAt: '2026-07-13T00:00:00Z' })

describe('practice answer mutation single flight', () => {
  it('waits for the current success before sending the next intent with the returned version', async () => {
    const responses = [deferred<PracticeSaveAnswerResult>(), deferred<PracticeSaveAnswerResult>()]
    const requests: PracticeAnswerMutationSnapshot[] = []
    let id = 0
    const controller = new PracticeAnswerMutationController({
      createMutationId: () => `mutation-${++id}`,
      send: (snapshot) => { requests.push(snapshot); return responses[requests.length - 1]!.promise },
    })

    const first = controller.begin(action('A'))
    const next = controller.begin(action('B'))
    expect(requests).toHaveLength(1)
    expect(controller.getNextIntent('sub-1')).toMatchObject({ answerPayload: { optionKey: 'B' }, revision: 2 })

    responses[0]!.resolve(result(4))
    expect(await first).toMatchObject({ kind: 'succeeded', nextPending: true })
    expect(requests).toHaveLength(2)
    expect(requests[1]).toMatchObject({ answerPayload: { optionKey: 'B' }, baseVersion: 4, clientMutationId: 'mutation-2' })
    responses[1]!.resolve(result(5))
    expect(await next).toMatchObject({ kind: 'succeeded', nextPending: false })
  })

  it('coalesces multiple edits during flight to the latest business intent', async () => {
    const responses = [deferred<PracticeSaveAnswerResult>(), deferred<PracticeSaveAnswerResult>()]
    const requests: PracticeAnswerMutationSnapshot[] = []
    let id = 0
    const controller = new PracticeAnswerMutationController({
      createMutationId: () => `mutation-${++id}`,
      send: (snapshot) => { requests.push(snapshot); return responses[requests.length - 1]!.promise },
    })
    const first = controller.begin(action('A'))
    const middle = controller.begin(action('B', false, 13))
    const latest = controller.begin(action('C', true, 18))
    expect(requests).toHaveLength(1)

    responses[0]!.resolve(result(4)); await first
    expect(requests).toHaveLength(2)
    expect(requests[1]).toMatchObject({ answerPayload: { optionKey: 'C' }, marked: true, usedSeconds: 18, baseVersion: 4 })
    responses[1]!.resolve(result(5))
    expect((await middle).kind).toBe('succeeded')
    expect((await latest).kind).toBe('succeeded')
  })

  it('keeps next unsent after failure and retries the current request byte-for-byte', async () => {
    const responses = [deferred<PracticeSaveAnswerResult>(), deferred<PracticeSaveAnswerResult>(), deferred<PracticeSaveAnswerResult>()]
    const requests: PracticeAnswerMutationSnapshot[] = []
    let id = 0
    const controller = new PracticeAnswerMutationController({
      createMutationId: () => `mutation-${++id}`,
      send: (snapshot) => { requests.push(snapshot); return responses[requests.length - 1]!.promise },
    })
    const first = controller.begin(action('A'))
    const next = controller.begin(action('B'))
    responses[0]!.reject(new TypeError('response lost'))
    expect((await first).kind).toBe('failed')
    expect(requests).toHaveLength(1)

    const retry = controller.retry('sub-1')
    expect(requests).toHaveLength(2)
    expect(requests[1]).toEqual(requests[0])
    expect(requests[1]!.requestSnapshot).toBe(requests[0]!.requestSnapshot)
    responses[1]!.resolve(result(4)); await retry
    expect(requests).toHaveLength(3)
    expect(requests[2]).toMatchObject({ answerPayload: { optionKey: 'B' }, baseVersion: 4, clientMutationId: 'mutation-2' })
    responses[2]!.resolve(result(5)); await next
  })

  it('keeps reload intent across restore failure and clears it only after scheduling the rebuilt mutation', async () => {
    const requests: PracticeAnswerMutationSnapshot[] = []
    let id = 0
    const controller = new PracticeAnswerMutationController({
      createMutationId: () => `mutation-${++id}`,
      send: async (snapshot) => { requests.push(snapshot); throw new TypeError('offline') },
    })
    await controller.begin(action('A'))
    void controller.begin(action('B', true, 20))
    controller.prepareReload('sub-1')
    controller.markReloadFailed('sub-1', new TypeError('restore failed'))
    expect(controller.getReloadIntent('sub-1')).toMatchObject({ answerPayload: { optionKey: 'B' }, marked: true })
    expect(requests).toHaveLength(1)

    const resumed = controller.resumeAfterReload('sub-1', 9)
    expect(controller.getReloadIntent('sub-1')).toBeUndefined()
    await resumed
    expect(requests[1]).toMatchObject({ answerPayload: { optionKey: 'B' }, marked: true, usedSeconds: 20, baseVersion: 9, clientMutationId: 'mutation-2' })
  })

  it('coalesces edits made during reload into the persistent latest intent without sending', async () => {
    const requests: PracticeAnswerMutationSnapshot[] = []
    let id = 0
    const controller = new PracticeAnswerMutationController({
      createMutationId: () => `mutation-${++id}`,
      send: async (snapshot) => { requests.push(snapshot); throw new ApiClientError('conflict', 409, 'PRACTICE_ANSWER_VERSION_CONFLICT') },
    })
    await controller.begin(action('A'))
    controller.prepareReload('sub-1')
    expect((await controller.begin(action('B', false, 15))).kind).toBe('reload-required')
    expect((await controller.begin(action('C', true, 22))).kind).toBe('reload-required')
    expect(requests).toHaveLength(1)
    expect(controller.getReloadIntent('sub-1')).toMatchObject({ answerPayload: { optionKey: 'C' }, marked: true, usedSeconds: 22, revision: 3 })
  })

  it('reports none and inflight explicitly without dispatching another request', async () => {
    const response = deferred<PracticeSaveAnswerResult>()
    let calls = 0
    const controller = new PracticeAnswerMutationController({
      createMutationId: () => 'mutation-1',
      send: () => { calls += 1; return response.promise },
    })
    expect((await controller.retry('missing')).kind).toBe('none')
    const pending = controller.begin(action('A'))
    expect((await controller.retry('sub-1')).kind).toBe('inflight')
    expect(calls).toBe(1)
    response.resolve(result(4)); await pending
  })

  it('fences a late response after the pending epoch is abandoned', async () => {
    const response = deferred<PracticeSaveAnswerResult>()
    const controller = new PracticeAnswerMutationController({ createMutationId: () => 'mutation-1', send: () => response.promise })
    const old = controller.begin(action('A'))
    controller.prepareReload('sub-1')
    response.resolve(result(4))
    expect((await old).kind).toBe('stale')
    expect(controller.isSaved('sub-1')).toBe(false)
  })

  it('blocks blind retry after 409 but keeps a reload path', async () => {
    let calls = 0
    const controller = new PracticeAnswerMutationController({
      createMutationId: () => `mutation-${calls + 1}`,
      send: async () => { calls += 1; throw new ApiClientError('conflict', 409, 'PRACTICE_ANSWER_VERSION_CONFLICT') },
    })
    expect((await controller.begin(action('A'))).kind).toBe('conflict')
    expect(await controller.retry('sub-1')).toMatchObject({ kind: 'conflict' })
    expect(calls).toBe(1)
    controller.prepareReload('sub-1')
    expect(controller.getReloadIntent('sub-1')).toMatchObject({ answerPayload: { optionKey: 'A' } })
  })

  it('treats PROCESSING as same-request retryable', async () => {
    const requests: PracticeAnswerMutationSnapshot[] = []
    const controller = new PracticeAnswerMutationController({
      createMutationId: () => 'mutation-1',
      send: async (snapshot) => {
        requests.push(snapshot)
        if (requests.length === 1) throw new ApiClientError('processing', 409, 'PRACTICE_REQUEST_PROCESSING')
        return result(4)
      },
    })
    expect((await controller.begin(action('A'))).kind).toBe('failed')
    expect((await controller.retry('sub-1')).kind).toBe('succeeded')
    expect(requests[1]).toEqual(requests[0])
  })
})
