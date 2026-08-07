import { describe, expect, it } from 'vitest';

import type { CanvasNode } from '../../draftModel';
import type { CanvasPerceptualQualityReport } from './canvasSemantics';
import type { CanvasLayoutQualityReport } from './layoutQuality';
import {
  decideLayoutAcceptance,
  estimateCanvasFitZoom,
  overrideLayoutAcceptance,
  projectLayoutQualitySnapshot,
} from './layoutAcceptance';

const passGeometry: CanvasLayoutQualityReport = {
  nodeOverlaps: 0,
  edgeLabelCollisions: 0,
  edgeLabelCollisionDetails: [],
  pinnedNodes: 0,
  status: 'PASS',
};

function perception(
  status: 'PASS' | 'REVIEW',
  zoom: number,
  overrides: Partial<CanvasPerceptualQualityReport> = {},
): CanvasPerceptualQualityReport {
  return {
    status,
    geometryStatus: 'PASS',
    mode: 'overview',
    nodeOverlaps: 0,
    nodeLabelCollisions: 0,
    labelLabelCollisions: 0,
    effectiveTitleFontPx: 15 * zoom,
    visibleNodeLabels: 5,
    visibleEdgeLabels: 4,
    visibleFieldLabels: 0,
    labelDensityPer100kPx: 1.2,
    graphScreenOccupancy: 0.42,
    reasons: [],
    ...overrides,
  };
}

describe('LayoutAcceptanceGate', () => {
  it('accepts a candidate that preserves geometry and perceptual quality', () => {
    const before = projectLayoutQualitySnapshot(passGeometry, perception('PASS', 0.85), 0.85, 100_000);
    const candidate = projectLayoutQualitySnapshot(passGeometry, perception('PASS', 0.9), 0.9, 110_000);

    expect(decideLayoutAcceptance(before, candidate, 5)).toMatchObject({
      decision: 'ACCEPTABLE',
      regressions: [],
      recommendedStrategy: 'COMPACT_LANES',
    });
  });

  it('rejects the observed small-graph regression even when geometry still passes', () => {
    const before = projectLayoutQualitySnapshot(passGeometry, perception('PASS', 0.85), 0.85, 100_000);
    const candidate = projectLayoutQualitySnapshot(
      passGeometry,
      perception('REVIEW', 0.39, { effectiveTitleFontPx: 5.8 }),
      0.39,
      270_000,
    );

    const result = decideLayoutAcceptance(before, candidate, 5);

    expect(result.decision).toBe('ALTERNATIVE_REQUIRED');
    expect(result.regressions.map((regression) => regression.code)).toEqual([
      'PERCEPTION_REGRESSION',
      'SMALL_GRAPH_ZOOM_FLOOR',
      'TITLE_SIZE_FLOOR',
      'GRAPH_AREA_EXPANSION',
    ]);
    expect(result.recommendedStrategy).toBe('KEEP_CURRENT');
  });

  it('rejects newly introduced node or edge-label collisions', () => {
    const before = projectLayoutQualitySnapshot(passGeometry, perception('PASS', 1), 1, 100_000);
    const candidateGeometry: CanvasLayoutQualityReport = {
      ...passGeometry,
      nodeOverlaps: 1,
      edgeLabelCollisions: 2,
      status: 'REVIEW',
    };
    const candidate = projectLayoutQualitySnapshot(
      candidateGeometry,
      perception('REVIEW', 0.9, { geometryStatus: 'REVIEW' }),
      0.9,
      100_000,
    );

    expect(decideLayoutAcceptance(before, candidate, 12).regressions.map(({ code }) => code))
      .toEqual(['NODE_OVERLAP_REGRESSION', 'EDGE_LABEL_COLLISION_REGRESSION', 'PERCEPTION_REGRESSION']);
  });

  it('requires an explicit, auditable reason to override a rejected candidate', () => {
    const before = projectLayoutQualitySnapshot(passGeometry, perception('PASS', 0.85), 0.85, 100_000);
    const candidate = projectLayoutQualitySnapshot(
      passGeometry,
      perception('REVIEW', 0.7, { effectiveTitleFontPx: 10.5 }),
      0.7,
      110_000,
    );
    const rejected = decideLayoutAcceptance(before, candidate, 5);

    expect(() => overrideLayoutAcceptance(rejected, '')).toThrow(/reason/i);
    expect(overrideLayoutAcceptance(rejected, 'USER_ACCEPTED_READABILITY_REGRESSION')).toMatchObject({
      decision: 'EXPLICIT_OVERRIDE',
      overrideReason: 'USER_ACCEPTED_READABILITY_REGRESSION',
    });
  });

  it('estimates fit zoom from stable node bounds and viewport padding', () => {
    const compact: CanvasNode[] = [
      { id: 'a', operatorRef: 'a', position: { x: 0, y: 0 } },
      { id: 'b', operatorRef: 'b', position: { x: 300, y: 0 } },
    ];
    const stretched: CanvasNode[] = [
      compact[0],
      { ...compact[1], position: { x: 1_800, y: 0 } },
    ];

    expect(estimateCanvasFitZoom(compact, {
      viewportWidth: 1_000,
      viewportHeight: 600,
      padding: 0.1,
      maxZoom: 1,
    })).toBe(1);
    expect(estimateCanvasFitZoom(stretched, {
      viewportWidth: 1_000,
      viewportHeight: 600,
      padding: 0.1,
      maxZoom: 1,
    })).toBeCloseTo(0.4, 1);
  });
});
