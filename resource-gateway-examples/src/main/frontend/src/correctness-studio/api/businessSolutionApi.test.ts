import { afterEach, describe, expect, it } from 'vitest';

import { resetBlogeApiTransport, setBlogeApiTransport } from '../../api';
import { createBusinessSolutionAssetsApi } from './businessSolutionApi';

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

    const api = createBusinessSolutionAssetsApi(() => ({ Authorization: 'Bearer reviewer-token' }));
    await api.golden('sol:cancel fee', 'journey:review');
    await api.goldenMaterial('sol:cancel fee', 'journey:review', 'G 1');
    await api.fixtures('sol:cancel fee');
    await api.coverage('sol:cancel fee');

    expect(requests[0]?.input).toBe('/api/solution/golden-review/sol%3Acancel%20fee?journeyRef=journey%3Areview');
    expect(new Headers(requests[0]?.init?.headers).get('X-Purpose')).toBe('SOLUTION_GOLDEN_REVIEW');
    expect(new Headers(requests[0]?.init?.headers).get('Authorization')).toBe('Bearer reviewer-token');
    expect(requests[1]?.input).toContain('/cases/G%201/material?journeyRef=journey%3Areview');
    expect(new Headers(requests[1]?.init?.headers).get('X-Purpose')).toBe('SOLUTION_GOLDEN_REVIEW');
    expect(requests[2]?.input).toBe('/api/agent-tdd/solutions/sol%3Acancel%20fee/fixtures');
    expect(new Headers(requests[2]?.init?.headers).get('X-Purpose')).toBe('SOLUTION_GOLDEN_REVIEW');
    expect(requests[3]?.input).toBe('/api/solution/coverage/sol%3Acancel%20fee');
    requests.forEach((request) => {
      expect(request.init?.headers).toMatchObject({
        Authorization: 'Bearer reviewer-token',
        'X-Purpose': 'SOLUTION_GOLDEN_REVIEW',
      });
    });
  });
});
