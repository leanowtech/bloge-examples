// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  decideScenarioRehearsalRemediation,
  fetchScenarioRehearsalRemediationComparison,
  fetchScenarioRehearsalRemediationLineage,
  getRehearsalRemediationCredentialStatus,
  previewScenarioRehearsalRemediation,
  submitScenarioRehearsalRemediation,
} from './api';
import RehearsalRemediationPanel from './RehearsalRemediationPanel';
import type {
  ScenarioRehearsalBatchWorkbookSeed,
  ScenarioRehearsalRemediationApproval,
  ScenarioRehearsalRemediationComparison,
  ScenarioRehearsalRemediationPlan,
  ScenarioRehearsalRemediationReceipt,
} from './types';

vi.mock('./api', () => ({
  decideScenarioRehearsalRemediation: vi.fn(),
  fetchScenarioRehearsalRemediationComparison: vi.fn(),
  fetchScenarioRehearsalRemediationLineage: vi.fn(),
  getRehearsalRemediationCredentialStatus: vi.fn(),
  previewScenarioRehearsalRemediation: vi.fn(),
  submitScenarioRehearsalRemediation: vi.fn(),
}));

const mockCredentials = vi.mocked(getRehearsalRemediationCredentialStatus);
const mockPreview = vi.mocked(previewScenarioRehearsalRemediation);
const mockDecide = vi.mocked(decideScenarioRehearsalRemediation);
const mockSubmit = vi.mocked(submitScenarioRehearsalRemediation);
const mockComparison = vi.mocked(fetchScenarioRehearsalRemediationComparison);
const mockLineage = vi.mocked(fetchScenarioRehearsalRemediationLineage);

