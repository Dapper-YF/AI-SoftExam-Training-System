import type { PracticeAnswerPayload, PracticeSaveAnswerResult } from '../types'

export interface PracticeAnswerMutationAction {
  attemptId: string
  questionId: string
  subQuestionId: string
  answer?: string
  answerPayload?: PracticeAnswerPayload
  marked: boolean
  usedSeconds: number
  baseVersion: number
}

export interface PracticeAnswerMutationSnapshot {
  readonly attemptId: string
  readonly questionId: string
  readonly subQuestionId: string
  readonly answerPayload: Readonly<PracticeAnswerPayload>
  readonly marked: boolean
  readonly usedSeconds: number
  readonly baseVersion: number
  readonly clientMutationId: string
  readonly requestSnapshot: string
}

export interface PracticeAnswerMutationIntent {
  readonly attemptId: string
  readonly questionId: string
  readonly subQuestionId: string
  readonly answerPayload: Readonly<PracticeAnswerPayload>
  readonly marked: boolean
  readonly usedSeconds: number
  readonly revision: number
}

export interface PracticePendingAnswerMutation {
  readonly snapshot: PracticeAnswerMutationSnapshot
  status: 'saving' | 'failed' | 'conflict'
  requiresReload: boolean
  error?: unknown
}

export type PracticeAnswerMutationOutcome =
  | { kind: 'succeeded'; snapshot: PracticeAnswerMutationSnapshot; result: PracticeSaveAnswerResult; nextPending: boolean }
  | { kind: 'failed'; snapshot: PracticeAnswerMutationSnapshot; error: unknown }
  | { kind: 'conflict'; snapshot: PracticeAnswerMutationSnapshot; error: unknown }
  | { kind: 'stale'; snapshot: PracticeAnswerMutationSnapshot }
  | { kind: 'none' }
  | { kind: 'inflight'; snapshot: PracticeAnswerMutationSnapshot }
  | { kind: 'reload-required'; intent: PracticeAnswerMutationIntent; error?: unknown }

interface ControllerOptions {
  send: (snapshot: PracticeAnswerMutationSnapshot) => Promise<PracticeSaveAnswerResult>
  createMutationId?: () => string
}

interface Waiter {
  resolve: (outcome: PracticeAnswerMutationOutcome) => void
}

interface MutationSlot {
  epoch: number
  revision: number
  pending?: PracticePendingAnswerMutation
  next?: PracticeAnswerMutationIntent
  reload?: { intent: PracticeAnswerMutationIntent; error?: unknown }
  nextWaiters: Waiter[]
}

function normalizeOptionKey(answer: string): string {
  return answer.trim().toUpperCase()
}

function normalizePayload(action: PracticeAnswerMutationAction): PracticeAnswerPayload {
  const payload = action.answerPayload ?? (action.answer == null ? undefined : { optionKey: action.answer })
  if (!payload) throw new Error('答案内容不能为空')
  if ('optionKey' in payload) return Object.freeze({ optionKey: normalizeOptionKey(payload.optionKey) })
  if ('optionKeys' in payload) return Object.freeze({ optionKeys: Object.freeze(
    [...payload.optionKeys].map(normalizeOptionKey).sort(),
  ) as unknown as string[] })
  if ('value' in payload) return Object.freeze({ value: payload.value })
  if ('structured' in payload) return Object.freeze({ structured: Object.freeze({ ...payload.structured }) })
  return Object.freeze({ text: payload.text.trim() })
}

function createIntent(action: PracticeAnswerMutationAction, revision: number): PracticeAnswerMutationIntent {
  return Object.freeze({
    attemptId: action.attemptId,
    questionId: action.questionId,
    subQuestionId: action.subQuestionId,
    answerPayload: normalizePayload(action),
    marked: action.marked,
    usedSeconds: action.usedSeconds,
    revision,
  })
}

