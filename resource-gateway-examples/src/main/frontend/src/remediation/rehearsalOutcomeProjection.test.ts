import { describe, expect, it } from 'vitest';

import { projectRehearsalOutcome } from './rehearsalOutcomeProjection';

describe('projectRehearsalOutcome', () => {
  it('does not confuse 100% completion with correctness or gate readiness', () => {
    const view = projectRehearsalOutcome({
      totalItems: 10, completedItems: 10, passedItems: 7, failedItems: 2,
      indeterminateItems: 1, cancelledItems: 0,
    }, true, false);

    expect(view).toEqual({
      completion: { completed: 10, total: 10, percent: 100, label: '10/10 complete' },
      correctness: { passed: 7, evaluated: 10, passRate: 70, label: '70% (7/10)' },
      gate: { status: 'BLOCKED', label: 'Blocked', tone: 'danger' },
    });
  });

  it('shows an active batch as pending even when every observed case has passed', () => {
    const view = projectRehearsalOutcome({
      totalItems: 10, completedItems: 4, passedItems: 4, failedItems: 0,
      indeterminateItems: 0, cancelledItems: 0,
    }, false, null);

    expect(view.completion).toMatchObject({ percent: 40 });
    expect(view.correctness).toMatchObject({ passRate: 100, label: '100% (4/4)' });
    expect(view.gate.status).toBe('PENDING');
  });

  it('does not call a terminal job blocked before its signed workbook is loaded', () => {
    expect(projectRehearsalOutcome(undefined, true, null)).toMatchObject({
      correctness: { passRate: null, label: 'Not evaluated' },
      gate: { status: 'AWAITING_WORKBOOK', label: 'Awaiting workbook' },
    });
  });

  it('bounds corrupt counters instead of emitting impossible completion percentages', () => {
    const view = projectRehearsalOutcome({
      totalItems: 2, completedItems: 9, passedItems: -1, failedItems: 0,
      indeterminateItems: 0, cancelledItems: 0,
    }, false, null);

    expect(view.completion).toMatchObject({ completed: 2, percent: 100 });
    expect(view.correctness.passRate).toBeNull();
  });
});