describe('RehearsalRemediationPanel', () => {
  let root: Root | null = null;
  let host: HTMLDivElement;
  let onRemediationIdChange: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    host = document.createElement('div');
    document.body.appendChild(host);
    onRemediationIdChange = vi.fn();
    vi.clearAllMocks();
    mockCredentials.mockImplementation((slot) => ({
      slot,
      configured: false,
      principalLabel: '',
      expiresAt: '',
    }));
  });

  afterEach(async () => {
    if (root) {
      await act(async () => root?.unmount());
      root = null;
    }
    host.remove();
  });

  it('keeps mutation disabled and performs no transport when human credentials are absent', async () => {
    await render();
    await changeLabel('Governance ticket ID', 'ANEKE-4821');
    await changeLabel('Governance ticket fingerprint', fingerprint('ticket'));

    const freeze = button('Freeze for review');
    expect(freeze.disabled).toBe(true);
    expect(text()).toContain('Connect a short-lived Owner identity');
    expect(text()).toContain('Not connected');
    expect(mockPreview).not.toHaveBeenCalled();
    expect(mockDecide).not.toHaveBeenCalled();
    expect(mockSubmit).not.toHaveBeenCalled();

    enableCredentials();
    await clickText('Refresh identities');
    await waitFor(() => text().includes('owner@example.test'));
    expect(button('Freeze for review').disabled).toBe(false);
  });

  it('carries exact CAS coordinates through Owner approval, independent review, submission, and comparison', async () => {
    enableCredentials();
    const ticketRef = artifact('GOVERNANCE_REVIEW_TICKET', 'ANEKE-4821');
    ticketRef.fingerprint = fingerprint('ticket');
    const frozenPlan = {
      ...remediationPlan(),
      governanceTicketRef: ticketRef,
    };
    const owner = {
      ...approval('OWNER', 1, '', 'owner@example.test'),
      governanceTicketRef: ticketRef,
    };
    const reviewer = {
      ...approval(
      'INDEPENDENT_REVIEWER',
      2,
      owner.approvalFingerprint,
      'reviewer@example.test',
      ),
      governanceTicketRef: ticketRef,
    };
    const accepted = receipt(reviewer.approvalFingerprint);
    mockPreview.mockImplementation(async (_jobId, request) => ({
      ...frozenPlan,
      previewRequestId: request.previewRequestId,
    }));
    mockDecide
      .mockResolvedValueOnce(owner)
      .mockResolvedValueOnce(reviewer);
    mockSubmit.mockResolvedValue(accepted);
    mockComparison.mockResolvedValue(comparison());

    await render();
    await changeLabel('Governance ticket ID', 'ANEKE-4821');
    await changeLabel('Governance ticket fingerprint', fingerprint('ticket'));
    await clickText('Freeze for review');
    await waitFor(() => text().includes('Owner approval is the next required fact'));

    expect(mockPreview).toHaveBeenCalledWith(workbook().jobId, expect.objectContaining({
      expectedWorkbookSeedFingerprint: workbook().seedFingerprint,
      strategy: 'RERUN_EXACT',
      replacements: [],
      governanceTicketRef: {
        kind: 'GOVERNANCE_REVIEW_TICKET',
        id: 'ANEKE-4821',
        revision: 1,
        fingerprint: fingerprint('ticket'),
      },
      reasonCode: 'TRANSIENT_EXECUTION_RECHECK',
    }));
    expect(onRemediationIdChange).toHaveBeenCalledWith(frozenPlan.remediationId);

    await clickText('Approve reviewed plan');
    await waitFor(() => text().includes('Owner approval was appended'));
    expect(mockDecide).toHaveBeenNthCalledWith(1, frozenPlan.remediationId, expect.objectContaining({
      remediationPlanFingerprint: frozenPlan.planFingerprint,
      expectedApprovalGeneration: 0,
      role: 'OWNER',
      decision: 'APPROVE',
      governanceTicketRef: frozenPlan.governanceTicketRef,
    }));

    await clickText('Approve reviewed plan');
    await waitFor(() => text().includes('Two-person approval complete'));
    expect(mockDecide).toHaveBeenNthCalledWith(2, frozenPlan.remediationId, expect.objectContaining({
      expectedApprovalGeneration: 1,
      role: 'INDEPENDENT_REVIEWER',
      decision: 'APPROVE',
    }));

    await clickText('Admit successor');
    await waitFor(() => document.querySelector('[data-testid="successor-receipt"]') !== null);
    expect(mockSubmit).toHaveBeenCalledWith(frozenPlan.remediationId, expect.objectContaining({
      remediationPlanFingerprint: frozenPlan.planFingerprint,
      expectedApprovalGeneration: 2,
      expectedApprovalHeadFingerprint: reviewer.approvalFingerprint,
      reasonCode: 'APPROVALS_COMPLETE',
    }));

    await clickText('Compare signed evidence');
    await waitFor(() => document.querySelector('[data-testid="remediation-comparison"]') !== null);

    expect(mockComparison).toHaveBeenCalledWith(frozenPlan.remediationId, 'READ');
    expect(query('[data-testid="remediation-comparison"]').textContent).toContain('RESOLVED');
    expect(query('[data-testid="remediation-comparison"]').textContent).toContain('BLOCKER_ASSERTION_FAILED');
    expect(query('[data-testid="approval-ledger"]').textContent).toContain('owner@example.test');
    expect(query('[data-testid="approval-ledger"]').textContent).toContain('reviewer@example.test');
  });

  it('builds an exact selected-entry plan replacement and rejects a no-op proposal', async () => {
    enableCredentials();
    mockPreview.mockImplementation(async (_jobId, request) => ({
      ...remediationPlan('REPLACE_COMPILED_PLANS'),
      previewRequestId: request.previewRequestId,
      governanceTicketRef: request.governanceTicketRef,
    }));

    await render();
    await clickText('Replace plans');
    await changeLabel('Governance ticket ID', 'ANEKE-5900');
    await changeLabel('Governance ticket fingerprint', fingerprint('ticket-2'));
    await checkLabel('Replace entry-0');

    expect(button('Freeze for review').disabled).toBe(true);
    expect(text()).toContain('at least one changed plan reference');

    await changeLabel('Replacement plan ID for entry-0', 'plan-0-revised');
    await changeLabel('Replacement plan revision for entry-0', '2');
    await changeLabel('Replacement plan fingerprint for entry-0', fingerprint('plan-0-revised'));
    expect(button('Freeze for review').disabled).toBe(false);

    await clickText('Freeze for review');
    await waitFor(() => mockPreview.mock.calls.length === 1);

    const request = mockPreview.mock.calls[0][1];
    expect(request.strategy).toBe('REPLACE_COMPILED_PLANS');
    expect(request.reasonCode).toBe('SCENARIO_REVISION');
    expect(request.replacements).toEqual([{
      entryIndex: 0,
      entryId: 'entry-0',
      expectedCompiledPlanRef: {
        kind: 'COMPILED_REHEARSAL_PLAN',
        id: 'plan-0',
        revision: 1,
        fingerprint: fingerprint('plan-0'),
      },
      replacementCompiledPlanRef: {
        kind: 'COMPILED_REHEARSAL_PLAN',
        id: 'plan-0-revised',
        revision: 2,
        fingerprint: fingerprint('plan-0-revised'),
      },
    }]);
  });

  it('retains a rejected decision as terminal history and never offers successor submission', async () => {
    enableCredentials();
    const frozenPlan = remediationPlan();
    mockPreview.mockImplementation(async (_jobId, request) => ({
      ...frozenPlan,
      previewRequestId: request.previewRequestId,
      governanceTicketRef: request.governanceTicketRef,
    }));
    mockDecide.mockResolvedValue({
      ...approval('OWNER', 1, '', 'owner@example.test'),
      decision: 'REJECT',
      governanceTicketRef: {
        ...artifact('GOVERNANCE_REVIEW_TICKET', 'ANEKE-4821'),
        fingerprint: fingerprint('ticket'),
      },
      reasonCode: 'REJECTED_POLICY_CONFLICT',
    });

    await render();
    await changeLabel('Governance ticket ID', 'ANEKE-4821');
    await changeLabel('Governance ticket fingerprint', fingerprint('ticket'));
    await clickText('Freeze for review');
    await waitFor(() => text().includes('Owner approval is the next required fact'));
    await changeLabel('Rejection reason', 'REJECTED_POLICY_CONFLICT');
    await clickText('Reject');
    await waitFor(() => text().includes('Immutable rejection retained'));

    expect(query('[data-testid="remediation-state"]').textContent).toContain('REJECTED');
    expect(mockDecide).toHaveBeenCalledWith(frozenPlan.remediationId, expect.objectContaining({
      expectedApprovalGeneration: 0,
      role: 'OWNER',
      decision: 'REJECT',
      reasonCode: 'REJECTED_POLICY_CONFLICT',
    }));
    expect(Array.from(document.querySelectorAll('button'))
      .some((candidate) => candidate.textContent?.includes('Admit successor'))).toBe(false);
    expect(mockSubmit).not.toHaveBeenCalled();
  });

  it('restores a deep-linked immutable lineage with the read identity and no mutation', async () => {
    enableCredentials();
    const frozenPlan = remediationPlan();
    const owner = approval('OWNER', 1, '', 'owner@example.test');
    mockLineage.mockResolvedValue({
      schemaVersion: 'resourceGateway.scenarioRehearsalRemediationLineage.v1',
      lineageFingerprint: fingerprint('lineage'),
      state: 'PENDING_APPROVAL',
      plan: frozenPlan,
      approvals: [owner],
      approvalGeneration: 1,
      approvalHeadFingerprint: owner.approvalFingerprint,
      receipt: null,
    });

    await render(frozenPlan.remediationId);
    await waitFor(() => document.querySelector('[data-testid="approval-ledger"]')
      ?.textContent?.includes('owner@example.test') ?? false);

    expect(mockLineage).toHaveBeenCalledWith(frozenPlan.remediationId, 'READ');
    expect(query('[data-testid="approval-ledger"]').textContent).toContain('owner@example.test');
    expect(mockPreview).not.toHaveBeenCalled();
    expect(mockDecide).not.toHaveBeenCalled();
  });

  async function render(initialRemediationId = '') {
    await act(async () => {
      root = createRoot(host);
      root.render(
        <RehearsalRemediationPanel
          workbook={workbook()}
          initialRemediationId={initialRemediationId}
          onRemediationIdChange={onRemediationIdChange}
        />,
      );
    });
  }
});

