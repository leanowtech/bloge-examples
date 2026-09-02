import type { FixtureCondition, FixtureSetView, FixtureSubjectRef } from './flowModel';
import type { FixtureSetSummary } from './model';

export type ExactFixtureSubjectRefV2 = FixtureSubjectRef;

export interface ExactFixtureSetRef {
  fixtureSetId: string;
  revision: number;
  fingerprint: string;
}

export type FixtureTarget = { kind: 'SUBJECT' }
  | { kind: 'NODE_PATH'; nodePath: string[] }
  | { kind: 'CALL_SITE'; nodePath: string[]; callSiteId: string };

export type FixtureSelection =
  | { kind: 'EXACT_CASE'; fixtureSet: ExactFixtureSetRef; caseId: string }
  | { kind: 'MATCH_CONDITION'; fixtureSet: ExactFixtureSetRef; conditionId: string }
  | { kind: 'AUTO_MATCH'; fixtureSet: ExactFixtureSetRef };

export interface FixtureBinding {
  target: FixtureTarget;
  selection: FixtureSelection;
}

export type FixturePlan = { kind: 'NONE' }
  | { kind: 'CASE_CONTROLS'; fixtureSet: ExactFixtureSetRef; caseId: string; unmatched: 'BLOCK' | 'REAL' }
  | { kind: 'BINDINGS'; unmatched: 'BLOCK' | 'REAL'; bindings: FixtureBinding[] };

export interface SimulationCommandV2 {
  schemaVersion: 'bloge.simulationCommand.v2';
  subject: ExactFixtureSubjectRefV2;
  input: { kind: 'INLINE'; value: unknown }
    | { kind: 'CASE_INPUT'; fixtureSet: ExactFixtureSetRef; caseId: string };
  fixturePlan: FixturePlan;
  executionPolicy: {
    externalReads: { kind: 'DENY' };
    externalWrites: { kind: 'DENY' };
  };
}

export interface SimulationRunV2 {
  schemaVersion: 'bloge.simulationRun.v2';
  runId: string;
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'BLOCKED';
  subject: ExactFixtureSubjectRefV2;
  requestFingerprint: string;
  resolvedFixturePlanFingerprint: string;
  output?: unknown;
  invocations: Array<{
    invocationKey: string;
    parentInvocationKey?: string;
    target: FixtureTarget;
    subject: ExactFixtureSubjectRefV2;
    status: 'COMPLETED' | 'FAILED' | 'BLOCKED' | 'SKIPPED';
    execution: 'REAL' | 'MOCKED';
    matchedBy: 'NONE' | 'EXACT_CASE' | 'CONDITION' | 'AUTO_MATCH' | 'CASE_CONTROLS';
    fixtureCase?: ExactFixtureSetRef & { caseId: string };
    behavior?: 'RETURN' | 'ERROR' | 'TIMEOUT' | 'REPLAY';
    fidelity?: 'OUTPUT_LEVEL' | 'PROTOCOL_DERIVED' | 'TRANSPORT_LEVEL';
    provenance?: 'PINNED_PRIVATE' | 'GOVERNED_ASSET' | 'REPLAY';
    inputFingerprint: string;
    outputFingerprint?: string;
    egress: { decision: string; attempted: boolean };
  }>;
  verdicts: {
    execution: 'PASSED' | 'FAILED' | 'BLOCKED';
    assertions: 'PASSED' | 'FAILED' | 'NOT_CHECKED';
    contract: 'VALID' | 'INVALID' | 'NOT_CHECKED';
    governance: 'PASSED' | 'FAILED' | 'NOT_CHECKED';
    aggregate: 'READY' | 'NOT_READY';
  };
  diagnostics: Array<{ code: string; message: string }>;
}

export interface FixtureBindingDraft {
  target: FixtureTarget;
  fixture: FixtureSetSummary;
  selectionKind: FixtureSelection['kind'];
  caseId?: string;
  conditionId?: string;
}

