import { describe, expect, it } from 'vitest';

import {
  presentCorrectnessVerdict,
  type CorrectnessVerdictInput,
} from './verdictPresentationPolicy';

describe('correctness verdict presentation policy', () => {
  it('marks a successful execution without assertions as unproven and blocked', () => {
    const presentation = presentCorrectnessVerdict(verdict({ assertions: 'NONE' }));

    expect(presentation).toMatchObject({
      reason: 'UNPROVEN',
      tone: 'warning',
      primary: { messageId: 'correctness.verdict.unproven.label' },
      axes: {
        execution: { status: 'SUCCESS' },
        assertions: { status: 'NONE' },
        gate: { status: 'BLOCKED' },
      },
    });
  });

  it.each<{
    name: string;
    input: Partial<CorrectnessVerdictInput>;
    reason: string;
    gate: string;
  }>([
    { name: 'not run', input: { execution: 'NOT_RUN', evidence: 'NOT_AVAILABLE' }, reason: 'NOT_RUN', gate: 'NOT_EVALUATED' },
    { name: 'running', input: { execution: 'RUNNING', assertions: 'NONE' }, reason: 'IN_PROGRESS', gate: 'NOT_EVALUATED' },
    { name: 'timeout', input: { execution: 'TIMEOUT', assertions: 'NONE' }, reason: 'EXECUTION_FAILED', gate: 'BLOCKED' },
    { name: 'assertion failure', input: { assertions: 'FAILED' }, reason: 'ASSERTIONS_FAILED', gate: 'BLOCKED' },
    { name: 'unfrozen denominator', input: { coverage: 'UNFROZEN' }, reason: 'COVERAGE_UNFROZEN', gate: 'BLOCKED' },
    { name: 'coverage gap', input: { coverage: 'GAPPED' }, reason: 'COVERAGE_GAPPED', gate: 'BLOCKED' },
    { name: 'exploratory evidence', input: { evidence: 'EXPLORATORY' }, reason: 'EVIDENCE_EXPLORATORY', gate: 'BLOCKED' },
    { name: 'stale evidence', input: { evidence: 'STALE' }, reason: 'EVIDENCE_STALE', gate: 'BLOCKED' },
    { name: 'external gate pending', input: { gate: 'NOT_EVALUATED' }, reason: 'GATE_NOT_EVALUATED', gate: 'NOT_EVALUATED' },
    { name: 'accepted closure', input: {}, reason: 'ACCEPTED', gate: 'ACCEPTED' },
  ])('keeps all five axes honest for $name', ({ input, reason, gate }) => {
    const presentation = presentCorrectnessVerdict(verdict(input));

    expect(presentation.reason).toBe(reason);
    expect(presentation.axes.gate.status).toBe(gate);
    expect(Object.keys(presentation.axes)).toEqual([
      'execution', 'assertions', 'coverage', 'evidence', 'gate',
    ]);
  });

  it('rejects a caller-supplied accepted gate when any stronger axis is not satisfied', () => {
    expect(presentCorrectnessVerdict(verdict({
      assertions: 'NONE',
      gate: 'ACCEPTED',
    })).axes.gate.status).toBe('BLOCKED');
    expect(presentCorrectnessVerdict(verdict({
      coverage: 'GAPPED',
      gate: 'ACCEPTED',
    })).axes.gate.status).toBe('BLOCKED');
    expect(presentCorrectnessVerdict(verdict({
      evidence: 'REVOKED',
      gate: 'ACCEPTED',
    })).axes.gate.status).toBe('BLOCKED');
  });

  it('provides stable localized message ids for every axis and primary reason', () => {
    const presentation = presentCorrectnessVerdict(verdict({}));

    expect(Object.values(presentation.axes).map((axis) => axis.label.messageId)).toEqual([
      'correctness.axis.execution.label',
      'correctness.axis.assertions.label',
      'correctness.axis.coverage.label',
      'correctness.axis.evidence.label',
      'correctness.axis.gate.label',
    ]);
    expect(presentation.primary.messageId).toBe('correctness.verdict.accepted.label');
  });
});

function verdict(overrides: Partial<CorrectnessVerdictInput>): CorrectnessVerdictInput {
  return {
    execution: 'SUCCESS',
    assertions: 'PASSED',
    coverage: 'COMPLETE',
    evidence: 'CURRENT',
    gate: 'ACCEPTED',
    proofLevel: 'CERTIFIABLE',
    ...overrides,
  };
}
