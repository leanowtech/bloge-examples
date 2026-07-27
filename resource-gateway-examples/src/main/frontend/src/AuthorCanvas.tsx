import {
  type CSSProperties,
  type DragEvent,
  lazy,
  Suspense,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import ReactFlow, {
  addEdge,
  applyEdgeChanges,
  applyNodeChanges,
  Background,
  BaseEdge,
  Controls,
  EdgeLabelRenderer,
  Handle,
  MiniMap,
  type Connection,
  type Edge,
  type EdgeChange,
  type EdgeProps,
  type EdgeTypes,
  type Node,
  type NodeChange,
  type NodeProps,
  type ReactFlowInstance,
  Position,
  getSmoothStepPath,
} from 'reactflow';
import 'reactflow/dist/style.css';

import {
  adaptCapabilityCatalogText,
  checkConnection,
  checkDslRewriteGate,
  commitDslImport,
  fetchConnectionCandidates,
  fetchGovernanceGateView,
  fetchGraphDraft,
  fetchOperatorCatalog,
  fetchScenarioGraphContract,
  fetchVisualGraphRun,
  governOperatorTestSuite,
  importOperatorLibraryText,
  previewDslImport,
  runOperatorTestCase,
  saveGraphDraft,
  simulate,
  validateDraft,
  validateOperatorLibraryText,
} from './api';
import {
  authoringJourney,
  autoLayoutCanvas,
  canvasEdgeBindingKey,
  canvasCoachPrompt,
  canvasNodeFocusState,
  type CanvasEdge,
  type CanvasNodeFocusState,
  type CanvasNode,
  type AuthoringJourneyAction,
  type ConnectionCandidateIndex,
  type ConnectionGuideFieldOption,
  type ConnectionCandidateStatus,
  type ConnectionGuideRow,
  compileFixtureDrafts,
  compileSimulationTableRows,
  connectionCandidatesMessage,
  connectionGuideRows,
  type OperatorSummary,
  connectionDecisionMessage,
  evaluateSimulationTableResult,
  fixtureDraftForOperator,
  fromGraphDraft,
  handleIdForPort,
  indexConnectionCandidates,
  isRunSuccessful,
  mergeNodeFixtures,
  nodeStatuses,
  type OperatorPaletteFacet,
  operatorPaletteView,
  operatorLibraryImportMessage,
  operatorLibraryValidationLevel,
  operatorLibraryValidationMessage,
  portNameFromHandle,
  sampleFromSchemaEnvelope,
  simulationChecklist,
  simulationFixtureRows,
  simulationRunSummary,
  simulationTableSummary,
  simulationTraceRows,
  summarizeCanvas,
  summarizeOperator,
  type SimulationTableCaseResult,
  type SimulationTableTestDraftRow,
  toConnectionCandidatesRequest,
  toConnectionCheckRequest,
  toExportableGraphDraft,
  toSimulationRequest,
} from './draftModel';
import type {
  DraftNodeBinding,
  BuiltInFunctionDefinition,
  DslImportCoverage,
  DslRoundTripSummary,
  DslRewriteGateResult,
  DslSourceMap,
  DslSourceSpan,
  DslVisualProjection,
  GraphDraft,
  GraphDraftImportResult,
  GovernanceGateIssue,
  GovernanceGateView,
  OperatorDefinition,
  OperatorLibrary,
  OperatorPort,
  OperatorTestSuiteCaseType,
  OperatorTestSuiteRun,
  SchemaEnvelope,
  SimulationResponse,
  VisualDiagnostic,
  VisualGraphRunRecord,
  VisualValidationResult,
} from './types';
import type { ConnectionCandidate } from './types';
import {
  CANVAS_EXAMPLE_TEMPLATES,
  exampleEdgeLabel,
  exampleRequiredOperatorRefs,
  hasOwnValue,
  maxNumericNodeId,
  type CanvasExampleAvailability,
  type CanvasExampleTestCase,
  type CanvasExampleTemplate,
} from './canvasExamples';
import ContractRail from './contract-scenario/ContractRail';
import {
  contractDraftFromGraphDraft,
  type ContractDraft,
  type ScenarioDraftSet,
} from './contract-scenario/domain';
import { canonicalJson, sha256Fingerprint } from './contract-scenario/fingerprint';
import {
  rebaseScenarioDraftSet,
  scenarioDraftSetFromCanvas,
  type ScenarioNodeOption,
} from './contract-scenario/scenarioAuthoring';

const ContractScenarioWorkspace = lazy(
  () => import('./contract-scenario/ContractScenarioWorkspace'),
);

interface NodeData {
  label: string;
  operatorRef: string;
  summary: OperatorSummary;
  inputs?: Record<string, DraftNodeBinding>;
  config?: Record<string, unknown>;
  status?: 'mocked' | 'real' | 'unknown';
  isOutput?: boolean;
  candidateStatus?: ConnectionCandidateStatus;
  candidatePorts?: Record<string, ConnectionCandidateStatus>;
  focusState?: CanvasNodeFocusState;
}

interface ConnectionNotice {
  level: 'ok' | 'warning' | 'error' | 'pending';
  message: string;
}

interface ConnectionStartParams {
  nodeId: string | null;
  handleId: string | null;
  handleType: string | null;
}

function governanceIssueNodeId(issue: GovernanceGateIssue): string {
  const targetMatch = (issue.targetPath ?? '').match(/(?:^|\/)nodes\/([^/?#]+)/);
  if (targetMatch) {
    return decodeURIComponent(targetMatch[1]);
  }
  if (!issue.deepLink) {
    return '';
  }
  try {
    return new URL(issue.deepLink, window.location.origin).searchParams.get('nodeId') ?? '';
  } catch {
    return '';
  }
}

function governanceGateLevel(view: GovernanceGateView): 'ok' | 'warning' | 'error' {
  if (view.freshness !== 'CURRENT') {
    return 'warning';
  }
  const status = view.result?.status?.toUpperCase() ?? 'UNKNOWN';
  if (status === 'PASSED' || status === 'APPROVED') {
    return 'ok';
  }
  if (status === 'BLOCKED' || status === 'FAILED' || status === 'REJECTED') {
    return 'error';
  }
  return 'warning';
}

interface SelectedConnectionGuide {
  nodeId: string;
  sourcePort: string;
  index: ConnectionCandidateIndex;
}

interface CheckedConnectionParams {
  sourceNodeId: string;
  targetNodeId: string;
  sourceHandleId?: string | null;
  targetHandleId?: string | null;
  sourcePath?: string;
  targetPath?: string;
}

interface CanvasEdgeData {
  sourcePath?: string;
  targetPath?: string;
  bindingKey?: string;
  kind?: string;
  condition?: string;
  labelLane?: number;
}

type CanvasFlowEdge = Edge<CanvasEdgeData> & {
  sourcePath?: string;
  targetPath?: string;
  bindingKey?: string;
  kind?: string;
  condition?: string;
};

const CANVAS_DATA_EDGE_TYPE = 'canvasDataEdge';
const EDGE_LABEL_DIAGONAL_OFFSET = 48;
const EDGE_LABEL_LANE_STEP = 30;
const CANVAS_MIN_ZOOM = 0.04;
const CANVAS_MAX_ZOOM = 1.8;
const COMPLEX_GRAPH_NODE_THRESHOLD = 24;
const COMPLEX_GRAPH_EDGE_THRESHOLD = 36;

const EDGE_LABEL_OPTIONS = {
  type: CANVAS_DATA_EDGE_TYPE,
  interactionWidth: 18,
};

function CanvasDataEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  markerStart,
  markerEnd,
  style,
  interactionWidth,
  label,
  selected,
  data,
}: EdgeProps<CanvasEdgeData>) {
  const [edgePath, labelX, labelY] = getSmoothStepPath({
    sourceX,
    sourceY,
    sourcePosition,
    targetX,
    targetY,
    targetPosition,
    borderRadius: 18,
    offset: 42,
  });
  const labelText = typeof label === 'string' || typeof label === 'number' ? String(label) : '';
  const labelLane = data?.labelLane ?? 0;
  const labelOffsetX = Math.abs(targetY - sourceY) > 60
    ? targetY > sourceY
      ? -EDGE_LABEL_DIAGONAL_OFFSET
      : EDGE_LABEL_DIAGONAL_OFFSET
    : 0;
  const labelOffsetY = labelLane * EDGE_LABEL_LANE_STEP;

  return (
    <>
      <BaseEdge
        id={id}
        path={edgePath}
        markerStart={markerStart}
        markerEnd={markerEnd}
        style={style}
        interactionWidth={interactionWidth}
      />
      {labelText && (
        <EdgeLabelRenderer>
          <div
            className={['flow-edge-label', selected ? 'selected' : ''].filter(Boolean).join(' ')}
            data-edge-id={id}
            data-testid="canvas-edge-label"
            style={{
              transform: `translate(-50%, -50%) translate(${labelX + labelOffsetX}px, ${labelY + labelOffsetY}px)`,
            }}
          >
            {labelText}
          </div>
        </EdgeLabelRenderer>
      )}
    </>
  );
}

function minimapNodeColor(node: Node<NodeData>): string {
  switch (node.data?.summary?.visualKind) {
    case 'decision-table':
      return '#0f766e';
    case 'transform':
      return '#7c3aed';
    case 'resource':
    case 'http':
      return '#2563eb';
    case 'foreach':
      return '#b45309';
    case 'streaming':
      return '#047857';
    default:
      return '#64748b';
  }
}

function canvasNodeFromFlowNode(node: Node<NodeData>): CanvasNode {
  return {
    id: node.id,
    operatorRef: node.data.operatorRef,
    label: node.data.label,
    inputs: node.data.inputs,
    config: node.data.config,
    position: node.position,
  };
}

function canvasEdgeFromFlowEdge(edge: Edge<CanvasEdgeData>): CanvasEdge {
  const pathEdge = edge as CanvasFlowEdge;
  const edgeData = edge.data as CanvasEdgeData | undefined;
  return {
    id: edge.id,
    source: edge.source,
    target: edge.target,
    kind: pathEdge.kind ?? edgeData?.kind,
    sourcePort: portNameFromHandle(edge.sourceHandle, 'out'),
    targetPort: portNameFromHandle(edge.targetHandle, 'in'),
    sourcePath: pathEdge.sourcePath ?? edgeData?.sourcePath,
    targetPath: pathEdge.targetPath ?? edgeData?.targetPath,
    bindingKey: pathEdge.bindingKey ?? edgeData?.bindingKey,
    condition: pathEdge.condition ?? edgeData?.condition,
  };
}

function autoLayoutFlowNodes(
  flowNodes: Node<NodeData>[],
  flowEdges: Edge<CanvasEdgeData>[],
): Node<NodeData>[] {
  const layout = autoLayoutCanvas(flowNodes.map(canvasNodeFromFlowNode), flowEdges.map(canvasEdgeFromFlowEdge));
  const positions = new Map(layout.map((node) => [node.id, node.position]));
  return flowNodes.map((node) => ({
    ...node,
    position: positions.get(node.id) ?? node.position,
  }));
}

const EDGE_TYPES: EdgeTypes = {
  [CANVAS_DATA_EDGE_TYPE]: CanvasDataEdge,
};

function edgeLaneFor(index: number, count: number): number {
  return index - (count - 1) / 2;
}

function edgeParallelKey(edge: Edge): string {
  return [
    edge.source,
    edge.target,
    edge.sourceHandle ?? '',
    edge.targetHandle ?? '',
  ].join('::');
}

function withEdgeLabelLanes(edges: Edge<CanvasEdgeData>[]): Edge<CanvasEdgeData>[] {
  const groups = new Map<string, Edge<CanvasEdgeData>[]>();
  for (const edge of edges) {
    const key = edgeParallelKey(edge);
    groups.set(key, [...(groups.get(key) ?? []), edge]);
  }

  return edges.map((edge) => {
    const group = groups.get(edgeParallelKey(edge)) ?? [edge];
    const index = group.findIndex((candidate) => candidate.id === edge.id);
    const count = group.length;
    return {
      ...edge,
      type: CANVAS_DATA_EDGE_TYPE,
      interactionWidth: edge.interactionWidth ?? EDGE_LABEL_OPTIONS.interactionWidth,
      labelShowBg: false,
      data: {
        ...(edge.data ?? {}),
        labelLane: edgeLaneFor(Math.max(0, index), count),
      },
    };
  });
}

interface OperatorFocusRow {
  key: string;
  label: string;
  value: string;
}

interface DecisionTableColumn {
  id: string;
  label: string;
  locked?: boolean;
  sourceLabel?: string;
}

interface DecisionTableRuleRow {
  conditions: Record<string, string>;
  outputs: Record<string, string>;
  otherwise: boolean;
}

interface DecisionTableEditorModel {
  hitPolicy: string;
  outputType: string;
  conditionColumns: DecisionTableColumn[];
  outputColumns: DecisionTableColumn[];
  rows: DecisionTableRuleRow[];
}

interface TransformAssignmentRow {
  field: string;
  expression: string;
}

interface TransformEditorModel {
  assignments: TransformAssignmentRow[];
}

interface OperatorTestSuiteDraftRow {
  id: string;
  name: string;
  caseType: OperatorTestSuiteCaseType;
  inputText: string;
  outputText: string;
  transportResponseText: string;
}

type OperatorTestCaseStatus = 'pending' | 'running' | 'passed' | 'failed';

interface OperatorTestCaseResult {
  id: string;
  name: string;
  status: OperatorTestCaseStatus;
  detail: string;
  actualOutput?: unknown;
  expectedInput?: unknown;
  fixtureOutput?: unknown;
  runId?: string;
  evidenceClass?: string;
}

interface OperatorTestSuitePublicationResult {
  status: OperatorTestCaseStatus;
  detail: string;
  suiteId?: string;
  revision?: number;
  suiteRunId?: string;
}

interface OperatorTestSuiteCompilation {
  input?: unknown;
  output?: unknown;
  transportResponse?: unknown;
  error?: string;
}

interface OperatorNodeMetrics {
  requiredInputCount: number;
  inputCount: number;
  outputCount: number;
}

interface JsonObjectCompilation {
  value: Record<string, unknown>;
  error?: string;
}

type ContextVariableType = 'string' | 'number' | 'boolean' | 'json';

interface ContextVariableRow {
  id: string;
  path: string;
  valueType: ContextVariableType;
  sample: string;
}

const CONTEXT_VARIABLE_DRAG_TYPE = 'application/bloge-context-path';
const EMPTY_GRAPH_INPUT_SCHEMA: SchemaEnvelope = {
  format: 'json-schema',
  version: '2020-12',
  schema: {
    type: 'object',
    properties: {},
    required: [],
    additionalProperties: true,
  },
};

function handleOffset(index: number, count: number): CSSProperties {
  return { top: `${((index + 1) / (count + 1)) * 100}%` };
}

function defaultOperatorPosition(index: number, canvasWidth: number): { x: number; y: number } {
  if (canvasWidth > 0 && canvasWidth < 640) {
    return { x: 72, y: 56 + index * 190 };
  }
  return { x: 72 + (index % 3) * 280, y: 56 + Math.floor(index / 3) * 170 };
}

function endpointLabel(port: string, path: string, fallback: string): string {
  const base = port || fallback;
  return path ? `${base}.${path}` : base;
}

function singleAcceptedFieldCandidate(
  candidates: ConnectionCandidate[] | undefined,
  targetNodeId: string,
  targetPort: string,
): ConnectionCandidate | null {
  const matches = (candidates ?? []).filter((candidate) =>
    candidate.accepted
    && (candidate.target.nodeId || candidate.targetNodeId) === targetNodeId
    && (candidate.target.port ?? '') === targetPort
    && Boolean(candidate.target.path),
  );
  return matches.length === 1 ? matches[0] : null;
}

function acceptedFieldCandidateLabels(
  candidates: ConnectionCandidate[] | undefined,
  targetNodeId: string,
  targetPort: string,
): string[] {
  return (candidates ?? [])
    .filter((candidate) =>
      candidate.accepted
      && (candidate.target.nodeId || candidate.targetNodeId) === targetNodeId
      && (candidate.target.port ?? '') === targetPort
      && Boolean(candidate.target.path),
    )
    .map((candidate) => endpointLabel(candidate.target.port ?? '', candidate.target.path ?? '', 'input'));
}

function operatorNodeMetrics(summary: OperatorSummary, config: Record<string, unknown> | undefined): OperatorNodeMetrics {
  if (summary.visualKind !== 'decision-table' || !config) {
    return {
      requiredInputCount: summary.requiredInputCount,
      inputCount: summary.inputCount,
      outputCount: summary.outputCount,
    };
  }
  const editor = decisionTableEditorModel(config);
  const inputCount = editor.conditionColumns.length || summary.inputCount;
  return {
    requiredInputCount: inputCount,
    inputCount,
    outputCount: editor.outputColumns.length || summary.outputCount,
  };
}

function compileJsonObjectDraft(text: string, label: string): JsonObjectCompilation {
  const trimmed = text.trim();
  if (!trimmed) {
    return { value: {} };
  }
  try {
    const value = JSON.parse(trimmed) as unknown;
    if (!isRecord(value)) {
      return { value: {}, error: `${label} must be a JSON object.` };
    }
    return { value };
  } catch {
    return { value: {}, error: `${label} must be valid JSON.` };
  }
}

function contextPathSegments(path: string): string[] {
  return path
    .trim()
    .replace(/^ctx\./, '')
    .split('.')
    .map((segment) => segment.trim())
    .filter(Boolean);
}

function normalizedContextPath(path: string): string {
  return contextPathSegments(path).join('.');
}

function contextBindingKey(path: string): string {
  const segments = contextPathSegments(path);
  return decisionFieldName(segments[segments.length - 1] ?? path, 'input');
}

function parseContextVariableValue(row: ContextVariableRow, index: number): { value: unknown; error?: string } {
  const label = row.path.trim() || `context variable ${index + 1}`;
  const raw = row.sample.trim();
  if (row.valueType === 'number') {
    const value = Number(raw);
    if (!raw || !Number.isFinite(value)) {
      return { value: 0, error: `${label} must use a numeric sample value.` };
    }
    return { value };
  }
  if (row.valueType === 'boolean') {
    if (raw.toLowerCase() === 'true') {
      return { value: true };
    }
    if (raw.toLowerCase() === 'false') {
      return { value: false };
    }
    return { value: false, error: `${label} must be true or false.` };
  }
  if (row.valueType === 'json') {
    if (!raw) {
      return { value: null };
    }
    try {
      return { value: JSON.parse(raw) as unknown };
    } catch {
      return { value: null, error: `${label} must use valid JSON sample value.` };
    }
  }
  return { value: row.sample };
}

function assignContextVariableValue(
  target: Record<string, unknown>,
  segments: string[],
  value: unknown,
): string | undefined {
  let cursor = target;
  for (let index = 0; index < segments.length - 1; index += 1) {
    const segment = segments[index];
    const existing = cursor[segment];
    if (existing === undefined) {
      const next: Record<string, unknown> = {};
      cursor[segment] = next;
      cursor = next;
      continue;
    }
    if (!isRecord(existing)) {
      return `${segments.slice(0, index + 1).join('.')} already contains a scalar value.`;
    }
    cursor = existing;
  }
  cursor[segments[segments.length - 1]] = value;
  return undefined;
}

function compileContextVariables(rows: ContextVariableRow[]): JsonObjectCompilation {
  const context: Record<string, unknown> = {};
  for (let index = 0; index < rows.length; index += 1) {
    const row = rows[index];
    if (!row.path.trim()) {
      continue;
    }
    const segments = contextPathSegments(row.path);
    if (segments.length === 0) {
      return { value: {}, error: `Context variable ${index + 1} needs a path.` };
    }
    const parsed = parseContextVariableValue(row, index);
    if (parsed.error) {
      return { value: {}, error: parsed.error };
    }
    const conflict = assignContextVariableValue(context, segments, parsed.value);
    if (conflict) {
      return { value: {}, error: conflict };
    }
  }
  return { value: context };
}

function formatDraftJson(value: unknown): string {
  return JSON.stringify(value ?? {}, null, 2);
}

function simulationTableRowsFromExample(cases: CanvasExampleTestCase[] | undefined): SimulationTableTestDraftRow[] {
  return (cases ?? []).map((testCase) => ({
    id: testCase.id,
    name: testCase.name,
    contextText: formatDraftJson(testCase.context),
    fixturesText: formatDraftJson(testCase.fixtureOverrides ?? {}),
    expectedOutputText: JSON.stringify(testCase.expectedOutput, null, 2),
  }));
}

function emptySimulationTableRow(id: string, inputSchema: SchemaEnvelope): SimulationTableTestDraftRow {
  return {
    id,
    name: `Case ${id.replace(/^table-case-/, '')}`,
    contextText: formatDraftJson(sampleFromSchemaEnvelope(inputSchema)),
    fixturesText: '{}',
    expectedOutputText: '',
  };
}

function operatorInputSample(operator: OperatorDefinition | undefined): unknown {
  const inputs = operator?.ports?.inputs ?? [];
  if (inputs.length === 0) {
    return {};
  }
  if (inputs.length === 1) {
    return sampleFromSchemaEnvelope(inputs[0].schema);
  }
  return Object.fromEntries(
    inputs.map((input) => [
      input.name || 'input',
      sampleFromSchemaEnvelope(input.schema),
    ]),
  );
}

function operatorTestOutputText(operator: OperatorDefinition | undefined): string {
  return operator ? fixtureDraftForOperator(operator) : 'null';
}

function operatorUsesTransportFixture(operator: OperatorDefinition | undefined): boolean {
  return operator?.lowering?.mode === 'resource-descriptor'
    || operator?.lowering?.operatorRef === 'httpResource'
    || operator?.operatorRef === 'httpResource';
}

function expectedResourcePayload(expectedOutput: unknown): unknown {
  return isRecord(expectedOutput) && Object.prototype.hasOwnProperty.call(expectedOutput, 'payload')
    ? expectedOutput.payload
    : expectedOutput;
}

function assignNestedTransportPayload(target: Record<string, unknown>, path: string, payload: unknown): void {
  const segments = path.split('.').map((segment) => segment.trim()).filter(Boolean);
  if (segments.length === 0) {
    return;
  }
  let cursor = target;
  for (const segment of segments.slice(0, -1)) {
    const nested: Record<string, unknown> = {};
    cursor[segment] = nested;
    cursor = nested;
  }
  cursor[segments[segments.length - 1]] = payload;
}

function defaultOperatorTransportResponse(
  operator: OperatorDefinition | undefined,
  expectedOutput: unknown,
): unknown {
  if (!operatorUsesTransportFixture(operator)) {
    return null;
  }
  const payload = expectedResourcePayload(expectedOutput);
  const payloadPath = operator?.lowering?.parameters?.payloadPath;
  if (typeof payloadPath !== 'string' || !payloadPath.trim()) {
    return payload;
  }
  const response: Record<string, unknown> = {
    code: 0,
    success: true,
    message: 'OK',
  };
  assignNestedTransportPayload(response, payloadPath, payload);
  return response;
}

function transportResponseTextForExpected(
  operator: OperatorDefinition | undefined,
  expectedOutput: unknown,
): string {
  return operatorUsesTransportFixture(operator)
    ? formatDraftJson(defaultOperatorTransportResponse(operator, expectedOutput))
    : '';
}

function transportResponseTextAfterExpectedEdit(
  operator: OperatorDefinition | undefined,
  previousExpectedText: string,
  nextExpectedText: string,
  currentTransportText: string,
): string {
  try {
    const previousGenerated = transportResponseTextForExpected(
      operator,
      JSON.parse(previousExpectedText || 'null') as unknown,
    );
    if (currentTransportText !== previousGenerated) {
      return currentTransportText;
    }
    return transportResponseTextForExpected(
      operator,
      JSON.parse(nextExpectedText || 'null') as unknown,
    );
  } catch {
    return currentTransportText;
  }
}

function defaultOperatorTestSuiteRows(
  node: Node<NodeData>,
  operator: OperatorDefinition | undefined,
): OperatorTestSuiteDraftRow[] {
  const outputText = operatorTestOutputText(operator);
  let expectedOutput: unknown = null;
  try {
    expectedOutput = JSON.parse(outputText) as unknown;
  } catch {
    // The row parser will surface malformed generated output if a future generator regresses.
  }
  return [
    {
      id: 'case-1',
      name: `${node.data.label} executable case`,
      caseType: 'GOLDEN',
      inputText: formatDraftJson(operatorInputSample(operator)),
      outputText,
      transportResponseText: transportResponseTextForExpected(operator, expectedOutput),
    },
  ];
}

function parseOperatorTestSuiteRow(row: OperatorTestSuiteDraftRow): OperatorTestSuiteCompilation {
  const messages: string[] = [];
  let input: unknown;
  let output: unknown;

  try {
    input = JSON.parse(row.inputText.trim() || 'null') as unknown;
  } catch {
    messages.push('Input case must be valid JSON.');
  }

  try {
    output = JSON.parse(row.outputText.trim() || 'null') as unknown;
  } catch {
    messages.push('Expected output must be valid JSON.');
  }

  let transportResponse: unknown;
  if (row.transportResponseText.trim()) {
    try {
      transportResponse = JSON.parse(row.transportResponseText) as unknown;
    } catch {
      messages.push('Transport response must be valid JSON.');
    }
  }

  return {
    input,
    output,
    transportResponse,
    ...(messages.length > 0 ? { error: messages.join(' ') } : {}),
  };
}

function evaluateOperatorTestResult(
  row: OperatorTestSuiteDraftRow,
  compilation: OperatorTestSuiteCompilation,
  run: Awaited<ReturnType<typeof runOperatorTestCase>>,
): OperatorTestCaseResult {
  const evidence = run.response.evidence;
  const subject = evidence.nodeTrace?.find((trace) => trace.nodeId === 'subject')
    ?? evidence.nodeTrace?.[0];
  const actualOutput = subject?.output;
  const storedFixtureRef = run.storedFixture
    ? `${run.storedFixture.fixtureBundleId}@${run.storedFixture.revision}`
    : '';
  if (evidence.status !== 'PASSED') {
    const failedAssertion = evidence.assertionResults?.find((assertion) => !assertion.passed);
    return {
      id: row.id,
      name: row.name.trim() || row.id,
      status: 'failed',
      detail: failedAssertion?.diagnostic
        || evidence.diagnostics?.[0]
        || `${evidence.status}${storedFixtureRef ? ` · fixture ${storedFixtureRef}` : ''} · run ${run.response.runId}`,
      actualOutput,
      expectedInput: compilation.input,
      fixtureOutput: compilation.output,
      runId: run.response.runId,
      evidenceClass: evidence.evidenceClass,
    };
  }

  return {
    id: row.id,
    name: row.name.trim() || row.id,
    status: 'passed',
    detail: run.storedFixture
      ? `Governed micro-graph PASSED · ${evidence.evidenceClass} · fixture ${storedFixtureRef} · run ${run.response.runId}`
      : `Real micro-graph PASSED · ${evidence.evidenceClass} · run ${run.response.runId}`,
    actualOutput,
    expectedInput: compilation.input,
    fixtureOutput: compilation.output,
    runId: run.response.runId,
    evidenceClass: evidence.evidenceClass,
  };
}

function evaluateGovernedOperatorSuite(
  rows: OperatorTestSuiteDraftRow[],
  run: OperatorTestSuiteRun,
): {
  publication: OperatorTestSuitePublicationResult;
  caseResults: Record<string, OperatorTestCaseResult>;
} {
  const evidence = run.response.evidence;
  const caseEvidence = new Map(evidence.caseResults.map((result) => [result.caseId, result]));
  const caseResults = Object.fromEntries(rows.map((row) => {
    const result = caseEvidence.get(row.id);
    const passed = result?.status === 'PASSED';
    const evidenceLabel = result?.evidenceClass ?? 'NO_EVIDENCE';
    const runLabel = result?.runId ? ` · child run ${result.runId}` : '';
    return [row.id, {
      id: row.id,
      name: row.name.trim() || row.id,
      status: passed ? 'passed' as const : 'failed' as const,
      detail: passed
        ? `Governed suite case PASSED · ${evidenceLabel}${runLabel}`
        : result?.diagnostic || result?.diagnosticCode
          || `Governed suite case ${result?.status ?? 'EVIDENCE_INCOMPLETE'}${runLabel}`,
      runId: result?.runId,
      evidenceClass: result?.evidenceClass ?? undefined,
    }];
  }));
  const eligible = evidence.status === 'PASSED'
    && evidence.coverage.status === 'SATISFIED'
    && evidence.promotion.status === 'ELIGIBLE';
  return {
    publication: {
      status: eligible ? 'passed' : 'failed',
      detail: `${evidence.status} · coverage ${evidence.coverage.status} · promotion ${evidence.promotion.status}`,
      suiteId: run.storedSuite.suiteId,
      revision: run.storedSuite.revision,
      suiteRunId: run.response.suiteRunId,
    },
    caseResults,
  };
}

function parseConstantInputValue(text: string): unknown {
  const trimmed = text.trim();
  if (!trimmed) {
    return '';
  }
  try {
    return JSON.parse(trimmed) as unknown;
  } catch {
    return text;
  }
}

function constantInputValueText(value: unknown): string {
  if (value === undefined || value === null) {
    return '';
  }
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2);
}

function uniqueInputBindingKey(
  bindings: Record<string, DraftNodeBinding>,
  rawBase: string,
  currentKey = '',
): string {
  const normalized = decisionFieldName(rawBase, currentKey || 'input');
  const taken = new Set(Object.keys(bindings).filter((key) => key !== currentKey));
  let candidate = normalized;
  let suffix = 2;
  while (taken.has(candidate)) {
    candidate = `${normalized}${suffix}`;
    suffix += 1;
  }
  return candidate;
}

function defaultInputTargetPort(node: Node<NodeData>): string {
  return node.data.summary.inputNames[0] || 'inputs';
}

function editableInputBindingKind(binding: DraftNodeBinding): 'contextPath' | 'constant' {
  return binding.kind === 'constant' ? 'constant' : 'contextPath';
}

function NodeInputBindingsEditor({
  node,
  onAdd,
  onRemove,
  onRename,
  onChange,
  onKindChange,
  onDropContextPath,
}: {
  node: Node<NodeData>;
  onAdd: () => void;
  onRemove: (bindingKey: string) => void;
  onRename: (bindingKey: string, value: string) => void;
  onChange: (bindingKey: string, patch: Partial<DraftNodeBinding>) => void;
  onKindChange: (bindingKey: string, kind: 'contextPath' | 'constant') => void;
  onDropContextPath: (path: string) => void;
}) {
  const inputPorts = node.data.summary.inputNames.length ? node.data.summary.inputNames : ['inputs'];
  const rows = Object.entries(node.data.inputs ?? {});
  return (
    <div
      className="input-binding-editor"
      data-testid="node-input-editor"
      onDragOver={(event) => {
        if (Array.from(event.dataTransfer.types).includes(CONTEXT_VARIABLE_DRAG_TYPE)) {
          event.preventDefault();
          event.dataTransfer.dropEffect = 'copy';
        }
      }}
      onDrop={(event) => {
        const path = event.dataTransfer.getData(CONTEXT_VARIABLE_DRAG_TYPE);
        if (!path) {
          return;
        }
        event.preventDefault();
        onDropContextPath(path);
      }}
    >
      <div className="input-binding-header">
        <strong>Node Inputs</strong>
        <button
          type="button"
          className="secondary compact"
          data-testid="node-input-add"
          onClick={onAdd}
        >
          Add Binding
        </button>
      </div>
      {rows.length > 0 ? (
        <ol className="input-binding-list">
          {rows.map(([bindingKey, binding], index) => {
            const kind = editableInputBindingKind(binding);
            const targetPort = binding.targetPort || defaultInputTargetPort(node);
            return (
              <li key={bindingKey} data-testid={`node-input-binding:${index}`}>
                <div className="input-binding-row-header">
                  <label>
                    <span>Key</span>
                    <input
                      aria-label={`Input binding key ${index + 1}`}
                      data-testid={`node-input-key:${index}`}
                      value={bindingKey}
                      onChange={(event) => onRename(bindingKey, event.target.value)}
                    />
                  </label>
                  <label>
                    <span>Source</span>
                    <select
                      aria-label={`Input binding source ${index + 1}`}
                      data-testid={`node-input-kind:${index}`}
                      value={kind}
                      onChange={(event) =>
                        onKindChange(bindingKey, event.target.value === 'constant' ? 'constant' : 'contextPath')
                      }
                    >
                      <option value="contextPath">ctx</option>
                      <option value="constant">constant</option>
                    </select>
                  </label>
                </div>
                <div className="input-binding-targets">
                  <label>
                    <span>Target port</span>
                    <select
                      aria-label={`Input target port ${index + 1}`}
                      data-testid={`node-input-target-port:${index}`}
                      value={targetPort}
                      onChange={(event) => onChange(bindingKey, { targetPort: event.target.value })}
                    >
                      {inputPorts.map((port) => (
                        <option key={port} value={port}>
                          {port}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label>
                    <span>Target path</span>
                    <input
                      aria-label={`Input target path ${index + 1}`}
                      data-testid={`node-input-target-path:${index}`}
                      placeholder={bindingKey}
                      value={binding.targetPath ?? ''}
                      onChange={(event) => onChange(bindingKey, { targetPath: event.target.value })}
                    />
                  </label>
                </div>
                {kind === 'contextPath' ? (
                  <label className="input-binding-source">
                    <span>Context path</span>
                    <input
                      aria-label={`Context path ${index + 1}`}
                      data-testid={`node-input-context-path:${index}`}
                      placeholder="user.id"
                      value={binding.path ?? ''}
                      onChange={(event) => onChange(bindingKey, { kind: 'contextPath', path: event.target.value })}
                    />
                  </label>
                ) : (
                  <label className="input-binding-source">
                    <span>Constant</span>
                    <textarea
                      aria-label={`Constant input value ${index + 1}`}
                      data-testid={`node-input-constant:${index}`}
                      value={constantInputValueText(binding.value)}
                      onChange={(event) =>
                        onChange(bindingKey, { kind: 'constant', value: parseConstantInputValue(event.target.value) })
                      }
                    />
                  </label>
                )}
                <button
                  type="button"
                  className="secondary compact"
                  data-testid={`node-input-remove:${index}`}
                  onClick={() => onRemove(bindingKey)}
                >
                  Remove
                </button>
              </li>
            );
          })}
        </ol>
      ) : (
        <p className="muted">No input bindings.</p>
      )}
    </div>
  );
}

function ContextVariablesEditor({
  rows,
  compilation,
  selectedNodeId,
  rawJson,
  onAdd,
  onUpdate,
  onRemove,
  onBind,
  onRawJsonChange,
}: {
  rows: ContextVariableRow[];
  compilation: JsonObjectCompilation;
  selectedNodeId: string;
  rawJson: string;
  onAdd: () => void;
  onUpdate: (id: string, patch: Partial<ContextVariableRow>) => void;
  onRemove: (id: string) => void;
  onBind: (path: string) => void;
  onRawJsonChange: (value: string) => void;
}) {
  const contextPreview = JSON.stringify(compilation.value, null, 2);
  return (
    <div className="fixture-editor context-editor">
      <div className="fixture-header">
        <strong>Context Variables</strong>
        <span className={`badge ${compilation.error ? 'error' : 'fixture'}`}>
          {compilation.error ? 'invalid' : 'ready'}
        </span>
      </div>
      {rows.length > 0 ? (
        <ol className="context-variable-list">
          {rows.map((row, index) => {
            const path = normalizedContextPath(row.path);
            return (
              <li key={row.id} className="context-variable-row">
                <button
                  type="button"
                  className="context-variable-chip"
                  draggable={Boolean(path)}
                  data-testid={`context-variable-chip:${index}`}
                  onDragStart={(event: DragEvent<HTMLButtonElement>) => {
                    if (!path) {
                      return;
                    }
                    event.dataTransfer.effectAllowed = 'copy';
                    event.dataTransfer.setData(CONTEXT_VARIABLE_DRAG_TYPE, path);
                    event.dataTransfer.setData('text/plain', `ctx.${path}`);
                  }}
                >
                  ctx.{path || 'path'}
                </button>
                <div className="context-variable-fields">
                  <label>
                    <span>Path</span>
                    <input
                      aria-label={`Context variable path ${index + 1}`}
                      data-testid={`context-variable-path:${index}`}
                      placeholder="applicant.score"
                      value={row.path}
                      onChange={(event) => onUpdate(row.id, { path: event.target.value })}
                    />
                  </label>
                  <label>
                    <span>Type</span>
                    <select
                      aria-label={`Context variable type ${index + 1}`}
                      data-testid={`context-variable-type:${index}`}
                      value={row.valueType}
                      onChange={(event) =>
                        onUpdate(row.id, { valueType: event.target.value as ContextVariableType })
                      }
                    >
                      <option value="string">string</option>
                      <option value="number">number</option>
                      <option value="boolean">boolean</option>
                      <option value="json">json</option>
                    </select>
                  </label>
                  <label>
                    <span>Sample</span>
                    <input
                      aria-label={`Context variable sample ${index + 1}`}
                      data-testid={`context-variable-value:${index}`}
                      placeholder={row.valueType === 'json' ? '{"tier":"gold"}' : 'value'}
                      value={row.sample}
                      onChange={(event) => onUpdate(row.id, { sample: event.target.value })}
                    />
                  </label>
                </div>
                <div className="context-variable-actions">
                  <button
                    type="button"
                    className="secondary compact"
                    data-testid={`context-variable-bind:${index}`}
                    disabled={!selectedNodeId || !path}
                    onClick={() => onBind(path)}
                  >
                    Bind
                  </button>
                  <button
                    type="button"
                    className="secondary compact"
                    data-testid={`context-variable-remove:${index}`}
                    onClick={() => onRemove(row.id)}
                  >
                    Remove
                  </button>
                </div>
              </li>
            );
          })}
        </ol>
      ) : (
        <p className="muted">No context variables.</p>
      )}
      <div className="context-variable-footer">
        <button
          type="button"
          className="secondary compact"
          data-testid="context-variable-add"
          onClick={onAdd}
        >
          Add Variable
        </button>
      </div>
      <pre className="context-preview" data-testid="context-preview-json">{contextPreview}</pre>
      <details className="context-advanced">
        <summary>Advanced JSON</summary>
        <label className="fixture-field">
          <span>JSON</span>
          <textarea
            aria-label="Simulation runtime context JSON"
            data-testid="simulation-context-json"
            spellCheck={false}
            placeholder="{}"
            value={rawJson}
            onChange={(event) => onRawJsonChange(event.target.value)}
          />
        </label>
      </details>
      {compilation.error && (
        <p className="fixture-error" data-testid="simulation-context-error">
          {compilation.error}
        </p>
      )}
    </div>
  );
}

function OperatorNode({ id, data, selected }: NodeProps<NodeData>) {
  const status = data.status ?? 'unknown';
  const inputPorts = data.summary.inputNames;
  const outputPorts = data.summary.outputNames.length ? data.summary.outputNames : [''];
  const metrics = operatorNodeMetrics(data.summary, data.config);
  const candidateClass = data.candidateStatus ? `candidate-${data.candidateStatus}` : '';
  const focusClass = data.focusState && data.focusState !== 'none' ? `focus-${data.focusState}` : '';
  const kindClass = `kind-${data.summary.visualKind}`;
  return (
    <div
      className={`operator-node ${kindClass} ${status} ${candidateClass} ${focusClass} ${selected ? 'selected' : ''}`}
      data-testid={`canvas-node:${id}`}
      data-operator-ref={data.operatorRef}
    >
      {inputPorts.map((port, index) => (
        <Handle
          key={`in:${port}`}
          id={handleIdForPort('in', port)}
          type="target"
          position={Position.Left}
          title={data.candidatePorts?.[port] ? `Input: ${port} · ${data.candidatePorts[port]}` : `Input: ${port}`}
          className={`port-handle target ${data.candidatePorts?.[port] ?? ''}`}
          style={handleOffset(index, inputPorts.length)}
        />
      ))}
      <div className="operator-node-title">
        <span>{data.label}</span>
        <span className="operator-node-pills">
          <span className={`operator-kind-pill ${data.summary.visualKind}`}>{data.summary.visualLabel}</span>
          {data.summary.externalWrite && (
            <span
              className={`operator-side-effect-pill ${data.summary.managedWrite ? 'managed' : 'unmanaged'}`}
              title={data.summary.sideEffectNotice}
            >
              {data.summary.managedWrite ? 'write ok' : 'write blocked'}
            </span>
          )}
          {data.isOutput && <span className="output-pill">output</span>}
          {status !== 'unknown' && <span className={`run-pill ${status}`}>{status}</span>}
        </span>
      </div>
      <div className="operator-node-ref">{data.operatorRef}</div>
      <div className="operator-node-contract" title={data.summary.contractHint}>
        <span>{data.summary.inputContractLabel}</span>
        <strong>→</strong>
        <span>{data.summary.outputContractLabel}</span>
      </div>
      <div className="operator-node-metrics">
        <span>
          {metrics.requiredInputCount}/{metrics.inputCount} inputs
        </span>
        <span>{metrics.outputCount} outputs</span>
      </div>
      <div className="operator-node-port-grid">
        <span>In</span>
        <strong>{inputPorts.join(', ') || 'none'}</strong>
        <span>Out</span>
        <strong>{data.summary.outputNames.join(', ') || 'value'}</strong>
      </div>
      {data.summary.readinessNodeNotice && (
        <div
          className={`operator-node-warning ${data.summary.readinessLevel}`}
          title={data.summary.readinessNotice || data.summary.readinessNodeNotice}
        >
          {data.summary.readinessBadgeLabel || data.summary.readinessState}: {data.summary.readinessNodeNotice}
        </div>
      )}
      {outputPorts.map((port, index) => (
        <Handle
          key={`out:${port}`}
          id={handleIdForPort('out', port)}
          type="source"
          position={Position.Right}
          title={port ? `Output: ${port}` : 'Output'}
          className="port-handle source"
          style={handleOffset(index, outputPorts.length)}
        />
      ))}
    </div>
  );
}

const NODE_TYPES = { operator: OperatorNode };
const OPERATOR_DRAG_MIME = 'application/bloge-operator-ref';
const DEFAULT_DECISION_OUTPUT_TYPE = '{ decision: String, ruleId: String }';
const DEFAULT_DECISION_CONDITION_COLUMNS: DecisionTableColumn[] = [{ id: 'value', label: 'value' }];
const DEFAULT_DECISION_OUTPUT_COLUMNS: DecisionTableColumn[] = [
  { id: 'decision', label: 'decision' },
  { id: 'ruleId', label: 'ruleId' },
];

function decisionTableEditorModel(
  config: Record<string, unknown> | undefined,
  incomingConditionColumns: DecisionTableColumn[] = [],
): DecisionTableEditorModel {
  const conditionColumns = decisionTableColumns(config?.conditionColumns);
  const outputColumns = decisionTableColumns(config?.outputColumns);
  const rows: DecisionTableRuleRow[] = [];
  if (Array.isArray(config?.rules)) {
    for (const rawRule of config.rules) {
      const row = decisionTableRuleRow(rawRule);
      if (!row) {
        continue;
      }
      Object.keys(row.conditions).forEach((key) => addDecisionColumn(conditionColumns, key));
      Object.keys(row.outputs).forEach((key) => addDecisionColumn(outputColumns, key));
      rows.push(row);
    }
  }
  incomingConditionColumns.forEach((column) =>
    addDecisionColumn(conditionColumns, column.id, {
      label: column.label,
      locked: true,
      sourceLabel: column.sourceLabel,
    }),
  );
  const effectiveConditionColumns = conditionColumns.length > 0
    ? conditionColumns
    : cloneDecisionColumns(DEFAULT_DECISION_CONDITION_COLUMNS);
  const effectiveOutputColumns = outputColumns.length > 0
    ? outputColumns
    : cloneDecisionColumns(DEFAULT_DECISION_OUTPUT_COLUMNS);
  return {
    hitPolicy: typeof config?.hitPolicy === 'string' && config.hitPolicy ? config.hitPolicy : 'unique',
    outputType: typeof config?.outputType === 'string' && config.outputType
      ? config.outputType
      : DEFAULT_DECISION_OUTPUT_TYPE,
    conditionColumns: effectiveConditionColumns,
    outputColumns: effectiveOutputColumns,
    rows: rows.length > 0
      ? rows.map((row) => normalizedDecisionTableRow(row, effectiveConditionColumns, effectiveOutputColumns))
      : defaultDecisionTableRows(effectiveConditionColumns, effectiveOutputColumns),
  };
}

function decisionTableRuleRow(rawRule: unknown): DecisionTableRuleRow | null {
  if (!isRecord(rawRule)) {
    return null;
  }
  const output = decisionTableOutputMap(rawRule);
  const rawConditions = rawRule.conditions;
  return {
    conditions: rawRule.otherwise === true ? {} : decisionTableConditionMap(rawConditions),
    outputs: {
      decision: stringField(output.decision, 'matched'),
      ruleId: stringField(output.ruleId, stringField(rawRule.id, 'rule')),
      ...output,
    },
    otherwise: rawRule.otherwise === true,
  };
}

function defaultDecisionTableRows(
  conditionColumns: DecisionTableColumn[],
  outputColumns: DecisionTableColumn[],
): DecisionTableRuleRow[] {
  return [
    {
      conditions: defaultDecisionConditions(conditionColumns),
      outputs: defaultDecisionOutputs(outputColumns, false, 0),
      otherwise: false,
    },
    {
      conditions: emptyDecisionValues(conditionColumns),
      outputs: defaultDecisionOutputs(outputColumns, true, 1),
      otherwise: true,
    },
  ];
}

function decisionTableConfigFromEditor(
  existing: Record<string, unknown> | undefined,
  editor: DecisionTableEditorModel,
): Record<string, unknown> {
  const conditionColumns = editor.conditionColumns.length > 0
    ? editor.conditionColumns
    : cloneDecisionColumns(DEFAULT_DECISION_CONDITION_COLUMNS);
  const outputColumns = editor.outputColumns.length > 0
    ? editor.outputColumns
    : cloneDecisionColumns(DEFAULT_DECISION_OUTPUT_COLUMNS);
  return {
    ...(existing ?? {}),
    hitPolicy: editor.hitPolicy || 'unique',
    outputType: editor.outputType || decisionOutputTypeFromColumns(outputColumns),
    conditionColumns: conditionColumns.map((column) => column.id),
    outputColumns: outputColumns.map((column) => column.id),
    rules: editor.rows.map((row, index) => {
      const output = decisionOutputMapFromRow(row, outputColumns, index);
      if (row.otherwise) {
        return { otherwise: true, output };
      }
      return {
        conditions: decisionConditionMapFromRow(row, conditionColumns),
        output,
      };
    }),
  };
}

function decisionTableColumns(rawColumns: unknown): DecisionTableColumn[] {
  if (!Array.isArray(rawColumns)) {
    return [];
  }
  const columns: DecisionTableColumn[] = [];
  for (const rawColumn of rawColumns) {
    if (typeof rawColumn === 'string') {
      addDecisionColumn(columns, rawColumn);
    } else if (isRecord(rawColumn)) {
      addDecisionColumn(columns, stringField(rawColumn.id, stringField(rawColumn.name, stringField(rawColumn.label, ''))));
    }
  }
  return columns;
}

function decisionTableConditionMap(rawConditions: unknown): Record<string, string> {
  if (typeof rawConditions === 'string' && rawConditions.trim()) {
    return { value: rawConditions };
  }
  if (!isRecord(rawConditions)) {
    return {};
  }
  return recordStringMap(rawConditions);
}

function decisionTableOutputMap(rawRule: Record<string, unknown>): Record<string, string> {
  if (isRecord(rawRule.output)) {
    return recordStringMap(rawRule.output);
  }
  const output: Record<string, unknown> = { ...rawRule };
  delete output.conditions;
  delete output.condition;
  delete output.otherwise;
  delete output.id;
  return recordStringMap(output);
}

function recordStringMap(record: Record<string, unknown>): Record<string, string> {
  return Object.fromEntries(Object.entries(record).map(([key, value]) => [key, valueToCellString(value)]));
}

function valueToCellString(value: unknown): string {
  if (value === undefined || value === null) {
    return '';
  }
  if (typeof value === 'string') {
    return value;
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  return JSON.stringify(value);
}

function cloneDecisionColumns(columns: DecisionTableColumn[]): DecisionTableColumn[] {
  return columns.map((column) => ({ ...column }));
}

function addDecisionColumn(
  columns: DecisionTableColumn[],
  rawId: string,
  patch: Partial<DecisionTableColumn> = {},
): void {
  const id = decisionFieldName(rawId, '');
  if (!id) {
    return;
  }
  const existing = columns.find((column) => column.id === id);
  if (existing) {
    Object.assign(existing, patch);
    return;
  }
  columns.push({ id, label: patch.label ?? id, ...patch });
}

function nextDecisionColumn(columns: DecisionTableColumn[], prefix: string): DecisionTableColumn {
  const id = uniqueDecisionColumnId(`${prefix}${columns.length + 1}`, columns.map((column) => column.id));
  return { id, label: id };
}

function uniqueDecisionColumnId(base: string, existing: string[]): string {
  const normalized = decisionFieldName(base, 'field');
  const taken = new Set(existing);
  let candidate = normalized;
  let suffix = 2;
  while (taken.has(candidate)) {
    candidate = `${normalized}${suffix}`;
    suffix += 1;
  }
  return candidate;
}

function decisionFieldName(value: string, fallback: string): string {
  const normalized = value
    .trim()
    .replace(/[^A-Za-z0-9_]/g, '_')
    .replace(/^[^A-Za-z_]+/, '');
  return normalized || fallback;
}

function normalizedDecisionTableRow(
  row: DecisionTableRuleRow,
  conditionColumns: DecisionTableColumn[],
  outputColumns: DecisionTableColumn[],
): DecisionTableRuleRow {
  return {
    otherwise: row.otherwise,
    conditions: decisionValuesForColumns(conditionColumns, row.conditions),
    outputs: decisionValuesForColumns(outputColumns, row.outputs),
  };
}

function decisionValuesForColumns(
  columns: DecisionTableColumn[],
  values: Record<string, string>,
): Record<string, string> {
  return Object.fromEntries(columns.map((column) => [column.id, values[column.id] ?? '']));
}

function emptyDecisionValues(columns: DecisionTableColumn[]): Record<string, string> {
  return Object.fromEntries(columns.map((column) => [column.id, '']));
}

function defaultDecisionConditions(columns: DecisionTableColumn[]): Record<string, string> {
  return Object.fromEntries(columns.map((column, index) => [
    column.id,
    index === 0 ? `${column.id} != null` : '',
  ]));
}

function defaultDecisionOutputs(
  columns: DecisionTableColumn[],
  otherwise: boolean,
  rowIndex: number,
): Record<string, string> {
  return Object.fromEntries(columns.map((column) => [
    column.id,
    defaultDecisionOutputValue(column.id, otherwise, rowIndex),
  ]));
}

function defaultDecisionOutputValue(field: string, otherwise: boolean, rowIndex: number): string {
  if (field === 'decision') {
    return otherwise ? 'fallback' : 'matched';
  }
  if (field === 'ruleId') {
    return otherwise ? 'otherwise' : `rule-${rowIndex + 1}`;
  }
  return '';
}

function decisionConditionMapFromRow(
  row: DecisionTableRuleRow,
  columns: DecisionTableColumn[],
): Record<string, string> {
  const conditions: Record<string, string> = {};
  for (const column of columns) {
    const value = row.conditions[column.id]?.trim();
    if (value) {
      conditions[column.id] = value;
    }
  }
  if (Object.keys(conditions).length > 0) {
    return conditions;
  }
  const firstColumn = columns[0] ?? DEFAULT_DECISION_CONDITION_COLUMNS[0];
  return { [firstColumn.id]: `${firstColumn.id} != null` };
}

function decisionOutputMapFromRow(
  row: DecisionTableRuleRow,
  columns: DecisionTableColumn[],
  rowIndex: number,
): Record<string, string> {
  return Object.fromEntries(columns.map((column) => {
    const value = row.outputs[column.id];
    return [column.id, value || defaultDecisionOutputValue(column.id, row.otherwise, rowIndex)];
  }));
}

function decisionOutputTypeFromColumns(columns: DecisionTableColumn[]): string {
  return `{ ${columns.map((column) => `${column.id}: String`).join(', ')} }`;
}

function syncedDecisionOutputType(
  currentOutputType: string,
  previousColumns: DecisionTableColumn[],
  nextColumns: DecisionTableColumn[],
): string {
  const previousAuto = decisionOutputTypeFromColumns(previousColumns);
  const trimmed = currentOutputType.trim();
  if (!trimmed || trimmed === DEFAULT_DECISION_OUTPUT_TYPE || trimmed === previousAuto) {
    return decisionOutputTypeFromColumns(nextColumns);
  }
  return currentOutputType;
}

function transformEditorModel(config: Record<string, unknown> | undefined): TransformEditorModel {
  const assignments = isRecord(config?.assignments)
    ? Object.entries(config.assignments).map(([field, expression]) => ({
        field: decisionFieldName(field, 'result'),
        expression: valueToCellString(expression) || '{}',
      }))
    : [];
  return { assignments: assignments.length > 0 ? assignments : [{ field: 'result', expression: '{}' }] };
}

function transformConfigFromEditor(
  existing: Record<string, unknown> | undefined,
  editor: TransformEditorModel,
): Record<string, unknown> {
  const fields: string[] = [];
  const assignments: Record<string, string> = {};
  editor.assignments.forEach((row, index) => {
    const field = uniqueDecisionColumnId(row.field || `field${index + 1}`, fields);
    fields.push(field);
    assignments[field] = row.expression.trim() || '{}';
  });
  if (Object.keys(assignments).length === 0) {
    assignments.result = '{}';
  }
  return {
    ...(existing ?? {}),
    assignments,
  };
}

function stringField(value: unknown, fallback: string): string {
  return typeof value === 'string' && value ? value : fallback;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function decisionTableIncomingConditionColumns(
  node: Node<NodeData>,
  edges: Edge[],
  nodes: Node<NodeData>[],
): DecisionTableColumn[] {
  const nodeById = new Map(nodes.map((candidate) => [candidate.id, candidate]));
  const columns = new Map<string, DecisionTableColumn>();
  for (const edge of edges) {
    if (edge.target !== node.id || edge.source === node.id) {
      continue;
    }
    const flowEdge = edge as CanvasFlowEdge;
    const edgeData = edge.data as { sourcePath?: string; targetPath?: string; bindingKey?: string } | undefined;
    const sourcePort = portNameFromHandle(edge.sourceHandle, 'out');
    const targetPort = portNameFromHandle(edge.targetHandle, 'in');
    const sourcePath = flowEdge.sourcePath ?? edgeData?.sourcePath ?? '';
    const targetPath = flowEdge.targetPath ?? edgeData?.targetPath ?? '';
    const bindingKey = flowEdge.bindingKey ?? edgeData?.bindingKey ?? canvasEdgeBindingKey({
      id: edge.id,
      source: edge.source,
      target: edge.target,
      sourcePort,
      targetPort,
      sourcePath,
      targetPath,
    });
    const id = decisionFieldName(bindingKey, '');
    if (!id || columns.has(id)) {
      continue;
    }
    const sourceNode = nodeById.get(edge.source);
    const sourceLabel = `${sourceNode?.data.label ?? edge.source}.${endpointLabel(sourcePort, sourcePath, 'output')}`;
    columns.set(id, {
      id,
      label: id,
      locked: true,
      sourceLabel,
    });
  }
  return Array.from(columns.values());
}

function OperatorFocusPanel({
  operator,
  summary,
}: {
  operator: OperatorDefinition | undefined;
  summary: OperatorSummary;
}) {
  const inputs = operator?.ports?.inputs ?? [];
  const outputs = operator?.ports?.outputs ?? [];
  const rows = operatorFocusRows(summary, inputs, outputs, operator);
  if (rows.length === 0) {
    return null;
  }
  return (
    <div
      className={`operator-focus ${summary.visualKind}`}
      data-testid={`operator-focus:${summary.visualKind}`}
    >
      <div className="operator-focus-heading">
        <span>{summary.visualLabel}</span>
        <strong>{operatorFocusTitle(summary.visualKind)}</strong>
      </div>
      <dl className="operator-focus-grid">
        {rows.map((row) => (
          <div key={row.key}>
            <dt>{row.label}</dt>
            <dd>{row.value}</dd>
          </div>
        ))}
      </dl>
    </div>
  );
}

function DecisionTableRuleEditor({
  node,
  incomingColumns,
  onClose,
  onChange,
  embedded = false,
}: {
  node: Node<NodeData>;
  incomingColumns: DecisionTableColumn[];
  onClose: () => void;
  onChange: (editor: DecisionTableEditorModel) => void;
  embedded?: boolean;
}) {
  const editor = decisionTableEditorModel(node.data.config, incomingColumns);
  const updateEditor = (next: DecisionTableEditorModel) => onChange(next);
  const updateRow = (index: number, patch: Partial<DecisionTableRuleRow>) => {
    updateEditor({
      ...editor,
      rows: editor.rows.map((row, rowIndex) => (rowIndex === index ? { ...row, ...patch } : row)),
    });
  };
  const deleteRow = (index: number) => {
    const rows = editor.rows.filter((_, rowIndex) => rowIndex !== index);
    updateEditor({
      ...editor,
      rows: rows.length > 0
        ? rows
        : defaultDecisionTableRows(editor.conditionColumns, editor.outputColumns),
    });
  };
  const addRow = () => {
    updateEditor({
      ...editor,
      rows: [
        ...editor.rows,
        {
          conditions: defaultDecisionConditions(editor.conditionColumns),
          outputs: defaultDecisionOutputs(editor.outputColumns, false, editor.rows.length),
          otherwise: false,
        },
      ],
    });
  };
  const updateConditionCell = (rowIndex: number, columnId: string, value: string) => {
    const row = editor.rows[rowIndex];
    updateRow(rowIndex, { conditions: { ...row.conditions, [columnId]: value } });
  };
  const updateOutputCell = (rowIndex: number, columnId: string, value: string) => {
    const row = editor.rows[rowIndex];
    updateRow(rowIndex, { outputs: { ...row.outputs, [columnId]: value } });
  };
  const addConditionColumn = () => {
    const column = nextDecisionColumn(editor.conditionColumns, 'condition');
    updateEditor({
      ...editor,
      conditionColumns: [...editor.conditionColumns, column],
      rows: editor.rows.map((row) => ({
        ...row,
        conditions: { ...row.conditions, [column.id]: '' },
      })),
    });
  };
  const addOutputColumn = () => {
    const column = nextDecisionColumn(editor.outputColumns, 'output');
    const outputColumns = [...editor.outputColumns, column];
    updateEditor({
      ...editor,
      outputColumns,
      outputType: syncedDecisionOutputType(editor.outputType, editor.outputColumns, outputColumns),
      rows: editor.rows.map((row) => ({
        ...row,
        outputs: { ...row.outputs, [column.id]: '' },
      })),
    });
  };
  const renameConditionColumn = (columnIndex: number, value: string) => {
    const current = editor.conditionColumns[columnIndex];
    if (!current || current.locked) {
      return;
    }
    const id = uniqueDecisionColumnId(
      value,
      editor.conditionColumns
        .filter((_, index) => index !== columnIndex)
        .map((column) => column.id),
    );
    const conditionColumns = editor.conditionColumns.map((column, index) =>
      index === columnIndex ? { id, label: id } : column,
    );
    updateEditor({
      ...editor,
      conditionColumns,
      rows: editor.rows.map((row) => {
        const conditions = { ...row.conditions };
        if (id !== current.id) {
          conditions[id] = conditions[current.id] ?? '';
          delete conditions[current.id];
        }
        return { ...row, conditions };
      }),
    });
  };
  const renameOutputColumn = (columnIndex: number, value: string) => {
    const current = editor.outputColumns[columnIndex];
    if (!current) {
      return;
    }
    const id = uniqueDecisionColumnId(
      value,
      editor.outputColumns
        .filter((_, index) => index !== columnIndex)
        .map((column) => column.id),
    );
    const outputColumns = editor.outputColumns.map((column, index) =>
      index === columnIndex ? { id, label: id } : column,
    );
    updateEditor({
      ...editor,
      outputColumns,
      outputType: syncedDecisionOutputType(editor.outputType, editor.outputColumns, outputColumns),
      rows: editor.rows.map((row) => {
        const outputs = { ...row.outputs };
        if (id !== current.id) {
          outputs[id] = outputs[current.id] ?? '';
          delete outputs[current.id];
        }
        return { ...row, outputs };
      }),
    });
  };
  const deleteConditionColumn = (columnIndex: number) => {
    if (editor.conditionColumns.length <= 1) {
      return;
    }
    const removed = editor.conditionColumns[columnIndex];
    if (removed.locked) {
      return;
    }
    const conditionColumns = editor.conditionColumns.filter((_, index) => index !== columnIndex);
    updateEditor({
      ...editor,
      conditionColumns,
      rows: editor.rows.map((row) => {
        const conditions = { ...row.conditions };
        delete conditions[removed.id];
        return { ...row, conditions };
      }),
    });
  };
  const deleteOutputColumn = (columnIndex: number) => {
    if (editor.outputColumns.length <= 1) {
      return;
    }
    const removed = editor.outputColumns[columnIndex];
    const outputColumns = editor.outputColumns.filter((_, index) => index !== columnIndex);
    updateEditor({
      ...editor,
      outputColumns,
      outputType: syncedDecisionOutputType(editor.outputType, editor.outputColumns, outputColumns),
      rows: editor.rows.map((row) => {
        const outputs = { ...row.outputs };
        delete outputs[removed.id];
        return { ...row, outputs };
      }),
    });
  };
  return (
    <div className={embedded ? 'rule-editor-embedded-wrap' : 'rule-editor-backdrop'} role="presentation">
      <section
        className={`rule-editor ${embedded ? 'embedded' : ''}`}
        role={embedded ? 'group' : 'dialog'}
        aria-modal={embedded ? undefined : true}
        aria-labelledby="decision-rule-editor-title"
        data-testid="decision-table-editor"
      >
        <div className="rule-editor-heading">
          <span>Decision table</span>
          <strong id="decision-rule-editor-title">{node.data.label}</strong>
          <button
            type="button"
            className="secondary compact"
            onClick={onClose}
            aria-label="Close decision table editor"
          >
            Done
          </button>
        </div>
        <div className="rule-editor-meta">
          <label>
            <span>Hit policy</span>
            <select
              aria-label="Decision table hit policy"
              value={editor.hitPolicy}
              onChange={(event) => updateEditor({ ...editor, hitPolicy: event.target.value })}
            >
              <option value="unique">unique</option>
              <option value="first">first</option>
              <option value="collect">collect</option>
            </select>
          </label>
          <label>
            <span>Output type</span>
            <input
              aria-label="Decision table output type"
              value={editor.outputType}
              onChange={(event) => updateEditor({ ...editor, outputType: event.target.value })}
            />
          </label>
        </div>
        <div className="rule-editor-column-tools">
          {incomingColumns.length > 0 && (
            <div className="rule-editor-incoming" data-testid="decision-incoming-inputs">
              {incomingColumns.map((column) => (
                <span key={column.id} title={column.sourceLabel || column.id}>
                  {column.id}
                </span>
              ))}
            </div>
          )}
          <button
            type="button"
            className="secondary compact"
            onClick={addConditionColumn}
            data-testid="decision-add-condition-column"
          >
            Add Condition Column
          </button>
          <button
            type="button"
            className="secondary compact"
            onClick={addOutputColumn}
            data-testid="decision-add-output-column"
          >
            Add Output Column
          </button>
        </div>
        <div className="rule-editor-table-wrap">
          <table className="rule-editor-table">
            <thead>
              <tr>
                <th className="rule-editor-row-index">Rule</th>
                {editor.conditionColumns.map((column, index) => (
                  <th key={`condition:${column.id}`} className="rule-editor-column condition">
                    <div className="rule-editor-column-header">
                      <span>Condition</span>
                      <input
                        aria-label={`Condition column ${index + 1} name`}
                        data-testid={`decision-condition-column-name:${index}`}
                        value={column.label}
                        disabled={column.locked}
                        onChange={(event) => renameConditionColumn(index, event.target.value)}
                      />
                      {column.sourceLabel && <small>{column.sourceLabel}</small>}
                      <button
                        type="button"
                        className="secondary compact"
                        onClick={() => deleteConditionColumn(index)}
                        disabled={editor.conditionColumns.length <= 1 || column.locked}
                        aria-label={`Delete condition column ${column.label}`}
                      >
                        Delete
                      </button>
                    </div>
                  </th>
                ))}
                {editor.outputColumns.map((column, index) => (
                  <th key={`output:${column.id}`} className="rule-editor-column output">
                    <div className="rule-editor-column-header">
                      <span>Output</span>
                      <input
                        aria-label={`Output column ${index + 1} name`}
                        data-testid={`decision-output-column-name:${index}`}
                        value={column.label}
                        onChange={(event) => renameOutputColumn(index, event.target.value)}
                      />
                      <button
                        type="button"
                        className="secondary compact"
                        onClick={() => deleteOutputColumn(index)}
                        disabled={editor.outputColumns.length <= 1}
                        aria-label={`Delete output column ${column.label}`}
                      >
                        Delete
                      </button>
                    </div>
                  </th>
                ))}
                <th>Otherwise</th>
                <th aria-label="Rule actions" />
              </tr>
            </thead>
            <tbody>
              {editor.rows.map((row, index) => (
                <tr key={`rule:${index}:${row.otherwise ? 'otherwise' : 'match'}`}>
                  <td className="rule-editor-row-index">{index + 1}</td>
                  {editor.conditionColumns.map((column) => (
                    <td key={`condition:${column.id}`} className="rule-editor-condition-cell">
                      <input
                        aria-label={`Rule ${index + 1} ${column.label} condition`}
                        data-testid={`decision-rule-condition:${index}:${column.id}`}
                        value={row.conditions[column.id] ?? ''}
                        disabled={row.otherwise}
                        onChange={(event) => updateConditionCell(index, column.id, event.target.value)}
                      />
                    </td>
                  ))}
                  {editor.outputColumns.map((column) => (
                    <td key={`output:${column.id}`} className="rule-editor-output-cell">
                      <input
                        aria-label={`Rule ${index + 1} ${column.label} output`}
                        data-testid={`decision-rule-output:${index}:${column.id}`}
                        value={row.outputs[column.id] ?? ''}
                        onChange={(event) => updateOutputCell(index, column.id, event.target.value)}
                      />
                    </td>
                  ))}
                  <td>
                    <input
                      type="checkbox"
                      aria-label={`Rule ${index + 1} otherwise`}
                      data-testid={`decision-rule-otherwise:${index}`}
                      checked={row.otherwise}
                      onChange={(event) => updateRow(index, {
                        otherwise: event.target.checked,
                        conditions: event.target.checked
                          ? emptyDecisionValues(editor.conditionColumns)
                          : {
                              ...row.conditions,
                              ...decisionConditionMapFromRow(row, editor.conditionColumns),
                            },
                      })}
                    />
                  </td>
                  <td>
                    <button
                      type="button"
                      className="secondary compact"
                      onClick={() => deleteRow(index)}
                      disabled={editor.rows.length <= 1}
                    >
                      Delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="rule-editor-actions">
          <button type="button" className="secondary compact" onClick={addRow}>
            Add Rule
          </button>
        </div>
      </section>
    </div>
  );
}

function functionSignatureLabel(fn: BuiltInFunctionDefinition): string {
  return fn.signatures?.[0]?.label || `${fn.name}()`;
}

function functionCallSnippet(fn: BuiltInFunctionDefinition): string {
  const signature = fn.signatures?.[0];
  const parameters = signature?.parameters ?? [];
  if (parameters.length === 0) {
    return `${fn.name}()`;
  }
  return `${fn.name}(${parameters.map((parameter) => parameter.name).join(', ')})`;
}

function insertFunctionSnippet(expression: string, fn: BuiltInFunctionDefinition): string {
  const snippet = functionCallSnippet(fn);
  const trimmed = expression.trim();
  if (!trimmed || trimmed === '{}') {
    return snippet;
  }
  return `${expression}${expression.endsWith(' ') ? '' : ' '}${snippet}`;
}

function expressionSignatureHints(
  expression: string,
  functions: BuiltInFunctionDefinition[],
): BuiltInFunctionDefinition[] {
  const trimmed = expression.trim().toLowerCase();
  if (!trimmed || trimmed === '{}') {
    return functions.slice(0, 6);
  }
  const referenced = functions.filter((fn) =>
    new RegExp(`(?:^|[^A-Za-z0-9_.])${fn.name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\(`)
      .test(expression),
  );
  if (referenced.length > 0) {
    return referenced.slice(0, 6);
  }
  return functions
    .filter((fn) => {
      const haystack = `${fn.name} ${fn.displayName ?? ''} ${fn.description ?? ''} ${fn.category ?? ''}`
        .toLowerCase();
      return haystack.includes(trimmed);
    })
    .slice(0, 6);
}

function TransformAssignmentEditor({
  node,
  onClose,
  onChange,
  builtInFunctions,
  embedded = false,
}: {
  node: Node<NodeData>;
  onClose: () => void;
  onChange: (editor: TransformEditorModel) => void;
  builtInFunctions: BuiltInFunctionDefinition[];
  embedded?: boolean;
}) {
  const editor = transformEditorModel(node.data.config);
  const updateEditor = (next: TransformEditorModel) => onChange(next);
  const updateAssignment = (index: number, patch: Partial<TransformAssignmentRow>) => {
    updateEditor({
      assignments: editor.assignments.map((row, rowIndex) =>
        rowIndex === index ? { ...row, ...patch } : row,
      ),
    });
  };
  const addAssignment = () => {
    const field = uniqueDecisionColumnId(`field${editor.assignments.length + 1}`, editor.assignments.map((row) => row.field));
    updateEditor({
      assignments: [
        ...editor.assignments,
        { field, expression: '{}' },
      ],
    });
  };
  const deleteAssignment = (index: number) => {
    const assignments = editor.assignments.filter((_, rowIndex) => rowIndex !== index);
    updateEditor({ assignments: assignments.length > 0 ? assignments : [{ field: 'result', expression: '{}' }] });
  };
  return (
    <div className={embedded ? 'rule-editor-embedded-wrap' : 'rule-editor-backdrop'} role="presentation">
      <section
        className={`rule-editor transform-editor ${embedded ? 'embedded' : ''}`}
        role={embedded ? 'group' : 'dialog'}
        aria-modal={embedded ? undefined : true}
        aria-labelledby="transform-assignment-editor-title"
        data-testid="transform-assignment-editor"
      >
        <div className="rule-editor-heading">
          <span>Transform mapping</span>
          <strong id="transform-assignment-editor-title">{node.data.label}</strong>
          <button
            type="button"
            className="secondary compact"
            onClick={onClose}
            aria-label="Close transform mapping editor"
          >
            Done
          </button>
        </div>
        <div className="rule-editor-table-wrap">
          <table className="rule-editor-table transform-editor-table">
            <thead>
              <tr>
                <th className="rule-editor-row-index">#</th>
                <th>Output Field</th>
                <th>Expression</th>
                <th aria-label="Assignment actions" />
              </tr>
            </thead>
            <tbody>
              {editor.assignments.map((assignment, index) => {
                const signatureHints = expressionSignatureHints(assignment.expression, builtInFunctions);
                return (
                  <tr key={`assignment:${index}:${assignment.field}`}>
                    <td className="rule-editor-row-index">{index + 1}</td>
                    <td className="rule-editor-output-cell">
                      <input
                        aria-label={`Assignment ${index + 1} output field`}
                        data-testid={`transform-assignment-field:${index}`}
                        value={assignment.field}
                        onChange={(event) => updateAssignment(index, {
                          field: decisionFieldName(event.target.value, assignment.field || `field${index + 1}`),
                        })}
                      />
                    </td>
                    <td className="rule-editor-expression-cell">
                      <input
                        aria-label={`Assignment ${index + 1} expression`}
                        data-testid={`transform-assignment-expression:${index}`}
                        value={assignment.expression}
                        onChange={(event) => updateAssignment(index, { expression: event.target.value })}
                        list={`transform-function-completions:${index}`}
                      />
                      {builtInFunctions.length > 0 && (
                        <>
                          <datalist id={`transform-function-completions:${index}`}>
                            {builtInFunctions.map((fn) => (
                              <option key={`${fn.namespace ?? 'default'}:${fn.name}`} value={fn.name}>
                                {functionSignatureLabel(fn)}
                              </option>
                            ))}
                          </datalist>
                          <div className="expression-assist" data-testid={`transform-expression-assist:${index}`}>
                            <div className="expression-function-buttons">
                              {builtInFunctions.slice(0, 6).map((fn) => (
                                <button
                                  type="button"
                                  className="function-chip"
                                  key={`${fn.namespace ?? 'default'}:${fn.name}`}
                                  data-testid={`transform-function-insert:${index}:${fn.name}`}
                                  onClick={() => updateAssignment(index, {
                                    expression: insertFunctionSnippet(assignment.expression, fn),
                                  })}
                                >
                                  {fn.name}
                                </button>
                              ))}
                            </div>
                            <div className="signature-hints">
                              {signatureHints.map((fn) => (
                                <div
                                  className="signature-hint"
                                  key={`${fn.namespace ?? 'default'}:${fn.name}`}
                                  data-testid={`transform-function-signature:${index}:${fn.name}`}
                                >
                                  <code>{functionSignatureLabel(fn)}</code>
                                  {fn.description && <span>{fn.description}</span>}
                                </div>
                              ))}
                            </div>
                          </div>
                        </>
                      )}
                    </td>
                    <td>
                      <button
                        type="button"
                        className="secondary compact"
                        onClick={() => deleteAssignment(index)}
                        disabled={editor.assignments.length <= 1}
                      >
                        Delete
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        <div className="rule-editor-actions">
          <button
            type="button"
            className="secondary compact"
            onClick={addAssignment}
            data-testid="transform-add-assignment"
          >
            Add Assignment
          </button>
        </div>
      </section>
    </div>
  );
}

function operatorFocusRows(
  summary: OperatorSummary,
  inputs: OperatorPort[],
  outputs: OperatorPort[],
  operator: OperatorDefinition | undefined,
): OperatorFocusRow[] {
  const inputSignature = portSignatures(inputs, summary.inputContractLabel || 'input');
  const outputSignature = portSignatures(outputs, summary.outputContractLabel || 'output');
  const readiness = summary.readinessNotice
    ? [{ key: 'readiness', label: 'Readiness', value: summary.readinessNotice }]
    : [];
  const sideEffect = summary.externalWrite
    ? [{ key: 'side-effect', label: 'Side-effect protocol', value: summary.sideEffectNotice }]
    : [];
  if (summary.visualKind === 'decision-table') {
    return [
      { key: 'conditions', label: 'Condition inputs', value: inputSignature },
      { key: 'decision', label: 'Decision output', value: outputSignature },
      { key: 'rules', label: 'Rule matrix', value: 'typed conditions -> matched row' },
      { key: 'lowering', label: 'Lowering', value: operator?.lowering?.mode || 'dsl' },
      ...sideEffect,
      ...readiness,
    ];
  }
  if (summary.visualKind === 'foreach') {
    return [
      { key: 'collection', label: 'Collection', value: inputSignature },
      { key: 'item', label: 'Item context', value: itemContextLabel(inputs) },
      { key: 'result', label: 'Result list', value: outputSignature },
      { key: 'cardinality', label: 'Cardinality', value: 'per item -> aggregated list' },
      ...sideEffect,
      ...readiness,
    ];
  }
  if (summary.visualKind === 'resource') {
    return [
      { key: 'params', label: 'Request params', value: inputSignature },
      { key: 'payload', label: 'Response payload', value: outputSignature },
      { key: 'boundary', label: 'Boundary', value: operator?.source?.kind || summary.sourceKind },
      ...sideEffect,
      ...readiness,
    ];
  }
  if (summary.visualKind === 'http') {
    return [
      { key: 'request', label: 'HTTP request', value: inputSignature },
      { key: 'response', label: 'HTTP response', value: outputSignature },
      { key: 'boundary', label: 'Boundary', value: operator?.source?.kind || summary.sourceKind },
      ...sideEffect,
      ...readiness,
    ];
  }
  if (summary.visualKind === 'transform') {
    return [
      { key: 'source', label: 'Source fields', value: inputSignature },
      { key: 'mapped', label: 'Mapped output', value: outputSignature },
      { key: 'lowering', label: 'Lowering', value: operator?.lowering?.mode || 'transform' },
      ...sideEffect,
      ...readiness,
    ];
  }
  if (summary.visualKind === 'streaming') {
    return [
      { key: 'request', label: 'Request', value: inputSignature },
      { key: 'stream', label: 'Event stream', value: outputSignature },
      { key: 'boundary', label: 'Boundary', value: operator?.source?.kind || summary.sourceKind },
      ...sideEffect,
      ...readiness,
    ];
  }
  if (summary.visualKind === 'design') {
    return [
      { key: 'inputs', label: 'Schema input', value: inputSignature },
      { key: 'outputs', label: 'Schema output', value: outputSignature },
      { key: 'lowering', label: 'Lowering', value: operator?.lowering?.mode || 'design' },
      ...sideEffect,
      ...readiness,
    ];
  }
  return [
    { key: 'inputs', label: 'Input contract', value: inputSignature },
    { key: 'outputs', label: 'Output contract', value: outputSignature },
    ...sideEffect,
    ...readiness,
  ];
}

function operatorFocusTitle(kind: OperatorSummary['visualKind']): string {
  if (kind === 'decision-table') {
    return 'Rule contract';
  }
  if (kind === 'foreach') {
    return 'Loop contract';
  }
  if (kind === 'resource') {
    return 'Resource contract';
  }
  if (kind === 'http') {
    return 'HTTP contract';
  }
  if (kind === 'transform') {
    return 'Mapping contract';
  }
  if (kind === 'streaming') {
    return 'Stream contract';
  }
  if (kind === 'design') {
    return 'Design contract';
  }
  return 'Schema contract';
}

function portSignatures(ports: OperatorPort[], fallback: string): string {
  if (ports.length === 0) {
    return fallback;
  }
  return ports.map((port) => `${port.name || 'value'}:${schemaKindLabel(port.schema?.schema)}`).join(', ');
}

function itemContextLabel(inputs: OperatorPort[]): string {
  const collection = inputs.find((port) => schemaKindLabel(port.schema?.schema) === 'array') ?? inputs[0];
  const schema = collection?.schema?.schema;
  const items = schema && typeof schema.items === 'object' && schema.items
    ? schema.items as Record<string, unknown>
    : undefined;
  return `${collection?.name || 'item'} item:${schemaKindLabel(items)}`;
}

function schemaKindLabel(schema: Record<string, unknown> | undefined): string {
  if (!schema) {
    return 'any';
  }
  const rawType = schema.type;
  if (typeof rawType === 'string' && rawType) {
    return rawType;
  }
  if (Array.isArray(rawType)) {
    const nonNull = rawType.find((value) => typeof value === 'string' && value !== 'null');
    return typeof nonNull === 'string' ? nonNull : 'any';
  }
  if (schema.properties || schema.required || schema.additionalProperties) {
    return 'object';
  }
  if (schema.items || schema.prefixItems || schema.contains) {
    return 'array';
  }
  return 'any';
}

function schemaEnvelope(schema: Record<string, unknown>): SchemaEnvelope {
  return {
    format: 'json-schema',
    version: '2020-12',
    schema,
  };
}

function schemaProperties(schema: Record<string, unknown> | undefined): Record<string, Record<string, unknown>> {
  const properties = schema?.properties;
  return isRecord(properties)
    ? Object.fromEntries(
      Object.entries(properties)
        .filter((entry): entry is [string, Record<string, unknown>] => isRecord(entry[1])),
    )
    : {};
}

function schemaRequiredSet(schema: Record<string, unknown> | undefined): Set<string> {
  return new Set(Array.isArray(schema?.required) ? schema.required.map(String) : []);
}

function schemaFieldRows(envelope: SchemaEnvelope | undefined): Array<{
  name: string;
  type: string;
  required: boolean;
}> {
  const properties = schemaProperties(envelope?.schema);
  const required = schemaRequiredSet(envelope?.schema);
  return Object.entries(properties).map(([name, schema]) => ({
    name,
    type: schemaKindLabel(schema),
    required: required.has(name),
  }));
}

function graphSchemaSummary(envelope: SchemaEnvelope | undefined): {
  type: string;
  fieldCount: number;
  requiredCount: number;
  fields: Array<{ name: string; type: string; required: boolean }>;
} {
  const fields = schemaFieldRows(envelope);
  return {
    type: schemaKindLabel(envelope?.schema),
    fieldCount: fields.length,
    requiredCount: fields.filter((field) => field.required).length,
    fields,
  };
}

function schemaPreview(envelope: SchemaEnvelope | undefined): string {
  return JSON.stringify(envelope?.schema ?? { type: 'any' }, null, 2);
}

function operatorConfigPreview(config: Record<string, unknown> | undefined): string {
  return JSON.stringify(config ?? {}, null, 2);
}

function configTextValue(config: Record<string, unknown> | undefined, key: string): string {
  const value = config?.[key];
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  return '';
}

function configPatchValue(value: string, coerce: 'string' | 'number' = 'string'): unknown {
  const trimmed = value.trim();
  if (!trimmed) {
    return undefined;
  }
  if (coerce === 'number') {
    const parsed = Number(trimmed);
    return Number.isFinite(parsed) ? parsed : trimmed;
  }
  return trimmed;
}

function operatorDefaultResourceId(node: Node<NodeData>, operator: OperatorDefinition | undefined): string {
  return operator?.source?.libraryId || node.data.operatorRef;
}

function operatorPropertyRows(
  node: Node<NodeData>,
  operator: OperatorDefinition | undefined,
): Array<{ label: string; value: string }> {
  return [
    { label: 'Operator Ref', value: node.data.operatorRef },
    { label: 'Source', value: operator?.source?.kind || node.data.summary.sourceKind || 'unknown' },
    { label: 'Lowering', value: operator?.lowering?.mode || 'not declared' },
    { label: 'Readiness', value: node.data.summary.readinessNotice || node.data.summary.readinessState },
    { label: 'Side-effect protocol', value: node.data.summary.sideEffectNotice || 'not applicable' },
    { label: 'Tags', value: operator?.display?.tags?.join(', ') || 'none' },
  ];
}

function OperatorKeyPropertiesEditor({
  node,
  operator,
  onLabelChange,
  onConfigPatch,
}: {
  node: Node<NodeData>;
  operator: OperatorDefinition | undefined;
  onLabelChange: (value: string) => void;
  onConfigPatch: (patch: Record<string, unknown>) => void;
}) {
  const config = node.data.config ?? {};
  const resourceLike = node.data.summary.visualKind === 'resource' || node.data.summary.visualKind === 'http';
  return (
    <section className="operator-detail-section operator-key-properties">
      <h3>Key properties</h3>
      <label className="operator-detail-field">
        <span>Node label</span>
        <input
          aria-label="Operator node label"
          data-testid="operator-detail-label"
          value={node.data.label}
          onChange={(event) => onLabelChange(event.target.value)}
        />
      </label>
      <dl className="operator-property-list">
        {operatorPropertyRows(node, operator).map((row) => (
          <div key={row.label}>
            <dt>{row.label}</dt>
            <dd>{row.value}</dd>
          </div>
        ))}
      </dl>
      {resourceLike && (
        <div className="resource-config-grid" data-testid="operator-detail-resource-config">
          <label className="operator-detail-field">
            <span>Resource ID</span>
            <input
              aria-label="Resource ID"
              data-testid="operator-detail-resource-id"
              placeholder={operatorDefaultResourceId(node, operator)}
              value={configTextValue(config, 'resourceId')}
              onChange={(event) => onConfigPatch({ resourceId: configPatchValue(event.target.value) })}
            />
          </label>
          <label className="operator-detail-field">
            <span>Method</span>
            <select
              aria-label="HTTP method"
              data-testid="operator-detail-http-method"
              value={configTextValue(config, 'method')}
              onChange={(event) => onConfigPatch({ method: configPatchValue(event.target.value) })}
            >
              <option value="">default</option>
              <option value="GET">GET</option>
              <option value="POST">POST</option>
              <option value="PUT">PUT</option>
              <option value="PATCH">PATCH</option>
              <option value="DELETE">DELETE</option>
            </select>
          </label>
          <label className="operator-detail-field resource-url-field">
            <span>URL / route</span>
            <input
              aria-label="Resource URL"
              data-testid="operator-detail-resource-url"
              placeholder="/resource/path"
              value={configTextValue(config, 'url')}
              onChange={(event) => onConfigPatch({ url: configPatchValue(event.target.value) })}
            />
          </label>
          <label className="operator-detail-field">
            <span>Timeout ms</span>
            <input
              aria-label="Resource timeout milliseconds"
              data-testid="operator-detail-resource-timeout"
              inputMode="numeric"
              placeholder="3000"
              value={configTextValue(config, 'timeoutMs')}
              onChange={(event) => onConfigPatch({ timeoutMs: configPatchValue(event.target.value, 'number') })}
            />
          </label>
        </div>
      )}
    </section>
  );
}

function OperatorConfigEditor({
  config,
  onApply,
}: {
  config: Record<string, unknown> | undefined;
  onApply: (config: Record<string, unknown>) => void;
}) {
  const renderedConfig = operatorConfigPreview(config);
  const [draft, setDraft] = useState(renderedConfig);
  const [error, setError] = useState('');
  useEffect(() => {
    setDraft(renderedConfig);
    setError('');
  }, [renderedConfig]);

  const applyDraft = () => {
    try {
      const parsed = JSON.parse(draft || '{}') as unknown;
      if (!isRecord(parsed)) {
        setError('Node config must be a JSON object.');
        return;
      }
      setError('');
      onApply(parsed);
    } catch (cause: unknown) {
      setError(cause instanceof Error ? cause.message : String(cause));
    }
  };

  return (
    <section className="operator-detail-section operator-config-editor">
      <div className="operator-detail-section-heading">
        <h3>Advanced config</h3>
        <button
          type="button"
          className="secondary compact"
          data-testid="operator-detail-config-apply"
          onClick={applyDraft}
        >
          Apply
        </button>
      </div>
      <textarea
        aria-label="Operator node config JSON"
        data-testid="operator-detail-config-json"
        spellCheck={false}
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
      />
      {error && <p className="fixture-error" data-testid="operator-detail-config-error">{error}</p>}
    </section>
  );
}

function OperatorFixtureEditor({
  fixtureDraft,
  expectedInputDraft,
  hasFixtureDraft,
  fixtureError,
  onOutputChange,
  onExpectedInputChange,
  onUseSample,
  onClear,
}: {
  fixtureDraft: string;
  expectedInputDraft: string;
  hasFixtureDraft: boolean;
  fixtureError: string | undefined;
  onOutputChange: (value: string) => void;
  onExpectedInputChange: (value: string) => void;
  onUseSample: () => void;
  onClear: () => void;
}) {
  return (
    <div className="fixture-editor operator-detail-fixtures" data-testid="operator-detail-fixtures">
      <div className="fixture-header">
        <strong>Input / Output samples</strong>
        <span className={`badge ${hasFixtureDraft ? 'fixture' : ''}`}>
          {hasFixtureDraft ? 'custom' : 'server sample'}
        </span>
      </div>
      <div className="fixture-actions">
        <button
          className="secondary compact"
          data-testid="operator-detail-use-sample"
          onClick={onUseSample}
        >
          Use Sample
        </button>
        <button
          className="secondary compact"
          data-testid="operator-detail-clear-fixture"
          onClick={onClear}
          disabled={!hasFixtureDraft}
        >
          Clear
        </button>
      </div>
      <label className="fixture-field">
        <span>Output sample</span>
        <textarea
          aria-label="Operator output sample JSON"
          data-testid="operator-detail-output-fixture"
          spellCheck={false}
          placeholder="null"
          value={fixtureDraft}
          onChange={(event) => onOutputChange(event.target.value)}
        />
      </label>
      <label className="fixture-field">
        <span>Expected input</span>
        <textarea
          aria-label="Operator expected input JSON"
          data-testid="operator-detail-expected-input"
          spellCheck={false}
          placeholder="{}"
          value={expectedInputDraft}
          onChange={(event) => onExpectedInputChange(event.target.value)}
        />
      </label>
      {fixtureError && <p className="fixture-error" data-testid="operator-detail-fixture-error">{fixtureError}</p>}
    </div>
  );
}

function OperatorTestSuiteEditor({
  operator,
  rows,
  results,
  publication,
  running,
  runDisabledReason,
  onAdd,
  onUpdate,
  onRemove,
  onApplyFixture,
  onRun,
  onRunAll,
  onGovern,
  onGovernAll,
}: {
  operator?: OperatorDefinition;
  rows: OperatorTestSuiteDraftRow[];
  results: Record<string, OperatorTestCaseResult>;
  publication?: OperatorTestSuitePublicationResult;
  running: boolean;
  runDisabledReason?: string;
  onAdd: () => void;
  onUpdate: (rowId: string, patch: Partial<OperatorTestSuiteDraftRow>) => void;
  onRemove: (rowId: string) => void;
  onApplyFixture: (row: OperatorTestSuiteDraftRow) => void;
  onRun: (row: OperatorTestSuiteDraftRow) => void;
  onRunAll: () => void;
  onGovern: (row: OperatorTestSuiteDraftRow) => void;
  onGovernAll: () => void;
}) {
  const transportControlled = operatorUsesTransportFixture(operator);
  const invalidCount = rows
    .map(parseOperatorTestSuiteRow)
    .filter((compilation) => compilation.error)
    .length;
  const resultValues = Object.values(results);
  const passedCount = resultValues.filter((result) => result.status === 'passed').length;
  const failedCount = resultValues.filter((result) => result.status === 'failed').length;
  const resultLabel = running
    ? 'running'
    : resultValues.length > 0
      ? `${passedCount}/${rows.length} passed${failedCount > 0 ? ` · ${failedCount} failed` : ''}`
      : invalidCount > 0
        ? `${invalidCount} invalid`
        : `${rows.length} valid`;
  const summaryStatus = running ? 'running' : failedCount > 0 || invalidCount > 0 ? 'failed' : passedCount > 0 ? 'passed' : 'pending';
  return (
    <section className="operator-detail-section operator-test-suite" data-testid="operator-test-suite">
      <div className="operator-detail-section-heading">
        <h3>Executable Operator Suite</h3>
        <div className="test-table-actions">
          <span className={`table-status ${summaryStatus}`} data-testid="operator-test-summary">
            {resultLabel}
          </span>
          <button
            type="button"
            className="primary compact"
            data-testid="operator-test-run-all"
            onClick={onRunAll}
            disabled={running || rows.length === 0 || invalidCount > 0 || Boolean(runDisabledReason)}
            title={runDisabledReason}
          >
            {running ? 'Running' : 'Run Exploratory'}
          </button>
          <button
            type="button"
            className="secondary compact"
            data-testid="operator-test-govern-all"
            onClick={onGovernAll}
            disabled={running || rows.length === 0 || invalidCount > 0 || Boolean(runDisabledReason)}
            title={runDisabledReason}
          >
            Publish Suite + Run
          </button>
          <button
            type="button"
            className="secondary compact"
            data-testid="operator-test-add"
            onClick={onAdd}
            disabled={running}
          >
            Add Case
          </button>
        </div>
      </div>
      {publication && (
        <div
          className={`operator-suite-publication ${publication.status}`}
          data-testid="operator-suite-publication"
        >
          <strong>{publication.detail}</strong>
          {publication.suiteId && (
            <span>
              {publication.suiteId}@{publication.revision} · run {publication.suiteRunId}
            </span>
          )}
        </div>
      )}
      {rows.length > 0 ? (
        <ol className="test-table-list operator-test-table">
          {rows.map((row, index) => {
            const compilation = parseOperatorTestSuiteRow(row);
            const result = results[row.id];
            const rowStatus = result?.status ?? (compilation.error ? 'failed' : 'pending');
            return (
              <li
                key={row.id}
                className={`test-table-row ${rowStatus}`}
                data-testid={`operator-test-row:${index}`}
              >
                <div className="test-table-row-heading">
                  <input
                    aria-label={`Operator test case name ${index + 1}`}
                    data-testid={`operator-test-name:${index}`}
                    value={row.name}
                    onChange={(event) => onUpdate(row.id, { name: event.target.value })}
                    disabled={running}
                  />
                  <span
                    className={`table-status ${rowStatus}`}
                    data-testid={`operator-test-status:${index}`}
                  >
                    {compilation.error ? 'invalid' : result?.status ?? 'valid'}
                  </span>
                  <label className="operator-test-case-type">
                    <span>Intent</span>
                    <select
                      aria-label={`Operator test case intent ${index + 1}`}
                      data-testid={`operator-test-case-type:${index}`}
                      value={row.caseType}
                      onChange={(event) => onUpdate(row.id, {
                        caseType: event.target.value as OperatorTestSuiteCaseType,
                      })}
                      disabled={running}
                    >
                      <option value="GOLDEN">Golden</option>
                      <option value="NEGATIVE">Negative</option>
                      <option value="BOUNDARY">Boundary</option>
                      <option value="REGRESSION">Regression</option>
                    </select>
                  </label>
                  <button
                    type="button"
                    className="primary compact"
                    data-testid={`operator-test-run:${index}`}
                    onClick={() => onRun(row)}
                    disabled={running || Boolean(compilation.error) || Boolean(runDisabledReason)}
                    title={runDisabledReason}
                  >
                    Run Case
                  </button>
                  <button
                    type="button"
                    className="secondary compact"
                    data-testid={`operator-test-govern:${index}`}
                    onClick={() => onGovern(row)}
                    disabled={running || Boolean(compilation.error) || Boolean(runDisabledReason)}
                    title={runDisabledReason}
                  >
                    Publish Case + Run
                  </button>
                  <button
                    type="button"
                    className="secondary compact"
                    data-testid={`operator-test-apply:${index}`}
                    onClick={() => onApplyFixture(row)}
                    disabled={running || Boolean(compilation.error)}
                  >
                    Apply Fixture
                  </button>
                  <button
                    type="button"
                    className="secondary compact"
                    aria-label={`Remove operator test case ${index + 1}`}
                    data-testid={`operator-test-remove:${index}`}
                    onClick={() => onRemove(row.id)}
                    disabled={running || rows.length <= 1}
                  >
                    Remove
                  </button>
                </div>
                <label className="fixture-field">
                  <span>Input case</span>
                  <textarea
                    aria-label={`Operator test input case ${index + 1}`}
                    data-testid={`operator-test-input:${index}`}
                    spellCheck={false}
                    value={row.inputText}
                    onChange={(event) => onUpdate(row.id, { inputText: event.target.value })}
                    disabled={running}
                  />
                </label>
                <label className="fixture-field">
                  <span>Expected output</span>
                  <textarea
                    aria-label={`Operator test expected output ${index + 1}`}
                    data-testid={`operator-test-output:${index}`}
                    spellCheck={false}
                    value={row.outputText}
                    onChange={(event) => {
                      const outputText = event.target.value;
                      const transportResponseText = transportControlled
                        ? transportResponseTextAfterExpectedEdit(
                          operator,
                          row.outputText,
                          outputText,
                          row.transportResponseText,
                        )
                        : row.transportResponseText;
                      onUpdate(row.id, { outputText, transportResponseText });
                    }}
                    disabled={running}
                  />
                </label>
                {transportControlled && (
                  <label className="fixture-field">
                    <span>Transport response</span>
                    <textarea
                      aria-label={`Operator test transport response ${index + 1}`}
                      data-testid={`operator-test-transport:${index}`}
                      spellCheck={false}
                      value={row.transportResponseText}
                      onChange={(event) => onUpdate(row.id, { transportResponseText: event.target.value })}
                      disabled={running}
                    />
                  </label>
                )}
                {compilation.error && (
                  <p className="fixture-error" data-testid={`operator-test-error:${index}`}>
                    {compilation.error}
                  </p>
                )}
                {result && !compilation.error && (
                  <div className={`operator-test-result ${result.status}`}>
                    <p data-testid={`operator-test-result:${index}`}>{result.detail}</p>
                    {result.actualOutput !== undefined && (
                      <details>
                        <summary>Actual output</summary>
                        <pre data-testid={`operator-test-actual:${index}`}>
                          {JSON.stringify(result.actualOutput, null, 2)}
                        </pre>
                      </details>
                    )}
                  </div>
                )}
              </li>
            );
          })}
        </ol>
      ) : (
        <p className="muted">No executable operator cases.</p>
      )}
    </section>
  );
}

function SchemaPortCards({
  title,
  direction,
  ports,
}: {
  title: string;
  direction: 'input' | 'output';
  ports: OperatorPort[];
}) {
  return (
    <section className="operator-detail-section">
      <h3>{title}</h3>
      {ports.length > 0 ? (
        <div className="operator-schema-grid">
          {ports.map((port, index) => {
            const fields = schemaFieldRows(port.schema);
            return (
              <article className="operator-schema-card" key={`${direction}:${port.name || index}`}>
                <div>
                  <strong>{port.name || (direction === 'input' ? 'input' : 'output')}</strong>
                  {port.required && <span className="schema-required">required</span>}
                </div>
                <div className="operator-schema-summary">
                  <span>{schemaKindLabel(port.schema?.schema)}</span>
                  <span>{fields.length} field{fields.length === 1 ? '' : 's'}</span>
                </div>
                {port.description && <p>{port.description}</p>}
                {fields.length > 0 && (
                  <table
                    className="schema-field-table"
                    data-testid={`operator-detail-schema-fields:${direction}:${index}`}
                  >
                    <tbody>
                      {fields.map((field) => (
                        <tr key={field.name}>
                          <th>{field.name}</th>
                          <td>{field.type}</td>
                          <td>{field.required ? 'required' : 'optional'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
                <details>
                  <summary>Raw schema</summary>
                  <pre data-testid={`operator-detail-schema:${direction}:${index}`}>
                    {schemaPreview(port.schema)}
                  </pre>
                </details>
              </article>
            );
          })}
        </div>
      ) : (
        <p className="muted">No declared {direction} ports.</p>
      )}
    </section>
  );
}

function ForeachLoopGuide({
  inputs,
  outputs,
}: {
  inputs: OperatorPort[];
  outputs: OperatorPort[];
}) {
  const collection = inputs.find((port) => schemaKindLabel(port.schema?.schema) === 'array') ?? inputs[0];
  const result = outputs.find((port) => schemaKindLabel(port.schema?.schema) === 'array') ?? outputs[0];
  return (
    <section className="foreach-loop-guide" data-testid="foreach-loop-guide">
      <h3>Loop guide</h3>
      <div className="foreach-loop-steps">
        <div>
          <span>1</span>
          <strong>Bind collection</strong>
          <p>Connect an array into <code>{collection?.name || 'input'}</code>.</p>
        </div>
        <div>
          <span>2</span>
          <strong>Run per item</strong>
          <p>Each item becomes the item context: <code>{itemContextLabel(inputs)}</code>.</p>
        </div>
        <div>
          <span>3</span>
          <strong>Collect result list</strong>
          <p>Downstream nodes consume <code>{result?.name || 'output'}</code> as an array.</p>
        </div>
      </div>
    </section>
  );
}

function OperatorDetailDialog({
  node,
  operator,
  incomingColumns,
  builtInFunctions,
  fixtureDraft,
  expectedInputDraft,
  hasFixtureDraft,
  fixtureError,
  operatorTestRows,
  operatorTestResults,
  operatorTestPublication,
  operatorTestsRunning,
  operatorTestRunDisabledReason,
  onClose,
  onLabelChange,
  onConfigPatch,
  onConfigReplace,
  onInputAdd,
  onInputRemove,
  onInputRename,
  onInputChange,
  onInputKindChange,
  onDropContextPath,
  onFixtureOutputChange,
  onExpectedInputChange,
  onUseFixtureSample,
  onClearFixture,
  onOperatorTestAdd,
  onOperatorTestUpdate,
  onOperatorTestRemove,
  onOperatorTestApplyFixture,
  onOperatorTestRun,
  onOperatorTestRunAll,
  onOperatorTestGovern,
  onOperatorTestGovernAll,
  onDecisionChange,
  onTransformChange,
}: {
  node: Node<NodeData>;
  operator: OperatorDefinition | undefined;
  incomingColumns: DecisionTableColumn[];
  builtInFunctions: BuiltInFunctionDefinition[];
  fixtureDraft: string;
  expectedInputDraft: string;
  hasFixtureDraft: boolean;
  fixtureError: string | undefined;
  operatorTestRows: OperatorTestSuiteDraftRow[];
  operatorTestResults: Record<string, OperatorTestCaseResult>;
  operatorTestPublication?: OperatorTestSuitePublicationResult;
  operatorTestsRunning: boolean;
  operatorTestRunDisabledReason?: string;
  onClose: () => void;
  onLabelChange: (value: string) => void;
  onConfigPatch: (patch: Record<string, unknown>) => void;
  onConfigReplace: (config: Record<string, unknown>) => void;
  onInputAdd: () => void;
  onInputRemove: (bindingKey: string) => void;
  onInputRename: (bindingKey: string, value: string) => void;
  onInputChange: (bindingKey: string, patch: Partial<DraftNodeBinding>) => void;
  onInputKindChange: (bindingKey: string, kind: 'contextPath' | 'constant') => void;
  onDropContextPath: (path: string) => void;
  onFixtureOutputChange: (value: string) => void;
  onExpectedInputChange: (value: string) => void;
  onUseFixtureSample: () => void;
  onClearFixture: () => void;
  onOperatorTestAdd: () => void;
  onOperatorTestUpdate: (rowId: string, patch: Partial<OperatorTestSuiteDraftRow>) => void;
  onOperatorTestRemove: (rowId: string) => void;
  onOperatorTestApplyFixture: (row: OperatorTestSuiteDraftRow) => void;
  onOperatorTestRun: (row: OperatorTestSuiteDraftRow) => void;
  onOperatorTestRunAll: () => void;
  onOperatorTestGovern: (row: OperatorTestSuiteDraftRow) => void;
  onOperatorTestGovernAll: () => void;
  onDecisionChange: (editor: DecisionTableEditorModel) => void;
  onTransformChange: (editor: TransformEditorModel) => void;
}) {
  const inputs = operator?.ports?.inputs ?? [];
  const outputs = operator?.ports?.outputs ?? [];
  const focusRows = operatorFocusRows(node.data.summary, inputs, outputs, operator);
  return (
    <div className="rule-editor-backdrop" role="presentation">
      <section
        className="operator-detail-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="operator-detail-title"
        data-testid="operator-detail-dialog"
      >
        <div className="operator-detail-heading">
          <span>{node.data.summary.visualLabel}</span>
          <strong id="operator-detail-title">{node.data.label}</strong>
          <button
            type="button"
            className="secondary compact"
            onClick={onClose}
            aria-label="Close operator details"
          >
            Done
          </button>
        </div>
        <div className="operator-detail-body">
          <OperatorKeyPropertiesEditor
            node={node}
            operator={operator}
            onLabelChange={onLabelChange}
            onConfigPatch={onConfigPatch}
          />

          <section className="operator-detail-section">
            <h3>{operatorFocusTitle(node.data.summary.visualKind)}</h3>
            <div className="operator-detail-focus">
              {focusRows.map((row) => (
                <div key={row.key}>
                  <span>{row.label}</span>
                  <strong>{row.value}</strong>
                </div>
              ))}
            </div>
          </section>

          {node.data.summary.visualKind === 'foreach' && (
            <ForeachLoopGuide inputs={inputs} outputs={outputs} />
          )}

          <NodeInputBindingsEditor
            node={node}
            onAdd={onInputAdd}
            onRemove={onInputRemove}
            onRename={onInputRename}
            onChange={onInputChange}
            onKindChange={onInputKindChange}
            onDropContextPath={onDropContextPath}
          />

          <OperatorFixtureEditor
            fixtureDraft={fixtureDraft}
            expectedInputDraft={expectedInputDraft}
            hasFixtureDraft={hasFixtureDraft}
            fixtureError={fixtureError}
            onOutputChange={onFixtureOutputChange}
            onExpectedInputChange={onExpectedInputChange}
            onUseSample={onUseFixtureSample}
            onClear={onClearFixture}
          />

          <OperatorTestSuiteEditor
            operator={operator}
            rows={operatorTestRows}
            results={operatorTestResults}
            publication={operatorTestPublication}
            running={operatorTestsRunning}
            runDisabledReason={operatorTestRunDisabledReason}
            onAdd={onOperatorTestAdd}
            onUpdate={onOperatorTestUpdate}
            onRemove={onOperatorTestRemove}
            onApplyFixture={onOperatorTestApplyFixture}
            onRun={onOperatorTestRun}
            onRunAll={onOperatorTestRunAll}
            onGovern={onOperatorTestGovern}
            onGovernAll={onOperatorTestGovernAll}
          />

          <SchemaPortCards title="Input schema" direction="input" ports={inputs} />
          <SchemaPortCards title="Output schema" direction="output" ports={outputs} />

          <OperatorConfigEditor config={node.data.config} onApply={onConfigReplace} />

          {node.data.summary.visualKind === 'decision-table' && (
            <DecisionTableRuleEditor
              node={node}
              incomingColumns={incomingColumns}
              onClose={onClose}
              onChange={onDecisionChange}
              embedded
            />
          )}

          {node.data.summary.visualKind === 'transform' && (
            <TransformAssignmentEditor
              node={node}
              onClose={onClose}
              onChange={onTransformChange}
              builtInFunctions={builtInFunctions}
              embedded
            />
          )}
        </div>
      </section>
    </div>
  );
}

function outputSchemaForCanvas(
  nodes: Node<NodeData>[],
  outputNodeId: string,
  operatorByRef: Map<string, OperatorDefinition>,
): SchemaEnvelope | undefined {
  const node = nodes.find((item) => item.id === outputNodeId);
  if (!node) {
    return undefined;
  }
  return operatorOutputSchema(operatorByRef.get(node.data.operatorRef));
}

function operatorOutputSchema(operator: OperatorDefinition | undefined): SchemaEnvelope | undefined {
  const outputs = operator?.ports?.outputs ?? [];
  if (outputs.length === 1) {
    return outputs[0].schema;
  }
  if (outputs.length > 1) {
    return schemaEnvelope({
      type: 'object',
      properties: Object.fromEntries(outputs.map((port) => [
        port.name || 'output',
        port.schema?.schema ?? {},
      ])),
      required: outputs.filter((port) => port.required).map((port) => port.name || 'output'),
      additionalProperties: false,
    });
  }
  return undefined;
}

function operatorInputSchema(operator: OperatorDefinition | undefined): SchemaEnvelope | undefined {
  const inputs = operator?.ports?.inputs ?? [];
  if (inputs.length === 1) {
    return inputs[0].schema;
  }
  if (inputs.length > 1) {
    return schemaEnvelope({
      type: 'object',
      properties: Object.fromEntries(inputs.map((port) => [
        port.name || 'input',
        port.schema?.schema ?? {},
      ])),
      required: inputs.filter((port) => port.required).map((port) => port.name || 'input'),
      additionalProperties: false,
    });
  }
  return undefined;
}

interface OperatorLibraryExample {
  key: string;
  label: string;
  description: string;
  sourceText: string;
}

const OPERATOR_LIBRARY_EXAMPLES: OperatorLibraryExample[] = [
  {
    key: 'risk-policy',
    label: 'Risk policy',
    description: 'Eligibility + policy decision',
    sourceText: JSON.stringify({
      schemaVersion: 'bloge.visualOperatorLibrary.v1',
      libraryId: 'risk-policy-starter',
      displayName: 'Risk Policy Starter',
      version: '1.0.0',
      owner: 'risk-team',
      status: 'ACTIVE',
      operators: [
        {
          schemaVersion: 'bloge.visualOperator.v1',
          operatorRef: 'risk:eligibility',
          operatorVersion: '1.0.0',
          display: {
            name: 'Eligibility Gate',
            description: 'Checks applicant facts and emits an eligibility decision.',
            tags: ['risk', 'decision', 'policy'],
          },
          source: { kind: 'user-library' },
          ports: {
            inputs: [
              {
                name: 'applicant',
                required: true,
                description: 'Applicant risk facts collected upstream.',
                schema: {
                  format: 'json-schema',
                  version: '2020-12',
                  schema: {
                    type: 'object',
                    properties: {
                      score: { type: 'integer', minimum: 300, maximum: 850 },
                      requestedAmount: { type: 'number', minimum: 0 },
                      segment: { type: 'string' },
                    },
                    required: ['score', 'requestedAmount'],
                    additionalProperties: false,
                  },
                },
              },
            ],
            outputs: [
              {
                name: 'decision',
                description: 'Policy decision that can feed routing or response shaping.',
                schema: {
                  format: 'json-schema',
                  version: '2020-12',
                  schema: {
                    type: 'object',
                    properties: {
                      eligible: { type: 'boolean' },
                      tier: { type: 'string', enum: ['prime', 'standard', 'review', 'decline'] },
                      reason: { type: 'string' },
                    },
                    required: ['eligible', 'tier'],
                    additionalProperties: false,
                  },
                },
              },
            ],
          },
          capabilities: {
            effect: 'PURE',
            idempotency: 'DETERMINISTIC',
            streaming: false,
            durable: false,
            requiresSecrets: false,
          },
          lowering: { mode: 'design', operatorRef: '' },
        },
      ],
    }, null, 2),
  },
  {
    key: 'order-fulfillment',
    label: 'Order flow',
    description: 'Normalize order + route SLA',
    sourceText: JSON.stringify({
      schemaVersion: 'bloge.visualOperatorLibrary.v1',
      libraryId: 'order-fulfillment-starter',
      displayName: 'Order Fulfillment Starter',
      version: '1.0.0',
      owner: 'commerce-platform',
      status: 'ACTIVE',
      operators: [
        {
          schemaVersion: 'bloge.visualOperator.v1',
          operatorRef: 'orders:normalize',
          operatorVersion: '1.0.0',
          display: {
            name: 'Normalize Order',
            description: 'Projects raw checkout payloads into a stable order contract.',
            tags: ['order', 'transform'],
          },
          source: { kind: 'user-library' },
          ports: {
            inputs: [
              {
                name: 'checkout',
                required: true,
                schema: {
                  format: 'json-schema',
                  version: '2020-12',
                  schema: {
                    type: 'object',
                    properties: {
                      orderId: { type: 'string' },
                      total: { type: 'number' },
                      region: { type: 'string' },
                      items: {
                        type: 'array',
                        items: { type: 'object', additionalProperties: true },
                      },
                    },
                    required: ['orderId', 'total', 'items'],
                    additionalProperties: true,
                  },
                },
              },
            ],
            outputs: [
              {
                name: 'order',
                schema: {
                  format: 'json-schema',
                  version: '2020-12',
                  schema: {
                    type: 'object',
                    properties: {
                      orderId: { type: 'string' },
                      total: { type: 'number' },
                      region: { type: 'string' },
                      itemCount: { type: 'integer' },
                    },
                    required: ['orderId', 'total', 'itemCount'],
                    additionalProperties: false,
                  },
                },
              },
            ],
          },
          lowering: { mode: 'design', operatorRef: '' },
        },
        {
          schemaVersion: 'bloge.visualOperator.v1',
          operatorRef: 'orders:route-sla',
          operatorVersion: '1.0.0',
          display: {
            name: 'Route SLA',
            description: 'Chooses the fulfillment lane from normalized order facts.',
            tags: ['order', 'routing', 'sla'],
          },
          source: { kind: 'user-library' },
          ports: {
            inputs: [
              {
                name: 'order',
                required: true,
                schema: {
                  format: 'json-schema',
                  version: '2020-12',
                  schema: {
                    type: 'object',
                    properties: {
                      orderId: { type: 'string' },
                      total: { type: 'number' },
                      region: { type: 'string' },
                      itemCount: { type: 'integer' },
                    },
                    required: ['orderId', 'total', 'itemCount'],
                    additionalProperties: false,
                  },
                },
              },
            ],
            outputs: [
              {
                name: 'route',
                schema: {
                  format: 'json-schema',
                  version: '2020-12',
                  schema: {
                    type: 'object',
                    properties: {
                      lane: { type: 'string', enum: ['standard', 'expedite', 'manual_review'] },
                      promisedHours: { type: 'integer' },
                      reason: { type: 'string' },
                    },
                    required: ['lane', 'promisedHours'],
                    additionalProperties: false,
                  },
                },
              },
            ],
          },
          lowering: { mode: 'design', operatorRef: '' },
        },
      ],
    }, null, 2),
  },
  {
    key: 'support-triage',
    label: 'Support triage',
    description: 'Ticket signal + action plan',
    sourceText: JSON.stringify({
      schemaVersion: 'bloge.visualOperatorLibrary.v1',
      libraryId: 'support-triage-starter',
      displayName: 'Support Triage Starter',
      version: '1.0.0',
      owner: 'customer-ops',
      status: 'ACTIVE',
      operators: [
        {
          schemaVersion: 'bloge.visualOperator.v1',
          operatorRef: 'support:classify-ticket',
          operatorVersion: '1.0.0',
          display: {
            name: 'Classify Ticket',
            description: 'Turns a customer ticket into priority, topic, and next action.',
            tags: ['support', 'triage', 'decision'],
          },
          source: { kind: 'user-library' },
          ports: {
            inputs: [
              {
                name: 'ticket',
                required: true,
                schema: {
                  format: 'json-schema',
                  version: '2020-12',
                  schema: {
                    type: 'object',
                    properties: {
                      subject: { type: 'string' },
                      body: { type: 'string' },
                      customerTier: { type: 'string', enum: ['free', 'pro', 'enterprise'] },
                      openHours: { type: 'number', minimum: 0 },
                    },
                    required: ['subject', 'body', 'customerTier'],
                    additionalProperties: false,
                  },
                },
              },
            ],
            outputs: [
              {
                name: 'triage',
                schema: {
                  format: 'json-schema',
                  version: '2020-12',
                  schema: {
                    type: 'object',
                    properties: {
                      priority: { type: 'string', enum: ['p0', 'p1', 'p2', 'p3'] },
                      topic: { type: 'string' },
                      action: { type: 'string', enum: ['auto_reply', 'assign_agent', 'escalate'] },
                      confidence: { type: 'number', minimum: 0, maximum: 1 },
                    },
                    required: ['priority', 'topic', 'action'],
                    additionalProperties: false,
                  },
                },
              },
            ],
          },
          capabilities: {
            effect: 'PURE',
            idempotency: 'DETERMINISTIC',
            streaming: false,
            durable: false,
            requiresSecrets: false,
          },
          lowering: { mode: 'design', operatorRef: '' },
        },
      ],
    }, null, 2),
  },
  {
    key: 'capability-catalog',
    label: 'Capability catalog',
    description: 'Framework export adapter',
    sourceText: JSON.stringify({
      schemaVersion: 'bloge.capabilityCatalog.v1',
      catalogId: 'risk-capabilities',
      displayName: 'Risk Capabilities',
      blogeVersion: '1.2.3',
      generatedAt: '2026-07-07T09:00:00Z',
      operators: [
        {
          operatorRef: 'risk:eligibility',
          operatorVersion: '1.0.0',
          display: {
            name: 'Eligibility',
            description: 'Existing BLOGE business operator exported from code.',
            tags: ['risk', 'decision', 'legacy'],
          },
          implementation: {
            kind: 'java-operator',
            className: 'com.acme.risk.EligibilityOperator',
            inputType: 'com.acme.risk.Applicant',
            outputType: 'com.acme.risk.Decision',
          },
          ports: {
            inputs: [
              {
                name: 'applicant',
                required: true,
                schema: {
                  format: 'json-schema',
                  version: '2020-12',
                  schema: {
                    type: 'object',
                    properties: {
                      score: { type: 'integer', minimum: 300, maximum: 850 },
                      requestedAmount: { type: 'number', minimum: 0 },
                    },
                    required: ['score', 'requestedAmount'],
                    additionalProperties: false,
                  },
                },
              },
            ],
            outputs: [
              {
                name: 'decision',
                schema: {
                  format: 'json-schema',
                  version: '2020-12',
                  schema: {
                    type: 'object',
                    properties: {
                      eligible: { type: 'boolean' },
                      reason: { type: 'string' },
                    },
                    required: ['eligible'],
                    additionalProperties: false,
                  },
                },
              },
            ],
          },
          capabilities: {
            idempotency: 'IDEMPOTENT',
            sideEffectType: 'READ_ONLY',
            deterministic: true,
          },
        },
      ],
      functions: [
        {
          name: 'normalizeScore',
          namespace: 'risk',
          displayName: 'Normalize score',
          description: 'Existing expression helper exported from business code.',
          category: 'risk',
          signatures: [
            {
              label: 'normalizeScore(score)',
              parameters: [{ name: 'score', type: 'Integer' }],
              returns: { type: 'Boolean' },
            },
          ],
          examples: ['risk.normalizeScore(inputs.score)'],
        },
      ],
    }, null, 2),
  },
];

interface LegacyDslExample {
  key: string;
  label: string;
  sourceId: string;
  sourceText: string;
}

interface DslSourceMapRow {
  key: string;
  kind: 'node' | 'edge' | 'binding';
  targetNodeId?: string;
  label: string;
  location: string;
  dslKind: string;
  snippet: string;
}

const LEGACY_DSL_EXAMPLES: LegacyDslExample[] = [
  {
    key: 'migrated-eligibility',
    label: 'Eligibility DSL',
    sourceId: 'migrated-eligibility.bloge',
    sourceText: [
      'graph migratedEligibility {',
      '  input {',
      '    score: Int',
      '    amount: Decimal',
      '  }',
      '  output {',
      '    eligible: Boolean',
      '    ruleId: String',
      '  }',
      '  node eligibility : "risk:eligibility" {',
      '    input {',
      '      score = ctx.score',
      '      amount = ctx.amount',
      '    }',
      '  }',
      '  transform response {',
      '    eligible = eligibility.output.eligible',
      '    ruleId = eligibility.output.ruleId',
      '  }',
      '}',
    ].join('\n'),
  },
];

function placeholderOperatorDefinition(operatorRef: string): OperatorDefinition {
  return {
    operatorRef,
    display: {
      name: operatorRef,
      description: 'Schema is missing from the current operator catalog.',
      tags: ['missing-schema'],
    },
    source: { kind: 'missing-schema' },
    lowering: { mode: 'design' },
    ports: {
      inputs: [{ name: 'inputs', schema: schemaEnvelope({ type: 'object', additionalProperties: true }) }],
      outputs: [{ name: 'output', schema: schemaEnvelope({ type: 'object', additionalProperties: true }) }],
    },
    runtimeReadiness: {
      state: 'missing-schema',
      level: 'warning',
      executable: false,
      title: 'Missing schema',
      summary: 'Import rendered this node from DSL, but no operator schema is loaded yet.',
    },
  };
}

function operatorLibraryIds(operators: OperatorDefinition[]): string[] {
  return Array.from(new Set(
    operators
      .map((operator) => operator.source?.libraryId ?? '')
      .filter(Boolean),
  ));
}

function inlineLibrariesFromSourceText(sourceText: string): OperatorLibrary[] {
  const trimmed = sourceText.trim();
  if (!trimmed || !trimmed.startsWith('{')) {
    return [];
  }
  try {
    const parsed = JSON.parse(trimmed) as unknown;
    if (isRecord(parsed) && Array.isArray(parsed.operators)) {
      return [parsed as unknown as OperatorLibrary];
    }
  } catch {
    return [];
  }
  return [];
}

function projectionDiagnosticsLevel(diagnostics: VisualDiagnostic[]): ConnectionNotice['level'] {
  if (diagnostics.some((diagnostic) => diagnostic.level === 'ERROR')) {
    return 'error';
  }
  if (diagnostics.length > 0) {
    return 'warning';
  }
  return 'ok';
}

function dslImportMetadata(projection: DslVisualProjection): Record<string, unknown> {
  const visualLayout = projection.draft.visualLayout;
  return isRecord(visualLayout) && isRecord(visualLayout.import)
    ? visualLayout.import
    : {};
}

function dslProjectionNotice(projection: DslVisualProjection): ConnectionNotice {
  const diagnostics = projection.diagnostics ?? [];
  const coverage = projection.coverage;
  const roundTrip = projection.roundTrip;
  const importMetadata = dslImportMetadata(projection);
  const nodeCount = projection.draft.nodes?.length ?? coverage?.projectedNodeCount ?? 0;
  const edgeCount = projection.draft.edges?.length ?? coverage?.edgeCount ?? 0;
  const missingOperatorCount = coverage?.missingOperatorCount ?? 0;
  const missingFunctionCount = coverage?.missingFunctionCount ?? 0;
  const projectionMode = typeof importMetadata.projectionMode === 'string'
    ? importMetadata.projectionMode
    : '';
  const repairHints = [
    projectionMode === 'topology-only' ? 'topology-only projection' : '',
    missingOperatorCount > 0 ? `${missingOperatorCount} missing operator schema` : '',
    missingFunctionCount > 0 ? `${missingFunctionCount} missing function schema` : '',
    roundTrip && roundTrip.status && roundTrip.status !== 'NOT_ASSESSED' ? `round-trip ${roundTrip.status}` : '',
  ].filter(Boolean);
  return {
    level: projectionDiagnosticsLevel(diagnostics),
    message: `Rendered ${nodeCount} nodes / ${edgeCount} edges${repairHints.length > 0 ? `; ${repairHints.join(', ')}` : ''}.`,
  };
}

function dslRoundTripNoticeLevel(roundTrip: DslRoundTripSummary): ConnectionNotice['level'] {
  if (roundTrip.supported) {
    return 'ok';
  }
  if (roundTrip.status === 'DRIFT') {
    return 'warning';
  }
  return 'pending';
}

function dslRewriteGateNoticeLevel(result: DslRewriteGateResult): ConnectionNotice['level'] {
  if (result.allowed) {
    return 'ok';
  }
  if (result.decision === 'BLOCK_IMPORT_DIAGNOSTICS') {
    return 'error';
  }
  return 'warning';
}

function dslRewriteGateNotice(result: DslRewriteGateResult): ConnectionNotice {
  return {
    level: dslRewriteGateNoticeLevel(result),
    message: result.message || (result.allowed
      ? 'Rewrite gate passed.'
      : 'Rewrite gate blocked automatic source replacement.'),
  };
}

function edgeLabelFromCanvasEdge(edge: CanvasEdge): string {
  if (edge.condition) {
    return edge.condition;
  }
  if (edge.kind && edge.kind !== 'data') {
    return edge.kind;
  }
  return `${endpointLabel(edge.sourcePort ?? '', edge.sourcePath ?? '', 'value')} -> ${
    endpointLabel(edge.targetPort ?? '', edge.targetPath ?? '', 'input')
  }`;
}

function importedContextVariables(inputSchema: SchemaEnvelope | undefined): ContextVariableRow[] {
  const fields = schemaFieldRows(inputSchema);
  const sample = sampleFromSchemaEnvelope(inputSchema);
  const sampleObject = isRecord(sample) ? sample : {};
  return fields.map((field, index) => {
    const value = sampleObject[field.name];
    return {
      id: `ctx-${index + 1}`,
      path: field.name,
      valueType: contextVariableType(value),
      sample: contextVariableSampleText(value),
    };
  });
}

function contextVariableType(value: unknown): ContextVariableType {
  if (typeof value === 'number') {
    return 'number';
  }
  if (typeof value === 'boolean') {
    return 'boolean';
  }
  if (isRecord(value) || Array.isArray(value)) {
    return 'json';
  }
  return 'string';
}

function contextVariableSampleText(value: unknown): string {
  if (typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  if (typeof value === 'string') {
    return value;
  }
  if (value === undefined) {
    return '';
  }
  return JSON.stringify(value, null, 2);
}

function visualLayoutWithGraphContract(
  visualLayout: Record<string, unknown>,
  inputSchema: SchemaEnvelope,
  outputSchema: SchemaEnvelope | null | undefined,
  schemaSource: string,
): Record<string, unknown> {
  const graphContract = isRecord(visualLayout.graphContract)
    ? { ...visualLayout.graphContract }
    : {};
  return {
    ...visualLayout,
    graphContract: {
      ...graphContract,
      inputSchema,
      ...(outputSchema ? { outputSchema } : {}),
      schemaSource: String(graphContract.schemaSource ?? schemaSource),
    },
  };
}

function visualLayoutWithImportSourceMap(
  visualLayout: Record<string, unknown>,
  sourceMap: DslSourceMap | undefined,
): Record<string, unknown> {
  if (!sourceMap || dslSourceMapEntryCount(sourceMap) === 0) {
    return visualLayout;
  }
  const importMetadata = isRecord(visualLayout.import) ? { ...visualLayout.import } : {};
  return {
    ...visualLayout,
    import: {
      ...importMetadata,
      sourceMap,
    },
  };
}

function dslSourceMapEntryCount(sourceMap: DslSourceMap | null | undefined): number {
  if (!sourceMap) {
    return 0;
  }
  return Object.keys(sourceMap.nodes ?? {}).length
    + Object.keys(sourceMap.edges ?? {}).length
    + Object.keys(sourceMap.bindings ?? {}).length;
}

function dslSourceMapFromImportResult(
  result: GraphDraftImportResult,
  fallback: DslSourceMap | null,
): DslSourceMap | undefined {
  const rawImportMetadata = result.draft?.visualLayout?.import;
  const importMetadata = isRecord(rawImportMetadata) ? rawImportMetadata : null;
  const sourceMap = importMetadata && isRecord(importMetadata.sourceMap)
    ? importMetadata.sourceMap as DslSourceMap
    : null;
  return sourceMap ?? fallback ?? undefined;
}

function dslCommitNotice(result: GraphDraftImportResult): ConnectionNotice {
  const draft = result.draft;
  if (!result.imported || !draft) {
    return { level: 'warning', message: 'DSL rendered but no draft was stored.' };
  }
  const diagnosticCount = result.validation?.diagnostics?.length ?? result.diagnostics?.length ?? 0;
  const level = diagnosticCount > 0 ? 'warning' : 'ok';
  const revision = draft.revision ? ` @${draft.revision}` : '';
  return {
    level,
    message: `Stored draft ${draft.draftId || draft.graphName}${revision}.`,
  };
}

function dslSourceMapRows(
  sourceMap: DslSourceMap | null,
  sourceText: string,
  edgeTargetsById: Record<string, string>,
): DslSourceMapRow[] {
  if (!sourceMap) {
    return [];
  }
  const rows: DslSourceMapRow[] = [];
  Object.entries(sourceMap.nodes ?? {}).forEach(([nodeId, span]) => {
    rows.push(dslSourceMapRow('node', nodeId, span, sourceText, nodeId));
  });
  Object.entries(sourceMap.edges ?? {}).forEach(([edgeId, span]) => {
    rows.push(dslSourceMapRow('edge', edgeId, span, sourceText, edgeTargetsById[edgeId]));
  });
  Object.entries(sourceMap.bindings ?? {}).forEach(([pointer, span]) => {
    rows.push(dslSourceMapRow('binding', pointer, span, sourceText, nodeIdFromJsonPointer(pointer)));
  });
  const kindOrder = { node: 0, edge: 1, binding: 2 };
  return rows.sort((left, right) => (
    sourceMapLineSortValue(left.location) - sourceMapLineSortValue(right.location)
    || kindOrder[left.kind] - kindOrder[right.kind]
    || left.label.localeCompare(right.label)
  ));
}

function sourceMapLineSortValue(location: string): number {
  const line = Number(location.split(':')[0]);
  return Number.isFinite(line) ? line : Number.MAX_SAFE_INTEGER;
}

function dslSourceMapRow(
  kind: DslSourceMapRow['kind'],
  id: string,
  span: DslSourceSpan,
  sourceText: string,
  targetNodeId?: string,
): DslSourceMapRow {
  return {
    key: `${kind}:${id}`,
    kind,
    targetNodeId,
    label: dslSourceMapLabel(kind, id),
    location: dslSourceLocation(span),
    dslKind: span.dslKind || kind,
    snippet: dslSourceLine(sourceText, span.startLine),
  };
}

function dslSourceMapLabel(kind: DslSourceMapRow['kind'], id: string): string {
  if (kind === 'binding') {
    const parts = id.split('/').filter(Boolean).map(decodeJsonPointerSegment);
    const nodeId = parts[1] ?? '';
    const bindingKey = parts[3] ?? parts[parts.length - 1] ?? id;
    return nodeId ? `${nodeId}.${bindingKey}` : bindingKey;
  }
  return id;
}

function dslSourceLocation(span: DslSourceSpan): string {
  const line = Number.isFinite(span.startLine ?? NaN) && (span.startLine ?? -1) > 0
    ? span.startLine
    : '?';
  const column = Number.isFinite(span.startColumn ?? NaN) && (span.startColumn ?? -1) > 0
    ? span.startColumn
    : '?';
  return `${line}:${column}`;
}

function dslSourceLine(sourceText: string, line: number | undefined): string {
  if (!line || line < 1) {
    return '';
  }
  return sourceText.split(/\r?\n/)[line - 1]?.trim() ?? '';
}

function nodeIdFromJsonPointer(pointer: string): string | undefined {
  const [, root, rawNodeId] = pointer.split('/');
  return root === 'nodes' && rawNodeId ? decodeJsonPointerSegment(rawNodeId) : undefined;
}

function decodeJsonPointerSegment(segment: string): string {
  return segment.replace(/~1/g, '/').replace(/~0/g, '~');
}

function pickRecordByKeys<TValue>(
  source: Record<string, TValue>,
  keys: Iterable<string>,
): Record<string, TValue> {
  const selected = new Set(keys);
  return Object.fromEntries(
    Object.entries(source).filter(([key]) => selected.has(key)),
  );
}

function maxCanvasNodeSequence(nodes: CanvasNode[]): number {
  return nodes.reduce((max, node, index) => {
    const match = node.id.match(/^n(\d+)$/);
    return Math.max(max, match ? Number(match[1]) : index + 1);
  }, 0);
}

/**
 * The authoring workspace: an operator palette, a React Flow canvas, and a result inspector wired to
 * the mock-run (simulate) endpoint. Non-trivial graph<->request logic lives in the pure, unit-tested
 * {@link ./draftModel} module; this component is thin glue.
 */
export default function AuthorCanvas() {
  const [operators, setOperators] = useState<OperatorDefinition[]>([]);
  const [builtInFunctions, setBuiltInFunctions] = useState<BuiltInFunctionDefinition[]>([]);
  const [nodes, setNodes] = useState<Node<NodeData>[]>([]);
  const [edges, setEdges] = useState<Edge<CanvasEdgeData>[]>([]);
  const [result, setResult] = useState<SimulationResponse | null>(null);
  const [validationResult, setValidationResult] = useState<VisualValidationResult | null>(null);
  const [error, setError] = useState<string>('');
  const [busy, setBusy] = useState(false);
  const [validatingDraft, setValidatingDraft] = useState(false);
  const [checkingConnection, setCheckingConnection] = useState(false);
  const [loadingCandidates, setLoadingCandidates] = useState(false);
  const [libraryBusy, setLibraryBusy] = useState(false);
  const [librarySourceText, setLibrarySourceText] = useState('');
  const [libraryNotice, setLibraryNotice] = useState<ConnectionNotice | null>(null);
  const [libraryDiagnostics, setLibraryDiagnostics] = useState<VisualDiagnostic[]>([]);
  const [libraryWarningsAcknowledged, setLibraryWarningsAcknowledged] = useState(false);
  const [libraryWarningReason, setLibraryWarningReason] = useState('');
  const [dslSourceId, setDslSourceId] = useState(LEGACY_DSL_EXAMPLES[0].sourceId);
  const [dslSourceText, setDslSourceText] = useState(LEGACY_DSL_EXAMPLES[0].sourceText);
  const [dslImportBusy, setDslImportBusy] = useState(false);
  const [dslCommitBusy, setDslCommitBusy] = useState(false);
  const [dslRewriteGateBusy, setDslRewriteGateBusy] = useState(false);
  const [dslRewriteGateResult, setDslRewriteGateResult] = useState<DslRewriteGateResult | null>(null);
  const [dslImportNotice, setDslImportNotice] = useState<ConnectionNotice | null>(null);
  const [dslImportDiagnostics, setDslImportDiagnostics] = useState<VisualDiagnostic[]>([]);
  const [dslImportCoverage, setDslImportCoverage] = useState<DslImportCoverage | null>(null);
  const [dslImportSourceMap, setDslImportSourceMap] = useState<DslSourceMap | null>(null);
  const [dslImportRoundTrip, setDslImportRoundTrip] = useState<DslRoundTripSummary | null>(null);
  const [search, setSearch] = useState('');
  const [paletteFacet, setPaletteFacet] = useState<OperatorPaletteFacet>('all');
  const [sourceFilter, setSourceFilter] = useState('all');
  const [tagFilter, setTagFilter] = useState('all');
  const [selectedNodeId, setSelectedNodeId] = useState('');
  const [operatorDetailNodeId, setOperatorDetailNodeId] = useState('');
  const [testSuiteOpen, setTestSuiteOpen] = useState(false);
  const [contractWorkspaceOpen, setContractWorkspaceOpen] = useState(false);
  const [contractDraft, setContractDraft] = useState<ContractDraft | null>(null);
  const [contractFingerprint, setContractFingerprint] = useState('');
  const [scenarioDraftSet, setScenarioDraftSet] = useState<ScenarioDraftSet | null>(null);
  const [explicitOutputNodeId, setExplicitOutputNodeId] = useState('');
  const [fixtureDrafts, setFixtureDrafts] = useState<Record<string, string>>({});
  const [fixtureInputDrafts, setFixtureInputDrafts] = useState<Record<string, string>>({});
  const [operatorTestSuites, setOperatorTestSuites] = useState<Record<string, OperatorTestSuiteDraftRow[]>>({});
  const [operatorTestResults, setOperatorTestResults] = useState<Record<string, Record<string, OperatorTestCaseResult>>>({});
  const [operatorTestPublications, setOperatorTestPublications] = useState<
    Record<string, OperatorTestSuitePublicationResult>
  >({});
  const [simulationTableRows, setSimulationTableRows] = useState<SimulationTableTestDraftRow[]>([]);
  const [simulationTableResults, setSimulationTableResults] = useState<Record<string, SimulationTableCaseResult>>({});
  const [tableTestingBusy, setTableTestingBusy] = useState(false);
  const [simulationContextDraft, setSimulationContextDraft] = useState('{}');
  const [contextVariables, setContextVariables] = useState<ContextVariableRow[]>([]);
  const [graphName, setGraphName] = useState('visualGraph');
  const [graphDraftId, setGraphDraftId] = useState('');
  const [graphDraftRevision, setGraphDraftRevision] = useState(0);
  const [graphTenantId, setGraphTenantId] = useState('tenant-a');
  const [graphNamespace, setGraphNamespace] = useState('local');
  const [graphEnvironment, setGraphEnvironment] = useState('test');
  const [graphInputSchema, setGraphInputSchema] = useState<SchemaEnvelope>(EMPTY_GRAPH_INPUT_SCHEMA);
  const [graphOutputSchema, setGraphOutputSchema] = useState<SchemaEnvelope | null>(null);
  const [graphContractSource, setGraphContractSource] = useState('Current draft');
  const [graphVisualLayout, setGraphVisualLayout] = useState<Record<string, unknown>>({});
  const [graphOperatorFingerprints, setGraphOperatorFingerprints] = useState<Record<string, string>>({});
  const [graphOperatorSnapshots, setGraphOperatorSnapshots] = useState<Record<string, OperatorDefinition>>({});
  const [governanceGateView, setGovernanceGateView] = useState<GovernanceGateView | null>(null);
  const [governanceGateBusy, setGovernanceGateBusy] = useState(false);
  const [deepLinkRun, setDeepLinkRun] = useState<VisualGraphRunRecord | null>(null);
  const [deepLinkNotice, setDeepLinkNotice] = useState<ConnectionNotice | null>(null);
  const [connectionNotice, setConnectionNotice] = useState<ConnectionNotice | null>(null);
  const [candidatePreview, setCandidatePreview] = useState<ConnectionCandidateIndex | null>(null);
  const [selectedConnectionSourcePort, setSelectedConnectionSourcePort] = useState('');
  const [connectionGuide, setConnectionGuide] = useState<SelectedConnectionGuide | null>(null);
  const [connectionGuideNotice, setConnectionGuideNotice] = useState<ConnectionNotice | null>(null);
  const [connectionGuideBusy, setConnectionGuideBusy] = useState(false);
  const [pendingConnectionGuideNodeId, setPendingConnectionGuideNodeId] = useState('');
  const [canvasFocusMode, setCanvasFocusMode] = useState(false);
  const [overviewVisible, setOverviewVisible] = useState(true);
  const [viewportZoom, setViewportZoom] = useState(1);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const flowRef = useRef<HTMLDivElement>(null);
  const flowInstanceRef = useRef<ReactFlowInstance<NodeData, CanvasEdgeData> | null>(null);
  const counter = useRef(0);
  const contextVariableCounter = useRef(0);
  const tableTestCounter = useRef(0);
  const operatorTestCounter = useRef(0);
  const candidatePreviewSequence = useRef(0);
  const connectionGuideSequence = useRef(0);
  const contractProjectionSequence = useRef(0);
  const scenarioGraphNameRef = useRef('');
  const authoritativeContractRef = useRef<{
    canvasSnapshot: string;
    contract: ContractDraft;
    contractFingerprint: string;
  } | null>(null);

  const reloadOperators = useCallback(async () => {
    const catalog = await fetchOperatorCatalog();
    setOperators(catalog.operators);
    setBuiltInFunctions(catalog.builtInFunctions ?? []);
  }, []);

  useEffect(() => {
    reloadOperators()
      .catch((cause: unknown) => setError(String(cause)));
  }, [reloadOperators]);

  useEffect(() => {
    if (!graphDraftId) {
      setGovernanceGateView(null);
      setGovernanceGateBusy(false);
      return undefined;
    }
    let active = true;
    setGovernanceGateBusy(true);
    fetchGovernanceGateView(graphDraftId)
      .then((view) => {
        if (active) {
          setGovernanceGateView(view);
        }
      })
      .catch((cause: unknown) => {
        if (active) {
          setGovernanceGateView(null);
          setDeepLinkNotice({
            level: 'warning',
            message: `Governance feedback unavailable: ${String(cause)}`,
          });
        }
      })
      .finally(() => {
        if (active) {
          setGovernanceGateBusy(false);
        }
      });
    return () => {
      active = false;
    };
  }, [graphDraftId]);

  const isComplexGraph = nodes.length >= COMPLEX_GRAPH_NODE_THRESHOLD
    || edges.length >= COMPLEX_GRAPH_EDGE_THRESHOLD;

  const refreshViewportZoom = useCallback(() => {
    const nextZoom = flowInstanceRef.current?.getZoom?.();
    if (typeof nextZoom === 'number' && Number.isFinite(nextZoom)) {
      setViewportZoom(nextZoom);
    }
  }, []);

  const fitCanvasToView = useCallback((graphSize?: { nodeCount: number; edgeCount: number }) => {
    const complex = graphSize
      ? graphSize.nodeCount >= COMPLEX_GRAPH_NODE_THRESHOLD
        || graphSize.edgeCount >= COMPLEX_GRAPH_EDGE_THRESHOLD
      : isComplexGraph;
    flowInstanceRef.current?.fitView({ padding: complex ? 0.06 : 0.18, duration: 240 });
    const updateZoom = () => refreshViewportZoom();
    if (typeof window.requestAnimationFrame === 'function') {
      window.requestAnimationFrame(updateZoom);
    } else {
      window.setTimeout(updateZoom, 0);
    }
  }, [isComplexGraph, refreshViewportZoom]);

  const zoomCanvasBy = useCallback((direction: 'in' | 'out') => {
    if (direction === 'in') {
      void flowInstanceRef.current?.zoomIn?.({ duration: 160 });
    } else {
      void flowInstanceRef.current?.zoomOut?.({ duration: 160 });
    }
    const updateZoom = () => refreshViewportZoom();
    if (typeof window.requestAnimationFrame === 'function') {
      window.requestAnimationFrame(updateZoom);
    } else {
      window.setTimeout(updateZoom, 0);
    }
  }, [refreshViewportZoom]);

  const resetCanvasZoom = useCallback(() => {
    void flowInstanceRef.current?.zoomTo?.(1, { duration: 180 });
    const updateZoom = () => refreshViewportZoom();
    if (typeof window.requestAnimationFrame === 'function') {
      window.requestAnimationFrame(updateZoom);
    } else {
      window.setTimeout(updateZoom, 0);
    }
  }, [refreshViewportZoom]);

  useEffect(() => {
    if (!canvasFocusMode || !flowInstanceRef.current) {
      return undefined;
    }
    const handle = window.setTimeout(() => {
      fitCanvasToView();
    }, 80);
    return () => window.clearTimeout(handle);
  }, [canvasFocusMode, edges.length, fitCanvasToView, nodes.length]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        searchInputRef.current?.focus();
        searchInputRef.current?.select();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

  const clearRunResult = useCallback(() => {
    setResult(null);
    setValidationResult(null);
    setSimulationTableResults({});
    setError('');
  }, []);

  const onNodesChange = useCallback(
    (changes: NodeChange[]) => {
      const removedNodeIds: string[] = [];
      for (const change of changes) {
        if (change.type === 'remove') {
          removedNodeIds.push(change.id);
        }
      }
      if (changes.some((change) => change.type === 'remove' || change.type === 'add')) {
        clearRunResult();
        setConnectionGuide(null);
      }
      if (removedNodeIds.length > 0) {
        setFixtureDrafts((current) => {
          const next = { ...current };
          for (const id of removedNodeIds) {
            delete next[id];
          }
          return next;
        });
        setFixtureInputDrafts((current) => {
          const next = { ...current };
          for (const id of removedNodeIds) {
            delete next[id];
          }
          return next;
        });
        setOperatorTestSuites((current) => {
          const next = { ...current };
          for (const id of removedNodeIds) {
            delete next[id];
          }
          return next;
        });
        setOperatorTestResults((current) => {
          const next = { ...current };
          for (const id of removedNodeIds) {
            delete next[id];
          }
          return next;
        });
        setOperatorTestPublications((current) => {
          const next = { ...current };
          for (const id of removedNodeIds) {
            delete next[id];
          }
          return next;
        });
        setSelectedNodeId((current) => (removedNodeIds.includes(current) ? '' : current));
        setOperatorDetailNodeId((current) => (removedNodeIds.includes(current) ? '' : current));
        setExplicitOutputNodeId((current) => (removedNodeIds.includes(current) ? '' : current));
      }
      setNodes((current) => applyNodeChanges(changes, current) as Node<NodeData>[]);
    },
    [clearRunResult],
  );
  const onEdgesChange = useCallback(
    (changes: EdgeChange[]) => {
      if (changes.some((change) => change.type === 'remove' || change.type === 'add')) {
        clearRunResult();
        setConnectionGuide(null);
      }
      setEdges((current) => applyEdgeChanges(changes, current) as Edge<CanvasEdgeData>[]);
    },
    [clearRunResult],
  );
  const addOperator = useCallback((operator: OperatorDefinition, position?: { x: number; y: number }) => {
    clearRunResult();
    setConnectionGuide(null);
    const nextIndex = counter.current + 1;
    counter.current = nextIndex;
    const id = `n${nextIndex}`;
    const placementIndex = nextIndex - 1;
    const canvasWidth = flowRef.current?.clientWidth ?? 0;
    const summary = summarizeOperator(operator);
    const node: Node<NodeData> = {
      id,
      type: 'operator',
      position: position ?? defaultOperatorPosition(placementIndex, canvasWidth),
      data: { label: summary.name, operatorRef: operator.operatorRef, summary },
    };
    setNodes((current) => [...current, node]);
    setOperatorTestSuites((current) => ({
      ...current,
      [id]: defaultOperatorTestSuiteRows(node, operator),
    }));
    setSelectedNodeId(id);
  }, [clearRunResult]);

  const openNodeEditor = useCallback((node: Node<NodeData>) => {
    setSelectedNodeId(node.id);
    setOperatorDetailNodeId(node.id);
  }, []);

  const updateDecisionTableRules = useCallback((nodeId: string, editor: DecisionTableEditorModel) => {
    clearRunResult();
    setNodes((current) => current.map((node) => (
      node.id === nodeId
        ? {
            ...node,
            data: {
              ...node.data,
              config: decisionTableConfigFromEditor(node.data.config, editor),
            },
          }
        : node
    )));
  }, [clearRunResult]);

  const updateTransformAssignments = useCallback((nodeId: string, editor: TransformEditorModel) => {
    clearRunResult();
    setNodes((current) => current.map((node) => (
      node.id === nodeId
        ? {
            ...node,
            data: {
              ...node.data,
              config: transformConfigFromEditor(node.data.config, editor),
            },
          }
        : node
    )));
  }, [clearRunResult]);

  const updateNodeData = useCallback((
    nodeId: string,
    update: (data: NodeData, node: Node<NodeData>) => NodeData,
  ) => {
    clearRunResult();
    setNodes((current) => current.map((node) => (
      node.id === nodeId
        ? {
            ...node,
            data: update(node.data, node),
          }
        : node
    )));
  }, [clearRunResult]);

  const updateNodeLabel = useCallback((nodeId: string, label: string) => {
    updateNodeData(nodeId, (data) => ({ ...data, label }));
  }, [updateNodeData]);

  const mergeNodeConfigPatch = useCallback((nodeId: string, patch: Record<string, unknown>) => {
    updateNodeData(nodeId, (data) => {
      const nextConfig = { ...(data.config ?? {}) };
      Object.entries(patch).forEach(([key, value]) => {
        if (value === undefined) {
          delete nextConfig[key];
        } else {
          nextConfig[key] = value;
        }
      });
      return {
        ...data,
        config: Object.keys(nextConfig).length > 0 ? nextConfig : undefined,
      };
    });
  }, [updateNodeData]);

  const replaceNodeConfig = useCallback((nodeId: string, config: Record<string, unknown>) => {
    updateNodeData(nodeId, (data) => ({
      ...data,
      config: Object.keys(config).length > 0 ? config : undefined,
    }));
  }, [updateNodeData]);

  const updateNodeInputs = useCallback((
    nodeId: string,
    update: (inputs: Record<string, DraftNodeBinding>, node: Node<NodeData>) => Record<string, DraftNodeBinding>,
  ) => {
    clearRunResult();
    setNodes((current) => current.map((node) => {
      if (node.id !== nodeId) {
        return node;
      }
      const inputs = update(node.data.inputs ?? {}, node);
      return {
        ...node,
        data: {
          ...node.data,
          inputs: Object.keys(inputs).length > 0 ? inputs : undefined,
        },
      };
    }));
  }, [clearRunResult]);

  const addInputBindingForNode = useCallback((nodeId: string) => {
    updateNodeInputs(nodeId, (inputs, node) => {
      const targetPort = defaultInputTargetPort(node);
      const bindingKey = uniqueInputBindingKey(inputs, targetPort === 'inputs' ? 'input' : targetPort);
      return {
        ...inputs,
        [bindingKey]: {
          kind: 'contextPath',
          path: bindingKey,
          targetPort,
        },
      };
    });
  }, [updateNodeInputs]);

  const addSelectedInputBinding = useCallback(() => {
    if (!selectedNodeId) {
      return;
    }
    addInputBindingForNode(selectedNodeId);
  }, [addInputBindingForNode, selectedNodeId]);

  const renameInputBindingForNode = useCallback((nodeId: string, bindingKey: string, value: string) => {
    updateNodeInputs(nodeId, (inputs) => {
      const binding = inputs[bindingKey];
      if (!binding) {
        return inputs;
      }
      const nextKey = uniqueInputBindingKey(inputs, value, bindingKey);
      if (nextKey === bindingKey) {
        return inputs;
      }
      const next: Record<string, DraftNodeBinding> = {};
      Object.entries(inputs).forEach(([key, candidate]) => {
        next[key === bindingKey ? nextKey : key] = key === bindingKey
          ? {
              ...candidate,
              path: candidate.kind === 'contextPath' && candidate.path === bindingKey ? nextKey : candidate.path,
              targetPath: candidate.targetPath === bindingKey ? nextKey : candidate.targetPath,
            }
          : candidate;
      });
      return next;
    });
  }, [updateNodeInputs]);

  const renameSelectedInputBinding = useCallback((bindingKey: string, value: string) => {
    if (!selectedNodeId) {
      return;
    }
    renameInputBindingForNode(selectedNodeId, bindingKey, value);
  }, [renameInputBindingForNode, selectedNodeId]);

  const updateInputBindingForNode = useCallback((
    nodeId: string,
    bindingKey: string,
    patch: Partial<DraftNodeBinding>,
  ) => {
    updateNodeInputs(nodeId, (inputs) => {
      const current = inputs[bindingKey];
      if (!current) {
        return inputs;
      }
      return {
        ...inputs,
        [bindingKey]: { ...current, ...patch },
      };
    });
  }, [updateNodeInputs]);

  const updateSelectedInputBinding = useCallback((bindingKey: string, patch: Partial<DraftNodeBinding>) => {
    if (!selectedNodeId) {
      return;
    }
    updateInputBindingForNode(selectedNodeId, bindingKey, patch);
  }, [selectedNodeId, updateInputBindingForNode]);

  const updateInputBindingKindForNode = useCallback((
    nodeId: string,
    bindingKey: string,
    kind: 'contextPath' | 'constant',
  ) => {
    updateNodeInputs(nodeId, (inputs) => {
      const current = inputs[bindingKey];
      if (!current) {
        return inputs;
      }
      const nextBinding: DraftNodeBinding = { ...current, kind, path: current.path || bindingKey };
      delete nextBinding.value;
      return {
        ...inputs,
        [bindingKey]: kind === 'constant'
          ? { ...current, kind, path: '', value: current.value ?? '' }
          : nextBinding,
      };
    });
  }, [updateNodeInputs]);

  const updateSelectedInputBindingKind = useCallback((bindingKey: string, kind: 'contextPath' | 'constant') => {
    if (!selectedNodeId) {
      return;
    }
    updateInputBindingKindForNode(selectedNodeId, bindingKey, kind);
  }, [selectedNodeId, updateInputBindingKindForNode]);

  const removeInputBindingForNode = useCallback((nodeId: string, bindingKey: string) => {
    updateNodeInputs(nodeId, (inputs) => {
      const next = { ...inputs };
      delete next[bindingKey];
      return next;
    });
  }, [updateNodeInputs]);

  const removeSelectedInputBinding = useCallback((bindingKey: string) => {
    if (!selectedNodeId) {
      return;
    }
    removeInputBindingForNode(selectedNodeId, bindingKey);
  }, [removeInputBindingForNode, selectedNodeId]);

  const addContextVariable = useCallback(() => {
    clearRunResult();
    const nextIndex = contextVariableCounter.current + 1;
    contextVariableCounter.current = nextIndex;
    setContextVariables((current) => [
      ...current,
      {
        id: `ctx-${nextIndex}`,
        path: '',
        valueType: 'string',
        sample: '',
      },
    ]);
  }, [clearRunResult]);

  const updateContextVariable = useCallback((id: string, patch: Partial<ContextVariableRow>) => {
    clearRunResult();
    setContextVariables((current) => current.map((row) => (
      row.id === id ? { ...row, ...patch } : row
    )));
  }, [clearRunResult]);

  const removeContextVariable = useCallback((id: string) => {
    clearRunResult();
    setContextVariables((current) => current.filter((row) => row.id !== id));
  }, [clearRunResult]);

  const updateSimulationContextDraft = useCallback((value: string) => {
    clearRunResult();
    setSimulationContextDraft(value);
  }, [clearRunResult]);

  const bindContextPathToNode = useCallback((nodeId: string, path: string) => {
    const normalizedPath = normalizedContextPath(path);
    if (!normalizedPath) {
      return;
    }
    updateNodeInputs(nodeId, (inputs, node) => {
      const targetPort = defaultInputTargetPort(node);
      const targetPath = contextBindingKey(normalizedPath);
      const bindingKey = uniqueInputBindingKey(inputs, targetPath);
      return {
        ...inputs,
        [bindingKey]: {
          kind: 'contextPath',
          path: normalizedPath,
          targetPort,
          targetPath,
        },
      };
    });
  }, [updateNodeInputs]);

  const bindContextVariableToSelectedNode = useCallback((path: string) => {
    if (!selectedNodeId) {
      return;
    }
    bindContextPathToNode(selectedNodeId, path);
  }, [bindContextPathToNode, selectedNodeId]);

  const canvasNodes = useMemo<CanvasNode[]>(
    () => nodes.map(canvasNodeFromFlowNode),
    [nodes],
  );
  const canvasEdges = useMemo<CanvasEdge[]>(
    () => edges.map(canvasEdgeFromFlowEdge),
    [edges],
  );
  const edgeTargetsById = useMemo(
    () => Object.fromEntries(edges.map((edge) => [edge.id, edge.target])),
    [edges],
  );
  const dslImportSourceRows = useMemo(
    () => dslSourceMapRows(dslImportSourceMap, dslSourceText, edgeTargetsById),
    [dslImportSourceMap, dslSourceText, edgeTargetsById],
  );
  const implicitOutputNodeId = nodes.length > 0 ? nodes[nodes.length - 1].id : '';
  const outputNodeId = explicitOutputNodeId || implicitOutputNodeId;
  const canvasSummary = useMemo(
    () => summarizeCanvas(canvasNodes, canvasEdges, outputNodeId),
    [canvasEdges, canvasNodes, outputNodeId],
  );
  const viewportZoomPercent = `${Math.round(viewportZoom * 100)}%`;
  const overviewLabel = isComplexGraph ? 'Large Map' : 'Map';
  const checklist = useMemo(
    () => simulationChecklist(canvasSummary, result),
    [canvasSummary, result],
  );
  const traceRows = useMemo(() => simulationTraceRows(canvasNodes, result), [canvasNodes, result]);
  const operatorByRef = useMemo(
    () => {
      const next = new Map(operators.map((operator) => [operator.operatorRef, operator]));
      for (const snapshot of Object.values(graphOperatorSnapshots)) {
        if (!next.has(snapshot.operatorRef)) {
          next.set(snapshot.operatorRef, snapshot);
        }
      }
      return next;
    },
    [graphOperatorSnapshots, operators],
  );
  const effectiveGraphOutputSchema = useMemo(
    () => graphOutputSchema ?? outputSchemaForCanvas(nodes, outputNodeId, operatorByRef),
    [graphOutputSchema, nodes, operatorByRef, outputNodeId],
  );
  const graphInputSummary = useMemo(
    () => graphSchemaSummary(graphInputSchema),
    [graphInputSchema],
  );
  const graphOutputSummary = useMemo(
    () => graphSchemaSummary(effectiveGraphOutputSchema),
    [effectiveGraphOutputSchema],
  );
  const canvasExamples = useMemo<CanvasExampleAvailability[]>(
    () =>
      CANVAS_EXAMPLE_TEMPLATES.map((template) => ({
        template,
        missingOperatorRefs: exampleRequiredOperatorRefs(template)
          .filter((operatorRef) => !operatorByRef.has(operatorRef)),
      })),
    [operatorByRef],
  );
  const loadCanvasExample = useCallback((template: CanvasExampleTemplate) => {
    const missingOperatorRefs = exampleRequiredOperatorRefs(template)
      .filter((operatorRef) => !operatorByRef.has(operatorRef));
    if (missingOperatorRefs.length > 0) {
      setConnectionNotice({
        level: 'warning',
        message: `Example needs ${missingOperatorRefs.length} missing operator${missingOperatorRefs.length === 1 ? '' : 's'}.`,
      });
      return;
    }

    const nextNodes: Node<NodeData>[] = [];
    const nextOperatorTestSuites: Record<string, OperatorTestSuiteDraftRow[]> = {};
    for (const templateNode of template.nodes) {
      const operator = operatorByRef.get(templateNode.operatorRef);
      if (!operator) {
        setConnectionNotice({
          level: 'warning',
          message: `Example needs missing operator ${templateNode.operatorRef}.`,
        });
        return;
      }
      const nextNode: Node<NodeData> = {
        id: templateNode.id,
        type: 'operator',
        position: templateNode.position,
        data: {
          label: templateNode.label,
          operatorRef: templateNode.operatorRef,
          summary: summarizeOperator(operator),
          inputs: templateNode.inputs,
          config: templateNode.config,
        },
      };
      const testRows = defaultOperatorTestSuiteRows(nextNode, operator);
      if (hasOwnValue(templateNode, 'expectedInput')) {
        testRows[0] = {
          ...testRows[0],
          inputText: JSON.stringify(templateNode.expectedInput, null, 2),
        };
      }
      if (hasOwnValue(templateNode, 'fixtureOutput')) {
        testRows[0] = {
          ...testRows[0],
          outputText: JSON.stringify(templateNode.fixtureOutput, null, 2),
          transportResponseText: transportResponseTextForExpected(operator, templateNode.fixtureOutput),
        };
      }
      nextOperatorTestSuites[templateNode.id] = testRows;
      nextNodes.push(nextNode);
    }

    const nextEdges = template.edges.map((edge) => ({
      id: edge.id,
      source: edge.source,
      target: edge.target,
      sourceHandle: handleIdForPort('out', edge.sourcePort),
      targetHandle: handleIdForPort('in', edge.targetPort),
      sourcePath: edge.sourcePath,
      targetPath: edge.targetPath,
      bindingKey: edge.bindingKey,
      data: {
        sourcePath: edge.sourcePath ?? '',
        targetPath: edge.targetPath ?? '',
        bindingKey: edge.bindingKey ?? '',
      },
      label: exampleEdgeLabel(edge),
      ...EDGE_LABEL_OPTIONS,
      animated: true,
      className: 'accepted-edge',
    } as CanvasFlowEdge));
    const nextFixtureDrafts: Record<string, string> = {};
    const nextFixtureInputDrafts: Record<string, string> = {};
    for (const templateNode of template.nodes) {
      if (hasOwnValue(templateNode, 'fixtureOutput')) {
        nextFixtureDrafts[templateNode.id] = JSON.stringify(templateNode.fixtureOutput, null, 2);
      }
      if (hasOwnValue(templateNode, 'expectedInput')) {
        nextFixtureInputDrafts[templateNode.id] = JSON.stringify(templateNode.expectedInput, null, 2);
      }
    }
    const nextSimulationTableRows = simulationTableRowsFromExample(template.testCases);

    clearRunResult();
    counter.current = maxNumericNodeId(template.nodes);
    tableTestCounter.current = nextSimulationTableRows.length;
    setNodes(nextNodes);
    setEdges(nextEdges);
    setFixtureDrafts(nextFixtureDrafts);
    setFixtureInputDrafts(nextFixtureInputDrafts);
    setOperatorTestSuites(nextOperatorTestSuites);
    setOperatorTestResults({});
    setOperatorTestPublications({});
    setSimulationTableRows(nextSimulationTableRows);
    setSimulationTableResults({});
    setGraphName(template.graphName);
    setGraphDraftId('');
    setGraphDraftRevision(0);
    setGraphTenantId('tenant-a');
    setGraphNamespace('local');
    setGraphEnvironment('test');
    authoritativeContractRef.current = null;
    setGraphInputSchema(template.inputSchema);
    setGraphOutputSchema(template.outputSchema);
    setGraphContractSource(template.label);
    setGraphVisualLayout(visualLayoutWithGraphContract({}, template.inputSchema, template.outputSchema, 'example'));
    setGraphOperatorFingerprints({});
    setGraphOperatorSnapshots({});
    setDslImportDiagnostics([]);
    setDslImportCoverage(null);
    setDslImportSourceMap(null);
    setDslImportRoundTrip(null);
    setDslRewriteGateResult(null);
    setDslImportNotice(null);
    setSimulationContextDraft(JSON.stringify(sampleFromSchemaEnvelope(template.inputSchema), null, 2));
    setContextVariables([]);
    setExplicitOutputNodeId(template.outputNodeId);
    setSelectedNodeId(template.outputNodeId);
    setOperatorDetailNodeId('');
    setTestSuiteOpen(false);
    setConnectionGuide(null);
    setConnectionGuideNotice(null);
    setCandidatePreview(null);
    setSelectedConnectionSourcePort('');
    setPendingConnectionGuideNodeId('');
    setSearch('');
    setPaletteFacet('all');
    setSourceFilter('all');
    setTagFilter('all');
    setConnectionNotice({
      level: 'ok',
      message: `Loaded ${template.label}: ${template.nodes.length} nodes / ${template.edges.length} edges.`,
    });
  }, [clearRunResult, operatorByRef]);
  const selectedNode = nodes.find((node) => node.id === selectedNodeId);
  const selectedOperator = selectedNode ? operatorByRef.get(selectedNode.data.operatorRef) : undefined;
  const operatorDetailNode = nodes.find((node) => node.id === operatorDetailNodeId);
  const operatorDetailIncomingColumns = operatorDetailNode
    ? decisionTableIncomingConditionColumns(operatorDetailNode, edges, nodes)
    : [];
  const operatorDetailDefinition = operatorDetailNode
    ? operatorByRef.get(operatorDetailNode.data.operatorRef)
    : undefined;
  const selectedOutputPorts = useMemo(
    () =>
      selectedNode
        ? (selectedNode.data.summary.outputNames.length ? selectedNode.data.summary.outputNames : [''])
        : [],
    [selectedNode],
  );
  const selectedOutputPortKey = selectedOutputPorts.join('\u0000');
  const fixtureCompilation = useMemo(
    () => compileFixtureDrafts(fixtureDrafts, fixtureInputDrafts),
    [fixtureDrafts, fixtureInputDrafts],
  );
  const simulationTableCompilation = useMemo(
    () => compileSimulationTableRows(simulationTableRows),
    [simulationTableRows],
  );
  const simulationTableRunSummary = useMemo(
    () => simulationTableSummary(simulationTableRows, simulationTableResults, tableTestingBusy),
    [simulationTableResults, simulationTableRows, tableTestingBusy],
  );
  const rawContextCompilation = useMemo(
    () => compileJsonObjectDraft(simulationContextDraft, 'Runtime context'),
    [simulationContextDraft],
  );
  const variableContextCompilation = useMemo(
    () => compileContextVariables(contextVariables),
    [contextVariables],
  );
  const hasContextVariables = contextVariables.some((row) => row.path.trim());
  const contextCompilation = hasContextVariables ? variableContextCompilation : rawContextCompilation;
  const fixtureRows = useMemo(
    () => simulationFixtureRows(
      canvasNodes,
      operators,
      fixtureCompilation,
      fixtureDrafts,
      fixtureInputDrafts,
      result,
    ),
    [canvasNodes, fixtureCompilation, fixtureDrafts, fixtureInputDrafts, operators, result],
  );
  const runSummary = useMemo(
    () => simulationRunSummary(canvasSummary, fixtureRows, result),
    [canvasSummary, fixtureRows, result],
  );
  const exportableDraft = useMemo(
    () => {
      const nodeIds = canvasNodes.map((node) => node.id);
      return toExportableGraphDraft(
        graphName,
        canvasNodes,
        canvasEdges,
        outputNodeId,
        fixtureCompilation.fixtures,
        graphInputSchema,
        {
          draftId: graphDraftId,
          revision: graphDraftRevision,
          tenantId: graphTenantId,
          namespace: graphNamespace,
          environment: graphEnvironment,
          visualLayout: visualLayoutWithGraphContract(
            graphVisualLayout,
            graphInputSchema,
            effectiveGraphOutputSchema,
            graphContractSource,
          ),
          outputSchema: effectiveGraphOutputSchema,
          operatorFingerprints: pickRecordByKeys(graphOperatorFingerprints, nodeIds),
          operatorSnapshots: pickRecordByKeys(graphOperatorSnapshots, nodeIds),
        },
      );
    },
    [
      canvasEdges,
      canvasNodes,
      effectiveGraphOutputSchema,
      fixtureCompilation.fixtures,
      graphDraftId,
      graphDraftRevision,
      graphEnvironment,
      graphName,
      graphContractSource,
      graphInputSchema,
      graphNamespace,
      graphOperatorFingerprints,
      graphOperatorSnapshots,
      graphTenantId,
      graphVisualLayout,
      outputNodeId,
    ],
  );
  const draftExportJson = useMemo(
    () => JSON.stringify(exportableDraft, null, 2),
    [exportableDraft],
  );
  const draftExportUrl = useMemo(
    () => `data:application/json;charset=utf-8,${encodeURIComponent(draftExportJson)}`,
    [draftExportJson],
  );
  const scenarioNodeOptions = useMemo<ScenarioNodeOption[]>(
    () => canvasNodes.map((node) => ({
      id: node.id,
      label: node.label || node.id,
      operatorRef: node.operatorRef,
      inputSchema: operatorInputSchema(operatorByRef.get(node.operatorRef)),
      outputSchema: operatorOutputSchema(operatorByRef.get(node.operatorRef)),
    })),
    [canvasNodes, operatorByRef],
  );

  useEffect(() => {
    const sequence = contractProjectionSequence.current + 1;
    contractProjectionSequence.current = sequence;
    let active = true;

    const project = async () => {
      const authoritative = authoritativeContractRef.current;
      const exactServerProjection = authoritative?.canvasSnapshot === canonicalJson(exportableDraft)
        ? authoritative
        : null;
      const targetFingerprint = exactServerProjection
        ? exactServerProjection.contract.target.fingerprint
        : await sha256Fingerprint(exportableDraft);
      const nextContract = exactServerProjection?.contract
        ?? contractDraftFromGraphDraft(exportableDraft, targetFingerprint);
      const nextContractFingerprint = exactServerProjection?.contractFingerprint
        ?? await sha256Fingerprint(nextContract);
      if (!active || contractProjectionSequence.current !== sequence) {
        return;
      }
      setContractDraft(nextContract);
      setContractFingerprint(nextContractFingerprint);
      setScenarioDraftSet((current) => {
        const graphChanged = scenarioGraphNameRef.current !== exportableDraft.graphName;
        scenarioGraphNameRef.current = exportableDraft.graphName;
        if (current && !graphChanged) {
          return current;
        }
        return scenarioDraftSetFromCanvas(
          nextContract.target,
          nextContractFingerprint,
          exportableDraft,
          scenarioNodeOptions,
          simulationTableCompilation.cases,
        );
      });
    };

    project().catch((cause: unknown) => {
      if (active && contractProjectionSequence.current === sequence) {
        setContractDraft(null);
        setContractFingerprint('');
        setError(`Contract projection failed: ${String(cause)}`);
      }
    });
    return () => {
      active = false;
    };
  }, [exportableDraft, scenarioNodeOptions, simulationTableCompilation.cases]);

  const saveGraphForScenario = useCallback(async () => {
    const stored = await saveGraphDraft(exportableDraft);
    if (!stored.draftId || !stored.revision) {
      throw new Error('Graph persistence did not return an exact draft revision.');
    }
    const projection = await fetchScenarioGraphContract(stored.draftId);
    const savedCanvasDraft: GraphDraft = {
      ...exportableDraft,
      draftId: stored.draftId,
      revision: stored.revision,
      tenantId: stored.tenantId ?? exportableDraft.tenantId,
      namespace: stored.namespace ?? exportableDraft.namespace,
      environment: stored.environment ?? exportableDraft.environment,
      operatorFingerprints: stored.operatorFingerprints ?? exportableDraft.operatorFingerprints,
      operatorSnapshots: stored.operatorSnapshots ?? exportableDraft.operatorSnapshots,
    };
    authoritativeContractRef.current = {
      canvasSnapshot: canonicalJson(savedCanvasDraft),
      contract: projection.contract,
      contractFingerprint: projection.contractFingerprint,
    };
    setGraphDraftId(stored.draftId);
    setGraphDraftRevision(stored.revision);
    setGraphTenantId(savedCanvasDraft.tenantId || 'tenant-a');
    setGraphNamespace(savedCanvasDraft.namespace || 'local');
    setGraphEnvironment(savedCanvasDraft.environment || 'test');
    setGraphOperatorFingerprints(savedCanvasDraft.operatorFingerprints ?? {});
    setGraphOperatorSnapshots(savedCanvasDraft.operatorSnapshots ?? {});
    setContractDraft(projection.contract);
    setContractFingerprint(projection.contractFingerprint);
  }, [exportableDraft]);

  const journey = useMemo(
    () => authoringJourney(operators.length, canvasSummary, fixtureRows, result),
    [canvasSummary, fixtureRows, operators.length, result],
  );
  const coachPrompt = useMemo(
    () => canvasCoachPrompt(operators.length, canvasSummary, fixtureRows, result),
    [canvasSummary, fixtureRows, operators.length, result],
  );
  const activeJourneyStepKey = journey.steps.find((step) => step.state !== 'ready')?.key ?? '';
  const fixtureCount = Object.keys(fixtureCompilation.fixtures).length;
  const fixtureErrorCount = Object.keys(fixtureCompilation.errors).length;
  const mockAttentionCount = fixtureRows
    .filter((row) => row.state === 'warning' || row.state === 'blocked')
    .length;
  const hasFixtureErrors = fixtureErrorCount > 0;
  const hasSimulationTableErrors = Object.keys(simulationTableCompilation.errors).length > 0;
  const hasContextError = Boolean(contextCompilation.error);
  const selectedFixtureDraft = selectedNode ? fixtureDrafts[selectedNode.id] ?? '' : '';
  const selectedExpectedInputDraft = selectedNode ? fixtureInputDrafts[selectedNode.id] ?? '' : '';
  const selectedFixtureHasDraft =
    selectedFixtureDraft.trim().length > 0 || selectedExpectedInputDraft.trim().length > 0;
  const selectedFixtureError = selectedNode ? fixtureCompilation.errors[selectedNode.id] : undefined;
  const operatorDetailFixtureDraft = operatorDetailNode ? fixtureDrafts[operatorDetailNode.id] ?? '' : '';
  const operatorDetailExpectedInputDraft = operatorDetailNode ? fixtureInputDrafts[operatorDetailNode.id] ?? '' : '';
  const operatorDetailFixtureHasDraft =
    operatorDetailFixtureDraft.trim().length > 0 || operatorDetailExpectedInputDraft.trim().length > 0;
  const operatorDetailFixtureError = operatorDetailNode ? fixtureCompilation.errors[operatorDetailNode.id] : undefined;
  const operatorDetailTestRows = operatorDetailNode
    ? operatorTestSuites[operatorDetailNode.id]
      ?? defaultOperatorTestSuiteRows(operatorDetailNode, operatorDetailDefinition)
    : [];
  const operatorDetailTestResults = operatorDetailNode
    ? operatorTestResults[operatorDetailNode.id] ?? {}
    : {};
  const operatorDetailTestPublication = operatorDetailNode
    ? operatorTestPublications[operatorDetailNode.id]
    : undefined;
  const operatorDetailTestsRunning = operatorDetailTestPublication?.status === 'running'
    || Object.values(operatorDetailTestResults).some((testResult) => testResult.status === 'running');
  const operatorDetailTestRunDisabledReason = operatorDetailDefinition?.runtimeReadiness?.executable === false
    ? operatorDetailDefinition.runtimeReadiness.summary
      || 'This visual operator has no executable runtime binding.'
    : undefined;
  const selectedGuideRows = useMemo(
    () =>
      connectionGuide?.nodeId === selectedNodeId
        ? connectionGuideRows(canvasNodes, connectionGuide.index)
        : [],
    [canvasNodes, connectionGuide, selectedNodeId],
  );
  const flowNodes = useMemo<Node<NodeData>[]>(
    () =>
      nodes.map((node) => {
        const candidateStatus = candidatePreview?.nodeStatuses[node.id];
        const candidatePorts = candidatePreview?.portStatuses[node.id];
        const focusState = canvasNodeFocusState(node.id, selectedNodeId, coachPrompt);
        return {
          ...node,
          selected: focusState === 'selected',
          data: {
            ...node.data,
            candidateStatus,
            candidatePorts,
            focusState,
            isOutput: node.id === outputNodeId,
          },
        };
      }),
    [candidatePreview, coachPrompt, nodes, outputNodeId, selectedNodeId],
  );
  const flowEdges = useMemo(() => withEdgeLabelLanes(edges), [edges]);

  const focusGovernanceIssue = useCallback((issue: GovernanceGateIssue) => {
    const nodeId = governanceIssueNodeId(issue);
    if (!nodeId || !nodes.some((node) => node.id === nodeId)) {
      setDeepLinkNotice({
        level: 'warning',
        message: `Governance target ${issue.targetPath || issue.issueId} is not present in this draft revision.`,
      });
      return;
    }
    setSelectedNodeId(nodeId);
    setDeepLinkNotice({ level: 'ok', message: `Focused governance issue ${issue.issueId} on ${nodeId}.` });
  }, [nodes]);

  const startOperatorDrag = useCallback((event: DragEvent<HTMLButtonElement>, operator: OperatorDefinition) => {
    event.dataTransfer.effectAllowed = 'copy';
    event.dataTransfer.setData(OPERATOR_DRAG_MIME, operator.operatorRef);
    event.dataTransfer.setData('text/plain', operator.operatorRef);
  }, []);

  const allowOperatorDrop = useCallback((event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = 'copy';
  }, []);

  const dropOperatorOnFlow = useCallback((event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    const operatorRef =
      event.dataTransfer.getData(OPERATOR_DRAG_MIME) || event.dataTransfer.getData('text/plain');
    const operator = operatorByRef.get(operatorRef);
    if (!operator) {
      return;
    }
    const bounds = flowRef.current?.getBoundingClientRect();
    const position = bounds
      ? {
          x: Math.max(24, event.clientX - bounds.left - 120),
          y: Math.max(24, event.clientY - bounds.top - 54),
        }
      : undefined;
    addOperator(operator, position);
  }, [addOperator, operatorByRef]);

  const updateFixtureDraftForNode = useCallback((nodeId: string, value: string) => {
    clearRunResult();
    setFixtureDrafts((current) => ({ ...current, [nodeId]: value }));
  }, [clearRunResult]);

  const updateExpectedInputDraftForNode = useCallback((nodeId: string, value: string) => {
    clearRunResult();
    setFixtureInputDrafts((current) => ({ ...current, [nodeId]: value }));
  }, [clearRunResult]);

  const useFixtureSampleForNode = useCallback((nodeId: string, operator: OperatorDefinition | undefined) => {
    if (!operator) {
      return;
    }
    clearRunResult();
    setFixtureDrafts((current) => ({
      ...current,
      [nodeId]: fixtureDraftForOperator(operator),
    }));
  }, [clearRunResult]);

  const updateSelectedFixtureDraft = useCallback((value: string) => {
    if (!selectedNodeId) {
      return;
    }
    updateFixtureDraftForNode(selectedNodeId, value);
  }, [selectedNodeId, updateFixtureDraftForNode]);

  const updateSelectedExpectedInputDraft = useCallback((value: string) => {
    if (!selectedNodeId) {
      return;
    }
    updateExpectedInputDraftForNode(selectedNodeId, value);
  }, [selectedNodeId, updateExpectedInputDraftForNode]);

  const useSelectedFixtureSample = useCallback(() => {
    if (!selectedNodeId) {
      return;
    }
    useFixtureSampleForNode(selectedNodeId, selectedOperator);
  }, [selectedNodeId, selectedOperator, useFixtureSampleForNode]);

  const adaptCapabilityCatalogSource = useCallback(async () => {
    if (!librarySourceText.trim()) {
      setLibraryDiagnostics([]);
      setLibraryNotice({ level: 'error', message: 'Capability catalog source is empty.' });
      return;
    }
    setLibraryBusy(true);
    try {
      const adaptation = await adaptCapabilityCatalogText(librarySourceText);
      setLibraryDiagnostics(adaptation.validation?.diagnostics ?? []);
      if (!adaptation.library) {
        const level = operatorLibraryValidationLevel(adaptation.validation);
        setLibraryNotice({
          level: level === 'ok' ? 'ok' : level,
          message: operatorLibraryValidationMessage(adaptation.validation),
        });
        return;
      }
      setLibrarySourceText(JSON.stringify(adaptation.library, null, 2));
      setDslRewriteGateResult(null);
      const projectedOperators = adaptation.projectionReview?.projectedOperatorCount
        ?? adaptation.library.operators.length;
      const projectedFunctions = adaptation.projectionReview?.projectedFunctionCount
        ?? adaptation.library.builtInFunctions?.length
        ?? 0;
      const opaqueSchemas = adaptation.projectionReview?.opaqueSchemaCount ?? 0;
      const coverage = adaptation.projectionReview?.coverageStatus ?? 'projected';
      const suffix = opaqueSchemas > 0 ? `, ${opaqueSchemas} opaque schema fallbacks` : '';
      setLibraryNotice({
        level: adaptation.validation?.valid ? 'ok' : 'warning',
        message: `Adapted ${adaptation.library.libraryId}: ${projectedOperators} operators / ${projectedFunctions} functions (${coverage}${suffix}). Validate before importing.`,
      });
    } catch (cause: unknown) {
      setLibraryDiagnostics([]);
      setLibraryNotice({ level: 'error', message: String(cause) });
    } finally {
      setLibraryBusy(false);
    }
  }, [librarySourceText]);

  const validateLibrarySource = useCallback(async () => {
    if (!librarySourceText.trim()) {
      setLibraryDiagnostics([]);
      setLibraryNotice({ level: 'error', message: 'Library source is empty.' });
      return;
    }
    setLibraryBusy(true);
    try {
      const validation = await validateOperatorLibraryText(librarySourceText);
      const level = operatorLibraryValidationLevel(validation);
      setLibraryDiagnostics(validation.diagnostics ?? []);
      setLibraryWarningsAcknowledged(false);
      setLibraryWarningReason('');
      setLibraryNotice({
        level: level === 'ok' ? 'ok' : level,
        message: operatorLibraryValidationMessage(validation),
      });
    } catch (cause: unknown) {
      setLibraryDiagnostics([]);
      setLibraryNotice({ level: 'error', message: String(cause) });
    } finally {
      setLibraryBusy(false);
    }
  }, [librarySourceText]);

  const importLibrarySource = useCallback(async () => {
    if (!librarySourceText.trim()) {
      setLibraryDiagnostics([]);
      setLibraryNotice({ level: 'error', message: 'Library source is empty.' });
      return;
    }
    setLibraryBusy(true);
    try {
      const storedLibrary = await importOperatorLibraryText(
        librarySourceText,
        libraryWarningsAcknowledged,
        libraryWarningReason,
      );
      await reloadOperators();
      setLibrarySourceText(JSON.stringify(storedLibrary, null, 2));
      setDslRewriteGateResult(null);
      setLibraryDiagnostics([]);
      setLibraryWarningsAcknowledged(false);
      setLibraryWarningReason('');
      setLibraryNotice({ level: 'ok', message: operatorLibraryImportMessage(storedLibrary) });
      setSearch('');
      setPaletteFacet('all');
      setSourceFilter('all');
      setTagFilter('all');
    } catch (cause: unknown) {
      setLibraryDiagnostics([]);
      setLibraryNotice({ level: 'error', message: String(cause) });
    } finally {
      setLibraryBusy(false);
    }
  }, [librarySourceText, libraryWarningReason, libraryWarningsAcknowledged, reloadOperators]);

  const applyDslProjection = useCallback((projection: DslVisualProjection, contractSource?: string) => {
    const imported = fromGraphDraft(projection.draft);
    const nextNodes: Node<NodeData>[] = [];
    const nextOperatorTestSuites: Record<string, OperatorTestSuiteDraftRow[]> = {};
    const nextFixtureDrafts: Record<string, string> = {};
    const nextFixtureInputDrafts: Record<string, string> = {};

    for (const importedNode of imported.nodes) {
      const operator = operatorByRef.get(importedNode.operatorRef)
        ?? imported.operatorSnapshots[importedNode.id]
        ?? placeholderOperatorDefinition(importedNode.operatorRef);
      const summary = summarizeOperator(operator);
      const nextNode: Node<NodeData> = {
        id: importedNode.id,
        type: 'operator',
        position: importedNode.position,
        data: {
          label: importedNode.label ?? summary.name,
          operatorRef: importedNode.operatorRef,
          summary,
          inputs: importedNode.inputs,
          config: importedNode.config,
        },
      };
      const testRows = defaultOperatorTestSuiteRows(nextNode, operator);
      const fixture = imported.nodeFixtures[importedNode.id];
      if (fixture) {
        if (hasOwnValue(fixture, 'expectedInput')) {
          nextFixtureInputDrafts[importedNode.id] = JSON.stringify(fixture.expectedInput, null, 2);
          testRows[0] = {
            ...testRows[0],
            inputText: JSON.stringify(fixture.expectedInput, null, 2),
          };
        }
        if (hasOwnValue(fixture, 'output')) {
          nextFixtureDrafts[importedNode.id] = JSON.stringify(fixture.output, null, 2);
          testRows[0] = {
            ...testRows[0],
            outputText: JSON.stringify(fixture.output, null, 2),
            transportResponseText: transportResponseTextForExpected(operator, fixture.output),
          };
        }
      }
      nextOperatorTestSuites[importedNode.id] = testRows;
      nextNodes.push(nextNode);
    }

    const nextEdges = imported.edges.map((edge) => ({
      id: edge.id,
      source: edge.source,
      target: edge.target,
      sourceHandle: edge.sourcePort ? handleIdForPort('out', edge.sourcePort) : undefined,
      targetHandle: edge.targetPort ? handleIdForPort('in', edge.targetPort) : undefined,
      kind: edge.kind,
      condition: edge.condition,
      sourcePath: edge.sourcePath,
      targetPath: edge.targetPath,
      bindingKey: edge.bindingKey,
      data: {
        kind: edge.kind ?? 'data',
        condition: edge.condition ?? '',
        sourcePath: edge.sourcePath ?? '',
        targetPath: edge.targetPath ?? '',
        bindingKey: edge.bindingKey ?? '',
      },
      label: edgeLabelFromCanvasEdge(edge),
      ...EDGE_LABEL_OPTIONS,
      animated: true,
      className: edge.kind && edge.kind !== 'data' ? 'accepted-edge route-edge' : 'accepted-edge',
    } as CanvasFlowEdge));

    const nextInputSchema = imported.inputSchema ?? EMPTY_GRAPH_INPUT_SCHEMA;
    const nextOutputSchema = imported.outputSchema ?? null;
    const nextContextVariables = importedContextVariables(nextInputSchema);
    const nextSimulationTableRows = [emptySimulationTableRow('table-case-1', nextInputSchema)];
    const notice = dslProjectionNotice(projection);

    clearRunResult();
    counter.current = maxCanvasNodeSequence(imported.nodes);
    contextVariableCounter.current = nextContextVariables.length;
    tableTestCounter.current = nextSimulationTableRows.length;
    operatorTestCounter.current = 0;
    setNodes(autoLayoutFlowNodes(nextNodes, nextEdges));
    setEdges(nextEdges);
    setFixtureDrafts(nextFixtureDrafts);
    setFixtureInputDrafts(nextFixtureInputDrafts);
    setOperatorTestSuites(nextOperatorTestSuites);
    setOperatorTestResults({});
    setOperatorTestPublications({});
    setSimulationTableRows(nextSimulationTableRows);
    setSimulationTableResults({});
    setGraphName(imported.graphName);
    setGraphDraftId(imported.draftId ?? '');
    setGraphDraftRevision(imported.revision ?? 0);
    setGraphTenantId(projection.draft.tenantId || 'tenant-a');
    setGraphNamespace(projection.draft.namespace || 'local');
    setGraphEnvironment(projection.draft.environment || 'test');
    authoritativeContractRef.current = null;
    setGraphInputSchema(nextInputSchema);
    setGraphOutputSchema(nextOutputSchema);
    setGraphContractSource(contractSource ?? `DSL ${projection.sourceId || imported.graphName}`);
    setGraphVisualLayout(visualLayoutWithImportSourceMap(
      visualLayoutWithGraphContract(
        imported.visualLayout ?? {},
        nextInputSchema,
        nextOutputSchema,
        'dsl',
      ),
      projection.sourceMap,
    ));
    setGraphOperatorFingerprints(imported.operatorFingerprints);
    setGraphOperatorSnapshots(imported.operatorSnapshots);
    setSimulationContextDraft(JSON.stringify(sampleFromSchemaEnvelope(nextInputSchema), null, 2));
    setContextVariables(nextContextVariables);
    setExplicitOutputNodeId(imported.outputNodeId);
    setSelectedNodeId(imported.outputNodeId || imported.nodes[0]?.id || '');
    setOperatorDetailNodeId('');
    setTestSuiteOpen(false);
    setConnectionGuide(null);
    setConnectionGuideNotice(null);
    setCandidatePreview(null);
    setSelectedConnectionSourcePort('');
    setPendingConnectionGuideNodeId('');
    setDslImportDiagnostics(projection.diagnostics ?? []);
    setDslImportCoverage(projection.coverage ?? null);
    setDslImportSourceMap(projection.sourceMap ?? null);
    setDslImportRoundTrip(projection.roundTrip ?? null);
    setDslRewriteGateResult(null);
    setDslImportNotice(notice);
    setConnectionNotice(notice);

    const graphSize = { nodeCount: imported.nodes.length, edgeCount: imported.edges.length };
    if (typeof window.requestAnimationFrame === 'function') {
      window.requestAnimationFrame(() => fitCanvasToView(graphSize));
    } else {
      fitCanvasToView(graphSize);
    }
  }, [clearRunResult, fitCanvasToView, operatorByRef]);
  const applyDslProjectionRef = useRef(applyDslProjection);

  useEffect(() => {
    applyDslProjectionRef.current = applyDslProjection;
  }, [applyDslProjection]);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const requestedDraftId = params.get('draftId')?.trim() ?? '';
    const requestedNodeId = params.get('nodeId')?.trim() ?? '';
    const requestedOperatorRef = params.get('operatorRef')?.trim() ?? '';
    const requestedRunId = params.get('runId')?.trim() ?? '';
    const requestedGateIssueId = params.get('gateIssueId')?.trim() ?? '';
    const loadKey = [
      requestedDraftId,
      requestedNodeId,
      requestedOperatorRef,
      requestedRunId,
      requestedGateIssueId,
    ].join('|');
    if (!loadKey.replace(/\|/g, '')) {
      return undefined;
    }
    let active = true;

    const loadDeepLink = async () => {
      setDeepLinkNotice({ level: 'pending', message: 'Opening linked authoring context...' });
      setError('');

      const run = requestedRunId ? await fetchVisualGraphRun(requestedRunId) : null;
      if (!active) {
        return;
      }
      setDeepLinkRun(run);
      const resolvedDraftId = requestedDraftId || run?.draftId || '';
      if (!resolvedDraftId) {
        setDeepLinkNotice({
          level: 'warning',
          message: `Run ${requestedRunId} is not linked to a stored draft.`,
        });
        return;
      }

      const [draft, gateView] = await Promise.all([
        fetchGraphDraft(resolvedDraftId),
        requestedGateIssueId
          ? fetchGovernanceGateView(resolvedDraftId)
          : Promise.resolve<GovernanceGateView | null>(null),
      ]);
      if (!active) {
        return;
      }
      if (gateView) {
        setGovernanceGateView(gateView);
      }
      applyDslProjectionRef.current({
        sourceId: `draft:${resolvedDraftId}`,
        draft,
        diagnostics: [],
      }, `Stored draft ${resolvedDraftId}@${draft.revision ?? 0}`);

      const gateIssue = requestedGateIssueId
        ? gateView?.result?.issues.find((issue) => issue.issueId === requestedGateIssueId)
        : undefined;
      const issueNodeId = gateIssue ? governanceIssueNodeId(gateIssue) : '';
      const operatorNodeId = requestedOperatorRef
        ? draft.nodes.find((node) => node.operatorRef === requestedOperatorRef)?.id ?? ''
        : '';
      const runNodeId = run?.outputNode && draft.nodes.some((node) => node.id === run.outputNode)
        ? run.outputNode
        : '';
      const targetNodeId = requestedNodeId || issueNodeId || operatorNodeId || runNodeId;

      if (targetNodeId && draft.nodes.some((node) => node.id === targetNodeId)) {
        setSelectedNodeId(targetNodeId);
        setDeepLinkNotice({
          level: 'ok',
          message: `Opened ${resolvedDraftId}@${draft.revision ?? 0} and focused ${targetNodeId}.`,
        });
      } else if (requestedNodeId || requestedOperatorRef || requestedGateIssueId) {
        const missingTarget = requestedNodeId || requestedOperatorRef || requestedGateIssueId;
        setDeepLinkNotice({
          level: 'warning',
          message: `Opened ${resolvedDraftId}, but target ${missingTarget} is not present in this revision.`,
        });
      } else {
        setDeepLinkNotice({
          level: 'ok',
          message: `Opened ${resolvedDraftId}@${draft.revision ?? 0}.`,
        });
      }
    };

    loadDeepLink().catch((cause: unknown) => {
      if (active) {
        setDeepLinkNotice({ level: 'error', message: `Deep link failed: ${String(cause)}` });
      }
    });
    return () => {
      active = false;
    };
  }, []);

  const previewLegacyDsl = useCallback(async () => {
    if (!dslSourceText.trim()) {
      setDslImportDiagnostics([]);
      setDslImportCoverage(null);
      setDslImportSourceMap(null);
      setDslImportRoundTrip(null);
      setDslRewriteGateResult(null);
      setDslImportNotice({ level: 'error', message: 'DSL source is empty.' });
      return;
    }
    setDslImportBusy(true);
    setDslRewriteGateResult(null);
    setDslImportNotice({ level: 'pending', message: 'Rendering DSL preview...' });
    setError('');
    try {
      const projection = await previewDslImport({
        sourceId: dslSourceId.trim() || 'inline.dsl',
        dsl: dslSourceText,
        operatorLibraryIds: operatorLibraryIds(operators),
        inlineLibraries: inlineLibrariesFromSourceText(librarySourceText),
        mode: 'preview',
      });
      applyDslProjection(projection);
    } catch (cause: unknown) {
      setDslImportDiagnostics([]);
      setDslImportCoverage(null);
      setDslImportSourceMap(null);
      setDslImportRoundTrip(null);
      setDslRewriteGateResult(null);
      setDslImportNotice({ level: 'error', message: String(cause) });
    } finally {
      setDslImportBusy(false);
    }
  }, [applyDslProjection, dslSourceId, dslSourceText, librarySourceText, operators]);

  const checkLegacyDslRewriteGate = useCallback(async () => {
    if (!dslSourceText.trim()) {
      setDslImportDiagnostics([]);
      setDslImportCoverage(null);
      setDslImportSourceMap(null);
      setDslImportRoundTrip(null);
      setDslRewriteGateResult(null);
      setDslImportNotice({ level: 'error', message: 'DSL source is empty.' });
      return;
    }
    setDslRewriteGateBusy(true);
    setDslRewriteGateResult(null);
    setDslImportNotice({ level: 'pending', message: 'Checking rewrite gate...' });
    setError('');
    try {
      const result = await checkDslRewriteGate({
        sourceId: dslSourceId.trim() || 'inline.dsl',
        dsl: dslSourceText,
        operatorLibraryIds: operatorLibraryIds(operators),
        inlineLibraries: inlineLibrariesFromSourceText(librarySourceText),
        mode: 'rewrite-gate',
      });
      const notice = dslRewriteGateNotice(result);
      setDslRewriteGateResult(result);
      setDslImportRoundTrip(result.roundTrip ?? null);
      setDslImportDiagnostics(result.diagnostics ?? []);
      setDslImportNotice(notice);
      setConnectionNotice(notice);
    } catch (cause: unknown) {
      setDslRewriteGateResult(null);
      setDslImportNotice({ level: 'error', message: String(cause) });
    } finally {
      setDslRewriteGateBusy(false);
    }
  }, [dslSourceId, dslSourceText, librarySourceText, operators]);

  const commitLegacyDsl = useCallback(async () => {
    if (!dslSourceText.trim()) {
      setDslImportDiagnostics([]);
      setDslImportCoverage(null);
      setDslImportSourceMap(null);
      setDslImportRoundTrip(null);
      setDslRewriteGateResult(null);
      setDslImportNotice({ level: 'error', message: 'DSL source is empty.' });
      return;
    }
    setDslCommitBusy(true);
    setDslRewriteGateResult(null);
    setDslImportNotice({ level: 'pending', message: 'Committing DSL draft...' });
    setError('');
    try {
      const result = await commitDslImport({
        sourceId: dslSourceId.trim() || 'inline.dsl',
        dsl: dslSourceText,
        operatorLibraryIds: operatorLibraryIds(operators),
        inlineLibraries: inlineLibrariesFromSourceText(librarySourceText),
        mode: 'commit',
      });
      if (!result.draft) {
        setDslImportDiagnostics([
          ...(result.diagnostics ?? []),
          ...(result.validation?.diagnostics ?? []),
        ]);
        setDslImportNotice(dslCommitNotice(result));
        return;
      }
      const sourceMap = dslSourceMapFromImportResult(result, dslImportSourceMap);
      applyDslProjection({
        schemaVersion: 'bloge.dslVisualProjection.v1',
        sourceId: dslSourceId.trim() || 'inline.dsl',
        draft: result.draft,
        sourceMap,
        coverage: dslImportCoverage ?? undefined,
        roundTrip: dslImportRoundTrip ?? undefined,
        diagnostics: [
          ...(result.diagnostics ?? []),
          ...(result.validation?.diagnostics ?? []),
        ],
      });
      const notice = dslCommitNotice(result);
      setDslImportNotice(notice);
      setConnectionNotice(notice);
    } catch (cause: unknown) {
      setDslImportNotice({ level: 'error', message: String(cause) });
    } finally {
      setDslCommitBusy(false);
    }
  }, [
    applyDslProjection,
    dslImportCoverage,
    dslImportRoundTrip,
    dslImportSourceMap,
    dslSourceId,
    dslSourceText,
    librarySourceText,
    operators,
  ]);

  const clearFixtureForNode = useCallback((nodeId: string) => {
    clearRunResult();
    setFixtureDrafts((current) => {
      const next = { ...current };
      delete next[nodeId];
      return next;
    });
    setFixtureInputDrafts((current) => {
      const next = { ...current };
      delete next[nodeId];
      return next;
    });
  }, [clearRunResult]);

  const clearSelectedFixture = useCallback(() => {
    if (!selectedNodeId) {
      return;
    }
    clearFixtureForNode(selectedNodeId);
  }, [clearFixtureForNode, selectedNodeId]);

  const clearOperatorTestPublication = useCallback((nodeId: string) => {
    setOperatorTestPublications((current) => {
      if (!current[nodeId]) {
        return current;
      }
      const next = { ...current };
      delete next[nodeId];
      return next;
    });
  }, []);

  const addOperatorTestRow = useCallback((node: Node<NodeData>, operator: OperatorDefinition | undefined) => {
    const nextIndex = operatorTestCounter.current + 1;
    operatorTestCounter.current = nextIndex;
    setOperatorTestSuites((current) => {
      const rows = current[node.id] ?? defaultOperatorTestSuiteRows(node, operator);
      const defaultRow = defaultOperatorTestSuiteRows(node, operator)[0];
      return {
        ...current,
        [node.id]: [
          ...rows,
          {
            ...defaultRow,
            id: `operator-case-${nextIndex}`,
            name: `Case ${rows.length + 1}`,
          },
        ],
      };
    });
    clearOperatorTestPublication(node.id);
  }, [clearOperatorTestPublication]);

  const updateOperatorTestRow = useCallback((
    node: Node<NodeData>,
    operator: OperatorDefinition | undefined,
    rowId: string,
    patch: Partial<OperatorTestSuiteDraftRow>,
  ) => {
    setOperatorTestSuites((current) => {
      const rows = current[node.id] ?? defaultOperatorTestSuiteRows(node, operator);
      return {
        ...current,
        [node.id]: rows.map((row) => (row.id === rowId ? { ...row, ...patch } : row)),
      };
    });
    setOperatorTestResults((current) => {
      if (!current[node.id]?.[rowId]) {
        return current;
      }
      const nodeResults = { ...current[node.id] };
      delete nodeResults[rowId];
      return { ...current, [node.id]: nodeResults };
    });
    clearOperatorTestPublication(node.id);
  }, [clearOperatorTestPublication]);

  const removeOperatorTestRow = useCallback((
    node: Node<NodeData>,
    operator: OperatorDefinition | undefined,
    rowId: string,
  ) => {
    setOperatorTestSuites((current) => {
      const rows = current[node.id] ?? defaultOperatorTestSuiteRows(node, operator);
      if (rows.length <= 1) {
        return current;
      }
      return {
        ...current,
        [node.id]: rows.filter((row) => row.id !== rowId),
      };
    });
    setOperatorTestResults((current) => {
      if (!current[node.id]?.[rowId]) {
        return current;
      }
      const nodeResults = { ...current[node.id] };
      delete nodeResults[rowId];
      return { ...current, [node.id]: nodeResults };
    });
    clearOperatorTestPublication(node.id);
  }, [clearOperatorTestPublication]);

  const applyOperatorTestFixture = useCallback((nodeId: string, row: OperatorTestSuiteDraftRow) => {
    const compilation = parseOperatorTestSuiteRow(row);
    if (compilation.error) {
      return;
    }
    clearRunResult();
    setFixtureInputDrafts((current) => ({
      ...current,
      [nodeId]: JSON.stringify(compilation.input, null, 2),
    }));
    setFixtureDrafts((current) => ({
      ...current,
      [nodeId]: JSON.stringify(compilation.output, null, 2),
    }));
  }, [clearRunResult]);

  const addSimulationTableRow = useCallback(() => {
    const nextIndex = tableTestCounter.current + 1;
    tableTestCounter.current = nextIndex;
    const row = emptySimulationTableRow(`table-case-${nextIndex}`, graphInputSchema);
    setSimulationTableRows((current) => [...current, row]);
    setSimulationTableResults({});
  }, [graphInputSchema]);

  const updateSimulationTableRow = useCallback((
    id: string,
    patch: Partial<SimulationTableTestDraftRow>,
  ) => {
    setSimulationTableRows((current) => current.map((row) => (
      row.id === id ? { ...row, ...patch } : row
    )));
    setSimulationTableResults((current) => {
      if (!current[id]) {
        return current;
      }
      const next = { ...current };
      delete next[id];
      return next;
    });
  }, []);

  const removeSimulationTableRow = useCallback((id: string) => {
    setSimulationTableRows((current) => current.filter((row) => row.id !== id));
    setSimulationTableResults((current) => {
      if (!current[id]) {
        return current;
      }
      const next = { ...current };
      delete next[id];
      return next;
    });
  }, []);

  const clearSimulationTableResults = useCallback(() => {
    setSimulationTableResults({});
  }, []);

  const setSelectedAsOutput = useCallback(() => {
    if (!selectedNodeId) {
      return;
    }
    clearRunResult();
    setExplicitOutputNodeId(selectedNodeId);
    setGraphOutputSchema(null);
    setGraphContractSource('Current draft');
  }, [clearRunResult, selectedNodeId]);

  const onConnectStart = useCallback(async (_event: unknown, params: ConnectionStartParams) => {
    if (params.handleType !== 'source' || !params.nodeId) {
      return;
    }
    const requestId = candidatePreviewSequence.current + 1;
    candidatePreviewSequence.current = requestId;
    setLoadingCandidates(true);
    setCandidatePreview(null);
    setConnectionNotice({ level: 'pending', message: 'Discovering compatible targets...' });
    try {
      const response = await fetchConnectionCandidates(toConnectionCandidatesRequest(
        graphName,
        canvasNodes,
        canvasEdges,
        outputNodeId,
        params.nodeId,
        params.handleId,
      ));
      if (candidatePreviewSequence.current !== requestId) {
        return;
      }
      const index = indexConnectionCandidates(response);
      setCandidatePreview(index);
      setConnectionNotice({
        level: index.acceptedCount > 0 ? 'ok' : 'warning',
        message: connectionCandidatesMessage(response),
      });
    } catch (cause: unknown) {
      if (candidatePreviewSequence.current === requestId) {
        setConnectionNotice({ level: 'error', message: String(cause) });
      }
    } finally {
      if (candidatePreviewSequence.current === requestId) {
        setLoadingCandidates(false);
      }
    }
  }, [canvasEdges, canvasNodes, graphName, outputNodeId]);

  const onConnectEnd = useCallback(() => {
    candidatePreviewSequence.current += 1;
    setCandidatePreview(null);
    setLoadingCandidates(false);
  }, []);

  const applyCheckedConnection = useCallback(async (params: CheckedConnectionParams) => {
    if (!params.sourceNodeId || !params.targetNodeId) {
      return;
    }
    candidatePreviewSequence.current += 1;
    setCandidatePreview(null);
    setCheckingConnection(true);
    setConnectionNotice({ level: 'pending', message: 'Checking schema compatibility...' });
    try {
      let sourcePath = params.sourcePath ?? '';
      let targetPath = params.targetPath ?? '';
      let check = await checkConnection(toConnectionCheckRequest(
        graphName,
        canvasNodes,
        canvasEdges,
        outputNodeId,
        params.sourceNodeId,
        params.targetNodeId,
        params.sourceHandleId,
        params.targetHandleId,
        sourcePath,
        targetPath,
      ));
      let message = connectionDecisionMessage(check);

      if (!check.accepted && !sourcePath && !targetPath) {
        const targetPort = portNameFromHandle(params.targetHandleId, 'in');
        const sourceHandleId = params.sourceHandleId;
        let candidateLabels: string[] = [];
        let candidateIndex: ConnectionCandidateIndex | null = null;
        try {
          const response = await fetchConnectionCandidates(toConnectionCandidatesRequest(
            graphName,
            canvasNodes,
            canvasEdges,
            outputNodeId,
            params.sourceNodeId,
            sourceHandleId,
          ));
          const index = indexConnectionCandidates(response);
          candidateIndex = index;
          setCandidatePreview(index);
          candidateLabels = acceptedFieldCandidateLabels(index.candidates, params.targetNodeId, targetPort);
          const fieldCandidate = singleAcceptedFieldCandidate(index.candidates, params.targetNodeId, targetPort);
          if (fieldCandidate) {
            targetPath = fieldCandidate.target.path ?? '';
            check = await checkConnection(toConnectionCheckRequest(
              graphName,
              canvasNodes,
              canvasEdges,
              outputNodeId,
              params.sourceNodeId,
              params.targetNodeId,
              params.sourceHandleId,
              params.targetHandleId,
              sourcePath,
              targetPath,
            ));
            if (check.accepted) {
              message = `${connectionDecisionMessage(check)} Bound field ${endpointLabel(targetPort, targetPath, 'input')}.`;
            }
          }
        } catch {
          candidateLabels = [];
        }
        if (!check.accepted && candidateLabels.length > 1) {
          const sourcePort = portNameFromHandle(sourceHandleId, 'out');
          if (candidateIndex) {
            setSelectedNodeId(params.sourceNodeId);
            setSelectedConnectionSourcePort(sourcePort);
            setConnectionGuide({
              nodeId: params.sourceNodeId,
              sourcePort,
              index: candidateIndex,
            });
            setConnectionGuideNotice({
              level: 'warning',
              message: `Choose a field path for ${endpointLabel(targetPort, '', 'input')}.`,
            });
          }
          message = `${message} Choose a compatible field path in Connect Next (${candidateLabels.slice(0, 4).join(', ')}).`;
        }
      }

      if (!check.accepted) {
        setConnectionNotice({ level: 'error', message });
        return;
      }

      clearRunResult();
      sourcePath = check.edge?.source?.path ?? sourcePath;
      targetPath = check.edge?.target?.path ?? targetPath;
      const bindingKey = check.bindingKey ?? '';
      const sourcePort = portNameFromHandle(params.sourceHandleId, 'out');
      const targetPort = portNameFromHandle(params.targetHandleId, 'in');
      const label = `${endpointLabel(sourcePort, sourcePath, 'value')} -> ${endpointLabel(targetPort, targetPath, 'input')}`;
      setEdges((current) =>
        addEdge({
          source: params.sourceNodeId,
          target: params.targetNodeId,
          sourceHandle: params.sourceHandleId,
          targetHandle: params.targetHandleId,
          sourcePath,
          targetPath,
          bindingKey,
          data: { sourcePath, targetPath, bindingKey },
          id: check.edge?.id || `${params.sourceNodeId}:${sourcePort}.${sourcePath}->${params.targetNodeId}:${targetPort}.${targetPath}`,
          label,
          ...EDGE_LABEL_OPTIONS,
          animated: true,
          className: 'accepted-edge',
        } as CanvasFlowEdge, current),
      );
      setConnectionNotice({
        level: check.summary?.graphStillInvalid ? 'warning' : 'ok',
        message,
      });
    } catch (cause: unknown) {
      setConnectionNotice({ level: 'error', message: String(cause) });
    } finally {
      setCheckingConnection(false);
    }
  }, [canvasEdges, canvasNodes, clearRunResult, graphName, outputNodeId]);

  const onConnect = useCallback(async (connection: Connection) => {
    await applyCheckedConnection({
      sourceNodeId: connection.source ?? '',
      targetNodeId: connection.target ?? '',
      sourceHandleId: connection.sourceHandle,
      targetHandleId: connection.targetHandle,
    });
  }, [applyCheckedConnection]);

  useEffect(() => {
    connectionGuideSequence.current += 1;
    if (!selectedNodeId || selectedOutputPorts.length === 0) {
      setSelectedConnectionSourcePort('');
      setConnectionGuide(null);
      setConnectionGuideNotice(null);
      setCandidatePreview(null);
      return;
    }
    setSelectedConnectionSourcePort((current) =>
      selectedOutputPorts.includes(current) ? current : selectedOutputPorts[0] ?? '',
    );
    setConnectionGuide((current) => (current?.nodeId === selectedNodeId ? current : null));
    setConnectionGuideNotice(null);
    setCandidatePreview(null);
  }, [selectedNodeId, selectedOutputPortKey, selectedOutputPorts]);

  const loadSelectedConnectionGuide = useCallback(async () => {
    if (!selectedNodeId) {
      return;
    }
    const requestId = connectionGuideSequence.current + 1;
    connectionGuideSequence.current = requestId;
    setConnectionGuideBusy(true);
    setConnectionGuide(null);
    setConnectionGuideNotice({ level: 'pending', message: 'Finding targets...' });
    try {
      const response = await fetchConnectionCandidates(toConnectionCandidatesRequest(
        graphName,
        canvasNodes,
        canvasEdges,
        outputNodeId,
        selectedNodeId,
        handleIdForPort('out', selectedConnectionSourcePort),
      ));
      if (connectionGuideSequence.current !== requestId) {
        return;
      }
      const index = indexConnectionCandidates(response);
      setConnectionGuide({
        nodeId: selectedNodeId,
        sourcePort: selectedConnectionSourcePort,
        index,
      });
      setCandidatePreview(index);
      setConnectionGuideNotice({
        level: index.acceptedCount > 0 ? 'ok' : 'warning',
        message: connectionCandidatesMessage(response),
      });
    } catch (cause: unknown) {
      if (connectionGuideSequence.current === requestId) {
        setConnectionGuideNotice({ level: 'error', message: String(cause) });
      }
    } finally {
      if (connectionGuideSequence.current === requestId) {
        setConnectionGuideBusy(false);
      }
    }
  }, [
    canvasEdges,
    canvasNodes,
    graphName,
    outputNodeId,
    selectedConnectionSourcePort,
    selectedNodeId,
  ]);

  useEffect(() => {
    if (!pendingConnectionGuideNodeId || pendingConnectionGuideNodeId !== selectedNodeId) {
      return;
    }
    const sourcePortReady =
      selectedOutputPorts.length > 0
      && selectedOutputPorts.includes(selectedConnectionSourcePort);
    if (!sourcePortReady) {
      return;
    }
    setPendingConnectionGuideNodeId('');
    void loadSelectedConnectionGuide();
  }, [
    loadSelectedConnectionGuide,
    pendingConnectionGuideNodeId,
    selectedConnectionSourcePort,
    selectedNodeId,
    selectedOutputPortKey,
    selectedOutputPorts,
  ]);

  const connectGuideRow = useCallback(async (row: ConnectionGuideRow) => {
    if (!selectedNodeId || row.status !== 'ready') {
      return;
    }
    await applyCheckedConnection({
      sourceNodeId: selectedNodeId,
      targetNodeId: row.targetNodeId,
      sourceHandleId: handleIdForPort('out', selectedConnectionSourcePort),
      targetHandleId: handleIdForPort('in', row.targetPort),
      targetPath: row.targetPath,
    });
    setConnectionGuide(null);
    setConnectionGuideNotice(null);
  }, [applyCheckedConnection, selectedConnectionSourcePort, selectedNodeId]);

  const connectGuideFieldOption = useCallback(async (
    row: ConnectionGuideRow,
    option: ConnectionGuideFieldOption,
  ) => {
    if (!selectedNodeId || !option.accepted) {
      return;
    }
    await applyCheckedConnection({
      sourceNodeId: selectedNodeId,
      targetNodeId: row.targetNodeId,
      sourceHandleId: handleIdForPort('out', selectedConnectionSourcePort),
      targetHandleId: handleIdForPort('in', row.targetPort),
      targetPath: option.path,
    });
    setConnectionGuide(null);
    setConnectionGuideNotice(null);
  }, [applyCheckedConnection, selectedConnectionSourcePort, selectedNodeId]);

  const showSimulationResponse = useCallback((response: SimulationResponse) => {
    setResult(response);

    const statuses = nodeStatuses(response);
    setNodes((current) =>
      current.map((node) => {
        const status = statuses[node.id];
        return {
          ...node,
          data: { ...node.data, status },
        };
      }),
    );
  }, []);

  const runScenarioSimulation = useCallback(async (request: Parameters<typeof simulate>[0]) => {
    setBusy(true);
    setError('');
    try {
      const response = await simulate(request);
      showSimulationResponse(response);
      return response;
    } catch (cause: unknown) {
      setError(String(cause));
      throw cause;
    } finally {
      setBusy(false);
    }
  }, [showSimulationResponse]);

  const rebaseScenariosToCurrentContract = useCallback(() => {
    if (!contractDraft || !contractFingerprint || !scenarioDraftSet) {
      return;
    }
    setScenarioDraftSet(rebaseScenarioDraftSet(
      scenarioDraftSet,
      contractDraft.target,
      contractFingerprint,
    ));
  }, [contractDraft, contractFingerprint, scenarioDraftSet]);

  const runOperatorTestRows = useCallback(async (
    nodeId: string,
    rowsToRun: OperatorTestSuiteDraftRow[],
  ) => {
    if (rowsToRun.length === 0) {
      return;
    }
    clearOperatorTestPublication(nodeId);

    const node = canvasNodes.find((candidate) => candidate.id === nodeId);
    const operator = node ? operatorByRef.get(node.operatorRef) : undefined;
    if (!node || !operator) {
      const detail = node
        ? `Operator ${node.operatorRef} is not available in the current catalog.`
        : `Canvas node ${nodeId} is no longer available.`;
      setOperatorTestResults((current) => ({
        ...current,
        [nodeId]: {
          ...(current[nodeId] ?? {}),
          ...Object.fromEntries(rowsToRun.map((row) => [
            row.id,
            {
              id: row.id,
              name: row.name.trim() || row.id,
              status: 'failed' as const,
              detail,
            },
          ])),
        },
      }));
      return;
    }

    setError('');
    for (const row of rowsToRun) {
      const compilation = parseOperatorTestSuiteRow(row);
      if (compilation.error) {
        setOperatorTestResults((current) => ({
          ...current,
          [nodeId]: {
            ...(current[nodeId] ?? {}),
            [row.id]: {
              id: row.id,
              name: row.name.trim() || row.id,
              status: 'failed',
              detail: compilation.error ?? 'Operator test JSON is invalid.',
            },
          },
        }));
        continue;
      }

      setOperatorTestResults((current) => ({
        ...current,
        [nodeId]: {
          ...(current[nodeId] ?? {}),
          [row.id]: {
            id: row.id,
            name: row.name.trim() || row.id,
            status: 'running',
            detail: 'Freezing the runtime binding and running its real one-node micro graph.',
            expectedInput: compilation.input,
            fixtureOutput: compilation.output,
          },
        },
      }));

      try {
        const run = await runOperatorTestCase(
          operator,
          compilation.input,
          compilation.output,
          compilation.transportResponse,
          row.id,
        );
        setOperatorTestResults((current) => ({
          ...current,
          [nodeId]: {
            ...(current[nodeId] ?? {}),
            [row.id]: evaluateOperatorTestResult(row, compilation, run),
          },
        }));
      } catch (cause: unknown) {
        setOperatorTestResults((current) => ({
          ...current,
          [nodeId]: {
            ...(current[nodeId] ?? {}),
            [row.id]: {
              id: row.id,
              name: row.name.trim() || row.id,
              status: 'failed',
              detail: String(cause),
              expectedInput: compilation.input,
              fixtureOutput: compilation.output,
            },
          },
        }));
      }
    }
  }, [canvasNodes, clearOperatorTestPublication, operatorByRef]);

  const publishOperatorTestRows = useCallback(async (
    nodeId: string,
    rowsToPublish: OperatorTestSuiteDraftRow[],
  ) => {
    if (rowsToPublish.length === 0) {
      return;
    }
    const node = canvasNodes.find((candidate) => candidate.id === nodeId);
    const operator = node ? operatorByRef.get(node.operatorRef) : undefined;
    if (!node || !operator) {
      setOperatorTestPublications((current) => ({
        ...current,
        [nodeId]: {
          status: 'failed',
          detail: node
            ? `Operator ${node.operatorRef} is not available in the current catalog.`
            : `Canvas node ${nodeId} is no longer available.`,
        },
      }));
      return;
    }

    const compiled = rowsToPublish.map((row) => ({ row, compilation: parseOperatorTestSuiteRow(row) }));
    const invalid = compiled.find(({ compilation }) => compilation.error);
    if (invalid) {
      setOperatorTestPublications((current) => ({
        ...current,
        [nodeId]: { status: 'failed', detail: invalid.compilation.error ?? 'Operator test JSON is invalid.' },
      }));
      return;
    }

    setError('');
    setOperatorTestPublications((current) => ({
      ...current,
      [nodeId]: {
        status: 'running',
        detail: `Publishing ${rowsToPublish.length} immutable case${rowsToPublish.length === 1 ? '' : 's'}...`,
      },
    }));
    setOperatorTestResults((current) => ({
      ...current,
      [nodeId]: Object.fromEntries(compiled.map(({ row, compilation }) => [row.id, {
          id: row.id,
          name: row.name.trim() || row.id,
          status: 'running' as const,
          detail: 'Registering an immutable fixture and publishing the exact suite revision.',
          expectedInput: compilation.input,
          fixtureOutput: compilation.output,
        }])),
    }));

    try {
      const run = await governOperatorTestSuite(
        operator,
        nodeId,
        compiled.map(({ row, compilation }) => ({
          caseId: row.id,
          caseType: row.caseType,
          name: row.name,
          input: compilation.input,
          expectedOutput: compilation.output,
          transportResponse: compilation.transportResponse,
        })),
      );
      const evaluated = evaluateGovernedOperatorSuite(rowsToPublish, run);
      setOperatorTestPublications((current) => ({
        ...current,
        [nodeId]: evaluated.publication,
      }));
      setOperatorTestResults((current) => ({
        ...current,
        [nodeId]: evaluated.caseResults,
      }));
    } catch (cause: unknown) {
      const detail = String(cause);
      setOperatorTestPublications((current) => ({
        ...current,
        [nodeId]: { status: 'failed', detail },
      }));
      setOperatorTestResults((current) => ({
        ...current,
        [nodeId]: Object.fromEntries(rowsToPublish.map((row) => [row.id, {
            id: row.id,
            name: row.name.trim() || row.id,
            status: 'failed' as const,
            detail,
          }])),
      }));
    }
  }, [canvasNodes, operatorByRef]);

  const runSimulation = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const response = await simulate(toSimulationRequest(
        graphName,
        canvasNodes,
        canvasEdges,
        outputNodeId,
        fixtureCompilation.fixtures,
        contextCompilation.value,
        graphInputSchema,
        effectiveGraphOutputSchema,
      ));
      showSimulationResponse(response);
    } catch (cause: unknown) {
      setError(String(cause));
    } finally {
      setBusy(false);
    }
  }, [
    canvasEdges,
    canvasNodes,
    contextCompilation.value,
    fixtureCompilation.fixtures,
    effectiveGraphOutputSchema,
    graphName,
    graphInputSchema,
    outputNodeId,
    showSimulationResponse,
  ]);

  const runSimulationTable = useCallback(async () => {
    setTableTestingBusy(true);
    setError('');
    const initialResults: Record<string, SimulationTableCaseResult> = {};
    for (const row of simulationTableRows) {
      const error = simulationTableCompilation.errors[row.id];
      if (error) {
        initialResults[row.id] = {
          id: row.id,
          name: row.name.trim() || row.id,
          status: 'failed',
          detail: error,
        };
      }
    }
    setSimulationTableResults(initialResults);

    try {
      for (const testCase of simulationTableCompilation.cases) {
        setSimulationTableResults((current) => ({
          ...current,
          [testCase.id]: {
            id: testCase.id,
            name: testCase.name,
            status: 'running',
            detail: 'Running simulate.',
          },
        }));
        try {
          const response = await simulate(toSimulationRequest(
            graphName,
            canvasNodes,
            canvasEdges,
            outputNodeId,
            mergeNodeFixtures(fixtureCompilation.fixtures, testCase.fixtures),
            testCase.context,
            graphInputSchema,
            effectiveGraphOutputSchema,
          ));
          showSimulationResponse(response);
          const rowResult = evaluateSimulationTableResult(testCase, response);
          setSimulationTableResults((current) => ({
            ...current,
            [testCase.id]: rowResult,
          }));
        } catch (cause: unknown) {
          setSimulationTableResults((current) => ({
            ...current,
            [testCase.id]: {
              id: testCase.id,
              name: testCase.name,
              status: 'failed',
              detail: String(cause),
              ...(testCase.hasExpectedOutput ? { expectedOutput: testCase.expectedOutput } : {}),
            },
          }));
        }
      }
    } finally {
      setTableTestingBusy(false);
    }
  }, [
    canvasEdges,
    canvasNodes,
    fixtureCompilation.fixtures,
    effectiveGraphOutputSchema,
    graphName,
    graphInputSchema,
    outputNodeId,
    showSimulationResponse,
    simulationTableCompilation,
    simulationTableRows,
  ]);

  const runDraftValidation = useCallback(async () => {
    setValidatingDraft(true);
    setError('');
    try {
      setValidationResult(await validateDraft(exportableDraft));
    } catch (cause: unknown) {
      setError(String(cause));
      setValidationResult(null);
    } finally {
      setValidatingDraft(false);
    }
  }, [exportableDraft]);

  const runAuthoringAction = useCallback((action: AuthoringJourneyAction) => {
    if (action.kind === 'focus-palette') {
      searchInputRef.current?.focus();
      searchInputRef.current?.select();
      return;
    }
    if (action.kind === 'select-node' && action.nodeId) {
      setSelectedNodeId(action.nodeId);
      if (action.guide === 'connection-guide') {
        setPendingConnectionGuideNodeId(action.nodeId);
      }
      return;
    }
    if (action.kind === 'simulate') {
      void runSimulation();
    }
  }, [runSimulation]);

  const runJourneyAction = useCallback(() => {
    runAuthoringAction(journey.action);
  }, [journey.action, runAuthoringAction]);

  const libraryHasWarnings = libraryDiagnostics.some(
    (diagnostic) => diagnostic.level?.trim().toUpperCase() === 'WARNING',
  );

  const autoLayout = useCallback(() => {
    setNodes((current) => autoLayoutFlowNodes(current, edges));
    if (typeof window.requestAnimationFrame === 'function') {
      window.requestAnimationFrame(() => fitCanvasToView());
    } else {
      fitCanvasToView();
    }
  }, [edges, fitCanvasToView]);

  const paletteView = useMemo(
    () => operatorPaletteView(operators, {
      search,
      facet: paletteFacet,
      sourceKind: sourceFilter,
      tag: tagFilter,
    }),
    [operators, paletteFacet, search, sourceFilter, tagFilter],
  );

  useEffect(() => {
    if (
      sourceFilter !== 'all'
      && !paletteView.sourceKindFacets.some((facet) => facet.key === sourceFilter)
    ) {
      setSourceFilter('all');
    }
    if (tagFilter !== 'all' && !paletteView.tagFacets.some((facet) => facet.key === tagFilter)) {
      setTagFilter('all');
    }
  }, [paletteView.sourceKindFacets, paletteView.tagFacets, sourceFilter, tagFilter]);

  const testTablePanel = (
    <section
      className={`simulation-test-table ${simulationTableRunSummary.state}`}
      data-testid="simulation-test-table"
    >
      <div className="test-table-header">
        <span>
          <strong>{simulationTableRunSummary.label}</strong>
          <small data-testid="test-table-summary">{simulationTableRunSummary.detail}</small>
        </span>
        <div className="test-table-actions">
          <button
            type="button"
            className="primary compact"
            data-testid="test-table-run"
            onClick={runSimulationTable}
            disabled={
              tableTestingBusy
              || nodes.length === 0
              || simulationTableRows.length === 0
              || hasFixtureErrors
            }
          >
            {tableTestingBusy ? 'Running' : 'Run Table'}
          </button>
          <button
            type="button"
            className="secondary compact"
            data-testid="test-table-add"
            onClick={addSimulationTableRow}
          >
            Add Case
          </button>
          <button
            type="button"
            className="secondary compact"
            data-testid="test-table-clear"
            onClick={clearSimulationTableResults}
            disabled={Object.keys(simulationTableResults).length === 0}
          >
            Clear
          </button>
        </div>
      </div>
      {hasSimulationTableErrors && (
        <p className="fixture-error" data-testid="test-table-error">
          {Object.values(simulationTableCompilation.errors)[0]}
        </p>
      )}
      {simulationTableRows.length > 0 ? (
        <ol className="test-table-list">
          {simulationTableRows.map((row, index) => {
            const rowResult = simulationTableResults[row.id];
            const rowError = simulationTableCompilation.errors[row.id];
            const rowStatus = rowResult?.status ?? (rowError ? 'failed' : 'pending');
            return (
              <li
                key={row.id}
                className={`test-table-row ${rowStatus}`}
                data-testid={`test-table-row:${index}`}
              >
                <div className="test-table-row-heading">
                  <input
                    aria-label={`Test case name ${index + 1}`}
                    data-testid={`test-table-name:${index}`}
                    value={row.name}
                    onChange={(event) => updateSimulationTableRow(row.id, { name: event.target.value })}
                  />
                  <span
                    className={`table-status ${rowStatus}`}
                    data-testid={`test-table-status:${index}`}
                  >
                    {rowStatus}
                  </span>
                  <button
                    type="button"
                    className="secondary compact"
                    aria-label={`Remove test case ${index + 1}`}
                    data-testid={`test-table-remove:${index}`}
                    onClick={() => removeSimulationTableRow(row.id)}
                  >
                    Remove
                  </button>
                </div>
                <label className="fixture-field">
                  <span>Context</span>
                  <textarea
                    aria-label={`Test case context ${index + 1}`}
                    data-testid={`test-table-context:${index}`}
                    spellCheck={false}
                    value={row.contextText}
                    onChange={(event) =>
                      updateSimulationTableRow(row.id, { contextText: event.target.value })}
                  />
                </label>
                <label className="fixture-field">
                  <span>Fixture Overrides</span>
                  <textarea
                    aria-label={`Test case fixture overrides ${index + 1}`}
                    data-testid={`test-table-fixtures:${index}`}
                    spellCheck={false}
                    value={row.fixturesText}
                    onChange={(event) =>
                      updateSimulationTableRow(row.id, { fixturesText: event.target.value })}
                  />
                </label>
                <label className="fixture-field">
                  <span>Expected Output</span>
                  <textarea
                    aria-label={`Test case expected output ${index + 1}`}
                    data-testid={`test-table-expected:${index}`}
                    spellCheck={false}
                    value={row.expectedOutputText}
                    onChange={(event) =>
                      updateSimulationTableRow(row.id, { expectedOutputText: event.target.value })}
                  />
                </label>
                {(rowResult || rowError) && (
                  <div className="test-table-result">
                    <strong>{rowResult?.detail ?? rowError}</strong>
                    {rowResult?.actualOutput !== undefined && (
                      <pre data-testid={`test-table-actual:${index}`}>
                        {JSON.stringify(rowResult.actualOutput, null, 2)}
                      </pre>
                    )}
                    {rowResult?.expectedOutput !== undefined && rowResult.status === 'failed' && (
                      <pre data-testid={`test-table-expected-result:${index}`}>
                        {JSON.stringify(rowResult.expectedOutput, null, 2)}
                      </pre>
                    )}
                  </div>
                )}
              </li>
            );
          })}
        </ol>
      ) : (
        <p className="muted">No test cases.</p>
      )}
    </section>
  );

  return (
    <div
      className={['workspace', canvasFocusMode ? 'canvas-focus' : ''].filter(Boolean).join(' ')}
      data-layout-mode={canvasFocusMode ? 'focus' : 'standard'}
    >
      <aside className="palette" id="operator-palette">
        <div className="palette-heading">
          <h2>Operators</h2>
          <span>
            {paletteView.matchingCount}/{paletteView.totalCount}
          </span>
        </div>
        <section className="library-intake" aria-label="Operator library intake" data-testid="library-intake">
          <div className="library-intake-heading">
            <h2>Library</h2>
            {libraryBusy && <span>Working</span>}
          </div>
          <textarea
            aria-label="Operator library JSON or YAML"
            data-testid="operator-library-source"
            spellCheck={false}
            placeholder="bloge.visualOperatorLibrary.v1 JSON/YAML, or bloge.capabilityCatalog.v1 for Adapt Catalog"
            value={librarySourceText}
            onChange={(event) => {
              setLibrarySourceText(event.target.value);
              setLibraryNotice(null);
              setLibraryDiagnostics([]);
              setLibraryWarningsAcknowledged(false);
              setLibraryWarningReason('');
              setDslRewriteGateResult(null);
            }}
          />
          <div className="library-examples" aria-label="Operator library examples">
            <span>Examples</span>
            <div className="library-example-buttons">
              {OPERATOR_LIBRARY_EXAMPLES.map((example) => (
                <button
                  key={example.key}
                  type="button"
                  className="library-example"
                  data-testid={`operator-library-example:${example.key}`}
                  onClick={() => {
                    setLibrarySourceText(example.sourceText);
                    setLibraryNotice({
                      level: 'pending',
                      message: example.key === 'capability-catalog'
                        ? `Loaded ${example.label} example. Adapt Catalog before validating.`
                        : `Loaded ${example.label} example. Validate before importing.`,
                    });
                    setLibraryDiagnostics([]);
                    setLibraryWarningsAcknowledged(false);
                    setLibraryWarningReason('');
                    setDslRewriteGateResult(null);
                  }}
                >
                  <strong>{example.label}</strong>
                  <span>{example.description}</span>
                </button>
              ))}
            </div>
          </div>
          <div className="library-actions">
            <button
              type="button"
              className="secondary compact"
              data-testid="operator-library-adapt-capability"
              onClick={adaptCapabilityCatalogSource}
              disabled={libraryBusy}
            >
              Adapt Catalog
            </button>
            <button
              type="button"
              className="secondary compact"
              data-testid="operator-library-validate"
              onClick={validateLibrarySource}
              disabled={libraryBusy}
            >
              Validate
            </button>
            <button
              type="button"
              className="primary compact"
              data-testid="operator-library-import"
              onClick={importLibrarySource}
              disabled={libraryBusy || (libraryHasWarnings
                && (!libraryWarningsAcknowledged || !libraryWarningReason.trim()))}
            >
              Import
            </button>
          </div>
          {libraryHasWarnings && (
            <div className="library-warning-ack" data-testid="operator-library-warning-ack">
              <label>
                <input
                  type="checkbox"
                  checked={libraryWarningsAcknowledged}
                  onChange={(event) => setLibraryWarningsAcknowledged(event.target.checked)}
                  data-testid="operator-library-ack-warnings"
                />
                <span>I reviewed the warning diagnostics</span>
              </label>
              <label>
                <span>Audit reason</span>
                <input
                  type="text"
                  value={libraryWarningReason}
                  onChange={(event) => setLibraryWarningReason(event.target.value)}
                  placeholder="Why this DESIGN-only import is acceptable"
                  data-testid="operator-library-warning-reason"
                />
              </label>
            </div>
          )}
          {libraryNotice && (
            <p className={`library-notice ${libraryNotice.level}`} data-testid="operator-library-notice">
              {libraryNotice.message}
            </p>
          )}
          {libraryDiagnostics.length > 0 && (
            <ol className="library-diagnostics">
              {libraryDiagnostics.slice(0, 3).map((diagnostic, index) => (
                <li key={`${diagnostic.code ?? 'diagnostic'}:${index}`}>
                  {diagnostic.code || diagnostic.level}: {diagnostic.message || diagnostic.target}
                </li>
              ))}
            </ol>
          )}
        </section>
        <section className="library-intake dsl-import" aria-label="Legacy DSL import" data-testid="legacy-dsl-import">
          <div className="library-intake-heading">
            <h2>Legacy DSL</h2>
            {dslImportBusy && <span>Rendering</span>}
            {dslCommitBusy && <span>Saving</span>}
            {dslRewriteGateBusy && <span>Checking</span>}
          </div>
          <label className="dsl-source-id">
            <span>Source</span>
            <input
              aria-label="DSL source id"
              data-testid="legacy-dsl-source-id"
              value={dslSourceId}
              onChange={(event) => {
                setDslSourceId(event.target.value);
                setDslRewriteGateResult(null);
              }}
            />
          </label>
          <textarea
            aria-label="BLOGE DSL source"
            data-testid="legacy-dsl-source"
            spellCheck={false}
            placeholder="graph migratedFlow { ... }"
            value={dslSourceText}
            onChange={(event) => {
              setDslSourceText(event.target.value);
              setDslImportNotice(null);
              setDslImportDiagnostics([]);
              setDslImportCoverage(null);
              setDslImportSourceMap(null);
              setDslImportRoundTrip(null);
              setDslRewriteGateResult(null);
            }}
          />
          <div className="library-examples" aria-label="Legacy DSL examples">
            <span>Examples</span>
            <div className="library-example-buttons">
              {LEGACY_DSL_EXAMPLES.map((example) => (
                <button
                  key={example.key}
                  type="button"
                  className="library-example"
                  data-testid={`legacy-dsl-example:${example.key}`}
                  onClick={() => {
                    setDslSourceId(example.sourceId);
                    setDslSourceText(example.sourceText);
                    setDslImportNotice({
                      level: 'pending',
                      message: `Loaded ${example.label}. Render to visualize.`,
                    });
                    setDslImportDiagnostics([]);
                    setDslImportCoverage(null);
                    setDslImportSourceMap(null);
                    setDslImportRoundTrip(null);
                    setDslRewriteGateResult(null);
                  }}
                >
                  <strong>{example.label}</strong>
                  <span>{example.sourceId}</span>
                </button>
              ))}
            </div>
          </div>
          <div className="library-actions">
            <button
              type="button"
              className="primary compact"
              data-testid="legacy-dsl-preview"
              onClick={previewLegacyDsl}
              disabled={dslImportBusy || dslCommitBusy || dslRewriteGateBusy}
            >
              Render DSL
            </button>
            <button
              type="button"
              className="secondary compact"
              data-testid="legacy-dsl-rewrite-gate"
              onClick={checkLegacyDslRewriteGate}
              disabled={dslImportBusy || dslCommitBusy || dslRewriteGateBusy}
            >
              Check Rewrite
            </button>
            <button
              type="button"
              className="secondary compact"
              data-testid="legacy-dsl-commit"
              onClick={commitLegacyDsl}
              disabled={dslImportBusy || dslCommitBusy || dslRewriteGateBusy}
            >
              Commit Draft
            </button>
          </div>
          {dslImportNotice && (
            <p className={`library-notice ${dslImportNotice.level}`} data-testid="legacy-dsl-notice">
              {dslImportNotice.message}
            </p>
          )}
          {dslImportCoverage && (
            <div className="dsl-import-stats" data-testid="legacy-dsl-coverage">
              <span>{dslImportCoverage.memberCount ?? 0} members</span>
              <span>{dslImportCoverage.projectedNodeCount ?? 0} nodes</span>
              <span>{dslImportCoverage.edgeCount ?? 0} edges</span>
            </div>
          )}
          {dslImportRoundTrip && (
            <div
              className={`dsl-round-trip ${dslRoundTripNoticeLevel(dslImportRoundTrip)}`}
              data-testid="legacy-dsl-round-trip"
            >
              <div className="dsl-round-trip-heading">
                <strong>Round trip</strong>
                <span>{dslImportRoundTrip.status || 'NOT_ASSESSED'}</span>
              </div>
              {dslImportRoundTrip.message && <p>{dslImportRoundTrip.message}</p>}
              <div className="dsl-round-trip-evidence">
                <span>{dslImportRoundTrip.generatedDsl ? 'Generated DSL' : 'No generated DSL'}</span>
                {dslImportRoundTrip.sourceFingerprint && <span>Source semantics</span>}
                {dslImportRoundTrip.generatedFingerprint && <span>Generated semantics</span>}
                {(dslImportRoundTrip.diagnostics?.length ?? 0) > 0 && (
                  <span>{dslImportRoundTrip.diagnostics?.length} diagnostics</span>
                )}
              </div>
            </div>
          )}
          {dslRewriteGateResult && (
            <div
              className={`dsl-rewrite-gate ${dslRewriteGateNoticeLevel(dslRewriteGateResult)}`}
              data-testid="legacy-dsl-rewrite-gate-result"
            >
              <div className="dsl-rewrite-gate-heading">
                <strong>Rewrite gate</strong>
                <span>{dslRewriteGateResult.decision || 'BLOCK_NOT_ASSESSED'}</span>
              </div>
              {dslRewriteGateResult.message && <p>{dslRewriteGateResult.message}</p>}
              <div className="dsl-round-trip-evidence">
                <span>{dslRewriteGateResult.allowed ? 'Auto rewrite allowed' : 'Auto rewrite blocked'}</span>
                {dslRewriteGateResult.generatedDsl && <span>Generated DSL ready</span>}
                {dslRewriteGateResult.roundTrip?.status && <span>{dslRewriteGateResult.roundTrip.status}</span>}
              </div>
            </div>
          )}
          {dslImportSourceRows.length > 0 && (
            <div className="dsl-source-map" data-testid="legacy-dsl-source-map">
              <div className="dsl-source-map-heading">
                <strong>Source map</strong>
                <span>{dslImportSourceRows.length} refs</span>
              </div>
              <ol>
                {dslImportSourceRows.slice(0, 8).map((row) => (
                  <li key={row.key}>
                    <button
                      type="button"
                      data-testid={`legacy-dsl-source-map-row:${row.key}`}
                      className={[
                        'dsl-source-map-row',
                        row.targetNodeId && row.targetNodeId === selectedNodeId ? 'selected' : '',
                      ].filter(Boolean).join(' ')}
                      disabled={!row.targetNodeId}
                      onClick={() => {
                        if (!row.targetNodeId) {
                          return;
                        }
                        setSelectedNodeId(row.targetNodeId);
                      }}
                    >
                      <span className="dsl-source-map-main">
                        <strong>{row.label}</strong>
                        <code>{row.kind} · {row.location}</code>
                      </span>
                      <span className="dsl-source-map-snippet">
                        {row.snippet || row.dslKind}
                      </span>
                    </button>
                  </li>
                ))}
              </ol>
            </div>
          )}
          {dslImportDiagnostics.length > 0 && (
            <ol className="library-diagnostics" data-testid="legacy-dsl-diagnostics">
              {dslImportDiagnostics.slice(0, 4).map((diagnostic, index) => (
                <li key={`${diagnostic.code ?? 'diagnostic'}:${index}`}>
                  {diagnostic.code || diagnostic.level}: {diagnostic.message || diagnostic.target}
                </li>
              ))}
            </ol>
          )}
        </section>
        <input
          id="operator-palette-search"
          ref={searchInputRef}
          aria-label="Search operators"
          aria-keyshortcuts="Meta+K Control+K"
          placeholder="Search…"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />
        <div className="palette-facets" role="group" aria-label="Operator runtime facet">
          {paletteView.runtimeFacets.map((facet) => (
            <button
              key={facet.key}
              type="button"
              className={`palette-facet ${paletteFacet === facet.key ? 'active' : ''}`}
              aria-pressed={paletteFacet === facet.key}
              onClick={() => setPaletteFacet(facet.key)}
            >
              <span>{facet.label}</span>
              <strong>{facet.count}</strong>
            </button>
          ))}
        </div>
        <div className="palette-selects">
          <select
            aria-label="Source kind filter"
            value={sourceFilter}
            onChange={(event) => setSourceFilter(event.target.value)}
          >
            <option value="all">Any source</option>
            {paletteView.sourceKindFacets.map((facet) => (
              <option key={facet.key} value={facet.key}>
                {facet.label} ({facet.count})
              </option>
            ))}
          </select>
          <select
            aria-label="Tag filter"
            value={tagFilter}
            onChange={(event) => setTagFilter(event.target.value)}
            disabled={paletteView.tagFacets.length === 0}
          >
            <option value="all">Any tag</option>
            {paletteView.tagFacets.map((facet) => (
              <option key={facet.key} value={facet.key}>
                {facet.label} ({facet.count})
              </option>
            ))}
          </select>
        </div>
        <div className="palette-groups">
          {paletteView.groups.map((group) => (
            <section
              className="palette-group"
              data-testid={`operator-group:${group.libraryId}`}
              key={group.libraryId}
            >
              <div className="palette-group-heading">
                <h3 title={group.libraryId}>{group.label}</h3>
                <span>{group.count}</span>
              </div>
              <ul className="operator-list">
                {group.rows.map(({ operator, summary }) => (
                  <li key={operator.operatorRef}>
                    <button
                      className="operator-button"
                      data-testid={`operator-button:${operator.operatorRef}`}
                      data-operator-ref={operator.operatorRef}
                      draggable
                      onDragStart={(event) => startOperatorDrag(event, operator)}
                      onClick={() => addOperator(operator)}
                      title={operator.operatorRef}
                    >
                      <span className="op-copy">
                        <span className={`op-kind ${summary.visualKind}`}>{summary.visualLabel}</span>
                        <span className="op-name">{summary.name}</span>
                        <span className="op-ref">{summary.operatorRef}</span>
                        <span className="op-meta">
                          {summary.contractHint} · {summary.requiredInputCount}/{summary.inputCount} inputs ·{' '}
                          {summary.outputCount} outputs
                        </span>
                      </span>
                      <span className="operator-badges">
                        {summary.sideEffectBadgeLabel && (
                          <span
                            className={`badge side-effect ${summary.managedWrite ? 'managed' : 'unmanaged'}`}
                            title={summary.sideEffectNotice}
                          >
                            {summary.sideEffectBadgeLabel}
                          </span>
                        )}
                        {summary.readinessBadgeLabel && (
                          <span className={`badge readiness ${summary.readinessLevel}`}>
                            {summary.readinessBadgeLabel}
                          </span>
                        )}
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          ))}
          {paletteView.groups.length === 0 && (
            <p className="muted">
              {operators.length === 0 ? 'No operators. Is the server running?' : 'No matching operators.'}
            </p>
          )}
        </div>
      </aside>

      <main className="canvas">
        <div className="journey-bar" aria-label="Authoring workflow">
          <ol className="journey-steps">
            {journey.steps.map((step) => (
              <li
                key={step.key}
                className={[
                  'journey-step',
                  step.state,
                  step.key === activeJourneyStepKey ? 'active' : '',
                ].filter(Boolean).join(' ')}
              >
                <span>{step.label}</span>
                <strong>{step.detail}</strong>
              </li>
            ))}
          </ol>
          {journey.action.kind !== 'none' && (
            <button
              type="button"
              className="secondary journey-action"
              onClick={runJourneyAction}
              disabled={busy}
            >
              {journey.action.label}
            </button>
          )}
          <span className="journey-count">
            {journey.completedCount}/{journey.steps.length}
          </span>
        </div>
        {deepLinkNotice && (
          <div
            className={`author-context-notice ${deepLinkNotice.level}`}
            data-testid="author-deep-link-notice"
            role={deepLinkNotice.level === 'error' ? 'alert' : 'status'}
          >
            {deepLinkNotice.message}
          </div>
        )}
        {deepLinkRun && (
          <section className={`run-context-strip ${deepLinkRun.success ? 'ok' : 'error'}`} data-testid="run-context-strip">
            <div className="context-strip-heading">
              <span>Run</span>
              <strong>{deepLinkRun.runId}</strong>
            </div>
            <dl className="context-strip-facts">
              <div><dt>Outcome</dt><dd>{deepLinkRun.success ? 'SUCCESS' : 'FAILED'}</dd></div>
              <div><dt>Source</dt><dd>{deepLinkRun.sourceKind || 'UNKNOWN'}</dd></div>
              <div><dt>Revision</dt><dd>{deepLinkRun.draftRevision ?? 0}</dd></div>
              <div><dt>Elapsed</dt><dd>{deepLinkRun.elapsedMs ?? 0} ms</dd></div>
            </dl>
            {deepLinkRun.errors?.[0] && <p>{deepLinkRun.errors[0]}</p>}
          </section>
        )}
        {(governanceGateBusy || governanceGateView) && (
          <section
            className={`governance-gate-strip ${governanceGateView ? governanceGateLevel(governanceGateView) : 'pending'}`}
            data-testid="governance-gate-strip"
            aria-label="Governance gate result"
          >
            <div className="context-strip-heading">
              <span>ANEKE Gate</span>
              <strong>{governanceGateBusy ? 'LOADING' : governanceGateView?.result?.status ?? 'NO RESULT'}</strong>
              {governanceGateView && <em>{governanceGateView.freshness}</em>}
            </div>
            {governanceGateView?.result?.issues.length ? (
              <ul className="governance-issue-list">
                {governanceGateView.result.issues.map((issue) => {
                  const issueNodeId = governanceIssueNodeId(issue);
                  return (
                    <li key={issue.issueId} data-severity={issue.severity.toLowerCase()}>
                      <button
                        type="button"
                        data-testid={`governance-issue:${issue.issueId}`}
                        onClick={() => focusGovernanceIssue(issue)}
                        title={issue.targetPath || issue.deepLink || issue.issueId}
                      >
                        <span>{issue.severity}</span>
                        <strong>{issue.code || issue.issueId}</strong>
                        <p>{issue.message}</p>
                        {issue.recommendedAction && <small>{issue.recommendedAction}</small>}
                        {issueNodeId && <em>{issueNodeId}</em>}
                      </button>
                    </li>
                  );
                })}
              </ul>
            ) : (
              !governanceGateBusy && <p className="governance-empty">No governance decision for this draft revision.</p>
            )}
          </section>
        )}
        <section className="canvas-examples" aria-label="Built-in canvas examples">
          <div className="canvas-examples-heading">
            <span>Examples</span>
            <strong>{canvasExamples.length}</strong>
          </div>
          <div className="canvas-example-list">
            {canvasExamples.map(({ template, missingOperatorRefs }) => {
              const available = missingOperatorRefs.length === 0;
              return (
                <article className={`canvas-example ${available ? '' : 'missing'}`} key={template.key}>
                  <div className="canvas-example-copy">
                    <span>{template.domain}</span>
                    <strong>{template.label}</strong>
                    <small>{template.pattern}</small>
                    <p>{template.description}</p>
                  </div>
                  <div className="canvas-example-meta">
                    <span>{template.nodes.length} nodes</span>
                    <span>{template.edges.length} edges</span>
                    <span>Input {graphSchemaSummary(template.inputSchema).fieldCount} fields</span>
                    <span>Output {graphSchemaSummary(template.outputSchema).fieldCount} fields</span>
                    {!available && <span>{missingOperatorRefs.length} missing</span>}
                  </div>
                  <button
                    type="button"
                    className="secondary compact"
                    data-testid={`canvas-example-load:${template.key}`}
                    onClick={() => loadCanvasExample(template)}
                    disabled={!available}
                    title={available ? `Load ${template.label}` : `Missing ${missingOperatorRefs.join(', ')}`}
                  >
                    Load
                  </button>
                </article>
              );
            })}
          </div>
        </section>
        <div className="toolbar">
          <button
            className="primary"
            onClick={runSimulation}
            disabled={busy || nodes.length === 0 || hasFixtureErrors || hasContextError}
            title={
              hasFixtureErrors
                ? 'Fix fixture JSON before simulating.'
                : hasContextError
                  ? 'Fix runtime context before simulating.'
                  : undefined
            }
          >
            {busy ? 'Simulating…' : 'Simulate'}
          </button>
          <button className="secondary" onClick={autoLayout} disabled={nodes.length < 2}>
            Auto Layout
          </button>
          <button
            className="secondary"
            data-testid="canvas-focus-toggle"
            aria-pressed={canvasFocusMode}
            onClick={() => setCanvasFocusMode((current) => !current)}
          >
            {canvasFocusMode ? 'Exit Focus' : 'Canvas Focus'}
          </button>
          <div className="zoom-toolbar" aria-label="Canvas zoom controls">
            <button
              type="button"
              className="secondary compact icon-button"
              aria-label="Zoom out"
              data-testid="author-zoom-out"
              onClick={() => zoomCanvasBy('out')}
              disabled={nodes.length === 0}
            >
              -
            </button>
            <button
              type="button"
              className="secondary compact zoom-level"
              aria-label="Reset zoom"
              data-testid="author-zoom-reset"
              onClick={resetCanvasZoom}
              disabled={nodes.length === 0}
            >
              {viewportZoomPercent}
            </button>
            <button
              type="button"
              className="secondary compact icon-button"
              aria-label="Zoom in"
              data-testid="author-zoom-in"
              onClick={() => zoomCanvasBy('in')}
              disabled={nodes.length === 0}
            >
              +
            </button>
            <button
              type="button"
              className="secondary compact"
              data-testid="author-fit-all"
              onClick={() => fitCanvasToView()}
              disabled={nodes.length === 0}
            >
              Fit All
            </button>
            <button
              type="button"
              className="secondary compact"
              data-testid="author-overview-toggle"
              aria-pressed={overviewVisible}
              onClick={() => setOverviewVisible((current) => !current)}
              disabled={nodes.length === 0}
            >
              {overviewVisible ? 'Map On' : 'Map Off'}
            </button>
          </div>
          <button
            className="secondary"
            data-testid="author-draft-validate"
            onClick={runDraftValidation}
            disabled={validatingDraft || nodes.length === 0}
          >
            {validatingDraft ? 'Validating...' : 'Validate'}
          </button>
          <a
            className={`toolbar-link ${nodes.length === 0 || hasFixtureErrors ? 'disabled' : ''}`}
            data-testid="author-draft-export"
            href={draftExportUrl}
            download={`${graphName}-draft.json`}
            aria-disabled={nodes.length === 0 || hasFixtureErrors}
            onClick={(event) => {
              if (nodes.length === 0 || hasFixtureErrors) {
                event.preventDefault();
              }
            }}
          >
            Export Draft
          </a>
          {result && (
            <span className={isRunSuccessful(result) ? 'status ok' : 'status fail'}>
              {isRunSuccessful(result) ? 'Success' : 'Blocked'}
            </span>
          )}
          <span className="canvas-chip">{canvasSummary.nodeCount} nodes</span>
          <span className="canvas-chip">{canvasSummary.edgeCount} edges</span>
          <span className="canvas-chip">Output {canvasSummary.outputNodeId || 'missing'}</span>
          {fixtureCount > 0 && (
            <span className="canvas-chip">
              {fixtureCount} fixture{fixtureCount === 1 ? '' : 's'}
            </span>
          )}
          {mockAttentionCount > 0 && (
            <span className="canvas-chip">Mock setup {mockAttentionCount}</span>
          )}
          {hasFixtureErrors && (
            <span className="connection-notice error">
              {fixtureErrorCount} fixture JSON error{fixtureErrorCount === 1 ? '' : 's'}
            </span>
          )}
          {connectionNotice && (
            <span className={`connection-notice ${connectionNotice.level}`}>
              {checkingConnection ? 'Checking...' : loadingCandidates ? 'Discovering...' : connectionNotice.message}
            </span>
          )}
          <span className="legend">
            <span className="swatch mocked" /> mocked
            <span className="swatch real" /> real
          </span>
        </div>
        <ContractRail
          source={graphContractSource}
          contract={contractDraft}
          contractFingerprint={contractFingerprint}
          scenarioDraftSet={scenarioDraftSet}
          inputFieldCount={graphInputSummary.fieldCount}
          outputFieldCount={graphOutputSummary.fieldCount}
          inputFields={graphInputSummary.fields.map((field) => field.name)}
          outputFields={graphOutputSummary.fields.map((field) => field.name)}
          onOpen={() => setContractWorkspaceOpen(true)}
        />
        <div
          ref={flowRef}
          className="flow"
          data-testid="author-flow"
          onDragOver={allowOperatorDrop}
          onDrop={dropOperatorOnFlow}
        >
          {coachPrompt && (
            <div
              className={[
                'canvas-coach',
                canvasSummary.nodeCount === 0 ? 'empty' : 'compact',
                coachPrompt.state,
              ].join(' ')}
              data-testid="canvas-coach"
            >
              <span className="canvas-coach-kicker">{coachPrompt.detail}</span>
              <strong>{coachPrompt.title}</strong>
              <span className="canvas-coach-body">{coachPrompt.body}</span>
              {coachPrompt.action.kind !== 'none' && (
                <button
                  type="button"
                  className="secondary compact"
                  onClick={() => runAuthoringAction(coachPrompt.action)}
                  disabled={busy}
                >
                  {coachPrompt.action.label}
                </button>
              )}
            </div>
          )}
          {nodes.length > 0 && (
            <div
              className={[
                'canvas-navigator',
                overviewVisible ? 'open' : 'collapsed',
                isComplexGraph ? 'complex' : '',
              ].filter(Boolean).join(' ')}
              data-testid="canvas-navigator"
            >
              <div className="canvas-navigator-head">
                <strong>{overviewLabel}</strong>
                <span data-testid="canvas-zoom-readout">{viewportZoomPercent}</span>
              </div>
              <div className="canvas-navigator-stats">
                <span>{canvasSummary.nodeCount} nodes</span>
                <span>{canvasSummary.edgeCount} edges</span>
              </div>
              <div className="canvas-navigator-actions">
                <button
                  type="button"
                  className="secondary compact"
                  data-testid="navigator-fit-all"
                  onClick={() => fitCanvasToView()}
                >
                  Fit All
                </button>
                <button
                  type="button"
                  className="secondary compact"
                  data-testid="navigator-map-toggle"
                  aria-pressed={overviewVisible}
                  onClick={() => setOverviewVisible((current) => !current)}
                >
                  {overviewVisible ? 'Hide' : 'Show'}
                </button>
              </div>
            </div>
          )}
          <ReactFlow
            nodes={flowNodes}
            edges={flowEdges}
            nodeTypes={NODE_TYPES}
            edgeTypes={EDGE_TYPES}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onConnectStart={onConnectStart}
            onConnectEnd={onConnectEnd}
            onInit={(instance) => {
              flowInstanceRef.current = instance;
              refreshViewportZoom();
            }}
            onMove={(_, viewport) => setViewportZoom(viewport.zoom)}
            onNodeClick={(_, node) => setSelectedNodeId(node.id)}
            onNodeDoubleClick={(_, node) => openNodeEditor(node)}
            onPaneClick={() => setSelectedNodeId('')}
            fitView
            fitViewOptions={{ padding: 0.12, minZoom: CANVAS_MIN_ZOOM, maxZoom: CANVAS_MAX_ZOOM }}
            minZoom={CANVAS_MIN_ZOOM}
            maxZoom={CANVAS_MAX_ZOOM}
          >
            <Background />
            <Controls />
            {overviewVisible && (
              <MiniMap
                className={`canvas-minimap ${isComplexGraph ? 'large' : 'compact'}`}
                nodeColor={minimapNodeColor}
                nodeStrokeColor={(node) => (node.selected ? '#1a56db' : '#ffffff')}
                nodeBorderRadius={4}
                nodeStrokeWidth={isComplexGraph ? 4 : 3}
                maskColor="rgb(15 23 42 / 10%)"
                maskStrokeColor="rgb(26 86 219 / 52%)"
                maskStrokeWidth={2}
                pannable
                zoomable
                zoomStep={0.7}
                ariaLabel="Canvas overview map"
              />
            )}
          </ReactFlow>
        </div>
      </main>

      <aside className="inspector">
        <h2>Checklist</h2>
        <ol className="checklist">
          {checklist.map((item) => (
            <li key={item.key} className={item.state}>
              <span>{item.label}</span>
              <strong>{item.detail}</strong>
            </li>
          ))}
        </ol>

        <h2>Mock Setup</h2>
        {fixtureRows.length > 0 ? (
          <ol className="mock-setup-list">
            {fixtureRows.map((row) => (
              <li key={row.nodeId}>
                <button
                  className={[
                    'mock-setup-row',
                    row.state,
                    row.nodeId === selectedNodeId ? 'selected' : '',
                  ].filter(Boolean).join(' ')}
                  onClick={() => setSelectedNodeId(row.nodeId)}
                  title={row.detail}
                >
                  <span className="mock-setup-copy">
                    <strong>{row.label}</strong>
                    <span>{row.operatorRef}</span>
                    <small>{row.detail}</small>
                  </span>
                  <span className="mock-setup-status">
                    <span className={`run-pill ${row.runMode}`}>{row.runMode}</span>
                    <code>{row.fixtureLabel}</code>
                  </span>
                </button>
              </li>
            ))}
          </ol>
        ) : (
          <p className="muted">No nodes.</p>
        )}

        <h2>Test Suite</h2>
        <section
          className={`test-suite-summary ${simulationTableRunSummary.state}`}
          data-testid="test-suite-summary"
        >
          <div>
            <span>{simulationTableRows.length} case{simulationTableRows.length === 1 ? '' : 's'}</span>
            <strong>{simulationTableRunSummary.label}</strong>
            <small>{simulationTableRunSummary.detail}</small>
          </div>
          <button
            type="button"
            className="primary compact"
            data-testid="test-suite-open"
            onClick={() => setTestSuiteOpen(true)}
          >
            Test Suite
          </button>
        </section>

        <h2>Runtime Context</h2>
        <ContextVariablesEditor
          rows={contextVariables}
          compilation={contextCompilation}
          selectedNodeId={selectedNodeId}
          rawJson={simulationContextDraft}
          onAdd={addContextVariable}
          onUpdate={updateContextVariable}
          onRemove={removeContextVariable}
          onBind={bindContextVariableToSelectedNode}
          onRawJsonChange={updateSimulationContextDraft}
        />

        <h2>Selected Node</h2>
        {selectedNode ? (
          <section className="node-detail">
            <h3>{selectedNode.data.label}</h3>
            <p className="muted">{selectedNode.data.operatorRef}</p>
            {selectedNode.data.summary.description && (
              <p>{selectedNode.data.summary.description}</p>
            )}
            <OperatorFocusPanel operator={selectedOperator} summary={selectedNode.data.summary} />
            <div className="port-list">
              <strong>Inputs</strong>
              <span>{selectedNode.data.summary.inputNames.join(', ') || 'none'}</span>
            </div>
            <div className="port-list">
              <strong>Outputs</strong>
              <span>{selectedNode.data.summary.outputNames.join(', ') || 'none'}</span>
            </div>
            <NodeInputBindingsEditor
              node={selectedNode}
              onAdd={addSelectedInputBinding}
              onRemove={removeSelectedInputBinding}
              onRename={renameSelectedInputBinding}
              onChange={updateSelectedInputBinding}
              onKindChange={updateSelectedInputBindingKind}
              onDropContextPath={bindContextVariableToSelectedNode}
            />
            <div className="connection-guide" data-testid="connection-guide">
              <div className="connection-guide-header">
                <strong>Connect Next</strong>
                <button
                  type="button"
                  className="secondary compact"
                  data-testid="connection-guide-refresh"
                  onClick={loadSelectedConnectionGuide}
                  disabled={connectionGuideBusy || nodes.length < 2}
                >
                  {connectionGuideBusy ? 'Finding' : 'Find Targets'}
                </button>
              </div>
              <label className="connection-source">
                <span>Source</span>
                <select
                  aria-label="Connection source output"
                  value={selectedConnectionSourcePort}
                  onChange={(event) => {
                    connectionGuideSequence.current += 1;
                    setSelectedConnectionSourcePort(event.target.value);
                    setConnectionGuide(null);
                    setConnectionGuideNotice(null);
                    setCandidatePreview(null);
                  }}
                  disabled={selectedOutputPorts.length <= 1}
                >
                  {selectedOutputPorts.map((port) => (
                    <option key={port || 'value'} value={port}>
                      {port || 'value'}
                    </option>
                  ))}
                </select>
              </label>
              {connectionGuideNotice && (
                <p className={`connection-guide-notice ${connectionGuideNotice.level}`}>
                  {connectionGuideNotice.message}
                </p>
              )}
              {selectedGuideRows.length > 0 ? (
                <ol className="connection-guide-list">
                  {selectedGuideRows.map((row) => (
                    <li
                      key={row.key}
                      className={row.status}
                      data-testid={`connection-guide-target:${row.targetNodeId}:${row.targetPort}`}
                    >
                      <div className="connection-guide-main">
                        <button
                          type="button"
                          className="connection-guide-target"
                          onClick={() => setSelectedNodeId(row.targetNodeId)}
                          title={`${row.detail} ${row.actionHint}`}
                        >
                          <span>
                            <strong>{row.targetLabel}</strong>
                            <small>{row.targetOperatorRef || row.targetNodeId}</small>
                            <code>{endpointLabel(row.targetPort, row.targetPath, 'input')}</code>
                            <small className="connection-guide-detail">{row.detail}</small>
                            <small className="connection-guide-action">{row.actionHint}</small>
                          </span>
                          <em>{row.status}</em>
                        </button>
                        {row.fieldOptions.length > 1 && (
                          <div className="connection-guide-fields" aria-label="Compatible field paths">
                            {row.fieldOptions.map((option) => (
                              <button
                                key={option.key}
                                type="button"
                                className={`connection-guide-field ${option.status}`}
                                data-testid={`connection-guide-field:${row.targetNodeId}:${row.targetPort}:${option.path}`}
                                onClick={() => connectGuideFieldOption(row, option)}
                                disabled={!option.accepted || connectionGuideBusy}
                                title={option.detail}
                              >
                                {option.label}
                              </button>
                            ))}
                          </div>
                        )}
                      </div>
                      <button
                        type="button"
                        className="secondary compact"
                        onClick={() => connectGuideRow(row)}
                        disabled={row.status !== 'ready' || connectionGuideBusy}
                      >
                        Connect
                      </button>
                    </li>
                  ))}
                </ol>
              ) : (
                <p className="muted">No targets loaded.</p>
              )}
            </div>
            <div className="output-control">
              <span>
                {selectedNode.id === outputNodeId
                  ? 'Selected as simulation output.'
                  : `Current output: ${outputNodeId || 'missing'}`}
              </span>
              <button
                type="button"
                className="secondary compact"
                onClick={setSelectedAsOutput}
                disabled={selectedNode.id === outputNodeId}
              >
                Set Output
              </button>
            </div>
            <div className="fixture-editor">
              <div className="fixture-header">
                <strong>Simulation</strong>
                <span className={`badge ${selectedFixtureHasDraft ? 'fixture' : ''}`}>
                  {selectedFixtureHasDraft ? 'custom' : 'server sample'}
                </span>
              </div>
              <div className="fixture-actions">
                <button
                  className="secondary compact"
                  onClick={useSelectedFixtureSample}
                  disabled={!selectedOperator}
                >
                  Use Sample
                </button>
                <button
                  className="secondary compact"
                  onClick={clearSelectedFixture}
                  disabled={!selectedFixtureHasDraft}
                >
                  Clear
                </button>
              </div>
              <label className="fixture-field">
                <span>Output Pin</span>
                <textarea
                  aria-label="Simulation output fixture JSON"
                  spellCheck={false}
                  placeholder="null"
                  value={selectedFixtureDraft}
                  onChange={(event) => updateSelectedFixtureDraft(event.target.value)}
                />
              </label>
              <label className="fixture-field">
                <span>Expected Input</span>
                <textarea
                  aria-label="Simulation expected input JSON"
                  spellCheck={false}
                  placeholder="{}"
                  value={selectedExpectedInputDraft}
                  onChange={(event) => updateSelectedExpectedInputDraft(event.target.value)}
                />
              </label>
              {selectedFixtureError && <p className="fixture-error">{selectedFixtureError}</p>}
            </div>
          </section>
        ) : (
          <p className="muted">No node selected.</p>
        )}

        <h2>Result</h2>
        {validationResult ? (
          <section
            className={`validation-summary ${validationResult.valid ? 'ok' : 'fail'}`}
            data-testid="draft-validation-summary"
          >
            <div className="validation-summary-heading">
              <span>{validationResult.valid ? 'Validated' : 'Needs repair'}</span>
              <strong>{validationResult.readiness?.title || (validationResult.valid ? 'Draft valid' : 'Draft invalid')}</strong>
            </div>
            {validationResult.readiness?.summary && (
              <p>{validationResult.readiness.summary}</p>
            )}
            <div className="validation-summary-chips">
              <span data-testid="draft-validation-summary:state">
                <span>Readiness</span>
                <strong>{validationResult.readiness?.state || 'unknown'}</strong>
              </span>
              <span data-testid="draft-validation-summary:actions">
                <span>Actions</span>
                <strong>{validationResult.actionReadiness?.state || 'unknown'}</strong>
              </span>
              <span data-testid="draft-validation-summary:diagnostics">
                <span>Diagnostics</span>
                <strong>{validationResult.diagnostics?.length ?? 0}</strong>
              </span>
            </div>
          </section>
        ) : (
          <p className="muted" data-testid="draft-validation-summary">Not validated.</p>
        )}
        <section className={`run-summary ${runSummary.state}`} data-testid="simulation-run-summary">
          <div className="run-summary-heading">
            <span>{runSummary.detail}</span>
            <strong>{runSummary.title}</strong>
          </div>
          <div className="run-summary-chips">
            {runSummary.chips.map((chip) => (
              <span
                key={chip.key}
                className={`run-summary-chip ${chip.state}`}
                data-testid={`simulation-run-summary:${chip.key}`}
              >
                <span>{chip.label}</span>
                <strong>{chip.value}</strong>
              </span>
            ))}
          </div>
        </section>
        {error && <pre className="error">{error}</pre>}
        {!result && !error && <p className="muted">No simulation result.</p>}
        {result && (
          <>
            <p>
              <strong>Mocked:</strong> {result.mockedNodeIds.join(', ') || '—'}
            </p>
            <p>
              <strong>Real:</strong> {result.realNodeIds.join(', ') || '—'}
            </p>
            {traceRows.length > 0 && (
              <>
                <h3>Trace</h3>
                <ol className="trace-list">
                  {traceRows.map((row) => (
                    <li key={row.nodeId}>
                      <button
                        className={`trace-row ${row.status}`}
                        onClick={() => setSelectedNodeId(row.nodeId)}
                      >
                        <span className="trace-copy">
                          <strong>{row.label}</strong>
                          <span>{row.operatorRef}</span>
                          <code>{row.outputPreview}</code>
                        </span>
                        <span className={`run-pill ${row.status}`}>{row.status}</span>
                      </button>
                    </li>
                  ))}
                </ol>
              </>
            )}
            <h3>Output</h3>
            <pre>{JSON.stringify(result.output, null, 2)}</pre>
            {result.diagnostics.length > 0 && (
              <>
                <h3>Diagnostics</h3>
                <ul>
                  {result.diagnostics.map((diagnostic, index) => (
                    <li key={index} className={`diag ${diagnostic.level ?? ''}`}>
                      {diagnostic.code}: {diagnostic.message}
                    </li>
                  ))}
                </ul>
              </>
            )}
            <h3>Generated DSL</h3>
            <pre className="dsl">{result.generatedDsl}</pre>
          </>
        )}
      </aside>
      {contractWorkspaceOpen && (
        <Suspense
          fallback={(
            <div className="canvas-loading-state" role="status">
              Opening Contract workspace...
            </div>
          )}
        >
          <ContractScenarioWorkspace
            open
            graphDraft={exportableDraft}
            contract={contractDraft}
            contractFingerprint={contractFingerprint}
            scenarioDraftSet={scenarioDraftSet}
            nodes={scenarioNodeOptions}
            lastRun={result}
            onScenarioDraftSetChange={setScenarioDraftSet}
            onSaveGraphDraft={saveGraphForScenario}
            onRebase={rebaseScenariosToCurrentContract}
            onRun={runScenarioSimulation}
            onClose={() => setContractWorkspaceOpen(false)}
          />
        </Suspense>
      )}
      {testSuiteOpen && (
        <div className="rule-editor-backdrop" role="presentation">
          <section
            className="test-suite-dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="test-suite-dialog-title"
            data-testid="test-suite-dialog"
          >
            <div className="operator-detail-heading">
              <span>Mock regression</span>
              <strong id="test-suite-dialog-title">Test Suite</strong>
              <button
                type="button"
                className="secondary compact"
                aria-label="Close test suite"
                onClick={() => setTestSuiteOpen(false)}
              >
                Done
              </button>
            </div>
            {testTablePanel}
          </section>
        </div>
      )}
      {operatorDetailNode && (
        <OperatorDetailDialog
          node={operatorDetailNode}
          operator={operatorDetailDefinition}
          incomingColumns={operatorDetailIncomingColumns}
          builtInFunctions={builtInFunctions}
          fixtureDraft={operatorDetailFixtureDraft}
          expectedInputDraft={operatorDetailExpectedInputDraft}
          hasFixtureDraft={operatorDetailFixtureHasDraft}
          fixtureError={operatorDetailFixtureError}
          operatorTestRows={operatorDetailTestRows}
          operatorTestResults={operatorDetailTestResults}
          operatorTestPublication={operatorDetailTestPublication}
          operatorTestsRunning={operatorDetailTestsRunning}
          operatorTestRunDisabledReason={operatorDetailTestRunDisabledReason}
          onClose={() => setOperatorDetailNodeId('')}
          onLabelChange={(value) => updateNodeLabel(operatorDetailNode.id, value)}
          onConfigPatch={(patch) => mergeNodeConfigPatch(operatorDetailNode.id, patch)}
          onConfigReplace={(config) => replaceNodeConfig(operatorDetailNode.id, config)}
          onInputAdd={() => addInputBindingForNode(operatorDetailNode.id)}
          onInputRemove={(bindingKey) => removeInputBindingForNode(operatorDetailNode.id, bindingKey)}
          onInputRename={(bindingKey, value) => renameInputBindingForNode(operatorDetailNode.id, bindingKey, value)}
          onInputChange={(bindingKey, patch) => updateInputBindingForNode(operatorDetailNode.id, bindingKey, patch)}
          onInputKindChange={(bindingKey, kind) =>
            updateInputBindingKindForNode(operatorDetailNode.id, bindingKey, kind)}
          onDropContextPath={(path) => bindContextPathToNode(operatorDetailNode.id, path)}
          onFixtureOutputChange={(value) => updateFixtureDraftForNode(operatorDetailNode.id, value)}
          onExpectedInputChange={(value) => updateExpectedInputDraftForNode(operatorDetailNode.id, value)}
          onUseFixtureSample={() => useFixtureSampleForNode(operatorDetailNode.id, operatorDetailDefinition)}
          onClearFixture={() => clearFixtureForNode(operatorDetailNode.id)}
          onOperatorTestAdd={() => addOperatorTestRow(operatorDetailNode, operatorDetailDefinition)}
          onOperatorTestUpdate={(rowId, patch) =>
            updateOperatorTestRow(operatorDetailNode, operatorDetailDefinition, rowId, patch)}
          onOperatorTestRemove={(rowId) =>
            removeOperatorTestRow(operatorDetailNode, operatorDetailDefinition, rowId)}
          onOperatorTestApplyFixture={(row) => applyOperatorTestFixture(operatorDetailNode.id, row)}
          onOperatorTestRun={(row) => {
            void runOperatorTestRows(operatorDetailNode.id, [row]);
          }}
          onOperatorTestRunAll={() => {
            void runOperatorTestRows(operatorDetailNode.id, operatorDetailTestRows);
          }}
          onOperatorTestGovern={(row) => {
            void publishOperatorTestRows(operatorDetailNode.id, [row]);
          }}
          onOperatorTestGovernAll={() => {
            void publishOperatorTestRows(operatorDetailNode.id, operatorDetailTestRows);
          }}
          onDecisionChange={(editor) => updateDecisionTableRules(operatorDetailNode.id, editor)}
          onTransformChange={(editor) => updateTransformAssignments(operatorDetailNode.id, editor)}
        />
      )}
    </div>
  );
}
