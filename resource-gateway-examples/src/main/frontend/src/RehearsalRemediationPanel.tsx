import { useEffect, useMemo, useState } from 'react';

import {
  decideScenarioRehearsalRemediation,
  fetchScenarioRehearsalRemediationComparison,
  fetchScenarioRehearsalRemediationLineage,
  getRehearsalRemediationCredentialStatus,
  previewScenarioRehearsalRemediation,
  submitScenarioRehearsalRemediation,
  type RehearsalRemediationCredentialSlot,
  type RehearsalRemediationCredentialStatus,
} from './api';
import RehearsalRemediationComparison from './RehearsalRemediationComparison';
import type {
  ScenarioArtifactRef,
  ScenarioRemediationApprovalRole,
  ScenarioRemediationReason,
  ScenarioRemediationStrategy,
  ScenarioRehearsalBatchWorkbookSeed,
  ScenarioRehearsalRemediationApproval,
  ScenarioRehearsalRemediationApprovalCommand,
  ScenarioRehearsalRemediationComparison,
  ScenarioRehearsalRemediationPlan,
  ScenarioRehearsalRemediationPreviewRequest,
  ScenarioRehearsalRemediationReceipt,
} from './types';

interface RehearsalRemediationPanelProps {
  workbook: ScenarioRehearsalBatchWorkbookSeed;
  initialRemediationId: string;
  onRemediationIdChange: (remediationId: string) => void;
}

interface ReplacementDraft {
  selected: boolean;
  id: string;
  revision: string;
  fingerprint: string;
}

type WorkflowState = 'DRAFT' | 'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED' | 'SUBMITTED';
type BusyAction = '' | 'LOAD' | 'PREVIEW' | 'DECIDE' | 'SUBMIT' | 'COMPARE';

const FINGERPRINT = /^sha256:[a-f0-9]{64}$/;
const IDENTIFIER = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/;
const RERUN_REASONS: ScenarioRemediationReason[] = [
  'TRANSIENT_EXECUTION_RECHECK',
  'EVIDENCE_RECOVERY_RECHECK',
];
const REPLACEMENT_REASONS: ScenarioRemediationReason[] = [
  'SCENARIO_REVISION',
  'FIXTURE_REVISION',
  'ASSERTION_REVISION',
  'MIRROR_PLAN_REVISION',
];
const REJECTION_REASONS: Array<ScenarioRehearsalRemediationApprovalCommand['reasonCode']> = [
  'REJECTED_REQUIRES_CHANGES',
  'REJECTED_POLICY_CONFLICT',
  'REJECTED_INSUFFICIENT_EVIDENCE',
];

function createClientId(prefix: string): string {
  const cryptoApi = globalThis.crypto;
  if (typeof cryptoApi?.randomUUID === 'function') {
    return `${prefix}-${cryptoApi.randomUUID()}`;
  }
  const material = typeof cryptoApi?.getRandomValues === 'function'
    ? Array.from(cryptoApi.getRandomValues(new Uint32Array(3)), (part) => part.toString(36)).join('-')
    : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
  return `${prefix}-${material}`;
}

function initialReplacementDrafts(
  workbook: ScenarioRehearsalBatchWorkbookSeed,
): Record<number, ReplacementDraft> {
  return Object.fromEntries(workbook.entries.map((entry) => [
    entry.entryIndex,
    {
      selected: false,
      id: entry.compiledPlanRef.id,
      revision: String(entry.compiledPlanRef.revision),
      fingerprint: entry.compiledPlanRef.fingerprint,
    },
  ]));
}

function shortFingerprint(value: string): string {
  return value.length > 22 ? `${value.slice(0, 12)}...${value.slice(-7)}` : value;
}

function formatDate(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(date);
}

function statusTone(value: string): string {
  if (value === 'RESOLVED' || value === 'STILL_READY' || value === 'APPROVED' || value === 'SUBMITTED') {
    return 'success';
  }
  if (value === 'REJECTED' || value === 'REGRESSED') {
    return 'danger';
  }
  if (value === 'STILL_BLOCKED' || value === 'PENDING_APPROVAL') {
    return 'warning';
  }
  return 'neutral';
}

