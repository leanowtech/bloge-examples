// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import AuthorCanvas from './AuthorCanvas';
import { CANVAS_EXAMPLE_TEMPLATES } from './canvasExamples';
import type {
  BuiltInFunctionDefinition,
  OperatorDefinition,
  OperatorLibrary,
  OperatorLibraryValidationResult,
  SchemaEnvelope,
  SimulationResponse,
} from './types';

const reactFlowMocks = vi.hoisted(() => ({
  fitView: vi.fn(),
}));

vi.mock('reactflow', async () => {
  const React = await import('react');

  return {
    default: function ReactFlowMock({ children, nodes, nodeTypes, onInit, onNodeClick, onNodeDoubleClick }: any) {
      React.useEffect(() => {
        onInit?.({ fitView: reactFlowMocks.fitView });
      }, [onInit]);
      return React.createElement(
        'div',
        { 'data-testid': 'react-flow' },
        nodes.map((node: any) => {
          const Component = nodeTypes?.[node.type] ?? (() => React.createElement('div', null, node.id));
          return React.createElement(
            'div',
            {
              key: node.id,
              'data-testid': `node-wrapper:${node.id}`,
              'data-position': `${node.position.x},${node.position.y}`,
              onClick: () => onNodeClick?.({}, node),
              onDoubleClick: () => onNodeDoubleClick?.({}, node),
            },
            React.createElement(Component, {
              id: node.id,
              data: node.data,
              selected: Boolean(node.selected),
            }),
          );
        }),
        children,
      );
    },
    Handle: ({ id, type, title, className }: any) =>
      React.createElement('span', {
        className,
        'data-testid': `handle:${type}:${id ?? ''}`,
        title,
      }),
    Background: () => null,
    Controls: () => null,
    MiniMap: () => null,
    Position: { Left: 'left', Right: 'right' },
    addEdge: (edge: unknown, edges: unknown[]) => [...edges, edge],
    applyEdgeChanges: (_changes: unknown, edges: unknown[]) => edges,
    applyNodeChanges: (_changes: unknown, nodes: unknown[]) => nodes,
  };
});

describe('AuthorCanvas operator-library intake', () => {
  let root: Root | null = null;
  let host: HTMLDivElement;
  let imported = false;
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    reactFlowMocks.fitView.mockReset();
    imported = false;
    host = document.createElement('div');
    document.body.appendChild(host);
    fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/api/visual/operators') {
        return jsonResponse({ operators: imported ? [eligibilityOperator()] : [] });
      }
      if (url === '/admin/visual-operator-libraries/validate-text') {
        expect(init).toMatchObject({
          method: 'POST',
          headers: { 'Content-Type': 'text/plain' },
          body: sampleLibraryYaml,
        });
        return jsonResponse(validationResult());
      }
      if (url.startsWith('/admin/visual-operator-libraries/import-text?')) {
        expect(url).toContain('actor=author-canvas');
        expect(url).toContain('changeSource=react-author');
        expect(init).toMatchObject({
          method: 'POST',
          headers: { 'Content-Type': 'text/plain' },
          body: sampleLibraryYaml,
        });
        imported = true;
        return jsonResponse(operatorLibrary(), { status: 201 });
      }
      throw new Error(`Unexpected fetch: ${url}`);
    });
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(async () => {
    if (root) {
      await act(async () => root?.unmount());
      root = null;
    }
    host.remove();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('validates and imports pasted user operators, then exposes them to the palette and canvas', async () => {
    await act(async () => {
      root = createRoot(host);
      root.render(<AuthorCanvas />);
    });

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/visual/operators'));
    expect(document.body.textContent).toContain('No operators. Is the server running?');

    await setControlValue(query<HTMLTextAreaElement>('[data-testid="operator-library-source"]'), sampleLibraryYaml);
    await click(query<HTMLButtonElement>('[data-testid="operator-library-validate"]'));

    await waitFor(() =>
      expect(query('[data-testid="operator-library-notice"]').textContent)
        .toContain('risk-policy: Schema-only library is ready for design-time authoring.'),
    );

    await click(query<HTMLButtonElement>('[data-testid="operator-library-import"]'));

    await waitFor(() =>
      expect(query('[data-testid="operator-library-notice"]').textContent)
        .toContain('Imported risk-policy (1 operator).'),
    );
    await waitFor(() =>
      expect(query('[data-operator-ref="risk:eligibility"]').textContent).toContain('Eligibility'),
    );
    expect(query<HTMLTextAreaElement>('[data-testid="operator-library-source"]').value)
      .toContain('"libraryId": "risk-policy"');

    await click(query<HTMLButtonElement>('[data-testid="operator-button:risk:eligibility"]'));

    await waitFor(() =>
      expect(query('[data-testid^="canvas-node:"][data-operator-ref="risk:eligibility"]').textContent)
        .toContain('Eligibility'),
    );
    expect(document.body.textContent).toContain('1 nodes');
    expect(document.body.textContent).toContain('Output n1');
  });

  it('loads concrete operator-library schema examples into the source editor', async () => {
    await act(async () => {
      root = createRoot(host);
      root.render(<AuthorCanvas />);
    });

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/visual/operators'));
    await click(query<HTMLButtonElement>('[data-testid="operator-library-example:risk-policy"]'));

    const source = query<HTMLTextAreaElement>('[data-testid="operator-library-source"]');
    expect(source.value).toContain('"libraryId": "risk-policy-starter"');
    expect(source.value).toContain('"inputs"');
    expect(source.value).toContain('"outputs"');
    expect(query('[data-testid="operator-library-notice"]').textContent)
      .toContain('Loaded Risk policy example. Validate before importing.');

    await click(query<HTMLButtonElement>('[data-testid="operator-library-example:order-fulfillment"]'));

    expect(source.value).toContain('"libraryId": "order-fulfillment-starter"');
    expect(source.value).toContain('"operatorRef": "orders:route-sla"');
  });
});

