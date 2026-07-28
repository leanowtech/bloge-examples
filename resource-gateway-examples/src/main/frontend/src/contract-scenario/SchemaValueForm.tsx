import { useEffect, useState, type ReactNode } from 'react';

import type { SchemaEnvelope } from '../types';
import {
  isSensitiveSchema,
  normalizeSchema,
  schemaType,
} from './schemaWorkbench';

interface SchemaValueFormProps {
  envelope?: SchemaEnvelope;
  schema?: Record<string, unknown>;
  value: unknown;
  onChange: (value: unknown) => void;
  label?: string;
  path?: string;
  compact?: boolean;
}

/** Native JSON Schema form used by Scenario Given, RETURN, and Then sections. */
export default function SchemaValueForm({
  envelope,
  schema,
  value,
  onChange,
  label = 'Value',
  path = '$',
  compact = false,
}: SchemaValueFormProps) {
  const rootSchema = normalizeSchema(schema ?? envelope?.schema ?? {});
  return (
    <div className={`schema-value-form ${compact ? 'compact' : ''}`}>
      <SchemaValueControl
        schema={rootSchema}
        value={value}
        onChange={onChange}
        label={label}
        path={path}
        required
        depth={0}
      />
    </div>
  );
}

interface SchemaValueControlProps {
  schema: Record<string, unknown>;
  value: unknown;
  onChange: (value: unknown) => void;
  label: string;
  path: string;
  required: boolean;
  depth: number;
}

function SchemaValueControl(props: SchemaValueControlProps): ReactNode {
  const schema = normalizeSchema(props.schema);
  const type = schemaType(schema);
  const description = typeof schema.description === 'string' ? schema.description : '';
  const title = typeof schema.title === 'string' ? schema.title : props.label;

  if (Array.isArray(schema.enum) && schema.enum.length > 0) {
    const enumValues = schema.enum;
    return (
      <label className="schema-value-field">
        <FieldLabel title={title} required={props.required} description={description} />
        <select
          aria-label={props.path}
          value={enumValue(props.value)}
          onChange={(event) => {
            const selected = enumValues.find((entry) => enumValue(entry) === event.target.value);
            props.onChange(selected);
          }}
        >
          {enumValues.map((entry) => (
            <option value={enumValue(entry)} key={enumValue(entry)}>{String(entry)}</option>
          ))}
        </select>
      </label>
    );
  }

  if (type === 'object') {
    const properties = recordValue(schema.properties);
    const requiredFields = new Set(stringArray(schema.required));
    if (Object.keys(properties).length === 0) {
      return <JsonFallback {...props} schema={schema} title={title} description={description} />;
    }
    const objectValue = recordValue(props.value);
    return (
      <fieldset className="schema-value-object">
        <legend>
          {title}
          {props.depth === 0 && <small>{props.path}</small>}
        </legend>
        <div className="schema-value-object-grid">
          {Object.entries(properties).map(([propertyName, propertySchema]) => {
            if (!isRecord(propertySchema)) {
              return null;
            }
            const childPath = props.path === '$' ? propertyName : `${props.path}.${propertyName}`;
            return (
              <SchemaValueControl
                key={propertyName}
                schema={propertySchema}
                value={objectValue[propertyName]}
                onChange={(nextValue) => props.onChange({
                  ...objectValue,
                  [propertyName]: nextValue,
                })}
                label={propertyName}
                path={childPath}
                required={requiredFields.has(propertyName)}
                depth={props.depth + 1}
              />
            );
          })}
        </div>
      </fieldset>
    );
  }

  if (type === 'array') {
    const values = Array.isArray(props.value) ? props.value : [];
    const itemSchema = isRecord(schema.items) ? schema.items : {};
    return (
      <fieldset className="schema-value-array">
        <legend>{title}<small>{values.length} items</small></legend>
        <div className="schema-array-items">
          {values.map((entry, index) => (
            <div className="schema-array-item" key={`${props.path}-${index}`}>
              <SchemaValueControl
                schema={itemSchema}
                value={entry}
                onChange={(nextValue) => {
                  const next = [...values];
                  next[index] = nextValue;
                  props.onChange(next);
                }}
                label={`${title} ${index + 1}`}
                path={`${props.path}[${index}]`}
                required
                depth={props.depth + 1}
              />
              <button
                type="button"
                className="icon-button danger"
                title={`Remove ${title} ${index + 1}`}
                aria-label={`Remove ${title} ${index + 1}`}
                onClick={() => props.onChange(values.filter((_, candidate) => candidate !== index))}
              >
                ×
              </button>
            </div>
          ))}
          <button
            type="button"
            className="secondary compact schema-array-add"
            onClick={() => props.onChange([...values, sampleForSchema(itemSchema)])}
          >
            + Add item
          </button>
        </div>
      </fieldset>
    );
  }

  if (type === 'boolean') {
    return (
      <label className="schema-value-field checkbox">
        <input
          type="checkbox"
          aria-label={props.path}
          checked={Boolean(props.value)}
          onChange={(event) => props.onChange(event.target.checked)}
        />
        <FieldLabel title={title} required={props.required} description={description} />
      </label>
    );
  }

  if (type === 'number' || type === 'integer') {
    return (
      <label className="schema-value-field">
        <FieldLabel title={title} required={props.required} description={description} />
        <input
          type="number"
          aria-label={props.path}
          step={type === 'integer' ? 1 : 'any'}
          min={typeof schema.minimum === 'number' ? schema.minimum : undefined}
          max={typeof schema.maximum === 'number' ? schema.maximum : undefined}
          value={typeof props.value === 'number' ? props.value : ''}
          onChange={(event) => {
            const parsed = type === 'integer'
              ? Number.parseInt(event.target.value, 10)
              : Number.parseFloat(event.target.value);
            props.onChange(Number.isFinite(parsed) ? parsed : 0);
          }}
        />
      </label>
    );
  }

  if (type === 'string') {
    const sensitive = isSensitiveSchema(schema);
    return (
      <label className="schema-value-field">
        <FieldLabel title={title} required={props.required} description={description} />
        <input
          type={sensitive
            ? 'password'
            : schema.format === 'date'
              ? 'date'
              : schema.format === 'date-time'
                ? 'datetime-local'
                : 'text'}
          aria-label={props.path}
          autoComplete={sensitive ? 'off' : undefined}
          value={typeof props.value === 'string' ? props.value : ''}
          minLength={typeof schema.minLength === 'number' ? schema.minLength : undefined}
          maxLength={typeof schema.maxLength === 'number' ? schema.maxLength : undefined}
          pattern={typeof schema.pattern === 'string' ? schema.pattern : undefined}
          onChange={(event) => props.onChange(event.target.value)}
        />
      </label>
    );
  }

  return <JsonFallback {...props} schema={schema} title={title} description={description} />;
}

