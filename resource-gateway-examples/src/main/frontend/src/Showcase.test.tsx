// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import Showcase from './Showcase';
import type { GatewayExampleDiagram, GatewayExampleScenario } from './types';

describe('Showcase', () => {
  let root: Root | null = null;
  let host: HTMLDivElement;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === '/api/gateway/examples/scenarios') {
        return jsonResponse(sampleScenarios());
      }
      if (url === '/api/gateway/examples/scenarios/userDashboard/diagram') {
        return jsonResponse(userDashboardDiagram());
      }
      if (url === '/api/gateway/examples/scenarios/loanDecisionPolicy/diagram') {
        return jsonResponse(loanDecisionDiagram());
      }
      if (url === '/api/gateway/examples/scenarios/aiEnrichedSearch/diagram') {
        return jsonResponse(aiSearchDiagram());
      }
      if (url === '/api/gateway/dashboard/u42') {
        return jsonResponse({ success: true, data: { profile: { name: 'Taylor' } } });
      }
      if (url === '/api/gateway/dashboard/u1') {
        return jsonResponse({ success: true, data: { profile: { name: 'Alice' } } });
      }
      if (url === '/api/gateway/loan-policy/prime?amount=450000') {
        return jsonResponse({ success: true, data: { policy: { ruleId: 'R1', decision: 'approved' } } });
      }
      if (url === '/api/gateway/loan-policy/review?amount=450000') {
        return jsonResponse({ success: true, data: { policy: { ruleId: 'R3', decision: 'manual_review' } } });
      }
      throw new Error(`Unexpected fetch: ${url}`);
    }));
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

  it('renders backend scenario order and defaults to the first scenario', async () => {
    await renderShowcase();

    await waitFor(() =>
      expect(query('[data-testid="showcase-scenario:userDashboard"]').textContent)
        .toContain('User Dashboard'),
    );
    expect(scenarioIds()).toEqual([
      'showcase-scenario:userDashboard',
      'showcase-scenario:loanDecisionPolicy',
      'showcase-scenario:aiEnrichedSearch',
    ]);
    expect(query('[data-testid="showcase-detail"]').textContent)
      .toContain('Parallel fan-out aggregation');
    expect(query('[data-testid="showcase-detail"]').textContent)
      .toContain('/api/gateway/dashboard/{userId}');
    expect(query('[data-testid="showcase-sample"]').textContent)
      .toContain('"userId": "u1"');
    await waitFor(() =>
      expect(query('[data-testid="showcase-diagram-node:fetchProfile"]').textContent)
        .toContain('Profile'),
    );
    expect(query('[data-testid="showcase-node-inspector"]').textContent)
      .toContain('fetchProfile');
    expect(query('[data-testid="showcase-node-inspector"]').textContent)
      .toContain('user-service.getProfile');
  });

  it('switches to decision-table metadata and interactive diagram nodes', async () => {
    await renderShowcase();
    await waitFor(() => query('[data-testid="showcase-scenario:loanDecisionPolicy"]'));

    await click(query<HTMLButtonElement>('[data-testid="showcase-scenario:loanDecisionPolicy"]'));

    await waitFor(() =>
      expect(query('[data-testid="showcase-detail"]').textContent)
        .toContain('Loan Decision Policy'),
    );
    expect(query('[data-testid="showcase-detail"]').textContent).toContain('Presets');
    expect(query('[data-testid="showcase-detail"]').textContent).toContain('4');
    expect(query('[data-testid="showcase-decision-table"]').textContent).toContain('unique');
    expect(query('[data-testid="showcase-decision-table"]').textContent).toContain('Rows');
    expect(query('[data-testid="showcase-decision-table"]').textContent).toContain('2');

    await waitFor(() =>
      expect(query('[data-testid="showcase-diagram-node:loanPolicy"]').textContent)
        .toContain('Loan Policy Matrix'),
    );
    await click(query<SVGGElement>('[data-testid="showcase-diagram-node:loanPolicy"]'));

    await waitFor(() =>
      expect(query('[data-testid="showcase-node-inspector"]').textContent)
        .toContain('loanPolicy'),
    );
    expect(query('[data-testid="showcase-node-inspector"]').textContent)
      .toContain('DecisionTableOperator');
    expect(query('[data-testid="showcase-node-inspector"]').textContent)
      .toContain('rules');
    expect(query('[data-testid="showcase-node-inspector"]').textContent)
      .toContain('4');
  });

  it('runs the selected gateway scenario with edited sample input', async () => {
    await renderShowcase();
    await waitFor(() => query('[data-testid="showcase-input:userId"]'));

    await setControlValue(query<HTMLInputElement>('[data-testid="showcase-input:userId"]'), 'u42');
    await click(query<HTMLButtonElement>('[data-testid="showcase-run-button"]'));

    await waitFor(() =>
      expect(query('[data-testid="showcase-run-result"]').textContent)
        .toContain('/api/gateway/dashboard/u42'),
    );
    expect(query('[data-testid="showcase-run-result"]').textContent).toContain('HTTP 200');
    expect(query('[data-testid="showcase-run-result"]').textContent).toContain('Taylor');
  });

  it('applies a preset and immediately runs the scenario', async () => {
    await renderShowcase();
    await waitFor(() => query('[data-testid="showcase-scenario:loanDecisionPolicy"]'));

    await click(query<HTMLButtonElement>('[data-testid="showcase-scenario:loanDecisionPolicy"]'));
    await waitFor(() => query('[data-testid="showcase-preset:Review"]'));
    await click(query<HTMLButtonElement>('[data-testid="showcase-preset:Review"]'));

    await waitFor(() =>
      expect(query<HTMLInputElement>('[data-testid="showcase-input:applicantId"]').value)
        .toBe('review'),
    );
    expect(query('[data-testid="showcase-run-result"]').textContent)
      .toContain('/api/gateway/loan-policy/review?amount=450000');
    expect(query('[data-testid="showcase-run-result"]').textContent).toContain('R3');
    expect(query('[data-testid="showcase-run-result"]').textContent).toContain('manual_review');
  });

  async function renderShowcase() {
    await act(async () => {
      root = createRoot(host);
      root.render(<Showcase />);
    });
  }
});

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json' },
  });
}

