import { describe, expect, it } from 'vitest';

import {
  autoLayoutCanvas,
  isRunSuccessful,
  nodeStatuses,
  simulationChecklist,
  summarizeCanvas,
  summarizeOperator,
  toGraphDraft,
} from './draftModel';
import type { OperatorDefinition, SimulationResponse } from './types';

describe('toGraphDraft', () => {
  it('maps canvas nodes and edges into a draft', () => {
    const draft = toGraphDraft(
      'myGraph',
      [
        { id: 'a', operatorRef: 'risk:eligibility', position: { x: 0, y: 0 } },
        { id: 'b', operatorRef: 'bloge:transform', label: 'Assemble', position: { x: 200, y: 0 } },
      ],
      [{ id: 'e1', source: 'a', target: 'b' }],
      'b',
    );

    expect(draft.graphName).toBe('myGraph');
    expect(draft.nodes).toHaveLength(2);
    expect(draft.nodes[0]).toMatchObject({ id: 'a', operatorRef: 'risk:eligibility' });
    expect(draft.nodes[1].label).toBe('Assemble');
    expect(draft.edges).toEqual([
      { id: 'e1', kind: 'data', source: { nodeId: 'a' }, target: { nodeId: 'b' } },
    ]);
    expect(draft.output).toEqual({ nodeId: 'b', path: '' });
  });

  it('defaults the output node to the last node when none is selected', () => {
    const draft = toGraphDraft(
      '',
      [
        { id: 'a', operatorRef: 'x', position: { x: 0, y: 0 } },
        { id: 'b', operatorRef: 'y', position: { x: 0, y: 0 } },
      ],
      [],
      '',
    );

    expect(draft.graphName).toBe('visualGraph');
    expect(draft.output.nodeId).toBe('b');
  });

  it('produces an empty output node for an empty canvas', () => {
    const draft = toGraphDraft('g', [], [], '');
    expect(draft.nodes).toHaveLength(0);
    expect(draft.output.nodeId).toBe('');
  });
});

describe('nodeStatuses', () => {
  it('marks mocked and real nodes distinctly', () => {
    const statuses = nodeStatuses({
      mockedNodeIds: ['a'],
      realNodeIds: ['b'],
    } as SimulationResponse);

    expect(statuses).toEqual({ a: 'mocked', b: 'real' });
  });

  it('tolerates missing arrays', () => {
    expect(nodeStatuses({} as SimulationResponse)).toEqual({});
  });
});

describe('summarizeOperator', () => {
  it('builds a readable operator summary from sparse catalog metadata', () => {
    const operator: OperatorDefinition = {
      operatorRef: 'risk:score',
      display: { name: 'Risk Score', tags: ['risk'] },
      source: { kind: 'user' },
      ports: {
        inputs: [
          { name: 'profile', required: true, schema: { schema: { type: 'object' } } },
          { name: 'history', schema: { schema: { type: 'array' } } },
        ],
        outputs: [{ name: 'decision', schema: { schema: { type: 'object' } } }],
      },
      lowering: { mode: 'design' },
    };

    expect(summarizeOperator(operator)).toMatchObject({
      operatorRef: 'risk:score',
      name: 'Risk Score',
      sourceKind: 'user',
      requiredInputCount: 1,
      inputCount: 2,
      outputCount: 1,
      inputNames: ['profile', 'history'],
      requiredInputNames: ['profile'],
      outputNames: ['decision'],
      designOnly: true,
    });
  });

  it('falls back to stable labels for minimal user supplied operators', () => {
    expect(summarizeOperator({ operatorRef: 'custom:minimal' })).toMatchObject({
      name: 'custom:minimal',
      sourceKind: 'library',
      inputCount: 0,
      outputCount: 0,
      designOnly: false,
    });
  });
});

