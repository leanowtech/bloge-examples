import type {
  ScenarioCaseType,
  ScenarioDraft,
  ScenarioDraftSet,
} from '../domain';
import { canonicalJson } from '../fingerprint';
import {
  presentTableCaseVerdict,
  type TableCaseVerdict,
  type TableCaseVerdictPresentation,
} from '../tableDrivenTestStatus';

export type ScenarioTableColumnGroup = 'CASE' | 'GIVEN' | 'DEPENDENCY' | 'THEN' | 'PROOF';

export interface ScenarioTableColumn {
  columnId: string;
  group: ScenarioTableColumnGroup;
  label: string;
  path: string;
  valueKind: 'STRING' | 'NUMBER' | 'BOOLEAN' | 'JSON' | 'SUMMARY' | 'STATUS';
  editable: boolean;
  source: 'CONTRACT' | 'SCENARIO' | 'EVIDENCE';
  binding:
    | { kind: 'NAME' | 'CASE_TYPE' | 'TAGS' }
    | { kind: 'GIVEN'; path: string[] }
    | { kind: 'DEPENDENCY'; coordinate: string }
    | { kind: 'ASSERTION'; coordinate: string }
    | { kind: 'EVIDENCE'; axis: 'VERDICT' | 'EXECUTION' | 'ASSERTIONS' | 'FRESHNESS' | 'PROOF' | 'DURATION' | 'ATTEMPTS' | 'BASELINE' };
}

export interface TableCaseEvidenceProjection extends TableCaseVerdict {
  caseId: string;
  runId: string;
  attempt: number;
  durationMs: number | null;
  flaky?: boolean;
  baselineOutcome?: 'NONE' | 'SAME' | 'IMPROVED' | 'REGRESSED' | 'CHANGED_INPUT' | 'NEW';
  firstFailure: {
    category: string;
    target: string;
    message: string;
  } | null;
}

export interface ScenarioTableRow {
  caseId: string;
  canonicalIndex: number;
  name: string;
  caseType: ScenarioCaseType;
  tags: string[];
  values: Record<string, unknown>;
  evidence: TableCaseEvidenceProjection;
  presentation: TableCaseVerdictPresentation;
}

export interface ScenarioTableProjection {
  schemaVersion: 'bloge.scenarioTableProjection.v1';
  target: ScenarioDraftSet['target'];
  contractFingerprint: string;
  scenarioDraftSetRevision: number;
  columns: ScenarioTableColumn[];
  rows: ScenarioTableRow[];
  projectionFingerprint: string;
}

export type ScenarioTableEvidenceByCase = Record<string, TableCaseEvidenceProjection | undefined>;

export interface ScenarioTableFilter {
  query: string;
  caseTypes: ScenarioCaseType[];
  tones: TableCaseVerdictPresentation['tone'][];
}

export interface ScenarioTableSort {
  key: 'CANONICAL' | 'NAME' | 'TYPE' | 'VERDICT';
  direction: 'ASC' | 'DESC';
}

export interface ScenarioTableSelection {
  selectedCaseIds: string[];
}

export type ScenarioRunSelectionMode = 'ALL' | 'SELECTED' | 'FAILED' | 'CHANGED' | 'AFFECTED';

export interface ExactScenarioRunSelection {
  mode: ScenarioRunSelectionMode;
  caseIds: string[];
  selectionFingerprint: string;
}

const CASE_COLUMNS: ScenarioTableColumn[] = [
  column('case:name', 'CASE', 'Case', '/name', 'STRING', true, { kind: 'NAME' }),
  column('case:type', 'CASE', 'Type', '/caseType', 'STRING', true, { kind: 'CASE_TYPE' }),
  column('case:tags', 'CASE', 'Tags', '/tags', 'SUMMARY', true, { kind: 'TAGS' }),
];

const PROOF_COLUMNS: ScenarioTableColumn[] = [
  evidenceColumn('proof:verdict', 'Verdict', 'VERDICT'),
  evidenceColumn('proof:execution', 'Execution', 'EXECUTION'),
  evidenceColumn('proof:assertions', 'Assertions', 'ASSERTIONS'),
  evidenceColumn('proof:freshness', 'Freshness', 'FRESHNESS'),
  evidenceColumn('proof:strength', 'Proof', 'PROOF'),
  evidenceColumn('proof:duration', 'Duration', 'DURATION'),
  evidenceColumn('proof:attempts', 'Attempts', 'ATTEMPTS'),
  evidenceColumn('proof:baseline', 'Baseline', 'BASELINE'),
];

