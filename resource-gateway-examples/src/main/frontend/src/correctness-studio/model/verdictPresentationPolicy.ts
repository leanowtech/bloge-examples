import type {
  ProductMessageDescriptor,
  ProductMessageId,
} from '../../i18n/messageCatalog';

export type CorrectnessExecutionStatus =
  | 'NOT_RUN'
  | 'QUEUED'
  | 'RUNNING'
  | 'SUCCESS'
  | 'FAILED'
  | 'TIMEOUT'
  | 'PARTIAL'
  | 'SKIPPED'
  | 'CANCELLED';

export type CorrectnessAssertionStatus = 'NONE' | 'PASSED' | 'FAILED' | 'INCONCLUSIVE';
export type CorrectnessCoverageStatus = 'NOT_EVALUATED' | 'UNFROZEN' | 'GAPPED' | 'COMPLETE';
export type CorrectnessEvidenceStatus =
  | 'NOT_AVAILABLE'
  | 'EXPLORATORY'
  | 'CURRENT'
  | 'STALE'
  | 'REVOKED'
  | 'SUPERSEDED';
export type CorrectnessGateStatus = 'NOT_EVALUATED' | 'BLOCKED' | 'ACCEPTED';
export type CorrectnessProofLevel =
  | 'SCHEMA'
  | 'MOCK'
  | 'SANDBOX'
  | 'RUNTIME'
  | 'CERTIFIABLE';

export type CorrectnessVerdictReason =
  | 'NOT_RUN'
  | 'IN_PROGRESS'
  | 'EXECUTION_FAILED'
  | 'UNPROVEN'
  | 'ASSERTIONS_FAILED'
  | 'ASSERTIONS_INCONCLUSIVE'
  | 'COVERAGE_NOT_EVALUATED'
  | 'COVERAGE_UNFROZEN'
  | 'COVERAGE_GAPPED'
  | 'EVIDENCE_NOT_AVAILABLE'
  | 'EVIDENCE_EXPLORATORY'
  | 'EVIDENCE_STALE'
  | 'EVIDENCE_REVOKED'
  | 'EVIDENCE_SUPERSEDED'
  | 'GATE_BLOCKED'
  | 'GATE_NOT_EVALUATED'
  | 'ACCEPTED';

export type CorrectnessVerdictTone =
  | 'neutral'
  | 'running'
  | 'passed'
  | 'warning'
  | 'failed'
  | 'stale';

export interface CorrectnessVerdictInput {
  execution: CorrectnessExecutionStatus;
  assertions: CorrectnessAssertionStatus;
  coverage: CorrectnessCoverageStatus;
  evidence: CorrectnessEvidenceStatus;
  gate: CorrectnessGateStatus;
  proofLevel: CorrectnessProofLevel;
}

export interface CorrectnessAxisPresentation<TStatus extends string> {
  status: TStatus;
  label: ProductMessageDescriptor;
  value: ProductMessageDescriptor;
  tone: CorrectnessVerdictTone;
}

export interface CorrectnessVerdictPresentation {
  axes: {
    execution: CorrectnessAxisPresentation<CorrectnessExecutionStatus>;
    assertions: CorrectnessAxisPresentation<CorrectnessAssertionStatus>;
    coverage: CorrectnessAxisPresentation<CorrectnessCoverageStatus>;
    evidence: CorrectnessAxisPresentation<CorrectnessEvidenceStatus>;
    gate: CorrectnessAxisPresentation<CorrectnessGateStatus>;
  };
  proofLevel: CorrectnessProofLevel;
  reason: CorrectnessVerdictReason;
  tone: CorrectnessVerdictTone;
  primary: ProductMessageDescriptor;
  detail: ProductMessageDescriptor;
}

const AXIS_LABELS = {
  execution: 'correctness.axis.execution.label',
  assertions: 'correctness.axis.assertions.label',
  coverage: 'correctness.axis.coverage.label',
  evidence: 'correctness.axis.evidence.label',
  gate: 'correctness.axis.gate.label',
} as const satisfies Record<string, ProductMessageId>;

