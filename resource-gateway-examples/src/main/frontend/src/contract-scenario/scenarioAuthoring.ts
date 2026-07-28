import type {
  GraphDraft,
  NodeFixture,
  SchemaEnvelope,
  SimulationResponse,
} from '../types';
import { sampleFromSchemaEnvelope, type SimulationTableTestCase } from '../draftModel';
import type {
  AssertionDraft,
  DependencyBehaviorDraft,
  ExactTargetRef,
  ScenarioDiagnostic,
  ScenarioDraft,
  ScenarioDraftSet,
} from './domain';
import { emptyScenarioDraftSet } from './domain';
import { valueAtPath } from './schemaWorkbench';

export interface ScenarioNodeOption {
  id: string;
  label: string;
  operatorRef: string;
  inputSchema?: SchemaEnvelope;
  outputSchema?: SchemaEnvelope;
}

export interface ScenarioAssertionResult {
  assertionId: string;
  passed: boolean;
  path: string;
  expected: unknown;
  actual: unknown;
  detail: string;
}

export interface ScenarioComparison {
  passed: boolean;
  results: ScenarioAssertionResult[];
  diagnostics: ScenarioDiagnostic[];
}

/** Projects existing canvas table tests into first-class Scenario drafts for immediate discoverability. */
export function scenarioDraftSetFromCanvas(
  target: ExactTargetRef,
  contractFingerprint: string,
  graphDraft: GraphDraft,
  nodes: ScenarioNodeOption[],
  tableCases: SimulationTableTestCase[],
): ScenarioDraftSet {
  const draftSet = emptyScenarioDraftSet(target, contractFingerprint, {
    tenantId: graphDraft.tenantId || 'tenant-a',
    organizationId: 'knowledge-governance',
    projectId: 'tool-studio',
    environment: 'test',
    region: 'local',
  });
  const cases = tableCases.length > 0
    ? tableCases
    : [{
        id: 'happy-path',
        name: 'Happy path',
        context: sampleObject(graphDraft.inputSchema),
        fixtures: {},
        hasExpectedOutput: Boolean(graphDraft.outputSchema),
        expectedOutput: sampleFromSchemaEnvelope(graphDraft.outputSchema),
      }];
  return {
    ...draftSet,
    scenarioDraftSetId: `${graphDraft.graphName}-scenarios`,
    scenarios: cases.map((testCase) => scenarioFromTableCase(
      testCase,
      nodes,
      graphDraft.nodeFixtures ?? {},
    )),
    metadata: {
      ...draftSet.metadata,
      owner: 'canvas-author',
      provenance: { source: 'canvas-table-tests' },
    },
  };
}

/** Explicitly rebases mutable Scenarios after the author has reviewed a changed graph Contract. */
export function rebaseScenarioDraftSet(
  draftSet: ScenarioDraftSet,
  target: ExactTargetRef,
  contractFingerprint: string,
): ScenarioDraftSet {
  return {
    ...draftSet,
    target: { ...target },
    contractFingerprint,
    metadata: {
      ...draftSet.metadata,
      updatedAt: new Date().toISOString(),
      provenance: {
        ...draftSet.metadata.provenance,
        rebasedFromTargetFingerprint: draftSet.target.fingerprint,
      },
    },
  };
}

/** Creates a graphical Scenario row using contract-derived samples. */
export function newScenarioDraft(
  sequence: number,
  graphDraft: GraphDraft,
  nodes: ScenarioNodeOption[],
): ScenarioDraft {
  return {
    scenarioId: `scenario-${sequence}`,
    name: `Scenario ${sequence}`,
    description: '',
    caseType: 'GOLDEN',
    tags: [],
    given: {
      input: sampleObject(graphDraft.inputSchema),
      provenance: 'GENERATED',
    },
    dependencies: nodes.map((node) => dependencyForNode(node, graphDraft.nodeFixtures?.[node.id])),
    then: {
      assertions: [],
    },
  };
}

