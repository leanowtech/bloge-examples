import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';

import {
  BlogeApiRequestError,
  fetchScenarioRehearsalBatchItemAttempts,
  fetchScenarioRehearsalBatchItems,
  fetchScenarioRehearsalBatchJobs,
  fetchScenarioRehearsalBatchWorkbook,
  fetchScenarioRehearsalWorkbook,
} from './api';
import RehearsalRemediationPanel from './RehearsalRemediationPanel';
import RemediationActionList from './remediation/RemediationActionList';
import {
  rehearsalEvidencePresentation,
  type RehearsalAuthorTarget,
} from './remediation/rehearsalEvidenceModel';
import {
  DEFAULT_REHEARSAL_DEMO_ID,
  findRehearsalDemoScenario,
  REHEARSAL_DEMO_SCENARIOS,
} from './rehearsalDemoData';
import { isTerminalRehearsalStatus } from './rehearsalStatus';
import type {
  ScenarioRehearsalBatchItem,
  ScenarioRehearsalBatchItemAttemptTimeline,
  ScenarioRehearsalBatchJob,
  ScenarioRehearsalBatchJobPage,
  ScenarioRehearsalBatchWorkbookEntry,
  ScenarioRehearsalBatchWorkbookSeed,
  ScenarioRehearsalChildWorkbook,
  ScenarioRehearsalWorkbookSeed,
} from './types';

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
type WorkbenchMode = 'LIVE' | 'SAMPLES';

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
  authorTarget?: RehearsalAuthorTarget;
}

interface EntryDiagnosis {
  category: WorkbenchCategory;
  reason: string;
}