function enableCredentials() {
  mockCredentials.mockImplementation((slot) => ({
    slot,
    configured: true,
    principalLabel: slot === 'OWNER'
      ? 'owner@example.test'
      : slot === 'INDEPENDENT_REVIEWER'
        ? 'reviewer@example.test'
        : 'governance-reader@example.test',
    expiresAt: '2099-08-01T00:00:00Z',
  }));
}

function workbook(): ScenarioRehearsalBatchWorkbookSeed {
  const scope = {
    tenantId: 'tenant-a',
    organizationId: 'knowledge-governance',
    projectId: 'tool-studio',
    environmentId: 'test',
    region: 'sg',
  };
  return {
    schemaVersion: 'resourceGateway.scenarioRehearsalBatchWorkbookSeed.v1',
    seedFingerprint: fingerprint('root-seed'),
    scope,
    jobId: batchId('predecessor'),
    requestId: 'request-predecessor',
    requestFingerprint: fingerprint('request-predecessor'),
    manifestFingerprint: fingerprint('manifest-predecessor'),
    terminalJobFingerprint: fingerprint('terminal-predecessor'),
    evidenceBundleFingerprint: fingerprint('evidence-predecessor'),
    evidenceIndexFingerprint: fingerprint('index-predecessor'),
    evidenceKeyId: 'evidence-key-1',
    workbookSeal: {
      keyId: 'evidence-key-1',
      algorithm: 'Ed25519',
      materialFingerprint: fingerprint('workbook-material'),
      signature: 'base64:signature',
    },
    retentionProof: {
      eventFingerprint: fingerprint('retention'),
      retainUntil: '2033-07-25T10:00:00Z',
    },
    status: 'PARTIAL',
    summary: {
      totalItems: 2,
      completedItems: 2,
      passedItems: 1,
      failedItems: 1,
      indeterminateItems: 0,
      cancelledItems: 0,
    },
    entries: [0, 1].map((index) => ({
      entryIndex: index,
      entryId: `entry-${index}`,
      compiledPlanRef: artifact('COMPILED_REHEARSAL_PLAN', `plan-${index}`),
      childRequestId: `child-${index}`,
      expectedRunId: `run-${index}`,
      status: index === 0 ? 'FAILED' as const : 'PASSED' as const,
      attemptCount: 1,
      runId: `run-${index}`,
      childEvidenceBundleFingerprint: fingerprint(`child-evidence-${index}`),
      childWorkbookSeedFingerprint: index === 0 ? '' : fingerprint(`child-workbook-${index}`),
      failureCode: index === 0 ? 'TARGET_TIMEOUT' : '',
      childWorkbook: null,
    })),
    gateReady: false,
    blockers: ['BLOCKER_ASSERTION_FAILED'],
  };
}

