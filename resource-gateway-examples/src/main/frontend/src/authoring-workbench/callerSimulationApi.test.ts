import { describe, expect, it, vi } from 'vitest';

import { runCallerDirectedSimulation } from './callerSimulationApi';
import { buildSimulationCommandV2 } from './callerSimulationModel';

describe('caller-directed simulation transport', () => {
  it('sends the v2 command with the simulation purpose and idempotency authority', async () => {
    const run = {
      schemaVersion: 'bloge.simulationRun.v2', runId: 'run-1', status: 'BLOCKED',
      subject: subject(), requestFingerprint: hash('b'), resolvedFixturePlanFingerprint: hash('c'),
      invocations: [], verdicts: {
        execution: 'BLOCKED', assertions: 'NOT_CHECKED', contract: 'NOT_CHECKED',
        governance: 'NOT_CHECKED', aggregate: 'NOT_READY',
      }, diagnostics: [{ code: 'NO_FIXTURE', message: 'No Fixture was selected.' }],
    } as const;
    const transport = vi.fn(async () => new Response(JSON.stringify(run), {
      status: 200, headers: { 'Content-Type': 'application/json' },
    }));
    const command = buildSimulationCommandV2(subject(), { id: 'c-1' }, { kind: 'NONE' });

    await expect(runCallerDirectedSimulation(command, 'run-key-1', transport)).resolves.toEqual(run);
    expect(transport).toHaveBeenCalledWith('/api/authoring/simulations', expect.objectContaining({
      method: 'POST', body: JSON.stringify(command), headers: expect.objectContaining({
        'Content-Type': 'application/json', 'Idempotency-Key': 'run-key-1',
        'X-Purpose': 'AUTHORING_SIMULATION_RUN',
      }),
    }));
  });

  it('surfaces only the bounded problem detail', async () => {
    const transport = vi.fn(async () => new Response(JSON.stringify({
      code: 'FIXTURE_AUTO_MATCH_EMPTY', detail: 'No saved condition matched.', secret: 'hidden',
    }), { status: 422, statusText: 'Unprocessable' }));

    await expect(runCallerDirectedSimulation(
      buildSimulationCommandV2(subject(), {}, { kind: 'NONE' }), 'run-key-2', transport,
    )).rejects.toThrow('No saved condition matched.');
  });
});

function subject() {
  return { kind: 'API_RESOURCE' as const, resourceId: 'customer', revision: 1, fingerprint: hash('a') };
}
function hash(value: string): string { return `sha256:${value.repeat(64)}`; }
