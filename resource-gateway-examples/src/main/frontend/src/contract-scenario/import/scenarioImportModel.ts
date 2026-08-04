import Papa from 'papaparse';

import type {
  ScenarioCaseType,
  ScenarioDraft,
  ScenarioDraftSet,
} from '../domain';
import { canonicalJson, sha256Fingerprint } from '../fingerprint';

export type ScenarioImportKind = 'CSV' | 'JSON';
export type ScenarioImportValueSemantics = 'VALUE' | 'NULL' | 'MISSING' | 'EMPTY' | 'DEFAULT';
export type ScenarioImportConverter = 'IDENTITY' | 'STRING' | 'NUMBER' | 'BOOLEAN' | 'JSON';
export type ScenarioImportConflictPolicy = 'FAIL' | 'APPEND' | 'REPLACE_EXACT_ID';
export type ScenarioImportClassification = 'PUBLIC' | 'INTERNAL' | 'CONFIDENTIAL' | 'RESTRICTED';

export interface ScenarioImportBudget {
  maxBytes: number;
  maxRows: number;
  maxColumns: number;
  maxCellBytes: number;
  maxDepth: number;
  maxItems: number;
  sampleRows: number;
}

export interface ScenarioImportWarning {
  code: string;
  path: string;
  message: string;
}

export interface ScenarioImportColumn {
  sourcePath: string;
  label: string;
  inferredType: 'STRING' | 'NUMBER' | 'BOOLEAN' | 'NULL' | 'JSON' | 'MIXED';
  sensitive: boolean;
  missingCount: number;
  nullCount: number;
  emptyCount: number;
  formulaRiskCount: number;
}

export interface ScenarioImportRow {
  rowId: string;
  canonicalIndex: number;
  rowFingerprint: string;
  values: Record<string, unknown>;
}

export interface ScenarioImportPreview {
  schemaVersion: 'bloge.scenarioImportPreview.v1';
  source: {
    kind: ScenarioImportKind;
    fingerprint: string;
    encoding: 'UTF-8';
    delimiter: string;
    parser: string;
  };
  rowCount: number;
  columnCount: number;
  columns: ScenarioImportColumn[];
  rows: ScenarioImportRow[];
  sampleRows: Array<{ rowId: string; values: Record<string, unknown> }>;
  warnings: ScenarioImportWarning[];
  budget: ScenarioImportBudget;
}

export type ScenarioImportTarget =
  | {
    targetId: 'case:name' | 'case:type' | 'case:tags';
    group: 'CASE';
    label: string;
    path: string;
    kind: 'NAME' | 'CASE_TYPE' | 'TAGS';
  }
  | {
    targetId: string;
    group: 'GIVEN';
    label: string;
    path: string;
    kind: 'GIVEN';
    valuePath: string[];
  }
  | {
    targetId: string;
    group: 'DEPENDENCY';
    label: string;
    path: string;
    kind: 'DEPENDENCY_OUTPUT';
    dependencyId: string;
    valuePath: string[];
  }
  | {
    targetId: string;
    group: 'THEN';
    label: string;
    path: string;
    kind: 'ASSERTION_EXPECTED';
    assertionId: string;
    valuePath: string[];
  };

export interface ScenarioColumnBinding {
  bindingId: string;
  sourcePath: string;
  target: ScenarioImportTarget;
  confidence: number;
  reason: 'EXACT_PATH' | 'EXACT_NAME' | 'NORMALIZED_NAME' | 'MANUAL';
  confirmed: boolean;
  converter: ScenarioImportConverter;
  valueSemantics: ScenarioImportValueSemantics;
  defaultValue?: unknown;
}

export interface ScenarioMaterializationPlan {
  schemaVersion: 'bloge.scenarioMaterializationPlan.v1';
  source: ScenarioImportPreview['source'] & { classification: ScenarioImportClassification };
  target: ScenarioDraftSet['target'];
  contractFingerprint: string;
  bindings: ScenarioColumnBinding[];
  valueSemantics: Record<string, ScenarioImportValueSemantics>;
  rowSelection: string[];
  rowIdentityPolicy: { kind: 'CANONICAL_ROW_HASH' | 'SOURCE_COLUMN'; sourcePath: string };
  conflictPolicy: ScenarioImportConflictPolicy;
  budget: Pick<ScenarioImportBudget, 'maxBytes' | 'maxRows' | 'maxColumns'>;
  mappingFingerprint: string;
  planFingerprint: string;
}

export interface ScenarioMaterializationReceipt {
  schemaVersion: 'bloge.scenarioMaterializationReceipt.v1';
  receiptId: string;
  planFingerprint: string;
  sourceFingerprint: string;
  mappingFingerprint: string;
  contractFingerprint: string;
  targetFingerprint: string;
  rowCount: number;
  acceptedRowCount: number;
  rejectedRowCount: number;
  rowIdentityPolicy: ScenarioMaterializationPlan['rowIdentityPolicy'];
  materializedScenarioIds: string[];
  rows: Array<{
    identityFingerprint: string;
    rowFingerprint: string;
    scenarioId: string;
    status: 'CREATED' | 'REPLACED' | 'UNCHANGED' | 'REJECTED';
    diagnosticCode: string;
  }>;
  actor: string;
  materializedAt: string;
  classification: ScenarioImportClassification;
  receiptFingerprint: string;
}

export interface ScenarioMaterializationResult {
  draftSet: ScenarioDraftSet;
  receipt: ScenarioMaterializationReceipt;
}

