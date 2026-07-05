import { describe, expect, it } from 'vitest';

import {
  authoringJourney,
  autoLayoutCanvas,
  canvasCoachPrompt,
  canvasNodeFocusState,
  compileFixtureDrafts,
  connectionCandidatesMessage,
  connectionDecisionMessage,
  connectionGuideRows,
  endpointFromHandle,
  fixtureDraftForOperator,
  handleIdForPort,
  indexConnectionCandidates,
  isRunSuccessful,
  nodeStatuses,
  operatorPaletteView,
  operatorLibraryImportMessage,
  operatorLibraryValidationLevel,
  operatorLibraryValidationMessage,
  portNameFromHandle,
  sampleFromSchemaEnvelope,
  simulationChecklist,
  simulationFixtureRows,
  simulationTraceRows,
  summarizeCanvas,
  summarizeOperator,
  toConnectionCandidatesRequest,
  toConnectionCheckRequest,
  toGraphDraft,
  toSimulationRequest,
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

  it('preserves port-qualified canvas edges in draft endpoints', () => {
    const draft = toGraphDraft(
      'myGraph',
      [
        { id: 'a', operatorRef: 'producer', position: { x: 0, y: 0 } },
        { id: 'b', operatorRef: 'consumer', position: { x: 200, y: 0 } },
      ],
      [
        {
          id: 'e1',
          source: 'a',
          target: 'b',
          sourcePort: 'decision',
          targetPort: 'profile',
        },
      ],
      'b',
    );

    expect(draft.edges[0]).toMatchObject({
      source: { nodeId: 'a', port: 'decision' },
      target: { nodeId: 'b', port: 'profile' },
    });
  });
});

describe('toSimulationRequest', () => {
  it('keeps request outputNode aligned with the draft output selection', () => {
    const request = toSimulationRequest(
      'myGraph',
      [
        { id: 'source', operatorRef: 'producer', position: { x: 0, y: 0 } },
        { id: 'chosen', operatorRef: 'consumer', position: { x: 200, y: 0 } },
        { id: 'tail', operatorRef: 'audit', position: { x: 400, y: 0 } },
      ],
      [{ id: 'e1', source: 'source', target: 'chosen' }],
      'chosen',
      { chosen: { output: { approved: true } } },
    );

    expect(request.outputNode).toBe('chosen');
    expect(request.draft.output.nodeId).toBe('chosen');
    expect(request.fixtures).toEqual({ chosen: { output: { approved: true } } });
  });

  it('falls back to the last node and omits empty fixtures', () => {
    const request = toSimulationRequest(
      'myGraph',
      [
        { id: 'a', operatorRef: 'a', position: { x: 0, y: 0 } },
        { id: 'b', operatorRef: 'b', position: { x: 200, y: 0 } },
      ],
      [],
      '',
    );

    expect(request.outputNode).toBe('b');
    expect(request.draft.output.nodeId).toBe('b');
    expect(request).not.toHaveProperty('fixtures');
  });
});

describe('port handles', () => {
  it('round-trips arbitrary port names through React Flow handle ids', () => {
    const handleId = handleIdForPort('out', 'decision.score');
    expect(handleId).toBe('out:decision.score');
    expect(portNameFromHandle(handleId, 'out')).toBe('decision.score');
    expect(portNameFromHandle(handleId, 'in')).toBe('');
  });

  it('creates BLOGE endpoints from handle ids', () => {
    expect(endpointFromHandle('policy', handleIdForPort('in', 'profile'), 'in')).toEqual({
      nodeId: 'policy',
      port: 'profile',
    });
  });
});

describe('toConnectionCheckRequest', () => {
  it('builds a server preflight request from a connection gesture', () => {
    const request = toConnectionCheckRequest(
      'myGraph',
      [
        { id: 'a', operatorRef: 'producer', position: { x: 0, y: 0 } },
        { id: 'b', operatorRef: 'consumer', position: { x: 200, y: 0 } },
      ],
      [],
      'b',
      'a',
      'b',
      handleIdForPort('out', 'decision'),
      handleIdForPort('in', 'profile'),
    );

    expect(request.kind).toBe('data');
    expect(request.source).toEqual({ nodeId: 'a', port: 'decision' });
    expect(request.target).toEqual({ nodeId: 'b', port: 'profile' });
    expect(request.draft.output.nodeId).toBe('b');
  });
});

