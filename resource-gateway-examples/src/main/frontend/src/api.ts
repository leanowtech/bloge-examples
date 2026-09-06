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
  OperatorTestSuiteCaseInput,
  OperatorTestSuiteExecutionResponse,
  OperatorTestSuiteRun,
  OperatorTestTargetDescriptor,
  StoredOperatorTestFixture,
  StoredOperatorTestSuite,
  SimulationRequest,
  SimulationResponse,
  ScenarioRehearsalBatchItemPage,
  ScenarioRehearsalBatchItemAttemptTimeline,
  ScenarioRehearsalBatchJobPage,
  ScenarioRehearsalBatchWorkbookSeed,
  ScenarioRehearsalRemediationApproval,
  ScenarioRehearsalRemediationApprovalCommand,
  ScenarioRehearsalRemediationComparison,
  ScenarioRehearsalRemediationLineage,
  ScenarioRehearsalRemediationPlan,
  ScenarioRehearsalRemediationPreviewRequest,
  ScenarioRehearsalRemediationReceipt,
  ScenarioRehearsalRemediationSubmitCommand,
  ScenarioRehearsalWorkbookSeed,
  ToolStudioIntegrationEnvelope,
  VisualValidationResult,
  VisualGraphRunRecord,
  VisualAuthoringFunctionTestDraft,
  VisualAuthoringFunctionTestRunEvidence,
  VisualAuthoringTestDraftGate,
  VisualAuthoringTestEvidenceView,
  VisualAuthoringFixtureReceipt,
  VisualAuthoringFixtureSaveRequest,
  VisualAuthoringOperatorTestDraft,
  VisualAuthoringOperatorTestRunEvidence,
  VisualAuthoringFactProjection,
  VisualFunctionTestSuite,
  VisualLibraryAuthoringCommitResult,
  VisualLibraryAuthoringCatalogs,
  VisualLibraryAuthoringCompileResult,
  VisualLibraryAuthoringDocument,
  VisualLibraryAuthoringDraft,
  VisualLibraryAuthoringHomeContext,
  VisualOperatorContractTestSuite,
  VisualSampleInferenceDecision,
  VisualSampleInferenceRequest,
  VisualSampleInferenceResult,
} from './types';
import type {
  ContractCompatibilityReport,
  ScenarioDraftSet,
  ScenarioContractProjection,
  StoredScenarioDraftSet,
  StoredScenarioPublication,
} from './contract-scenario/domain';
import type {
  ReferenceCandidate,
  ReferencePage,
  ReferenceQuery,
  ReferenceResolveResult,
} from './shared/reference-picker/types';
import type {
  ScenarioImportExecutionRequest,
  ScenarioMaterializationResult,
} from './contract-scenario/import/scenarioImportModel';
import type {
  TableSuiteRunBatch,
  TableSuiteRunCommand,
  TableSuiteRunDelta,
} from './contract-scenario/table/tableSuiteRunModel';
import type {
  ScenarioBulkEditCommand,
  ScenarioBulkEditResult,
  ScenarioTablePage,
  ScenarioTablePageQuery,
} from './contract-scenario/table/scenarioTableScaleModel';
import type {
  WorkspaceForkCommand,
  WorkspaceForkReceipt,
} from './author/workspace/workspaceSeed';
import type {
  BusinessMirrorCompilationReceipt,
  BusinessMirrorDomainEvidencePortfolio,
  BusinessMirrorEvidenceOwnerTask,
  BusinessMirrorEvidenceProjectionResult,
  BusinessMirrorPackageDraft,
  BusinessMirrorPackageEvidenceIndex,
  BusinessMirrorPackagePage,
  BusinessMirrorPackageSaveReceipt,
  LegacyGraphPackageProjection,
  LegacyGraphPackageProjectionCatalog,
} from './business-mirror/domain';
import type {
  AuthoringLinkDescriptor,
  ExactBusinessMirrorGraphSubject,
} from './shared/workspace-routing/businessMirrorAuthorLink';

/** Structured transport failure that lets optional product surfaces distinguish capability absence. */
export class BlogeApiRequestError extends Error {
  constructor(
    readonly status: number,
    readonly detail: string,
  ) {
    super(`Request failed: ${status} ${detail}`);
    this.name = 'BlogeApiRequestError';
  }
}

async function readJson<T>(response: Response): Promise<T> {
  if (!response.ok) {
    throw new BlogeApiRequestError(response.status, response.statusText);
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
    throw new BlogeApiRequestError(response.status, detail);
  }
  if (!payload) {
    throw new BlogeApiRequestError(response.status, 'empty response');
  }
  return payload;
}

