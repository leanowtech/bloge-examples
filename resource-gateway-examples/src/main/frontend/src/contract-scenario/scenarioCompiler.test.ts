import { describe, expect, it } from 'vitest';
import type { GraphDraft } from '../types';
import {
  contractDraftFromGraphDraft,
  emptyScenarioDraftSet,
  type DependencyBehaviorDraft,
  type ScenarioDraft,
  type ScenarioDraftSet,
} from './domain';
import {
  compileScenarioEditorSnapshotForSimulation,
  compileScenarioForSimulation,
  verifyScenarioCompilationProof,
} from './scenarioCompiler';
import { captureScenarioEditorSnapshot } from './scenarioEditorModel';
import { sha256Fingerprint } from './fingerprint';
import type { ScenarioNodeOption } from './scenarioAuthoring';

const TARGET_FINGERPRINT = `sha256:${'a'.repeat(64)}`;
const CONTRACT_FINGERPRINT = `sha256:${'b'.repeat(64)}`;

describe('Contract and Scenario authoring domain', () => {
  it('projects graph schemas while preserving unknown execution semantics', () => {
    const contract = contractDraftFromGraphDraft(graphDraft(), TARGET_FINGERPRINT);

    expect(contract.target).toEqual({
      kind: 'GRAPH',
      id: 'draft-a',
      revision: 4,
      fingerprint: TARGET_FINGERPRINT,
    });
    expect(contract.inputSchema.schema).toMatchObject({
      type: 'object',
      required: ['applicantId'],
    });
    expect(contract.executionSemantics).toEqual({
      effect: 'UNKNOWN',
      idempotency: 'UNKNOWN',
      streaming: null,
      durable: null,
    });
    expect(contract.confidence).toBe('EXACT');
  });

  it('creates a separate mutable Scenario asset with complete enterprise scope', () => {
    const target = contractDraftFromGraphDraft(graphDraft(), TARGET_FINGERPRINT).target;
    const draftSet = emptyScenarioDraftSet(target, CONTRACT_FINGERPRINT, {
      tenantId: 'tenant-a',
      organizationId: 'org-a',
      projectId: 'project-a',
      environment: 'test',
      region: 'sg',
    });

    expect(draftSet.schemaVersion).toBe('bloge.scenarioDraftSet.v1');
    expect(draftSet.scope).toMatchObject({
      tenantId: 'tenant-a',
      organizationId: 'org-a',
      projectId: 'project-a',
    });
    expect(draftSet.scenarios).toEqual([]);
  });
});

