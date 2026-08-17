import { useEffect, useMemo, useState } from 'react';
import {
  AlertTriangle,
  Archive,
  BarChart3,
  BookCheck,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  ChevronUp,
  CircleGauge,
  Database,
  FileCheck2,
  LoaderCircle,
  PlayCircle,
  RefreshCw,
  ShieldCheck,
} from 'lucide-react';

import { useI18n } from '../i18n/I18nProvider';
import {
  fetchCorrectnessCapabilities,
  fetchCorrectnessDefinitions,
  fetchCorrectnessTargets,
  fetchCorrectnessWorkspace,
} from './api/correctnessApi';
import type { ReferenceCandidate, ReferenceQuery } from '../shared/reference-picker/types';
import type {
  CorrectnessApiEnvelope,
  CorrectnessDeploymentCapabilities,
  CorrectnessTargetKind,
  CorrectnessWorkspaceCoordinate,
  CorrectnessWorkspaceProjection,
} from './model/domain';
import RunCenter from './runs/RunCenter';
import FiveAxisVerdict from './shared/FiveAxisVerdict';
import CoverageStudio from './authoring/CoverageStudio';
import CaseStudio from './authoring/CaseStudio';
import OracleStudio from './authoring/OracleStudio';
import FixtureStudio from './authoring/FixtureStudio';
import PublicationStudio from './authoring/PublicationStudio';
import CorrectnessI18nProvider from './CorrectnessI18nProvider';
import CorrectnessWorkspaceLauncher from './launcher/CorrectnessWorkspaceLauncher';
import {
  noopGuidedAuthoringTelemetry,
  type GuidedAuthoringTelemetry,
} from '../shared/guided-telemetry/guidedTelemetry';
import './styles.css';

type CorrectnessView = 'overview' | 'coverage' | 'cases' | 'fixtures' | 'oracle' | 'runs';
type CorrectnessStage = 'verdict' | 'definition' | 'evidence';
type LoadState = 'CAPABILITIES' | 'CONNECT' | 'WORKSPACE' | 'READY' | 'UNAVAILABLE' | 'ERROR';

const CORRECTNESS_GUIDANCE_COLLAPSED_KEY = 'bloge.correctness.guidance.collapsed.v1';

export interface CorrectnessStudioApi {
  capabilities(): Promise<CorrectnessDeploymentCapabilities>;
  workspace(coordinate: CorrectnessWorkspaceCoordinate):
  Promise<CorrectnessApiEnvelope<CorrectnessWorkspaceProjection>>;
  targets(kind: CorrectnessTargetKind, request: ReferenceQuery, signal: AbortSignal):
  ReturnType<typeof fetchCorrectnessTargets>;
  definitions(target: ReferenceCandidate, request: ReferenceQuery, signal: AbortSignal):
  ReturnType<typeof fetchCorrectnessDefinitions>;
}

const DEFAULT_API: CorrectnessStudioApi = {
  capabilities: fetchCorrectnessCapabilities,
  workspace: fetchCorrectnessWorkspace,
  targets: fetchCorrectnessTargets,
  definitions: fetchCorrectnessDefinitions,
};

const VIEWS: Array<{
  id: CorrectnessView;
  label: string;
  icon: typeof BarChart3;
}> = [
  { id: 'overview', label: 'Overview', icon: CircleGauge },
  { id: 'coverage', label: 'Coverage', icon: BarChart3 },
  { id: 'cases', label: 'Cases', icon: BookCheck },
  { id: 'fixtures', label: 'Fixtures', icon: Database },
  { id: 'oracle', label: 'Oracle', icon: FileCheck2 },
  { id: 'runs', label: 'Runs', icon: PlayCircle },
];

export interface CorrectnessStudioProps {
  api?: CorrectnessStudioApi;
  telemetry?: GuidedAuthoringTelemetry;
}

export default function CorrectnessStudio(props: CorrectnessStudioProps) {
  return (
    <CorrectnessI18nProvider>
      <CorrectnessStudioSurface {...props} />
    </CorrectnessI18nProvider>
  );
}

