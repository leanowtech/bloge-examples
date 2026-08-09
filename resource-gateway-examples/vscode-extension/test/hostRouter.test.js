'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');

const { createGatewayFetchHandler, createHostMessageRouter } = require('../src/hostRouter');

const coordinate = { tenantId: 'tenant-a', namespace: 'support', environment: 'test' };

test('serves a complete offline author catalog and reports route readiness', async () => {
  const responses = [];
  const ready = [];
  const router = createHostMessageRouter({
    postMessage: async (message) => responses.push(message),
    recoveryStore: memoryRecoveryStore(),
    fetchHandler: createGatewayFetchHandler(),
    onReady: (message) => ready.push(message),
  });

  assert.equal(await router({
    schemaVersion: 'bloge.vscodeWebviewReady.v1',
    route: 'author',
    measuredAt: 12.5,
  }), true);
  assert.equal(ready[0].route, 'author');

  await router(request('catalog', 'FETCH', {
    url: 'vscode-webview://panel/api/visual/operators',
    method: 'GET',
    headers: {},
    body: null,
  }));
  const payload = JSON.parse(responses[0].payload.body);
  assert.equal(responses[0].ok, true);
  assert.equal(responses[0].payload.status, 200);
  assert.equal(payload.operators.length, 11);
  assert.deepEqual(payload.builtInFunctions.map((fn) => fn.name), ['coalesce', 'toNumber', 'round']);
});

test('round-trips encrypted-store operations through correlated responses', async () => {
  const responses = [];
  const recoveryStore = memoryRecoveryStore();
  const router = createHostMessageRouter({
    postMessage: async (message) => responses.push(message),
    recoveryStore,
    fetchHandler: createGatewayFetchHandler(),
  });

  await router(request('save', 'RECOVERY_SAVE', { coordinate, serializedEnvelope: '{"draft":1}' }));
  await router(request('load', 'RECOVERY_LOAD', { coordinate }));
  await router(request('remove', 'RECOVERY_REMOVE', { coordinate }));

  assert.deepEqual(responses.map((item) => [item.requestId, item.ok]), [
    ['save', true], ['load', true], ['remove', true],
  ]);
  assert.equal(responses[1].payload.serializedEnvelope, '{"draft":1}');
  assert.equal(await recoveryStore.load(coordinate), null);
});

test('rejects malformed protocol requests immediately with a stable code', async () => {
  const responses = [];
  const router = createHostMessageRouter({
    postMessage: async (message) => responses.push(message),
    recoveryStore: memoryRecoveryStore(),
    fetchHandler: createGatewayFetchHandler(),
  });

  assert.equal(await router(request('bad-op', 'EXECUTE', {})), true);
  assert.equal(responses[0].ok, false);
  assert.equal(responses[0].errorCode, 'RG.HOST.REQUEST.INVALID');
});

test('keeps credentials in the host and rejects insecure or untrusted upstreams', async () => {
  assert.throws(
    () => createGatewayFetchHandler({ remoteBaseUrl: 'http://corp.example.test' }),
    { code: 'RG.HOST.REMOTE_BASE.INSECURE' },
  );
  let called = false;
  const untrusted = createGatewayFetchHandler({
    remoteBaseUrl: 'https://gateway.example.test',
    workspaceTrusted: false,
    fetchImpl: async () => { called = true; },
  });
  const denied = await untrusted({ url: '/api/visual/operators', method: 'GET', headers: {}, body: null });
  assert.equal(denied.status, 403);
  assert.equal(called, false);

  let upstreamRequest;
  const trusted = createGatewayFetchHandler({
    remoteBaseUrl: 'https://gateway.example.test/root',
    workspaceTrusted: true,
    tokenProvider: async () => 'host-owned-token',
    fetchImpl: async (url, init) => {
      upstreamRequest = { url: String(url), init };
      return new Response('{"ok":true}', {
        status: 200,
        headers: { 'Content-Type': 'application/json', 'Set-Cookie': 'secret=1' },
      });
    },
  });
  const accepted = await trusted({
    url: '/api/visual/operators?tenantId=tenant-a',
    method: 'GET',
    headers: { authorization: 'webview-forged', cookie: 'unsafe=1', accept: 'application/json' },
    body: null,
  });
  assert.equal(accepted.status, 200);
  assert.equal(upstreamRequest.init.headers.authorization, 'Bearer host-owned-token');
  assert.equal(upstreamRequest.init.headers.cookie, undefined);
  assert.match(upstreamRequest.url, /^https:\/\/gateway\.example\.test\/api\/visual\/operators\?/);
  assert.equal(accepted.headers['set-cookie'], undefined);
});

test('blocks admin and non-Resource-Gateway paths by default', async () => {
  const handler = createGatewayFetchHandler();
  const admin = await handler({ url: '/admin/operator-libraries', method: 'GET', headers: {}, body: null });
  assert.equal(admin.status, 403);
  await assert.rejects(
    handler({ url: '/file:///etc/passwd', method: 'GET', headers: {}, body: null }),
    { code: 'RG.HOST.FETCH.PATH_DENIED' },
  );
});

function request(requestId, operation, payload) {
  return { schemaVersion: 'bloge.vscodeWebviewRequest.v1', requestId, operation, payload };
}

function memoryRecoveryStore() {
  const values = new Map();
  const key = (value) => JSON.stringify([value.tenantId, value.namespace, value.environment]);
  return {
    async load(value) { return values.get(key(value)) ?? null; },
    async save(value, serialized) { values.set(key(value), serialized); },
    async remove(value) { values.delete(key(value)); },
  };
}
