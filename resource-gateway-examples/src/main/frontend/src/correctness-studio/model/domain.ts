import type { CorrectnessVerdictInput } from './verdictPresentationPolicy';

export type CorrectnessTargetKind = 'GRAPH' | 'OPERATOR' | 'FUNCTION';
export type CorrectnessRiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type CorrectnessSelectionMode = 'ALL' | 'SELECTED';

export interface CorrectnessApiEnvelope<T> {
  protocolVersion: 'bloge.correctnessApi.v1';
  correlationId: string;
  capabilities: string[];
  scope: EnterpriseScope;
  data: T;
}

export interface EnterpriseScope {
  tenantId: string;
  organizationId: string;
  projectId: string;
  environment: string;
  region: string;
}

export interface ExactAssetRef {
  kind: string;
  id: string;
  revision: number;
  fingerprint: string;
}

export interface ExactTargetRef {
  kind: CorrectnessTargetKind;
  id: string;
  revision: number;
  fingerprint: string;
}

export interface PrincipalRef {
  id: string;
  kind: 'USER' | 'TEAM' | 'SERVICE';
  displayName: string;
}

export interface CorrectnessVerdict extends CorrectnessVerdictInput {
  reasons: Array<{ code: string; axis: string; messageId: string }>;
  nextActions: Array<{ command: string; reasonCode: string }>;
}

export interface CorrectnessWorkspaceProjection {
  schemaVersion: 'bloge.correctnessWorkspaceProjection.v1';
  queryFingerprint: string;
  target: ExactTargetRef;
  definition: {
    definitionRef: ExactAssetRef;
    title: string;
    businessIntent: string;
    successCriteria: string[];
    riskLevel: CorrectnessRiskLevel;
    owner: PrincipalRef;
    lifecycle: string;
  };
  coverage: {
    availability: 'AVAILABLE' | 'UNAVAILABLE';
    inventoryRef: ExactAssetRef | null;
    lifecycle: string;
    total: number;
    fulfilled: number;
    waived: number;
    uncovered: number;
  };
  oracleAssertions: {
    availability: 'AVAILABLE' | 'UNAVAILABLE';
    oracleTotal: number;
    proposedOracles: number;
    approvedOracles: number;
    supersededOracles: number;
    assertionSetTotal: number;
    draftAssertionSets: number;
    validAssertionSets: number;
    staleAssertionSets: number;
    unsupportedAssertionSets: number;
  };
  cases: {
    availability: 'AVAILABLE' | 'UNAVAILABLE';
    scenarioDraftSetRef: ExactAssetRef | null;
    total: number;
    rows: CorrectnessCaseSummary[];
    nextCursor: string;
    queryFingerprint: string;
  };
  fixtures: {
    availability: 'AVAILABLE' | 'UNAVAILABLE';
    total: number;
    active: number;
    stale: number;
    rows: CorrectnessFixtureSummary[];
  };
  reviews: { pending: number; approved: number; rejected: number; stale: number };
  lastPublication: {
    publicationRef: ExactAssetRef;
    lifecycle: string;
    publishedAt: string;
  } | null;
  lastRun: {
    runId: string;
    finishedAt: string;
    executionStatus: string;
    assertionStatus: string;
    evidenceRef: ExactAssetRef | null;
  } | null;
  verdict: CorrectnessVerdict;
  staleReasons: Array<{ code: string; assetKind: string; assetRef: ExactAssetRef | null }>;
  capabilities: string[];
  commandPolicy: {
    commands: Record<string, { allowed: boolean; reasonCode: string }>;
  };
  deepLinks: {
    workspace: string;
    definition: string;
    cases: string;
    fixtures: string;
    lastRun: string;
  };
}

export interface CorrectnessCaseSummary {
  scenarioDraftSetRef: ExactAssetRef;
  caseId: string;
  caseFingerprint: string;
  name: string;
  businessIntent: string;
  caseType: string;
  risk: CorrectnessRiskLevel;
  owner: PrincipalRef;
  lifecycle: string;
  obligationCount: number;
  oracleCount: number;
  assertionSetCount: number;
  dependencyCount: number;
  reviewStatus: string;
  tags: string[];
}

export interface CorrectnessFixtureSummary {
  descriptorRef: ExactAssetRef;
  name: string;
  variantKey: string;
  lifecycle: string;
  classification: string;
  schemaRef: { id: string; revision: number; fingerprint: string };
  materialFingerprint: string;
  usageCount: number;
}

export interface CorrectnessPublicationRef {
  publicationId: string;
  revision: 1;
  fingerprint: string;
}

export interface CorrectnessSelectionIntent {
  mode: CorrectnessSelectionMode;
  caseIds: string[];
  expectedSelectionFingerprint: string;
}

export interface CorrectnessSelection {
  mode: CorrectnessSelectionMode;
  caseIds: string[];
  selectionFingerprint: string;
}

export interface CorrectnessPreflightRequest {
  schemaVersion: 'bloge.correctnessPreflightRequest.v1';
  publicationRef: CorrectnessPublicationRef;
  selection: CorrectnessSelectionIntent;
}

