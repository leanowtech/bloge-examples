// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import AuthorCanvas from './AuthorCanvas';
import type {
  OperatorDefinition,
  OperatorLibrary,
  OperatorLibraryValidationResult,
  SchemaEnvelope,
} from './types';

vi.mock('reactflow', async () => {
  const React = await import('react');

  return {
    default: function ReactFlowMock({ children, nodes, nodeTypes, onNodeClick, onNodeDoubleClick }: any) {
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
      expect(query('[data-testid="node-wrapper:n1"]').getAttribute('data-position')).toBe('72,56'),
    );
    expect(query('[data-testid="node-wrapper:n2"]').getAttribute('data-position')).toBe('332,56');
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

    await click(query<HTMLElement>('[data-testid="node-wrapper:n2"]'));
    expect(query('[data-testid="operator-focus:decision-table"]').textContent).toContain('Rule contract');
    expect(query('[data-testid="operator-focus:decision-table"]').textContent).toContain('Condition inputs');
    expect(query('[data-testid="operator-focus:decision-table"]').textContent).toContain('Decision output');
    expect(query('[data-testid="operator-focus:decision-table"]').textContent).toContain('Rule matrix');

    await doubleClick(query<HTMLElement>('[data-testid="node-wrapper:n2"]'));
    await waitFor(() =>
      expect(query('[data-testid="decision-table-editor"]').textContent).toContain('Decision Table'),
    );
    expect(query('[data-testid="decision-table-editor"]').textContent).toContain('When');
    expect(query('[data-testid="decision-table-editor"]').textContent).toContain('Decision');

    await setControlValue(query<HTMLInputElement>('[data-testid="decision-rule-condition:0"]'), 'score: score >= 700');
    await setControlValue(query<HTMLInputElement>('[data-testid="decision-rule-decision:0"]'), 'approve');
    await setControlValue(query<HTMLInputElement>('[data-testid="decision-rule-id:0"]'), 'prime');

    const exportLink = query<HTMLAnchorElement>('[data-testid="author-draft-export"]');
    expect(authorDraftExport(exportLink).nodes[1].config).toMatchObject({
      hitPolicy: 'unique',
      outputType: '{ decision: String, ruleId: String }',
      rules: [
        {
          conditions: 'score: score >= 700',
          output: {
            decision: 'approve',
            ruleId: 'prime',
          },
        },
        {
          otherwise: true,
          output: {
            decision: 'fallback',
            ruleId: 'otherwise',
          },
        },
      ],
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

async function setControlValue(element: HTMLInputElement | HTMLTextAreaElement, value: string): Promise<void> {
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
