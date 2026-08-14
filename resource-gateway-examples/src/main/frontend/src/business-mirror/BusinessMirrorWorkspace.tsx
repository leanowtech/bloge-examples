import {
  ArrowLeft,
  Boxes,
  Check,
  ChevronRight,
  CircleAlert,
  CloudOff,
  FileCheck2,
  Layers3,
  LoaderCircle,
  Network,
  Play,
  RefreshCw,
  Save,
  Search,
  ShieldCheck,
  SlidersHorizontal,
} from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';

import {
  compileBusinessMirrorPackage,
  fetchBusinessMirrorLegacyCatalog,
  fetchBusinessMirrorPackages,
  importBusinessMirrorLegacyPackage,
  saveBusinessMirrorPackage,
} from '../api';
import { useI18n } from '../i18n/I18nProvider';
import type { MessageId } from '../i18n/messageCatalog';
import {
  businessMirrorCapabilityLayers,
  businessMirrorTaskForGap,
  businessMirrorTaskProgress,
  effectiveBusinessMirrorGaps,
  projectBusinessMirrorPortfolio,
  type BusinessMirrorCompilationReceipt,
  type BusinessMirrorGap,
  type BusinessMirrorPackageDraft,
  type BusinessMirrorPackagePage,
  type BusinessMirrorPortfolioItem,
  type BusinessMirrorTaskId,
  type LegacyGraphPackageProjectionCatalog,
  type StoredBusinessMirrorPackage,
} from './domain';
import './businessMirror.css';

type CommandState =
  | { kind: 'idle' }
  | { kind: 'running'; operation: 'import' | 'save' | 'compile' }
  | { kind: 'success'; messageId: MessageId; values: Record<string, string | number> }
  | { kind: 'error'; detail: string };

const TASKS: Array<{
  id: BusinessMirrorTaskId;
  label: MessageId;
  detail: MessageId;
}> = [
  { id: 'problem', label: 'businessMirror.task.problem', detail: 'businessMirror.task.problemDetail' },
  { id: 'boundary', label: 'businessMirror.task.boundary', detail: 'businessMirror.task.boundaryDetail' },
  { id: 'capabilities', label: 'businessMirror.task.capabilities', detail: 'businessMirror.task.capabilitiesDetail' },
  { id: 'scenarios', label: 'businessMirror.task.scenarios', detail: 'businessMirror.task.scenariosDetail' },
  { id: 'rehearsal', label: 'businessMirror.task.rehearsal', detail: 'businessMirror.task.rehearsalDetail' },
  { id: 'calibrate', label: 'businessMirror.task.calibrate', detail: 'businessMirror.task.calibrateDetail' },
];

const BUILT_IN_GRAPH_TITLES: Record<string, MessageId> = {
  userDashboard: 'showcase.userDashboard.title',
  loanDecisionPolicy: 'showcase.loanDecisionPolicy.title',
  productDetail: 'showcase.productDetail.title',
  enrichOrderList: 'showcase.enrichOrderList.title',
  creditScore: 'showcase.creditScore.title',
  resourceDispatch: 'showcase.resourceDispatch.title',
  aiEnrichedSearch: 'showcase.aiEnrichedSearch.title',
};

