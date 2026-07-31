import { describe, expect, it } from 'vitest';

import type {
  VisualAuthoringTestDraftGate,
  VisualLibraryAuthoringCompileResult,
  VisualLibraryAuthoringDraft,
} from '../types';
import {
  countLibraryHomeFilter,
  filterLibraryHomeItems,
  libraryRevisionHref,
  paginateLibraryHomeItems,
  projectLibraryHomeItems,
} from './libraryHomeModel';

describe('libraryHomeModel', () => {
  it('projects runtime, confirmation, test, ownership, actor, and exact revision state', () => {
    const owned = draft('owned', 4, 'alice', 'alice', '2026-07-30T09:00:00Z');
    const unresolved = draft('unresolved', 2, '', 'bob', '2026-06-01T09:00:00Z');
    const items = projectLibraryHomeItems(
      [unresolved, owned],
      {
        owned: {
          preview: preview([{
            assetKind: 'OPERATOR',
            assetRef: 'support:search',
            runtimeProfile: 'demo',
            state: 'DRIFTED',
            executableReady: false,
            declaredFingerprint: 'declared',
            runtimeFingerprint: 'runtime',
            reasonCode: 'RUNTIME_CHANGED',
            message: 'Runtime differs.',
          }], 1),
          testGate: gate('BLOCKED'),
        },
        unresolved: {
          preview: preview([], 0),
          testGate: gate('PASSED'),
        },
      },
      'alice',
      new Date('2026-07-31T00:00:00Z'),
    );

    expect(items.map((item) => item.draft.draftId)).toEqual(['owned', 'unresolved']);
    expect(items[0]).toMatchObject({
      mine: true,
      recent: true,
      statuses: [
        'NEEDS_CONFIRMATION',
        'RUNTIME_DRIFT',
        'TEST_GATE_INCOMPLETE',
      ],
      resumeHref: '/libraries/?draftId=owned&revision=4',
    });
    expect(items[1].statuses).toEqual(['OWNERSHIP_CONFLICT']);
    expect(countLibraryHomeFilter(items, 'runtime-drift')).toBe(1);
    expect(filterLibraryHomeItems(items, 'mine', 'support')).toHaveLength(1);
    expect(filterLibraryHomeItems(items, 'ownership-conflict', 'bob')).toHaveLength(1);
  });

  it('keeps search and pagination deterministic and clamps invalid pages', () => {
    const items = projectLibraryHomeItems(
      Array.from({ length: 11 }, (_, index) => draft(
        `library-${index}`,
        index + 1,
        'team',
        'alice',
        `2026-07-${String(index + 1).padStart(2, '0')}T09:00:00Z`,
      )),
      {},
      'alice',
      new Date('2026-07-31T00:00:00Z'),
    );
    const matching = filterLibraryHomeItems(items, 'all', 'library-');
    const page = paginateLibraryHomeItems(matching, 99, 8);

    expect(page.total).toBe(11);
    expect(page.page).toBe(2);
    expect(page.pageCount).toBe(2);
    expect(page.items).toHaveLength(3);
    expect(libraryRevisionHref('library with spaces', 0))
      .toBe('/libraries/?draftId=library+with+spaces&revision=1');
  });
});

function draft(
  id: string,
  revision: number,
  owner: string,
  savedBy: string,
  updatedAt: string,
): VisualLibraryAuthoringDraft {
  return {
    schemaVersion: 'bloge.visualLibraryAuthoringDraft.v1',
    draftId: id,
    revision,
    sourceMode: 'QUICK',
    document: {
      schemaVersion: 'bloge.visualLibraryAuthoring.v1',
      library: { id: `support-${id}`, name: `Support ${id}`, owner },
      operators: {},
      functions: {},
    },
    fingerprint: `sha256:${id}`,
    createdAt: updatedAt,
    updatedAt,
    savedBy,
  };
}

function preview(
  runtimeParity: NonNullable<VisualLibraryAuthoringCompileResult['runtimeParity']>,
  confirmations: number,
): VisualLibraryAuthoringCompileResult {
  return {
    schemaVersion: 'bloge.visualLibraryCompileResult.v1',
    draftId: 'owned',
    authoringRevision: 4,
    authoringFingerprint: 'authoring',
    compileFingerprint: 'compile',
    compilerVersion: '1',
    grammarVersion: '1',
    catalogFingerprint: 'catalog',
    runtimeParity,
    previewAuthority: 'SERVER_AUTHORITATIVE',
    canonicalFingerprint: 'canonical',
    sourceMap: [],
    diagnostics: [],
    confirmationRequests: Array.from({ length: confirmations }, (_, index) => ({
      code: `CONFIRM_${index}`,
      authoringPath: '/',
      question: 'Confirm?',
      allowedValues: ['YES', 'NO'],
    })),
    readiness: {
      state: 'DESIGN_READY',
      importable: true,
      strongSchemaReady: true,
      designReady: true,
      productionReady: false,
      gates: [],
    },
  };
}

function gate(status: VisualAuthoringTestDraftGate['status']): VisualAuthoringTestDraftGate {
  return {
    schemaVersion: 'bloge.visualAuthoringTestEvidenceGate.v1',
    scope: {
      tenantId: 'tenant',
      organizationId: 'organization',
      projectId: 'project',
      environmentId: 'test',
      region: 'local',
    },
    draftId: 'owned',
    authoringRevision: 4,
    authoringFingerprint: 'authoring',
    canonicalFingerprint: 'canonical',
    policyVersion: '1',
    status,
    achievedMaturity: status === 'PASSED' ? 'TEST_EVIDENCED' : 'DESIGN_READY',
    requiredAssets: 1,
    satisfiedAssets: status === 'PASSED' ? 1 : 0,
    reasons: status === 'PASSED' ? [] : ['MISSING_EVIDENCE'],
    assets: [],
    evaluatedAt: '2026-07-31T00:00:00Z',
  };
}
