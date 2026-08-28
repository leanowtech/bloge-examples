import { describe, expect, it } from 'vitest';

import {
  fixtureSchemaStale,
  governedRefFromReceipt,
  promoteRequestFrom,
  provenanceOf,
} from './graphNodeFixtureModel';
import type { GraphNodeFixtureClassification } from './graphNodeFixtureModel';

describe('promoteRequestFrom', () => {
  it('builds the exact backend wire version and normalizes governance input deterministically', () => {
    const actual = promoteRequestFrom({
      fixtureAssetId: ' loan.profile ',
      classification: 'restricted' as GraphNodeFixtureClassification,
      retentionDays: 30,
      redactionPaths: ['/phone', '/email', '/phone'],
    });
    expect(actual).toEqual({
      schemaVersion: 'bloge.graphNodeFixturePromote.v1',
      fixtureId: 'loan.profile',
      classification: 'RESTRICTED',
      retentionDays: 30,
      redactionPaths: ['/email', '/phone'],
    });
  });

  it.each([
    ['id', { fixtureAssetId: '-bad', classification: 'PUBLIC', retentionDays: 7 }],
    ['classification', { fixtureAssetId: 'good', classification: 'SECRET', retentionDays: 7 }],
    ['retention-zero', { fixtureAssetId: 'good', classification: 'PUBLIC', retentionDays: 0 }],
    ['retention-over', { fixtureAssetId: 'good', classification: 'PUBLIC', retentionDays: 31 }],
    ['empty-redaction', {
      fixtureAssetId: 'good', classification: 'PUBLIC', retentionDays: 7,
      redactionPaths: ['   '],
    }],
    ['json-path-redaction', {
      fixtureAssetId: 'good', classification: 'PUBLIC', retentionDays: 7,
      redactionPaths: ['$.phone'],
    }],
  ])('rejects invalid %s', (_name, input) => {
    expect(() => promoteRequestFrom(input as Parameters<typeof promoteRequestFrom>[0])).toThrow(TypeError);
  });

  it('does not mutate caller-owned redaction paths', () => {
    const paths = ['/b', '/a'];
    promoteRequestFrom({ fixtureAssetId: 'good', classification: 'PUBLIC', retentionDays: 1, redactionPaths: paths });
    expect(paths).toEqual(['/b', '/a']);
  });
});

describe('provenanceOf', () => {
  it.each([
    [undefined, 'sample'],
    [{ output: null }, 'sample'],
    [{ output: { ok: true } }, 'sample'],
    [{ output: { ok: true }, expectedInput: {} }, 'pinned'],
    [{ output: { ok: true }, provenance: 'governed' }, 'sample'],
    [{ output: { ok: true }, governedRef: { fixtureAssetId: '', revision: 2, schemaFingerprint: 'sha256:f' } }, 'sample'],
    [{
      output: { ok: true },
      governedRef: { fixtureAssetId: 'f1', revision: 2, schemaFingerprint: 'sha256:f' },
    }, 'governed'],
  ] as const)('maps %j to %s', (fixture, expected) => {
    expect(provenanceOf(fixture)).toBe(expected);
  });
});

describe('governedRefFromReceipt', () => {
  const receipt = {
    fixtureAssetId: 'profile-v1',
    revision: 4,
    lifecycle: 'DRAFT',
    assetRef: { id: 'material-1', fingerprint: 'sha256:m' },
    schemaRef: { id: 'schema-applicant', revision: 3, fingerprint: 'sha256:s' },
    provenance: 'governed',
  };

  it('keeps only the exact coordinate and captures the owning node id', () => {
    expect(governedRefFromReceipt('node_1', receipt)).toEqual({
      nodeId: 'node_1',
      fixtureAssetId: 'profile-v1',
      revision: 4,
      schemaFingerprint: 'sha256:s',
    });
  });

  it.each([
    ['', receipt],
    ['node_1', { ...receipt, fixtureAssetId: '' }],
    ['node_1', { ...receipt, revision: 0 }],
    ['node_1', { ...receipt, lifecycle: 'ACTIVE' }],
    ['node_1', { ...receipt, provenance: 'sample' }],
    ['node_1', { ...receipt, schemaRef: {} }],
  ])('rejects an unverifiable receipt for node %j', (nodeId, value) => {
    expect(() => governedRefFromReceipt(nodeId, value as typeof receipt)).toThrow(TypeError);
  });
});

describe('fixtureSchemaStale', () => {
  const governed = {
    output: {},
    governedRef: { fixtureAssetId: 'f1', revision: 1, schemaFingerprint: 'sha256:old' },
  };

  it('warns only for a confirmed schema fingerprint change', () => {
    expect(fixtureSchemaStale(governed, 'sha256:old')).toBe(false);
    expect(fixtureSchemaStale(governed, 'sha256:new')).toBe(true);
  });

  it('never claims staleness without sufficient evidence', () => {
    expect(fixtureSchemaStale(governed)).toBe(false);
    expect(fixtureSchemaStale({ output: {} }, 'sha256:new')).toBe(false);
  });
});
