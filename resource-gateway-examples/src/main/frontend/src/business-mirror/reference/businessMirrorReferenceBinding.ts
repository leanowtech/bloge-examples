import type {
  ReferenceCandidate,
  ReferenceResolveResult,
} from '../../shared/reference-picker/types';
import type {
  BusinessMirrorArtifactRef,
  BusinessMirrorAssetRef,
  BusinessMirrorPackageDraft,
} from '../domain';

export type BusinessMirrorReferenceField =
  | 'domain'
  | 'taxonomy'
  | 'accountableOwner'
  | 'contract'
  | 'stateModel'
  | 'effectModel'
  | 'solution'
  | 'carrier'
  | 'channel'
  | 'scenarioInventory'
  | 'scenarioPack'
  | 'fidelityInventory'
  | 'outcomeDefinition'
  | 'approvalOwner';

export const BUSINESS_MIRROR_REFERENCE_KINDS: Record<BusinessMirrorReferenceField, string> = {
  domain: 'BUSINESS_DOMAIN',
  taxonomy: 'PROBLEM_TAXONOMY',
  accountableOwner: 'OWNER',
  contract: 'PACKAGE_CONTRACT',
  stateModel: 'STATE_MODEL',
  effectModel: 'EFFECT_MODEL',
  solution: 'SOLUTION',
  carrier: 'SERVICE_CARRIER',
  channel: 'CHANNEL',
  scenarioInventory: 'SCENARIO_INVENTORY',
  scenarioPack: 'SCENARIO_PACK',
  fidelityInventory: 'FIDELITY_INVENTORY',
  outcomeDefinition: 'OUTCOME_DEFINITION',
  approvalOwner: 'OWNER',
};

const BINDABLE_CANDIDATE_KINDS: Partial<Record<BusinessMirrorReferenceField, readonly string[]>> = {
  carrier: ['SOP', 'AGENT', 'WORKFLOW'],
  channel: ['CHANNEL_APPLICATION'],
};

export function businessMirrorBindableCandidateKinds(
  field: BusinessMirrorReferenceField,
): readonly string[] {
  return BINDABLE_CANDIDATE_KINDS[field] ?? [BUSINESS_MIRROR_REFERENCE_KINDS[field]];
}

export function referenceBindingIntent(field: BusinessMirrorReferenceField): string {
  return `BIND_${field.replace(/([a-z])([A-Z])/g, '$1_$2').toUpperCase()}`;
}

export function applyResolvedBusinessMirrorReference(
  draft: BusinessMirrorPackageDraft,
  field: BusinessMirrorReferenceField,
  result: ReferenceResolveResult,
  approvalTimestamp = new Date().toISOString(),
): BusinessMirrorPackageDraft {
  if (result.status !== 'RESOLVED' || !result.candidate) {
    throw new BusinessMirrorReferenceBindingError(result.status, result.errorCode);
  }
  const candidate = result.candidate;
  const expectedKinds = businessMirrorBindableCandidateKinds(field);
  if (!expectedKinds.includes(candidate.kind)) {
    throw new BusinessMirrorReferenceBindingError(
      'KIND_MISMATCH',
      `Expected ${expectedKinds.join('|')}; received ${candidate.kind}.`,
    );
  }
  const artifactRef = toArtifactRef(candidate);
  if (field === 'domain') {
    return withBusinessDefinition(draft, { domainId: candidate.id });
  }
  if (field === 'taxonomy') {
    return withBusinessDefinition(draft, { problemTaxonomyRef: artifactRef });
  }
  if (field === 'accountableOwner') {
    return withBusinessDefinition(draft, { accountableOwner: candidate.id });
  }
  if (field === 'approvalOwner') {
    return {
      ...draft,
      provenance: {
        ...draft.provenance,
        approvedBy: candidate.id,
        approvedAt: approvalTimestamp,
      },
    };
  }
  if (field === 'contract') return { ...draft, packageContractRef: artifactRef };
  if (field === 'stateModel') {
    return { ...draft, stateModelRefs: appendExact(draft.stateModelRefs, artifactRef) };
  }
  if (field === 'effectModel') {
    return { ...draft, effectModelRefs: appendExact(draft.effectModelRefs, artifactRef) };
  }
  if (field === 'scenarioInventory') return { ...draft, scenarioInventoryRef: artifactRef };
  if (field === 'scenarioPack') {
    return { ...draft, scenarioPackRefs: appendExact(draft.scenarioPackRefs, artifactRef) };
  }
  if (field === 'fidelityInventory') return { ...draft, fidelityInventoryRef: artifactRef };
  if (field === 'outcomeDefinition') {
    return { ...draft, outcomeDefinitionRefs: appendExact(draft.outcomeDefinitionRefs, artifactRef) };
  }
  const assetRef = toAssetRef(candidate, field);
  if (field === 'solution') return { ...draft, solutionRefs: appendExact(draft.solutionRefs, assetRef) };
  if (field === 'carrier') return { ...draft, carrierRefs: appendExact(draft.carrierRefs, assetRef) };
  return { ...draft, channelRefs: appendExact(draft.channelRefs, assetRef) };
}

