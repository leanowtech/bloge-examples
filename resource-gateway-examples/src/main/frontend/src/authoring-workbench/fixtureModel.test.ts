import { describe, expect, it } from 'vitest';

import { buildFixtureObjectCommand, fixtureObjectDraft } from './fixtureModel';
import type { FixtureSetView } from './flowModel';

describe('Fixture object model', () => {
  it('round-trips one editable whole-flow Return without changing its Subject', () => {
    const view = fixture('FLOW_DRAFT');
    const draft = fixtureObjectDraft(view);

    expect(draft).toEqual(expect.objectContaining({
      displayName: 'Overview default', inputSource: '{\n  "customerId": "c-1"\n}',
      outputSource: '{\n  "result": "old"\n}',
    }));
    const command = buildFixtureObjectCommand(view, { ...draft!, outputSource: '{"result":"new"}' });
    expect(command.subject).toEqual(view.subject);
    expect(command.cases[0]).toEqual(expect.objectContaining({
      input: { customerId: 'c-1' }, expect: { output: { result: 'new' } },
      controls: [{
        target: { kind: 'SUBJECT' },
        behavior: { kind: 'RETURN', material: { kind: 'INLINE', value: { result: 'new' } } },
      }],
    }));
  });

  it('does not offer an independent editor for a Resource-owned Default Fixture', () => {
    expect(fixtureObjectDraft(fixture('API_RESOURCE'))).toBeNull();
    expect(() => buildFixtureObjectCommand(fixture('API_RESOURCE'), {
      displayName: 'Changed', caseId: 'default', caseName: 'Default',
      inputSource: '{}', outputSource: '{}', conditionId: '', conditionPath: '$.',
      conditionOperator: 'EQ', conditionValueSource: '',
    })).toThrow('edited on the Resource page');
  });

  it('round-trips one stable condition without changing Fixture material', () => {
    const view = fixture('FLOW_DRAFT');
    view.cases[0].when = {
      conditionId: 'known-customer', all: [{ operator: 'EQ', path: '$.tier', value: 'gold' }],
    };

    const draft = fixtureObjectDraft(view)!;
    expect(draft).toMatchObject({
      conditionId: 'known-customer', conditionPath: '$.tier',
      conditionOperator: 'EQ', conditionValueSource: '"gold"',
    });
    const command = buildFixtureObjectCommand(view, {
      ...draft, conditionId: 'priority-customer', conditionValueSource: '"platinum"',
    });
    expect(command.cases[0].when).toEqual({
      conditionId: 'priority-customer', all: [{ operator: 'EQ', path: '$.tier', value: 'platinum' }],
    });
    expect(command.cases[0].controls).toEqual(view.cases[0].controls);
  });
});

function fixture(kind: 'FLOW_DRAFT' | 'API_RESOURCE'): FixtureSetView {
  return {
    schemaVersion: 'bloge.fixtureSet.v1', fixtureSetId: 'overview.default', revision: 1,
    fingerprint: hash('a'), statusRevision: 1, displayName: 'Overview default',
    subject: kind === 'FLOW_DRAFT'
      ? { kind, draftId: 'draft-1', revision: 1, fingerprint: hash('b') }
      : { kind, resourceId: 'profile', revision: 1, fingerprint: hash('b') },
    cases: [{
      caseId: 'default', name: 'Default', input: { customerId: 'c-1' },
      controls: [{
        target: { kind: 'SUBJECT' },
        behavior: { kind: 'RETURN', material: { kind: 'INLINE', value: { result: 'old' } } },
      }],
      expect: { output: { result: 'old' } },
    }],
    status: 'PRIVATE_DRAFT',
  };
}

function hash(value: string): string { return `sha256:${value.repeat(64)}`; }