describe('AuthorCanvas built-in canvas examples', () => {
  let root: Root | null = null;
  let host: HTMLDivElement;
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
    fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/api/visual/operators') {
        return jsonResponse({
          operators: [
            loanApplicantResourceOperator(),
            primaryCreditResourceOperator(),
            secondaryCreditResourceOperator(),
            decisionTableOperator(),
            transformOperator(),
          ],
        });
      }
      if (url === '/api/visual/graphs/simulate') {
        const body = JSON.parse(String(init?.body));
        const applicantId = body.context?.applicantId;
        if (applicantId === 'applicant-2002') {
          expect(body.fixtures.n2.output.payload.score).toBe(650);
          return jsonResponse(loanSimulationResponse({
            applicantId: 'applicant-2002',
            segment: 'watchlist',
            primaryScore: 650,
            secondaryScore: 688,
            decision: 'decline',
            tier: 'risk',
            reason: 'policy threshold not met',
          }));
        }
        return jsonResponse(loanSimulationResponse({
          applicantId: 'applicant-1001',
          segment: 'prime',
          primaryScore: 728,
          secondaryScore: 701,
          decision: 'approve',
          tier: 'prime',
          reason: 'strong primary credit',
        }));
      }
      throw new Error(`Unexpected fetch: ${url}`);
    });
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(async () => {
    if (root) {
      await act(async () => root?.unmount());
      root = null;
    }
    host.remove();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('defines graph-level input and output schemas for every built-in example', () => {
    expect(CANVAS_EXAMPLE_TEMPLATES).toHaveLength(3);
    for (const template of CANVAS_EXAMPLE_TEMPLATES) {
      expect(template.inputSchema.schema).toMatchObject({ type: 'object' });
      expect(template.outputSchema.schema).toMatchObject({ type: 'object' });
      expect(Object.keys(template.inputSchema.schema.properties as Record<string, unknown>)).not.toHaveLength(0);
      expect(Object.keys(template.outputSchema.schema.properties as Record<string, unknown>)).not.toHaveLength(0);
      expect(template.testCases).toHaveLength(2);
    }
  });

  it('uses built-in expression functions in every complex example transform', () => {
    for (const template of CANVAS_EXAMPLE_TEMPLATES) {
      const transform = template.nodes.find((node) => node.operatorRef === 'bloge:transform');
      expect(transform, template.key).toBeDefined();
      const assignments = transform?.config?.assignments as Record<string, string>;
      expect(Object.values(assignments).join('\n'), template.key)
        .toMatch(/\b(coalesce|toNumber|round)\(/);
    }
  });

  it('models resource node fixtures as complete resource outputs with payload fields', () => {
    for (const template of CANVAS_EXAMPLE_TEMPLATES) {
      const resourceNodeIds = new Set(
        template.nodes
          .filter((node) => node.operatorRef.startsWith('resource:'))
          .map((node) => node.id),
      );

      for (const node of template.nodes) {
        if (resourceNodeIds.has(node.id) && node.fixtureOutput !== undefined) {
          expect(node.fixtureOutput, `${template.key}:${node.id}`).toMatchObject({ payload: expect.anything() });
        }
      }

      for (const testCase of template.testCases ?? []) {
        for (const [nodeId, fixture] of Object.entries(testCase.fixtureOverrides ?? {})) {
          if (resourceNodeIds.has(nodeId)) {
            expect(fixture.output, `${template.key}:${testCase.id}:${nodeId}`)
              .toMatchObject({ payload: expect.anything() });
          }
        }
      }
    }
  });

  it('loads a complex built-in example into the editable canvas draft', async () => {
    await act(async () => {
      root = createRoot(host);
      root.render(<AuthorCanvas />);
    });

    await waitFor(() =>
      expect(query('[data-testid="canvas-example-load:loan-policy-fallback"]').textContent).toContain('Load'),
    );
    expect(query<HTMLButtonElement>('[data-testid="canvas-example-load:order-fulfillment-lane"]').disabled)
      .toBe(true);

    await click(query<HTMLButtonElement>('[data-testid="canvas-example-load:loan-policy-fallback"]'));

    await waitFor(() =>
      expect(document.body.textContent).toContain('Loaded Loan policy fallback: 5 nodes / 12 edges.'),
    );
    expect(document.body.textContent).toContain('5 nodes');
    expect(document.body.textContent).toContain('12 edges');
    expect(document.body.textContent).toContain('Input 1 fields');
    expect(document.body.textContent).toContain('Output 7 fields');
    expect(query('[data-testid="author-graph-contract"]').textContent).toContain('Graph Contract');
    expect(query('[data-testid="author-graph-contract"]').textContent).toContain('Loan policy fallback');
    expect(query('[data-testid="author-graph-contract"]').textContent).toContain('applicantId');
    expect(query('[data-testid="author-graph-contract"]').textContent).toContain('decision');
    expect(document.body.textContent).toContain('3 fixtures');
    expect(document.body.textContent).toContain('Output n5');
    expect(query('[data-testid="canvas-node:n1"][data-operator-ref="resource:loan-applicant-service.getProfile"]').textContent)
      .toContain('Fetch applicant');
    expect(query('[data-testid="canvas-node:n5"][data-operator-ref="bloge:transform"]').textContent)
      .toContain('Decision response');

    const exported = authorDraftExport(query<HTMLAnchorElement>('[data-testid="author-draft-export"]'));
    expect(exported.nodes.map((node: { id: string; operatorRef: string }) => [node.id, node.operatorRef]))
      .toEqual([
        ['n1', 'resource:loan-applicant-service.getProfile'],
        ['n2', 'resource:credit-provider.primary'],
        ['n3', 'resource:credit-provider.secondary'],
        ['n4', 'bloge:decisionTable'],
        ['n5', 'bloge:transform'],
      ]);
    expect(exported.nodes[0].inputs).toMatchObject({
      applicantId: { kind: 'constant', value: 'applicant-1001', targetPort: 'params' },
    });
    expect(exported.inputSchema.schema).toMatchObject({
      properties: {
        applicantId: { type: 'string' },
      },
    });
    expect(exported.edges).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          source: { nodeId: 'n2', port: 'payload', path: 'score' },
          target: { nodeId: 'n4', port: 'inputs', path: 'score' },
        }),
        expect.objectContaining({
          source: { nodeId: 'n4', port: 'output', path: 'decision' },
          target: { nodeId: 'n5', port: 'inputs', path: 'decision' },
        }),
      ]),
    );
    expect(exported.nodes[3].config).toMatchObject({
      conditionColumns: ['score', 'income', 'employmentYears'],
      outputColumns: ['decision', 'tier', 'reason'],
    });
    expect(exported.nodes[4].config.assignments).toMatchObject({
      applicantId: 'n1.output.payload.applicantId',
      primaryScore: 'toNumber(coalesce(n2.output.payload.score, 0))',
      decision: 'n4.output.decision',
      reason: 'coalesce(n4.output.reason, "policy fallback")',
    });
    expect(exported.nodeFixtures).toMatchObject({
      n1: { output: { payload: { applicantId: 'applicant-1001', score: 715 } } },
      n2: { output: { payload: { score: 728, provider: 'primary' } } },
      n3: { output: { payload: { score: 701, provider: 'secondary' } } },
    });
    expect(query('[data-testid="test-suite-summary"]').textContent).toContain('Not run');
    expect(document.querySelector('[data-testid="simulation-test-table"]')).toBeNull();
    await click(query<HTMLButtonElement>('[data-testid="test-suite-open"]'));
    await waitFor(() =>
      expect(query('[data-testid="test-suite-dialog"]').textContent).toContain('Test Suite'),
    );
    expect(query<HTMLInputElement>('[data-testid="test-table-name:0"]').value).toBe('Prime approval path');
    expect(query<HTMLInputElement>('[data-testid="test-table-name:1"]').value).toBe('Policy decline path');
    expect(query<HTMLTextAreaElement>('[data-testid="test-table-context:0"]').value)
      .toContain('"applicantId": "applicant-1001"');
    expect(query<HTMLTextAreaElement>('[data-testid="test-table-fixtures:1"]').value)
      .toContain('"score": 650');
  });

  it('runs built-in example test table cases through simulate with row fixture overrides', async () => {
    await act(async () => {
      root = createRoot(host);
      root.render(<AuthorCanvas />);
    });

    await waitFor(() =>
      expect(query('[data-testid="canvas-example-load:loan-policy-fallback"]').textContent).toContain('Load'),
    );
    await click(query<HTMLButtonElement>('[data-testid="canvas-example-load:loan-policy-fallback"]'));
    await click(query<HTMLButtonElement>('[data-testid="test-suite-open"]'));
    await waitFor(() =>
      expect(query('[data-testid="test-table-summary"]').textContent).toContain('0/2 passed'),
    );

    await click(query<HTMLButtonElement>('[data-testid="test-table-run"]'));

    await waitFor(() =>
      expect(query('[data-testid="test-table-summary"]').textContent).toContain('2/2 passed'),
    );
    expect(query('[data-testid="test-table-status:0"]').textContent).toContain('passed');
    expect(query('[data-testid="test-table-status:1"]').textContent).toContain('passed');

    const simulateCalls = fetchMock.mock.calls
      .filter(([input]) => String(input) === '/api/visual/graphs/simulate');
    expect(simulateCalls).toHaveLength(2);
    const secondRequest = JSON.parse(String(simulateCalls[1][1]?.body));
    expect(secondRequest.context).toEqual({ applicantId: 'applicant-2002' });
    expect(secondRequest.fixtures.n1.output.payload).toMatchObject({
      applicantId: 'applicant-2002',
      segment: 'watchlist',
    });
  });
});

