import type {
  AssertionDraft,
  DependencyBehaviorDraft,
  ScenarioCaseType,
  ScenarioDraft,
  ScenarioDraftSet,
} from './domain';

export type TableDrivenBaselineSize = 5 | 50 | 500;

const CASE_TYPES: ScenarioCaseType[] = [
  'GOLDEN',
  'NEGATIVE',
  'BOUNDARY',
  'REGRESSION',
  'PROPERTY',
];

/**
 * Deterministic case corpus shared by projection, performance, browser, and compatibility gates.
 *
 * Every row deliberately exercises 20 Given fields, 8 controlled dependencies, and 12 assertions.
 * Weakening those dimensions is a product-baseline change and must be called out in the implementation
 * status rather than silently reducing the stress surface.
 */
export function tableDrivenScenarioBaseline(size: TableDrivenBaselineSize): ScenarioDraftSet {
  return {
    schemaVersion: 'bloge.scenarioDraftSet.v1',
    scenarioDraftSetId: `table-driven-baseline-${size}`,
    revision: 7,
    scope: {
      tenantId: 'baseline-tenant',
      organizationId: 'quality-engineering',
      projectId: 'table-driven-workbench',
      environment: 'test',
      region: 'local',
    },
    target: {
      kind: 'GRAPH',
      id: 'tableDrivenBaselineGraph',
      revision: 11,
      fingerprint: fingerprint('a'),
    },
    contractFingerprint: fingerprint('b'),
    scenarios: Array.from({ length: size }, (_, index) => scenario(index + 1, size)),
    metadata: {
      owner: 'resource-gateway-ux',
      classification: 'INTERNAL',
      createdAt: '2026-08-04T00:00:00Z',
      updatedAt: '2026-08-04T00:00:00Z',
      provenance: {
        source: 'table-driven-testing-baseline',
        size,
        givenFieldCount: 20,
        dependencyCount: 8,
        assertionCount: 12,
      },
    },
  };
}

function scenario(sequence: number, size: TableDrivenBaselineSize): ScenarioDraft {
  const width = String(size).length;
  const id = `case-${String(sequence).padStart(width, '0')}`;
  return {
    scenarioId: id,
    name: `${CASE_TYPES[(sequence - 1) % CASE_TYPES.length]} business case ${sequence}`,
    description: `Deterministic ${size}-row table baseline case ${sequence}.`,
    caseType: CASE_TYPES[(sequence - 1) % CASE_TYPES.length],
    tags: [
      sequence % 2 === 0 ? 'segment:business' : 'segment:consumer',
      `risk:${sequence % 3 === 0 ? 'high' : 'normal'}`,
    ],
    given: {
      input: Object.fromEntries(Array.from({ length: 20 }, (_, fieldIndex) => [
        `field${String(fieldIndex + 1).padStart(2, '0')}`,
        `${id}-value-${fieldIndex + 1}`,
      ])),
      provenance: sequence % 4 === 0 ? 'IMPORTED' : 'AUTHORED',
    },
    dependencies: Array.from({ length: 8 }, (_, dependencyIndex) => (
      dependency(id, sequence, dependencyIndex + 1)
    )),
    then: {
      assertions: Array.from({ length: 12 }, (_, assertionIndex) => (
        assertion(id, sequence, assertionIndex + 1)
      )),
    },
  };
}

function dependency(
  caseId: string,
  caseSequence: number,
  dependencySequence: number,
): DependencyBehaviorDraft {
  const kind = dependencyKind(dependencySequence);
  return {
    dependencyId: `${caseId}-dependency-${dependencySequence}`,
    selector: {
      graphPath: '/root',
      nodeId: `dependency-${dependencySequence}`,
      operatorRef: `baseline:operator-${dependencySequence}`,
      resourceRef: `baseline:resource-${dependencySequence}`,
      functionRef: '',
      attempts: dependencySequence === 4 ? [1, 2] : [],
      occurrences: [],
      correlationKey: '',
      pathEquals: dependencySequence === 1 ? { '$.caseSequence': caseSequence } : {},
    },
    behavior: behavior(kind, caseId, dependencySequence),
    consumption: {
      required: kind !== 'OBSERVE',
      minUses: kind === 'MUST_NOT_CALL' ? 0 : 1,
      maxUses: kind === 'MUST_NOT_CALL' ? 0 : dependencySequence === 4 ? 2 : 1,
      onExhausted: 'FAIL',
      onUnmatched: 'FAIL',
    },
    schemaCheck: { mode: 'STRICT', waiverReason: '' },
    origin: 'TABLE_DRIVEN_BASELINE',
  };
}

function dependencyKind(sequence: number): DependencyBehaviorDraft['behavior']['kind'] {
  const kinds: DependencyBehaviorDraft['behavior']['kind'][] = [
    'RETURN',
    'ERROR',
    'DELAY',
    'TIMEOUT',
    'REPLAY',
    'OBSERVE',
    'MUST_NOT_CALL',
    'RETURN',
  ];
  return kinds[sequence - 1];
}

function behavior(
  kind: DependencyBehaviorDraft['behavior']['kind'],
  caseId: string,
  sequence: number,
): DependencyBehaviorDraft['behavior'] {
  const base = { kind, boundary: 'NODE' as const };
  switch (kind) {
    case 'RETURN': return { ...base, output: { caseId, dependency: sequence, accepted: true } };
    case 'ERROR': return {
      ...base,
      errorCode: 'BASELINE_DEPENDENCY_ERROR',
      errorType: 'BusinessDependencyError',
      errorMessage: `Controlled error for ${caseId}`,
    };
    case 'DELAY': return { ...base, after: 'PT0.02S', output: { delayed: true } };
    case 'TIMEOUT': return { ...base, after: 'PT0.05S' };
    case 'REPLAY': return { ...base, replayRef: `replay:${caseId}:${sequence}` };
    case 'OBSERVE': return base;
    case 'MUST_NOT_CALL': return base;
    case 'REAL': return base;
  }
}

function assertion(caseId: string, caseSequence: number, sequence: number): AssertionDraft {
  if (sequence === 11) {
    return {
      assertionId: `${caseId}-assertion-${sequence}`,
      scope: 'NODE_STATUS',
      nodeId: 'decision',
      fromNodeId: '',
      toNodeId: '',
      path: '',
      operator: 'STATUS',
      expected: 'SUCCESS',
    };
  }
  if (sequence === 12) {
    return {
      assertionId: `${caseId}-assertion-${sequence}`,
      scope: 'INVOCATION',
      nodeId: 'dependency-7',
      fromNodeId: '',
      toNodeId: '',
      path: '',
      operator: 'NOT_USED',
    };
  }
  return {
    assertionId: `${caseId}-assertion-${sequence}`,
    scope: 'OUTPUT_PATH',
    nodeId: '',
    fromNodeId: '',
    toNodeId: '',
    path: `$.result.field${String(sequence).padStart(2, '0')}`,
    operator: 'EQUALS',
    expected: `${caseId}-expected-${caseSequence}-${sequence}`,
  };
}

function fingerprint(seed: string): string {
  return `sha256:${seed.repeat(64)}`;
}
