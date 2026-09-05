import { useEffect, useMemo, useState, type FormEvent, type ReactNode } from 'react';
import { Braces, Boxes, PlugZap, Sparkles, TestTube2 } from 'lucide-react';

import { useI18n } from '../i18n/I18nProvider';
import FlowObjectPage from './FlowObjectPage';
import FixtureObjectPage from './FixtureObjectPage';
import CallerDirectedSimulationPanel from './CallerDirectedSimulationPanel';
import type { LegacyFixtureReauthorPreview, LegacyReusableFlowReauthorPreview } from './flowModel';
import {
  listApiResourceFixtures,
  listApiConnections,
  previewOpenApi,
  readAuthoringAvailability,
  readLegacyAssetMigrationInventory,
  readLegacyMigrationAssessment,
  readLegacyApiResourcePreview,
  readApiResource,
  saveApiResource,
  simulateFixtureCase,
} from './api';
import {
  buildApiResourceSaveCommand,
  formDraftFromOpenApiOperation,
  formDraftFromLegacyPreview,
  formDraftFromSpec,
  type ApiResourceFormDraft,
  type ApiResourceRef,
  type ApiConnectionView,
  type FixtureSetSummary,
  type LegacyAssetMigrationInventory,
  type LegacyAssetMigrationItem,
  type LegacyMigrationAssessment,
  type LegacyApiResourceReauthorPreview,
  type OpenApiPreview,
  type SimulationRun,
} from './model';
import './authoringWorkbench.css';
import IntentWorkbench from '../workbench/IntentWorkbench';
import type { FourEntityDraft, IntentExpressionInput } from '../workbench/intentModel';

const EMPTY_DRAFT: ApiResourceFormDraft = {
  resourceId: '',
  displayName: '',
  connectionMode: 'CREATE',
  connectionId: '',
  connectionDisplayName: '',
  connectionBaseUrl: '',
  method: 'GET',
  path: '/',
  requestExample: '{\n  "id": "customer-1"\n}',
  responseExample: '{\n  "name": "Ada",\n  "status": "active"\n}',
  importedResource: null,
};

type ObjectTab = 'design' | 'fixture' | 'simulation' | 'versions';