export function buildScenarioTableProjection(
  draftSet: ScenarioDraftSet,
  evidenceByCase: ScenarioTableEvidenceByCase = {},
): ScenarioTableProjection {
  const givenPaths = uniqueSorted(draftSet.scenarios.flatMap((scenario) => (
    flattenValue(scenario.given.input).map((entry) => entry.path)
  )));
  const dependencyCoordinates = uniqueSorted(draftSet.scenarios.flatMap((scenario) => (
    scenario.dependencies.map(dependencyCoordinate)
  )));
  const assertionCoordinates = uniqueSorted(draftSet.scenarios.flatMap((scenario) => (
    scenario.then.assertions.map(assertionCoordinate)
  )));
  const columns = [
    ...CASE_COLUMNS,
    ...givenPaths.map(givenColumn),
    ...dependencyCoordinates.map(dependencyColumn),
    ...assertionCoordinates.map(assertionColumn),
    ...PROOF_COLUMNS,
  ];
  const rows = draftSet.scenarios.map((scenario, canonicalIndex) => {
    const evidence = evidenceByCase[scenario.scenarioId] ?? notRunEvidence(scenario.scenarioId);
    return {
      caseId: scenario.scenarioId,
      canonicalIndex,
      name: scenario.name,
      caseType: scenario.caseType,
      tags: scenario.tags,
      values: rowValues(scenario, columns, evidence),
      evidence,
      presentation: presentTableCaseVerdict(evidence),
    };
  });
  const fingerprintMaterial = {
    target: draftSet.target,
    contractFingerprint: draftSet.contractFingerprint,
    revision: draftSet.revision,
    columns: columns.map(({ binding: _binding, ...entry }) => entry),
    rows: rows.map(({ evidence, presentation: _presentation, ...row }) => ({
      ...row,
      evidence: {
        execution: evidence.execution,
        assertions: evidence.assertions,
        freshness: evidence.freshness,
        proofStrength: evidence.proofStrength,
      },
    })),
  };
  return {
    schemaVersion: 'bloge.scenarioTableProjection.v1',
    target: { ...draftSet.target },
    contractFingerprint: draftSet.contractFingerprint,
    scenarioDraftSetRevision: draftSet.revision,
    columns,
    rows,
    projectionFingerprint: `fnv1a32:${fnv1a32(canonicalJson(fingerprintMaterial))}`,
  };
}

/** Payload-free browser coordinate used to compare a current case with its retained baseline. */
export function scenarioTableCaseFingerprint(scenario: ScenarioDraft): string {
  return `fnv1a32:${fnv1a32(canonicalJson(scenario))}`;
}

export function filterAndSortScenarioRows(
  projection: ScenarioTableProjection,
  filter: ScenarioTableFilter,
  sort: ScenarioTableSort,
): ScenarioTableRow[] {
  const query = filter.query.trim().toLocaleLowerCase();
  const caseTypes = new Set(filter.caseTypes);
  const tones = new Set(filter.tones);
  const rows = projection.rows.filter((row) => (
    (caseTypes.size === 0 || caseTypes.has(row.caseType))
      && (tones.size === 0 || tones.has(row.presentation.tone))
      && (!query || [row.caseId, row.name, row.caseType, ...row.tags]
        .some((value) => value.toLocaleLowerCase().includes(query)))
  ));
  const factor = sort.direction === 'ASC' ? 1 : -1;
  return [...rows].sort((left, right) => {
    const comparison = sortValue(left, sort.key).localeCompare(sortValue(right, sort.key));
    return comparison === 0
      ? (left.canonicalIndex - right.canonicalIndex) * factor
      : comparison * factor;
  });
}

export function toggleScenarioSelection(
  selection: ScenarioTableSelection,
  caseId: string,
  selected?: boolean,
): ScenarioTableSelection {
  const ids = new Set(selection.selectedCaseIds);
  const shouldSelect = selected ?? !ids.has(caseId);
  if (shouldSelect) ids.add(caseId);
  else ids.delete(caseId);
  return { selectedCaseIds: [...ids] };
}

export function selectVisibleScenarios(
  selection: ScenarioTableSelection,
  visibleCaseIds: string[],
  selected: boolean,
): ScenarioTableSelection {
  const ids = new Set(selection.selectedCaseIds);
  for (const caseId of visibleCaseIds) {
    if (selected) ids.add(caseId);
    else ids.delete(caseId);
  }
  return { selectedCaseIds: [...ids] };
}

