// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { tableDrivenScenarioBaseline } from '../tableDrivenTestingBaseline';
import ScenarioImportWorkbench from './ScenarioImportWorkbench';

describe('ScenarioImportWorkbench', () => {
  let host: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    host.remove();
  });

  it('moves from a bounded sample source to masked preview and mapping', async () => {
    await render();
    await click(button('Load sample'));
    expect(textarea().value).toContain('import-example-1');
    await click(button('Inspect source'));
    await settle();

    expect(text()).toContain('5 source rows');
    expect(text()).toContain('7 columns');
    expect(text()).toContain('Import example 1');
    await click(button('Map columns'));
    expect(select('Target for name').value).toBe('case:name');
    expect(select('Target for field01').value).toBe('given:/field01');
    expect(text()).toContain('100%');
  });

  it('masks a sensitive source value in Preview while retaining a payload-free warning', async () => {
    await render();
    await setTextarea('name,api_token,field01\nCase A,super-secret-value,12');
    await click(button('Inspect source'));
    await settle();

    expect(text()).toContain('[masked]');
    expect(text()).not.toContain('super-secret-value');
    expect(text()).toContain('Potentially sensitive values are masked');
  });

  it('requires explicit confirmation for normalized mappings', async () => {
    await render();
    await setTextarea('name,Field-01\nCase A,value');
    await click(button('Inspect source'));
    await settle();
    await click(button('Map columns'));

    expect(text()).toContain('82%');
    await click(button('Review plan'));
    expect(text()).toContain('Confirm every low-confidence mapping');
    const confirm = host.querySelector<HTMLInputElement>('.scenario-import-confidence input[type="checkbox"]');
    expect(confirm).not.toBeNull();
    await click(confirm!);
    await click(button('Review plan'));
    expect(text()).toContain('Freeze the exact materialization closure');
  });

  it('materializes sample rows and exposes the exact receipt', async () => {
    const onMaterialize = vi.fn();
    await render(onMaterialize);
    await click(button('Load sample'));
    await click(button('Inspect source'));
    await settle();
    await click(await waitForButton('Map columns'));
    await click(button('Review plan'));
    expect(text()).toContain('6 bindings');
    await click(button('Materialize 5 cases'));
    for (let attempt = 0; attempt < 10 && onMaterialize.mock.calls.length === 0; attempt += 1) {
      await settle();
    }

    expect(onMaterialize).toHaveBeenCalledTimes(1);
    const result = onMaterialize.mock.calls[0][0];
    expect(result.receipt).toMatchObject({
      acceptedRowCount: 5,
      rejectedRowCount: 0,
      rowIdentityPolicy: { kind: 'SOURCE_COLUMN', sourcePath: '/id' },
    });
    expect(result.draftSet.scenarios).toHaveLength(10);
    expect(text()).toContain('5 cases materialized');
    expect(text()).toContain(result.receipt.planFingerprint);
    expect(button('Done')).toBeInstanceOf(HTMLButtonElement);
  });

  async function render(onMaterialize = vi.fn()) {
    await act(async () => root.render(
      <ScenarioImportWorkbench
        open
        draftSet={tableDrivenScenarioBaseline(5)}
        onMaterialize={onMaterialize}
        onClose={vi.fn()}
      />,
    ));
  }

  function text() {
    return host.textContent ?? '';
  }

  function button(name: string) {
    const candidate = Array.from(host.querySelectorAll<HTMLButtonElement>('button'))
      .find((element) => element.textContent?.trim() === name);
    if (!candidate) throw new Error(`Missing button: ${name}`);
    return candidate;
  }

  async function waitForButton(name: string, timeoutMs = 2_000): Promise<HTMLButtonElement> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const candidate = Array.from(host.querySelectorAll<HTMLButtonElement>('button'))
        .find((element) => element.textContent?.trim() === name);
      if (candidate) return candidate;
      await act(async () => new Promise((resolve) => setTimeout(resolve, 10)));
    }
    return button(name);
  }

  function textarea() {
    const candidate = host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Scenario import source"]');
    if (!candidate) throw new Error('Missing Scenario import source');
    return candidate;
  }

  function select(name: string) {
    const candidate = host.querySelector<HTMLSelectElement>(`select[aria-label="${name}"]`);
    if (!candidate) throw new Error(`Missing select: ${name}`);
    return candidate;
  }

  async function setTextarea(value: string) {
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set?.call(textarea(), value);
      textarea().dispatchEvent(new Event('input', { bubbles: true }));
    });
  }
});

async function click(element: HTMLElement) {
  await act(async () => element.dispatchEvent(new MouseEvent('click', { bubbles: true })));
}

async function settle() {
  await act(async () => {
    for (let index = 0; index < 3; index += 1) {
      await Promise.resolve();
      await new Promise((resolve) => setTimeout(resolve, 0));
    }
  });
}