function CorrectnessStudioSurface({
  api = DEFAULT_API,
  telemetry = noopGuidedAuthoringTelemetry,
}: CorrectnessStudioProps) {
  const { t } = useI18n();
  const initial = useMemo(() => parseRoute(window.location.search), []);
  const [coordinate, setCoordinate] = useState<CorrectnessWorkspaceCoordinate | null>(initial.coordinate);
  const [view, setView] = useState<CorrectnessView>(initial.view);
  const [deployment, setDeployment] = useState<CorrectnessDeploymentCapabilities | null>(null);
  const [workspace, setWorkspace] = useState<CorrectnessWorkspaceProjection | null>(null);
  const [state, setState] = useState<LoadState>('CAPABILITIES');
  const [error, setError] = useState('');
  const [reloadEpoch, setReloadEpoch] = useState(0);
  const coordinateKey = coordinate
    ? `${coordinate.targetKind}:${coordinate.targetId}:${coordinate.targetFingerprint}:${coordinate.definitionId ?? ''}`
    : '';

  useEffect(() => {
    let active = true;
    setState('CAPABILITIES');
    setError('');
    api.capabilities().then((value) => {
      if (!active) return;
      setDeployment(value);
      if (value.features.correctnessWorkspaceApi !== true) {
        setState('UNAVAILABLE');
        return;
      }
      setState(coordinate ? 'WORKSPACE' : 'CONNECT');
    }).catch((cause: unknown) => {
      if (!active) return;
      setError(errorMessage(cause));
      setState('ERROR');
    });
    return () => { active = false; };
  }, [api]);

  useEffect(() => {
    if (!coordinate || !deployment || deployment.features.correctnessWorkspaceApi !== true) return;
    let active = true;
    setState('WORKSPACE');
    setError('');
    api.workspace(coordinate).then((response) => {
      if (!active) return;
      setWorkspace(response.data);
      setState('READY');
    }).catch((cause: unknown) => {
      if (!active) return;
      setWorkspace(null);
      setError(errorMessage(cause));
      setState('ERROR');
    });
    return () => { active = false; };
  }, [api, coordinateKey, deployment, reloadEpoch]);

  const openCoordinate = (next: CorrectnessWorkspaceCoordinate) => {
    const params = new URLSearchParams(window.location.search);
    params.set('targetKind', next.targetKind);
    params.set('targetId', next.targetId);
    params.set('targetFingerprint', next.targetFingerprint);
    setOptional(params, 'definitionId', next.definitionId);
    params.set('correctnessView', view);
    window.history.replaceState({}, '', `${window.location.pathname}?${params}${window.location.hash}`);
    setCoordinate(next);
  };

  const changeView = (next: CorrectnessView) => {
    const params = new URLSearchParams(window.location.search);
    params.set('correctnessView', next);
    window.history.replaceState({}, '', `${window.location.pathname}?${params}${window.location.hash}`);
    setView(next);
  };

  const activeStage = correctnessStageForView(view);

  useEffect(() => {
    if (!workspace) return;
    telemetry.record('GUIDED_STEP_VIEWED', {
      workspace: 'CORRECTNESS',
      step: correctnessStageTelemetryStep(activeStage),
      status: correctnessStageStatus(activeStage, workspace),
    });
  }, [activeStage, telemetry, workspace?.queryFingerprint]);

  if (state === 'CAPABILITIES') {
    return <WorkspaceState icon={<LoaderCircle className="spin" size={22} />} title={t('Checking deployment capabilities')} />;
  }

  if (state === 'UNAVAILABLE') {
    return (
      <WorkspaceState
        icon={<AlertTriangle size={22} />}
        title={t('Correctness Studio is not available')}
        detail={t('This deployment does not advertise the correctness workspace API. Enable the correctness authoring runtime before opening a target.')}
      />
    );
  }

  if ((state === 'CONNECT' || (!workspace && !coordinate)) && deployment) {
    return (
      <CorrectnessWorkspaceLauncher
        deployment={deployment}
        onOpen={openCoordinate}
        searchDefinitions={api.definitions}
        searchTargets={api.targets}
        telemetry={telemetry}
      />
    );
  }

  if (state === 'ERROR' && !workspace) {
    return (
      <WorkspaceState
        icon={<AlertTriangle size={22} />}
        title={t('The correctness workspace could not be loaded')}
        detail={error}
        action={coordinate ? {
          label: t('Retry exact target'),
          onClick: () => setReloadEpoch((value) => value + 1),
        } : undefined}
      />
    );
  }

  if (state === 'WORKSPACE' || !workspace || !deployment) {
    return <WorkspaceState icon={<LoaderCircle className="spin" size={22} />} title={t('Loading exact correctness projection')} />;
  }

  return (
    <main className="correctness-studio">
      <header className="correctness-workspace-header">
        <div className="correctness-workspace-identity">
          <p className="eyebrow">{t('CORRECTNESS STUDIO')}</p>
          <div>
            <h2>{workspace.definition.title}</h2>
            <span className="correctness-risk" data-risk={workspace.definition.riskLevel}>
              {t(workspace.definition.riskLevel)}
            </span>
          </div>
          <p>{workspace.definition.businessIntent}</p>
        </div>
        <div className="correctness-workspace-coordinate" aria-label={t('Exact target coordinate')}>
          <span>{workspace.target.kind}</span>
          <strong>{workspace.target.id}</strong>
          <code>r{workspace.target.revision} · {shortFingerprint(workspace.target.fingerprint)}</code>
        </div>
      </header>

      <div className="correctness-workspace-context">
        <span><strong>{t('Owner')}</strong>{workspace.definition.owner.displayName}</span>
        <span><strong>{t('Lifecycle')}</strong>{t(workspace.definition.lifecycle)}</span>
        <span><strong>{t('Cases')}</strong>{workspace.cases.total}</span>
        <span><strong>{t('Reviews pending')}</strong>{workspace.reviews.pending}</span>
        <span><strong>{t('Last run')}</strong>{workspace.lastRun?.runId ?? t('No evidence yet')}</span>
        <button type="button" onClick={() => setReloadEpoch((value) => value + 1)} title={t('Refresh workspace')}>
          <RefreshCw aria-hidden="true" size={17} />
          <span className="visually-hidden">{t('Refresh workspace')}</span>
        </button>
      </div>

      <GuidedCorrectnessTaskBand
        activeStage={activeStage}
        workspace={workspace}
        onOpen={changeView}
      />

      <nav className="correctness-view-tabs" aria-label={t('Correctness workspace views')}>
        {VIEWS.map((item) => {
          const Icon = item.icon;
          return (
            <button
              type="button"
              key={item.id}
              aria-selected={view === item.id}
              role="tab"
              onClick={() => changeView(item.id)}
            >
              <Icon aria-hidden="true" size={17} />
              {t(item.label)}
              <ViewCount view={item.id} workspace={workspace} />
            </button>
          );
        })}
      </nav>

      <section className="correctness-view" role="tabpanel">
        {view === 'overview' && <Overview
          workspace={workspace}
          deployment={deployment}
          onOpen={changeView}
          onRefresh={() => setReloadEpoch((current) => current + 1)}
        />}
        {view === 'coverage' && <Coverage workspace={workspace} deployment={deployment} />}
        {view === 'cases' && <Cases workspace={workspace} deployment={deployment} />}
        {view === 'fixtures' && <Fixtures workspace={workspace} deployment={deployment} />}
        {view === 'oracle' && <Oracle workspace={workspace} deployment={deployment} />}
        {view === 'runs' && <RunCenter workspace={workspace} deployment={deployment} />}
      </section>
    </main>
  );
}

