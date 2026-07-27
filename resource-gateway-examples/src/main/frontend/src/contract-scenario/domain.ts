import type { GraphDraft, SchemaEnvelope } from '../types';

export type ContractTargetKind = 'GRAPH' | 'OPERATOR';
export type ContractSource = 'AUTHORED' | 'DSL' | 'IMPORTED' | 'INFERRED';
export type ContractConfidence = 'EXACT' | 'INFERRED' | 'OPAQUE';
export type ContractEffect = 'PURE' | 'READ' | 'WRITE' | 'UNKNOWN';
export type ScenarioCaseType = 'GOLDEN' | 'NEGATIVE' | 'BOUNDARY' | 'REGRESSION' | 'PROPERTY';
export type ValueProvenance = 'AUTHORED' | 'GENERATED' | 'IMPORTED' | 'CAPTURED' | 'MIGRATED';
export type DependencyBehaviorKind =
  'REAL' | 'RETURN' | 'ERROR' | 'DELAY' | 'TIMEOUT' | 'REPLAY' | 'OBSERVE' | 'MUST_NOT_CALL';
export type DependencyBehaviorBoundary = 'NODE' | 'TRANSPORT';
export type AssertionScope =
  'OUTPUT_PATH' | 'NODE_OUTPUT' | 'NODE_STATUS' | 'EDGE_TRANSFER' | 'INVOCATION';
export type AssertionOperator =
  'EQUALS' | 'MATCHES_SCHEMA' | 'EXISTS' | 'ABSENT' | 'STATUS' | 'USED' | 'NOT_USED';

export interface ExactTargetRef {
  kind: ContractTargetKind;
  id: string;
  revision: number;
  fingerprint: string;
}

export interface ErrorVariant {
  code: string;
  type: string;
  description: string;
  retryable: boolean;
}

export interface ContractInvariant {
  invariantId: string;
  phase: 'PRECONDITION' | 'POSTCONDITION';
  expression: string;
  description: string;
  severity: 'ERROR' | 'WARNING';
}

export interface FieldMetadata {
  displayName: string;
  description: string;
  classification: 'PUBLIC' | 'INTERNAL' | 'CONFIDENTIAL' | 'RESTRICTED';
  source: ContractSource;
  confidence: ContractConfidence;
  extensions: Record<string, unknown>;
}

export interface ContractDraft {
  schemaVersion: 'bloge.contractDraft.v1';
  target: ExactTargetRef;
  inputSchema: SchemaEnvelope;
  outputSchema: SchemaEnvelope;
  errorContract: ErrorVariant[];
  executionSemantics: {
    effect: ContractEffect;
    idempotency: string;
    streaming: boolean | null;
    durable: boolean | null;
    sideEffectProtocol?: {
      protocol: string;
      reconcilerRef: string;
      reversible: boolean;
      metadata: Record<string, unknown>;
    };
  };
  invariants: ContractInvariant[];
  compatibilityPolicy: {
    mode: 'STRICT' | 'BACKWARD' | 'FORWARD' | 'NONE';
    unknownBlocksAutomaticMigration: boolean;
  };
  fieldMetadata: Record<string, FieldMetadata>;
  source: ContractSource;
  confidence: ContractConfidence;
}

export interface EnterpriseScope {
  tenantId: string;
  organizationId: string;
  projectId: string;
  environment: string;
  region: string;
}

export interface ScenarioDraftSet {
  schemaVersion: 'bloge.scenarioDraftSet.v1';
  scenarioDraftSetId: string;
  revision: number;
  scope: EnterpriseScope;
  target: ExactTargetRef;
  contractFingerprint: string;
  scenarios: ScenarioDraft[];
  metadata: {
    owner: string;
    classification: 'PUBLIC' | 'INTERNAL' | 'CONFIDENTIAL' | 'RESTRICTED';
    createdAt: string | null;
    updatedAt: string | null;
    provenance: Record<string, unknown>;
  };
}

export interface ScenarioDraft {
  scenarioId: string;
  name: string;
  description: string;
  caseType: ScenarioCaseType;
  tags: string[];
  given: {
    input: unknown;
    provenance: ValueProvenance;
  };
  dependencies: DependencyBehaviorDraft[];
  then: {
    assertions: AssertionDraft[];
  };
}

export interface DependencyBehaviorDraft {
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
    pathEquals: Record<string, unknown>;
  };
  behavior: {
    kind: DependencyBehaviorKind;
    boundary: DependencyBehaviorBoundary;
    output?: unknown;
    expectedInput?: unknown;
    rawBody?: string;
    statusCode?: number;
    headers?: Record<string, string>;
    errorCode?: string;
    errorType?: string;
    errorMessage?: string;
    after?: string;
    replayRef?: string;
  };
  consumption: {
    required: boolean;
    minUses: number;
    maxUses: number;
    onExhausted: 'FAIL' | 'FALLBACK_TO_REAL';
    onUnmatched: 'FAIL' | 'WARN' | 'ALLOW_REAL';
  };
  schemaCheck: {
    mode: 'STRICT' | 'WAIVED';
    waiverReason: string;
  };
  origin: string;
}