export interface ScenarioImportExecutionRequest {
  schemaVersion: 'bloge.scenarioImportMaterializationRequest.v1';
  sourceText: string;
  plan: ScenarioMaterializationPlan;
  draftSet: ScenarioDraftSet;
  templateScenarioId: string;
}

export interface ScenarioImportDiff {
  added: string[];
  changed: string[];
  removed: string[];
  unchanged: string[];
}

export const DEFAULT_SCENARIO_IMPORT_BUDGET: ScenarioImportBudget = {
  maxBytes: 1_048_576,
  maxRows: 500,
  maxColumns: 100,
  maxCellBytes: 32_768,
  maxDepth: 16,
  maxItems: 50_000,
  sampleRows: 8,
};

const SENSITIVE_NAME = /(^|[._/\-])(password|passwd|secret|token|authorization|api.?key|ssn|email|phone)([._/\-]|$)/i;
const FORMULA_PREFIX = /^[\t\r ]*[=+@]|^[\t\r ]*-[A-Za-z0-9(]/;
const CASE_TYPES: ScenarioCaseType[] = ['GOLDEN', 'NEGATIVE', 'BOUNDARY', 'REGRESSION', 'PROPERTY'];

/** Parses one bounded source without retaining the original text in the preview model. */
export async function parseScenarioImport(
  text: string,
  kind: ScenarioImportKind,
  requestedBudget: Partial<ScenarioImportBudget> = {},
): Promise<ScenarioImportPreview> {
  const budget = normalizeBudget(requestedBudget);
  const bytes = new TextEncoder().encode(text);
  if (bytes.byteLength > budget.maxBytes) {
    throw importError('RG.SCENARIO_IMPORT.BYTES_EXCEEDED', 'Source exceeds the configured byte budget.');
  }
  if (text.includes('\u0000') || text.includes('\uFFFD')) {
    throw importError('RG.SCENARIO_IMPORT.ENCODING_INVALID', 'Source is not valid, clean UTF-8 text.');
  }
  const parsed = kind === 'CSV'
    ? parseCsv(text, budget)
    : parseJson(text, budget);
  const sourceFingerprint = await sha256Fingerprint({
    kind,
    encoding: 'UTF-8',
    delimiter: parsed.delimiter,
    parser: parsed.parser,
    text,
  });
  const duplicateOccurrences = new Map<string, number>();
  const rows: ScenarioImportRow[] = [];
  for (let index = 0; index < parsed.rows.length; index += 1) {
    const values = parsed.rows[index];
    const rowFingerprint = await sha256Fingerprint(values);
    const occurrence = (duplicateOccurrences.get(rowFingerprint) ?? 0) + 1;
    duplicateOccurrences.set(rowFingerprint, occurrence);
    rows.push({
      rowId: `row-${rowFingerprint.slice(7, 23)}-${occurrence}`,
      canonicalIndex: index,
      rowFingerprint,
      values,
    });
  }
  const columns = projectColumns(parsed.columns, rows);
  const warnings = [...parsed.warnings];
  for (const column of columns) {
    if (column.sensitive) warnings.push(warning(
      'RG.SCENARIO_IMPORT.SENSITIVE_PATH',
      column.sourcePath,
      'Potentially sensitive values are masked in preview and require governed classification.',
    ));
    if (column.formulaRiskCount > 0) warnings.push(warning(
      'RG.SCENARIO_IMPORT.FORMULA_PREFIX',
      column.sourcePath,
      'Spreadsheet formula prefixes were detected and will never be evaluated.',
    ));
  }
  return {
    schemaVersion: 'bloge.scenarioImportPreview.v1',
    source: {
      kind,
      fingerprint: sourceFingerprint,
      encoding: 'UTF-8',
      delimiter: parsed.delimiter,
      parser: parsed.parser,
    },
    rowCount: rows.length,
    columnCount: columns.length,
    columns,
    rows,
    sampleRows: rows.slice(0, budget.sampleRows).map((row) => ({
      rowId: row.rowId,
      values: maskPreviewValues(row.values, columns),
    })),
    warnings,
    budget,
  };
}

/** Derives stable mapping targets from the current canonical Scenario shape. */
export function deriveScenarioImportTargets(draftSet: ScenarioDraftSet): ScenarioImportTarget[] {
  const targets: ScenarioImportTarget[] = [
    { targetId: 'case:name', group: 'CASE', label: 'Case name', path: '/name', kind: 'NAME' },
    { targetId: 'case:type', group: 'CASE', label: 'Case type', path: '/caseType', kind: 'CASE_TYPE' },
    { targetId: 'case:tags', group: 'CASE', label: 'Tags', path: '/tags', kind: 'TAGS' },
  ];
  const seen = new Set(targets.map((target) => target.targetId));
  for (const scenario of draftSet.scenarios) {
    for (const entry of flattenValue(scenario.given.input)) {
      addTarget(targets, seen, {
        targetId: `given:${entry.pointer}`,
        group: 'GIVEN',
        label: entry.path[entry.path.length - 1] ?? 'input',
        path: `/given/input${entry.pointer}`,
        kind: 'GIVEN',
        valuePath: entry.path,
      });
    }
    for (const dependency of scenario.dependencies) {
      const output = dependency.behavior.output;
      for (const entry of flattenValue(output === undefined ? null : output)) {
        addTarget(targets, seen, {
          targetId: `dependency:${dependency.dependencyId}:output:${entry.pointer}`,
          group: 'DEPENDENCY',
          label: `${dependency.dependencyId} / ${entry.path[entry.path.length - 1] ?? 'output'}`,
          path: `/dependencies/${escapePointer(dependency.dependencyId)}/behavior/output${entry.pointer}`,
          kind: 'DEPENDENCY_OUTPUT',
          dependencyId: dependency.dependencyId,
          valuePath: entry.path,
        });
      }
    }
    for (const assertion of scenario.then.assertions) {
      for (const entry of flattenValue(assertion.expected === undefined ? null : assertion.expected)) {
        addTarget(targets, seen, {
          targetId: `assertion:${assertion.assertionId}:expected:${entry.pointer}`,
          group: 'THEN',
          label: `${assertion.assertionId} / ${entry.path[entry.path.length - 1] ?? 'expected'}`,
          path: `/then/assertions/${escapePointer(assertion.assertionId)}/expected${entry.pointer}`,
          kind: 'ASSERTION_EXPECTED',
          assertionId: assertion.assertionId,
          valuePath: entry.path,
        });
      }
    }
  }
  return targets;
}

/** Maps exact path/name first and marks normalized guesses for explicit confirmation. */
export function suggestScenarioImportBindings(
  preview: ScenarioImportPreview,
  targets: ScenarioImportTarget[],
): ScenarioColumnBinding[] {
  const usedTargets = new Set<string>();
  const bindings: ScenarioColumnBinding[] = [];
  for (const column of preview.columns) {
    const candidates = targets
      .filter((target) => !usedTargets.has(target.targetId))
      .map((target) => bindingCandidate(column, target))
      .filter((candidate): candidate is NonNullable<typeof candidate> => candidate !== null)
      .sort((left, right) => right.confidence - left.confidence || left.target.path.localeCompare(right.target.path));
    const candidate = candidates[0];
    if (!candidate) continue;
    usedTargets.add(candidate.target.targetId);
    bindings.push({
      bindingId: `${column.sourcePath}->${candidate.target.targetId}`,
      sourcePath: column.sourcePath,
      target: candidate.target,
      confidence: candidate.confidence,
      reason: candidate.reason,
      confirmed: candidate.confidence >= 0.95,
      converter: suggestedConverter(column),
      valueSemantics: column.emptyCount > 0 ? 'EMPTY' : 'VALUE',
    });
  }
  return bindings;
}

/** Freezes the exact source/mapping/Contract/target closure before materialization. */
export async function createScenarioMaterializationPlan(options: {
  preview: ScenarioImportPreview;
  draftSet: ScenarioDraftSet;
  bindings: ScenarioColumnBinding[];
  rowSelection?: string[];
  identitySourcePath?: string;
  classification?: ScenarioImportClassification;
  conflictPolicy?: ScenarioImportConflictPolicy;
}): Promise<ScenarioMaterializationPlan> {
  const selected = options.rowSelection?.length
    ? [...new Set(options.rowSelection)]
    : options.preview.rows.map((row) => row.rowId);
  const availableRows = new Set(options.preview.rows.map((row) => row.rowId));
  if (selected.some((rowId) => !availableRows.has(rowId))) {
    throw importError('RG.SCENARIO_IMPORT.SELECTION_INVALID', 'Selection references a row outside this source snapshot.');
  }
  const availableColumns = new Set(options.preview.columns.map((column) => column.sourcePath));
  validateBindings(options.bindings, availableColumns);
  const mappingFingerprint = await sha256Fingerprint(options.bindings.map(bindingFingerprintMaterial));
  const material = {
    schemaVersion: 'bloge.scenarioMaterializationPlan.v1' as const,
    source: {
      ...options.preview.source,
      classification: options.classification ?? options.draftSet.metadata.classification,
    },
    target: { ...options.draftSet.target },
    contractFingerprint: options.draftSet.contractFingerprint,
    bindings: options.bindings.map(cloneBinding),
    valueSemantics: Object.fromEntries(options.bindings.map((binding) => (
      [binding.bindingId, binding.valueSemantics]
    ))),
    rowSelection: selected,
    rowIdentityPolicy: options.identitySourcePath
      ? { kind: 'SOURCE_COLUMN' as const, sourcePath: options.identitySourcePath }
      : { kind: 'CANONICAL_ROW_HASH' as const, sourcePath: '' },
    conflictPolicy: options.conflictPolicy ?? 'FAIL',
    budget: {
      maxBytes: options.preview.budget.maxBytes,
      maxRows: options.preview.budget.maxRows,
      maxColumns: options.preview.budget.maxColumns,
    },
    mappingFingerprint,
  };
  if (options.identitySourcePath && !availableColumns.has(options.identitySourcePath)) {
    throw importError('RG.SCENARIO_IMPORT.IDENTITY_INVALID', 'The selected identity column is not present in the source.');
  }
  return { ...material, planFingerprint: await sha256Fingerprint(material) };
}

/** Materializes canonical Scenario rows deterministically and emits a payload-free receipt. */
export async function materializeScenarioImport(options: {
  preview: ScenarioImportPreview;
  plan: ScenarioMaterializationPlan;
  draftSet: ScenarioDraftSet;
  actor: string;
  materializedAt: string;
  templateScenarioId?: string;
}): Promise<ScenarioMaterializationResult> {
  validatePlanClosure(options.preview, options.plan, options.draftSet);
  const previousReceipt = options.draftSet.metadata.provenance.scenarioImportReceipt;
  if (isMaterializationReceipt(previousReceipt)
      && previousReceipt.planFingerprint === options.plan.planFingerprint) {
    return { draftSet: options.draftSet, receipt: previousReceipt };
  }
  const template = options.draftSet.scenarios.find((scenario) => (
    scenario.scenarioId === options.templateScenarioId
  )) ?? options.draftSet.scenarios[0] ?? emptyTemplate();
  const selected = new Set(options.plan.rowSelection);
  const existingById = new Map(options.draftSet.scenarios.map((scenario) => [scenario.scenarioId, scenario]));
  const imported: ScenarioDraft[] = [];
  const rowReceipts: ScenarioMaterializationReceipt['rows'] = [];
  const seenIdentities = new Set<string>();
  for (const row of options.preview.rows.filter((candidate) => selected.has(candidate.rowId))) {
    let identity = row.rowFingerprint;
    let identityFingerprint = await sha256Fingerprint({ identity });
    try {
      identity = rowIdentity(row, options.plan.rowIdentityPolicy);
      identityFingerprint = await sha256Fingerprint({ identity });
      if (seenIdentities.has(identity)) {
        throw importError('RG.SCENARIO_IMPORT.IDENTITY_DUPLICATE', 'Imported row identity must be unique.');
      }
      seenIdentities.add(identity);
      const scenarioId = `import-${(await sha256Fingerprint({
        source: options.preview.source.fingerprint,
        identity,
        target: options.plan.target,
      })).slice(7, 23)}`;
      const materialized = applyBindings(template, scenarioId, row, options.plan.bindings);
      const existing = existingById.get(scenarioId);
      const status = conflictStatus(existing, materialized, options.plan.conflictPolicy);
      if (status === 'REJECTED') {
        rowReceipts.push({
          identityFingerprint,
          rowFingerprint: row.rowFingerprint,
          scenarioId,
          status,
          diagnosticCode: 'RG.SCENARIO_IMPORT.CONFLICT',
        });
        continue;
      }
      imported.push(materialized);
      existingById.set(scenarioId, materialized);
      rowReceipts.push({
        identityFingerprint,
        rowFingerprint: row.rowFingerprint,
        scenarioId,
        status,
        diagnosticCode: '',
      });
    } catch (error) {
      rowReceipts.push({
        identityFingerprint,
        rowFingerprint: row.rowFingerprint,
        scenarioId: '',
        status: 'REJECTED',
        diagnosticCode: error instanceof ScenarioImportError
          ? error.code
          : 'RG.SCENARIO_IMPORT.ROW_INVALID',
      });
    }
  }
  const acceptedIds = rowReceipts
    .filter((row) => row.status !== 'REJECTED')
    .map((row) => row.scenarioId);
  const acceptedSet = new Set(acceptedIds);
  const retained = options.draftSet.scenarios.filter((scenario) => !acceptedSet.has(scenario.scenarioId));
  const receiptMaterial = {
    schemaVersion: 'bloge.scenarioMaterializationReceipt.v1' as const,
    receiptId: `scenario-import-${options.plan.planFingerprint.slice(7, 23)}`,
    planFingerprint: options.plan.planFingerprint,
    sourceFingerprint: options.preview.source.fingerprint,
    mappingFingerprint: options.plan.mappingFingerprint,
    contractFingerprint: options.plan.contractFingerprint,
    targetFingerprint: options.plan.target.fingerprint,
    rowCount: options.plan.rowSelection.length,
    acceptedRowCount: acceptedIds.length,
    rejectedRowCount: rowReceipts.length - acceptedIds.length,
    rowIdentityPolicy: options.plan.rowIdentityPolicy,
    materializedScenarioIds: acceptedIds,
    rows: rowReceipts,
    actor: options.actor.trim() || 'unknown',
    materializedAt: options.materializedAt,
    classification: options.plan.source.classification,
  };
  const receipt: ScenarioMaterializationReceipt = {
    ...receiptMaterial,
    receiptFingerprint: await sha256Fingerprint(receiptMaterial),
  };
  return {
    draftSet: {
      ...options.draftSet,
      scenarios: [...retained, ...imported],
      metadata: {
        ...options.draftSet.metadata,
        classification: options.plan.source.classification,
        updatedAt: options.materializedAt,
        provenance: {
          ...options.draftSet.metadata.provenance,
          scenarioImportReceipt: receipt,
        },
      },
    },
    receipt,
  };
}

/** Compares a replacement source against one exact receipt without mutating the suite. */
export async function diffScenarioImport(
  preview: ScenarioImportPreview,
  receipt: ScenarioMaterializationReceipt,
): Promise<ScenarioImportDiff> {
  const current = new Map(await Promise.all(preview.rows.map(async (row) => [
    await sha256Fingerprint({ identity: rowIdentity(row, receipt.rowIdentityPolicy) }),
    row.rowFingerprint,
  ] as const)));
  const previous = new Map(receipt.rows
    .filter((row) => row.status !== 'REJECTED')
    .map((row) => [row.identityFingerprint, row.rowFingerprint]));
  const added: string[] = [];
  const changed: string[] = [];
  const unchanged: string[] = [];
  for (const [identity, fingerprint] of current) {
    if (!previous.has(identity)) added.push(identity);
    else if (previous.get(identity) !== fingerprint) changed.push(identity);
    else unchanged.push(identity);
  }
  const removed = [...previous.keys()].filter((identity) => !current.has(identity));
  return { added: added.sort(), changed: changed.sort(), removed: removed.sort(), unchanged: unchanged.sort() };
}

export class ScenarioImportError extends Error {
  constructor(readonly code: string, message: string) {
    super(message);
    this.name = 'ScenarioImportError';
  }
}

interface ParsedSource {
  rows: Record<string, unknown>[];
  columns: string[];
  warnings: ScenarioImportWarning[];
  delimiter: string;
  parser: string;
}

function parseCsv(text: string, budget: ScenarioImportBudget): ParsedSource {
  const result = Papa.parse<string[]>(text, {
    delimiter: '',
    skipEmptyLines: 'greedy',
  });
  const errors = result.errors.filter((error) => error.type !== 'Delimiter');
  if (errors.length > 0) {
    throw importError('RG.SCENARIO_IMPORT.CSV_INVALID', 'CSV could not be parsed within the supported dialect.');
  }
  const matrix = result.data;
  if (matrix.length === 0) return { rows: [], columns: [], warnings: [], delimiter: result.meta.delimiter || ',', parser: 'papaparse-v5' };
  const headers = matrix[0].map((header) => String(header).replace(/^\uFEFF/, '').trim());
  if (headers.some((header) => !header)) {
    throw importError('RG.SCENARIO_IMPORT.HEADER_EMPTY', 'Every CSV column requires a non-empty header.');
  }
  if (new Set(headers).size !== headers.length) {
    throw importError('RG.SCENARIO_IMPORT.HEADER_DUPLICATE', 'CSV contains duplicate headers.');
  }
  requireColumnBudget(headers.length, budget);
  const dataRows = matrix.slice(1);
  requireRowBudget(dataRows.length, budget);
  const rows = dataRows.map((cells) => {
    if (cells.length > headers.length) {
      throw importError('RG.SCENARIO_IMPORT.COLUMN_OVERFLOW', 'A CSV row contains more cells than the header.');
    }
    const row: Record<string, unknown> = {};
    headers.forEach((header, index) => {
      if (index < cells.length) {
        const value = cells[index];
        requireCellBudget(value, budget);
        row[`/${escapePointer(header)}`] = value;
      }
    });
    return row;
  });
  return {
    rows,
    columns: headers.map((header) => `/${escapePointer(header)}`),
    warnings: [],
    delimiter: result.meta.delimiter || ',',
    parser: 'papaparse-v5',
  };
}

function parseJson(text: string, budget: ScenarioImportBudget): ParsedSource {
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    throw importError('RG.SCENARIO_IMPORT.JSON_INVALID', 'JSON source is malformed.');
  }
  const stats = jsonStats(value);
  if (stats.depth > budget.maxDepth) {
    throw importError('RG.SCENARIO_IMPORT.DEPTH_EXCEEDED', 'JSON source exceeds the configured nesting depth.');
  }
  if (stats.items > budget.maxItems) {
    throw importError('RG.SCENARIO_IMPORT.ITEMS_EXCEEDED', 'JSON source exceeds the configured item budget.');
  }
  if (!Array.isArray(value) || value.some((row) => row === null || typeof row !== 'object' || Array.isArray(row))) {
    throw importError('RG.SCENARIO_IMPORT.JSON_SHAPE_INVALID', 'JSON source must be an array of objects.');
  }
  requireRowBudget(value.length, budget);
  const rows = value.map((row) => Object.fromEntries(
    flattenValue(row).map((entry) => {
      requireCellBudget(entry.value, budget);
      return [entry.pointer, entry.value];
    }),
  ));
  const columns = [...new Set(rows.flatMap((row) => Object.keys(row)))].sort();
  requireColumnBudget(columns.length, budget);
  return { rows, columns, warnings: [], delimiter: '', parser: 'json-standard-v1' };
}

