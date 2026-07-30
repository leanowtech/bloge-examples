// TypeScript mirrors of the relevant BLOGE visual wire contracts. Intentionally minimal — only the
// fields the authoring app reads or sends are modelled.

/** A JSON Schema envelope (bloge SchemaEnvelope). */
export interface SchemaEnvelope {
  format?: string;
  version?: string;
  schema: Record<string, unknown>;
}

/** An operator input/output port. */
export interface OperatorPort {
  name: string;
  schema: SchemaEnvelope;
  required?: boolean;
  description?: string;
}

/** Formal execution contract for an operator that writes to an external system. */
export interface SideEffectProtocol {
  schemaVersion?: string;
  mode?: string;
  commitReceiptRequired?: boolean;
  reconciliationRequired?: boolean;
  reconcilerRef?: string;
  idempotencyKeySource?: string;
  reconciliationLookupSource?: string;
  commitReceiptSource?: string;
}

/** Runtime and governance capabilities projected into the visual operator catalog. */
export interface OperatorCapabilities {
  effect?: string;
  idempotency?: string;
  streaming?: boolean;
  durable?: boolean;
  requiresSecrets?: boolean;
  sideEffectProtocol?: SideEffectProtocol;
}

/** A visual operator definition (bloge.visualOperator.v1). */
export interface OperatorDefinition {
  operatorRef: string;
  operatorVersion?: string;
  fingerprint?: string;
  display?: { name?: string; description?: string; tags?: string[] };
  source?: { kind?: string; libraryId?: string };
  ports?: { inputs: OperatorPort[]; outputs: OperatorPort[] };
  capabilities?: OperatorCapabilities;
  lowering?: {
    mode?: string;
    operatorRef?: string;
    parameters?: Record<string, unknown>;
  };
  runtimeReadiness?: {
    state?: string;
    level?: string;
    executable?: boolean;
    title?: string;
    summary?: string;
  };
}

/** Frozen runtime binding returned by the isolated operator testing control plane. */
export interface OperatorTestTargetDescriptor {
  schemaVersion: string;
  target: {
    kind: 'OPERATOR';
    id: string;
    fingerprint: string;
  };
  testabilityClass: 'EXECUTABLE_UNIT' | 'CONDITIONAL_TRANSPORT' | 'OPAQUE_RUNTIME' | 'UNSUPPORTED_EXECUTION_MODEL';
  executionSupported: boolean;
  certificationEligible: boolean;
  certificationRequirements: string[];
  certificationGaps: string[];
}

/** Sanitized evidence projection returned by one controlled operator micro-graph run. */
export interface OperatorTestExecutionResponse {
  schemaVersion: string;
  runId: string;
  target: {
    kind: 'OPERATOR';
    id: string;
    fingerprint: string;
  };
  evidence: {
    status: string;
    evidenceClass: 'EXPLORATORY' | 'CERTIFIABLE';
    diagnostics?: string[];
    nodeTrace?: Array<{
      nodeId: string;
      operatorRef: string;
      status: string;
      fidelity: string;
      output?: unknown;
      errorCode?: string;
      durationMs?: number;
    }>;
    assertionResults?: Array<{
      scope: string;
      path: string;
      passed: boolean;
      diagnostic?: string;
    }>;
  };
}

/** Immutable fixture revision registered in the isolated testing control plane. */
export interface StoredOperatorTestFixture {
  schemaVersion: 'bloge.storedFixtureBundle.v1' | 'bloge.storedFixtureBundle.v2';
  tenantId: string;
  organizationId?: string;
  projectId?: string;
  environmentId: string;
  region?: string;
  fixtureBundleId: string;
  revision: number;
  fingerprint: string;
  createdAt: string;
  createdBy: string;
}

/** Combined discovery and execution result used by the canvas operator test table. */
export interface OperatorTestCaseRun {
  target: OperatorTestTargetDescriptor;
  response: OperatorTestExecutionResponse;
  storedFixture?: StoredOperatorTestFixture;
}

/** Governance intent assigned to one immutable operator suite case. */
export type OperatorTestSuiteCaseType = 'GOLDEN' | 'NEGATIVE' | 'BOUNDARY' | 'REGRESSION';

/** One Canvas-authored case to publish through the governed suite registry. */
export interface OperatorTestSuiteCaseInput {
  caseId: string;
  caseType: OperatorTestSuiteCaseType;
  name?: string;
  input: unknown;
  expectedOutput: unknown;
  transportResponse: unknown;
}

/** Exact immutable fixture reference retained by a suite case and its evidence. */
export interface GovernedFixtureReference {
  fixtureBundleId: string;
  revision: number;
  fingerprint: string;
}

/** Immutable suite revision returned by the isolated testing control plane. */
export interface StoredOperatorTestSuite {
  schemaVersion: 'bloge.storedTestSuite.v1' | 'bloge.storedTestSuite.v2';
  tenantId: string;
  organizationId?: string;
  projectId?: string;
  environmentId: string;
  region?: string;
  suiteId: string;
  revision: number;
  fingerprint: string;
  suite: {
    schemaVersion: 'bloge.testSuite.v1';
    suiteId: string;
    revision: number;
    target: OperatorTestTargetDescriptor['target'];
    classification: string;
    cases: Array<{
      caseId: string;
      caseType: OperatorTestSuiteCaseType;
      input: unknown;
      fixtureBundleRef: GovernedFixtureReference;
      tags: string[];
      metadata: Record<string, unknown>;
    }>;
    coveragePolicy: {
      minimumCases: number;
      requiredCaseTypes: OperatorTestSuiteCaseType[];
      requiredInvocationSiteIds: string[];
      requiredEdgeTransfers: Array<{
        fromInvocationSiteId: string;
        toInvocationSiteId: string;
      }>;
      minimumAssertionsPerCase: number;
      requireAllFixtureRulesConsumed: boolean;
    };
    promotionPolicy: {
      requireAllCasesPassed: boolean;
      minimumCertifiableCases: number;
      requireTargetCertificationEligible: boolean;
    };
    metadata: Record<string, unknown>;
  };
  createdAt: string;
  createdBy: string;
}

