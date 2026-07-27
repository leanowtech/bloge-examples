import type { SchemaEnvelope } from '../types';

export interface SchemaFieldRow {
  path: string;
  name: string;
  type: string;
  required: boolean;
  depth: number;
  description: string;
  constraints: string[];
  schema: Record<string, unknown>;
}

/** Projects native JSON Schema object and array structure into searchable workbench rows. */
export function projectSchemaFields(envelope: SchemaEnvelope): SchemaFieldRow[] {
  const rows: SchemaFieldRow[] = [];
  visitSchema(normalizeSchema(envelope.schema), '', '', false, 0, rows, new Set<object>());
  return rows;
}

/** Reads either a dotted path or JSON Pointer from a JSON-shaped value. */
export function valueAtPath(value: unknown, path: string): unknown {
  const segments = pathSegments(path);
  let cursor = value;
  for (const segment of segments) {
    if (Array.isArray(cursor)) {
      const index = Number(segment);
      if (!Number.isInteger(index) || index < 0 || index >= cursor.length) {
        return undefined;
      }
      cursor = cursor[index];
      continue;
    }
    if (!isRecord(cursor) || !Object.prototype.hasOwnProperty.call(cursor, segment)) {
      return undefined;
    }
    cursor = cursor[segment];
  }
  return cursor;
}

/** Returns the closest native schema node for a dotted path or JSON Pointer. */
export function schemaAtPath(
  envelope: SchemaEnvelope,
  path: string,
): Record<string, unknown> {
  let schema = normalizeSchema(envelope.schema);
  for (const segment of pathSegments(path)) {
    const properties = recordValue(schema.properties);
    if (properties && isRecord(properties[segment])) {
      schema = normalizeSchema(properties[segment] as Record<string, unknown>);
      continue;
    }
    if (schemaType(schema) === 'array' && isRecord(schema.items)) {
      schema = normalizeSchema(schema.items);
      continue;
    }
    return {};
  }
  return schema;
}

/** Replaces one path without mutating the original JSON value. */
export function withValueAtPath(value: unknown, path: string, nextValue: unknown): unknown {
  const segments = pathSegments(path);
  if (segments.length === 0) {
    return nextValue;
  }
  const root = isRecord(value) ? { ...value } : {};
  let cursor: Record<string, unknown> = root;
  segments.forEach((segment, index) => {
    if (index === segments.length - 1) {
      cursor[segment] = nextValue;
      return;
    }
    const existing = cursor[segment];
    const child = isRecord(existing) ? { ...existing } : {};
    cursor[segment] = child;
    cursor = child;
  });
  return root;
}

/** Determines the most useful control type for one native JSON Schema node. */
export function schemaType(schema: Record<string, unknown>): string {
  const type = schema.type;
  if (typeof type === 'string') {
    return type;
  }
  if (Array.isArray(type)) {
    const concrete = type.find((entry) => typeof entry === 'string' && entry !== 'null');
    return typeof concrete === 'string' ? concrete : 'unknown';
  }
  if (isRecord(schema.properties)) {
    return 'object';
  }
  if (schema.items !== undefined) {
    return 'array';
  }
  if (Array.isArray(schema.enum) && schema.enum.length > 0) {
    return typeof schema.enum[0];
  }
  return 'unknown';
}

export function normalizeSchema(schema: Record<string, unknown>): Record<string, unknown> {
  const allOf = Array.isArray(schema.allOf) ? schema.allOf.filter(isRecord) : [];
  if (allOf.length === 0) {
    const branch = firstRecord(schema.oneOf) ?? firstRecord(schema.anyOf);
    return branch ? { ...schema, ...branch } : schema;
  }
  return allOf.reduce<Record<string, unknown>>((merged, branch) => {
    const next = { ...merged, ...branch };
    const mergedProperties = {
      ...(recordValue(merged.properties) ?? {}),
      ...(recordValue(branch.properties) ?? {}),
    };
    if (Object.keys(mergedProperties).length > 0) {
      next.properties = mergedProperties;
    }
    const required = new Set([
      ...stringArray(merged.required),
      ...stringArray(branch.required),
    ]);
    if (required.size > 0) {
      next.required = Array.from(required);
    }
    return next;
  }, { ...schema });
}

function visitSchema(
  rawSchema: Record<string, unknown>,
  path: string,
  name: string,
  required: boolean,
  depth: number,
  rows: SchemaFieldRow[],
  ancestors: Set<object>,
): void {
  if (ancestors.has(rawSchema) || depth > 24) {
    return;
  }
  const schema = normalizeSchema(rawSchema);
  const type = schemaType(schema);
  if (path) {
    rows.push({
      path,
      name,
      type,
      required,
      depth,
      description: typeof schema.description === 'string' ? schema.description : '',
      constraints: schemaConstraints(schema),
      schema,
    });
  }
  const nextAncestors = new Set(ancestors).add(rawSchema);
  if (type === 'object') {
    const properties = recordValue(schema.properties) ?? {};
    const requiredFields = new Set(stringArray(schema.required));
    Object.entries(properties).forEach(([propertyName, propertySchema]) => {
      if (!isRecord(propertySchema)) {
        return;
      }
      visitSchema(
        propertySchema,
        path ? `${path}.${propertyName}` : propertyName,
        propertyName,
        requiredFields.has(propertyName),
        path ? depth + 1 : depth,
        rows,
        nextAncestors,
      );
    });
  } else if (type === 'array' && isRecord(schema.items)) {
    const itemPath = `${path || '$'}[]`;
    visitSchema(schema.items, itemPath, 'item', false, depth + 1, rows, nextAncestors);
  }
}

function schemaConstraints(schema: Record<string, unknown>): string[] {
  const constraints: string[] = [];
  if (Array.isArray(schema.enum)) {
    constraints.push(`enum ${schema.enum.map(String).join(' | ')}`);
  }
  if (typeof schema.format === 'string') {
    constraints.push(schema.format);
  }
  if (typeof schema.minimum === 'number') {
    constraints.push(`min ${schema.minimum}`);
  }
  if (typeof schema.maximum === 'number') {
    constraints.push(`max ${schema.maximum}`);
  }
  if (typeof schema.minLength === 'number') {
    constraints.push(`minLength ${schema.minLength}`);
  }
  if (typeof schema.pattern === 'string') {
    constraints.push(`pattern ${schema.pattern}`);
  }
  return constraints;
}

function pathSegments(path: string): string[] {
  const trimmed = path.trim();
  if (!trimmed) {
    return [];
  }
  if (trimmed.startsWith('/')) {
    return trimmed
      .split('/')
      .slice(1)
      .map((segment) => segment.replace(/~1/g, '/').replace(/~0/g, '~'))
      .filter(Boolean);
  }
  return trimmed.replace(/^\$\.?/, '').split('.').filter(Boolean);
}

function firstRecord(value: unknown): Record<string, unknown> | undefined {
  return Array.isArray(value) ? value.find(isRecord) : undefined;
}

function stringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === 'string') : [];
}

function recordValue(value: unknown): Record<string, unknown> | null {
  return isRecord(value) ? value : null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