function projectColumns(columns: string[], rows: ScenarioImportRow[]): ScenarioImportColumn[] {
  return columns.map((sourcePath) => {
    const present = rows.filter((row) => Object.prototype.hasOwnProperty.call(row.values, sourcePath));
    const values = present.map((row) => row.values[sourcePath]);
    return {
      sourcePath,
      label: last(pointerParts(sourcePath)) ?? sourcePath,
      inferredType: inferType(values),
      sensitive: SENSITIVE_NAME.test(sourcePath),
      missingCount: rows.length - present.length,
      nullCount: values.filter((value) => value === null).length,
      emptyCount: values.filter((value) => value === '').length,
      formulaRiskCount: values.filter((value) => typeof value === 'string' && FORMULA_PREFIX.test(value)).length,
    };
  });
}

function maskPreviewValues(
  values: Record<string, unknown>,
  columns: ScenarioImportColumn[],
): Record<string, unknown> {
  const sensitive = new Set(columns.filter((column) => column.sensitive).map((column) => column.sourcePath));
  return Object.fromEntries(Object.entries(values).map(([key, value]) => [
    key,
    sensitive.has(key) && value !== null && value !== '' ? '[masked]' : value,
  ]));
}

function bindingCandidate(
  column: ScenarioImportColumn,
  target: ScenarioImportTarget,
): { target: ScenarioImportTarget; confidence: number; reason: ScenarioColumnBinding['reason'] } | null {
  const sourcePath = pointerParts(column.sourcePath).join('/');
  const targetPath = pointerParts(stripTargetPrefix(target.path)).join('/');
  if (sourcePath === targetPath) return { target, confidence: 1, reason: 'EXACT_PATH' };
  if (column.label.toLocaleLowerCase() === target.label.toLocaleLowerCase()) {
    return { target, confidence: 0.98, reason: 'EXACT_NAME' };
  }
  const sourceName = normalizeName(column.label);
  const targetNames = [target.label, last(pointerParts(target.path)) ?? ''].map(normalizeName);
  if (sourceName && targetNames.includes(sourceName)) {
    return { target, confidence: 0.82, reason: 'NORMALIZED_NAME' };
  }
  return null;
}

