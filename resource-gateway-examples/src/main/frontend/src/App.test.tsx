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
    expect(document.querySelectorAll('.topbar-link')).toHaveLength(3);
  });

  it('renders the showcase catalog for /showcase/', async () => {
    await renderAt('/showcase/');

    expect(document.body.textContent).toContain('Showcase');
    expect(document.title).toBe('BLOGE Visual Canvas - Showcase');
    expect(query('[data-testid="showcase-mock"]').textContent).toContain('Showcase catalog');
    expect(query<HTMLAnchorElement>('.topbar-link.active').getAttribute('href')).toBe('/showcase/');
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