/** Resolves a predicate to the exact canonical order submitted to the runner. */
export function resolveExactScenarioRunSelection(
  projection: ScenarioTableProjection,
  selection: ScenarioTableSelection,
  mode: ScenarioRunSelectionMode,
  previousRunCaseIds: string[] = [],
): ExactScenarioRunSelection {
  const selected = new Set(selection.selectedCaseIds);
  const previous = new Set(previousRunCaseIds);
  const caseIds = projection.rows
    .filter((row) => {
      if (mode === 'ALL') return true;
      if (mode === 'SELECTED') return selected.has(row.caseId);
      if (mode === 'FAILED') return previous.has(row.caseId) && row.presentation.tone === 'failed';
      return false;
    })
    .map((row) => row.caseId);
  return {
    mode,
    caseIds,
    selectionFingerprint: `fnv1a32:${fnv1a32(canonicalJson({
      projectionFingerprint: projection.projectionFingerprint,
      mode,
      caseIds,
    }))}`,
  };
}

export function applyScenarioTableCellEdit(
  draftSet: ScenarioDraftSet,
  caseId: string,
  column: ScenarioTableColumn,
  value: unknown,
): ScenarioDraftSet {
  if (!column.editable) return draftSet;
  const scenarios: ScenarioDraft[] = draftSet.scenarios.map((scenario): ScenarioDraft => {
    if (scenario.scenarioId !== caseId) return scenario;
    switch (column.binding.kind) {
      case 'NAME': return { ...scenario, name: String(value) };
      case 'CASE_TYPE': return isCaseType(value) ? { ...scenario, caseType: value } : scenario;
      case 'TAGS': return {
        ...scenario,
        tags: Array.isArray(value)
          ? value.map(String)
          : String(value).split(',').map((tag) => tag.trim()).filter(Boolean),
      };
      case 'GIVEN': return {
        ...scenario,
        given: {
          input: setValueAtPath(scenario.given.input, column.binding.path, value),
          provenance: 'AUTHORED' as const,
        },
      };
      case 'DEPENDENCY':
      case 'ASSERTION':
      case 'EVIDENCE':
        return scenario;
    }
  });
  return {
    ...draftSet,
    scenarios,
    metadata: { ...draftSet.metadata, updatedAt: new Date().toISOString() },
  };
}

export function notRunEvidence(caseId: string): TableCaseEvidenceProjection {
  return {
    caseId,
    runId: '',
    attempt: 0,
    execution: 'NOT_RUN',
    assertions: 'NONE',
    freshness: 'CURRENT',
    proofStrength: 'SCHEMA',
    durationMs: null,
    firstFailure: null,
  };
}

function rowValues(
  scenario: ScenarioDraft,
  columns: ScenarioTableColumn[],
  evidence: TableCaseEvidenceProjection,
): Record<string, unknown> {
  const values: Record<string, unknown> = {};
  for (const column of columns) {
    switch (column.binding.kind) {
      case 'NAME': values[column.columnId] = scenario.name; break;
      case 'CASE_TYPE': values[column.columnId] = scenario.caseType; break;
      case 'TAGS': values[column.columnId] = scenario.tags.join(', '); break;
      case 'GIVEN': values[column.columnId] = valueAtPath(scenario.given.input, column.binding.path); break;
      case 'DEPENDENCY': {
        const coordinate = column.binding.coordinate;
        const dependency = scenario.dependencies.find((entry) => (
          dependencyCoordinate(entry) === coordinate
        ));
        values[column.columnId] = dependency ? dependency.behavior.kind : '';
        break;
      }
      case 'ASSERTION': {
        const coordinate = column.binding.coordinate;
        const assertion = scenario.then.assertions.find((entry) => (
          assertionCoordinate(entry) === coordinate
        ));
        values[column.columnId] = assertion?.expected ?? assertion?.operator ?? '';
        break;
      }
      case 'EVIDENCE': values[column.columnId] = evidenceValue(evidence, column.binding.axis); break;
    }
  }
  return values;
}

function givenColumn(path: string): ScenarioTableColumn {
  const parts = parsePath(path);
  return column(
    `given:${encodeURIComponent(path)}`,
    'GIVEN',
    parts[parts.length - 1] ?? 'input',
    `/given/input${path}`,
    'JSON',
    true,
    { kind: 'GIVEN', path: parts },
  );
}

function dependencyColumn(coordinate: string): ScenarioTableColumn {
  return column(
    `dependency:${encodeURIComponent(coordinate)}`,
    'DEPENDENCY',
    coordinate,
    `/dependencies/${coordinate}`,
    'SUMMARY',
    false,
    { kind: 'DEPENDENCY', coordinate },
  );
}

function assertionColumn(coordinate: string): ScenarioTableColumn {
  return column(
    `assertion:${encodeURIComponent(coordinate)}`,
    'THEN',
    coordinate,
    `/then/assertions/${coordinate}`,
    'JSON',
    false,
    { kind: 'ASSERTION', coordinate },
  );
}