function suggestedConverter(column: ScenarioImportColumn): ScenarioImportConverter {
  if (column.inferredType === 'NUMBER') return 'NUMBER';
  if (column.inferredType === 'BOOLEAN') return 'BOOLEAN';
  if (column.inferredType === 'JSON') return 'JSON';
  return 'IDENTITY';
}

function validateBindings(bindings: ScenarioColumnBinding[], availableColumns: Set<string>): void {
  const targetIds = new Set<string>();
  for (const binding of bindings) {
    if (!availableColumns.has(binding.sourcePath)) {
      throw importError('RG.SCENARIO_IMPORT.BINDING_SOURCE_INVALID', 'A mapping references an unavailable source column.');
    }
    if (targetIds.has(binding.target.targetId)) {
      throw importError('RG.SCENARIO_IMPORT.BINDING_TARGET_DUPLICATE', 'Multiple source columns map to the same target.');
    }
    targetIds.add(binding.target.targetId);
    if (binding.confidence < 0.95 && !binding.confirmed) {
      throw importError('RG.SCENARIO_IMPORT.BINDING_CONFIRMATION_REQUIRED', 'Low-confidence mappings require explicit confirmation.');
    }
    if (binding.valueSemantics === 'DEFAULT' && binding.defaultValue === undefined) {
      throw importError('RG.SCENARIO_IMPORT.DEFAULT_REQUIRED', 'Default semantics require an explicit default value.');
    }
  }
}

