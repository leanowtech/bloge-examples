import { describe, expect, it } from 'vitest';

import {
  authoringJourney,
  autoLayoutCanvas,
  canvasCoachPrompt,
  canvasEdgeLabelForZoom,
  canvasFocusPath,
  canvasNodeFocusState,
  canvasZoomPresentation,
  compileFixtureDrafts,
  compileSimulationTableRows,
  connectionCandidatesMessage,
  connectionDecisionMessage,
  connectionGuideRows,
  endpointFromHandle,
  evaluateSimulationTableResult,
  fixtureDraftForOperator,
  fromGraphDraft,
  handleIdForPort,
  indexConnectionCandidates,
  isRunSuccessful,
  mergeNodeFixtures,
  nodeStatuses,
  operatorPaletteView,
  operatorLibraryImportMessage,
  operatorLibraryValidationLevel,
  operatorLibraryValidationMessage,
  portNameFromHandle,
  sampleFromSchemaEnvelope,
  simulationChecklist,
  simulationFixtureRows,
  simulationRunSummary,
  simulationTableSummary,
  simulationTraceRows,
  summarizeCanvas,
  summarizeOperator,
  toConnectionCandidatesRequest,
  toConnectionCheckRequest,
  toExportableGraphDraft,
  toGraphDraft,
  toSimulationRequest,
} from './draftModel';
import type { GraphDraft, OperatorDefinition, SimulationResponse } from './types';

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

  it('carries graph-level input schema when provided', () => {
    const inputSchema = {
      format: 'json-schema',
      version: '2020-12',
      schema: {
        type: 'object',
        properties: { userId: { type: 'string' } },
        required: ['userId'],
      },
    };
    const draft = toGraphDraft(
      'myGraph',
      [{ id: 'a', operatorRef: 'x', position: { x: 0, y: 0 } }],
      [],
      'a',
      inputSchema,
    );

    expect(draft.inputSchema).toEqual(inputSchema);
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

  it('derives nodePath input bindings from data edges so configurable operators can reference incoming data', () => {
    const draft = toGraphDraft(
      'myGraph',
      [
        { id: 'score', operatorRef: 'risk:score', position: { x: 0, y: 0 } },
        { id: 'decision', operatorRef: 'bloge:decisionTable', position: { x: 200, y: 0 } },
      ],
      [
        {
          id: 'e1',
          source: 'score',
          target: 'decision',
          sourcePort: 'decision',
          sourcePath: 'score',
          targetPort: 'inputs',
          targetPath: 'score',
          bindingKey: 'score',
        },
      ],
      'decision',
    );

    expect(draft.nodes[1].inputs).toMatchObject({
      score: {
        kind: 'nodePath',
        nodeId: 'score',
        sourcePort: 'decision',
        path: 'score',
        targetPort: 'inputs',
        targetPath: 'score',
      },
    });
  });

  it('preserves non-data route edges without turning them into node input bindings', () => {
    const draft = toGraphDraft(
      'myGraph',
      [
        { id: 'source', operatorRef: 'risk:score', position: { x: 0, y: 0 } },
        { id: 'approve', operatorRef: 'risk:approval', position: { x: 200, y: 0 } },
      ],
      [
        {
          id: 'route_source_approve',
          source: 'source',
          target: 'approve',
          kind: 'route',
          condition: 'source.output.score >= 760',
        },
      ],
      'approve',
    );

    expect(draft.edges[0]).toMatchObject({
      kind: 'route',
      condition: 'source.output.score >= 760',
    });
    expect(draft.nodes[1].inputs).toEqual({});
  });
});