/** Payload-free aggregate evidence returned by one immutable suite execution. */
export interface OperatorTestSuiteExecutionResponse {
  schemaVersion: 'bloge.testSuiteExecutionResponse.v1';
  suiteRunId: string;
  evidenceFingerprint: string;
  evidence: {
    schemaVersion: 'bloge.testSuiteRunEvidence.v1';
    suiteRunId: string;
    clientRequestId: string;
    status: 'RUNNING' | 'PASSED' | 'COMPLETED_WITH_FAILURES' | 'PARTIAL' | 'EVIDENCE_INCOMPLETE';
    executionPurpose: 'TEST_SUITE_EXECUTION';
    suiteRef: { suiteId: string; revision: number; fingerprint: string };
    target: OperatorTestTargetDescriptor['target'];
    caseResults: Array<{
      caseId: string;
      caseType: OperatorTestSuiteCaseType;
      fixtureBundleRef: GovernedFixtureReference;
      status: 'PENDING' | 'PASSED' | 'FAILED' | 'NOT_SCHEDULED' | 'EVIDENCE_INCOMPLETE';
      runId: string;
      evidenceStatus: string | null;
      evidenceClass: 'EXPLORATORY' | 'CERTIFIABLE' | null;
      assertionsEvaluated: number;
      assertionsPassed: number;
      diagnosticCode: string;
      diagnostic: string;
    }>;
    coverage: {
      status: 'NOT_EVALUATED' | 'SATISFIED' | 'UNSATISFIED' | 'INCOMPLETE';
      minimumCases: number;
      completedCases: number;
      requiredCaseTypes: OperatorTestSuiteCaseType[];
      observedCaseTypes: OperatorTestSuiteCaseType[];
      missingCaseTypes: OperatorTestSuiteCaseType[];
      requiredInvocationSiteIds: string[];
      observedInvocationSiteIds: string[];
      missingInvocationSiteIds: string[];
      requiredEdgeTransfers: Array<{ fromInvocationSiteId: string; toInvocationSiteId: string }>;
      observedEdgeTransfers: Array<{ fromInvocationSiteId: string; toInvocationSiteId: string }>;
      missingEdgeTransfers: Array<{ fromInvocationSiteId: string; toInvocationSiteId: string }>;
      minimumAssertionsPerCase: number;
      assertionDensityViolations: string[];
      fixtureConsumptionViolations: string[];
      allCasesCompleted: boolean;
    };
    promotion: {
      status: 'NOT_EVALUATED' | 'ELIGIBLE' | 'BLOCKED';
      reasons: string[];
      certifiableCases: number;
      minimumCertifiableCases: number;
      allCasesPassed: boolean;
      targetCertificationEligible: boolean;
      coverageSatisfied: boolean;
      allCasesCompleted: boolean;
    };
    diagnostics: string[];
  };
}

/** Combined publication and execution result consumed by the Canvas suite table. */
export interface OperatorTestSuiteRun {
  target: OperatorTestTargetDescriptor;
  storedFixtures: StoredOperatorTestFixture[];
  storedSuite: StoredOperatorTestSuite;
  response: OperatorTestSuiteExecutionResponse;
}

/** Versioned Tool Studio integration envelope returned by protected Mirror endpoints. */
export interface ToolStudioIntegrationEnvelope<T> {
  protocol: string;
  protocolVersion: string;
  resourceGatewayVersion: string;
  schemaVersion: string;
  producedAt: string;
  payloadKind: string;
  payloadSchemaVersion: string;
  payloadFingerprint: string;
  payload: T;
}

/** Enterprise coordinates fixed by the authenticated rehearsal-workbench identity. */
export interface ScenarioRehearsalScope {
  tenantId: string;
  organizationId: string;
  projectId: string;
  environmentId: string;
  region: string;
}

/** Payload-free progress counters shared by live jobs and signed terminal workbooks. */
export interface ScenarioRehearsalBatchSummary {
  totalItems: number;
  completedItems: number;
  passedItems: number;
  failedItems: number;
  indeterminateItems: number;
  cancelledItems: number;
}

/** Integrity-protected mutable Scenario batch projection. */
export interface ScenarioRehearsalBatchJob {
  schemaVersion: 'resourceGateway.scenarioRehearsalBatchJob.v1'
    | 'resourceGateway.scenarioRehearsalBatchJob.v2';
  jobId: string;
  requestId: string;
  requestFingerprint: string;
  manifestFingerprint: string;
  scope: ScenarioRehearsalScope;
  status: 'QUEUED' | 'RUNNING' | 'CANCEL_REQUESTED' | 'FINALIZING_EVIDENCE'
    | 'SUCCEEDED' | 'PARTIAL' | 'FAILED' | 'CANCELLED' | 'EXPIRED' | 'QUARANTINED';
  failureMode: string;
  priority: string;
  maximumItemAttempts: number;
  summary: ScenarioRehearsalBatchSummary;
  deadlineAt: string;
  failureCode: string;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
  recordFingerprint: string;
}

/** Stable newest-first keyset page used by the rehearsal workbench. */
export interface ScenarioRehearsalBatchJobPage {
  schemaVersion: 'resourceGateway.scenarioRehearsalBatchJobPage.v1';
  scope: ScenarioRehearsalScope;
  jobs: ScenarioRehearsalBatchJob[];
  nextCursor: {
    createdAt: string;
    jobId: string;
  } | null;
}

/** Content-addressed reference embedded in Scenario workbooks. */
export interface ScenarioArtifactRef {
  kind: string;
  id: string;
  revision: number;
  fingerprint: string;
}

/** One mutable live item read before a batch reaches signed terminal evidence. */
export interface ScenarioRehearsalBatchItem {
  itemIndex: number;
  compiledPlanRef: ScenarioArtifactRef;
  childRequestId: string;
  status: 'PENDING' | 'RUNNING' | 'PASSED' | 'FAILED' | 'INDETERMINATE' | 'CANCELLED';
  attemptCount: number;
  runId: string;
  evidenceBundleFingerprint: string;
  workbookSeedFingerprint: string;
  failureCode: string;
  startedAt: string | null;
  completedAt: string | null;
}

/** Bounded mutable item page for an active Scenario batch. */
export interface ScenarioRehearsalBatchItemPage {
  schemaVersion: 'resourceGateway.scenarioRehearsalBatchItemPage.v1';
  jobId: string;
  manifestFingerprint: string;
  items: ScenarioRehearsalBatchItem[];
  nextIndex: number | null;
}

/** Signed child-workbook summary embedded in a terminal batch workbook. */
export interface ScenarioRehearsalChildWorkbook {
  schemaVersion: 'resourceGateway.scenarioRehearsalWorkbookSeed.v1';
  seedFingerprint: string;
  runId: string;
  requestId: string;
  compiledPlanRef: ScenarioArtifactRef;
  scenarioPackRef: ScenarioArtifactRef;
  targetCapabilityRef: ScenarioArtifactRef;
  evidenceBundleFingerprint: string;
  resultFingerprint: string;
  evidenceKeyId: string;
  retentionProofFingerprint: string;
  outcome: 'PASS' | 'FAIL' | 'INDETERMINATE';
  summary: ScenarioRehearsalCaseSummary;
  gateReady: boolean;
  blockers: string[];
}