function validatePlanClosure(
  preview: ScenarioImportPreview,
  plan: ScenarioMaterializationPlan,
  draftSet: ScenarioDraftSet,
): void {
  if (preview.source.fingerprint !== plan.source.fingerprint) {
    throw importError('RG.SCENARIO_IMPORT.SOURCE_DRIFT', 'Source fingerprint changed after the plan was created.');
  }
  if (draftSet.contractFingerprint !== plan.contractFingerprint) {
    throw importError('RG.SCENARIO_IMPORT.CONTRACT_DRIFT', 'Contract fingerprint changed after the plan was created.');
  }
  if (canonicalJson(draftSet.target) !== canonicalJson(plan.target)) {
    throw importError('RG.SCENARIO_IMPORT.TARGET_DRIFT', 'Target coordinate changed after the plan was created.');
  }
  validateBindings(plan.bindings, new Set(preview.columns.map((column) => column.sourcePath)));
}

function applyBindings(
  template: ScenarioDraft,
  scenarioId: string,
  row: ScenarioImportRow,
  bindings: ScenarioColumnBinding[],
): ScenarioDraft {
  let scenario: ScenarioDraft = {
    ...deepClone(template),
    scenarioId,
    name: `Imported ${row.canonicalIndex + 1}`,
    given: { ...deepClone(template.given), provenance: 'IMPORTED' },
  };
  for (const binding of bindings) {
    const resolved = resolveBindingValue(row, binding);
    if (resolved.action === 'REMOVE') {
      scenario = removeBindingValue(scenario, binding.target);
      continue;
    }
    if (resolved.action === 'SKIP') continue;
    const value = convertValue(resolved.value, binding.converter);
    const target = binding.target;
    switch (target.kind) {
      case 'NAME': scenario = { ...scenario, name: String(value) }; break;
      case 'CASE_TYPE': {
        const caseType = String(value).toUpperCase() as ScenarioCaseType;
        if (!CASE_TYPES.includes(caseType)) {
          throw importError('RG.SCENARIO_IMPORT.CASE_TYPE_INVALID', 'Imported case type is unsupported.');
        }
        scenario = { ...scenario, caseType };
        break;
      }
      case 'TAGS': scenario = { ...scenario, tags: tagsValue(value) }; break;
      case 'GIVEN': scenario = {
        ...scenario,
        given: {
          input: setValueAtPath(scenario.given.input, target.valuePath, value),
          provenance: 'IMPORTED',
        },
      }; break;
      case 'DEPENDENCY_OUTPUT': scenario = {
        ...scenario,
        dependencies: scenario.dependencies.map((dependency) => (
          dependency.dependencyId === target.dependencyId
            ? {
              ...dependency,
              behavior: {
                ...dependency.behavior,
                output: setValueAtPath(dependency.behavior.output, target.valuePath, value),
              },
              origin: 'IMPORTED',
            }
            : dependency
        )),
      }; break;
      case 'ASSERTION_EXPECTED': scenario = {
        ...scenario,
        then: {
          assertions: scenario.then.assertions.map((assertion) => (
          assertion.assertionId === target.assertionId
              ? { ...assertion, expected: setValueAtPath(assertion.expected, target.valuePath, value) }
              : assertion
          )),
        },
      }; break;
    }
  }
  if (!scenario.name.trim()) {
    throw importError('RG.SCENARIO_IMPORT.NAME_REQUIRED', 'Materialized Scenario name cannot be empty.');
  }
  return scenario;
}

