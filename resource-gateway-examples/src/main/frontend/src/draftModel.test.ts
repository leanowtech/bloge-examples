import { describe, expect, it } from 'vitest';

import { isRunSuccessful, nodeStatuses, toGraphDraft } from './draftModel';
import type { SimulationResponse } from './types';

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