describe('fromGraphDraft', () => {
  it('restores canvas nodes, edges, graph schemas, and frozen operator snapshots from a draft', () => {
    const draft: GraphDraft = {
      schemaVersion: 'bloge.visualGraphDraft.v1',
      graphName: 'migratedEligibility',
      inputSchema: {
        format: 'json-schema',
        version: '2020-12',
        schema: {
          type: 'object',
          properties: { score: { type: 'integer' } },
          required: ['score'],
        },
      },
      outputSchema: {
        format: 'json-schema',
        version: '2020-12',
        schema: {
          type: 'object',
          properties: { finalDecision: { type: 'string' } },
        },
      },
      nodes: [
        {
          id: 'eligibility',
          operatorRef: 'risk:eligibility',
          label: 'eligibility',
          position: { x: 120, y: 120 },
        },
        {
          id: 'response',
          operatorRef: 'bloge:transform',
          label: 'response',
          config: { assignments: [{ field: 'eligible', expression: 'eligibility.output.eligible' }] },
          position: { x: 480, y: 120 },
        },
      ],
      edges: [
        {
          id: 'data_eligibility_response_eligible',
          kind: 'data',
          source: { nodeId: 'eligibility', port: 'output', path: 'eligible' },
          target: { nodeId: 'response', port: 'inputs', path: 'eligible' },
        },
        {
          id: 'route_eligibility_response',
          kind: 'route',
          source: { nodeId: 'eligibility' },
          target: { nodeId: 'response' },
          condition: 'eligibility.output.eligible == true',
        },
      ],
      visualLayout: {
        graphContract: {
          outputSchema: {
            format: 'json-schema',
            version: '2020-12',
            schema: {
              type: 'object',
              properties: { eligible: { type: 'boolean' } },
            },
          },
        },
      },
      output: { nodeId: 'response', path: '' },
      operatorFingerprints: { eligibility: 'fp-risk' },
      operatorSnapshots: { eligibility: { operatorRef: 'risk:eligibility' } },
    };

    const canvas = fromGraphDraft(draft);

    expect(canvas.graphName).toBe('migratedEligibility');
    expect(canvas.nodes.map((node) => node.id)).toEqual(['eligibility', 'response']);
    expect(canvas.edges[0]).toMatchObject({
      source: 'eligibility',
      target: 'response',
      sourcePort: 'output',
      sourcePath: 'eligible',
      targetPort: 'inputs',
      targetPath: 'eligible',
      bindingKey: 'eligible',
    });
    expect(canvas.edges[1]).toMatchObject({
      kind: 'route',
      condition: 'eligibility.output.eligible == true',
    });
    expect(canvas.outputNodeId).toBe('response');
    expect(canvas.outputSchema?.schema).toMatchObject({
      properties: { finalDecision: { type: 'string' } },
    });
    expect(canvas.operatorFingerprints).toEqual({ eligibility: 'fp-risk' });
    expect(canvas.operatorSnapshots.eligibility.operatorRef).toBe('risk:eligibility');
  });

  it('restores output schema from legacy graphContract layout when the top-level field is absent', () => {
    const canvas = fromGraphDraft({
      schemaVersion: 'bloge.visualGraphDraft.v1',
      graphName: 'legacyDraft',
      nodes: [{ id: 'response', operatorRef: 'bloge:transform', position: { x: 0, y: 0 } }],
      edges: [],
      visualLayout: {
        graphContract: {
          outputSchema: {
            format: 'json-schema',
            version: '2020-12',
            schema: {
              type: 'object',
              properties: { eligible: { type: 'boolean' } },
            },
          },
        },
      },
      output: { nodeId: 'response', path: '' },
    });

    expect(canvas.outputSchema?.schema).toMatchObject({
      properties: { eligible: { type: 'boolean' } },
    });
  });
});

