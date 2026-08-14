'use strict';

const crypto = require('node:crypto');

const SCOPE = Object.freeze({
  tenantId: 'offline-demo',
  organizationId: 'customer-service',
  projectId: 'business-mirror',
  environmentId: 'offline',
  region: 'local',
});

function createOfflineBusinessMirrorStore() {
  const projections = [
    projection('loanDecisionPolicy', 'loan-decision-policy-smoke'),
    projection('resourceDispatch', 'resource-dispatch-routing'),
    projection('enrichOrderList', 'order-enrichment-regression'),
  ];
  const packageHeads = new Map();
  const receipts = new Map();
  let compilationRevision = 0;

  return function handleOfflineBusinessMirror(request, target) {
    const path = decodeURIComponent(target.pathname);
    if (!path.startsWith('/api/business-mirror/')) return null;
    if (request.method === 'GET' && path === '/api/business-mirror/legacy-graphs') {
      return jsonResponse(200, {
        schemaVersion: 'resourceGateway.legacyGraphPackageProjectionCatalog.v1',
        scope: SCOPE,
        items: projections,
      });
    }
    if (request.method === 'GET' && path === '/api/business-mirror/packages') {
      return jsonResponse(200, {
        schemaVersion: 'resourceGateway.domainCapabilityPackagePage.v1',
        items: [...packageHeads.values()].sort((left, right) =>
          left.draft.packageId.localeCompare(right.draft.packageId)),
        nextCursor: '',
      });
    }
    const legacyMatch = path.match(/^\/api\/business-mirror\/legacy-graphs\/([^/]+)$/);
    if (request.method === 'GET' && legacyMatch) {
      const found = projections.find((item) => item.graphName === legacyMatch[1]);
      return found ? jsonResponse(200, found) : notFound('RG.BUSINESS_MIRROR.LEGACY_GRAPH_NOT_FOUND');
    }
    const importMatch = path.match(/^\/api\/business-mirror\/legacy-graphs\/([^/]+)\/packages$/);
    if (request.method === 'POST' && importMatch) {
      const found = projections.find((item) => item.graphName === importMatch[1]);
      if (!found) return notFound('RG.BUSINESS_MIRROR.LEGACY_GRAPH_NOT_FOUND');
      const attempt = beginIdempotent(request, path, receipts, { graphName: found.graphName });
      if (attempt.response) return attempt.response;
      return completeIdempotent(attempt, receipts, () => {
        const now = new Date().toISOString();
        const stored = {
          schemaVersion: 'resourceGateway.storedDomainCapabilityPackageDraft.v1',
          draftFingerprint: fingerprint({ draft: found.packageDraft, revision: 1 }),
          draft: { ...clone(found.packageDraft), revision: 1 },
          createdAt: now,
          updatedAt: now,
          updatedBy: 'vscode-offline-author',
        };
        packageHeads.set(stored.draft.packageId, stored);
        return saveReceipt(stored, { operation: 'IMPORT', graphName: found.graphName });
      }, 201);
    }
    const packageMatch = path.match(/^\/api\/business-mirror\/packages\/([^/]+)$/);
    if (request.method === 'PUT' && packageMatch) {
      const packageId = packageMatch[1];
      const current = packageHeads.get(packageId);
      if (!current) return notFound('RG.BUSINESS_MIRROR.PACKAGE_NOT_FOUND');
      let candidate;
      try {
        candidate = JSON.parse(request.body || '{}');
      } catch {
        return problem(400, 'RG.BUSINESS_MIRROR.PACKAGE_BODY_INVALID', 'Package body is invalid.');
      }
      const expectedRevision = Number(target.searchParams.get('expectedRevision'));
      const attempt = beginIdempotent(request, path, receipts, { candidate, expectedRevision });
      if (attempt.response) return attempt.response;
      if (candidate.packageId !== packageId || candidate.revision !== expectedRevision
          || current.draft.revision !== expectedRevision) {
        return problem(409, 'RG.BUSINESS_MIRROR.PACKAGE_REVISION_CONFLICT',
          'Package draft changed after it was loaded.');
      }
      return completeIdempotent(attempt, receipts, () => {
        const stored = {
          ...current,
          draftFingerprint: fingerprint({ draft: candidate, revision: expectedRevision + 1 }),
          draft: { ...clone(candidate), revision: expectedRevision + 1 },
          updatedAt: new Date().toISOString(),
        };
        packageHeads.set(packageId, stored);
        return saveReceipt(stored, { operation: 'SAVE', expectedRevision });
      });
    }
    const compileMatch = path.match(/^\/api\/business-mirror\/packages\/([^/]+)\/compile$/);
    if (request.method === 'POST' && compileMatch) {
      const packageId = compileMatch[1];
      const current = packageHeads.get(packageId);
      if (!current) return notFound('RG.BUSINESS_MIRROR.PACKAGE_NOT_FOUND');
      const sourceRevision = Number(target.searchParams.get('sourceRevision'));
      const attempt = beginIdempotent(request, path, receipts, { packageId, sourceRevision });
      if (attempt.response) return attempt.response;
      if (sourceRevision !== current.draft.revision) {
        return problem(409, 'RG.BUSINESS_MIRROR.PACKAGE_REVISION_CONFLICT',
          'Package draft changed after it was loaded.');
      }
      return completeIdempotent(attempt, receipts, () => {
        compilationRevision += 1;
        return compileReceipt(current, compilationRevision);
      }, 201);
    }
    return notFound('RG.HOST.OFFLINE_ROUTE.UNAVAILABLE');
  };
}

