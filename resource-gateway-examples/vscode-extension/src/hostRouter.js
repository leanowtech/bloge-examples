'use strict';

const {
  REQUEST_SCHEMA,
  parseFetchPayload,
  parseReadyMessage,
  parseRecoveryPayload,
  parseWebviewRequest,
  protocolError,
  response,
  stableErrorCode,
} = require('./protocol');
const { offlineOperatorCatalog } = require('./offlineCatalog');
const { createOfflineBusinessMirrorStore } = require('./offlineBusinessMirror');
const { createOfflineReferenceCandidateStore } = require('./offlineReferenceCandidates');

const RESPONSE_BODY_LIMIT = 20 * 1024 * 1024;
const SENSITIVE_REQUEST_HEADERS = new Set([
  'authorization',
  'cookie',
  'host',
  'origin',
  'proxy-authorization',
  'referer',
]);
const FORWARDED_RESPONSE_HEADERS = new Set([
  'cache-control',
  'content-language',
  'content-type',
  'etag',
  'last-modified',
  'retry-after',
]);

function createHostMessageRouter({ postMessage, recoveryStore, fetchHandler, onReady = () => undefined }) {
  if (typeof postMessage !== 'function' || !recoveryStore || typeof fetchHandler !== 'function') {
    throw new TypeError('postMessage, recoveryStore, and fetchHandler are required');
  }
  return async function handleWebviewMessage(message) {
    const ready = parseReadyMessage(message);
    if (ready) {
      onReady(ready);
      return true;
    }
    let request;
    try {
      request = parseWebviewRequest(message);
    } catch (cause) {
      if (message && message.schemaVersion === REQUEST_SCHEMA
          && typeof message.requestId === 'string' && message.requestId.length <= 128) {
        await postMessage(response(message.requestId, false, undefined, stableErrorCode(cause)));
        return true;
      }
      return false;
    }
    if (!request) return false;
    try {
      const payload = await executeRequest(request, recoveryStore, fetchHandler);
      await postMessage(response(request.requestId, true, payload));
    } catch (cause) {
      await postMessage(response(request.requestId, false, undefined, stableErrorCode(cause)));
    }
    return true;
  };
}

async function executeRequest(request, recoveryStore, fetchHandler) {
  switch (request.operation) {
    case 'FETCH':
      return fetchHandler(parseFetchPayload(request.payload));
    case 'RECOVERY_LOAD': {
      const { coordinate } = parseRecoveryPayload(request.payload);
      return { serializedEnvelope: await recoveryStore.load(coordinate) };
    }
    case 'RECOVERY_SAVE': {
      const { coordinate, serializedEnvelope } = parseRecoveryPayload(request.payload, true);
      await recoveryStore.save(coordinate, serializedEnvelope);
      return {};
    }
    case 'RECOVERY_REMOVE': {
      const { coordinate } = parseRecoveryPayload(request.payload);
      await recoveryStore.remove(coordinate);
      return {};
    }
    default:
      throw protocolError('RG.HOST.REQUEST.OPERATION_UNSUPPORTED');
  }
}

