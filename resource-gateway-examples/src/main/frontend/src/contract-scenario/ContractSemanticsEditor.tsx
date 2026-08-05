import { X } from 'lucide-react';

import type {
  ContractDraft,
  ContractEffect,
  ContractInvariant,
  ErrorVariant,
} from './domain';
import { useI18n } from '../i18n/I18nProvider';

interface ContractSemanticsEditorProps {
  contract: ContractDraft;
  onChange: (contract: ContractDraft) => void;
}

/** Structure-first editor for non-schema promises carried by one Graph or Operator Contract. */
export default function ContractSemanticsEditor({
  contract,
  onChange,
}: ContractSemanticsEditorProps) {
  const { t } = useI18n();
  const updateExecution = (patch: Partial<ContractDraft['executionSemantics']>) => {
    onChange({
      ...contract,
      executionSemantics: { ...contract.executionSemantics, ...patch },
    });
  };
  const updateError = (index: number, patch: Partial<ErrorVariant>) => {
    onChange({
      ...contract,
      errorContract: contract.errorContract.map((error, candidate) => (
        candidate === index ? { ...error, ...patch } : error
      )),
    });
  };
  const updateInvariant = (index: number, patch: Partial<ContractInvariant>) => {
    onChange({
      ...contract,
      invariants: contract.invariants.map((invariant, candidate) => (
        candidate === index ? { ...invariant, ...patch } : invariant
      )),
    });
  };

  return (
    <section className="contract-semantics-editor" aria-label={t('Contract semantics')}>
      <div className="contract-semantics-grid">
        <Field label={t('Effect')}>
          <select
            aria-label={t('Contract effect')}
            value={contract.executionSemantics.effect}
            onChange={(event) => {
              const effect = event.target.value as ContractEffect;
              updateExecution({
                effect,
                sideEffectProtocol: effect === 'WRITE'
                  ? contract.executionSemantics.sideEffectProtocol ?? {
                      protocol: '',
                      reconcilerRef: '',
                      reversible: false,
                      metadata: {},
                    }
                  : undefined,
              });
            }}
          >
            <option value="UNKNOWN">{t('Not declared')}</option>
            <option value="PURE">{t('Pure')}</option>
            <option value="READ">{t('Read')}</option>
            <option value="WRITE">{t('Write')}</option>
          </select>
        </Field>
        <Field label={t('Idempotency')}>
          <input
            aria-label={t('Contract idempotency')}
            value={contract.executionSemantics.idempotency}
            placeholder={t('UNKNOWN or stable key policy')}
            onChange={(event) => updateExecution({ idempotency: event.target.value })}
          />
        </Field>
        <TriState
          label={t('Streaming')}
          value={contract.executionSemantics.streaming}
          onChange={(streaming) => updateExecution({ streaming })}
        />
        <TriState
          label={t('Durable')}
          value={contract.executionSemantics.durable}
          onChange={(durable) => updateExecution({ durable })}
        />
        <Field label={t('Compatibility')}>
          <select
            aria-label={t('Contract compatibility mode')}
            value={contract.compatibilityPolicy.mode}
            onChange={(event) => onChange({
              ...contract,
              compatibilityPolicy: {
                ...contract.compatibilityPolicy,
                mode: event.target.value as ContractDraft['compatibilityPolicy']['mode'],
              },
            })}
          >
            <option value="STRICT">{t('Strict')}</option>
            <option value="BACKWARD">{t('Backward')}</option>
            <option value="FORWARD">{t('Forward')}</option>
            <option value="NONE">{t('None')}</option>
          </select>
        </Field>
        <label className="scenario-check-field">
          <input
            type="checkbox"
            checked={contract.compatibilityPolicy.unknownBlocksAutomaticMigration}
            onChange={(event) => onChange({
              ...contract,
              compatibilityPolicy: {
                ...contract.compatibilityPolicy,
                unknownBlocksAutomaticMigration: event.target.checked,
              },
            })}
          />
          <span>{t('Unknown changes block migration')}</span>
        </label>
      </div>

      {contract.executionSemantics.effect === 'WRITE' && (
        <fieldset className="contract-side-effect-fields">
          <legend>{t('Write reconciliation')}</legend>
          <Field label={t('Protocol')}>
            <input
              aria-label={t('Side effect protocol')}
              value={contract.executionSemantics.sideEffectProtocol?.protocol ?? ''}
              placeholder={t('bloge.sideEffectProtocol.v1')}
              onChange={(event) => updateExecution({
                sideEffectProtocol: {
                  ...contract.executionSemantics.sideEffectProtocol!,
                  protocol: event.target.value,
                },
              })}
            />
          </Field>
          <Field label={t('Reconciler')}>
            <input
              aria-label={t('Side effect reconciler')}
              value={contract.executionSemantics.sideEffectProtocol?.reconcilerRef ?? ''}
              placeholder={t('capability.reconcile')}
              onChange={(event) => updateExecution({
                sideEffectProtocol: {
                  ...contract.executionSemantics.sideEffectProtocol!,
                  reconcilerRef: event.target.value,
                },
              })}
            />
          </Field>
          <label className="scenario-check-field">
            <input
              type="checkbox"
              checked={contract.executionSemantics.sideEffectProtocol?.reversible ?? false}
              onChange={(event) => updateExecution({
                sideEffectProtocol: {
                  ...contract.executionSemantics.sideEffectProtocol!,
                  reversible: event.target.checked,
                },
              })}
            />
            <span>{t('Compensation declared')}</span>
          </label>
        </fieldset>
      )}

      <SemanticTable
        title={t('Stable errors')}
        empty={t('No stable error variants declared.')}
        onAdd={() => onChange({
          ...contract,
          errorContract: [...contract.errorContract, {
            code: `ERROR_${contract.errorContract.length + 1}`,
            type: 'BUSINESS',
            description: '',
            retryable: false,
          }],
        })}
      >
        {contract.errorContract.map((error, index) => (
          <div className="contract-semantic-row error" key={`${error.code}-${index}`}>
            <Field label={t('Code')}>
              <input
                aria-label={t('Error code {index}', { index: index + 1 })}
                value={error.code}
                onChange={(event) => updateError(index, { code: event.target.value })}
              />
            </Field>
            <Field label={t('Type')}>
              <input
                value={error.type}
                onChange={(event) => updateError(index, { type: event.target.value })}
              />
            </Field>
            <Field label={t('Meaning')}>
              <input
                value={error.description}
                onChange={(event) => updateError(index, { description: event.target.value })}
              />
            </Field>
            <label className="scenario-check-field">
              <input
                aria-label={t('Error retryable {index}', { index: index + 1 })}
                type="checkbox"
                checked={error.retryable}
                onChange={(event) => updateError(index, { retryable: event.target.checked })}
              />
              <span>{t('Retryable')}</span>
            </label>
            <RemoveButton
              label={t('Remove error {index}', { index: index + 1 })}
              onClick={() => onChange({
                ...contract,
                errorContract: contract.errorContract.filter((_, candidate) => candidate !== index),
              })}
            />
          </div>
        ))}
      </SemanticTable>

      <SemanticTable
        title={t('Contract invariants')}
        empty={t('No preconditions or postconditions declared.')}
        onAdd={() => onChange({
          ...contract,
          invariants: [...contract.invariants, {
            invariantId: uniqueInvariantId(contract.invariants),
            phase: 'POSTCONDITION',
            expression: '',
            description: '',
            severity: 'ERROR',
          }],
        })}
      >
        {contract.invariants.map((invariant, index) => (
          <div className="contract-semantic-row invariant" key={`${invariant.invariantId}-${index}`}>
            <Field label={t('Id')}>
              <input
                aria-label={t('Invariant id {index}', { index: index + 1 })}
                value={invariant.invariantId}
                onChange={(event) => updateInvariant(index, { invariantId: event.target.value })}
              />
            </Field>
            <Field label={t('Phase')}>
              <select
                value={invariant.phase}
                onChange={(event) => updateInvariant(index, {
                  phase: event.target.value as ContractInvariant['phase'],
                })}
              >
                <option value="PRECONDITION">{t('Precondition')}</option>
                <option value="POSTCONDITION">{t('Postcondition')}</option>
              </select>
            </Field>
            <Field label={t('Expression')}>
              <input
                aria-label={t('Invariant expression {index}', { index: index + 1 })}
                value={invariant.expression}
                placeholder={t('exists(ctx.requestId)')}
                onChange={(event) => updateInvariant(index, { expression: event.target.value })}
              />
            </Field>
            <Field label={t('Severity')}>
              <select
                value={invariant.severity}
                onChange={(event) => updateInvariant(index, {
                  severity: event.target.value as ContractInvariant['severity'],
                })}
              >
                <option value="ERROR">{t('Error')}</option>
                <option value="WARNING">{t('Warning')}</option>
              </select>
            </Field>
            <Field label={t('Meaning')}>
              <input
                value={invariant.description}
                onChange={(event) => updateInvariant(index, { description: event.target.value })}
              />
            </Field>
            <RemoveButton
              label={t('Remove invariant {index}', { index: index + 1 })}
              onClick={() => onChange({
                ...contract,
                invariants: contract.invariants.filter((_, candidate) => candidate !== index),
              })}
            />
          </div>
        ))}
      </SemanticTable>
    </section>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return <label className="scenario-labeled-field"><span>{label}</span>{children}</label>;
}

function TriState({
  label,
  value,
  onChange,
}: {
  label: string;
  value: boolean | null;
  onChange: (value: boolean | null) => void;
}) {
  const { t } = useI18n();
  return (
    <Field label={label}>
      <select
        aria-label={t('Contract {label}', { label })}
        value={value === null ? 'UNKNOWN' : String(value).toUpperCase()}
        onChange={(event) => onChange(
          event.target.value === 'UNKNOWN' ? null : event.target.value === 'TRUE',
        )}
      >
        <option value="UNKNOWN">{t('Not declared')}</option>
        <option value="TRUE">{t('Yes')}</option>
        <option value="FALSE">{t('No')}</option>
      </select>
    </Field>
  );
}

function SemanticTable({
  title,
  empty,
  onAdd,
  children,
}: {
  title: string;
  empty: string;
  onAdd: () => void;
  children: React.ReactNode;
}) {
  const { t } = useI18n();
  const hasChildren = Array.isArray(children) ? children.length > 0 : Boolean(children);
  return (
    <section className="contract-semantic-table">
      <header>
        <h3>{title}</h3>
        <button type="button" className="secondary compact" onClick={onAdd}>{t('Add')}</button>
      </header>
      {!hasChildren && <p>{empty}</p>}
      {children}
    </section>
  );
}

function RemoveButton({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button
      type="button"
      className="icon-button danger"
      aria-label={label}
      title={label}
      onClick={onClick}
    >
      <X size={14} aria-hidden="true" />
    </button>
  );
}

function uniqueInvariantId(invariants: ContractInvariant[]): string {
  const used = new Set(invariants.map((invariant) => invariant.invariantId));
  let sequence = invariants.length + 1;
  while (used.has(`invariant-${sequence}`)) sequence += 1;
  return `invariant-${sequence}`;
}