function projection(graphName, suiteId) {
  const graphRef = ref('GRAPH_DRAFT', `built-in:${graphName}`);
  const contractRef = ref('CONTRACT', `built-in:${graphName}:contract`);
  const capabilityRef = ref('CAPABILITY', `graph:${graphName}`);
  const closureRef = ref('CAPABILITY_CLOSURE', `graph:${graphName}`);
  const suiteRef = ref('TEST_SUITE', suiteId);
  const gaps = [
    gap('ACCOUNTABLE_OWNER_MISSING', 'PACKAGE_READINESS', 'BUSINESS_CONTEXT', '/businessDefinition/accountableOwner'),
    gap('BUSINESS_DOMAIN_MISSING', 'PACKAGE_READINESS', 'BUSINESS_CONTEXT', '/businessDefinition/domainId'),
    gap('BUSINESS_GOAL_MISSING', 'PACKAGE_READINESS', 'BUSINESS_CONTEXT', '/businessDefinition/businessGoal'),
    gap('EXPECTED_OUTCOME_MISSING', 'PACKAGE_READINESS', 'OUTCOME', '/businessDefinition/expectedOutcome'),
    gap('PROBLEM_CODE_MISSING', 'PACKAGE_READINESS', 'BUSINESS_CONTEXT', '/businessDefinition/problemCode'),
    gap('PROBLEM_TAXONOMY_MISSING', 'PACKAGE_READINESS', 'BUSINESS_CONTEXT', '/businessDefinition/problemTaxonomyRef'),
    gap('SCENARIO_INVENTORY_MISSING', 'PACKAGE_READINESS', 'SCENARIO', '/scenarioInventoryRef', [suiteRef]),
    gap('SCENARIO_PACK_MISSING', 'PACKAGE_READINESS', 'SCENARIO', '/scenarioPackRefs', [suiteRef]),
    gap('SOLUTION_BINDING_MISSING', 'PACKAGE_READINESS', 'SERVICE_ASSET', '/solutionRefs'),
    gap('SERVICE_CARRIER_BINDING_MISSING', 'PACKAGE_READINESS', 'SERVICE_ASSET', '/carrierRefs'),
    gap('CHANNEL_BINDING_MISSING', 'PACKAGE_READINESS', 'SERVICE_ASSET', '/channelRefs'),
    gap('FIDELITY_INVENTORY_MISSING', 'PACKAGE_READINESS', 'FIDELITY', '/fidelityInventoryRef'),
    gap('OUTCOME_DEFINITION_MISSING', 'PACKAGE_READINESS', 'OUTCOME', '/outcomeDefinitionRefs'),
    gap('HIGH_RISK_STATE_MODEL_MISSING', 'PACKAGE_READINESS', 'EXECUTION_MODEL', '/stateModelRefs', [graphRef]),
    gap('HIGH_RISK_EFFECT_MODEL_MISSING', 'PACKAGE_READINESS', 'EXECUTION_MODEL', '/effectModelRefs', [graphRef]),
    gap('GRAPH_CONTRACT_OWNER_CONFIRMATION_MISSING', 'MIGRATION_POLICY', 'CONTRACT', '/packageContractRef', [contractRef]),
    gap('LEGACY_PROJECTION_OWNER_APPROVAL_MISSING', 'MIGRATION_POLICY', 'MIGRATION_TRUST', '/provenance/approvedBy', [graphRef, contractRef]),
    gap('MIRROR_PLAN_MISSING', 'MIGRATION_POLICY', 'EXECUTION_MODEL', '/graphRefs/0', [graphRef, closureRef]),
    {
      ...gap('DISCOVERED_TEST_SUITE_REQUIRES_SCENARIO_GOVERNANCE', 'MIGRATION_POLICY',
        'SCENARIO', '/scenarioPackRefs', [suiteRef]),
      severity: 'WARNING',
    },
  ].sort((left, right) => left.code.localeCompare(right.code));
  const draft = {
    schemaVersion: 'bloge.domainCapabilityPackageDraft.v1',
    packageId: `legacy:${graphName}`,
    revision: 0,
    scope: SCOPE,
    businessDefinition: {
      domainId: '', problemTaxonomyRef: null, problemCode: '', businessGoal: '', expectedOutcome: '',
      riskClass: 'CRITICAL', accountableOwner: '', collaboratingOwners: [],
    },
    packageContractRef: contractRef,
    capabilityRefs: [], graphRefs: [graphRef], proposalRefs: [], stateModelRefs: [], effectModelRefs: [],
    scenarioInventoryRef: null, scenarioPackRefs: [], solutionRefs: [], carrierRefs: [], channelRefs: [],
    fidelityInventoryRef: null, outcomeDefinitionRefs: [],
    limitations: ['Offline authoring cannot create production Outcome or ANEKE governance evidence.'],
    assumptions: ['Existing Graph behavior is preserved without topology rewriting.'],
    expiresAt: null,
    provenance: {
      schemaVersion: 'resourceGateway.artifactProvenance.v1', sourceType: 'INFERRED',
      sourceRefs: [capabilityRef, closureRef, contractRef, graphRef, suiteRef]
        .sort((left, right) => `${left.kind}:${left.id}`.localeCompare(`${right.kind}:${right.id}`)),
      tenantId: SCOPE.tenantId, purpose: 'BUSINESS_MIRROR_LEGACY_MIGRATION',
      sampleFrom: null, sampleTo: null, sampleCount: null, confidence: null,
      biasRisks: ['Offline fixtures are illustrative and cannot establish production fidelity.'],
      approvedBy: '', approvedAt: null, expiresAt: null, revocationRef: '',
    },
    lifecycle: 'DRAFT',
  };
  const material = {
    schemaVersion: 'resourceGateway.legacyGraphPackageProjection.v1',
    projectorVersion: 'legacy-graph-package-projector-v1', migrationMode: 'LEGACY_IMPORTED',
    graphName, scope: SCOPE, sourceGraphRef: graphRef, sourceContractRef: contractRef,
    projectedCapabilityRef: capabilityRef, capabilityClosureRef: closureRef,
    discoveredTestSuiteRefs: [suiteRef], packageDraft: draft, gaps, status: 'BLOCKED',
  };
  return { ...material, projectionFingerprint: fingerprint(material) };
}