describe('AuthorCanvas connection guide', () => {
  let root: Root | null = null;
  let host: HTMLDivElement;
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
    fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/api/visual/operators') {
        return jsonResponse({ operators: [scoreOperator(), decisionOperator()] });
      }
      if (url === '/api/visual/connections/candidates') {
        const body = JSON.parse(String(init?.body));
        expect(body.source).toEqual({ nodeId: 'n1', port: 'decision' });
        expect(body.draft.nodes.map((node: { id: string }) => node.id)).toEqual(['n1', 'n2']);
        return jsonResponse({
          source: { nodeId: 'n1', port: 'decision' },
          acceptedCount: 1,
          rejectedCount: 0,
          totalCandidateCount: 1,
          candidates: [
            {
              targetNodeId: 'n2',
              targetNodeLabel: 'Decision Consumer',
              targetOperatorRef: 'risk:decision',
              targetSurface: 'input',
              target: { nodeId: 'n2', port: 'profile', path: 'score' },
              accepted: true,
              targetStatus: 'ready',
              summary: { message: 'Schemas match.' },
            },
          ],
        });
      }
      if (url === '/api/visual/connections/check') {
        const body = JSON.parse(String(init?.body));
        expect(body.source).toEqual({ nodeId: 'n1', port: 'decision' });
        expect(body.target).toEqual({ nodeId: 'n2', port: 'profile', path: 'score' });
        return jsonResponse({
          accepted: true,
          edge: {
            id: 'n1:decision->n2:profile',
            kind: 'data',
            source: { nodeId: 'n1', port: 'decision' },
            target: { nodeId: 'n2', port: 'profile', path: 'score' },
          },
          diagnostics: [],
          summary: { message: 'Connection accepted.' },
        });
      }
      throw new Error(`Unexpected fetch: ${url}`);
    });
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(async () => {
    if (root) {
      await act(async () => root?.unmount());
      root = null;
    }
    host.remove();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('discovers compatible targets for the selected source node and connects one directly', async () => {
    await act(async () => {
      root = createRoot(host);
      root.render(<AuthorCanvas />);
    });

    await waitFor(() =>
      expect(query('[data-testid="operator-button:risk:score"]').textContent).toContain('Risk Score'),
    );
    await click(query<HTMLButtonElement>('[data-testid="operator-button:risk:score"]'));
    await click(query<HTMLButtonElement>('[data-testid="operator-button:risk:decision"]'));
    await click(query<HTMLElement>('[data-testid="node-wrapper:n1"]'));

    await waitFor(() =>
      expect(query('[data-testid="connection-guide"]').textContent).toContain('Connect Next'),
    );
    await click(query<HTMLButtonElement>('[data-testid="connection-guide-refresh"]'));

    await waitFor(() =>
      expect(query('[data-testid="connection-guide-target:n2:profile"]').textContent)
        .toContain('Decision Consumer'),
    );
    expect(query('[data-testid="connection-guide-target:n2:profile"]').textContent).toContain('ready');

    const connectButton = query('[data-testid="connection-guide-target:n2:profile"]')
      .querySelector<HTMLButtonElement>('button.secondary');
    expect(connectButton).not.toBeNull();
    await click(connectButton as HTMLButtonElement);

    await waitFor(() => expect(document.body.textContent).toContain('1 edges'));
    expect(document.body.textContent).toContain('Connection accepted.');

    const layoutButton = Array.from(document.querySelectorAll<HTMLButtonElement>('button'))
      .find((button) => button.textContent === 'Auto Layout');
    expect(layoutButton).toBeDefined();
    expect(layoutButton?.disabled).toBe(false);
    await click(layoutButton as HTMLButtonElement);

    await waitFor(() =>
      expect(query('[data-testid="node-wrapper:n1"]').getAttribute('data-position')).toBe('96,72'),
    );
    expect(query('[data-testid="node-wrapper:n2"]').getAttribute('data-position')).toBe('504,72');
    await waitFor(() =>
      expect(reactFlowMocks.fitView).toHaveBeenCalledWith({ padding: 0.18, duration: 240 }),
    );
  });

  it('opens operator details for a regular canvas node on double click', async () => {
    await act(async () => {
      root = createRoot(host);
      root.render(<AuthorCanvas />);
    });

    await waitFor(() =>
      expect(query('[data-testid="operator-button:risk:score"]').textContent).toContain('Risk Score'),
    );
    await click(query<HTMLButtonElement>('[data-testid="operator-button:risk:score"]'));
    await doubleClick(query<HTMLElement>('[data-testid="node-wrapper:n1"]'));

    await waitFor(() =>
      expect(query('[data-testid="operator-detail-dialog"]').textContent).toContain('Risk Score'),
    );
    expect(query('[data-testid="operator-detail-dialog"]').textContent).toContain('risk:score');
    expect(query('[data-testid="operator-detail-dialog"]').textContent).toContain('Input schema');
    expect(query('[data-testid="operator-detail-dialog"]').textContent).toContain('Output schema');
    expect(query('[data-testid="operator-detail-schema:output:0"]').textContent).toContain('"score"');
  });

  it('opens compatible targets from the in-canvas coach action', async () => {
    await act(async () => {
      root = createRoot(host);
      root.render(<AuthorCanvas />);
    });

    await waitFor(() =>
      expect(query('[data-testid="operator-button:risk:score"]').textContent).toContain('Risk Score'),
    );
    await click(query<HTMLButtonElement>('[data-testid="operator-button:risk:score"]'));
    await click(query<HTMLButtonElement>('[data-testid="operator-button:risk:decision"]'));

    await waitFor(() =>
      expect(query('[data-testid="canvas-coach"]').textContent).toContain('Find compatible targets for n1.'),
    );
    const coachAction = query('[data-testid="canvas-coach"]').querySelector<HTMLButtonElement>('button');
    expect(coachAction).not.toBeNull();
    expect(coachAction?.textContent).toContain('Find targets');
    await click(coachAction as HTMLButtonElement);

    await waitFor(() =>
      expect(query('[data-testid="connection-guide-target:n2:profile"]').textContent)
        .toContain('Decision Consumer'),
    );
    expect(query('[data-testid="connection-guide-target:n2:profile"]').textContent).toContain('ready');
  });

  it('renders field-level choices when a target input has multiple compatible paths', async () => {
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/api/visual/operators') {
        return jsonResponse({ operators: [scoreOperator(), decisionOperator()] });
      }
      if (url === '/api/visual/connections/candidates') {
        return jsonResponse({
          source: { nodeId: 'n1', port: 'decision' },
          acceptedCount: 2,
          rejectedCount: 1,
          totalCandidateCount: 3,
          candidates: [
            {
              targetNodeId: 'n2',
              targetNodeLabel: 'Decision Consumer',
              targetOperatorRef: 'risk:decision',
              targetSurface: 'input',
              target: { nodeId: 'n2', port: 'profile' },
              accepted: false,
              targetStatus: 'blocked',
              summary: { message: 'Connection rejected by server.' },
              diagnostics: [{ level: 'error', code: 'visual.connection.schema', message: 'object -> number' }],
            },
            {
              targetNodeId: 'n2',
              targetNodeLabel: 'Decision Consumer',
              targetOperatorRef: 'risk:decision',
              targetSurface: 'input',
              target: { nodeId: 'n2', port: 'profile', path: 'score' },
              accepted: true,
              targetStatus: 'ready',
              summary: { message: 'Schemas match.' },
            },
            {
              targetNodeId: 'n2',
              targetNodeLabel: 'Decision Consumer',
              targetOperatorRef: 'risk:decision',
              targetSurface: 'input',
              target: { nodeId: 'n2', port: 'profile', path: 'amount' },
              accepted: true,
              targetStatus: 'ready',
              summary: { message: 'Schemas match.' },
            },
          ],
        });
      }
      if (url === '/api/visual/connections/check') {
        const body = JSON.parse(String(init?.body));
        expect(body.target).toEqual({ nodeId: 'n2', port: 'profile', path: 'amount' });
        return jsonResponse({
          accepted: true,
          edge: {
            id: 'n1:decision->n2:profile.amount',
            kind: 'data',
            source: { nodeId: 'n1', port: 'decision' },
            target: { nodeId: 'n2', port: 'profile', path: 'amount' },
          },
          diagnostics: [],
          summary: { message: 'Connection accepted.' },
        });
      }
      throw new Error(`Unexpected fetch: ${url}`);
    });

    await act(async () => {
      root = createRoot(host);
      root.render(<AuthorCanvas />);
    });

    await waitFor(() =>
      expect(query('[data-testid="operator-button:risk:score"]').textContent).toContain('Risk Score'),
    );
    await click(query<HTMLButtonElement>('[data-testid="operator-button:risk:score"]'));
    await click(query<HTMLButtonElement>('[data-testid="operator-button:risk:decision"]'));
    await click(query<HTMLElement>('[data-testid="node-wrapper:n1"]'));
    await click(query<HTMLButtonElement>('[data-testid="connection-guide-refresh"]'));

    await waitFor(() =>
      expect(query('[data-testid="connection-guide-target:n2:profile"]').textContent)
        .toContain('2 compatible fields found.'),
    );
    expect(query('[data-testid="connection-guide-target:n2:profile"]').textContent)
      .toContain('Choose the field path that should feed this input.');
    expect(query('[data-testid="connection-guide-field:n2:profile:score"]').textContent).toContain('profile.score');
    expect(query('[data-testid="connection-guide-field:n2:profile:amount"]').textContent).toContain('profile.amount');

    await click(query<HTMLButtonElement>('[data-testid="connection-guide-field:n2:profile:amount"]'));

    await waitFor(() => expect(document.body.textContent).toContain('1 edges'));
    const exportLink = query<HTMLAnchorElement>('[data-testid="author-draft-export"]');
    expect(authorDraftExport(exportLink).edges).toMatchObject([
      { target: { nodeId: 'n2', port: 'profile', path: 'amount' } },
    ]);
  });

  it('focuses and filters the palette from the Cmd-K shortcut', async () => {
    await act(async () => {
      root = createRoot(host);
      root.render(<AuthorCanvas />);
    });

    await waitFor(() =>
      expect(query('[data-testid="operator-button:risk:score"]').textContent).toContain('Risk Score'),
    );
    const search = query<HTMLInputElement>('#operator-palette-search');

    await act(async () => {
      window.dispatchEvent(new KeyboardEvent('keydown', {
        key: 'k',
        metaKey: true,
        bubbles: true,
        cancelable: true,
      }));
    });

    expect(document.activeElement).toBe(search);
    await setControlValue(search, 'consumer');

    expect(query('[data-testid="operator-button:risk:decision"]').textContent).toContain('Decision Consumer');
    expect(document.querySelector('[data-testid="operator-button:risk:score"]')).toBeNull();
  });

  it('drops palette operators onto the canvas at the pointer location', async () => {
    await act(async () => {
      root = createRoot(host);
      root.render(<AuthorCanvas />);
    });

    await waitFor(() =>
      expect(query('[data-testid="operator-button:risk:score"]').textContent).toContain('Risk Score'),
    );
    const flow = query<HTMLElement>('[data-testid="author-flow"]');
    Object.defineProperty(flow, 'getBoundingClientRect', {
      configurable: true,
      value: () => ({
        left: 40,
        top: 80,
        right: 840,
        bottom: 680,
        width: 800,
        height: 600,
        x: 40,
        y: 80,
        toJSON: () => ({}),
      }),
    });

    const transfer = fakeDataTransfer();
    await drag(query<HTMLButtonElement>('[data-testid="operator-button:risk:score"]'), 'dragstart', transfer);
    await drag(flow, 'dragover', transfer, { clientX: 360, clientY: 260 });
    await drag(flow, 'drop', transfer, { clientX: 360, clientY: 260 });

    await waitFor(() =>
      expect(query('[data-testid="canvas-node:n1"][data-operator-ref="risk:score"]').textContent)
        .toContain('Risk Score'),
    );
    expect(query('[data-testid="node-wrapper:n1"]').getAttribute('data-position')).toBe('200,126');
    expect(document.body.textContent).toContain('Output n1');
  });

  it('stacks clicked palette operators on narrow canvases', async () => {
    await act(async () => {
      root = createRoot(host);
      root.render(<AuthorCanvas />);
    });

    await waitFor(() =>
      expect(query('[data-testid="operator-button:risk:score"]').textContent).toContain('Risk Score'),
    );
    Object.defineProperty(query<HTMLElement>('[data-testid="author-flow"]'), 'clientWidth', {
      configurable: true,
      value: 390,
    });

    await click(query<HTMLButtonElement>('[data-testid="operator-button:risk:score"]'));
    await click(query<HTMLButtonElement>('[data-testid="operator-button:risk:decision"]'));

    await waitFor(() =>
      expect(query('[data-testid="node-wrapper:n1"]').getAttribute('data-position')).toBe('72,56'),
    );
    expect(query('[data-testid="node-wrapper:n2"]').getAttribute('data-position')).toBe('72,246');
  });

  it('renders foreach and decision-table nodes with family-specific contract hints', async () => {
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === '/api/visual/operators') {
        return jsonResponse({ operators: [foreachOperator(), decisionTableOperator()] });
      }
      throw new Error(`Unexpected fetch: ${url}`);
    });

    await act(async () => {
      root = createRoot(host);
      root.render(<AuthorCanvas />);
    });

    await waitFor(() =>
      expect(query('[data-testid="operator-button:__foreach__:enrichOrders"]').textContent)
        .toContain('Foreach'),
    );
    expect(query('[data-testid="operator-button:bloge:decisionTable"]').textContent)
      .toContain('conditions -> matched decision');

    await click(query<HTMLButtonElement>('[data-testid="operator-button:__foreach__:enrichOrders"]'));
    await click(query<HTMLButtonElement>('[data-testid="operator-button:bloge:decisionTable"]'));

    await waitFor(() =>
      expect(query('[data-testid="canvas-node:n1"][data-operator-ref="__foreach__:enrichOrders"]').textContent)
        .toContain('collection'),
    );
    expect(query('[data-testid="node-wrapper:n1"]').getAttribute('data-position')).toBe('72,56');
    expect(query('[data-testid="node-wrapper:n2"]').getAttribute('data-position')).toBe('352,56');
    expect(query('[data-testid="canvas-node:n1"][data-operator-ref="__foreach__:enrichOrders"]').textContent)
      .toContain('result list');
    expect(query('[data-testid="canvas-node:n2"][data-operator-ref="bloge:decisionTable"]').textContent)
      .toContain('conditions');
    expect(query('[data-testid="canvas-node:n2"][data-operator-ref="bloge:decisionTable"]').textContent)
      .toContain('decision row');

    await click(query<HTMLElement>('[data-testid="node-wrapper:n1"]'));
    expect(query('[data-testid="operator-focus:foreach"]').textContent).toContain('Loop contract');
    expect(query('[data-testid="operator-focus:foreach"]').textContent).toContain('Collection');
    expect(query('[data-testid="operator-focus:foreach"]').textContent).toContain('Item context');
    expect(query('[data-testid="operator-focus:foreach"]').textContent).toContain('Result list');

    await doubleClick(query<HTMLElement>('[data-testid="node-wrapper:n1"]'));
    await waitFor(() =>
      expect(query('[data-testid="operator-detail-dialog"]').textContent).toContain('foreach enrich orders'),
    );
    expect(query('[data-testid="operator-detail-dialog"]').textContent).toContain('Input schema');
    expect(query('[data-testid="operator-detail-dialog"]').textContent).toContain('Output schema');
    expect(query('[data-testid="foreach-loop-guide"]').textContent).toContain('Bind collection');
    expect(query('[data-testid="foreach-loop-guide"]').textContent).toContain('Run per item');
    expect(query('[data-testid="foreach-loop-guide"]').textContent).toContain('Collect result list');
    await click(query<HTMLButtonElement>('[aria-label="Close operator details"]'));

    await click(query<HTMLElement>('[data-testid="node-wrapper:n2"]'));
    expect(query('[data-testid="operator-focus:decision-table"]').textContent).toContain('Rule contract');
    expect(query('[data-testid="operator-focus:decision-table"]').textContent).toContain('Condition inputs');
    expect(query('[data-testid="operator-focus:decision-table"]').textContent).toContain('Decision output');
    expect(query('[data-testid="operator-focus:decision-table"]').textContent).toContain('Rule matrix');

    await doubleClick(query<HTMLElement>('[data-testid="node-wrapper:n2"]'));
    await waitFor(() =>
      expect(query('[data-testid="decision-table-editor"]').textContent).toContain('Decision Table'),
    );
    expect(query('[data-testid="operator-detail-dialog"]').textContent).toContain('Input schema');
    expect(query('[data-testid="decision-table-editor"]').textContent).toContain('Condition');
    expect(query('[data-testid="decision-table-editor"]').textContent).toContain('Output');

    await click(query<HTMLButtonElement>('[data-testid="decision-add-condition-column"]'));
    await setControlValue(query<HTMLInputElement>('[data-testid="decision-condition-column-name:1"]'), 'score');
    await click(query<HTMLButtonElement>('[data-testid="decision-add-output-column"]'));
    await setControlValue(query<HTMLInputElement>('[data-testid="decision-output-column-name:2"]'), 'tier');

    await setControlValue(
      query<HTMLInputElement>('[data-testid="decision-rule-condition:0:score"]'),
      'score >= 700',
    );
    await setControlValue(query<HTMLInputElement>('[data-testid="decision-rule-output:0:decision"]'), 'approve');
    await setControlValue(query<HTMLInputElement>('[data-testid="decision-rule-output:0:ruleId"]'), 'prime');
    await setControlValue(query<HTMLInputElement>('[data-testid="decision-rule-output:0:tier"]'), 'platinum');

    await click(query<HTMLButtonElement>('[aria-label="Close decision table editor"]'));
    await waitFor(() =>
      expect(document.querySelector('[data-testid="decision-table-editor"]')).toBeNull(),
    );
    expect(query('[data-testid="canvas-node:n2"][data-operator-ref="bloge:decisionTable"]').textContent)
      .toContain('2/2 inputs');
    expect(query('[data-testid="canvas-node:n2"][data-operator-ref="bloge:decisionTable"]').textContent)
      .toContain('3 outputs');

    const exportLink = query<HTMLAnchorElement>('[data-testid="author-draft-export"]');
    expect(authorDraftExport(exportLink).nodes[1].config).toMatchObject({
      hitPolicy: 'unique',
      outputType: '{ decision: String, ruleId: String, tier: String }',
      conditionColumns: ['value', 'score'],
      outputColumns: ['decision', 'ruleId', 'tier'],
      rules: [
        {
          conditions: {
            value: 'value != null',
            score: 'score >= 700',
          },
          output: {
            decision: 'approve',
            ruleId: 'prime',
            tier: 'platinum',
          },
        },
        {
          otherwise: true,
          output: {
            decision: 'fallback',
            ruleId: 'otherwise',
            tier: '',
          },
        },
      ],
    });
  });

  it('surfaces resource and streaming readiness directly in palette, nodes, and inspector', async () => {
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === '/api/visual/operators') {
        return jsonResponse({ operators: [httpResourceOperator(), streamingOperator()] });
      }
      throw new Error(`Unexpected fetch: ${url}`);
    });

    await act(async () => {
      root = createRoot(host);
      root.render(<AuthorCanvas />);
    });

    await waitFor(() =>
      expect(query('[data-testid="operator-button:httpResource"]').textContent).toContain('Resource'),
    );
    expect(query('[data-testid="operator-button:httpResource"]').textContent).toContain('review');
    expect(query('[data-testid="operator-button:MockCitationStreamingOperator"]').textContent).toContain('Streaming');
    expect(query('[data-testid="operator-button:MockCitationStreamingOperator"]').textContent).toContain('blocked');

    await click(query<HTMLButtonElement>('[data-testid="operator-button:httpResource"]'));
    await click(query<HTMLButtonElement>('[data-testid="operator-button:MockCitationStreamingOperator"]'));

    expect(query('[data-testid="canvas-node:n1"][data-operator-ref="httpResource"]').textContent)
      .toContain('params');
    expect(query('[data-testid="canvas-node:n1"][data-operator-ref="httpResource"]').textContent)
      .toContain('review');
    expect(query('[data-testid="canvas-node:n2"][data-operator-ref="MockCitationStreamingOperator"]').textContent)
      .toContain('blocked');

    await click(query<HTMLElement>('[data-testid="node-wrapper:n2"]'));
    expect(query('[data-testid="operator-focus:streaming"]').textContent).toContain('Stream contract');
    expect(query('[data-testid="operator-focus:streaming"]').textContent).toContain('Readiness');
    expect(query('[data-testid="operator-focus:streaming"]').textContent)
      .toContain('Runtime blocked in this visual runtime');

    await doubleClick(query<HTMLElement>('[data-testid="node-wrapper:n1"]'));
    const dialog = query('[data-testid="operator-detail-dialog"]');
    const dialogQuery = <TElement extends Element = Element>(selector: string): TElement => {
      const element = dialog.querySelector<TElement>(selector);
      expect(element, `Expected operator detail selector ${selector}`).not.toBeNull();
      return element as TElement;
    };
    expect(dialog.textContent).toContain('Key properties');
    expect(dialog.textContent).toContain('Resource contract');
    expect(dialogQuery('[data-testid="operator-detail-resource-config"]').textContent).toContain('Resource ID');

    await setControlValue(dialogQuery<HTMLInputElement>('[data-testid="operator-detail-label"]'), 'Customer HTTP call');
    await setControlValue(dialogQuery<HTMLSelectElement>('[data-testid="operator-detail-http-method"]'), 'POST');
    await setControlValue(dialogQuery<HTMLInputElement>('[data-testid="operator-detail-resource-url"]'), '/customers/{customerId}');
    await click(dialogQuery<HTMLButtonElement>('[data-testid="node-input-add"]'));
    await setControlValue(dialogQuery<HTMLInputElement>('[data-testid="node-input-context-path:0"]'), 'request.customerId');
    await setControlValue(dialogQuery<HTMLTextAreaElement>('[data-testid="operator-detail-output-fixture"]'), '{"ok":true}');
    expect(dialogQuery('[data-testid="operator-test-suite"]').textContent).toContain('Operator Test Suite');
    expect(dialogQuery('[data-testid="operator-test-status:0"]').textContent).toContain('valid');
    await click(dialogQuery<HTMLButtonElement>('[data-testid="operator-test-add"]'));
    await waitFor(() =>
      expect(dialogQuery('[data-testid="operator-test-row:1"]').textContent).toContain('Apply Fixture'),
    );
    await setControlValue(
      dialogQuery<HTMLTextAreaElement>('[data-testid="operator-test-input:1"]'),
      '{"customerId":"c-42"}',
    );
    await setControlValue(
      dialogQuery<HTMLTextAreaElement>('[data-testid="operator-test-output:1"]'),
      '{"ok":false,"source":"operator-case"}',
    );
    await click(dialogQuery<HTMLButtonElement>('[data-testid="operator-test-apply:1"]'));

    const exported = authorDraftExport(query<HTMLAnchorElement>('[data-testid="author-draft-export"]'));
    expect(exported.nodes[0]).toMatchObject({
      label: 'Customer HTTP call',
      inputs: {
        input: {
          kind: 'contextPath',
          path: 'request.customerId',
          targetPort: 'input',
        },
      },
      config: {
        method: 'POST',
        url: '/customers/{customerId}',
      },
    });
    expect(exported.nodeFixtures).toMatchObject({
      n1: {
        expectedInput: { customerId: 'c-42' },
        output: { ok: false, source: 'operator-case' },
      },
    });
  });

  it('uses incoming edge bindings as decision-table condition columns', async () => {
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/api/visual/operators') {
        return jsonResponse({ operators: [scoreOperator(), decisionTableOperator()] });
      }
      if (url === '/api/visual/connections/candidates') {
        const body = JSON.parse(String(init?.body));
        expect(body.source).toEqual({ nodeId: 'n1', port: 'decision' });
        return jsonResponse({
          source: { nodeId: 'n1', port: 'decision' },
          acceptedCount: 1,
          rejectedCount: 0,
          totalCandidateCount: 1,
          candidates: [
            {
              targetNodeId: 'n2',
              targetNodeLabel: 'Decision Table',
              targetOperatorRef: 'bloge:decisionTable',
              targetSurface: 'input',
              target: { nodeId: 'n2', port: 'inputs', path: 'score' },
              accepted: true,
              targetStatus: 'ready',
              summary: { message: 'Schemas match.' },
            },
          ],
        });
      }
      if (url === '/api/visual/connections/check') {
        const body = JSON.parse(String(init?.body));
        expect(body.target).toEqual({ nodeId: 'n2', port: 'inputs', path: 'score' });
        return jsonResponse({
          accepted: true,
          bindingKey: 'score',
          edge: {
            id: 'n1:decision.score->n2:inputs.score',
            kind: 'data',
            source: { nodeId: 'n1', port: 'decision', path: 'score' },
            target: { nodeId: 'n2', port: 'inputs', path: 'score' },
          },
          diagnostics: [],
          summary: { message: 'Connection accepted.' },
        });
      }
      throw new Error(`Unexpected fetch: ${url}`);
    });

    await act(async () => {
      root = createRoot(host);
      root.render(<AuthorCanvas />);
    });

    await waitFor(() =>
      expect(query('[data-testid="operator-button:risk:score"]').textContent).toContain('Risk Score'),
    );
    await click(query<HTMLButtonElement>('[data-testid="operator-button:risk:score"]'));
    await click(query<HTMLButtonElement>('[data-testid="operator-button:bloge:decisionTable"]'));
    await click(query<HTMLElement>('[data-testid="node-wrapper:n1"]'));
    await click(query<HTMLButtonElement>('[data-testid="connection-guide-refresh"]'));

    await waitFor(() =>
      expect(query('[data-testid="connection-guide-target:n2:inputs"]').textContent)
        .toContain('Decision Table'),
    );
    const connectButton = query('[data-testid="connection-guide-target:n2:inputs"]')
      .querySelector<HTMLButtonElement>('button.secondary');
    expect(connectButton).not.toBeNull();
    await click(connectButton as HTMLButtonElement);

    await waitFor(() => expect(document.body.textContent).toContain('1 edges'));
    await doubleClick(query<HTMLElement>('[data-testid="node-wrapper:n2"]'));
    await waitFor(() =>
      expect(query('[data-testid="decision-table-editor"]').textContent).toContain('Decision Table'),
    );

    expect(query('[data-testid="decision-incoming-inputs"]').textContent).toContain('score');
    expect(query<HTMLInputElement>('[data-testid="decision-condition-column-name:0"]').value).toBe('score');
    expect(query<HTMLInputElement>('[data-testid="decision-condition-column-name:0"]').disabled).toBe(true);

    await setControlValue(
      query<HTMLInputElement>('[data-testid="decision-rule-condition:0:score"]'),
      'score >= 700',
    );
    await setControlValue(query<HTMLInputElement>('[data-testid="decision-rule-output:0:decision"]'), 'approve');

    const exportLink = query<HTMLAnchorElement>('[data-testid="author-draft-export"]');
    const exported = authorDraftExport(exportLink);
    expect(exported.nodes[1].inputs).toMatchObject({
      score: {
        kind: 'nodePath',
        nodeId: 'n1',
        sourcePort: 'decision',
        path: 'score',
        targetPort: 'inputs',
        targetPath: 'score',
      },
    });
    expect(exported.nodes[1].config.conditionColumns).toEqual(['score']);
    expect(exported.nodes[1].config.rules[0]).toMatchObject({
      conditions: {
        score: 'score >= 700',
      },
      output: {
        decision: 'approve',
      },
    });
  });

  it('opens a transform mapping editor on double click and exports assignments', async () => {
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === '/api/visual/operators') {
        return jsonResponse({ operators: [transformOperator()] });
      }
      throw new Error(`Unexpected fetch: ${url}`);
    });

    await act(async () => {
      root = createRoot(host);
      root.render(<AuthorCanvas />);
    });

    await waitFor(() =>
      expect(query('[data-testid="operator-button:bloge:transform"]').textContent)
        .toContain('Transform'),
    );

    await click(query<HTMLButtonElement>('[data-testid="operator-button:bloge:transform"]'));
    await waitFor(() =>
      expect(query('[data-testid="canvas-node:n1"][data-operator-ref="bloge:transform"]').textContent)
        .toContain('source fields'),
    );

    await doubleClick(query<HTMLElement>('[data-testid="node-wrapper:n1"]'));
    await waitFor(() =>
      expect(query('[data-testid="transform-assignment-editor"]').textContent).toContain('Transform mapping'),
    );
    expect(query('[data-testid="transform-assignment-editor"]').textContent).toContain('Output Field');
    expect(query('[data-testid="transform-assignment-editor"]').textContent).toContain('Expression');

    await setControlValue(query<HTMLInputElement>('[data-testid="transform-assignment-field:0"]'), 'tier');
    await setControlValue(
      query<HTMLInputElement>('[data-testid="transform-assignment-expression:0"]'),
      'inputs.score >= 700 ? "prime" : "standard"',
    );
    await click(query<HTMLButtonElement>('[data-testid="transform-add-assignment"]'));
    await setControlValue(query<HTMLInputElement>('[data-testid="transform-assignment-field:1"]'), 'reason');
    await setControlValue(
      query<HTMLInputElement>('[data-testid="transform-assignment-expression:1"]'),
      '"score policy"',
    );

    const exportLink = query<HTMLAnchorElement>('[data-testid="author-draft-export"]');
    expect(authorDraftExport(exportLink).nodes[0].config).toEqual({
      assignments: {
        tier: 'inputs.score >= 700 ? "prime" : "standard"',
        reason: '"score policy"',
      },
    });
  });

  it('shows built-in function completion and signature hints in transform expressions', async () => {
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === '/api/visual/operators') {
        return jsonResponse({
          operators: [transformOperator()],
          builtInFunctions: [coalesceFunction()],
        });
      }
      throw new Error(`Unexpected fetch: ${url}`);
    });

    await act(async () => {
      root = createRoot(host);
      root.render(<AuthorCanvas />);
    });

    await waitFor(() =>
      expect(query('[data-testid="operator-button:bloge:transform"]').textContent)
        .toContain('Transform'),
    );

    await click(query<HTMLButtonElement>('[data-testid="operator-button:bloge:transform"]'));
    await doubleClick(query<HTMLElement>('[data-testid="node-wrapper:n1"]'));
    await waitFor(() =>
      expect(query('[data-testid="transform-assignment-editor"]').textContent).toContain('Transform mapping'),
    );

    expect(query('[data-testid="transform-function-signature:0:coalesce"]').textContent)
      .toContain('coalesce(value, fallback)');
    await click(query<HTMLButtonElement>('[data-testid="transform-function-insert:0:coalesce"]'));

    expect(query<HTMLInputElement>('[data-testid="transform-assignment-expression:0"]').value)
      .toBe('coalesce(value, fallback)');
    expect(authorDraftExport(query<HTMLAnchorElement>('[data-testid="author-draft-export"]')).nodes[0].config)
      .toEqual({
        assignments: {
          result: 'coalesce(value, fallback)',
        },
      });
  });
});

