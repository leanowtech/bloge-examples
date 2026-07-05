import type {
  ConnectionCandidate,
  ConnectionCandidatesRequest,
  ConnectionCandidatesResponse,
  ConnectionCheckRequest,
  ConnectionCheckResponse,
  DraftEdge,
  DraftEndpoint,
  DraftNode,
  GraphDraft,
  NodeFixture,
  OperatorDefinition,
  SchemaEnvelope,
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

/** One readable node row in the simulation trace panel. */
export interface SimulationTraceRow {
  nodeId: string;
  label: string;
  operatorRef: string;
  status: NodeRunStatus;
  outputPreview: string;
}

/** One row in the fixture setup list that guides authors to mocked nodes. */
export interface SimulationFixtureRow {
  nodeId: string;
  label: string;
  operatorRef: string;
  state: SimulationChecklistItem['state'];
  runMode: NodeRunStatus;
  fixtureLabel: string;
  detail: string;
}

export type PortHandleDirection = 'in' | 'out';
export type ConnectionCandidateStatus = 'ready' | 'blocked' | 'wired';

/** Canvas-ready lookup tables derived from the server candidate response. */
export interface ConnectionCandidateIndex {
  sourceKey: string;
  nodeStatuses: Record<string, ConnectionCandidateStatus>;
  portStatuses: Record<string, Record<string, ConnectionCandidateStatus>>;
  candidatesByEndpointKey: Record<string, ConnectionCandidate>;
  acceptedCount: number;
  rejectedCount: number;
  totalCandidateCount: number;
}

/** Parsed request fixtures plus per-node JSON errors from the inspector editor. */
export interface FixtureDraftCompilation {
  fixtures: Record<string, NodeFixture>;
  errors: Record<string, string>;
}

const MAX_SAMPLE_DEPTH = 12;
const MAX_SAMPLE_NODES = 512;
const MAX_ARRAY_ITEMS = 25;

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

/**
 * Converts a source-handle drag into the server candidate-discovery request.
 */
export function toConnectionCandidatesRequest(
  graphName: string,
  nodes: CanvasNode[],
  edges: CanvasEdge[],
  outputNodeId: string,
  sourceNodeId: string,
  sourceHandleId: string | null | undefined,
): ConnectionCandidatesRequest {
  return {
    draft: toGraphDraft(graphName, nodes, edges, outputNodeId),
    source: endpointFromHandle(sourceNodeId, sourceHandleId, 'out'),
    kind: 'data',
    includeRejected: true,
    limit: 250,
    targetSurface: 'input',
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

/** Human-readable feedback for a server candidate-discovery response. */
export function connectionCandidatesMessage(response: ConnectionCandidatesResponse): string {
  const accepted = response.acceptedCount ?? response.candidates.filter((candidate) => candidate.accepted).length;
  const rejected = response.rejectedCount
    ?? response.candidates.filter((candidate) => !candidate.accepted).length;
  const suffix = response.truncated ? ' Showing first results.' : '';
  if (accepted > 0) {
    return `${accepted} compatible target${accepted === 1 ? '' : 's'} · ${rejected} blocked.${suffix}`;
  }
  const firstDiagnostic = firstConnectionDiagnostic(response.diagnostics);
  if (firstDiagnostic) {
    return `${firstDiagnostic.code ? `${firstDiagnostic.code}: ` : ''}${firstDiagnostic.message ?? ''}`.trim();
  }
  return `0 compatible targets · ${rejected} blocked.${suffix}`;
}

/** Indexes server candidates for fast canvas node and handle highlighting. */
export function indexConnectionCandidates(response: ConnectionCandidatesResponse): ConnectionCandidateIndex {
  const nodeStatuses: Record<string, ConnectionCandidateStatus> = {};
  const portStatuses: Record<string, Record<string, ConnectionCandidateStatus>> = {};
  const candidatesByEndpointKey: Record<string, ConnectionCandidate> = {};

  for (const candidate of response.candidates) {
    const status = candidateStatus(candidate);
    const nodeId = candidate.target.nodeId || candidate.targetNodeId;
    if (!nodeId) {
      continue;
    }
    nodeStatuses[nodeId] = strongerCandidateStatus(nodeStatuses[nodeId], status);
    const port = candidate.target.port ?? '';
    if (port) {
      portStatuses[nodeId] = portStatuses[nodeId] ?? {};
      portStatuses[nodeId][port] = strongerCandidateStatus(portStatuses[nodeId][port], status);
    }
    candidatesByEndpointKey[endpointKey(candidate.target)] = candidate;
  }

  return {
    sourceKey: endpointKey(response.source),
    nodeStatuses,
    portStatuses,
    candidatesByEndpointKey,
    acceptedCount: response.acceptedCount ?? response.candidates.filter((candidate) => candidate.accepted).length,
    rejectedCount: response.rejectedCount ?? response.candidates.filter((candidate) => !candidate.accepted).length,
    totalCandidateCount: response.totalCandidateCount ?? response.candidates.length,
  };
}

/** Stable key for endpoint-indexed preview lookups. */
export function endpointKey(target: DraftEndpoint): string {
  return [
    encodeURIComponent(target.nodeId || ''),
    encodeURIComponent(target.port || ''),
    encodeURIComponent(target.path || ''),
  ].join('|');
}

function firstConnectionDiagnostic(diagnostics: VisualDiagnostic[] | undefined): VisualDiagnostic | undefined {
  return diagnostics?.find((diagnostic) => diagnostic.level === 'error')
    ?? diagnostics?.find((diagnostic) => diagnostic.message || diagnostic.code);
}

function candidateStatus(candidate: ConnectionCandidate): ConnectionCandidateStatus {
  if (candidate.targetStatus === 'wired') {
    return 'wired';
  }
  return candidate.accepted ? 'ready' : 'blocked';
}

function strongerCandidateStatus(
  current: ConnectionCandidateStatus | undefined,
  next: ConnectionCandidateStatus,
): ConnectionCandidateStatus {
  if (!current || current === 'blocked') {
    return next;
  }
  if (current === 'wired' && next === 'ready') {
    return next;
  }
  return current;
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

/**
 * Converts a simulation response into compact node-level trace rows for the inspector.
 */
export function simulationTraceRows(
  nodes: CanvasNode[],
  response: SimulationResponse | null,
): SimulationTraceRow[] {
  if (!response) {
    return [];
  }
  const statuses = nodeStatuses(response);
  return nodes.map((node) => ({
    nodeId: node.id,
    label: node.label || node.id,
    operatorRef: node.operatorRef,
    status: statuses[node.id] ?? 'unknown',
    outputPreview: previewValue(response.results?.[node.id]),
  }));
}

/**
 * Builds the node-level mock setup list shown before the selected-node editor.
 *
 * <p>The backend remains authoritative for the final mocked/real decision; before the first run this
 * helper only calls out design-only nodes and explicit fixture overrides so authors can configure
 * likely mock points without reading generated DSL.</p>
 */
export function simulationFixtureRows(
  nodes: CanvasNode[],
  operators: OperatorDefinition[],
  fixtureCompilation: FixtureDraftCompilation,
  outputDrafts: Record<string, string>,
  expectedInputDrafts: Record<string, string>,
  response: SimulationResponse | null,
): SimulationFixtureRow[] {
  const operatorByRef = new Map(operators.map((operator) => [operator.operatorRef, operator]));
  const statuses = response ? nodeStatuses(response) : {};

  return nodes.map((node) => {
    const outputText = outputDrafts[node.id]?.trim() ?? '';
    const expectedInputText = expectedInputDrafts[node.id]?.trim() ?? '';
    const hasOutputPin = outputText.length > 0;
    const hasInputAssert = expectedInputText.length > 0;
    const hasFixtureDraft = hasOutputPin || hasInputAssert;
    const error = fixtureCompilation.errors[node.id];
    const operator = operatorByRef.get(node.operatorRef);
    const summary = operator ? summarizeOperator(operator) : undefined;
    const runMode = statuses[node.id]
      ?? (hasFixtureDraft || summary?.designOnly ? 'mocked' : 'unknown');

    if (error) {
      return {
        nodeId: node.id,
        label: node.label || node.id,
        operatorRef: node.operatorRef,
        state: 'blocked',
        runMode,
        fixtureLabel: 'json error',
        detail: error,
      };
    }

    const fixtureLabel = fixtureStateLabel(hasOutputPin, hasInputAssert);
    if (runMode === 'real') {
      return {
        nodeId: node.id,
        label: node.label || node.id,
        operatorRef: node.operatorRef,
        state: 'ready',
        runMode,
        fixtureLabel,
        detail: 'real run',
      };
    }

    if (hasFixtureDraft) {
      return {
        nodeId: node.id,
        label: node.label || node.id,
        operatorRef: node.operatorRef,
        state: 'ready',
        runMode,
        fixtureLabel,
        detail: 'fixture set',
      };
    }

    if (runMode === 'mocked' || summary?.designOnly) {
      return {
        nodeId: node.id,
        label: node.label || node.id,
        operatorRef: node.operatorRef,
        state: 'warning',
        runMode: 'mocked',
        fixtureLabel,
        detail: 'server sample',
      };
    }

    return {
      nodeId: node.id,
      label: node.label || node.id,
      operatorRef: node.operatorRef,
      state: 'pending',
      runMode,
      fixtureLabel,
      detail: response ? 'not reached' : 'not run',
    };
  });
}

/**
 * Builds the initial JSON text for a node output fixture from its operator contract.
 *
 * <p>A single-output operator pins that output value directly. Multi-output operators pin an object
 * keyed by output port name so authors can override the whole mocked node result in one JSON payload.</p>
 */
export function fixtureDraftForOperator(operator: OperatorDefinition): string {
  return JSON.stringify(sampleFromOperatorOutput(operator), null, 2);
}

/**
 * Converts inspector JSON text into the request-scoped fixture map accepted by simulate.
 */
export function compileFixtureDrafts(
  outputDrafts: Record<string, string>,
  expectedInputDrafts: Record<string, string> = {},
): FixtureDraftCompilation {
  const fixtures: Record<string, NodeFixture> = {};
  const errors: Record<string, string> = {};
  const nodeIds = new Set([...Object.keys(outputDrafts), ...Object.keys(expectedInputDrafts)]);

  for (const nodeId of nodeIds) {
    const outputText = outputDrafts[nodeId]?.trim() ?? '';
    const expectedInputText = expectedInputDrafts[nodeId]?.trim() ?? '';
    const messages: string[] = [];
    let hasOutput = false;
    let hasExpectedInput = false;
    let output: unknown = null;
    let expectedInput: unknown;

    if (outputText) {
      try {
        output = JSON.parse(outputText);
        hasOutput = true;
      } catch (cause: unknown) {
        const detail = cause instanceof Error ? cause.message : String(cause);
        messages.push(`Invalid JSON in output: ${detail}`);
      }
    }
    if (expectedInputText) {
      try {
        expectedInput = JSON.parse(expectedInputText);
        hasExpectedInput = true;
      } catch (cause: unknown) {
        const detail = cause instanceof Error ? cause.message : String(cause);
        messages.push(`Invalid JSON in expected input: ${detail}`);
      }
    }

    if (messages.length > 0) {
      errors[nodeId] = messages.join(' ');
      continue;
    }
    if (hasOutput || hasExpectedInput) {
      fixtures[nodeId] = {
        output: hasOutput ? output : null,
        ...(hasExpectedInput ? { expectedInput } : {}),
      };
    }
  }

  return { fixtures, errors };
}

function fixtureStateLabel(hasOutputPin: boolean, hasInputAssert: boolean): string {
  if (hasOutputPin && hasInputAssert) {
    return 'pin + assert';
  }
  if (hasOutputPin) {
    return 'output pin';
  }
  if (hasInputAssert) {
    return 'input assert';
  }
  return 'server sample';
}

/** Generates the same deterministic schema sample shape used by the server mock-run generator. */
export function sampleFromSchemaEnvelope(envelope: SchemaEnvelope | undefined): unknown {
  return sampleFromJsonSchema(envelope?.schema);
}

function sampleFromOperatorOutput(operator: OperatorDefinition): unknown {
  const outputs = operator.ports?.outputs ?? [];
  if (outputs.length === 0) {
    return null;
  }
  if (outputs.length === 1) {
    return sampleFromSchemaEnvelope(outputs[0].schema);
  }

  const sample: Record<string, unknown> = {};
  for (const output of outputs) {
    sample[output.name || 'output'] = sampleFromSchemaEnvelope(output.schema);
  }
  return sample;
}

function sampleFromJsonSchema(schema: Record<string, unknown> | undefined): unknown {
  if (!schema || Object.keys(schema).length === 0) {
    return null;
  }
  return sampleValue(schema, 0, { remaining: MAX_SAMPLE_NODES });
}

function sampleValue(rawSchema: unknown, depth: number, budget: { remaining: number }): unknown {
  if (depth > MAX_SAMPLE_DEPTH || budget.remaining <= 0 || !isRecord(rawSchema)) {
    return null;
  }
  budget.remaining -= 1;
  const schema = rawSchema;

  if (hasOwn(schema, 'const')) {
    return deepCopy(schema.const);
  }
  if (hasOwn(schema, 'default')) {
    return deepCopy(schema.default);
  }
  const example = firstOf(schema.examples);
  if (example !== undefined && example !== null) {
    return deepCopy(example);
  }
  const enumValue = firstOf(schema.enum);
  if (enumValue !== undefined && enumValue !== null) {
    return deepCopy(enumValue);
  }

  const unionBranch = firstOf(schema.oneOf) ?? firstOf(schema.anyOf);
  if (unionBranch !== undefined && unionBranch !== null) {
    return sampleValue(unionBranch, depth, budget);
  }
  const allOfBranch = firstOf(schema.allOf);
  if (
    allOfBranch !== undefined &&
    allOfBranch !== null &&
    !hasOwn(schema, 'type') &&
    !hasOwn(schema, 'properties')
  ) {
    return sampleValue(allOfBranch, depth, budget);
  }

  return sampleByType(effectiveType(schema), schema, depth, budget);
}

function sampleByType(
  type: string,
  schema: Record<string, unknown>,
  depth: number,
  budget: { remaining: number },
): unknown {
  switch (type) {
    case 'object':
      return sampleObject(schema, depth, budget);
    case 'array':
      return sampleArray(schema, depth, budget);
    case 'string':
      return canonicalString(schema);
    case 'integer':
      return canonicalInteger(schema);
    case 'number':
      return canonicalNumber(schema);
    case 'boolean':
      return false;
    case 'null':
      return null;
    default:
      return null;
  }
}

function sampleObject(
  schema: Record<string, unknown>,
  depth: number,
  budget: { remaining: number },
): Record<string, unknown> {
  const instance: Record<string, unknown> = {};
  if (isRecord(schema.properties)) {
    for (const [name, propertySchema] of Object.entries(schema.properties)) {
      if (budget.remaining <= 0) {
        break;
      }
      instance[name] = sampleValue(propertySchema, depth + 1, budget);
    }
  }
  if (Array.isArray(schema.required)) {
    for (const required of schema.required) {
      const name = String(required);
      if (!hasOwn(instance, name)) {
        instance[name] = null;
      }
    }
  }
  return instance;
}

function sampleArray(schema: Record<string, unknown>, depth: number, budget: { remaining: number }): unknown[] {
  const itemSchema = isRecord(schema.items) ? schema.items : firstOf(schema.prefixItems);
  const minItems = numberValue(schema.minItems, 0);
  const count = itemSchema == null
    ? Math.min(minItems, MAX_ARRAY_ITEMS)
    : Math.min(Math.max(minItems, 1), MAX_ARRAY_ITEMS);
  const instance: unknown[] = [];
  for (let index = 0; index < count; index += 1) {
    if (budget.remaining <= 0) {
      break;
    }
    instance.push(itemSchema == null ? null : sampleValue(itemSchema, depth + 1, budget));
  }
  return instance;
}

function canonicalString(schema: Record<string, unknown>): string {
  const base = stringByFormat(String(schema.format ?? ''));
  const minLength = numberValue(schema.minLength, 0);
  const maxLength = numberValue(schema.maxLength, Number.MAX_SAFE_INTEGER);
  let value = base;
  while (value.length < minLength) {
    value += 'x';
  }
  return value.length > maxLength ? value.slice(0, Math.max(maxLength, 0)) : value;
}

function stringByFormat(format: string): string {
  switch (format) {
    case 'date-time':
      return '1970-01-01T00:00:00Z';
    case 'date':
      return '1970-01-01';
    case 'time':
      return '00:00:00Z';
    case 'email':
      return 'user@example.com';
    case 'uri':
    case 'url':
    case 'iri':
      return 'https://example.com';
    case 'uuid':
      return '00000000-0000-0000-0000-000000000000';
    case 'hostname':
      return 'example.com';
    case 'ipv4':
      return '127.0.0.1';
    default:
      return 'string';
  }
}

function canonicalInteger(schema: Record<string, unknown>): number {
  let value = numberValue(schema.minimum, numberValue(schema.exclusiveMinimum, 0));
  if (typeof schema.exclusiveMinimum === 'number' && value <= schema.exclusiveMinimum) {
    value = schema.exclusiveMinimum + 1;
  }
  return Math.min(value, numberValue(schema.maximum, Number.MAX_SAFE_INTEGER));
}

function canonicalNumber(schema: Record<string, unknown>): number {
  if (typeof schema.minimum === 'number') {
    return schema.minimum;
  }
  if (typeof schema.exclusiveMinimum === 'number') {
    return schema.exclusiveMinimum + 1;
  }
  return 0;
}

function effectiveType(schema: Record<string, unknown>): string {
  if (typeof schema.type === 'string' && schema.type) {
    return schema.type;
  }
  if (Array.isArray(schema.type)) {
    const candidate = schema.type.find((value) => typeof value === 'string' && value && value !== 'null');
    if (typeof candidate === 'string') {
      return candidate;
    }
  }
  if (
    hasOwn(schema, 'properties') ||
    hasOwn(schema, 'required') ||
    hasOwn(schema, 'additionalProperties') ||
    hasOwn(schema, 'patternProperties')
  ) {
    return 'object';
  }
  if (hasOwn(schema, 'items') || hasOwn(schema, 'prefixItems')) {
    return 'array';
  }
  return '';
}

function firstOf(value: unknown): unknown | undefined {
  return Array.isArray(value) && value.length > 0 ? value[0] : undefined;
}

function numberValue(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function deepCopy(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map((item) => deepCopy(item));
  }
  if (isRecord(value)) {
    const copy: Record<string, unknown> = {};
    for (const [key, item] of Object.entries(value)) {
      copy[key] = deepCopy(item);
    }
    return copy;
  }
  return value;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function hasOwn(value: Record<string, unknown>, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(value, key);
}

function previewValue(value: unknown): string {
  if (value === undefined) {
    return 'no output';
  }
  if (typeof value === 'string') {
    return truncate(value);
  }
  try {
    return truncate(JSON.stringify(value));
  } catch {
    return String(value);
  }
}

function truncate(value: string): string {
  return value.length > 120 ? `${value.slice(0, 117)}...` : value;
}
