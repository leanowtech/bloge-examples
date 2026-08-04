import { Fragment, useEffect, useMemo, useState } from 'react';

import type { ScenarioCaseType } from '../domain';
import type { TableCaseVerdictPresentation } from '../tableDrivenTestStatus';
import {
  filterAndSortScenarioRows,
  selectVisibleScenarios,
  toggleScenarioSelection,
  type ScenarioRunSelectionMode,
  type ScenarioTableColumn,
  type ScenarioTableProjection,
  type ScenarioTableSelection,
  type ScenarioTableSort,
} from './scenarioTableModel';

interface ScenarioMatrixSurfaceProps {
  projection: ScenarioTableProjection;
  selection: ScenarioTableSelection;
  previousRunCaseIds: string[];
  runningCaseIds: string[];
  disabled?: boolean;
  importDisabled?: boolean;
  importDisabledReason?: string;
  onSelectionChange: (selection: ScenarioTableSelection) => void;
  onOpenCase: (caseId: string) => void;
  onCellEdit: (caseId: string, column: ScenarioTableColumn, value: unknown) => void;
  onAddCase: () => void;
  onImportCases?: () => void;
  onRunSelection: (mode: ScenarioRunSelectionMode) => void;
}

const CASE_TYPES: ScenarioCaseType[] = ['GOLDEN', 'NEGATIVE', 'BOUNDARY', 'REGRESSION', 'PROPERTY'];
const TONES: TableCaseVerdictPresentation['tone'][] = [
  'neutral', 'running', 'passed', 'warning', 'failed', 'stale',
];
const WINDOW_SIZE = 50;

