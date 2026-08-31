import { describe, expect, it, vi } from 'vitest';

import { listApiResourceFixtures, readApiResource, saveApiResource, simulateFixtureCase } from './api';
import type { ApiResourceSaveCommand } from './model';

describe('simple authoring transport', () => {
  it('reads and saves with authenticated purpose, strong CAS, and idempotency', async () => {
    const transport = vi.fn()
      .mockResolvedValueOnce(response({ resourceId: 'profile' }, { ETag: '"r1"' }))
      .mockResolvedValueOnce(response({ schemaVersion: 'bloge.apiResourceSaveReceipt.v1' }, {
        ETag: '"r2"', 'Idempotency-Replayed': 'false',
      }));

    expect(await readApiResource('profile', undefined, transport)).toMatchObject({ strongEtag: '"r1"' });
    await saveApiResource('profile', {} as ApiResourceSaveCommand, '"r1"', 'save-1', transport);

    const readHeaders = new Headers(transport.mock.calls[0][1]?.headers);
    expect(readHeaders.get('X-Purpose')).toBe('API_RESOURCE_AUTHORING');
    const saveHeaders = new Headers(transport.mock.calls[1][1]?.headers);
    expect(saveHeaders.get('If-Match')).toBe('"r1"');
    expect(saveHeaders.get('If-None-Match')).toBeNull();
    expect(saveHeaders.get('Idempotency-Key')).toBe('save-1');
  });

  it('creates with If-None-Match and simulates the exact returned Fixture Case under deny-all policy', async () => {
    const transport = vi.fn()
      .mockResolvedValueOnce(response({ schemaVersion: 'bloge.apiResourceSaveReceipt.v1' }, { ETag: '"r1"' }))
      .mockResolvedValueOnce(response({ schemaVersion: 'bloge.simulationRun.v1', runId: 'run-1' }));

    await saveApiResource('profile', {} as ApiResourceSaveCommand, null, 'save-1', transport);
    await simulateFixtureCase('fixture-1', 2, 'default', 'run-key', transport);

    expect(new Headers(transport.mock.calls[0][1]?.headers).get('If-None-Match')).toBe('*');
    expect(JSON.parse(String(transport.mock.calls[1][1]?.body))).toEqual({
      schemaVersion: 'bloge.simulationRequest.v1',
      source: { kind: 'FIXTURE_CASE', fixtureSetId: 'fixture-1', revision: 2, caseId: 'default' },
      executionPolicy: { externalReads: { kind: 'DENY' }, externalWrites: { kind: 'DENY' } },
    });
  });

  it('lists private Fixtures by the exact committed Resource subject', async () => {
    const transport = vi.fn().mockResolvedValue(response([]));

    await listApiResourceFixtures({
      resourceId: 'customer profile', revision: 2, fingerprint: `sha256:${'a'.repeat(64)}`,
    }, transport);

    expect(transport.mock.calls[0][0]).toBe('/api/authoring/fixture-sets?subjectKind=API_RESOURCE&subjectId=customer+profile&subjectRevision=2&subjectFingerprint=sha256%3Aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa');
  });

  it('surfaces safe Problem Detail instead of raw transport bodies', async () => {
    const transport = vi.fn().mockResolvedValue(response({
      code: 'RG.AUTHORING.API_RESOURCE.CONNECTION_NOT_FOUND',
      detail: 'Choose an existing Connection.',
    }, {}, 404));

    await expect(readApiResource('profile', undefined, transport)).rejects.toThrow('Choose an existing Connection.');
  });
});

function response(body: unknown, headers: Record<string, string> = {}, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });
}
