// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import I18nProvider from '../i18n/I18nProvider';
import CorrectnessStudio, { type CorrectnessStudioApi } from './CorrectnessStudio';
import {
  deploymentCapabilities,
  envelope,
  workspaceProjection,
} from './testFixtures';

describe('CorrectnessStudio', () => {
  let host: HTMLDivElement;
  let root: Root | null;
  let api: CorrectnessStudioApi;
  const capabilities = vi.fn();
  const workspace = vi.fn();

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    window.history.replaceState({}, '', '/correctness/');
    host = document.createElement('div');
    document.body.appendChild(host);
    root = null;
    capabilities.mockReset();
    workspace.mockReset();
    api = { capabilities, workspace };
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
    expect(host.textContent).toContain('Unproven');
    expect(host.textContent).toContain('no business assertion was evaluated');
    expect(host.textContent).not.toContain('Gate accepted');

    await click(button('Cases'));

    expect(host.textContent).toContain('Eligible prime customer');
    expect(window.location.search).toContain('correctnessView=cases');
    expect(window.location.search).toContain('targetId=loan-decision');
    expect(window.location.search).toContain('lang=en');
    expect(window.location.hash).toBe('#oracle:approve');
    expect(workspace).toHaveBeenCalledTimes(1);

    await click(button('Refresh workspace'));
    expect(workspace).toHaveBeenCalledTimes(2);
  });

  it('opens an exact target from the coordinate connector without guessing a demo asset', async () => {
    capabilities.mockResolvedValue(deploymentCapabilities());
    workspace.mockResolvedValue(envelope(workspaceProjection()));
    await render();

    await change(input('Target ID'), 'loan-decision');
    await change(input('Target fingerprint'), 'sha256:graph');
    await click(button('Open exact target'));

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
    expect(host.textContent).toContain('业务权威');
    expect(host.textContent).toContain('冻结义务');
    expect(host.textContent).toContain('未证明');
    expect(workspace).toHaveBeenCalledWith(expect.objectContaining({
      targetId: 'loan-decision', targetFingerprint: 'sha256:graph',
    }));
  });

  async function render() {
    await act(async () => {
      root = createRoot(host);
      root.render(<I18nProvider><CorrectnessStudio api={api} /></I18nProvider>);
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
