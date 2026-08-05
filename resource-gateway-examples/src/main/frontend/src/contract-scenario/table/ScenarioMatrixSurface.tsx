import { Fragment, useEffect, useMemo, useState } from 'react';
import { useI18n } from '../../i18n/I18nProvider';
import type { AuthorCommandAvailability } from '../../author/task/taskStateProjection';

import type { ScenarioCaseType } from '../domain';
import type { TableCaseVerdictPresentation } from '../tableDrivenTestStatus';
import type { TableSuiteDifferentialCounts, TableSuiteRunBatch } from './tableSuiteRunModel';
import {
  filterAndSortScenarioRows,
  scenarioMatrixFacetCounts,
  selectVisibleScenarios,
  toggleScenarioSelection,
  type ScenarioMatrixResultFacet,
  type ScenarioRunSelectionMode,
  type ScenarioTableColumn,
  type ScenarioTableProjection,
  type ScenarioTableRow,
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
  runCommand?: AuthorCommandAvailability;
  importDisabled?: boolean;
  importDisabledReason?: string;
  onSelectionChange: (selection: ScenarioTableSelection) => void;
  onOpenCase: (caseId: string) => void;
  onCellEdit: (caseId: string, column: ScenarioTableColumn, value: unknown) => void;
  onAddCase: (caseType?: ScenarioCaseType) => void;
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
  runCommand,
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
  const { m, t } = useI18n();
  const [query, setQuery] = useState('');
  const [caseType, setCaseType] = useState<ScenarioCaseType | ''>('');
  const [tone, setTone] = useState<TableCaseVerdictPresentation['tone'] | ''>('');
  const [facet, setFacet] = useState<ScenarioMatrixResultFacet>('ALL');
  const [sort, setSort] = useState<ScenarioTableSort>({ key: 'CANONICAL', direction: 'ASC' });
  const [pageIndex, setPageIndex] = useState(0);
  const [expandedRows, setExpandedRows] = useState<string[]>([]);

  useEffect(() => setPageIndex(0), [caseType, facet, query, sort, tone]);

  const filteredRows = useMemo(() => filterAndSortScenarioRows(
    projection,
    {
      query,
      caseTypes: caseType ? [caseType] : [],
      tones: tone ? [tone] : [],
      facets: [facet],
      targetChanged: differentialCounts?.targetChanged ?? false,
    },
    sort,
  ), [caseType, differentialCounts?.targetChanged, facet, projection, query, sort, tone]);
  const pageStart = Math.min(pageIndex * WINDOW_SIZE, Math.max(0, filteredRows.length - 1));
  const rows = filteredRows.slice(pageStart, pageStart + WINDOW_SIZE);
  const facetCounts = useMemo(() => scenarioMatrixFacetCounts(
    projection,
    differentialCounts?.targetChanged ?? false,
  ), [differentialCounts?.targetChanged, projection]);
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
        <details className="scenario-matrix-more-filters">
          <summary>{t('More filters')}</summary>
          <div>
            <label>
              <span>{t('Case type')}</span>
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
              <span>{t('Verdict')}</span>
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
              <span>{t('Sort')}</span>
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
          </div>
        </details>
        <details className="scenario-preset-menu">
          <summary className="secondary compact">{t('Add case')}</summary>
          <div>
            {(['GOLDEN', 'NEGATIVE', 'BOUNDARY', 'REGRESSION'] as const).map((value) => (
              <button type="button" key={value} disabled={disabled} onClick={() => onAddCase(value)}>
                <strong>{t(caseTypeLabel(value))}</strong>
                <span>{t(presetDescription(value))}</span>
              </button>
            ))}
          </div>
        </details>
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

      <div className="scenario-matrix-facets" role="group" aria-label={t('Result filters')}>
        {(['ALL', 'FAILED', 'CHANGED', 'IMPACTED', 'STALE', 'UNPROVEN'] as const).map((value) => (
          <button
            type="button"
            key={value}
            aria-pressed={facet === value}
            onClick={() => setFacet(value)}
          >
            <span>{t(facetLabel(value))}</span>
            <strong>{facetCounts[value]}</strong>
          </button>
        ))}
      </div>

      <div className="scenario-matrix-context" role="status">
        <span>{t('{count} canonical cases', { count: projection.rows.length })}</span>
        <span>{t('{count} matching', { count: filteredRows.length })}</span>
        <span>{filteredRows.length === 0
          ? t('0 shown')
          : t('{start}-{end} / {total} shown', {
            start: pageStart + 1,
            end: pageStart + rows.length,
            total: filteredRows.length,
          })}</span>
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
        <table aria-label={t('Scenario result summary')}>
          <thead>
            <tr data-testid="scenario-matrix-summary-columns">
              <th className="scenario-matrix-select">
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
              <th scope="col">{t('Case')}</th>
              <th scope="col">{t('Result')}</th>
              <th scope="col">{t('Given')}</th>
              <th scope="col">{t('Dependencies')}</th>
              <th scope="col">{t('Assertions')}</th>
              <th scope="col">{t('Duration')}</th>
              <th scope="col">{t('Currentness')}</th>
              <th className="scenario-matrix-actions">{t('Actions')}</th>
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
                <td className="scenario-matrix-case-cell">
                  <strong>{row.name}</strong>
                  <span><b>{t(caseTypeLabel(row.caseType))}</b>{row.tags.length > 0 ? ` · ${row.tags.slice(0, 2).join(', ')}` : ''}</span>
                </td>
                <td className="scenario-matrix-result-cell" data-tone={row.presentation.tone}>
                  <strong>{t(row.presentation.label)}</strong>
                  <span>{row.evidence.firstFailure?.message ?? t(row.presentation.detail)}</span>
                </td>
                <td title={summarizeGiven(row)}>{summarizeGiven(row)}</td>
                <td>{t('{controlled}/{total} controlled', {
                  controlled: row.summary.controlledDependencyCount,
                  total: row.summary.dependencyCount,
                })}</td>
                <td>{row.summary.assertionCount === 0
                  ? <strong className="scenario-matrix-needs-oracle">{t('Needs oracle')}</strong>
                  : t('{count} checks', { count: row.summary.assertionCount })}</td>
                <td>{row.evidence.durationMs === null ? '—' : `${row.evidence.durationMs} ms`}</td>
                <td className="scenario-matrix-currentness">
                  <strong data-freshness={row.evidence.freshness}>{t(freshnessLabel(row.evidence.freshness))}</strong>
                  <span>{t(proofLabel(row.evidence.proofStrength))}</span>
                </td>
                <td className="scenario-matrix-actions">
                  <button
                    type="button"
                    className="secondary compact"
                    aria-expanded={expandedRows.includes(row.caseId)}
                    aria-label={t('Inspect {name}', { name: row.name })}
                    onClick={() => setExpandedRows((current) => (
                      current.includes(row.caseId)
                        ? current.filter((caseId) => caseId !== row.caseId)
                        : [...current, row.caseId]
                    ))}
                  >
                    {t('Inspect')}
                  </button>
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
              {expandedRows.includes(row.caseId) && (
                <tr className="scenario-matrix-detail-row">
                  <td colSpan={9}>
                    <ScenarioMatrixDetails
                      row={row}
                      columns={projection.columns}
                      disabled={disabled}
                      onCellEdit={onCellEdit}
                      onOpenCase={onOpenCase}
                    />
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

      <nav className="scenario-matrix-window" aria-label={t('Case result pages')}>
        <button
          type="button"
          className="secondary compact"
          disabled={pageStart === 0}
          onClick={() => setPageIndex((index) => Math.max(0, index - 1))}
        >
          {t('Previous {count}', { count: WINDOW_SIZE })}
        </button>
        <span>{filteredRows.length === 0 ? t('No rows') : t('Page {current} of {total}', {
          current: Math.floor(pageStart / WINDOW_SIZE) + 1,
          total: Math.ceil(filteredRows.length / WINDOW_SIZE),
        })}</span>
        <button
          type="button"
          className="secondary compact"
          disabled={pageStart + rows.length >= filteredRows.length}
          onClick={() => setPageIndex((index) => index + 1)}
        >
          {t('Next {count}', {
            count: Math.min(WINDOW_SIZE, Math.max(0, filteredRows.length - pageStart - rows.length)) || WINDOW_SIZE,
          })}
        </button>
      </nav>

      <footer className="scenario-matrix-bulkbar">
        <div>
          <strong>{t('{count} selected', { count: selection.selectedCaseIds.length })}</strong>
          <span>{runCommand?.state === 'BLOCKED' && runCommand.messageId
            ? m(runCommand.messageId)
            : t(runCommand?.state === 'BLOCKED'
              ? runCommand.message
              : 'Selection is independent from the current filter and sort.')}</span>
        </div>
        <div>
          <button
            type="button"
            className="secondary"
            disabled={disabled || runCommand?.enabled === false || !baselineAvailable || failedCount === 0}
            onClick={() => onRunSelection('FAILED')}
          >
            {t('Run failed ({count})', { count: failedCount })}
          </button>
          <button
            type="button"
            className="secondary"
            disabled={disabled || runCommand?.enabled === false || !baselineAvailable || changedCount === 0}
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
            disabled={disabled || runCommand?.enabled === false || !baselineAvailable || affectedCount === 0}
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
            disabled={disabled || runCommand?.enabled === false || projection.rows.length === 0}
            onClick={() => onRunSelection('ALL')}
          >
            {t('Run all')}
          </button>
          <button
            type="button"
            className="primary"
            disabled={disabled || runCommand?.enabled === false || selection.selectedCaseIds.length === 0}
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

function ScenarioMatrixDetails({
  row,
  columns,
  disabled,
  onCellEdit,
  onOpenCase,
}: {
  row: ScenarioTableRow;
  columns: ScenarioTableColumn[];
  disabled: boolean;
  onCellEdit: (caseId: string, column: ScenarioTableColumn, value: unknown) => void;
  onOpenCase: (caseId: string) => void;
}) {
  const { t } = useI18n();
  const givenColumns = columns.filter((column) => column.group === 'GIVEN');
  const dependencyColumns = columns.filter((column) => column.group === 'DEPENDENCY');
  const assertionColumns = columns.filter((column) => column.group === 'THEN');
  const diffs = row.evidence.assertionDiffs ?? [];
  return (
    <div className="scenario-matrix-detail" data-testid={`scenario-matrix-detail-${row.caseId}`}>
      <section>
        <header><strong>{t('Given')}</strong><span>{t('{count} fields', { count: row.summary.givenFieldCount })}</span></header>
        <div className="scenario-matrix-detail-fields">
          {givenColumns.map((column) => {
            const value = row.values[column.columnId];
            return (
              <label key={column.columnId}>
                <span>{column.label}</span>
                {typeof value === 'boolean' ? (
                  <input
                    type="checkbox"
                    checked={value}
                    disabled={disabled}
                    onChange={(event) => onCellEdit(row.caseId, column, event.target.checked)}
                  />
                ) : (
                  <input
                    aria-label={t('{label} value', { label: column.label })}
                    value={displayValue(value)}
                    disabled={disabled}
                    onChange={(event) => onCellEdit(
                      row.caseId,
                      column,
                      parseCellValue(event.target.value, value),
                    )}
                  />
                )}
              </label>
            );
          })}
        </div>
      </section>
      <section>
        <header><strong>{t('Dependencies')}</strong><span>{t('{count} controlled', { count: row.summary.controlledDependencyCount })}</span></header>
        <p className="scenario-matrix-subject-mode">
          <strong>{t('Subject under test')}: {t(subjectModeLabel(row.evidence.subjectMode))}</strong>
          <span>{row.evidence.subjectMode === 'SCHEMA_ONLY'
            ? t('Schema is the subject; no runtime implementation is invoked.')
            : row.summary.controlledDependencyCount > 0
              ? t('{count} dependencies controlled; the subject still executes normally.', {
                count: row.summary.controlledDependencyCount,
              })
              : t('No dependency overrides; the target runs normally.')}</span>
        </p>
        <DetailValues
          empty={t('No dependency overrides; the target runs normally.')}
          values={dependencyColumns.map((column) => ({
            label: column.label,
            value: row.values[column.columnId],
          }))}
        />
      </section>
      <section className="scenario-matrix-diff">
        <header><strong>{t('Expected / Actual / Diff')}</strong><span>{t('{count} checks', { count: row.summary.assertionCount })}</span></header>
        {diffs.length > 0 ? diffs.map((diff) => (
          <article key={diff.assertionId} data-passed={diff.passed}>
            <header><code>{diff.path || '$'}</code><strong>{t(diff.passed ? 'Matched' : 'Different')}</strong></header>
            <div><span>{t('Expected')}</span><pre>{prettyValue(diff.expected)}</pre></div>
            <div><span>{t('Actual')}</span><pre>{prettyValue(diff.actual)}</pre></div>
            <p>{diff.detail}</p>
          </article>
        )) : (
          <DetailValues
            empty={row.summary.assertionCount === 0
              ? t('No business oracle yet. Open the Case and define the expected outcome.')
              : t('Run this Case to compare expected and actual values.')}
            values={assertionColumns.map((column) => ({
              label: column.label,
              value: row.values[column.columnId],
            }))}
          />
        )}
      </section>
      <section>
        <header><strong>{t('Proof')}</strong><span>{t(proofLabel(row.evidence.proofStrength))}</span></header>
        <dl className="scenario-matrix-proof-details">
          <div><dt>{t('Subject')}</dt><dd>{t(subjectModeLabel(row.evidence.subjectMode))}</dd></div>
          <div><dt>{t('Execution')}</dt><dd>{t(row.evidence.execution)}</dd></div>
          <div><dt>{t('Assertions')}</dt><dd>{t(row.evidence.assertions)}</dd></div>
          <div><dt>{t('Freshness')}</dt><dd>{t(row.evidence.freshness)}</dd></div>
          <div><dt>{t('Attempt')}</dt><dd>{row.evidence.attempt || '—'}</dd></div>
        </dl>
        {row.evidence.firstFailure && (
          <p className="scenario-matrix-detail-failure">
            <strong>{row.evidence.firstFailure.category}</strong>
            <code>{row.evidence.firstFailure.target}</code>
            <span>{row.evidence.firstFailure.message}</span>
          </p>
        )}
        <button type="button" className="secondary compact" onClick={() => onOpenCase(row.caseId)}>
          {t('Edit full Case')}
        </button>
      </section>
    </div>
  );
}

function DetailValues({
  values,
  empty,
}: {
  values: Array<{ label: string; value: unknown }>;
  empty: string;
}) {
  const populated = values.filter((item) => displayValue(item.value) !== '');
  if (populated.length === 0) return <p className="scenario-matrix-detail-empty">{empty}</p>;
  return (
    <dl className="scenario-matrix-detail-values">
      {populated.map((item) => (
        <div key={item.label}>
          <dt title={item.label}>{businessLabel(item.label)}</dt>
          <dd title={displayValue(item.value)}>{displayValue(item.value)}</dd>
        </div>
      ))}
    </dl>
  );
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

function facetLabel(value: ScenarioMatrixResultFacet): string {
  switch (value) {
    case 'ALL': return 'All';
    case 'FAILED': return 'Failed';
    case 'CHANGED': return 'Changed';
    case 'IMPACTED': return 'Impacted';
    case 'STALE': return 'Stale';
    case 'UNPROVEN': return 'Unproven';
  }
}

function presetDescription(value: ScenarioCaseType): string {
  switch (value) {
    case 'GOLDEN': return 'Typical input with a reviewable expected result';
    case 'NEGATIVE': return 'Adverse input marked as needing an error oracle';
    case 'BOUNDARY': return 'Values derived from declared schema limits';
    case 'REGRESSION': return 'Typical input ready to retain as a guard';
    case 'PROPERTY': return 'Generated input awaiting a property oracle';
  }
}

function summarizeGiven(row: ScenarioTableRow): string {
  const visible = row.summary.givenFields.slice(0, 2).map((field) => (
    `${businessLabel(field.path.split('/').filter(Boolean).pop() ?? 'input')}=${compactValue(field.value)}`
  ));
  const remaining = row.summary.givenFieldCount - visible.length;
  return `${visible.join(' · ')}${remaining > 0 ? ` · +${remaining}` : ''}` || '—';
}

function compactValue(value: unknown): string {
  const rendered = displayValue(value);
  return rendered.length > 28 ? `${rendered.slice(0, 25)}...` : rendered;
}

function prettyValue(value: unknown): string {
  if (value === undefined) return 'Not available';
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2);
}

function businessLabel(value: string): string {
  let decoded = value;
  try {
    decoded = decodeURIComponent(value);
  } catch {
    decoded = value;
  }
  const assertionCoordinate = /^[A-Z_]+:(.*):(?:EQUALS|MATCHES_SCHEMA|EXISTS|ABSENT|STATUS|USED|NOT_USED)$/
    .exec(decoded);
  return (assertionCoordinate?.[1] ?? decoded)
    .replace(/^\$\.?/, '')
    .replace(/[_-]+/g, ' ')
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .trim() || 'Value';
}

function freshnessLabel(value: ScenarioTableRow['evidence']['freshness']): string {
  switch (value) {
    case 'CURRENT': return 'Current';
    case 'STALE': return 'Stale';
    case 'SUPERSEDED': return 'Superseded';
  }
}

function proofLabel(value: ScenarioTableRow['evidence']['proofStrength']): string {
  switch (value) {
    case 'SCHEMA': return 'Schema only';
    case 'MOCK': return 'Mock proof';
    case 'SANDBOX': return 'Sandbox proof';
    case 'RUNTIME': return 'Runtime proof';
    case 'CERTIFIABLE': return 'Certifiable proof';
  }
}

function subjectModeLabel(value: ScenarioTableRow['evidence']['subjectMode']): string {
  return value === 'SCHEMA_ONLY' ? 'Schema validation only' : 'Real target execution';
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