describe('Scenario transient compiler', () => {
  it('binds editor, plan, request source, and request material to one canonical snapshot', async () => {
    const graph = graphDraft();
    const scenarios = draftSet([returnDependency()]);
    const contract = contractDraftFromGraphDraft(graph, TARGET_FINGERPRINT);
    const snapshot = captureScenarioEditorSnapshot(
      scenarios,
      'fallback',
      contract,
      scenarioNodes(),
    );

    const result = await compileScenarioEditorSnapshotForSimulation(
      graph,
      snapshot,
      TARGET_FINGERPRINT,
      CONTRACT_FINGERPRINT,
    );

    expect(result.compiled).toBe(true);
    expect(result.proof).toMatchObject({
      editorSnapshotFingerprint: expect.stringMatching(/^sha256:/),
      compiledPlanSourceFingerprint: expect.stringMatching(/^sha256:/),
      requestSourceFingerprint: expect.stringMatching(/^sha256:/),
      evidenceSourceFingerprint: expect.stringMatching(/^sha256:/),
    });
    expect(new Set([
      result.proof?.editorSnapshotFingerprint,
      result.proof?.compiledPlanSourceFingerprint,
      result.proof?.requestSourceFingerprint,
      result.proof?.evidenceSourceFingerprint,
    ]).size).toBe(1);
    expect(result.proof?.requestFingerprint).toBe(await sha256Fingerprint(result.request));
  });

  it('accepts evidence only while the visible editor and exact request retain fingerprint closure', async () => {
    const graph = graphDraft();
    const scenarios = draftSet([returnDependency()]);
    const contract = contractDraftFromGraphDraft(graph, TARGET_FINGERPRINT);
    const result = await compileScenarioEditorSnapshotForSimulation(
      graph,
      captureScenarioEditorSnapshot(scenarios, 'fallback', contract, scenarioNodes()),
      TARGET_FINGERPRINT,
      CONTRACT_FINGERPRINT,
    );
    const proof = result.proof!;

    expect(verifyScenarioCompilationProof(
      proof,
      proof.editorSnapshotFingerprint,
      await sha256Fingerprint(result.request),
    )).toEqual({ valid: true, reasonCode: '', message: '' });
    expect(verifyScenarioCompilationProof(
      proof,
      `sha256:${'c'.repeat(64)}`,
      proof.requestFingerprint,
    )).toMatchObject({
      valid: false,
      reasonCode: 'RG.AUTHOR.EVIDENCE.SOURCE_CHANGED',
    });
    expect(verifyScenarioCompilationProof(
      proof,
      proof.editorSnapshotFingerprint,
      `sha256:${'d'.repeat(64)}`,
    )).toMatchObject({
      valid: false,
      reasonCode: 'RG.AUTHOR.EVIDENCE.REQUEST_CHANGED',
    });
  });

  it('blocks an empty required Return field instead of allowing runtime sample generation', async () => {
    const graph = graphDraft();
    const dependency = returnDependency();
    dependency.behavior.output = { score: '' };
    const scenarios = draftSet([dependency]);
    const contract = contractDraftFromGraphDraft(graph, TARGET_FINGERPRINT);
    const snapshot = captureScenarioEditorSnapshot(
      scenarios,
      'fallback',
      contract,
      scenarioNodes(true),
    );

    const result = await compileScenarioEditorSnapshotForSimulation(
      graph,
      snapshot,
      TARGET_FINGERPRINT,
      CONTRACT_FINGERPRINT,
    );

    expect(result.compiled).toBe(false);
    expect(result.request).toBeUndefined();
    expect(result.diagnostics).toContainEqual(expect.objectContaining({
      code: 'visual.scenario.return.requiredValueMissing',
      target: '/dependencies/crm-return/behavior/output/score',
    }));
  });

  it('compiles exact node RETURN and Expected Result into the existing simulation request', () => {
    const result = compileScenarioForSimulation(
      graphDraft(),
      draftSet([returnDependency()]),
      'fallback',
      TARGET_FINGERPRINT,
      CONTRACT_FINGERPRINT,
    );

    expect(result.compiled).toBe(true);
    expect(result.request?.context).toEqual({ applicantId: 'A-1' });
    expect(result.request?.fixtures?.crm).toEqual({
      output: { score: 720 },
      expectedInput: { applicantId: 'A-1' },
    });
    expect(result.assertions.map((assertion) => assertion.assertionId))
      .toEqual(['decision-approved']);
  });

  it('removes a persisted fixture when the Scenario explicitly requests REAL behavior', () => {
    const real = returnDependency();
    real.behavior = { kind: 'REAL', boundary: 'NODE' };

    const result = compileScenarioForSimulation(
      graphDraft(),
      draftSet([real]),
      'fallback',
      TARGET_FINGERPRINT,
      CONTRACT_FINGERPRINT,
    );

    expect(result.compiled).toBe(true);
    expect(result.request?.draft.nodeFixtures).toEqual({});
    expect(result.request?.fixtures).toBeUndefined();
  });

  it('runs an Operator target through its exact one-node executable projection', () => {
    const graph: GraphDraft = {
      ...graphDraft(),
      draftId: undefined,
      revision: undefined,
      graphName: 'operator-risk-score',
      nodes: [{ id: 'operator', operatorRef: 'risk:score', label: 'Risk score' }],
      edges: [],
      output: { nodeId: 'operator' },
      nodeFixtures: {},
    };
    const scenarios: ScenarioDraftSet = {
      ...draftSet([]),
      target: {
        kind: 'OPERATOR',
        id: 'risk:score',
        revision: 7,
        fingerprint: TARGET_FINGERPRINT,
      },
    };

    const result = compileScenarioForSimulation(
      graph,
      scenarios,
      'fallback',
      TARGET_FINGERPRINT,
      CONTRACT_FINGERPRINT,
    );

    expect(result.compiled).toBe(true);
    expect(result.request?.draft.nodes).toEqual([
      expect.objectContaining({ id: 'operator', operatorRef: 'risk:score' }),
    ]);
    expect(result.request?.context).toEqual({ applicantId: 'A-1' });
  });

  it('rejects an Operator projection for a different catalog coordinate', () => {
    const scenarios: ScenarioDraftSet = {
      ...draftSet([]),
      target: {
        kind: 'OPERATOR',
        id: 'risk:other',
        revision: 1,
        fingerprint: TARGET_FINGERPRINT,
      },
    };

    const result = compileScenarioForSimulation(
      graphDraft(),
      scenarios,
      'fallback',
      TARGET_FINGERPRINT,
      CONTRACT_FINGERPRINT,
    );

    expect(result.compiled).toBe(false);
    expect(result.diagnostics.map((diagnostic) => diagnostic.code))
      .toContain('visual.scenario.target.operatorIdMismatch');
  });

  it.each(['ERROR', 'DELAY', 'TIMEOUT', 'REPLAY', 'OBSERVE', 'MUST_NOT_CALL'] as const)(
    'fails closed for advanced %s behavior',
    (kind) => {
      const advanced = returnDependency();
      advanced.behavior = { kind, boundary: 'NODE' };

      const result = compileScenarioForSimulation(
        graphDraft(),
        draftSet([advanced]),
        'fallback',
        TARGET_FINGERPRINT,
        CONTRACT_FINGERPRINT,
      );

      expect(result.compiled).toBe(false);
      expect(result.request).toBeUndefined();
      expect(result.diagnostics.map((diagnostic) => diagnostic.code))
        .toContain('visual.scenario.compile.governedBehaviorRequired');
    },
  );

  it('reports stale target, stale contract, and missing Scenario together', () => {
    const result = compileScenarioForSimulation(
      graphDraft(),
      draftSet([returnDependency()]),
      'missing',
      `sha256:${'c'.repeat(64)}`,
      `sha256:${'d'.repeat(64)}`,
    );

    expect(result.compiled).toBe(false);
    expect(result.diagnostics.map((diagnostic) => diagnostic.code)).toEqual(expect.arrayContaining([
      'visual.scenario.target.fingerprintStale',
      'visual.scenario.contract.stale',
      'visual.scenario.compile.scenarioMissing',
    ]));
  });

  it('rejects transport selectors and duplicate controls instead of applying ambiguous overrides', () => {
    const first = returnDependency();
    const transport = returnDependency();
    transport.dependencyId = 'crm-transport';
    transport.behavior.boundary = 'TRANSPORT';

    const result = compileScenarioForSimulation(
      graphDraft(),
      draftSet([first, transport]),
      'fallback',
      TARGET_FINGERPRINT,
      CONTRACT_FINGERPRINT,
    );

    expect(result.compiled).toBe(false);
    expect(result.diagnostics.map((diagnostic) => diagnostic.code))
      .toContain('visual.scenario.compile.governedBehaviorRequired');
  });
});