/** Case and assertion counters derived from one signed Scenario run. */
export interface ScenarioRehearsalCaseSummary {
  totalCases: number;
  passedCases: number;
  failedCases: number;
  indeterminateCases: number;
  assertionResults: number;
  blockerFailures: number;
  blockerIndeterminate: number;
  warningFailures: number;
  warningIndeterminate: number;
}

/** One ordered terminal item in the signed batch workbook. */
export interface ScenarioRehearsalBatchWorkbookEntry {
  entryIndex: number;
  entryId: string;
  compiledPlanRef: ScenarioArtifactRef;
  childRequestId: string;
  expectedRunId: string;
  status: 'PASSED' | 'FAILED' | 'INDETERMINATE' | 'CANCELLED';
  attemptCount: number;
  runId: string;
  childEvidenceBundleFingerprint: string;
  childWorkbookSeedFingerprint: string;
  failureCode: string;
  childWorkbook: ScenarioRehearsalChildWorkbook | null;
}

/** Root-sealed terminal batch workbook consumed by the Owner workbench. */
export interface ScenarioRehearsalBatchWorkbookSeed {
  schemaVersion: 'resourceGateway.scenarioRehearsalBatchWorkbookSeed.v1';
  seedFingerprint: string;
  scope: ScenarioRehearsalScope;
  jobId: string;
  requestId: string;
  requestFingerprint: string;
  manifestFingerprint: string;
  terminalJobFingerprint: string;
  evidenceBundleFingerprint: string;
  evidenceIndexFingerprint: string;
  evidenceKeyId: string;
  workbookSeal: {
    keyId: string;
    algorithm: string;
    materialFingerprint: string;
    signature: string;
  };
  retentionProof: {
    eventFingerprint: string;
    retainUntil: string;
    evidenceSeal?: {
      keyId: string;
      algorithm: string;
      materialFingerprint: string;
    };
  };
  status: ScenarioRehearsalBatchJob['status'];
  summary: ScenarioRehearsalBatchSummary;
  entries: ScenarioRehearsalBatchWorkbookEntry[];
  gateReady: boolean;
  blockers: string[];
}

/** Closed construction strategy for a reviewed successor batch. */
export type ScenarioRemediationStrategy = 'RERUN_EXACT' | 'REPLACE_COMPILED_PLANS';

/** Closed reason vocabulary for reviewed successor construction. */
export type ScenarioRemediationReason =
  | 'TRANSIENT_EXECUTION_RECHECK'
  | 'EVIDENCE_RECOVERY_RECHECK'
  | 'SCENARIO_REVISION'
  | 'FIXTURE_REVISION'
  | 'ASSERTION_REVISION'
  | 'MIRROR_PLAN_REVISION';

/** One compare-and-set compiled-plan replacement in a remediation proposal. */
export interface ScenarioRemediationPlanReplacement {
  entryIndex: number;
  entryId: string;
  expectedCompiledPlanRef: ScenarioArtifactRef;
  replacementCompiledPlanRef: ScenarioArtifactRef;
}

/** Payload-free proposal sent to the reviewed Scenario remediation boundary. */
export interface ScenarioRehearsalRemediationPreviewRequest {
  schemaVersion: 'resourceGateway.scenarioRehearsalRemediationPreviewRequest.v1';
  previewRequestId: string;
  expectedWorkbookSeedFingerprint: string;
  strategy: ScenarioRemediationStrategy;
  replacements: ScenarioRemediationPlanReplacement[];
  governanceTicketRef: ScenarioArtifactRef;
  reasonCode: ScenarioRemediationReason;
}

/** Server-frozen immutable successor plan reviewed by two distinct human roles. */
export interface ScenarioRehearsalRemediationPlan {
  schemaVersion: 'resourceGateway.scenarioRehearsalRemediationPlan.v1';
  planFingerprint: string;
  scope: ScenarioRehearsalScope;
  remediationId: string;
  previewRequestId: string;
  predecessorJobId: string;
  predecessorWorkbookSeedFingerprint: string;
  predecessorEvidenceBundleFingerprint: string;
  predecessorStatus: ScenarioRehearsalBatchJob['status'];
  predecessorBlockers: string[];
  strategy: ScenarioRemediationStrategy;
  reasonCode: ScenarioRemediationReason;
  replacements: ScenarioRemediationPlanReplacement[];
  successorRequest: {
    requestId: string;
    entries: Array<{
      entryId: string;
      compiledPlanRef: ScenarioArtifactRef;
    }>;
    [key: string]: unknown;
  };
  successorRequestFingerprint: string;
  governanceTicketRef: ScenarioArtifactRef;
  approvalPolicy: {
    requiredRoles: ScenarioRemediationApprovalRole[];
    minimumDistinctActors: number;
    serverPolicyGeneration: number;
    serverPolicyFingerprint: string;
  };
  generatedAt: string;
  expiresAt: string;
}

/** Fixed separation-of-duties roles in the first remediation protocol generation. */
export type ScenarioRemediationApprovalRole = 'OWNER' | 'INDEPENDENT_REVIEWER';

/** One compare-and-set role decision command. */
export interface ScenarioRehearsalRemediationApprovalCommand {
  schemaVersion: 'resourceGateway.scenarioRehearsalRemediationApprovalCommand.v1';
  commandId: string;
  remediationPlanFingerprint: string;
  expectedApprovalGeneration: number;
  role: ScenarioRemediationApprovalRole;
  decision: 'APPROVE' | 'REJECT';
  governanceTicketRef: ScenarioArtifactRef;
  reasonCode: 'APPROVED_AS_REVIEWED'
    | 'REJECTED_REQUIRES_CHANGES'
    | 'REJECTED_POLICY_CONFLICT'
    | 'REJECTED_INSUFFICIENT_EVIDENCE';
}

/** Server-authored append-only human decision fact. */
export interface ScenarioRehearsalRemediationApproval {
  schemaVersion: 'resourceGateway.scenarioRehearsalRemediationApproval.v1';
  approvalFingerprint: string;
  sourceCommandFingerprint: string;
  scope: ScenarioRehearsalScope;
  remediationId: string;
  remediationPlanFingerprint: string;
  generation: number;
  previousApprovalFingerprint: string;
  role: ScenarioRemediationApprovalRole;
  decision: 'APPROVE' | 'REJECT';
  governanceTicketRef: ScenarioArtifactRef;
  reasonCode: ScenarioRehearsalRemediationApprovalCommand['reasonCode'];
  actorId: string;
  delegatedBy: string;
  decidedAt: string;
}

