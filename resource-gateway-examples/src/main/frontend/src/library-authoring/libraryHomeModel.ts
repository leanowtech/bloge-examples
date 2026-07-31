import type {
  VisualAuthoringTestDraftGate,
  VisualLibraryAuthoringCompileResult,
  VisualLibraryAuthoringDraft,
} from '../types';

export type LibraryHomeFilter =
  | 'all'
  | 'recent'
  | 'mine'
  | 'needs-confirmation'
  | 'runtime-drift'
  | 'test-gate-incomplete'
  | 'ownership-conflict';

export type LibraryHomeStatus =
  | 'NEEDS_CONFIRMATION'
  | 'RUNTIME_DRIFT'
  | 'TEST_GATE_INCOMPLETE'
  | 'OWNERSHIP_CONFLICT';

export interface LibraryHomeAssessment {
  preview?: VisualLibraryAuthoringCompileResult;
  testGate?: VisualAuthoringTestDraftGate;
  pending?: boolean;
  error?: string;
}

export interface LibraryHomeItem {
  draft: VisualLibraryAuthoringDraft;
  libraryId: string;
  name: string;
  owner: string;
  mine: boolean;
  recent: boolean;
  statuses: LibraryHomeStatus[];
  assessmentPending: boolean;
  assessmentError: string;
  resumeHref: string;
}

export interface LibraryHomePage {
  items: LibraryHomeItem[];
  page: number;
  pageCount: number;
  total: number;
}

export const LIBRARY_HOME_FILTERS: Array<{
  id: LibraryHomeFilter;
  label: string;
}> = [
  { id: 'all', label: 'All libraries' },
  { id: 'recent', label: 'Recent drafts' },
  { id: 'mine', label: 'My libraries' },
  { id: 'needs-confirmation', label: 'Needs confirmation' },
  { id: 'runtime-drift', label: 'Runtime drift' },
  { id: 'test-gate-incomplete', label: 'Test gate incomplete' },
  { id: 'ownership-conflict', label: 'Ownership conflict' },
];

const DRIFT_STATES = new Set(['DRIFTED']);

export function projectLibraryHomeItems(
  drafts: VisualLibraryAuthoringDraft[],
  assessments: Record<string, LibraryHomeAssessment>,
  actorId: string,
  now = new Date(),
): LibraryHomeItem[] {
  const recentBoundary = now.getTime() - (14 * 24 * 60 * 60 * 1000);
  return drafts
    .map((draft) => {
      const assessment = assessments[draft.draftId] ?? {};
      const owner = draft.document.library.owner?.trim() ?? '';
      const statuses: LibraryHomeStatus[] = [];
      if ((assessment.preview?.confirmationRequests.length ?? 0) > 0) {
        statuses.push('NEEDS_CONFIRMATION');
      }
      if (assessment.preview?.runtimeParity?.some((entry) => (
        DRIFT_STATES.has(entry.state)
      ))) {
        statuses.push('RUNTIME_DRIFT');
      }
      if (assessment.testGate?.status === 'BLOCKED') {
        statuses.push('TEST_GATE_INCOMPLETE');
      }
      if (!owner) {
        statuses.push('OWNERSHIP_CONFLICT');
      }
      const updatedAt = Date.parse(draft.updatedAt);
      return {
        draft,
        libraryId: draft.document.library.id || draft.draftId,
        name: draft.document.library.name?.trim()
          || draft.document.library.id
          || draft.draftId,
        owner,
        mine: Boolean(actorId) && (
          draft.savedBy === actorId || owner === actorId
        ),
        recent: Number.isFinite(updatedAt) && updatedAt >= recentBoundary,
        statuses,
        assessmentPending: assessment.pending === true,
        assessmentError: assessment.error ?? '',
        resumeHref: libraryRevisionHref(draft.draftId, draft.revision),
      };
    })
    .sort((left, right) => (
      Date.parse(right.draft.updatedAt) - Date.parse(left.draft.updatedAt)
      || left.name.localeCompare(right.name)
    ));
}

export function filterLibraryHomeItems(
  items: LibraryHomeItem[],
  filter: LibraryHomeFilter,
  search: string,
): LibraryHomeItem[] {
  const query = search.trim().toLocaleLowerCase();
  return items.filter((item) => (
    matchesFilter(item, filter)
    && (
      !query
      || [
        item.name,
        item.libraryId,
        item.draft.draftId,
        item.owner,
        item.draft.savedBy,
      ].some((value) => value.toLocaleLowerCase().includes(query))
    )
  ));
}

export function paginateLibraryHomeItems(
  items: LibraryHomeItem[],
  requestedPage: number,
  pageSize: number,
): LibraryHomePage {
  const size = Math.max(1, pageSize);
  const pageCount = Math.max(1, Math.ceil(items.length / size));
  const page = Math.min(Math.max(1, requestedPage), pageCount);
  const start = (page - 1) * size;
  return {
    items: items.slice(start, start + size),
    page,
    pageCount,
    total: items.length,
  };
}

export function countLibraryHomeFilter(
  items: LibraryHomeItem[],
  filter: LibraryHomeFilter,
): number {
  return items.filter((item) => matchesFilter(item, filter)).length;
}

export function libraryRevisionHref(draftId: string, revision: number): string {
  const query = new URLSearchParams({
    draftId,
    revision: String(Math.max(1, revision)),
  });
  return `/libraries/?${query.toString()}`;
}

function matchesFilter(item: LibraryHomeItem, filter: LibraryHomeFilter): boolean {
  switch (filter) {
    case 'all':
      return true;
    case 'recent':
      return item.recent;
    case 'mine':
      return item.mine;
    case 'needs-confirmation':
      return item.statuses.includes('NEEDS_CONFIRMATION');
    case 'runtime-drift':
      return item.statuses.includes('RUNTIME_DRIFT');
    case 'test-gate-incomplete':
      return item.statuses.includes('TEST_GATE_INCOMPLETE');
    case 'ownership-conflict':
      return item.statuses.includes('OWNERSHIP_CONFLICT');
  }
}
