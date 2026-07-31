import { describe, expect, it } from 'vitest';

import { redactedFixturePreview } from './GovernedFixtureSavePanel';

describe('redactedFixturePreview', () => {
  it('masks sensitive keys and explicit JSON Pointer paths without mutating the payload', () => {
    const payload = {
      input: {
        customerId: 'customer-1',
        password: 'do-not-show',
      },
      response: {
        token: 'also-private',
        decision: 'APPROVE',
      },
    };

    expect(redactedFixturePreview(payload, ['/input/customerId'])).toEqual({
      input: {
        customerId: '[REDACTED]',
        password: '[REDACTED]',
      },
      response: {
        token: '[REDACTED]',
        decision: 'APPROVE',
      },
    });
    expect(payload.input.password).toBe('do-not-show');
  });
});
