import { describe, expect, it } from 'vitest';
import {
  boundedCartesian,
  enumerateDecisionTableScenarios,
  evalDecisionTable,
  parsePredicate,
  pickCombo,
  representativeValues,
  type DecisionTable,
} from './decisionScenario';

const table: DecisionTable = {
  tableId: 'credit-policy',
  hitPolicy: 'unique',
  columns: [
    { name: 'score', type: 'integer' },
    { name: 'segment', type: 'enum', values: ['A', 'B'] },
  ],
  rules: [
    { id: 'approve', conditions: { score: 'score >= 720', segment: 'segment in ["A"]' }, output: { decision: 'approve' } },
    { id: 'review', conditions: { score: '680 <= score < 720' }, output: { decision: 'review' } },
    { id: 'decline', otherwise: true, output: { decision: 'decline' } },
  ],
};

describe('decision scenario enumeration', () => {
  it('parses bounded comparisons, ranges, in, and opaque predicates', () => {
    expect(parsePredicate('score >= 720', 'score')).toMatchObject({ kind: 'comparison', operator: '>=', values: [720] });
    expect(parsePredicate('680 <= score < 720', 'score')).toMatchObject({ kind: 'range', values: [680, 720] });
    expect(parsePredicate('segment in ["A", "B"]', 'segment')).toMatchObject({ kind: 'in', values: ['A', 'B'] });
    expect(parsePredicate('creditBand(score)', 'score').kind).toBe('opaque');
  });

  it('prioritises integer epsilon representatives and preserves enum/boolean samples', () => {
    expect(representativeValues({ name: 'score', type: 'integer' }, [parsePredicate('score >= 720', 'score')])).toEqual([719, 720, 721]);
    expect(representativeValues({ name: 'segment', type: 'enum', values: ['A', 'B'] }, [])).toEqual(['A', 'B']);
    expect(representativeValues({ name: 'enabled', type: 'boolean' }, [])).toEqual([false, true]);
  });

  it('evaluates first and rejects ambiguous unique matches', () => {
    expect(evalDecisionTable(table, { score: 725, segment: 'A' })).toMatchObject({ status: 'MATCHED', ruleId: 'approve' });
    expect(evalDecisionTable({ ...table, hitPolicy: 'first' }, { score: 700, segment: 'B' })).toMatchObject({ status: 'MATCHED', ruleId: 'review' });
    expect(evalDecisionTable({ ...table, rules: [{ id: 'a', conditions: { score: 'score >= 0' }, output: 'a' }, { id: 'b', conditions: { score: 'score >= 0' }, output: 'b' }] }, { score: 1 })).toMatchObject({ status: 'AMBIGUOUS' });
  });

  it('picks a representative combo for a rule without being intercepted by higher rules', () => {
    expect(pickCombo(table, 'review')).toEqual({ score: 680, segment: 'A' });
    expect(pickCombo(table, 'decline')).not.toBeNull();
    expect(pickCombo({ ...table, rules: [{ id: 'a', conditions: { score: 'score >= 0' }, output: 'a' }, { id: 'b', conditions: { score: 'score >= 0' }, output: 'b' }] }, 'b')).toBeNull();
  });

  it('reports deterministic truncation metadata from bounded cartesian products', () => {
    const first = boundedCartesian({ a: [1, 2, 3], b: ['x', 'y', 'z'] }, 4);
    const second = boundedCartesian({ a: [1, 2, 3], b: ['x', 'y', 'z'] }, 4);
    expect(first).toEqual(second);
    expect(first).toMatchObject({ truncated: true, strategy: 'STRATIFIED', totalCombinations: 9, emittedCombinations: 4 });
    expect(first.combinations).toEqual(expect.arrayContaining([{ a: 1, b: 'x' }, { a: 1, b: 'z' }, { a: 3, b: 'x' }, { a: 3, b: 'z' }]));
  });

  it('emits ScenarioDraft-compatible deterministic scenarios and truthful opaque metadata', () => {
    const result = enumerateDecisionTableScenarios(table, { mode: 'per-rule', cap: 20, target: { kind: 'GRAPH', id: 'g', revision: 1, fingerprint: 'sha256:g' }, contractFingerprint: 'sha256:c' });
    expect(result.scenarios.length).toBe(3);
    expect(result.scenarios.every((scenario) => scenario.given.provenance === 'GENERATED')).toBe(true);
    expect(result.scenarios[0]?.then.assertions[0]?.expected).toBeDefined();
    expect(result.metadata.provenance).toBe('DECISION_TABLE_ENUMERATION');
    expect(result.metadata.sourceFingerprint).toMatch(/^sha256:/);
    expect(result.draftSet.schemaVersion).toBe('bloge.scenarioDraftSet.v1');

    const opaque: DecisionTable = { ...table, columns: [{ name: 'score', type: 'integer', authorSamples: [701] }], rules: [{ id: 'opaque', conditions: { score: 'risk(score)' }, output: 'review' }] };
    const opaqueResult = enumerateDecisionTableScenarios(opaque, { mode: 'combinatorial', cap: 10, target: { kind: 'GRAPH', id: 'g', revision: 1, fingerprint: 'sha256:g' }, contractFingerprint: 'sha256:c' });
    expect(opaqueResult.metadata.exhaustive).toBe(false);
    expect(opaqueResult.metadata.opaqueColumns).toEqual(['score']);
  });

  it('maps graph input paths and keeps plan/dispatch outputs structured and non-executable', () => {
    const result = enumerateDecisionTableScenarios({ ...table, outputKind: 'plan', rules: [{ id: 'plan', conditions: { score: 'score >= 720' }, output: { action: 'approve', steps: [{ id: 'notify' }], reason: 'eligible' } }] }, { mode: 'per-rule', cap: 4, colToInputPath: { score: 'request.score' }, target: { kind: 'GRAPH', id: 'g', revision: 1, fingerprint: 'sha256:g' } });
    expect(result.scenarios[0]?.given.input).toMatchObject({ request: { score: 720 }, segment: expect.any(String) });
    expect(result.scenarios[0]?.then.assertions[0]?.expected).toMatchObject({ action: 'approve', steps: [{ id: 'notify' }] });
    const dispatch = enumerateDecisionTableScenarios({ ...table, outputKind: 'dispatch', rules: [{ id: 'dispatch', otherwise: true, output: { targetRef: 'publication:child' } }] }, { mode: 'per-rule', cap: 4 });
    expect(dispatch.scenarios[0]?.then.assertions[0]?.expected).toMatchObject({ targetRef: 'publication:child', dispatchMode: 'MODELED_ONLY' });
  });

  it('maps ordinary plan objects deterministically without stringifying them as [object Object]', () => {
    const result = enumerateDecisionTableScenarios({
      ...table,
      outputKind: 'plan',
      rules: [{
        id: 'decline',
        otherwise: true,
        output: { decision: 'decline', tier: 'risk', reason: { code: 'LIMIT' } },
      }],
    }, { mode: 'per-rule', cap: 4 });

    expect(result.scenarios[0]?.then.assertions[0]?.expected).toEqual({
      action: 'decline',
      steps: [],
      reason: '{"code":"LIMIT"}',
    });
    expect(JSON.stringify(result.scenarios[0])).not.toContain('[object Object]');
    expect(result.scenarios[0]?.dependencies).toEqual([]);
  });

  it('refuses to fabricate an opaque domain without author samples', () => {
    expect(() => enumerateDecisionTableScenarios({ ...table, columns: [{ name: 'score', type: 'integer' }], rules: [{ id: 'opaque', conditions: { score: 'risk(score)' }, output: 'review' }] }, { mode: 'combinatorial', cap: 4 })).toThrowError(/author samples/);
  });
});
