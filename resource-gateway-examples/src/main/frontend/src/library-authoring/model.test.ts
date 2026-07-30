import { describe, expect, it } from 'vitest';

import {
  addAsset,
  assetSelectionFromPath,
  compactFieldRows,
  compactFieldsFromRows,
  createQuickLibraryDocument,
  nestedCompactFields,
  renameAsset,
} from './model';

describe('library authoring model', () => {
  it('creates a complete pure-operator starter without raw schema editing', () => {
    const document = createQuickLibraryDocument('support-tools', 'support-team');

    expect(document.library).toMatchObject({
      id: 'support-tools',
      name: 'Support Tools',
      owner: 'support-team',
    });
    expect(Object.keys(document.operators ?? {})).toEqual(['support:transform']);
    expect(document.operators?.['support:transform']).toMatchObject({
      archetype: 'pure',
      input: { value: 'string' },
      output: { result: 'string' },
    });
  });

  it('keeps optional field semantics through table projections', () => {
    const rows = compactFieldRows({
      id: 'string',
      'profile?': 'CustomerProfile',
    });

    expect(rows).toMatchObject([
      { name: 'id', type: 'string', required: true },
      { name: 'profile', type: 'CustomerProfile', required: false },
    ]);
    expect(compactFieldsFromRows(rows)).toEqual({
      id: 'string',
      'profile?': 'CustomerProfile',
    });
  });

  it('preserves inferred structured fields through compact edits', () => {
    const structured = {
      fields: {
        createdAt: 'datetime',
        metadata: {
          fields: {
            'channel?': 'string',
          },
          additionalProperties: true,
        },
      },
      additionalProperties: true,
    };
    const rows = compactFieldRows({
      request: structured,
      'tenantId?': 'string',
    });

    expect(rows[0]).toMatchObject({ name: 'request', type: 'object', required: true });
    expect(compactFieldsFromRows([
      rows[0],
      { ...rows[1], required: true },
    ])).toEqual({
      request: structured,
      tenantId: 'string',
    });
    expect(nestedCompactFields(rows[0].sourceValue)).toEqual([
      { path: 'createdAt', name: 'createdAt', type: 'datetime', required: true, depth: 0 },
      { path: 'metadata', name: 'metadata', type: 'object', required: true, depth: 0 },
      {
        path: 'metadata.channel',
        name: 'channel',
        type: 'string',
        required: false,
        depth: 1,
      },
    ]);
  });

  it('uses escaped source paths to select the right builder asset', () => {
    expect(assetSelectionFromPath('/operators/payments~1authorize/input/card')).toEqual({
      kind: 'operator',
      key: 'payments/authorize',
    });
    expect(assetSelectionFromPath('/library/owner')).toEqual({ kind: 'library', key: '' });
  });

  it('adds and renames stable assets without overwriting an existing key', () => {
    const base = createQuickLibraryDocument('support-tools', 'support-team');
    const added = addAsset(base, 'function');
    const renamed = renameAsset(added.document, added.selection, 'support.normalizeText');
    const duplicate = renameAsset(renamed.document, renamed.selection, 'support.normalizeText');

    expect(renamed.selection).toEqual({ kind: 'function', key: 'support.normalizeText' });
    expect(Object.keys(renamed.document.functions ?? {})).toEqual(['support.normalizeText']);
    expect(duplicate).toEqual(renamed);
  });
});
