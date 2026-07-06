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
import type {
  DraftNodeBinding,
  OperatorDefinition,
  OperatorPort,
  SimulationResponse,
  VisualDiagnostic,
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
  type CanvasExampleTemplate,
} from './canvasExamples';

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

type CanvasFlowEdge = Edge & {
  sourcePath?: string;
  targetPath?: string;
  bindingKey?: string;
};

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

function OperatorNode({ id, data, selected }: NodeProps<NodeData>) {
  const status = data.status ?? 'unknown';
  const inputPorts = data.summary.inputNames;
  const outputPorts = data.summary.outputNames.length ? data.summary.outputNames : [''];
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
}: {
  node: Node<NodeData>;
  incomingColumns: DecisionTableColumn[];
  onClose: () => void;
  onChange: (editor: DecisionTableEditorModel) => void;
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
    <div className="rule-editor-backdrop" role="presentation">
      <section
        className="rule-editor"
        role="dialog"
        aria-modal="true"
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

function TransformAssignmentEditor({
  node,
  onClose,
  onChange,
}: {
  node: Node<NodeData>;
  onClose: () => void;
  onChange: (editor: TransformEditorModel) => void;
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
    <div className="rule-editor-backdrop" role="presentation">
      <section
        className="rule-editor transform-editor"
        role="dialog"
        aria-modal="true"
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
              {editor.assignments.map((assignment, index) => (
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
                    />
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
              ))}
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
  if (summary.visualKind === 'decision-table') {
    return [
      { key: 'conditions', label: 'Condition inputs', value: inputSignature },
      { key: 'decision', label: 'Decision output', value: outputSignature },
      { key: 'rules', label: 'Rule matrix', value: 'typed conditions -> matched row' },
      { key: 'lowering', label: 'Lowering', value: operator?.lowering?.mode || 'dsl' },
      ...readiness,
    ];
  }
  if (summary.visualKind === 'foreach') {
    return [
      { key: 'collection', label: 'Collection', value: inputSignature },
      { key: 'item', label: 'Item context', value: itemContextLabel(inputs) },
      { key: 'result', label: 'Result list', value: outputSignature },
      { key: 'cardinality', label: 'Cardinality', value: 'per item -> aggregated list' },
      ...readiness,
    ];
  }
  if (summary.visualKind === 'resource') {
    return [
      { key: 'params', label: 'Request params', value: inputSignature },
      { key: 'payload', label: 'Response payload', value: outputSignature },
      { key: 'boundary', label: 'Boundary', value: operator?.source?.kind || summary.sourceKind },
      ...readiness,
    ];
  }
  if (summary.visualKind === 'http') {
    return [
      { key: 'request', label: 'HTTP request', value: inputSignature },
      { key: 'response', label: 'HTTP response', value: outputSignature },
      { key: 'boundary', label: 'Boundary', value: operator?.source?.kind || summary.sourceKind },
      ...readiness,
    ];
  }
  if (summary.visualKind === 'transform') {
    return [
      { key: 'source', label: 'Source fields', value: inputSignature },
      { key: 'mapped', label: 'Mapped output', value: outputSignature },
      { key: 'lowering', label: 'Lowering', value: operator?.lowering?.mode || 'transform' },
      ...readiness,
    ];
  }
  if (summary.visualKind === 'streaming') {
    return [
      { key: 'request', label: 'Request', value: inputSignature },
      { key: 'stream', label: 'Event stream', value: outputSignature },
      { key: 'boundary', label: 'Boundary', value: operator?.source?.kind || summary.sourceKind },
      ...readiness,
    ];
  }
  if (summary.visualKind === 'design') {
    return [
      { key: 'inputs', label: 'Schema input', value: inputSignature },
      { key: 'outputs', label: 'Schema output', value: outputSignature },
      { key: 'lowering', label: 'Lowering', value: operator?.lowering?.mode || 'design' },
      ...readiness,
    ];
  }
  return [
    { key: 'inputs', label: 'Input contract', value: inputSignature },
    { key: 'outputs', label: 'Output contract', value: outputSignature },
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
];

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
  const [ruleEditorNodeId, setRuleEditorNodeId] = useState('');
  const [mappingEditorNodeId, setMappingEditorNodeId] = useState('');
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
        setRuleEditorNodeId((current) => (removedNodeIds.includes(current) ? '' : current));
        setMappingEditorNodeId((current) => (removedNodeIds.includes(current) ? '' : current));
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
    const placementIndex = nextIndex - 1;
    const canvasWidth = flowRef.current?.clientWidth ?? 0;
    const summary = summarizeOperator(operator);
    setNodes((current) => [
      ...current,
      {
        id,
        type: 'operator',
        position: position ?? defaultOperatorPosition(placementIndex, canvasWidth),
        data: { label: summary.name, operatorRef: operator.operatorRef, summary },
      },
    ]);
    setSelectedNodeId(id);
  }, [clearRunResult]);

  const openNodeEditor = useCallback((node: Node<NodeData>) => {
    setSelectedNodeId(node.id);
    if (node.data.summary.visualKind === 'decision-table') {
      setRuleEditorNodeId(node.id);
      setMappingEditorNodeId('');
    } else if (node.data.summary.visualKind === 'transform') {
      setMappingEditorNodeId(node.id);
      setRuleEditorNodeId('');
    }
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

  const canvasNodes = useMemo<CanvasNode[]>(
    () =>
      nodes.map((node) => ({
        id: node.id,
        operatorRef: node.data.operatorRef,
        label: node.data.label,
        inputs: node.data.inputs,
        config: node.data.config,
        position: node.position,
      })),
    [nodes],
  );
  const canvasEdges = useMemo<CanvasEdge[]>(
    () =>
      edges.map((edge) => {
        const pathEdge = edge as CanvasFlowEdge;
        const edgeData = edge.data as { sourcePath?: string; targetPath?: string; bindingKey?: string } | undefined;
        return {
          id: edge.id,
          source: edge.source,
          target: edge.target,
          sourcePort: portNameFromHandle(edge.sourceHandle, 'out'),
          targetPort: portNameFromHandle(edge.targetHandle, 'in'),
          sourcePath: pathEdge.sourcePath ?? edgeData?.sourcePath,
          targetPath: pathEdge.targetPath ?? edgeData?.targetPath,
          bindingKey: pathEdge.bindingKey ?? edgeData?.bindingKey,
        };
      }),
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
    for (const templateNode of template.nodes) {
      const operator = operatorByRef.get(templateNode.operatorRef);
      if (!operator) {
        setConnectionNotice({
          level: 'warning',
          message: `Example needs missing operator ${templateNode.operatorRef}.`,
        });
        return;
      }
      nextNodes.push({
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
      });
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

    clearRunResult();
    counter.current = maxNumericNodeId(template.nodes);
    setNodes(nextNodes);
    setEdges(nextEdges);
    setFixtureDrafts(nextFixtureDrafts);
    setFixtureInputDrafts(nextFixtureInputDrafts);
    setExplicitOutputNodeId(template.outputNodeId);
    setSelectedNodeId(template.outputNodeId);
    setRuleEditorNodeId('');
    setMappingEditorNodeId('');
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
  const ruleEditorNode = nodes.find((node) => node.id === ruleEditorNodeId);
  const ruleEditorIncomingColumns = ruleEditorNode
    ? decisionTableIncomingConditionColumns(ruleEditorNode, edges, nodes)
    : [];
  const mappingEditorNode = nodes.find((node) => node.id === mappingEditorNodeId);
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
        'visualGraph',
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
            'visualGraph',
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
              'visualGraph',
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
  }, [canvasEdges, canvasNodes, clearRunResult, outputNodeId]);

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
                      message: `Loaded ${example.label} example. Validate before importing.`,
                    });
                    setLibraryDiagnostics([]);
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
                        <span className={`op-kind ${summary.visualKind}`}>{summary.visualLabel}</span>
                        <span className="op-name">{summary.name}</span>
                        <span className="op-ref">{summary.operatorRef}</span>
                        <span className="op-meta">
                          {summary.contractHint} · {summary.requiredInputCount}/{summary.inputCount} inputs ·{' '}
                          {summary.outputCount} outputs
                        </span>
                      </span>
                      {summary.readinessBadgeLabel && (
                        <span className={`badge readiness ${summary.readinessLevel}`}>
                          {summary.readinessBadgeLabel}
                        </span>
                      )}
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
            onNodeDoubleClick={(_, node) => openNodeEditor(node)}
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
            <OperatorFocusPanel operator={selectedOperator} summary={selectedNode.data.summary} />
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
      {ruleEditorNode && (
        <DecisionTableRuleEditor
          node={ruleEditorNode}
          incomingColumns={ruleEditorIncomingColumns}
          onClose={() => setRuleEditorNodeId('')}
          onChange={(editor) => updateDecisionTableRules(ruleEditorNode.id, editor)}
        />
      )}
      {mappingEditorNode && (
        <TransformAssignmentEditor
          node={mappingEditorNode}
          onClose={() => setMappingEditorNodeId('')}
          onChange={(editor) => updateTransformAssignments(mappingEditorNode.id, editor)}
        />
      )}
    </div>
  );
}
