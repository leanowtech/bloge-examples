import { describe, expect, it } from 'vitest';

import { projectAuthorReadiness, type AuthorReadinessInput } from './authorReadiness';

describe('projectAuthorReadiness', () => {
  it('is fail closed until the exact saved revision passes every dimension', () => {
    const verdict = projectAuthorReadiness(input({
      draft: { durable: true, current: true, conflicted: false },
      execution: { busy: false, evaluated: true, passed: true, warnings: false, stale: false },
      assertions: { configured: true, busy: false, evaluated: true, passed: true, stale: false },
      contract: { busy: false, evaluated: true, passed: true, stale: false },
      governance: { busy: false, evaluated: true, status: 'APPROVED', stale: false },
    }));

    expect(verdict).toMatchObject({
      draft: 'SAVED',
      execution: 'PASSED',
      assertions: 'PASSED',
      contract: 'VALID',
      governance: 'APPROVED',
      promotion: 'READY',
      headline: 'Ready for promotion',
    });
    expect(verdict.reasons).toEqual([]);
  });

  it('retains stale evidence but blocks it ahead of incomplete checks', () => {
    const verdict = projectAuthorReadiness(input({
      draft: { durable: true, current: false, conflicted: false },
      execution: { busy: false, evaluated: true, passed: true, warnings: false, stale: true },
      assertions: { configured: true, busy: false, evaluated: true, passed: true, stale: true },
      contract: { busy: false, evaluated: true, passed: true, stale: true },
      governance: { busy: false, evaluated: true, status: 'APPROVED', stale: true },
    }));

    expect(verdict).toMatchObject({
      draft: 'DIRTY',
      execution: 'STALE',
      assertions: 'STALE',
      contract: 'STALE',
      governance: 'STALE',
      promotion: 'BLOCKED',
    });
    expect(verdict.reasons[0].code).toMatch(/STALE|DIRTY/);
    expect(verdict.nextAction).toBeDefined();
  });

  it('distinguishes ephemeral, running, failed, and concurrent-conflict states', () => {
    const ephemeral = projectAuthorReadiness(input({}));
    expect(ephemeral.draft).toBe('EPHEMERAL');
    expect(ephemeral.execution).toBe('NOT_RUN');
    expect(ephemeral.promotion).toBe('NOT_EVALUATED');

    const running = projectAuthorReadiness(input({
      execution: { busy: true, evaluated: false, passed: false, warnings: false, stale: false },
    }));
    expect(running.execution).toBe('RUNNING');

    const failed = projectAuthorReadiness(input({
      draft: { durable: true, current: true, conflicted: true },
      execution: { busy: false, evaluated: true, passed: false, warnings: false, stale: false },
    }));
    expect(failed.draft).toBe('CONFLICTED');
    expect(failed.execution).toBe('FAILED');
    expect(failed.promotion).toBe('BLOCKED');
    expect(failed.reasons[0].code).toMatch(/CONFLICTED|FAILED/);
  });

  it('requires complete, unexpired warning-waiver accountability', () => {
    const warningInput = input({
      draft: { durable: true, current: true, conflicted: false },
      execution: { busy: false, evaluated: true, passed: true, warnings: true, stale: false },
      assertions: { configured: true, busy: false, evaluated: true, passed: true, stale: false },
      contract: { busy: false, evaluated: true, passed: true, stale: false },
      governance: { busy: false, evaluated: true, status: 'REVIEW_REQUIRED', stale: false },
      now: '2026-07-29T00:00:00Z',
    });
    expect(projectAuthorReadiness(warningInput)).toMatchObject({
      promotion: 'REVIEW_REQUIRED',
      waiver: 'MISSING',
    });
    const waived = projectAuthorReadiness({
      ...warningInput,
      warningWaiver: {
        owner: 'risk-owner',
        reason: 'Accepted for one canary window',
        scope: 'draft:loan:r4',
        expiresAt: '2026-07-30T00:00:00Z',
      },
    });
    expect(waived).toMatchObject({
      promotion: 'READY',
      waiver: 'ACTIVE',
      nextAction: { label: 'Review promotion evidence', mode: 'evidence' },
    });
    expect(waived.summary).toContain('active scoped waiver');
    expect(projectAuthorReadiness({
      ...warningInput,
      warningWaiver: {
        owner: 'risk-owner',
        reason: '',
        scope: 'draft:loan:r4',
        expiresAt: '2026-07-28T00:00:00Z',
      },
    })).toMatchObject({
      promotion: 'REVIEW_REQUIRED',
      waiver: 'INVALID',
    });
  });
});

function input(overrides: Partial<AuthorReadinessInput>): AuthorReadinessInput {
  return {
    draft: { durable: false, current: false, conflicted: false },
    execution: { busy: false, evaluated: false, passed: false, warnings: false, stale: false },
    assertions: { configured: false, busy: false, evaluated: false, passed: false, stale: false },
    contract: { busy: false, evaluated: false, passed: false, stale: false },
    governance: { busy: false, evaluated: false, status: '', stale: false },
    ...overrides,
  };
}