describe('AuthorCanvas simulation summary', () => {
  let root: Root | null = null;
  let host: HTMLDivElement;
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
    fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/api/visual/operators') {
        return jsonResponse({ operators: [eligibilityOperator()] });
      }
      if (url === '/api/visual/drafts/validate') {
        const body = JSON.parse(String(init?.body));
        expect(body.schemaVersion).toBe('bloge.visualGraphDraft.v1');
        expect(body.output.nodeId).toBe('n1');
        expect(body.nodes).toHaveLength(1);
        return jsonResponse({
          valid: true,
          diagnostics: [],
          readiness: {
            state: 'design-only',
            level: 'info',
            title: 'Design-only draft',
            summary: 'Draft is structurally valid and ready for simulation.',
            artifactKinds: ['DESIGN'],
          },
          actionReadiness: {
            state: 'design-artifact-ready',
            publishDesignNow: true,
          },
        });
      }
      if (url === '/api/visual/graphs/simulate') {
        const body = JSON.parse(String(init?.body));
        expect(body.outputNode).toBe('n1');
        expect(body.draft.output.nodeId).toBe('n1');
        expect(body.draft.nodes).toHaveLength(1);
        return jsonResponse({
          validated: true,
          compiled: true,
          success: true,
          graphName: 'visualGraph',
          outputNode: 'n1',
          output: { eligible: true },
          results: { n1: { eligible: true } },
          statusMap: {},
          mockedNodeIds: ['n1'],
          realNodeIds: [],
          terminalOutputConforms: true,
          diagnostics: [],
          errors: [],
          generatedDsl: 'graph visualGraph {}',
        });
      }
      throw new Error(`Unexpected fetch: ${url}`);
    });
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(async () => {
    if (root) {
      await act(async () => root?.unmount());
      root = null;
    }
    host.remove();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('shows a readable run summary before and after simulation', async () => {
    await act(async () => {
      root = createRoot(host);
      root.render(<AuthorCanvas />);
    });

    await waitFor(() =>
      expect(query('[data-testid="operator-button:risk:eligibility"]').textContent).toContain('Eligibility'),
    );
    await click(query<HTMLButtonElement>('[data-testid="operator-button:risk:eligibility"]'));

    await waitFor(() =>
      expect(query('[data-testid="simulation-run-summary"]').textContent).toContain('Ready to simulate'),
    );
    expect(query('[data-testid="simulation-run-summary:terminal"]').textContent).toContain('n1');
    expect(query('[data-testid="simulation-run-summary:mock-samples"]').textContent).toContain('1 sample');

    const simulateButton = Array.from(document.querySelectorAll<HTMLButtonElement>('button'))
      .find((button) => button.textContent === 'Simulate' && button.className.includes('primary'));
    expect(simulateButton).toBeDefined();
    await click(simulateButton as HTMLButtonElement);

    await waitFor(() =>
      expect(query('[data-testid="simulation-run-summary"]').textContent).toContain('Simulation succeeded'),
    );
    expect(query('[data-testid="simulation-run-summary:trust"]').textContent).toContain('0 real / 1 mocked');
    expect(query('[data-testid="simulation-run-summary:diagnostics"]').textContent).toContain('0 diagnostics');
  });

  it('binds start-node inputs to runtime context paths', async () => {
    await act(async () => {
      root = createRoot(host);
      root.render(<AuthorCanvas />);
    });

    await waitFor(() =>
      expect(query('[data-testid="operator-button:risk:eligibility"]').textContent).toContain('Eligibility'),
    );
    await click(query<HTMLButtonElement>('[data-testid="operator-button:risk:eligibility"]'));
    await click(query<HTMLElement>('[data-testid="node-wrapper:n1"]'));

    await click(query<HTMLButtonElement>('[data-testid="node-input-add"]'));
    await setControlValue(query<HTMLInputElement>('[data-testid="node-input-key:0"]'), 'score');
    await setControlValue(query<HTMLInputElement>('[data-testid="node-input-context-path:0"]'), 'applicant.score');
    await setControlValue(
      query<HTMLTextAreaElement>('[data-testid="simulation-context-json"]'),
      '{\n  "applicant": { "score": 720 }\n}',
    );

    const exported = authorDraftExport(query<HTMLAnchorElement>('[data-testid="author-draft-export"]'));
    expect(exported.nodes[0].inputs.score).toMatchObject({
      kind: 'contextPath',
      path: 'applicant.score',
      targetPort: 'inputs',
    });

    const simulateButton = Array.from(document.querySelectorAll<HTMLButtonElement>('button'))
      .find((button) => button.textContent === 'Simulate' && button.className.includes('primary'));
    expect(simulateButton).toBeDefined();
    await click(simulateButton as HTMLButtonElement);

    await waitFor(() =>
      expect(query('[data-testid="simulation-run-summary"]').textContent).toContain('Simulation succeeded'),
    );
    const simulateCalls = fetchMock.mock.calls
      .filter(([input]) => String(input) === '/api/visual/graphs/simulate');
    const simulateCall = simulateCalls[simulateCalls.length - 1];
    expect(simulateCall).toBeDefined();
    const request = JSON.parse(String(simulateCall?.[1]?.body));
    expect(request.context).toEqual({ applicant: { score: 720 } });
    expect(request.draft.nodes[0].inputs.score).toMatchObject({
      kind: 'contextPath',
      path: 'applicant.score',
      targetPort: 'inputs',
    });
  });

  it('creates runtime context variables and binds them to selected node inputs', async () => {
    await act(async () => {
      root = createRoot(host);
      root.render(<AuthorCanvas />);
    });

    await waitFor(() =>
      expect(query('[data-testid="operator-button:risk:eligibility"]').textContent).toContain('Eligibility'),
    );
    await click(query<HTMLButtonElement>('[data-testid="operator-button:risk:eligibility"]'));
    await click(query<HTMLElement>('[data-testid="node-wrapper:n1"]'));

    await click(query<HTMLButtonElement>('[data-testid="context-variable-add"]'));
    await setControlValue(query<HTMLInputElement>('[data-testid="context-variable-path:0"]'), 'applicant.score');
    await setControlValue(query<HTMLSelectElement>('[data-testid="context-variable-type:0"]'), 'number');
    await setControlValue(query<HTMLInputElement>('[data-testid="context-variable-value:0"]'), '720');

    expect(query('[data-testid="context-preview-json"]').textContent).toContain('"score": 720');

    await click(query<HTMLButtonElement>('[data-testid="context-variable-bind:0"]'));

    const exported = authorDraftExport(query<HTMLAnchorElement>('[data-testid="author-draft-export"]'));
    expect(exported.nodes[0].inputs).toEqual({
      score: {
        kind: 'contextPath',
        path: 'applicant.score',
        targetPort: 'inputs',
        targetPath: 'score',
      },
    });

    const simulateButton = Array.from(document.querySelectorAll<HTMLButtonElement>('button'))
      .find((button) => button.textContent === 'Simulate' && button.className.includes('primary'));
    expect(simulateButton).toBeDefined();
    await click(simulateButton as HTMLButtonElement);

    await waitFor(() =>
      expect(query('[data-testid="simulation-run-summary"]').textContent).toContain('Simulation succeeded'),
    );
    const simulateCalls = fetchMock.mock.calls
      .filter(([input]) => String(input) === '/api/visual/graphs/simulate');
    const simulateCall = simulateCalls[simulateCalls.length - 1];
    expect(simulateCall).toBeDefined();
    const request = JSON.parse(String(simulateCall?.[1]?.body));
    expect(request.context).toEqual({ applicant: { score: 720 } });
  });

  it('binds runtime context variables by dragging them onto node inputs', async () => {
    await act(async () => {
      root = createRoot(host);
      root.render(<AuthorCanvas />);
    });

    await waitFor(() =>
      expect(query('[data-testid="operator-button:risk:eligibility"]').textContent).toContain('Eligibility'),
    );
    await click(query<HTMLButtonElement>('[data-testid="operator-button:risk:eligibility"]'));
    await click(query<HTMLElement>('[data-testid="node-wrapper:n1"]'));

    await click(query<HTMLButtonElement>('[data-testid="context-variable-add"]'));
    await setControlValue(query<HTMLInputElement>('[data-testid="context-variable-path:0"]'), 'applicant.score');
    const transfer = fakeDataTransfer();
    await drag(query<HTMLButtonElement>('[data-testid="context-variable-chip:0"]'), 'dragstart', transfer);
    await drag(query<HTMLElement>('[data-testid="node-input-editor"]'), 'dragover', transfer);
    await drag(query<HTMLElement>('[data-testid="node-input-editor"]'), 'drop', transfer);

    const exported = authorDraftExport(query<HTMLAnchorElement>('[data-testid="author-draft-export"]'));
    expect(exported.nodes[0].inputs.score).toMatchObject({
      kind: 'contextPath',
      path: 'applicant.score',
      targetPort: 'inputs',
      targetPath: 'score',
    });
  });

  it('validates the current draft through the server readiness gate', async () => {
    await act(async () => {
      root = createRoot(host);
      root.render(<AuthorCanvas />);
    });

    await waitFor(() =>
      expect(query('[data-testid="operator-button:risk:eligibility"]').textContent).toContain('Eligibility'),
    );
    await click(query<HTMLButtonElement>('[data-testid="operator-button:risk:eligibility"]'));
    await click(query<HTMLButtonElement>('[data-testid="author-draft-validate"]'));

    await waitFor(() =>
      expect(query('[data-testid="draft-validation-summary"]').textContent).toContain('Design-only draft'),
    );
    expect(query('[data-testid="draft-validation-summary"]').textContent)
      .toContain('Draft is structurally valid and ready for simulation.');
    expect(query('[data-testid="draft-validation-summary:state"]').textContent).toContain('design-only');
    expect(query('[data-testid="draft-validation-summary:actions"]').textContent)
      .toContain('design-artifact-ready');
    expect(query('[data-testid="draft-validation-summary:diagnostics"]').textContent).toContain('0');
  });

  it('exports the current authoring draft with node fixtures', async () => {
    await act(async () => {
      root = createRoot(host);
      root.render(<AuthorCanvas />);
    });

    await waitFor(() =>
      expect(query('[data-testid="operator-button:risk:eligibility"]').textContent).toContain('Eligibility'),
    );
    await click(query<HTMLButtonElement>('[data-testid="operator-button:risk:eligibility"]'));

    const exportLink = query<HTMLAnchorElement>('[data-testid="author-draft-export"]');
    expect(exportLink.getAttribute('aria-disabled')).toBe('false');
    expect(authorDraftExport(exportLink)).toMatchObject({
      schemaVersion: 'bloge.visualGraphDraft.v1',
      graphName: 'visualGraph',
      nodes: [{ id: 'n1', operatorRef: 'risk:eligibility', label: 'Eligibility' }],
      output: { nodeId: 'n1', path: '' },
    });

    const useSample = Array.from(document.querySelectorAll<HTMLButtonElement>('button'))
      .find((button) => button.textContent === 'Use Sample');
    expect(useSample).toBeDefined();
    await click(useSample as HTMLButtonElement);

    await waitFor(() =>
      expect(authorDraftExport(exportLink).nodeFixtures).toEqual({
        n1: { output: { eligible: false } },
      }),
    );
  });
});

