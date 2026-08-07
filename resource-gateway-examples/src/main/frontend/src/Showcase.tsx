import { useEffect, useMemo, useRef, useState } from 'react';

import { buildGatewayRunRequest, fetchGatewayDiagram, fetchGatewayScenarios, runGatewayScenario } from './api';
import { useI18n } from './i18n/I18nProvider';
import type { MessageDescriptor } from './i18n/messageCatalog';
import { presentShowcaseScenario } from './showcasePresentation';
import type {
  GatewayDiagramEdge,
  GatewayDiagramGroup,
  GatewayDiagramNode,
  GatewayDecisionColumn,
  GatewayExampleDiagram,
  GatewayExamplePreset,
  GatewayExampleRunRequest,
  GatewayExampleScenario,
} from './types';

const DEFAULT_NODE_WIDTH = 180;
const DEFAULT_NODE_HEIGHT = 72;
const DIAGRAM_PADDING = 48;
const STREAM_EVENT_NAMES = ['meta', 'token', 'citation'] as const;

type StreamEventName = (typeof STREAM_EVENT_NAMES)[number];
type ShowcaseRunStatus = 'idle' | 'running' | 'success' | 'error' | 'streaming';

interface ShowcaseRunState {
  status: ShowcaseRunStatus;
  url: string;
  message: MessageDescriptor;
  payload: unknown;
  request: ShowcaseRunRequestSummary | null;
}

interface ShowcaseRunRequestSummary {
  mode: string;
  method: string;
  url: string;
  bodyPreview: string;
}

interface ShowcaseExpectationCheck {
  key: string;
  expected: unknown;
  state: 'pending' | 'matched' | 'missing';
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
  return { status: 'idle', url: '', message: { messageId: 'showcase.run.notRun' }, payload: null, request: null };
}

function streamFrames(payload: unknown): Record<StreamEventName, unknown[]> {
  const source = (payload && typeof payload === 'object' ? payload : {}) as Record<string, unknown>;
  return Object.fromEntries(
    STREAM_EVENT_NAMES.map((eventName) => [
      eventName,
      Array.isArray(source[eventName]) ? source[eventName] : [],
    ]),
  ) as Record<StreamEventName, unknown[]>;
}

function scalarValue(value: unknown): string {
  if (value === null || value === undefined) {
    return '';
  }
  if (typeof value === 'object') {
    return previewJson(value).replace(/\s+/g, ' ');
  }
  return String(value);
}

function decisionColumnLabel(column: GatewayDecisionColumn): string {
  return column.label?.trim() || column.key;
}

function valuesMatch(actual: unknown, expected: unknown): boolean {
  if (actual === expected) {
    return true;
  }
  if (typeof actual !== 'object' && typeof expected !== 'object') {
    return String(actual) === String(expected);
  }
  return scalarValue(actual) === scalarValue(expected);
}

function payloadContainsExpected(payload: unknown, key: string, expected: unknown): boolean {
  if (!payload || typeof payload !== 'object') {
    return false;
  }
  if (Array.isArray(payload)) {
    return payload.some((item) => payloadContainsExpected(item, key, expected));
  }
  const record = payload as Record<string, unknown>;
  if (Object.prototype.hasOwnProperty.call(record, key) && valuesMatch(record[key], expected)) {
    return true;
  }
  return Object.values(record).some((value) => payloadContainsExpected(value, key, expected));
}

function recordsEqual(left: Record<string, string>, right: Record<string, string>): boolean {
  const leftKeys = Object.keys(left);
  const rightKeys = Object.keys(right);
  return leftKeys.length === rightKeys.length && leftKeys.every((key) => left[key] === right[key]);
}

function matchingPreset(
  scenario: GatewayExampleScenario | undefined,
  presets: GatewayExamplePreset[],
  values: Record<string, string>,
): GatewayExamplePreset | undefined {
  return presets.find((preset) => recordsEqual(scenarioInputValues(scenario, preset), values));
}