function sampleScenarios(): GatewayExampleScenario[] {
  return [
    {
      graphName: 'userDashboard',
      title: 'User Dashboard',
      pattern: 'Parallel fan-out aggregation',
      description: 'Fetches five independent user-facing resources concurrently.',
      concepts: ['parallel fan-out', 'httpResource'],
      sampleInput: { userId: 'u1' },
      samplePresets: [],
      run: { mode: 'request', method: 'GET', pathTemplate: '/api/gateway/dashboard/{userId}' },
      diagramPath: '/api/gateway/examples/scenarios/userDashboard/diagram',
    },
    {
      graphName: 'loanDecisionPolicy',
      title: 'Loan Decision Policy',
      pattern: 'Decision-table policy matrix',
      description: 'Evaluates a UNIQUE decision table and returns the matched policy row.',
      concepts: ['decision_table', 'hit=unique'],
      sampleInput: { applicantId: 'prime', amount: 450000 },
      samplePresets: [
        { label: 'Prime', values: { applicantId: 'prime' }, expected: { ruleId: 'R1' } },
        { label: 'Near prime', values: { applicantId: 'nearPrime' }, expected: { ruleId: 'R2' } },
        { label: 'Review', values: { applicantId: 'review' }, expected: { ruleId: 'R3' } },
        { label: 'Decline', values: { applicantId: 'decline' }, expected: { ruleId: 'R4' } },
      ],
      run: {
        mode: 'request',
        method: 'GET',
        pathTemplate: '/api/gateway/loan-policy/{applicantId}?amount={amount}',
      },
      decisionTable: {
        hitPolicy: 'unique',
        columns: [{ id: 'score' }, { id: 'amount' }],
        rows: [{ ruleId: 'R1' }, { ruleId: 'R2' }],
      },
      diagramPath: '/api/gateway/examples/scenarios/loanDecisionPolicy/diagram',
    },
    {
      graphName: 'aiEnrichedSearch',
      title: 'AI Enriched Search',
      pattern: 'Mixed streaming fan-in',
      description: 'Routes each stream to a separate SSE event lane.',
      concepts: ['stream node', 'SSE'],
      sampleInput: { query: 'hello' },
      samplePresets: [],
      run: { mode: 'stream', method: 'GET', pathTemplate: '/api/gateway/ai/search/stream?q={query}' },
      diagramPath: '/api/gateway/examples/scenarios/aiEnrichedSearch/diagram',
    },
  ];
}

