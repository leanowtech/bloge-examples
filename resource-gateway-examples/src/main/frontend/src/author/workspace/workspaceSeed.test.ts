import { describe, expect, it } from 'vitest';

import type { ScenarioDraftSet } from '../../contract-scenario/domain';
import type { GraphDraft } from '../../types';
import { workspaceForkCommand } from './workspaceSeed';

describe('Workspace Seed protocol', () => {
  it('closes Graph, Scenario, fixture, runtime, and capability information', () => {
    const command = workspaceForkCommand(graph(), scenarios(), {
      key: 'loan-policy-fallback',
      label: 'Loan policy fallback',
    });

    expect(command.schemaVersion).toBe('bloge.workspaceForkCommand.v1');
    expect(command.seed.template.templateId).toBe('loan-policy-fallback');
    expect(command.seed.graphDraft.draftId).toBe('');
    expect(command.seed.graphDraft.revision).toBe(0);
    expect(command.seed.fixtureRefs).toEqual(['graph-node:risk']);
    expect(command.seed.runtimeProfile).toMatchObject({
      mode: 'SANDBOX_MOCK',
      sandboxRunnable: true,
      liveDependencies: false,
      mockedOperatorRefs: ['risk:score'],
    });
    expect(command.seed.capabilities).toContain('DURABLE_FORK');
    expect(command.seed.missingCapabilities).toEqual([]);
  });
});

function graph(): GraphDraft {
  return {
    schemaVersion: 'bloge.visualGraphDraft.v1',
    draftId: 'ephemeral',
    revision: 7,
    graphName: 'loanPolicy',
    tenantId: 'tenant-a',
    namespace: 'local',
    environment: 'test',
    status: 'DRAFT',
    inputSchema: { format: 'json-schema', version: '2020-12', schema: { type: 'object' } },
    outputSchema: { format: 'json-schema', version: '2020-12', schema: { type: 'object' } },
    nodes: [{
      id: 'risk',
      operatorRef: 'risk:score',
      label: 'Risk score',
      inputs: {},
      config: {},
      position: { x: 0, y: 0 },
    }],
    edges: [],
    visualLayout: {},
    nodeFixtures: { risk: { output: { decision: 'approve' } } },
    output: { nodeId: 'risk', path: '' },
    operatorFingerprints: {},
    operatorSnapshots: {},
  };
}

function scenarios(): ScenarioDraftSet {
  return {
    schemaVersion: 'bloge.scenarioDraftSet.v1',
    scenarioDraftSetId: 'loan-scenarios',
    revision: 0,
    scope: {
      tenantId: 'tenant-a',
      organizationId: 'knowledge-governance',
      projectId: 'tool-studio',
      environment: 'test',
      region: 'sg',
    },
    target: { kind: 'GRAPH', id: 'loanPolicy', revision: 0, fingerprint: 'sha256:local' },
    contractFingerprint: 'sha256:local-contract',
    scenarios: [{
      scenarioId: 'golden',
      name: 'Prime approval',
      description: 'Approves a prime applicant.',
      caseType: 'GOLDEN',
      tags: ['demo'],
      given: { input: { applicantId: 'A-1001' }, provenance: 'AUTHORED' },
      dependencies: [],
      then: { assertions: [] },
    }],
    metadata: {
      owner: 'credit-platform',
      classification: 'INTERNAL',
      createdAt: null,
      updatedAt: null,
      provenance: { source: 'example' },
    },
  };
}
