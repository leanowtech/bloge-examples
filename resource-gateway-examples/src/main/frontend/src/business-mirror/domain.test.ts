import { describe, expect, it } from 'vitest';

import {
  businessMirrorCapabilityLayers,
  businessMirrorTaskForGap,
  businessMirrorTaskProgress,
  deriveBusinessMirrorDraftBlockers,
  effectiveBusinessMirrorGaps,
  projectBusinessMirrorPortfolio,
  type BusinessMirrorGap,
  type BusinessMirrorPackageDraft,
  type LegacyGraphPackageProjection,
} from './domain';

describe('Business Mirror workspace projection', () => {
  it('merges Legacy projections with durable Package heads without hiding remaining gaps', () => {
    const projection = legacyProjection();
    const storedDraft = {
      ...projection.packageDraft,
      revision: 3,
      businessDefinition: {
        ...projection.packageDraft.businessDefinition,
        accountableOwner: 'customer-service-owner',
      },
    };
    const items = projectBusinessMirrorPortfolio(
      { schemaVersion: 'resourceGateway.legacyGraphPackageProjectionCatalog.v1',
        scope: projection.scope, items: [projection] },
      { schemaVersion: 'resourceGateway.domainCapabilityPackagePage.v1', nextCursor: '', items: [{
        schemaVersion: 'resourceGateway.storedDomainCapabilityPackageDraft.v1',
        draftFingerprint: fingerprint('stored'),
        draft: storedDraft,
        createdAt: '2026-08-14T00:00:00Z',
        updatedAt: '2026-08-14T00:01:00Z',
        updatedBy: 'author',
      }] },
    );

    expect(items).toHaveLength(1);
    expect(items[0]).toMatchObject({ imported: true, revision: 3, owner: 'customer-service-owner' });
    expect(items[0].blockerCount).toBe(2);
    expect(items[0].displayName).toBe('Loan Decision Policy');
  });

  it('routes each blocker to a stable task and derives task progress independently', () => {
    const gaps = legacyProjection().gaps;
    expect(businessMirrorTaskForGap(gaps[0])).toBe('problem');
    expect(businessMirrorTaskForGap(gaps[1])).toBe('scenarios');
    expect(businessMirrorTaskForGap(gaps[2])).toBe('calibrate');
    expect(businessMirrorTaskProgress('problem', gaps)).toBe('BLOCKED');
    expect(businessMirrorTaskProgress('boundary', gaps)).toBe('COMPLETE');
  });

  it('keeps migration-policy gaps alongside compiler findings', () => {
    const projection = legacyProjection();
    const gaps = effectiveBusinessMirrorGaps(projection, null, {
      schemaVersion: 'resourceGateway.packageCompilationReceipt.v1',
      requestFingerprint: fingerprint('request'),
      packageId: projection.packageDraft.packageId,
      sourceDraftRevision: 1,
      sourceDraftFingerprint: fingerprint('draft'),
      compilationRevision: 1,
      readiness: {
        schemaVersion: 'resourceGateway.packageReadinessReport.v1',
        reportId: 'readiness:loan', revision: 1, fingerprint: fingerprint('report'),
        scope: projection.scope, packageId: projection.packageDraft.packageId,
        sourceDraftRevision: 1, sourceDraftFingerprint: fingerprint('draft'), status: 'BLOCKED',
        findings: [{
          findingId: 'finding:contract', code: 'PACKAGE_CONTRACT_MISSING', severity: 'ERROR',
          category: 'CONTRACT', fieldPath: '/packageContractRef', artifactRef: null,
          messageId: 'business-mirror.package-contract-missing',
        }],
        createdAt: '2026-08-14T00:00:00Z',
      },
      snapshot: null,
      authorityGeneration: 'authority:1',
      completedAt: '2026-08-14T00:00:00Z',
    });

    expect(gaps.map((gap) => gap.code)).toEqual([
      'LEGACY_PROJECTION_OWNER_APPROVAL_MISSING',
      'PACKAGE_CONTRACT_MISSING',
    ]);
  });

  it('projects a four-layer map with explicit missing L1-L3 assets', () => {
    const projection = legacyProjection();
    const layers = businessMirrorCapabilityLayers(projection, projection.packageDraft);

    expect(layers.map((layer) => layer.id)).toEqual(['L0', 'L1', 'L2', 'L3']);
    expect(layers[0].refs.every((ref) => !ref.missing)).toBe(true);
    expect(layers.slice(1).every((layer) => layer.refs[0].missing)).toBe(true);
  });

  it('recomputes mutable draft blockers after guided business fields are completed', () => {
    const draft = legacyProjection().packageDraft;
    expect(deriveBusinessMirrorDraftBlockers(draft)).toContain('ACCOUNTABLE_OWNER_MISSING');

    const edited: BusinessMirrorPackageDraft = {
      ...draft,
      businessDefinition: {
        ...draft.businessDefinition,
        domainId: 'ride.customer-service',
        problemCode: 'loan-decision',
        businessGoal: 'Give an explainable decision.',
        expectedOutcome: 'Correct decisions with explicit abstention.',
        accountableOwner: 'risk-service-owner',
      },
    };
    expect(deriveBusinessMirrorDraftBlockers(edited)).not.toContain('ACCOUNTABLE_OWNER_MISSING');
    expect(deriveBusinessMirrorDraftBlockers(edited)).toContain('PROBLEM_TAXONOMY_MISSING');
  });
});

