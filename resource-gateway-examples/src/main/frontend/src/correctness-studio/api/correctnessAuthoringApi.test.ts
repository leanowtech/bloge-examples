import { afterEach, describe, expect, it } from 'vitest';

import { resetBlogeApiTransport, setBlogeApiTransport } from '../../api';
import type { CoverageInventory } from '../model/authoring';
import {
  fetchCoverageInventory,
  fetchFixtureMaterial,
  freezeCoverageInventory,
  previewCorrectnessCompilation,
  saveCoverageInventory,
} from './correctnessAuthoringApi';

describe('correctnessAuthoringApi', () => {
  afterEach(() => resetBlogeApiTransport());

  it('loads exact authoring revisions with the read purpose', async () => {
    const requests = captureRequests();

    await fetchCoverageInventory(exactRef('COVERAGE_INVENTORY', 'loan inventory', 4, 'a'));

    expect(requests.values[0]?.input)
      .toBe('/api/visual/coverage-inventories/loan%20inventory?revision=4');
    expect(headers(requests.values[0]?.init).get('X-Purpose')).toBe('CORRECTNESS_READ');
  });

  it('preserves CAS and idempotency headers for author and reviewer commands', async () => {
    const requests = captureRequests();
    const inventory = {
      inventoryId: 'loan-inventory', revision: 7,
    } as CoverageInventory;

    await saveCoverageInventory(inventory);
    await freezeCoverageInventory('loan-inventory', 8, 'Reviewed', 'freeze:loan:8');

    expect(requests.values[0]).toMatchObject({
      input: '/api/visual/coverage-inventories/loan-inventory',
      init: { method: 'PUT', body: JSON.stringify(inventory) },
    });
    expect(headers(requests.values[0]?.init).get('If-Match')).toBe('7');
    expect(headers(requests.values[0]?.init).get('X-Purpose')).toBe('CORRECTNESS_WRITE');
    expect(headers(requests.values[1]?.init).get('If-Match')).toBe('8');
    expect(headers(requests.values[1]?.init).get('Idempotency-Key')).toBe('freeze:loan:8');
    expect(headers(requests.values[1]?.init).get('X-Purpose')).toBe('CORRECTNESS_REVIEW');
  });

  it('isolates Fixture payload access from metadata and publication authority', async () => {
    const requests = captureRequests();
    const material = exactRef('FIXTURE_MATERIAL', 'fixture-a', 3, 'b');

    await fetchFixtureMaterial('fixture-a', material);
    await previewCorrectnessCompilation({
      definitionRef: exactRef('CORRECTNESS_DEFINITION', 'definition-a', 1, 'a'),
      inventoryRef: exactRef('COVERAGE_INVENTORY', 'inventory-a', 2, 'b'),
      scenarioDraftSetRef: exactRef('SCENARIO_DRAFT_SET', 'scenarios-a', 3, 'c'),
      oracleRefs: [exactRef('BUSINESS_ORACLE', 'oracle-a', 4, 'd')],
      assertionSetRefs: [exactRef('ASSERTION_SET', 'assertions-a', 5, 'e')],
      fixtureAssetRefs: [exactRef('FIXTURE_ASSET', 'fixture-a', 2, 'f')],
      target: { kind: 'GRAPH', id: 'loan', revision: 9, fingerprint: fp('9') },
    }, 'compile:loan:9');

    expect(headers(requests.values[0]?.init).get('X-Purpose'))
      .toBe('CORRECTNESS_FIXTURE_MATERIAL_READ');
    expect(requests.values[0]?.input).toContain(`fingerprint=${encodeURIComponent(fp('b'))}`);
    expect(headers(requests.values[1]?.init).get('X-Purpose')).toBe('TEST_SCENARIO_PUBLISH');
    expect(headers(requests.values[1]?.init).get('Idempotency-Key')).toBe('compile:loan:9');
  });
});

function captureRequests() {
  const values: Array<{ input: string; init?: RequestInit }> = [];
  setBlogeApiTransport(async (input, init) => {
    values.push({ input: String(input), init });
    return json({ data: {} });
  });
  return { values };
}

function json(value: unknown): Response {
  return new Response(JSON.stringify(value), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

function headers(init?: RequestInit): Headers {
  return new Headers(init?.headers);
}

function exactRef(kind: string, id: string, revision: number, seed: string) {
  return { kind, id, revision, fingerprint: fp(seed) };
}

function fp(seed: string): string {
  return `sha256:${seed.repeat(64)}`;
}
