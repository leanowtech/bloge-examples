import type { DragEvent } from 'react';

import SchemaValueForm from '../../contract-scenario/SchemaValueForm';
import { useI18n } from '../../i18n/I18nProvider';
import type { SchemaEnvelope } from '../../types';
import {
  assessRunInput,
  graphInputFields,
  type JsonObjectCompilation,
} from './authorRunInput';

const CONTEXT_VARIABLE_DRAG_TYPE = 'application/bloge-context-path';

interface GraphRunInputPanelProps {
  inputSchema: SchemaEnvelope;
  value: Record<string, unknown>;
  assessmentValue?: Record<string, unknown>;
  readOnly?: boolean;
  selectedNodeLabel: string;
  onChange: (value: Record<string, unknown>) => void;
  onBind: (path: string) => void;
  onOpenContract: () => void;
}

/** Schema-driven graph input values and direct graph-input-to-node binding affordances. */
export default function GraphRunInputPanel({
  inputSchema,
  value,
  assessmentValue,
  readOnly = false,
  selectedNodeLabel,
  onChange,
  onBind,
  onOpenContract,
}: GraphRunInputPanelProps) {
  const { t } = useI18n();
  const fields = graphInputFields(inputSchema);
  const assessment = assessRunInput(inputSchema, assessmentValue ?? value);
  const missingCount = assessment.missingRequired.length;
  const statusLabel = assessment.ready
    ? t('{required} required, complete', { required: assessment.requiredFieldCount })
    : missingCount > 0
      ? t('{required} required, {missing} missing', {
          required: assessment.requiredFieldCount,
          missing: missingCount,
        })
      : t('{count} invalid values', { count: assessment.issues.length });

  return (
    <section className="graph-run-input-panel" data-testid="graph-run-input-panel">
      <header>
        <div>
          <h3>{t('Run Input Values')}</h3>
          <p>{t('Generated from the Graph Input Contract')}</p>
        </div>
        <button type="button" className="secondary compact" onClick={onOpenContract}>
          {t('Contract')}
        </button>
      </header>
      <div
        className={`run-input-readiness ${assessment.ready ? 'ready' : 'invalid'}`}
        data-testid="run-input-readiness"
        role="status"
      >
        <strong>{statusLabel}</strong>
        <span>{t('{count} schema fields', { count: assessment.fieldCount })}</span>
      </div>
      {fields.length > 0 ? (
        <>
          {readOnly && <p className="run-input-raw-notice">{t('Raw runtime context currently controls this run.')}</p>}
          <fieldset className="run-input-form" disabled={readOnly}>
            <SchemaValueForm
              envelope={inputSchema}
              value={value}
              onChange={(nextValue) => onChange(recordValue(nextValue))}
              label={t('Graph input')}
              compact
            />
          </fieldset>
        </>
      ) : (
        <div className="run-input-empty">
          <p>{t('No Graph Input fields are declared.')}</p>
          <button type="button" className="secondary compact" onClick={onOpenContract}>
            {t('Define Input Contract')}
          </button>
        </div>
      )}
      {assessment.issues.length > 0 && (
        <ul className="run-input-issues" aria-label={t('Run input issues')}>
          {assessment.issues.slice(0, 4).map((issue) => (
            <li key={`${issue.path}:${issue.code}`}>{issue.message}</li>
          ))}
        </ul>
      )}
      {fields.length > 0 && (
        <fieldset className="graph-input-bindings">
          <legend>{t('Graph Input fields')}</legend>
          <ul>
            {fields.map((field) => (
              <li key={field.path}>
                <button
                  type="button"
                  className="context-variable-chip"
                  draggable
                  title={t('Drag ctx.{path} to a node input', { path: field.path })}
                  onDragStart={(event: DragEvent<HTMLButtonElement>) => {
                    event.dataTransfer.effectAllowed = 'copy';
                    event.dataTransfer.setData(CONTEXT_VARIABLE_DRAG_TYPE, field.path);
                    event.dataTransfer.setData('text/plain', `ctx.${field.path}`);
                  }}
                >
                  <span>ctx.{field.path}</span>
                  <small>{field.type}{field.required ? t(' · required') : ''}{field.sensitive ? t(' · sensitive') : ''}</small>
                </button>
                <button
                  type="button"
                  className="secondary compact"
                  data-testid={`graph-input-bind:${field.path}`}
                  disabled={!selectedNodeLabel || readOnly}
                  title={selectedNodeLabel
                    ? t('Bind ctx.{path} to {node}', { path: field.path, node: selectedNodeLabel })
                    : t('Select a node to bind this Graph Input field')}
                  onClick={() => onBind(field.path)}
                >
                  {t('Bind')}
                </button>
              </li>
            ))}
          </ul>
          <p>{selectedNodeLabel
            ? t('Binding target: {node}', { node: selectedNodeLabel })
            : t('Select a node to enable Bind.')}</p>
        </fieldset>
      )}
    </section>
  );
}

