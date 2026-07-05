import type {
  ConnectionCheckRequest,
  ConnectionCheckResponse,
  DraftEdge,
  DraftEndpoint,
  DraftNode,
  GraphDraft,
  OperatorDefinition,
  SimulationResponse,
  VisualDiagnostic,
} from './types';

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
  sourcePort?: string;
  targetPort?: string;
  sourcePath?: string;
  targetPath?: string;
}

/** Compact operator facts shown directly on canvas cards and palette rows. */
export interface OperatorSummary {
  operatorRef: string;
  name: string;
  description: string;
  tags: string[];
  sourceKind: string;
  requiredInputCount: number;
  inputCount: number;
  outputCount: number;
  inputNames: string[];
  requiredInputNames: string[];
  outputNames: string[];
  designOnly: boolean;
}

/** A concise topology readout for the current canvas. */
export interface CanvasSummary {
  nodeCount: number;
  edgeCount: number;
  outputNodeId: string;
  rootNodeIds: string[];
  terminalNodeIds: string[];
  disconnectedNodeIds: string[];
}

/** One row in the simulation readiness checklist. */
export interface SimulationChecklistItem {
  key: string;
  label: string;
  state: 'ready' | 'warning' | 'blocked' | 'pending';
  detail: string;
}

export type PortHandleDirection = 'in' | 'out';

/** Encodes a port name into a stable React Flow handle id. */
export function handleIdForPort(direction: PortHandleDirection, portName: string): string {
  return `${direction}:${encodeURIComponent(portName || '')}`;
}

/** Decodes a React Flow handle id back into the BLOGE port name it represents. */
export function portNameFromHandle(
  handleId: string | null | undefined,
  direction: PortHandleDirection,
): string {
  const prefix = `${direction}:`;
  if (!handleId || !handleId.startsWith(prefix)) {
    return '';
  }
  return decodeURIComponent(handleId.slice(prefix.length));
}

