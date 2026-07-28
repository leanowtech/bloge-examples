import type { SchemaEnvelope } from '../../types';
import {
  isSensitiveSchema,
  normalizeSchema,
  projectSchemaFields,
  schemaType,
} from '../../contract-scenario/schemaWorkbench';

export { isSensitiveSchema } from '../../contract-scenario/schemaWorkbench';

export interface JsonObjectCompilation {
  value: Record<string, unknown>;
  error?: string;
}

export interface RunInputIssue {
  path: string;
  code: 'required' | 'type' | 'enum' | 'constraint' | 'additional-property';
  message: string;
}

export interface RunInputAssessment {
  fieldCount: number;
  requiredFieldCount: number;
  missingRequired: string[];
  issues: RunInputIssue[];
  ready: boolean;
}

export interface GraphInputField {
  name: string;
  path: string;
  type: string;
  required: boolean;
  description: string;
  sensitive: boolean;
}

export interface TaskRunContextCompilation extends JsonObjectCompilation {
  source: 'structured' | 'raw';
  conflicts: string[];
}

/** Preserves authored values while adding schema-generated fields and pruning forbidden extras. */
export function reconcileRunInputWithSchema(
  envelope: SchemaEnvelope,
  current: Record<string, unknown>,
  sample: unknown,
): Record<string, unknown> {
  const schema = normalizeSchema(envelope.schema);
  const generated = isRecord(sample) ? sample : {};
  const fields = graphInputFields(envelope);
  const next = Object.fromEntries(fields.map((field) => [
    field.name,
    Object.prototype.hasOwnProperty.call(current, field.name)
      ? current[field.name]
      : generated[field.name],
  ]));
  if (schema.additionalProperties !== false) {
    for (const [key, value] of Object.entries(current)) {
      if (!Object.prototype.hasOwnProperty.call(next, key)) {
        next[key] = value;
      }
    }
  }
  return next;
}

/** Returns the top-level fields that can be bound from graph input to a node input. */
export function graphInputFields(envelope: SchemaEnvelope): GraphInputField[] {
  const schema = normalizeSchema(envelope.schema);
  const properties = recordValue(schema.properties);
  const required = new Set(stringArray(schema.required));
  return Object.entries(properties)
    .filter((entry): entry is [string, Record<string, unknown>] => isRecord(entry[1]))
    .map(([name, propertySchema]) => {
      const normalized = normalizeSchema(propertySchema);
      return {
        name,
        path: name,
        type: schemaType(normalized),
        required: required.has(name),
        description: typeof normalized.description === 'string' ? normalized.description : '',
        sensitive: isSensitiveSchema(normalized),
      };
    });
}

/**
 * Assesses the same structured run input that will be sent to the simulation API.
 *
 * This intentionally validates the JSON Schema subset rendered by SchemaValueForm. The server
 * remains authoritative for complete JSON Schema validation.
 */
export function assessRunInput(
  envelope: SchemaEnvelope,
  value: unknown,
): RunInputAssessment {
  const issues: RunInputIssue[] = [];
  validateSchemaValue(normalizeSchema(envelope.schema), value, '$', issues, true);
  const fields = projectSchemaFields(envelope);
  const missingRequired = issues
    .filter((issue) => issue.code === 'required')
    .map((issue) => issue.path);
  return {
    fieldCount: fields.length,
    requiredFieldCount: fields.filter((field) => field.required).length,
    missingRequired,
    issues,
    ready: issues.length === 0,
  };
}

/**
 * Compiles the v2 run context from contract-generated values plus optional non-contract extras.
 *
 * Extras fail closed when they would shadow a top-level Graph Input field. Raw mode is an explicit
 * takeover path and never silently merges with structured values.
 */
export function compileTaskRunContext({
  runInput,
  extras,
  raw,
  rawMode,
}: {
  runInput: Record<string, unknown>;
  extras: JsonObjectCompilation;
  raw: JsonObjectCompilation;
  rawMode: boolean;
}): TaskRunContextCompilation {
  if (rawMode) {
    return {
      value: raw.value,
      error: raw.error,
      source: 'raw',
      conflicts: [],
    };
  }
  if (extras.error) {
    return {
      value: runInput,
      error: extras.error,
      source: 'structured',
      conflicts: [],
    };
  }
  const conflicts = Object.keys(extras.value)
    .filter((key) => Object.prototype.hasOwnProperty.call(runInput, key))
    .sort();
  if (conflicts.length > 0) {
    return {
      value: runInput,
      error: `Context Extras cannot replace Graph Input: ${conflicts.join(', ')}.`,
      source: 'structured',
      conflicts,
    };
  }
  return {
    value: { ...runInput, ...extras.value },
    source: 'structured',
    conflicts: [],
  };
}