function deriveBlockers(draft) {
  const definition = draft.businessDefinition;
  const blockers = [];
  if (!definition.domainId) blockers.push('BUSINESS_DOMAIN_MISSING');
  if (!definition.problemTaxonomyRef) blockers.push('PROBLEM_TAXONOMY_MISSING');
  if (!definition.problemCode) blockers.push('PROBLEM_CODE_MISSING');
  if (!definition.businessGoal) blockers.push('BUSINESS_GOAL_MISSING');
  if (!definition.expectedOutcome) blockers.push('EXPECTED_OUTCOME_MISSING');
  if (!definition.accountableOwner) blockers.push('ACCOUNTABLE_OWNER_MISSING');
  if (!draft.packageContractRef) blockers.push('PACKAGE_CONTRACT_MISSING');
  if (draft.capabilityRefs.length === 0 && draft.graphRefs.length === 0) blockers.push('EXECUTABLE_PROJECTION_MISSING');
  if (!draft.scenarioInventoryRef) blockers.push('SCENARIO_INVENTORY_MISSING');
  if (draft.scenarioPackRefs.length === 0) blockers.push('SCENARIO_PACK_MISSING');
  if (draft.solutionRefs.length === 0) blockers.push('SOLUTION_BINDING_MISSING');
  if (draft.carrierRefs.length === 0) blockers.push('SERVICE_CARRIER_BINDING_MISSING');
  if (draft.channelRefs.length === 0) blockers.push('CHANNEL_BINDING_MISSING');
  if (!draft.fidelityInventoryRef) blockers.push('FIDELITY_INVENTORY_MISSING');
  if (draft.outcomeDefinitionRefs.length === 0) blockers.push('OUTCOME_DEFINITION_MISSING');
  if (['HIGH', 'CRITICAL'].includes(definition.riskClass) && draft.stateModelRefs.length === 0) {
    blockers.push('HIGH_RISK_STATE_MODEL_MISSING');
  }
  if (['HIGH', 'CRITICAL'].includes(definition.riskClass) && draft.effectModelRefs.length === 0) {
    blockers.push('HIGH_RISK_EFFECT_MODEL_MISSING');
  }
  return blockers.sort();
}