export default function BusinessMirrorWorkspace() {
  const { m } = useI18n();
  const [catalog, setCatalog] = useState<LegacyGraphPackageProjectionCatalog | null>(null);
  const [packagePage, setPackagePage] = useState<BusinessMirrorPackagePage | null>(null);
  const [loadError, setLoadError] = useState('');
  const [loadGeneration, setLoadGeneration] = useState(0);
  const [query, setQuery] = useState('');
  const [selectedPackageId, setSelectedPackageId] = useState(() =>
    new URLSearchParams(window.location.search).get('packageId') ?? '');
  const [activeTask, setActiveTask] = useState<BusinessMirrorTaskId>(() =>
    parseTask(new URLSearchParams(window.location.search).get('task')));
  const [editor, setEditor] = useState<BusinessMirrorPackageDraft | null>(null);
  const [compilation, setCompilation] = useState<BusinessMirrorCompilationReceipt | null>(null);
  const [command, setCommand] = useState<CommandState>({ kind: 'idle' });

  useEffect(() => {
    let active = true;
    setLoadError('');
    void Promise.all([fetchBusinessMirrorLegacyCatalog(), fetchBusinessMirrorPackages()])
      .then(([nextCatalog, nextPackages]) => {
        if (!active) return;
        setCatalog(nextCatalog);
        setPackagePage(nextPackages);
      })
      .catch((cause: unknown) => {
        if (active) setLoadError(errorDetail(cause));
      });
    return () => { active = false; };
  }, [loadGeneration]);

  const items = useMemo(() => (
    catalog && packagePage ? projectBusinessMirrorPortfolio(catalog, packagePage) : []
  ), [catalog, packagePage]);
  const selected = items.find((item) => item.packageId === selectedPackageId) ?? null;

  useEffect(() => {
    if (!selected) {
      setEditor(null);
      return;
    }
    setEditor(structuredClone(selected.stored?.draft ?? selected.projection.packageDraft));
  }, [selected?.packageId, selected?.stored?.draftFingerprint]);

  useEffect(() => {
    setCompilation(null);
    setCommand({ kind: 'idle' });
  }, [selected?.packageId]);

  if (loadError) {
    return (
      <main className="business-mirror-load-state" role="alert">
        <CircleAlert aria-hidden="true" size={24} />
        <h2>{m('businessMirror.load.failed')}</h2>
        <code>{loadError}</code>
        <button type="button" onClick={() => setLoadGeneration((value) => value + 1)}>
          <RefreshCw aria-hidden="true" size={16} />
          {m('businessMirror.command.retry')}
        </button>
      </main>
    );
  }
  if (!catalog || !packagePage) {
    return (
      <main className="business-mirror-load-state" aria-busy="true" aria-live="polite">
        <LoaderCircle aria-hidden="true" className="spin" size={22} />
        <span>{m('businessMirror.portfolio.title')}</span>
      </main>
    );
  }

  if (!selected || !editor) {
    return (
      <Portfolio
        items={items}
        query={query}
        offline={catalog.scope.environmentId === 'offline'}
        onQuery={setQuery}
        onOpen={(item) => {
          setSelectedPackageId(item.packageId);
          setActiveTask('problem');
          replaceWorkspaceQuery(item.packageId, 'problem');
        }}
      />
    );
  }

  const gaps = effectiveBusinessMirrorGaps(selected.projection, selected.stored, compilation);
  const firstBlocker = gaps.find((gap) => gap.severity === 'BLOCKING') ?? null;
  const blockerCount = gaps.filter((gap) => gap.severity === 'BLOCKING').length;
  const offline = catalog.scope.environmentId === 'offline';
  const dirty = selected.stored !== null
    && JSON.stringify(editor.businessDefinition) !== JSON.stringify(selected.stored.draft.businessDefinition);

  const selectTask = (task: BusinessMirrorTaskId) => {
    setActiveTask(task);
    replaceWorkspaceQuery(selected.packageId, task);
  };
  const upsertStored = (stored: StoredBusinessMirrorPackage) => {
    setPackagePage((current) => current && ({
      ...current,
      items: [...current.items.filter((item) => item.draft.packageId !== stored.draft.packageId), stored]
        .sort((left, right) => left.draft.packageId.localeCompare(right.draft.packageId)),
    }));
    setEditor(structuredClone(stored.draft));
  };
  const runImport = async () => {
    setCommand({ kind: 'running', operation: 'import' });
    try {
      const receipt = await importBusinessMirrorLegacyPackage(
        selected.graphName,
        `business-mirror:import:${selected.graphName}:v1`,
      );
      upsertStored(receipt.result);
      setCommand({
        kind: 'success',
        messageId: 'businessMirror.command.imported',
        values: { revision: receipt.result.draft.revision },
      });
    } catch (cause) {
      setCommand({ kind: 'error', detail: errorDetail(cause) });
    }
  };
  const runSave = async () => {
    if (!selected.stored) return;
    setCommand({ kind: 'running', operation: 'save' });
    try {
      const receipt = await saveBusinessMirrorPackage(editor, commandId('save', editor.revision));
      upsertStored(receipt.result);
      setCompilation(null);
      setCommand({
        kind: 'success',
        messageId: 'businessMirror.command.saved',
        values: { revision: receipt.result.draft.revision },
      });
    } catch (cause) {
      setCommand({ kind: 'error', detail: errorDetail(cause) });
    }
  };
  const runCompile = async () => {
    if (!selected.stored || dirty) return;
    setCommand({ kind: 'running', operation: 'compile' });
    try {
      const receipt = await compileBusinessMirrorPackage(
        selected.packageId,
        selected.stored.draft.revision,
        commandId('compile', selected.stored.draft.revision),
      );
      setCompilation(receipt);
      setCommand({
        kind: 'success',
        messageId: 'businessMirror.command.compiled',
        values: {
          status: receipt.readiness.status,
          revision: receipt.compilationRevision,
        },
      });
    } catch (cause) {
      setCommand({ kind: 'error', detail: errorDetail(cause) });
    }
  };

  return (
    <main className="business-mirror-workspace">
      <header className="business-mirror-context">
        <button
          type="button"
          className="business-mirror-back"
          onClick={() => {
            setSelectedPackageId('');
            replaceWorkspaceQuery('', 'problem');
          }}
        >
          <ArrowLeft aria-hidden="true" size={17} />
          {m('businessMirror.command.back')}
        </button>
        <div>
          <span>{selected.graphName}</span>
          <h2>{localizedItemName(selected, m)}</h2>
        </div>
        <div className="business-mirror-context-meta">
          <span className={`business-mirror-badge ${selected.stored ? 'imported' : 'legacy'}`}>
            {selected.stored
              ? m('businessMirror.status.imported', { revision: selected.stored.draft.revision })
              : m('businessMirror.status.legacy')}
          </span>
          <span className="business-mirror-connection">
            {offline ? <CloudOff aria-hidden="true" size={15} /> : <ShieldCheck aria-hidden="true" size={15} />}
            {m(offline ? 'businessMirror.status.offline' : 'businessMirror.status.connected')}
          </span>
        </div>
      </header>

      {offline && (
        <div className="business-mirror-offline-note">
          <CloudOff aria-hidden="true" size={18} />
          <span>{m('businessMirror.offline.detail')}</span>
        </div>
      )}

      <ReadinessBand
        gaps={gaps}
        firstBlocker={firstBlocker}
        blockerCount={blockerCount}
        imported={selected.stored !== null}
        dirty={dirty}
        command={command}
        onImport={runImport}
        onSave={runSave}
        onCompile={runCompile}
        onFix={() => firstBlocker && selectTask(businessMirrorTaskForGap(firstBlocker))}
      />

      <div className="business-mirror-task-layout">
        <nav className="business-mirror-task-rail" aria-label={m('businessMirror.readiness.all')}>
          {TASKS.map((task) => {
            const progress = businessMirrorTaskProgress(task.id, gaps);
            return (
              <button
                key={task.id}
                type="button"
                className={activeTask === task.id ? 'active' : ''}
                aria-current={activeTask === task.id ? 'step' : undefined}
                onClick={() => selectTask(task.id)}
              >
                <span className={`task-progress ${progress.toLowerCase()}`}>
                  {progress === 'COMPLETE'
                    ? <Check aria-hidden="true" size={14} />
                    : <CircleAlert aria-hidden="true" size={14} />}
                </span>
                <span>
                  <strong>{m(task.label)}</strong>
                  <small>{m(task.detail)}</small>
                </span>
                <ChevronRight aria-hidden="true" size={15} />
              </button>
            );
          })}
        </nav>

        <section className="business-mirror-task-surface" aria-live="polite">
          <TaskSurface
            task={activeTask}
            item={selected}
            draft={editor}
            gaps={gaps}
            editable={selected.stored !== null}
            onDraft={setEditor}
          />
        </section>

        <aside className="business-mirror-evidence-rail">
          <GapInventory gaps={gaps} onSelect={(gap) => selectTask(businessMirrorTaskForGap(gap))} />
          <Lineage item={selected} />
        </aside>
      </div>
    </main>
  );
}

