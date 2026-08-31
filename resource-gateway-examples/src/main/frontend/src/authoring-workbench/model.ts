export type JsonObject = Record<string, unknown>;

export interface SchemaEnvelope {
  format: 'json-schema';
  version: '2020-12';
  schema: {
    type: 'object';
    properties: Record<string, { type: 'string' | 'integer' | 'number' | 'boolean' | 'object' }>;
    required: string[];
    additionalProperties: false;
  };
}

export interface ApiResourceFormDraft {
  resourceId: string;
  displayName: string;
  connectionId: string;
  method: 'GET' | 'POST' | 'PUT' | 'DELETE';
  path: string;
  requestExample: string;
  responseExample: string;
}

export interface ApiResourceSaveCommand {
  schemaVersion: 'bloge.apiResourceSaveCommand.v1';
  connection: { mode: 'EXISTING'; connectionId: string };
  resource: {
    displayName: string;
    operation: {
      method: ApiResourceFormDraft['method'];
      path: string;
      bindings: Array<{ from: string; to: { location: 'QUERY'; name: string } }>;
    };
    contract: { input: SchemaEnvelope; output: SchemaEnvelope };
    response: { success: { kind: 'HTTP_STATUS'; codes: number[] } };
    effect: { kind: 'READ_ONLY' | 'FIXTURE_ONLY_WRITE' };
    examples: Array<{ name: 'default'; input: JsonObject; output: JsonObject }>;
  };
  defaultFixture: { kind: 'FROM_EXAMPLES'; displayName: string; exampleNames: ['default'] };
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
  subject: ApiResourceRef;
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
  const connectionId = requiredIdentifier(draft.connectionId, 'Connection ID');
  const displayName = draft.displayName.trim();
  if (!displayName || displayName.length > 200) throw new Error('API name is required.');
  if (!PATH.test(draft.path) || draft.path.length > 2048) {
    throw new Error('Path must start with / and contain only URL path characters.');
  }
  const input = parseObjectExample(draft.requestExample, 'Request example');
  const output = parseObjectExample(draft.responseExample, 'Response example');
  const inputSchema = inferSchema(input);
  const outputSchema = inferSchema(output);

  return {
    schemaVersion: 'bloge.apiResourceSaveCommand.v1',
    connection: { mode: 'EXISTING', connectionId },
    resource: {
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
      examples: [{ name: 'default', input, output }],
    },
    defaultFixture: {
      kind: 'FROM_EXAMPLES',
      displayName: `${displayName} default`,
      exampleNames: ['default'],
    },
  };
}

/** Restores the concise form from one committed Resource authority. */
export function formDraftFromSpec(spec: ApiResourceSpec): ApiResourceFormDraft {
  const example = spec.examples[0];
  return {
    resourceId: spec.resourceId,
    displayName: spec.displayName,
    connectionId: spec.connectionId,
    method: spec.operation.method,
    path: spec.operation.path,
    requestExample: JSON.stringify(example?.input ?? {}, null, 2),
    responseExample: JSON.stringify(example?.output ?? {}, null, 2),
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
