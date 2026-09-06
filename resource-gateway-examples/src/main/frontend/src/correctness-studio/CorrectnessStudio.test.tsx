// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

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
    businessApi = { golden, goldenMaterial, fixtures };
  });

  afterEach(async () => {
    if (root) await act(async () => root?.unmount());
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

    await render();

    expect(capabilities).not.toHaveBeenCalled();
    expect(host.textContent).toContain('Business Golden');
    expect(host.textContent).toContain('Business Fixtures');
    expect(host.textContent).toContain('取消责任方');
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

  async function render() {
    await act(async () => {
      root = createRoot(host);
      root.render(
        <I18nProvider>
          <CorrectnessStudio api={api} businessApi={businessApi}
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

async function change(element: HTMLInputElement, value: string) {
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
  await act(async () => {
    setter?.call(element, value);
    element.dispatchEvent(new Event('input', { bubbles: true }));
    element.dispatchEvent(new Event('change', { bubbles: true }));
  });
}
