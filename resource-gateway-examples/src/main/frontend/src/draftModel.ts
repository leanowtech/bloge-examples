import type {
  ConnectionCandidate,
  ConnectionCandidatesRequest,
  ConnectionCandidatesResponse,
  ConnectionCheckRequest,
  ConnectionCheckResponse,
  DraftEdge,
  DraftEndpoint,
  DraftNode,
  DraftNodeBinding,
  GraphDraft,
  NodeFixture,
  OperatorDefinition,
  OperatorLibrary,
  OperatorLibraryValidationResult,
  SchemaEnvelope,
  SimulationRequest,
  SimulationResponse,
  VisualDiagnostic,
} from './types';

const GRAPH_DRAFT_SCHEMA_VERSION = 'bloge.visualGraphDraft.v1';
const AUTO_LAYOUT_LEFT = 96;
const AUTO_LAYOUT_TOP = 72;
const AUTO_LAYOUT_NODE_WIDTH = 260;
const AUTO_LAYOUT_NODE_HEIGHT = 164;
const AUTO_LAYOUT_NODE_ROW_GAP = 72;
const AUTO_LAYOUT_MIN_COLUMN_GAP = 148;
const AUTO_LAYOUT_MAX_COLUMN_PITCH = 760;
const AUTO_LAYOUT_EDGE_LABEL_CHAR_WIDTH = 6.2;
const AUTO_LAYOUT_EDGE_LABEL_PADDING = 160;
const AUTO_LAYOUT_LONG_EDGE_BUS_LANES = 1;

/**
 * The minimal shape of a canvas node needed to build a draft. Decouples the pure draft-building logic
 * from React Flow's node type so it can be unit-tested without a DOM.
 */
export interface CanvasNode {
  id: string;
  operatorRef: string;
  label?: string;
  inputs?: Record<string, DraftNodeBinding>;
  config?: Record<string, unknown>;
  position: { x: number; y: number };
}

/** The minimal shape of a canvas edge needed to build a draft. */
export interface CanvasEdge {
  id: string;
  source: string;
  target: string;
  kind?: string;
  sourcePort?: string;
  targetPort?: string;
  sourcePath?: string;
  targetPath?: string;
  bindingKey?: string;
  condition?: string;
}

export interface CanvasDraftProjection {
  draftId?: string;
  revision?: number;
  graphName: string;
  nodes: CanvasNode[];
  edges: CanvasEdge[];
  outputNodeId: string;
  inputSchema?: SchemaEnvelope;
  outputSchema?: SchemaEnvelope;
  visualLayout?: Record<string, unknown>;
  nodeFixtures: Record<string, NodeFixture>;
  operatorFingerprints: Record<string, string>;
  operatorSnapshots: Record<string, OperatorDefinition>;
}

export interface GraphDraftExportOptions {
  draftId?: string;
  revision?: number;
  tenantId?: string;
  namespace?: string;
  environment?: string;
  outputSchema?: SchemaEnvelope;
  visualLayout?: Record<string, unknown>;
  operatorFingerprints?: Record<string, string>;
  operatorSnapshots?: Record<string, OperatorDefinition>;
}

/** Compact operator facts shown directly on canvas cards and palette rows. */
export interface OperatorSummary {
  operatorRef: string;
  name: string;
  description: string;
  tags: string[];
  sourceKind: string;
  visualKind: 'decision-table' | 'foreach' | 'transform' | 'resource' | 'http' | 'streaming' | 'design' | 'generic';
  visualLabel: string;
  contractHint: string;
  inputContractLabel: string;
  outputContractLabel: string;
  requiredInputCount: number;
  inputCount: number;
  outputCount: number;
  inputNames: string[];
  requiredInputNames: string[];
  outputNames: string[];
  designOnly: boolean;
  readinessState: string;
  readinessLevel: 'success' | 'info' | 'warning' | 'error' | 'unknown';
  readinessBadgeLabel: string;
  readinessNodeNotice: string;
  readinessNotice: string;
  externalWrite: boolean;
  managedWrite: boolean;
  sideEffectBadgeLabel: string;
  sideEffectNotice: string;
}

export type OperatorPaletteFacet = 'all' | 'runtime' | 'design';

export interface OperatorPaletteQuery {
  search?: string;
  facet?: OperatorPaletteFacet;
  sourceKind?: string;
  tag?: string;
}

export interface OperatorPaletteFacetCount<TKey extends string = string> {
  key: TKey;
  label: string;
  count: number;
}

export interface OperatorPaletteRow {
  operator: OperatorDefinition;
  summary: OperatorSummary;
  libraryId: string;
}

export interface OperatorPaletteGroup {
  libraryId: string;
  label: string;
  count: number;
  sourceKinds: string[];
  rows: OperatorPaletteRow[];
}

export interface OperatorPaletteView {
  totalCount: number;
  matchingCount: number;
  runtimeFacets: OperatorPaletteFacetCount<OperatorPaletteFacet>[];
  sourceKindFacets: OperatorPaletteFacetCount[];
  tagFacets: OperatorPaletteFacetCount[];
  groups: OperatorPaletteGroup[];
}

export type OperatorLibraryIntakeLevel = 'ok' | 'warning' | 'error';

/** A concise topology readout for the current canvas. */
export interface CanvasSummary {
  nodeCount: number;
  edgeCount: number;
  outputNodeId: string;
  rootNodeIds: string[];
  terminalNodeIds: string[];
  disconnectedNodeIds: string[];
}

export type CanvasZoomTier = 'detail' | 'compact' | 'overview';

export interface CanvasZoomPresentation {
  tier: CanvasZoomTier;
  showNodeDetails: boolean;
  edgeLabelMode: 'full' | 'summary' | 'hidden';
}