const sampleLibraryYaml = [
  'schemaVersion: bloge.visualOperatorLibrary.v1',
  'libraryId: risk-policy',
  'operators:',
  '  - operatorRef: risk:eligibility',
].join('\n');

function validationResult(): OperatorLibraryValidationResult {
  return {
    valid: true,
    diagnostics: [],
    profile: { libraryId: 'risk-policy', operatorCount: 1 },
    importReadiness: {
      level: 'info',
      operatorCount: 1,
      message: 'Schema-only library is ready for design-time authoring.',
    },
  };
}

function operatorLibrary(): OperatorLibrary {
  return {
    schemaVersion: 'bloge.visualOperatorLibrary.v1',
    libraryId: 'risk-policy',
    displayName: 'Risk policy',
    operators: [eligibilityOperator()],
  };
}

function eligibilityOperator(): OperatorDefinition {
  return {
    operatorRef: 'risk:eligibility',
    display: { name: 'Eligibility', description: 'Decides whether the applicant is eligible.' },
    source: { kind: 'operator-library', libraryId: 'risk-policy' },
    lowering: { mode: 'design' },
    ports: {
      inputs: [
        {
          name: 'inputs',
          required: true,
          schema: schema({
            type: 'object',
            properties: {
              score: { type: 'integer' },
              amount: { type: 'number' },
            },
            required: ['score', 'amount'],
          }),
        },
      ],
      outputs: [
        {
          name: 'output',
          schema: schema({
            type: 'object',
            properties: {
              eligible: { type: 'boolean' },
            },
            required: ['eligible'],
          }),
        },
      ],
    },
  };
}

