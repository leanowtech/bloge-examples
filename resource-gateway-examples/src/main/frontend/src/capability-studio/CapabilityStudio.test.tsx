// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import axe, { type AxeResults } from 'axe-core';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import I18nProvider from '../i18n/I18nProvider';
import CapabilityStudio from './CapabilityStudio';
import type { CapabilityStudioFetcher } from './api';
import { parseCapabilityStudioDemoPack } from './domain';
import {
  capabilityStudioDemoPackFixture,
  featureRehearsalProjectionFixture,
  governedBaselineProjectionFixture,
  scenarioDatasetProjectionFixture,
} from './testFixtures';

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

  it('moves and activates mobile task tabs with standard arrow-key navigation', async () => {
    await render();
    const overview = query<HTMLButtonElement>('#capability-mobile-task-overview');
    overview.focus();

    await act(async () => overview.dispatchEvent(new KeyboardEvent('keydown', {
      key: 'ArrowRight',
      bubbles: true,
    })));

    const contract = query<HTMLButtonElement>('#capability-mobile-task-contract');
    expect(contract.getAttribute('aria-selected')).toBe('true');
    expect(document.activeElement).toBe(contract);
    expect(query('[data-testid="capability-contract"]')).toBeTruthy();

    await act(async () => contract.dispatchEvent(new KeyboardEvent('keydown', {
      key: 'ArrowRight',
      bubbles: true,
    })));
    expect(query<HTMLButtonElement>('#capability-mobile-task-scenarios').getAttribute('aria-selected')).toBe('true');
    expect(document.activeElement?.id).toBe('capability-mobile-task-scenarios');
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

  it('opens GP-09 from Scenario data, explains blocked admission, and highlights the selected impact closure', async () => {
    const fetcher = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith('/demo-pack')) return json(capabilityStudioDemoPackFixture);
      if (url.endsWith('/scenario-dataset')) return json(scenarioDatasetProjectionFixture);
      if (url.endsWith('/scenario-dataset/quality-impact')) return json(scenarioQualityImpactProjectionFixture());
      return json({ code: 'NOT_FOUND' }, 404);
    });
    await render(fetcher);
    await act(async () => buttonWithText('Scenario data').click());
    await settle();
    await act(async () => buttonWithText('Review quality & impact').click());
    await settle();

    expect(new URL(window.location.href).searchParams.get('task')).toBe('quality');
    expect(query('[data-testid="capability-quality-impact"]')).toBeTruthy();
    expect(document.body.textContent).toContain('All five coverage dimensions are 100%');
    expect(document.body.textContent).toContain('admission remains blocked');
    expect(document.body.textContent).toContain('Draft');
    expect(document.body.textContent).toContain('Orphan cases');
    expect(document.body.textContent).toContain('This view does not export request/response content');
    expect(document.body.textContent).toContain('Business content not exported');
    expect(document.body.textContent).not.toContain('PAYLOAD_NOT_EXPORTED');
    expect([...document.querySelectorAll('.capability-quality-case-item strong, .capability-quality-graph-node strong')].map((node) => node.textContent).join(' ')).not.toContain('case-standard-cancellation-fee');
    expect(document.querySelectorAll('.capability-quality-case-item')).toHaveLength(9);
    expect(document.querySelectorAll('.capability-quality-graph-node.selected').length).toBeGreaterThan(1);
    await act(async () => buttonWithText('Compensation history times out').click());
    expect(document.querySelector('.capability-quality-case-item.selected')?.textContent).toContain('Compensation history times out');
    expect(document.querySelectorAll('.capability-quality-graph-node.selected').length).toBeGreaterThan(1);
    expect(document.querySelectorAll('.capability-quality-graph-node strong').length).toBeGreaterThan(1);
  });

  it('presents a recoverable GP-09 failure without exposing protocol codes', async () => {
    let attempts = 0;
    const fetcher = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith('/demo-pack')) return json(capabilityStudioDemoPackFixture);
      if (url.endsWith('/scenario-dataset/quality-impact') && attempts++ === 0) return json({
        code: 'RG.CAPABILITY_STUDIO.QUALITY_IMPACT_TEMPORARILY_UNAVAILABLE',
        whatHappened: 'The quality projection service is temporarily unavailable.',
        impact: 'The quality projection was not loaded or changed.',
        recoveryAction: 'Retry quality and impact.',
      }, 503);
      if (url.endsWith('/scenario-dataset/quality-impact')) return json(scenarioQualityImpactProjectionFixture());
      return json({ code: 'NOT_FOUND' }, 404);
    });
    await render(fetcher);
    await act(async () => buttonWithText('Quality & impact').click());
    await settle();

    expect(query('[data-testid="capability-quality-impact-error"]')).toBeTruthy();
    expect(document.body.textContent).toContain('Business quality check');
    expect(document.body.textContent).toContain('Quality & impact is unavailable');
    expect(document.body.textContent).toContain('What happened');
    expect(document.body.textContent).toContain('Impact');
    expect(document.body.textContent).toContain('How to continue');
    expect(document.body.textContent).toContain('Retry quality & impact');
    expect(document.body.textContent).not.toContain('RG.CAPABILITY_STUDIO.');
    await act(async () => buttonWithText('Retry quality & impact').click());
    await settle();
    expect(query('[data-testid="capability-quality-impact"]')).toBeTruthy();
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
      if (url.endsWith('/scenario-dataset') && attempts++ === 0) return json({
        code: 'RG.CAPABILITY_STUDIO.SCENARIO_DATASET_UNAVAILABLE',
        whatHappened: 'RG.CAPABILITY_STUDIO.SCENARIO_DATASET_UNAVAILABLE: internal response failure',
        impact: 'RG.CAPABILITY_STUDIO.INTERNAL_IMPACT',
        recoveryAction: 'RG.CAPABILITY_STUDIO.INTERNAL_RECOVERY',
      }, 503);
      if (url.endsWith('/scenario-dataset')) return json(scenarioDatasetProjectionFixture);
      return json({ code: 'NOT_FOUND' }, 404);
    });
    await render(fetcher);
    await act(async () => buttonWithText('Scenario data').click());
    await settle();
    expect(query('[data-testid="capability-scenario-error"]')).toBeTruthy();
    expect(document.body.textContent).toContain('What happened');
    expect(document.body.textContent).toContain('Impact');
    expect(document.body.textContent).toContain('How to continue');
    expect(document.body.textContent).toContain('Retry scenario dataset');
    expect(document.body.textContent).not.toContain('RG.CAPABILITY_STUDIO.');
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
    expect(document.body.textContent).toContain('What happened');
    expect(document.body.textContent).toContain('Current impact');
    expect(document.body.textContent).toContain('Recovery action');
    expect(document.body.textContent).toContain('A newer version is available');
    expect(document.body.textContent).toContain('Reload latest revision');
    expect(document.body.textContent).not.toContain('RG.CAPABILITY_STUDIO.');
    expect(duration.value).toBe('5100');
    expect(document.querySelector('[data-testid="capability-preflight-success"]')).toBeNull();
  });

  it('presents a generic tutorial operation failure as a safe validation outcome', async () => {
    const fetcher = tutorialFetcher({ invalidPreflight: true });
    await render(fetcher);
    await act(async () => buttonWithText('Isolated rehearsal setup').click());
    await settle();
    await act(async () => buttonWithText('Save and run isolated preflight').click());
    await settle();

    expect(query('[data-testid="capability-tutorial-error"]')).toBeTruthy();
    expect(document.body.textContent).toContain('What happened');
    expect(document.body.textContent).toContain('Current impact');
    expect(document.body.textContent).toContain('Recovery action');
    expect(document.body.textContent).toContain('Data validation failed');
    expect(document.body.textContent).toContain('Reload tutorial branch');
    expect(document.body.textContent).not.toContain('RG.CAPABILITY_STUDIO.');
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

  it('issues one ordinary rehearsal request per scenario or permission action', async () => {
    let rehearsalCalls = 0;
    const fetcher = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith('/demo-pack')) return json(capabilityStudioDemoPackFixture);
      if (url.includes('/feature-rehearsal?')) {
        rehearsalCalls += 1;
        const query = new URL(url, 'http://capability-studio.local').searchParams;
        return json(featureRehearsalProjectionFixture(
          query.get('permission') === 'PAYLOAD_VISIBLE' ? 'PAYLOAD_VISIBLE' : 'STRUCTURE_ONLY',
          query.get('caseId') ?? 'case-compensation-history-timeout',
        ));
      }
      return json({ code: 'NOT_FOUND' }, 404);
    });

    await render(fetcher);
    await act(async () => buttonWithText('Cancellation dispute feature').click());
    await settle();
    expect(rehearsalCalls).toBe(1);

    const scenario = query<HTMLSelectElement>('#feature-rehearsal-case');
    await act(async () => {
      const setValue = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')?.set;
      setValue?.call(scenario, 'case-standard-cancellation-fee');
      scenario.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await settle();
    expect(rehearsalCalls).toBe(2);

    await act(async () => buttonWithText('Payload').click());
    await settle();
    expect(rehearsalCalls).toBe(3);
  });

  it('keeps authorization impact and recovery visible when Data Lens access is denied', async () => {
    window.history.pushState({}, '', '/capabilities/?lang=zh-CN');
    const fetcher = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith('/demo-pack')) return json(capabilityStudioDemoPackFixture);
      if (url.includes('/feature-rehearsal?')) {
        const permission = new URL(url, 'http://capability-studio.local')
          .searchParams.get('permission');
        if (permission === 'PAYLOAD_VISIBLE') return json({
          schemaVersion: 'toolStudio.resourceGateway.problem.v1',
          title: 'The verified identity cannot view this Data Lens.',
          status: 403,
          code: 'RG.CAPABILITY_STUDIO.PAYLOAD_CLEARANCE_REQUIRED',
          details: {
            requiredClearance: 'CONFIDENTIAL',
          },
        }, 403);
        return json(featureRehearsalProjectionFixture('STRUCTURE_ONLY'));
      }
      return json({ code: 'NOT_FOUND' }, 404);
    });

    await render(fetcher);
    await act(async () => buttonWithText('取消费争议特征').click());
    await settle();
    await act(async () => buttonWithText('受控数据').click());
    await settle();

    expect(document.body.textContent).toContain(
      '已验证身份不具备查看受控数据所需的 CONFIDENTIAL 权限。',
    );
    expect(document.body.textContent).toContain(
      '现有结构视图继续可见且保持不变。',
    );
    expect(document.body.textContent).toContain(
      '保持使用结构视图，或重新连接具备 CONFIDENTIAL 权限的身份后重试。',
    );
    expect(buttonWithText('结构').getAttribute('aria-pressed')).toBe('true');
    expect(buttonWithText('受控数据').getAttribute('aria-pressed')).toBe('false');
    expect(document.querySelectorAll('.feature-dag-node')).toHaveLength(6);
    expect(document.body.textContent).not.toContain('DEMO-ORDER-20260818-001');
  });

  it('runs GP-07/08 through the governed endpoint and keeps release acceptance visibly closed', async () => {
    const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith('/demo-pack')) return json(capabilityStudioDemoPackFixture);
      if (url.endsWith('/governed-baseline') && init?.method === 'POST') {
        return json(governedBaselineProjectionFixture);
      }
      return json({ code: 'NOT_FOUND' }, 404);
    });
    await render(fetcher);
    await act(async () => query<HTMLButtonElement>('[data-testid="capability-task-tool"]').click());

    expect(query('[data-testid="capability-tool"]')).toBeTruthy();
    expect(document.body.textContent).toContain('9');
    expect(document.body.textContent).toContain('27');
    expect(document.body.textContent).toContain('0');
    expect(document.body.textContent).toContain('The canonical baseline remains unchanged');

    await act(async () => query<HTMLButtonElement>('[data-testid="run-governed-baseline"]').click());
    await settle();

    expect(fetcher).toHaveBeenCalledWith(
      '/api/capability-studio/governed-baseline',
      expect.objectContaining({ method: 'POST' }),
    );
    expect(query('[data-testid="governed-baseline-result"]')).toBeTruthy();
    expect(query('[data-testid="capability-summary-status"]').textContent).toContain('DEVELOPMENT VERIFIED');
    expect(document.body.textContent).not.toContain('Design ready, runtime evidence pending');
    expect(document.body.textContent).toContain('All 27 business checks passed');
    expect(document.body.textContent).toContain('certifiable runtime evidence');
    expect(document.body.textContent).toContain('candidate build bound');
    expect(document.body.textContent).toContain('Still not accepted');
    expect(document.body.textContent).toContain('27 / 27');
    expect(document.querySelectorAll('.capability-governed-case-table tbody tr')).toHaveLength(9);
    expect(document.body.textContent).toContain('Business Oracles');
    expect(document.body.textContent).toContain('Stable result');
    expect(document.body.textContent).toContain('Timeout fallback and safe continuation confirmed');
    expect(document.body.textContent).toContain('Distinct runs produced the same business result');
    expect(document.body.textContent).toContain('No write operator, write trace, or real call observed');
    expect(document.querySelectorAll('.capability-governed-case-table tbody td')).toHaveLength(36);
    expect(document.body.textContent).toContain('Deployment-level network denial');
    expect(document.body.textContent).not.toContain('exploratory, not certifiable');
    expect(document.body.textContent).not.toContain('not bound to an immutable release candidate');
    expect(document.body.textContent).not.toContain('NO_GO');
    expect(document.body.textContent).not.toContain('DEVELOPMENT_TEST_OWNED');
    expect(document.querySelector('.capability-governed-result details[open]')).toBeNull();
  });

  it('explains a governed run failure and offers an in-place retry without stale green evidence', async () => {
    let attempts = 0;
    const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith('/demo-pack')) return json(capabilityStudioDemoPackFixture);
      if (url.endsWith('/governed-baseline') && init?.method === 'POST' && attempts++ === 0) {
        return json({
          code: 'RG.CAPABILITY_STUDIO.GOVERNED_BASELINE_FAILED',
          whatHappened: 'The governed suite could not establish complete evidence.',
          impact: 'No new development verification was established; existing assets are unchanged.',
          recoveryAction: 'Review the diagnostic and run the governed baseline again.',
        }, 503);
      }
      if (url.endsWith('/governed-baseline') && init?.method === 'POST') {
        return json(governedBaselineProjectionFixture);
      }
      return json({ code: 'NOT_FOUND' }, 404);
    });
    await render(fetcher);
    await act(async () => query<HTMLButtonElement>('[data-testid="capability-task-tool"]').click());
    await act(async () => query<HTMLButtonElement>('[data-testid="run-governed-baseline"]').click());
    await settle();

    expect(document.body.textContent).toContain('The governed verification did not complete');
    expect(document.body.textContent).toContain('What happened');
    expect(document.body.textContent).toContain('Impact');
    expect(document.body.textContent).toContain('Recovery');
    expect(document.querySelector('[data-testid="governed-baseline-result"]')).toBeNull();
    expect(query('[data-testid="capability-summary-status"]').textContent).toContain('RUN FAILED · RETRY AVAILABLE');
    expect(document.body.textContent).not.toContain('Design ready, runtime evidence pending');

    await act(async () => buttonWithText('Run again').click());
    await settle();
    expect(query('[data-testid="governed-baseline-result"]')).toBeTruthy();
    expect(query('[data-testid="capability-summary-status"]').textContent).toContain('DEVELOPMENT VERIFIED');
  });

  it('reads matrix evidence exactly once, keeps the run/case/node through graph and return navigation, and clears it on exit', async () => {
    const expectedRunId = governedBaselineProjectionFixture.cases.find((entry) => entry.caseId === 'case-standard-cancellation-fee')?.rounds[0].runId;
    if (!expectedRunId) throw new Error('Missing standard cancellation fee run fixture.');
    let evidenceCalls = 0;
    let featureCalls = 0;
    let baselinePosts = 0;
    const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith('/demo-pack')) return json(capabilityStudioDemoPackFixture);
      if (url.endsWith('/governed-baseline') && init?.method === 'POST') {
        baselinePosts += 1;
        return json(governedBaselineProjectionFixture);
      }
      if (url.includes('/governed-runs/') && url.endsWith('/evidence?expectedCaseId=case-standard-cancellation-fee')) {
        evidenceCalls += 1;
        const runId = decodeURIComponent(url.split('/governed-runs/')[1].split('/evidence')[0]);
        return json(governedRunEvidencePayload(runId, 'case-standard-cancellation-fee'));
      }
      if (url.includes('/feature-rehearsal?')) {
        featureCalls += 1;
        return json(featureRehearsalProjectionFixture());
      }
      return json({ code: 'NOT_FOUND' }, 404);
    });

    await render(fetcher);
    await act(async () => query<HTMLButtonElement>('[data-testid="capability-task-tool"]').click());
    await act(async () => query<HTMLButtonElement>('[data-testid="run-governed-baseline"]').click());
    await settle();

    const pushState = vi.spyOn(window.history, 'pushState');
    const replaceState = vi.spyOn(window.history, 'replaceState');
    await act(async () => query<HTMLButtonElement>('[data-testid="governed-evidence-case-standard-cancellation-fee-1"]').click());
    await settle();

    expect(evidenceCalls).toBe(1);
    expect(featureCalls).toBe(0);
    expect(baselinePosts).toBe(1);
    expect(pushState).toHaveBeenCalledTimes(1);
    expect(replaceState).toHaveBeenCalledTimes(1);
    const evidenceUrl = new URL(window.location.href);
    expect(evidenceUrl.searchParams.get('task')).toBe('tool');
    expect(evidenceUrl.searchParams.get('runId')).toBe(expectedRunId);
    expect(evidenceUrl.searchParams.get('scenarioId')).toBe('case-standard-cancellation-fee');
    expect(evidenceUrl.searchParams.get('nodeId')).toBe('compensationHistoryLookup');
    expect(query('[data-testid="governed-run-evidence-panel"]')).toBeTruthy();

    await act(async () => query<HTMLButtonElement>('[data-testid="governed-evidence-case-standard-cancellation-fee-1"]').click());
    await settle();
    expect(evidenceCalls).toBe(2);
    expect(featureCalls).toBe(0);
    expect(baselinePosts).toBe(1);

    await act(async () => buttonWithText("Back to this run's orchestration graph").click());
    await settle();
    const graphUrl = new URL(window.location.href);
    expect(graphUrl.searchParams.get('task')).toBe('feature');
    expect(graphUrl.searchParams.get('runId')).toBe(expectedRunId);
    expect(graphUrl.searchParams.get('scenarioId')).toBe('case-standard-cancellation-fee');
    expect(graphUrl.searchParams.get('nodeId')).toBe('compensationHistoryLookup');
    expect(query('[data-testid="capability-feature-rehearsal"]')).toBeTruthy();
    expect(document.querySelector('#feature-rehearsal-case')).toBeNull();
    expect([...document.querySelectorAll('button')].some((button) => button.textContent?.includes('Payload'))).toBe(false);
    expect(document.querySelector('.capability-segmented-control')).toBeNull();
    const exactDag = query('[data-testid="feature-dag"]');
    expect(exactDag.querySelectorAll('.feature-dag-node')).toHaveLength(6);
    expect(exactDag.querySelector('[data-node-id="subject"]')).toBeNull();
    expect(exactDag.querySelectorAll('.feature-node-decision')).toHaveLength(1);
    expect(query('[data-node-id="compensationHistoryLookup"]').classList.contains('feature-node-focus')).toBe(true);
    expect(document.activeElement).toBe(query('[data-node-id="compensationHistoryLookup"]'));
    const exactLensNodes = document.querySelectorAll('.capability-lens-node-list .feature-lens-row');
    expect(exactLensNodes).toHaveLength(7);
    expect([...exactLensNodes].some((row) => row.querySelector('strong')?.textContent === 'subject')).toBe(true);
    expect(evidenceCalls).toBe(2);
    expect(featureCalls).toBe(0);
    expect(baselinePosts).toBe(1);

    await act(async () => buttonWithText('Return to Tool evidence').click());
    await settle();
    const returnUrl = new URL(window.location.href);
    expect(returnUrl.searchParams.get('task')).toBe('tool');
    expect(returnUrl.searchParams.get('runId')).toBe(expectedRunId);
    expect(returnUrl.searchParams.get('scenarioId')).toBe('case-standard-cancellation-fee');
    expect(returnUrl.searchParams.get('nodeId')).toBe('compensationHistoryLookup');
    expect(query('[data-testid="governed-run-evidence-panel"]')).toBeTruthy();

    await act(async () => buttonWithText('Overview').click());
    const clearedUrl = new URL(window.location.href);
    expect(clearedUrl.searchParams.get('runId')).toBeNull();
    expect(clearedUrl.searchParams.get('scenarioId')).toBeNull();
    expect(clearedUrl.searchParams.get('nodeId')).toBeNull();
  });

  it('initializes an exact Feature URL from the same run without ordinary rehearsal or POST', async () => {
    window.history.pushState({}, '', '/capabilities/?task=feature&runId=child-run-1-1&scenarioId=case-standard-cancellation-fee');
    let evidenceCalls = 0;
    let featureCalls = 0;
    let postCalls = 0;
    const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith('/demo-pack')) return json(capabilityStudioDemoPackFixture);
      if (url.includes('/governed-runs/') && url.includes('/evidence?')) {
        evidenceCalls += 1;
        return json(governedRunEvidencePayload('child-run-1-1', 'case-standard-cancellation-fee'));
      }
      if (url.includes('/feature-rehearsal?')) {
        featureCalls += 1;
        return json(featureRehearsalProjectionFixture());
      }
      if (init?.method === 'POST') postCalls += 1;
      return json({ code: 'NOT_FOUND' }, 404);
    });

    await render(fetcher);
    await settle();

    expect(evidenceCalls).toBe(1);
    expect(featureCalls).toBe(0);
    expect(postCalls).toBe(0);
    expect(document.querySelector('#feature-rehearsal-case')).toBeNull();
    expect(document.querySelector('.capability-segmented-control')).toBeNull();
    expect(query('[data-node-id="compensationHistoryLookup"]').classList.contains('feature-node-focus')).toBe(true);
    expect(new URL(window.location.href).searchParams.get('nodeId')).toBe('compensationHistoryLookup');
  });

  it('does not treat stale run parameters under a non-exact task as an exact request', async () => {
    window.history.pushState({}, '', '/capabilities/?task=overview&runId=stale-run&scenarioId=case-standard-cancellation-fee');
    let evidenceCalls = 0;
    const fetcher = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith('/demo-pack')) return json(capabilityStudioDemoPackFixture);
      if (url.includes('/governed-runs/')) evidenceCalls += 1;
      return json({ code: 'NOT_FOUND' }, 404);
    });

    await render(fetcher);
    await settle();

    expect(evidenceCalls).toBe(0);
    expect(document.querySelector('[data-testid="capability-overview"]')).toBeTruthy();
  });

  it('recovers an exact Feature error with only the exact GET or return-to-Tool action', async () => {
    window.history.pushState({}, '', '/capabilities/?task=feature&runId=child-run-1-1&scenarioId=case-standard-cancellation-fee');
    let evidenceCalls = 0;
    let featureCalls = 0;
    const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith('/demo-pack')) return json(capabilityStudioDemoPackFixture);
      if (url.includes('/governed-runs/') && url.includes('/evidence?')) {
        evidenceCalls += 1;
        if (evidenceCalls === 1) return json({ code: 'RG.CAPABILITY_STUDIO.EVIDENCE_UNAVAILABLE', recoveryAction: 'Retry exact evidence.' }, 503);
        return json(governedRunEvidencePayload('child-run-1-1', 'case-standard-cancellation-fee'));
      }
      if (url.includes('/feature-rehearsal?')) {
        featureCalls += 1;
        return json(featureRehearsalProjectionFixture());
      }
      if (init?.method === 'POST') throw new Error('exact Feature must not POST');
      return json({ code: 'NOT_FOUND' }, 404);
    });

    await render(fetcher);
    await settle();
    expect(query('.capability-feature-error')).toBeTruthy();
    expect(document.querySelector('#feature-rehearsal-case')).toBeNull();

    await act(async () => buttonWithText('Return to Tool evidence').click());
    await settle();
    expect(new URL(window.location.href).searchParams.get('task')).toBe('tool');
    expect(new URL(window.location.href).searchParams.get('runId')).toBe('child-run-1-1');
    expect(new URL(window.location.href).searchParams.get('scenarioId')).toBe('case-standard-cancellation-fee');
    expect(query('[data-testid="governed-run-evidence-error"]')).toBeTruthy();
    expect(evidenceCalls).toBe(1);

    await act(async () => buttonWithText('Retry exact evidence').click());
    await settle();

    expect(evidenceCalls).toBe(2);
    expect(featureCalls).toBe(0);
    expect(query('[data-testid="governed-run-evidence-panel"]')).toBeTruthy();
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
    expect(document.body.textContent).toContain('Service temporarily unavailable');
    expect(document.body.textContent).toContain('How to continue');
    expect(document.body.textContent).not.toContain('RG.CAPABILITY_STUDIO.');
    expect(document.querySelector('[data-testid="capability-load-error"] details')).toBeNull();
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
    if (url.endsWith('/scenario-dataset/quality-impact')) return json(scenarioQualityImpactProjectionFixture());
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

