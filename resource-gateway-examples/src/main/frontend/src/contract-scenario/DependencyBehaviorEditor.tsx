import { useEffect, useState } from 'react';

import type {
  DependencyBehaviorDraft,
  DependencyBehaviorKind,
} from './domain';
import SchemaValueForm from './SchemaValueForm';
import {
  behaviorForKind,
  dependencySelectorKind,
  durationFromMilliseconds,
  durationMilliseconds,
  selectDependencyTarget,
  type DependencySelectorKind,
} from './scenarioEditorModel';
import type { ScenarioNodeOption } from './scenarioAuthoring';

const BEHAVIORS: Array<[DependencyBehaviorKind, string]> = [
  ['REAL', 'Real'],
  ['RETURN', 'Return'],
  ['ERROR', 'Error'],
  ['DELAY', 'Delay'],
  ['TIMEOUT', 'Timeout'],
  ['REPLAY', 'Replay'],
  ['OBSERVE', 'Observe'],
  ['MUST_NOT_CALL', 'Deny'],
];

interface DependencyBehaviorEditorProps {
  dependency: DependencyBehaviorDraft;
  nodes: ScenarioNodeOption[];
  onChange: (dependency: DependencyBehaviorDraft) => void;
  onRemove?: () => void;
  defaultSelectorKind?: DependencySelectorKind;
}

