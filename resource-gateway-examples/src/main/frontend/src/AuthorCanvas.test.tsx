// @vitest-environment jsdom
import { act, StrictMode } from 'react';
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
  getZoom: vi.fn(() => 1),
  zoomIn: vi.fn(),
  zoomOut: vi.fn(),
  zoomTo: vi.fn(),
}));

vi.mock('reactflow', async () => {
  const React = await import('react');

  return {
    default: function ReactFlowMock({ children, nodes, nodeTypes, onInit, onNodeClick, onNodeDoubleClick }: any) {
      React.useEffect(() => {
        onInit?.({
          fitView: reactFlowMocks.fitView,
          getZoom: reactFlowMocks.getZoom,
          zoomIn: reactFlowMocks.zoomIn,
          zoomOut: reactFlowMocks.zoomOut,
          zoomTo: reactFlowMocks.zoomTo,
        });
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
    window.history.replaceState({}, '', '/author/');
    reactFlowMocks.fitView.mockReset();
    reactFlowMocks.getZoom.mockReset();
    reactFlowMocks.getZoom.mockReturnValue(1);
    reactFlowMocks.zoomIn.mockReset();
    reactFlowMocks.zoomOut.mockReset();
    reactFlowMocks.zoomTo.mockReset();
    imported = false;
    host = document.createElement('div');
    document.body.appendChild(host);
    fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/api/visual/operators') {
        return jsonResponse({ operators: imported ? [eligibilityOperator()] : [] });
      }
      if (url === '/admin/visual-operator-libraries/from-capability-catalog-text') {
        expect(init).toMatchObject({
          method: 'POST',
          headers: { 'Content-Type': 'text/plain' },
          body: sampleCapabilityCatalogJson,
        });
        return jsonResponse(capabilityAdapterResult());
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
      if (url === '/api/visual/dsl-imports/preview') {
        expect(init).toMatchObject({
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
        });
        const body = JSON.parse(String(init?.body ?? '{}'));
        expect(body).toMatchObject({
          sourceId: 'migrated-eligibility.bloge',
          mode: 'preview',
        });
        expect(body.dsl).toContain('graph migratedEligibility');
        expect(body.operatorLibraryIds).toEqual(imported ? ['risk-policy'] : []);
        return jsonResponse(dslProjection());
      }
      if (url === '/api/visual/dsl-imports/rewrite-gate') {
        expect(init).toMatchObject({
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
        });
        const body = JSON.parse(String(init?.body ?? '{}'));
        expect(body).toMatchObject({
          sourceId: 'migrated-eligibility.bloge',
          mode: 'rewrite-gate',
        });
        expect(body.dsl).toContain('graph migratedEligibility');
        expect(body.operatorLibraryIds).toEqual(imported ? ['risk-policy'] : []);
        return jsonResponse(dslRewriteGate());
      }
      if (url.startsWith('/api/visual/dsl-imports/commit?')) {
        expect(url).toContain('actor=author-canvas');
        expect(url).toContain('changeSource=legacy-dsl-import');
        expect(init).toMatchObject({
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
        });
        const body = JSON.parse(String(init?.body ?? '{}'));
        expect(body).toMatchObject({
          sourceId: 'migrated-eligibility.bloge',
          mode: 'commit',
        });
        expect(body.dsl).toContain('graph migratedEligibility');
        expect(body.operatorLibraryIds).toEqual(imported ? ['risk-policy'] : []);
        return jsonResponse(dslCommitResult(), { status: 201 });
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

  it('opens a run deep link, restores its draft, focuses a node, and displays governance feedback', async () => {
    const projection = dslProjection() as { draft: Record<string, unknown> };
    const draft = { ...projection.draft, draftId: 'draft-42', revision: 7 };
    window.history.replaceState({}, '', '/author/?runId=run-99&nodeId=eligibility');
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === '/api/visual/operators') {
        return jsonResponse({ operators: [eligibilityOperator(), transformOperator()] });
      }
      if (url === '/api/visual/runs/run-99') {
        return jsonResponse({
          runId: 'run-99',
          draftId: 'draft-42',
          draftRevision: 7,
          sourceKind: 'STORED_DRAFT',
          outputNode: 'response',
          success: false,
          elapsedMs: 81,
          errors: ['Policy assertion failed.'],
        });
      }
      if (url === '/api/visual/drafts/draft-42') {
        return jsonResponse(draft);
      }
      if (url === '/api/visual/governance-gates/drafts/draft-42') {
        return jsonResponse({
          draftId: 'draft-42',
          currentRevision: 7,
          currentDraftFingerprint: 'draft-fp-7',
          freshness: 'CURRENT',
          result: {
            gateResultId: 'gate-1',
            status: 'BLOCKED',
            target: { draftId: 'draft-42', revision: 7, draftFingerprint: 'draft-fp-7' },
            issues: [{
              issueId: 'missing-owner',
              severity: 'BLOCKING',
              code: 'OWNER_APPROVAL_REQUIRED',
              message: 'Owner approval is missing.',
              targetPath: '/nodes/response',
              recommendedAction: 'Request approval from the graph owner.',
            }],
          },
        });
      }
      throw new Error(`Unexpected fetch: ${url}`);
    });

    await act(async () => {
      root = createRoot(host);
      root.render(<StrictMode><AuthorCanvas /></StrictMode>);
    });

    await waitFor(() => {
      expect(query('[data-testid="author-deep-link-notice"]').textContent).toContain('focused eligibility');
      expect(query('[data-testid="canvas-node:eligibility"]').className).toContain('selected');
    });
    expect(query('[data-testid="run-context-strip"]').textContent).toContain('run-99');
    expect(query('[data-testid="run-context-strip"]').textContent).toContain('FAILED');
    expect(query('[data-testid="governance-gate-strip"]').textContent).toContain('BLOCKED');
    expect(query('[data-testid="governance-gate-strip"]').textContent).toContain('CURRENT');

    await click(query('[data-testid="governance-issue:missing-owner"]'));

    await waitFor(() => {
      expect(query('[data-testid="canvas-node:response"]').className).toContain('selected');
      expect(query('[data-testid="author-deep-link-notice"]').textContent).toContain('missing-owner');
    });
  });

  it('resolves an operator deep link and marks stale governance feedback explicitly', async () => {
    const projection = dslProjection() as { draft: Record<string, unknown> };
    const draft = { ...projection.draft, draftId: 'draft-stale', revision: 9 };
    window.history.replaceState({}, '', '/author/?draftId=draft-stale&operatorRef=risk%3Aeligibility');
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === '/api/visual/operators') {
        return jsonResponse({ operators: [eligibilityOperator(), transformOperator()] });
      }
      if (url === '/api/visual/drafts/draft-stale') {
        return jsonResponse(draft);
      }
      if (url === '/api/visual/governance-gates/drafts/draft-stale') {
        return jsonResponse({
          draftId: 'draft-stale',
          currentRevision: 9,
          currentDraftFingerprint: 'draft-fp-9',
          freshness: 'STALE',
          result: {
            gateResultId: 'gate-old',
            status: 'PASSED',
            target: { draftId: 'draft-stale', revision: 8, draftFingerprint: 'draft-fp-8' },
            issues: [],
          },
        });
      }
      throw new Error(`Unexpected fetch: ${url}`);
    });

    await act(async () => {
      root = createRoot(host);
      root.render(<AuthorCanvas />);
    });

    await waitFor(() => {
      expect(query('[data-testid="canvas-node:eligibility"]').className).toContain('selected');
      expect(query('[data-testid="governance-gate-strip"]').textContent).toContain('STALE');
    });
    expect(query('[data-testid="governance-gate-strip"]').className).toContain('warning');
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

  it('requires explicit warning acknowledgement and an audit reason before DESIGN import', async () => {
    let importedWithAcknowledgement = false;
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === '/api/visual/operators') {
        return jsonResponse({ operators: [] });
      }
      if (url === '/admin/visual-operator-libraries/validate-text') {
        return jsonResponse({
          valid: true,
          diagnostics: [{
            level: 'WARNING',
            code: 'visual.operator.sideEffectProtocol.required',
            message: 'External write can only be imported for DESIGN authoring.',
          }],
          profile: { libraryId: 'side-effect-demo', operatorCount: 1 },
          importReadiness: { state: 'warning-ack-required', level: 'warning', message: 'Review warnings.' },
        });
      }
      if (url.startsWith('/admin/visual-operator-libraries/import-text?')) {
        expect(url).toContain('ackWarnings=true');
        expect(url).toContain('reason=Reviewed+DESIGN-only+external+write');
        importedWithAcknowledgement = true;
        return jsonResponse({ libraryId: 'side-effect-demo', operators: [] }, { status: 201 });
      }
      throw new Error(`Unexpected fetch: ${url}`);
    });

    await act(async () => {
      root = createRoot(host);
      root.render(<AuthorCanvas />);
    });
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/visual/operators'));
    await setControlValue(query<HTMLTextAreaElement>('[data-testid="operator-library-source"]'), sampleLibraryYaml);
    await click(query<HTMLButtonElement>('[data-testid="operator-library-validate"]'));
    await waitFor(() =>
      expect(query('[data-testid="operator-library-warning-ack"]').textContent).toContain('Audit reason'),
    );

    const importButton = query<HTMLButtonElement>('[data-testid="operator-library-import"]');
    expect(importButton.disabled).toBe(true);
    await click(query<HTMLInputElement>('[data-testid="operator-library-ack-warnings"]'));
    await setControlValue(
      query<HTMLInputElement>('[data-testid="operator-library-warning-reason"]'),
      'Reviewed DESIGN-only external write',
    );
    expect(importButton.disabled).toBe(false);
    await click(importButton);
    await waitFor(() => expect(importedWithAcknowledgement).toBe(true));
  });

  it('offers a canvas focus mode for wide topology review', async () => {
    await act(async () => {
      root = createRoot(host);
      root.render(<AuthorCanvas />);
    });

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/visual/operators'));
    const workspace = query<HTMLElement>('.workspace');
    const focusToggle = query<HTMLButtonElement>('[data-testid="canvas-focus-toggle"]');
    expect(workspace.dataset.layoutMode).toBe('standard');
    expect(workspace.classList.contains('canvas-focus')).toBe(false);
    expect(focusToggle.getAttribute('aria-pressed')).toBe('false');
    expect(focusToggle.textContent).toContain('Canvas Focus');

    await click(focusToggle);

    expect(workspace.dataset.layoutMode).toBe('focus');
    expect(workspace.classList.contains('canvas-focus')).toBe(true);
    expect(focusToggle.getAttribute('aria-pressed')).toBe('true');
    expect(focusToggle.textContent).toContain('Exit Focus');

    await click(focusToggle);

    expect(workspace.dataset.layoutMode).toBe('standard');
    expect(workspace.classList.contains('canvas-focus')).toBe(false);
    expect(focusToggle.getAttribute('aria-pressed')).toBe('false');
  });

  it('exposes zoom controls and an overview navigator for graph shape review', async () => {
    imported = true;
    await act(async () => {
      root = createRoot(host);
      root.render(<AuthorCanvas />);
    });

    await waitFor(() =>
      expect(query('[data-testid="operator-button:risk:eligibility"]').textContent).toContain('Eligibility'),
    );
    await click(query<HTMLButtonElement>('[data-testid="operator-button:risk:eligibility"]'));

    await waitFor(() => expect(document.body.textContent).toContain('1 nodes'));
    expect(query('[data-testid="canvas-navigator"]').textContent).toContain('Map');
    expect(query('[data-testid="canvas-zoom-readout"]').textContent).toBe('100%');

    await click(query<HTMLButtonElement>('[data-testid="author-fit-all"]'));
    expect(reactFlowMocks.fitView).toHaveBeenCalledWith({ padding: 0.18, duration: 240 });

    await click(query<HTMLButtonElement>('[data-testid="author-zoom-out"]'));
    expect(reactFlowMocks.zoomOut).toHaveBeenCalledWith({ duration: 160 });

    await click(query<HTMLButtonElement>('[data-testid="author-zoom-in"]'));
    expect(reactFlowMocks.zoomIn).toHaveBeenCalledWith({ duration: 160 });

    await click(query<HTMLButtonElement>('[data-testid="author-zoom-reset"]'));
    expect(reactFlowMocks.zoomTo).toHaveBeenCalledWith(1, { duration: 180 });

    const overviewToggle = query<HTMLButtonElement>('[data-testid="author-overview-toggle"]');
    expect(overviewToggle.getAttribute('aria-pressed')).toBe('true');
    await click(overviewToggle);
    expect(overviewToggle.getAttribute('aria-pressed')).toBe('false');
    expect(query('[data-testid="canvas-navigator"]').classList.contains('collapsed')).toBe(true);
  });

  it('adapts a framework capability catalog into the standard visual library draft', async () => {
    await act(async () => {
      root = createRoot(host);
      root.render(<AuthorCanvas />);
    });

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/visual/operators'));
    await setControlValue(query<HTMLTextAreaElement>('[data-testid="operator-library-source"]'),
      sampleCapabilityCatalogJson);
    await click(query<HTMLButtonElement>('[data-testid="operator-library-adapt-capability"]'));

    await waitFor(() =>
      expect(query('[data-testid="operator-library-notice"]').textContent)
        .toContain('Adapted risk-capabilities: 1 operators / 1 functions (FULL). Validate before importing.'),
    );
    const source = query<HTMLTextAreaElement>('[data-testid="operator-library-source"]');
    expect(source.value).toContain('"schemaVersion": "bloge.visualOperatorLibrary.v1"');
    expect(source.value).toContain('"libraryId": "risk-capabilities"');
    expect(source.value).toContain('"builtInFunctions"');
    expect(source.value).toContain('"lowering"');
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

  it('renders legacy DSL into the same editable canvas draft when schema structures are valid', async () => {
    await act(async () => {
      root = createRoot(host);
      root.render(<AuthorCanvas />);
    });

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/visual/operators'));
    await click(query<HTMLButtonElement>('[data-testid="legacy-dsl-preview"]'));

    await waitFor(() =>
      expect(query('[data-testid="legacy-dsl-notice"]').textContent)
        .toContain('Rendered 2 nodes / 2 edges; round-trip SUPPORTED.'),
    );

    expect(query('[data-testid="legacy-dsl-coverage"]').textContent).toContain('2 nodes');
    expect(query('[data-testid="legacy-dsl-round-trip"]').textContent)
      .toContain('Round tripSUPPORTED');
    expect(query('[data-testid="legacy-dsl-round-trip"]').textContent)
      .toContain('Generated DSL re-parsed into the same canonical visual semantics');
    expect(query('[data-testid="legacy-dsl-round-trip"]').textContent).toContain('Generated DSL');
    expect(query('[data-testid="legacy-dsl-source-map"]').textContent).toContain('6 refs');
    expect(query('[data-testid="legacy-dsl-source-map"]').textContent).toContain('node · 10:3');
    expect(query('[data-testid="legacy-dsl-source-map"]').textContent)
      .toContain('node eligibility : "risk:eligibility"');
    expect(query('[data-testid="author-graph-contract"]').textContent)
      .toContain('DSL migrated-eligibility.bloge');
    expect(query('[data-testid="author-graph-contract"]').textContent).toContain('eligible');
    expect(query<HTMLInputElement>('[data-testid="context-variable-path:0"]').value).toBe('score');
    expect(query('[data-testid="canvas-node:eligibility"][data-operator-ref="risk:eligibility"]').textContent)
      .toContain('eligibility');
    expect(query('[data-testid="canvas-node:response"][data-operator-ref="bloge:transform"]').textContent)
      .toContain('response');
    expect(query('[data-testid="node-wrapper:eligibility"]').getAttribute('data-position')).toBe('96,72');
    expect(query('[data-testid="node-wrapper:response"]').getAttribute('data-position')).toBe('504,72');

    const exported = authorDraftExport(query<HTMLAnchorElement>('[data-testid="author-draft-export"]'));
    expect(exported).toMatchObject({
      graphName: 'migratedEligibility',
      inputSchema: {
        schema: {
          properties: {
            score: { type: 'integer' },
            amount: { type: 'number' },
          },
        },
      },
      visualLayout: {
        import: {
          sourceMap: {
            nodes: {
              eligibility: { startLine: 10, startColumn: 3 },
            },
          },
        },
        graphContract: {
          schemaSource: 'dsl',
          outputSchema: {
            schema: {
              properties: {
                eligible: { type: 'boolean' },
              },
            },
          },
        },
      },
      operatorFingerprints: { eligibility: 'fp-risk-eligibility' },
      operatorSnapshots: {
        eligibility: { operatorRef: 'risk:eligibility' },
        response: { operatorRef: 'bloge:transform' },
      },
    });
    expect(exported.nodes.find((node: { id: string }) => node.id === 'eligibility')?.position).toEqual({ x: 96, y: 72 });
    expect(exported.nodes.find((node: { id: string }) => node.id === 'response')?.position).toEqual({ x: 504, y: 72 });
    expect(exported.edges[0]).toMatchObject({
      kind: 'data',
      source: { nodeId: 'eligibility', port: 'output', path: 'eligible' },
      target: { nodeId: 'response', port: 'inputs', path: 'eligible' },
    });

    await click(query<HTMLButtonElement>('[data-testid="legacy-dsl-source-map-row:node:eligibility"]'));
    expect(query('[data-testid="legacy-dsl-source-map-row:node:eligibility"]').className)
      .toContain('selected');

    await click(query<HTMLButtonElement>('[data-testid="legacy-dsl-rewrite-gate"]'));
    await waitFor(() =>
      expect(query('[data-testid="legacy-dsl-rewrite-gate-result"]').textContent)
        .toContain('Rewrite gateALLOW_REWRITE'),
    );
    expect(query('[data-testid="legacy-dsl-rewrite-gate-result"]').textContent)
      .toContain('Auto rewrite allowed');
    expect(query('[data-testid="legacy-dsl-notice"]').textContent)
      .toContain('Generated DSL has the same canonical visual semantics');

    await click(query<HTMLButtonElement>('[data-testid="legacy-dsl-commit"]'));
    await waitFor(() =>
      expect(query('[data-testid="legacy-dsl-notice"]').textContent)
        .toContain('Stored draft draft-migrated-eligibility @1.'),
    );
    const storedExport = authorDraftExport(query<HTMLAnchorElement>('[data-testid="author-draft-export"]'));
    expect(storedExport).toMatchObject({
      draftId: 'draft-migrated-eligibility',
      revision: 1,
      visualLayout: {
        import: {
          sourceMap: {
            nodes: {
              eligibility: { startLine: 10, startColumn: 3 },
            },
          },
        },
      },
    });
    expect(storedExport.nodes.find((node: { id: string }) => node.id === 'eligibility')?.position)
      .toEqual({ x: 96, y: 72 });
    expect(storedExport.nodes.find((node: { id: string }) => node.id === 'response')?.position)
      .toEqual({ x: 504, y: 72 });
  });

  it('uses a larger overview and tighter fit padding for complex DSL projections', async () => {
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/api/visual/operators') {
        return jsonResponse({ operators: [] });
      }
      if (url === '/api/visual/dsl-imports/preview') {
        expect(init).toMatchObject({
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
        });
        return jsonResponse(largeDslProjection());
      }
      throw new Error(`Unexpected fetch: ${url}`);
    });

    await act(async () => {
      root = createRoot(host);
      root.render(<AuthorCanvas />);
    });

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/visual/operators'));
    await click(query<HTMLButtonElement>('[data-testid="legacy-dsl-preview"]'));

    await waitFor(() =>
      expect(query('[data-testid="legacy-dsl-notice"]').textContent)
        .toContain('Rendered 30 nodes / 29 edges; round-trip SUPPORTED.'),
    );

    expect(query('[data-testid="canvas-navigator"]').textContent).toContain('Large Map');
    expect(query('[data-testid="canvas-navigator"]').classList.contains('complex')).toBe(true);
    expect(query('[data-testid="canvas-zoom-readout"]').textContent).toBe('100%');
    await waitFor(() =>
      expect(reactFlowMocks.fitView).toHaveBeenCalledWith({ padding: 0.06, duration: 240 }),
    );
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
    let governedFixtureId = '';
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
      if (url === '/api/testing/targets/operators/httpResource') {
        expect(init?.headers).toMatchObject({
          Authorization: 'Bearer bloge-aneke-demo-token',
          'X-Purpose': 'TEST_FIXTURE_WRITE',
        });
        return jsonResponse(operatorTestTarget('httpResource', 'CONDITIONAL_TRANSPORT'));
      }
      if (url.startsWith('/api/testing/fixture-bundles/')) {
        expect(init).toMatchObject({
          method: 'PUT',
          headers: {
            Authorization: 'Bearer bloge-aneke-demo-token',
            'X-Purpose': 'TEST_FIXTURE_WRITE',
            'Content-Type': 'application/json',
          },
        });
        governedFixtureId = decodeURIComponent(url.slice('/api/testing/fixture-bundles/'.length));
        expect(governedFixtureId).toMatch(/^canvas-httpResource-case-1-[0-9a-f]{64}$/);
        const body = JSON.parse(String(init?.body));
        expect(body).toMatchObject({
          schemaVersion: 'bloge.fixtureBundleRegistrationRequest.v1',
          target: { kind: 'OPERATOR', id: 'httpResource', fingerprint: 'sha256:operator-target' },
          fixtureBundle: {
            fixtureBundleId: governedFixtureId,
            revision: 1,
            targetFingerprint: 'sha256:operator-target',
            rules: [{
              selector: { nodeId: 'subject', resourceRef: 'loan-applicant-service.getProfile' },
              behavior: { kind: 'RETURN', boundary: 'TRANSPORT', statusCode: 200 },
            }],
            assertions: [{ nodeId: 'subject', path: '/payload', operator: 'EQUALS' }],
          },
        });
        expect(JSON.parse(body.fixtureBundle.rules[0].behavior.rawBody)).toMatchObject({
          code: 0,
          success: true,
          fixtureSource: 'author-supplied',
          data: { applicantId: 'applicant-1001', score: 715 },
        });
        return jsonResponse({
          schemaVersion: 'bloge.storedFixtureBundle.v1',
          tenantId: 'tenant-a',
          environmentId: 'test',
          fixtureBundleId: governedFixtureId,
          revision: 1,
          fingerprint: 'sha256:governed-fixture',
          createdAt: '2026-07-15T12:00:00Z',
          createdBy: 'author-canvas',
        });
      }
      if (url === '/api/testing/targets/operators/httpResource/executions') {
        expect(init).toMatchObject({
          method: 'POST',
          headers: {
            Authorization: 'Bearer bloge-aneke-demo-token',
            'X-Purpose': 'TEST_EXECUTION',
            'Content-Type': 'application/json',
          },
        });
        const body = JSON.parse(String(init?.body));
        expect(body).toMatchObject({
          schemaVersion: 'bloge.testOperatorExecutionRequest.v1',
          target: { kind: 'OPERATOR', id: 'httpResource', fingerprint: 'sha256:operator-target' },
          executionPurpose: 'OPERATOR_UNIT_TEST',
          fixtureBundle: null,
          fixtureBundleRef: {
            fixtureBundleId: governedFixtureId,
            revision: 1,
            fingerprint: 'sha256:governed-fixture',
          },
        });
        expect(body.input).toEqual({
          resourceId: 'loan-applicant-service.getProfile',
          params: { applicantId: 'applicant-1001' },
        });
        return jsonResponse(operatorTestExecution('operator-run-1', {
          resourceId: 'loan-applicant-service.getProfile',
          statusCode: 200,
          payload: { applicantId: 'applicant-1001', score: 715 },
          success: true,
        }, 'CERTIFIABLE'));
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
          expect(node.expectedInput, `${template.key}:${node.id}`).toBeDefined();
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

  it('keeps built-in run-table examples aligned with executable demo graphs', () => {
    const loan = CANVAS_EXAMPLE_TEMPLATES.find((template) => template.key === 'loan-policy-fallback');
    const loanDecision = loan?.nodes.find((node) => node.id === 'n4');
    const loanRules = loanDecision?.config?.rules as Array<{ conditions?: Record<string, string> }> | undefined;
    expect(loanRules?.[1].conditions?.score).toContain('score < 720');

    const order = CANVAS_EXAMPLE_TEMPLATES.find((template) => template.key === 'order-fulfillment-lane');
    const orderEnrichment = order?.nodes.find((node) => node.id === 'n2');
    expect(orderEnrichment?.operatorRef).toBe('bloge:transform');
    expect(orderEnrichment?.fixtureOutput).toMatchObject({ items: expect.any(Array) });
    expect(orderEnrichment?.config?.assignments).toMatchObject({
      items: 'coalesce(inputs.items, [])',
    });

    expect(order?.edges).toEqual(expect.arrayContaining([
      expect.objectContaining({
        id: 'n1:payload.items->n2:inputs.items',
        targetPort: 'inputs',
        targetPath: 'items',
        bindingKey: 'items',
      }),
      expect.objectContaining({
        id: 'n2:output.items->n5:inputs.enrichedOrders',
        sourcePort: 'output',
        sourcePath: 'items',
        targetPort: 'inputs',
        targetPath: 'enrichedOrders',
      }),
    ]));

    const orderDecision = order?.nodes.find((node) => node.id === 'n4');
    const orderRules = orderDecision?.config?.rules as Array<{ conditions?: Record<string, string> }> | undefined;
    expect(orderRules?.[1].conditions).toMatchObject({
      total: 'total < 2',
      etaDays: 'etaDays <= 5',
    });
    const orderResponse = order?.nodes.find((node) => node.id === 'n5');
    expect(orderResponse?.config?.assignments).toMatchObject({
      enrichedOrders: 'coalesce(n2.output.items, [])',
    });
    expect(order?.testCases?.[1].fixtureOverrides?.n2?.output).toMatchObject({
      items: expect.any(Array),
    });
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

  it('runs a built-in resource operator test through the controlled runtime micro graph', async () => {
    await act(async () => {
      root = createRoot(host);
      root.render(<AuthorCanvas />);
    });

    await waitFor(() =>
      expect(query('[data-testid="canvas-example-load:loan-policy-fallback"]').textContent).toContain('Load'),
    );
    await click(query<HTMLButtonElement>('[data-testid="canvas-example-load:loan-policy-fallback"]'));
    await doubleClick(query<HTMLElement>('[data-testid="node-wrapper:n1"]'));
    await waitFor(() =>
      expect(query('[data-testid="operator-test-suite"]').textContent).toContain('Executable Operator Suite'),
    );

    expect(query<HTMLTextAreaElement>('[data-testid="operator-test-input:0"]').value)
      .toContain('"applicantId": "applicant-1001"');
    expect(query<HTMLTextAreaElement>('[data-testid="operator-test-transport:0"]').value)
      .toContain('"code": 0');
    const transportFixture = query<HTMLTextAreaElement>('[data-testid="operator-test-transport:0"]');
    const customTransport = transportFixture.value.replace(
      '"message": "OK"',
      '"message": "OK",\n  "fixtureSource": "author-supplied"',
    );
    await setControlValue(transportFixture, customTransport);
    const expectedOutput = query<HTMLTextAreaElement>('[data-testid="operator-test-output:0"]');
    await setControlValue(expectedOutput, `${expectedOutput.value}\n`);
    expect(query<HTMLTextAreaElement>('[data-testid="operator-test-transport:0"]').value)
      .toBe(customTransport);
    await click(query<HTMLButtonElement>('[data-testid="operator-test-govern:0"]'));

    await waitFor(() =>
      expect(query('[data-testid="operator-test-status:0"]').textContent).toContain('passed'),
    );
    expect(query('[data-testid="operator-test-summary"]').textContent).toContain('1/1 passed');
    expect(query('[data-testid="operator-test-result:0"]').textContent)
      .toContain('Governed micro-graph PASSED · CERTIFIABLE · fixture canvas-httpResource-case-1-');
    expect(query('[data-testid="operator-test-result:0"]').textContent)
      .toContain('@1 · run operator-run-1');
    expect(query('[data-testid="operator-test-actual:0"]').textContent)
      .toContain('loan-applicant-service.getProfile');
    expect(fetchMock.mock.calls.filter(([input]) => String(input) === '/api/visual/graphs/simulate'))
      .toHaveLength(0);
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
    fetchMock.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === '/api/visual/operators') {
        return jsonResponse({ operators: [httpResourceOperator(), streamingOperator()] });
      }
      if (url === '/api/testing/targets/operators/httpResource') {
        return jsonResponse(operatorTestTarget('httpResource', 'CONDITIONAL_TRANSPORT'));
      }
      if (url === '/api/testing/targets/operators/httpResource/executions') {
        const body = JSON.parse(String(init?.body));
        expect(body).toMatchObject({
          fixtureBundle: {
            rules: [{
              selector: { resourceRef: 'customer-service.get' },
              behavior: { boundary: 'TRANSPORT' },
            }],
            assertions: [{ path: '/payload', expected: { ok: false, source: 'operator-case' } }],
          },
        });
        expect(body.input).toEqual({
          resourceId: 'customer-service.get',
          params: { customerId: 'c-42' },
        });
        return jsonResponse(operatorTestExecution('operator-run-2', {
          resourceId: 'customer-service.get',
          payload: { ok: false, source: 'operator-case' },
          success: true,
        }));
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
    expect(dialogQuery('[data-testid="operator-test-suite"]').textContent).toContain('Executable Operator Suite');
    expect(dialogQuery('[data-testid="operator-test-status:0"]').textContent).toContain('valid');
    await click(dialogQuery<HTMLButtonElement>('[data-testid="operator-test-add"]'));
    await waitFor(() =>
      expect(dialogQuery('[data-testid="operator-test-row:1"]').textContent).toContain('Apply Fixture'),
    );
    await setControlValue(
      dialogQuery<HTMLTextAreaElement>('[data-testid="operator-test-input:1"]'),
      '{"resourceId":"customer-service.get","customerId":"c-42"}',
    );
    await setControlValue(
      dialogQuery<HTMLTextAreaElement>('[data-testid="operator-test-output:1"]'),
      '{"ok":false,"source":"operator-case"}',
    );
    await click(dialogQuery<HTMLButtonElement>('[data-testid="operator-test-run:1"]'));
    await waitFor(() =>
      expect(dialogQuery('[data-testid="operator-test-status:1"]').textContent).toContain('passed'),
    );
    expect(dialogQuery('[data-testid="operator-test-result:1"]').textContent)
      .toContain('Real micro-graph PASSED · EXPLORATORY · run operator-run-2');
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
        expectedInput: { resourceId: 'customer-service.get', customerId: 'c-42' },
        output: { ok: false, source: 'operator-case' },
      },
    });
  });

  it('shows managed and blocked external-write protocols in the main authoring surface', async () => {
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      if (String(input) === '/api/visual/operators') {
        return jsonResponse({ operators: [externalWriteOperator('orders:managed', true), externalWriteOperator('orders:blocked', false)] });
      }
      throw new Error(`Unexpected fetch: ${String(input)}`);
    });

    await act(async () => {
      root = createRoot(host);
      root.render(<AuthorCanvas />);
    });

    await waitFor(() =>
      expect(query('[data-testid="operator-button:orders:managed"]').textContent).toContain('managed write'),
    );
    expect(query('[data-testid="operator-button:orders:blocked"]').textContent)
      .toContain('write protocol required');

    await click(query<HTMLButtonElement>('[data-testid="operator-button:orders:blocked"]'));
    expect(query('[data-testid="canvas-node:n1"]').textContent).toContain('write blocked');
    await click(query<HTMLElement>('[data-testid="node-wrapper:n1"]'));
    expect(query('[data-testid="operator-focus:generic"]').textContent).toContain('Side-effect protocol');
    expect(query('[data-testid="operator-focus:generic"]').textContent).toContain('DESIGN-only');
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

const sampleCapabilityCatalogJson = JSON.stringify({
  schemaVersion: 'bloge.capabilityCatalog.v1',
  catalogId: 'risk-capabilities',
  displayName: 'Risk Capabilities',
  blogeVersion: '1.2.3',
  operators: [
    {
      operatorRef: 'risk:eligibility',
      display: { name: 'Eligibility', tags: ['risk'] },
      implementation: { kind: 'java-operator', className: 'com.acme.RiskEligibilityOperator' },
      ports: {
        inputs: [{ name: 'applicant', required: true, schema: schema({ type: 'object' }) }],
        outputs: [{ name: 'decision', schema: schema({ type: 'object' }) }],
      },
      capabilities: { idempotency: 'IDEMPOTENT', sideEffectType: 'READ_ONLY', deterministic: true },
    },
  ],
  functions: [
    {
      name: 'normalizeScore',
      namespace: 'risk',
      signatures: [
        {
          label: 'normalizeScore(score)',
          parameters: [{ name: 'score', type: 'Integer' }],
          returns: { type: 'Boolean' },
        },
      ],
    },
  ],
}, null, 2);

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

function capabilityAdapterResult(): unknown {
  return {
    schemaVersion: 'bloge.capabilityCatalogVisualAdapterResult.v1',
    library: {
      schemaVersion: 'bloge.visualOperatorLibrary.v1',
      libraryId: 'risk-capabilities',
      displayName: 'Risk Capabilities',
      version: '1.2.3',
      builtInFunctions: [coalesceFunction()],
      operators: [{
        ...eligibilityOperator(),
        source: { kind: 'user-library', libraryId: 'risk-capabilities' },
        lowering: {
          mode: 'design',
          operatorRef: '',
          parameters: {
            bindingTarget: 'risk:eligibility',
            capabilityCatalog: { className: 'com.acme.RiskEligibilityOperator' },
          },
        },
      }],
    },
    validation: {
      valid: true,
      diagnostics: [],
      profile: { libraryId: 'risk-capabilities', operatorCount: 1 },
      importReadiness: { state: 'design-only-importable', level: 'info' },
    },
    projectionReview: {
      coverageStatus: 'FULL',
      projectedOperatorCount: 1,
      projectedFunctionCount: 1,
      opaqueSchemaCount: 0,
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

function dslProjection(): unknown {
  return {
    schemaVersion: 'bloge.dslVisualProjection.v1',
    sourceId: 'migrated-eligibility.bloge',
    draft: {
      schemaVersion: 'bloge.visualGraphDraft.v1',
      graphName: 'migratedEligibility',
      inputSchema: schema({
        type: 'object',
        properties: {
          score: { type: 'integer' },
          amount: { type: 'number' },
        },
        required: ['score', 'amount'],
      }),
      nodes: [
        {
          id: 'eligibility',
          operatorRef: 'risk:eligibility',
          label: 'eligibility',
          inputs: {
            score: { kind: 'contextPath', path: 'score', targetPort: 'inputs', targetPath: 'score' },
            amount: { kind: 'contextPath', path: 'amount', targetPort: 'inputs', targetPath: 'amount' },
          },
          position: { x: 120, y: 120 },
        },
        {
          id: 'response',
          operatorRef: 'bloge:transform',
          label: 'response',
          config: {
            assignments: [
              { field: 'eligible', expression: 'eligibility.output.eligible' },
              { field: 'ruleId', expression: 'eligibility.output.ruleId' },
            ],
          },
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
          id: 'data_eligibility_response_ruleId',
          kind: 'data',
          source: { nodeId: 'eligibility', port: 'output', path: 'ruleId' },
          target: { nodeId: 'response', port: 'inputs', path: 'ruleId' },
        },
      ],
      visualLayout: {
        import: { mode: 'preview', sourceId: 'migrated-eligibility.bloge', schemaNeutral: true },
        graphContract: {
          schemaSource: 'dsl',
          inputSchema: schema({
            type: 'object',
            properties: {
              score: { type: 'integer' },
              amount: { type: 'number' },
            },
            required: ['score', 'amount'],
          }),
          outputSchema: schema({
            type: 'object',
            properties: {
              eligible: { type: 'boolean' },
              ruleId: { type: 'string' },
            },
            required: ['eligible', 'ruleId'],
          }),
        },
      },
      output: { nodeId: 'response', path: '' },
      operatorFingerprints: {
        eligibility: 'fp-risk-eligibility',
      },
      operatorSnapshots: {
        eligibility: eligibilityOperator(),
        response: transformOperator(),
      },
    },
    coverage: {
      memberCount: 2,
      projectedNodeCount: 2,
      edgeCount: 2,
      unsupportedSyntaxCount: 0,
      missingOperatorCount: 0,
      missingFunctionCount: 0,
    },
    roundTrip: {
      supported: true,
      status: 'SUPPORTED',
      message: 'Generated DSL re-parsed into the same canonical visual semantics as the source DSL.',
      generatedDsl: 'graph migratedEligibility { transform response { eligible = eligibility.output.eligible } }',
      sourceFingerprint: 'source-fingerprint',
      generatedFingerprint: 'source-fingerprint',
      diagnostics: [],
    },
    sourceMap: {
      nodes: {
        eligibility: {
          sourceId: 'migrated-eligibility.bloge',
          startLine: 10,
          startColumn: 3,
          dslKind: 'NodeDef',
        },
        response: {
          sourceId: 'migrated-eligibility.bloge',
          startLine: 16,
          startColumn: 3,
          dslKind: 'TransformDef',
        },
      },
      edges: {
        data_eligibility_response_eligible: {
          sourceId: 'migrated-eligibility.bloge',
          startLine: 17,
          startColumn: 16,
          dslKind: 'NodeOutputPath',
        },
        data_eligibility_response_ruleId: {
          sourceId: 'migrated-eligibility.bloge',
          startLine: 18,
          startColumn: 14,
          dslKind: 'NodeOutputPath',
        },
      },
      bindings: {
        '/nodes/eligibility/inputs/score': {
          sourceId: 'migrated-eligibility.bloge',
          startLine: 12,
          startColumn: 15,
          dslKind: 'InputBinding',
        },
        '/nodes/eligibility/inputs/amount': {
          sourceId: 'migrated-eligibility.bloge',
          startLine: 13,
          startColumn: 16,
          dslKind: 'InputBinding',
        },
      },
    },
    diagnostics: [],
  };
}

function largeDslProjection(): unknown {
  const projection = dslProjection() as {
    draft: Record<string, unknown> & {
      operatorSnapshots?: Record<string, OperatorDefinition>;
      visualLayout?: Record<string, unknown>;
    };
    coverage: Record<string, unknown>;
    roundTrip: Record<string, unknown>;
  };
  const nodes = Array.from({ length: 30 }, (_, index) => {
    const id = `step${index + 1}`;
    const isDecision = index % 2 === 0;
    return {
      id,
      operatorRef: isDecision ? 'bloge:decisionTable' : 'bloge:transform',
      label: id,
      config: isDecision
        ? { hitPolicy: 'first', inputs: {}, rules: [] }
        : { assignments: [{ field: 'value', expression: `step${index}.output.value` }] },
      position: { x: (index % 6) * 360, y: Math.floor(index / 6) * 220 },
    };
  });
  const edges = nodes.slice(1).map((node, index) => ({
    id: `edge_step${index + 1}_${node.id}`,
    kind: 'data',
    source: { nodeId: `step${index + 1}`, port: 'output', path: 'value' },
    target: { nodeId: node.id, port: 'inputs', path: 'value' },
  }));
  const operatorSnapshots = Object.fromEntries(
    nodes.map((node) => [
      node.id,
      node.operatorRef === 'bloge:decisionTable' ? decisionTableOperator() : transformOperator(),
    ]),
  );

  return {
    ...projection,
    draft: {
      ...projection.draft,
      graphName: 'largePolicy',
      nodes,
      edges,
      output: { nodeId: 'step30', path: '' },
      operatorFingerprints: {},
      operatorSnapshots: {
        ...(projection.draft.operatorSnapshots ?? {}),
        ...operatorSnapshots,
      },
      visualLayout: {
        ...(projection.draft.visualLayout ?? {}),
        import: { mode: 'preview', sourceId: 'migrated-eligibility.bloge', schemaNeutral: true },
      },
    },
    coverage: {
      ...projection.coverage,
      memberCount: 30,
      projectedNodeCount: 30,
      edgeCount: 29,
    },
    roundTrip: {
      ...projection.roundTrip,
      generatedDsl: 'graph largePolicy { chain step1 -> step30 }',
    },
  };
}

function dslRewriteGate(): unknown {
  const projection = dslProjection() as {
    roundTrip: unknown;
  };
  return {
    schemaVersion: 'bloge.dslRewriteGate.v1',
    sourceId: 'migrated-eligibility.bloge',
    allowed: true,
    decision: 'ALLOW_REWRITE',
    message: 'Generated DSL has the same canonical visual semantics as the source projection.',
    generatedDsl: 'graph migratedEligibility { transform response { eligible = eligibility.output.eligible } }',
    roundTrip: projection.roundTrip,
    diagnostics: [],
  };
}

function dslCommitResult(): unknown {
  const projection = dslProjection() as {
    draft: Record<string, unknown>;
    sourceMap: Record<string, unknown>;
  };
  const visualLayout = projection.draft.visualLayout as { import?: Record<string, unknown> };
  return {
    schemaVersion: 'bloge.visualGraphDraftImportResult.v1',
    imported: true,
    draft: {
      ...projection.draft,
      draftId: 'draft-migrated-eligibility',
      revision: 1,
      visualLayout: {
        ...visualLayout,
        import: {
          ...(visualLayout.import ?? {}),
          mode: 'commit',
          sourceMap: projection.sourceMap,
        },
      },
    },
    diagnostics: [],
    validation: { valid: true, diagnostics: [] },
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

function externalWriteOperator(operatorRef: string, managed: boolean): OperatorDefinition {
  return {
    operatorRef,
    display: { name: managed ? 'Managed order write' : 'Blocked order write' },
    source: { kind: 'user-library', libraryId: 'orders' },
    capabilities: {
      effect: 'WRITE_EXTERNAL',
      sideEffectProtocol: managed ? {
        schemaVersion: 'bloge.sideEffectProtocol.v1',
        mode: 'JOURNALED',
        commitReceiptRequired: true,
        reconciliationRequired: true,
        reconcilerRef: 'orders.status',
        idempotencyKeySource: 'input.params.idempotencyKey',
        reconciliationLookupSource: 'input.params.lookupRef',
        commitReceiptSource: 'response.headers.x-receipt-id',
      } : undefined,
    },
    lowering: { mode: 'native' },
    runtimeReadiness: managed ? {
      state: 'RUNTIME_EXECUTABLE',
      level: 'success',
      executable: true,
    } : {
      state: 'RUNTIME_BLOCKED',
      level: 'error',
      executable: false,
      title: 'Write protocol required',
      summary: 'External write is DESIGN-only until the side-effect protocol is complete.',
    },
    ports: {
      inputs: [{ name: 'input', required: true, schema: schema({ type: 'object' }) }],
      outputs: [{ name: 'output', schema: schema({ type: 'object' }) }],
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
  const resourceId = operatorRef.slice('resource:'.length);
  const requestProperties = Object.fromEntries(
    requiredParams.map((param) => [param, { type: 'string' }]),
  );
  return {
    operatorRef,
    display: { name, description: `${name} resource.`, tags: ['resource'] },
    source: { kind: 'resource-descriptor', libraryId: resourceId },
    lowering: {
      mode: 'resource-descriptor',
      operatorRef: 'httpResource',
      parameters: { resourceId, payloadPath: 'data' },
    },
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

function operatorTestTarget(
  operatorRef: string,
  testabilityClass: 'EXECUTABLE_UNIT' | 'CONDITIONAL_TRANSPORT' = 'EXECUTABLE_UNIT',
) {
  return {
    schemaVersion: 'bloge.testOperatorTargetDescriptor.v2',
    target: { kind: 'OPERATOR', id: operatorRef, fingerprint: 'sha256:operator-target' },
    testabilityClass,
    executionSupported: true,
    certificationEligible: true,
    certificationRequirements: testabilityClass === 'CONDITIONAL_TRANSPORT'
      ? ['Every selected resource invocation requires a strict TRANSPORT raw-response fixture.']
      : [],
    certificationGaps: [],
  };
}

function operatorTestExecution(
  runId: string,
  output: unknown,
  evidenceClass: 'EXPLORATORY' | 'CERTIFIABLE' = 'EXPLORATORY',
) {
  return {
    schemaVersion: 'bloge.testExecutionResponse.v1',
    runId,
    target: { kind: 'OPERATOR', id: 'httpResource', fingerprint: 'sha256:operator-target' },
    evidence: {
      status: 'PASSED',
      evidenceClass,
      diagnostics: [],
      nodeTrace: [{
        nodeId: 'subject',
        operatorRef: 'httpResource',
        status: 'SUCCESS',
        fidelity: 'TRANSPORT_LEVEL',
        output,
      }],
      assertionResults: [{ scope: 'OUTPUT_PATH', path: '/payload', passed: true }],
    },
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
