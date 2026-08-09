'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const { EncryptedRecoveryStore } = require('../src/encryptedRecoveryStore');

test('encrypts recovery at rest and restores the exact scoped envelope', async (t) => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'rg-recovery-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  const secrets = memorySecrets();
  const store = new EncryptedRecoveryStore({ secretStorage: secrets, storagePath: root });
  const coordinate = { tenantId: 'tenant-a', namespace: 'support', environment: 'test' };
  const plaintext = '{"schemaVersion":"bloge.authoringRecovery.v1","payload":{"customer":"private"}}';

  await store.save(coordinate, plaintext);

  const files = await fs.readdir(root);
  assert.equal(files.length, 1);
  const ciphertext = await fs.readFile(path.join(root, files[0]), 'utf8');
  assert.doesNotMatch(ciphertext, /customer|private|authoringRecovery/);
  assert.equal(await store.load(coordinate), plaintext);
  assert.equal(await store.load({ ...coordinate, tenantId: 'tenant-b' }), null);
  assert.equal(secrets.values.size, 1);
});

test('rejects tampered ciphertext and removes only the exact partition', async (t) => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'rg-recovery-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  const store = new EncryptedRecoveryStore({ secretStorage: memorySecrets(), storagePath: root });
  const left = { tenantId: 'tenant-a', namespace: 'support', environment: 'test' };
  const right = { tenantId: 'tenant-a', namespace: 'support', environment: 'staging' };
  await store.save(left, '{"left":true}');
  await store.save(right, '{"right":true}');

  const leftFile = store.fileFor(left);
  const document = JSON.parse(await fs.readFile(leftFile, 'utf8'));
  document.ciphertext = Buffer.from('tampered').toString('base64');
  await fs.writeFile(leftFile, JSON.stringify(document));

  await assert.rejects(store.load(left), { code: 'RG.HOST.RECOVERY.DECRYPT_FAILED' });
  await store.remove(left);
  assert.equal(await store.load(left), null);
  assert.equal(await store.load(right), '{"right":true}');
});

test('rejects invalid coordinates before touching disk', async (t) => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'rg-recovery-'));
  t.after(() => fs.rm(root, { recursive: true, force: true }));
  const store = new EncryptedRecoveryStore({ secretStorage: memorySecrets(), storagePath: root });

  await assert.rejects(
    store.save({ tenantId: '', namespace: 'support', environment: 'test' }, '{}'),
    { code: 'RG.HOST.RECOVERY.COORDINATE_INVALID' },
  );
  await assert.rejects(
    store.save({ tenantId: 'tenant-a', namespace: '../escape', environment: '' }, '{}'),
    { code: 'RG.HOST.RECOVERY.COORDINATE_INVALID' },
  );
  assert.deepEqual(await fs.readdir(root), []);
});

function memorySecrets() {
  const values = new Map();
  return {
    values,
    async get(key) { return values.get(key); },
    async store(key, value) { values.set(key, value); },
  };
}
