import { useEffect, useRef, type RefObject } from 'react';

const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[contenteditable="true"]',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

interface DialogFocusTrapOptions {
  open: boolean;
  dialogRef: RefObject<HTMLElement>;
  onDismiss: () => void;
  initialFocusKey?: string;
}

function focusableElements(dialog: HTMLElement): HTMLElement[] {
  return Array.from(dialog.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR))
    .filter((element) =>
      !element.hidden
      && element.getAttribute('aria-hidden') !== 'true'
      && !element.closest('[hidden], [aria-hidden="true"]'));
}

function focusInitialElement(dialog: HTMLElement): void {
  const preferred = dialog.querySelector<HTMLElement>('[data-dialog-initial-focus]');
  const preferredAvailable = preferred
    && !preferred.matches(':disabled')
    && !preferred.hidden
    && !preferred.closest('[hidden], [aria-hidden="true"]');
  const target = (preferredAvailable ? preferred : null) ?? focusableElements(dialog)[0] ?? dialog;
  target.focus();
}

/**
 * Applies the shared modal keyboard contract: enter the task, contain Tab, dismiss with Escape,
 * and return focus to the control that opened it.
 */
export default function useDialogFocusTrap({
  open,
  dialogRef,
  onDismiss,
  initialFocusKey = '',
}: DialogFocusTrapOptions): void {
  const dismissRef = useRef(onDismiss);
  const restoreFocusRef = useRef<HTMLElement | null>(null);
  dismissRef.current = onDismiss;

  useEffect(() => {
    if (!open) {
      return undefined;
    }
    restoreFocusRef.current = document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null;
    return () => {
      const restoreTarget = restoreFocusRef.current;
      restoreFocusRef.current = null;
      if (restoreTarget?.isConnected) {
        restoreTarget.focus();
      }
    };
  }, [open]);

  useEffect(() => {
    if (!open || !dialogRef.current) {
      return undefined;
    }
    const frame = window.requestAnimationFrame(() => {
      if (dialogRef.current) {
        focusInitialElement(dialogRef.current);
      }
    });
    return () => window.cancelAnimationFrame(frame);
  }, [dialogRef, initialFocusKey, open]);

  useEffect(() => {
    if (!open) {
      return undefined;
    }
    const onKeyDown = (event: KeyboardEvent) => {
      const dialog = dialogRef.current;
      if (!dialog) {
        return;
      }
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        dismissRef.current();
        return;
      }
      if (event.key !== 'Tab') {
        return;
      }
      const focusable = focusableElements(dialog);
      if (focusable.length === 0) {
        event.preventDefault();
        dialog.focus();
        return;
      }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      const active = document.activeElement;
      if (event.shiftKey && (active === first || !dialog.contains(active))) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && (active === last || !dialog.contains(active))) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', onKeyDown, true);
    return () => document.removeEventListener('keydown', onKeyDown, true);
  }, [dialogRef, open]);
}
