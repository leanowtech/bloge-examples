import { type CSSProperties, useCallback, useEffect, useMemo, useRef, useState } from 'react';
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

import { checkConnection, fetchConnectionCandidates, fetchOperators, simulate } from './api';
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
  compileFixtureDrafts,
  connectionCandidatesMessage,
  type OperatorSummary,
  connectionDecisionMessage,
  fixtureDraftForOperator,
  handleIdForPort,
  indexConnectionCandidates,
  isRunSuccessful,
  nodeStatuses,
  type OperatorPaletteFacet,
  operatorPaletteView,
  portNameFromHandle,
  simulationChecklist,
  simulationFixtureRows,
  simulationTraceRows,
  summarizeCanvas,
  summarizeOperator,
  toConnectionCandidatesRequest,
  toConnectionCheckRequest,
  toGraphDraft,
} from './draftModel';
import type { OperatorDefinition, SimulationResponse } from './types';

interface NodeData {
  label: string;
  operatorRef: string;
  summary: OperatorSummary;
  status?: 'mocked' | 'real' | 'unknown';
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

function handleOffset(index: number, count: number): CSSProperties {
  return { top: `${((index + 1) / (count + 1)) * 100}%` };
}

function OperatorNode({ data, selected }: NodeProps<NodeData>) {
  const status = data.status ?? 'unknown';
  const inputPorts = data.summary.inputNames;
  const outputPorts = data.summary.outputNames.length ? data.summary.outputNames : [''];
  const candidateClass = data.candidateStatus ? `candidate-${data.candidateStatus}` : '';
  const focusClass = data.focusState && data.focusState !== 'none' ? `focus-${data.focusState}` : '';
  return (
    <div className={`operator-node ${status} ${candidateClass} ${focusClass} ${selected ? 'selected' : ''}`}>
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
        {status !== 'unknown' && <span className={`run-pill ${status}`}>{status}</span>}
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
  const [error, setError] = useState<string>('');
  const [busy, setBusy] = useState(false);
  const [checkingConnection, setCheckingConnection] = useState(false);
  const [loadingCandidates, setLoadingCandidates] = useState(false);
  const [search, setSearch] = useState('');
  const [paletteFacet, setPaletteFacet] = useState<OperatorPaletteFacet>('all');
  const [sourceFilter, setSourceFilter] = useState('all');
  const [tagFilter, setTagFilter] = useState('all');
  const [selectedNodeId, setSelectedNodeId] = useState('');
  const [fixtureDrafts, setFixtureDrafts] = useState<Record<string, string>>({});
  const [fixtureInputDrafts, setFixtureInputDrafts] = useState<Record<string, string>>({});
  const [connectionNotice, setConnectionNotice] = useState<ConnectionNotice | null>(null);
  const [candidatePreview, setCandidatePreview] = useState<ConnectionCandidateIndex | null>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const counter = useRef(0);
  const candidatePreviewSequence = useRef(0);

  useEffect(() => {
    fetchOperators()
      .then(setOperators)
      .catch((cause: unknown) => setError(String(cause)));
  }, []);

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
      }
      setNodes((current) => applyNodeChanges(changes, current) as Node<NodeData>[]);
    },
    [clearRunResult],
  );
  const onEdgesChange = useCallback(
    (changes: EdgeChange[]) => {
      if (changes.some((change) => change.type === 'remove' || change.type === 'add')) {
        clearRunResult();
      }
      setEdges((current) => applyEdgeChanges(changes, current));
    },
    [clearRunResult],
  );
  const addOperator = useCallback((operator: OperatorDefinition) => {
    clearRunResult();
    counter.current += 1;
    const id = `n${counter.current}`;
    const summary = summarizeOperator(operator);
    setNodes((current) => [
      ...current,
      {
        id,
        type: 'operator',
        position: { x: 72 + (counter.current % 4) * 36, y: 56 + counter.current * 40 },
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
  const outputNodeId = nodes.length > 0 ? nodes[nodes.length - 1].id : '';
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
          },
        };
      }),
    [candidatePreview, coachPrompt, nodes, selectedNodeId],
  );

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

  const runSimulation = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const draft = toGraphDraft('visualGraph', canvasNodes, canvasEdges, '');
      const response = await simulate({
        draft,
        context: {},
        outputNode: '',
        ...(fixtureCount > 0 ? { fixtures: fixtureCompilation.fixtures } : {}),
      });
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
  }, [canvasEdges, canvasNodes, fixtureCompilation.fixtures, fixtureCount]);

  const runAuthoringAction = useCallback((action: AuthoringJourneyAction) => {
    if (action.kind === 'focus-palette') {
      searchInputRef.current?.focus();
      searchInputRef.current?.select();
      return;
    }
    if (action.kind === 'select-node' && action.nodeId) {
      setSelectedNodeId(action.nodeId);
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
            <section className="palette-group" key={group.libraryId}>
              <div className="palette-group-heading">
                <h3 title={group.libraryId}>{group.label}</h3>
                <span>{group.count}</span>
              </div>
              <ul className="operator-list">
                {group.rows.map(({ operator, summary }) => (
                  <li key={operator.operatorRef}>
                    <button
                      className="operator-button"
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
        <div className="flow">
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
