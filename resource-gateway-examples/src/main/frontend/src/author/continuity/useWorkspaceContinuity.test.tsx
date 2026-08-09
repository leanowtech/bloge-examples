// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import type { RecoveryCoordinate, WorkspaceRecoveryStore } from './workspaceContinuity';
import { useWorkspaceContinuity } from './useWorkspaceContinuity';

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

  async function render(
    payload: { draftId: string; revision: number },
    authoritativelySaved: boolean,
    savedRevision: number,
    store: WorkspaceRecoveryStore,
  ) {
    await act(async () => {
      if (!root) root = createRoot(host);
      root.render(
        <ContinuityHarness
          payload={payload}
          authoritativelySaved={authoritativelySaved}
          savedRevision={savedRevision}
          store={store}
        />,
      );
    });
  }
});

function ContinuityHarness({
  payload,
  authoritativelySaved,
  savedRevision,
  store,
}: {
  payload: { draftId: string; revision: number };
  authoritativelySaved: boolean;
  savedRevision: number;
  store: WorkspaceRecoveryStore;
}) {
  const continuity = useWorkspaceContinuity({
    enabled: true,
    ready: true,
    allowRecovery: false,
    hasContent: true,
    coordinate: { tenantId: 'tenant-a', namespace: 'local', environment: 'test' },
    payload,
    fingerprintValue: payload,
    authoritativelySaved,
    savedRevision,
    canAutosave: false,
    onRestore: () => undefined,
    onSave: async () => undefined,
    recoveryStore: store,
    debounceMs: 5,
    maxWaitMs: 15,
  });
  return <div data-lifecycle={continuity.state.lifecycle} />;
}

class MemoryRecoveryStore implements WorkspaceRecoveryStore {
  readonly security = 'HOST_ENCRYPTED' as const;
  serialized = '';

  async load(_coordinate: RecoveryCoordinate): Promise<string | null> {
    return this.serialized || null;
  }

  async save(_coordinate: RecoveryCoordinate, serializedEnvelope: string): Promise<void> {
    this.serialized = serializedEnvelope;
  }

  async remove(_coordinate: RecoveryCoordinate): Promise<void> {
    this.serialized = '';
  }
}

async function waitFor(assertion: () => boolean): Promise<void> {
  const deadline = Date.now() + 1_000;
  while (!assertion()) {
    if (Date.now() > deadline) throw new Error('Timed out waiting for continuity state');
    await act(async () => new Promise((resolve) => setTimeout(resolve, 10)));
  }
}
