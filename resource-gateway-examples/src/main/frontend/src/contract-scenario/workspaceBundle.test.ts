import { describe, expect, it } from 'vitest';

import { contractDraftFromGraphDraft } from './domain';
import { sha256Fingerprint } from './fingerprint';
import { scenarioDraftSetFromCanvas } from './scenarioAuthoring';
import { graphDraft, nodes } from './testFixtures';
import {
  createWorkspaceBundle,
  parseWorkspaceBundle,
  WorkspaceBundleError,
} from './workspaceBundle';

describe('portable Contract and Scenario workspace bundle', () => {
  it('round-trips one exact graph, Contract, Scenario set, and operator snapshot index', async () => {
    const graph = graphDraft();
    const targetFingerprint = await sha256Fingerprint(graph);
    const contract = contractDraftFromGraphDraft(graph, targetFingerprint);
    const contractFingerprint = await sha256Fingerprint(contract);
    const scenarios = scenarioDraftSetFromCanvas(
      contract.target,
      contractFingerprint,
      graph,
      nodes(),
      [],
    );

    const bundle = createWorkspaceBundle(graph, contract, contractFingerprint, scenarios, null);
    const parsed = await parseWorkspaceBundle(JSON.stringify(bundle));

    expect(parsed).toEqual(bundle);
    expect(parsed.operatorSnapshotRefs).toEqual([
      { nodeId: 'score', operatorRef: 'risk:score' },
      { nodeId: 'decide', operatorRef: 'risk:decide' },
    ]);
    expect(parsed.contractProjection.scope).toEqual(scenarios.scope);
  });

  it('fails closed when a Contract fingerprint or target coordinate is changed', async () => {
    const bundle = await bundleFixture();

    await expect(parseWorkspaceBundle(JSON.stringify({
      ...bundle,
      contractProjection: {
        ...bundle.contractProjection,
        contractFingerprint: sha('f'),
      },
    }))).rejects.toMatchObject({
      code: 'WORKSPACE_CONTRACT_FINGERPRINT_INVALID',
    });
    await expect(parseWorkspaceBundle(JSON.stringify({
      ...bundle,
      scenarioDraftSet: {
        ...bundle.scenarioDraftSet,
        target: { ...bundle.scenarioDraftSet.target, id: 'another-graph' },
      },
    }))).rejects.toMatchObject({
      code: 'WORKSPACE_TARGET_MISMATCH',
    });
  });

  it('rejects raw credentials while allowing references and schema field declarations', async () => {
    const bundle = await bundleFixture();
    const safe = {
      ...bundle,
      scenarioDraftSet: {
        ...bundle.scenarioDraftSet,
        metadata: {
          ...bundle.scenarioDraftSet.metadata,
          provenance: {
            tokenSchema: { type: 'string' },
            credentialRef: 'secretRef:crm-test',
          },
        },
      },
    };
    await expect(parseWorkspaceBundle(JSON.stringify(safe))).resolves.toMatchObject({
      schemaVersion: 'bloge.visualAuthoringWorkspaceBundle.v1',
    });

    const unsafe: any = structuredClone(safe);
    unsafe.scenarioDraftSet.metadata.provenance = {
      apiToken: 'Bearer raw-workspace-credential',
    };
    await expect(parseWorkspaceBundle(JSON.stringify(unsafe))).rejects.toBeInstanceOf(
      WorkspaceBundleError,
    );
    await expect(parseWorkspaceBundle(JSON.stringify(unsafe))).rejects.toMatchObject({
      code: 'WORKSPACE_RAW_SECRET_FORBIDDEN',
      paths: ['/scenarioDraftSet/metadata/provenance/apiToken'],
    });
  });

  it('rejects malformed nested assets with a stable diagnostic before projection', async () => {
    const bundle = await bundleFixture();
    const malformed: any = structuredClone(bundle);
    malformed.graphDraft.nodes = {};

    await expect(parseWorkspaceBundle(JSON.stringify(malformed))).rejects.toMatchObject({
      code: 'WORKSPACE_SHAPE_INVALID',
    });

    malformed.graphDraft.nodes = bundle.graphDraft.nodes;
    malformed.classification = 'TOP_SECRET';
    malformed.scenarioDraftSet.metadata.classification = 'TOP_SECRET';
    await expect(parseWorkspaceBundle(JSON.stringify(malformed))).rejects.toMatchObject({
      code: 'WORKSPACE_SHAPE_INVALID',
    });
  });
});

async function bundleFixture() {
  const graph = graphDraft();
  const targetFingerprint = await sha256Fingerprint(graph);
  const contract = contractDraftFromGraphDraft(graph, targetFingerprint);
  const contractFingerprint = await sha256Fingerprint(contract);
  const scenarios = scenarioDraftSetFromCanvas(
    contract.target,
    contractFingerprint,
    graph,
    nodes(),
    [],
  );
  return createWorkspaceBundle(graph, contract, contractFingerprint, scenarios, null);
}

function sha(seed: string): string {
  return `sha256:${seed.repeat(64).slice(0, 64)}`;
}
