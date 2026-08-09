// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import I18nProvider from '../../i18n/I18nProvider';
import ProductionCommandDialog from './ProductionCommandDialog';

describe('ProductionCommandDialog', () => {
  let host: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean })
      .IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.append(host);
    root = createRoot(host);
  });

  afterEach(() => {
    act(() => root.unmount());
    host.remove();
  });

  it('requires the explicit production phrase before invoking a destructive command', () => {
    const onConfirm = vi.fn();
    act(() => root.render(
      <I18nProvider>
        <ProductionCommandDialog
          open
          commandLabel="Delete connection"
          targetLabel="edge-7"
          onCancel={vi.fn()}
          onConfirm={onConfirm}
        />
      </I18nProvider>,
    ));
    const input = host.querySelector<HTMLInputElement>('[aria-label="Production confirmation"]')!;
    const confirm = [...host.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent === 'Confirm command')!;
    expect(confirm.disabled).toBe(true);

    act(() => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')
        ?.set?.call(input, 'production');
      input.dispatchEvent(new Event('input', { bubbles: true }));
    });
    expect(confirm.disabled).toBe(false);
    act(() => confirm.click());
    expect(onConfirm).toHaveBeenCalledOnce();
  });
});
