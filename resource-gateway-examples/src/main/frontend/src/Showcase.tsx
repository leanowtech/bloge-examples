import { useEffect, useMemo, useRef, useState } from 'react';

import { buildGatewayRunRequest, fetchGatewayDiagram, fetchGatewayScenarios, runGatewayScenario } from './api';
import type {
  GatewayDiagramEdge,
  GatewayDiagramGroup,
  GatewayDiagramNode,
  GatewayExampleDiagram,
  GatewayExamplePreset,
  GatewayExampleScenario,
} from './types';

const DEFAULT_NODE_WIDTH = 180;
const DEFAULT_NODE_HEIGHT = 72;
const DIAGRAM_PADDING = 48;
const STREAM_EVENT_NAMES = ['meta', 'token', 'citation'] as const;

type ShowcaseRunStatus = 'idle' | 'running' | 'success' | 'error' | 'streaming';

interface ShowcaseRunState {
  status: ShowcaseRunStatus;
  url: string;
  message: string;
  payload: unknown;
}

function previewJson(value: unknown): string {
  try {
    return JSON.stringify(value ?? {}, null, 2);
  } catch {
    return '{}';
  }
}

function inputText(value: unknown): string {
  if (value === null || value === undefined) {
    return '';
  }
  if (typeof value === 'object') {
    return JSON.stringify(value);
  }
  return String(value);
}

function scenarioInputValues(
  scenario: GatewayExampleScenario | undefined,
  preset?: GatewayExamplePreset,
): Record<string, string> {
  const base = scenario?.sampleInput ?? {};
  const next = { ...base, ...(preset?.values ?? {}) };
  return Object.fromEntries(
    Object.entries(next).map(([key, value]) => [key, inputText(value)]),
  );
}

function emptyRunState(): ShowcaseRunState {
  return { status: 'idle', url: '', message: 'Not run yet.', payload: null };
}

function conceptList(scenario: GatewayExampleScenario): string[] {
  return scenario.concepts ?? [];
}

function nodeX(node: GatewayDiagramNode): number {
  return node.position?.x ?? 0;
}

function nodeY(node: GatewayDiagramNode): number {
  return node.position?.y ?? 0;
}

function nodeWidth(node: GatewayDiagramNode): number {
  return node.size?.width ?? DEFAULT_NODE_WIDTH;
}

function nodeHeight(node: GatewayDiagramNode): number {
  return node.size?.height ?? DEFAULT_NODE_HEIGHT;
}

function nodeCenter(node: GatewayDiagramNode): { x: number; y: number } {
  return {
    x: nodeX(node) + nodeWidth(node) / 2,
    y: nodeY(node) + nodeHeight(node) / 2,
  };
}

function diagramViewBox(diagram: GatewayExampleDiagram | null): string {
  const nodes = diagram?.nodes ?? [];
  if (nodes.length === 0) {
    return '0 0 720 360';
  }
  const minX = Math.min(...nodes.map(nodeX)) - DIAGRAM_PADDING;
  const minY = Math.min(...nodes.map(nodeY)) - DIAGRAM_PADDING;
  const maxX = Math.max(...nodes.map((node) => nodeX(node) + nodeWidth(node))) + DIAGRAM_PADDING;
  const maxY = Math.max(...nodes.map((node) => nodeY(node) + nodeHeight(node))) + DIAGRAM_PADDING;
  return `${minX} ${minY} ${Math.max(320, maxX - minX)} ${Math.max(220, maxY - minY)}`;
}

function groupBounds(group: GatewayDiagramGroup, nodes: GatewayDiagramNode[]) {
  const groupedNodes = nodes.filter((node) => node.group === group.id);
  if (groupedNodes.length === 0) {
    return null;
  }
  const minX = Math.min(...groupedNodes.map(nodeX)) - 24;
  const minY = Math.min(...groupedNodes.map(nodeY)) - 32;
  const maxX = Math.max(...groupedNodes.map((node) => nodeX(node) + nodeWidth(node))) + 24;
  const maxY = Math.max(...groupedNodes.map((node) => nodeY(node) + nodeHeight(node))) + 24;
  return { x: minX, y: minY, width: maxX - minX, height: maxY - minY };
}

function edgeLine(edge: GatewayDiagramEdge, nodesById: Map<string, GatewayDiagramNode>) {
  const source = nodesById.get(edge.source);
  const target = nodesById.get(edge.target);
  if (!source || !target) {
    return null;
  }
  const from = nodeCenter(source);
  const to = nodeCenter(target);
  return { x1: from.x, y1: from.y, x2: to.x, y2: to.y };
}

