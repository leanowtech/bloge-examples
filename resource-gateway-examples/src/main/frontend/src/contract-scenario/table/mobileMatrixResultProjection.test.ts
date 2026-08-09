import { describe, expect, it } from 'vitest';

import { tableDrivenScenarioBaseline } from '../tableDrivenTestingBaseline';
import { buildScenarioTableProjection } from './scenarioTableModel';
import {
  MOBILE_MATRIX_FIRST_VIEWPORT_COUNT,
  projectMobileMatrixResults,
} from './mobileMatrixResultProjection';

describe('mobileMatrixResultProjection', () => {
  it('reserves the first mobile result viewport for three comparable summaries', () => {
    const rows = buildScenarioTableProjection(tableDrivenScenarioBaseline(5)).rows;

    const projection = projectMobileMatrixResults(rows);

    expect(MOBILE_MATRIX_FIRST_VIEWPORT_COUNT).toBe(3);
    expect(projection.firstViewportCaseIds).toEqual(rows.slice(0, 3).map((row) => row.caseId));
    expect(projection.items).toHaveLength(5);
  });

  it('exposes the first failed field as a direct drill-down coordinate', () => {
    const draft = tableDrivenScenarioBaseline(5);
    const caseId = draft.scenarios[1].scenarioId;
    const rows = buildScenarioTableProjection(draft, {
      [caseId]: {
        caseId,
        runId: 'run-failed',
        attempt: 1,
        execution: 'SUCCESS',
        assertions: 'FAILED',
        freshness: 'CURRENT',
        proofStrength: 'RUNTIME',
        subjectMode: 'REAL',
        durationMs: 12,
        firstFailure: null,
        assertionDiffs: [{
          assertionId: 'decision',
          path: '$.decision',
          passed: false,
          expected: 'APPROVED',
          actual: 'REVIEW',
          detail: 'Expected APPROVED.',
        }],
      },
    }).rows;

    const item = projectMobileMatrixResults(rows).items.find((candidate) => candidate.row.caseId === caseId);

    expect(item).toMatchObject({ hasFieldDiff: true, firstDiffPath: '$.decision' });
    expect(item?.authority.governanceEligibility).toBe('INELIGIBLE');
  });
});
