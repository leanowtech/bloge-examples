export const CORRECTNESS_TASK_EVENT_TYPE = 'bloge:correctness-task';

export type CorrectnessTaskStage = 'CONTRACT' | 'SCENARIO' | 'COMPATIBILITY' | 'EVIDENCE';
export type CorrectnessTaskScope = 'CASE' | 'SELECTION' | 'SUITE';
export type CorrectnessTaskEventName =
  | 'WORKSPACE_OPENED'
  | 'STAGE_VIEWED'
  | 'PREFLIGHT_EVALUATED'
  | 'RUN_REQUESTED'
  | 'RUN_COMPLETED'
  | 'COMMAND_REJECTED'
  | 'WORKSPACE_EXITED';

type CorrectnessTaskMetadataValue = string | number;
export type CorrectnessTaskMetadata = Record<
string,
CorrectnessTaskMetadataValue | undefined
>;

export interface CorrectnessTaskEvent {
  schema: 'bloge.correctnessTaskEvent.v1';
  name: CorrectnessTaskEventName;
  occurredAt: string;
  metadata: Record<string, CorrectnessTaskMetadataValue>;
}

const ALLOWED_METADATA: Record<CorrectnessTaskEventName, ReadonlySet<string>> = {
  WORKSPACE_OPENED: new Set(['stage', 'caseCount']),
  STAGE_VIEWED: new Set(['stage']),
  PREFLIGHT_EVALUATED: new Set([
    'stage', 'scope', 'preflightStatus', 'caseCount', 'realCount', 'mockedCount',
    'faultCount', 'blockerCount',
  ]),
  RUN_REQUESTED: new Set(['stage', 'scope', 'source', 'preflightStatus', 'caseCount']),
  RUN_COMPLETED: new Set([
    'stage', 'scope', 'source', 'runStatus', 'caseCount', 'failureCount', 'durationMs',
  ]),
  COMMAND_REJECTED: new Set([
    'stage', 'scope', 'rejectionReason', 'errorCode', 'caseCount', 'blockerCount',
  ]),
  WORKSPACE_EXITED: new Set(['stage', 'exitKind', 'durationMs']),
};

const ALLOWED_STRING_VALUES: Record<string, ReadonlySet<string>> = {
  stage: new Set(['CONTRACT', 'SCENARIO', 'COMPATIBILITY', 'EVIDENCE']),
  scope: new Set(['CASE', 'SELECTION', 'SUITE']),
  source: new Set(['LOCAL', 'SERVER']),
  preflightStatus: new Set(['SAFE', 'REVIEW', 'BLOCKED']),
  runStatus: new Set(['PASSED', 'FAILED', 'PARTIAL', 'CANCELLED']),
  rejectionReason: new Set([
    'PREFLIGHT_BLOCKED',
    'BASELINE_REQUIRED',
    'SELECTION_EMPTY',
    'CAPABILITY_UNAVAILABLE',
    'API_ERROR',
  ]),
  errorCode: new Set([
    'RG.CORRECTNESS.PREFLIGHT_BLOCKED',
    'RG.CORRECTNESS.BASELINE_REQUIRED',
    'RG.CORRECTNESS.SELECTION_EMPTY',
    'RG.CORRECTNESS.CAPABILITY_UNAVAILABLE',
    'RG.CORRECTNESS.API_ERROR',
  ]),
  exitKind: new Set(['CLOSED', 'UNMOUNTED']),
};

const FORBIDDEN_KEY_SEGMENTS = new Set([
  'id', 'ref', 'path', 'message', 'context', 'fixture', 'payload', 'schema', 'dsl', 'config',
  'input', 'output', 'secret', 'token', 'credential',
]);
const MAX_COUNT = 1_000_000;
const MAX_DURATION_MS = 86_400_000;

/** Creates a bounded event whose metadata cannot become a business-data side channel. */
export function createCorrectnessTaskEvent(
  name: CorrectnessTaskEventName,
  metadata: CorrectnessTaskMetadata = {},
  now: Date = new Date(),
): CorrectnessTaskEvent {
  const allowed = ALLOWED_METADATA[name];
  const entries = Object.entries(metadata)
    .filter((entry): entry is [string, CorrectnessTaskMetadataValue] => entry[1] !== undefined);
  for (const [key, value] of entries) {
    if (!allowed.has(key) || forbiddenMetadataKey(key)) {
      throw new Error(`Correctness telemetry metadata "${key}" is not allowed for ${name}.`);
    }
    if (typeof value === 'string' && !ALLOWED_STRING_VALUES[key]?.has(value)) {
      throw new Error(`Correctness telemetry metadata "${key}" has an unsupported enum value.`);
    }
    if (typeof value === 'number') validateNumber(key, value);
  }
  return {
    schema: 'bloge.correctnessTaskEvent.v1',
    name,
    occurredAt: now.toISOString(),
    metadata: Object.fromEntries(entries),
  };
}

/** Emits to the host boundary and drops invalid instrumentation without interrupting authoring. */
export function recordCorrectnessTaskEvent(
  name: CorrectnessTaskEventName,
  metadata: CorrectnessTaskMetadata = {},
  target: EventTarget | undefined = typeof window === 'undefined' ? undefined : window,
): CorrectnessTaskEvent | null {
  try {
    const event = createCorrectnessTaskEvent(name, metadata);
    target?.dispatchEvent(new CustomEvent<CorrectnessTaskEvent>(CORRECTNESS_TASK_EVENT_TYPE, {
      detail: event,
    }));
    return event;
  } catch {
    return null;
  }
}

export function correctnessTaskElapsedMs(
  startedAt: number,
  now: number = performance.now(),
): number {
  return Math.min(MAX_DURATION_MS, Math.max(0, Math.round(now - startedAt)));
}

function validateNumber(key: string, value: number): void {
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new Error(`Correctness telemetry metadata "${key}" must be a non-negative safe integer.`);
  }
  const maximum = key === 'durationMs' ? MAX_DURATION_MS : MAX_COUNT;
  if (value > maximum) {
    throw new Error(`Correctness telemetry metadata "${key}" exceeds its bounded maximum.`);
  }
}

function forbiddenMetadataKey(key: string): boolean {
  const segments = key
    .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
    .toLocaleLowerCase()
    .split(/[^a-z0-9]+/)
    .filter(Boolean);
  return segments.some((segment) => FORBIDDEN_KEY_SEGMENTS.has(segment));
}
