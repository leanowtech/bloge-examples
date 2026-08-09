export type DraftLifecycleState =
  | 'NEW'
  | 'DIRTY'
  | 'SAVING'
  | 'SAVED'
  | 'CONFLICTED'
  | 'RECOVERABLE'
  | 'RECOVERABLE_OFFLINE'
  | 'RECOVERED';

export interface RecoveryCoordinate {
  tenantId: string;
  namespace: string;
  environment: string;
  draftId?: string;
}

export interface ContinuityState {
  sessionId: string;
  lifecycle: DraftLifecycleState;
  contentEpoch: number;
  contentFingerprint: string;
  savedEpoch: number;
  savedFingerprint: string;
  savedRevision: number;
  recoveryCapturedAt: string;
  errorCode: string;
}

export type ContinuityEvent =
  | { type: 'CONTENT_CHANGED'; epoch: number; fingerprint: string }
  | { type: 'SAVE_STARTED'; epoch: number }
  | { type: 'SAVE_SUCCEEDED'; epoch: number; fingerprint: string; revision: number }
  | { type: 'SAVE_CONFLICTED'; errorCode?: string }
  | { type: 'SAVE_FAILED'; offline: boolean; errorCode?: string }
  | { type: 'RECOVERY_STORED'; epoch: number; capturedAt: string }
  | { type: 'RECOVERY_RESTORED'; sessionId: string; epoch: number; fingerprint: string; capturedAt: string }
  | { type: 'DISCARDED' };

export interface AuthoringRecoveryEnvelope<TPayload> {
  schemaVersion: 'bloge.authoringRecovery.v1';
  sessionId: string;
  coordinate: RecoveryCoordinate;
  contentEpoch: number;
  contentFingerprint: string;
  capturedAt: string;
  expiresAt: string;
  payload: TPayload;
}

export interface WorkspaceRecoveryStore {
  readonly security: 'SESSION_EPHEMERAL' | 'HOST_ENCRYPTED';
  load(coordinate: RecoveryCoordinate): Promise<string | null>;
  save(coordinate: RecoveryCoordinate, serializedEnvelope: string): Promise<void>;
  remove(coordinate: RecoveryCoordinate): Promise<void>;
}

export function initialContinuityState(sessionId: string): ContinuityState {
  return {
    sessionId,
    lifecycle: 'NEW',
    contentEpoch: 0,
    contentFingerprint: '',
    savedEpoch: 0,
    savedFingerprint: '',
    savedRevision: 0,
    recoveryCapturedAt: '',
    errorCode: '',
  };
}

/** Pure lifecycle reducer. Epoch checks prevent an older save receipt from blessing newer edits. */
export function reduceContinuityState(
  state: ContinuityState,
  event: ContinuityEvent,
): ContinuityState {
  switch (event.type) {
    case 'CONTENT_CHANGED':
      if (event.epoch < state.contentEpoch) return state;
      return {
        ...state,
        lifecycle: event.fingerprint === state.savedFingerprint ? 'SAVED' : 'DIRTY',
        contentEpoch: event.epoch,
        contentFingerprint: event.fingerprint,
        errorCode: '',
      };
    case 'SAVE_STARTED':
      if (event.epoch < state.contentEpoch) return state;
      return { ...state, lifecycle: 'SAVING', errorCode: '' };
    case 'SAVE_SUCCEEDED': {
      const newerContentExists = state.contentEpoch > event.epoch
        || state.contentFingerprint !== event.fingerprint;
      return {
        ...state,
        lifecycle: newerContentExists ? 'DIRTY' : 'SAVED',
        savedEpoch: Math.max(state.savedEpoch, event.epoch),
        savedFingerprint: event.fingerprint,
        savedRevision: Math.max(state.savedRevision, event.revision),
        errorCode: '',
      };
    }
    case 'SAVE_CONFLICTED':
      return { ...state, lifecycle: 'CONFLICTED', errorCode: event.errorCode ?? 'RG.AUTHOR.SAVE.CONFLICT' };
    case 'SAVE_FAILED':
      return {
        ...state,
        lifecycle: event.offline ? 'RECOVERABLE_OFFLINE' : 'DIRTY',
        errorCode: event.errorCode ?? 'RG.AUTHOR.SAVE.FAILED',
      };
    case 'RECOVERY_STORED':
      if (event.epoch !== state.contentEpoch) return state;
      return {
        ...state,
        lifecycle: state.lifecycle === 'SAVED'
          ? 'SAVED'
          : state.lifecycle === 'RECOVERABLE_OFFLINE' ? state.lifecycle : 'RECOVERABLE',
        recoveryCapturedAt: event.capturedAt,
      };
    case 'RECOVERY_RESTORED':
      return {
        ...state,
        sessionId: event.sessionId,
        lifecycle: 'RECOVERED',
        contentEpoch: event.epoch,
        contentFingerprint: event.fingerprint,
        recoveryCapturedAt: event.capturedAt,
        errorCode: '',
      };
    case 'DISCARDED':
      return initialContinuityState(state.sessionId);
  }
}

