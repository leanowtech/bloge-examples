import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
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

import { fetchOperators, simulate } from './api';
import {
  autoLayoutCanvas,
  type CanvasEdge,
  type CanvasNode,
  type OperatorSummary,
  isRunSuccessful,
  nodeStatuses,
  simulationChecklist,
  summarizeCanvas,
  summarizeOperator,
  toGraphDraft,
} from './draftModel';
import type { OperatorDefinition, SimulationResponse } from './types';

interface NodeData {
  label: string;
  operatorRef: string;
  summary: OperatorSummary;
  status?: 'mocked' | 'real' | 'unknown';
}

function OperatorNode({ data, selected }: NodeProps<NodeData>) {
  const status = data.status ?? 'unknown';
  return (
    <div className={`operator-node ${status} ${selected ? 'selected' : ''}`}>
      <Handle type="target" position={Position.Left} />
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
      {data.summary.designOnly && <div className="operator-node-warning">design-only</div>}
      <Handle type="source" position={Position.Right} />
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
  const [search, setSearch] = useState('');
  const [selectedNodeId, setSelectedNodeId] = useState('');
  const counter = useRef(0);

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
      if (changes.some((change) => change.type === 'remove' || change.type === 'add')) {
        clearRunResult();
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
  const onConnect = useCallback((connection: Connection) => {
    clearRunResult();
    setEdges((current) => addEdge({ ...connection, animated: true }, current));
  }, [clearRunResult]);

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
  const selectedNode = nodes.find((node) => node.id === selectedNodeId);

  const runSimulation = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const draft = toGraphDraft('visualGraph', canvasNodes, canvasEdges, '');
      const response = await simulate({ draft, context: {}, outputNode: '' });
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
  }, [canvasEdges, canvasNodes]);

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
          <button className="primary" onClick={runSimulation} disabled={busy || nodes.length === 0}>
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
          <span className="legend">
            <span className="swatch mocked" /> mocked
            <span className="swatch real" /> real
          </span>
        </div>
        <div className="flow">
          <ReactFlow
            nodes={nodes}
            edges={edges}
            nodeTypes={NODE_TYPES}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
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