function isTerminal(job: ScenarioRehearsalBatchJob | undefined): boolean {
  return job !== undefined && isTerminalRehearsalStatus(job.status);
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
  if (entry.failureCode && isEvidenceFailureCode(entry.failureCode)) {
    return {
      category: 'EVIDENCE',
      reason: entry.failureCode,
    };
  }
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

function isEvidenceFailureCode(code: string): boolean {
  return [
    'ATTESTATION',
    'EVIDENCE',
    'KMS',
    'RETENTION',
    'SEAL',
    'SIGNER',
    'WORKBOOK',
  ].some((token) => code.includes(token));
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

function formatEntryDate(
  value: string | null,
  terminal: boolean,
  liveFallback: string,
): string {
  return value
    ? formatDate(value)
    : terminal ? 'Not included in workbook' : liveFallback;
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

function querySelection(): {
  jobId: string;
  sampleId: string;
  entry: number | null;
  remediationId: string;
} {
  const query = new URLSearchParams(window.location.search);
  const rawEntry = query.get('entry');
  const entry = rawEntry === null ? Number.NaN : Number(rawEntry);
  return {
    jobId: query.get('jobId') ?? '',
    sampleId: query.get('sample') ?? '',
    entry: Number.isInteger(entry) && entry >= 0 ? entry : null,
    remediationId: query.get('remediationId') ?? '',
  };
}

function updateDeepLink(
  jobId: string,
  entry: number | null,
  remediationId: string,
  mode: WorkbenchMode,
): void {
  const url = new URL(window.location.href);
  if (mode === 'SAMPLES') {
    url.searchParams.set('sample', jobId);
    url.searchParams.delete('jobId');
    url.searchParams.delete('remediationId');
  } else {
    url.searchParams.set('jobId', jobId);
    url.searchParams.delete('sample');
    if (remediationId) {
      url.searchParams.set('remediationId', remediationId);
    } else {
      url.searchParams.delete('remediationId');
    }
  }
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
  const initialSample = findRehearsalDemoScenario(initialSelection.sampleId);
  const [mode, setMode] = useState<WorkbenchMode>(initialSample ? 'SAMPLES' : 'LIVE');
  const [liveJobs, setLiveJobs] = useState<ScenarioRehearsalBatchJob[]>([]);
  const [nextCursor, setNextCursor] = useState<ScenarioRehearsalBatchJobPage['nextCursor']>(null);
  const [selectedJobId, setSelectedJobId] = useState(
    initialSample?.job.jobId ?? initialSelection.jobId,
  );
  const [selectedEntryIndex, setSelectedEntryIndex] = useState<number | null>(initialSelection.entry);
  const [selectedRemediationId, setSelectedRemediationId] =
    useState(initialSelection.remediationId);
  const [workbook, setWorkbook] = useState<ScenarioRehearsalBatchWorkbookSeed | null>(null);
  const [liveItems, setLiveItems] = useState<ScenarioRehearsalBatchItem[]>([]);
  const [nextItemIndex, setNextItemIndex] = useState<number | null>(null);
  const [childWorkbook, setChildWorkbook] = useState<ScenarioRehearsalWorkbookSeed | null>(null);
  const [attemptTimeline, setAttemptTimeline] =
    useState<ScenarioRehearsalBatchItemAttemptTimeline | null>(null);
  const [filter, setFilter] = useState<WorkbenchFilter>('ALL');
  const [loadingJobs, setLoadingJobs] = useState(true);
  const [loadingOlderJobs, setLoadingOlderJobs] = useState(false);
  const [loadingJob, setLoadingJob] = useState(false);
  const [loadingMoreItems, setLoadingMoreItems] = useState(false);
  const [loadingChild, setLoadingChild] = useState(false);
  const [batchApiAvailable, setBatchApiAvailable] = useState(true);
  const [error, setError] = useState('');
  const [detailError, setDetailError] = useState('');
  const discoveryGeneration = useRef(0);
  const jobPageGeneration = useRef(0);
  const itemPageGeneration = useRef(0);

  const sampleMode = mode === 'SAMPLES';
  const jobs = sampleMode
    ? REHEARSAL_DEMO_SCENARIOS.map((scenario) => scenario.job)
    : liveJobs;
  const selectedJob = jobs.find((job) => job.jobId === selectedJobId);
  const selectedDemoScenario = sampleMode
    ? findRehearsalDemoScenario(selectedJobId)
    : undefined;
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
  const selectedEvidence = useMemo(() => {
    if (!selectedEntry || !selectedJob) {
      return null;
    }
    return rehearsalEvidencePresentation(
      attemptTimeline?.authorTarget
        ? {
          ...selectedEntry,
          authorTarget: attemptTimeline.authorTarget,
        }
        : selectedEntry,
      selectedJob,
      diagnoses.get(selectedEntry.index) ?? {
        category: 'EVIDENCE',
        reason: 'Evidence is incomplete',
      },
      {
        sampleMode,
        currentHref: window.location.href,
        exactAttempts: attemptTimeline,
      },
    );
  }, [attemptTimeline, diagnoses, sampleMode, selectedEntry, selectedJob]);
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
    const generation = discoveryGeneration.current + 1;
    discoveryGeneration.current = generation;
    jobPageGeneration.current += 1;
    itemPageGeneration.current += 1;
    setLoadingOlderJobs(false);
    setLoadingMoreItems(false);
    setLoadingJobs(true);
    setBatchApiAvailable(true);
    setError('');
    try {
      const accumulated: ScenarioRehearsalBatchJob[] = [];
      let cursor: ScenarioRehearsalBatchJobPage['nextCursor'] = null;
      let page: ScenarioRehearsalBatchJobPage | null = null;
      const soughtJobId = keepSelection && mode === 'LIVE'
        ? selectedJobId || initialSelection.jobId
        : '';
      const soughtEntry = keepSelection ? selectedEntryIndex : null;
      const soughtRemediation = keepSelection
        ? selectedRemediationId || initialSelection.remediationId
        : '';
      for (let pageNumber = 0; pageNumber < 20; pageNumber += 1) {
        page = await fetchScenarioRehearsalBatchJobs(50, cursor);
        if (generation !== discoveryGeneration.current) {
          return;
        }
        accumulated.push(...page.jobs);
        cursor = page.nextCursor;
        if (!soughtJobId || accumulated.some((job) => job.jobId === soughtJobId) || !cursor) {
          break;
        }
      }
      if (generation !== discoveryGeneration.current) {
        return;
      }
      const uniqueJobs = Array.from(new Map(accumulated.map((job) => [job.jobId, job])).values());
      setBatchApiAvailable(true);
      setLiveJobs(uniqueJobs);
      setNextCursor(page?.nextCursor ?? null);
      if (uniqueJobs.length === 0) {
        setMode('SAMPLES');
        setSelectedJobId(DEFAULT_REHEARSAL_DEMO_ID);
        setSelectedEntryIndex(null);
        setSelectedRemediationId('');
        updateDeepLink(DEFAULT_REHEARSAL_DEMO_ID, null, '', 'SAMPLES');
        return;
      }
      setMode('LIVE');
      const selectionExists = soughtJobId && uniqueJobs.some((job) => job.jobId === soughtJobId);
      const nextJobId = selectionExists ? soughtJobId : uniqueJobs[0]?.jobId ?? '';
      setSelectedJobId(nextJobId);
      setSelectedEntryIndex(selectionExists ? soughtEntry : null);
      if (soughtJobId && !selectionExists) {
        setError('The deep-linked batch is not visible in this authenticated scope.');
      }
      if (nextJobId) {
        updateDeepLink(
          nextJobId,
          selectionExists ? soughtEntry : null,
          selectionExists ? soughtRemediation : '',
          'LIVE',
        );
      }
    } catch (cause) {
      if (generation !== discoveryGeneration.current) {
        return;
      }
      const fallbackSampleId = sampleMode && selectedDemoScenario
        ? selectedDemoScenario.job.jobId
        : DEFAULT_REHEARSAL_DEMO_ID;
      const fallbackEntry = sampleMode ? selectedEntryIndex : null;
      if (cause instanceof BlogeApiRequestError && cause.status === 404) {
        setBatchApiAvailable(false);
        setLiveJobs([]);
        setNextCursor(null);
      } else {
        setError(cause instanceof Error ? cause.message : 'Unable to discover rehearsal batches.');
      }
      setMode('SAMPLES');
      setSelectedJobId(fallbackSampleId);
      setSelectedEntryIndex(fallbackEntry);
      setSelectedRemediationId('');
      updateDeepLink(fallbackSampleId, fallbackEntry, '', 'SAMPLES');
    } finally {
      if (generation === discoveryGeneration.current) {
        setLoadingJobs(false);
      }
    }
  }, [
    initialSelection.jobId,
    initialSelection.remediationId,
    mode,
    sampleMode,
    selectedDemoScenario,
    selectedEntryIndex,
    selectedJobId,
    selectedRemediationId,
  ]);

  useEffect(() => {
    if (initialSample) {
      setLoadingJobs(false);
      updateDeepLink(initialSample.job.jobId, initialSelection.entry, '', 'SAMPLES');
    } else {
      void discoverJobs();
    }
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
    if (!sampleMode) {
      setError('');
    }
    setWorkbook(null);
    setLiveItems([]);
    setNextItemIndex(null);
    setChildWorkbook(null);
    setAttemptTimeline(null);
    setDetailError('');
    const load = async () => {
      try {
        if (sampleMode) {
          if (!selectedDemoScenario) {
            throw new Error('The selected sample scenario is unavailable.');
          }
          if (selectedDemoScenario.kind === 'TERMINAL') {
            setWorkbook(selectedDemoScenario.workbook);
          } else {
            setLiveItems(selectedDemoScenario.itemPage.items);
            setNextItemIndex(selectedDemoScenario.itemPage.nextIndex);
          }
          return;
        }
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
  }, [sampleMode, selectedDemoScenario, selectedJob]);

  useEffect(() => {
    if (sampleMode || !selectedJob || !selectedEntry) {
      setAttemptTimeline(null);
      return;
    }
    let cancelled = false;
    setAttemptTimeline(null);
    void fetchScenarioRehearsalBatchItemAttempts(
      selectedJob.jobId,
      selectedEntry.index,
    )
      .then((timeline) => {
        if (!cancelled) {
          setAttemptTimeline(timeline);
        }
      })
      .catch(() => {
        // Older compatible deployments fall back to the explicitly labelled aggregate projection.
      });
    return () => {
      cancelled = true;
    };
  }, [sampleMode, selectedEntry, selectedJob]);

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
    if (sampleMode) {
      const sampleChild = selectedDemoScenario?.childWorkbooks[selectedEntry.runId];
      if (sampleChild) {
        setChildWorkbook(sampleChild);
      } else {
        setDetailError('This sample intentionally has no complete signed child workbook.');
      }
      setLoadingChild(false);
      return;
    }
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
  }, [sampleMode, selectedDemoScenario, selectedEntry, terminal]);

  async function loadOlderJobs() {
    if (sampleMode || !nextCursor) {
      return;
    }
    const generation = jobPageGeneration.current + 1;
    jobPageGeneration.current = generation;
    setLoadingOlderJobs(true);
    setError('');
    try {
      const page = await fetchScenarioRehearsalBatchJobs(50, nextCursor);
      if (generation !== jobPageGeneration.current) {
        return;
      }
      setLiveJobs((current) => Array.from(
        new Map([...current, ...page.jobs].map((job) => [job.jobId, job])).values(),
      ));
      setNextCursor(page.nextCursor);
    } catch (cause) {
      if (generation === jobPageGeneration.current) {
        setError(cause instanceof Error ? cause.message : 'Unable to load older batches.');
      }
    } finally {
      if (generation === jobPageGeneration.current) {
        setLoadingOlderJobs(false);
      }
    }
  }

  async function loadMoreItems() {
    if (sampleMode || !selectedJob || nextItemIndex === null) {
      return;
    }
    const generation = itemPageGeneration.current + 1;
    itemPageGeneration.current = generation;
    setLoadingMoreItems(true);
    setError('');
    try {
      const page = await fetchScenarioRehearsalBatchItems(selectedJob.jobId, nextItemIndex, 100);
      if (generation !== itemPageGeneration.current) {
        return;
      }
      setLiveItems((current) => [...current, ...page.items]);
      setNextItemIndex(page.nextIndex);
    } catch (cause) {
      if (generation === itemPageGeneration.current) {
        setError(cause instanceof Error ? cause.message : 'Unable to load more batch items.');
      }
    } finally {
      if (generation === itemPageGeneration.current) {
        setLoadingMoreItems(false);
      }
    }
  }

  function cancelPendingDiscovery() {
    discoveryGeneration.current += 1;
    setLoadingJobs(false);
  }

  function selectJob(jobId: string) {
    cancelPendingDiscovery();
    itemPageGeneration.current += 1;
    setLoadingMoreItems(false);
    setSelectedJobId(jobId);
    setSelectedEntryIndex(null);
    setSelectedRemediationId('');
    setFilter('ALL');
    updateDeepLink(jobId, null, '', mode);
  }

  function selectEntry(index: number) {
    cancelPendingDiscovery();
    setSelectedEntryIndex(index);
    updateDeepLink(selectedJobId, index, selectedRemediationId, mode);
  }

  function closeEvidence() {
    cancelPendingDiscovery();
    setSelectedEntryIndex(null);
    updateDeepLink(selectedJobId, null, selectedRemediationId, mode);
  }

  function selectRemediation(remediationId: string) {
    cancelPendingDiscovery();
    setSelectedRemediationId(remediationId);
    updateDeepLink(selectedJobId, selectedEntryIndex, remediationId, mode);
  }

  function showSamples() {
    cancelPendingDiscovery();
    jobPageGeneration.current += 1;
    setLoadingOlderJobs(false);
    itemPageGeneration.current += 1;
    setLoadingMoreItems(false);
    setMode('SAMPLES');
    setSelectedJobId(DEFAULT_REHEARSAL_DEMO_ID);
    setSelectedEntryIndex(null);
    setSelectedRemediationId('');
    setFilter('ALL');
    setError('');
    updateDeepLink(DEFAULT_REHEARSAL_DEMO_ID, null, '', 'SAMPLES');
  }

  function showLiveData() {
    itemPageGeneration.current += 1;
    setLoadingMoreItems(false);
    if (liveJobs.length > 0) {
      cancelPendingDiscovery();
      const nextJobId = liveJobs[0].jobId;
      setMode('LIVE');
      setSelectedJobId(nextJobId);
      setSelectedEntryIndex(null);
      setSelectedRemediationId('');
      setFilter('ALL');
      setError('');
      updateDeepLink(nextJobId, null, '', 'LIVE');
      return;
    }
    void discoverJobs(false);
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
            <p className="workbench-kicker">{sampleMode ? 'Guided sample data' : 'Exact scope'}</p>
            <h2>{sampleMode ? 'Scenario gallery' : 'Rehearsal batches'}</h2>
          </div>
          <div className="workbench-pane-actions">
            <div className="workbench-mode-switch" role="group" aria-label="Rehearsal data source">
              <button
                type="button"
                className={!sampleMode ? 'active' : ''}
                onClick={showLiveData}
                aria-pressed={!sampleMode}
              >
                Live
              </button>
              <button
                type="button"
                className={sampleMode ? 'active' : ''}
                onClick={showSamples}
                aria-pressed={sampleMode}
              >
                Samples
              </button>
            </div>
            <button
              className="compact-command"
              type="button"
              onClick={() => void discoverJobs(!sampleMode)}
              disabled={loadingJobs}
            >
              {sampleMode ? 'Retry live' : 'Refresh'}
            </button>
          </div>
        </div>
        {sampleMode && (
          <div
            className="sample-data-notice"
            data-testid="sample-data-notice"
            role="status"
            aria-live="polite"
          >
            <strong>Sample data</strong>
            <span>
              {error
                ? 'Live data is unavailable; the local preview remains active.'
                : batchApiAvailable
                ? 'Protocol-shaped preview; not server evidence.'
                : 'Batch API unavailable; using a local protocol-shaped preview.'}
            </span>
          </div>
        )}
        {jobs[0] && (
          <dl className="scope-coordinates" data-testid="scope-coordinates">
            <div><dt>Tenant</dt><dd>{jobs[0].scope.tenantId}</dd></div>
            <div><dt>Project</dt><dd>{jobs[0].scope.projectId}</dd></div>
            <div><dt>Environment</dt><dd>{jobs[0].scope.environmentId}</dd></div>
            <div><dt>Region</dt><dd>{jobs[0].scope.region}</dd></div>
          </dl>
        )}
        <div className="batch-queue-list">
          {jobs.map((job) => {
            const demo = sampleMode ? findRehearsalDemoScenario(job.jobId) : undefined;
            return (
              <button
                className={`batch-queue-row ${job.jobId === selectedJobId ? 'selected' : ''}`}
                type="button"
                key={job.jobId}
                onClick={() => selectJob(job.jobId)}
                aria-pressed={job.jobId === selectedJobId}
                data-testid={`batch-${job.jobId}`}
              >
                <span className="batch-queue-row-top">
                  <strong>{demo?.title ?? job.jobId}</strong>
                  <span className={`status-label ${statusTone(job.status)}`}>{job.status}</span>
                </span>
                {demo && <span className="sample-row-focus">{demo.focus}</span>}
                <span>{job.summary.completedItems} / {job.summary.totalItems} complete</span>
                {!demo && <span>{formatDate(job.createdAt)}</span>}
              </button>
            );
          })}
          {!sampleMode && !loadingJobs && jobs.length === 0 && batchApiAvailable && (
            <div className="empty-workbench">
              <p>No Scenario rehearsal batches are visible in this scope.</p>
              <button className="compact-command" type="button" onClick={showSamples}>
                Explore samples
              </button>
            </div>
          )}
          {!sampleMode && !loadingJobs && !batchApiAvailable && (
            <p className="empty-workbench" data-testid="rehearsal-api-unavailable">
              Scenario rehearsals are not enabled for this deployment.
            </p>
          )}
        </div>
        {!sampleMode && nextCursor && (
          <button
            className="pane-command"
            type="button"
            onClick={() => void loadOlderJobs()}
            disabled={loadingJobs || loadingOlderJobs}
          >
            {loadingOlderJobs ? 'Loading older batches...' : 'Load older batches'}
          </button>
        )}
      </aside>

      <section className="rehearsal-results" aria-label="Selected batch">
        {error && <div className="workbench-alert danger" role="alert">{error}</div>}
        {selectedJob ? (
          <>
            <header className="batch-overview">
              {sampleMode && (
                <div className="sample-workbook-banner" data-testid="sample-workbook-banner">
                  <strong>Illustrative sample</strong>
                  <span>No server signature, governance approval, or release evidence is produced.</span>
                </div>
              )}
              <div className="batch-overview-title">
                <div>
                  <p className="workbench-kicker">
                    {sampleMode
                      ? selectedDemoScenario?.focus
                      : terminal ? 'Root-sealed terminal evidence' : 'Integrity-protected live state'}
                  </p>
                  <h2>{selectedDemoScenario?.title ?? selectedJob.jobId}</h2>
                  {selectedDemoScenario && (
                    <p className="sample-situation">{selectedDemoScenario.situation}</p>
                  )}
                </div>
                <div className="evidence-mode" data-testid="evidence-mode">
                  <strong>
                    {sampleMode
                      ? terminal ? 'Sample workbook' : 'Sample live projection'
                      : terminal ? 'Signed workbook' : 'Live projection'}
                  </strong>
                  <span>
                    {sampleMode
                      ? selectedJob.jobId
                      : terminal
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

            {terminal && workbook && !workbook.gateReady && !sampleMode && (
              <RehearsalRemediationPanel
                key={workbook.jobId}
                workbook={workbook}
                initialRemediationId={selectedRemediationId}
                onRemediationIdChange={selectRemediation}
              />
            )}

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
                            <span>
                              {entry.workbookFingerprint
                                ? sampleMode ? 'Sample child workbook' : 'Signed child workbook'
                                : entry.evidenceFingerprint
                                  ? 'Evidence bundle'
                                  : 'Not sealed'}
                            </span>
                            <strong>View</strong>
                          </span>
                        </button>
                      );
                    })}
                  </div>
                </section>
              ))}
            </div>
            {!sampleMode && !terminal && nextItemIndex !== null && (
              <button
                className="pane-command load-more-items"
                type="button"
                onClick={() => void loadMoreItems()}
                disabled={loadingJob || loadingMoreItems}
              >
                {loadingMoreItems ? 'Loading more items...' : 'Load more items'}
              </button>
            )}
          </>
        ) : !loadingJobs && (
          <div className="workbench-welcome">
            <h2>{batchApiAvailable ? 'No batch selected' : 'Rehearsals unavailable'}</h2>
            <p>
              {batchApiAvailable
                ? 'Choose a Scenario rehearsal batch to inspect its execution and evidence state.'
                : 'This deployment does not advertise the Scenario rehearsal batch API.'}
            </p>
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
                <p className="workbench-kicker">
                  {sampleMode ? 'Illustrative sample' : `Batch item #${selectedEntry.index}`}
                </p>
                <h2>{sampleMode ? 'Sample evidence' : 'Evidence'}</h2>
                {sampleMode && <span className="sample-drawer-label">Not server evidence</span>}
              </div>
              <button className="drawer-close" type="button" onClick={closeEvidence} aria-label="Close evidence">
                Close
              </button>
            </header>
            {selectedEvidence && (
              <>
                <section className="rehearsal-entry-verdict" data-testid="rehearsal-entry-verdict">
                  <span className={`status-label ${selectedEvidence.verdictTone}`}>
                    {selectedEvidence.verdictLabel}
                  </span>
                  <h3>{selectedEvidence.headline}</h3>
                  <p>{selectedEvidence.rootCause}</p>
                  <dl>
                    <div>
                      <dt>Business impact</dt>
                      <dd>{selectedEvidence.businessImpact}</dd>
                    </div>
                    <div>
                      <dt>Responsible</dt>
                      <dd>{selectedEvidence.owner} · {selectedEvidence.requiredRole}</dd>
                    </div>
                  </dl>
                </section>
                <RemediationActionList
                  actions={selectedEvidence.action ? [selectedEvidence.action] : []}
                  onInvoke={() => undefined}
                />
                <section className="evidence-section rehearsal-attempts" data-testid="rehearsal-attempts">
                  <div className="evidence-section-heading">
                    <h3>Attempt timeline</h3>
                    <div>
                      <span className={`attempt-evidence-source ${
                        selectedEvidence.attemptsExact ? 'exact' : 'aggregate'
                      }`}>
                        {selectedEvidence.attemptsExact ? 'Exact lifecycle' : 'Aggregate projection'}
                      </span>
                      <strong>
                        {selectedEvidence.attemptsUsed} of {selectedEvidence.attemptsMaximum} used
                      </strong>
                    </div>
                  </div>
                  <dl className="rehearsal-attempt-budget">
                    <div><dt>Remaining</dt><dd>{selectedEvidence.attemptsRemaining}</dd></div>
                    <div><dt>Deadline</dt><dd>{formatDate(selectedEvidence.deadlineAt)}</dd></div>
                    <div><dt>Batch policy</dt><dd>{selectedEvidence.batchFallback}</dd></div>
                    <div><dt>Item fallback</dt><dd>{selectedEvidence.itemFallback}</dd></div>
                  </dl>
                  {selectedEvidence.timeline.length > 0 ? (
                    <ol>
                      {selectedEvidence.timeline.map((attempt) => (
                        <li key={attempt.attempt} data-state={attempt.state.toLowerCase()}>
                          <span>{attempt.attempt}</span>
                          <div>
                            <strong>
                              Attempt {attempt.attempt} · {attemptStateLabel(attempt.state)}
                            </strong>
                            <p>{attempt.observation}</p>
                            <small>
                              {attempt.observedAt ? formatDate(attempt.observedAt) : 'Timestamp not retained'}
                              {!attempt.exact ? ' · projection limit, not inferred' : ''}
                            </small>
                          </div>
                        </li>
                      ))}
                    </ol>
                  ) : (
                    <p className="empty-workbench">No attempt has started.</p>
                  )}
                  <div className="rehearsal-last-observation">
                    <strong>Last observation</strong>
                    <span>{selectedEvidence.lastObservation}</span>
                  </div>
                </section>
              </>
            )}
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
            <details className="rehearsal-technical-details">
              <summary>Technical identity and integrity</summary>
              <section className="evidence-section">
                <h3>Identity</h3>
                <dl className="evidence-fields">
                  <div><dt>Compiled plan</dt><dd>{selectedEntry.planId}@{selectedEntry.planRevision}</dd></div>
                  <div><dt>Run</dt><dd>{selectedEntry.runId || 'Not assigned'}</dd></div>
                  <div><dt>Outcome</dt><dd>{selectedEntry.status}</dd></div>
                  <div>
                    <dt>Started</dt>
                    <dd>{formatEntryDate(selectedEntry.startedAt, terminal, 'Not started')}</dd>
                  </div>
                  <div>
                    <dt>Completed</dt>
                    <dd>{formatEntryDate(selectedEntry.completedAt, terminal, 'Not complete')}</dd>
                  </div>
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
            </details>
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

function attemptStateLabel(state: 'RETRY SCHEDULED' | 'RUNNING' | 'TERMINAL'): string {
  if (state === 'RETRY SCHEDULED') {
    return 'Retry scheduled';
  }
  return state === 'RUNNING' ? 'Running' : 'Terminal';
}
