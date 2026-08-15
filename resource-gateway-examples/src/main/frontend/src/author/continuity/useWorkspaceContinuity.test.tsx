// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createRecoveryEnvelope,
  type RecoveryCoordinate,
  type WorkspaceRecoveryStore,
} from './workspaceContinuity';
import {
  useWorkspaceContinuity,
  type WorkspaceSaveAttempt,
} from './useWorkspaceContinuity';
import { prepareHostDisposal } from '../../host/hostLifecycle';
import { sha256Fingerprint } from '../../contract-scenario/fingerprint';

describe('useWorkspaceContinuity', () => {
  let root: Root | null = null;
  let host: HTMLDivElement;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean })
      .IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
  });

  afterEach(async () => {
    if (root) await act(async () => root?.unmount());
    root = null;
    host.remove();
    vi.useRealTimers();
  });

  it('refreshes the recovery envelope after an authoritative revision is saved', async () => {
    const store = new MemoryRecoveryStore();
    await render({ draftId: '', revision: 0 }, false, 0, store);
    await waitFor(() => store.serialized.includes('"revision":0'));

    await render({ draftId: 'draft-a', revision: 1 }, true, 1, store);
    await waitFor(() => store.serialized.includes('"draftId":"draft-a"'));

    const envelope = JSON.parse(store.serialized) as {
      payload: { draftId: string; revision: number };
    };
    expect(envelope.payload).toEqual({ draftId: 'draft-a', revision: 1 });
    expect(host.querySelector('[data-lifecycle]')?.getAttribute('data-lifecycle')).toBe('SAVED');
  });

  it('joins the host disposal barrier with an exact latest-payload recovery flush', async () => {
    const store = new MemoryRecoveryStore();
    await render({ draftId: 'draft-a', revision: 1 }, false, 0, store);
    await waitFor(() => store.serialized.includes('"draftId":"draft-a"'));

    await render({ draftId: 'draft-a', revision: 2 }, false, 0, store);
    store.serialized = '';
    let preparation: Awaited<ReturnType<typeof prepareHostDisposal>> | undefined;
    await act(async () => {
      preparation = await prepareHostDisposal(window);
    });

    expect(preparation).toEqual({ ready: true, handlerCount: 1, failureCount: 0, timedOut: false });
    expect(store.serialized).toContain('"revision":2');
  });

  it('starts the autosave deadline from the edit instead of after recovery debounce', async () => {
    vi.useFakeTimers();
    const store = new MemoryRecoveryStore();
    const onSave = vi.fn().mockResolvedValue(undefined);
    await render({ draftId: 'draft-a', revision: 2 }, false, 1, store, {
      canAutosave: true,
      autosaveMs: 1_500,
      debounceMs: 350,
      onSave,
    });
    await settleAsyncWork();

    await act(async () => vi.advanceTimersByTimeAsync(1_499));
    expect(onSave).not.toHaveBeenCalled();
    await act(async () => vi.advanceTimersByTimeAsync(1));
    expect(onSave).toHaveBeenCalledOnce();
  });

  it('protects an edit on the recovery deadline even while Web Crypto is still pending', async () => {
    vi.useFakeTimers();
    const store = new MemoryRecoveryStore();
    const digest = vi.spyOn(globalThis.crypto.subtle, 'digest').mockImplementation(
      () => new Promise<ArrayBuffer>(() => undefined),
    );

    await render({ draftId: 'draft-a', revision: 1 }, false, 0, store, {
      debounceMs: 350,
      maxWaitMs: 5_000,
    });
    expect(lifecycle()).toBe('DIRTY');

    await act(async () => vi.advanceTimersByTimeAsync(350));

    expect(store.saveCount).toBe(1);
    expect(store.serialized).toContain('"revision":1');
    expect(store.serialized).toContain('"contentFingerprint":"sha256:');

    digest.mockRestore();
  });

  it('captures continuously changing work no later than the five-second max wait', async () => {
    vi.useFakeTimers();
    const store = new MemoryRecoveryStore();
    await render({ draftId: 'draft-a', revision: 0 }, false, 0, store, {
      debounceMs: 350,
      maxWaitMs: 5_000,
    });
    await settleAsyncWork();

    for (let revision = 1; revision <= 16; revision += 1) {
      await act(async () => vi.advanceTimersByTimeAsync(300));
      await render({ draftId: 'draft-a', revision }, false, 0, store, {
        debounceMs: 350,
        maxWaitMs: 5_000,
      });
      await settleAsyncWork();
    }
    expect(store.saveCount).toBe(0);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(201);
      await crypto.subtle.digest('SHA-256', new Uint8Array([5]));
      await Promise.resolve();
    });
    expect(store.saveCount).toBeGreaterThan(0);
    expect(store.serialized).toContain('"revision":16');
  });

  it('coalesces concurrent manual and lifecycle saves into one authoritative request', async () => {
    const store = new MemoryRecoveryStore();
    let releaseSave: (() => void) | undefined;
    const onSave = vi.fn(() => new Promise<void>((resolve) => {
      releaseSave = resolve;
    }));
    await render({ draftId: 'draft-a', revision: 2 }, false, 1, store, { onSave });
    await waitFor(() => Boolean(host.querySelector('[data-action="save"]')));

    await act(async () => {
      queryAction('save').click();
      queryAction('save').click();
      window.dispatchEvent(new Event('online'));
      await Promise.resolve();
    });
    expect(onSave).toHaveBeenCalledOnce();

    await act(async () => {
      releaseSave?.();
      await Promise.resolve();
    });
  });

  it('marks an unavailable server as offline-recoverable and retries once on reconnection', async () => {
    const store = new MemoryRecoveryStore();
    const onSave = vi.fn<(attempt: WorkspaceSaveAttempt) => Promise<void>>()
      .mockRejectedValueOnce(new Error('Failed to fetch'))
      .mockResolvedValueOnce(undefined);
    await render({ draftId: 'draft-a', revision: 2 }, false, 1, store, {
      canAutosave: true,
      autosaveMs: 60_000,
      onSave,
    });
    await waitFor(() => Boolean(host.querySelector('[data-action="save"]')));

    await act(async () => {
      queryAction('save').click();
      await Promise.resolve();
      await Promise.resolve();
    });
    await waitFor(() => lifecycle() === 'RECOVERABLE_OFFLINE');

    await act(async () => {
      window.dispatchEvent(new Event('online'));
      window.dispatchEvent(new Event('online'));
      await Promise.resolve();
      await Promise.resolve();
    });
    await waitFor(() => onSave.mock.calls.length === 2);
    expect(onSave).toHaveBeenCalledTimes(2);
    expect(onSave.mock.calls[0]?.[0]).toMatchObject({
      contentEpoch: expect.any(Number),
      contentFingerprint: expect.stringMatching(/^sha256:/),
      idempotencyKey: expect.stringMatching(/^graph-save:sha256-/),
    });
    expect(onSave.mock.calls[1]?.[0]).toEqual(onSave.mock.calls[0]?.[0]);
  });

  it('rejects a recovery payload whose stored fingerprint does not match its content', async () => {
    const store = new MemoryRecoveryStore();
    store.serialized = JSON.stringify({
      schemaVersion: 'bloge.authoringRecovery.v1',
      sessionId: 'tampered-session',
      coordinate: { tenantId: 'tenant-a', namespace: 'local', environment: 'test' },
      contentEpoch: 4,
      contentFingerprint: `sha256:${'0'.repeat(64)}`,
      capturedAt: '2026-08-09T08:00:00.000Z',
      expiresAt: '2099-08-09T16:00:00.000Z',
      payload: { draftId: 'tampered', revision: 99 },
    });
    const onRestore = vi.fn();

    await render({ draftId: '', revision: 0 }, false, 0, store, {
      allowRecovery: true,
      onRestore,
    });
    await waitFor(() => store.removeCount === 1);

    expect(onRestore).not.toHaveBeenCalled();
    expect(store.serialized).toBe('');
  });

  it('rejects a structurally invalid payload even when its content fingerprint matches', async () => {
    const store = new MemoryRecoveryStore();
    const malformed = { draftId: 42, revision: 'four' };
    store.serialized = JSON.stringify(createRecoveryEnvelope({
      sessionId: 'malformed-session',
      coordinate: { tenantId: 'tenant-a', namespace: 'local', environment: 'test' },
      contentEpoch: 4,
      contentFingerprint: await sha256Fingerprint(malformed),
      payload: malformed,
      now: new Date(),
    }));
    const onRestore = vi.fn();

    await render({ draftId: '', revision: 0 }, false, 0, store, {
      allowRecovery: true,
      onRestore,
      recoveryPayloadGuard: isHarnessPayload,
    });
    await waitFor(() => store.removeCount === 1);

    expect(onRestore).not.toHaveBeenCalled();
  });

  async function render(
    payload: { draftId: string; revision: number },
    authoritativelySaved: boolean,
    savedRevision: number,
    store: WorkspaceRecoveryStore,
    options: HarnessOptions = {},
  ) {
    await act(async () => {
      if (!root) root = createRoot(host);
      root.render(
        <ContinuityHarness
          payload={payload}
          authoritativelySaved={authoritativelySaved}
          savedRevision={savedRevision}
          store={store}
          options={options}
        />,
      );
    });
  }

  function queryAction(action: string): HTMLButtonElement {
    const button = host.querySelector<HTMLButtonElement>(`[data-action="${action}"]`);
    if (!button) throw new Error(`Action not found: ${action}`);
    return button;
  }

  function lifecycle(): string {
    return host.querySelector('[data-lifecycle]')?.getAttribute('data-lifecycle') ?? '';
  }
});

