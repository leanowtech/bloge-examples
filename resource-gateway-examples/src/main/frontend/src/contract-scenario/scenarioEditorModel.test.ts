import { describe, expect, it } from 'vitest';

import { contractDraftFromGraphDraft } from './domain';
import {
  assertionForScope,
  assertionPathOptions,
  behaviorForKind,
  captureScenarioEditorSnapshot,
  dependencyNeedsAttention,
  durationFromMilliseconds,
  selectDependencyTarget,
  upsertExpectedReturnDependency,
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

  it('opens only incomplete dependency behaviors for remediation', () => {
    const complete = {
      dependencyId: 'score-behavior',
      selector: {
        graphPath: '',
        nodeId: 'score',
        operatorRef: '',
        resourceRef: '',
        functionRef: '',
        attempts: [],
        occurrences: [],
        correlationKey: '',
        pathEquals: {},
      },
      behavior: behaviorForKind('RETURN', nodes()[0]),
      consumption: {
        required: true,
        minUses: 1,
        maxUses: 1,
        onExhausted: 'FAIL' as const,
        onUnmatched: 'FAIL' as const,
      },
      schemaCheck: { mode: 'STRICT' as const, waiverReason: '' },
      origin: 'TEST',
    };

    expect(dependencyNeedsAttention(complete)).toBe(false);
    expect(dependencyNeedsAttention({
      ...complete,
      behavior: behaviorForKind('REPLAY', nodes()[0]),
    })).toBe(true);
    expect(dependencyNeedsAttention({
      ...complete,
      schemaCheck: { mode: 'WAIVED', waiverReason: '' },
    })).toBe(true);
  });

  it('switches selector kinds without leaving an ambiguous competing coordinate', () => {
    const draft = {
      ...graphDraft(),
      nodeFixtures: { score: { output: { score: 0 } } },
    };
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
    const draft = {
      ...graphDraft(),
      nodeFixtures: { score: { output: { score: 0 } } },
    };
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

  it('adds one explicit expected-output Return fixture and updates it idempotently', () => {
    const scenario = scenarioDraftSetFromCanvas(
      contractDraftFromGraphDraft(graphDraft(), fingerprint('a')).target,
      fingerprint('b'),
      graphDraft(),
      nodes().slice(0, 1),
      [],
    ).scenarios[0];
    const expected = { action: 'decline', steps: [], reason: 'bounded' };
    const source = { ...scenario, then: { assertions: [{ ...scenario.then.assertions[0], expected }] } };

    const first = upsertExpectedReturnDependency(source, nodes().slice(0, 1), 'GRAPH');
    expect(first?.dependencies).toHaveLength(1);
    expect(first?.dependencies[0]).toMatchObject({
      dependencyId: 'expected-return-score',
      selector: { nodeId: 'score', operatorRef: '', resourceRef: '', functionRef: '' },
      behavior: { kind: 'RETURN', boundary: 'NODE', output: expected },
    });
    expect(first?.dependencies[0]?.behavior.output).not.toBe(expected);

    const changed = { action: 'approve', steps: [], reason: 'reviewed' };
    const second = upsertExpectedReturnDependency(
      { ...first!, then: { assertions: [{ ...source.then.assertions[0], expected: changed }] } },
      nodes().slice(0, 1),
      'GRAPH',
    );
    expect(second?.dependencies).toHaveLength(1);
    expect(second?.dependencies[0]?.dependencyId).toBe('expected-return-score');
    expect(second?.dependencies[0]?.behavior.output).toEqual(changed);
  });

  it('targets an operator-authored Return fixture by operator reference instead of node id', () => {
    const scenario = scenarioDraftSetFromCanvas(
      contractDraftFromGraphDraft(graphDraft(), fingerprint('a')).target,
      fingerprint('b'),
      graphDraft(),
      nodes().slice(0, 1),
      [],
    ).scenarios[0];
    const expected = { decision: { approved: false, reason: 'policy' } };
    const source = { ...scenario, then: { assertions: [{ ...scenario.then.assertions[0], expected }] } };

    const actual = upsertExpectedReturnDependency(source, nodes().slice(0, 1), 'OPERATOR');

    expect(actual?.dependencies).toHaveLength(1);
    expect(actual?.dependencies[0]).toMatchObject({
      dependencyId: 'expected-return-score',
      selector: { nodeId: '', operatorRef: 'risk:score' },
      behavior: { kind: 'RETURN', boundary: 'NODE', output: expected },
    });
    expect(actual?.dependencies[0]?.behavior.output).not.toBe(expected);
  });

  it('does not offer an expected Return fixture without one executable node and whole output', () => {
    const scenario = scenarioDraftSetFromCanvas(
      contractDraftFromGraphDraft(graphDraft(), fingerprint('a')).target,
      fingerprint('b'),
      graphDraft(),
      nodes(),
      [],
    ).scenarios[0];

    expect(upsertExpectedReturnDependency(scenario, nodes(), 'GRAPH')).toBeNull();
    expect(upsertExpectedReturnDependency({
      ...scenario,
      then: { assertions: [{ ...scenario.then.assertions[0], path: 'decision' }] },
    }, nodes().slice(0, 1), 'GRAPH')).toBeNull();
  });

  it('uses the operator selector for an operator-target Contract and updates it idempotently', () => {
    const scenario = scenarioDraftSetFromCanvas(
      contractDraftFromGraphDraft(graphDraft(), fingerprint('a')).target,
      fingerprint('b'),
      graphDraft(),
      nodes().slice(0, 1),
      [],
    ).scenarios[0];
    const source = {
      ...scenario,
      then: {
        assertions: [{
          ...scenario.then.assertions[0],
          expected: { action: 'review', steps: [], reason: 'operator target' },
        }],
      },
    };

    const first = upsertExpectedReturnDependency(source, nodes().slice(0, 1), 'OPERATOR');
    expect(first?.dependencies[0]).toMatchObject({
      dependencyId: 'expected-return-score',
      selector: { nodeId: '', operatorRef: 'risk:score' },
      behavior: { kind: 'RETURN', boundary: 'NODE' },
    });
    const second = upsertExpectedReturnDependency(first!, nodes().slice(0, 1), 'OPERATOR');
    expect(second?.dependencies).toHaveLength(1);
    expect(second?.dependencies[0]?.selector.nodeId).toBe('');
    expect(second?.dependencies[0]?.selector.operatorRef).toBe('risk:score');
  });
});

function fingerprint(seed: string): string {
  return `sha256:${seed.repeat(64).slice(0, 64)}`;
}
