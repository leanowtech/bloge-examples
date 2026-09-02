import type { ScenarioEditorSnapshot } from './scenarioEditorModel';
import {
  bindingPlan,
  type ExactFixtureSubjectRefV2,
  type FixtureBindingDraft,
  type FixturePlan,
} from '../authoring-workbench/callerSimulationModel';
import type { FixtureSetSummary } from '../authoring-workbench/model';

/**
 * Projects only explicitly saved Scenario Return dependencies into caller-directed Fixture bindings.
 *
 * Generated Decision Scenarios with `dependencies=[]` remain Fixture-free. Inline dependency
 * output is deliberately ignored: the caller must provide the exact saved PRIVATE or governed
 * Fixture revision created by the visible authoring action. Runtime invocation keys and output
 * material never enter the resulting Simulation command.
 */
export function compileScenarioFixturePlanV2(
  snapshot: ScenarioEditorSnapshot,
  subject: ExactFixtureSubjectRefV2,
  savedFixtures: Readonly<Record<string, FixtureSetSummary>>,
): FixturePlan {
  if (snapshot.scenario.dependencies.length === 0) return { kind: 'NONE' };
  const drafts: FixtureBindingDraft[] = snapshot.scenario.dependencies.map((dependency) => {
    if (dependency.behavior.kind !== 'RETURN' || dependency.behavior.boundary !== 'NODE') {
      throw new Error(`Dependency '${dependency.dependencyId}' is not a reusable Return fixture.`);
    }
    const fixture = savedFixtures[dependency.dependencyId];
    if (!fixture) {
      throw new Error(`Save dependency '${dependency.dependencyId}' as a Fixture Case before simulation.`);
    }
    const target = targetFor(dependency.selector, subject);
    const caseId = fixture.cases[0]?.caseId;
    if (!caseId) throw new Error(`Fixture '${fixture.fixtureSetId}' has no reusable Case.`);
    return { target, fixture, selectionKind: 'EXACT_CASE', caseId };
  });
  return bindingPlan('BLOCK', drafts);
}

function targetFor(selector: ScenarioEditorSnapshot['scenario']['dependencies'][number]['selector'],
  subject: ExactFixtureSubjectRefV2): FixtureBindingDraft['target'] {
  if (subject.kind === 'OPERATOR_VERSION' && selector.operatorRef === subject.operatorRef) {
    return { kind: 'SUBJECT' };
  }
  if (selector.functionRef) {
    throw new Error('Function Return fixtures require a compiler-owned stable Call Site ID.');
  }
  if (selector.nodeId) return { kind: 'NODE_PATH', nodePath: path(selector.graphPath, selector.nodeId) };
  throw new Error('Return fixture selector does not resolve to one static v2 target.');
}

function path(graphPath: string, nodeId: string): string[] {
  const prefix = graphPath.split('/').map((part) => part.trim()).filter(Boolean);
  return [...prefix, nodeId];
}
