// @vitest-environment jsdom

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import I18nProvider from '../../i18n/I18nProvider';
import CanvasTaskNavigator from './CanvasTaskNavigator';
import type { LayoutAcceptanceDecision } from './layoutAcceptance';

function layoutAcceptance(
  decision: LayoutAcceptanceDecision['decision'],
): LayoutAcceptanceDecision {
  const geometry = {
    nodeOverlaps: 0,
    edgeLabelCollisions: 0,
    edgeLabelCollisionDetails: [],
    pinnedNodes: 1,
    status: 'PASS' as const,
  };
  const perception = (status: 'PASS' | 'REVIEW', zoom: number) => ({
    status,
    geometryStatus: 'PASS' as const,
    mode: 'inspect' as const,
    nodeOverlaps: 0,
    nodeLabelCollisions: 0,
    labelLabelCollisions: 0,
    effectiveTitleFontPx: 15 * zoom,
    visibleNodeLabels: 5,
    visibleEdgeLabels: 4,
    visibleFieldLabels: 0,
    labelDensityPer100kPx: 1.2,
    graphScreenOccupancy: 0.4,
    reasons: [],
  });
  return {
    decision,
    before: {
      geometry,
      perception: perception('PASS', 0.85),
      zoom: 0.85,
      graphArea: 100_000,
    },
    candidate: {
      geometry,
      perception: perception(decision === 'ACCEPTABLE' ? 'PASS' : 'REVIEW', decision === 'ACCEPTABLE' ? 0.9 : 0.39),
      zoom: decision === 'ACCEPTABLE' ? 0.9 : 0.39,
      graphArea: decision === 'ACCEPTABLE' ? 110_000 : 270_000,
    },
    regressions: decision === 'ACCEPTABLE' ? [] : [
      { code: 'PERCEPTION_REGRESSION', before: 'PASS', candidate: 'REVIEW' },
      { code: 'SMALL_GRAPH_ZOOM_FLOOR', before: 0.85, candidate: 0.39 },
    ],
    recommendedStrategy: decision === 'ACCEPTABLE' ? 'COMPACT_LANES' : 'KEEP_CURRENT',
  };
}

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
    window.history.replaceState({}, '', '/');
    window.localStorage?.clear();
  });

  it('searches, focuses, and exposes one explicit reading-mode control', async () => {
    const onModeChange = vi.fn();
    const onSelectNode = vi.fn();
    const onToggleCanvasExpanded = vi.fn();
    const onZoomIn = vi.fn();
    const onZoomOut = vi.fn();
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
          canvasExpanded={false}
          layoutPlanning={false}
          layoutPreview={false}
          layoutQuality={null}
          layoutAcceptance={null}
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
          }}
          topologyLanes={[]}
          layoutNotice={null}
          canUndoLayout={false}
          onModeChange={onModeChange}
          onSelectNode={onSelectNode}
          onToggleCanvasExpanded={onToggleCanvasExpanded}
          onZoomIn={onZoomIn}
          onZoomOut={onZoomOut}
          onFitAll={vi.fn()}
          onToggleMap={vi.fn()}
          onTogglePin={vi.fn()}
          onApplyLayout={vi.fn()}
          onOverrideLayout={vi.fn()}
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

    await act(async () => {
      host.querySelector<HTMLButtonElement>('[data-testid="navigator-expand-canvas"]')?.click();
      host.querySelector<HTMLButtonElement>('[data-testid="navigator-zoom-out"]')?.click();
      host.querySelector<HTMLButtonElement>('[data-testid="navigator-zoom-in"]')?.click();
    });
    expect(onToggleCanvasExpanded).toHaveBeenCalledOnce();
    expect(onZoomOut).toHaveBeenCalledOnce();
    expect(onZoomIn).toHaveBeenCalledOnce();

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
          canvasExpanded={false}
          layoutPlanning={false}
          layoutPreview
          layoutAcceptance={layoutAcceptance('ACCEPTABLE')}
          layoutQuality={{
            nodeOverlaps: 0,
            edgeLabelCollisions: 0,
            edgeLabelCollisionDetails: [],
            pinnedNodes: 1,
            status: 'PASS',
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
          }}
          topologyLanes={[]}
          layoutNotice={null}
          canUndoLayout={false}
          onModeChange={vi.fn()}
          onSelectNode={vi.fn()}
          onToggleCanvasExpanded={vi.fn()}
          onZoomIn={vi.fn()}
          onZoomOut={vi.fn()}
          onFitAll={vi.fn()}
          onToggleMap={vi.fn()}
          onTogglePin={vi.fn()}
          onApplyLayout={onApplyLayout}
          onOverrideLayout={vi.fn()}
          onCancelLayout={onCancelLayout}
          onUndoLayout={vi.fn()}
        />,
      );
    });

    expect(host.textContent).toContain('Layout candidate preserves readability.');
    expect(host.textContent).toContain('Before 85%');
    expect(host.textContent).toContain('Candidate 90%');
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
          canvasExpanded={false}
          layoutPlanning={false}
          layoutPreview={false}
          layoutQuality={null}
          layoutAcceptance={null}
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
          layoutNotice={null}
          canUndoLayout={false}
          onModeChange={vi.fn()}
          onSelectNode={onSelectNode}
          onToggleCanvasExpanded={vi.fn()}
          onZoomIn={vi.fn()}
          onZoomOut={vi.fn()}
          onFitAll={vi.fn()}
          onToggleMap={vi.fn()}
          onTogglePin={vi.fn()}
          onApplyLayout={vi.fn()}
          onOverrideLayout={vi.fn()}
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

  it('blocks a regressive candidate and isolates explicit override behind advanced review', async () => {
    const onApplyLayout = vi.fn();
    const onOverrideLayout = vi.fn();
    await act(async () => {
      root!.render(
        <CanvasTaskNavigator
          mode="overview"
          nodes={[]}
          selectedNodeId=""
          nodeCount={5}
          edgeCount={12}
          pathNodeCount={0}
          zoomPercent="39%"
          mapVisible={false}
          canvasExpanded={false}
          layoutPlanning={false}
          layoutPreview
          layoutQuality={{
            nodeOverlaps: 0,
            edgeLabelCollisions: 0,
            edgeLabelCollisionDetails: [],
            pinnedNodes: 0,
            status: 'PASS',
          }}
          layoutAcceptance={layoutAcceptance('ALTERNATIVE_REQUIRED')}
          perceptualQuality={layoutAcceptance('ALTERNATIVE_REQUIRED').candidate.perception}
          topologyLanes={[]}
          layoutNotice={null}
          canUndoLayout={false}
          onModeChange={vi.fn()}
          onSelectNode={vi.fn()}
          onToggleCanvasExpanded={vi.fn()}
          onZoomIn={vi.fn()}
          onZoomOut={vi.fn()}
          onFitAll={vi.fn()}
          onToggleMap={vi.fn()}
          onTogglePin={vi.fn()}
          onApplyLayout={onApplyLayout}
          onOverrideLayout={onOverrideLayout}
          onCancelLayout={vi.fn()}
          onUndoLayout={vi.fn()}
        />,
      );
    });

    const apply = host.querySelector<HTMLButtonElement>('[data-testid="layout-apply"]');
    expect(apply?.disabled).toBe(true);
    expect(host.textContent).toContain('85%');
    expect(host.textContent).toContain('39%');
    expect(host.textContent).toContain('Keep current layout');

    const advanced = host.querySelector<HTMLDetailsElement>('[data-testid="layout-override-review"]');
    advanced!.open = true;
    await act(async () => {
      host.querySelector<HTMLButtonElement>('[data-testid="layout-override"]')?.click();
    });
    expect(onApplyLayout).not.toHaveBeenCalled();
    expect(onOverrideLayout).toHaveBeenCalledOnce();
  });

  it('renders candidate quality evidence in Chinese without leaking English runtime prose', async () => {
    window.history.replaceState({}, '', '/?lang=zh-CN');
    const acceptance = layoutAcceptance('ALTERNATIVE_REQUIRED');
    acceptance.candidate.perception.reasons = [
      { code: 'NODE_OVERLAPS', count: 1 },
      { code: 'SMALL_GRAPH_ZOOM_FLOOR' },
    ];
    acceptance.candidate.geometry = {
      ...acceptance.candidate.geometry,
      nodeOverlaps: 1,
      status: 'REVIEW',
    };
    await act(async () => {
      root!.render(
        <I18nProvider>
          <CanvasTaskNavigator
            mode="overview"
            nodes={[]}
            selectedNodeId=""
            nodeCount={5}
            edgeCount={12}
            pathNodeCount={0}
            zoomPercent="39%"
            mapVisible={false}
            canvasExpanded
            layoutPlanning={false}
            layoutPreview
            layoutQuality={acceptance.candidate.geometry}
            layoutAcceptance={acceptance}
            perceptualQuality={acceptance.before.perception}
            topologyLanes={[]}
            layoutNotice={null}
            canUndoLayout={false}
            onModeChange={vi.fn()}
            onSelectNode={vi.fn()}
            onToggleCanvasExpanded={vi.fn()}
            onZoomIn={vi.fn()}
            onZoomOut={vi.fn()}
            onFitAll={vi.fn()}
            onToggleMap={vi.fn()}
            onTogglePin={vi.fn()}
            onApplyLayout={vi.fn()}
            onOverrideLayout={vi.fn()}
            onCancelLayout={vi.fn()}
            onUndoLayout={vi.fn()}
          />
        </I18nProvider>,
      );
    });

    expect(host.textContent).toContain('候选布局会降低当前可读性。');
    expect(host.textContent).toContain('候选 39%');
    expect(host.textContent).toContain('1 个节点重叠');
    expect(host.textContent).toContain('标题 5.9px');
    expect(host.textContent).toContain('需检查');
    expect(host.textContent).not.toContain('Geometry');
    expect(host.textContent).not.toContain('Perception');
  });
});