/** Compares terminal output against the exact assertions authored in one Scenario. */
export function compareScenarioRun(
  scenario: ScenarioDraft,
  response: SimulationResponse,
): ScenarioComparison {
  const diagnostics: ScenarioDiagnostic[] = [];
  if (!response.success || !response.validated || !response.compiled) {
    diagnostics.push({
      level: 'ERROR',
      code: 'visual.scenario.run.failed',
      message: response.errors?.[0] ?? 'Simulation did not complete successfully.',
      target: '/run',
    });
  }
  const results = scenario.then.assertions.map((assertion) => compareAssertion(assertion, response));
  return {
    passed: diagnostics.length === 0 && results.every((result) => result.passed),
    results,
    diagnostics,
  };
}

/** Returns true when the mutable Scenario asset still addresses the exact current Contract. */
export function scenarioSetIsCurrent(
  draftSet: ScenarioDraftSet,
  targetFingerprint: string,
  contractFingerprint: string,
): boolean {
  return draftSet.target.fingerprint === targetFingerprint
    && draftSet.contractFingerprint === contractFingerprint;
}

function scenarioFromTableCase(
  testCase: SimulationTableTestCase,
  nodes: ScenarioNodeOption[],
  baseFixtures: Record<string, NodeFixture>,
): ScenarioDraft {
  const fixtures = { ...baseFixtures, ...testCase.fixtures };
  return {
    scenarioId: testCase.id,
    name: testCase.name,
    description: 'Projected from the canvas test table.',
    caseType: 'GOLDEN',
    tags: ['canvas-example'],
    given: {
      input: { ...testCase.context },
      provenance: 'MIGRATED',
    },
    dependencies: nodes.map((node) => dependencyForNode(node, fixtures[node.id])),
    then: {
      assertions: testCase.hasExpectedOutput
        ? [outputAssertion(`${testCase.id}-output`, '', testCase.expectedOutput)]
        : [],
    },
  };
}

function dependencyForNode(
  node: ScenarioNodeOption,
  fixture: NodeFixture | undefined,
): DependencyBehaviorDraft {
  return {
    dependencyId: `${node.id}-behavior`,
    selector: {
      graphPath: '',
      nodeId: node.id,
      operatorRef: '',
      resourceRef: '',
      functionRef: '',
      attempts: [],
      occurrences: [],
      correlationKey: '',
      pathEquals: {},
    },
    behavior: fixture
      ? {
          kind: 'RETURN',
          boundary: 'NODE',
          output: fixture.output,
          ...(fixture.expectedInput !== undefined ? { expectedInput: fixture.expectedInput } : {}),
        }
      : {
          kind: 'REAL',
          boundary: 'NODE',
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
    origin: 'CANVAS',
  };
}

function outputAssertion(assertionId: string, path: string, expected: unknown): AssertionDraft {
  return {
    assertionId,
    scope: 'OUTPUT_PATH',
    nodeId: '',
    fromNodeId: '',
    toNodeId: '',
    path,
    operator: 'EQUALS',
    expected,
  };
}

function compareAssertion(
  assertion: AssertionDraft,
  response: SimulationResponse,
): ScenarioAssertionResult {
  if (assertion.scope !== 'OUTPUT_PATH' || assertion.operator !== 'EQUALS') {
    return {
      assertionId: assertion.assertionId,
      passed: false,
      path: assertion.path,
      expected: assertion.expected,
      actual: undefined,
      detail: `${assertion.scope}/${assertion.operator} requires governed Scenario execution.`,
    };
  }
  const actual = valueAtPath(response.output, assertion.path);
  const passed = jsonEqual(actual, assertion.expected);
  return {
    assertionId: assertion.assertionId,
    passed,
    path: assertion.path,
    expected: assertion.expected,
    actual,
    detail: passed ? 'Matched.' : 'Expected and actual values differ.',
  };
}

function sampleObject(envelope: SchemaEnvelope | undefined): Record<string, unknown> {
  const sample = envelope ? sampleFromSchemaEnvelope(envelope) : {};
  return sample !== null && typeof sample === 'object' && !Array.isArray(sample)
    ? sample as Record<string, unknown>
    : {};
}

function jsonEqual(left: unknown, right: unknown): boolean {
  return JSON.stringify(left) === JSON.stringify(right);
}