describe('summarizeCanvas', () => {
  it('reports roots, terminals, disconnected nodes, and implicit output', () => {
    const summary = summarizeCanvas(
      [
        { id: 'a', operatorRef: 'a', position: { x: 0, y: 0 } },
        { id: 'b', operatorRef: 'b', position: { x: 0, y: 0 } },
        { id: 'c', operatorRef: 'c', position: { x: 0, y: 0 } },
      ],
      [{ id: 'e1', source: 'a', target: 'b' }],
    );

    expect(summary).toMatchObject({
      nodeCount: 3,
      edgeCount: 1,
      outputNodeId: 'c',
      rootNodeIds: ['a', 'c'],
      terminalNodeIds: ['b', 'c'],
      disconnectedNodeIds: ['c'],
    });
  });
});

describe('autoLayoutCanvas', () => {
  it('places a linear graph left to right by dependency depth', () => {
    const layout = autoLayoutCanvas(
      [
        { id: 'a', operatorRef: 'a', position: { x: 0, y: 0 } },
        { id: 'b', operatorRef: 'b', position: { x: 0, y: 0 } },
        { id: 'c', operatorRef: 'c', position: { x: 0, y: 0 } },
      ],
      [
        { id: 'e1', source: 'a', target: 'b' },
        { id: 'e2', source: 'b', target: 'c' },
      ],
    );

    expect(layout[0].position.x).toBeLessThan(layout[1].position.x);
    expect(layout[1].position.x).toBeLessThan(layout[2].position.x);
    expect(layout.map((node) => node.id)).toEqual(['a', 'b', 'c']);
  });

  it('keeps cyclic graphs deterministic instead of throwing', () => {
    const layout = autoLayoutCanvas(
      [
        { id: 'a', operatorRef: 'a', position: { x: 1, y: 1 } },
        { id: 'b', operatorRef: 'b', position: { x: 2, y: 2 } },
      ],
      [
        { id: 'e1', source: 'a', target: 'b' },
        { id: 'e2', source: 'b', target: 'a' },
      ],
    );

    expect(layout).toHaveLength(2);
    expect(layout[0].position.x).toBe(72);
    expect(layout[1].position.y).toBeGreaterThan(layout[0].position.y);
  });
});

describe('simulationChecklist', () => {
  it('marks an empty canvas as blocked', () => {
    expect(simulationChecklist(summarizeCanvas([], []), null)).toEqual([
      { key: 'nodes', label: 'Nodes', state: 'blocked', detail: '0' },
      { key: 'flow', label: 'Flow', state: 'ready', detail: 'single' },
      { key: 'output', label: 'Output', state: 'blocked', detail: 'missing' },
      { key: 'run', label: 'Run', state: 'pending', detail: 'not run' },
    ]);
  });

  it('surfaces mocked nodes as a trust warning after simulation', () => {
    const response: SimulationResponse = {
      validated: true,
      compiled: true,
      success: true,
      graphName: 'g',
      outputNode: 'b',
      output: null,
      results: {},
      statusMap: {},
      mockedNodeIds: ['a'],
      realNodeIds: ['b'],
      terminalOutputConforms: true,
      diagnostics: [],
      errors: [],
      generatedDsl: '',
    };

    const summary = summarizeCanvas(
      [
        { id: 'a', operatorRef: 'a', position: { x: 0, y: 0 } },
        { id: 'b', operatorRef: 'b', position: { x: 0, y: 0 } },
      ],
      [{ id: 'e1', source: 'a', target: 'b' }],
    );

    const items = simulationChecklist(summary, response);
    expect(items[items.length - 1]).toEqual({
      key: 'trust',
      label: 'Trust',
      state: 'warning',
      detail: '1 real / 1 mocked',
    });
  });
});

describe('isRunSuccessful', () => {
  it('is true only when validated, compiled, successful, and error-free', () => {
    const base: SimulationResponse = {
      validated: true,
      compiled: true,
      success: true,
      graphName: 'g',
      outputNode: 'n',
      output: null,
      results: {},
      statusMap: {},
      mockedNodeIds: [],
      realNodeIds: [],
      terminalOutputConforms: true,
      diagnostics: [],
      errors: [],
      generatedDsl: '',
    };

    expect(isRunSuccessful(base)).toBe(true);
    expect(isRunSuccessful({ ...base, success: false })).toBe(false);
    expect(isRunSuccessful({ ...base, errors: ['boom'] })).toBe(false);
    expect(isRunSuccessful({ ...base, compiled: false })).toBe(false);
  });
});
