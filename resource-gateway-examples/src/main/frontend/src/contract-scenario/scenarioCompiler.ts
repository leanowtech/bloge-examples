import type { GraphDraft, NodeFixture, SimulationRequest } from '../types';
import type {
  AssertionDraft,
  ContractDraft,
  DependencyBehaviorDraft,
  ScenarioDiagnostic,
  ScenarioDraft,
  ScenarioDraftSet,
} from './domain';
import { sha256Fingerprint } from './fingerprint';
import type { ScenarioEditorSnapshot } from './scenarioEditorModel';
import { normalizeSchema, schemaType } from './schemaWorkbench';

export interface ScenarioCompilationProof {
  editorSnapshotFingerprint: string;
  compiledPlanSourceFingerprint: string;
  requestSourceFingerprint: string;
  evidenceSourceFingerprint: string;
  requestFingerprint: string;
}

export interface ScenarioSimulationCompilation {
  compiled: boolean;
  scenarioId: string;
  targetFingerprint: string;
  contractFingerprint: string;
  request?: SimulationRequest;
  assertions: AssertionDraft[];
  diagnostics: ScenarioDiagnostic[];
  proof?: ScenarioCompilationProof;
}

/**
 * Compiles only an immutable graphical-editor snapshot and binds every local run artifact to it.
 *
 * This is the canonical Author UI entry point. The draft-set compiler below remains as a protocol
 * compatibility adapter for callers that have not yet adopted editor snapshots.
 */
export async function compileScenarioEditorSnapshotForSimulation(
  graphDraft: GraphDraft,
  snapshot: ScenarioEditorSnapshot,
  currentTargetFingerprint: string,
  currentContractFingerprint: string,
): Promise<ScenarioSimulationCompilation> {
  const sourceFingerprint = await sha256Fingerprint(snapshot);
  const diagnostics = validateReturnValues(snapshot);
  if (diagnostics.length > 0) {
    return {
      compiled: false,
      scenarioId: snapshot.scenario.scenarioId,
      targetFingerprint: snapshot.target.fingerprint,
      contractFingerprint: snapshot.contractFingerprint,
      assertions: [],
      diagnostics,
    };
  }
  const draftSet = snapshotDraftSet(snapshot);
  const compilation = compileScenarioForSimulation(
    graphDraft,
    draftSet,
    snapshot.scenario.scenarioId,
    currentTargetFingerprint,
    currentContractFingerprint,
  );
  if (!compilation.compiled || !compilation.request) {
    return compilation;
  }
  return {
    ...compilation,
    proof: {
      editorSnapshotFingerprint: sourceFingerprint,
      compiledPlanSourceFingerprint: sourceFingerprint,
      requestSourceFingerprint: sourceFingerprint,
      evidenceSourceFingerprint: sourceFingerprint,
      requestFingerprint: await sha256Fingerprint(compilation.request),
    },
  };
}

/**
 * Compiles one Graph or Operator Scenario into the existing transient simulation request.
 *
 * Only exact node-level REAL and RETURN behaviors are lossless in NodeFixture. Advanced selectors,
 * transport behavior, and failure/replay/observation controls fail closed and must use governed
 * Scenario execution. Operator targets execute through a synthetic one-node GraphDraft whose
 * operator reference remains the exact visual catalog coordinate.
 */
