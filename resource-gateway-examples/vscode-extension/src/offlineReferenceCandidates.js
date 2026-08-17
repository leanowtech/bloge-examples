'use strict';

const crypto = require('node:crypto');

const SEARCH_SCHEMA = 'bloge.referenceSearchRequest.v1';
const PAGE_SCHEMA = 'bloge.referencePage.v1';
const RESOLVE_COMMAND_SCHEMA = 'bloge.referenceResolveCommand.v1';
const RESOLVE_RESULT_SCHEMA = 'bloge.referenceResolveResult.v1';
const CURSOR_VERSION = 'v1';
const DEFAULT_LIMIT = 20;
const MAX_LIMIT = 100;
const MAX_QUERY_LENGTH = 200;
const MAX_CURSOR_LENGTH = 4096;
const OFFLINE_SCOPE = Object.freeze({
  tenantId: 'offline-demo',
  organizationId: 'customer-service',
  projectId: 'business-mirror',
  environmentId: 'offline',
  region: 'local',
});
const AUTHORITY = 'resource-gateway://demo/business-catalog/loan-decision';
const OWNER = Object.freeze({ stableId: 'credit-service-design', displayName: 'Credit Service Design' });

function createOfflineReferenceCandidateStore() {
  const candidates = offlineReferenceCandidates();
  const generation = catalogGeneration(candidates);
  const generationNumber = Number(generation);

  return function handleOfflineReferenceCandidates(request, target) {
    if (target.pathname !== '/api/visual/reference-candidates'
        && target.pathname !== '/api/visual/reference-candidates:resolve') {
      return null;
    }
    if (target.pathname.endsWith(':resolve')) {
      if (request.method !== 'POST') return jsonResponse(405, { code: 'RG.REFERENCE.METHOD_NOT_ALLOWED' });
      return resolveCandidate(request, candidates);
    }
    if (request.method !== 'GET') return jsonResponse(405, { code: 'RG.REFERENCE.METHOD_NOT_ALLOWED' });
    return searchCandidates(target, candidates, generation, generationNumber);
  };
}

function searchCandidates(target, candidates, generation, generationNumber) {
  try {
    const kind = textParameter(target, 'kind');
    const query = textParameter(target, 'query');
    const cursor = textParameter(target, 'cursor');
    const limit = boundedLimit(textParameter(target, 'limit'));
    const lifecycle = enumParameter(target, 'lifecycle', ['DRAFT', 'ACTIVE', 'DEPRECATED', 'SUPERSEDED']);
    const compatibleWith = enumParameter(
      target, 'compatibleWith', ['COMPATIBLE', 'REVIEW', 'INCOMPATIBLE', 'UNKNOWN']);
    if (query.length > MAX_QUERY_LENGTH) throw invalid('query exceeds 200 characters');
    if (cursor.length > MAX_CURSOR_LENGTH) throw invalid('cursor exceeds 4096 characters');

    const queryFingerprint = sha256([
      SEARCH_SCHEMA, kind, query, String(limit),
      OFFLINE_SCOPE.tenantId, OFFLINE_SCOPE.organizationId, OFFLINE_SCOPE.projectId,
      OFFLINE_SCOPE.environmentId, OFFLINE_SCOPE.region, lifecycle, compatibleWith,
    ].join('\u0000'));
    const decodedCursor = decodeCursor(cursor);
    if (decodedCursor) {
      if (decodedCursor.queryFingerprint !== queryFingerprint) {
        return problem(409, 'RG.REFERENCE.QUERY_FINGERPRINT_MISMATCH',
          'cursor belongs to a different search query');
      }
      if (decodedCursor.generation !== generation.toString()) {
        return problem(409, 'RG.REFERENCE.CURSOR_STALE',
          'catalog generation changed while paging');
      }
    }

    const ranked = rankCandidates(candidates, { kind, query, lifecycle, compatibleWith });
    const start = startAfter(ranked, decodedCursor);
    const end = Math.min(start + limit, ranked.length);
    const items = ranked.slice(start, end);
    const nextCursor = end < ranked.length
      ? encodeCursor({ generation, queryFingerprint, lastCoordinate: coordinate(items[items.length - 1]) })
      : '';
    return jsonResponse(200, {
      schemaVersion: PAGE_SCHEMA,
      items,
      nextCursor,
      queryFingerprint,
      catalogGeneration: generationNumber,
    });
  } catch (error) {
    if (error && error.code === 'RG.REFERENCE.CURSOR_STALE') {
      return problem(409, error.code, error.message);
    }
    return problem(400, 'RG.REFERENCE.REQUEST_INVALID', error?.message || 'invalid reference search');
  }
}

