// @vitest-environment jsdom
import { act, useRef } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import useDialogFocusTrap from './useDialogFocusTrap';

function DialogHarness({
  open,
  onDismiss,
  preferredDisabled = false,
}: {
  open: boolean;
  onDismiss: () => void;
  preferredDisabled?: boolean;
}) {
  const dialogRef = useRef<HTMLElement>(null);
  useDialogFocusTrap({ open, dialogRef, onDismiss });
  return open ? (
    <section ref={dialogRef} role="dialog" tabIndex={-1}>
      <button type="button" data-dialog-initial-focus disabled={preferredDisabled}>First</button>
      <button type="button">Last</button>
    </section>
  ) : null;
}

describe('useDialogFocusTrap', () => {
  let host: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean })
      .IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    host.remove();
  });

  it('focuses the preferred task, traps Tab, dismisses with Escape, and restores the opener', async () => {
    const onDismiss = vi.fn();
    const opener = document.createElement('button');
    document.body.appendChild(opener);
    opener.focus();

    await act(async () => root.render(<DialogHarness open onDismiss={onDismiss} />));
    await act(async () => {
      await new Promise<void>((resolve) => window.requestAnimationFrame(() => resolve()));
    });

    const buttons = host.querySelectorAll<HTMLButtonElement>('button');
    expect(document.activeElement).toBe(buttons[0]);

    buttons[1].focus();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true }));
    expect(document.activeElement).toBe(buttons[0]);

    document.dispatchEvent(new KeyboardEvent('keydown', {
      key: 'Tab',
      shiftKey: true,
      bubbles: true,
    }));
    expect(document.activeElement).toBe(buttons[1]);

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    expect(onDismiss).toHaveBeenCalledOnce();

    await act(async () => root.render(<DialogHarness open={false} onDismiss={onDismiss} />));
    expect(document.activeElement).toBe(opener);
    opener.remove();
  });

  it('falls back to the first enabled task when the preferred target is unavailable', async () => {
    await act(async () => root.render(
      <DialogHarness open preferredDisabled onDismiss={vi.fn()} />,
    ));
    await act(async () => {
      await new Promise<void>((resolve) => window.requestAnimationFrame(() => resolve()));
    });

    expect(document.activeElement?.textContent).toBe('Last');
  });
});