function scoreOperator(): OperatorDefinition {
  return {
    operatorRef: 'risk:score',
    display: { name: 'Risk Score', description: 'Scores an applicant.' },
    source: { kind: 'operator-library', libraryId: 'risk-policy' },
    lowering: { mode: 'design' },
    ports: {
      inputs: [],
      outputs: [
        {
          name: 'decision',
          schema: schema({
            type: 'object',
            properties: {
              score: { type: 'number' },
            },
            required: ['score'],
          }),
        },
      ],
    },
  };
}

function decisionOperator(): OperatorDefinition {
  return {
    operatorRef: 'risk:decision',
    display: { name: 'Decision Consumer', description: 'Consumes a scored profile.' },
    source: { kind: 'operator-library', libraryId: 'risk-policy' },
    lowering: { mode: 'design' },
    ports: {
      inputs: [
        {
          name: 'profile',
          required: true,
          schema: schema({
            type: 'object',
            properties: {
              score: { type: 'number' },
            },
            required: ['score'],
          }),
        },
      ],
      outputs: [
        {
          name: 'output',
          schema: schema({
            type: 'object',
            properties: {
              approved: { type: 'boolean' },
            },
          }),
        },
      ],
    },
  };
}

function foreachOperator(): OperatorDefinition {
  return {
    operatorRef: '__foreach__:enrichOrders',
    display: { name: 'foreach enrich orders', description: 'Loops order enrichment.' },
    source: { kind: 'java-operator' },
    ports: {
      inputs: [
        {
          name: 'input',
          required: true,
          schema: schema({
            type: 'array',
            items: { type: 'object', additionalProperties: true },
          }),
        },
      ],
      outputs: [
        {
          name: 'output',
          schema: schema({
            type: 'array',
            items: { type: 'object', additionalProperties: true },
          }),
        },
      ],
    },
  };
}

