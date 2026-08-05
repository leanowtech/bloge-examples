import {
  useEffect,
  useMemo,
  useState,
} from 'react';

import {
  fetchLibraryAuthoringContext,
  fetchLibraryAuthoringDrafts,
  fetchLibraryAuthoringTestGate,
  previewLibraryAuthoringDraft,
} from '../api';
import { useI18n } from '../i18n/I18nProvider';
import type {
  VisualLibraryAuthoringDocument,
  VisualLibraryAuthoringDraft,
} from '../types';
import LibraryStartChoices, {
  type LibraryStartChoice,
} from './LibraryStartChoices';
import {
  countLibraryHomeFilter,
  filterLibraryHomeItems,
  LIBRARY_HOME_FILTERS,
  paginateLibraryHomeItems,
  projectLibraryHomeItems,
  type LibraryHomeAssessment,
  type LibraryHomeFilter,
  type LibraryHomeStatus,
} from './libraryHomeModel';
import type { SampleInferenceLaunch } from './SampleInferenceReview';

interface LibraryHomeProps {
  routeError?: string;
  onStart: (
    document: VisualLibraryAuthoringDocument,
    source: string,
    inference?: SampleInferenceLaunch,
  ) => void;
}

const PAGE_SIZE = 8;

export default function LibraryHome({ routeError = '', onStart }: LibraryHomeProps) {
  const { locale, t } = useI18n();
  const [drafts, setDrafts] = useState<VisualLibraryAuthoringDraft[]>([]);
  const [actorId, setActorId] = useState('');
  const [assessments, setAssessments] = useState<Record<string, LibraryHomeAssessment>>({});
  const [filter, setFilter] = useState<LibraryHomeFilter>('all');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [startChoice, setStartChoice] = useState<LibraryStartChoice | null>(null);

  useEffect(() => {
    let active = true;
    Promise.all([
      fetchLibraryAuthoringDrafts(),
      fetchLibraryAuthoringContext(),
    ]).then(([loadedDrafts, context]) => {
      if (!active) return;
      setDrafts(loadedDrafts);
      setActorId(context.actorId);
      if (loadedDrafts.length === 0) {
        setStartChoice((current) => current ?? 'quick');
      }
      setAssessments(Object.fromEntries(loadedDrafts.map((draft) => [
        draft.draftId,
        { pending: true },
      ])));
      void assessDrafts(loadedDrafts, (draftId, assessment) => {
        if (!active) return;
        setAssessments((current) => ({ ...current, [draftId]: assessment }));
      });
    }).catch((cause: unknown) => {
      if (active) {
        setLoadError(cause instanceof Error ? cause.message : 'Unable to load library assets.');
      }
    }).finally(() => {
      if (active) setLoading(false);
    });
    return () => {
      active = false;
    };
  }, []);

  const items = useMemo(
    () => projectLibraryHomeItems(drafts, assessments, actorId),
    [actorId, assessments, drafts],
  );
  const filtered = useMemo(
    () => filterLibraryHomeItems(items, filter, search),
    [filter, items, search],
  );
  const visible = paginateLibraryHomeItems(filtered, page, PAGE_SIZE);

  useEffect(() => {
    if (page !== visible.page) setPage(visible.page);
  }, [page, visible.page]);

  return (
    <main
      className="library-home"
      data-testid="library-home"
      data-view={startChoice ? 'start' : 'queue'}
    >
      <header className="library-home-heading" hidden={Boolean(startChoice)}>
        <div>
          <p className="eyebrow">{t('Library assets')}</p>
          <h2>{t('Resume governed library work')}</h2>
          <p>{t('Find the exact draft revision, understand what blocks it, and continue in context.')}</p>
        </div>
        <div className="library-home-primary-actions">
          <button
            type="button"
            className="primary"
            onClick={() => setStartChoice('quick')}
            data-testid="library-home-create"
          >
            {t('Create library')}
          </button>
          <button
            type="button"
            className="secondary"
            onClick={() => setStartChoice('discover')}
            data-testid="library-home-discover"
          >
            {t('Discover existing')}
          </button>
        </div>
      </header>

      {(routeError || loadError) && (
        <p className="library-route-error" role="alert">{routeError || loadError}</p>
      )}

      <section
        className="library-home-work-queue"
        aria-label={t('Library work queue')}
        hidden={Boolean(startChoice)}
      >
        <nav className="library-home-filters" aria-label={t('Library status filters')}>
          {LIBRARY_HOME_FILTERS.map((option) => (
            <button
              type="button"
              key={option.id}
              className={filter === option.id ? 'active' : ''}
              aria-pressed={filter === option.id}
              onClick={() => {
                setFilter(option.id);
                setPage(1);
              }}
              data-testid={`library-filter:${option.id}`}
            >
              <span>{t(option.label)}</span>
              <strong>{countLibraryHomeFilter(items, option.id)}</strong>
            </button>
          ))}
        </nav>

        <div className="library-home-toolbar">
          <label>
            <span>{t('Search libraries')}</span>
            <input
              type="search"
              value={search}
              onChange={(event) => {
                setSearch(event.target.value);
                setPage(1);
              }}
              placeholder={t('Name, id, owner, or draft')}
            />
          </label>
          <span>{t('{count} matching assets', { count: visible.total })}</span>
        </div>

        {loading && <p className="library-home-empty">{t('Loading durable library drafts...')}</p>}
        {!loading && visible.items.length === 0 && (
          <div className="library-home-empty" data-testid="library-home-empty">
            <strong>{t(drafts.length === 0 ? 'No durable drafts yet' : 'No assets match this view')}</strong>
            <span>
              {drafts.length === 0
                ? t('Create or discover a library; autosave will make it recoverable here.')
                : t('Change the status filter or search terms.')}
            </span>
          </div>
        )}
        {visible.items.length > 0 && (
          <div className="library-home-table" role="table" aria-label={t('Durable library drafts')}>
            <div className="library-home-table-head" role="row">
              <span role="columnheader">{t('Library / exact draft')}</span>
              <span role="columnheader">{t('Owner')}</span>
              <span role="columnheader">{t('Readiness')}</span>
              <span role="columnheader">{t('Updated')}</span>
              <span role="columnheader">{t('Action')}</span>
            </div>
            {visible.items.map((item) => (
              <div
                className="library-home-row"
                role="row"
                key={item.draft.draftId}
                data-testid={`library-home-row:${item.draft.draftId}`}
              >
                <span className="library-home-identity" role="cell">
                  <strong>{item.name}</strong>
                  <small>{item.draft.draftId} · {t('revision {revision}', { revision: item.draft.revision })}</small>
                  <code title={item.draft.fingerprint}>{shortFingerprint(item.draft.fingerprint)}</code>
                </span>
                <span className="library-home-owner" role="cell">
                  <strong>{item.owner || t('Owner unresolved')}</strong>
                  <small>{item.mine
                    ? t('My work')
                    : t('Last saved by {actor}', { actor: item.draft.savedBy || t('unknown') })}</small>
                </span>
                <span className="library-home-statuses" role="cell">
                  {item.assessmentPending && <em>{t('Checking readiness...')}</em>}
                  {!item.assessmentPending && item.statuses.length === 0 && (
                    <b data-state="ready">{t('No active blockers')}</b>
                  )}
                  {item.statuses.map((status) => (
                    <b data-state={statusTone(status)} key={status}>{t(statusLabel(status))}</b>
                  ))}
                  {item.assessmentError && <em title={item.assessmentError}>{t('Readiness unavailable')}</em>}
                </span>
                <span className="library-home-updated" role="cell">
                  <strong>{formatDate(item.draft.updatedAt, locale)}</strong>
                  <small>{t('{mode} source', { mode: item.draft.sourceMode.toLowerCase() })}</small>
                </span>
                <span role="cell">
                  <a className="primary compact" href={item.resumeHref}>
                    {t('Resume r{revision}', { revision: item.draft.revision })}
                  </a>
                </span>
              </div>
            ))}
          </div>
        )}

        {visible.pageCount > 1 && (
          <footer className="library-home-pagination">
            <button
              type="button"
              className="secondary compact"
              disabled={visible.page === 1}
              onClick={() => setPage((current) => current - 1)}
            >
              {t('Previous')}
            </button>
            <span>{t('Page {page} of {pages}', { page: visible.page, pages: visible.pageCount })}</span>
            <button
              type="button"
              className="secondary compact"
              disabled={visible.page === visible.pageCount}
              onClick={() => setPage((current) => current + 1)}
            >
              {t('Next')}
            </button>
          </footer>
        )}
      </section>

      {startChoice && (
        <section className="library-home-create-panel" data-testid="library-home-create-panel">
          <header>
            <div>
              <span>{t('New source')}</span>
              <strong>{t(startChoice === 'discover' ? 'Discover existing assets' : 'Create a library')}</strong>
            </div>
            <button type="button" className="secondary compact" onClick={() => setStartChoice(null)}>
              {t('Close')}
            </button>
          </header>
          <LibraryStartChoices
            key={startChoice}
            initialChoice={startChoice}
            onStart={onStart}
          />
        </section>
      )}
    </main>
  );
}