function compileReceipt(stored, revision) {
  const createdAt = new Date().toISOString();
  const findings = deriveBlockers(stored.draft).map((code, index) => ({
    findingId: `offline:${stored.draft.packageId}:${index + 1}`,
    code,
    severity: 'ERROR',
    category: blockerCategory(code),
    fieldPath: blockerPath(code),
    artifactRef: null,
    messageId: `business-mirror.${code.toLowerCase().replaceAll('_', '-')}`,
  }));
  const readinessMaterial = {
    schemaVersion: 'resourceGateway.packageReadinessReport.v1',
    reportId: `readiness:${stored.draft.packageId}`,
    revision,
    scope: SCOPE,
    packageId: stored.draft.packageId,
    sourceDraftRevision: stored.draft.revision,
    sourceDraftFingerprint: stored.draftFingerprint,
    status: findings.length ? 'BLOCKED' : 'READY',
    findings,
    createdAt,
  };
  const readiness = { ...readinessMaterial, fingerprint: fingerprint(readinessMaterial) };
  return {
    schemaVersion: 'resourceGateway.packageCompilationReceipt.v1',
    requestFingerprint: fingerprint({ packageId: stored.draft.packageId, revision }),
    packageId: stored.draft.packageId,
    sourceDraftRevision: stored.draft.revision,
    sourceDraftFingerprint: stored.draftFingerprint,
    compilationRevision: revision,
    readiness,
    businessAssetLinkClosure: {
      schemaVersion: 'bloge.businessAssetLinkClosure.v1', packageId: stored.draft.packageId,
      revision, scope: SCOPE, roots: [], links: [], createdAt,
      fingerprint: fingerprint({ packageId: stored.draft.packageId, links: [] }),
    },
    snapshot: findings.length ? null : {},
    authorityGeneration: 'vscode-offline-authority:v1',
    completedAt: createdAt,
  };
}

