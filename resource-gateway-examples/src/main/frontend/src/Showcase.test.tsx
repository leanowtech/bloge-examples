// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import Showcase from './Showcase';
import type { GatewayExampleScenario } from './types';

describe('Showcase', () => {
  let root: Root | null = null;
  let host: HTMLDivElement;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === '/api/gateway/examples/scenarios') {
        return jsonResponse(sampleScenarios());
      }
      throw new Error(`Unexpected fetch: ${String(input)}`);
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
  });

  it('switches to decision-table metadata without losing catalog context', async () => {
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

async function click(element: HTMLElement) {
  await act(async () => {
    element.dispatchEvent(new MouseEvent('click', { bubbles: true }));
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
