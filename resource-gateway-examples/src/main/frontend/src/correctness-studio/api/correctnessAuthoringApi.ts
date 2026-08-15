import { exchangeCorrectnessApi } from '../../api';
import type { CorrectnessApiEnvelope, ExactAssetRef } from '../model/domain';
import type {
  AssertionCompilationReport,
  AssertionSet,
  BusinessOracle,
  CorrectnessCompilationCoordinate,
  CorrectnessCompilationReport,
  CoverageInventory,
  FixtureAssetDescriptor,
  FixtureMaterial,
  FixtureMaterialReceipt,
  ScenarioDraftSetV2,
  StoredAssertionSet,
  StoredBusinessOracle,
  StoredCoverageInventory,
  StoredFixtureAsset,
  StoredScenarioDraftSetV2,
} from '../model/authoring';

export async function fetchCoverageInventory(
  ref: ExactAssetRef,
): Promise<CorrectnessApiEnvelope<StoredCoverageInventory>> {
  return readAsset('/api/visual/coverage-inventories', ref);
}

export async function saveCoverageInventory(
  inventory: CoverageInventory,
): Promise<CorrectnessApiEnvelope<StoredCoverageInventory>> {
  return writeAsset(
    `/api/visual/coverage-inventories/${encodeURIComponent(inventory.inventoryId)}`,
    inventory.revision,
    inventory,
  );
}

export async function freezeCoverageInventory(
  inventoryId: string,
  revision: number,
  comment: string,
  idempotencyKey: string,
): Promise<CorrectnessApiEnvelope<{ stored: StoredCoverageInventory; replayed: boolean }>> {
  return exchangeCorrectnessApi(
    `/api/visual/coverage-inventories/${encodeURIComponent(inventoryId)}:freeze`,
    'CORRECTNESS_REVIEW',
    { method: 'POST', ifMatch: revision, idempotencyKey, body: { comment } },
  );
}

export async function fetchBusinessOracle(
  ref: ExactAssetRef,
): Promise<CorrectnessApiEnvelope<StoredBusinessOracle>> {
  return readAsset('/api/visual/oracles', ref);
}

export async function saveBusinessOracle(
  oracle: BusinessOracle,
): Promise<CorrectnessApiEnvelope<StoredBusinessOracle>> {
  return writeAsset(
    `/api/visual/oracles/${encodeURIComponent(oracle.oracleId)}`,
    oracle.revision,
    oracle,
  );
}

export async function approveBusinessOracle(
  oracleId: string,
  revision: number,
  comment: string,
  idempotencyKey: string,
): Promise<CorrectnessApiEnvelope<{ stored: StoredBusinessOracle; replayed: boolean }>> {
  return exchangeCorrectnessApi(
    `/api/visual/oracles/${encodeURIComponent(oracleId)}:approve`,
    'CORRECTNESS_REVIEW',
    { method: 'POST', ifMatch: revision, idempotencyKey, body: { comment } },
  );
}

export async function fetchAssertionSet(
  ref: ExactAssetRef,
): Promise<CorrectnessApiEnvelope<StoredAssertionSet>> {
  return readAsset('/api/visual/assertion-sets', ref);
}

export async function previewAssertionSet(
  assertionSet: AssertionSet,
): Promise<CorrectnessApiEnvelope<AssertionCompilationReport>> {
  return exchangeCorrectnessApi(
    '/api/visual/assertion-sets:compile-preview',
    'CORRECTNESS_WRITE',
    { method: 'POST', body: assertionSet },
  );
}

export async function saveAssertionSet(
  assertionSet: AssertionSet,
): Promise<CorrectnessApiEnvelope<StoredAssertionSet>> {
  return writeAsset(
    `/api/visual/assertion-sets/${encodeURIComponent(assertionSet.assertionSetId)}`,
    assertionSet.revision,
    assertionSet,
  );
}

export async function validateAssertionSet(
  assertionSetId: string,
  revision: number,
): Promise<CorrectnessApiEnvelope<{ stored: StoredAssertionSet; report: AssertionCompilationReport }>> {
  return exchangeCorrectnessApi(
    `/api/visual/assertion-sets/${encodeURIComponent(assertionSetId)}:validate`,
    'CORRECTNESS_WRITE',
    { method: 'POST', ifMatch: revision },
  );
}

export async function fetchScenarioDraftSet(
  ref: ExactAssetRef,
): Promise<CorrectnessApiEnvelope<StoredScenarioDraftSetV2>> {
  return readAsset('/api/visual/scenario-draft-sets-v2', ref);
}

export async function saveScenarioDraftSet(
  draftSet: ScenarioDraftSetV2,
): Promise<CorrectnessApiEnvelope<StoredScenarioDraftSetV2>> {
  return writeAsset(
    `/api/visual/scenario-draft-sets-v2/${encodeURIComponent(draftSet.scenarioDraftSetId)}`,
    draftSet.revision,
    draftSet,
  );
}

