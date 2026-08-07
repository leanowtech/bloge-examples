import type {
  ScenarioRehearsalBatchItemAttemptTimeline,
  ScenarioRehearsalBatchJob,
  ScenarioRehearsalChildWorkbook,
} from '../types';
import type {
  RemediationAction,
  RemediationSource,
} from './remediationAction';

export interface RehearsalAuthorTarget {
  kind: 'GRAPH_DRAFT' | 'OPERATOR' | 'FUNCTION';
  id: string;
  label: string;
  draftId?: string;
  revision?: number;
  nodeId?: string;
  scenarioId?: string;
  runId?: string;
  owner?: string;
  requiredRole?: string;
}

export interface RehearsalEvidenceEntry {
  index: number;
  id: string;
  status: 'PENDING' | 'RUNNING' | 'PASSED' | 'FAILED' | 'INDETERMINATE' | 'CANCELLED';
  attemptCount: number;
  runId: string;
  failureCode: string;
  planId: string;
  planRevision: number;
  childWorkbook: ScenarioRehearsalChildWorkbook | null;
  startedAt: string | null;
  completedAt: string | null;
  authorTarget?: RehearsalAuthorTarget;
}

export interface RehearsalAttemptStep {
  attempt: number;
  state: 'RETRY SCHEDULED' | 'RUNNING' | 'TERMINAL';
  observation: string;
  observedAt: string;
  exact: boolean;
}

export interface RehearsalEvidencePresentation {
  headline: string;
  verdictLabel: string;
  verdictTone: 'success' | 'danger' | 'warning' | 'neutral';
  rootCause: string;
  businessImpact: string;
  owner: string;
  requiredRole: string;
  attemptsUsed: number;
  attemptsMaximum: number;
  attemptsRemaining: number;
  attemptsExact: boolean;
  deadlineAt: string;
  batchFallback: string;
  itemFallback: string;
  lastObservation: string;
  timeline: RehearsalAttemptStep[];
  action: RemediationAction | null;
}

export function rehearsalEvidencePresentation(
  entry: RehearsalEvidenceEntry,
  job: ScenarioRehearsalBatchJob,
  diagnosis: { category: string; reason: string },
  options: {
    sampleMode?: boolean;
    currentHref?: string;
    exactAttempts?: ScenarioRehearsalBatchItemAttemptTimeline | null;
  } = {},
): RehearsalEvidencePresentation {
  const failure = humanizeMachineCode(entry.failureCode);
  const rootCause = failure || humanizeMachineCode(diagnosis.reason) || outcomeReason(entry);
  const businessImpact = impactFor(diagnosis.category, entry.status);
  const owner = entry.authorTarget?.owner || ownerFor(diagnosis.category, job);
  const requiredRole = entry.authorTarget?.requiredRole || roleFor(diagnosis.category);
  const exactAttempts = options.exactAttempts;
  const attemptsUsed = exactAttempts?.attemptsUsed ?? entry.attemptCount;
  const attemptsMaximum = Math.max(
    exactAttempts?.maximumAttempts ?? job.maximumItemAttempts,
    attemptsUsed,
  );
  const lastObservation = failure || outcomeReason(entry);
  return {
    headline: headlineFor(diagnosis.category, entry.status, entry.failureCode),
    ...verdictFor(diagnosis.category, entry.status),
    rootCause,
    businessImpact,
    owner,
    requiredRole,
    attemptsUsed,
    attemptsMaximum,
    attemptsRemaining: exactAttempts?.attemptsRemaining
      ?? Math.max(0, attemptsMaximum - attemptsUsed),
    attemptsExact: exactAttempts?.historyComplete === true,
    deadlineAt: exactAttempts?.deadlineAt ?? job.deadlineAt,
    batchFallback: batchFallback(exactAttempts?.failureMode ?? job.failureMode),
    itemFallback: 'No per-item fallback was advertised by the batch protocol.',
    lastObservation,
    timeline: attemptTimeline(entry, job, options.exactAttempts),
    action: remediationFor(entry, job, diagnosis, rootCause, businessImpact, owner, requiredRole, options),
  };
}

function verdictFor(
  category: string,
  status: RehearsalEvidenceEntry['status'],
): Pick<RehearsalEvidencePresentation, 'verdictLabel' | 'verdictTone'> {
  if (category === 'PASSED') return { verdictLabel: 'PASSED', verdictTone: 'success' };
  if (category === 'WARNINGS') return { verdictLabel: 'REVIEW WARNING', verdictTone: 'warning' };
  if (status === 'PENDING' || status === 'RUNNING') {
    return { verdictLabel: status, verdictTone: 'neutral' };
  }
  const scope = category === 'EXECUTION' ? 'EXECUTION FAILED' : `${category} BLOCKER`;
  return { verdictLabel: scope, verdictTone: 'danger' };
}

