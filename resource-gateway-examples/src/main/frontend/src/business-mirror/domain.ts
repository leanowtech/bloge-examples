export interface BusinessMirrorScope {
  tenantId: string;
  organizationId: string;
  projectId: string;
  environmentId: string;
  region: string;
}

export interface BusinessMirrorArtifactRef {
  kind: string;
  id: string;
  revision: number;
  fingerprint: string;
}

export interface BusinessMirrorAssetRef extends BusinessMirrorArtifactRef {
  scope: BusinessMirrorScope;
  layer: 'L0_FOUNDATION' | 'L1_SERVICE_DESIGN' | 'L2_SERVICE_CARRIER' | 'L3_APPLICATION';
  kind: string;
}

export interface BusinessMirrorBusinessDefinition {
  domainId: string;
  problemTaxonomyRef: BusinessMirrorArtifactRef | null;
  problemCode: string;
  businessGoal: string;
  expectedOutcome: string;
  riskClass: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
  accountableOwner: string;
  collaboratingOwners: string[];
}

export interface BusinessMirrorProvenance {
  schemaVersion: string;
  sourceType: string;
  sourceRefs: BusinessMirrorArtifactRef[];
  tenantId: string;
  purpose: string;
  sampleFrom: string | null;
  sampleTo: string | null;
  sampleCount: number | null;
  confidence: number | null;
  biasRisks: string[];
  approvedBy: string;
  approvedAt: string | null;
  expiresAt: string | null;
  revocationRef: string;
}

export interface BusinessMirrorPackageDraft {
  schemaVersion: string;
  packageId: string;
  revision: number;
  scope: BusinessMirrorScope;
  businessDefinition: BusinessMirrorBusinessDefinition;
  packageContractRef: BusinessMirrorArtifactRef | null;
  capabilityRefs: BusinessMirrorArtifactRef[];
  graphRefs: BusinessMirrorArtifactRef[];
  proposalRefs: BusinessMirrorArtifactRef[];
  stateModelRefs: BusinessMirrorArtifactRef[];
  effectModelRefs: BusinessMirrorArtifactRef[];
  scenarioInventoryRef: BusinessMirrorArtifactRef | null;
  scenarioPackRefs: BusinessMirrorArtifactRef[];
  solutionRefs: BusinessMirrorAssetRef[];
  carrierRefs: BusinessMirrorAssetRef[];
  channelRefs: BusinessMirrorAssetRef[];
  fidelityInventoryRef: BusinessMirrorArtifactRef | null;
  outcomeDefinitionRefs: BusinessMirrorArtifactRef[];
  limitations: string[];
  assumptions: string[];
  expiresAt: string | null;
  provenance: BusinessMirrorProvenance;
  lifecycle: 'DRAFT' | 'READY_FOR_REVIEW' | 'SUBMITTED' | 'SUPERSEDED';
}

export interface StoredBusinessMirrorPackage {
  schemaVersion: string;
  draftFingerprint: string;
  draft: BusinessMirrorPackageDraft;
  createdAt: string;
  updatedAt: string;
  updatedBy: string;
}

export interface BusinessMirrorPackagePage {
  schemaVersion: string;
  items: StoredBusinessMirrorPackage[];
  nextCursor: string;
}

export interface BusinessMirrorGap {
  code: string;
  origin: 'PACKAGE_READINESS' | 'MIGRATION_POLICY';
  category: string;
  severity: 'BLOCKING' | 'WARNING';
  draftPath: string;
  explanation: string;
  requiredAction: string;
  evidenceRefs: BusinessMirrorArtifactRef[];
}