function legacyProjection(): LegacyGraphPackageProjection {
  const scope = {
    tenantId: 'ride-hailing', organizationId: 'customer-service', projectId: 'loan',
    environmentId: 'test', region: 'sg',
  };
  const graphRef = ref('GRAPH_DRAFT', 'built-in:loanDecisionPolicy');
  const contractRef = ref('CONTRACT', 'built-in:loanDecisionPolicy:contract');
  const capabilityRef = ref('CAPABILITY', 'graph:loanDecisionPolicy');
  const draft: BusinessMirrorPackageDraft = {
    schemaVersion: 'bloge.domainCapabilityPackageDraft.v1',
    packageId: 'legacy:loanDecisionPolicy', revision: 0, scope,
    businessDefinition: {
      domainId: '', problemTaxonomyRef: null, problemCode: '', businessGoal: '',
      expectedOutcome: '', riskClass: 'CRITICAL', accountableOwner: '', collaboratingOwners: [],
    },
    packageContractRef: contractRef,
    capabilityRefs: [], graphRefs: [graphRef], proposalRefs: [], stateModelRefs: [],
    effectModelRefs: [], scenarioInventoryRef: null, scenarioPackRefs: [], solutionRefs: [],
    carrierRefs: [], channelRefs: [], fidelityInventoryRef: null, outcomeDefinitionRefs: [],
    limitations: [], assumptions: [], expiresAt: null,
    provenance: {
      schemaVersion: 'resourceGateway.artifactProvenance.v1', sourceType: 'INFERRED',
      sourceRefs: [graphRef, contractRef, capabilityRef], tenantId: scope.tenantId,
      purpose: 'BUSINESS_MIRROR_LEGACY_MIGRATION', sampleFrom: null, sampleTo: null,
      sampleCount: null, confidence: null, biasRisks: [], approvedBy: '', approvedAt: null,
      expiresAt: null, revocationRef: '',
    },
    lifecycle: 'DRAFT',
  };
  return {
    schemaVersion: 'resourceGateway.legacyGraphPackageProjection.v1',
    projectorVersion: 'legacy-graph-package-projector-v1', migrationMode: 'LEGACY_IMPORTED',
    graphName: 'loanDecisionPolicy', scope, sourceGraphRef: graphRef,
    sourceContractRef: contractRef, projectedCapabilityRef: capabilityRef,
    capabilityClosureRef: ref('CAPABILITY_CLOSURE', 'graph:loanDecisionPolicy'),
    discoveredTestSuiteRefs: [ref('TEST_SUITE', 'loan-decision-smoke')], packageDraft: draft,
    gaps: [
      gap('ACCOUNTABLE_OWNER_MISSING', 'PACKAGE_READINESS', 'BUSINESS_CONTEXT', 'BLOCKING'),
      gap('SCENARIO_PACK_MISSING', 'PACKAGE_READINESS', 'SCENARIO', 'BLOCKING'),
      gap('LEGACY_PROJECTION_OWNER_APPROVAL_MISSING', 'MIGRATION_POLICY', 'MIGRATION_TRUST', 'BLOCKING'),
    ],
    status: 'BLOCKED', projectionFingerprint: fingerprint('projection'),
  };
}

function gap(
  code: string,
  origin: BusinessMirrorGap['origin'],
  category: string,
  severity: BusinessMirrorGap['severity'],
): BusinessMirrorGap {
  return {
    code, origin, category, severity, draftPath: '/draft', explanation: code,
    requiredAction: code, evidenceRefs: [],
  };
}

function ref(kind: string, id: string) {
  return { kind, id, revision: 1, fingerprint: fingerprint(`${kind}:${id}`) };
}

function fingerprint(seed: string): string {
  return `sha256:${seed.padEnd(64, '0').slice(0, 64).replace(/[^a-f0-9]/g, 'a')}`;
}