export default function ScenarioMatrixSurface({
  projection,
  selection,
  previousRunCaseIds,
  runningCaseIds,
  disabled = false,
  importDisabled = false,
  importDisabledReason = '',
  onSelectionChange,
  onOpenCase,
  onCellEdit,
  onAddCase,
  onImportCases,
  onRunSelection,
}: ScenarioMatrixSurfaceProps) {
  const [query, setQuery] = useState('');
  const [caseType, setCaseType] = useState<ScenarioCaseType | ''>('');
  const [tone, setTone] = useState<TableCaseVerdictPresentation['tone'] | ''>('');
  const [sort, setSort] = useState<ScenarioTableSort>({ key: 'CANONICAL', direction: 'ASC' });
  const [visibleLimit, setVisibleLimit] = useState(WINDOW_SIZE);
  const [expandedFailures, setExpandedFailures] = useState<string[]>([]);
  const [visibleColumnIds, setVisibleColumnIds] = useState<string[]>(() => (
    defaultVisibleColumnIds(projection.columns)
  ));

  useEffect(() => {
    setVisibleColumnIds((current) => {
      const available = new Set(projection.columns.map((column) => column.columnId));
      const retained = current.filter((columnId) => available.has(columnId));
      return retained.length > 0 ? retained : defaultVisibleColumnIds(projection.columns);
    });
  }, [projection.columns]);

  useEffect(() => setVisibleLimit(WINDOW_SIZE), [caseType, query, sort, tone]);

  const filteredRows = useMemo(() => filterAndSortScenarioRows(
    projection,
    {
      query,
      caseTypes: caseType ? [caseType] : [],
      tones: tone ? [tone] : [],
    },
    sort,
  ), [caseType, projection, query, sort, tone]);
  const rows = filteredRows.slice(0, visibleLimit);
  const columns = projection.columns.filter((column) => visibleColumnIds.includes(column.columnId));
  const selected = new Set(selection.selectedCaseIds);
  const visibleCaseIds = rows.map((row) => row.caseId);
  const allVisibleSelected = visibleCaseIds.length > 0 && visibleCaseIds.every((caseId) => selected.has(caseId));
  const failedFromPreviousRun = projection.rows.filter((row) => (
    previousRunCaseIds.includes(row.caseId) && row.presentation.tone === 'failed'
  )).length;

  return (
    <section className="scenario-matrix" aria-label="Scenario test matrix" data-testid="scenario-matrix">
      <header className="scenario-matrix-toolbar">
        <label className="scenario-matrix-search">
          <span className="visually-hidden">Search cases</span>
          <input
            type="search"
            aria-label="Search cases"
            placeholder="Search cases, ids, or tags"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
        </label>
        <label>
          <span className="visually-hidden">Case type filter</span>
          <select
            aria-label="Case type filter"
            value={caseType}
            onChange={(event) => setCaseType(event.target.value as ScenarioCaseType | '')}
          >
            <option value="">All types</option>
            {CASE_TYPES.map((value) => <option value={value} key={value}>{caseTypeLabel(value)}</option>)}
          </select>
        </label>
        <label>
          <span className="visually-hidden">Verdict filter</span>
          <select
            aria-label="Verdict filter"
            value={tone}
            onChange={(event) => setTone(event.target.value as TableCaseVerdictPresentation['tone'] | '')}
          >
            <option value="">All verdicts</option>
            {TONES.map((value) => <option value={value} key={value}>{capitalize(value)}</option>)}
          </select>
        </label>
        <label>
          <span className="visually-hidden">Sort cases</span>
          <select
            aria-label="Sort cases"
            value={`${sort.key}:${sort.direction}`}
            onChange={(event) => {
              const [key, direction] = event.target.value.split(':') as [ScenarioTableSort['key'], ScenarioTableSort['direction']];
              setSort({ key, direction });
            }}
          >
            <option value="CANONICAL:ASC">Canonical order</option>
            <option value="NAME:ASC">Name A-Z</option>
            <option value="NAME:DESC">Name Z-A</option>
            <option value="TYPE:ASC">Type</option>
            <option value="VERDICT:ASC">Verdict</option>
          </select>
        </label>
        <details className="scenario-column-menu">
          <summary>Columns {columns.length}/{projection.columns.length}</summary>
          <div>
            {projection.columns.map((column) => (
              <label key={column.columnId}>
                <input
                  type="checkbox"
                  checked={visibleColumnIds.includes(column.columnId)}
                  disabled={column.columnId === 'case:name'}
                  onChange={(event) => setVisibleColumnIds((current) => (
                    event.target.checked
                      ? [...current, column.columnId]
                      : current.filter((columnId) => columnId !== column.columnId)
                  ))}
                />
                <span>{column.group} / {column.label}</span>
              </label>
            ))}
          </div>
        </details>
        <button type="button" className="secondary compact" onClick={onAddCase} disabled={disabled}>
          Add case
        </button>
        {onImportCases && (
          <button
            type="button"
            className="secondary compact"
            onClick={onImportCases}
            disabled={disabled || importDisabled}
            title={importDisabled ? importDisabledReason : 'Import CSV or JSON cases'}
          >
            Import cases
          </button>
        )}
      </header>

      <div className="scenario-matrix-context" role="status">
        <span><strong>{projection.rows.length}</strong> canonical cases</span>
        <span><strong>{filteredRows.length}</strong> matching</span>
        <span><strong>{rows.length} / {filteredRows.length}</strong> shown</span>
        <span><strong>{selection.selectedCaseIds.length}</strong> selected</span>
        <code title={projection.projectionFingerprint}>{shortFingerprint(projection.projectionFingerprint)}</code>
      </div>

      <div className="scenario-matrix-scroll" tabIndex={0} aria-label="Scrollable Scenario matrix">
        <table>
          <thead>
            <tr className="scenario-matrix-groups">
              <th rowSpan={2} className="scenario-matrix-select">
                <input
                  type="checkbox"
                  aria-label="Select visible cases"
                  checked={allVisibleSelected}
                  onChange={(event) => onSelectionChange(selectVisibleScenarios(
                    selection,
                    visibleCaseIds,
                    event.target.checked,
                  ))}
                />
              </th>
              {groupSpans(columns).map((group) => (
                <th colSpan={group.count} scope="colgroup" key={group.name}>{group.name}</th>
              ))}
              <th rowSpan={2} className="scenario-matrix-actions">Actions</th>
            </tr>
            <tr>
              {columns.map((column) => (
                <th scope="col" key={column.columnId} title={column.path}>{column.label}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <Fragment key={row.caseId}>
              <tr
                data-verdict={row.presentation.tone}
                data-testid={`scenario-matrix-row-${row.caseId}`}
                tabIndex={0}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' && event.target === event.currentTarget) {
                    event.preventDefault();
                    onOpenCase(row.caseId);
                  }
                }}
              >
                <td className="scenario-matrix-select">
                  <input
                    type="checkbox"
                    aria-label={`Select ${row.name}`}
                    checked={selected.has(row.caseId)}
                    onChange={(event) => onSelectionChange(toggleScenarioSelection(
                      selection,
                      row.caseId,
                      event.target.checked,
                    ))}
                  />
                </td>
                {columns.map((column) => (
                  <ScenarioMatrixCell
                    column={column}
                    value={row.values[column.columnId]}
                    tone={column.columnId === 'proof:verdict' ? row.presentation.tone : undefined}
                    key={column.columnId}
                    onChange={(value) => onCellEdit(row.caseId, column, value)}
                  />
                ))}
                <td className="scenario-matrix-actions">
                  {row.evidence.firstFailure && (
                    <button
                      type="button"
                      className="secondary compact"
                      aria-expanded={expandedFailures.includes(row.caseId)}
                      aria-label={`Explain failure for ${row.name}`}
                      onClick={() => setExpandedFailures((current) => (
                        current.includes(row.caseId)
                          ? current.filter((caseId) => caseId !== row.caseId)
                          : [...current, row.caseId]
                      ))}
                    >
                      Why
                    </button>
                  )}
                  <button
                    type="button"
                    className="secondary compact"
                    onClick={() => onOpenCase(row.caseId)}
                    disabled={disabled}
                  >
                    Open
                  </button>
                </td>
              </tr>
              {row.evidence.firstFailure && expandedFailures.includes(row.caseId) && (
                <tr className="scenario-matrix-failure">
                  <td colSpan={columns.length + 2}>
                    <strong>{row.evidence.firstFailure.category}</strong>
                    <code>{row.evidence.firstFailure.target}</code>
                    <span>{row.evidence.firstFailure.message}</span>
                  </td>
                </tr>
              )}
              </Fragment>
            ))}
          </tbody>
        </table>
        {rows.length === 0 && (
          <div className="scenario-matrix-empty">
            <strong>No cases match this view.</strong>
            <button type="button" className="secondary compact" onClick={() => {
              setQuery('');
              setCaseType('');
              setTone('');
            }}>
              Clear filters
            </button>
          </div>
        )}
      </div>

      {filteredRows.length > rows.length && (
        <button
          type="button"
          className="scenario-matrix-more"
          onClick={() => setVisibleLimit((limit) => limit + WINDOW_SIZE)}
        >
          Show next {Math.min(WINDOW_SIZE, filteredRows.length - rows.length)} cases
        </button>
      )}

      <footer className="scenario-matrix-bulkbar">
        <div>
          <strong>{selection.selectedCaseIds.length} selected</strong>
          <span>Selection is independent from the current filter and sort.</span>
        </div>
        <div>
          <button
            type="button"
            className="secondary"
            disabled={disabled || failedFromPreviousRun === 0}
            onClick={() => onRunSelection('FAILED')}
          >
            Run failed ({failedFromPreviousRun})
          </button>
          <button
            type="button"
            className="secondary"
            disabled={disabled || projection.rows.length === 0}
            onClick={() => onRunSelection('ALL')}
          >
            Run all
          </button>
          <button
            type="button"
            className="primary"
            disabled={disabled || selection.selectedCaseIds.length === 0}
            onClick={() => onRunSelection('SELECTED')}
            data-testid="scenario-run-selected"
          >
            {runningCaseIds.length > 0 ? `Running ${runningCaseIds.length}...` : 'Run selected'}
          </button>
        </div>
      </footer>
    </section>
  );
}