function blockerCategory(code) {
  if (code.includes('SCENARIO')) return 'SCENARIO';
  if (code.includes('OUTCOME')) return 'OUTCOME';
  if (code.includes('CONTRACT')) return 'CONTRACT';
  if (code.includes('STATE') || code.includes('EFFECT')) return 'ISOLATION';
  return 'BUSINESS_DEFINITION';
}

function blockerPath(code) {
  const projection = {
    BUSINESS_DOMAIN_MISSING: '/businessDefinition/domainId',
    PROBLEM_TAXONOMY_MISSING: '/businessDefinition/problemTaxonomyRef',
    PROBLEM_CODE_MISSING: '/businessDefinition/problemCode',
    BUSINESS_GOAL_MISSING: '/businessDefinition/businessGoal',
    EXPECTED_OUTCOME_MISSING: '/businessDefinition/expectedOutcome',
    ACCOUNTABLE_OWNER_MISSING: '/businessDefinition/accountableOwner',
  };
  return projection[code] || '/';
}

function gap(code, origin, category, draftPath, evidenceRefs = []) {
  return {
    code, origin, category, severity: 'BLOCKING', draftPath,
    explanation: 'A governed Package requirement is incomplete.',
    requiredAction: 'Complete this requirement in the highlighted task.',
    evidenceRefs,
  };
}

function ref(kind, id) {
  return { kind, id, revision: 1, fingerprint: fingerprint({ kind, id, revision: 1 }) };
}

function saveReceipt(stored, command) {
  return {
    schemaVersion: 'resourceGateway.domainCapabilityPackageSaveReceipt.v1',
    requestFingerprint: fingerprint(command),
    result: stored,
    completedAt: stored.updatedAt,
  };
}

function beginIdempotent(request, path, receipts, material) {
  const key = request.headers['idempotency-key'] || request.headers['Idempotency-Key'];
  if (!key) {
    return { response: problem(400, 'RG.BUSINESS_MIRROR.IDEMPOTENCY_KEY_INVALID',
      'Idempotency-Key is required.') };
  }
  const coordinate = `${request.method}:${path}:${key}`;
  const materialFingerprint = fingerprint(material);
  const existing = receipts.get(coordinate);
  if (existing) {
    if (existing.materialFingerprint !== materialFingerprint) {
      return { response: problem(409, 'RG.BUSINESS_MIRROR.IDEMPOTENCY_MATERIAL_CONFLICT',
        'Idempotency-Key was already used for different request material.') };
    }
    return { response: jsonResponse(existing.status, existing.body) };
  }
  return { coordinate, materialFingerprint };
}

function completeIdempotent(attempt, receipts, supplier, status = 200) {
  const result = supplier();
  receipts.set(attempt.coordinate, { materialFingerprint: attempt.materialFingerprint, status, body: result });
  return jsonResponse(status, result);
}

function fingerprint(value) {
  return `sha256:${crypto.createHash('sha256').update(JSON.stringify(value)).digest('hex')}`;
}

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

function notFound(code) {
  return problem(404, code, 'The requested offline Business Mirror asset was not found.');
}

function problem(status, code, detail) {
  return jsonResponse(status, { code, detail }, code);
}

function jsonResponse(status, body, statusText = status >= 400 ? 'Request failed' : 'OK') {
  return {
    status,
    statusText,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  };
}

module.exports = { createOfflineBusinessMirrorStore };
