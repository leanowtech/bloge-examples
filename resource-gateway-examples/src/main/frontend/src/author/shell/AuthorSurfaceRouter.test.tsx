// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import AuthorSurfaceRouter from './AuthorSurfaceRouter';
import type { AuthorMode } from './authorWorkspaceState';

describe('AuthorSurfaceRouter', () => {
  let host: HTMLDivElement;
  let root: Root | null = null;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean })
      .IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
  });

  afterEach(async () => {
    if (root) {
      await act(async () => root?.unmount());
      root = null;
    }
    host.remove();
  });

  it('does not mount a central task region in Compose', async () => {
    await render('compose');

    expect(document.querySelector('.author-central-surface')).toBeNull();
  });

  it.each<Exclude<AuthorMode, 'compose'>>(['contract', 'scenarios', 'evidence'])(
    'mounts exactly one %s region with the exact target',
    async (mode) => {
      await render(mode);

      expect(document.querySelectorAll('.author-central-surface')).toHaveLength(1);
      expect(document.querySelector(`[data-testid="author-surface:${mode}"]`)
        ?.getAttribute('data-target-kind')).toBe('operator');
      expect(document.querySelector('[data-testid="author-context-rail-launcher"]')
        ?.getAttribute('aria-expanded')).toBe('false');
      expect(host.textContent).toContain(`${mode} content`);
    },
  );

  async function render(mode: AuthorMode) {
    await act(async () => {
      root = createRoot(host);
      root.render(
        <AuthorSurfaceRouter
          mode={mode}
          targetKind="operator"
          contextRailExpanded={false}
          onOpenContextRail={vi.fn()}
        >
          <div>{mode} content</div>
        </AuthorSurfaceRouter>,
      );
    });
  }
});
