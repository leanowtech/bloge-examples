import type {
  VisualFunctionTestCase,
  VisualOperatorContractTestCase,
} from '../../types';
import type {
  AssertionDraft,
  ScenarioDraft,
  ScenarioDraftSet,
} from '../domain';
import {
  buildScenarioTableProjection,
  notRunEvidence,
  type ScenarioAssertionDiff,
  type ScenarioTableEvidenceByCase,
  type ScenarioTableProjection,
} from './scenarioTableModel';

interface AssetProjectionCoordinate {
  assetRef: string;
  revision: number;
  authoringFingerprint: string;
  artifactFingerprint: string;
}

export interface AssetCaseResultProjection {
  passed: boolean;
  status?: string;
  actual?: unknown;
  message: string;
}

export function operatorTestScenarioTableProjection(
  coordinate: AssetProjectionCoordinate,
  cases: VisualOperatorContractTestCase[],
  results: Record<number, AssetCaseResultProjection | undefined>,
  freshness: 'CURRENT' | 'STALE' = 'CURRENT',
): ScenarioTableProjection {
  return restrictAdapterEdits(buildScenarioTableProjection(
    assetDraftSet(coordinate, cases.map(operatorScenario), 'operator-contract-table'),
    assetEvidence(cases.length, results, 'SCHEMA', freshness, 'SCHEMA_ONLY'),
  ), false);
}

export function functionTestScenarioTableProjection(
  coordinate: AssetProjectionCoordinate,
  cases: VisualFunctionTestCase[],
  results: Record<number, AssetCaseResultProjection | undefined>,
  freshness: 'CURRENT' | 'STALE' = 'CURRENT',
): ScenarioTableProjection {
  return restrictAdapterEdits(buildScenarioTableProjection(
    assetDraftSet(coordinate, cases.map(functionScenario), 'function-test-table'),
    assetEvidence(
      cases.length,
      results,
      'RUNTIME',
      freshness,
      'REAL',
      (index, result) => functionAssertionDiff(cases[index], index, result),
    ),
  ), true);
}

function restrictAdapterEdits(
  projection: ScenarioTableProjection,
  caseTypeEditable: boolean,
): ScenarioTableProjection {
  return {
    ...projection,
    columns: projection.columns.map((column) => ({
      ...column,
      editable: column.binding.kind === 'NAME'
        || (caseTypeEditable && column.binding.kind === 'CASE_TYPE'),
    })),
  };
}

function assetDraftSet(
  coordinate: AssetProjectionCoordinate,
  scenarios: ScenarioDraft[],
  source: string,
): ScenarioDraftSet {
  return {
    schemaVersion: 'bloge.scenarioDraftSet.v1',
    scenarioDraftSetId: `${source}:${coordinate.assetRef}`,
    revision: coordinate.revision,
    scope: {
      tenantId: 'authoring',
      organizationId: 'library',
      projectId: 'asset-tests',
      environment: 'test',
      region: 'local',
    },
    target: {
      kind: 'OPERATOR',
      id: coordinate.assetRef,
      revision: coordinate.revision,
      fingerprint: coordinate.artifactFingerprint,
    },
    contractFingerprint: coordinate.authoringFingerprint,
    scenarios,
    metadata: {
      owner: 'library-author',
      classification: 'INTERNAL',
      createdAt: null,
      updatedAt: null,
      provenance: { source, projectionOnly: true },
    },
  };
}