function createSnapshot(
  intent: PracticeAnswerMutationIntent,
  baseVersion: number,
  clientMutationId: string,
): PracticeAnswerMutationSnapshot {
  const requestSnapshot = JSON.stringify({
    attemptId: intent.attemptId,
    questionId: intent.questionId,
    subQuestionId: intent.subQuestionId,
    answerPayload: intent.answerPayload,
    marked: intent.marked,
    usedSeconds: intent.usedSeconds,
    baseVersion,
    clientMutationId,
  })
  return Object.freeze({
    attemptId: intent.attemptId,
    questionId: intent.questionId,
    subQuestionId: intent.subQuestionId,
    answerPayload: intent.answerPayload,
    marked: intent.marked,
    usedSeconds: intent.usedSeconds,
    baseVersion,
    clientMutationId,
    requestSnapshot,
  })
}

function isVersionConflict(error: unknown): boolean {
  if (!error || typeof error !== 'object') return false
  const value = error as { status?: unknown; code?: unknown }
  return value.status === 409 && value.code !== 'PRACTICE_REQUEST_PROCESSING'
}

export class PracticeAnswerMutationController {
  private readonly slots = new Map<string, MutationSlot>()
  private readonly saved = new Set<string>()
  private readonly completed = new Map<string, PracticeAnswerMutationSnapshot>()
  private readonly send: ControllerOptions['send']
  private readonly createMutationId: () => string

  constructor(options: ControllerOptions) {
    this.send = options.send
    this.createMutationId = options.createMutationId ?? (() => crypto.randomUUID())
  }

  begin(action: PracticeAnswerMutationAction): Promise<PracticeAnswerMutationOutcome> {
    const slot = this.getOrCreateSlot(action.subQuestionId)
    const intent = createIntent(action, ++slot.revision)
    if (slot.reload) {
      slot.reload = { intent, error: slot.reload.error }
      return Promise.resolve({ kind: 'reload-required', intent, error: slot.reload.error })
    }
    if (slot.pending) {
      slot.next = intent
      return new Promise((resolve) => slot.nextWaiters.push({ resolve }))
    }
    return this.start(slot, intent, action.baseVersion)
  }

  retry(subQuestionId: string): Promise<PracticeAnswerMutationOutcome> {
    const slot = this.slots.get(subQuestionId)
    const record = slot?.pending
    if (!slot || !record) return Promise.resolve({ kind: 'none' })
    if (record.status === 'conflict') {
      return Promise.resolve({ kind: 'conflict', snapshot: record.snapshot, error: record.error })
    }
    if (record.status === 'saving') return Promise.resolve({ kind: 'inflight', snapshot: record.snapshot })
    return this.dispatch(slot, record, slot.epoch)
  }

  prepareReload(subQuestionId: string): PracticeAnswerMutationIntent | undefined {
    const slot = this.slots.get(subQuestionId)
    if (!slot) return undefined
    if (slot.reload) return slot.reload.intent
    const intent = slot.next ?? (slot.pending ? createIntent({
      attemptId: slot.pending.snapshot.attemptId,
      questionId: slot.pending.snapshot.questionId,
      subQuestionId: slot.pending.snapshot.subQuestionId,
      answerPayload: slot.pending.snapshot.answerPayload as PracticeAnswerPayload,
      marked: slot.pending.snapshot.marked,
      usedSeconds: slot.pending.snapshot.usedSeconds,
      baseVersion: slot.pending.snapshot.baseVersion,
    }, slot.revision + 1) : undefined)
    const abandoned = slot.pending?.snapshot
    slot.epoch += 1
    slot.pending = undefined
    slot.next = undefined
    if (intent) slot.reload = { intent }
    if (abandoned) {
      const stale: PracticeAnswerMutationOutcome = { kind: 'stale', snapshot: abandoned }
      slot.nextWaiters.splice(0).forEach(({ resolve }) => resolve(stale))
    }
    return intent
  }

  markReloadFailed(subQuestionId: string, error: unknown): void {
    const slot = this.slots.get(subQuestionId)
    if (slot?.reload) slot.reload = { intent: slot.reload.intent, error }
  }

