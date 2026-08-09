// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import I18nProvider from '../../i18n/I18nProvider';
import SaveConflictResolutionDialog from './SaveConflictResolutionDialog';

describe('SaveConflictResolutionDialog', () => {
  let root: Root | null = null;
  let host: HTMLDivElement | null = null;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean })
      .IS_REACT_ACT_ENVIRONMENT = true;
  });

  afterEach(async () => {
    if (root) await act(async () => root?.unmount());
    host?.remove();
    root = null;
    host = null;
    window.history.replaceState({}, '', '/');
  });

  it('makes fork the safe default and gates destructive reload behind a second decision', async () => {
    const onFork = vi.fn();
    const onReload = vi.fn();
    host = document.createElement('div');
    document.body.appendChild(host);
    await act(async () => {
      root = createRoot(host as HTMLDivElement);
      root.render(
        <I18nProvider>
          <SaveConflictResolutionDialog
            open
            subjectLabel="Graph draft"
            local={{
              revision: 2,
              fingerprint: 'sha256:local',
              facts: [{ id: 'nodes', label: 'Nodes', value: 4 }],
            }}
            authoritative={{
              revision: 3,
              fingerprint: 'sha256:authoritative',
              facts: [{ id: 'nodes', label: 'Nodes', value: 5 }],
            }}
            onFork={onFork}
            onReload={onReload}
          />
        </I18nProvider>,
      );
    });
    query<HTMLElement>('[data-testid="save-conflict-dialog"]').focus();
    await act(async () => animationFrame());

    const fork = query<HTMLButtonElement>('[data-testid="save-conflict-fork"]');
    expect(document.activeElement).toBe(fork);
    expect(query('[data-testid="save-conflict-dialog"]').getAttribute('aria-modal')).toBe('true');
    expect(query('[data-conflict-fact="nodes"]').className).toContain('changed');

    await click(query<HTMLButtonElement>('[data-testid="save-conflict-reload"]'));
    expect(onReload).not.toHaveBeenCalled();
    expect(query('[data-testid="save-conflict-dialog"]').textContent).toContain('cannot be undone');

    await act(async () => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    });
    expect(document.querySelector('[data-testid="save-conflict-confirm-reload"]')).toBeNull();
    expect(query('[data-testid="save-conflict-dialog"]')).toBeDefined();

    await click(query<HTMLButtonElement>('[data-testid="save-conflict-reload"]'));
    await click(query<HTMLButtonElement>('[data-testid="save-conflict-confirm-reload"]'));
    expect(onReload).toHaveBeenCalledOnce();
    expect(onFork).not.toHaveBeenCalled();
  });
});

async function click(element: HTMLElement): Promise<void> {
  await act(async () => element.click());
}

function query<T extends Element = Element>(selector: string): T {
  const element = document.querySelector<T>(selector);
  expect(element, `Expected ${selector}`).not.toBeNull();
  return element as T;
}

function animationFrame(): Promise<void> {
  return new Promise((resolve) => window.requestAnimationFrame(() => resolve()));
}
