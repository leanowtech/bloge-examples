import { describe, expect, it } from 'vitest';

import type {
  DraftEdge,
  DraftNode,
  GraphDraft,
  OperatorDefinition,
  SchemaEnvelope,
  SimulationResponse,
} from '../../types';
import {
  projectEffectiveContract,
  schemaFromAcceptedInference,
} from './effectiveContractProjection';

describe('effectiveContractProjection', () => {
  it('keeps declared, inferred, bound, and observed transform facts separate', () => {
    const assignments = {
      applicantId: 'n1.output.payload.applicantId',
      segment: 'coalesce(n1.output.payload.segment, "unknown")',
      primaryScore: 'toNumber(n2.output.payload.score)',
      secondaryScore: 'toNumber(n3.output.payload.score)',
      decision: 'n4.output.decision',
      tier: 'n4.output.tier',
      reason: 'coalesce(n4.output.reason, "fallback")',
    };
    const nodes = [
      sourceNode('n1'),
      sourceNode('n2'),
      sourceNode('n3'),
      sourceNode('n4'),
      {
        id: 'n5',
        operatorRef: 'bloge:transform',
        label: 'Decision response',
        config: { assignments },
        inputs: Object.fromEntries(Object.keys(assignments).map((field, index) => [
          field,
          {
            kind: 'nodePath',
            nodeId: index < 2 ? 'n1' : index < 4 ? `n${index}` : 'n4',
            sourcePort: 'output',
            path: index < 2 ? `payload.${field}` : field,
            targetPort: 'inputs',
            targetPath: field,
          },
        ])),
      },
    ];
    const edges = Object.keys(assignments).map((field, index) => edge(
      `e${index + 1}`,
      index < 2 ? 'n1' : index < 4 ? `n${index}` : 'n4',
      index < 2 ? `payload.${field}` : field,
      'n5',
      field,
    ));
    const graph = graphDraft(nodes, edges);
    const observed = {
      applicantId: 'applicant-1001',
      segment: 'prime',
      primaryScore: 728,
      secondaryScore: 701,
      decision: 'approve',
      tier: 'prime',
      reason: 'strong primary credit',
    };

    const projection = projectEffectiveContract({
      graphDraft: graph,
      nodeId: 'n5',
      operator: transformOperator(),
      operators: [sourceOperator()],
      run: runResponse({ n5: observed }),
    });

    expect(projection.declaredOutputs).toEqual([
      expect.objectContaining({ path: 'output', type: 'object', source: 'DECLARED' }),
    ]);
    expect(projection.inferredOutputs).toHaveLength(7);
    expect(projection.inferredOutputs).toContainEqual(expect.objectContaining({
      path: 'output.primaryScore',
      type: 'number',
      source: 'INFERRED',
      trace: expect.objectContaining({
        coordinate: '/nodes/n5/config/assignments/primaryScore',
      }),
    }));
    expect(projection.activeBindings).toHaveLength(7);
    expect(projection.activeBindings.every((binding) => binding.kind === 'EDGE')).toBe(true);
    expect(projection.observedOutputs).toHaveLength(7);
    expect(projection.confidence).toBe('INFERRED');
    expect(projection.conflicts).toEqual([]);
    expect(schemaFromAcceptedInference(projection)?.schema).toMatchObject({
      type: 'object',
      required: [],
      additionalProperties: true,
      properties: {
        applicantId: {},
        primaryScore: { type: 'number' },
      },
    });
  });

  it('infers decision output columns and types from rule values', () => {
    const graph = graphDraft([{
      id: 'policy',
      operatorRef: 'bloge:decisionTable',
      config: {
        outputType: '{ decision: String, promisedHours: Integer, approved: Boolean }',
        outputColumns: ['decision', 'promisedHours', 'approved'],
        rules: [
          { output: { decision: 'approve', promisedHours: 24, approved: true } },
          { output: { decision: 'decline', promisedHours: 72, approved: false } },
        ],
      },
    }], []);

    const projection = projectEffectiveContract({
      graphDraft: graph,
      nodeId: 'policy',
      operator: decisionOperator(),
    });

    expect(projection.inferredOutputs).toEqual([
      expect.objectContaining({ path: 'output.decision', type: 'string' }),
      expect.objectContaining({ path: 'output.promisedHours', type: 'number' }),
      expect.objectContaining({ path: 'output.approved', type: 'boolean' }),
    ]);
    expect(projection.inferredOutputs[0].trace.kind).toBe('DECISION_OUTPUT');
  });

  it('fails visibly on type mismatches, duplicate sources, and required gaps', () => {
    const targetOperator: OperatorDefinition = {
      operatorRef: 'target',
      ports: {
        inputs: [{
          name: 'inputs',
          required: true,
          schema: schema({
            type: 'object',
            properties: {
              amount: { type: 'number' },
              currency: { type: 'string' },
            },
            required: ['amount', 'currency'],
          }),
        }],
        outputs: [],
      },
    };
    const graph = graphDraft([
      sourceNode('a'),
      sourceNode('b'),
      {
        id: 'target',
        operatorRef: 'target',
        inputs: {
          manualAmount: {
            kind: 'constant',
            value: 'not-a-number',
            targetPort: 'inputs',
            targetPath: 'amount',
          },
        },
      },
    ], [edge('amount-edge', 'a', 'payload.amount', 'target', 'amount')]);

    const projection = projectEffectiveContract({
      graphDraft: graph,
      nodeId: 'target',
      operator: targetOperator,
      operators: [sourceOperator()],
    });

    expect(projection.activeBindings).toContainEqual(expect.objectContaining({
      targetPath: 'inputs.amount',
      status: 'CONFLICT',
    }));
    expect(projection.activeBindings).toContainEqual(expect.objectContaining({
      targetPath: 'inputs.currency',
      status: 'UNBOUND',
    }));
    expect(projection.conflicts.map((conflict) => conflict.code)).toEqual(
      expect.arrayContaining(['MULTIPLE_SOURCES', 'TYPE_MISMATCH']),
    );
    expect(projection.confidence).toBe('CONFLICTED');
  });

  it('does not promote observed values into the accepted inference schema', () => {
    const graph = graphDraft([sourceNode('opaque')], []);
    const projection = projectEffectiveContract({
      graphDraft: graph,
      nodeId: 'opaque',
      operator: sourceOperator(),
      run: runResponse({ opaque: { payload: { hidden: 'runtime-only' } } }),
    });

    expect(projection.observedOutputs).toContainEqual(expect.objectContaining({
      path: 'output.payload.hidden',
      source: 'OBSERVED',
    }));
    expect(projection.inferredOutputs).toEqual([]);
    expect(schemaFromAcceptedInference(projection)).toBeNull();
  });

  it('is deterministic and stays within the 25-node projection budget', () => {
    const nodes = Array.from({ length: 25 }, (_, index) => ({
      id: `n${index + 1}`,
      operatorRef: index === 24 ? 'bloge:transform' : 'source',
      config: index === 24
        ? {
            assignments: Object.fromEntries(
              Array.from({ length: 50 }, (__, field) => [
                `field${field + 1}`,
                `n${(field % 24) + 1}.output.payload.value`,
              ]),
            ),
          }
        : undefined,
    }));
    const edges = Array.from({ length: 50 }, (_, index) => edge(
      `e${index + 1}`,
      `n${(index % 24) + 1}`,
      'payload.value',
      'n25',
      `field${index + 1}`,
    ));
    const graph = graphDraft(nodes, edges);
    const startedAt = performance.now();
    const first = projectEffectiveContract({
      graphDraft: graph,
      nodeId: 'n25',
      operator: transformOperator(),
      operators: [sourceOperator()],
    });
    const elapsed = performance.now() - startedAt;
    const second = projectEffectiveContract({
      graphDraft: graph,
      nodeId: 'n25',
      operator: transformOperator(),
      operators: [sourceOperator()],
    });

    expect(first).toEqual(second);
    expect(first.activeBindings).toHaveLength(50);
    expect(first.inferredOutputs).toHaveLength(50);
    expect(elapsed).toBeLessThan(100);
  });
});

