import { describe, expect, it } from 'vitest';

import { CANVAS_EXAMPLE_TEMPLATES } from '../../canvasExamples';
import type { CanvasEdge, CanvasNode } from '../../draftModel';
import {
  adaptiveCanvasChromePolicy,
  assessCanvasPerceptualQuality,
  deriveCanvasTopologyLanes,
  projectCanvasSemantics,
} from './canvasSemantics';

const nodes: CanvasNode[] = [
  { id: 'source', operatorRef: 'source', label: 'Source', position: { x: 0, y: 0 } },
  { id: 'decision', operatorRef: 'decision', label: 'Decision', position: { x: 300, y: 0 } },
  { id: 'output', operatorRef: 'output', label: 'Output', position: { x: 600, y: 0 } },
  { id: 'side', operatorRef: 'side', label: 'Side', position: { x: 300, y: 236 } },
];
const edges: CanvasEdge[] = [
  {
    id: 'source-score',
    source: 'source',
    target: 'decision',
    sourcePath: 'payload.score',
    targetPath: 'inputs.score',
  },
  {
    id: 'source-tier',
    source: 'source',
    target: 'decision',
    sourcePath: 'payload.tier',
    targetPath: 'inputs.tier',
  },
  {
    id: 'decision-output',
    source: 'decision',
    target: 'output',
    sourcePath: 'decision',
    targetPath: 'result.decision',
  },
  {
    id: 'source-side',
    source: 'source',
    target: 'side',
    sourcePath: 'payload.id',
    targetPath: 'input.id',
  },
];

describe('canvas semantic projection', () => {
  it('keeps Overview topology-only and bundles parallel field edges in Inspect', () => {
    const overview = projectCanvasSemantics(nodes, edges, { mode: 'overview' });
    expect(overview.visibleEdgeLabelCount).toBe(0);
    expect(overview.visibleFieldCount).toBe(0);

    const inspect = projectCanvasSemantics(nodes, edges, {
      mode: 'inspect',
      selectedNodeId: 'decision',
    });
    expect(inspect.edgeLabels.get('source-score')).toMatchObject({
      text: '3 fields / 2 targets · score, tier, id -> score, tier, id',
      fieldCount: 3,
      bundledEdgeIds: ['source-score', 'source-tier', 'source-side'],
    });
    expect(inspect.edgeLabels.has('source-tier')).toBe(false);
    expect(inspect.edgeLabels.has('source-side')).toBe(false);
    expect(inspect.visibleFieldCount).toBe(4);
    expect(inspect.detailedNodeIds).toEqual(new Set(['decision']));
  });

  it('shows only the Focus closure, with deterministic collision-free label lanes', () => {
    const focus = projectCanvasSemantics(nodes, edges, {
      mode: 'focus',
      anchorNodeId: 'decision',
      selectedNodeId: 'decision',
    });
    expect([...focus.edgeLabels.values()].flatMap((label) => label.bundledEdgeIds))
      .toEqual(['source-score', 'source-tier', 'decision-output']);
    expect(focus.hiddenEdgeLabelCount).toBe(1);
    expect(focus.labelBudget).toBe(12);
    expect(focus.detailedNodeIds).toEqual(new Set(['decision', 'output', 'source']));
  });

  it('balances competing long labels across the top and bottom safety bands', () => {
    const loanNodes: CanvasNode[] = [
      { id: 'n1', operatorRef: 'profile', label: 'Profile', position: { x: 96, y: 190 } },
      { id: 'n2', operatorRef: 'primary', label: 'Primary', position: { x: 384, y: 72 } },
      { id: 'n3', operatorRef: 'secondary', label: 'Secondary', position: { x: 384, y: 308 } },
      { id: 'n4', operatorRef: 'policy', label: 'Policy', position: { x: 672, y: 190 } },
      { id: 'n5', operatorRef: 'response', label: 'Response', position: { x: 960, y: 190 } },
    ];
    const loanEdges: CanvasEdge[] = [
      ...['applicantId', 'income', 'employmentYears', 'segment', 'region', 'riskBand']
        .map((field, index) => ({
          id: `profile-${field}`,
          source: 'n1',
          target: ['n2', 'n3', 'n4', 'n5'][index % 4],
          sourcePath: `payload.${field}`,
          targetPath: `inputs.${field}`,
        })),
      ...['score', 'confidence'].map((field, index) => ({
        id: `primary-${field}`,
        source: 'n2',
        target: index === 0 ? 'n4' : 'n5',
        sourcePath: `payload.${field}`,
        targetPath: `inputs.${field}`,
      })),
    ];

    const inspect = projectCanvasSemantics(loanNodes, loanEdges, { mode: 'inspect' });
    const profileLabel = inspect.edgeLabels.get('profile-applicantId');
    const primaryLabel = inspect.edgeLabels.get('primary-score');

    expect(profileLabel?.y).toBeLessThan(72);
    expect(primaryLabel?.y).toBeGreaterThan(472);
    expect(inspect.nodeLabelCollisionCount).toBe(0);
    expect(inspect.labelLabelCollisionCount).toBe(0);
  });

  it('keeps every visible label in the complete loan example outside an 8px safety band', () => {
    const loanExample = CANVAS_EXAMPLE_TEMPLATES.find((example) => (
      example.key === 'loan-policy-fallback'
    ));
    expect(loanExample).toBeDefined();

    const inspect = projectCanvasSemantics(
      loanExample!.nodes,
      loanExample!.edges,
      { mode: 'inspect' },
    );
    const labelBoxes = [...inspect.edgeLabels.values()].map((label) => {
      const width = Math.min(320, Math.max(104, label.text.length * 6.2 + 24));
      return {
        left: label.x - width / 2 - 8,
        top: label.y - 15 - 8,
        right: label.x + width / 2 + 8,
        bottom: label.y + 15 + 8,
      };
    });

    for (let left = 0; left < labelBoxes.length; left += 1) {
      for (let right = left + 1; right < labelBoxes.length; right += 1) {
        expect(
          labelBoxes[left].left < labelBoxes[right].right
          && labelBoxes[left].right > labelBoxes[right].left
          && labelBoxes[left].top < labelBoxes[right].bottom
          && labelBoxes[left].bottom > labelBoxes[right].top,
        ).toBe(false);
      }
    }
  });

  it('builds topological lanes for 100 nodes in linear time and keeps cyclic nodes bounded', () => {
    const largeNodes = Array.from({ length: 100 }, (_, index) => ({
      id: `n${index}`,
      operatorRef: `operator:${index}`,
      label: `Node ${index}`,
      position: { x: index * 20, y: 0 },
    }));
    const largeEdges = Array.from({ length: 99 }, (_, index) => ({
      id: `e${index}`,
      source: `n${index}`,
      target: `n${index + 1}`,
    }));
    largeEdges.push({ id: 'cycle', source: 'n99', target: 'n98' });

    const startedAt = performance.now();
    const lanes = deriveCanvasTopologyLanes(largeNodes, largeEdges);

    expect(performance.now() - startedAt).toBeLessThan(50);
    expect(lanes.flatMap((lane) => lane.nodeIds)).toHaveLength(100);
    expect(new Set(lanes.flatMap((lane) => lane.nodeIds)).size).toBe(100);
    expect(lanes[0].label).toBe('Inputs');
  });
});