function Overview({
  workspace,
  deployment,
  onOpen,
  onRefresh,
}: {
  workspace: CorrectnessWorkspaceProjection;
  deployment: CorrectnessDeploymentCapabilities;
  onOpen(view: CorrectnessView): void;
  onRefresh(): void;
}) {
  const { t } = useI18n();
  const coveragePercent = percent(workspace.coverage.fulfilled, workspace.coverage.total);
  const runAttentionAction = (command: string) => {
    if (command === 'REFRESH_STALE_ASSETS') {
      onRefresh();
      return;
    }
    onOpen(correctnessViewForCommand(command));
  };
  return (
    <div className="correctness-overview">
      <FiveAxisVerdict verdict={workspace.verdict} />
      <section className="correctness-success-criteria">
        <header className="correctness-section-header compact">
          <div><p className="eyebrow">{t('BUSINESS AUTHORITY')}</p><h3>{t('What must be true')}</h3></div>
          <span className="correctness-coordinate">{workspace.definition.successCriteria.length}</span>
        </header>
        <ol>
          {workspace.definition.successCriteria.map((criterion) => <li key={criterion}>{criterion}</li>)}
        </ol>
      </section>
      <div className="correctness-overview-grid">
        <OverviewLink
          icon={<BarChart3 size={19} />}
          label={t('Coverage denominator')}
          value={workspace.coverage.availability === 'AVAILABLE' ? `${coveragePercent}%` : t('Unavailable')}
          detail={t('{fulfilled} fulfilled / {total} frozen obligations', {
            fulfilled: workspace.coverage.fulfilled,
            total: workspace.coverage.total,
          })}
          onClick={() => onOpen('coverage')}
        />
        <OverviewLink
          icon={<BookCheck size={19} />}
          label={t('Canonical Cases')}
          value={String(workspace.cases.total)}
          detail={t('{count} awaiting review', { count: workspace.reviews.pending })}
          onClick={() => onOpen('cases')}
        />
        <OverviewLink
          icon={<Database size={19} />}
          label={t('Fixture assets')}
          value={String(workspace.fixtures.total)}
          detail={t('{active} active / {stale} stale', {
            active: workspace.fixtures.active,
            stale: workspace.fixtures.stale,
          })}
          onClick={() => onOpen('fixtures')}
        />
        <OverviewLink
          icon={<FileCheck2 size={19} />}
          label={t('Approved business Oracles')}
          value={String(workspace.oracleAssertions.approvedOracles)}
          detail={t('{valid} executable / {stale} stale Assertion Sets', {
            valid: workspace.oracleAssertions.validAssertionSets,
            stale: workspace.oracleAssertions.staleAssertionSets,
          })}
          onClick={() => onOpen('oracle')}
        />
      </div>
      {(workspace.staleReasons.length > 0 || workspace.verdict.nextActions.length > 0) && (
        <section className="correctness-attention">
          <header><ShieldCheck aria-hidden="true" size={19} /><strong>{t('Required attention')}</strong></header>
          <div className="correctness-attention-list">
            {workspace.verdict.nextActions.map((action) => (
              <article key={`${action.command}:${action.reasonCode}`}>
                <div>
                  <strong>{t(correctnessCommandLabel(action.command))}</strong>
                  <p><b>{t('Why')}</b>{t(correctnessReasonLabel(action.reasonCode))}</p>
                  <p><b>{t('Impact')}</b>{t(correctnessActionImpact(action.command))}</p>
                  <p><b>{t('Done when')}</b>{t(correctnessActionCompletion(action.command))}</p>
                </div>
                <button type="button" onClick={() => runAttentionAction(action.command)}>
                  {t(correctnessCommandActionLabel(action.command))}
                  <ChevronRight aria-hidden="true" size={16} />
                </button>
              </article>
            ))}
            {workspace.staleReasons
              .filter((reason) => !workspace.verdict.nextActions.some((action) => action.reasonCode === reason.code))
              .map((reason) => (
                <article key={`${reason.code}:${reason.assetKind}`}>
                  <div>
                    <strong>{t(correctnessReasonLabel(reason.code))}</strong>
                    <p><b>{t('Affected asset')}</b>{t(correctnessAssetLabel(reason.assetKind))}</p>
                    <p><b>{t('Done when')}</b>{t('The exact asset is refreshed and the workspace verdict is recalculated.')}</p>
                  </div>
                  <button type="button" onClick={onRefresh}>
                    {t('Refresh workspace')}
                    <RefreshCw aria-hidden="true" size={16} />
                  </button>
                </article>
              ))}
          </div>
        </section>
      )}
      <PublicationStudio
        workspace={workspace}
        compilationAvailable={deployment.features.correctnessCompilationApi === true}
        publicationAvailable={deployment.features.correctnessPublicationApi === true}
        onPublished={onRefresh}
      />
    </div>
  );
}

