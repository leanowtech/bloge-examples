import { describe, expect, it } from 'vitest';

import { tableDrivenScenarioBaseline } from '../tableDrivenTestingBaseline';
import {
  applyScenarioTableCellEdit,
  buildScenarioTableProjection,
  filterAndSortScenarioRows,
  resolveExactScenarioRunSelection,
  selectVisibleScenarios,
  toggleScenarioSelection,
  type ScenarioTableEvidenceByCase,
} from './scenarioTableModel';

describe('ScenarioTableProjection', () => {
  it.each([5, 50, 500] as const)(
    'projects %i canonical Scenarios with stable grouped columns',
    (size) => {
      const draftSet = tableDrivenScenarioBaseline(size);
      const first = buildScenarioTableProjection(draftSet);
      const second = buildScenarioTableProjection(draftSet);

      expect(first).toEqual(second);
      expect(first.rows).toHaveLength(size);
      expect(first.columns.filter((column) => column.group === 'GIVEN')).toHaveLength(20);
      expect(first.columns.filter((column) => column.group === 'DEPENDENCY')).toHaveLength(8);
      expect(first.columns.filter((column) => column.group === 'THEN')).toHaveLength(12);
      expect(new Set(first.columns.map((column) => column.columnId)).size).toBe(first.columns.length);
      expect(first.projectionFingerprint).toMatch(/^fnv1a32:[0-9a-f]{8}$/);
    },
  );

  it('round-trips Matrix scalar edits through the canonical Scenario only', () => {
    const original = tableDrivenScenarioBaseline(5);
    const projection = buildScenarioTableProjection(original);
    const caseId = original.scenarios[0].scenarioId;
    const nameColumn = projection.columns.find((column) => column.columnId === 'case:name');
    const givenColumn = projection.columns.find((column) => column.path === '/given/input/field01');
    expect(nameColumn).toBeDefined();
    expect(givenColumn).toBeDefined();

    const renamed = applyScenarioTableCellEdit(original, caseId, nameColumn!, 'Reviewed boundary');
    const edited = applyScenarioTableCellEdit(renamed, caseId, givenColumn!, 'customer-42');
    const rebuilt = buildScenarioTableProjection(edited);

    expect(original.scenarios[0].name).not.toBe('Reviewed boundary');
    expect(edited.scenarios[0].name).toBe('Reviewed boundary');
    expect(edited.scenarios[0].given.input).toMatchObject({ field01: 'customer-42' });
    expect(rebuilt.rows[0].values[givenColumn!.columnId]).toBe('customer-42');
    expect(rebuilt.projectionFingerprint).not.toBe(projection.projectionFingerprint);
  });

  it('keeps exact selected ids when filter and sort change the visible rows', () => {
    const projection = buildScenarioTableProjection(tableDrivenScenarioBaseline(50));
    let selection = { selectedCaseIds: [] as string[] };
    selection = toggleScenarioSelection(selection, projection.rows[1].caseId, true);
    selection = toggleScenarioSelection(selection, projection.rows[38].caseId, true);

    const visible = filterAndSortScenarioRows(
      projection,
      { query: 'business case 1', caseTypes: ['GOLDEN'], tones: [] },
      { key: 'NAME', direction: 'DESC' },
    );
    selection = selectVisibleScenarios(selection, visible.map((row) => row.caseId), true);
    const exact = resolveExactScenarioRunSelection(projection, selection, 'SELECTED');

    expect(exact.caseIds).toEqual(projection.rows
      .filter((row) => selection.selectedCaseIds.includes(row.caseId))
      .map((row) => row.caseId));
    expect(exact.caseIds).toContain(projection.rows[38].caseId);
    expect(exact.selectionFingerprint).toMatch(/^fnv1a32:/);
  });

  it('resolves Run failed only from the previous exact run closure', () => {
    const draftSet = tableDrivenScenarioBaseline(5);
    const evidence: ScenarioTableEvidenceByCase = {
      [draftSet.scenarios[0].scenarioId]: failedEvidence(draftSet.scenarios[0].scenarioId),
      [draftSet.scenarios[1].scenarioId]: failedEvidence(draftSet.scenarios[1].scenarioId),
      [draftSet.scenarios[2].scenarioId]: passedEvidence(draftSet.scenarios[2].scenarioId),
    };
    const projection = buildScenarioTableProjection(draftSet, evidence);
    const failed = resolveExactScenarioRunSelection(
      projection,
      { selectedCaseIds: [] },
      'FAILED',
      [draftSet.scenarios[1].scenarioId, draftSet.scenarios[2].scenarioId],
    );

    expect(failed.caseIds).toEqual([draftSet.scenarios[1].scenarioId]);
  });

  it('does not expose a generic Passed value in proof cells', () => {
    const draftSet = tableDrivenScenarioBaseline(5);
    const projection = buildScenarioTableProjection(draftSet, {
      [draftSet.scenarios[0].scenarioId]: passedEvidence(draftSet.scenarios[0].scenarioId),
    });

    expect(projection.rows[0].values['proof:verdict']).toBe('Mock behavior matched');
    expect(Object.values(projection.rows[0].values)).not.toContain('Passed');
  });
});

function failedEvidence(caseId: string) {
  return {
    caseId,
    runId: 'run-1',
    attempt: 1,
    execution: 'SUCCESS' as const,
    assertions: 'FAILED' as const,
    freshness: 'CURRENT' as const,
    proofStrength: 'MOCK' as const,
    durationMs: 12,
    firstFailure: { category: 'ASSERTION', target: '$.result', message: 'Values differ.' },
  };
}

function passedEvidence(caseId: string) {
  return {
    ...failedEvidence(caseId),
    assertions: 'PASSED' as const,
    firstFailure: null,
  };
}
