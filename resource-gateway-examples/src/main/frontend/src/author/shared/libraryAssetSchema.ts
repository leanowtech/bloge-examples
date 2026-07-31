import type {
  SchemaEnvelope,
  VisualLibraryAuthoringDocument,
} from '../../types';

export interface FunctionParameterProjection {
  name: string;
  optional: boolean;
}

export interface FunctionSignatureProjection {
  parameters: FunctionParameterProjection[];
  inputSchema: SchemaEnvelope;
  outputSchema: SchemaEnvelope;
}

const PRIMITIVES: Record<string, Record<string, unknown>> = {
  any: {},
  unknown: {},
  string: { type: 'string' },
  boolean: { type: 'boolean' },
  number: { type: 'number' },
  integer: { type: 'integer' },
  object: { type: 'object', additionalProperties: true },
  null: { type: 'null' },
};

/** Converts the compact Library Workbench type language into native JSON Schema. */
export function compactValueSchema(
  value: unknown,
  types: Record<string, unknown> = {},
  ancestors: ReadonlySet<string> = new Set(),
): Record<string, unknown> {
  if (typeof value === 'string') {
    const trimmed = value.trim();
    if (trimmed.endsWith('[]')) {
      return {
        type: 'array',
        items: compactValueSchema(trimmed.slice(0, -2), types, ancestors),
      };
    }
    if (trimmed.includes('|')) {
      return {
        oneOf: trimmed.split('|').map((entry) => compactValueSchema(
          entry.trim(),
          types,
          ancestors,
        )),
      };
    }
    if (PRIMITIVES[trimmed]) {
      return { ...PRIMITIVES[trimmed] };
    }
    if (Object.prototype.hasOwnProperty.call(types, trimmed) && !ancestors.has(trimmed)) {
      return compactValueSchema(
        types[trimmed],
        types,
        new Set(ancestors).add(trimmed),
      );
    }
    return { type: 'object', title: trimmed, additionalProperties: true };
  }
  if (!isRecord(value)) {
    return {};
  }
  if (
    value.type !== undefined
    || value.properties !== undefined
    || value.items !== undefined
    || value.oneOf !== undefined
    || value.anyOf !== undefined
    || value.allOf !== undefined
  ) {
    return structuredClone(value);
  }
  if (Array.isArray(value.enum)) {
    const first = value.enum.find((entry) => entry !== null);
    return {
      ...(first === undefined ? {} : { type: typeof first }),
      enum: structuredClone(value.enum),
    };
  }
  if (isRecord(value.fields)) {
    return compactRecordSchema(value.fields, types, ancestors);
  }
  return compactRecordSchema(value, types, ancestors);
}

export function operatorInputSchema(
  document: VisualLibraryAuthoringDocument,
  operatorRef: string,
): SchemaEnvelope {
  return envelope(compactRecordSchema(
    document.operators?.[operatorRef]?.input ?? {},
    document.types ?? {},
  ));
}

export function operatorOutputSchema(
  document: VisualLibraryAuthoringDocument,
  operatorRef: string,
): SchemaEnvelope {
  return envelope(compactRecordSchema(
    document.operators?.[operatorRef]?.output ?? {},
    document.types ?? {},
  ));
}

export function operatorConfigSchema(
  document: VisualLibraryAuthoringDocument,
  operatorRef: string,
): SchemaEnvelope {
  return envelope(compactValueSchema(
    document.operators?.[operatorRef]?.config ?? { type: 'object', additionalProperties: true },
    document.types ?? {},
  ));
}

export function functionSignatureSchema(
  document: VisualLibraryAuthoringDocument,
  functionRef: string,
): FunctionSignatureProjection {
  const signature = document.functions?.[functionRef]?.signatures?.[0]
    ?? document.functions?.[functionRef]?.signature
    ?? '(value: unknown) -> unknown';
  const match = signature.match(/^\s*\((.*)\)\s*->\s*(.+?)\s*$/);
  const parameters = splitSignatureParameters(match?.[1] ?? '').map((parameter, index) => {
    const parsed = parameter.match(/^\s*(?:\.\.\.)?([A-Za-z_$][\w$]*)(\?)?\s*:\s*(.+?)\s*$/);
    return {
      name: parsed?.[1] ?? `arg${index + 1}`,
      optional: Boolean(parsed?.[2]),
      schema: compactValueSchema(parsed?.[3] ?? 'unknown', document.types ?? {}),
    };
  });
  return {
    parameters: parameters.map(({ name, optional }) => ({ name, optional })),
    inputSchema: envelope({
      type: 'object',
      properties: Object.fromEntries(parameters.map(({ name, schema }) => [name, schema])),
      required: parameters.filter(({ optional }) => !optional).map(({ name }) => name),
      additionalProperties: false,
    }),
    outputSchema: envelope(compactValueSchema(match?.[2] ?? 'unknown', document.types ?? {})),
  };
}

export function functionArgsObject(
  args: unknown[],
  projection: FunctionSignatureProjection,
): Record<string, unknown> {
  return Object.fromEntries(projection.parameters.map((parameter, index) => (
    [parameter.name, args[index]]
  )));
}

export function functionArgsArray(
  value: unknown,
  projection: FunctionSignatureProjection,
): unknown[] {
  const object = isRecord(value) ? value : {};
  const lastDefined = projection.parameters.reduce(
    (last, parameter, index) => object[parameter.name] === undefined ? last : index,
    -1,
  );
  return projection.parameters
    .slice(0, lastDefined + 1)
    .map((parameter) => object[parameter.name]);
}

function compactRecordSchema(
  fields: Record<string, unknown>,
  types: Record<string, unknown>,
  ancestors: ReadonlySet<string> = new Set(),
): Record<string, unknown> {
  const properties: Record<string, unknown> = {};
  const required: string[] = [];
  Object.entries(fields).forEach(([rawName, definition]) => {
    const optional = rawName.endsWith('?');
    const name = optional ? rawName.slice(0, -1) : rawName;
    properties[name] = compactValueSchema(definition, types, ancestors);
    if (!optional) required.push(name);
  });
  return {
    type: 'object',
    properties,
    required,
    additionalProperties: false,
  };
}

function splitSignatureParameters(value: string): string[] {
  const parameters: string[] = [];
  let start = 0;
  let depth = 0;
  for (let index = 0; index < value.length; index += 1) {
    const character = value[index];
    if ('[({<'.includes(character)) depth += 1;
    if ('])}>'.includes(character)) depth = Math.max(0, depth - 1);
    if (character === ',' && depth === 0) {
      parameters.push(value.slice(start, index).trim());
      start = index + 1;
    }
  }
  const tail = value.slice(start).trim();
  if (tail) parameters.push(tail);
  return parameters.filter(Boolean);
}

function envelope(schema: Record<string, unknown>): SchemaEnvelope {
  return { format: 'json-schema', version: '2020-12', schema };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