function scenarioQualityImpactProjectionFixture() {
  const dataset = structuredClone(scenarioDatasetProjectionFixture);
  const nodes = new Map<string, { id: string; kind: string; label: string; ref: typeof dataset.datasetRef; status: string }>();
  const edges: Array<{ id: string; source: string; target: string; relation: string }> = [];
  const compare = (left: string, right: string) => left < right ? -1 : left > right ? 1 : 0;
  const key = (ref: typeof dataset.datasetRef) => `${ref.kind}|${ref.id}|${ref.revision}|${ref.fingerprint}|${ref.authority}`;
  const addNode = (kind: string, ref: typeof dataset.datasetRef, label: string, status: string) => nodes.set(key(ref), { id: `${kind}:${ref.id}`, kind, label, ref, status });
  addNode('DATASET', dataset.datasetRef, dataset.name, 'BLOCKED');
  addNode('TARGET', dataset.targetRef, 'Cancellation resolution tool', 'DRAFT');
  dataset.cases.forEach((scenario) => {
    addNode('DATA_CASE', scenario.caseRef, scenario.name, scenario.lifecycle);
    if (scenario.sourceRef && scenario.source) addNode('SOURCE', scenario.sourceRef, scenario.source.displayName, 'BLOCKED');
    if (scenario.oracleRef && scenario.oracle) addNode('ORACLE', scenario.oracleRef, scenario.oracle.displayName, 'DRAFT');
    scenario.applicableContractRefs.forEach((ref) => addNode('CONTRACT', ref, `Contract ${ref.id}`, 'DRAFT'));
    scenario.behaviorProfiles.filter((profile) => profile.purpose === 'RUNTIME_CONTROL').forEach((profile) => addNode('DEPENDENCY', profile.dependencyRef, `Dependency ${profile.dependencyRef.id}`, 'DRAFT'));
  });
  const nodeId = (ref: typeof dataset.datasetRef, kind: string) => nodes.get(key(ref))?.id ?? `${kind}:${ref.id}`;
  dataset.cases.forEach((scenario) => {
    const caseNode = nodeId(scenario.caseRef, 'DATA_CASE');
    edges.push({ id: `edge-${caseNode}-dataset`, source: nodes.get(key(dataset.datasetRef))!.id, target: caseNode, relation: 'CONTAINS' });
    if (scenario.sourceRef) edges.push({ id: `edge-${caseNode}-source-${scenario.sourceRef.id}`, source: caseNode, target: nodeId(scenario.sourceRef, 'SOURCE'), relation: 'SOURCED_BY' });
    if (scenario.oracleRef) edges.push({ id: `edge-${caseNode}-oracle-${scenario.oracleRef.id}`, source: caseNode, target: nodeId(scenario.oracleRef, 'ORACLE'), relation: 'CHECKED_BY' });
    scenario.applicableContractRefs.forEach((ref) => edges.push({ id: `edge-${caseNode}-contract-${ref.id}`, source: caseNode, target: nodeId(ref, 'CONTRACT'), relation: 'VALIDATES' }));
    scenario.behaviorProfiles.filter((profile) => profile.purpose === 'RUNTIME_CONTROL').forEach((profile) => edges.push({ id: `edge-${caseNode}-dependency-${profile.dependencyRef.id}`, source: caseNode, target: nodeId(profile.dependencyRef, 'DEPENDENCY'), relation: 'CONTROLS' }));
    edges.push({ id: `edge-${caseNode}-target`, source: caseNode, target: nodeId(dataset.targetRef, 'TARGET'), relation: 'VALIDATES_TARGET' });
  });
  const cases = dataset.cases.map((scenario) => {
    const dependencyRefs = scenario.behaviorProfiles.filter((profile) => profile.purpose === 'RUNTIME_CONTROL').map((profile) => profile.dependencyRef).sort((a, b) => key(a).localeCompare(key(b)));
    const impacted = new Set([...scenario.applicableContractRefs.map(key), ...dependencyRefs.map(key), key(dataset.targetRef)]);
    return { caseRef: scenario.caseRef, name: scenario.name, lifecycle: scenario.lifecycle, qualityState: scenario.qualityState, owner: scenario.owner, sourceRef: scenario.sourceRef, source: scenario.source, oracleRef: scenario.oracleRef, oracle: scenario.oracle, contractRefs: scenario.applicableContractRefs, dependencyRefs, freshnessStatus: 'UNVERIFIED', maskingStatus: 'PAYLOAD_NOT_EXPORTED', impactedAssetCount: impacted.size };
  });
  const sortedNodes = [...nodes.values()].sort((a, b) => compare(a.kind, b.kind) || compare(a.id, b.id));
  const sortedEdges = edges.sort((a, b) => compare(a.source, b.source) || compare(a.target, b.target) || compare(a.relation, b.relation) || compare(a.id, b.id));
  return {
    schemaVersion: 'resource-gateway.capability-studio.scenario-quality-impact.v1', datasetRef: dataset.datasetRef, targetRef: dataset.targetRef, projectionFingerprint: `sha256:${'e'.repeat(64)}`,
    admission: { status: 'BLOCKED', activeCaseCount: dataset.cases.filter((scenario) => scenario.lifecycle === 'ACTIVE').length, draftCaseCount: dataset.cases.filter((scenario) => scenario.lifecycle === 'DRAFT').length, staleCaseCount: dataset.cases.filter((scenario) => (scenario.lifecycle as string) === 'STALE').length, blockers: [{ code: 'FRESHNESS_EVIDENCE_MISSING', message: 'Add freshness evidence before admission.' }, { code: 'NO_ACTIVE_CASES', message: 'Activate at least one case before admission.' }] },
    quality: { status: 'BLOCKED', ownerCoveragePercent: dataset.quality.ownerCoveragePercent, sourceCoveragePercent: dataset.quality.sourceCoveragePercent, oracleCoveragePercent: dataset.quality.oracleCoveragePercent, contractCoveragePercent: dataset.quality.contractCoveragePercent, behaviorClosurePercent: dataset.quality.behaviorClosurePercent, freshnessStatus: 'UNVERIFIED', payloadExposure: 'NONE', maskingStatus: 'PAYLOAD_NOT_EXPORTED' },
    summary: { caseCount: cases.length, sourceCount: sortedNodes.filter((node) => node.kind === 'SOURCE').length, oracleCount: sortedNodes.filter((node) => node.kind === 'ORACLE').length, contractCount: sortedNodes.filter((node) => node.kind === 'CONTRACT').length, dependencyCount: sortedNodes.filter((node) => node.kind === 'DEPENDENCY').length, targetCount: 1, impactedAssetCount: sortedNodes.filter((node) => node.kind === 'CONTRACT' || node.kind === 'DEPENDENCY' || node.kind === 'TARGET').length, orphanCaseCount: 0 },
    cases, impactGraph: { nodes: sortedNodes, edges: sortedEdges },
  };
}

