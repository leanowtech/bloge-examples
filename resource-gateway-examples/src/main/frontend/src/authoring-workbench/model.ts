export type JsonObject = Record<string, unknown>;

export interface SchemaEnvelope {
  format: 'json-schema';
  version: '2020-12';
  schema: {
    type: 'object';
    properties: Record<string, { type: string; [key: string]: unknown }>;
    required: string[];
    additionalProperties: false;
  };
}

export interface ApiResourceFormDraft {
  resourceId: string;
  displayName: string;
  connectionMode: 'EXISTING' | 'CREATE';
  connectionId: string;
  connectionDisplayName: string;
  connectionBaseUrl: string;
  method: 'GET' | 'POST' | 'PUT' | 'DELETE';
  path: string;
  requestExample: string;
  responseExample: string;
  importedResource: ApiResourceSaveCommand['resource'] | null;
}

export interface ApiConnectionView {
  schemaVersion: 'bloge.apiConnectionView.v1';
  connectionId: string;
  revision: number;
  displayName: string;
  baseUrl: string;
  auth: { kind: string; configured: boolean };
}

export interface LegacyAssetMigrationInventory {
  schemaVersion: 'bloge.legacyAssetMigrationInventory.v1';
  summary: { total: number; readyToReauthor: number; needsRepair: number; legacyOnly: number };
  items: LegacyAssetMigrationItem[];
}

export interface LegacyAssetMigrationItem {
  kind: 'API_RESOURCE' | 'REUSABLE_FLOW_DRAFT' | 'REUSABLE_FLOW_VERSION' | 'FIXTURE_SET';
  sourceId: string;
  sourceRevision: number;
  displayName: string;
  status: 'READY_TO_REAUTHOR' | 'NEEDS_REPAIR' | 'LEGACY_ONLY';
  fixtureReferences: number;
  reasonCodes: string[];
  action: {
    kind: 'REAUTHOR_RESOURCE' | 'REAUTHOR_FLOW' | 'REPAIR_SOURCE' | 'OPEN_LEGACY_FLOW' | 'REAUTHOR_FIXTURE';
    path: string;
  };
}

/** Read-only proof that every item in one exact inventory snapshot was classified. */
export interface LegacyMigrationAssessment {
  schemaVersion: 'bloge.legacyMigrationAssessment.v1';
  inventoryFingerprint: string;
  coverage: {
    total: number;
    classified: number;
    unclassified: number;
    readyToReauthor: number;
    needsRepair: number;
    legacyOnly: number;
    fixtureReferences: number;
  };
  failures: LegacyAssetMigrationItem[];
}

/** Connection-independent, transport-redacted command projected from one exact legacy Resource. */
export interface LegacyApiResourceReauthorPreview {
  schemaVersion: 'bloge.legacyApiResourceReauthorPreview.v1';
  source: { kind: 'API_RESOURCE'; resourceId: string; sourceRevision: 0 };
  suggestedResource: ApiResourceSaveCommand['resource'];
  diagnostics: Array<{ code: string; message: string }>;
}

export interface ApiResourceBinding {
  from: string;
  to: { location: 'PATH' | 'QUERY' | 'HEADER' | 'BODY'; name: string };
}

export interface ApiResourceSaveCommand {
  schemaVersion: 'bloge.apiResourceSaveCommand.v1';
  connection: { mode: 'EXISTING'; connectionId: string } | {
    mode: 'CREATE';
    command: {
      schemaVersion: 'bloge.apiConnectionCommand.v1';
      displayName: string;
      baseUrl: string;
      auth: { kind: 'NONE' };
    };
  };
  resource: {
    displayName: string;
    operation: {
      method: ApiResourceFormDraft['method'];
      path: string;
      bindings: ApiResourceBinding[];
    };
    contract: { input: SchemaEnvelope; output: SchemaEnvelope };
    response: {
      success: { kind: 'HTTP_STATUS'; codes: number[] }
        | { kind: 'BODY_MATCH'; path: string; values: unknown[] };
      outputPath?: string;
    };
    effect: { kind: 'READ_ONLY' | 'FIXTURE_ONLY_WRITE' };
    examples: Array<{ name: string; input: JsonObject; output: JsonObject }>;
  };
  defaultFixture: { kind: 'FROM_EXAMPLES'; displayName: string; exampleNames: string[] };
}

export interface OpenApiPreview {
  schemaVersion: 'bloge.openApiPreview.v1';
  discoveryId: string;
  operations: Array<{
    operationId: string;
    method: ApiResourceFormDraft['method'];
    path: string;
    suggestedResource: ApiResourceSaveCommand['resource'];
    diagnostics: Array<{ code: string; message: string }>;
  }>;
}

export interface ApiResourceRef {
  kind: 'API_RESOURCE';
  resourceId: string;
  revision: number;
  fingerprint: string;
}