export interface LegacyGraphPackageProjection {
  schemaVersion: string;
  projectorVersion: string;
  migrationMode: 'LEGACY_IMPORTED';
  graphName: string;
  scope: BusinessMirrorScope;
  sourceGraphRef: BusinessMirrorArtifactRef;
  sourceContractRef: BusinessMirrorArtifactRef;
  projectedCapabilityRef: BusinessMirrorArtifactRef;
  capabilityClosureRef: BusinessMirrorArtifactRef;
  discoveredTestSuiteRefs: BusinessMirrorArtifactRef[];
  packageDraft: BusinessMirrorPackageDraft;
  gaps: BusinessMirrorGap[];
  status: 'BLOCKED' | 'READY_FOR_OWNER_REVIEW';
  projectionFingerprint: string;
}

export interface LegacyGraphPackageProjectionCatalog {
  schemaVersion: string;
  scope: BusinessMirrorScope;
  items: LegacyGraphPackageProjection[];
}

export interface BusinessMirrorPackageSaveReceipt {
  schemaVersion: string;
  requestFingerprint: string;
  result: StoredBusinessMirrorPackage;
  completedAt: string;
}

export interface BusinessMirrorReadinessFinding {
  findingId: string;
  code: string;
  severity: 'INFO' | 'WARNING' | 'ERROR';
  category: string;
  fieldPath: string;
  artifactRef: BusinessMirrorArtifactRef | null;
  messageId: string;
}

export interface BusinessMirrorCompilationReceipt {
  schemaVersion: string;
  requestFingerprint: string;
  packageId: string;
  sourceDraftRevision: number;
  sourceDraftFingerprint: string;
  compilationRevision: number;
  readiness: {
    schemaVersion: string;
    reportId: string;
    revision: number;
    fingerprint: string;
    scope: BusinessMirrorScope;
    packageId: string;
    sourceDraftRevision: number;
    sourceDraftFingerprint: string;
    status: 'READY' | 'REVIEW_REQUIRED' | 'BLOCKED';
    findings: BusinessMirrorReadinessFinding[];
    createdAt: string;
  };
  snapshot: unknown | null;
  authorityGeneration: string;
  completedAt: string;
}

export type BusinessMirrorTaskId =
  | 'problem'
  | 'boundary'
  | 'capabilities'
  | 'scenarios'
  | 'rehearsal'
  | 'calibrate';

export interface BusinessMirrorPortfolioItem {
  packageId: string;
  graphName: string;
  displayName: string;
  status: 'BLOCKED' | 'REVIEW_REQUIRED' | 'READY';
  blockerCount: number;
  imported: boolean;
  revision: number;
  owner: string;
  domainId: string;
  projection: LegacyGraphPackageProjection;
  stored: StoredBusinessMirrorPackage | null;
}

export interface BusinessMirrorCapabilityLayer {
  id: 'L0' | 'L1' | 'L2' | 'L3';
  refs: Array<{ id: string; kind: string; missing: boolean }>;
}

const BLOCKER_TASKS: Record<string, BusinessMirrorTaskId> = {
  ACCOUNTABLE_OWNER_MISSING: 'problem',
  BUSINESS_DOMAIN_MISSING: 'problem',
  BUSINESS_GOAL_MISSING: 'problem',
  EXPECTED_OUTCOME_MISSING: 'problem',
  PROBLEM_CODE_MISSING: 'problem',
  PROBLEM_TAXONOMY_MISSING: 'problem',
  PACKAGE_CONTRACT_MISSING: 'boundary',
  GRAPH_CONTRACT_OWNER_CONFIRMATION_MISSING: 'boundary',
  HIGH_RISK_EFFECT_MODEL_MISSING: 'boundary',
  HIGH_RISK_STATE_MODEL_MISSING: 'boundary',
  EXECUTABLE_PROJECTION_MISSING: 'capabilities',
  SOLUTION_BINDING_MISSING: 'capabilities',
  SERVICE_CARRIER_BINDING_MISSING: 'capabilities',
  CHANNEL_BINDING_MISSING: 'capabilities',
  SCENARIO_INVENTORY_MISSING: 'scenarios',
  SCENARIO_PACK_MISSING: 'scenarios',
  DISCOVERED_TEST_SUITE_REQUIRES_SCENARIO_GOVERNANCE: 'scenarios',
  MIRROR_PLAN_MISSING: 'rehearsal',
  FIDELITY_INVENTORY_MISSING: 'calibrate',
  OUTCOME_DEFINITION_MISSING: 'calibrate',
  LEGACY_PROJECTION_OWNER_APPROVAL_MISSING: 'calibrate',
};

