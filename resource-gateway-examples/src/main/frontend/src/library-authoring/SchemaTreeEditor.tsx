import { type CSSProperties, useMemo, useState } from 'react';
import { X } from 'lucide-react';

import { useI18n } from '../i18n/I18nProvider';
import {
  compactFieldRows,
  compactFieldsFromRows,
  nestedCompactFields,
  type CompactFieldRow,
} from './model';

interface SchemaTreeEditorProps {
  title: string;
  fields: Record<string, unknown>;
  basePath: string;
  onChange: (fields: Record<string, unknown>) => void;
  onInferSamples?: () => void;
}

type SchemaView = 'tree' | 'table';

export default function SchemaTreeEditor({
  title,
  fields,
  basePath,
  onChange,
  onInferSamples,
}: SchemaTreeEditorProps) {
  const { t } = useI18n();
  const [view, setView] = useState<SchemaView>('tree');
  const rows = useMemo(() => compactFieldRows(fields), [fields]);

  const update = (nextRows: CompactFieldRow[]) => onChange(compactFieldsFromRows(nextRows));
  const patch = (index: number, patchValue: Partial<CompactFieldRow>) => update(
    rows.map((row, rowIndex) => rowIndex === index ? { ...row, ...patchValue } : row),
  );
  const add = () => update([
    ...rows,
    {
      id: `new:${rows.length}`,
      name: `field${rows.length + 1}`,
      type: 'string',
      required: true,
    },
  ]);
  const remove = (index: number) => update(rows.filter((_, rowIndex) => rowIndex !== index));

  return (
    <section className="schema-tree-editor">
      <header>
        <div>
          <h4>{title}</h4>
          <span>{t('{count} fields', { count: rows.length })}</span>
        </div>
        <div className="schema-tree-actions">
          {onInferSamples && (
            <button
              type="button"
              className="secondary compact"
              onClick={onInferSamples}
              data-testid={`infer-${title.toLowerCase()}-from-samples`}
            >
              {t('Infer from samples')}
            </button>
          )}
          <div className="segmented-control" aria-label={t('{title} view', { title })}>
            <button
              type="button"
              className={view === 'tree' ? 'active' : ''}
              aria-pressed={view === 'tree'}
              onClick={() => setView('tree')}
            >
              {t('Tree')}
            </button>
            <button
              type="button"
              className={view === 'table' ? 'active' : ''}
              aria-pressed={view === 'table'}
              onClick={() => setView('table')}
            >
              {t('Table')}
            </button>
          </div>
        </div>
      </header>
      {view === 'tree' ? (
        <div className="schema-field-tree" role="tree">
          <div className="schema-tree-root" role="treeitem" aria-expanded="true">
            <span aria-hidden="true">v</span>
            <strong>{title}</strong>
            <small>{t('object')}</small>
          </div>
          {rows.map((row, index) => {
            const nested = nestedCompactFields(row.sourceValue);
            return (
              <div className="schema-tree-field-group" key={row.id}>
                <div className="schema-tree-field" role="treeitem" aria-expanded={nested.length ? true : undefined}>
                  <span className="schema-tree-branch" aria-hidden="true" />
                  <input
                    aria-label={t('{title} field {index} name', { title, index: index + 1 })}
                    value={row.name}
                    onChange={(event) => patch(index, { name: event.target.value })}
                    data-authoring-path={fieldPath(basePath, row)}
                  />
                  <input
                    aria-label={t('{title} field {name} type', { title, name: row.name })}
                    value={row.type}
                    onChange={(event) => patch(index, { type: event.target.value })}
                    data-authoring-path={fieldPath(basePath, row)}
                  />
                  <label title={t('Required field')}>
                    <input
                      type="checkbox"
                      checked={row.required}
                      onChange={(event) => patch(index, { required: event.target.checked })}
                    />
                    <span>{t('Required')}</span>
                  </label>
                  <button
                    type="button"
                    aria-label={t('Remove {name}', { name: row.name })}
                    title={t('Remove {name}', { name: row.name })}
                    onClick={() => remove(index)}
                  >
                    <X size={14} aria-hidden="true" />
                  </button>
                </div>
                {nested.map((field) => (
                  <div
                    className="schema-tree-nested-field"
                    role="treeitem"
                    key={field.path}
                    style={{ '--schema-indent': `${field.depth * 16}px` } as CSSProperties}
                  >
                    <span className="schema-tree-branch" aria-hidden="true" />
                    <strong>{field.name}</strong>
                    <code>{field.type}</code>
                    <small>{field.required ? t('required') : t('optional')}</small>
                  </div>
                ))}
              </div>
            );
          })}
        </div>
      ) : (
        <table className="schema-field-table">
          <thead>
            <tr><th>{t('Field')}</th><th>{t('Type')}</th><th>{t('Required')}</th><th aria-label={t('Actions')} /></tr>
          </thead>
          <tbody>
            {rows.map((row, index) => (
              <tr key={row.id}>
                <td>
                  <input
                    aria-label={t('{title} field {index} name', { title, index: index + 1 })}
                    value={row.name}
                    onChange={(event) => patch(index, { name: event.target.value })}
                    data-authoring-path={fieldPath(basePath, row)}
                  />
                </td>
                <td>
                  <input
                    aria-label={t('{title} field {name} type', { title, name: row.name })}
                    value={row.type}
                    onChange={(event) => patch(index, { type: event.target.value })}
                  />
                </td>
                <td>
                  <input
                    type="checkbox"
                    aria-label={t('{name} required', { name: row.name })}
                    checked={row.required}
                    onChange={(event) => patch(index, { required: event.target.checked })}
                  />
                </td>
                <td>
                  <button
                    type="button"
                    aria-label={t('Remove {name}', { name: row.name })}
                    title={t('Remove {name}', { name: row.name })}
                    onClick={() => remove(index)}
                  >
                    <X size={14} aria-hidden="true" />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {rows.length === 0 && <p className="schema-fields-empty">{t('No fields declared.')}</p>}
      <button type="button" className="secondary compact schema-add-field" onClick={add}>
        {t('+ Add field')}
      </button>
    </section>
  );
}

function fieldPath(basePath: string, row: CompactFieldRow): string {
  const key = row.required ? row.name : `${row.name}?`;
  return `${basePath}/${key.replace(/~/g, '~0').replace(/\//g, '~1')}`;
}
