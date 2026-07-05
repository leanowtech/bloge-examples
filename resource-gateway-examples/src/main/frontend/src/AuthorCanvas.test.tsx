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
    default: function ReactFlowMock({ children, nodes, nodeTypes, onNodeClick }: any) {
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
              onClick: () => onNodeClick?.({}, node),
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
              target: { nodeId: 'n2', port: 'profile' },
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
        expect(body.target).toEqual({ nodeId: 'n2', port: 'profile' });
        return jsonResponse({
          accepted: true,
          edge: {
            id: 'n1:decision->n2:profile',
            kind: 'data',
            source: { nodeId: 'n1', port: 'decision' },
            target: { nodeId: 'n2', port: 'profile' },
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

async function click(element: HTMLElement): Promise<void> {
  await act(async () => {
    element.click();
  });
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
