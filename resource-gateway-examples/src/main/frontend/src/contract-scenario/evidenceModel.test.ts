import { describe, expect, it } from 'vitest';

import { successfulResponse } from './testFixtures';
import { scenarioEvidenceView } from './evidenceModel';
import type { ScenarioComparison } from './scenarioAuthoring';

describe('scenarioEvidenceView', () => {
  it('does not claim success when assertions pass but trust checks are missing', () => {
    const view = scenarioEvidenceView(successfulResponse(), comparison(true));

    expect(view.headline).toBe('Evidence incomplete');
    expect(view.tone).toBe('pending');
    expect(view.dimensions.map((dimension) => [dimension.key, dimension.state])).toEqual([
      ['draft', 'passed'],
      ['execution', 'passed'],
      ['assertions', 'passed'],
      ['contract', 'not-checked'],
      ['governance', 'not-checked'],
    ]);
  });

  it('prioritizes blockers over passing assertions and warnings', () => {
    const view = scenarioEvidenceView(successfulResponse(), comparison(false), {
      contractStatus: 'WARNING',
      governanceStatus: 'BLOCKED',
      diagnostics: [{
        id: 'gate-1',
        severity: 'BLOCKING',
        scope: 'GOVERNANCE',
        code: 'OWNER_APPROVAL_MISSING',
        message: 'Owner approval is required.',
        coordinate: '/owner',
      }],
    });

    expect(view.headline).toBe('Promotion blocked');
    expect(view.blockers.map((issue) => issue.code)).toEqual(expect.arrayContaining([
      'ASSERTIONS_FAILED',
      'GOVERNANCE_BLOCKED',
      'OWNER_APPROVAL_MISSING',
    ]));
    expect(view.warnings.map((issue) => issue.code)).toContain('CONTRACT_WARNING');
    expect(view.failedAssertions).toHaveLength(1);
    expect(view.passedAssertions).toHaveLength(1);
  });

  it('claims promotion readiness only after all five dimensions pass', () => {
    const view = scenarioEvidenceView(successfulResponse(), comparison(true), {
      contractStatus: 'VALID',
      governanceStatus: 'APPROVED',
    });

    expect(view.headline).toBe('Ready for promotion');
    expect(view.tone).toBe('success');
    expect(view.dimensions.every((dimension) => dimension.state === 'passed')).toBe(true);
  });

  it('retains prior evidence as stale and blocks a dirty draft from promotion', () => {
    const view = scenarioEvidenceView(successfulResponse(), comparison(true), {
      draftStatus: 'DIRTY',
      evidenceFreshness: 'STALE',
      contractStatus: 'STALE',
      governanceStatus: 'STALE',
    });

    expect(view.headline).toBe('Promotion blocked');
    expect(view.dimensions.map((dimension) => [dimension.key, dimension.status])).toEqual([
      ['draft', 'DIRTY'],
      ['execution', 'STALE'],
      ['assertions', 'STALE'],
      ['contract', 'STALE'],
      ['governance', 'STALE'],
    ]);
    expect(view.blockers.map((issue) => issue.code)).toEqual(expect.arrayContaining([
      'DRAFT_DIRTY',
      'EXECUTION_STALE',
      'ASSERTIONS_STALE',
      'CONTRACT_STALE',
      'GOVERNANCE_STALE',
    ]));
  });

  it('uses the deduplicated diagnostic count in the user-facing summary', () => {
    const warning = {
      level: 'warning',
      code: 'bloge.dsl',
      message: 'Generated DSL retained one inferred path.',
      target: '/nodes/score',
    };
    const view = scenarioEvidenceView({
      ...successfulResponse(),
      diagnostics: [warning, warning, warning],
    }, comparison(true), {
      contractStatus: 'VALID',
      governanceStatus: 'APPROVED',
    });

    expect(view.warnings).toHaveLength(1);
    expect(view.warnings[0].occurrences).toBe(3);
    expect(view.summary).toBe('1 warning needs an explicit decision.');
  });

  it('blocks evidence when any canonical source fingerprint drifts', () => {
    const view = scenarioEvidenceView(successfulResponse(), comparison(true), {
      contractStatus: 'VALID',
      governanceStatus: 'APPROVED',
      coordinate: {
        draftId: 'loan',
        draftRevision: 4,
        draftFingerprint: fingerprint('d'),
        contractFingerprint: fingerprint('c'),
        scenarioId: 'approved',
        scenarioRevision: 2,
        scenarioFingerprint: fingerprint('s'),
        closureFingerprint: fingerprint('o'),
        requestFingerprint: fingerprint('r'),
        editorSnapshotFingerprint: fingerprint('a'),
        compiledPlanSourceFingerprint: fingerprint('a'),
        requestSourceFingerprint: fingerprint('b'),
        evidenceSourceFingerprint: fingerprint('a'),
      },
    });

    expect(view.headline).toBe('Promotion blocked');
    expect(view.blockers).toContainEqual(expect.objectContaining({
      code: 'SCENARIO_FINGERPRINT_CLOSURE_MISMATCH',
      occurrences: 1,
    }));
  });
});

function comparison(passed: boolean): ScenarioComparison {
  return {
    passed,
    diagnostics: [],
    results: passed
      ? [{
          assertionId: 'decision',
          passed: true,
          path: 'decision.approved',
          expected: true,
          actual: true,
          detail: 'Matched.',
        }]
      : [
          {
            assertionId: 'decision',
            passed: false,
            path: 'decision.approved',
            expected: true,
            actual: false,
            detail: 'Expected and actual values differ.',
          },
          {
            assertionId: 'reason',
            passed: true,
            path: 'decision.reason',
            expected: 'eligible',
            actual: 'eligible',
            detail: 'Matched.',
          },
        ],
  };
}

function fingerprint(seed: string): string {
  return `sha256:${seed.repeat(64).slice(0, 64)}`;
}
