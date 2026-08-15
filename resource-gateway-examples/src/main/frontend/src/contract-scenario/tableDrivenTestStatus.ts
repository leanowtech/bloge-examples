import {
  presentCorrectnessVerdict,
  type CorrectnessCoverageStatus,
  type CorrectnessEvidenceStatus,
  type CorrectnessExecutionStatus,
  type CorrectnessVerdictPresentation,
} from '../correctness-studio/model/verdictPresentationPolicy';
import type {
  ProductMessageDescriptor,
  ProductMessageId,
} from '../i18n/messageCatalog';

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
  /** Legacy Scenario rows have no frozen inventory and therefore remain NOT_EVALUATED. */
  coverage?: CorrectnessCoverageStatus;
}

export interface TableCaseVerdictPresentation {
  tone: 'neutral' | 'running' | 'passed' | 'warning' | 'failed' | 'stale';
  label: ProductMessageDescriptor;
  detail: ProductMessageDescriptor;
}

export type TableCaseGovernanceEligibility = 'ELIGIBLE' | 'INELIGIBLE' | 'NOT_EVALUATED';

export interface TableCaseAuthorityPresentation {
  verdict: CorrectnessVerdictPresentation;
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
  const correctness = correctnessVerdictForTableCase(verdict);
  return {
    tone: correctness.tone,
    label: correctness.primary,
    detail: correctness.detail,
  };
}

export function presentTableCaseAuthority(verdict: TableCaseVerdict): TableCaseAuthorityPresentation {
  const correctness = correctnessVerdictForTableCase(verdict);
  const behavior = verdict.execution === 'SUCCESS' && verdict.assertions === 'PASSED'
    ? { messageId: proofPassedLabel(verdict.proofStrength) }
    : correctness.primary;
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
    verdict: correctness,
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
  const gate = correctnessVerdictForTableCase(verdict).axes.gate.status;
  return gate === 'ACCEPTED'
    ? 'ELIGIBLE'
    : gate === 'BLOCKED' ? 'INELIGIBLE' : 'NOT_EVALUATED';
}

export function correctnessVerdictForTableCase(
  verdict: TableCaseVerdict,
): CorrectnessVerdictPresentation {
  const execution = correctnessExecution(verdict.execution);
  const evidence = correctnessEvidence(verdict);
  const coverage = verdict.coverage ?? 'NOT_EVALUATED';
  const gate = execution === 'NOT_RUN' || execution === 'QUEUED' || execution === 'RUNNING'
    ? 'NOT_EVALUATED'
    : verdict.proofStrength === 'CERTIFIABLE'
      && execution === 'SUCCESS'
      && verdict.assertions === 'PASSED'
      && coverage === 'COMPLETE'
      && evidence === 'CURRENT'
      ? 'ACCEPTED'
      : 'BLOCKED';
  return presentCorrectnessVerdict({
    execution,
    assertions: verdict.assertions,
    coverage,
    evidence,
    gate,
    proofLevel: verdict.proofStrength,
  });
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

function correctnessExecution(status: TableCaseExecutionStatus): CorrectnessExecutionStatus {
  if (status === 'ERROR') return 'FAILED';
  if (status === 'BUDGET_STOPPED') return 'PARTIAL';
  return status;
}

function correctnessEvidence(verdict: TableCaseVerdict): CorrectnessEvidenceStatus {
  if (verdict.execution === 'NOT_RUN'
    || verdict.execution === 'QUEUED'
    || verdict.execution === 'RUNNING') {
    return 'NOT_AVAILABLE';
  }
  if (verdict.freshness === 'STALE') return 'STALE';
  if (verdict.freshness === 'SUPERSEDED') return 'SUPERSEDED';
  return verdict.proofStrength === 'CERTIFIABLE' ? 'CURRENT' : 'EXPLORATORY';
}
