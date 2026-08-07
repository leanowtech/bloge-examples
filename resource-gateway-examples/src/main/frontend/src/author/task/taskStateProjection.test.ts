import { describe, expect, it } from 'vitest';

import { projectAuthorTaskState, type AuthorTaskStateInput } from './taskStateProjection';

describe('projectAuthorTaskState', () => {
  it('offers one sandbox run command for a complete ephemeral Scenario', () => {
    const state = projectAuthorTaskState(input());

    expect(state).toMatchObject({
      canonicalState: 'RUNNABLE',
      currentness: 'NOT_RUN',
      proofStrength: 'EXPLORATORY',
      primaryAction: { kind: 'run', label: 'Run scenario' },
      primaryCommand: { state: 'READY', enabled: true },
    });
    expect(state.primaryCommand).toBe(state.commands.runCurrentScenario);
    expect(state.primaryCommand).toMatchObject({
      owner: 'GLOBAL',
      scope: { kind: 'CASE', count: 1, targetIds: ['case-1'] },
    });
  });

  it('hands formal task surfaces their scoped command instead of exposing a hidden header run', () => {
    const state = projectAuthorTaskState(input({ activeMode: 'scenarios' }));

    expect(state.primaryCommand).toMatchObject({
      owner: 'TASK_SURFACE',
      scope: {
        kind: 'CASE',
        count: 1,
        targetIds: ['case-1'],
        fingerprint: 'sha256:scenario',
      },
    });
  });

  it('blocks every run surface with one stale-coordinate reason and remediation', () => {
    const state = projectAuthorTaskState(input({
      canonicalScenarioReady: false,
    }));

    expect(state.canonicalState).toBe('BLOCKED');
    expect(state.commands.runCurrentScenario).toMatchObject({
      state: 'BLOCKED',
      enabled: false,
      reasonCode: 'RG.AUTHOR.RUN.SCENARIO_STALE',
      remediation: { label: 'Review compatibility', mode: 'contract' },
    });
    expect(state.blockingReasons).toHaveLength(1);
  });

  it('distinguishes coordinate preparation from a stale durable asset', () => {
    const state = projectAuthorTaskState(input({
      canonicalScenarioReady: false,
      coordinate: {
        ...coordinate(),
        scenarioFingerprint: '',
      },
    }));

    expect(state).toMatchObject({
      canonicalState: 'PREPARING',
      commands: {
        runCurrentScenario: {
          reasonCode: 'RG.AUTHOR.RUN.COORDINATE_PREPARING',
          remediation: { label: 'Open Scenarios' },
        },
      },
    });
  });

  it('keeps running state and label identical even when older evidence exists', () => {
    const state = projectAuthorTaskState(input({
      busy: true,
      hasRunResult: true,
      runSuccessful: true,
    }));

    expect(state.primaryAction).toMatchObject({ kind: 'run', label: 'Running scenario...' });
    expect(state.primaryCommand).toEqual(state.commands.runCurrentScenario);
    expect(state.commands.runCurrentScenario).toMatchObject({
      state: 'RUNNING',
      enabled: false,
      reasonCode: 'RG.AUTHOR.RUN.IN_PROGRESS',
    });
  });

  it('retains stale evidence but makes rerun the canonical next action', () => {
    const state = projectAuthorTaskState(input({
      hasRunResult: true,
      runSuccessful: true,
      evidenceStale: true,
      coordinate: { ...coordinate(), targetId: 'draft-1', targetRevision: 3 },
    }));

    expect(state).toMatchObject({
      canonicalState: 'EVIDENCE_STALE',
      currentness: 'STALE',
      proofStrength: 'DURABLE',
      primaryAction: { kind: 'run', label: 'Rerun current scenario' },
    });
  });

  it('claims governed proof only for current evidence on a durable coordinate', () => {
    const state = projectAuthorTaskState(input({
      hasRunResult: true,
      runSuccessful: true,
      governanceApproved: true,
      coordinate: { ...coordinate(), targetId: 'draft-1', targetRevision: 3 },
    }));

    expect(state).toMatchObject({
      canonicalState: 'EVIDENCE_CURRENT',
      currentness: 'CURRENT',
      proofStrength: 'GOVERNED',
      primaryAction: { kind: 'review-result' },
      primaryCommand: { commandId: 'PRIMARY_TASK_ACTION' },
    });
  });
});

function input(overrides: Partial<AuthorTaskStateInput> = {}): AuthorTaskStateInput {
  return {
    nodeCount: 3,
    busy: false,
    hasInputErrors: false,
    hasScenario: true,
    canonicalScenarioReady: true,
    hasRunResult: false,
    runSuccessful: false,
    evidenceStale: false,
    governanceApproved: false,
    coordinate: coordinate(),
    ...overrides,
  };
}

function coordinate() {
  return {
    targetKind: 'GRAPH' as const,
    targetId: '',
    targetRevision: 0,
    targetFingerprint: 'sha256:draft',
    contractFingerprint: 'sha256:contract',
    scenarioSetId: 'suite-1',
    scenarioId: 'case-1',
    scenarioRevision: 0,
    scenarioFingerprint: 'sha256:scenario',
    operatorClosureFingerprint: 'sha256:closure',
  };
}