function Portfolio({
  items,
  query,
  offline,
  onQuery,
  onOpen,
}: {
  items: BusinessMirrorPortfolioItem[];
  query: string;
  offline: boolean;
  onQuery(value: string): void;
  onOpen(item: BusinessMirrorPortfolioItem): void;
}) {
  const { m } = useI18n();
  const normalized = query.trim().toLowerCase();
  const filtered = items.filter((item) => !normalized || [
    item.displayName, item.graphName, item.packageId, item.owner, item.domainId,
  ].some((value) => value.toLowerCase().includes(normalized)));
  const imported = items.filter((item) => item.imported).length;
  const blockers = items.reduce((total, item) => total + item.blockerCount, 0);
  return (
    <main className="business-mirror-portfolio">
      <header className="business-mirror-portfolio-header">
        <div>
          <h2>{m('businessMirror.portfolio.title')}</h2>
          <p>{m('businessMirror.portfolio.summary')}</p>
        </div>
        <span className="business-mirror-connection">
          {offline ? <CloudOff aria-hidden="true" size={16} /> : <ShieldCheck aria-hidden="true" size={16} />}
          {m(offline ? 'businessMirror.status.offline' : 'businessMirror.status.connected')}
        </span>
      </header>
      {offline && <p className="business-mirror-offline-copy">{m('businessMirror.offline.detail')}</p>}
      <div className="business-mirror-metrics" aria-label={m('businessMirror.portfolio.title')}>
        <span><strong>{items.length}</strong>{m('businessMirror.portfolio.packages')}</span>
        <span><strong>{imported}</strong>{m('businessMirror.portfolio.imported')}</span>
        <span><strong>{items.length - imported}</strong>{m('businessMirror.portfolio.remaining')}</span>
        <span className="danger"><strong>{blockers}</strong>{m('businessMirror.portfolio.blockers')}</span>
      </div>
      <label className="business-mirror-search">
        <Search aria-hidden="true" size={17} />
        <span className="visually-hidden">{m('businessMirror.portfolio.search')}</span>
        <input
          value={query}
          placeholder={m('businessMirror.portfolio.searchPlaceholder')}
          onChange={(event) => onQuery(event.target.value)}
        />
      </label>
      <section className="business-mirror-package-list" aria-label={m('businessMirror.portfolio.packages')}>
        {filtered.map((item) => (
          <button key={item.packageId} type="button" onClick={() => onOpen(item)}>
            <span className="package-list-icon"><Boxes aria-hidden="true" size={19} /></span>
            <span className="package-list-identity">
              <strong>{localizedItemName(item, m)}</strong>
              <small>{item.graphName}</small>
            </span>
            <span className="package-list-domain">
              <small>{item.domainId || m('businessMirror.readiness.noDomain')}</small>
              <span>{item.owner || m('businessMirror.readiness.noOwner')}</span>
            </span>
            <span className={`business-mirror-badge ${item.imported ? 'imported' : 'legacy'}`}>
              {item.imported
                ? m('businessMirror.status.imported', { revision: item.revision })
                : m('businessMirror.status.legacy')}
            </span>
            <span className="package-list-blockers">
              <CircleAlert aria-hidden="true" size={15} />
              {item.blockerCount}
            </span>
            <ChevronRight aria-hidden="true" size={18} />
          </button>
        ))}
        {filtered.length === 0 && <p>{m('businessMirror.portfolio.empty')}</p>}
      </section>
    </main>
  );
}

