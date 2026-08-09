import { useMemo } from 'react';
import { X } from 'lucide-react';

import { sampleFromSchemaEnvelope } from '../draftModel';
import { useI18n } from '../i18n/I18nProvider';
import type {
  AssertionDraft,
  AssertionScope,
  ContractDraft,
  ScenarioDraft,
} from './domain';
import SchemaValueForm from './SchemaValueForm';
import {
  assertionForScope,
  assertionOperators,
  assertionPathOptions,
} from './scenarioEditorModel';
import type { ScenarioNodeOption } from './scenarioAuthoring';
import { schemaAtPath } from './schemaWorkbench';

interface AssertionBuilderProps {
  assertion: AssertionDraft;
  contract: ContractDraft;
  nodes: ScenarioNodeOption[];
  dependencies: ScenarioDraft['dependencies'];
  onChange: (assertion: AssertionDraft) => void;
  onRemove: () => void;
}

/** Scope-aware graphical builder for all governed Scenario v1 assertion forms. */
export default function AssertionBuilder({
  assertion,
  contract,
  nodes,
  dependencies,
  onChange,
  onRemove,
}: AssertionBuilderProps) {
  const { t , d } = useI18n();
  const operators = assertionOperators(assertion.scope);
  const selectedNode = nodes.find((node) => node.id === assertion.nodeId);
  const expectedEnvelope = assertion.scope === 'NODE_OUTPUT'
    ? selectedNode?.outputSchema ?? contract.outputSchema
    : contract.outputSchema;
  const expectedSchema = useMemo(
    () => schemaAtPath(expectedEnvelope, assertion.path),
    [assertion.path, expectedEnvelope],
  );
  const pathOptions = useMemo(
    () => assertionPathOptions(expectedEnvelope),
    [expectedEnvelope],
  );
  const takesPath = assertion.scope === 'OUTPUT_PATH' || assertion.scope === 'NODE_OUTPUT';
  const takesExpected = takesPath
    && (assertion.operator === 'EQUALS' || assertion.operator === 'MATCHES_SCHEMA');
  const knownPath = pathOptions.some((option) => option.path === assertion.path);
  const pathPickerValue = knownPath ? assertion.path : '__custom__';
  const changePath = (path: string) => {
    const nextSchema = schemaAtPath(expectedEnvelope, path);
    onChange({
      ...assertion,
      path,
      ...(assertion.operator === 'EQUALS'
        ? { expected: sampleFromSchemaEnvelope({ schema: nextSchema }) }
        : {}),
    });
  };

  return (
    <article className="scenario-assertion-card">
      <div className="scenario-assertion-controls">
        <Field label={t('Scope')}>
          <select
            aria-label={`Assertion scope for ${assertion.assertionId}`}
            value={assertion.scope}
            onChange={(event) => onChange(assertionForScope(
              assertion,
              event.target.value as AssertionScope,
              contract,
              nodes,
              dependencies,
            ))}
          >
            <option value="OUTPUT_PATH">{t('Graph output')}</option>
            <option value="NODE_OUTPUT">{t('Node output')}</option>
            <option value="NODE_STATUS">{t('Node status')}</option>
            <option value="EDGE_TRANSFER">{t('Edge transfer')}</option>
            <option value="INVOCATION">{t('Dependency use')}</option>
          </select>
        </Field>

        {(assertion.scope === 'NODE_OUTPUT' || assertion.scope === 'NODE_STATUS') && (
          <Field label={t('Node')}>
            <select
              aria-label={`Assertion node for ${assertion.assertionId}`}
              value={assertion.nodeId}
              onChange={(event) => {
                const node = nodes.find((candidate) => candidate.id === event.target.value);
                onChange({
                  ...assertion,
                  nodeId: event.target.value,
                  ...(assertion.scope === 'NODE_OUTPUT'
                    && assertion.operator === 'EQUALS'
                    ? {
                        expected: node?.outputSchema
                          ? sampleFromSchemaEnvelope(node.outputSchema)
                          : {},
                      }
                    : {}),
                });
              }}
            >
              {nodes.map((node) => <option key={node.id} value={node.id}>{node.label}</option>)}
            </select>
          </Field>
        )}

        {assertion.scope === 'EDGE_TRANSFER' && (
          <>
            <Field label={t('From node')}>
              <select
                value={assertion.fromNodeId}
                onChange={(event) => onChange({ ...assertion, fromNodeId: event.target.value })}
              >
                {nodes.map((node) => <option key={node.id} value={node.id}>{node.label}</option>)}
              </select>
            </Field>
            <Field label={t('To node')}>
              <select
                value={assertion.toNodeId}
                onChange={(event) => onChange({ ...assertion, toNodeId: event.target.value })}
              >
                {nodes.map((node) => <option key={node.id} value={node.id}>{node.label}</option>)}
              </select>
            </Field>
          </>
        )}

        {assertion.scope === 'INVOCATION' && (
          <Field label={t('Dependency')}>
            <select
              aria-label={`Assertion dependency for ${assertion.assertionId}`}
              value={assertion.nodeId}
              onChange={(event) => onChange({ ...assertion, nodeId: event.target.value })}
            >
              {dependencies.map((dependency) => (
                <option key={dependency.dependencyId} value={dependency.dependencyId}>
                  {dependency.dependencyId}
                </option>
              ))}
            </select>
          </Field>
        )}

        {takesPath && (
          <>
            <Field label={t(assertion.scope === 'NODE_OUTPUT' ? 'Node output field' : 'Result field')}>
              <select
                aria-label={`Assertion path for ${assertion.assertionId}`}
                data-testid={`assertion-path-picker:${assertion.assertionId}`}
                value={pathPickerValue}
                onChange={(event) => {
                  const value = event.target.value;
                  changePath(value === '__custom__' ? '/' : value);
                }}
              >
                {pathOptions.map((option) => (
                  <option key={option.path || '$'} value={option.path}>
                    {option.label} · {option.type}
                  </option>
                ))}
                <option value="__custom__">{t('Custom path...')}</option>
              </select>
            </Field>
            {!knownPath && (
              <Field label={t('Custom path')}>
                <input
                  aria-label={`Custom assertion path for ${assertion.assertionId}`}
                  value={assertion.path}
                  placeholder={t('/decision/approved')}
                  onChange={(event) => changePath(event.target.value)}
                />
              </Field>
            )}
          </>
        )}

        <Field label={t('Check')}>
          <select
            aria-label={`Assertion operator for ${assertion.assertionId}`}
            value={assertion.operator}
            onChange={(event) => {
              const operator = event.target.value as AssertionDraft['operator'];
              onChange({
                ...assertion,
                operator,
                ...(operator === 'NOT_USED' ? { expected: 0 } : {}),
                ...(operator === 'USED' ? { expected: 1 } : {}),
              });
            }}
          >
            {operators.map((operator) => (
              <option key={operator} value={operator}>{d(operatorLabel(operator))}</option>
            ))}
          </select>
        </Field>

        {assertion.scope === 'NODE_STATUS' && (
          <Field label={t('Expected status')}>
            <select
              value={String(assertion.expected ?? 'SUCCESS')}
              onChange={(event) => onChange({ ...assertion, expected: event.target.value })}
            >
              {['SUCCESS', 'FAILED', 'TIMEOUT', 'SKIPPED', 'PARTIAL', 'MOCKED'].map((status) => (
                <option key={status} value={status}>{status}</option>
              ))}
            </select>
          </Field>
        )}

        <button
          type="button"
          className="icon-button danger"
          title={t('Remove assertion')}
          aria-label={t('Remove assertion')}
          onClick={onRemove}
        >
          <X size={14} aria-hidden="true" />
        </button>
      </div>

      {takesExpected && (
        <div className="scenario-assertion-expected">
          <SchemaValueForm
            schema={expectedSchema}
            value={assertion.expected}
            onChange={(expected) => onChange({ ...assertion, expected })}
            label={t(assertion.operator === 'MATCHES_SCHEMA' ? 'Schema expectation' : 'Expected value')}
            compact
          />
          {assertion.operator === 'EQUALS' && typeof assertion.expected === 'number' && (
            <Field label={t('Tolerance')}>
              <input
                type="number"
                min="0"
                step="any"
                value={assertion.numericTolerance ?? 0}
                onChange={(event) => onChange({
                  ...assertion,
                  numericTolerance: Math.max(0, Number(event.target.value)),
                })}
              />
            </Field>
          )}
        </div>
      )}
    </article>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return <label className="scenario-labeled-field"><span>{label}</span>{children}</label>;
}

function operatorLabel(operator: AssertionDraft['operator']): string {
  switch (operator) {
    case 'MATCHES_SCHEMA': return 'Matches schema';
    case 'EXISTS': return 'Exists';
    case 'ABSENT': return 'Absent';
    case 'STATUS': return 'Has status';
    case 'USED': return 'Was used';
    case 'NOT_USED': return 'Was not used';
    default: return 'Equals';
  }
}
