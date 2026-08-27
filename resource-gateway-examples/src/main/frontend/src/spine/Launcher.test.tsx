// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import Launcher from './Launcher';

describe('tool spine Launcher', () => {
  let root: Root | null = null;
  let host: HTMLDivElement;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
  });

  afterEach(async () => {
    await act(async () => root?.unmount());
    root = null;
    host.remove();
  });

  it('renders five accessible intent cards', async () => {
    await render();

    expect(document.querySelectorAll('[data-testid="spine-intent-card"]')).toHaveLength(5);
    expect(document.querySelector('[data-intent="build-tool"]')).toBeTruthy();
    expect(document.querySelector('[data-intent="import-dsl-api"]')).toBeTruthy();
    expect(document.querySelector('[data-intent="author-library"]')).toBeTruthy();
    expect(document.querySelector('[data-intent="review-evidence"]')).toBeTruthy();
    expect(document.querySelector('[data-intent="run-examples"]')).toBeTruthy();
  });

  it('creates a stable coordinate route after entering a non-blank tool name', async () => {
    await render();
    const input = element<HTMLInputElement>('[data-testid="spine-build-tool-name"]');
    const link = element<HTMLAnchorElement>('[data-testid="spine-build-tool-link"]');

    expect(link.getAttribute('href')).toBeNull();
    await act(async () => setInput(input, 'Loan Profile'));

    expect(link.getAttribute('href')).toBe(
      '/author/?spine=v1&toolId=tool-loan-profile&toolName=Loan+Profile&stage=define',
    );
    expect(link.getAttribute('aria-disabled')).toBe('false');
  });

  it('keeps distinct Unicode tool names distinct and deterministic', async () => {
    await render();
    const input = element<HTMLInputElement>('[data-testid="spine-build-tool-name"]');
    const link = element<HTMLAnchorElement>('[data-testid="spine-build-tool-link"]');

    await act(async () => setInput(input, '贷款资料'));
    const firstHref = link.getAttribute('href');
    expect(firstHref).toContain('toolId=tool-%E8%B4%B7%E6%AC%BE%E8%B5%84%E6%96%99');
    expect(firstHref).toContain('toolName=%E8%B4%B7%E6%AC%BE%E8%B5%84%E6%96%99');

    await act(async () => setInput(input, '信用资料'));
    expect(link.getAttribute('href')).not.toBe(firstHref);
  });

  it('exposes executable deep links for every non-build intent', async () => {
    await render();

    expect(element<HTMLAnchorElement>('[data-testid="spine-intent-link-import-dsl-api"]')
      .getAttribute('href')).toBe('/author/?spine=v1&intent=import-dsl-api');
    expect(element<HTMLAnchorElement>('[data-testid="spine-intent-link-author-library"]')
      .getAttribute('href')).toBe('/libraries/?spine=v1&intent=author-library');
    expect(element<HTMLAnchorElement>('[data-testid="spine-intent-link-review-evidence"]')
      .getAttribute('href')).toBe('/correctness/?spine=v1&intent=review-evidence');
    expect(element<HTMLAnchorElement>('[data-testid="spine-intent-link-run-examples"]')
      .getAttribute('href')).toBe('/showcase/?spine=v1&intent=run-examples');
  });

  it('offers all seven existing workspaces from an explicit menu', async () => {
    await render();

    const links = document.querySelectorAll<HTMLAnchorElement>('[data-testid="spine-all-workspaces"] a');
    expect(links).toHaveLength(7);
    expect([...links].map((link) => link.getAttribute('href'))).toEqual([
      '/capabilities/?spine=v1',
      '/business-mirror/?spine=v1',
      '/author/?spine=v1',
      '/correctness/?spine=v1',
      '/libraries/?spine=v1',
      '/rehearsals/?spine=v1',
      '/showcase/?spine=v1',
    ]);
  });

  async function render() {
    await act(async () => {
      root = createRoot(host);
      root.render(<Launcher />);
    });
  }
});

function element<T extends Element>(selector: string): T {
  const value = document.querySelector<T>(selector);
  if (!value) throw new Error(`Missing element: ${selector}`);
  return value;
}

function setInput(input: HTMLInputElement, value: string): void {
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
  setter?.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
  input.dispatchEvent(new Event('change', { bubbles: true }));
}