function resolveCandidate(request, candidates) {
  let command;
  try {
    command = parseResolveCommand(request.body);
  } catch (error) {
    return problem(400, 'RG.REFERENCE.REQUEST_INVALID', error.message);
  }
  const candidate = candidates.find((item) => item.kind === command.kind && item.id === command.id);
  if (!candidate) {
    return jsonResponse(200, resolveResult('NOT_FOUND', null, 'RG.REFERENCE.NOT_FOUND'));
  }
  if (candidate.revision !== command.revision || candidate.fingerprint !== command.fingerprint) {
    return jsonResponse(200, resolveResult('DRIFTED', candidate, 'RG.REFERENCE.DRIFTED'));
  }
  return jsonResponse(200, resolveResult('RESOLVED', candidate, ''));
}

function parseResolveCommand(body) {
  if (typeof body !== 'string' || !body.trim()) throw new Error('resolve body is required');
  let value;
  try {
    value = JSON.parse(body);
  } catch {
    throw new Error('resolve body is invalid JSON');
  }
  if (!isRecord(value) || value.schemaVersion !== RESOLVE_COMMAND_SCHEMA) {
    throw new Error('Unsupported ReferenceResolveCommand schemaVersion');
  }
  const kind = requiredText(value.kind, 'kind');
  const id = requiredText(value.id, 'id');
  if (!Number.isInteger(value.revision) || value.revision < 1) {
    throw new Error('revision must be positive');
  }
  const fingerprint = requiredText(value.fingerprint, 'fingerprint');
  requiredText(value.intendedUse, 'intendedUse');
  // Deliberately ignore an optional body scope. Authorization is always OFFLINE_SCOPE.
  return { kind, id, revision: value.revision, fingerprint };
}

function resolveResult(status, candidate, errorCode) {
  return {
    schemaVersion: RESOLVE_RESULT_SCHEMA,
    status,
    candidate,
    errorCode,
  };
}

function rankCandidates(candidates, filters) {
  const query = filters.query.toLowerCase();
  return candidates
    .filter((candidate) => matchesKind(filters.kind, candidate.kind))
    .filter((candidate) => !filters.lifecycle || candidate.lifecycle === filters.lifecycle)
    .filter((candidate) => !filters.compatibleWith || candidate.compatibility === filters.compatibleWith)
    .filter((candidate) => matchesQuery(candidate, query))
    .slice()
    .sort((left, right) => relevance(right, query) - relevance(left, query)
      || lifecycleRank(right) - lifecycleRank(left)
      || compatibilityRank(right) - compatibilityRank(left)
      || compareCoordinates(left, right));
}

function matchesKind(requestedKind, candidateKind) {
  if (!requestedKind || requestedKind === candidateKind) return true;
  if (requestedKind === 'SERVICE_CARRIER') return ['SOP', 'AGENT', 'WORKFLOW'].includes(candidateKind);
  if (requestedKind === 'CHANNEL') return candidateKind === 'CHANNEL_APPLICATION';
  return false;
}

function matchesQuery(candidate, query) {
  if (!query) return true;
  return [candidate.id, candidate.displayName, candidate.description, ...candidate.labels]
    .some((value) => value.toLowerCase().includes(query));
}

