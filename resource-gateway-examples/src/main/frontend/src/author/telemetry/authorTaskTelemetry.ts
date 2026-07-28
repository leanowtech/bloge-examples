export const AUTHOR_TASK_EVENT_TYPE = 'bloge:author-task';

export type AuthorTaskEventName =
  | 'WORKSPACE_OPENED'
  | 'START_CHOICE_SELECTED'
  | 'EXAMPLE_LOADED'
  | 'MODE_CHANGED'
  | 'AUTO_LAYOUT_COMPLETED'
  | 'AUTO_LAYOUT_UNDONE'
  | 'RUN_STARTED'
  | 'RUN_COMPLETED'
  | 'FIRST_SUCCESS';

type AuthorTaskMetadataValue = string | number | boolean;
export type AuthorTaskMetadata = Record<string, AuthorTaskMetadataValue | undefined>;

export interface AuthorTaskEvent {
  schema: 'bloge.authorTaskEvent.v1';
  name: AuthorTaskEventName;
  occurredAt: string;
  metadata: Record<string, AuthorTaskMetadataValue>;
}

const ALLOWED_METADATA: Record<AuthorTaskEventName, ReadonlySet<string>> = {
  WORKSPACE_OPENED: new Set(['workspaceVersion', 'nodeCount']),
  START_CHOICE_SELECTED: new Set(['choice']),
  EXAMPLE_LOADED: new Set(['source', 'nodeCount', 'edgeCount', 'scenarioCount']),
  MODE_CHANGED: new Set(['previousMode', 'nextMode']),
  AUTO_LAYOUT_COMPLETED: new Set(['nodeCount', 'edgeCount', 'movedNodeCount', 'durationMs']),
  AUTO_LAYOUT_UNDONE: new Set(['movedNodeCount']),
  RUN_STARTED: new Set(['runKind', 'nodeCount', 'caseCount']),
  RUN_COMPLETED: new Set(['runKind', 'status', 'caseCount', 'durationMs']),
  FIRST_SUCCESS: new Set(['elapsedMs', 'runKind']),
};

const ALLOWED_STRING_VALUES: Record<string, ReadonlySet<string>> = {
  workspaceVersion: new Set(['v1', 'v2']),
  choice: new Set(['examples', 'library', 'dsl', 'blank']),
  source: new Set(['built-in']),
  previousMode: new Set(['compose', 'contract', 'test', 'review']),
  nextMode: new Set(['compose', 'contract', 'test', 'review']),
  runKind: new Set(['graph', 'table', 'scenario', 'operator']),
  status: new Set(['PASSED', 'FAILED', 'CANCELLED']),
};

const FORBIDDEN_KEY = /(context|fixture|payload|schema|dsl|config|input|output|secret|token|credential)/i;

/** Builds the host-facing event and rejects metadata that could become a business-payload channel. */
export function createAuthorTaskEvent(
  name: AuthorTaskEventName,
  metadata: AuthorTaskMetadata = {},
  now: Date = new Date(),
): AuthorTaskEvent {
  const allowed = ALLOWED_METADATA[name];
  const entries = Object.entries(metadata)
    .filter((entry): entry is [string, AuthorTaskMetadataValue] => entry[1] !== undefined);
  for (const [key, value] of entries) {
    if (!allowed.has(key) || FORBIDDEN_KEY.test(key)) {
      throw new Error(`Author task telemetry metadata "${key}" is not allowed for ${name}.`);
    }
    if (typeof value === 'number' && (!Number.isFinite(value) || value < 0)) {
      throw new Error(`Author task telemetry metadata "${key}" must be a finite non-negative number.`);
    }
    if (typeof value === 'string' && value.length > 64) {
      throw new Error(`Author task telemetry metadata "${key}" exceeds 64 characters.`);
    }
    if (typeof value === 'string' && !ALLOWED_STRING_VALUES[key]?.has(value)) {
      throw new Error(`Author task telemetry metadata "${key}" has an unsupported enum value.`);
    }
    if (typeof value === 'boolean') {
      throw new Error(`Author task telemetry metadata "${key}" does not accept boolean values.`);
    }
  }
  return {
    schema: 'bloge.authorTaskEvent.v1',
    name,
    occurredAt: now.toISOString(),
    metadata: Object.fromEntries(entries),
  };
}

/**
 * Emits a payload-free browser event for a host shell or VS Code webview bridge to consume.
 * Invalid instrumentation is dropped so telemetry can never break authoring.
 */
export function recordAuthorTaskEvent(
  name: AuthorTaskEventName,
  metadata: AuthorTaskMetadata = {},
  target: EventTarget | undefined = typeof window === 'undefined' ? undefined : window,
): AuthorTaskEvent | null {
  try {
    const event = createAuthorTaskEvent(name, metadata);
    target?.dispatchEvent(new CustomEvent<AuthorTaskEvent>(AUTHOR_TASK_EVENT_TYPE, {
      detail: event,
    }));
    return event;
  } catch {
    return null;
  }
}

export function authorTaskElapsedMs(startedAt: number, now: number = performance.now()): number {
  return Math.max(0, Math.round(now - startedAt));
}
