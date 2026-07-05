import type { DraftEdge, DraftNode, GraphDraft, SimulationResponse } from './types';

/**
 * The minimal shape of a canvas node needed to build a draft. Decouples the pure draft-building logic
 * from React Flow's node type so it can be unit-tested without a DOM.
 */
export interface CanvasNode {
  id: string;
  operatorRef: string;
  label?: string;
  position: { x: number; y: number };
}

/** The minimal shape of a canvas edge needed to build a draft. */
export interface CanvasEdge {
  id: string;
  source: string;
  target: string;
}

/**
 * Converts canvas nodes/edges into a {@link GraphDraft} suitable for the simulate endpoint.
 *
 * <p>Pure and deterministic so it can be unit-tested. When no output node is selected, the last node
 * added is used as the terminal output (a sensible default for a linear authoring flow).</p>
 */
export function toGraphDraft(
  graphName: string,
  nodes: CanvasNode[],
  edges: CanvasEdge[],
  outputNodeId: string,
): GraphDraft {
  const draftNodes: DraftNode[] = nodes.map((node) => ({
    id: node.id,
    operatorRef: node.operatorRef,
    label: node.label ?? '',
    inputs: {},
    config: {},
    position: node.position,
  }));

  const draftEdges: DraftEdge[] = edges.map((edge) => ({
    id: edge.id,
    kind: 'data',
    source: { nodeId: edge.source },
    target: { nodeId: edge.target },
  }));

  const resolvedOutputNode =
    outputNodeId || (nodes.length > 0 ? nodes[nodes.length - 1].id : '');

  return {
    graphName: graphName || 'visualGraph',
    nodes: draftNodes,
    edges: draftEdges,
    output: { nodeId: resolvedOutputNode, path: '' },
  };
}

/** How a node behaved during a mock run. */
export type NodeRunStatus = 'mocked' | 'real' | 'unknown';

/**
 * Maps a simulate response to a per-node run status. Mocked nodes are a first-class trust signal
 * (decision D15): their outputs are synthesized, not real. Pure.
 */
export function nodeStatuses(response: SimulationResponse): Record<string, NodeRunStatus> {
  const statuses: Record<string, NodeRunStatus> = {};
  for (const id of response.mockedNodeIds ?? []) {
    statuses[id] = 'mocked';
  }
  for (const id of response.realNodeIds ?? []) {
    statuses[id] = 'real';
  }
  return statuses;
}

/** Whether the whole run should be presented as successful. Pure. */
export function isRunSuccessful(response: SimulationResponse): boolean {
  return (
    response.validated &&
    response.compiled &&
    response.success &&
    (response.errors?.length ?? 0) === 0
  );
}