function decisionTableOperator(): OperatorDefinition {
  return {
    operatorRef: 'bloge:decisionTable',
    display: { name: 'Decision Table', description: 'Rules with typed inputs.', tags: ['logic', 'rules'] },
    source: { kind: 'bloge-dsl' },
    ports: {
      inputs: [
        {
          name: 'inputs',
          required: true,
          schema: schema({ type: 'object', additionalProperties: true }),
        },
      ],
      outputs: [
        {
          name: 'output',
          schema: schema({ type: 'object', additionalProperties: true }),
        },
      ],
    },
  };
}

function transformOperator(): OperatorDefinition {
  return {
    operatorRef: 'bloge:transform',
    display: { name: 'Transform', description: 'Maps fields into an output object.', tags: ['logic', 'mapping'] },
    source: { kind: 'bloge-dsl' },
    lowering: { mode: 'transform' },
    ports: {
      inputs: [
        {
          name: 'inputs',
          required: false,
          schema: schema({ type: 'object', additionalProperties: true }),
        },
      ],
      outputs: [
        {
          name: 'output',
          schema: schema({ type: 'object', additionalProperties: true }),
        },
      ],
    },
  };
}

function coalesceFunction(): BuiltInFunctionDefinition {
  return {
    name: 'coalesce',
    namespace: 'bloge',
    description: 'Returns the first non-null argument.',
    category: 'null-handling',
    signatures: [
      {
        label: 'coalesce(value, fallback)',
        parameters: [
          { name: 'value', type: 'any' },
          { name: 'fallback', type: 'any' },
        ],
        returns: { type: 'any' },
      },
    ],
    examples: ['coalesce(inputs.primaryScore, 0)'],
  };
}