/** Unified entry and first API Resource object page for the simplified authoring model. */
export default function AuthoringWorkbench() {
  const { t } = useI18n();
  const [availability, setAvailability] = useState<Awaited<ReturnType<typeof readAuthoringAvailability>> | null>(null);
  const params = useMemo(() => new URLSearchParams(window.location.search), []);
  const requestedResourceId = params.get('resourceId')?.trim() || '';
  const requestedFlowId = params.get('flowId')?.trim() || '';
  const requestedFixtureSetId = params.get('fixtureSetId')?.trim() || '';
  const createApi = params.get('create') === 'api';
  const createFlow = params.get('create') === 'flow';
  const createBusinessSolution = params.get('create') === 'business-solution';
  const legacyInventory = params.get('legacy') === 'inventory';
  const legacyResourceId = params.get('legacyResourceId')?.trim() || '';
  const legacyFlowKind = params.get('legacyFlowKind');
  const legacyFlowId = params.get('legacyFlowId')?.trim() || '';
  const legacyFlowRevision = Number(params.get('legacyFlowRevision'));
  const legacyFixtureDraftId = params.get('legacyFixtureDraftId')?.trim() || '';
  const legacyFixtureRevision = Number(params.get('legacyFixtureRevision'));
  const flowKind = params.get('kind') === 'SOLUTION' ? 'SOLUTION' : 'TOOL';

  useEffect(() => {
    let cancelled = false;
    void readAuthoringAvailability().then((value) => {
      if (!cancelled) setAvailability(value);
    }).catch(() => {
      if (!cancelled) setAvailability({
        schemaVersion: 'bloge.authoringAvailability.v1', apiResource: false, reusableFlow: false,
      });
    });
    return () => { cancelled = true; };
  }, []);

  if (!availability) return <main className="simple-authoring-home"><p>{t('Loading...')}</p></main>;
  if ((requestedResourceId || requestedFixtureSetId || createApi || legacyInventory || legacyResourceId)
      && !availability.apiResource) {
    return <AuthoringUnavailable objectName="API Resource"
      enableCommand="RG_API_RESOURCE_AUTHORING_ENABLED=true" />;
  }
  if ((requestedFlowId || createFlow || createBusinessSolution) && !availability.reusableFlow) {
    return <AuthoringUnavailable objectName="Reusable Flow"
      enableCommand="RG_REUSABLE_FLOW_AUTHORING_ENABLED=true" />;
  }

  if (requestedFixtureSetId) {
    return <FixtureObjectPage initialFixtureSetId={requestedFixtureSetId} />;
  }
  if (legacyInventory) {
    return <LegacyAssetInventoryPage />;
  }
  if (createBusinessSolution) {
    return <IntentWorkbench sessionId="browser-intent-session" authorId="business-owner"
      contextFingerprint="context:provided-by-agent-host"
      compile={compileWithConnectedAgent} />;
  }
  if (requestedFlowId || createFlow) {
    const normalizedLegacyKind: LegacyReusableFlowReauthorPreview['source']['kind'] | null
      = legacyFlowKind === 'REUSABLE_FLOW_DRAFT'
      || legacyFlowKind === 'REUSABLE_FLOW_VERSION' ? legacyFlowKind : null;
    const initialLegacyFlow = legacyFlowId && Number.isSafeInteger(legacyFlowRevision)
      && legacyFlowRevision > 0 && normalizedLegacyKind
      ? { sourceKind: normalizedLegacyKind, sourceId: legacyFlowId, sourceRevision: legacyFlowRevision }
      : null;
    const initialLegacyFixture: LegacyFixtureReauthorPreview['source'] | null = legacyFixtureDraftId
      && Number.isSafeInteger(legacyFixtureRevision) && legacyFixtureRevision > 0
      ? { draftId: legacyFixtureDraftId, revision: legacyFixtureRevision } : null;
    const initialTab = params.get('tab') === 'fixture' ? 'fixture' : 'design';
    return <FlowObjectPage initialFlowId={requestedFlowId} initialKind={flowKind}
      initialLegacyFlow={initialLegacyFlow} initialLegacyFixture={initialLegacyFixture}
      initialTab={initialTab} />;
  }
  if (!requestedResourceId && !createApi) {
    return <AuthoringHome />;
  }
  return <ApiResourceObjectPage initialResourceId={requestedResourceId}
    initialLegacyResourceId={legacyResourceId} t={t} />;
}

function AuthoringUnavailable({ objectName, enableCommand }: {
  objectName: 'API Resource' | 'Reusable Flow';
  enableCommand: string;
}) {
  const { t } = useI18n();
  return (
    <main className="simple-authoring-home" data-testid="authoring-unavailable">
      <header>
        <p className="eyebrow">Resource Gateway</p>
        <h1>{t('{object} authoring is not enabled for this deployment.', { object: objectName })}</h1>
        <p>{t('Apply the authoring migrations, enable the deployment feature, and restart the service.')}</p>
        <code>{enableCommand}</code>
      </header>
    </main>
  );
}

function AuthoringHome() {
  const { t } = useI18n();
  const [inventory, setInventory] = useState<LegacyAssetMigrationInventory | null>(null);
  const [inventoryError, setInventoryError] = useState(false);
  useEffect(() => {
    let cancelled = false;
    void readLegacyAssetMigrationInventory().then((value) => {
      if (!cancelled) setInventory(value);
    }).catch(() => {
      if (!cancelled) setInventoryError(true);
    });
    return () => { cancelled = true; };
  }, []);
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
        <a href="/workbench/?create=business-solution" data-testid="express-business-solution">
          <Sparkles aria-hidden="true" />
          <strong>Describe a business solution</strong>
          <span>Answer business questions while the connected Codex Agent builds the verified draft.</span>
        </a>
      </section>
      <section className="legacy-assets-summary" data-testid="legacy-assets-summary">
        <div>
          <h2>Existing assets</h2>
          {inventory && <p>{t('Review')}: {inventory.summary.total} · {t('Needs repair')}: {inventory.summary.needsRepair} · {t('Legacy')}: {inventory.summary.legacyOnly}</p>}
          {!inventory && !inventoryError && <p>{t('Loading...')}</p>}
          {inventoryError && <p>Migration inventory unavailable.</p>}
        </div>
        <a href="/workbench/?legacy=inventory" data-testid="open-legacy-inventory">
          Review migration list
        </a>
      </section>
    </main>
  );
}