function governedRunEvidencePayload(runId: string, caseId: string) {
  const dataLens = structuredClone(featureRehearsalProjectionFixture().dataLens);
  dataLens.runId = runId;
  const featureGraphPath = '/root/subject/feature-cancellation-dispute-context';
  dataLens.nodes.forEach((node) => {
    node.graphPath = featureGraphPath;
    node.invocationSite = `${featureGraphPath}/${node.nodeId}#PRIMARY`;
  });
  dataLens.edges.forEach((edge) => {
    edge.graphPath = featureGraphPath;
    edge.edgeId = `${featureGraphPath}/${edge.edgeId}`;
    edge.fromInvocationSite = edge.fromInvocationSite.replace('/root/', `${featureGraphPath}/`);
    edge.toInvocationSite = edge.toInvocationSite.replace('/root/', `${featureGraphPath}/`);
  });
  dataLens.nodes.unshift({
    ...structuredClone(dataLens.nodes[dataLens.nodes.length - 1]),
    nodeId: 'subject',
    operatorRef: 'tool-cancellation-resolution',
    graphPath: '/root',
    invocationSite: '/root/subject#PRIMARY',
  });
  const ref = (kind: string, id: string, seed: string) => ({ kind, id, revision: 1, fingerprint: `sha256:${seed.repeat(64).slice(0, 64)}` });
  const caseRef = ref('DATA_CASE', caseId, '1');
  const contractRef = ref('CONTRACT', 'contract-cancellation-fee', '2');
  return {
    schemaVersion: 'resource-gateway.capability-studio.governed-run-evidence.v1', verificationStatus: 'EXACT_VERIFIED', baselineId: 'capability-studio-governed-9x3-v1', projectionFingerprint: `sha256:${'3'.repeat(64)}`,
    scenario: { caseId, name: 'Standard cancellation fee', businessIntent: 'Return an explainable fee decision.', category: 'GOLDEN', lifecycle: 'ACTIVE', qualityState: 'READY', owner: { id: 'customer-service-platform', name: 'Customer Service Platform' }, scenarioRef: ref('SCENARIO', caseId, '4'), caseRef, sourceRef: ref('SOURCE', 'source-cancellation-fee', '5'), oracleRef: ref('ORACLE', 'oracle-cancellation-fee', '6'), applicableContractRefs: [contractRef] },
    graphRef: ref('FEATURE', 'feature-cancellation-dispute-context', '7'), capabilityRef: ref('TOOL', 'tool-cancellation-resolution', '8'), contractRef, datasetRef: ref('DATASET', 'cancellation-fee-scenarios', '9'), caseRef,
    runtimeTarget: { kind: 'OPERATOR', id: 'tool-cancellation-resolution', fingerprint: `sha256:${'a'.repeat(64)}` },
    bindingPlan: { ref: ref('BINDING_PLAN', 'binding-cancellation-fee', 'b'), fixtureBundleRef: ref('FIXTURE_BUNDLE', 'fixture-cancellation-fee', 'c'), effectiveExecutionPlanFingerprint: `sha256:${'d'.repeat(64)}`, behaviorRefs: [ref('BEHAVIOR_PROFILE', 'behavior-cancellation-fee', 'e')], dependencyRefs: [ref('API', 'api-order-lookup', 'f')], fallbackToReal: false, sourceMapFingerprint: `sha256:${'1'.repeat(64)}`, provenanceFingerprint: `sha256:${'2'.repeat(64)}` },
    run: { runId, status: 'TIMED_OUT', evidenceClass: 'CERTIFIABLE', evidenceFingerprint: `sha256:${'4'.repeat(64)}`, semanticResultFingerprint: `sha256:${'5'.repeat(64)}`, assertionsEvaluated: 1, assertionsPassed: 1, fixtureControlsEvaluated: 1, fixtureControlsSatisfied: 1 },
    focusNodeId: 'compensationHistoryLookup', dataLens,
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

function tutorialFetcher(options: { conflict?: boolean; invalidPreflight?: boolean } = {}) {
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
    if (url.endsWith('/preflight') && init?.method === 'POST' && options.invalidPreflight) return json({
      code: 'RG.CAPABILITY_STUDIO.INVALID_PREFLIGHT_RESPONSE',
      message: 'RG.CAPABILITY_STUDIO.INVALID_PREFLIGHT_RESPONSE: expected a valid preflight projection',
    });
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
