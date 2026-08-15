import type {
  CorrectnessRiskLevel,
  EnterpriseScope,
  ExactAssetRef,
  ExactTargetRef,
  PrincipalRef,
} from './domain';

export interface AuditMetadata {
  createdAt: string;
  updatedAt: string;
  createdBy: PrincipalRef;
  updatedBy: PrincipalRef;
}

export interface ReviewRecord {
  status: 'PENDING' | 'APPROVED' | 'REJECTED';
  reviewer: PrincipalRef | null;
  reviewedAt: string | null;
  comment: string;
}

export interface ExactSchemaRef {
  id: string;
  revision: number;
  fingerprint: string;
}

export interface ExactObligationRef {
  inventoryRef: ExactAssetRef;
  obligationId: string;
  obligationFingerprint: string;
}

export interface CoverageInventory {
  schemaVersion: 'bloge.coverageInventory.v1';
  inventoryId: string;
  revision: number;
  scope: EnterpriseScope;
  target: ExactTargetRef;
  lifecycle: 'DRAFT' | 'FROZEN' | 'SUPERSEDED';
  obligations: CoverageObligation[];
  derivationSources: Array<ExactAssetRef>;
  freezeReview: ReviewRecord;
  metadata: AuditMetadata;
}

export interface CoverageObligation {
  obligationId: string;
  dimension: 'CONTRACT' | 'PATH' | 'POLICY' | 'RISK' | 'INCIDENT' | 'BOUNDARY';
  title: string;
  statement: string;
  risk: CorrectnessRiskLevel;
  owner: PrincipalRef;
  source: 'AUTOMATED' | 'BUSINESS' | 'INCIDENT' | 'MIGRATED';
  lifecycle: 'PROPOSED' | 'FROZEN' | 'WAIVED' | 'RETIRED';
  waiver: null | {
    reason: string;
    expiresAt: string;
    approvedBy: PrincipalRef;
    approvedAt: string;
  };
  tags: string[];
}

export interface StoredCoverageInventory {
  schemaVersion: 'bloge.storedCoverageInventory.v1';
  inventoryFingerprint: string;
  inventory: CoverageInventory;
}

export interface BusinessOracle {
  schemaVersion: 'bloge.businessOracle.v1';
  oracleId: string;
  revision: number;
  scope: EnterpriseScope;
  target: ExactTargetRef;
  statement: string;
  forbiddenOutcomes: string[];
  basisRefs: ExactAssetRef[];
  owner: PrincipalRef;
  lifecycle: 'PROPOSED' | 'APPROVED' | 'SUPERSEDED';
  approval: ReviewRecord;
  assertionSetRefs: ExactAssetRef[];
  metadata: AuditMetadata;
}

export interface StoredBusinessOracle {
  schemaVersion: 'bloge.storedBusinessOracle.v1';
  oracleFingerprint: string;
  oracle: BusinessOracle;
}

export type AssertionType =
  'OUTPUT' | 'ERROR' | 'NODE' | 'EDGE' | 'INVOCATION' | 'STATE_EFFECT' | 'GOVERNANCE';

export interface ExecutableAssertionSpec {
  type: AssertionType;
  assertionId: string;
  evaluationKind: 'RUNTIME' | 'EVIDENCE' | 'GATE';
  path?: string;
  operator: string;
  expected?: unknown;
  code?: string;
  errorType?: string;
  retryable?: boolean | null;
  nodeId?: string;
  fromNodeId?: string;
  toNodeId?: string;
  operatorRef?: string;
  stateOrEffect?: string;
}

export interface AssertionSet {
  schemaVersion: 'bloge.assertionSet.v1';
  assertionSetId: string;
  revision: number;
  target: ExactTargetRef;
  oracleRef: ExactAssetRef;
  lifecycle: 'DRAFT' | 'VALID' | 'STALE';
  assertions: ExecutableAssertionSpec[];
  compatibility: {
    supported: boolean;
    evaluatorVersion: string;
    capabilities: string[];
    reasonCode: string;
  };
  metadata: AuditMetadata;
}

export interface StoredAssertionSet {
  schemaVersion: 'bloge.storedAssertionSet.v1';
  assertionSetFingerprint: string;
  assertionSet: AssertionSet;
}

export interface AssertionCompilationReport {
  schemaVersion: 'bloge.assertionCompilationReport.v1';
  supported: boolean;
  evaluatorVersion: string;
  capabilities: string[];
  dispositions: Array<{
    assertionId: string;
    type: AssertionType;
    supported: boolean;
    reasonCode: string;
  }>;
  diagnostics: Array<{ severity: string; code: string; fieldPath: string }>;
}

export type ScenarioValueSource =
  | { kind: 'INLINE'; value: unknown }
  | { kind: 'FIXTURE_VARIANT'; fixtureAssetRef: ExactAssetRef; variantKey: string }
  | { kind: 'GENERATED'; generatorRef: ExactAssetRef; deterministicSeedFingerprint: string }
  | { kind: 'REPLAY_MATERIAL'; replayMaterialRef: ExactAssetRef };

