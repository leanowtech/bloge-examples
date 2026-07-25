import { useCallback, useEffect, useMemo, useState } from 'react';

import {
  fetchScenarioRehearsalBatchItems,
  fetchScenarioRehearsalBatchJobs,
  fetchScenarioRehearsalBatchWorkbook,
  fetchScenarioRehearsalWorkbook,
} from './api';
import type {
  ScenarioRehearsalBatchItem,
  ScenarioRehearsalBatchJob,
  ScenarioRehearsalBatchJobPage,
  ScenarioRehearsalBatchWorkbookEntry,
  ScenarioRehearsalBatchWorkbookSeed,
  ScenarioRehearsalChildWorkbook,
  ScenarioRehearsalWorkbookSeed,
} from './types';

const TERMINAL_STATUSES = new Set<ScenarioRehearsalBatchJob['status']>([
  'SUCCEEDED',
  'PARTIAL',
  'FAILED',
  'CANCELLED',
  'EXPIRED',
  'QUARANTINED',
]);

const CATEGORY_ORDER = [
  'EXECUTION',
  'EVIDENCE',
  'ASSERTIONS',
  'GOVERNANCE',
  'WARNINGS',
  'PASSED',
] as const;

type WorkbenchCategory = (typeof CATEGORY_ORDER)[number];
type WorkbenchFilter = 'ALL' | WorkbenchCategory;

interface WorkbenchEntry {
  index: number;
  id: string;
  status: ScenarioRehearsalBatchItem['status'];
  attemptCount: number;
  runId: string;
  failureCode: string;
  planId: string;
  planRevision: number;
  planFingerprint: string;
  evidenceFingerprint: string;
  workbookFingerprint: string;
  childWorkbook: ScenarioRehearsalChildWorkbook | null;
  startedAt: string | null;
  completedAt: string | null;
}

interface EntryDiagnosis {
  category: WorkbenchCategory;
  reason: string;
}

function isTerminal(job: ScenarioRehearsalBatchJob | undefined): boolean {
  return job !== undefined && TERMINAL_STATUSES.has(job.status);
}

function asWorkbenchEntry(
  entry: ScenarioRehearsalBatchWorkbookEntry,
): WorkbenchEntry {
  return {
    index: entry.entryIndex,
    id: entry.entryId,
    status: entry.status,
    attemptCount: entry.attemptCount,
    runId: entry.runId,
    failureCode: entry.failureCode,
    planId: entry.compiledPlanRef.id,
    planRevision: entry.compiledPlanRef.revision,
    planFingerprint: entry.compiledPlanRef.fingerprint,
    evidenceFingerprint: entry.childEvidenceBundleFingerprint,
    workbookFingerprint: entry.childWorkbookSeedFingerprint,
    childWorkbook: entry.childWorkbook,
    startedAt: null,
    completedAt: null,
  };
}

function asLiveWorkbenchEntry(item: ScenarioRehearsalBatchItem): WorkbenchEntry {
  return {
    index: item.itemIndex,
    id: `item-${item.itemIndex}`,
    status: item.status,
    attemptCount: item.attemptCount,
    runId: item.runId,
    failureCode: item.failureCode,
    planId: item.compiledPlanRef.id,
    planRevision: item.compiledPlanRef.revision,
    planFingerprint: item.compiledPlanRef.fingerprint,
    evidenceFingerprint: item.evidenceBundleFingerprint,
    workbookFingerprint: item.workbookSeedFingerprint,
    childWorkbook: null,
    startedAt: item.startedAt,
    completedAt: item.completedAt,
  };
}

