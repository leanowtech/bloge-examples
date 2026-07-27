import type { GraphDraft, NodeFixture, SimulationRequest } from '../types';
import type {
  AssertionDraft,
  DependencyBehaviorDraft,
  ScenarioDiagnostic,
  ScenarioDraft,
  ScenarioDraftSet,
} from './domain';

export interface ScenarioSimulationCompilation {
  compiled: boolean;
  scenarioId: string;
  targetFingerprint: string;
  contractFingerprint: string;
  request?: SimulationRequest;
  assertions: AssertionDraft[];
  diagnostics: ScenarioDiagnostic[];
}

/**
 * Compiles one graph-target Scenario into the existing transient simulation request.
 *
 * Only exact node-level REAL and RETURN behaviors are lossless in NodeFixture. Advanced selectors,
 * transport behavior, and failure/replay/observation controls fail closed and must use governed
 * Scenario execution.
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
  const expectedTargetId = graphDraft.draftId || graphDraft.graphName;
  if (draftSet.target.kind !== 'GRAPH' || draftSet.target.id !== expectedTargetId) {
    diagnostics.push(error(
      'visual.scenario.target.graphIdMismatch',
      `Scenario target '${draftSet.target.id}' does not match GraphDraft '${expectedTargetId}'.`,
      '/target/id',
    ));
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
  if (draftSet.target.revision > 0 && draftSet.target.revision !== (graphDraft.revision ?? 0)) {
    diagnostics.push(error(
      'visual.scenario.target.revisionStale',
      'Scenario target revision is stale.',
      '/target/revision',
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
      'Graph-target Scenario input must be a JSON object.',
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
