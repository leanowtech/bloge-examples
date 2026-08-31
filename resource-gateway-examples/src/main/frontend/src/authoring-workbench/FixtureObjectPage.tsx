import { useEffect, useState } from 'react';
import { Save, TestTube2 } from 'lucide-react';

import { useI18n } from '../i18n/I18nProvider';
import { buildFixtureObjectCommand, fixtureObjectDraft, type FixtureObjectDraft } from './fixtureModel';
import { readFixtureSet, saveFixtureSet, simulateFixtureSetCase } from './flowApi';
import type { FixtureSetView } from './flowModel';
import type { SimulationRun } from './model';

/** Independent Fixture object page backed only by exact Fixture and Simulation protocols. */
export default function FixtureObjectPage({ initialFixtureSetId }: { initialFixtureSetId: string }) {
  const { t } = useI18n();
  const [view, setView] = useState<FixtureSetView | null>(null);
  const [draft, setDraft] = useState<FixtureObjectDraft | null>(null);
  const [strongEtag, setStrongEtag] = useState<string | null>(null);
  const [selectedCaseId, setSelectedCaseId] = useState('');
  const [run, setRun] = useState<SimulationRun | null>(null);
  const [busy, setBusy] = useState(true);
  const [message, setMessage] = useState('');

  useEffect(() => {
    let cancelled = false;
    void readFixtureSet(initialFixtureSetId).then((stored) => {
      if (cancelled) return;
      setView(stored.value);
      setDraft(fixtureObjectDraft(stored.value));
      setStrongEtag(stored.strongEtag);
      setSelectedCaseId(stored.value.cases[0]?.caseId ?? '');
      setMessage('');
    }).catch((failure: unknown) => {
      if (!cancelled) setMessage(errorMessage(failure));
    }).finally(() => {
      if (!cancelled) setBusy(false);
    });
    return () => { cancelled = true; };
  }, [initialFixtureSetId, t]);

  const runCase = async (fixtureSet = view, caseId = selectedCaseId) => {
    if (!fixtureSet || !caseId) return;
    setBusy(true);
    setMessage('');
    try {
      setRun(await simulateFixtureSetCase(
        fixtureSet.fixtureSetId, fixtureSet.revision, caseId,
        operationKey('simulate-fixture', `${fixtureSet.fixtureSetId}-${fixtureSet.revision}-${caseId}`),
      ));
      setMessage(t('Simulation completed from the exact saved Fixture Case.'));
    } catch (failure) {
      setMessage(errorMessage(failure));
    } finally {
      setBusy(false);
    }
  };

  const saveAndRun = async () => {
    if (!view || !draft || !strongEtag) return;
    setBusy(true);
    setMessage('');
    try {
      const command = buildFixtureObjectCommand(view, draft);
      const saved = await saveFixtureSet(
        view.fixtureSetId, command, strongEtag,
        operationKey('save-fixture', view.fixtureSetId),
      );
      const updated: FixtureSetView = {
        ...view, revision: saved.value.revision, fingerprint: saved.value.fingerprint,
        status: saved.value.status, statusRevision: saved.value.statusRevision,
        subject: saved.value.subject, displayName: command.displayName, cases: command.cases,
      };
      setView(updated);
      setStrongEtag(saved.strongEtag);
      await runCase(updated, command.cases[0].caseId);
      setMessage(saved.replayed
        ? t('The exact Fixture save was replayed; its simulation is shown.')
        : t('Fixture saved and simulated.'));
    } catch (failure) {
      setMessage(errorMessage(failure));
      setBusy(false);
    }
  };

  if (!view) {
    return <main className="api-resource-object fixture-object" data-testid="fixture-object-page">
      <a href="/workbench/">← {t('All objects')}</a>
      <p className="object-message" role="status">{message || t('Loading Fixture...')}</p>
    </main>;
  }

  const subjectLink = view.subject.kind === 'API_RESOURCE'
    ? `/workbench/?resourceId=${encodeURIComponent(view.subject.resourceId)}` : '/workbench/';

  return (
    <main className="api-resource-object fixture-object" data-testid="fixture-object-page">
      <header className="api-resource-object-header">
        <div>
          <a href="/workbench/">← {t('All objects')}</a>
          <p className="eyebrow">Fixture</p>
          <h1>{view.displayName}</h1>
          <p>{t('Save and simulate to see an immutable run result.')}</p>
        </div>
        <span className="object-status" data-testid="fixture-status">{view.status}</span>
      </header>

      <section className="object-task-panel" data-testid="fixture-authority">
        <h2>{t('Fixture authority')}</h2>
        <dl>
          <div><dt>{t('Fixture Set')}</dt><dd>{view.fixtureSetId}</dd></div>
          <div><dt>{t('Revision')}</dt><dd>{view.revision}</dd></div>
          <div><dt>{t('Subject')}</dt><dd><a data-testid="fixture-subject-link" href={subjectLink}>
            {subjectLabel(view)}
          </a></dd></div>
          <div><dt>{t('Lifecycle')}</dt><dd>{view.status} · r{view.statusRevision}</dd></div>
        </dl>
      </section>

      <section className="object-task-panel" data-testid="fixture-case-panel">
        <h2>{t('Cases')}</h2>
        <label className="object-field"><span>{t('Case')}</span>
          <select data-testid="fixture-case" value={selectedCaseId}
            onChange={(event) => setSelectedCaseId(event.target.value)}>
            {view.cases.map((fixtureCase) => (
              <option key={fixtureCase.caseId} value={fixtureCase.caseId}>{fixtureCase.name}</option>
            ))}
          </select>
        </label>
        {draft && strongEtag ? <>
          <label className="object-field"><span>{t('Fixture name')}</span>
            <input value={draft.displayName}
              onChange={(event) => setDraft({ ...draft, displayName: event.target.value })} /></label>
          <div className="object-example-grid">
            <label className="object-field"><span>{t('Fixture input')}</span>
              <textarea data-testid="fixture-object-input" rows={9} value={draft.inputSource}
                onChange={(event) => setDraft({ ...draft, inputSource: event.target.value })} /></label>
            <label className="object-field"><span>{t('Fixture output')}</span>
              <textarea data-testid="fixture-object-output" rows={9} value={draft.outputSource}
                onChange={(event) => setDraft({ ...draft, outputSource: event.target.value })} /></label>
          </div>
          <button type="button" className="primary-object-action" data-testid="save-fixture-object"
            disabled={busy} onClick={saveAndRun}>
            <Save aria-hidden="true" /> {busy ? t('Saving and simulating...') : t('Save Fixture and simulate')}
          </button>
        </> : <p>{t('This Fixture is governed by its parent object and is read-only here.')}</p>}
        <button type="button" data-testid="run-fixture-case" disabled={busy || !selectedCaseId}
          onClick={() => { void runCase(); }}>
          <TestTube2 aria-hidden="true" /> {busy ? t('Running...') : t('Run saved Fixture')}
        </button>
      </section>

      {run && <section className="object-task-panel" data-testid="fixture-simulation-panel">
        <h2>{t('Simulation')}</h2>
        <div className="simulation-summary">
          <div><span>{t('Run')}</span><strong>{run.runId}</strong></div>
          <div><span>{t('Status')}</span><strong>{run.status}</strong></div>
          <div><span>{t('Execution')}</span><strong>{run.verdicts.execution}</strong></div>
        </div>
        <pre data-testid="fixture-simulation-output">{JSON.stringify(run.output ?? null, null, 2)}</pre>
      </section>}
      {message && <p className="object-message" role="status" data-testid="fixture-message">{message}</p>}
    </main>
  );
}

function subjectLabel(view: FixtureSetView): string {
  const subject = view.subject;
  if (subject.kind === 'API_RESOURCE') return `API Resource · ${subject.resourceId}@${subject.revision}`;
  if (subject.kind === 'FLOW_DRAFT') return `Flow Draft · ${subject.draftId}@${subject.revision}`;
  return `Flow Version · ${subject.publicationId}@${subject.revision}`;
}

function operationKey(action: string, coordinate: string): string {
  const nonce = globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `${action}:${coordinate}:${nonce}`;
}

function errorMessage(failure: unknown): string {
  return failure instanceof Error ? failure.message : 'The request did not complete.';
}
