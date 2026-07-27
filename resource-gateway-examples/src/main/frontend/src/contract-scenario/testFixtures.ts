import type { GraphDraft, SchemaEnvelope, SimulationResponse } from '../types';
import type { ScenarioNodeOption } from './scenarioAuthoring';

export function graphDraft(): GraphDraft {
  return {
    schemaVersion: 'bloge.visualGraphDraft.v1',
    draftId: 'loan-graph',
    revision: 2,
    graphName: 'loanGraph',
    inputSchema: inputSchema(),
    outputSchema: outputSchema(),
    nodes: [
      { id: 'score', operatorRef: 'risk:score', label: 'Risk Score' },
      { id: 'decide', operatorRef: 'risk:decide', label: 'Decide' },
    ],
    edges: [{
      id: 'score-decide',
      kind: 'data',
      source: { nodeId: 'score', port: 'score' },
      target: { nodeId: 'decide', port: 'score' },
    }],
    output: { nodeId: 'decide', path: '' },
  };
}

export function inputSchema(): SchemaEnvelope {
  return {
    format: 'json-schema',
    version: '2020-12',
    schema: {
      type: 'object',
      required: ['applicantId', 'profile'],
      properties: {
        applicantId: { type: 'string', minLength: 1 },
        profile: {
          type: 'object',
          required: ['age'],
          properties: {
            age: { type: 'integer', minimum: 18 },
            tags: { type: 'array', items: { type: 'string' } },
          },
        },
      },
      additionalProperties: false,
    },
  };
}

export function outputSchema(): SchemaEnvelope {
  return {
    format: 'json-schema',
    version: '2020-12',
    schema: {
      type: 'object',
      required: ['decision'],
      properties: {
        decision: {
          type: 'object',
          required: ['approved', 'reason'],
          properties: {
            approved: { type: 'boolean' },
            reason: { type: 'string' },
          },
        },
      },
    },
  };
}

export function nodes(): ScenarioNodeOption[] {
  return [
    {
      id: 'score',
      label: 'Risk Score',
      operatorRef: 'risk:score',
      outputSchema: {
        format: 'json-schema',
        version: '2020-12',
        schema: {
          type: 'object',
          properties: { score: { type: 'integer', minimum: 0 } },
        },
      },
    },
    {
      id: 'decide',
      label: 'Decide',
      operatorRef: 'risk:decide',
      outputSchema: outputSchema(),
    },
  ];
}

export function successfulResponse(): SimulationResponse {
  return {
    validated: true,
    compiled: true,
    success: true,
    graphName: 'loanGraph',
    outputNode: 'decide',
    output: { decision: { approved: true, reason: 'eligible' } },
    results: {
      score: { score: 720 },
      decide: { decision: { approved: true, reason: 'eligible' } },
    },
    statusMap: { score: 'MOCKED', decide: 'SUCCESS' },
    mockedNodeIds: ['score'],
    realNodeIds: ['decide'],
    terminalOutputConforms: true,
    diagnostics: [],
    errors: [],
    generatedDsl: 'loanGraph = graph(...)',
  };
}