function resolveBindingValue(
  row: ScenarioImportRow,
  binding: ScenarioColumnBinding,
): { action: 'SET' | 'REMOVE' | 'SKIP'; value: unknown } {
  if (!Object.prototype.hasOwnProperty.call(row.values, binding.sourcePath)) {
    return binding.valueSemantics === 'DEFAULT'
      ? { action: 'SET', value: binding.defaultValue }
      : { action: removable(binding.target) ? 'REMOVE' : 'SKIP', value: undefined };
  }
  const value = row.values[binding.sourcePath];
  if (value !== '') return { action: 'SET', value };
  switch (binding.valueSemantics) {
    case 'NULL': return { action: 'SET', value: null };
    case 'MISSING': return { action: removable(binding.target) ? 'REMOVE' : 'SKIP', value: undefined };
    case 'DEFAULT': return { action: 'SET', value: binding.defaultValue };
    case 'EMPTY':
    case 'VALUE': return { action: 'SET', value: '' };
  }
}

function convertValue(value: unknown, converter: ScenarioImportConverter): unknown {
  if (value === null || converter === 'IDENTITY') return value;
  switch (converter) {
    case 'STRING': return String(value);
    case 'NUMBER': {
      if (typeof value === 'string' && value.trim() === '') {
        throw importError('RG.SCENARIO_IMPORT.NUMBER_INVALID', 'An empty value cannot be converted to a number.');
      }
      const converted = typeof value === 'number' ? value : Number(String(value).trim());
      if (!Number.isFinite(converted)) throw importError('RG.SCENARIO_IMPORT.NUMBER_INVALID', 'A value cannot be converted to a finite number.');
      return converted;
    }
    case 'BOOLEAN': {
      if (typeof value === 'boolean') return value;
      const normalized = String(value).trim().toLocaleLowerCase();
      if (normalized === 'true' || normalized === '1') return true;
      if (normalized === 'false' || normalized === '0') return false;
      throw importError('RG.SCENARIO_IMPORT.BOOLEAN_INVALID', 'A value cannot be converted to boolean.');
    }
    case 'JSON': {
      if (typeof value !== 'string') return value;
      try {
        return JSON.parse(value);
      } catch {
        throw importError('RG.SCENARIO_IMPORT.CELL_JSON_INVALID', 'A mapped JSON cell is malformed.');
      }
    }
  }
}

