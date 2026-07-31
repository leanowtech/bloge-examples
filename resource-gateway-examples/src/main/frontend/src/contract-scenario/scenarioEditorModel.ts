import { sampleFromSchemaEnvelope } from '../draftModel';
import type {
  AssertionDraft,
  AssertionScope,
  ContractDraft,
  DependencyBehaviorDraft,
  DependencyBehaviorKind,
  ScenarioDraft,
  ScenarioDraftSet,
} from './domain';
import type { ScenarioNodeOption } from './scenarioAuthoring';
import { projectSchemaFields, schemaType } from './schemaWorkbench';

export type DependencySelectorKind = 'NODE' | 'OPERATOR' | 'RESOURCE' | 'FUNCTION';

export interface AssertionPathOption {
  path: string;
  label: string;
  type: string;
}

export interface ScenarioEditorSnapshot {
  schemaVersion: 'bloge.scenarioEditorSnapshot.v1';
  scenarioDraftSetId: string;
  scenarioRevision: number;
  target: ScenarioDraftSet['target'];
  contractFingerprint: string;
  contract: Pick<ContractDraft, 'inputSchema' | 'outputSchema'>;
  scenario: ScenarioDraft;
  nodeSchemas: Record<string, Pick<ScenarioNodeOption, 'inputSchema' | 'outputSchema'>>;
}

/**
 * Freezes the exact protocol values visible in the graphical Scenario editor at Run time.
 *
 * The snapshot intentionally excludes mutable React state and timestamps. It is safe to fingerprint,
 * compile, and retain as evidence provenance without later form edits changing its meaning.
 */
export function captureScenarioEditorSnapshot(
  draftSet: ScenarioDraftSet,
  scenarioId: string,
  contract: ContractDraft,
  nodes: ScenarioNodeOption[],
): ScenarioEditorSnapshot {
  const scenario = draftSet.scenarios.find((candidate) => candidate.scenarioId === scenarioId);
  if (!scenario) {
    throw new Error(`Scenario '${scenarioId}' does not exist in the draft set.`);
  }
  return deepFreeze(cloneJson({
    schemaVersion: 'bloge.scenarioEditorSnapshot.v1',
    scenarioDraftSetId: draftSet.scenarioDraftSetId,
    scenarioRevision: draftSet.revision,
    target: draftSet.target,
    contractFingerprint: draftSet.contractFingerprint,
    contract: {
      inputSchema: contract.inputSchema,
      outputSchema: contract.outputSchema,
    },
    scenario,
    nodeSchemas: Object.fromEntries(nodes.map((node) => [
      node.id,
      {
        ...(node.inputSchema ? { inputSchema: node.inputSchema } : {}),
        ...(node.outputSchema ? { outputSchema: node.outputSchema } : {}),
      },
    ])),
  } satisfies ScenarioEditorSnapshot));
}

/** Creates a complete, immediately editable behavior payload for the selected business intent. */
export function behaviorForKind(
  kind: DependencyBehaviorKind,
  node?: ScenarioNodeOption,
): DependencyBehaviorDraft['behavior'] {
  const output = node?.outputSchema ? sampleFromSchemaEnvelope(node.outputSchema) : {};
  switch (kind) {
    case 'RETURN':
      return { kind, boundary: 'NODE', output };
    case 'ERROR':
      return {
        kind,
        boundary: 'NODE',
        errorCode: 'SCENARIO_DEPENDENCY_ERROR',
        errorType: 'DEPENDENCY_ERROR',
        errorMessage: 'Dependency failed as defined by the Scenario.',
      };
    case 'DELAY':
      return { kind, boundary: 'NODE', after: 'PT0.1S', output };
    case 'TIMEOUT':
      return {
        kind,
        boundary: 'NODE',
        after: 'PT1S',
        errorCode: 'SCENARIO_TIMEOUT',
        errorType: 'TIMEOUT',
        errorMessage: 'Dependency exceeded the Scenario timeout.',
      };
    case 'REPLAY':
      return { kind, boundary: 'NODE', replayRef: '' };
    case 'OBSERVE':
      return { kind, boundary: 'NODE' };
    case 'MUST_NOT_CALL':
      return {
        kind,
        boundary: 'NODE',
        errorCode: 'SCENARIO_MUST_NOT_CALL',
        errorType: 'DENIED_INVOCATION',
        errorMessage: 'Scenario forbids this dependency invocation.',
      };
    default:
      return { kind: 'REAL', boundary: 'NODE' };
  }
}

