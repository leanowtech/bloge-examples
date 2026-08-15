import { FormEvent, useEffect, useMemo, useState } from 'react';
import { AlertTriangle, CheckCircle2, Lightbulb, Send } from 'lucide-react';

import { useI18n } from '../../i18n/I18nProvider';
import type {
  CorrectnessApiEnvelope,
  OutcomeCalibrationRequest,
  OutcomeMismatchKind,
  StoredCorrectnessEvidenceCompanion,
  StoredOutcomeCalibrationProposal,
} from '../model/domain';

export interface OutcomeCalibrationApi {
  calibrate(request: OutcomeCalibrationRequest):
  Promise<CorrectnessApiEnvelope<StoredOutcomeCalibrationProposal>>;
}

export default function OutcomeCalibrationPanel({
  evidence,
  available,
  api,
}: {
  evidence: StoredCorrectnessEvidenceCompanion;
  available: boolean;
  api: OutcomeCalibrationApi;
}) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const [caseIds, setCaseIds] = useState<string[]>([]);
  const [kind, setKind] = useState<OutcomeMismatchKind>('EXPECTED_OUTCOME_DIFFERED');
  const [reasonCode, setReasonCode] = useState('OBSERVED_OUTCOME_MISMATCH');
  const [rationale, setRationale] = useState('');
  const [title, setTitle] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const [receipt, setReceipt] = useState<StoredOutcomeCalibrationProposal | null>(null);
  const companion = evidence.companion;

  useEffect(() => {
    setOpen(false);
    setCaseIds(companion.caseRefs.map((value) => value.caseId));
    setKind('EXPECTED_OUTCOME_DIFFERED');
    setReasonCode('OBSERVED_OUTCOME_MISMATCH');
    setRationale('');
    setTitle('');
    setSubmitting(false);
    setError('');
    setReceipt(null);
  }, [evidence.companionFingerprint]);

  const oracleIds = useMemo(
    () => companion.oracleRefs.map((value) => value.id),
    [companion.oracleRefs],
  );
  const canSubmit = available && caseIds.length > 0 && oracleIds.length > 0
    && reasonCode.trim().length > 0 && rationale.trim().length > 0
    && title.trim().length > 0 && !submitting;

  const toggleCase = (caseId: string) => {
    setCaseIds((current) => current.includes(caseId)
      ? current.filter((value) => value !== caseId)
      : [...current, caseId].sort());
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!canSubmit) return;
    setSubmitting(true);
    setError('');
    try {
      const response = await api.calibrate({
        proposalId: proposalId(),
        suiteRunId: companion.suiteRunId,
        evidenceCompanionFingerprint: evidence.companionFingerprint,
        affectedCaseIds: caseIds,
        affectedOracleIds: oracleIds,
        mismatchKind: kind,
        reasonCode: reasonCode.trim().toUpperCase().replace(/[^A-Z0-9_]/g, '_'),
        businessRationale: rationale.trim(),
        proposedRegressionTitle: title.trim(),
      });
      setReceipt(response.data);
      setOpen(false);
    } catch (cause: unknown) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <section className="correctness-calibration" aria-label={t('Outcome calibration')}>
      <div className="correctness-calibration-intro">
        <Lightbulb aria-hidden="true" size={19} />
        <div>
          <strong>{t('Observed outcome differs from approved business truth?')}</strong>
          <span>{t('Capture the mismatch as a review proposal backed by this exact evidence.')}</span>
        </div>
        <button type="button" disabled={!available || Boolean(receipt)} onClick={() => setOpen((value) => !value)}>
          {t(open ? 'Close proposal form' : 'Propose calibration')}
        </button>
      </div>

      {!available && (
        <p className="correctness-inline-warning">
          {t('This deployment does not advertise outcome calibration proposals.')}
        </p>
      )}

      {receipt && (
        <div className="correctness-calibration-receipt" role="status">
          <CheckCircle2 aria-hidden="true" size={18} />
          <div>
            <strong>{t('Review proposal created')}</strong>
            <span>{receipt.proposal.proposalId} · {shortFingerprint(receipt.proposalFingerprint)}</span>
            <small>{t('Business truth and canonical Cases remain unchanged until a separate review approves a new revision.')}</small>
          </div>
        </div>
      )}

      {open && (
        <form className="correctness-calibration-form" onSubmit={(event) => void submit(event)}>
          <div className="correctness-calibration-boundary">
            <AlertTriangle aria-hidden="true" size={18} />
            <span>{t('This action proposes a correction. It does not edit the Oracle or publish a regression Case.')}</span>
          </div>

          <label>
            <span>{t('Mismatch type')}</span>
            <select value={kind} onChange={(event) => setKind(event.target.value as OutcomeMismatchKind)}>
              <option value="EXPECTED_OUTCOME_DIFFERED">{t('Expected outcome differed')}</option>
              <option value="FORBIDDEN_OUTCOME_OBSERVED">{t('Forbidden outcome observed')}</option>
              <option value="MISSING_BUSINESS_BRANCH">{t('Missing business branch')}</option>
              <option value="STALE_BUSINESS_ASSUMPTION">{t('Stale business assumption')}</option>
              <option value="OTHER">{t('Other mismatch')}</option>
            </select>
          </label>
          <label>
            <span>{t('Reason code')}</span>
            <input value={reasonCode} maxLength={128} onChange={(event) => setReasonCode(event.target.value)} />
          </label>
          <label className="wide">
            <span>{t('Why should the reviewed truth change?')}</span>
            <textarea
              value={rationale}
              maxLength={4000}
              rows={3}
              placeholder={t('State the business evidence and why the current expectation is no longer correct.')}
              onChange={(event) => setRationale(event.target.value)}
            />
          </label>
          <label className="wide">
            <span>{t('Proposed regression title')}</span>
            <input
              value={title}
              maxLength={240}
              placeholder={t('Name the regression behavior this proposal should preserve.')}
              onChange={(event) => setTitle(event.target.value)}
            />
          </label>

          <fieldset className="wide correctness-calibration-cases">
            <legend>{t('Affected evidence Cases')}</legend>
            {companion.caseRefs.map((testCase) => (
              <label key={testCase.caseId}>
                <input
                  type="checkbox"
                  checked={caseIds.includes(testCase.caseId)}
                  onChange={() => toggleCase(testCase.caseId)}
                />
                <span>{testCase.caseId}</span>
              </label>
            ))}
          </fieldset>

          <div className="wide correctness-calibration-closure">
            <span><strong>{caseIds.length}</strong>{t('Cases')}</span>
            <span><strong>{oracleIds.length}</strong>{t('Exact Oracles')}</span>
            <span><strong>1</strong>{t('Evidence companion')}</span>
          </div>
          {error && <p className="wide correctness-inline-error" role="alert">{error}</p>}
          <div className="wide correctness-form-actions">
            <button type="submit" className="correctness-primary-command" disabled={!canSubmit}>
              <Send aria-hidden="true" size={17} />
              {t(submitting ? 'Creating proposal' : 'Create review proposal')}
            </button>
          </div>
        </form>
      )}
    </section>
  );
}

function proposalId(): string {
  const value = globalThis.crypto?.randomUUID?.()
    ?? `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
  return `calibration-${value}`;
}

function shortFingerprint(value: string): string {
  return value.length > 24 ? `${value.slice(0, 19)}…` : value;
}
