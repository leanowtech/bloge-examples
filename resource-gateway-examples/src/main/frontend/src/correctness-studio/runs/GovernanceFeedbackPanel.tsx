import { useCallback, useEffect, useState } from 'react';
import {
  AlertTriangle,
  CheckCircle2,
  ExternalLink,
  RefreshCw,
  ShieldAlert,
} from 'lucide-react';

import { BlogeApiRequestError } from '../../api';
import { useI18n } from '../../i18n/I18nProvider';
import type {
  CorrectnessApiEnvelope,
  CorrectnessPublicationRef,
  StoredCorrectnessGovernanceFeedback,
} from '../model/domain';

export interface GovernanceFeedbackApi {
  governanceFeedback(publicationId: string):
  Promise<CorrectnessApiEnvelope<StoredCorrectnessGovernanceFeedback>>;
}

type LoadState = 'DISABLED' | 'LOADING' | 'EMPTY' | 'READY' | 'ERROR';

export default function GovernanceFeedbackPanel({
  publication,
  available,
  api,
}: {
  publication: CorrectnessPublicationRef | null;
  available: boolean;
  api: GovernanceFeedbackApi;
}) {
  const { t } = useI18n();
  const [state, setState] = useState<LoadState>(available ? 'LOADING' : 'DISABLED');
  const [stored, setStored] = useState<StoredCorrectnessGovernanceFeedback | null>(null);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    if (!available || !publication) {
      setState('DISABLED');
      setStored(null);
      return;
    }
    setState('LOADING');
    setError('');
    try {
      const response = await api.governanceFeedback(publication.publicationId);
      setStored(response.data);
      setState('READY');
    } catch (cause: unknown) {
      setStored(null);
      if (cause instanceof BlogeApiRequestError && cause.status === 404) {
        setState('EMPTY');
      } else {
        setError(cause instanceof Error ? cause.message : String(cause));
        setState('ERROR');
      }
    }
  }, [api, available, publication?.publicationId, publication?.fingerprint]);

  useEffect(() => { void load(); }, [load]);

  if (!publication) return null;

  return (
    <section className="correctness-governance-feedback" aria-label={t('ANEKE governance feedback')}>
      <header className="correctness-section-header compact">
        <div>
          <p className="eyebrow">{t('EXTERNAL GOVERNANCE')}</p>
          <h3>{t('ANEKE publication decision')}</h3>
        </div>
        <button type="button" disabled={!available || state === 'LOADING'} onClick={() => void load()} title={t('Refresh governance feedback')}>
          <RefreshCw aria-hidden="true" className={state === 'LOADING' ? 'spin' : ''} size={17} />
          <span className="visually-hidden">{t('Refresh governance feedback')}</span>
        </button>
      </header>

      {state === 'DISABLED' && (
        <p className="correctness-governance-empty">
          <AlertTriangle aria-hidden="true" size={18} />
          {t('This deployment is not connected to the Correctness governance feedback API.')}
        </p>
      )}
      {state === 'EMPTY' && (
        <p className="correctness-governance-empty">
          <ShieldAlert aria-hidden="true" size={18} />
          {t('ANEKE has not returned a decision for this exact Publication yet.')}
        </p>
      )}
      {state === 'ERROR' && <p className="correctness-inline-error" role="alert">{error}</p>}

      {state === 'READY' && stored && <Feedback value={stored} />}
    </section>
  );
}

function Feedback({ value }: { value: StoredCorrectnessGovernanceFeedback }) {
  const { t } = useI18n();
  const feedback = value.feedback;
  const expired = Boolean(feedback.expiresAt
    && new Date(feedback.expiresAt).getTime() <= Date.now());
  return (
    <div className="correctness-governance-body">
      <div className="correctness-governance-decision" data-decision={expired ? 'EXPIRED' : feedback.decision}>
        {feedback.decision === 'ACCEPTED' && !expired
          ? <CheckCircle2 aria-hidden="true" size={21} />
          : <ShieldAlert aria-hidden="true" size={21} />}
        <div>
          <strong>{expired ? t('Feedback expired') : t(feedback.decision)}</strong>
          <span>{feedback.sourceDecisionId} · r{feedback.sourceDecisionRevision}</span>
        </div>
        <code>{shortFingerprint(value.feedbackFingerprint)}</code>
      </div>
      <div className="correctness-governance-facts">
        <Fact label={t('Workbook')} value={t(feedback.workbookStatus)} />
        <Fact label={t('Owner approval')} value={t(feedback.ownerApprovalStatus)} />
        <Fact label={t('Breaking migration')} value={t(feedback.breakingMigrationStatus)} />
        <Fact label={t('Received')} value={new Date(feedback.receivedAt).toLocaleString()} />
      </div>
      {feedback.findings.length > 0 && (
        <div className="correctness-governance-findings">
          {feedback.findings.map((finding) => (
            <article key={finding.findingId} data-severity={finding.severity}>
              <span>{t(finding.category)}</span>
              <div>
                <strong>{finding.code}</strong>
                <p>{finding.message}</p>
                <small>{finding.remediation}</small>
              </div>
              {finding.deepLink && (
                <a href={finding.deepLink} target="_blank" rel="noreferrer" title={t('Open in ANEKE')}>
                  <ExternalLink aria-hidden="true" size={16} />
                  <span className="visually-hidden">{t('Open in ANEKE')}</span>
                </a>
              )}
            </article>
          ))}
        </div>
      )}
      <p className="correctness-governance-boundary">
        {t('Resource Gateway projects this decision. ANEKE remains the authority for workbook and publish-gate lifecycle.')}
      </p>
    </div>
  );
}

function Fact({ label, value }: { label: string; value: string }) {
  return <span><strong>{label}</strong>{value}</span>;
}

function shortFingerprint(value: string): string {
  return value.length > 24 ? `${value.slice(0, 19)}…` : value;
}