function diagnoseEntry(entry: WorkbenchEntry): EntryDiagnosis {
  const child = entry.childWorkbook;
  if (entry.status === 'FAILED' || entry.status === 'CANCELLED' || entry.failureCode) {
    return {
      category: 'EXECUTION',
      reason: entry.failureCode || (entry.status === 'CANCELLED' ? 'Item cancelled' : 'Execution failed'),
    };
  }
  if (entry.status === 'INDETERMINATE'
    || (entry.runId && entry.status !== 'RUNNING' && !child && !entry.workbookFingerprint)) {
    return {
      category: 'EVIDENCE',
      reason: 'Evidence is incomplete or cannot establish an outcome',
    };
  }
  if (child && (child.summary.blockerFailures > 0 || child.summary.blockerIndeterminate > 0)) {
    return {
      category: 'ASSERTIONS',
      reason: `${child.summary.blockerFailures} failed and ${child.summary.blockerIndeterminate} indeterminate blocker assertions`,
    };
  }
  if (child && (!child.gateReady || child.blockers.length > 0)) {
    return {
      category: 'GOVERNANCE',
      reason: child.blockers[0] || 'Child workbook is not gate-ready',
    };
  }
  if (child && (child.summary.warningFailures > 0 || child.summary.warningIndeterminate > 0)) {
    return {
      category: 'WARNINGS',
      reason: `${child.summary.warningFailures} failed and ${child.summary.warningIndeterminate} indeterminate warnings`,
    };
  }
  if (entry.status === 'PASSED') {
    return { category: 'PASSED', reason: 'All evaluated cases passed' };
  }
  return { category: 'EVIDENCE', reason: `Mutable ${entry.status.toLowerCase()} projection` };
}

function shortFingerprint(value: string): string {
  if (!value) {
    return 'Not available';
  }
  return value.length > 22 ? `${value.slice(0, 12)}...${value.slice(-7)}` : value;
}

