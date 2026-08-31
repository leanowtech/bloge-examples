import { useEffect, useMemo, useState, type FormEvent, type ReactNode } from 'react';
import { Braces, Boxes, PlugZap, TestTube2 } from 'lucide-react';

import { useI18n } from '../i18n/I18nProvider';
import FlowObjectPage from './FlowObjectPage';
import { listApiResourceFixtures, readApiResource, saveApiResource, simulateFixtureCase } from './api';
import {
  buildApiResourceSaveCommand,
  formDraftFromSpec,
  type ApiResourceFormDraft,
  type FixtureSetSummary,
  type SimulationRun,
} from './model';
import './authoringWorkbench.css';

const EMPTY_DRAFT: ApiResourceFormDraft = {
  resourceId: '',
  displayName: '',
  connectionId: '',
  method: 'GET',
  path: '/',
  requestExample: '{\n  "id": "customer-1"\n}',
  responseExample: '{\n  "name": "Ada",\n  "status": "active"\n}',
};

type ObjectTab = 'design' | 'fixture' | 'simulation' | 'versions';

/** Unified entry and first API Resource object page for the simplified authoring model. */
export default function AuthoringWorkbench() {
  const { t } = useI18n();
  const params = useMemo(() => new URLSearchParams(window.location.search), []);
  const requestedResourceId = params.get('resourceId')?.trim() || '';
  const requestedFlowId = params.get('flowId')?.trim() || '';
  const createApi = params.get('create') === 'api';
  const createFlow = params.get('create') === 'flow';
  const flowKind = params.get('kind') === 'SOLUTION' ? 'SOLUTION' : 'TOOL';

  if (requestedFlowId || createFlow) {
    return <FlowObjectPage initialFlowId={requestedFlowId} initialKind={flowKind} />;
  }
  if (!requestedResourceId && !createApi) {
    return <AuthoringHome />;
  }
  return <ApiResourceObjectPage initialResourceId={requestedResourceId} t={t} />;
}

function AuthoringHome() {
  const { t } = useI18n();
  return (
    <main className="simple-authoring-home" data-testid="simple-authoring-home">
      <header>
        <p className="eyebrow">Resource Gateway</p>
        <h1>{t('What do you want to build?')}</h1>
        <p>{t('Start with one object. Fixture and simulation stay on its page.')}</p>
      </header>
      <section className="simple-authoring-intents" aria-label={t('Create an authoring object')}>
        <a href="/workbench/?create=api" data-testid="create-api-resource">
          <PlugZap aria-hidden="true" />
          <strong>{t('Connect an API')}</strong>
          <span>{t('Describe one operation, add examples, and simulate it immediately.')}</span>
        </a>
        <a href="/workbench/?create=flow&kind=TOOL" data-testid="create-tool">
          <Boxes aria-hidden="true" />
          <strong>{t('Create a tool')}</strong>
          <span>{t('Compose API resources and published flows into a reusable DAG.')}</span>
        </a>
        <a href="/workbench/?create=flow&kind=SOLUTION" data-testid="create-solution">
          <Braces aria-hidden="true" />
          <strong>{t('Create a solution')}</strong>
          <span>{t('Build a reusable solution with the same Flow contract and runtime.')}</span>
        </a>
      </section>
    </main>
  );
}

