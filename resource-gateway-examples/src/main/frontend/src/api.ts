import type {
  CapabilityCatalogVisualAdapterResult,
  ConnectionCandidatesRequest,
  ConnectionCandidatesResponse,
  ConnectionCheckRequest,
  ConnectionCheckResponse,
  DslImportBatchCommitRequest,
  DslImportBatchCommitResult,
  DslImportBatchReport,
  DslImportBatchReportRequest,
  DslImportPreviewRequest,
  DslRewriteGateResult,
  DslVisualProjection,
  GatewayExampleDiagram,
  GatewayExampleRun,
  GatewayExampleRunRequest,
  GatewayExampleRunResult,
  GatewayExampleScenario,
  GraphDraft,
  GraphDraftImportResult,
  OperatorLibrary,
  OperatorLibraryValidationResult,
  OperatorCatalogResponse,
  OperatorDefinition,
  SimulationRequest,
  SimulationResponse,
  VisualValidationResult,
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

function fillTemplate(template: string, values: Record<string, unknown>): string {
  return template.replace(/\{([^}]+)\}/g, (_, key: string) =>
    encodeURIComponent(String(values[key] ?? '')),
  );
}

function replacePlaceholders(value: unknown, values: Record<string, unknown>): unknown {
  if (typeof value === 'string') {
    const exact = value.match(/^\{([^}]+)\}$/);
    if (exact) {
      return values[exact[1]] ?? '';
    }
    return value.replace(/\{([^}]+)\}/g, (_, key: string) => String(values[key] ?? ''));
  }
  if (Array.isArray(value)) {
    return value.map((item) => replacePlaceholders(item, values));
  }
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value).map(([key, item]) => [key, replacePlaceholders(item, values)]),
    );
  }
  return value;
}

async function readFlexiblePayload(response: Response): Promise<unknown> {
  const text = await response.text();
  if (!text) {
    return null;
  }
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

/**
 * Loads the operator catalog. Tolerates either a bare array or a full envelope so the palette works
 * regardless of the catalog controller's exact response shape.
 */
export async function fetchOperatorCatalog(): Promise<OperatorCatalogResponse> {
  const data = await readJson<unknown>(await fetch('/api/visual/operators'));
  if (Array.isArray(data)) {
    return { operators: data as OperatorDefinition[], builtInFunctions: [] };
  }
  const envelope = data as OperatorCatalogResponse;
  return {
    operators: envelope.operators ?? [],
    builtInFunctions: envelope.builtInFunctions ?? [],
  };
}

/** Loads only the operator list for legacy callers/tests. */
export async function fetchOperators(): Promise<OperatorDefinition[]> {
  return (await fetchOperatorCatalog()).operators;
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

/** Resolves one scenario run recipe into the browser request used by the showcase runner. */
export function buildGatewayRunRequest(
  run: GatewayExampleRun,
  values: Record<string, unknown>,
): GatewayExampleRunRequest {
  const mode = run.mode ?? 'request';
  const headers = { ...(run.headers ?? {}) };
  const init: RequestInit = {
    method: run.method ?? 'GET',
    headers,
  };
  if (mode === 'post') {
    init.body = JSON.stringify(replacePlaceholders(run.bodyTemplate ?? {}, values));
  }
  return {
    mode,
    url: fillTemplate(run.pathTemplate ?? '/', values),
    init,
  };
}

/** Executes one non-streaming resource-gateway showcase scenario through its public gateway endpoint. */
export async function runGatewayScenario(
  run: GatewayExampleRun,
  values: Record<string, unknown>,
): Promise<GatewayExampleRunResult> {
  const request = buildGatewayRunRequest(run, values);
  if (request.mode === 'stream') {
    throw new Error('Streaming scenarios must be executed with EventSource.');
  }
  const response = await fetch(request.url, request.init);
  const payload = await readFlexiblePayload(response);
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status} ${response.statusText || 'Gateway run failed'}`);
  }
  return {
    status: response.status,
    url: request.url,
    payload,
  };
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

/** Adapts pasted bloge.capabilityCatalog.v1 JSON/YAML into a visual operator-library draft. */
export async function adaptCapabilityCatalogText(sourceText: string): Promise<CapabilityCatalogVisualAdapterResult> {
  return readJsonMutation<CapabilityCatalogVisualAdapterResult>(
    await fetch('/admin/visual-operator-libraries/from-capability-catalog-text', {
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

/** Validates a transient visual graph draft through the server-authoritative schema/readiness gate. */
export async function validateDraft(draft: GraphDraft): Promise<VisualValidationResult> {
  return readJson<VisualValidationResult>(
    await fetch('/api/visual/drafts/validate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(draft),
    }),
  );
}

/** Projects existing BLOGE DSL into an editable visual graph draft without persisting it. */
export async function previewDslImport(request: DslImportPreviewRequest): Promise<DslVisualProjection> {
  return readJsonMutation<DslVisualProjection>(
    await fetch('/api/visual/dsl-imports/preview', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    }),
  );
}

/** Checks whether generated DSL is safe enough to overwrite its source file. */
export async function checkDslRewriteGate(request: DslImportPreviewRequest): Promise<DslRewriteGateResult> {
  return readJsonMutation<DslRewriteGateResult>(
    await fetch('/api/visual/dsl-imports/rewrite-gate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    }),
  );
}

/** Assesses multiple BLOGE DSL sources against the same schema-neutral catalog view. */
export async function batchReportDslImports(request: DslImportBatchReportRequest): Promise<DslImportBatchReport> {
  return readJsonMutation<DslImportBatchReport>(
    await fetch('/api/visual/dsl-imports/batch-report', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    }),
  );
}

/** Stores eligible DSL projections from a batch as governed visual graph drafts. */
export async function batchCommitDslImports(
  request: DslImportBatchCommitRequest,
): Promise<DslImportBatchCommitResult> {
  const query = new URLSearchParams({
    actor: 'author-canvas',
    changeSource: 'legacy-dsl-batch-import',
    changeSummary: `Batch imported ${(request.sources ?? []).length} Legacy DSL sources`,
  });
  return readJsonMutation<DslImportBatchCommitResult>(
    await fetch(`/api/visual/dsl-imports/batch-commit?${query.toString()}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    }),
  );
}

/** Re-projects existing BLOGE DSL server-side and stores it as a governed visual draft revision. */
export async function commitDslImport(request: DslImportPreviewRequest): Promise<GraphDraftImportResult> {
  const query = new URLSearchParams({
    actor: 'author-canvas',
    changeSource: 'legacy-dsl-import',
    changeSummary: `Imported ${request.sourceId || 'inline.dsl'} from Legacy DSL panel`,
  });
  return readJsonMutation<GraphDraftImportResult>(
    await fetch(`/api/visual/dsl-imports/commit?${query.toString()}`, {
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
