import { describe, expect, it } from 'vitest';

import {
  chromeBudgetViolations,
  MOBILE_MATRIX_CHROME_BUDGET,
  RESPONSIVE_HOST_WIDTHS,
} from './chromeBudget';

describe('chromeBudget', () => {
  it('defines the required enterprise host-width matrix', () => {
    expect(RESPONSIVE_HOST_WIDTHS).toEqual([390, 820, 1024, 1280, 1440]);
  });

  it('fails each chrome zone independently instead of hiding content loss in one total', () => {
    expect(chromeBudgetViolations({
      taskHeaderPx: 381,
      localPreludePx: 191,
      stickyCommandPx: 53,
      taskContentPx: 185,
    }, MOBILE_MATRIX_CHROME_BUDGET)).toEqual([
      'TASK_HEADER', 'LOCAL_PRELUDE', 'STICKY_COMMAND', 'TASK_CONTENT',
    ]);
  });

  it('accepts a mobile Matrix with room for three 62px summaries', () => {
    expect(chromeBudgetViolations({
      taskHeaderPx: 360,
      localPreludePx: 176,
      stickyCommandPx: 52,
      taskContentPx: 186,
    }, MOBILE_MATRIX_CHROME_BUDGET)).toEqual([]);
  });
});