  resumeAfterReload(subQuestionId: string, baseVersion: number): Promise<PracticeAnswerMutationOutcome> {
    const slot = this.slots.get(subQuestionId)
    const intent = slot?.reload?.intent
    if (!slot || !intent) return Promise.resolve({ kind: 'none' })
    slot.revision = Math.max(slot.revision, intent.revision)
    const dispatched = this.start(slot, intent, baseVersion)
    slot.reload = undefined
    return dispatched
  }

  getPending(subQuestionId: string): PracticePendingAnswerMutation | undefined {
    return this.slots.get(subQuestionId)?.pending
  }

  getNextIntent(subQuestionId: string): PracticeAnswerMutationIntent | undefined {
    return this.slots.get(subQuestionId)?.next
  }

  getReloadIntent(subQuestionId: string): PracticeAnswerMutationIntent | undefined {
    return this.slots.get(subQuestionId)?.reload?.intent
  }

  getCompleted(subQuestionId: string): PracticeAnswerMutationSnapshot | undefined {
    return this.completed.get(subQuestionId)
  }

  isSaved(subQuestionId: string): boolean {
    return this.saved.has(subQuestionId)
  }

  markSaved(subQuestionId: string): void { this.saved.add(subQuestionId) }

  clear(subQuestionId: string): void {
    const slot = this.slots.get(subQuestionId)
    if (slot) slot.epoch += 1
    this.slots.delete(subQuestionId)
  }

  clearAll(): void {
    for (const slot of this.slots.values()) slot.epoch += 1
    this.slots.clear()
  }

  private getOrCreateSlot(subQuestionId: string): MutationSlot {
    const existing = this.slots.get(subQuestionId)
    if (existing) return existing
    const slot: MutationSlot = { epoch: 0, revision: 0, nextWaiters: [] }
    this.slots.set(subQuestionId, slot)
    return slot
  }

  private start(slot: MutationSlot, intent: PracticeAnswerMutationIntent, baseVersion: number): Promise<PracticeAnswerMutationOutcome> {
    const record: PracticePendingAnswerMutation = {
      snapshot: createSnapshot(intent, baseVersion, this.createMutationId()),
      status: 'saving',
      requiresReload: false,
    }
    slot.pending = record
    const epoch = ++slot.epoch
    return this.dispatch(slot, record, epoch)
  }

  private async dispatch(slot: MutationSlot, record: PracticePendingAnswerMutation, epoch: number): Promise<PracticeAnswerMutationOutcome> {
    record.status = 'saving'
    record.requiresReload = false
    record.error = undefined
    try {
      const result = await this.send(record.snapshot)
      if (slot.epoch !== epoch || slot.pending !== record) return { kind: 'stale', snapshot: record.snapshot }
      this.saved.add(record.snapshot.subQuestionId)
      this.completed.set(record.snapshot.subQuestionId, record.snapshot)
      const next = slot.next
      if (!next) {
        slot.pending = undefined
        return { kind: 'succeeded', snapshot: record.snapshot, result, nextPending: false }
      }
      slot.next = undefined
      slot.pending = undefined
      const waiters = slot.nextWaiters.splice(0)
      const nextPromise = this.start(slot, next, result.answerVersion)
      void nextPromise.then((outcome) => waiters.forEach(({ resolve }) => resolve(outcome)))
      return { kind: 'succeeded', snapshot: record.snapshot, result, nextPending: true }
    } catch (error) {
      if (slot.epoch !== epoch || slot.pending !== record) return { kind: 'stale', snapshot: record.snapshot }
      record.error = error
      if (isVersionConflict(error)) {
        record.status = 'conflict'
        record.requiresReload = true
        return { kind: 'conflict', snapshot: record.snapshot, error }
      }
      record.status = 'failed'
      return { kind: 'failed', snapshot: record.snapshot, error }
    }
  }
}
