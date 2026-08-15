import { useEffect, useMemo, useState } from 'react';
import {
  AlertTriangle,
  Check,
  ChevronDown,
  LoaderCircle,
  Play,
  RefreshCw,
  SearchCheck,
  ShieldAlert,
} from 'lucide-react';

import { BlogeApiRequestError } from '../../api';
import { useI18n } from '../../i18n/I18nProvider';
import {
  correctnessClientRequestId,
  executeCorrectnessRun,
  fetchCorrectnessEvidence,
  preflightCorrectnessRun,
  publicationRef,
  selectionIntent,
} from '../api/correctnessApi';
import type {
  CorrectnessApiEnvelope,
  CorrectnessDeploymentCapabilities,
  CorrectnessPreflightReport,
  CorrectnessRunRequest,
  CorrectnessRunResponse,
  CorrectnessSelectionMode,
  CorrectnessWorkspaceProjection,
  StoredCorrectnessEvidenceCompanion,
} from '../model/domain';
import FiveAxisVerdict from '../shared/FiveAxisVerdict';

export interface CorrectnessRunApi {
  preflight(request: Parameters<typeof preflightCorrectnessRun>[0]):
  Promise<CorrectnessApiEnvelope<CorrectnessPreflightReport>>;
  execute(request: CorrectnessRunRequest):
  Promise<CorrectnessApiEnvelope<CorrectnessRunResponse>>;
  evidence(suiteRunId: string):
  Promise<CorrectnessApiEnvelope<StoredCorrectnessEvidenceCompanion>>;
}

const DEFAULT_API: CorrectnessRunApi = {
  preflight: preflightCorrectnessRun,
  execute: executeCorrectnessRun,
  evidence: fetchCorrectnessEvidence,
};

interface RunCenterProps {
  workspace: CorrectnessWorkspaceProjection;
  deployment: CorrectnessDeploymentCapabilities;
  api?: CorrectnessRunApi;
}

type RunPhase = 'IDLE' | 'PREFLIGHT' | 'REVIEW' | 'RUNNING' | 'EVIDENCE' | 'ERROR';

