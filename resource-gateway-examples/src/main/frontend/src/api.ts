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
  GovernanceGateView,
  OperatorLibrary,
  OperatorLibraryValidationResult,
  OperatorCatalogResponse,
  OperatorDefinition,
  OperatorTestCaseRun,
  OperatorTestExecutionResponse,
  OperatorTestTargetDescriptor,
  StoredOperatorTestFixture,
  SimulationRequest,
  SimulationResponse,
  VisualValidationResult,
  VisualGraphRunRecord,
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

async function readTestingJson<T>(response: Response): Promise<T> {
  const payload = await readJsonBody<T & {
    code?: string;
    detail?: string;
    diagnostics?: Array<{ message?: string; code?: string }>;
  }>(response);
  if (!response.ok) {
    const firstDiagnostic = payload?.diagnostics?.find((diagnostic) => diagnostic.message || diagnostic.code);
    const detail = payload?.detail || firstDiagnostic?.message || firstDiagnostic?.code
      || payload?.code || response.statusText;
    throw new Error(`Request failed: ${response.status} ${detail}`);
  }
  if (!payload) {
    throw new Error(`Request failed: ${response.status} empty response`);
  }
  return payload;
}

export type BlogeApiTransport = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;

const defaultBlogeApiTransport: BlogeApiTransport = (input, init) => (
  init === undefined ? fetch(input) : fetch(input, init)
);
let blogeApiTransport: BlogeApiTransport = defaultBlogeApiTransport;

/** Supplies workload authentication for the isolated test control plane. */
export type OperatorTestHeadersProvider = () => Record<string, string>;

const defaultOperatorTestHeadersProvider: OperatorTestHeadersProvider = () => ({
  // This identity is accepted only by the repository's test/staging demo profiles.
  Authorization: 'Bearer bloge-aneke-demo-token',
});
let operatorTestHeadersProvider = defaultOperatorTestHeadersProvider;

/**
 * Replaces the HTTP transport used by the visual authoring client.
 *
 * The browser demo keeps the default fetch-backed transport. A VSCode Webview can install a
 * postMessage-backed transport and let the extension host satisfy the same contracts from local
 * workspace files, lightweight DSL projection, or an optional remote BLOGE service.
 */
export function setBlogeApiTransport(transport: BlogeApiTransport): void {
  blogeApiTransport = transport;
}

export function resetBlogeApiTransport(): void {
  blogeApiTransport = defaultBlogeApiTransport;
}

/** Lets a VSCode extension or authenticated host supply short-lived testing credentials. */
export function setOperatorTestHeadersProvider(provider: OperatorTestHeadersProvider): void {
  operatorTestHeadersProvider = provider;
}

/** Restores the local test-profile credential used by the standalone demo. */
export function resetOperatorTestHeadersProvider(): void {
  operatorTestHeadersProvider = defaultOperatorTestHeadersProvider;
}

function sendRequest(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  return blogeApiTransport(input, init);
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
  const data = await readJson<unknown>(await sendRequest('/api/visual/operators'));
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
    await sendRequest('/api/gateway/examples/scenarios'),
  );
}

/** Loads the presentation-only diagram for one resource-gateway showcase scenario. */
export async function fetchGatewayDiagram(path: string): Promise<GatewayExampleDiagram> {
  return readJson<GatewayExampleDiagram>(
    await sendRequest(path),
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
  const response = await sendRequest(request.url, request.init);
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
    await sendRequest('/admin/visual-operator-libraries/validate-text', {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain' },
      body: sourceText,
    }),
  );
}

/** Imports pasted operator-library JSON/YAML, then the caller should refresh the catalog. */
export async function importOperatorLibraryText(
  sourceText: string,
  ackWarnings = false,
  reason = '',
): Promise<OperatorLibrary> {
  const query = new URLSearchParams({
    actor: 'author-canvas',
    changeSource: 'react-author',
    changeSummary: 'Imported from React author canvas',
  });
  if (ackWarnings) {
    query.set('ackWarnings', 'true');
    query.set('reason', reason.trim());
  }
  return readJsonMutation<OperatorLibrary>(
    await sendRequest(`/admin/visual-operator-libraries/import-text?${query.toString()}`, {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain' },
      body: sourceText,
    }),
  );
}