/** Compare-and-set command admitting an approved frozen successor batch. */
export interface ScenarioRehearsalRemediationSubmitCommand {
  schemaVersion: 'resourceGateway.scenarioRehearsalRemediationSubmitCommand.v1';
  commandId: string;
  remediationPlanFingerprint: string;
  expectedApprovalGeneration: number;
  expectedApprovalHeadFingerprint: string;
  reasonCode: 'APPROVALS_COMPLETE';
}

/** Immutable admission receipt joining predecessor, approvals, and successor. */
export interface ScenarioRehearsalRemediationReceipt {
  schemaVersion: 'resourceGateway.scenarioRehearsalRemediationReceipt.v1';
  receiptFingerprint: string;
  sourceCommandFingerprint: string;
  scope: ScenarioRehearsalScope;
  remediationId: string;
  remediationPlanFingerprint: string;
  predecessorJobId: string;
  successorJobId: string;
  successorRequestFingerprint: string;
  approvalGeneration: number;
  approvalHeadFingerprint: string;
  acceptedBy: string;
  delegatedBy: string;
  acceptedAt: string;
}

/** Content-addressed public read model reconstructed from immutable remediation facts. */
export interface ScenarioRehearsalRemediationLineage {
  schemaVersion: 'resourceGateway.scenarioRehearsalRemediationLineage.v1';
  lineageFingerprint: string;
  state: 'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED' | 'SUBMITTED';
  plan: ScenarioRehearsalRemediationPlan;
  approvals: ScenarioRehearsalRemediationApproval[];
  approvalGeneration: number;
  approvalHeadFingerprint: string;
  receipt: ScenarioRehearsalRemediationReceipt | null;
}

/** Bounded aggregate correctness counters in a signed workbook comparison. */
export interface ScenarioRemediationCorrectnessSummary extends ScenarioRehearsalCaseSummary {
  evidenceBackedEntries: number;
}

/** Root signed-workbook projection retained by a remediation comparison. */
export interface ScenarioRemediationWorkbookSnapshot {
  workbookSchemaVersion: 'resourceGateway.scenarioRehearsalBatchWorkbookSeed.v1';
  scope: ScenarioRehearsalScope;
  jobId: string;
  seedFingerprint: string;
  requestFingerprint: string;
  manifestFingerprint: string;
  evidenceBundleFingerprint: string;
  evidenceIndexFingerprint: string;
  workbookSeal: ScenarioRehearsalBatchWorkbookSeed['workbookSeal'];
  status: ScenarioRehearsalBatchJob['status'];
  summary: ScenarioRehearsalBatchSummary;
  correctnessSummary: ScenarioRemediationCorrectnessSummary;
  gateReady: boolean;
  blockers: string[];
}

/** One signed entry projection in a predecessor/successor comparison. */
export interface ScenarioRemediationEntrySnapshot {
  compiledPlanRef: ScenarioArtifactRef;
  status: ScenarioRehearsalBatchWorkbookEntry['status'];
  failureCode: string;
  runId: string;
  childEvidenceBundleFingerprint: string;
  childWorkbookSeedFingerprint: string;
  scenarioPackRef: ScenarioArtifactRef | null;
  targetCapabilityRef: ScenarioArtifactRef | null;
  outcome: 'PASS' | 'FAIL' | 'INDETERMINATE' | null;
  summary: ScenarioRehearsalCaseSummary | null;
  gateReady: boolean;
  blockers: string[];
}

/** Exact per-entry transition between two independently verified workbooks. */
export interface ScenarioRemediationEntryComparison {
  entryIndex: number;
  entryId: string;
  planChanged: boolean;
  gateTransition: ScenarioRemediationGateTransition;
  resolvedBlockers: string[];
  remainingBlockers: string[];
  introducedBlockers: string[];
  predecessor: ScenarioRemediationEntrySnapshot;
  successor: ScenarioRemediationEntrySnapshot;
}

/** Source-derived readiness transition; no synthetic quality score is introduced. */
export type ScenarioRemediationGateTransition =
  | 'RESOLVED'
  | 'STILL_BLOCKED'
  | 'REGRESSED'
  | 'STILL_READY';

/** Deterministic comparison of predecessor and successor root-signed workbooks. */
export interface ScenarioRehearsalRemediationComparison {
  schemaVersion: 'resourceGateway.scenarioRehearsalRemediationComparison.v1';
  comparisonFingerprint: string;
  scope: ScenarioRehearsalScope;
  remediationId: string;
  lineageFingerprint: string;
  remediationPlanFingerprint: string;
  receiptFingerprint: string;
  predecessor: ScenarioRemediationWorkbookSnapshot;
  successor: ScenarioRemediationWorkbookSnapshot;
  gateTransition: ScenarioRemediationGateTransition;
  resolvedBlockers: string[];
  remainingBlockers: string[];
  introducedBlockers: string[];
  entries: ScenarioRemediationEntryComparison[];
}

/** Signed handling-assertion result shown without raw business payloads. */
export interface ScenarioHandlingAssertionResult {
  resultFingerprint: string;
  assertionRef: ScenarioArtifactRef;
  observation: string;
  outcome: 'PASS' | 'FAIL' | 'INDETERMINATE';
  severity: 'BLOCKER' | 'WARNING';
  governanceCode: string;
  reasonCode: string;
}

/** One case projection loaded only when an Owner opens a terminal batch entry. */
export interface ScenarioRehearsalWorkbookCase {
  caseIndex: number;
  scenarioCaseRef: ScenarioArtifactRef;
  caseType: 'GOLDEN' | 'NEGATIVE' | 'BOUNDARY' | 'REGRESSION'
    | 'FAULT' | 'STATE_TRANSITION' | 'WHAT_IF';
  testCaseId: string;
  childRunId: string;
  childEvidenceBundleFingerprint: string;
  evidenceStatus: string;
  evidenceClass: '' | 'EXPLORATORY' | 'CERTIFIABLE';
  outcome: 'PASS' | 'FAIL' | 'INDETERMINATE';
  diagnosticCode: string;
  assertionResults: ScenarioHandlingAssertionResult[];
}

/** Full case-level signed workbook fetched lazily for one terminal entry. */
export interface ScenarioRehearsalWorkbookSeed extends ScenarioRehearsalChildWorkbook {
  scope: ScenarioRehearsalScope;
  retentionProof: Record<string, unknown>;
  cases: ScenarioRehearsalWorkbookCase[];
}

