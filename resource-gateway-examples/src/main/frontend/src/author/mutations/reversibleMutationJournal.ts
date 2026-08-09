import { canonicalJson } from '../../contract-scenario/fingerprint';

export const MUTATION_KINDS = [
  'ADD_NODE',
  'REMOVE_NODE',
  'MOVE_NODE',
  'ADD_EDGE',
  'REMOVE_EDGE',
  'INPUT_BINDING',
  'NODE_CONFIG',
  'FIXTURE',
  'DECISION_TABLE',
  'TRANSFORM',
  'TEST_SUITE',
  'CONTEXT',
  'GRAPH_CONTRACT',
  'GRAPH_METADATA',
  'RUNTIME_BINDING',
  'IMPORT',
  'AUTO_LAYOUT',
  'OUTPUT_BINDING',
  'SCENARIO',
  'OTHER',
] as const;

export type MutationKind = typeof MUTATION_KINDS[number];

export type AssetImpactKind =
  | 'NODE'
  | 'EDGE'
  | 'FIXTURE_OUTPUT'
  | 'FIXTURE_INPUT'
  | 'TEST_CASE'
  | 'TEST_RESULT'
  | 'TEST_PUBLICATION'
  | 'OUTPUT_BINDING';

export interface AssetImpact {
  kind: AssetImpactKind;
  count: number;
  refs: string[];
  severity: 'INFO' | 'WARNING' | 'DESTRUCTIVE';
}

export interface AuthoringMutation<TSnapshot> {
  mutationId: string;
  kind: MutationKind;
  label: string;
  subjectRef: string;
  beforeFingerprint: string;
  afterFingerprint: string;
  before: TSnapshot;
  after: TSnapshot;
  impact: AssetImpact[];
  coalesceKey: string;
  occurredAt: number;
  estimatedBytes: number;
}

export interface MutationJournalState<TSnapshot> {
  past: AuthoringMutation<TSnapshot>[];
  future: AuthoringMutation<TSnapshot>[];
  savedCheckpointFingerprint: string;
  maxEntries: number;
  maxBytes: number;
  totalBytes: number;
  droppedMutationCount: number;
}

interface MutationInput<TSnapshot> {
  mutationId: string;
  kind: MutationKind;
  label: string;
  subjectRef: string;
  before: TSnapshot;
  after: TSnapshot;
  impact?: AssetImpact[];
  coalesceKey?: string;
  occurredAt?: number;
}

interface JournalLimits {
  maxEntries?: number;
  maxBytes?: number;
}

export interface JournalTransition<TSnapshot> {
  journal: MutationJournalState<TSnapshot>;
  mutation: AuthoringMutation<TSnapshot>;
  snapshot: TSnapshot;
}

export interface NodeDeletionInventory {
  edges: Array<{ id: string; source: string; target: string }>;
  fixtureDrafts: Record<string, string>;
  fixtureInputDrafts: Record<string, string>;
  operatorTestSuites: Record<string, unknown[]>;
  operatorTestResults: Record<string, Record<string, unknown>>;
  operatorTestPublications: Record<string, unknown>;
  explicitOutputNodeId: string;
}

export interface NodeDeletionImpact {
  items: AssetImpact[];
  edgeIds: string[];
  requiresConfirmation: boolean;
}

const DEFAULT_MAX_ENTRIES = 100;
const DEFAULT_MAX_BYTES = 20 * 1024 * 1024;
const DEFAULT_RECOVERY_MAX_ENTRIES = 24;
const DEFAULT_RECOVERY_MAX_BYTES = 1_500_000;
const COALESCE_WINDOW_MS = 750;
const MUTATION_KIND_SET = new Set<string>(MUTATION_KINDS);
const ASSET_IMPACT_KIND_SET = new Set<AssetImpactKind>([
  'NODE',
  'EDGE',
  'FIXTURE_OUTPUT',
  'FIXTURE_INPUT',
  'TEST_CASE',
  'TEST_RESULT',
  'TEST_PUBLICATION',
  'OUTPUT_BINDING',
]);
const IMPACT_SEVERITY_SET = new Set<AssetImpact['severity']>(['INFO', 'WARNING', 'DESTRUCTIVE']);