export function createRecoveryEnvelope<TPayload>({
  sessionId,
  coordinate,
  contentEpoch,
  contentFingerprint,
  payload,
  now = new Date(),
  ttlMs = 8 * 60 * 60 * 1000,
}: {
  sessionId: string;
  coordinate: RecoveryCoordinate;
  contentEpoch: number;
  contentFingerprint: string;
  payload: TPayload;
  now?: Date;
  ttlMs?: number;
}): AuthoringRecoveryEnvelope<TPayload> {
  return {
    schemaVersion: 'bloge.authoringRecovery.v1',
    sessionId,
    coordinate: normalizedCoordinate(coordinate),
    contentEpoch,
    contentFingerprint,
    capturedAt: now.toISOString(),
    expiresAt: new Date(now.getTime() + ttlMs).toISOString(),
    payload,
  };
}

export function parseRecoveryEnvelope<TPayload>(
  raw: string | null,
  expectedCoordinate: RecoveryCoordinate,
  now = new Date(),
): AuthoringRecoveryEnvelope<TPayload> | null {
  if (!raw) return null;
  try {
    const envelope = JSON.parse(raw) as Partial<AuthoringRecoveryEnvelope<TPayload>>;
    if (
      envelope.schemaVersion !== 'bloge.authoringRecovery.v1'
      || !envelope.sessionId
      || !envelope.contentFingerprint
      || !envelope.capturedAt
      || !envelope.expiresAt
      || envelope.payload === undefined
      || !sameRecoveryScope(envelope.coordinate, expectedCoordinate)
      || Date.parse(envelope.expiresAt) <= now.getTime()
    ) {
      return null;
    }
    return envelope as AuthoringRecoveryEnvelope<TPayload>;
  } catch {
    return null;
  }
}

/** Browser demo adapter. Enterprise and VS Code hosts should inject a HOST_ENCRYPTED store. */
export class BrowserSessionRecoveryStore implements WorkspaceRecoveryStore {
  readonly security = 'SESSION_EPHEMERAL' as const;

  constructor(private readonly storage: Storage) {}

  async load(coordinate: RecoveryCoordinate): Promise<string | null> {
    return this.storage.getItem(recoveryStorageKey(coordinate));
  }

  async save(coordinate: RecoveryCoordinate, serializedEnvelope: string): Promise<void> {
    this.storage.setItem(recoveryStorageKey(coordinate), serializedEnvelope);
  }

  async remove(coordinate: RecoveryCoordinate): Promise<void> {
    this.storage.removeItem(recoveryStorageKey(coordinate));
  }
}

let configuredRecoveryStore: WorkspaceRecoveryStore | null = null;

/** Installs an encrypted host-owned recovery store before rendering the app. */
export function setWorkspaceRecoveryStore(store: WorkspaceRecoveryStore): void {
  configuredRecoveryStore = store;
}

export function resetWorkspaceRecoveryStore(): void {
  configuredRecoveryStore = null;
}

export function workspaceRecoveryStore(): WorkspaceRecoveryStore {
  if (configuredRecoveryStore) return configuredRecoveryStore;
  return new BrowserSessionRecoveryStore(window.sessionStorage);
}

export function recoveryStorageKey(coordinate: RecoveryCoordinate): string {
  const scope = normalizedCoordinate(coordinate);
  return [
    'bloge.authoring.recovery.v1',
    scope.tenantId,
    scope.namespace,
    scope.environment,
  ].map(encodeURIComponent).join(':');
}

function sameRecoveryScope(
  actual: RecoveryCoordinate | undefined,
  expected: RecoveryCoordinate,
): boolean {
  if (!actual) return false;
  const left = normalizedCoordinate(actual);
  const right = normalizedCoordinate(expected);
  return left.tenantId === right.tenantId
    && left.namespace === right.namespace
    && left.environment === right.environment;
}

function normalizedCoordinate(coordinate: RecoveryCoordinate): RecoveryCoordinate {
  return {
    tenantId: coordinate.tenantId.trim(),
    namespace: coordinate.namespace.trim(),
    environment: coordinate.environment.trim(),
    ...(coordinate.draftId?.trim() ? { draftId: coordinate.draftId.trim() } : {}),
  };
}
