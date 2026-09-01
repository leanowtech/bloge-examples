import { describe, expect, it } from 'vitest';

import {
  buildFlowFixtureCommand, buildParentFlowFixtureCommand, buildReusableFlowCommand, type ResolvedFlowNode,
} from './flowModel';
import type { ApiResourceSpec } from './model';

describe('simple reusable Flow model', () => {
  it('derives a mapping-defined DAG from exact API Resource revisions', () => {
    const command = buildReusableFlowCommand({
      flowId: 'customer-overview', displayName: 'Customer overview', kind: 'TOOL',
      description: 'Loads a profile and its orders.',
    }, [
      node('profile', resource('customer-profile', ['customerId'], { customerId: 'string' }, {
        customerId: 'string', name: 'string',
      })),
      node('orders', resource('customer-orders', ['customerId'], { customerId: 'string' }, {
        orders: 'object',
      })),
    ]);

    expect(command.flow.contract.input.schema).toEqual(expect.objectContaining({
      properties: { customerId: { type: 'string' } }, required: ['customerId'],
    }));
    expect(command.flow.graph.nodes[0].inputs).toEqual([{
      to: '$.customerId', from: { kind: 'FLOW_INPUT', path: '$.customerId' },
    }]);
    expect(command.flow.graph.nodes[1].inputs).toEqual([{
      to: '$.customerId', from: { kind: 'NODE_OUTPUT', nodeId: 'profile', path: '$.customerId' },
    }]);
    expect(command.flow.graph.output).toEqual({ nodeId: 'orders', path: '$' });
    expect(command.flow.graph.nodes.map((value) => value.use)).toEqual([
      { kind: 'API_RESOURCE', resourceId: 'customer-profile', revision: 3, fingerprint: hash('a') },
      { kind: 'API_RESOURCE', resourceId: 'customer-orders', revision: 3, fingerprint: hash('a') },
    ]);
  });

  it('keeps unmatched later inputs as explicit Flow inputs instead of inventing constants', () => {
    const command = buildReusableFlowCommand({
      flowId: 'lookup', displayName: 'Lookup', kind: 'SOLUTION', description: '',
    }, [
      node('profile', resource('profile', [], {}, { name: 'string' })),
      node('search', resource('search', ['query'], { query: 'string' }, { answer: 'string' })),
    ]);

    expect(command.flow.contract.input.schema.required).toEqual(['query']);
    expect(command.flow.graph.nodes[1].inputs).toEqual([{
      to: '$.query', from: { kind: 'FLOW_INPUT', path: '$.query' },
    }]);
  });

  it('authors one whole-flow RETURN fixture without exposing a graph-internal control', () => {
    const command = buildFlowFixtureCommand({
      kind: 'FLOW_DRAFT', draftId: 'draft-1', revision: 2, fingerprint: hash('b'),
    }, 'Customer overview default', '{"customerId":"c-1"}', '{"orders":[]}');

    expect(command).toEqual({
      schemaVersion: 'bloge.fixtureSetCommand.v1', displayName: 'Customer overview default',
      subject: { kind: 'FLOW_DRAFT', draftId: 'draft-1', revision: 2, fingerprint: hash('b') },
      cases: [{
        caseId: 'default', name: 'Default', input: { customerId: 'c-1' },
        controls: [{
          target: { kind: 'SUBJECT' },
          behavior: { kind: 'RETURN', material: { kind: 'INLINE', value: { orders: [] } } },
        }],
        expect: { output: { orders: [] } },
      }],
    });
  });

  it('pins an immutable Flow Version and authors exact node APPLY_CASE controls', () => {
    const child = flowNode('child', 'published-child', { customerId: 'string' }, { orderCount: 'integer' });
    const fixture = {
      schemaVersion: 'bloge.fixtureSetSummary.v1' as const, fixtureSetId: 'child.default', revision: 2,
      fingerprint: hash('f'), displayName: 'Child default', subject: child.item.reference,
      cases: [{ caseId: 'default', name: 'Default' }], status: 'PRIVATE_DRAFT' as const, statusRevision: 1,
    };
    const flow = buildReusableFlowCommand({
      flowId: 'parent', displayName: 'Parent', kind: 'SOLUTION', description: '',
    }, [child]);
    const command = buildParentFlowFixtureCommand({
      kind: 'FLOW_VERSION', publicationId: 'published-parent', revision: 1, fingerprint: hash('p'),
    }, 'Parent default', '{"customerId":"c-1"}', '{"orderCount":2}', [child], { child: fixture });

    expect(flow.flow.graph.nodes[0].use).toEqual(child.item.reference);
    expect(command.cases[0].controls).toEqual([{
      target: { kind: 'NODE', nodeId: 'child' },
      behavior: { kind: 'APPLY_CASE', fixtureSetId: 'child.default', revision: 2, caseId: 'default' },
    }]);
  });
});

function node(nodeId: string, spec: ApiResourceSpec): ResolvedFlowNode {
  return { nodeId, label: spec.displayName, item: {
    schemaVersion: 'bloge.composableCatalogItem.v1', displayName: spec.displayName,
    reference: { kind: 'API_RESOURCE', resourceId: spec.resourceId, revision: spec.revision,
      fingerprint: spec.fingerprint }, contract: spec.contract,
  } };
}

function flowNode(nodeId: string, publicationId: string, input: Record<string, string>, output: Record<string, string>): ResolvedFlowNode {
  return { nodeId, label: publicationId, item: {
    schemaVersion: 'bloge.composableCatalogItem.v1', displayName: publicationId,
    reference: { kind: 'FLOW_VERSION', publicationId, revision: 4, fingerprint: hash('v') },
    contract: { input: envelope(input, Object.keys(input)), output: envelope(output, Object.keys(output)) },
  } };
}

function resource(resourceId: string, required: string[], input: Record<string, string>, output: Record<string, string>): ApiResourceSpec {
  return {
    schemaVersion: 'bloge.apiResourceSpec.v1', resourceId, revision: 3, fingerprint: hash('a'),
    displayName: resourceId, connectionId: 'connection', operation: { method: 'GET', path: '/', bindings: [] },
    contract: { input: envelope(input, required), output: envelope(output, Object.keys(output)) },
    response: { success: { kind: 'HTTP_STATUS', codes: [200] } }, effect: { kind: 'READ_ONLY' },
    examples: [{ name: 'default', input: {}, output: {} }], status: 'DRAFT',
  };
}

function envelope(properties: Record<string, string>, required: string[]) {
  return {
    format: 'json-schema' as const, version: '2020-12' as const,
    schema: {
      type: 'object' as const,
      properties: Object.fromEntries(Object.entries(properties).map(([name, type]) => [name, { type }])),
      required, additionalProperties: false as const,
    },
  };
}

function hash(value: string): string { return `sha256:${value.repeat(64)}`; }
