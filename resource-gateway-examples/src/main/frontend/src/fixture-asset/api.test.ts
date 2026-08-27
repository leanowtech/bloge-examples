import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  fetchGovernedFixtureAssets,
  promoteGraphNodeFixture,
  resetFixtureAssetTransport,
  setFixtureAssetTransport,
} from './api';

afterEach(() => {
  resetFixtureAssetTransport();
});

describe('fixture asset transport', () => {
  it('posts the exact payload-free graph node promotion command', async () => {
    const transport = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      expect(String(input)).toBe('/api/visual/graphs/draft%2F1/nodes/node%2F1/fixtures:promote');
      expect(init?.method).toBe('POST');
      expect(init?.headers).toEqual(expect.objectContaining({
        'Content-Type': 'application/json',
        'X-Purpose': 'CORRECTNESS_FIXTURE_MATERIAL_WRITE',
      }));
      expect(JSON.parse(String(init?.body))).toEqual({
        schemaVersion: 'bloge.graphNodeFixturePromote.v1',
        fixtureAssetId: 'profile.v1',
        classification: 'INTERNAL',
        retentionDays: 7,
        redactionPaths: ['/email'],
      });
      return new Response(JSON.stringify({
        fixtureAssetId: 'profile.v1',
        revision: 1,
        lifecycle: 'DRAFT',
        schemaRef: { fingerprint: 'sha256:s' },
        provenance: 'governed',
      }), { status: 201 });
    });
    setFixtureAssetTransport(transport);

    await expect(promoteGraphNodeFixture('draft/1', 'node/1', {
      schemaVersion: 'bloge.graphNodeFixturePromote.v1',
      fixtureAssetId: 'profile.v1',
      classification: 'INTERNAL',
      retentionDays: 7,
      redactionPaths: ['/email'],
    })).resolves.toMatchObject({ fixtureAssetId: 'profile.v1', revision: 1 });
  });

  it('does not copy a sensitive error body into the surfaced failure', async () => {
    setFixtureAssetTransport(async () => new Response(
      JSON.stringify({ payload: 'secret customer data', detail: 'internal detail' }),
      { status: 409 },
    ));
    await expect(promoteGraphNodeFixture('draft', 'node', {
      schemaVersion: 'bloge.graphNodeFixturePromote.v1',
      fixtureAssetId: 'profile.v1',
      classification: 'PUBLIC',
      retentionDays: 1,
      redactionPaths: [],
    })).rejects.toThrow('409');
    await expect(promoteGraphNodeFixture('draft', 'node', {
      schemaVersion: 'bloge.graphNodeFixturePromote.v1',
      fixtureAssetId: 'profile.v1',
      classification: 'PUBLIC',
      retentionDays: 1,
      redactionPaths: [],
    })).rejects.not.toThrow('secret customer data');
  });

  it('projects only ACTIVE metadata from the optional collection endpoint', async () => {
    setFixtureAssetTransport(async (input) => {
      expect(String(input)).toBe('/api/visual/fixture-assets');
      return new Response(JSON.stringify({ items: [
        {
          descriptor: {
            fixtureAssetId: 'zeta', revision: 2, name: 'Zulu', lifecycle: 'ACTIVE',
            schemaRef: { fingerprint: 'sha256:z' }, quality: { usageCount: 4 },
          },
        },
        {
          descriptor: {
            fixtureAssetId: 'draft', revision: 1, name: 'Draft', lifecycle: 'DRAFT',
            schemaRef: { fingerprint: 'sha256:d' }, quality: { usageCount: 0 },
          },
        },
      ] }), { status: 200 });
    });
    await expect(fetchGovernedFixtureAssets()).resolves.toEqual([{
      fixtureAssetId: 'zeta', revision: 2, name: 'Zulu', schemaFingerprint: 'sha256:z',
      usageCount: 4, lifecycle: 'ACTIVE',
    }]);
  });

  it('treats an unavailable legacy collection endpoint as an empty picker', async () => {
    setFixtureAssetTransport(async () => new Response('', { status: 404 }));
    await expect(fetchGovernedFixtureAssets()).resolves.toEqual([]);
  });
});