describe('toConnectionCandidatesRequest', () => {
  it('builds a server candidate request from a source handle drag', () => {
    const request = toConnectionCandidatesRequest(
      'myGraph',
      [
        { id: 'a', operatorRef: 'producer', position: { x: 0, y: 0 } },
        { id: 'b', operatorRef: 'consumer', position: { x: 200, y: 0 } },
      ],
      [],
      'b',
      'a',
      handleIdForPort('out', 'decision'),
    );

    expect(request).toMatchObject({
      kind: 'data',
      includeRejected: true,
      limit: 250,
      targetSurface: 'input',
      source: { nodeId: 'a', port: 'decision' },
    });
    expect(request.draft.output.nodeId).toBe('b');
  });
});

describe('connectionDecisionMessage', () => {
  it('prefers the server summary message', () => {
    expect(
      connectionDecisionMessage({
        accepted: false,
        diagnostics: [{ level: 'error', code: 'x', message: 'diagnostic' }],
        summary: { message: 'server says no' },
      }),
    ).toBe('server says no');
  });

  it('falls back to the first diagnostic when no summary exists', () => {
    expect(
      connectionDecisionMessage({
        accepted: false,
        diagnostics: [{ level: 'error', code: 'visual.connection.schema', message: 'string -> number' }],
      }),
    ).toBe('visual.connection.schema: string -> number');
  });
});

describe('connectionCandidatesMessage', () => {
  it('summarizes compatible and blocked candidate counts', () => {
    expect(
      connectionCandidatesMessage({
        source: { nodeId: 'a', port: 'decision' },
        acceptedCount: 2,
        rejectedCount: 1,
        candidates: [],
      }),
    ).toBe('2 compatible targets · 1 blocked.');
  });

  it('falls back to request diagnostics when no target is compatible', () => {
    expect(
      connectionCandidatesMessage({
        source: { nodeId: 'a' },
        acceptedCount: 0,
        rejectedCount: 0,
        candidates: [],
        diagnostics: [{ level: 'error', code: 'visual.connection.source', message: 'Unknown source.' }],
      }),
    ).toBe('visual.connection.source: Unknown source.');
  });
});

describe('indexConnectionCandidates', () => {
  it('indexes target nodes and ports with ready status taking precedence', () => {
    const index = indexConnectionCandidates({
      source: { nodeId: 'a', port: 'decision' },
      acceptedCount: 1,
      rejectedCount: 1,
      totalCandidateCount: 2,
      candidates: [
        {
          targetNodeId: 'b',
          targetSurface: 'input',
          target: { nodeId: 'b', port: 'profile' },
          accepted: false,
          targetStatus: 'blocked',
        },
        {
          targetNodeId: 'b',
          targetSurface: 'input',
          target: { nodeId: 'b', port: 'score' },
          accepted: true,
          targetStatus: 'ready',
        },
      ],
    });

    expect(index.nodeStatuses.b).toBe('ready');
    expect(index.portStatuses.b).toEqual({ profile: 'blocked', score: 'ready' });
    expect(index.acceptedCount).toBe(1);
    expect(index.rejectedCount).toBe(1);
  });

  it('keeps already-wired targets distinct from blocked targets', () => {
    const index = indexConnectionCandidates({
      source: { nodeId: 'a' },
      candidates: [
        {
          targetNodeId: 'b',
          targetSurface: 'input',
          target: { nodeId: 'b', port: 'profile' },
          accepted: false,
          targetStatus: 'wired',
        },
      ],
    });

    expect(index.nodeStatuses.b).toBe('wired');
    expect(index.portStatuses.b.profile).toBe('wired');
  });
});

