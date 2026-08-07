import type {
  ScenarioArtifactRef,
  ScenarioRehearsalBatchItem,
  ScenarioRehearsalBatchItemPage,
  ScenarioRehearsalBatchJob,
  ScenarioRehearsalBatchSummary,
  ScenarioRehearsalBatchWorkbookEntry,
  ScenarioRehearsalBatchWorkbookSeed,
  ScenarioRehearsalChildWorkbook,
  ScenarioRehearsalScope,
  ScenarioRehearsalWorkbookCase,
  ScenarioRehearsalWorkbookSeed,
} from './types';
import type {
  LiveRehearsalJobStatus,
  TerminalRehearsalJobStatus,
} from './rehearsalStatus';
import type { MessageDescriptor, MessageId } from './i18n/messageCatalog';

interface RehearsalDemoScenarioBase {
  title: MessageDescriptor;
  situation: MessageDescriptor;
  focus: MessageDescriptor;
  childWorkbooks: Record<string, ScenarioRehearsalWorkbookSeed>;
}

type RehearsalJobStatus = ScenarioRehearsalBatchJob['status'];

interface TerminalRehearsalDemoScenario extends RehearsalDemoScenarioBase {
  kind: 'TERMINAL';
  job: ScenarioRehearsalBatchJob & { status: TerminalRehearsalJobStatus };
  workbook: ScenarioRehearsalBatchWorkbookSeed;
}

interface LiveRehearsalDemoScenario extends RehearsalDemoScenarioBase {
  kind: 'LIVE';
  job: ScenarioRehearsalBatchJob & { status: LiveRehearsalJobStatus };
  itemPage: ScenarioRehearsalBatchItemPage;
}

export type RehearsalDemoScenario =
  | TerminalRehearsalDemoScenario
  | LiveRehearsalDemoScenario;

const SCOPE: ScenarioRehearsalScope = {
  tenantId: 'demo-tenant',
  organizationId: 'enterprise-knowledge',
  projectId: 'customer-service-copilot',
  environmentId: 'test',
  region: 'sg',
};

const CREATED_AT = '2026-07-27T08:00:00Z';
const COMPLETED_AT = '2026-07-27T08:04:12Z';
const RETAIN_UNTIL = '2033-07-27T08:00:00Z';

interface ChildSpec {
  id: string;
  planId: string;
  capabilityId: string;
  caseType: ScenarioRehearsalWorkbookCase['caseType'];
  outcome: ScenarioRehearsalChildWorkbook['outcome'];
  gateReady: boolean;
  assertionOutcome?: 'PASS' | 'FAIL' | 'INDETERMINATE';
  assertionSeverity?: 'BLOCKER' | 'WARNING';
  governanceCode?: string;
  diagnosticCode?: string;
  blockers?: string[];
  evidenceClass?: ScenarioRehearsalWorkbookCase['evidenceClass'];
}

interface TerminalEntrySpec {
  id: string;
  planId: string;
  status: ScenarioRehearsalBatchWorkbookEntry['status'];
  failureCode?: string;
  attemptCount?: number;
  child?: ScenarioRehearsalWorkbookSeed;
  runAvailable?: boolean;
}