function ReadinessBand({
  gaps,
  firstBlocker,
  blockerCount,
  imported,
  dirty,
  command,
  onImport,
  onSave,
  onCompile,
  onFix,
}: {
  gaps: BusinessMirrorGap[];
  firstBlocker: BusinessMirrorGap | null;
  blockerCount: number;
  imported: boolean;
  dirty: boolean;
  command: CommandState;
  onImport(): void;
  onSave(): void;
  onCompile(): void;
  onFix(): void;
}) {
  const { m } = useI18n();
  const running = command.kind === 'running';
  return (
    <section className={`business-mirror-readiness ${blockerCount ? 'blocked' : 'ready'}`}>
      <div className="readiness-state">
        {blockerCount ? <CircleAlert aria-hidden="true" size={21} /> : <Check aria-hidden="true" size={21} />}
        <span>
          <small>{m('businessMirror.readiness.title')}</small>
          <strong>{blockerCount
            ? m('businessMirror.readiness.blockerCount', { count: blockerCount })
            : m('businessMirror.readiness.clear')}</strong>
        </span>
      </div>
      <div className="readiness-first">
        <small>{m('businessMirror.readiness.first')}</small>
        <code>{firstBlocker?.code ?? 'NONE'}</code>
      </div>
      <div className="readiness-actions">
        {!imported && (
          <button type="button" className="primary" disabled={running} onClick={onImport}>
            {command.kind === 'running' && command.operation === 'import'
              ? <LoaderCircle aria-hidden="true" className="spin" size={16} />
              : <Boxes aria-hidden="true" size={16} />}
            {m(command.kind === 'running' && command.operation === 'import'
              ? 'businessMirror.command.importing' : 'businessMirror.command.import')}
          </button>
        )}
        {imported && dirty && (
          <button type="button" className="primary" disabled={running} onClick={onSave}>
            {command.kind === 'running' && command.operation === 'save'
              ? <LoaderCircle aria-hidden="true" className="spin" size={16} />
              : <Save aria-hidden="true" size={16} />}
            {m(command.kind === 'running' && command.operation === 'save'
              ? 'businessMirror.command.saving' : 'businessMirror.command.save')}
          </button>
        )}
        {imported && !dirty && (
          <button type="button" className="primary" disabled={running} onClick={onCompile}>
            {command.kind === 'running' && command.operation === 'compile'
              ? <LoaderCircle aria-hidden="true" className="spin" size={16} />
              : <FileCheck2 aria-hidden="true" size={16} />}
            {m(command.kind === 'running' && command.operation === 'compile'
              ? 'businessMirror.command.compiling' : 'businessMirror.command.compile')}
          </button>
        )}
        {firstBlocker && (
          <button type="button" className="secondary" onClick={onFix}>
            <SlidersHorizontal aria-hidden="true" size={16} />
            {m('businessMirror.command.fixFirst')}
          </button>
        )}
      </div>
      {command.kind === 'success' && (
        <p className="readiness-command ok" role="status">{m(command.messageId, command.values)}</p>
      )}
      {command.kind === 'error' && (
        <p className="readiness-command error" role="alert">
          {m('businessMirror.command.failed')} <code>{command.detail}</code>
        </p>
      )}
      {dirty && <span className="readiness-dirty">{m('businessMirror.status.unsaved')}</span>}
      <span className="visually-hidden">{gaps.length}</span>
    </section>
  );
}