function truncateLabel(value: string | undefined | null, maxLength: number): string {
  const text = value?.trim() ?? '';
  if (text.length <= maxLength) {
    return text;
  }
  return `${text.slice(0, Math.max(0, maxLength - 1))}…`;
}

function annotationValue(value: unknown): string {
  if (value === null || value === undefined) {
    return '';
  }
  if (typeof value === 'object') {
    return previewJson(value).replace(/\s+/g, ' ');
  }
  return String(value);
}

/** Read-only scenario browser for the resource-gateway examples catalog. */
export default function Showcase() {
  const [scenarios, setScenarios] = useState<GatewayExampleScenario[]>([]);
  const [selectedGraphName, setSelectedGraphName] = useState('');
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading');
  const [errorMessage, setErrorMessage] = useState('');
  const [diagram, setDiagram] = useState<GatewayExampleDiagram | null>(null);
  const [diagramStatus, setDiagramStatus] = useState<'idle' | 'loading' | 'ready' | 'error'>('idle');
  const [diagramErrorMessage, setDiagramErrorMessage] = useState('');
  const [selectedDiagramNodeId, setSelectedDiagramNodeId] = useState('');
  const [inputValues, setInputValues] = useState<Record<string, string>>({});
  const [runState, setRunState] = useState<ShowcaseRunState>(emptyRunState);
  const streamSourceRef = useRef<EventSource | null>(null);

  useEffect(() => {
    let cancelled = false;
    setStatus('loading');
    fetchGatewayScenarios()
      .then((loadedScenarios) => {
        if (cancelled) {
          return;
        }
        setScenarios(loadedScenarios);
        setSelectedGraphName((current) =>
          loadedScenarios.some((scenario) => scenario.graphName === current)
            ? current
            : loadedScenarios[0]?.graphName ?? '',
        );
        setStatus('ready');
      })
      .catch((error: unknown) => {
        if (cancelled) {
          return;
        }
        setErrorMessage(error instanceof Error ? error.message : 'Unable to load scenarios.');
        setStatus('error');
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const selectedScenario = useMemo(
    () => scenarios.find((scenario) => scenario.graphName === selectedGraphName) ?? scenarios[0],
    [scenarios, selectedGraphName],
  );
  const presets = selectedScenario?.samplePresets ?? [];
  const decisionRows = selectedScenario?.decisionTable?.rows ?? [];
  const decisionColumns = selectedScenario?.decisionTable?.columns ?? [];
  const nodesById = useMemo(
    () => new Map((diagram?.nodes ?? []).map((node) => [node.id, node])),
    [diagram],
  );
  const selectedDiagramNode = useMemo(
    () => diagram?.nodes.find((node) => node.id === selectedDiagramNodeId) ?? diagram?.nodes[0],
    [diagram, selectedDiagramNodeId],
  );
  const selectedDiagramAnnotations = Object.entries(selectedDiagramNode?.annotations ?? {}).slice(0, 6);
  const inputKeys = Object.keys(selectedScenario?.sampleInput ?? {});

  function closeStream() {
    streamSourceRef.current?.close();
    streamSourceRef.current = null;
  }

  function updateInput(key: string, value: string) {
    setInputValues((current) => ({ ...current, [key]: value }));
  }

  async function runSelectedScenario(values: Record<string, string> = inputValues) {
    const run = selectedScenario?.run;
    if (!run) {
      setRunState({
        status: 'error',
        url: '',
        message: 'Selected scenario has no run recipe.',
        payload: null,
      });
      return;
    }
    closeStream();
    const request = buildGatewayRunRequest(run, values);
    setRunState({
      status: request.mode === 'stream' ? 'streaming' : 'running',
      url: request.url,
      message: request.mode === 'stream' ? 'Opening stream...' : 'Running...',
      payload: request.mode === 'stream' ? { meta: [], token: [], citation: [] } : null,
    });
    if (request.mode === 'stream') {
      if (typeof EventSource === 'undefined') {
        setRunState({
          status: 'error',
          url: request.url,
          message: 'EventSource is not available in this browser.',
          payload: null,
        });
        return;
      }
      const frames: Record<string, unknown[]> = { meta: [], token: [], citation: [] };
      const source = new EventSource(request.url);
      streamSourceRef.current = source;
      STREAM_EVENT_NAMES.forEach((eventName) => {
        source.addEventListener(eventName, (event) => {
          const data = (event as MessageEvent).data;
          try {
            frames[eventName] = [...frames[eventName], JSON.parse(data)];
          } catch {
            frames[eventName] = [...frames[eventName], data];
          }
          setRunState({
            status: 'streaming',
            url: request.url,
            message: 'Streaming...',
            payload: { ...frames },
          });
        });
      });
      source.onerror = () => {
        closeStream();
        setRunState((current) => ({
          ...current,
          status: current.status === 'streaming' ? 'success' : current.status,
          message: 'Stream closed.',
        }));
      };
      return;
    }
    try {
      const result = await runGatewayScenario(run, values);
      setRunState({
        status: 'success',
        url: result.url,
        message: `HTTP ${result.status}`,
        payload: result.payload,
      });
    } catch (error) {
      setRunState({
        status: 'error',
        url: request.url,
        message: error instanceof Error ? error.message : 'Gateway run failed.',
        payload: null,
      });
    }
  }

  function applyPreset(preset: GatewayExamplePreset) {
    const next = scenarioInputValues(selectedScenario, preset);
    setInputValues(next);
    void runSelectedScenario(next);
  }

  useEffect(() => {
    closeStream();
    setInputValues(scenarioInputValues(selectedScenario));
    setRunState(emptyRunState());
  }, [selectedScenario?.graphName]);

  useEffect(() => () => closeStream(), []);

  useEffect(() => {
    const diagramPath = selectedScenario?.diagramPath;
    if (!diagramPath) {
      setDiagram(null);
      setDiagramStatus('idle');
      setDiagramErrorMessage('');
      setSelectedDiagramNodeId('');
      return;
    }
    let cancelled = false;
    setDiagram(null);
    setDiagramStatus('loading');
    setDiagramErrorMessage('');
    setSelectedDiagramNodeId('');
    fetchGatewayDiagram(diagramPath)
      .then((loadedDiagram) => {
        if (cancelled) {
          return;
        }
        setDiagram(loadedDiagram);
        setSelectedDiagramNodeId(loadedDiagram.nodes[0]?.id ?? '');
        setDiagramStatus('ready');
      })
      .catch((error: unknown) => {
        if (cancelled) {
          return;
        }
        setDiagramErrorMessage(error instanceof Error ? error.message : 'Unable to load diagram.');
        setDiagramStatus('error');
      });
    return () => {
      cancelled = true;
    };
  }, [selectedScenario?.diagramPath]);

  return (
    <main className="showcase" data-testid="react-showcase">
      <aside className="showcase-sidebar" aria-label="Gateway scenarios">
        <div className="showcase-sidebar-heading">
          <span>Scenarios</span>
          <strong>{scenarios.length}</strong>
        </div>
        {status === 'loading' && scenarios.length === 0 ? (
          <p className="showcase-status">Loading catalog...</p>
        ) : null}
        {status === 'error' ? (
          <p className="showcase-status error">{errorMessage}</p>
        ) : null}
        <div className="showcase-list">
          {scenarios.map((scenario) => (
            <button
              key={scenario.graphName}
              type="button"
              className="showcase-scenario-button"
              data-testid={`showcase-scenario:${scenario.graphName}`}
              aria-current={scenario.graphName === selectedScenario?.graphName ? 'true' : undefined}
              onClick={() => setSelectedGraphName(scenario.graphName)}
            >
              <strong>{scenario.title}</strong>
              <span>{scenario.pattern}</span>
              <code>{scenario.graphName}</code>
            </button>
          ))}
        </div>
      </aside>

      {selectedScenario ? (
        <section className="showcase-detail" data-testid="showcase-detail">
          <div className="showcase-header">
            <div>
              <p className="eyebrow">{selectedScenario.pattern}</p>
              <h2>{selectedScenario.title}</h2>
              <p>{selectedScenario.description}</p>
            </div>
            <div className="showcase-actions">
              <a className="link" href={selectedScenario.diagramPath ?? '#'}>
                Diagram JSON
              </a>
              <a className="link" href="/examples/gateway">
                Legacy runner
              </a>
            </div>
          </div>

          <div className="showcase-tags" aria-label="Scenario concepts">
            {conceptList(selectedScenario).map((concept) => (
              <span key={concept} className="showcase-chip">
                {concept}
              </span>
            ))}
          </div>

          <section className="showcase-diagram-panel" data-testid="showcase-diagram">
            <div className="showcase-panel-heading">
              <h3>Diagram</h3>
              {diagram ? (
                <span>{diagram.nodes.length} nodes · {diagram.edges.length} edges</span>
              ) : null}
            </div>
            {diagramStatus === 'loading' ? (
              <p className="showcase-status">Loading diagram...</p>
            ) : null}
            {diagramStatus === 'error' ? (
              <p className="showcase-status error">{diagramErrorMessage}</p>
            ) : null}
            {diagramStatus === 'ready' && diagram ? (
              <div className="showcase-diagram-layout">
                <svg
                  className="showcase-diagram-svg"
                  viewBox={diagramViewBox(diagram)}
                  role="img"
                  aria-label={`${selectedScenario.title} graph diagram`}
                >
                  {(diagram.groups ?? []).map((group) => {
                    const bounds = groupBounds(group, diagram.nodes);
                    if (!bounds) {
                      return null;
                    }
                    return (
                      <g key={group.id} className="showcase-diagram-group">
                        <rect
                          x={bounds.x}
                          y={bounds.y}
                          width={bounds.width}
                          height={bounds.height}
                          rx="8"
                        />
                        <text x={bounds.x + 12} y={bounds.y + 20}>
                          {truncateLabel(group.label ?? group.id, 28)}
                        </text>
                      </g>
                    );
                  })}
                  {diagram.edges.map((edge) => {
                    const line = edgeLine(edge, nodesById);
                    if (!line) {
                      return null;
                    }
                    return (
                      <g key={edge.id ?? `${edge.source}->${edge.target}`} className="showcase-diagram-edge">
                        <line x1={line.x1} y1={line.y1} x2={line.x2} y2={line.y2} />
                        {edge.label ? (
                          <text x={(line.x1 + line.x2) / 2} y={(line.y1 + line.y2) / 2 - 8}>
                            {truncateLabel(edge.label, 18)}
                          </text>
                        ) : null}
                      </g>
                    );
                  })}
                  {diagram.nodes.map((node) => {
                    const selected = node.id === selectedDiagramNode?.id;
                    return (
                      <g
                        key={node.id}
                        role="button"
                        tabIndex={0}
                        className={`showcase-diagram-node ${selected ? 'selected' : ''}`}
                        data-testid={`showcase-diagram-node:${node.id}`}
                        aria-pressed={selected}
                        transform={`translate(${nodeX(node)} ${nodeY(node)})`}
                        onClick={() => setSelectedDiagramNodeId(node.id)}
                        onKeyDown={(event) => {
                          if (event.key === 'Enter' || event.key === ' ') {
                            event.preventDefault();
                            setSelectedDiagramNodeId(node.id);
                          }
                        }}
                      >
                        <rect width={nodeWidth(node)} height={nodeHeight(node)} rx="8" />
                        <text x="14" y="24" className="showcase-diagram-node-title">
                          {truncateLabel(node.label ?? node.id, 22)}
                        </text>
                        <text x="14" y="46" className="showcase-diagram-node-meta">
                          {truncateLabel(node.operatorRef ?? node.kind ?? 'node', 24)}
                        </text>
                      </g>
                    );
                  })}
                </svg>
                <aside className="showcase-node-inspector" data-testid="showcase-node-inspector">
                  {selectedDiagramNode ? (
                    <>
                      <h4>{selectedDiagramNode.label ?? selectedDiagramNode.id}</h4>
                      <dl>
                        <div>
                          <dt>Node</dt>
                          <dd>
                            <code>{selectedDiagramNode.id}</code>
                          </dd>
                        </div>
                        <div>
                          <dt>Kind</dt>
                          <dd>{selectedDiagramNode.kind ?? 'node'}</dd>
                        </div>
                        <div>
                          <dt>Operator</dt>
                          <dd>{selectedDiagramNode.operatorRef ?? 'n/a'}</dd>
                        </div>
                        <div>
                          <dt>Group</dt>
                          <dd>{selectedDiagramNode.group ?? 'none'}</dd>
                        </div>
                      </dl>
                      {selectedDiagramAnnotations.length > 0 ? (
                        <ul className="showcase-annotations">
                          {selectedDiagramAnnotations.map(([key, value]) => (
                            <li key={key}>
                              <span>{key}</span>
                              <strong>{annotationValue(value)}</strong>
                            </li>
                          ))}
                        </ul>
                      ) : null}
                    </>
                  ) : (
                    <p className="muted">No node selected.</p>
                  )}
                </aside>
              </div>
            ) : null}
          </section>

          <div className="showcase-grid">
            <section className="showcase-panel">
              <h3>Run</h3>
              <dl className="showcase-run">
                <div>
                  <dt>Mode</dt>
                  <dd>{selectedScenario.run?.mode ?? 'request'}</dd>
                </div>
                <div>
                  <dt>Method</dt>
                  <dd>{selectedScenario.run?.method ?? 'GET'}</dd>
                </div>
                <div>
                  <dt>Path</dt>
                  <dd>
                    <code>{selectedScenario.run?.pathTemplate ?? '/'}</code>
                  </dd>
                </div>
                <div>
                  <dt>Presets</dt>
                  <dd>{presets.length}</dd>
                </div>
              </dl>
              {presets.length > 0 ? (
                <div className="showcase-presets" aria-label="Sample presets">
                  {presets.map((preset) => (
                    <button
                      key={preset.label ?? previewJson(preset.values)}
                      type="button"
                      className="showcase-preset-button"
                      data-testid={`showcase-preset:${preset.label ?? 'preset'}`}
                      onClick={() => applyPreset(preset)}
                    >
                      <strong>{preset.label ?? 'Preset'}</strong>
                      <span>
                        {[preset.expected?.ruleId, preset.expected?.decision].filter(Boolean).join(' / ') || 'sample'}
                      </span>
                    </button>
                  ))}
                </div>
              ) : null}
              {inputKeys.length > 0 ? (
                <form className="showcase-inputs" onSubmit={(event) => {
                  event.preventDefault();
                  void runSelectedScenario();
                }}
                >
                  {inputKeys.map((key) => (
                    <label key={key}>
                      <span>{key}</span>
                      <input
                        data-testid={`showcase-input:${key}`}
                        name={key}
                        value={inputValues[key] ?? ''}
                        onChange={(event) => updateInput(key, event.currentTarget.value)}
                      />
                    </label>
                  ))}
                  <button
                    type="submit"
                    className="primary compact"
                    data-testid="showcase-run-button"
                    disabled={runState.status === 'running'}
                  >
                    {runState.status === 'running' ? 'Running...' : 'Run'}
                  </button>
                </form>
              ) : (
                <button
                  type="button"
                  className="primary compact"
                  data-testid="showcase-run-button"
                  disabled={runState.status === 'running'}
                  onClick={() => void runSelectedScenario()}
                >
                  {runState.status === 'running' ? 'Running...' : 'Run'}
                </button>
              )}
            </section>

            <section className="showcase-panel">
              <h3>Sample Input</h3>
              <pre className="showcase-sample" data-testid="showcase-sample">
                {previewJson(inputValues)}
              </pre>
            </section>

            <section
              className={`showcase-panel showcase-run-result ${runState.status}`}
              data-testid="showcase-run-result"
            >
              <div className="showcase-panel-heading">
                <h3>Output</h3>
                <span>{runState.status}</span>
              </div>
              <dl className="showcase-run">
                <div>
                  <dt>Status</dt>
                  <dd>{runState.message}</dd>
                </div>
                <div>
                  <dt>URL</dt>
                  <dd>
                    <code>{runState.url || 'n/a'}</code>
                  </dd>
                </div>
              </dl>
              <pre className="showcase-sample">
                {previewJson(runState.payload)}
              </pre>
            </section>
          </div>

          {selectedScenario.decisionTable ? (
            <section className="showcase-decision-table" data-testid="showcase-decision-table">
              <h3>Decision Table</h3>
              <div className="showcase-metrics">
                <span>
                  Hit policy <strong>{selectedScenario.decisionTable.hitPolicy ?? 'unknown'}</strong>
                </span>
                <span>
                  Rows <strong>{decisionRows.length}</strong>
                </span>
                <span>
                  Columns <strong>{decisionColumns.length}</strong>
                </span>
              </div>
            </section>
          ) : null}
        </section>
      ) : (
        <section className="showcase-empty" data-testid="showcase-detail" role="status">
          <h2>
            {status === 'loading'
              ? 'Loading catalog'
              : status === 'error'
                ? 'Catalog unavailable'
                : 'No scenarios available'}
          </h2>
          <p>
            {status === 'loading'
              ? 'Reading the resource-gateway scenario catalog.'
              : status === 'error'
                ? errorMessage
                : 'The backend catalog returned an empty list.'}
          </p>
        </section>
      )}
    </main>
  );
}