function GuidedCorrectnessTaskBand({
  activeStage,
  workspace,
  onOpen,
}: {
  activeStage: CorrectnessStage;
  workspace: CorrectnessWorkspaceProjection;
  onOpen(view: CorrectnessView): void;
}) {
  const { t } = useI18n();
  const [collapsed, setCollapsed] = useState(() => readGuidanceCollapsedPreference());
  const stages = correctnessStages(workspace);
  const active = stages.find((stage) => stage.id === activeStage) ?? stages[0];
  const toggleGuidance = () => {
    const next = !collapsed;
    setCollapsed(next);
    try {
      window.localStorage.setItem(CORRECTNESS_GUIDANCE_COLLAPSED_KEY, String(next));
    } catch {
      // Private browser profiles may deny preference storage; the in-memory state still works.
    }
  };
  return (
    <section
      className="correctness-guided-band"
      aria-label={t('Guided correctness workflow')}
      data-collapsed={collapsed}
    >
      <div className="correctness-guided-heading">
        <strong>{t('Guided correctness workflow')}</strong>
        <button type="button" aria-expanded={!collapsed} onClick={toggleGuidance}>
          {collapsed ? <ChevronDown aria-hidden="true" size={16} /> : <ChevronUp aria-hidden="true" size={16} />}
          {t(collapsed ? 'Show guidance' : 'Hide guidance')}
        </button>
      </div>
      <div className="correctness-guided-stages">
        {stages.map((stage, index) => (
          <button
            type="button"
            key={stage.id}
            aria-current={stage.id === activeStage ? 'step' : undefined}
            data-status={stage.status}
            onClick={() => onOpen(stage.view)}
          >
            <span>{index + 1}</span>
            <span><strong>{t(stage.title)}</strong>{!collapsed && <small>{t(stage.question)}</small>}</span>
            <CorrectnessStageStatus status={stage.status} />
          </button>
        ))}
      </div>
      {!collapsed && (
        <div className="correctness-guided-detail" role="status">
          <span><strong>{t('Current status')}</strong>{t(correctnessStageStatusLabel(active.status))}</span>
          <span><strong>{t('Still needed')}</strong>{t(active.remaining)}</span>
          <span><strong>{t('Done when')}</strong>{t(active.completion)}</span>
          <button type="button" onClick={() => onOpen(active.actionView)}>
            {t(active.action)}<ChevronRight aria-hidden="true" size={16} />
          </button>
        </div>
      )}
    </section>
  );
}

function readGuidanceCollapsedPreference(): boolean {
  try {
    return window.localStorage.getItem(CORRECTNESS_GUIDANCE_COLLAPSED_KEY) === 'true';
  } catch {
    return false;
  }
}

function CorrectnessStageStatus({ status }: { status: 'READY' | 'BLOCKED' | 'REVIEW' }) {
  if (status === 'READY') return <CheckCircle2 aria-label="Ready" size={17} />;
  if (status === 'BLOCKED') return <AlertTriangle aria-label="Blocked" size={17} />;
  return <CircleGauge aria-label="Review" size={17} />;
}