const blockedChildren = [
  childWorkbook({
    id: 'answer-grounding',
    planId: 'grounded-answer-plan',
    capabilityId: 'knowledge.answer',
    caseType: 'GOLDEN',
    outcome: 'FAIL',
    gateReady: false,
    assertionOutcome: 'FAIL',
    assertionSeverity: 'BLOCKER',
    governanceCode: 'GROUNDING_BELOW_THRESHOLD',
    diagnosticCode: 'ANSWER_NOT_GROUNDED',
    blockers: ['BLOCKER_ASSERTION_FAILED'],
  }),
  childWorkbook({
    id: 'owner-approval',
    planId: 'sensitive-topic-plan',
    capabilityId: 'knowledge.sensitive-answer',
    caseType: 'NEGATIVE',
    outcome: 'PASS',
    gateReady: false,
    assertionOutcome: 'PASS',
    assertionSeverity: 'BLOCKER',
    governanceCode: 'SENSITIVE_TOPIC_HANDLED',
    blockers: ['OWNER_APPROVAL_REQUIRED'],
  }),
  childWorkbook({
    id: 'freshness-warning',
    planId: 'policy-freshness-plan',
    capabilityId: 'knowledge.policy-search',
    caseType: 'REGRESSION',
    outcome: 'PASS',
    gateReady: true,
    assertionOutcome: 'FAIL',
    assertionSeverity: 'WARNING',
    governanceCode: 'SOURCE_FRESHNESS_WARNING',
    diagnosticCode: 'SOURCE_AGE_ABOVE_TARGET',
  }),
  childWorkbook({
    id: 'refund-happy-path',
    planId: 'refund-guidance-plan',
    capabilityId: 'knowledge.refund-guidance',
    caseType: 'GOLDEN',
    outcome: 'PASS',
    gateReady: true,
    assertionOutcome: 'PASS',
    assertionSeverity: 'BLOCKER',
    governanceCode: 'REFUND_POLICY_GROUNDED',
  }),
];

const blockedEntries = [
  terminalEntry(0, {
    id: 'retrieval-provider-timeout',
    planId: 'retrieval-failover-plan',
    status: 'FAILED',
    failureCode: 'DEPENDENCY_TIMEOUT',
    attemptCount: 2,
  }),
  terminalEntry(1, {
    id: 'answer-grounding',
    planId: 'grounded-answer-plan',
    status: 'PASSED',
    child: blockedChildren[0],
  }),
  terminalEntry(2, {
    id: 'evidence-chain-incomplete',
    planId: 'citation-evidence-plan',
    status: 'INDETERMINATE',
    runAvailable: false,
  }),
  terminalEntry(3, {
    id: 'owner-approval',
    planId: 'sensitive-topic-plan',
    status: 'PASSED',
    child: blockedChildren[1],
  }),
  terminalEntry(4, {
    id: 'freshness-warning',
    planId: 'policy-freshness-plan',
    status: 'PASSED',
    child: blockedChildren[2],
  }),
  terminalEntry(5, {
    id: 'refund-happy-path',
    planId: 'refund-guidance-plan',
    status: 'PASSED',
    child: blockedChildren[3],
  }),
];

const blockedSummary = summary(6, 6, 4, 1, 1, 0);
const blockedJob = job(
  'sample-governance-blocked',
  'PARTIAL',
  blockedSummary,
  'GOVERNANCE_GATE_BLOCKED',
);
const blockedWorkbook = batchWorkbook(
  blockedJob,
  blockedEntries,
  false,
  [
    'DEPENDENCY_TIMEOUT',
    'BLOCKER_ASSERTION_FAILED',
    'OWNER_APPROVAL_REQUIRED',
    'EVIDENCE_INCOMPLETE',
  ],
);

const readyChildren = [
  childWorkbook({
    id: 'account-lookup',
    planId: 'account-lookup-plan',
    capabilityId: 'customer.account',
    caseType: 'GOLDEN',
    outcome: 'PASS',
    gateReady: true,
    assertionOutcome: 'PASS',
    assertionSeverity: 'BLOCKER',
    governanceCode: 'ACCOUNT_LOOKUP_CORRECT',
  }),
  childWorkbook({
    id: 'refund-eligibility',
    planId: 'refund-eligibility-plan',
    capabilityId: 'customer.refund-eligibility',
    caseType: 'BOUNDARY',
    outcome: 'PASS',
    gateReady: true,
    assertionOutcome: 'PASS',
    assertionSeverity: 'BLOCKER',
    governanceCode: 'REFUND_BOUNDARY_CORRECT',
  }),
  childWorkbook({
    id: 'pii-redaction',
    planId: 'pii-redaction-plan',
    capabilityId: 'knowledge.safe-response',
    caseType: 'NEGATIVE',
    outcome: 'PASS',
    gateReady: true,
    assertionOutcome: 'PASS',
    assertionSeverity: 'BLOCKER',
    governanceCode: 'PII_REDACTED',
  }),
  childWorkbook({
    id: 'freshness-observation',
    planId: 'knowledge-freshness-plan',
    capabilityId: 'knowledge.search',
    caseType: 'REGRESSION',
    outcome: 'PASS',
    gateReady: true,
    assertionOutcome: 'FAIL',
    assertionSeverity: 'WARNING',
    governanceCode: 'SOURCE_FRESHNESS_WARNING',
    diagnosticCode: 'SOURCE_AGE_ABOVE_TARGET',
  }),
];