function remediationPlan(
  strategy: 'RERUN_EXACT' | 'REPLACE_COMPILED_PLANS' = 'RERUN_EXACT',
): ScenarioRehearsalRemediationPlan {
  const source = workbook();
  const remediationId = `scenario-remediation-${'a'.repeat(64)}`;
  const replacements = strategy === 'REPLACE_COMPILED_PLANS'
    ? [{
      entryIndex: 0,
      entryId: 'entry-0',
      expectedCompiledPlanRef: artifact('COMPILED_REHEARSAL_PLAN', 'plan-0'),
      replacementCompiledPlanRef: {
        ...artifact('COMPILED_REHEARSAL_PLAN', 'plan-0-revised'),
        revision: 2,
      },
    }]
    : [];
  return {
    schemaVersion: 'resourceGateway.scenarioRehearsalRemediationPlan.v1',
    planFingerprint: fingerprint('remediation-plan'),
    scope: source.scope,
    remediationId,
    previewRequestId: 'preview-request-1',
    predecessorJobId: source.jobId,
    predecessorWorkbookSeedFingerprint: source.seedFingerprint,
    predecessorEvidenceBundleFingerprint: source.evidenceBundleFingerprint,
    predecessorStatus: source.status,
    predecessorBlockers: source.blockers,
    strategy,
    reasonCode: strategy === 'RERUN_EXACT' ? 'TRANSIENT_EXECUTION_RECHECK' : 'SCENARIO_REVISION',
    replacements,
    successorRequest: {
      requestId: remediationId,
      entries: source.entries.map((entry) => ({
        entryId: entry.entryId,
        compiledPlanRef: entry.compiledPlanRef,
      })),
    },
    successorRequestFingerprint: fingerprint('successor-request'),
    governanceTicketRef: artifact('GOVERNANCE_REVIEW_TICKET', 'ANEKE-4821'),
    approvalPolicy: {
      requiredRoles: ['OWNER', 'INDEPENDENT_REVIEWER'],
      minimumDistinctActors: 2,
      serverPolicyGeneration: 1,
      serverPolicyFingerprint: fingerprint('approval-policy'),
    },
    generatedAt: '2026-07-25T10:00:00Z',
    expiresAt: '2099-07-25T11:00:00Z',
  };
}

