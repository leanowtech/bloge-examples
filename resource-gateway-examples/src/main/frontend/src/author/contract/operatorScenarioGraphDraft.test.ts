import { describe, expect, it } from 'vitest';

import type { ContractDraft } from '../../contract-scenario/domain';
import type { OperatorDefinition, SchemaEnvelope } from '../../types';
import {
  operatorScenarioGraphDraft,
  operatorScenarioInputBindings,
} from './operatorScenarioGraphDraft';

function schema(properties: Record<string, unknown>): SchemaEnvelope {
  return {
    format: 'JSON_SCHEMA',
    schema: {
      type: 'object',
      properties,
      additionalProperties: false,
    },
  };
}

function contract(inputSchema: SchemaEnvelope): ContractDraft {
  return {
    schemaVersion: 'bloge.contractDraft.v1',
    target: {
      kind: 'OPERATOR',
      id: 'resource:loan-applicant-service.getProfile',
      revision: 0,
      fingerprint: `sha256:${'a'.repeat(64)}`,
    },
    inputSchema,
    outputSchema: schema({ payload: { type: 'object' } }),
    errorContract: [],
    executionSemantics: {
      effect: 'READ',
      idempotency: 'REQUEST_KEY',
      streaming: false,
      durable: false,
    },
    invariants: [],
    compatibilityPolicy: {
      mode: 'STRICT',
      unknownBlocksAutomaticMigration: true,
    },
    fieldMetadata: {},
    source: 'AUTHORED',
    confidence: 'EXACT',
  };
}

function operator(inputPorts: string[]): OperatorDefinition {
  return {
    operatorRef: 'resource:loan-applicant-service.getProfile',
    display: { name: 'Fetch applicant' },
    source: { kind: 'resource' },
    lowering: { mode: 'native' },
    ports: {
      inputs: inputPorts.map((name) => ({ name, required: true, schema: schema({}) })),
      outputs: [{ name: 'payload', required: true, schema: schema({}) }],
    },
  } as OperatorDefinition;
}

describe('operatorScenarioInputBindings', () => {
  it('binds a wrapped single-port Contract to the root of that port', () => {
    const bindings = operatorScenarioInputBindings(
      operator(['params']),
      contract(schema({ params: { type: 'object', properties: { applicantId: { type: 'string' } } } })),
    );

    expect(bindings).toEqual({
      params: { kind: 'contextPath', path: 'params', targetPort: 'params' },
    });
    expect(bindings.params).not.toHaveProperty('targetPath');
  });

  it('maps an unwrapped Contract field into the only Operator port', () => {
    expect(operatorScenarioInputBindings(
      operator(['params']),
      contract(schema({ applicantId: { type: 'string' }, locale: { type: 'string' } })),
    )).toEqual({
      applicantId: {
        kind: 'contextPath',
        path: 'applicantId',
        targetPort: 'params',
        targetPath: 'applicantId',
      },
      locale: {
        kind: 'contextPath',
        path: 'locale',
        targetPort: 'params',
        targetPath: 'locale',
      },
    });
  });

  it('binds every multi-port input as an independent root value', () => {
    const graph = operatorScenarioGraphDraft(
      operator(['profile', 'policy']),
      contract(schema({ profile: { type: 'object' }, policy: { type: 'object' } })),
      'tenant-a',
      'test',
    );

    expect(graph.nodes[0].inputs).toEqual({
      profile: { kind: 'contextPath', path: 'profile', targetPort: 'profile' },
      policy: { kind: 'contextPath', path: 'policy', targetPort: 'policy' },
    });
  });
});