const readyEntries = readyChildren.map((child, index) => terminalEntry(index, {
  id: child.requestId.replace('sample-request-', ''),
  planId: child.compiledPlanRef.id,
  status: 'PASSED',
  child,
}));
const readySummary = summary(4, 4, 4, 0, 0, 0);
const readyJob = job('sample-release-ready', 'SUCCEEDED', readySummary);
const readyWorkbook = batchWorkbook(readyJob, readyEntries, true, []);

const liveSummary = summary(6, 3, 2, 1, 0, 0);
const liveJob = job('sample-live-dependency-degradation', 'RUNNING', liveSummary);
const liveItems: ScenarioRehearsalBatchItem[] = [
  liveItem(0, 'intent-routing-plan', 'PASSED', {
    runId: 'sample-live-run-routing',
    evidence: true,
    workbook: true,
    completed: true,
  }),
  liveItem(1, 'knowledge-retrieval-plan', 'RUNNING', {
    runId: 'sample-live-run-retrieval',
  }),
  liveItem(2, 'crm-enrichment-plan', 'FAILED', {
    runId: 'sample-live-run-crm',
    evidence: true,
    failureCode: 'CRM_RATE_LIMITED',
    completed: true,
    attempts: 2,
  }),
  liveItem(3, 'response-ranking-plan', 'PENDING'),
  liveItem(4, 'policy-check-plan', 'PENDING'),
  liveItem(5, 'response-assembly-plan', 'PASSED', {
    runId: 'sample-live-run-assembly',
    evidence: true,
    workbook: true,
    completed: true,
  }),
];

const quarantineChild = childWorkbook({
  id: 'signed-child-valid',
  planId: 'signed-child-plan',
  capabilityId: 'knowledge.answer',
  caseType: 'REGRESSION',
  outcome: 'PASS',
  gateReady: true,
  assertionOutcome: 'PASS',
  assertionSeverity: 'BLOCKER',
  governanceCode: 'CHILD_EVIDENCE_VALID',
});
const quarantineEntries = [
  terminalEntry(0, {
    id: 'signed-child-valid',
    planId: 'signed-child-plan',
    status: 'PASSED',
    child: quarantineChild,
  }),
  terminalEntry(1, {
    id: 'kms-finalization-failed',
    planId: 'evidence-finalization-plan',
    status: 'FAILED',
    failureCode: 'EVIDENCE_SIGNER_UNAVAILABLE',
    attemptCount: 3,
  }),
  terminalEntry(2, {
    id: 'retention-proof-missing',
    planId: 'retention-proof-plan',
    status: 'INDETERMINATE',
    runAvailable: false,
  }),
];
const quarantineSummary = summary(3, 3, 1, 1, 1, 0);
const quarantineJob = job(
  'sample-evidence-quarantined',
  'QUARANTINED',
  quarantineSummary,
  'EVIDENCE_FINALIZATION_QUARANTINED',
);
const quarantineWorkbook = batchWorkbook(
  quarantineJob,
  quarantineEntries,
  false,
  ['EVIDENCE_SIGNER_UNAVAILABLE', 'RETENTION_PROOF_INCOMPLETE'],
);

export const DEFAULT_REHEARSAL_DEMO_ID = blockedJob.jobId;

