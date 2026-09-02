import { integrationRequestHeaders } from '../api';
import type { AuthoringWorkbenchTransport } from './api';
import type { SimulationCommandV2, SimulationRunV2 } from './callerSimulationModel';

/** Executes or exactly replays one caller-directed Fixture Plan with external effects denied by default. */
export async function runCallerDirectedSimulation(
  command: SimulationCommandV2,
  idempotencyKey: string,
  transport: AuthoringWorkbenchTransport = fetch,
): Promise<SimulationRunV2> {
  const response = await transport('/api/authoring/simulations', {
    method: 'POST',
    headers: integrationRequestHeaders('AUTHORING_SIMULATION_RUN', {
      'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey,
    }),
    body: JSON.stringify(command),
  });
  const text = await response.text();
  let payload: unknown = null;
  if (text) {
    try { payload = JSON.parse(text); } catch { payload = null; }
  }
  if (!response.ok) {
    const problem = payload as { detail?: string; title?: string; code?: string } | null;
    throw new Error(problem?.detail || problem?.title || problem?.code || response.statusText);
  }
  if (!payload) throw new Error('The server returned an empty Simulation Run.');
  return payload as SimulationRunV2;
}
