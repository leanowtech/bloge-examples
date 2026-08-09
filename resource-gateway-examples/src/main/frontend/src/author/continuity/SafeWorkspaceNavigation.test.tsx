// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import I18nProvider from '../../i18n/I18nProvider';
import {
  SafeWorkspaceNavigationProvider,
  useWorkspaceNavigationGuard,
} from './SafeWorkspaceNavigation';

describe('SafeWorkspaceNavigationProvider', () => {
  let root: Root | null = null;
  let host: HTMLDivElement;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean })
      .IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
  });

  afterEach(async () => {
    if (root) await act(async () => root?.unmount());
    root = null;
    host.remove();
  });

  it('flushes a recoverable snapshot before cross-workspace navigation', async () => {
    const flush = vi.fn().mockResolvedValue(true);
    const beforeUnloadResults: boolean[] = [];
    const navigate = vi.fn(() => {
      const event = new Event('beforeunload', { cancelable: true });
      window.dispatchEvent(event);
      beforeUnloadResults.push(event.defaultPrevented);
    });
    await render({ flush }, navigate);

    await act(async () => queryLink().click());

    expect(flush).toHaveBeenCalledOnce();
    expect(navigate).toHaveBeenCalledWith('/libraries/');
    expect(beforeUnloadResults).toEqual([false]);
    expect(host.querySelector('[role="dialog"]')).toBeNull();

    const laterDeparture = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(laterDeparture);
    expect(laterDeparture.defaultPrevented).toBe(true);
  });

  it('keeps the user in place when recovery flush fails', async () => {
    const navigate = vi.fn();
    await render({ flush: vi.fn().mockResolvedValue(false) }, navigate);

    await act(async () => queryLink().click());

    expect(host.querySelector('[role="dialog"]')).not.toBeNull();
    expect(navigate).not.toHaveBeenCalled();
    await act(async () => queryButton('Stay').click());
    expect(host.querySelector('[role="dialog"]')).toBeNull();
  });

  it('saves authoritatively before leaving when the user selects Save and leave', async () => {
    const save = vi.fn().mockResolvedValue(true);
    const navigate = vi.fn();
    await render({ flush: vi.fn().mockResolvedValue(false), save }, navigate);

    await act(async () => queryLink().click());
    await act(async () => queryButton('Save and leave').click());

    expect(save).toHaveBeenCalledOnce();
    expect(navigate).toHaveBeenCalledWith('/libraries/');
  });

  async function render(
    callbacks: { flush: () => Promise<boolean>; save?: () => Promise<boolean> },
    navigate: (href: string) => void,
  ) {
    await act(async () => {
      root = createRoot(host);
      root.render(
        <I18nProvider>
          <SafeWorkspaceNavigationProvider navigate={navigate}>
            <GuardRegistration callbacks={callbacks} />
            <a href="/libraries/">Libraries</a>
          </SafeWorkspaceNavigationProvider>
        </I18nProvider>,
      );
    });
  }

  function queryLink(): HTMLAnchorElement {
    const link = host.querySelector<HTMLAnchorElement>('a');
    if (!link) throw new Error('Link not found');
    return link;
  }

  function queryButton(label: string): HTMLButtonElement {
    const button = Array.from(host.querySelectorAll('button'))
      .find((candidate) => candidate.textContent === label);
    if (!button) throw new Error(`Button not found: ${label}`);
    return button;
  }
});

function GuardRegistration({
  callbacks,
}: {
  callbacks: { flush: () => Promise<boolean>; save?: () => Promise<boolean> };
}) {
  useWorkspaceNavigationGuard({
    lifecycle: 'DIRTY',
    flushRecovery: callbacks.flush,
    save: callbacks.save ?? (async () => false),
    exportRecovery: () => undefined,
    discard: async () => undefined,
  });
  return null;
}