function operatorScenario(testCase: VisualOperatorContractTestCase, index: number): ScenarioDraft {
  const assertions: AssertionDraft[] = [{
    assertionId: `operator-case-${index + 1}-output`,
    scope: 'OUTPUT_PATH',
    nodeId: '',
    fromNodeId: '',
    toNodeId: '',
    path: '',
    operator: 'EQUALS',
    expected: testCase.mockedOutputs,
  }];
  return {
    scenarioId: `operator-case-${index + 1}`,
    name: testCase.name || `Operator case ${index + 1}`,
    description: testCase.description,
    caseType: 'GOLDEN',
    tags: ['operator', 'legacy-adapter'],
    given: { input: testCase.inputs, provenance: 'MIGRATED' },
    dependencies: [{
      dependencyId: `operator-case-${index + 1}-config`,
      selector: {
        graphPath: '',
        nodeId: '',
        operatorRef: '',
        resourceRef: '',
        functionRef: '',
        attempts: [],
        occurrences: [],
        correlationKey: '',
        pathEquals: {},
      },
      behavior: { kind: 'RETURN', boundary: 'NODE', output: testCase.config },
      consumption: {
        required: false,
        minUses: 0,
        maxUses: 1,
        onExhausted: 'FAIL',
        onUnmatched: 'FAIL',
      },
      schemaCheck: { mode: 'STRICT', waiverReason: '' },
      origin: 'OPERATOR_CONFIG_ADAPTER',
    }],
    then: { assertions },
  };
}

function functionScenario(testCase: VisualFunctionTestCase, index: number): ScenarioDraft {
  const expected = testCase.assertion === 'EXPECT_ERROR'
    ? { errorCode: testCase.expectError?.code ?? '' }
    : testCase.expect;
  return {
    scenarioId: `function-case-${index + 1}`,
    name: testCase.id || `Function case ${index + 1}`,
    description: '',
    caseType: testCase.kind,
    tags: ['function', 'legacy-adapter'],
    given: {
      input: Object.fromEntries(testCase.args.map((argument, argumentIndex) => [
        `arg${argumentIndex + 1}`,
        argument,
      ])),
      provenance: 'MIGRATED',
    },
    dependencies: [],
    then: {
      assertions: [{
        assertionId: `function-case-${index + 1}-outcome`,
        scope: 'OUTPUT_PATH',
        nodeId: '',
        fromNodeId: '',
        toNodeId: '',
        path: testCase.assertion === 'EXPECT_ERROR' ? '$.error' : '',
        operator: testCase.assertion === 'RETURN_TYPE' ? 'MATCHES_SCHEMA' : 'EQUALS',
        expected,
      }],
    },
  };
}

function assetEvidence(
  caseCount: number,
  results: Record<number, AssetCaseResultProjection | undefined>,
  proofStrength: 'SCHEMA' | 'RUNTIME',
  freshness: 'CURRENT' | 'STALE',
  subjectMode: 'REAL' | 'SCHEMA_ONLY',
  assertionDiff?: (index: number, result: AssetCaseResultProjection) => ScenarioAssertionDiff[],
): ScenarioTableEvidenceByCase {
  return Object.fromEntries(Array.from({ length: caseCount }, (_, index) => {
    const result = results[index];
    const caseId = `${proofStrength === 'SCHEMA' ? 'operator' : 'function'}-case-${index + 1}`;
    if (!result) return [caseId, { ...notRunEvidence(caseId), subjectMode }];
    const notRun = result.status === 'NOT_RUN';
    return [caseId, {
      caseId,
      runId: `asset:${caseId}`,
      attempt: 1,
      execution: notRun ? 'SKIPPED' : 'SUCCESS',
      assertions: notRun ? 'NONE' : result.passed ? 'PASSED' : 'FAILED',
      freshness,
      proofStrength,
      subjectMode,
      durationMs: null,
      assertionDiffs: notRun ? [] : assertionDiff?.(index, result),
      firstFailure: result.passed ? null : {
        category: notRun ? 'BINDING' : 'ASSERTION',
        target: '/case',
        message: result.message,
      },
    }];
  }));
}

function functionAssertionDiff(
  testCase: VisualFunctionTestCase | undefined,
  index: number,
  result: AssetCaseResultProjection,
): ScenarioAssertionDiff[] {
  if (!testCase) return [];
  const expected = testCase.assertion === 'EXPECT_ERROR'
    ? { errorCode: testCase.expectError?.code ?? '' }
    : testCase.assertion === 'RETURN_TYPE'
      ? 'Declared return schema'
      : testCase.expect;
  return [{
    assertionId: `function-case-${index + 1}-outcome`,
    path: testCase.assertion === 'EXPECT_ERROR' ? '$.error' : '$',
    passed: result.passed,
    expected,
    actual: result.actual,
    detail: result.message,
  }];
}
