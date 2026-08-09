import { useEffect, useState } from 'react';
import { X } from 'lucide-react';

import { useI18n } from '../i18n/I18nProvider';
import type { VisualOperatorAuthoring } from '../types';
import { OPERATOR_ARCHETYPES, presentOperatorArchetype } from './archetypePresentation';
import SchemaTreeEditor from './SchemaTreeEditor';

interface OperatorBuilderProps {
  operatorKey: string;
  operator: VisualOperatorAuthoring;
  onRename: (nextKey: string) => void;
  onChange: (operator: VisualOperatorAuthoring) => void;
  onRemove: () => void;
  onInferSamples: (direction: 'INPUT' | 'OUTPUT') => void;
  onOpenTests: () => void;
}

export default function OperatorBuilder({
  operatorKey,
  operator,
  onRename,
  onChange,
  onRemove,
  onInferSamples,
  onOpenTests,
}: OperatorBuilderProps) {
  const { m, t } = useI18n();
  const [keyDraft, setKeyDraft] = useState(operatorKey);
  useEffect(() => setKeyDraft(operatorKey), [operatorKey]);
  const archetype = operator.archetype ?? 'pure';
  const external = presentOperatorArchetype(archetype).external;
  const patch = (value: Partial<VisualOperatorAuthoring>) => onChange({ ...operator, ...value });

  return (
    <div className="library-task-builder" data-testid="operator-builder">
      <header className="library-builder-heading">
        <div>
          <span>{t('Operator')}</span>
          <h2>{operator.name || operatorKey}</h2>
        </div>
        <button type="button" className="danger compact" onClick={onRemove}>{t('Delete')}</button>
      </header>

      <section className="library-builder-section">
        <header><h3>{t('Identity & Archetype')}</h3><span>{t('Required')}</span></header>
        <div className="library-form-grid">
          <label>
            <span>{t('Operator ref')}</span>
            <input
              value={keyDraft}
              onChange={(event) => setKeyDraft(event.target.value)}
              onBlur={() => onRename(keyDraft)}
              data-authoring-path={`/operators/${pointer(operatorKey)}`}
            />
          </label>
          <label>
            <span>{t('Display name')}</span>
            <input
              value={operator.name ?? ''}
              onChange={(event) => patch({ name: event.target.value })}
              data-authoring-path={`/operators/${pointer(operatorKey)}/name`}
            />
          </label>
          <label className="library-form-wide">
            <span>{t('Description')}</span>
            <textarea
              value={operator.description ?? ''}
              onChange={(event) => patch({ description: event.target.value })}
              data-authoring-path={`/operators/${pointer(operatorKey)}/description`}
            />
          </label>
        </div>
        <fieldset className="archetype-picker">
          <legend>{t('Execution archetype')}</legend>
          {OPERATOR_ARCHETYPES.map((value) => {
            const presentation = presentOperatorArchetype(value);
            return (
            <label key={value} className={archetype === value ? 'selected' : ''}>
              <input
                type="radio"
                name={`archetype:${operatorKey}`}
                value={value}
                checked={archetype === value}
                onChange={() => patch({ archetype: value })}
              />
              <span>{m(presentation.label.messageId)}</span>
              <small>{m(presentation.summary.messageId)}</small>
            </label>
            );
          })}
        </fieldset>
      </section>

      <section className="library-builder-section">
        <header><h3>{t('Inputs / Outputs')}</h3><span>{t('Compact schema')}</span></header>
        <SchemaTreeEditor
          title={t('Inputs')}
          fields={operator.input ?? {}}
          basePath={`/operators/${pointer(operatorKey)}/input`}
          onChange={(input) => patch({ input })}
          onInferSamples={() => onInferSamples('INPUT')}
        />
        <SchemaTreeEditor
          title={t('Outputs')}
          fields={operator.output ?? {}}
          basePath={`/operators/${pointer(operatorKey)}/output`}
          onChange={(output) => patch({ output })}
          onInferSamples={() => onInferSamples('OUTPUT')}
        />
      </section>

      <section className="library-builder-section">
        <header>
          <h3>{t('Examples & Tests')}</h3>
          <button
            type="button"
            className="primary compact"
            onClick={onOpenTests}
            data-testid="open-operator-test-table"
          >
            {t('Open test table')}
          </button>
        </header>
        <ReferenceEditor
          values={(operator.tests ?? []).map((test) => test.ref)}
          onChange={(values) => patch({ tests: values.map((ref) => ({ ref })) })}
        />
      </section>

      {external && (
        <details className="library-builder-details">
          <summary>{t('Runtime Binding & Governance')}</summary>
          <div className="library-form-grid">
            <label>
              <span>{t('Effect')}</span>
              <select
                value={operator.effect ?? ''}
                onChange={(event) => patch({ effect: event.target.value })}
                data-authoring-path={`/operators/${pointer(operatorKey)}/effect`}
              >
                <option value="">{t('Archetype default')}</option>
                <option value="READ_EXTERNAL">{t('Read external')}</option>
                <option value="WRITE_EXTERNAL">{t('Write external')}</option>
                <option value="EXTERNAL">{t('External')}</option>
              </select>
            </label>
            <label>
              <span>{t('Secret posture')}</span>
              <select
                value={operator.requiresSecrets === undefined
                  ? '' : operator.requiresSecrets ? 'yes' : 'no'}
                onChange={(event) => patch({
                  requiresSecrets: event.target.value === ''
                    ? undefined : event.target.value === 'yes',
                })}
                data-authoring-path={`/operators/${pointer(operatorKey)}/requiresSecrets`}
              >
                <option value="">{t('Confirm later')}</option>
                <option value="no">{t('No secrets')}</option>
                <option value="yes">{t('Requires secret refs')}</option>
              </select>
            </label>
            <label>
              <span>{t('Idempotency')}</span>
              <select
                value={operator.idempotency ?? ''}
                onChange={(event) => patch({ idempotency: event.target.value })}
              >
                <option value="">{t('Archetype default')}</option>
                <option value="NOT_APPLICABLE">{t('Not applicable')}</option>
                <option value="SUPPORTED">{t('Supported')}</option>
                <option value="REQUIRED">{t('Required')}</option>
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
  const { t } = useI18n();
  return (
    <div className="reference-editor">
      {values.map((value, index) => (
        <div key={`${index}:${value}`}>
          <input
            aria-label={t('Test reference {index}', { index: index + 1 })}
            value={value}
            onChange={(event) => onChange(
              values.map((item, itemIndex) => itemIndex === index ? event.target.value : item),
            )}
          />
          <button
            type="button"
            aria-label={t('Remove test reference {index}', { index: index + 1 })}
            title={t('Remove test reference')}
            onClick={() => onChange(values.filter((_, itemIndex) => itemIndex !== index))}
          >
            <X size={14} aria-hidden="true" />
          </button>
        </div>
      ))}
      <button
        type="button"
        className="secondary compact"
        onClick={() => onChange([...values, `fixtures/case-${values.length + 1}`])}
      >
        {t('+ Add test reference')}
      </button>
    </div>
  );
}

function pointer(value: string): string {
  return value.replace(/~/g, '~0').replace(/\//g, '~1');
}