function approval(
  role: 'OWNER' | 'INDEPENDENT_REVIEWER',
  generation: number,
  previousApprovalFingerprint: string,
  actorId: string,
): ScenarioRehearsalRemediationApproval {
  const plan = remediationPlan();
  return {
    schemaVersion: 'resourceGateway.scenarioRehearsalRemediationApproval.v1',
    approvalFingerprint: fingerprint(`approval-${generation}`),
    sourceCommandFingerprint: fingerprint(`command-${generation}`),
    scope: plan.scope,
    remediationId: plan.remediationId,
    remediationPlanFingerprint: plan.planFingerprint,
    generation,
    previousApprovalFingerprint,
    role,
    decision: 'APPROVE',
    governanceTicketRef: plan.governanceTicketRef,
    reasonCode: 'APPROVED_AS_REVIEWED',
    actorId,
    delegatedBy: '',
    decidedAt: `2026-07-25T10:0${generation}:00Z`,
  };
}

function receipt(approvalHeadFingerprint: string): ScenarioRehearsalRemediationReceipt {
  const plan = remediationPlan();
  return {
    schemaVersion: 'resourceGateway.scenarioRehearsalRemediationReceipt.v1',
    receiptFingerprint: fingerprint('receipt'),
    sourceCommandFingerprint: fingerprint('submit-command'),
    scope: plan.scope,
    remediationId: plan.remediationId,
    remediationPlanFingerprint: plan.planFingerprint,
    predecessorJobId: plan.predecessorJobId,
    successorJobId: batchId('successor'),
    successorRequestFingerprint: plan.successorRequestFingerprint,
    approvalGeneration: 2,
    approvalHeadFingerprint,
    acceptedBy: 'owner@example.test',
    delegatedBy: '',
    acceptedAt: '2026-07-25T10:03:00Z',
  };
}

function comparison(): ScenarioRehearsalRemediationComparison {
  const plan = remediationPlan();
  const accepted = receipt(fingerprint('approval-2'));
  const before = comparisonSnapshot(plan.predecessorJobId, false, ['BLOCKER_ASSERTION_FAILED'], 1, 1);
  const after = comparisonSnapshot(accepted.successorJobId, true, [], 2, 0);
  return {
    schemaVersion: 'resourceGateway.scenarioRehearsalRemediationComparison.v1',
    comparisonFingerprint: fingerprint('comparison'),
    scope: plan.scope,
    remediationId: plan.remediationId,
    lineageFingerprint: fingerprint('lineage'),
    remediationPlanFingerprint: plan.planFingerprint,
    receiptFingerprint: accepted.receiptFingerprint,
    predecessor: before,
    successor: after,
    gateTransition: 'RESOLVED',
    resolvedBlockers: ['BLOCKER_ASSERTION_FAILED'],
    remainingBlockers: [],
    introducedBlockers: [],
    entries: [0, 1].map((index) => ({
      entryIndex: index,
      entryId: `entry-${index}`,
      planChanged: false,
      gateTransition: index === 0 ? 'RESOLVED' as const : 'STILL_READY' as const,
      resolvedBlockers: index === 0 ? ['BLOCKER_ASSERTION_FAILED'] : [],
      remainingBlockers: [],
      introducedBlockers: [],
      predecessor: comparisonEntry(index, index !== 0, index === 0 ? ['BLOCKER_ASSERTION_FAILED'] : []),
      successor: comparisonEntry(index, true, []),
    })),
  };
}