function createGatewayFetchHandler({
  remoteBaseUrl = '',
  workspaceTrusted = false,
  allowAdminProxy = false,
  requestTimeoutMs = 10_000,
  fetchImpl = globalThis.fetch,
  tokenProvider = async () => null,
  catalog = offlineOperatorCatalog(),
} = {}) {
  const remoteBase = normalizeRemoteBase(remoteBaseUrl);
  const timeout = Math.min(60_000, Math.max(1_000, Number(requestTimeoutMs) || 10_000));
  const offlineBusinessMirror = createOfflineBusinessMirrorStore();
  const offlineReferenceCandidates = createOfflineReferenceCandidateStore();
  return async function gatewayFetch(request) {
    const target = new URL(request.url, 'https://resource-gateway.invalid');
    const resourcePath = `${target.pathname}${target.search}`;
    if (!target.pathname.startsWith('/api/') && !target.pathname.startsWith('/admin/')) {
      throw protocolError('RG.HOST.FETCH.PATH_DENIED');
    }
    if (target.pathname.startsWith('/admin/') && !allowAdminProxy) {
      return jsonResponse(403, { code: 'RG.HOST.ADMIN_PROXY.DISABLED' }, 'Forbidden');
    }
    if (!remoteBase) {
      const businessMirrorResponse = offlineBusinessMirror(request, target);
      if (businessMirrorResponse) return businessMirrorResponse;
      const referenceCandidateResponse = offlineReferenceCandidates(request, target);
      if (referenceCandidateResponse) return referenceCandidateResponse;
      if (request.method === 'GET' && target.pathname === '/api/visual/operators') {
        return jsonResponse(200, catalog, 'OK');
      }
      return jsonResponse(501, {
        code: 'RG.HOST.OFFLINE_ROUTE.UNAVAILABLE',
        detail: 'This route requires a configured Resource Gateway runtime.',
      }, 'RG.HOST.OFFLINE_ROUTE.UNAVAILABLE');
    }
    if (!workspaceTrusted) {
      return jsonResponse(403, { code: 'RG.HOST.WORKSPACE.UNTRUSTED' }, 'RG.HOST.WORKSPACE.UNTRUSTED');
    }
    if (typeof fetchImpl !== 'function') throw protocolError('RG.HOST.FETCH.UNAVAILABLE');
    const headers = Object.fromEntries(
      Object.entries(request.headers).filter(([name]) => !SENSITIVE_REQUEST_HEADERS.has(name)),
    );
    const token = await tokenProvider();
    if (typeof token === 'string' && token) headers.authorization = `Bearer ${token}`;
    let upstream;
    try {
      upstream = await fetchImpl(new URL(resourcePath, remoteBase), {
        method: request.method,
        headers,
        body: request.method === 'GET' || request.method === 'HEAD' ? undefined : request.body,
        redirect: 'error',
        signal: AbortSignal.timeout(timeout),
      });
    } catch {
      throw protocolError('RG.HOST.FETCH.UPSTREAM_FAILED');
    }
    const body = await readBoundedResponseBody(upstream);
    const responseHeaders = {};
    upstream.headers.forEach((value, name) => {
      if (FORWARDED_RESPONSE_HEADERS.has(name.toLowerCase())) responseHeaders[name] = value;
    });
    return {
      status: upstream.status,
      statusText: upstream.statusText,
      headers: responseHeaders,
      body,
    };
  };
}

async function readBoundedResponseBody(responseValue) {
  const length = Number(responseValue.headers.get('content-length'));
  if (Number.isFinite(length) && length > RESPONSE_BODY_LIMIT) {
    throw protocolError('RG.HOST.FETCH.RESPONSE_TOO_LARGE');
  }
  const body = await responseValue.arrayBuffer();
  if (body.byteLength > RESPONSE_BODY_LIMIT) {
    throw protocolError('RG.HOST.FETCH.RESPONSE_TOO_LARGE');
  }
  return Buffer.from(body).toString('utf8');
}

function normalizeRemoteBase(value) {
  if (typeof value !== 'string' || !value.trim()) return null;
  let parsed;
  try {
    parsed = new URL(value.trim());
  } catch {
    throw protocolError('RG.HOST.REMOTE_BASE.INVALID');
  }
  const loopback = ['127.0.0.1', '::1', 'localhost'].includes(parsed.hostname);
  if (parsed.protocol !== 'https:' && !(parsed.protocol === 'http:' && loopback)) {
    throw protocolError('RG.HOST.REMOTE_BASE.INSECURE');
  }
  parsed.pathname = parsed.pathname.endsWith('/') ? parsed.pathname : `${parsed.pathname}/`;
  parsed.search = '';
  parsed.hash = '';
  return parsed;
}

function jsonResponse(status, body, statusText) {
  return {
    status,
    statusText,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  };
}

module.exports = {
  createGatewayFetchHandler,
  createHostMessageRouter,
  normalizeRemoteBase,
};
