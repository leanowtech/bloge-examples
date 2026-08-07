import type { CanvasNode } from '../../draftModel';
import type { CanvasPerceptualQualityReport } from './canvasSemantics';
import type { CanvasLayoutQualityReport } from './layoutQuality';

const CANVAS_NODE_WIDTH = 260;
const CANVAS_NODE_HEIGHT = 164;
const SMALL_GRAPH_MAX_NODES = 8;
const SMALL_GRAPH_MIN_ZOOM = 0.8;
const MIN_EFFECTIVE_TITLE_PX = 12;
const MAX_AREA_EXPANSION_RATIO = 1.25;

export type LayoutRegressionCode =
  | 'NODE_OVERLAP_REGRESSION'
  | 'EDGE_LABEL_COLLISION_REGRESSION'
  | 'PERCEPTION_REGRESSION'
  | 'SMALL_GRAPH_ZOOM_FLOOR'
  | 'TITLE_SIZE_FLOOR'
  | 'GRAPH_AREA_EXPANSION';

export interface LayoutQualitySnapshot {
  geometry: CanvasLayoutQualityReport;
  perception: CanvasPerceptualQualityReport;
  zoom: number;
  graphArea: number;
}

export interface LayoutRegression {
  code: LayoutRegressionCode;
  before: number | string;
  candidate: number | string;
}

export type LayoutAcceptanceStrategy =
  | 'KEEP_CURRENT'
  | 'COMPACT_LANES'
  | 'FOCUS_PATH'
  | 'OVERVIEW_ONLY';

export interface LayoutAcceptanceDecision {
  decision: 'ACCEPTABLE' | 'ALTERNATIVE_REQUIRED' | 'EXPLICIT_OVERRIDE';
  before: LayoutQualitySnapshot;
  candidate: LayoutQualitySnapshot;
  regressions: LayoutRegression[];
  recommendedStrategy: LayoutAcceptanceStrategy;
  overrideReason?: string;
}

interface FitZoomOptions {
  viewportWidth: number;
  viewportHeight: number;
  padding: number;
  maxZoom: number;
  minZoom?: number;
}

export function projectLayoutQualitySnapshot(
  geometry: CanvasLayoutQualityReport,
  perception: CanvasPerceptualQualityReport,
  zoom: number,
  graphArea: number,
): LayoutQualitySnapshot {
  return {
    geometry,
    perception,
    zoom: finiteNonNegative(zoom),
    graphArea: finiteNonNegative(graphArea),
  };
}

/** Prevents Auto Layout from silently exchanging a readable graph for a weaker candidate. */
export function decideLayoutAcceptance(
  before: LayoutQualitySnapshot,
  candidate: LayoutQualitySnapshot,
  nodeCount: number,
): LayoutAcceptanceDecision {
  const regressions: LayoutRegression[] = [];
  if (candidate.geometry.nodeOverlaps > before.geometry.nodeOverlaps) {
    regressions.push(regression(
      'NODE_OVERLAP_REGRESSION',
      before.geometry.nodeOverlaps,
      candidate.geometry.nodeOverlaps,
    ));
  }
  if (candidate.geometry.edgeLabelCollisions > before.geometry.edgeLabelCollisions) {
    regressions.push(regression(
      'EDGE_LABEL_COLLISION_REGRESSION',
      before.geometry.edgeLabelCollisions,
      candidate.geometry.edgeLabelCollisions,
    ));
  }
  if (qualityRank(candidate.perception.status) < qualityRank(before.perception.status)) {
    regressions.push(regression(
      'PERCEPTION_REGRESSION',
      before.perception.status,
      candidate.perception.status,
    ));
  }
  if (nodeCount <= SMALL_GRAPH_MAX_NODES && candidate.zoom < SMALL_GRAPH_MIN_ZOOM) {
    regressions.push(regression(
      'SMALL_GRAPH_ZOOM_FLOOR',
      before.zoom,
      candidate.zoom,
    ));
  }
  if (
    nodeCount <= SMALL_GRAPH_MAX_NODES
    && candidate.perception.effectiveTitleFontPx < MIN_EFFECTIVE_TITLE_PX
  ) {
    regressions.push(regression(
      'TITLE_SIZE_FLOOR',
      before.perception.effectiveTitleFontPx,
      candidate.perception.effectiveTitleFontPx,
    ));
  }
  if (
    before.graphArea > 0
    && candidate.graphArea > before.graphArea * MAX_AREA_EXPANSION_RATIO
  ) {
    regressions.push(regression(
      'GRAPH_AREA_EXPANSION',
      before.graphArea,
      candidate.graphArea,
    ));
  }

  return {
    decision: regressions.length === 0 ? 'ACCEPTABLE' : 'ALTERNATIVE_REQUIRED',
    before,
    candidate,
    regressions,
    recommendedStrategy: regressions.length === 0 ? 'COMPACT_LANES' : 'KEEP_CURRENT',
  };
}

export function overrideLayoutAcceptance(
  decision: LayoutAcceptanceDecision,
  reason: string,
): LayoutAcceptanceDecision {
  const normalizedReason = reason.trim();
  if (!normalizedReason) {
    throw new Error('A layout override reason is required.');
  }
  if (decision.decision === 'ACCEPTABLE') {
    throw new Error('An acceptable layout does not require an override.');
  }
  return {
    ...decision,
    decision: 'EXPLICIT_OVERRIDE',
    overrideReason: normalizedReason,
  };
}

export function estimateCanvasFitZoom(
  nodes: CanvasNode[],
  options: FitZoomOptions,
): number {
  const minZoom = Math.max(0, options.minZoom ?? 0);
  const maxZoom = Math.max(minZoom, finiteNonNegative(options.maxZoom));
  if (nodes.length === 0) return maxZoom;

  const left = Math.min(...nodes.map((node) => node.position.x));
  const top = Math.min(...nodes.map((node) => node.position.y));
  const right = Math.max(...nodes.map((node) => node.position.x + CANVAS_NODE_WIDTH));
  const bottom = Math.max(...nodes.map((node) => node.position.y + CANVAS_NODE_HEIGHT));
  const paddingFactor = 1 + Math.max(0, options.padding) * 2;
  const widthZoom = finitePositive(options.viewportWidth) / Math.max(1, right - left) / paddingFactor;
  const heightZoom = finitePositive(options.viewportHeight) / Math.max(1, bottom - top) / paddingFactor;
  return Math.min(maxZoom, Math.max(minZoom, Math.min(widthZoom, heightZoom)));
}

export function canvasGraphArea(nodes: CanvasNode[]): number {
  if (nodes.length === 0) return 0;
  const left = Math.min(...nodes.map((node) => node.position.x));
  const top = Math.min(...nodes.map((node) => node.position.y));
  const right = Math.max(...nodes.map((node) => node.position.x + CANVAS_NODE_WIDTH));
  const bottom = Math.max(...nodes.map((node) => node.position.y + CANVAS_NODE_HEIGHT));
  return Math.max(0, right - left) * Math.max(0, bottom - top);
}

function regression(
  code: LayoutRegressionCode,
  before: number | string,
  candidate: number | string,
): LayoutRegression {
  return { code, before, candidate };
}

function qualityRank(status: CanvasPerceptualQualityReport['status']): number {
  return status === 'PASS' ? 1 : 0;
}

function finitePositive(value: number): number {
  return Number.isFinite(value) && value > 0 ? value : 1;
}

function finiteNonNegative(value: number): number {
  return Number.isFinite(value) ? Math.max(0, value) : 0;
}