function userDashboardDiagram(): GatewayExampleDiagram {
  return {
    schemaVersion: 'bloge.visualLayout.v1',
    rootId: 'userDashboard',
    executionMode: 'GRAPH',
    nodes: [
      {
        id: 'fetchProfile',
        kind: 'resource',
        operatorRef: 'httpResource',
        label: 'Profile',
        position: { x: 80, y: 80 },
        size: { width: 180, height: 72 },
        group: 'parallelFetch',
        annotations: { resourceId: 'user-service.getProfile', timeout: '3s' },
      },
      {
        id: 'assembleDashboard',
        kind: 'transform',
        label: 'Assemble Dashboard',
        position: { x: 420, y: 80 },
        size: { width: 180, height: 72 },
        annotations: {},
      },
    ],
    edges: [{ id: 'fetchProfile->assembleDashboard', source: 'fetchProfile', target: 'assembleDashboard', label: 'profile' }],
    groups: [{ id: 'parallelFetch', label: 'Parallel API fan-out', kind: 'parallel' }],
    viewport: { x: 0, y: 0, zoom: 1 },
  };
}

function loanDecisionDiagram(): GatewayExampleDiagram {
  return {
    schemaVersion: 'bloge.visualLayout.v1',
    rootId: 'loanDecisionPolicy',
    executionMode: 'GRAPH',
    nodes: [
      {
        id: 'fetchApplicant',
        kind: 'resource',
        operatorRef: 'httpResource',
        label: 'Applicant Profile',
        position: { x: 80, y: 210 },
        size: { width: 180, height: 72 },
        annotations: { resourceId: 'loan-applicant-service.getProfile' },
      },
      {
        id: 'loanPolicy',
        kind: 'decision-table',
        operatorRef: 'DecisionTableOperator',
        label: 'Loan Policy Matrix',
        position: { x: 360, y: 210 },
        size: { width: 180, height: 72 },
        group: 'policyMatrix',
        annotations: { hitPolicy: 'unique', rules: 4 },
      },
    ],
    edges: [{ id: 'fetchApplicant->loanPolicy', source: 'fetchApplicant', target: 'loanPolicy', label: 'risk facts' }],
    groups: [{ id: 'policyMatrix', label: 'Auditable rule matrix', kind: 'decision-table' }],
    viewport: { x: 0, y: 0, zoom: 1 },
  };
}

function aiSearchDiagram(): GatewayExampleDiagram {
  return {
    schemaVersion: 'bloge.visualLayout.v1',
    rootId: 'aiEnrichedSearch',
    executionMode: 'GRAPH',
    nodes: [
      {
        id: 'llmStream',
        kind: 'stream',
        operatorRef: 'MockLlmTokenStreamingOperator',
        label: 'LLM Tokens',
        position: { x: 80, y: 240 },
        size: { width: 180, height: 72 },
        annotations: { event: 'token' },
      },
    ],
    edges: [],
    groups: [],
    viewport: { x: 0, y: 0, zoom: 1 },
  };
}

function scenarioIds(): string[] {
  return Array.from(document.querySelectorAll('[data-testid^="showcase-scenario:"]'))
    .map((element) => element.getAttribute('data-testid') ?? '');
}

function query<T extends Element = Element>(selector: string): T {
  const element = document.querySelector<T>(selector);
  if (!element) {
    throw new Error(`Missing element: ${selector}`);
  }
  return element;
}

async function click(element: Element) {
  await act(async () => {
    element.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  });
}

async function setControlValue(element: HTMLInputElement, value: string) {
  await act(async () => {
    const valueSetter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
    valueSetter?.call(element, value);
    element.dispatchEvent(new Event('input', { bubbles: true }));
  });
}

async function waitFor(assertion: () => void) {
  const deadline = Date.now() + 1000;
  for (;;) {
    try {
      assertion();
      return;
    } catch (error) {
      if (Date.now() > deadline) {
        throw error;
      }
      await act(async () => {
        await new Promise((resolve) => setTimeout(resolve, 10));
      });
    }
  }
}
