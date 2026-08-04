import { afterEach, describe, expect, it } from 'vitest';

import {
  cancelTableSuiteRun,
  fetchTableSuiteRun,
  fetchTableSuiteRunEvents,
  resetBlogeApiTransport,
  retryFailedTableSuiteRun,
  setBlogeApiTransport,
  submitTableSuiteRun,
} from '../../api';
import type { TableSuiteRunCommand } from './tableSuiteRunModel';

describe('table suite run API', () => {
  afterEach(() => resetBlogeApiTransport());

  it('uses exact endpoints and the accepted test-execution purpose for the whole batch lifecycle', async () => {
    const calls: Array<{ url: string; init?: RequestInit }> = [];
    setBlogeApiTransport(async (input, init) => {
      calls.push({ url: String(input), init });
      return new Response(JSON.stringify(String(input).includes('/events?')
        ? {
            schemaVersion: 'bloge.tableSuiteRunDelta.v1',
            batchId: 'batch-a', revision: 1, status: 'RUNNING',
            counts: { total: 1, queued: 1, running: 0, succeeded: 0, failed: 0, cancelled: 0, budgetStopped: 0 },
            promotion: { eligible: false, reason: 'PENDING' }, events: [],
            resetRequired: false,
          }
        : { schemaVersion: 'bloge.tableSuiteRunBatch.v1', batchId: 'batch-a' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    });

    await submitTableSuiteRun({ requestId: 'request-a' } as TableSuiteRunCommand);
    await fetchTableSuiteRun('batch-a');
    await fetchTableSuiteRunEvents('batch-a', 7);
    await cancelTableSuiteRun('batch-a');
    await retryFailedTableSuiteRun('batch-a');

    expect(calls.map((call) => [call.init?.method ?? 'GET', call.url])).toEqual([
      ['POST', '/api/visual/table-suite-runs'],
      ['GET', '/api/visual/table-suite-runs/batch-a'],
      ['GET', '/api/visual/table-suite-runs/batch-a/events?afterRevision=7'],
      ['POST', '/api/visual/table-suite-runs/batch-a/cancel'],
      ['POST', '/api/visual/table-suite-runs/batch-a/retry-failed'],
    ]);
    expect(calls.map((call) => new Headers(call.init?.headers).get('X-Purpose'))).toEqual([
      'TEST_EXECUTION',
      'TEST_EXECUTION',
      'TEST_EXECUTION',
      'TEST_EXECUTION',
      'TEST_EXECUTION',
    ]);
  });
});
