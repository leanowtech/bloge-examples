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
    default: function ReactFlowMock({ children, nodes, nodeTypes }: any) {
      return React.createElement(
        'div',
        { 'data-testid': 'react-flow' },
        nodes.map((node: any) => {
          const Component = nodeTypes?.[node.type] ?? (() => React.createElement('div', null, node.id));
          return React.createElement(Component, {
            key: node.id,
            id: node.id,
            data: node.data,
            selected: false,
          });
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
