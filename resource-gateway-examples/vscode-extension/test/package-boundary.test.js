'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const root = path.resolve(__dirname, '..');

test('manifest exposes the runnable lifecycle and credential commands', () => {
  const manifest = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
  const commands = manifest.contributes.commands.map((command) => command.command);
  assert.deepEqual(commands, [
    'resourceGatewayAuthoring.open',
    'resourceGatewayAuthoring.closeSafely',
    'resourceGatewayAuthoring.setRemoteToken',
    'resourceGatewayAuthoring.clearRemoteToken',
  ]);
  assert.equal(manifest.main, './src/extension.js');
  assert.ok(manifest.activationEvents.includes('onUri'));
  assert.ok(manifest.activationEvents.includes('onStartupFinished'));
  assert.equal(manifest.contributes.configuration.properties
    ['resourceGatewayAuthoring.allowAdminProxy'].default, false);
});

test('registers a stable deep link that opens the authoring panel', () => {
  const source = fs.readFileSync(path.join(root, 'src', 'extension.js'), 'utf8');
  assert.match(source, /registerUriHandler/);
  assert.match(source, /uri\.path === '\/open'/);
  assert.match(source, /extensionMode === vscode\.ExtensionMode\.Development/);
  assert.match(source, /setTimeout\(\(\) => controller\.open\(\), 500\)/);
});

test('extension source never logs request bodies, credentials, or recovery plaintext', () => {
  const source = fs.readdirSync(path.join(root, 'src'))
    .filter((file) => file.endsWith('.js'))
    .map((file) => fs.readFileSync(path.join(root, 'src', file), 'utf8'))
    .join('\n');
  assert.doesNotMatch(source, /console\.(?:log|debug|info|warn|error)/);
  assert.doesNotMatch(source, /appendLine\([^)]*(?:body|token|serializedEnvelope)/i);
});
