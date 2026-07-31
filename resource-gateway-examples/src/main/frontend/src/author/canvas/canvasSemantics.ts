import {
  canvasFocusPath,
  type CanvasEdge,
  type CanvasNode,
} from '../../draftModel';

export type CanvasSemanticMode = 'overview' | 'focus' | 'inspect';

export interface CanvasSemanticEdgeLabel {
  edgeId: string;
  text: string;
  title: string;
  lane: number;
  x: number;
  y: number;
  bundledEdgeIds: string[];
  fieldCount: number;
}

export interface CanvasTopologyLane {
  id: string;
  depth: number;
  label: string;
  nodeIds: string[];
  representativeNodeId: string;
}

export interface CanvasSemanticProjection {
  mode: CanvasSemanticMode;
  edgeLabels: Map<string, CanvasSemanticEdgeLabel>;
  detailedNodeIds: Set<string>;
  visibleEdgeLabelCount: number;
  visibleFieldCount: number;
  hiddenEdgeLabelCount: number;
  suppressedForCollisionCount: number;
  nodeLabelCollisionCount: number;
  labelLabelCollisionCount: number;
  labelBudget: number;
  lanes: CanvasTopologyLane[];
}

export interface CanvasPerceptualQualityReport {
  status: 'PASS' | 'REVIEW';
  geometryStatus: 'PASS' | 'REVIEW';
  mode: CanvasSemanticMode;
  nodeOverlaps: number;
  nodeLabelCollisions: number;
  labelLabelCollisions: number;
  effectiveTitleFontPx: number;
  visibleNodeLabels: number;
  visibleEdgeLabels: number;
  visibleFieldLabels: number;
  labelDensityPer100kPx: number;
  graphScreenOccupancy: number;
  reasons: string[];
  summary: string;
}

export type CanvasPanelPreference = 'auto' | 'open' | 'closed';

export interface AdaptiveCanvasChromePolicy {
  collapsePalette: boolean;
  collapseInspector: boolean;
  reason: string;
}

interface SemanticProjectionOptions {
  mode: CanvasSemanticMode;
  anchorNodeId?: string;
  selectedNodeId?: string;
}

interface PerceptualQualityOptions {
  mode: CanvasSemanticMode;
  viewportWidth: number;
  viewportHeight: number;
  zoom: number;
  visibleEdgeLabels: number;
  visibleFieldLabels: number;
  nodeOverlaps: number;
  nodeLabelCollisions: number;
  labelLabelCollisions: number;
}

interface AdaptiveChromeOptions {
  authorMode: 'compose' | 'contract' | 'scenarios' | 'evidence';
  compactWorkspace: boolean;
  nodeCount: number;
  fitZoom: number;
  selectedNodeId: string;
  palettePreference: CanvasPanelPreference;
  inspectorPreference: CanvasPanelPreference;
}

interface LabelCandidate {
  representative: CanvasEdge;
  edges: CanvasEdge[];
  text: string;
  title: string;
  priority: number;
}

