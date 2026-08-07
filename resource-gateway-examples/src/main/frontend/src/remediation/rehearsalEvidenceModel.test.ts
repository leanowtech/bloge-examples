import { describe, expect, it } from 'vitest';

import type { ScenarioRehearsalBatchJob } from '../types';
import { rehearsalEvidencePresentation } from './rehearsalEvidenceModel';

describe('rehearsalEvidencePresentation', () => {
  it('shows a bounded timeout timeline without inventing discarded attempt observations', () => {
    const view = rehearsalEvidencePresentation({
      index: 0,
      id: 'retrieval-timeout',
      status: 'FAILED',
      attemptCount: 2,
      runId: 'run-timeout',
      failureCode: 'DEPENDENCY_TIMEOUT',
      planId: 'retrieval-plan',
      planRevision: 3,
      childWorkbook: null,
      startedAt: '2026-07-25T10:01:00Z',
      completedAt: '2026-07-25T10:02:00Z',
    }, job(), {
      category: 'EXECUTION',
      reason: 'DEPENDENCY_TIMEOUT',
    });

    expect(view).toEqual(expect.objectContaining({
      headline: 'Dependency timed out',
      verdictLabel: 'EXECUTION FAILED',
      verdictTone: 'danger',
      attemptsUsed: 2,
      attemptsMaximum: 3,
      attemptsRemaining: 1,
      batchFallback: 'Continue the batch and collect every item outcome.',
      attemptsExact: false,
      itemFallback: expect.stringContaining('No per-item fallback'),
      lastObservation: 'Dependency timeout.',
    }));
    expect(view.timeline).toEqual([
      expect.objectContaining({
        attempt: 1,
        state: 'RETRY SCHEDULED',
        exact: false,
        observation: expect.stringContaining('does not retain'),
      }),
      expect.objectContaining({
        attempt: 2,
        state: 'TERMINAL',
        exact: true,
        observation: 'Dependency timeout.',
      }),
    ]);
    expect(view.action).toEqual(expect.objectContaining({
      source: 'REHEARSAL_TIMEOUT',
      available: false,
      actionLabel: 'Request controlled retry',
    }));
  });

  it('uses exact server lifecycle facts instead of inferring earlier attempts', () => {
    const view = rehearsalEvidencePresentation({
      index: 0,
      id: 'retrieval-timeout',
      status: 'FAILED',
      attemptCount: 2,
      runId: 'run-timeout',
      failureCode: 'DEPENDENCY_TIMEOUT',
      planId: 'retrieval-plan',
      planRevision: 3,
      childWorkbook: null,
      startedAt: null,
      completedAt: null,
    }, job(), {
      category: 'EXECUTION',
      reason: 'DEPENDENCY_TIMEOUT',
    }, {
      exactAttempts: {
        schemaVersion: 'resourceGateway.scenarioRehearsalBatchItemAttemptTimeline.v1',
        jobId: 'job-1',
        itemIndex: 0,
        maximumAttempts: 3,
        attemptsUsed: 2,
        attemptsRemaining: 1,
        deadlineAt: '2026-07-25T11:00:00Z',
        failureMode: 'COLLECT_ALL',
        historyComplete: true,
        authorTarget: null,
        attempts: [
          {
            attempt: 1,
            state: 'RETRY_SCHEDULED',
            startedAt: '2026-07-25T10:00:01Z',
            observedAt: '2026-07-25T10:00:04Z',
            outcome: '',
            reasonCode: 'DEPENDENCY_TIMEOUT',
            claimSequence: 1,
            observationSequence: 2,
          },
          {
            attempt: 2,
            state: 'TERMINAL',
            startedAt: '2026-07-25T10:00:09Z',
            observedAt: '2026-07-25T10:00:12Z',
            outcome: 'FAILED',
            reasonCode: 'DEPENDENCY_TIMEOUT',
            claimSequence: 3,
            observationSequence: 4,
          },
        ],
      },
    });

    expect(view.attemptsExact).toBe(true);
    expect(view.batchFallback).toContain('collect every item outcome');
    expect(view.timeline).toEqual([
      expect.objectContaining({
        attempt: 1,
        state: 'RETRY SCHEDULED',
        observedAt: '2026-07-25T10:00:04Z',
        exact: true,
      }),
      expect.objectContaining({
        attempt: 2,
        state: 'TERMINAL',
        observedAt: '2026-07-25T10:00:12Z',
        exact: true,
      }),
    ]);
  });

  it('creates a one-hop Author handoff only from an exact advertised target', () => {
    const view = rehearsalEvidencePresentation({
      index: 1,
      id: 'grounding',
      status: 'FAILED',
      attemptCount: 1,
      runId: 'run-grounding',
      failureCode: 'ASSERTION_FAILED',
      planId: 'grounding-plan',
      planRevision: 4,
      childWorkbook: null,
      startedAt: '2026-07-25T10:01:00Z',
      completedAt: '2026-07-25T10:02:00Z',
      authorTarget: {
        kind: 'GRAPH_DRAFT',
        id: 'answer-graph',
        label: 'Answer graph',
        draftId: 'answer-draft',
        revision: 7,
        nodeId: 'grounding',
        scenarioId: 'golden-answer',
        runId: 'visual-run-44',
        owner: 'Knowledge Answers',
        requiredRole: 'Scenario author',
      },
    }, job(), {
      category: 'ASSERTIONS',
      reason: 'Assertion failed',
    }, {
      currentHref: 'http://localhost:18080/rehearsals/',
    });

    expect(view.action).toEqual(expect.objectContaining({
      actionKind: 'OPEN_AUTHOR_TARGET',
      navigation: 'AUTHOR',
      available: true,
      owner: 'Knowledge Answers',
      deepLink: expect.stringContaining('draftId=answer-draft'),
    }));
    expect(view.action?.deepLink).toContain('nodeId=grounding');
    expect(view.action?.deepLink).toContain('scenarioId=golden-answer');
    expect(view.action?.deepLink).toContain('runId=visual-run-44');
  });

  it('disables an otherwise exact handoff for illustrative samples', () => {
    const view = rehearsalEvidencePresentation({
      index: 1,
      id: 'sample',
      status: 'FAILED',
      attemptCount: 1,
      runId: 'sample-run',
      failureCode: 'ASSERTION_FAILED',
      planId: 'sample-plan',
      planRevision: 1,
      childWorkbook: null,
      startedAt: null,
      completedAt: null,
      authorTarget: {
        kind: 'GRAPH_DRAFT',
        id: 'sample-graph',
        label: 'Sample graph',
        draftId: 'sample-draft',
      },
    }, job(), {
      category: 'ASSERTIONS',
      reason: 'Assertion failed',
    }, {
      sampleMode: true,
    });

    expect(view.action).toEqual(expect.objectContaining({
      available: false,
      unavailableReason: expect.stringContaining('Illustrative samples'),
    }));
  });

  it('exposes only a local executable retry for illustrative timeout samples', () => {
    const view = rehearsalEvidencePresentation({
      index: 0,
      id: 'sample-timeout',
      status: 'FAILED',
      attemptCount: 2,
      runId: 'sample-run',
      failureCode: 'DEPENDENCY_TIMEOUT',
      planId: 'sample-plan',
      planRevision: 1,
      childWorkbook: null,
      startedAt: null,
      completedAt: null,
    }, job(), {
      category: 'EXECUTION',
      reason: 'DEPENDENCY_TIMEOUT',
    }, {
      sampleMode: true,
    });

    expect(view.action).toEqual(expect.objectContaining({
      actionKind: 'RETRY_REHEARSAL',
      actionLabel: 'Run sample retry',
      navigation: 'DIAGNOSTIC',
      available: true,
      deepLink: '',
      unavailableReason: '',
    }));
  });
});

function job(): ScenarioRehearsalBatchJob {
  return {
    schemaVersion: 'resourceGateway.scenarioRehearsalBatchJob.v2',
    jobId: 'job-1',
    requestId: 'request-1',
    requestFingerprint: fingerprint('r'),
    manifestFingerprint: fingerprint('m'),
    scope: {
      tenantId: 'tenant-a',
      organizationId: 'knowledge',
      projectId: 'answer-service',
      environmentId: 'test',
      region: 'sg',
    },
    status: 'PARTIAL',
    failureMode: 'CONTINUE',
    priority: 'NORMAL',
    maximumItemAttempts: 3,
    summary: {
      totalItems: 1,
      completedItems: 1,
      passedItems: 0,
      failedItems: 1,
      indeterminateItems: 0,
      cancelledItems: 0,
    },
    deadlineAt: '2026-07-25T11:00:00Z',
    failureCode: '',
    createdAt: '2026-07-25T10:00:00Z',
    updatedAt: '2026-07-25T10:02:00Z',
    completedAt: '2026-07-25T10:02:00Z',
    recordFingerprint: fingerprint('j'),
  };
}

function fingerprint(seed: string): string {
  return `sha256:${seed.repeat(64).slice(0, 64)}`;
}
