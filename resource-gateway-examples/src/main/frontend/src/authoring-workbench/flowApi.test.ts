import { describe, expect, it, vi } from 'vitest';

import {
  listFlowDraftFixtures,
  publishFlow,
  readFixtureSet,
  readFlow,
  saveFixtureSet,
  saveFlow,
  saveFlowFixture,
  simulateFixtureSetCase,
} from './flowApi';
import type { FixtureSetCommand, ReusableFlowCommand } from './flowModel';

describe('reusable Flow object transport', () => {
  it('reads and saves one Flow under authenticated strong-CAS transport', async () => {
    const transport = vi.fn()
      .mockResolvedValueOnce(response({ schemaVersion: 'bloge.reusableFlowDraft.v1' }, { ETag: '"flow-r1"' }))
      .mockResolvedValueOnce(response({ schemaVersion: 'bloge.reusableFlowSaveReceipt.v1' }, {
        ETag: '"flow-r2"', 'Idempotency-Replayed': 'false',
      }));

    await readFlow('customer-overview', transport);
    await saveFlow('customer-overview', {} as ReusableFlowCommand, '"flow-r1"', 'save-flow-1', transport);

    expect(new Headers(transport.mock.calls[0][1]?.headers).get('X-Purpose')).toBe('API_RESOURCE_AUTHORING');
    const headers = new Headers(transport.mock.calls[1][1]?.headers);
    expect(headers.get('If-Match')).toBe('"flow-r1"');
    expect(headers.get('Idempotency-Key')).toBe('save-flow-1');
  });

  it('saves, discovers, and publishes exact Flow authorities', async () => {
    const transport = vi.fn()
      .mockResolvedValueOnce(response({ schemaVersion: 'bloge.fixtureSetSaveReceipt.v1' }, { ETag: '"fixture-r1"' }))
      .mockResolvedValueOnce(response([]))
      .mockResolvedValueOnce(response({ schemaVersion: 'bloge.reusableFlowPublishReceipt.v1' }));
    const subject = { kind: 'FLOW_DRAFT' as const, draftId: 'draft-1', revision: 2, fingerprint: hash('a') };

    await saveFlowFixture('fixture-1', {} as FixtureSetCommand, null, 'fixture-key', transport);
    await listFlowDraftFixtures(subject, transport);
    await publishFlow('flow-1', subject, 'publish-key', transport);

    expect(new Headers(transport.mock.calls[0][1]?.headers).get('If-None-Match')).toBe('*');
    expect(transport.mock.calls[1][0]).toContain('subjectKind=FLOW_DRAFT');
    expect(JSON.parse(String(transport.mock.calls[2][1]?.body))).toEqual({
      schemaVersion: 'bloge.reusableFlowPublishCommand.v1', source: subject,
    });
  });

  it('reads, updates, and simulates an independently addressed Fixture', async () => {
    const transport = vi.fn()
      .mockResolvedValueOnce(response({ schemaVersion: 'bloge.fixtureSet.v1' }, { ETag: '"fixture-r1"' }))
      .mockResolvedValueOnce(response({ schemaVersion: 'bloge.fixtureSetSaveReceipt.v1' }, {
        ETag: '"fixture-r2"', 'Idempotency-Replayed': 'false',
      }))
      .mockResolvedValueOnce(response({ schemaVersion: 'bloge.simulationRun.v1' }));

    await readFixtureSet('overview.default', 1, transport);
    await saveFixtureSet('overview.default', {} as FixtureSetCommand, '"fixture-r1"', 'save-fixture-1', transport);
    await simulateFixtureSetCase('overview.default', 2, 'default', 'run-fixture-2', transport);

    expect(transport.mock.calls[0][0]).toBe('/api/authoring/fixture-sets/overview.default?revision=1');
    expect(new Headers(transport.mock.calls[1][1]?.headers).get('If-Match')).toBe('"fixture-r1"');
    expect(JSON.parse(String(transport.mock.calls[2][1]?.body))).toEqual(expect.objectContaining({
      source: { kind: 'FIXTURE_CASE', fixtureSetId: 'overview.default', revision: 2, caseId: 'default' },
      executionPolicy: { externalReads: { kind: 'DENY' }, externalWrites: { kind: 'DENY' } },
    }));
  });

  it('reads a parent-governed Fixture without inventing an editable validator', async () => {
    const transport = vi.fn().mockResolvedValue(response({
      schemaVersion: 'bloge.fixtureSet.v1',
      subject: { kind: 'API_RESOURCE', resourceId: 'customer.get', revision: 1, fingerprint: hash('a') },
    }));

    await expect(readFixtureSet('customer.get.default', undefined, transport)).resolves.toMatchObject({
      strongEtag: null,
      value: { subject: { kind: 'API_RESOURCE' } },
    });
  });
});

function response(body: unknown, headers: Record<string, string> = {}, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });
}

function hash(value: string): string { return `sha256:${value.repeat(64)}`; }
