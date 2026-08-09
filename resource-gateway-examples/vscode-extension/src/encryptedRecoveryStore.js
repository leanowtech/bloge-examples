'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs/promises');
const path = require('node:path');

const { MAX_RECOVERY_BYTES, normalizeCoordinate, protocolError } = require('./protocol');

const CIPHER_SCHEMA = 'bloge.vscodeRecoveryCipher.v1';
const SECRET_KEY = 'resourceGatewayAuthoring.recovery.aes256.v1';
const MAX_CIPHER_FILE_BYTES = 30 * 1024 * 1024;

class EncryptedRecoveryStore {
  constructor({ secretStorage, storagePath }) {
    if (!secretStorage || typeof secretStorage.get !== 'function' || typeof secretStorage.store !== 'function') {
      throw new TypeError('secretStorage must implement get/store');
    }
    if (typeof storagePath !== 'string' || !path.isAbsolute(storagePath)) {
      throw new TypeError('storagePath must be absolute');
    }
    this.secretStorage = secretStorage;
    this.storagePath = storagePath;
    this.keyPromise = null;
  }

  async load(coordinate) {
    const normalized = normalizeCoordinate(coordinate);
    const file = this.fileFor(normalized);
    let serialized;
    try {
      const stat = await fs.stat(file);
      if (stat.size > MAX_CIPHER_FILE_BYTES) throw protocolError('RG.HOST.RECOVERY.CIPHER_TOO_LARGE');
      serialized = await fs.readFile(file, 'utf8');
    } catch (cause) {
      if (cause && cause.code === 'ENOENT') return null;
      throw cause;
    }
    let cipher;
    try {
      cipher = JSON.parse(serialized);
    } catch {
      throw protocolError('RG.HOST.RECOVERY.CIPHER_INVALID');
    }
    if (!cipher || cipher.schemaVersion !== CIPHER_SCHEMA
        || typeof cipher.iv !== 'string'
        || typeof cipher.tag !== 'string'
        || typeof cipher.ciphertext !== 'string') {
      throw protocolError('RG.HOST.RECOVERY.CIPHER_INVALID');
    }
    try {
      const decipher = crypto.createDecipheriv(
        'aes-256-gcm',
        await this.key(),
        Buffer.from(cipher.iv, 'base64'),
      );
      decipher.setAAD(Buffer.from(scopeIdentity(normalized), 'utf8'));
      decipher.setAuthTag(Buffer.from(cipher.tag, 'base64'));
      const plaintext = Buffer.concat([
        decipher.update(Buffer.from(cipher.ciphertext, 'base64')),
        decipher.final(),
      ]);
      if (plaintext.byteLength > MAX_RECOVERY_BYTES) {
        throw protocolError('RG.HOST.RECOVERY.PAYLOAD_TOO_LARGE');
      }
      return plaintext.toString('utf8');
    } catch (cause) {
      if (cause && cause.code === 'RG.HOST.RECOVERY.PAYLOAD_TOO_LARGE') throw cause;
      throw protocolError('RG.HOST.RECOVERY.DECRYPT_FAILED');
    }
  }

  async save(coordinate, serializedEnvelope) {
    const normalized = normalizeCoordinate(coordinate);
    if (typeof serializedEnvelope !== 'string'
        || Buffer.byteLength(serializedEnvelope) > MAX_RECOVERY_BYTES) {
      throw protocolError('RG.HOST.RECOVERY.PAYLOAD_TOO_LARGE');
    }
    const iv = crypto.randomBytes(12);
    const cipher = crypto.createCipheriv('aes-256-gcm', await this.key(), iv);
    cipher.setAAD(Buffer.from(scopeIdentity(normalized), 'utf8'));
    const ciphertext = Buffer.concat([
      cipher.update(serializedEnvelope, 'utf8'),
      cipher.final(),
    ]);
    const document = JSON.stringify({
      schemaVersion: CIPHER_SCHEMA,
      algorithm: 'AES-256-GCM',
      iv: iv.toString('base64'),
      tag: cipher.getAuthTag().toString('base64'),
      ciphertext: ciphertext.toString('base64'),
    });
    await fs.mkdir(this.storagePath, { recursive: true, mode: 0o700 });
    const target = this.fileFor(normalized);
    const temporary = `${target}.${process.pid}.${crypto.randomUUID()}.tmp`;
    try {
      await fs.writeFile(temporary, document, { encoding: 'utf8', mode: 0o600, flag: 'wx' });
      await fs.rename(temporary, target);
    } finally {
      await fs.rm(temporary, { force: true });
    }
  }

  async remove(coordinate) {
    await fs.rm(this.fileFor(normalizeCoordinate(coordinate)), { force: true });
  }

  fileFor(coordinate) {
    const digest = crypto.createHash('sha256').update(scopeIdentity(coordinate)).digest('hex');
    return path.join(this.storagePath, `${digest}.recovery`);
  }

  async key() {
    if (!this.keyPromise) this.keyPromise = this.loadOrCreateKey();
    return this.keyPromise;
  }

  async loadOrCreateKey() {
    const stored = await this.secretStorage.get(SECRET_KEY);
    if (stored) {
      const key = Buffer.from(stored, 'base64');
      if (key.byteLength !== 32 || key.toString('base64') !== stored) {
        throw protocolError('RG.HOST.RECOVERY.KEY_INVALID');
      }
      return key;
    }
    const key = crypto.randomBytes(32);
    await this.secretStorage.store(SECRET_KEY, key.toString('base64'));
    return key;
  }
}

function scopeIdentity(coordinate) {
  return JSON.stringify([
    coordinate.tenantId,
    coordinate.namespace,
    coordinate.environment,
  ]);
}

module.exports = {
  CIPHER_SCHEMA,
  EncryptedRecoveryStore,
  SECRET_KEY,
  scopeIdentity,
};
