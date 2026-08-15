// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import axe, { type AxeResults } from 'axe-core';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import I18nProvider from '../../i18n/I18nProvider';
import type { CorrectnessRunApi } from './RunCenter';
import RunCenter from './RunCenter';
import {
  deploymentCapabilities,
  envelope,
  preflightReport,
  storedCalibrationProposal,
  storedEvidence,
  storedGovernanceFeedback,
  workspaceProjection,
} from '../testFixtures';

describe('Correctness Run Center', () => {
  let host: HTMLDivElement;
  let root: Root | null;
  let api: CorrectnessRunApi;
  const preflight = vi.fn();
  const execute = vi.fn();
  const evidence = vi.fn();
  const calibrate = vi.fn();
  const governanceFeedback = vi.fn();

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    window.history.replaceState({}, '', '/correctness/?lang=en');
    host = document.createElement('div');
    document.body.appendChild(host);
    root = null;
    preflight.mockReset();
    execute.mockReset();
    evidence.mockReset();
    calibrate.mockReset();
    governanceFeedback.mockReset();
    governanceFeedback.mockResolvedValue(envelope(storedGovernanceFeedback()));
    api = { preflight, execute, evidence, calibrate, governanceFeedback };
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
    expect(host.textContent).toContain('does not grant current publication permission');
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

  it('shows exact ANEKE findings without claiming ownership of the gate lifecycle', async () => {
    await render();

    expect(governanceFeedback).toHaveBeenCalledWith('loan-publication');
    expect(host.textContent).toContain('ANEKE publication decision');
    expect(host.textContent).toContain('WORKBOOK_REQUIRED');
    expect(host.textContent).toContain('ANEKE remains the authority');
  });

  it('creates a proposed-only calibration from exact terminal evidence', async () => {
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
    calibrate.mockResolvedValue(envelope(storedCalibrationProposal()));
    await render();
    await click(button('Review run plan'));
    await click(button('Run reviewed selection'));
    await click(button('Propose calibration'));
    await changeValue(field('Why should the reviewed truth change?'), 'Reviewed policy changed.');
    await changeValue(field('Proposed regression title'), 'Preserve newly reviewed outcome');
    await click(button('Create review proposal'));

    expect(calibrate).toHaveBeenCalledWith(expect.objectContaining({
      suiteRunId: 'suite-run-1',
      evidenceCompanionFingerprint: companion.companionFingerprint,
      affectedCaseIds: ['eligible-prime'],
      affectedOracleIds: ['approve-eligible'],
      mismatchKind: 'EXPECTED_OUTCOME_DIFFERED',
    }));
    expect(host.textContent).toContain('Review proposal created');
    expect(host.textContent).toContain('remain unchanged');
  });

  it('has no serious or critical accessibility violations across the governed run loop', async () => {
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

    let result: AxeResults | undefined;
    await act(async () => {
      result = await axe.run(host, {
        runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa'] },
        rules: { 'color-contrast': { enabled: false } },
      });
    });
    const severe = (result as AxeResults).violations.filter((violation) =>
      violation.impact === 'serious' || violation.impact === 'critical');

    expect(severe.map((violation) => ({
      id: violation.id,
      targets: violation.nodes.map((node) => node.target),
    }))).toEqual([]);
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

  function field(label: string): HTMLInputElement | HTMLTextAreaElement {
    const wrapper = [...host.querySelectorAll('label')]
      .find((candidate) => candidate.textContent?.includes(label));
    const element = wrapper?.querySelector('input, textarea');
    if (!(element instanceof HTMLInputElement) && !(element instanceof HTMLTextAreaElement)) {
      throw new Error(`Missing field: ${label}`);
    }
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

async function changeValue(element: HTMLInputElement | HTMLTextAreaElement, value: string) {
  await act(async () => {
    const prototype = element instanceof HTMLTextAreaElement
      ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(prototype, 'value')?.set?.call(element, value);
    element.dispatchEvent(new Event('input', { bubbles: true }));
    await Promise.resolve();
  });
}