export function compileScenarioForSimulation(
  graphDraft: GraphDraft,
  draftSet: ScenarioDraftSet,
  scenarioId: string,
  currentTargetFingerprint: string,
  currentContractFingerprint: string,
): ScenarioSimulationCompilation {
  const diagnostics: ScenarioDiagnostic[] = [];
  const selected = draftSet.scenarios.find((scenario) => scenario.scenarioId === scenarioId);
  validateExactInputs(graphDraft, draftSet, currentTargetFingerprint, currentContractFingerprint, diagnostics);
  if (!selected) {
    diagnostics.push(error(
      'visual.scenario.compile.scenarioMissing',
      `Scenario '${scenarioId}' does not exist in the draft set.`,
      '/scenarioId',
    ));
    return blocked(draftSet, scenarioId, diagnostics);
  }

  const fixtures: Record<string, NodeFixture> = {};
  const persistedFixtures = { ...(graphDraft.nodeFixtures ?? {}) };
  const controlledNodes = new Set<string>();
  for (const [index, dependency] of selected.dependencies.entries()) {
    const target = `/scenarios/${scenarioId}/dependencies/${index}`;
    if (!losslesslyRepresentable(dependency)) {
      diagnostics.push(error(
        'visual.scenario.compile.governedBehaviorRequired',
        `Dependency behavior '${dependency.behavior.kind}' requires governed Scenario execution.`,
        target,
      ));
      continue;
    }
    const nodeId = dependency.selector.nodeId;
    if (!graphDraft.nodes.some((node) => node.id === nodeId)) {
      diagnostics.push(error(
        'visual.scenario.dependency.nodeUnknown',
        `Dependency node '${nodeId}' does not exist in the GraphDraft.`,
        `${target}/selector/nodeId`,
      ));
      continue;
    }
    if (controlledNodes.has(nodeId)) {
      diagnostics.push(error(
        'visual.scenario.compile.nodeBehaviorDuplicate',
        `Transient Scenario has more than one behavior for node '${nodeId}'.`,
        `${target}/selector/nodeId`,
      ));
      continue;
    }
    controlledNodes.add(nodeId);
    if (dependency.behavior.kind === 'REAL') {
      delete persistedFixtures[nodeId];
    } else {
      fixtures[nodeId] = {
        output: dependency.behavior.output ?? null,
        ...(dependency.behavior.expectedInput !== undefined
          ? { expectedInput: dependency.behavior.expectedInput }
          : {}),
      };
    }
  }

  const context = graphInput(selected, diagnostics);
  if (diagnostics.some((diagnostic) => diagnostic.level === 'ERROR')) {
    return blocked(draftSet, scenarioId, diagnostics);
  }
  const executableDraft: GraphDraft = {
    ...graphDraft,
    nodeFixtures: persistedFixtures,
  };
  return {
    compiled: true,
    scenarioId,
    targetFingerprint: draftSet.target.fingerprint,
    contractFingerprint: draftSet.contractFingerprint,
    request: {
      draft: executableDraft,
      context,
      outputNode: graphDraft.output.nodeId,
      ...(Object.keys(fixtures).length > 0 ? { fixtures } : {}),
    },
    assertions: selected.then.assertions.map((assertion) => ({ ...assertion })),
    diagnostics,
  };
}

function validateExactInputs(
  graphDraft: GraphDraft,
  draftSet: ScenarioDraftSet,
  currentTargetFingerprint: string,
  currentContractFingerprint: string,
  diagnostics: ScenarioDiagnostic[],
): void {
  if (draftSet.target.kind === 'GRAPH') {
    const expectedTargetId = graphDraft.draftId || graphDraft.graphName;
    if (draftSet.target.id !== expectedTargetId) {
      diagnostics.push(error(
        'visual.scenario.target.graphIdMismatch',
        `Scenario target '${draftSet.target.id}' does not match GraphDraft '${expectedTargetId}'.`,
        '/target/id',
      ));
    }
    if (draftSet.target.revision > 0 && draftSet.target.revision !== (graphDraft.revision ?? 0)) {
      diagnostics.push(error(
        'visual.scenario.target.revisionStale',
        'Scenario target revision is stale.',
        '/target/revision',
      ));
    }
  } else {
    const executableNodes = graphDraft.nodes.filter(
      (node) => node.operatorRef === draftSet.target.id,
    );
    if (executableNodes.length !== 1 || graphDraft.output.nodeId !== executableNodes[0]?.id) {
      diagnostics.push(error(
        'visual.scenario.target.operatorIdMismatch',
        `Scenario target '${draftSet.target.id}' does not match the executable Operator projection.`,
        '/target/id',
      ));
    }
  }
  if (!draftSet.target.fingerprint || draftSet.target.fingerprint !== currentTargetFingerprint) {
    diagnostics.push(error(
      'visual.scenario.target.fingerprintStale',
      'Scenario target fingerprint is missing or stale.',
      '/target/fingerprint',
    ));
  }
  if (!draftSet.contractFingerprint || draftSet.contractFingerprint !== currentContractFingerprint) {
    diagnostics.push(error(
      'visual.scenario.contract.stale',
      'Scenario Contract fingerprint is missing or stale.',
      '/contractFingerprint',
    ));
  }
}

