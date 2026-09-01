import { describe, expect, it, vi } from 'vitest';

import {
  listFlowFixtures,
  publishFlow,
  readFixtureSet,
  reviewFixtureSet,
  readFlow,
  readLatestFlowVersion,
  readLegacyReusableFlowPreview,
  saveFixtureSet,
  shareFixtureSet,
  saveFlow,
  saveFlowFixture,
  simulateFixtureSetCase,
} from './flowApi';
import type { FixtureReviewCommand, FixtureSetCommand, FixtureShareCommand, ReusableFlowCommand } from './flowModel';

describe('reusable Flow object transport', () => {
  it('reads one exact fixture-free legacy Flow preview without mutation headers', async () => {
    const transport = vi.fn().mockResolvedValue(response({
      schemaVersion: 'bloge.legacyReusableFlowReauthorPreview.v1',
      source: { kind: 'REUSABLE_FLOW_VERSION', sourceId: 'published-flow', sourceRevision: 4 },
      suggestedFlowId: 'customer-tool', suggestedFlow: {}, fixtureReferences: 1, diagnostics: [],
    }));

    await readLegacyReusableFlowPreview('REUSABLE_FLOW_VERSION', 'published-flow', 4, transport);

    expect(transport.mock.calls[0][0]).toBe(
      '/api/authoring/migrations/legacy-assets/flows/REUSABLE_FLOW_VERSION/published-flow:preview?revision=4');
    const headers = new Headers(transport.mock.calls[0][1]?.headers);
    expect(headers.get('X-Purpose')).toBe('API_RESOURCE_AUTHORING');
    expect(headers.get('Idempotency-Key')).toBeNull();
    expect(headers.get('If-Match')).toBeNull();
  });
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
    await listFlowFixtures(subject, transport);
    await publishFlow('flow-1', subject, 'publish-key', transport);

    expect(new Headers(transport.mock.calls[0][1]?.headers).get('If-None-Match')).toBe('*');
    expect(transport.mock.calls[1][0]).toContain('subjectKind=FLOW_DRAFT');
    expect(JSON.parse(String(transport.mock.calls[2][1]?.body))).toEqual({
      schemaVersion: 'bloge.reusableFlowPublishCommand.v1', source: subject,
    });
  });

  it('reads the latest immutable version and lists its exact Fixture Subject', async () => {
    const transport = vi.fn()
      .mockResolvedValueOnce(response({
        schemaVersion: 'bloge.reusableFlowVersion.v1', publicationId: 'flow-v', revision: 2,
      }))
      .mockResolvedValueOnce(response([]));
    const subject = {
      kind: 'FLOW_VERSION' as const, publicationId: 'flow-v', revision: 2, fingerprint: hash('b'),
    };

    await readLatestFlowVersion('flow 1', transport);
    await listFlowFixtures(subject, transport);

    expect(transport.mock.calls[0][0]).toBe('/api/authoring/flows/flow%201/versions/latest');
    expect(transport.mock.calls[1][0]).toContain('subjectKind=FLOW_VERSION');
    expect(transport.mock.calls[1][0]).toContain('subjectId=flow-v');
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

  it('submits one exact private Fixture revision under the protected-material purpose', async () => {
    const transport = vi.fn().mockResolvedValue(response({
      schemaVersion: 'bloge.fixtureShareReceipt.v1', fixtureSetId: 'overview.default',
      derivedFromRevision: 1, revision: 2, fingerprint: hash('c'),
      status: 'SHARING_PENDING', statusRevision: 2, reviewRequestId: 'review-overview-r2',
    }, { ETag: '"fixture-r2"', 'Idempotency-Replayed': 'false' }, 202));
    const command: FixtureShareCommand = {
      schemaVersion: 'bloge.fixtureShareCommand.v1',
      source: {
        fixtureSetId: 'overview.default', revision: 1,
        fingerprint: hash('b'), statusRevision: 1,
      },
      policy: {
        classification: 'CONFIDENTIAL', retentionDays: 30,
        redaction: { profileVersion: 'default-v1', paths: ['/email'] },
      },
    };

    await shareFixtureSet('overview.default', command, '"fixture-r1"', 'share-fixture-1', transport);

    expect(transport.mock.calls[0][0]).toBe('/api/authoring/fixture-sets/overview.default:share');
    const headers = new Headers(transport.mock.calls[0][1]?.headers);
    expect(headers.get('X-Purpose')).toBe('CORRECTNESS_FIXTURE_MATERIAL_WRITE');
    expect(headers.get('If-Match')).toBe('"fixture-r1"');
    expect(headers.get('Idempotency-Key')).toBe('share-fixture-1');
    expect(JSON.parse(String(transport.mock.calls[0][1]?.body))).toEqual(command);
  });

  it('reviews one exact pending Fixture under the independent-review purpose', async () => {
    const transport = vi.fn().mockResolvedValue(response({
      schemaVersion: 'bloge.fixtureReviewReceipt.v1', reviewRequestId: 'review-overview-r2',
      fixtureSetId: 'overview.default', derivedFromRevision: 2, revision: 3,
      fingerprint: hash('d'), status: 'TEAM_AVAILABLE', statusRevision: 3, activatedAssetCount: 1,
    }, { ETag: '"fixture-r3"', 'Idempotency-Replayed': 'false' }));
    const command: FixtureReviewCommand = {
      schemaVersion: 'bloge.fixtureReviewCommand.v1',
      source: {
        reviewRequestId: 'review-overview-r2', fixtureSetId: 'overview.default',
        revision: 2, fingerprint: hash('c'), statusRevision: 2,
      },
      attestations: {
        redactionReviewed: true, schemaValid: true, redactionVerified: true,
        comment: 'Independent reviewer verified protected material',
      },
    };

    await reviewFixtureSet('overview.default', command, '"fixture-r2"', 'review-fixture-1', transport);

    expect(transport.mock.calls[0][0]).toBe('/api/authoring/fixture-sets/overview.default:review');
    const headers = new Headers(transport.mock.calls[0][1]?.headers);
    expect(headers.get('X-Purpose')).toBe('CORRECTNESS_REVIEW');
    expect(headers.get('If-Match')).toBe('"fixture-r2"');
    expect(headers.get('Idempotency-Key')).toBe('review-fixture-1');
    expect(JSON.parse(String(transport.mock.calls[0][1]?.body))).toEqual(command);
  });
});

function response(body: unknown, headers: Record<string, string> = {}, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });
}

function hash(value: string): string { return `sha256:${value.repeat(64)}`; }
