import { describe, expect, it } from 'vitest';

import type { DependencyBehaviorDraft, ScenarioDraftSet } from '../../contract-scenario/domain';
import type { GraphDraft } from '../../types';
import { projectScenarioRunPreflight } from './preflightRiskProjection';

describe('Scenario run preflight risk projection', () => {
  it('admits a non-production read whose every node is explicitly mocked', () => {
    const projection = project('READ', [dependency('score', 'RETURN'), dependency('decide', 'RETURN')]);

    expect(projection).toMatchObject({
      status: 'SAFE',
      selectedCaseCount: 1,
      counts: { invocations: 2, subjectReal: 0, real: 0, mocked: 2, fallbackToReal: 0, missingOracle: 0 },
      reasons: [],
    });
  });

  it('resolves a unique operator selector to the executable node for a mocked preflight', () => {
    const projection = projectScenarioRunPreflight({
      graphDraft: graph(),
      draftSet: draftSet([operatorDependency('risk:score', 'RETURN')]),
      targetEffect: 'READ',
      caseIds: ['case-a'],
    });

    expect(projection.status).toBe('SAFE');
    expect(projection.counts).toMatchObject({ mocked: 1, subjectReal: 1, real: 0, fallbackToReal: 0 });
    expect(reasonCodes(projection)).not.toContain('UNRESOLVED_DEPENDENCY');
    expect(reasonCodes(projection)).not.toContain('TRANSIENT_RUNTIME_UNSUPPORTED');
    expect(projection.invocationGroups).toContainEqual(expect.objectContaining({
      nodeId: 'score', operatorRef: 'risk:score', mode: 'MOCKED',
    }));
  });

  it('fails closed for an unknown operator selector', () => {
    const projection = projectScenarioRunPreflight({
      graphDraft: graph(),
      draftSet: draftSet([operatorDependency('risk:missing', 'RETURN')]),
      targetEffect: 'READ',
      caseIds: ['case-a'],
    });

    expect(projection.status).toBe('BLOCKED');
    expect(reasonCodes(projection)).toContain('UNRESOLVED_DEPENDENCY');
  });

  it('fails closed when an operator selector matches more than one node', () => {
    const graphDraft = graph();
    graphDraft.nodes.push({ id: 'score-copy', operatorRef: 'risk:score' });
    const projection = projectScenarioRunPreflight({
      graphDraft,
      draftSet: draftSet([operatorDependency('risk:score', 'RETURN')]),
      targetEffect: 'READ',
      caseIds: ['case-a'],
    });

    expect(projection.status).toBe('BLOCKED');
    expect(reasonCodes(projection)).toContain('UNRESOLVED_DEPENDENCY');
  });

  it('finds implicit, explicit, observed, and fallback real-call paths before execution', () => {
    const real = dependency('score', 'REAL');
    real.consumption.onUnmatched = 'ALLOW_REAL';
    const projection = project('READ', [real, dependency('decide', 'OBSERVE')]);

    expect(projection.status).toBe('BLOCKED');
    expect(projection.counts).toMatchObject({ real: 1, observe: 1, fallbackToReal: 1 });
    expect(reasonCodes(projection)).toEqual(expect.arrayContaining([
      'REAL_DEPENDENCY',
      'OBSERVED_REAL_DEPENDENCY',
      'REAL_FALLBACK',
    ]));
  });

  it('treats an unconfigured graph node as an implicit real invocation', () => {
    const graphDraft = graph();
    graphDraft.output = { nodeId: 'score' };
    const projection = projectScenarioRunPreflight({
      graphDraft,
      draftSet: draftSet([dependency('score', 'RETURN')]),
      targetEffect: 'READ',
      caseIds: ['case-a'],
    });

    expect(projection.counts).toMatchObject({ mocked: 1, real: 1 });
    expect(projection.status).toBe('REVIEW');
    expect(projection.invocationGroups).toContainEqual(expect.objectContaining({
      nodeId: 'decide',
      mode: 'REAL',
      source: 'UNCONTROLLED',
    }));
  });

  it('requires review rather than blocking a non-production real read without fallback', () => {
    const projection = project('READ', [dependency('score', 'REAL'), dependency('decide', 'RETURN')]);

    expect(projection.status).toBe('REVIEW');
    expect(projection.reasons).toContainEqual(expect.objectContaining({
      code: 'REAL_DEPENDENCY',
      severity: 'WARNING',
    }));
  });

  it('reports the real subject separately without treating it as a real dependency', () => {
    const projection = project('READ', [dependency('score', 'RETURN')]);

    expect(projection.status).toBe('SAFE');
    expect(projection.counts).toMatchObject({ subjectReal: 1, real: 0, mocked: 1 });
    expect(reasonCodes(projection)).not.toContain('REAL_DEPENDENCY');
    expect(projection.invocationGroups).toContainEqual(expect.objectContaining({
      nodeId: 'decide',
      mode: 'REAL',
      source: 'SUBJECT',
    }));
  });

  it('honors persisted Graph fixtures without exposing their material', () => {
    const graphDraft = graph();
    graphDraft.nodeFixtures = { decide: { output: { secret: 'DO-NOT-LEAK' } } };
    const projection = projectScenarioRunPreflight({
      graphDraft,
      draftSet: draftSet([dependency('score', 'RETURN')]),
      targetEffect: 'READ',
      caseIds: ['case-a'],
    });

    expect(projection.status).toBe('SAFE');
    expect(projection.counts.mocked).toBe(2);
    expect(JSON.stringify(projection)).not.toContain('DO-NOT-LEAK');
  });

  it('blocks production, write targets, and cases without a business oracle', () => {
    const source = draftSet([dependency('score', 'RETURN'), dependency('decide', 'RETURN')]);
    source.scope.environment = 'customer-prod-sg';
    source.scenarios[0].then.assertions = [];
    const projection = projectScenarioRunPreflight({
      graphDraft: graph(),
      draftSet: source,
      targetEffect: 'WRITE',
      caseIds: ['case-a'],
    });

    expect(reasonCodes(projection)).toEqual(expect.arrayContaining([
      'PRODUCTION_ENVIRONMENT', 'TARGET_WRITE_EFFECT', 'MISSING_ORACLE',
    ]));
    expect(projection.counts.missingOracle).toBe(1);
  });

  it('requires review for an unknown target effect without blocking a fully mocked run', () => {
    const projection = project('UNKNOWN', [
      dependency('score', 'RETURN'), dependency('decide', 'RETURN'),
    ]);

    expect(projection.status).toBe('REVIEW');
    expect(reasonCodes(projection)).toEqual(['TARGET_EFFECT_UNKNOWN']);
  });

  it('classifies advanced controls and blocks unsupported transient execution', () => {
    const projection = project('READ', [dependency('score', 'ERROR'), dependency('decide', 'REPLAY')]);

    expect(projection.counts).toMatchObject({ fault: 1, replay: 1 });
    expect(reasonCodes(projection)).toContain('TRANSIENT_RUNTIME_UNSUPPORTED');
  });

  it('fails closed for empty, unknown, and unresolved selections without leaking ids', () => {
    const empty = projectScenarioRunPreflight({
      graphDraft: graph(), draftSet: draftSet([]), targetEffect: 'READ', caseIds: [],
    });
    const unknown = projectScenarioRunPreflight({
      graphDraft: graph(), draftSet: draftSet([]), targetEffect: 'READ', caseIds: ['secret-case-id'],
    });

    expect(reasonCodes(empty)).toContain('EMPTY_SELECTION');
    expect(reasonCodes(unknown)).toContain('UNKNOWN_CASE_SELECTION');
    expect(JSON.stringify(unknown)).not.toContain('secret-case-id');
  });
});

