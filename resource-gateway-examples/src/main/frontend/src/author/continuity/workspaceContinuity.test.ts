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

  it('marks content dirty before its exact fingerprint has finished', () => {
    const edited = reduceContinuityState(initialContinuityState('session-a'), {
      type: 'CONTENT_EDITED',
      epoch: 1,
    });

    expect(edited).toMatchObject({
      lifecycle: 'DIRTY',
      contentEpoch: 1,
      contentFingerprint: '',
    });
  });

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

  it('does not let an older receipt replace an already newer authoritative checkpoint', () => {
    const changed = reduceContinuityState(initialContinuityState('session-a'), {
      type: 'CONTENT_CHANGED',
      epoch: 7,
      fingerprint: 'sha256:seven',
    });
    const saved = reduceContinuityState(changed, {
      type: 'SAVE_SUCCEEDED',
      epoch: 7,
      fingerprint: 'sha256:seven',
      revision: 11,
    });
    const stale = reduceContinuityState(saved, {
      type: 'SAVE_SUCCEEDED',
      epoch: 6,
      fingerprint: 'sha256:six',
      revision: 10,
    });

    expect(stale).toEqual(saved);
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
    expect(reduceContinuityState(recovered, {
      type: 'CONTENT_CHANGED',
      epoch: 7,
      fingerprint: 'sha256:recovered',
    })).toEqual(recovered);
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

  it('rejects invalid epochs and malformed or reversed recovery times', () => {
    const base = createRecoveryEnvelope({
      sessionId: 'session-a',
      coordinate: { tenantId: 'tenant-a', namespace: 'credit', environment: 'test' },
      contentEpoch: 3,
      contentFingerprint: 'sha256:draft',
      payload: { graphName: 'creditDecision' },
      now: new Date('2026-08-09T08:00:00.000Z'),
    });
    const coordinate = { tenantId: 'tenant-a', namespace: 'credit', environment: 'test' };

    expect(parseRecoveryEnvelope(JSON.stringify({ ...base, contentEpoch: -1 }), coordinate)).toBeNull();
    expect(parseRecoveryEnvelope(JSON.stringify({ ...base, contentEpoch: 1.5 }), coordinate)).toBeNull();
    expect(parseRecoveryEnvelope(JSON.stringify({ ...base, capturedAt: 'not-a-date' }), coordinate)).toBeNull();
    expect(parseRecoveryEnvelope(JSON.stringify({
      ...base,
      expiresAt: '2026-08-09T07:59:59.000Z',
    }), coordinate, new Date('2026-08-09T07:00:00.000Z'))).toBeNull();
  });

  it('preserves a visible recovery boundary through 1000 deterministic fault interleavings', () => {
    for (let scenario = 0; scenario < 1_000; scenario += 1) {
      let state = initialContinuityState(`session-${scenario}`);
      const latestEpoch = 2 + scenario % 7;
      for (let epoch = 1; epoch <= latestEpoch; epoch += 1) {
        state = reduceContinuityState(state, {
          type: 'CONTENT_CHANGED',
          epoch,
          fingerprint: `sha256:${scenario}:${epoch}`,
        });
        if ((scenario + epoch) % 3 === 0) {
          state = reduceContinuityState(state, {
            type: 'RECOVERY_STORED',
            epoch,
            capturedAt: `2026-08-09T08:00:${String(epoch).padStart(2, '0')}.000Z`,
          });
        }
      }
      const staleEpoch = Math.max(1, latestEpoch - 1);
      state = reduceContinuityState(state, { type: 'SAVE_STARTED', epoch: staleEpoch });
      state = scenario % 2 === 0
        ? reduceContinuityState(state, {
            type: 'SAVE_FAILED',
            offline: scenario % 4 === 0,
            errorCode: 'RG.TEST.INJECTED',
          })
        : reduceContinuityState(state, {
            type: 'SAVE_SUCCEEDED',
            epoch: staleEpoch,
            fingerprint: `sha256:${scenario}:${staleEpoch}`,
            revision: staleEpoch,
          });

      if (state.lifecycle === 'SAVED') {
        expect(state.contentFingerprint).toBe(state.savedFingerprint);
        expect(state.contentEpoch).toBe(state.savedEpoch);
      } else {
        expect(['DIRTY', 'RECOVERABLE', 'RECOVERABLE_OFFLINE']).toContain(state.lifecycle);
      }
    }
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