export interface AssertionDraft {
  assertionId: string;
  scope: AssertionScope;
  nodeId: string;
  fromNodeId: string;
  toNodeId: string;
  path: string;
  operator: AssertionOperator;
  expected?: unknown;
  numericTolerance?: number;
}

export type ScenarioValidationStatus = 'VALID' | 'INVALID' | 'STALE' | 'UNKNOWN';

export interface ScenarioValidationReport {
  schemaVersion: 'bloge.scenarioValidationReport.v1';
  targetFingerprint: string;
  contractFingerprint: string;
  scenarioDraftSetRevision: number;
  status: ScenarioValidationStatus;
  diagnostics: ScenarioDiagnostic[];
  compatibility: unknown[];
  impactedBindings: unknown[];
  impactedScenarios: unknown[];
  publicationImpact: unknown[];
}

export interface ScenarioDiagnostic {
  level: 'ERROR' | 'WARNING' | 'INFO';
  code: string;
  message: string;
  target: string;
}

export interface StoredScenarioDraftSet {
  schemaVersion: 'bloge.storedScenarioDraftSet.v1';
  scenarioDraftSetId: string;
  revision: number;
  fingerprint: string;
  draftSet: ScenarioDraftSet;
  savedAt: string;
  savedBy: string;
}

export interface ScenarioContractProjection {
  schemaVersion: 'bloge.scenarioContractProjection.v1';
  scope: EnterpriseScope;
  contract: ContractDraft;
  contractFingerprint: string;
}

export interface ScenarioPublicationAssetRef {
  kind: 'FIXTURE_BUNDLE' | 'TEST_SUITE';
  id: string;
  revision: number;
  fingerprint: string;
}

export interface ScenarioPublicationReport {
  schemaVersion: 'bloge.scenarioPublicationReport.v1';
  publicationId: string;
  scope: EnterpriseScope;
  source: {
    scenarioDraftSetId: string;
    revision: number;
    fingerprint: string;
    targetFingerprint: string;
    contractFingerprint: string;
    compilerSchemaVersion: 'bloge.scenarioGovernedCompilationPlan.v1';
    compilationPlanFingerprint: string;
  };
  runtimeTarget: {
    kind: 'GRAPH';
    id: string;
    fingerprint: string;
  };
  status: 'IN_PROGRESS' | 'PARTIAL' | 'FAILED' | 'PUBLISHED';
  attempt: number;
  fixtures: ScenarioPublicationAssetRef[];
  suite: ScenarioPublicationAssetRef | null;
  diagnostics: string[];
  failure: {
    stage: string;
    code: string;
    retryable: boolean;
  };
  startedAt: string;
  updatedAt: string;
  completedAt: string | null;
  actor: string;
}

export interface StoredScenarioPublication {
  schemaVersion: 'bloge.storedScenarioPublication.v1';
  stateVersion: number;
  fingerprint: string;
  report: ScenarioPublicationReport;
}

const OPAQUE_SCHEMA: SchemaEnvelope = {
  format: 'json-schema',
  version: '2020-12',
  schema: { type: 'object', additionalProperties: true },
};

/** Projects the current GraphDraft contract without mutating the v1 graph wire contract. */
export function contractDraftFromGraphDraft(
  draft: GraphDraft,
  targetFingerprint: string,
): ContractDraft {
  const inputSchema = draft.inputSchema ?? OPAQUE_SCHEMA;
  const outputSchema = draft.outputSchema ?? OPAQUE_SCHEMA;
  const confidence: ContractConfidence = schemaIsOpaque(inputSchema) || schemaIsOpaque(outputSchema)
    ? 'OPAQUE'
    : 'EXACT';
  return {
    schemaVersion: 'bloge.contractDraft.v1',
    target: {
      kind: 'GRAPH',
      id: draft.draftId || draft.graphName,
      revision: Math.max(0, draft.revision ?? 0),
      fingerprint: targetFingerprint.trim(),
    },
    inputSchema,
    outputSchema,
    errorContract: [],
    executionSemantics: {
      effect: 'UNKNOWN',
      idempotency: 'UNKNOWN',
      streaming: null,
      durable: null,
    },
    invariants: [],
    compatibilityPolicy: {
      mode: 'STRICT',
      unknownBlocksAutomaticMigration: true,
    },
    fieldMetadata: {},
    source: 'AUTHORED',
    confidence,
  };
}

/** Creates a separate mutable Scenario asset for one exact contract. */
export function emptyScenarioDraftSet(
  target: ExactTargetRef,
  contractFingerprint: string,
  scope: EnterpriseScope,
): ScenarioDraftSet {
  return {
    schemaVersion: 'bloge.scenarioDraftSet.v1',
    scenarioDraftSetId: '',
    revision: 0,
    scope: { ...scope },
    target: { ...target },
    contractFingerprint: contractFingerprint.trim(),
    scenarios: [],
    metadata: {
      owner: '',
      classification: 'INTERNAL',
      createdAt: null,
      updatedAt: null,
      provenance: {},
    },
  };
}

function schemaIsOpaque(envelope: SchemaEnvelope): boolean {
  const type = envelope.schema?.type;
  return Object.keys(envelope.schema ?? {}).length === 0 || type === 'opaque';
}