/** Builds the payload-only command; invocation identities and Fixture material stay server-owned. */
export function buildSimulationCommandV2(
  subject: ExactFixtureSubjectRefV2,
  input: unknown,
  plan: FixturePlan,
): SimulationCommandV2 {
  return {
    schemaVersion: 'bloge.simulationCommand.v2',
    subject: structuredClone(subject),
    input: { kind: 'INLINE', value: structuredClone(input) },
    fixturePlan: structuredClone(plan),
    executionPolicy: { externalReads: { kind: 'DENY' }, externalWrites: { kind: 'DENY' } },
  };
}

/** Compiles visible per-target choices into the frozen exact Fixture Binding union. */
export function bindingPlan(
  unmatched: 'BLOCK' | 'REAL', drafts: FixtureBindingDraft[],
): Extract<FixturePlan, { kind: 'BINDINGS' }> {
  const targets = new Set<string>();
  const bindings = drafts.map((draft) => {
    const targetKey = JSON.stringify(draft.target);
    if (targets.has(targetKey)) throw new Error('Each target can have only one Fixture binding.');
    targets.add(targetKey);
    const fixtureSet = fixtureRef(draft.fixture);
    let selection: FixtureSelection;
    if (draft.selectionKind === 'EXACT_CASE') {
      if (!draft.caseId) throw new Error('Choose one exact Fixture Case.');
      selection = { kind: 'EXACT_CASE', fixtureSet, caseId: draft.caseId };
    } else if (draft.selectionKind === 'MATCH_CONDITION') {
      if (!draft.conditionId) throw new Error('Choose one stable condition.');
      selection = { kind: 'MATCH_CONDITION', fixtureSet, conditionId: draft.conditionId };
    } else {
      selection = { kind: 'AUTO_MATCH', fixtureSet };
    }
    return { target: structuredClone(draft.target), selection };
  });
  return { kind: 'BINDINGS', unmatched, bindings };
}

/** Returns a deterministic local preview; the server remains authoritative for runtime matching. */
export function previewFixtureConditions(view: FixtureSetView, input: unknown): Array<{
  caseId: string; conditionId: string; matched: boolean;
}> {
  return view.cases.flatMap((fixtureCase) => fixtureCase.when ? [{
    caseId: fixtureCase.caseId,
    conditionId: fixtureCase.when.conditionId,
    matched: matches(fixtureCase.when, input),
  }] : []);
}

export function fixtureRef(summary: FixtureSetSummary): ExactFixtureSetRef {
  return {
    fixtureSetId: summary.fixtureSetId,
    revision: summary.revision,
    fingerprint: summary.fingerprint,
  };
}

function matches(condition: FixtureCondition, input: unknown): boolean {
  return condition.all.every((predicate) => {
    const resolved = jsonPath(input, predicate.path);
    if (predicate.operator === 'PRESENT') return resolved.present;
    if (predicate.operator === 'ABSENT') return !resolved.present;
    if (predicate.operator === 'EQ') return resolved.present && equal(resolved.value, predicate.value);
    if (predicate.operator === 'IN') {
      return resolved.present && predicate.values.some((candidate) => equal(resolved.value, candidate));
    }
    if (predicate.operator !== 'NUMBER_RANGE') return false;
    if (!resolved.present || typeof resolved.value !== 'number') return false;
    return (predicate.minimum === undefined || resolved.value >= predicate.minimum)
      && (predicate.maximum === undefined || resolved.value <= predicate.maximum);
  });
}

function jsonPath(root: unknown, path: string): { present: boolean; value?: unknown } {
  if (path === '$') return { present: true, value: root };
  if (!path.startsWith('$.')) return { present: false };
  let current: unknown = root;
  for (const segment of path.slice(2).split('.')) {
    if (!current || typeof current !== 'object' || Array.isArray(current)
      || !Object.prototype.hasOwnProperty.call(current, segment)) return { present: false };
    current = (current as Record<string, unknown>)[segment];
  }
  return { present: true, value: current };
}

function equal(left: unknown, right: unknown): boolean {
  return JSON.stringify(left) === JSON.stringify(right);
}