function comparisonSnapshot(
  jobId: string,
  gateReady: boolean,
  blockers: string[],
  passedItems: number,
  failedItems: number,
): ScenarioRehearsalRemediationComparison['predecessor'] {
  return {
    workbookSchemaVersion: 'resourceGateway.scenarioRehearsalBatchWorkbookSeed.v1',
    scope: workbook().scope,
    jobId,
    seedFingerprint: fingerprint(`seed-${jobId}`),
    requestFingerprint: fingerprint(`request-${jobId}`),
    manifestFingerprint: fingerprint(`manifest-${jobId}`),
    evidenceBundleFingerprint: fingerprint(`evidence-${jobId}`),
    evidenceIndexFingerprint: fingerprint(`index-${jobId}`),
    workbookSeal: workbook().workbookSeal,
    status: gateReady ? 'SUCCEEDED' : 'PARTIAL',
    summary: {
      totalItems: 2,
      completedItems: 2,
      passedItems,
      failedItems,
      indeterminateItems: 0,
      cancelledItems: 0,
    },
    correctnessSummary: {
      evidenceBackedEntries: 2,
      totalCases: 2,
      passedCases: passedItems,
      failedCases: failedItems,
      indeterminateCases: 0,
      assertionResults: 2,
      blockerFailures: failedItems,
      blockerIndeterminate: 0,
      warningFailures: 0,
      warningIndeterminate: 0,
    },
    gateReady,
    blockers,
  };
}

function comparisonEntry(
  index: number,
  gateReady: boolean,
  blockers: string[],
): ScenarioRehearsalRemediationComparison['entries'][number]['predecessor'] {
  return {
    compiledPlanRef: artifact('COMPILED_REHEARSAL_PLAN', `plan-${index}`),
    status: gateReady ? 'PASSED' : 'FAILED',
    failureCode: gateReady ? '' : 'TARGET_TIMEOUT',
    runId: `run-${index}`,
    childEvidenceBundleFingerprint: fingerprint(`child-evidence-${index}`),
    childWorkbookSeedFingerprint: fingerprint(`child-workbook-${index}`),
    scenarioPackRef: artifact('SCENARIO_PACK', `pack-${index}`),
    targetCapabilityRef: artifact('CAPABILITY', `capability-${index}`),
    outcome: gateReady ? 'PASS' : 'FAIL',
    summary: {
      totalCases: 1,
      passedCases: gateReady ? 1 : 0,
      failedCases: gateReady ? 0 : 1,
      indeterminateCases: 0,
      assertionResults: 1,
      blockerFailures: gateReady ? 0 : 1,
      blockerIndeterminate: 0,
      warningFailures: 0,
      warningIndeterminate: 0,
    },
    gateReady,
    blockers,
  };
}

function artifact(kind: string, id: string) {
  return {
    kind,
    id,
    revision: 1,
    fingerprint: fingerprint(id),
  };
}

function fingerprint(seed: string): string {
  const material = seed.replace(/[^a-f0-9]/g, 'a') || 'a';
  return `sha256:${material.padEnd(64, material[0]).slice(0, 64)}`;
}

function batchId(seed: string): string {
  return `scenario-batch-${fingerprint(seed).slice(7)}`;
}

async function waitFor(predicate: () => boolean) {
  for (let attempt = 0; attempt < 60; attempt += 1) {
    if (predicate()) {
      return;
    }
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 1));
    });
  }
  throw new Error(`Condition was not met. Current body: ${document.body.textContent}`);
}

async function changeLabel(label: string, value: string) {
  const element = document.querySelector<HTMLInputElement | HTMLSelectElement>(
    `[aria-label="${label}"]`,
  );
  if (!element) {
    throw new Error(`Missing field: ${label}`);
  }
  await act(async () => {
    const setter = Object.getOwnPropertyDescriptor(
      element instanceof HTMLInputElement ? HTMLInputElement.prototype : HTMLSelectElement.prototype,
      'value',
    )?.set;
    setter?.call(element, value);
    element.dispatchEvent(new Event('input', { bubbles: true }));
    element.dispatchEvent(new Event('change', { bubbles: true }));
  });
}

async function checkLabel(label: string) {
  const element = document.querySelector<HTMLInputElement>(`[aria-label="${label}"]`);
  if (!element) {
    throw new Error(`Missing checkbox: ${label}`);
  }
  await act(async () => {
    element.click();
  });
}

async function clickText(label: string) {
  const element = button(label);
  await act(async () => {
    element.click();
  });
}

function button(label: string): HTMLButtonElement {
  const element = Array.from(document.querySelectorAll('button'))
    .find((candidate) => candidate.textContent?.includes(label));
  if (!element) {
    throw new Error(`Missing button containing: ${label}`);
  }
  return element;
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
