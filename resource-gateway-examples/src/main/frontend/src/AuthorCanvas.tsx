import { type CSSProperties, useCallback, useEffect, useRef, useState } from 'react';
import ReactFlow, {
  addEdge,
  applyEdgeChanges,
  applyNodeChanges,
  Background,
  Controls,
  MiniMap,
  type Connection,
  type Edge,
  type EdgeChange,
  type Node,
  type NodeChange,
} from 'reactflow';
import 'reactflow/dist/style.css';

import { fetchOperators, simulate } from './api';
import {
  type CanvasEdge,
  type CanvasNode,
  isRunSuccessful,
  nodeStatuses,
  toGraphDraft,
} from './draftModel';
import type { OperatorDefinition, SimulationResponse } from './types';

interface NodeData {
  label: string;
  operatorRef: string;
  status?: 'mocked' | 'real' | 'unknown';
}

// Mocked nodes are visually unmistakable (decision D15): dashed amber = synthesized output.
const STATUS_STYLE: Record<string, CSSProperties> = {
  mocked: { border: '2px dashed #b8860b', background: '#fff8e1' },
  real: { border: '2px solid #2e7d32', background: '#e8f5e9' },
};

const BASE_NODE_STYLE: CSSProperties = { padding: 8, borderRadius: 6 };

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
  const counter = useRef(0);

  useEffect(() => {
    fetchOperators()
      .then(setOperators)
      .catch((cause: unknown) => setError(String(cause)));
  }, []);

  const onNodesChange = useCallback(
    (changes: NodeChange[]) =>
      setNodes((current) => applyNodeChanges(changes, current) as Node<NodeData>[]),
    [],
  );
  const onEdgesChange = useCallback(
    (changes: EdgeChange[]) => setEdges((current) => applyEdgeChanges(changes, current)),
    [],
  );
  const onConnect = useCallback(
    (connection: Connection) =>
      setEdges((current) => addEdge({ ...connection, animated: true }, current)),
    [],
  );

  const addOperator = useCallback((operator: OperatorDefinition) => {
    counter.current += 1;
    const id = `n${counter.current}`;
    const label = operator.display?.name || operator.operatorRef;
    setNodes((current) => [
      ...current,
      {
        id,
        position: { x: 60 + (counter.current % 5) * 40, y: 40 + counter.current * 34 },
        data: { label, operatorRef: operator.operatorRef },
        style: BASE_NODE_STYLE,
      },
    ]);
  }, []);

  const runSimulation = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const canvasNodes: CanvasNode[] = nodes.map((node) => ({
        id: node.id,
        operatorRef: node.data.operatorRef,
        label: node.data.label,
        position: node.position,
      }));
      const canvasEdges: CanvasEdge[] = edges.map((edge) => ({
        id: edge.id,
        source: edge.source,
        target: edge.target,
      }));
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
            style: { ...BASE_NODE_STYLE, ...(status ? STATUS_STYLE[status] : {}) },
          };
        }),
      );
    } catch (cause: unknown) {
      setError(String(cause));
    } finally {
      setBusy(false);
    }
  }, [nodes, edges]);

  const filteredOperators = operators.filter((operator) =>
    (operator.display?.name || operator.operatorRef).toLowerCase().includes(search.toLowerCase()),
  );

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
          {filteredOperators.map((operator) => (
            <li key={operator.operatorRef}>
              <button onClick={() => addOperator(operator)} title={operator.operatorRef}>
                <span className="op-name">{operator.display?.name || operator.operatorRef}</span>
                {operator.lowering?.mode === 'design' && (
                  <span className="badge design">design-only</span>
                )}
              </button>
            </li>
          ))}
          {filteredOperators.length === 0 && (
            <li className="muted">No operators. Is the server running?</li>
          )}
        </ul>
      </aside>

      <main className="canvas">
        <div className="toolbar">
          <button className="primary" onClick={runSimulation} disabled={busy || nodes.length === 0}>
            {busy ? 'Simulating…' : 'Simulate'}
          </button>
          {result && (
            <span className={isRunSuccessful(result) ? 'status ok' : 'status fail'}>
              {isRunSuccessful(result) ? 'Success' : 'Blocked'}
            </span>
          )}
          <span className="legend">
            <span className="swatch mocked" /> mocked
            <span className="swatch real" /> real
          </span>
        </div>
        <div className="flow">
          <ReactFlow
            nodes={nodes}
            edges={edges}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            fitView
          >
            <Background />
            <Controls />
            <MiniMap />
          </ReactFlow>
        </div>
      </main>

      <aside className="inspector">
        <h2>Result</h2>
        {error && <pre className="error">{error}</pre>}
        {!result && !error && <p className="muted">Add operators, connect them, then Simulate.</p>}
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
