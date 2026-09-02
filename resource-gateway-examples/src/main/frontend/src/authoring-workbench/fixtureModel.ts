import type { FixtureSetCommand, FixtureSetView } from './flowModel';

export interface FixtureObjectDraft {
  displayName: string;
  caseId: string;
  caseName: string;
  inputSource: string;
  outputSource: string;
  conditionId: string;
  conditionPath: string;
  conditionOperator: 'EQ' | 'PRESENT' | 'ABSENT';
  conditionValueSource: string;
}

/** Returns the narrow whole-subject editor state, or null for protected/non-inline Fixture shapes. */
export function fixtureObjectDraft(view: FixtureSetView): FixtureObjectDraft | null {
  if (view.subject.kind === 'API_RESOURCE' || view.cases.length !== 1) return null;
  const fixtureCase = view.cases[0];
  if (fixtureCase.controls.length !== 1) return null;
  const control = fixtureCase.controls[0];
  if (control.target.kind !== 'SUBJECT' || control.behavior.kind !== 'RETURN'
      || control.behavior.material.kind !== 'INLINE') return null;
  if (!isObject(fixtureCase.input) || !isObject(control.behavior.material.value)) return null;
  return {
    displayName: view.displayName, caseId: fixtureCase.caseId, caseName: fixtureCase.name,
    inputSource: JSON.stringify(fixtureCase.input, null, 2),
    outputSource: JSON.stringify(control.behavior.material.value, null, 2),
    conditionId: fixtureCase.when?.conditionId ?? '',
    conditionPath: fixtureCase.when?.all[0]?.path ?? '$.',
    conditionOperator: conditionOperator(fixtureCase.when?.all[0]?.operator),
    conditionValueSource: fixtureCase.when?.all[0]?.operator === 'EQ'
      ? JSON.stringify(fixtureCase.when.all[0].value) : '',
  };
}

/** Rebuilds one editable whole-subject RETURN command without changing its exact Subject authority. */
export function buildFixtureObjectCommand(view: FixtureSetView, draft: FixtureObjectDraft): FixtureSetCommand {
  if (view.subject.kind === 'API_RESOURCE') throw new Error('API Resource Default Fixtures are edited on the Resource page.');
  const input = objectJson(draft.inputSource, 'Fixture input');
  const output = objectJson(draft.outputSource, 'Fixture output');
  const when = condition(draft);
  return {
    schemaVersion: 'bloge.fixtureSetCommand.v1', displayName: requiredText(draft.displayName),
    subject: structuredClone(view.subject),
    cases: [{
      caseId: draft.caseId, name: draft.caseName, input,
      ...(when ? { when } : {}),
      controls: [{
        target: { kind: 'SUBJECT' },
        behavior: { kind: 'RETURN', material: { kind: 'INLINE', value: output } },
      }],
      expect: { output: structuredClone(output) },
    }],
  };
}

function condition(draft: FixtureObjectDraft) {
  const conditionId = draft.conditionId.trim();
  if (!conditionId) return null;
  const path = draft.conditionPath.trim();
  if (!/^(\$|\$(?:\.[A-Za-z0-9_-]+)+)$/.test(path)) {
    throw new Error('Condition path must be $ or a bounded dot path such as $.customer.tier.');
  }
  const predicate = draft.conditionOperator === 'EQ'
    ? { operator: 'EQ' as const, path, value: jsonValue(draft.conditionValueSource, 'Condition value') }
    : { operator: draft.conditionOperator, path };
  return { conditionId, all: [predicate] };
}

function conditionOperator(value: string | undefined): FixtureObjectDraft['conditionOperator'] {
  return value === 'PRESENT' || value === 'ABSENT' ? value : 'EQ';
}

function jsonValue(source: string, label: string): unknown {
  try { return JSON.parse(source) as unknown; } catch { throw new Error(`${label} must be valid JSON.`); }
}

function objectJson(source: string, label: string): Record<string, unknown> {
  try {
    const value: unknown = JSON.parse(source);
    if (!isObject(value)) throw new Error();
    return value;
  } catch {
    throw new Error(`${label} must be a JSON object.`);
  }
}

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function requiredText(value: string): string {
  const normalized = value.trim();
  if (!normalized || normalized.length > 200) throw new Error('Fixture name is required.');
  return normalized;
}
