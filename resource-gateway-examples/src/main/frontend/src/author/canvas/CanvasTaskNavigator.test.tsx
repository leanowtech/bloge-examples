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
          perceptualQuality={{
            status: 'PASS',
            geometryStatus: 'PASS',
            mode: 'overview',
            nodeOverlaps: 0,
            nodeLabelCollisions: 0,
            labelLabelCollisions: 0,
            effectiveTitleFontPx: 12.3,
            visibleNodeLabels: 2,
            visibleEdgeLabels: 0,
            visibleFieldLabels: 0,
            labelDensityPer100kPx: 0.4,
            graphScreenOccupancy: 0.2,
            reasons: [],
            summary: 'PASS · 12.3px effective title · 0 edge labels · 0.4/100k px',
          }}
          topologyLanes={[]}
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
          perceptualQuality={{
            status: 'PASS',
            geometryStatus: 'PASS',
            mode: 'inspect',
            nodeOverlaps: 0,
            nodeLabelCollisions: 0,
            labelLabelCollisions: 0,
            effectiveTitleFontPx: 15,
            visibleNodeLabels: 1,
            visibleEdgeLabels: 0,
            visibleFieldLabels: 0,
            labelDensityPer100kPx: 0.2,
            graphScreenOccupancy: 0.1,
            reasons: [],
            summary: 'PASS · 15px effective title · 0 edge labels · 0.2/100k px',
          }}
          topologyLanes={[]}
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

  it('exposes stage-level navigation for a graph too large to scan node by node', async () => {
    const onSelectNode = vi.fn();
    await act(async () => {
      root!.render(
        <CanvasTaskNavigator
          mode="overview"
          nodes={Array.from({ length: 25 }, (_, index) => ({
            id: `n${index}`,
            label: `Node ${index}`,
            operatorRef: `operator:${index}`,
            pinned: false,
          }))}
          selectedNodeId=""
          nodeCount={25}
          edgeCount={24}
          pathNodeCount={0}
          zoomPercent="24%"
          mapVisible
          layoutPlanning={false}
          layoutPreview={false}
          layoutQuality={null}
          perceptualQuality={{
            status: 'PASS',
            geometryStatus: 'PASS',
            mode: 'overview',
            nodeOverlaps: 0,
            nodeLabelCollisions: 0,
            labelLabelCollisions: 0,
            effectiveTitleFontPx: 3.6,
            visibleNodeLabels: 25,
            visibleEdgeLabels: 0,
            visibleFieldLabels: 0,
            labelDensityPer100kPx: 3.4,
            graphScreenOccupancy: 0.8,
            reasons: [],
            summary: 'PASS · topology overview',
          }}
          topologyLanes={[
            {
              id: 'lane-0',
              depth: 0,
              label: 'Inputs',
              nodeIds: ['n0', 'n1'],
              representativeNodeId: 'n0',
            },
            {
              id: 'lane-1',
              depth: 1,
              label: 'Outputs',
              nodeIds: ['n24'],
              representativeNodeId: 'n24',
            },
          ]}
          layoutNotice=""
          canUndoLayout={false}
          onModeChange={vi.fn()}
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

    expect(host.textContent).toContain('Stages');
    expect(host.textContent).toContain('Inputs2');
    await act(async () => {
      host.querySelector<HTMLButtonElement>('[data-testid="canvas-topology-lane:lane-1"]')?.click();
    });
    expect(onSelectNode).toHaveBeenCalledWith('n24');
  });
});
