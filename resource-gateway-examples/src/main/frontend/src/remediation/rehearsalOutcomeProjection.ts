import type { ScenarioRehearsalBatchSummary } from '../types';

export interface RehearsalOutcomeProjection {
  completion: {
    completed: number;
    total: number;
    percent: number;
    label: string;
  };
  correctness: {
    passed: number;
    evaluated: number;
    passRate: number | null;
    label: string;
  };
  gate: {
    status: 'PENDING' | 'AWAITING_WORKBOOK' | 'READY' | 'BLOCKED';
    label: string;
    tone: 'neutral' | 'success' | 'danger';
  };
}

/** Separates execution completion, observed correctness, and publish-gate eligibility. */
export function projectRehearsalOutcome(
  summary: ScenarioRehearsalBatchSummary | null | undefined,
  terminal: boolean,
  gateReady: boolean | null,
): RehearsalOutcomeProjection {
  const total = nonNegative(summary?.totalItems);
  const completed = Math.min(total, nonNegative(summary?.completedItems));
  const passed = nonNegative(summary?.passedItems);
  const failed = nonNegative(summary?.failedItems);
  const indeterminate = nonNegative(summary?.indeterminateItems);
  const evaluated = passed + failed + indeterminate;
  const completionPercent = total > 0 ? Math.round(completed / total * 100) : 0;
  const passRate = evaluated > 0 ? Math.round(passed / evaluated * 100) : null;

  return {
    completion: {
      completed,
      total,
      percent: completionPercent,
      label: `${completed}/${total} complete`,
    },
    correctness: {
      passed,
      evaluated,
      passRate,
      label: passRate === null ? 'Not evaluated' : `${passRate}% (${passed}/${evaluated})`,
    },
    gate: !terminal
      ? { status: 'PENDING', label: 'Pending', tone: 'neutral' }
      : gateReady === null
        ? { status: 'AWAITING_WORKBOOK', label: 'Awaiting workbook', tone: 'neutral' }
        : gateReady
          ? { status: 'READY', label: 'Ready', tone: 'success' }
          : { status: 'BLOCKED', label: 'Blocked', tone: 'danger' },
  };
}

function nonNegative(value: number | null | undefined): number {
  return Number.isFinite(value) ? Math.max(0, Math.floor(value ?? 0)) : 0;
}