async function assessDrafts(
  drafts: VisualLibraryAuthoringDraft[],
  accept: (draftId: string, assessment: LibraryHomeAssessment) => void,
): Promise<void> {
  await mapWithConcurrency(drafts, 4, async (draft) => {
    const [preview, testGate] = await Promise.allSettled([
      previewLibraryAuthoringDraft(draft.draftId, draft.revision),
      fetchLibraryAuthoringTestGate(draft.draftId),
    ]);
    const errors = [preview, testGate]
      .filter((result) => result.status === 'rejected')
      .map((result) => result.reason instanceof Error ? result.reason.message : String(result.reason));
    accept(draft.draftId, {
      preview: preview.status === 'fulfilled' ? preview.value : undefined,
      testGate: testGate.status === 'fulfilled' ? testGate.value : undefined,
      pending: false,
      error: errors.join(' | '),
    });
  });
}

async function mapWithConcurrency<T>(
  values: T[],
  concurrency: number,
  task: (value: T) => Promise<void>,
): Promise<void> {
  let index = 0;
  const workers = Array.from(
    { length: Math.min(Math.max(1, concurrency), values.length) },
    async () => {
      while (index < values.length) {
        const current = values[index];
        index += 1;
        await task(current);
      }
    },
  );
  await Promise.all(workers);
}

function statusLabel(status: LibraryHomeStatus): string {
  switch (status) {
    case 'NEEDS_CONFIRMATION':
      return 'Needs confirmation';
    case 'RUNTIME_DRIFT':
      return 'Runtime drift';
    case 'TEST_GATE_INCOMPLETE':
      return 'Test gate incomplete';
    case 'OWNERSHIP_CONFLICT':
      return 'Ownership conflict';
  }
}

function statusTone(status: LibraryHomeStatus): string {
  return status === 'RUNTIME_DRIFT' || status === 'OWNERSHIP_CONFLICT'
    ? 'blocked'
    : 'attention';
}

function formatDate(value: string, locale: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat(locale, {
        dateStyle: 'medium',
        timeStyle: 'short',
      }).format(date);
}

function shortFingerprint(value: string): string {
  return value.length > 24 ? `${value.slice(0, 13)}...${value.slice(-7)}` : value;
}
