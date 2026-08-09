// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest';

import {
  BrowserSessionRecoveryStore,
  createRecoveryEnvelope,
  initialContinuityState,
  parseRecoveryEnvelope,
  reduceContinuityState,
} from './workspaceContinuity';

describe('workspace continuity', () => {
  beforeEach(() => window.sessionStorage.clear());

  it('keeps a newer dirty epoch when an older save receipt arrives', () => {
    const dirty = reduceContinuityState(initialContinuityState('session-a'), {
      type: 'CONTENT_CHANGED',
      epoch: 4,
      fingerprint: 'sha256:newer',
    });
    const saving = reduceContinuityState(dirty, { type: 'SAVE_STARTED', epoch: 4 });
    const editedAgain = reduceContinuityState(saving, {
      type: 'CONTENT_CHANGED',
      epoch: 5,
      fingerprint: 'sha256:newest',
    });

    const afterStaleReceipt = reduceContinuityState(editedAgain, {
      type: 'SAVE_SUCCEEDED',
      epoch: 4,
      fingerprint: 'sha256:newer',
      revision: 8,
    });

    expect(afterStaleReceipt.lifecycle).toBe('DIRTY');
    expect(afterStaleReceipt.contentEpoch).toBe(5);
    expect(afterStaleReceipt.savedRevision).toBe(8);
    expect(afterStaleReceipt.savedFingerprint).toBe('sha256:newer');
  });

  it('records recoverability without pretending the draft is authoritatively saved', () => {
    const dirty = reduceContinuityState(initialContinuityState('session-a'), {
      type: 'CONTENT_CHANGED',
      epoch: 2,
      fingerprint: 'sha256:dirty',
    });
    const recoverable = reduceContinuityState(dirty, {
      type: 'RECOVERY_STORED',
      epoch: 2,
      capturedAt: '2026-08-09T08:00:00.000Z',
    });

    expect(recoverable.lifecycle).toBe('RECOVERABLE');
    expect(recoverable.savedRevision).toBe(0);
    expect(recoverable.recoveryCapturedAt).toBe('2026-08-09T08:00:00.000Z');
  });

  it('keeps the authoritative saved lifecycle while refreshing its recovery timestamp', () => {
    const changed = reduceContinuityState(initialContinuityState('session-a'), {
      type: 'CONTENT_CHANGED',
      epoch: 1,
      fingerprint: 'sha256:saved',
    });
    const saved = reduceContinuityState(changed, {
      type: 'SAVE_SUCCEEDED',
      epoch: 1,
      fingerprint: 'sha256:saved',
      revision: 1,
    });
    const refreshed = reduceContinuityState(saved, {
      type: 'RECOVERY_STORED',
      epoch: 1,
      capturedAt: '2026-08-09T09:00:00.000Z',
    });

    expect(refreshed.lifecycle).toBe('SAVED');
    expect(refreshed.recoveryCapturedAt).toBe('2026-08-09T09:00:00.000Z');
  });

  it('adopts the recovered session identity and content coordinate', () => {
    const recovered = reduceContinuityState(initialContinuityState('new-session'), {
      type: 'RECOVERY_RESTORED',
      sessionId: 'original-session',
      epoch: 7,
      fingerprint: 'sha256:recovered',
      capturedAt: '2026-08-09T08:00:00.000Z',
    });

    expect(recovered).toMatchObject({
      sessionId: 'original-session',
      lifecycle: 'RECOVERED',
      contentEpoch: 7,
      contentFingerprint: 'sha256:recovered',
    });
  });

  it('rejects expired and cross-tenant recovery envelopes', () => {
    const envelope = createRecoveryEnvelope({
      sessionId: 'session-a',
      coordinate: {
        tenantId: 'tenant-a',
        namespace: 'credit',
        environment: 'test',
        draftId: 'draft-1',
      },
      contentEpoch: 3,
      contentFingerprint: 'sha256:draft',
      payload: { graphName: 'creditDecision' },
      now: new Date('2026-08-09T08:00:00.000Z'),
      ttlMs: 60_000,
    });

    expect(parseRecoveryEnvelope(JSON.stringify(envelope), {
      tenantId: 'tenant-a',
      namespace: 'credit',
      environment: 'test',
    }, new Date('2026-08-09T08:00:30.000Z'))?.payload).toEqual({ graphName: 'creditDecision' });
    expect(parseRecoveryEnvelope(JSON.stringify(envelope), {
      tenantId: 'tenant-b',
      namespace: 'credit',
      environment: 'test',
    }, new Date('2026-08-09T08:00:30.000Z'))).toBeNull();
    expect(parseRecoveryEnvelope(JSON.stringify(envelope), {
      tenantId: 'tenant-a',
      namespace: 'credit',
      environment: 'test',
    }, new Date('2026-08-09T08:01:01.000Z'))).toBeNull();
  });

  it('partitions browser recovery by the complete tenant, namespace, and environment coordinate', async () => {
    const store = new BrowserSessionRecoveryStore(window.sessionStorage);
    const coordinateA = { tenantId: 'tenant-a', namespace: 'credit', environment: 'test' };
    const coordinateB = { tenantId: 'tenant-a', namespace: 'credit', environment: 'prod' };

    await store.save(coordinateA, 'first');
    await store.save(coordinateB, 'second');

    expect(await store.load(coordinateA)).toBe('first');
    expect(await store.load(coordinateB)).toBe('second');
    await store.remove(coordinateA);
    expect(await store.load(coordinateA)).toBeNull();
    expect(await store.load(coordinateB)).toBe('second');
  });
});
