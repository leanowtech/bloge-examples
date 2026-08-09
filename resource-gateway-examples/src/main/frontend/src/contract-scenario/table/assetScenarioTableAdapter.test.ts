import { describe, expect, it } from 'vitest';

import type { VisualFunctionTestCase, VisualOperatorContractTestCase } from '../../types';
import {
  functionTestScenarioTableProjection,
  operatorTestScenarioTableProjection,
} from './assetScenarioTableAdapter';

const coordinate = {
  assetRef: 'demo:normalize',
  revision: 3,
  authoringFingerprint: fingerprint('a'),
  artifactFingerprint: fingerprint('b'),
};

describe('asset table Scenario adapters', () => {
  it('projects operator inputs, configuration, mocked output, and schema proof', () => {
    const testCase: VisualOperatorContractTestCase = {
      schemaVersion: 'bloge.visualOperatorContractTestCase.v1',
      name: 'Valid request',
      description: 'Checks the generated contract.',
      inputs: { request: 'hello' },
      config: { timeoutMs: 40 },
      mockedOutputs: { result: 'HELLO' },
      outputAssertions: {},
    };
    const projection = operatorTestScenarioTableProjection(
      coordinate,
      [testCase],
      { 0: { passed: true, message: 'Schema valid.' } },
    );

    expect(projection.rows[0]).toMatchObject({
      caseId: 'operator-case-1',
      name: 'Valid request',
      presentation: { label: { messageId: 'table.verdict.schemaMatched' } },
    });
    expect(projection.columns.some((column) => column.path === '/given/input/request')).toBe(true);
    expect(projection.columns.some((column) => column.group === 'DEPENDENCY')).toBe(true);
    expect(projection.columns.some((column) => column.group === 'THEN')).toBe(true);
  });

  it('projects function arguments, intent, expected error, and an honest unbound status', () => {
    const testCase: VisualFunctionTestCase = {
      schemaVersion: 'bloge.visualAuthoringFunctionTestCase.v1',
      id: 'reject blank',
      kind: 'NEGATIVE',
      args: [''],
      assertion: 'EXPECT_ERROR',
      expect: null,
      expectError: { code: 'VALUE_REQUIRED' },
    };
    const projection = functionTestScenarioTableProjection(
      coordinate,
      [testCase],
      { 0: { passed: false, status: 'NOT_RUN', message: 'No exact callable was found.' } },
      'STALE',
    );

    expect(projection.rows[0]).toMatchObject({
      caseId: 'function-case-1',
      caseType: 'NEGATIVE',
      evidence: { execution: 'SKIPPED', freshness: 'STALE', proofStrength: 'RUNTIME' },
      presentation: { label: { messageId: 'table.verdict.evidenceStale' } },
    });
    expect(projection.rows[0].values).toMatchObject({ 'given:%2Farg1': '' });
    expect(Object.values(projection.rows[0].values)).not.toContain('Passed');
  });

  it('projects the real function subject and an inspectable expected/actual diff', () => {
    const testCase: VisualFunctionTestCase = {
      schemaVersion: 'bloge.visualAuthoringFunctionTestCase.v1',
      id: 'normalize team',
      kind: 'GOLDEN',
      args: [' platform '],
      assertion: 'EQUALS',
      expect: 'PLATFORM',
      expectError: null,
    };
    const projection = functionTestScenarioTableProjection(
      coordinate,
      [testCase],
      { 0: { passed: false, actual: 'platform', message: 'Values differ.' } },
    );

    expect(projection.rows[0].evidence).toMatchObject({
      subjectMode: 'REAL',
      assertionDiffs: [{
        path: '$',
        passed: false,
        expected: 'PLATFORM',
        actual: 'platform',
      }],
    });
  });
});

function fingerprint(seed: string): string {
  return `sha256:${seed.repeat(64)}`;
}
