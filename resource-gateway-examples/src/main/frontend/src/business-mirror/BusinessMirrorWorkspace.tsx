import {
  Activity,
  ArrowLeft,
  Boxes,
  Check,
  ChevronRight,
  CircleAlert,
  CloudOff,
  FileCheck2,
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
import { type ReactNode, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';

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
import { useWorkspaceNavigationGuard } from '../author/continuity/SafeWorkspaceNavigation';
import CrossWorkspaceAuthorLink from '../shared/workspace-routing/CrossWorkspaceAuthorLink';
import referenceEvidenceJson from '../../../../../../docs/schemas/resource-gateway-business-mirror/package-evidence-index-stage1-v1.fixture.json';
import {
  guidedTelemetryDurationBucket,
  guidedTelemetryGapCode,
  noopGuidedAuthoringTelemetry,
  type GuidedAuthoringTelemetry,
} from '../shared/guided-telemetry/guidedTelemetry';
import {
  businessMirrorCapabilityLayers,
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
import {
  getBusinessMirrorStepContract,
  remediationDescriptorForGap,
  type RemediationDescriptor,
} from './guidance';
import GuidedTaskShell, { type GuidedInputState } from './GuidedTaskShell';
import BusinessMirrorReferenceBindingControl from './reference/BusinessMirrorReferenceBindingControl';
import './businessMirror.css';

type CommandState =
  | { kind: 'idle' }
  | { kind: 'running'; operation: 'import' | 'save' | 'compile' }
  | { kind: 'success'; messageId: MessageId; values: Record<string, string | number> }
  | { kind: 'error'; detail: string };

type RemediationUiState = {
  requestId: number;
  packageId: string;
  descriptor: RemediationDescriptor;
  outcome: 'LOCATING' | 'STILL_BLOCKED' | 'RESOLVED' | 'NAVIGATED' | 'FAILED';
};

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

export interface BusinessMirrorWorkspaceProps {
  telemetry?: GuidedAuthoringTelemetry;
}

export default function BusinessMirrorWorkspace({
  telemetry = noopGuidedAuthoringTelemetry,
}: BusinessMirrorWorkspaceProps = {}) {
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
  const [remediation, setRemediation] = useState<RemediationUiState | null>(null);
  const activeTaskButton = useRef<HTMLButtonElement | null>(null);
  const workspace = useRef<HTMLElement | null>(null);
  const remediationTarget = useRef<HTMLElement | null>(null);
  const remediationSequence = useRef(0);
  const remediationStartedAt = useRef<number | null>(null);
  const previousSelectedPackageId = useRef<string | null>(null);

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
  const authoritativeGaps = useMemo(() => selected
    ? effectiveBusinessMirrorGaps(selected.projection, selected.stored, compilation)
    : [], [compilation, selected]);
  const authoritativeDirty = Boolean(selected?.stored && editor
    && JSON.stringify(editor) !== JSON.stringify(selected.stored.draft));

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
    if (previousSelectedPackageId.current !== null
      && previousSelectedPackageId.current !== selected?.packageId) {
      setRemediation(null);
    }
    previousSelectedPackageId.current = selected?.packageId ?? null;
  }, [selected?.packageId]);

  useLayoutEffect(() => {
    if (!remediation || !selected) return undefined;
    const locateRemediationTarget = () => {
      remediationTarget.current?.classList.remove('business-mirror-remediation-target');
      const target = [...(workspace.current?.querySelectorAll<HTMLElement>(
        '[data-remediation-anchor]',
      ) ?? [])].find((candidate) => (
        candidate.dataset.remediationAnchor === remediation.descriptor.anchor
      ));
      if (!target) {
        telemetry.record('REMEDIATION_COMPLETED', {
          gapCode: guidedTelemetryGapCode(remediation.descriptor.gapCode),
          outcome: 'FAILED',
          durationBucket: guidedTelemetryDurationBucket(
            performance.now() - (remediationStartedAt.current ?? performance.now()),
          ),
        });
        remediationStartedAt.current = null;
        setRemediation((current) => current?.requestId === remediation.requestId
          ? { ...current, outcome: 'FAILED' }
          : current);
        return;
      }
      remediationTarget.current = target;
      target.classList.add('business-mirror-remediation-target');
      target.scrollIntoView?.({ block: 'center', behavior: 'smooth' });
      const pickerTarget = remediation.descriptor.actionKind === 'OPEN_PICKER'
        ? target.querySelector<HTMLElement>('[role="combobox"]:not([disabled])')
        : null;
      const focusTarget = pickerTarget ?? (target.matches('a, button, input, select, textarea, [tabindex]')
        && !target.matches('[disabled], [aria-disabled="true"]')
        ? target
        : target.querySelector<HTMLElement>(
          'a:not([aria-disabled="true"]), button:not([disabled]), input:not([disabled]), '
          + 'select:not([disabled]), textarea:not([disabled]), [tabindex]:not([aria-disabled="true"])',
        ));
      const resolvedFocus = focusTarget ?? target;
      if (!focusTarget) resolvedFocus.tabIndex = -1;
      resolvedFocus.focus({ preventScroll: true });
      if (isCrossWorkspaceRemediation(remediation.descriptor.actionKind)) {
        const navigationTarget = target.matches('a[href]')
          ? target as HTMLAnchorElement
          : target.querySelector<HTMLAnchorElement>('a[href]');
        if (!navigationTarget) {
          telemetry.record('REMEDIATION_COMPLETED', {
            gapCode: guidedTelemetryGapCode(remediation.descriptor.gapCode),
            outcome: 'FAILED',
            durationBucket: guidedTelemetryDurationBucket(
              performance.now() - (remediationStartedAt.current ?? performance.now()),
            ),
          });
          remediationStartedAt.current = null;
          setRemediation((current) => current?.requestId === remediation.requestId
            ? { ...current, outcome: 'FAILED' }
            : current);
          return;
        }
        telemetry.record('REMEDIATION_COMPLETED', {
          gapCode: guidedTelemetryGapCode(remediation.descriptor.gapCode),
          outcome: 'NAVIGATED',
          durationBucket: guidedTelemetryDurationBucket(
            performance.now() - (remediationStartedAt.current ?? performance.now()),
          ),
        });
        remediationStartedAt.current = null;
        setRemediation((current) => current?.requestId === remediation.requestId
          ? { ...current, outcome: 'NAVIGATED' }
          : current);
        navigationTarget.click();
        return;
      }
      telemetry.record('REMEDIATION_COMPLETED', {
        gapCode: guidedTelemetryGapCode(remediation.descriptor.gapCode),
        outcome: 'TARGETED',
        durationBucket: guidedTelemetryDurationBucket(
          performance.now() - (remediationStartedAt.current ?? performance.now()),
        ),
      });
      setRemediation((current) => current?.requestId === remediation.requestId
        ? { ...current, outcome: 'STILL_BLOCKED' }
        : current);
    };
    locateRemediationTarget();
    return undefined;
  }, [activeTask, remediation?.requestId, selected?.packageId, telemetry]);

  useEffect(() => () => {
    remediationTarget.current?.classList.remove('business-mirror-remediation-target');
  }, []);

  useEffect(() => {
    if (!remediation || remediation.outcome !== 'STILL_BLOCKED'
      || remediation.packageId !== selected?.packageId
      || authoritativeGaps.some((gap) => gap.code === remediation.descriptor.gapCode)) return;
    telemetry.record('REMEDIATION_COMPLETED', {
      gapCode: guidedTelemetryGapCode(remediation.descriptor.gapCode),
      outcome: 'RESOLVED',
      durationBucket: guidedTelemetryDurationBucket(
        performance.now() - (remediationStartedAt.current ?? performance.now()),
      ),
    });
    remediationStartedAt.current = null;
    setRemediation((current) => current?.requestId === remediation.requestId
      && current.outcome === 'STILL_BLOCKED'
      ? { ...current, outcome: 'RESOLVED' }
      : current);
  }, [authoritativeGaps, remediation, selected?.packageId, telemetry]);

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

  const gaps = authoritativeGaps;
  const firstBlocker = gaps.find((gap) => gap.severity === 'BLOCKING') ?? null;
  const blockerCount = gaps.filter((gap) => gap.severity === 'BLOCKING').length;
  const offline = catalog.scope.environmentId === 'offline';
  const dirty = authoritativeDirty;

  const selectTask = (task: BusinessMirrorTaskId) => {
    setActiveTask(task);
    setAssetFocus(null);
    setRemediation(null);
    remediationStartedAt.current = null;
    replaceWorkspaceQuery(selected.packageId, task);
  };
  const remediate = (gap: BusinessMirrorGap) => {
    const descriptor = remediationDescriptorForGap(gap);
    remediationSequence.current += 1;
    remediationStartedAt.current = performance.now();
    telemetry.record('REMEDIATION_STARTED', {
      gapCode: guidedTelemetryGapCode(descriptor.gapCode),
      actionKind: descriptor.actionKind,
      sameStep: descriptor.taskId === activeTask,
    });
    setActiveTask(descriptor.taskId);
    setAssetFocus(null);
    setRemediation({
      requestId: remediationSequence.current,
      packageId: selected.packageId,
      descriptor,
      outcome: 'LOCATING',
    });
    replaceWorkspaceQuery(selected.packageId, descriptor.taskId, {
      gapCode: gap.code,
      remediationAnchor: descriptor.anchor,
    });
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
    if (!selected.stored) return false;
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
      return true;
    } catch (cause) {
      setCommand({ kind: 'error', detail: errorDetail(cause) });
      return false;
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
    <main className="business-mirror-workspace" ref={workspace}>
      <BusinessMirrorNavigationGuard
        dirty={dirty}
        saving={command.kind === 'running' && command.operation === 'save'}
        draft={editor}
        onDiscard={() => setEditor(structuredClone(selected.stored?.draft ?? editor))}
        onSave={runSave}
      />
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
        onFix={() => firstBlocker && remediate(firstBlocker)}
      />

      {remediation && (
        <div
          className={`business-mirror-remediation-notice ${remediation.outcome.toLowerCase()}`}
          role={remediation.outcome === 'FAILED' ? 'alert' : 'status'}
          aria-live="polite"
          data-testid="business-mirror-remediation-outcome"
        >
          {remediation.outcome === 'LOCATING'
            ? <LoaderCircle aria-hidden="true" className="spin" size={16} />
            : remediation.outcome === 'RESOLVED'
              ? <Check aria-hidden="true" size={16} />
              : remediation.outcome === 'NAVIGATED'
                ? <ChevronRight aria-hidden="true" size={16} />
                : <SlidersHorizontal aria-hidden="true" size={16} />}
          <span>
            <strong>{remediation.descriptor.gapCode}</strong>
            <small>{m(remediation.outcome === 'FAILED'
              ? 'businessMirror.remediation.unavailable'
              : remediation.outcome === 'LOCATING'
                ? 'businessMirror.remediation.locating'
                : remediation.outcome === 'RESOLVED'
                  ? 'businessMirror.remediation.resolved'
                  : remediation.outcome === 'NAVIGATED'
                    ? 'businessMirror.remediation.navigated'
                    : 'businessMirror.remediation.targeted', {
              capability: remediation.descriptor.capabilityRequired,
            })}</small>
          </span>
        </div>
      )}

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
            onRemediate={remediate}
            onTask={selectTask}
            telemetry={telemetry}
          />
        </section>

        <aside className="business-mirror-evidence-rail">
          <GapInventory gaps={gaps} onSelect={remediate} />
          <Lineage item={selected} />
        </aside>
      </div>
    </main>
  );
}

function BusinessMirrorNavigationGuard({
  dirty,
  saving,
  draft,
  onDiscard,
  onSave,
}: {
  dirty: boolean;
  saving: boolean;
  draft: BusinessMirrorPackageDraft;
  onDiscard(): void;
  onSave(): Promise<boolean>;
}) {
  useWorkspaceNavigationGuard(useMemo(() => ({
    lifecycle: saving ? 'SAVING' : dirty ? 'DIRTY' : 'SAVED',
    flushRecovery: async () => false,
    save: onSave,
    exportRecovery: () => exportBusinessMirrorRecovery(draft),
    discard: async () => onDiscard(),
  }), [dirty, draft, onDiscard, onSave, saving]));
  return null;
}

function exportBusinessMirrorRecovery(draft: BusinessMirrorPackageDraft): void {
  const blob = new Blob([JSON.stringify({
    schemaVersion: 'bloge.businessMirrorRecovery.v1',
    exportedAt: new Date().toISOString(),
    draft,
  }, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `${draft.packageId.replace(/[^A-Za-z0-9._-]/g, '_')}-recovery.json`;
  link.click();
  URL.revokeObjectURL(url);
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
  onRemediate,
  onTask,
  telemetry,
}: {
  task: BusinessMirrorTaskId;
  item: BusinessMirrorPortfolioItem;
  draft: BusinessMirrorPackageDraft;
  gaps: BusinessMirrorGap[];
  editable: boolean;
  assetFocus: BusinessAssetFocus | null;
  onDraft(draft: BusinessMirrorPackageDraft): void;
  onRemediate(gap: BusinessMirrorGap): void;
  onTask(task: BusinessMirrorTaskId): void;
  telemetry: GuidedAuthoringTelemetry;
}) {
  const contract = getBusinessMirrorStepContract(task);
  let content: ReactNode;
  if (task === 'problem') {
    content = <ProblemTask draft={draft} editable={editable} onDraft={onDraft} />;
  } else if (task === 'boundary') {
    content = <BoundaryTask draft={draft} gaps={gaps} editable={editable} onDraft={onDraft} />;
  } else if (task === 'capabilities') {
    content = <CapabilityTask item={item} draft={draft} focus={assetFocus}
      editable={editable} onDraft={onDraft} telemetry={telemetry} />;
  } else if (task === 'scenarios') {
    content = <ScenarioTask item={item} draft={draft} editable={editable} onDraft={onDraft} />;
  } else if (task === 'rehearsal') {
    content = <RehearsalTask draft={draft} />;
  } else if (task === 'evidence') {
    content = <EvidenceTask item={item} />;
  } else {
    content = <CalibrateTask draft={draft} editable={editable} onDraft={onDraft} />;
  }
  return (
    <GuidedTaskShell
      contract={contract}
      gaps={gaps}
      progress={businessMirrorTaskProgress(task, gaps)}
      readOnly={!editable}
      inputStates={guidedInputStates(draft, item)}
      onRemediate={onRemediate}
      onTask={onTask}
    >
      {content}
    </GuidedTaskShell>
  );
}

function guidedInputStates(
  draft: BusinessMirrorPackageDraft,
  item: BusinessMirrorPortfolioItem,
): Record<string, GuidedInputState> {
  const definition = draft.businessDefinition;
  const state = (available: boolean): GuidedInputState => available ? 'READY' : 'MISSING';
  return {
    domain: state(Boolean(definition.domainId)),
    taxonomy: state(definition.problemTaxonomyRef !== null),
    problemCode: state(Boolean(definition.problemCode)),
    businessGoal: state(Boolean(definition.businessGoal)),
    expectedOutcome: state(Boolean(definition.expectedOutcome)),
    accountableOwner: state(Boolean(definition.accountableOwner)),
    contract: state(draft.packageContractRef !== null),
    state: state(draft.stateModelRefs.length > 0),
    effect: state(draft.effectModelRefs.length > 0),
    ownerConfirmation: state(Boolean(draft.provenance.approvedBy)),
    executable: state(draft.capabilityRefs.length > 0 || draft.graphRefs.length > 0
      || Boolean(item.projection.projectedCapabilityRef.id)),
    solution: state(draft.solutionRefs.length > 0),
    carrier: state(draft.carrierRefs.length > 0),
    channel: state(draft.channelRefs.length > 0),
    scenarioInventory: state(draft.scenarioInventoryRef !== null),
    scenarioPack: state(draft.scenarioPackRefs.length > 0),
    discoveredSuiteDisposition: state(item.projection.discoveredTestSuiteRefs.length === 0
      || (draft.scenarioInventoryRef !== null && draft.scenarioPackRefs.length > 0)),
    mirrorPlan: 'MISSING',
    evidencePortfolio: 'REVIEW',
    ownerTasks: 'REVIEW',
    fidelity: state(draft.fidelityInventoryRef !== null),
    outcome: state(draft.outcomeDefinitionRefs.length > 0),
    approval: state(Boolean(draft.provenance.approvedBy)),
  };
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
      <div className="business-mirror-reference-grid">
        <BusinessMirrorReferenceBindingControl
          currentStableId={definition.domainId}
          draft={draft}
          editable={editable}
          field="domain"
          help="businessMirror.reference.help.domain"
          label="businessMirror.field.domain"
          onDraft={onDraft}
          remediationAnchor="business-mirror.problem.domain"
        />
        <BusinessMirrorReferenceBindingControl
          currentReferences={definition.problemTaxonomyRef ? [definition.problemTaxonomyRef] : []}
          draft={draft}
          editable={editable}
          field="taxonomy"
          help="businessMirror.reference.help.taxonomy"
          label="businessMirror.problem.taxonomy"
          onDraft={onDraft}
          remediationAnchor="business-mirror.problem.taxonomy"
        />
      </div>
      <fieldset className="business-mirror-form" disabled={!editable}>
        <label data-remediation-anchor="business-mirror.problem.code">
          <span>{m('businessMirror.field.problemCode')}</span>
          <input value={definition.problemCode} placeholder={m('businessMirror.field.problemCodePlaceholder')}
            onChange={(event) => update('problemCode', event.target.value)} />
        </label>
        <label className="wide" data-remediation-anchor="business-mirror.problem.goal">
          <span>{m('businessMirror.field.goal')}</span>
          <textarea value={definition.businessGoal} placeholder={m('businessMirror.field.goalPlaceholder')}
            onChange={(event) => update('businessGoal', event.target.value)} />
        </label>
        <label className="wide" data-remediation-anchor="business-mirror.problem.outcome">
          <span>{m('businessMirror.field.outcome')}</span>
          <textarea value={definition.expectedOutcome} placeholder={m('businessMirror.field.outcomePlaceholder')}
            onChange={(event) => update('expectedOutcome', event.target.value)} />
        </label>
        <label>
          <span>{m('businessMirror.field.risk')}</span>
          <select value={definition.riskClass} onChange={(event) => update('riskClass', event.target.value)}>
            {(['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] as const)
              .map((risk) => <option key={risk} value={risk}>{risk}</option>)}
          </select>
        </label>
      </fieldset>
      <div className="business-mirror-reference-grid">
        <BusinessMirrorReferenceBindingControl
          currentStableId={definition.accountableOwner}
          draft={draft}
          editable={editable}
          field="accountableOwner"
          help="businessMirror.reference.help.owner"
          label="businessMirror.field.owner"
          onDraft={onDraft}
          remediationAnchor="business-mirror.problem.owner"
        />
      </div>
    </>
  );
}

function BoundaryTask({
  draft,
  gaps,
  editable,
  onDraft,
}: {
  draft: BusinessMirrorPackageDraft;
  gaps: BusinessMirrorGap[];
  editable: boolean;
  onDraft(draft: BusinessMirrorPackageDraft): void;
}) {
  const { m } = useI18n();
  const ownerReview = gaps.some((gap) => gap.code === 'GRAPH_CONTRACT_OWNER_CONFIRMATION_MISSING');
  return (
    <>
      <div className="business-mirror-reference-grid">
        <BusinessMirrorReferenceBindingControl
          currentReferences={draft.packageContractRef ? [draft.packageContractRef] : []}
          draft={draft} editable={editable} field="contract"
          help="businessMirror.reference.help.contract" label="businessMirror.boundary.contract"
          onDraft={onDraft} remediationAnchor="business-mirror.boundary.contract"
        />
        <BusinessMirrorReferenceBindingControl
          currentReferences={draft.stateModelRefs}
          draft={draft} editable={editable} field="stateModel"
          help="businessMirror.reference.help.state" label="businessMirror.boundary.state"
          onDraft={onDraft} remediationAnchor="business-mirror.boundary.state"
        />
        <BusinessMirrorReferenceBindingControl
          currentReferences={draft.effectModelRefs}
          draft={draft} editable={editable} field="effectModel"
          help="businessMirror.reference.help.effect" label="businessMirror.boundary.effect"
          onDraft={onDraft} remediationAnchor="business-mirror.boundary.effect"
        />
        <BusinessMirrorReferenceBindingControl
          currentStableId={draft.provenance.approvedBy}
          draft={draft} editable={editable} field="approvalOwner"
          help="businessMirror.reference.help.approval" label="businessMirror.calibrate.approval"
          onDraft={onDraft} remediationAnchor="business-mirror.boundary.owner-confirmation"
        />
      </div>
      {ownerReview && (
        <p className="business-mirror-inline-warning">
          {m('businessMirror.boundary.ownerReview')}
        </p>
      )}
    </>
  );
}

function CapabilityTask({
  item,
  draft,
  focus,
  editable,
  onDraft,
  telemetry,
}: {
  item: BusinessMirrorPortfolioItem;
  draft: BusinessMirrorPackageDraft;
  focus: BusinessAssetFocus | null;
  editable: boolean;
  onDraft(draft: BusinessMirrorPackageDraft): void;
  telemetry: GuidedAuthoringTelemetry;
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
          <div
            key={layer.id}
            className="capability-layer"
          >
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
      <CrossWorkspaceAuthorLink
        subject={{
          graphName: item.graphName,
          graphRef: item.projection.sourceGraphRef,
          packageId: item.packageId,
        }}
        label={m('businessMirror.capability.openGraph')}
        resolvingLabel={m('businessMirror.capability.resolvingGraph')}
        failedLabel={m('businessMirror.capability.graphLinkFailed')}
        retryLabel={m('businessMirror.command.retry')}
        telemetry={telemetry}
      />
      <div className="business-mirror-reference-grid">
        <BusinessMirrorReferenceBindingControl
          currentReferences={draft.solutionRefs}
          draft={draft} editable={editable} field="solution"
          help="businessMirror.reference.help.solution" label="businessMirror.capability.l1"
          onDraft={onDraft} remediationAnchor="business-mirror.capabilities.solution"
        />
        <BusinessMirrorReferenceBindingControl
          currentReferences={draft.carrierRefs}
          draft={draft} editable={editable} field="carrier"
          help="businessMirror.reference.help.carrier" label="businessMirror.capability.l2"
          onDraft={onDraft} remediationAnchor="business-mirror.capabilities.carrier"
        />
        <BusinessMirrorReferenceBindingControl
          currentReferences={draft.channelRefs}
          draft={draft} editable={editable} field="channel"
          help="businessMirror.reference.help.channel" label="businessMirror.capability.l3"
          onDraft={onDraft} remediationAnchor="business-mirror.capabilities.channel"
        />
      </div>
    </>
  );
}

function ScenarioTask({
  item,
  draft,
  editable,
  onDraft,
}: {
  item: BusinessMirrorPortfolioItem;
  draft: BusinessMirrorPackageDraft;
  editable: boolean;
  onDraft(draft: BusinessMirrorPackageDraft): void;
}) {
  const { m } = useI18n();
  return (
    <>
      <div
        className="business-mirror-scenario-discovery"
      >
        <FileCheck2 aria-hidden="true" size={22} />
        <strong>{m('businessMirror.scenario.discovered', {
          count: item.projection.discoveredTestSuiteRefs.length,
        })}</strong>
        {item.projection.discoveredTestSuiteRefs.map((ref) => <code key={ref.id}>{ref.id}</code>)}
      </div>
      <p className="business-mirror-inline-warning">{m('businessMirror.scenario.warning')}</p>
      <a
        className="business-mirror-secondary-link"
        data-remediation-anchor="business-mirror.scenarios.discovered-suite"
        href={workspaceHref('correctness', draft.packageId, 'scenarios', 'discovered-suite')}
      >
        <Search aria-hidden="true" size={16} />
        {m('businessMirror.command.openCorrectness')}
      </a>
      <div className="business-mirror-reference-grid">
        <BusinessMirrorReferenceBindingControl
          currentReferences={draft.scenarioInventoryRef ? [draft.scenarioInventoryRef] : []}
          draft={draft} editable={editable} field="scenarioInventory"
          help="businessMirror.reference.help.inventory" label="businessMirror.scenario.inventory"
          onDraft={onDraft} remediationAnchor="business-mirror.scenarios.inventory"
        />
        <BusinessMirrorReferenceBindingControl
          currentReferences={draft.scenarioPackRefs}
          draft={draft} editable={editable} field="scenarioPack"
          help="businessMirror.reference.help.pack" label="businessMirror.scenario.pack"
          onDraft={onDraft} remediationAnchor="business-mirror.scenarios.pack"
        />
      </div>
    </>
  );
}

function RehearsalTask({ draft }: { draft: BusinessMirrorPackageDraft }) {
  const { m } = useI18n();
  const ready = draft.scenarioPackRefs.length > 0;
  return (
    <>
      <div
        className={`business-mirror-stage-message ${ready ? 'ready' : 'blocked'}`}
      >
        {ready ? <Play aria-hidden="true" size={24} /> : <CircleAlert aria-hidden="true" size={24} />}
        <p>{m(ready ? 'businessMirror.rehearsal.ready' : 'businessMirror.rehearsal.blocked')}</p>
      </div>
      <a
        className="business-mirror-secondary-link"
        data-remediation-anchor="business-mirror.rehearsal.mirror-plan"
        href={workspaceHref('rehearsals', draft.packageId, 'rehearsal', 'mirror-plan')}
      >
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
      <div data-remediation-anchor="business-mirror.evidence.portfolio">
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
      </div>
      {detail && state === 'available' && <p className="business-mirror-inline-warning" role="alert">{detail}</p>}
    </>
  );
}

function ratio(value: number | undefined): string {
  return value === undefined ? '-' : `${Math.round(value * 1000) / 10}%`;
}

function CalibrateTask({
  draft,
  editable,
  onDraft,
}: {
  draft: BusinessMirrorPackageDraft;
  editable: boolean;
  onDraft(draft: BusinessMirrorPackageDraft): void;
}) {
  const { m } = useI18n();
  return (
    <>
      <div className="business-mirror-reference-grid">
        <BusinessMirrorReferenceBindingControl
          currentReferences={draft.fidelityInventoryRef ? [draft.fidelityInventoryRef] : []}
          draft={draft} editable={editable} field="fidelityInventory"
          help="businessMirror.reference.help.fidelity" label="businessMirror.calibrate.fidelity"
          onDraft={onDraft} remediationAnchor="business-mirror.calibrate.fidelity"
        />
        <BusinessMirrorReferenceBindingControl
          currentReferences={draft.outcomeDefinitionRefs}
          draft={draft} editable={editable} field="outcomeDefinition"
          help="businessMirror.reference.help.outcome" label="businessMirror.calibrate.outcome"
          onDraft={onDraft} remediationAnchor="business-mirror.calibrate.outcome"
        />
        <BusinessMirrorReferenceBindingControl
          currentStableId={draft.provenance.approvedBy}
          draft={draft} editable={editable} field="approvalOwner"
          help="businessMirror.reference.help.approval" label="businessMirror.calibrate.approval"
          onDraft={onDraft} remediationAnchor="business-mirror.calibrate.approval"
        />
      </div>
      <p className="business-mirror-governance-note">
        <ShieldCheck aria-hidden="true" size={20} />
        {m('businessMirror.calibrate.governance')}
      </p>
    </>
  );
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

function replaceWorkspaceQuery(
  packageId: string,
  task: BusinessMirrorTaskId,
  remediation?: { gapCode: string; remediationAnchor: string },
): void {
  const params = new URLSearchParams(window.location.search);
  if (packageId) {
    params.set('packageId', packageId);
    params.set('task', task);
  } else {
    params.delete('packageId');
    params.delete('task');
  }
  if (packageId && remediation) {
    params.set('gapCode', remediation.gapCode);
    params.set('remediationAnchor', remediation.remediationAnchor);
  } else {
    params.delete('gapCode');
    params.delete('remediationAnchor');
  }
  ['compilationRevision', 'assetKind', 'assetId', 'assetRevision', 'assetAuthority']
    .forEach((key) => params.delete(key));
  const search = params.toString();
  window.history.replaceState({}, '', `${window.location.pathname}${search ? `?${search}` : ''}`);
}

function workspaceHref(
  route: string,
  packageId: string,
  returnTask: BusinessMirrorTaskId,
  returnAnchor: string,
): string {
  const source = new URLSearchParams(window.location.search);
  const params = new URLSearchParams();
  ['lang', 'sessionTenantId'].forEach((key) => {
    const value = source.get(key);
    if (value) params.set(key, value);
  });
  params.set('returnRoute', 'business-mirror');
  params.set('returnPackageId', packageId);
  params.set('returnTask', returnTask);
  params.set('returnAnchor', returnAnchor);
  if (typeof globalThis.acquireVsCodeApi === 'function') {
    params.set('workspaceRoute', route);
    return `?${params.toString()}`;
  }
  return `/${route}/?${params.toString()}`;
}

function isCrossWorkspaceRemediation(actionKind: RemediationDescriptor['actionKind']): boolean {
  return actionKind === 'OPEN_AUTHOR'
    || actionKind === 'OPEN_REHEARSAL'
    || actionKind === 'OPEN_CORRECTNESS'
    || actionKind === 'OPEN_GOVERNANCE';
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