function ApiResourceObjectPage({ initialResourceId, t }: {
  initialResourceId: string;
  t: (source: string) => string;
}) {
  const [draft, setDraft] = useState<ApiResourceFormDraft>({
    ...EMPTY_DRAFT,
    resourceId: initialResourceId,
  });
  const [strongEtag, setStrongEtag] = useState<string | null>(null);
  const [resourceRevision, setResourceRevision] = useState<number | null>(null);
  const [fixture, setFixture] = useState<FixtureSetSummary | null>(null);
  const [run, setRun] = useState<SimulationRun | null>(null);
  const [activeTab, setActiveTab] = useState<ObjectTab>('design');
  const [busy, setBusy] = useState(initialResourceId.length > 0);
  const [message, setMessage] = useState('');

  useEffect(() => {
    if (!initialResourceId) return;
    let cancelled = false;
    void readApiResource(initialResourceId).then(async (stored) => {
      if (cancelled) return;
      setDraft(formDraftFromSpec(stored.value));
      setStrongEtag(stored.strongEtag);
      setResourceRevision(stored.value.revision);
      const fixtures = await listApiResourceFixtures(stored.value);
      if (!cancelled) setFixture(fixtures[0] ?? null);
      setMessage(t('Loaded committed Resource.'));
    }).catch((failure: unknown) => {
      if (!cancelled) setMessage(errorMessage(failure));
    }).finally(() => {
      if (!cancelled) setBusy(false);
    });
    return () => { cancelled = true; };
  }, [initialResourceId, t]);

  const saveAndSimulate = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setMessage('');
    setRun(null);
    try {
      const command = buildApiResourceSaveCommand(draft);
      const save = await saveApiResource(
        draft.resourceId.trim(), command, strongEtag, operationKey('save', draft.resourceId),
      );
      setStrongEtag(save.strongEtag);
      setResourceRevision(save.value.resource.revision);
      const fixture = save.value.defaultFixture;
      const firstCase = fixture?.cases[0];
      if (!fixture || !firstCase) throw new Error('The Resource was saved without a Default Fixture Case.');
      setFixture({
        schemaVersion: 'bloge.fixtureSetSummary.v1', fixtureSetId: fixture.fixtureSetId,
        revision: fixture.revision, fingerprint: fixture.fingerprint,
        displayName: `${draft.displayName.trim()} default`, subject: save.value.resource,
        cases: fixture.cases.map((value) => ({ caseId: value.caseId, name: value.exampleName })),
        status: 'PRIVATE_DRAFT', statusRevision: 1,
      });
      const simulation = await simulateFixtureCase(
        fixture.fixtureSetId, fixture.revision, firstCase.caseId,
        operationKey('simulate', `${fixture.fixtureSetId}-${fixture.revision}-${firstCase.caseId}`),
      );
      setRun(simulation);
      setActiveTab('simulation');
      setMessage(save.replayed
        ? t('The saved command was replayed; the exact Fixture simulation is shown.')
        : t('Resource and Default Fixture saved; simulation completed.'));
      window.history.replaceState(null, '', `/workbench/?resourceId=${encodeURIComponent(draft.resourceId.trim())}`);
    } catch (failure) {
      setMessage(errorMessage(failure));
    } finally {
      setBusy(false);
    }
  };

  const runFixture = async () => {
    const firstCase = fixture?.cases[0];
    if (!fixture || !firstCase) return;
    setBusy(true);
    setMessage('');
    try {
      setRun(await simulateFixtureCase(fixture.fixtureSetId, fixture.revision, firstCase.caseId,
        operationKey('simulate', `${fixture.fixtureSetId}-${fixture.revision}-${firstCase.caseId}`)));
      setActiveTab('simulation');
      setMessage(t('Simulation completed from the exact saved Fixture Case.'));
    } catch (failure) {
      setMessage(errorMessage(failure));
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="api-resource-object" data-testid="api-resource-object">
      <header className="api-resource-object-header">
        <div>
          <a href="/workbench/">← {t('All objects')}</a>
          <p className="eyebrow">API Resource</p>
          <h1>{draft.displayName || t('Connect an API')}</h1>
          <p>{t('Describe the operation with examples. The server owns contracts, fixtures, and runtime projections.')}</p>
        </div>
        {strongEtag && <span className="object-status">{t('Saved')}</span>}
      </header>

      <nav className="object-tabs" aria-label={t('Object tasks')}>
        {(['design', 'fixture', 'simulation', 'versions'] as const).map((tab) => (
          <button key={tab} type="button" aria-current={activeTab === tab ? 'page' : undefined}
            onClick={() => setActiveTab(tab)}>
            {t(tabLabel(tab))}
          </button>
        ))}
      </nav>

      {activeTab === 'design' && (
        <form className="api-resource-design" onSubmit={saveAndSimulate}>
          <section>
            <h2>{t('API identity')}</h2>
            <div className="object-form-grid">
              <Field label={t('API name')}>
                <input data-testid="api-name" value={draft.displayName}
                  onChange={(event) => setDraft({ ...draft, displayName: event.target.value })} required />
              </Field>
              <Field label={t('Resource ID')}>
                <input data-testid="api-resource-id" value={draft.resourceId}
                  onChange={(event) => setDraft({ ...draft, resourceId: event.target.value })}
                  disabled={strongEtag !== null} required />
              </Field>
              <Field label={t('Connection ID')}>
                <input data-testid="api-connection-id" value={draft.connectionId}
                  onChange={(event) => setDraft({ ...draft, connectionId: event.target.value })} required />
              </Field>
            </div>
          </section>
          <section>
            <h2>{t('Operation')}</h2>
            <div className="object-form-grid operation-grid">
              <Field label={t('Method')}>
                <select data-testid="api-method" value={draft.method}
                  onChange={(event) => setDraft({ ...draft, method: event.target.value as ApiResourceFormDraft['method'] })}>
                  {['GET', 'POST', 'PUT', 'DELETE'].map((method) => <option key={method}>{method}</option>)}
                </select>
              </Field>
              <Field label={t('Path')}>
                <input data-testid="api-path" value={draft.path}
                  onChange={(event) => setDraft({ ...draft, path: event.target.value })} required />
              </Field>
            </div>
          </section>
          <section>
            <h2>{t('Examples')}</h2>
            <p>{t('One request and response example generate the contract and the private Default Fixture.')}</p>
            <div className="object-example-grid">
              <Field label={t('Request example')}>
                <textarea data-testid="api-request-example" rows={9} value={draft.requestExample}
                  onChange={(event) => setDraft({ ...draft, requestExample: event.target.value })} />
              </Field>
              <Field label={t('Response example')}>
                <textarea data-testid="api-response-example" rows={9} value={draft.responseExample}
                  onChange={(event) => setDraft({ ...draft, responseExample: event.target.value })} />
              </Field>
            </div>
          </section>
          <button className="primary-object-action" data-testid="save-and-simulate" disabled={busy}>
            <TestTube2 aria-hidden="true" />
            {busy ? t('Saving and simulating...') : t('Save and simulate')}
          </button>
        </form>
      )}

      {activeTab === 'fixture' && <FixturePanel fixture={fixture} busy={busy} onRun={runFixture} t={t} />}
      {activeTab === 'simulation' && <SimulationPanel run={run} t={t} />}
      {activeTab === 'versions' && (
        <section className="object-task-panel" data-testid="resource-version-panel">
          <h2>{t('Version')}</h2>
          <p>{resourceRevision
            ? `${t('Current revision')}: ${resourceRevision}`
            : t('Save the Resource to create its first immutable revision.')}</p>
        </section>
      )}
      {message && <p className="object-message" role="status" data-testid="object-message">{message}</p>}
    </main>
  );
}