/** A BLOGE expression function exposed to authoring editors. */
export interface BuiltInFunctionDefinition {
  name: string;
  namespace?: string;
  displayName?: string;
  description?: string;
  category?: string;
  signatures?: BuiltInFunctionSignature[];
  examples?: string[];
}

/** One callable overload for a BLOGE expression function. */
export interface BuiltInFunctionSignature {
  label: string;
  description?: string;
  parameters?: BuiltInFunctionParameter[];
  returns?: BuiltInFunctionReturn;
}

/** One BLOGE expression function parameter. */
export interface BuiltInFunctionParameter {
  name: string;
  type?: string;
  schema?: SchemaEnvelope;
  optional?: boolean;
  variadic?: boolean;
  description?: string;
}

/** BLOGE expression function return contract. */
export interface BuiltInFunctionReturn {
  type?: string;
  schema?: SchemaEnvelope;
  description?: string;
}

/** A user-provided operator library (bloge.visualOperatorLibrary.v1). */
export interface OperatorLibrary {
  schemaVersion?: string;
  libraryId: string;
  displayName?: string;
  version?: string;
  owner?: string;
  status?: string;
  builtInFunctions?: BuiltInFunctionDefinition[];
  operators: OperatorDefinition[];
}

/** The response of GET /api/visual/operators. */
export interface OperatorCatalogResponse {
  operators: OperatorDefinition[];
  builtInFunctions?: BuiltInFunctionDefinition[];
}

/** Server-derived import readiness for one submitted operator library. */
export interface OperatorLibraryImportReadiness {
  state?: string;
  level?: string;
  operatorCount?: number;
  diagnosticCount?: number;
  message?: string;
  recommendedAction?: string;
}

/** The response of POST /admin/visual-operator-libraries/validate-text. */
export interface OperatorLibraryValidationResult {
  valid: boolean;
  diagnostics: VisualDiagnostic[];
  profile?: { libraryId?: string; operatorCount?: number };
  importReadiness?: OperatorLibraryImportReadiness;
}

/** Human-oriented source model edited by the progressive Library Workbench. */
export interface VisualLibraryAuthoringDocument {
  schemaVersion: 'bloge.visualLibraryAuthoring.v1';
  library: {
    id: string;
    name?: string;
    version?: string;
    owner?: string;
    status?: string;
  };
  defaults?: {
    operatorVersion?: string;
    namespace?: string;
  };
  types?: Record<string, unknown>;
  operators?: Record<string, VisualOperatorAuthoring>;
  functions?: Record<string, VisualFunctionAuthoring>;
  imports?: Array<{ libraryId: string; version: string; fingerprint: string }>;
  examples?: Record<string, unknown>;
}

/** Compact operator source owned by the Workbench. */
export interface VisualOperatorAuthoring {
  name?: string;
  description?: string;
  archetype?: string;
  version?: string;
  tags?: string[];
  input?: Record<string, unknown>;
  output?: Record<string, unknown>;
  config?: unknown;
  effect?: string;
  idempotency?: string;
  streaming?: boolean;
  durable?: boolean;
  requiresSecrets?: boolean;
  runtime?: unknown;
  tests?: Array<{ ref: string }>;
}

export type VisualSamplePortDirection = 'INPUT' | 'OUTPUT';

/** Exact operator port that receives one accepted sample-inference candidate. */
export interface VisualSampleInferenceTarget {
  assetKind: 'OPERATOR';
  assetRef: string;
  portDirection: VisualSamplePortDirection;
  portName: string;
}

/** Ephemeral sample batch. Raw samples are never part of a persisted authoring draft. */
export interface VisualSampleInferenceRequest {
  schemaVersion: 'bloge.visualSampleInferenceRequest.v1';
  target: VisualSampleInferenceTarget;
  samples: unknown[];
  options: {
    suggestEnums: boolean;
    suggestFormats: boolean;
    persistPayload: false;
  };
  idempotencyKey: string;
}

export interface VisualSampleFieldObservation {
  factId: string;
  authoringPath: string;
  sourceLevel: 'OBSERVED';
  suggestedType: string;
  sampleCount: number;
  presenceCount: number;
  nullCount: number;
  distinctCount: number;
  sensitive: boolean;
  requiredCandidate: boolean;
  nullableCandidate: boolean;
  formatCandidate: string;
  enumCandidates: string[];
  conflictTypes: string[];
  widenReasons: string[];
}

export interface VisualSampleInferenceConfirmation {
  confirmationId: string;
  factId: string;
  code: string;
  authoringPath: string;
  question: string;
  recommendedValue: string;
  allowedValues: string[];
  blocking: boolean;
}

/** Payload-free result for one exact persisted authoring revision. */
export interface VisualSampleInferenceResult {
  schemaVersion: 'bloge.visualSampleInferenceResult.v1';
  draftId: string;
  authoringRevision: number;
  target: VisualSampleInferenceTarget;
  evidenceFingerprint: string;
  inferencerVersion: string;
  redactionProfileVersion: string;
  sampleCount: number;
  candidate: unknown;
  observations: VisualSampleFieldObservation[];
  confirmationRequests: VisualSampleInferenceConfirmation[];
  diagnostics: VisualAuthoringDiagnostic[];
  payloadPersisted: false;
}

export interface VisualSampleInferenceDecision {
  confirmationId: string;
  value: string;
}

export interface VisualAuthoringEvidence {
  evidenceFingerprint: string;
  evidenceKind: string;
  target: VisualSampleInferenceTarget;
  producerVersion: string;
  redactionProfileVersion: string;
  sampleCount: number;
  candidate: unknown;
  declaredCandidate: unknown;
  declaredPortName: string;
  targetRemoved: boolean;
  observations: VisualSampleFieldObservation[];
}

export interface VisualAuthoringConfirmation {
  confirmationId: string;
  evidenceFingerprint: string;
  factId: string;
  code: string;
  authoringPath: string;
  decision: string;
  blocking: boolean;
  decidedBy: string;
}

/** Compact built-in function source owned by the Workbench. */
export interface VisualFunctionAuthoring {
  name?: string;
  namespace?: string;
  description?: string;
  category?: string;
  signature?: string;
  signatures?: string[];
  examples?: string[];
  tests?: Array<{ ref: string }>;
}

/** One persisted, optimistically locked Workbench source revision. */
export interface VisualLibraryAuthoringDraft {
  schemaVersion: 'bloge.visualLibraryAuthoringDraft.v1';
  draftId: string;
  revision: number;
  sourceMode: 'QUICK' | 'CANONICAL';
  document: VisualLibraryAuthoringDocument;
  evidence?: VisualAuthoringEvidence[];
  confirmations?: VisualAuthoringConfirmation[];
  fingerprint: string;
  createdAt: string;
  updatedAt: string;
  savedBy: string;
}

