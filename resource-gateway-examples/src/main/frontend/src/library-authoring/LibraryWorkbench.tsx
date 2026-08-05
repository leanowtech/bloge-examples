import {
  useCallback,
  useEffect,
  useRef,
  useState,
} from 'react';

import {
  BlogeApiRequestError,
  commitLibraryAuthoringDraft,
  fetchLibraryAuthoringCatalogs,
  fetchLibraryAuthoringDraft,
  fetchLibraryAuthoringDraftRevision,
  previewLibraryAuthoringDraft,
  saveLibraryAuthoringDraft,
} from '../api';
import { useI18n } from '../i18n/I18nProvider';
import type {
  VisualLibraryAuthoringCommitResult,
  VisualLibraryAuthoringCompileResult,
  VisualLibraryAuthoringDocument,
  VisualLibraryAuthoringDraft,
} from '../types';
import AssetTestTable, {
  type AssetTestLaunch,
} from './AssetTestTable';
import CanonicalContractPreview from './CanonicalContractPreview';
import FunctionBuilder from './FunctionBuilder';
import LibraryHome from './LibraryHome';
import LibraryTree from './LibraryTree';
import OperatorBuilder from './OperatorBuilder';
import SampleInferenceReview, {
  type SampleInferenceLaunch,
} from './SampleInferenceReview';
import SchemaTreeEditor from './SchemaTreeEditor';
import {
  addAsset,
  assetSelectionFromPath,
  removeAsset,
  renameAsset,
  replaceTypeFields,
  typeFields,
  type LibraryAssetKind,
  type LibraryAssetSelection,
} from './model';

type SaveState = 'idle' | 'dirty' | 'saving' | 'saved' | 'conflict' | 'error';

