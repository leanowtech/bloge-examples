/** A JSON Schema object emitted by the bounded sample inferencer. */
export type JsonSchema = Record<string, unknown>;

/** A parameter accepted by an external API resource. */
export interface ExternalApiParameter {
  name: string;
  in: 'path' | 'query' | 'header';
  from: string;
}

/** The response-success strategies supported by the gateway's runtime protocol. */
export type ExternalApiResponseProtocol =
  | { kind: 'HttpStatus' }
  | { kind: 'StatusCodes'; success: readonly number[] }
  | { kind: 'BodyFlag'; flagField: string }
  | { kind: 'BodyCode'; codeField: string; successCodes: readonly (string | number)[]; messageField?: string };

/** How an API response schema was supplied by an author. */
export type ExternalApiOutputSchema =
  | { source: 'manual'; schema: JsonSchema }
  | { source: 'structured'; schema: JsonSchema }
  | { source: 'inferred'; sampleResponse: unknown; schema: JsonSchema };

/** Primitive property types supported by the bounded structured schema editor. */
export type StructuredSchemaPropertyType = 'string' | 'integer' | 'number' | 'boolean';

/** One object property authored without requiring JSON syntax. */
export interface StructuredSchemaProperty {
  name: string;
  type: StructuredSchemaPropertyType;
  required: boolean;
}

/** Safety bound for the compact structured schema editor. */
export const MAX_STRUCTURED_PROPERTIES = 20;

/** Build a deterministic object schema while rejecting incomplete editor rows. */
export function structuredObjectSchema(rows: readonly StructuredSchemaProperty[]): JsonSchema {
  if (rows.length > MAX_STRUCTURED_PROPERTIES) {
    throw new Error('Structured schema contains too many properties.');
  }
  const normalized = rows.map((row) => ({ ...row, name: row.name.trim() }));
  if (normalized.some((row) => !row.name)) {
    throw new Error('Every structured schema property needs a name.');
  }
  const names = normalized.map((row) => row.name);
  if (new Set(names).size !== names.length) {
    throw new Error('Structured schema property names must be unique.');
  }
  const ordered = [...normalized].sort((left, right) => left.name.localeCompare(right.name));
  return {
    type: 'object',
    properties: Object.fromEntries(ordered.map((row) => [row.name, { type: row.type }])),
    required: ordered.filter((row) => row.required).map((row) => row.name),
    additionalProperties: false,
  };
}

/** Pure authoring state for one external HTTP API resource. */
export interface ExternalApiFormModel {
  resourceId: string;
  displayName: string;
  urlTemplate: string;
  method: 'GET' | 'POST' | 'PUT' | 'DELETE';
  params: ExternalApiParameter[];
  responseProtocol: ExternalApiResponseProtocol;
  payloadPath: string;
  outputSchema: ExternalApiOutputSchema;
}

/** JSON shape accepted by the runtime `ResourceDescriptor` endpoint. */
export interface ResourceDescriptorPayload {
  resourceId: string;
  urlTemplate: string;
  method: ExternalApiFormModel['method'];
  defaultHeaders: { Accept: string };
  authStrategy: null;
  defaultTimeout: string;
  parameterMapping: {
    pathExpressions: Record<string, string>;
    queryExpressions: Record<string, string>;
    headerExpressions: Record<string, string>;
    cookieExpressions: Record<string, string>;
    bodyExpression: null;
  };
  responseProtocol: Record<string, unknown>;
  payloadPath: string;
}

/** JSON shape accepted by the visual `ResourceDesignContract` endpoint. */
export interface ResourceDesignContractPayload {
  contractId: string;
  resourceId: string;
  displayName: string;
  description: string;
  tags: string[];
  requestSchema: SchemaEnvelopePayload;
  responseSchema: SchemaEnvelopePayload;
  examples: Record<string, unknown>;
  status: 'ACTIVE';
}

/** The schema envelope used by visual resource contracts. */
export interface SchemaEnvelopePayload {
  format: 'json-schema';
  version: '2020-12';
  schema: JsonSchema;
}

/** Maximum object/array nesting depth explored by {@link inferSchema}. */
export const MAX_DEPTH = 6;

/** Maximum number of schema nodes explored by {@link inferSchema}. */
export const MAX_NODES = 500;

const OPAQUE_SCHEMA: JsonSchema = { additionalProperties: true };

/**
 * Convert a pure form model to the JSON wire shape of `ResourceDescriptor`.
 *
 * <p>The conversion deliberately uses the backend record field names
 * (`pathExpressions` and `codePath`) rather than the older static demo names.
 * The input model is never modified.</p>
 *
 * @param form external API form state
 * @return a descriptor payload suitable for the runtime admin endpoint
 */
