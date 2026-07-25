// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  fetchScenarioRehearsalBatchItems,
  fetchScenarioRehearsalBatchJobs,
  fetchScenarioRehearsalBatchWorkbook,
  fetchScenarioRehearsalWorkbook,
} from './api';
import RehearsalWorkbench from './RehearsalWorkbench';
import type {
  ScenarioRehearsalBatchJob,
  ScenarioRehearsalBatchWorkbookSeed,
  ScenarioRehearsalWorkbookSeed,
} from './types';

vi.mock('./api', () => ({
  fetchScenarioRehearsalBatchItems: vi.fn(),
  fetchScenarioRehearsalBatchJobs: vi.fn(),
  fetchScenarioRehearsalBatchWorkbook: vi.fn(),
  fetchScenarioRehearsalWorkbook: vi.fn(),
}));

const mockJobs = vi.mocked(fetchScenarioRehearsalBatchJobs);
const mockItems = vi.mocked(fetchScenarioRehearsalBatchItems);
const mockBatchWorkbook = vi.mocked(fetchScenarioRehearsalBatchWorkbook);
const mockChildWorkbook = vi.mocked(fetchScenarioRehearsalWorkbook);

describe('RehearsalWorkbench', () => {
  let root: Root | null = null;
  let host: HTMLDivElement;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    window.history.replaceState({}, '', '/rehearsals/');
    host = document.createElement('div');
    document.body.appendChild(host);
    vi.clearAllMocks();
  });

  afterEach(async () => {
    if (root) {
      await act(async () => root?.unmount());
      root = null;
    }
    host.remove();
  });

  it('labels an active batch as mutable and never requests terminal evidence', async () => {
    mockJobs.mockResolvedValue(jobPage([batchJob('job-live', 'RUNNING')]));
    mockItems.mockResolvedValue({
      schemaVersion: 'resourceGateway.scenarioRehearsalBatchItemPage.v1',
      jobId: 'job-live',
      manifestFingerprint: fingerprint('manifest-live'),
      items: [{
        itemIndex: 0,
        compiledPlanRef: artifact('compiled-plan', 'plan-live'),
        childRequestId: 'child-request-live',
        status: 'RUNNING',
        attemptCount: 1,
        runId: 'run-live',
        evidenceBundleFingerprint: '',
        workbookSeedFingerprint: '',
        failureCode: '',
        startedAt: '2026-07-25T10:01:00Z',
        completedAt: null,
      }],
      nextIndex: null,
    });

    await render();
    await waitFor(() => text().includes('Live projection'));

    expect(text()).toContain('Mutable and not publish-gate evidence');
    expect(text()).toContain('Mutable running projection');
    expect(mockItems).toHaveBeenCalledWith('job-live', 0, 100);
    expect(mockBatchWorkbook).not.toHaveBeenCalled();
    expect(mockChildWorkbook).not.toHaveBeenCalled();
  });

  it('groups terminal failures and lazily opens signed case evidence', async () => {
    mockJobs.mockResolvedValue(jobPage([batchJob('job-terminal', 'PARTIAL')]));
    mockBatchWorkbook.mockResolvedValue(batchWorkbook());
    mockChildWorkbook.mockResolvedValue(childWorkbook());

    await render();
    await waitFor(() => text().includes('Signed workbook'));

    expect(text()).toContain('Publish gate blocked');
    expect(query('[data-testid="root-blockers"]').textContent).toContain('BLOCKER_ASSERTION_FAILED');
    expect(text()).toContain('Execution');
    expect(text()).toContain('Assertions');
    expect(mockChildWorkbook).not.toHaveBeenCalled();

    await click('[data-testid="entry-1"]');
    await waitFor(() => document.querySelector('[data-testid="child-cases"]') !== null);

    expect(mockChildWorkbook).toHaveBeenCalledOnce();
    expect(mockChildWorkbook).toHaveBeenCalledWith('run-assertion');
    expect(window.location.search).toContain('jobId=job-terminal');
    expect(window.location.search).toContain('entry=1');
    expect(query('[data-testid="rehearsal-workbench"]').classList.contains('drawer-open')).toBe(true);
    expect(query('[data-testid="child-cases"]').textContent).toContain('customer-answer-is-grounded');
    expect(query('[data-testid="child-cases"]').textContent).toContain('GROUNDING_BELOW_THRESHOLD');
    expect(text()).not.toContain('secret customer payload');

    await click('.drawer-close');
    expect(query('[data-testid="rehearsal-workbench"]').classList.contains('drawer-open')).toBe(false);
    expect(window.location.search).not.toContain('entry=');
  });

  it('restores a deep-linked entry and verifies its child workbook after the root workbook loads', async () => {
    window.history.replaceState({}, '', '/rehearsals/?jobId=job-terminal&entry=1');
    mockJobs.mockResolvedValue(jobPage([batchJob('job-terminal', 'PARTIAL')]));
    mockBatchWorkbook.mockResolvedValue(batchWorkbook());
    mockChildWorkbook.mockResolvedValue(childWorkbook());

    await render();
    await waitFor(() => document.querySelector('[data-testid="child-summary"]') !== null);

    expect(query('[data-testid="entry-1"]').classList.contains('selected')).toBe(true);
    expect(mockChildWorkbook).toHaveBeenCalledWith('run-assertion');
    expect(query('[data-testid="child-summary"]').textContent).toContain('BLOCKED');
  });

  it('appends older keyset pages without duplicating jobs', async () => {
    mockJobs
      .mockResolvedValueOnce(jobPage(
        [batchJob('job-new', 'RUNNING')],
        { createdAt: '2026-07-25T09:00:00Z', jobId: 'job-new' },
      ))
      .mockResolvedValueOnce(jobPage([
        batchJob('job-new', 'RUNNING'),
        batchJob('job-old', 'SUCCEEDED'),
      ]));
    mockItems.mockResolvedValue({
      schemaVersion: 'resourceGateway.scenarioRehearsalBatchItemPage.v1',
      jobId: 'job-new',
      manifestFingerprint: fingerprint('manifest-new'),
      items: [],
      nextIndex: null,
    });

    await render();
    await waitFor(() => text().includes('Load older batches'));
    await clickText('Load older batches');
    await waitFor(() => document.querySelector('[data-testid="batch-job-old"]') !== null);

    expect(document.querySelectorAll('[data-testid="batch-job-new"]')).toHaveLength(1);
    expect(mockJobs).toHaveBeenLastCalledWith(50, {
      createdAt: '2026-07-25T09:00:00Z',
      jobId: 'job-new',
    });
  });

  async function render() {
    await act(async () => {
      root = createRoot(host);
      root.render(<RehearsalWorkbench />);
    });
  }
});

