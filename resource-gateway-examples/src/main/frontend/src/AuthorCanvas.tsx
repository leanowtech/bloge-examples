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
  autoLayoutCanvas,
  type CanvasEdge,
  type CanvasNode,
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
  portNameFromHandle,
  simulationChecklist,
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
  return (
    <div className={`operator-node ${status} ${candidateClass} ${selected ? 'selected' : ''}`}>
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
  const [selectedNodeId, setSelectedNodeId] = useState('');
  const [fixtureDrafts, setFixtureDrafts] = useState<Record<string, string>>({});
  const [connectionNotice, setConnectionNotice] = useState<ConnectionNotice | null>(null);
  const [candidatePreview, setCandidatePreview] = useState<ConnectionCandidateIndex | null>(null);
  const counter = useRef(0);
  const candidatePreviewSequence = useRef(0);

  useEffect(() => {
    fetchOperators()
      .then(setOperators)
      .catch((cause: unknown) => setError(String(cause)));
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
  const fixtureCompilation = useMemo(() => compileFixtureDrafts(fixtureDrafts), [fixtureDrafts]);
  const fixtureCount = Object.keys(fixtureCompilation.fixtures).length;
  const fixtureErrorCount = Object.keys(fixtureCompilation.errors).length;
  const hasFixtureErrors = fixtureErrorCount > 0;
  const selectedFixtureDraft = selectedNode ? fixtureDrafts[selectedNode.id] ?? '' : '';
  const selectedFixtureError = selectedNode ? fixtureCompilation.errors[selectedNode.id] : undefined;
  const flowNodes = useMemo<Node<NodeData>[]>(
    () =>
      nodes.map((node) => {
        const candidateStatus = candidatePreview?.nodeStatuses[node.id];
        const candidatePorts = candidatePreview?.portStatuses[node.id];
        if (!candidateStatus && !candidatePorts) {
          return node;
        }
        return {
          ...node,
          data: {
            ...node.data,
            candidateStatus,
            candidatePorts,
          },
        };
      }),
    [candidatePreview, nodes],
  );

  const updateSelectedFixtureDraft = useCallback((value: string) => {
    if (!selectedNodeId) {
      return;
    }
    clearRunResult();
    setFixtureDrafts((current) => ({ ...current, [selectedNodeId]: value }));
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

  const operatorRows = operators
    .map((operator) => ({ operator, summary: summarizeOperator(operator) }))
    .filter(({ summary }) => {
      const query = search.toLowerCase();
      return (
        summary.name.toLowerCase().includes(query) ||
        summary.operatorRef.toLowerCase().includes(query) ||
        summary.tags.some((tag) => tag.toLowerCase().includes(query))
      );
    });

  return (
    <div className="workspace">
      <aside className="palette">
        <h2>Operators</h2>
        <input
          placeholder="Search…"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />
        <ul>
          {operatorRows.map(({ operator, summary }) => (
            <li key={operator.operatorRef}>
              <button onClick={() => addOperator(operator)} title={operator.operatorRef}>
                <span className="op-copy">
                  <span className="op-name">{summary.name}</span>
                  <span className="op-meta">
                    {summary.requiredInputCount}/{summary.inputCount} inputs ·{' '}
                    {summary.outputCount} outputs
                  </span>
                </span>
                {summary.designOnly && <span className="badge design">design</span>}
              </button>
            </li>
          ))}
          {operatorRows.length === 0 && (
            <li className="muted">No operators. Is the server running?</li>
          )}
        </ul>
      </aside>

      <main className="canvas">
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
                <span className={`badge ${selectedFixtureDraft.trim() ? 'fixture' : ''}`}>
                  {selectedFixtureDraft.trim() ? 'pinned' : 'server sample'}
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
                  disabled={!selectedFixtureDraft}
                >
                  Clear
                </button>
              </div>
              <textarea
                aria-label="Simulation fixture JSON"
                spellCheck={false}
                placeholder="null"
                value={selectedFixtureDraft}
                onChange={(event) => updateSelectedFixtureDraft(event.target.value)}
              />
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
