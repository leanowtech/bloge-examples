import type {
  ConnectionCandidatesRequest,
  ConnectionCandidatesResponse,
  ConnectionCheckRequest,
  ConnectionCheckResponse,
  GatewayExampleDiagram,
  GatewayExampleScenario,
  OperatorLibrary,
  OperatorLibraryValidationResult,
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

async function readJsonBody<T>(response: Response): Promise<T | null> {
  const text = await response.text();
  if (!text) {
    return null;
  }
  try {
    return JSON.parse(text) as T;
  } catch {
    return null;
  }
}

async function readJsonMutation<T>(response: Response): Promise<T> {
  const payload = await readJsonBody<T & { diagnostics?: { message?: string; code?: string }[] }>(response);
  if (!response.ok) {
    const firstDiagnostic = payload?.diagnostics?.find((diagnostic) => diagnostic.message || diagnostic.code);
    const detail = firstDiagnostic?.message || firstDiagnostic?.code || response.statusText;
    throw new Error(`Request failed: ${response.status} ${detail}`);
  }
  if (!payload) {
    throw new Error(`Request failed: ${response.status} empty response`);
  }
  return payload;
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

/** Loads resource-gateway showcase scenarios in backend-defined order. */
export async function fetchGatewayScenarios(): Promise<GatewayExampleScenario[]> {
  return readJson<GatewayExampleScenario[]>(
    await fetch('/api/gateway/examples/scenarios'),
  );
}

/** Loads the presentation-only diagram for one resource-gateway showcase scenario. */
export async function fetchGatewayDiagram(path: string): Promise<GatewayExampleDiagram> {
  return readJson<GatewayExampleDiagram>(
    await fetch(path),
  );
}

/** Validates pasted operator-library JSON/YAML without storing it. */
export async function validateOperatorLibraryText(sourceText: string): Promise<OperatorLibraryValidationResult> {
  return readJsonMutation<OperatorLibraryValidationResult>(
    await fetch('/admin/visual-operator-libraries/validate-text', {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain' },
      body: sourceText,
    }),
  );
}

/** Imports pasted operator-library JSON/YAML, then the caller should refresh the catalog. */
export async function importOperatorLibraryText(sourceText: string): Promise<OperatorLibrary> {
  const query = new URLSearchParams({
    actor: 'author-canvas',
    changeSource: 'react-author',
    changeSummary: 'Imported from React author canvas',
  });
  return readJsonMutation<OperatorLibrary>(
    await fetch(`/admin/visual-operator-libraries/import-text?${query.toString()}`, {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain' },
      body: sourceText,
    }),
  );
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
