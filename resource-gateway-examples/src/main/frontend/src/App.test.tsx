// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import App from './App';

vi.mock('./AuthorCanvas', () => ({
  default: ({ workspaceVersion }: { workspaceVersion: string }) => (
    <div data-testid="author-mock" data-workspace-version={workspaceVersion}>Author canvas</div>
  ),
}));

vi.mock('./Showcase', () => ({
  default: () => <div data-testid="showcase-mock">Showcase catalog</div>,
}));

vi.mock('./RehearsalWorkbench', () => ({
  default: () => <div data-testid="rehearsals-mock">Rehearsal workbench</div>,
}));

vi.mock('./library-authoring/LibraryWorkbench', () => ({
  default: () => <div data-testid="libraries-mock">Library workbench</div>,
}));

describe('App route shell', () => {
  let root: Root | null = null;
  let host: HTMLDivElement;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    const values = new Map<string, string>();
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      value: {
        clear: () => values.clear(),
        getItem: (key: string) => values.get(key) ?? null,
        setItem: (key: string, value: string) => values.set(key, value),
        removeItem: (key: string) => values.delete(key),
        key: (index: number) => [...values.keys()][index] ?? null,
        get length() { return values.size; },
      },
    });
    window.localStorage.clear();
    host = document.createElement('div');
    document.body.appendChild(host);
  });

  afterEach(async () => {
    if (root) {
      await act(async () => root?.unmount());
      root = null;
    }
    host?.remove();
    vi.restoreAllMocks();
  });

  it('renders the author canvas for /author/', async () => {
    await renderAt('/author/');

    expect(document.body.textContent).toContain('Author');
    expect(document.title).toBe('BLOGE Visual Canvas - Author');
    expect(query('[data-testid="author-mock"]').textContent).toContain('Author canvas');
    expect(query('[data-testid="author-mock"]').getAttribute('data-workspace-version')).toBe('v2');
    expect(query<HTMLAnchorElement>('.topbar-link.active').getAttribute('href')).toBe('/author/');
  });

  it('canonicalizes an explicit v2 link while preserving its deep-link context', async () => {
    await renderAt('/author/?authorWorkspace=v2&draftId=draft-1');

    expect(query('[data-testid="author-mock"]').getAttribute('data-workspace-version')).toBe('v2');
    expect(query<HTMLAnchorElement>('.topbar-link.active').getAttribute('href'))
      .toBe('/author/?draftId=draft-1');
  });

  it('keeps the legacy author reachable without discarding deep-link context', async () => {
    await renderAt('/author/?authorWorkspace=legacy&draftId=draft-1&nodeId=policy');

    expect(query('[data-testid="author-mock"]').getAttribute('data-workspace-version')).toBe('v1');
    expect(query<HTMLAnchorElement>('.topbar-link.active').textContent).toContain('Legacy');
    expect(query<HTMLAnchorElement>('.topbar-link.active').getAttribute('href'))
      .toBe('/author/?authorWorkspace=legacy&draftId=draft-1&nodeId=policy');
    expect(query<HTMLAnchorElement>('.topbar-link:not(.active)').getAttribute('href'))
      .toBe('/author/?draftId=draft-1&nodeId=policy');
  });

  it('falls back to legacy for an unknown explicit author shell version', async () => {
    await renderAt('/author/?authorWorkspace=experimental');

    expect(query('[data-testid="author-mock"]').getAttribute('data-workspace-version')).toBe('v1');
    expect(query<HTMLAnchorElement>('.topbar-link.active').textContent).toContain('Legacy');
  });

  it('renders the rehearsal workbench for /rehearsals/', async () => {
    await renderAt('/rehearsals/?jobId=job-1');

    expect(document.body.textContent).toContain('Rehearsals');
    expect(document.title).toBe('BLOGE Visual Canvas - Rehearsals');
    expect(query('[data-testid="rehearsals-mock"]').textContent).toContain('Rehearsal workbench');
    expect(query<HTMLAnchorElement>('.topbar-link.active').getAttribute('href')).toBe('/rehearsals/');
    expect(query<HTMLAnchorElement>('.topbar-link[href="/author/"]').textContent).toContain('Author');
    expect(document.querySelectorAll('.topbar-link')).toHaveLength(4);
  });

  it('renders the library workbench for /libraries/', async () => {
    await renderAt('/libraries/?draftId=library-1');

    expect(document.body.textContent).toContain('Libraries');
    expect(document.title).toBe('BLOGE Visual Canvas - Libraries');
    expect(query('[data-testid="libraries-mock"]').textContent).toContain('Library workbench');
    expect(query<HTMLAnchorElement>('.topbar-link.active').getAttribute('href')).toBe('/libraries/');
  });

  it('renders the showcase catalog for /showcase/', async () => {
    await renderAt('/showcase/');

    expect(document.body.textContent).toContain('Showcase');
    expect(document.title).toBe('BLOGE Visual Canvas - Showcase');
    expect(query('[data-testid="showcase-mock"]').textContent).toContain('Showcase catalog');
    expect(query<HTMLAnchorElement>('.topbar-link.active').getAttribute('href')).toBe('/showcase/');
  });

  it('switches the shell to Chinese and persists the preference without reloading', async () => {
    await renderAt('/author/?lang=en');

    const chineseButton = query<HTMLButtonElement>('[data-testid="locale-option:zh-CN"]');
    await act(async () => chineseButton.click());

    expect(document.documentElement.lang).toBe('zh-CN');
    expect(window.localStorage.getItem('bloge.visual.locale')).toBe('zh-CN');
    expect(document.title).toBe('BLOGE 可视化画布 - 编排');
    expect(query('nav[aria-label="工作区视图"]').textContent).toContain('算子库');
    expect(window.location.search).toContain('lang=zh-CN');
  });

  it('defaults to comfortable density and persists an expert compact choice', async () => {
    await renderAt('/author/');

    expect(document.documentElement.dataset.density).toBe('comfortable');
    expect(query<HTMLButtonElement>('[data-testid="density-option:comfortable"]')
      .getAttribute('aria-pressed')).toBe('true');

    await act(async () => query<HTMLButtonElement>('[data-testid="density-option:compact"]').click());

    expect(document.documentElement.dataset.density).toBe('compact');
    expect(window.localStorage.getItem('bloge.visual.density')).toBe('compact');
    expect(query<HTMLButtonElement>('[data-testid="density-option:compact"]')
      .getAttribute('aria-pressed')).toBe('true');
  });

  it('opens the responsive workspace navigation through an explicit menu command', async () => {
    await renderAt('/author/');

    const toggle = query<HTMLButtonElement>('.topbar-nav-toggle');
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(query('#workspace-navigation').getAttribute('data-open')).toBe('false');

    await act(async () => toggle.click());

    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(query('#workspace-navigation').getAttribute('data-open')).toBe('true');
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
