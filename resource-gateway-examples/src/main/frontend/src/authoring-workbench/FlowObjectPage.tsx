import { useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { Boxes, Plus, Rocket, TestTube2, Trash2 } from 'lucide-react';

import { useI18n } from '../i18n/I18nProvider';
import {
  listComposableCatalog,
  listFlowFixtures,
  publishFlow,
  readLegacyFixtureReauthorPreview,
  readLegacyReusableFlowPreview,
  readFlow,
  readLatestFlowVersion,
  readFlowFixture,
  saveFlow,
  saveFlowFixture,
  simulateFlowFixture,
} from './flowApi';
import {
  buildFlowFixtureCommand,
  buildParentFlowFixtureCommand,
  buildReusableFlowCommand,
  type FlowDraftRef,
  type FlowFormDraft,
  type FlowVersionRef,
  type LegacyFixtureReauthorPreview,
  type LegacyReusableFlowReauthorPreview,
  type ReusableFlowCommand,
  type ComposableCatalogItem,
  type ResolvedFlowNode,
} from './flowModel';
import type { FixtureSetSummary, SimulationRun } from './model';

type FlowTab = 'design' | 'fixture' | 'simulation' | 'versions';

/** One shared object page for reusable Tool and Solution Flow drafts. */
export default function FlowObjectPage({
  initialFlowId, initialKind, initialLegacyFlow, initialLegacyFixture, initialTab,
}: {
  initialFlowId: string;
  initialKind: 'TOOL' | 'SOLUTION';
  initialLegacyFlow: {
    sourceKind: LegacyReusableFlowReauthorPreview['source']['kind'];
    sourceId: string;
    sourceRevision: number;
  } | null;
  initialLegacyFixture: LegacyFixtureReauthorPreview['source'] | null;
  initialTab: 'design' | 'fixture';
}) {
  const { t } = useI18n();
  const [draft, setDraft] = useState<FlowFormDraft>({
    flowId: initialFlowId, displayName: '', kind: initialKind, description: '',
  });
  const [nodes, setNodes] = useState<ResolvedFlowNode[]>([]);
  const [catalog, setCatalog] = useState<ComposableCatalogItem[]>([]);
  const [catalogCoordinate, setCatalogCoordinate] = useState('');
  const [strongEtag, setStrongEtag] = useState<string | null>(null);
  const [subject, setSubject] = useState<FlowDraftRef | null>(null);
  const [publishedSubject, setPublishedSubject] = useState<FlowVersionRef | null>(null);
  const [fixture, setFixture] = useState<FixtureSetSummary | null>(null);
  const [fixtureEtag, setFixtureEtag] = useState<string | null>(null);
  const [fixtureInput, setFixtureInput] = useState('{}');
  const [fixtureOutput, setFixtureOutput] = useState('{}');
  const [fixtureMode, setFixtureMode] = useState<'WHOLE_FLOW' | 'NODE_CASES'>('WHOLE_FLOW');
  const [nodeFixtureOptions, setNodeFixtureOptions] = useState<Record<string, FixtureSetSummary[]>>({});
  const [nodeFixtureSelections, setNodeFixtureSelections] = useState<Record<string, FixtureSetSummary>>({});
  const [run, setRun] = useState<SimulationRun | null>(null);
  const [published, setPublished] = useState('');
  const [legacyPreview, setLegacyPreview] = useState<LegacyReusableFlowReauthorPreview | null>(null);
  const [legacyFixturePreview, setLegacyFixturePreview] = useState<LegacyFixtureReauthorPreview | null>(null);
  const [importedFlow, setImportedFlow] = useState<ReusableFlowCommand | null>(null);
  const [tab, setTab] = useState<FlowTab>(initialTab);
  const [busy, setBusy] = useState(
    initialFlowId.length > 0 || initialLegacyFlow !== null || initialLegacyFixture !== null,
  );
  const [message, setMessage] = useState('');

  useEffect(() => {
    let cancelled = false;
    void listComposableCatalog().then((items) => {
      if (!cancelled) {
        setCatalog(items);
        setCatalogCoordinate((current) => current || catalogKey(items[0]?.reference));
      }
    }).catch((failure: unknown) => {
      if (!cancelled) setMessage(errorMessage(failure));
    });
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    if (!initialFlowId) return;
    let cancelled = false;
    const latest = initialLegacyFixture ? Promise.resolve(null) : readLatestFlowVersion(initialFlowId);
    void Promise.all([readFlow(initialFlowId), latest, listComposableCatalog()])
      .then(async ([stored, currentVersion, items]) => {
      const restored = stored.value.graph.nodes.map((node) => {
        const item = exactCatalogItem(items, node.use);
        return { nodeId: node.nodeId, label: node.label, item };
      });
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
      setCatalog(items);
      setStrongEtag(stored.strongEtag);
      setSubject(exactSubject);
      const currentPublished = currentVersion && currentVersion.source.draftId === exactSubject.draftId
        && currentVersion.source.revision === exactSubject.revision
        && currentVersion.source.fingerprint === exactSubject.fingerprint
        ? {
            kind: 'FLOW_VERSION' as const, publicationId: currentVersion.publicationId,
            revision: currentVersion.revision, fingerprint: currentVersion.fingerprint,
          } : null;
      setPublishedSubject(currentPublished);
      setPublished(currentVersion ? `${currentVersion.publicationId}@${currentVersion.revision}` : '');
      const summaries = await listFlowFixtures(currentPublished ?? exactSubject);
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
  }, [initialFlowId, initialLegacyFixture, t]);

  useEffect(() => {
    if (!initialLegacyFlow) return;
    let cancelled = false;
    void Promise.all([readLegacyReusableFlowPreview(
      initialLegacyFlow.sourceKind, initialLegacyFlow.sourceId, initialLegacyFlow.sourceRevision,
    ), listComposableCatalog()]).then(async ([preview, items]) => {
      const restored = preview.suggestedFlow.flow.graph.nodes.map((node) => ({
        nodeId: node.nodeId, label: node.label, item: exactCatalogItem(items, node.use),
      }));
      if (cancelled) return;
      setDraft({
        flowId: preview.suggestedFlowId,
        displayName: preview.suggestedFlow.flow.displayName,
        kind: preview.suggestedFlow.flow.kind,
        description: preview.suggestedFlow.flow.description,
      });
      setNodes(restored);
      setCatalog(items);
      setLegacyPreview(preview);
      setImportedFlow(preview.suggestedFlow);
      setMessage(t('Review'));
    }).catch((failure: unknown) => {
      if (!cancelled) setMessage(errorMessage(failure));
    }).finally(() => {
      if (!cancelled) setBusy(false);
    });
    return () => { cancelled = true; };
  }, [initialLegacyFlow, t]);

  useEffect(() => {
    if (!initialLegacyFixture) return;
    let cancelled = false;
    void readLegacyFixtureReauthorPreview(
      initialLegacyFixture.draftId, initialLegacyFixture.revision,
    ).then((preview) => {
      if (!cancelled) setLegacyFixturePreview(preview);
    }).catch((failure: unknown) => {
      if (!cancelled) setMessage(errorMessage(failure));
    });
    return () => { cancelled = true; };
  }, [initialLegacyFixture]);

  useEffect(() => {
    if (tab !== 'fixture' || !publishedSubject || nodes.length === 0) return;
    let cancelled = false;
    void Promise.all(nodes.map(async (node) => [node.nodeId,
      await listFlowFixtures(node.item.reference)] as const)).then((entries) => {
      if (cancelled) return;
      const options = Object.fromEntries(entries);
      setNodeFixtureOptions(options);
      setNodeFixtureSelections((current) => Object.fromEntries(nodes.flatMap((node) => {
        const selected = current[node.nodeId] ?? options[node.nodeId]?.[0];
        return selected ? [[node.nodeId, selected]] : [];
      })));
    }).catch((failure: unknown) => {
      if (!cancelled) setMessage(errorMessage(failure));
    });
    return () => { cancelled = true; };
  }, [nodes, publishedSubject, tab]);

  const addCatalogSelection = async () => {
    setBusy(true);
    setMessage('');
    try {
      const item = catalog.find((value) => catalogKey(value.reference) === catalogCoordinate);
      if (!item) throw new Error('Select a catalog item.');
      const nodeId = nextNodeId(nodes);
      setNodes([...nodes, { nodeId, label: item.displayName, item }]);
      setImportedFlow(null);
      setMessage('');
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
      const command = importedFlow ? {
        ...importedFlow,
        flow: {
          ...importedFlow.flow,
          displayName: draft.displayName.trim(),
          kind: draft.kind,
          description: draft.description,
        },
      } : buildReusableFlowCommand(draft, nodes);
      const result = await saveFlow(
        draft.flowId.trim(), command, strongEtag, operationKey('save-flow', draft.flowId),
      );
      setStrongEtag(result.strongEtag);
      setSubject(result.value.draft);
      setPublishedSubject(null);
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
    const fixtureSubject = publishedSubject ?? subject;
    if (!fixtureSubject) return;
    if (legacyFixturePreview && !sameDraftSubject(legacyFixturePreview.target, fixtureSubject)) {
      setMessage('The target Flow changed after the legacy Fixture review. Reload the review.');
      return;
    }
    setBusy(true);
    setMessage('');
    try {
      const parentMode = fixtureMode === 'NODE_CASES';
      if (parentMode && !publishedSubject) throw new Error('Publish the parent Flow before applying node Cases.');
      const fixtureSetId = `${draft.flowId.trim()}.${parentMode ? 'parent-default' : 'default'}`;
      const command = parentMode
        ? buildParentFlowFixtureCommand(publishedSubject!, `${draft.displayName.trim()} parent default`,
          fixtureInput, fixtureOutput, nodes, nodeFixtureSelections)
        : buildFlowFixtureCommand(
          fixtureSubject, `${draft.displayName.trim()} default`, fixtureInput, fixtureOutput,
        );
      const saved = await saveFlowFixture(
        fixtureSetId, command, fixtureEtag, operationKey('save-flow-fixture', fixtureSetId),
      );
      setLegacyFixturePreview(null);
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
      setPublishedSubject(receipt.version);
      setPublished(`${receipt.version.publicationId}@${receipt.version.revision}`);
      setTab('fixture');
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
          {legacyPreview && (
            <section className="legacy-reauthor-preview" data-testid="legacy-flow-reauthor-preview">
              <h2>{t('Review')}</h2>
              <p>Nothing is migrated automatically. This preview copies no legacy Fixture material.</p>
              <p>{legacyPreview.source.kind} · {legacyPreview.source.sourceId}
                · r{legacyPreview.source.sourceRevision} · {legacyPreview.fixtureReferences} Fixture references</p>
              <ul>{legacyPreview.diagnostics.map((diagnostic) => (
                <li key={diagnostic.code}>{diagnostic.message}</li>
              ))}</ul>
            </section>
          )}
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
            <h2>{t('Dependencies')}</h2>
            <div className="flow-add-resource">
              <select data-testid="flow-catalog-selection" value={catalogCoordinate}
                onChange={(event) => setCatalogCoordinate(event.target.value)}>
                {catalog.map((item) => <option key={catalogKey(item.reference)} value={catalogKey(item.reference)}>
                  {item.reference.kind === 'API_RESOURCE' ? 'API' : 'Flow'} · {item.displayName}
                  {' · '}{catalogLabel(item.reference)}
                </option>)}
              </select>
              <button type="button" data-testid="add-flow-catalog-item" disabled={busy || !catalogCoordinate}
                onClick={addCatalogSelection}><Plus aria-hidden="true" /> {t('Add item')}</button>
            </div>
            <ol className="flow-node-list" data-testid="flow-node-list">
              {nodes.map((node, index) => (
                <li key={node.nodeId}>
                  <span>{index + 1}</span>
                  <div><strong>{node.label}</strong><small>{catalogLabel(node.item.reference)}</small></div>
                  <button type="button" aria-label={`${t('Remove')} ${node.label}`}
                    onClick={() => {
                      setNodes(nodes.filter((value) => value.nodeId !== node.nodeId));
                      setImportedFlow(null);
                    }}>
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
          {legacyFixturePreview && <section className="legacy-reauthor-preview"
            data-testid="legacy-fixture-reauthor-preview">
            <h3>{t('Review')}</h3>
            <p>Legacy Fixture material is not copied. Enter new whole-Flow input and output.</p>
            <p>{legacyFixturePreview.source.draftId} · r{legacyFixturePreview.source.revision}
              · {legacyFixturePreview.references.length} Fixture references</p>
            <ul>{legacyFixturePreview.references.map((reference) => (
              <li key={reference.nodeId}>{reference.nodeId} · {reference.materialKind}
                · {reference.fidelity} · expected input {reference.expectedInputPresent ? 'present' : 'absent'}</li>
            ))}</ul>
            <ul>{legacyFixturePreview.diagnostics.map((diagnostic) => (
              <li key={diagnostic.code}>{diagnostic.message}</li>
            ))}</ul>
          </section>}
          <p>{t('Define one whole-flow input and returned output. Internal API calls stay unexecuted.')}</p>
          {publishedSubject && <div className="fixture-mode" data-testid="flow-fixture-mode">
            <label><input type="radio" name="flow-fixture-mode" checked={fixtureMode === 'WHOLE_FLOW'}
              onChange={() => setFixtureMode('WHOLE_FLOW')} />{t('Return')}</label>
            <label><input type="radio" name="flow-fixture-mode" checked={fixtureMode === 'NODE_CASES'}
              onChange={() => setFixtureMode('NODE_CASES')} />{t('Node')} · {t('Case')}</label>
          </div>}
          {fixtureMode === 'NODE_CASES' && <div data-testid="flow-node-fixtures">
            {nodes.map((node) => <Field key={node.nodeId} label={`${node.label} · ${catalogLabel(node.item.reference)}`}>
              <select data-testid={`flow-node-fixture:${node.nodeId}`}
                value={nodeFixtureSelections[node.nodeId]?.fixtureSetId ?? ''}
                onChange={(event) => {
                  const selected = nodeFixtureOptions[node.nodeId]?.find(
                    (option) => option.fixtureSetId === event.target.value,
                  );
                  if (selected) setNodeFixtureSelections({ ...nodeFixtureSelections, [node.nodeId]: selected });
                }}>
                <option value="">{t('Fixture')}</option>
                {(nodeFixtureOptions[node.nodeId] ?? []).map((option) => <option key={option.fixtureSetId}
                  value={option.fixtureSetId}>{option.displayName} · {option.cases[0]?.name}</option>)}
              </select>
            </Field>)}
          </div>}
          <div className="object-example-grid">
            <Field label={t('Fixture input')}><textarea data-testid="flow-fixture-input" rows={9}
              value={fixtureInput} onChange={(event) => setFixtureInput(event.target.value)} /></Field>
            <Field label={t('Fixture output')}><textarea data-testid="flow-fixture-output" rows={9}
              value={fixtureOutput} onChange={(event) => setFixtureOutput(event.target.value)} /></Field>
          </div>
          <button type="button" className="primary-object-action" data-testid="save-flow-fixture"
            disabled={busy || !(publishedSubject ?? subject)
              || fixtureMode === 'NODE_CASES' && nodes.some((node) => !nodeFixtureSelections[node.nodeId])}
            onClick={saveFixtureAndSimulate}>
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
            <ul className="simulation-node-evidence" data-testid="flow-simulation-nodes">
              {run.nodes.map((node) => <li key={node.nodeId} data-testid={`flow-simulation-node:${node.nodeId}`}>
                {node.nodeId} · {node.status} · {node.execution} · {node.fixtureSource}
                {' · '}{node.egress.decision} · {node.egress.attempted ? 'EGRESS_ATTEMPTED' : 'NO_EGRESS'}
              </li>)}
            </ul>
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

function nextNodeId(nodes: ResolvedFlowNode[]): string {
  let index = nodes.length + 1;
  while (nodes.some((node) => node.nodeId === `step${index}`)) index += 1;
  return `step${index}`;
}

function catalogKey(reference: ComposableCatalogItem['reference'] | undefined): string {
  if (!reference) return '';
  return `${reference.kind}:${reference.kind === 'API_RESOURCE'
    ? reference.resourceId : reference.publicationId}:${reference.revision}:${reference.fingerprint}`;
}

function catalogLabel(reference: ComposableCatalogItem['reference']): string {
  return `${reference.kind === 'API_RESOURCE' ? reference.resourceId : reference.publicationId}@${reference.revision}`;
}

function exactCatalogItem(
  items: ComposableCatalogItem[], reference: ComposableCatalogItem['reference'],
): ComposableCatalogItem {
  const item = items.find((candidate) => catalogKey(candidate.reference) === catalogKey(reference));
  if (!item) throw new Error('A Flow dependency is unavailable or has drifted.');
  return item;
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

function sameDraftSubject(expected: FlowDraftRef, actual: FlowDraftRef | FlowVersionRef): boolean {
  return actual.kind === 'FLOW_DRAFT' && actual.draftId === expected.draftId
    && actual.revision === expected.revision && actual.fingerprint === expected.fingerprint;
}
