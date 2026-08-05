import { useMemo, useState } from 'react';

import { useI18n } from '../i18n/I18nProvider';
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
  const { t } = useI18n();
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
    <section className="contract-schema-tree" aria-label={t('{label} schema', { label })}>
      <div className="contract-schema-tree-head">
        <div>
          <span>{label}</span>
          <strong>{rootLabel}</strong>
        </div>
        <label>
          <span className="sr-only">{t('Search {label} fields', { label })}</span>
          <input
            type="search"
            value={query}
            placeholder={t('Search fields')}
            onChange={(event) => setQuery(event.target.value)}
          />
        </label>
      </div>
      <div className="contract-schema-table" role="table">
        <div className="contract-schema-row heading" role="row">
          <span role="columnheader">{t('Field')}</span>
          <span role="columnheader">{t('Type')}</span>
          <span role="columnheader">{t('Rule')}</span>
          <span role="columnheader">{t('Details')}</span>
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
              {t(field.required ? 'Required' : 'Optional')}
            </span>
            <span role="cell" title={field.description}>
              {field.constraints.join(', ') || field.description || t('No extra constraint')}
            </span>
          </div>
        ))}
        {visibleFields.length === 0 && (
          <p className="contract-schema-empty">
            {t(fields.length === 0 ? 'Open object: no named fields.' : 'No fields match this search.')}
          </p>
        )}
      </div>
    </section>
  );
}
