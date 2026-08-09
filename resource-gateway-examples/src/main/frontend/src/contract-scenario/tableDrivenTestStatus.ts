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
  label: ProductMessageDescriptor;
  detail: ProductMessageDescriptor;
}

export type TableCaseGovernanceEligibility = 'ELIGIBLE' | 'INELIGIBLE' | 'NOT_EVALUATED';

export interface TableCaseAuthorityPresentation {
  behavior: ProductMessageDescriptor;
  proof: ProductMessageDescriptor;
  proofDetail: ProductMessageDescriptor;
  freshness: ProductMessageDescriptor;
  governance: ProductMessageDescriptor;
  governanceDetail: ProductMessageDescriptor;
  governanceEligibility: TableCaseGovernanceEligibility;
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
    return presentation(
      'stale',
      verdict.freshness === 'STALE'
        ? 'table.verdict.evidenceStale'
        : 'table.verdict.evidenceSuperseded',
      'table.detail.rerunCurrent',
    );
  }
  if (verdict.execution === 'NOT_RUN') {
    return presentation('neutral', 'table.verdict.notRun', 'table.detail.noEvidence');
  }
  if (verdict.execution === 'QUEUED' || verdict.execution === 'RUNNING') {
    return presentation(
      'running',
      verdict.execution === 'QUEUED' ? 'table.verdict.queued' : 'table.verdict.running',
      'table.detail.inProgress',
    );
  }
  if (verdict.execution !== 'SUCCESS') {
    return presentation(
      verdict.execution === 'SKIPPED' || verdict.execution === 'BUDGET_STOPPED'
        ? 'warning'
        : 'failed',
      executionLabel(verdict.execution),
      'table.detail.executionFailed',
    );
  }
  if (verdict.assertions === 'FAILED') {
    return presentation('failed', 'table.verdict.assertionsFailed', 'table.detail.assertionsFailed');
  }
  if (verdict.assertions === 'INCONCLUSIVE') {
    return presentation(
      'warning',
      'table.verdict.assertionsInconclusive',
      'table.detail.assertionsInconclusive',
    );
  }
  if (verdict.assertions === 'NONE') {
    return presentation('warning', proofSucceededLabel(verdict.proofStrength), 'table.detail.noAssertions');
  }
  return presentation('passed', proofPassedLabel(verdict.proofStrength), 'table.detail.assertionsPassed');
}

export function presentTableCaseAuthority(verdict: TableCaseVerdict): TableCaseAuthorityPresentation {
  const behavior = presentTableCaseVerdict(verdict).label;
  const governanceEligibility = governanceEligibilityFor(verdict);
  const freshness = verdict.execution === 'NOT_RUN'
    || verdict.execution === 'QUEUED'
    || verdict.execution === 'RUNNING'
    ? { messageId: 'table.freshness.notEvaluated.label' as const }
    : { messageId: freshnessMessageId(verdict.freshness) };
  const governanceKey = governanceEligibility === 'ELIGIBLE'
    ? 'eligible'
    : governanceEligibility === 'INELIGIBLE' ? 'ineligible' : 'notEvaluated';
  return {
    behavior,
    proof: { messageId: proofMessageId(verdict.proofStrength, 'label') },
    proofDetail: { messageId: proofMessageId(verdict.proofStrength, 'detail') },
    freshness,
    governance: { messageId: `table.governance.${governanceKey}.label` },
    governanceDetail: { messageId: `table.governance.${governanceKey}.detail` },
    governanceEligibility,
  };
}

export function governanceEligibilityFor(verdict: TableCaseVerdict): TableCaseGovernanceEligibility {
  if (verdict.execution === 'NOT_RUN'
    || verdict.execution === 'QUEUED'
    || verdict.execution === 'RUNNING'
    || verdict.assertions === 'NONE') {
    return 'NOT_EVALUATED';
  }
  return verdict.execution === 'SUCCESS'
    && verdict.assertions === 'PASSED'
    && verdict.freshness === 'CURRENT'
    && verdict.proofStrength === 'CERTIFIABLE'
    ? 'ELIGIBLE'
    : 'INELIGIBLE';
}

function presentation(
  tone: TableCaseVerdictPresentation['tone'],
  label: ProductMessageId,
  detail: ProductMessageId,
): TableCaseVerdictPresentation {
  return { tone, label: { messageId: label }, detail: { messageId: detail } };
}

function executionLabel(
  status: Exclude<TableCaseExecutionStatus, 'NOT_RUN' | 'QUEUED' | 'RUNNING' | 'SUCCESS'>,
): ProductMessageId {
  switch (status) {
    case 'ERROR': return 'table.verdict.executionError';
    case 'TIMEOUT': return 'table.verdict.executionTimeout';
    case 'SKIPPED': return 'table.verdict.skipped';
    case 'CANCELLED': return 'table.verdict.cancelled';
    case 'BUDGET_STOPPED': return 'table.verdict.budgetStopped';
  }
}

function proofSucceededLabel(proof: TableCaseProofStrength): ProductMessageId {
  switch (proof) {
    case 'SCHEMA': return 'table.verdict.schemaSucceeded';
    case 'MOCK': return 'table.verdict.mockSucceeded';
    case 'SANDBOX': return 'table.verdict.sandboxSucceeded';
    case 'RUNTIME': return 'table.verdict.runtimeSucceeded';
    case 'CERTIFIABLE': return 'table.verdict.certifiableSucceeded';
  }
}

function proofPassedLabel(proof: TableCaseProofStrength): ProductMessageId {
  switch (proof) {
    case 'SCHEMA': return 'table.verdict.schemaMatched';
    case 'MOCK': return 'table.verdict.mockMatched';
    case 'SANDBOX': return 'table.verdict.sandboxMatched';
    case 'RUNTIME': return 'table.verdict.runtimeMatched';
    case 'CERTIFIABLE': return 'table.verdict.certifiableMatched';
  }
}

function proofMessageId(
  proof: TableCaseProofStrength,
  field: 'label' | 'detail',
): ProductMessageId {
  const proofKey = proof.toLocaleLowerCase() as Lowercase<TableCaseProofStrength>;
  return `table.proof.${proofKey}.${field}`;
}

function freshnessMessageId(freshness: TableCaseEvidenceFreshness): ProductMessageId {
  const freshnessKey = freshness === 'CURRENT'
    ? 'current'
    : freshness === 'STALE' ? 'stale' : 'superseded';
  return `table.freshness.${freshnessKey}.label`;
}
import type {
  ProductMessageDescriptor,
  ProductMessageId,
} from '../i18n/messageCatalog';