/** Switches selector scope and clears competing coordinates so compilation stays unambiguous. */
export function selectDependencyTarget(
  dependency: DependencyBehaviorDraft,
  kind: DependencySelectorKind,
  value: string,
): DependencyBehaviorDraft {
  return {
    ...dependency,
    selector: {
      ...dependency.selector,
      nodeId: kind === 'NODE' ? value : '',
      operatorRef: kind === 'OPERATOR' ? value : '',
      resourceRef: kind === 'RESOURCE' ? value : '',
      functionRef: kind === 'FUNCTION' ? value : '',
    },
  };
}

/** Derives the active selector kind from one canonical dependency payload. */
export function dependencySelectorKind(
  dependency: DependencyBehaviorDraft,
): DependencySelectorKind {
  if (dependency.selector.operatorRef) return 'OPERATOR';
  if (dependency.selector.resourceRef) return 'RESOURCE';
  if (dependency.selector.functionRef) return 'FUNCTION';
  return 'NODE';
}

/** Converts a form-friendly duration into the ISO-8601 wire format used by Java Duration. */
export function durationFromMilliseconds(milliseconds: number): string {
  const bounded = Math.max(1, Math.round(Number.isFinite(milliseconds) ? milliseconds : 1));
  if (bounded % 1000 === 0) {
    return `PT${bounded / 1000}S`;
  }
  const seconds = (bounded / 1000).toFixed(3).replace(/0+$/, '');
  return `PT${seconds}S`;
}

/** Converts a supported ISO-8601 seconds duration back to a numeric form control. */
export function durationMilliseconds(duration: string | undefined): number {
  const match = /^PT(\d+(?:\.\d+)?)S$/i.exec(duration?.trim() ?? '');
  return match ? Math.max(1, Math.round(Number(match[1]) * 1000)) : 1000;
}

/** Reinitializes scope-specific assertion coordinates and operators to valid governed defaults. */
export function assertionForScope(
  assertion: AssertionDraft,
  scope: AssertionScope,
  contract: ContractDraft,
  nodes: ScenarioNodeOption[],
  dependencies: ScenarioDraft['dependencies'],
): AssertionDraft {
  const firstNode = nodes[0];
  const secondNode = nodes[1] ?? firstNode;
  const base: AssertionDraft = {
    ...assertion,
    scope,
    nodeId: '',
    fromNodeId: '',
    toNodeId: '',
    path: '',
    numericTolerance: undefined,
  };
  switch (scope) {
    case 'NODE_OUTPUT':
      return {
        ...base,
        nodeId: firstNode?.id ?? '',
        operator: 'EQUALS',
        expected: firstNode?.outputSchema ? sampleFromSchemaEnvelope(firstNode.outputSchema) : {},
      };
    case 'NODE_STATUS':
      return {
        ...base,
        nodeId: firstNode?.id ?? '',
        operator: 'STATUS',
        expected: 'SUCCESS',
      };
    case 'EDGE_TRANSFER':
      return {
        ...base,
        fromNodeId: firstNode?.id ?? '',
        toNodeId: secondNode?.id ?? '',
        operator: 'USED',
        expected: 1,
      };
    case 'INVOCATION':
      return {
        ...base,
        nodeId: dependencies[0]?.dependencyId ?? '',
        operator: 'USED',
        expected: 1,
      };
    default:
      return {
        ...base,
        operator: 'EQUALS',
        expected: sampleFromSchemaEnvelope(contract.outputSchema),
      };
  }
}

/** Returns operators accepted by the authoritative v1 Scenario validator for one scope. */
export function assertionOperators(scope: AssertionScope): AssertionDraft['operator'][] {
  switch (scope) {
    case 'OUTPUT_PATH':
    case 'NODE_OUTPUT':
      return ['EQUALS', 'MATCHES_SCHEMA', 'EXISTS', 'ABSENT'];
    case 'NODE_STATUS':
      return ['STATUS', 'EQUALS'];
    case 'EDGE_TRANSFER':
      return ['USED'];
    case 'INVOCATION':
      return ['USED', 'NOT_USED'];
  }
}

/** Projects the selected result schema into stable business-field choices for assertion authoring. */
export function assertionPathOptions(
  schema: ContractDraft['outputSchema'],
): AssertionPathOption[] {
  return [
    { path: '', label: 'Whole result', type: schemaType(schema.schema) },
    ...projectSchemaFields(schema)
      .filter((field) => !field.path.includes('[]'))
      .map((field) => ({
        path: field.path,
        label: field.path,
        type: field.type,
      })),
  ];
}

function cloneJson<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

function deepFreeze<T>(value: T): T {
  if (value && typeof value === 'object' && !Object.isFrozen(value)) {
    Object.values(value as Record<string, unknown>).forEach((entry) => deepFreeze(entry));
    Object.freeze(value);
  }
  return value;
}
