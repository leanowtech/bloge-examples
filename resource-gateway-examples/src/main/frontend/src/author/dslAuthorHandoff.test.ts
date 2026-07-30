// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest';

import {
  DSL_AUTHOR_HANDOFF_KEY,
  MAXIMUM_DSL_HANDOFF_CHARACTERS,
  stageDslAuthorHandoff,
  takeDslAuthorHandoff,
} from './dslAuthorHandoff';

describe('DSL Author handoff', () => {
  beforeEach(() => window.sessionStorage.clear());

  it('stages a bounded same-origin handoff and consumes it once', () => {
    expect(stageDslAuthorHandoff('support-routing.bloge', 'graph supportRouting {}', 100))
      .toEqual({ accepted: true, message: 'DSL handoff staged.' });

    expect(takeDslAuthorHandoff(200)).toMatchObject({
      sourceId: 'support-routing.bloge',
      dsl: 'graph supportRouting {}',
    });
    expect(takeDslAuthorHandoff(200)).toBeNull();
  });

  it('rejects oversized and expired handoffs', () => {
    expect(stageDslAuthorHandoff(
      'large.bloge',
      'x'.repeat(MAXIMUM_DSL_HANDOFF_CHARACTERS + 1),
      100,
    )).toMatchObject({ accepted: false });

    window.sessionStorage.setItem(DSL_AUTHOR_HANDOFF_KEY, JSON.stringify({
      schemaVersion: 'bloge.dslAuthorHandoff.v1',
      sourceId: 'expired.bloge',
      dsl: 'graph expired {}',
      expiresAt: 99,
    }));
    expect(takeDslAuthorHandoff(100)).toBeNull();
    expect(window.sessionStorage.getItem(DSL_AUTHOR_HANDOFF_KEY)).toBeNull();
  });
});
