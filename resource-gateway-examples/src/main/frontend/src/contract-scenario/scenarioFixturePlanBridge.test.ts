import { describe, expect, it } from 'vitest';

import type { ScenarioEditorSnapshot } from './scenarioEditorModel';
import { compileScenarioFixturePlanV2 } from './scenarioFixturePlanBridge';

describe('Scenario to caller-directed Fixture Plan bridge', () => {
  it('keeps generated Decision Scenarios fixture-free', () => {
    expect(compileScenarioFixturePlanV2(snapshot([]), subject(), {})).toEqual({ kind: 'NONE' });
  });

  it('projects only an explicitly saved expected Return fixture to the operator subject', () => {
    const dependency = returnDependency();
    const plan = compileScenarioFixturePlanV2(snapshot([dependency]), subject(), {
      [dependency.dependencyId]: fixture(),
    });

    expect(plan).toEqual({ kind: 'BINDINGS', unmatched: 'BLOCK', bindings: [{
      target: { kind: 'SUBJECT' }, selection: {
        kind: 'EXACT_CASE', fixtureSet: {
          fixtureSetId: 'decision-return', revision: 2, fingerprint: hash('d'),
        }, caseId: 'expected-output',
      },
    }] });
    expect(JSON.stringify(plan)).not.toContain('approved');
    expect(JSON.stringify(plan)).not.toContain('invocationKey');
  });

  it('rejects an inline dependency until a saved Fixture revision exists', () => {
    const dependency = returnDependency();
    expect(() => compileScenarioFixturePlanV2(snapshot([dependency]), subject(), {}))
      .toThrow("Save dependency 'expected-return-operator' as a Fixture Case");
  });
});

function snapshot(dependencies: ScenarioEditorSnapshot['scenario']['dependencies']): ScenarioEditorSnapshot {
  return {
    schemaVersion: 'bloge.scenarioEditorSnapshot.v1', scenarioDraftSetId: 'decision-cases',
    scenarioRevision: 2, target: { kind: 'OPERATOR', id: 'loan-policy', revision: 3, fingerprint: hash('a') },
    contractFingerprint: hash('b'), contract: { inputSchema: schema(), outputSchema: schema() },
    nodeSchemas: {}, scenario: {
      scenarioId: 'approved', name: 'Approved', description: '', caseType: 'GOLDEN', tags: [],
      given: { input: { score: 800 }, provenance: 'GENERATED' }, dependencies,
      then: { assertions: [] },
    },
  };
}

function returnDependency(): ScenarioEditorSnapshot['scenario']['dependencies'][number] {
  return {
    dependencyId: 'expected-return-operator', selector: {
      graphPath: '', nodeId: '', operatorRef: 'loan-policy', resourceRef: '', functionRef: '',
      attempts: [], occurrences: [], correlationKey: '', pathEquals: {},
    }, behavior: { kind: 'RETURN', boundary: 'NODE', output: { approved: true } },
    consumption: { required: true, minUses: 1, maxUses: 1, onExhausted: 'FAIL', onUnmatched: 'FAIL' },
    schemaCheck: { mode: 'STRICT', waiverReason: '' }, origin: 'EXPECTED_RETURN_FIXTURE',
  };
}

function subject() {
  return { kind: 'OPERATOR_VERSION' as const, libraryId: 'loan', libraryRevision: 3,
    operatorRef: 'loan-policy', contractFingerprint: hash('b') };
}

function fixture() {
  return { schemaVersion: 'bloge.fixtureSetSummary.v1' as const, fixtureSetId: 'decision-return', revision: 2,
    fingerprint: hash('d'), displayName: 'Expected return', subject: subject(),
    cases: [{ caseId: 'expected-output', name: 'Expected output' }],
    status: 'PRIVATE_DRAFT', statusRevision: 1 };
}

function schema() {
  return { format: 'json-schema' as const, version: '2020-12' as const,
    schema: { type: 'object' as const, properties: {}, required: [], additionalProperties: false as const } };
}
function hash(value: string): string { return `sha256:${value.repeat(64)}`; }