function credentialUsable(status: RehearsalRemediationCredentialStatus): boolean {
  if (!status.configured) {
    return false;
  }
  const expiresAt = Date.parse(status.expiresAt);
  return !status.expiresAt || Number.isNaN(expiresAt) || expiresAt > Date.now();
}

function credentialLabel(status: RehearsalRemediationCredentialStatus): string {
  if (!status.configured) {
    return 'Not connected';
  }
  return credentialUsable(status) ? status.principalLabel : 'Credential expired';
}

function exactArtifactRef(
  kind: 'COMPILED_REHEARSAL_PLAN' | 'GOVERNANCE_REVIEW_TICKET',
  id: string,
  revision: number,
  fingerprint: string,
): ScenarioArtifactRef {
  return { kind, id, revision, fingerprint };
}

function sameArtifactRef(left: ScenarioArtifactRef, right: ScenarioArtifactRef): boolean {
  return left.kind === right.kind
    && left.id === right.id
    && left.revision === right.revision
    && left.fingerprint === right.fingerprint;
}

function commandId(
  generation: number,
  role: ScenarioRemediationApprovalRole,
  decision: 'APPROVE' | 'REJECT',
  reason: ScenarioRehearsalRemediationApprovalCommand['reasonCode'],
): string {
  return `decision-${generation}-${role.toLowerCase()}-${decision.toLowerCase()}-${reason.toLowerCase()}`
    .slice(0, 128);
}