export interface ControlledDependency {
  dependencyId: string;
  selector: {
    graphPath: string;
    nodeId: string;
    operatorRef: string;
    resourceRef: string;
    functionRef: string;
    attempts: number[];
    occurrences: number[];
    correlationKey: string;
    pathMatches: Array<{ path: string; expected: unknown }>;
  };
  behavior: {
    kind: 'REAL' | 'RETURN' | 'ERROR' | 'DELAY' | 'TIMEOUT' | 'REPLAY' | 'OBSERVE' | 'MUST_NOT_CALL';
    boundary: 'NODE' | 'TRANSPORT';
    value: ScenarioValueSource | null;
    errorCode: string;
    delayMs: number;
  };
  consumption: {
    required: boolean;
    minUses: number;
    maxUses: number;
    onExhausted: 'FAIL' | 'REPEAT_LAST' | 'FALLBACK_TO_REAL';
    onUnmatched: 'FAIL' | 'ALLOW_REAL';
  };
}

export interface ScenarioDraftV2 {
  scenarioId: string;
  name: string;
  businessIntent: string;
  description: string;
  caseType: 'GOLDEN' | 'NEGATIVE' | 'BOUNDARY' | 'REGRESSION' | 'PROPERTY';
  risk: CorrectnessRiskLevel;
  owner: PrincipalRef;
  lifecycle: 'EXPLORATORY' | 'REVIEW_READY' | 'CANONICAL' | 'RETIRED';
  obligationRefs: ExactObligationRef[];
  oracleRefs: ExactAssetRef[];
  assertionSetRefs: ExactAssetRef[];
  sourceRefs: ExactAssetRef[];
  given: { input: ScenarioValueSource };
  dependencies: ControlledDependency[];
  review: ReviewRecord;
  tags: string[];
}

export interface ScenarioDraftSetV2 {
  schemaVersion: 'bloge.scenarioDraftSet.v2';
  scenarioDraftSetId: string;
  revision: number;
  scope: EnterpriseScope;
  target: ExactTargetRef;
  contractRef: ExactAssetRef;
  scenarios: ScenarioDraftV2[];
  metadata: AuditMetadata;
}

export interface StoredScenarioDraftSetV2 {
  schemaVersion: 'bloge.storedScenarioDraftSet.v2';
  scenarioDraftSetFingerprint: string;
  scenarioDraftSet: ScenarioDraftSetV2;
}

export interface FixtureAssetDescriptor {
  schemaVersion: 'bloge.fixtureAssetDescriptor.v1';
  fixtureAssetId: string;
  revision: number;
  scope: EnterpriseScope;
  name: string;
  source: { kind: string; sourceRef: ExactAssetRef | null };
  materialRef: ExactAssetRef;
  schemaRef: ExactSchemaRef;
  variantKey: string;
  lifecycle: 'DRAFT' | 'PROPOSED' | 'APPROVED' | 'ACTIVE' | 'STALE' | 'REVOKED' | 'EXPIRED';
  classification: string;
  owner: PrincipalRef;
  redaction: { profileVersion: string; redactedPaths: string[]; reviewed: boolean };
  retention: { policyVersion: string; retentionDays: number; expiresAt: string };
  quality: {
    schemaValid: boolean;
    redactionVerified: boolean;
    duplicateCandidateCount: number;
    usageCount: number;
  };
  tags: string[];
  metadata: AuditMetadata;
}

export interface StoredFixtureAsset {
  schemaVersion: 'bloge.storedFixtureAsset.v1';
  descriptorFingerprint: string;
  descriptor: FixtureAssetDescriptor;
}

export interface FixtureMaterial {
  schemaVersion: 'bloge.fixtureMaterial.v2';
  receipt: FixtureMaterialReceipt;
  payload: unknown;
  payloadReturned: true;
}

export interface FixtureMaterialReceipt {
  schemaVersion: 'bloge.fixtureMaterialReceipt.v2';
  fixtureAssetId: string;
  materialRef: ExactAssetRef;
  payloadFingerprint: string;
  payloadPersisted: true;
  payloadReturned: false;
  [key: string]: unknown;
}

export interface CorrectnessCompilationCoordinate {
  definitionRef: ExactAssetRef;
  inventoryRef: ExactAssetRef;
  scenarioDraftSetRef: ExactAssetRef;
  oracleRefs: ExactAssetRef[];
  assertionSetRefs: ExactAssetRef[];
  fixtureAssetRefs: ExactAssetRef[];
  target: ExactTargetRef;
}

export interface CorrectnessCompilationReport {
  schemaVersion: 'bloge.correctnessCompilationReport.v1';
  publishable: boolean;
  compilerVersion: string;
  coordinate: CorrectnessCompilationCoordinate;
  compilationFingerprint: string;
  sourceMap: Array<Record<string, unknown>>;
  compiledAssets: Array<{ assetRef: ExactAssetRef; sourceElementCount: number }>;
  diagnostics: Array<{
    severity: 'INFO' | 'WARNING' | 'ERROR';
    code: string;
    assetRef: ExactAssetRef | null;
    fieldPath: string;
    messageId: string;
  }>;
  riskSummary: {
    realDependencyCount: number;
    controlledDependencyCount: number;
    faultDependencyCount: number;
    deniedDependencyCount: number;
    fallbackToRealCount: number;
    transportBoundaryCount: number;
    logicalClockRequired: boolean;
    riskCodes: string[];
  };
}