function relevance(candidate, query) {
  if (!query) return 0;
  const id = candidate.id.toLowerCase();
  const name = candidate.displayName.toLowerCase();
  if (id === query) return 10_000;
  if (name === query) return 8_000;
  if (id.startsWith(query)) return 7_000;
  if (name.startsWith(query)) return 6_000;
  if (candidate.labels.some((label) => label.toLowerCase().startsWith(query))) return 5_000;
  return 1_000;
}

function lifecycleRank(candidate) {
  return { ACTIVE: 4, DRAFT: 3, DEPRECATED: 2, SUPERSEDED: 1 }[candidate.lifecycle] || 0;
}

function compatibilityRank(candidate) {
  return { COMPATIBLE: 4, REVIEW: 3, UNKNOWN: 2, INCOMPATIBLE: 1 }[candidate.compatibility] || 0;
}

function compareCoordinates(left, right) {
  return coordinate(left).localeCompare(coordinate(right));
}

function startAfter(candidates, cursor) {
  if (!cursor) return 0;
  const index = candidates.findIndex((candidate) => coordinate(candidate) === cursor.lastCoordinate);
  if (index < 0) throw protocolError('RG.REFERENCE.CURSOR_STALE', 'cursor position is no longer present');
  return index + 1;
}

function encodeCursor(cursor) {
  const encodedCoordinate = base64Url(cursor.lastCoordinate);
  return base64Url([CURSOR_VERSION, cursor.generation.toString(), cursor.queryFingerprint, encodedCoordinate].join('|'));
}

function decodeCursor(value) {
  if (!value) return null;
  try {
    const parts = fromBase64Url(value).split('|');
    if (parts.length !== 4 || parts[0] !== CURSOR_VERSION || !parts[1] || !parts[2] || !parts[3]) {
      throw new Error('invalid cursor');
    }
    BigInt(parts[1]);
    return {
      generation: parts[1],
      queryFingerprint: parts[2],
      lastCoordinate: fromBase64Url(parts[3]),
    };
  } catch {
    throw protocolError('RG.REFERENCE.CURSOR_STALE', 'cursor is malformed or expired');
  }
}

