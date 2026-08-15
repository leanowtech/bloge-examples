import {
  useCallback,
  useEffect,
  useMemo,
  useReducer,
  useRef,
  useState,
} from 'react';

import { canonicalJson, sha256Fingerprint } from '../../contract-scenario/fingerprint';
import { useWorkspaceNavigationGuard } from './SafeWorkspaceNavigation';
import { HOST_WILL_DISPOSE_EVENT, joinHostDisposal } from '../../host/hostLifecycle';
import {
  createRecoveryEnvelope,
  initialContinuityState,
  parseRecoveryEnvelope,
  reduceContinuityState,
  type ContinuityState,
  type RecoveryCoordinate,
  type WorkspaceRecoveryStore,
  workspaceRecoveryStore,
} from './workspaceContinuity';

export interface WorkspaceContinuityOptions<TPayload> {
  enabled: boolean;
  ready: boolean;
  allowRecovery: boolean;
  hasContent: boolean;
  coordinate: RecoveryCoordinate;
  payload: TPayload;
  fingerprintValue?: unknown;
  authoritativelySaved: boolean;
  savedRevision: number;
  canAutosave: boolean;
  onRestore: (payload: TPayload, capturedAt: string) => void;
  onSave: (attempt: WorkspaceSaveAttempt) => Promise<void>;
  recoveryPayloadGuard?: (payload: unknown) => payload is TPayload;
  recoveryFingerprintValue?: (payload: TPayload) => unknown;
  recoveryStore?: WorkspaceRecoveryStore;
  debounceMs?: number;
  maxWaitMs?: number;
  autosaveMs?: number;
}

/** Stable command identity retained across ambiguous retries for one content epoch. */
export interface WorkspaceSaveAttempt {
  sessionId: string;
  contentEpoch: number;
  contentFingerprint: string;
  idempotencyKey: string;
}

export interface WorkspaceContinuityHandle {
  state: ContinuityState;
  recoverySecurity: WorkspaceRecoveryStore['security'];
  restoreChecked: boolean;
  flushRecovery: () => Promise<boolean>;
  save: () => Promise<boolean>;
  exportRecovery: () => void;
  discard: () => Promise<void>;
}

