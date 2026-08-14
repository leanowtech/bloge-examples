import {
  Activity,
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
  TableProperties,
} from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';

import {
  acknowledgeBusinessMirrorEvidenceTask,
  BlogeApiRequestError,
  compileBusinessMirrorPackage,
  fetchBusinessMirrorDomainEvidencePortfolio,
  fetchBusinessMirrorLegacyCatalog,
  fetchBusinessMirrorPackageEvidence,
  fetchBusinessMirrorPackages,
  importBusinessMirrorLegacyPackage,
  refreshBusinessMirrorPackageEvidence,
  saveBusinessMirrorPackage,
} from '../api';
import { useI18n } from '../i18n/I18nProvider';
import type { MessageId } from '../i18n/messageCatalog';
import referenceEvidenceJson from '../../../../../../docs/schemas/resource-gateway-business-mirror/package-evidence-index-stage1-v1.fixture.json';
import {
  businessMirrorCapabilityLayers,
  businessMirrorTaskForGap,
  businessMirrorTaskProgress,
  effectiveBusinessMirrorGaps,
  projectBusinessMirrorPortfolio,
  type BusinessMirrorCompilationReceipt,
  type BusinessMirrorDomainEvidencePortfolio,
  type BusinessMirrorEvidenceLayer,
  type BusinessMirrorPackageEvidenceIndex,
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

interface BusinessAssetFocus {
  kind: string;
  id: string;
  revision: number;
  authority: string;
  compilationRevision: number;
}

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
  { id: 'evidence', label: 'businessMirror.task.evidence', detail: 'businessMirror.task.evidenceDetail' },
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

const REFERENCE_PACKAGE_EVIDENCE =
  referenceEvidenceJson as unknown as BusinessMirrorPackageEvidenceIndex;

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
  const [assetFocus, setAssetFocus] = useState<BusinessAssetFocus | null>(() =>
    parseAssetFocus(new URLSearchParams(window.location.search)));
  const [editor, setEditor] = useState<BusinessMirrorPackageDraft | null>(null);
  const [compilation, setCompilation] = useState<BusinessMirrorCompilationReceipt | null>(null);
  const [command, setCommand] = useState<CommandState>({ kind: 'idle' });
  const activeTaskButton = useRef<HTMLButtonElement | null>(null);

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

  useEffect(() => {
    const align = () => {
      const button = activeTaskButton.current;
      const rail = button?.parentElement;
      if (button && rail && rail.scrollWidth > rail.clientWidth) {
        rail.scrollLeft = Math.max(
          0,
          button.offsetLeft - (rail.clientWidth - button.offsetWidth) / 2,
        );
      }
    };
    const frame = window.requestAnimationFrame(align);
    window.addEventListener('resize', align);
    return () => {
      window.cancelAnimationFrame(frame);
      window.removeEventListener('resize', align);
    };
  }, [activeTask, selected?.packageId]);

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
          setAssetFocus(null);
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
    setAssetFocus(null);
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
            setAssetFocus(null);
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
                ref={activeTask === task.id ? activeTaskButton : undefined}
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
            assetFocus={assetFocus}
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
  assetFocus,
  onDraft,
}: {
  task: BusinessMirrorTaskId;
  item: BusinessMirrorPortfolioItem;
  draft: BusinessMirrorPackageDraft;
  gaps: BusinessMirrorGap[];
  editable: boolean;
  assetFocus: BusinessAssetFocus | null;
  onDraft(draft: BusinessMirrorPackageDraft): void;
}) {
  if (task === 'problem') {
    return <ProblemTask draft={draft} editable={editable} onDraft={onDraft} />;
  }
  if (task === 'boundary') return <BoundaryTask draft={draft} gaps={gaps} />;
  if (task === 'capabilities') {
    return <CapabilityTask item={item} draft={draft} focus={assetFocus} />;
  }
  if (task === 'scenarios') return <ScenarioTask item={item} draft={draft} />;
  if (task === 'rehearsal') return <RehearsalTask draft={draft} />;
  if (task === 'evidence') return <EvidenceTask item={item} />;
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

function CapabilityTask({
  item,
  draft,
  focus,
}: {
  item: BusinessMirrorPortfolioItem;
  draft: BusinessMirrorPackageDraft;
  focus: BusinessAssetFocus | null;
}) {
  const { m } = useI18n();
  const layers = businessMirrorCapabilityLayers(item.projection, draft).map((layer) => ({
    ...layer,
    refs: [...layer.refs],
  }));
  if (focus && !layers.some((layer) => layer.refs.some((ref) => isExactAssetFocus(ref, focus)))) {
    layers.find((layer) => layer.id === layerForAssetKind(focus.kind))?.refs.push({
      id: focus.id,
      kind: focus.kind,
      missing: false,
      revision: focus.revision,
      authority: focus.authority,
    });
  }
  const labels: Record<string, MessageId> = {
    L0: 'businessMirror.capability.l0', L1: 'businessMirror.capability.l1',
    L2: 'businessMirror.capability.l2', L3: 'businessMirror.capability.l3',
  };
  return (
    <>
      <TaskHeading heading="businessMirror.capability.title" detail="businessMirror.capability.detail" />
      {focus && (
        <div className="business-mirror-focus-coordinate" role="status">
          <Network aria-hidden="true" size={18} />
          <span>
            <strong>{m('businessMirror.capability.focus')}</strong>
            <small>{m('businessMirror.capability.focusCoordinate', {
              kind: focus.kind,
              revision: focus.revision,
              authority: focus.authority,
              compilationRevision: focus.compilationRevision,
            })}</small>
          </span>
        </div>
      )}
      <div className="business-mirror-capability-map">
        {layers.map((layer, index) => (
          <div key={layer.id} className="capability-layer">
            <header><span>{layer.id}</span><strong>{m(labels[layer.id])}</strong></header>
            <div className="capability-layer-assets">
              {layer.refs.map((ref, refIndex) => (
                <div
                  key={`${ref.kind}:${ref.id}:${refIndex}`}
                  className={[
                    ref.missing ? 'missing' : '',
                    focus && isExactAssetFocus(ref, focus) ? 'focused' : '',
                  ].filter(Boolean).join(' ')}
                  data-focused-asset={focus && isExactAssetFocus(ref, focus)
                    ? 'true' : undefined}
                >
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

type EvidenceLoadState = 'loading' | 'available' | 'missing' | 'error';

const EVIDENCE_LAYER_LABELS: Record<BusinessMirrorEvidenceLayer, MessageId> = {
  L0_RESOURCE: 'businessMirror.evidence.layer.l0',
  L1_SERVICE_DESIGN: 'businessMirror.evidence.layer.l1',
  L2_SERVICE_CARRIER: 'businessMirror.evidence.layer.l2',
  L3_APPLICATION: 'businessMirror.evidence.layer.l3',
  CALIBRATION: 'businessMirror.evidence.layer.calibration',
};

function EvidenceTask({ item }: { item: BusinessMirrorPortfolioItem }) {
  const { m } = useI18n();
  const [generation, setGeneration] = useState(0);
  const [state, setState] = useState<EvidenceLoadState>('loading');
  const [index, setIndex] = useState<BusinessMirrorPackageEvidenceIndex | null>(null);
  const [portfolio, setPortfolio] = useState<BusinessMirrorDomainEvidencePortfolio | null>(null);
  const [detail, setDetail] = useState('');
  const [refreshing, setRefreshing] = useState(false);
  const [taskActionId, setTaskActionId] = useState('');
  const [reference, setReference] = useState(false);

  useEffect(() => {
    let active = true;
    setState('loading');
    setIndex(null);
    setPortfolio(null);
    setDetail('');
    setReference(false);
    void fetchBusinessMirrorPackageEvidence(item.packageId)
      .then(async (nextIndex) => {
        const nextPortfolio = await fetchBusinessMirrorDomainEvidencePortfolio(nextIndex.domainId)
          .catch(() => null);
        if (!active) return;
        setIndex(nextIndex);
        setPortfolio(nextPortfolio);
        setState('available');
      })
      .catch((cause: unknown) => {
        if (!active) return;
        if (cause instanceof BlogeApiRequestError && cause.status === 404) {
          setState('missing');
          return;
        }
        setDetail(errorDetail(cause));
        setState('error');
      });
    return () => { active = false; };
  }, [generation, item.packageId]);

  const refresh = async () => {
    setRefreshing(true);
    setDetail('');
    try {
      await refreshBusinessMirrorPackageEvidence(item.packageId);
      setGeneration((value) => value + 1);
    } catch (cause) {
      setDetail(errorDetail(cause));
    } finally {
      setRefreshing(false);
    }
  };
  const acknowledge = async (taskId: string, version: number) => {
    setTaskActionId(taskId);
    setDetail('');
    try {
      await acknowledgeBusinessMirrorEvidenceTask(taskId, version);
      setGeneration((value) => value + 1);
    } catch (cause) {
      setDetail(errorDetail(cause));
    } finally {
      setTaskActionId('');
    }
  };
  const loadReference = () => {
    setIndex(REFERENCE_PACKAGE_EVIDENCE);
    setPortfolio(null);
    setDetail('');
    setReference(true);
    setState('available');
  };

  return (
    <>
      <TaskHeading heading="businessMirror.evidence.title" detail="businessMirror.evidence.detail" />
      {state === 'loading' && (
        <div className="business-mirror-evidence-state" aria-busy="true">
          <LoaderCircle aria-hidden="true" className="spin" size={21} />
          <span>{m('businessMirror.evidence.loading')}</span>
        </div>
      )}
      {state === 'missing' && (
        <div className="business-mirror-evidence-state missing">
          <TableProperties aria-hidden="true" size={24} />
          <span>
            <strong>{m('businessMirror.evidence.missing')}</strong>
            <small>{m(item.imported
              ? 'businessMirror.evidence.missingCompiled' : 'businessMirror.evidence.missingImported')}</small>
          </span>
          <button type="button" onClick={() => setGeneration((value) => value + 1)}>
            <RefreshCw aria-hidden="true" size={16} />
            {m('businessMirror.evidence.reload')}
          </button>
          <button type="button" onClick={loadReference}>
            <TableProperties aria-hidden="true" size={16} />
            {m('businessMirror.evidence.loadReference')}
          </button>
        </div>
      )}
      {state === 'error' && (
        <div className="business-mirror-evidence-state error" role="alert">
          <CircleAlert aria-hidden="true" size={22} />
          <span><strong>{m('businessMirror.evidence.failed')}</strong><code>{detail}</code></span>
          <button type="button" onClick={() => setGeneration((value) => value + 1)}>
            <RefreshCw aria-hidden="true" size={16} />
            {m('businessMirror.command.retry')}
          </button>
        </div>
      )}
      {state === 'available' && index && (
        <>
          {reference && (
            <div className="business-mirror-reference-note" role="status">
              <TableProperties aria-hidden="true" size={18} />
              <span>
                <strong>{m('businessMirror.evidence.referenceTitle')}</strong>
                <small>{m('businessMirror.evidence.referenceDetail', {
                  packageId: index.packageId,
                })}</small>
              </span>
              <button type="button" onClick={() => setGeneration((value) => value + 1)}>
                <RefreshCw aria-hidden="true" size={15} />
                {m('businessMirror.evidence.returnCurrent')}
              </button>
            </div>
          )}
          <div className={`business-mirror-evidence-summary fidelity-${index.fidelity.state.toLowerCase()}`}>
            <Activity aria-hidden="true" size={22} />
            <span>
              <small>{m('businessMirror.evidence.fidelityState')}</small>
              <strong>{index.fidelity.state}</strong>
            </span>
            <span>
              <small>{m('businessMirror.evidence.compilation')}</small>
              <strong>r{index.compilationRevision} / p{index.projectionRevision}</strong>
            </span>
            <span>
              <small>{m('businessMirror.evidence.projectedAt')}</small>
              <code>{index.projectedAt}</code>
            </span>
            {!reference && (
              <button type="button" disabled={refreshing} onClick={refresh}>
                <RefreshCw aria-hidden="true" className={refreshing ? 'spin' : ''} size={16} />
                {m(refreshing ? 'businessMirror.evidence.refreshing' : 'businessMirror.evidence.refresh')}
              </button>
            )}
          </div>

          <section className="business-mirror-evidence-section">
            <header>
              <h4>{m('businessMirror.evidence.layers')}</h4>
              <span>{m('businessMirror.evidence.noScore')}</span>
            </header>
            <div className="business-mirror-evidence-layers">
              {index.layers.map((layer) => {
                const available = layer.conclusions.filter((value) => value.state === 'AVAILABLE').length;
                const debt = layer.conclusions.length - available;
                return (
                  <article key={layer.layer}>
                    <span>{layer.layer}</span>
                    <strong>{m(EVIDENCE_LAYER_LABELS[layer.layer])}</strong>
                    <dl>
                      <div><dt>{m('businessMirror.evidence.conclusions')}</dt><dd>{layer.conclusions.length}</dd></div>
                      <div><dt>{m('businessMirror.evidence.available')}</dt><dd>{available}</dd></div>
                      <div className={debt ? 'debt' : ''}><dt>{m('businessMirror.evidence.debt')}</dt><dd>{debt}</dd></div>
                    </dl>
                  </article>
                );
              })}
            </div>
          </section>

          <section className="business-mirror-evidence-section">
            <header>
              <h4>{m('businessMirror.evidence.dimensions')}</h4>
              <span>{m('businessMirror.evidence.dimensionDetail')}</span>
            </header>
            {index.fidelity.dimensions.length === 0 ? (
              <p className="business-mirror-inline-warning">{m('businessMirror.evidence.profileMissing')}</p>
            ) : (
              <div className="business-mirror-fidelity-table" role="table">
                <div role="row" className="header">
                  <span role="columnheader">{m('businessMirror.evidence.dimension')}</span>
                  <span role="columnheader">{m('businessMirror.evidence.state')}</span>
                  <span role="columnheader">{m('businessMirror.evidence.obligations')}</span>
                  <span role="columnheader">{m('businessMirror.evidence.coverage')}</span>
                  <span role="columnheader">{m('businessMirror.evidence.confidence')}</span>
                  <span role="columnheader">{m('businessMirror.evidence.abstention')}</span>
                </div>
                {index.fidelity.dimensions.map((dimension) => (
                  <div role="row" key={dimension.dimension}>
                    <code role="cell">{dimension.dimension}</code>
                    <span role="cell" className={`dimension-state ${dimension.state.toLowerCase()}`}>
                      {dimension.state}
                    </span>
                    <span role="cell">{dimension.metric
                      ? `${dimension.metric.passedUnits}/${dimension.metric.requiredUnits}` : '-'}</span>
                    <span role="cell">{ratio(dimension.metric?.coverageRatio)}</span>
                    <span role="cell">{dimension.metric
                      ? `${ratio(dimension.metric.confidence.lowerBound)}-${ratio(dimension.metric.confidence.upperBound)}`
                      : '-'}</span>
                    <span role="cell">{ratio(dimension.metric?.abstentionRatio)}</span>
                  </div>
                ))}
              </div>
            )}
          </section>

          <section className="business-mirror-evidence-section">
            <header>
              <h4>{m('businessMirror.evidence.ownerTasks')}</h4>
              <span>{m('businessMirror.evidence.ownerTasksDetail', {
                count: portfolio?.packages.flatMap((value) => value.ownerTasks).length
                  ?? index.driftSignals.length,
              })}</span>
            </header>
            <div className="business-mirror-owner-tasks">
              {(portfolio?.packages.flatMap((value) => value.ownerTasks) ?? []).map((task) => (
                <article key={task.taskId}>
                  <span className={`task-severity ${task.severity.toLowerCase()}`}>{task.severity}</span>
                  <span><strong>{task.reason}</strong><small>{task.owner} · {task.dueAt}</small></span>
                  <code>{task.status}</code>
                  {task.status === 'OPEN' && (
                    <button type="button" disabled={taskActionId === task.taskId}
                      onClick={() => acknowledge(task.taskId, task.version)}>
                      {taskActionId === task.taskId
                        ? <LoaderCircle aria-hidden="true" className="spin" size={15} />
                        : <Check aria-hidden="true" size={15} />}
                      {m('businessMirror.evidence.acknowledge')}
                    </button>
                  )}
                </article>
              ))}
              {!portfolio?.packages.some((value) => value.ownerTasks.length > 0) && (
                <p>{m(index.driftSignals.length
                  ? 'businessMirror.evidence.portfolioPending' : 'businessMirror.evidence.noTasks')}</p>
              )}
            </div>
          </section>
        </>
      )}
      {detail && state === 'available' && <p className="business-mirror-inline-warning" role="alert">{detail}</p>}
    </>
  );
}

function ratio(value: number | undefined): string {
  return value === undefined ? '-' : `${Math.round(value * 1000) / 10}%`;
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

function parseAssetFocus(params: URLSearchParams): BusinessAssetFocus | null {
  const kind = params.get('assetKind')?.trim() ?? '';
  const id = params.get('assetId')?.trim() ?? '';
  const authority = params.get('assetAuthority')?.trim() ?? '';
  const revision = Number(params.get('assetRevision'));
  const compilationRevision = Number(params.get('compilationRevision'));
  if (!kind || !id || !authority || !Number.isSafeInteger(revision) || revision < 1
      || !Number.isSafeInteger(compilationRevision) || compilationRevision < 1) return null;
  return { kind, id, authority, revision, compilationRevision };
}

function layerForAssetKind(kind: string): 'L0' | 'L1' | 'L2' | 'L3' {
  if (['RESOURCE', 'OPERATOR', 'BUILT_IN_FUNCTION'].includes(kind)) return 'L0';
  if (['FEATURE', 'SCENARIO', 'SOLUTION'].includes(kind)) return 'L1';
  if (['SOP', 'AGENT', 'WORKFLOW'].includes(kind)) return 'L2';
  return 'L3';
}

function isExactAssetFocus(
  ref: { kind: string; id: string; revision?: number; authority?: string },
  focus: BusinessAssetFocus,
): boolean {
  return ref.kind === focus.kind && ref.id === focus.id
    && ref.revision === focus.revision && ref.authority === focus.authority;
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
  ['compilationRevision', 'assetKind', 'assetId', 'assetRevision', 'assetAuthority']
    .forEach((key) => params.delete(key));
  const search = params.toString();
  window.history.replaceState({}, '', `${window.location.pathname}${search ? `?${search}` : ''}`);
}

function workspaceHref(route: string): string {
  if (typeof globalThis.acquireVsCodeApi !== 'function') return `/${route}/`;
  const params = new URLSearchParams(window.location.search);
  params.set('workspaceRoute', route);
  params.delete('packageId');
  params.delete('task');
  ['compilationRevision', 'assetKind', 'assetId', 'assetRevision', 'assetAuthority']
    .forEach((key) => params.delete(key));
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