export default function RunCenter({ workspace, deployment, api = DEFAULT_API }: RunCenterProps) {
  const { t } = useI18n();
  const [mode, setMode] = useState<CorrectnessSelectionMode>('ALL');
  const [selectedCaseIds, setSelectedCaseIds] = useState<string[]>([]);
  const [strategy, setStrategy] = useState<CorrectnessRunRequest['strategy']>('COLLECT_ALL');
  const [phase, setPhase] = useState<RunPhase>('IDLE');
  const [preflight, setPreflight] = useState<CorrectnessPreflightReport | null>(null);
  const [suiteRunId, setSuiteRunId] = useState('');
  const [evidence, setEvidence] = useState<StoredCorrectnessEvidenceCompanion | null>(null);
  const [error, setError] = useState('');
  const publication = publicationRef(workspace);
  const preflightAvailable = deployment.features.correctnessPreflightApi === true;
  const runAvailable = deployment.features.correctnessRunApi === true;
  const evidenceAvailable = deployment.features.correctnessEvidenceCompanionApi === true;
  const selectedCount = mode === 'ALL' ? workspace.cases.total : selectedCaseIds.length;
  const canReview = Boolean(publication && preflightAvailable
    && selectedCount > 0 && phase !== 'PREFLIGHT' && phase !== 'RUNNING');
  const canRun = Boolean(preflight && preflight.blockers.length === 0
    && runAvailable && phase === 'REVIEW');

  useEffect(() => {
    setPhase('IDLE');
    setPreflight(null);
    setEvidence(null);
    setSuiteRunId('');
    setError('');
  }, [mode, selectedCaseIds.join('\u0000'), strategy, workspace.queryFingerprint]);

  const invocationRows = useMemo(() => preflight?.cases.flatMap((testCase) => (
    testCase.invocationSites.map((site) => ({ ...site, caseId: testCase.caseId }))
  )) ?? [], [preflight]);

  const review = async () => {
    if (!publication || !canReview) return;
    setPhase('PREFLIGHT');
    setError('');
    setEvidence(null);
    try {
      const response = await api.preflight({
        schemaVersion: 'bloge.correctnessPreflightRequest.v1',
        publicationRef: publication,
        selection: selectionIntent(mode, selectedCaseIds),
      });
      setPreflight(response.data);
      setPhase('REVIEW');
    } catch (cause: unknown) {
      setError(errorMessage(cause));
      setPhase('ERROR');
    }
  };

  const run = async () => {
    if (!publication || !preflight || !canRun) return;
    setPhase('RUNNING');
    setError('');
    try {
      const response = await api.execute({
        schemaVersion: 'bloge.correctnessRunRequest.v1',
        publicationRef: publication,
        selection: preflight.selection,
        preflightFingerprint: preflight.preflightFingerprint,
        clientRequestId: correctnessClientRequestId(),
        strategy,
      });
      setSuiteRunId(response.data.suiteExecution.suiteRunId);
      if (response.data.evidenceCompanion) {
        setEvidence(response.data.evidenceCompanion);
        setPhase('EVIDENCE');
      } else {
        setPhase('RUNNING');
      }
    } catch (cause: unknown) {
      setError(errorMessage(cause));
      setPhase('ERROR');
    }
  };

  const refreshEvidence = async () => {
    if (!suiteRunId || !evidenceAvailable) return;
    setError('');
    try {
      const response = await api.evidence(suiteRunId);
      setEvidence(response.data);
      setPhase('EVIDENCE');
    } catch (cause: unknown) {
      if (cause instanceof BlogeApiRequestError && cause.status === 404) {
        setPhase('RUNNING');
        return;
      }
      setError(errorMessage(cause));
      setPhase('ERROR');
    }
  };

  const toggleCase = (caseId: string) => {
    setSelectedCaseIds((current) => current.includes(caseId)
      ? current.filter((value) => value !== caseId)
      : [...current, caseId].sort());
  };

  return (
    <div className="correctness-run-center" data-phase={phase}>
      <header className="correctness-section-header">
        <div>
          <p className="eyebrow">{t('RUN CENTER')}</p>
          <h2>{t('Review, run, and retain evidence')}</h2>
        </div>
        <span className="correctness-coordinate">
          {publication ? `${publication.publicationId} · r${publication.revision}` : t('No publication')}
        </span>
      </header>

      {!publication && (
        <Notice tone="warning" icon={<ShieldAlert aria-hidden="true" size={18} />}>
          {t('Publish an exact correctness revision before running.')}
        </Notice>
      )}
      {!preflightAvailable && (
        <Notice tone="warning" icon={<AlertTriangle aria-hidden="true" size={18} />}>
          {t('This deployment does not advertise governed run preflight.')}
        </Notice>
      )}

      <section className="correctness-run-compose" aria-label={t('Run selection')}>
        <div className="correctness-run-controls">
          <fieldset>
            <legend>{t('Scope')}</legend>
            <div className="correctness-segmented">
              <button type="button" aria-pressed={mode === 'ALL'} onClick={() => setMode('ALL')}>
                {t('All Cases')} <span>{workspace.cases.total}</span>
              </button>
              <button
                type="button"
                aria-pressed={mode === 'SELECTED'}
                onClick={() => setMode('SELECTED')}
              >
                {t('Selected')} <span>{selectedCaseIds.length}</span>
              </button>
            </div>
          </fieldset>
          <fieldset>
            <legend>{t('Failure strategy')}</legend>
            <div className="correctness-segmented">
              <button
                type="button"
                aria-pressed={strategy === 'COLLECT_ALL'}
                onClick={() => setStrategy('COLLECT_ALL')}
              >
                {t('Collect all')}
              </button>
              <button
                type="button"
                aria-pressed={strategy === 'FAIL_FAST'}
                onClick={() => setStrategy('FAIL_FAST')}
              >
                {t('Fail fast')}
              </button>
            </div>
          </fieldset>
          <button
            type="button"
            className="correctness-primary-command"
            disabled={!canReview}
            onClick={() => void review()}
          >
            {phase === 'PREFLIGHT'
              ? <LoaderCircle aria-hidden="true" className="spin" size={18} />
              : <SearchCheck aria-hidden="true" size={18} />}
            {t('Review run plan')}
          </button>
        </div>

        {mode === 'SELECTED' && (
          <div className="correctness-case-picker">
            <div className="correctness-table-scroll">
              <table>
                <thead>
                  <tr>
                    <th aria-label={t('Select')} />
                    <th>{t('Case')}</th>
                    <th>{t('Type')}</th>
                    <th>{t('Risk')}</th>
                    <th>{t('Proof assets')}</th>
                  </tr>
                </thead>
                <tbody>
                  {workspace.cases.rows.map((testCase) => (
                    <tr key={testCase.caseId}>
                      <td>
                        <input
                          type="checkbox"
                          checked={selectedCaseIds.includes(testCase.caseId)}
                          aria-label={t('Select {name}', { name: testCase.name })}
                          onChange={() => toggleCase(testCase.caseId)}
                        />
                      </td>
                      <td><strong>{testCase.name}</strong><small>{testCase.caseId}</small></td>
                      <td>{t(testCase.caseType)}</td>
                      <td>{t(testCase.risk)}</td>
                      <td>{testCase.oracleCount} / {testCase.assertionSetCount}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {workspace.cases.nextCursor && (
              <small>{t('This page shows {count} of {total} Cases. Use All Cases for the complete server-resolved selection.', {
                count: workspace.cases.rows.length,
                total: workspace.cases.total,
              })}</small>
            )}
          </div>
        )}
      </section>

      {error && (
        <Notice tone="danger" icon={<AlertTriangle aria-hidden="true" size={18} />}>
          {error}
        </Notice>
      )}

      {preflight && (
        <section className="correctness-preflight-review" aria-label={t('Server preflight result')}>
          <div className="correctness-section-header compact">
            <div>
              <p className="eyebrow">{t('SERVER PREFLIGHT')}</p>
              <h3>{preflight.blockers.length === 0
                ? t('Plan ready for execution')
                : t('{count} blockers must be resolved', { count: preflight.blockers.length })}</h3>
            </div>
            <span className="correctness-coordinate">{t(preflight.proofLevel)}</span>
          </div>
          <RiskSummary summary={preflight.riskSummary} />
          {preflight.blockers.length > 0 && (
            <ul className="correctness-blocker-list">
              {preflight.blockers.map((blocker) => (
                <li key={`${blocker.code}:${blocker.caseId}`}>
                  <ShieldAlert aria-hidden="true" size={17} />
                  <span><strong>{blocker.code}</strong>{blocker.caseId && ` · ${blocker.caseId}`}</span>
                </li>
              ))}
            </ul>
          )}
          <div className="correctness-table-scroll">
            <table>
              <thead><tr>
                <th>{t('Case')}</th><th>{t('Node')}</th><th>{t('Dependency')}</th>
                <th>{t('Resolution')}</th><th>{t('Behavior')}</th><th>{t('Side effect')}</th>
              </tr></thead>
              <tbody>
                {invocationRows.map((site) => (
                  <tr key={`${site.caseId}:${site.invocationSiteId}`}>
                    <td>{site.caseId}</td>
                    <td>{site.nodeId}</td>
                    <td>{site.operatorRef || site.resourceRef || site.functionRef || '—'}</td>
                    <td><StatusTag value={site.resolution} /></td>
                    <td>{site.behavior}</td>
                    <td>{site.sideEffectType}</td>
                  </tr>
                ))}
                {invocationRows.length === 0 && (
                  <tr><td colSpan={6}>{t('No dependency invocation sites were resolved.')}</td></tr>
                )}
              </tbody>
            </table>
          </div>
          <div className="correctness-run-confirmation">
            <div>
              <strong>{t('{count} Cases will run', { count: preflight.cases.length })}</strong>
              <span>{t('Selection and publication fingerprints are locked to this review.')}</span>
            </div>
            <button
              type="button"
              className="correctness-primary-command"
              disabled={!canRun}
              onClick={() => void run()}
            >
              <Play aria-hidden="true" size={18} />
              {t('Run reviewed selection')}
            </button>
          </div>
          {!runAvailable && preflight.blockers.length === 0 && (
            <small className="correctness-inline-warning">
              {t('This deployment does not advertise the governed run API.')}
            </small>
          )}
        </section>
      )}

      {phase === 'RUNNING' && (
        <section className="correctness-run-progress" aria-live="polite">
          <LoaderCircle aria-hidden="true" className="spin" size={20} />
          <div><strong>{t('Run in progress')}</strong><span>{suiteRunId || t('Waiting for run receipt')}</span></div>
          <button type="button" disabled={!suiteRunId || !evidenceAvailable} onClick={() => void refreshEvidence()}>
            <RefreshCw aria-hidden="true" size={17} /> {t('Refresh evidence')}
          </button>
        </section>
      )}

      {evidence && <EvidenceResult evidence={evidence} />}
    </div>
  );
}

function RiskSummary({ summary }: { summary: CorrectnessPreflightReport['riskSummary'] }) {
  const { t } = useI18n();
  const values = [
    ['REAL', summary.realCount],
    ['MOCKED', summary.mockedCount],
    ['FAULT', summary.faultCount],
    ['REPLAY', summary.replayCount],
    ['OBSERVE', summary.observeCount],
    ['DENIED', summary.deniedCount],
  ] as const;
  return (
    <div className="correctness-risk-summary" aria-label={t('Execution risk summary')}>
      {values.map(([label, value]) => (
        <div key={label} data-risk={label}><span>{t(label)}</span><strong>{value}</strong></div>
      ))}
      <div data-risk="SECRET"><span>{t('Secrets')}</span><strong>{summary.secretRequirementCount}</strong></div>
      <div data-risk="CLOCK"><span>{t('Logical clock')}</span><strong>{summary.logicalClockConfigured ? t('Ready') : t('Not set')}</strong></div>
    </div>
  );
}

function EvidenceResult({ evidence }: { evidence: StoredCorrectnessEvidenceCompanion }) {
  const { t } = useI18n();
  const value = evidence.companion;
  return (
    <section className="correctness-evidence-result" aria-label={t('Correctness evidence')}>
      <div className="correctness-section-header compact">
        <div><p className="eyebrow">{t('IMMUTABLE EVIDENCE')}</p><h3>{t('Five-axis result')}</h3></div>
        <span className="correctness-coordinate">{value.suiteRunId}</span>
      </div>
      <FiveAxisVerdict verdict={value.verdict} />
      <div className="correctness-evidence-meta">
        <span><strong>{t('Cases')}</strong>{value.caseExecutions.length}</span>
        <span><strong>{t('Proof level')}</strong>{t(value.verdict.proofLevel)}</span>
        <span><strong>{t('Attestation')}</strong>{t(value.attestation.signatureStatus)}</span>
        <span><strong>{t('Classifications')}</strong>{value.dataClassifications.join(', ') || '—'}</span>
      </div>
      <div className="correctness-table-scroll">
        <table>
          <thead><tr><th>{t('Case')}</th><th>{t('Execution')}</th><th>{t('Evidence class')}</th><th>{t('Child run')}</th></tr></thead>
          <tbody>{value.caseExecutions.map((testCase) => (
            <tr key={testCase.caseId}>
              <td>{testCase.caseId}</td>
              <td><StatusTag value={testCase.status} /></td>
              <td>{testCase.evidenceClass ? t(testCase.evidenceClass) : '—'}</td>
              <td>{testCase.childRunId || '—'}</td>
            </tr>
          ))}</tbody>
        </table>
      </div>
      <details className="correctness-lineage">
        <summary><ChevronDown aria-hidden="true" size={17} />{t('Inspect exact evidence lineage')} <span>{value.sourceMap.length}</span></summary>
        <div className="correctness-table-scroll">
          <table>
            <thead><tr><th>{t('Authoring source')}</th><th>{t('Compiled output')}</th></tr></thead>
            <tbody>{value.sourceMap.map((mapping) => (
              <tr key={`${mapping.source.assetRef.id}:${mapping.source.elementId}:${mapping.output.elementId}`}>
                <td>{mapping.source.elementKind} · {mapping.source.elementId}</td>
                <td>{mapping.output.elementKind} · {mapping.output.elementId}</td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      </details>
    </section>
  );
}

function Notice({
  tone,
  icon,
  children,
}: {
  tone: 'warning' | 'danger';
  icon: React.ReactNode;
  children: React.ReactNode;
}) {
  return <div className="correctness-notice" data-tone={tone}>{icon}<span>{children}</span></div>;
}

function StatusTag({ value }: { value: string }) {
  const { t } = useI18n();
  return <span className="correctness-status-tag" data-status={value}>{value === 'TEST_DOUBLE' ? <Check aria-hidden="true" size={14} /> : null}{t(value)}</span>;
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause);
}