/** Coordinates recovery snapshots and authoritative saves without owning domain state. */
export function useWorkspaceContinuity<TPayload>({
  enabled,
  ready,
  allowRecovery,
  hasContent,
  coordinate,
  payload,
  fingerprintValue = payload,
  authoritativelySaved,
  savedRevision,
  canAutosave,
  onRestore,
  onSave,
  recoveryPayloadGuard,
  recoveryFingerprintValue = (recoveredPayload) => recoveredPayload,
  recoveryStore: suppliedStore,
  debounceMs = 350,
  maxWaitMs = 5_000,
  autosaveMs = 1_500,
}: WorkspaceContinuityOptions<TPayload>): WorkspaceContinuityHandle {
  const sessionIdRef = useRef(createSessionId());
  const store = useMemo(
    () => suppliedStore ?? workspaceRecoveryStore(),
    [suppliedStore],
  );
  const [state, dispatch] = useReducer(
    reduceContinuityState,
    sessionIdRef.current,
    initialContinuityState,
  );
  const [restoreChecked, setRestoreChecked] = useState(!enabled || !allowRecovery);
  const restoreAttemptedRef = useRef(!enabled || !allowRecovery);
  const payloadRef = useRef(payload);
  const fingerprintValueRef = useRef(fingerprintValue);
  const coordinateRef = useRef(coordinate);
  const fingerprintRef = useRef('');
  const epochRef = useRef(0);
  const fingerprintSequenceRef = useRef(0);
  const debounceTimerRef = useRef<number | null>(null);
  const maxWaitTimerRef = useRef<number | null>(null);
  const autosaveTimerRef = useRef<number | null>(null);
  const latestEnvelopeRef = useRef('');
  const saveInFlightRef = useRef<Promise<boolean> | null>(null);
  const onRestoreRef = useRef(onRestore);
  const onSaveRef = useRef(onSave);
  const recoveryPayloadGuardRef = useRef(recoveryPayloadGuard);
  const recoveryFingerprintValueRef = useRef(recoveryFingerprintValue);

  payloadRef.current = payload;
  fingerprintValueRef.current = fingerprintValue;
  coordinateRef.current = coordinate;
  onRestoreRef.current = onRestore;
  onSaveRef.current = onSave;
  recoveryPayloadGuardRef.current = recoveryPayloadGuard;
  recoveryFingerprintValueRef.current = recoveryFingerprintValue;

  useEffect(() => {
    if (!enabled || !ready || restoreAttemptedRef.current) return;
    restoreAttemptedRef.current = true;
    let active = true;
    const restore = async () => {
      try {
        const raw = await store.load(coordinateRef.current);
        const envelope = parseRecoveryEnvelope<TPayload>(raw, coordinateRef.current);
        if (!active || !envelope) return;
        if (recoveryPayloadGuardRef.current
          && !recoveryPayloadGuardRef.current(envelope.payload)) {
          await store.remove(coordinateRef.current);
          return;
        }
        const verifiedFingerprint = await sha256Fingerprint(
          recoveryFingerprintValueRef.current(envelope.payload),
        );
        if (!active || verifiedFingerprint !== envelope.contentFingerprint) {
          await store.remove(coordinateRef.current);
          return;
        }
        sessionIdRef.current = envelope.sessionId;
        epochRef.current = envelope.contentEpoch;
        fingerprintRef.current = envelope.contentFingerprint;
        latestEnvelopeRef.current = raw ?? '';
        onRestoreRef.current(envelope.payload, envelope.capturedAt);
        dispatch({
          type: 'RECOVERY_RESTORED',
          sessionId: envelope.sessionId,
          epoch: envelope.contentEpoch,
          fingerprint: envelope.contentFingerprint,
          capturedAt: envelope.capturedAt,
        });
      } finally {
        if (active) setRestoreChecked(true);
      }
    };
    void restore();
    return () => {
      active = false;
    };
  }, [enabled, ready, store]);

  useEffect(() => {
    if (!enabled || !restoreChecked || !hasContent) return;
    if (debounceTimerRef.current !== null) {
      window.clearTimeout(debounceTimerRef.current);
      debounceTimerRef.current = null;
    }
    const sequence = ++fingerprintSequenceRef.current;
    void sha256Fingerprint(fingerprintValue).then((fingerprint) => {
      if (sequence !== fingerprintSequenceRef.current) return;
      if (fingerprint !== fingerprintRef.current) {
        fingerprintRef.current = fingerprint;
        epochRef.current += 1;
      }
      dispatch({
        type: 'CONTENT_CHANGED',
        epoch: epochRef.current,
        fingerprint,
      });
      if (authoritativelySaved) {
        dispatch({
          type: 'SAVE_SUCCEEDED',
          epoch: epochRef.current,
          fingerprint,
          revision: savedRevision,
        });
      }
    });
  }, [authoritativelySaved, enabled, fingerprintValue, hasContent, restoreChecked, savedRevision]);

  const clearRecoveryTimers = useCallback(() => {
    if (debounceTimerRef.current !== null) window.clearTimeout(debounceTimerRef.current);
    if (maxWaitTimerRef.current !== null) window.clearTimeout(maxWaitTimerRef.current);
    debounceTimerRef.current = null;
    maxWaitTimerRef.current = null;
  }, []);

  const flushRecovery = useCallback(async (): Promise<boolean> => {
    if (!enabled || !hasContent) return true;
    try {
      const fingerprint = await sha256Fingerprint(fingerprintValueRef.current);
      if (fingerprint !== fingerprintRef.current) {
        fingerprintRef.current = fingerprint;
        epochRef.current += 1;
        dispatch({
          type: 'CONTENT_CHANGED',
          epoch: epochRef.current,
          fingerprint,
        });
      }
      const envelope = createRecoveryEnvelope({
        sessionId: sessionIdRef.current,
        coordinate: coordinateRef.current,
        contentEpoch: epochRef.current,
        contentFingerprint: fingerprint,
        payload: payloadRef.current,
      });
      const serialized = canonicalJson(envelope);
      await store.save(coordinateRef.current, serialized);
      latestEnvelopeRef.current = serialized;
      clearRecoveryTimers();
      dispatch({
        type: 'RECOVERY_STORED',
        epoch: epochRef.current,
        capturedAt: envelope.capturedAt,
      });
      return true;
    } catch {
      dispatch({ type: 'SAVE_FAILED', offline: true, errorCode: 'RG.AUTHOR.RECOVERY.WRITE_FAILED' });
      return false;
    }
  }, [clearRecoveryTimers, enabled, hasContent, store]);

  useEffect(() => {
    if (!enabled || !restoreChecked || !hasContent || !authoritativelySaved || savedRevision < 1) {
      return;
    }
    void flushRecovery();
  }, [authoritativelySaved, enabled, flushRecovery, hasContent, restoreChecked, savedRevision]);

  const save = useCallback((): Promise<boolean> => {
    if (!enabled || !hasContent) return Promise.resolve(true);
    if (saveInFlightRef.current) return saveInFlightRef.current;
    const task = (async (): Promise<boolean> => {
      const fingerprint = fingerprintRef.current || await sha256Fingerprint(fingerprintValueRef.current);
      if (!fingerprintRef.current) {
        fingerprintRef.current = fingerprint;
        epochRef.current += 1;
      }
      const epoch = epochRef.current;
      dispatch({ type: 'SAVE_STARTED', epoch });
      try {
        await onSaveRef.current({
          sessionId: sessionIdRef.current,
          contentEpoch: epoch,
          contentFingerprint: fingerprint,
          idempotencyKey: `graph-save:${fingerprint.replace(':', '-')}`,
        });
        dispatch({
          type: 'SAVE_SUCCEEDED',
          epoch,
          fingerprint,
          revision: Math.max(1, savedRevision),
        });
        return true;
      } catch (cause: unknown) {
        const conflicted = /conflict|revision|409|412/i.test(String(cause));
        dispatch(conflicted
          ? { type: 'SAVE_CONFLICTED', errorCode: 'RG.AUTHOR.SAVE.CONFLICT' }
          : { type: 'SAVE_FAILED', offline: isOfflineError(cause), errorCode: 'RG.AUTHOR.SAVE.FAILED' });
        return false;
      }
    })();
    saveInFlightRef.current = task;
    void task.finally(() => {
      if (saveInFlightRef.current === task) saveInFlightRef.current = null;
    });
    return task;
  }, [enabled, hasContent, savedRevision]);

  const discard = useCallback(async () => {
    clearRecoveryTimers();
    await store.remove(coordinateRef.current);
    latestEnvelopeRef.current = '';
    dispatch({ type: 'DISCARDED' });
  }, [clearRecoveryTimers, store]);

  const exportRecovery = useCallback(() => {
    const serialized = latestEnvelopeRef.current;
    if (!serialized) return;
    const blob = new Blob([serialized], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `bloge-recovery-${sessionIdRef.current}.json`;
    anchor.click();
    URL.revokeObjectURL(url);
  }, []);

  useEffect(() => {
    if (state.lifecycle !== 'DIRTY' && state.lifecycle !== 'RECOVERED') return undefined;
    if (debounceTimerRef.current !== null) window.clearTimeout(debounceTimerRef.current);
    debounceTimerRef.current = window.setTimeout(() => void flushRecovery(), debounceMs);
    if (maxWaitTimerRef.current === null) {
      maxWaitTimerRef.current = window.setTimeout(() => void flushRecovery(), maxWaitMs);
    }
    return () => {
      if (debounceTimerRef.current !== null) window.clearTimeout(debounceTimerRef.current);
      debounceTimerRef.current = null;
    };
  }, [debounceMs, flushRecovery, maxWaitMs, state.contentEpoch, state.lifecycle]);

  const autosaveBlocked = state.lifecycle === 'CONFLICTED'
    || state.lifecycle === 'RECOVERABLE_OFFLINE';
  useEffect(() => {
    if (!canAutosave || autosaveBlocked || state.contentEpoch < 1) return undefined;
    autosaveTimerRef.current = window.setTimeout(() => void save(), autosaveMs);
    return () => {
      if (autosaveTimerRef.current !== null) window.clearTimeout(autosaveTimerRef.current);
      autosaveTimerRef.current = null;
    };
  }, [autosaveBlocked, autosaveMs, canAutosave, save, state.contentEpoch]);

  useEffect(() => {
    if (!canAutosave || state.lifecycle !== 'RECOVERABLE_OFFLINE') return undefined;
    const retryWhenOnline = () => void save();
    window.addEventListener('online', retryWhenOnline);
    return () => window.removeEventListener('online', retryWhenOnline);
  }, [canAutosave, save, state.lifecycle]);

  useEffect(() => {
    const flushWhenHidden = () => {
      if (document.visibilityState === 'hidden') void flushRecovery();
    };
    const flushOnPageHide = () => void flushRecovery();
    const flushOnHostDispose = (event: Event) => joinHostDisposal(event, flushRecovery());
    document.addEventListener('visibilitychange', flushWhenHidden);
    window.addEventListener('pagehide', flushOnPageHide);
    window.addEventListener(HOST_WILL_DISPOSE_EVENT, flushOnHostDispose);
    return () => {
      document.removeEventListener('visibilitychange', flushWhenHidden);
      window.removeEventListener('pagehide', flushOnPageHide);
      window.removeEventListener(HOST_WILL_DISPOSE_EVENT, flushOnHostDispose);
      clearRecoveryTimers();
      if (autosaveTimerRef.current !== null) window.clearTimeout(autosaveTimerRef.current);
    };
  }, [clearRecoveryTimers, flushRecovery]);

  const navigationGuard = useMemo(() => ({
    lifecycle: state.lifecycle,
    flushRecovery,
    save,
    exportRecovery,
    discard,
  }), [discard, exportRecovery, flushRecovery, save, state.lifecycle]);
  useWorkspaceNavigationGuard(navigationGuard);

  return {
    state,
    recoverySecurity: store.security,
    restoreChecked,
    flushRecovery,
    save,
    exportRecovery,
    discard,
  };
}

function createSessionId(): string {
  return globalThis.crypto?.randomUUID?.() ?? `session-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function isOfflineError(cause: unknown): boolean {
  return typeof navigator !== 'undefined' && navigator.onLine === false
    || /network|offline|failed to fetch/i.test(String(cause));
}
