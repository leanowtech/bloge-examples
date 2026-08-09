export type TaskSurface = 'COMPOSE' | 'CONTRACT' | 'SCENARIO' | 'EVIDENCE' | 'LIBRARY';
export type TaskSubjectKind = 'GRAPH' | 'NODE' | 'OPERATOR' | 'FUNCTION' | 'CASE' | 'RUN' | 'LIBRARY';
export type TaskRole = 'OWNER' | 'EDITOR' | 'REVIEWER' | 'VIEWER';

export interface TaskSelectionCoordinate {
  nodeId: string;
  caseId: string;
  runId: string;
}

/** Stable cross-surface coordinate. Evidence fingerprints deliberately remain in their own model. */
export interface TaskCoordinate {
  tenantId: string;
  namespace: string;
  environment: string;
  draftId: string;
  revision: number;
  surface: TaskSurface;
  subjectKind: TaskSubjectKind;
  subjectRef: string;
  selectionFingerprint: string;
  role: TaskRole;
  capabilityFingerprint: string;
  selection: TaskSelectionCoordinate;
}

export interface TaskReturnCoordinate {
  href: string;
  coordinate: TaskCoordinate;
  scrollX: number;
  scrollY: number;
  focusId: string;
}

export interface TaskViewportRestore {
  scrollX: number;
  scrollY: number;
  focusId: string;
  cleanHref: string;
}

export type TaskCoordinateDefaults = Partial<Omit<TaskCoordinate, 'selection'>> & {
  selection?: Partial<TaskSelectionCoordinate>;
};

const TASK_SURFACES = new Set<TaskSurface>([
  'COMPOSE', 'CONTRACT', 'SCENARIO', 'EVIDENCE', 'LIBRARY',
]);
const TASK_SUBJECT_KINDS = new Set<TaskSubjectKind>([
  'GRAPH', 'NODE', 'OPERATOR', 'FUNCTION', 'CASE', 'RUN', 'LIBRARY',
]);
const TASK_ROLES = new Set<TaskRole>(['OWNER', 'EDITOR', 'REVIEWER', 'VIEWER']);
const RETURN_COORDINATE_KEY = 'returnCoordinate';
const RESTORE_SCROLL_X_KEY = 'restoreScrollX';
const RESTORE_SCROLL_Y_KEY = 'restoreScrollY';
const RESTORE_FOCUS_KEY = 'restoreFocusId';
const MAX_RETURN_COORDINATE_LENGTH = 8_192;

export function parseTaskCoordinate(
  href: string,
  defaults: TaskCoordinateDefaults = {},
): TaskCoordinate {
  const url = new URL(href, 'http://localhost');
  const selection: TaskSelectionCoordinate = {
    nodeId: bounded(url.searchParams.get('nodeId')) || bounded(defaults.selection?.nodeId),
    caseId: bounded(url.searchParams.get('scenarioId') || url.searchParams.get('caseId'))
      || bounded(defaults.selection?.caseId),
    runId: bounded(url.searchParams.get('runId')) || bounded(defaults.selection?.runId),
  };
  const surface = enumValue(
    url.searchParams.get('surface'),
    TASK_SURFACES,
  ) ?? inferSurface(url, defaults.surface);
  const explicitKind = enumValue(url.searchParams.get('subjectKind'), TASK_SUBJECT_KINDS);
  const explicitRef = bounded(url.searchParams.get('subjectRef'));
  const inferredSubject = inferSubject(url, surface, selection, defaults);

  return {
    tenantId: bounded(url.searchParams.get('tenantId')) || bounded(defaults.tenantId) || 'tenant-a',
    namespace: bounded(url.searchParams.get('namespace')) || bounded(defaults.namespace) || 'local',
    environment: bounded(url.searchParams.get('environment'))
      || bounded(defaults.environment) || 'test',
    draftId: bounded(url.searchParams.get('draftId')) || bounded(defaults.draftId),
    revision: positiveInteger(url.searchParams.get('revision')) || positiveInteger(defaults.revision),
    surface,
    subjectKind: explicitKind && explicitRef ? explicitKind : inferredSubject.kind,
    subjectRef: explicitKind && explicitRef ? explicitRef : inferredSubject.ref,
    selectionFingerprint: bounded(url.searchParams.get('selectionFingerprint'))
      || bounded(defaults.selectionFingerprint),
    role: enumValue(url.searchParams.get('role'), TASK_ROLES) ?? defaults.role ?? 'EDITOR',
    capabilityFingerprint: bounded(url.searchParams.get('capabilityFingerprint'))
      || bounded(defaults.capabilityFingerprint) || 'unadvertised',
    selection,
  };
}

