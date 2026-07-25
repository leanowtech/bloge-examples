package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable reviewed-remediation state machine and append-only decision ledger.
 *
 * <p>Implementations own idempotency, database-clock expiry, approval ordering, distinct-actor
 * separation, compare-and-set submission, and atomic successor admission. Canonical plan,
 * approval, and receipt JSON remain the source facts; mutable status and approval-head columns
 * are verified projections used only for concurrency control.</p>
 */
public interface ScenarioRehearsalRemediationRepository {
    /** Derived lifecycle state of one immutable remediation lineage. */
    enum State {
        PENDING_APPROVAL,
        APPROVED,
        REJECTED,
        SUBMITTED
    }

    /** Complete immutable preview creation material. */
    record Preview(
            ScenarioRehearsalRemediationPlan plan,
            String previewRequestFingerprint
    ) {
        /** Requires a sealed plan and exact request content address. */
        public Preview {
            plan = Objects.requireNonNull(plan, "plan");
            previewRequestFingerprint =
                    MirrorStateProtocolSupport.fingerprint(
                            previewRequestFingerprint,
                            "previewRequestFingerprint");
        }
    }

    /** Authenticated approval mutation; actor and time never come from the wire command. */
    record ApprovalMutation(
            CapabilitySnapshot.Scope scope,
            String remediationId,
            ScenarioRehearsalRemediationApprovalCommand command,
            String commandFingerprint,
            String actorId,
            String delegatedBy
    ) {
        /** Validates exact immutable command and authenticated actor coordinates. */
        public ApprovalMutation {
            scope = Objects.requireNonNull(scope, "scope");
            remediationId = MirrorStateProtocolSupport.required(
                    remediationId, "remediationId");
            command = Objects.requireNonNull(command, "command");
            commandFingerprint =
                    MirrorStateProtocolSupport.fingerprint(
                            commandFingerprint,
                            "commandFingerprint");
            actorId = MirrorStateProtocolSupport.required(
                    actorId, "actorId");
            delegatedBy = delegatedBy == null
                    ? "" : delegatedBy.trim();
        }
    }

    /** Fully compiled successor admission and authenticated submit command. */
    record SubmissionMutation(
            CapabilitySnapshot.Scope scope,
            String remediationId,
            ScenarioRehearsalRemediationSubmitCommand command,
            String commandFingerprint,
            ScenarioRehearsalBatchRepository.Submission
                    successorSubmission,
            String acceptedBy,
            String delegatedBy
    ) {
        /** Validates complete scope, immutable command, and successor admission material. */
        public SubmissionMutation {
            scope = Objects.requireNonNull(scope, "scope");
            remediationId = MirrorStateProtocolSupport.required(
                    remediationId, "remediationId");
            command = Objects.requireNonNull(command, "command");
            commandFingerprint =
                    MirrorStateProtocolSupport.fingerprint(
                            commandFingerprint,
                            "commandFingerprint");
            successorSubmission = Objects.requireNonNull(
                    successorSubmission, "successorSubmission");
            acceptedBy = MirrorStateProtocolSupport.required(
                    acceptedBy, "acceptedBy");
            delegatedBy = delegatedBy == null
                    ? "" : delegatedBy.trim();
        }
    }

    /** Exact current read model reconstructed from immutable facts. */
    record Snapshot(
            ScenarioRehearsalRemediationPlan plan,
            State state,
            List<ScenarioRehearsalRemediationApproval> approvals,
            ScenarioRehearsalRemediationReceipt receipt
    ) {
        /** Enforces state, chain-head, and receipt correspondence. */
        public Snapshot {
            plan = Objects.requireNonNull(plan, "plan");
            state = Objects.requireNonNull(state, "state");
            approvals = approvals == null
                    ? List.of() : List.copyOf(approvals);
            String previous = "";
            for (int index = 0; index < approvals.size(); index++) {
                ScenarioRehearsalRemediationApproval approval =
                        Objects.requireNonNull(
                                approvals.get(index), "approval");
                if (approval.generation() != index + 1L
                        || !approval.previousApprovalFingerprint()
                        .equals(previous)
                        || !approval.remediationId().equals(
                        plan.remediationId())
                        || !approval.remediationPlanFingerprint()
                        .equals(plan.planFingerprint())) {
                    throw new IllegalStateException(
                            "Scenario remediation approval chain is inconsistent");
                }
                previous = approval.approvalFingerprint();
            }
            boolean rejected = approvals.stream().anyMatch(
                    approval -> approval.decision()
                            == ScenarioRehearsalRemediationApprovalCommand
                            .Decision.REJECT);
            boolean complete = approvals.size() == 2
                    && approvals.stream().allMatch(
                    approval -> approval.decision()
                            == ScenarioRehearsalRemediationApprovalCommand
                            .Decision.APPROVE);
            if (state == State.REJECTED != rejected
                    || state == State.APPROVED
                    && !complete
                    || state == State.PENDING_APPROVAL
                    && (rejected || complete)
                    || state == State.SUBMITTED
                    != (receipt != null)
                    || receipt != null
                    && (!complete
                    || !receipt.remediationId().equals(
                    plan.remediationId())
                    || !receipt.approvalHeadFingerprint()
                    .equals(previous))) {
                throw new IllegalStateException(
                        "Scenario remediation state differs from immutable facts");
            }
        }

        /** @return current approval generation */
        public long approvalGeneration() {
            return approvals.size();
        }

        /** @return current approval head, blank before the first decision */
        public String approvalHeadFingerprint() {
            return approvals.isEmpty()
                    ? ""
                    : approvals.getLast()
                    .approvalFingerprint();
        }
    }

    /** Idempotent preview creation result. */
    record PreviewResult(
            ScenarioRehearsalRemediationPlan plan,
            boolean idempotentReplay
    ) {
        /** Requires one immutable retained plan. */
        public PreviewResult {
            plan = Objects.requireNonNull(plan, "plan");
        }
    }

    /** Idempotent append-only approval result. */
    record ApprovalResult(
            ScenarioRehearsalRemediationApproval approval,
            boolean idempotentReplay
    ) {
        /** Requires one immutable retained approval. */
        public ApprovalResult {
            approval = Objects.requireNonNull(
                    approval, "approval");
        }
    }

    /** Idempotent atomic successor-admission result. */
    record SubmissionResult(
            ScenarioRehearsalRemediationReceipt receipt,
            boolean idempotentReplay
    ) {
        /** Requires one immutable retained receipt. */
        public SubmissionResult {
            receipt = Objects.requireNonNull(receipt, "receipt");
        }
    }

    /**
     * Creates or exactly recovers one immutable preview with its success audit atomically.
     */
    PreviewResult create(
            Preview preview,
            ScenarioRehearsalRemediationPolicy policy,
            MirrorOperationObservability.Observation observation);

    /**
     * Appends one role decision and its protected-operation success audit atomically.
     */
    ApprovalResult approve(
            ApprovalMutation mutation,
            ScenarioRehearsalRemediationPolicy policy,
            MirrorOperationObservability.Observation observation);

    /**
     * Admits the exact successor and commits receipt plus success audit in one transaction.
     */
    SubmissionResult submit(
            SubmissionMutation mutation,
            ScenarioRehearsalRemediationPolicy policy,
            ScenarioRehearsalBatchPolicy batchPolicy,
            MirrorOperationObservability.Observation observation);

    /** Reads one integrity-verified immutable lineage inside the exact enterprise scope. */
    Optional<Snapshot> find(
            CapabilitySnapshot.Scope scope,
            String remediationId);
}
