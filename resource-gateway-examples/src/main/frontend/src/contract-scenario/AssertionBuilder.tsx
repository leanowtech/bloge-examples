import { useMemo } from 'react';

import { sampleFromSchemaEnvelope } from '../draftModel';
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
  const operators = assertionOperators(assertion.scope);
  const selectedNode = nodes.find((node) => node.id === assertion.nodeId);
  const expectedEnvelope = assertion.scope === 'NODE_OUTPUT'
    ? selectedNode?.outputSchema ?? contract.outputSchema
    : contract.outputSchema;
  const expectedSchema = useMemo(
    () => schemaAtPath(expectedEnvelope, assertion.path),
    [assertion.path, expectedEnvelope],
  );
  const takesPath = assertion.scope === 'OUTPUT_PATH' || assertion.scope === 'NODE_OUTPUT';
  const takesExpected = takesPath
    && (assertion.operator === 'EQUALS' || assertion.operator === 'MATCHES_SCHEMA');

  return (
    <article className="scenario-assertion-card">
      <div className="scenario-assertion-controls">
        <Field label="Scope">
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
            <option value="OUTPUT_PATH">Graph output</option>
            <option value="NODE_OUTPUT">Node output</option>
            <option value="NODE_STATUS">Node status</option>
            <option value="EDGE_TRANSFER">Edge transfer</option>
            <option value="INVOCATION">Dependency use</option>
          </select>
        </Field>

        {(assertion.scope === 'NODE_OUTPUT' || assertion.scope === 'NODE_STATUS') && (
          <Field label="Node">
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
            <Field label="From node">
              <select
                value={assertion.fromNodeId}
                onChange={(event) => onChange({ ...assertion, fromNodeId: event.target.value })}
              >
                {nodes.map((node) => <option key={node.id} value={node.id}>{node.label}</option>)}
              </select>
            </Field>
            <Field label="To node">
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
          <Field label="Dependency">
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
          <Field label="JSON Pointer">
            <input
              aria-label={`Assertion path for ${assertion.assertionId}`}
              value={assertion.path}
              placeholder="/decision/approved"
              onChange={(event) => onChange({ ...assertion, path: event.target.value })}
            />
          </Field>
        )}

        <Field label="Check">
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
              <option key={operator} value={operator}>{operatorLabel(operator)}</option>
            ))}
          </select>
        </Field>

        {assertion.scope === 'NODE_STATUS' && (
          <Field label="Expected status">
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
          title="Remove assertion"
          aria-label="Remove assertion"
          onClick={onRemove}
        >
          ×
        </button>
      </div>

      {takesExpected && (
        <div className="scenario-assertion-expected">
          <SchemaValueForm
            schema={expectedSchema}
            value={assertion.expected}
            onChange={(expected) => onChange({ ...assertion, expected })}
            label={assertion.operator === 'MATCHES_SCHEMA' ? 'Schema expectation' : 'Expected value'}
            compact
          />
          {assertion.operator === 'EQUALS' && typeof assertion.expected === 'number' && (
            <Field label="Tolerance">
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