async function compileWithConnectedAgent(input: IntentExpressionInput): Promise<FourEntityDraft> {
  const host = globalThis as typeof globalThis & {
    blogeIntentCompiler?: (request: IntentExpressionInput) => Promise<FourEntityDraft>;
  };
  if (!host.blogeIntentCompiler) throw new Error('Connected Agent compiler unavailable');
  return host.blogeIntentCompiler(input);
}

function LegacyAssetInventoryPage() {
  const { t } = useI18n();
  const [inventory, setInventory] = useState<LegacyAssetMigrationInventory | null>(null);
  const [assessment, setAssessment] = useState<LegacyMigrationAssessment | null>(null);
  const [message, setMessage] = useState('');
  useEffect(() => {
    let cancelled = false;
    void Promise.all([readLegacyAssetMigrationInventory(), readLegacyMigrationAssessment()]).then((values) => {
      if (!cancelled) {
        setInventory(values[0]);
        setAssessment(values[1].value);
      }
    }).catch((failure: unknown) => {
      if (!cancelled) setMessage(errorMessage(failure));
    });
    return () => { cancelled = true; };
  }, []);
  return (
    <main className="legacy-asset-inventory" data-testid="legacy-asset-inventory">
      <header className="api-resource-object-header">
        <div>
          <a href="/workbench/">← {t('All objects')}</a>
          <p className="eyebrow">Compatibility</p>
          <h1>Existing assets</h1>
          <p>Nothing is migrated automatically. Review each exact legacy coordinate.</p>
        </div>
      </header>
      {message && <p role="alert">{message}</p>}
      {!inventory && !message && <p>{t('Loading...')}</p>}
      {inventory && (
        <>
          {assessment && <p className="legacy-migration-coverage" data-testid="legacy-migration-coverage">
            {assessment.coverage.classified} / {assessment.coverage.total} classified · {' '}
            {assessment.failures.length} require action · snapshot {' '}
            <code>{assessment.inventoryFingerprint.slice(0, 19)}…</code>
          </p>}
          <dl className="legacy-inventory-counts" data-testid="legacy-inventory-counts">
            <div><dt>{t('Ready')}</dt><dd>{inventory.summary.readyToReauthor}</dd></div>
            <div><dt>{t('Needs repair')}</dt><dd>{inventory.summary.needsRepair}</dd></div>
            <div><dt>{t('Legacy')}</dt><dd>{inventory.summary.legacyOnly}</dd></div>
          </dl>
          <ul className="legacy-inventory-list">
            {inventory.items.map((item) => <LegacyInventoryItem key={`${item.kind}:${item.sourceId}:${item.sourceRevision}`} item={item} t={t} />)}
          </ul>
        </>
      )}
    </main>
  );
}

function LegacyInventoryItem({ item, t }: {
  item: LegacyAssetMigrationItem;
  t: (source: string) => string;
}) {
  return (
    <li data-testid={`legacy-item:${item.kind}:${item.sourceId}`}>
      <div>
        <span className={`legacy-status legacy-status-${item.status.toLowerCase()}`}>{item.status}</span>
        <h2>{item.displayName}</h2>
        <p>{item.kind} · {item.sourceId}{item.sourceRevision > 0 ? ` · r${item.sourceRevision}` : ''}</p>
        <small>{item.reasonCodes.join(' · ')}</small>
      </div>
      <a href={item.action.path}>{t(actionLabel(item.action.kind))}</a>
    </li>
  );
}

function actionLabel(kind: LegacyAssetMigrationItem['action']['kind']): string {
  if (kind === 'REAUTHOR_RESOURCE') return 'Connect an API';
  if (kind === 'REAUTHOR_FLOW') return 'Create a tool';
  if (kind === 'REPAIR_SOURCE') return 'Review';
  if (kind === 'REAUTHOR_FIXTURE') return 'Fixture';
  return 'Open';
}

