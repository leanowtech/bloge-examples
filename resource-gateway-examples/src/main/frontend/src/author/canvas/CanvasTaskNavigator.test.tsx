// @vitest-environment jsdom

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import CanvasTaskNavigator from './CanvasTaskNavigator';

describe('CanvasTaskNavigator', () => {
  let root: Root | null;
  let host: HTMLDivElement;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean })
      .IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.append(host);
    root = createRoot(host);
  });

  afterEach(async () => {
    await act(async () => root?.unmount());
    root = null;
    host.remove();
  });

  it('searches, focuses, and exposes one explicit reading-mode control', async () => {
    const onModeChange = vi.fn();
    const onSelectNode = vi.fn();
    await act(async () => {
      root!.render(
        <CanvasTaskNavigator
          mode="overview"
          nodes={[
            { id: 'n1', label: 'Fetch applicant', operatorRef: 'resource:profile', pinned: false },
            { id: 'n2', label: 'Policy decision', operatorRef: 'bloge:decisionTable', pinned: true },
          ]}
          selectedNodeId=""
          nodeCount={2}
          edgeCount={1}
          pathNodeCount={0}
          zoomPercent="72%"
          mapVisible={false}
          layoutPlanning={false}
          layoutPreview={false}
          layoutQuality={null}
          layoutNotice=""
          canUndoLayout={false}
          onModeChange={onModeChange}
          onSelectNode={onSelectNode}
          onFitAll={vi.fn()}
          onToggleMap={vi.fn()}
          onTogglePin={vi.fn()}
          onApplyLayout={vi.fn()}
          onCancelLayout={vi.fn()}
          onUndoLayout={vi.fn()}
        />,
      );
    });

    const input = host.querySelector<HTMLInputElement>('input[aria-label="Find canvas node"]');
    expect(input).not.toBeNull();
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')
        ?.set?.call(input, 'decision');
      input!.dispatchEvent(new Event('input', { bubbles: true }));
    });
    const result = host.querySelector<HTMLButtonElement>('[data-testid="canvas-node-result:n2"]');
    expect(result?.textContent).toContain('Policy decision');
    await act(async () => result?.click());
    expect(onSelectNode).toHaveBeenCalledWith('n2');

    const focus = host.querySelector<HTMLButtonElement>('[data-testid="canvas-task-mode:focus"]');
    expect(focus?.disabled).toBe(true);
    expect(host.textContent).toContain('2 nodes');
    expect(host.textContent).toContain('72%');
  });

  it('keeps preview apply and cancel actions adjacent to the quality verdict', async () => {
    const onApplyLayout = vi.fn();
    const onCancelLayout = vi.fn();
    await act(async () => {
      root!.render(
        <CanvasTaskNavigator
          mode="inspect"
          nodes={[{
            id: 'n1',
            label: 'Fetch applicant',
            operatorRef: 'resource:profile',
            pinned: true,
          }]}
          selectedNodeId="n1"
          nodeCount={1}
          edgeCount={0}
          pathNodeCount={1}
          zoomPercent="100%"
          mapVisible={false}
          layoutPlanning={false}
          layoutPreview
          layoutQuality={{
            nodeOverlaps: 0,
            edgeLabelCollisions: 0,
            edgeLabelCollisionDetails: [],
            pinnedNodes: 1,
            status: 'PASS',
            summary: '0 node overlaps · 0 label collisions · 1 pinned',
          }}
          layoutNotice=""
          canUndoLayout={false}
          onModeChange={vi.fn()}
          onSelectNode={vi.fn()}
          onFitAll={vi.fn()}
          onToggleMap={vi.fn()}
          onTogglePin={vi.fn()}
          onApplyLayout={onApplyLayout}
          onCancelLayout={onCancelLayout}
          onUndoLayout={vi.fn()}
        />,
      );
    });

    expect(host.textContent).toContain('0 node overlaps · 0 label collisions · 1 pinned');
    await act(async () => {
      host.querySelector<HTMLButtonElement>('[data-testid="layout-apply"]')?.click();
      host.querySelector<HTMLButtonElement>('[data-testid="layout-cancel"]')?.click();
    });
    expect(onApplyLayout).toHaveBeenCalledOnce();
    expect(onCancelLayout).toHaveBeenCalledOnce();
    expect(host.querySelector('[data-testid="navigator-pin-node"]')?.textContent).toBe('Unpin');
  });
});
