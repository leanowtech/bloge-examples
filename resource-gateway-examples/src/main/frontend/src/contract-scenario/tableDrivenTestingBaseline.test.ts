import { describe, expect, it } from 'vitest';

import { scenarioDraftSetFromOperatorTableCases } from './scenarioAuthoring';
import { tableDrivenScenarioBaseline } from './tableDrivenTestingBaseline';
import { presentTableCaseVerdict, type TableCaseVerdict } from './tableDrivenTestStatus';

describe('table-driven testing baselines', () => {
  it.each([5, 50, 500] as const)(
    'freezes the %i-case stress corpus without weakening row dimensions',
    (size) => {
      const first = tableDrivenScenarioBaseline(size);
      const second = tableDrivenScenarioBaseline(size);

      expect(first).toEqual(second);
      expect(first.scenarios).toHaveLength(size);
      expect(new Set(first.scenarios.map((scenario) => scenario.scenarioId)).size).toBe(size);
      expect(new Set(first.scenarios.map((scenario) => scenario.caseType)))
        .toEqual(new Set(['GOLDEN', 'NEGATIVE', 'BOUNDARY', 'REGRESSION', 'PROPERTY']));
      for (const scenario of first.scenarios) {
        expect(Object.keys(scenario.given.input as object)).toHaveLength(20);
        expect(scenario.dependencies).toHaveLength(8);
        expect(scenario.then.assertions).toHaveLength(12);
      }
    },
  );

  it('retains operator table values inside the canonical Scenario adapter', () => {
    const projected = scenarioDraftSetFromOperatorTableCases(
      { kind: 'OPERATOR', id: 'risk:score', revision: 3, fingerprint: fingerprint('a') },
      fingerprint('b'),
      {
        schemaVersion: 'bloge.visualGraphDraft.v1',
        draftId: 'operator-risk-score',
        revision: 3,
        graphName: 'operator-risk-score',
        nodes: [{ id: 'risk-score', operatorRef: 'risk:score', label: 'Risk score' }],
        edges: [],
        output: { nodeId: 'risk-score', path: '' },
      },
      { id: 'risk-score', label: 'Risk score', operatorRef: 'risk:score' },
      [{
        id: 'boundary-score',
        name: 'Boundary score',
        caseType: 'BOUNDARY',
        input: { applicantId: 'applicant-1', score: 600 },
        expectedOutput: { eligible: true, band: 'B' },
      }],
    );

    expect(projected.scenarios).toHaveLength(1);
    expect(projected.target).toMatchObject({
      kind: 'OPERATOR',
      id: 'risk:score',
      revision: 3,
      fingerprint: fingerprint('a'),
    });
    expect(projected.scenarios[0]).toMatchObject({
      scenarioId: 'boundary-score',
      name: 'Boundary score',
      caseType: 'BOUNDARY',
      given: { input: { applicantId: 'applicant-1', score: 600 } },
      dependencies: [{
        selector: { nodeId: 'risk-score', operatorRef: '' },
        behavior: { kind: 'RETURN', output: { eligible: true, band: 'B' } },
      }],
      then: { assertions: [{ expected: { eligible: true, band: 'B' } }] },
    });
  });
});

describe('table-driven verdict vocabulary', () => {
  it.each<{
    verdict: TableCaseVerdict;
    label: string;
    tone: string;
  }>([
    {
      verdict: verdict('NOT_RUN', 'NONE', 'CURRENT', 'SCHEMA'),
      label: 'Not run',
      tone: 'neutral',
    },
    {
      verdict: verdict('SUCCESS', 'NONE', 'CURRENT', 'MOCK'),
      label: 'Mock execution succeeded',
      tone: 'warning',
    },
    {
      verdict: verdict('SUCCESS', 'PASSED', 'CURRENT', 'MOCK'),
      label: 'Mock behavior matched',
      tone: 'passed',
    },
    {
      verdict: verdict('SUCCESS', 'FAILED', 'CURRENT', 'RUNTIME'),
      label: 'Assertions failed',
      tone: 'failed',
    },
    {
      verdict: verdict('TIMEOUT', 'NONE', 'CURRENT', 'SANDBOX'),
      label: 'Execution timed out',
      tone: 'failed',
    },
    {
      verdict: verdict('SUCCESS', 'PASSED', 'STALE', 'CERTIFIABLE'),
      label: 'Evidence stale',
      tone: 'stale',
    },
    {
      verdict: verdict('BUDGET_STOPPED', 'NONE', 'CURRENT', 'MOCK'),
      label: 'Stopped by budget',
      tone: 'warning',
    },
  ])('presents $label without collapsing independent status axes', ({ verdict, label, tone }) => {
    expect(presentTableCaseVerdict(verdict)).toMatchObject({ label, tone });
    expect(presentTableCaseVerdict(verdict).label).not.toBe('Passed');
  });
});

function verdict(
  execution: TableCaseVerdict['execution'],
  assertions: TableCaseVerdict['assertions'],
  freshness: TableCaseVerdict['freshness'],
  proofStrength: TableCaseVerdict['proofStrength'],
): TableCaseVerdict {
  return { execution, assertions, freshness, proofStrength };
}

function fingerprint(seed: string): string {
  return `sha256:${seed.repeat(64)}`;
}
