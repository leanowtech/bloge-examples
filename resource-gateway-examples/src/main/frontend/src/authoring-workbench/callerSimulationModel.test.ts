import { describe, expect, it } from 'vitest';

import { bindingPlan, buildSimulationCommandV2, previewFixtureConditions } from './callerSimulationModel';
import type { FixtureSetView } from './flowModel';
import type { FixtureSetSummary } from './model';

describe('caller-directed simulation model', () => {
  it('keeps business input separate from exact Fixture bindings and denies egress', () => {
    const input = { customer: { tier: 'gold' } };
    const command = buildSimulationCommandV2(subject(), input, bindingPlan('BLOCK', [{
      target: { kind: 'NODE_PATH', nodePath: ['credit'] }, fixture: summary(),
      selectionKind: 'MATCH_CONDITION', conditionId: 'gold-customer',
    }]));
    input.customer.tier = 'changed';

    expect(command).toEqual({
      schemaVersion: 'bloge.simulationCommand.v2', subject: subject(),
      input: { kind: 'INLINE', value: { customer: { tier: 'gold' } } },
      fixturePlan: { kind: 'BINDINGS', unmatched: 'BLOCK', bindings: [{
        target: { kind: 'NODE_PATH', nodePath: ['credit'] },
        selection: { kind: 'MATCH_CONDITION', fixtureSet: {
          fixtureSetId: 'credit-cases', revision: 4, fingerprint: hash('b'),
        }, conditionId: 'gold-customer' },
      }] },
      executionPolicy: { externalReads: { kind: 'DENY' }, externalWrites: { kind: 'DENY' } },
    });
    expect(JSON.stringify(command)).not.toContain('invocationKey');
    expect(JSON.stringify(command)).not.toContain('output');
  });

  it('rejects duplicate static targets and incomplete exact selections', () => {
    expect(() => bindingPlan('BLOCK', [
      { target: { kind: 'SUBJECT' }, fixture: summary(), selectionKind: 'AUTO_MATCH' },
      { target: { kind: 'SUBJECT' }, fixture: summary(), selectionKind: 'EXACT_CASE', caseId: 'gold' },
    ])).toThrow('only one Fixture binding');
    expect(() => bindingPlan('BLOCK', [{
      target: { kind: 'SUBJECT' }, fixture: summary(), selectionKind: 'EXACT_CASE',
    }])).toThrow('Choose one exact Fixture Case');
  });

  it('previews the restricted condition language without exposing material', () => {
    const view: FixtureSetView = {
      schemaVersion: 'bloge.fixtureSet.v1', fixtureSetId: 'credit-cases', revision: 4,
      fingerprint: hash('b'), statusRevision: 1, displayName: 'Credit cases', subject: subject(),
      status: 'PRIVATE_DRAFT', cases: [{
        caseId: 'gold', name: 'Gold', input: {}, when: { conditionId: 'gold-customer', all: [
          { operator: 'EQ', path: '$.tier', value: 'gold' },
          { operator: 'NUMBER_RANGE', path: '$.score', minimum: 700 },
          { operator: 'PRESENT', path: '$.customerId' },
        ] }, controls: [],
      }, {
        caseId: 'missing', name: 'Missing', input: {}, when: { conditionId: 'missing-id', all: [
          { operator: 'ABSENT', path: '$.customerId' },
        ] }, controls: [],
      }],
    };

    expect(previewFixtureConditions(view, { tier: 'gold', score: 750, customerId: 'c-1' }))
      .toEqual([
        { caseId: 'gold', conditionId: 'gold-customer', matched: true },
        { caseId: 'missing', conditionId: 'missing-id', matched: false },
      ]);
  });
});

function subject() {
  return { kind: 'API_RESOURCE' as const, resourceId: 'credit', revision: 3, fingerprint: hash('a') };
}

function summary(): FixtureSetSummary {
  return {
    schemaVersion: 'bloge.fixtureSetSummary.v1', fixtureSetId: 'credit-cases', revision: 4,
    fingerprint: hash('b'), displayName: 'Credit cases', subject: subject(),
    cases: [{ caseId: 'gold', name: 'Gold' }], status: 'PRIVATE_DRAFT', statusRevision: 1,
  };
}

function hash(value: string): string { return `sha256:${value.repeat(64)}`; }
