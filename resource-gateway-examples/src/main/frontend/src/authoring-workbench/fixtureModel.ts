import type { FixtureSetCommand, FixtureSetView } from './flowModel';

export interface FixtureObjectDraft {
  displayName: string;
  caseId: string;
  caseName: string;
  inputSource: string;
  outputSource: string;
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
  };
}

/** Rebuilds one editable whole-subject RETURN command without changing its exact Subject authority. */
export function buildFixtureObjectCommand(view: FixtureSetView, draft: FixtureObjectDraft): FixtureSetCommand {
  if (view.subject.kind === 'API_RESOURCE') throw new Error('API Resource Default Fixtures are edited on the Resource page.');
  const input = objectJson(draft.inputSource, 'Fixture input');
  const output = objectJson(draft.outputSource, 'Fixture output');
  return {
    schemaVersion: 'bloge.fixtureSetCommand.v1', displayName: requiredText(draft.displayName),
    subject: structuredClone(view.subject),
    cases: [{
      caseId: draft.caseId, name: draft.caseName, input,
      controls: [{
        target: { kind: 'SUBJECT' },
        behavior: { kind: 'RETURN', material: { kind: 'INLINE', value: output } },
      }],
      expect: { output: structuredClone(output) },
    }],
  };
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
