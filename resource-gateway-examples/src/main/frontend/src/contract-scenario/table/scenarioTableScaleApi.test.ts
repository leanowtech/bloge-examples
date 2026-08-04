import { afterEach, describe, expect, it } from 'vitest';

import {
  bulkEditScenarioTable,
  queryScenarioTablePage,
  resetBlogeApiTransport,
  setBlogeApiTransport,
} from '../../api';
import type { StoredScenarioDraftSet } from '../domain';
import {
  createScenarioBulkEditCommand,
  createScenarioTablePageQuery,
  type ScenarioBulkEditResult,
  type ScenarioTablePage,
} from './scenarioTableScaleModel';

const source: StoredScenarioDraftSet = {
  schemaVersion: 'bloge.storedScenarioDraftSet.v1',
  scenarioDraftSetId: 'loan scenarios',
  revision: 17,
  fingerprint: 'sha256:source',
  draftSet: {
    schemaVersion: 'bloge.scenarioDraftSet.v1',
    scenarioDraftSetId: 'loan scenarios',
    revision: 17,
    scope: {
      tenantId: 'tenant-a', organizationId: 'org-a', projectId: 'project-a',
      environment: 'test', region: 'sg',
    },
    target: { kind: 'GRAPH', id: 'loan', revision: 4, fingerprint: 'sha256:target' },
    contractFingerprint: 'sha256:contract',
    scenarios: [],
    metadata: {
      owner: 'credit-platform', classification: 'INTERNAL',
      createdAt: null, updatedAt: null, provenance: {},
    },
  },
  savedAt: '2026-08-05T00:00:00Z',
  savedBy: 'author-1',
};

describe('Scenario Matrix scale API', () => {
  afterEach(() => resetBlogeApiTransport());

  it('binds page queries to the exact source and uses read authority', async () => {
    const query = createScenarioTablePageQuery(source, {
      query: 'boundary', caseTypes: ['BOUNDARY'], limit: 200,
    });
    const page: ScenarioTablePage = {
      schemaVersion: 'bloge.scenarioTablePage.v1',
      scenarioDraftSetId: source.scenarioDraftSetId,
      revision: source.revision,
      draftFingerprint: source.fingerprint,
      queryFingerprint: 'sha256:query',
      totalMatching: 0,
      rows: [],
      nextCursor: '',
    };
    setBlogeApiTransport(async (input, init) => {
      expect(String(input)).toBe(
        '/api/visual/scenario-draft-sets/loan%20scenarios/matrix/query',
      );
      expect(init).toMatchObject({ method: 'POST' });
      expect(new Headers(init?.headers).get('X-Purpose')).toBe('TEST_SUITE_READ');
      expect(JSON.parse(String(init?.body))).toEqual(query);
      return json(page);
    });

    await expect(queryScenarioTablePage(source.scenarioDraftSetId, query)).resolves.toEqual(page);
    expect(query).toMatchObject({
      expectedRevision: 17,
      expectedDraftFingerprint: 'sha256:source',
      sortField: 'CANONICAL',
      sortDirection: 'ASC',
    });
  });

  it('sends all row-fenced edits in one write-authorized atomic command', async () => {
    const command = createScenarioBulkEditCommand(source, 'command-a', [{
      caseId: 'case-a', expectedCaseFingerprint: 'sha256:case-a',
      field: 'GIVEN_PATH', path: '/applicant/age', operation: 'SET', value: 42,
    }]);
    const result: ScenarioBulkEditResult = {
      schemaVersion: 'bloge.scenarioBulkEditResult.v1',
      commandId: 'command-a',
      scenarioDraftSetId: source.scenarioDraftSetId,
      sourceRevision: 17,
      sourceDraftFingerprint: 'sha256:source',
      storedRevision: 18,
      storedDraftFingerprint: 'sha256:stored',
      touchedCells: 1,
      editedCaseIds: ['case-a'],
      committedAt: '2026-08-05T00:00:01Z',
      committedBy: 'author-1',
    };
    setBlogeApiTransport(async (input, init) => {
      expect(String(input)).toBe(
        '/api/visual/scenario-draft-sets/loan%20scenarios/matrix/bulk-edits',
      );
      expect(init).toMatchObject({ method: 'POST' });
      expect(new Headers(init?.headers).get('X-Purpose')).toBe('TEST_SUITE_WRITE');
      expect(JSON.parse(String(init?.body))).toEqual(command);
      return json(result);
    });

    await expect(bulkEditScenarioTable(source.scenarioDraftSetId, command)).resolves.toEqual(result);
    expect(command).toMatchObject({
      expectedRevision: 17,
      expectedDraftFingerprint: 'sha256:source',
      atomicity: 'ALL_OR_NOTHING',
    });
  });
});

function json(value: unknown): Response {
  return new Response(JSON.stringify(value), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
