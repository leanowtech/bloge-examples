import type { CanvasEdge, CanvasNode } from '../draftModel';

export type AuthorUxBaselineSize = 0 | 5 | 25 | 100;

export interface AuthorUxBaselineFixture {
  key: `ux-${AuthorUxBaselineSize}`;
  nodeCount: AuthorUxBaselineSize;
  edgeCount: number;
  nodes: CanvasNode[];
  edges: CanvasEdge[];
}

const EDGE_COUNTS: Record<AuthorUxBaselineSize, number> = {
  0: 0,
  5: 12,
  25: 50,
  100: 250,
};

function createNodes(nodeCount: AuthorUxBaselineSize): CanvasNode[] {
  return Array.from({ length: nodeCount }, (_, index) => ({
    id: `n${index + 1}`,
    operatorRef: index % 7 === 0 ? 'bloge:decisionTable' : 'bloge:transform',
    label: index % 11 === 0
      ? `Long business decision label ${index + 1} used for collision coverage`
      : `Step ${index + 1}`,
    position: { x: 0, y: 0 },
  }));
}

function createEdges(nodeCount: AuthorUxBaselineSize, edgeCount: number): CanvasEdge[] {
  if (nodeCount === 0) {
    return [];
  }
  return Array.from({ length: edgeCount }, (_, index) => {
    const sourceIndex = index % Math.max(1, nodeCount - 1);
    const distance = 1 + Math.floor(index / Math.max(1, nodeCount - 1)) % Math.min(5, nodeCount - 1);
    const targetIndex = Math.min(nodeCount - 1, sourceIndex + distance);
    const lane = Math.floor(index / Math.max(1, nodeCount - 1));
    return {
      id: `e${index + 1}:n${sourceIndex + 1}->n${targetIndex + 1}:lane${lane}`,
      source: `n${sourceIndex + 1}`,
      target: `n${targetIndex + 1}`,
      sourcePort: 'output',
      targetPort: 'input',
      sourcePath: lane % 3 === 0 ? `payload.customer.profile.field${index}` : `value${index}`,
      targetPath: `field${index}`,
      bindingKey: `field${index}`,
      condition: index % 13 === 0 ? `ctx.flags.route${index} == true` : undefined,
    };
  });
}

function createFixture(nodeCount: AuthorUxBaselineSize): AuthorUxBaselineFixture {
  const edgeCount = EDGE_COUNTS[nodeCount];
  return {
    key: `ux-${nodeCount}`,
    nodeCount,
    edgeCount,
    nodes: createNodes(nodeCount),
    edges: createEdges(nodeCount, edgeCount),
  };
}

/**
 * Deterministic graph corpus used by layout, semantic zoom, and browser collision gates.
 *
 * These fixtures deliberately include long node labels, parallel routes, path labels, and
 * conditional edges. Changing their size or shape is a UX baseline change and requires updating
 * the implementation-status evidence rather than silently weakening the stress surface.
 */
export const AUTHOR_UX_BASELINE_FIXTURES: Readonly<Record<
AuthorUxBaselineSize,
AuthorUxBaselineFixture
>> = {
  0: createFixture(0),
  5: createFixture(5),
  25: createFixture(25),
  100: createFixture(100),
};
