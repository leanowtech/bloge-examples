import {
  type CSSProperties,
  type DragEvent,
  type PointerEvent as ReactPointerEvent,
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
import { ChevronLeft, ChevronRight, CopyPlus, LockKeyhole, Minus, Plus } from 'lucide-react';
import 'reactflow/dist/style.css';

import {
  adaptCapabilityCatalogText,
  checkConnection,
  checkDslRewriteGate,
  commitDslImport,
  fetchBusinessMirrorLegacyProjection,
  fetchConnectionCandidates,
  fetchGatewayDiagram,
  fetchGatewayScenarios,
  fetchGovernanceGateView,
  fetchGraphDraft,
  fetchOperatorCatalog,
  fetchScenarioDraftSet,
  fetchScenarioGraphContract,
  fetchScenarioOperatorContract,
  fetchVisualGraphRun,
  forkWorkspace,
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
  canvasFocusPath,
  canvasNodeFocusState,
  canvasZoomPresentation,
  type CanvasEdge,
  type CanvasFocusPath,
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
  type SimulationTableTestCase,
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
  exampleIncompatibleContractPaths,
  exampleEdgeLabel,
  exampleRequiredOperatorRefs,
  hasOwnValue,
  maxNumericNodeId,
  type CanvasExampleAvailability,
  type CanvasExampleTestCase,
  type CanvasExampleTemplate,
} from './canvasExamples';
import {
  graphDraftFromBusinessMirrorSeed,
  parseBusinessMirrorGraphSeed,
} from './author/source/businessMirrorGraphSeed';
import ContractRail from './contract-scenario/ContractRail';
import {
  contractDraftFromGraphDraft,
  type ExactTargetRef,
  type EnterpriseScope,
  type ContractDraft,
  type ScenarioDraftSet,
  type VisualAuthoringWorkspaceBundle,
  visualLayoutWithContractSemantics,
} from './contract-scenario/domain';
import { canonicalJson, sha256Fingerprint } from './contract-scenario/fingerprint';
import type { ScenarioEvidenceTrustContext } from './contract-scenario/evidenceModel';
import type {
  ScenarioRunIntent,
  WorkspaceTab,
} from './contract-scenario/ContractScenarioWorkspace';
import {
  compareScenarioRun,
  rebaseScenarioDraftSet,
  scenarioDraftSetFromCanvas,
  scenarioDraftSetFromOperatorTableCases,
  scenarioSetIsCurrent,
  type LegacyOperatorTableCase,
  type LegacyTableProjectionDiagnostic,
  type ScenarioComparison,
  type ScenarioNodeOption,
} from './contract-scenario/scenarioAuthoring';
import {
  compileScenarioEditorSnapshotForSimulation,
  type ScenarioCompilationProof,
  verifyScenarioCompilationProof,
} from './contract-scenario/scenarioCompiler';
import { captureScenarioEditorSnapshot } from './contract-scenario/scenarioEditorModel';
import { workspaceForkCommand } from './author/workspace/workspaceSeed';
import {
  clearDslAuthorHandoff,
  peekDslAuthorHandoff,
} from './author/dslAuthorHandoff';
import AuthorCommandBar from './author/shell/AuthorCommandBar';
import { useI18n } from './i18n/I18nProvider';
import type { MessageDescriptor } from './i18n/messageCatalog';
import AuthorContextInspector from './author/shell/AuthorContextInspector';
import AuthorSurfaceRouter from './author/shell/AuthorSurfaceRouter';
import TopologyContextRail from './author/shell/TopologyContextRail';
import StartImportDialog, {
  type StartImportSection,
} from './author/shell/StartImportDialog';
import type { AuthorMode } from './author/shell/authorWorkspaceState';
import {
  projectAuthorTaskState,
  type AuthorCommandAvailability,
} from './author/task/taskStateProjection';
import { evaluateTaskCommandAuthority } from './author/task/commandAuthority';
import ProductionCommandDialog from './author/task/ProductionCommandDialog';
import {
  parseTaskCoordinate,
  parseTaskReturnCoordinate,
  taskCoordinateUrl,
  taskReturnHref,
  type TaskCoordinate,
} from './author/task/taskCoordinate';
import {
  authorWorkspaceUrl,
  parseAuthorWorkspaceLocation,
} from './author/shell/authorWorkspaceLocation';
import AuthorDiagnosticsDrawer from './author/review/AuthorDiagnosticsDrawer';
import {
  projectAuthorDiagnostics,
  type AuthorDiagnosticItem,
} from './author/review/authorDiagnostics';
import { operatorScenarioGraphDraft } from './author/contract/operatorScenarioGraphDraft';
import {
  resolveNodeEditor,
  type NodeEditorTab,
} from './author/node-editor/nodeEditorRegistry';
import GraphRunInputPanel, {
  ContextExtrasPanel,
  RawRunContextPanel,
} from './author/input/GraphRunInputPanel';
import {
  assessRunInput,
  compileTaskRunContext,
  reconcileRunInputWithSchema,
} from './author/input/authorRunInput';
import ExternalApiAuthoring from './external-api/ExternalApiAuthoring';
import { parseToolCoordinate, resolveSpine } from './spine/authorSpine';
import type { DecisionEditorSnapshot } from './decision-scenario/decisionScenarioModel';
import ToolAuthoringPanel from './tool/ToolAuthoringPanel';
import ToolPaletteFacets from './tool/ToolPaletteFacets';
import type { ToolPublicationMetadata } from './tool/toolModel';
import {
  fetchGovernedFixtureAssets,
  promoteGraphNodeFixture,
  type GovernedFixtureAssetSummary,
  type GovernedGraphNodeFixtureRef,
  type GraphNodeFixtureState,
  type PickerAsset,
  type ResourceFidelity,
} from './fixture-asset';
import useDialogFocusTrap from './author/accessibility/useDialogFocusTrap';
import {
  authorTaskElapsedMs,
  recordAuthorTaskEvent,
} from './author/telemetry/authorTaskTelemetry';
import {
  useWorkspaceContinuity,
  type WorkspaceSaveAttempt,
} from './author/continuity/useWorkspaceContinuity';
import SaveConflictResolutionDialog from './author/continuity/SaveConflictResolutionDialog';
import type { SaveConflictSnapshot } from './author/continuity/saveConflictModel';
import EffectiveContractPanel from './author/contract/EffectiveContractPanel';
import NodeDeletionImpactDialog, {
  MutationNotice,
} from './author/mutations/NodeDeletionImpactDialog';
import {
  createMutation,
  initialMutationJournal,
  markSavedCheckpoint,
  mutationFingerprint,
  mutationJournalForRecovery,
  projectNodeDeletionImpact,
  recordMutation,
  redoMutation,
  restoreMutationJournal,
  undoMutation,
  type AssetImpact,
  type MutationJournalState,
  type MutationKind,
  type NodeDeletionImpact,
} from './author/mutations/reversibleMutationJournal';
import {
  projectEffectiveContract,
  schemaFromAcceptedInference,
  type EffectiveContractField,
  type EffectiveContractProjection,
  type EffectiveInputBinding,
} from './author/contract/effectiveContractProjection';
import { projectAuthorReadiness } from './author/readiness/authorReadiness';
import CanvasTaskNavigator, {
  type CanvasTaskMode,
} from './author/canvas/CanvasTaskNavigator';
import {
  adaptiveCanvasChromePolicy,
  assessCanvasPerceptualQuality,
  projectCanvasSemantics,
  semanticZoomContract,
  type AdaptiveCanvasChromeReason,
  type CanvasPanelPreference,
  type CanvasSemanticProjection,
} from './author/canvas/canvasSemantics';
import { containedViewportTransform } from './author/canvas/viewportContainment';
import {
  assessCanvasLayout,
  constrainCanvasLayout,
  type CanvasLayoutQualityReport,
} from './author/canvas/layoutQuality';
import {
  canvasGraphArea,
  decideLayoutAcceptance,
  estimateCanvasFitZoom,
  overrideLayoutAcceptance,
  projectLayoutQualitySnapshot,
  type LayoutAcceptanceDecision,
} from './author/canvas/layoutAcceptance';

const ContractScenarioWorkspace = lazy(
  () => import('./contract-scenario/ContractScenarioWorkspace'),
);
const DecisionScenarioWorkbench = lazy(
  () => import('./decision-scenario/DecisionScenarioWorkbench').then((module) => ({
    default: module.DecisionScenarioWorkbench,
  })),
);
const GraphNodeFixturePicker = lazy(() => import('./fixture-asset/GraphNodeFixtureControls')
  .then((module) => ({ default: module.GraphNodeFixturePicker })));
const FixtureStalenessNotice = lazy(() => import('./fixture-asset/GraphNodeFixtureControls')
  .then((module) => ({ default: module.FixtureStalenessNotice })));
const ResourceFidelitySelect = lazy(() => import('./fixture-asset/GraphNodeFixtureControls')
  .then((module) => ({ default: module.ResourceFidelitySelect })));
const SimulationFixtureControls = lazy(() => import('./fixture-asset/GraphNodeFixtureControls')
  .then((module) => ({ default: module.SimulationFixtureControls })));

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
  pathFocus?: 'active' | 'dimmed';
  pinned?: boolean;
}

function graphConflictSnapshot(
  draft: GraphDraft,
  fingerprint: string,
  scenarioDraftSet: ScenarioDraftSet | null | undefined,
): SaveConflictSnapshot {
  return {
    revision: draft.revision ?? 0,
    fingerprint,
    facts: [
      { id: 'name', label: 'Graph name', value: draft.graphName },
      { id: 'nodes', label: 'Nodes', value: draft.nodes.length },
      { id: 'edges', label: 'Edges', value: draft.edges.length },
      { id: 'fixtures', label: 'Fixtures', value: Object.keys(draft.nodeFixtures ?? {}).length },
      ...(scenarioDraftSet === undefined
        ? []
        : [{ id: 'scenarios', label: 'Scenarios', value: scenarioDraftSet?.scenarios.length ?? 0 }]),
    ],
  };
}

interface LayoutUndoSnapshot {
  positions: Record<string, { x: number; y: number }>;
  movedNodeCount: number;
}

interface LayoutPreviewSnapshot extends LayoutUndoSnapshot {
  quality: CanvasLayoutQualityReport;
  acceptance: LayoutAcceptanceDecision;
  durationMs: number;
}

interface OperatorDetailBaseline {
  nodeId: string;
  nodeData: NodeData;
  fixtureDraft?: string;
  fixtureInputDraft?: string;
  testRows?: OperatorTestSuiteDraftRow[];
}

interface ConnectionNotice {
  level: 'ok' | 'warning' | 'error' | 'pending';
  message: string;
}

interface OperatorContractWorkspaceState {
  graphDraft: GraphDraft;
  contract: ContractDraft;
  contractFingerprint: string;
  scenarioDraftSet: ScenarioDraftSet;
  nodes: ScenarioNodeOption[];
}

interface ScenarioReviewEvidence {
  scenarioId: string;
  comparison: ScenarioComparison;
  response: SimulationResponse;
  coordinate: {
    contentEpoch: number;
    targetKind: 'GRAPH' | 'OPERATOR';
    targetId: string;
    targetRevision: number;
    draftId: string;
    draftRevision: number;
    draftFingerprint: string;
    contractFingerprint: string;
    scenarioId: string;
    scenarioRevision: number;
    scenarioFingerprint: string;
    closureFingerprint: string;
    requestFingerprint: string;
    editorSnapshotFingerprint?: string;
    compiledPlanSourceFingerprint?: string;
    requestSourceFingerprint?: string;
    evidenceSourceFingerprint?: string;
  };
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
  viewportZoom?: number;
  pathFocus?: 'active' | 'dimmed';
  semanticLabel?: string;
  semanticLabelTitle?: string;
  bundledFieldCount?: number;
  semanticLabelX?: number;
  semanticLabelY?: number;
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
const EDGE_LABEL_LANE_STEP = 42;
const CANVAS_MIN_ZOOM = 0.04;
const CANVAS_MAX_ZOOM = 1.8;
const COMPLEX_GRAPH_NODE_THRESHOLD = 24;
const COMPLEX_GRAPH_EDGE_THRESHOLD = 36;
const COMPACT_AUTHOR_MEDIA = '(max-width: 1100px)';
const CANVAS_PANEL_PREFERENCE_PREFIX = 'resourceGateway.author.canvasPanel.';

function initialCanvasPanelPreference(panel: 'palette' | 'inspector'): CanvasPanelPreference {
  try {
    const stored = window.localStorage.getItem(`${CANVAS_PANEL_PREFERENCE_PREFIX}${panel}`);
    return stored === 'open' || stored === 'closed' ? stored : 'auto';
  } catch {
    return 'auto';
  }
}

function compactAuthorFingerprint(value: string | undefined): string {
  const normalized = value?.trim() ?? '';
  if (normalized.length <= 22) {
    return normalized;
  }
  return `${normalized.slice(0, 13)}...${normalized.slice(-6)}`;
}

function workspaceTabForMode(mode: AuthorMode): WorkspaceTab {
  if (mode === 'scenarios') {
    return 'scenarios';
  }
  if (mode === 'evidence') {
    return 'evidence';
  }
  return 'interface';
}

function authorModeForWorkspaceTab(tab: WorkspaceTab): AuthorMode {
  if (tab === 'scenarios') {
    return 'scenarios';
  }
  if (tab === 'evidence') {
    return 'evidence';
  }
  return 'contract';
}

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
  const { t } = useI18n();
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
  const rawLabelText = typeof label === 'string' || typeof label === 'number' ? String(label) : '';
  const labelText = data?.semanticLabel !== undefined
    ? data.semanticLabel
    : data?.pathFocus === 'dimmed' ? '' : rawLabelText;
  const bundledLabelMatch = labelText.match(/^(\d+) fields \/ (\d+) targets? · (.+)$/);
  const localizedLabelText = bundledLabelMatch
    ? t('{fields} fields / {targets} targets · {paths}', {
      fields: bundledLabelMatch[1],
      targets: bundledLabelMatch[2],
      paths: bundledLabelMatch[3],
    })
    : labelText;
  const labelLane = data?.labelLane ?? 0;
  const labelOffsetX = Math.abs(targetY - sourceY) > 60
    ? targetY > sourceY
      ? -EDGE_LABEL_DIAGONAL_OFFSET
      : EDGE_LABEL_DIAGONAL_OFFSET
    : 0;
  const labelOffsetY = labelLane * EDGE_LABEL_LANE_STEP;
  const renderedLabelX = data?.semanticLabelX ?? labelX + labelOffsetX;
  const renderedLabelY = data?.semanticLabelY ?? labelY + labelOffsetY;

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
      {localizedLabelText && (
        <EdgeLabelRenderer>
          <div
            className={['flow-edge-label', selected ? 'selected' : ''].filter(Boolean).join(' ')}
            data-edge-id={id}
            data-testid="canvas-edge-label"
            data-field-count={data?.bundledFieldCount ?? 1}
            title={data?.semanticLabelTitle || localizedLabelText}
            style={{
              transform: `translate(-50%, -50%) translate(${renderedLabelX}px, ${renderedLabelY}px)`,
            }}
          >
            {localizedLabelText}
          </div>
        </EdgeLabelRenderer>
      )}
    </>
  );
}

