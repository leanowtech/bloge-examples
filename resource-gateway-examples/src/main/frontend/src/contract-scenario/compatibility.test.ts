import { describe, expect, it } from 'vitest';

import {
  applyAutomaticCompatibilityMigrations,
  rebaseAfterCompatibilityReview,
} from './compatibility';
import { contractDraftFromGraphDraft } from './domain';
import { scenarioDraftSetFromCanvas } from './scenarioAuthoring';
import { graphDraft, nodes } from './testFixtures';
import type { ContractCompatibilityReport } from './domain';

describe('Contract compatibility migration', () => {
  it('applies declared default, input rename, and assertion rebind without rebasing', () => {
    const draft = graphDraft();
    const contract = contractDraftFromGraphDraft(draft, fingerprint('a'));
    contract.inputSchema.schema = {
      type: 'object',
      properties: {
        applicantId: { type: 'string' },
        customerCode: { type: 'string', 'x-bloge-renamed-from': '/legacyCode' },
        country: { type: 'string', default: 'SG' },
      },
      required: ['applicantId', 'country'],
      additionalProperties: false,
    };
    const draftSet = scenarioDraftSetFromCanvas(
      contract.target,
      fingerprint('b'),
      draft,
      nodes(),
      [],
    );
    draftSet.scenarios[0] = {
      ...draftSet.scenarios[0],
      given: {
        input: { applicantId: 'A-1', legacyCode: 'L-1' },
        provenance: 'AUTHORED',
      },
      then: {
        assertions: [{
          assertionId: 'decision',
          scope: 'OUTPUT_PATH',
          nodeId: '',
          fromNodeId: '',
          toNodeId: '',
          path: '/decision/reason',
          operator: 'EQUALS',
          expected: 'eligible',
        }],
      },
    };

    const result = applyAutomaticCompatibilityMigrations(
      draftSet,
      report([
        migration('M-001', 'ADD_DEFAULT', '', '/country'),
        migration('M-002', 'RENAME_INPUT', '/legacyCode', '/customerCode'),
        migration('M-003', 'REBIND_OUTPUT_ASSERTION', '/decision', '/outcome', 'OUTPUT'),
      ]),
      contract,
    );

    expect(result.appliedActionIds).toEqual(['M-001', 'M-002', 'M-003']);
    expect(result.blockedActionIds).toEqual([]);
    expect(result.draftSet.scenarios[0].given).toEqual({
      input: { applicantId: 'A-1', country: 'SG', customerCode: 'L-1' },
      provenance: 'MIGRATED',
    });
    expect(result.draftSet.scenarios[0].then.assertions[0].path).toBe('/outcome/reason');
    expect(result.draftSet.target).toEqual(draftSet.target);
    expect(result.draftSet.metadata.provenance.stagedCompatibilityMigration)
      .toMatchObject({ reportFingerprint: fingerprint('e') });
  });

  it('blocks a rename collision instead of overwriting authored data', () => {
    const draft = graphDraft();
    const contract = contractDraftFromGraphDraft(draft, fingerprint('a'));
    const draftSet = scenarioDraftSetFromCanvas(
      contract.target,
      fingerprint('b'),
      draft,
      nodes(),
      [],
    );
    draftSet.scenarios[0] = {
      ...draftSet.scenarios[0],
      given: {
        input: { legacyCode: 'L-1', customerCode: 'C-2' },
        provenance: 'AUTHORED',
      },
    };

    const result = applyAutomaticCompatibilityMigrations(
      draftSet,
      report([migration('M-001', 'RENAME_INPUT', '/legacyCode', '/customerCode')]),
      contract,
    );

    expect(result.appliedActionIds).toEqual([]);
    expect(result.blockedActionIds).toEqual(['M-001']);
    expect(result.draftSet).toBe(draftSet);
  });

  it('records exact review lineage when the author explicitly rebases', () => {
    const draft = graphDraft();
    const contract = contractDraftFromGraphDraft(draft, fingerprint('a'));
    const draftSet = scenarioDraftSetFromCanvas(
      { ...contract.target, fingerprint: fingerprint('c') },
      fingerprint('d'),
      draft,
      nodes(),
      [],
    );
    const compatibility = report([]);

    const rebased = rebaseAfterCompatibilityReview(
      draftSet,
      compatibility,
      contract.target,
      fingerprint('b'),
    );

    expect(rebased.target).toEqual(contract.target);
    expect(rebased.contractFingerprint).toBe(fingerprint('b'));
    expect(rebased.metadata.provenance.compatibilityResolution).toMatchObject({
      reportFingerprint: fingerprint('e'),
      sourceRevision: 1,
      classification: 'BREAKING',
    });
  });
});

function report(
  migrations: ContractCompatibilityReport['migrations'],
): ContractCompatibilityReport {
  return {
    schemaVersion: 'bloge.contractCompatibilityReport.v1',
    scenarioDraftSetId: 'loan-scenarios',
    scenarioRevision: 1,
    target: {
      kind: 'GRAPH',
      id: 'loan-graph',
      revision: 2,
      fingerprint: fingerprint('a'),
    },
    baselineContractFingerprint: fingerprint('d'),
    currentContractFingerprint: fingerprint('b'),
    policy: 'STRICT',
    classification: 'BREAKING',
    findings: [{
      findingId: 'F-001',
      scope: 'INPUT',
      path: '/country',
      previousPath: '',
      change: 'ADDED',
      classification: 'BREAKING',
      code: 'RG.CONTRACT.FIELD_ADDED',
      message: 'Country became required.',
      details: {},
    }],
    impactedScenarios: [{
      scenarioId: 'happy-path',
      status: 'MIGRATION_AVAILABLE',
      findingIds: ['F-001'],
      paths: ['/country'],
    }],
    migrations,
    generatedAt: '2026-07-27T00:00:00Z',
    reportFingerprint: fingerprint('e'),
  };
}

function migration(
  actionId: string,
  kind: ContractCompatibilityReport['migrations'][number]['kind'],
  fromPath: string,
  toPath: string,
  scope: 'INPUT' | 'OUTPUT' = 'INPUT',
): ContractCompatibilityReport['migrations'][number] {
  return {
    actionId,
    kind,
    scope,
    fromPath,
    toPath,
    automatic: true,
    scenarioIds: ['happy-path'],
    rationale: 'test',
  };
}

function fingerprint(character: string): string {
  return `sha256:${character.repeat(64)}`;
}