export interface CanvasFocusPath {
  nodeIds: Set<string>;
  edgeIds: Set<string>;
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

export interface SimulationRunSummaryChip {
  key: string;
  label: string;
  value: string;
  state: SimulationChecklistItem['state'];
}

export interface SimulationRunSummary {
  state: 'pending' | 'success' | 'blocked';
  title: string;
  detail: string;
  chips: SimulationRunSummaryChip[];
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

export type AuthoringJourneyActionKind = 'focus-palette' | 'select-node' | 'simulate' | 'none';
export type AuthoringJourneyActionGuide = 'connection-guide';

export interface AuthoringJourneyStep {
  key: 'library' | 'compose' | 'flow' | 'mocks' | 'simulate';
  label: string;
  state: SimulationChecklistItem['state'];
  detail: string;
}

export interface AuthoringJourneyAction {
  kind: AuthoringJourneyActionKind;
  label: string;
  nodeId?: string;
  guide?: AuthoringJourneyActionGuide;
}

export interface AuthoringJourney {
  steps: AuthoringJourneyStep[];
  action: AuthoringJourneyAction;
  completedCount: number;
}

export type CanvasCoachState = 'blocked' | 'compose' | 'connect' | 'mock' | 'simulate' | 'review' | 'ready';

export interface CanvasCoachPrompt {
  state: CanvasCoachState;
  title: string;
  detail: string;
  body: string;
  action: AuthoringJourneyAction;
}

export type CanvasNodeFocusState = 'none' | 'suggested' | 'selected';

export type PortHandleDirection = 'in' | 'out';
export type ConnectionCandidateStatus = 'ready' | 'blocked' | 'wired';

/** Canvas-ready lookup tables derived from the server candidate response. */
export interface ConnectionCandidateIndex {
  sourceKey: string;
  nodeStatuses: Record<string, ConnectionCandidateStatus>;
  portStatuses: Record<string, Record<string, ConnectionCandidateStatus>>;
  candidatesByEndpointKey: Record<string, ConnectionCandidate>;
  candidates: ConnectionCandidate[];
  acceptedCount: number;
  rejectedCount: number;
  totalCandidateCount: number;
}

/** One inspector row that turns server connection candidates into an actionable target list. */
export interface ConnectionGuideRow {
  key: string;
  targetNodeId: string;
  targetLabel: string;
  targetOperatorRef: string;
  targetPort: string;
  targetPath: string;
  status: ConnectionCandidateStatus;
  accepted: boolean;
  detail: string;
  actionHint: string;
  fieldOptions: ConnectionGuideFieldOption[];
}

/** One explicit field-level choice under a target input row. */
export interface ConnectionGuideFieldOption {
  key: string;
  path: string;
  label: string;
  status: ConnectionCandidateStatus;
  accepted: boolean;
  detail: string;
}

/** Parsed request fixtures plus per-node JSON errors from the inspector editor. */
export interface FixtureDraftCompilation {
  fixtures: Record<string, NodeFixture>;
  errors: Record<string, string>;
}

/** One editable row in the canvas-side mock/table test panel. */
export interface SimulationTableTestDraftRow {
  id: string;
  name: string;
  contextText: string;
  fixturesText: string;
  expectedOutputText: string;
}

/** Parsed table row ready to run against the transient simulate endpoint. */
export interface SimulationTableTestCase {
  id: string;
  name: string;
  context: Record<string, unknown>;
  fixtures: Record<string, NodeFixture>;
  hasExpectedOutput: boolean;
  expectedOutput?: unknown;
}

/** Parsed table rows plus row-local JSON errors. */
export interface SimulationTableCompilation {
  cases: SimulationTableTestCase[];
  errors: Record<string, string>;
}

export type SimulationTableCaseStatus = 'pending' | 'running' | 'passed' | 'failed';

/** One table row result after running simulate. */
export interface SimulationTableCaseResult {
  id: string;
  name: string;
  status: SimulationTableCaseStatus;
  detail: string;
  actualOutput?: unknown;
  expectedOutput?: unknown;
}

export interface SimulationTableSummary {
  state: 'pending' | 'running' | 'passed' | 'failed';
  label: string;
  detail: string;
  passed: number;
  failed: number;
  total: number;
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
  inputSchema?: SchemaEnvelope,
  outputSchema?: SchemaEnvelope,
): GraphDraft {
  const edgeInputs = nodeInputsFromEdges(edges);
  const draftNodes: DraftNode[] = nodes.map((node) => ({
    id: node.id,
    operatorRef: node.operatorRef,
    label: node.label ?? '',
    inputs: { ...(node.inputs ?? {}), ...(edgeInputs[node.id] ?? {}) },
    config: node.config ?? {},
    position: node.position,
  }));

  const draftEdges: DraftEdge[] = edges.map((edge) => ({
    id: edge.id,
    kind: edge.kind || 'data',
    source: endpoint(edge.source, edge.sourcePort, edge.sourcePath),
    target: endpoint(edge.target, edge.targetPort, edge.targetPath),
    ...(edge.condition ? { condition: edge.condition } : {}),
  }));

  const resolvedOutputNode =
    outputNodeId || (nodes.length > 0 ? nodes[nodes.length - 1].id : '');

  return {
    graphName: graphName || 'visualGraph',
    ...(inputSchema ? { inputSchema } : {}),
    ...(outputSchema ? { outputSchema } : {}),
    nodes: draftNodes,
    edges: draftEdges,
    output: { nodeId: resolvedOutputNode, path: '' },
  };
}

function nodeInputsFromEdges(edges: CanvasEdge[]): Record<string, Record<string, DraftNodeBinding>> {
  const inputsByNode: Record<string, Record<string, DraftNodeBinding>> = {};
  for (const edge of edges) {
    if (edge.kind && edge.kind !== 'data') {
      continue;
    }
    if (!edge.source || !edge.target) {
      continue;
    }
    const inputKey = canvasEdgeBindingKey(edge);
    if (!inputKey) {
      continue;
    }
    inputsByNode[edge.target] = {
      ...(inputsByNode[edge.target] ?? {}),
      [inputKey]: {
        kind: 'nodePath',
        nodeId: edge.source,
        sourcePort: edge.sourcePort ?? '',
        path: edge.sourcePath ?? '',
        targetPort: edge.targetPort ?? '',
        targetPath: edge.targetPath ?? '',
      },
    };
  }
  return inputsByNode;
}

export function canvasEdgeBindingKey(edge: CanvasEdge): string {
  if (edge.bindingKey) {
    return edge.bindingKey;
  }
  if (edge.targetPath) {
    return edge.targetPath;
  }
  if (edge.targetPort && edge.targetPort !== 'inputs' && edge.targetPort !== 'input') {
    return edge.targetPort;
  }
  const sourcePathTail = lastPathSegment(edge.sourcePath);
  if (sourcePathTail) {
    return sourcePathTail;
  }
  if (edge.sourcePort && edge.sourcePort !== 'output') {
    return edge.sourcePort;
  }
  return edge.targetPort || 'input';
}

function lastPathSegment(path: string | undefined): string {
  if (!path) {
    return '';
  }
  const segments = path.split('.').filter(Boolean);
  return segments[segments.length - 1] ?? '';
}

export function fromGraphDraft(draft: GraphDraft): CanvasDraftProjection {
  const nodes = (draft.nodes ?? []).map((node, index) => ({
    id: node.id,
    operatorRef: node.operatorRef,
    label: node.label || node.id,
    inputs: node.inputs ?? {},
    config: node.config ?? {},
    position: validPosition(node.position)
      ? node.position
      : {
          x: AUTO_LAYOUT_LEFT + (index % 4) * (AUTO_LAYOUT_NODE_WIDTH + AUTO_LAYOUT_MIN_COLUMN_GAP),
          y: AUTO_LAYOUT_TOP + Math.floor(index / 4) * (AUTO_LAYOUT_NODE_HEIGHT + AUTO_LAYOUT_NODE_ROW_GAP),
        },
  }));
  const edges = (draft.edges ?? []).map((edge) => ({
    id: edge.id,
    source: edge.source?.nodeId ?? '',
    target: edge.target?.nodeId ?? '',
    kind: edge.kind || 'data',
    sourcePort: edge.source?.port ?? '',
    targetPort: edge.target?.port ?? '',
    sourcePath: edge.source?.path ?? '',
    targetPath: edge.target?.path ?? '',
    bindingKey: edge.target?.path || edge.target?.port || '',
    condition: edge.condition ?? '',
  }));
  const visualLayout = isRecord(draft.visualLayout) ? draft.visualLayout : undefined;
  return {
    draftId: draft.draftId,
    revision: draft.revision,
    graphName: draft.graphName || 'visualGraph',
    nodes,
    edges,
    outputNodeId: draft.output?.nodeId || (nodes.length > 0 ? nodes[nodes.length - 1].id : ''),
    inputSchema: draft.inputSchema,
    outputSchema: draft.outputSchema ?? schemaEnvelopeFromGraphContract(visualLayout, 'outputSchema'),
    visualLayout,
    nodeFixtures: draft.nodeFixtures ?? {},
    operatorFingerprints: draft.operatorFingerprints ?? {},
    operatorSnapshots: draft.operatorSnapshots ?? {},
  };
}

function validPosition(position: { x: number; y: number } | undefined): position is { x: number; y: number } {
  return Boolean(position && Number.isFinite(position.x) && Number.isFinite(position.y));
}

function schemaEnvelopeFromGraphContract(
  visualLayout: Record<string, unknown> | undefined,
  key: string,
): SchemaEnvelope | undefined {
  const graphContract = visualLayout?.graphContract;
  if (!isRecord(graphContract)) {
    return undefined;
  }
  const candidate = graphContract[key];
  return isSchemaEnvelope(candidate) ? candidate : undefined;
}

function isSchemaEnvelope(value: unknown): value is SchemaEnvelope {
  return isRecord(value) && isRecord(value.schema);
}

/**
 * Builds a portable draft snapshot for the Author canvas export control.
 *
 * <p>Simulation fixtures are intentionally stored as {@code nodeFixtures}, matching the backend
 * GraphDraft contract, instead of the simulate request's transient {@code fixtures} envelope.</p>
 */
export function toExportableGraphDraft(
  graphName: string,
  nodes: CanvasNode[],
  edges: CanvasEdge[],
  outputNodeId: string,
  nodeFixtures: Record<string, NodeFixture> = {},
  inputSchema?: SchemaEnvelope,
  options: GraphDraftExportOptions = {},
): GraphDraft {
  const draft = toGraphDraft(graphName, nodes, edges, outputNodeId, inputSchema, options.outputSchema);
  return {
    schemaVersion: GRAPH_DRAFT_SCHEMA_VERSION,
    ...(options.draftId ? { draftId: options.draftId } : {}),
    ...(typeof options.revision === 'number' && options.revision > 0 ? { revision: options.revision } : {}),
    ...(options.tenantId ? { tenantId: options.tenantId } : {}),
    ...(options.namespace ? { namespace: options.namespace } : {}),
    ...(options.environment ? { environment: options.environment } : {}),
    ...draft,
    ...(options.visualLayout && Object.keys(options.visualLayout).length > 0
      ? { visualLayout: options.visualLayout }
      : {}),
    ...(Object.keys(nodeFixtures).length > 0 ? { nodeFixtures } : {}),
    ...(options.operatorFingerprints && Object.keys(options.operatorFingerprints).length > 0
      ? { operatorFingerprints: options.operatorFingerprints }
      : {}),
    ...(options.operatorSnapshots && Object.keys(options.operatorSnapshots).length > 0
      ? { operatorSnapshots: options.operatorSnapshots }
      : {}),
  };
}

/**
 * Builds the simulate endpoint request from the canvas state.
 *
 * <p>The output node is duplicated in the draft and request envelope because the backend accepts both
 * persisted draft output and request-scoped output selection. Keeping them identical prevents the
 * authoring UI from simulating one terminal node while displaying another.</p>
 */
export function toSimulationRequest(
  graphName: string,
  nodes: CanvasNode[],
  edges: CanvasEdge[],
  outputNodeId: string,
  fixtures: Record<string, NodeFixture> = {},
  context: Record<string, unknown> = {},
  inputSchema?: SchemaEnvelope,
  outputSchema?: SchemaEnvelope,
): SimulationRequest {
  const draft = toGraphDraft(graphName, nodes, edges, outputNodeId, inputSchema, outputSchema);
  const selectedOutputNode = draft.output.nodeId;
  return {
    draft,
    context,
    outputNode: selectedOutputNode,
    ...(Object.keys(fixtures).length > 0 ? { fixtures } : {}),
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
  sourcePath = '',
  targetPath = '',
): ConnectionCheckRequest {
  const source = endpointFromHandle(sourceNodeId, sourceHandleId, 'out');
  const target = endpointFromHandle(targetNodeId, targetHandleId, 'in');
  return {
    draft: toGraphDraft(graphName, nodes, edges, outputNodeId),
    kind: 'data',
    condition: '',
    source: sourcePath ? { ...source, path: sourcePath } : source,
    target: targetPath ? { ...target, path: targetPath } : target,
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
    candidates: response.candidates,
    acceptedCount: response.acceptedCount ?? response.candidates.filter((candidate) => candidate.accepted).length,
    rejectedCount: response.rejectedCount ?? response.candidates.filter((candidate) => !candidate.accepted).length,
    totalCandidateCount: response.totalCandidateCount ?? response.candidates.length,
  };
}

/**
 * Builds the selected-node connection guide shown in the inspector.
 *
 * <p>The server has already made the compatibility decision; this helper only labels and sorts those
 * decisions so the UI can offer a clear next target without duplicating schema rules.</p>
 */
export function connectionGuideRows(
  nodes: CanvasNode[],
  index: ConnectionCandidateIndex | null | undefined,
): ConnectionGuideRow[] {
  if (!index) {
    return [];
  }
  const nodeById = new Map(nodes.map((node) => [node.id, node]));
  const groups = new Map<string, ConnectionCandidate[]>();
  for (const candidate of index.candidates) {
    const targetNodeId = candidate.target.nodeId || candidate.targetNodeId;
    const key = endpointKey({
      nodeId: targetNodeId,
      port: candidate.target.port ?? '',
    });
    groups.set(key, [...(groups.get(key) ?? []), candidate]);
  }

  return [...groups.entries()]
    .map(([key, candidates]) => {
      const representative =
        candidates.find((candidate) => candidateStatus(candidate) === 'ready')
        ?? candidates.find((candidate) => candidateStatus(candidate) === 'wired')
        ?? candidates[0];
      const status = candidates.reduce<ConnectionCandidateStatus>(
        (current, candidate) => strongerCandidateStatus(current, candidateStatus(candidate)),
        'blocked',
      );
      const fieldOptions = candidates
        .filter((candidate) => Boolean(candidate.target.path))
        .map((candidate) => {
          const optionStatus = candidateStatus(candidate);
          return {
            key: endpointKey(candidate.target),
            path: candidate.target.path ?? '',
            label: endpointLabel(candidate.target.port ?? '', candidate.target.path ?? '', 'input'),
            status: optionStatus,
            accepted: candidate.accepted,
            detail: connectionCandidateDetail(candidate, optionStatus),
          };
        });
      const readyFieldOptions = fieldOptions.filter((option) => option.accepted);
      const targetPath = readyFieldOptions[0]?.path ?? representative.target.path ?? '';
      const node = nodeById.get(representative.target.nodeId || representative.targetNodeId);
      const targetNodeId = representative.target.nodeId || representative.targetNodeId;
      return {
        key,
        targetNodeId,
        targetLabel: representative.targetNodeLabel || node?.label || targetNodeId,
        targetOperatorRef: representative.targetOperatorRef || node?.operatorRef || '',
        targetPort: representative.target.port ?? '',
        targetPath,
        status,
        accepted: status === 'ready',
        detail: connectionGuideGroupDetail(candidates, status, readyFieldOptions.length),
        actionHint: connectionGuideActionHint(
          status,
          readyFieldOptions.length,
          endpointLabel(representative.target.port ?? '', targetPath, 'input'),
        ),
        fieldOptions: readyFieldOptions,
      };
    })
    .sort((left, right) =>
      connectionGuideRank(left.status) - connectionGuideRank(right.status)
      || left.targetLabel.localeCompare(right.targetLabel)
      || left.targetPort.localeCompare(right.targetPort),
    );
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

function connectionGuideRank(status: ConnectionCandidateStatus): number {
  if (status === 'ready') {
    return 0;
  }
  if (status === 'wired') {
    return 1;
  }
  return 2;
}

function defaultConnectionGuideDetail(status: ConnectionCandidateStatus): string {
  if (status === 'ready') {
    return 'Compatible.';
  }
  if (status === 'wired') {
    return 'Already connected.';
  }
  return 'Blocked by schema.';
}

function connectionGuideGroupDetail(
  candidates: ConnectionCandidate[],
  status: ConnectionCandidateStatus,
  readyFieldCount: number,
): string {
  if (status === 'ready' && readyFieldCount > 1) {
    return `${readyFieldCount} compatible fields found.`;
  }
  const representative =
    candidates.find((candidate) => candidateStatus(candidate) === status && candidate.accepted)
    ?? candidates.find((candidate) => candidateStatus(candidate) === status)
    ?? candidates[0];
  return connectionCandidateDetail(representative, status);
}

function connectionGuideActionHint(
  status: ConnectionCandidateStatus,
  readyFieldCount: number,
  targetLabel: string,
): string {
  if (status === 'ready' && readyFieldCount > 1) {
    return 'Choose the field path that should feed this input.';
  }
  if (status === 'ready') {
    return `Connect to ${targetLabel}.`;
  }
  if (status === 'wired') {
    return 'Already connected; remove the current edge before reconnecting.';
  }
  return 'Try a nested field, add a transform, or choose another target.';
}

function connectionCandidateDetail(
  candidate: ConnectionCandidate | undefined,
  status: ConnectionCandidateStatus,
): string {
  if (!candidate) {
    return defaultConnectionGuideDetail(status);
  }
  const diagnostic = firstDiagnosticText(candidate.diagnostics);
  const summary = candidate.summary?.message?.trim();
  if (summary && !isGenericConnectionRejection(summary)) {
    return summary;
  }
  return diagnostic || summary || defaultConnectionGuideDetail(status);
}

function isGenericConnectionRejection(message: string): boolean {
  return /^connection rejected(?: by server)?\.?$/i.test(message.trim());
}

function endpointLabel(port: string, path: string, fallback: string): string {
  const base = port || fallback;
  return path ? `${base}.${path}` : base;
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
  const visualKind = operatorVisualKind(operator);
  const firstInputType = schemaRootLabel(inputs[0]?.schema, 'input');
  const firstOutputType = schemaRootLabel(outputs[0]?.schema, 'output');
  const visualContract = operatorVisualContract(visualKind, firstInputType, firstOutputType);
  const readiness = operatorReadiness(operator);
  const sideEffect = operatorSideEffect(operator);
  return {
    operatorRef: operator.operatorRef,
    name: operator.display?.name || operator.operatorRef,
    description: operator.display?.description || '',
    tags: operator.display?.tags ?? [],
    sourceKind: operator.source?.kind || 'library',
    visualKind,
    visualLabel: visualContract.visualLabel,
    contractHint: visualContract.contractHint,
    inputContractLabel: visualContract.inputContractLabel,
    outputContractLabel: visualContract.outputContractLabel,
    requiredInputCount: requiredInputs.length,
    inputCount: inputs.length,
    outputCount: outputs.length,
    inputNames: inputs.map((input) => input.name),
    requiredInputNames: requiredInputs.map((input) => input.name),
    outputNames: outputs.map((output) => output.name),
    designOnly: operator.lowering?.mode === 'design' || readiness.state === 'design-only',
    readinessState: readiness.state,
    readinessLevel: readiness.level,
    readinessBadgeLabel: readiness.badgeLabel,
    readinessNodeNotice: readiness.nodeNotice,
    readinessNotice: readiness.notice,
    externalWrite: sideEffect.externalWrite,
    managedWrite: sideEffect.managedWrite,
    sideEffectBadgeLabel: sideEffect.badgeLabel,
    sideEffectNotice: sideEffect.notice,
  };
}

function operatorSideEffect(operator: OperatorDefinition): {
  externalWrite: boolean;
  managedWrite: boolean;
  badgeLabel: string;
  notice: string;
} {
  const capabilities = operator.capabilities;
  const externalWrite = capabilities?.effect?.trim().toUpperCase() === 'WRITE_EXTERNAL';
  if (!externalWrite) {
    return { externalWrite: false, managedWrite: false, badgeLabel: '', notice: '' };
  }
  const protocol = capabilities?.sideEffectProtocol;
  const managedWrite = protocol?.schemaVersion === 'bloge.sideEffectProtocol.v1'
    && protocol.mode?.trim().toUpperCase() === 'JOURNALED'
    && protocol.commitReceiptRequired === true
    && protocol.reconciliationRequired === true
    && Boolean(protocol.reconcilerRef?.trim())
    && Boolean(protocol.idempotencyKeySource?.trim())
    && Boolean(protocol.reconciliationLookupSource?.trim())
    && Boolean(protocol.commitReceiptSource?.trim());
  return managedWrite
    ? {
      externalWrite: true,
      managedWrite: true,
      badgeLabel: 'managed write',
      notice: `Journaled external write; reconciler ${protocol?.reconcilerRef}.`,
    }
    : {
      externalWrite: true,
      managedWrite: false,
      badgeLabel: 'write protocol required',
      notice: 'External write is DESIGN-only until journal, receipt and reconciliation sources are declared.',
    };
}

function operatorReadiness(operator: OperatorDefinition): {
  state: string;
  level: OperatorSummary['readinessLevel'];
  badgeLabel: string;
  nodeNotice: string;
  notice: string;
} {
  const state = normalizeReadinessState(operator.runtimeReadiness?.state);
  const level = normalizeReadinessLevel(operator.runtimeReadiness?.level);
  if (state === 'runtime-executable') {
    return { state, level, badgeLabel: '', nodeNotice: '', notice: '' };
  }
  if (state === 'runtime-blocked') {
    return {
      state,
      level: level === 'unknown' ? 'warning' : level,
      badgeLabel: 'blocked',
      nodeNotice: operator.runtimeReadiness?.title || 'Runtime blocked',
      notice: operator.runtimeReadiness?.summary || 'Runtime blocked in this visual runtime.',
    };
  }
  if (state === 'governance-review') {
    return {
      state,
      level: level === 'unknown' ? 'warning' : level,
      badgeLabel: 'review',
      nodeNotice: operator.runtimeReadiness?.title || 'Governance review',
      notice: operator.runtimeReadiness?.summary || 'Executable, but promotion should review governance risks.',
    };
  }
  if (state === 'design-only' || operator.lowering?.mode?.toLowerCase() === 'design') {
    return {
      state: 'design-only',
      level: level === 'unknown' ? 'info' : level,
      badgeLabel: 'design',
      nodeNotice: operator.runtimeReadiness?.title || 'Design-only',
      notice: operator.runtimeReadiness?.summary || 'Design-only operator; executable lowering is not bound yet.',
    };
  }
  if (state) {
    const fallback = operator.runtimeReadiness?.title || readinessBadgeLabel(state);
    return {
      state,
      level,
      badgeLabel: readinessBadgeLabel(state),
      nodeNotice: fallback,
      notice: operator.runtimeReadiness?.summary || fallback,
    };
  }
  return { state: '', level: 'unknown', badgeLabel: '', nodeNotice: '', notice: '' };
}

function normalizeReadinessState(value: string | undefined): string {
  return (value ?? '').trim().toLowerCase().replace(/_/g, '-');
}

function normalizeReadinessLevel(value: string | undefined): OperatorSummary['readinessLevel'] {
  const normalized = (value ?? '').trim().toLowerCase();
  if (normalized === 'success' || normalized === 'info' || normalized === 'warning' || normalized === 'error') {
    return normalized;
  }
  return 'unknown';
}

function readinessBadgeLabel(state: string): string {
  return state.replace(/-/g, ' ');
}

function operatorVisualKind(operator: OperatorDefinition): OperatorSummary['visualKind'] {
  const ref = operator.operatorRef.toLowerCase();
  const name = (operator.display?.name ?? '').toLowerCase();
  const tags = (operator.display?.tags ?? []).map((tag) => tag.toLowerCase());
  const sourceKind = operator.source?.kind?.toLowerCase() ?? '';
  const loweringMode = operator.lowering?.mode?.toLowerCase() ?? '';
  const capabilities = operator.capabilities;

  if (ref.includes('decisiontable') || ref.includes('decision_table') || name.includes('decision table')) {
    return 'decision-table';
  }
  if (ref.includes('foreach') || name.includes('foreach') || name.includes('for each')) {
    return 'foreach';
  }
  if (loweringMode === 'transform' || ref.includes('transform')) {
    return 'transform';
  }
  if (
    ref === 'httpresource'
    || ref.startsWith('resource:')
    || sourceKind === 'resource-descriptor'
    || loweringMode === 'resource-descriptor'
    || tags.includes('resource')
  ) {
    return 'resource';
  }
  if (ref === 'httprequest' || tags.includes('http') || tags.includes('api') || tags.includes('rest')) {
    return 'http';
  }
  if (capabilities?.streaming || sourceKind.includes('streaming')) {
    return 'streaming';
  }
  if (loweringMode === 'design') {
    return 'design';
  }
  if (tags.includes('rules') || tags.includes('logic')) {
    return 'decision-table';
  }
  return 'generic';
}

function operatorVisualContract(
  visualKind: OperatorSummary['visualKind'],
  firstInputType: string,
  firstOutputType: string,
): Pick<OperatorSummary, 'visualLabel' | 'contractHint' | 'inputContractLabel' | 'outputContractLabel'> {
  switch (visualKind) {
    case 'decision-table':
      return {
        visualLabel: 'Decision table',
        contractHint: 'conditions -> matched decision',
        inputContractLabel: 'conditions',
        outputContractLabel: 'decision row',
      };
    case 'foreach':
      return {
        visualLabel: 'Foreach',
        contractHint: 'collection -> per-item results',
        inputContractLabel: firstInputType === 'array' ? 'collection' : 'item source',
        outputContractLabel: firstOutputType === 'array' ? 'result list' : 'per-item output',
      };
    case 'transform':
      return {
        visualLabel: 'Transform',
        contractHint: 'source fields -> mapped output',
        inputContractLabel: 'source fields',
        outputContractLabel: 'mapped output',
      };
    case 'resource':
      return {
        visualLabel: 'Resource',
        contractHint: 'params -> payload',
        inputContractLabel: 'params',
        outputContractLabel: 'payload',
      };
    case 'http':
      return {
        visualLabel: 'HTTP',
        contractHint: 'request -> response',
        inputContractLabel: 'request',
        outputContractLabel: 'response',
      };
    case 'streaming':
      return {
        visualLabel: 'Streaming',
        contractHint: 'request -> event stream',
        inputContractLabel: firstInputType,
        outputContractLabel: 'stream',
      };
    case 'design':
      return {
        visualLabel: 'Design',
        contractHint: `schema-only ${firstInputType} -> ${firstOutputType}`,
        inputContractLabel: 'schema input',
        outputContractLabel: 'schema output',
      };
    default:
      return {
        visualLabel: 'Operator',
        contractHint: `${firstInputType} -> ${firstOutputType}`,
        inputContractLabel: firstInputType,
        outputContractLabel: firstOutputType,
      };
  }
}

function schemaRootLabel(envelope: SchemaEnvelope | undefined, fallback: string): string {
  const schema = envelope?.schema;
  if (!schema) {
    return fallback;
  }
  const rawKind = schema.kind ?? schema.type;
  if (typeof rawKind === 'string' && rawKind.trim()) {
    return rawKind.trim();
  }
  if (schema.values || schema.enum) {
    return 'enum';
  }
  if (schema.properties || schema.required || schema.additionalProperties) {
    return 'object';
  }
  if (schema.items || schema.prefixItems || schema.contains) {
    return 'array';
  }
  return fallback;
}

/**
 * Builds the operator discovery view consumed by the palette.
 *
 * <p>The server owns the catalog; the browser only organizes the already-loaded window by imported
 * library and cheap facets so authors can find the right operator without scanning a flat list.</p>
 */
export function operatorPaletteView(
  operators: OperatorDefinition[],
  query: OperatorPaletteQuery = {},
): OperatorPaletteView {
  const rows = operators.map((operator) => ({
    operator,
    summary: summarizeOperator(operator),
    libraryId: operatorLibraryId(operator),
  }));
  const terms = searchTerms(query.search ?? '');
  const searchedRows = rows.filter((row) => matchesOperatorSearch(row, terms));
  const selectedFacet = query.facet ?? 'all';
  const selectedSourceKind = normalizedFilter(query.sourceKind);
  const selectedTag = normalizedFilter(query.tag);
  const filteredRows = searchedRows.filter((row) => {
    if (selectedFacet === 'runtime' && row.summary.designOnly) {
      return false;
    }
    if (selectedFacet === 'design' && !row.summary.designOnly) {
      return false;
    }
    if (selectedSourceKind !== 'all' && row.summary.sourceKind !== selectedSourceKind) {
      return false;
    }
    if (selectedTag !== 'all' && !row.summary.tags.includes(selectedTag)) {
      return false;
    }
    return true;
  });

  return {
    totalCount: operators.length,
    matchingCount: filteredRows.length,
    runtimeFacets: [
      { key: 'all', label: 'All', count: searchedRows.length },
      {
        key: 'runtime',
        label: 'Runtime',
        count: searchedRows.filter((row) => !row.summary.designOnly).length,
      },
      {
        key: 'design',
        label: 'Design',
        count: searchedRows.filter((row) => row.summary.designOnly).length,
      },
    ],
    sourceKindFacets: countedFacets(searchedRows.map((row) => row.summary.sourceKind)),
    tagFacets: countedFacets(searchedRows.flatMap((row) => row.summary.tags)),
    groups: groupPaletteRows(filteredRows),
  };
}

/** Chooses the concise status level shown by the operator-library intake panel. */
export function operatorLibraryValidationLevel(
  result: OperatorLibraryValidationResult,
): OperatorLibraryIntakeLevel {
  if (!result.valid || hasDiagnosticLevel(result.diagnostics, 'ERROR')) {
    return 'error';
  }
  if (hasDiagnosticLevel(result.diagnostics, 'WARNING')) {
    return 'warning';
  }
  const readinessLevel = result.importReadiness?.level?.trim().toLowerCase();
  return readinessLevel === 'warning' || readinessLevel === 'error' ? readinessLevel : 'ok';
}

/** Formats one validation result for the library intake panel without duplicating server diagnostics. */
export function operatorLibraryValidationMessage(result: OperatorLibraryValidationResult): string {
  const libraryId = result.profile?.libraryId?.trim() || '';
  const operatorCount = result.importReadiness?.operatorCount ?? result.profile?.operatorCount ?? 0;
  if (!result.valid) {
    return firstDiagnosticText(result.diagnostics) || 'Operator library is invalid.';
  }
  const readinessMessage = result.importReadiness?.message?.trim();
  if (readinessMessage) {
    return libraryId ? `${libraryId}: ${readinessMessage}` : readinessMessage;
  }
  const label = libraryId || 'Operator library';
  return `${label} is valid${operatorCount ? ` (${operatorCount} operators)` : ''}.`;
}

/** Formats the stored-library response after a successful import. */
export function operatorLibraryImportMessage(library: OperatorLibrary): string {
  const libraryId = library.libraryId?.trim() || 'operator library';
  const operatorCount = library.operators?.length ?? 0;
  return `Imported ${libraryId}${operatorCount ? ` (${operatorCount} operator${operatorCount === 1 ? '' : 's'})` : ''}.`;
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
 * <p>DAGs are layered by predecessor depth, while cyclic or partially disconnected nodes fall back to
 * insertion order. Within each layer, nodes are spaced by the rendered card footprint and lightly
 * ordered by neighbouring edge barycenters. Column gaps expand only when edge labels need more room,
 * so small graphs stay dense and high-fanout graphs avoid card and label overlap.</p>
 */
export function autoLayoutCanvas(nodes: CanvasNode[], edges: CanvasEdge[]): CanvasNode[] {
  const nodeIds = new Set(nodes.map((node) => node.id));
  const indegree = new Map<string, number>();
  const outgoing = new Map<string, string[]>();
  const incoming = new Map<string, string[]>();
  const layers = new Map<string, number>();
  const originalIndex = new Map<string, number>();
  for (const node of nodes) {
    originalIndex.set(node.id, originalIndex.size);
    indegree.set(node.id, 0);
    outgoing.set(node.id, []);
    incoming.set(node.id, []);
    layers.set(node.id, 0);
  }
  for (const edge of edges) {
    if (!nodeIds.has(edge.source) || !nodeIds.has(edge.target)) {
      continue;
    }
    outgoing.get(edge.source)?.push(edge.target);
    incoming.get(edge.target)?.push(edge.source);
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

  const layerByNode = new Map<string, number>();
  const nodesByLayer = new Map<number, string[]>();
  for (const node of nodes) {
    const layer = visited.has(node.id) ? layers.get(node.id) ?? 0 : 0;
    layerByNode.set(node.id, layer);
    nodesByLayer.set(layer, [...(nodesByLayer.get(layer) ?? []), node.id]);
  }

  const maxLayer = Math.max(0, ...nodes.map((node) => layerByNode.get(node.id) ?? 0));
  const orderedLayers = orderLayoutLayers(nodesByLayer, incoming, outgoing, originalIndex, layerByNode, maxLayer);
  const topLanePlan = layoutTopLanePlan(edges, layerByNode, nodesByLayer, nodeIds);
  const maxLayerSize = Math.max(
    1,
    ...Array.from(orderedLayers.values()).map((layerNodes) => layerNodes.length),
  ) + (topLanePlan.reserved ? AUTO_LAYOUT_LONG_EDGE_BUS_LANES : 0);
  const rowPitch = AUTO_LAYOUT_NODE_HEIGHT + AUTO_LAYOUT_NODE_ROW_GAP;
  const yByNode = new Map<string, number>();
  for (let layer = 0; layer <= maxLayer; layer += 1) {
    const layerNodes = orderedLayers.get(layer) ?? [];
    const singleLongSpanEndpoint = layerNodes.length === 1 && topLanePlan.endpointNodeIds.has(layerNodes[0]);
    const availableRows = maxLayerSize - (topLanePlan.reserved ? AUTO_LAYOUT_LONG_EDGE_BUS_LANES : 0);
    const centeredOffset = ((Math.max(1, availableRows) - layerNodes.length) * rowPitch) / 2;
    const layerOffset = singleLongSpanEndpoint
      ? 0
      : topLanePlan.intermediateLayers.has(layer)
        ? AUTO_LAYOUT_LONG_EDGE_BUS_LANES * rowPitch + centeredOffset
        : ((maxLayerSize - layerNodes.length) * rowPitch) / 2;
    layerNodes.forEach((nodeId, row) => {
      yByNode.set(nodeId, AUTO_LAYOUT_TOP + layerOffset + row * rowPitch);
    });
  }

  const layerX = layoutLayerXPositions(maxLayer, edges, layerByNode, nodeIds);
  return nodes.map((node, index) => {
    const layer = layerByNode.get(node.id) ?? 0;
    return {
      ...node,
      position: {
        x: layerX.get(layer) ?? AUTO_LAYOUT_LEFT,
        y: (yByNode.get(node.id) ?? AUTO_LAYOUT_TOP) + (visited.has(node.id) ? 0 : index * 8),
      },
    };
  });
}

/** Converts one numeric viewport zoom into the stable semantic-detail policy used by the canvas. */
export function canvasZoomPresentation(zoom: number): CanvasZoomPresentation {
  const normalized = Number.isFinite(zoom) ? zoom : 1;
  if (normalized >= 0.7) {
    return { tier: 'detail', showNodeDetails: true, edgeLabelMode: 'full' };
  }
  if (normalized >= 0.45) {
    return { tier: 'compact', showNodeDetails: false, edgeLabelMode: 'summary' };
  }
  return { tier: 'overview', showNodeDetails: false, edgeLabelMode: 'hidden' };
}

/**
 * Chooses the edge copy for the current semantic zoom.
 *
 * Selected and Focus Path edges retain full coordinates at every zoom; ordinary edges collapse to
 * terminal field names and then disappear so a topology overview never becomes a cloud of text.
 */
export function canvasEdgeLabelForZoom(
  label: string,
  zoom: number,
  emphasized: boolean,
): string {
  if (!label || emphasized) {
    return label;
  }
  const presentation = canvasZoomPresentation(zoom);
  if (presentation.edgeLabelMode === 'full') {
    return label;
  }
  if (presentation.edgeLabelMode === 'hidden') {
    return '';
  }
  const [source, target] = label.split(/\s*->\s*/, 2);
  if (!target) {
    return label.length > 28 ? `${label.slice(0, 25)}...` : label;
  }
  return `${edgeLabelLastSegment(source)} -> ${edgeLabelLastSegment(target)}`;
}

/**
 * Returns the selected node's complete upstream and downstream business path.
 *
 * Side branches that are neither predecessors nor successors are excluded. Cycles are bounded by
 * visited sets, so the projection remains deterministic for partially invalid drafts.
 */
export function canvasFocusPath(
  nodes: CanvasNode[],
  edges: CanvasEdge[],
  anchorNodeId: string,
): CanvasFocusPath {
  const nodeIds = new Set(nodes.map((node) => node.id));
  if (!anchorNodeId || !nodeIds.has(anchorNodeId)) {
    return { nodeIds: new Set(), edgeIds: new Set() };
  }
  const outgoing = new Map<string, CanvasEdge[]>();
  const incoming = new Map<string, CanvasEdge[]>();
  for (const edge of edges) {
    if (!nodeIds.has(edge.source) || !nodeIds.has(edge.target)) continue;
    outgoing.set(edge.source, [...(outgoing.get(edge.source) ?? []), edge]);
    incoming.set(edge.target, [...(incoming.get(edge.target) ?? []), edge]);
  }
  const focusedNodes = new Set<string>([anchorNodeId]);
  visitFocusDirection(anchorNodeId, outgoing, (edge) => edge.target, focusedNodes);
  visitFocusDirection(anchorNodeId, incoming, (edge) => edge.source, focusedNodes);
  const focusedEdges = new Set(
    edges
      .filter((edge) => focusedNodes.has(edge.source) && focusedNodes.has(edge.target))
      .map((edge) => edge.id),
  );
  return { nodeIds: focusedNodes, edgeIds: focusedEdges };
}

function visitFocusDirection(
  anchorNodeId: string,
  adjacency: Map<string, CanvasEdge[]>,
  nextNode: (edge: CanvasEdge) => string,
  visited: Set<string>,
): void {
  const queue = [anchorNodeId];
  const traversed = new Set<string>([anchorNodeId]);
  for (let index = 0; index < queue.length; index += 1) {
    for (const edge of adjacency.get(queue[index]) ?? []) {
      const next = nextNode(edge);
      visited.add(next);
      if (!traversed.has(next)) {
        traversed.add(next);
        queue.push(next);
      }
    }
  }
}

function edgeLabelLastSegment(endpoint: string): string {
  const normalized = endpoint.trim();
  const segments = normalized.split('.');
  return segments[segments.length - 1] || normalized;
}

function layoutTopLanePlan(
  edges: CanvasEdge[],
  layerByNode: Map<string, number>,
  nodesByLayer: Map<number, string[]>,
  nodeIds: Set<string>,
): { reserved: boolean; endpointNodeIds: Set<string>; intermediateLayers: Set<number> } {
  const endpointNodeIds = new Set<string>();
  const intermediateLayers = new Set<number>();

  for (const edge of edges) {
    if (!nodeIds.has(edge.source) || !nodeIds.has(edge.target)) {
      continue;
    }
    const sourceLayer = layerByNode.get(edge.source) ?? 0;
    const targetLayer = layerByNode.get(edge.target) ?? 0;
    if (targetLayer - sourceLayer <= 1) {
      continue;
    }

    let crossesPopulatedLayer = false;
    for (let layer = sourceLayer + 1; layer < targetLayer; layer += 1) {
      if ((nodesByLayer.get(layer)?.length ?? 0) > 0) {
        crossesPopulatedLayer = true;
        intermediateLayers.add(layer);
      }
    }
    if (crossesPopulatedLayer) {
      endpointNodeIds.add(edge.source);
      endpointNodeIds.add(edge.target);
    }
  }

  return {
    reserved: endpointNodeIds.size > 0,
    endpointNodeIds,
    intermediateLayers,
  };
}

function orderLayoutLayers(
  nodesByLayer: Map<number, string[]>,
  incoming: Map<string, string[]>,
  outgoing: Map<string, string[]>,
  originalIndex: Map<string, number>,
  layerByNode: Map<string, number>,
  maxLayer: number,
): Map<number, string[]> {
  const ordered = new Map<number, string[]>();
  const rowByNode = new Map<string, number>();

  for (let layer = 0; layer <= maxLayer; layer += 1) {
    const layerNodes = [...(nodesByLayer.get(layer) ?? [])];
    layerNodes.sort((left, right) => {
      const leftWeight = layoutBarycenter(left, layer, incoming, rowByNode, layerByNode);
      const rightWeight = layoutBarycenter(right, layer, incoming, rowByNode, layerByNode);
      if (leftWeight !== rightWeight) {
        return leftWeight - rightWeight;
      }
      return (originalIndex.get(left) ?? 0) - (originalIndex.get(right) ?? 0);
    });
    ordered.set(layer, layerNodes);
    layerNodes.forEach((nodeId, row) => rowByNode.set(nodeId, row));
  }

  for (let layer = maxLayer; layer >= 0; layer -= 1) {
    const layerNodes = [...(ordered.get(layer) ?? [])];
    layerNodes.sort((left, right) => {
      const leftWeight = layoutBarycenter(left, layer, outgoing, rowByNode, layerByNode);
      const rightWeight = layoutBarycenter(right, layer, outgoing, rowByNode, layerByNode);
      if (leftWeight !== rightWeight) {
        return leftWeight - rightWeight;
      }
      return (originalIndex.get(left) ?? 0) - (originalIndex.get(right) ?? 0);
    });
    ordered.set(layer, layerNodes);
    layerNodes.forEach((nodeId, row) => rowByNode.set(nodeId, row));
  }

  return ordered;
}

function layoutBarycenter(
  nodeId: string,
  layer: number,
  neighborMap: Map<string, string[]>,
  rowByNode: Map<string, number>,
  layerByNode: Map<string, number>,
): number {
  const neighborRows = (neighborMap.get(nodeId) ?? [])
    .filter((neighborId) => (layerByNode.get(neighborId) ?? layer) !== layer)
    .map((neighborId) => rowByNode.get(neighborId))
    .filter((row): row is number => row !== undefined);
  if (neighborRows.length === 0) {
    return Number.POSITIVE_INFINITY;
  }
  return neighborRows.reduce((sum, row) => sum + row, 0) / neighborRows.length;
}

function layoutLayerXPositions(
  maxLayer: number,
  edges: CanvasEdge[],
  layerByNode: Map<string, number>,
  nodeIds: Set<string>,
): Map<number, number> {
  const minPitch = AUTO_LAYOUT_NODE_WIDTH + AUTO_LAYOUT_MIN_COLUMN_GAP;
  const gapPitch = new Map<number, number>();
  for (let layer = 0; layer < maxLayer; layer += 1) {
    gapPitch.set(layer, minPitch);
  }

  for (const edge of edges) {
    if (!nodeIds.has(edge.source) || !nodeIds.has(edge.target)) {
      continue;
    }
    const sourceLayer = layerByNode.get(edge.source) ?? 0;
    const targetLayer = layerByNode.get(edge.target) ?? 0;
    if (targetLayer <= sourceLayer) {
      continue;
    }
    const span = targetLayer - sourceLayer;
    const requiredPitch = Math.ceil(layoutRequiredPitchForEdge(edge) / span);
    for (let layer = sourceLayer; layer < targetLayer; layer += 1) {
      gapPitch.set(layer, Math.max(gapPitch.get(layer) ?? minPitch, requiredPitch));
    }
  }

  const xByLayer = new Map<number, number>([[0, AUTO_LAYOUT_LEFT]]);
  for (let layer = 1; layer <= maxLayer; layer += 1) {
    xByLayer.set(layer, (xByLayer.get(layer - 1) ?? AUTO_LAYOUT_LEFT) + (gapPitch.get(layer - 1) ?? minPitch));
  }
  return xByLayer;
}

function layoutRequiredPitchForEdge(edge: CanvasEdge): number {
  const label = `${layoutEndpointLabel(edge.sourcePort, edge.sourcePath, 'value')} -> ${
    layoutEndpointLabel(edge.targetPort, edge.targetPath, 'input')
  }`;
  const estimatedLabelWidth = label.length * AUTO_LAYOUT_EDGE_LABEL_CHAR_WIDTH + AUTO_LAYOUT_EDGE_LABEL_PADDING;
  return Math.min(
    AUTO_LAYOUT_MAX_COLUMN_PITCH,
    Math.max(AUTO_LAYOUT_NODE_WIDTH + AUTO_LAYOUT_MIN_COLUMN_GAP, Math.ceil(estimatedLabelWidth)),
  );
}

function layoutEndpointLabel(port: string | undefined, path: string | undefined, fallback: string): string {
  const base = port || fallback;
  return path ? `${base}.${path}` : base;
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

/** Turns the canvas state into a compact core-loop progress model for the authoring toolbar. */
export function authoringJourney(
  operatorCount: number,
  summary: CanvasSummary,
  fixtureRows: SimulationFixtureRow[],
  result: SimulationResponse | null,
): AuthoringJourney {
  const blockedFixtures = fixtureRows.filter((row) => row.state === 'blocked');
  const warningFixtures = fixtureRows.filter((row) => row.state === 'warning');
  const flowNeedsConnection = summary.nodeCount > 1 && summary.disconnectedNodeIds.length > 0;
  const runSucceeded = result ? isRunSuccessful(result) : false;

  const steps: AuthoringJourneyStep[] = [
    {
      key: 'library',
      label: 'Library',
      state: operatorCount > 0 ? 'ready' : 'blocked',
      detail: operatorCount > 0 ? `${operatorCount} ops` : 'empty',
    },
    {
      key: 'compose',
      label: 'Compose',
      state: summary.nodeCount > 0 ? 'ready' : operatorCount > 0 ? 'blocked' : 'pending',
      detail: `${summary.nodeCount} node${summary.nodeCount === 1 ? '' : 's'}`,
    },
    {
      key: 'flow',
      label: 'Flow',
      state:
        summary.nodeCount === 0
          ? 'pending'
          : flowNeedsConnection
            ? 'warning'
            : 'ready',
      detail:
        summary.nodeCount <= 1
          ? 'single'
          : `${summary.edgeCount} edge${summary.edgeCount === 1 ? '' : 's'}`,
    },
    {
      key: 'mocks',
      label: 'Mocks',
      state:
        summary.nodeCount === 0
          ? 'pending'
          : blockedFixtures.length > 0
            ? 'blocked'
            : warningFixtures.length > 0
              ? 'warning'
              : 'ready',
      detail:
        blockedFixtures.length > 0
          ? `${blockedFixtures.length} blocked`
          : warningFixtures.length > 0
            ? `${warningFixtures.length} sample`
            : summary.nodeCount > 0
              ? 'ready'
              : 'pending',
    },
    {
      key: 'simulate',
      label: 'Simulate',
      state:
        runSucceeded
          ? 'ready'
          : result
            ? 'blocked'
            : summary.nodeCount > 0 && blockedFixtures.length === 0
              ? 'pending'
              : 'blocked',
      detail: result ? (runSucceeded ? 'success' : 'blocked') : 'not run',
    },
  ];

  return {
    steps,
    action: nextAuthoringAction(operatorCount, summary, blockedFixtures, warningFixtures, result),
    completedCount: steps.filter((step) => step.state === 'ready').length,
  };
}

/**
 * Chooses the single canvas-level action prompt that should be visible inside the flow area.
 *
 * <p>The journey bar is comprehensive; this prompt is intentionally narrow. It keeps the empty and
 * partially wired canvas from feeling like an inert blank surface while preserving server-authoritative
 * validation as the real gate.</p>
 */
export function canvasCoachPrompt(
  operatorCount: number,
  summary: CanvasSummary,
  fixtureRows: SimulationFixtureRow[],
  result: SimulationResponse | null,
): CanvasCoachPrompt {
  const blockedFixtures = fixtureRows.filter((row) => row.state === 'blocked');
  const warningFixtures = fixtureRows.filter((row) => row.state === 'warning');
  const disconnectedCount = summary.disconnectedNodeIds.length;

  if (operatorCount === 0) {
    return {
      state: 'blocked',
      title: 'Catalog empty',
      detail: '0 operators',
      body: 'Import an operator library before composing.',
      action: { kind: 'none', label: 'Catalog empty' },
    };
  }
  if (summary.nodeCount === 0) {
    return {
      state: 'compose',
      title: 'Add first operator',
      detail: `${operatorCount} available`,
      body: 'Choose one operator from the palette to create the first node.',
      action: { kind: 'focus-palette', label: 'Add operator' },
    };
  }
  if (summary.nodeCount > 1 && disconnectedCount > 0) {
    const nodeId = summary.disconnectedNodeIds[0];
    return {
      state: 'connect',
      title: 'Connect open nodes',
      detail: `${disconnectedCount} open`,
      body: `Find compatible targets for ${nodeId}.`,
      action: { kind: 'select-node', label: 'Find targets', nodeId, guide: 'connection-guide' },
    };
  }
  if (blockedFixtures.length > 0) {
    const fixture = blockedFixtures[0];
    return {
      state: 'blocked',
      title: 'Fix mock JSON',
      detail: `${blockedFixtures.length} blocked`,
      body: `${fixture.label} has invalid fixture JSON.`,
      action: { kind: 'select-node', label: 'Fix mock JSON', nodeId: fixture.nodeId },
    };
  }
  if (!result && warningFixtures.length > 0) {
    const fixture = warningFixtures[0];
    return {
      state: 'mock',
      title: 'Pin mock output',
      detail: `${warningFixtures.length} sample`,
      body: `Review the generated sample for ${fixture.label}.`,
      action: { kind: 'select-node', label: 'Pin mock output', nodeId: fixture.nodeId },
    };
  }
  if (!result) {
    return {
      state: 'simulate',
      title: 'Run simulation',
      detail: `${summary.nodeCount} node${summary.nodeCount === 1 ? '' : 's'}`,
      body: `Use ${summary.outputNodeId || 'the output node'} as the terminal result.`,
      action: { kind: 'simulate', label: 'Simulate' },
    };
  }
  if (!isRunSuccessful(result)) {
    return {
      state: 'review',
      title: 'Review diagnostics',
      detail: `${result.errors?.length ?? 0} error${(result.errors?.length ?? 0) === 1 ? '' : 's'}`,
      body: 'Fix the reported issue, then retry the simulation.',
      action: { kind: 'simulate', label: 'Retry simulate' },
    };
  }
  return {
    state: 'ready',
    title: 'Graph ready',
    detail: `${result.realNodeIds?.length ?? 0} real / ${result.mockedNodeIds?.length ?? 0} mocked`,
    body: 'Simulation completed; mocked nodes remain marked on the canvas.',
    action: { kind: 'none', label: 'Ready' },
  };
}

/**
 * Projects the canvas-level coach action onto a single node's visual focus state.
 *
 * <p>This keeps the "what should I touch next?" hint testable and separate from React Flow. A
 * manually selected node always wins over a suggested next node, and server-side validation remains
 * the authoritative gate for actual connections and simulation.</p>
 */
export function canvasNodeFocusState(
  nodeId: string,
  selectedNodeId: string,
  coachPrompt: CanvasCoachPrompt | null | undefined,
): CanvasNodeFocusState {
  if (nodeId && nodeId === selectedNodeId) {
    return 'selected';
  }
  if (coachPrompt?.action.kind === 'select-node' && coachPrompt.action.nodeId === nodeId) {
    return 'suggested';
  }
  return 'none';
}

function nextAuthoringAction(
  operatorCount: number,
  summary: CanvasSummary,
  blockedFixtures: SimulationFixtureRow[],
  warningFixtures: SimulationFixtureRow[],
  result: SimulationResponse | null,
): AuthoringJourneyAction {
  if (operatorCount === 0) {
    return { kind: 'none', label: 'Catalog empty' };
  }
  if (summary.nodeCount === 0) {
    return { kind: 'focus-palette', label: 'Add operator' };
  }
  if (blockedFixtures.length > 0) {
    return { kind: 'select-node', label: 'Fix mock JSON', nodeId: blockedFixtures[0].nodeId };
  }
  if (!result && warningFixtures.length > 0) {
    return { kind: 'select-node', label: 'Pin mock output', nodeId: warningFixtures[0].nodeId };
  }
  if (!result) {
    return { kind: 'simulate', label: 'Simulate' };
  }
  if (!isRunSuccessful(result)) {
    return { kind: 'simulate', label: 'Retry simulate' };
  }
  return { kind: 'none', label: 'Ready' };
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
 * Compresses the latest simulation state into one scan-friendly result header.
 *
 * <p>The detailed trace remains below it; this summary exists so authors can immediately see whether
 * a run is trustworthy, which node produced the terminal output, and whether fixtures shaped the
 * result.</p>
 */
export function simulationRunSummary(
  summary: CanvasSummary,
  fixtureRows: SimulationFixtureRow[],
  response: SimulationResponse | null,
): SimulationRunSummary {
  const blockedFixtures = fixtureRows.filter((row) => row.state === 'blocked').length;
  const pinnedFixtures = fixtureRows.filter((row) => row.fixtureLabel !== 'server sample').length;
  const sampleMocks = fixtureRows.filter((row) => row.state === 'warning').length;

  if (!response) {
    return {
      state: blockedFixtures > 0 ? 'blocked' : 'pending',
      title: blockedFixtures > 0 ? 'Simulation blocked' : 'Ready to simulate',
      detail: blockedFixtures > 0
        ? `${blockedFixtures} fixture JSON error${blockedFixtures === 1 ? '' : 's'}`
        : `${summary.nodeCount} node${summary.nodeCount === 1 ? '' : 's'} selected`,
      chips: [
        {
          key: 'terminal',
          label: 'Terminal',
          value: summary.outputNodeId || 'missing',
          state: summary.outputNodeId ? 'ready' : 'blocked',
        },
        {
          key: 'fixtures',
          label: 'Fixtures',
          value: blockedFixtures > 0
            ? `${blockedFixtures} invalid`
            : pinnedFixtures > 0
              ? `${pinnedFixtures} pinned`
              : 'none',
          state: blockedFixtures > 0 ? 'blocked' : pinnedFixtures > 0 ? 'ready' : 'pending',
        },
        {
          key: 'mock-samples',
          label: 'Mock Samples',
          value: sampleMocks > 0 ? `${sampleMocks} sample${sampleMocks === 1 ? '' : 's'}` : 'none',
          state: sampleMocks > 0 ? 'warning' : 'ready',
        },
      ],
    };
  }

  const successful = isRunSuccessful(response);
  const mockedCount = response.mockedNodeIds?.length ?? 0;
  const realCount = response.realNodeIds?.length ?? 0;
  const errorCount = response.errors?.length ?? 0;
  const diagnosticCount = response.diagnostics?.length ?? 0;
  return {
    state: successful ? 'success' : 'blocked',
    title: successful ? 'Simulation succeeded' : 'Simulation blocked',
    detail: successful
      ? `${realCount} real / ${mockedCount} mocked`
      : `${errorCount} error${errorCount === 1 ? '' : 's'}`,
    chips: [
      {
        key: 'terminal',
        label: 'Terminal',
        value: response.outputNode || summary.outputNodeId || 'missing',
        state: response.outputNode || summary.outputNodeId ? 'ready' : 'blocked',
      },
      {
        key: 'trust',
        label: 'Trust',
        value: `${realCount} real / ${mockedCount} mocked`,
        state: mockedCount > 0 ? 'warning' : 'ready',
      },
      {
        key: 'fixtures',
        label: 'Fixtures',
        value: blockedFixtures > 0
          ? `${blockedFixtures} invalid`
          : pinnedFixtures > 0
            ? `${pinnedFixtures} pinned`
            : 'none',
        state: blockedFixtures > 0 ? 'blocked' : pinnedFixtures > 0 ? 'ready' : 'pending',
      },
      {
        key: 'diagnostics',
        label: 'Diagnostics',
        value: `${diagnosticCount} diagnostic${diagnosticCount === 1 ? '' : 's'}`,
        state: diagnosticCount > 0 || errorCount > 0 ? 'warning' : 'ready',
      },
    ],
  };
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

/** Parses the canvas-side table test rows into request-ready cases. */
export function compileSimulationTableRows(rows: SimulationTableTestDraftRow[]): SimulationTableCompilation {
  const cases: SimulationTableTestCase[] = [];
  const errors: Record<string, string> = {};

  for (const row of rows) {
    const messages: string[] = [];
    const context = parseTableJsonObject(row.contextText, 'Context', messages);
    const fixtures = parseTableFixtures(row.fixturesText, messages);
    const trimmedExpected = row.expectedOutputText.trim();
    let expectedOutput: unknown;

    if (trimmedExpected) {
      try {
        expectedOutput = JSON.parse(trimmedExpected) as unknown;
      } catch (cause: unknown) {
        messages.push(`Expected output must be valid JSON: ${jsonErrorMessage(cause)}`);
      }
    }

    if (messages.length > 0) {
      errors[row.id] = messages.join(' ');
      continue;
    }

    cases.push({
      id: row.id,
      name: row.name.trim() || row.id,
      context,
      fixtures,
      hasExpectedOutput: Boolean(trimmedExpected),
      ...(trimmedExpected ? { expectedOutput } : {}),
    });
  }

  return { cases, errors };
}

/** Merges authored node fixtures with row-local overrides for one table run. */
export function mergeNodeFixtures(
  baseFixtures: Record<string, NodeFixture>,
  overrideFixtures: Record<string, NodeFixture>,
): Record<string, NodeFixture> {
  return {
    ...baseFixtures,
    ...overrideFixtures,
  };
}

/** Evaluates one simulation response against the row's expected terminal output. */
export function evaluateSimulationTableResult(
  testCase: SimulationTableTestCase,
  response: SimulationResponse,
): SimulationTableCaseResult {
  if (!isRunSuccessful(response)) {
    return {
      id: testCase.id,
      name: testCase.name,
      status: 'failed',
      detail: response.errors?.[0] || `${response.diagnostics?.length ?? 0} diagnostic(s)`,
      actualOutput: response.output,
      ...(testCase.hasExpectedOutput ? { expectedOutput: testCase.expectedOutput } : {}),
    };
  }

  if (!testCase.hasExpectedOutput) {
    return {
      id: testCase.id,
      name: testCase.name,
      status: 'passed',
      detail: 'Simulation succeeded; no output assertion.',
      actualOutput: response.output,
    };
  }

  if (jsonValuesEqual(response.output, testCase.expectedOutput)) {
    return {
      id: testCase.id,
      name: testCase.name,
      status: 'passed',
      detail: 'Output matched.',
      actualOutput: response.output,
      expectedOutput: testCase.expectedOutput,
    };
  }

  return {
    id: testCase.id,
    name: testCase.name,
    status: 'failed',
    detail: 'Output mismatch.',
    actualOutput: response.output,
    expectedOutput: testCase.expectedOutput,
  };
}

/** Summarizes table testing progress for the inspector header. */
export function simulationTableSummary(
  rows: SimulationTableTestDraftRow[],
  results: Record<string, SimulationTableCaseResult>,
  running: boolean,
): SimulationTableSummary {
  const total = rows.length;
  const passed = rows.filter((row) => results[row.id]?.status === 'passed').length;
  const failed = rows.filter((row) => results[row.id]?.status === 'failed').length;

  if (running) {
    return {
      state: 'running',
      label: 'Running',
      detail: `${passed}/${total} passed`,
      passed,
      failed,
      total,
    };
  }
  if (total === 0) {
    return {
      state: 'pending',
      label: 'No cases',
      detail: 'Add test rows',
      passed,
      failed,
      total,
    };
  }
  if (failed > 0) {
    return {
      state: 'failed',
      label: 'Needs repair',
      detail: `${failed}/${total} failed`,
      passed,
      failed,
      total,
    };
  }
  if (passed === total) {
    return {
      state: 'passed',
      label: 'All passed',
      detail: `${passed}/${total} passed`,
      passed,
      failed,
      total,
    };
  }
  return {
    state: 'pending',
    label: 'Not run',
    detail: `${passed}/${total} passed`,
    passed,
    failed,
    total,
  };
}

function parseTableJsonObject(
  text: string,
  label: string,
  messages: string[],
): Record<string, unknown> {
  const trimmed = text.trim();
  if (!trimmed) {
    return {};
  }
  try {
    const value = JSON.parse(trimmed) as unknown;
    if (!isRecord(value)) {
      messages.push(`${label} must be a JSON object.`);
      return {};
    }
    return value;
  } catch (cause: unknown) {
    messages.push(`${label} must be valid JSON: ${jsonErrorMessage(cause)}`);
    return {};
  }
}

function parseTableFixtures(text: string, messages: string[]): Record<string, NodeFixture> {
  const raw = parseTableJsonObject(text, 'Fixture overrides', messages);
  const fixtures: Record<string, NodeFixture> = {};
  for (const [nodeId, fixture] of Object.entries(raw)) {
    if (!isRecord(fixture)) {
      messages.push(`Fixture override for ${nodeId} must be an object.`);
      continue;
    }
    fixtures[nodeId] = {
      output: hasOwn(fixture, 'output') ? fixture.output : null,
      ...(hasOwn(fixture, 'expectedInput') ? { expectedInput: fixture.expectedInput } : {}),
    };
  }
  return fixtures;
}

function jsonValuesEqual(left: unknown, right: unknown): boolean {
  return canonicalJson(left) === canonicalJson(right);
}

function canonicalJson(value: unknown): string {
  return JSON.stringify(sortJsonValue(value));
}

function sortJsonValue(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map((item) => sortJsonValue(item));
  }
  if (isRecord(value)) {
    return Object.keys(value)
      .sort()
      .reduce<Record<string, unknown>>((sorted, key) => {
        sorted[key] = sortJsonValue(value[key]);
        return sorted;
      }, {});
  }
  return value;
}

function jsonErrorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause);
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

function operatorLibraryId(operator: OperatorDefinition): string {
  const explicitLibraryId = operator.source?.libraryId?.trim();
  if (explicitLibraryId) {
    return explicitLibraryId;
  }
  const operatorRef = operator.operatorRef ?? '';
  const namespaceEnd = operatorRef.indexOf(':');
  if (namespaceEnd > 0) {
    return operatorRef.slice(0, namespaceEnd);
  }
  return operator.source?.kind?.trim() || 'library';
}

function normalizedFilter(value: string | undefined): string {
  return value?.trim() || 'all';
}

function searchTerms(search: string): string[] {
  return search.toLowerCase().split(/\s+/).filter(Boolean);
}

function matchesOperatorSearch(row: OperatorPaletteRow, terms: string[]): boolean {
  if (terms.length === 0) {
    return true;
  }
  const haystack = [
    row.summary.name,
    row.summary.operatorRef,
    row.summary.description,
    row.summary.sourceKind,
    row.summary.visualKind,
    row.summary.visualLabel,
    row.summary.contractHint,
    row.libraryId,
    ...row.summary.tags,
    ...row.summary.inputNames,
    ...row.summary.outputNames,
  ].join(' ').toLowerCase();
  return terms.every((term) => haystack.includes(term));
}

function hasDiagnosticLevel(diagnostics: VisualDiagnostic[] | undefined, level: string): boolean {
  return (diagnostics ?? []).some((diagnostic) => diagnostic.level?.toUpperCase() === level);
}

function firstDiagnosticText(diagnostics: VisualDiagnostic[] | undefined): string {
  const diagnostic = diagnostics?.find((item) => item.message || item.code);
  if (!diagnostic) {
    return '';
  }
  return `${diagnostic.code ? `${diagnostic.code}: ` : ''}${diagnostic.message ?? ''}`.trim();
}

function countedFacets(values: string[]): OperatorPaletteFacetCount[] {
  const counts = new Map<string, number>();
  for (const value of values) {
    const normalized = value.trim();
    if (!normalized) {
      continue;
    }
    counts.set(normalized, (counts.get(normalized) ?? 0) + 1);
  }
  return Array.from(counts.entries())
    .sort(([leftKey, leftCount], [rightKey, rightCount]) =>
      rightCount - leftCount || leftKey.localeCompare(rightKey))
    .map(([key, count]) => ({ key, label: key, count }));
}

function groupPaletteRows(rows: OperatorPaletteRow[]): OperatorPaletteGroup[] {
  const groups = new Map<string, OperatorPaletteRow[]>();
  for (const row of rows) {
    const groupRows = groups.get(row.libraryId);
    if (groupRows) {
      groupRows.push(row);
    } else {
      groups.set(row.libraryId, [row]);
    }
  }
  return Array.from(groups.entries())
    .map(([libraryId, groupRows]) => {
      const sourceKinds = Array.from(new Set(groupRows.map((row) => row.summary.sourceKind))).sort();
      const sortedRows = [...groupRows].sort((left, right) =>
        left.summary.name.localeCompare(right.summary.name)
        || left.summary.operatorRef.localeCompare(right.summary.operatorRef));
      return {
        libraryId,
        label: libraryId,
        count: sortedRows.length,
        sourceKinds,
        rows: sortedRows,
      };
    })
    .sort((left, right) => left.label.localeCompare(right.label));
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
