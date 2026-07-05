import { type CSSProperties, type DragEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import ReactFlow, {
  addEdge,
  applyEdgeChanges,
  applyNodeChanges,
  Background,
  Controls,
  Handle,
  MiniMap,
  type Connection,
  type Edge,
  type EdgeChange,
  type Node,
  type NodeChange,
  type NodeProps,
  Position,
} from 'reactflow';
import 'reactflow/dist/style.css';

import {
  checkConnection,
  fetchConnectionCandidates,
  fetchOperators,
  importOperatorLibraryText,
  simulate,
  validateDraft,
  validateOperatorLibraryText,
} from './api';
import {
  authoringJourney,
  autoLayoutCanvas,
  canvasCoachPrompt,
  canvasNodeFocusState,
  type CanvasEdge,
  type CanvasNodeFocusState,
  type CanvasNode,
  type AuthoringJourneyAction,
  type ConnectionCandidateIndex,
  type ConnectionCandidateStatus,
  type ConnectionGuideRow,
  compileFixtureDrafts,
  connectionCandidatesMessage,
  connectionGuideRows,
  type OperatorSummary,
  connectionDecisionMessage,
  fixtureDraftForOperator,
  handleIdForPort,
  indexConnectionCandidates,
  isRunSuccessful,
  nodeStatuses,
  type OperatorPaletteFacet,
  operatorPaletteView,
  operatorLibraryImportMessage,
  operatorLibraryValidationLevel,
  operatorLibraryValidationMessage,
  portNameFromHandle,
  simulationChecklist,
  simulationFixtureRows,
  simulationRunSummary,
  simulationTraceRows,
  summarizeCanvas,
  summarizeOperator,
  toConnectionCandidatesRequest,
  toConnectionCheckRequest,
  toExportableGraphDraft,
  toSimulationRequest,
} from './draftModel';
import type { OperatorDefinition, SimulationResponse, VisualDiagnostic, VisualValidationResult } from './types';

interface NodeData {
  label: string;
  operatorRef: string;
  summary: OperatorSummary;
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

interface SelectedConnectionGuide {
  nodeId: string;
  sourcePort: string;
  index: ConnectionCandidateIndex;
}

function handleOffset(index: number, count: number): CSSProperties {
  return { top: `${((index + 1) / (count + 1)) * 100}%` };
}

function OperatorNode({ id, data, selected }: NodeProps<NodeData>) {
  const status = data.status ?? 'unknown';
  const inputPorts = data.summary.inputNames;
  const outputPorts = data.summary.outputNames.length ? data.summary.outputNames : [''];
  const candidateClass = data.candidateStatus ? `candidate-${data.candidateStatus}` : '';
  const focusClass = data.focusState && data.focusState !== 'none' ? `focus-${data.focusState}` : '';
  return (
    <div
      className={`operator-node ${status} ${candidateClass} ${focusClass} ${selected ? 'selected' : ''}`}
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
          {data.isOutput && <span className="output-pill">output</span>}
          {status !== 'unknown' && <span className={`run-pill ${status}`}>{status}</span>}
        </span>
      </div>
      <div className="operator-node-ref">{data.operatorRef}</div>
      <div className="operator-node-metrics">
        <span>
          {data.summary.requiredInputCount}/{data.summary.inputCount} inputs
        </span>
        <span>{data.summary.outputCount} outputs</span>
      </div>
      <div className="operator-node-port-grid">
        <span>In</span>
        <strong>{inputPorts.join(', ') || 'none'}</strong>
        <span>Out</span>
        <strong>{data.summary.outputNames.join(', ') || 'value'}</strong>
      </div>
      {data.summary.designOnly && <div className="operator-node-warning">design-only</div>}
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

/**
 * The authoring workspace: an operator palette, a React Flow canvas, and a result inspector wired to
 * the mock-run (simulate) endpoint. Non-trivial graph<->request logic lives in the pure, unit-tested
 * {@link ./draftModel} module; this component is thin glue.
 */
export default function AuthorCanvas() {
  const [operators, setOperators] = useState<OperatorDefinition[]>([]);
  const [nodes, setNodes] = useState<Node<NodeData>[]>([]);
  const [edges, setEdges] = useState<Edge[]>([]);
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
  const [search, setSearch] = useState('');
  const [paletteFacet, setPaletteFacet] = useState<OperatorPaletteFacet>('all');
  const [sourceFilter, setSourceFilter] = useState('all');
  const [tagFilter, setTagFilter] = useState('all');
  const [selectedNodeId, setSelectedNodeId] = useState('');
  const [explicitOutputNodeId, setExplicitOutputNodeId] = useState('');
  const [fixtureDrafts, setFixtureDrafts] = useState<Record<string, string>>({});
  const [fixtureInputDrafts, setFixtureInputDrafts] = useState<Record<string, string>>({});
  const [connectionNotice, setConnectionNotice] = useState<ConnectionNotice | null>(null);
  const [candidatePreview, setCandidatePreview] = useState<ConnectionCandidateIndex | null>(null);
  const [selectedConnectionSourcePort, setSelectedConnectionSourcePort] = useState('');
  const [connectionGuide, setConnectionGuide] = useState<SelectedConnectionGuide | null>(null);
  const [connectionGuideNotice, setConnectionGuideNotice] = useState<ConnectionNotice | null>(null);
  const [connectionGuideBusy, setConnectionGuideBusy] = useState(false);
  const [pendingConnectionGuideNodeId, setPendingConnectionGuideNodeId] = useState('');
  const searchInputRef = useRef<HTMLInputElement>(null);
  const flowRef = useRef<HTMLDivElement>(null);
  const counter = useRef(0);
  const candidatePreviewSequence = useRef(0);
  const connectionGuideSequence = useRef(0);

  const reloadOperators = useCallback(async () => {
    setOperators(await fetchOperators());
  }, []);

  useEffect(() => {
    reloadOperators()
      .catch((cause: unknown) => setError(String(cause)));
  }, [reloadOperators]);

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
        setSelectedNodeId((current) => (removedNodeIds.includes(current) ? '' : current));
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
      setEdges((current) => applyEdgeChanges(changes, current));
    },
    [clearRunResult],
  );
  const addOperator = useCallback((operator: OperatorDefinition, position?: { x: number; y: number }) => {
    clearRunResult();
    setConnectionGuide(null);
    const nextIndex = counter.current + 1;
    counter.current = nextIndex;
    const id = `n${nextIndex}`;
    const summary = summarizeOperator(operator);
    setNodes((current) => [
      ...current,
      {
        id,
        type: 'operator',
        position: position ?? { x: 72 + (nextIndex % 4) * 36, y: 56 + nextIndex * 40 },
        data: { label: summary.name, operatorRef: operator.operatorRef, summary },
      },
    ]);
    setSelectedNodeId(id);
  }, [clearRunResult]);

  const canvasNodes = useMemo<CanvasNode[]>(
    () =>
      nodes.map((node) => ({
        id: node.id,
        operatorRef: node.data.operatorRef,
        label: node.data.label,
        position: node.position,
      })),
    [nodes],
  );
  const canvasEdges = useMemo<CanvasEdge[]>(
    () =>
      edges.map((edge) => ({
        id: edge.id,
        source: edge.source,
        target: edge.target,
        sourcePort: portNameFromHandle(edge.sourceHandle, 'out'),
        targetPort: portNameFromHandle(edge.targetHandle, 'in'),
      })),
    [edges],
  );
  const implicitOutputNodeId = nodes.length > 0 ? nodes[nodes.length - 1].id : '';
  const outputNodeId = explicitOutputNodeId || implicitOutputNodeId;
  const canvasSummary = useMemo(
    () => summarizeCanvas(canvasNodes, canvasEdges, outputNodeId),
    [canvasEdges, canvasNodes, outputNodeId],
  );
  const checklist = useMemo(
    () => simulationChecklist(canvasSummary, result),
    [canvasSummary, result],
  );
  const traceRows = useMemo(() => simulationTraceRows(canvasNodes, result), [canvasNodes, result]);
  const operatorByRef = useMemo(
    () => new Map(operators.map((operator) => [operator.operatorRef, operator])),
    [operators],
  );
  const selectedNode = nodes.find((node) => node.id === selectedNodeId);
  const selectedOperator = selectedNode ? operatorByRef.get(selectedNode.data.operatorRef) : undefined;
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
    () => toExportableGraphDraft(
      'visualGraph',
      canvasNodes,
      canvasEdges,
      outputNodeId,
      fixtureCompilation.fixtures,
    ),
    [canvasEdges, canvasNodes, fixtureCompilation.fixtures, outputNodeId],
  );
  const draftExportJson = useMemo(
    () => JSON.stringify(exportableDraft, null, 2),
    [exportableDraft],
  );
  const draftExportUrl = useMemo(
    () => `data:application/json;charset=utf-8,${encodeURIComponent(draftExportJson)}`,
    [draftExportJson],
  );
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
  const selectedFixtureDraft = selectedNode ? fixtureDrafts[selectedNode.id] ?? '' : '';
  const selectedExpectedInputDraft = selectedNode ? fixtureInputDrafts[selectedNode.id] ?? '' : '';
  const selectedFixtureHasDraft =
    selectedFixtureDraft.trim().length > 0 || selectedExpectedInputDraft.trim().length > 0;
  const selectedFixtureError = selectedNode ? fixtureCompilation.errors[selectedNode.id] : undefined;
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

  const updateSelectedFixtureDraft = useCallback((value: string) => {
    if (!selectedNodeId) {
      return;
    }
    clearRunResult();
    setFixtureDrafts((current) => ({ ...current, [selectedNodeId]: value }));
  }, [clearRunResult, selectedNodeId]);

  const updateSelectedExpectedInputDraft = useCallback((value: string) => {
    if (!selectedNodeId) {
      return;
    }
    clearRunResult();
    setFixtureInputDrafts((current) => ({ ...current, [selectedNodeId]: value }));
  }, [clearRunResult, selectedNodeId]);

  const useSelectedFixtureSample = useCallback(() => {
    if (!selectedNodeId || !selectedOperator) {
      return;
    }
    clearRunResult();
    setFixtureDrafts((current) => ({
      ...current,
      [selectedNodeId]: fixtureDraftForOperator(selectedOperator),
    }));
  }, [clearRunResult, selectedNodeId, selectedOperator]);

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
      const storedLibrary = await importOperatorLibraryText(librarySourceText);
      await reloadOperators();
      setLibrarySourceText(JSON.stringify(storedLibrary, null, 2));
      setLibraryDiagnostics([]);
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
  }, [librarySourceText, reloadOperators]);

  const clearSelectedFixture = useCallback(() => {
    if (!selectedNodeId) {
      return;
    }
    clearRunResult();
    setFixtureDrafts((current) => {
      const next = { ...current };
      delete next[selectedNodeId];
      return next;
    });
    setFixtureInputDrafts((current) => {
      const next = { ...current };
      delete next[selectedNodeId];
      return next;
    });
  }, [clearRunResult, selectedNodeId]);

  const setSelectedAsOutput = useCallback(() => {
    if (!selectedNodeId) {
      return;
    }
    clearRunResult();
    setExplicitOutputNodeId(selectedNodeId);
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
        'visualGraph',
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
  }, [canvasEdges, canvasNodes, outputNodeId]);

  const onConnectEnd = useCallback(() => {
    candidatePreviewSequence.current += 1;
    setCandidatePreview(null);
    setLoadingCandidates(false);
  }, []);

  const onConnect = useCallback(async (connection: Connection) => {
    if (!connection.source || !connection.target) {
      return;
    }
    candidatePreviewSequence.current += 1;
    setCandidatePreview(null);
    setCheckingConnection(true);
    setConnectionNotice({ level: 'pending', message: 'Checking schema compatibility...' });
    try {
      const check = await checkConnection(toConnectionCheckRequest(
        'visualGraph',
        canvasNodes,
        canvasEdges,
        outputNodeId,
        connection.source,
        connection.target,
        connection.sourceHandle,
        connection.targetHandle,
      ));
      const message = connectionDecisionMessage(check);
      if (!check.accepted) {
        setConnectionNotice({ level: 'error', message });
        return;
      }

      clearRunResult();
      const sourcePort = portNameFromHandle(connection.sourceHandle, 'out');
      const targetPort = portNameFromHandle(connection.targetHandle, 'in');
      const label = `${sourcePort || 'value'} -> ${targetPort || 'input'}`;
      setEdges((current) =>
        addEdge({
          ...connection,
          id: check.edge?.id || `${connection.source}:${sourcePort}->${connection.target}:${targetPort}`,
          label,
          animated: true,
          className: 'accepted-edge',
        }, current),
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
  }, [canvasEdges, canvasNodes, clearRunResult, outputNodeId]);

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
        'visualGraph',
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
    await onConnect({
      source: selectedNodeId,
      target: row.targetNodeId,
      sourceHandle: handleIdForPort('out', selectedConnectionSourcePort),
      targetHandle: handleIdForPort('in', row.targetPort),
    });
    setConnectionGuide(null);
    setConnectionGuideNotice(null);
  }, [onConnect, selectedConnectionSourcePort, selectedNodeId]);

  const runSimulation = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const response = await simulate(toSimulationRequest(
        'visualGraph',
        canvasNodes,
        canvasEdges,
        outputNodeId,
        fixtureCompilation.fixtures,
      ));
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
    } catch (cause: unknown) {
      setError(String(cause));
    } finally {
      setBusy(false);
    }
  }, [canvasEdges, canvasNodes, fixtureCompilation.fixtures, outputNodeId]);

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

  const autoLayout = useCallback(() => {
    const layout = autoLayoutCanvas(canvasNodes, canvasEdges);
    const positions = new Map(layout.map((node) => [node.id, node.position]));
    setNodes((current) =>
      current.map((node) => ({
        ...node,
        position: positions.get(node.id) ?? node.position,
      })),
    );
  }, [canvasEdges, canvasNodes]);

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

  return (
    <div className="workspace">
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
            placeholder="bloge.visualOperatorLibrary.v1 JSON/YAML"
            value={librarySourceText}
            onChange={(event) => {
              setLibrarySourceText(event.target.value);
              setLibraryNotice(null);
              setLibraryDiagnostics([]);
            }}
          />
          <div className="library-actions">
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
              disabled={libraryBusy}
            >
              Import
            </button>
          </div>
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
                        <span className="op-name">{summary.name}</span>
                        <span className="op-ref">{summary.operatorRef}</span>
                        <span className="op-meta">
                          {summary.requiredInputCount}/{summary.inputCount} inputs ·{' '}
                          {summary.outputCount} outputs
                        </span>
                      </span>
                      {summary.designOnly && <span className="badge design">design</span>}
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
        <div className="toolbar">
          <button
            className="primary"
            onClick={runSimulation}
            disabled={busy || nodes.length === 0 || hasFixtureErrors}
            title={hasFixtureErrors ? 'Fix fixture JSON before simulating.' : undefined}
          >
            {busy ? 'Simulating…' : 'Simulate'}
          </button>
          <button className="secondary" onClick={autoLayout} disabled={nodes.length < 2}>
            Auto Layout
          </button>
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
            download="visualGraph-draft.json"
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
          <ReactFlow
            nodes={flowNodes}
            edges={edges}
            nodeTypes={NODE_TYPES}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onConnectStart={onConnectStart}
            onConnectEnd={onConnectEnd}
            onNodeClick={(_, node) => setSelectedNodeId(node.id)}
            onPaneClick={() => setSelectedNodeId('')}
            fitView
          >
            <Background />
            <Controls />
            <MiniMap />
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

        <h2>Selected Node</h2>
        {selectedNode ? (
          <section className="node-detail">
            <h3>{selectedNode.data.label}</h3>
            <p className="muted">{selectedNode.data.operatorRef}</p>
            {selectedNode.data.summary.description && (
              <p>{selectedNode.data.summary.description}</p>
            )}
            <div className="port-list">
              <strong>Inputs</strong>
              <span>{selectedNode.data.summary.inputNames.join(', ') || 'none'}</span>
            </div>
            <div className="port-list">
              <strong>Outputs</strong>
              <span>{selectedNode.data.summary.outputNames.join(', ') || 'none'}</span>
            </div>
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
                      <button
                        type="button"
                        className="connection-guide-target"
                        onClick={() => setSelectedNodeId(row.targetNodeId)}
                        title={row.detail}
                      >
                        <span>
                          <strong>{row.targetLabel}</strong>
                          <small>{row.targetOperatorRef || row.targetNodeId}</small>
                          <code>{row.targetPort || 'input'}</code>
                        </span>
                        <em>{row.status}</em>
                      </button>
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
    </div>
  );
}
