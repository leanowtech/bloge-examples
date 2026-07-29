import { describe, expect, it } from 'vitest';

import type { GraphDraft } from '../types';
import {
  scenarioDraftSetFromOperatorTableCases,
  type ScenarioNodeOption,
} from './scenarioAuthoring';

const graphDraft: GraphDraft = {
  graphName: 'operator-risk-score',
  inputSchema: {
    format: 'json-schema',
    version: '2020-12',
    schema: {
      type: 'object',
      properties: { applicantId: { type: 'string' } },
      required: ['applicantId'],
    },
  },
  outputSchema: {
    format: 'json-schema',
    version: '2020-12',
    schema: {
      type: 'object',
      properties: { score: { type: 'integer' } },
      required: ['score'],
    },
  },
  nodes: [{
    id: 'operator',
    operatorRef: 'risk:score',
    inputs: {},
    config: {},
    position: { x: 0, y: 0 },
  }],
  edges: [],
  output: { nodeId: 'operator', path: '' },
};

const operatorNode: ScenarioNodeOption = {
  id: 'operator',
  label: 'Risk score',
  operatorRef: 'risk:score',
  inputSchema: graphDraft.inputSchema,
  outputSchema: graphDraft.outputSchema,
};

describe('operator table Scenario adapter', () => {
  it('projects input, fixture output, oracle, and case type into one canonical Scenario', () => {
    const draftSet = scenarioDraftSetFromOperatorTableCases(
      {
        kind: 'OPERATOR',
        id: 'risk:score',
        revision: 1,
        fingerprint: 'sha256:operator',
      },
      'sha256:contract',
      graphDraft,
      operatorNode,
      [{
        id: 'boundary-score',
        name: 'Boundary score',
        caseType: 'BOUNDARY',
        input: { applicantId: 'A-1' },
        expectedOutput: { score: 700 },
      }],
    );

    expect(draftSet.metadata.provenance).toMatchObject({
      source: 'operator-table-adapter',
      projectedCaseCount: 1,
    });
    expect(draftSet.scenarios[0]).toMatchObject({
      scenarioId: 'boundary-score',
      caseType: 'BOUNDARY',
      given: { input: { applicantId: 'A-1' }, provenance: 'MIGRATED' },
      dependencies: [{
        selector: { nodeId: 'operator' },
        behavior: {
          kind: 'RETURN',
          output: { score: 700 },
          expectedInput: { applicantId: 'A-1' },
        },
      }],
      then: {
        assertions: [{
          scope: 'OUTPUT_PATH',
          operator: 'EQUALS',
          expected: { score: 700 },
        }],
      },
    });
  });

  it('preserves unprojectable rows as Advanced provenance instead of inventing a passing case', () => {
    const diagnostic = {
      caseId: 'broken',
      message: 'Input case must be valid JSON.',
      raw: { inputText: '{' },
    };
    const draftSet = scenarioDraftSetFromOperatorTableCases(
      {
        kind: 'OPERATOR',
        id: 'risk:score',
        revision: 1,
        fingerprint: 'sha256:operator',
      },
      'sha256:contract',
      graphDraft,
      operatorNode,
      [],
      [diagnostic],
    );

    expect(draftSet.scenarios).toEqual([]);
    expect(draftSet.metadata.provenance.projectionDiagnostics).toEqual([diagnostic]);
  });
});