function remediationFor(
  entry: RehearsalEvidenceEntry,
  job: ScenarioRehearsalBatchJob,
  diagnosis: { category: string; reason: string },
  rootCause: string,
  businessImpact: string,
  owner: string,
  requiredRole: string,
  options: { sampleMode?: boolean; currentHref?: string },
): RemediationAction | null {
  if (diagnosis.category === 'PASSED' || entry.status === 'PENDING' || entry.status === 'RUNNING') {
    return null;
  }
  const timeout = /TIMEOUT|DEADLINE/.test(entry.failureCode);
  const governance = diagnosis.category === 'GOVERNANCE';
  const source: RemediationSource = timeout
    ? 'REHEARSAL_TIMEOUT'
    : governance
      ? 'ANEKE_GATE_BLOCKER'
      : 'RUN_FAILURE';
  const target = entry.authorTarget;
  const sampleUnavailable = options.sampleMode === true;
  const sampleRetry = sampleUnavailable && timeout;
  const available = sampleRetry || (Boolean(target) && !sampleUnavailable);
  return {
    id: `rehearsal:${job.jobId}:${entry.index}`,
    source,
    severity: diagnosis.category === 'WARNINGS' ? 'WARNING' : 'BLOCKING',
    target: {
      kind: target?.kind === 'GRAPH_DRAFT'
        ? 'GRAPH_DRAFT'
        : target?.kind === 'OPERATOR'
          ? 'OPERATOR'
          : target?.kind === 'FUNCTION'
            ? 'FUNCTION'
            : 'REHEARSAL',
      id: target?.id || `${job.jobId}:${entry.index}`,
      label: target?.label || `${entry.planId}@${entry.planRevision}`,
      draftId: target?.draftId,
      revision: target?.revision,
      nodeId: target?.nodeId,
      scenarioId: target?.scenarioId,
      runId: target?.runId || entry.runId,
    },
    rootCause,
    businessImpact,
    actionKind: sampleRetry ? 'RETRY_REHEARSAL'
      : target ? 'OPEN_AUTHOR_TARGET' : timeout ? 'RETRY_REHEARSAL' : 'OPEN_DIAGNOSTIC',
    actionLabel: sampleRetry ? 'Run sample retry'
      : target ? 'Open exact Author target' : timeout ? 'Request controlled retry' : 'Request owner review',
    deepLink: target ? authorTargetLink(target, options.currentHref) : '',
    navigation: sampleRetry ? 'DIAGNOSTIC' : target ? 'AUTHOR' : 'UNAVAILABLE',
    requiredRole,
    owner,
    auditRequirement: timeout
      ? 'A retry must retain the predecessor run, attempt budget, and resulting evidence.'
      : 'The decision must remain bound to this plan revision and batch item.',
    expiresAt: job.deadlineAt,
    available,
    unavailableReason: sampleRetry
      ? ''
      : sampleUnavailable
      ? 'Illustrative samples do not have a server-side Author target.'
      : target
        ? ''
        : `This plan does not advertise an Author source. Contact ${owner}.`,
    diagnosticId: '',
    technicalCode: entry.failureCode || diagnosis.category,
    technicalCoordinate: `${job.jobId}/items/${entry.index}`,
  };
}

function attemptTimeline(
  entry: RehearsalEvidenceEntry,
  job: ScenarioRehearsalBatchJob,
  exact: ScenarioRehearsalBatchItemAttemptTimeline | null | undefined,
): RehearsalAttemptStep[] {
  if (exact
    && exact.jobId === job.jobId
    && exact.itemIndex === entry.index
    && exact.historyComplete) {
    return exact.attempts.map((attempt) => ({
      attempt: attempt.attempt,
      state: attempt.state === 'RETRY_SCHEDULED' ? 'RETRY SCHEDULED' : attempt.state,
      observation: humanizeMachineCode(attempt.reasonCode)
        || humanizeMachineCode(attempt.outcome)
        || (attempt.state === 'RUNNING'
          ? 'The attempt is still running.'
          : 'The attempt reached a terminal observation.'),
      observedAt: attempt.observedAt || attempt.startedAt,
      exact: true,
    }));
  }
  const steps: RehearsalAttemptStep[] = [];
  for (let attempt = 1; attempt <= entry.attemptCount; attempt += 1) {
    const latest = attempt === entry.attemptCount;
    if (!latest) {
      steps.push({
        attempt,
        state: 'RETRY SCHEDULED',
        observation: 'Attempt consumed; the v1 projection does not retain its individual observation.',
        observedAt: '',
        exact: false,
      });
      continue;
    }
    steps.push({
      attempt,
      state: entry.status === 'RUNNING' ? 'RUNNING' : 'TERMINAL',
      observation: humanizeMachineCode(entry.failureCode) || outcomeReason(entry),
      observedAt: entry.completedAt || entry.startedAt || job.completedAt || job.updatedAt,
      exact: true,
    });
  }
  return steps;
}

