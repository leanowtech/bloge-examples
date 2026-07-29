import { describe, expect, it } from 'vitest';

import type { CanvasEdge, CanvasNode } from '../../draftModel';
import { assessCanvasLayout, constrainCanvasLayout } from './layoutQuality';

const nodes: CanvasNode[] = [
  { id: 'source', operatorRef: 'source', position: { x: 20, y: 40 } },
  { id: 'middle', operatorRef: 'middle', position: { x: 380, y: 40 } },
  { id: 'target', operatorRef: 'target', position: { x: 740, y: 40 } },
];

describe('canvas layout constraints and quality', () => {
  it('keeps every pinned node exact while resolving generated card collisions', () => {
    const candidate: CanvasNode[] = [
      { ...nodes[0], position: { x: 96, y: 72 } },
      { ...nodes[1], position: { x: 96, y: 72 } },
      { ...nodes[2], position: { x: 504, y: 72 } },
    ];

    const constrained = constrainCanvasLayout(nodes, candidate, new Set(['source', 'target']));

    expect(constrained.find((node) => node.id === 'source')?.position).toEqual({ x: 20, y: 40 });
    expect(constrained.find((node) => node.id === 'target')?.position).toEqual({ x: 740, y: 40 });
    expect(assessCanvasLayout(constrained, [], new Set(['source', 'target']))).toMatchObject({
      nodeOverlaps: 0,
      pinnedNodes: 2,
      status: 'PASS',
    });
  });

  it('reports node overlap and adjacent edge-label collision without mutating input', () => {
    const crowded: CanvasNode[] = [
      { ...nodes[0], position: { x: 0, y: 0 } },
      { ...nodes[1], position: { x: 100, y: 0 } },
      { ...nodes[2], position: { x: 310, y: 0 } },
    ];
    const before = structuredClone(crowded);

    const report = assessCanvasLayout(crowded, [{
      id: 'source-target',
      source: 'source',
      target: 'target',
      sourcePort: 'payload',
      sourcePath: 'customer.primaryRiskSignal',
      targetPort: 'inputs',
      targetPath: 'decision.riskSignal',
    }]);

    expect(report).toMatchObject({
      nodeOverlaps: 2,
      edgeLabelCollisions: 1,
      status: 'REVIEW',
    });
    expect(report.edgeLabelCollisionDetails).toEqual([
      expect.objectContaining({ edgeId: 'source-target', nodeId: 'middle' }),
    ]);
    expect(crowded).toEqual(before);
  });

  it('moves an unrelated node out of an edge label box while preserving a pinned endpoint', () => {
    const crowded: CanvasNode[] = [
      { id: 'source', operatorRef: 'source', position: { x: 0, y: 0 } },
      { id: 'target', operatorRef: 'target', position: { x: 408, y: 0 } },
      { id: 'intruder', operatorRef: 'intruder', position: { x: 282, y: 60 } },
    ];
    const edges: CanvasEdge[] = [{
      id: 'source-target',
      source: 'source',
      target: 'target',
      sourcePort: 'payload',
      targetPort: 'inputs',
    }];

    const constrained = constrainCanvasLayout(crowded, crowded, new Set(['target']), edges);
    const report = assessCanvasLayout(constrained, edges, new Set(['target']));

    expect(constrained.find((node) => node.id === 'target')?.position).toEqual({ x: 408, y: 0 });
    expect(constrained.find((node) => node.id === 'intruder')?.position.y).toBeGreaterThan(60);
    expect(report).toMatchObject({
      nodeOverlaps: 0,
      edgeLabelCollisions: 0,
      pinnedNodes: 1,
      status: 'PASS',
    });
  });

  it('returns deterministic copies when no pins are configured', () => {
    const candidate = nodes.map((node) => ({ ...node, position: { ...node.position } }));

    const first = constrainCanvasLayout(nodes, candidate, new Set());
    const second = constrainCanvasLayout(nodes, candidate, new Set());

    expect(first).toEqual(second);
    expect(first).not.toBe(candidate);
    expect(first[0]).not.toBe(candidate[0]);
  });
});