/** Writes canonical task fields while retaining unrelated integration and locale parameters. */
export function taskCoordinateUrl(href: string, coordinate: TaskCoordinate): string {
  const url = new URL(href, 'http://localhost');
  setText(url, 'tenantId', coordinate.tenantId);
  setText(url, 'namespace', coordinate.namespace);
  setText(url, 'environment', coordinate.environment);
  setText(url, 'draftId', coordinate.draftId);
  setPositiveInteger(url, 'revision', coordinate.revision);
  setText(url, 'surface', coordinate.surface);
  setText(url, 'subjectKind', coordinate.subjectKind);
  setText(url, 'subjectRef', coordinate.subjectRef);
  setText(url, 'selectionFingerprint', coordinate.selectionFingerprint);
  setText(url, 'role', coordinate.role);
  setText(url, 'capabilityFingerprint', coordinate.capabilityFingerprint);
  setText(url, 'nodeId', coordinate.selection.nodeId);
  setText(url, 'scenarioId', coordinate.selection.caseId);
  setText(url, 'runId', coordinate.selection.runId);
  return relativeHref(url);
}

export function createTaskReturnCoordinate(
  href: string,
  coordinate: TaskCoordinate,
  viewport: { scrollX?: number; scrollY?: number; focusId?: string } = {},
): TaskReturnCoordinate {
  const url = new URL(href, 'http://localhost');
  return {
    href: relativeHref(url),
    coordinate,
    scrollX: boundedNumber(viewport.scrollX),
    scrollY: boundedNumber(viewport.scrollY),
    focusId: bounded(viewport.focusId, 256),
  };
}

/** Adds one bounded, same-origin return coordinate to a cross-surface deep link. */
export function withTaskReturnCoordinate(
  targetHref: string,
  returnCoordinate: TaskReturnCoordinate,
): string {
  const url = new URL(targetHref, 'http://localhost');
  const serialized = JSON.stringify(returnCoordinate);
  if (serialized.length > MAX_RETURN_COORDINATE_LENGTH) {
    throw new Error('Task return coordinate exceeds the URL budget.');
  }
  url.searchParams.set(RETURN_COORDINATE_KEY, serialized);
  return relativeHref(url);
}

/** Fails closed for malformed, oversized, or cross-origin return payloads. */
export function parseTaskReturnCoordinate(href: string): TaskReturnCoordinate | null {
  const url = new URL(href, 'http://localhost');
  const raw = url.searchParams.get(RETURN_COORDINATE_KEY) ?? '';
  if (!raw || raw.length > MAX_RETURN_COORDINATE_LENGTH) return null;
  try {
    const candidate = JSON.parse(raw) as Partial<TaskReturnCoordinate>;
    if (!candidate || typeof candidate !== 'object' || typeof candidate.href !== 'string') return null;
    const returnUrl = new URL(candidate.href, 'http://localhost');
    if (returnUrl.origin !== 'http://localhost' || !candidate.href.startsWith('/')) return null;
    const coordinate = parseTaskCoordinate(
      taskCoordinateUrl(returnUrl.href, candidate.coordinate as TaskCoordinate),
    );
    return {
      href: relativeHref(returnUrl),
      coordinate,
      scrollX: boundedNumber(candidate.scrollX),
      scrollY: boundedNumber(candidate.scrollY),
      focusId: bounded(candidate.focusId, 256),
    };
  } catch {
    return null;
  }
}

/** Produces the exact return href plus one-shot viewport restoration coordinates. */
export function taskReturnHref(returnCoordinate: TaskReturnCoordinate): string {
  const url = new URL(returnCoordinate.href, 'http://localhost');
  setNonNegativeInteger(url, RESTORE_SCROLL_X_KEY, returnCoordinate.scrollX);
  setNonNegativeInteger(url, RESTORE_SCROLL_Y_KEY, returnCoordinate.scrollY);
  setText(url, RESTORE_FOCUS_KEY, returnCoordinate.focusId);
  return relativeHref(url);
}

/** Reads and removes one-shot viewport restoration fields from a same-origin task URL. */
export function parseTaskViewportRestore(href: string): TaskViewportRestore | null {
  const url = new URL(href, 'http://localhost');
  const hasRestore = [RESTORE_SCROLL_X_KEY, RESTORE_SCROLL_Y_KEY, RESTORE_FOCUS_KEY]
    .some((key) => url.searchParams.has(key));
  if (!hasRestore) return null;
  const restore = {
    scrollX: nonNegativeInteger(url.searchParams.get(RESTORE_SCROLL_X_KEY)),
    scrollY: nonNegativeInteger(url.searchParams.get(RESTORE_SCROLL_Y_KEY)),
    focusId: bounded(url.searchParams.get(RESTORE_FOCUS_KEY), 256),
  };
  url.searchParams.delete(RESTORE_SCROLL_X_KEY);
  url.searchParams.delete(RESTORE_SCROLL_Y_KEY);
  url.searchParams.delete(RESTORE_FOCUS_KEY);
  return { ...restore, cleanHref: relativeHref(url) };
}

