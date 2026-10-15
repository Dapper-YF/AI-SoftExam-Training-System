import type { EssayParagraph } from './essay-state'
import type { EssayEditorData } from './types'

export type EssayAutosaveStatus = 'saved' | 'dirty' | 'saving' | 'failed' | 'conflict' | 'unconfirmed'

export interface EssayServerSnapshot {
  paragraphs: EssayParagraph[]
  baseVersion: number
  updatedAt: string
}

export interface EssayLocalSnapshot {
  revision: number
  baseVersion: number
  capturedAt: string
  draft: EssayEditorData
}

export interface EssayConflictState {
  localSnapshot: EssayLocalSnapshot
  serverSnapshot?: EssayServerSnapshot
  requiresReload: boolean
  choice?: 'local'
}

export interface EssaySaveRequest {
  draft: EssayEditorData
  baseVersion: number
  localRevision: number
}

export interface EssaySaveResult {
  confirmedRevision?: number
  baseVersion: number
  updatedAt: string
}

export interface EssayAutosaveState {
  status: EssayAutosaveStatus
  draft: EssayEditorData
  baseVersion: number
  localRevision: number
  confirmedRevision: number
  canRetry: boolean
  conflict?: EssayConflictState
}

interface StorageLike {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
}

interface EssayAutosaveOptions {
  storage: StorageLike
  storageKey: string
  initialDraft: EssayEditorData
  restoredSnapshot?: EssayLocalSnapshot | null
  supportsConflictRetry?: boolean
  save: (request: EssaySaveRequest) => Promise<EssaySaveResult>
  onChange?: (state: EssayAutosaveState) => void
}

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}

export function readEssayLocalSnapshot(storage: StorageLike, storageKey: string): EssayLocalSnapshot | null {
  const raw = storage.getItem(storageKey)
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as Partial<EssayLocalSnapshot>
    if (!Number.isInteger(parsed.revision) || !Number.isInteger(parsed.baseVersion) || !parsed.draft) return null
    return parsed as EssayLocalSnapshot
  } catch {
    return null
  }
}

export class EssaySaveConflictError extends Error {
  constructor(message: string, readonly serverSnapshot?: EssayServerSnapshot) {
    super(message)
    this.name = 'EssaySaveConflictError'
  }
}

export class EssayAutosaveController {
  readonly state: EssayAutosaveState
  private readonly storage: StorageLike
  private readonly storageKey: string
  private readonly supportsConflictRetry: boolean
  private readonly saveOperation: (request: EssaySaveRequest) => Promise<EssaySaveResult>
  private readonly onChange?: (state: EssayAutosaveState) => void
  private pending: EssaySaveRequest | null = null
  private inFlight: EssaySaveRequest | null = null
  private flushRequested = false

  constructor(options: EssayAutosaveOptions) {
    this.storage = options.storage
    this.storageKey = options.storageKey
    this.supportsConflictRetry = options.supportsConflictRetry ?? false
    this.saveOperation = options.save
    this.onChange = options.onChange
    const restored = options.restoredSnapshot
    const draft = clone(restored?.draft ?? options.initialDraft)
    const revision = restored?.revision ?? 0
    const baseVersion = restored?.baseVersion ?? draft.version
    this.state = {
      status: restored ? 'dirty' : 'saved',
      draft,
      baseVersion,
      localRevision: revision,
      confirmedRevision: 0,
      canRetry: false,
    }
    if (restored) this.pending = this.requestFrom(restored)
    this.emit()
  }

  get canRetryConflict(): boolean {
    return this.supportsConflictRetry && Boolean(this.state.conflict?.serverSnapshot)
  }

  capture(draft: EssayEditorData): number {
    const revision = this.state.localRevision + 1
    const snapshot: EssayLocalSnapshot = {
      revision,
      baseVersion: this.state.baseVersion,
      capturedAt: new Date().toISOString(),
      draft: clone(draft),
    }
    this.state.draft = clone(snapshot.draft)
    this.state.localRevision = revision
    this.storage.setItem(this.storageKey, JSON.stringify(snapshot))
    this.pending = this.requestFrom(snapshot)
    this.state.canRetry = false
    if (this.state.conflict) {
      this.state.conflict.localSnapshot = clone(snapshot)
      this.state.conflict.choice = 'local'
      this.state.status = 'conflict'
    } else {
      this.state.status = 'dirty'
    }
    this.emit()
    return revision
  }

  flush(): void {
    if (this.state.conflict) return
    if (this.inFlight) {
      this.flushRequested = true
      return
    }
    if (!this.pending) return
    const request = clone(this.pending)
    this.pending = null
    this.inFlight = request
    this.flushRequested = false
    this.state.status = 'saving'
    this.state.canRetry = false
    this.emit()
    void this.run(request)
  }