function expectationChecks(
  preset: GatewayExamplePreset | undefined,
  payload: unknown,
  status: ShowcaseRunStatus,
): ShowcaseExpectationCheck[] {
  return Object.entries(preset?.expected ?? {}).map(([key, expected]) => {
    const state = status === 'success'
      ? payloadContainsExpected(payload, key, expected) ? 'matched' : 'missing'
      : 'pending';
    return { key, expected, state };
  });
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

function requestBodyPreview(request: GatewayExampleRunRequest): string {
  const body = request.init.body;
  if (body === null || body === undefined) {
    return '';
  }
  if (typeof body === 'string') {
    return body;
  }
  if (body instanceof URLSearchParams) {
    return body.toString();
  }
  if (typeof FormData !== 'undefined' && body instanceof FormData) {
    return '[form data]';
  }
  if (typeof Blob !== 'undefined' && body instanceof Blob) {
    return `[blob ${body.type || 'application/octet-stream'}]`;
  }
  return '[body not previewable]';
}

function runRequestSummary(request: GatewayExampleRunRequest): ShowcaseRunRequestSummary {
  return {
    mode: request.mode,
    method: String(request.init.method ?? 'GET').toUpperCase(),
    url: request.url,
    bodyPreview: requestBodyPreview(request),
  };
}

/** Read-only scenario browser for the resource-gateway examples catalog. */
export default function Showcase() {
  const { t, d, m } = useI18n();
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
  const selectedPresentation = selectedScenario
    ? presentShowcaseScenario(selectedScenario)
    : null;
  const selectedTitle = selectedPresentation
    ? m(selectedPresentation.title.messageId, selectedPresentation.title.params)
    : selectedScenario?.title ?? '';
  const presets = selectedScenario?.samplePresets ?? [];
  const decisionTable = selectedScenario?.decisionTable ?? null;
  const decisionRows = decisionTable?.rows ?? [];
  const decisionInputColumns = decisionTable?.inputs ?? [];
  const decisionOutputColumns = decisionTable?.outputs ?? [];
  const decisionTableColumnSpan = 2
    + Math.max(decisionInputColumns.length, 1)
    + Math.max(decisionOutputColumns.length, 1);
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
  const runStreamFrames = streamFrames(runState.payload);
  const activePreset = matchingPreset(selectedScenario, presets, inputValues);
  const runExpectationChecks = expectationChecks(activePreset, runState.payload, runState.status);
  const matchedExpectationCount = runExpectationChecks.filter((check) => check.state === 'matched').length;

  function closeStream() {
    streamSourceRef.current?.close();
    streamSourceRef.current = null;
  }

  function stopStream() {
    closeStream();
    setRunState((current) => ({
      ...current,
      status: current.status === 'streaming' ? 'success' : current.status,
      message: { messageId: 'showcase.run.streamStopped' },
    }));
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
        message: { messageId: 'showcase.run.recipeMissing' },
        payload: null,
        request: null,
      });
      return;
    }
    closeStream();
    const request = buildGatewayRunRequest(run, values);
    const requestSummary = runRequestSummary(request);
    setRunState({
      status: request.mode === 'stream' ? 'streaming' : 'running',
      url: request.url,
      message: { messageId: request.mode === 'stream'
        ? 'showcase.run.openingStream'
        : 'showcase.run.running' },
      payload: request.mode === 'stream' ? { meta: [], token: [], citation: [] } : null,
      request: requestSummary,
    });
    if (request.mode === 'stream') {
      if (typeof EventSource === 'undefined') {
        setRunState({
          status: 'error',
          url: request.url,
          message: { messageId: 'showcase.run.eventSourceUnavailable' },
          payload: null,
          request: requestSummary,
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
            message: { messageId: 'showcase.run.streaming' },
            payload: { ...frames },
            request: requestSummary,
          });
        });
      });
      source.onerror = () => {
        closeStream();
        setRunState((current) => ({
          ...current,
          status: current.status === 'streaming' ? 'success' : current.status,
          message: { messageId: 'showcase.run.streamClosed' },
        }));
      };
      return;
    }
    try {
      const result = await runGatewayScenario(run, values);
      setRunState({
        status: 'success',
        url: result.url,
        message: { messageId: 'showcase.run.httpStatus', params: { status: result.status } },
        payload: result.payload,
        request: requestSummary,
      });
    } catch (error) {
      setRunState({
        status: 'error',
        url: request.url,
        message: {
          messageId: 'showcase.run.failed',
          rawDetail: error instanceof Error ? error.message : 'Gateway run failed.',
        },
        payload: null,
        request: requestSummary,
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
      <aside className="showcase-sidebar" aria-label={t('Gateway scenarios')}>
        <div className="showcase-sidebar-heading">
          <span>{t('Scenarios')}</span>
          <strong>{scenarios.length}</strong>
        </div>
        {status === 'loading' && scenarios.length === 0 ? (
          <p className="showcase-status">{t('Loading catalog...')}</p>
        ) : null}
        {status === 'error' ? (
          <p className="showcase-status error">{errorMessage}</p>
        ) : null}
        <div className="showcase-list">
          {scenarios.map((scenario) => {
            const presentation = presentShowcaseScenario(scenario);
            return (
              <button
                key={scenario.graphName}
                type="button"
                className="showcase-scenario-button"
                data-testid={`showcase-scenario:${scenario.graphName}`}
                aria-current={scenario.graphName === selectedScenario?.graphName ? 'true' : undefined}
                onClick={() => setSelectedGraphName(scenario.graphName)}
              >
                <strong>{presentation
                  ? m(presentation.title.messageId, presentation.title.params)
                  : scenario.title}</strong>
                <span>{presentation
                  ? m(presentation.pattern.messageId, presentation.pattern.params)
                  : scenario.pattern}</span>
                <code>{scenario.graphName}</code>
              </button>
            );
          })}
        </div>
      </aside>

      {selectedScenario ? (
        <section className="showcase-detail" data-testid="showcase-detail">
          <div className="showcase-header">
            <div>
              <p className="eyebrow">{selectedPresentation
                ? m(selectedPresentation.pattern.messageId, selectedPresentation.pattern.params)
                : selectedScenario.pattern}</p>
              <h2>{selectedTitle}</h2>
              <p>{selectedPresentation
                ? m(selectedPresentation.description.messageId, selectedPresentation.description.params)
                : selectedScenario.description}</p>
            </div>
            <details className="showcase-actions">
              <summary>{t('Advanced')}</summary>
              <div>
                <a className="link" href={selectedScenario.diagramPath ?? '#'}>
                  {t('Diagram JSON')}
                </a>
                <a className="link" href="/examples/gateway">
                  {t('Legacy runner')}
                </a>
              </div>
            </details>
          </div>

          <div className="showcase-tags" aria-label={t('Scenario concepts')}>
            {(selectedPresentation?.concepts ?? conceptList(selectedScenario)).map((concept) => {
              const key = typeof concept === 'string' ? concept : concept.messageId;
              return (
                <span key={key} className="showcase-chip">
                  {typeof concept === 'string' ? concept : m(concept.messageId, concept.params)}
                </span>
              );
            })}
          </div>

          <section className="showcase-diagram-panel" data-testid="showcase-diagram">
            <div className="showcase-panel-heading">
              <h3>{t('Diagram')}</h3>
              {diagram ? (
                <span>{t('{nodes} nodes · {edges} edges', {
                  nodes: diagram.nodes.length,
                  edges: diagram.edges.length,
                })}</span>
              ) : null}
            </div>
            {diagramStatus === 'loading' ? (
              <p className="showcase-status">{t('Loading diagram...')}</p>
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
                  aria-label={t('{title} graph diagram', { title: selectedTitle })}
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
                          <dt>{t('Node')}</dt>
                          <dd>
                            <code>{selectedDiagramNode.id}</code>
                          </dd>
                        </div>
                        <div>
                          <dt>{t('Kind')}</dt>
                          <dd>{selectedDiagramNode.kind ?? 'node'}</dd>
                        </div>
                        <div>
                          <dt>{t('Operator')}</dt>
                          <dd>{selectedDiagramNode.operatorRef ?? 'n/a'}</dd>
                        </div>
                        <div>
                          <dt>{t('Group')}</dt>
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
                    <p className="muted">{t('No node selected.')}</p>
                  )}
                </aside>
              </div>
            ) : null}
          </section>

          <div className="showcase-grid">
            <section className="showcase-panel">
              <h3>{t('Run')}</h3>
              <dl className="showcase-run">
                <div>
                  <dt>{t('Mode')}</dt>
                  <dd>{selectedScenario.run?.mode ?? 'request'}</dd>
                </div>
                <div>
                  <dt>{t('Method')}</dt>
                  <dd>{selectedScenario.run?.method ?? 'GET'}</dd>
                </div>
                <div>
                  <dt>{t('Path')}</dt>
                  <dd>
                    <code>{selectedScenario.run?.pathTemplate ?? '/'}</code>
                  </dd>
                </div>
                <div>
                  <dt>{t('Presets')}</dt>
                  <dd>{presets.length}</dd>
                </div>
              </dl>
              {presets.length > 0 ? (
                <div className="showcase-presets" aria-label={t('Sample presets')}>
                  {presets.map((preset) => (
                    <button
                      key={preset.label ?? previewJson(preset.values)}
                      type="button"
                      className="showcase-preset-button"
                      data-testid={`showcase-preset:${preset.label ?? 'preset'}`}
                      onClick={() => applyPreset(preset)}
                    >
                      <strong>{preset.label ?? t('Preset')}</strong>
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
                    {runState.status === 'running' ? t('Running...') : t('Run')}
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
                  {runState.status === 'running' ? t('Running...') : t('Run')}
                </button>
              )}
            </section>

            <section className="showcase-panel">
              <h3>{t('Sample Input')}</h3>
              <pre className="showcase-sample" data-testid="showcase-sample">
                {previewJson(inputValues)}
              </pre>
            </section>

            <section
              className={`showcase-panel showcase-run-result ${runState.status}`}
              data-testid="showcase-run-result"
            >
              <div className="showcase-panel-heading">
                <h3>{t('Output')}</h3>
                <div className="showcase-output-actions">
                  <span>{d(runState.status)}</span>
                  {runState.status === 'streaming' ? (
                    <button
                      type="button"
                      className="secondary compact"
                      data-testid="showcase-stop-stream"
                      onClick={stopStream}
                    >
                      {t('Stop')}
                    </button>
                  ) : null}
                </div>
              </div>
              <dl className="showcase-run">
                <div>
                  <dt>{t('Status')}</dt>
                  <dd>{m(runState.message.messageId, runState.message.params)}</dd>
                </div>
                <div>
                  <dt>{t('URL')}</dt>
                  <dd>
                    <code>{runState.url || 'n/a'}</code>
                  </dd>
                </div>
              </dl>
              {runState.message.rawDetail && (
                <details className="showcase-run-technical" data-testid="showcase-run-technical">
                  <summary>{t('Technical details')}</summary>
                  <p lang="en">{runState.message.rawDetail}</p>
                </details>
              )}
              {runState.request ? (
                <div className="showcase-run-receipt" data-testid="showcase-run-receipt">
                  <div>
                    <span>{t('Mode')}</span>
                    <strong>{runState.request.mode}</strong>
                  </div>
                  <div>
                    <span>{t('Method')}</span>
                    <strong>{runState.request.method}</strong>
                  </div>
                  <div className="wide">
                    <span>{t('Endpoint')}</span>
                    <code>{runState.request.url}</code>
                  </div>
                  {runState.request.bodyPreview ? (
                    <div className="wide">
                      <span>{t('Body')}</span>
                      <code>{runState.request.bodyPreview}</code>
                    </div>
                  ) : null}
                </div>
              ) : null}
              {(runState.status === 'streaming' || selectedScenario.run?.mode === 'stream') ? (
                <div className="showcase-stream-lanes" data-testid="showcase-stream-lanes">
                  {STREAM_EVENT_NAMES.map((eventName) => (
                    <div
                      key={eventName}
                      className="showcase-stream-lane"
                      data-testid={`showcase-stream-lane:${eventName}`}
                    >
                      <span>{eventName}</span>
                      <strong>{runStreamFrames[eventName].length}</strong>
                    </div>
                  ))}
                </div>
              ) : null}
              {runExpectationChecks.length > 0 ? (
                <div className="showcase-expectations" data-testid="showcase-expectations">
                  <div className="showcase-expectations-heading">
                    <span>{t('{preset} expectations', {
                      preset: activePreset?.label ?? t('Preset'),
                    })}</span>
                    <strong>{matchedExpectationCount}/{runExpectationChecks.length}</strong>
                  </div>
                  <div className="showcase-expectation-list">
                    {runExpectationChecks.map((check) => (
                      <span
                        key={check.key}
                        className={`showcase-expectation ${check.state}`}
                        data-testid={`showcase-expectation:${check.key}`}
                      >
                        <span>{check.key}</span>
                        <strong>{scalarValue(check.expected)}</strong>
                        <em>{d(check.state)}</em>
                      </span>
                    ))}
                  </div>
                </div>
              ) : null}
              <pre className="showcase-sample">
                {previewJson(runState.payload)}
              </pre>
            </section>
          </div>

          {decisionTable ? (
            <section className="showcase-decision-table" data-testid="showcase-decision-table">
              <div className="decision-table-heading">
                <h3>{decisionTable.title ?? t('Decision Table')}</h3>
              </div>
              <div className="showcase-metrics">
                <span>
                  {t('Hit policy')} <strong>{decisionTable.hitPolicy ?? t('unknown')}</strong>
                </span>
                <span>
                  {t('Rules (OR)')} <strong>{decisionRows.length}</strong>
                </span>
                <span>
                  {t('Conditions (AND)')} <strong>{decisionInputColumns.length}</strong>
                </span>
                <span>
                  {t('Actions')} <strong>{decisionOutputColumns.length}</strong>
                </span>
              </div>
              <div className="decision-table-scroll">
                <table className="decision-rule-table">
                  <thead>
                    <tr>
                      <th scope="col" rowSpan={2}>{t('Rule')}</th>
                      <th
                        scope="colgroup"
                        colSpan={Math.max(decisionInputColumns.length, 1)}
                        className="condition-band"
                      >
                        {t('Conditions (AND)')}
                      </th>
                      <th
                        scope="colgroup"
                        colSpan={Math.max(decisionOutputColumns.length, 1)}
                        className="action-band"
                      >
                        {t('Decision actions')}
                      </th>
                      <th scope="col" rowSpan={2}>{t('Explanation')}</th>
                    </tr>
                    <tr>
                      {decisionInputColumns.length ? (
                        decisionInputColumns.map((column) => (
                          <th key={`input:${column.key}`} scope="col" className="condition-column">
                            {decisionColumnLabel(column)}
                          </th>
                        ))
                      ) : (
                        <th scope="col" className="condition-column">{t('Any input')}</th>
                      )}
                      {decisionOutputColumns.length ? (
                        decisionOutputColumns.map((column) => (
                          <th key={`output:${column.key}`} scope="col" className="action-column">
                            {decisionColumnLabel(column)}
                          </th>
                        ))
                      ) : (
                        <th scope="col" className="action-column">{t('No action')}</th>
                      )}
                    </tr>
                  </thead>
                  <tbody>
                    {decisionRows.length ? (
                      decisionRows.map((row) => (
                        <tr key={row.id} data-testid={`showcase-decision-row:${row.id}`}>
                          <th scope="row">{row.id}</th>
                          {decisionInputColumns.length ? (
                            decisionInputColumns.map((column) => (
                              <td key={`${row.id}:condition:${column.key}`} className="condition-cell">
                                <code>{scalarValue(row.conditions?.[column.key]) || t('any')}</code>
                              </td>
                            ))
                          ) : (
                            <td className="condition-cell"><code>{t('any')}</code></td>
                          )}
                          {decisionOutputColumns.length ? (
                            decisionOutputColumns.map((column) => (
                              <td key={`${row.id}:output:${column.key}`} className="action-cell">
                                {scalarValue(row.output?.[column.key]) || '-'}
                              </td>
                            ))
                          ) : (
                            <td className="action-cell">-</td>
                          )}
                          <td className="explanation-cell">{row.explanation || '-'}</td>
                        </tr>
                      ))
                    ) : (
                      <tr>
                        <td colSpan={decisionTableColumnSpan} className="decision-table-empty">
                          {t('No decision rules supplied.')}
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </section>
          ) : null}
        </section>
      ) : (
        <section className="showcase-empty" data-testid="showcase-detail" role="status">
          <h2>
            {status === 'loading'
              ? t('Loading catalog')
              : status === 'error'
                ? t('Catalog unavailable')
                : t('No scenarios available')}
          </h2>
          <p>
            {status === 'loading'
              ? t('Reading the resource-gateway scenario catalog.')
              : status === 'error'
                ? errorMessage
                : t('The backend catalog returned an empty list.')}
          </p>
        </section>
      )}
    </main>
  );
}