describe('canvas perceptual quality and adaptive chrome', () => {
  it('fails a geometrically possible small graph when the effective text is unreadable', () => {
    const report = assessCanvasPerceptualQuality(nodes, {
      mode: 'inspect',
      viewportWidth: 1024,
      viewportHeight: 720,
      zoom: 0.64,
      visibleEdgeLabels: 3,
      visibleFieldLabels: 4,
      nodeOverlaps: 0,
      nodeLabelCollisions: 0,
      labelLabelCollisions: 0,
    });
    expect(report).toMatchObject({
      status: 'REVIEW',
      effectiveTitleFontPx: 9.6,
      visibleNodeLabels: 4,
    });
    expect(report.reasons).toContainEqual({ code: 'SMALL_GRAPH_ZOOM_FLOOR' });
  });

  it('passes a topology-only Overview and protects explicit panel preferences', () => {
    expect(assessCanvasPerceptualQuality(nodes, {
      mode: 'overview',
      viewportWidth: 1024,
      viewportHeight: 720,
      zoom: 0.82,
      visibleEdgeLabels: 0,
      visibleFieldLabels: 0,
      nodeOverlaps: 0,
      nodeLabelCollisions: 0,
      labelLabelCollisions: 0,
    }).status).toBe('PASS');

    expect(adaptiveCanvasChromePolicy({
      authorMode: 'compose',
      compactWorkspace: false,
      nodeCount: 5,
      fitZoom: 0.68,
      selectedNodeId: 'decision',
      palettePreference: 'auto',
      inspectorPreference: 'open',
    })).toEqual({
      collapsePalette: true,
      collapseInspector: false,
      reason: 'READABILITY_FLOOR',
    });
  });

  it('gives formal task surfaces the full workspace unless context was explicitly pinned', () => {
    expect(adaptiveCanvasChromePolicy({
      authorMode: 'evidence',
      compactWorkspace: true,
      nodeCount: 5,
      fitZoom: 1,
      selectedNodeId: 'decision',
      palettePreference: 'auto',
      inspectorPreference: 'auto',
    })).toEqual({
      collapsePalette: true,
      collapseInspector: true,
      reason: 'TASK_SURFACE',
    });

    expect(adaptiveCanvasChromePolicy({
      authorMode: 'evidence',
      compactWorkspace: true,
      nodeCount: 5,
      fitZoom: 1,
      selectedNodeId: 'decision',
      palettePreference: 'auto',
      inspectorPreference: 'open',
    }).collapseInspector).toBe(false);
  });
});