function Coverage({ workspace, deployment }: {
  workspace: CorrectnessWorkspaceProjection;
  deployment: CorrectnessDeploymentCapabilities;
}) {
  const { t } = useI18n();
  const value = percent(workspace.coverage.fulfilled, workspace.coverage.total);
  return (
    <div className="correctness-surface">
      <SurfaceHeading eyebrow="FROZEN DENOMINATOR" title="Coverage obligations" refValue={workspace.coverage.inventoryRef} />
      {workspace.coverage.availability === 'UNAVAILABLE' ? <UnavailableProjection /> : (
        <>
          <div className="correctness-coverage-meter" style={{ '--coverage': `${value}%` } as React.CSSProperties}>
            <div><strong>{value}%</strong><span>{t('fulfilled')}</span></div>
            <div className="correctness-coverage-track"><span /></div>
          </div>
          <div className="correctness-metric-row">
            <Metric label="Frozen obligations" value={workspace.coverage.total} />
            <Metric label="Fulfilled" value={workspace.coverage.fulfilled} tone="positive" />
            <Metric label="Uncovered" value={workspace.coverage.uncovered} tone={workspace.coverage.uncovered ? 'negative' : 'neutral'} />
            <Metric label="Waived" value={workspace.coverage.waived} />
          </div>
          <p className="correctness-projection-note">
            {t('Coverage is derived from the exact frozen inventory and canonical Case bindings. It cannot be edited as a green status.')}
          </p>
          <CoverageStudio
            workspace={workspace}
            available={deployment.features.correctnessCoverageApi === true}
          />
        </>
      )}
    </div>
  );
}

function Cases({ workspace, deployment }: {
  workspace: CorrectnessWorkspaceProjection;
  deployment: CorrectnessDeploymentCapabilities;
}) {
  const { t } = useI18n();
  return (
    <div className="correctness-surface">
      <SurfaceHeading eyebrow="BUSINESS EXAMPLES" title="Canonical Cases" refValue={workspace.cases.scenarioDraftSetRef} />
      {workspace.cases.availability === 'UNAVAILABLE' ? <UnavailableProjection /> : (
        <>
          <div className="correctness-table-scroll"><table>
            <thead><tr>
              <th>{t('Case')}</th><th>{t('Type')}</th><th>{t('Risk')}</th><th>{t('Proof assets')}</th>
              <th>{t('Dependencies')}</th><th>{t('Review')}</th><th>{t('Owner')}</th>
            </tr></thead>
            <tbody>{workspace.cases.rows.map((testCase) => (
              <tr key={testCase.caseId}>
                <td><strong>{testCase.name}</strong><small>{testCase.businessIntent || testCase.caseId}</small></td>
                <td>{t(testCase.caseType)}</td>
                <td><span className="correctness-risk" data-risk={testCase.risk}>{t(testCase.risk)}</span></td>
                <td>{testCase.oracleCount} O · {testCase.assertionSetCount} A</td>
                <td>{testCase.dependencyCount}</td>
                <td>{t(testCase.reviewStatus)}</td>
                <td>{testCase.owner.displayName}</td>
              </tr>
            ))}</tbody>
          </table></div>
          {workspace.cases.nextCursor && <p className="correctness-projection-note">
            {t('Showing {count} of {total} Cases from a bounded server projection.', {
              count: workspace.cases.rows.length,
              total: workspace.cases.total,
            })}
          </p>}
          <CaseStudio
            workspace={workspace}
            available={deployment.features.correctnessScenarioV2Api === true}
          />
        </>
      )}
    </div>
  );
}

function Fixtures({ workspace, deployment }: {
  workspace: CorrectnessWorkspaceProjection;
  deployment: CorrectnessDeploymentCapabilities;
}) {
  const { t } = useI18n();
  return (
    <div className="correctness-surface">
      <SurfaceHeading eyebrow="CONTROLLED TEST DATA" title="Fixture catalog" />
      {workspace.fixtures.availability === 'UNAVAILABLE' ? <UnavailableProjection /> : (
        <>
          <div className="correctness-table-scroll"><table>
            <thead><tr>
              <th>{t('Fixture')}</th><th>{t('Variant')}</th><th>{t('Classification')}</th>
              <th>{t('Lifecycle')}</th><th>{t('Schema')}</th><th>{t('Used by')}</th>
            </tr></thead>
            <tbody>{workspace.fixtures.rows.map((fixture) => (
              <tr key={`${fixture.descriptorRef.id}:${fixture.descriptorRef.revision}`}>
                <td><strong>{fixture.name}</strong><small>{shortFingerprint(fixture.materialFingerprint)}</small></td>
                <td>{fixture.variantKey}</td>
                <td>{t(fixture.classification)}</td>
                <td>{t(fixture.lifecycle)}</td>
                <td>{fixture.schemaRef.id} · r{fixture.schemaRef.revision}</td>
                <td>{fixture.usageCount}</td>
              </tr>
            ))}</tbody>
          </table></div>
          <FixtureStudio
            workspace={workspace}
            catalogAvailable={deployment.features.correctnessFixtureCatalogApi === true}
            materialAvailable={deployment.features.correctnessFixtureMaterialApi === true}
          />
        </>
      )}
      <p className="correctness-projection-note">
        {t('This catalog is metadata-only. Fixture material is loaded only through an authorized, no-store editor session.')}
      </p>
    </div>
  );
}