export interface ApiResourceReceipt {
  schemaVersion: 'bloge.apiResourceSaveReceipt.v1';
  resource: ApiResourceRef;
  connection: { connectionId: string; revision: number };
  defaultFixture: {
    fixtureSetId: string;
    revision: number;
    fingerprint: string;
    cases: Array<{ exampleName: string; caseId: string }>;
  };
  projections: { descriptor: 'READY'; designContract: 'READY'; operator: 'READY' };
}

export interface ApiResourceSpec {
  schemaVersion: 'bloge.apiResourceSpec.v1';
  resourceId: string;
  revision: number;
  fingerprint: string;
  displayName: string;
  connectionId: string;
  operation: ApiResourceSaveCommand['resource']['operation'];
  contract: ApiResourceSaveCommand['resource']['contract'];
  response: ApiResourceSaveCommand['resource']['response'];
  effect: ApiResourceSaveCommand['resource']['effect'];
  examples: ApiResourceSaveCommand['resource']['examples'];
  status: string;
}

export interface FixtureSetSummary {
  schemaVersion: 'bloge.fixtureSetSummary.v1';
  fixtureSetId: string;
  revision: number;
  fingerprint: string;
  displayName: string;
  subject: ApiResourceRef | {
    kind: 'FLOW_DRAFT'; draftId: string; revision: number; fingerprint: string;
  } | {
    kind: 'FLOW_VERSION'; publicationId: string; revision: number; fingerprint: string;
  };
  cases: Array<{ caseId: string; name: string }>;
  status: string;
  statusRevision: number;
}

export interface SimulationRun {
  schemaVersion: 'bloge.simulationRun.v1';
  runId: string;
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'BLOCKED';
  output?: unknown;
  nodes: Array<{
    nodeId: string;
    status: string;
    execution: 'REAL' | 'MOCKED';
    fixtureSource: string;
    fidelity?: string;
    egress: { decision: string; attempted: boolean };
  }>;
  verdicts: {
    execution: string;
    contract: string;
    assertions: string;
    governance: string;
  };
  diagnostics: Array<{ code: string; message: string }>;
}

const IDENTIFIER = /^[A-Za-z0-9][A-Za-z0-9._:-]*$/;
const PATH = /^\/[A-Za-z0-9._~:/{}-]*$/;

/** Builds the one compound command used by the simple API object page. */
export function buildApiResourceSaveCommand(draft: ApiResourceFormDraft): ApiResourceSaveCommand {
  requiredIdentifier(draft.resourceId, 'Resource ID');
  const connection = draft.connectionMode === 'CREATE'
    ? createdConnection(draft.connectionDisplayName, draft.connectionBaseUrl)
    : { mode: 'EXISTING' as const, connectionId: requiredIdentifier(draft.connectionId, 'Connection ID') };
  const displayName = draft.displayName.trim();
  if (!displayName || displayName.length > 200) throw new Error('API name is required.');
  if (!PATH.test(draft.path) || draft.path.length > 2048) {
    throw new Error('Path must start with / and contain only URL path characters.');
  }
  const input = parseObjectExample(draft.requestExample, 'Request example');
  const output = parseObjectExample(draft.responseExample, 'Response example');
  const inputSchema = inferSchema(input);
  const outputSchema = inferSchema(output);
  const imported = draft.importedResource;
  if (imported && (imported.operation.method !== draft.method || imported.operation.path !== draft.path
      || JSON.stringify(imported.examples[0]?.input ?? {}) !== JSON.stringify(input)
      || JSON.stringify(imported.examples[0]?.output ?? {}) !== JSON.stringify(output))) {
    throw new Error('The imported operation changed. Re-import it or edit it as a manual Resource.');
  }
  const exampleName = imported?.examples[0]?.name ?? 'default';

  return {
    schemaVersion: 'bloge.apiResourceSaveCommand.v1',
    connection,
    resource: imported ? { ...imported, displayName } : {
      displayName,
      operation: {
        method: draft.method,
        path: draft.path,
        bindings: Object.keys(input).map((name) => ({
          from: `$.${name}`,
          to: { location: 'QUERY', name },
        })),
      },
      contract: { input: inputSchema, output: outputSchema },
      response: { success: { kind: 'HTTP_STATUS', codes: [200] } },
      effect: { kind: draft.method === 'GET' ? 'READ_ONLY' : 'FIXTURE_ONLY_WRITE' },
      examples: [{ name: exampleName, input, output }],
    },
    defaultFixture: {
      kind: 'FROM_EXAMPLES',
      displayName: `${displayName} default`,
      exampleNames: [exampleName],
    },
  };
}

