import { useEffect, useState } from 'react';

import type { VisualOperatorAuthoring } from '../types';
import SchemaTreeEditor from './SchemaTreeEditor';

const ARCHETYPES = [
  ['pure', 'Pure transformation'],
  ['decision', 'Decision or policy'],
  ['resource-read', 'External read'],
  ['external-write', 'External write'],
  ['remote-worker', 'Remote worker'],
  ['ai-tool', 'AI tool'],
  ['event-source', 'Event source'],
  ['message-handler', 'Message handler'],
  ['webhook', 'Webhook'],
] as const;

interface OperatorBuilderProps {
  operatorKey: string;
  operator: VisualOperatorAuthoring;
  onRename: (nextKey: string) => void;
  onChange: (operator: VisualOperatorAuthoring) => void;
  onRemove: () => void;
}

export default function OperatorBuilder({
  operatorKey,
  operator,
  onRename,
  onChange,
  onRemove,
}: OperatorBuilderProps) {
  const [keyDraft, setKeyDraft] = useState(operatorKey);
  useEffect(() => setKeyDraft(operatorKey), [operatorKey]);
  const archetype = operator.archetype ?? 'pure';
  const external = !['pure', 'decision'].includes(archetype);
  const patch = (value: Partial<VisualOperatorAuthoring>) => onChange({ ...operator, ...value });

  return (
    <div className="library-task-builder" data-testid="operator-builder">
      <header className="library-builder-heading">
        <div>
          <span>Operator</span>
          <h2>{operator.name || operatorKey}</h2>
        </div>
        <button type="button" className="danger compact" onClick={onRemove}>Delete</button>
      </header>

      <section className="library-builder-section">
        <header><h3>Identity & Archetype</h3><span>Required</span></header>
        <div className="library-form-grid">
          <label>
            <span>Operator ref</span>
            <input
              value={keyDraft}
              onChange={(event) => setKeyDraft(event.target.value)}
              onBlur={() => onRename(keyDraft)}
              data-authoring-path={`/operators/${pointer(operatorKey)}`}
            />
          </label>
          <label>
            <span>Display name</span>
            <input
              value={operator.name ?? ''}
              onChange={(event) => patch({ name: event.target.value })}
              data-authoring-path={`/operators/${pointer(operatorKey)}/name`}
            />
          </label>
          <label className="library-form-wide">
            <span>Description</span>
            <textarea
              value={operator.description ?? ''}
              onChange={(event) => patch({ description: event.target.value })}
              data-authoring-path={`/operators/${pointer(operatorKey)}/description`}
            />
          </label>
        </div>
        <fieldset className="archetype-picker">
          <legend>Execution archetype</legend>
          {ARCHETYPES.map(([value, label]) => (
            <label key={value} className={archetype === value ? 'selected' : ''}>
              <input
                type="radio"
                name={`archetype:${operatorKey}`}
                value={value}
                checked={archetype === value}
                onChange={() => patch({ archetype: value })}
              />
              <span>{label}</span>
              <small>{value}</small>
            </label>
          ))}
        </fieldset>
      </section>

      <section className="library-builder-section">
        <header><h3>Inputs / Outputs</h3><span>Compact schema</span></header>
        <SchemaTreeEditor
          title="Inputs"
          fields={operator.input ?? {}}
          basePath={`/operators/${pointer(operatorKey)}/input`}
          onChange={(input) => patch({ input })}
        />
        <SchemaTreeEditor
          title="Outputs"
          fields={operator.output ?? {}}
          basePath={`/operators/${pointer(operatorKey)}/output`}
          onChange={(output) => patch({ output })}
        />
      </section>

      <section className="library-builder-section">
        <header><h3>Examples & Tests</h3><span>{operator.tests?.length ?? 0} references</span></header>
        <ReferenceEditor
          values={(operator.tests ?? []).map((test) => test.ref)}
          onChange={(values) => patch({ tests: values.map((ref) => ({ ref })) })}
        />
      </section>

      {external && (
        <details className="library-builder-details">
          <summary>Runtime Binding & Governance</summary>
          <div className="library-form-grid">
            <label>
              <span>Effect</span>
              <select
                value={operator.effect ?? ''}
                onChange={(event) => patch({ effect: event.target.value })}
                data-authoring-path={`/operators/${pointer(operatorKey)}/effect`}
              >
                <option value="">Archetype default</option>
                <option value="READ_EXTERNAL">Read external</option>
                <option value="WRITE_EXTERNAL">Write external</option>
                <option value="EXTERNAL">External</option>
              </select>
            </label>
            <label>
              <span>Secret posture</span>
              <select
                value={operator.requiresSecrets === undefined
                  ? '' : operator.requiresSecrets ? 'yes' : 'no'}
                onChange={(event) => patch({
                  requiresSecrets: event.target.value === ''
                    ? undefined : event.target.value === 'yes',
                })}
                data-authoring-path={`/operators/${pointer(operatorKey)}/requiresSecrets`}
              >
                <option value="">Confirm later</option>
                <option value="no">No secrets</option>
                <option value="yes">Requires secret refs</option>
              </select>
            </label>
            <label>
              <span>Idempotency</span>
              <select
                value={operator.idempotency ?? ''}
                onChange={(event) => patch({ idempotency: event.target.value })}
              >
                <option value="">Archetype default</option>
                <option value="NOT_APPLICABLE">Not applicable</option>
                <option value="SUPPORTED">Supported</option>
                <option value="REQUIRED">Required</option>
              </select>
            </label>
          </div>
        </details>
      )}
    </div>
  );
}

interface ReferenceEditorProps {
  values: string[];
  onChange: (values: string[]) => void;
}

export function ReferenceEditor({ values, onChange }: ReferenceEditorProps) {
  return (
    <div className="reference-editor">
      {values.map((value, index) => (
        <div key={`${index}:${value}`}>
          <input
            aria-label={`Test reference ${index + 1}`}
            value={value}
            onChange={(event) => onChange(
              values.map((item, itemIndex) => itemIndex === index ? event.target.value : item),
            )}
          />
          <button
            type="button"
            aria-label={`Remove test reference ${index + 1}`}
            title="Remove test reference"
            onClick={() => onChange(values.filter((_, itemIndex) => itemIndex !== index))}
          >
            x
          </button>
        </div>
      ))}
      <button
        type="button"
        className="secondary compact"
        onClick={() => onChange([...values, `fixtures/case-${values.length + 1}`])}
      >
        + Add test reference
      </button>
    </div>
  );
}

function pointer(value: string): string {
  return value.replace(/~/g, '~0').replace(/\//g, '~1');
}
