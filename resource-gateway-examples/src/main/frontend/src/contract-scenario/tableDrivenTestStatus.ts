/** Execution is a runtime fact and must never be collapsed into assertion or governance state. */
export type TableCaseExecutionStatus =
  'NOT_RUN'
  | 'QUEUED'
  | 'RUNNING'
  | 'SUCCESS'
  | 'ERROR'
  | 'TIMEOUT'
  | 'SKIPPED'
  | 'CANCELLED'
  | 'BUDGET_STOPPED';

/** Assertions describe the business oracle independently from runtime completion. */
export type TableCaseAssertionStatus = 'NONE' | 'PASSED' | 'FAILED' | 'INCONCLUSIVE';

/** Freshness states whether evidence still proves the currently visible material. */
export type TableCaseEvidenceFreshness = 'CURRENT' | 'STALE' | 'SUPERSEDED';

/** Proof strength tells the author what kind of execution actually happened. */
export type TableCaseProofStrength =
  'SCHEMA'
  | 'MOCK'
  | 'SANDBOX'
  | 'RUNTIME'
  | 'CERTIFIABLE';

export interface TableCaseVerdict {
  execution: TableCaseExecutionStatus;
  assertions: TableCaseAssertionStatus;
  freshness: TableCaseEvidenceFreshness;
  proofStrength: TableCaseProofStrength;
}

export interface TableCaseVerdictPresentation {
  tone: 'neutral' | 'running' | 'passed' | 'warning' | 'failed' | 'stale';
  label: string;
  detail: string;
}

/**
 * Projects one honest human verdict without manufacturing a generic "Passed" state.
 *
 * Freshness wins because stale proof cannot support a current decision. Runtime failures win over
 * assertions because no business comparison can repair an execution that did not complete.
 */
export function presentTableCaseVerdict(
  verdict: TableCaseVerdict,
): TableCaseVerdictPresentation {
  if (verdict.freshness !== 'CURRENT') {
    return {
      tone: 'stale',
      label: verdict.freshness === 'STALE' ? 'Evidence stale' : 'Evidence superseded',
      detail: 'Run this case again against the current Scenario, Fixture, Contract, and target.',
    };
  }
  if (verdict.execution === 'NOT_RUN') {
    return { tone: 'neutral', label: 'Not run', detail: 'No execution evidence exists for this case.' };
  }
  if (verdict.execution === 'QUEUED' || verdict.execution === 'RUNNING') {
    return {
      tone: 'running',
      label: verdict.execution === 'QUEUED' ? 'Queued' : 'Running',
      detail: 'Execution is in progress; no final business verdict is available.',
    };
  }
  if (verdict.execution !== 'SUCCESS') {
    return {
      tone: verdict.execution === 'SKIPPED' || verdict.execution === 'BUDGET_STOPPED'
        ? 'warning'
        : 'failed',
      label: executionLabel(verdict.execution),
      detail: 'Execution did not produce a successful result for business assertions.',
    };
  }
  if (verdict.assertions === 'FAILED') {
    return {
      tone: 'failed',
      label: 'Assertions failed',
      detail: 'Runtime execution completed, but at least one expected business outcome did not match.',
    };
  }
  if (verdict.assertions === 'INCONCLUSIVE') {
    return {
      tone: 'warning',
      label: 'Assertions inconclusive',
      detail: 'Runtime execution completed, but the business oracle could not be evaluated completely.',
    };
  }
  if (verdict.assertions === 'NONE') {
    return {
      tone: 'warning',
      label: `${proofLabel(verdict.proofStrength)} execution succeeded`,
      detail: 'No business assertion was evaluated; this is not correctness evidence.',
    };
  }
  return {
    tone: 'passed',
    label: proofPassedLabel(verdict.proofStrength),
    detail: 'Execution completed and every authored business assertion passed.',
  };
}

function executionLabel(status: Exclude<TableCaseExecutionStatus, 'NOT_RUN' | 'QUEUED' | 'RUNNING' | 'SUCCESS'>): string {
  switch (status) {
    case 'ERROR': return 'Execution error';
    case 'TIMEOUT': return 'Execution timed out';
    case 'SKIPPED': return 'Skipped';
    case 'CANCELLED': return 'Cancelled';
    case 'BUDGET_STOPPED': return 'Stopped by budget';
  }
}

function proofLabel(proof: TableCaseProofStrength): string {
  switch (proof) {
    case 'SCHEMA': return 'Schema';
    case 'MOCK': return 'Mock';
    case 'SANDBOX': return 'Sandbox';
    case 'RUNTIME': return 'Runtime';
    case 'CERTIFIABLE': return 'Certifiable';
  }
}

function proofPassedLabel(proof: TableCaseProofStrength): string {
  switch (proof) {
    case 'SCHEMA': return 'Schema contract valid';
    case 'MOCK': return 'Mock behavior matched';
    case 'SANDBOX': return 'Sandbox behavior matched';
    case 'RUNTIME': return 'Runtime behavior matched';
    case 'CERTIFIABLE': return 'Certifiable behavior matched';
  }
}