/** Converts one React Flow handle into the endpoint shape accepted by BLOGE visual APIs. */
export function endpointFromHandle(
  nodeId: string,
  handleId: string | null | undefined,
  direction: PortHandleDirection,
): DraftEndpoint {
  return endpoint(nodeId, portNameFromHandle(handleId, direction));
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
    source: endpoint(edge.source, edge.sourcePort, edge.sourcePath),
    target: endpoint(edge.target, edge.targetPort, edge.targetPath),
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

/**
 * Converts a React Flow connection gesture into the server-authoritative schema preflight request.
 */
export function toConnectionCheckRequest(
  graphName: string,
  nodes: CanvasNode[],
  edges: CanvasEdge[],
  outputNodeId: string,
  sourceNodeId: string,
  targetNodeId: string,
  sourceHandleId: string | null | undefined,
  targetHandleId: string | null | undefined,
): ConnectionCheckRequest {
  return {
    draft: toGraphDraft(graphName, nodes, edges, outputNodeId),
    kind: 'data',
    condition: '',
    source: endpointFromHandle(sourceNodeId, sourceHandleId, 'out'),
    target: endpointFromHandle(targetNodeId, targetHandleId, 'in'),
  };
}

/** Human-readable feedback for a server connection-check response. */
export function connectionDecisionMessage(response: ConnectionCheckResponse): string {
  if (response.summary?.message) {
    return response.summary.message;
  }
  const firstDiagnostic = firstConnectionDiagnostic(response.diagnostics);
  if (firstDiagnostic) {
    return `${firstDiagnostic.code ? `${firstDiagnostic.code}: ` : ''}${firstDiagnostic.message ?? ''}`.trim();
  }
  return response.accepted ? 'Connection accepted.' : 'Connection rejected.';
}

function firstConnectionDiagnostic(diagnostics: VisualDiagnostic[] | undefined): VisualDiagnostic | undefined {
  return diagnostics?.find((diagnostic) => diagnostic.level === 'error')
    ?? diagnostics?.find((diagnostic) => diagnostic.message || diagnostic.code);
}

function endpoint(nodeId: string, port = '', path = ''): DraftEndpoint {
  return {
    nodeId,
    ...(port ? { port } : {}),
    ...(path ? { path } : {}),
  };
}

/**
 * Extracts stable display metadata from one operator definition.
 *
 * <p>The authoring UI receives operator libraries owned by users, so display names and port arrays may
 * be sparse. This helper keeps fallback rules deterministic and testable.</p>
 */
export function summarizeOperator(operator: OperatorDefinition): OperatorSummary {
  const inputs = operator.ports?.inputs ?? [];
  const outputs = operator.ports?.outputs ?? [];
  const requiredInputs = inputs.filter((input) => input.required);
  return {
    operatorRef: operator.operatorRef,
    name: operator.display?.name || operator.operatorRef,
    description: operator.display?.description || '',
    tags: operator.display?.tags ?? [],
    sourceKind: operator.source?.kind || 'library',
    requiredInputCount: requiredInputs.length,
    inputCount: inputs.length,
    outputCount: outputs.length,
    inputNames: inputs.map((input) => input.name),
    requiredInputNames: requiredInputs.map((input) => input.name),
    outputNames: outputs.map((output) => output.name),
    designOnly: operator.lowering?.mode === 'design',
  };
}

/**
 * Builds a node/edge summary that can drive HUD chips without coupling those chips to React Flow.
 */
export function summarizeCanvas(
  nodes: CanvasNode[],
  edges: CanvasEdge[],
  outputNodeId = '',
): CanvasSummary {
  const nodeIds = new Set(nodes.map((node) => node.id));
  const incoming = new Map<string, number>();
  const outgoing = new Map<string, number>();
  for (const node of nodes) {
    incoming.set(node.id, 0);
    outgoing.set(node.id, 0);
  }
  for (const edge of edges) {
    if (!nodeIds.has(edge.source) || !nodeIds.has(edge.target)) {
      continue;
    }
    outgoing.set(edge.source, (outgoing.get(edge.source) ?? 0) + 1);
    incoming.set(edge.target, (incoming.get(edge.target) ?? 0) + 1);
  }

  return {
    nodeCount: nodes.length,
    edgeCount: edges.filter((edge) => nodeIds.has(edge.source) && nodeIds.has(edge.target)).length,
    outputNodeId: outputNodeId || (nodes.length > 0 ? nodes[nodes.length - 1].id : ''),
    rootNodeIds: nodes.filter((node) => (incoming.get(node.id) ?? 0) === 0).map((node) => node.id),
    terminalNodeIds: nodes.filter((node) => (outgoing.get(node.id) ?? 0) === 0).map((node) => node.id),
    disconnectedNodeIds: nodes
      .filter((node) => (incoming.get(node.id) ?? 0) === 0 && (outgoing.get(node.id) ?? 0) === 0)
      .map((node) => node.id),
  };
}

/**
 * Produces a deterministic left-to-right layout for a directed canvas.
 *
 * <p>The algorithm is deliberately small: DAGs are layered by predecessor depth, while cyclic or
 * partially disconnected nodes fall back to their insertion order. This gives authors a quick readable
 * arrangement without taking ownership of manual positioning.</p>
 */
export function autoLayoutCanvas(nodes: CanvasNode[], edges: CanvasEdge[]): CanvasNode[] {
  const nodeIds = new Set(nodes.map((node) => node.id));
  const indegree = new Map<string, number>();
  const outgoing = new Map<string, string[]>();
  const layers = new Map<string, number>();
  for (const node of nodes) {
    indegree.set(node.id, 0);
    outgoing.set(node.id, []);
    layers.set(node.id, 0);
  }
  for (const edge of edges) {
    if (!nodeIds.has(edge.source) || !nodeIds.has(edge.target)) {
      continue;
    }
    outgoing.get(edge.source)?.push(edge.target);
    indegree.set(edge.target, (indegree.get(edge.target) ?? 0) + 1);
  }

  const queue = nodes.filter((node) => (indegree.get(node.id) ?? 0) === 0).map((node) => node.id);
  const visited = new Set<string>();
  for (let index = 0; index < queue.length; index += 1) {
    const id = queue[index];
    visited.add(id);
    for (const target of outgoing.get(id) ?? []) {
      layers.set(target, Math.max(layers.get(target) ?? 0, (layers.get(id) ?? 0) + 1));
      indegree.set(target, (indegree.get(target) ?? 0) - 1);
      if ((indegree.get(target) ?? 0) === 0) {
        queue.push(target);
      }
    }
  }

  const rowByLayer = new Map<number, number>();
  return nodes.map((node, index) => {
    const layer = visited.has(node.id) ? layers.get(node.id) ?? 0 : 0;
    const row = rowByLayer.get(layer) ?? 0;
    rowByLayer.set(layer, row + 1);
    return {
      ...node,
      position: {
        x: 72 + layer * 260,
        y: 56 + row * 132 + (visited.has(node.id) ? 0 : index * 8),
      },
    };
  });
}

/**
 * Converts topology and last simulation result into the compact checklist shown beside the canvas.
 */
export function simulationChecklist(
  summary: CanvasSummary,
  result: SimulationResponse | null,
): SimulationChecklistItem[] {
  const items: SimulationChecklistItem[] = [
    {
      key: 'nodes',
      label: 'Nodes',
      state: summary.nodeCount > 0 ? 'ready' : 'blocked',
      detail: String(summary.nodeCount),
    },
    {
      key: 'flow',
      label: 'Flow',
      state:
        summary.nodeCount <= 1 || summary.edgeCount > 0
          ? 'ready'
          : summary.disconnectedNodeIds.length > 0
            ? 'warning'
            : 'pending',
      detail:
        summary.nodeCount <= 1
          ? 'single'
          : `${summary.edgeCount} edge${summary.edgeCount === 1 ? '' : 's'}`,
    },
    {
      key: 'output',
      label: 'Output',
      state: summary.outputNodeId ? 'ready' : 'blocked',
      detail: summary.outputNodeId || 'missing',
    },
  ];

  if (!result) {
    return [
      ...items,
      { key: 'run', label: 'Run', state: 'pending', detail: 'not run' },
    ];
  }

  return [
    ...items,
    {
      key: 'run',
      label: 'Run',
      state: isRunSuccessful(result) ? 'ready' : 'blocked',
      detail: isRunSuccessful(result) ? 'success' : `${result.errors?.length ?? 0} error(s)`,
    },
    {
      key: 'trust',
      label: 'Trust',
      state: (result.mockedNodeIds?.length ?? 0) > 0 ? 'warning' : 'ready',
      detail: `${result.realNodeIds?.length ?? 0} real / ${result.mockedNodeIds?.length ?? 0} mocked`,
    },
  ];
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