export default function LibraryWorkbench() {
  const { t } = useI18n();
  const [document, setDocument] = useState<VisualLibraryAuthoringDocument | null>(null);
  const [draftId, setDraftId] = useState('');
  const [revision, setRevision] = useState(0);
  const [selection, setSelection] = useState<LibraryAssetSelection>({ kind: 'library', key: '' });
  const [saveState, setSaveState] = useState<SaveState>('idle');
  const [saveMessage, setSaveMessage] = useState('');
  const [preview, setPreview] = useState<VisualLibraryAuthoringCompileResult | null>(null);
  const [previewBusy, setPreviewBusy] = useState(false);
  const [commitBusy, setCommitBusy] = useState(false);
  const [commitReason, setCommitReason] = useState('Reviewed in Library Workbench');
  const [commitResult, setCommitResult] = useState<VisualLibraryAuthoringCommitResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [inferenceLaunch, setInferenceLaunch] = useState<SampleInferenceLaunch | null>(null);
  const [testLaunch, setTestLaunch] = useState<AssetTestLaunch | null>(null);
  const [fixtureAvailable, setFixtureAvailable] = useState(false);
  const [startSource, setStartSource] = useState('');
  const [historicalDraft, setHistoricalDraft] = useState<VisualLibraryAuthoringDraft | null>(null);
  const [latestDraft, setLatestDraft] = useState<VisualLibraryAuthoringDraft | null>(null);
  const revisionRef = useRef(0);
  const currentDraftRef = useRef<VisualLibraryAuthoringDraft | null>(null);
  const lastSavedJsonRef = useRef('');
  const editEpochRef = useRef(0);
  const saveQueueRef = useRef<Promise<void>>(Promise.resolve());

  const installDraft = useCallback((
    draft: VisualLibraryAuthoringDraft,
    nextSelection: LibraryAssetSelection = { kind: 'library', key: '' },
  ) => {
    currentDraftRef.current = draft;
    revisionRef.current = draft.revision;
    lastSavedJsonRef.current = JSON.stringify(draft.document);
    setDraftId(draft.draftId);
    setRevision(draft.revision);
    setDocument(draft.document);
    setSelection(nextSelection);
    setSaveState('saved');
    setSaveMessage(`Saved revision ${draft.revision}`);
    setPreview(null);
    setCommitResult(null);
    setStartSource('');
  }, []);

  useEffect(() => {
    let active = true;
    fetchLibraryAuthoringCatalogs()
      .then((catalogs) => {
        if (active) {
          setFixtureAvailable(
            catalogs.features.governedFixturePersistence === true,
          );
        }
      })
      .catch(() => {
        if (active) {
          setFixtureAvailable(false);
        }
      });
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    const query = new URLSearchParams(window.location.search);
    const requestedDraftId = query.get('draftId')?.trim() ?? '';
    const requestedRevision = positiveRevision(query.get('revision'));
    if (!requestedDraftId) {
      return;
    }
    let active = true;
    setLoading(true);
    fetchLibraryAuthoringDraft(requestedDraftId)
      .then(async (current) => {
        if (!active) return;
        setLatestDraft(current);
        if (requestedRevision && requestedRevision !== current.revision) {
          const exact = await fetchLibraryAuthoringDraftRevision(
            requestedDraftId,
            requestedRevision,
          );
          if (!active) return;
          setHistoricalDraft(exact);
          installDraft(exact);
          return;
        }
        setHistoricalDraft(null);
        installDraft(current);
      })
      .catch((error) => {
        if (active) {
          setSaveState('error');
          setSaveMessage(error instanceof Error ? error.message : 'Failed to load draft.');
        }
      })
      .finally(() => {
        if (active) {
          setLoading(false);
        }
      });
    return () => {
      active = false;
    };
  }, [installDraft]);

  const persist = useCallback((
    snapshot: VisualLibraryAuthoringDocument,
    epoch: number,
  ): Promise<VisualLibraryAuthoringDraft> => {
    const serialized = JSON.stringify(snapshot);
    if (serialized === lastSavedJsonRef.current && currentDraftRef.current) {
      return Promise.resolve(currentDraftRef.current);
    }
    const task = saveQueueRef.current.then(async () => {
        if (serialized === lastSavedJsonRef.current && currentDraftRef.current) {
          return currentDraftRef.current;
        }
        setSaveState('saving');
        setSaveMessage('Saving...');
        const stored = await saveLibraryAuthoringDraft(
          draftId,
          revisionRef.current,
          snapshot,
          'QUICK',
        );
        currentDraftRef.current = stored;
        revisionRef.current = stored.revision;
        lastSavedJsonRef.current = serialized;
        setRevision(stored.revision);
        setLatestDraft(stored);
        replaceLibraryDraftLocation(stored.draftId, stored.revision);
        if (epoch === editEpochRef.current) {
          setSaveState('saved');
          setSaveMessage(`Saved revision ${stored.revision}`);
        }
        return stored;
      });
    saveQueueRef.current = task.then(
      () => undefined,
      (error) => {
        if (error instanceof BlogeApiRequestError && error.status === 412) {
          setSaveState('conflict');
          setSaveMessage('A newer revision exists. Reload before continuing.');
        } else {
          setSaveState('error');
          setSaveMessage(error instanceof Error ? error.message : 'Autosave failed.');
        }
      },
    );
    return task;
  }, [draftId]);

  const runPreview = useCallback(async (
    snapshot: VisualLibraryAuthoringDocument,
    epoch: number,
  ) => {
    setPreviewBusy(true);
    try {
      const stored = await persist(snapshot, epoch);
      const nextPreview = await previewLibraryAuthoringDraft(stored.draftId, stored.revision);
      if (epoch === editEpochRef.current) {
        setPreview(nextPreview);
      }
      return nextPreview;
    } catch {
      return null;
    } finally {
      if (epoch === editEpochRef.current) {
        setPreviewBusy(false);
      }
    }
  }, [persist]);

  useEffect(() => {
    if (!document || !draftId || saveState === 'conflict') {
      return undefined;
    }
    const epoch = editEpochRef.current;
    const timer = window.setTimeout(() => {
      void runPreview(document, epoch);
    }, 700);
    return () => window.clearTimeout(timer);
  }, [document, draftId, runPreview]);

  const changeDocument = useCallback((
    update: (current: VisualLibraryAuthoringDocument) => VisualLibraryAuthoringDocument,
  ) => {
    editEpochRef.current += 1;
    setDocument((current) => current ? update(current) : current);
    setSaveState('dirty');
    setSaveMessage('Unsaved changes');
    setPreview(null);
    setCommitResult(null);
  }, []);

  const start = (
    nextDocument: VisualLibraryAuthoringDocument,
    source: string,
    inference?: SampleInferenceLaunch,
  ) => {
    const id = `${nextDocument.library.id}-${draftSuffix()}`;
    editEpochRef.current += 1;
    revisionRef.current = 0;
    currentDraftRef.current = null;
    lastSavedJsonRef.current = '';
    setDraftId(id);
    setRevision(0);
    setDocument(nextDocument);
    setSelection(inference
      ? { kind: 'operator', key: inference.operatorKey }
      : { kind: 'library', key: '' });
    setSaveState('dirty');
    setSaveMessage(`New ${source} draft`);
    setPreview(null);
    setCommitResult(null);
    setInferenceLaunch(inference ?? null);
    setStartSource(source);
    setHistoricalDraft(null);
    setLatestDraft(null);
    replaceLibraryDraftLocation(id, 0);
  };

  const reload = async () => {
    if (!draftId) {
      return;
    }
    setLoading(true);
    try {
      const current = await fetchLibraryAuthoringDraft(draftId);
      setHistoricalDraft(null);
      setLatestDraft(current);
      installDraft(current);
      replaceLibraryDraftLocation(current.draftId, current.revision);
    } finally {
      setLoading(false);
    }
  };

  const validateNow = () => {
    if (document) {
      void runPreview(document, editEpochRef.current);
    }
  };

  const commit = async () => {
    if (!preview || preview.authoringRevision !== revisionRef.current) {
      return;
    }
    setCommitBusy(true);
    try {
      const result = await commitLibraryAuthoringDraft(
        draftId,
        revisionRef.current,
        preview,
        commitReason,
      );
      setCommitResult(result);
      setSaveMessage(`Design Catalog revision ${result.targetRevision} imported`);
    } catch (error) {
      setSaveState(error instanceof BlogeApiRequestError && error.status === 412
        ? 'conflict' : 'error');
      setSaveMessage(error instanceof Error ? error.message : 'Commit failed.');
    } finally {
      setCommitBusy(false);
    }
  };

  const focusDiagnostic = (authoringPath: string) => {
    setSelection(assetSelectionFromPath(authoringPath));
    window.setTimeout(() => {
      const candidates = [...documentQueryAll<HTMLElement>('[data-authoring-path]')];
      const target = candidates.find((element) => (
        element.dataset.authoringPath === authoringPath
        || authoringPath.startsWith(`${element.dataset.authoringPath}/`)
      ));
      target?.focus();
      target?.scrollIntoView({ block: 'center', behavior: 'smooth' });
    }, 0);
  };

  const prepareExactDraft = useCallback(() => {
    if (!document) {
      return Promise.reject(new Error('A library draft is required before testing.'));
    }
    return persist(document, editEpochRef.current);
  }, [document, persist]);
  const markRevisionConflict = useCallback(() => {
    setSaveState('conflict');
    setSaveMessage('A newer revision exists. Reload before continuing.');
  }, []);

  if (loading && !document) {
    return <main className="library-workbench-loading">{t('Loading library draft...')}</main>;
  }
  if (!document) {
    return (
      <LibraryHome
        routeError={saveState === 'error' ? saveMessage : ''}
        onStart={start}
      />
    );
  }
  if (historicalDraft) {
    return (
      <main className="library-history-view" data-testid="library-history-view">
        <header>
          <div>
            <p className="eyebrow">{t('Immutable authoring history')}</p>
            <h2>{document.library.name || document.library.id}</h2>
            <p>
              {t('Exact revision {revision} is open read-only. The mutable head is revision {head}.', {
                revision: historicalDraft.revision,
                head: latestDraft?.revision ?? t('unavailable'),
              })}
            </p>
          </div>
          <a className="secondary compact" href="/libraries/">{t('Library home')}</a>
        </header>
        <section className="library-history-verdict">
          <span>{t('Historical snapshot')}</span>
          <strong>{t('No edits can overwrite this revision')}</strong>
          <p>
            {t('Resume the current head to continue the same draft, or fork this snapshot into a new independently autosaved draft.')}
          </p>
        </section>
        <dl className="library-history-summary">
          <div><dt>{t('Draft')}</dt><dd>{historicalDraft.draftId}</dd></div>
          <div><dt>{t('Revision')}</dt><dd>{historicalDraft.revision}</dd></div>
          <div><dt>{t('Owner')}</dt><dd>{document.library.owner || t('Unresolved')}</dd></div>
          <div><dt>{t('Operators')}</dt><dd>{Object.keys(document.operators ?? {}).length}</dd></div>
          <div><dt>{t('Functions')}</dt><dd>{Object.keys(document.functions ?? {}).length}</dd></div>
          <div><dt>{t('Saved by')}</dt><dd>{historicalDraft.savedBy || t('Unknown')}</dd></div>
        </dl>
        <div className="library-history-actions">
          {latestDraft && (
            <a
              className="primary"
              href={`/libraries/?draftId=${encodeURIComponent(latestDraft.draftId)}&revision=${latestDraft.revision}`}
            >
              {t('Resume latest r{revision}', { revision: latestDraft.revision })}
            </a>
          )}
          <button
            type="button"
            className="secondary"
            onClick={() => start(
              structuredClone(historicalDraft.document),
              `fork:r${historicalDraft.revision}`,
            )}
          >
            {t('Fork this revision')}
          </button>
        </div>
        <details className="library-history-technical">
          <summary>{t('Technical coordinates')}</summary>
          <dl>
            <div><dt>{t('Fingerprint')}</dt><dd><code>{historicalDraft.fingerprint}</code></dd></div>
            <div><dt>{t('Created')}</dt><dd>{historicalDraft.createdAt}</dd></div>
            <div><dt>{t('Updated')}</dt><dd>{historicalDraft.updatedAt}</dd></div>
            <div><dt>{t('Source mode')}</dt><dd>{historicalDraft.sourceMode}</dd></div>
          </dl>
        </details>
      </main>
    );
  }

  const add = (kind: Exclude<LibraryAssetKind, 'library'>) => {
    const result = addAsset(document, kind);
    editEpochRef.current += 1;
    setDocument(result.document);
    setSelection(result.selection);
    setSaveState('dirty');
    setSaveMessage('Unsaved changes');
    setPreview(null);
    setCommitResult(null);
  };
  const rename = (nextKey: string) => {
    const result = renameAsset(document, selection, nextKey);
    if (result.document !== document) {
      changeDocument(() => result.document);
      setSelection(result.selection);
    }
  };
  const remove = () => {
    changeDocument((current) => removeAsset(current, selection));
    setSelection({ kind: 'library', key: '' });
  };
  const installInferenceDraft = (draft: VisualLibraryAuthoringDraft) => {
    const operatorKey = inferenceLaunch?.operatorKey ?? '';
    editEpochRef.current += 1;
    installDraft(draft, { kind: 'operator', key: operatorKey });
    setInferenceLaunch(null);
  };

  return (
    <main className="library-workbench" data-testid="library-workbench">
      <header className="library-command-bar">
        <div>
          <a href="/libraries/" aria-label={t('Open Library home')} title={t('Open Library home')}>←</a>
          <div>
            <strong>{document.library.name || document.library.id}</strong>
            <span>
              {draftId} / {t('revision {revision}', { revision })}
              {startSource.startsWith('example:') ? t(' · Design-only example') : ''}
            </span>
          </div>
        </div>
        <div className={`library-save-state ${saveState}`} role="status" data-testid="library-save-state">
          <span aria-hidden="true" />
          <strong>{t(saveState === 'conflict' ? 'Conflict' : saveState)}</strong>
          <small>{t(saveMessage)}</small>
          {saveState === 'conflict' && (
            <button type="button" className="secondary compact" onClick={() => void reload()}>
              {t('Reload')}
            </button>
          )}
        </div>
        <nav>
          <a className="secondary compact" href="/author/">{t('Graph Author')}</a>
          <button
            type="button"
            className="secondary compact"
            onClick={validateNow}
            disabled={previewBusy || saveState === 'conflict'}
          >
            {t('Validate')}
          </button>
        </nav>
      </header>

      <div className="library-workbench-grid">
        <LibraryTree
          document={document}
          selection={selection}
          onSelect={setSelection}
          onAdd={add}
        />
        <section className="library-builder-scroll">
          {renderBuilder(
            document,
            selection,
            changeDocument,
            rename,
            remove,
            (direction) => {
              if (selection.kind === 'operator') {
                setInferenceLaunch({ operatorKey: selection.key, direction });
              }
            },
            () => {
              if (selection.kind === 'operator' || selection.kind === 'function') {
                setTestLaunch({ kind: selection.kind, assetRef: selection.key });
              }
            },
            t,
          )}
        </section>
        <CanonicalContractPreview
          preview={preview}
          previewBusy={previewBusy}
          commitBusy={commitBusy}
          commitReason={commitReason}
          commitResult={commitResult}
          onCommitReasonChange={setCommitReason}
          onValidate={validateNow}
          onCommit={() => void commit()}
          onDiagnostic={focusDiagnostic}
        />
      </div>
      {inferenceLaunch && document.operators?.[inferenceLaunch.operatorKey] && (
        <SampleInferenceReview
          {...inferenceLaunch}
          operator={document.operators[inferenceLaunch.operatorKey]}
          prepareDraft={prepareExactDraft}
          fixtureAvailable={fixtureAvailable}
          onApplied={installInferenceDraft}
          onConflict={markRevisionConflict}
          onClose={() => setInferenceLaunch(null)}
        />
      )}
      {testLaunch && (
        <AssetTestTable
          {...testLaunch}
          prepareDraft={prepareExactDraft}
          fixtureAvailable={fixtureAvailable}
          onConflict={markRevisionConflict}
          onClose={() => setTestLaunch(null)}
        />
      )}
    </main>
  );
}

