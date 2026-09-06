// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { resetBlogeApiTransport, setBlogeApiTransport } from '../api';
import I18nProvider from '../i18n/I18nProvider';
import { createGuidedAuthoringTelemetry } from '../shared/guided-telemetry/guidedTelemetry';
import CorrectnessStudio, { type CorrectnessStudioApi } from './CorrectnessStudio';
import type { BusinessSolutionAssetsApi } from './api/businessSolutionApi';
import {
  deploymentCapabilities,
  envelope,
  workspaceProjection,
} from './testFixtures';

describe('CorrectnessStudio', () => {
  let host: HTMLDivElement;
  let root: Root | null;
  let api: CorrectnessStudioApi;
  let businessApi: BusinessSolutionAssetsApi;
  const capabilities = vi.fn();
  const workspace = vi.fn();
  const targets = vi.fn();
  const definitions = vi.fn();
  const telemetrySink = vi.fn();
  const golden = vi.fn();
  const goldenMaterial = vi.fn();
  const fixtures = vi.fn();
  const coverage = vi.fn();

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    window.history.replaceState({}, '', '/correctness/');
    host = document.createElement('div');
    document.body.appendChild(host);
    root = null;
    capabilities.mockReset();
    workspace.mockReset();
    targets.mockReset();
    definitions.mockReset();
    telemetrySink.mockReset();
    golden.mockReset();
    goldenMaterial.mockReset();
    fixtures.mockReset();
    coverage.mockReset();
    const preferences = new Map<string, string>();
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      value: {
        clear: () => preferences.clear(),
        getItem: (key: string) => preferences.get(key) ?? null,
        setItem: (key: string, value: string) => preferences.set(key, value),
        removeItem: (key: string) => preferences.delete(key),
        key: (index: number) => [...preferences.keys()][index] ?? null,
        get length() { return preferences.size; },
      },
    });
    window.localStorage.clear();
    api = { capabilities, workspace, targets, definitions };
    businessApi = { golden, goldenMaterial, fixtures, coverage };
  });

  afterEach(async () => {
    if (root) await act(async () => root?.unmount());
    resetBlogeApiTransport();
    host.remove();
  });

  it('probes capabilities before asking for a workspace and explains unavailable deployments', async () => {
    capabilities.mockResolvedValue(deploymentCapabilities({ correctnessWorkspaceApi: false }));
    await render();

    expect(capabilities).toHaveBeenCalledOnce();
    expect(workspace).not.toHaveBeenCalled();
    expect(host.textContent).toContain('Correctness Studio is not available');
    expect(host.textContent).toContain('does not advertise the correctness workspace API');
  });

  it('loads one exact target projection and preserves its coordinate while switching views', async () => {
    window.history.replaceState({}, '', '/correctness/?lang=en&targetKind=GRAPH&targetId=loan-decision'
      + '&targetFingerprint=sha256%3Agraph&definitionId=definition-1#oracle:approve');
    capabilities.mockResolvedValue(deploymentCapabilities());
    workspace.mockResolvedValue(envelope(workspaceProjection()));
    await render();

    expect(workspace).toHaveBeenCalledWith({
      targetKind: 'GRAPH', targetId: 'loan-decision', targetFingerprint: 'sha256:graph',
      definitionId: 'definition-1', caseCursor: undefined, caseLimit: 100,
    });
    expect(host.textContent).toContain('Loan decision correctness');
    expect(host.textContent).toContain('1. Review the verdict');
    expect(host.textContent).toContain('2. Define correctness');
    expect(host.textContent).toContain('3. Run and retain evidence');
    expect(host.textContent).toContain('Still needed');
    expect(host.textContent).toContain('Unproven');
    expect(host.textContent).toContain('no business assertion was evaluated');
    expect(host.textContent).not.toContain('Gate accepted');

    await click(button('Hide guidance'));
    expect(host.textContent).not.toContain('Still needed');
    expect(window.localStorage.getItem('bloge.correctness.guidance.collapsed.v1')).toBe('true');
    await click(button('Show guidance'));
    expect(host.textContent).toContain('Still needed');

    await click(button('Open Assertion Builder'));
    expect(host.textContent).toContain('Oracle and Assertion readiness');
    expect(window.location.search).toContain('correctnessView=oracle');

    await click(button('Cases'));

    expect(host.textContent).toContain('Eligible prime customer');
    expect(window.location.search).toContain('correctnessView=cases');
    expect(window.location.search).toContain('targetId=loan-decision');
    expect(window.location.search).toContain('lang=en');
    expect(window.location.hash).toBe('#oracle:approve');
    expect(workspace).toHaveBeenCalledTimes(1);

    await click(button('Refresh workspace'));
    expect(workspace).toHaveBeenCalledTimes(2);

    expect(telemetrySink).toHaveBeenCalledWith(expect.objectContaining({
      name: 'GUIDED_STEP_VIEWED',
      metadata: { workspace: 'CORRECTNESS', step: 'VERDICT', status: 'BLOCKED' },
    }));
    expect(telemetrySink).toHaveBeenCalledWith(expect.objectContaining({
      name: 'GUIDED_STEP_VIEWED',
      metadata: { workspace: 'CORRECTNESS', step: 'DEFINE_CORRECTNESS', status: 'BLOCKED' },
    }));
  });

  it('preserves advanced exact-coordinate recovery without guessing a demo asset', async () => {
    capabilities.mockResolvedValue(deploymentCapabilities());
    workspace.mockResolvedValue(envelope(workspaceProjection()));
    await render();

    await change(input('Target ID'), 'loan-decision');
    await change(input('Target fingerprint'), 'sha256:graph');
    await click(button('Open with exact coordinates'));

    expect(workspace).toHaveBeenCalledWith(expect.objectContaining({
      targetKind: 'GRAPH', targetId: 'loan-decision', targetFingerprint: 'sha256:graph',
    }));
    expect(window.location.search).toContain('targetKind=GRAPH');
    expect(host.textContent).toContain('Loan decision correctness');
  });

  it('renders route-local task language in Chinese without changing the exact coordinate', async () => {
    window.history.replaceState({}, '', '/correctness/?lang=zh-CN&targetKind=GRAPH'
      + '&targetId=loan-decision&targetFingerprint=sha256%3Agraph');
    capabilities.mockResolvedValue(deploymentCapabilities());
    workspace.mockResolvedValue(envelope(workspaceProjection()));
    await render();

    expect(host.textContent).toContain('正确性工作台');
    expect(host.textContent).toContain('1. 先看结论');
    expect(host.textContent).toContain('完成还差什么');
    expect(host.textContent).toContain('完成标准');
    expect(host.textContent).toContain('业务权威');
    expect(host.textContent).toContain('冻结义务');
    expect(host.textContent).toContain('未证明');
    expect(host.textContent).toContain('打开断言编辑器');
    expect(host.textContent).toContain('尚未绑定可执行断言');
    expect(host.textContent).not.toContain('OPEN_ASSERTION_BUILDER');
    expect(host.textContent).not.toContain('ASSERTION_NONE');
    expect(workspace).toHaveBeenCalledWith(expect.objectContaining({
      targetId: 'loan-decision', targetFingerprint: 'sha256:graph',
    }));

    await click(button('用例数'));
    expect(host.textContent).toContain('已批准');
    expect(host.textContent).not.toContain('APPROVED');
  });

  it('separates business assets from legacy graph correctness and loads protected Golden on demand', async () => {
    window.history.replaceState({}, '', '/correctness/?correctnessWorld=business'
      + '&solutionRef=sol%3Acancel&journeyRef=journey%3Acancel');
    golden.mockResolvedValue({
      solutionRef: 'sol:cancel', journeyRef: 'journey:cancel', caseSetRef: 'caseSet:cancel',
      revision: 4, approvalState: 'ACTIVE', cases: [{
        caseId: 'G1', lifecycle: 'ACTIVE', qualityState: 'GREEN', factCount: 2,
        assumptionCount: 1, goldenCaseFingerprint: `sha256:${'a'.repeat(64)}`,
        materialViewable: true,
      }],
    });
    fixtures.mockResolvedValue([{ capabilityKind: 'FEATURE', capabilityRef: 'feature:party',
      businessLabel: '取消责任方', fixtures: [{ fixtureAssetId: 'fixture:party', revision: 2,
        name: '乘客责任样本', variantKey: 'passenger', lifecycle: 'ACTIVE',
        classification: 'INTERNAL', schemaFingerprint: `sha256:${'b'.repeat(64)}`, usageCount: 3 }] }]);
    goldenMaterial.mockResolvedValue({ caseId: 'G1', businessIntent: '乘客超时取消由乘客承担',
      givenFacts: { 取消责任方: '乘客' }, dependencyAssumptions: [{ capability: '退款执行', behavior: 'STUB' }],
      expectedOutcome: { disposition: '维持' }, oracleOwner: '业务负责人' });
    coverage.mockResolvedValue({
      solutionRef: 'sol:cancel', inventoryId: 'solution-coverage:sol:cancel', inventoryRevision: 3,
      solutionFingerprint: `sha256:${'c'.repeat(64)}`,
      summary: { total: 3, covered: 1, uncovered: 2, highRiskUncovered: 1 },
      obligations: [
        { id: 'rule:scn:cancel:R1', obligationFingerprint: `sha256:${'d'.repeat(64)}`,
          dimension: 'RULE', risk: 'HIGH', covered: true, byCaseIds: ['G1'] },
        { id: 'otherwise:scn:cancel', obligationFingerprint: `sha256:${'e'.repeat(64)}`,
          dimension: 'OTHERWISE', risk: 'MEDIUM', covered: false, byCaseIds: [] },
        { id: 'fault:ins:refund:UNAVAILABLE', obligationFingerprint: `sha256:${'f'.repeat(64)}`,
          dimension: 'DEPENDENCY_FAULT', risk: 'HIGH', covered: false, byCaseIds: [] },
      ],
    });

    await render();

    expect(capabilities).not.toHaveBeenCalled();
    expect(host.textContent).toContain('Business Golden');
    expect(host.textContent).toContain('Business Fixtures');
    expect(host.textContent).toContain('取消责任方');
    expect(coverage).toHaveBeenCalledWith('sol:cancel');
    const coveragePanel = host.querySelector('[data-testid="business-solution-coverage"]');
    expect(coveragePanel).not.toBeNull();
    expect(coveragePanel?.textContent).toContain('1 of 3 covered');
    expect(coveragePanel?.textContent).toContain('Decision rules');
    expect(coveragePanel?.textContent).toContain('Fallback paths');
    expect(coveragePanel?.textContent).toContain('Dependency failures');
    expect(coveragePanel?.querySelectorAll('[data-coverage="covered"]')).toHaveLength(1);
    expect(coveragePanel?.querySelectorAll('[data-coverage="uncovered"]')).toHaveLength(2);
    expect(coveragePanel?.textContent).toContain('rule:scn:cancel:R1');
    expect(coveragePanel?.textContent).toContain('G1');
    expect(host.textContent).not.toContain('乘客超时取消由乘客承担');
    await click(button('Load protected data'));
    expect(goldenMaterial).toHaveBeenCalledWith('sol:cancel', 'journey:cancel', 'G1');
    expect(host.textContent).toContain('乘客超时取消由乘客承担');
    expect(host.textContent).toContain('退款执行');

    capabilities.mockResolvedValue(deploymentCapabilities({ correctnessWorkspaceApi: false }));
    await click(button('Legacy graph'));
    expect(capabilities).toHaveBeenCalledOnce();
    expect(window.location.search).toContain('correctnessWorld=legacy');
  });

  it('keeps the reviewer credential in page memory and sends it only to business human APIs', async () => {
    window.history.replaceState({}, '', '/correctness/?correctnessWorld=business'
      + '&solutionRef=sol%3Acancel&journeyRef=journey%3Acancel');
    const requests: Array<{ input: string; headers: Headers }> = [];
    setBlogeApiTransport(async (request, init) => {
      const input = String(request);
      requests.push({ input, headers: new Headers(init?.headers) });
      if (input.includes('/golden-review/')) return jsonResponse({
        solutionRef: 'sol:cancel', journeyRef: 'journey:cancel', caseSetRef: 'caseSet:cancel',
        revision: 4, approvalState: 'APPROVED', cases: [],
      });
      if (input.includes('/fixtures')) return jsonResponse([]);
      return jsonResponse({
        solutionRef: 'sol:cancel', inventoryId: 'solution-coverage:sol:cancel', inventoryRevision: 3,
        solutionFingerprint: `sha256:${'a'.repeat(64)}`, obligations: [],
        summary: { total: 0, covered: 0, uncovered: 0, highRiskUncovered: 0 },
      });
    });
    await render(null);

    await change(input('Reviewer credential'), 'reviewer-secret-token');
    await click(button('Open protected business assets'));
    await vi.waitFor(() => expect(requests).toHaveLength(3));

    expect(requests.map((request) => request.headers.get('Authorization')))
      .toEqual(['Bearer reviewer-secret-token', 'Bearer reviewer-secret-token', 'Bearer reviewer-secret-token']);
    expect(requests.map((request) => request.headers.get('X-Purpose')))
      .toEqual(['SOLUTION_GOLDEN_REVIEW', 'SOLUTION_GOLDEN_REVIEW', 'SOLUTION_GOLDEN_REVIEW']);
    expect(window.location.href).not.toContain('reviewer-secret-token');
    expect([...Array(window.localStorage.length)].map((_, index) => window.localStorage.key(index))
      .some((key) => window.localStorage.getItem(key ?? '')?.includes('reviewer-secret-token'))).toBe(false);
    expect(host.textContent).not.toContain('reviewer-secret-token');
    expect(host.querySelector('input[type="password"]')).toBeNull();
  });

  async function render(selectedBusinessApi: BusinessSolutionAssetsApi | null = businessApi) {
    await act(async () => {
      root = createRoot(host);
      root.render(
        <I18nProvider>
          <CorrectnessStudio api={api} businessApi={selectedBusinessApi ?? undefined}
            telemetry={createGuidedAuthoringTelemetry(telemetrySink)} />
        </I18nProvider>,
      );
      await Promise.resolve();
      await Promise.resolve();
    });
  }

  function input(label: string): HTMLInputElement {
    const element = [...host.querySelectorAll('label')].find((candidate) => candidate.textContent?.includes(label))
      ?.querySelector('input');
    if (!(element instanceof HTMLInputElement)) throw new Error(`Missing input: ${label}`);
    return element;
  }

  function button(label: string): HTMLButtonElement {
    const element = [...host.querySelectorAll('button')].find((candidate) => candidate.textContent?.includes(label));
    if (!(element instanceof HTMLButtonElement)) throw new Error(`Missing button: ${label}`);
    return element;
  }
});

async function click(element: HTMLElement) {
  await act(async () => {
    element.click();
    await Promise.resolve();
    await Promise.resolve();
  });
}

function jsonResponse(value: unknown): Response {
  return new Response(JSON.stringify(value), {
    status: 200, headers: { 'Content-Type': 'application/json' },
  });
}

async function change(element: HTMLInputElement, value: string) {
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
  await act(async () => {
    setter?.call(element, value);
    element.dispatchEvent(new Event('input', { bubbles: true }));
    element.dispatchEvent(new Event('change', { bubbles: true }));
  });
}
