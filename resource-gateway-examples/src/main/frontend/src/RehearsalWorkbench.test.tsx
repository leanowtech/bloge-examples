// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  BlogeApiRequestError,
  fetchScenarioRehearsalBatchItemAttempts,
  fetchScenarioRehearsalBatchItems,
  fetchScenarioRehearsalBatchJobs,
  fetchScenarioRehearsalBatchWorkbook,
  fetchScenarioRehearsalWorkbook,
  getRehearsalRemediationCredentialStatus,
} from './api';
import I18nProvider from './i18n/I18nProvider';
import RehearsalWorkbench from './RehearsalWorkbench';
import type {
  ScenarioRehearsalBatchJob,
  ScenarioRehearsalBatchWorkbookSeed,
  ScenarioRehearsalWorkbookSeed,
} from './types';

vi.mock('./api', () => ({
  BlogeApiRequestError: class BlogeApiRequestError extends Error {
    constructor(
      readonly status: number,
      readonly detail: string,
    ) {
      super(`Request failed: ${status} ${detail}`);
    }
  },
  fetchScenarioRehearsalBatchItems: vi.fn(),
  fetchScenarioRehearsalBatchItemAttempts: vi.fn(),
  fetchScenarioRehearsalBatchJobs: vi.fn(),
  fetchScenarioRehearsalBatchWorkbook: vi.fn(),
  fetchScenarioRehearsalWorkbook: vi.fn(),
  getRehearsalRemediationCredentialStatus: vi.fn((slot: string) => ({
    slot,
    configured: false,
    principalLabel: '',
    expiresAt: '',
  })),
  previewScenarioRehearsalRemediation: vi.fn(),
  fetchScenarioRehearsalRemediationLineage: vi.fn(),
  decideScenarioRehearsalRemediation: vi.fn(),
  submitScenarioRehearsalRemediation: vi.fn(),
  fetchScenarioRehearsalRemediationComparison: vi.fn(),
}));

const mockJobs = vi.mocked(fetchScenarioRehearsalBatchJobs);
const mockItems = vi.mocked(fetchScenarioRehearsalBatchItems);
const mockAttempts = vi.mocked(fetchScenarioRehearsalBatchItemAttempts);
const mockBatchWorkbook = vi.mocked(fetchScenarioRehearsalBatchWorkbook);
const mockChildWorkbook = vi.mocked(fetchScenarioRehearsalWorkbook);
const mockCredentialStatus = vi.mocked(getRehearsalRemediationCredentialStatus);

