export const RESPONSIVE_HOST_WIDTHS = [390, 820, 1024, 1280, 1440] as const;

export interface ChromeBudget {
  taskHeaderMaxPx: number;
  localPreludeMaxPx: number;
  stickyCommandMaxPx: number;
  minimumTaskContentPx: number;
}

export const MOBILE_MATRIX_CHROME_BUDGET: ChromeBudget = {
  taskHeaderMaxPx: 380,
  localPreludeMaxPx: 190,
  stickyCommandMaxPx: 52,
  minimumTaskContentPx: 186,
};

export const DESKTOP_TASK_CHROME_BUDGET: ChromeBudget = {
  taskHeaderMaxPx: 208,
  localPreludeMaxPx: 142,
  stickyCommandMaxPx: 52,
  minimumTaskContentPx: 220,
};

export interface ChromeMeasurement {
  taskHeaderPx: number;
  localPreludePx: number;
  stickyCommandPx: number;
  taskContentPx: number;
}

export function chromeBudgetViolations(
  measurement: ChromeMeasurement,
  budget: ChromeBudget,
): string[] {
  const violations: string[] = [];
  if (measurement.taskHeaderPx > budget.taskHeaderMaxPx) violations.push('TASK_HEADER');
  if (measurement.localPreludePx > budget.localPreludeMaxPx) violations.push('LOCAL_PRELUDE');
  if (measurement.stickyCommandPx > budget.stickyCommandMaxPx) violations.push('STICKY_COMMAND');
  if (measurement.taskContentPx < budget.minimumTaskContentPx) violations.push('TASK_CONTENT');
  return violations;
}
