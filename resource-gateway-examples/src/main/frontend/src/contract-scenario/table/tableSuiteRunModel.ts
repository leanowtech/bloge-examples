import type { ContractDraft, ScenarioDraftSet } from '../domain';
import type { GraphDraft } from '../../types';
import type {
  ScenarioRunSelectionMode,
  ScenarioCommandReceipt,
  ScenarioTableEvidenceByCase,
  TableCaseEvidenceProjection,
} from './scenarioTableModel';
import { scenarioTableCaseFingerprint } from './scenarioTableModel';

export type TableSuiteBatchStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'BUDGET_STOPPED';

export type TableSuiteRowStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'SUCCESS'
  | 'ASSERTION_FAILED'
  | 'COMPILE_ERROR'
  | 'RUNTIME_ERROR'
  | 'TIMEOUT'
  | 'CANCELLED'
  | 'BUDGET_STOPPED';

export interface TableSuiteRunCommand {
  schemaVersion: 'bloge.tableSuiteRunCommand.v1';
  requestId: string;
  graphDraft: GraphDraft;
  contract: ContractDraft;
  draftSet: ScenarioDraftSet;
  selection: { mode: ScenarioRunSelectionMode; caseIds: string[] };
  preflight: {
    environment: string;
    dependencyMode: 'SIMULATED';
    effectProfile: 'SIDE_EFFECT_FREE';
    maxCases: number;
    maxFailures: number;
    maxConcurrency: 1;
    caseTimeoutMs: number;
  };
  baselineBatchId: string;
}

export interface TableSuiteAttemptEvidence {
  attempt: number;
  status: TableSuiteRowStatus;
  assertions: 'NONE' | 'PASSED' | 'FAILED' | 'INCONCLUSIVE';
  proofStrength: 'SCHEMA' | 'MOCK' | 'RUNTIME';
  durationMs: number;
  runFingerprint: string;
  firstFailure: {
    category: string;
    code: string;
    target: string;
    summary: string;
  } | null;
  assertionEvidence: Array<{
    assertionId: string;
    path: string;
    passed: boolean;
    expectedFingerprint: string;
    actualFingerprint: string;
    diagnosticCode: string;
  }>;
  startedAt: string;
  completedAt: string;
}

export interface TableSuiteRowEvidence {
  caseId: string;
  caseFingerprint: string;
  status: TableSuiteRowStatus;
  attempts: TableSuiteAttemptEvidence[];
  flaky: boolean;
  baseline: {
    baselineBatchId: string;
    baselineStatus: TableSuiteRowStatus | null;
    outcome: 'NONE' | 'SAME' | 'IMPROVED' | 'REGRESSED' | 'CHANGED_INPUT' | 'NEW';
  };
}

export interface TableSuiteRunCounts {
  total: number;
  queued: number;
  running: number;
  succeeded: number;
  failed: number;
  cancelled: number;
  budgetStopped: number;
}

export interface TableSuiteRunEvent {
  revision: number;
  type: string;
  caseId: string;
  row: TableSuiteRowEvidence | null;
  observedAt: string;
}

export interface TableSuiteRunBatch {
  schemaVersion: 'bloge.tableSuiteRunBatch.v1';
  batchId: string;
  requestId: string;
  requestFingerprint: string;
  scope: ScenarioDraftSet['scope'];
  target: ScenarioDraftSet['target'];
  scenarioDraftSetId: string;
  scenarioDraftSetRevision: number;
  scenarioDraftSetFingerprint: string;
  contractFingerprint: string;
  selection: {
    mode: ScenarioRunSelectionMode;
    caseIds: string[];
    fingerprint: string;
    fullSuite: boolean;
  };
  preflight: TableSuiteRunCommand['preflight'];
  baselineBatchId: string;
  status: TableSuiteBatchStatus;
  revision: number;
  cancelRequested: boolean;
  rows: TableSuiteRowEvidence[];
  counts: TableSuiteRunCounts;
  promotion: { eligible: boolean; reason: string };
  events: TableSuiteRunEvent[];
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
}

export interface TableSuiteRunDelta {
  schemaVersion: 'bloge.tableSuiteRunDelta.v1';
  batchId: string;
  revision: number;
  status: TableSuiteBatchStatus;
  counts: TableSuiteRunCounts;
  promotion: TableSuiteRunBatch['promotion'];
  resetRequired: boolean;
  events: TableSuiteRunEvent[];
}