/** Graphical projection of the complete Scenario dependency behavior protocol. */
export default function DependencyBehaviorEditor({
  dependency,
  nodes,
  onChange,
  onRemove,
  defaultSelectorKind = 'NODE',
}: DependencyBehaviorEditorProps) {
  const inferredSelectorKind = dependencySelectorKind(dependency);
  const [selectorKind, setSelectorKind] = useState<DependencySelectorKind>(
    hasSelector(dependency) ? inferredSelectorKind : defaultSelectorKind,
  );
  const hasSelectorCoordinate = Boolean(
    dependency.selector.nodeId
      || dependency.selector.operatorRef
      || dependency.selector.resourceRef
      || dependency.selector.functionRef,
  );
  useEffect(() => {
    if (hasSelectorCoordinate) setSelectorKind(inferredSelectorKind);
  }, [hasSelectorCoordinate, inferredSelectorKind]);
  useEffect(() => {
    setSelectorKind(hasSelector(dependency)
      ? dependencySelectorKind(dependency)
      : defaultSelectorKind);
  }, [defaultSelectorKind, dependency.dependencyId]);
  const selectorValue = selectorValueFor(dependency, selectorKind);
  const node = nodes.find((candidate) => candidate.id === dependency.selector.nodeId)
    ?? nodes.find((candidate) => candidate.operatorRef === dependency.selector.operatorRef);
  const behavior = dependency.behavior;
  const updateBehavior = (patch: Partial<DependencyBehaviorDraft['behavior']>) => {
    onChange({ ...dependency, behavior: { ...behavior, ...patch } });
  };
  const updateSelectorKind = (nextKind: DependencySelectorKind) => {
    setSelectorKind(nextKind);
    onChange(selectDependencyTarget(
      dependency,
      nextKind,
      defaultSelectorValue(nextKind, node, dependency),
    ));
  };
  const updateSelectorValue = (value: string) => {
    onChange(selectDependencyTarget(dependency, selectorKind, value));
  };

  return (
    <article
      className="scenario-dependency-card"
      data-testid={`scenario-dependency:${dependency.dependencyId}`}
    >
      <header className="scenario-dependency-heading">
        <div className="scenario-dependency-identity">
          <strong>{(node?.label ?? selectorValue) || dependency.dependencyId}</strong>
          <code>{(node?.operatorRef ?? selectorValue) || 'unbound selector'}</code>
        </div>
        {onRemove && (
          <button
            type="button"
            className="icon-button"
            aria-label={`Remove dependency ${dependency.dependencyId}`}
            title="Remove dependency"
            onClick={onRemove}
          >
            ×
          </button>
        )}
      </header>
      <div className="scenario-dependency-primary">
        <div className="scenario-dependency-target">
          <Field label="Target type">
            <select
              aria-label={`Selector kind for ${dependency.dependencyId}`}
              data-testid={`dependency-selector-kind:${dependency.dependencyId}`}
              value={selectorKind}
              onChange={(event) => updateSelectorKind(
                event.target.value as DependencySelectorKind,
              )}
            >
              <option value="NODE">Canvas node</option>
              <option value="OPERATOR">Operator</option>
              <option value="RESOURCE">Resource</option>
              <option value="FUNCTION">Built-in function</option>
            </select>
          </Field>
          <Field label={selectorLabel(selectorKind)}>
            {selectorKind === 'NODE' ? (
              <select
                aria-label={`Selector value for ${dependency.dependencyId}`}
                data-testid={`dependency-selector-value:${dependency.dependencyId}`}
                value={selectorValue}
                onChange={(event) => updateSelectorValue(event.target.value)}
              >
                {nodes.map((candidate) => (
                  <option key={candidate.id} value={candidate.id}>{candidate.label}</option>
                ))}
              </select>
            ) : (
              <input
                aria-label={`${selectorLabel(selectorKind)} for ${dependency.dependencyId}`}
                data-testid={`dependency-selector-value:${dependency.dependencyId}`}
                value={selectorValue}
                onChange={(event) => updateSelectorValue(event.target.value)}
              />
            )}
          </Field>
        </div>
        <div className="scenario-behavior-segments" role="group" aria-label={`Behavior for ${dependency.dependencyId}`}>
          {BEHAVIORS.map(([kind, label]) => (
            <button
              type="button"
              key={kind}
              className={behavior.kind === kind ? 'active' : ''}
              aria-pressed={behavior.kind === kind}
              title={behaviorTitle(kind)}
              onClick={() => onChange({
                ...dependency,
                behavior: behaviorForKind(kind, node),
              })}
            >
              {label}
            </button>
          ))}
        </div>
      </div>

      <div className="scenario-behavior-fields">
        {behavior.kind !== 'REAL' && (
          <Field label="Boundary">
            <select
              aria-label={`Boundary for ${dependency.dependencyId}`}
              value={behavior.boundary}
              onChange={(event) => {
                const boundary = event.target.value as DependencyBehaviorDraft['behavior']['boundary'];
                updateBehavior({
                  boundary,
                  ...(boundary === 'TRANSPORT'
                    && (behavior.kind === 'RETURN' || behavior.kind === 'DELAY')
                    && behavior.statusCode === undefined
                    ? { statusCode: 200 }
                    : {}),
                });
              }}
            >
              <option value="NODE">Logical node</option>
              <option value="TRANSPORT">Transport</option>
            </select>
          </Field>
        )}

        {(behavior.kind === 'RETURN' || behavior.kind === 'DELAY') && (
          <SchemaValueForm
            envelope={node?.outputSchema}
            value={behavior.output}
            onChange={(output) => updateBehavior({ output })}
            label="Returned output"
            compact
          />
        )}

        {(behavior.kind === 'DELAY' || behavior.kind === 'TIMEOUT') && (
          <Field label="Duration (ms)">
            <input
              aria-label={`Duration for ${dependency.dependencyId}`}
              type="number"
              min="1"
              value={durationMilliseconds(behavior.after)}
              onChange={(event) => updateBehavior({
                after: durationFromMilliseconds(Number(event.target.value)),
              })}
            />
          </Field>
        )}

        {(behavior.kind === 'ERROR' || behavior.kind === 'TIMEOUT' || behavior.kind === 'MUST_NOT_CALL') && (
          <div className="scenario-field-grid">
            <Field label="Error code">
              <input
                aria-label={`Error code for ${dependency.dependencyId}`}
                value={behavior.errorCode ?? ''}
                onChange={(event) => updateBehavior({ errorCode: event.target.value })}
              />
            </Field>
            {behavior.kind === 'ERROR' && (
              <Field label="Error type">
                <input
                  value={behavior.errorType ?? ''}
                  onChange={(event) => updateBehavior({ errorType: event.target.value })}
                />
              </Field>
            )}
            <Field label="Error message">
              <input
                value={behavior.errorMessage ?? ''}
                onChange={(event) => updateBehavior({ errorMessage: event.target.value })}
              />
            </Field>
          </div>
        )}

        {behavior.kind === 'REPLAY' && (
          <Field label="Governed replay ref">
            <input
              aria-label={`Replay reference for ${dependency.dependencyId}`}
              value={behavior.replayRef ?? ''}
              placeholder="replay-id@revision:fingerprint"
              onChange={(event) => updateBehavior({ replayRef: event.target.value })}
            />
          </Field>
        )}

        {behavior.boundary === 'TRANSPORT'
          && (behavior.kind === 'RETURN' || behavior.kind === 'DELAY') && (
          <div className="scenario-transport-fields">
            <Field label="Status">
              <input
                type="number"
                min="100"
                max="599"
                value={behavior.statusCode ?? 200}
                onChange={(event) => updateBehavior({ statusCode: Number(event.target.value) })}
              />
            </Field>
            <Field label="Raw body">
              <textarea
                rows={3}
                value={behavior.rawBody ?? ''}
                onChange={(event) => updateBehavior({ rawBody: event.target.value })}
              />
            </Field>
            <StringMapEditor
              label="Response headers"
              value={behavior.headers ?? {}}
              onChange={(headers) => updateBehavior({ headers })}
            />
          </div>
        )}
      </div>

      <details className="scenario-dependency-advanced">
        <summary>Selector, matching & consumption</summary>
        <div className="scenario-advanced-grid">
          <Field label="Graph path">
            <input
              value={dependency.selector.graphPath}
              placeholder="/root"
              onChange={(event) => onChange({
                ...dependency,
                selector: { ...dependency.selector, graphPath: event.target.value },
              })}
            />
          </Field>
          <Field label="Correlation key">
            <input
              value={dependency.selector.correlationKey}
              onChange={(event) => onChange({
                ...dependency,
                selector: { ...dependency.selector, correlationKey: event.target.value },
              })}
            />
          </Field>
          <NumberListEditor
            label="Attempts"
            values={dependency.selector.attempts}
            onChange={(attempts) => onChange({
              ...dependency,
              selector: { ...dependency.selector, attempts },
            })}
          />
          <NumberListEditor
            label="Occurrences"
            values={dependency.selector.occurrences}
            onChange={(occurrences) => onChange({
              ...dependency,
              selector: { ...dependency.selector, occurrences },
            })}
          />
        </div>

        <JsonMapEditor
          label="Input path matches"
          value={dependency.selector.pathEquals}
          onChange={(pathEquals) => onChange({
            ...dependency,
            selector: { ...dependency.selector, pathEquals },
          })}
        />

        {behavior.kind === 'RETURN' && (
          <SchemaValueForm
            envelope={node?.inputSchema}
            value={behavior.expectedInput}
            onChange={(expectedInput) => updateBehavior({ expectedInput })}
            label="Expected dependency input"
            compact
          />
        )}

        <div className="scenario-advanced-grid">
          <label className="scenario-check-field">
            <input
              type="checkbox"
              checked={dependency.consumption.required}
              onChange={(event) => onChange({
                ...dependency,
                consumption: { ...dependency.consumption, required: event.target.checked },
              })}
            />
            <span>Required use</span>
          </label>
          <Field label="Minimum uses">
            <input
              type="number"
              min="0"
              value={dependency.consumption.minUses}
              onChange={(event) => onChange({
                ...dependency,
                consumption: {
                  ...dependency.consumption,
                  minUses: Math.max(0, Number(event.target.value)),
                },
              })}
            />
          </Field>
          <Field label="Maximum uses (0 = unlimited)">
            <input
              type="number"
              min="0"
              value={dependency.consumption.maxUses}
              onChange={(event) => onChange({
                ...dependency,
                consumption: {
                  ...dependency.consumption,
                  maxUses: Math.max(0, Number(event.target.value)),
                },
              })}
            />
          </Field>
          <Field label="On exhausted">
            <select
              value={dependency.consumption.onExhausted}
              onChange={(event) => onChange({
                ...dependency,
                consumption: {
                  ...dependency.consumption,
                  onExhausted: event.target.value as DependencyBehaviorDraft['consumption']['onExhausted'],
                },
              })}
            >
              <option value="FAIL">Fail</option>
              <option value="FALLBACK_TO_REAL">Fall back to real</option>
            </select>
          </Field>
          <Field label="On unmatched">
            <select
              value={dependency.consumption.onUnmatched}
              onChange={(event) => onChange({
                ...dependency,
                consumption: {
                  ...dependency.consumption,
                  onUnmatched: event.target.value as DependencyBehaviorDraft['consumption']['onUnmatched'],
                },
              })}
            >
              <option value="FAIL">Fail</option>
              <option value="WARN">Warn</option>
              <option value="ALLOW_REAL">Allow real</option>
            </select>
          </Field>
          <Field label="Schema check">
            <select
              value={dependency.schemaCheck.mode}
              onChange={(event) => onChange({
                ...dependency,
                schemaCheck: {
                  ...dependency.schemaCheck,
                  mode: event.target.value as DependencyBehaviorDraft['schemaCheck']['mode'],
                },
              })}
            >
              <option value="STRICT">Strict</option>
              <option value="WAIVED">Waived</option>
            </select>
          </Field>
          {dependency.schemaCheck.mode === 'WAIVED' && (
            <Field label="Waiver reason">
              <input
                value={dependency.schemaCheck.waiverReason}
                onChange={(event) => onChange({
                  ...dependency,
                  schemaCheck: {
                    ...dependency.schemaCheck,
                    waiverReason: event.target.value,
                  },
                })}
              />
            </Field>
          )}
        </div>
      </details>
    </article>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return <label className="scenario-labeled-field"><span>{label}</span>{children}</label>;
}

function NumberListEditor({
  label,
  values,
  onChange,
}: {
  label: string;
  values: number[];
  onChange: (values: number[]) => void;
}) {
  return (
    <fieldset className="scenario-list-field">
      <legend>{label}</legend>
      <div>
        {values.map((value, index) => (
          <span key={`${label}-${index}`}>
            <input
              aria-label={`${label} ${index + 1}`}
              type="number"
              min="1"
              value={value}
              onChange={(event) => onChange(values.map((entry, candidate) => (
                candidate === index ? Math.max(1, Number(event.target.value)) : entry
              )).sort((left, right) => left - right))}
            />
            <button
              type="button"
              className="icon-button"
              title={`Remove ${label.toLowerCase()} value`}
              aria-label={`Remove ${label.toLowerCase()} value`}
              onClick={() => onChange(values.filter((_, candidate) => candidate !== index))}
            >
              ×
            </button>
          </span>
        ))}
        <button
          type="button"
          className="secondary compact"
          onClick={() => onChange([...values, (values[values.length - 1] ?? 0) + 1])}
        >
          Add
        </button>
      </div>
    </fieldset>
  );
}

function StringMapEditor({
  label,
  value,
  onChange,
}: {
  label: string;
  value: Record<string, string>;
  onChange: (value: Record<string, string>) => void;
}) {
  return (
    <MapRows
      label={label}
      value={value}
      newKey="Header-Name"
      renderValue={(entry, update) => (
        <input value={entry} onChange={(event) => update(event.target.value)} />
      )}
      onChange={onChange}
    />
  );
}

function JsonMapEditor({
  label,
  value,
  onChange,
}: {
  label: string;
  value: Record<string, unknown>;
  onChange: (value: Record<string, unknown>) => void;
}) {
  return (
    <MapRows
      label={label}
      value={value}
      newKey="/field"
      renderValue={(entry, update) => (
        <JsonValueEditor value={entry} onChange={update} />
      )}
      onChange={onChange}
    />
  );
}

function MapRows<T>({
  label,
  value,
  newKey,
  renderValue,
  onChange,
}: {
  label: string;
  value: Record<string, T>;
  newKey: string;
  renderValue: (value: T, update: (value: T) => void) => React.ReactNode;
  onChange: (value: Record<string, T>) => void;
}) {
  const entries = Object.entries(value);
  return (
    <fieldset className="scenario-map-field">
      <legend>{label}</legend>
      {entries.map(([key, entry], index) => (
        <div key={`${key}-${index}`}>
          <input
            aria-label={`${label} key ${index + 1}`}
            value={key}
            onChange={(event) => {
              const next = [...entries];
              next[index] = [event.target.value, entry];
              onChange(Object.fromEntries(next));
            }}
          />
          {renderValue(entry, (nextValue) => {
            const next = [...entries];
            next[index] = [key, nextValue];
            onChange(Object.fromEntries(next));
          })}
          <button
            type="button"
            className="icon-button danger"
            title={`Remove ${label.toLowerCase()} row`}
            aria-label={`Remove ${label.toLowerCase()} row`}
            onClick={() => onChange(Object.fromEntries(
              entries.filter((_, candidate) => candidate !== index),
            ))}
          >
            ×
          </button>
        </div>
      ))}
      <button
        type="button"
        className="secondary compact"
        onClick={() => {
          let key = newKey;
          let suffix = 2;
          while (Object.prototype.hasOwnProperty.call(value, key)) {
            key = `${newKey}-${suffix}`;
            suffix += 1;
          }
          onChange({ ...value, [key]: '' as T });
        }}
      >
        Add row
      </button>
    </fieldset>
  );
}

function JsonValueEditor({
  value,
  onChange,
}: {
  value: unknown;
  onChange: (value: unknown) => void;
}) {
  const kind = jsonValueKind(value);
  return (
    <span className="scenario-json-value">
      <select
        value={kind}
        onChange={(event) => onChange(defaultJsonValue(event.target.value))}
      >
        <option value="string">Text</option>
        <option value="number">Number</option>
        <option value="boolean">Boolean</option>
        <option value="null">Null</option>
        <option value="json">JSON</option>
      </select>
      {kind === 'boolean' ? (
        <select value={String(value)} onChange={(event) => onChange(event.target.value === 'true')}>
          <option value="true">true</option>
          <option value="false">false</option>
        </select>
      ) : kind === 'null' ? (
        <input value="null" disabled />
      ) : kind === 'json' ? (
        <textarea
          rows={2}
          value={JSON.stringify(value)}
          onChange={(event) => {
            try {
              onChange(JSON.parse(event.target.value));
            } catch {
              // Keep the last valid canonical value while the fragment is incomplete.
            }
          }}
        />
      ) : (
        <input
          type={kind === 'number' ? 'number' : 'text'}
          value={String(value ?? '')}
          onChange={(event) => onChange(
            kind === 'number' ? Number(event.target.value) : event.target.value,
          )}
        />
      )}
    </span>
  );
}

function hasSelector(dependency: DependencyBehaviorDraft): boolean {
  return Boolean(
    dependency.selector.nodeId
      || dependency.selector.operatorRef
      || dependency.selector.resourceRef
      || dependency.selector.functionRef,
  );
}

function selectorValueFor(
  dependency: DependencyBehaviorDraft,
  kind: DependencySelectorKind,
): string {
  if (kind === 'OPERATOR') return dependency.selector.operatorRef;
  if (kind === 'RESOURCE') return dependency.selector.resourceRef;
  if (kind === 'FUNCTION') return dependency.selector.functionRef;
  return dependency.selector.nodeId;
}

function selectorLabel(kind: DependencySelectorKind): string {
  if (kind === 'OPERATOR') return 'Operator reference';
  if (kind === 'RESOURCE') return 'Resource reference';
  if (kind === 'FUNCTION') return 'Function reference';
  return 'Canvas node';
}

function behaviorTitle(kind: DependencyBehaviorKind): string {
  switch (kind) {
    case 'REAL': return 'Call the real dependency';
    case 'RETURN': return 'Return deterministic data';
    case 'ERROR': return 'Return a controlled error';
    case 'DELAY': return 'Return data after a controlled delay';
    case 'TIMEOUT': return 'Simulate a timeout';
    case 'REPLAY': return 'Replay a governed recording';
    case 'OBSERVE': return 'Observe without replacing the call';
    case 'MUST_NOT_CALL': return 'Fail if this dependency is called';
  }
}

function defaultSelectorValue(
  kind: DependencySelectorKind,
  node: ScenarioNodeOption | undefined,
  dependency: DependencyBehaviorDraft,
): string {
  if (kind === 'NODE') return node?.id ?? dependency.selector.nodeId;
  if (kind === 'OPERATOR') return node?.operatorRef ?? dependency.selector.operatorRef;
  return '';
}

function jsonValueKind(value: unknown): 'string' | 'number' | 'boolean' | 'null' | 'json' {
  if (value === null) return 'null';
  if (typeof value === 'string') return 'string';
  if (typeof value === 'number') return 'number';
  if (typeof value === 'boolean') return 'boolean';
  return 'json';
}

function defaultJsonValue(kind: string): unknown {
  if (kind === 'number') return 0;
  if (kind === 'boolean') return true;
  if (kind === 'null') return null;
  if (kind === 'json') return {};
  return '';
}
