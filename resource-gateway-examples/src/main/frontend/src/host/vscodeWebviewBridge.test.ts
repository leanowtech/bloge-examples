// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest';

import {
  HOST_WILL_DISPOSE_EVENT,
  joinHostDisposal,
  signalHostWorkspaceReady,
} from './hostLifecycle';
import { VsCodeWebviewBridge, type HostRequest } from './vscodeWebviewBridge';

describe('VsCodeWebviewBridge', () => {
  const bridges: VsCodeWebviewBridge[] = [];

  afterEach(() => {
    while (bridges.length > 0) bridges.pop()?.dispose();
  });

  it('projects fetch through one correlated host request without logging payloads', async () => {
    const messages: unknown[] = [];
    const bridge = track(new VsCodeWebviewBridge({ postMessage: (message) => messages.push(message) }));

    const responsePromise = bridge.transport('/api/visual/operators', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer test' },
      body: JSON.stringify({ graph: 'loan' }),
    });
    await waitFor(() => messages.length === 1);
    const request = messages[0] as HostRequest;
    expect(request).toMatchObject({
      schemaVersion: 'bloge.vscodeWebviewRequest.v1',
      operation: 'FETCH',
      payload: {
        method: 'POST',
        body: '{"graph":"loan"}',
      },
    });

    respond(request, {
      status: 201,
      headers: { 'Content-Type': 'application/json' },
      body: '{"accepted":true}',
    });
    const response = await responsePromise;
    expect(response.status).toBe(201);
    expect(await response.json()).toEqual({ accepted: true });
  });

  it('uses the host encrypted store for recovery load, save, and removal', async () => {
    const messages: unknown[] = [];
    const bridge = track(new VsCodeWebviewBridge({ postMessage: (message) => messages.push(message) }));
    const coordinate = { tenantId: 'tenant-a', namespace: 'support', environment: 'test' };

    const loadPromise = bridge.recoveryStore.load(coordinate);
    const loadRequest = messages[messages.length - 1] as HostRequest;
    respond(loadRequest, { serializedEnvelope: '{"schemaVersion":"bloge.authoringRecovery.v1"}' });
    expect(await loadPromise).toContain('authoringRecovery');

    const savePromise = bridge.recoveryStore.save(coordinate, '{"draft":1}');
    const saveRequest = messages[messages.length - 1] as HostRequest;
    expect(saveRequest).toMatchObject({ operation: 'RECOVERY_SAVE' });
    respond(saveRequest, {});
    await savePromise;

    const removePromise = bridge.recoveryStore.remove(coordinate);
    const removeRequest = messages[messages.length - 1] as HostRequest;
    expect(removeRequest).toMatchObject({ operation: 'RECOVERY_REMOVE' });
    respond(removeRequest, {});
    await removePromise;
    expect(bridge.recoveryStore.security).toBe('HOST_ENCRYPTED');
  });

  it('reports route readiness without sending authoring payloads', () => {
    const messages: unknown[] = [];
    track(new VsCodeWebviewBridge({ postMessage: (message) => messages.push(message) }));

    signalHostWorkspaceReady('author');

    expect(messages).toHaveLength(1);
    expect(messages[0]).toMatchObject({
      schemaVersion: 'bloge.vscodeWebviewReady.v1',
      route: 'author',
    });
    expect(messages[0]).not.toHaveProperty('payload');
  });

  it('acknowledges host disposal only after every authoring surface has flushed', async () => {
    const messages: unknown[] = [];
    track(new VsCodeWebviewBridge({ postMessage: (message) => messages.push(message) }));
    let release: (() => void) | undefined;
    const pending = new Promise<void>((resolve) => { release = resolve; });
    const listener = (event: Event) => joinHostDisposal(event, pending);
    window.addEventListener(HOST_WILL_DISPOSE_EVENT, listener);

    window.dispatchEvent(new MessageEvent('message', {
      data: { schemaVersion: 'bloge.vscodeHostWillDispose.v1', requestId: 'dispose-1' },
    }));
    await Promise.resolve();
    expect(messages).toHaveLength(0);

    release?.();
    await waitFor(() => messages.length === 1);
    expect(messages[0]).toEqual({
      schemaVersion: 'bloge.vscodeHostDisposalReceipt.v1',
      requestId: 'dispose-1',
      ready: true,
      handlerCount: 1,
      failureCount: 0,
      timedOut: false,
    });
    window.removeEventListener(HOST_WILL_DISPOSE_EVENT, listener);
  });

  it('fails closed when recovery reports false or exceeds the host deadline', async () => {
    const failedMessages: unknown[] = [];
    const failedBridge = track(new VsCodeWebviewBridge({ postMessage: (message) => failedMessages.push(message) }));
    const failedListener = (event: Event) => joinHostDisposal(event, Promise.resolve(false));
    window.addEventListener(HOST_WILL_DISPOSE_EVENT, failedListener);
    window.dispatchEvent(new MessageEvent('message', {
      data: { schemaVersion: 'bloge.vscodeHostWillDispose.v1', requestId: 'dispose-failed' },
    }));
    await waitFor(() => failedMessages.length === 1);
    expect(failedMessages[0]).toMatchObject({
      requestId: 'dispose-failed',
      ready: false,
      failureCount: 1,
      timedOut: false,
    });
    window.removeEventListener(HOST_WILL_DISPOSE_EVENT, failedListener);
    failedBridge.dispose();

    const timeoutMessages: unknown[] = [];
    track(new VsCodeWebviewBridge({ postMessage: (message) => timeoutMessages.push(message) }, window, 5));
    const timeoutListener = (event: Event) => joinHostDisposal(event, new Promise(() => undefined));
    window.addEventListener(HOST_WILL_DISPOSE_EVENT, timeoutListener);
    window.dispatchEvent(new MessageEvent('message', {
      data: { schemaVersion: 'bloge.vscodeHostWillDispose.v1', requestId: 'dispose-timeout' },
    }));
    await waitFor(() => timeoutMessages.length === 1);
    expect(timeoutMessages[0]).toMatchObject({
      requestId: 'dispose-timeout',
      ready: false,
      failureCount: 1,
      timedOut: true,
    });
    window.removeEventListener(HOST_WILL_DISPOSE_EVENT, timeoutListener);
  });

  function track(bridge: VsCodeWebviewBridge) {
    bridges.push(bridge);
    return bridge;
  }
});

function respond(request: HostRequest, payload: unknown) {
  window.dispatchEvent(new MessageEvent('message', {
    data: {
      schemaVersion: 'bloge.vscodeWebviewResponse.v1',
      requestId: request.requestId,
      ok: true,
      payload,
    },
  }));
}

async function waitFor(assertion: () => boolean): Promise<void> {
  const deadline = Date.now() + 1_000;
  while (!assertion()) {
    if (Date.now() > deadline) throw new Error('Timed out waiting for host bridge');
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
}