export interface ContextExtraRow {
  id: string;
  path: string;
  valueType: 'string' | 'number' | 'boolean' | 'json';
  sample: string;
}

interface ContextExtrasPanelProps {
  rows: ContextExtraRow[];
  compilation: JsonObjectCompilation;
  selectedNodeLabel: string;
  onAdd: () => void;
  onUpdate: (id: string, patch: Partial<ContextExtraRow>) => void;
  onRemove: (id: string) => void;
  onBind: (path: string) => void;
}

/** Optional context values not declared by the public Graph Input Contract. */
export function ContextExtrasPanel({
  rows,
  compilation,
  selectedNodeLabel,
  onAdd,
  onUpdate,
  onRemove,
  onBind,
}: ContextExtrasPanelProps) {
  const { t } = useI18n();
  return (
    <details className="context-extras-panel" data-testid="context-extras-panel">
      <summary>
        <span>{t('Context Extras')}</span>
        <small>{rows.length || t('Optional')}</small>
      </summary>
      <div className="context-extras-body">
        <p>{t('Runtime-only values outside the caller contract.')}</p>
        {rows.map((row, index) => {
          const path = normalizedPath(row.path);
          return (
            <div className="context-extra-row" key={row.id}>
              <div className="context-extra-path">
                <input
                  aria-label={t('Context extra path {index}', { index: index + 1 })}
                  placeholder="trace.correlationId"
                  value={row.path}
                  onChange={(event) => onUpdate(row.id, { path: event.target.value })}
                />
                <select
                  aria-label={t('Context extra type {index}', { index: index + 1 })}
                  value={row.valueType}
                  onChange={(event) => onUpdate(row.id, {
                    valueType: event.target.value as ContextExtraRow['valueType'],
                  })}
                >
                  <option value="string">string</option>
                  <option value="number">number</option>
                  <option value="boolean">boolean</option>
                  <option value="json">json</option>
                </select>
              </div>
              <input
                aria-label={t('Context extra value {index}', { index: index + 1 })}
                placeholder={row.valueType === 'json' ? '{"key":"value"}' : t('Sample value')}
                value={row.sample}
                onChange={(event) => onUpdate(row.id, { sample: event.target.value })}
              />
              <div className="context-extra-actions">
                <button
                  type="button"
                  className="secondary compact"
                  disabled={!selectedNodeLabel || !path}
                  onClick={() => onBind(path)}
                >
                  {t('Bind')}
                </button>
                <button
                  type="button"
                  className="icon-button danger"
                  title={t('Remove context extra {index}', { index: index + 1 })}
                  aria-label={t('Remove context extra {index}', { index: index + 1 })}
                  onClick={() => onRemove(row.id)}
                >
                  ×
                </button>
              </div>
            </div>
          );
        })}
        <button type="button" className="secondary compact" onClick={onAdd}>
          {t('Add Context Extra')}
        </button>
        {compilation.error && <p className="fixture-error">{compilation.error}</p>}
      </div>
    </details>
  );
}

interface RawRunContextPanelProps {
  rawMode: boolean;
  rawText: string;
  effectiveValue: Record<string, unknown>;
  error?: string;
  onRawModeChange: (enabled: boolean) => void;
  onRawTextChange: (value: string) => void;
}

/** Explicit expert takeover for the complete runtime context JSON object. */
export function RawRunContextPanel({
  rawMode,
  rawText,
  effectiveValue,
  error,
  onRawModeChange,
  onRawTextChange,
}: RawRunContextPanelProps) {
  const { t } = useI18n();
  return (
    <section className="raw-run-context-panel" data-testid="raw-run-context-panel">
      <label className="raw-context-mode">
        <input
          type="checkbox"
          checked={rawMode}
          onChange={(event) => onRawModeChange(event.target.checked)}
        />
        <span>
          <strong>{t('Use raw runtime context')}</strong>
          <small>{t('Replaces structured Run Input and Context Extras for this run.')}</small>
        </span>
      </label>
      {rawMode ? (
        <label className="fixture-field">
          <span>{t('Raw context JSON')}</span>
          <textarea
            aria-label={t('Simulation runtime context JSON')}
            data-testid="simulation-context-json"
            spellCheck={false}
            value={rawText}
            onChange={(event) => onRawTextChange(event.target.value)}
          />
        </label>
      ) : (
        <div className="effective-context-preview">
          <strong>{t('Effective structured context')}</strong>
          <pre>{JSON.stringify(effectiveValue, null, 2)}</pre>
        </div>
      )}
      {error && <p className="fixture-error" data-testid="simulation-context-error">{error}</p>}
    </section>
  );
}

function normalizedPath(path: string): string {
  return path.trim().replace(/^ctx\./, '');
}

function recordValue(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {};
}