function TaskSurface({
  task,
  item,
  draft,
  gaps,
  editable,
  onDraft,
}: {
  task: BusinessMirrorTaskId;
  item: BusinessMirrorPortfolioItem;
  draft: BusinessMirrorPackageDraft;
  gaps: BusinessMirrorGap[];
  editable: boolean;
  onDraft(draft: BusinessMirrorPackageDraft): void;
}) {
  if (task === 'problem') {
    return <ProblemTask draft={draft} editable={editable} onDraft={onDraft} />;
  }
  if (task === 'boundary') return <BoundaryTask draft={draft} gaps={gaps} />;
  if (task === 'capabilities') return <CapabilityTask item={item} draft={draft} />;
  if (task === 'scenarios') return <ScenarioTask item={item} draft={draft} />;
  if (task === 'rehearsal') return <RehearsalTask draft={draft} />;
  return <CalibrateTask draft={draft} />;
}

function ProblemTask({
  draft,
  editable,
  onDraft,
}: {
  draft: BusinessMirrorPackageDraft;
  editable: boolean;
  onDraft(draft: BusinessMirrorPackageDraft): void;
}) {
  const { m } = useI18n();
  const definition = draft.businessDefinition;
  const update = (field: keyof typeof definition, value: string) => onDraft({
    ...draft,
    businessDefinition: { ...definition, [field]: value },
  });
  return (
    <>
      <TaskHeading heading="businessMirror.problem.title" detail="businessMirror.problem.detail" />
      <fieldset className="business-mirror-form" disabled={!editable}>
        <label>
          <span>{m('businessMirror.field.domain')}</span>
          <input value={definition.domainId} placeholder={m('businessMirror.field.domainPlaceholder')}
            onChange={(event) => update('domainId', event.target.value)} />
        </label>
        <label>
          <span>{m('businessMirror.field.problemCode')}</span>
          <input value={definition.problemCode} placeholder={m('businessMirror.field.problemCodePlaceholder')}
            onChange={(event) => update('problemCode', event.target.value)} />
        </label>
        <label className="wide">
          <span>{m('businessMirror.field.goal')}</span>
          <textarea value={definition.businessGoal} placeholder={m('businessMirror.field.goalPlaceholder')}
            onChange={(event) => update('businessGoal', event.target.value)} />
        </label>
        <label className="wide">
          <span>{m('businessMirror.field.outcome')}</span>
          <textarea value={definition.expectedOutcome} placeholder={m('businessMirror.field.outcomePlaceholder')}
            onChange={(event) => update('expectedOutcome', event.target.value)} />
        </label>
        <label>
          <span>{m('businessMirror.field.owner')}</span>
          <input value={definition.accountableOwner} placeholder={m('businessMirror.field.ownerPlaceholder')}
            onChange={(event) => update('accountableOwner', event.target.value)} />
        </label>
        <label>
          <span>{m('businessMirror.field.risk')}</span>
          <select value={definition.riskClass} onChange={(event) => update('riskClass', event.target.value)}>
            {(['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] as const)
              .map((risk) => <option key={risk} value={risk}>{risk}</option>)}
          </select>
        </label>
      </fieldset>
      <div className={`business-mirror-requirement ${definition.problemTaxonomyRef ? 'complete' : 'missing'}`}>
        <Network aria-hidden="true" size={18} />
        <span>
          <strong>{m('businessMirror.problem.taxonomy')}</strong>
          <small>{definition.problemTaxonomyRef?.id ?? m('businessMirror.problem.taxonomyMissing')}</small>
        </span>
      </div>
    </>
  );
}

function BoundaryTask({ draft, gaps }: { draft: BusinessMirrorPackageDraft; gaps: BusinessMirrorGap[] }) {
  const { m } = useI18n();
  const rows = [
    { label: 'businessMirror.boundary.contract' as MessageId, available: draft.packageContractRef !== null,
      value: draft.packageContractRef?.id ?? '' },
    { label: 'businessMirror.boundary.state' as MessageId, available: draft.stateModelRefs.length > 0,
      value: draft.stateModelRefs[0]?.id ?? '' },
    { label: 'businessMirror.boundary.effect' as MessageId, available: draft.effectModelRefs.length > 0,
      value: draft.effectModelRefs[0]?.id ?? '' },
  ];
  const ownerReview = gaps.some((gap) => gap.code === 'GRAPH_CONTRACT_OWNER_CONFIRMATION_MISSING');
  return (
    <>
      <TaskHeading heading="businessMirror.boundary.title" detail="businessMirror.task.boundaryDetail" />
      <div className="business-mirror-requirement-list">
        {rows.map((row) => (
          <div key={row.label} className={`business-mirror-requirement ${row.available ? 'complete' : 'missing'}`}>
            {row.available ? <Check aria-hidden="true" size={18} /> : <CircleAlert aria-hidden="true" size={18} />}
            <span><strong>{m(row.label)}</strong><small>{row.value || m('businessMirror.boundary.missing')}</small></span>
          </div>
        ))}
      </div>
      {ownerReview && <p className="business-mirror-inline-warning">{m('businessMirror.boundary.ownerReview')}</p>}
    </>
  );
}

function CapabilityTask({ item, draft }: { item: BusinessMirrorPortfolioItem; draft: BusinessMirrorPackageDraft }) {
  const { m } = useI18n();
  const layers = businessMirrorCapabilityLayers(item.projection, draft);
  const labels: Record<string, MessageId> = {
    L0: 'businessMirror.capability.l0', L1: 'businessMirror.capability.l1',
    L2: 'businessMirror.capability.l2', L3: 'businessMirror.capability.l3',
  };
  return (
    <>
      <TaskHeading heading="businessMirror.capability.title" detail="businessMirror.capability.detail" />
      <div className="business-mirror-capability-map">
        {layers.map((layer, index) => (
          <div key={layer.id} className="capability-layer">
            <header><span>{layer.id}</span><strong>{m(labels[layer.id])}</strong></header>
            <div className="capability-layer-assets">
              {layer.refs.map((ref, refIndex) => (
                <div key={`${ref.kind}:${ref.id}:${refIndex}`} className={ref.missing ? 'missing' : ''}>
                  {ref.missing ? <CircleAlert aria-hidden="true" size={16} /> : <Boxes aria-hidden="true" size={16} />}
                  <span>{ref.missing ? m('businessMirror.capability.missing', { kind: ref.kind }) : ref.id}</span>
                </div>
              ))}
            </div>
            {index < layers.length - 1 && <ChevronRight className="capability-layer-arrow" aria-hidden="true" size={20} />}
          </div>
        ))}
      </div>
      <a className="business-mirror-secondary-link" href={showcaseHref(item.graphName)}>
        <Layers3 aria-hidden="true" size={16} />
        {m('businessMirror.capability.openGraph')}
      </a>
    </>
  );
}

function ScenarioTask({ item, draft }: { item: BusinessMirrorPortfolioItem; draft: BusinessMirrorPackageDraft }) {
  const { m } = useI18n();
  return (
    <>
      <TaskHeading heading="businessMirror.scenario.title" detail="businessMirror.task.scenariosDetail" />
      <div className="business-mirror-scenario-discovery">
        <FileCheck2 aria-hidden="true" size={22} />
        <strong>{m('businessMirror.scenario.discovered', {
          count: item.projection.discoveredTestSuiteRefs.length,
        })}</strong>
        {item.projection.discoveredTestSuiteRefs.map((ref) => <code key={ref.id}>{ref.id}</code>)}
      </div>
      <p className="business-mirror-inline-warning">{m('businessMirror.scenario.warning')}</p>
      <div className="business-mirror-requirement-list two-column">
        <Requirement label="businessMirror.scenario.inventory" available={draft.scenarioInventoryRef !== null} />
        <Requirement label="businessMirror.scenario.pack" available={draft.scenarioPackRefs.length > 0} />
      </div>
    </>
  );
}

function RehearsalTask({ draft }: { draft: BusinessMirrorPackageDraft }) {
  const { m } = useI18n();
  const ready = draft.scenarioPackRefs.length > 0;
  return (
    <>
      <TaskHeading heading="businessMirror.rehearsal.title" detail="businessMirror.task.rehearsalDetail" />
      <div className={`business-mirror-stage-message ${ready ? 'ready' : 'blocked'}`}>
        {ready ? <Play aria-hidden="true" size={24} /> : <CircleAlert aria-hidden="true" size={24} />}
        <p>{m(ready ? 'businessMirror.rehearsal.ready' : 'businessMirror.rehearsal.blocked')}</p>
      </div>
      <a className="business-mirror-secondary-link" href={workspaceHref('rehearsals')}>
        <Play aria-hidden="true" size={16} />
        {m('businessMirror.command.openRehearsals')}
      </a>
    </>
  );
}

function CalibrateTask({ draft }: { draft: BusinessMirrorPackageDraft }) {
  const { m } = useI18n();
  return (
    <>
      <TaskHeading heading="businessMirror.calibrate.title" detail="businessMirror.task.calibrateDetail" />
      <div className="business-mirror-requirement-list">
        <Requirement label="businessMirror.calibrate.fidelity" available={draft.fidelityInventoryRef !== null} />
        <Requirement label="businessMirror.calibrate.outcome" available={draft.outcomeDefinitionRefs.length > 0} />
        <Requirement label="businessMirror.calibrate.approval" available={Boolean(draft.provenance.approvedBy)} />
      </div>
      <p className="business-mirror-governance-note">
        <ShieldCheck aria-hidden="true" size={20} />
        {m('businessMirror.calibrate.governance')}
      </p>
    </>
  );
}

function Requirement({ label, available }: { label: MessageId; available: boolean }) {
  const { m } = useI18n();
  return (
    <div className={`business-mirror-requirement ${available ? 'complete' : 'missing'}`}>
      {available ? <Check aria-hidden="true" size={18} /> : <CircleAlert aria-hidden="true" size={18} />}
      <span>
        <strong>{m(label)}</strong>
        <small>{m(available ? 'businessMirror.boundary.exact' : 'businessMirror.calibrate.unavailable')}</small>
      </span>
    </div>
  );
}

function TaskHeading({ heading, detail }: { heading: MessageId; detail: MessageId }) {
  const { m } = useI18n();
  return <header className="business-mirror-task-heading"><h3>{m(heading)}</h3><p>{m(detail)}</p></header>;
}

function GapInventory({ gaps, onSelect }: { gaps: BusinessMirrorGap[]; onSelect(gap: BusinessMirrorGap): void }) {
  const { m } = useI18n();
  return (
    <details open className="business-mirror-detail-group">
      <summary>
        <span>{m('businessMirror.readiness.all')}</span>
        <strong>{gaps.length}</strong>
      </summary>
      <div className="business-mirror-gap-list">
        {gaps.map((gap) => (
          <button key={gap.code} type="button" onClick={() => onSelect(gap)}>
            <span className={gap.severity.toLowerCase()}><CircleAlert aria-hidden="true" size={14} /></span>
            <span><code>{gap.code}</code><small>{m('businessMirror.gap.action')}</small></span>
            <ChevronRight aria-hidden="true" size={14} />
          </button>
        ))}
      </div>
    </details>
  );
}

function Lineage({ item }: { item: BusinessMirrorPortfolioItem }) {
  const { m } = useI18n();
  const projection = item.projection;
  const rows: Array<[MessageId, string]> = [
    ['businessMirror.lineage.graph', projection.sourceGraphRef.id],
    ['businessMirror.lineage.contract', projection.sourceContractRef.id],
    ['businessMirror.lineage.capability', projection.projectedCapabilityRef.id],
    ['businessMirror.lineage.closure', projection.capabilityClosureRef.id],
    ['businessMirror.lineage.tests', projection.discoveredTestSuiteRefs.map((ref) => ref.id).join(', ')],
  ];
  return (
    <details className="business-mirror-detail-group">
      <summary><span>{m('businessMirror.readiness.lineage')}</span><Network aria-hidden="true" size={15} /></summary>
      <dl className="business-mirror-lineage">
        {rows.map(([label, value]) => (
          <div key={label}><dt>{m(label)}</dt><dd>{value || '0'}</dd></div>
        ))}
      </dl>
    </details>
  );
}

function parseTask(value: string | null): BusinessMirrorTaskId {
  return TASKS.some((task) => task.id === value) ? value as BusinessMirrorTaskId : 'problem';
}

function replaceWorkspaceQuery(packageId: string, task: BusinessMirrorTaskId): void {
  const params = new URLSearchParams(window.location.search);
  if (packageId) {
    params.set('packageId', packageId);
    params.set('task', task);
  } else {
    params.delete('packageId');
    params.delete('task');
  }
  const search = params.toString();
  window.history.replaceState({}, '', `${window.location.pathname}${search ? `?${search}` : ''}`);
}

function workspaceHref(route: string): string {
  if (typeof globalThis.acquireVsCodeApi !== 'function') return `/${route}/`;
  const params = new URLSearchParams(window.location.search);
  params.set('workspaceRoute', route);
  params.delete('packageId');
  params.delete('task');
  return `?${params.toString()}`;
}

function showcaseHref(graphName: string): string {
  const base = workspaceHref('showcase');
  return `${base}${base.includes('?') ? '&' : '?'}graph=${encodeURIComponent(graphName)}`;
}

function commandId(operation: string, revision: number): string {
  const nonce = globalThis.crypto?.randomUUID?.() ?? `${Date.now()}`;
  return `business-mirror:${operation}:r${revision}:${nonce}`;
}

function localizedItemName(
  item: BusinessMirrorPortfolioItem,
  message: (id: MessageId, values?: Record<string, string | number>) => string,
): string {
  const messageId = BUILT_IN_GRAPH_TITLES[item.graphName];
  return messageId ? message(messageId) : item.displayName;
}

function errorDetail(cause: unknown): string {
  return cause instanceof Error ? cause.message : 'RG.BUSINESS_MIRROR.UNKNOWN';
}