function batchJob(
  jobId: string,
  status: ScenarioRehearsalBatchJob['status'],
): ScenarioRehearsalBatchJob {
  return {
    schemaVersion: 'resourceGateway.scenarioRehearsalBatchJob.v2',
    jobId,
    requestId: `request-${jobId}`,
    requestFingerprint: fingerprint(`request-${jobId}`),
    manifestFingerprint: fingerprint(`manifest-${jobId}`),
    scope: {
      tenantId: 'tenant-a',
      organizationId: 'knowledge-governance',
      projectId: 'tool-studio',
      environmentId: 'test',
      region: 'sg',
    },
    status,
    failureMode: 'CONTINUE',
    priority: 'NORMAL',
    maximumItemAttempts: 2,
    summary: {
      totalItems: 3,
      completedItems: status === 'RUNNING' ? 1 : 3,
      passedItems: 1,
      failedItems: status === 'RUNNING' ? 0 : 2,
      indeterminateItems: 0,
      cancelledItems: 0,
    },
    deadlineAt: '2026-07-25T11:00:00Z',
    failureCode: '',
    createdAt: '2026-07-25T10:00:00Z',
    updatedAt: '2026-07-25T10:02:00Z',
    completedAt: status === 'RUNNING' ? null : '2026-07-25T10:02:00Z',
    recordFingerprint: fingerprint(`record-${jobId}`),
  };
}

function jobPage(
  jobs: ScenarioRehearsalBatchJob[],
  nextCursor: { createdAt: string; jobId: string } | null = null,
) {
  return {
    schemaVersion: 'resourceGateway.scenarioRehearsalBatchJobPage.v1' as const,
    scope: jobs[0]?.scope ?? batchJob('scope', 'RUNNING').scope,
    jobs,
    nextCursor,
  };
}

