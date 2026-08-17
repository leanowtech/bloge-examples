// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import axe, { type AxeResults } from 'axe-core';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import I18nProvider from '../i18n/I18nProvider';
import CapabilityStudio from './CapabilityStudio';
import type { CapabilityStudioFetcher } from './api';
import { parseCapabilityStudioDemoPack } from './domain';
import { capabilityStudioDemoPackFixture, featureRehearsalProjectionFixture, scenarioDatasetProjectionFixture } from './testFixtures';

describe('Capability Studio Stage 0 read-only slice', () => {
  let host: HTMLDivElement;
  let root: Root | null = null;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(() => ({
      font: '',
      measureText: (text: string) => ({ width: text.length * 8 }),
    }) as unknown as CanvasRenderingContext2D);
    host = document.createElement('div');
    document.body.appendChild(host);
    window.history.pushState({}, '', '/capabilities/?lang=en');
  });

  afterEach(async () => {
    if (root) await act(async () => root?.unmount());
    root = null;
    host.remove();
    vi.restoreAllMocks();
  });

  it('renders GP-01 counts, baseline, tutorial branch, and no technical identifiers by default', async () => {
    await render();

    expect(document.querySelector('[data-testid="capability-overview"]')).toBeTruthy();
    expect(document.body.textContent).toContain('Cancellation fee dispute handling');
    expect(document.body.textContent).toContain('4');
    expect(document.body.textContent).toContain('Canonical baseline');
    expect(document.body.textContent).toContain('Tutorial branch');
    expect(document.body.textContent).toContain('Not ready for acceptance');
    expect(document.body.textContent).not.toContain('api:order-query:v1');
    expect(document.querySelectorAll('details[open]')).toHaveLength(0);
    expect(document.body.textContent).not.toMatch(/\b(ACCEPTED|PASS)\b/);
  });

  it('opens the selected API contract instead of always showing the first API', async () => {
    await render();
    const apiButtons = document.querySelectorAll<HTMLButtonElement>('.capability-sidebar .capability-task-button');
    await act(async () => apiButtons[2].click());

    expect(document.querySelector('[data-testid="capability-contract"]')).toBeTruthy();
    expect(document.body.textContent).toContain('Fee detail lookup');
    expect(apiButtons[2].classList.contains('active')).toBe(true);
  });

  it('opens GP-02 business contract details without making technical refs the primary view', async () => {
    await render();
    await act(async () => query<HTMLButtonElement>('button.capability-asset-row').click());

    expect(document.querySelector('[data-testid="capability-contract"]')).toBeTruthy();
    expect(document.body.textContent).toContain('Inputs');
    expect(document.body.textContent).toContain('Success result');
    expect(document.body.textContent).toContain('Expected errors');
    expect(document.body.textContent).toContain('Side effects');
    expect(document.body.textContent).toContain('SLA');
    expect(document.body.textContent).toContain('Sensitivity');
    expect(document.body.textContent).toContain('Technical references (expand when needed)');
  });

  it('loads the real Dataset, supports search and category filtering, and opens business details', async () => {
    await render();
    await act(async () => buttonWithText('Scenario data').click());
    await settle();

    expect(query('[data-testid="capability-scenarios"]')).toBeTruthy();
    expect(document.querySelectorAll('.capability-scenario-list-item')).toHaveLength(9);
    expect(document.body.textContent).toContain('Cancellation fee dispute scenario dataset');
    expect(document.body.textContent).toContain('Owner coverage');
    expect(document.body.textContent).toContain('Business goal');
    expect(document.body.textContent).toContain('Isolated runtime controls');
    expect(document.body.textContent).toContain('Business correctness expectations');
    expect(document.body.textContent).toContain('Business result is checked by the Oracle');
    expect(query('[data-testid="capability-scenario-details"]').textContent).toContain('Order lookup');
    expect(document.body.textContent).not.toContain('payload');
    await act(async () => buttonWithText('Compensation history times out').click());
    expect(document.body.textContent).toContain('Stop automatic decisioning and route the case to human review.');
    expect(document.body.textContent).toContain('Exact technical references');
    expect(document.querySelectorAll('details[open]')).toHaveLength(0);
    await act(async () => buttonWithText('Duplicate fee incident regression').click());
    expect(document.body.textContent).toContain('Repeated requests must return the same governed explanation and action.');
    const search = query<HTMLInputElement>('input[placeholder="Search business scenario, owner, or expected result"]');
    await act(async () => {
      const setValue = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
      setValue?.call(search, 'Stop automatic decisioning');
      search.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: 'Stop automatic decisioning' }));
    });
    expect(document.querySelectorAll('.capability-scenario-list-item')).toHaveLength(1);
  });

  it('shows an empty filtered state without changing the Dataset', async () => {
    await render();
    await act(async () => buttonWithText('Scenario data').click());
    await settle();
    const search = query<HTMLInputElement>('input[placeholder="Search business scenario, owner, or expected result"]');
    await act(async () => {
      const setValue = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
      setValue?.call(search, 'no such business case');
      search.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: 'no such business case' }));
    });
    expect(query('[data-testid="capability-scenario-empty"]')).toBeTruthy();
    expect(document.body.textContent).toContain('No matching scenarios');
  });

  it('retries a failed Dataset request in place', async () => {
    let attempts = 0;
    const fetcher = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith('/demo-pack')) return json(capabilityStudioDemoPackFixture);
      if (url.endsWith('/scenario-dataset') && attempts++ === 0) return json({ code: 'DATASET_OFFLINE' }, 503);
      if (url.endsWith('/scenario-dataset')) return json(scenarioDatasetProjectionFixture);
      return json({ code: 'NOT_FOUND' }, 404);
    });
    await render(fetcher);
    await act(async () => buttonWithText('Scenario data').click());
    await settle();
    expect(query('[data-testid="capability-scenario-error"]')).toBeTruthy();
    expect(document.body.textContent).toContain('What happened');
    expect(document.body.textContent).toContain('Impact');
    await act(async () => buttonWithText('Retry scenario dataset').click());
    await settle();
    expect(query('[data-testid="capability-scenarios"]')).toBeTruthy();
    expect(document.querySelectorAll('.capability-scenario-list-item')).toHaveLength(9);
  });

  it('edits GP-04 as a business sentence and proves branch isolation in preflight', async () => {
    const fetcher = tutorialFetcher();
    await render(fetcher);
    await act(async () => buttonWithText('Isolated rehearsal setup').click());
    await settle();

    expect(query('[data-testid="capability-tutorial-branch"]')).toBeTruthy();
    expect(document.body.textContent).toContain('Canonical baseline');
    expect(document.body.textContent).toContain('Read-only and unchanged by this task');
    expect(document.body.textContent).toContain('without authoring mock JSON');

    const duration = query<HTMLInputElement>('input[aria-label="Timeout duration in milliseconds"]');
    await act(async () => {
      const setValue = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
      setValue?.call(duration, '4200');
      duration.dispatchEvent(new Event('input', { bubbles: true }));
      duration.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await act(async () => buttonWithText('Save and run isolated preflight').click());
    await settle();

    expect(query('[data-testid="capability-preflight-success"]')).toBeTruthy();
    expect(document.body.textContent).toContain('revision 2');
    expect(document.body.textContent).toContain('Unresolved dependencies');
    expect(document.body.textContent).toContain('Real external calls');
    expect(document.body.textContent).toContain('Blocked');
    const putCall = fetcher.mock.calls.find(([input, init]) => String(input).endsWith('/behaviors/compensation-history') && init?.method === 'PUT');
    expect(putCall).toBeTruthy();
    expect(JSON.parse(String(putCall?.[1]?.body))).toEqual({
      condition: 'WHEN_COMPENSATION_HISTORY_IS_REQUESTED',
      behavior: 'TIMEOUT',
      durationMs: 4200,
      expectedRevision: 1,
    });
  });

  it('keeps GP-04 edits and gives a recovery action on optimistic revision conflict', async () => {
    const fetcher = tutorialFetcher({ conflict: true });
    await render(fetcher);
    await act(async () => buttonWithText('Isolated rehearsal setup').click());
    await settle();
    const duration = query<HTMLInputElement>('input[aria-label="Timeout duration in milliseconds"]');
    await act(async () => {
      const setValue = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
      setValue?.call(duration, '5100');
      duration.dispatchEvent(new Event('input', { bubbles: true }));
      duration.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await act(async () => buttonWithText('Save and run isolated preflight').click());
    await settle();

    expect(query('[data-testid="capability-tutorial-error"]')).toBeTruthy();
    expect(document.body.textContent).toContain('The tutorial branch changed in another session.');
    expect(document.body.textContent).toContain('Your unsaved values are still present.');
    expect(document.body.textContent).toContain('Reload latest revision');
    expect(duration.value).toBe('5100');
    expect(document.querySelector('[data-testid="capability-preflight-success"]')).toBeNull();
  });

  it('renders GP-05/06 from one real Trace projection and reveals payload only after permission changes', async () => {
    await render();
    await act(async () => buttonWithText('Cancellation dispute feature').click());
    await settle();

    expect(query('[data-testid="capability-feature-rehearsal"]')).toBeTruthy();
    expect(query<HTMLSelectElement>('#feature-rehearsal-case').options).toHaveLength(9);
    expect(query<HTMLSelectElement>('#feature-rehearsal-case').value).toBe('case-compensation-history-timeout');
    expect(document.querySelectorAll('.feature-dag-node')).toHaveLength(6);
    expect([...document.querySelectorAll('.feature-dag-inputs .feature-dag-node h4')]
      .map((heading) => heading.textContent)).toEqual([
        'Order lookup',
        'Responsibility lookup',
        'City policy lookup',
        'Compensation history lookup',
      ]);
    expect(document.body.textContent).toContain('Timed Out');
    expect(document.body.textContent).toContain('Real calls');
    expect(document.body.textContent).toContain('0');
    expect(document.body.textContent).toContain('COMPENSATION_HISTORY_TIMEOUT');
    expect(document.body.textContent).not.toContain('DEMO-ORDER-20260818-001');

    await act(async () => buttonWithText('Payload').click());
    await settle();
    expect(document.body.textContent).toContain('DEMO-ORDER-20260818-001');
    expect(buttonWithText('Payload').getAttribute('aria-pressed')).toBe('true');
  });

  it('has no serious or critical automated accessibility violations across NFR-02 states', async () => {
    await render();
    await expectNoSevereAccessibilityViolations('overview');

    await act(async () => buttonWithText('Order lookup').click());
    await expectNoSevereAccessibilityViolations('contract');

    await act(async () => buttonWithText('Scenario data').click());
    await settle();
    await expectNoSevereAccessibilityViolations('dataset ready');

    let attempts = 0;
    const datasetErrorFetcher = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith('/demo-pack')) return json(capabilityStudioDemoPackFixture);
      if (url.endsWith('/scenario-dataset') && attempts++ === 0) return new Response('offline', { status: 503 });
      return json(scenarioDatasetProjectionFixture);
    });
    await render(datasetErrorFetcher);
    await act(async () => buttonWithText('Scenario data').click());
    await settle();
    expect(query('[data-testid="capability-scenario-error"]')).toBeTruthy();
    await expectNoSevereAccessibilityViolations('dataset error');

    await render(tutorialFetcher());
    await act(async () => buttonWithText('Isolated rehearsal setup').click());
    await settle();
    await expectNoSevereAccessibilityViolations('tutorial ready');

    await render(tutorialFetcher({ conflict: true }));
    await act(async () => buttonWithText('Isolated rehearsal setup').click());
    await settle();
    const duration = query<HTMLInputElement>('input[aria-label="Timeout duration in milliseconds"]');
    await act(async () => {
      const setValue = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
      setValue?.call(duration, '5100');
      duration.dispatchEvent(new Event('input', { bubbles: true }));
      duration.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await act(async () => buttonWithText('Save and run isolated preflight').click());
    await settle();
    expect(query('[data-testid="capability-tutorial-error"]')).toBeTruthy();
    await expectNoSevereAccessibilityViolations('tutorial conflict');

    await render();
    await act(async () => buttonWithText('Cancellation dispute feature').click());
    await settle();
    await expectNoSevereAccessibilityViolations('feature rehearsal structure');
  });

  it('shows a visible what happened / impact / retry error state', async () => {
    const fetcher = vi.fn(async () => new Response('offline', { status: 503 }));
    await render(fetcher);

    expect(query('[data-testid="capability-load-error"]')).toBeTruthy();
    expect(document.body.textContent).toContain('What happened');
    expect(document.body.textContent).toContain('Impact');
    expect(document.body.textContent).toContain('Retry loading');
    expect(document.body.textContent).toContain('RG.CAPABILITY_STUDIO.DEMO_PACK_UNAVAILABLE');
  });

  it('keeps critical labels bilingual when the explicit locale is Chinese', async () => {
    window.history.pushState({}, '', '/capabilities/?lang=zh-CN');
    await render();
    expect(document.body.textContent).toContain('能力资产');
    expect(document.body.textContent).toContain('查看订单查询契约');
    expect(document.body.textContent).toContain('场景数据');
    expect(document.body.textContent).toContain('业务接口契约');
    expect(document.body.textContent).toContain('暂不可验收');
    expect(document.body.textContent).toContain('设计已就绪，待补运行证据');
    expect(document.body.textContent).not.toContain('Next action');
    expect(document.body.textContent).not.toContain('METADATA_READY_RUNTIME_EVIDENCE_PENDING');
    expect(document.body.textContent).not.toContain('NO_GO');
    await act(async () => buttonWithText('场景数据').click());
    await settle();
    expect(document.body.textContent).toContain('场景数据中心');
    expect(document.body.textContent).toContain('质量摘要');
    expect(document.body.textContent).toContain('业务目标');
    expect(document.body.textContent).not.toContain('Quality summary');
  });

  it('rejects incomplete cardinality with a deterministic protocol error and tolerates extra fields', () => {
    expect(() => parseCapabilityStudioDemoPack({ ...capabilityStudioDemoPackFixture, extraProjectionField: { owner: 'server' }, apiCapabilities: capabilityStudioDemoPackFixture.apiCapabilities.slice(0, 3) })).toThrow('RG.CAPABILITY_STUDIO.INVALID_DEMO_PACK');
    const parsed = parseCapabilityStudioDemoPack({ ...capabilityStudioDemoPackFixture, extraProjectionField: { owner: 'server' } });
    expect(parsed.assets.apis).toHaveLength(4);
    expect(parsed.scenarios).toHaveLength(9);
  });

  async function render(fetcher: CapabilityStudioFetcher = defaultFetcher()) {
    if (root) {
      await act(async () => root?.unmount());
      root = null;
    }
    await act(async () => {
      root = createRoot(host);
      root.render(<I18nProvider><CapabilityStudio fetcher={fetcher} /></I18nProvider>);
    });
  }
});

function defaultFetcher(): CapabilityStudioFetcher {
  return async (input) => {
    const url = String(input);
    if (url.endsWith('/scenario-dataset')) return json(scenarioDatasetProjectionFixture);
    if (url.includes('/feature-rehearsal?')) {
      const query = new URL(url, 'http://capability-studio.local').searchParams;
      return json(featureRehearsalProjectionFixture(
        query.get('permission') === 'PAYLOAD_VISIBLE' ? 'PAYLOAD_VISIBLE' : 'STRUCTURE_ONLY',
        query.get('caseId') ?? 'case-compensation-history-timeout',
      ));
    }
    return json(capabilityStudioDemoPackFixture);
  };
}

const tutorialBranch = {
  branchId: 'tutorial-compensation-history-timeout',
  revision: 1,
  fingerprint: `sha256:${'1'.repeat(64)}`,
  canonicalBaselineFingerprint: `sha256:${'a'.repeat(64)}`,
  behavior: {
    dependencyId: 'api-compensation-history',
    dependencyName: 'Compensation history lookup',
    condition: 'WHEN_COMPENSATION_HISTORY_IS_REQUESTED',
    behavior: 'TIMEOUT',
    durationMs: 3000,
  },
};

function tutorialFetcher(options: { conflict?: boolean } = {}) {
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.endsWith('/demo-pack')) return json(capabilityStudioDemoPackFixture);
    if (url.endsWith('/scenario-dataset')) return json(scenarioDatasetProjectionFixture);
    if (url.endsWith('/behaviors/compensation-history') && init?.method === 'PUT') {
      if (options.conflict) return json({
        code: 'RG.CAPABILITY_STUDIO.REVISION_CONFLICT',
        whatHappened: 'The tutorial branch changed in another session.',
        impact: 'Your unsaved values are still present.',
        recoveryAction: 'Reload the latest revision before saving again.',
        field: 'expectedRevision',
      }, 409);
      const body = JSON.parse(String(init.body));
      return json({ ...tutorialBranch, revision: 2, fingerprint: `sha256:${'2'.repeat(64)}`, behavior: { ...tutorialBranch.behavior, durationMs: body.durationMs } });
    }
    if (url.endsWith('/preflight') && init?.method === 'POST') return json({
      mode: 'ISOLATED',
      unresolvedDependencies: 0,
      realExternalCallCount: 0,
      fallbackToReal: false,
      branchId: tutorialBranch.branchId,
      revision: 2,
      fingerprint: `sha256:${'2'.repeat(64)}`,
    });
    if (url.endsWith('/tutorial-branch')) return json(tutorialBranch);
    return json({ code: 'NOT_FOUND' }, 404);
  });
}

function json(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), { status, headers: { 'Content-Type': 'application/json' } });
}

async function settle() {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
}

function buttonWithText(text: string): HTMLButtonElement {
  const button = [...document.querySelectorAll<HTMLButtonElement>('button')].find((candidate) => candidate.textContent?.includes(text));
  if (!button) throw new Error(`Missing button with text: ${text}`);
  return button;
}

function query<T extends Element = Element>(selector: string): T {
  const element = document.querySelector<T>(selector);
  if (!element) throw new Error(`Missing element: ${selector}`);
  return element;
}

async function expectNoSevereAccessibilityViolations(state: string) {
  let result: AxeResults | undefined;
  await act(async () => {
    result = await axe.run(document.body);
  });
  const severe = (result as AxeResults).violations.filter((violation) =>
    violation.impact === 'serious' || violation.impact === 'critical');
  expect(severe.map((violation) => ({
    state,
    id: violation.id,
    targets: violation.nodes.map((node) => node.target),
  }))).toEqual([]);
}