export const REHEARSAL_DEMO_SCENARIOS: RehearsalDemoScenario[] = [
  {
    kind: 'TERMINAL',
    title: demoMessage('rehearsal.demo.governanceBlocked.title'),
    situation: demoMessage('rehearsal.demo.governanceBlocked.situation'),
    focus: demoMessage('rehearsal.demo.governanceBlocked.focus'),
    job: blockedJob,
    workbook: blockedWorkbook,
    childWorkbooks: childIndex(blockedChildren),
  },
  {
    kind: 'TERMINAL',
    title: demoMessage('rehearsal.demo.releaseReady.title'),
    situation: demoMessage('rehearsal.demo.releaseReady.situation'),
    focus: demoMessage('rehearsal.demo.releaseReady.focus'),
    job: readyJob,
    workbook: readyWorkbook,
    childWorkbooks: childIndex(readyChildren),
  },
  {
    kind: 'LIVE',
    title: demoMessage('rehearsal.demo.liveDegradation.title'),
    situation: demoMessage('rehearsal.demo.liveDegradation.situation'),
    focus: demoMessage('rehearsal.demo.liveDegradation.focus'),
    job: liveJob,
    itemPage: {
      schemaVersion: 'resourceGateway.scenarioRehearsalBatchItemPage.v1',
      jobId: liveJob.jobId,
      manifestFingerprint: liveJob.manifestFingerprint,
      items: liveItems,
      nextIndex: null,
    },
    childWorkbooks: {},
  },
  {
    kind: 'TERMINAL',
    title: demoMessage('rehearsal.demo.evidenceQuarantine.title'),
    situation: demoMessage('rehearsal.demo.evidenceQuarantine.situation'),
    focus: demoMessage('rehearsal.demo.evidenceQuarantine.focus'),
    job: quarantineJob,
    workbook: quarantineWorkbook,
    childWorkbooks: childIndex([quarantineChild]),
  },
];

export function findRehearsalDemoScenario(
  id: string,
): RehearsalDemoScenario | undefined {
  return REHEARSAL_DEMO_SCENARIOS.find((scenario) => scenario.job.jobId === id);
}

function demoMessage(messageId: MessageId): MessageDescriptor {
  return { messageId };
}

function job<Status extends RehearsalJobStatus>(
  jobId: string,
  status: Status,
  batchSummary: ScenarioRehearsalBatchSummary,
  failureCode = '',
): ScenarioRehearsalBatchJob & { status: Status } {
  return {
    schemaVersion: 'resourceGateway.scenarioRehearsalBatchJob.v2',
    jobId,
    requestId: `sample-request-${jobId}`,
    requestFingerprint: fingerprint(`request-${jobId}`),
    manifestFingerprint: fingerprint(`manifest-${jobId}`),
    scope: SCOPE,
    status,
    failureMode: 'CONTINUE',
    priority: status === 'RUNNING' ? 'HIGH' : 'NORMAL',
    maximumItemAttempts: 3,
    summary: batchSummary,
    deadlineAt: '2026-07-27T09:00:00Z',
    failureCode,
    createdAt: CREATED_AT,
    updatedAt: status === 'RUNNING' ? '2026-07-27T08:02:20Z' : COMPLETED_AT,
    completedAt: status === 'RUNNING' ? null : COMPLETED_AT,
    recordFingerprint: fingerprint(`record-${jobId}`),
  };
}

function summary(
  totalItems: number,
  completedItems: number,
  passedItems: number,
  failedItems: number,
  indeterminateItems: number,
  cancelledItems: number,
): ScenarioRehearsalBatchSummary {
  return {
    totalItems,
    completedItems,
    passedItems,
    failedItems,
    indeterminateItems,
    cancelledItems,
  };
}