export interface VisualAuthoringDiagnostic {
  level: string;
  code: string;
  message: string;
  authoringPath: string;
  canonicalPath?: string;
  fixes?: Array<{ kind: string; label: string; target: string }>;
  metadata?: Record<string, unknown>;
}

export interface VisualLibraryAuthoringCompileResult {
  schemaVersion: 'bloge.visualLibraryCompileResult.v1';
  draftId: string;
  authoringRevision: number;
  authoringFingerprint: string;
  compileFingerprint: string;
  compilerVersion: string;
  grammarVersion: string;
  catalogFingerprint: string;
  previewAuthority: 'SERVER_AUTHORITATIVE' | 'LOCAL_PREVIEW';
  canonicalLibrary?: OperatorLibrary;
  canonicalFingerprint: string;
  sourceMap: Array<{
    authoringPath: string;
    canonicalPath: string;
    origin: string;
    evidenceRef?: string;
  }>;
  diagnostics: VisualAuthoringDiagnostic[];
  confirmationRequests: Array<{
    code: string;
    authoringPath: string;
    question: string;
    allowedValues: string[];
  }>;
  readiness: {
    state: string;
    importable: boolean;
    strongSchemaReady: boolean;
    designReady: boolean;
    productionReady: boolean;
    gates: Array<{
      code: string;
      level: string;
      message: string;
      authoringPath: string;
      blocking: boolean;
    }>;
  };
  diff?: {
    libraryId: string;
    baseRevision: number;
    changed: boolean;
    addedOperatorCount: number;
    removedOperatorCount: number;
    changedOperatorCount: number;
  };
  impact?: Record<string, unknown>;
}

export interface VisualLibraryAuthoringCommitResult {
  schemaVersion: 'bloge.visualLibraryAuthoringCommitResult.v1';
  draftId: string;
  authoringRevision: number;
  authoringFingerprint: string;
  canonicalFingerprint: string;
  catalogFingerprintBeforeCommit: string;
  targetRevision: number;
  library: OperatorLibrary;
  preview: VisualLibraryAuthoringCompileResult;
  committedAt: string;
  committedBy: string;
}

/** Projection review for adapting bloge.capabilityCatalog.v1 into a visual operator library. */
export interface CapabilityCatalogProjectionReview {
  schemaVersion?: string;
  catalogId?: string;
  sourceSchemaVersion?: string;
  sourceOperatorCount?: number;
  sourceFunctionCount?: number;
  projectedOperatorCount?: number;
  projectedFunctionCount?: number;
  opaqueSchemaCount?: number;
  sourceDiagnosticCount?: number;
  coverageStatus?: string;
  sourceKinds?: string[];
}

/** The response of POST /admin/visual-operator-libraries/from-capability-catalog-text. */
export interface CapabilityCatalogVisualAdapterResult {
  schemaVersion?: string;
  library?: OperatorLibrary;
  validation: OperatorLibraryValidationResult;
  projectionReview?: CapabilityCatalogProjectionReview;
}

/** A draft node input binding. */
export interface DraftNodeBinding {
  kind: string;
  value?: unknown;
  path?: string;
  nodeId?: string;
  sourcePort?: string;
  targetPort?: string;
  targetPath?: string;
  expr?: string;
  fields?: Record<string, DraftNodeBinding>;
}

/** A visual graph draft node. */
export interface DraftNode {
  id: string;
  operatorRef: string;
  label?: string;
  inputs?: Record<string, DraftNodeBinding>;
  config?: Record<string, unknown>;
  position?: { x: number; y: number };
}

/** A visual graph draft edge endpoint. */
export interface DraftEndpoint {
  nodeId: string;
  port?: string;
  path?: string;
}

/** A visual graph draft edge. */
export interface DraftEdge {
  id: string;
  kind: string;
  source: DraftEndpoint;
  target: DraftEndpoint;
  condition?: string;
}

/** A visual graph draft (bloge.visualGraphDraft.v1) — the subset the authoring app sends or exports. */
export interface GraphDraft {
  schemaVersion?: string;
  draftId?: string;
  revision?: number;
  graphName: string;
  tenantId?: string;
  namespace?: string;
  environment?: string;
  status?: string;
  inputSchema?: SchemaEnvelope;
  outputSchema?: SchemaEnvelope;
  nodes: DraftNode[];
  edges: DraftEdge[];
  visualLayout?: Record<string, unknown>;
  nodeFixtures?: Record<string, NodeFixture>;
  output: { nodeId: string; path?: string };
  operatorFingerprints?: Record<string, string>;
  operatorSnapshots?: Record<string, OperatorDefinition>;
}

/** One ANEKE governance finding bound to a draft snapshot. */
export interface GovernanceGateIssue {
  issueId: string;
  severity: string;
  code: string;
  message: string;
  targetPath?: string;
  recommendedAction?: string;
  deepLink?: string;
}

/** Immutable governance decision submitted by ANEKE Tool Studio. */
export interface GovernanceGateResult {
  schemaVersion?: string;
  gateResultId: string;
  target: {
    kind?: string;
    draftId: string;
    revision: number;
    draftFingerprint: string;
  };
  status: string;
  issues: GovernanceGateIssue[];
  producedAt?: string;
  expiresAt?: string;
  resultFingerprint?: string;
}

/** Authoring read model that compares a gate decision with the current draft revision. */
export interface GovernanceGateView {
  schemaVersion?: string;
  draftId: string;
  currentRevision: number;
  currentDraftFingerprint: string;
  freshness: 'CURRENT' | 'STALE' | 'UNVERIFIABLE' | 'EXPIRED' | 'MISSING' | string;
  result?: GovernanceGateResult | null;
}

/** Minimal run-history shape used to resolve and display run deep links. */
export interface VisualGraphRunRecord {
  schemaVersion?: string;
  runId: string;
  sourceKind?: string;
  draftId?: string;
  draftRevision?: number;
  draftFingerprint?: string;
  publicationId?: string;
  graphName?: string;
  outputNode?: string;
  createdAt?: string;
  success?: boolean;
  elapsedMs?: number;
  statusMap?: Record<string, string>;
  errors?: string[];
}

/** A per-node author-supplied simulation fixture. */
export interface NodeFixture {
  output: unknown;
  expectedInput?: unknown;
}