function FixturePanel({ fixture, busy, onRun, t }: {
  fixture: FixtureSetSummary | null;
  busy: boolean;
  onRun: () => void;
  t: (source: string) => string;
}) {
  if (!fixture) return <EmptyPanel text={t('Save the Resource to create its private Default Fixture.')} />;
  return (
    <section className="object-task-panel" data-testid="resource-fixture-panel">
      <h2>{t('Default Fixture')}</h2>
      <dl>
        <div><dt>{t('Fixture Set')}</dt><dd>{fixture.fixtureSetId}</dd></div>
        <div><dt>{t('Revision')}</dt><dd>{fixture.revision}</dd></div>
        <div><dt>{t('Case')}</dt><dd>{fixture.cases[0]?.caseId}</dd></div>
        <div><dt>{t('Behavior')}</dt><dd>RETURN · OUTPUT_LEVEL</dd></div>
      </dl>
      <button type="button" className="primary-object-action" data-testid="run-saved-fixture"
        disabled={busy} onClick={onRun}>
        <TestTube2 aria-hidden="true" /> {busy ? t('Running...') : t('Run saved Fixture')}
      </button>
    </section>
  );
}

function SimulationPanel({ run, t }: { run: SimulationRun | null; t: (source: string) => string }) {
  if (!run) return <EmptyPanel text={t('Save and simulate to see an immutable run result.')} />;
  return (
    <section className="object-task-panel" data-testid="resource-simulation-panel">
      <div className="simulation-summary">
        <div><span>{t('Run')}</span><strong>{run.runId}</strong></div>
        <div><span>{t('Status')}</span><strong>{run.status}</strong></div>
        <div><span>{t('Execution')}</span><strong>{run.verdicts.execution}</strong></div>
      </div>
      <h3>{t('Output')}</h3>
      <pre data-testid="simulation-output">{JSON.stringify(run.output ?? null, null, 2)}</pre>
      <h3>{t('Evidence')}</h3>
      <ul>
        <li>{t('Contract')}: {run.verdicts.contract}</li>
        <li>{t('Assertions')}: {run.verdicts.assertions}</li>
        <li>{t('Governance')}: {run.verdicts.governance}</li>
      </ul>
    </section>
  );
}

function EmptyPanel({ text }: { text: string }) {
  return <section className="object-task-panel object-empty"><p>{text}</p></section>;
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return <label className="object-field"><span>{label}</span>{children}</label>;
}

function tabLabel(tab: ObjectTab): string {
  return ({ design: 'Design', fixture: 'Fixture', simulation: 'Simulation', versions: 'Versions' })[tab];
}

function operationKey(action: string, coordinate: string): string {
  const nonce = globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `${action}:${coordinate.trim() || 'new'}:${nonce}`;
}

function errorMessage(failure: unknown): string {
  return failure instanceof Error ? failure.message : 'The request did not complete.';
}
