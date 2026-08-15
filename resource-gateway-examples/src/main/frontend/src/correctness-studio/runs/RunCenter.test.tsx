// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import I18nProvider from '../../i18n/I18nProvider';
import type { CorrectnessRunApi } from './RunCenter';
import RunCenter from './RunCenter';
import {
  deploymentCapabilities,
  envelope,
  preflightReport,
  storedEvidence,
  workspaceProjection,
} from '../testFixtures';

describe('Correctness Run Center', () => {
  let host: HTMLDivElement;
  let root: Root | null;
  let api: CorrectnessRunApi;
  const preflight = vi.fn();
  const execute = vi.fn();
  const evidence = vi.fn();

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    window.history.replaceState({}, '', '/correctness/?lang=en');
    host = document.createElement('div');
    document.body.appendChild(host);
    root = null;
    preflight.mockReset();
    execute.mockReset();
    evidence.mockReset();
    api = { preflight, execute, evidence };
  });

  afterEach(async () => {
    if (root) await act(async () => root?.unmount());
    host.remove();
  });

  it('sends selection intent without inventing the canonical fingerprint', async () => {
    preflight.mockResolvedValue(envelope(preflightReport()));
    await render();
    await click(button('Review run plan'));

    expect(preflight).toHaveBeenCalledWith(expect.objectContaining({
      selection: { mode: 'ALL', caseIds: [], expectedSelectionFingerprint: '' },
    }));
    expect(host.textContent).toContain('Plan ready for execution');
    expect(host.textContent).toContain('risk:credit-score');
    expect(button('Run reviewed selection').disabled).toBe(false);
  });

  it('runs only the reviewed exact selection and presents immutable five-axis evidence lineage', async () => {
    const report = preflightReport();
    const companion = storedEvidence();
    preflight.mockResolvedValue(envelope(report));
    execute.mockResolvedValue(envelope({
      schemaVersion: 'bloge.correctnessRunResponse.v1' as const,
      status: 'EVIDENCE_AVAILABLE' as const,
      suiteExecution: {
        schemaVersion: 'bloge.testSuiteExecution.v1', suiteRunId: 'suite-run-1',
        evidenceFingerprint: 'sha256:evidence', evidence: { status: 'SUCCESS' },
      },
      evidenceCompanion: companion,
    }));
    await render();
    await click(button('Review run plan'));
    await click(button('Run reviewed selection'));

    expect(execute).toHaveBeenCalledWith(expect.objectContaining({
      selection: report.selection,
      preflightFingerprint: report.preflightFingerprint,
      strategy: 'COLLECT_ALL',
    }));
    expect(host.textContent).toContain('Gate accepted');
    expect(host.textContent).toContain('Execution');
    expect(host.textContent).toContain('Assertions');
    expect(host.textContent).toContain('Coverage');
    expect(host.textContent).toContain('Evidence');
    expect(host.textContent).toContain('Gate');
    expect(host.textContent).toContain('oracle:approve-eligible');
    expect(host.textContent).toContain('assertion:decision-equals-approve');
  });

  it('invalidates an old review whenever the selection changes', async () => {
    preflight.mockResolvedValue(envelope(preflightReport()));
    await render();
    await click(button('Review run plan'));
    expect(host.textContent).toContain('Plan ready for execution');

    await click(button('Selected'));

    expect(host.textContent).not.toContain('Plan ready for execution');
    expect(button('Review run plan').disabled).toBe(true);
    await click(checkbox('Select Eligible prime customer'));
    expect(button('Review run plan').disabled).toBe(false);
    expect(execute).not.toHaveBeenCalled();
  });

  it('fails closed on preflight blockers and unavailable deployment capabilities', async () => {
    preflight.mockResolvedValue(envelope(preflightReport([{
      code: 'RG.CORRECTNESS.REAL_CALL_BLOCKED', messageId: 'real-call-blocked', caseId: 'eligible-prime',
    }])));
    await render();
    await click(button('Review run plan'));

    expect(host.textContent).toContain('1 blockers must be resolved');
    expect(button('Run reviewed selection').disabled).toBe(true);
    expect(execute).not.toHaveBeenCalled();

    await render(deploymentCapabilities({ correctnessPreflightApi: false }));
    expect(host.textContent).toContain('does not advertise governed run preflight');
    expect(button('Review run plan').disabled).toBe(true);
  });

  async function render(deployment = deploymentCapabilities()) {
    if (root) await act(async () => root?.unmount());
    await act(async () => {
      root = createRoot(host);
      root.render(
        <I18nProvider>
          <RunCenter workspace={workspaceProjection()} deployment={deployment} api={api} />
        </I18nProvider>,
      );
    });
  }

  function button(label: string): HTMLButtonElement {
    const element = [...host.querySelectorAll('button')].find((candidate) => candidate.textContent?.includes(label));
    if (!(element instanceof HTMLButtonElement)) throw new Error(`Missing button: ${label}`);
    return element;
  }

  function checkbox(label: string): HTMLInputElement {
    const element = host.querySelector<HTMLInputElement>(`input[aria-label="${label}"]`);
    if (!element) throw new Error(`Missing checkbox: ${label}`);
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