export function initialMutationJournal<TSnapshot>({
  maxEntries = DEFAULT_MAX_ENTRIES,
  maxBytes = DEFAULT_MAX_BYTES,
}: JournalLimits = {}): MutationJournalState<TSnapshot> {
  return {
    past: [],
    future: [],
    savedCheckpointFingerprint: '',
    maxEntries,
    maxBytes,
    totalBytes: 0,
    droppedMutationCount: 0,
  };
}

export function createMutation<TSnapshot>({
  mutationId,
  kind,
  label,
  subjectRef,
  before,
  after,
  impact = [],
  coalesceKey = '',
  occurredAt = Date.now(),
}: MutationInput<TSnapshot>): AuthoringMutation<TSnapshot> {
  const beforeFingerprint = mutationFingerprint(before);
  const afterFingerprint = mutationFingerprint(after);
  const estimatedBytes = jsonBytes({
    mutationId,
    kind,
    label,
    subjectRef,
    before,
    after,
    impact,
    coalesceKey,
    occurredAt,
  });
  return {
    mutationId,
    kind,
    label,
    subjectRef,
    beforeFingerprint,
    afterFingerprint,
    before,
    after,
    impact,
    coalesceKey,
    occurredAt,
    estimatedBytes,
  };
}

export function recordMutation<TSnapshot>(
  journal: MutationJournalState<TSnapshot>,
  mutation: AuthoringMutation<TSnapshot>,
): MutationJournalState<TSnapshot> {
  if (mutation.beforeFingerprint === mutation.afterFingerprint) return journal;

  const previous = journal.past[journal.past.length - 1];
  const shouldCoalesce = Boolean(
    previous
    && mutation.coalesceKey
    && previous.coalesceKey === mutation.coalesceKey
    && mutation.occurredAt - previous.occurredAt <= COALESCE_WINDOW_MS,
  );
  const nextMutation = shouldCoalesce
    ? createMutation({
        mutationId: previous.mutationId,
        kind: mutation.kind,
        label: mutation.label,
        subjectRef: mutation.subjectRef,
        before: previous.before,
        after: mutation.after,
        impact: mergeImpact(previous.impact, mutation.impact),
        coalesceKey: mutation.coalesceKey,
        occurredAt: mutation.occurredAt,
      })
    : mutation;
  let past = shouldCoalesce
    ? [...journal.past.slice(0, -1), nextMutation]
    : [...journal.past, nextMutation];
  let droppedMutationCount = journal.droppedMutationCount;
  let totalBytes = mutationBytes(past);

  while (past.length > journal.maxEntries || (past.length > 0 && totalBytes > journal.maxBytes)) {
    past = past.slice(1);
    droppedMutationCount += 1;
    totalBytes = mutationBytes(past);
  }

  return {
    ...journal,
    past,
    future: [],
    totalBytes,
    droppedMutationCount,
  };
}

export function undoMutation<TSnapshot>(
  journal: MutationJournalState<TSnapshot>,
): JournalTransition<TSnapshot> | null {
  const mutation = journal.past[journal.past.length - 1];
  if (!mutation) return null;
  const past = journal.past.slice(0, -1);
  const future = [...journal.future, mutation];
  return {
    mutation,
    snapshot: structuredClone(mutation.before),
    journal: {
      ...journal,
      past,
      future,
      totalBytes: mutationBytes(past) + mutationBytes(future),
    },
  };
}

export function redoMutation<TSnapshot>(
  journal: MutationJournalState<TSnapshot>,
): JournalTransition<TSnapshot> | null {
  const mutation = journal.future[journal.future.length - 1];
  if (!mutation) return null;
  const past = [...journal.past, mutation];
  const future = journal.future.slice(0, -1);
  return {
    mutation,
    snapshot: structuredClone(mutation.after),
    journal: {
      ...journal,
      past,
      future,
      totalBytes: mutationBytes(past) + mutationBytes(future),
    },
  };
}

export function markSavedCheckpoint<TSnapshot>(
  journal: MutationJournalState<TSnapshot>,
  fingerprint: string,
): MutationJournalState<TSnapshot> {
  if (journal.savedCheckpointFingerprint === fingerprint) return journal;
  return { ...journal, savedCheckpointFingerprint: fingerprint };
}

