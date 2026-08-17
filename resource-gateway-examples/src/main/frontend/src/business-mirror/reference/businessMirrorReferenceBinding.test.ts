import { describe, expect, it } from 'vitest';

import type { ReferenceCandidate, ReferenceResolveResult } from '../../shared/reference-picker/types';
import type { BusinessMirrorPackageDraft } from '../domain';
import {
  applyResolvedBusinessMirrorReference,
  BusinessMirrorReferenceBindingError,
  clearBusinessMirrorReference,
  referenceBindingIntent,
} from './businessMirrorReferenceBinding';

describe('businessMirrorReferenceBinding', () => {
  it('stores stable directory IDs without persisting display names', () => {
    const draft = minimalDraft();
    const domain = applyResolvedBusinessMirrorReference(
      draft, 'domain', resolved(candidate('BUSINESS_DOMAIN', 'ride.customer-service', 'Customer Service')),
    );
    const owner = applyResolvedBusinessMirrorReference(
      domain, 'accountableOwner', resolved(candidate('OWNER', 'team-service-design', 'Service Design Team')),
    );

    expect(owner.businessDefinition.domainId).toBe('ride.customer-service');
    expect(owner.businessDefinition.accountableOwner).toBe('team-service-design');
    expect(JSON.stringify(owner)).not.toContain('Service Design Team');
  });

  it('binds exact refs and deduplicates repeated multi-value coordinates', () => {
    const first = applyResolvedBusinessMirrorReference(
      minimalDraft(), 'scenarioPack', resolved(candidate('SCENARIO_PACK', 'loan-boundaries', 'Loan boundaries')),
    );
    const second = applyResolvedBusinessMirrorReference(
      first, 'scenarioPack', resolved(candidate('SCENARIO_PACK', 'loan-boundaries', 'Loan boundaries')),
    );

    expect(second.scenarioPackRefs).toEqual([expect.objectContaining({
      kind: 'SCENARIO_PACK', id: 'loan-boundaries', revision: 3, fingerprint: fingerprint('l'),
    })]);
    expect(referenceBindingIntent('scenarioPack')).toBe('BIND_SCENARIO_PACK');
  });

  it('persists concrete package artifact kinds returned by catalog-family searches', () => {
    const contract = applyResolvedBusinessMirrorReference(
      minimalDraft(), 'contract', resolved(candidate('CONTRACT', 'loan-contract', 'Loan contract')),
    );
    const effects = applyResolvedBusinessMirrorReference(
      contract, 'effectModel', resolved(candidate('EFFECT_CONTRACT', 'loan-effects', 'Loan effects')),
    );
    const fidelity = applyResolvedBusinessMirrorReference(
      effects, 'fidelityInventory',
      resolved(candidate('DOMAIN_FIDELITY_INVENTORY', 'loan-fidelity', 'Loan fidelity')),
    );

    expect(fidelity.packageContractRef?.kind).toBe('CONTRACT');
    expect(fidelity.effectModelRefs[0]?.kind).toBe('EFFECT_CONTRACT');
    expect(fidelity.fidelityInventoryRef?.kind).toBe('DOMAIN_FIDELITY_INVENTORY');
  });

  it('keeps drift, forbidden, not-found, and kind mismatch fail closed', () => {
    const draft = minimalDraft();
    for (const status of ['DRIFTED', 'FORBIDDEN', 'NOT_FOUND'] as const) {
      expect(() => applyResolvedBusinessMirrorReference(draft, 'contract', {
        schemaVersion: 'bloge.referenceResolveResult.v1', status,
        candidate: status === 'DRIFTED' ? candidate('PACKAGE_CONTRACT', 'current', 'Current') : null,
        errorCode: `RG.REFERENCE.${status}`,
      })).toThrow(BusinessMirrorReferenceBindingError);
    }
    expect(() => applyResolvedBusinessMirrorReference(
      draft, 'contract', resolved(candidate('OWNER', 'owner-a', 'Owner A')),
    )).toThrow('KIND_MISMATCH');
    expect(draft.packageContractRef).toBeNull();
  });

  it('maps L1-L3 candidates into scoped asset refs and clears governed bindings', () => {
    const solutionCandidate = candidate('SOLUTION', 'refund-solution', 'Refund solution');
    Object.assign(solutionCandidate.scope, { fullySpecified: true });
    const solution = applyResolvedBusinessMirrorReference(
      minimalDraft(), 'solution', resolved(solutionCandidate),
    );
    expect(solution.solutionRefs[0]).toMatchObject({
      id: 'refund-solution', layer: 'L1_SERVICE_DESIGN', authority: 'resource-gateway://demo',
      scope: { tenantId: 'ride-hailing', projectId: 'loan' },
    });
    expect(solution.solutionRefs[0].scope).not.toHaveProperty('fullySpecified');
    expect(clearBusinessMirrorReference(solution, 'solution').solutionRefs).toEqual([]);

    const carrier = applyResolvedBusinessMirrorReference(
      solution, 'carrier', resolved(candidate('AGENT', 'policy-agent', 'Policy agent')),
    );
    const channel = applyResolvedBusinessMirrorReference(
      carrier, 'channel', resolved(candidate('CHANNEL_APPLICATION', 'support-chat', 'Support chat')),
    );
    expect(carrier.carrierRefs[0]).toMatchObject({
      kind: 'AGENT', layer: 'L2_SERVICE_CARRIER',
    });
    expect(channel.channelRefs[0]).toMatchObject({
      kind: 'CHANNEL_APPLICATION', layer: 'L3_APPLICATION',
    });
  });
});