export function taskCoordinateFingerprintMaterial(coordinate: TaskCoordinate): string {
  return JSON.stringify({
    tenantId: coordinate.tenantId,
    namespace: coordinate.namespace,
    environment: coordinate.environment,
    draftId: coordinate.draftId,
    revision: coordinate.revision,
    surface: coordinate.surface,
    subjectKind: coordinate.subjectKind,
    subjectRef: coordinate.subjectRef,
    role: coordinate.role,
    capabilityFingerprint: coordinate.capabilityFingerprint,
    selection: coordinate.selection,
  });
}

function inferSurface(url: URL, fallback?: TaskSurface): TaskSurface {
  if (url.pathname.startsWith('/libraries')) return 'LIBRARY';
  if (url.pathname.startsWith('/rehearsals')) return 'EVIDENCE';
  const mode = (url.searchParams.get('authorMode') ?? '').trim().toLowerCase();
  const view = (url.searchParams.get('workspaceView') ?? '').trim().toLowerCase();
  if (mode === 'contract' || view === 'interface' || view === 'compatibility') return 'CONTRACT';
  if (mode === 'scenarios' || mode === 'test' || view === 'scenarios') return 'SCENARIO';
  if (mode === 'evidence' || mode === 'review' || view === 'evidence') return 'EVIDENCE';
  return fallback ?? 'COMPOSE';
}

function inferSubject(
  url: URL,
  surface: TaskSurface,
  selection: TaskSelectionCoordinate,
  defaults: TaskCoordinateDefaults,
): { kind: TaskSubjectKind; ref: string } {
  if (selection.runId && surface === 'EVIDENCE') return { kind: 'RUN', ref: selection.runId };
  if (selection.caseId && (surface === 'SCENARIO' || surface === 'EVIDENCE')) {
    return { kind: 'CASE', ref: selection.caseId };
  }
  if (selection.nodeId) return { kind: 'NODE', ref: selection.nodeId };
  const target = bounded(url.searchParams.get('target'));
  if (target.startsWith('operator:')) return { kind: 'OPERATOR', ref: target.slice(9) };
  if (target.startsWith('function:')) return { kind: 'FUNCTION', ref: target.slice(9) };
  if (surface === 'LIBRARY') {
    return {
      kind: 'LIBRARY',
      ref: bounded(url.searchParams.get('libraryId')) || bounded(defaults.subjectRef)
        || bounded(url.searchParams.get('draftId')),
    };
  }
  return {
    kind: defaults.subjectKind ?? 'GRAPH',
    ref: bounded(defaults.subjectRef) || bounded(url.searchParams.get('draftId')),
  };
}

function enumValue<T extends string>(value: unknown, allowed: Set<T>): T | undefined {
  const normalized = bounded(value).toUpperCase() as T;
  return allowed.has(normalized) ? normalized : undefined;
}

function bounded(value: unknown, limit = 512): string {
  return typeof value === 'string' ? value.trim().slice(0, limit) : '';
}

function positiveInteger(value: unknown): number {
  const parsed = typeof value === 'number' ? value : Number.parseInt(bounded(value, 24), 10);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : 0;
}

function nonNegativeInteger(value: unknown): number {
  const parsed = typeof value === 'number' ? value : Number.parseInt(bounded(value, 24), 10);
  return Number.isSafeInteger(parsed) && parsed >= 0
    ? Math.min(10_000_000, parsed)
    : 0;
}

function boundedNumber(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value)
    ? Math.max(0, Math.min(10_000_000, Math.round(value)))
    : 0;
}

function setText(url: URL, key: string, value: string): void {
  const normalized = bounded(value);
  if (normalized) url.searchParams.set(key, normalized);
  else url.searchParams.delete(key);
}

function setNonNegativeInteger(url: URL, key: string, value: number): void {
  const normalized = nonNegativeInteger(value);
  if (normalized > 0) url.searchParams.set(key, String(normalized));
  else url.searchParams.delete(key);
}

function setPositiveInteger(url: URL, key: string, value: number): void {
  if (positiveInteger(value)) url.searchParams.set(key, String(value));
  else url.searchParams.delete(key);
}

function relativeHref(url: URL): string {
  return `${url.pathname}${url.search}${url.hash}`;
}
