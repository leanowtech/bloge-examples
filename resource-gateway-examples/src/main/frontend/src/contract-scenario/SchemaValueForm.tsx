import { useEffect, useState, type ReactNode } from 'react';
import { X } from 'lucide-react';

import type { SchemaEnvelope } from '../types';
import { useI18n } from '../i18n/I18nProvider';
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
  const { t } = useI18n();
  const nullable = nullableSchema(props.schema);
  if (nullable) {
    return (
      <fieldset className="schema-value-nullable">
        <legend>{schemaTitle(props.schema, props.label)}</legend>
        <label className="schema-null-toggle">
          <input
            type="checkbox"
            aria-label={`${props.path} is null`}
            checked={props.value === null}
            onChange={(event) => props.onChange(
              event.target.checked ? null : sampleForSchema(nullable),
            )}
          />
          <span>{t('Use null')}</span>
        </label>
        {props.value !== null && (
          <SchemaValueControl
            {...props}
            schema={nullable}
          />
        )}
      </fieldset>
    );
  }

  const union = unionBranches(props.schema);
  if (union.length > 1) {
    return <UnionValueControl {...props} branches={union} />;
  }

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
          data-schema-path={props.path}
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
            const childPath = childSchemaPath(props.path, propertyName);
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
        <legend>{title}<small>{t('{count} items', { count: values.length })}</small></legend>
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
                title={t('Remove {title} {index}', { title, index: index + 1 })}
                aria-label={t('Remove {title} {index}', { title, index: index + 1 })}
                onClick={() => props.onChange(values.filter((_, candidate) => candidate !== index))}
              >
                <X size={14} aria-hidden="true" />
              </button>
            </div>
          ))}
          <button
            type="button"
            className="secondary compact schema-array-add"
            onClick={() => props.onChange([...values, sampleForSchema(itemSchema)])}
          >
            + {t('Add item')}
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
          data-schema-path={props.path}
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
          data-schema-path={props.path}
          step={type === 'integer' ? 1 : 'any'}
          min={typeof schema.minimum === 'number' ? schema.minimum : undefined}
          max={typeof schema.maximum === 'number' ? schema.maximum : undefined}
          value={typeof props.value === 'number' ? props.value : ''}
          onChange={(event) => {
            if (event.target.value === '') {
              props.onChange(undefined);
              return;
            }
            const parsed = type === 'integer'
              ? Number.parseInt(event.target.value, 10)
              : Number.parseFloat(event.target.value);
            props.onChange(Number.isFinite(parsed) ? parsed : undefined);
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
          data-schema-path={props.path}
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

function UnionValueControl({
  branches,
  ...props
}: SchemaValueControlProps & { branches: Record<string, unknown>[] }) {
  const { t } = useI18n();
  const activeIndex = activeUnionBranch(branches, props.value);
  const active = branches[activeIndex] ?? branches[0];
  const title = schemaTitle(props.schema, props.label);
  return (
    <fieldset className="schema-value-union">
      <legend>{title}</legend>
      <label className="schema-union-selector">
        <span>{t('Value shape')}</span>
        <select
          aria-label={`${props.path} variant`}
          value={activeIndex}
          onChange={(event) => {
            const branch = branches[Number(event.target.value)] ?? branches[0];
            props.onChange(sampleForSchema(branch));
          }}
        >
          {branches.map((branch, index) => (
            <option key={`${branchLabel(branch, index)}:${index}`} value={index}>
              {branchLabel(branch, index)}
            </option>
          ))}
        </select>
      </label>
      {schemaType(normalizeSchema(active)) !== 'null' && (
        <SchemaValueControl
          {...props}
          schema={active}
        />
      )}
    </fieldset>
  );
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
  const { t } = useI18n();
  return (
    <span className="schema-field-label">
      <strong>{title}</strong>
      <small>{t(required ? 'Required' : 'Optional')}{description ? ` · ${description}` : ''}</small>
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
  const { t } = useI18n();
  const canonicalText = JSON.stringify(value ?? {}, null, 2);
  const [text, setText] = useState(canonicalText);

  useEffect(() => {
    setText(canonicalText);
  }, [canonicalText]);

  return (
    <label className="schema-value-field json">
      <FieldLabel title={title} required={required} description={description || t('Open JSON value')} />
      <textarea
        aria-label={path}
        data-schema-path={path}
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
  const branches = unionBranches(rawSchema);
  if (branches.length > 0) {
    return sampleForSchema(branches[0]);
  }
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

function unionBranches(schema: Record<string, unknown>): Record<string, unknown>[] {
  const explicit = Array.isArray(schema.oneOf)
    ? schema.oneOf.filter(isRecord)
    : Array.isArray(schema.anyOf)
      ? schema.anyOf.filter(isRecord)
      : [];
  if (explicit.length > 1) {
    return explicit;
  }
  return Array.isArray(schema.type) && schema.type.length > 1
    ? schema.type
      .filter((entry): entry is string => typeof entry === 'string')
      .map((type) => ({ ...schema, type, oneOf: undefined, anyOf: undefined }))
    : [];
}

function nullableSchema(schema: Record<string, unknown>): Record<string, unknown> | null {
  if (!Array.isArray(schema.type) || !schema.type.includes('null')) {
    return null;
  }
  const concrete = schema.type.filter(
    (entry): entry is string => typeof entry === 'string' && entry !== 'null',
  );
  if (concrete.length !== 1) {
    return null;
  }
  return { ...schema, type: concrete[0] };
}

function activeUnionBranch(
  branches: Record<string, unknown>[],
  value: unknown,
): number {
  const exact = branches.findIndex((branch) => valueMatchesSchema(value, branch));
  return exact >= 0 ? exact : 0;
}

function valueMatchesSchema(value: unknown, rawSchema: Record<string, unknown>): boolean {
  const schema = normalizeSchema(rawSchema);
  const type = schemaType(schema);
  if (type === 'null') return value === null;
  if (type === 'array') return Array.isArray(value);
  if (type === 'object') {
    if (!isRecord(value)) return false;
    const required = stringArray(schema.required);
    return required.every((name) => Object.prototype.hasOwnProperty.call(value, name));
  }
  if (type === 'integer') return typeof value === 'number' && Number.isInteger(value);
  if (type === 'number') return typeof value === 'number';
  return typeof value === type;
}

function branchLabel(schema: Record<string, unknown>, index: number): string {
  if (typeof schema.title === 'string' && schema.title.trim()) {
    return schema.title;
  }
  const type = schemaType(normalizeSchema(schema));
  return type === 'unknown' ? `Variant ${index + 1}` : type;
}

function schemaTitle(schema: Record<string, unknown>, fallback: string): string {
  return typeof schema.title === 'string' && schema.title.trim()
    ? schema.title
    : fallback;
}

function enumValue(value: unknown): string {
  return JSON.stringify(value) ?? 'null';
}

function childSchemaPath(parent: string, propertyName: string): string {
  if (parent.startsWith('/')) {
    return `${parent}/${propertyName.replace(/~/g, '~0').replace(/\//g, '~1')}`;
  }
  return parent === '$' ? propertyName : `${parent}.${propertyName}`;
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