function Oracle({ workspace, deployment }: {
  workspace: CorrectnessWorkspaceProjection;
  deployment: CorrectnessDeploymentCapabilities;
}) {
  const { t } = useI18n();
  return (
    <div className="correctness-surface">
      <SurfaceHeading eyebrow="BUSINESS EXPECTATION" title="Oracle and Assertion readiness" />
      {workspace.oracleAssertions.availability === 'UNAVAILABLE' ? <UnavailableProjection /> : (
        <div className="correctness-oracle-groups">
          <section>
            <header><strong>{t('Business Oracle')}</strong><span>{t('What must be true')}</span></header>
            <div className="correctness-metric-row">
              <Metric label="Approved" value={workspace.oracleAssertions.approvedOracles} tone="positive" />
              <Metric label="Proposed" value={workspace.oracleAssertions.proposedOracles} />
              <Metric label="Superseded" value={workspace.oracleAssertions.supersededOracles} />
            </div>
          </section>
          <ChevronRight aria-hidden="true" size={24} />
          <section>
            <header><strong>{t('Assertion Set')}</strong><span>{t('How the expectation is checked')}</span></header>
            <div className="correctness-metric-row">
              <Metric label="Executable" value={workspace.oracleAssertions.validAssertionSets} tone="positive" />
              <Metric label="Draft" value={workspace.oracleAssertions.draftAssertionSets} />
              <Metric label="Stale" value={workspace.oracleAssertions.staleAssertionSets} tone={workspace.oracleAssertions.staleAssertionSets ? 'negative' : 'neutral'} />
              <Metric label="Unsupported" value={workspace.oracleAssertions.unsupportedAssertionSets} tone={workspace.oracleAssertions.unsupportedAssertionSets ? 'negative' : 'neutral'} />
            </div>
          </section>
        </div>
      )}
      <p className="correctness-projection-note">
        {t('Business Oracle approval and Assertion Set executability are independent. An approved sentence is not executable proof by itself.')}
      </p>
      <OracleStudio
        workspace={workspace}
        available={deployment.features.correctnessOracleAssertionApi === true
          && deployment.features.correctnessScenarioV2Api === true}
      />
    </div>
  );
}

function WorkspaceState({
  icon,
  title,
  detail,
  action,
}: {
  icon: React.ReactNode;
  title: string;
  detail?: string;
  action?: { label: string; onClick(): void };
}) {
  return (
    <main className="correctness-workspace-state" role={detail ? 'alert' : 'status'}>
      <span>{icon}</span><h2>{title}</h2>{detail && <p>{detail}</p>}
      {action && <button type="button" onClick={action.onClick}><RefreshCw size={17} />{action.label}</button>}
    </main>
  );
}

function OverviewLink({ icon, label, value, detail, onClick }: {
  icon: React.ReactNode;
  label: string;
  value: string;
  detail: string;
  onClick(): void;
}) {
  return (
    <button type="button" className="correctness-overview-link" onClick={onClick}>
      <span className="correctness-overview-icon">{icon}</span>
      <span><small>{label}</small><strong>{value}</strong><em>{detail}</em></span>
      <ChevronRight aria-hidden="true" size={18} />
    </button>
  );
}

function SurfaceHeading({ eyebrow, title, refValue }: {
  eyebrow: string;
  title: string;
  refValue?: { id: string; revision: number; fingerprint: string } | null;
}) {
  const { t } = useI18n();
  return (
    <header className="correctness-section-header">
      <div><p className="eyebrow">{t(eyebrow)}</p><h2>{t(title)}</h2></div>
      {refValue && <span className="correctness-coordinate">{refValue.id} · r{refValue.revision}</span>}
    </header>
  );
}

function Metric({ label, value, tone = 'neutral' }: {
  label: string;
  value: number;
  tone?: 'neutral' | 'positive' | 'negative';
}) {
  const { t } = useI18n();
  return <span className="correctness-metric" data-tone={tone}><strong>{value}</strong><small>{t(label)}</small></span>;
}

function UnavailableProjection() {
  const { t } = useI18n();
  return <p className="correctness-empty"><Archive aria-hidden="true" size={20} />{t('This projection is not available for the selected target revision.')}</p>;
}

function ViewCount({ view, workspace }: { view: CorrectnessView; workspace: CorrectnessWorkspaceProjection }) {
  if (view === 'coverage') return <span>{workspace.coverage.uncovered}</span>;
  if (view === 'cases') return <span>{workspace.cases.total}</span>;
  if (view === 'fixtures') return <span>{workspace.fixtures.total}</span>;
  if (view === 'oracle') return <span>{workspace.oracleAssertions.approvedOracles}</span>;
  return null;
}

