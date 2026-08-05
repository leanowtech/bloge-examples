import type { TranslationValues } from '../../i18n/i18n';
import type { AuthorMode } from '../shell/authorWorkspaceState';

export type DraftLifecycle = 'EPHEMERAL' | 'SAVED' | 'DIRTY' | 'CONFLICTED';
export type ExecutionLifecycle =
  | 'NOT_RUN'
  | 'RUNNING'
  | 'PASSED'
  | 'PASSED_WITH_WARNINGS'
  | 'FAILED'
  | 'STALE';
export type AssertionLifecycle =
  | 'NOT_CONFIGURED'
  | 'NOT_RUN'
  | 'RUNNING'
  | 'PASSED'
  | 'FAILED'
  | 'STALE';
export type ContractLifecycle = 'NOT_CHECKED' | 'CHECKING' | 'VALID' | 'BLOCKED' | 'STALE';
export type GovernanceLifecycle =
  | 'NOT_CHECKED'
  | 'CHECKING'
  | 'APPROVED'
  | 'REVIEW_REQUIRED'
  | 'BLOCKED'
  | 'STALE';
export type PromotionLifecycle = 'NOT_EVALUATED' | 'BLOCKED' | 'REVIEW_REQUIRED' | 'READY';

export interface AuthorWarningWaiver {
  owner: string;
  reason: string;
  scope: string;
  expiresAt: string;
}

export interface AuthorReadinessInput {
  draft: {
    durable: boolean;
    current: boolean;
    conflicted: boolean;
  };
  execution: {
    busy: boolean;
    evaluated: boolean;
    passed: boolean;
    warnings: boolean;
    stale: boolean;
  };
  assertions: {
    configured: boolean;
    busy: boolean;
    evaluated: boolean;
    passed: boolean;
    stale: boolean;
  };
  contract: {
    busy: boolean;
    evaluated: boolean;
    passed: boolean;
    stale: boolean;
  };
  governance: {
    busy: boolean;
    evaluated: boolean;
    status: string;
    stale: boolean;
  };
  warningWaiver?: AuthorWarningWaiver | null;
  now?: string;
}

export interface AuthorReadinessReason {
  code: string;
  dimension: 'DRAFT' | 'EXECUTION' | 'ASSERTIONS' | 'CONTRACT' | 'GOVERNANCE';
  message: string;
  action: {
    label: string;
    mode: AuthorMode;
  };
}

export interface AuthorReadinessVerdict {
  draft: DraftLifecycle;
  execution: ExecutionLifecycle;
  assertions: AssertionLifecycle;
  contract: ContractLifecycle;
  governance: GovernanceLifecycle;
  promotion: PromotionLifecycle;
  headline: string;
  summary: string;
  summaryValues?: TranslationValues;
  reasons: AuthorReadinessReason[];
  nextAction: AuthorReadinessReason['action'];
  waiver: 'NOT_REQUIRED' | 'MISSING' | 'INVALID' | 'ACTIVE';
}

/**
 * Projects the complete authoring lifecycle without mutating draft, run, or governance assets.
 *
 * Promotion is a fail-closed conjunction. Stale evidence never inherits a previous green status,
 * and a warning waiver is usable only when owner, reason, scope, and a future expiry are present.
 */
export function projectAuthorReadiness(input: AuthorReadinessInput): AuthorReadinessVerdict {
  const draft = draftLifecycle(input);
  const execution = executionLifecycle(input);
  const assertions = assertionLifecycle(input);
  const contract = contractLifecycle(input);
  const governance = governanceLifecycle(input);
  const warningPresent = execution === 'PASSED_WITH_WARNINGS'
    || governance === 'REVIEW_REQUIRED';
  const waiver = warningPresent
    ? warningWaiverState(input.warningWaiver, input.now)
    : 'NOT_REQUIRED';
  const reasons = readinessReasons({ draft, execution, assertions, contract, governance });
  const hardBlock = reasons.some((reason) => (
    /CONFLICTED|DIRTY|STALE|FAILED|BLOCKED/.test(reason.code)
  ));
  const incomplete = reasons.some((reason) => (
    /EPHEMERAL|NOT_RUN|NOT_CONFIGURED|NOT_CHECKED|RUNNING|CHECKING/.test(reason.code)
  ));
  const unresolvedWarning = warningPresent && waiver !== 'ACTIVE';
  const promotion: PromotionLifecycle = hardBlock
    ? 'BLOCKED'
    : incomplete
      ? 'NOT_EVALUATED'
      : unresolvedWarning
        ? 'REVIEW_REQUIRED'
        : 'READY';
  const nextAction = promotion === 'READY'
    ? { label: 'Review promotion evidence', mode: 'evidence' as const }
    : reasons[0]?.action ?? (
      unresolvedWarning
        ? { label: 'Record warning decision', mode: 'evidence' as const }
        : { label: 'Review promotion evidence', mode: 'evidence' as const }
    );
  const copy = promotionCopy(promotion, reasons.length, unresolvedWarning, waiver);
  return {
    draft,
    execution,
    assertions,
    contract,
    governance,
    promotion,
    ...copy,
    reasons,
    nextAction,
    waiver,
  };
}