function evidenceColumn(
  columnId: string,
  label: string,
  axis: Extract<ScenarioTableColumn['binding'], { kind: 'EVIDENCE' }>['axis'],
): ScenarioTableColumn {
  return column(columnId, 'PROOF', label, `/evidence/${axis.toLowerCase()}`, 'STATUS', false, {
    kind: 'EVIDENCE',
    axis,
  });
}

function column(
  columnId: string,
  group: ScenarioTableColumnGroup,
  label: string,
  path: string,
  valueKind: ScenarioTableColumn['valueKind'],
  editable: boolean,
  binding: ScenarioTableColumn['binding'],
): ScenarioTableColumn {
  return { columnId, group, label, path, valueKind, editable, source: group === 'PROOF' ? 'EVIDENCE' : 'SCENARIO', binding };
}

function flattenValue(value: unknown, path: string[] = []): Array<{ path: string; value: unknown }> {
  if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
    const entries = Object.entries(value as Record<string, unknown>);
    if (entries.length > 0) return entries.flatMap(([key, child]) => flattenValue(child, [...path, key]));
  }
  return [{ path: `/${path.map(escapePath).join('/')}`, value }];
}

function dependencyCoordinate(dependency: ScenarioDraft['dependencies'][number]): string {
  const selector = dependency.selector;
  return selector.nodeId || selector.operatorRef || selector.functionRef || selector.resourceRef || dependency.dependencyId;
}

function assertionCoordinate(assertion: ScenarioDraft['then']['assertions'][number]): string {
  const target = assertion.path || assertion.nodeId
    || [assertion.fromNodeId, assertion.toNodeId].filter(Boolean).join('->')
    || assertion.assertionId;
  return `${assertion.scope}:${target}:${assertion.operator}`;
}

function evidenceValue(evidence: TableCaseEvidenceProjection, axis: Extract<ScenarioTableColumn['binding'], { kind: 'EVIDENCE' }>['axis']): unknown {
  switch (axis) {
    case 'VERDICT': return presentTableCaseVerdict(evidence).label;
    case 'EXECUTION': return evidence.execution;
    case 'ASSERTIONS': return evidence.assertions;
    case 'FRESHNESS': return evidence.freshness;
    case 'PROOF': return evidence.proofStrength;
    case 'DURATION': return evidence.durationMs === null ? '' : `${evidence.durationMs} ms`;
    case 'ATTEMPTS': return evidence.attempt || '';
    case 'BASELINE': return evidence.flaky
      ? 'FLAKY'
      : evidence.baselineOutcome && evidence.baselineOutcome !== 'NONE'
        ? evidence.baselineOutcome.replace('_', ' ')
        : '';
  }
}

function sortValue(row: ScenarioTableRow, key: ScenarioTableSort['key']): string {
  switch (key) {
    case 'CANONICAL': return String(row.canonicalIndex).padStart(9, '0');
    case 'NAME': return row.name;
    case 'TYPE': return row.caseType;
    case 'VERDICT': return row.presentation.label;
  }
}

function setValueAtPath(root: unknown, path: string[], value: unknown): unknown {
  if (path.length === 0) return value;
  const record = root !== null && typeof root === 'object' && !Array.isArray(root)
    ? root as Record<string, unknown>
    : {};
  const [head, ...tail] = path;
  return { ...record, [head]: setValueAtPath(record[head], tail, value) };
}

function valueAtPath(root: unknown, path: string[]): unknown {
  return path.reduce<unknown>((value, key) => (
    value !== null && typeof value === 'object' ? (value as Record<string, unknown>)[key] : undefined
  ), root);
}

function parsePath(path: string): string[] {
  return path.split('/').slice(1).map(unescapePath);
}

function escapePath(value: string): string {
  return value.replace(/~/g, '~0').replace(/\//g, '~1');
}

function unescapePath(value: string): string {
  return value.replace(/~1/g, '/').replace(/~0/g, '~');
}

function uniqueSorted(values: string[]): string[] {
  return [...new Set(values)].sort((left, right) => left.localeCompare(right));
}

function isCaseType(value: unknown): value is ScenarioCaseType {
  return ['GOLDEN', 'NEGATIVE', 'BOUNDARY', 'REGRESSION', 'PROPERTY'].includes(String(value));
}

function fnv1a32(value: string): string {
  let hash = 0x811c9dc5;
  for (let index = 0; index < value.length; index += 1) {
    hash ^= value.charCodeAt(index);
    hash = Math.imul(hash, 0x01000193);
  }
  return (hash >>> 0).toString(16).padStart(8, '0');
}
