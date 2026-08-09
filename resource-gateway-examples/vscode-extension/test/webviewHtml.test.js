'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');

const { buildWebviewHtml } = require('../src/webviewHtml');

test('rewrites only packaged assets and emits a nonce-restricted CSP', () => {
  const vscode = {
    Uri: {
      joinPath(base, ...segments) { return { path: [base.path, ...segments].join('/') }; },
    },
  };
  const webview = {
    cspSource: 'vscode-webview-resource:',
    asWebviewUri(uri) { return `vscode-resource:${uri.path}`; },
  };
  const html = buildWebviewHtml({
    indexHtml: '<!doctype html><html><head><link rel="stylesheet" href="./assets/app.css"></head>'
      + '<body><div id="root"></div><script type="module" src="./assets/app.js"></script></body></html>',
    webview,
    extensionUri: { path: '/extension' },
    vscode,
    nonce: 'fixed-nonce',
  });

  assert.match(html, /Content-Security-Policy/);
  assert.match(html, /default-src 'none'/);
  assert.match(html, /connect-src 'none'/);
  assert.match(html, /script-src vscode-webview-resource: 'nonce-fixed-nonce'/);
  assert.match(html, /<script nonce="fixed-nonce" type="module"/);
  assert.match(html, /vscode-resource:\/extension\/media\/webview\/assets\/app\.js/);
  assert.doesNotMatch(html, /src="\.\/assets/);
});

test('rejects an unrelated or malformed index', () => {
  assert.throws(() => buildWebviewHtml({ indexHtml: '<html></html>' }), /RG.HOST.WEBVIEW.INDEX_INVALID/);
});