function authorTargetLink(target: RehearsalAuthorTarget, currentHref = 'http://localhost/author/'): string {
  const url = new URL(currentHref, 'http://localhost');
  url.pathname = '/author/';
  url.search = '';
  url.searchParams.set('authorWorkspace', 'v2');
  url.searchParams.set('authorMode', 'evidence');
  url.searchParams.set('workspaceView', 'evidence');
  url.searchParams.set('target', target.kind === 'OPERATOR'
    ? `operator:${target.id}`
    : target.kind === 'FUNCTION'
      ? `function:${target.id}`
      : 'graph');
  if (target.draftId) url.searchParams.set('draftId', target.draftId);
  if (target.nodeId) url.searchParams.set('nodeId', target.nodeId);
  if (target.scenarioId) url.searchParams.set('scenarioId', target.scenarioId);
  if (target.runId) url.searchParams.set('runId', target.runId);
  return `${url.pathname}${url.search}`;
}

function headlineFor(category: string, status: string, failureCode: string): string {
  if (/TIMEOUT|DEADLINE/.test(failureCode)) return 'Dependency timed out';
  if (category === 'ASSERTIONS') return 'Business assertion failed';
  if (category === 'GOVERNANCE') return 'Governance decision required';
  if (category === 'WARNINGS') return 'Review warning';
  if (category === 'EVIDENCE') return 'Evidence is incomplete';
  if (category === 'PASSED') return 'Rehearsal passed';
  if (status === 'CANCELLED') return 'Rehearsal item cancelled';
  return 'Execution failed';
}

function impactFor(category: string, status: string): string {
  if (category === 'ASSERTIONS') {
    return 'The capability behavior does not meet its governed business expectation.';
  }
  if (category === 'GOVERNANCE') {
    return 'The batch cannot pass the release gate until the accountable owner decides.';
  }
  if (category === 'WARNINGS') {
    return 'Release remains possible only after an explicit risk decision.';
  }
  if (category === 'EVIDENCE') {
    return 'The observed execution cannot be used as auditable release evidence.';
  }
  if (category === 'PASSED') {
    return 'This item contributes trusted evidence to the batch decision.';
  }
  return status === 'CANCELLED'
    ? 'The intended business path was not evaluated.'
    : 'The dependent business path did not complete and may reduce service coverage.';
}

function ownerFor(category: string, job: ScenarioRehearsalBatchJob): string {
  const projectOwner = `${job.scope.projectId} owner`;
  if (category === 'EVIDENCE') return 'Evidence platform owner';
  if (category === 'GOVERNANCE') return projectOwner;
  return `${job.scope.projectId} rehearsal owner`;
}

function roleFor(category: string): string {
  if (category === 'EVIDENCE') return 'Evidence operator';
  if (category === 'GOVERNANCE') return 'Business owner';
  if (category === 'ASSERTIONS') return 'Scenario author';
  return 'Rehearsal operator';
}

function batchFallback(failureMode: string): string {
  const normalized = failureMode.trim().toUpperCase();
  if (normalized === 'CONTINUE' || normalized === 'COLLECT_ALL') {
    return 'Continue the batch and collect every item outcome.';
  }
  if (normalized === 'FAIL_FAST') return 'Stop the batch after the first terminal failure.';
  return normalized ? humanizeMachineCode(normalized) : 'No batch failure policy was advertised.';
}

function outcomeReason(entry: RehearsalEvidenceEntry): string {
  if (entry.status === 'PASSED') return 'The item completed successfully.';
  if (entry.status === 'RUNNING') return 'The latest attempt is still running.';
  if (entry.status === 'PENDING') return 'No attempt has started.';
  if (entry.status === 'INDETERMINATE') return 'The available evidence cannot establish an outcome.';
  if (entry.status === 'CANCELLED') return 'The item was cancelled before a trusted outcome was established.';
  return 'The item ended in failure.';
}

export function humanizeMachineCode(value: string): string {
  const normalized = value.trim();
  if (!normalized) return '';
  if (!/^[A-Z0-9_. -]+$/.test(normalized)) return normalized;
  const words = normalized
    .replace(/[._-]+/g, ' ')
    .toLowerCase();
  return `${words.charAt(0).toUpperCase()}${words.slice(1)}.`;
}