function batchWorkbook(): ScenarioRehearsalBatchWorkbookSeed {
  const summary = {
    totalItems: 3,
    completedItems: 3,
    passedItems: 1,
    failedItems: 2,
    indeterminateItems: 0,
    cancelledItems: 0,
  };
  return {
    schemaVersion: 'resourceGateway.scenarioRehearsalBatchWorkbookSeed.v1',
    seedFingerprint: fingerprint('root-seed'),
    scope: batchJob('job-terminal', 'PARTIAL').scope,
    jobId: 'job-terminal',
    requestId: 'request-terminal',
    requestFingerprint: fingerprint('request-terminal'),
    manifestFingerprint: fingerprint('manifest-terminal'),
    terminalJobFingerprint: fingerprint('terminal-job'),
    evidenceBundleFingerprint: fingerprint('batch-evidence'),
    evidenceIndexFingerprint: fingerprint('batch-index'),
    evidenceKeyId: 'evidence-key-1',
    workbookSeal: {
      keyId: 'workbook-key-1',
      algorithm: 'Ed25519',
      materialFingerprint: fingerprint('workbook-material'),
      signature: 'base64:signature',
    },
    retentionProof: {
      eventFingerprint: fingerprint('retention-event'),
      retainUntil: '2033-07-25T10:00:00Z',
    },
    status: 'PARTIAL',
    summary,
    entries: [{
      entryIndex: 0,
      entryId: 'entry-execution',
      compiledPlanRef: artifact('compiled-plan', 'plan-execution'),
      childRequestId: 'child-request-execution',
      expectedRunId: 'run-execution',
      status: 'FAILED',
      attemptCount: 2,
      runId: 'run-execution',
      childEvidenceBundleFingerprint: fingerprint('execution-evidence'),
      childWorkbookSeedFingerprint: '',
      failureCode: 'TARGET_TIMEOUT',
      childWorkbook: null,
    }, {
      entryIndex: 1,
      entryId: 'entry-assertion',
      compiledPlanRef: artifact('compiled-plan', 'plan-assertion'),
      childRequestId: 'child-request-assertion',
      expectedRunId: 'run-assertion',
      status: 'PASSED',
      attemptCount: 1,
      runId: 'run-assertion',
      childEvidenceBundleFingerprint: fingerprint('assertion-evidence'),
      childWorkbookSeedFingerprint: fingerprint('assertion-workbook'),
      failureCode: '',
      childWorkbook: {
        schemaVersion: 'resourceGateway.scenarioRehearsalWorkbookSeed.v1',
        seedFingerprint: fingerprint('assertion-workbook'),
        runId: 'run-assertion',
        requestId: 'request-assertion',
        compiledPlanRef: artifact('compiled-plan', 'plan-assertion'),
        scenarioPackRef: artifact('scenario-pack', 'pack-assertion'),
        targetCapabilityRef: artifact('capability', 'support-answer'),
        evidenceBundleFingerprint: fingerprint('assertion-evidence'),
        resultFingerprint: fingerprint('assertion-result'),
        evidenceKeyId: 'evidence-key-1',
        retentionProofFingerprint: fingerprint('retention-assertion'),
        outcome: 'FAIL',
        summary: {
          totalCases: 1,
          passedCases: 0,
          failedCases: 1,
          indeterminateCases: 0,
          assertionResults: 1,
          blockerFailures: 1,
          blockerIndeterminate: 0,
          warningFailures: 0,
          warningIndeterminate: 0,
        },
        gateReady: false,
        blockers: ['BLOCKER_ASSERTION_FAILED'],
      },
    }],
    gateReady: false,
    blockers: ['BLOCKER_ASSERTION_FAILED'],
  };
}

function childWorkbook(): ScenarioRehearsalWorkbookSeed {
  const embedded = batchWorkbook().entries[1].childWorkbook;
  if (!embedded) {
    throw new Error('Missing embedded child workbook fixture.');
  }
  return {
    ...embedded,
    scope: batchJob('job-terminal', 'PARTIAL').scope,
    retentionProof: {
      eventFingerprint: fingerprint('retention-assertion'),
      retainUntil: '2033-07-25T10:00:00Z',
    },
    cases: [{
      caseIndex: 0,
      scenarioCaseRef: artifact('scenario-case', 'grounding-golden'),
      caseType: 'GOLDEN',
      testCaseId: 'customer-answer-is-grounded',
      childRunId: 'case-run-1',
      childEvidenceBundleFingerprint: fingerprint('case-evidence'),
      evidenceStatus: 'PASSED',
      evidenceClass: 'CERTIFIABLE',
      outcome: 'FAIL',
      diagnosticCode: 'GROUNDING_BELOW_THRESHOLD',
      assertionResults: [{
        resultFingerprint: fingerprint('assertion-result-1'),
        assertionRef: artifact('handling-assertion', 'answer-grounding'),
        observation: 'Score was below the governed threshold.',
        outcome: 'FAIL',
        severity: 'BLOCKER',
        governanceCode: 'GROUNDING_BELOW_THRESHOLD',
        reasonCode: 'ANSWER_NOT_GROUNDED',
      }],
    }],
  };
}

function artifact(kind: string, id: string) {
  return {
    kind,
    id,
    revision: 1,
    fingerprint: fingerprint(`${kind}-${id}`),
  };
}

function fingerprint(seed: string): string {
  return `sha256:${seed.padEnd(64, seed[0] ?? '0').slice(0, 64)}`;
}

async function waitFor(predicate: () => boolean) {
  for (let attempt = 0; attempt < 40; attempt += 1) {
    if (predicate()) {
      return;
    }
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 1));
    });
  }
  throw new Error(`Condition was not met. Current body: ${document.body.textContent}`);
}

async function click(selector: string) {
  const element = query<HTMLElement>(selector);
  await act(async () => {
    element.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  });
}

async function clickText(label: string) {
  const element = Array.from(document.querySelectorAll('button'))
    .find((button) => button.textContent?.includes(label));
  if (!element) {
    throw new Error(`Missing button containing: ${label}`);
  }
  await act(async () => {
    element.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  });
}

function text(): string {
  return document.body.textContent ?? '';
}

function query<T extends Element = Element>(selector: string): T {
  const element = document.querySelector<T>(selector);
  if (!element) {
    throw new Error(`Missing element: ${selector}`);
  }
  return element;
}