/** Keeps runtime history rich while bounding the copy embedded in browser recovery storage. */
export function mutationJournalForRecovery<TSnapshot>(
  journal: MutationJournalState<TSnapshot>,
  {
    maxEntries = DEFAULT_RECOVERY_MAX_ENTRIES,
    maxBytes = DEFAULT_RECOVERY_MAX_BYTES,
  }: JournalLimits = {},
): MutationJournalState<TSnapshot> {
  let past = [...journal.past];
  let future = [...journal.future];
  let droppedMutationCount = journal.droppedMutationCount;
  let totalBytes = mutationBytes(past) + mutationBytes(future);
  while (past.length + future.length > maxEntries || totalBytes > maxBytes) {
    if (past.length >= future.length && past.length > 0) {
      past = past.slice(1);
    } else if (future.length > 0) {
      future = future.slice(1);
    } else {
      break;
    }
    droppedMutationCount += 1;
    totalBytes = mutationBytes(past) + mutationBytes(future);
  }
  return {
    ...journal,
    past,
    future,
    totalBytes,
    droppedMutationCount,
  };
}

/** A compact deterministic identity for local equality checks, not a trust or signature primitive. */
export function mutationFingerprint(value: unknown): string {
  const source = canonicalJson(value);
  return `mfp1:${source.length.toString(16)}:${fnv1a32(source, 0x811c9dc5)}:${fnv1a32(source, 0x9e3779b9)}`;
}

/** Restores only structurally valid bounded history from an untrusted recovery envelope. */
export function restoreMutationJournal<TSnapshot>(
  candidate: unknown,
  snapshotGuard?: (value: unknown) => value is TSnapshot,
): MutationJournalState<TSnapshot> {
  const fallback = initialMutationJournal<TSnapshot>();
  if (!isRecord(candidate) || !Array.isArray(candidate.past) || !Array.isArray(candidate.future)) {
    return fallback;
  }
  const past = restoreMutationArray<TSnapshot>(candidate.past, snapshotGuard);
  const future = restoreMutationArray<TSnapshot>(candidate.future, snapshotGuard);
  if (!past || !future) return fallback;

  let boundedPast = past.slice(-fallback.maxEntries);
  let boundedFuture = future.slice(-Math.max(0, fallback.maxEntries - boundedPast.length));
  let totalBytes = mutationBytes(boundedPast) + mutationBytes(boundedFuture);
  let droppedMutationCount = Number.isFinite(candidate.droppedMutationCount)
    ? Math.max(0, Number(candidate.droppedMutationCount))
    : 0;
  while ((boundedPast.length > 0 || boundedFuture.length > 0) && totalBytes > fallback.maxBytes) {
    if (boundedPast.length > 0) {
      boundedPast = boundedPast.slice(1);
    } else {
      boundedFuture = boundedFuture.slice(1);
    }
    droppedMutationCount += 1;
    totalBytes = mutationBytes(boundedPast) + mutationBytes(boundedFuture);
  }
  return {
    ...fallback,
    past: boundedPast,
    future: boundedFuture,
    savedCheckpointFingerprint: typeof candidate.savedCheckpointFingerprint === 'string'
      ? candidate.savedCheckpointFingerprint
      : '',
    totalBytes,
    droppedMutationCount,
  };
}

export function projectNodeDeletionImpact(
  nodeIds: string[],
  inventory: NodeDeletionInventory,
): NodeDeletionImpact {
  const selected = new Set(nodeIds);
  const edgeIds = inventory.edges
    .filter((edge) => selected.has(edge.source) || selected.has(edge.target))
    .map((edge) => edge.id);
  const fixtureOutputs = matchingKeys(inventory.fixtureDrafts, selected);
  const fixtureInputs = matchingKeys(inventory.fixtureInputDrafts, selected);
  const testCases = nodeIds.flatMap((nodeId) => (
    inventory.operatorTestSuites[nodeId]?.map((_, index) => `${nodeId}:${index + 1}`) ?? []
  ));
  const testResults = nodeIds.flatMap((nodeId) => (
    Object.keys(inventory.operatorTestResults[nodeId] ?? {}).map((caseId) => `${nodeId}:${caseId}`)
  ));
  const testPublications = matchingKeys(inventory.operatorTestPublications, selected);
  const outputBindings = selected.has(inventory.explicitOutputNodeId)
    ? [inventory.explicitOutputNodeId]
    : [];
  const items = [
    impact('NODE', nodeIds, 'DESTRUCTIVE'),
    impact('EDGE', edgeIds, 'WARNING'),
    impact('FIXTURE_OUTPUT', fixtureOutputs, 'DESTRUCTIVE'),
    impact('FIXTURE_INPUT', fixtureInputs, 'DESTRUCTIVE'),
    impact('TEST_CASE', testCases, 'DESTRUCTIVE'),
    impact('TEST_RESULT', testResults, 'DESTRUCTIVE'),
    impact('TEST_PUBLICATION', testPublications, 'DESTRUCTIVE'),
    impact('OUTPUT_BINDING', outputBindings, 'DESTRUCTIVE'),
  ].filter((item): item is AssetImpact => item !== null);
  return {
    items,
    edgeIds,
    requiresConfirmation: items.some((item) => (
      item.kind !== 'NODE' && item.kind !== 'EDGE' && item.severity === 'DESTRUCTIVE'
    )),
  };
}