/** The body of POST /api/visual/graphs/simulate. */
export interface SimulationRequest {
  draft: GraphDraft;
  context: Record<string, unknown>;
  outputNode: string;
  fixtures?: Record<string, NodeFixture>;
}

/** The body of POST /api/visual/connections/check. */
export interface ConnectionCheckRequest {
  draft: GraphDraft;
  source: DraftEndpoint;
  target: DraftEndpoint;
  kind: string;
  condition?: string;
}

/** The body of POST /api/visual/connections/candidates. */
export interface ConnectionCandidatesRequest {
  draft: GraphDraft;
  source: DraftEndpoint;
  kind?: string;
  includeRejected?: boolean;
  limit?: number;
  offset?: number;
  targetNodeId?: string;
  targetSurface?: string;
  targetPort?: string;
  targetPath?: string;
  query?: string;
  targetStatus?: string;
  facetFilters?: Record<string, string[]>;
}

/** A visual diagnostic. */
export interface VisualDiagnostic {
  level?: string;
  code?: string;
  message?: string;
  target?: string;
  line?: number;
  column?: number;
  metadata?: Record<string, unknown>;
}

/** Source span in an imported BLOGE DSL document. */
export interface DslSourceSpan {
  sourceId?: string;
  startLine?: number;
  startColumn?: number;
  endLine?: number;
  endColumn?: number;
  dslKind?: string;
}

/** Source-map links from visual draft elements back to DSL source locations. */
export interface DslSourceMap {
  nodes?: Record<string, DslSourceSpan>;
  edges?: Record<string, DslSourceSpan>;
  bindings?: Record<string, DslSourceSpan>;
}

/** Import projection coverage summary. */
export interface DslImportCoverage {
  memberCount?: number;
  projectedNodeCount?: number;
  edgeCount?: number;
  unsupportedSyntaxCount?: number;
  missingOperatorCount?: number;
  missingFunctionCount?: number;
}

/** Conservative round-trip readiness summary for an imported DSL preview. */
export interface DslRoundTripSummary {
  supported?: boolean;
  status?: string;
  message?: string;
  generatedDsl?: string;
  sourceFingerprint?: string;
  generatedFingerprint?: string;
  diagnostics?: VisualDiagnostic[];
}

/** Request body for schema-neutral DSL preview import. */
export interface DslImportPreviewRequest {
  sourceId?: string;
  dsl: string;
  operatorLibraryIds?: string[];
  inlineLibraries?: OperatorLibrary[];
  mode?: string;
  layout?: Record<string, unknown>;
}

/** Response of POST /api/visual/dsl-imports/preview. */
export interface DslVisualProjection {
  schemaVersion?: string;
  sourceId: string;
  draft: GraphDraft;
  sourceMap?: DslSourceMap;
  coverage?: DslImportCoverage;
  roundTrip?: DslRoundTripSummary;
  diagnostics?: VisualDiagnostic[];
}

/** One source file in a repository-level DSL import batch. */
export interface DslImportBatchSource {
  sourceId?: string;
  dsl: string;
  layout?: Record<string, unknown>;
}

/** Request body for repository-level schema-neutral DSL import assessment. */
export interface DslImportBatchReportRequest {
  sources: DslImportBatchSource[];
  operatorLibraryIds?: string[];
  inlineLibraries?: OperatorLibrary[];
  mode?: string;
  includeDrafts?: boolean;
}

/** Aggregate render/repair/rewrite readiness for a DSL import batch. */
export interface DslImportBatchSummary {
  sourceCount?: number;
  renderableSourceCount?: number;
  fullyProjectedSourceCount?: number;
  repairableSourceCount?: number;
  blockedSourceCount?: number;
  rewriteAllowedSourceCount?: number;
  rewriteBlockedSourceCount?: number;
  totalMemberCount?: number;
  totalProjectedNodeCount?: number;
  totalEdgeCount?: number;
  totalUnsupportedSyntaxCount?: number;
  totalMissingOperatorCount?: number;
  totalMissingFunctionCount?: number;
  totalSourceMapEntryCount?: number;
  roundTripStatusCounts?: Record<string, number>;
  rewriteDecisionCounts?: Record<string, number>;
  diagnosticLevelCounts?: Record<string, number>;
}

/** Per-source readiness item returned by batch DSL import assessment. */
export interface DslImportBatchReportItem {
  sourceId?: string;
  graphName?: string;
  renderable?: boolean;
  fullyProjected?: boolean;
  needsRepair?: boolean;
  sourceMapEntryCount?: number;
  coverage?: DslImportCoverage;
  roundTrip?: DslRoundTripSummary;
  rewriteAllowed?: boolean;
  rewriteDecision?: string;
  diagnosticLevelCounts?: Record<string, number>;
  diagnostics?: VisualDiagnostic[];
  draft?: GraphDraft;
}

/** Response of POST /api/visual/dsl-imports/batch-report. */
export interface DslImportBatchReport {
  schemaVersion?: string;
  mode?: string;
  summary?: DslImportBatchSummary;
  items?: DslImportBatchReportItem[];
}

/** Request body for repository-level governed draft creation from DSL imports. */
export interface DslImportBatchCommitRequest extends DslImportBatchReportRequest {
  commitPolicy?: string;
}

/** Aggregate commit outcome for a DSL import batch. */
export interface DslImportBatchCommitSummary {
  sourceCount?: number;
  committedSourceCount?: number;
  skippedSourceCount?: number;
  failedSourceCount?: number;
  reportSummary?: DslImportBatchSummary;
  commitDecisionCounts?: Record<string, number>;
}

/** Per-source commit outcome returned by batch DSL import commit. */
export interface DslImportBatchCommitItem {
  sourceId?: string;
  graphName?: string;
  committed?: boolean;
  commitDecision?: string;
  message?: string;
  reportItem?: DslImportBatchReportItem;
  importResult?: GraphDraftImportResult;
}

/** Response of POST /api/visual/dsl-imports/batch-commit. */
export interface DslImportBatchCommitResult {
  schemaVersion?: string;
  mode?: string;
  commitPolicy?: string;
  summary?: DslImportBatchCommitSummary;
  items?: DslImportBatchCommitItem[];
}

/** Server-authoritative gate for generated DSL source replacement. */
export interface DslRewriteGateResult {
  schemaVersion?: string;
  sourceId?: string;
  allowed?: boolean;
  decision?: string;
  message?: string;
  generatedDsl?: string;
  roundTrip?: DslRoundTripSummary;
  diagnostics?: VisualDiagnostic[];
}