async function readTestingJson<T>(response: Response): Promise<T> {
  const payload = await readJsonBody<T & {
    code?: string;
    title?: string;
    detail?: string;
    details?: { diagnosticCodes?: string[] };
    diagnostics?: Array<{ message?: string; code?: string }>;
  }>(response);
  if (!response.ok) {
    const firstDiagnostic = payload?.diagnostics?.find((diagnostic) => diagnostic.message || diagnostic.code);
    const diagnosticCodes = payload?.details?.diagnosticCodes?.filter(Boolean).join(', ');
    const detail = payload?.detail || firstDiagnostic?.message || firstDiagnostic?.code
      || (payload?.title && diagnosticCodes ? `${payload.title} (${diagnosticCodes})` : payload?.title)
      || payload?.code || response.statusText;
    throw new BlogeApiRequestError(response.status, detail);
  }
  if (!payload) {
    throw new BlogeApiRequestError(response.status, 'empty response');
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

/** Credential slots keep workload reads, Owner actions, and independent review visibly separate. */
export type RehearsalRemediationCredentialSlot = 'READ' | 'OWNER' | 'INDEPENDENT_REVIEWER';

/** Log-safe credential projection supplied by a VSCode or enterprise host. */
export interface RehearsalRemediationCredential {
  headers: Record<string, string>;
  principalLabel: string;
  expiresAt?: string;
}

/** Host-owned resolver for short-lived human remediation credentials. */
export type RehearsalRemediationCredentialsProvider = (
  slot: RehearsalRemediationCredentialSlot,
) => RehearsalRemediationCredential | null;

/** UI-safe credential availability; authorization remains exclusively server-enforced. */
export interface RehearsalRemediationCredentialStatus {
  slot: RehearsalRemediationCredentialSlot;
  configured: boolean;
  principalLabel: string;
  expiresAt: string;
}

const emptyRehearsalRemediationCredentialsProvider:
RehearsalRemediationCredentialsProvider = () => null;
let rehearsalRemediationCredentialsProvider = emptyRehearsalRemediationCredentialsProvider;

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

/** Builds authenticated integration headers while keeping purpose server-contract owned. */
export function integrationRequestHeaders(
  purpose: string,
  extra: Record<string, string> = {},
): Record<string, string> {
  return {
    Accept: 'application/json',
    ...operatorTestHeadersProvider(),
    ...extra,
    'X-Purpose': purpose,
  };
}

/**
 * Installs role-separated, short-lived credentials for reviewed remediation.
 *
 * The standalone demo intentionally installs none. Authenticated hosts should resolve the actual
 * identity immediately before each request and must never expose bearer material in the label.
 */
export function setRehearsalRemediationCredentialsProvider(
  provider: RehearsalRemediationCredentialsProvider,
): void {
  rehearsalRemediationCredentialsProvider = provider;
}

/** Restores the fail-closed standalone behavior for all reviewed remediation roles. */
export function resetRehearsalRemediationCredentialsProvider(): void {
  rehearsalRemediationCredentialsProvider = emptyRehearsalRemediationCredentialsProvider;
}

/** Returns only log-safe availability metadata for one host credential slot. */
export function getRehearsalRemediationCredentialStatus(
  slot: RehearsalRemediationCredentialSlot,
): RehearsalRemediationCredentialStatus {
  try {
    const credential = rehearsalRemediationCredentialsProvider(slot);
    const principalLabel = credential?.principalLabel?.trim() ?? '';
    return {
      slot,
      configured: credential !== null && principalLabel.length > 0,
      principalLabel,
      expiresAt: credential?.expiresAt?.trim() ?? '',
    };
  } catch {
    return {
      slot,
      configured: false,
      principalLabel: '',
      expiresAt: '',
    };
  }
}

function sendRequest(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  return blogeApiTransport(input, init);
}

function businessMirrorHeaders(extra: Record<string, string> = {}): Record<string, string> {
  return {
    Accept: 'application/json',
    ...operatorTestHeadersProvider(),
    ...extra,
    'X-Purpose': 'BUSINESS_MIRROR_AUTHORING',
  };
}

/** Searches one authorized, metadata-only reference kind for Business Mirror authoring. */
export async function fetchBusinessMirrorReferenceCandidates(
  kind: string,
  request: ReferenceQuery,
  signal: AbortSignal,
): Promise<ReferencePage> {
  const query = new URLSearchParams({
    kind,
    query: request.query,
    cursor: request.cursor ?? '',
    limit: String(request.limit),
    compatibleWith: 'COMPATIBLE',
  });
  try {
    return await readTestingJson(await sendRequest(`/api/visual/reference-candidates?${query}`, {
      headers: businessMirrorHeaders(),
      signal,
    }));
  } catch (failure) {
    throw mapReferenceCandidateFailure(failure);
  }
}

/** Re-resolves an untrusted search result before binding it into a Package draft. */
export async function resolveBusinessMirrorReferenceCandidate(
  candidate: ReferenceCandidate,
  intendedUse: string,
): Promise<ReferenceResolveResult> {
  return readTestingJson(await sendRequest('/api/visual/reference-candidates:resolve', {
    method: 'POST',
    headers: businessMirrorHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({
      schemaVersion: 'bloge.referenceResolveCommand.v1',
      kind: candidate.kind,
      id: candidate.id,
      revision: candidate.revision,
      fingerprint: candidate.fingerprint,
      intendedUse,
    }),
  }));
}

/** Resolves an exact Business Mirror Graph into an allowlisted Author Compose descriptor. */
export async function resolveBusinessMirrorAuthorLink(
  subject: ExactBusinessMirrorGraphSubject,
): Promise<AuthoringLinkDescriptor> {
  return readTestingJson(await sendRequest('/api/visual/authoring-links:resolve', {
    method: 'POST',
    headers: businessMirrorHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({
      schemaVersion: 'bloge.authoringLinkResolveRequest.v1',
      subjectRef: {
        kind: 'BUSINESS_MIRROR_LEGACY_GRAPH',
        id: subject.graphRef.id,
        revision: subject.graphRef.revision,
        fingerprint: subject.graphRef.fingerprint,
      },
      intent: 'EDIT_TOPOLOGY',
      returnCoordinate: {
        route: 'business-mirror',
        packageId: subject.packageId,
        task: 'capabilities',
        anchor: `graph:${subject.graphRef.id}`,
      },
    }),
  }));
}

/** Loads the complete bounded Legacy Graph migration catalog for the verified enterprise Scope. */
export async function fetchBusinessMirrorLegacyCatalog(): Promise<LegacyGraphPackageProjectionCatalog> {
  return readTestingJson(await sendRequest('/api/business-mirror/legacy-graphs', {
    headers: businessMirrorHeaders(),
  }));
}

/** Loads one exact Legacy Graph migration projection without mutating authoring state. */
export async function fetchBusinessMirrorLegacyProjection(
  graphName: string,
): Promise<LegacyGraphPackageProjection> {
  return readTestingJson(await sendRequest(
    `/api/business-mirror/legacy-graphs/${encodeURIComponent(graphName)}`,
    { headers: businessMirrorHeaders() },
  ));
}

/** Lists current durable Package drafts in the verified enterprise Scope. */
export async function fetchBusinessMirrorPackages(limit = 200): Promise<BusinessMirrorPackagePage> {
  return readTestingJson(await sendRequest(`/api/business-mirror/packages?limit=${limit}`, {
    headers: businessMirrorHeaders(),
  }));
}

/** Imports one Legacy Graph projection through the durable Package authoring boundary. */
export async function importBusinessMirrorLegacyPackage(
  graphName: string,
  idempotencyKey: string,
): Promise<BusinessMirrorPackageSaveReceipt> {
  return readTestingJson(await sendRequest(
    `/api/business-mirror/legacy-graphs/${encodeURIComponent(graphName)}/packages`,
    {
      method: 'POST',
      headers: businessMirrorHeaders({ 'Idempotency-Key': idempotencyKey }),
    },
  ));
}

/** Saves guided Package edits using the server-owned optimistic revision boundary. */
export async function saveBusinessMirrorPackage(
  draft: BusinessMirrorPackageDraft,
  idempotencyKey: string,
): Promise<BusinessMirrorPackageSaveReceipt> {
  return readTestingJson(await sendRequest(
    `/api/business-mirror/packages/${encodeURIComponent(draft.packageId)}`
      + `?expectedRevision=${draft.revision}`,
    {
      method: 'PUT',
      headers: businessMirrorHeaders({
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
      }),
      body: JSON.stringify(draft),
    },
  ));
}

/** Compiles one exact durable Package revision and returns its authoritative readiness report. */
export async function compileBusinessMirrorPackage(
  packageId: string,
  sourceRevision: number,
  idempotencyKey: string,
): Promise<BusinessMirrorCompilationReceipt> {
  return readTestingJson(await sendRequest(
    `/api/business-mirror/packages/${encodeURIComponent(packageId)}`
      + `/compile?sourceRevision=${sourceRevision}`,
    {
      method: 'POST',
      headers: businessMirrorHeaders({ 'Idempotency-Key': idempotencyKey }),
    },
  ));
}

/** Loads the immutable current Package evidence projection for one exact enterprise Scope. */
export async function fetchBusinessMirrorPackageEvidence(
  packageId: string,
): Promise<BusinessMirrorPackageEvidenceIndex> {
  return readTestingJson(await sendRequest(
    `/api/business-mirror/domain-capability-packages/${encodeURIComponent(packageId)}/evidence-index`,
    { headers: businessMirrorHeaders() },
  ));
}

/** Reprojects one Package from the latest durable compilation and Fidelity source cuts. */
export async function refreshBusinessMirrorPackageEvidence(
  packageId: string,
): Promise<BusinessMirrorEvidenceProjectionResult> {
  return readTestingJson(await sendRequest(
    `/api/business-mirror/domain-capability-packages/${encodeURIComponent(packageId)}`
      + '/evidence-index/refresh',
    { method: 'POST', headers: businessMirrorHeaders() },
  ));
}

/** Loads a bounded domain Portfolio without flattening evidence into a scalar score. */
export async function fetchBusinessMirrorDomainEvidencePortfolio(
  domainId: string,
  limit = 100,
): Promise<BusinessMirrorDomainEvidencePortfolio> {
  const query = new URLSearchParams({ limit: String(limit) });
  return readTestingJson(await sendRequest(
    `/api/business-mirror/domain-portfolios/${encodeURIComponent(domainId)}?${query}`,
    { headers: businessMirrorHeaders() },
  ));
}

/** Acknowledges one optimistic-concurrency-fenced owner task. */
export async function acknowledgeBusinessMirrorEvidenceTask(
  taskId: string,
  expectedVersion: number,
): Promise<BusinessMirrorEvidenceOwnerTask> {
  return readTestingJson(await sendRequest(
    `/api/business-mirror/evidence-owner-tasks/${encodeURIComponent(taskId)}`
      + `/acknowledge?expectedVersion=${expectedVersion}`,
    { method: 'POST', headers: businessMirrorHeaders() },
  ));
}

function mapReferenceCandidateFailure(failure: unknown): unknown {
  if (failure instanceof BlogeApiRequestError && failure.status === 503) {
    return Object.assign(new Error(failure.detail), {
      status: 'unavailable' as const,
      retryable: true,
    });
  }
  return failure;
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

/** Lists durable progressive-library authoring drafts. */
export async function fetchLibraryAuthoringDrafts(): Promise<VisualLibraryAuthoringDraft[]> {
  return readJson<VisualLibraryAuthoringDraft[]>(
    await sendRequest('/admin/visual-operator-library-authoring/drafts', {
      headers: operatorTestingHeaders('TEST_SUITE_READ'),
    }),
  );
}

/** Reads the authenticated actor and enterprise scope used by Library Home projections. */
export async function fetchLibraryAuthoringContext(): Promise<VisualLibraryAuthoringHomeContext> {
  return readJson<VisualLibraryAuthoringHomeContext>(
    await sendRequest('/admin/visual-operator-library-authoring/drafts/context', {
      headers: operatorTestingHeaders('TEST_SUITE_READ'),
    }),
  );
}

/** Reads exact Workbench limits and optional runtime feature availability. */
export async function fetchLibraryAuthoringCatalogs(): Promise<VisualLibraryAuthoringCatalogs> {
  return readJsonMutation<VisualLibraryAuthoringCatalogs>(
    await sendRequest('/admin/visual-operator-library-authoring/catalogs'),
  );
}

export type LibraryAuthoringDiscoveryMode =
  | 'runtime'
  | 'dsl'
  | 'capability-catalog'
  | 'asyncapi'
  | 'openapi';

/** Discovers existing assets through one source-neutral facts and runtime-parity protocol. */
export async function discoverLibraryAuthoringAssets(
  mode: LibraryAuthoringDiscoveryMode,
  request: Record<string, unknown> = {},
): Promise<VisualAuthoringFactProjection> {
  const base = '/admin/visual-operator-library-authoring/discovery';
  if (mode === 'runtime') {
    return readJsonMutation<VisualAuthoringFactProjection>(
      await sendRequest(`${base}/runtime`),
    );
  }
  return readJsonMutation<VisualAuthoringFactProjection>(
    await sendRequest(`${base}/${mode}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    }),
  );
}

/** Loads one exact current progressive-library authoring draft. */
export async function fetchLibraryAuthoringDraft(
  draftId: string,
): Promise<VisualLibraryAuthoringDraft> {
  return readJsonMutation<VisualLibraryAuthoringDraft>(
    await sendRequest(
      `/admin/visual-operator-library-authoring/drafts/${encodeURIComponent(draftId)}`,
      { headers: operatorTestingHeaders('TEST_SUITE_READ') },
    ),
  );
}

/** Loads one immutable historical authoring revision without advancing the mutable draft head. */
export async function fetchLibraryAuthoringDraftRevision(
  draftId: string,
  revision: number,
): Promise<VisualLibraryAuthoringDraft> {
  return readJsonMutation<VisualLibraryAuthoringDraft>(
    await sendRequest(
      `/admin/visual-operator-library-authoring/drafts/${encodeURIComponent(draftId)}`
      + `/revisions/${Math.max(1, revision)}`,
      { headers: operatorTestingHeaders('TEST_SUITE_READ') },
    ),
  );
}

/** Stores the next source revision under an If-Match optimistic concurrency fence. */
export async function saveLibraryAuthoringDraft(
  draftId: string,
  expectedRevision: number,
  document: VisualLibraryAuthoringDocument,
  sourceMode: VisualLibraryAuthoringDraft['sourceMode'] = 'QUICK',
): Promise<VisualLibraryAuthoringDraft> {
  return readJsonMutation<VisualLibraryAuthoringDraft>(
    await sendRequest(
      `/admin/visual-operator-library-authoring/drafts/${encodeURIComponent(draftId)}`,
      {
        method: 'PUT',
        headers: {
          ...operatorTestingHeaders('TEST_SUITE_WRITE', true),
          'If-Match': `"${Math.max(0, expectedRevision)}"`,
        },
        body: JSON.stringify({
          sourceMode,
          document,
        }),
      },
    ),
  );
}

/** Infers payload-free observed facts for one exact persisted operator port. */
export async function inferLibraryAuthoringSamples(
  draftId: string,
  revision: number,
  request: VisualSampleInferenceRequest,
): Promise<VisualSampleInferenceResult> {
  return readJsonMutation<VisualSampleInferenceResult>(
    await sendRequest(
      `/admin/visual-operator-library-authoring/drafts/${encodeURIComponent(draftId)}/infer/samples`,
      {
        method: 'POST',
        headers: {
          ...operatorTestingHeaders('TEST_SUITE_READ', true),
          'If-Match': `"${Math.max(0, revision)}"`,
        },
        body: JSON.stringify(request),
      },
    ),
  );
}

/** Replays the exact sample request and atomically promotes all explicit decisions. */
export async function applyLibraryAuthoringSamples(
  draftId: string,
  revision: number,
  inference: VisualSampleInferenceRequest,
  evidenceFingerprint: string,
  decisions: VisualSampleInferenceDecision[],
): Promise<VisualLibraryAuthoringDraft> {
  return readJsonMutation<VisualLibraryAuthoringDraft>(
    await sendRequest(
      `/admin/visual-operator-library-authoring/drafts/${encodeURIComponent(draftId)}/infer/samples/apply`,
      {
        method: 'POST',
        headers: {
          ...operatorTestingHeaders('TEST_SUITE_WRITE', true),
          'If-Match': `"${Math.max(0, revision)}"`,
        },
        body: JSON.stringify({
          schemaVersion: 'bloge.visualSampleInferenceApplyRequest.v1',
          inference,
          evidenceFingerprint,
          decisions,
        }),
      },
    ),
  );
}

/** Generates an editable schema-contract suite from one exact draft operator. */
export async function draftLibraryAuthoringOperatorTest(
  draftId: string,
  revision: number,
  operatorRef: string,
): Promise<VisualAuthoringOperatorTestDraft> {
  return readJsonMutation<VisualAuthoringOperatorTestDraft>(
    await sendRequest(
      `/admin/visual-operator-library-authoring/drafts/${encodeURIComponent(draftId)}/tests/operators/draft`,
      {
        method: 'POST',
        headers: {
          ...operatorTestingHeaders('TEST_SUITE_READ', true),
          'If-Match': `"${Math.max(0, revision)}"`,
        },
        body: JSON.stringify({
          schemaVersion: 'bloge.visualAuthoringOperatorTestDraftRequest.v1',
          draft: {
            schemaVersion: 'bloge.visualOperatorContractTestDraftRequest.v1',
            operatorRef,
            caseName: 'generated contract case',
            includeOptionalPorts: true,
            inputOverrides: {},
            configOverrides: {},
            mockedOutputOverrides: {},
          },
        }),
      },
    ),
  );
}

/** Runs an ephemeral operator suite against the exact uncommitted canonical definition. */
export async function runLibraryAuthoringOperatorTest(
  draftId: string,
  revision: number,
  suite: VisualOperatorContractTestSuite,
): Promise<VisualAuthoringOperatorTestRunEvidence> {
  return readJsonMutation<VisualAuthoringOperatorTestRunEvidence>(
    await sendRequest(
      `/admin/visual-operator-library-authoring/drafts/${encodeURIComponent(draftId)}/tests/operators/run`,
      {
        method: 'POST',
        headers: {
          ...operatorTestingHeaders('TEST_EXECUTION', true),
          'If-Match': `"${Math.max(0, revision)}"`,
        },
        body: JSON.stringify({
          schemaVersion: 'bloge.visualAuthoringOperatorTestRunRequest.v1',
          suite,
        }),
      },
    ),
  );
}

/** Generates a type-oriented starter suite and reports runtime binding status. */
export async function draftLibraryAuthoringFunctionTest(
  draftId: string,
  revision: number,
  functionRef: string,
): Promise<VisualAuthoringFunctionTestDraft> {
  return readJsonMutation<VisualAuthoringFunctionTestDraft>(
    await sendRequest(
      `/admin/visual-operator-library-authoring/drafts/${encodeURIComponent(draftId)}/tests/functions/draft`,
      {
        method: 'POST',
        headers: {
          ...operatorTestingHeaders('TEST_SUITE_READ', true),
          'If-Match': `"${Math.max(0, revision)}"`,
        },
        body: JSON.stringify({
          schemaVersion: 'bloge.visualAuthoringFunctionTestDraftRequest.v1',
          functionRef,
        }),
      },
    ),
  );
}

/** Runs only a pure, service-free function found in the bounded BLOGE runtime inventory. */
export async function runLibraryAuthoringFunctionTest(
  draftId: string,
  revision: number,
  suite: VisualFunctionTestSuite,
): Promise<VisualAuthoringFunctionTestRunEvidence> {
  return readJsonMutation<VisualAuthoringFunctionTestRunEvidence>(
    await sendRequest(
      `/admin/visual-operator-library-authoring/drafts/${encodeURIComponent(draftId)}/tests/functions/run`,
      {
        method: 'POST',
        headers: {
          ...operatorTestingHeaders('TEST_EXECUTION', true),
          'If-Match': `"${Math.max(0, revision)}"`,
        },
        body: JSON.stringify({
          schemaVersion: 'bloge.visualAuthoringFunctionTestRunRequest.v1',
          suite,
        }),
      },
    ),
  );
}

/** Reads one immutable signed run summary and recalculates freshness against the live draft. */
export async function fetchLibraryAuthoringTestEvidence(
  draftId: string,
  runId: string,
): Promise<VisualAuthoringTestEvidenceView> {
  return readTestingJson<VisualAuthoringTestEvidenceView>(
    await sendRequest(
      `/admin/visual-operator-library-authoring/drafts/${encodeURIComponent(draftId)}`
      + `/tests/evidence/${encodeURIComponent(runId)}`,
      { headers: operatorTestingHeaders('TEST_SUITE_READ') },
    ),
  );
}

/** Evaluates the current draft's conservative TEST_EVIDENCED baseline. */
export async function fetchLibraryAuthoringTestGate(
  draftId: string,
): Promise<VisualAuthoringTestDraftGate> {
  return readTestingJson<VisualAuthoringTestDraftGate>(
    await sendRequest(
      `/admin/visual-operator-library-authoring/drafts/${encodeURIComponent(draftId)}/tests/gate`,
      { headers: operatorTestingHeaders('TEST_SUITE_READ') },
    ),
  );
}

/** Persists one explicit, classified and redacted fixture against an exact draft revision. */
export async function saveLibraryAuthoringFixture(
  draftId: string,
  revision: number,
  request: VisualAuthoringFixtureSaveRequest,
): Promise<VisualAuthoringFixtureReceipt> {
  return readTestingJson<VisualAuthoringFixtureReceipt>(
    await sendRequest(
      `/admin/visual-operator-library-authoring/drafts/${encodeURIComponent(draftId)}/fixtures`,
      {
        method: 'POST',
        headers: {
          ...operatorTestingHeaders('TEST_FIXTURE_WRITE', true),
          'If-Match': `"${Math.max(0, revision)}"`,
        },
        body: JSON.stringify(request),
      },
    ),
  );
}

/** Compiles the exact persisted revision against the current target catalog. */
export async function previewLibraryAuthoringDraft(
  draftId: string,
  revision: number,
): Promise<VisualLibraryAuthoringCompileResult> {
  return readJsonMutation<VisualLibraryAuthoringCompileResult>(
    await sendRequest(
      `/admin/visual-operator-library-authoring/drafts/${encodeURIComponent(draftId)}/preview`,
      {
        method: 'POST',
        headers: {
          ...operatorTestingHeaders('TEST_SUITE_READ'),
          'If-Match': `"${Math.max(0, revision)}"`,
        },
      },
    ),
  );
}

/** Commits only the exact authoritative preview into the Design Catalog. */
export async function commitLibraryAuthoringDraft(
  draftId: string,
  revision: number,
  preview: VisualLibraryAuthoringCompileResult,
  reason: string,
): Promise<VisualLibraryAuthoringCommitResult> {
  return readJsonMutation<VisualLibraryAuthoringCommitResult>(
    await sendRequest(
      `/admin/visual-operator-library-authoring/drafts/${encodeURIComponent(draftId)}/commit`,
      {
        method: 'POST',
        headers: {
          ...operatorTestingHeaders('TEST_SCENARIO_PUBLISH', true),
          'If-Match': `"${Math.max(0, revision)}"`,
        },
        body: JSON.stringify({
          authoringFingerprint: preview.authoringFingerprint,
          compileFingerprint: preview.compileFingerprint,
          catalogFingerprint: preview.catalogFingerprint,
          canonicalFingerprint: preview.canonicalFingerprint,
          targetRevision: preview.diff?.baseRevision ?? 0,
          reason,
        }),
      },
    ),
  );
}

/** Runs a mock simulation of the current draft. */
export async function simulate(request: SimulationRequest): Promise<SimulationResponse> {
  const requiresGovernedMaterialRead = Object.values(request.fixtures ?? {})
    .some((fixture) => Boolean(fixture?.governedRef));
  return readJson<SimulationResponse>(
    await sendRequest('/api/visual/graphs/simulate', {
      method: 'POST',
      headers: requiresGovernedMaterialRead
        ? integrationRequestHeaders('CORRECTNESS_FIXTURE_MATERIAL_READ', {
          'Content-Type': 'application/json',
        })
        : { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    }),
  );
}

/** Stores the next exact mutable Scenario revision through optimistic concurrency. */
export async function saveScenarioDraftSet(
  draftSet: ScenarioDraftSet,
): Promise<StoredScenarioDraftSet> {
  const id = encodeURIComponent(draftSet.scenarioDraftSetId);
  return readTestingJson<StoredScenarioDraftSet>(
    await sendRequest(
      `/api/visual/scenario-draft-sets/${id}?expectedRevision=${draftSet.revision}`,
      {
        method: 'PUT',
        headers: operatorTestingHeaders('TEST_SUITE_WRITE', true),
        body: JSON.stringify(draftSet),
      },
    ),
  );
}

/** Queries one source-bound bounded Matrix page without downloading the full Scenario payload. */
export async function queryScenarioTablePage(
  scenarioDraftSetId: string,
  query: ScenarioTablePageQuery,
): Promise<ScenarioTablePage> {
  return readTestingJson<ScenarioTablePage>(
    await sendRequest(
      `/api/visual/scenario-draft-sets/${encodeURIComponent(scenarioDraftSetId)}/matrix/query`,
      {
        method: 'POST',
        headers: operatorTestingHeaders('TEST_SUITE_READ', true),
        body: JSON.stringify(query),
      },
    ),
  );
}

/** Commits source- and row-fenced Matrix edits as one all-or-nothing Scenario revision. */
export async function bulkEditScenarioTable(
  scenarioDraftSetId: string,
  command: ScenarioBulkEditCommand,
): Promise<ScenarioBulkEditResult> {
  return readTestingJson<ScenarioBulkEditResult>(
    await sendRequest(
      `/api/visual/scenario-draft-sets/${encodeURIComponent(scenarioDraftSetId)}/matrix/bulk-edits`,
      {
        method: 'POST',
        headers: operatorTestingHeaders('TEST_SUITE_WRITE', true),
        body: JSON.stringify(command),
      },
    ),
  );
}

/** Re-parses and materializes one exact CSV/JSON Scenario import on the server. */
export async function materializeScenarioImportOnServer(
  request: ScenarioImportExecutionRequest,
): Promise<ScenarioMaterializationResult> {
  return readTestingJson<ScenarioMaterializationResult>(
    await sendRequest('/api/visual/scenario-imports/materialize', {
      method: 'POST',
      headers: operatorTestingHeaders('TEST_SUITE_WRITE', true),
      body: JSON.stringify(request),
    }),
  );
}

/** Admits one exact server-authoritative Scenario Matrix selection. */
export async function submitTableSuiteRun(
  command: TableSuiteRunCommand,
): Promise<TableSuiteRunBatch> {
  return readTestingJson<TableSuiteRunBatch>(
    await sendRequest('/api/visual/table-suite-runs', {
      method: 'POST',
      headers: operatorTestingHeaders('TEST_EXECUTION', true),
      body: JSON.stringify(command),
    }),
  );
}

/** Restores a durable payload-free Matrix batch after refresh or navigation. */
export async function fetchTableSuiteRun(batchId: string): Promise<TableSuiteRunBatch> {
  return readTestingJson<TableSuiteRunBatch>(
    await sendRequest(`/api/visual/table-suite-runs/${encodeURIComponent(batchId)}`, {
      headers: operatorTestingHeaders('TEST_EXECUTION'),
    }),
  );
}

/** Polls only durable Matrix transitions newer than the observed revision. */
export async function fetchTableSuiteRunEvents(
  batchId: string,
  afterRevision: number,
): Promise<TableSuiteRunDelta> {
  const query = new URLSearchParams({ afterRevision: String(afterRevision) });
  return readTestingJson<TableSuiteRunDelta>(
    await sendRequest(`/api/visual/table-suite-runs/${encodeURIComponent(batchId)}/events?${query}`, {
      headers: operatorTestingHeaders('TEST_EXECUTION'),
    }),
  );
}

/** Requests cooperative cancellation without deleting completed row attempts. */
export async function cancelTableSuiteRun(batchId: string): Promise<TableSuiteRunBatch> {
  return readTestingJson<TableSuiteRunBatch>(
    await sendRequest(`/api/visual/table-suite-runs/${encodeURIComponent(batchId)}/cancel`, {
      method: 'POST',
      headers: operatorTestingHeaders('TEST_EXECUTION'),
    }),
  );
}

/** Appends attempts for failed rows while preserving their first failure. */
export async function retryFailedTableSuiteRun(batchId: string): Promise<TableSuiteRunBatch> {
  return readTestingJson<TableSuiteRunBatch>(
    await sendRequest(`/api/visual/table-suite-runs/${encodeURIComponent(batchId)}/retry-failed`, {
      method: 'POST',
      headers: operatorTestingHeaders('TEST_EXECUTION'),
    }),
  );
}

/** Loads the latest retained mutable Scenario revision in the authenticated scope. */
export async function fetchScenarioDraftSet(
  scenarioDraftSetId: string,
): Promise<StoredScenarioDraftSet> {
  return readTestingJson<StoredScenarioDraftSet>(
    await sendRequest(
      `/api/visual/scenario-draft-sets/${encodeURIComponent(scenarioDraftSetId)}`,
      { headers: operatorTestingHeaders('TEST_SUITE_READ') },
    ),
  );
}

/** Reads semantic Contract drift and exact Scenario impact for one retained revision. */
export async function fetchScenarioCompatibility(
  scenarioDraftSetId: string,
  revision: number,
): Promise<ContractCompatibilityReport> {
  const id = encodeURIComponent(scenarioDraftSetId);
  return readTestingJson<ContractCompatibilityReport>(
    await sendRequest(
      `/api/visual/scenario-draft-sets/${id}/compatibility?revision=${revision}`,
      { headers: operatorTestingHeaders('TEST_SUITE_READ') },
    ),
  );
}

/** Publishes one exact stored Scenario revision into governed FixtureBundle/TestSuite assets. */
export async function publishScenarioDraftSet(
  scenarioDraftSetId: string,
  revision: number,
): Promise<StoredScenarioPublication> {
  const id = encodeURIComponent(scenarioDraftSetId);
  return readTestingJson<StoredScenarioPublication>(
    await sendRequest(
      `/api/visual/scenario-draft-sets/${id}/publications?revision=${revision}`,
      {
        method: 'POST',
        headers: operatorTestingHeaders('TEST_SCENARIO_PUBLISH'),
      },
    ),
  );
}

/** Creates or updates the retained Graph revision that Scenario assets address. */
export async function saveGraphDraft(
  draft: GraphDraft,
  idempotencyKey = '',
): Promise<GraphDraft> {
  const query = new URLSearchParams({
    actor: 'author-canvas',
    changeSource: 'contract-scenario-workspace',
    changeSummary: draft.draftId
      ? 'Saved Graph before Scenario authoring.'
      : 'Created Graph for Scenario authoring.',
    reason: 'Establish an exact server Contract coordinate.',
  });
  const path = draft.draftId
    ? `/api/visual/drafts/${encodeURIComponent(draft.draftId)}`
    : '/api/visual/drafts';
  return readJsonMutation<GraphDraft>(
    await sendRequest(`${path}?${query.toString()}`, {
      method: draft.draftId ? 'PUT' : 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {}),
      },
      body: JSON.stringify(draft),
    }),
  );
}

/** Atomically materializes a complete example as one current durable authoring Workspace. */
export async function forkWorkspace(
  idempotencyKey: string,
  command: WorkspaceForkCommand,
): Promise<WorkspaceForkReceipt> {
  return readTestingJson<WorkspaceForkReceipt>(
    await sendRequest('/api/authoring/workspace-forks', {
      method: 'POST',
      headers: {
        ...operatorTestingHeaders('TEST_SUITE_WRITE', true),
        'Idempotency-Key': idempotencyKey,
      },
      body: JSON.stringify(command),
    }),
  );
}

/** Reads the authoritative Contract and fingerprints derived from one retained Graph revision. */
export async function fetchScenarioGraphContract(
  draftId: string,
): Promise<ScenarioContractProjection> {
  return readTestingJson<ScenarioContractProjection>(
    await sendRequest(
      `/api/visual/scenario-draft-sets/targets/graphs/${encodeURIComponent(draftId)}/contract`,
      { headers: operatorTestingHeaders('TEST_SUITE_READ') },
    ),
  );
}

/** Reads the authoritative Contract and fingerprint for one catalog Operator. */
export async function fetchScenarioOperatorContract(
  operatorRef: string,
): Promise<ScenarioContractProjection> {
  return readTestingJson<ScenarioContractProjection>(
    await sendRequest(
      `/api/visual/scenario-draft-sets/targets/operators/${encodeURIComponent(operatorRef)}/contract`,
      { headers: operatorTestingHeaders('TEST_SUITE_READ') },
    ),
  );
}

function recordValue(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

type OperatorTestingPurpose =
  'TEST_EXECUTION'
  | 'TEST_FIXTURE_WRITE'
  | 'TEST_SUITE_READ'
  | 'TEST_SUITE_WRITE'
  | 'TEST_SCENARIO_PUBLISH';

export type CorrectnessApiPurpose =
  'CORRECTNESS_READ'
  | 'CORRECTNESS_WRITE'
  | 'CORRECTNESS_REVIEW'
  | 'CORRECTNESS_FIXTURE_MATERIAL_READ'
  | 'CORRECTNESS_FIXTURE_MATERIAL_WRITE'
  | 'SOLUTION_GOLDEN_REVIEW'
  | 'AGENT_TDD_GOVERNED_WRITE'
  | 'TEST_EXECUTION'
  | 'TEST_SUITE_READ'
  | 'TEST_SCENARIO_PUBLISH'
  | 'GOVERNANCE_EVIDENCE_INGESTION';

const CORRECTNESS_API_PATHS = [
  '/api/visual/correctness-',
  '/api/visual/coverage-inventories/',
  '/api/visual/oracles/',
  '/api/visual/assertion-sets',
  '/api/visual/scenario-draft-sets-v2/',
  '/api/visual/fixture-assets/',
  '/api/visual/fixture-materials',
  '/api/solution/golden-review/',
  '/api/solution/coverage/',
  '/api/agent-tdd/solutions/',
] as const;

export interface CorrectnessApiExchangeOptions {
  method?: 'GET' | 'POST' | 'PUT';
  body?: unknown;
  ifMatch?: number;
  idempotencyKey?: string;
  signal?: AbortSignal;
  /** Request-local identity headers; an explicit empty object suppresses the workload default. */
  identityHeaders?: Record<string, string>;
}

/** Uses the host-aware transport and workload identity for the isolated Correctness API family. */
export async function exchangeCorrectnessApi<T>(
  path: string,
  purpose: CorrectnessApiPurpose,
  options: CorrectnessApiExchangeOptions = {},
): Promise<T> {
  if (path.includes('..') || (path !== '/api/integration/capabilities'
      && !CORRECTNESS_API_PATHS.some((prefix) => path.startsWith(prefix)))) {
    throw new Error('Correctness API requests must use an approved same-origin endpoint.');
  }
  return readTestingJson<T>(await sendRequest(path, {
    method: options.method ?? 'GET',
    headers: {
      ...(options.identityHeaders === undefined
        ? operatorTestHeadersProvider() : options.identityHeaders),
      'X-Purpose': purpose,
      ...(options.body === undefined ? {} : { 'Content-Type': 'application/json' }),
      ...(options.ifMatch === undefined ? {} : { 'If-Match': String(options.ifMatch) }),
      ...(options.idempotencyKey ? { 'Idempotency-Key': options.idempotencyKey } : {}),
    },
    ...(options.body === undefined ? {} : { body: JSON.stringify(options.body) }),
    ...(options.signal === undefined ? {} : { signal: options.signal }),
  }));
}

function operatorTestingHeaders(purpose: OperatorTestingPurpose, json = false): Record<string, string> {
  return {
    ...operatorTestHeadersProvider(),
    'X-Purpose': purpose,
    ...(json ? { 'Content-Type': 'application/json' } : {}),
  };
}

function mirrorWorkbenchHeaders(): Record<string, string> {
  return {
    ...operatorTestHeadersProvider(),
    'X-Purpose': 'GOVERNANCE_EVIDENCE_INGESTION',
  };
}

function remediationHeaders(
  slot: RehearsalRemediationCredentialSlot,
  json = false,
): Record<string, string> {
  const credential = rehearsalRemediationCredentialsProvider(slot);
  if (!credential || !credential.principalLabel?.trim()) {
    throw new Error(`${slotLabel(slot)} remediation identity is not configured by the host.`);
  }
  const hostHeaders = Object.fromEntries(
    Object.entries(credential.headers).filter(([name]) => {
      const normalized = name.toLowerCase();
      return normalized !== 'x-purpose'
        && normalized !== 'content-type'
        && normalized !== 'content-length';
    }),
  );
  return {
    ...hostHeaders,
    'X-Purpose': 'MIRROR_REHEARSAL_REMEDIATION',
    ...(json ? { 'Content-Type': 'application/json' } : {}),
  };
}

function slotLabel(slot: RehearsalRemediationCredentialSlot): string {
  if (slot === 'INDEPENDENT_REVIEWER') {
    return 'Independent reviewer';
  }
  return slot === 'OWNER' ? 'Owner' : 'Read';
}

async function readMirrorPayload<T>(
  response: Response,
  payloadKind: string,
  payloadSchemaVersion: string,
): Promise<T> {
  const envelope = await readTestingJson<ToolStudioIntegrationEnvelope<T>>(response);
  if (envelope.payloadKind !== payloadKind
    || envelope.payloadSchemaVersion !== payloadSchemaVersion
    || envelope.payload === null
    || typeof envelope.payload !== 'object') {
    throw new Error(`Mirror response contract mismatch for ${payloadKind}.`);
  }
  return envelope.payload;
}

/** Lists one exact-scope newest-first page for the Owner rehearsal workbench. */
export async function fetchScenarioRehearsalBatchJobs(
  limit = 25,
  cursor: ScenarioRehearsalBatchJobPage['nextCursor'] = null,
): Promise<ScenarioRehearsalBatchJobPage> {
  const query = new URLSearchParams({ limit: String(limit) });
  if (cursor) {
    query.set('beforeCreatedAt', cursor.createdAt);
    query.set('beforeJobId', cursor.jobId);
  }
  return readMirrorPayload<ScenarioRehearsalBatchJobPage>(
    await sendRequest(`/api/mirror/rehearsal-jobs?${query.toString()}`, {
      headers: mirrorWorkbenchHeaders(),
    }),
    'SCENARIO_REHEARSAL_BATCH_JOB_PAGE',
    'resourceGateway.scenarioRehearsalBatchJobPage.v1',
  );
}

/** Reads one bounded mutable item page while a Scenario batch is active. */
export async function fetchScenarioRehearsalBatchItems(
  jobId: string,
  startIndex = 0,
  limit = 100,
): Promise<ScenarioRehearsalBatchItemPage> {
  const query = new URLSearchParams({
    startIndex: String(startIndex),
    limit: String(limit),
  });
  return readMirrorPayload<ScenarioRehearsalBatchItemPage>(
    await sendRequest(
      `/api/mirror/rehearsal-jobs/${encodeURIComponent(jobId)}/items?${query.toString()}`,
      { headers: mirrorWorkbenchHeaders() },
    ),
    'SCENARIO_REHEARSAL_BATCH_ITEM_PAGE',
    'resourceGateway.scenarioRehearsalBatchItemPage.v1',
  );
}

/** Reads database-authoritative retry and terminal observations for one batch item. */
export async function fetchScenarioRehearsalBatchItemAttempts(
  jobId: string,
  itemIndex: number,
): Promise<ScenarioRehearsalBatchItemAttemptTimeline> {
  return readMirrorPayload<ScenarioRehearsalBatchItemAttemptTimeline>(
    await sendRequest(
      `/api/mirror/rehearsal-jobs/${encodeURIComponent(jobId)}/items/${itemIndex}/attempts`,
      { headers: mirrorWorkbenchHeaders() },
    ),
    'SCENARIO_REHEARSAL_BATCH_ITEM_ATTEMPT_TIMELINE',
    'resourceGateway.scenarioRehearsalBatchItemAttemptTimeline.v1',
  );
}

/** Reads one root-sealed terminal Scenario batch workbook. */
export async function fetchScenarioRehearsalBatchWorkbook(
  jobId: string,
): Promise<ScenarioRehearsalBatchWorkbookSeed> {
  return readMirrorPayload<ScenarioRehearsalBatchWorkbookSeed>(
    await sendRequest(
      `/api/mirror/rehearsal-jobs/${encodeURIComponent(jobId)}/workbook-seed`,
      { headers: mirrorWorkbenchHeaders() },
    ),
    'SCENARIO_REHEARSAL_BATCH_WORKBOOK_SEED',
    'resourceGateway.scenarioRehearsalBatchWorkbookSeed.v1',
  );
}

/** Lazily reads case and assertion evidence for one terminal Scenario child run. */
export async function fetchScenarioRehearsalWorkbook(
  runId: string,
): Promise<ScenarioRehearsalWorkbookSeed> {
  return readMirrorPayload<ScenarioRehearsalWorkbookSeed>(
    await sendRequest(
      `/api/mirror/scenarios/runs/${encodeURIComponent(runId)}/workbook-seed`,
      { headers: mirrorWorkbenchHeaders() },
    ),
    'SCENARIO_REHEARSAL_WORKBOOK_SEED',
    'resourceGateway.scenarioRehearsalWorkbookSeed.v1',
  );
}

/** Freezes one blocked signed workbook and an exact proposed successor for human review. */
export async function previewScenarioRehearsalRemediation(
  jobId: string,
  request: ScenarioRehearsalRemediationPreviewRequest,
): Promise<ScenarioRehearsalRemediationPlan> {
  return readMirrorPayload<ScenarioRehearsalRemediationPlan>(
    await sendRequest(
      `/api/mirror/rehearsal-jobs/${encodeURIComponent(jobId)}/remediations`,
      {
        method: 'POST',
        headers: remediationHeaders('OWNER', true),
        body: JSON.stringify(request),
      },
    ),
    'SCENARIO_REHEARSAL_REMEDIATION_PLAN',
    'resourceGateway.scenarioRehearsalRemediationPlan.v1',
  );
}

/** Reads the immutable plan, decision chain, state, and optional successor receipt. */
export async function fetchScenarioRehearsalRemediationLineage(
  remediationId: string,
  slot: RehearsalRemediationCredentialSlot = 'READ',
): Promise<ScenarioRehearsalRemediationLineage> {
  return readMirrorPayload<ScenarioRehearsalRemediationLineage>(
    await sendRequest(
      `/api/mirror/rehearsal-remediations/${encodeURIComponent(remediationId)}`,
      { headers: remediationHeaders(slot) },
    ),
    'SCENARIO_REHEARSAL_REMEDIATION_LINEAGE',
    'resourceGateway.scenarioRehearsalRemediationLineage.v1',
  );
}

/** Appends one Owner or independent-reviewer decision using exact generation fencing. */
export async function decideScenarioRehearsalRemediation(
  remediationId: string,
  request: ScenarioRehearsalRemediationApprovalCommand,
): Promise<ScenarioRehearsalRemediationApproval> {
  const slot: RehearsalRemediationCredentialSlot = request.role === 'OWNER'
    ? 'OWNER'
    : 'INDEPENDENT_REVIEWER';
  return readMirrorPayload<ScenarioRehearsalRemediationApproval>(
    await sendRequest(
      `/api/mirror/rehearsal-remediations/${encodeURIComponent(remediationId)}/approvals`,
      {
        method: 'POST',
        headers: remediationHeaders(slot, true),
        body: JSON.stringify(request),
      },
    ),
    'SCENARIO_REHEARSAL_REMEDIATION_APPROVAL',
    'resourceGateway.scenarioRehearsalRemediationApproval.v1',
  );
}

/** Atomically admits the frozen successor after the exact two-person approval head. */
export async function submitScenarioRehearsalRemediation(
  remediationId: string,
  request: ScenarioRehearsalRemediationSubmitCommand,
): Promise<ScenarioRehearsalRemediationReceipt> {
  return readMirrorPayload<ScenarioRehearsalRemediationReceipt>(
    await sendRequest(
      `/api/mirror/rehearsal-remediations/${encodeURIComponent(remediationId)}/submissions`,
      {
        method: 'POST',
        headers: remediationHeaders('OWNER', true),
        body: JSON.stringify(request),
      },
    ),
    'SCENARIO_REHEARSAL_REMEDIATION_RECEIPT',
    'resourceGateway.scenarioRehearsalRemediationReceipt.v1',
  );
}

/** Reads the deterministic predecessor/successor comparison from two root-signed workbooks. */
export async function fetchScenarioRehearsalRemediationComparison(
  remediationId: string,
  slot: RehearsalRemediationCredentialSlot = 'READ',
): Promise<ScenarioRehearsalRemediationComparison> {
  return readMirrorPayload<ScenarioRehearsalRemediationComparison>(
    await sendRequest(
      `/api/mirror/rehearsal-remediations/${encodeURIComponent(remediationId)}/comparison`,
      { headers: remediationHeaders(slot) },
    ),
    'SCENARIO_REHEARSAL_REMEDIATION_COMPARISON',
    'resourceGateway.scenarioRehearsalRemediationComparison.v1',
  );
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
  purpose: OperatorTestingPurpose,
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

interface RegisteredOperatorFixture {
  request: Record<string, unknown>;
  storedFixture: StoredOperatorTestFixture;
}

async function registerOperatorTestFixture(
  operator: OperatorDefinition,
  target: OperatorTestTargetDescriptor,
  input: unknown,
  expectedOutput: unknown,
  transportResponse: unknown,
  caseRef: string,
): Promise<RegisteredOperatorFixture> {
  const runtimeRef = operatorRuntimeRef(operator);
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
  if (!['bloge.storedFixtureBundle.v1', 'bloge.storedFixtureBundle.v2']
        .includes(storedFixture.schemaVersion)
      || (storedFixture.schemaVersion === 'bloge.storedFixtureBundle.v2'
        && (!storedFixture.organizationId?.trim()
          || !storedFixture.projectId?.trim()
          || !storedFixture.region?.trim()))
      || storedFixture.fixtureBundleId !== fixtureBundleId
      || storedFixture.revision !== 1
      || !storedFixture.fingerprint?.trim()) {
    throw new Error('Fixture registry returned an inconsistent stored fixture identity.');
  }
  return {
    request: {
      ...inlineRequest,
      fixtureBundle: null,
      fixtureBundleRef: {
        fixtureBundleId: storedFixture.fixtureBundleId,
        revision: storedFixture.revision,
        fingerprint: storedFixture.fingerprint,
      },
    },
    storedFixture,
  };
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
  const registered = await registerOperatorTestFixture(
    operator, target, input, expectedOutput, transportResponse, caseRef,
  );
  const response = await readTestingJson<OperatorTestExecutionResponse>(
    await sendRequest(`/api/testing/targets/operators/${encodeURIComponent(runtimeRef)}/executions`, {
      method: 'POST',
      headers: operatorTestingHeaders('TEST_EXECUTION', true),
      body: JSON.stringify(registered.request),
    }),
  );
  return { target, response, storedFixture: registered.storedFixture };
}

function fullFingerprint(value: string | undefined): boolean {
  return /^sha256:[0-9a-f]{64}$/.test(value ?? '');
}

function sameTarget(
  actual: OperatorTestTargetDescriptor['target'] | undefined,
  expected: OperatorTestTargetDescriptor['target'],
): boolean {
  return actual?.kind === expected.kind
    && actual.id === expected.id
    && actual.fingerprint === expected.fingerprint;
}

function validateSuiteCases(cases: OperatorTestSuiteCaseInput[]): OperatorTestSuiteCaseInput[] {
  if (cases.length < 1 || cases.length > 100) {
    throw new Error('A Canvas operator suite must contain between 1 and 100 cases.');
  }
  const supportedTypes = new Set(['GOLDEN', 'NEGATIVE', 'BOUNDARY', 'REGRESSION']);
  const identities = new Set<string>();
  return cases.map((testCase) => {
    const caseId = testCase.caseId.trim();
    if (!caseId || caseId.length > 255 || identities.has(caseId)
        || !supportedTypes.has(testCase.caseType)) {
      throw new Error('Every Canvas suite case requires a unique bounded id and supported case type.');
    }
    identities.add(caseId);
    return { ...testCase, caseId };
  });
}

function validateStoredOperatorSuite(
  stored: StoredOperatorTestSuite,
  suiteId: string,
  expectedSuite: StoredOperatorTestSuite['suite'],
): void {
  if (!['bloge.storedTestSuite.v1', 'bloge.storedTestSuite.v2']
        .includes(stored.schemaVersion)
      || (stored.schemaVersion === 'bloge.storedTestSuite.v2'
        && (!stored.organizationId?.trim()
          || !stored.projectId?.trim()
          || !stored.region?.trim()))
      || stored.suiteId !== suiteId
      || stored.revision !== 1
      || !fullFingerprint(stored.fingerprint)
      || canonicalJson(stored.suite) !== canonicalJson(expectedSuite)) {
    throw new Error('Suite registry returned an inconsistent stored suite identity.');
  }
}

function nonNegativeInteger(value: unknown): value is number {
  return Number.isInteger(value) && Number(value) >= 0;
}

function sameCanonicalValues(left: unknown, right: unknown): boolean {
  return canonicalJson(left) === canonicalJson(right);
}

function expectedAggregateStatus(
  cases: OperatorTestSuiteExecutionResponse['evidence']['caseResults'],
  coverageStatus: OperatorTestSuiteExecutionResponse['evidence']['coverage']['status'],
): OperatorTestSuiteExecutionResponse['evidence']['status'] {
  if (cases.some((testCase) => testCase.status === 'EVIDENCE_INCOMPLETE')) {
    return 'EVIDENCE_INCOMPLETE';
  }
  if (cases.some((testCase) => testCase.status === 'PENDING' || testCase.status === 'NOT_SCHEDULED')) {
    return 'PARTIAL';
  }
  if (cases.some((testCase) => testCase.status === 'FAILED')) {
    return 'COMPLETED_WITH_FAILURES';
  }
  return coverageStatus === 'SATISFIED' ? 'PASSED' : 'COMPLETED_WITH_FAILURES';
}

function validateOperatorSuiteExecution(
  response: OperatorTestSuiteExecutionResponse,
  clientRequestId: string,
  suiteRef: { suiteId: string; revision: number; fingerprint: string },
  target: OperatorTestTargetDescriptor['target'],
  expectedCases: Map<string, {
    caseType: OperatorTestSuiteCaseInput['caseType'];
    fixture: StoredOperatorTestFixture;
  }>,
  expectedSuite: StoredOperatorTestSuite['suite'],
): void {
  const evidence = response.evidence;
  const terminalStatuses = new Set(['PASSED', 'COMPLETED_WITH_FAILURES', 'PARTIAL', 'EVIDENCE_INCOMPLETE']);
  const caseStatuses = new Set(['PASSED', 'FAILED', 'NOT_SCHEDULED', 'EVIDENCE_INCOMPLETE']);
  const evidenceStatuses = new Set([
    'PASSED', 'ASSERTION_FAILED', 'EXECUTION_FAILED', 'CONTROL_PLAN_REJECTED',
    'FIXTURE_UNMATCHED', 'FIXTURE_UNUSED', 'CONTROL_PLAN_UNAVAILABLE',
    'EVIDENCE_INCOMPLETE', 'CANCELLED', 'TIMED_OUT',
  ]);
  const evidenceClasses = new Set(['EXPLORATORY', 'CERTIFIABLE']);
  const caseResults = evidence?.caseResults ?? [];
  const returnedCaseIds = new Set(caseResults.map((result) => result.caseId));
  const caseIdentitiesMatch = caseResults.length === expectedCases.size
    && returnedCaseIds.size === expectedCases.size
    && [...expectedCases.keys()].every((caseId) => returnedCaseIds.has(caseId))
    && caseResults.every((result) => {
      const expected = expectedCases.get(result.caseId);
      return expected !== undefined
        && result.caseType === expected.caseType
        && result.fixtureBundleRef?.fixtureBundleId === expected.fixture.fixtureBundleId
        && result.fixtureBundleRef.revision === expected.fixture.revision
        && result.fixtureBundleRef.fingerprint === expected.fixture.fingerprint
        && caseStatuses.has(result.status)
        && nonNegativeInteger(result.assertionsEvaluated)
        && nonNegativeInteger(result.assertionsPassed)
        && result.assertionsPassed <= result.assertionsEvaluated
        && (result.evidenceStatus === null || evidenceStatuses.has(result.evidenceStatus))
        && (result.evidenceClass === null || evidenceClasses.has(result.evidenceClass))
        && (!['PASSED', 'FAILED'].includes(result.status) || (
          Boolean(result.runId?.trim())
          && result.evidenceStatus !== null
          && result.evidenceClass !== null
        ))
        && (result.status !== 'NOT_SCHEDULED' || (
          !result.runId?.trim()
          && result.evidenceStatus === null
          && result.evidenceClass === null
          && result.assertionsEvaluated === 0
          && result.assertionsPassed === 0
        ))
        && (result.status !== 'PASSED' || (
          Boolean(result.runId?.trim())
          && result.evidenceStatus === 'PASSED'
          && result.evidenceClass !== null
          && result.assertionsEvaluated >= expectedSuite.coveragePolicy.minimumAssertionsPerCase
          && result.assertionsPassed === result.assertionsEvaluated
        ));
    });
  const coverage = evidence?.coverage;
  const promotion = evidence?.promotion;
  const policy = expectedSuite.coveragePolicy;
  const promotionPolicy = expectedSuite.promotionPolicy;
  const childEvidenceCount = caseResults.filter((result) => (
    Boolean(result.runId?.trim()) && result.evidenceStatus !== null
  )).length;
  const allCasesCompleted = caseResults.length === expectedCases.size
    && caseResults.every((result) => result.status !== 'PENDING' && result.status !== 'NOT_SCHEDULED');
  const observedCaseTypes = new Set(caseResults
    .filter((result) => Boolean(result.runId?.trim()) && result.evidenceStatus !== null)
    .map((result) => result.caseType));
  const missingCaseTypes = policy.requiredCaseTypes.filter((caseType) => !observedCaseTypes.has(caseType));
  const assertionDensityViolations = caseResults
    .filter((result) => result.assertionsEvaluated < policy.minimumAssertionsPerCase)
    .map((result) => result.caseId)
    .sort();
  const incompleteEvidence = caseResults.some((result) => (
    !result.runId?.trim()
    || result.evidenceStatus === null
    || result.status === 'EVIDENCE_INCOMPLETE'
    || result.status === 'NOT_SCHEDULED'
    || result.status === 'PENDING'
  ));
  const coverageSatisfied = childEvidenceCount >= policy.minimumCases
    && missingCaseTypes.length === 0
    && assertionDensityViolations.length === 0
    && coverage?.fixtureConsumptionViolations?.length === 0;
  const derivedCoverageStatus = incompleteEvidence
    ? 'INCOMPLETE'
    : coverageSatisfied ? 'SATISFIED' : 'UNSATISFIED';
  const certifiableCases = caseResults.filter((result) => (
    Boolean(result.runId?.trim()) && result.evidenceClass === 'CERTIFIABLE'
  )).length;
  const allCasesPassed = allCasesCompleted
    && caseResults.every((result) => result.status === 'PASSED');
  const coverageMatches = coverage !== undefined
    && ['SATISFIED', 'UNSATISFIED', 'INCOMPLETE'].includes(coverage.status)
    && coverage.status === derivedCoverageStatus
    && coverage.minimumCases === policy.minimumCases
    && coverage.completedCases === childEvidenceCount
    && coverage.minimumAssertionsPerCase === policy.minimumAssertionsPerCase
    && Array.isArray(coverage.requiredCaseTypes)
    && Array.isArray(coverage.observedCaseTypes)
    && Array.isArray(coverage.missingCaseTypes)
    && Array.isArray(coverage.assertionDensityViolations)
    && Array.isArray(coverage.fixtureConsumptionViolations)
    && sameCanonicalValues(coverage.requiredCaseTypes, policy.requiredCaseTypes)
    && sameCanonicalValues(
      [...coverage.observedCaseTypes].sort(),
      [...observedCaseTypes].sort(),
    )
    && sameCanonicalValues(coverage.missingCaseTypes, missingCaseTypes)
    && coverage.allCasesCompleted === allCasesCompleted
    && sameCanonicalValues([...coverage.assertionDensityViolations].sort(), assertionDensityViolations);
  const promotionMatches = promotion !== undefined
    && ['ELIGIBLE', 'BLOCKED'].includes(promotion.status)
    && nonNegativeInteger(promotion.certifiableCases)
    && promotion.certifiableCases === certifiableCases
    && promotion.minimumCertifiableCases === promotionPolicy.minimumCertifiableCases
    && promotion.allCasesPassed === allCasesPassed
    && promotion.coverageSatisfied === (derivedCoverageStatus === 'SATISFIED')
    && promotion.allCasesCompleted === allCasesCompleted
    && Array.isArray(promotion.reasons)
    && (!promotionPolicy.requireTargetCertificationEligible
      || promotion.targetCertificationEligible === true
      || promotion.status === 'BLOCKED')
    && (promotion.status !== 'ELIGIBLE' || (
      promotion.reasons.length === 0
      && (!promotionPolicy.requireAllCasesPassed || allCasesPassed)
      && certifiableCases >= promotionPolicy.minimumCertifiableCases
      && (!promotionPolicy.requireTargetCertificationEligible || promotion.targetCertificationEligible)
      && derivedCoverageStatus === 'SATISFIED'
      && allCasesCompleted
    ));
  const aggregateStatusMatches = coverageMatches
    && evidence.status === expectedAggregateStatus(caseResults, coverage.status);
  if (response.schemaVersion !== 'bloge.testSuiteExecutionResponse.v1'
      || !response.suiteRunId?.trim()
      || response.suiteRunId !== evidence?.suiteRunId
      || !fullFingerprint(response.evidenceFingerprint)
      || evidence?.schemaVersion !== 'bloge.testSuiteRunEvidence.v1'
      || evidence.clientRequestId !== clientRequestId
      || evidence.executionPurpose !== 'TEST_SUITE_EXECUTION'
      || evidence.suiteRef?.suiteId !== suiteRef.suiteId
      || evidence.suiteRef.revision !== suiteRef.revision
      || evidence.suiteRef.fingerprint !== suiteRef.fingerprint
      || !sameTarget(evidence.target, target)
      || !terminalStatuses.has(evidence.status)
      || !caseIdentitiesMatch
      || !coverageMatches
      || !promotionMatches
      || !aggregateStatusMatches) {
    throw new Error('Suite runner returned a response for a different execution intent.');
  }
}

/**
 * Publishes Canvas operator rows as content-addressed fixtures plus one immutable suite revision,
 * then executes that exact revision through the common governed suite runner.
 */
export async function governOperatorTestSuite(
  operator: OperatorDefinition,
  canvasSuiteRef: string,
  cases: OperatorTestSuiteCaseInput[],
): Promise<OperatorTestSuiteRun> {
  const normalizedCases = validateSuiteCases(cases);
  const runtimeRef = operatorRuntimeRef(operator);
  const target = await discoverOperatorTestTarget(operator, 'TEST_SUITE_WRITE');
  if (target.schemaVersion !== 'bloge.testOperatorTargetDescriptor.v2'
      || target.target.kind !== 'OPERATOR'
      || target.target.id !== runtimeRef
      || !fullFingerprint(target.target.fingerprint)) {
    throw new Error('Operator target discovery did not return an exact content fingerprint.');
  }

  const registeredCases = [];
  for (const testCase of normalizedCases) {
    const registered = await registerOperatorTestFixture(
      operator,
      target,
      testCase.input,
      testCase.expectedOutput,
      testCase.transportResponse,
      testCase.caseId,
    );
    if (!fullFingerprint(registered.storedFixture.fingerprint)) {
      throw new Error('Fixture registry did not return an exact content fingerprint.');
    }
    registeredCases.push({ testCase, registered });
  }

  const requiredCaseTypes = [...new Set(normalizedCases.map((testCase) => testCase.caseType))].sort();
  const suiteContent = {
    target: target.target,
    classification: 'INTERNAL',
    cases: registeredCases.map(({ testCase, registered }) => ({
      caseId: testCase.caseId,
      caseType: testCase.caseType,
      input: registered.request.input,
      fixtureBundleRef: {
        fixtureBundleId: registered.storedFixture.fixtureBundleId,
        revision: registered.storedFixture.revision,
        fingerprint: registered.storedFixture.fingerprint,
      },
      tags: ['author-canvas', testCase.caseType.toLowerCase()].sort(),
      metadata: {
        source: 'author-canvas',
        visualCaseName: (testCase.name?.trim() || testCase.caseId).slice(0, 255),
      },
    })),
    coveragePolicy: {
      minimumCases: normalizedCases.length,
      requiredCaseTypes,
      requiredInvocationSiteIds: [],
      requiredEdgeTransfers: [],
      minimumAssertionsPerCase: 1,
      requireAllFixtureRulesConsumed: true,
    },
    promotionPolicy: {
      requireAllCasesPassed: true,
      minimumCertifiableCases: normalizedCases.length,
      requireTargetCertificationEligible: true,
    },
    metadata: {
      source: 'author-canvas',
      visualOperatorRef: operator.operatorRef,
      canvasSuiteRef: canvasSuiteRef.trim().slice(0, 255),
    },
  };
  const suiteDigest = await sha256Hex(suiteContent);
  const suiteId = [
    'canvas',
    boundedProtocolId(runtimeRef, 'operator').slice(0, 32),
    boundedProtocolId(canvasSuiteRef, 'suite').slice(0, 32),
    suiteDigest,
  ].join('-');
  const testSuite: StoredOperatorTestSuite['suite'] = {
    schemaVersion: 'bloge.testSuite.v1',
    suiteId,
    revision: 1,
    ...suiteContent,
  };
  const storedSuite = await readTestingJson<StoredOperatorTestSuite>(
    await sendRequest(`/api/testing/suites/${encodeURIComponent(suiteId)}`, {
      method: 'PUT',
      headers: operatorTestingHeaders('TEST_SUITE_WRITE', true),
      body: JSON.stringify({
        schemaVersion: 'bloge.testSuiteRegistrationRequest.v1',
        testSuite,
      }),
    }),
  );
  validateStoredOperatorSuite(storedSuite, suiteId, testSuite);

  const suiteRef = { suiteId, revision: storedSuite.revision, fingerprint: storedSuite.fingerprint };
  const clientRequestId = `canvas-suite-${await sha256Hex(suiteRef)}`;
  const response = await readTestingJson<OperatorTestSuiteExecutionResponse>(
    await sendRequest(`/api/testing/suites/${encodeURIComponent(suiteId)}/executions`, {
      method: 'POST',
      headers: operatorTestingHeaders('TEST_EXECUTION', true),
      body: JSON.stringify({
        schemaVersion: 'bloge.testSuiteExecutionRequest.v1',
        suiteRef,
        clientRequestId,
        strategy: 'COLLECT_ALL',
        metadata: {
          source: 'author-canvas',
          visualOperatorRef: operator.operatorRef,
          canvasSuiteRef: canvasSuiteRef.trim().slice(0, 255),
        },
      }),
    }),
  );
  validateOperatorSuiteExecution(
    response,
    clientRequestId,
    suiteRef,
    target.target,
    new Map(registeredCases.map(({ testCase, registered }) => [testCase.caseId, {
      caseType: testCase.caseType,
      fixture: registered.storedFixture,
    }])),
    testSuite,
  );
  return {
    target,
    storedFixtures: registeredCases.map(({ registered }) => registered.storedFixture),
    storedSuite,
    response,
  };
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