const EXECUTION_MESSAGES = statusMessages<CorrectnessExecutionStatus>('execution', [
  'NOT_RUN', 'QUEUED', 'RUNNING', 'SUCCESS', 'FAILED', 'TIMEOUT', 'PARTIAL', 'SKIPPED', 'CANCELLED',
]);
const ASSERTION_MESSAGES = statusMessages<CorrectnessAssertionStatus>('assertions', [
  'NONE', 'PASSED', 'FAILED', 'INCONCLUSIVE',
]);
const COVERAGE_MESSAGES = statusMessages<CorrectnessCoverageStatus>('coverage', [
  'NOT_EVALUATED', 'UNFROZEN', 'GAPPED', 'COMPLETE',
]);
const EVIDENCE_MESSAGES = statusMessages<CorrectnessEvidenceStatus>('evidence', [
  'NOT_AVAILABLE', 'EXPLORATORY', 'CURRENT', 'STALE', 'REVOKED', 'SUPERSEDED',
]);
const GATE_MESSAGES = statusMessages<CorrectnessGateStatus>('gate', [
  'NOT_EVALUATED', 'BLOCKED', 'ACCEPTED',
]);

const REASON_TONES: Record<CorrectnessVerdictReason, CorrectnessVerdictTone> = {
  NOT_RUN: 'neutral',
  IN_PROGRESS: 'running',
  EXECUTION_FAILED: 'failed',
  UNPROVEN: 'warning',
  ASSERTIONS_FAILED: 'failed',
  ASSERTIONS_INCONCLUSIVE: 'warning',
  COVERAGE_NOT_EVALUATED: 'warning',
  COVERAGE_UNFROZEN: 'warning',
  COVERAGE_GAPPED: 'warning',
  EVIDENCE_NOT_AVAILABLE: 'warning',
  EVIDENCE_EXPLORATORY: 'warning',
  EVIDENCE_STALE: 'stale',
  EVIDENCE_REVOKED: 'failed',
  EVIDENCE_SUPERSEDED: 'stale',
  GATE_BLOCKED: 'failed',
  GATE_NOT_EVALUATED: 'warning',
  ACCEPTED: 'passed',
};

/**
 * Produces the only product-level correctness verdict presentation.
 *
 * The supplied gate is treated as an external fact request, not as permission to bypass the
 * other axes. A weaker axis always normalizes an ACCEPTED request to BLOCKED.
 */
export function presentCorrectnessVerdict(
  input: CorrectnessVerdictInput,
): CorrectnessVerdictPresentation {
  const reason = primaryReason(input);
  const gate = normalizedGate(reason);
  return {
    axes: {
      execution: axis('execution', input.execution, EXECUTION_MESSAGES, executionTone(input.execution)),
      assertions: axis('assertions', input.assertions, ASSERTION_MESSAGES, assertionTone(input.assertions)),
      coverage: axis('coverage', input.coverage, COVERAGE_MESSAGES, coverageTone(input.coverage)),
      evidence: axis('evidence', input.evidence, EVIDENCE_MESSAGES, evidenceTone(input.evidence)),
      gate: axis('gate', gate, GATE_MESSAGES, gateTone(gate)),
    },
    proofLevel: input.proofLevel,
    reason,
    tone: REASON_TONES[reason],
    primary: { messageId: reasonMessage(reason, 'label') },
    detail: { messageId: reasonMessage(reason, 'detail') },
  };
}

