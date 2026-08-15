import { FormEvent, useEffect, useMemo, useState } from 'react';
import {
  AlertTriangle,
  Archive,
  BarChart3,
  BookCheck,
  ChevronRight,
  CircleGauge,
  Database,
  FileCheck2,
  LoaderCircle,
  PlayCircle,
  RefreshCw,
  Search,
  ShieldCheck,
} from 'lucide-react';

import { useI18n } from '../i18n/I18nProvider';
import {
  fetchCorrectnessCapabilities,
  fetchCorrectnessWorkspace,
} from './api/correctnessApi';
import type {
  CorrectnessApiEnvelope,
  CorrectnessDeploymentCapabilities,
  CorrectnessTargetKind,
  CorrectnessWorkspaceCoordinate,
  CorrectnessWorkspaceProjection,
} from './model/domain';
import RunCenter from './runs/RunCenter';
import FiveAxisVerdict from './shared/FiveAxisVerdict';
import CorrectnessI18nProvider from './CorrectnessI18nProvider';
import './styles.css';

type CorrectnessView = 'overview' | 'coverage' | 'cases' | 'fixtures' | 'oracle' | 'runs';
type LoadState = 'CAPABILITIES' | 'CONNECT' | 'WORKSPACE' | 'READY' | 'UNAVAILABLE' | 'ERROR';

export interface CorrectnessStudioApi {
  capabilities(): Promise<CorrectnessDeploymentCapabilities>;
  workspace(coordinate: CorrectnessWorkspaceCoordinate):
  Promise<CorrectnessApiEnvelope<CorrectnessWorkspaceProjection>>;
}

const DEFAULT_API: CorrectnessStudioApi = {
  capabilities: fetchCorrectnessCapabilities,
  workspace: fetchCorrectnessWorkspace,
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

export default function CorrectnessStudio(props: { api?: CorrectnessStudioApi }) {
  return (
    <CorrectnessI18nProvider>
      <CorrectnessStudioSurface {...props} />
    </CorrectnessI18nProvider>
  );
}

function CorrectnessStudioSurface({ api = DEFAULT_API }: { api?: CorrectnessStudioApi }) {
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
    return <CoordinateConnector onOpen={openCoordinate} />;
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
        {view === 'overview' && <Overview workspace={workspace} onOpen={changeView} />}
        {view === 'coverage' && <Coverage workspace={workspace} />}
        {view === 'cases' && <Cases workspace={workspace} />}
        {view === 'fixtures' && <Fixtures workspace={workspace} />}
        {view === 'oracle' && <Oracle workspace={workspace} />}
        {view === 'runs' && <RunCenter workspace={workspace} deployment={deployment} />}
      </section>
    </main>
  );
}

function Overview({
  workspace,
  onOpen,
}: {
  workspace: CorrectnessWorkspaceProjection;
  onOpen(view: CorrectnessView): void;
}) {
  const { t } = useI18n();
  const coveragePercent = percent(workspace.coverage.fulfilled, workspace.coverage.total);
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
          {workspace.staleReasons.map((reason) => (
            <p key={`${reason.code}:${reason.assetKind}`}>
              {t(correctnessReasonLabel(reason.code))} · {t(correctnessAssetLabel(reason.assetKind))}
            </p>
          ))}
          {workspace.verdict.nextActions.map((action) => (
            <p key={`${action.command}:${action.reasonCode}`}>
              {t(correctnessCommandLabel(action.command))} · {t(correctnessReasonLabel(action.reasonCode))}
            </p>
          ))}
        </section>
      )}
    </div>
  );
}

function Coverage({ workspace }: { workspace: CorrectnessWorkspaceProjection }) {
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
        </>
      )}
    </div>
  );
}

function Cases({ workspace }: { workspace: CorrectnessWorkspaceProjection }) {
  const { t } = useI18n();
  return (
    <div className="correctness-surface">
      <SurfaceHeading eyebrow="BUSINESS EXAMPLES" title="Canonical Cases" refValue={workspace.cases.scenarioDraftSetRef} />
      {workspace.cases.availability === 'UNAVAILABLE' ? <UnavailableProjection /> : (
        <div className="correctness-table-scroll">
          <table>
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
          </table>
          {workspace.cases.nextCursor && <p className="correctness-projection-note">
            {t('Showing {count} of {total} Cases from a bounded server projection.', {
              count: workspace.cases.rows.length,
              total: workspace.cases.total,
            })}
          </p>}
        </div>
      )}
    </div>
  );
}

function Fixtures({ workspace }: { workspace: CorrectnessWorkspaceProjection }) {
  const { t } = useI18n();
  return (
    <div className="correctness-surface">
      <SurfaceHeading eyebrow="CONTROLLED TEST DATA" title="Fixture catalog" />
      {workspace.fixtures.availability === 'UNAVAILABLE' ? <UnavailableProjection /> : (
        <div className="correctness-table-scroll">
          <table>
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
          </table>
        </div>
      )}
      <p className="correctness-projection-note">
        {t('This catalog is metadata-only. Fixture material is loaded only through an authorized, no-store editor session.')}
      </p>
    </div>
  );
}

function Oracle({ workspace }: { workspace: CorrectnessWorkspaceProjection }) {
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
    </div>
  );
}

function CoordinateConnector({ onOpen }: { onOpen(value: CorrectnessWorkspaceCoordinate): void }) {
  const { t } = useI18n();
  const [targetKind, setTargetKind] = useState<CorrectnessTargetKind>('GRAPH');
  const [targetId, setTargetId] = useState('');
  const [targetFingerprint, setTargetFingerprint] = useState('');
  const [definitionId, setDefinitionId] = useState('');
  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!targetId.trim() || !targetFingerprint.trim()) return;
    onOpen({
      targetKind,
      targetId: targetId.trim(),
      targetFingerprint: targetFingerprint.trim(),
      definitionId: definitionId.trim() || undefined,
      caseLimit: 100,
    });
  };
  return (
    <main className="correctness-connect">
      <div className="correctness-connect-heading">
        <span><Search aria-hidden="true" size={22} /></span>
        <div><p className="eyebrow">{t('EXACT TARGET')}</p><h2>{t('Open a correctness workspace')}</h2></div>
      </div>
      <p>{t('Connect to one exact Graph, Operator, or Function revision. The server will project its authoritative correctness assets and evidence.')}</p>
      <form onSubmit={submit}>
        <label>{t('Target kind')}
          <select value={targetKind} onChange={(event) => setTargetKind(event.target.value as CorrectnessTargetKind)}>
            <option value="GRAPH">{t('Graph')}</option><option value="OPERATOR">{t('Operator')}</option><option value="FUNCTION">{t('Function')}</option>
          </select>
        </label>
        <label>{t('Target ID')}<input required value={targetId} onChange={(event) => setTargetId(event.target.value)} placeholder="loan-decision" /></label>
        <label>{t('Target fingerprint')}<input required value={targetFingerprint} onChange={(event) => setTargetFingerprint(event.target.value)} placeholder="sha256:..." /></label>
        <label>{t('Definition ID')} <span>{t('optional')}</span><input value={definitionId} onChange={(event) => setDefinitionId(event.target.value)} placeholder="correctness-loan-decision" /></label>
        <button type="submit" className="correctness-primary-command" disabled={!targetId.trim() || !targetFingerprint.trim()}>
          <Search aria-hidden="true" size={18} />{t('Open exact target')}
        </button>
      </form>
    </main>
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
