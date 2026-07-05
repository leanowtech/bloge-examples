import type {
  ConnectionCandidatesRequest,
  ConnectionCandidatesResponse,
  ConnectionCheckRequest,
  ConnectionCheckResponse,
  OperatorDefinition,
  SimulationRequest,
  SimulationResponse,
} from './types';

async function readJson<T>(response: Response): Promise<T> {
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status} ${response.statusText}`);
  }
  return (await response.json()) as T;
}

/**
 * Loads the operator catalog. Tolerates either a bare array or a `{ operators: [...] }` envelope so the
 * palette works regardless of the catalog controller's exact response shape.
 */
export async function fetchOperators(): Promise<OperatorDefinition[]> {
  const data = await readJson<unknown>(await fetch('/api/visual/operators'));
  if (Array.isArray(data)) {
    return data as OperatorDefinition[];
  }
  const envelope = data as { operators?: OperatorDefinition[] };
  return envelope.operators ?? [];
}

/** Runs a mock simulation of the current draft. */
export async function simulate(request: SimulationRequest): Promise<SimulationResponse> {
  return readJson<SimulationResponse>(
    await fetch('/api/visual/graphs/simulate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    }),
  );
}

/** Preflights one proposed canvas connection with the server's schema gate. */
export async function checkConnection(
  request: ConnectionCheckRequest,
): Promise<ConnectionCheckResponse> {
  return readJson<ConnectionCheckResponse>(
    await fetch('/api/visual/connections/check', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    }),
  );
}

/** Discovers server-authoritative target candidates for a source handle drag. */
export async function fetchConnectionCandidates(
  request: ConnectionCandidatesRequest,
): Promise<ConnectionCandidatesResponse> {
  return readJson<ConnectionCandidatesResponse>(
    await fetch('/api/visual/connections/candidates', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    }),
  );
}
