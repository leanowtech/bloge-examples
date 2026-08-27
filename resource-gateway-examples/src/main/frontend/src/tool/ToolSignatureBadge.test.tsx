// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import ToolSignatureBadge from './ToolSignatureBadge';
import type { ToolSignature } from './toolModel';

const input = { format: 'json-schema', version: '2020-12', schema: { type: 'object' } } as const;

describe('ToolSignatureBadge', () => {
  let host: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
  });

  afterEach(() => act(() => root.unmount()));

  it('presents a draft without inventing publication metadata', () => {
    render({ state: 'draft', toolId: 'tool-1', toolName: 'Loan tool', input, output: input });
    expect(host.querySelector('[data-testid="tool-signature-badge"]')?.getAttribute('data-tool-state')).toBe('draft');
    expect(host.textContent).toContain('Draft');
    expect(host.textContent).not.toContain('#');
  });

  it('presents the immutable publication revision', () => {
    render({
      state: 'published', toolId: 'tool-1', toolName: 'Loan tool', input, output: input,
      publicationId: 'pub-42', publicationRevision: 3,
    });
    expect(host.querySelector('[data-testid="tool-signature-badge"]')?.getAttribute('data-tool-state')).toBe('published');
    expect(host.textContent).toContain('#pub-42 · r3');
  });

  function render(signature: ToolSignature): void {
    act(() => root.render(<ToolSignatureBadge signature={signature} />));
  }
});