interface HarnessOptions {
  allowRecovery?: boolean;
  canAutosave?: boolean;
  debounceMs?: number;
  maxWaitMs?: number;
  autosaveMs?: number;
  onRestore?: (payload: { draftId: string; revision: number }, capturedAt: string) => void;
  onSave?: (attempt: WorkspaceSaveAttempt) => Promise<void>;
  recoveryPayloadGuard?: (payload: unknown) => payload is { draftId: string; revision: number };
}

function ContinuityHarness({
  payload,
  authoritativelySaved,
  savedRevision,
  store,
  options,
}: {
  payload: { draftId: string; revision: number };
  authoritativelySaved: boolean;
  savedRevision: number;
  store: WorkspaceRecoveryStore;
  options: HarnessOptions;
}) {
  const continuity = useWorkspaceContinuity({
    enabled: true,
    ready: true,
    allowRecovery: options.allowRecovery ?? false,
    hasContent: true,
    coordinate: { tenantId: 'tenant-a', namespace: 'local', environment: 'test' },
    payload,
    fingerprintValue: payload,
    authoritativelySaved,
    savedRevision,
    canAutosave: options.canAutosave ?? false,
    onRestore: options.onRestore ?? (() => undefined),
    onSave: options.onSave ?? (async () => undefined),
    recoveryPayloadGuard: options.recoveryPayloadGuard,
    recoveryStore: store,
    debounceMs: options.debounceMs ?? 5,
    maxWaitMs: options.maxWaitMs ?? 15,
    autosaveMs: options.autosaveMs ?? 1_500,
  });
  return (
    <div data-lifecycle={continuity.state.lifecycle}>
      <button type="button" data-action="save" onClick={() => void continuity.save()}>Save</button>
    </div>
  );
}