function childWorkbook(spec: ChildSpec): ScenarioRehearsalWorkbookSeed {
  const runId = `sample-run-${spec.id}`;
  const assertionOutcome = spec.assertionOutcome ?? spec.outcome;
  const severity = spec.assertionSeverity ?? 'BLOCKER';
  const failed = spec.outcome === 'FAIL' ? 1 : 0;
  const indeterminate = spec.outcome === 'INDETERMINATE' ? 1 : 0;
  const passed = spec.outcome === 'PASS' ? 1 : 0;
  const assertionFailed = assertionOutcome === 'FAIL';
  const assertionIndeterminate = assertionOutcome === 'INDETERMINATE';
  const resultFingerprint = fingerprint(`assertion-result-${spec.id}`);
  const cases: ScenarioRehearsalWorkbookCase[] = [{
    caseIndex: 0,
    scenarioCaseRef: artifact('SCENARIO_CASE', `${spec.id}-case`),
    caseType: spec.caseType,
    testCaseId: spec.id,
    childRunId: `${runId}-case-0`,
    childEvidenceBundleFingerprint: fingerprint(`case-evidence-${spec.id}`),
    evidenceStatus: 'PASSED',
    evidenceClass: spec.evidenceClass ?? 'CERTIFIABLE',
    outcome: spec.outcome,
    diagnosticCode: spec.diagnosticCode ?? '',
    assertionResults: [{
      resultFingerprint,
      assertionRef: artifact('HANDLING_ASSERTION', `${spec.id}-assertion`),
      observation: spec.governanceCode ?? 'EXPECTED_BEHAVIOR_OBSERVED',
      outcome: assertionOutcome,
      severity,
      governanceCode: spec.governanceCode ?? 'EXPECTED_BEHAVIOR_OBSERVED',
      reasonCode: spec.diagnosticCode ?? '',
    }],
  }];
  return {
    schemaVersion: 'resourceGateway.scenarioRehearsalWorkbookSeed.v1',
    seedFingerprint: fingerprint(`child-workbook-${spec.id}`),
    runId,
    requestId: `sample-request-${spec.id}`,
    compiledPlanRef: artifact('COMPILED_REHEARSAL_PLAN', spec.planId),
    scenarioPackRef: artifact('SCENARIO_PACK', `${spec.id}-pack`),
    targetCapabilityRef: artifact('CAPABILITY', spec.capabilityId),
    evidenceBundleFingerprint: fingerprint(`child-evidence-${spec.id}`),
    resultFingerprint: fingerprint(`child-result-${spec.id}`),
    evidenceKeyId: 'sample-evidence-key',
    retentionProofFingerprint: fingerprint(`child-retention-${spec.id}`),
    outcome: spec.outcome,
    summary: {
      totalCases: 1,
      passedCases: passed,
      failedCases: failed,
      indeterminateCases: indeterminate,
      assertionResults: 1,
      blockerFailures: severity === 'BLOCKER' && assertionFailed ? 1 : 0,
      blockerIndeterminate: severity === 'BLOCKER' && assertionIndeterminate ? 1 : 0,
      warningFailures: severity === 'WARNING' && assertionFailed ? 1 : 0,
      warningIndeterminate: severity === 'WARNING' && assertionIndeterminate ? 1 : 0,
    },
    gateReady: spec.gateReady,
    blockers: spec.blockers ?? [],
    scope: SCOPE,
    retentionProof: {
      eventFingerprint: fingerprint(`child-retention-event-${spec.id}`),
      retainUntil: RETAIN_UNTIL,
    },
    cases,
  };
}

function terminalEntry(
  index: number,
  spec: TerminalEntrySpec,
): ScenarioRehearsalBatchWorkbookEntry {
  const runId = spec.runAvailable === false ? '' : spec.child?.runId ?? `sample-run-${spec.id}`;
  return {
    entryIndex: index,
    entryId: spec.id,
    compiledPlanRef: artifact('COMPILED_REHEARSAL_PLAN', spec.planId),
    childRequestId: `sample-child-request-${spec.id}`,
    expectedRunId: `sample-run-${spec.id}`,
    status: spec.status,
    attemptCount: spec.attemptCount ?? 1,
    runId,
    childEvidenceBundleFingerprint: spec.child?.evidenceBundleFingerprint
      ?? (runId ? fingerprint(`entry-evidence-${spec.id}`) : ''),
    childWorkbookSeedFingerprint: spec.child?.seedFingerprint ?? '',
    failureCode: spec.failureCode ?? '',
    childWorkbook: spec.child ? embeddedChild(spec.child) : null,
  };
}

