import {
  presentTableCaseAuthority,
  type TableCaseAuthorityPresentation,
} from '../tableDrivenTestStatus';

import type { ScenarioTableRow } from './scenarioTableModel';

export const MOBILE_MATRIX_FIRST_VIEWPORT_COUNT = 3;

export interface MobileMatrixResultItem {
  row: ScenarioTableRow;
  authority: TableCaseAuthorityPresentation;
  hasFieldDiff: boolean;
  firstDiffPath: string;
}

export interface MobileMatrixResultProjection {
  items: MobileMatrixResultItem[];
  firstViewportCaseIds: string[];
  totalCount: number;
}

/** Projects canonical rows into the compact decision facts required by the mobile result task. */
export function projectMobileMatrixResults(
  rows: ScenarioTableRow[],
): MobileMatrixResultProjection {
  const items = rows.map((row) => ({
    row,
    authority: presentTableCaseAuthority(row.evidence),
    hasFieldDiff: Boolean(row.evidence.assertionDiffs?.some((diff) => !diff.passed)),
    firstDiffPath: row.evidence.assertionDiffs?.find((diff) => !diff.passed)?.path ?? '',
  }));
  return {
    items,
    firstViewportCaseIds: items
      .slice(0, MOBILE_MATRIX_FIRST_VIEWPORT_COUNT)
      .map((item) => item.row.caseId),
    totalCount: items.length,
  };
}