describe('toSimulationRequest', () => {
  it('keeps request outputNode aligned with the draft output selection', () => {
    const inputSchema = {
      format: 'json-schema',
      version: '2020-12',
      schema: { type: 'object', properties: { score: { type: 'integer' } } },
    };
    const outputSchema = {
      format: 'json-schema',
      version: '2020-12',
      schema: { type: 'object', properties: { approved: { type: 'boolean' } } },
    };
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
      {},
      inputSchema,
      outputSchema,
    );

    expect(request.outputNode).toBe('chosen');
    expect(request.draft.output.nodeId).toBe('chosen');
    expect(request.draft.inputSchema).toEqual(inputSchema);
    expect(request.draft.outputSchema).toEqual(outputSchema);
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

describe('toExportableGraphDraft', () => {
  it('emits a portable graph draft snapshot with node fixtures', () => {
    const inputSchema = {
      format: 'json-schema',
      version: '2020-12',
      schema: {
        type: 'object',
        properties: { score: { type: 'integer' } },
        required: ['score'],
      },
    };
    const outputSchema = {
      format: 'json-schema',
      version: '2020-12',
      schema: {
        type: 'object',
        properties: { eligible: { type: 'boolean' } },
        required: ['eligible'],
      },
    };
    const draft = toExportableGraphDraft(
      'visualGraph',
      [{ id: 'n1', operatorRef: 'risk:eligibility', label: 'Eligibility', position: { x: 10, y: 20 } }],
      [],
      'n1',
      { n1: { output: { eligible: true }, expectedInput: { score: 720 } } },
      inputSchema,
      { outputSchema },
    );

    expect(draft).toMatchObject({
      schemaVersion: 'bloge.visualGraphDraft.v1',
      graphName: 'visualGraph',
      inputSchema,
      outputSchema,
      nodes: [{ id: 'n1', operatorRef: 'risk:eligibility', label: 'Eligibility' }],
      output: { nodeId: 'n1', path: '' },
      nodeFixtures: {
        n1: { output: { eligible: true }, expectedInput: { score: 720 } },
      },
    });
  });

  it('omits nodeFixtures when no fixture was authored', () => {
    const draft = toExportableGraphDraft(
      'visualGraph',
      [{ id: 'n1', operatorRef: 'risk:eligibility', position: { x: 10, y: 20 } }],
      [],
      'n1',
    );

    expect(draft).not.toHaveProperty('nodeFixtures');
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

  it('can preserve server-selected source and target field paths', () => {
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
      'score',
      'score',
    );

    expect(request.source).toEqual({ nodeId: 'a', port: 'decision', path: 'score' });
    expect(request.target).toEqual({ nodeId: 'b', port: 'profile', path: 'score' });
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
          target: { nodeId: 'ready', port: 'case', path: 'score' },
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
        targetPath: 'score',
        status: 'ready',
        accepted: true,
        detail: 'Schemas match.',
        actionHint: 'Connect to case.score.',
        fieldOptions: [
          {
            key: 'ready|case|score',
            path: 'score',
            label: 'case.score',
            status: 'ready',
            accepted: true,
            detail: 'Schemas match.',
          },
        ],
      },
      {
        key: 'wired|profile|',
        targetNodeId: 'wired',
        targetLabel: 'Wired Policy',
        targetOperatorRef: 'risk:wired',
        targetPort: 'profile',
        targetPath: '',
        status: 'wired',
        accepted: false,
        detail: 'Already connected.',
        actionHint: 'Already connected; remove the current edge before reconnecting.',
        fieldOptions: [],
      },
      {
        key: 'blocked|profile|',
        targetNodeId: 'blocked',
        targetLabel: 'Blocked Policy',
        targetOperatorRef: 'risk:blocked',
        targetPort: 'profile',
        targetPath: '',
        status: 'blocked',
        accepted: false,
        detail: 'visual.connection.schema: object -> number',
        actionHint: 'Try a nested field, add a transform, or choose another target.',
        fieldOptions: [],
      },
    ]);
  });

  it('groups field-level target choices and makes blocked rows actionable', () => {
    const index = indexConnectionCandidates({
      source: { nodeId: 'source', port: 'profile' },
      candidates: [
        {
          targetNodeId: 'review',
          targetNodeLabel: 'Risk Review',
          targetOperatorRef: 'risk:review',
          targetSurface: 'input',
          target: { nodeId: 'review', port: 'facts' },
          accepted: false,
          targetStatus: 'blocked',
          summary: { message: 'Connection rejected by server.' },
          diagnostics: [{ level: 'error', code: 'visual.connection.schema', message: 'object -> number' }],
        },
        {
          targetNodeId: 'review',
          targetNodeLabel: 'Risk Review',
          targetOperatorRef: 'risk:review',
          targetSurface: 'input',
          target: { nodeId: 'review', port: 'facts', path: 'score' },
          accepted: true,
          targetStatus: 'ready',
          summary: { message: 'Schemas match.' },
        },
        {
          targetNodeId: 'review',
          targetNodeLabel: 'Risk Review',
          targetOperatorRef: 'risk:review',
          targetSurface: 'input',
          target: { nodeId: 'review', port: 'facts', path: 'amount' },
          accepted: true,
          targetStatus: 'ready',
          summary: { message: 'Schemas match.' },
        },
        {
          targetNodeId: 'blocked',
          targetNodeLabel: 'Legacy Check',
          targetOperatorRef: 'risk:legacy',
          targetSurface: 'input',
          target: { nodeId: 'blocked', port: 'payload' },
          accepted: false,
          targetStatus: 'blocked',
          summary: { message: 'Connection rejected by server.' },
          diagnostics: [{ level: 'error', code: 'visual.connection.schema', message: 'object -> string' }],
        },
      ],
    });

    const rows = connectionGuideRows([
      { id: 'review', operatorRef: 'risk:review', label: 'Risk Review', position: { x: 0, y: 0 } },
      { id: 'blocked', operatorRef: 'risk:legacy', label: 'Legacy Check', position: { x: 0, y: 0 } },
    ], index);

    expect(rows).toHaveLength(2);
    expect(rows[0]).toMatchObject({
      key: 'review|facts|',
      targetNodeId: 'review',
      targetPort: 'facts',
      targetPath: 'score',
      status: 'ready',
      detail: '2 compatible fields found.',
      actionHint: 'Choose the field path that should feed this input.',
      fieldOptions: [
        { path: 'score', label: 'facts.score', status: 'ready', accepted: true },
        { path: 'amount', label: 'facts.amount', status: 'ready', accepted: true },
      ],
    });
    expect(rows[1]).toMatchObject({
      key: 'blocked|payload|',
      status: 'blocked',
      detail: 'visual.connection.schema: object -> string',
      actionHint: 'Try a nested field, add a transform, or choose another target.',
      fieldOptions: [],
    });
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

  it('distinguishes managed and unmanaged external-write protocols', () => {
    const unmanaged = summarizeOperator({
      operatorRef: 'orders:create',
      capabilities: { effect: 'WRITE_EXTERNAL' },
    });
    expect(unmanaged).toMatchObject({
      externalWrite: true,
      managedWrite: false,
      sideEffectBadgeLabel: 'write protocol required',
    });

    const managed = summarizeOperator({
      operatorRef: 'orders:createManaged',
      capabilities: {
        effect: 'WRITE_EXTERNAL',
        sideEffectProtocol: {
          schemaVersion: 'bloge.sideEffectProtocol.v1',
          mode: 'JOURNALED',
          commitReceiptRequired: true,
          reconciliationRequired: true,
          reconcilerRef: 'orders.status',
          idempotencyKeySource: 'input.params.idempotencyKey',
          reconciliationLookupSource: 'input.params.lookupRef',
          commitReceiptSource: 'response.headers.x-receipt-id',
        },
      },
    });
    expect(managed).toMatchObject({
      externalWrite: true,
      managedWrite: true,
      sideEffectBadgeLabel: 'managed write',
    });
    expect(managed.sideEffectNotice).toContain('orders.status');
  });

  it('classifies special operator families with contract hints', () => {
    expect(summarizeOperator({
      operatorRef: 'bloge:decisionTable',
      display: { name: 'Decision Table', tags: ['logic', 'rules'] },
      ports: {
        inputs: [{ name: 'inputs', schema: { schema: { type: 'object' } } }],
        outputs: [{ name: 'output', schema: { schema: { type: 'object' } } }],
      },
    })).toMatchObject({
      visualKind: 'decision-table',
      visualLabel: 'Decision table',
      contractHint: 'conditions -> matched decision',
      inputContractLabel: 'conditions',
      outputContractLabel: 'decision row',
    });

    expect(summarizeOperator({
      operatorRef: '__foreach__:enrichOrders',
      display: { name: 'foreach enrich orders' },
      ports: {
        inputs: [{ name: 'input', schema: { schema: { type: 'object' } } }],
        outputs: [{ name: 'output', schema: { schema: { type: 'array', items: { type: 'object' } } } }],
      },
    })).toMatchObject({
      visualKind: 'foreach',
      visualLabel: 'Foreach',
      contractHint: 'collection -> per-item results',
      inputContractLabel: 'item source',
      outputContractLabel: 'result list',
    });

    expect(summarizeOperator({
      operatorRef: 'httpResource',
      display: { name: 'HTTP Resource', tags: ['resource', 'advanced'] },
      source: { kind: 'bloge-operator' },
      ports: {
        inputs: [{ name: 'input', schema: { schema: { type: 'object' } } }],
        outputs: [{ name: 'output', schema: { schema: { type: 'object' } } }],
      },
      lowering: { mode: 'native' },
    })).toMatchObject({
      visualKind: 'resource',
      visualLabel: 'Resource',
      contractHint: 'params -> payload',
      inputContractLabel: 'params',
      outputContractLabel: 'payload',
    });

    expect(summarizeOperator({
      operatorRef: 'httpRequest',
      display: { name: 'Http Request', tags: ['java', 'http', 'api', 'rest'] },
      source: { kind: 'java-operator' },
      ports: {
        inputs: [{ name: 'input', schema: { schema: { type: 'object' } } }],
        outputs: [{ name: 'output', schema: { schema: { type: 'object' } } }],
      },
    })).toMatchObject({
      visualKind: 'http',
      visualLabel: 'HTTP',
      contractHint: 'request -> response',
      inputContractLabel: 'request',
      outputContractLabel: 'response',
    });

    expect(summarizeOperator({
      operatorRef: 'MockCitationStreamingOperator',
      display: { name: 'Mock Citation Streaming Operator', tags: ['streaming'] },
      source: { kind: 'java-streaming-operator' },
      capabilities: { streaming: true },
      ports: {
        inputs: [{ name: 'input', schema: { schema: { type: 'object' } } }],
        outputs: [{ name: 'output', schema: { schema: { type: 'object' } } }],
      },
      runtimeReadiness: {
        state: 'RUNTIME_BLOCKED',
        level: 'warning',
        executable: false,
        summary: 'Streaming runtime not supported by this request-response runtime.',
      },
    })).toMatchObject({
      visualKind: 'streaming',
      readinessState: 'runtime-blocked',
      readinessLevel: 'warning',
      readinessBadgeLabel: 'blocked',
      readinessNodeNotice: 'Runtime blocked',
      readinessNotice: 'Streaming runtime not supported by this request-response runtime.',
    });

    expect(summarizeOperator({
      operatorRef: 'risk:eligibility',
      display: { name: 'Eligibility Gate', tags: ['risk', 'decision', 'policy'] },
      source: { kind: 'user-library' },
      lowering: { mode: 'design' },
      ports: {
        inputs: [{ name: 'applicant', schema: { schema: { type: 'object' } } }],
        outputs: [{ name: 'decision', schema: { schema: { type: 'object' } } }],
      },
    })).toMatchObject({
      visualKind: 'design',
      visualLabel: 'Design',
      contractHint: 'schema-only object -> object',
      inputContractLabel: 'schema input',
      outputContractLabel: 'schema output',
      readinessBadgeLabel: 'design',
      readinessNodeNotice: 'Design-only',
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
    expect(layout[0].position.x).toBe(96);
    expect(layout[1].position.y).toBeGreaterThan(layout[0].position.y);
  });

  it('keeps dense fan-out nodes far enough apart to avoid card and edge-label overlap', () => {
    const layout = autoLayoutCanvas(
      [
        { id: 'root', operatorRef: 'root', position: { x: 0, y: 0 } },
        { id: 'profile', operatorRef: 'resource:profile', position: { x: 0, y: 0 } },
        { id: 'wallet', operatorRef: 'resource:wallet', position: { x: 0, y: 0 } },
        { id: 'orders', operatorRef: 'resource:orders', position: { x: 0, y: 0 } },
        { id: 'notifications', operatorRef: 'resource:notifications', position: { x: 0, y: 0 } },
        { id: 'join', operatorRef: 'bloge:transform', position: { x: 0, y: 0 } },
      ],
      [
        { id: 'e1', source: 'root', target: 'profile', sourcePath: 'customer.profile.primaryFacts', targetPath: 'params.customerId' },
        { id: 'e2', source: 'root', target: 'wallet', sourcePath: 'customer.wallet.balance', targetPath: 'params.customerId' },
        { id: 'e3', source: 'root', target: 'orders', sourcePath: 'customer.orders.items', targetPath: 'params.customerId' },
        { id: 'e4', source: 'root', target: 'notifications', sourcePath: 'customer.notifications.unread', targetPath: 'params.customerId' },
        { id: 'e5', source: 'profile', target: 'join', sourcePath: 'payload', targetPath: 'inputs.profile' },
        { id: 'e6', source: 'wallet', target: 'join', sourcePath: 'payload', targetPath: 'inputs.wallet' },
        { id: 'e7', source: 'orders', target: 'join', sourcePath: 'payload', targetPath: 'inputs.orders' },
        { id: 'e8', source: 'notifications', target: 'join', sourcePath: 'payload', targetPath: 'inputs.notifications' },
      ],
    );

    const byId = new Map(layout.map((node) => [node.id, node.position]));
    const middleLayer = ['profile', 'wallet', 'orders', 'notifications']
      .map((id) => byId.get(id)?.y ?? 0)
      .sort((left, right) => left - right);
    for (let index = 1; index < middleLayer.length; index += 1) {
      expect(middleLayer[index] - middleLayer[index - 1]).toBeGreaterThanOrEqual(236);
    }
    expect((byId.get('profile')?.x ?? 0) - (byId.get('root')?.x ?? 0)).toBeGreaterThanOrEqual(408);
    expect((byId.get('join')?.x ?? 0) - (byId.get('profile')?.x ?? 0)).toBeGreaterThanOrEqual(408);
  });

  it('reserves a top bus lane for long-span edge labels across populated layers', () => {
    const layout = autoLayoutCanvas(
      [
        { id: 'profile', operatorRef: 'resource:user-service.getProfile', position: { x: 0, y: 0 } },
        { id: 'wallet', operatorRef: 'resource:wallet-service.getBalance', position: { x: 0, y: 0 } },
        { id: 'recommendations', operatorRef: 'resource:recommendation-service.forUser', position: { x: 0, y: 0 } },
        { id: 'notifications', operatorRef: 'resource:notification-service.unread', position: { x: 0, y: 0 } },
        { id: 'response', operatorRef: 'bloge:transform', position: { x: 0, y: 0 } },
      ],
      [
        { id: 'e1', source: 'profile', target: 'wallet', sourcePath: 'payload.userId', targetPath: 'params.userId' },
        { id: 'e2', source: 'profile', target: 'recommendations', sourcePath: 'payload.userId', targetPath: 'params.userId' },
        { id: 'e3', source: 'profile', target: 'notifications', sourcePath: 'payload.userId', targetPath: 'params.userId' },
        { id: 'e4', source: 'profile', target: 'response', sourcePath: 'payload.name', targetPath: 'inputs.name' },
        { id: 'e5', source: 'profile', target: 'response', sourcePath: 'payload.tier', targetPath: 'inputs.tier' },
        { id: 'e6', source: 'wallet', target: 'response', sourcePath: 'payload.amount', targetPath: 'inputs.walletAmount' },
        { id: 'e7', source: 'recommendations', target: 'response', sourcePath: 'payload.items', targetPath: 'inputs.recommendations' },
        { id: 'e8', source: 'notifications', target: 'response', sourcePath: 'payload.count', targetPath: 'inputs.unreadCount' },
      ],
    );

    const byId = new Map(layout.map((node) => [node.id, node.position]));
    const busY = byId.get('profile')?.y ?? 0;
    expect(byId.get('response')?.y).toBe(busY);
    expect(byId.get('wallet')?.y).toBeGreaterThanOrEqual(busY + 236);
    expect(byId.get('recommendations')?.y).toBeGreaterThanOrEqual(busY + 236);
    expect(byId.get('notifications')?.y).toBeGreaterThanOrEqual(busY + 236);
  });

  it('expands column spacing for long edge labels so paths have readable room', () => {
    const layout = autoLayoutCanvas(
      [
        { id: 'source', operatorRef: 'source', position: { x: 0, y: 0 } },
        { id: 'target', operatorRef: 'target', position: { x: 0, y: 0 } },
      ],
      [
        {
          id: 'long',
          source: 'source',
          target: 'target',
          sourcePort: 'payload',
          sourcePath: 'customer.profile.primaryRiskSignal.longNestedField',
          targetPort: 'inputs',
          targetPath: 'riskFacts.customerProfile.primaryRiskSignal',
        },
      ],
    );

    expect(layout[1].position.x - layout[0].position.x).toBeGreaterThanOrEqual(680);
  });

  it.each([
    { nodeCount: 25, width: 5 },
    { nodeCount: 100, width: 10 },
  ])('keeps $nodeCount-node fixtures overlap-free and deterministic', ({ nodeCount, width }) => {
    const depth = nodeCount / width;
    const nodes = Array.from({ length: nodeCount }, (_, index) => ({
      id: `n${index}`,
      operatorRef: `operator:${index}`,
      position: { x: 0, y: 0 },
    }));
    const edges = Array.from({ length: (depth - 1) * width }, (_, index) => {
      const layer = Math.floor(index / width);
      const row = index % width;
      return {
        id: `e${index}`,
        source: `n${layer * width + row}`,
        target: `n${(layer + 1) * width + row}`,
        sourcePort: 'payload',
        sourcePath: `business.field${row}`,
        targetPort: 'inputs',
        targetPath: `business.field${row}`,
      };
    });

    const startedAt = performance.now();
    const first = autoLayoutCanvas(nodes, edges);
    const layoutDurationMs = performance.now() - startedAt;
    const second = autoLayoutCanvas(nodes, edges);

    expect(second).toEqual(first);
    expect(layoutDurationMs, `${nodeCount}-node layout exceeded its interaction budget`)
      .toBeLessThan(200);
    for (let left = 0; left < first.length; left += 1) {
      for (let right = left + 1; right < first.length; right += 1) {
        const horizontalGap = Math.abs(first[left].position.x - first[right].position.x);
        const verticalGap = Math.abs(first[left].position.y - first[right].position.y);
        expect(
          horizontalGap >= 260 || verticalGap >= 164,
          `${first[left].id} overlaps ${first[right].id}`,
        ).toBe(true);
      }
    }
  });
});

describe('canvas semantic zoom and focus path', () => {
  it('reduces detail at stable zoom thresholds while preserving emphasized edge coordinates', () => {
    expect(canvasZoomPresentation(0.7)).toMatchObject({ tier: 'detail', edgeLabelMode: 'full' });
    expect(canvasZoomPresentation(0.5)).toMatchObject({ tier: 'compact', edgeLabelMode: 'summary' });
    expect(canvasZoomPresentation(0.2)).toMatchObject({ tier: 'overview', edgeLabelMode: 'hidden' });
    expect(canvasEdgeLabelForZoom('payload.score -> inputs.primaryScore', 0.5, false))
      .toBe('score -> primaryScore');
    expect(canvasEdgeLabelForZoom('payload.score -> inputs.primaryScore', 0.2, false)).toBe('');
    expect(canvasEdgeLabelForZoom('payload.score -> inputs.primaryScore', 0.2, true))
      .toBe('payload.score -> inputs.primaryScore');
  });

  it('focuses complete upstream and downstream paths without unrelated side branches', () => {
    const nodes = ['root', 'left', 'selected', 'output', 'side']
      .map((id) => ({ id, operatorRef: id, position: { x: 0, y: 0 } }));
    const edges = [
      { id: 'root-left', source: 'root', target: 'left' },
      { id: 'left-selected', source: 'left', target: 'selected' },
      { id: 'selected-output', source: 'selected', target: 'output' },
      { id: 'root-side', source: 'root', target: 'side' },
    ];

    const path = canvasFocusPath(nodes, edges, 'selected');

    expect([...path.nodeIds]).toEqual(['selected', 'output', 'left', 'root']);
    expect([...path.edgeIds]).toEqual(['root-left', 'left-selected', 'selected-output']);
  });

  it('bounds focus traversal for cycles and rejects an unknown anchor', () => {
    const nodes = ['a', 'b', 'c']
      .map((id) => ({ id, operatorRef: id, position: { x: 0, y: 0 } }));
    const edges = [
      { id: 'a-b', source: 'a', target: 'b' },
      { id: 'b-a', source: 'b', target: 'a' },
      { id: 'b-c', source: 'b', target: 'c' },
    ];

    expect([...canvasFocusPath(nodes, edges, 'a').nodeIds]).toEqual(['a', 'b', 'c']);
    expect(canvasFocusPath(nodes, edges, 'missing').nodeIds.size).toBe(0);
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

describe('simulationRunSummary', () => {
  const summary = summarizeCanvas(
    [
      { id: 'n1', operatorRef: 'risk:score', position: { x: 0, y: 0 } },
      { id: 'n2', operatorRef: 'risk:decision', position: { x: 0, y: 0 } },
    ],
    [{ id: 'e1', source: 'n1', target: 'n2' }],
    'n2',
  );

  it('summarizes a not-yet-run graph without hiding mock sample work', () => {
    const run = simulationRunSummary(
      summary,
      [
        {
          nodeId: 'n1',
          label: 'Risk Score',
          operatorRef: 'risk:score',
          state: 'warning',
          runMode: 'mocked',
          fixtureLabel: 'server sample',
          detail: 'server sample',
        },
      ],
      null,
    );

    expect(run).toMatchObject({
      state: 'pending',
      title: 'Ready to simulate',
      detail: '2 nodes selected',
    });
    expect(run.chips).toEqual([
      { key: 'terminal', label: 'Terminal', value: 'n2', state: 'ready' },
      { key: 'fixtures', label: 'Fixtures', value: 'none', state: 'pending' },
      { key: 'mock-samples', label: 'Mock Samples', value: '1 sample', state: 'warning' },
    ]);
  });

  it('blocks the run summary when fixture JSON is invalid', () => {
    const run = simulationRunSummary(
      summary,
      [
        {
          nodeId: 'n1',
          label: 'Risk Score',
          operatorRef: 'risk:score',
          state: 'blocked',
          runMode: 'mocked',
          fixtureLabel: 'json error',
          detail: 'Invalid JSON',
        },
      ],
      null,
    );

    expect(run.state).toBe('blocked');
    expect(run.title).toBe('Simulation blocked');
    expect(run.detail).toBe('1 fixture JSON error');
    expect(run.chips.find((chip) => chip.key === 'fixtures')).toEqual({
      key: 'fixtures',
      label: 'Fixtures',
      value: '1 invalid',
      state: 'blocked',
    });
  });

  it('summarizes a completed run with terminal, trust, fixture, and diagnostic facts', () => {
    const response: SimulationResponse = {
      validated: true,
      compiled: true,
      success: true,
      graphName: 'g',
      outputNode: 'n2',
      output: { approved: true },
      results: {},
      statusMap: {},
      mockedNodeIds: ['n1'],
      realNodeIds: ['n2'],
      terminalOutputConforms: true,
      diagnostics: [],
      errors: [],
      generatedDsl: '',
    };

    const run = simulationRunSummary(
      summary,
      [
        {
          nodeId: 'n1',
          label: 'Risk Score',
          operatorRef: 'risk:score',
          state: 'ready',
          runMode: 'mocked',
          fixtureLabel: 'output pin',
          detail: 'fixture set',
        },
      ],
      response,
    );

    expect(run).toMatchObject({
      state: 'success',
      title: 'Simulation succeeded',
      detail: '1 real / 1 mocked',
    });
    expect(run.chips).toContainEqual({ key: 'terminal', label: 'Terminal', value: 'n2', state: 'ready' });
    expect(run.chips).toContainEqual({ key: 'trust', label: 'Trust', value: '1 real / 1 mocked', state: 'warning' });
    expect(run.chips).toContainEqual({ key: 'fixtures', label: 'Fixtures', value: '1 pinned', state: 'ready' });
    expect(run.chips).toContainEqual({
      key: 'diagnostics',
      label: 'Diagnostics',
      value: '0 diagnostics',
      state: 'ready',
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
      body: 'Choose one operator from the palette to create the first node.',
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
      body: 'Find compatible targets for n1.',
      action: { kind: 'select-node', label: 'Find targets', nodeId: 'n1', guide: 'connection-guide' },
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
      body: 'Review the generated sample for Risk.',
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
      body: 'Simulation completed; mocked nodes remain marked on the canvas.',
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

describe('simulation table testing', () => {
  it('compiles table rows into simulate-ready context, fixture overrides, and expected output', () => {
    const compiled = compileSimulationTableRows([
      {
        id: 'case-1',
        name: 'Happy path',
        contextText: '{"applicantId":"a-1"}',
        fixturesText: '{"score":{"output":{"value":720},"expectedInput":{"id":"a-1"}}}',
        expectedOutputText: '{"decision":"approve"}',
      },
      {
        id: 'case-2',
        name: 'Bad context',
        contextText: '[]',
        fixturesText: '{}',
        expectedOutputText: '',
      },
    ]);

    expect(compiled.cases).toEqual([
      {
        id: 'case-1',
        name: 'Happy path',
        context: { applicantId: 'a-1' },
        fixtures: {
          score: {
            output: { value: 720 },
            expectedInput: { id: 'a-1' },
          },
        },
        hasExpectedOutput: true,
        expectedOutput: { decision: 'approve' },
      },
    ]);
    expect(compiled.errors['case-2']).toContain('Context must be a JSON object');
  });

  it('merges row-local fixture overrides over base node fixtures', () => {
    expect(mergeNodeFixtures(
      {
        n1: { output: { score: 710 } },
        n2: { output: { decision: 'manual' } },
      },
      {
        n2: { output: { decision: 'approve' } },
      },
    )).toEqual({
      n1: { output: { score: 710 } },
      n2: { output: { decision: 'approve' } },
    });
  });

  it('evaluates table result by canonical JSON output equality', () => {
    const response: SimulationResponse = {
      validated: true,
      compiled: true,
      success: true,
      graphName: 'visualGraph',
      outputNode: 'n2',
      output: { b: 2, a: 1 },
      results: {},
      statusMap: {},
      mockedNodeIds: ['n1'],
      realNodeIds: ['n2'],
      terminalOutputConforms: true,
      diagnostics: [],
      errors: [],
      generatedDsl: '',
    };

    expect(evaluateSimulationTableResult({
      id: 'case-1',
      name: 'Order-insensitive object',
      context: {},
      fixtures: {},
      hasExpectedOutput: true,
      expectedOutput: { a: 1, b: 2 },
    }, response)).toMatchObject({
      status: 'passed',
      detail: 'Output matched.',
    });

    expect(evaluateSimulationTableResult({
      id: 'case-2',
      name: 'Mismatch',
      context: {},
      fixtures: {},
      hasExpectedOutput: true,
      expectedOutput: { a: 1, b: 3 },
    }, response)).toMatchObject({
      status: 'failed',
      detail: 'Output mismatch.',
      actualOutput: { b: 2, a: 1 },
    });
  });

  it('summarizes table run progress', () => {
    const rows = [
      { id: 'a', name: 'A', contextText: '{}', fixturesText: '{}', expectedOutputText: '{}' },
      { id: 'b', name: 'B', contextText: '{}', fixturesText: '{}', expectedOutputText: '{}' },
    ];

    expect(simulationTableSummary(rows, {}, false)).toMatchObject({
      state: 'pending',
      detail: '0/2 passed',
    });
    expect(simulationTableSummary(rows, {
      a: { id: 'a', name: 'A', status: 'passed', detail: 'ok' },
      b: { id: 'b', name: 'B', status: 'failed', detail: 'bad' },
    }, false)).toMatchObject({
      state: 'failed',
      detail: '1/2 failed',
    });
    expect(simulationTableSummary(rows, {
      a: { id: 'a', name: 'A', status: 'passed', detail: 'ok' },
      b: { id: 'b', name: 'B', status: 'passed', detail: 'ok' },
    }, false)).toMatchObject({
      state: 'passed',
      detail: '2/2 passed',
    });
  });
});