function batchWorkbook(
  sourceJob: ScenarioRehearsalBatchJob,
  entries: ScenarioRehearsalBatchWorkbookEntry[],
  gateReady: boolean,
  blockers: string[],
): ScenarioRehearsalBatchWorkbookSeed {
  return {
    schemaVersion: 'resourceGateway.scenarioRehearsalBatchWorkbookSeed.v1',
    seedFingerprint: fingerprint(`batch-workbook-${sourceJob.jobId}`),
    scope: SCOPE,
    jobId: sourceJob.jobId,
    requestId: sourceJob.requestId,
    requestFingerprint: sourceJob.requestFingerprint,
    manifestFingerprint: sourceJob.manifestFingerprint,
    terminalJobFingerprint: sourceJob.recordFingerprint,
    evidenceBundleFingerprint: fingerprint(`batch-evidence-${sourceJob.jobId}`),
    evidenceIndexFingerprint: fingerprint(`batch-index-${sourceJob.jobId}`),
    evidenceKeyId: 'sample-evidence-key',
    workbookSeal: {
      keyId: 'sample-workbook-key',
      algorithm: 'Ed25519',
      materialFingerprint: fingerprint(`batch-material-${sourceJob.jobId}`),
      signature: 'sample-only-not-a-server-signature',
    },
    retentionProof: {
      eventFingerprint: fingerprint(`batch-retention-${sourceJob.jobId}`),
      retainUntil: RETAIN_UNTIL,
    },
    status: sourceJob.status,
    summary: sourceJob.summary,
    entries,
    gateReady,
    blockers,
  };
}

function liveItem(
  itemIndex: number,
  planId: string,
  status: ScenarioRehearsalBatchItem['status'],
  options: {
    runId?: string;
    evidence?: boolean;
    workbook?: boolean;
    failureCode?: string;
    completed?: boolean;
    attempts?: number;
  } = {},
): ScenarioRehearsalBatchItem {
  return {
    itemIndex,
    compiledPlanRef: artifact('COMPILED_REHEARSAL_PLAN', planId),
    childRequestId: `sample-live-request-${itemIndex}`,
    status,
    attemptCount: options.attempts ?? (status === 'PENDING' ? 0 : 1),
    runId: options.runId ?? '',
    evidenceBundleFingerprint: options.evidence ? fingerprint(`live-evidence-${itemIndex}`) : '',
    workbookSeedFingerprint: options.workbook ? fingerprint(`live-workbook-${itemIndex}`) : '',
    failureCode: options.failureCode ?? '',
    startedAt: status === 'PENDING' ? null : `2026-07-27T08:0${itemIndex}:00Z`,
    completedAt: options.completed ? `2026-07-27T08:0${itemIndex}:42Z` : null,
  };
}

function embeddedChild(
  workbook: ScenarioRehearsalWorkbookSeed,
): ScenarioRehearsalChildWorkbook {
  return {
    schemaVersion: workbook.schemaVersion,
    seedFingerprint: workbook.seedFingerprint,
    runId: workbook.runId,
    requestId: workbook.requestId,
    compiledPlanRef: workbook.compiledPlanRef,
    scenarioPackRef: workbook.scenarioPackRef,
    targetCapabilityRef: workbook.targetCapabilityRef,
    evidenceBundleFingerprint: workbook.evidenceBundleFingerprint,
    resultFingerprint: workbook.resultFingerprint,
    evidenceKeyId: workbook.evidenceKeyId,
    retentionProofFingerprint: workbook.retentionProofFingerprint,
    outcome: workbook.outcome,
    summary: workbook.summary,
    gateReady: workbook.gateReady,
    blockers: workbook.blockers,
  };
}

function childIndex(
  children: ScenarioRehearsalWorkbookSeed[],
): Record<string, ScenarioRehearsalWorkbookSeed> {
  return Object.fromEntries(children.map((child) => [child.runId, child]));
}

function artifact(kind: string, id: string): ScenarioArtifactRef {
  return {
    kind,
    id,
    revision: 1,
    fingerprint: fingerprint(`${kind}-${id}`),
  };
}

function fingerprint(seed: string): string {
  const hexadecimal = Array.from(seed)
    .map((character) => character.charCodeAt(0).toString(16).padStart(2, '0'))
    .join('');
  return `sha256:${(hexadecimal + '0'.repeat(64)).slice(0, 64)}`;
}
