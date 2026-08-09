'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');

const { ResourceGatewayPanelController } = require('../src/panelController');

test('keeps one authoritative panel when startup open races restored deserialization', () => {
  const controller = new ResourceGatewayPanelController(fakeVscode([]), {}, { appendLine() {} });
  let startupDisposed = false;
  const startupPanel = { dispose() { startupDisposed = true; } };
  const restoredPanel = { dispose() { throw new Error('restored panel must remain authoritative'); } };

  controller.adopt(startupPanel);
  controller.adopt(restoredPanel);

  assert.equal(startupDisposed, true);
  assert.equal(controller.current, restoredPanel);
});

test('disposes only after a matching ready receipt', async () => {
  const warnings = [];
  const controller = new ResourceGatewayPanelController(fakeVscode(warnings), {}, { appendLine() {} });
  const sent = [];
  let disposed = false;
  controller.current = {
    webview: { async postMessage(message) { sent.push(message); return true; } },
    dispose() { disposed = true; controller.current = null; },
  };

  const closing = controller.closeSafely(1_000);
  await waitFor(() => sent.length === 1);
  assert.equal(disposed, false);
  assert.equal(controller.receiveDisposalReceipt({
    schemaVersion: 'bloge.vscodeHostDisposalReceipt.v1',
    requestId: sent[0].requestId,
    ready: true,
    handlerCount: 1,
    failureCount: 0,
    timedOut: false,
  }), true);

  assert.equal(await closing, true);
  assert.equal(disposed, true);
  assert.deepEqual(warnings, []);
});

test('fails closed when recovery reports failure or the WebView is unavailable', async () => {
  const warnings = [];
  const controller = new ResourceGatewayPanelController(fakeVscode(warnings), {}, { appendLine() {} });
  const sent = [];
  let disposed = false;
  controller.current = {
    webview: { async postMessage(message) { sent.push(message); return true; } },
    dispose() { disposed = true; },
  };

  const closing = controller.closeSafely(1_000);
  await waitFor(() => sent.length === 1);
  controller.receiveDisposalReceipt({
    schemaVersion: 'bloge.vscodeHostDisposalReceipt.v1',
    requestId: sent[0].requestId,
    ready: false,
    handlerCount: 1,
    failureCount: 1,
    timedOut: false,
  });
  assert.equal(await closing, false);
  assert.equal(disposed, false);
  assert.match(warnings[0], /recovery failed/);

  controller.current.webview.postMessage = async () => false;
  assert.equal(await controller.closeSafely(1_000), false);
  assert.equal(disposed, false);
});

function fakeVscode(warnings) {
  return {
    ViewColumn: { Active: 1 },
    window: {
      showWarningMessage(message) { warnings.push(message); },
    },
  };
}

async function waitFor(assertion) {
  const deadline = Date.now() + 1_000;
  while (!assertion()) {
    if (Date.now() > deadline) throw new Error('Timed out');
    await new Promise((resolve) => setTimeout(resolve, 2));
  }
}