export interface TableSuiteBaselineSummary {
  batchId: string;
  target: ScenarioDraftSet['target'];
  contractFingerprint: string;
  caseFingerprints: Record<string, string>;
  failedCaseIds: string[];
}

export interface TableSuiteDifferentialCounts {
  failed: number;
  changed: number;
  affected: number;
  targetChanged: boolean;
}

export function createTableSuiteRunCommand(
  graphDraft: GraphDraft,
  contract: ContractDraft,
  draftSet: ScenarioDraftSet,
  mode: ScenarioRunSelectionMode,
  selectedCaseIds: string[],
  baselineBatchId: string,
): TableSuiteRunCommand {
  return {
    schemaVersion: 'bloge.tableSuiteRunCommand.v1',
    requestId: requestId(),
    graphDraft: {
      ...graphDraft,
      schemaVersion: graphDraft.schemaVersion ?? 'bloge.visualGraphDraft.v1',
    },
    contract,
    draftSet,
    selection: {
      mode,
      caseIds: mode === 'SELECTED' ? [...selectedCaseIds] : [],
    },
    preflight: {
      environment: draftSet.scope.environment,
      dependencyMode: 'SIMULATED',
      effectProfile: 'SIDE_EFFECT_FREE',
      maxCases: 500,
      maxFailures: Math.min(25, Math.max(0, draftSet.scenarios.length)),
      maxConcurrency: 1,
      caseTimeoutMs: 10_000,
    },
    baselineBatchId: ['FAILED', 'CHANGED', 'AFFECTED'].includes(mode)
      ? baselineBatchId
      : '',
  };
}

/** Applies durable row events without replacing already rendered rows or waiting for full completion. */
export function applyTableSuiteRunDelta(
  batch: TableSuiteRunBatch,
  delta: TableSuiteRunDelta,
): TableSuiteRunBatch {
  if (delta.batchId !== batch.batchId || delta.revision < batch.revision) return batch;
  if (delta.resetRequired) return batch;
  const rows = new Map(batch.rows.map((row) => [row.caseId, row]));
  for (const event of delta.events) {
    if (event.row) rows.set(event.row.caseId, event.row);
  }
  return {
    ...batch,
    revision: delta.revision,
    status: delta.status,
    counts: delta.counts,
    promotion: delta.promotion,
    rows: batch.rows.map((row) => rows.get(row.caseId) ?? row),
    events: [...batch.events, ...delta.events].slice(-5_000),
  };
}

export function tableSuiteEvidenceByCase(
  batch: TableSuiteRunBatch,
  receipt: ScenarioCommandReceipt = tableSuiteCommandReceipt(batch),
): ScenarioTableEvidenceByCase {
  return Object.fromEntries(batch.rows.map((row) => [row.caseId, rowProjection(batch, row, receipt)]));
}

/** Projects protocol coordinates only; Scenario payloads never enter the UI receipt. */
export function tableSuiteCommandReceipt(batch: TableSuiteRunBatch): ScenarioCommandReceipt {
  return {
    correlationId: batch.requestId,
    source: 'SERVER',
    state: tableSuiteBatchTerminal(batch) ? 'TERMINAL' : 'ADMITTED',
    mode: batch.selection.mode,
    caseIds: [...batch.selection.caseIds],
    caseCount: batch.selection.caseIds.length,
    previewFingerprint: '',
    canonicalFingerprint: batch.selection.fingerprint,
    batchId: batch.batchId,
  };
}

export function tableSuiteBatchTerminal(batch: TableSuiteRunBatch | null): boolean {
  return Boolean(batch && ['SUCCEEDED', 'FAILED', 'CANCELLED', 'BUDGET_STOPPED'].includes(batch.status));
}

/** Only a full batch whose rows all produced conclusive attempts can drive later differential runs. */
export function tableSuiteBatchIsCompleteBaseline(batch: TableSuiteRunBatch | null): boolean {
  return Boolean(batch
    && batch.selection.fullSuite
    && ['SUCCEEDED', 'FAILED'].includes(batch.status)
    && batch.rows.length === batch.selection.caseIds.length
    && batch.rows.every((row) => !['QUEUED', 'RUNNING', 'CANCELLED', 'BUDGET_STOPPED'].includes(row.status)));
}

