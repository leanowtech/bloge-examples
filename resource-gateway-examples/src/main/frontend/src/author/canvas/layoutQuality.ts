import type { CanvasEdge, CanvasNode } from '../../draftModel';

const NODE_WIDTH = 260;
const NODE_HEIGHT = 164;
const NODE_CLEARANCE = 24;
const ROW_PITCH = 236;
const LABEL_HEIGHT = 30;
const LABEL_MIN_WIDTH = 104;
const LABEL_MAX_WIDTH = 320;
const LABEL_CHAR_WIDTH = 6.2;
const LABEL_DIAGONAL_OFFSET = 48;
const LABEL_LANE_STEP = 30;

export interface CanvasLayoutQualityReport {
  nodeOverlaps: number;
  edgeLabelCollisions: number;
  edgeLabelCollisionDetails: CanvasLayoutCollision[];
  pinnedNodes: number;
  status: 'PASS' | 'REVIEW';
  summary: string;
}

export interface CanvasLayoutCollision {
  edgeId: string;
  nodeId: string;
  label: string;
}

/**
 * Reconciles deterministic Auto Layout output with author-owned pinned positions.
 *
 * The first pinned node anchors a common translation so the graph keeps its generated shape. Every
 * pinned node is then restored exactly, and unpinned nodes move down by complete row pitches until
 * they clear already placed cards. A bounded relaxation pass then moves unrelated, unpinned cards
 * out of edge-label boxes. The function is pure and deterministic; it never mutates either the
 * current graph or the generated candidate.
 */
export function constrainCanvasLayout(
  current: CanvasNode[],
  candidate: CanvasNode[],
  pinnedNodeIds: ReadonlySet<string>,
  edges: CanvasEdge[] = [],
): CanvasNode[] {
  const currentById = new Map(current.map((node) => [node.id, node]));
  const candidateById = new Map(candidate.map((node) => [node.id, node]));
  const pinned = current.filter((node) => pinnedNodeIds.has(node.id));
  const anchor = pinned[0];
  const generatedAnchor = anchor ? candidateById.get(anchor.id) : undefined;
  const offset = anchor && generatedAnchor
    ? {
        x: anchor.position.x - generatedAnchor.position.x,
        y: anchor.position.y - generatedAnchor.position.y,
      }
    : { x: 0, y: 0 };
  const translated = candidate.map((node) => ({
    ...node,
    position: {
      x: node.position.x + offset.x,
      y: node.position.y + offset.y,
    },
  }));
  const placed: CanvasNode[] = pinned.map(copyNode);

  const separated = translated.map((node) => {
    if (pinnedNodeIds.has(node.id)) {
      return copyNode(currentById.get(node.id) ?? node);
    }
    const resolved = copyNode(node);
    while (placed.some((other) => nodeBoxesOverlap(resolved, other, NODE_CLEARANCE))) {
      resolved.position = {
        ...resolved.position,
        y: resolved.position.y + ROW_PITCH,
      };
    }
    placed.push(resolved);
    return resolved;
  });
  return relaxEdgeLabelCollisions(separated, edges, pinnedNodeIds);
}

/**
 * Audits the geometry that the canvas will render before a candidate layout is accepted.
 *
 * Node overlap is exact against the stable card footprint. Edge-label collision is intentionally
 * conservative and checks adjacent-column midpoint labels against unrelated node cards; long-span
 * labels use the canvas bus lane and are excluded from this midpoint approximation.
 */
export function assessCanvasLayout(
  nodes: CanvasNode[],
  edges: CanvasEdge[],
  pinnedNodeIds: ReadonlySet<string> = new Set(),
): CanvasLayoutQualityReport {
  let nodeOverlaps = 0;
  for (let left = 0; left < nodes.length; left += 1) {
    for (let right = left + 1; right < nodes.length; right += 1) {
      if (nodeBoxesOverlap(nodes[left], nodes[right], 0)) {
        nodeOverlaps += 1;
      }
    }
  }

  const edgeLabelCollisionDetails = findEdgeLabelCollisions(nodes, edges);
  const edgeLabelCollisions = edgeLabelCollisionDetails.length;

  const status = nodeOverlaps === 0 && edgeLabelCollisions === 0 ? 'PASS' : 'REVIEW';
  const pinnedNodes = nodes.filter((node) => pinnedNodeIds.has(node.id)).length;
  return {
    nodeOverlaps,
    edgeLabelCollisions,
    edgeLabelCollisionDetails,
    pinnedNodes,
    status,
    summary: `${nodeOverlaps} node overlaps · ${edgeLabelCollisions} label collisions · ${
      pinnedNodes
    } pinned`,
  };
}