function draftLifecycle(input: AuthorReadinessInput): DraftLifecycle {
  if (input.draft.conflicted) return 'CONFLICTED';
  if (!input.draft.durable) return 'EPHEMERAL';
  return input.draft.current ? 'SAVED' : 'DIRTY';
}

function executionLifecycle(input: AuthorReadinessInput): ExecutionLifecycle {
  if (input.execution.busy) return 'RUNNING';
  if (!input.execution.evaluated) return 'NOT_RUN';
  if (input.execution.stale) return 'STALE';
  if (!input.execution.passed) return 'FAILED';
  return input.execution.warnings ? 'PASSED_WITH_WARNINGS' : 'PASSED';
}

function assertionLifecycle(input: AuthorReadinessInput): AssertionLifecycle {
  if (!input.assertions.configured) return 'NOT_CONFIGURED';
  if (input.assertions.busy) return 'RUNNING';
  if (!input.assertions.evaluated) return 'NOT_RUN';
  if (input.assertions.stale) return 'STALE';
  return input.assertions.passed ? 'PASSED' : 'FAILED';
}

function contractLifecycle(input: AuthorReadinessInput): ContractLifecycle {
  if (input.contract.busy) return 'CHECKING';
  if (!input.contract.evaluated) return 'NOT_CHECKED';
  if (input.contract.stale) return 'STALE';
  return input.contract.passed ? 'VALID' : 'BLOCKED';
}

function governanceLifecycle(input: AuthorReadinessInput): GovernanceLifecycle {
  if (input.governance.busy) return 'CHECKING';
  if (!input.governance.evaluated) return 'NOT_CHECKED';
  if (input.governance.stale) return 'STALE';
  const status = input.governance.status.trim().toUpperCase();
  if (/(BLOCK|DENY|REJECT|FAIL|INVALID|ERROR)/.test(status)) return 'BLOCKED';
  if (/(WARN|REVIEW|PARTIAL|CONDITIONAL)/.test(status)) return 'REVIEW_REQUIRED';
  if (/(PASS|APPROV|ALLOW|READY|SUCCESS)/.test(status)) return 'APPROVED';
  return 'REVIEW_REQUIRED';
}

function warningWaiverState(
  waiver: AuthorWarningWaiver | null | undefined,
  now: string | undefined,
): AuthorReadinessVerdict['waiver'] {
  if (!waiver) return 'MISSING';
  const expiry = Date.parse(waiver.expiresAt);
  const observedAt = now ? Date.parse(now) : Date.now();
  if (!waiver.owner.trim()
    || !waiver.reason.trim()
    || !waiver.scope.trim()
    || Number.isNaN(expiry)
    || Number.isNaN(observedAt)
    || expiry <= observedAt) {
    return 'INVALID';
  }
  return 'ACTIVE';
}