export async function markScenarioReviewReady(
  draftSetId: string,
  scenarioId: string,
  revision: number,
): Promise<CorrectnessApiEnvelope<{ stored: StoredScenarioDraftSetV2; scenarioId: string }>> {
  return exchangeCorrectnessApi(
    `/api/visual/scenario-draft-sets-v2/${encodeURIComponent(draftSetId)}`
      + `/cases/${encodeURIComponent(scenarioId)}:review-ready`,
    'CORRECTNESS_WRITE',
    { method: 'POST', ifMatch: revision },
  );
}

export async function approveScenario(
  draftSetId: string,
  scenarioId: string,
  revision: number,
  comment: string,
  idempotencyKey: string,
): Promise<CorrectnessApiEnvelope<{ stored: StoredScenarioDraftSetV2; scenarioId: string }>> {
  return exchangeCorrectnessApi(
    `/api/visual/scenario-draft-sets-v2/${encodeURIComponent(draftSetId)}`
      + `/cases/${encodeURIComponent(scenarioId)}:approve`,
    'CORRECTNESS_REVIEW',
    { method: 'POST', ifMatch: revision, idempotencyKey, body: { comment } },
  );
}

export async function fetchFixtureAsset(
  ref: ExactAssetRef,
): Promise<CorrectnessApiEnvelope<StoredFixtureAsset>> {
  return readAsset('/api/visual/fixture-assets', ref);
}

export async function saveFixtureAsset(
  descriptor: FixtureAssetDescriptor,
): Promise<CorrectnessApiEnvelope<StoredFixtureAsset>> {
  return writeAsset(
    `/api/visual/fixture-assets/${encodeURIComponent(descriptor.fixtureAssetId)}`,
    descriptor.revision,
    descriptor,
  );
}

export async function transitionFixtureAsset(
  fixtureAssetId: string,
  revision: number,
  transition: 'review-ready' | 'activate' | 'revoke',
): Promise<CorrectnessApiEnvelope<StoredFixtureAsset>> {
  return exchangeCorrectnessApi(
    `/api/visual/fixture-assets/${encodeURIComponent(fixtureAssetId)}:${transition}`,
    transition === 'review-ready' ? 'CORRECTNESS_WRITE' : 'CORRECTNESS_REVIEW',
    { method: 'POST', ifMatch: revision },
  );
}

export async function approveFixtureAsset(
  fixtureAssetId: string,
  revision: number,
  comment: string,
  idempotencyKey: string,
): Promise<CorrectnessApiEnvelope<{ stored: StoredFixtureAsset; replayed: boolean }>> {
  return exchangeCorrectnessApi(
    `/api/visual/fixture-assets/${encodeURIComponent(fixtureAssetId)}:approve`,
    'CORRECTNESS_REVIEW',
    { method: 'POST', ifMatch: revision, idempotencyKey, body: { comment } },
  );
}

export async function fetchFixtureMaterial(
  fixtureAssetId: string,
  materialRef: ExactAssetRef,
): Promise<FixtureMaterial> {
  const query = new URLSearchParams({
    revision: String(materialRef.revision),
    fingerprint: materialRef.fingerprint,
  });
  return exchangeCorrectnessApi(
    `/api/visual/fixture-materials/${encodeURIComponent(fixtureAssetId)}?${query}`,
    'CORRECTNESS_FIXTURE_MATERIAL_READ',
  );
}

export async function writeFixtureMaterial(
  request: Record<string, unknown>,
): Promise<FixtureMaterialReceipt> {
  return exchangeCorrectnessApi('/api/visual/fixture-materials',
    'CORRECTNESS_FIXTURE_MATERIAL_WRITE', { method: 'POST', body: request });
}

export async function previewCorrectnessCompilation(
  coordinate: CorrectnessCompilationCoordinate,
  idempotencyKey: string,
): Promise<CorrectnessApiEnvelope<CorrectnessCompilationReport>> {
  return exchangeCorrectnessApi('/api/visual/correctness-publications:compile-preview',
    'TEST_SCENARIO_PUBLISH', { method: 'POST', idempotencyKey, body: coordinate });
}

export async function publishCorrectness(
  coordinate: CorrectnessCompilationCoordinate,
  idempotencyKey: string,
): Promise<CorrectnessApiEnvelope<Record<string, unknown>>> {
  return exchangeCorrectnessApi('/api/visual/correctness-publications',
    'TEST_SCENARIO_PUBLISH', { method: 'POST', idempotencyKey, body: coordinate });
}

async function readAsset<T>(
  root: string,
  ref: ExactAssetRef,
): Promise<CorrectnessApiEnvelope<T>> {
  const query = new URLSearchParams({ revision: String(ref.revision) });
  return exchangeCorrectnessApi(
    `${root}/${encodeURIComponent(ref.id)}?${query}`,
    'CORRECTNESS_READ',
  );
}

async function writeAsset<T>(
  path: string,
  revision: number,
  candidate: unknown,
): Promise<CorrectnessApiEnvelope<T>> {
  return exchangeCorrectnessApi(path, 'CORRECTNESS_WRITE', {
    method: 'PUT', ifMatch: revision, body: candidate,
  });
}