function graphInput(
  scenario: ScenarioDraft,
  diagnostics: ScenarioDiagnostic[],
): Record<string, unknown> {
  const input = scenario.given.input;
  if (!input || Array.isArray(input) || typeof input !== 'object') {
    diagnostics.push(error(
      'visual.scenario.compile.graphInputNotObject',
      'Scenario input must be a JSON object.',
      '/given/input',
    ));
    return {};
  }
  return { ...(input as Record<string, unknown>) };
}

function losslesslyRepresentable(dependency: DependencyBehaviorDraft): boolean {
  const { selector, behavior } = dependency;
  return (behavior.kind === 'REAL' || behavior.kind === 'RETURN')
    && behavior.boundary === 'NODE'
    && selector.nodeId.length > 0
    && selector.operatorRef.length === 0
    && selector.resourceRef.length === 0
    && selector.functionRef.length === 0
    && selector.attempts.length === 0
    && selector.occurrences.length === 0
    && selector.correlationKey.length === 0
    && Object.keys(selector.pathEquals).length === 0;
}

function blocked(
  draftSet: ScenarioDraftSet,
  scenarioId: string,
  diagnostics: ScenarioDiagnostic[],
): ScenarioSimulationCompilation {
  return {
    compiled: false,
    scenarioId,
    targetFingerprint: draftSet.target.fingerprint,
    contractFingerprint: draftSet.contractFingerprint,
    assertions: [],
    diagnostics,
  };
}

function error(code: string, message: string, target: string): ScenarioDiagnostic {
  return { level: 'ERROR', code, message, target };
}

function snapshotDraftSet(snapshot: ScenarioEditorSnapshot): ScenarioDraftSet {
  return {
    schemaVersion: 'bloge.scenarioDraftSet.v1',
    scenarioDraftSetId: snapshot.scenarioDraftSetId,
    revision: snapshot.scenarioRevision,
    scope: {
      tenantId: '',
      organizationId: '',
      projectId: '',
      environment: '',
      region: '',
    },
    target: snapshot.target,
    contractFingerprint: snapshot.contractFingerprint,
    scenarios: [snapshot.scenario],
    metadata: {
      owner: '',
      classification: 'INTERNAL',
      createdAt: null,
      updatedAt: null,
      provenance: { source: 'scenario-editor-snapshot' },
    },
  };
}

function validateReturnValues(snapshot: ScenarioEditorSnapshot): ScenarioDiagnostic[] {
  const diagnostics: ScenarioDiagnostic[] = [];
  snapshot.scenario.dependencies.forEach((dependency) => {
    if (dependency.behavior.kind !== 'RETURN' && dependency.behavior.kind !== 'DELAY') {
      return;
    }
    const nodeId = dependency.selector.nodeId;
    const outputSchema = snapshot.nodeSchemas[nodeId]?.outputSchema;
    if (!outputSchema) {
      return;
    }
    validateRequiredValue(
      outputSchema.schema,
      dependency.behavior.output,
      `/dependencies/${pointerSegment(dependency.dependencyId)}/behavior/output`,
      diagnostics,
    );
  });
  return diagnostics;
}

function validateRequiredValue(
  rawSchema: ContractDraft['outputSchema']['schema'],
  value: unknown,
  target: string,
  diagnostics: ScenarioDiagnostic[],
): void {
  const schema = normalizeSchema(rawSchema);
  if (schemaType(schema) !== 'object') {
    return;
  }
  const record = isRecord(value) ? value : {};
  const properties = isRecord(schema.properties) ? schema.properties : {};
  const required = Array.isArray(schema.required)
    ? schema.required.filter((entry): entry is string => typeof entry === 'string')
    : [];
  required.forEach((field) => {
    const fieldTarget = `${target}/${pointerSegment(field)}`;
    const fieldValue = record[field];
    if (missingRequiredValue(fieldValue)) {
      diagnostics.push(error(
        'visual.scenario.return.requiredValueMissing',
        `Required Return value '${field}' is empty.`,
        fieldTarget,
      ));
      return;
    }
    const fieldSchema = properties[field];
    if (isRecord(fieldSchema)) {
      validateRequiredValue(fieldSchema, fieldValue, fieldTarget, diagnostics);
    }
  });
}

function missingRequiredValue(value: unknown): boolean {
  return value === undefined || value === null || (typeof value === 'string' && value.trim() === '');
}

function pointerSegment(value: string): string {
  return value.replace(/~/g, '~0').replace(/\//g, '~1');
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