describe('connectionGuideRows', () => {
  it('turns server candidates into sorted actionable inspector rows', () => {
    const index = indexConnectionCandidates({
      source: { nodeId: 'source', port: 'decision' },
      candidates: [
        {
          targetNodeId: 'blocked',
          targetNodeLabel: 'Blocked Policy',
          targetOperatorRef: 'risk:blocked',
          targetSurface: 'input',
          target: { nodeId: 'blocked', port: 'profile' },
          accepted: false,
          targetStatus: 'blocked',
          diagnostics: [{ level: 'error', code: 'visual.connection.schema', message: 'object -> number' }],
        },
        {
          targetNodeId: 'wired',
          targetSurface: 'input',
          target: { nodeId: 'wired', port: 'profile' },
          accepted: false,
          targetStatus: 'wired',
        },
        {
          targetNodeId: 'ready',
          targetSurface: 'input',
          target: { nodeId: 'ready', port: 'case' },
          accepted: true,
          targetStatus: 'ready',
          summary: { message: 'Schemas match.' },
        },
      ],
    });

    expect(connectionGuideRows([
      { id: 'ready', operatorRef: 'risk:ready', label: 'Ready Policy', position: { x: 0, y: 0 } },
      { id: 'wired', operatorRef: 'risk:wired', label: 'Wired Policy', position: { x: 0, y: 0 } },
      { id: 'blocked', operatorRef: 'risk:blocked', position: { x: 0, y: 0 } },
    ], index)).toEqual([
      {
        key: 'ready|case|',
        targetNodeId: 'ready',
        targetLabel: 'Ready Policy',
        targetOperatorRef: 'risk:ready',
        targetPort: 'case',
        status: 'ready',
        accepted: true,
        detail: 'Schemas match.',
      },
      {
        key: 'wired|profile|',
        targetNodeId: 'wired',
        targetLabel: 'Wired Policy',
        targetOperatorRef: 'risk:wired',
        targetPort: 'profile',
        status: 'wired',
        accepted: false,
        detail: 'Already connected.',
      },
      {
        key: 'blocked|profile|',
        targetNodeId: 'blocked',
        targetLabel: 'Blocked Policy',
        targetOperatorRef: 'risk:blocked',
        targetPort: 'profile',
        status: 'blocked',
        accepted: false,
        detail: 'visual.connection.schema: object -> number',
      },
    ]);
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

describe('operatorPaletteView', () => {
  const operators: OperatorDefinition[] = [
    {
      operatorRef: 'risk:score',
      display: { name: 'Risk Score', description: 'Scores an applicant.', tags: ['risk', 'score'] },
      source: { kind: 'user-library', libraryId: 'risk-lib' },
      ports: {
        inputs: [{ name: 'profile', required: true, schema: { schema: { type: 'object' } } }],
        outputs: [{ name: 'score', schema: { schema: { type: 'number' } } }],
      },
      lowering: { mode: 'transform' },
    },
    {
      operatorRef: 'risk:fraudReview',
      display: { name: 'Fraud Review', tags: ['risk', 'manual'] },
      source: { kind: 'user-library', libraryId: 'risk-lib' },
      ports: {
        inputs: [{ name: 'case', schema: { schema: { type: 'object' } } }],
        outputs: [{ name: 'decision', schema: { schema: { type: 'string' } } }],
      },
      lowering: { mode: 'design' },
    },
    {
      operatorRef: 'payments:enrich',
      display: { name: 'Payment Enrich', tags: ['payment'] },
      source: { kind: 'builtin' },
      ports: {
        inputs: [{ name: 'transaction', schema: { schema: { type: 'object' } } }],
        outputs: [{ name: 'enriched', schema: { schema: { type: 'object' } } }],
      },
      lowering: { mode: 'transform' },
    },
  ];

  it('groups visible operators by explicit library id and namespace fallback', () => {
    const view = operatorPaletteView(operators);

    expect(view.totalCount).toBe(3);
    expect(view.matchingCount).toBe(3);
    expect(view.groups.map((group) => [group.libraryId, group.count])).toEqual([
      ['payments', 1],
      ['risk-lib', 2],
    ]);
    expect(view.groups[1].rows.map((row) => row.summary.name)).toEqual([
      'Fraud Review',
      'Risk Score',
    ]);
  });

  it('applies runtime, source, tag, and search filters with stable facet counts', () => {
    const view = operatorPaletteView(operators, {
      search: 'risk',
      facet: 'design',
      sourceKind: 'user-library',
      tag: 'manual',
    });

    expect(view.runtimeFacets).toEqual([
      { key: 'all', label: 'All', count: 2 },
      { key: 'runtime', label: 'Runtime', count: 1 },
      { key: 'design', label: 'Design', count: 1 },
    ]);
    expect(view.sourceKindFacets).toEqual([
      { key: 'user-library', label: 'user-library', count: 2 },
    ]);
    expect(view.tagFacets).toContainEqual({ key: 'risk', label: 'risk', count: 2 });
    expect(view.matchingCount).toBe(1);
    expect(view.groups[0].rows[0].summary.operatorRef).toBe('risk:fraudReview');
  });

  it('matches separate search terms across names, ports, tags, and library ids', () => {
    const view = operatorPaletteView(operators, { search: 'risk-lib profile score' });

    expect(view.matchingCount).toBe(1);
    expect(view.groups[0].rows[0].summary.operatorRef).toBe('risk:score');
  });
});

describe('operator library intake helpers', () => {
  it('summarizes a valid library using server import readiness', () => {
    const validation = {
      valid: true,
      diagnostics: [],
      profile: { libraryId: 'risk-policy', operatorCount: 3 },
      importReadiness: {
        level: 'info',
        operatorCount: 3,
        message: 'Schema-only library is ready for design-time authoring.',
      },
    };

    expect(operatorLibraryValidationLevel(validation)).toBe('ok');
    expect(operatorLibraryValidationMessage(validation)).toBe(
      'risk-policy: Schema-only library is ready for design-time authoring.',
    );
  });

  it('surfaces warning and error diagnostics without losing the server code', () => {
    expect(operatorLibraryValidationLevel({
      valid: true,
      diagnostics: [{ level: 'WARNING', code: 'visual.library.replacement' }],
    })).toBe('warning');
    expect(operatorLibraryValidationLevel({
      valid: false,
      diagnostics: [
        {
          level: 'ERROR',
          code: 'visual.library.schemaVersion',
          message: 'Unsupported schema version.',
        },
      ],
    })).toBe('error');
    expect(operatorLibraryValidationMessage({
      valid: false,
      diagnostics: [
        {
          level: 'ERROR',
          code: 'visual.library.schemaVersion',
          message: 'Unsupported schema version.',
        },
      ],
    })).toBe('visual.library.schemaVersion: Unsupported schema version.');
  });

  it('formats a stored library import summary', () => {
    expect(operatorLibraryImportMessage({
      libraryId: 'risk-policy',
      operators: [
        {
          operatorRef: 'risk:score',
          ports: { inputs: [], outputs: [] },
        },
      ],
    })).toBe('Imported risk-policy (1 operator).');
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

describe('authoringJourney', () => {
  const successfulResponse: SimulationResponse = {
    validated: true,
    compiled: true,
    success: true,
    graphName: 'g',
    outputNode: 'n1',
    output: { ok: true },
    results: {},
    statusMap: {},
    mockedNodeIds: [],
    realNodeIds: ['n1'],
    terminalOutputConforms: true,
    diagnostics: [],
    errors: [],
    generatedDsl: '',
  };

  it('starts blocked when no operator library is available', () => {
    const journey = authoringJourney(0, summarizeCanvas([], []), [], null);

    expect(journey.steps.map((step) => [step.key, step.state])).toEqual([
      ['library', 'blocked'],
      ['compose', 'pending'],
      ['flow', 'pending'],
      ['mocks', 'pending'],
      ['simulate', 'blocked'],
    ]);
    expect(journey.action).toEqual({ kind: 'none', label: 'Catalog empty' });
    expect(journey.completedCount).toBe(0);
  });

  it('points the primary action at the palette before nodes exist', () => {
    const journey = authoringJourney(3, summarizeCanvas([], []), [], null);

    expect(journey.steps.find((step) => step.key === 'library')).toMatchObject({ state: 'ready' });
    expect(journey.steps.find((step) => step.key === 'compose')).toMatchObject({ state: 'blocked' });
    expect(journey.action).toEqual({ kind: 'focus-palette', label: 'Add operator' });
  });

  it('surfaces disconnected flow without blocking fixture repair priority', () => {
    const summary = summarizeCanvas(
      [
        { id: 'n1', operatorRef: 'risk:a', position: { x: 0, y: 0 } },
        { id: 'n2', operatorRef: 'risk:b', position: { x: 0, y: 0 } },
      ],
      [],
    );
    const journey = authoringJourney(
      2,
      summary,
      [
        {
          nodeId: 'n2',
          label: 'Broken',
          operatorRef: 'risk:b',
          state: 'blocked',
          runMode: 'mocked',
          fixtureLabel: 'json error',
          detail: 'Invalid JSON',
        },
      ],
      null,
    );

    expect(journey.steps.find((step) => step.key === 'flow')).toMatchObject({
      state: 'warning',
      detail: '0 edges',
    });
    expect(journey.steps.find((step) => step.key === 'mocks')).toMatchObject({
      state: 'blocked',
      detail: '1 blocked',
    });
    expect(journey.action).toEqual({ kind: 'select-node', label: 'Fix mock JSON', nodeId: 'n2' });
  });

  it('chooses mock pinning before the first run and then closes on success', () => {
    const summary = summarizeCanvas(
      [{ id: 'n1', operatorRef: 'risk:design', position: { x: 0, y: 0 } }],
      [],
    );
    const fixtureRows = [
      {
        nodeId: 'n1',
        label: 'Risk',
        operatorRef: 'risk:design',
        state: 'warning' as const,
        runMode: 'mocked' as const,
        fixtureLabel: 'server sample',
        detail: 'server sample',
      },
    ];

    expect(authoringJourney(1, summary, fixtureRows, null).action).toEqual({
      kind: 'select-node',
      label: 'Pin mock output',
      nodeId: 'n1',
    });

    const completed = authoringJourney(1, summary, fixtureRows, successfulResponse);
    expect(completed.steps.find((step) => step.key === 'simulate')).toMatchObject({
      state: 'ready',
      detail: 'success',
    });
    expect(completed.action).toEqual({ kind: 'none', label: 'Ready' });
  });
});

describe('canvasCoachPrompt', () => {
  const successfulResponse: SimulationResponse = {
    validated: true,
    compiled: true,
    success: true,
    graphName: 'g',
    outputNode: 'n1',
    output: { ok: true },
    results: {},
    statusMap: {},
    mockedNodeIds: ['n2'],
    realNodeIds: ['n1'],
    terminalOutputConforms: true,
    diagnostics: [],
    errors: [],
    generatedDsl: '',
  };

  it('turns an empty canvas into an in-canvas add action', () => {
    const prompt = canvasCoachPrompt(4, summarizeCanvas([], []), [], null);

    expect(prompt).toEqual({
      state: 'compose',
      title: 'Add first operator',
      detail: '4 available',
      action: { kind: 'focus-palette', label: 'Add operator' },
    });
  });

  it('prioritizes disconnected topology before simulation guidance', () => {
    const prompt = canvasCoachPrompt(
      2,
      summarizeCanvas(
        [
          { id: 'n1', operatorRef: 'risk:a', position: { x: 0, y: 0 } },
          { id: 'n2', operatorRef: 'risk:b', position: { x: 0, y: 0 } },
        ],
        [],
      ),
      [],
      null,
    );

    expect(prompt).toEqual({
      state: 'connect',
      title: 'Connect open nodes',
      detail: '2 open',
      action: { kind: 'select-node', label: 'Select open node', nodeId: 'n1' },
    });
  });

  it('points authors at mock output pinning before the first run', () => {
    const prompt = canvasCoachPrompt(
      1,
      summarizeCanvas([{ id: 'n1', operatorRef: 'risk:design', position: { x: 0, y: 0 } }], []),
      [
        {
          nodeId: 'n1',
          label: 'Risk',
          operatorRef: 'risk:design',
          state: 'warning',
          runMode: 'mocked',
          fixtureLabel: 'server sample',
          detail: 'server sample',
        },
      ],
      null,
    );

    expect(prompt).toEqual({
      state: 'mock',
      title: 'Pin mock output',
      detail: '1 sample',
      action: { kind: 'select-node', label: 'Pin mock output', nodeId: 'n1' },
    });
  });

  it('closes with a trust summary after a successful run', () => {
    const prompt = canvasCoachPrompt(
      1,
      summarizeCanvas([{ id: 'n1', operatorRef: 'risk:real', position: { x: 0, y: 0 } }], []),
      [],
      successfulResponse,
    );

    expect(prompt).toEqual({
      state: 'ready',
      title: 'Graph ready',
      detail: '1 real / 1 mocked',
      action: { kind: 'none', label: 'Ready' },
    });
  });
});

describe('canvasNodeFocusState', () => {
  it('suggests the node targeted by the canvas coach action', () => {
    const prompt = canvasCoachPrompt(
      2,
      summarizeCanvas(
        [
          { id: 'n1', operatorRef: 'risk:a', position: { x: 0, y: 0 } },
          { id: 'n2', operatorRef: 'risk:b', position: { x: 0, y: 0 } },
        ],
        [],
      ),
      [],
      null,
    );

    expect(canvasNodeFocusState('n1', '', prompt)).toBe('suggested');
    expect(canvasNodeFocusState('n2', '', prompt)).toBe('none');
  });

  it('lets explicit selection override the suggested next node', () => {
    const prompt = canvasCoachPrompt(
      1,
      summarizeCanvas([{ id: 'mocked', operatorRef: 'risk:design', position: { x: 0, y: 0 } }], []),
      [
        {
          nodeId: 'mocked',
          label: 'Risk',
          operatorRef: 'risk:design',
          state: 'warning',
          runMode: 'mocked',
          fixtureLabel: 'server sample',
          detail: 'server sample',
        },
      ],
      null,
    );

    expect(canvasNodeFocusState('mocked', 'mocked', prompt)).toBe('selected');
  });

  it('does not suggest a node for palette or simulation actions', () => {
    const addPrompt = canvasCoachPrompt(2, summarizeCanvas([], []), [], null);

    expect(canvasNodeFocusState('n1', '', addPrompt)).toBe('none');
  });
});

describe('simulationTraceRows', () => {
  it('maps simulate results into node-level trace rows', () => {
    const response: SimulationResponse = {
      validated: true,
      compiled: true,
      success: true,
      graphName: 'g',
      outputNode: 'b',
      output: null,
      results: {
        a: { eligible: true },
        b: 'approved',
      },
      statusMap: {},
      mockedNodeIds: ['a'],
      realNodeIds: ['b'],
      terminalOutputConforms: true,
      diagnostics: [],
      errors: [],
      generatedDsl: '',
    };

    expect(
      simulationTraceRows(
        [
          { id: 'a', operatorRef: 'risk:score', label: 'Score', position: { x: 0, y: 0 } },
          { id: 'b', operatorRef: 'risk:decision', position: { x: 0, y: 0 } },
          { id: 'c', operatorRef: 'risk:unused', position: { x: 0, y: 0 } },
        ],
        response,
      ),
    ).toEqual([
      {
        nodeId: 'a',
        label: 'Score',
        operatorRef: 'risk:score',
        status: 'mocked',
        outputPreview: '{"eligible":true}',
      },
      {
        nodeId: 'b',
        label: 'b',
        operatorRef: 'risk:decision',
        status: 'real',
        outputPreview: 'approved',
      },
      {
        nodeId: 'c',
        label: 'c',
        operatorRef: 'risk:unused',
        status: 'unknown',
        outputPreview: 'no output',
      },
    ]);
  });

  it('returns no rows before a simulation result exists', () => {
    expect(simulationTraceRows([], null)).toEqual([]);
  });
});

describe('simulationFixtureRows', () => {
  const designOperator: OperatorDefinition = {
    operatorRef: 'risk:design',
    display: { name: 'Design Risk' },
    lowering: { mode: 'design' },
    ports: { inputs: [], outputs: [] },
  };
  const transformOperator: OperatorDefinition = {
    operatorRef: 'risk:transform',
    display: { name: 'Transform Risk' },
    lowering: { mode: 'transform' },
    ports: { inputs: [], outputs: [] },
  };

  it('surfaces likely mocked nodes and fixture JSON errors before simulation', () => {
    const compiled = compileFixtureDrafts(
      {
        design: '',
        pinned: '{"ok":true}',
        broken: '{nope',
      },
      {
        pinned: '{"score":720}',
      },
    );

    expect(
      simulationFixtureRows(
        [
          { id: 'design', operatorRef: 'risk:design', label: 'Design', position: { x: 0, y: 0 } },
          { id: 'pinned', operatorRef: 'risk:transform', label: 'Pinned', position: { x: 0, y: 0 } },
          { id: 'broken', operatorRef: 'risk:transform', label: 'Broken', position: { x: 0, y: 0 } },
          { id: 'plain', operatorRef: 'risk:transform', label: 'Plain', position: { x: 0, y: 0 } },
        ],
        [designOperator, transformOperator],
        compiled,
        {
          design: '',
          pinned: '{"ok":true}',
          broken: '{nope',
        },
        {
          pinned: '{"score":720}',
        },
        null,
      ),
    ).toEqual([
      {
        nodeId: 'design',
        label: 'Design',
        operatorRef: 'risk:design',
        state: 'warning',
        runMode: 'mocked',
        fixtureLabel: 'server sample',
        detail: 'server sample',
      },
      {
        nodeId: 'pinned',
        label: 'Pinned',
        operatorRef: 'risk:transform',
        state: 'ready',
        runMode: 'mocked',
        fixtureLabel: 'pin + assert',
        detail: 'fixture set',
      },
      {
        nodeId: 'broken',
        label: 'Broken',
        operatorRef: 'risk:transform',
        state: 'blocked',
        runMode: 'mocked',
        fixtureLabel: 'json error',
        detail: expect.stringContaining('Invalid JSON in output'),
      },
      {
        nodeId: 'plain',
        label: 'Plain',
        operatorRef: 'risk:transform',
        state: 'pending',
        runMode: 'unknown',
        fixtureLabel: 'server sample',
        detail: 'not run',
      },
    ]);
  });

  it('uses the server simulation result as the authoritative run mode', () => {
    const response: SimulationResponse = {
      validated: true,
      compiled: true,
      success: true,
      graphName: 'g',
      outputNode: 'real',
      output: null,
      results: {},
      statusMap: {},
      mockedNodeIds: ['mocked'],
      realNodeIds: ['real'],
      terminalOutputConforms: true,
      diagnostics: [],
      errors: [],
      generatedDsl: '',
    };

    const rows = simulationFixtureRows(
      [
        { id: 'real', operatorRef: 'risk:design', label: 'Real', position: { x: 0, y: 0 } },
        { id: 'mocked', operatorRef: 'risk:transform', label: 'Mocked', position: { x: 0, y: 0 } },
      ],
      [designOperator, transformOperator],
      { fixtures: {}, errors: {} },
      {},
      {},
      response,
    );

    expect(rows.map((row) => [row.nodeId, row.runMode, row.state, row.detail])).toEqual([
      ['real', 'real', 'ready', 'real run'],
      ['mocked', 'mocked', 'warning', 'server sample'],
    ]);
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

describe('simulation fixtures', () => {
  it('generates samples with the same explicit-value precedence as the server generator', () => {
    const sample = sampleFromSchemaEnvelope({
      schema: {
        type: 'string',
        const: 'CONST',
        default: 'DEFAULT',
        examples: ['EXAMPLE'],
        enum: ['ENUM'],
      },
    });

    expect(sample).toBe('CONST');
  });

  it('generates deterministic canonical samples for nested schemas', () => {
    expect(
      sampleFromSchemaEnvelope({
        schema: {
          type: 'object',
          properties: {
            email: { type: 'string', format: 'email' },
            score: { type: 'integer', minimum: 620 },
            active: { type: 'boolean' },
            tags: { type: 'array', items: { type: 'string' } },
          },
          required: ['missing'],
        },
      }),
    ).toEqual({
      email: 'user@example.com',
      score: 620,
      active: false,
      tags: ['string'],
      missing: null,
    });
  });

  it('prefills multi-output operators as one object keyed by output port', () => {
    const operator: OperatorDefinition = {
      operatorRef: 'risk:decision',
      ports: {
        inputs: [],
        outputs: [
          {
            name: 'decision',
            schema: { schema: { type: 'object', properties: { eligible: { const: true } } } },
          },
          {
            name: 'reason',
            schema: { schema: { type: 'string', default: 'ok' } },
          },
        ],
      },
    };

    expect(JSON.parse(fixtureDraftForOperator(operator))).toEqual({
      decision: { eligible: true },
      reason: 'ok',
    });
  });

  it('compiles fixture JSON text into simulate request fixtures and reports invalid drafts', () => {
    const compiled = compileFixtureDrafts({
      a: '{"eligible":true}',
      b: '',
      c: '{nope',
    });

    expect(compiled.fixtures).toEqual({ a: { output: { eligible: true } } });
    expect(compiled.errors.b).toBeUndefined();
    expect(compiled.errors.c).toContain('Invalid JSON');
  });

  it('compiles expected input assertion JSON alongside output pins', () => {
    const compiled = compileFixtureDrafts(
      {
        a: '{"eligible":true}',
        b: '',
        c: '{nope',
      },
      {
        a: '{"score":720,"amount":250000}',
        b: '{"score":680}',
        d: '{nope',
      },
    );

    expect(compiled.fixtures).toEqual({
      a: { output: { eligible: true }, expectedInput: { score: 720, amount: 250000 } },
      b: { output: null, expectedInput: { score: 680 } },
    });
    expect(compiled.errors.c).toContain('Invalid JSON in output');
    expect(compiled.errors.d).toContain('Invalid JSON in expected input');
  });
});