function primaryReason(input: CorrectnessVerdictInput): CorrectnessVerdictReason {
  if (input.evidence === 'STALE') return 'EVIDENCE_STALE';
  if (input.evidence === 'REVOKED') return 'EVIDENCE_REVOKED';
  if (input.evidence === 'SUPERSEDED') return 'EVIDENCE_SUPERSEDED';
  if (input.execution === 'NOT_RUN') return 'NOT_RUN';
  if (input.execution === 'QUEUED' || input.execution === 'RUNNING') return 'IN_PROGRESS';
  if (input.execution !== 'SUCCESS') return 'EXECUTION_FAILED';
  if (input.assertions === 'NONE') return 'UNPROVEN';
  if (input.assertions === 'FAILED') return 'ASSERTIONS_FAILED';
  if (input.assertions === 'INCONCLUSIVE') return 'ASSERTIONS_INCONCLUSIVE';
  if (input.coverage === 'NOT_EVALUATED') return 'COVERAGE_NOT_EVALUATED';
  if (input.coverage === 'UNFROZEN') return 'COVERAGE_UNFROZEN';
  if (input.coverage === 'GAPPED') return 'COVERAGE_GAPPED';
  if (input.evidence === 'NOT_AVAILABLE') return 'EVIDENCE_NOT_AVAILABLE';
  if (input.evidence === 'EXPLORATORY') return 'EVIDENCE_EXPLORATORY';
  if (input.gate === 'BLOCKED') return 'GATE_BLOCKED';
  if (input.gate !== 'ACCEPTED') return 'GATE_NOT_EVALUATED';
  return 'ACCEPTED';
}

function normalizedGate(reason: CorrectnessVerdictReason): CorrectnessGateStatus {
  if (reason === 'NOT_RUN' || reason === 'IN_PROGRESS' || reason === 'GATE_NOT_EVALUATED') {
    return 'NOT_EVALUATED';
  }
  return reason === 'ACCEPTED' ? 'ACCEPTED' : 'BLOCKED';
}

function axis<TStatus extends string>(
  key: keyof typeof AXIS_LABELS,
  status: TStatus,
  messages: Record<TStatus, ProductMessageId>,
  tone: CorrectnessVerdictTone,
): CorrectnessAxisPresentation<TStatus> {
  return {
    status,
    label: { messageId: AXIS_LABELS[key] },
    value: { messageId: messages[status] },
    tone,
  };
}

function statusMessages<TStatus extends string>(
  axisName: string,
  statuses: readonly TStatus[],
): Record<TStatus, ProductMessageId> {
  return Object.fromEntries(statuses.map((status) => [
    status,
    `correctness.axis.${axisName}.${lowerCamelEnum(status)}` as ProductMessageId,
  ])) as Record<TStatus, ProductMessageId>;
}

function reasonMessage(
  reason: CorrectnessVerdictReason,
  field: 'label' | 'detail',
): ProductMessageId {
  return `correctness.verdict.${lowerCamelEnum(reason)}.${field}` as ProductMessageId;
}

function lowerCamelEnum(value: string): string {
  return value.toLocaleLowerCase().replace(/_([a-z])/g, (_, character: string) => (
    character.toLocaleUpperCase()
  ));
}

function executionTone(status: CorrectnessExecutionStatus): CorrectnessVerdictTone {
  if (status === 'NOT_RUN') return 'neutral';
  if (status === 'QUEUED' || status === 'RUNNING') return 'running';
  if (status === 'SUCCESS') return 'passed';
  return status === 'PARTIAL' || status === 'SKIPPED' ? 'warning' : 'failed';
}

function assertionTone(status: CorrectnessAssertionStatus): CorrectnessVerdictTone {
  if (status === 'PASSED') return 'passed';
  if (status === 'FAILED') return 'failed';
  return status === 'NONE' ? 'neutral' : 'warning';
}

function coverageTone(status: CorrectnessCoverageStatus): CorrectnessVerdictTone {
  if (status === 'COMPLETE') return 'passed';
  return status === 'NOT_EVALUATED' ? 'neutral' : 'warning';
}

function evidenceTone(status: CorrectnessEvidenceStatus): CorrectnessVerdictTone {
  if (status === 'CURRENT') return 'passed';
  if (status === 'STALE' || status === 'SUPERSEDED') return 'stale';
  if (status === 'REVOKED') return 'failed';
  return status === 'NOT_AVAILABLE' ? 'neutral' : 'warning';
}

function gateTone(status: CorrectnessGateStatus): CorrectnessVerdictTone {
  if (status === 'ACCEPTED') return 'passed';
  if (status === 'BLOCKED') return 'failed';
  return 'neutral';
}