function parseRoute(search: string): { coordinate: CorrectnessWorkspaceCoordinate | null; view: CorrectnessView } {
  const params = new URLSearchParams(search);
  const kind = params.get('targetKind');
  const targetId = params.get('targetId')?.trim() ?? '';
  const targetFingerprint = params.get('targetFingerprint')?.trim() ?? '';
  const requestedView = params.get('correctnessView');
  const view = VIEWS.some((item) => item.id === requestedView) ? requestedView as CorrectnessView : 'overview';
  const targetKind = kind === 'GRAPH' || kind === 'OPERATOR' || kind === 'FUNCTION' ? kind : null;
  return {
    coordinate: targetKind && targetId && targetFingerprint ? {
      targetKind,
      targetId,
      targetFingerprint,
      definitionId: params.get('definitionId')?.trim() || undefined,
      caseCursor: params.get('caseCursor')?.trim() || undefined,
      caseLimit: 100,
    } : null,
    view,
  };
}

function setOptional(params: URLSearchParams, key: string, value?: string) {
  if (value) params.set(key, value);
  else params.delete(key);
}

function percent(value: number, total: number): number {
  return total === 0 ? 0 : Math.round((value / total) * 100);
}

function shortFingerprint(value: string): string {
  if (value.length <= 22) return value;
  return `${value.slice(0, 12)}...${value.slice(-7)}`;
}

interface CorrectnessStageDefinition {
  id: CorrectnessStage;
  title: string;
  question: string;
  status: 'READY' | 'BLOCKED' | 'REVIEW';
  remaining: string;
  completion: string;
  action: string;
  view: CorrectnessView;
  actionView: CorrectnessView;
}

function correctnessStages(workspace: CorrectnessWorkspaceProjection): CorrectnessStageDefinition[] {
  const definitionBlocked = workspace.coverage.availability !== 'AVAILABLE'
    || workspace.coverage.uncovered > 0
    || workspace.cases.total === 0
    || workspace.fixtures.active === 0
    || workspace.oracleAssertions.validAssertionSets === 0;
  const definitionAction = workspace.coverage.availability !== 'AVAILABLE' || workspace.coverage.uncovered > 0
    ? 'coverage'
    : workspace.cases.total === 0
      ? 'cases'
      : workspace.fixtures.active === 0
        ? 'fixtures'
        : 'oracle';
  const evidenceReady = workspace.lastRun?.evidenceRef != null
    && workspace.verdict.evidence === 'CURRENT';
  return [{
    id: 'verdict',
    title: '1. Review the verdict',
    question: 'Is business correctness proven, and what blocks it first?',
    status: correctnessStageStatus('verdict', workspace),
    remaining: workspace.verdict.nextActions.length > 0
      ? 'Resolve the first required attention item below.'
      : 'Review the five-axis verdict and confirm the business conclusion.',
    completion: 'You can explain the five-axis verdict and its first blocker.',
    action: workspace.verdict.nextActions.length > 0 ? 'Handle first required action' : 'Review the verdict',
    view: 'overview',
    actionView: workspace.verdict.nextActions.length > 0
      ? correctnessViewForCommand(workspace.verdict.nextActions[0].command)
      : 'overview',
  }, {
    id: 'definition',
    title: '2. Define correctness',
    question: 'What must be covered, with which data and expected result?',
    status: definitionBlocked ? 'BLOCKED' : 'READY',
    remaining: definitionBlocked
      ? 'Close the missing denominator, Case, Fixture, or executable Assertion asset.'
      : 'The correctness definition has an executable proof closure.',
    completion: 'The frozen denominator, canonical Cases, active Fixtures, and executable Assertions form a closure.',
    action: definitionBlocked ? 'Continue defining correctness' : 'Review proof assets',
    view: 'cases',
    actionView: definitionAction,
  }, {
    id: 'evidence',
    title: '3. Run and retain evidence',
    question: 'Can this revision run in isolation and retain current evidence?',
    status: evidenceReady ? 'READY' : workspace.lastRun ? 'REVIEW' : 'BLOCKED',
    remaining: evidenceReady
      ? 'Current evidence is retained for this exact revision.'
      : 'Pass preflight, run the selected Cases, and retain exact current evidence.',
    completion: 'Preflight passes and the exact target revision has current retained evidence.',
    action: evidenceReady ? 'Inspect retained evidence' : 'Open run center',
    view: 'runs',
    actionView: 'runs',
  }];
}

function correctnessStageForView(view: CorrectnessView): CorrectnessStage {
  if (view === 'overview') return 'verdict';
  if (view === 'runs') return 'evidence';
  return 'definition';
}

function correctnessStageTelemetryStep(stage: CorrectnessStage) {
  if (stage === 'verdict') return 'VERDICT' as const;
  if (stage === 'definition') return 'DEFINE_CORRECTNESS' as const;
  return 'RUN_AND_EVIDENCE' as const;
}

function correctnessStageStatus(
  stage: CorrectnessStage,
  workspace: CorrectnessWorkspaceProjection,
): 'READY' | 'BLOCKED' | 'REVIEW' {
  if (stage === 'verdict') return workspace.verdict.nextActions.length > 0 ? 'BLOCKED' : 'READY';
  if (stage === 'evidence') {
    if (workspace.lastRun?.evidenceRef != null && workspace.verdict.evidence === 'CURRENT') return 'READY';
    return workspace.lastRun ? 'REVIEW' : 'BLOCKED';
  }
  return workspace.coverage.availability === 'AVAILABLE'
    && workspace.coverage.uncovered === 0
    && workspace.cases.total > 0
    && workspace.fixtures.active > 0
    && workspace.oracleAssertions.validAssertionSets > 0
    ? 'READY'
    : 'BLOCKED';
}