describe('RehearsalWorkbench', () => {
  let root: Root | null = null;
  let host: HTMLDivElement;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    window.history.replaceState({}, '', '/rehearsals/');
    host = document.createElement('div');
    document.body.appendChild(host);
    vi.resetAllMocks();
    mockCredentialStatus.mockImplementation((slot) => ({
      slot,
      configured: false,
      principalLabel: '',
      expiresAt: '',
    }));
    mockAttempts.mockRejectedValue(new BlogeApiRequestError(404, 'Not Found'));
  });

  afterEach(async () => {
    if (root) {
      await act(async () => root?.unmount());
      root = null;
    }
    host.remove();
  });

  it('falls back to clearly labelled samples when the optional batch API is absent', async () => {
    mockJobs.mockRejectedValue(new BlogeApiRequestError(404, 'Not Found'));

    await render();
    await waitFor(() => document.querySelector('[data-testid="sample-workbook-banner"]') !== null);

    expect(document.querySelector('[role="alert"]')).toBeNull();
    expect(text()).not.toContain('Request failed: 404');
    expect(query('[data-testid="sample-data-notice"]').textContent)
      .toContain('Batch API unavailable');
    expect(text()).toContain('Grounding policy regression');
    expect(text()).toContain('Triage every failure category');
    expect(text()).toContain('Execution 1');
    expect(text()).toContain('Evidence 1');
    expect(text()).toContain('Assertions 1');
    expect(text()).toContain('Governance 1');
    expect(text()).toContain('Warnings 1');
    expect(text()).toContain('Passed 1');
    expect(mockBatchWorkbook).not.toHaveBeenCalled();
    expect(mockChildWorkbook).not.toHaveBeenCalled();
    expect(mockJobs).toHaveBeenCalledOnce();
  });

  it('renders sample learning metadata and the relative evidence clock in Chinese', async () => {
    window.history.replaceState({}, '', '/rehearsals/?lang=zh-CN');
    mockJobs.mockRejectedValue(new BlogeApiRequestError(404, 'Not Found'));

    await render(true);
    await waitFor(() => text().includes('溯源策略回归'));

    expect(text()).toContain('分诊每一类失败');
    expect(text()).toContain('同时包含执行、证据、断言');
    expect(text()).not.toContain('Grounding policy regression');
    await click('[data-testid="entry-0"]');
    await waitFor(() => document.querySelector('[data-testid="rehearsal-attempts"]') !== null);
    expect(text()).toContain('1小时后');
    expect(text()).not.toContain('2026年7月27日');
  });

  it('returns from samples to live data when an explicit retry succeeds', async () => {
    mockJobs
      .mockRejectedValueOnce(new BlogeApiRequestError(404, 'Not Found'))
      .mockResolvedValueOnce(jobPage([batchJob('job-recovered', 'RUNNING')]));
    mockItems.mockResolvedValue({
      schemaVersion: 'resourceGateway.scenarioRehearsalBatchItemPage.v1',
      jobId: 'job-recovered',
      manifestFingerprint: fingerprint('manifest-recovered'),
      items: [],
      nextIndex: null,
    });

    await render();
    await waitFor(() => document.querySelector('[data-testid="sample-data-notice"]') !== null);
    await clickText('Retry live');
    await waitFor(() => document.querySelector('[data-testid="batch-job-recovered"]') !== null);

    expect(document.querySelector('[data-testid="sample-data-notice"]')).toBeNull();
    expect(document.querySelector('[role="alert"]')).toBeNull();
    expect(mockJobs).toHaveBeenCalledTimes(2);
    expect(mockItems).toHaveBeenCalledWith('job-recovered', 0, 100);
  });

  it('keeps samples usable while surfacing non-404 live-data failures', async () => {
    mockJobs.mockRejectedValue(new BlogeApiRequestError(503, 'Service Unavailable'));

    await render();
    await waitFor(() => document.querySelector('[data-testid="sample-workbook-banner"]') !== null);

    expect(query('[role="alert"]').textContent).toContain('Request failed: 503 Service Unavailable');
    expect(query('[role="alert"] span').textContent)
      .toBe('Request failed: 503 Service Unavailable');
    expect(query<HTMLDetailsElement>('[role="alert"] details').open).toBe(false);
    expect(document.querySelector('[data-testid="rehearsal-api-unavailable"]')).toBeNull();
    expect(query('[data-testid="sample-data-notice"]').textContent)
      .toContain('Live data is unavailable');
    expect(text()).toContain('Grounding policy regression');
  });

  it('fails closed for an unregistered live-data error in the Chinese product surface', async () => {
    window.history.replaceState({}, '', '/rehearsals/?lang=zh-CN');
    mockJobs.mockRejectedValue(new BlogeApiRequestError(503, 'Service Unavailable'));

    await render(true);
    await waitFor(() => document.querySelector('[data-testid="sample-workbook-banner"]') !== null);

    expect(query('[role="alert"] span').textContent)
      .toBe('未识别的产品状态，请查看技术详情。');
    expect(query('[role="alert"] details').textContent)
      .toContain('Request failed: 503 Service Unavailable');
  });

  it('uses samples when the live scope is empty and drills into local child evidence', async () => {
    mockJobs.mockResolvedValue(jobPage([]));

    await render();
    await waitFor(() => text().includes('Grounding policy regression'));
    await click('[data-testid="batch-sample-release-ready"]');
    await waitFor(() => text().includes('Release candidate ready'));

    expect(text()).toContain('Sample workbook');
    expect(text()).toContain('GateReady');
    expect(query('[data-testid="entry-0"]').textContent).toContain('View');
    expect(window.location.search).toContain('sample=sample-release-ready');

    await click('[data-testid="entry-0"]');
    await waitFor(() => document.querySelector('[data-testid="child-cases"]') !== null);

    expect(query('[data-testid="child-summary"]').textContent).toContain('GATE READY');
    expect(query('[data-testid="child-cases"]').textContent).toContain('account-lookup');
    expect(query('.sample-drawer-label').textContent).toContain('Not server evidence');
    expect(mockBatchWorkbook).not.toHaveBeenCalled();
    expect(mockChildWorkbook).not.toHaveBeenCalled();
    expect(window.location.search).toContain('entry=0');
  });

  it('restores a shareable sample deep link without querying the server', async () => {
    window.history.replaceState(
      {},
      '',
      '/rehearsals/?sample=sample-release-ready&entry=1',
    );

    await render();
    await waitFor(() => document.querySelector('[data-testid="child-summary"]') !== null);

    expect(text()).toContain('Release candidate ready');
    expect(query('[data-testid="entry-1"]').classList.contains('selected')).toBe(true);
    expect(query('[data-testid="child-cases"]').textContent).toContain('refund-eligibility');
    expect(mockJobs).not.toHaveBeenCalled();
    expect(mockBatchWorkbook).not.toHaveBeenCalled();
    expect(mockChildWorkbook).not.toHaveBeenCalled();
  });

  it('runs a deterministic sample retry with a predecessor-bound demo receipt and reset', async () => {
    mockJobs.mockResolvedValue(jobPage([]));

    await render();
    await waitFor(() => text().includes('Grounding policy regression'));
    await click('[data-testid="entry-0"]');
    await waitFor(() => document.querySelector('[data-testid="rehearsal-attempts"]') !== null);

    expect(query('[data-testid="rehearsal-entry-verdict"]').textContent)
      .toContain('Dependency timed out');
    expect(query('[data-testid="rehearsal-entry-verdict"]').textContent)
      .toContain('Business impact');
    expect(query('[data-testid="rehearsal-attempts"]').textContent)
      .toContain('2 of 3 used');
    expect(query('[data-testid="rehearsal-attempts"]').textContent)
      .toContain('Continue the batch and collect every item outcome.');
    expect(query('[data-testid="rehearsal-attempts"]').textContent)
      .toContain('Aggregate projection');
    expect(query('[data-testid="rehearsal-attempts"]').textContent)
      .toContain('projection limit, not inferred');
    expect(text()).not.toContain('Illustrative samples do not have a server-side Author target.');
    expect(Array.from(document.querySelectorAll('a')).some(
      (link) => link.textContent?.includes('Open exact Author target'),
    )).toBe(false);
    expect(document.querySelector<HTMLDetailsElement>('.rehearsal-technical-details')?.open)
      .toBe(false);

    await clickText('Run sample retry');
    await waitFor(() => document.querySelector('[data-testid="demo-remediation-receipt"]') !== null);

    expect(text()).toContain('Demo retry completed');
    expect(text()).toContain('sample-governance-blocked');
    expect(text()).toContain('sample-release-ready');
    expect(text()).toContain('Local demo only; no governance evidence was created.');
    expect(text()).toContain('Release candidate ready');
    expect(window.location.search).toContain('sample=sample-release-ready');

    await clickText('Reset sample');
    await waitFor(() => text().includes('Grounding policy regression'));
    expect(document.querySelector('[data-testid="demo-remediation-receipt"]')).toBeNull();
    expect(window.location.search).toContain('sample=sample-governance-blocked');
  });

  it('does not let a stale live discovery override an explicit Samples choice', async () => {
    const pending = deferred<ReturnType<typeof jobPage>>();
    mockJobs.mockReturnValue(pending.promise);

    await render();
    await waitFor(() => mockJobs.mock.calls.length === 1);
    await clickText('Samples');
    await waitFor(() => text().includes('Grounding policy regression'));

    pending.resolve(jobPage([batchJob('late-live-job', 'RUNNING')]));
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 1));
    });

    expect(query('[data-testid="sample-data-notice"]')).toBeTruthy();
    expect(window.location.search).toContain('sample=sample-governance-blocked');
    expect(window.location.search).not.toContain('jobId=late-live-job');
  });

  it('preserves the selected live batch and evidence coordinate on refresh', async () => {
    const jobs = [
      batchJob('job-first', 'RUNNING'),
      batchJob('job-selected', 'RUNNING'),
    ];
    mockJobs.mockResolvedValue(jobPage(jobs));
    mockItems.mockImplementation(async (jobId) => ({
      schemaVersion: 'resourceGateway.scenarioRehearsalBatchItemPage.v1',
      jobId,
      manifestFingerprint: fingerprint(`manifest-${jobId}`),
      items: [],
      nextIndex: null,
    }));

    await render();
    await waitFor(() => document.querySelector('[data-testid="batch-job-selected"]') !== null);
    await click('[data-testid="batch-job-selected"]');
    await clickText('Refresh');
    await waitFor(() => mockJobs.mock.calls.length === 2);

    expect(query('[data-testid="batch-job-selected"]').getAttribute('aria-pressed')).toBe('true');
    expect(window.location.search).toContain('jobId=job-selected');
  });

  it('does not let a pending refresh undo a newer live selection', async () => {
    const jobs = [
      batchJob('job-first', 'RUNNING'),
      batchJob('job-second', 'RUNNING'),
    ];
    const pending = deferred<ReturnType<typeof jobPage>>();
    mockJobs
      .mockResolvedValueOnce(jobPage(jobs))
      .mockReturnValueOnce(pending.promise);
    mockItems.mockImplementation(async (jobId) => ({
      schemaVersion: 'resourceGateway.scenarioRehearsalBatchItemPage.v1',
      jobId,
      manifestFingerprint: fingerprint(`manifest-${jobId}`),
      items: [],
      nextIndex: null,
    }));

    await render();
    await waitFor(() => document.querySelector('[data-testid="batch-job-second"]') !== null);
    await click('[data-testid="batch-job-second"]');
    await clickText('Refresh');
    await waitFor(() => mockJobs.mock.calls.length === 2);
    await click('[data-testid="batch-job-first"]');

    pending.resolve(jobPage(jobs));
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 1));
    });

    expect(query('[data-testid="batch-job-first"]').getAttribute('aria-pressed')).toBe('true');
    expect(window.location.search).toContain('jobId=job-first');
  });

  it('keeps evidence-plane quarantine failures out of the execution category', async () => {
    mockJobs.mockResolvedValue(jobPage([]));

    await render();
    await waitFor(() => text().includes('Grounding policy regression'));
    await click('[data-testid="batch-sample-evidence-quarantined"]');
    await waitFor(() => text().includes('Evidence finalization quarantine'));

    expect(text()).toContain('Execution 0');
    expect(text()).toContain('Evidence 2');
    expect(text()).toContain('EVIDENCE_SIGNER_UNAVAILABLE');
    expect(text()).toContain('RETENTION_PROOF_INCOMPLETE');
    expect(mockBatchWorkbook).not.toHaveBeenCalled();
  });

  it('renders the running sample as a mutable projection without backend calls', async () => {
    mockJobs.mockResolvedValue(jobPage([]));

    await render();
    await waitFor(() => text().includes('Grounding policy regression'));
    await click('[data-testid="batch-sample-live-dependency-degradation"]');
    await waitFor(() => text().includes('Sample live projection'));

    expect(query('[data-testid="entry-2"]').textContent).not.toContain('CRM_RATE_LIMITED');
    expect(query('[data-testid="entry-2"]').textContent)
      .toContain('The item needs review before it can contribute trusted evidence.');
    expect(text()).toContain('Mutable running projection');
    expect(text()).toContain('GatePending');
    expect(mockItems).not.toHaveBeenCalled();
    expect(mockBatchWorkbook).not.toHaveBeenCalled();
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
    expect(query('[data-testid="workspace-context-bar"]').textContent).toContain('tenant-a');
    expect(query('[data-testid="workspace-context-bar"]').textContent).toContain('TEST');
    expect(query('[data-testid="workspace-command-scope"]').getAttribute('data-scope-kind'))
      .toBe('case');
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

  it('presents protocol lifecycle states as localized product labels', async () => {
    window.history.replaceState({}, '', '/rehearsals/?lang=zh-CN');
    mockJobs.mockResolvedValue(jobPage([batchJob('job-terminal', 'PARTIAL')]));
    mockBatchWorkbook.mockResolvedValue(batchWorkbook());
    mockChildWorkbook.mockResolvedValue(childWorkbook());

    await render(true);
    await waitFor(() => text().includes('部分完成'));

    expect(text()).toContain('已失败');
    expect(text()).toContain('已通过');
    expect(text()).toContain('受治理的业务预期未能匹配。');
    expect(text()).not.toContain('PARTIAL');
    expect(document.querySelector('[aria-label="执行条目"]')).not.toBeNull();
  });

  it('keeps known blocker codes out of the default Chinese product summary', async () => {
    window.history.replaceState({}, '', '/rehearsals/?lang=zh-CN');
    mockJobs.mockResolvedValue(jobPage([batchJob('job-terminal', 'PARTIAL')]));
    mockBatchWorkbook.mockResolvedValue({
      ...batchWorkbook(),
      blockers: [
        'DEPENDENCY_TIMEOUT',
        'BLOCKER_ASSERTION_FAILED',
        'OWNER_APPROVAL_REQUIRED',
        'EVIDENCE_INCOMPLETE',
      ],
    });
    mockChildWorkbook.mockResolvedValue(childWorkbook());

    await render(true);
    await waitFor(() => text().includes('依赖服务未在规定时间内响应。'));

    const blockers = query('[data-testid="root-blockers"]');
    expect(blockers.textContent).toContain('受治理的业务预期未能匹配。');
    expect(blockers.textContent).toContain('仍需要责任人作出审批决定。');
    expect(blockers.textContent).toContain('当前决策所需的留存证据不完整。');
    expect(blockers.textContent).not.toContain('未识别的产品状态');
    expect(blockers.querySelector<HTMLDetailsElement>('details')?.open).toBe(false);
    expect(blockers.querySelector('code')?.textContent).toContain('DEPENDENCY_TIMEOUT');
  });

  it('replaces aggregate retry placeholders with exact server lifecycle observations', async () => {
    mockJobs.mockResolvedValue(jobPage([batchJob('job-terminal', 'PARTIAL')]));
    mockBatchWorkbook.mockResolvedValue(batchWorkbook());
    mockChildWorkbook.mockResolvedValue(childWorkbook());
    mockAttempts.mockResolvedValue({
      schemaVersion: 'resourceGateway.scenarioRehearsalBatchItemAttemptTimeline.v1',
      jobId: 'job-terminal',
      itemIndex: 0,
      maximumAttempts: 3,
      attemptsUsed: 2,
      attemptsRemaining: 1,
      deadlineAt: '2026-07-25T11:00:00Z',
      failureMode: 'COLLECT_ALL',
      historyComplete: true,
      authorTarget: {
        kind: 'GRAPH_DRAFT',
        id: 'answer-graph',
        label: 'Answer graph',
        draftId: 'answer-draft',
        revision: 7,
        sourceFingerprint: fingerprint('answer-source'),
        nodeId: 'grounding',
        scenarioId: 'golden-answer',
        runId: 'visual-run-44',
        owner: 'Knowledge Answers',
        requiredRole: 'Scenario author',
      },
      attempts: [{
        attempt: 1,
        state: 'RETRY_SCHEDULED',
        startedAt: '2026-07-25T10:00:01Z',
        observedAt: '2026-07-25T10:00:04Z',
        outcome: '',
        reasonCode: 'TARGET_TIMEOUT',
        claimSequence: 1,
        observationSequence: 2,
      }, {
        attempt: 2,
        state: 'TERMINAL',
        startedAt: '2026-07-25T10:00:09Z',
        observedAt: '2026-07-25T10:00:12Z',
        outcome: 'FAILED',
        reasonCode: 'TARGET_TIMEOUT',
        claimSequence: 3,
        observationSequence: 4,
      }],
    });

    await render();
    await waitFor(() => text().includes('Signed workbook'));
    await click('[data-testid="entry-0"]');
    await waitFor(() => query('[data-testid="rehearsal-attempts"]').textContent
      .includes('Exact lifecycle'));

    expect(mockAttempts).toHaveBeenCalledWith('job-terminal', 0);
    expect(query('[data-testid="rehearsal-attempts"]').textContent)
      .toContain('Retry scheduled');
    expect(query('[data-testid="rehearsal-attempts"]').textContent)
      .not.toContain('projection limit, not inferred');
    const authorLink = Array.from(document.querySelectorAll<HTMLAnchorElement>('a'))
      .find((link) => link.textContent?.includes('Open exact Author target'));
    expect(authorLink?.href).toContain('authorWorkspace=v2');
    expect(authorLink?.href).toContain('draftId=answer-draft');
    expect(authorLink?.href).toContain('nodeId=grounding');
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

  it('keeps an explicitly requested older page when the live selection changes', async () => {
    const pendingPage = deferred<ReturnType<typeof jobPage>>();
    mockJobs
      .mockResolvedValueOnce(jobPage(
        [
          batchJob('job-new', 'RUNNING'),
          batchJob('job-selected', 'RUNNING'),
        ],
        { createdAt: '2026-07-25T09:00:00Z', jobId: 'job-selected' },
      ))
      .mockReturnValueOnce(pendingPage.promise);
    mockItems.mockImplementation(async (jobId) => ({
      schemaVersion: 'resourceGateway.scenarioRehearsalBatchItemPage.v1',
      jobId,
      manifestFingerprint: fingerprint(`manifest-${jobId}`),
      items: [],
      nextIndex: null,
    }));

    await render();
    await waitFor(() => text().includes('Load older batches'));
    await clickText('Load older batches');
    await waitFor(() => mockJobs.mock.calls.length === 2);
    await click('[data-testid="batch-job-selected"]');

    pendingPage.resolve(jobPage([batchJob('job-old', 'SUCCEEDED')]));
    await waitFor(() => document.querySelector('[data-testid="batch-job-old"]') !== null);

    expect(query('[data-testid="batch-job-selected"]').getAttribute('aria-pressed')).toBe('true');
    expect(window.location.search).toContain('jobId=job-selected');
  });

  it('clears stale pagination busy state when live discovery refreshes', async () => {
    const pendingPage = deferred<ReturnType<typeof jobPage>>();
    const cursor = { createdAt: '2026-07-25T09:00:00Z', jobId: 'job-new' };
    mockJobs
      .mockResolvedValueOnce(jobPage([batchJob('job-new', 'RUNNING')], cursor))
      .mockReturnValueOnce(pendingPage.promise)
      .mockResolvedValueOnce(jobPage([batchJob('job-new', 'RUNNING')], cursor));
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
    expect(text()).toContain('Loading older batches...');

    await clickText('Refresh');
    await waitFor(() => mockJobs.mock.calls.length === 3);
    await waitFor(() => text().includes('Load older batches'));

    const loadOlder = Array.from(document.querySelectorAll('button'))
      .find((button) => button.textContent?.includes('Load older batches'));
    expect(loadOlder?.hasAttribute('disabled')).toBe(false);

    pendingPage.resolve(jobPage([batchJob('stale-job', 'SUCCEEDED')]));
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 1));
    });
    expect(document.querySelector('[data-testid="batch-stale-job"]')).toBeNull();
  });

  async function render(localized = false) {
    await act(async () => {
      root = createRoot(host);
      root.render(localized
        ? <I18nProvider><RehearsalWorkbench /></I18nProvider>
        : <RehearsalWorkbench />);
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

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((next) => {
    resolve = next;
  });
  return { promise, resolve };
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
