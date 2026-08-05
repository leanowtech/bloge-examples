// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { tableDrivenScenarioBaseline } from '../tableDrivenTestingBaseline';
import ScenarioMatrixSurface from './ScenarioMatrixSurface';
import { buildScenarioTableProjection, type ScenarioTableSelection } from './scenarioTableModel';
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
    await click(button('Run selected'));
    expect(onRunSelection).toHaveBeenCalledWith('SELECTED');
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

  it('localizes matrix controls and counts without translating case payloads', async () => {
    window.history.replaceState({}, '', '/author/?lang=zh-CN');
    await render(5, {}, true);

    expect(text()).toContain('5 个标准用例');
    expect(text()).toContain('显示 1-5 / 5');
    expect(button('运行所选项')).toBeInstanceOf(HTMLButtonElement);
    expect(input('搜索用例').placeholder).toBe('搜索用例、ID 或标签');
    await click(button('检查'));
    expect(text()).toContain('case-1-expected-1-1');
    expect(text()).toContain('result.field01');
  });

  it('keeps seven decision columns and moves protocol fields into grouped details', async () => {
    await render(5);

    const headers = host.querySelectorAll('[data-testid="scenario-matrix-summary-columns"] th');
    expect(headers).toHaveLength(9);
    expect(Array.from(headers).slice(1, -1).map((header) => header.textContent))
      .toEqual(['Case', 'Result', 'Given', 'Dependencies', 'Assertions', 'Duration', 'Currentness']);
    expect(text()).not.toContain('OUTPUT_PATH:$.result.field01:EQUALS');

    await click(button('Inspect'));
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
    await click(button('Inspect'));
    expect(text()).toContain('Subject under test: Real target execution');
    expect(text()).toContain('APPROVED');
    expect(text()).toContain('REVIEW');
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

  async function renderProjection(projection: ReturnType<typeof buildScenarioTableProjection>) {
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
