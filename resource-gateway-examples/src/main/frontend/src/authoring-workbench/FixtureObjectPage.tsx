import { useEffect, useState } from 'react';
import { Save, Share2, TestTube2 } from 'lucide-react';

import { useI18n } from '../i18n/I18nProvider';
import { buildFixtureObjectCommand, fixtureObjectDraft, type FixtureObjectDraft } from './fixtureModel';
import { readFixtureSet, saveFixtureSet, shareFixtureSet, simulateFixtureSetCase } from './flowApi';
import type { FixtureSetView, FixtureShareCommand } from './flowModel';
import type { SimulationRun } from './model';

/** Independent Fixture object page backed only by exact Fixture and Simulation protocols. */
export default function FixtureObjectPage({ initialFixtureSetId }: { initialFixtureSetId: string }) {
  const { locale, t } = useI18n();
  const shareText = fixtureShareText(locale);
  const [view, setView] = useState<FixtureSetView | null>(null);
  const [draft, setDraft] = useState<FixtureObjectDraft | null>(null);
  const [strongEtag, setStrongEtag] = useState<string | null>(null);
  const [selectedCaseId, setSelectedCaseId] = useState('');
  const [run, setRun] = useState<SimulationRun | null>(null);
  const [classification, setClassification] =
    useState<FixtureShareCommand['policy']['classification']>('INTERNAL');
  const [retentionDays, setRetentionDays] = useState(30);
  const [redactionProfile, setRedactionProfile] = useState('default-v1');
  const [redactionPaths, setRedactionPaths] = useState('');
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
    if (!fixtureSet || !caseId || !runnable(fixtureSet)) return;
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

  const share = async () => {
    if (!view || view.status !== 'PRIVATE_DRAFT' || !draft || !strongEtag) return;
    setBusy(true);
    setMessage('');
    try {
      const paths = redactionPaths.split('\n').map((path) => path.trim()).filter(Boolean);
      if (paths.some((path) => !path.startsWith('/') || path === '/')) {
        throw new Error(shareText.invalidPaths);
      }
      const command: FixtureShareCommand = {
        schemaVersion: 'bloge.fixtureShareCommand.v1',
        source: {
          fixtureSetId: view.fixtureSetId, revision: view.revision,
          fingerprint: view.fingerprint, statusRevision: view.statusRevision,
        },
        policy: {
          classification, retentionDays,
          redaction: { profileVersion: redactionProfile.trim(), paths },
        },
      };
      const shared = await shareFixtureSet(
        view.fixtureSetId, command, strongEtag,
        operationKey('share-fixture', `${view.fixtureSetId}-${view.revision}`),
      );
      const pending = await readFixtureSet(view.fixtureSetId, shared.value.revision);
      setView(pending.value);
      setDraft(fixtureObjectDraft(pending.value));
      setStrongEtag(pending.strongEtag ?? shared.strongEtag);
      setSelectedCaseId(pending.value.cases[0]?.caseId ?? '');
      setRun(null);
      setMessage(`${shareText.submitted} ${shared.value.reviewRequestId}`);
    } catch (failure) {
      setMessage(errorMessage(failure));
    } finally {
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
  const editable = view.status === 'PRIVATE_DRAFT' && draft !== null && strongEtag !== null;
  const canRun = runnable(view);

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
        {editable && draft ? <>
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
        <button type="button" data-testid="run-fixture-case" disabled={busy || !selectedCaseId || !canRun}
          onClick={() => { void runCase(); }}>
          <TestTube2 aria-hidden="true" /> {busy ? t('Running...') : t('Run saved Fixture')}
        </button>
      </section>

      {editable && <section className="object-task-panel" data-testid="fixture-share-panel">
        <h2>{shareText.share}</h2>
        <p>{shareText.description}</p>
        <div className="object-example-grid">
          <label className="object-field"><span>{t('Classification')}</span>
            <select data-testid="fixture-share-classification" value={classification}
              onChange={(event) => setClassification(
                event.target.value as FixtureShareCommand['policy']['classification'],
              )}>
              <option value="INTERNAL">INTERNAL</option>
              <option value="CONFIDENTIAL">CONFIDENTIAL</option>
              <option value="RESTRICTED">RESTRICTED</option>
            </select>
          </label>
          <label className="object-field"><span>{t('Retention')}</span>
            <input data-testid="fixture-share-retention" type="number" min="1" max="365"
              value={retentionDays}
              onChange={(event) => setRetentionDays(Number(event.target.value))} />
          </label>
          <label className="object-field"><span>{shareText.redactionProfile}</span>
            <input data-testid="fixture-share-redaction-profile" value={redactionProfile}
              onChange={(event) => setRedactionProfile(event.target.value)} />
          </label>
          <label className="object-field"><span>{t('Redaction paths')}</span>
            <textarea data-testid="fixture-share-redaction-paths" rows={4} value={redactionPaths}
              placeholder="/customer/email"
              onChange={(event) => setRedactionPaths(event.target.value)} />
          </label>
        </div>
        <button type="button" className="primary-object-action" data-testid="share-fixture-object"
          disabled={busy || retentionDays < 1 || retentionDays > 365 || !redactionProfile.trim()}
          onClick={() => { void share(); }}>
          <Share2 aria-hidden="true" /> {busy ? shareText.submitting : shareText.share}
        </button>
      </section>}

      {view.status === 'SHARING_PENDING' && <section className="object-task-panel"
        data-testid="fixture-sharing-pending">
        <h2>{shareText.pending}</h2>
        <p>{shareText.pendingDetail}</p>
      </section>}

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

function runnable(view: FixtureSetView): boolean {
  return view.status === 'PRIVATE_DRAFT' || view.status === 'TEAM_AVAILABLE';
}

function fixtureShareText(locale: 'en' | 'zh-CN') {
  return locale === 'zh-CN' ? {
    share: '共享给团队',
    description: '创建受保护的修订版并提交独立评审。',
    redactionProfile: '脱敏规则版本',
    submitting: '正在提交评审...',
    submitted: 'Fixture 已提交评审，原私有修订版仍可按精确版本访问。',
    pending: '等待评审',
    pendingDetail: '受保护修订版在独立评审通过前不能运行或复用。',
    invalidPaths: '脱敏路径必须使用非根 JSON Pointer，并以 / 开头。',
  } : {
    share: 'Share with team',
    description: 'Create a protected revision and submit it for independent review.',
    redactionProfile: 'Redaction profile',
    submitting: 'Submitting for review...',
    submitted: 'Fixture submitted for review. The private source revision remains available.',
    pending: 'Review pending',
    pendingDetail: 'This protected revision cannot run or be reused until an independent reviewer approves it.',
    invalidPaths: 'Redaction paths must be non-root JSON Pointers and start with /.',
  };
}

function operationKey(action: string, coordinate: string): string {
  const nonce = globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `${action}:${coordinate}:${nonce}`;
}

function errorMessage(failure: unknown): string {
  return failure instanceof Error ? failure.message : 'The request did not complete.';
}