function graphDraft(): GraphDraft {
  return {
    schemaVersion: 'bloge.visualGraphDraft.v1',
    draftId: 'draft-a',
    revision: 4,
    graphName: 'loanPolicy',
    tenantId: 'tenant-a',
    namespace: 'local',
    environment: 'test',
    inputSchema: {
      format: 'json-schema',
      version: '2020-12',
      schema: {
        type: 'object',
        properties: { applicantId: { type: 'string' } },
        required: ['applicantId'],
        additionalProperties: false,
      },
    },
    outputSchema: {
      format: 'json-schema',
      version: '2020-12',
      schema: {
        type: 'object',
        properties: { decision: { type: 'string' } },
        required: ['decision'],
        additionalProperties: false,
      },
    },
    nodes: [
      { id: 'crm', operatorRef: 'crm:lookup', label: 'CRM' },
      { id: 'decision', operatorRef: 'bloge:transform', label: 'Decision' },
    ],
    edges: [{
      id: 'crm-decision',
      kind: 'data',
      source: { nodeId: 'crm', port: 'profile' },
      target: { nodeId: 'decision', port: 'applicant' },
    }],
    output: { nodeId: 'decision' },
    nodeFixtures: { crm: { output: { score: 600 } } },
  };
}

function draftSet(dependencies: DependencyBehaviorDraft[]): ScenarioDraftSet {
  const target = contractDraftFromGraphDraft(graphDraft(), TARGET_FINGERPRINT).target;
  const scenario: ScenarioDraft = {
    scenarioId: 'fallback',
    name: 'CRM fallback',
    description: 'Return a controlled CRM response.',
    caseType: 'REGRESSION',
    tags: ['crm'],
    given: {
      input: { applicantId: 'A-1' },
      provenance: 'AUTHORED',
    },
    dependencies,
    then: {
      assertions: [{
        assertionId: 'decision-approved',
        scope: 'OUTPUT_PATH',
        nodeId: '',
        fromNodeId: '',
        toNodeId: '',
        path: '/decision',
        operator: 'EQUALS',
        expected: 'APPROVED',
      }],
    },
  };
  return {
    schemaVersion: 'bloge.scenarioDraftSet.v1',
    scenarioDraftSetId: 'loan-scenarios',
    revision: 3,
    scope: {
      tenantId: 'tenant-a',
      organizationId: 'org-a',
      projectId: 'project-a',
      environment: 'test',
      region: 'sg',
    },
    target,
    contractFingerprint: CONTRACT_FINGERPRINT,
    scenarios: [scenario],
    metadata: {
      owner: 'credit-platform',
      classification: 'INTERNAL',
      createdAt: null,
      updatedAt: null,
      provenance: {},
    },
  };
}