function ApiResourceObjectPage({ initialResourceId, initialLegacyResourceId, t }: {
  initialResourceId: string;
  initialLegacyResourceId: string;
  t: (source: string) => string;
}) {
  const [draft, setDraft] = useState<ApiResourceFormDraft>({
    ...EMPTY_DRAFT,
    resourceId: initialResourceId,
  });
  const [strongEtag, setStrongEtag] = useState<string | null>(null);
  const [resourceRevision, setResourceRevision] = useState<number | null>(null);
  const [fixture, setFixture] = useState<FixtureSetSummary | null>(null);
  const [resourceSubject, setResourceSubject] = useState<ApiResourceRef | null>(null);
  const [run, setRun] = useState<SimulationRun | null>(null);
  const [activeTab, setActiveTab] = useState<ObjectTab>('design');
  const [busy, setBusy] = useState(initialResourceId.length > 0 || initialLegacyResourceId.length > 0);
  const [message, setMessage] = useState('');
  const [openApiDocument, setOpenApiDocument] = useState('');
  const [openApiPreview, setOpenApiPreview] = useState<OpenApiPreview | null>(null);
  const [previewBusy, setPreviewBusy] = useState(false);
  const [connections, setConnections] = useState<ApiConnectionView[]>([]);
  const [legacyPreview, setLegacyPreview] = useState<LegacyApiResourceReauthorPreview | null>(null);

  useEffect(() => {
    let cancelled = false;
    void listApiConnections().then((values) => {
      if (!cancelled) setConnections(values);
    }).catch((failure: unknown) => {
      if (!cancelled) setMessage(errorMessage(failure));
    });
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    if (!initialResourceId) return;
    let cancelled = false;
    void readApiResource(initialResourceId).then(async (stored) => {
      if (cancelled) return;
      setDraft(formDraftFromSpec(stored.value));
      setStrongEtag(stored.strongEtag);
      setResourceRevision(stored.value.revision);
      setResourceSubject({
        kind: 'API_RESOURCE', resourceId: stored.value.resourceId,
        revision: stored.value.revision, fingerprint: stored.value.fingerprint,
      });
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

  useEffect(() => {
    if (!initialLegacyResourceId || initialResourceId) return;
    let cancelled = false;
    void readLegacyApiResourcePreview(initialLegacyResourceId).then((preview) => {
      if (cancelled) return;
      setLegacyPreview(preview);
      setDraft(formDraftFromLegacyPreview(preview));
      setMessage(t('Review the legacy operation and choose a Connection before saving.'));
    }).catch((failure: unknown) => {
      if (!cancelled) setMessage(errorMessage(failure));
    }).finally(() => {
      if (!cancelled) setBusy(false);
    });
    return () => { cancelled = true; };
  }, [initialLegacyResourceId, initialResourceId, t]);

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
      setResourceSubject(save.value.resource);
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

  const previewDocument = async () => {
    setPreviewBusy(true);
    setMessage('');
    try {
      const preview = await previewOpenApi(openApiDocument);
      setOpenApiPreview(preview);
      setMessage(t('Choose an operation.'));
    } catch (failure) {
      setOpenApiPreview(null);
      setMessage(errorMessage(failure));
    } finally {
      setPreviewBusy(false);
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
          {legacyPreview && (
            <section className="legacy-reauthor-preview" data-testid="legacy-reauthor-preview">
              <h2>{t('Legacy Resource review')}</h2>
              <p>{t('Nothing has been migrated. Review this safe projection, choose a Connection, then save.')}</p>
              <ul>{legacyPreview.diagnostics.map((diagnostic) => (
                <li key={diagnostic.code}><strong>{diagnostic.code}</strong> · {t(diagnostic.message)}</li>
              ))}</ul>
            </section>
          )}
          <section className="openapi-import" data-testid="openapi-import">
            <h2>{t('Import')} OpenAPI</h2>
            <Field label="OpenAPI">
              <textarea data-testid="openapi-document" rows={7} value={openApiDocument}
                onChange={(event) => setOpenApiDocument(event.target.value)} />
            </Field>
            <button type="button" data-testid="preview-openapi" disabled={previewBusy || !openApiDocument.trim()}
              onClick={previewDocument}>
              {previewBusy ? t('Loading...') : t('Preview')}
            </button>
            {openApiPreview && (
              <ul className="openapi-operation-list" data-testid="openapi-operation-list">
                {openApiPreview.operations.map((operation) => (
                  <li key={operation.operationId}>
                    <span><strong>{operation.suggestedResource.displayName}</strong>
                      <small>{operation.method} {operation.path}</small></span>
                    <button type="button" data-testid={`use-openapi-operation-${operation.operationId}`}
                      onClick={() => setDraft(formDraftFromOpenApiOperation(draft, operation))}>
                      {t('Use')}
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>
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
              <Field label={t('Connection')}>
                <select data-testid="api-connection-mode" value={draft.connectionMode}
                  onChange={(event) => setDraft({ ...draft,
                    connectionMode: event.target.value as ApiResourceFormDraft['connectionMode'] })}>
                  <option value="CREATE">{t('Create')}</option>
                  <option value="EXISTING">{t('Existing')}</option>
                </select>
              </Field>
              {draft.connectionMode === 'EXISTING' ? (
                <Field label={t('Connection ID')}>
                  <select data-testid="api-connection-id" value={draft.connectionId}
                    onChange={(event) => setDraft({ ...draft, connectionId: event.target.value })} required>
                    <option value="">{t('Choose a Connection')}</option>
                    {connections.map((connection) => (
                      <option key={connection.connectionId} value={connection.connectionId}>
                        {connection.displayName} · {connection.baseUrl}
                      </option>
                    ))}
                    {draft.connectionId && !connections.some((value) => value.connectionId === draft.connectionId)
                      && <option value={draft.connectionId}>{draft.connectionId}</option>}
                  </select>
                </Field>
              ) : (
                <>
                  <Field label={t('Name')}>
                    <input data-testid="api-connection-name" value={draft.connectionDisplayName}
                      onChange={(event) => setDraft({ ...draft, connectionDisplayName: event.target.value })}
                      required />
                  </Field>
                  <Field label={t('URL')}>
                    <input data-testid="api-connection-base-url" value={draft.connectionBaseUrl}
                      placeholder="https://api.example.com"
                      onChange={(event) => setDraft({ ...draft, connectionBaseUrl: event.target.value })}
                      required />
                  </Field>
                </>
              )}
            </div>
          </section>
          <section>
            <h2>{t('Operation')}</h2>
            <div className="object-form-grid operation-grid">
              <Field label={t('Method')}>
                <select data-testid="api-method" value={draft.method}
                  onChange={(event) => setDraft({ ...draft,
                    method: event.target.value as ApiResourceFormDraft['method'], importedResource: null })}>
                  {['GET', 'POST', 'PUT', 'DELETE'].map((method) => <option key={method}>{method}</option>)}
                </select>
              </Field>
              <Field label={t('Path')}>
                <input data-testid="api-path" value={draft.path}
                  onChange={(event) => setDraft({ ...draft, path: event.target.value, importedResource: null })} required />
              </Field>
            </div>
          </section>
          <section>
            <h2>{t('Examples')}</h2>
            <p>{t('One request and response example generate the contract and the private Default Fixture.')}</p>
            <div className="object-example-grid">
              <Field label={t('Request example')}>
                <textarea data-testid="api-request-example" rows={9} value={draft.requestExample}
                  onChange={(event) => setDraft({ ...draft,
                    requestExample: event.target.value, importedResource: null })} />
              </Field>
              <Field label={t('Response example')}>
                <textarea data-testid="api-response-example" rows={9} value={draft.responseExample}
                  onChange={(event) => setDraft({ ...draft,
                    responseExample: event.target.value, importedResource: null })} />
              </Field>
            </div>
          </section>
          {draft.importedResource && (
            <p className="openapi-binding-summary" data-testid="openapi-binding-summary">
              {t('Transport bindings')}: {draft.importedResource.operation.bindings
                .map((binding) => `${binding.from} → ${binding.to.location}:${binding.to.name}`).join(' · ')}
            </p>
          )}
          <button className="primary-object-action" data-testid="save-and-simulate" disabled={busy}>
            <TestTube2 aria-hidden="true" />
            {busy ? t('Saving and simulating...') : t('Save and simulate')}
          </button>
        </form>
      )}

      {activeTab === 'fixture' && <FixturePanel fixture={fixture} busy={busy} onRun={runFixture} t={t} />}
      {activeTab === 'simulation' && <>
        <SimulationPanel run={run} t={t} />
        {resourceSubject && <CallerDirectedSimulationPanel subject={resourceSubject}
          initialInput={draft.requestExample}
          targets={[{
            key: 'subject', label: draft.displayName || resourceSubject.resourceId,
            target: { kind: 'SUBJECT' }, fixtures: fixture ? [fixture] : [],
          }]} />}
      </>}
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
      <a data-testid="open-resource-fixture" href={`/workbench/?fixtureSetId=${encodeURIComponent(fixture.fixtureSetId)}`}>
        {t('Open Fixture object')}
      </a>
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