function renderBuilder(
  document: VisualLibraryAuthoringDocument,
  selection: LibraryAssetSelection,
  changeDocument: (
    update: (current: VisualLibraryAuthoringDocument) => VisualLibraryAuthoringDocument,
  ) => void,
  rename: (nextKey: string) => void,
  remove: () => void,
  inferSamples: (direction: 'INPUT' | 'OUTPUT') => void,
  openTests: () => void,
  t: (source: string, values?: Record<string, string | number>) => string,
) {
  if (selection.kind === 'operator') {
    const operator = document.operators?.[selection.key];
    if (operator) {
      return (
        <OperatorBuilder
          operatorKey={selection.key}
          operator={operator}
          onRename={rename}
          onChange={(nextOperator) => changeDocument((current) => ({
            ...current,
            operators: { ...(current.operators ?? {}), [selection.key]: nextOperator },
          }))}
          onRemove={remove}
          onInferSamples={inferSamples}
          onOpenTests={openTests}
        />
      );
    }
  }
  if (selection.kind === 'function') {
    const fn = document.functions?.[selection.key];
    if (fn) {
      return (
        <FunctionBuilder
          functionKey={selection.key}
          fn={fn}
          onRename={rename}
          onChange={(nextFunction) => changeDocument((current) => ({
            ...current,
            functions: { ...(current.functions ?? {}), [selection.key]: nextFunction },
          }))}
          onRemove={remove}
          onOpenTests={openTests}
        />
      );
    }
  }
  if (selection.kind === 'type') {
    const type = document.types?.[selection.key];
    if (type) {
      return (
        <div className="library-task-builder" data-testid="type-builder">
          <header className="library-builder-heading">
            <div><span>{t('Named Type')}</span><h2>{selection.key}</h2></div>
            <button type="button" className="danger compact" onClick={remove}>{t('Delete')}</button>
          </header>
          <section className="library-builder-section">
            <header><h3>{t('Identity')}</h3><span>{t('Reusable schema')}</span></header>
            <label className="library-single-field">
              <span>{t('Type name')}</span>
              <input
                defaultValue={selection.key}
                onBlur={(event) => rename(event.target.value)}
                data-authoring-path={`/types/${pointer(selection.key)}`}
              />
            </label>
          </section>
          <section className="library-builder-section">
            <SchemaTreeEditor
              title={t('{name} fields', { name: selection.key })}
              fields={typeFields(type)}
              basePath={`/types/${pointer(selection.key)}/fields`}
              onChange={(fields) => changeDocument((current) => ({
                ...current,
                types: {
                  ...(current.types ?? {}),
                  [selection.key]: replaceTypeFields(current.types?.[selection.key], fields),
                },
              }))}
            />
          </section>
        </div>
      );
    }
  }
  return (
    <div className="library-task-builder" data-testid="library-metadata-builder">
      <header className="library-builder-heading">
        <div><span>{t('Library')}</span><h2>{document.library.name || document.library.id}</h2></div>
      </header>
      <section className="library-builder-section">
        <header><h3>{t('Identity & Ownership')}</h3><span>{t('Required for governance')}</span></header>
        <div className="library-form-grid">
          <label>
            <span>{t('Library id')}</span>
            <input
              value={document.library.id}
              readOnly
              data-authoring-path="/library/id"
              title={t('Library id remains stable after draft creation')}
            />
          </label>
          <label>
            <span>{t('Version')}</span>
            <input
              value={document.library.version ?? ''}
              onChange={(event) => changeDocument((current) => ({
                ...current,
                library: { ...current.library, version: event.target.value },
              }))}
              data-authoring-path="/library/version"
            />
          </label>
          <label>
            <span>{t('Name')}</span>
            <input
              value={document.library.name ?? ''}
              onChange={(event) => changeDocument((current) => ({
                ...current,
                library: { ...current.library, name: event.target.value },
              }))}
              data-authoring-path="/library/name"
            />
          </label>
          <label>
            <span>{t('Owner')}</span>
            <input
              value={document.library.owner ?? ''}
              onChange={(event) => changeDocument((current) => ({
                ...current,
                library: { ...current.library, owner: event.target.value },
              }))}
              data-authoring-path="/library/owner"
            />
          </label>
        </div>
      </section>
      <section className="library-builder-section library-summary">
        <header><h3>{t('Library Contents')}</h3><span>{t('Current draft')}</span></header>
        <dl>
          <div><dt>{t('Types')}</dt><dd>{Object.keys(document.types ?? {}).length}</dd></div>
          <div><dt>{t('Operators')}</dt><dd>{Object.keys(document.operators ?? {}).length}</dd></div>
          <div><dt>{t('Functions')}</dt><dd>{Object.keys(document.functions ?? {}).length}</dd></div>
        </dl>
      </section>
    </div>
  );
}

function draftSuffix(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID().slice(0, 8);
  }
  return Date.now().toString(36);
}

function documentQueryAll<TElement extends Element>(selector: string): NodeListOf<TElement> {
  return document.querySelectorAll<TElement>(selector);
}

function pointer(value: string): string {
  return value.replace(/~/g, '~0').replace(/\//g, '~1');
}

function positiveRevision(value: string | null): number {
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : 0;
}

function replaceLibraryDraftLocation(draftId: string, revision: number): void {
  const query = new URLSearchParams({ draftId });
  if (revision > 0) query.set('revision', String(revision));
  window.history.replaceState({}, '', `/libraries/?${query.toString()}`);
}
