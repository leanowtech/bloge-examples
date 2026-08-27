import { describe, expect, it } from 'vitest';
import {
  decisionTableFromEditor,
  decisionTableSourceFingerprint,
  operatorScenarioDraftSetId,
  scenarioSetIsStale,
  scenarioSetMatchesOperator,
} from './decisionScenarioModel';

const editor = {
  hitPolicy: 'unique', outputType: '{ decision: String }',
  conditionColumns: [{ id: 'score' }], outputColumns: [{ id: 'decision' }],
  rows: [
    { conditions: { score: 'score >= 720' }, outputs: { decision: 'approve' }, otherwise: false },
    { conditions: { score: 'score < 720' }, outputs: { decision: 'review' }, otherwise: false },
    { conditions: { score: '' }, outputs: { decision: 'decline' }, otherwise: true },
  ],
};

describe('decision scenario workbench model', () => {
  it('adapts the real editor rows into four compatible rules including otherwise output', () => {
    const table = decisionTableFromEditor(editor, 'risk');
    expect(table.rules).toHaveLength(3);
    expect(table.rules[0]?.output).toEqual({ decision: 'approve' });
    expect(table.rules[2]?.otherwise).toBe(true);
    expect(decisionTableFromEditor({ ...editor, rows: [{ ...editor.rows[0], conditions: { score: '>= 720' } }] }).rules[0]?.conditions?.score).toBe('score >= 720');
  });

  it('fingerprints equivalent editor snapshots deterministically and detects stale source', () => {
    const first = decisionTableSourceFingerprint(editor, 'risk');
    expect(first).toBe(decisionTableSourceFingerprint(structuredClone(editor), 'risk'));
    expect(scenarioSetIsStale(editor, { metadata: { provenance: { sourceFingerprint: 'sha256:changed' } } } as never, 'risk')).toBe(true);
  });

  it('reopens only a generated set with the exact operator and contract coordinate', () => {
    const draftSet = {
      metadata: { provenance: { operatorRef: 'bloge:decisionTable' } },
      target: { kind: 'OPERATOR', id: 'bloge:decisionTable', fingerprint: 'sha256:operator' },
      contractFingerprint: 'sha256:contract',
    } as any;
    expect(scenarioSetMatchesOperator(draftSet, 'bloge:decisionTable', draftSet.target, 'sha256:contract')).toBe(true);
    expect(scenarioSetMatchesOperator(draftSet, 'bloge:decisionTable', { ...draftSet.target, revision: 2 }, 'sha256:contract')).toBe(false);
    expect(scenarioSetMatchesOperator(draftSet, 'bloge:decisionTable', draftSet.target, 'sha256:changed')).toBe(false);
    expect(scenarioSetMatchesOperator(draftSet, 'bloge:other', draftSet.target, 'sha256:contract')).toBe(false);
  });

  it('builds deterministic portable ids with bounded readable prefixes and digest distinction', async () => {
    const longRef = `catalog:${'very-long-operator-name.'.repeat(30)}`;
    const first = await operatorScenarioDraftSetId(longRef);
    const second = await operatorScenarioDraftSetId(longRef);

    expect(first).toBe(second);
    expect(first).toMatch(/^[A-Za-z0-9][A-Za-z0-9._-]*$/);
    expect(first.length).toBeLessThanOrEqual(255);
    expect(first).toContain('catalog-very-long-operator-name');
    expect(await operatorScenarioDraftSetId('catalog:other-operator')).not.toBe(first);
  });
});