function returnDependency(): DependencyBehaviorDraft {
  return {
    dependencyId: 'crm-return',
    selector: {
      graphPath: '/root',
      nodeId: 'crm',
      operatorRef: '',
      resourceRef: '',
      functionRef: '',
      attempts: [],
      occurrences: [],
      correlationKey: '',
      pathEquals: {},
    },
    behavior: {
      kind: 'RETURN',
      boundary: 'NODE',
      output: { score: 720 },
      expectedInput: { applicantId: 'A-1' },
    },
    consumption: {
      required: true,
      minUses: 1,
      maxUses: 1,
      onExhausted: 'FAIL',
      onUnmatched: 'FAIL',
    },
    schemaCheck: {
      mode: 'STRICT',
      waiverReason: '',
    },
    origin: 'AUTHORED',
  };
}

function scenarioNodes(requiredScore = false): ScenarioNodeOption[] {
  return [{
    id: 'crm',
    label: 'CRM',
    operatorRef: 'crm:lookup',
    outputSchema: {
      format: 'json-schema',
      version: '2020-12',
      schema: {
        type: 'object',
        properties: { score: { type: requiredScore ? 'string' : 'integer' } },
        required: requiredScore ? ['score'] : [],
      },
    },
  }, {
    id: 'decision',
    label: 'Decision',
    operatorRef: 'bloge:transform',
  }];
}
