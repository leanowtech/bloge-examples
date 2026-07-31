import { describe, expect, it } from 'vitest';

import { contractDraftFromGraphDraft } from './domain';
import {
  assertionForScope,
  assertionPathOptions,
  behaviorForKind,
  captureScenarioEditorSnapshot,
  durationFromMilliseconds,
  selectDependencyTarget,
} from './scenarioEditorModel';
import { scenarioDraftSetFromCanvas } from './scenarioAuthoring';
import { graphDraft, nodes } from './testFixtures';

describe('Scenario graphical editor model', () => {
  it('creates complete deterministic defaults for every governed behavior', () => {
    const node = nodes()[0];

    expect(behaviorForKind('ERROR', node)).toMatchObject({
      kind: 'ERROR',
      boundary: 'NODE',
      errorCode: 'SCENARIO_DEPENDENCY_ERROR',
      errorType: 'DEPENDENCY_ERROR',
    });
    expect(behaviorForKind('DELAY', node)).toMatchObject({
      kind: 'DELAY',
      after: 'PT0.1S',
      output: { score: 0 },
    });
    expect(behaviorForKind('TIMEOUT', node)).toMatchObject({
      kind: 'TIMEOUT',
      after: 'PT1S',
      errorCode: 'SCENARIO_TIMEOUT',
    });
    expect(behaviorForKind('REPLAY', node)).toMatchObject({
      kind: 'REPLAY',
      replayRef: '',
    });
    expect(durationFromMilliseconds(250)).toBe('PT0.25S');
  });

  it('switches selector kinds without leaving an ambiguous competing coordinate', () => {
    const draft = graphDraft();
    const contract = contractDraftFromGraphDraft(draft, fingerprint('a'));
    const dependency = scenarioDraftSetFromCanvas(
      contract.target,
      fingerprint('b'),
      draft,
      nodes(),
      [],
    ).scenarios[0].dependencies[0];

    const selected = selectDependencyTarget(dependency, 'FUNCTION', 'money.round');

    expect(selected.selector).toMatchObject({
      nodeId: '',
      operatorRef: '',
      resourceRef: '',
      functionRef: 'money.round',
    });
  });

  it('normalizes each assertion scope to a valid graphical starting point', () => {
    const draft = graphDraft();
    const contract = contractDraftFromGraphDraft(draft, fingerprint('a'));
    const dependencies = scenarioDraftSetFromCanvas(
      contract.target,
      fingerprint('b'),
      draft,
      nodes(),
      [],
    ).scenarios[0].dependencies;
    const base = {
      assertionId: 'assertion-1',
      scope: 'OUTPUT_PATH' as const,
      nodeId: '',
      fromNodeId: '',
      toNodeId: '',
      path: '',
      operator: 'EQUALS' as const,
      expected: {},
    };

    expect(assertionForScope(base, 'NODE_STATUS', contract, nodes(), dependencies)).toMatchObject({
      scope: 'NODE_STATUS',
      nodeId: 'score',
      operator: 'STATUS',
      expected: 'SUCCESS',
    });
    expect(assertionForScope(base, 'EDGE_TRANSFER', contract, nodes(), dependencies)).toMatchObject({
      scope: 'EDGE_TRANSFER',
      fromNodeId: 'score',
      toNodeId: 'decide',
      operator: 'USED',
    });
    expect(assertionForScope(base, 'INVOCATION', contract, nodes(), dependencies)).toMatchObject({
      scope: 'INVOCATION',
      nodeId: 'score-behavior',
      operator: 'USED',
      expected: 1,
    });
  });

  it('projects nested result fields into assertion choices without array pseudo-paths', () => {
    const contract = contractDraftFromGraphDraft(graphDraft(), fingerprint('a'));

    expect(assertionPathOptions(contract.outputSchema)).toEqual([
      { path: '', label: 'Whole result', type: 'object' },
      { path: 'decision', label: 'decision', type: 'object' },
      { path: 'decision.approved', label: 'decision.approved', type: 'boolean' },
      { path: 'decision.reason', label: 'decision.reason', type: 'string' },
    ]);
  });

  it('captures an immutable canonical snapshot of exactly what the graphical editor shows', () => {
    const draft = {
      ...graphDraft(),
      nodeFixtures: { score: { output: { score: 0 } } },
    };
    const contract = contractDraftFromGraphDraft(draft, fingerprint('a'));
    const draftSet = scenarioDraftSetFromCanvas(
      contract.target,
      fingerprint('b'),
      draft,
      nodes(),
      [],
    );

    const snapshot = captureScenarioEditorSnapshot(
      draftSet,
      draftSet.scenarios[0].scenarioId,
      contract,
      nodes(),
    );
    const dependency = draftSet.scenarios[0].dependencies[0];
    dependency.behavior.output = { score: 999 };

    expect(snapshot.scenario.dependencies[0].behavior.output).toEqual({ score: 0 });
    expect(snapshot.contract.inputSchema).toEqual(contract.inputSchema);
    expect(snapshot.nodeSchemas.score.outputSchema).toEqual(nodes()[0].outputSchema);
    expect(Object.isFrozen(snapshot)).toBe(true);
  });
});

function fingerprint(seed: string): string {
  return `sha256:${seed.repeat(64).slice(0, 64)}`;
}