function resolved(candidateValue: ReferenceCandidate): ReferenceResolveResult {
  return {
    schemaVersion: 'bloge.referenceResolveResult.v1',
    status: 'RESOLVED',
    candidate: candidateValue,
    errorCode: '',
  };
}

function candidate(kind: string, id: string, displayName: string): ReferenceCandidate {
  return {
    schemaVersion: 'bloge.referenceCandidate.v1', kind, id, displayName, description: '',
    revision: 3, fingerprint: fingerprint(id.slice(0, 1)), authority: 'resource-gateway://demo',
    scope: {
      tenantId: 'ride-hailing', organizationId: 'customer-service', projectId: 'loan',
      environmentId: 'test', region: 'sg',
    },
    lifecycle: 'ACTIVE', owner: null, labels: [], compatibility: 'COMPATIBLE',
    disabledReasonCode: '',
  };
}

function minimalDraft(): BusinessMirrorPackageDraft {
  return {
    schemaVersion: 'bloge.domainCapabilityPackageDraft.v1', packageId: 'legacy:loan', revision: 1,
    scope: {
      tenantId: 'ride-hailing', organizationId: 'customer-service', projectId: 'loan',
      environmentId: 'test', region: 'sg',
    },
    businessDefinition: {
      domainId: '', problemTaxonomyRef: null, problemCode: '', businessGoal: '',
      expectedOutcome: '', riskClass: 'CRITICAL', accountableOwner: '', collaboratingOwners: [],
    },
    packageContractRef: null, capabilityRefs: [], graphRefs: [], proposalRefs: [],
    stateModelRefs: [], effectModelRefs: [], scenarioInventoryRef: null, scenarioPackRefs: [],
    solutionRefs: [], carrierRefs: [], channelRefs: [], fidelityInventoryRef: null,
    outcomeDefinitionRefs: [], limitations: [], assumptions: [], expiresAt: null,
    provenance: {
      schemaVersion: 'resourceGateway.artifactProvenance.v1', sourceType: 'DECLARED', sourceRefs: [],
      tenantId: 'ride-hailing', purpose: 'TEST', sampleFrom: null, sampleTo: null,
      sampleCount: null, confidence: null, biasRisks: [], approvedBy: '', approvedAt: null,
      expiresAt: null, revocationRef: '',
    },
    lifecycle: 'DRAFT',
  };
}

function fingerprint(seed: string): string {
  return `sha256:${(seed || 'x').repeat(64).slice(0, 64)}`;
}
