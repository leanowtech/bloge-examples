// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import I18nProvider from './i18n/I18nProvider';
import Showcase from './Showcase';
import type { GatewayExampleDiagram, GatewayExampleScenario } from './types';

describe('Showcase', () => {
  let root: Root | null = null;
  let host: HTMLDivElement;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    FakeEventSource.instances = [];
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
      if (url === '/api/gateway/loan-policy/nearPrime?amount=450000') {
        return jsonResponse({ success: true, data: { policy: { ruleId: 'RX', decision: 'manual_review' } } });
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
    window.history.replaceState({}, '', '/showcase/');
    window.localStorage?.clear();
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
    const advanced = query<HTMLDetailsElement>('details.showcase-actions');
    expect(advanced.open).toBe(false);
    expect(advanced.querySelector('a[href="/examples/gateway"]')).not.toBeNull();
    await click(advanced.querySelector<HTMLButtonElement>('summary')!);
    expect(advanced.open).toBe(true);
  });

  it('renders built-in scenario metadata in Chinese while preserving graph coordinates', async () => {
    window.history.replaceState({}, '', '/showcase/?lang=zh-CN');
    await renderShowcase(true);

    await waitFor(() => expect(query('[data-testid="showcase-detail"]').textContent)
      .toContain('用户仪表盘'));
    expect(query('[data-testid="showcase-detail"]').textContent).toContain('并行扇出聚合');
    expect(query('[data-testid="showcase-detail"]').textContent).toContain('并发获取五项');
    expect(query('[data-testid="showcase-detail"]').textContent).toContain('并行扇出');
    expect(host.textContent).toContain('userDashboard');
    expect(query('[data-testid="showcase-detail"]').textContent).not.toContain('User Dashboard');
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
    const decisionTable = query('[data-testid="showcase-decision-table"]');
    expect(decisionTable.textContent).toContain('unique');
    expect(decisionTable.textContent).toContain('Conditions (AND)');
    expect(decisionTable.textContent).toContain('Decision actions');
    expect(decisionTable.textContent).toContain('Credit score');
    expect(decisionTable.textContent).toContain('score >= 760');
    expect(decisionTable.textContent).toContain('manual_review');
    expect(query('[data-testid="showcase-decision-row:R3"]').textContent)
      .toContain('senior-underwriter');

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
    expect(query('[data-testid="showcase-run-receipt"]').textContent).toContain('request');
    expect(query('[data-testid="showcase-run-receipt"]').textContent).toContain('GET');
    expect(query('[data-testid="showcase-run-receipt"]').textContent)
      .toContain('/api/gateway/dashboard/u42');
  });

  it('keeps an unexpected run error behind a localized product conclusion', async () => {
    await renderShowcase();
    await waitFor(() => query('[data-testid="showcase-input:userId"]'));

    await setControlValue(query<HTMLInputElement>('[data-testid="showcase-input:userId"]'), 'unknown');
    await click(query<HTMLButtonElement>('[data-testid="showcase-run-button"]'));

    await waitFor(() => expect(query('[data-testid="showcase-run-result"]').textContent)
      .toContain('Gateway run failed. Review technical details.'));
    const technical = query<HTMLDetailsElement>('[data-testid="showcase-run-technical"]');
    expect(technical.open).toBe(false);
    expect(technical.textContent).toContain('Unexpected fetch');
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
    expect(query('[data-testid="showcase-expectations"]').textContent).toContain('Review expectations');
    expect(query('[data-testid="showcase-expectations"]').textContent).toContain('2/2');
    expect(query('[data-testid="showcase-expectation:ruleId"]').textContent).toContain('R3');
    expect(query('[data-testid="showcase-expectation:ruleId"]').textContent).toContain('matched');
    expect(query('[data-testid="showcase-expectation:decision"]').textContent).toContain('manual_review');
    expect(query('[data-testid="showcase-expectation:decision"]').textContent).toContain('matched');
  });

  it('marks preset expectations as missing when the response does not contain them', async () => {
    await renderShowcase();
    await waitFor(() => query('[data-testid="showcase-scenario:loanDecisionPolicy"]'));

    await click(query<HTMLButtonElement>('[data-testid="showcase-scenario:loanDecisionPolicy"]'));
    await waitFor(() => query('[data-testid="showcase-preset:Near prime"]'));
    await click(query<HTMLButtonElement>('[data-testid="showcase-preset:Near prime"]'));

    await waitFor(() =>
      expect(query('[data-testid="showcase-run-result"]').textContent)
        .toContain('/api/gateway/loan-policy/nearPrime?amount=450000'),
    );
    expect(query('[data-testid="showcase-expectations"]').textContent).toContain('Near prime expectations');
    expect(query('[data-testid="showcase-expectations"]').textContent).toContain('0/2');
    expect(query('[data-testid="showcase-expectation:ruleId"]').textContent).toContain('R2');
    expect(query('[data-testid="showcase-expectation:ruleId"]').textContent).toContain('missing');
    expect(query('[data-testid="showcase-expectation:decision"]').textContent).toContain('approved');
    expect(query('[data-testid="showcase-expectation:decision"]').textContent).toContain('missing');
  });

  it('streams SSE lanes and lets the reader stop the stream', async () => {
    vi.stubGlobal('EventSource', FakeEventSource as unknown as typeof EventSource);
    await renderShowcase();
    await waitFor(() => query('[data-testid="showcase-scenario:aiEnrichedSearch"]'));

    await click(query<HTMLButtonElement>('[data-testid="showcase-scenario:aiEnrichedSearch"]'));
    await waitFor(() => query('[data-testid="showcase-input:query"]'));
    await setControlValue(query<HTMLInputElement>('[data-testid="showcase-input:query"]'), 'risk intel');
    await click(query<HTMLButtonElement>('[data-testid="showcase-run-button"]'));

    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(1));
    const source = FakeEventSource.instances[0];
    expect(source.url).toBe('/api/gateway/ai/search/stream?q=risk%20intel');
    expect(query('[data-testid="showcase-run-receipt"]').textContent).toContain('stream');
    expect(query('[data-testid="showcase-run-receipt"]').textContent).toContain('GET');
    expect(query('[data-testid="showcase-run-receipt"]').textContent)
      .toContain('/api/gateway/ai/search/stream?q=risk%20intel');
    expect(query('[data-testid="showcase-stream-lane:meta"]').textContent).toContain('0');
    expect(query('[data-testid="showcase-stream-lane:token"]').textContent).toContain('0');
    expect(query('[data-testid="showcase-stream-lane:citation"]').textContent).toContain('0');

    await act(async () => {
      source.emit('meta', { requestId: 'm1' });
      source.emit('token', { text: 'hello' });
      source.emit('token', { text: 'world' });
      source.emit('citation', { title: 'Design note' });
    });

    await waitFor(() =>
      expect(query('[data-testid="showcase-run-result"]').textContent)
        .toContain('Design note'),
    );
    expect(query('[data-testid="showcase-stream-lane:meta"]').textContent).toContain('1');
    expect(query('[data-testid="showcase-stream-lane:token"]').textContent).toContain('2');
    expect(query('[data-testid="showcase-stream-lane:citation"]').textContent).toContain('1');

    await click(query<HTMLButtonElement>('[data-testid="showcase-stop-stream"]'));

    expect(source.closed).toBe(true);
    expect(query('[data-testid="showcase-run-result"]').textContent).toContain('Stream stopped.');
  });

  async function renderShowcase(localized = false) {
    await act(async () => {
      root = createRoot(host);
      root.render(localized ? <I18nProvider><Showcase /></I18nProvider> : <Showcase />);
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
        { label: 'Prime', values: { applicantId: 'prime' }, expected: { ruleId: 'R1', decision: 'approved' } },
        { label: 'Near prime', values: { applicantId: 'nearPrime' }, expected: { ruleId: 'R2', decision: 'approved' } },
        { label: 'Review', values: { applicantId: 'review' }, expected: { ruleId: 'R3', decision: 'manual_review' } },
        { label: 'Decline', values: { applicantId: 'decline' }, expected: { ruleId: 'R4', decision: 'declined' } },
      ],
      run: {
        mode: 'request',
        method: 'GET',
        pathTemplate: '/api/gateway/loan-policy/{applicantId}?amount={amount}',
      },
      decisionTable: {
        title: 'Loan policy matrix',
        hitPolicy: 'unique',
        inputs: [
          { key: 'score', label: 'Credit score' },
          { key: 'amount', label: 'Requested amount' },
        ],
        outputs: [
          { key: 'decision', label: 'Decision' },
          { key: 'rate', label: 'Rate' },
          { key: 'reviewLane', label: 'Review lane' },
          { key: 'ruleId', label: 'Rule' },
        ],
        rows: [
          {
            id: 'R1',
            conditions: { score: 'score >= 760', amount: 'amount <= 500000' },
            output: { decision: 'approved', rate: 3.5, reviewLane: 'auto-approve', ruleId: 'R1' },
            explanation: 'Prime applicant under jumbo threshold.',
          },
          {
            id: 'R2',
            conditions: { score: '700 <= score < 760', amount: 'amount <= 300000' },
            output: { decision: 'approved', rate: 4.5, reviewLane: 'standard', ruleId: 'R2' },
            explanation: 'Standard credit applicant within conservative amount.',
          },
          {
            id: 'R3',
            conditions: { score: '650 <= score < 700', amount: 'amount <= 200000' },
            output: { decision: 'manual_review', rate: 5.75, reviewLane: 'senior-underwriter', ruleId: 'R3' },
            explanation: 'Borderline credit requires human review.',
          },
          {
            id: 'R4',
            conditions: { score: 'otherwise', amount: 'otherwise' },
            output: { decision: 'declined', rate: 0, reviewLane: 'decline', ruleId: 'R4' },
            explanation: 'No approval policy matched.',
          },
        ],
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

class FakeEventSource {
  static instances: FakeEventSource[] = [];

  readonly url: string;
  closed = false;
  onerror: ((event: Event) => void) | null = null;
  private readonly listeners = new Map<string, Array<(event: MessageEvent) => void>>();

  constructor(url: string | URL) {
    this.url = String(url);
    FakeEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: EventListenerOrEventListenerObject) {
    const current = this.listeners.get(type) ?? [];
    const callback = typeof listener === 'function'
      ? listener as (event: MessageEvent) => void
      : (event: MessageEvent) => listener.handleEvent(event);
    this.listeners.set(type, [...current, callback]);
  }

  close() {
    this.closed = true;
  }

  emit(type: string, data: unknown) {
    const event = { data: JSON.stringify(data) } as MessageEvent;
    (this.listeners.get(type) ?? []).forEach((listener) => listener(event));
  }
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