function graphDraft(nodes: DraftNode[], edges: DraftEdge[]): GraphDraft {
  return {
    graphName: 'loanGraph',
    inputSchema: schema({
      type: 'object',
      properties: { applicantId: { type: 'string' } },
      required: ['applicantId'],
    }),
    nodes,
    edges,
    output: { nodeId: nodes[nodes.length - 1]?.id ?? '' },
  };
}

function sourceNode(id: string): DraftNode {
  return { id, operatorRef: 'source', label: `Source ${id}` };
}

function edge(
  id: string,
  sourceNodeId: string,
  sourcePath: string,
  targetNodeId: string,
  targetPath: string,
): DraftEdge {
  return {
    id,
    kind: 'data',
    source: { nodeId: sourceNodeId, port: 'output', path: sourcePath },
    target: { nodeId: targetNodeId, port: 'inputs', path: targetPath },
  };
}

function sourceOperator(): OperatorDefinition {
  return {
    operatorRef: 'source',
    source: { kind: 'test' },
    ports: {
      inputs: [],
      outputs: [{
        name: 'output',
        schema: schema({
          type: 'object',
          properties: {
            payload: {
              type: 'object',
              properties: {
                applicantId: { type: 'string' },
                amount: { type: 'number' },
                score: { type: 'number' },
                value: { type: 'string' },
              },
            },
            decision: { type: 'string' },
            tier: { type: 'string' },
            reason: { type: 'string' },
          },
        }),
      }],
    },
  };
}

function transformOperator(): OperatorDefinition {
  return {
    operatorRef: 'bloge:transform',
    source: { kind: 'bloge-dsl' },
    ports: {
      inputs: [{
        name: 'inputs',
        schema: schema({ type: 'object', additionalProperties: true }),
      }],
      outputs: [{
        name: 'output',
        schema: schema({ type: 'object', additionalProperties: true }),
      }],
    },
  };
}

function decisionOperator(): OperatorDefinition {
  return {
    ...transformOperator(),
    operatorRef: 'bloge:decisionTable',
  };
}

function schema(value: Record<string, unknown>): SchemaEnvelope {
  return { format: 'json-schema', version: '2020-12', schema: value };
}

function runResponse(results: Record<string, unknown>): SimulationResponse {
  return {
    validated: true,
    compiled: true,
    success: true,
    graphName: 'loanGraph',
    outputNode: 'n5',
    output: results.n5 ?? {},
    results,
    statusMap: Object.fromEntries(Object.keys(results).map((nodeId) => [nodeId, 'COMPLETED'])),
    mockedNodeIds: [],
    realNodeIds: Object.keys(results),
    terminalOutputConforms: true,
    diagnostics: [],
    errors: [],
    generatedDsl: '',
  };
}
