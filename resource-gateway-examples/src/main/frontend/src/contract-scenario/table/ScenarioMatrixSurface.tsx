import { Fragment, useEffect, useMemo, useState } from 'react';
import { useI18n } from '../../i18n/I18nProvider';

import type { ScenarioCaseType } from '../domain';
import type { TableCaseVerdictPresentation } from '../tableDrivenTestStatus';
import type { TableSuiteDifferentialCounts, TableSuiteRunBatch } from './tableSuiteRunModel';
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
  batch?: TableSuiteRunBatch | null;
  runError?: string;
  baselineAvailable?: boolean;
  differentialCounts?: TableSuiteDifferentialCounts | null;
  disabled?: boolean;
  importDisabled?: boolean;
  importDisabledReason?: string;
  onSelectionChange: (selection: ScenarioTableSelection) => void;
  onOpenCase: (caseId: string) => void;
  onCellEdit: (caseId: string, column: ScenarioTableColumn, value: unknown) => void;
  onAddCase: () => void;
  onImportCases?: () => void;
  onRunSelection: (mode: ScenarioRunSelectionMode) => void;
  onCancelRun?: () => void;
  onRetryFailed?: () => void;
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
  batch = null,
  runError = '',
  baselineAvailable = false,
  differentialCounts = null,
  disabled = false,
  importDisabled = false,
  importDisabledReason = '',
  onSelectionChange,
  onOpenCase,
  onCellEdit,
  onAddCase,
  onImportCases,
  onRunSelection,
  onCancelRun,
  onRetryFailed,
}: ScenarioMatrixSurfaceProps) {
  const { t } = useI18n();
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
  const failedCount = differentialCounts?.failed ?? failedFromPreviousRun;
  const changedCount = differentialCounts?.changed ?? 0;
  const affectedCount = differentialCounts?.affected ?? 0;

  return (
    <section className="scenario-matrix" aria-label={t('Scenario test matrix')} data-testid="scenario-matrix">
      <header className="scenario-matrix-toolbar">
        <label className="scenario-matrix-search">
          <span className="visually-hidden">{t('Search cases')}</span>
          <input
            type="search"
            aria-label={t('Search cases')}
            placeholder={t('Search cases, ids, or tags')}
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
        </label>
        <label>
          <span className="visually-hidden">{t('Case type filter')}</span>
          <select
            aria-label={t('Case type filter')}
            value={caseType}
            onChange={(event) => setCaseType(event.target.value as ScenarioCaseType | '')}
          >
            <option value="">{t('All types')}</option>
            {CASE_TYPES.map((value) => <option value={value} key={value}>{t(caseTypeLabel(value))}</option>)}
          </select>
        </label>
        <label>
          <span className="visually-hidden">{t('Verdict filter')}</span>
          <select
            aria-label={t('Verdict filter')}
            value={tone}
            onChange={(event) => setTone(event.target.value as TableCaseVerdictPresentation['tone'] | '')}
          >
            <option value="">{t('All verdicts')}</option>
            {TONES.map((value) => <option value={value} key={value}>{t(capitalize(value))}</option>)}
          </select>
        </label>
        <label>
          <span className="visually-hidden">{t('Sort cases')}</span>
          <select
            aria-label={t('Sort cases')}
            value={`${sort.key}:${sort.direction}`}
            onChange={(event) => {
              const [key, direction] = event.target.value.split(':') as [ScenarioTableSort['key'], ScenarioTableSort['direction']];
              setSort({ key, direction });
            }}
          >
            <option value="CANONICAL:ASC">{t('Canonical order')}</option>
            <option value="NAME:ASC">{t('Name A-Z')}</option>
            <option value="NAME:DESC">{t('Name Z-A')}</option>
            <option value="TYPE:ASC">{t('Type')}</option>
            <option value="VERDICT:ASC">{t('Verdict')}</option>
          </select>
        </label>
        <details className="scenario-column-menu">
          <summary>{t('Columns {visible}/{total}', { visible: columns.length, total: projection.columns.length })}</summary>
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
                <span>{t(column.group)} / {t(column.label)}</span>
              </label>
            ))}
          </div>
        </details>
        <button type="button" className="secondary compact" onClick={onAddCase} disabled={disabled}>
          {t('Add case')}
        </button>
        {onImportCases && (
          <button
            type="button"
            className="secondary compact"
            onClick={onImportCases}
            disabled={disabled || importDisabled}
            title={importDisabled ? t(importDisabledReason) : t('Import CSV or JSON cases')}
          >
            {t('Import cases')}
          </button>
        )}
      </header>

      <div className="scenario-matrix-context" role="status">
        <span>{t('{count} canonical cases', { count: projection.rows.length })}</span>
        <span>{t('{count} matching', { count: filteredRows.length })}</span>
        <span>{t('{shown} / {total} shown', { shown: rows.length, total: filteredRows.length })}</span>
        <span>{t('{count} selected', { count: selection.selectedCaseIds.length })}</span>
        <code title={projection.projectionFingerprint}>{shortFingerprint(projection.projectionFingerprint)}</code>
      </div>

      <div className="scenario-matrix-run-stack">
        {batch && (
          <section className="scenario-matrix-run" data-status={batch.status} aria-label={t('Server batch status')}>
          <header>
            <div>
              <span>{t('Server batch')}</span>
              <strong>{t(batchStatusLabel(batch.status))}</strong>
              <code title={batch.batchId}>{shortBatchId(batch.batchId)}</code>
            </div>
            <div className="scenario-matrix-run-actions">
              {!isTerminalBatch(batch) && onCancelRun && (
                <button type="button" className="secondary compact" onClick={onCancelRun}>
                  {t('Cancel')}
                </button>
              )}
              {isTerminalBatch(batch) && batch.counts.failed > 0 && onRetryFailed && (
                <button type="button" className="secondary compact" onClick={onRetryFailed}>
                  {t('Retry failed')}
                </button>
              )}
            </div>
          </header>
          <dl>
            <div><dt>{t('Closure')}</dt><dd>{batch.selection.caseIds.length} {batch.selection.mode.toLocaleLowerCase()}</dd></div>
            <div><dt>{t('Passed')}</dt><dd>{batch.counts.succeeded}</dd></div>
            <div><dt>{t('Failed')}</dt><dd>{batch.counts.failed}</dd></div>
            <div><dt>{t('Waiting')}</dt><dd>{batch.counts.queued + batch.counts.running}</dd></div>
            <div>
              <dt>{t('Promotion')}</dt>
              <dd data-eligible={batch.promotion.eligible} title={batch.promotion.reason}>
                {t(batch.promotion.eligible ? 'Eligible' : promotionLabel(batch))}
              </dd>
            </div>
          </dl>
          </section>
        )}
        {runError && <div className="scenario-matrix-run-error" role="alert">{runError}</div>}
      </div>

      <div className="scenario-matrix-scroll" tabIndex={0} aria-label={t('Scrollable Scenario matrix')}>
        <table>
          <thead>
            <tr className="scenario-matrix-groups">
              <th rowSpan={2} className="scenario-matrix-select">
                <input
                  type="checkbox"
                  aria-label={t('Select visible cases')}
                  checked={allVisibleSelected}
                  onChange={(event) => onSelectionChange(selectVisibleScenarios(
                    selection,
                    visibleCaseIds,
                    event.target.checked,
                  ))}
                />
              </th>
              {groupSpans(columns).map((group) => (
                <th colSpan={group.count} scope="colgroup" key={group.name}>{t(group.name)}</th>
              ))}
              <th rowSpan={2} className="scenario-matrix-actions">{t('Actions')}</th>
            </tr>
            <tr>
              {columns.map((column) => (
                <th scope="col" key={column.columnId} title={column.path}>{t(column.label)}</th>
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
                    aria-label={t('Select {name}', { name: row.name })}
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
                      aria-label={t('Explain failure for {name}', { name: row.name })}
                      onClick={() => setExpandedFailures((current) => (
                        current.includes(row.caseId)
                          ? current.filter((caseId) => caseId !== row.caseId)
                          : [...current, row.caseId]
                      ))}
                    >
                      {t('Why')}
                    </button>
                  )}
                  <button
                    type="button"
                    className="secondary compact"
                    onClick={() => onOpenCase(row.caseId)}
                    disabled={disabled}
                  >
                    {t('Open')}
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
            <strong>{t('No cases match this view.')}</strong>
            <button type="button" className="secondary compact" onClick={() => {
              setQuery('');
              setCaseType('');
              setTone('');
            }}>
              {t('Clear filters')}
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
          {t('Show next {count} cases', { count: Math.min(WINDOW_SIZE, filteredRows.length - rows.length) })}
        </button>
      )}

      <footer className="scenario-matrix-bulkbar">
        <div>
          <strong>{t('{count} selected', { count: selection.selectedCaseIds.length })}</strong>
          <span>{t('Selection is independent from the current filter and sort.')}</span>
        </div>
        <div>
          <button
            type="button"
            className="secondary"
            disabled={disabled || !baselineAvailable || failedCount === 0}
            onClick={() => onRunSelection('FAILED')}
          >
            {t('Run failed ({count})', { count: failedCount })}
          </button>
          <button
            type="button"
            className="secondary"
            disabled={disabled || !baselineAvailable || changedCount === 0}
            onClick={() => onRunSelection('CHANGED')}
            title={!baselineAvailable
              ? t('Run all once to create a complete baseline')
              : changedCount === 0 ? t('No cases changed since the complete baseline')
                : t('Run cases changed since the complete baseline')}
          >
            {t('Run changed ({count})', { count: changedCount })}
          </button>
          <button
            type="button"
            className="secondary"
            disabled={disabled || !baselineAvailable || affectedCount === 0}
            onClick={() => onRunSelection('AFFECTED')}
            title={!baselineAvailable
              ? t('Run all once to create a complete baseline')
              : affectedCount === 0 ? t('No cases are affected relative to the complete baseline')
                : t('Run changed, failed, or target-affected cases')}
          >
            {t('Run affected ({count})', { count: affectedCount })}
          </button>
          <button
            type="button"
            className="secondary"
            disabled={disabled || projection.rows.length === 0}
            onClick={() => onRunSelection('ALL')}
          >
            {t('Run all')}
          </button>
          <button
            type="button"
            className="primary"
            disabled={disabled || selection.selectedCaseIds.length === 0}
            onClick={() => onRunSelection('SELECTED')}
            data-testid="scenario-run-selected"
          >
            {runningCaseIds.length > 0 ? t('Running {count}...', { count: runningCaseIds.length }) : t('Run selected')}
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
  const { t } = useI18n();
  if (!column.editable) {
    return <td data-tone={tone} title={displayValue(value)}><span>{displayValue(value)}</span></td>;
  }
  if (column.binding.kind === 'CASE_TYPE') {
    return (
      <td>
        <select aria-label={t('{label} value', { label: t(column.label) })} value={String(value)} onChange={(event) => onChange(event.target.value)}>
          {CASE_TYPES.map((caseType) => <option value={caseType} key={caseType}>{t(caseTypeLabel(caseType))}</option>)}
        </select>
      </td>
    );
  }
  if (typeof value === 'boolean') {
    return (
      <td>
        <input
          type="checkbox"
          aria-label={t('{label} value', { label: t(column.label) })}
          checked={value}
          onChange={(event) => onChange(event.target.checked)}
        />
      </td>
    );
  }
  return (
    <td>
      <input
        aria-label={t('{label} value', { label: t(column.label) })}
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

function shortBatchId(value: string): string {
  return value.length > 22 ? `${value.slice(0, 19)}...` : value;
}

function isTerminalBatch(batch: TableSuiteRunBatch): boolean {
  return ['SUCCEEDED', 'FAILED', 'CANCELLED', 'BUDGET_STOPPED'].includes(batch.status);
}

function batchStatusLabel(status: TableSuiteRunBatch['status']): string {
  switch (status) {
    case 'QUEUED': return 'Queued';
    case 'RUNNING': return 'Running';
    case 'SUCCEEDED': return 'Succeeded';
    case 'FAILED': return 'Failed';
    case 'CANCELLED': return 'Cancelled';
    case 'BUDGET_STOPPED': return 'Stopped by budget';
  }
}

function promotionLabel(batch: TableSuiteRunBatch): string {
  if (!isTerminalBatch(batch)) return 'Pending';
  if (!batch.selection.fullSuite) return 'Partial only';
  return 'Blocked';
}