/** Adapts pasted bloge.capabilityCatalog.v1 JSON/YAML into a visual operator-library draft. */
export async function adaptCapabilityCatalogText(sourceText: string): Promise<CapabilityCatalogVisualAdapterResult> {
  return readJsonMutation<CapabilityCatalogVisualAdapterResult>(
    await sendRequest('/admin/visual-operator-libraries/from-capability-catalog-text', {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain' },
      body: sourceText,
    }),
  );
}

/** Runs a mock simulation of the current draft. */
export async function simulate(request: SimulationRequest): Promise<SimulationResponse> {
  return readJson<SimulationResponse>(
    await sendRequest('/api/visual/graphs/simulate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    }),
  );
}

function recordValue(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function operatorTestingHeaders(purpose: 'TEST_EXECUTION' | 'TEST_FIXTURE_WRITE', json = false): Record<string, string> {
  return {
    ...operatorTestHeadersProvider(),
    'X-Purpose': purpose,
    ...(json ? { 'Content-Type': 'application/json' } : {}),
  };
}

/** Resolves the executable registry binding represented by a visual operator definition. */
export function operatorRuntimeRef(operator: OperatorDefinition): string {
  return operator.lowering?.operatorRef?.trim() || operator.operatorRef;
}

function resourceLowering(operator: OperatorDefinition): boolean {
  return operator.lowering?.mode === 'resource-descriptor' || operatorRuntimeRef(operator) === 'httpResource';
}

function resourceId(operator: OperatorDefinition, input: unknown): string {
  const configured = operator.lowering?.parameters?.resourceId;
  const supplied = recordValue(input)?.resourceId;
  const resolved = typeof configured === 'string' && configured.trim()
    ? configured.trim()
    : typeof supplied === 'string' ? supplied.trim() : '';
  if (!resolved) {
    throw new Error('Resource-backed operator tests require lowering.parameters.resourceId.');
  }
  return resolved;
}

function loweredOperatorInput(operator: OperatorDefinition, input: unknown): unknown {
  if (!resourceLowering(operator)) {
    return input;
  }
  const inputObject = recordValue(input);
  const flatParams = inputObject
    ? Object.fromEntries(Object.entries(inputObject).filter(([key]) => key !== 'resourceId'))
    : input;
  const params = inputObject && Object.prototype.hasOwnProperty.call(inputObject, 'params')
    ? inputObject.params
    : flatParams;
  const lowered: Record<string, unknown> = {
    resourceId: resourceId(operator, input),
    params: params ?? {},
  };
  for (const key of ['headerOverrides', 'authOverride', 'timeoutOverride']) {
    if (inputObject && Object.prototype.hasOwnProperty.call(inputObject, key)) {
      lowered[key] = inputObject[key];
    }
  }
  return lowered;
}

function expectedRuntimeOutput(operator: OperatorDefinition, expectedOutput: unknown): {
  path: string;
  value: unknown;
} {
  if (!resourceLowering(operator)) {
    return { path: '', value: expectedOutput };
  }
  const expectedObject = recordValue(expectedOutput);
  return {
    path: '/payload',
    value: expectedObject && Object.prototype.hasOwnProperty.call(expectedObject, 'payload')
      ? expectedObject.payload
      : expectedOutput,
  };
}

function boundedProtocolId(value: string, fallback: string): string {
  const normalized = value.trim().replace(/[^A-Za-z0-9._-]+/g, '-').replace(/^-+|-+$/g, '');
  return (normalized || fallback).slice(0, 80);
}

function canonicalJson(value: unknown): string {
  if (value === undefined) {
    return 'null';
  }
  if (value === null || typeof value !== 'object') {
    return JSON.stringify(value) ?? 'null';
  }
  if (Array.isArray(value)) {
    return `[${value.map(canonicalJson).join(',')}]`;
  }
  const entries = Object.entries(value as Record<string, unknown>)
    .filter(([, entry]) => entry !== undefined)
    .sort(([left], [right]) => left < right ? -1 : left > right ? 1 : 0);
  return `{${entries.map(([key, entry]) => `${JSON.stringify(key)}:${canonicalJson(entry)}`).join(',')}}`;
}

async function sha256Hex(value: unknown): Promise<string> {
  if (!globalThis.crypto?.subtle) {
    throw new Error('Governed fixture registration requires Web Crypto SHA-256 support.');
  }
  const digest = await globalThis.crypto.subtle.digest(
    'SHA-256',
    new TextEncoder().encode(canonicalJson(value)),
  );
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('');
}

function validateExecutableOperatorTarget(target: OperatorTestTargetDescriptor): void {
  if (!target.executionSupported) {
    throw new Error(target.certificationGaps[0] || 'The runtime binding cannot execute synchronously.');
  }
  if (target.testabilityClass === 'OPAQUE_RUNTIME') {
    throw new Error(target.certificationGaps[0]
      || 'The runtime binding has no controllable test boundary and cannot be executed safely.');
  }
  if (!['EXECUTABLE_UNIT', 'CONDITIONAL_TRANSPORT'].includes(target.testabilityClass)) {
    throw new Error(target.certificationGaps[0]
      || `The runtime binding testability class '${target.testabilityClass}' is not executable by this canvas.`);
  }
}

async function discoverOperatorTestTarget(
  operator: OperatorDefinition,
  purpose: 'TEST_EXECUTION' | 'TEST_FIXTURE_WRITE',
): Promise<OperatorTestTargetDescriptor> {
  const runtimeRef = operatorRuntimeRef(operator);
  const target = await readTestingJson<OperatorTestTargetDescriptor>(
    await sendRequest(`/api/testing/targets/operators/${encodeURIComponent(runtimeRef)}`, {
      headers: operatorTestingHeaders(purpose),
    }),
  );
  validateExecutableOperatorTarget(target);
  return target;
}

/**
 * Builds an inline exploratory fixture that executes the real operator binding. Resource-backed
 * operators replace only transport I/O; self-contained operators use a SPY around real code.
 */
export function buildOperatorTestExecutionRequest(
  operator: OperatorDefinition,
  target: OperatorTestTargetDescriptor,
  input: unknown,
  expectedOutput: unknown,
  transportResponse: unknown,
  caseRef: string,
): Record<string, unknown> {
  const runtimeRef = operatorRuntimeRef(operator);
  const resource = resourceLowering(operator);
  const expected = expectedRuntimeOutput(operator, expectedOutput);
  const selector = {
    graphPath: '/root',
    nodeId: 'subject',
    operatorRef: resource ? '' : runtimeRef,
    resourceRef: resource ? resourceId(operator, input) : '',
    functionRef: '',
    capabilities: [],
    tags: [],
    invocationKind: resource ? 'RESOURCE' : 'PRIMARY',
    attempts: [],
    occurrences: [],
    correlationKey: '',
    match: {
      canonicalInput: null,
      pathEquals: {},
      pathsExist: [],
      pathsAbsent: [],
      schema: {},
      correlationKey: '',
      boundedRegex: {},
    },
  };
  const behavior = resource ? {
    kind: 'RETURN',
    boundary: 'TRANSPORT',
    value: null,
    rawBody: JSON.stringify(transportResponse ?? expected.value),
    statusCode: 200,
    headers: { 'Content-Type': 'application/json' },
    errorCode: '',
    errorType: '',
    errorMessage: '',
    after: null,
    sequence: [],
    replayRef: '',
  } : {
    kind: 'SPY',
    boundary: 'NODE',
    value: null,
    rawBody: '',
    statusCode: null,
    headers: {},
    errorCode: '',
    errorType: '',
    errorMessage: '',
    after: null,
    sequence: [],
    replayRef: '',
  };
  const fixtureId = `canvas-${boundedProtocolId(runtimeRef, 'operator')}-${boundedProtocolId(caseRef, 'case')}`;
  return {
    schemaVersion: 'bloge.testOperatorExecutionRequest.v1',
    target: target.target,
    executionPurpose: 'OPERATOR_UNIT_TEST',
    input: loweredOperatorInput(operator, input),
    fixtureBundle: {
      schemaVersion: 'bloge.fixtureBundle.v1',
      fixtureBundleId: fixtureId,
      revision: 1,
      targetFingerprint: target.target.fingerprint,
      classification: 'INTERNAL',
      logicalClock: null,
      randomSeed: null,
      rules: [{
        schemaVersion: 'bloge.fixtureRule.v1',
        ruleId: resource ? 'subject-transport' : 'subject-spy',
        selector,
        behavior,
        consumption: {
          required: true,
          minUses: 1,
          maxUses: 1,
          onExhausted: 'FAIL',
          onUnmatched: 'FAIL',
        },
        schemaCheck: { mode: 'STRICT', waiverReason: '' },
      }],
      assertions: [{
        scope: 'OUTPUT_PATH',
        nodeId: 'subject',
        path: expected.path,
        operator: 'EQUALS',
        expected: expected.value,
        numericTolerance: null,
      }],
      metadata: {
        source: 'author-canvas',
        visualOperatorRef: operator.operatorRef,
      },
    },
    fixtureBundleRef: null,
    verbosity: 'FULL',
    metadata: {
      suiteRef: `canvas:${operator.operatorRef}`,
      caseRef,
      visualOperatorRef: operator.operatorRef,
    },
  };
}

/** Discovers and executes one operator table row through the real micro-graph testing kernel. */
export async function runOperatorTestCase(
  operator: OperatorDefinition,
  input: unknown,
  expectedOutput: unknown,
  transportResponse: unknown,
  caseRef: string,
): Promise<OperatorTestCaseRun> {
  const runtimeRef = operatorRuntimeRef(operator);
  const target = await discoverOperatorTestTarget(operator, 'TEST_EXECUTION');
  const request = buildOperatorTestExecutionRequest(
    operator, target, input, expectedOutput, transportResponse, caseRef,
  );
  const response = await readTestingJson<OperatorTestExecutionResponse>(
    await sendRequest(`/api/testing/targets/operators/${encodeURIComponent(runtimeRef)}/executions`, {
      method: 'POST',
      headers: operatorTestingHeaders('TEST_EXECUTION', true),
      body: JSON.stringify(request),
    }),
  );
  return { target, response };
}

/** Registers a content-addressed immutable fixture revision, then executes the operator by stored ref. */
export async function governOperatorTestCase(
  operator: OperatorDefinition,
  input: unknown,
  expectedOutput: unknown,
  transportResponse: unknown,
  caseRef: string,
): Promise<OperatorTestCaseRun> {
  const runtimeRef = operatorRuntimeRef(operator);
  const target = await discoverOperatorTestTarget(operator, 'TEST_FIXTURE_WRITE');
  const inlineRequest = buildOperatorTestExecutionRequest(
    operator, target, input, expectedOutput, transportResponse, caseRef,
  );
  const inlineFixture = inlineRequest.fixtureBundle as Record<string, unknown>;
  const contentDigest = await sha256Hex({
    target: target.target,
    input: inlineRequest.input,
    fixture: { ...inlineFixture, fixtureBundleId: '' },
    metadata: inlineRequest.metadata,
  });
  const fixtureBundleId = [
    'canvas',
    boundedProtocolId(runtimeRef, 'operator').slice(0, 32),
    boundedProtocolId(caseRef, 'case').slice(0, 32),
    contentDigest,
  ].join('-');
  const fixtureBundle = { ...inlineFixture, fixtureBundleId, revision: 1 };
  const storedFixture = await readTestingJson<StoredOperatorTestFixture>(
    await sendRequest(`/api/testing/fixture-bundles/${encodeURIComponent(fixtureBundleId)}`, {
      method: 'PUT',
      headers: operatorTestingHeaders('TEST_FIXTURE_WRITE', true),
      body: JSON.stringify({
        schemaVersion: 'bloge.fixtureBundleRegistrationRequest.v1',
        target: target.target,
        fixtureBundle,
      }),
    }),
  );
  if (storedFixture.fixtureBundleId !== fixtureBundleId
      || storedFixture.revision !== 1
      || !storedFixture.fingerprint?.trim()) {
    throw new Error('Fixture registry returned an inconsistent stored fixture identity.');
  }
  const storedRequest = {
    ...inlineRequest,
    fixtureBundle: null,
    fixtureBundleRef: {
      fixtureBundleId: storedFixture.fixtureBundleId,
      revision: storedFixture.revision,
      fingerprint: storedFixture.fingerprint,
    },
  };
  const response = await readTestingJson<OperatorTestExecutionResponse>(
    await sendRequest(`/api/testing/targets/operators/${encodeURIComponent(runtimeRef)}/executions`, {
      method: 'POST',
      headers: operatorTestingHeaders('TEST_EXECUTION', true),
      body: JSON.stringify(storedRequest),
    }),
  );
  return { target, response, storedFixture };
}

/** Validates a transient visual graph draft through the server-authoritative schema/readiness gate. */
export async function validateDraft(draft: GraphDraft): Promise<VisualValidationResult> {
  return readJson<VisualValidationResult>(
    await sendRequest('/api/visual/drafts/validate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(draft),
    }),
  );
}

/** Loads one stored graph draft for authoring deep links. */
export async function fetchGraphDraft(draftId: string): Promise<GraphDraft> {
  return readJson<GraphDraft>(
    await sendRequest(`/api/visual/drafts/${encodeURIComponent(draftId)}`),
  );
}

/** Loads the latest governance decision and snapshot freshness for a stored draft. */
export async function fetchGovernanceGateView(draftId: string): Promise<GovernanceGateView> {
  return readJson<GovernanceGateView>(
    await sendRequest(`/api/visual/governance-gates/drafts/${encodeURIComponent(draftId)}`),
  );
}

/** Loads one run record so a run deep link can recover its draft and node context. */
export async function fetchVisualGraphRun(runId: string): Promise<VisualGraphRunRecord> {
  return readJson<VisualGraphRunRecord>(
    await sendRequest(`/api/visual/runs/${encodeURIComponent(runId)}`),
  );
}

/** Projects existing BLOGE DSL into an editable visual graph draft without persisting it. */
export async function previewDslImport(request: DslImportPreviewRequest): Promise<DslVisualProjection> {
  return readJsonMutation<DslVisualProjection>(
    await sendRequest('/api/visual/dsl-imports/preview', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    }),
  );
}

/** Checks whether generated DSL is safe enough to overwrite its source file. */
export async function checkDslRewriteGate(request: DslImportPreviewRequest): Promise<DslRewriteGateResult> {
  return readJsonMutation<DslRewriteGateResult>(
    await sendRequest('/api/visual/dsl-imports/rewrite-gate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    }),
  );
}

/** Assesses multiple BLOGE DSL sources against the same schema-neutral catalog view. */
export async function batchReportDslImports(request: DslImportBatchReportRequest): Promise<DslImportBatchReport> {
  return readJsonMutation<DslImportBatchReport>(
    await sendRequest('/api/visual/dsl-imports/batch-report', {
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
    await sendRequest(`/api/visual/dsl-imports/batch-commit?${query.toString()}`, {
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
    await sendRequest(`/api/visual/dsl-imports/commit?${query.toString()}`, {
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
    await sendRequest('/api/visual/connections/check', {
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
    await sendRequest('/api/visual/connections/candidates', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    }),
  );
}