function validateSchemaValue(
  rawSchema: Record<string, unknown>,
  value: unknown,
  path: string,
  issues: RunInputIssue[],
  present: boolean,
): void {
  if (!present) {
    return;
  }
  const schema = normalizeSchema(rawSchema);
  if (value === null && schemaAllowsNull(schema)) {
    return;
  }
  const type = schemaType(schema);
  if (!matchesType(type, value)) {
    issues.push({
      path,
      code: 'type',
      message: `${path} must be ${type}.`,
    });
    return;
  }
  if (Array.isArray(schema.enum) && !schema.enum.some((candidate) => deepEqual(candidate, value))) {
    issues.push({ path, code: 'enum', message: `${path} must use an allowed value.` });
  }
  if (Object.prototype.hasOwnProperty.call(schema, 'const') && !deepEqual(schema.const, value)) {
    issues.push({ path, code: 'constraint', message: `${path} must match its constant value.` });
  }
  if (type === 'object' && isRecord(value)) {
    validateObject(schema, value, path, issues);
  } else if (type === 'array' && Array.isArray(value)) {
    validateArray(schema, value, path, issues);
  } else if (type === 'string' && typeof value === 'string') {
    validateString(schema, value, path, issues);
  } else if ((type === 'number' || type === 'integer') && typeof value === 'number') {
    validateNumber(schema, value, path, issues);
  }
}

function validateObject(
  schema: Record<string, unknown>,
  value: Record<string, unknown>,
  path: string,
  issues: RunInputIssue[],
): void {
  const properties = recordValue(schema.properties);
  for (const name of stringArray(schema.required)) {
    if (!Object.prototype.hasOwnProperty.call(value, name) || value[name] === undefined) {
      const fieldPath = childPath(path, name);
      issues.push({
        path: fieldPath,
        code: 'required',
        message: `${fieldPath} is required.`,
      });
    }
  }
  for (const [name, propertySchema] of Object.entries(properties)) {
    if (!isRecord(propertySchema)) {
      continue;
    }
    validateSchemaValue(
      propertySchema,
      value[name],
      childPath(path, name),
      issues,
      Object.prototype.hasOwnProperty.call(value, name),
    );
  }
  if (schema.additionalProperties === false) {
    for (const name of Object.keys(value)) {
      if (!Object.prototype.hasOwnProperty.call(properties, name)) {
        issues.push({
          path: childPath(path, name),
          code: 'additional-property',
          message: `${childPath(path, name)} is not declared by the Graph Input Contract.`,
        });
      }
    }
  }
}

function validateArray(
  schema: Record<string, unknown>,
  value: unknown[],
  path: string,
  issues: RunInputIssue[],
): void {
  const minItems = numberValue(schema.minItems);
  const maxItems = numberValue(schema.maxItems);
  if (minItems !== undefined && value.length < minItems) {
    issues.push({ path, code: 'constraint', message: `${path} needs at least ${minItems} items.` });
  }
  if (maxItems !== undefined && value.length > maxItems) {
    issues.push({ path, code: 'constraint', message: `${path} allows at most ${maxItems} items.` });
  }
  if (isRecord(schema.items)) {
    value.forEach((entry, index) => {
      validateSchemaValue(schema.items as Record<string, unknown>, entry, `${path}[${index}]`, issues, true);
    });
  }
}

function validateString(
  schema: Record<string, unknown>,
  value: string,
  path: string,
  issues: RunInputIssue[],
): void {
  const minLength = numberValue(schema.minLength);
  const maxLength = numberValue(schema.maxLength);
  if (minLength !== undefined && value.length < minLength) {
    issues.push({ path, code: 'constraint', message: `${path} needs at least ${minLength} characters.` });
  }
  if (maxLength !== undefined && value.length > maxLength) {
    issues.push({ path, code: 'constraint', message: `${path} allows at most ${maxLength} characters.` });
  }
  if (typeof schema.pattern === 'string') {
    try {
      if (!new RegExp(schema.pattern).test(value)) {
        issues.push({ path, code: 'constraint', message: `${path} does not match its required pattern.` });
      }
    } catch {
      // Invalid schema patterns are reported by server-side schema admission.
    }
  }
}

function validateNumber(
  schema: Record<string, unknown>,
  value: number,
  path: string,
  issues: RunInputIssue[],
): void {
  const minimum = numberValue(schema.minimum);
  const maximum = numberValue(schema.maximum);
  if (minimum !== undefined && value < minimum) {
    issues.push({ path, code: 'constraint', message: `${path} must be at least ${minimum}.` });
  }
  if (maximum !== undefined && value > maximum) {
    issues.push({ path, code: 'constraint', message: `${path} must be at most ${maximum}.` });
  }
}

function matchesType(type: string, value: unknown): boolean {
  switch (type) {
    case 'object':
      return isRecord(value);
    case 'array':
      return Array.isArray(value);
    case 'string':
      return typeof value === 'string';
    case 'integer':
      return typeof value === 'number' && Number.isInteger(value);
    case 'number':
      return typeof value === 'number' && Number.isFinite(value);
    case 'boolean':
      return typeof value === 'boolean';
    case 'null':
      return value === null;
    case 'unknown':
      return true;
    default:
      return true;
  }
}

function schemaAllowsNull(schema: Record<string, unknown>): boolean {
  return schema.type === 'null'
    || (Array.isArray(schema.type) && schema.type.includes('null'));
}

function childPath(parent: string, name: string): string {
  return parent === '$' ? name : `${parent}.${name}`;
}

function deepEqual(left: unknown, right: unknown): boolean {
  return JSON.stringify(left) === JSON.stringify(right);
}

function stringArray(value: unknown): string[] {
  return Array.isArray(value)
    ? value.filter((entry): entry is string => typeof entry === 'string')
    : [];
}

function numberValue(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

function recordValue(value: unknown): Record<string, unknown> {
  return isRecord(value) ? value : {};
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
