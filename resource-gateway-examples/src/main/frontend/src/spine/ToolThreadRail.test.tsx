// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import ToolThreadRail from './ToolThreadRail';
import type { ToolCoordinate } from './authorSpine';

describe('ToolThreadRail', () => {
  let root: Root | null = null;
  let host: HTMLDivElement;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    window.history.replaceState({}, '', '/author/?spine=v1&other=keep#selected');
    host = document.createElement('div');
    document.body.appendChild(host);
  });

  afterEach(async () => {
    await act(async () => root?.unmount());
    root = null;
    host.remove();
  });

  it('renders all six stages and preserves the spine in each stage href', async () => {
    await render({ toolId: 'loan', toolName: 'Loan Profile', stage: 'wire' });

    expect(host.querySelectorAll('[data-tool-stage]')).toHaveLength(6);
    const publish = host.querySelector<HTMLAnchorElement>('[data-tool-stage="publish"]');
    expect(publish?.getAttribute('href')).toContain('spine=v1');
    expect(publish?.getAttribute('href')).toContain('stage=publish');
    expect(publish?.getAttribute('href')).toContain('other=keep');
    expect(publish?.getAttribute('href')).toContain('#selected');
    expect(publish?.getAttribute('aria-current')).toBeNull();
    expect(host.querySelector<HTMLAnchorElement>('[data-tool-stage="wire"]')
      ?.getAttribute('aria-current')).toBe('step');
  });

  it('renders a safe disabled rail without a coordinate', async () => {
    await render(null);

    expect(host.querySelector('[data-testid="tool-thread-rail"]')).toBeTruthy();
    expect(host.querySelectorAll('a[data-tool-stage]')).toHaveLength(0);
    expect(host.textContent).toContain('Select a tool to continue');
  });

  async function render(coordinate: ToolCoordinate | null) {
    await act(async () => {
      root = createRoot(host);
      root.render(<ToolThreadRail coordinate={coordinate} />);
    });
  }
});