function project(
  effect: 'PURE' | 'READ' | 'WRITE' | 'UNKNOWN',
  dependencies: DependencyBehaviorDraft[],
) {
  return projectScenarioRunPreflight({
    graphDraft: graph(),
    draftSet: draftSet(dependencies),
    targetEffect: effect,
    caseIds: ['case-a'],
  });
}

function graph(): GraphDraft {
  return {
    schemaVersion: 'bloge.visualGraphDraft.v1',
    draftId: 'graph-a',
    revision: 1,
    graphName: 'graph-a',
    nodes: [
      { id: 'score', operatorRef: 'risk:score' },
      { id: 'decide', operatorRef: 'risk:decide' },
    ],
    edges: [],
    output: { nodeId: 'decide' },
  };
}

function draftSet(dependencies: DependencyBehaviorDraft[]): ScenarioDraftSet {
  return {
    schemaVersion: 'bloge.scenarioDraftSet.v1',
    scenarioDraftSetId: 'scenarios-a',
    revision: 1,
    scope: {
      tenantId: 'tenant-a', organizationId: 'org-a', projectId: 'project-a',
      environment: 'test', region: 'sg',
    },
    target: { kind: 'GRAPH', id: 'graph-a', revision: 1, fingerprint: fingerprint('a') },
    contractFingerprint: fingerprint('b'),
    scenarios: [{
      scenarioId: 'case-a',
      name: 'Case A',
      description: '',
      caseType: 'GOLDEN',
      tags: [],
      given: { input: { secret: 'BUSINESS-PAYLOAD' }, provenance: 'AUTHORED' },
      dependencies,
      then: {
        assertions: [{
          assertionId: 'assertion-a', scope: 'OUTPUT_PATH', nodeId: '', fromNodeId: '',
          toNodeId: '', path: '$.approved', operator: 'EQUALS', expected: true,
        }],
      },
    }],
    metadata: {
      owner: 'owner-a', classification: 'INTERNAL', createdAt: null, updatedAt: null,
      provenance: {},
    },
  };
}

function dependency(
  nodeId: string,
  kind: DependencyBehaviorDraft['behavior']['kind'],
): DependencyBehaviorDraft {
  return {
    dependencyId: `dependency-${nodeId}`,
    selector: {
      graphPath: '', nodeId, operatorRef: '', resourceRef: '', functionRef: '', attempts: [],
      occurrences: [], correlationKey: '', pathEquals: {},
    },
    behavior: { kind, boundary: 'NODE', output: { secret: 'FIXTURE-PAYLOAD' } },
    consumption: { required: true, minUses: 1, maxUses: 1, onExhausted: 'FAIL', onUnmatched: 'FAIL' },
    schemaCheck: { mode: 'STRICT', waiverReason: '' },
    origin: 'TEST',
  };
}

function operatorDependency(
  operatorRef: string,
  kind: DependencyBehaviorDraft['behavior']['kind'],
): DependencyBehaviorDraft {
  return {
    ...dependency('', kind),
    dependencyId: `dependency-${operatorRef}`,
    selector: {
      ...dependency('', kind).selector,
      nodeId: '',
      operatorRef,
    },
  };
}

function reasonCodes(projection: ReturnType<typeof projectScenarioRunPreflight>): string[] {
  return projection.reasons.map((reason) => reason.code);
}

function fingerprint(seed: string): string {
  return `sha256:${seed.repeat(64)}`;
}