export function clearBusinessMirrorReference(
  draft: BusinessMirrorPackageDraft,
  field: BusinessMirrorReferenceField,
): BusinessMirrorPackageDraft {
  if (field === 'domain') return withBusinessDefinition(draft, { domainId: '' });
  if (field === 'taxonomy') return withBusinessDefinition(draft, { problemTaxonomyRef: null });
  if (field === 'accountableOwner') return withBusinessDefinition(draft, { accountableOwner: '' });
  if (field === 'approvalOwner') {
    return { ...draft, provenance: { ...draft.provenance, approvedBy: '', approvedAt: null } };
  }
  if (field === 'contract') return { ...draft, packageContractRef: null };
  if (field === 'stateModel') return { ...draft, stateModelRefs: [] };
  if (field === 'effectModel') return { ...draft, effectModelRefs: [] };
  if (field === 'solution') return { ...draft, solutionRefs: [] };
  if (field === 'carrier') return { ...draft, carrierRefs: [] };
  if (field === 'channel') return { ...draft, channelRefs: [] };
  if (field === 'scenarioInventory') return { ...draft, scenarioInventoryRef: null };
  if (field === 'scenarioPack') return { ...draft, scenarioPackRefs: [] };
  if (field === 'fidelityInventory') return { ...draft, fidelityInventoryRef: null };
  return { ...draft, outcomeDefinitionRefs: [] };
}

export class BusinessMirrorReferenceBindingError extends Error {
  constructor(readonly status: string, readonly errorCode: string) {
    super(errorCode ? `${status}: ${errorCode}` : status);
    this.name = 'BusinessMirrorReferenceBindingError';
  }
}

function withBusinessDefinition(
  draft: BusinessMirrorPackageDraft,
  patch: Partial<BusinessMirrorPackageDraft['businessDefinition']>,
): BusinessMirrorPackageDraft {
  return {
    ...draft,
    businessDefinition: { ...draft.businessDefinition, ...patch },
  };
}

function toArtifactRef(candidate: ReferenceCandidate): BusinessMirrorArtifactRef {
  return {
    kind: candidate.kind,
    id: candidate.id,
    revision: candidate.revision,
    fingerprint: candidate.fingerprint,
  };
}

function toAssetRef(
  candidate: ReferenceCandidate,
  field: 'solution' | 'carrier' | 'channel',
): BusinessMirrorAssetRef {
  return {
    ...toArtifactRef(candidate),
    scope: {
      tenantId: candidate.scope.tenantId,
      organizationId: candidate.scope.organizationId,
      projectId: candidate.scope.projectId,
      environmentId: candidate.scope.environmentId,
      region: candidate.scope.region,
    },
    layer: field === 'solution' ? 'L1_SERVICE_DESIGN'
      : field === 'carrier' ? 'L2_SERVICE_CARRIER' : 'L3_APPLICATION',
    authority: candidate.authority,
  };
}

function appendExact<T extends BusinessMirrorArtifactRef>(current: T[], candidate: T): T[] {
  const coordinate = exactCoordinate(candidate);
  return [...current.filter((item) => exactCoordinate(item) !== coordinate), candidate];
}

function exactCoordinate(reference: BusinessMirrorArtifactRef): string {
  return `${reference.kind}\u0000${reference.id}\u0000${reference.revision}\u0000${reference.fingerprint}`;
}
