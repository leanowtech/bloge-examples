// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import App from './App';

vi.mock('./AuthorCanvas', () => ({
  default: () => <div data-testid="author-mock">Author canvas</div>,
}));

vi.mock('./Showcase', () => ({
  default: () => <div data-testid="showcase-mock">Showcase catalog</div>,
}));

describe('App route shell', () => {
  let root: Root | null = null;
  let host: HTMLDivElement;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
  });

  afterEach(async () => {
    if (root) {
      await act(async () => root?.unmount());
      root = null;
    }
    host.remove();
    vi.restoreAllMocks();
  });

  it('renders the author canvas for /author/', async () => {
    await renderAt('/author/');

    expect(document.body.textContent).toContain('Author');
    expect(query('[data-testid="author-mock"]').textContent).toContain('Author canvas');
    expect(query<HTMLAnchorElement>('.topbar .link').getAttribute('href')).toBe('/showcase/');
  });

  it('renders the showcase catalog for /showcase/', async () => {
    await renderAt('/showcase/');

    expect(document.body.textContent).toContain('Showcase');
    expect(query('[data-testid="showcase-mock"]').textContent).toContain('Showcase catalog');
    expect(query<HTMLAnchorElement>('.topbar .link').getAttribute('href')).toBe('/author/');
  });

  async function renderAt(path: string) {
    window.history.pushState({}, '', path);
    await act(async () => {
      root = createRoot(host);
      root.render(<App />);
    });
  }
});

function query<T extends Element = Element>(selector: string): T {
  const element = document.querySelector<T>(selector);
  if (!element) {
    throw new Error(`Missing element: ${selector}`);
  }
  return element;
}
