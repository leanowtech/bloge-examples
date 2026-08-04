// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { tableDrivenScenarioBaseline } from '../tableDrivenTestingBaseline';
import ScenarioMatrixSurface from './ScenarioMatrixSurface';
import { buildScenarioTableProjection, type ScenarioTableSelection } from './scenarioTableModel';

describe('ScenarioMatrixSurface', () => {
  let host: HTMLDivElement;
  let root: Root | null;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
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
    expect(text()).toContain('50 / 500 shown');
    expect(button('Show next 50 cases')).toBeInstanceOf(HTMLButtonElement);

    await click(button('Show next 50 cases'));
    expect(host.querySelectorAll('tbody tr')).toHaveLength(100);
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

    await click(host.querySelector<HTMLButtonElement>('tbody button')!);
    expect(onOpenCase).toHaveBeenCalledWith('case-1');
  });

  async function render(size: 5 | 50 | 500, overrides: Partial<Parameters<typeof ScenarioMatrixSurface>[0]> = {}) {
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
    await act(async () => root?.render(<StatefulMatrix {...props} />));
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