/** Restores the concise form from one committed Resource authority. */
export function formDraftFromSpec(spec: ApiResourceSpec): ApiResourceFormDraft {
  const example = spec.examples[0];
  return {
    resourceId: spec.resourceId,
    displayName: spec.displayName,
    connectionMode: 'EXISTING',
    connectionId: spec.connectionId,
    connectionDisplayName: '',
    connectionBaseUrl: '',
    method: spec.operation.method,
    path: spec.operation.path,
    requestExample: JSON.stringify(example?.input ?? {}, null, 2),
    responseExample: JSON.stringify(example?.output ?? {}, null, 2),
    importedResource: {
      displayName: spec.displayName,
      operation: spec.operation,
      contract: spec.contract,
      response: spec.response,
      effect: spec.effect,
      examples: spec.examples,
    },
  };
}

/** Applies one server-projected OpenAPI operation while preserving the chosen Connection. */
export function formDraftFromOpenApiOperation(
  current: ApiResourceFormDraft,
  operation: OpenApiPreview['operations'][number],
): ApiResourceFormDraft {
  const example = operation.suggestedResource.examples[0];
  return {
    resourceId: current.resourceId.trim() || operation.operationId,
    displayName: operation.suggestedResource.displayName,
    connectionMode: current.connectionMode,
    connectionId: current.connectionId,
    connectionDisplayName: current.connectionDisplayName,
    connectionBaseUrl: current.connectionBaseUrl,
    method: operation.method,
    path: operation.path,
    requestExample: JSON.stringify(example?.input ?? {}, null, 2),
    responseExample: JSON.stringify(example?.output ?? {}, null, 2),
    importedResource: operation.suggestedResource,
  };
}

/** Applies a reviewed legacy projection while deliberately requiring a new visible Connection choice. */
export function formDraftFromLegacyPreview(
  preview: LegacyApiResourceReauthorPreview,
): ApiResourceFormDraft {
  const suggested = preview.suggestedResource;
  const example = suggested.examples[0];
  return {
    resourceId: preview.source.resourceId,
    displayName: suggested.displayName,
    connectionMode: 'CREATE',
    connectionId: '',
    connectionDisplayName: `${suggested.displayName} connection`,
    connectionBaseUrl: '',
    method: suggested.operation.method,
    path: suggested.operation.path,
    requestExample: JSON.stringify(example?.input ?? {}, null, 2),
    responseExample: JSON.stringify(example?.output ?? {}, null, 2),
    importedResource: suggested,
  };
}

function createdConnection(displayNameSource: string, baseUrlSource: string): ApiResourceSaveCommand['connection'] {
  const displayName = displayNameSource.trim();
  if (!displayName || displayName.length > 200) throw new Error('Connection name is required.');
  const baseUrl = baseUrlSource.trim();
  let parsed: URL;
  try {
    parsed = new URL(baseUrl);
  } catch {
    throw new Error('Base URL must be an absolute HTTP or HTTPS URL.');
  }
  if (!['http:', 'https:'].includes(parsed.protocol) || !parsed.hostname
      || parsed.username || parsed.password || parsed.search || parsed.hash) {
    throw new Error('Base URL must be an absolute HTTP or HTTPS URL without credentials, query, or fragment.');
  }
  return {
    mode: 'CREATE',
    command: {
      schemaVersion: 'bloge.apiConnectionCommand.v1',
      displayName,
      baseUrl,
      auth: { kind: 'NONE' },
    },
  };
}

function parseObjectExample(source: string, label: string): JsonObject {
  let value: unknown;
  try {
    value = JSON.parse(source);
  } catch {
    throw new Error(`${label} must be valid JSON.`);
  }
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error(`${label} must be a JSON object.`);
  }
  for (const [name, field] of Object.entries(value)) {
    if (!IDENTIFIER.test(name) || field === null || Array.isArray(field)) {
      throw new Error(`${label} supports named string, number, boolean, or object fields.`);
    }
  }
  return value as JsonObject;
}

function inferSchema(value: JsonObject): SchemaEnvelope {
  const properties = Object.fromEntries(Object.entries(value).map(([name, field]) => [
    name,
    { type: inferType(field) },
  ])) as SchemaEnvelope['schema']['properties'];
  return {
    format: 'json-schema',
    version: '2020-12',
    schema: {
      type: 'object',
      properties,
      required: Object.keys(value),
      additionalProperties: false,
    },
  };
}

function inferType(value: unknown): 'string' | 'integer' | 'number' | 'boolean' | 'object' {
  if (typeof value === 'string') return 'string';
  if (typeof value === 'number') return Number.isInteger(value) ? 'integer' : 'number';
  if (typeof value === 'boolean') return 'boolean';
  return 'object';
}

function requiredIdentifier(value: string, label: string): string {
  const normalized = value.trim();
  if (!IDENTIFIER.test(normalized) || normalized.length > 128) {
    throw new Error(`${label} must be a simple identifier.`);
  }
  return normalized;
}