interface Box {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

const NODE_WIDTH = 260;
const NODE_HEIGHT = 164;
const LABEL_HEIGHT = 30;
const LABEL_CHAR_WIDTH = 6.2;
const LABEL_MIN_WIDTH = 104;
const LABEL_MAX_WIDTH = 320;
const LABEL_LANE_STEP = 42;
const LABEL_CANVAS_NUDGE_Y = 16;
const LABEL_LANES = [
  0,
  -1, 1,
  -2, 2,
  -3, 3,
  -4, 4,
  -5, 5,
  -6, 6,
  -7, 7,
  -8, 8,
  -9, 9,
  -10, 10,
  -11, 11,
  -12, 12,
];
const LABEL_NODE_CLEARANCE = 8;
const FOCUS_LABEL_BUDGET = 12;

/**
 * Projects one graph into a task-specific visual vocabulary.
 *
 * Overview is topology-only. Focus is closure-only and budgeted. Inspect keeps exact field
 * semantics, but aggregates parallel data edges into one collision-free bundle.
 */
export function projectCanvasSemantics(
  nodes: CanvasNode[],
  edges: CanvasEdge[],
  options: SemanticProjectionOptions,
): CanvasSemanticProjection {
  const nodeIds = new Set(nodes.map((node) => node.id));
  const selectedNodeId = options.selectedNodeId?.trim() ?? '';
  const focus = options.mode === 'focus'
    ? canvasFocusPath(nodes, edges, options.anchorNodeId?.trim() ?? selectedNodeId)
    : { nodeIds, edgeIds: new Set(edges.map((edge) => edge.id)) };
  const detailedNodeIds = options.mode === 'overview'
    ? new Set<string>()
    : options.mode === 'focus'
      ? focus.nodeIds
      : new Set(selectedNodeId ? [selectedNodeId] : []);
  const labelBudget = options.mode === 'overview'
    ? 0
    : options.mode === 'focus'
      ? FOCUS_LABEL_BUDGET
      : inspectLabelBudget(nodes.length);
  const lanes = deriveCanvasTopologyLanes(nodes, edges);

  if (labelBudget === 0) {
    return {
      mode: options.mode,
      edgeLabels: new Map(),
      detailedNodeIds,
      visibleEdgeLabelCount: 0,
      visibleFieldCount: 0,
      hiddenEdgeLabelCount: edges.length,
      suppressedForCollisionCount: 0,
      nodeLabelCollisionCount: 0,
      labelLabelCollisionCount: 0,
      labelBudget,
      lanes,
    };
  }

  const candidates = labelCandidates(
    edges.filter((edge) => options.mode !== 'focus' || focus.edgeIds.has(edge.id)),
    selectedNodeId,
  );
  const nodeById = new Map(nodes.map((node) => [node.id, node]));
  const viewportFitNodes = options.mode === 'focus' && focus.nodeIds.size > 0
    ? nodes.filter((node) => focus.nodeIds.has(node.id))
    : nodes;
  const occupiedLabels: Box[] = [];
  const edgeLabels = new Map<string, CanvasSemanticEdgeLabel>();
  let nodeLabelCollisionCount = 0;
  let labelLabelCollisionCount = 0;

  for (const candidate of candidates.slice(0, labelBudget)) {
    const placement = firstCollisionFreeLane(
      candidate,
      nodeById,
      nodes,
      viewportFitNodes,
      occupiedLabels,
    );
    if (placement.lane === null) {
      if (placement.blockedByNodes) nodeLabelCollisionCount += 1;
      if (placement.blockedByLabels) labelLabelCollisionCount += 1;
      continue;
    }
    const labelBox = edgeLabelBox(candidate, nodeById, placement.lane);
    if (labelBox) occupiedLabels.push(labelBox);
    edgeLabels.set(candidate.representative.id, {
      edgeId: candidate.representative.id,
      text: candidate.text,
      title: candidate.title,
      lane: placement.lane,
      x: labelBox ? (labelBox.left + labelBox.right) / 2 : 0,
      y: labelBox ? (labelBox.top + labelBox.bottom) / 2 : 0,
      bundledEdgeIds: candidate.edges.map((edge) => edge.id),
      fieldCount: candidate.edges.length,
    });
  }

  const visibleFieldCount = [...edgeLabels.values()]
    .reduce((total, label) => total + label.fieldCount, 0);
  const consideredCandidateCount = Math.min(candidates.length, labelBudget);
  return {
    mode: options.mode,
    edgeLabels,
    detailedNodeIds,
    visibleEdgeLabelCount: edgeLabels.size,
    visibleFieldCount,
    hiddenEdgeLabelCount: Math.max(0, edges.length - visibleFieldCount),
    suppressedForCollisionCount: Math.max(0, consideredCandidateCount - edgeLabels.size),
    nodeLabelCollisionCount,
    labelLabelCollisionCount,
    labelBudget,
    lanes,
  };
}

/** Groups a large DAG into stable topological columns used by the overview navigator. */
export function deriveCanvasTopologyLanes(
  nodes: CanvasNode[],
  edges: CanvasEdge[],
): CanvasTopologyLane[] {
  const nodeIds = new Set(nodes.map((node) => node.id));
  const indegree = new Map(nodes.map((node) => [node.id, 0]));
  const outgoing = new Map(nodes.map((node) => [node.id, [] as string[]]));
  for (const edge of edges) {
    if (!nodeIds.has(edge.source) || !nodeIds.has(edge.target)) continue;
    indegree.set(edge.target, (indegree.get(edge.target) ?? 0) + 1);
    outgoing.get(edge.source)?.push(edge.target);
  }
  const depth = new Map(nodes.map((node) => [node.id, 0]));
  const queue = nodes.filter((node) => (indegree.get(node.id) ?? 0) === 0).map((node) => node.id);
  const visited = new Set<string>();
  for (let index = 0; index < queue.length; index += 1) {
    const nodeId = queue[index];
    visited.add(nodeId);
    for (const target of outgoing.get(nodeId) ?? []) {
      depth.set(target, Math.max(depth.get(target) ?? 0, (depth.get(nodeId) ?? 0) + 1));
      indegree.set(target, (indegree.get(target) ?? 0) - 1);
      if ((indegree.get(target) ?? 0) === 0) queue.push(target);
    }
  }

  const grouped = new Map<number, CanvasNode[]>();
  for (const node of nodes) {
    const laneDepth = visited.has(node.id) ? depth.get(node.id) ?? 0 : 0;
    grouped.set(laneDepth, [...(grouped.get(laneDepth) ?? []), node]);
  }
  const maximumDepth = Math.max(0, ...grouped.keys());
  return [...grouped.entries()]
    .sort(([left], [right]) => left - right)
    .map(([laneDepth, laneNodes]) => ({
      id: `lane-${laneDepth}`,
      depth: laneDepth,
      label: laneLabel(laneDepth, maximumDepth),
      nodeIds: laneNodes.map((node) => node.id),
      representativeNodeId: laneNodes[0]?.id ?? '',
    }));
}

/** Adds perceptual readability to geometric quality without relying on browser-only measurements. */
export function assessCanvasPerceptualQuality(
  nodes: CanvasNode[],
  options: PerceptualQualityOptions,
): CanvasPerceptualQualityReport {
  const viewportWidth = Math.max(1, options.viewportWidth);
  const viewportHeight = Math.max(1, options.viewportHeight);
  const zoom = Number.isFinite(options.zoom) ? Math.max(0, options.zoom) : 1;
  const effectiveTitleFontPx = round(15 * zoom, 1);
  const labelCount = nodes.length + options.visibleEdgeLabels;
  const labelDensityPer100kPx = round(
    labelCount / ((viewportWidth * viewportHeight) / 100_000),
    1,
  );
  const graphScreenOccupancy = round(graphOccupancy(nodes, viewportWidth, viewportHeight, zoom), 2);
  const reasons: string[] = [];
  const geometryStatus = options.nodeOverlaps === 0
    && options.nodeLabelCollisions === 0
    && options.labelLabelCollisions === 0
    ? 'PASS'
    : 'REVIEW';
  if (options.nodeOverlaps > 0) {
    reasons.push(`${options.nodeOverlaps} node overlap${options.nodeOverlaps === 1 ? '' : 's'} remain.`);
  }
  if (options.nodeLabelCollisions > 0) {
    reasons.push(`${options.nodeLabelCollisions} field label${options.nodeLabelCollisions === 1 ? '' : 's'} were suppressed by nodes.`);
  }
  if (options.labelLabelCollisions > 0) {
    reasons.push(`${options.labelLabelCollisions} field label${options.labelLabelCollisions === 1 ? '' : 's'} were suppressed by other labels.`);
  }
  if (nodes.length <= 8 && zoom < 0.8) {
    reasons.push('Small graph fit is below the 80% readability floor.');
  }
  if (nodes.length <= 8 && effectiveTitleFontPx < 12) {
    reasons.push('Effective node title size is below 12px.');
  }
  if (options.mode === 'overview' && options.visibleFieldLabels > 0) {
    reasons.push('Overview exposes field-level labels.');
  }
  if (labelDensityPer100kPx > 7) {
    reasons.push('Visible label density is too high for reliable scanning.');
  }
  const status = reasons.length === 0 ? 'PASS' : 'REVIEW';
  return {
    status,
    geometryStatus,
    mode: options.mode,
    nodeOverlaps: options.nodeOverlaps,
    nodeLabelCollisions: options.nodeLabelCollisions,
    labelLabelCollisions: options.labelLabelCollisions,
    effectiveTitleFontPx,
    visibleNodeLabels: nodes.length,
    visibleEdgeLabels: options.visibleEdgeLabels,
    visibleFieldLabels: options.visibleFieldLabels,
    labelDensityPer100kPx,
    graphScreenOccupancy,
    reasons,
    summary: `Geometry ${geometryStatus} · Perception ${status} · ${effectiveTitleFontPx}px title · ${
      options.visibleEdgeLabels
    } edge labels · ${labelDensityPer100kPx}/100k px`,
  };
}

/** Chooses panel visibility from task and readability, while preserving explicit user preferences. */
export function adaptiveCanvasChromePolicy(
  options: AdaptiveChromeOptions,
): AdaptiveCanvasChromePolicy {
  const paletteForcedOpen = options.palettePreference === 'open';
  const inspectorForcedOpen = options.inspectorPreference === 'open';
  const paletteForcedClosed = options.palettePreference === 'closed';
  const inspectorForcedClosed = options.inspectorPreference === 'closed';

  if (options.authorMode !== 'compose') {
    return {
      collapsePalette: !paletteForcedOpen,
      collapseInspector: !inspectorForcedOpen,
      reason: 'The active task surface owns the workspace width.',
    };
  }
  if (options.compactWorkspace) {
    return {
      collapsePalette: !paletteForcedOpen,
      collapseInspector: !inspectorForcedOpen,
      reason: 'Compact workspace keeps panels available as drawers.',
    };
  }
  const readabilityPressure = options.nodeCount > 0
    && (options.fitZoom < 0.8 || options.nodeCount > 8);
  if (readabilityPressure) {
    return {
      collapsePalette: !paletteForcedOpen,
      collapseInspector: inspectorForcedClosed
        || (!inspectorForcedOpen && (!options.selectedNodeId || options.fitZoom < 0.72)),
      reason: options.nodeCount > 8
        ? 'Panels were reclaimed for the graph overview.'
        : 'Panels were reclaimed to keep the graph above its readability floor.',
    };
  }
  return {
    collapsePalette: paletteForcedClosed,
    collapseInspector: inspectorForcedClosed,
    reason: '',
  };
}

function labelCandidates(edges: CanvasEdge[], selectedNodeId: string): LabelCandidate[] {
  const groups = new Map<string, CanvasEdge[]>();
  for (const edge of edges) {
    const key = isDataEdge(edge) ? `data:${edge.source}` : `route:${edge.id}`;
    groups.set(key, [...(groups.get(key) ?? []), edge]);
  }
  return [...groups.values()]
    .map((group, index) => {
      const representative = group[0];
      const exactLabels = group.map(edgeSemanticLabel);
      return {
        representative,
        edges: group,
        text: group.length === 1 ? exactLabels[0] : bundledLabel(group),
        title: exactLabels.join('\n'),
        priority: selectedNodeId
          && (representative.source === selectedNodeId || representative.target === selectedNodeId)
          ? index - 10_000
          : index,
      };
    })
    .sort((left, right) => left.priority - right.priority);
}

function firstCollisionFreeLane(
  candidate: LabelCandidate,
  nodeById: Map<string, CanvasNode>,
  collisionNodes: CanvasNode[],
  viewportFitNodes: CanvasNode[],
  occupiedLabels: Box[],
): { lane: number | null; blockedByNodes: boolean; blockedByLabels: boolean } {
  let blockedByNodes = false;
  let blockedByLabels = false;
  let bestLane: number | null = null;
  let bestScore = Number.POSITIVE_INFINITY;
  const graphTop = Math.min(...viewportFitNodes.map((node) => node.position.y));
  const graphBottom = Math.max(
    ...viewportFitNodes.map((node) => node.position.y + NODE_HEIGHT),
  );
  for (const lane of orderedLabelLanes(candidate, nodeById, viewportFitNodes)) {
    const box = edgeLabelBox(candidate, nodeById, lane);
    if (!box) return { lane: null, blockedByNodes, blockedByLabels };
    const hitsNode = collisionNodes.some((node) => boxesOverlap(box, nodeBox(node)));
    const hitsLabel = occupiedLabels.some((occupied) => boxesOverlap(box, occupied));
    blockedByNodes ||= hitsNode;
    blockedByLabels ||= hitsLabel;
    if (hitsNode || hitsLabel) continue;
    const side = labelBoxSide(box, graphTop, graphBottom);
    const sameSideCount = side === 0
      ? 0
      : occupiedLabels.filter((occupied) => (
        labelBoxSide(occupied, graphTop, graphBottom) === side
      )).length;
    const graphOverflow = Math.max(
      0,
      graphTop - box.top,
      box.bottom - graphBottom,
    );
    const score = Math.abs(lane) * 5 + graphOverflow + sameSideCount * 25;
    if (score < bestScore) {
      bestLane = lane;
      bestScore = score;
    }
  }
  return { lane: bestLane, blockedByNodes, blockedByLabels };
}

function labelBoxSide(box: Box, graphTop: number, graphBottom: number): -1 | 0 | 1 {
  if (box.bottom < graphTop) return -1;
  if (box.top > graphBottom) return 1;
  return 0;
}

function orderedLabelLanes(
  candidate: LabelCandidate,
  nodeById: Map<string, CanvasNode>,
  nodes: CanvasNode[],
): number[] {
  const source = nodeById.get(candidate.representative.source);
  const target = nodeById.get(candidate.representative.target);
  if (!source || !target || nodes.length === 0) return LABEL_LANES;
  const graphTop = Math.min(...nodes.map((node) => node.position.y));
  const graphBottom = Math.max(...nodes.map((node) => node.position.y + NODE_HEIGHT));
  const graphCenter = (graphTop + graphBottom) / 2;
  const edgeCenter = (source.position.y + target.position.y + NODE_HEIGHT) / 2;
  const primaryDirection = edgeCenter <= graphCenter ? 1 : -1;
  const lanes = [0];
  for (let magnitude = 1; magnitude <= 12; magnitude += 1) {
    lanes.push(primaryDirection * magnitude, primaryDirection * -magnitude);
  }
  return lanes;
}

function edgeLabelBox(
  candidate: LabelCandidate,
  nodeById: Map<string, CanvasNode>,
  lane: number,
): Box | null {
  const source = nodeById.get(candidate.representative.source);
  const target = nodeById.get(candidate.representative.target);
  if (!source || !target) return null;
  const width = Math.min(
    LABEL_MAX_WIDTH,
    Math.max(LABEL_MIN_WIDTH, candidate.text.length * LABEL_CHAR_WIDTH + 24),
  );
  const centerX = (source.position.x + target.position.x + NODE_WIDTH) / 2;
  const centerY = (source.position.y + target.position.y + NODE_HEIGHT) / 2
    + lane * LABEL_LANE_STEP
    + LABEL_CANVAS_NUDGE_Y;
  return {
    left: centerX - width / 2,
    top: centerY - LABEL_HEIGHT / 2,
    right: centerX + width / 2,
    bottom: centerY + LABEL_HEIGHT / 2,
  };
}

function bundledLabel(edges: CanvasEdge[]): string {
  const sources = unique(edges.map((edge) => edgeLastSegment(edge.sourcePath || edge.sourcePort || 'value')));
  const targets = unique(edges.map((edge) => edgeLastSegment(edge.targetPath || edge.targetPort || 'input')));
  const targetCount = new Set(edges.map((edge) => edge.target)).size;
  const destination = targetCount === 1 ? '1 target' : `${targetCount} targets`;
  return `${edges.length} fields / ${destination} · ${shortList(sources)} -> ${shortList(targets)}`;
}

function edgeSemanticLabel(edge: CanvasEdge): string {
  if (edge.condition) return edge.condition;
  if (edge.kind && edge.kind !== 'data') return edge.kind;
  return `${edge.sourcePath || edge.sourcePort || 'value'} -> ${
    edge.targetPath || edge.targetPort || 'input'
  }`;
}

function isDataEdge(edge: CanvasEdge): boolean {
  return !edge.condition && (!edge.kind || edge.kind === 'data');
}

function edgeLastSegment(value: string): string {
  const segments = value.split(/[.[\]]/).filter(Boolean);
  return segments[segments.length - 1] ?? value;
}

function shortList(values: string[]): string {
  if (values.length <= 3) return values.join(', ');
  return `${values.slice(0, 2).join(', ')} +${values.length - 2}`;
}

function unique(values: string[]): string[] {
  return [...new Set(values)];
}

function inspectLabelBudget(nodeCount: number): number {
  if (nodeCount <= 8) return 32;
  if (nodeCount <= 25) return 20;
  return 10;
}

function laneLabel(depth: number, maximumDepth: number): string {
  if (depth === 0) return 'Inputs';
  if (depth === maximumDepth) return 'Outputs';
  return `Stage ${depth}`;
}

function graphOccupancy(
  nodes: CanvasNode[],
  viewportWidth: number,
  viewportHeight: number,
  zoom: number,
): number {
  if (nodes.length === 0) return 0;
  const left = Math.min(...nodes.map((node) => node.position.x));
  const top = Math.min(...nodes.map((node) => node.position.y));
  const right = Math.max(...nodes.map((node) => node.position.x + NODE_WIDTH));
  const bottom = Math.max(...nodes.map((node) => node.position.y + NODE_HEIGHT));
  return Math.min(
    1,
    (((right - left) * zoom) * ((bottom - top) * zoom)) / (viewportWidth * viewportHeight),
  );
}

function nodeBox(node: CanvasNode): Box {
  return {
    left: node.position.x - LABEL_NODE_CLEARANCE,
    top: node.position.y - LABEL_NODE_CLEARANCE,
    right: node.position.x + NODE_WIDTH + LABEL_NODE_CLEARANCE,
    bottom: node.position.y + NODE_HEIGHT + LABEL_NODE_CLEARANCE,
  };
}

function boxesOverlap(left: Box, right: Box): boolean {
  return left.left < right.right
    && left.right > right.left
    && left.top < right.bottom
    && left.bottom > right.top;
}

function round(value: number, digits: number): number {
  const scale = 10 ** digits;
  return Math.round(value * scale) / scale;
}
