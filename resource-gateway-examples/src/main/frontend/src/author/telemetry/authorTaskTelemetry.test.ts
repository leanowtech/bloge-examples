import { describe, expect, it, vi } from 'vitest';

import {
  AUTHOR_TASK_EVENT_TYPE,
  authorTaskElapsedMs,
  createAuthorTaskEvent,
  recordAuthorTaskEvent,
} from './authorTaskTelemetry';

describe('author task telemetry', () => {
  it('emits a versioned payload-free task event', () => {
    const target = new EventTarget();
    const listener = vi.fn();
    target.addEventListener(AUTHOR_TASK_EVENT_TYPE, listener);

    const event = recordAuthorTaskEvent('AUTO_LAYOUT_COMPLETED', {
      nodeCount: 100,
      edgeCount: 160,
      movedNodeCount: 92,
      durationMs: 24,
    }, target);

    expect(event).toMatchObject({
      schema: 'bloge.authorTaskEvent.v1',
      name: 'AUTO_LAYOUT_COMPLETED',
      metadata: {
        nodeCount: 100,
        edgeCount: 160,
        movedNodeCount: 92,
        durationMs: 24,
      },
    });
    expect(listener).toHaveBeenCalledOnce();
  });

  it('rejects payload-like, unknown, unbounded, and invalid metadata', () => {
    expect(() => createAuthorTaskEvent('RUN_COMPLETED', {
      inputPayload: '{"customerId":"secret"}',
    })).toThrow(/not allowed/);
    expect(() => createAuthorTaskEvent('RUN_COMPLETED', {
      graphName: 'customer-approval',
    })).toThrow(/not allowed/);
    expect(() => createAuthorTaskEvent('RUN_COMPLETED', {
      status: 'x'.repeat(65),
    })).toThrow(/64/);
    expect(() => createAuthorTaskEvent('RUN_COMPLETED', {
      status: 'customer-42',
    })).toThrow(/unsupported enum/);
    expect(() => createAuthorTaskEvent('RUN_COMPLETED', {
      durationMs: -1,
    })).toThrow(/non-negative/);
  });

  it('drops invalid instrumentation instead of breaking authoring', () => {
    expect(recordAuthorTaskEvent('RUN_STARTED', {
      context: 'classified',
    }, new EventTarget())).toBeNull();
    expect(authorTaskElapsedMs(100.4, 124.8)).toBe(24);
    expect(authorTaskElapsedMs(200, 100)).toBe(0);
  });

  it('records layout rejection and override decisions without graph payloads', () => {
    expect(createAuthorTaskEvent('AUTO_LAYOUT_CANDIDATE_REJECTED', {
      beforeQuality: 'PASS',
      candidateQuality: 'REVIEW',
      regressionCount: 4,
      beforeZoomPercent: 85,
      candidateZoomPercent: 39,
    }).metadata).toEqual({
      beforeQuality: 'PASS',
      candidateQuality: 'REVIEW',
      regressionCount: 4,
      beforeZoomPercent: 85,
      candidateZoomPercent: 39,
    });
    expect(createAuthorTaskEvent('AUTO_LAYOUT_OVERRIDE_APPLIED', {
      overrideReason: 'USER_ACCEPTED_READABILITY_REGRESSION',
      regressionCount: 4,
    }).metadata).toEqual({
      overrideReason: 'USER_ACCEPTED_READABILITY_REGRESSION',
      regressionCount: 4,
    });
  });

  it('records mutation history health without leaking authored content', () => {
    expect(createAuthorTaskEvent('AUTHOR_MUTATION_RECORDED', {
      mutationKind: 'REMOVE_NODE',
      impactCount: 7,
      historyDepth: 12,
    }).metadata).toEqual({
      mutationKind: 'REMOVE_NODE',
      impactCount: 7,
      historyDepth: 12,
    });
    expect(createAuthorTaskEvent('AUTHOR_MUTATION_UNDONE', {
      mutationKind: 'TEST_SUITE',
    }).metadata).toEqual({ mutationKind: 'TEST_SUITE' });
    expect(() => createAuthorTaskEvent('AUTHOR_MUTATION_RECORDED', {
      mutationKind: 'customer-specific-change',
      impactCount: 1,
      historyDepth: 1,
    })).toThrow(/unsupported enum/);
  });
});