function FieldLabel({
  title,
  required,
  description,
}: {
  title: string;
  required: boolean;
  description: string;
}) {
  return (
    <span className="schema-field-label">
      <strong>{title}</strong>
      <small>{required ? 'Required' : 'Optional'}{description ? ` · ${description}` : ''}</small>
    </span>
  );
}

function JsonFallback({
  value,
  onChange,
  path,
  required,
  title,
  description,
}: SchemaValueControlProps & {
  title: string;
  description: string;
}) {
  const canonicalText = JSON.stringify(value ?? {}, null, 2);
  const [text, setText] = useState(canonicalText);

  useEffect(() => {
    setText(canonicalText);
  }, [canonicalText]);

  return (
    <label className="schema-value-field json">
      <FieldLabel title={title} required={required} description={description || 'Open JSON value'} />
      <textarea
        aria-label={path}
        value={text}
        rows={Math.min(8, Math.max(3, text.split('\n').length))}
        onChange={(event) => {
          setText(event.target.value);
          try {
            onChange(JSON.parse(event.target.value) as unknown);
          } catch {
            // Keep the last valid protocol value until the JSON becomes valid again.
          }
        }}
      />
    </label>
  );
}

function sampleForSchema(rawSchema: Record<string, unknown>): unknown {
  const schema = normalizeSchema(rawSchema);
  if (schema.default !== undefined) {
    return schema.default;
  }
  if (Array.isArray(schema.examples) && schema.examples.length > 0) {
    return schema.examples[0];
  }
  if (Array.isArray(schema.enum) && schema.enum.length > 0) {
    return schema.enum[0];
  }
  switch (schemaType(schema)) {
    case 'object':
      return Object.fromEntries(
        Object.entries(recordValue(schema.properties))
          .filter((entry): entry is [string, Record<string, unknown>] => isRecord(entry[1]))
          .map(([key, child]) => [key, sampleForSchema(child)]),
      );
    case 'array':
      return [];
    case 'boolean':
      return false;
    case 'number':
    case 'integer':
      return typeof schema.minimum === 'number' ? schema.minimum : 0;
    case 'string':
      return '';
    default:
      return {};
  }
}

function enumValue(value: unknown): string {
  return JSON.stringify(value) ?? 'null';
}

function stringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === 'string') : [];
}

function recordValue(value: unknown): Record<string, unknown> {
  return isRecord(value) ? value : {};
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