function impact(
  kind: AssetImpactKind,
  refs: string[],
  severity: AssetImpact['severity'],
): AssetImpact | null {
  return refs.length > 0 ? { kind, count: refs.length, refs, severity } : null;
}

function matchingKeys(source: Record<string, unknown>, selected: Set<string>): string[] {
  return Object.keys(source).filter((key) => selected.has(key));
}

function mergeImpact(left: AssetImpact[], right: AssetImpact[]): AssetImpact[] {
  const merged = new Map<AssetImpactKind, AssetImpact>();
  [...left, ...right].forEach((item) => {
    const current = merged.get(item.kind);
    const refs = Array.from(new Set([...(current?.refs ?? []), ...item.refs]));
    merged.set(item.kind, { ...item, refs, count: refs.length });
  });
  return Array.from(merged.values());
}

function mutationBytes<TSnapshot>(mutations: AuthoringMutation<TSnapshot>[]): number {
  return mutations.reduce((total, mutation) => total + mutation.estimatedBytes, 0);
}

function restoreMutationArray<TSnapshot>(
  candidate: unknown[],
  snapshotGuard?: (value: unknown) => value is TSnapshot,
): AuthoringMutation<TSnapshot>[] | null {
  const restored: AuthoringMutation<TSnapshot>[] = [];
  for (const raw of candidate) {
    if (
      !isRecord(raw)
      || typeof raw.mutationId !== 'string'
      || typeof raw.kind !== 'string'
      || !MUTATION_KIND_SET.has(raw.kind)
      || typeof raw.label !== 'string'
      || typeof raw.subjectRef !== 'string'
      || typeof raw.occurredAt !== 'number'
      || raw.before === undefined
      || raw.after === undefined
      || (snapshotGuard && (!snapshotGuard(raw.before) || !snapshotGuard(raw.after)))
      || (raw.impact !== undefined && !Array.isArray(raw.impact))
    ) {
      return null;
    }
    const impact = restoreImpact(raw.impact ?? []);
    if (!impact) return null;
    restored.push(createMutation({
      mutationId: raw.mutationId,
      kind: raw.kind as MutationKind,
      label: raw.label,
      subjectRef: raw.subjectRef,
      before: raw.before as TSnapshot,
      after: raw.after as TSnapshot,
      impact,
      coalesceKey: typeof raw.coalesceKey === 'string' ? raw.coalesceKey : '',
      occurredAt: raw.occurredAt,
    }));
  }
  return restored;
}

function restoreImpact(candidate: unknown): AssetImpact[] | null {
  if (!Array.isArray(candidate)) return null;
  const restored: AssetImpact[] = [];
  for (const raw of candidate) {
    if (
      !isRecord(raw)
      || typeof raw.kind !== 'string'
      || !ASSET_IMPACT_KIND_SET.has(raw.kind as AssetImpactKind)
      || !Array.isArray(raw.refs)
      || !raw.refs.every((ref) => typeof ref === 'string')
      || typeof raw.severity !== 'string'
      || !IMPACT_SEVERITY_SET.has(raw.severity as AssetImpact['severity'])
    ) {
      return null;
    }
    const refs = Array.from(new Set(raw.refs as string[]));
    restored.push({
      kind: raw.kind as AssetImpactKind,
      count: refs.length,
      refs,
      severity: raw.severity as AssetImpact['severity'],
    });
  }
  return restored;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function jsonBytes(value: unknown): number {
  return new TextEncoder().encode(canonicalJson(value)).byteLength;
}

function fnv1a32(source: string, seed: number): string {
  let hash = seed >>> 0;
  for (let index = 0; index < source.length; index += 1) {
    hash ^= source.charCodeAt(index);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return hash.toString(16).padStart(8, '0');
}