function relaxEdgeLabelCollisions(
  nodes: CanvasNode[],
  edges: CanvasEdge[],
  pinnedNodeIds: ReadonlySet<string>,
): CanvasNode[] {
  const relaxed = nodes.map(copyNode);
  const byId = new Map(relaxed.map((node) => [node.id, node]));
  const maxPasses = Math.max(1, relaxed.length * Math.max(1, edges.length) * 2);

  for (let pass = 0; pass < maxPasses; pass += 1) {
    const collision = findEdgeLabelCollisions(relaxed, edges)[0];
    if (!collision) break;
    const edge = edges.find((candidate) => candidate.id === collision.edgeId);
    if (!edge) break;
    const victim = [
      byId.get(collision.nodeId),
      byId.get(edge.target),
      byId.get(edge.source),
    ].find((node) => node && !pinnedNodeIds.has(node.id));
    if (!victim) break;

    victim.position = {
      ...victim.position,
      y: victim.position.y + ROW_PITCH,
    };
    while (relaxed.some((other) => (
      other.id !== victim.id && nodeBoxesOverlap(victim, other, NODE_CLEARANCE)
    ))) {
      victim.position = {
        ...victim.position,
        y: victim.position.y + ROW_PITCH,
      };
    }
  }
  return relaxed;
}

function findEdgeLabelCollisions(
  nodes: CanvasNode[],
  edges: CanvasEdge[],
): CanvasLayoutCollision[] {
  const byId = new Map(nodes.map((node) => [node.id, node]));
  const parallelGroups = new Map<string, CanvasEdge[]>();
  for (const edge of edges) {
    const key = `${edge.source}::${edge.target}`;
    parallelGroups.set(key, [...(parallelGroups.get(key) ?? []), edge]);
  }
  const collisions: CanvasLayoutCollision[] = [];
  for (const edge of edges) {
    const source = byId.get(edge.source);
    const target = byId.get(edge.target);
    if (!source || !target) continue;
    const horizontalSpan = Math.abs(target.position.x - source.position.x);
    if (horizontalSpan > 760) continue;
    const label = edgeLabel(edge);
    const width = Math.min(
      LABEL_MAX_WIDTH,
      Math.max(LABEL_MIN_WIDTH, label.length * LABEL_CHAR_WIDTH + 24),
    );
    const parallel = parallelGroups.get(`${edge.source}::${edge.target}`) ?? [edge];
    const lane = parallel.findIndex((candidate) => candidate.id === edge.id)
      - (parallel.length - 1) / 2;
    const offsetX = Math.abs(target.position.y - source.position.y) > 60
      ? target.position.y > source.position.y ? -LABEL_DIAGONAL_OFFSET : LABEL_DIAGONAL_OFFSET
      : 0;
    const offsetY = lane * LABEL_LANE_STEP;
    const centerX = (source.position.x + target.position.x + NODE_WIDTH) / 2 + offsetX;
    const centerY = (source.position.y + target.position.y + NODE_HEIGHT) / 2 + offsetY;
    const labelBox = {
      left: centerX - width / 2,
      top: centerY - LABEL_HEIGHT / 2,
      right: centerX + width / 2,
      bottom: centerY + LABEL_HEIGHT / 2,
    };
    for (const node of nodes) {
      if (
        node.id !== edge.source
        && node.id !== edge.target
        && boxesOverlap(labelBox, nodeBox(node, 0))
      ) {
        collisions.push({ edgeId: edge.id, nodeId: node.id, label });
      }
    }
  }
  return collisions;
}

function copyNode(node: CanvasNode): CanvasNode {
  return {
    ...node,
    position: { ...node.position },
  };
}

function nodeBoxesOverlap(left: CanvasNode, right: CanvasNode, clearance: number): boolean {
  return boxesOverlap(nodeBox(left, clearance), nodeBox(right, clearance));
}

function nodeBox(node: CanvasNode, clearance: number) {
  return {
    left: node.position.x - clearance,
    top: node.position.y - clearance,
    right: node.position.x + NODE_WIDTH + clearance,
    bottom: node.position.y + NODE_HEIGHT + clearance,
  };
}

function boxesOverlap(
  left: { left: number; top: number; right: number; bottom: number },
  right: { left: number; top: number; right: number; bottom: number },
): boolean {
  return left.left < right.right
    && left.right > right.left
    && left.top < right.bottom
    && left.bottom > right.top;
}

function edgeLabel(edge: CanvasEdge): string {
  return `${edge.sourcePath || edge.sourcePort || 'value'} -> ${
    edge.targetPath || edge.targetPort || 'input'
  }`;
}
