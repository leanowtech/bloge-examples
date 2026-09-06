import { afterEach, describe, expect, it } from 'vitest';

import { resetBlogeApiTransport, setBlogeApiTransport } from '../../api';
import { businessSolutionAssetsApi } from './businessSolutionApi';

describe('businessSolutionAssetsApi', () => {
  afterEach(() => resetBlogeApiTransport());

  it('separates protected Golden authority from metadata-only Fixture authority', async () => {
    const requests: Array<{ input: string; init?: RequestInit }> = [];
    setBlogeApiTransport(async (input, init) => {
      requests.push({ input: String(input), init });
      return new Response(JSON.stringify({ cases: [] }), {
        status: 200, headers: { 'Content-Type': 'application/json' },
      });
    });

    await businessSolutionAssetsApi.golden('sol:cancel fee', 'journey:review');
    await businessSolutionAssetsApi.goldenMaterial('sol:cancel fee', 'journey:review', 'G 1');
    await businessSolutionAssetsApi.fixtures('sol:cancel fee');

    expect(requests[0]?.input).toBe('/api/solution/golden-review/sol%3Acancel%20fee?journeyRef=journey%3Areview');
    expect(new Headers(requests[0]?.init?.headers).get('X-Purpose')).toBe('SOLUTION_GOLDEN_REVIEW');
    expect(requests[1]?.input).toContain('/cases/G%201/material?journeyRef=journey%3Areview');
    expect(new Headers(requests[1]?.init?.headers).get('X-Purpose')).toBe('SOLUTION_GOLDEN_REVIEW');
    expect(requests[2]?.input).toBe('/api/agent-tdd/solutions/sol%3Acancel%20fee/fixtures');
    expect(new Headers(requests[2]?.init?.headers).get('X-Purpose')).toBe('AGENT_TDD_GOVERNED_WRITE');
  });
});