  retry(): boolean {
    if (!['failed', 'unconfirmed'].includes(this.state.status)) return false
    const snapshot = readEssayLocalSnapshot(this.storage, this.storageKey)
    if (!snapshot) return false
    this.pending = this.requestFrom(snapshot)
    this.state.status = 'dirty'
    this.state.canRetry = false
    this.emit()
    this.flush()
    return true
  }

  useServerSnapshot(): boolean {
    const conflict = this.state.conflict
    if (!conflict?.serverSnapshot) return false
    this.archive(conflict.localSnapshot)
    const server = conflict.serverSnapshot
    this.state.draft = {
      ...clone(this.state.draft),
      version: server.baseVersion,
      paragraphs: clone(server.paragraphs),
    }
    this.state.baseVersion = server.baseVersion
    this.state.confirmedRevision = this.state.localRevision
    this.state.status = 'saved'
    this.state.canRetry = false
    this.state.conflict = undefined
    this.pending = null
    this.flushRequested = false
    this.removeIfRevision(conflict.localSnapshot.revision)
    this.emit()
    return true
  }

  keepLocalForComparison(): boolean {
    const conflict = this.state.conflict
    if (!conflict) return false
    this.archive(conflict.localSnapshot)
    conflict.choice = 'local'
    this.state.status = 'conflict'
    this.emit()
    return true
  }

  retryLocalAgainstServer(): boolean {
    const conflict = this.state.conflict
    if (!this.supportsConflictRetry || !conflict?.serverSnapshot) return false
    this.state.baseVersion = conflict.serverSnapshot.baseVersion
    this.pending = {
      draft: clone(conflict.localSnapshot.draft),
      baseVersion: conflict.serverSnapshot.baseVersion,
      localRevision: conflict.localSnapshot.revision,
    }
    this.state.conflict = undefined
    this.state.status = 'dirty'
    this.emit()
    this.flush()
    return true
  }

  private async run(request: EssaySaveRequest): Promise<void> {
    try {
      const result = await this.saveOperation(clone(request))
      this.handleSuccess(request, result)
    } catch (reason) {
      this.handleFailure(request, reason)
    } finally {
      this.inFlight = null
      if (!this.state.conflict && this.pending && this.flushRequested) this.flush()
    }
  }

  private handleSuccess(request: EssaySaveRequest, result: EssaySaveResult): void {
    const confirmed = result.confirmedRevision === request.localRevision
    if (confirmed) {
      this.state.baseVersion = result.baseVersion
      this.state.confirmedRevision = request.localRevision
      if (this.pending) this.pending.baseVersion = result.baseVersion
    }
    if (confirmed && this.state.localRevision === request.localRevision) {
      this.state.draft.version = result.baseVersion
      this.state.status = 'saved'
      this.removeIfRevision(request.localRevision)
    } else if (this.state.localRevision > request.localRevision) {
      this.state.status = 'dirty'
    } else {
      this.state.status = 'unconfirmed'
      this.state.canRetry = true
      this.pending = request
    }
    this.emit()
  }

  private handleFailure(request: EssaySaveRequest, reason: unknown): void {
    const current = readEssayLocalSnapshot(this.storage, this.storageKey) ?? {
      revision: this.state.localRevision,
      baseVersion: this.state.baseVersion,
      capturedAt: new Date().toISOString(),
      draft: clone(this.state.draft),
    }
    if (reason instanceof EssaySaveConflictError) {
      this.state.status = 'conflict'
      this.state.canRetry = false
      this.state.conflict = {
        localSnapshot: clone(current),
        serverSnapshot: reason.serverSnapshot ? clone(reason.serverSnapshot) : undefined,
        requiresReload: !reason.serverSnapshot,
      }
      this.pending = this.requestFrom(current)
      this.flushRequested = false
    } else {
      this.state.status = 'failed'
      this.state.canRetry = true
      if (!this.pending || this.pending.localRevision < current.revision) this.pending = this.requestFrom(current)
    }
    this.emit()
  }

  private requestFrom(snapshot: EssayLocalSnapshot): EssaySaveRequest {
    return { draft: clone(snapshot.draft), baseVersion: snapshot.baseVersion, localRevision: snapshot.revision }
  }

  private removeIfRevision(revision: number): void {
    if (readEssayLocalSnapshot(this.storage, this.storageKey)?.revision === revision) this.storage.removeItem(this.storageKey)
  }

  private archive(snapshot: EssayLocalSnapshot): void {
    this.storage.setItem(`${this.storageKey}:conflict:${snapshot.revision}`, JSON.stringify(snapshot))
  }

  private emit(): void {
    this.onChange?.(clone(this.state))
  }
}
