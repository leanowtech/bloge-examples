import { afterEach, describe, expect, it, vi } from 'vitest';

import { resetBlogeApiTransport, setBlogeApiTransport } from '../api';
import {
  activateGovernedFixture,
  fetchGovernedFixtureAssets,
  promoteGraphNodeFixture,
  resetFixtureAssetTransport,
  reviewReadyGovernedFixture,
  verifyGovernedFixture,
  approveGovernedFixture,
  setFixtureAssetTransport,
} from './api';

afterEach(() => {
  resetFixtureAssetTransport();
  resetBlogeApiTransport();
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
      return new Response(JSON.stringify({ data: [
        {
          fixtureAssetId: 'zeta', revision: 2, name: 'Zulu', lifecycle: 'ACTIVE',
          schemaRef: { fingerprint: 'sha256:z' }, usageCount: 4,
        },
        {
          fixtureAssetId: 'draft', revision: 1, name: 'Draft', lifecycle: 'DRAFT',
          schemaRef: { fingerprint: 'sha256:d' }, usageCount: 0,
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
    setFixtureAssetTransport(async () => new Response('', { status: 405 }));
    await expect(fetchGovernedFixtureAssets()).resolves.toEqual([]);
  });

  it('passes the selected operator and preserves server compatibility metadata', async () => {
    setFixtureAssetTransport(async (input) => {
      expect(String(input)).toBe('/api/visual/fixture-assets?operatorRef=resource%3Apayment');
      return new Response(JSON.stringify({ data: [{
        fixtureAssetId: 'payment', revision: 3, name: 'Payment', lifecycle: 'ACTIVE',
        schemaRef: { fingerprint: 'sha256:s' }, usageCount: 6,
        compatibleWithOperatorRef: true, currentSchemaFingerprint: 'sha256:current',
      }] }), { status: 200 });
    });
    await expect(fetchGovernedFixtureAssets('resource:payment')).resolves.toMatchObject([{
      compatible: true, currentSchemaFingerprint: 'sha256:current', usageCount: 6,
    }]);
  });

  it('fails closed for malformed successful responses', async () => {
    setFixtureAssetTransport(async () => new Response(JSON.stringify({ data: { nope: true } }), { status: 200 }));
    await expect(fetchGovernedFixtureAssets()).rejects.toThrow('invalid data payload');
    setFixtureAssetTransport(async () => new Response(JSON.stringify({ data: [{}] }), { status: 200 }));
    await expect(fetchGovernedFixtureAssets()).rejects.toThrow('invalid summary');
  });

  it('uses the existing fixture lifecycle endpoints with CAS and reviewer idempotency', async () => {
    const requests: Array<{ input: string; init?: RequestInit }> = [];
    setBlogeApiTransport(async (input, init) => {
      requests.push({ input: String(input), init });
      const path = String(input);
      const lifecycle = path.endsWith(':review-ready') || path.endsWith(':verify-review')
        ? 'PROPOSED' : path.endsWith(':approve') ? 'APPROVED' : 'ACTIVE';
      const descriptor = {
        fixtureAssetId: 'profile.v1', revision: lifecycle === 'PROPOSED' ? 3 : 4, lifecycle,
      };
      return new Response(JSON.stringify({ data: {
        ...(lifecycle === 'APPROVED' ? { stored: { descriptor } } : { descriptor }),
      } }), { status: 200 });
    });

    await expect(reviewReadyGovernedFixture('profile.v1', 2)).resolves.toMatchObject({
      revision: 3, lifecycle: 'PROPOSED',
    });
    await expect(verifyGovernedFixture('profile.v1', 3, {
      redactionReviewed: true,
      redactionVerified: true,
      comment: 'Redaction reviewed',
    })).resolves.toMatchObject({ revision: 3, lifecycle: 'PROPOSED' });
    await expect(approveGovernedFixture('profile.v1', 3, 'Reviewed', 'approve:profile.v1:3'))
      .resolves.toMatchObject({ revision: 4, lifecycle: 'APPROVED' });
    await expect(activateGovernedFixture('profile.v1', 4)).resolves.toMatchObject({
      revision: 4, lifecycle: 'ACTIVE',
    });

    expect(requests.map((request) => request.input)).toEqual([
      '/api/visual/fixture-assets/profile.v1:review-ready',
      '/api/visual/fixture-assets/profile.v1:verify-review',
      '/api/visual/fixture-assets/profile.v1:approve',
      '/api/visual/fixture-assets/profile.v1:activate',
    ]);
    expect(new Headers(requests[0]?.init?.headers).get('If-Match')).toBe('2');
    expect(new Headers(requests[1]?.init?.headers).get('If-Match')).toBe('3');
    expect(JSON.parse(String(requests[1]?.init?.body))).toEqual({
      redactionReviewed: true, redactionVerified: true, comment: 'Redaction reviewed',
    });
    expect(new Headers(requests[2]?.init?.headers).get('If-Match')).toBe('3');
    expect(new Headers(requests[2]?.init?.headers).get('Idempotency-Key')).toBe('approve:profile.v1:3');
    expect(new Headers(requests[3]?.init?.headers).get('If-Match')).toBe('4');
  });
});