/** Result of committing a DSL import projection into the stored draft repository. */
export interface GraphDraftImportResult {
  schemaVersion?: string;
  imported: boolean;
  draft?: GraphDraft;
  diagnostics?: VisualDiagnostic[];
  validation?: VisualValidationResult;
  dependencyReport?: Record<string, unknown>;
  targetDependencyReport?: Record<string, unknown>;
}

/** Server-derived graph-level readiness returned by transient draft validation. */
export interface VisualGraphReadiness {
  state?: string;
  level?: string;
  executable?: boolean;
  artifactKinds?: string[];
  title?: string;
  summary?: string;
  nodeCount?: number;
  runtimeBindingRequirementCount?: number;
}

/** Server-derived action gates returned by transient draft validation. */
export interface VisualGraphActionReadiness {
  state?: string;
  compileNow?: boolean;
  runNow?: boolean;
  publishExecutableNow?: boolean;
  publishDesignNow?: boolean;
}

/** The response of POST /api/visual/drafts/validate. */
export interface VisualValidationResult {
  valid: boolean;
  diagnostics: VisualDiagnostic[];
  readiness?: VisualGraphReadiness;
  actionReadiness?: VisualGraphActionReadiness;
}

/** Stable server-authored summary of a proposed connection decision. */
export interface ConnectionCheckSummary {
  accepted?: boolean;
  message?: string;
  graphStillInvalid?: boolean;
  readinessState?: string;
  readinessLevel?: string;
}

/** The response of POST /api/visual/connections/check. */
export interface ConnectionCheckResponse {
  accepted: boolean;
  edge?: DraftEdge;
  bindingKey?: string;
  diagnostics: VisualDiagnostic[];
  validation?: {
    valid?: boolean;
    diagnostics?: VisualDiagnostic[];
  };
  summary?: ConnectionCheckSummary;
}

/** One server-enumerated target candidate for a connection source. */
export interface ConnectionCandidate {
  targetNodeId: string;
  targetNodeLabel?: string;
  targetOperatorRef?: string;
  targetSurface?: string;
  target: DraftEndpoint;
  accepted: boolean;
  targetStatus?: string;
  bindingKey?: string;
  summary?: ConnectionCheckSummary;
  diagnostics?: VisualDiagnostic[];
}

/** The response of POST /api/visual/connections/candidates. */
export interface ConnectionCandidatesResponse {
  schemaVersion?: string;
  source: DraftEndpoint;
  kind?: string;
  offset?: number;
  totalCandidateCount?: number;
  acceptedCount?: number;
  rejectedCount?: number;
  displayedCount?: number;
  truncated?: boolean;
  candidates: ConnectionCandidate[];
  diagnostics?: VisualDiagnostic[];
}

/** The response of POST /api/visual/graphs/simulate. */
export interface SimulationResponse {
  validated: boolean;
  compiled: boolean;
  success: boolean;
  graphName: string;
  outputNode: string;
  output: unknown;
  results: Record<string, unknown>;
  statusMap: Record<string, string>;
  mockedNodeIds: string[];
  realNodeIds: string[];
  terminalOutputConforms: boolean;
  diagnostics: VisualDiagnostic[];
  errors: string[];
  generatedDsl: string;
}

/** A browser-selectable sample input for one resource-gateway showcase scenario. */
export interface GatewayExamplePreset {
  label?: string;
  description?: string;
  values?: Record<string, unknown>;
  expected?: Record<string, unknown>;
}

/** The public gateway endpoint recipe used by one resource-gateway showcase scenario. */
export interface GatewayExampleRun {
  mode?: string;
  method?: string;
  pathTemplate?: string;
  bodyTemplate?: Record<string, unknown>;
  headers?: Record<string, string>;
}

/** Resolved browser request for executing one resource-gateway showcase scenario. */
export interface GatewayExampleRunRequest {
  mode: string;
  url: string;
  init: RequestInit;
}

/** Result of executing one non-streaming resource-gateway showcase scenario. */
export interface GatewayExampleRunResult {
  status: number;
  url: string;
  payload: unknown;
}

/** One input or output column in a matrix-oriented decision table. */
export interface GatewayDecisionColumn {
  key: string;
  label?: string;
}

/** One rule row in a matrix-oriented decision table. */
export interface GatewayDecisionRow {
  id: string;
  conditions?: Record<string, unknown>;
  output?: Record<string, unknown>;
  explanation?: string;
}

/** Optional decision-table metadata exposed for matrix-oriented showcase scenarios. */
export interface GatewayDecisionTable {
  title?: string;
  hitPolicy?: string;
  inputs?: GatewayDecisionColumn[];
  outputs?: GatewayDecisionColumn[];
  rows?: GatewayDecisionRow[];
}

/** Public scenario metadata returned by GET /api/gateway/examples/scenarios. */
export interface GatewayExampleScenario {
  graphName: string;
  title: string;
  graphFile?: string;
  pattern?: string;
  description?: string;
  concepts?: string[];
  sampleInput?: Record<string, unknown>;
  samplePresets?: GatewayExamplePreset[];
  run?: GatewayExampleRun;
  decisionTable?: GatewayDecisionTable | null;
  diagramPath?: string;
  inputSchema?: SchemaEnvelope;
  outputSchema?: SchemaEnvelope;
}

/** Canvas coordinate used by resource-gateway showcase diagrams. */
export interface GatewayDiagramPosition {
  x?: number;
  y?: number;
}

/** Stable node dimensions used by resource-gateway showcase diagrams. */
export interface GatewayDiagramSize {
  width?: number;
  height?: number;
}

/** One visual node returned by the resource-gateway diagram endpoint. */
export interface GatewayDiagramNode {
  id: string;
  kind?: string;
  operatorRef?: string | null;
  label?: string;
  position?: GatewayDiagramPosition;
  size?: GatewayDiagramSize;
  group?: string | null;
  annotations?: Record<string, unknown>;
}

/** One visual edge returned by the resource-gateway diagram endpoint. */
export interface GatewayDiagramEdge {
  id?: string;
  source: string;
  target: string;
  label?: string;
}

/** Optional grouping hint returned by the resource-gateway diagram endpoint. */
export interface GatewayDiagramGroup {
  id: string;
  label?: string;
  kind?: string;
}

/** Presentation-only layout returned by GET /api/gateway/examples/scenarios/{graph}/diagram. */
export interface GatewayExampleDiagram {
  schemaVersion?: string;
  rootId: string;
  executionMode?: string;
  nodes: GatewayDiagramNode[];
  edges: GatewayDiagramEdge[];
  groups?: GatewayDiagramGroup[];
  viewport?: {
    x?: number;
    y?: number;
    zoom?: number;
  };
}