class MemoryRecoveryStore implements WorkspaceRecoveryStore {
  readonly security = 'HOST_ENCRYPTED' as const;
  serialized = '';
  saveCount = 0;
  removeCount = 0;

  async load(_coordinate: RecoveryCoordinate): Promise<string | null> {
    return this.serialized || null;
  }

  async save(_coordinate: RecoveryCoordinate, serializedEnvelope: string): Promise<void> {
    this.saveCount += 1;
    this.serialized = serializedEnvelope;
  }

  async remove(_coordinate: RecoveryCoordinate): Promise<void> {
    this.removeCount += 1;
    this.serialized = '';
  }
}

function isHarnessPayload(value: unknown): value is { draftId: string; revision: number } {
  return Boolean(value)
    && typeof value === 'object'
    && typeof (value as { draftId?: unknown }).draftId === 'string'
    && Number.isSafeInteger((value as { revision?: unknown }).revision);
}

async function settleAsyncWork(): Promise<void> {
  await act(async () => {
    await Promise.resolve();
    await vi.advanceTimersByTimeAsync(0);
    await Promise.resolve();
  });
}

async function waitFor(assertion: () => boolean): Promise<void> {
  const deadline = Date.now() + 1_000;
  while (!assertion()) {
    if (Date.now() > deadline) throw new Error('Timed out waiting for continuity state');
    await act(async () => new Promise((resolve) => setTimeout(resolve, 10)));
  }
}
