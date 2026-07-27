import type { ScenarioRehearsalBatchJob } from './types';

export const TERMINAL_REHEARSAL_STATUSES = [
  'SUCCEEDED',
  'PARTIAL',
  'FAILED',
  'CANCELLED',
  'EXPIRED',
  'QUARANTINED',
] as const satisfies readonly ScenarioRehearsalBatchJob['status'][];

export type TerminalRehearsalJobStatus =
  (typeof TERMINAL_REHEARSAL_STATUSES)[number];

export type LiveRehearsalJobStatus = Exclude<
  ScenarioRehearsalBatchJob['status'],
  TerminalRehearsalJobStatus
>;

const TERMINAL_STATUS_SET = new Set<ScenarioRehearsalBatchJob['status']>(
  TERMINAL_REHEARSAL_STATUSES,
);

export function isTerminalRehearsalStatus(
  status: ScenarioRehearsalBatchJob['status'],
): status is TerminalRehearsalJobStatus {
  return TERMINAL_STATUS_SET.has(status);
}
