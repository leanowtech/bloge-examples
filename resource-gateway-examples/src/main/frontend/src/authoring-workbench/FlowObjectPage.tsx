import { useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { Boxes, Plus, Rocket, TestTube2, Trash2 } from 'lucide-react';

import { useI18n } from '../i18n/I18nProvider';
import { readApiResource } from './api';
import {
  listFlowDraftFixtures,
  publishFlow,
  readFlow,
  readFlowFixture,
  saveFlow,
  saveFlowFixture,
  simulateFlowFixture,
} from './flowApi';
import {
  buildFlowFixtureCommand,
  buildReusableFlowCommand,
  type FlowDraftRef,
  type FlowFormDraft,
  type ResolvedApiNode,
} from './flowModel';
import type { FixtureSetSummary, SimulationRun } from './model';

type FlowTab = 'design' | 'fixture' | 'simulation' | 'versions';

/** One shared object page for reusable Tool and Solution Flow drafts. */
export default function FlowObjectPage({ initialFlowId, initialKind }: {
  initialFlowId: string;
  initialKind: 'TOOL' | 'SOLUTION';
}) {
  const { t } = useI18n();
  const [draft, setDraft] = useState<FlowFormDraft>({
    flowId: initialFlowId, displayName: '', kind: initialKind, description: '',
  });
  const [nodes, setNodes] = useState<ResolvedApiNode[]>([]);
  const [resourceId, setResourceId] = useState('');
  const [strongEtag, setStrongEtag] = useState<string | null>(null);
  const [subject, setSubject] = useState<FlowDraftRef | null>(null);
  const [fixture, setFixture] = useState<FixtureSetSummary | null>(null);
  const [fixtureEtag, setFixtureEtag] = useState<string | null>(null);
  const [fixtureInput, setFixtureInput] = useState('{}');
  const [fixtureOutput, setFixtureOutput] = useState('{}');
  const [run, setRun] = useState<SimulationRun | null>(null);
  const [published, setPublished] = useState('');
  const [tab, setTab] = useState<FlowTab>('design');
  const [busy, setBusy] = useState(initialFlowId.length > 0);
  const [message, setMessage] = useState('');

  useEffect(() => {
    if (!initialFlowId) return;
    let cancelled = false;
    void readFlow(initialFlowId).then(async (stored) => {
      const restored = await Promise.all(stored.value.graph.nodes.map(async (node) => {
        if (node.use.kind !== 'API_RESOURCE') throw new Error('This simple page supports API Resource nodes.');
        const resource = await readApiResource(node.use.resourceId, node.use.revision);
        if (resource.value.fingerprint !== node.use.fingerprint) throw new Error('A Flow dependency has drifted.');
        return { nodeId: node.nodeId, label: node.label, resource: resource.value };
      }));
      if (cancelled) return;
      const exactSubject: FlowDraftRef = {
        kind: 'FLOW_DRAFT', draftId: stored.value.draftId,
        revision: stored.value.revision, fingerprint: stored.value.fingerprint,
      };
      setDraft({
        flowId: stored.value.flowId, displayName: stored.value.displayName,
        kind: stored.value.kind, description: stored.value.description,
      });
      setNodes(restored);
      setStrongEtag(stored.strongEtag);
      setSubject(exactSubject);
      const summaries = await listFlowDraftFixtures(exactSubject);
      if (summaries[0]) {
        const savedFixture = await readFlowFixture(summaries[0].fixtureSetId, summaries[0].revision);
        if (!cancelled) {
          setFixture(summaries[0]);
          setFixtureEtag(savedFixture.strongEtag);
          setFixtureInput(JSON.stringify(savedFixture.value.cases[0]?.input ?? {}, null, 2));
          const control = savedFixture.value.cases[0]?.controls[0];
          const material = control?.behavior.kind === 'RETURN'
            && control.behavior.material.kind === 'INLINE' ? control.behavior.material.value : {};
          setFixtureOutput(JSON.stringify(material, null, 2));
        }
      }
      setMessage(t('Loaded committed Flow.'));
    }).catch((failure: unknown) => {
      if (!cancelled) setMessage(errorMessage(failure));
    }).finally(() => {
      if (!cancelled) setBusy(false);
    });
    return () => { cancelled = true; };
  }, [initialFlowId, t]);

  const addResource = async () => {
    setBusy(true);
    setMessage('');
    try {
      const stored = await readApiResource(resourceId.trim());
      const nodeId = nextNodeId(nodes);
      setNodes([...nodes, { nodeId, label: stored.value.displayName, resource: stored.value }]);
      setResourceId('');
      setMessage(t('Added exact committed API Resource.'));
    } catch (failure) {
      setMessage(errorMessage(failure));
    } finally {
      setBusy(false);
    }
  };

  const save = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setMessage('');
    try {
      const command = buildReusableFlowCommand(draft, nodes);
      const result = await saveFlow(
        draft.flowId.trim(), command, strongEtag, operationKey('save-flow', draft.flowId),
      );
      setStrongEtag(result.strongEtag);
      setSubject(result.value.draft);
      setFixture(null);
      setFixtureEtag(null);
      setRun(null);
      setTab('fixture');
      setMessage(result.replayed ? t('The exact Flow save was replayed.') : t('Flow saved. Add its reusable Fixture.'));
      window.history.replaceState(null, '', `/workbench/?flowId=${encodeURIComponent(draft.flowId.trim())}`);
    } catch (failure) {
      setMessage(errorMessage(failure));
    } finally {
      setBusy(false);
    }
  };

  const saveFixtureAndSimulate = async () => {
    if (!subject) return;
    setBusy(true);
    setMessage('');
    try {
      const fixtureSetId = `${draft.flowId.trim()}.default`;
      const command = buildFlowFixtureCommand(
        subject, `${draft.displayName.trim()} default`, fixtureInput, fixtureOutput,
      );
      const saved = await saveFlowFixture(
        fixtureSetId, command, fixtureEtag, operationKey('save-flow-fixture', fixtureSetId),
      );
      setFixtureEtag(saved.strongEtag);
      setFixture({
        schemaVersion: 'bloge.fixtureSetSummary.v1', fixtureSetId: saved.value.fixtureSetId,
        revision: saved.value.revision, fingerprint: saved.value.fingerprint,
        displayName: command.displayName, subject: saved.value.subject,
        cases: saved.value.caseIds.map((caseId) => ({ caseId, name: 'Default' })),
        status: saved.value.status, statusRevision: saved.value.statusRevision,
      });
      const simulation = await simulateFlowFixture(
        saved.value.fixtureSetId, saved.value.revision, saved.value.caseIds[0],
        operationKey('simulate-flow', `${saved.value.fixtureSetId}-${saved.value.revision}`),
      );
      setRun(simulation);
      setTab('simulation');
      setMessage(t('Flow Fixture saved and simulated.'));
    } catch (failure) {
      setMessage(errorMessage(failure));
    } finally {
      setBusy(false);
    }
  };

  const runSavedFixture = async () => {
    const first = fixture?.cases[0];
    if (!fixture || !first) return;
    setBusy(true);
    try {
      setRun(await simulateFlowFixture(
        fixture.fixtureSetId, fixture.revision, first.caseId,
        operationKey('simulate-flow', `${fixture.fixtureSetId}-${fixture.revision}`),
      ));
      setTab('simulation');
      setMessage(t('Simulation completed from the saved Flow Fixture.'));
    } catch (failure) {
      setMessage(errorMessage(failure));
    } finally {
      setBusy(false);
    }
  };

  const publish = async () => {
    if (!subject) return;
    setBusy(true);
    try {
      const receipt = await publishFlow(draft.flowId.trim(), subject, operationKey('publish-flow', draft.flowId));
      setPublished(`${receipt.version.publicationId}@${receipt.version.revision}`);
      setMessage(t('Flow published as an immutable reusable version.'));
    } catch (failure) {
      setMessage(errorMessage(failure));
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="api-resource-object flow-object" data-testid="flow-object-page">
      <header className="api-resource-object-header">
        <div>
          <a href="/workbench/">← {t('All objects')}</a>
          <p className="eyebrow">{draft.kind === 'TOOL' ? 'Tool' : 'Solution'}</p>
          <h1>{draft.displayName || (draft.kind === 'TOOL' ? t('Create a tool') : t('Create a solution'))}</h1>
          <p>{t('Add API Resources in execution order. Matching fields are wired automatically.')}</p>
        </div>
        {strongEtag && <span className="object-status">{t('Saved')}</span>}
      </header>

      <nav className="object-tabs" aria-label={t('Object tasks')}>
        {(['design', 'fixture', 'simulation', 'versions'] as const).map((value) => (
          <button key={value} type="button" aria-current={tab === value ? 'page' : undefined}
            onClick={() => setTab(value)}>{t(tabLabel(value))}</button>
        ))}
      </nav>

      {tab === 'design' && (
        <form className="api-resource-design" onSubmit={save}>
          <section>
            <h2>{t('Flow identity')}</h2>
            <div className="object-form-grid">
              <Field label={t('Flow name')}><input data-testid="flow-name" required value={draft.displayName}
                onChange={(event) => setDraft({ ...draft, displayName: event.target.value })} /></Field>
              <Field label={t('Flow ID')}><input data-testid="flow-id" required value={draft.flowId}
                disabled={strongEtag !== null}
                onChange={(event) => setDraft({ ...draft, flowId: event.target.value })} /></Field>
              <Field label={t('Kind')}><select data-testid="flow-kind" value={draft.kind}
                onChange={(event) => setDraft({ ...draft, kind: event.target.value as FlowFormDraft['kind'] })}>
                <option value="TOOL">Tool</option><option value="SOLUTION">Solution</option>
              </select></Field>
            </div>
            <Field label={t('Description')}><textarea data-testid="flow-description" rows={3}
              value={draft.description}
              onChange={(event) => setDraft({ ...draft, description: event.target.value })} /></Field>
          </section>
          <section>
            <h2>{t('API steps')}</h2>
            <p>{t('Each step is pinned to the exact committed Resource revision. Reorder by removing and adding again.')}</p>
            <div className="flow-add-resource">
              <input data-testid="flow-resource-id" placeholder={t('API Resource ID')} value={resourceId}
                onChange={(event) => setResourceId(event.target.value)} />
              <button type="button" data-testid="add-flow-resource" disabled={busy || !resourceId.trim()}
                onClick={addResource}><Plus aria-hidden="true" /> {t('Add API')}</button>
            </div>
            <ol className="flow-node-list" data-testid="flow-node-list">
              {nodes.map((node, index) => (
                <li key={node.nodeId}>
                  <span>{index + 1}</span>
                  <div><strong>{node.label}</strong><small>{node.resource.resourceId}@{node.resource.revision}</small></div>
                  <button type="button" aria-label={`${t('Remove')} ${node.label}`}
                    onClick={() => setNodes(nodes.filter((value) => value.nodeId !== node.nodeId))}>
                    <Trash2 aria-hidden="true" />
                  </button>
                </li>
              ))}
            </ol>
          </section>
          <button className="primary-object-action" data-testid="save-flow" disabled={busy || nodes.length === 0}>
            <Boxes aria-hidden="true" /> {busy ? t('Saving...') : t('Save Flow')}
          </button>
        </form>
      )}

      {tab === 'fixture' && (
        <section className="object-task-panel" data-testid="flow-fixture-panel">
          <h2>{t('Reusable Flow Fixture')}</h2>
          <p>{t('Define one whole-flow input and returned output. Internal API calls stay unexecuted.')}</p>
          <div className="object-example-grid">
            <Field label={t('Fixture input')}><textarea data-testid="flow-fixture-input" rows={9}
              value={fixtureInput} onChange={(event) => setFixtureInput(event.target.value)} /></Field>
            <Field label={t('Fixture output')}><textarea data-testid="flow-fixture-output" rows={9}
              value={fixtureOutput} onChange={(event) => setFixtureOutput(event.target.value)} /></Field>
          </div>
          <button type="button" className="primary-object-action" data-testid="save-flow-fixture"
            disabled={busy || !subject} onClick={saveFixtureAndSimulate}>
            <TestTube2 aria-hidden="true" /> {busy ? t('Saving and simulating...') : t('Save Fixture and simulate')}
          </button>
          {fixture && <button type="button" data-testid="rerun-flow-fixture" disabled={busy}
            onClick={runSavedFixture}>{t('Run saved Fixture')}</button>}
          {fixture && <a data-testid="open-flow-fixture"
            href={`/workbench/?fixtureSetId=${encodeURIComponent(fixture.fixtureSetId)}`}>
            {t('Open Fixture object')}
          </a>}
        </section>
      )}

      {tab === 'simulation' && (
        <section className="object-task-panel" data-testid="flow-simulation-panel">
          <h2>{t('Simulation')}</h2>
          {run ? <>
            <div className="simulation-summary">
              <div><span>{t('Run')}</span><strong>{run.runId}</strong></div>
              <div><span>{t('Status')}</span><strong>{run.status}</strong></div>
              <div><span>{t('Execution')}</span><strong>{run.verdicts.execution}</strong></div>
            </div>
            <pre data-testid="flow-simulation-output">{JSON.stringify(run.output ?? null, null, 2)}</pre>
          </> : <p>{t('Save a Flow Fixture to simulate it without external effects.')}</p>}
        </section>
      )}

      {tab === 'versions' && (
        <section className="object-task-panel" data-testid="flow-version-panel">
          <h2>{t('Versions')}</h2>
          <p>{subject ? `${t('Draft revision')}: ${subject.revision}` : t('Save the Flow before publishing.')}</p>
          <button type="button" className="primary-object-action" data-testid="publish-flow"
            disabled={busy || !subject} onClick={publish}>
            <Rocket aria-hidden="true" /> {t('Publish reusable version')}
          </button>
          {published && <p data-testid="published-flow-version">{published}</p>}
        </section>
      )}
      {message && <p className="object-message" role="status" data-testid="flow-message">{message}</p>}
    </main>
  );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return <label className="object-field"><span>{label}</span>{children}</label>;
}

function nextNodeId(nodes: ResolvedApiNode[]): string {
  let index = nodes.length + 1;
  while (nodes.some((node) => node.nodeId === `step${index}`)) index += 1;
  return `step${index}`;
}

function tabLabel(tab: FlowTab): string {
  return ({ design: 'Design', fixture: 'Fixture', simulation: 'Simulation', versions: 'Versions' })[tab];
}

function operationKey(action: string, coordinate: string): string {
  const nonce = globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `${action}:${coordinate.trim() || 'new'}:${nonce}`;
}

function errorMessage(failure: unknown): string {
  return failure instanceof Error ? failure.message : 'The request did not complete.';
}
