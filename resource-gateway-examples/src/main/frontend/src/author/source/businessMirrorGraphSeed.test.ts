import { describe, expect, it } from 'vitest';

import type { LegacyGraphPackageProjection } from '../../business-mirror/domain';
import type { GatewayExampleDiagram, GatewayExampleScenario } from '../../types';
import { graphDraftFromBusinessMirrorSeed, parseBusinessMirrorGraphSeed } from './businessMirrorGraphSeed';

const fingerprint = `sha256:${'a'.repeat(64)}`;
const search = '?sourceKind=BUSINESS_MIRROR_LEGACY_GRAPH&sourceGraphName=loanDecisionPolicy'
  + '&sourceId=built-in%3AloanDecisionPolicy&sourceRevision=1'
  + `&sourceFingerprint=${encodeURIComponent(fingerprint)}`
  + '&returnRoute=business-mirror&returnPackageId=legacy%3AloanDecisionPolicy'
  + '&returnTask=capabilities&returnAnchor=graph%3Abuilt-in%3AloanDecisionPolicy';

describe('Business Mirror Author seed', () => {
  it('parses only a complete allowlisted coordinate', () => {
    expect(parseBusinessMirrorGraphSeed(search)).toMatchObject({
      graphName: 'loanDecisionPolicy',
      sourceId: 'built-in:loanDecisionPolicy',
      sourceRevision: 1,
      sourceFingerprint: fingerprint,
      returnCoordinate: { route: 'business-mirror', task: 'capabilities' },
    });
    expect(parseBusinessMirrorGraphSeed(search.replace('sourceRevision=1', 'sourceRevision=0'))).toBeNull();
    expect(parseBusinessMirrorGraphSeed(
      search.replace(/sourceFingerprint=[^&]+/, 'sourceFingerprint=latest'),
    )).toBeNull();
    expect(parseBusinessMirrorGraphSeed('?sourceKind=OTHER')).toBeNull();
  });

  it('projects official topology only after exact source validation', () => {
    const coordinate = parseBusinessMirrorGraphSeed(search)!;
    const draft = graphDraftFromBusinessMirrorSeed(coordinate, projection(), scenario(), diagram());

    expect(draft.graphName).toBe('loanDecisionPolicy');
    expect(draft.status).toBe('SOURCE_PREVIEW');
    expect(draft.nodes).toHaveLength(2);
    expect(draft.nodes[1].operatorRef).toBe('bloge:inferred:decision');
    expect(draft.edges[0]).toMatchObject({
      source: { nodeId: 'profile', port: 'output' },
      target: { nodeId: 'decision', port: 'inputs' },
    });
    expect(draft.output.nodeId).toBe('decision');
    expect(draft.visualLayout).toMatchObject({
      source: { fingerprint, readOnly: true },
      returnCoordinate: { packageId: 'legacy:loanDecisionPolicy' },
    });
  });

  it('fails closed on source drift and dangling topology', () => {
    const coordinate = parseBusinessMirrorGraphSeed(search)!;
    expect(() => graphDraftFromBusinessMirrorSeed(
      coordinate,
      { ...projection(), sourceGraphRef: { ...projection().sourceGraphRef, fingerprint: `sha256:${'b'.repeat(64)}` } },
      scenario(),
      diagram(),
    )).toThrow('RG.AUTHORING.BUSINESS_MIRROR_SOURCE_DRIFT');
    expect(() => graphDraftFromBusinessMirrorSeed(
      coordinate,
      projection(),
      scenario(),
      { ...diagram(), edges: [{ source: 'missing', target: 'decision' }] },
    )).toThrow('RG.AUTHORING.BUSINESS_MIRROR_TOPOLOGY_INVALID');
  });
});

function projection(): LegacyGraphPackageProjection {
  const scope = {
    tenantId: 'ride-hailing', organizationId: 'customer-service', projectId: 'loan',
    environmentId: 'test', region: 'sg',
  };
  const sourceGraphRef = { kind: 'GRAPH_DRAFT', id: 'built-in:loanDecisionPolicy', revision: 1, fingerprint };
  return {
    schemaVersion: 'resourceGateway.legacyGraphPackageProjection.v1',
    projectorVersion: 'v1', migrationMode: 'LEGACY_IMPORTED', graphName: 'loanDecisionPolicy', scope,
    sourceGraphRef,
    sourceContractRef: { ...sourceGraphRef, kind: 'CONTRACT', id: 'contract' },
    projectedCapabilityRef: { ...sourceGraphRef, kind: 'CAPABILITY', id: 'capability' },
    capabilityClosureRef: { ...sourceGraphRef, kind: 'CAPABILITY_CLOSURE', id: 'closure' },
    discoveredTestSuiteRefs: [],
    packageDraft: {
      schemaVersion: 'bloge.domainCapabilityPackageDraft.v1', packageId: 'legacy:loanDecisionPolicy',
      revision: 0, scope,
      businessDefinition: {
        domainId: '', problemTaxonomyRef: null, problemCode: '', businessGoal: '', expectedOutcome: '',
        riskClass: 'HIGH', accountableOwner: '', collaboratingOwners: [],
      },
      packageContractRef: null, capabilityRefs: [], graphRefs: [sourceGraphRef], proposalRefs: [],
      stateModelRefs: [], effectModelRefs: [], scenarioInventoryRef: null, scenarioPackRefs: [],
      solutionRefs: [], carrierRefs: [], channelRefs: [], fidelityInventoryRef: null,
      outcomeDefinitionRefs: [], limitations: [], assumptions: [], expiresAt: null,
      provenance: {
        schemaVersion: 'v1', sourceType: 'LEGACY', sourceRefs: [sourceGraphRef], tenantId: scope.tenantId,
        purpose: 'AUTHORING', sampleFrom: null, sampleTo: null, sampleCount: null, confidence: null,
        biasRisks: [], approvedBy: '', approvedAt: null, expiresAt: null, revocationRef: '',
      },
      lifecycle: 'DRAFT',
    },
    gaps: [], status: 'BLOCKED', projectionFingerprint: fingerprint,
  };
}

function scenario(): GatewayExampleScenario {
  return {
    graphName: 'loanDecisionPolicy', title: 'Loan decision',
    inputSchema: { format: 'json-schema', schema: { type: 'object' } },
    outputSchema: { format: 'json-schema', schema: { type: 'object' } },
  };
}

function diagram(): GatewayExampleDiagram {
  return {
    rootId: 'loanDecisionPolicy',
    nodes: [
      { id: 'profile', operatorRef: 'resource:profile', label: 'Profile', position: { x: 10, y: 20 } },
      { id: 'decision', kind: 'decision', label: 'Decision', position: { x: 300, y: 20 } },
    ],
    edges: [{ id: 'profile-decision', source: 'profile', target: 'decision', label: 'profile' }],
  };
}