function removeBindingValue(scenario: ScenarioDraft, target: ScenarioImportTarget): ScenarioDraft {
  switch (target.kind) {
    case 'NAME':
    case 'CASE_TYPE':
    case 'TAGS':
      return scenario;
    case 'GIVEN': return {
      ...scenario,
      given: {
        input: removeValueAtPath(scenario.given.input, target.valuePath),
        provenance: 'IMPORTED',
      },
    };
    case 'DEPENDENCY_OUTPUT': return {
      ...scenario,
      dependencies: scenario.dependencies.map((dependency) => (
        dependency.dependencyId === target.dependencyId
          ? {
            ...dependency,
            behavior: {
              ...dependency.behavior,
              output: removeValueAtPath(dependency.behavior.output, target.valuePath),
            },
            origin: 'IMPORTED',
          }
          : dependency
      )),
    };
    case 'ASSERTION_EXPECTED': return {
      ...scenario,
      then: {
        assertions: scenario.then.assertions.map((assertion) => (
          assertion.assertionId === target.assertionId
            ? { ...assertion, expected: removeValueAtPath(assertion.expected, target.valuePath) }
            : assertion
        )),
      },
    };
  }
}

function removable(target: ScenarioImportTarget): boolean {
  return target.kind === 'GIVEN'
    || target.kind === 'DEPENDENCY_OUTPUT'
    || target.kind === 'ASSERTION_EXPECTED';
}

function conflictStatus(
  existing: ScenarioDraft | undefined,
  materialized: ScenarioDraft,
  policy: ScenarioImportConflictPolicy,
): ScenarioMaterializationReceipt['rows'][number]['status'] {
  if (!existing) return 'CREATED';
  if (canonicalJson(existing) === canonicalJson(materialized)) return 'UNCHANGED';
  if (policy === 'REPLACE_EXACT_ID') return 'REPLACED';
  return 'REJECTED';
}

function rowIdentity(
  row: ScenarioImportRow,
  policy: ScenarioMaterializationPlan['rowIdentityPolicy'],
): string {
  if (policy.kind === 'CANONICAL_ROW_HASH') return row.rowFingerprint;
  if (!Object.prototype.hasOwnProperty.call(row.values, policy.sourcePath)) {
    throw importError('RG.SCENARIO_IMPORT.IDENTITY_MISSING', 'An imported row is missing its configured identity value.');
  }
  const value = row.values[policy.sourcePath];
  if (value === null || String(value).trim() === '') {
    throw importError('RG.SCENARIO_IMPORT.IDENTITY_EMPTY', 'An imported row has an empty identity value.');
  }
  return canonicalJson(value);
}

function jsonStats(value: unknown, depth = 0): { depth: number; items: number } {
  if (value === null || typeof value !== 'object') return { depth, items: 1 };
  const children = Array.isArray(value) ? value : Object.values(value as Record<string, unknown>);
  return children.reduce((stats, child) => {
    const nested = jsonStats(child, depth + 1);
    return { depth: Math.max(stats.depth, nested.depth), items: stats.items + nested.items };
  }, { depth, items: 1 });
}

function flattenValue(value: unknown, path: string[] = []): Array<{ pointer: string; path: string[]; value: unknown }> {
  if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
    const entries = Object.entries(value as Record<string, unknown>);
    if (entries.length > 0) return entries.flatMap(([key, child]) => flattenValue(child, [...path, key]));
  }
  return [{ pointer: `/${path.map(escapePointer).join('/')}`, path, value }];
}

function setValueAtPath(root: unknown, path: string[], value: unknown): unknown {
  if (path.length === 0) return value;
  const record = root !== null && typeof root === 'object' && !Array.isArray(root)
    ? root as Record<string, unknown>
    : {};
  const [head, ...tail] = path;
  return { ...record, [head]: setValueAtPath(record[head], tail, value) };
}

