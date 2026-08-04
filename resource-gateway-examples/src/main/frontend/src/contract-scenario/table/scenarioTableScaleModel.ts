import type { ScenarioCaseType, ScenarioDraft, StoredScenarioDraftSet } from '../domain';

export type ScenarioTableSortField = 'CANONICAL' | 'NAME' | 'TYPE';
export type ScenarioTableSortDirection = 'ASC' | 'DESC';

/** Exact source-bound request for one bounded server-side Matrix page. */
export interface ScenarioTablePageQuery {
  schemaVersion: 'bloge.scenarioTablePageQuery.v1';
  expectedRevision: number;
  expectedDraftFingerprint: string;
  query: string;
  caseTypes: ScenarioCaseType[];
  sortField: ScenarioTableSortField;
  sortDirection: ScenarioTableSortDirection;
  cursor: string;
  limit: number;
}

export interface ScenarioTablePageRow {
  canonicalIndex: number;
  caseFingerprint: string;
  scenario: ScenarioDraft;
}

/** A page is meaningful only for the exact source revision and query fingerprint it declares. */
export interface ScenarioTablePage {
  schemaVersion: 'bloge.scenarioTablePage.v1';
  scenarioDraftSetId: string;
  revision: number;
  draftFingerprint: string;
  queryFingerprint: string;
  totalMatching: number;
  rows: ScenarioTablePageRow[];
  nextCursor: string;
}

export type ScenarioBulkEditField = 'NAME' | 'CASE_TYPE' | 'TAGS' | 'GIVEN_PATH';
export type ScenarioBulkEditOperation = 'SET' | 'REMOVE';

export interface ScenarioBulkCellEdit {
  caseId: string;
  expectedCaseFingerprint: string;
  field: ScenarioBulkEditField;
  path: string;
  operation: ScenarioBulkEditOperation;
  value: unknown;
}

/** One all-or-nothing edit command guarded at both draft and row granularity. */
export interface ScenarioBulkEditCommand {
  schemaVersion: 'bloge.scenarioBulkEditCommand.v1';
  commandId: string;
  expectedRevision: number;
  expectedDraftFingerprint: string;
  atomicity: 'ALL_OR_NOTHING';
  edits: ScenarioBulkCellEdit[];
}

/** Payload-free receipt suitable for logs, telemetry, and retry reconciliation. */
export interface ScenarioBulkEditResult {
  schemaVersion: 'bloge.scenarioBulkEditResult.v1';
  commandId: string;
  scenarioDraftSetId: string;
  sourceRevision: number;
  sourceDraftFingerprint: string;
  storedRevision: number;
  storedDraftFingerprint: string;
  touchedCells: number;
  editedCaseIds: string[];
  committedAt: string;
  committedBy: string;
}

export function createScenarioTablePageQuery(
  source: StoredScenarioDraftSet,
  options: Partial<Pick<ScenarioTablePageQuery,
    'query' | 'caseTypes' | 'sortField' | 'sortDirection' | 'cursor' | 'limit'>> = {},
): ScenarioTablePageQuery {
  return {
    schemaVersion: 'bloge.scenarioTablePageQuery.v1',
    expectedRevision: source.revision,
    expectedDraftFingerprint: source.fingerprint,
    query: options.query ?? '',
    caseTypes: options.caseTypes ?? [],
    sortField: options.sortField ?? 'CANONICAL',
    sortDirection: options.sortDirection ?? 'ASC',
    cursor: options.cursor ?? '',
    limit: options.limit ?? 100,
  };
}

export function createScenarioBulkEditCommand(
  source: StoredScenarioDraftSet,
  commandId: string,
  edits: ScenarioBulkCellEdit[],
): ScenarioBulkEditCommand {
  return {
    schemaVersion: 'bloge.scenarioBulkEditCommand.v1',
    commandId,
    expectedRevision: source.revision,
    expectedDraftFingerprint: source.fingerprint,
    atomicity: 'ALL_OR_NOTHING',
    edits,
  };
}