function readinessReasons(
  dimensions: Pick<
  AuthorReadinessVerdict,
  'draft' | 'execution' | 'assertions' | 'contract' | 'governance'
  >,
): AuthorReadinessReason[] {
  const candidates: AuthorReadinessReason[] = [];
  if (dimensions.draft !== 'SAVED') {
    candidates.push(reason(
      `DRAFT_${dimensions.draft}`,
      'DRAFT',
      dimensions.draft === 'EPHEMERAL'
        ? 'Save an immutable draft revision before treating evidence as durable.'
        : dimensions.draft === 'DIRTY'
          ? 'The current graph differs from its saved revision.'
          : 'Resolve the concurrent draft save conflict without overwriting either revision.',
      dimensions.draft === 'CONFLICTED' ? 'Resolve save conflict' : 'Save current draft',
      'contract',
    ));
  }
  if (dimensions.execution !== 'PASSED' && dimensions.execution !== 'PASSED_WITH_WARNINGS') {
    candidates.push(reason(
      `EXECUTION_${dimensions.execution}`,
      'EXECUTION',
      dimensions.execution === 'STALE'
        ? 'The retained run targets an older authoring snapshot.'
        : 'Run the current Scenario and inspect any runtime failure.',
      dimensions.execution === 'FAILED' ? 'Review execution failure' : 'Run current Scenario',
      dimensions.execution === 'FAILED' ? 'evidence' : 'scenarios',
    ));
  }
  if (dimensions.assertions !== 'PASSED') {
    candidates.push(reason(
      `ASSERTIONS_${dimensions.assertions}`,
      'ASSERTIONS',
      dimensions.assertions === 'STALE'
        ? 'The retained assertion result targets an older Scenario or draft snapshot.'
        : 'Add and run business assertions for the current Scenario.',
      dimensions.assertions === 'FAILED' ? 'Repair failed assertions' : 'Open Scenarios',
      'scenarios',
    ));
  }
  if (dimensions.contract !== 'VALID') {
    candidates.push(reason(
      `CONTRACT_${dimensions.contract}`,
      'CONTRACT',
      dimensions.contract === 'STALE'
        ? 'Contract validation targets an older authoring snapshot.'
        : 'Validate the exact current Graph Contract.',
      'Validate current Contract',
      'contract',
    ));
  }
  if (dimensions.governance !== 'APPROVED') {
    candidates.push(reason(
      `GOVERNANCE_${dimensions.governance}`,
      'GOVERNANCE',
      dimensions.governance === 'STALE'
        ? 'The governance decision does not target the current saved revision.'
        : 'Obtain or review a governance decision for the exact current revision.',
      dimensions.governance === 'BLOCKED' ? 'Review governance blockers' : 'Check governance',
      'evidence',
    ));
  }
  return candidates.sort((left, right) => reasonRank(left.code) - reasonRank(right.code));
}

function reason(
  code: string,
  dimension: AuthorReadinessReason['dimension'],
  message: string,
  label: string,
  mode: AuthorMode,
): AuthorReadinessReason {
  return { code, dimension, message, action: { label, mode } };
}

function reasonRank(code: string): number {
  if (/CONFLICTED|FAILED|BLOCKED/.test(code)) return 0;
  if (/STALE|DIRTY/.test(code)) return 1;
  if (/EPHEMERAL/.test(code)) return 2;
  if (/NOT_RUN|NOT_CONFIGURED|NOT_CHECKED/.test(code)) return 3;
  if (/RUNNING|CHECKING/.test(code)) return 4;
  return 5;
}

function promotionCopy(
  promotion: PromotionLifecycle,
  reasonCount: number,
  unresolvedWarning: boolean,
  waiver: AuthorReadinessVerdict['waiver'],
): Pick<AuthorReadinessVerdict, 'headline' | 'summary' | 'summaryValues'> {
  if (promotion === 'READY') {
    return {
      headline: 'Ready for promotion',
      summary: waiver === 'ACTIVE'
        ? 'Every blocking dimension passed; remaining warnings have an active scoped waiver.'
        : 'The exact saved revision satisfies every readiness dimension.',
    };
  }
  if (promotion === 'BLOCKED') {
    return {
      headline: 'Promotion blocked',
      summary: '{count} lifecycle conditions require repair.',
      summaryValues: { count: reasonCount },
    };
  }
  if (promotion === 'REVIEW_REQUIRED' || unresolvedWarning) {
    return {
      headline: 'Review required',
      summary: 'A scoped owner decision with reason and expiry is required for remaining warnings.',
    };
  }
  return {
    headline: 'Evidence incomplete',
    summary: 'Complete the next lifecycle action before promotion can be evaluated.',
  };
}