function offlineReferenceCandidates() {
  const definitions = [
    ['BUSINESS_DOMAIN', 'credit-decision', 'Credit decision',
      'Customer-service business domain for policy-compliant lending decisions.', ['loan', 'customer-service', 'domain']],
    ['PROBLEM_TAXONOMY', 'loan-decision-problems', 'Loan decision problems',
      'Taxonomy for approval, rejection, fallback, timeout, and manual-review problems.', ['problem', 'taxonomy', 'loan']],
    ['OWNER', 'credit-service-design', 'Credit Service Design',
      'Business owner responsible for the loan decision service assets.', ['owner', 'accountable']],
    ['PACKAGE_CONTRACT', 'loan-decision-contract-v1', 'Loan decision package contract',
      'Stable contract boundary for the loan-decision resource package.', ['contract', 'package', 'v1']],
    ['STATE_MODEL', 'loan-decision-state-v1', 'Loan decision state model',
      'Decision lifecycle states used by service and customer-support workflows.', ['state', 'lifecycle']],
    ['EFFECT_MODEL', 'loan-decision-effect-v1', 'Loan decision effect model',
      'Allowed read, review, and write-effect classification for the decision flow.', ['effect', 'governance', 'read-only']],
    ['SOLUTION', 'loan-decision-fallback-solution', 'Loan decision fallback solution',
      'Reviewed solution for primary credit timeout and double-failure degradation.', ['solution', 'fallback', 'manual-review']],
    ['AGENT', 'loan-policy-agent', 'Loan policy service carrier',
      'Service carrier that presents the verified decision solution to support channels.', ['agent', 'service-carrier']],
    ['CHANNEL_APPLICATION', 'customer-support-chat', 'Customer support chat',
      'Customer-support interaction channel used by the demonstration workflow.', ['channel', 'chat', 'customer-service']],
    ['SCENARIO_INVENTORY', 'loan-policy-obligations', 'Loan policy scenario inventory',
      'Frozen inventory of correctness obligations for the loan decision flow.', ['inventory', 'obligation', 'correctness']],
    ['SCENARIO_PACK', 'loan-policy-regression', 'Loan policy regression pack',
      'Scenario pack covering golden, negative, boundary, and fallback behavior.', ['scenario', 'regression', 'golden']],
    ['FIDELITY_INVENTORY', 'loan-policy-fidelity', 'Loan policy fidelity inventory',
      'Inventory of business assumptions and evidence needed for high-fidelity rehearsal.', ['fidelity', 'evidence', 'simulation']],
    ['OUTCOME_DEFINITION', 'loan-decision-outcomes', 'Loan decision outcomes',
      'Expected business outcomes for approval, rejection, fallback, and manual review.', ['outcome', 'assertion', 'business-correctness']],
  ];
  return Object.freeze(definitions.map(([kind, id, displayName, description, labels]) => Object.freeze({
    schemaVersion: 'bloge.referenceCandidate.v1',
    kind,
    id,
    displayName,
    description,
    revision: 1,
    fingerprint: sha256(`${kind}\u0000${id}\u0000${displayName}`).replace(/^/, 'sha256:'),
    authority: AUTHORITY,
    scope: OFFLINE_SCOPE,
    lifecycle: 'ACTIVE',
    owner: OWNER,
    labels: Object.freeze(labels),
    compatibility: 'COMPATIBLE',
    disabledReasonCode: '',
  })));
}

function catalogGeneration(candidates) {
  const canonical = candidates.map(coordinate).sort().reduce((left, right) => `${left}\n${right}`, '');
  return BigInt(`0x${crypto.createHash('sha256').update(canonical).digest('hex').slice(0, 16)}`)
    & ((1n << 63n) - 1n);
}

function coordinate(candidate) {
  return [candidate.kind, candidate.id, String(candidate.revision), candidate.fingerprint].join('|');
}

function textParameter(target, name) {
  const value = target.searchParams.get(name) || '';
  return value.trim();
}

function boundedLimit(value) {
  if (!value) return DEFAULT_LIMIT;
  if (!/^\d+$/.test(value)) throw invalid('limit must be an integer');
  const limit = Number(value);
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > MAX_LIMIT) {
    throw invalid('limit must be between 1 and 100');
  }
  return limit;
}

function enumParameter(target, name, allowed) {
  const value = textParameter(target, name).toUpperCase();
  if (value && !allowed.includes(value)) throw invalid(`${name} is not supported`);
  return value;
}

function requiredText(value, field) {
  if (typeof value !== 'string' || !value.trim()) throw new Error(`${field} must not be blank`);
  return value.trim();
}

function invalid(message) {
  const error = new Error(message);
  error.code = 'RG.REFERENCE.REQUEST_INVALID';
  return error;
}

function protocolError(code, message) {
  const error = new Error(message);
  error.code = code;
  return error;
}

function sha256(value) {
  return crypto.createHash('sha256').update(value, 'utf8').digest('hex');
}

function base64Url(value) {
  return Buffer.from(value, 'utf8').toString('base64url');
}

function fromBase64Url(value) {
  return Buffer.from(value, 'base64url').toString('utf8');
}

function jsonResponse(status, body) {
  return {
    status,
    statusText: status >= 400 ? 'Bad Request' : 'OK',
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' },
    body: JSON.stringify(body),
  };
}

function problem(status, code, detail) {
  return jsonResponse(status, { code, detail });
}

function isRecord(value) {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

module.exports = {
  OFFLINE_SCOPE,
  createOfflineReferenceCandidateStore,
  offlineReferenceCandidates,
};