export function projectBusinessMirrorPortfolio(
  catalog: LegacyGraphPackageProjectionCatalog,
  packages: BusinessMirrorPackagePage,
): BusinessMirrorPortfolioItem[] {
  const imported = new Map(packages.items.map((item) => [item.draft.packageId, item]));
  return catalog.items.map<BusinessMirrorPortfolioItem>((projection) => {
    const stored = imported.get(projection.packageDraft.packageId) ?? null;
    const gaps = effectiveBusinessMirrorGaps(projection, stored, null);
    return {
      packageId: projection.packageDraft.packageId,
      graphName: projection.graphName,
      displayName: humanizeBusinessMirrorName(projection.graphName),
      status: gaps.some((gap) => gap.severity === 'BLOCKING') ? 'BLOCKED' : 'REVIEW_REQUIRED',
      blockerCount: gaps.filter((gap) => gap.severity === 'BLOCKING').length,
      imported: stored !== null,
      revision: stored?.draft.revision ?? 0,
      owner: stored?.draft.businessDefinition.accountableOwner ?? '',
      domainId: stored?.draft.businessDefinition.domainId ?? '',
      projection,
      stored,
    };
  }).sort((left, right) => left.displayName.localeCompare(right.displayName));
}

export function effectiveBusinessMirrorGaps(
  projection: LegacyGraphPackageProjection,
  stored: StoredBusinessMirrorPackage | null,
  compilation: BusinessMirrorCompilationReceipt | null,
): BusinessMirrorGap[] {
  if (compilation) {
    const compiled = compilation.readiness.findings.map<BusinessMirrorGap>((finding) => ({
      code: finding.code,
      origin: 'PACKAGE_READINESS',
      category: finding.category,
      severity: finding.severity === 'ERROR' ? 'BLOCKING' : 'WARNING',
      draftPath: finding.fieldPath,
      explanation: finding.messageId,
      requiredAction: finding.messageId,
      evidenceRefs: finding.artifactRef ? [finding.artifactRef] : [],
    }));
    return uniqueSortedGaps([
      ...compiled,
      ...projection.gaps.filter((gap) => gap.origin === 'MIGRATION_POLICY'),
    ]);
  }
  if (!stored) return projection.gaps;
  const activeReadiness = new Set(deriveBusinessMirrorDraftBlockers(stored.draft));
  return projection.gaps.filter((gap) => (
    gap.origin === 'MIGRATION_POLICY' || activeReadiness.has(gap.code)
  ));
}

export function deriveBusinessMirrorDraftBlockers(draft: BusinessMirrorPackageDraft): string[] {
  const definition = draft.businessDefinition;
  const blockers: string[] = [];
  if (!definition.domainId) blockers.push('BUSINESS_DOMAIN_MISSING');
  if (!definition.problemTaxonomyRef) blockers.push('PROBLEM_TAXONOMY_MISSING');
  if (!definition.problemCode) blockers.push('PROBLEM_CODE_MISSING');
  if (!definition.businessGoal) blockers.push('BUSINESS_GOAL_MISSING');
  if (!definition.expectedOutcome) blockers.push('EXPECTED_OUTCOME_MISSING');
  if (!definition.accountableOwner) blockers.push('ACCOUNTABLE_OWNER_MISSING');
  if (!draft.packageContractRef) blockers.push('PACKAGE_CONTRACT_MISSING');
  if (draft.capabilityRefs.length === 0 && draft.graphRefs.length === 0) {
    blockers.push('EXECUTABLE_PROJECTION_MISSING');
  }
  if (!draft.scenarioInventoryRef) blockers.push('SCENARIO_INVENTORY_MISSING');
  if (draft.scenarioPackRefs.length === 0) blockers.push('SCENARIO_PACK_MISSING');
  if (draft.solutionRefs.length === 0) blockers.push('SOLUTION_BINDING_MISSING');
  if (draft.carrierRefs.length === 0) blockers.push('SERVICE_CARRIER_BINDING_MISSING');
  if (draft.channelRefs.length === 0) blockers.push('CHANNEL_BINDING_MISSING');
  if (!draft.fidelityInventoryRef) blockers.push('FIDELITY_INVENTORY_MISSING');
  if (draft.outcomeDefinitionRefs.length === 0) blockers.push('OUTCOME_DEFINITION_MISSING');
  if ((definition.riskClass === 'HIGH' || definition.riskClass === 'CRITICAL')
      && draft.stateModelRefs.length === 0) blockers.push('HIGH_RISK_STATE_MODEL_MISSING');
  if ((definition.riskClass === 'HIGH' || definition.riskClass === 'CRITICAL')
      && draft.effectModelRefs.length === 0) blockers.push('HIGH_RISK_EFFECT_MODEL_MISSING');
  return blockers.sort();
}

