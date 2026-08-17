// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import I18nProvider from '../i18n/I18nProvider';
import CapabilityStudio from './CapabilityStudio';
import { parseCapabilityStudioDemoPack } from './domain';
import { capabilityStudioDemoPackFixture } from './testFixtures';

describe('Capability Studio Stage 0 read-only slice', () => {
  let host: HTMLDivElement;
  let root: Root | null = null;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
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

  it('renders all nine GP-03 rows and supports search and category filtering', async () => {
    await render();
    await act(async () => query<HTMLButtonElement>('[data-testid="capability-studio"] .capability-task-button:last-child').click());

    expect(document.querySelectorAll('.capability-scenario-table tbody tr')).toHaveLength(9);
    const search = query<HTMLInputElement>('input[placeholder="Search business scenario, owner, or expected result"]');
    await act(async () => {
      const setValue = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
      setValue?.call(search, '应停止');
      search.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: '应停止' }));
    });
    expect(document.querySelectorAll('.capability-scenario-table tbody tr')).toHaveLength(1);
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
  });

  it('rejects incomplete cardinality with a deterministic protocol error and tolerates extra fields', () => {
    expect(() => parseCapabilityStudioDemoPack({ ...capabilityStudioDemoPackFixture, extraProjectionField: { owner: 'server' }, apiCapabilities: capabilityStudioDemoPackFixture.apiCapabilities.slice(0, 3) })).toThrow('RG.CAPABILITY_STUDIO.INVALID_DEMO_PACK');
    const parsed = parseCapabilityStudioDemoPack({ ...capabilityStudioDemoPackFixture, extraProjectionField: { owner: 'server' } });
    expect(parsed.assets.apis).toHaveLength(4);
    expect(parsed.scenarios).toHaveLength(9);
  });

  async function render(fetcher = async () => new Response(JSON.stringify(capabilityStudioDemoPackFixture), { status: 200, headers: { 'Content-Type': 'application/json' } })) {
    await act(async () => {
      root = createRoot(host);
      root.render(<I18nProvider><CapabilityStudio fetcher={fetcher} /></I18nProvider>);
    });
  }
});

function query<T extends Element = Element>(selector: string): T {
  const element = document.querySelector<T>(selector);
  if (!element) throw new Error(`Missing element: ${selector}`);
  return element;
}
