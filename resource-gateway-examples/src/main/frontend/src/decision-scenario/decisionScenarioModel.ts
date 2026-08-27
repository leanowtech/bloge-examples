import type { ScenarioDraftSet } from '../contract-scenario/domain';
import { sha256FingerprintSync } from '../contract-scenario/fingerprint';
import {
  enumerateDecisionTableScenarios,
  type DecisionOutputKind,
  type DecisionTable,
  type EnumerationOptions,
} from './decisionScenario';

/** Snapshot shape shared by the existing decision-table editor and the enumerator. */
export interface DecisionEditorSnapshot {
  hitPolicy: string;
  outputType: string;
  conditionColumns: Array<{ id: string }>;
  outputColumns: Array<{ id: string }>;
  rows: Array<{ conditions: Record<string, string>; outputs: Record<string, string>; otherwise: boolean }>;
  outputKind?: DecisionOutputKind;
}

/** Converts the existing editor model into the bounded D0 decision-table model. */
export function decisionTableFromEditor(editor: DecisionEditorSnapshot, tableId?: string): DecisionTable {
  return {
    tableId,
    hitPolicy: editor.hitPolicy === 'first' ? 'first' : 'unique',
    columns: editor.conditionColumns.map((column) => ({ name: column.id, type: inferColumnType(editor, column.id) })),
    rules: editor.rows.map((row, index) => ({ id: `rule-${index + 1}`, conditions: Object.fromEntries(Object.entries(row.conditions).map(([column, value]) => [column, normalizeCondition(column, value)])), otherwise: row.otherwise, output: parseOutput(row.outputs) })),
    outputKind: editor.outputKind ?? 'object',
  };
}

/** Computes the current source coordinate used by stale detection. */
export function decisionTableSourceFingerprint(editor: DecisionEditorSnapshot, tableId?: string): string {
  return sha256FingerprintSync(decisionTableFromEditor(editor, tableId));
}

/** Re-enumerates from the current editor snapshot with explicit persisted target metadata. */
export function enumerateFromEditor(editor: DecisionEditorSnapshot, options: Omit<EnumerationOptions, 'target'> & { target: EnumerationOptions['target'] }, tableId?: string) {
  return enumerateDecisionTableScenarios(decisionTableFromEditor(editor, tableId), options);
}

/** Indicates whether a stored/generated set was produced from a different decision table. */
export function scenarioSetIsStale(editor: DecisionEditorSnapshot, draftSet: ScenarioDraftSet | null, tableId?: string): boolean {
  if (!draftSet) return false;
  const source = draftSet.metadata.provenance.sourceFingerprint;
  return typeof source === 'string' && source !== decisionTableSourceFingerprint(editor, tableId);
}

/**
 * Identifies a generated set that can be reopened in the matching operator Scenarios surface.
 *
 * <p>The operator reference, target coordinate, and Contract fingerprint are checked because an
 * operator name alone is not an authoritative contract identity.</p>
 */
export function scenarioSetMatchesOperator(
  draftSet: ScenarioDraftSet | null,
  operatorRef: string,
  target: ScenarioDraftSet['target'],
  contractFingerprint: string,
): boolean {
  return Boolean(
    draftSet
      && draftSet.metadata.provenance?.operatorRef === operatorRef
      && draftSet.target.kind === target.kind
      && draftSet.target.id === target.id
      && draftSet.target.revision === target.revision
      && draftSet.target.fingerprint === target.fingerprint
      && draftSet.contractFingerprint === contractFingerprint,
  );
}

function inferColumnType(editor: DecisionEditorSnapshot, id: string): 'integer' | 'number' | 'string' | 'enum' | 'boolean' {
  const expressions = editor.rows.map((row) => row.conditions[id] ?? '').join(' ');
  if (/\b(?:true|false)\b/i.test(expressions)) return 'boolean';
  if (/\bin\s*\[/.test(expressions)) return 'enum';
  if (new RegExp(`${escapeRegExp(id)}\\s*(?:<=|>=|==|!=|<|>)\\s*-?\\d+`).test(expressions) || new RegExp(`-?\\d+\\s*(?:<=|>=|==|!=|<|>)\\s*${escapeRegExp(id)}`).test(expressions)) return 'integer';
  return 'string';
}

function parseOutput(outputs: Record<string, string>): unknown {
  const entries = Object.entries(outputs).map(([key, value]) => [key, parseCell(value)] as const);
  return Object.fromEntries(entries);
}

function normalizeCondition(column: string, value: string): string {
  const trimmed = value.trim();
  return /^(?:<=|>=|==|!=|<|>)\s*-?(?:\d+(?:\.\d*)?|\.\d+)/.test(trimmed) ? `${column} ${trimmed}` : trimmed;
}

function parseCell(value: string): unknown {
  const trimmed = value.trim();
  if (!trimmed) return '';
  try { return JSON.parse(trimmed); } catch { return trimmed; }
}

function escapeRegExp(value: string): string { return value.replace(/[.*+?^${}()|[\[\]\\]/g, '\\$&'); }
