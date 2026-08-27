// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import ObjectBreadcrumb from './ObjectBreadcrumb';
import type { ToolCoordinate } from './authorSpine';

describe('ObjectBreadcrumb', () => {
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

  it('shows Tool, DAG, and Node from a coordinate without writing protocol data', async () => {
    await render({
      toolId: 'loan',
      toolName: 'Loan Profile',
      stage: 'wire',
      graphDraftId: 'draft-7',
    }, 'policy');

    expect(host.textContent).toContain('Tool');
    expect(host.textContent).toContain('Loan Profile');
    expect(host.textContent).toContain('DAG');
    expect(host.textContent).toContain('draft-7');
    expect(host.textContent).toContain('Node');
    expect(host.textContent).toContain('policy');
    expect(host.querySelector('[data-tool-id="loan"]')).toBeTruthy();
  });

  it('degrades safely when coordinate or selected node is absent', async () => {
    await render(null);

    expect(host.querySelector('[data-testid="object-breadcrumb"]')).toBeTruthy();
    expect(host.textContent).toContain('No tool selected');
    expect(host.textContent).not.toContain('undefined');
  });

  async function render(coordinate: ToolCoordinate | null, selectedNodeId?: string) {
    await act(async () => {
      root = createRoot(host);
      root.render(<ObjectBreadcrumb coordinate={coordinate} selectedNodeId={selectedNodeId} />);
    });
  }
});