function formatDate(value: string | null | undefined): string {
  if (!value) {
    return 'Not complete';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(date);
}

function statusTone(status: string): string {
  if (status === 'SUCCEEDED' || status === 'PASSED' || status === 'PASS') {
    return 'success';
  }
  if (status === 'FAILED' || status === 'FAIL' || status === 'QUARANTINED') {
    return 'danger';
  }
  if (status === 'PARTIAL' || status === 'INDETERMINATE' || status === 'EXPIRED') {
    return 'warning';
  }
  return 'neutral';
}

function querySelection(): { jobId: string; entry: number | null } {
  const query = new URLSearchParams(window.location.search);
  const rawEntry = query.get('entry');
  const entry = rawEntry === null ? Number.NaN : Number(rawEntry);
  return {
    jobId: query.get('jobId') ?? '',
    entry: Number.isInteger(entry) && entry >= 0 ? entry : null,
  };
}

function updateDeepLink(jobId: string, entry: number | null): void {
  const url = new URL(window.location.href);
  url.searchParams.set('jobId', jobId);
  if (entry === null) {
    url.searchParams.delete('entry');
  } else {
    url.searchParams.set('entry', String(entry));
  }
  window.history.replaceState({}, '', `${url.pathname}${url.search}${url.hash}`);
}

/**
 * Read-only Owner surface for triaging Scenario rehearsal batches and drilling into signed evidence.
 *
 * Mutable active projections are deliberately labelled as non-governance evidence. Only terminal
 * root-sealed workbooks expose gate readiness and support lazy case/assertion inspection.
 */
export default function RehearsalWorkbench() {
  const initialSelection = useMemo(querySelection, []);
  const [jobs, setJobs] = useState<ScenarioRehearsalBatchJob[]>([]);
  const [nextCursor, setNextCursor] = useState<ScenarioRehearsalBatchJobPage['nextCursor']>(null);
  const [selectedJobId, setSelectedJobId] = useState(initialSelection.jobId);
  const [selectedEntryIndex, setSelectedEntryIndex] = useState<number | null>(initialSelection.entry);
  const [workbook, setWorkbook] = useState<ScenarioRehearsalBatchWorkbookSeed | null>(null);
  const [liveItems, setLiveItems] = useState<ScenarioRehearsalBatchItem[]>([]);
  const [nextItemIndex, setNextItemIndex] = useState<number | null>(null);
  const [childWorkbook, setChildWorkbook] = useState<ScenarioRehearsalWorkbookSeed | null>(null);
  const [filter, setFilter] = useState<WorkbenchFilter>('ALL');
  const [loadingJobs, setLoadingJobs] = useState(true);
  const [loadingJob, setLoadingJob] = useState(false);
  const [loadingChild, setLoadingChild] = useState(false);
  const [error, setError] = useState('');
  const [detailError, setDetailError] = useState('');

  const selectedJob = jobs.find((job) => job.jobId === selectedJobId);
  const terminal = isTerminal(selectedJob);
  const entries = useMemo(
    () => terminal
      ? (workbook?.entries ?? []).map(asWorkbenchEntry)
      : liveItems.map(asLiveWorkbenchEntry),
    [liveItems, terminal, workbook],
  );
  const selectedEntry = entries.find((entry) => entry.index === selectedEntryIndex) ?? null;

  const diagnoses = useMemo(
    () => new Map(entries.map((entry) => [entry.index, diagnoseEntry(entry)])),
    [entries],
  );
  const categoryCounts = useMemo(
    () => Object.fromEntries(
      CATEGORY_ORDER.map((category) => [
        category,
        entries.filter((entry) => diagnoses.get(entry.index)?.category === category).length,
      ]),
    ) as Record<WorkbenchCategory, number>,
    [diagnoses, entries],
  );
  const groupedEntries = useMemo(
    () => CATEGORY_ORDER
      .filter((category) => filter === 'ALL' || filter === category)
      .map((category) => ({
        category,
        entries: entries.filter((entry) => diagnoses.get(entry.index)?.category === category),
      }))
      .filter((group) => group.entries.length > 0),
    [diagnoses, entries, filter],
  );

  const discoverJobs = useCallback(async (keepSelection = true) => {
    setLoadingJobs(true);
    setError('');
    try {
      const accumulated: ScenarioRehearsalBatchJob[] = [];
      let cursor: ScenarioRehearsalBatchJobPage['nextCursor'] = null;
      let page: ScenarioRehearsalBatchJobPage | null = null;
      const soughtJobId = keepSelection ? selectedJobId || initialSelection.jobId : '';
      const soughtEntry = keepSelection ? selectedEntryIndex : null;
      for (let pageNumber = 0; pageNumber < 20; pageNumber += 1) {
        page = await fetchScenarioRehearsalBatchJobs(50, cursor);
        accumulated.push(...page.jobs);
        cursor = page.nextCursor;
        if (!soughtJobId || accumulated.some((job) => job.jobId === soughtJobId) || !cursor) {
          break;
        }
      }
      const uniqueJobs = Array.from(new Map(accumulated.map((job) => [job.jobId, job])).values());
      setJobs(uniqueJobs);
      setNextCursor(page?.nextCursor ?? null);
      const selectionExists = soughtJobId && uniqueJobs.some((job) => job.jobId === soughtJobId);
      const nextJobId = selectionExists ? soughtJobId : uniqueJobs[0]?.jobId ?? '';
      setSelectedJobId(nextJobId);
      setSelectedEntryIndex(selectionExists ? soughtEntry : null);
      if (soughtJobId && !selectionExists) {
        setError('The deep-linked batch is not visible in this authenticated scope.');
      }
      if (nextJobId) {
        updateDeepLink(nextJobId, selectionExists ? soughtEntry : null);
      }
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Unable to discover rehearsal batches.');
    } finally {
      setLoadingJobs(false);
    }
  }, [initialSelection.jobId, selectedEntryIndex, selectedJobId]);

  useEffect(() => {
    void discoverJobs();
    // Initial discovery owns deep-link resolution; subsequent refreshes are explicit.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!selectedJob) {
      setWorkbook(null);
      setLiveItems([]);
      setNextItemIndex(null);
      return;
    }
    let cancelled = false;
    setLoadingJob(true);
    setError('');
    setWorkbook(null);
    setLiveItems([]);
    setNextItemIndex(null);
    setChildWorkbook(null);
    setDetailError('');
    const load = async () => {
      try {
        if (isTerminal(selectedJob)) {
          const nextWorkbook = await fetchScenarioRehearsalBatchWorkbook(selectedJob.jobId);
          if (!cancelled) {
            setWorkbook(nextWorkbook);
          }
        } else {
          const page = await fetchScenarioRehearsalBatchItems(selectedJob.jobId, 0, 100);
          if (!cancelled) {
            setLiveItems(page.items);
            setNextItemIndex(page.nextIndex);
          }
        }
      } catch (cause) {
        if (!cancelled) {
          setError(cause instanceof Error ? cause.message : 'Unable to load the selected batch.');
        }
      } finally {
        if (!cancelled) {
          setLoadingJob(false);
        }
      }
    };
    void load();
    return () => {
      cancelled = true;
    };
  }, [selectedJob]);

  useEffect(() => {
    if (!selectedEntry || !terminal || !selectedEntry.runId) {
      setChildWorkbook(null);
      setDetailError('');
      return;
    }
    let cancelled = false;
    setLoadingChild(true);
    setChildWorkbook(null);
    setDetailError('');
    void fetchScenarioRehearsalWorkbook(selectedEntry.runId)
      .then((nextWorkbook) => {
        if (!cancelled) {
          setChildWorkbook(nextWorkbook);
        }
      })
      .catch((cause: unknown) => {
        if (!cancelled) {
          setDetailError(cause instanceof Error ? cause.message : 'Unable to load child evidence.');
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoadingChild(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [selectedEntry, terminal]);

  async function loadOlderJobs() {
    if (!nextCursor) {
      return;
    }
    setLoadingJobs(true);
    setError('');
    try {
      const page = await fetchScenarioRehearsalBatchJobs(50, nextCursor);
      setJobs((current) => Array.from(
        new Map([...current, ...page.jobs].map((job) => [job.jobId, job])).values(),
      ));
      setNextCursor(page.nextCursor);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Unable to load older batches.');
    } finally {
      setLoadingJobs(false);
    }
  }

  async function loadMoreItems() {
    if (!selectedJob || nextItemIndex === null) {
      return;
    }
    setLoadingJob(true);
    setError('');
    try {
      const page = await fetchScenarioRehearsalBatchItems(selectedJob.jobId, nextItemIndex, 100);
      setLiveItems((current) => [...current, ...page.items]);
      setNextItemIndex(page.nextIndex);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Unable to load more batch items.');
    } finally {
      setLoadingJob(false);
    }
  }

  function selectJob(jobId: string) {
    setSelectedJobId(jobId);
    setSelectedEntryIndex(null);
    setFilter('ALL');
    updateDeepLink(jobId, null);
  }

  function selectEntry(index: number) {
    setSelectedEntryIndex(index);
    updateDeepLink(selectedJobId, index);
  }

  function closeEvidence() {
    setSelectedEntryIndex(null);
    updateDeepLink(selectedJobId, null);
  }

  const summary = workbook?.summary ?? selectedJob?.summary;
  const completionPercent = summary && summary.totalItems > 0
    ? Math.round(summary.completedItems / summary.totalItems * 100)
    : 0;

  return (
    <main
      className={`rehearsal-workbench ${selectedEntry ? 'drawer-open' : ''}`}
      data-testid="rehearsal-workbench"
    >
      <aside className="rehearsal-queue" aria-label="Rehearsal batch queue">
        <div className="workbench-pane-heading">
          <div>
            <p className="workbench-kicker">Exact scope</p>
            <h2>Rehearsal batches</h2>
          </div>
          <button
            className="compact-command"
            type="button"
            onClick={() => void discoverJobs()}
            disabled={loadingJobs}
          >
            Refresh
          </button>
        </div>
        {jobs[0] && (
          <dl className="scope-coordinates" data-testid="scope-coordinates">
            <div><dt>Tenant</dt><dd>{jobs[0].scope.tenantId}</dd></div>
            <div><dt>Project</dt><dd>{jobs[0].scope.projectId}</dd></div>
            <div><dt>Environment</dt><dd>{jobs[0].scope.environmentId}</dd></div>
            <div><dt>Region</dt><dd>{jobs[0].scope.region}</dd></div>
          </dl>
        )}
        <div className="batch-queue-list">
          {jobs.map((job) => (
            <button
              className={`batch-queue-row ${job.jobId === selectedJobId ? 'selected' : ''}`}
              type="button"
              key={job.jobId}
              onClick={() => selectJob(job.jobId)}
              aria-pressed={job.jobId === selectedJobId}
              data-testid={`batch-${job.jobId}`}
            >
              <span className="batch-queue-row-top">
                <strong>{job.jobId}</strong>
                <span className={`status-label ${statusTone(job.status)}`}>{job.status}</span>
              </span>
              <span>{job.summary.completedItems} / {job.summary.totalItems} complete</span>
              <span>{formatDate(job.createdAt)}</span>
            </button>
          ))}
          {!loadingJobs && jobs.length === 0 && (
            <p className="empty-workbench">No Scenario rehearsal batches are visible in this scope.</p>
          )}
        </div>
        {nextCursor && (
          <button
            className="pane-command"
            type="button"
            onClick={() => void loadOlderJobs()}
            disabled={loadingJobs}
          >
            Load older batches
          </button>
        )}
      </aside>

      <section className="rehearsal-results" aria-label="Selected batch">
        {error && <div className="workbench-alert danger" role="alert">{error}</div>}
        {selectedJob ? (
          <>
            <header className="batch-overview">
              <div className="batch-overview-title">
                <div>
                  <p className="workbench-kicker">
                    {terminal ? 'Root-sealed terminal evidence' : 'Integrity-protected live state'}
                  </p>
                  <h2>{selectedJob.jobId}</h2>
                </div>
                <div className="evidence-mode" data-testid="evidence-mode">
                  <strong>{terminal ? 'Signed workbook' : 'Live projection'}</strong>
                  <span>
                    {terminal
                      ? 'Immutable and eligible for governance review'
                      : 'Mutable and not publish-gate evidence'}
                  </span>
                </div>
              </div>
              <div className="batch-progress" aria-label={`${completionPercent}% complete`}>
                <span style={{ width: `${completionPercent}%` }} />
              </div>
              <dl className="batch-metrics">
                <div><dt>Progress</dt><dd>{completionPercent}%</dd></div>
                <div><dt>Passed</dt><dd>{summary?.passedItems ?? 0}</dd></div>
                <div><dt>Failed</dt><dd>{summary?.failedItems ?? 0}</dd></div>
                <div><dt>Indeterminate</dt><dd>{summary?.indeterminateItems ?? 0}</dd></div>
                <div>
                  <dt>Gate</dt>
                  <dd className={workbook?.gateReady ? 'metric-success' : 'metric-danger'}>
                    {terminal ? workbook?.gateReady ? 'Ready' : 'Blocked' : 'Pending'}
                  </dd>
                </div>
              </dl>
              {terminal && workbook && !workbook.gateReady && (
                <div className="workbench-alert warning" data-testid="root-blockers">
                  <strong>Publish gate blocked</strong>
                  <span>{workbook.blockers.join(' | ') || 'Terminal workbook is not gate-ready.'}</span>
                </div>
              )}
            </header>

            <div className="result-toolbar">
              <div className="failure-filters" role="group" aria-label="Failure category">
                <button
                  type="button"
                  className={filter === 'ALL' ? 'active' : ''}
                  onClick={() => setFilter('ALL')}
                >
                  All <strong>{entries.length}</strong>
                </button>
                {CATEGORY_ORDER.map((category) => (
                  <button
                    type="button"
                    key={category}
                    className={filter === category ? 'active' : ''}
                    onClick={() => setFilter(category)}
                  >
                    {categoryLabel(category)} <strong>{categoryCounts[category]}</strong>
                  </button>
                ))}
              </div>
            </div>

            <div className="entry-groups" data-testid="entry-groups">
              {loadingJob && entries.length === 0 && (
                <p className="empty-workbench">Loading batch evidence...</p>
              )}
              {!loadingJob && entries.length === 0 && (
                <p className="empty-workbench">This batch has no visible items.</p>
              )}
              {groupedEntries.map((group) => (
                <section className="entry-group" key={group.category}>
                  <header>
                    <h3>{categoryLabel(group.category)}</h3>
                    <span>{group.entries.length}</span>
                  </header>
                  <div className="entry-table" aria-label={`${categoryLabel(group.category)} entries`}>
                    <div className="entry-table-head">
                      <span>Item / compiled plan</span>
                      <span>Outcome</span>
                      <span>Diagnosis</span>
                      <span>Evidence</span>
                    </div>
                    {group.entries.map((entry) => {
                      const diagnosis = diagnoses.get(entry.index);
                      return (
                        <button
                          className={`entry-table-row ${entry.index === selectedEntryIndex ? 'selected' : ''}`}
                          type="button"
                          key={entry.id}
                          onClick={() => selectEntry(entry.index)}
                          data-testid={`entry-${entry.index}`}
                        >
                          <span className="entry-identity">
                            <strong>#{entry.index} {entry.planId}</strong>
                            <small>revision {entry.planRevision} · {entry.attemptCount} attempt(s)</small>
                          </span>
                          <span>
                            <span className={`status-label ${statusTone(entry.status)}`}>{entry.status}</span>
                          </span>
                          <span className="entry-diagnosis">{diagnosis?.reason}</span>
                          <span className="entry-evidence-state">
                            {entry.workbookFingerprint
                              ? 'Signed child workbook'
                              : entry.evidenceFingerprint
                                ? 'Evidence bundle'
                                : 'Not sealed'}
                          </span>
                        </button>
                      );
                    })}
                  </div>
                </section>
              ))}
            </div>
            {!terminal && nextItemIndex !== null && (
              <button
                className="pane-command load-more-items"
                type="button"
                onClick={() => void loadMoreItems()}
                disabled={loadingJob}
              >
                Load more items
              </button>
            )}
          </>
        ) : !loadingJobs && (
          <div className="workbench-welcome">
            <h2>No batch selected</h2>
            <p>Choose a Scenario rehearsal batch to inspect its execution and evidence state.</p>
          </div>
        )}
      </section>

      <aside
        className={`evidence-drawer ${selectedEntry ? 'open' : ''}`}
        aria-label="Entry evidence"
        aria-hidden={!selectedEntry}
      >
        {selectedEntry && (
          <>
            <header className="evidence-drawer-heading">
              <div>
                <p className="workbench-kicker">Batch item #{selectedEntry.index}</p>
                <h2>Evidence</h2>
              </div>
              <button className="drawer-close" type="button" onClick={closeEvidence} aria-label="Close evidence">
                Close
              </button>
            </header>
            <section className="evidence-section">
              <h3>Identity</h3>
              <dl className="evidence-fields">
                <div><dt>Compiled plan</dt><dd>{selectedEntry.planId}@{selectedEntry.planRevision}</dd></div>
                <div><dt>Run</dt><dd>{selectedEntry.runId || 'Not assigned'}</dd></div>
                <div><dt>Outcome</dt><dd>{selectedEntry.status}</dd></div>
                <div><dt>Started</dt><dd>{formatDate(selectedEntry.startedAt)}</dd></div>
                <div><dt>Completed</dt><dd>{formatDate(selectedEntry.completedAt)}</dd></div>
              </dl>
            </section>
            <section className="evidence-section">
              <h3>Integrity chain</h3>
              <dl className="evidence-fields fingerprints">
                <div>
                  <dt>Plan</dt>
                  <dd title={selectedEntry.planFingerprint}>{shortFingerprint(selectedEntry.planFingerprint)}</dd>
                </div>
                <div>
                  <dt>Evidence bundle</dt>
                  <dd title={selectedEntry.evidenceFingerprint}>{shortFingerprint(selectedEntry.evidenceFingerprint)}</dd>
                </div>
                <div>
                  <dt>Workbook</dt>
                  <dd title={selectedEntry.workbookFingerprint}>{shortFingerprint(selectedEntry.workbookFingerprint)}</dd>
                </div>
              </dl>
            </section>
            {!terminal && (
              <div className="workbench-alert warning">
                This live projection may change. It must not be used as release evidence.
              </div>
            )}
            {terminal && loadingChild && (
              <p className="empty-workbench">Verifying and loading signed child evidence...</p>
            )}
            {detailError && <div className="workbench-alert danger" role="alert">{detailError}</div>}
            {terminal && !loadingChild && !detailError && !selectedEntry.runId && (
              <div className="workbench-alert danger">No child run is available for evidence drill-down.</div>
            )}
            {childWorkbook && (
              <>
                <section className="evidence-section" data-testid="child-summary">
                  <div className="evidence-section-heading">
                    <h3>Case summary</h3>
                    <span className={`status-label ${childWorkbook.gateReady ? 'success' : 'danger'}`}>
                      {childWorkbook.gateReady ? 'GATE READY' : 'BLOCKED'}
                    </span>
                  </div>
                  <dl className="case-summary-grid">
                    <div><dt>Total</dt><dd>{childWorkbook.summary.totalCases}</dd></div>
                    <div><dt>Passed</dt><dd>{childWorkbook.summary.passedCases}</dd></div>
                    <div><dt>Failed</dt><dd>{childWorkbook.summary.failedCases}</dd></div>
                    <div><dt>Unknown</dt><dd>{childWorkbook.summary.indeterminateCases}</dd></div>
                  </dl>
                  {childWorkbook.blockers.length > 0 && (
                    <ul className="evidence-blockers">
                      {childWorkbook.blockers.map((blocker) => <li key={blocker}>{blocker}</li>)}
                    </ul>
                  )}
                </section>
                <section className="evidence-section" data-testid="child-cases">
                  <h3>Cases and assertions</h3>
                  <div className="case-list">
                    {childWorkbook.cases.map((scenarioCase) => (
                      <article className="case-row" key={`${scenarioCase.caseIndex}-${scenarioCase.childRunId}`}>
                        <header>
                          <span>#{scenarioCase.caseIndex} {scenarioCase.caseType}</span>
                          <span className={`status-label ${statusTone(scenarioCase.outcome)}`}>
                            {scenarioCase.outcome}
                          </span>
                        </header>
                        <strong>{scenarioCase.testCaseId}</strong>
                        {scenarioCase.diagnosticCode && <p>{scenarioCase.diagnosticCode}</p>}
                        <div className="assertion-list">
                          {scenarioCase.assertionResults.map((assertion) => (
                            <div className="assertion-row" key={assertion.resultFingerprint}>
                              <span className={`assertion-mark ${statusTone(assertion.outcome)}`} aria-hidden="true" />
                              <div>
                                <strong>{assertion.assertionRef.id}</strong>
                                <span>{assertion.governanceCode || assertion.reasonCode || assertion.observation}</span>
                              </div>
                            </div>
                          ))}
                        </div>
                      </article>
                    ))}
                  </div>
                </section>
              </>
            )}
          </>
        )}
      </aside>
    </main>
  );
}

function categoryLabel(category: WorkbenchCategory): string {
  switch (category) {
    case 'EXECUTION':
      return 'Execution';
    case 'EVIDENCE':
      return 'Evidence';
    case 'ASSERTIONS':
      return 'Assertions';
    case 'GOVERNANCE':
      return 'Governance';
    case 'WARNINGS':
      return 'Warnings';
    case 'PASSED':
      return 'Passed';
  }
}