export function externalApiFormToDescriptor(form: ExternalApiFormModel): ResourceDescriptorPayload {
  const mapping = {
    pathExpressions: expressionsFor(form.params, 'path'),
    queryExpressions: expressionsFor(form.params, 'query'),
    headerExpressions: expressionsFor(form.params, 'header'),
    cookieExpressions: {},
    bodyExpression: null,
  };
  return {
    resourceId: form.resourceId,
    urlTemplate: form.urlTemplate,
    method: form.method,
    defaultHeaders: { Accept: 'application/json' },
    authStrategy: null,
    defaultTimeout: 'PT5S',
    parameterMapping: mapping,
    responseProtocol: responseProtocolPayload(form.responseProtocol),
    payloadPath: form.payloadPath,
  };
}

/**
 * Convert a pure form model to the backend visual `ResourceDesignContract` shape.
 *
 * <p>Parameter names become a conservative string request schema, while the
 * selected output schema becomes the contract response schema. No transport
 * request is made and no graph/scenario protocol is created.</p>
 *
 * @param form external API form state
 * @return a visual design-contract payload suitable for the admin endpoint
 */
export function toDesignContract(form: ExternalApiFormModel): ResourceDesignContractPayload {
  const names = [...new Set(form.params.map((parameter) => parameter.name))].sort();
  const properties = Object.fromEntries(names.map((name) => [name, { type: 'string' }])) as JsonSchema;
  return {
    contractId: form.resourceId,
    resourceId: form.resourceId,
    displayName: form.displayName,
    description: '',
    tags: [],
    requestSchema: schemaEnvelope({
      type: 'object',
      properties,
      required: names,
      additionalProperties: false,
    }),
    responseSchema: schemaEnvelope(form.outputSchema.schema),
    examples: {},
    status: 'ACTIVE',
  };
}

/**
 * Infer a bounded JSON Schema from a JSON-like sample.
 *
 * <p>Objects are traversed in sorted-key order for deterministic output;
 * arrays use their first item. Null-valued properties remain in `properties`
 * but are omitted from `required`. Once either safety budget is exceeded, the
 * current branch becomes an unconstrained object schema.</p>
 *
 * @param sample sample JSON-like value
 * @returns a new schema object; `sample` is not changed
 */
export function inferSchema(sample: unknown): JsonSchema {
  const budget = { nodes: 0 };
  return inferValue(sample, 0, budget, new WeakSet<object>());
}

function inferValue(value: unknown, depth: number, budget: { nodes: number }, ancestors: WeakSet<object>): JsonSchema {
  if (depth > MAX_DEPTH || budget.nodes >= MAX_NODES) {
    return { ...OPAQUE_SCHEMA };
  }
  budget.nodes += 1;
  if (value === null) return { type: 'null' };
  if (Array.isArray(value)) {
    if (ancestors.has(value)) return { ...OPAQUE_SCHEMA };
    ancestors.add(value);
    const result = { type: 'array', items: value.length ? inferValue(value[0], depth + 1, budget, ancestors) : {} };
    ancestors.delete(value);
    return result;
  }
  if (typeof value === 'string') return { type: 'string' };
  if (typeof value === 'boolean') return { type: 'boolean' };
  if (typeof value === 'number') return { type: Number.isInteger(value) ? 'integer' : 'number' };
  if (typeof value !== 'object') return { ...OPAQUE_SCHEMA };
  if (ancestors.has(value)) return { ...OPAQUE_SCHEMA };
  ancestors.add(value);
  const properties: Record<string, JsonSchema> = {};
  const required: string[] = [];
  for (const key of Object.keys(value).sort()) {
    const child = (value as Record<string, unknown>)[key];
    properties[key] = inferValue(child, depth + 1, budget, ancestors);
    if (child !== null) required.push(key);
  }
  ancestors.delete(value);
  return { type: 'object', properties, required, additionalProperties: false };
}

function expressionsFor(params: ExternalApiParameter[], location: ExternalApiParameter['in']): Record<string, string> {
  return Object.fromEntries(
    params
      .filter((parameter) => parameter.in === location)
      .sort((left, right) => left.name.localeCompare(right.name))
      .map((parameter) => [parameter.name, parameter.from]),
  );
}

function responseProtocolPayload(protocol: ExternalApiResponseProtocol): Record<string, unknown> {
  switch (protocol.kind) {
    case 'HttpStatus':
      return { type: 'httpStatus' };
    case 'StatusCodes':
      return { type: 'statusCodes', successCodes: [...protocol.success] };
    case 'BodyFlag':
      return { type: 'bodyFlag', flagPath: protocol.flagField };
    case 'BodyCode':
      return {
        type: 'bodyCode',
        codePath: protocol.codeField,
        successValues: [...protocol.successCodes],
        messagePath: protocol.messageField ?? null,
      };
  }
}

function schemaEnvelope(schema: JsonSchema): SchemaEnvelopePayload {
  return { format: 'json-schema', version: '2020-12', schema };
}
