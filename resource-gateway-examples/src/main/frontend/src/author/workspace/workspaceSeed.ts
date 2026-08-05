import type { ScenarioDraftSet } from '../../contract-scenario/domain';
import type { GraphDraft } from '../../types';

export interface WorkspaceSeedBundle {
  schemaVersion: 'bloge.workspaceSeedBundle.v1';
  template: {
    templateId: string;
    version: string;
    label: string;
  };
  graphDraft: GraphDraft;
  scenarioDraftSets: ScenarioDraftSet[];
  fixtureRefs: string[];
  runtimeProfile: {
    mode: 'SANDBOX_MOCK' | 'SANDBOX_MIXED' | 'LIVE_TEST';
    sandboxRunnable: boolean;
    liveDependencies: boolean;
    mockedOperatorRefs: string[];
  };
  proofStrength: 'EXPLORATORY' | 'REPLAYABLE' | 'GOVERNED';
  capabilities: string[];
  missingCapabilities: string[];
}

export interface WorkspaceForkCommand {
  schemaVersion: 'bloge.workspaceForkCommand.v1';
  seed: WorkspaceSeedBundle;
  workspaceName: string;
  changeSource: string;
}

export interface WorkspaceForkReceipt {
  schemaVersion: 'bloge.workspaceForkReceipt.v1';
  workspaceId: string;
  graphCoordinate: {
    draftId: string;
    revision: number;
    fingerprint: string;
  };
  contractCoordinate: {
    target: ScenarioDraftSet['target'];
    fingerprint: string;
  };
  scenarioSuiteCoordinates: Array<{
    kind: string;
    id: string;
    revision: number;
    fingerprint: string;
  }>;
  fixtureCoordinates: Array<{
    kind: string;
    id: string;
    revision: number;
    fingerprint: string;
  }>;
  sourceTemplateFingerprint: string;
  forkedWorkspaceFingerprint: string;
  runtimeProfile: string;
  proofStrength: string;
  warnings: string[];
  replayed: boolean;
}

/** Builds the exact aggregate sent by the first durable save of a complete canvas example. */
export function workspaceForkCommand(
  graphDraft: GraphDraft,
  scenarioDraftSet: ScenarioDraftSet,
  template: { key: string; label: string } | null,
): WorkspaceForkCommand {
  const fixtureNodeIds = Object.keys(graphDraft.nodeFixtures ?? {}).sort();
  const mockedOperatorRefs = graphDraft.nodes
    .filter((node) => fixtureNodeIds.includes(node.id))
    .map((node) => node.operatorRef)
    .filter((operatorRef, index, all) => Boolean(operatorRef) && all.indexOf(operatorRef) === index)
    .sort();
  const missingCapabilities = [
    ...(scenarioDraftSet.scenarios.length === 0 ? ['SCENARIO_CASES'] : []),
    ...(fixtureNodeIds.length === 0 ? ['CONTROLLED_FIXTURES'] : []),
  ];
  const templateId = template?.key || graphDraft.graphName;
  const label = template?.label || graphDraft.graphName;
  return {
    schemaVersion: 'bloge.workspaceForkCommand.v1',
    workspaceName: label,
    changeSource: 'author-canvas-complete-example',
    seed: {
      schemaVersion: 'bloge.workspaceSeedBundle.v1',
      template: { templateId, version: '1.0.0', label },
      graphDraft: { ...graphDraft, draftId: '', revision: 0 },
      scenarioDraftSets: [{ ...scenarioDraftSet, revision: 0 }],
      fixtureRefs: fixtureNodeIds.map((nodeId) => `graph-node:${nodeId}`),
      runtimeProfile: {
        mode: 'SANDBOX_MOCK',
        sandboxRunnable: missingCapabilities.length === 0,
        liveDependencies: false,
        mockedOperatorRefs,
      },
      proofStrength: 'EXPLORATORY',
      capabilities: ['DURABLE_FORK', 'EXACT_CONTRACT', 'SANDBOX_RUN', 'TABLE_SCENARIOS'],
      missingCapabilities,
    },
  };
}
