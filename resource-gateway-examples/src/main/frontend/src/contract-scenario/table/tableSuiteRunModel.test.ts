import { describe, expect, it } from 'vitest';

import { tableDrivenScenarioBaseline } from '../tableDrivenTestingBaseline';
import { contractDraftFromGraphDraft } from '../domain';
import { graphDraft } from '../testFixtures';
import {
  applyTableSuiteRunDelta,
  createTableSuiteBaselineSummary,
  createTableSuiteRunCommand,
  tableSuiteBatchIsCompleteBaseline,
  tableSuiteBatchTerminal,
  tableSuiteDifferentialCounts,
  tableSuiteEvidenceByCase,
  type TableSuiteRunBatch,
} from './tableSuiteRunModel';

describe('tableSuiteRunModel', () => {
  it('previews failed, changed, and affected cases from a payload-free complete baseline', () => {
    const draftSet = tableDrivenScenarioBaseline(5);
    const baseline = completeBaseline(draftSet, ['case-1']);
    const summary = createTableSuiteBaselineSummary(baseline, draftSet);
    const changedDraftSet = {
      ...draftSet,
      scenarios: draftSet.scenarios.map((scenario) => scenario.scenarioId === 'case-2'
        ? { ...scenario, name: 'Changed case 2' }
        : scenario),
    };

    expect(tableSuiteBatchIsCompleteBaseline(baseline)).toBe(true);
    expect(tableSuiteDifferentialCounts(draftSet, summary)).toEqual({
      failed: 1, changed: 0, affected: 1, targetChanged: false,
    });
    expect(tableSuiteDifferentialCounts(changedDraftSet, summary)).toEqual({
      failed: 1, changed: 1, affected: 2, targetChanged: false,
    });
    expect(createTableSuiteBaselineSummary({ ...baseline, status: 'CANCELLED' }, draftSet)).toBeNull();
  });

  it('creates a side-effect-free exact command and sends selected ids only for selected mode', () => {
    const draft = graphDraft();
    const scenarios = tableDrivenScenarioBaseline(5);
    const contract = contractDraftFromGraphDraft(draft, scenarios.target.fingerprint);

    const selected = createTableSuiteRunCommand(
      draft, contract, scenarios, 'SELECTED', ['case-4', 'case-2'], 'baseline-a',
    );
    const changed = createTableSuiteRunCommand(
      draft, contract, scenarios, 'CHANGED', ['case-4'], 'baseline-a',
    );

    expect(selected.selection).toEqual({ mode: 'SELECTED', caseIds: ['case-4', 'case-2'] });
    expect(selected.baselineBatchId).toBe('');
    expect(selected.preflight).toMatchObject({
      environment: scenarios.scope.environment,
      dependencyMode: 'SIMULATED',
      effectProfile: 'SIDE_EFFECT_FREE',
      maxConcurrency: 1,
      caseTimeoutMs: 10_000,
    });
    expect(changed.selection).toEqual({ mode: 'CHANGED', caseIds: [] });
    expect(changed.baselineBatchId).toBe('baseline-a');
  });

  it('applies incremental row events and keeps assertion failure distinct from execution failure', () => {
    const batch = queuedBatch();
    const failedRow = {
      ...batch.rows[0],
      status: 'ASSERTION_FAILED' as const,
      attempts: [{
        attempt: 1,
        status: 'ASSERTION_FAILED' as const,
        assertions: 'FAILED' as const,
        proofStrength: 'MOCK' as const,
        durationMs: 17,
        runFingerprint: fingerprint('e'),
        firstFailure: {
          category: 'ASSERTION',
          code: 'RG.TABLE_RUN.ASSERTION_MISMATCH',
          target: '/approved',
          summary: 'Expected and actual values differ.',
        },
        assertionEvidence: [],
        startedAt: '2026-08-04T10:00:00Z',
        completedAt: '2026-08-04T10:00:00Z',
      }],
      baseline: {
        baselineBatchId: 'baseline-a',
        baselineStatus: 'SUCCESS' as const,
        outcome: 'REGRESSED' as const,
      },
    };
    const next = applyTableSuiteRunDelta(batch, {
      schemaVersion: 'bloge.tableSuiteRunDelta.v1',
      batchId: batch.batchId,
      revision: 4,
      status: 'FAILED',
      counts: { ...batch.counts, queued: 0, failed: 1 },
      promotion: { eligible: false, reason: 'NON_SUCCESS_TERMINAL_STATUS' },
      resetRequired: false,
      events: [{
        revision: 4,
        type: 'ROW_TERMINAL',
        caseId: 'case-1',
        row: failedRow,
        observedAt: '2026-08-04T10:00:00Z',
      }],
    });
    const evidence = tableSuiteEvidenceByCase(next)['case-1'];

    expect(tableSuiteBatchTerminal(next)).toBe(true);
    expect(evidence).toMatchObject({
      execution: 'SUCCESS',
      assertions: 'FAILED',
      proofStrength: 'MOCK',
      attempt: 1,
      baselineOutcome: 'REGRESSED',
    });
    expect(JSON.stringify(evidence)).not.toContain('expectedBusinessValue');
  });
});