/** Freezes only fingerprints and verdict ids; Scenario values never enter browser persistence. */
export function createTableSuiteBaselineSummary(
  batch: TableSuiteRunBatch,
  draftSet: ScenarioDraftSet,
): TableSuiteBaselineSummary | null {
  if (!tableSuiteBatchIsCompleteBaseline(batch)) return null;
  return {
    batchId: batch.batchId,
    target: { ...batch.target },
    contractFingerprint: batch.contractFingerprint,
    caseFingerprints: Object.fromEntries(draftSet.scenarios.map((scenario) => [
      scenario.scenarioId,
      scenarioTableCaseFingerprint(scenario),
    ])),
    failedCaseIds: batch.rows.filter((row) => rowStatusFailed(row.status)).map((row) => row.caseId),
  };
}

export function tableSuiteDifferentialCounts(
  draftSet: ScenarioDraftSet,
  baseline: TableSuiteBaselineSummary | null,
): TableSuiteDifferentialCounts | null {
  if (!baseline) return null;
  const currentIds = new Set(draftSet.scenarios.map((scenario) => scenario.scenarioId));
  const failed = baseline.failedCaseIds.filter((caseId) => currentIds.has(caseId));
  const changed = draftSet.scenarios
    .filter((scenario) => baseline.caseFingerprints[scenario.scenarioId]
      !== scenarioTableCaseFingerprint(scenario))
    .map((scenario) => scenario.scenarioId);
  const targetChanged = JSON.stringify(baseline.target) !== JSON.stringify(draftSet.target)
    || baseline.contractFingerprint !== draftSet.contractFingerprint;
  const affected = targetChanged
    ? draftSet.scenarios.map((scenario) => scenario.scenarioId)
    : [...new Set([...failed, ...changed])];
  return {
    failed: failed.length,
    changed: changed.length,
    affected: affected.length,
    targetChanged,
  };
}

export function tableSuiteBatchStorageKey(draftSet: ScenarioDraftSet): string {
  const { scope, scenarioDraftSetId } = draftSet;
  return ['bloge-table-run-v1', scope.tenantId, scope.organizationId, scope.projectId,
    scope.environment, scope.region, scenarioDraftSetId].join(':');
}

function rowProjection(
  batch: TableSuiteRunBatch,
  row: TableSuiteRowEvidence,
  commandReceipt: ScenarioCommandReceipt,
): TableCaseEvidenceProjection {
  const latest = row.attempts[row.attempts.length - 1];
  return {
    caseId: row.caseId,
    runId: batch.batchId,
    attempt: latest?.attempt ?? 0,
    execution: executionStatus(row.status),
    assertions: latest?.assertions ?? 'NONE',
    freshness: 'CURRENT',
    proofStrength: latest?.proofStrength ?? 'SCHEMA',
    subjectMode: 'REAL',
    durationMs: latest?.durationMs ?? null,
    assertionDiffs: latest?.assertionEvidence.map((assertion) => ({
      assertionId: assertion.assertionId,
      path: assertion.path,
      passed: assertion.passed,
      expected: assertion.expectedFingerprint,
      actual: assertion.actualFingerprint,
      detail: assertion.passed
        ? 'Expected and actual values have matching evidence fingerprints.'
        : `Expected and actual evidence fingerprints differ (${assertion.diagnosticCode}).`,
    })) ?? [],
    flaky: row.flaky,
    baselineOutcome: row.baseline.outcome,
    firstFailure: latest?.firstFailure ? {
      category: latest.firstFailure.category,
      target: latest.firstFailure.target,
      message: latest.firstFailure.summary,
    } : null,
    commandReceipt,
  };
}

function executionStatus(status: TableSuiteRowStatus): TableCaseEvidenceProjection['execution'] {
  switch (status) {
    case 'QUEUED': return 'QUEUED';
    case 'RUNNING': return 'RUNNING';
    case 'SUCCESS':
    case 'ASSERTION_FAILED': return 'SUCCESS';
    case 'COMPILE_ERROR':
    case 'RUNTIME_ERROR': return 'ERROR';
    case 'TIMEOUT': return 'TIMEOUT';
    case 'CANCELLED': return 'CANCELLED';
    case 'BUDGET_STOPPED': return 'BUDGET_STOPPED';
  }
}

function rowStatusFailed(status: TableSuiteRowStatus): boolean {
  return ['ASSERTION_FAILED', 'COMPILE_ERROR', 'RUNTIME_ERROR', 'TIMEOUT'].includes(status);
}

function requestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `table-run-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}