function removeValueAtPath(root: unknown, path: string[]): unknown {
  if (path.length === 0) return undefined;
  if (root === null || typeof root !== 'object' || Array.isArray(root)) return root;
  const record = root as Record<string, unknown>;
  const [head, ...tail] = path;
  if (tail.length === 0) {
    const { [head]: _removed, ...rest } = record;
    return rest;
  }
  return { ...record, [head]: removeValueAtPath(record[head], tail) };
}

function pointerParts(pointer: string): string[] {
  return pointer.split('/').slice(1).map((part) => part.replace(/~1/g, '/').replace(/~0/g, '~'));
}

function stripTargetPrefix(path: string): string {
  return path
    .replace(/^\/given\/input/, '')
    .replace(/^\/dependencies\/[^/]+\/behavior\/output/, '')
    .replace(/^\/then\/assertions\/[^/]+\/expected/, '');
}

function normalizeName(value: string): string {
  return value.toLocaleLowerCase().replace(/[^a-z0-9]/g, '');
}

function inferType(values: unknown[]): ScenarioImportColumn['inferredType'] {
  const types = new Set(values.map((value) => {
    if (value === null) return 'NULL';
    if (Array.isArray(value) || typeof value === 'object') return 'JSON';
    if (typeof value === 'number') return 'NUMBER';
    if (typeof value === 'boolean') return 'BOOLEAN';
    if (typeof value === 'string' && value.trim() !== '' && Number.isFinite(Number(value))) return 'NUMBER';
    if (typeof value === 'string' && /^(true|false)$/i.test(value.trim())) return 'BOOLEAN';
    return 'STRING';
  }));
  return types.size === 1
    ? [...types][0] as ScenarioImportColumn['inferredType']
    : 'MIXED';
}

function normalizeBudget(requested: Partial<ScenarioImportBudget>): ScenarioImportBudget {
  return Object.fromEntries(Object.entries(DEFAULT_SCENARIO_IMPORT_BUDGET).map(([key, fallback]) => {
    const value = requested[key as keyof ScenarioImportBudget];
    return [key, Number.isInteger(value) && Number(value) > 0 ? Number(value) : fallback];
  })) as unknown as ScenarioImportBudget;
}

function requireRowBudget(count: number, budget: ScenarioImportBudget): void {
  if (count > budget.maxRows) throw importError('RG.SCENARIO_IMPORT.ROWS_EXCEEDED', 'Source exceeds the configured row budget.');
}

function requireColumnBudget(count: number, budget: ScenarioImportBudget): void {
  if (count > budget.maxColumns) throw importError('RG.SCENARIO_IMPORT.COLUMNS_EXCEEDED', 'Source exceeds the configured column budget.');
}

function requireCellBudget(value: unknown, budget: ScenarioImportBudget): void {
  if (new TextEncoder().encode(canonicalJson(value)).byteLength > budget.maxCellBytes) {
    throw importError('RG.SCENARIO_IMPORT.CELL_BYTES_EXCEEDED', 'A source cell exceeds the configured byte budget.');
  }
}

function addTarget(targets: ScenarioImportTarget[], seen: Set<string>, target: ScenarioImportTarget): void {
  if (!seen.has(target.targetId)) {
    seen.add(target.targetId);
    targets.push(target);
  }
}

function bindingFingerprintMaterial(binding: ScenarioColumnBinding): unknown {
  return {
    bindingId: binding.bindingId,
    sourcePath: binding.sourcePath,
    target: binding.target,
    confidence: binding.confidence,
    reason: binding.reason,
    confirmed: binding.confirmed,
    converter: binding.converter,
    valueSemantics: binding.valueSemantics,
    defaultValue: binding.defaultValue,
  };
}

function cloneBinding(binding: ScenarioColumnBinding): ScenarioColumnBinding {
  return deepClone(binding);
}

function deepClone<T>(value: T): T {
  return typeof structuredClone === 'function'
    ? structuredClone(value)
    : JSON.parse(JSON.stringify(value)) as T;
}

function tagsValue(value: unknown): string[] {
  if (Array.isArray(value)) return value.map(String).map((tag) => tag.trim()).filter(Boolean);
  return String(value).split(',').map((tag) => tag.trim()).filter(Boolean);
}

function emptyTemplate(): ScenarioDraft {
  return {
    scenarioId: '',
    name: '',
    description: '',
    caseType: 'GOLDEN',
    tags: [],
    given: { input: {}, provenance: 'IMPORTED' },
    dependencies: [],
    then: { assertions: [] },
  };
}

function warning(code: string, path: string, message: string): ScenarioImportWarning {
  return { code, path, message };
}

function importError(code: string, message: string): ScenarioImportError {
  return new ScenarioImportError(code, message);
}

function isMaterializationReceipt(value: unknown): value is ScenarioMaterializationReceipt {
  if (value === null || typeof value !== 'object') return false;
  const candidate = value as Partial<ScenarioMaterializationReceipt>;
  return candidate.schemaVersion === 'bloge.scenarioMaterializationReceipt.v1'
    && typeof candidate.planFingerprint === 'string'
    && typeof candidate.receiptFingerprint === 'string'
    && Array.isArray(candidate.rows);
}

function escapePointer(value: string): string {
  return value.replace(/~/g, '~0').replace(/\//g, '~1');
}

function last<T>(values: T[]): T | undefined {
  return values[values.length - 1];
}