function correctnessStageStatusLabel(status: 'READY' | 'BLOCKED' | 'REVIEW'): string {
  if (status === 'READY') return 'Ready';
  if (status === 'BLOCKED') return 'Blocked';
  return 'Needs review';
}

function correctnessViewForCommand(command: string): CorrectnessView {
  const views: Record<string, CorrectnessView> = {
    CREATE_CASE_FROM_GAP: 'coverage',
    OPEN_ASSERTION_BUILDER: 'oracle',
    OPEN_COVERAGE_INVENTORY: 'coverage',
    REVIEW_GOVERNANCE_GATE: 'runs',
    REFRESH_STALE_ASSETS: 'overview',
  };
  return views[command] ?? 'overview';
}

export function correctnessCommandLabel(command: string): string {
  const labels: Record<string, string> = {
    CREATE_CASE_FROM_GAP: 'Create Case from coverage gap',
    OPEN_ASSERTION_BUILDER: 'Open Assertion Builder',
    OPEN_COVERAGE_INVENTORY: 'Open Coverage Inventory',
    REVIEW_GOVERNANCE_GATE: 'Review governance gate',
    REFRESH_STALE_ASSETS: 'Refresh stale assets',
  };
  return labels[command] ?? humanizeProtocolCode(command);
}

function correctnessCommandActionLabel(command: string): string {
  const labels: Record<string, string> = {
    CREATE_CASE_FROM_GAP: 'View uncovered obligations',
    OPEN_ASSERTION_BUILDER: 'Open Assertion Builder',
    OPEN_COVERAGE_INVENTORY: 'Open Coverage Inventory',
    REVIEW_GOVERNANCE_GATE: 'Open run and gate evidence',
    REFRESH_STALE_ASSETS: 'Refresh exact assets',
  };
  return labels[command] ?? 'Open recommended workspace';
}

function correctnessActionImpact(command: string): string {
  const labels: Record<string, string> = {
    CREATE_CASE_FROM_GAP: 'Coverage remains blocked and the governed TestSuite cannot be published.',
    OPEN_ASSERTION_BUILDER: 'A successful execution still cannot prove the expected business outcome.',
    OPEN_COVERAGE_INVENTORY: 'The system cannot show which frozen business obligations remain unproven.',
    REVIEW_GOVERNANCE_GATE: 'Publication cannot proceed until the gate decision and evidence are understood.',
    REFRESH_STALE_ASSETS: 'Evidence for an older revision cannot prove the current business asset.',
  };
  return labels[command] ?? 'The current verdict remains blocked until this condition is resolved.';
}

function correctnessActionCompletion(command: string): string {
  const labels: Record<string, string> = {
    CREATE_CASE_FROM_GAP: 'Every uncovered obligation is bound to a canonical Case or an approved waiver.',
    OPEN_ASSERTION_BUILDER: 'The Case has an approved Business Oracle and a validated executable Assertion Set.',
    OPEN_COVERAGE_INVENTORY: 'The exact frozen inventory is available and its uncovered obligations are explained.',
    REVIEW_GOVERNANCE_GATE: 'The gate decision has current evidence and every blocking finding has an owner.',
    REFRESH_STALE_ASSETS: 'Every referenced asset is current and the workspace verdict has been recalculated.',
  };
  return labels[command] ?? 'The server-recalculated verdict no longer reports this action.';
}

export function correctnessReasonLabel(reasonCode: string): string {
  const labels: Record<string, string> = {
    ASSERTION_NONE: 'No executable assertion is bound',
    AUTHORING_ASSETS_INCOMPLETE: 'Authoring assets are incomplete',
    COVERAGE_GAPS_REMAIN: 'Frozen obligations remain unproven',
    EVIDENCE_STALE: 'Evidence no longer matches the current assets',
    REFERENCE_MISSING: 'An exact referenced asset is missing',
    HEAD_DRIFT: 'A referenced asset has a newer head revision',
    NOT_ACTIVE: 'A referenced asset is not active',
    RETENTION_EXPIRED: 'Fixture retention has expired',
  };
  return labels[reasonCode] ?? humanizeProtocolCode(reasonCode);
}

function correctnessAssetLabel(assetKind: string): string {
  const labels: Record<string, string> = {
    ASSERTION_SET: 'Assertion Set',
    COVERAGE_INVENTORY: 'Coverage Inventory',
    FIXTURE_ASSET: 'Fixture asset',
    SCENARIO_DRAFT_SET: 'Scenario set',
  };
  return labels[assetKind] ?? humanizeProtocolCode(assetKind);
}

function humanizeProtocolCode(value: string): string {
  const words = value.trim().toLocaleLowerCase().replace(/_/g, ' ');
  return words ? words[0].toLocaleUpperCase() + words.slice(1) : 'Review required';
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause);
}
