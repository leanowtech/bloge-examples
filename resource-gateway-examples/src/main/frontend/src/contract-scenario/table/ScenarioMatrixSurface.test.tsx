// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { tableDrivenScenarioBaseline } from '../tableDrivenTestingBaseline';
import ScenarioMatrixSurface from './ScenarioMatrixSurface';
import {
  buildScenarioTableProjection,
  notRunEvidence,
  type ScenarioCommandReceipt,
  type ScenarioTableSelection,
} from './scenarioTableModel';
import I18nProvider from '../../i18n/I18nProvider';

describe('ScenarioMatrixSurface', () => {
  let host: HTMLDivElement;
  let root: Root | null;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
    window.history.pushState({}, '', '/author/?lang=en');
  });

  afterEach(async () => {
    await act(async () => root?.unmount());
    host.remove();
    root = null;
  });

  it('shows a stable 50-row comparison window for the 500-case corpus', async () => {
    await render(500);

    expect(host.querySelectorAll('tbody tr')).toHaveLength(50);
    expect(text()).toContain('500 canonical cases');
    expect(text()).toContain('1-50 / 500 shown');
    expect(button('Next 50')).toBeInstanceOf(HTMLButtonElement);

    await click(button('Next 50'));
    expect(host.querySelectorAll('tbody tr')).toHaveLength(50);
    expect(text()).toContain('51-100 / 500 shown');
  });

  it('keeps selection while a filter hides the selected row and submits selected mode', async () => {
    const onRunSelection = vi.fn();
    const projection = buildScenarioTableProjection(tableDrivenScenarioBaseline(50));
    const selectedName = projection.rows[38].name;
    await render(50, {
      onRunSelection,
    });

    await click(input(`Select ${selectedName}`));
    await type(input('Search cases'), projection.rows[0].name);

    expect(text()).toContain('1 selected');
    const runSelected = button('Run selected (1)');
    expect(runSelected.dataset.commandScope).toBe('SELECTION');
    expect(runSelected.dataset.scopeCount).toBe('1');
    expect(runSelected.dataset.scopeFingerprint).toMatch(/^fnv1a32:/);
    await click(runSelected);
    expect(onRunSelection).toHaveBeenCalledWith('SELECTED');
  });

  it('publishes exact suite scope and locks selection while the submitted plan is running', async () => {
    await render(5, { disabled: true, runningCaseIds: ['case-1', 'case-2'] });

    const runAll = button('Running 2...');
    expect(runAll.dataset.commandScope).toBe('SUITE');
    expect(runAll.dataset.scopeCount).toBe('5');
    expect(runAll.dataset.scopeFingerprint).toMatch(/^fnv1a32:/);
    expect(input('Select visible cases').disabled).toBe(true);
    expect(host.querySelector<HTMLInputElement>('tbody input[type="checkbox"]')?.disabled).toBe(true);
  });

  it('links the visible command receipt to the same identity in row proof', async () => {
    const receipt: ScenarioCommandReceipt = {
      correlationId: 'request-command-a',
      source: 'SERVER',
      state: 'TERMINAL',
      mode: 'SELECTED',
      caseIds: ['case-1'],
      caseCount: 1,
      previewFingerprint: 'fnv1a32:preview-a',
      canonicalFingerprint: 'sha256:canonical-a',
      batchId: 'batch-a',
    };
    const projection = buildScenarioTableProjection(tableDrivenScenarioBaseline(5), {
      'case-1': { ...notRunEvidence('case-1'), commandReceipt: receipt },
    });
    await renderProjection(projection, { commandReceipt: receipt });

    const summary = host.querySelector('[data-testid="scenario-matrix-command-receipt"]');
    expect(summary?.textContent).toContain('request-command-a');
    expect(summary?.getAttribute('aria-label'))
      .toBe('Command receipt: TERMINAL, SELECTED, 1 cases');
    expect(summary?.querySelector('code[title="sha256:canonical-a"]')).not.toBeNull();
    await click(buttonByLabel('Inspect '));
    const detail = host.querySelector('[data-testid="scenario-matrix-detail-case-1"]');
    expect(detail?.querySelector('code[title="request-command-a"]')).not.toBeNull();
    expect(detail?.querySelector('code[title="sha256:canonical-a"]')).not.toBeNull();
  });

  it('opens one case without changing its canonical Matrix values', async () => {
    const onOpenCase = vi.fn();
    await render(5, { onOpenCase });

    await click(button('Open'));
    expect(onOpenCase).toHaveBeenCalledWith('case-1');
  });

  it('explains why import is unavailable before the target is retained', async () => {
    await render(5, {
      onImportCases: vi.fn(),
      importDisabled: true,
      importDisabledReason: 'Save Graph before importing cases.',
    });

    expect(button('Import cases').disabled).toBe(true);
    expect(button('Import cases').title).toBe('Save Graph before importing cases.');
  });

  it('shows exact differential counts and prevents empty changed runs', async () => {
    const onRunSelection = vi.fn();
    await render(5, {
      baselineAvailable: true,
      differentialCounts: { failed: 1, changed: 0, affected: 1, targetChanged: false },
      onRunSelection,
    });

    expect(button('Run changed (0)').disabled).toBe(true);
    expect(button('Run changed (0)').title).toContain('No cases changed');
    expect(button('Run affected (1)').disabled).toBe(false);
    await click(button('Run affected (1)'));
    expect(onRunSelection).toHaveBeenCalledWith('AFFECTED');
  });

  it('projects exactly one primary run command and one alternative-scope menu', async () => {
    await render(5, { compactCommands: true });

    const matrix = host.querySelector('[data-testid="scenario-matrix"]');
    expect(matrix?.getAttribute('data-command-density')).toBe('compact');
    expect(matrix?.querySelector('.scenario-preset-menu')).toBeNull();
    expect(matrix?.querySelector('.scenario-run-scope-menu summary')?.getAttribute('aria-label'))
      .toBe('More run scopes');
    expect(matrix?.querySelectorAll('.scenario-run-scope-menu button')).toHaveLength(3);
    expect(matrix?.querySelectorAll('.scenario-matrix-bulk-actions > button')).toHaveLength(1);
    expect(button('Run all (5)').classList.contains('primary')).toBe(true);
  });

  it('shows three comparable mobile summaries without mounting the desktop table', async () => {
    await render(5, { compactCommands: true });

    const results = host.querySelector('[data-testid="scenario-mobile-results"]');
    expect(results?.getAttribute('data-first-viewport-count')).toBe('3');
    expect(results?.querySelectorAll('[data-first-viewport="true"]')).toHaveLength(3);
    expect(results?.querySelectorAll('.scenario-mobile-result')).toHaveLength(5);
    expect(host.querySelector('.scenario-matrix-scroll')).toBeNull();
    expect(host.querySelector('[data-testid="scenario-matrix"]')?.getAttribute('data-result-projection'))
      .toBe('mobile-summary');
  });

  it('opens a failed mobile result directly onto its field diff', async () => {
    const draftSet = tableDrivenScenarioBaseline(5);
    const caseId = draftSet.scenarios[0].scenarioId;
    const projection = buildScenarioTableProjection(draftSet, {
      [caseId]: {
        caseId,
        runId: 'run-mobile-failure',
        attempt: 1,
        execution: 'SUCCESS',
        assertions: 'FAILED',
        freshness: 'CURRENT',
        proofStrength: 'RUNTIME',
        subjectMode: 'REAL',
        durationMs: 11,
        firstFailure: null,
        assertionDiffs: [{
          assertionId: 'decision',
          path: '$.decision',
          passed: false,
          expected: 'APPROVED',
          actual: 'REVIEW',
          detail: 'Expected APPROVED.',
        }],
      },
    });
    await renderProjection(projection, { compactCommands: true });

    const result = host.querySelector(`[data-case-coordinate="${caseId}"]`);
    expect(result?.textContent).toContain('$.decision');
    await click(buttonByLabel('Inspect '));
    const detail = host.querySelector(`[data-testid="scenario-matrix-detail-${caseId}"]`);
    expect(detail?.firstElementChild?.textContent).toContain('Expected / Actual / Diff');
    expect(detail?.textContent).toContain('APPROVED');
    expect(detail?.textContent).toContain('REVIEW');
  });

  it('preserves selection, expansion, and focused case while switching mobile and desktop projections', async () => {
    await render(5, { compactCommands: true });
    const firstInspect = buttonByLabel('Inspect ');
    firstInspect.focus();
    await click(firstInspect);
    const firstSelect = host.querySelector<HTMLInputElement>('.scenario-mobile-result input[type="checkbox"]');
    expect(firstSelect).not.toBeNull();
    await click(firstSelect as HTMLInputElement);

    await render(5, { compactCommands: false });

    expect(button('Run selected (1)')).toBeInstanceOf(HTMLButtonElement);
    expect(host.querySelector('[data-testid="scenario-matrix-detail-case-1"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="scenario-mobile-results"]')).toBeNull();
    expect((document.activeElement as HTMLElement | null)?.dataset.focusAction).toBe('inspect');
    expect((document.activeElement as HTMLElement | null)?.closest('[data-case-coordinate]')
      ?.getAttribute('data-case-coordinate')).toBe('case-1');
  });

  it('localizes matrix controls and counts without translating case payloads', async () => {
    window.history.replaceState({}, '', '/author/?lang=zh-CN');
    await render(5, {}, true);

    expect(text()).toContain('5 个标准用例');
    expect(text()).toContain('显示 1-5 / 5');
    expect(button('运行全部（5）')).toBeInstanceOf(HTMLButtonElement);
    expect(input('搜索用例').placeholder).toBe('搜索用例、ID 或标签');
    expect(text()).toContain('未评估');
    expect(text()).not.toContain('当前证据');
    await click(buttonByLabel('检查 '));
    expect(text()).toContain('case-1-expected-1-1');
    expect(text()).toContain('result.field01');
  });

  it('keeps seven decision columns and moves protocol fields into grouped details', async () => {
    await render(5);

    const headers = host.querySelectorAll('[data-testid="scenario-matrix-summary-columns"] th');
    expect(headers).toHaveLength(9);
    expect(Array.from(headers).slice(1, -1).map((header) => header.textContent))
      .toEqual(['Case', 'Behavior', 'Given', 'Dependencies', 'Assertions', 'Duration', 'Proof authority']);
    expect(text()).not.toContain('OUTPUT_PATH:$.result.field01:EQUALS');

    await click(buttonByLabel('Inspect '));
    expect(text()).toContain('Expected / Actual / Diff');
    expect(text()).toContain('result.field01');
  });

  it('filters failed cases from the result-first facet bar', async () => {
    const draftSet = tableDrivenScenarioBaseline(5);
    const projection = buildScenarioTableProjection(draftSet, {
      [draftSet.scenarios[2].scenarioId]: {
        caseId: draftSet.scenarios[2].scenarioId,
        runId: 'run-failed',
        attempt: 1,
        execution: 'SUCCESS',
        assertions: 'FAILED',
        freshness: 'CURRENT',
        proofStrength: 'RUNTIME',
        subjectMode: 'REAL',
        durationMs: 9,
        assertionDiffs: [{
          assertionId: 'decision',
          path: '$.decision',
          passed: false,
          expected: 'APPROVED',
          actual: 'REVIEW',
          detail: 'Expected APPROVED.',
        }],
        firstFailure: { category: 'ASSERTION', target: '$.decision', message: 'Expected APPROVED.' },
      },
    });
    await renderProjection(projection);

    await click(button('Failed1'));
    expect(host.querySelectorAll('tbody tr[data-testid]')).toHaveLength(1);
    expect(text()).toContain('business case 3');
    expect(text()).not.toContain('business case 1');
    await click(buttonByLabel('Inspect '));
    expect(text()).toContain('Subject under test: Real target execution');
    expect(text()).toContain('APPROVED');
    expect(text()).toContain('REVIEW');
  });

  it('shows behavior, proof, freshness, and governance without promoting Mock evidence', async () => {
    const draftSet = tableDrivenScenarioBaseline(5);
    const caseId = draftSet.scenarios[0].scenarioId;
    const projection = buildScenarioTableProjection(draftSet, {
      [caseId]: {
        caseId,
        runId: 'run-mock',
        attempt: 1,
        execution: 'SUCCESS',
        assertions: 'PASSED',
        freshness: 'CURRENT',
        proofStrength: 'MOCK',
        subjectMode: 'REAL',
        durationMs: 7,
        firstFailure: null,
      },
    });
    await renderProjection(projection);

    const authority = host.querySelector(`[data-testid="scenario-matrix-authority-${caseId}"]`);
    expect(authority?.textContent).toContain('Mock simulation');
    expect(authority?.textContent).toContain('Current evidence');
    expect(authority?.textContent).toContain('Not publish eligible');
    expect(authority?.getAttribute('data-governance')).toBe('ineligible');
  });

  it('keeps raw failure protocol fields inside closed technical details', async () => {
    const draftSet = tableDrivenScenarioBaseline(5);
    const caseId = draftSet.scenarios[0].scenarioId;
    const projection = buildScenarioTableProjection(draftSet, {
      [caseId]: {
        caseId,
        runId: 'run-timeout',
        attempt: 1,
        execution: 'TIMEOUT',
        assertions: 'NONE',
        freshness: 'CURRENT',
        proofStrength: 'RUNTIME',
        subjectMode: 'REAL',
        durationMs: 10_000,
        firstFailure: {
          category: 'DEPENDENCY_TIMEOUT',
          target: 'risk-service',
          message: 'Socket deadline exceeded.',
        },
      },
    });
    await renderProjection(projection);

    const row = host.querySelector(`[data-testid="scenario-matrix-row-${caseId}"]`);
    expect(row?.textContent).toContain('Execution timed out');
    expect(row?.textContent).not.toContain('DEPENDENCY_TIMEOUT');
    expect(row?.textContent).not.toContain('Socket deadline exceeded');

    await click(buttonByLabel('Inspect '));
    const technical = host.querySelector<HTMLDetailsElement>('.scenario-matrix-technical-details');
    expect(technical?.open).toBe(false);
    expect(technical?.textContent).toContain('DEPENDENCY_TIMEOUT');
    expect(technical?.textContent).toContain('Socket deadline exceeded');
  });

  async function render(
    size: 5 | 50 | 500,
    overrides: Partial<Parameters<typeof ScenarioMatrixSurface>[0]> = {},
    localized = false,
  ) {
    const projection = buildScenarioTableProjection(tableDrivenScenarioBaseline(size));
    const props: Parameters<typeof ScenarioMatrixSurface>[0] = {
      projection,
      selection: { selectedCaseIds: [] },
      previousRunCaseIds: [],
      runningCaseIds: [],
      onSelectionChange: vi.fn(),
      onOpenCase: vi.fn(),
      onCellEdit: vi.fn(),
      onAddCase: vi.fn(),
      onRunSelection: vi.fn(),
      ...overrides,
    };
    const matrix = <StatefulMatrix {...props} />;
    await act(async () => root?.render(localized ? <I18nProvider>{matrix}</I18nProvider> : matrix));
  }

  async function renderProjection(
    projection: ReturnType<typeof buildScenarioTableProjection>,
    overrides: Partial<Parameters<typeof ScenarioMatrixSurface>[0]> = {},
  ) {
    await act(async () => root?.render(<StatefulMatrix
      projection={projection}
      selection={{ selectedCaseIds: [] }}
      previousRunCaseIds={[]}
      runningCaseIds={[]}
      onSelectionChange={vi.fn()}
      onOpenCase={vi.fn()}
      onCellEdit={vi.fn()}
      onAddCase={vi.fn()}
      onRunSelection={vi.fn()}
      {...overrides}
    />));
  }

  function text() {
    return host.textContent ?? '';
  }

  function button(name: string) {
    const match = Array.from(host.querySelectorAll<HTMLButtonElement>('button'))
      .find((candidate) => candidate.textContent?.trim() === name);
    if (!match) throw new Error(`Missing button: ${name}`);
    return match;
  }

  function buttonByLabel(prefix: string) {
    const match = Array.from(host.querySelectorAll<HTMLButtonElement>('button[aria-label]'))
      .find((candidate) => candidate.getAttribute('aria-label')?.startsWith(prefix));
    if (!match) throw new Error(`Missing button label prefix: ${prefix}`);
    return match;
  }

  function input(name: string) {
    const match = host.querySelector<HTMLInputElement>(`input[aria-label="${name}"]`);
    if (!match) throw new Error(`Missing input: ${name}`);
    return match;
  }
});

function StatefulMatrix(props: Parameters<typeof ScenarioMatrixSurface>[0]) {
  const [selection, setSelection] = useState<ScenarioTableSelection>(props.selection);
  return (
    <ScenarioMatrixSurface
      {...props}
      selection={selection}
      onSelectionChange={(next) => {
        setSelection(next);
        props.onSelectionChange(next);
      }}
    />
  );
}

async function click(element: HTMLElement) {
  await act(async () => element.dispatchEvent(new MouseEvent('click', { bubbles: true })));
}

async function type(element: HTMLInputElement, value: string) {
  await act(async () => {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(element, value);
    element.dispatchEvent(new Event('input', { bubbles: true }));
  });
}
