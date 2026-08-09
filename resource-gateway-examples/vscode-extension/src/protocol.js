'use strict';

const REQUEST_SCHEMA = 'bloge.vscodeWebviewRequest.v1';
const RESPONSE_SCHEMA = 'bloge.vscodeWebviewResponse.v1';
const DISPOSE_SCHEMA = 'bloge.vscodeHostWillDispose.v1';
const DISPOSE_RECEIPT_SCHEMA = 'bloge.vscodeHostDisposalReceipt.v1';
const READY_SCHEMA = 'bloge.vscodeWebviewReady.v1';
const MAX_REQUEST_BODY_BYTES = 10 * 1024 * 1024;
const MAX_RECOVERY_BYTES = 20 * 1024 * 1024;
const REQUEST_ID = /^[A-Za-z0-9][A-Za-z0-9._:#-]{0,127}$/;
const OPERATIONS = new Set(['FETCH', 'RECOVERY_LOAD', 'RECOVERY_SAVE', 'RECOVERY_REMOVE']);

function parseWebviewRequest(value) {
  if (!isRecord(value) || value.schemaVersion !== REQUEST_SCHEMA) return null;
  if (!REQUEST_ID.test(value.requestId || '') || !OPERATIONS.has(value.operation)) {
    throw protocolError('RG.HOST.REQUEST.INVALID');
  }
  return {
    schemaVersion: REQUEST_SCHEMA,
    requestId: value.requestId,
    operation: value.operation,
    payload: value.payload,
  };
}

function parseFetchPayload(value) {
  if (!isRecord(value) || typeof value.url !== 'string' || value.url.length > 4096) {
    throw protocolError('RG.HOST.FETCH.INVALID');
  }
  const method = typeof value.method === 'string' ? value.method.toUpperCase() : 'GET';
  if (!['GET', 'HEAD', 'POST', 'PUT', 'PATCH', 'DELETE'].includes(method)) {
    throw protocolError('RG.HOST.FETCH.METHOD_DENIED');
  }
  const headers = normalizeHeaders(value.headers);
  const body = value.body === null || value.body === undefined ? null : value.body;
  if (body !== null && (typeof body !== 'string' || Buffer.byteLength(body) > MAX_REQUEST_BODY_BYTES)) {
    throw protocolError('RG.HOST.FETCH.BODY_TOO_LARGE');
  }
  return { url: value.url, method, headers, body };
}

function parseRecoveryPayload(value, requireEnvelope = false) {
  if (!isRecord(value)) throw protocolError('RG.HOST.RECOVERY.INVALID');
  const coordinate = normalizeCoordinate(value.coordinate);
  if (!requireEnvelope) return { coordinate };
  if (typeof value.serializedEnvelope !== 'string'
      || Buffer.byteLength(value.serializedEnvelope) > MAX_RECOVERY_BYTES) {
    throw protocolError('RG.HOST.RECOVERY.PAYLOAD_TOO_LARGE');
  }
  return { coordinate, serializedEnvelope: value.serializedEnvelope };
}

function normalizeCoordinate(value) {
  if (!isRecord(value)) throw protocolError('RG.HOST.RECOVERY.COORDINATE_INVALID');
  const coordinate = {};
  for (const field of ['tenantId', 'namespace', 'environment']) {
    const normalized = typeof value[field] === 'string' ? value[field].trim() : '';
    if (!normalized || normalized.length > 128) {
      throw protocolError('RG.HOST.RECOVERY.COORDINATE_INVALID');
    }
    coordinate[field] = normalized;
  }
  if (typeof value.draftId === 'string' && value.draftId.trim()) {
    if (value.draftId.trim().length > 256) {
      throw protocolError('RG.HOST.RECOVERY.COORDINATE_INVALID');
    }
    coordinate.draftId = value.draftId.trim();
  }
  return coordinate;
}

function parseReadyMessage(value) {
  if (!isRecord(value) || value.schemaVersion !== READY_SCHEMA) return null;
  if (typeof value.route !== 'string' || value.route.length > 64 || !Number.isFinite(value.measuredAt)) {
    throw protocolError('RG.HOST.READY.INVALID');
  }
  return { schemaVersion: READY_SCHEMA, route: value.route, measuredAt: value.measuredAt };
}

function parseDisposalReceipt(value, requestId) {
  if (!isRecord(value)
      || value.schemaVersion !== DISPOSE_RECEIPT_SCHEMA
      || value.requestId !== requestId
      || typeof value.ready !== 'boolean'
      || !Number.isInteger(value.handlerCount)
      || !Number.isInteger(value.failureCount)
      || typeof value.timedOut !== 'boolean') {
    return null;
  }
  return value;
}

function response(requestId, ok, payload, errorCode) {
  return {
    schemaVersion: RESPONSE_SCHEMA,
    requestId,
    ok,
    ...(ok ? { payload } : { errorCode: errorCode || 'RG.HOST.REQUEST.FAILED' }),
  };
}

function protocolError(code) {
  const error = new Error(code);
  error.code = code;
  return error;
}

function stableErrorCode(cause) {
  return cause && typeof cause.code === 'string' && /^RG\.[A-Z0-9._-]+$/.test(cause.code)
    ? cause.code
    : 'RG.HOST.INTERNAL';
}

function normalizeHeaders(value) {
  if (value === undefined || value === null) return {};
  if (!isRecord(value) || Object.keys(value).length > 64) {
    throw protocolError('RG.HOST.FETCH.HEADERS_INVALID');
  }
  const headers = {};
  for (const [name, raw] of Object.entries(value)) {
    if (!/^[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}$/.test(name)
        || typeof raw !== 'string' || raw.length > 8192) {
      throw protocolError('RG.HOST.FETCH.HEADERS_INVALID');
    }
    headers[name.toLowerCase()] = raw;
  }
  return headers;
}

function isRecord(value) {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

module.exports = {
  DISPOSE_RECEIPT_SCHEMA,
  DISPOSE_SCHEMA,
  MAX_RECOVERY_BYTES,
  READY_SCHEMA,
  REQUEST_SCHEMA,
  RESPONSE_SCHEMA,
  normalizeCoordinate,
  parseDisposalReceipt,
  parseFetchPayload,
  parseReadyMessage,
  parseRecoveryPayload,
  parseWebviewRequest,
  protocolError,
  response,
  stableErrorCode,
};