function queuedBatch(): TableSuiteRunBatch {
  const scenarios = tableDrivenScenarioBaseline(5);
  return {
    schemaVersion: 'bloge.tableSuiteRunBatch.v1',
    batchId: 'batch-a',
    requestId: 'request-a',
    requestFingerprint: fingerprint('a'),
    scope: scenarios.scope,
    target: scenarios.target,
    scenarioDraftSetId: scenarios.scenarioDraftSetId,
    scenarioDraftSetRevision: scenarios.revision,
    scenarioDraftSetFingerprint: fingerprint('b'),
    contractFingerprint: scenarios.contractFingerprint,
    selection: {
      mode: 'SELECTED',
      caseIds: ['case-1'],
      fingerprint: fingerprint('c'),
      fullSuite: false,
    },
    preflight: {
      environment: 'test',
      dependencyMode: 'SIMULATED',
      effectProfile: 'SIDE_EFFECT_FREE',
      maxCases: 500,
      maxFailures: 10,
      maxConcurrency: 1,
      caseTimeoutMs: 10_000,
    },
    baselineBatchId: 'baseline-a',
    status: 'RUNNING',
    revision: 2,
    cancelRequested: false,
    rows: [{
      caseId: 'case-1',
      caseFingerprint: fingerprint('d'),
      status: 'QUEUED',
      attempts: [],
      flaky: false,
      baseline: { baselineBatchId: 'baseline-a', baselineStatus: 'SUCCESS', outcome: 'SAME' },
    }],
    counts: { total: 1, queued: 1, running: 0, succeeded: 0, failed: 0, cancelled: 0, budgetStopped: 0 },
    promotion: { eligible: false, reason: 'PENDING' },
    events: [],
    createdAt: '2026-08-04T10:00:00Z',
    startedAt: '2026-08-04T10:00:00Z',
    completedAt: null,
  };
}

function completeBaseline(
  scenarios: ReturnType<typeof tableDrivenScenarioBaseline>,
  failedCaseIds: string[],
): TableSuiteRunBatch {
  const now = '2026-08-04T10:00:00Z';
  const rows = scenarios.scenarios.map((scenario) => {
    const failed = failedCaseIds.includes(scenario.scenarioId);
    return {
      caseId: scenario.scenarioId,
      caseFingerprint: fingerprint(scenario.scenarioId),
      status: failed ? 'ASSERTION_FAILED' as const : 'SUCCESS' as const,
      attempts: [],
      flaky: false,
      baseline: { baselineBatchId: '', baselineStatus: null, outcome: 'NONE' as const },
    };
  });
  return {
    ...queuedBatch(),
    batchId: 'baseline-complete',
    target: scenarios.target,
    scenarioDraftSetId: scenarios.scenarioDraftSetId,
    scenarioDraftSetRevision: scenarios.revision,
    contractFingerprint: scenarios.contractFingerprint,
    selection: {
      mode: 'ALL',
      caseIds: scenarios.scenarios.map((scenario) => scenario.scenarioId),
      fingerprint: fingerprint('z'),
      fullSuite: true,
    },
    status: failedCaseIds.length ? 'FAILED' : 'SUCCEEDED',
    rows,
    counts: {
      total: rows.length,
      queued: 0,
      running: 0,
      succeeded: rows.length - failedCaseIds.length,
      failed: failedCaseIds.length,
      cancelled: 0,
      budgetStopped: 0,
    },
    createdAt: now,
    startedAt: now,
    completedAt: now,
  };
}

function fingerprint(seed: string): string {
  return `sha256:${seed.repeat(64).slice(0, 64)}`;
}
