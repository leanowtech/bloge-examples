import type { DragEvent } from 'react';

import SchemaValueForm from '../../contract-scenario/SchemaValueForm';
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
  const fields = graphInputFields(inputSchema);
  const assessment = assessRunInput(inputSchema, assessmentValue ?? value);
  const missingCount = assessment.missingRequired.length;
  const statusLabel = assessment.ready
    ? `${assessment.requiredFieldCount} required, complete`
    : missingCount > 0
      ? `${assessment.requiredFieldCount} required, ${missingCount} missing`
      : `${assessment.issues.length} invalid value${assessment.issues.length === 1 ? '' : 's'}`;

  return (
    <section className="graph-run-input-panel" data-testid="graph-run-input-panel">
      <header>
        <div>
          <h3>Run Input Values</h3>
          <p>Generated from the Graph Input Contract</p>
        </div>
        <button type="button" className="secondary compact" onClick={onOpenContract}>
          Contract
        </button>
      </header>
      <div
        className={`run-input-readiness ${assessment.ready ? 'ready' : 'invalid'}`}
        data-testid="run-input-readiness"
        role="status"
      >
        <strong>{statusLabel}</strong>
        <span>{assessment.fieldCount} schema field{assessment.fieldCount === 1 ? '' : 's'}</span>
      </div>
      {fields.length > 0 ? (
        <>
          {readOnly && <p className="run-input-raw-notice">Raw runtime context currently controls this run.</p>}
          <fieldset className="run-input-form" disabled={readOnly}>
            <SchemaValueForm
              envelope={inputSchema}
              value={value}
              onChange={(nextValue) => onChange(recordValue(nextValue))}
              label="Graph input"
              compact
            />
          </fieldset>
        </>
      ) : (
        <div className="run-input-empty">
          <p>No Graph Input fields are declared.</p>
          <button type="button" className="secondary compact" onClick={onOpenContract}>
            Define Input Contract
          </button>
        </div>
      )}
      {assessment.issues.length > 0 && (
        <ul className="run-input-issues" aria-label="Run input issues">
          {assessment.issues.slice(0, 4).map((issue) => (
            <li key={`${issue.path}:${issue.code}`}>{issue.message}</li>
          ))}
        </ul>
      )}
      {fields.length > 0 && (
        <fieldset className="graph-input-bindings">
          <legend>Graph Input fields</legend>
          <ul>
            {fields.map((field) => (
              <li key={field.path}>
                <button
                  type="button"
                  className="context-variable-chip"
                  draggable
                  title={`Drag ctx.${field.path} to a node input`}
                  onDragStart={(event: DragEvent<HTMLButtonElement>) => {
                    event.dataTransfer.effectAllowed = 'copy';
                    event.dataTransfer.setData(CONTEXT_VARIABLE_DRAG_TYPE, field.path);
                    event.dataTransfer.setData('text/plain', `ctx.${field.path}`);
                  }}
                >
                  <span>ctx.{field.path}</span>
                  <small>{field.type}{field.required ? ' · required' : ''}{field.sensitive ? ' · sensitive' : ''}</small>
                </button>
                <button
                  type="button"
                  className="secondary compact"
                  data-testid={`graph-input-bind:${field.path}`}
                  disabled={!selectedNodeLabel || readOnly}
                  title={selectedNodeLabel
                    ? `Bind ctx.${field.path} to ${selectedNodeLabel}`
                    : 'Select a node to bind this Graph Input field'}
                  onClick={() => onBind(field.path)}
                >
                  Bind
                </button>
              </li>
            ))}
          </ul>
          <p>{selectedNodeLabel ? `Binding target: ${selectedNodeLabel}` : 'Select a node to enable Bind.'}</p>
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
  return (
    <details className="context-extras-panel" data-testid="context-extras-panel">
      <summary>
        <span>Context Extras</span>
        <small>{rows.length || 'Optional'}</small>
      </summary>
      <div className="context-extras-body">
        <p>Runtime-only values outside the caller contract.</p>
        {rows.map((row, index) => {
          const path = normalizedPath(row.path);
          return (
            <div className="context-extra-row" key={row.id}>
              <div className="context-extra-path">
                <input
                  aria-label={`Context extra path ${index + 1}`}
                  placeholder="trace.correlationId"
                  value={row.path}
                  onChange={(event) => onUpdate(row.id, { path: event.target.value })}
                />
                <select
                  aria-label={`Context extra type ${index + 1}`}
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
                aria-label={`Context extra value ${index + 1}`}
                placeholder={row.valueType === 'json' ? '{"key":"value"}' : 'Sample value'}
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
                  Bind
                </button>
                <button
                  type="button"
                  className="icon-button danger"
                  title={`Remove context extra ${index + 1}`}
                  aria-label={`Remove context extra ${index + 1}`}
                  onClick={() => onRemove(row.id)}
                >
                  ×
                </button>
              </div>
            </div>
          );
        })}
        <button type="button" className="secondary compact" onClick={onAdd}>
          Add Context Extra
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
  return (
    <section className="raw-run-context-panel" data-testid="raw-run-context-panel">
      <label className="raw-context-mode">
        <input
          type="checkbox"
          checked={rawMode}
          onChange={(event) => onRawModeChange(event.target.checked)}
        />
        <span>
          <strong>Use raw runtime context</strong>
          <small>Replaces structured Run Input and Context Extras for this run.</small>
        </span>
      </label>
      {rawMode ? (
        <label className="fixture-field">
          <span>Raw context JSON</span>
          <textarea
            aria-label="Simulation runtime context JSON"
            data-testid="simulation-context-json"
            spellCheck={false}
            value={rawText}
            onChange={(event) => onRawTextChange(event.target.value)}
          />
        </label>
      ) : (
        <div className="effective-context-preview">
          <strong>Effective structured context</strong>
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
