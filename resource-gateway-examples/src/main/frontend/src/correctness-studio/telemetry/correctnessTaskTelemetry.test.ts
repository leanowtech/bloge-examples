import { describe, expect, it, vi } from 'vitest';

import {
  CORRECTNESS_TASK_EVENT_TYPE,
  correctnessTaskElapsedMs,
  createCorrectnessTaskEvent,
  recordCorrectnessTaskEvent,
} from './correctnessTaskTelemetry';

describe('correctness task telemetry', () => {
  it('emits a versioned, bounded preflight event', () => {
    const target = new EventTarget();
    const listener = vi.fn();
    target.addEventListener(CORRECTNESS_TASK_EVENT_TYPE, listener);

    const event = recordCorrectnessTaskEvent('PREFLIGHT_EVALUATED', {
      stage: 'SCENARIO',
      scope: 'SELECTION',
      preflightStatus: 'BLOCKED',
      caseCount: 12,
      realCount: 2,
      mockedCount: 20,
      faultCount: 1,
      blockerCount: 2,
    }, target);

    expect(event).toMatchObject({
      schema: 'bloge.correctnessTaskEvent.v1',
      name: 'PREFLIGHT_EVALUATED',
      metadata: { preflightStatus: 'BLOCKED', caseCount: 12, blockerCount: 2 },
    });
    expect(listener).toHaveBeenCalledOnce();
  });

  it.each([
    ['caseId', 'customer-case-42'],
    ['targetRef', 'operator:customer'],
    ['fixturePayload', '{"customer":"secret"}'],
    ['actualOutput', 'secret-result'],
    ['errorMessage', 'customer account not found'],
  ])('rejects the payload-bearing metadata key %s', (key, value) => {
    expect(() => createCorrectnessTaskEvent('COMMAND_REJECTED', {
      stage: 'SCENARIO',
      scope: 'CASE',
      rejectionReason: 'API_ERROR',
      errorCode: 'RG.CORRECTNESS.API_ERROR',
      [key]: value,
    })).toThrow(/not allowed/);
  });

  it('rejects unknown enums, unbounded counts, fractions, and arbitrary error codes', () => {
    expect(() => createCorrectnessTaskEvent('RUN_REQUESTED', {
      stage: 'customer-stage', scope: 'CASE', source: 'LOCAL', preflightStatus: 'SAFE', caseCount: 1,
    })).toThrow(/unsupported enum/);
    expect(() => createCorrectnessTaskEvent('RUN_REQUESTED', {
      stage: 'SCENARIO', scope: 'CASE', source: 'LOCAL', preflightStatus: 'SAFE', caseCount: 1_000_001,
    })).toThrow(/bounded maximum/);
    expect(() => createCorrectnessTaskEvent('RUN_COMPLETED', {
      stage: 'SCENARIO', scope: 'CASE', source: 'LOCAL', runStatus: 'PASSED',
      caseCount: 1, failureCount: 0, durationMs: 1.5,
    })).toThrow(/safe integer/);
    expect(() => createCorrectnessTaskEvent('COMMAND_REJECTED', {
      stage: 'SCENARIO', scope: 'CASE', rejectionReason: 'API_ERROR',
      errorCode: 'RG.CUSTOMER.SECRET_FAILURE', caseCount: 1, blockerCount: 0,
    })).toThrow(/unsupported enum/);
  });

  it('drops invalid instrumentation and bounds elapsed time', () => {
    expect(recordCorrectnessTaskEvent('RUN_COMPLETED', {
      stage: 'SCENARIO', payload: 'secret',
    }, new EventTarget())).toBeNull();
    expect(correctnessTaskElapsedMs(100, 124.6)).toBe(25);
    expect(correctnessTaskElapsedMs(200, 100)).toBe(0);
    expect(correctnessTaskElapsedMs(0, Number.MAX_SAFE_INTEGER)).toBe(86_400_000);
  });
});