function adaptiveChromeReasonMessage(reason: AdaptiveCanvasChromeReason): MessageDescriptor {
  switch (reason) {
    case 'TASK_SURFACE':
      return { messageId: 'layout.chrome.taskSurface' };
    case 'COMPACT_WORKSPACE':
      return { messageId: 'layout.chrome.compactWorkspace' };
    case 'GRAPH_OVERVIEW':
      return { messageId: 'layout.chrome.graphOverview' };
    case 'READABILITY_FLOOR':
      return { messageId: 'layout.chrome.readabilityFloor' };
  }
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

function constrainedAutoLayoutFlowNodes(
  flowNodes: Node<NodeData>[],
  flowEdges: Edge<CanvasEdgeData>[],
  pinnedNodeIds: ReadonlySet<string>,
): Node<NodeData>[] {
  const candidate = autoLayoutFlowNodes(flowNodes, flowEdges);
  const constrained = constrainCanvasLayout(
    flowNodes.map(canvasNodeFromFlowNode),
    candidate.map(canvasNodeFromFlowNode),
    pinnedNodeIds,
    flowEdges.map(canvasEdgeFromFlowEdge),
  );
  const positions = new Map(constrained.map((node) => [node.id, node.position]));
  return candidate.map((node) => ({
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
  return `${edge.source}::${edge.target}`;
}

function withEdgeLabelLanes(
  edges: Edge<CanvasEdgeData>[],
  viewportZoom: number,
  focusPath: CanvasFocusPath,
  focusActive: boolean,
  semantics: CanvasSemanticProjection,
): Edge<CanvasEdgeData>[] {
  const groups = new Map<string, Edge<CanvasEdgeData>[]>();
  for (const edge of edges) {
    const key = edgeParallelKey(edge);
    groups.set(key, [...(groups.get(key) ?? []), edge]);
  }

  return edges.map((edge) => {
    const group = groups.get(edgeParallelKey(edge)) ?? [edge];
    const index = group.findIndex((candidate) => candidate.id === edge.id);
    const count = group.length;
    const pathFocus = focusActive
      ? focusPath.edgeIds.has(edge.id) ? 'active' : 'dimmed'
      : undefined;
    const semanticLabel = semantics.edgeLabels.get(edge.id);
    return {
      ...edge,
      type: CANVAS_DATA_EDGE_TYPE,
      className: [
        edge.className,
        pathFocus ? `focus-${pathFocus}` : '',
      ].filter(Boolean).join(' '),
      interactionWidth: edge.interactionWidth ?? EDGE_LABEL_OPTIONS.interactionWidth,
      labelShowBg: false,
      data: {
        ...(edge.data ?? {}),
        labelLane: edgeLaneFor(Math.max(0, index), count),
        viewportZoom,
        pathFocus,
        semanticLabel: semanticLabel?.text ?? '',
        semanticLabelTitle: semanticLabel?.title ?? '',
        bundledFieldCount: semanticLabel?.fieldCount ?? 0,
        semanticLabelX: semanticLabel?.x,
        semanticLabelY: semanticLabel?.y,
        ...(semanticLabel ? { labelLane: semanticLabel.lane } : {}),
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
  outputKind?: 'scalar' | 'object' | 'plan' | 'dispatch';
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
  inputMetricKind: 'ports' | 'mappings';
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

interface AuthoringRecoveryPayload {
  graphDraft: GraphDraft;
  scenarioDraftSet: ScenarioDraftSet | null;
  fixtureDrafts: Record<string, string>;
  fixtureInputDrafts: Record<string, string>;
  operatorTestSuites: Record<string, OperatorTestSuiteDraftRow[]>;
  simulationTableRows: SimulationTableTestDraftRow[];
  runInputValue: Record<string, unknown>;
  simulationContextDraft: string;
  rawContextMode: boolean;
  contextVariables: ContextVariableRow[];
  selectedNodeId: string;
  explicitOutputNodeId: string;
  authorMode: AuthorMode;
  loadedExampleKey: string;
  workspaceForkIdempotencyKey: string;
  mutationJournal?: MutationJournalState<AuthoringMutationSnapshot>;
}

interface AuthorGraphSaveConflict {
  localDraft: GraphDraft;
  localScenarioDraftSet: ScenarioDraftSet | null;
  localFingerprint: string;
  authoritative: GraphDraft | null;
  authoritativeFingerprint: string;
  loading: boolean;
  busyAction: 'fork' | 'reload' | '';
  error: string;
  forkIdempotencyKey: string;
}

function authoringRecoveryFingerprintValue(payload: AuthoringRecoveryPayload): unknown {
  return {
    graphDraft: payload.graphDraft,
    scenarioDraftSet: payload.scenarioDraftSet,
    fixtureDrafts: payload.fixtureDrafts,
    fixtureInputDrafts: payload.fixtureInputDrafts,
    operatorTestSuites: payload.operatorTestSuites,
    simulationTableRows: payload.simulationTableRows,
    runInputValue: payload.runInputValue,
    simulationContextDraft: payload.simulationContextDraft,
    rawContextMode: payload.rawContextMode,
    contextVariables: payload.contextVariables,
  };
}

function isAuthoringRecoveryPayload(value: unknown): value is AuthoringRecoveryPayload {
  if (!isRecord(value) || !isRecord(value.graphDraft)) return false;
  const graphDraft = value.graphDraft;
  return typeof graphDraft.graphName === 'string'
    && Array.isArray(graphDraft.nodes)
    && Array.isArray(graphDraft.edges)
    && (value.scenarioDraftSet === null || isRecord(value.scenarioDraftSet))
    && isRecoveryStringRecord(value.fixtureDrafts)
    && isRecoveryStringRecord(value.fixtureInputDrafts)
    && isRecoveryArrayRecord(value.operatorTestSuites)
    && Array.isArray(value.simulationTableRows)
    && isRecord(value.runInputValue)
    && typeof value.simulationContextDraft === 'string'
    && typeof value.rawContextMode === 'boolean'
    && Array.isArray(value.contextVariables)
    && typeof value.selectedNodeId === 'string'
    && typeof value.explicitOutputNodeId === 'string'
    && (value.authorMode === 'compose'
      || value.authorMode === 'contract'
      || value.authorMode === 'scenarios'
      || value.authorMode === 'evidence')
    && typeof value.loadedExampleKey === 'string'
    && typeof value.workspaceForkIdempotencyKey === 'string';
}

function isRecoveryStringRecord(value: unknown): value is Record<string, string> {
  return isRecord(value) && Object.values(value).every((entry) => typeof entry === 'string');
}

function isRecoveryArrayRecord(value: unknown): value is Record<string, unknown[]> {
  return isRecord(value) && Object.values(value).every(Array.isArray);
}

interface AuthoringMutationSnapshot {
  nodes: Node<NodeData>[];
  edges: Edge<CanvasEdgeData>[];
  fixtureDrafts: Record<string, string>;
  fixtureInputDrafts: Record<string, string>;
  operatorTestSuites: Record<string, OperatorTestSuiteDraftRow[]>;
  operatorTestResults: Record<string, Record<string, OperatorTestCaseResult>>;
  operatorTestPublications: Record<string, OperatorTestSuitePublicationResult>;
  simulationTableRows: SimulationTableTestDraftRow[];
  simulationTableResults: Record<string, SimulationTableCaseResult>;
  runInputValue: Record<string, unknown>;
  simulationContextDraft: string;
  rawContextMode: boolean;
  contextVariables: ContextVariableRow[];
  scenarioDraftSet: ScenarioDraftSet | null;
  contractDraft: ContractDraft | null;
  contractFingerprint: string;
  explicitOutputNodeId: string;
  selectedNodeId: string;
  pinnedNodeIds: string[];
  graphName: string;
  graphInputSchema: SchemaEnvelope;
  graphOutputSchema: SchemaEnvelope | null;
  graphContractSource: string;
  graphVisualLayout: Record<string, unknown>;
  graphOperatorFingerprints: Record<string, string>;
  graphOperatorSnapshots: Record<string, OperatorDefinition>;
  loadedExampleKey: string;
}

interface PendingMutationDescriptor {
  kind: MutationKind;
  label: string;
  subjectRef: string;
  impact?: AssetImpact[];
  coalesceKey?: string;
}

interface PendingNodeDeletion {
  nodeIds: string[];
  nodeLabels: string[];
  impact: NodeDeletionImpact;
  productionSafeguard: boolean;
}

interface PendingProductionCommand {
  commandLabel: string;
  targetLabel: string;
  execute: () => void;
}

interface AuthorMutationNotice {
  message: string;
  action: 'undo' | 'redo';
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
  if (summary.visualKind === 'transform' && config) {
    const assignmentCount = isRecord(config.assignments) ? Object.keys(config.assignments).length : 0;
    return {
      requiredInputCount: assignmentCount,
      inputCount: assignmentCount,
      outputCount: summary.outputCount,
      inputMetricKind: 'mappings',
    };
  }
  if (summary.visualKind !== 'decision-table' || !config) {
    return {
      requiredInputCount: summary.requiredInputCount,
      inputCount: summary.inputCount,
      outputCount: summary.outputCount,
      inputMetricKind: 'ports',
    };
  }
  const editor = decisionTableEditorModel(config);
  const inputCount = editor.conditionColumns.length || summary.inputCount;
  return {
    requiredInputCount: inputCount,
    inputCount,
    outputCount: editor.outputColumns.length || summary.outputCount,
    inputMetricKind: 'ports',
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
    caseType: testCase.caseType,
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

function tableCaseScenarioComparison(
  testCase: SimulationTableTestCase,
  response: SimulationResponse,
  rowResult: SimulationTableCaseResult,
): ScenarioComparison {
  const executionPassed = isRunSuccessful(response);
  return {
    passed: executionPassed && rowResult.status === 'passed',
    results: testCase.hasExpectedOutput
      ? [{
          assertionId: `${testCase.id}-output`,
          passed: rowResult.status === 'passed',
          path: '',
          expected: testCase.expectedOutput,
          actual: response.output,
          detail: rowResult.detail,
        }]
      : [],
    diagnostics: executionPassed
      ? []
      : [{
          level: 'ERROR',
          code: 'visual.scenario.run.failed',
          message: response.errors?.[0] ?? 'Simulation did not complete successfully.',
          target: '/run',
        }],
  };
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

function projectLegacyOperatorTableRows(rows: OperatorTestSuiteDraftRow[]): {
  cases: LegacyOperatorTableCase[];
  diagnostics: LegacyTableProjectionDiagnostic[];
} {
  const cases: LegacyOperatorTableCase[] = [];
  const diagnostics: LegacyTableProjectionDiagnostic[] = [];
  rows.forEach((row) => {
    const compilation = parseOperatorTestSuiteRow(row);
    if (compilation.error) {
      diagnostics.push({
        caseId: row.id,
        message: compilation.error,
        raw: { ...row },
      });
      return;
    }
    cases.push({
      id: row.id,
      name: row.name.trim() || row.id,
      caseType: row.caseType,
      input: compilation.input,
      expectedOutput: compilation.output,
    });
  });
  return { cases, diagnostics };
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
  incomingEdges = [],
  graphNodes = [],
  onAdd,
  onRemove,
  onRename,
  onChange,
  onKindChange,
  onDropContextPath,
}: {
  node: Node<NodeData>;
  incomingEdges?: Edge<CanvasEdgeData>[];
  graphNodes?: Node<NodeData>[];
  onAdd: () => void;
  onRemove: (bindingKey: string) => void;
  onRename: (bindingKey: string, value: string) => void;
  onChange: (bindingKey: string, patch: Partial<DraftNodeBinding>) => void;
  onKindChange: (bindingKey: string, kind: 'contextPath' | 'constant') => void;
  onDropContextPath: (path: string) => void;
}) {
  const { t } = useI18n();
  const inputPorts = node.data.summary.inputNames.length ? node.data.summary.inputNames : ['inputs'];
  const rows = Object.entries(node.data.inputs ?? {});
  const edgeRows = incomingEdges
    .filter((edge) => edge.target === node.id && (!edge.data?.kind || edge.data.kind === 'data'))
    .map((edge) => ({
      id: edge.id,
      sourceLabel: graphNodes.find((candidate) => candidate.id === edge.source)?.data.label ?? edge.source,
      sourcePort: portNameFromHandle(edge.sourceHandle, 'out'),
      sourcePath: edge.data?.sourcePath ?? (edge as CanvasFlowEdge).sourcePath ?? '',
      targetPort: portNameFromHandle(edge.targetHandle, 'in'),
      targetPath: edge.data?.targetPath ?? (edge as CanvasFlowEdge).targetPath ?? '',
    }));
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
        <strong>{t('Node Inputs')}</strong>
        <button
          type="button"
          className="secondary compact"
          data-testid="node-input-add"
          onClick={onAdd}
        >
          {t('Add Binding')}
        </button>
      </div>
      {edgeRows.length > 0 && (
        <details className="incoming-edge-bindings">
          <summary>
            <strong>{t('Connected sources')}</strong>
            <span>{edgeRows.length}</span>
          </summary>
          <ul>
            {edgeRows.map((edge) => (
              <li key={edge.id}>
                <span>{edge.sourceLabel}</span>
                <code>
                  {endpointLabel(edge.sourcePort, edge.sourcePath, 'output')}
                  {' -> '}
                  {endpointLabel(edge.targetPort, edge.targetPath, 'input')}
                </code>
              </li>
            ))}
          </ul>
        </details>
      )}
      {rows.length > 0 ? (
        <ol className="input-binding-list">
          {rows.map(([bindingKey, binding], index) => {
            const kind = editableInputBindingKind(binding);
            const targetPort = binding.targetPort || defaultInputTargetPort(node);
            return (
              <li key={bindingKey} data-testid={`node-input-binding:${index}`}>
                <div className="input-binding-row-header">
                  <label>
                    <span>{t('Key')}</span>
                    <input
                      aria-label={t('Input binding key {index}', { index: index + 1 })}
                      data-testid={`node-input-key:${index}`}
                      value={bindingKey}
                      onChange={(event) => onRename(bindingKey, event.target.value)}
                    />
                  </label>
                  <label>
                    <span>{t('Source')}</span>
                    <select
                      aria-label={t('Input binding source {index}', { index: index + 1 })}
                      data-testid={`node-input-kind:${index}`}
                      value={kind}
                      onChange={(event) =>
                        onKindChange(bindingKey, event.target.value === 'constant' ? 'constant' : 'contextPath')
                      }
                    >
                      <option value="contextPath">{t('ctx')}</option>
                      <option value="constant">{t('constant')}</option>
                    </select>
                  </label>
                </div>
                <div className="input-binding-targets">
                  <label>
                    <span>{t('Target port')}</span>
                    <select
                      aria-label={t('Input target port {index}', { index: index + 1 })}
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
                    <span>{t('Target path')}</span>
                    <input
                      aria-label={t('Input target path {index}', { index: index + 1 })}
                      data-testid={`node-input-target-path:${index}`}
                      placeholder={bindingKey}
                      value={binding.targetPath ?? ''}
                      onChange={(event) => onChange(bindingKey, { targetPath: event.target.value })}
                    />
                  </label>
                </div>
                {kind === 'contextPath' ? (
                  <label className="input-binding-source">
                    <span>{t('Context path')}</span>
                    <input
                      aria-label={t('Context path {index}', { index: index + 1 })}
                      data-testid={`node-input-context-path:${index}`}
                      placeholder={t('user.id')}
                      value={binding.path ?? ''}
                      onChange={(event) => onChange(bindingKey, { kind: 'contextPath', path: event.target.value })}
                    />
                  </label>
                ) : (
                  <label className="input-binding-source">
                    <span>{t('Constant')}</span>
                    <textarea
                      aria-label={t('Constant input value {index}', { index: index + 1 })}
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
                  {t('Remove')}
                </button>
              </li>
            );
          })}
        </ol>
      ) : (
        <p className="muted">
          {edgeRows.length > 0 ? t('No direct context or constant bindings.') : t('No input bindings.')}
        </p>
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
  const { d, t } = useI18n();
  const contextPreview = JSON.stringify(compilation.value, null, 2);
  return (
    <div className="fixture-editor context-editor">
      <div className="fixture-header">
        <strong>{t('Context Variables')}</strong>
        <span className={`badge ${compilation.error ? 'error' : 'fixture'}`}>
          {compilation.error ? t('invalid') : t('ready')}
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
                  {t('ctx')}.{path || t('path')}
                </button>
                <div className="context-variable-fields">
                  <label>
                    <span>{t('Path')}</span>
                    <input
                      aria-label={t('Context variable path {index}', { index: index + 1 })}
                      data-testid={`context-variable-path:${index}`}
                      placeholder={t('applicant.score')}
                      value={row.path}
                      onChange={(event) => onUpdate(row.id, { path: event.target.value })}
                    />
                  </label>
                  <label>
                    <span>{t('Type')}</span>
                    <select
                      aria-label={t('Context variable type {index}', { index: index + 1 })}
                      data-testid={`context-variable-type:${index}`}
                      value={row.valueType}
                      onChange={(event) =>
                        onUpdate(row.id, { valueType: event.target.value as ContextVariableType })
                      }
                    >
                      <option value="string">{t('string')}</option>
                      <option value="number">{t('number')}</option>
                      <option value="boolean">{t('boolean')}</option>
                      <option value="json">{t('json')}</option>
                    </select>
                  </label>
                  <label>
                    <span>{t('Sample')}</span>
                    <input
                      aria-label={t('Context variable sample {index}', { index: index + 1 })}
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
                    {t('Bind')}
                  </button>
                  <button
                    type="button"
                    className="secondary compact"
                    data-testid={`context-variable-remove:${index}`}
                    onClick={() => onRemove(row.id)}
                  >
                    {t('Remove')}
                  </button>
                </div>
              </li>
            );
          })}
        </ol>
      ) : (
        <p className="muted">{t('No context variables.')}</p>
      )}
      <div className="context-variable-footer">
        <button
          type="button"
          className="secondary compact"
          data-testid="context-variable-add"
          onClick={onAdd}
        >
          {t('Add Variable')}
        </button>
      </div>
      <pre className="context-preview" data-testid="context-preview-json">{contextPreview}</pre>
      <details className="context-advanced">
        <summary>{t('Advanced JSON')}</summary>
        <label className="fixture-field">
          <span>{t('JSON')}</span>
          <textarea
            aria-label={t('Simulation runtime context JSON')}
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
          {d(compilation.error)}
        </p>
      )}
    </div>
  );
}

function OperatorNode({ id, data, selected }: NodeProps<NodeData>) {
  const { t, d } = useI18n();
  const status = data.status ?? 'unknown';
  const inputPorts = data.summary.inputNames;
  const outputPorts = data.summary.outputNames.length ? data.summary.outputNames : [''];
  const metrics = operatorNodeMetrics(data.summary, data.config);
  const candidateClass = data.candidateStatus ? `candidate-${data.candidateStatus}` : '';
  const focusClass = data.focusState && data.focusState !== 'none' ? `focus-${data.focusState}` : '';
  const kindClass = `kind-${data.summary.visualKind}`;
  return (
    <div
      className={[
        'operator-node',
        kindClass,
        status,
        candidateClass,
        focusClass,
        data.pathFocus ? `path-${data.pathFocus}` : '',
        data.pinned ? 'pinned' : '',
        selected ? 'selected' : '',
      ].filter(Boolean).join(' ')}
      data-testid={`canvas-node:${id}`}
      data-operator-ref={data.operatorRef}
    >
      {inputPorts.map((port, index) => (
        <Handle
          key={`in:${port}`}
          id={handleIdForPort('in', port)}
          type="target"
          position={Position.Left}
          title={data.candidatePorts?.[port]
            ? t('Input: {port} · {status}', { port, status: d(data.candidatePorts[port]) })
            : t('Input: {port}', { port })}
          className={`port-handle target ${data.candidatePorts?.[port] ?? ''}`}
          style={handleOffset(index, inputPorts.length)}
        />
      ))}
      <div className="operator-node-title">
        <span>{data.label}</span>
        <span className="operator-node-pills">
          <span className={`operator-kind-pill ${data.summary.visualKind}`}>{d(data.summary.visualLabel)}</span>
          {data.summary.externalWrite && (
            <span
              className={`operator-side-effect-pill ${data.summary.managedWrite ? 'managed' : 'unmanaged'}`}
              title={d(data.summary.sideEffectNotice)}
            >
              {data.summary.managedWrite ? t('write ok') : t('write blocked')}
            </span>
          )}
          {data.isOutput && <span className="output-pill">{t('output')}</span>}
          {data.pinned && <span className="pin-pill" title={t('Pinned for Auto Layout')}>{t('pinned')}</span>}
          {status !== 'unknown' && <span className={`run-pill ${status}`}>{d(status)}</span>}
        </span>
      </div>
      <div className="operator-node-ref">{data.operatorRef}</div>
      <div className="operator-node-contract" title={d(data.summary.contractHint)}>
        <span>{d(data.summary.inputContractLabel)}</span>
        <strong>→</strong>
        <span>{d(data.summary.outputContractLabel)}</span>
      </div>
      <div className="operator-node-metrics">
        <span>
          {metrics.inputMetricKind === 'mappings'
            ? t('{count} mappings', { count: metrics.inputCount })
            : t('{required}/{total} inputs', { required: metrics.requiredInputCount, total: metrics.inputCount })}
        </span>
        <span>{t('{count} outputs', { count: metrics.outputCount })}</span>
      </div>
      <div className="operator-node-port-grid">
        <span>{t('In')}</span>
        <strong>{inputPorts.join(', ') || 'none'}</strong>
        <span>{t('Out')}</span>
        <strong>{data.summary.outputNames.join(', ') || 'value'}</strong>
      </div>
      {data.summary.readinessNodeNotice && (
        <div
          className={`operator-node-warning ${data.summary.readinessLevel}`}
          title={d(data.summary.readinessNotice || data.summary.readinessNodeNotice)}
        >
          {d(data.summary.readinessBadgeLabel || data.summary.readinessState)}: {d(data.summary.readinessNodeNotice)}
        </div>
      )}
      {outputPorts.map((port, index) => (
        <Handle
          key={`out:${port}`}
          id={handleIdForPort('out', port)}
          type="source"
          position={Position.Right}
          title={port ? t('Output: {port}', { port }) : t('Output')}
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
    outputKind: config?.outputKind === 'scalar' || config?.outputKind === 'plan' || config?.outputKind === 'dispatch'
      ? config.outputKind
      : 'object',
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
    outputKind: editor.outputKind ?? 'object',
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
  const { d } = useI18n();
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
        <span>{d(summary.visualLabel)}</span>
        <strong>{d(operatorFocusTitle(summary.visualKind))}</strong>
      </div>
      <dl className="operator-focus-grid">
        {rows.map((row) => (
          <div key={row.key}>
            <dt>{d(row.label)}</dt>
            <dd>{d(row.value)}</dd>
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
  onConfigPatch,
  scenarioTarget,
  scenarioScope,
  persistedScenarioDraftSet,
  onScenarioDraftSetChange,
}: {
  node: Node<NodeData>;
  incomingColumns: DecisionTableColumn[];
  onClose: () => void;
  onChange: (editor: DecisionTableEditorModel) => void;
  embedded?: boolean;
  onConfigPatch?: (patch: Record<string, unknown>) => void;
  scenarioTarget?: ExactTargetRef;
  scenarioScope?: EnterpriseScope;
  persistedScenarioDraftSet?: ScenarioDraftSet | null;
  onScenarioDraftSetChange?: (draftSet: ScenarioDraftSet) => void;
}) {
  const { t } = useI18n();
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
          <span>{t('Decision table')}</span>
          <strong id="decision-rule-editor-title">{node.data.label}</strong>
          {!embedded && (
            <button
              type="button"
              className="secondary compact"
              onClick={onClose}
              aria-label={t('Close decision table editor')}
            >
              {t('Done')}
            </button>
          )}
        </div>
        <div className="rule-editor-meta">
          <label>
            <span>{t('Hit policy')}</span>
            <select
              aria-label={t('Decision table hit policy')}
              value={editor.hitPolicy}
              onChange={(event) => updateEditor({ ...editor, hitPolicy: event.target.value })}
            >
              <option value="unique">{t('unique')}</option>
              <option value="first">{t('first')}</option>
              <option value="collect">{t('collect')}</option>
            </select>
          </label>
          <label>
            <span>{t('Output type')}</span>
            <input
              aria-label={t('Decision table output type')}
              value={editor.outputType}
              onChange={(event) => updateEditor({ ...editor, outputType: event.target.value })}
            />
          </label>
        </div>
        {resolveSpine(window.location.search) === 'v1' && scenarioTarget && scenarioScope && onScenarioDraftSetChange && (
          <Suspense fallback={<p className="muted" role="status">{t('Loading scenario tools…')}</p>}>
            <DecisionScenarioWorkbench
              editor={editor as DecisionEditorSnapshot}
              tableId={node.id}
              target={scenarioTarget}
              scope={scenarioScope}
              owner={scenarioScope.tenantId}
              persisted={persistedScenarioDraftSet ?? null}
              onPersistedChange={onScenarioDraftSetChange}
              onOutputKindChange={(outputKind) => onConfigPatch?.({ outputKind })}
            />
          </Suspense>
        )}
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
            {t('Add Condition Column')}
          </button>
          <button
            type="button"
            className="secondary compact"
            onClick={addOutputColumn}
            data-testid="decision-add-output-column"
          >
            {t('Add Output Column')}
          </button>
        </div>
        <div className="rule-editor-table-wrap">
          <table className="rule-editor-table">
            <thead>
              <tr>
                <th className="rule-editor-row-index">{t('Rule')}</th>
                {editor.conditionColumns.map((column, index) => (
                  <th key={`condition:${column.id}`} className="rule-editor-column condition">
                    <div className="rule-editor-column-header">
                      <span>{t('Condition')}</span>
                      <input
                        aria-label={t('Condition column {index} name', { index: index + 1 })}
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
                        aria-label={t('Delete condition column {label}', { label: column.label })}
                      >
                        {t('Delete')}
                      </button>
                    </div>
                  </th>
                ))}
                {editor.outputColumns.map((column, index) => (
                  <th key={`output:${column.id}`} className="rule-editor-column output">
                    <div className="rule-editor-column-header">
                      <span>{t('Output')}</span>
                      <input
                        aria-label={t('Output column {index} name', { index: index + 1 })}
                        data-testid={`decision-output-column-name:${index}`}
                        value={column.label}
                        onChange={(event) => renameOutputColumn(index, event.target.value)}
                      />
                      <button
                        type="button"
                        className="secondary compact"
                        onClick={() => deleteOutputColumn(index)}
                        disabled={editor.outputColumns.length <= 1}
                        aria-label={t('Delete output column {label}', { label: column.label })}
                      >
                        {t('Delete')}
                      </button>
                    </div>
                  </th>
                ))}
                <th>{t('Otherwise')}</th>
                <th aria-label={t('Rule actions')} />
              </tr>
            </thead>
            <tbody>
              {editor.rows.map((row, index) => (
                <tr key={`rule:${index}:${row.otherwise ? 'otherwise' : 'match'}`}>
                  <td className="rule-editor-row-index">{index + 1}</td>
                  {editor.conditionColumns.map((column) => (
                    <td key={`condition:${column.id}`} className="rule-editor-condition-cell">
                      <input
                        aria-label={t('Rule {index} {label} condition', { index: index + 1, label: column.label })}
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
                        aria-label={t('Rule {index} {label} output', { index: index + 1, label: column.label })}
                        data-testid={`decision-rule-output:${index}:${column.id}`}
                        value={row.outputs[column.id] ?? ''}
                        onChange={(event) => updateOutputCell(index, column.id, event.target.value)}
                      />
                    </td>
                  ))}
                  <td>
                    <input
                      type="checkbox"
                      aria-label={t('Rule {index} otherwise', { index: index + 1 })}
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
                      {t('Delete')}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="rule-editor-actions">
          <button type="button" className="secondary compact" onClick={addRow}>
            {t('Add Rule')}
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
  const { t } = useI18n();
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
          <span>{t('Transform mapping')}</span>
          <strong id="transform-assignment-editor-title">{node.data.label}</strong>
          {!embedded && (
            <button
              type="button"
              className="secondary compact"
              onClick={onClose}
              aria-label={t('Close transform mapping editor')}
            >
              {t('Done')}
            </button>
          )}
        </div>
        <div className="rule-editor-table-wrap">
          <table className="rule-editor-table transform-editor-table">
            <thead>
              <tr>
                <th className="rule-editor-row-index">#</th>
                <th>{t('Output Field')}</th>
                <th>{t('Expression')}</th>
                <th aria-label={t('Assignment actions')} />
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
                        aria-label={t('Assignment {index} output field', { index: index + 1 })}
                        data-testid={`transform-assignment-field:${index}`}
                        value={assignment.field}
                        onChange={(event) => updateAssignment(index, {
                          field: decisionFieldName(event.target.value, assignment.field || `field${index + 1}`),
                        })}
                      />
                    </td>
                    <td className="rule-editor-expression-cell">
                      <input
                        aria-label={t('Assignment {index} expression', { index: index + 1 })}
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
                        {t('Delete')}
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
            {t('Add Assignment')}
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

function acceptedInferenceAddsFields(
  current: SchemaEnvelope | undefined,
  candidate: SchemaEnvelope,
): boolean {
  const currentProperties = schemaProperties(current?.schema);
  const candidateProperties = schemaProperties(candidate.schema);
  return Object.keys(candidateProperties).some((field) => !(field in currentProperties));
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
  const { t, d } = useI18n();
  const config = node.data.config ?? {};
  const resourceLike = node.data.summary.visualKind === 'resource' || node.data.summary.visualKind === 'http';
  return (
    <section className="operator-detail-section operator-key-properties">
      <h3>{t('Key properties')}</h3>
      <label className="operator-detail-field">
        <span>{t('Node label')}</span>
        <input
          aria-label={t('Operator node label')}
          data-testid="operator-detail-label"
          value={node.data.label}
          onChange={(event) => onLabelChange(event.target.value)}
        />
      </label>
      <dl className="operator-property-list">
        {operatorPropertyRows(node, operator).map((row) => (
          <div key={row.label}>
            <dt>{d(row.label)}</dt>
            <dd>{d(row.value)}</dd>
          </div>
        ))}
      </dl>
      {resourceLike && (
        <div className="resource-config-grid" data-testid="operator-detail-resource-config">
          <label className="operator-detail-field">
            <span>{t('Resource ID')}</span>
            <input
              aria-label={t('Resource ID')}
              data-testid="operator-detail-resource-id"
              placeholder={operatorDefaultResourceId(node, operator)}
              value={configTextValue(config, 'resourceId')}
              onChange={(event) => onConfigPatch({ resourceId: configPatchValue(event.target.value) })}
            />
          </label>
          <label className="operator-detail-field">
            <span>{t('Method')}</span>
            <select
              aria-label={t('HTTP method')}
              data-testid="operator-detail-http-method"
              value={configTextValue(config, 'method')}
              onChange={(event) => onConfigPatch({ method: configPatchValue(event.target.value) })}
            >
              <option value="">{t('default')}</option>
              <option value="GET">{t('GET')}</option>
              <option value="POST">{t('POST')}</option>
              <option value="PUT">{t('PUT')}</option>
              <option value="PATCH">{t('PATCH')}</option>
              <option value="DELETE">{t('DELETE')}</option>
            </select>
          </label>
          <label className="operator-detail-field resource-url-field">
            <span>{t('URL / route')}</span>
            <input
              aria-label={t('Resource URL')}
              data-testid="operator-detail-resource-url"
              placeholder={t('/resource/path')}
              value={configTextValue(config, 'url')}
              onChange={(event) => onConfigPatch({ url: configPatchValue(event.target.value) })}
            />
          </label>
          <label className="operator-detail-field">
            <span>{t('Timeout ms')}</span>
            <input
              aria-label={t('Resource timeout milliseconds')}
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
  const { t, d } = useI18n();
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
        <h3>{t('Advanced config')}</h3>
        <button
          type="button"
          className="secondary compact"
          data-testid="operator-detail-config-apply"
          onClick={applyDraft}
        >
          {t('Apply')}
        </button>
      </div>
      <textarea
        aria-label={t('Operator node config JSON')}
        data-testid="operator-detail-config-json"
        spellCheck={false}
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
      />
      {error && <p className="fixture-error" data-testid="operator-detail-config-error">{d(error)}</p>}
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
  const { t, d } = useI18n();
  return (
    <div className="fixture-editor operator-detail-fixtures" data-testid="operator-detail-fixtures">
      <div className="fixture-header">
        <strong>{t('Input / Output samples')}</strong>
        <span className={`badge ${hasFixtureDraft ? 'fixture' : ''}`}>
          {hasFixtureDraft ? t('custom') : t('server sample')}
        </span>
      </div>
      <div className="fixture-actions">
        <button
          className="secondary compact"
          data-testid="operator-detail-use-sample"
          onClick={onUseSample}
        >
          {t('Use Sample')}
        </button>
        <button
          className="secondary compact"
          data-testid="operator-detail-clear-fixture"
          onClick={onClear}
          disabled={!hasFixtureDraft}
        >
          {t('Clear')}
        </button>
      </div>
      <label className="fixture-field">
        <span>{t('Output sample')}</span>
        <textarea
          aria-label={t('Operator output sample JSON')}
          data-testid="operator-detail-output-fixture"
          spellCheck={false}
          placeholder={t('null')}
          value={fixtureDraft}
          onChange={(event) => onOutputChange(event.target.value)}
        />
      </label>
      <label className="fixture-field">
        <span>{t('Expected input')}</span>
        <textarea
          aria-label={t('Operator expected input JSON')}
          data-testid="operator-detail-expected-input"
          spellCheck={false}
          placeholder="{}"
          value={expectedInputDraft}
          onChange={(event) => onExpectedInputChange(event.target.value)}
        />
      </label>
      {fixtureError && <p className="fixture-error" data-testid="operator-detail-fixture-error">{d(fixtureError)}</p>}
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
  const { t, d } = useI18n();
  const transportControlled = operatorUsesTransportFixture(operator);
  const invalidCount = rows
    .map(parseOperatorTestSuiteRow)
    .filter((compilation) => compilation.error)
    .length;
  const resultValues = Object.values(results);
  const passedCount = resultValues.filter((result) => result.status === 'passed').length;
  const failedCount = resultValues.filter((result) => result.status === 'failed').length;
  const resultLabel = running
    ? t('running')
    : resultValues.length > 0
      ? `${t('{passed}/{total} passed', { passed: passedCount, total: rows.length })}${failedCount > 0 ? t(' · {count} failed', { count: failedCount }) : ''}`
      : invalidCount > 0
        ? t('{count} invalid', { count: invalidCount })
        : t('{count} valid', { count: rows.length });
  const summaryStatus = running ? 'running' : failedCount > 0 || invalidCount > 0 ? 'failed' : passedCount > 0 ? 'passed' : 'pending';
  return (
    <section className="operator-detail-section operator-test-suite" data-testid="operator-test-suite">
      <div className="operator-detail-section-heading">
        <h3>{t('Executable Operator Suite')}</h3>
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
            {running ? t('Running') : t('Run Exploratory')}
          </button>
          <button
            type="button"
            className="secondary compact"
            data-testid="operator-test-govern-all"
            onClick={onGovernAll}
            disabled={running || rows.length === 0 || invalidCount > 0 || Boolean(runDisabledReason)}
            title={runDisabledReason}
          >
            {t('Publish Suite + Run')}
          </button>
          <button
            type="button"
            className="secondary compact"
            data-testid="operator-test-add"
            onClick={onAdd}
            disabled={running}
          >
            {t('Add Case')}
          </button>
        </div>
      </div>
      {publication && (
        <div
          className={`operator-suite-publication ${publication.status}`}
          data-testid="operator-suite-publication"
        >
          <strong>{d(publication.detail)}</strong>
          {publication.suiteId && (
            <span>
              {publication.suiteId}@{publication.revision}{t(' · run')} {publication.suiteRunId}
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
                    aria-label={t('Operator test case name {index}', { index: index + 1 })}
                    data-testid={`operator-test-name:${index}`}
                    value={row.name}
                    onChange={(event) => onUpdate(row.id, { name: event.target.value })}
                    disabled={running}
                  />
                  <span
                    className={`table-status ${rowStatus}`}
                    data-testid={`operator-test-status:${index}`}
                  >
                    {d(compilation.error ? 'invalid' : result?.status ?? 'valid')}
                  </span>
                  <label className="operator-test-case-type">
                    <span>{t('Intent')}</span>
                    <select
                      aria-label={t('Operator test case intent {index}', { index: index + 1 })}
                      data-testid={`operator-test-case-type:${index}`}
                      value={row.caseType}
                      onChange={(event) => onUpdate(row.id, {
                        caseType: event.target.value as OperatorTestSuiteCaseType,
                      })}
                      disabled={running}
                    >
                      <option value="GOLDEN">{t('Golden')}</option>
                      <option value="NEGATIVE">{t('Negative')}</option>
                      <option value="BOUNDARY">{t('Boundary')}</option>
                      <option value="REGRESSION">{t('Regression')}</option>
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
                    {t('Run Case')}
                  </button>
                  <button
                    type="button"
                    className="secondary compact"
                    data-testid={`operator-test-govern:${index}`}
                    onClick={() => onGovern(row)}
                    disabled={running || Boolean(compilation.error) || Boolean(runDisabledReason)}
                    title={runDisabledReason}
                  >
                    {t('Publish Case + Run')}
                  </button>
                  <button
                    type="button"
                    className="secondary compact"
                    data-testid={`operator-test-apply:${index}`}
                    onClick={() => onApplyFixture(row)}
                    disabled={running || Boolean(compilation.error)}
                  >
                    {t('Apply Fixture')}
                  </button>
                  <button
                    type="button"
                    className="secondary compact"
                    aria-label={t('Remove operator test case {index}', { index: index + 1 })}
                    data-testid={`operator-test-remove:${index}`}
                    onClick={() => onRemove(row.id)}
                    disabled={running || rows.length <= 1}
                  >
                    {t('Remove')}
                  </button>
                </div>
                <label className="fixture-field">
                  <span>{t('Input case')}</span>
                  <textarea
                    aria-label={t('Operator test input case {index}', { index: index + 1 })}
                    data-testid={`operator-test-input:${index}`}
                    spellCheck={false}
                    value={row.inputText}
                    onChange={(event) => onUpdate(row.id, { inputText: event.target.value })}
                    disabled={running}
                  />
                </label>
                <label className="fixture-field">
                  <span>{t('Expected output')}</span>
                  <textarea
                    aria-label={t('Operator test expected output {index}', { index: index + 1 })}
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
                    <span>{t('Transport response')}</span>
                    <textarea
                      aria-label={t('Operator test transport response {index}', { index: index + 1 })}
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
                    {d(compilation.error)}
                  </p>
                )}
                {result && !compilation.error && (
                  <div className={`operator-test-result ${result.status}`}>
                    <p data-testid={`operator-test-result:${index}`}>{d(result.detail)}</p>
                    {result.actualOutput !== undefined && (
                      <details>
                        <summary>{t('Actual output')}</summary>
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
        <p className="muted">{t('No executable operator cases.')}</p>
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
  const { t, d } = useI18n();
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
                  <strong>{port.name || t(direction === 'input' ? 'input' : 'output')}</strong>
                  {port.required && <span className="schema-required">{t('required')}</span>}
                </div>
                <div className="operator-schema-summary">
                  <span>{d(schemaKindLabel(port.schema?.schema))}</span>
                  <span>{t('{count} fields', { count: fields.length })}</span>
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
                          <td>{d(field.type)}</td>
                          <td>{field.required ? t('required') : t('optional')}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
                <details>
                  <summary>{t('Raw schema')}</summary>
                  <pre data-testid={`operator-detail-schema:${direction}:${index}`}>
                    {schemaPreview(port.schema)}
                  </pre>
                </details>
              </article>
            );
          })}
        </div>
      ) : (
        <p className="muted">{t('No declared {direction} ports.', { direction: d(direction) })}</p>
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
  const { t } = useI18n();
  const collection = inputs.find((port) => schemaKindLabel(port.schema?.schema) === 'array') ?? inputs[0];
  const result = outputs.find((port) => schemaKindLabel(port.schema?.schema) === 'array') ?? outputs[0];
  return (
    <section className="foreach-loop-guide" data-testid="foreach-loop-guide">
      <h3>{t('Loop guide')}</h3>
      <div className="foreach-loop-steps">
        <div>
          <span>1</span>
          <strong>{t('Bind collection')}</strong>
          <p>{t('Connect an array into')} <code>{collection?.name || t('input')}</code>.</p>
        </div>
        <div>
          <span>2</span>
          <strong>{t('Run per item')}</strong>
          <p>{t('Each item becomes the item context:')} <code>{itemContextLabel(inputs)}</code>.</p>
        </div>
        <div>
          <span>3</span>
          <strong>{t('Collect result list')}</strong>
          <p>{t('Downstream nodes consume')} <code>{result?.name || t('output')}</code> {t('as an array.')}</p>
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
  canonicalScenarios,
  effectiveContract,
  onCancel,
  onApply,
  dirty,
  onOpenContract,
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
  onAcceptInference,
  scenarioTarget,
  scenarioScope,
  persistedScenarioDraftSet,
  onScenarioDraftSetChange,
  governedFixtureAssets,
  governedFixtureRef,
  governedFixtureStale,
  onGovernedFixtureSelect,
  onClearGovernedFixture,
  resourceFidelity,
  onResourceFidelityChange,
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
  canonicalScenarios: boolean;
  effectiveContract: EffectiveContractProjection;
  onCancel: () => void;
  onApply: () => void;
  dirty: boolean;
  onOpenContract: () => void;
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
  onAcceptInference?: () => void;
  scenarioTarget?: ExactTargetRef;
  scenarioScope?: EnterpriseScope;
  persistedScenarioDraftSet?: ScenarioDraftSet | null;
  onScenarioDraftSetChange?: (draftSet: ScenarioDraftSet) => void;
  governedFixtureAssets: readonly GovernedFixtureAssetSummary[];
  governedFixtureRef?: GovernedGraphNodeFixtureRef;
  governedFixtureStale: boolean;
  onGovernedFixtureSelect: (asset: PickerAsset) => void;
  onClearGovernedFixture: () => void;
  resourceFidelity: ResourceFidelity;
  onResourceFidelityChange: (value: ResourceFidelity) => void;
}) {
  const { t, d } = useI18n();
  const inputs = operator?.ports?.inputs ?? [];
  const outputs = operator?.ports?.outputs ?? [];
  const focusRows = operatorFocusRows(node.data.summary, inputs, outputs, operator);
  const editorDefinition = resolveNodeEditor(node.data.summary.visualKind);
  const visibleTabs = canonicalScenarios
    ? editorDefinition.tabs.filter((tab) => tab.id !== 'test')
    : editorDefinition.tabs;
  const [activeTab, setActiveTab] = useState<NodeEditorTab>(editorDefinition.defaultTab);
  const dialogRef = useRef<HTMLElement>(null);
  const legacyTestTools = (
    <>
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
    </>
  );

  useEffect(() => {
    setActiveTab(editorDefinition.defaultTab);
  }, [editorDefinition.defaultTab, node.id]);
  useDialogFocusTrap({
    open: true,
    dialogRef,
    onDismiss: onCancel,
    initialFocusKey: node.id,
  });

  return (
    <div className="rule-editor-backdrop" role="presentation">
      <section
        ref={dialogRef}
        className="operator-detail-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="operator-detail-title"
        tabIndex={-1}
        data-testid="operator-detail-dialog"
        data-editor-kind={editorDefinition.visualKind}
        data-default-tab={editorDefinition.defaultTab}
        data-dirty={dirty}
      >
        <div className="operator-detail-heading">
          <span>
            {d(node.data.summary.visualLabel)}
            <small>{d(editorDefinition.primaryTask)}</small>
          </span>
          <strong id="operator-detail-title">{node.data.label}</strong>
          <button
            type="button"
            className="secondary compact"
            onClick={onOpenContract}
            disabled={!operator}
          >
            {t('Contract & Scenarios')}
          </button>
          <div className="operator-detail-heading-actions">
            <button
              type="button"
              className="secondary compact"
              onClick={onCancel}
              aria-label={t('Close operator details')}
            >
              {t('Cancel')}
            </button>
            <button
              type="button"
              className="primary compact"
              onClick={onApply}
              data-testid="operator-detail-apply"
            >
              {t('Apply to draft')}
            </button>
          </div>
        </div>
        <nav className="operator-editor-tabs" role="tablist" aria-label={t('Node editor sections')}>
          {visibleTabs.map((tab) => (
            <button
              type="button"
              role="tab"
              key={tab.id}
              aria-selected={activeTab === tab.id}
              data-testid={`operator-editor-tab:${tab.id}`}
              onClick={() => setActiveTab(tab.id)}
            >
              {d(tab.label)}
            </button>
          ))}
        </nav>
        <div className="operator-detail-body" data-dialog-initial-focus tabIndex={-1}>
          <div
            className="operator-editor-pane config"
            role="tabpanel"
            hidden={activeTab !== 'config'}
            data-testid="operator-editor-pane:config"
          >
            <OperatorKeyPropertiesEditor
              node={node}
              operator={operator}
              onLabelChange={onLabelChange}
              onConfigPatch={onConfigPatch}
            />

            <section className="operator-detail-section">
              <h3>{d(operatorFocusTitle(node.data.summary.visualKind))}</h3>
              <div className="operator-detail-focus">
                {focusRows.map((row) => (
                  <div key={row.key}>
                    <span>{d(row.label)}</span>
                    <strong>{d(row.value)}</strong>
                  </div>
                ))}
              </div>
            </section>

            {node.data.summary.visualKind === 'foreach' && (
              <ForeachLoopGuide inputs={inputs} outputs={outputs} />
            )}

            {(node.data.operatorRef.startsWith('resource:') || operator?.display?.tags?.includes('resource')) && (
              <section className="fixture-asset-reuse" data-testid="fixture-asset-reuse">
                <h3>{t('Governed fixture reuse')}</h3>
                <Suspense fallback={<p className="muted" role="status">{t('Loading fixture controls…')}</p>}>
                  <GraphNodeFixturePicker
                    assets={governedFixtureAssets}
                    onSelect={onGovernedFixtureSelect}
                  />
                  <FixtureStalenessNotice
                    stale={governedFixtureStale}
                    onRecapture={onClearGovernedFixture}
                  />
                  <ResourceFidelitySelect
                    value={resourceFidelity}
                    onChange={onResourceFidelityChange}
                  />
                </Suspense>
                {governedFixtureRef && !governedFixtureStale && (
                  <p className="fixture-provenance" data-testid="governed-fixture-bound">
                    {t('Governed fixture bound')}: {governedFixtureRef.fixtureAssetId} r{governedFixtureRef.revision}
                  </p>
                )}
                <button
                  type="button"
                  className="secondary compact"
                  onClick={onClearGovernedFixture}
                  disabled={!governedFixtureRef}
                >
                  {t('Clear governed fixture')}
                </button>
                <small className="muted">{t('Only output-level fidelity is executed by visual simulation.')}</small>
              </section>
            )}
          </div>

          <div
            className="operator-editor-pane data"
            role="tabpanel"
            hidden={activeTab !== 'data'}
            data-testid="operator-editor-pane:data"
          >
            <NodeInputBindingsEditor
              node={node}
              onAdd={onInputAdd}
              onRemove={onInputRemove}
              onRename={onInputRename}
              onChange={onInputChange}
              onKindChange={onInputKindChange}
              onDropContextPath={onDropContextPath}
            />
          </div>

          {!canonicalScenarios && (
            <div
              className="operator-editor-pane test"
              role="tabpanel"
              hidden={activeTab !== 'test'}
              data-testid="operator-editor-pane:test"
            >
              {legacyTestTools}
            </div>
          )}

          <div
            className="operator-editor-pane contract"
            role="tabpanel"
            hidden={activeTab !== 'contract'}
            data-testid="operator-editor-pane:contract"
          >
            <EffectiveContractPanel
              projection={effectiveContract}
              onTraceField={() => setActiveTab(
                node.data.summary.visualKind === 'transform'
                  ? 'mapping'
                  : node.data.summary.visualKind === 'decision-table'
                    ? 'rules'
                    : 'config',
              )}
              onAcceptInference={onAcceptInference}
              acceptInferenceLabel="Accept as Graph Output Contract"
            />
            <SchemaPortCards title={t('Input schema')} direction="input" ports={inputs} />
            <SchemaPortCards title={t('Output schema')} direction="output" ports={outputs} />
          </div>

          <div
            className="operator-editor-pane advanced"
            role="tabpanel"
            hidden={activeTab !== 'advanced'}
            data-testid="operator-editor-pane:advanced"
          >
            <OperatorConfigEditor config={node.data.config} onApply={onConfigReplace} />
            {canonicalScenarios && (
              <details className="operator-legacy-test-tools">
                <summary>{t('Legacy operator table and fixture tools')}</summary>
                <p className="muted">
                  {t('Existing rows are projected into Contract & Scenarios. Keep these controls for migration or low-level fixture troubleshooting.')}
                </p>
                {legacyTestTools}
              </details>
            )}
          </div>

          {node.data.summary.visualKind === 'decision-table' && (
            <div
              className="operator-editor-pane rules"
              role="tabpanel"
              hidden={activeTab !== 'rules'}
              data-testid="operator-editor-pane:rules"
            >
            <DecisionTableRuleEditor
              node={node}
              incomingColumns={incomingColumns}
              onClose={onApply}
              onChange={onDecisionChange}
              embedded
              onConfigPatch={onConfigPatch}
              scenarioTarget={scenarioTarget}
              scenarioScope={scenarioScope}
              persistedScenarioDraftSet={persistedScenarioDraftSet}
              onScenarioDraftSetChange={onScenarioDraftSetChange}
            />
            </div>
          )}

          {node.data.summary.visualKind === 'transform' && (
            <div
              className="operator-editor-pane mapping"
              role="tabpanel"
              hidden={activeTab !== 'mapping'}
              data-testid="operator-editor-pane:mapping"
            >
            <TransformAssignmentEditor
              node={node}
              onClose={onApply}
              onChange={onTransformChange}
              builtInFunctions={builtInFunctions}
              embedded
            />
            </div>
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

/**
 * Node fixtures address the complete operator result map, including a single named port.
 *
 * Graph and Operator public Contracts intentionally unwrap one-port targets; Scenario dependency
 * controls must not, otherwise the form edits one shape while SimulationRequest sends another.
 */
function operatorNodePortSchema(
  operator: OperatorDefinition | undefined,
  direction: 'inputs' | 'outputs',
): SchemaEnvelope | undefined {
  const ports = operator?.ports?.[direction] ?? [];
  if (ports.length === 0) {
    return undefined;
  }
  const fallbackName = direction === 'inputs' ? 'input' : 'output';
  return schemaEnvelope({
    type: 'object',
    properties: Object.fromEntries(ports.map((port) => [
      port.name || fallbackName,
      port.schema?.schema ?? {},
    ])),
    required: ports.filter((port) => port.required).map((port) => port.name || fallbackName),
    additionalProperties: false,
  });
}

async function operatorScenarioDraftSetId(operatorRef: string): Promise<string> {
  const digest = (await sha256Fingerprint(operatorRef)).replace(/^sha256:/, '');
  const safeRef = operatorRef.replace(/[^A-Za-z0-9._-]+/g, '-').replace(/^-+|-+$/g, '')
    || 'operator';
  const fixed = `operator--${digest}-scenarios`;
  const prefixLimit = Math.max(1, 255 - fixed.length);
  return `operator-${safeRef.slice(0, prefixLimit)}-${digest}-scenarios`;
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

/**
 * Returns the canonical execution projection used to bind Contract and Scenario assets.
 *
 * Node coordinates are authoring presentation state: moving a card changes the durable draft and
 * must still make Save dirty, but it cannot invalidate fixtures, assertions, or runtime evidence.
 */
function canonicalExecutionGraphDraft(draft: GraphDraft): string {
  return canonicalJson({
    ...draft,
    nodes: draft.nodes.map(({ position: _position, ...node }) => node),
  });
}

/**
 * Rebinds locally authored content to the exact stored coordinate while preserving the portable
 * export rule that empty server-derived operator maps are omitted. Without this normalization an
 * empty map returned by the server and an omitted map emitted by the canvas look spuriously dirty.
 */
function savedCanvasDraftAtRevision(local: GraphDraft, stored: GraphDraft): GraphDraft {
  const {
    operatorFingerprints: _localFingerprints,
    operatorSnapshots: _localSnapshots,
    ...authored
  } = local;
  const operatorFingerprints = stored.operatorFingerprints ?? {};
  const operatorSnapshots = stored.operatorSnapshots ?? {};
  return {
    ...authored,
    draftId: stored.draftId,
    revision: stored.revision,
    tenantId: stored.tenantId ?? local.tenantId,
    namespace: stored.namespace ?? local.namespace,
    environment: stored.environment ?? local.environment,
    ...(Object.keys(operatorFingerprints).length > 0 ? { operatorFingerprints } : {}),
    ...(Object.keys(operatorSnapshots).length > 0 ? { operatorSnapshots } : {}),
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

function omitRecordKeys<TValue>(
  source: Record<string, TValue>,
  keys: Iterable<string>,
): Record<string, TValue> {
  const omitted = new Set(keys);
  return Object.fromEntries(
    Object.entries(source).filter(([key]) => !omitted.has(key)),
  );
}

function maxCanvasNodeSequence(nodes: CanvasNode[]): number {
  return nodes.reduce((max, node, index) => {
    const match = node.id.match(/^n(\d+)$/);
    return Math.max(max, match ? Number(match[1]) : index + 1);
  }, 0);
}

function authoringMutationContent(snapshot: AuthoringMutationSnapshot): unknown {
  return {
    nodes: snapshot.nodes.map((node) => ({
      id: node.id,
      type: node.type,
      position: node.position,
      data: authoredMutationNodeData(node.data),
    })),
    edges: snapshot.edges.map((edge) => ({
      id: edge.id,
      source: edge.source,
      target: edge.target,
      sourceHandle: edge.sourceHandle,
      targetHandle: edge.targetHandle,
      label: edge.label,
      data: edge.data,
    })),
    fixtureDrafts: snapshot.fixtureDrafts,
    fixtureInputDrafts: snapshot.fixtureInputDrafts,
    operatorTestSuites: snapshot.operatorTestSuites,
    simulationTableRows: snapshot.simulationTableRows,
    runInputValue: snapshot.runInputValue,
    simulationContextDraft: snapshot.simulationContextDraft,
    rawContextMode: snapshot.rawContextMode,
    contextVariables: snapshot.contextVariables,
    scenarioDraftSet: snapshot.scenarioDraftSet,
    explicitOutputNodeId: snapshot.explicitOutputNodeId,
    pinnedNodeIds: snapshot.pinnedNodeIds,
    graphName: snapshot.graphName,
    graphInputSchema: snapshot.graphInputSchema,
    graphOutputSchema: snapshot.graphOutputSchema,
    graphContractSource: snapshot.graphContractSource,
    graphVisualLayout: snapshot.graphVisualLayout,
    graphOperatorFingerprints: snapshot.graphOperatorFingerprints,
    graphOperatorSnapshots: snapshot.graphOperatorSnapshots,
    loadedExampleKey: snapshot.loadedExampleKey,
  };
}

function authoredMutationNodeData(data: NodeData): Omit<
  NodeData,
  'status' | 'candidateStatus' | 'candidatePorts' | 'focusState' | 'pathFocus' | 'isOutput' | 'pinned'
> {
  const {
    status: _status,
    candidateStatus: _candidateStatus,
    candidatePorts: _candidatePorts,
    focusState: _focusState,
    pathFocus: _pathFocus,
    isOutput: _isOutput,
    pinned: _pinned,
    ...authored
  } = data;
  return authored;
}

function authoringMutationFingerprint(snapshot: AuthoringMutationSnapshot): string {
  return mutationFingerprint(authoringMutationContent(snapshot));
}

function isAuthoringMutationSnapshot(value: unknown): value is AuthoringMutationSnapshot {
  if (!isObjectRecord(value)) return false;
  const nodeShape = (node: unknown) => isObjectRecord(node)
    && typeof node.id === 'string'
    && isObjectRecord(node.position)
    && typeof node.position.x === 'number'
    && typeof node.position.y === 'number'
    && isObjectRecord(node.data);
  const edgeShape = (edge: unknown) => isObjectRecord(edge)
    && typeof edge.id === 'string'
    && typeof edge.source === 'string'
    && typeof edge.target === 'string';
  return Array.isArray(value.nodes)
    && value.nodes.every(nodeShape)
    && Array.isArray(value.edges)
    && value.edges.every(edgeShape)
    && isStringRecord(value.fixtureDrafts)
    && isStringRecord(value.fixtureInputDrafts)
    && isRecordOf(value.operatorTestSuites, Array.isArray)
    && isRecordOf(value.operatorTestResults, isObjectRecord)
    && isRecordOf(value.operatorTestPublications, isObjectRecord)
    && Array.isArray(value.simulationTableRows)
    && isObjectRecord(value.simulationTableResults)
    && isObjectRecord(value.runInputValue)
    && typeof value.simulationContextDraft === 'string'
    && typeof value.rawContextMode === 'boolean'
    && Array.isArray(value.contextVariables)
    && (value.scenarioDraftSet === null || isObjectRecord(value.scenarioDraftSet))
    && (value.contractDraft === null || isObjectRecord(value.contractDraft))
    && typeof value.contractFingerprint === 'string'
    && typeof value.explicitOutputNodeId === 'string'
    && typeof value.selectedNodeId === 'string'
    && Array.isArray(value.pinnedNodeIds)
    && value.pinnedNodeIds.every((nodeId) => typeof nodeId === 'string')
    && typeof value.graphName === 'string'
    && isObjectRecord(value.graphInputSchema)
    && (value.graphOutputSchema === null || isObjectRecord(value.graphOutputSchema))
    && typeof value.graphContractSource === 'string'
    && isObjectRecord(value.graphVisualLayout)
    && isStringRecord(value.graphOperatorFingerprints)
    && isRecordOf(value.graphOperatorSnapshots, isObjectRecord)
    && typeof value.loadedExampleKey === 'string';
}

function isObjectRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function isRecordOf(
  value: unknown,
  valueGuard: (candidate: unknown) => boolean,
): value is Record<string, unknown> {
  return isObjectRecord(value) && Object.values(value).every(valueGuard);
}

function isStringRecord(value: unknown): value is Record<string, string> {
  return isRecordOf(value, (candidate) => typeof candidate === 'string');
}

function inferMutationDescriptor(
  before: AuthoringMutationSnapshot,
  after: AuthoringMutationSnapshot,
  translate: (source: string, values?: Record<string, string | number>) => string,
): PendingMutationDescriptor {
  const beforeNodeIds = new Set(before.nodes.map((node) => node.id));
  const afterNodeIds = new Set(after.nodes.map((node) => node.id));
  const addedNodes = after.nodes.filter((node) => !beforeNodeIds.has(node.id));
  const removedNodes = before.nodes.filter((node) => !afterNodeIds.has(node.id));
  if (addedNodes.length > 0 || removedNodes.length > 0) {
    const candidates = addedNodes.length > 0 ? addedNodes : removedNodes;
    const action = addedNodes.length > 0 ? 'Add' : 'Delete';
    return {
      kind: addedNodes.length > 0 ? 'ADD_NODE' : 'REMOVE_NODE',
      label: translate('{action} {subject}', {
        action: translate(action),
        subject: candidates.map((node) => node.data.label).join(', '),
      }),
      subjectRef: candidates.map((node) => node.id).join(','),
    };
  }
  const beforeEdgeIds = new Set(before.edges.map((edge) => edge.id));
  const afterEdgeIds = new Set(after.edges.map((edge) => edge.id));
  const addedEdges = after.edges.filter((edge) => !beforeEdgeIds.has(edge.id));
  const removedEdges = before.edges.filter((edge) => !afterEdgeIds.has(edge.id));
  if (addedEdges.length > 0 || removedEdges.length > 0) {
    const candidates = addedEdges.length > 0 ? addedEdges : removedEdges;
    return {
      kind: addedEdges.length > 0 ? 'ADD_EDGE' : 'REMOVE_EDGE',
      label: translate(addedEdges.length > 0 ? 'Connect nodes' : 'Delete connection'),
      subjectRef: candidates.map((edge) => edge.id).join(','),
    };
  }
  const changedNodes = after.nodes.filter((node) => {
    const previous = before.nodes.find((candidate) => candidate.id === node.id);
    return previous && canonicalJson(previous) !== canonicalJson(node);
  });
  if (changedNodes.length > 0) {
    const positionsChanged = changedNodes.every((node) => {
      const previous = before.nodes.find((candidate) => candidate.id === node.id);
      return previous
        && canonicalJson(previous.data) === canonicalJson(node.data)
        && canonicalJson(previous.position) !== canonicalJson(node.position);
    });
    const node = changedNodes[0];
    const visualKind = node.data.summary.visualKind;
    return {
      kind: positionsChanged
        ? 'MOVE_NODE'
        : visualKind === 'decision-table'
          ? 'DECISION_TABLE'
          : visualKind === 'transform' ? 'TRANSFORM' : 'NODE_CONFIG',
      label: translate(positionsChanged ? 'Move {subject}' : 'Edit {subject}', {
        subject: changedNodes.map((candidate) => candidate.data.label).join(', '),
      }),
      subjectRef: changedNodes.map((candidate) => candidate.id).join(','),
      coalesceKey: positionsChanged
        ? ''
        : `node:${changedNodes.map((candidate) => candidate.id).join(',')}`,
    };
  }
  if (
    canonicalJson(before.fixtureDrafts) !== canonicalJson(after.fixtureDrafts)
    || canonicalJson(before.fixtureInputDrafts) !== canonicalJson(after.fixtureInputDrafts)
  ) {
    return {
      kind: 'FIXTURE',
      label: translate('Edit fixture data'),
      subjectRef: 'fixtures',
      coalesceKey: 'fixtures',
    };
  }
  if (canonicalJson(before.operatorTestSuites) !== canonicalJson(after.operatorTestSuites)) {
    return {
      kind: 'TEST_SUITE',
      label: translate('Edit operator test suite'),
      subjectRef: 'operator-tests',
      coalesceKey: 'operator-tests',
    };
  }
  if (canonicalJson(before.scenarioDraftSet) !== canonicalJson(after.scenarioDraftSet)) {
    return { kind: 'SCENARIO', label: translate('Edit Scenarios'), subjectRef: 'scenarios' };
  }
  if (
    canonicalJson(before.graphInputSchema) !== canonicalJson(after.graphInputSchema)
    || canonicalJson(before.graphOutputSchema) !== canonicalJson(after.graphOutputSchema)
    || canonicalJson(before.contractDraft) !== canonicalJson(after.contractDraft)
  ) {
    return { kind: 'GRAPH_CONTRACT', label: translate('Edit Graph Contract'), subjectRef: 'contract' };
  }
  if (
    canonicalJson(before.runInputValue) !== canonicalJson(after.runInputValue)
    || canonicalJson(before.contextVariables) !== canonicalJson(after.contextVariables)
    || before.simulationContextDraft !== after.simulationContextDraft
  ) {
    return {
      kind: 'CONTEXT',
      label: translate('Edit run input'),
      subjectRef: 'run-input',
      coalesceKey: 'run-input',
    };
  }
  if (before.loadedExampleKey !== after.loadedExampleKey || before.graphName !== after.graphName) {
    return { kind: 'IMPORT', label: translate('Import {subject}', { subject: after.graphName }), subjectRef: after.graphName };
  }
  return { kind: 'OTHER', label: translate('Edit graph'), subjectRef: 'graph', coalesceKey: 'graph' };
}

function isEditableKeyboardTarget(target: EventTarget | null): boolean {
  return target instanceof HTMLElement && Boolean(
    target.closest('input, textarea, select, [contenteditable="true"], [role="dialog"]'),
  );
}

/**
 * The authoring workspace: an operator palette, a React Flow canvas, and a result inspector wired to
 * the mock-run (simulate) endpoint. Non-trivial graph<->request logic lives in the pure, unit-tested
 * {@link ./draftModel} module; this component is thin glue.
 */
export interface AuthorCanvasProps {
  workspaceVersion?: 'v1' | 'v2';
}

export default function AuthorCanvas({ workspaceVersion = 'v1' }: AuthorCanvasProps = {}) {
  const { locale, t, m, d } = useI18n();
  const isTaskWorkspace = workspaceVersion === 'v2';
  const spineEnabled = resolveSpine(window.location.search) === 'v1';
  const toolCoordinate = spineEnabled ? parseToolCoordinate(window.location.href) : null;
  const [initialWorkspaceLocation] = useState(() => (
    parseAuthorWorkspaceLocation(window.location.search)
  ));
  const [initialTaskCoordinate] = useState(() => parseTaskCoordinate(window.location.href));
  const [returnTaskCoordinate] = useState(() => parseTaskReturnCoordinate(window.location.href));
  const [sessionTenantId] = useState(() => (
    new URLSearchParams(window.location.search).get('sessionTenantId')?.trim()
    || initialTaskCoordinate.tenantId
  ));
  const [initialDslHandoff] = useState(() => (
    isTaskWorkspace ? peekDslAuthorHandoff() : null
  ));
  const [initialBusinessMirrorSeed] = useState(() => (
    isTaskWorkspace ? parseBusinessMirrorGraphSeed(window.location.search) : null
  ));
  const [authorMode, setAuthorMode] = useState<AuthorMode>(initialWorkspaceLocation.mode);
  const [startOpen, setStartOpen] = useState(
    isTaskWorkspace && !initialWorkspaceLocation.hasDeepLinkTarget
      && !initialDslHandoff && !initialBusinessMirrorSeed,
  );
  const [startSection, setStartSection] = useState<StartImportSection>(
    initialDslHandoff ? 'dsl' : 'menu',
  );
  const [paletteWidth, setPaletteWidth] = useState(220);
  const [inspectorWidth, setInspectorWidth] = useState(220);
  const [paletteCollapsed, setPaletteCollapsed] = useState(false);
  const [inspectorCollapsed, setInspectorCollapsed] = useState(false);
  const [palettePreference, setPalettePreference] = useState<CanvasPanelPreference>(
    () => initialCanvasPanelPreference('palette'),
  );
  const [inspectorPreference, setInspectorPreference] = useState<CanvasPanelPreference>(
    () => initialCanvasPanelPreference('inspector'),
  );
  const [adaptiveChromeNotice, setAdaptiveChromeNotice] = useState<MessageDescriptor | null>(null);
  const [compactWorkspace, setCompactWorkspace] = useState(() => (
    isTaskWorkspace
    && typeof window.matchMedia === 'function'
    && window.matchMedia(COMPACT_AUTHOR_MEDIA).matches
  ));
  const [formalContextRailOpen, setFormalContextRailOpen] = useState(false);
  const [diagnosticsOpen, setDiagnosticsOpen] = useState(false);
  const [operators, setOperators] = useState<OperatorDefinition[]>([]);
  const [toolPublication, setToolPublication] = useState<ToolPublicationMetadata>();
  const [toolCatalogError, setToolCatalogError] = useState('');
  const [builtInFunctions, setBuiltInFunctions] = useState<BuiltInFunctionDefinition[]>([]);
  const [nodes, setNodes] = useState<Node<NodeData>[]>([]);
  const [edges, setEdges] = useState<Edge<CanvasEdgeData>[]>([]);
  const [result, setResult] = useState<SimulationResponse | null>(null);
  const [validationResult, setValidationResult] = useState<VisualValidationResult | null>(null);
  const [authorContentEpoch, setAuthorContentEpoch] = useState(0);
  const [evidenceContentEpoch, setEvidenceContentEpoch] = useState(-1);
  const [matrixDiagnosticsSuppressed, setMatrixDiagnosticsSuppressed] = useState(false);
  const [validationContentEpoch, setValidationContentEpoch] = useState(-1);
  const [draftSaveConflict, setDraftSaveConflict] = useState<AuthorGraphSaveConflict | null>(null);
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
  const [dslSourceId, setDslSourceId] = useState(
    initialDslHandoff?.sourceId ?? LEGACY_DSL_EXAMPLES[0].sourceId,
  );
  const [dslSourceText, setDslSourceText] = useState(
    initialDslHandoff?.dsl ?? LEGACY_DSL_EXAMPLES[0].sourceText,
  );
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
  const [selectedNodeId, setSelectedNodeId] = useState(initialWorkspaceLocation.selectedNodeId);
  const [operatorDetailNodeId, setOperatorDetailNodeId] = useState('');
  const [operatorDetailBaseline, setOperatorDetailBaseline] =
    useState<OperatorDetailBaseline | null>(null);
  const [testSuiteOpen, setTestSuiteOpen] = useState(false);
  const [contractWorkspaceOpen, setContractWorkspaceOpen] = useState(false);
  const [contractWorkspaceInitialTab, setContractWorkspaceInitialTab] =
    useState<WorkspaceTab>(
      initialWorkspaceLocation.workspaceView
      || workspaceTabForMode(initialWorkspaceLocation.mode),
    );
  const [workspaceScenarioId, setWorkspaceScenarioId] =
    useState(initialWorkspaceLocation.scenarioId);
  const [contractDraft, setContractDraft] = useState<ContractDraft | null>(null);
  const [contractFingerprint, setContractFingerprint] = useState('');
  const [scenarioDraftSet, setScenarioDraftSet] = useState<ScenarioDraftSet | null>(null);
  const [loadedExampleKey, setLoadedExampleKey] = useState('');
  const workspaceForkIdempotencyKeyRef = useRef('');
  const [scenarioFingerprint, setScenarioFingerprint] = useState('');
  const [scenarioFingerprintTargetKey, setScenarioFingerprintTargetKey] = useState('');
  const scenarioFingerprintRef = useRef('');
  const [operatorClosureFingerprint, setOperatorClosureFingerprint] = useState('');
  const [lastScenarioReviewEvidence, setLastScenarioReviewEvidence] =
    useState<ScenarioReviewEvidence | null>(null);
  const [operatorContractWorkspace, setOperatorContractWorkspace] =
    useState<OperatorContractWorkspaceState | null>(null);
  const [explicitOutputNodeId, setExplicitOutputNodeId] = useState('');
  const [fixtureDrafts, setFixtureDrafts] = useState<Record<string, string>>({});
  const [fixtureInputDrafts, setFixtureInputDrafts] = useState<Record<string, string>>({});
  /** UI-only fixture lifecycle markers; durable GraphDraft carries only NodeFixture values. */
  const [fixturePinnedNodeIds, setFixturePinnedNodeIds] = useState<Set<string>>(new Set());
  const [governedFixtureRefs, setGovernedFixtureRefs] = useState<
    Record<string, GovernedGraphNodeFixtureRef>
  >({});
  const [governedFixtureAssets, setGovernedFixtureAssets] = useState<GovernedFixtureAssetSummary[]>([]);
  const [governedFixtureAssetsError, setGovernedFixtureAssetsError] = useState('');
  const [resourceFidelityByNode, setResourceFidelityByNode] = useState<Record<string, ResourceFidelity>>({});
  const [operatorTestSuites, setOperatorTestSuites] = useState<Record<string, OperatorTestSuiteDraftRow[]>>({});
  const [operatorTestResults, setOperatorTestResults] = useState<Record<string, Record<string, OperatorTestCaseResult>>>({});
  const [operatorTestPublications, setOperatorTestPublications] = useState<
    Record<string, OperatorTestSuitePublicationResult>
  >({});
  const [simulationTableRows, setSimulationTableRows] = useState<SimulationTableTestDraftRow[]>([]);
  const [simulationTableResults, setSimulationTableResults] = useState<Record<string, SimulationTableCaseResult>>({});
  const [tableTestingBusy, setTableTestingBusy] = useState(false);
  const [simulationContextDraft, setSimulationContextDraft] = useState('{}');
  const [runInputValue, setRunInputValue] = useState<Record<string, unknown>>({});
  const [rawContextMode, setRawContextMode] = useState(false);
  const [contextVariables, setContextVariables] = useState<ContextVariableRow[]>([]);
  const [graphName, setGraphName] = useState('visualGraph');
  const [graphDraftId, setGraphDraftId] = useState('');
  const [graphDraftRevision, setGraphDraftRevision] = useState(0);
  const [graphDraftStatus, setGraphDraftStatus] = useState<string | undefined>();
  const sourcePreviewReadOnly = Boolean(initialBusinessMirrorSeed && !graphDraftId);
  const lastSavedGraphRef = useRef<GraphDraft | null>(null);
  const [sourceCopyBusy, setSourceCopyBusy] = useState(false);
  const [sourceCopyError, setSourceCopyError] = useState('');
  const [graphTenantId, setGraphTenantId] = useState(initialTaskCoordinate.tenantId);
  const [graphNamespace, setGraphNamespace] = useState(initialTaskCoordinate.namespace);
  const [graphEnvironment, setGraphEnvironment] = useState(initialTaskCoordinate.environment);
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
  const [overviewVisible, setOverviewVisible] = useState(!isTaskWorkspace);
  const [viewportZoom, setViewportZoom] = useState(1);
  const [focusPathNodeId, setFocusPathNodeId] = useState('');
  const [pinnedNodeIds, setPinnedNodeIds] = useState<Set<string>>(new Set());
  const [layoutPlanning, setLayoutPlanning] = useState(false);
  const [layoutPreview, setLayoutPreview] = useState<LayoutPreviewSnapshot | null>(null);
  const [layoutUndo, setLayoutUndo] = useState<LayoutUndoSnapshot | null>(null);
  const [layoutNotice, setLayoutNotice] = useState<MessageDescriptor | null>(null);
  const [mutationJournal, setMutationJournal] = useState<MutationJournalState<AuthoringMutationSnapshot>>(
    () => initialMutationJournal<AuthoringMutationSnapshot>(),
  );
  const [pendingNodeDeletion, setPendingNodeDeletion] = useState<PendingNodeDeletion | null>(null);
  const [pendingProductionCommand, setPendingProductionCommand] =
    useState<PendingProductionCommand | null>(null);
  const [mutationNotice, setMutationNotice] = useState<AuthorMutationNotice | null>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const flowRef = useRef<HTMLDivElement>(null);
  const overviewBeforeFocusRef = useRef(!isTaskWorkspace);
  useEffect(() => {
    const localizeControls = () => {
      const labels = [
        ['.react-flow__controls-zoomin', t('Zoom in')],
        ['.react-flow__controls-zoomout', t('Zoom out')],
        ['.react-flow__controls-fitview', t('Fit view')],
        ['.react-flow__controls-interactive', t('Toggle interactivity')],
      ] as const;
      labels.forEach(([selector, label]) => {
        const control = flowRef.current?.querySelector<HTMLButtonElement>(selector);
        control?.setAttribute('aria-label', label);
        control?.setAttribute('title', label);
      });
    };
    localizeControls();
    const observer = new MutationObserver(localizeControls);
    if (flowRef.current) {
      observer.observe(flowRef.current, { childList: true, subtree: true });
    }
    return () => observer.disconnect();
  }, [locale, t]);
  const testSuiteDialogRef = useRef<HTMLElement>(null);
  const flowInstanceRef = useRef<ReactFlowInstance<NodeData, CanvasEdgeData> | null>(null);
  const authorSessionStartedAtRef = useRef(performance.now());
  const authorWorkspaceEventRecordedRef = useRef(false);
  const firstAuthorSuccessRecordedRef = useRef(false);
  const successfulRunKindRef = useRef('');
  const previousAuthorModeRef = useRef(authorMode);
  const layoutPlanSequenceRef = useRef(0);
  const layoutPlanTimerRef = useRef<number | null>(null);
  const fitCanvasTimerRef = useRef<number | null>(null);
  const counter = useRef(0);
  const contextVariableCounter = useRef(0);
  const tableTestCounter = useRef(0);
  const operatorTestCounter = useRef(0);
  const candidatePreviewSequence = useRef(0);
  const connectionGuideSequence = useRef(0);
  const contractProjectionSequence = useRef(0);
  const contractExecutionSnapshotRef = useRef('');
  const scenarioGraphNameRef = useRef('');
  const dslHandoffStartedRef = useRef(false);
  const initialOperatorTargetRestoredRef = useRef(false);
  const mutationJournalRef = useRef(mutationJournal);
  const mutationBaselineRef = useRef<{
    snapshot: AuthoringMutationSnapshot;
    fingerprint: string;
  } | null>(null);
  const mutationSequenceRef = useRef(0);
  const pendingMutationDescriptorRef = useRef<PendingMutationDescriptor | null>(null);
  const recentMutationDescriptorRef = useRef<{
    descriptor: PendingMutationDescriptor;
    recordedAt: number;
  } | null>(null);
  const suppressDerivedMutationUntilRef = useRef(0);
  const mutationObservationModeRef = useRef<'observe' | 'reset' | 'hold'>('observe');
  const authoritativeContractRef = useRef<{
    canvasSnapshot: string;
    executionSnapshot: string;
    graphDraft: GraphDraft;
    contract: ContractDraft;
    contractFingerprint: string;
  } | null>(null);

  const authoringMutationSnapshot = useMemo<AuthoringMutationSnapshot>(() => ({
    nodes,
    edges,
    fixtureDrafts,
    fixtureInputDrafts,
    operatorTestSuites,
    operatorTestResults,
    operatorTestPublications,
    simulationTableRows,
    simulationTableResults,
    runInputValue,
    simulationContextDraft,
    rawContextMode,
    contextVariables,
    scenarioDraftSet,
    contractDraft,
    contractFingerprint,
    explicitOutputNodeId,
    selectedNodeId,
    pinnedNodeIds: Array.from(pinnedNodeIds).sort(),
    graphName,
    graphInputSchema,
    graphOutputSchema,
    graphContractSource,
    graphVisualLayout,
    graphOperatorFingerprints,
    graphOperatorSnapshots,
    loadedExampleKey,
  }), [
    contextVariables,
    contractDraft,
    contractFingerprint,
    edges,
    explicitOutputNodeId,
    fixtureDrafts,
    fixtureInputDrafts,
    graphContractSource,
    graphInputSchema,
    graphName,
    graphOperatorFingerprints,
    graphOperatorSnapshots,
    graphOutputSchema,
    graphVisualLayout,
    loadedExampleKey,
    nodes,
    operatorTestPublications,
    operatorTestResults,
    operatorTestSuites,
    pinnedNodeIds,
    rawContextMode,
    runInputValue,
    scenarioDraftSet,
    selectedNodeId,
    simulationContextDraft,
    simulationTableResults,
    simulationTableRows,
  ]);
  const currentMutationFingerprint = useMemo(
    () => authoringMutationFingerprint(authoringMutationSnapshot),
    [authoringMutationSnapshot],
  );
  const workspaceTaskCoordinate = useMemo<TaskCoordinate>(() => {
    const surface = authorMode === 'contract'
      ? 'CONTRACT' as const
      : authorMode === 'scenarios'
        ? 'SCENARIO' as const
        : authorMode === 'evidence' ? 'EVIDENCE' as const : 'COMPOSE' as const;
    const selectedRunId = deepLinkRun?.runId || initialWorkspaceLocation.runId;
    const operatorRef = operatorContractWorkspace?.contract.target.id ?? '';
    const subject = surface === 'EVIDENCE' && selectedRunId
      ? { kind: 'RUN' as const, ref: selectedRunId }
      : (surface === 'SCENARIO' || surface === 'EVIDENCE') && workspaceScenarioId
        ? { kind: 'CASE' as const, ref: workspaceScenarioId }
        : operatorRef
          ? { kind: 'OPERATOR' as const, ref: operatorRef }
          : surface === 'COMPOSE' && selectedNodeId
            ? { kind: 'NODE' as const, ref: selectedNodeId }
            : { kind: 'GRAPH' as const, ref: graphDraftId || graphName };
    return {
      tenantId: graphTenantId,
      namespace: graphNamespace,
      environment: graphEnvironment,
      draftId: graphDraftId,
      revision: graphDraftRevision,
      surface,
      subjectKind: subject.kind,
      subjectRef: subject.ref,
      selectionFingerprint: currentMutationFingerprint,
      role: initialTaskCoordinate.role,
      capabilityFingerprint: initialTaskCoordinate.capabilityFingerprint,
      selection: {
        nodeId: selectedNodeId,
        caseId: workspaceScenarioId,
        runId: selectedRunId,
      },
    };
  }, [
    authorMode,
    currentMutationFingerprint,
    deepLinkRun?.runId,
    graphDraftId,
    graphDraftRevision,
    graphEnvironment,
    graphName,
    graphNamespace,
    graphTenantId,
    initialTaskCoordinate.capabilityFingerprint,
    initialTaskCoordinate.role,
    initialWorkspaceLocation.runId,
    operatorContractWorkspace,
    selectedNodeId,
    workspaceScenarioId,
  ]);
  const mutationCommandPolicy = useMemo(() => evaluateTaskCommandAuthority({
    commandId: 'MUTATE_AUTHORING_WORKSPACE',
    risk: 'MUTATE',
    coordinate: workspaceTaskCoordinate,
    sessionTenantId,
  }), [sessionTenantId, workspaceTaskCoordinate]);
  const destructiveCommandPolicy = useMemo(() => evaluateTaskCommandAuthority({
    commandId: 'DELETE_AUTHORING_ASSET',
    risk: 'DESTRUCTIVE',
    coordinate: workspaceTaskCoordinate,
    sessionTenantId,
  }), [sessionTenantId, workspaceTaskCoordinate]);
  const requestDestructiveCommand = useCallback((
    commandLabel: string,
    targetLabel: string,
    execute: () => void,
  ) => {
    if (!destructiveCommandPolicy.enabled) {
      setError(t('This role or tenant scope cannot change authoring assets.'));
      return;
    }
    if (destructiveCommandPolicy.requiresExplicitConfirmation) {
      setPendingProductionCommand({ commandLabel, targetLabel, execute });
      return;
    }
    execute();
  }, [destructiveCommandPolicy.enabled, destructiveCommandPolicy.requiresExplicitConfirmation, t]);

  const replaceMutationJournal = useCallback((next: MutationJournalState<AuthoringMutationSnapshot>) => {
    mutationJournalRef.current = next;
    setMutationJournal(next);
  }, []);

  const restoreMutationSnapshot = useCallback((snapshot: AuthoringMutationSnapshot) => {
    mutationObservationModeRef.current = 'reset';
    mutationBaselineRef.current = {
      snapshot: structuredClone(snapshot),
      fingerprint: authoringMutationFingerprint(snapshot),
    };
    recentMutationDescriptorRef.current = null;
    suppressDerivedMutationUntilRef.current = Date.now() + 1_000;
    setNodes(structuredClone(snapshot.nodes));
    setEdges(structuredClone(snapshot.edges));
    setFixtureDrafts(structuredClone(snapshot.fixtureDrafts));
    setFixtureInputDrafts(structuredClone(snapshot.fixtureInputDrafts));
    setOperatorTestSuites(structuredClone(snapshot.operatorTestSuites));
    setOperatorTestResults(structuredClone(snapshot.operatorTestResults));
    setOperatorTestPublications(structuredClone(snapshot.operatorTestPublications));
    setSimulationTableRows(structuredClone(snapshot.simulationTableRows));
    setSimulationTableResults(structuredClone(snapshot.simulationTableResults));
    setRunInputValue(structuredClone(snapshot.runInputValue));
    setSimulationContextDraft(snapshot.simulationContextDraft);
    setRawContextMode(snapshot.rawContextMode);
    setContextVariables(structuredClone(snapshot.contextVariables));
    setScenarioDraftSet(structuredClone(snapshot.scenarioDraftSet));
    setContractDraft(structuredClone(snapshot.contractDraft));
    setContractFingerprint(snapshot.contractFingerprint);
    setExplicitOutputNodeId(snapshot.explicitOutputNodeId);
    setSelectedNodeId(snapshot.selectedNodeId);
    setPinnedNodeIds(new Set(snapshot.pinnedNodeIds));
    setGraphName(snapshot.graphName);
    setGraphInputSchema(structuredClone(snapshot.graphInputSchema));
    setGraphOutputSchema(structuredClone(snapshot.graphOutputSchema));
    setGraphContractSource(snapshot.graphContractSource);
    setGraphVisualLayout(structuredClone(snapshot.graphVisualLayout));
    setGraphOperatorFingerprints(structuredClone(snapshot.graphOperatorFingerprints));
    setGraphOperatorSnapshots(structuredClone(snapshot.graphOperatorSnapshots));
    setLoadedExampleKey(snapshot.loadedExampleKey);
    counter.current = maxCanvasNodeSequence(snapshot.nodes.map(canvasNodeFromFlowNode));
    tableTestCounter.current = snapshot.simulationTableRows.length;
    operatorTestCounter.current = Object.values(snapshot.operatorTestSuites)
      .reduce((total, rows) => total + rows.length, 0);
    setOperatorDetailNodeId('');
    setOperatorDetailBaseline(null);
    setPendingNodeDeletion(null);
    setConnectionGuide(null);
    setLayoutPlanning(false);
    setLayoutPreview(null);
    setLayoutUndo(null);
    setValidationContentEpoch(-1);
    setAuthorContentEpoch((current) => current + 1);
    setError('');
  }, []);

  const undoAuthoringMutation = useCallback(() => {
    const transition = undoMutation(mutationJournalRef.current);
    if (!transition) return;
    replaceMutationJournal(transition.journal);
    restoreMutationSnapshot(transition.snapshot);
    setMutationNotice({
      message: t('Undid {change}.', { change: transition.mutation.label }),
      action: 'redo',
    });
    recordAuthorTaskEvent('AUTHOR_MUTATION_UNDONE', {
      mutationKind: transition.mutation.kind,
    });
  }, [replaceMutationJournal, restoreMutationSnapshot, t]);

  const redoAuthoringMutation = useCallback(() => {
    const transition = redoMutation(mutationJournalRef.current);
    if (!transition) return;
    replaceMutationJournal(transition.journal);
    restoreMutationSnapshot(transition.snapshot);
    setMutationNotice({
      message: t('Redid {change}.', { change: transition.mutation.label }),
      action: 'undo',
    });
    recordAuthorTaskEvent('AUTHOR_MUTATION_REDONE', {
      mutationKind: transition.mutation.kind,
    });
  }, [replaceMutationJournal, restoreMutationSnapshot, t]);

  useEffect(() => {
    if (!isTaskWorkspace) return;
    const current = {
      snapshot: structuredClone(authoringMutationSnapshot),
      fingerprint: currentMutationFingerprint,
    };
    const previous = mutationBaselineRef.current;
    if (!previous) {
      mutationBaselineRef.current = current;
      mutationObservationModeRef.current = 'observe';
      return;
    }
    if (mutationObservationModeRef.current === 'reset') {
      mutationObservationModeRef.current = 'observe';
      mutationBaselineRef.current = current;
      pendingMutationDescriptorRef.current = null;
      return;
    }
    if (mutationObservationModeRef.current === 'hold') return;
    if (previous.fingerprint === current.fingerprint) {
      mutationBaselineRef.current = current;
      return;
    }
    const inferred = inferMutationDescriptor(previous.snapshot, current.snapshot, t);
    if (
      Date.now() <= suppressDerivedMutationUntilRef.current
      && (inferred.kind === 'GRAPH_CONTRACT' || inferred.kind === 'SCENARIO')
    ) {
      mutationBaselineRef.current = current;
      pendingMutationDescriptorRef.current = null;
      return;
    }
    const recent = recentMutationDescriptorRef.current;
    const derivedContinuation = !pendingMutationDescriptorRef.current
      && recent
      && Date.now() - recent.recordedAt <= 750
      && (inferred.kind === 'GRAPH_CONTRACT' || inferred.kind === 'SCENARIO');
    const descriptor = pendingMutationDescriptorRef.current
      ?? (derivedContinuation ? recent.descriptor : inferred);
    pendingMutationDescriptorRef.current = null;
    mutationSequenceRef.current += 1;
    const mutation = createMutation({
      mutationId: `author-mutation-${mutationSequenceRef.current}`,
      ...descriptor,
      before: previous.snapshot,
      after: current.snapshot,
    });
    replaceMutationJournal(recordMutation(mutationJournalRef.current, mutation));
    recentMutationDescriptorRef.current = { descriptor, recordedAt: mutation.occurredAt };
    mutationBaselineRef.current = current;
    if (!derivedContinuation) {
      setValidationContentEpoch(-1);
      setAuthorContentEpoch((epoch) => epoch + 1);
    }
    recordAuthorTaskEvent('AUTHOR_MUTATION_RECORDED', {
      mutationKind: mutation.kind,
      impactCount: mutation.impact.reduce((total, item) => total + item.count, 0),
      historyDepth: mutationJournalRef.current.past.length,
    });
  }, [authoringMutationSnapshot, currentMutationFingerprint, isTaskWorkspace, replaceMutationJournal, t]);

  const commitHeldMutation = useCallback((descriptor: PendingMutationDescriptor) => {
    const previous = mutationBaselineRef.current;
    if (!previous || previous.fingerprint === currentMutationFingerprint) {
      mutationObservationModeRef.current = 'observe';
      return;
    }
    mutationSequenceRef.current += 1;
    const current = {
      snapshot: structuredClone(authoringMutationSnapshot),
      fingerprint: currentMutationFingerprint,
    };
    const mutation = createMutation({
      mutationId: `author-mutation-${mutationSequenceRef.current}`,
      ...descriptor,
      before: previous.snapshot,
      after: current.snapshot,
    });
    replaceMutationJournal(recordMutation(mutationJournalRef.current, mutation));
    recentMutationDescriptorRef.current = { descriptor, recordedAt: mutation.occurredAt };
    mutationBaselineRef.current = current;
    mutationObservationModeRef.current = 'observe';
    setValidationContentEpoch(-1);
    setAuthorContentEpoch((epoch) => epoch + 1);
    recordAuthorTaskEvent('AUTHOR_MUTATION_RECORDED', {
      mutationKind: mutation.kind,
      impactCount: mutation.impact.reduce((total, item) => total + item.count, 0),
      historyDepth: mutationJournalRef.current.past.length,
    });
  }, [authoringMutationSnapshot, currentMutationFingerprint, replaceMutationJournal]);

  const reloadOperators = useCallback(async () => {
    const catalog = await fetchOperatorCatalog();
    setOperators(catalog.operators);
    setBuiltInFunctions(catalog.builtInFunctions ?? []);
  }, []);

  const refreshToolCatalog = useCallback(async () => {
    try {
      await reloadOperators();
      setToolCatalogError('');
    } catch {
      setToolCatalogError('Tool published, but the operator catalog could not be refreshed. Retry refresh.');
    }
  }, [reloadOperators]);

  const handleToolPublished = useCallback(async (publication: ToolPublicationMetadata) => {
    setToolPublication(publication);
    await refreshToolCatalog();
  }, [refreshToolCatalog]);

  useEffect(() => {
    if (!isTaskWorkspace || typeof window.matchMedia !== 'function') {
      return undefined;
    }
    const media = window.matchMedia(COMPACT_AUTHOR_MEDIA);
    const synchronize = (matches: boolean) => {
      setCompactWorkspace(matches);
    };
    synchronize(media.matches);
    const onChange = (event: MediaQueryListEvent) => synchronize(event.matches);
    media.addEventListener?.('change', onChange);
    return () => media.removeEventListener?.('change', onChange);
  }, [isTaskWorkspace]);

  useEffect(() => {
    setFormalContextRailOpen(false);
  }, [authorMode, compactWorkspace]);

  useEffect(() => {
    try {
      window.localStorage.setItem(
        `${CANVAS_PANEL_PREFERENCE_PREFIX}palette`,
        palettePreference,
      );
      window.localStorage.setItem(
        `${CANVAS_PANEL_PREFERENCE_PREFIX}inspector`,
        inspectorPreference,
      );
    } catch {
      // Browser privacy modes may disable local storage; session behavior remains intact.
    }
  }, [inspectorPreference, palettePreference]);

  useEffect(() => () => {
    layoutPlanSequenceRef.current += 1;
    if (layoutPlanTimerRef.current !== null) {
      window.clearTimeout(layoutPlanTimerRef.current);
    }
  }, []);

  useEffect(() => {
    reloadOperators()
      .catch((cause: unknown) => setError(String(cause)));
  }, [reloadOperators]);

  useEffect(() => {
    if (!spineEnabled) return undefined;
    let active = true;
    const selectedResourceRef = nodes.find((node) => node.id === selectedNodeId)?.data.operatorRef;
    fetchGovernedFixtureAssets(selectedResourceRef)
      .then((assets) => {
        if (active) {
          setGovernedFixtureAssets(assets);
          setGovernedFixtureAssetsError('');
        }
      })
      .catch((cause: unknown) => {
        if (active) {
          setGovernedFixtureAssets([]);
          setGovernedFixtureAssetsError(
            cause instanceof Error ? cause.message : 'Governed fixture catalogue unavailable.',
          );
        }
      });
    return () => {
      active = false;
    };
  }, [
    spineEnabled,
    nodes,
    selectedNodeId,
  ]);

  useEffect(() => {
    if (!isTaskWorkspace || authorWorkspaceEventRecordedRef.current) {
      return;
    }
    authorWorkspaceEventRecordedRef.current = true;
    recordAuthorTaskEvent('WORKSPACE_OPENED', {
      workspaceVersion,
      nodeCount: nodes.length,
    });
  }, [isTaskWorkspace, nodes.length, workspaceVersion]);

  useEffect(() => {
    const previousMode = previousAuthorModeRef.current;
    previousAuthorModeRef.current = authorMode;
    if (isTaskWorkspace && previousMode !== authorMode) {
      recordAuthorTaskEvent('MODE_CHANGED', {
        previousMode,
        nextMode: authorMode,
      });
    }
  }, [authorMode, isTaskWorkspace]);

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

  const fitCanvasToView = useCallback((
    graphSize?: { nodeCount: number; edgeCount: number },
    requestedTaskMode?: CanvasTaskMode,
  ) => {
    const complex = graphSize
      ? graphSize.nodeCount >= COMPLEX_GRAPH_NODE_THRESHOLD
        || graphSize.edgeCount >= COMPLEX_GRAPH_EDGE_THRESHOLD
      : isComplexGraph;
    const fitTaskMode: CanvasTaskMode = requestedTaskMode ?? (focusPathNodeId
      ? 'focus'
      : selectedNodeId
        ? 'inspect'
        : 'overview');
    const minimumZoom = isTaskWorkspace
      ? semanticZoomContract(fitTaskMode).minimumZoom
      : CANVAS_MIN_ZOOM;
    const fitOptions = {
      // Preserve readable type; a post-fit pan below contains labels outside the node bounds.
      padding: complex ? 0.14 : 0.1,
      duration: 240,
      minZoom: minimumZoom,
      maxZoom: 1,
    };
    const containSemanticLabels = () => {
      const instance = flowInstanceRef.current;
      const flow = flowRef.current;
      if (!instance?.getViewport || !instance?.setViewport || !flow) return;
      const visible = [...flow.querySelectorAll<HTMLElement>(
        '.react-flow__node, [data-testid="canvas-edge-label"]',
      )].filter((element) => {
        const rect = element.getBoundingClientRect();
        return getComputedStyle(element).display !== 'none' && rect.width > 0 && rect.height > 0;
      });
      if (visible.length === 0) {
        flow.dataset.canvasViewportSettled = 'true';
        return;
      }
      const rects = visible.map((element) => element.getBoundingClientRect());
      const content = {
        left: Math.min(...rects.map((rect) => rect.left)),
        right: Math.max(...rects.map((rect) => rect.right)),
        top: Math.min(...rects.map((rect) => rect.top)),
        bottom: Math.max(...rects.map((rect) => rect.bottom)),
      };
      const viewport = instance.getViewport();
      const renderingViewport = flow.querySelector<HTMLElement>('.react-flow') ?? flow;
      const contained = containedViewportTransform(
        renderingViewport.getBoundingClientRect(),
        content,
        viewport,
        minimumZoom,
        2,
      );
      const changed = Math.abs(contained.x - viewport.x) >= 1
        || Math.abs(contained.y - viewport.y) >= 1
        || Math.abs(contained.zoom - viewport.zoom) >= 0.001;
      if (!changed) {
        flow.dataset.canvasViewportSettled = 'true';
        refreshViewportZoom();
        return;
      }
      instance.setViewport(contained);
      const markSettled = () => {
        flow.dataset.canvasViewportSettled = 'true';
        refreshViewportZoom();
      };
      if (typeof window.requestAnimationFrame === 'function') {
        window.requestAnimationFrame(markSettled);
      } else {
        markSettled();
      }
    };
    if (flowRef.current) flowRef.current.dataset.canvasViewportSettled = 'pending';
    flowInstanceRef.current?.fitView(fitOptions);
    if (fitCanvasTimerRef.current !== null) {
      window.clearTimeout(fitCanvasTimerRef.current);
    }
    fitCanvasTimerRef.current = window.setTimeout(() => {
      flowInstanceRef.current?.fitView(fitOptions);
      fitCanvasTimerRef.current = window.setTimeout(() => {
        fitCanvasTimerRef.current = null;
        containSemanticLabels();
      }, fitOptions.duration + 24);
    }, 80);
    const updateZoom = () => refreshViewportZoom();
    if (typeof window.requestAnimationFrame === 'function') {
      window.requestAnimationFrame(updateZoom);
    } else {
      window.setTimeout(updateZoom, 0);
    }
  }, [focusPathNodeId, isComplexGraph, isTaskWorkspace, refreshViewportZoom, selectedNodeId]);

  useEffect(() => () => {
    if (fitCanvasTimerRef.current !== null) {
      window.clearTimeout(fitCanvasTimerRef.current);
    }
  }, []);

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
      if (fitCanvasTimerRef.current !== null) {
        window.clearTimeout(fitCanvasTimerRef.current);
        fitCanvasTimerRef.current = null;
      }
      const selected = isTaskWorkspace
        ? nodes.find((node) => node.id === selectedNodeId)
        : undefined;
      if (selected) {
        const adjacentIds = new Set<string>();
        edges.forEach((edge) => {
          if (edge.source === selected.id) adjacentIds.add(edge.target);
          if (edge.target === selected.id) adjacentIds.add(edge.source);
        });
        const nearestAdjacent = nodes
          .filter((node) => adjacentIds.has(node.id))
          .sort((left, right) => {
            const leftDistance = ((left.position.x - selected.position.x) ** 2)
              + ((left.position.y - selected.position.y) ** 2);
            const rightDistance = ((right.position.x - selected.position.x) ** 2)
              + ((right.position.y - selected.position.y) ** 2);
            return leftDistance - rightDistance || left.id.localeCompare(right.id);
          })
          .slice(0, 1);
        const neighborhood = [selected, ...nearestAdjacent];
        const minimumZoom = semanticZoomContract('inspect').minimumZoom;
        const bounds = neighborhood.reduce((current, node) => ({
          left: Math.min(current.left, node.position.x),
          right: Math.max(current.right, node.position.x + (node.width ?? 240)),
          top: Math.min(current.top, node.position.y),
          bottom: Math.max(current.bottom, node.position.y + (node.height ?? 164)),
        }), {
          left: Number.POSITIVE_INFINITY,
          right: Number.NEGATIVE_INFINITY,
          top: Number.POSITIVE_INFINITY,
          bottom: Number.NEGATIVE_INFINITY,
        });
        const instance = flowInstanceRef.current;
        if (!instance) return;
        const renderingViewport = flowRef.current
          ?.querySelector<HTMLElement>('.react-flow')
          ?.getBoundingClientRect();
        const neighborhoodWidth = Math.max(1, bounds.right - bounds.left);
        const neighborhoodHeight = Math.max(1, bounds.bottom - bounds.top);
        const widthFit = renderingViewport && renderingViewport.width > 0
          ? (renderingViewport.width * 0.76) / neighborhoodWidth
          : minimumZoom;
        const heightFit = renderingViewport && renderingViewport.height > 0
          ? (renderingViewport.height * 0.76) / neighborhoodHeight
          : minimumZoom;
        const targetZoom = Math.max(minimumZoom, Math.min(1, widthFit, heightFit));
        void instance.setCenter(
          (bounds.left + bounds.right) / 2,
          (bounds.top + bounds.bottom) / 2,
          { zoom: targetZoom, duration: 240 },
        );
        window.setTimeout(refreshViewportZoom, 256);
      } else {
        fitCanvasToView();
      }
    }, 80);
    return () => window.clearTimeout(handle);
  }, [
    canvasFocusMode,
    edges,
    fitCanvasToView,
    isTaskWorkspace,
    nodes,
    refreshViewportZoom,
    selectedNodeId,
  ]);

  useEffect(() => {
    if (!layoutPreview) {
      return undefined;
    }
    const handle = window.setTimeout(() => fitCanvasToView(undefined, 'overview'), 80);
    return () => window.clearTimeout(handle);
  }, [fitCanvasToView, layoutPreview]);

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
    setAuthorContentEpoch((current) => current + 1);
    setSimulationTableResults({});
    setError('');
  }, []);

  const resetRunResult = useCallback(() => {
    layoutPlanSequenceRef.current += 1;
    if (layoutPlanTimerRef.current !== null) {
      window.clearTimeout(layoutPlanTimerRef.current);
      layoutPlanTimerRef.current = null;
    }
    setLayoutPlanning(false);
    setLayoutPreview(null);
    setAuthorContentEpoch((current) => current + 1);
    setEvidenceContentEpoch(-1);
    setValidationContentEpoch(-1);
    setResult(null);
    setValidationResult(null);
    setSimulationTableResults({});
    setLastScenarioReviewEvidence(null);
    setError('');
  }, []);

  const deleteNodesAtomically = useCallback((
    nodeIds: string[],
    impact: NodeDeletionImpact,
  ) => {
    const selected = new Set(nodeIds);
    const nodeLabels = nodes
      .filter((node) => selected.has(node.id))
      .map((node) => node.data.label);
    pendingMutationDescriptorRef.current = {
      kind: 'REMOVE_NODE',
      label: t('Delete {subject}', { subject: nodeLabels.join(', ') || nodeIds.join(', ') }),
      subjectRef: nodeIds.join(','),
      impact: impact.items,
      coalesceKey: `delete-nodes:${nodeIds.join(',')}`,
    };
    clearRunResult();
    setConnectionGuide(null);
    setLayoutUndo(null);
    setLayoutNotice(null);
    setEdges((current) => current.filter((edge) => (
      !selected.has(edge.source) && !selected.has(edge.target)
    )));
    setNodes((current) => current.filter((node) => !selected.has(node.id)));
    setFixtureDrafts((current) => omitRecordKeys(current, nodeIds));
    setFixtureInputDrafts((current) => omitRecordKeys(current, nodeIds));
    setGovernedFixtureRefs((current) => omitRecordKeys(current, nodeIds));
    setFixturePinnedNodeIds((current) => {
      const next = new Set(current);
      nodeIds.forEach((nodeId) => next.delete(nodeId));
      return next;
    });
    setOperatorTestSuites((current) => omitRecordKeys(current, nodeIds));
    setOperatorTestResults((current) => omitRecordKeys(current, nodeIds));
    setOperatorTestPublications((current) => omitRecordKeys(current, nodeIds));
    setSelectedNodeId((current) => (selected.has(current) ? '' : current));
    setFocusPathNodeId((current) => (selected.has(current) ? '' : current));
    setPinnedNodeIds((current) => {
      const next = new Set(current);
      nodeIds.forEach((nodeId) => next.delete(nodeId));
      return next;
    });
    setOperatorDetailNodeId((current) => (selected.has(current) ? '' : current));
    setOperatorDetailBaseline((current) => current && selected.has(current.nodeId) ? null : current);
    setExplicitOutputNodeId((current) => (selected.has(current) ? '' : current));
    setPendingNodeDeletion(null);
    setMutationNotice({
      message: t('Deleted {node}.', { node: nodeLabels.join(', ') || nodeIds.join(', ') }),
      action: 'undo',
    });
  }, [clearRunResult, nodes, t]);

  const requestNodeDeletion = useCallback((nodeIds: string[]) => {
    if (!destructiveCommandPolicy.enabled) {
      setError(t('This role or tenant scope cannot delete authoring assets.'));
      return;
    }
    const existingNodeIds = nodeIds.filter((nodeId) => nodes.some((node) => node.id === nodeId));
    if (existingNodeIds.length === 0) return;
    const impact = projectNodeDeletionImpact(existingNodeIds, {
      edges,
      fixtureDrafts,
      fixtureInputDrafts,
      operatorTestSuites,
      operatorTestResults,
      operatorTestPublications,
      explicitOutputNodeId,
    });
    const nodeLabels = nodes
      .filter((node) => existingNodeIds.includes(node.id))
      .map((node) => node.data.label);
    if (impact.requiresConfirmation || destructiveCommandPolicy.requiresExplicitConfirmation) {
      setPendingNodeDeletion({
        nodeIds: existingNodeIds,
        nodeLabels,
        impact,
        productionSafeguard: destructiveCommandPolicy.requiresExplicitConfirmation,
      });
      return;
    }
    deleteNodesAtomically(existingNodeIds, impact);
  }, [
    deleteNodesAtomically,
    destructiveCommandPolicy.enabled,
    destructiveCommandPolicy.requiresExplicitConfirmation,
    edges,
    explicitOutputNodeId,
    fixtureDrafts,
    fixtureInputDrafts,
    nodes,
    operatorTestPublications,
    operatorTestResults,
    operatorTestSuites,
    t,
  ]);

  const deleteEdgesAtomically = useCallback((edgeIds: string[]) => {
    if (edgeIds.length === 0) return;
    pendingMutationDescriptorRef.current = {
      kind: 'REMOVE_EDGE',
      label: t(edgeIds.length === 1 ? 'Delete connection' : 'Delete connections'),
      subjectRef: edgeIds.join(','),
      impact: [{ kind: 'EDGE', count: edgeIds.length, refs: edgeIds, severity: 'WARNING' }],
      coalesceKey: `delete-edges:${edgeIds.join(',')}`,
    };
    clearRunResult();
    setConnectionGuide(null);
    const selected = new Set(edgeIds);
    setEdges((current) => current.filter((edge) => !selected.has(edge.id)));
    setMutationNotice({
      message: t('Deleted {count} connection(s).', { count: edgeIds.length }),
      action: 'undo',
    });
  }, [clearRunResult, t]);

  const requestEdgeDeletion = useCallback((edgeIds: string[]) => {
    if (edgeIds.length === 0) return;
    requestDestructiveCommand(
      t(edgeIds.length === 1 ? 'Delete connection' : 'Delete connections'),
      edgeIds.join(', '),
      () => deleteEdgesAtomically(edgeIds),
    );
  }, [deleteEdgesAtomically, requestDestructiveCommand, t]);

  const onNodesChange = useCallback(
    (changes: NodeChange[]) => {
      const removedNodeIds = changes
        .filter((change): change is NodeChange & { type: 'remove'; id: string } => change.type === 'remove')
        .map((change) => change.id);
      if (changes.some((change) => change.type === 'add')) {
        clearRunResult();
        setConnectionGuide(null);
        setLayoutUndo(null);
        setLayoutNotice(null);
      }
      if (changes.some((change) => change.type === 'position')) {
        setLayoutUndo(null);
        setLayoutNotice(null);
      }
      if (removedNodeIds.length > 0) {
        requestNodeDeletion(removedNodeIds);
      }
      const retainedChanges = changes.filter((change) => change.type !== 'remove');
      if (retainedChanges.length > 0) {
        setNodes((current) => applyNodeChanges(retainedChanges, current) as Node<NodeData>[]);
      }
    },
    [clearRunResult, requestNodeDeletion],
  );
  const onEdgesChange = useCallback(
    (changes: EdgeChange[]) => {
      if (changes.some((change) => change.type === 'add')) {
        clearRunResult();
        setConnectionGuide(null);
      }
      const removedEdgeIds = changes
        .filter((change): change is EdgeChange & { type: 'remove'; id: string } => change.type === 'remove')
        .map((change) => change.id);
      if (removedEdgeIds.length > 0) requestEdgeDeletion(removedEdgeIds);
      const retainedChanges = changes.filter((change) => change.type !== 'remove');
      if (retainedChanges.length > 0) {
        setEdges((current) => applyEdgeChanges(retainedChanges, current) as Edge<CanvasEdgeData>[]);
      }
    },
    [clearRunResult, requestEdgeDeletion],
  );

  useEffect(() => {
    if (!isTaskWorkspace) return undefined;
    const onKeyDown = (event: KeyboardEvent) => {
      if (isEditableKeyboardTarget(event.target)) return;
      const commandModifier = event.metaKey || event.ctrlKey;
      const key = event.key.toLowerCase();
      if (commandModifier && key === 'z') {
        event.preventDefault();
        if (event.shiftKey) {
          redoAuthoringMutation();
        } else {
          undoAuthoringMutation();
        }
        return;
      }
      if (event.ctrlKey && key === 'y') {
        event.preventDefault();
        redoAuthoringMutation();
        return;
      }
      if (
        (event.key === 'Backspace' || event.key === 'Delete')
        && authorMode === 'compose'
        && !layoutPlanning
        && !layoutPreview
        && !pendingNodeDeletion
        && !document.querySelector('[role="dialog"][aria-modal="true"]')
      ) {
        if (selectedNodeId) {
          event.preventDefault();
          requestNodeDeletion([selectedNodeId]);
          return;
        }
        const selectedEdgeIds = edges.filter((edge) => edge.selected).map((edge) => edge.id);
        if (selectedEdgeIds.length > 0) {
          event.preventDefault();
          requestEdgeDeletion(selectedEdgeIds);
        }
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [
    authorMode,
    requestEdgeDeletion,
    edges,
    isTaskWorkspace,
    layoutPlanning,
    layoutPreview,
    pendingNodeDeletion,
    redoAuthoringMutation,
    requestNodeDeletion,
    selectedNodeId,
    undoAuthoringMutation,
  ]);
  const addOperator = useCallback((operator: OperatorDefinition, position?: { x: number; y: number }) => {
    if (layoutPlanning || layoutPreview) {
      return;
    }
    clearRunResult();
    setConnectionGuide(null);
    setLayoutUndo(null);
    setLayoutNotice(null);
    const nextIndex = counter.current + 1;
    counter.current = nextIndex;
    const id = `n${nextIndex}`;
    const placementIndex = nextIndex - 1;
    const canvasWidth = flowRef.current?.clientWidth ?? 0;
    const summary = summarizeOperator(operator);
    pendingMutationDescriptorRef.current = {
      kind: 'ADD_NODE',
      label: t('{action} {subject}', { action: t('Add'), subject: summary.name }),
      subjectRef: id,
      coalesceKey: `add-node:${id}`,
    };
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
  }, [clearRunResult, layoutPlanning, layoutPreview, t]);

  const openNodeEditor = useCallback((node: Node<NodeData>) => {
    if (isTaskWorkspace) mutationObservationModeRef.current = 'hold';
    const authoredNode = nodes.find((candidate) => candidate.id === node.id) ?? node;
    setSelectedNodeId(node.id);
    setOperatorDetailBaseline({
      nodeId: node.id,
      nodeData: structuredClone(authoredNode.data),
      fixtureDraft: fixtureDrafts[node.id],
      fixtureInputDraft: fixtureInputDrafts[node.id],
      testRows: operatorTestSuites[node.id]
        ? structuredClone(operatorTestSuites[node.id])
        : undefined,
    });
    setOperatorDetailNodeId(node.id);
  }, [fixtureDrafts, fixtureInputDrafts, isTaskWorkspace, nodes, operatorTestSuites]);

  const applyOperatorDetail = useCallback(() => {
    if (operatorDetailNodeId) {
      const node = nodes.find((candidate) => candidate.id === operatorDetailNodeId);
      commitHeldMutation({
        kind: node?.data.summary.visualKind === 'decision-table'
          ? 'DECISION_TABLE'
          : node?.data.summary.visualKind === 'transform' ? 'TRANSFORM' : 'NODE_CONFIG',
        label: t('Edit {subject}', { subject: node?.data.label ?? operatorDetailNodeId }),
        subjectRef: operatorDetailNodeId,
      });
    } else {
      mutationObservationModeRef.current = 'observe';
    }
    setOperatorDetailNodeId('');
    setOperatorDetailBaseline(null);
  }, [commitHeldMutation, nodes, operatorDetailNodeId, t]);

  const cancelOperatorDetail = useCallback(() => {
    mutationObservationModeRef.current = 'observe';
    if (operatorDetailBaseline) {
      setNodes((current) => current.map((node) => (
        node.id === operatorDetailBaseline.nodeId
          ? { ...node, data: structuredClone(operatorDetailBaseline.nodeData) }
          : node
      )));
      setFixtureDrafts((current) => {
        const next = { ...current };
        if (operatorDetailBaseline.fixtureDraft === undefined) {
          delete next[operatorDetailBaseline.nodeId];
        } else {
          next[operatorDetailBaseline.nodeId] = operatorDetailBaseline.fixtureDraft;
        }
        return next;
      });
      setFixtureInputDrafts((current) => {
        const next = { ...current };
        if (operatorDetailBaseline.fixtureInputDraft === undefined) {
          delete next[operatorDetailBaseline.nodeId];
        } else {
          next[operatorDetailBaseline.nodeId] = operatorDetailBaseline.fixtureInputDraft;
        }
        return next;
      });
      setOperatorTestSuites((current) => {
        const next = { ...current };
        if (operatorDetailBaseline.testRows === undefined) {
          delete next[operatorDetailBaseline.nodeId];
        } else {
          next[operatorDetailBaseline.nodeId] = structuredClone(operatorDetailBaseline.testRows);
        }
        return next;
      });
    }
    setOperatorDetailNodeId('');
    setOperatorDetailBaseline(null);
  }, [operatorDetailBaseline]);

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

  const updateRunInputValue = useCallback((value: Record<string, unknown>) => {
    clearRunResult();
    setRunInputValue(value);
  }, [clearRunResult]);

  const bindContextPathToNode = useCallback((nodeId: string, path: string) => {
    const normalizedPath = normalizedContextPath(path);
    const targetNode = nodes.find((node) => node.id === nodeId);
    if (!normalizedPath || !targetNode) {
      return;
    }
    const targetPort = defaultInputTargetPort(targetNode);
    const targetPath = contextBindingKey(normalizedPath);
    setEdges((current) => current.filter((edge) => {
      if (edge.target !== nodeId) {
        return true;
      }
      const edgeTargetPort = portNameFromHandle(edge.targetHandle, 'in');
      const edgeTargetPath = edge.data?.targetPath ?? (edge as CanvasFlowEdge).targetPath ?? '';
      return edgeTargetPort !== targetPort || edgeTargetPath !== targetPath;
    }));
    updateNodeInputs(nodeId, (inputs) => {
      const existingTarget = Object.entries(inputs).find(([bindingKey, binding]) =>
        bindingKey === targetPath
        || (
          (binding.targetPort || targetPort) === targetPort
          && (binding.targetPath || bindingKey) === targetPath
        ));
      const bindingKey = existingTarget?.[0] ?? uniqueInputBindingKey(inputs, targetPath);
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
  }, [nodes, updateNodeInputs]);

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
  const zoomPresentation = useMemo(
    () => canvasZoomPresentation(viewportZoom),
    [viewportZoom],
  );
  const focusedCanvasPath = useMemo(
    () => canvasFocusPath(canvasNodes, canvasEdges, focusPathNodeId),
    [canvasEdges, canvasNodes, focusPathNodeId],
  );
  const canvasTaskMode: CanvasTaskMode = focusPathNodeId
    ? 'focus'
    : selectedNodeId
      ? 'inspect'
      : 'overview';
  const canvasSemanticZoom = useMemo(
    () => semanticZoomContract(canvasTaskMode),
    [canvasTaskMode],
  );
  useEffect(() => {
    if (!isTaskWorkspace) return;
    const instance = flowInstanceRef.current;
    const minimumZoom = canvasSemanticZoom.minimumZoom;
    if (!instance || instance.getZoom() >= minimumZoom) return;
    void instance.zoomTo(minimumZoom, { duration: 0 });
    setViewportZoom(minimumZoom);
  }, [canvasSemanticZoom.minimumZoom, isTaskWorkspace]);
  const canvasSemantics = useMemo(
    () => projectCanvasSemantics(canvasNodes, canvasEdges, {
      mode: canvasTaskMode,
      anchorNodeId: focusPathNodeId,
      selectedNodeId,
    }),
    [canvasEdges, canvasNodes, canvasTaskMode, focusPathNodeId, selectedNodeId],
  );
  const currentCanvasGeometry = useMemo(
    () => assessCanvasLayout(canvasNodes, canvasEdges, pinnedNodeIds),
    [canvasEdges, canvasNodes, pinnedNodeIds],
  );
  const canvasPerceptualQuality = useMemo(
    () => assessCanvasPerceptualQuality(canvasNodes, {
      mode: canvasTaskMode,
      viewportWidth: flowRef.current?.clientWidth || window.innerWidth,
      viewportHeight: flowRef.current?.clientHeight || window.innerHeight,
      zoom: viewportZoom,
      visibleEdgeLabels: canvasSemantics.visibleEdgeLabelCount,
      visibleFieldLabels: canvasSemantics.visibleFieldCount,
      nodeOverlaps: currentCanvasGeometry.nodeOverlaps,
      nodeLabelCollisions: canvasSemantics.nodeLabelCollisionCount,
      labelLabelCollisions: canvasSemantics.labelLabelCollisionCount,
    }),
    [
      canvasSemantics.visibleEdgeLabelCount,
      canvasSemantics.visibleFieldCount,
      canvasSemantics.labelLabelCollisionCount,
      canvasSemantics.nodeLabelCollisionCount,
      canvasNodes,
      canvasTaskMode,
      compactWorkspace,
      currentCanvasGeometry.nodeOverlaps,
      inspectorCollapsed,
      paletteCollapsed,
      viewportZoom,
    ],
  );
  const adaptiveChrome = useMemo(
    () => adaptiveCanvasChromePolicy({
      authorMode,
      compactWorkspace,
      nodeCount: canvasNodes.length,
      fitZoom: viewportZoom,
      selectedNodeId,
      palettePreference,
      inspectorPreference,
    }),
    [
      authorMode,
      canvasNodes.length,
      compactWorkspace,
      inspectorPreference,
      palettePreference,
      selectedNodeId,
      viewportZoom,
    ],
  );

  useEffect(() => {
    if (!isTaskWorkspace) return undefined;
    const formalMobileTask = compactWorkspace && authorMode !== 'compose';
    const nextPaletteCollapsed = formalMobileTask
      ? true
      : palettePreference === 'open'
        ? false
        : adaptiveChrome.collapsePalette || paletteCollapsed;
    const nextInspectorCollapsed = formalMobileTask
      ? !formalContextRailOpen
      : inspectorPreference === 'open'
        ? false
        : adaptiveChrome.collapseInspector || inspectorCollapsed;
    const changed = nextPaletteCollapsed !== paletteCollapsed
      || nextInspectorCollapsed !== inspectorCollapsed;
    if (nextPaletteCollapsed !== paletteCollapsed) {
      setPaletteCollapsed(nextPaletteCollapsed);
    }
    if (nextInspectorCollapsed !== inspectorCollapsed) {
      setInspectorCollapsed(nextInspectorCollapsed);
    }
    const notice = adaptiveChrome.reason
      && (adaptiveChrome.collapsePalette || adaptiveChrome.collapseInspector)
      ? adaptiveChromeReasonMessage(adaptiveChrome.reason)
      : null;
    setAdaptiveChromeNotice((current) => (
      current?.messageId === notice?.messageId ? current : notice
    ));
    if (!changed || authorMode !== 'compose' || canvasNodes.length === 0) {
      return undefined;
    }
    const handle = window.setTimeout(() => fitCanvasToView(), 90);
    return () => window.clearTimeout(handle);
  }, [
    adaptiveChrome,
    authorMode,
    canvasNodes.length,
    fitCanvasToView,
    formalContextRailOpen,
    inspectorCollapsed,
    inspectorPreference,
    isTaskWorkspace,
    paletteCollapsed,
    palettePreference,
  ]);
  const canvasTaskNodes = useMemo(
    () => nodes.map((node) => ({
      id: node.id,
      label: node.data.label,
      operatorRef: node.data.operatorRef,
      pinned: pinnedNodeIds.has(node.id),
    })),
    [nodes, pinnedNodeIds],
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
        incompatibleContractPaths: exampleIncompatibleContractPaths(template, operatorByRef),
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
    const incompatibleContractPaths = exampleIncompatibleContractPaths(template, operatorByRef);
    if (incompatibleContractPaths.length > 0) {
      setConnectionNotice({
        level: 'warning',
        message: t('Example Contract changed. Review incompatible paths: {paths}', {
          paths: incompatibleContractPaths.join(', '),
        }),
      });
      return;
    }
    pendingMutationDescriptorRef.current = {
      kind: 'IMPORT',
      label: t('Load example {subject}', { subject: template.label }),
      subjectRef: template.key,
      coalesceKey: `load-example:${template.key}`,
    };

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
      if (hasOwnValue(templateNode, 'operatorTestInput') || hasOwnValue(templateNode, 'expectedInput')) {
        const operatorTestInput = hasOwnValue(templateNode, 'operatorTestInput')
          ? templateNode.operatorTestInput
          : templateNode.expectedInput;
        testRows[0] = {
          ...testRows[0],
          inputText: JSON.stringify(operatorTestInput, null, 2),
        };
      }
      if (
        hasOwnValue(templateNode, 'operatorTestExpectedOutput')
        || hasOwnValue(templateNode, 'fixtureOutput')
      ) {
        const operatorTestExpectedOutput = hasOwnValue(templateNode, 'operatorTestExpectedOutput')
          ? templateNode.operatorTestExpectedOutput
          : templateNode.fixtureOutput;
        testRows[0] = {
          ...testRows[0],
          outputText: JSON.stringify(operatorTestExpectedOutput, null, 2),
          transportResponseText: transportResponseTextForExpected(operator, operatorTestExpectedOutput),
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

    resetRunResult();
    counter.current = maxNumericNodeId(template.nodes);
    tableTestCounter.current = nextSimulationTableRows.length;
    setNodes(autoLayoutFlowNodes(nextNodes, nextEdges));
    setEdges(nextEdges);
    setFixtureDrafts(nextFixtureDrafts);
    setFixtureInputDrafts(nextFixtureInputDrafts);
    setOperatorTestSuites(nextOperatorTestSuites);
    setOperatorTestResults({});
    setOperatorTestPublications({});
    setSimulationTableRows(nextSimulationTableRows);
    setSimulationTableResults({});
    setPinnedNodeIds(new Set());
    setLayoutPlanning(false);
    setLayoutPreview(null);
    setLayoutUndo(null);
    setLayoutNotice(null);
    setFocusPathNodeId('');
    setOverviewVisible(!isTaskWorkspace && (
      nextNodes.length >= COMPLEX_GRAPH_NODE_THRESHOLD
      || nextEdges.length >= COMPLEX_GRAPH_EDGE_THRESHOLD
    ));
    scenarioGraphNameRef.current = '';
    setScenarioDraftSet(null);
    setLoadedExampleKey(template.key);
    workspaceForkIdempotencyKeyRef.current = `canvas:${template.key}:${
      globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`
    }`;
    setGraphName(template.graphName);
    setGraphDraftId('');
    setGraphDraftRevision(0);
    setGraphDraftStatus(undefined);
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
    const nextRunInput = sampleFromSchemaEnvelope(template.inputSchema);
    setRunInputValue(isRecord(nextRunInput) ? nextRunInput : {});
    setSimulationContextDraft(JSON.stringify(nextRunInput, null, 2));
    setRawContextMode(false);
    setContextVariables([]);
    setExplicitOutputNodeId(template.outputNodeId);
    setSelectedNodeId(template.outputNodeId);
    setOperatorDetailNodeId('');
    setOperatorDetailBaseline(null);
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
    if (isTaskWorkspace) {
      setAuthorMode('compose');
      setStartOpen(false);
      setStartSection('menu');
    }
    setConnectionNotice({
      level: 'ok',
      message: `Loaded ${template.label}: ${template.nodes.length} nodes / ${template.edges.length} edges.`,
    });
    const graphSize = { nodeCount: nextNodes.length, edgeCount: nextEdges.length };
    if (typeof window.requestAnimationFrame === 'function') {
      window.requestAnimationFrame(() => fitCanvasToView(graphSize, 'inspect'));
    } else {
      fitCanvasToView(graphSize, 'inspect');
    }
  }, [fitCanvasToView, isTaskWorkspace, operatorByRef, resetRunResult, t]);
  const requestLoadCanvasExample = useCallback((template: CanvasExampleTemplate) => {
    requestDestructiveCommand(
      t('Load example {subject}', { subject: template.label }),
      graphName,
      () => loadCanvasExample(template),
    );
  }, [graphName, loadCanvasExample, requestDestructiveCommand, t]);
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
  const taskContextCompilation = useMemo(
    () => compileTaskRunContext({
      runInput: runInputValue,
      extras: variableContextCompilation,
      raw: rawContextCompilation,
      rawMode: rawContextMode,
    }),
    [rawContextCompilation, rawContextMode, runInputValue, variableContextCompilation],
  );
  const contextCompilation = isTaskWorkspace
    ? taskContextCompilation
    : hasContextVariables
      ? variableContextCompilation
      : rawContextCompilation;
  const runInputAssessment = useMemo(
    () => assessRunInput(graphInputSchema, contextCompilation.value),
    [contextCompilation.value, graphInputSchema],
  );
  const updateRawContextMode = useCallback((enabled: boolean) => {
    clearRunResult();
    if (enabled) {
      setSimulationContextDraft(JSON.stringify(contextCompilation.value, null, 2));
    }
    setRawContextMode(enabled);
  }, [clearRunResult, contextCompilation.value]);
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
  const fixtureForNode = useCallback((nodeId: string): GraphNodeFixtureState | undefined => {
    const durable = fixtureCompilation.fixtures[nodeId];
    const hasRunOutput = Boolean(
      result && Object.prototype.hasOwnProperty.call(result.results, nodeId),
    );
    if (!durable && !hasRunOutput) return undefined;
    return {
      ...(durable ?? { output: result?.results[nodeId] }),
      ...(fixturePinnedNodeIds.has(nodeId) ? { pinned: true } : {}),
      ...(governedFixtureRefs[nodeId] ? { governedRef: governedFixtureRefs[nodeId] } : {}),
    };
  }, [fixtureCompilation.fixtures, fixturePinnedNodeIds, governedFixtureRefs, result]);
  const simulationFixtures = useMemo(() => Object.fromEntries(
    [...new Set([
      ...Object.keys(fixtureCompilation.fixtures),
      ...Object.keys(governedFixtureRefs),
    ])].map((nodeId) => [
      nodeId,
      {
        ...(fixtureCompilation.fixtures[nodeId] ?? { output: null }),
        ...(governedFixtureRefs[nodeId] ? { governedRef: governedFixtureRefs[nodeId] } : {}),
        ...(canvasNodes.find((node) => node.id === nodeId)?.operatorRef.startsWith('resource:')
          || operatorByRef.get(canvasNodes.find((node) => node.id === nodeId)?.operatorRef ?? '')
            ?.display?.tags?.includes('resource')
          ? { resourceFidelity: resourceFidelityByNode[nodeId] ?? 'OUTPUT_LEVEL' }
          : {}),
      },
    ]),
  ), [canvasNodes, fixtureCompilation.fixtures, governedFixtureRefs, operatorByRef, resourceFidelityByNode]);
  const pinSimulationNode = useCallback((nodeId: string) => {
    if (!result || !Object.prototype.hasOwnProperty.call(result.results, nodeId)) return;
    const output = result.results[nodeId];
    setFixtureDrafts((current) => ({
      ...current,
      [nodeId]: JSON.stringify(output, null, 2),
    }));
    setFixturePinnedNodeIds((current) => {
      const next = new Set(current);
      next.add(nodeId);
      return next;
    });
  }, [result]);
  const acceptGovernedFixture = useCallback((reference: GovernedGraphNodeFixtureRef & { nodeId: string }) => {
    const { nodeId, ...coordinate } = reference;
    setGovernedFixtureRefs((current) => ({ ...current, [nodeId]: coordinate }));
  }, []);
  const selectedFixtureState = selectedNode ? fixtureForNode(selectedNode.id) : undefined;
  const selectedIsResource = Boolean(
    selectedNode
    && (selectedNode.data.operatorRef.startsWith('resource:')
      || operatorByRef.get(selectedNode.data.operatorRef)?.display?.tags?.includes('resource')),
  );
  const selectedGovernedFixtureRef = selectedNode ? governedFixtureRefs[selectedNode.id] : undefined;
  // Compatibility is server-derived by the fixture catalogue; the client must not
  // compare unrelated operator and material fingerprints.
  const selectedGovernedAsset = governedFixtureAssets.find((asset) => (
    selectedGovernedFixtureRef
    && asset.fixtureAssetId === selectedGovernedFixtureRef.fixtureAssetId
    && asset.revision === selectedGovernedFixtureRef.revision
  ));
  const selectedGovernedFixtureStale = Boolean(
    selectedGovernedFixtureRef && (!selectedGovernedAsset || selectedGovernedAsset.compatible !== true),
  );
  const selectGovernedFixture = useCallback((asset: PickerAsset) => {
    if (asset.compatible !== true) {
      setError(t('This governed fixture is not compatible with the current operator.'));
      return;
    }
    if (!selectedNode) return;
    setGovernedFixtureRefs((current) => ({
      ...current,
      [selectedNode.id]: {
        fixtureAssetId: asset.fixtureAssetId,
        revision: asset.revision,
        schemaFingerprint: asset.schemaFingerprint,
      },
    }));
    setError('');
  }, [selectedNode]);
  const clearSelectedGovernedFixture = useCallback(() => {
    if (!selectedNode) return;
    setGovernedFixtureRefs((current) => {
      const next = { ...current };
      delete next[selectedNode.id];
      return next;
    });
  }, [selectedNode]);
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
  const selectedEffectiveContract = useMemo(
    () => selectedNode
      ? projectEffectiveContract({
          graphDraft: exportableDraft,
          nodeId: selectedNode.id,
          operator: selectedOperator,
          operators: [...operatorByRef.values()],
          run: result,
        })
      : null,
    [exportableDraft, operatorByRef, result, selectedNode, selectedOperator],
  );
  const operatorDetailEffectiveContract = useMemo(
    () => operatorDetailNode
      ? projectEffectiveContract({
          graphDraft: exportableDraft,
          nodeId: operatorDetailNode.id,
          operator: operatorDetailDefinition,
          operators: [...operatorByRef.values()],
          run: result,
        })
      : null,
    [
      exportableDraft,
      operatorByRef,
      operatorDetailDefinition,
      operatorDetailNode,
      result,
    ],
  );
  const selectedAcceptedInference = selectedEffectiveContract
    ? schemaFromAcceptedInference(selectedEffectiveContract)
    : null;
  const operatorDetailAcceptedInference = operatorDetailEffectiveContract
    ? schemaFromAcceptedInference(operatorDetailEffectiveContract)
    : null;
  const selectedInferenceAcceptable = Boolean(
    selectedNode?.id === outputNodeId
    && selectedAcceptedInference
    && acceptedInferenceAddsFields(effectiveGraphOutputSchema, selectedAcceptedInference),
  );
  const operatorDetailInferenceAcceptable = Boolean(
    operatorDetailNode?.id === outputNodeId
    && operatorDetailAcceptedInference
    && acceptedInferenceAddsFields(effectiveGraphOutputSchema, operatorDetailAcceptedInference),
  );
  const acceptSelectedInference = useCallback(() => {
    if (!selectedNode || selectedNode.id !== outputNodeId || !selectedAcceptedInference) {
      return;
    }
    clearRunResult();
    setGraphOutputSchema(selectedAcceptedInference);
    setGraphContractSource(`Accepted inference from ${selectedNode.id}`);
  }, [
    clearRunResult,
    outputNodeId,
    selectedAcceptedInference,
    selectedNode,
  ]);
  const acceptOperatorDetailInference = useCallback(() => {
    if (!operatorDetailNode
      || operatorDetailNode.id !== outputNodeId
      || !operatorDetailAcceptedInference) {
      return;
    }
    clearRunResult();
    setGraphOutputSchema(operatorDetailAcceptedInference);
    setGraphContractSource(`Accepted inference from ${operatorDetailNode.id}`);
  }, [
    clearRunResult,
    operatorDetailAcceptedInference,
    operatorDetailNode,
    outputNodeId,
  ]);
  const traceEffectiveBinding = useCallback((binding: EffectiveInputBinding) => {
    if (!binding.sourceNodeId) {
      return;
    }
    setFocusPathNodeId(selectedNodeId);
    setSelectedNodeId(binding.sourceNodeId);
  }, [selectedNodeId]);
  const traceEffectiveField = useCallback((_field: EffectiveContractField) => {
    if (selectedNode) {
      openNodeEditor(selectedNode);
    }
  }, [openNodeEditor, selectedNode]);
  const scenarioNodeOptions = useMemo<ScenarioNodeOption[]>(
    () => canvasNodes.map((node) => ({
      id: node.id,
      label: node.label || node.id,
      operatorRef: node.operatorRef,
      inputSchema: operatorNodePortSchema(operatorByRef.get(node.operatorRef), 'inputs'),
      outputSchema: operatorNodePortSchema(operatorByRef.get(node.operatorRef), 'outputs'),
    })),
    [canvasNodes, operatorByRef],
  );

  useEffect(() => {
    const sequence = contractProjectionSequence.current + 1;
    contractProjectionSequence.current = sequence;
    let active = true;

    const project = async () => {
      const executionSnapshot = canonicalExecutionGraphDraft(exportableDraft);
      if (contractExecutionSnapshotRef.current === executionSnapshot) {
        return;
      }
      const authoritative = authoritativeContractRef.current;
      const exactServerProjection = authoritative
        && authoritative.executionSnapshot === executionSnapshot
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
      contractExecutionSnapshotRef.current = executionSnapshot;
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

  useEffect(() => {
    if (!scenarioDraftSet) {
      setWorkspaceScenarioId('');
      return;
    }
    setWorkspaceScenarioId((current) => (
      scenarioDraftSet.scenarios.some((scenario) => scenario.scenarioId === current)
        ? current
        : scenarioDraftSet.scenarios[0]?.scenarioId ?? ''
    ));
  }, [
    scenarioDraftSet?.scenarioDraftSetId,
    scenarioDraftSet?.target.fingerprint,
    scenarioDraftSet?.scenarios,
  ]);

  const activeTaskGraphDraft = operatorContractWorkspace?.graphDraft ?? exportableDraft;
  const activeTaskContract = operatorContractWorkspace?.contract ?? contractDraft;
  const activeTaskContractFingerprint = operatorContractWorkspace?.contractFingerprint
    ?? contractFingerprint;
  const activeTaskScenarioSet = operatorContractWorkspace?.scenarioDraftSet ?? scenarioDraftSet;
  const activeTaskScenarioNodes = operatorContractWorkspace?.nodes ?? scenarioNodeOptions;
  const activeTaskTargetKey = activeTaskContract && activeTaskScenarioSet
    ? [
        activeTaskContract.target.kind,
        activeTaskContract.target.id,
        activeTaskContract.target.revision,
        activeTaskContractFingerprint,
        activeTaskScenarioSet.scenarioDraftSetId,
      ].join(':')
    : '';
  const activeScenarioFingerprint = scenarioFingerprintTargetKey === activeTaskTargetKey
    ? scenarioFingerprint
    : '';
  scenarioFingerprintRef.current = activeScenarioFingerprint;

  useEffect(() => {
    if (!activeTaskScenarioSet) return;
    setWorkspaceScenarioId((current) => (
      activeTaskScenarioSet.scenarios.some((scenario) => scenario.scenarioId === current)
        ? current
        : activeTaskScenarioSet.scenarios[0]?.scenarioId ?? ''
    ));
  }, [activeTaskScenarioSet]);

  useEffect(() => {
    let active = true;
    if (!activeTaskScenarioSet) {
      setScenarioFingerprint('');
      setScenarioFingerprintTargetKey('');
      setOperatorClosureFingerprint('');
      return () => {
        active = false;
      };
    }
    const selectedScenario = activeTaskScenarioSet.scenarios.find(
      (scenario) => scenario.scenarioId === workspaceScenarioId,
    ) ?? activeTaskScenarioSet.scenarios[0];
    const scenarioMaterial = activeTaskContract && selectedScenario
      ? captureScenarioEditorSnapshot(
          activeTaskScenarioSet,
          selectedScenario.scenarioId,
          activeTaskContract,
          activeTaskScenarioNodes,
        )
      : activeTaskScenarioSet;
    Promise.all([
      sha256Fingerprint(scenarioMaterial),
      sha256Fingerprint({
        operatorFingerprints: activeTaskGraphDraft.operatorFingerprints ?? {},
        runtimeBindings: Object.fromEntries(activeTaskGraphDraft.nodes.map((node) => [
          node.id,
          {
            operatorRef: node.operatorRef,
            inputs: node.inputs ?? {},
            config: node.config ?? {},
          },
        ])),
      }),
    ]).then(([nextScenarioFingerprint, nextClosureFingerprint]) => {
      if (active) {
        setScenarioFingerprint(nextScenarioFingerprint);
        setScenarioFingerprintTargetKey(activeTaskTargetKey);
        setOperatorClosureFingerprint(nextClosureFingerprint);
      }
    }).catch((cause: unknown) => {
      if (active) {
        setScenarioFingerprint('');
        setScenarioFingerprintTargetKey('');
        setOperatorClosureFingerprint('');
        setError(`Evidence coordinate projection failed: ${String(cause)}`);
      }
    });
    return () => {
      active = false;
    };
  }, [
    activeTaskContract,
    activeTaskGraphDraft,
    activeTaskScenarioNodes,
    activeTaskScenarioSet,
    activeTaskTargetKey,
    workspaceScenarioId,
  ]);

  const evidenceCoordinateForScenario = useCallback((
    scenarioId: string,
    requestFingerprint: string,
    proof?: ScenarioCompilationProof,
  ) => ({
    contentEpoch: authorContentEpoch,
    targetKind: activeTaskContract?.target.kind ?? 'GRAPH',
    targetId: activeTaskContract?.target.id ?? '',
    targetRevision: activeTaskContract?.target.revision ?? 0,
    draftId: activeTaskContract?.target.id ?? graphDraftId,
    draftRevision: activeTaskContract?.target.revision ?? graphDraftRevision,
    draftFingerprint: activeTaskContract?.target.fingerprint ?? '',
    contractFingerprint: activeTaskContractFingerprint,
    scenarioId,
    scenarioRevision: activeTaskScenarioSet?.revision ?? 0,
    scenarioFingerprint: proof?.editorSnapshotFingerprint ?? activeScenarioFingerprint,
    closureFingerprint: operatorClosureFingerprint,
    requestFingerprint,
    ...(proof ? {
      editorSnapshotFingerprint: proof.editorSnapshotFingerprint,
      compiledPlanSourceFingerprint: proof.compiledPlanSourceFingerprint,
      requestSourceFingerprint: proof.requestSourceFingerprint,
      evidenceSourceFingerprint: proof.evidenceSourceFingerprint,
    } : {}),
  }), [
    authorContentEpoch,
    activeTaskContract,
    activeTaskContractFingerprint,
    activeTaskScenarioSet?.revision,
    graphDraftId,
    graphDraftRevision,
    operatorClosureFingerprint,
    activeScenarioFingerprint,
  ]);

  const openGraphSaveConflict = useCallback(async (
    localDraft: GraphDraft,
    localScenarioDraftSet: ScenarioDraftSet | null,
    _detail = '',
    retainedForkIdempotencyKey = '',
  ) => {
    const localDraftId = localDraft.draftId?.trim() ?? '';
    if (!localDraftId) return;
    const forkIdempotencyKey = retainedForkIdempotencyKey || `graph-conflict-fork:${
      globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`
    }`;
    const localSnapshot = structuredClone(localDraft);
    const scenarioSnapshot = localScenarioDraftSet
      ? structuredClone(localScenarioDraftSet)
      : null;
    setDraftSaveConflict({
      localDraft: localSnapshot,
      localScenarioDraftSet: scenarioSnapshot,
      localFingerprint: '',
      authoritative: null,
      authoritativeFingerprint: '',
      loading: true,
      busyAction: '',
      error: '',
      forkIdempotencyKey,
    });
    try {
      const [authoritative, localFingerprint] = await Promise.all([
        fetchGraphDraft(localDraftId),
        sha256Fingerprint(localSnapshot),
      ]);
      const authoritativeFingerprint = await sha256Fingerprint(authoritative);
      setDraftSaveConflict((current) => current?.forkIdempotencyKey === forkIdempotencyKey
        ? {
            ...current,
            authoritative,
            authoritativeFingerprint,
            localFingerprint,
            loading: false,
            error: '',
          }
        : current);
    } catch (cause: unknown) {
      setDraftSaveConflict((current) => current?.forkIdempotencyKey === forkIdempotencyKey
        ? {
            ...current,
            loading: false,
            error: cause instanceof Error ? cause.message : String(cause),
          }
        : current);
    }
  }, []);

  const saveGraphForScenario = useCallback(async (saveAttempt?: WorkspaceSaveAttempt) => {
    try {
      let stored: GraphDraft;
      let persistedScenarios: ScenarioDraftSet | null = null;
      if (!exportableDraft.draftId && loadedExampleKey) {
        if (!scenarioDraftSet) {
          throw new Error('The complete example is still preparing its Scenario suite. Try Save again.');
        }
        const template = CANVAS_EXAMPLE_TEMPLATES.find(
          (candidate) => candidate.key === loadedExampleKey,
        ) ?? null;
        const idempotencyKey = workspaceForkIdempotencyKeyRef.current;
        if (!idempotencyKey) {
          throw new Error('Workspace fork identity is missing. Reload the complete example.');
        }
        const receipt = await forkWorkspace(
          idempotencyKey,
          workspaceForkCommand(exportableDraft, scenarioDraftSet, template),
        );
        stored = await fetchGraphDraft(receipt.graphCoordinate.draftId);
        const scenarioCoordinate = receipt.scenarioSuiteCoordinates[0];
        if (!scenarioCoordinate) {
          throw new Error('Workspace fork did not return a Scenario suite coordinate.');
        }
        persistedScenarios = (await fetchScenarioDraftSet(scenarioCoordinate.id)).draftSet;
      } else {
        stored = await saveGraphDraft(exportableDraft, saveAttempt?.idempotencyKey);
      }
      if (!stored.draftId || !stored.revision) {
        throw new Error('Graph persistence did not return an exact draft revision.');
      }
      lastSavedGraphRef.current = stored;
      const projection = await fetchScenarioGraphContract(stored.draftId);
      const savedCanvasDraft = savedCanvasDraftAtRevision(exportableDraft, stored);
      authoritativeContractRef.current = {
        canvasSnapshot: canonicalJson(savedCanvasDraft),
        executionSnapshot: canonicalExecutionGraphDraft(savedCanvasDraft),
        graphDraft: stored,
        contract: projection.contract,
        contractFingerprint: projection.contractFingerprint,
      };
      setDraftSaveConflict(null);
      setGraphDraftId(stored.draftId);
      setGraphDraftRevision(stored.revision);
      setGraphDraftStatus(stored.status);
      setGraphTenantId(savedCanvasDraft.tenantId || 'tenant-a');
      setGraphNamespace(savedCanvasDraft.namespace || 'local');
      setGraphEnvironment(savedCanvasDraft.environment || 'test');
      setGraphOperatorFingerprints(savedCanvasDraft.operatorFingerprints ?? {});
      setGraphOperatorSnapshots(savedCanvasDraft.operatorSnapshots ?? {});
      setContractDraft(projection.contract);
      setContractFingerprint(projection.contractFingerprint);
      if (persistedScenarios) {
        scenarioGraphNameRef.current = stored.graphName;
        setScenarioDraftSet(persistedScenarios);
        setConnectionNotice({
          level: 'ok',
          message: `Saved complete Workspace: Graph r${stored.revision} and Scenario r${persistedScenarios.revision} are current.`,
        });
      }
    } catch (cause: unknown) {
      if (/conflict|revision|409/i.test(String(cause))) {
        void openGraphSaveConflict(exportableDraft, scenarioDraftSet, String(cause));
      }
      throw cause;
    }
  }, [exportableDraft, loadedExampleKey, openGraphSaveConflict, scenarioDraftSet]);

  const forkConflictedGraph = useCallback(async () => {
    const conflict = draftSaveConflict;
    if (!conflict?.authoritative) return;
    setDraftSaveConflict((current) => current
      ? { ...current, busyAction: 'fork', error: '' }
      : current);
    try {
      let stored: GraphDraft;
      let persistedScenarios: ScenarioDraftSet | null = null;
      if (conflict.localScenarioDraftSet) {
        const command = workspaceForkCommand(
          conflict.localDraft,
          conflict.localScenarioDraftSet,
          null,
        );
        command.changeSource = 'author-canvas-conflict-resolution';
        command.workspaceName = `${conflict.localDraft.graphName} local fork`;
        const receipt = await forkWorkspace(conflict.forkIdempotencyKey, command);
        stored = await fetchGraphDraft(receipt.graphCoordinate.draftId);
        const scenarioCoordinate = receipt.scenarioSuiteCoordinates[0];
        if (!scenarioCoordinate) {
          throw new Error('The conflict fork did not return a Scenario suite coordinate.');
        }
        persistedScenarios = (await fetchScenarioDraftSet(scenarioCoordinate.id)).draftSet;
      } else {
        stored = await saveGraphDraft({
          ...conflict.localDraft,
          draftId: '',
          revision: 0,
        }, conflict.forkIdempotencyKey);
      }
      if (!stored.draftId || !stored.revision) {
        throw new Error('The conflict fork did not return an exact Graph revision.');
      }
      const projection = await fetchScenarioGraphContract(stored.draftId);
      const savedCanvasDraft = savedCanvasDraftAtRevision(conflict.localDraft, stored);
      authoritativeContractRef.current = {
        canvasSnapshot: canonicalJson(savedCanvasDraft),
        executionSnapshot: canonicalExecutionGraphDraft(savedCanvasDraft),
        graphDraft: stored,
        contract: projection.contract,
        contractFingerprint: projection.contractFingerprint,
      };
      resetRunResult();
      setOperatorTestResults({});
      setOperatorTestPublications({});
      setGraphDraftId(stored.draftId);
      setGraphDraftRevision(stored.revision);
      setGraphDraftStatus(stored.status);
      setGraphTenantId(savedCanvasDraft.tenantId || 'tenant-a');
      setGraphNamespace(savedCanvasDraft.namespace || 'local');
      setGraphEnvironment(savedCanvasDraft.environment || 'test');
      setGraphOperatorFingerprints(savedCanvasDraft.operatorFingerprints ?? {});
      setGraphOperatorSnapshots(savedCanvasDraft.operatorSnapshots ?? {});
      setContractDraft(projection.contract);
      setContractFingerprint(projection.contractFingerprint);
      setLoadedExampleKey('');
      workspaceForkIdempotencyKeyRef.current = '';
      if (persistedScenarios) {
        scenarioGraphNameRef.current = stored.graphName;
        setScenarioDraftSet(persistedScenarios);
      }
      setDraftSaveConflict(null);
      setConnectionNotice({
        level: 'ok',
        message: t('Local work was preserved as Graph revision {revision} in a new Workspace.', {
          revision: stored.revision,
        }),
      });
    } catch (cause: unknown) {
      setDraftSaveConflict((current) => current ? {
        ...current,
        busyAction: '',
        error: cause instanceof Error ? cause.message : String(cause),
      } : current);
    }
  }, [draftSaveConflict, resetRunResult, t]);

  const openOperatorContractWorkspace = useCallback(async (
    operator: OperatorDefinition,
    initialTab: WorkspaceTab = 'interface',
    requestedNodeId = '',
  ) => {
    setError('');
    setContractWorkspaceInitialTab(initialTab);
    try {
      const projection = await fetchScenarioOperatorContract(operator.operatorRef);
      const syntheticGraph = operatorScenarioGraphDraft(
        operator,
        projection.contract,
        projection.scope.tenantId,
        projection.scope.environment,
      );
      const sourceNode = nodes.find((node) => (
        node.id === (requestedNodeId || selectedNodeId)
        && node.data.operatorRef === operator.operatorRef
      )) ?? nodes.find((node) => node.data.operatorRef === operator.operatorRef);
      const legacyRows = sourceNode
        ? operatorTestSuites[sourceNode.id] ?? defaultOperatorTestSuiteRows(sourceNode, operator)
        : [];
      const legacyProjection = projectLegacyOperatorTableRows(legacyRows);
      const operatorNode: ScenarioNodeOption = {
        id: 'operator',
        label: operator.display?.name || operator.operatorRef,
        operatorRef: operator.operatorRef,
        inputSchema: operatorNodePortSchema(operator, 'inputs'),
        outputSchema: operatorNodePortSchema(operator, 'outputs'),
      };
      const draftSet = scenarioDraftSetFromOperatorTableCases(
        projection.contract.target,
        projection.contractFingerprint,
        syntheticGraph,
        operatorNode,
        legacyProjection.cases,
        legacyProjection.diagnostics,
      );
      const scenarioDraftSetId = await operatorScenarioDraftSetId(operator.operatorRef);
      const nextDraftSet = {
        ...draftSet,
        scenarioDraftSetId,
        metadata: {
          ...draftSet.metadata,
          provenance: {
            ...draftSet.metadata.provenance,
            operatorRef: operator.operatorRef,
            sourceNodeId: sourceNode?.id ?? '',
          },
        },
      };
      setOperatorContractWorkspace({
        graphDraft: syntheticGraph,
        contract: projection.contract,
        contractFingerprint: projection.contractFingerprint,
        scenarioDraftSet: nextDraftSet,
        nodes: [operatorNode],
      });
      setWorkspaceScenarioId(nextDraftSet.scenarios[0]?.scenarioId ?? '');
      setContractWorkspaceOpen(false);
      setOperatorDetailNodeId('');
      setOperatorDetailBaseline(null);
      if (isTaskWorkspace) {
        setAuthorMode(authorModeForWorkspaceTab(initialTab));
      }
    } catch (cause: unknown) {
      setError(`Operator Contract projection failed: ${String(cause)}`);
    }
  }, [isTaskWorkspace, nodes, operatorTestSuites, selectedNodeId]);

  const updateContractSemantics = useCallback((nextContract: ContractDraft) => {
    if (contractDraft && canonicalJson(contractDraft) === canonicalJson(nextContract)) {
      return;
    }
    authoritativeContractRef.current = null;
    setContractDraft(nextContract);
    setGraphInputSchema(nextContract.inputSchema);
    setGraphOutputSchema(nextContract.outputSchema);
    const sample = sampleFromSchemaEnvelope(nextContract.inputSchema);
    setRunInputValue((current) =>
      reconcileRunInputWithSchema(nextContract.inputSchema, current, sample));
    setGraphVisualLayout((current) => visualLayoutWithContractSemantics(current, nextContract));
  }, [contractDraft]);

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
  const hasContextError = Boolean(
    contextCompilation.error
    || (isTaskWorkspace && !runInputAssessment.ready),
  );
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
  const operatorDetailDirty = Boolean(
    operatorDetailNode
    && operatorDetailBaseline?.nodeId === operatorDetailNode.id
    && (
      canonicalJson(operatorDetailBaseline.nodeData) !== canonicalJson(operatorDetailNode.data)
      || (operatorDetailBaseline.fixtureDraft ?? '') !== operatorDetailFixtureDraft
      || (operatorDetailBaseline.fixtureInputDraft ?? '') !== operatorDetailExpectedInputDraft
      || canonicalJson(operatorDetailBaseline.testRows ?? null)
        !== canonicalJson(operatorTestSuites[operatorDetailNode.id] ?? null)
    )
  );
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
            pathFocus: focusPathNodeId
              ? focusedCanvasPath.nodeIds.has(node.id) ? 'active' : 'dimmed'
              : undefined,
            isOutput: node.id === outputNodeId,
            pinned: pinnedNodeIds.has(node.id),
          },
        };
      }),
    [
      candidatePreview,
      coachPrompt,
      focusPathNodeId,
      focusedCanvasPath.nodeIds,
      nodes,
      outputNodeId,
      pinnedNodeIds,
      selectedNodeId,
    ],
  );
  const flowEdges = useMemo(
    () => withEdgeLabelLanes(
      edges,
      viewportZoom,
      focusedCanvasPath,
      Boolean(focusPathNodeId),
      canvasSemantics,
    ),
    [canvasSemantics, edges, focusPathNodeId, focusedCanvasPath, viewportZoom],
  );

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
    if (sourcePreviewReadOnly) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = 'copy';
  }, [sourcePreviewReadOnly]);

  const dropOperatorOnFlow = useCallback((event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    if (sourcePreviewReadOnly || layoutPlanning || layoutPreview) {
      return;
    }
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
  }, [addOperator, layoutPlanning, layoutPreview, operatorByRef, sourcePreviewReadOnly]);

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
      if (isTaskWorkspace) {
        setAuthorMode('compose');
        setStartOpen(false);
        setStartSection('menu');
      }
    } catch (cause: unknown) {
      setLibraryDiagnostics([]);
      setLibraryNotice({ level: 'error', message: String(cause) });
    } finally {
      setLibraryBusy(false);
    }
  }, [
    isTaskWorkspace,
    librarySourceText,
    libraryWarningReason,
    libraryWarningsAcknowledged,
    reloadOperators,
  ]);

  const applyDslProjection = useCallback((
    projection: DslVisualProjection,
    contractSource?: string,
    workspaceBundle?: VisualAuthoringWorkspaceBundle,
  ) => {
    if (isTaskWorkspace && mutationObservationModeRef.current === 'observe') {
      pendingMutationDescriptorRef.current = {
        kind: 'IMPORT',
        label: t('Import {subject}', { subject: projection.draft.graphName }),
        subjectRef: projection.sourceId,
        coalesceKey: `import-dsl:${projection.sourceId}`,
      };
    }
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
    const notice = workspaceBundle
      ? {
          level: 'ok' as const,
          message: `Imported verified workspace with ${workspaceBundle.scenarioDraftSet.scenarios.length} Scenarios.`,
        }
      : dslProjectionNotice(projection);

    resetRunResult();
    counter.current = maxCanvasNodeSequence(imported.nodes);
    contextVariableCounter.current = isTaskWorkspace ? 0 : nextContextVariables.length;
    tableTestCounter.current = nextSimulationTableRows.length;
    operatorTestCounter.current = 0;
    const preserveStoredLayout = Boolean(workspaceBundle)
      || projection.sourceId.startsWith('draft:');
    setNodes(preserveStoredLayout ? nextNodes : autoLayoutFlowNodes(nextNodes, nextEdges));
    setPinnedNodeIds(new Set());
    setLayoutPlanning(false);
    setLayoutPreview(null);
    setLayoutUndo(null);
    setLayoutNotice(null);
    setFocusPathNodeId('');
    setOverviewVisible(!isTaskWorkspace && (
      nextNodes.length >= COMPLEX_GRAPH_NODE_THRESHOLD
      || nextEdges.length >= COMPLEX_GRAPH_EDGE_THRESHOLD
    ));
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
    setGraphDraftStatus(projection.draft.status);
    setGraphTenantId(projection.draft.tenantId || 'tenant-a');
    setGraphNamespace(projection.draft.namespace || 'local');
    setGraphEnvironment(projection.draft.environment || 'test');
    if (workspaceBundle) {
      scenarioGraphNameRef.current = imported.graphName;
      setScenarioDraftSet(workspaceBundle.scenarioDraftSet);
      authoritativeContractRef.current = {
        canvasSnapshot: canonicalJson(projection.draft),
        executionSnapshot: canonicalExecutionGraphDraft(projection.draft),
        graphDraft: projection.draft,
        contract: workspaceBundle.contractProjection.contract,
        contractFingerprint: workspaceBundle.contractProjection.contractFingerprint,
      };
    } else {
      authoritativeContractRef.current = null;
    }
    setGraphInputSchema(nextInputSchema);
    setGraphOutputSchema(nextOutputSchema);
    setGraphContractSource(contractSource ?? (
      workspaceBundle ? 'Workspace bundle' : `DSL ${projection.sourceId || imported.graphName}`
    ));
    setGraphVisualLayout(workspaceBundle
      ? imported.visualLayout ?? {}
      : visualLayoutWithImportSourceMap(
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
    const nextRunInput = sampleFromSchemaEnvelope(nextInputSchema);
    setRunInputValue(isRecord(nextRunInput) ? nextRunInput : {});
    setSimulationContextDraft(JSON.stringify(nextRunInput, null, 2));
    setRawContextMode(false);
    setContextVariables(isTaskWorkspace ? [] : nextContextVariables);
    setExplicitOutputNodeId(imported.outputNodeId);
    setSelectedNodeId(imported.outputNodeId || imported.nodes[0]?.id || '');
    setOperatorDetailNodeId('');
    setOperatorDetailBaseline(null);
    setOperatorContractWorkspace(null);
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
    if (isTaskWorkspace) {
      setAuthorMode('compose');
      setStartOpen(false);
      setStartSection('menu');
    }

    const graphSize = { nodeCount: imported.nodes.length, edgeCount: imported.edges.length };
    if (typeof window.requestAnimationFrame === 'function') {
      window.requestAnimationFrame(() => fitCanvasToView(graphSize));
    } else {
      fitCanvasToView(graphSize);
    }
  }, [fitCanvasToView, isTaskWorkspace, operatorByRef, resetRunResult, t]);

  const importScenarioWorkspace = useCallback(async (
    bundle: VisualAuthoringWorkspaceBundle,
  ) => {
    applyDslProjection({
      schemaVersion: 'bloge.dslVisualProjection.v1',
      sourceId: `${bundle.scenarioDraftSet.scenarioDraftSetId}.workspace.json`,
      draft: bundle.graphDraft,
      diagnostics: [],
    }, 'Workspace bundle', bundle);
    setContractWorkspaceInitialTab('interface');
    setContractWorkspaceOpen(true);
    if (isTaskWorkspace) {
      setAuthorMode('contract');
    }
  }, [applyDslProjection, isTaskWorkspace]);
  const applyDslProjectionRef = useRef(applyDslProjection);

  useEffect(() => {
    applyDslProjectionRef.current = applyDslProjection;
  }, [applyDslProjection]);

  const reloadAuthoritativeGraph = useCallback(async () => {
    const conflict = draftSaveConflict;
    if (!conflict?.authoritative?.draftId) return;
    setDraftSaveConflict((current) => current
      ? { ...current, busyAction: 'reload', error: '' }
      : current);
    try {
      const projection = await fetchScenarioGraphContract(conflict.authoritative.draftId);
      mutationObservationModeRef.current = 'reset';
      replaceMutationJournal(initialMutationJournal<AuthoringMutationSnapshot>());
      applyDslProjection({
        schemaVersion: 'bloge.dslVisualProjection.v1',
        sourceId: `draft:${conflict.authoritative.draftId}`,
        draft: conflict.authoritative,
        diagnostics: [],
      }, `Stored draft ${conflict.authoritative.draftId}@${conflict.authoritative.revision ?? 0}`);
      scenarioGraphNameRef.current = '';
      setScenarioDraftSet(null);
      setContractDraft(projection.contract);
      setContractFingerprint(projection.contractFingerprint);
      setLoadedExampleKey('');
      workspaceForkIdempotencyKeyRef.current = '';
      authoritativeContractRef.current = {
        canvasSnapshot: canonicalJson(conflict.authoritative),
        executionSnapshot: canonicalExecutionGraphDraft(conflict.authoritative),
        graphDraft: conflict.authoritative,
        contract: projection.contract,
        contractFingerprint: projection.contractFingerprint,
      };
      setDraftSaveConflict(null);
      setConnectionNotice({
        level: 'ok',
        message: t('Loaded authoritative Graph revision {revision}. Local edits were discarded.', {
          revision: conflict.authoritative.revision ?? 0,
        }),
      });
    } catch (cause: unknown) {
      setDraftSaveConflict((current) => current ? {
        ...current,
        busyAction: '',
        error: cause instanceof Error ? cause.message : String(cause),
      } : current);
    }
  }, [applyDslProjection, draftSaveConflict, replaceMutationJournal, t]);

  useEffect(() => {
    if (!isTaskWorkspace || !initialDslHandoff || dslHandoffStartedRef.current) {
      return;
    }
    dslHandoffStartedRef.current = true;
    clearDslAuthorHandoff();
    setDslImportBusy(true);
    setDslImportNotice({ level: 'pending', message: 'Rendering discovered DSL...' });
    setError('');
    previewDslImport({
      sourceId: initialDslHandoff.sourceId,
      dsl: initialDslHandoff.dsl,
      operatorLibraryIds: operatorLibraryIds(operators),
      inlineLibraries: inlineLibrariesFromSourceText(librarySourceText),
      mode: 'preview',
    })
      .then((projection) => applyDslProjectionRef.current(projection))
      .catch((cause: unknown) => {
        setDslImportNotice({ level: 'error', message: String(cause) });
        setStartSection('dsl');
        setStartOpen(true);
      })
      .finally(() => setDslImportBusy(false));
  }, [initialDslHandoff, isTaskWorkspace, librarySourceText, operators]);

  useEffect(() => {
    if (!isTaskWorkspace || !initialBusinessMirrorSeed) return undefined;
    let active = true;
    setDeepLinkNotice({ level: 'pending', message: 'Opening exact Business Mirror Graph...' });
    setError('');
    Promise.all([
      fetchBusinessMirrorLegacyProjection(initialBusinessMirrorSeed.graphName),
      fetchGatewayScenarios(),
    ]).then(async ([projection, scenarios]) => {
      const scenario = scenarios.find(
        (candidate) => candidate.graphName === initialBusinessMirrorSeed.graphName,
      );
      if (!scenario) throw new Error('RG.AUTHORING.BUSINESS_MIRROR_SCENARIO_NOT_FOUND');
      const diagram = await fetchGatewayDiagram(
        scenario.diagramPath
          || `/api/gateway/examples/scenarios/${encodeURIComponent(scenario.graphName)}/diagram`,
      );
      if (!active) return;
      const draft = graphDraftFromBusinessMirrorSeed(
        initialBusinessMirrorSeed,
        projection,
        scenario,
        diagram,
      );
      applyDslProjectionRef.current({
        schemaVersion: 'bloge.dslVisualProjection.v1',
        sourceId: `business-mirror:${initialBusinessMirrorSeed.sourceId}`,
        draft,
        diagnostics: [],
      }, `Business Mirror ${initialBusinessMirrorSeed.sourceId}@${initialBusinessMirrorSeed.sourceRevision}`);
      setAuthorMode('compose');
      setStartOpen(false);
      setDeepLinkNotice({
        level: 'ok',
        message: `Opened exact Business Mirror Graph ${initialBusinessMirrorSeed.sourceId}`
          + `@${initialBusinessMirrorSeed.sourceRevision}. Saving creates a durable authoring draft.`,
      });
    }).catch((cause: unknown) => {
      if (!active) return;
      setDeepLinkNotice({
        level: 'warning',
        message: cause instanceof Error ? cause.message : String(cause),
      });
    });
    return () => { active = false; };
  }, [initialBusinessMirrorSeed, isTaskWorkspace]);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const requestedDraftId = params.get('draftId')?.trim() ?? '';
    const requestedNodeId = params.get('nodeId')?.trim() ?? '';
    const requestedOperatorRef = params.get('operatorRef')?.trim() ?? '';
    const requestedRunId = params.get('runId')?.trim() ?? '';
    const requestedGateIssueId = params.get('gateIssueId')?.trim() ?? '';
    const hasPersistentCoordinate = Boolean(requestedDraftId || requestedRunId);
    if (!hasPersistentCoordinate) {
      return undefined;
    }
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
        const fingerprint = compactAuthorFingerprint(run?.draftFingerprint);
        setDeepLinkNotice({
          level: 'warning',
          message: fingerprint
            ? `Exploratory run ${requestedRunId} is bound to ${fingerprint}; no stored draft revision is available.`
            : `Exploratory run ${requestedRunId} has no stored draft revision or immutable fingerprint.`,
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
      if (isTaskWorkspace) {
        setAuthorMode(initialWorkspaceLocation.mode);
      }

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
  }, [initialWorkspaceLocation.mode, isTaskWorkspace]);

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

      pendingMutationDescriptorRef.current = {
        kind: 'ADD_EDGE',
        label: t('Connect nodes'),
        subjectRef: `${params.sourceNodeId}:${params.targetNodeId}`,
        coalesceKey: `add-edge:${params.sourceNodeId}:${params.targetNodeId}`,
      };
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
  }, [canvasEdges, canvasNodes, clearRunResult, graphName, outputNodeId, t]);

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
    if (!isTaskWorkspace) {
      setEvidenceContentEpoch(authorContentEpoch);
    }

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
  }, [authorContentEpoch, isTaskWorkspace]);

  const runScenarioSimulation = useCallback(async (
    request: Parameters<typeof simulate>[0],
    intent?: ScenarioRunIntent,
  ) => {
    const matrixRun = intent?.reviewMode === 'MATRIX';
    setMatrixDiagnosticsSuppressed(matrixRun);
    if (matrixRun) {
      setDiagnosticsOpen(false);
    }
    const startedAt = performance.now();
    if (isTaskWorkspace) {
      recordAuthorTaskEvent('RUN_STARTED', {
        runKind: 'scenario',
        nodeCount: canvasNodes.length,
        caseCount: 1,
      });
    }
    let status = 'FAILED';
    setBusy(true);
    setError('');
    try {
      const response = await simulate(request);
      showSimulationResponse(response);
      if (isTaskWorkspace && !matrixRun) {
        setContractWorkspaceInitialTab('evidence');
        setAuthorMode('evidence');
      }
      status = isRunSuccessful(response) ? 'PASSED' : 'FAILED';
      return response;
    } catch (cause: unknown) {
      setError(String(cause));
      throw cause;
    } finally {
      setBusy(false);
      if (isTaskWorkspace) {
        if (matrixRun) {
          setDiagnosticsOpen(false);
        }
        if (status === 'PASSED') {
          successfulRunKindRef.current = 'scenario';
        }
        recordAuthorTaskEvent('RUN_COMPLETED', {
          runKind: 'scenario',
          status,
          caseCount: 1,
          durationMs: authorTaskElapsedMs(startedAt),
        });
      }
    }
  }, [canvasNodes.length, isTaskWorkspace, showSimulationResponse]);

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
    if (selectedGovernedFixtureStale) {
      setError(t('The governed fixture schema is stale; recapture before simulating.'));
      return;
    }
    const startedAt = performance.now();
    if (isTaskWorkspace) {
      recordAuthorTaskEvent('RUN_STARTED', {
        runKind: 'graph',
        nodeCount: canvasNodes.length,
        caseCount: 1,
      });
    }
    let status = 'FAILED';
    setBusy(true);
    setError('');
    try {
      const response = await simulate(toSimulationRequest(
        graphName,
        canvasNodes,
        canvasEdges,
        outputNodeId,
        simulationFixtures,
        contextCompilation.value,
        graphInputSchema,
        effectiveGraphOutputSchema,
      ));
      showSimulationResponse(response);
      status = isRunSuccessful(response) ? 'PASSED' : 'FAILED';
      if (status === 'PASSED' && spineEnabled && selectedIsResource) {
        void fetchGovernedFixtureAssets(selectedOperator?.operatorRef)
          .then(setGovernedFixtureAssets)
          .catch(() => undefined);
      }
    } catch (cause: unknown) {
      setError(String(cause));
    } finally {
      setBusy(false);
      if (isTaskWorkspace) {
        if (status === 'PASSED') {
          successfulRunKindRef.current = 'graph';
        }
        recordAuthorTaskEvent('RUN_COMPLETED', {
          runKind: 'graph',
          status,
          caseCount: 1,
          durationMs: authorTaskElapsedMs(startedAt),
        });
      }
    }
  }, [
    canvasEdges,
    canvasNodes,
    contextCompilation.value,
    simulationFixtures,
    effectiveGraphOutputSchema,
    graphName,
    graphInputSchema,
    isTaskWorkspace,
    outputNodeId,
    selectedGovernedFixtureStale,
    selectedIsResource,
    selectedOperator?.operatorRef,
    spineEnabled,
    showSimulationResponse,
    t,
  ]);

  const runSimulationTable = useCallback(async () => {
    const startedAt = performance.now();
    if (isTaskWorkspace) {
      recordAuthorTaskEvent('RUN_STARTED', {
        runKind: 'table',
        nodeCount: canvasNodes.length,
        caseCount: simulationTableRows.length,
      });
    }
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
    let failedCount = Object.keys(initialResults).length;
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
          const request = toSimulationRequest(
            graphName,
            canvasNodes,
            canvasEdges,
            outputNodeId,
            mergeNodeFixtures(simulationFixtures, testCase.fixtures),
            testCase.context,
            graphInputSchema,
            effectiveGraphOutputSchema,
          );
          const requestFingerprint = await sha256Fingerprint(request);
          const response = await simulate(request);
          showSimulationResponse(response);
          const rowResult = evaluateSimulationTableResult(testCase, response);
          const scenario = scenarioDraftSet?.scenarios.find(
            (candidate) => candidate.scenarioId === testCase.id,
          );
          setLastScenarioReviewEvidence({
            scenarioId: testCase.id,
            comparison: scenario
              ? compareScenarioRun(scenario, response)
              : tableCaseScenarioComparison(testCase, response, rowResult),
            response,
            coordinate: evidenceCoordinateForScenario(testCase.id, requestFingerprint),
          });
          if (rowResult.status !== 'passed') {
            failedCount += 1;
          }
          setSimulationTableResults((current) => ({
            ...current,
            [testCase.id]: rowResult,
          }));
        } catch (cause: unknown) {
          failedCount += 1;
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
      if (isTaskWorkspace) {
        const status = failedCount === 0 ? 'PASSED' : 'FAILED';
        if (status === 'PASSED') {
          successfulRunKindRef.current = 'table';
        }
        recordAuthorTaskEvent('RUN_COMPLETED', {
          runKind: 'table',
          status,
          caseCount: simulationTableRows.length,
          durationMs: authorTaskElapsedMs(startedAt),
        });
      }
    }
  }, [
    canvasEdges,
    canvasNodes,
    evidenceCoordinateForScenario,
    simulationFixtures,
    effectiveGraphOutputSchema,
    graphName,
    graphInputSchema,
    isTaskWorkspace,
    outputNodeId,
    showSimulationResponse,
    simulationTableCompilation,
    simulationTableRows,
    scenarioDraftSet,
  ]);

  const runDraftValidation = useCallback(async () => {
    setValidatingDraft(true);
    setError('');
    try {
      setValidationResult(await validateDraft(exportableDraft));
      setValidationContentEpoch(authorContentEpoch);
    } catch (cause: unknown) {
      setError(String(cause));
      setValidationResult(null);
      setValidationContentEpoch(-1);
    } finally {
      setValidatingDraft(false);
    }
  }, [authorContentEpoch, exportableDraft]);

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
    const startedAt = performance.now();
    if (isTaskWorkspace) {
      layoutPlanSequenceRef.current += 1;
      const sequence = layoutPlanSequenceRef.current;
      if (layoutPlanTimerRef.current !== null) {
        window.clearTimeout(layoutPlanTimerRef.current);
      }
      setLayoutPlanning(true);
      setLayoutPreview(null);
      setLayoutNotice({ messageId: 'layout.notice.computing' });
      if (compactWorkspace) {
        setPaletteCollapsed(true);
        setInspectorCollapsed(true);
      }
      layoutPlanTimerRef.current = window.setTimeout(() => {
        if (layoutPlanSequenceRef.current !== sequence) return;
        const nextNodes = constrainedAutoLayoutFlowNodes(nodes, edges, pinnedNodeIds);
        const nextCanvasNodes = nextNodes.map(canvasNodeFromFlowNode);
        const movedNodeCount = nextNodes.filter((node, index) => {
          const current = nodes[index];
          return !current
            || current.position.x !== node.position.x
            || current.position.y !== node.position.y;
        }).length;
        const quality = assessCanvasLayout(
          nextCanvasNodes,
          canvasEdges,
          pinnedNodeIds,
        );
        setLayoutPlanning(false);
        layoutPlanTimerRef.current = null;
        if (movedNodeCount === 0) {
          setLayoutPreview(null);
          setLayoutNotice({
            messageId: 'layout.notice.alreadyOptimalWithQuality',
            params: {
              overlaps: quality.nodeOverlaps,
              collisions: quality.edgeLabelCollisions,
              pinned: quality.pinnedNodes,
            },
          });
          recordAuthorTaskEvent('AUTO_LAYOUT_COMPLETED', {
            nodeCount: nodes.length,
            edgeCount: edges.length,
            movedNodeCount,
            durationMs: authorTaskElapsedMs(startedAt),
          });
          return;
        }
        const candidateSemantics = projectCanvasSemantics(nextCanvasNodes, canvasEdges, {
          mode: canvasTaskMode,
          anchorNodeId: focusPathNodeId,
          selectedNodeId,
        });
        const candidateZoom = estimateCanvasFitZoom(nextCanvasNodes, {
          viewportWidth: flowRef.current?.clientWidth || window.innerWidth,
          viewportHeight: flowRef.current?.clientHeight || window.innerHeight,
          padding: isComplexGraph ? 0.12 : 0.1,
          minZoom: CANVAS_MIN_ZOOM,
          maxZoom: 1,
        });
        const candidatePerception = assessCanvasPerceptualQuality(nextCanvasNodes, {
          mode: canvasTaskMode,
          viewportWidth: flowRef.current?.clientWidth || window.innerWidth,
          viewportHeight: flowRef.current?.clientHeight || window.innerHeight,
          zoom: candidateZoom,
          visibleEdgeLabels: candidateSemantics.visibleEdgeLabelCount,
          visibleFieldLabels: candidateSemantics.visibleFieldCount,
          nodeOverlaps: quality.nodeOverlaps,
          nodeLabelCollisions: candidateSemantics.nodeLabelCollisionCount,
          labelLabelCollisions: candidateSemantics.labelLabelCollisionCount,
        });
        const acceptance = decideLayoutAcceptance(
          projectLayoutQualitySnapshot(
            currentCanvasGeometry,
            canvasPerceptualQuality,
            viewportZoom,
            canvasGraphArea(canvasNodes),
          ),
          projectLayoutQualitySnapshot(
            quality,
            candidatePerception,
            candidateZoom,
            canvasGraphArea(nextCanvasNodes),
          ),
          nextCanvasNodes.length,
        );
        mutationObservationModeRef.current = 'hold';
        setNodes(nextNodes);
        setLayoutPreview({
          positions: Object.fromEntries(nodes.map((node) => [node.id, { ...node.position }])),
          movedNodeCount,
          quality,
          acceptance,
          durationMs: authorTaskElapsedMs(startedAt),
        });
        if (acceptance.decision === 'ALTERNATIVE_REQUIRED') {
          recordAuthorTaskEvent('AUTO_LAYOUT_CANDIDATE_REJECTED', {
            beforeQuality: acceptance.before.perception.status,
            candidateQuality: acceptance.candidate.perception.status,
            regressionCount: acceptance.regressions.length,
            beforeZoomPercent: Math.round(acceptance.before.zoom * 100),
            candidateZoomPercent: Math.round(acceptance.candidate.zoom * 100),
          });
        }
        setLayoutNotice({
          messageId: 'layout.notice.previewMoves',
          params: { count: movedNodeCount },
        });
      }, 0);
      return;
    }

    const nextNodes = autoLayoutFlowNodes(nodes, edges);
    const movedNodeCount = nextNodes.filter((node, index) => {
      const current = nodes[index];
      return !current
        || current.position.x !== node.position.x
        || current.position.y !== node.position.y;
    }).length;
    if (movedNodeCount > 0) {
      setLayoutUndo({
        positions: Object.fromEntries(nodes.map((node) => [node.id, { ...node.position }])),
        movedNodeCount,
      });
      setLayoutNotice({
        messageId: 'layout.notice.moved',
        params: { count: movedNodeCount },
      });
      setNodes(nextNodes);
    } else {
      setLayoutUndo(null);
      setLayoutNotice({ messageId: 'layout.notice.alreadyOptimal' });
    }
    if (typeof window.requestAnimationFrame === 'function') {
      window.requestAnimationFrame(() => fitCanvasToView());
    } else {
      fitCanvasToView();
    }
    if (isTaskWorkspace) {
      recordAuthorTaskEvent('AUTO_LAYOUT_COMPLETED', {
        nodeCount: nodes.length,
        edgeCount: edges.length,
        movedNodeCount,
        durationMs: authorTaskElapsedMs(startedAt),
      });
    }
  }, [
    canvasEdges,
    canvasNodes,
    canvasPerceptualQuality,
    canvasTaskMode,
    compactWorkspace,
    currentCanvasGeometry,
    edges,
    fitCanvasToView,
    focusPathNodeId,
    isComplexGraph,
    isTaskWorkspace,
    nodes,
    pinnedNodeIds,
    selectedNodeId,
    viewportZoom,
  ]);

  const applyLayoutPreview = useCallback(() => {
    if (!layoutPreview || layoutPreview.acceptance.decision !== 'ACCEPTABLE') return;
    commitHeldMutation({
      kind: 'AUTO_LAYOUT',
      label: t('Apply auto layout'),
      subjectRef: 'graph-layout',
      impact: [{
        kind: 'NODE',
        count: layoutPreview.movedNodeCount,
        refs: nodes.map((node) => node.id),
        severity: 'INFO',
      }],
    });
    setLayoutUndo({
      positions: layoutPreview.positions,
      movedNodeCount: layoutPreview.movedNodeCount,
    });
    setLayoutNotice({
      messageId: 'layout.notice.applied',
      params: {
        count: layoutPreview.movedNodeCount,
        overlaps: layoutPreview.quality.nodeOverlaps,
        collisions: layoutPreview.quality.edgeLabelCollisions,
      },
    });
    recordAuthorTaskEvent('AUTO_LAYOUT_COMPLETED', {
      nodeCount: nodes.length,
      edgeCount: edges.length,
      movedNodeCount: layoutPreview.movedNodeCount,
      durationMs: layoutPreview.durationMs,
    });
    setLayoutPreview(null);
  }, [commitHeldMutation, edges.length, layoutPreview, nodes, t]);

  const overrideLayoutPreview = useCallback(() => {
    if (!layoutPreview || layoutPreview.acceptance.decision !== 'ALTERNATIVE_REQUIRED') return;
    const acceptance = overrideLayoutAcceptance(
      layoutPreview.acceptance,
      'USER_ACCEPTED_READABILITY_REGRESSION',
    );
    recordAuthorTaskEvent('AUTO_LAYOUT_OVERRIDE_APPLIED', {
      overrideReason: acceptance.overrideReason,
      regressionCount: acceptance.regressions.length,
    });
    commitHeldMutation({
      kind: 'AUTO_LAYOUT',
      label: t('Apply auto layout'),
      subjectRef: 'graph-layout',
      impact: [{
        kind: 'NODE',
        count: layoutPreview.movedNodeCount,
        refs: nodes.map((node) => node.id),
        severity: 'INFO',
      }],
    });
    setLayoutUndo({
      positions: layoutPreview.positions,
      movedNodeCount: layoutPreview.movedNodeCount,
    });
    setLayoutNotice({
      messageId: 'layout.notice.overrideApplied',
      params: {
        count: layoutPreview.movedNodeCount,
        overlaps: layoutPreview.quality.nodeOverlaps,
        collisions: layoutPreview.quality.edgeLabelCollisions,
      },
    });
    recordAuthorTaskEvent('AUTO_LAYOUT_COMPLETED', {
      nodeCount: nodes.length,
      edgeCount: edges.length,
      movedNodeCount: layoutPreview.movedNodeCount,
      durationMs: layoutPreview.durationMs,
    });
    setLayoutPreview(null);
  }, [commitHeldMutation, edges.length, layoutPreview, nodes, t]);

  const cancelLayoutPreview = useCallback(() => {
    layoutPlanSequenceRef.current += 1;
    if (layoutPlanTimerRef.current !== null) {
      window.clearTimeout(layoutPlanTimerRef.current);
      layoutPlanTimerRef.current = null;
    }
    setLayoutPlanning(false);
    if (layoutPreview) {
      mutationObservationModeRef.current = 'reset';
      setNodes((current) => current.map((node) => ({
        ...node,
        position: layoutPreview.positions[node.id] ?? node.position,
      })));
      setLayoutNotice({ messageId: 'layout.notice.previewCanceled' });
      window.requestAnimationFrame?.(() => fitCanvasToView());
    } else {
      setLayoutNotice({ messageId: 'layout.notice.computationCanceled' });
    }
    setLayoutPreview(null);
  }, [fitCanvasToView, layoutPreview]);

  const undoAutoLayout = useCallback(() => {
    if (!layoutUndo) return;
    if (isTaskWorkspace) {
      undoAuthoringMutation();
      setLayoutNotice({
        messageId: 'layout.notice.restored',
        params: { count: layoutUndo.movedNodeCount },
      });
      recordAuthorTaskEvent('AUTO_LAYOUT_UNDONE', {
        movedNodeCount: layoutUndo.movedNodeCount,
      });
      setLayoutUndo(null);
      return;
    }
    setNodes((current) => current.map((node) => ({
      ...node,
      position: layoutUndo.positions[node.id] ?? node.position,
    })));
    setLayoutNotice({
      messageId: 'layout.notice.restored',
      params: { count: layoutUndo.movedNodeCount },
    });
    if (isTaskWorkspace) {
      recordAuthorTaskEvent('AUTO_LAYOUT_UNDONE', {
        movedNodeCount: layoutUndo.movedNodeCount,
      });
    }
    setLayoutUndo(null);
    if (typeof window.requestAnimationFrame === 'function') {
      window.requestAnimationFrame(() => fitCanvasToView());
    } else {
      fitCanvasToView();
    }
  }, [fitCanvasToView, isTaskWorkspace, layoutUndo, undoAuthoringMutation]);

  const toggleSelectedNodePin = useCallback(() => {
    if (!selectedNodeId) return;
    setPinnedNodeIds((current) => {
      const next = new Set(current);
      if (next.has(selectedNodeId)) {
        next.delete(selectedNodeId);
        setLayoutNotice({ messageId: 'layout.notice.nodeMovable' });
      } else {
        next.add(selectedNodeId);
        setLayoutNotice({ messageId: 'layout.notice.nodePinned' });
      }
      return next;
    });
  }, [selectedNodeId]);

  const toggleFocusPath = useCallback(() => {
    if (focusPathNodeId) {
      setFocusPathNodeId('');
      fitCanvasToView();
      return;
    }
    if (!selectedNodeId) return;
    const path = canvasFocusPath(canvasNodes, canvasEdges, selectedNodeId);
    setFocusPathNodeId(selectedNodeId);
    if (isTaskWorkspace) {
      fitCanvasToView(undefined, 'focus');
    } else {
      flowInstanceRef.current?.fitView({
        nodes: nodes.filter((node) => path.nodeIds.has(node.id)),
        padding: 0.08,
        duration: 240,
        minZoom: semanticZoomContract('focus').minimumZoom,
      });
    }
  }, [
    canvasEdges,
    canvasNodes,
    fitCanvasToView,
    focusPathNodeId,
    isTaskWorkspace,
    nodes,
    selectedNodeId,
  ]);

  const focusNodeFromNavigator = useCallback((nodeId: string) => {
    const target = nodes.find((node) => node.id === nodeId);
    if (!target) return;
    setSelectedNodeId(nodeId);
    setFocusPathNodeId('');
    if (compactWorkspace) {
      setPaletteCollapsed(true);
      setInspectorCollapsed(false);
    }
    flowInstanceRef.current?.fitView({
      nodes: [target],
      padding: 0.72,
      duration: 220,
      maxZoom: 1,
    });
  }, [compactWorkspace, nodes]);

  const activateCanvasTaskMode = useCallback((mode: CanvasTaskMode) => {
    if (mode === 'overview') {
      setFocusPathNodeId('');
      setSelectedNodeId('');
      if (compactWorkspace) {
        setInspectorCollapsed(true);
      }
      fitCanvasToView(undefined, 'overview');
      return;
    }
    if (!selectedNodeId) return;
    if (mode === 'focus') {
      setFocusPathNodeId(selectedNodeId);
      if (compactWorkspace) {
        setPaletteCollapsed(true);
        setInspectorCollapsed(true);
      }
      fitCanvasToView(undefined, 'focus');
      return;
    }
    setFocusPathNodeId('');
    if (compactWorkspace) {
      setPaletteCollapsed(true);
      setInspectorCollapsed(false);
    }
  }, [
    compactWorkspace,
    fitCanvasToView,
    selectedNodeId,
  ]);

  const toggleCanvasExpanded = useCallback(() => {
    const expanding = !canvasFocusMode;
    const selected = nodes.find((node) => node.id === selectedNodeId);
    setCanvasFocusMode(expanding);
    if (expanding) {
      overviewBeforeFocusRef.current = overviewVisible;
      if (selected) {
        setOverviewVisible(true);
      }
    }
    if (!expanding) {
      setOverviewVisible(overviewBeforeFocusRef.current);
      const settleCamera = () => fitCanvasToView();
      if (typeof window.requestAnimationFrame === 'function') {
        window.requestAnimationFrame(() => window.requestAnimationFrame(settleCamera));
      } else {
        window.setTimeout(settleCamera, 0);
      }
    }
  }, [canvasFocusMode, fitCanvasToView, nodes, overviewVisible, selectedNodeId]);

  const togglePalettePanel = useCallback(() => {
    setPaletteCollapsed((current) => {
      const opening = current;
      if (opening && compactWorkspace) {
        setInspectorCollapsed(true);
        setInspectorPreference('closed');
      }
      setPalettePreference(opening ? 'open' : 'closed');
      return !current;
    });
  }, [compactWorkspace]);

  const toggleInspectorPanel = useCallback(() => {
    setInspectorCollapsed((current) => {
      const opening = current;
      if (opening && compactWorkspace) {
        setPaletteCollapsed(true);
        setPalettePreference('closed');
      }
      setInspectorPreference(opening ? 'open' : 'closed');
      return !current;
    });
  }, [compactWorkspace]);

  const beginPanelResize = useCallback((
    panel: 'palette' | 'inspector',
    event: ReactPointerEvent<HTMLButtonElement>,
  ) => {
    event.preventDefault();
    const startX = event.clientX;
    const startWidth = panel === 'palette' ? paletteWidth : inspectorWidth;
    const move = (moveEvent: PointerEvent) => {
      const delta = panel === 'palette'
        ? moveEvent.clientX - startX
        : startX - moveEvent.clientX;
      const nextWidth = Math.max(220, Math.min(360, startWidth + delta));
      if (panel === 'palette') {
        setPaletteWidth(nextWidth);
      } else {
        setInspectorWidth(nextWidth);
      }
    };
    const stop = () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', stop);
      document.body.style.cursor = '';
    };
    document.body.style.cursor = 'col-resize';
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', stop, { once: true });
  }, [inspectorWidth, paletteWidth]);

  useEffect(() => {
    if (!isTaskWorkspace) {
      return;
    }
    const workspaceOpen = contractWorkspaceOpen || operatorContractWorkspace !== null;
    const authorUrl = authorWorkspaceUrl(window.location.href, authorMode, selectedNodeId, {
      target: operatorContractWorkspace
        ? `operator:${operatorContractWorkspace.contract.target.id}`
        : authorMode === 'compose'
          ? ''
          : !initialOperatorTargetRestoredRef.current
              && initialWorkspaceLocation.target.startsWith('operator:')
            ? initialWorkspaceLocation.target
            : 'graph',
      workspaceView: workspaceOpen
        ? contractWorkspaceInitialTab
        : authorMode === 'compose' ? '' : workspaceTabForMode(authorMode),
      scenarioId: workspaceOpen ? workspaceScenarioId : '',
    });
    const nextUrl = taskCoordinateUrl(authorUrl, workspaceTaskCoordinate);
    const currentUrl = `${window.location.pathname}${window.location.search}${window.location.hash}`;
    if (nextUrl !== currentUrl) {
      window.history.replaceState(window.history.state, '', nextUrl);
    }
  }, [
    authorMode,
    contractWorkspaceInitialTab,
    contractWorkspaceOpen,
    isTaskWorkspace,
    operatorContractWorkspace,
    selectedNodeId,
    workspaceTaskCoordinate,
    workspaceScenarioId,
  ]);

  useEffect(() => {
    if (!isTaskWorkspace) {
      return undefined;
    }
    const restoreLocation = () => {
      const location = parseAuthorWorkspaceLocation(window.location.search);
      const nextTab = location.workspaceView || workspaceTabForMode(location.mode);
      setAuthorMode(location.mode);
      setSelectedNodeId(location.selectedNodeId);
      setWorkspaceScenarioId(location.scenarioId);
      setContractWorkspaceInitialTab(nextTab);
      if (location.mode === 'compose') {
        setContractWorkspaceOpen(false);
        setOperatorContractWorkspace(null);
        return;
      }
      const operatorRef = location.target.startsWith('operator:')
        ? location.target.slice('operator:'.length)
        : '';
      if (operatorRef) {
        const operator = operators.find((candidate) => candidate.operatorRef === operatorRef);
        if (operator) {
          void openOperatorContractWorkspace(operator, nextTab, location.selectedNodeId);
          return;
        }
        setDeepLinkNotice({
          level: 'warning',
          message: `Operator target ${operatorRef} is unavailable; opened the Graph target instead.`,
        });
      }
      setOperatorContractWorkspace(null);
      setContractWorkspaceOpen(true);
    };
    window.addEventListener('popstate', restoreLocation);
    return () => window.removeEventListener('popstate', restoreLocation);
  }, [isTaskWorkspace, openOperatorContractWorkspace, operators]);

  useEffect(() => {
    if (
      !isTaskWorkspace
      || initialOperatorTargetRestoredRef.current
      || !initialWorkspaceLocation.target.startsWith('operator:')
      || initialWorkspaceLocation.mode === 'compose'
      || operators.length === 0
    ) {
      return;
    }
    const operatorRef = initialWorkspaceLocation.target.slice('operator:'.length);
    const operator = operators.find((candidate) => candidate.operatorRef === operatorRef);
    initialOperatorTargetRestoredRef.current = true;
    if (operator) {
      void openOperatorContractWorkspace(
        operator,
        initialWorkspaceLocation.workspaceView
          || workspaceTabForMode(initialWorkspaceLocation.mode),
        initialWorkspaceLocation.selectedNodeId,
      );
      return;
    }
    setDeepLinkNotice({
      level: 'warning',
      message: `Operator target ${operatorRef} is unavailable; opened the Graph target instead.`,
    });
    setContractWorkspaceOpen(true);
  }, [
    initialWorkspaceLocation,
    isTaskWorkspace,
    openOperatorContractWorkspace,
    operators,
  ]);

  const activeScenarioEvidence = lastScenarioReviewEvidence
    && lastScenarioReviewEvidence.coordinate.targetKind === activeTaskContract?.target.kind
    && lastScenarioReviewEvidence.coordinate.targetId === activeTaskContract.target.id
      ? lastScenarioReviewEvidence
      : null;
  const activeTaskRunResponse = isTaskWorkspace
    ? activeScenarioEvidence?.response ?? null
    : result;
  const hasRunResult = isTaskWorkspace
    ? activeScenarioEvidence !== null
    : result !== null || Object.keys(simulationTableResults).length > 0;
  const runSuccessful = Boolean(
    (activeTaskRunResponse
      ? isRunSuccessful(activeTaskRunResponse)
      : Object.keys(simulationTableResults).length > 0)
    && (!activeScenarioEvidence || activeScenarioEvidence.comparison.passed)
    && Object.values(simulationTableResults).every((row) => row.status === 'passed'),
  );
  const evidenceStale = hasRunResult && (
    evidenceContentEpoch !== authorContentEpoch
    || Boolean(activeScenarioEvidence?.coordinate.editorSnapshotFingerprint && (
      !activeScenarioFingerprint
      || activeScenarioEvidence.coordinate.editorSnapshotFingerprint !== activeScenarioFingerprint
    ))
  );
  const assertionsEvaluated = Boolean(
    activeScenarioEvidence || Object.keys(simulationTableResults).length > 0,
  );
  const canonicalScenarioReady = Boolean(
    activeTaskContract
    && activeTaskScenarioSet
    && activeTaskScenarioSet.scenarios.length > 0
    && activeScenarioFingerprint
    && operatorClosureFingerprint
    && scenarioSetIsCurrent(
      activeTaskScenarioSet,
      activeTaskContract.target.fingerprint,
      activeTaskContractFingerprint,
    ),
  );
  const exactSavedDraft = Boolean(
    graphDraftId
    && graphDraftRevision > 0
    && authoritativeContractRef.current?.canvasSnapshot === canonicalJson(exportableDraft),
  );
  const toolMutationFingerprintRef = useRef(currentMutationFingerprint);
  useEffect(() => {
    if (toolMutationFingerprintRef.current !== currentMutationFingerprint) {
      setToolPublication(undefined);
      toolMutationFingerprintRef.current = currentMutationFingerprint;
    }
  }, [currentMutationFingerprint]);
  useEffect(() => {
    if (!isTaskWorkspace || !exactSavedDraft) return;
    const next = markSavedCheckpoint(mutationJournalRef.current, currentMutationFingerprint);
    if (next !== mutationJournalRef.current) replaceMutationJournal(next);
  }, [currentMutationFingerprint, exactSavedDraft, isTaskWorkspace, replaceMutationJournal]);
  const authoringRecoveryPayload = useMemo<AuthoringRecoveryPayload>(() => ({
    graphDraft: exportableDraft,
    scenarioDraftSet,
    fixtureDrafts,
    fixtureInputDrafts,
    operatorTestSuites,
    simulationTableRows,
    runInputValue,
    simulationContextDraft,
    rawContextMode,
    contextVariables,
    selectedNodeId,
    explicitOutputNodeId,
    authorMode,
    loadedExampleKey,
    workspaceForkIdempotencyKey: workspaceForkIdempotencyKeyRef.current,
    mutationJournal: mutationJournalForRecovery(mutationJournal),
  }), [
    authorMode,
    contextVariables,
    explicitOutputNodeId,
    exportableDraft,
    fixtureDrafts,
    fixtureInputDrafts,
    loadedExampleKey,
    mutationJournal,
    operatorTestSuites,
    rawContextMode,
    runInputValue,
    scenarioDraftSet,
    selectedNodeId,
    simulationContextDraft,
    simulationTableRows,
  ]);
  const authoringRecoveryContent = useMemo(() => ({
    graphDraft: exportableDraft,
    scenarioDraftSet,
    fixtureDrafts,
    fixtureInputDrafts,
    operatorTestSuites,
    simulationTableRows,
    runInputValue,
    simulationContextDraft,
    rawContextMode,
    contextVariables,
  }), [
    contextVariables,
    exportableDraft,
    fixtureDrafts,
    fixtureInputDrafts,
    operatorTestSuites,
    rawContextMode,
    runInputValue,
    scenarioDraftSet,
    simulationContextDraft,
    simulationTableRows,
  ]);
  const restoreAuthoringWorkspace = useCallback((
    recovered: AuthoringRecoveryPayload,
    capturedAt: string,
  ) => {
    mutationObservationModeRef.current = 'reset';
    const recoveredJournal = restoreMutationJournal<AuthoringMutationSnapshot>(
      recovered.mutationJournal,
      isAuthoringMutationSnapshot,
    );
    replaceMutationJournal(recoveredJournal);
    mutationSequenceRef.current = recoveredJournal.past.length + recoveredJournal.future.length;
    applyDslProjection({
      schemaVersion: 'bloge.dslVisualProjection.v1',
      sourceId: `recovery:${recovered.graphDraft.graphName}`,
      draft: recovered.graphDraft,
      diagnostics: [],
    }, `Recovered session ${capturedAt}`);
    setScenarioDraftSet(recovered.scenarioDraftSet);
    setFixtureDrafts(recovered.fixtureDrafts);
    setFixtureInputDrafts(recovered.fixtureInputDrafts);
    setOperatorTestSuites(recovered.operatorTestSuites);
    setOperatorTestResults({});
    setOperatorTestPublications({});
    setSimulationTableRows(recovered.simulationTableRows);
    setSimulationTableResults({});
    setRunInputValue(recovered.runInputValue);
    setSimulationContextDraft(recovered.simulationContextDraft);
    setRawContextMode(recovered.rawContextMode);
    setContextVariables(recovered.contextVariables);
    setSelectedNodeId(recovered.selectedNodeId);
    setExplicitOutputNodeId(recovered.explicitOutputNodeId);
    setAuthorMode(recovered.authorMode);
    setLoadedExampleKey(recovered.loadedExampleKey);
    workspaceForkIdempotencyKeyRef.current = recovered.workspaceForkIdempotencyKey
      || `recovery:${Date.now()}`;
    tableTestCounter.current = recovered.simulationTableRows.length;
    operatorTestCounter.current = Object.values(recovered.operatorTestSuites)
      .reduce((total, rows) => total + rows.length, 0);
    setStartOpen(false);
    setConnectionNotice({
      level: 'ok',
      message: t('Recovered {graph} from {capturedAt}. Save it to create an authoritative revision.', {
        graph: recovered.graphDraft.graphName,
        capturedAt: new Date(capturedAt).toLocaleString(locale),
      }),
    });
  }, [applyDslProjection, locale, replaceMutationJournal, t]);
  const authoringContinuity = useWorkspaceContinuity({
    enabled: isTaskWorkspace,
    ready: operators.length > 0,
    allowRecovery: !initialWorkspaceLocation.hasDeepLinkTarget && !initialDslHandoff,
    hasContent: nodes.length > 0,
    coordinate: {
      tenantId: graphTenantId,
      namespace: graphNamespace,
      environment: graphEnvironment,
      ...(graphDraftId ? { draftId: graphDraftId } : {}),
    },
    payload: authoringRecoveryPayload,
    fingerprintValue: authoringRecoveryContent,
    authoritativelySaved: exactSavedDraft,
    savedRevision: graphDraftRevision,
    canAutosave: Boolean(
      graphDraftId
      && graphDraftRevision > 0
      && !exactSavedDraft
      && !busy
      && !hasFixtureErrors
      && !draftSaveConflict
    ),
    onRestore: restoreAuthoringWorkspace,
    onSave: saveGraphForScenario,
    recoveryPayloadGuard: isAuthoringRecoveryPayload,
    recoveryFingerprintValue: authoringRecoveryFingerprintValue,
  });
  const saveAuthoritativeGraph = useCallback(async () => {
    const saved = await authoringContinuity.save();
    if (!saved) {
      throw new Error('Graph save did not reach an authoritative revision.');
    }
  }, [authoringContinuity.save]);
  const createBusinessMirrorWorkingCopy = useCallback(async () => {
    if (!initialBusinessMirrorSeed || sourceCopyBusy) return;
    setSourceCopyBusy(true);
    setSourceCopyError('');
    lastSavedGraphRef.current = null;
    try {
      await saveGraphForScenario();
      const stored = lastSavedGraphRef.current as GraphDraft | null;
      if (!stored?.draftId || !stored.revision) {
        throw new Error('The working copy did not return an exact Graph revision.');
      }
      const url = new URL(window.location.href);
      [
        'sourceKind', 'sourceGraphName', 'sourceId', 'sourceRevision', 'sourceFingerprint',
      ].forEach((key) => url.searchParams.delete(key));
      url.searchParams.set('draftId', stored.draftId);
      url.searchParams.set('revision', String(stored.revision));
      window.history.replaceState(window.history.state, '', `${url.pathname}${url.search}${url.hash}`);
      setDeepLinkNotice({
        level: 'ok',
        message: `Created editable working copy ${stored.draftId}@${stored.revision}. The source lineage remains attached.`,
      });
      setConnectionNotice({
        level: 'ok',
        message: `Working copy ${stored.draftId}@${stored.revision} is editable and retains its source lineage.`,
      });
    } catch (cause: unknown) {
      setSourceCopyError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setSourceCopyBusy(false);
    }
  }, [initialBusinessMirrorSeed, saveGraphForScenario, sourceCopyBusy]);
  const authorReadiness = projectAuthorReadiness({
    draft: {
      durable: Boolean(graphDraftId && graphDraftRevision > 0),
      current: exactSavedDraft,
      conflicted: Boolean(draftSaveConflict),
    },
    execution: {
      busy: busy || tableTestingBusy,
      evaluated: hasRunResult,
      passed: runSuccessful,
      warnings: Boolean(activeTaskRunResponse?.diagnostics.some(
        (diagnostic) => diagnostic.level?.trim().toUpperCase() === 'WARNING',
      )),
      stale: evidenceStale,
    },
    assertions: {
      configured: Boolean(
        activeScenarioEvidence?.comparison.results.length
        || simulationTableRows.length,
      ),
      busy: tableTestingBusy,
      evaluated: assertionsEvaluated,
      passed: Boolean(
        activeScenarioEvidence
          ? activeScenarioEvidence.comparison.passed
          : Object.keys(simulationTableResults).length > 0
            && simulationTableRunSummary.state === 'passed',
      ),
      stale: evidenceStale,
    },
    contract: {
      busy: validatingDraft,
      evaluated: validationResult !== null,
      passed: Boolean(validationResult?.valid),
      stale: Boolean(
        validationResult && validationContentEpoch !== authorContentEpoch,
      ),
    },
    governance: {
      busy: governanceGateBusy,
      evaluated: Boolean(governanceGateView?.result),
      status: governanceGateView?.result?.status ?? '',
      stale: Boolean(
        governanceGateView?.result
        && (
          governanceGateView.freshness !== 'CURRENT'
          || !exactSavedDraft
        )
      ),
    },
  });
  const executionStatus = authorReadiness.execution.replace(/_/g, ' ');
  const assertionStatus = authorReadiness.assertions.replace(/_/g, ' ');
  const contractStatus = authorReadiness.contract.replace(/_/g, ' ');
  const governanceStatus = authorReadiness.governance.replace(/_/g, ' ');
  const taskDraftStatus = operatorContractWorkspace ? 'SAVED' : authorReadiness.draft;
  const taskContractStatus = operatorContractWorkspace ? 'VALID' : contractStatus;
  const taskGovernanceStatus = operatorContractWorkspace ? 'NOT CHECKED' : governanceStatus;
  const taskPromotionStatus = operatorContractWorkspace
    ? hasRunResult && (!runSuccessful || evidenceStale) ? 'BLOCKED' : 'NOT EVALUATED'
    : authorReadiness.promotion.replace(/_/g, ' ');
  const taskPromotionSummary = operatorContractWorkspace
    ? taskPromotionStatus === 'BLOCKED'
      ? t('Current Operator evidence is not eligible for governance review.')
      : t('Operator governance is not evaluated in this workspace.')
    : t('{headline}. {summary}', {
      headline: d(authorReadiness.headline),
      summary: d(authorReadiness.summary, authorReadiness.summaryValues),
    });
  const closeTestSuite = useCallback(() => {
    setTestSuiteOpen(false);
    if (isTaskWorkspace) {
      setAuthorMode(hasRunResult ? 'evidence' : 'compose');
    }
  }, [hasRunResult, isTaskWorkspace]);
  useDialogFocusTrap({
    open: testSuiteOpen && !isTaskWorkspace,
    dialogRef: testSuiteDialogRef,
    onDismiss: closeTestSuite,
  });

  useEffect(() => {
    if (
      isTaskWorkspace
      && hasRunResult
      && runSuccessful
      && !busy
      && !tableTestingBusy
      && successfulRunKindRef.current
      && !firstAuthorSuccessRecordedRef.current
    ) {
      firstAuthorSuccessRecordedRef.current = true;
      recordAuthorTaskEvent('FIRST_SUCCESS', {
        elapsedMs: authorTaskElapsedMs(authorSessionStartedAtRef.current),
        runKind: successfulRunKindRef.current,
      });
    }
  }, [busy, hasRunResult, isTaskWorkspace, runSuccessful, tableTestingBusy]);

  const authorTaskState = projectAuthorTaskState({
    activeMode: authorMode,
    nodeCount: activeTaskGraphDraft.nodes.length,
    busy: busy || tableTestingBusy,
    hasInputErrors: operatorContractWorkspace === null && (
      hasFixtureErrors
      || hasContextError
      || (!isTaskWorkspace && hasSimulationTableErrors)
    ),
    hasScenario: Boolean(activeTaskScenarioSet?.scenarios.length),
    canonicalScenarioReady,
    hasRunResult,
    runSuccessful,
    evidenceStale,
    governanceApproved: !operatorContractWorkspace && authorReadiness.governance === 'APPROVED',
    coordinate: {
      targetKind: activeTaskContract?.target.kind ?? 'GRAPH',
      targetId: activeTaskContract?.target.id ?? '',
      targetRevision: activeTaskContract?.target.revision ?? 0,
      targetFingerprint: activeTaskContract?.target.fingerprint ?? '',
      contractFingerprint: activeTaskContractFingerprint,
      scenarioSetId: activeTaskScenarioSet?.scenarioDraftSetId ?? '',
      scenarioId: workspaceScenarioId,
      scenarioRevision: activeTaskScenarioSet?.revision ?? 0,
      scenarioFingerprint: activeScenarioFingerprint,
      operatorClosureFingerprint,
    },
    ...(layoutPlanning || layoutPreview ? {
      interactionBlocker: {
        code: 'RG.AUTHOR.RUN.LAYOUT_PENDING',
        message: 'Accept or cancel the pending layout preview before running.',
        messageId: 'author.blocker.layoutPending',
        remediation: {
          label: 'Review layout',
          labelId: 'author.command.reviewLayout',
          mode: 'compose',
        },
      },
    } : {}),
  });
  const executeCommandPolicy = evaluateTaskCommandAuthority({
    commandId: 'RUN_CURRENT_SCENARIO',
    risk: 'EXECUTE',
    coordinate: workspaceTaskCoordinate,
    sessionTenantId,
  });
  const authorizedRunCommand: AuthorCommandAvailability = executeCommandPolicy.enabled
    ? authorTaskState.commands.runCurrentScenario
    : {
        ...authorTaskState.commands.runCurrentScenario,
        state: 'BLOCKED',
        enabled: false,
        reasonCode: executeCommandPolicy.reasonCode,
        message: 'This role or tenant scope cannot execute Scenarios.',
        messageId: undefined,
        remediation: undefined,
      };
  const authorizedPrimaryCommand = authorTaskState.primaryAction.kind === 'run'
    ? authorizedRunCommand
    : authorTaskState.primaryCommand;
  const primaryAction = authorTaskState.primaryAction;
  const authorScenarioResults = useMemo<Record<string, SimulationTableCaseResult>>(() => {
    if (!activeScenarioEvidence) {
      return simulationTableResults;
    }
    const failed = activeScenarioEvidence.comparison.results.find((comparison) => !comparison.passed);
    return {
      [activeScenarioEvidence.scenarioId]: {
        id: activeScenarioEvidence.scenarioId,
        name: activeTaskScenarioSet?.scenarios.find(
          (scenario) => scenario.scenarioId === activeScenarioEvidence.scenarioId,
        )?.name ?? activeScenarioEvidence.scenarioId,
        status: activeScenarioEvidence.comparison.passed ? 'passed' : 'failed',
        detail: failed?.detail
          ?? activeScenarioEvidence.comparison.diagnostics[0]?.message
          ?? 'Scenario assertions passed.',
      },
    };
  }, [activeScenarioEvidence, activeTaskScenarioSet?.scenarios, simulationTableResults]);
  const resultMessage = error
    || (hasRunResult
      ? evidenceStale
        ? 'Evidence retained but stale. Rerun the current Scenario before relying on it.'
        : runSuccessful
        ? activeScenarioEvidence
          ? `${activeScenarioEvidence.comparison.results.filter((item) => item.passed).length}`
            + `/${activeScenarioEvidence.comparison.results.length} scenario assertions passed.`
          : simulationTableRows.length > 0
          ? `${simulationTableRunSummary.passed}/${simulationTableRunSummary.total} scenario assertions passed.`
          : 'Execution completed successfully.'
        : activeScenarioEvidence
          ? `${activeScenarioEvidence.comparison.results.filter((item) => !item.passed).length}`
            + `/${activeScenarioEvidence.comparison.results.length} scenario assertions failed.`
          : simulationTableRunSummary.failed > 0
          ? `${simulationTableRunSummary.failed}/${simulationTableRunSummary.total} scenario assertions failed.`
          : 'Execution completed with failures.'
      : '');
  const runProvenance = hasRunResult
    ? [
        activeScenarioEvidence?.coordinate.targetId
          ? `${activeScenarioEvidence.coordinate.targetKind === 'OPERATOR' ? 'Operator' : 'Graph'}`
            + ` ${activeScenarioEvidence.coordinate.targetId}`
            + ` r${activeScenarioEvidence.coordinate.targetRevision}`
          : 'Exploratory run',
        compactAuthorFingerprint(
          activeScenarioEvidence?.coordinate.draftFingerprint
            || activeTaskContract?.target.fingerprint,
        ),
        evidenceStale ? 'STALE' : 'CURRENT',
        activeScenarioEvidence?.coordinate.targetRevision
          ? 'revision-bound evidence'
          : 'simulation evidence only',
      ].filter(Boolean).join(' · ')
    : '';
  const diagnosticItems = useMemo(
    () => projectAuthorDiagnostics({
      error,
      validation: validationResult,
      run: activeTaskRunResponse,
      scenarioResults: authorScenarioResults,
      governance: governanceGateView,
      dslDiagnostics: dslImportDiagnostics,
      effectiveContract: selectedEffectiveContract,
      readinessReasons: authorReadiness.reasons,
    }),
    [
      dslImportDiagnostics,
      error,
      governanceGateView,
      activeTaskRunResponse,
      authorReadiness.reasons,
      authorScenarioResults,
      selectedEffectiveContract,
      validationResult,
    ],
  );
  const scenarioEvidenceTrustContext = useMemo<ScenarioEvidenceTrustContext>(
    () => ({
      draftStatus: taskDraftStatus,
      evidenceFreshness: evidenceStale ? 'STALE' : 'CURRENT',
      contractStatus: taskContractStatus,
      governanceStatus: taskGovernanceStatus,
      coordinate: activeScenarioEvidence?.coordinate,
      diagnostics: diagnosticItems
        .filter((item) => item.scope === 'CONTRACT' || item.scope === 'GOVERNANCE')
        .map((item) => ({
          id: item.id,
          severity: item.severity,
          scope: item.scope,
          code: item.code,
          message: item.message,
          coordinate: item.coordinate,
          nodeId: item.nodeId,
          recommendedAction: item.recommendedAction,
          deepLink: item.deepLink,
          requiredRole: item.requiredRole,
          owner: item.owner,
          auditRequirement: item.auditRequirement,
          expiresAt: item.expiresAt,
        })),
    }),
    [
      diagnosticItems,
      evidenceStale,
      activeScenarioEvidence?.coordinate,
      taskContractStatus,
      taskDraftStatus,
      taskGovernanceStatus,
    ],
  );

  const openAuthorDiagnostic = useCallback((item: { scope: string; nodeId?: string }) => {
    setAuthorMode('evidence');
    if (item.nodeId && nodes.some((node) => node.id === item.nodeId)) {
      setSelectedNodeId(item.nodeId);
      setContractWorkspaceOpen(false);
      setOperatorContractWorkspace(null);
    } else if (item.scope === 'SCENARIO') {
      setAuthorMode('scenarios');
      setOperatorContractWorkspace(null);
      setContractWorkspaceInitialTab('scenarios');
      setContractWorkspaceOpen(true);
    } else if (item.scope === 'CONTRACT') {
      setAuthorMode('contract');
      setOperatorContractWorkspace(null);
      setContractWorkspaceInitialTab('interface');
      setContractWorkspaceOpen(true);
    } else {
      setDiagnosticsOpen(true);
      setContractWorkspaceOpen(false);
      setOperatorContractWorkspace(null);
    }
  }, [nodes]);

  const changeAuthorMode = useCallback((nextMode: AuthorMode) => {
    if (nextMode !== 'scenarios') {
      setMatrixDiagnosticsSuppressed(false);
    }
    const nextTab = workspaceTabForMode(nextMode);
    const nextUrl = authorWorkspaceUrl(window.location.href, nextMode, selectedNodeId, {
      target: nextMode === 'compose'
        ? ''
        : operatorContractWorkspace
          ? `operator:${operatorContractWorkspace.contract.target.id}`
          : 'graph',
      workspaceView: nextMode === 'compose' ? '' : nextTab,
      scenarioId: nextMode === 'compose' ? '' : workspaceScenarioId,
    });
    const currentUrl = `${window.location.pathname}${window.location.search}${window.location.hash}`;
    if (nextUrl !== currentUrl) {
      window.history.pushState(window.history.state, '', nextUrl);
    }
    setAuthorMode(nextMode);
    setTestSuiteOpen(false);
    if (nextMode === 'compose') {
      setContractWorkspaceOpen(false);
      setOperatorContractWorkspace(null);
      return;
    }
    if (nextMode === 'contract') {
      setContractWorkspaceInitialTab('interface');
    } else if (nextMode === 'scenarios') {
      setContractWorkspaceInitialTab('scenarios');
    } else {
      setContractWorkspaceInitialTab('evidence');
    }
    setContractWorkspaceOpen(operatorContractWorkspace === null);
  }, [
    operatorContractWorkspace,
    selectedNodeId,
    workspaceScenarioId,
  ]);

  const remediatePrimaryCommand = useCallback(() => {
    const remediation = authorTaskState.primaryCommand.remediation;
    if (!remediation) return;
    if (authorTaskState.primaryCommand.reasonCode === 'RG.AUTHOR.RUN.SCENARIO_STALE') {
      setAuthorMode('contract');
      setContractWorkspaceInitialTab('compatibility');
      setContractWorkspaceOpen(operatorContractWorkspace === null);
      return;
    }
    changeAuthorMode(remediation.mode);
  }, [authorTaskState.primaryCommand, changeAuthorMode, operatorContractWorkspace]);

  const updateWorkspaceCoordinate = useCallback((tab: WorkspaceTab, scenarioId: string) => {
    const nextMode = authorModeForWorkspaceTab(tab);
    const nextUrl = authorWorkspaceUrl(window.location.href, nextMode, selectedNodeId, {
      target: operatorContractWorkspace
        ? `operator:${operatorContractWorkspace.contract.target.id}`
        : 'graph',
      workspaceView: tab,
      scenarioId,
    });
    const currentUrl = `${window.location.pathname}${window.location.search}${window.location.hash}`;
    if (nextUrl !== currentUrl) {
      window.history.pushState(window.history.state, '', nextUrl);
    }
    setContractWorkspaceInitialTab(tab);
    setWorkspaceScenarioId(scenarioId);
    setAuthorMode(nextMode);
  }, [operatorContractWorkspace, selectedNodeId]);

  const recordScenarioEvidence = useCallback(async (
    scenarioId: string,
    comparison: ScenarioComparison,
    request: Parameters<typeof simulate>[0],
    proof: ScenarioCompilationProof,
    response: SimulationResponse,
  ): Promise<boolean> => {
    try {
      const scenario = activeTaskScenarioSet?.scenarios.find((candidate) => (
        candidate.scenarioId === scenarioId
      ));
      if (!activeTaskScenarioSet || !activeTaskContract || !scenario) {
        throw new Error(`RG.SCENARIO.NOT_FOUND: Scenario ${scenarioId} is not in the active authoring snapshot.`);
      }
      const expectedScenarioFingerprint = await sha256Fingerprint(captureScenarioEditorSnapshot(
        activeTaskScenarioSet,
        scenario.scenarioId,
        activeTaskContract,
        activeTaskScenarioNodes,
      ));
      const requestFingerprint = await sha256Fingerprint(request);
      const verification = verifyScenarioCompilationProof(
        proof,
        expectedScenarioFingerprint,
        requestFingerprint,
      );
      if (!verification.valid) throw new Error(`${verification.reasonCode}: ${verification.message}`);
      setLastScenarioReviewEvidence({
        scenarioId,
        comparison,
        response,
        coordinate: evidenceCoordinateForScenario(scenarioId, requestFingerprint, proof),
      });
      setEvidenceContentEpoch(authorContentEpoch);
      setWorkspaceScenarioId(scenarioId);
      return true;
    } catch (cause: unknown) {
      setError(`Evidence request fingerprint failed: ${String(cause)}`);
      return false;
    }
  }, [
    activeTaskContract,
    activeTaskScenarioNodes,
    activeTaskScenarioSet,
    authorContentEpoch,
    evidenceCoordinateForScenario,
  ]);

  const runFirstCanonicalScenario = useCallback(async () => {
    const selectedScenario = activeTaskScenarioSet?.scenarios.find(
      (scenario) => scenario.scenarioId === workspaceScenarioId,
    ) ?? activeTaskScenarioSet?.scenarios[0];
    if (!activeTaskContract || !activeTaskScenarioSet || !selectedScenario || !canonicalScenarioReady) {
      setContractWorkspaceInitialTab('scenarios');
      setContractWorkspaceOpen(true);
      return;
    }
    const snapshot = captureScenarioEditorSnapshot(
      activeTaskScenarioSet,
      selectedScenario.scenarioId,
      activeTaskContract,
      activeTaskScenarioNodes,
    );
    const compilation = await compileScenarioEditorSnapshotForSimulation(
      activeTaskGraphDraft,
      snapshot,
      activeTaskContract.target.fingerprint,
      activeTaskContractFingerprint,
    );
    if (!compilation.compiled || !compilation.request || !compilation.proof) {
      setError(compilation.diagnostics[0]?.message ?? 'Scenario cannot be compiled.');
      setContractWorkspaceInitialTab('scenarios');
      setContractWorkspaceOpen(true);
      return;
    }
    const requestFingerprint = await sha256Fingerprint(compilation.request);
    const verification = verifyScenarioCompilationProof(
      compilation.proof,
      scenarioFingerprintRef.current,
      requestFingerprint,
    );
    if (!verification.valid) {
      setError(`${verification.reasonCode}: ${verification.message}`);
      return;
    }
    const response = await runScenarioSimulation(compilation.request);
    const completionVerification = verifyScenarioCompilationProof(
      compilation.proof,
      scenarioFingerprintRef.current,
      requestFingerprint,
    );
    if (!completionVerification.valid) {
      setError(`${completionVerification.reasonCode}: ${completionVerification.message}`);
      setContractWorkspaceInitialTab('scenarios');
      setContractWorkspaceOpen(true);
      return;
    }
    const comparison = compareScenarioRun(selectedScenario, response);
    setLastScenarioReviewEvidence({
      scenarioId: selectedScenario.scenarioId,
      comparison,
      response,
      coordinate: evidenceCoordinateForScenario(
        selectedScenario.scenarioId,
        requestFingerprint,
        compilation.proof,
      ),
    });
    setEvidenceContentEpoch(authorContentEpoch);
    setWorkspaceScenarioId(selectedScenario.scenarioId);
    setContractWorkspaceInitialTab('evidence');
    setContractWorkspaceOpen(true);
  }, [
    activeTaskContract,
    activeTaskContractFingerprint,
    activeTaskGraphDraft,
    activeTaskScenarioNodes,
    activeTaskScenarioSet,
    authorContentEpoch,
    canonicalScenarioReady,
    evidenceCoordinateForScenario,
    runScenarioSimulation,
    workspaceScenarioId,
  ]);

  const runPrimaryAuthorAction = useCallback(() => {
    setAuthorMode(primaryAction.targetMode);
    if (primaryAction.kind === 'focus-palette') {
      setStartOpen(false);
      setStartSection('menu');
      const focusSearch = () => {
        searchInputRef.current?.focus();
        searchInputRef.current?.select();
      };
      if (typeof window.requestAnimationFrame === 'function') {
        window.requestAnimationFrame(focusSearch);
      } else {
        focusSearch();
      }
      return;
    }
    if (primaryAction.kind === 'fix-input') {
      if (isTaskWorkspace && hasContextError) {
        setSelectedNodeId('');
        setAuthorMode('compose');
        setInspectorCollapsed(false);
        return;
      }
      setContractWorkspaceInitialTab('scenarios');
      setContractWorkspaceOpen(true);
      return;
    }
    if (primaryAction.kind === 'run') {
      if (isTaskWorkspace) {
        void runFirstCanonicalScenario();
      } else if (simulationTableRows.length > 0) {
        void runSimulationTable();
      } else {
        void runSimulation();
      }
      return;
    }
    if (primaryAction.kind === 'review-failures') {
      setContractWorkspaceOpen(false);
      setOperatorContractWorkspace(null);
      setDiagnosticsOpen(true);
      return;
    }
    if (primaryAction.kind === 'review-result') {
      setTestSuiteOpen(false);
      setDiagnosticsOpen(false);
      setOperatorContractWorkspace(null);
      setContractWorkspaceInitialTab('evidence');
      setContractWorkspaceOpen(true);
    }
  }, [
    hasContextError,
    isTaskWorkspace,
    primaryAction,
    runFirstCanonicalScenario,
    runSimulation,
    runSimulationTable,
    simulationTableRows.length,
  ]);

  useEffect(() => {
    if (
      isTaskWorkspace
      && contractDraft
      && authorMode !== 'compose'
      && !contractWorkspaceOpen
      && !operatorContractWorkspace
      && !diagnosticsOpen
    ) {
      setContractWorkspaceInitialTab(workspaceTabForMode(authorMode));
      setContractWorkspaceOpen(true);
    }
  }, [
    authorMode,
    contractDraft,
    contractWorkspaceOpen,
    diagnosticsOpen,
    isTaskWorkspace,
    operatorContractWorkspace,
  ]);

  useEffect(() => {
    if (
      isTaskWorkspace
      && !matrixDiagnosticsSuppressed
      && diagnosticItems.some((item) => item.severity === 'BLOCKING' || item.severity === 'ERROR')
    ) {
      setDiagnosticsOpen(true);
    }
  }, [diagnosticItems, isTaskWorkspace, matrixDiagnosticsSuppressed]);

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
            data-dialog-initial-focus
            data-testid="test-table-run"
            onClick={runSimulationTable}
            disabled={
              tableTestingBusy
              || nodes.length === 0
              || simulationTableRows.length === 0
              || hasFixtureErrors
            }
          >
            {tableTestingBusy ? t('Running') : t('Run Table')}
          </button>
          <button
            type="button"
            className="secondary compact"
            data-testid="test-table-add"
            onClick={addSimulationTableRow}
          >
            {t('Add Case')}
          </button>
          <button
            type="button"
            className="secondary compact"
            data-testid="test-table-clear"
            onClick={clearSimulationTableResults}
            disabled={Object.keys(simulationTableResults).length === 0}
          >
            {t('Clear')}
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
                    aria-label={t('Test case name {index}', { index: index + 1 })}
                    data-testid={`test-table-name:${index}`}
                    value={row.name}
                    onChange={(event) => updateSimulationTableRow(row.id, { name: event.target.value })}
                  />
                  <span
                    className={`table-status ${rowStatus}`}
                    data-testid={`test-table-status:${index}`}
                  >
                    {d(rowStatus)}
                  </span>
                  <button
                    type="button"
                    className="secondary compact"
                    aria-label={t('Remove test case {index}', { index: index + 1 })}
                    data-testid={`test-table-remove:${index}`}
                    onClick={() => removeSimulationTableRow(row.id)}
                  >
                    {t('Remove')}
                  </button>
                </div>
                <label className="fixture-field">
                  <span>{t('Context')}</span>
                  <textarea
                    aria-label={t('Test case context {index}', { index: index + 1 })}
                    data-testid={`test-table-context:${index}`}
                    spellCheck={false}
                    value={row.contextText}
                    onChange={(event) =>
                      updateSimulationTableRow(row.id, { contextText: event.target.value })}
                  />
                </label>
                <label className="fixture-field">
                  <span>{t('Fixture Overrides')}</span>
                  <textarea
                    aria-label={t('Test case fixture overrides {index}', { index: index + 1 })}
                    data-testid={`test-table-fixtures:${index}`}
                    spellCheck={false}
                    value={row.fixturesText}
                    onChange={(event) =>
                      updateSimulationTableRow(row.id, { fixturesText: event.target.value })}
                  />
                </label>
                <label className="fixture-field">
                  <span>{t('Expected Output')}</span>
                  <textarea
                    aria-label={t('Test case expected output {index}', { index: index + 1 })}
                    data-testid={`test-table-expected:${index}`}
                    spellCheck={false}
                    value={row.expectedOutputText}
                    onChange={(event) =>
                      updateSimulationTableRow(row.id, { expectedOutputText: event.target.value })}
                  />
                </label>
                {(rowResult || rowError) && (
                  <div className="test-table-result">
                    <strong>{d(rowResult?.detail ?? rowError ?? '')}</strong>
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
        <p className="muted">{t('No test cases.')}</p>
      )}
    </section>
  );

  const authoritativeContract = authoritativeContractRef.current;
  const scenarioWorkspaceGraphDraft = authoritativeContract
    && authoritativeContract.executionSnapshot === canonicalExecutionGraphDraft(exportableDraft)
    ? authoritativeContract.graphDraft
    : exportableDraft;

  return (
    <div
      className={[
        'workspace',
        isTaskWorkspace ? 'workspace-v2' : '',
        paletteCollapsed ? 'palette-collapsed' : '',
        inspectorCollapsed ? 'inspector-collapsed' : '',
        diagnosticsOpen ? 'diagnostics-open' : '',
        canvasFocusMode ? 'canvas-focus' : '',
        compactWorkspace ? 'compact-workspace' : '',
        layoutPreview ? 'layout-preview-active' : '',
      ].filter(Boolean).join(' ')}
      style={isTaskWorkspace ? {
        '--author-palette-track': paletteCollapsed ? '36px' : `${paletteWidth}px`,
        '--author-inspector-track': inspectorCollapsed ? '36px' : `${inspectorWidth}px`,
      } as CSSProperties : undefined}
      data-layout-mode={canvasFocusMode ? 'focus' : 'standard'}
      data-canvas-zoom-tier={zoomPresentation.tier}
      data-focus-path={focusPathNodeId ? 'active' : 'inactive'}
      data-canvas-task-mode={canvasTaskMode}
      data-canvas-node-body={canvasSemanticZoom.nodeBody.toLowerCase()}
      data-canvas-minimum-title-px={canvasSemanticZoom.minimumEffectiveTitlePx}
      data-canvas-readability={canvasPerceptualQuality.status.toLowerCase()}
      data-canvas-effective-title-px={canvasPerceptualQuality.effectiveTitleFontPx}
      data-canvas-visible-field-labels={canvasPerceptualQuality.visibleFieldLabels}
      data-compact-workspace={compactWorkspace ? 'true' : 'false'}
      data-layout-preview={layoutPreview ? 'active' : layoutPlanning ? 'planning' : 'inactive'}
      data-canonical-scenario-ready={canonicalScenarioReady ? 'true' : 'false'}
      data-author-workspace-version={workspaceVersion}
      data-author-mode={authorMode}
      data-draft-lifecycle={authorReadiness.draft.toLowerCase()}
      data-workspace-continuity={authoringContinuity.state.lifecycle.toLowerCase()}
      data-evidence-freshness={evidenceStale ? 'stale' : 'current'}
      data-promotion-lifecycle={authorReadiness.promotion.toLowerCase()}
      data-task-canonical-state={authorTaskState.canonicalState.toLowerCase()}
      data-task-currentness={authorTaskState.currentness.toLowerCase()}
      data-task-proof-strength={authorTaskState.proofStrength.toLowerCase()}
      data-start-section={startOpen ? startSection : 'closed'}
      data-history-undo-depth={mutationJournal.past.length}
      data-history-redo-depth={mutationJournal.future.length}
      data-command-policy={mutationCommandPolicy.decision.toLowerCase()}
      data-task-environment={workspaceTaskCoordinate.environment}
      data-task-role={workspaceTaskCoordinate.role.toLowerCase()}
    >
      {isTaskWorkspace && (
        <>
          <AuthorCommandBar
            graphName={graphName}
            nodeCount={canvasSummary.nodeCount}
            edgeCount={canvasSummary.edgeCount}
            mode={authorMode}
            taskCoordinate={workspaceTaskCoordinate}
            commandPolicy={mutationCommandPolicy}
            primaryCommand={authorizedPrimaryCommand}
            draftStatus={taskDraftStatus}
            contractStatus={taskContractStatus}
            runStatus={authorizedRunCommand.state === 'READY'
              ? 'RUNNABLE'
              : authorizedRunCommand.state}
            evidenceStatus={authorTaskState.currentness.replace(/_/g, ' ')}
            proofStrength={authorTaskState.proofStrength}
            promotionStatus={taskPromotionStatus}
            promotionSummary={taskPromotionSummary}
            continuityStatus={authoringContinuity.state.lifecycle}
            recoveryCapturedAt={authoringContinuity.state.recoveryCapturedAt}
            recoverySecurity={authoringContinuity.recoverySecurity}
            exportUrl={draftExportUrl}
            exportName={`${graphName}-draft.json`}
            exportDisabled={
              nodes.length === 0
              || hasFixtureErrors
              || layoutPlanning
              || Boolean(layoutPreview)
            }
            layoutDisabled={nodes.length < 2 || layoutPlanning || Boolean(layoutPreview)}
            validationDisabled={validatingDraft || nodes.length === 0}
            saveDisabled={
              nodes.length === 0
              || hasFixtureErrors
              || authoringContinuity.state.lifecycle === 'SAVING'
              || busy
              || !mutationCommandPolicy.enabled
            }
            returnHref={returnTaskCoordinate ? taskReturnHref(returnTaskCoordinate) : ''}
            canUndo={mutationJournal.past.length > 0}
            canRedo={mutationJournal.future.length > 0}
            undoLabel={mutationJournal.past[mutationJournal.past.length - 1]?.label ?? ''}
            redoLabel={mutationJournal.future[mutationJournal.future.length - 1]?.label ?? ''}
            onModeChange={changeAuthorMode}
            onPrimaryAction={runPrimaryAuthorAction}
            onPrimaryRemediation={remediatePrimaryCommand}
            onImport={() => {
              setStartSection('menu');
              setStartOpen(true);
            }}
            onAutoLayout={autoLayout}
            onValidate={() => void runDraftValidation()}
            onSave={() => void authoringContinuity.save()}
            onUndo={undoAuthoringMutation}
            onRedo={redoAuthoringMutation}
          />
          <StartImportDialog
            open={startOpen}
            section={startSection}
            examples={canvasExamples.map(({
              template,
              missingOperatorRefs,
              incompatibleContractPaths,
            }) => ({
              key: template.key,
              label: template.label,
              domain: template.domain,
              description: template.description,
              pattern: template.pattern,
              nodeCount: template.nodes.length,
              edgeCount: template.edges.length,
              inputFieldCount: graphSchemaSummary(template.inputSchema).fieldCount,
              outputFieldCount: graphSchemaSummary(template.outputSchema).fieldCount,
              scenarioCount: template.testCases?.length ?? 0,
              caseTypes: Array.from(new Set(
                (template.testCases ?? []).map((testCase) => testCase.caseType),
              )),
              mockedOperatorCount: template.nodes.filter(
                (node) => hasOwnValue(node, 'fixtureOutput'),
              ).length,
              runtimeMode: 'Sandbox mock',
              proofStrength: 'Exploratory evidence',
              available: missingOperatorRefs.length === 0 && incompatibleContractPaths.length === 0,
              missingOperatorRefs,
              incompatibleContractPaths,
            }))}
            onSectionChange={(section) => {
              if (section !== 'menu') {
                recordAuthorTaskEvent('START_CHOICE_SELECTED', { choice: section });
              }
              setStartSection(section);
            }}
            onLoadExample={(key) => {
              const template = CANVAS_EXAMPLE_TEMPLATES.find((candidate) => candidate.key === key);
              if (template) {
                recordAuthorTaskEvent('EXAMPLE_LOADED', {
                  source: 'built-in',
                  nodeCount: template.nodes.length,
                  edgeCount: template.edges.length,
                  scenarioCount: template.testCases?.length ?? 0,
                });
                requestLoadCanvasExample(template);
              }
            }}
            onBlankGraph={() => {
              recordAuthorTaskEvent('START_CHOICE_SELECTED', { choice: 'blank' });
              setStartOpen(false);
              setStartSection('menu');
              setAuthorMode('compose');
              window.requestAnimationFrame(() => searchInputRef.current?.focus());
            }}
            onClose={() => {
              setStartOpen(false);
              setStartSection('menu');
            }}
          />
        </>
      )}
      {isTaskWorkspace && !paletteCollapsed && (
        <button
          type="button"
          className="author-panel-resizer palette-resizer"
          aria-label={t('Resize operator palette')}
          onPointerDown={(event) => beginPanelResize('palette', event)}
        />
      )}
      {isTaskWorkspace && !inspectorCollapsed && (
        <button
          type="button"
          className="author-panel-resizer inspector-resizer"
          aria-label={t('Resize context inspector')}
          onPointerDown={(event) => beginPanelResize('inspector', event)}
        />
      )}
      <aside className="palette" id="operator-palette">
        {isTaskWorkspace && (
          <button
            type="button"
            className="author-panel-toggle palette-panel-toggle"
            aria-label={t(paletteCollapsed ? 'Expand operator palette' : 'Collapse operator palette')}
            title={t(paletteCollapsed ? 'Expand operator palette' : 'Collapse operator palette')}
            aria-expanded={!paletteCollapsed}
            onClick={togglePalettePanel}
          >
            {paletteCollapsed
              ? <ChevronRight aria-hidden="true" size={18} />
              : <ChevronLeft aria-hidden="true" size={18} />}
          </button>
        )}
        <div className="palette-heading">
          <h2>{t('Operators')}</h2>
          <span>
            {paletteView.matchingCount}/{paletteView.totalCount}
          </span>
          {isTaskWorkspace && (
            <label className="author-panel-pin" title={t('Keep this panel open during canvas fitting')}>
              <input
                type="checkbox"
                checked={palettePreference === 'open'}
                onChange={(event) => {
                  setPalettePreference(event.target.checked ? 'open' : 'auto');
                  if (event.target.checked) setPaletteCollapsed(false);
                }}
              />
              <span>{t('Keep open')}</span>
            </label>
          )}
        </div>
        {spineEnabled && (
          <>
            {toolCoordinate && (
              <ToolAuthoringPanel
                draft={{
                  ...exportableDraft,
                  ...(graphDraftStatus ? { status: graphDraftStatus } : {}),
                }}
                coordinate={toolCoordinate}
                publication={toolPublication}
                onPublished={handleToolPublished}
                catalogError={toolCatalogError}
                onRefreshCatalog={refreshToolCatalog}
              />
            )}
            <ToolPaletteFacets
              operators={operators}
              onAddOperator={(operatorRef) => {
                const operator = operatorByRef.get(operatorRef);
                if (operator) addOperator(operator);
              }}
            />
            <ExternalApiAuthoring
              onCatalogRefresh={(catalog) => {
                setOperators(catalog.operators);
                setBuiltInFunctions(catalog.builtInFunctions ?? []);
              }}
              onAddOperator={(operatorRef) => {
                const operator = operatorByRef.get(operatorRef);
                if (operator) addOperator(operator);
              }}
            />
          </>
        )}
        <section className="library-intake" aria-label={t('Operator library intake')} data-testid="library-intake">
          <div className="library-intake-heading">
            <h2>{t('Library')}</h2>
            {libraryBusy && <span>{t('Working')}</span>}
          </div>
          <textarea
            aria-label={t('Operator library JSON or YAML')}
            data-testid="operator-library-source"
            spellCheck={false}
            placeholder={t('bloge.visualOperatorLibrary.v1 JSON/YAML, or bloge.capabilityCatalog.v1 for Adapt Catalog')}
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
          <div className="library-examples" aria-label={t('Operator library examples')}>
            <span>{t('Examples')}</span>
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
              {t('Adapt Catalog')}
            </button>
            <button
              type="button"
              className="secondary compact"
              data-testid="operator-library-validate"
              onClick={validateLibrarySource}
              disabled={libraryBusy}
            >
              {t('Validate')}
            </button>
            <button
              type="button"
              className="primary compact"
              data-testid="operator-library-import"
              onClick={() => requestDestructiveCommand(
                t('Import operator library'),
                graphName,
                () => void importLibrarySource(),
              )}
              disabled={libraryBusy || (libraryHasWarnings
                && (!libraryWarningsAcknowledged || !libraryWarningReason.trim()))}
            >
              {t('Import')}
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
                <span>{t('I reviewed the warning diagnostics')}</span>
              </label>
              <label>
                <span>{t('Audit reason')}</span>
                <input
                  type="text"
                  value={libraryWarningReason}
                  onChange={(event) => setLibraryWarningReason(event.target.value)}
                  placeholder={t('Why this DESIGN-only import is acceptable')}
                  data-testid="operator-library-warning-reason"
                />
              </label>
            </div>
          )}
          {libraryNotice && (
            <p className={`library-notice ${libraryNotice.level}`} data-testid="operator-library-notice">
              {d(libraryNotice.message)}
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
        <section className="library-intake dsl-import" aria-label={t('Legacy DSL import')} data-testid="legacy-dsl-import">
          <div className="library-intake-heading">
            <h2>{t('Legacy DSL')}</h2>
            {dslImportBusy && <span>{t('Rendering')}</span>}
            {dslCommitBusy && <span>{t('Saving')}</span>}
            {dslRewriteGateBusy && <span>{t('Checking')}</span>}
          </div>
          <label className="dsl-source-id">
            <span>{t('Source')}</span>
            <input
              aria-label={t('DSL source id')}
              data-testid="legacy-dsl-source-id"
              value={dslSourceId}
              onChange={(event) => {
                setDslSourceId(event.target.value);
                setDslRewriteGateResult(null);
              }}
            />
          </label>
          <textarea
            aria-label={t('BLOGE DSL source')}
            data-testid="legacy-dsl-source"
            spellCheck={false}
            placeholder={t('graph migratedFlow { ... }')}
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
          <div className="library-examples" aria-label={t('Legacy DSL examples')}>
            <span>{t('Examples')}</span>
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
              onClick={() => requestDestructiveCommand(
                t('Render DSL'),
                graphName,
                () => void previewLegacyDsl(),
              )}
              disabled={dslImportBusy || dslCommitBusy || dslRewriteGateBusy}
            >
              {t('Render DSL')}
            </button>
            <button
              type="button"
              className="secondary compact"
              data-testid="legacy-dsl-rewrite-gate"
              onClick={checkLegacyDslRewriteGate}
              disabled={dslImportBusy || dslCommitBusy || dslRewriteGateBusy}
            >
              {t('Check Rewrite')}
            </button>
            <button
              type="button"
              className="secondary compact"
              data-testid="legacy-dsl-commit"
              onClick={() => requestDestructiveCommand(
                t('Commit DSL draft'),
                graphName,
                () => void commitLegacyDsl(),
              )}
              disabled={dslImportBusy || dslCommitBusy || dslRewriteGateBusy}
            >
              {t('Commit Draft')}
            </button>
          </div>
          {dslImportNotice && (
            <p className={`library-notice ${dslImportNotice.level}`} data-testid="legacy-dsl-notice">
              {d(dslImportNotice.message)}
            </p>
          )}
          {dslImportCoverage && (
            <div className="dsl-import-stats" data-testid="legacy-dsl-coverage">
              <span>{t('{count} members', { count: dslImportCoverage.memberCount ?? 0 })}</span>
              <span>{t('{count} nodes', { count: dslImportCoverage.projectedNodeCount ?? 0 })}</span>
              <span>{t('{count} edges', { count: dslImportCoverage.edgeCount ?? 0 })}</span>
            </div>
          )}
          {dslImportRoundTrip && (
            <div
              className={`dsl-round-trip ${dslRoundTripNoticeLevel(dslImportRoundTrip)}`}
              data-testid="legacy-dsl-round-trip"
            >
              <div className="dsl-round-trip-heading">
                <strong>{t('Round trip')}</strong>
                <span>{d(dslImportRoundTrip.status || 'NOT_ASSESSED')}</span>
              </div>
              {dslImportRoundTrip.message && <p>{d(dslImportRoundTrip.message)}</p>}
              <div className="dsl-round-trip-evidence">
                <span>{dslImportRoundTrip.generatedDsl ? t('Generated DSL') : t('No generated DSL')}</span>
                {dslImportRoundTrip.sourceFingerprint && <span>{t('Source semantics')}</span>}
                {dslImportRoundTrip.generatedFingerprint && <span>{t('Generated semantics')}</span>}
                {(dslImportRoundTrip.diagnostics?.length ?? 0) > 0 && (
                  <span>{t('{count} diagnostics', { count: dslImportRoundTrip.diagnostics?.length ?? 0 })}</span>
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
                <strong>{t('Rewrite gate')}</strong>
                <span>{d(dslRewriteGateResult.decision || 'BLOCK_NOT_ASSESSED')}</span>
              </div>
              {dslRewriteGateResult.message && <p>{d(dslRewriteGateResult.message)}</p>}
              <div className="dsl-round-trip-evidence">
                <span>{dslRewriteGateResult.allowed ? t('Auto rewrite allowed') : t('Auto rewrite blocked')}</span>
                {dslRewriteGateResult.generatedDsl && <span>{t('Generated DSL ready')}</span>}
                {dslRewriteGateResult.roundTrip?.status && <span>{d(dslRewriteGateResult.roundTrip.status)}</span>}
              </div>
            </div>
          )}
          {dslImportSourceRows.length > 0 && (
            <div className="dsl-source-map" data-testid="legacy-dsl-source-map">
              <div className="dsl-source-map-heading">
                <strong>{t('Source map')}</strong>
                <span>{t('{count} refs', { count: dslImportSourceRows.length })}</span>
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
          aria-label={t('Search operators')}
          aria-keyshortcuts="Meta+K Control+K"
          placeholder={t('Search…')}
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />
        <div className="palette-facets" role="group" aria-label={t('Operator runtime facet')}>
          {paletteView.runtimeFacets.map((facet) => (
            <button
              key={facet.key}
              type="button"
              className={`palette-facet ${paletteFacet === facet.key ? 'active' : ''}`}
              aria-pressed={paletteFacet === facet.key}
              onClick={() => setPaletteFacet(facet.key)}
            >
              <span>{d(facet.label)}</span>
              <strong>{facet.count}</strong>
            </button>
          ))}
        </div>
        <div className="palette-selects">
          <select
            aria-label={t('Source kind filter')}
            value={sourceFilter}
            onChange={(event) => setSourceFilter(event.target.value)}
          >
            <option value="all">{t('Any source')}</option>
            {paletteView.sourceKindFacets.map((facet) => (
              <option key={facet.key} value={facet.key}>
                {facet.label} ({facet.count})
              </option>
            ))}
          </select>
          <select
            aria-label={t('Tag filter')}
            value={tagFilter}
            onChange={(event) => setTagFilter(event.target.value)}
            disabled={paletteView.tagFacets.length === 0}
          >
            <option value="all">{t('Any tag')}</option>
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
                      draggable={!layoutPlanning && !layoutPreview}
                      disabled={layoutPlanning || Boolean(layoutPreview)}
                      onDragStart={(event) => startOperatorDrag(event, operator)}
                      onClick={() => addOperator(operator)}
                      title={operator.operatorRef}
                    >
                      <span className="op-copy">
                        <span className={`op-kind ${summary.visualKind}`}>{d(summary.visualLabel)}</span>
                        <span className="op-name">{summary.name}</span>
                        <span className="op-ref">{summary.operatorRef}</span>
                        <span className="op-meta">
                          {d(summary.contractHint)} · {t('{required}/{inputs} inputs · {outputs} outputs', {
                            required: summary.requiredInputCount,
                            inputs: summary.inputCount,
                            outputs: summary.outputCount,
                          })}
                        </span>
                      </span>
                      <span className="operator-badges">
                        {summary.sideEffectBadgeLabel && (
                          <span
                            className={`badge side-effect ${summary.managedWrite ? 'managed' : 'unmanaged'}`}
                            title={summary.sideEffectNotice}
                          >
                            {d(summary.sideEffectBadgeLabel)}
                          </span>
                        )}
                        {summary.readinessBadgeLabel && (
                          <span className={`badge readiness ${summary.readinessLevel}`}>
                            {d(summary.readinessBadgeLabel)}
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
              {t(operators.length === 0 ? 'No operators. Is the server running?' : 'No matching operators.')}
            </p>
          )}
        </div>
      </aside>

      <main className="canvas">
        {isTaskWorkspace && (
          <AuthorSurfaceRouter
            mode={authorMode}
            targetKind={operatorContractWorkspace ? 'operator' : 'graph'}
            contextRailExpanded={!inspectorCollapsed}
            onOpenContextRail={() => {
              if (compactWorkspace && authorMode !== 'compose') {
                setFormalContextRailOpen(true);
              } else {
                setInspectorPreference('open');
              }
              setInspectorCollapsed(false);
            }}
          >
            <Suspense
              fallback={(
                <div className="author-surface-loading" role="status">
                  {t('Opening {mode} surface...', { mode: d(authorMode) })}
                </div>
              )}
            >
              {operatorContractWorkspace ? (
                <ContractScenarioWorkspace
                  open
                  presentation="surface"
                  initialTab={contractWorkspaceInitialTab}
                  initialScenarioId={workspaceScenarioId}
                  graphDraft={operatorContractWorkspace.graphDraft}
                  contract={operatorContractWorkspace.contract}
                  contractFingerprint={operatorContractWorkspace.contractFingerprint}
                  scenarioDraftSet={operatorContractWorkspace.scenarioDraftSet}
                  nodes={operatorContractWorkspace.nodes}
                  lastRun={activeScenarioEvidence?.response ?? null}
                  lastRunScenarioId={activeScenarioEvidence?.scenarioId}
                  lastComparison={activeScenarioEvidence?.comparison}
                  targetStored
                  contractEditable={false}
                  workspaceTransferEnabled={false}
                  onContractChange={() => undefined}
                  onImportWorkspace={async () => undefined}
                  onScenarioDraftSetChange={(next) => setOperatorContractWorkspace((current) => (
                    current ? { ...current, scenarioDraftSet: next } : current
                  ))}
                  onSaveGraphDraft={async () => undefined}
                  onRebase={() => setOperatorContractWorkspace((current) => (
                    current
                      ? {
                          ...current,
                          scenarioDraftSet: rebaseScenarioDraftSet(
                            current.scenarioDraftSet,
                            current.contract.target,
                            current.contractFingerprint,
                          ),
                        }
                      : current
                  ))}
                  onRun={runScenarioSimulation}
                  runCommand={authorizedRunCommand}
                  onRunRemediation={remediatePrimaryCommand}
                  onRunEvidence={recordScenarioEvidence}
                  onCoordinateChange={updateWorkspaceCoordinate}
                  trustContext={scenarioEvidenceTrustContext}
                  onSelectEvidenceDiagnostic={openAuthorDiagnostic}
                  onClose={() => setAuthorMode('compose')}
                />
              ) : contractDraft && scenarioDraftSet ? (
                <ContractScenarioWorkspace
                  open
                  presentation="surface"
                  initialTab={contractWorkspaceInitialTab}
                  initialScenarioId={workspaceScenarioId}
                  graphDraft={scenarioWorkspaceGraphDraft}
                  contract={contractDraft}
                  contractFingerprint={contractFingerprint}
                  scenarioDraftSet={scenarioDraftSet}
                  nodes={scenarioNodeOptions}
                  lastRun={activeScenarioEvidence?.response ?? null}
                  lastRunScenarioId={activeScenarioEvidence?.scenarioId}
                  lastComparison={activeScenarioEvidence?.comparison}
                  targetStored={exactSavedDraft}
                  onContractChange={updateContractSemantics}
                  onImportWorkspace={importScenarioWorkspace}
                  onScenarioDraftSetChange={setScenarioDraftSet}
                  onSaveGraphDraft={saveAuthoritativeGraph}
                  onRebase={rebaseScenariosToCurrentContract}
                  onRun={runScenarioSimulation}
                  runCommand={authorizedRunCommand}
                  onRunRemediation={remediatePrimaryCommand}
                  onRunEvidence={recordScenarioEvidence}
                  onCoordinateChange={updateWorkspaceCoordinate}
                  trustContext={scenarioEvidenceTrustContext}
                  onSelectEvidenceDiagnostic={openAuthorDiagnostic}
                  onClose={() => setAuthorMode('compose')}
                />
              ) : (
                <div className="author-surface-loading" role="status">
                  {t('Preparing the canonical Contract...')}
                </div>
              )}
            </Suspense>
          </AuthorSurfaceRouter>
        )}
        <div className="journey-bar" aria-label={t('Authoring workflow')}>
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
                <span>{d(step.label)}</span>
                <strong>{d(step.detail)}</strong>
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
              {d(journey.action.label)}
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
            {d(deepLinkNotice.message)}
          </div>
        )}
        {sourcePreviewReadOnly && initialBusinessMirrorSeed && (
          <section className="author-source-preview" data-testid="author-source-preview" role="status">
            <LockKeyhole aria-hidden="true" size={18} />
            <div>
              <strong>{t('Read-only source')}</strong>
              <span>{t('Inspect the exact source topology. Create a working copy before editing.')}</span>
              <code>
                {initialBusinessMirrorSeed.sourceId}@{initialBusinessMirrorSeed.sourceRevision}
              </code>
              {sourceCopyError && <small role="alert">{d(sourceCopyError)}</small>}
            </div>
            <button
              type="button"
              className="primary"
              disabled={sourceCopyBusy}
              onClick={() => void createBusinessMirrorWorkingCopy()}
            >
              <CopyPlus aria-hidden="true" size={16} />
              {t(sourceCopyBusy ? 'Creating working copy...' : 'Create working copy')}
            </button>
          </section>
        )}
        {deepLinkRun && (
          <section className={`run-context-strip ${deepLinkRun.success ? 'ok' : 'error'}`} data-testid="run-context-strip">
            <div className="context-strip-heading">
              <span>{t('Run')}</span>
              <strong>{deepLinkRun.runId}</strong>
            </div>
            <dl className="context-strip-facts">
              <div><dt>{t('Outcome')}</dt><dd>{t(deepLinkRun.success ? 'SUCCESS' : 'FAILED')}</dd></div>
              <div><dt>{t('Source')}</dt><dd>{d(deepLinkRun.sourceKind || 'UNKNOWN')}</dd></div>
              <div><dt>{t('Revision')}</dt><dd>{deepLinkRun.draftRevision ?? 0}</dd></div>
              <div><dt>{t('Elapsed')}</dt><dd>{deepLinkRun.elapsedMs ?? 0} {t('ms')}</dd></div>
            </dl>
            {deepLinkRun.errors?.[0] && <p>{deepLinkRun.errors[0]}</p>}
          </section>
        )}
        {(governanceGateBusy || governanceGateView) && (
          <section
            className={`governance-gate-strip ${governanceGateView ? governanceGateLevel(governanceGateView) : 'pending'}`}
            data-testid="governance-gate-strip"
            aria-label={t('Governance gate result')}
          >
            <div className="context-strip-heading">
              <span>{t('ANEKE Gate')}</span>
              <strong>{d(governanceGateBusy ? 'LOADING' : governanceGateView?.result?.status ?? 'NO RESULT')}</strong>
              {governanceGateView && <em>{d(governanceGateView.freshness)}</em>}
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
                        <span>{d(issue.severity)}</span>
                        <strong>{issue.code || issue.issueId}</strong>
                        <p>{d(issue.message)}</p>
                        {issue.recommendedAction && <small>{d(issue.recommendedAction)}</small>}
                        {issueNodeId && <em>{issueNodeId}</em>}
                      </button>
                    </li>
                  );
                })}
              </ul>
            ) : (
              !governanceGateBusy && <p className="governance-empty">{t('No governance decision for this draft revision.')}</p>
            )}
          </section>
        )}
        <section className="canvas-examples" aria-label={t('Built-in canvas examples')}>
          <div className="canvas-examples-heading">
            <span>{t('Examples')}</span>
            <strong>{canvasExamples.length}</strong>
          </div>
          <div className="canvas-example-list">
            {canvasExamples.map(({ template, missingOperatorRefs, incompatibleContractPaths }) => {
              const available = missingOperatorRefs.length === 0 && incompatibleContractPaths.length === 0;
              return (
                <article className={`canvas-example ${available ? '' : 'missing'}`} key={template.key}>
                  <div className="canvas-example-copy">
                    <span>{template.domain}</span>
                    <strong>{template.label}</strong>
                    <small>{template.pattern}</small>
                    <p>{template.description}</p>
                  </div>
                  <div className="canvas-example-meta">
                    <span>{t('{count} nodes', { count: template.nodes.length })}</span>
                    <span>{t('{count} edges', { count: template.edges.length })}</span>
                    <span>{t('Input {count} fields', { count: graphSchemaSummary(template.inputSchema).fieldCount })}</span>
                    <span>{t('Output {count} fields', { count: graphSchemaSummary(template.outputSchema).fieldCount })}</span>
                    {missingOperatorRefs.length > 0 && (
                      <span>{t('{count} missing', { count: missingOperatorRefs.length })}</span>
                    )}
                    {missingOperatorRefs.length === 0 && incompatibleContractPaths.length > 0 && (
                      <span title={incompatibleContractPaths.join(', ')}>
                        {t('Contract changed')}: {incompatibleContractPaths[0]}
                      </span>
                    )}
                  </div>
                  <button
                    type="button"
                    className="secondary compact"
                    data-testid={`canvas-example-load:${template.key}`}
                    onClick={() => requestLoadCanvasExample(template)}
                    disabled={!available}
                    title={available
                      ? `${t('Load example')}: ${d(template.label)}`
                      : missingOperatorRefs.length > 0
                        ? t('Missing {operators}', { operators: missingOperatorRefs.join(', ') })
                        : t('Current Contracts do not expose {paths}', {
                          paths: incompatibleContractPaths.join(', '),
                        })}
                  >
                    {t('Load')}
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
            disabled={busy || nodes.length === 0 || hasFixtureErrors || hasContextError || selectedGovernedFixtureStale}
            title={
              hasFixtureErrors
                ? 'Fix fixture JSON before simulating.'
                : hasContextError
                  ? 'Fix runtime context before simulating.'
                  : selectedGovernedFixtureStale
                    ? t('The governed fixture schema is stale; recapture before simulating.')
                  : undefined
            }
          >
            {busy ? t('Simulating…') : t('Simulate')}
          </button>
          <button className="secondary" onClick={autoLayout} disabled={nodes.length < 2}>
            {t('Auto Layout')}
          </button>
          <button
            className="secondary"
            data-testid="canvas-focus-toggle"
            aria-pressed={canvasFocusMode}
            onClick={() => setCanvasFocusMode((current) => !current)}
          >
            {canvasFocusMode ? t('Exit Focus') : t('Canvas Focus')}
          </button>
          <div className="zoom-toolbar" aria-label={t('Canvas zoom controls')}>
            <button
              type="button"
              className="secondary compact icon-button"
              aria-label={t('Zoom out')}
              data-testid="author-zoom-out"
              onClick={() => zoomCanvasBy('out')}
              disabled={nodes.length === 0}
            >
              <Minus size={14} aria-hidden="true" />
            </button>
            <button
              type="button"
              className="secondary compact zoom-level"
              aria-label={t('Reset zoom')}
              data-testid="author-zoom-reset"
              onClick={resetCanvasZoom}
              disabled={nodes.length === 0}
            >
              {viewportZoomPercent}
            </button>
            <button
              type="button"
              className="secondary compact icon-button"
              aria-label={t('Zoom in')}
              data-testid="author-zoom-in"
              onClick={() => zoomCanvasBy('in')}
              disabled={nodes.length === 0}
            >
              <Plus size={14} aria-hidden="true" />
            </button>
            <button
              type="button"
              className="secondary compact"
              data-testid="author-fit-all"
              onClick={() => fitCanvasToView()}
              disabled={nodes.length === 0}
            >
              {t('Fit All')}
            </button>
            <button
              type="button"
              className="secondary compact"
              data-testid="author-overview-toggle"
              aria-pressed={overviewVisible}
              onClick={() => setOverviewVisible((current) => !current)}
              disabled={nodes.length === 0}
            >
              {overviewVisible ? t('Map On') : t('Map Off')}
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
            {t('Export Draft')}
          </a>
          {result && (
            <span className={isRunSuccessful(result) ? 'status ok' : 'status fail'}>
              {isRunSuccessful(result) ? t('Success') : t('Blocked')}
            </span>
          )}
          <span className="canvas-chip">{t('{count} nodes', { count: canvasSummary.nodeCount })}</span>
          <span className="canvas-chip">{t('{count} edges', { count: canvasSummary.edgeCount })}</span>
          <span className="canvas-chip">{t('Output')} {canvasSummary.outputNodeId || t('missing')}</span>
          {fixtureCount > 0 && (
            <span className="canvas-chip">
              {t('{count} fixtures', { count: fixtureCount })}
            </span>
          )}
          {mockAttentionCount > 0 && (
            <span className="canvas-chip">{t('Mock setup {count}', { count: mockAttentionCount })}</span>
          )}
          {hasFixtureErrors && (
            <span className="connection-notice error">
              {t('{count} fixture JSON errors', { count: fixtureErrorCount })}
            </span>
          )}
          {connectionNotice && (
            <span className={`connection-notice ${connectionNotice.level}`}>
              {checkingConnection ? 'Checking...' : loadingCandidates ? 'Discovering...' : connectionNotice.message}
            </span>
          )}
          <span className="legend">
            <span className="swatch mocked" /> {t('mocked')}
            <span className="swatch real" /> {t('real')}
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
          onOpen={() => {
            setOperatorContractWorkspace(null);
            setContractWorkspaceInitialTab('interface');
            setContractWorkspaceOpen(true);
          }}
        />
        <div
          ref={flowRef}
          className="flow"
          data-testid="author-flow"
          onDragOver={allowOperatorDrop}
          onDrop={dropOperatorOnFlow}
        >
          {isTaskWorkspace && nodes.length > 0 && (
            <CanvasTaskNavigator
              mode={canvasTaskMode}
              nodes={canvasTaskNodes}
              selectedNodeId={selectedNodeId}
              nodeCount={canvasSummary.nodeCount}
              edgeCount={canvasSummary.edgeCount}
              pathNodeCount={focusedCanvasPath.nodeIds.size}
              zoomPercent={viewportZoomPercent}
              mapVisible={overviewVisible}
              canvasExpanded={canvasFocusMode}
              layoutPlanning={layoutPlanning}
              layoutPreview={Boolean(layoutPreview)}
              layoutQuality={layoutPreview?.quality ?? null}
              layoutAcceptance={layoutPreview?.acceptance ?? null}
              perceptualQuality={canvasPerceptualQuality}
              topologyLanes={canvasSemantics.lanes}
              layoutNotice={layoutNotice ?? adaptiveChromeNotice}
              canUndoLayout={Boolean(layoutUndo)}
              onModeChange={activateCanvasTaskMode}
              onSelectNode={focusNodeFromNavigator}
              onToggleCanvasExpanded={toggleCanvasExpanded}
              onZoomIn={() => zoomCanvasBy('in')}
              onZoomOut={() => zoomCanvasBy('out')}
              onFitAll={() => fitCanvasToView()}
              onToggleMap={() => setOverviewVisible((current) => !current)}
              onTogglePin={toggleSelectedNodePin}
              onApplyLayout={applyLayoutPreview}
              onOverrideLayout={overrideLayoutPreview}
              onCancelLayout={cancelLayoutPreview}
              onUndoLayout={undoAutoLayout}
            />
          )}
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
          {!isTaskWorkspace && nodes.length > 0 && (
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
                <span>{t('{count} nodes', { count: canvasSummary.nodeCount })}</span>
                <span>{t('{count} edges', { count: canvasSummary.edgeCount })}</span>
                {focusPathNodeId && <span>{t('{count} in path', { count: focusedCanvasPath.nodeIds.size })}</span>}
                {layoutNotice && (
                  <span data-testid="layout-notice" role="status" aria-live="polite">
                    {m(layoutNotice.messageId, layoutNotice.params)}
                  </span>
                )}
              </div>
              <div className="canvas-navigator-actions">
                <button
                  type="button"
                  className="secondary compact"
                  data-testid="navigator-fit-all"
                  onClick={() => fitCanvasToView()}
                >
                  {t('Fit All')}
                </button>
                <button
                  type="button"
                  className="secondary compact"
                  data-testid="navigator-focus-path"
                  aria-pressed={Boolean(focusPathNodeId)}
                  onClick={toggleFocusPath}
                  disabled={!selectedNodeId && !focusPathNodeId}
                  title={focusPathNodeId
                    ? 'Restore the complete graph'
                    : 'Emphasize this node, its predecessors, and its successors'}
                >
                  {focusPathNodeId ? 'Show All' : 'Focus Path'}
                </button>
                {layoutUndo && (
                  <button
                    type="button"
                    className="secondary compact"
                    data-testid="navigator-undo-layout"
                    onClick={undoAutoLayout}
                    title={t('Restore positions from before the last Auto Layout')}
                  >
                    {t('Undo layout')}
                  </button>
                )}
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
          {isTaskWorkspace && (
            <div className="compact-canvas-launchers" aria-label={t('Compact workspace panels')}>
              <button
                type="button"
                data-testid="compact-open-palette"
                aria-pressed={!paletteCollapsed}
                onClick={() => {
                  setInspectorCollapsed(true);
                  setInspectorPreference('closed');
                  setPaletteCollapsed(false);
                  setPalettePreference('open');
                }}
              >
                {t('Operators')}
              </button>
              <button
                type="button"
                data-testid="compact-open-inspector"
                aria-pressed={!inspectorCollapsed}
                disabled={!selectedNodeId}
                onClick={() => {
                  setPaletteCollapsed(true);
                  setPalettePreference('closed');
                  setInspectorCollapsed(false);
                  setInspectorPreference('open');
                }}
              >
                {t('Inspect')}
              </button>
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
            nodesDraggable={!sourcePreviewReadOnly && !layoutPlanning && !layoutPreview}
            nodesConnectable={!sourcePreviewReadOnly && !layoutPlanning && !layoutPreview}
            deleteKeyCode={null}
            elementsSelectable={!layoutPlanning && !layoutPreview}
            onInit={(instance) => {
              flowInstanceRef.current = instance;
              refreshViewportZoom();
            }}
            onMove={(_, viewport) => setViewportZoom(viewport.zoom)}
            onNodeClick={(_, node) => {
              setSelectedNodeId(node.id);
              if (focusPathNodeId && focusPathNodeId !== node.id) {
                setFocusPathNodeId('');
              }
            }}
            onNodeDoubleClick={(_, node) => {
              if (!sourcePreviewReadOnly) openNodeEditor(node);
            }}
            onPaneClick={() => {
              setSelectedNodeId('');
              setFocusPathNodeId('');
            }}
            fitView
            fitViewOptions={{ padding: 0.08, minZoom: canvasSemanticZoom.minimumZoom, maxZoom: 1 }}
            minZoom={canvasSemanticZoom.minimumZoom}
            maxZoom={CANVAS_MAX_ZOOM}
          >
            <Background />
            {!isTaskWorkspace && <Controls />}
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
                ariaLabel={t('Canvas overview map')}
              />
            )}
          </ReactFlow>
        </div>
      </main>

      <aside className="inspector">
        {isTaskWorkspace && (
          <>
            <button
              type="button"
              className="author-panel-toggle inspector-panel-toggle"
              aria-label={t(inspectorCollapsed ? 'Expand context inspector' : 'Collapse context inspector')}
              title={t(inspectorCollapsed ? 'Expand context inspector' : 'Collapse context inspector')}
              aria-expanded={!inspectorCollapsed}
              onClick={compactWorkspace && authorMode !== 'compose'
                ? () => {
                    setFormalContextRailOpen((open) => {
                      setInspectorCollapsed(open);
                      return !open;
                    });
                  }
                : toggleInspectorPanel}
            >
              {inspectorCollapsed
                ? <ChevronLeft aria-hidden="true" size={18} />
                : <ChevronRight aria-hidden="true" size={18} />}
            </button>
            <label
              className="author-panel-pin inspector-panel-pin"
              title={t('Keep this panel open during canvas fitting')}
            >
              <input
                type="checkbox"
                checked={inspectorPreference === 'open'}
                onChange={(event) => {
                  setInspectorPreference(event.target.checked ? 'open' : 'auto');
                  if (event.target.checked) setInspectorCollapsed(false);
                }}
              />
              <span>{t('Keep open')}</span>
            </label>
          </>
        )}
        {isTaskWorkspace && authorMode === 'compose' && (
          <AuthorContextInspector
            mode={authorMode}
            selectedNode={selectedNode ? {
              id: selectedNode.id,
              label: selectedNode.data.label,
              operatorRef: selectedNode.data.operatorRef,
              visualLabel: selectedNode.data.summary.visualLabel,
              readiness: selectedNode.data.summary.readinessBadgeLabel
                || selectedNode.data.summary.readinessState
                || 'Unknown',
              inputCount: selectedNode.data.summary.inputCount,
              outputCount: selectedNode.data.summary.outputCount,
            } : null}
            graphName={graphName}
            inputFieldCount={graphInputSummary.fieldCount}
            outputFieldCount={graphOutputSummary.fieldCount}
            executionStatus={executionStatus}
            assertionStatus={assertionStatus}
            contractStatus={contractStatus}
            governanceStatus={governanceStatus}
            resultMessage={resultMessage}
            runProvenance={runProvenance}
            dataContent={(
              <div className="author-inspector-data">
                {selectedNode && selectedEffectiveContract && (
                  <>
                    <EffectiveContractPanel
                      projection={selectedEffectiveContract}
                      compact
                      onTraceBinding={traceEffectiveBinding}
                      onTraceField={traceEffectiveField}
                      onAcceptInference={
                        selectedInferenceAcceptable ? acceptSelectedInference : undefined
                      }
                      acceptInferenceLabel="Accept as Graph Output Contract"
                    />
                    <details className="direct-binding-tools">
                      <summary>{t('Edit direct bindings')}</summary>
                      <NodeInputBindingsEditor
                        node={selectedNode}
                        incomingEdges={edges}
                        graphNodes={nodes}
                        onAdd={addSelectedInputBinding}
                        onRemove={removeSelectedInputBinding}
                        onRename={renameSelectedInputBinding}
                        onChange={updateSelectedInputBinding}
                        onKindChange={updateSelectedInputBindingKind}
                        onDropContextPath={bindContextVariableToSelectedNode}
                      />
                    </details>
                  </>
                )}
                <GraphRunInputPanel
                  inputSchema={graphInputSchema}
                  value={runInputValue}
                  assessmentValue={contextCompilation.value}
                  readOnly={rawContextMode}
                  selectedNodeLabel={selectedNode?.data.label ?? ''}
                  onChange={updateRunInputValue}
                  onBind={bindContextVariableToSelectedNode}
                  onOpenContract={() => {
                    setAuthorMode('contract');
                    setOperatorContractWorkspace(null);
                    setContractWorkspaceInitialTab('interface');
                    setContractWorkspaceOpen(true);
                  }}
                />
              </div>
            )}
            advancedContent={(
              <div className="author-inspector-advanced">
                <ContextExtrasPanel
                  rows={contextVariables}
                  compilation={variableContextCompilation}
                  selectedNodeLabel={selectedNode?.data.label ?? ''}
                  onAdd={addContextVariable}
                  onUpdate={updateContextVariable}
                  onRemove={removeContextVariable}
                  onBind={bindContextVariableToSelectedNode}
                />
                <RawRunContextPanel
                  rawMode={rawContextMode}
                  rawText={simulationContextDraft}
                  effectiveValue={contextCompilation.value}
                  error={contextCompilation.error}
                  onRawModeChange={updateRawContextMode}
                  onRawTextChange={updateSimulationContextDraft}
                />
              </div>
            )}
            onEditNode={() => {
              if (selectedNode) {
                openNodeEditor(selectedNode);
              }
            }}
            onOpenNodeContract={() => {
              if (selectedOperator) {
                void openOperatorContractWorkspace(selectedOperator, 'interface', selectedNodeId);
              }
            }}
            onOpenScenarios={() => {
              if (selectedOperator) {
                void openOperatorContractWorkspace(selectedOperator, 'scenarios', selectedNodeId);
              } else {
                setAuthorMode('scenarios');
                setOperatorContractWorkspace(null);
                setContractWorkspaceInitialTab('scenarios');
                setContractWorkspaceOpen(true);
              }
            }}
            onOpenGraphContract={() => {
              setAuthorMode('contract');
              setOperatorContractWorkspace(null);
              setContractWorkspaceInitialTab('interface');
              setContractWorkspaceOpen(true);
            }}
          />
        )}
        {isTaskWorkspace && authorMode !== 'compose' && (
          <TopologyContextRail
            mode={authorMode}
            graphName={graphName}
            nodes={nodes.map((node) => ({
              id: node.id,
              label: node.data.label,
              operatorRef: node.data.operatorRef,
            }))}
            edges={edges.map((edge) => ({ source: edge.source, target: edge.target }))}
            selectedNodeId={selectedNodeId}
            scenarioId={workspaceScenarioId}
            runId={deepLinkRun?.runId ?? ''}
            onSelectNode={(nodeId) => setSelectedNodeId(nodeId)}
            onRevealInCompose={() => {
              setAuthorMode('compose');
              setContractWorkspaceOpen(false);
              setOperatorContractWorkspace(null);
              setInspectorCollapsed(false);
              setFormalContextRailOpen(false);
            }}
          />
        )}
        <h2>{t('Checklist')}</h2>
        <ol className="checklist">
          {checklist.map((item) => (
            <li key={item.key} className={item.state}>
              <span>{d(item.label)}</span>
              <strong>{d(item.detail)}</strong>
            </li>
          ))}
        </ol>

        <h2>{t('Mock Setup')}</h2>
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
                    <small>{d(row.detail)}</small>
                  </span>
                  <span className="mock-setup-status">
                    <span className={`run-pill ${row.runMode}`}>{d(row.runMode)}</span>
                    <code>{row.fixtureLabel}</code>
                  </span>
                </button>
              </li>
            ))}
          </ol>
        ) : (
          <p className="muted">{t('No nodes.')}</p>
        )}

        <h2>{t('Test Suite')}</h2>
        <section
          className={`test-suite-summary ${simulationTableRunSummary.state}`}
          data-testid="test-suite-summary"
        >
          <div>
            <span>{t('{count} cases', { count: simulationTableRows.length })}</span>
            <strong>{d(simulationTableRunSummary.label)}</strong>
            <small>{d(simulationTableRunSummary.detail)}</small>
          </div>
          <button
            type="button"
            className="primary compact"
            data-testid="test-suite-open"
            onClick={() => setTestSuiteOpen(true)}
          >
            {t('Test Suite')}
          </button>
        </section>

        <h2>{t('Runtime Context')}</h2>
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

        <h2>{t('Selected Node')}</h2>
        {selectedNode ? (
          <section className="node-detail">
            <h3>{selectedNode.data.label}</h3>
            <p className="muted">{selectedNode.data.operatorRef}</p>
            {selectedNode.data.summary.description && (
              <p>{selectedNode.data.summary.description}</p>
            )}
            <OperatorFocusPanel operator={selectedOperator} summary={selectedNode.data.summary} />
            <div className="port-list">
              <strong>{t('Inputs')}</strong>
              <span>{selectedNode.data.summary.inputNames.join(', ') || 'none'}</span>
            </div>
            <div className="port-list">
              <strong>{t('Outputs')}</strong>
              <span>{selectedNode.data.summary.outputNames.join(', ') || 'none'}</span>
            </div>
            <NodeInputBindingsEditor
              node={selectedNode}
              incomingEdges={edges}
              graphNodes={nodes}
              onAdd={addSelectedInputBinding}
              onRemove={removeSelectedInputBinding}
              onRename={renameSelectedInputBinding}
              onChange={updateSelectedInputBinding}
              onKindChange={updateSelectedInputBindingKind}
              onDropContextPath={bindContextVariableToSelectedNode}
            />
            <div className="connection-guide" data-testid="connection-guide">
              <div className="connection-guide-header">
                <strong>{t('Connect Next')}</strong>
                <button
                  type="button"
                  className="secondary compact"
                  data-testid="connection-guide-refresh"
                  onClick={loadSelectedConnectionGuide}
                  disabled={connectionGuideBusy || nodes.length < 2}
                >
                  {connectionGuideBusy ? t('Finding') : t('Find Targets')}
                </button>
              </div>
              <label className="connection-source">
                <span>{t('Source')}</span>
                <select
                  aria-label={t('Connection source output')}
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
                  {d(connectionGuideNotice.message)}
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
                            <small className="connection-guide-detail">{d(row.detail)}</small>
                            <small className="connection-guide-action">{d(row.actionHint)}</small>
                          </span>
                          <em>{d(row.status)}</em>
                        </button>
                        {row.fieldOptions.length > 1 && (
                          <div className="connection-guide-fields" aria-label={t('Compatible field paths')}>
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
                        {t('Connect')}
                      </button>
                    </li>
                  ))}
                </ol>
              ) : (
                <p className="muted">{t('No targets loaded.')}</p>
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
                {t('Set Output')}
              </button>
            </div>
            <div className="fixture-editor">
              <div className="fixture-header">
                <strong>{t('Simulation')}</strong>
                <span className={`badge ${selectedFixtureHasDraft ? 'fixture' : ''}`}>
                  {selectedFixtureHasDraft ? t('custom') : t('server sample')}
                </span>
              </div>
              <div className="fixture-actions">
                <button
                  className="secondary compact"
                  onClick={useSelectedFixtureSample}
                  disabled={!selectedOperator}
                >
                  {t('Use Sample')}
                </button>
                <button
                  className="secondary compact"
                  onClick={clearSelectedFixture}
                  disabled={!selectedFixtureHasDraft}
                >
                  {t('Clear')}
                </button>
              </div>
              <label className="fixture-field">
                <span>{t('Output Pin')}</span>
                <textarea
                  aria-label={t('Simulation output fixture JSON')}
                  spellCheck={false}
                  placeholder={t('null')}
                  value={selectedFixtureDraft}
                  onChange={(event) => updateSelectedFixtureDraft(event.target.value)}
                />
              </label>
              <label className="fixture-field">
                <span>{t('Expected Input')}</span>
                <textarea
                  aria-label={t('Simulation expected input JSON')}
                  spellCheck={false}
                  placeholder="{}"
                  value={selectedExpectedInputDraft}
                  onChange={(event) => updateSelectedExpectedInputDraft(event.target.value)}
                />
              </label>
              {selectedFixtureError && <p className="fixture-error">{d(selectedFixtureError)}</p>}
              {spineEnabled && (
                <>
                  <Suspense fallback={<p className="muted" role="status">{t('Loading fixture controls…')}</p>}>
                    <SimulationFixtureControls
                      draftId={graphDraftId}
                      nodeId={selectedNode.id}
                      label={selectedNode.data.label}
                      operatorRef={selectedNode.data.operatorRef}
                      output={result && Object.prototype.hasOwnProperty.call(result.results, selectedNode.id)
                        ? result.results[selectedNode.id] : undefined}
                      fixture={selectedFixtureState}
                      onPin={() => pinSimulationNode(selectedNode.id)}
                      promoter={promoteGraphNodeFixture}
                      onGoverned={acceptGovernedFixture}
                      testIdPrefix="inspector"
                    />
                  </Suspense>
                  {selectedIsResource && (
                    <div className="fixture-asset-reuse" data-testid="fixture-asset-reuse">
                      {governedFixtureAssetsError && (
                        <p className="fixture-error" role="status">{d(governedFixtureAssetsError)}</p>
                      )}
                      <p className="muted" data-testid="fixture-reuse-unavailable">
                        {governedFixtureAssets.length > 0
                          ? t('ACTIVE governed fixture metadata is visible, but reuse is unavailable until simulate accepts a material reference.')
                          : t('Governed fixture reuse is unavailable in this deployment.')}
                      </p>
                      <p className="muted" data-testid="fixture-fidelity-unavailable">
                        {t('Resource fidelity selection is unavailable until simulate accepts a fidelity field.')}
                      </p>
                    </div>
                  )}
                </>
              )}
            </div>
          </section>
        ) : (
          <p className="muted">{t('No node selected.')}</p>
        )}

        <h2>{t('Result')}</h2>
        {validationResult ? (
          <section
            className={`validation-summary ${validationResult.valid ? 'ok' : 'fail'}`}
            data-testid="draft-validation-summary"
          >
            <div className="validation-summary-heading">
              <span>{validationResult.valid ? t('Validated') : t('Needs repair')}</span>
              <strong>{d(validationResult.readiness?.title || (validationResult.valid ? 'Draft valid' : 'Draft invalid'))}</strong>
            </div>
            {validationResult.readiness?.summary && (
              <p>{d(validationResult.readiness.summary)}</p>
            )}
            <div className="validation-summary-chips">
              <span data-testid="draft-validation-summary:state">
                <span>{t('Readiness')}</span>
                <strong>{d(validationResult.readiness?.state || 'unknown')}</strong>
              </span>
              <span data-testid="draft-validation-summary:actions">
                <span>{t('Actions')}</span>
                <strong>{d(validationResult.actionReadiness?.state || 'unknown')}</strong>
              </span>
              <span data-testid="draft-validation-summary:diagnostics">
                <span>{t('Diagnostics')}</span>
                <strong>{validationResult.diagnostics?.length ?? 0}</strong>
              </span>
            </div>
          </section>
        ) : (
          <p className="muted" data-testid="draft-validation-summary">{t('Not validated.')}</p>
        )}
        <section className={`run-summary ${runSummary.state}`} data-testid="simulation-run-summary">
          <div className="run-summary-heading">
            <span>{d(runSummary.detail)}</span>
            <strong>{d(runSummary.title)}</strong>
          </div>
          <div className="run-summary-chips">
            {runSummary.chips.map((chip) => (
              <span
                key={chip.key}
                className={`run-summary-chip ${chip.state}`}
                data-testid={`simulation-run-summary:${chip.key}`}
              >
                <span>{d(chip.label)}</span>
                <strong>{d(chip.value)}</strong>
              </span>
            ))}
          </div>
        </section>
        {error && <pre className="error">{error}</pre>}
        {!result && !error && <p className="muted">{t('No simulation result.')}</p>}
        {result && (
          <>
            <p>
              <strong>{t('Mocked:')}</strong> {result.mockedNodeIds.join(', ') || '-'}
            </p>
            <p>
              <strong>{t('Real:')}</strong> {result.realNodeIds.join(', ') || '-'}
            </p>
            {traceRows.length > 0 && (
              <>
                <h3>{t('Trace')}</h3>
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
                          {row.fidelity && (
                            <small data-testid={`server-fidelity:${row.nodeId}`}>
                              {t('Server fidelity')}: {row.fidelity}
                            </small>
                          )}
                          <code>{row.outputPreview}</code>
                        </span>
                        <span className={`run-pill ${row.status}`}>{d(row.status)}</span>
                      </button>
                      {spineEnabled && (
                        <Suspense fallback={null}>
                          <SimulationFixtureControls
                            draftId={graphDraftId}
                            nodeId={row.nodeId}
                            label={row.label}
                            operatorRef={row.operatorRef}
                            output={Object.prototype.hasOwnProperty.call(result.results, row.nodeId)
                              ? result.results[row.nodeId] : undefined}
                            fixture={fixtureForNode(row.nodeId)}
                            onPin={() => pinSimulationNode(row.nodeId)}
                            promoter={promoteGraphNodeFixture}
                            onGoverned={acceptGovernedFixture}
                            testIdPrefix="trace"
                          />
                        </Suspense>
                      )}
                    </li>
                  ))}
                </ol>
              </>
            )}
            <h3>{t('Output')}</h3>
            <pre>{JSON.stringify(result.output, null, 2)}</pre>
            {result.diagnostics.length > 0 && (
              <>
                <h3>{t('Diagnostics')}</h3>
                <ul>
                  {result.diagnostics.map((diagnostic, index) => (
                    <li key={index} className={`diag ${diagnostic.level ?? ''}`}>
                      {diagnostic.code}: {d(diagnostic.message ?? '')}
                    </li>
                  ))}
                </ul>
              </>
            )}
            <h3>{t('Generated DSL')}</h3>
            <pre className="dsl">{result.generatedDsl}</pre>
          </>
        )}
      </aside>
      {isTaskWorkspace && (
        <AuthorDiagnosticsDrawer
          open={diagnosticsOpen}
          items={diagnosticItems}
          onToggle={() => setDiagnosticsOpen((current) => !current)}
          onSelect={(item: AuthorDiagnosticItem) => openAuthorDiagnostic(item)}
        />
      )}
      {!isTaskWorkspace && operatorContractWorkspace && (
        <Suspense
          fallback={(
            <div className="canvas-loading-state" role="status">
              {t('Opening Operator Contract workspace...')}
            </div>
          )}
        >
          <ContractScenarioWorkspace
            open
            initialTab={contractWorkspaceInitialTab}
            initialScenarioId={workspaceScenarioId}
            graphDraft={operatorContractWorkspace.graphDraft}
            contract={operatorContractWorkspace.contract}
            contractFingerprint={operatorContractWorkspace.contractFingerprint}
            scenarioDraftSet={operatorContractWorkspace.scenarioDraftSet}
            nodes={operatorContractWorkspace.nodes}
            lastRun={activeScenarioEvidence?.response ?? null}
            lastRunScenarioId={activeScenarioEvidence?.scenarioId}
            lastComparison={activeScenarioEvidence?.comparison}
            targetStored
            contractEditable={false}
            workspaceTransferEnabled={false}
            onContractChange={() => undefined}
            onImportWorkspace={async () => undefined}
            onScenarioDraftSetChange={(next) => setOperatorContractWorkspace((current) => (
              current ? { ...current, scenarioDraftSet: next } : current
            ))}
            onSaveGraphDraft={async () => undefined}
            onRebase={() => setOperatorContractWorkspace((current) => (
              current
                ? {
                    ...current,
                    scenarioDraftSet: rebaseScenarioDraftSet(
                      current.scenarioDraftSet,
                      current.contract.target,
                      current.contractFingerprint,
                    ),
                  }
                : current
            ))}
            onRun={runScenarioSimulation}
            onRunEvidence={recordScenarioEvidence}
            onCoordinateChange={updateWorkspaceCoordinate}
            trustContext={scenarioEvidenceTrustContext}
            onSelectEvidenceDiagnostic={openAuthorDiagnostic}
            onClose={() => {
              setOperatorContractWorkspace(null);
              if (isTaskWorkspace) {
                setAuthorMode('compose');
              }
            }}
          />
        </Suspense>
      )}
      {!isTaskWorkspace && contractWorkspaceOpen && !operatorContractWorkspace && (
        <Suspense
          fallback={(
            <div className="canvas-loading-state" role="status">
              {t('Opening Contract workspace...')}
            </div>
          )}
        >
          <ContractScenarioWorkspace
            open
            initialTab={contractWorkspaceInitialTab}
            initialScenarioId={workspaceScenarioId}
            graphDraft={scenarioWorkspaceGraphDraft}
            contract={contractDraft}
            contractFingerprint={contractFingerprint}
            scenarioDraftSet={scenarioDraftSet}
            nodes={scenarioNodeOptions}
            lastRun={activeScenarioEvidence?.response ?? result}
            lastRunScenarioId={activeScenarioEvidence?.scenarioId}
            lastComparison={activeScenarioEvidence?.comparison}
            targetStored={exactSavedDraft}
            onContractChange={updateContractSemantics}
            onImportWorkspace={importScenarioWorkspace}
            onScenarioDraftSetChange={setScenarioDraftSet}
            onSaveGraphDraft={saveAuthoritativeGraph}
            onRebase={rebaseScenariosToCurrentContract}
            onRun={runScenarioSimulation}
            onRunEvidence={recordScenarioEvidence}
            onCoordinateChange={updateWorkspaceCoordinate}
            trustContext={scenarioEvidenceTrustContext}
            onSelectEvidenceDiagnostic={openAuthorDiagnostic}
            onClose={() => {
              setContractWorkspaceOpen(false);
              if (isTaskWorkspace) {
                setAuthorMode('compose');
              }
            }}
          />
        </Suspense>
      )}
      {testSuiteOpen && !isTaskWorkspace && (
        <div className="rule-editor-backdrop" role="presentation">
          <section
            ref={testSuiteDialogRef}
            className="test-suite-dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="test-suite-dialog-title"
            tabIndex={-1}
            data-testid="test-suite-dialog"
          >
            <div className="operator-detail-heading">
              <span>{t('Mock regression')}</span>
              <strong id="test-suite-dialog-title">{t('Test Suite')}</strong>
              <button
                type="button"
                className="secondary compact"
                aria-label={t('Close test suite')}
                onClick={closeTestSuite}
              >
                {t('Done')}
              </button>
            </div>
            {testTablePanel}
          </section>
        </div>
      )}
      {pendingNodeDeletion && (
        <NodeDeletionImpactDialog
          open
          nodeLabels={pendingNodeDeletion.nodeLabels}
          impact={pendingNodeDeletion.impact}
          productionSafeguard={pendingNodeDeletion.productionSafeguard}
          onCancel={() => setPendingNodeDeletion(null)}
          onConfirm={() => deleteNodesAtomically(
            pendingNodeDeletion.nodeIds,
            pendingNodeDeletion.impact,
          )}
        />
      )}
      {draftSaveConflict && (
        <SaveConflictResolutionDialog
          open
          subjectLabel={t('Graph draft')}
          local={graphConflictSnapshot(
            draftSaveConflict.localDraft,
            draftSaveConflict.localFingerprint,
            draftSaveConflict.localScenarioDraftSet,
          )}
          authoritative={draftSaveConflict.authoritative
            ? graphConflictSnapshot(
                draftSaveConflict.authoritative,
                draftSaveConflict.authoritativeFingerprint,
                undefined,
              )
            : null}
          authorityLoading={draftSaveConflict.loading}
          busyAction={draftSaveConflict.busyAction}
          error={draftSaveConflict.error}
          onFork={() => void forkConflictedGraph()}
          onReload={() => void reloadAuthoritativeGraph()}
          onRetryAuthority={() => void openGraphSaveConflict(
            draftSaveConflict.localDraft,
            draftSaveConflict.localScenarioDraftSet,
            '',
            draftSaveConflict.forkIdempotencyKey,
          )}
        />
      )}
      {pendingProductionCommand && (
        <ProductionCommandDialog
          open
          commandLabel={pendingProductionCommand.commandLabel}
          targetLabel={pendingProductionCommand.targetLabel}
          onCancel={() => setPendingProductionCommand(null)}
          onConfirm={() => {
            const command = pendingProductionCommand;
            setPendingProductionCommand(null);
            command.execute();
          }}
        />
      )}
      {mutationNotice && (
        <MutationNotice
          message={mutationNotice.message}
          action={mutationNotice.action}
          onAction={mutationNotice.action === 'undo' ? undoAuthoringMutation : redoAuthoringMutation}
          onDismiss={() => setMutationNotice(null)}
        />
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
          canonicalScenarios={isTaskWorkspace}
          effectiveContract={operatorDetailEffectiveContract ?? projectEffectiveContract({
            graphDraft: exportableDraft,
            nodeId: operatorDetailNode.id,
            operator: operatorDetailDefinition,
            operators: [...operatorByRef.values()],
            run: result,
          })}
          scenarioTarget={operatorDetailDefinition?.fingerprint ? {
            kind: 'OPERATOR',
            id: operatorDetailDefinition.operatorRef,
            revision: 1,
            fingerprint: operatorDetailDefinition.fingerprint,
          } : undefined}
          scenarioScope={{
            tenantId: exportableDraft.tenantId ?? '',
            organizationId: '',
            projectId: '',
            environment: exportableDraft.environment ?? '',
            region: '',
          }}
          persistedScenarioDraftSet={scenarioDraftSet}
          onScenarioDraftSetChange={setScenarioDraftSet}
          governedFixtureAssets={governedFixtureAssets}
          governedFixtureRef={operatorDetailNode.id === selectedNode?.id
            ? governedFixtureRefs[operatorDetailNode.id] : undefined}
          governedFixtureStale={operatorDetailNode.id === selectedNode?.id
            ? selectedGovernedFixtureStale : false}
          onGovernedFixtureSelect={selectGovernedFixture}
          onClearGovernedFixture={clearSelectedGovernedFixture}
          resourceFidelity={resourceFidelityByNode[operatorDetailNode.id] ?? 'OUTPUT_LEVEL'}
          onResourceFidelityChange={(value) => setResourceFidelityByNode((current) => ({
            ...current,
            [operatorDetailNode.id]: value,
          }))}
          onCancel={cancelOperatorDetail}
          onApply={applyOperatorDetail}
          dirty={operatorDetailDirty}
          onOpenContract={() => {
            if (operatorDetailDefinition) {
              void openOperatorContractWorkspace(
                operatorDetailDefinition,
                'interface',
                operatorDetailNode.id,
              );
            }
          }}
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
          onAcceptInference={
            operatorDetailInferenceAcceptable ? acceptOperatorDetailInference : undefined
          }
        />
      )}
    </div>
  );
}