function loanSimulationResponse(output: Record<string, unknown>): SimulationResponse {
  return {
    validated: true,
    compiled: true,
    success: true,
    graphName: 'visualGraph',
    outputNode: 'n5',
    output,
    results: { n5: output },
    statusMap: {},
    mockedNodeIds: ['n1', 'n2', 'n3'],
    realNodeIds: ['n4', 'n5'],
    terminalOutputConforms: true,
    diagnostics: [],
    errors: [],
    generatedDsl: 'graph visualGraph {}',
  };
}

function httpResourceOperator(): OperatorDefinition {
  return {
    operatorRef: 'httpResource',
    display: { name: 'HTTP Resource', description: 'Descriptor-backed HTTP resource.', tags: ['resource'] },
    source: { kind: 'bloge-operator' },
    lowering: { mode: 'native' },
    runtimeReadiness: {
      state: 'GOVERNANCE_REVIEW',
      level: 'warning',
      executable: true,
      summary: 'Executable, but promotion should review governance risks.',
    },
    ports: {
      inputs: [
        {
          name: 'input',
          required: true,
          schema: schema({ type: 'object', additionalProperties: true }),
        },
      ],
      outputs: [
        {
          name: 'output',
          schema: schema({ type: 'object', additionalProperties: true }),
        },
      ],
    },
  };
}

function streamingOperator(): OperatorDefinition {
  return {
    operatorRef: 'MockCitationStreamingOperator',
    display: { name: 'Mock Citation Streaming Operator', tags: ['java', 'streaming'] },
    source: { kind: 'java-streaming-operator' },
    capabilities: { streaming: true },
    lowering: { mode: 'native' },
    runtimeReadiness: {
      state: 'RUNTIME_BLOCKED',
      level: 'warning',
      executable: false,
      summary: 'Runtime blocked in this visual runtime.',
    },
    ports: {
      inputs: [
        {
          name: 'input',
          required: true,
          schema: schema({ type: 'object', additionalProperties: true }),
        },
      ],
      outputs: [
        {
          name: 'output',
          schema: schema({ type: 'object', additionalProperties: true }),
        },
      ],
    },
  };
}

function loanApplicantResourceOperator(): OperatorDefinition {
  return resourceOperator(
    'resource:loan-applicant-service.getProfile',
    'Loan applicant profile',
    ['applicantId'],
    {
      applicantId: { type: 'string' },
      score: { type: 'integer' },
      segment: { type: 'string' },
      income: { type: 'number' },
      employmentYears: { type: 'number' },
    },
  );
}

function primaryCreditResourceOperator(): OperatorDefinition {
  return creditResourceOperator('resource:credit-provider.primary', 'Primary credit score');
}

function secondaryCreditResourceOperator(): OperatorDefinition {
  return creditResourceOperator('resource:credit-provider.secondary', 'Secondary credit score');
}

function creditResourceOperator(operatorRef: string, name: string): OperatorDefinition {
  return resourceOperator(operatorRef, name, ['userId'], {
    score: { type: 'integer' },
    provider: { type: 'string' },
    band: { type: 'string' },
  });
}

function resourceOperator(
  operatorRef: string,
  name: string,
  requiredParams: string[],
  payloadProperties: Record<string, unknown>,
): OperatorDefinition {
  const requestProperties = Object.fromEntries(
    requiredParams.map((param) => [param, { type: 'string' }]),
  );
  return {
    operatorRef,
    display: { name, description: `${name} resource.`, tags: ['resource'] },
    source: { kind: 'resource-descriptor', libraryId: operatorRef.slice('resource:'.length) },
    lowering: { mode: 'resource-descriptor' },
    ports: {
      inputs: [
        {
          name: 'params',
          required: true,
          schema: schema({
            type: 'object',
            properties: requestProperties,
            required: requiredParams,
          }),
        },
      ],
      outputs: [
        {
          name: 'payload',
          schema: schema({
            type: 'object',
            properties: payloadProperties,
            required: Object.keys(payloadProperties),
          }),
        },
      ],
    },
  };
}

function schema(schemaBody: Record<string, unknown>): SchemaEnvelope {
  return {
    format: 'json-schema',
    version: '2020-12',
    schema: schemaBody,
  };
}

function jsonResponse(body: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  });
}

function query<TElement extends Element = Element>(selector: string): TElement {
  const element = document.querySelector<TElement>(selector);
  expect(element, `Expected selector ${selector}`).not.toBeNull();
  return element as TElement;
}

function authorDraftExport(link: HTMLAnchorElement): any {
  const prefix = 'data:application/json;charset=utf-8,';
  if (!link.href.startsWith(prefix)) {
    throw new Error(`Unexpected export URL: ${link.href}`);
  }
  return JSON.parse(decodeURIComponent(link.href.slice(prefix.length)));
}

async function click(element: HTMLElement): Promise<void> {
  await act(async () => {
    element.click();
  });
}

async function doubleClick(element: HTMLElement): Promise<void> {
  await act(async () => {
    element.dispatchEvent(new MouseEvent('dblclick', { bubbles: true, cancelable: true }));
  });
}

async function drag(
  element: HTMLElement,
  eventName: string,
  dataTransfer: DataTransfer,
  position: { clientX?: number; clientY?: number } = {},
): Promise<void> {
  await act(async () => {
    const event = new Event(eventName, { bubbles: true, cancelable: true });
    Object.defineProperty(event, 'dataTransfer', { value: dataTransfer });
    Object.defineProperty(event, 'clientX', { value: position.clientX ?? 0 });
    Object.defineProperty(event, 'clientY', { value: position.clientY ?? 0 });
    element.dispatchEvent(event);
  });
}

function fakeDataTransfer(): DataTransfer {
  const values = new Map<string, string>();
  const transfer = {
    dropEffect: 'none',
    effectAllowed: 'all',
    files: [] as unknown as FileList,
    items: [] as unknown as DataTransferItemList,
    types: [] as string[],
    clearData(type?: string) {
      if (type) {
        values.delete(type);
      } else {
        values.clear();
      }
      transfer.types = Array.from(values.keys());
    },
    getData(type: string) {
      return values.get(type) ?? '';
    },
    setData(type: string, value: string) {
      values.set(type, value);
      transfer.types = Array.from(values.keys());
    },
    setDragImage() {},
  };
  return transfer as unknown as DataTransfer;
}

async function setControlValue(
  element: HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement,
  value: string,
): Promise<void> {
  await act(async () => {
    const valueSetter = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(element), 'value')?.set;
    valueSetter?.call(element, value);
    element.dispatchEvent(new Event('input', { bubbles: true }));
    element.dispatchEvent(new Event('change', { bubbles: true }));
  });
}

async function waitFor(assertion: () => void): Promise<void> {
  let lastError: unknown;
  for (let attempt = 0; attempt < 40; attempt += 1) {
    try {
      assertion();
      return;
    } catch (error) {
      lastError = error;
      await act(async () => {
        await new Promise((resolve) => setTimeout(resolve, 10));
      });
    }
  }
  throw lastError;
}