export function businessMirrorTaskForGap(gap: BusinessMirrorGap): BusinessMirrorTaskId {
  return BLOCKER_TASKS[gap.code] ?? taskForCategory(gap.category);
}

export function businessMirrorTaskProgress(
  task: BusinessMirrorTaskId,
  gaps: BusinessMirrorGap[],
): 'BLOCKED' | 'REVIEW' | 'COMPLETE' {
  const taskGaps = gaps.filter((gap) => businessMirrorTaskForGap(gap) === task);
  if (taskGaps.some((gap) => gap.severity === 'BLOCKING')) return 'BLOCKED';
  return taskGaps.length > 0 ? 'REVIEW' : 'COMPLETE';
}

export function businessMirrorCapabilityLayers(
  projection: LegacyGraphPackageProjection,
  draft: BusinessMirrorPackageDraft,
): BusinessMirrorCapabilityLayer[] {
  const l0 = [projection.projectedCapabilityRef, ...draft.graphRefs]
    .map((ref) => ({ id: ref.id, kind: ref.kind, missing: false }));
  return [
    { id: 'L0', refs: l0.length ? l0 : [missingRef('CAPABILITY')] },
    { id: 'L1', refs: draft.solutionRefs.length
      ? draft.solutionRefs.map(assetRef) : [missingRef('SOLUTION')] },
    { id: 'L2', refs: draft.carrierRefs.length
      ? draft.carrierRefs.map(assetRef) : [missingRef('SERVICE_CARRIER')] },
    { id: 'L3', refs: draft.channelRefs.length
      ? draft.channelRefs.map(assetRef) : [missingRef('CHANNEL_APPLICATION')] },
  ];
}

export function humanizeBusinessMirrorName(value: string): string {
  return value
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[-_]+/g, ' ')
    .replace(/\b\w/g, (character) => character.toUpperCase());
}

function taskForCategory(category: string): BusinessMirrorTaskId {
  if (category === 'BUSINESS_CONTEXT') return 'problem';
  if (category === 'CONTRACT' || category === 'EXECUTION_MODEL') return 'boundary';
  if (category === 'SERVICE_ASSET') return 'capabilities';
  if (category === 'SCENARIO') return 'scenarios';
  if (category === 'FIDELITY' || category === 'OUTCOME') return 'calibrate';
  return 'calibrate';
}

function uniqueSortedGaps(gaps: BusinessMirrorGap[]): BusinessMirrorGap[] {
  const unique = new Map(gaps.map((gap) => [gap.code, gap]));
  return [...unique.values()].sort((left, right) => left.code.localeCompare(right.code));
}

function assetRef(ref: BusinessMirrorAssetRef): { id: string; kind: string; missing: boolean } {
  return { id: ref.id, kind: ref.kind, missing: false };
}

function missingRef(kind: string): { id: string; kind: string; missing: boolean } {
  return { id: '', kind, missing: true };
}
