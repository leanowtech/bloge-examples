import type {
  ContractDraft,
  ContractEffect,
  ContractInvariant,
  ErrorVariant,
} from './domain';

interface ContractSemanticsEditorProps {
  contract: ContractDraft;
  onChange: (contract: ContractDraft) => void;
}

/** Structure-first editor for non-schema promises carried by one Graph or Operator Contract. */
export default function ContractSemanticsEditor({
  contract,
  onChange,
}: ContractSemanticsEditorProps) {
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
    <section className="contract-semantics-editor" aria-label="Contract semantics">
      <div className="contract-semantics-grid">
        <Field label="Effect">
          <select
            aria-label="Contract effect"
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
            <option value="UNKNOWN">Not declared</option>
            <option value="PURE">Pure</option>
            <option value="READ">Read</option>
            <option value="WRITE">Write</option>
          </select>
        </Field>
        <Field label="Idempotency">
          <input
            aria-label="Contract idempotency"
            value={contract.executionSemantics.idempotency}
            placeholder="UNKNOWN or stable key policy"
            onChange={(event) => updateExecution({ idempotency: event.target.value })}
          />
        </Field>
        <TriState
          label="Streaming"
          value={contract.executionSemantics.streaming}
          onChange={(streaming) => updateExecution({ streaming })}
        />
        <TriState
          label="Durable"
          value={contract.executionSemantics.durable}
          onChange={(durable) => updateExecution({ durable })}
        />
        <Field label="Compatibility">
          <select
            aria-label="Contract compatibility mode"
            value={contract.compatibilityPolicy.mode}
            onChange={(event) => onChange({
              ...contract,
              compatibilityPolicy: {
                ...contract.compatibilityPolicy,
                mode: event.target.value as ContractDraft['compatibilityPolicy']['mode'],
              },
            })}
          >
            <option value="STRICT">Strict</option>
            <option value="BACKWARD">Backward</option>
            <option value="FORWARD">Forward</option>
            <option value="NONE">None</option>
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
          <span>Unknown changes block migration</span>
        </label>
      </div>

      {contract.executionSemantics.effect === 'WRITE' && (
        <fieldset className="contract-side-effect-fields">
          <legend>Write reconciliation</legend>
          <Field label="Protocol">
            <input
              aria-label="Side effect protocol"
              value={contract.executionSemantics.sideEffectProtocol?.protocol ?? ''}
              placeholder="bloge.sideEffectProtocol.v1"
              onChange={(event) => updateExecution({
                sideEffectProtocol: {
                  ...contract.executionSemantics.sideEffectProtocol!,
                  protocol: event.target.value,
                },
              })}
            />
          </Field>
          <Field label="Reconciler">
            <input
              aria-label="Side effect reconciler"
              value={contract.executionSemantics.sideEffectProtocol?.reconcilerRef ?? ''}
              placeholder="capability.reconcile"
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
            <span>Compensation declared</span>
          </label>
        </fieldset>
      )}

      <SemanticTable
        title="Stable errors"
        empty="No stable error variants declared."
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
            <Field label="Code">
              <input
                aria-label={`Error code ${index + 1}`}
                value={error.code}
                onChange={(event) => updateError(index, { code: event.target.value })}
              />
            </Field>
            <Field label="Type">
              <input
                value={error.type}
                onChange={(event) => updateError(index, { type: event.target.value })}
              />
            </Field>
            <Field label="Meaning">
              <input
                value={error.description}
                onChange={(event) => updateError(index, { description: event.target.value })}
              />
            </Field>
            <label className="scenario-check-field">
              <input
                aria-label={`Error retryable ${index + 1}`}
                type="checkbox"
                checked={error.retryable}
                onChange={(event) => updateError(index, { retryable: event.target.checked })}
              />
              <span>Retryable</span>
            </label>
            <RemoveButton
              label={`Remove error ${index + 1}`}
              onClick={() => onChange({
                ...contract,
                errorContract: contract.errorContract.filter((_, candidate) => candidate !== index),
              })}
            />
          </div>
        ))}
      </SemanticTable>

      <SemanticTable
        title="Contract invariants"
        empty="No preconditions or postconditions declared."
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
            <Field label="Id">
              <input
                aria-label={`Invariant id ${index + 1}`}
                value={invariant.invariantId}
                onChange={(event) => updateInvariant(index, { invariantId: event.target.value })}
              />
            </Field>
            <Field label="Phase">
              <select
                value={invariant.phase}
                onChange={(event) => updateInvariant(index, {
                  phase: event.target.value as ContractInvariant['phase'],
                })}
              >
                <option value="PRECONDITION">Precondition</option>
                <option value="POSTCONDITION">Postcondition</option>
              </select>
            </Field>
            <Field label="Expression">
              <input
                aria-label={`Invariant expression ${index + 1}`}
                value={invariant.expression}
                placeholder="exists(ctx.requestId)"
                onChange={(event) => updateInvariant(index, { expression: event.target.value })}
              />
            </Field>
            <Field label="Severity">
              <select
                value={invariant.severity}
                onChange={(event) => updateInvariant(index, {
                  severity: event.target.value as ContractInvariant['severity'],
                })}
              >
                <option value="ERROR">Error</option>
                <option value="WARNING">Warning</option>
              </select>
            </Field>
            <Field label="Meaning">
              <input
                value={invariant.description}
                onChange={(event) => updateInvariant(index, { description: event.target.value })}
              />
            </Field>
            <RemoveButton
              label={`Remove invariant ${index + 1}`}
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
  return (
    <Field label={label}>
      <select
        aria-label={`Contract ${label.toLowerCase()}`}
        value={value === null ? 'UNKNOWN' : String(value).toUpperCase()}
        onChange={(event) => onChange(
          event.target.value === 'UNKNOWN' ? null : event.target.value === 'TRUE',
        )}
      >
        <option value="UNKNOWN">Not declared</option>
        <option value="TRUE">Yes</option>
        <option value="FALSE">No</option>
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
  const hasChildren = Array.isArray(children) ? children.length > 0 : Boolean(children);
  return (
    <section className="contract-semantic-table">
      <header>
        <h3>{title}</h3>
        <button type="button" className="secondary compact" onClick={onAdd}>Add</button>
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
      ×
    </button>
  );
}

function uniqueInvariantId(invariants: ContractInvariant[]): string {
  const used = new Set(invariants.map((invariant) => invariant.invariantId));
  let sequence = invariants.length + 1;
  while (used.has(`invariant-${sequence}`)) sequence += 1;
  return `invariant-${sequence}`;
}