export interface CorrectnessPreflightReport {
  schemaVersion: 'bloge.correctnessPreflightReport.v1';
  publicationRef: CorrectnessPublicationRef;
  target: ExactTargetRef;
  compiledTestSuiteRef: ExactAssetRef;
  selection: CorrectnessSelection;
  proofLevel: CorrectnessVerdict['proofLevel'];
  cases: CorrectnessPreflightCasePlan[];
  riskSummary: CorrectnessRiskSummary;
  blockers: Array<{ code: string; messageId: string; caseId: string }>;
  preflightFingerprint: string;
}

export interface CorrectnessPreflightCasePlan {
  caseId: string;
  caseType: string;
  fixtureBundleRef: ExactAssetRef;
  executionPlanFingerprint: string;
  invocationSites: Array<{
    invocationSiteId: string;
    graphPath: string;
    nodeId: string;
    operatorRef: string;
    resourceRef: string;
    functionRef: string;
    runtimeBindingFingerprint: string;
    invocationKind: string;
    sideEffectType: string;
    resolution: 'REAL' | 'TEST_DOUBLE' | 'DENIED';
    behavior: string;
    boundary: 'NODE' | 'TRANSPORT';
    ruleRefs: string[];
    fidelity: string;
  }>;
  rulePolicies: Array<{
    ruleId: string;
    behavior: string;
    boundary: string;
    required: boolean;
    minUses: number;
    maxUses: number;
    onUnmatched: string;
    onExhausted: string;
    schemaCheckMode: string;
  }>;
  executionServices: Array<{
    service: string;
    mode: string;
    available: boolean;
    deterministic: boolean;
    configurationFingerprint: string;
    consumers: string[];
    certificationGaps: string[];
  }>;
  replayDependencyCount: number;
}

export interface CorrectnessRiskSummary {
  realCount: number;
  mockedCount: number;
  faultCount: number;
  replayCount: number;
  observeCount: number;
  deniedCount: number;
  fallbackToRealCount: number;
  transportBoundaryCount: number;
  secretRequirementCount: number;
  logicalClockConfigured: boolean;
  sideEffectTypes: string[];
}

export interface CorrectnessRunRequest {
  schemaVersion: 'bloge.correctnessRunRequest.v1';
  publicationRef: CorrectnessPublicationRef;
  selection: CorrectnessSelection;
  preflightFingerprint: string;
  clientRequestId: string;
  strategy: 'COLLECT_ALL' | 'FAIL_FAST';
}

export interface CorrectnessRunResponse {
  schemaVersion: 'bloge.correctnessRunResponse.v1';
  status: 'RUNNING' | 'EVIDENCE_AVAILABLE';
  suiteExecution: {
    schemaVersion: string;
    suiteRunId: string;
    evidenceFingerprint: string;
    evidence: { status: string };
    attestation?: Record<string, unknown>;
  };
  evidenceCompanion: StoredCorrectnessEvidenceCompanion | null;
}

export interface StoredCorrectnessEvidenceCompanion {
  schemaVersion: 'bloge.storedCorrectnessEvidenceCompanion.v1';
  companionFingerprint: string;
  companion: CorrectnessEvidenceCompanion;
}

export interface CorrectnessEvidenceCompanion {
  schemaVersion: 'bloge.correctnessEvidenceCompanion.v1';
  evidenceCompanionId: string;
  scope: EnterpriseScope;
  suiteRunId: string;
  suiteEvidenceFingerprint: string;
  clientRequestFingerprint: string;
  publicationRef: CorrectnessPublicationRef;
  target: ExactTargetRef;
  definitionRef: ExactAssetRef;
  inventoryRef: ExactAssetRef;
  scenarioDraftSetRef: ExactAssetRef;
  caseRefs: Array<{
    scenarioDraftSetRef: ExactAssetRef;
    caseId: string;
    caseFingerprint: string;
  }>;
  oracleRefs: ExactAssetRef[];
  assertionSetRefs: ExactAssetRef[];
  fixtureAssetRefs: ExactAssetRef[];
  compiledFixtureBundleRefs: ExactAssetRef[];
  compiledTestSuiteRef: ExactAssetRef;
  selection: CorrectnessSelection;
  caseExecutions: Array<{
    caseId: string;
    fixtureBundleRef: ExactAssetRef;
    executionPlanFingerprint: string;
    status: string;
    childRunId: string;
    evidenceClass: 'EXPLORATORY' | 'CERTIFIABLE' | null;
  }>;
  sourceMap: Array<{
    source: { assetRef: ExactAssetRef; elementKind: string; elementId: string };
    output: { assetRef: ExactAssetRef; elementKind: string; elementId: string };
  }>;
  riskSummary: CorrectnessRiskSummary;
  dataClassifications: string[];
  verdict: CorrectnessVerdict;
  attestation: {
    signatureStatus: string;
    scope: string;
    independentlyVerifiable: boolean;
    [key: string]: unknown;
  };
  metadata: {
    createdAt: string;
    updatedAt: string;
    createdBy: PrincipalRef;
    updatedBy: PrincipalRef;
  };
}

export interface CorrectnessDeploymentCapabilities {
  schemaVersion: string;
  features: Record<string, boolean>;
  endpoints: Array<{ method: string; path: string }>;
}

export interface IntegrationEnvelope<T> {
  payload: T;
  [key: string]: unknown;
}

export interface CorrectnessWorkspaceCoordinate {
  targetKind: CorrectnessTargetKind;
  targetId: string;
  targetFingerprint: string;
  definitionId?: string;
  caseCursor?: string;
  caseLimit?: number;
}