/** Governed human workflow for producing and evaluating a reviewed successor rehearsal batch. */
export default function RehearsalRemediationPanel({
  workbook,
  initialRemediationId,
  onRemediationIdChange,
}: RehearsalRemediationPanelProps) {
  const [credentialEpoch, setCredentialEpoch] = useState(0);
  const credentials = useMemo(() => ({
    READ: getRehearsalRemediationCredentialStatus('READ'),
    OWNER: getRehearsalRemediationCredentialStatus('OWNER'),
    INDEPENDENT_REVIEWER: getRehearsalRemediationCredentialStatus('INDEPENDENT_REVIEWER'),
  }), [credentialEpoch]);
  const readSlot = useMemo<RehearsalRemediationCredentialSlot | null>(() => {
    if (credentialUsable(credentials.READ)) {
      return 'READ';
    }
    if (credentialUsable(credentials.OWNER)) {
      return 'OWNER';
    }
    if (credentialUsable(credentials.INDEPENDENT_REVIEWER)) {
      return 'INDEPENDENT_REVIEWER';
    }
    return null;
  }, [credentials]);

  const [strategy, setStrategy] = useState<ScenarioRemediationStrategy>('RERUN_EXACT');
  const [reasonCode, setReasonCode] = useState<ScenarioRemediationReason>(
    'TRANSIENT_EXECUTION_RECHECK',
  );
  const [ticketId, setTicketId] = useState('');
  const [ticketRevision, setTicketRevision] = useState('1');
  const [ticketFingerprint, setTicketFingerprint] = useState('');
  const [replacements, setReplacements] = useState<Record<number, ReplacementDraft>>(
    () => initialReplacementDrafts(workbook),
  );
  const [previewRequestId, setPreviewRequestId] = useState(() => createClientId('preview'));
  const [plan, setPlan] = useState<ScenarioRehearsalRemediationPlan | null>(null);
  const [approvals, setApprovals] = useState<ScenarioRehearsalRemediationApproval[]>([]);
  const [receipt, setReceipt] = useState<ScenarioRehearsalRemediationReceipt | null>(null);
  const [workflowState, setWorkflowState] = useState<WorkflowState>('DRAFT');
  const [comparison, setComparison] = useState<ScenarioRehearsalRemediationComparison | null>(null);
  const [rejectionReason, setRejectionReason] =
    useState<ScenarioRehearsalRemediationApprovalCommand['reasonCode']>(
      'REJECTED_REQUIRES_CHANGES',
    );
  const [busy, setBusy] = useState<BusyAction>('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  useEffect(() => {
    if (!initialRemediationId) {
      return;
    }
    if (!readSlot) {
      setError('A read, Owner, or independent-reviewer identity is required to restore this remediation link.');
      return;
    }
    let cancelled = false;
    setBusy('LOAD');
    setError('');
    void fetchScenarioRehearsalRemediationLineage(initialRemediationId, readSlot)
      .then((lineage) => {
        if (cancelled) {
          return;
        }
        if (lineage.plan.predecessorJobId !== workbook.jobId
          || lineage.plan.predecessorWorkbookSeedFingerprint !== workbook.seedFingerprint
          || lineage.plan.remediationId !== initialRemediationId) {
          throw new Error('The deep-linked remediation does not belong to this signed workbook.');
        }
        setPlan(lineage.plan);
        setApprovals(lineage.approvals);
        setReceipt(lineage.receipt);
        setWorkflowState(lineage.state);
        setTicketId(lineage.plan.governanceTicketRef.id);
        setTicketRevision(String(lineage.plan.governanceTicketRef.revision));
        setTicketFingerprint(lineage.plan.governanceTicketRef.fingerprint);
        if (lineage.receipt) {
          setNotice(`Successor ${lineage.receipt.successorJobId} was admitted.`);
        }
      })
      .catch((cause: unknown) => {
        if (!cancelled) {
          setError(cause instanceof Error ? cause.message : 'Unable to restore reviewed remediation.');
        }
      })
      .finally(() => {
        if (!cancelled) {
          setBusy('');
        }
      });
    return () => {
      cancelled = true;
    };
  }, [initialRemediationId, readSlot, workbook.jobId, workbook.seedFingerprint]);

  const selectedReplacements = useMemo(() => workbook.entries
    .filter((entry) => replacements[entry.entryIndex]?.selected)
    .map((entry) => {
      const replacement = replacements[entry.entryIndex];
      return {
        entry,
        draft: replacement,
        revision: Number(replacement?.revision),
      };
    }), [replacements, workbook.entries]);
  const ticketRevisionNumber = Number(ticketRevision);
  const ticketValid = IDENTIFIER.test(ticketId)
    && Number.isInteger(ticketRevisionNumber)
    && ticketRevisionNumber >= 1
    && FINGERPRINT.test(ticketFingerprint);
  const replacementsValid = strategy === 'RERUN_EXACT' || (
    selectedReplacements.length > 0
      && selectedReplacements.every(({ entry, draft, revision }) => draft
        && IDENTIFIER.test(draft.id)
        && Number.isInteger(revision)
        && revision >= 1
        && FINGERPRINT.test(draft.fingerprint)
        && (draft.id !== entry.compiledPlanRef.id
          || revision !== entry.compiledPlanRef.revision
          || draft.fingerprint !== entry.compiledPlanRef.fingerprint))
  );
  const proposalValid = ticketValid && replacementsValid;
  const ownerUsable = credentialUsable(credentials.OWNER);
  const nextRole: ScenarioRemediationApprovalRole | null = workflowState === 'PENDING_APPROVAL'
    ? approvals.length === 0
      ? 'OWNER'
      : approvals.length === 1 && approvals[0].decision === 'APPROVE'
        ? 'INDEPENDENT_REVIEWER'
        : null
    : null;

  function proposalChanged(): void {
    setPreviewRequestId(createClientId('preview'));
    setError('');
    setNotice('');
  }

  function chooseStrategy(nextStrategy: ScenarioRemediationStrategy): void {
    setStrategy(nextStrategy);
    setReasonCode(nextStrategy === 'RERUN_EXACT'
      ? 'TRANSIENT_EXECUTION_RECHECK'
      : 'SCENARIO_REVISION');
    proposalChanged();
  }

  function updateReplacement(index: number, patch: Partial<ReplacementDraft>): void {
    setReplacements((current) => ({
      ...current,
      [index]: { ...current[index], ...patch },
    }));
    proposalChanged();
  }

  async function preview(): Promise<void> {
    if (!proposalValid || !ownerUsable) {
      return;
    }
    const request: ScenarioRehearsalRemediationPreviewRequest = {
      schemaVersion: 'resourceGateway.scenarioRehearsalRemediationPreviewRequest.v1',
      previewRequestId,
      expectedWorkbookSeedFingerprint: workbook.seedFingerprint,
      strategy,
      replacements: strategy === 'RERUN_EXACT'
        ? []
        : selectedReplacements.map(({ entry, draft, revision }) => ({
          entryIndex: entry.entryIndex,
          entryId: entry.entryId,
          expectedCompiledPlanRef: exactArtifactRef(
            'COMPILED_REHEARSAL_PLAN',
            entry.compiledPlanRef.id,
            entry.compiledPlanRef.revision,
            entry.compiledPlanRef.fingerprint,
          ),
          replacementCompiledPlanRef: exactArtifactRef(
            'COMPILED_REHEARSAL_PLAN',
            draft.id,
            revision,
            draft.fingerprint,
          ),
        })),
      governanceTicketRef: exactArtifactRef(
        'GOVERNANCE_REVIEW_TICKET',
        ticketId,
        ticketRevisionNumber,
        ticketFingerprint,
      ),
      reasonCode,
    };
    setBusy('PREVIEW');
    setError('');
    setNotice('');
    try {
      const frozen = await previewScenarioRehearsalRemediation(workbook.jobId, request);
      if (frozen.predecessorWorkbookSeedFingerprint !== workbook.seedFingerprint
        || frozen.predecessorJobId !== workbook.jobId
        || frozen.previewRequestId !== previewRequestId
        || !sameArtifactRef(frozen.governanceTicketRef, request.governanceTicketRef)) {
        throw new Error('The frozen plan does not close over the proposal being reviewed.');
      }
      setPlan(frozen);
      setApprovals([]);
      setReceipt(null);
      setComparison(null);
      setWorkflowState('PENDING_APPROVAL');
      setNotice('The successor plan is frozen. Owner approval is the next required fact.');
      onRemediationIdChange(frozen.remediationId);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Unable to freeze the remediation plan.');
    } finally {
      setBusy('');
    }
  }

  async function decide(decision: 'APPROVE' | 'REJECT'): Promise<void> {
    if (!plan || !nextRole) {
      return;
    }
    const reason = decision === 'APPROVE' ? 'APPROVED_AS_REVIEWED' : rejectionReason;
    const generation = approvals.length;
    const request: ScenarioRehearsalRemediationApprovalCommand = {
      schemaVersion: 'resourceGateway.scenarioRehearsalRemediationApprovalCommand.v1',
      commandId: commandId(generation, nextRole, decision, reason),
      remediationPlanFingerprint: plan.planFingerprint,
      expectedApprovalGeneration: generation,
      role: nextRole,
      decision,
      governanceTicketRef: plan.governanceTicketRef,
      reasonCode: reason,
    };
    setBusy('DECIDE');
    setError('');
    setNotice('');
    try {
      const approval = await decideScenarioRehearsalRemediation(plan.remediationId, request);
      if (approval.generation !== generation + 1
        || approval.role !== nextRole
        || approval.decision !== decision
        || approval.reasonCode !== reason
        || approval.remediationId !== plan.remediationId
        || approval.remediationPlanFingerprint !== plan.planFingerprint
        || approval.previousApprovalFingerprint
          !== (approvals[approvals.length - 1]?.approvalFingerprint ?? '')
        || !sameArtifactRef(approval.governanceTicketRef, plan.governanceTicketRef)) {
        throw new Error('The returned decision fact does not match the reviewed command.');
      }
      const nextApprovals = [...approvals, approval];
      setApprovals(nextApprovals);
      setWorkflowState(decision === 'REJECT'
        ? 'REJECTED'
        : nextApprovals.length === plan.approvalPolicy.requiredRoles.length
          ? 'APPROVED'
          : 'PENDING_APPROVAL');
      setNotice(decision === 'REJECT'
        ? `${roleLabel(nextRole)} rejected this frozen plan.`
        : `${roleLabel(nextRole)} approval was appended by ${approval.actorId}.`);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Unable to append the reviewed decision.');
    } finally {
      setBusy('');
    }
  }

  async function submit(): Promise<void> {
    if (!plan || workflowState !== 'APPROVED' || approvals.length < 2) {
      return;
    }
    const head = approvals[approvals.length - 1];
    setBusy('SUBMIT');
    setError('');
    setNotice('');
    try {
      const accepted = await submitScenarioRehearsalRemediation(plan.remediationId, {
        schemaVersion: 'resourceGateway.scenarioRehearsalRemediationSubmitCommand.v1',
        commandId: `submit-${approvals.length}-${head.approvalFingerprint.slice(7, 27)}`,
        remediationPlanFingerprint: plan.planFingerprint,
        expectedApprovalGeneration: approvals.length,
        expectedApprovalHeadFingerprint: head.approvalFingerprint,
        reasonCode: 'APPROVALS_COMPLETE',
      });
      if (accepted.approvalHeadFingerprint !== head.approvalFingerprint
        || accepted.approvalGeneration !== approvals.length
        || accepted.predecessorJobId !== workbook.jobId
        || accepted.remediationId !== plan.remediationId
        || accepted.remediationPlanFingerprint !== plan.planFingerprint
        || accepted.successorRequestFingerprint !== plan.successorRequestFingerprint) {
        throw new Error('The successor receipt does not close over the approved decision chain.');
      }
      setReceipt(accepted);
      setWorkflowState('SUBMITTED');
      setNotice(`Successor ${accepted.successorJobId} was admitted. Its terminal evidence can now be compared.`);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Unable to admit the approved successor.');
    } finally {
      setBusy('');
    }
  }

  async function loadComparison(): Promise<void> {
    if (!plan || !receipt || !readSlot) {
      return;
    }
    setBusy('COMPARE');
    setError('');
    setNotice('');
    try {
      const nextComparison = await fetchScenarioRehearsalRemediationComparison(
        plan.remediationId,
        readSlot,
      );
      if (nextComparison.receiptFingerprint !== receipt.receiptFingerprint
        || nextComparison.predecessor.jobId !== workbook.jobId
        || nextComparison.successor.jobId !== receipt.successorJobId
        || nextComparison.remediationId !== plan.remediationId
        || nextComparison.remediationPlanFingerprint !== plan.planFingerprint) {
        throw new Error('The signed-workbook comparison does not close over this receipt.');
      }
      setComparison(nextComparison);
      setNotice('Verified predecessor and successor workbooks are now shown side by side.');
    } catch (cause) {
      const detail = cause instanceof Error ? cause.message : 'Unable to compare signed workbooks.';
      setError(detail.includes('409')
        ? 'The successor is not terminal yet. Refresh after its root-signed workbook is available.'
        : detail);
    } finally {
      setBusy('');
    }
  }

  function resetProposal(): void {
    setPlan(null);
    setApprovals([]);
    setReceipt(null);
    setComparison(null);
    setWorkflowState('DRAFT');
    setStrategy('RERUN_EXACT');
    setReasonCode('TRANSIENT_EXECUTION_RECHECK');
    setReplacements(initialReplacementDrafts(workbook));
    setPreviewRequestId(createClientId('preview'));
    setError('');
    setNotice('');
    onRemediationIdChange('');
  }

  const stages = [
    { id: 'PLAN', label: 'Freeze plan', complete: plan !== null },
    { id: 'OWNER', label: 'Owner', complete: approvals.some((approval) => approval.role === 'OWNER') },
    {
      id: 'REVIEWER',
      label: 'Independent review',
      complete: approvals.some((approval) => approval.role === 'INDEPENDENT_REVIEWER'),
    },
    { id: 'SUBMIT', label: 'Admit successor', complete: receipt !== null },
    { id: 'COMPARE', label: 'Compare evidence', complete: comparison !== null },
  ];
  const currentStage = stages.findIndex((stage) => !stage.complete);

  return (
    <section className="remediation-workflow" data-testid="remediation-workflow">
      <header className="remediation-heading">
        <div>
          <p className="workbench-kicker">Separation-of-duties workflow</p>
          <h3>Reviewed remediation</h3>
        </div>
        <div className="remediation-heading-actions">
          <button
            className="compact-command"
            type="button"
            onClick={() => setCredentialEpoch((current) => current + 1)}
          >
            Refresh identities
          </button>
          <span className={`status-label ${statusTone(workflowState)}`} data-testid="remediation-state">
            {workflowState.replace(/_/g, ' ')}
          </span>
        </div>
      </header>

      <ol className="remediation-timeline" aria-label="Reviewed remediation progress">
        {stages.map((stage, index) => (
          <li
            className={stage.complete ? 'complete' : index === currentStage ? 'current' : ''}
            key={stage.id}
          >
            <span aria-hidden="true">{stage.complete ? '✓' : index + 1}</span>
            <strong>{stage.label}</strong>
          </li>
        ))}
      </ol>

      <div className="remediation-identities" data-testid="remediation-identities">
        {(['OWNER', 'INDEPENDENT_REVIEWER', 'READ'] as const).map((slot) => {
          const status = credentials[slot];
          return (
            <div key={slot}>
              <span>{roleLabel(slot)}</span>
              <strong className={credentialUsable(status) ? 'identity-ready' : 'identity-missing'}>
                {credentialLabel(status)}
              </strong>
              {status.expiresAt && credentialUsable(status) && <small>until {formatDate(status.expiresAt)}</small>}
            </div>
          );
        })}
      </div>

      {error && <div className="workbench-alert danger" role="alert">{error}</div>}
      {notice && <div className="workbench-alert" role="status">{notice}</div>}
      {busy === 'LOAD' && <p className="empty-workbench">Restoring the immutable decision lineage...</p>}

      {!plan && busy !== 'LOAD' && (
        <div className="remediation-proposal">
          <div className="remediation-section-heading">
            <div>
              <h4>Successor proposal</h4>
              <p>The signed predecessor remains fixed; only the closed successor choices below are admitted.</p>
            </div>
            <code title={previewRequestId}>{shortFingerprint(previewRequestId)}</code>
          </div>

          <div className="remediation-strategy" role="group" aria-label="Successor strategy">
            <button
              type="button"
              className={strategy === 'RERUN_EXACT' ? 'active' : ''}
              onClick={() => chooseStrategy('RERUN_EXACT')}
            >
              Retry exact
            </button>
            <button
              type="button"
              className={strategy === 'REPLACE_COMPILED_PLANS' ? 'active' : ''}
              onClick={() => chooseStrategy('REPLACE_COMPILED_PLANS')}
            >
              Replace plans
            </button>
          </div>

          <div className="remediation-fields">
            <label>
              <span>Reason</span>
              <select
                aria-label="Remediation reason"
                value={reasonCode}
                onChange={(event) => {
                  setReasonCode(event.target.value as ScenarioRemediationReason);
                  proposalChanged();
                }}
              >
                {(strategy === 'RERUN_EXACT' ? RERUN_REASONS : REPLACEMENT_REASONS)
                  .map((reason) => <option value={reason} key={reason}>{reasonLabel(reason)}</option>)}
              </select>
            </label>
            <label>
              <span>Governance ticket</span>
              <input
                aria-label="Governance ticket ID"
                value={ticketId}
                placeholder="ANEKE-4821"
                onChange={(event) => {
                  setTicketId(event.target.value);
                  proposalChanged();
                }}
              />
            </label>
            <label className="compact-field">
              <span>Revision</span>
              <input
                aria-label="Governance ticket revision"
                inputMode="numeric"
                value={ticketRevision}
                onChange={(event) => {
                  setTicketRevision(event.target.value);
                  proposalChanged();
                }}
              />
            </label>
            <label className="fingerprint-field">
              <span>Ticket fingerprint</span>
              <input
                aria-label="Governance ticket fingerprint"
                value={ticketFingerprint}
                placeholder="sha256:..."
                onChange={(event) => {
                  setTicketFingerprint(event.target.value.trim());
                  proposalChanged();
                }}
              />
            </label>
          </div>

          {strategy === 'REPLACE_COMPILED_PLANS' && (
            <div className="replacement-table" data-testid="replacement-table">
              <div className="replacement-table-head">
                <span>Use</span>
                <span>Entry / current plan</span>
                <span>Replacement identity</span>
                <span>Revision</span>
                <span>Fingerprint</span>
              </div>
              {workbook.entries.map((entry) => {
                const replacement = replacements[entry.entryIndex];
                return (
                  <div className="replacement-table-row" key={entry.entryId}>
                    <input
                      aria-label={`Replace ${entry.entryId}`}
                      type="checkbox"
                      checked={replacement?.selected ?? false}
                      onChange={(event) => updateReplacement(entry.entryIndex, {
                        selected: event.target.checked,
                      })}
                    />
                    <span>
                      <strong>#{entry.entryIndex} {entry.entryId}</strong>
                      <small>{entry.compiledPlanRef.id}@{entry.compiledPlanRef.revision}</small>
                    </span>
                    <input
                      aria-label={`Replacement plan ID for ${entry.entryId}`}
                      disabled={!replacement?.selected}
                      value={replacement?.id ?? ''}
                      onChange={(event) => updateReplacement(entry.entryIndex, { id: event.target.value })}
                    />
                    <input
                      aria-label={`Replacement plan revision for ${entry.entryId}`}
                      disabled={!replacement?.selected}
                      inputMode="numeric"
                      value={replacement?.revision ?? ''}
                      onChange={(event) => updateReplacement(entry.entryIndex, {
                        revision: event.target.value,
                      })}
                    />
                    <input
                      aria-label={`Replacement plan fingerprint for ${entry.entryId}`}
                      disabled={!replacement?.selected}
                      value={replacement?.fingerprint ?? ''}
                      onChange={(event) => updateReplacement(entry.entryIndex, {
                        fingerprint: event.target.value.trim(),
                      })}
                    />
                  </div>
                );
              })}
            </div>
          )}

          <div className="remediation-command-row">
            {!ownerUsable && (
              <span>Connect a short-lived Owner identity in the host before freezing a plan.</span>
            )}
            {ownerUsable && !proposalValid && (
              <span>Provide an exact ticket reference{strategy === 'REPLACE_COMPILED_PLANS'
                ? ' and at least one changed plan reference' : ''}.</span>
            )}
            <button
              type="button"
              className="primary-command"
              disabled={!ownerUsable || !proposalValid || busy !== ''}
              onClick={() => void preview()}
            >
              {busy === 'PREVIEW' ? 'Freezing...' : 'Freeze for review'}
            </button>
          </div>
        </div>
      )}

      {plan && (
        <div className="remediation-lineage">
          <div className="remediation-section-heading">
            <div>
              <h4>Frozen successor</h4>
              <p>{plan.strategy === 'RERUN_EXACT'
                ? 'Every exact compiled plan will be rerun.'
                : `${plan.replacements.length} compiled plan replacement(s) are frozen.`}</p>
            </div>
            <span className="status-label neutral">{plan.reasonCode}</span>
          </div>
          <dl className="remediation-facts">
            <div><dt>Remediation</dt><dd>{plan.remediationId}</dd></div>
            <div><dt>Plan fingerprint</dt><dd title={plan.planFingerprint}>{shortFingerprint(plan.planFingerprint)}</dd></div>
            <div><dt>Successor request</dt><dd title={plan.successorRequestFingerprint}>{shortFingerprint(plan.successorRequestFingerprint)}</dd></div>
            <div><dt>Review deadline</dt><dd>{formatDate(plan.expiresAt)}</dd></div>
          </dl>

          <div className="approval-ledger" data-testid="approval-ledger">
            <div className="approval-ledger-head">
              <span>Generation</span>
              <span>Role</span>
              <span>Decision</span>
              <span>Server-bound actor</span>
              <span>Time</span>
            </div>
            {approvals.map((approval) => (
              <div className="approval-ledger-row" key={approval.approvalFingerprint}>
                <span>#{approval.generation}</span>
                <strong>{roleLabel(approval.role)}</strong>
                <span className={`status-label ${approval.decision === 'APPROVE' ? 'success' : 'danger'}`}>
                  {approval.decision}
                </span>
                <span>{approval.actorId}{approval.delegatedBy ? ` via ${approval.delegatedBy}` : ''}</span>
                <span>{formatDate(approval.decidedAt)}</span>
              </div>
            ))}
            {approvals.length === 0 && <p>No decision facts have been appended.</p>}
          </div>

          {nextRole && (
            <div className="decision-bar">
              <div>
                <strong>Next: {roleLabel(nextRole)}</strong>
                <span>
                  {credentialUsable(credentials[nextRole])
                    ? `Host identity: ${credentials[nextRole].principalLabel}`
                    : 'The required human identity is not connected.'}
                </span>
              </div>
              <select
                aria-label="Rejection reason"
                value={rejectionReason}
                onChange={(event) => setRejectionReason(
                  event.target.value as ScenarioRehearsalRemediationApprovalCommand['reasonCode'],
                )}
              >
                {REJECTION_REASONS.map((reason) => (
                  <option key={reason} value={reason}>{reasonLabel(reason)}</option>
                ))}
              </select>
              <button
                className="danger-command"
                type="button"
                disabled={!credentialUsable(credentials[nextRole]) || busy !== ''}
                onClick={() => void decide('REJECT')}
              >
                Reject
              </button>
              <button
                className="primary-command"
                type="button"
                disabled={!credentialUsable(credentials[nextRole]) || busy !== ''}
                onClick={() => void decide('APPROVE')}
              >
                {busy === 'DECIDE' ? 'Appending...' : 'Approve reviewed plan'}
              </button>
            </div>
          )}

          {workflowState === 'APPROVED' && (
            <div className="decision-bar submit-bar">
              <div>
                <strong>Two-person approval complete</strong>
                <span>The exact approval head and frozen request will be admitted atomically.</span>
              </div>
              <button
                className="primary-command"
                type="button"
                disabled={!ownerUsable || busy !== ''}
                onClick={() => void submit()}
              >
                {busy === 'SUBMIT' ? 'Admitting...' : 'Admit successor'}
              </button>
            </div>
          )}

          {workflowState === 'REJECTED' && (
            <div className="decision-bar rejected-bar">
              <div>
                <strong>Immutable rejection retained</strong>
                <span>Create a new proposal after the governed source artifacts have changed.</span>
              </div>
              <button className="compact-command" type="button" onClick={resetProposal}>
                Draft another proposal
              </button>
            </div>
          )}

          {receipt && (
            <div className="successor-receipt" data-testid="successor-receipt">
              <div>
                <span>Admitted successor</span>
                <strong>{receipt.successorJobId}</strong>
                <small>Accepted by {receipt.acceptedBy} at {formatDate(receipt.acceptedAt)}</small>
              </div>
              <button
                className="primary-command"
                type="button"
                disabled={!readSlot || busy !== ''}
                onClick={() => void loadComparison()}
              >
                {busy === 'COMPARE' ? 'Verifying...' : comparison ? 'Refresh comparison' : 'Compare signed evidence'}
              </button>
            </div>
          )}
        </div>
      )}

      {comparison && <RehearsalRemediationComparison comparison={comparison} />}
    </section>
  );
}

function roleLabel(role: RehearsalRemediationCredentialSlot | ScenarioRemediationApprovalRole): string {
  if (role === 'INDEPENDENT_REVIEWER') {
    return 'Independent reviewer';
  }
  if (role === 'OWNER') {
    return 'Owner';
  }
  return 'Evidence reader';
}

function reasonLabel(reason: ScenarioRemediationReason
| ScenarioRehearsalRemediationApprovalCommand['reasonCode']): string {
  return reason
    .toLowerCase()
    .split('_')
    .map((part) => part[0].toUpperCase() + part.slice(1))
    .join(' ');
}