function ScenarioMatrixCell({
  column,
  value,
  tone,
  onChange,
}: {
  column: ScenarioTableColumn;
  value: unknown;
  tone?: TableCaseVerdictPresentation['tone'];
  onChange: (value: unknown) => void;
}) {
  if (!column.editable) {
    return <td data-tone={tone} title={displayValue(value)}><span>{displayValue(value)}</span></td>;
  }
  if (column.binding.kind === 'CASE_TYPE') {
    return (
      <td>
        <select aria-label={`${column.label} value`} value={String(value)} onChange={(event) => onChange(event.target.value)}>
          {CASE_TYPES.map((caseType) => <option value={caseType} key={caseType}>{caseTypeLabel(caseType)}</option>)}
        </select>
      </td>
    );
  }
  if (typeof value === 'boolean') {
    return (
      <td>
        <input
          type="checkbox"
          aria-label={`${column.label} value`}
          checked={value}
          onChange={(event) => onChange(event.target.checked)}
        />
      </td>
    );
  }
  return (
    <td>
      <input
        aria-label={`${column.label} value`}
        value={displayValue(value)}
        onChange={(event) => onChange(parseCellValue(event.target.value, value))}
      />
    </td>
  );
}

function defaultVisibleColumnIds(columns: ScenarioTableColumn[]): string[] {
  const limits: Record<ScenarioTableColumn['group'], number> = {
    CASE: 2,
    GIVEN: 3,
    DEPENDENCY: 1,
    THEN: 3,
    PROOF: 6,
  };
  const counts: Partial<Record<ScenarioTableColumn['group'], number>> = {};
  return columns.filter((column) => {
    const count = counts[column.group] ?? 0;
    counts[column.group] = count + 1;
    return count < limits[column.group];
  }).map((column) => column.columnId);
}

function groupSpans(columns: ScenarioTableColumn[]): Array<{ name: string; count: number }> {
  return columns.reduce<Array<{ name: string; count: number }>>((groups, column) => {
    const last = groups[groups.length - 1];
    if (last?.name === column.group) last.count += 1;
    else groups.push({ name: column.group, count: 1 });
    return groups;
  }, []);
}

function parseCellValue(value: string, previous: unknown): unknown {
  if (typeof previous === 'number') {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : previous;
  }
  return value;
}

function displayValue(value: unknown): string {
  if (value === undefined || value === null) return '';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

function caseTypeLabel(value: ScenarioCaseType): string {
  return value.charAt(0) + value.slice(1).toLocaleLowerCase();
}

function capitalize(value: string): string {
  return value.charAt(0).toLocaleUpperCase() + value.slice(1);
}

function shortFingerprint(value: string): string {
  return value.length > 18 ? `${value.slice(0, 15)}...` : value;
}
