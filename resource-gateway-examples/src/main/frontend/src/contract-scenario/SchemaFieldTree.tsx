import { useMemo, useState } from 'react';

import type { SchemaEnvelope } from '../types';
import { projectSchemaFields } from './schemaWorkbench';

interface SchemaFieldTreeProps {
  envelope: SchemaEnvelope;
  label: string;
  rootLabel: string;
}

/** Searchable field projection for the Contract Interface tab. */
export default function SchemaFieldTree({
  envelope,
  label,
  rootLabel,
}: SchemaFieldTreeProps) {
  const [query, setQuery] = useState('');
  const fields = useMemo(() => projectSchemaFields(envelope), [envelope]);
  const normalizedQuery = query.trim().toLowerCase();
  const visibleFields = normalizedQuery
    ? fields.filter((field) => [
        field.path,
        field.type,
        field.description,
        ...field.constraints,
      ].join(' ').toLowerCase().includes(normalizedQuery))
    : fields;

  return (
    <section className="contract-schema-tree" aria-label={`${label} schema`}>
      <div className="contract-schema-tree-head">
        <div>
          <span>{label}</span>
          <strong>{rootLabel}</strong>
        </div>
        <label>
          <span className="sr-only">Search {label.toLowerCase()} fields</span>
          <input
            type="search"
            value={query}
            placeholder="Search fields"
            onChange={(event) => setQuery(event.target.value)}
          />
        </label>
      </div>
      <div className="contract-schema-table" role="table">
        <div className="contract-schema-row heading" role="row">
          <span role="columnheader">Field</span>
          <span role="columnheader">Type</span>
          <span role="columnheader">Rule</span>
          <span role="columnheader">Details</span>
        </div>
        {visibleFields.map((field) => (
          <div className="contract-schema-row" role="row" key={field.path}>
            <span
              className="contract-schema-path"
              role="cell"
              style={{ paddingLeft: `${10 + field.depth * 16}px` }}
              title={field.path}
            >
              {field.name}
              <small>{field.path}</small>
            </span>
            <code role="cell">{field.type}</code>
            <span role="cell" className={field.required ? 'required' : 'optional'}>
              {field.required ? 'Required' : 'Optional'}
            </span>
            <span role="cell" title={field.description}>
              {field.constraints.join(', ') || field.description || 'No extra constraint'}
            </span>
          </div>
        ))}
        {visibleFields.length === 0 && (
          <p className="contract-schema-empty">
            {fields.length === 0 ? 'Open object: no named fields.' : 'No fields match this search.'}
          </p>
        )}
      </div>
    </section>
  );
}
