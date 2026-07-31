import { describe, expect, it } from 'vitest';

import type { GraphDraft } from '../types';
import {
  newScenarioDraft,
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

  it('projects only controlled fixtures instead of one card for every graph node', () => {
    const fiveNodes = Array.from({ length: 5 }, (_, index): ScenarioNodeOption => ({
      id: `node-${index + 1}`,
      label: `Node ${index + 1}`,
      operatorRef: `demo:node-${index + 1}`,
    }));
    const draft = {
      ...graphDraft,
      nodeFixtures: {
        'node-2': { output: { value: 2 } },
        'node-5': { output: { value: 5 } },
      },
    };

    const scenario = newScenarioDraft(1, draft, fiveNodes);

    expect(scenario.dependencies).toHaveLength(2);
    expect(scenario.dependencies.map((dependency) => dependency.selector.nodeId))
      .toEqual(['node-2', 'node-5']);
    expect(scenario.dependencies.every((dependency) => dependency.behavior.kind === 'RETURN'))
      .toBe(true);
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
