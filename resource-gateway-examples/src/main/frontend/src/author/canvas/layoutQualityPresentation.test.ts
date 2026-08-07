import { describe, expect, it } from 'vitest';

import { translateMessage } from '../../i18n/messageCatalog';
import type { CanvasPerceptualQualityReport } from './canvasSemantics';
import {
  presentCanvasQuality,
  presentLayoutCollision,
} from './layoutQualityPresentation';

describe('layout quality presentation', () => {
  it('keeps geometry and perception as structured localized metrics', () => {
    const presentation = presentCanvasQuality({
      nodeOverlaps: 0,
      edgeLabelCollisions: 2,
      edgeLabelCollisionDetails: [],
      pinnedNodes: 1,
      status: 'REVIEW',
    }, perception());

    expect(presentation.geometry).toEqual({
      messageId: 'layout.quality.geometrySummary',
      params: { overlaps: 0, collisions: 2, pinned: 1 },
    });
    expect(translateMessage(
      'en',
      presentation.geometry.messageId,
      presentation.geometry.params,
    )).toBe('Node overlaps 0 · label collisions 2 · pinned nodes 1');
    expect(translateMessage(
      'zh-CN',
      presentation.perception.messageId,
      presentation.perception.params,
    )).toBe('几何通过 · 可读性需检查 · 标题 10.5px · 4 个连线标签 · 密度 8.2/10万 px');
  });

  it('projects reason codes and collision coordinates without English sentence parsing', () => {
    const presentation = presentCanvasQuality({
      nodeOverlaps: 1,
      edgeLabelCollisions: 1,
      edgeLabelCollisionDetails: [],
      pinnedNodes: 0,
      status: 'REVIEW',
    }, perception());

    expect(presentation.reasons).toEqual([
      { messageId: 'layout.quality.reason.nodeOverlaps', params: { count: 1 } },
      { messageId: 'layout.quality.reason.smallGraphZoom', params: undefined },
    ]);
    const collision = presentLayoutCollision({
      edgeId: 'profile-to-policy',
      nodeId: 'review',
      label: 'payload.score -> input.score',
    });
    expect(translateMessage('zh-CN', collision.messageId, collision.params))
      .toBe('连线 profile-to-policy 的标签与节点 review 相交。');
  });
});

function perception(): CanvasPerceptualQualityReport {
  return {
    status: 'REVIEW',
    geometryStatus: 'PASS',
    mode: 'inspect',
    nodeOverlaps: 1,
    nodeLabelCollisions: 0,
    labelLabelCollisions: 0,
    effectiveTitleFontPx: 10.5,
    visibleNodeLabels: 5,
    visibleEdgeLabels: 4,
    visibleFieldLabels: 6,
    labelDensityPer100kPx: 8.2,
    graphScreenOccupancy: 0.45,
    reasons: [
      { code: 'NODE_OVERLAPS', count: 1 },
      { code: 'SMALL_GRAPH_ZOOM_FLOOR' },
    ],
  };
}
