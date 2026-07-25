package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Database-authoritative durable queue for multi-plan Scenario rehearsals.
 *
 * <p>Implementations must serialize admission, fairness cursor movement, claim, cancellation,
 * retry, and item completion per region and environment. All deadlines and lease decisions use
 * database time. One lease authorizes exactly one manifest item, so a crashed worker can replay
 * the stable child request without rerunning already checkpointed items.</p>
 */
public interface ScenarioRehearsalBatchRepository {
    Pattern FINALIZATION_FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");
    Pattern FINALIZATION_SIGNING_REQUEST_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}");

    /** Complete immutable submission material after exact-plan resolution. */
    record Submission(
            ScenarioRehearsalBatchRequest request,
            String requestFingerprint,
            ScenarioRehearsalBatchManifest manifest,
            ScenarioRehearsalBatchPrincipal principal
    ) {
        /** Validates ordered manifest closure and scope correspondence. */
        public Submission {
            request = Objects.requireNonNull(request, "request");
            requestFingerprint = required(
                    requestFingerprint, "requestFingerprint");
            manifest = Objects.requireNonNull(
                    manifest, "manifest");
            principal = Objects.requireNonNull(
                    principal, "principal");
            if (!manifest.scope().equals(principal.scope())
                    || !manifest.requestId().equals(
                    request.requestId())
                    || manifest.entries().size()
                    != request.entries().size()) {
                throw new IllegalArgumentException(
                    "Scenario batch submission closure is inconsistent");
            }
            for (int index = 0;
                 index < manifest.entries().size();
                 index++) {
                ScenarioRehearsalBatchManifest.Entry entry =
                        manifest.entries().get(index);
                ScenarioRehearsalBatchRequest.Entry requested =
                        request.entries().get(index);
                if (entry.entryIndex() != index
                        || !entry.entryId().equals(
                        requested.entryId())
                        || !entry.compiledPlanRef().equals(
                        requested.compiledPlanRef())) {
                    throw new IllegalArgumentException(
                            "Scenario batch plans must preserve request order");
                }
            }
        }
    }

    /** Durable replay disposition for queue admission. */
    record SubmissionResult(
            ScenarioRehearsalBatchJob job,
            boolean idempotentReplay
    ) {
        /** Requires a concrete retained projection. */
        public SubmissionResult {
            job = Objects.requireNonNull(job, "job");
        }
    }

    /** Exact current one-item worker fence. */
    record Lease(
            CapabilitySnapshot.Scope scope,
            String jobId,
            String ownerId,
            long epoch,
            int itemIndex,
            Instant expiresAt
    ) {
        /** Validates a complete positive fence. */
        public Lease {
            scope = Objects.requireNonNull(scope, "scope");
            jobId = required(jobId, "jobId");
            ownerId = required(ownerId, "ownerId");
            if (epoch < 1 || itemIndex < 0) {
                throw new IllegalArgumentException(
                        "Scenario batch lease coordinates are invalid");
            }
            expiresAt = Objects.requireNonNull(
                    expiresAt, "expiresAt");
        }
    }

    /** Queue claim disposition. */
    enum ClaimOutcome {
        ACQUIRED,
        NO_WORK
    }

    /** One acquired item or a bounded no-work observation. */
    record Claim(
            ClaimOutcome outcome,
            Instant observedAt,
            ScenarioRehearsalBatchJob job,
            ScenarioRehearsalBatchItemPage.Item item,
            ScenarioRehearsalBatchPrincipal principal,
            Lease lease
    ) {
        /** Enforces acquired-field correspondence. */
        public Claim {
            outcome = Objects.requireNonNull(outcome, "outcome");
            observedAt = Objects.requireNonNull(
                    observedAt, "observedAt");
            boolean acquired = outcome == ClaimOutcome.ACQUIRED;
            if (acquired != (job != null
                    && item != null
                    && principal != null
                    && lease != null)) {
                throw new IllegalArgumentException(
                        "Scenario batch claim fields are inconsistent");
            }
        }

        /** Creates a database-clock no-work observation. */
        public static Claim noWork(Instant observedAt) {
            return new Claim(
                    ClaimOutcome.NO_WORK,
                    observedAt,
                    null,
                    null,
                    null,
                    null);
        }
    }

    /** Database-authoritative outcome of one payload-free execution heartbeat. */
    enum ExecutionControlOutcome {
        CONTINUE,
        CANCELLED,
        DEADLINE_EXCEEDED,
        LEASE_LOST
    }

    /**
     * Persisted heartbeat and cooperative-control decision for the current item.
     *
     * @param outcome continue or a closed stop reason
     * @param observedAt database-clock decision time
     * @param heartbeatCount monotonic heartbeat count for the current item attempt
     * @param nextCaseIndex latest observed aggregate progress cursor
     * @param job integrity-verified job projection after the decision
     */
    record ExecutionControlCheckpoint(
            ExecutionControlOutcome outcome,
            Instant observedAt,
            long heartbeatCount,
            int nextCaseIndex,
            ScenarioRehearsalBatchJob job
    ) {
        /** Validates heartbeat coordinates and terminal decision closure. */
        public ExecutionControlCheckpoint {
            outcome = Objects.requireNonNull(outcome, "outcome");
            observedAt = Objects.requireNonNull(
                    observedAt, "observedAt");
            job = Objects.requireNonNull(job, "job");
            if (heartbeatCount < 0 || nextCaseIndex < 0) {
                throw new IllegalArgumentException(
                        "Scenario batch execution checkpoint is invalid");
            }
            boolean cancelled =
                    job.status()
                            == ScenarioRehearsalBatchJob.Status.CANCELLED
                            || job.status()
                            == ScenarioRehearsalBatchJob.Status
                            .FINALIZING_EVIDENCE
                            && !job.cancellationRequestId().isBlank();
            boolean expired =
                    job.status()
                            == ScenarioRehearsalBatchJob.Status.EXPIRED
                            || job.status()
                            == ScenarioRehearsalBatchJob.Status
                            .FINALIZING_EVIDENCE
                            && "RG.MIRROR.REHEARSAL_BATCH.DEADLINE_EXCEEDED"
                            .equals(job.failureCode());
            if (outcome == ExecutionControlOutcome.CANCELLED
                    && !cancelled
                    || outcome
                    == ExecutionControlOutcome.DEADLINE_EXCEEDED
                    && !expired) {
                throw new IllegalArgumentException(
                        "Scenario batch stop decision differs from its job");
            }
        }
    }

    /** Terminal execution projection supplied after independent Scenario evidence verification. */
    record ItemCompletion(
            ScenarioCaseRehearsalResult.Outcome outcome,
            String runId,
            String evidenceBundleFingerprint,
            String workbookSeedFingerprint
    ) {
        /** Requires a complete evidence-backed item result. */
        public ItemCompletion {
            outcome = Objects.requireNonNull(outcome, "outcome");
            runId = required(runId, "runId");
            evidenceBundleFingerprint = required(
                    evidenceBundleFingerprint,
                    "evidenceBundleFingerprint");
            workbookSeedFingerprint = required(
                    workbookSeedFingerprint,
                    "workbookSeedFingerprint");
        }
    }

    /** Idempotent cancellation command. */
    record Cancellation(
            CapabilitySnapshot.Scope scope,
            String jobId,
            String commandId,
            String reasonCode
    ) {
        private static final java.util.regex.Pattern REASON_CODE =
                java.util.regex.Pattern.compile(
                        "[A-Z][A-Z0-9_.-]{0,127}");

        /** Validates exact scope, job, and bounded command identity. */
        public Cancellation {
            scope = Objects.requireNonNull(scope, "scope");
            jobId = required(jobId, "jobId");
            commandId = required(commandId, "commandId");
            reasonCode = required(reasonCode, "reasonCode")
                    .toUpperCase(java.util.Locale.ROOT);
            if (!REASON_CODE.matcher(reasonCode).matches()) {
                throw new IllegalArgumentException(
                        "Scenario batch cancellation reason code is invalid");
            }
        }
    }

    /** Immutable payload-free material frozen before any remote signing call. */
    record FinalizationIntent(
            String schemaVersion,
            String intentFingerprint,
            String signingRequestId,
            ScenarioRehearsalBatchJob finalizingJob,
            ScenarioRehearsalBatchJob terminalJob,
            ScenarioRehearsalBatchRequest request,
            ScenarioRehearsalBatchManifest manifest,
            List<ScenarioRehearsalBatchItemPage.Item> items,
            Instant retainUntil,
            Instant queuedAt
    ) {
        /** Current embedded finalization-intent version. */
        public static final String SCHEMA_VERSION =
                "resourceGateway.scenarioRehearsalBatchFinalizationIntent.v1";

        /** Enforces exact interim/terminal and evidence-source closure. */
        public FinalizationIntent {
            schemaVersion = normalized(schemaVersion);
            if (schemaVersion.isBlank()) {
                schemaVersion = SCHEMA_VERSION;
            }
            if (!SCHEMA_VERSION.equals(schemaVersion)) {
                throw new IllegalArgumentException(
                        "unsupported Scenario batch finalization intent");
            }
            intentFingerprint = optionalFingerprint(
                    intentFingerprint, "intentFingerprint");
            signingRequestId = required(
                    signingRequestId, "signingRequestId");
            if (!FINALIZATION_SIGNING_REQUEST_ID.matcher(
                    signingRequestId).matches()) {
                throw new IllegalArgumentException(
                        "signingRequestId is invalid");
            }
            finalizingJob = Objects.requireNonNull(
                    finalizingJob, "finalizingJob");
            terminalJob = Objects.requireNonNull(
                    terminalJob, "terminalJob");
            request = Objects.requireNonNull(request, "request");
            manifest = Objects.requireNonNull(
                    manifest, "manifest");
            items = items == null ? List.of() : List.copyOf(items);
            retainUntil = Objects.requireNonNull(
                    retainUntil, "retainUntil");
            queuedAt = Objects.requireNonNull(
                    queuedAt, "queuedAt");
            if (finalizingJob.status()
                    != ScenarioRehearsalBatchJob.Status
                    .FINALIZING_EVIDENCE
                    || terminalJob.status()
                    .equals(ScenarioRehearsalBatchJob.Status
                            .FINALIZING_EVIDENCE)
                    || !terminalJob.status().terminal()
                    || terminalJob.completedAt() == null
                    || !queuedAt.equals(finalizingJob.updatedAt())
                    || !queuedAt.equals(terminalJob.completedAt())
                    || !finalizingJob.jobId().equals(
                    terminalJob.jobId())
                    || !finalizingJob.requestFingerprint().equals(
                    terminalJob.requestFingerprint())
                    || !finalizingJob.manifestFingerprint().equals(
                    terminalJob.manifestFingerprint())
                    || !finalizingJob.scope().equals(
                    terminalJob.scope())
                    || !finalizingJob.summary().equals(
                    terminalJob.summary())
                    || !finalizingJob.cancellationRequestId().equals(
                    terminalJob.cancellationRequestId())
                    || !finalizingJob.cancellationReasonCode().equals(
                    terminalJob.cancellationReasonCode())
                    || !retainUntil.isAfter(queuedAt)) {
                throw new IllegalArgumentException(
                        "Scenario batch finalization job closure is inconsistent");
            }
            new ScenarioRehearsalBatchEvidenceIndex(
                    "", "", request, manifest, terminalJob, items);
        }

        /** Returns identical intent carrying its canonical content address. */
        public FinalizationIntent withFingerprint(String value) {
            return new FinalizationIntent(
                    schemaVersion, value, signingRequestId,
                    finalizingJob, terminalJob, request,
                    manifest, items, retainUntil, queuedAt);
        }
    }

    /** Durable finalization control state. */
    enum FinalizationState {
        PENDING,
        SIGNING,
        RETRY_WAIT,
        QUARANTINED,
        FINALIZED
    }

    /** Integrity-verified payload-free finalization projection. */
    record FinalizationSnapshot(
            FinalizationState state,
            String jobId,
            String intentFingerprint,
            int attemptCount,
            Instant nextEligibleAt,
            String leaseOwner,
            long leaseEpoch,
            Instant leaseExpiresAt,
            Instant signingStartedAt,
            String lastFailureCode,
            String evidenceBundleFingerprint,
            Instant createdAt,
            Instant updatedAt,
            Instant finalizedAt
    ) {
        /** Validates bounded lease, retry, quarantine, and completion correspondence. */
        public FinalizationSnapshot {
            state = Objects.requireNonNull(state, "state");
            jobId = required(jobId, "jobId");
            intentFingerprint = fingerprint(
                    intentFingerprint, "intentFingerprint");
            nextEligibleAt = Objects.requireNonNull(
                    nextEligibleAt, "nextEligibleAt");
            leaseOwner = normalized(leaseOwner);
            leaseExpiresAt = Objects.requireNonNull(
                    leaseExpiresAt, "leaseExpiresAt");
            signingStartedAt = Objects.requireNonNull(
                    signingStartedAt, "signingStartedAt");
            lastFailureCode = normalized(lastFailureCode);
            evidenceBundleFingerprint = optionalFingerprint(
                    evidenceBundleFingerprint,
                    "evidenceBundleFingerprint");
            createdAt = Objects.requireNonNull(
                    createdAt, "createdAt");
            updatedAt = Objects.requireNonNull(
                    updatedAt, "updatedAt");
            if (attemptCount < 0
                    || leaseEpoch < 0
                    || state == FinalizationState.SIGNING
                    && (attemptCount < 1
                    || leaseEpoch < 1
                    || leaseOwner.isBlank()
                    || signingStartedAt.equals(Instant.EPOCH)
                    || !leaseExpiresAt.isAfter(updatedAt))
                    || state != FinalizationState.SIGNING
                    && (!leaseOwner.isBlank()
                    || !leaseExpiresAt.equals(Instant.EPOCH))
                    || state == FinalizationState.FINALIZED
                    != (finalizedAt != null
                    && !evidenceBundleFingerprint.isBlank())
                    || state != FinalizationState.FINALIZED
                    && finalizedAt != null) {
                throw new IllegalArgumentException(
                        "Scenario batch finalization snapshot is inconsistent");
            }
        }
    }

    /** Exact database lease over one immutable finalization intent. */
    record FinalizationClaim(
            FinalizationIntent intent,
            String ownerId,
            long leaseEpoch,
            Instant leaseExpiresAt,
            Instant signingStartedAt,
            int attemptCount
    ) {
        /** Validates a live positive claim fence. */
        public FinalizationClaim {
            intent = Objects.requireNonNull(intent, "intent");
            ownerId = required(ownerId, "ownerId");
            leaseExpiresAt = Objects.requireNonNull(
                    leaseExpiresAt, "leaseExpiresAt");
            signingStartedAt = Objects.requireNonNull(
                    signingStartedAt, "signingStartedAt");
            if (leaseEpoch < 1
                    || attemptCount < 1
                    || Instant.EPOCH.equals(signingStartedAt)) {
                throw new IllegalArgumentException(
                        "Scenario batch finalization claim is invalid");
            }
        }
    }

    /** Bounded claim disposition. */
    enum FinalizationClaimOutcome {
        ACQUIRED,
        NO_WORK,
        BUSY,
        RETRY_DELAYED,
        QUARANTINED
    }

    /** One finalization claim or database-authoritative wait observation. */
    record FinalizationAcquisition(
            FinalizationClaimOutcome outcome,
            Instant observedAt,
            FinalizationSnapshot snapshot,
            FinalizationClaim claim
    ) {
        /** Enforces acquired-claim correspondence. */
        public FinalizationAcquisition {
            outcome = Objects.requireNonNull(outcome, "outcome");
            observedAt = Objects.requireNonNull(
                    observedAt, "observedAt");
            boolean acquired =
                    outcome == FinalizationClaimOutcome.ACQUIRED;
            if (acquired != (snapshot != null && claim != null)
                    || outcome == FinalizationClaimOutcome.NO_WORK
                    && (snapshot != null || claim != null)) {
                throw new IllegalArgumentException(
                        "Scenario batch finalization acquisition is inconsistent");
            }
        }
    }

    /** Exact full-scope compare-and-set command for one quarantined finalization. */
    record FinalizationRemediation(
            CapabilitySnapshot.Scope scope,
            String jobId,
            ScenarioRehearsalBatchFinalizationRemediationRequest request,
            String requestFingerprint
    ) {
        /** Validates scope, stable job identity, and canonical command content address. */
        public FinalizationRemediation {
            scope = Objects.requireNonNull(scope, "scope");
            jobId = required(jobId, "jobId");
            request = Objects.requireNonNull(request, "request");
            requestFingerprint = fingerprint(
                    requestFingerprint, "requestFingerprint");
        }
    }

    /** Durable remediation disposition; exact command replay returns the original receipt. */
    record FinalizationRemediationResult(
            ScenarioRehearsalBatchFinalizationRemediationReceipt receipt,
            boolean idempotentReplay
    ) {
        /** Requires one immutable retained receipt. */
        public FinalizationRemediationResult {
            receipt = Objects.requireNonNull(receipt, "receipt");
        }
    }

    /** Submits or exactly replays one resolved batch under database capacity policy. */
    SubmissionResult submit(
            Submission submission,
            ScenarioRehearsalBatchPolicy policy);

    /**
     * Submits one batch and commits its mandatory protected-operation success audit.
     *
     * <p>Durable implementations must override this method so the success audit and admission
     * share one transaction. The default keeps test doubles and alternate adapters compatible,
     * but does not claim atomic persistence.</p>
     */
    default SubmissionResult submit(
            Submission submission,
            ScenarioRehearsalBatchPolicy policy,
            MirrorOperationObservability.Observation observation) {
        SubmissionResult result = submit(submission, policy);
        Objects.requireNonNull(observation, "observation")
                .succeeded(result.job().jobId());
        return result;
    }

    /** Claims at most one item using tenant rotation and aged priority. */
    Claim claimNext(
            String region,
            String environmentId,
            String ownerId,
            ScenarioRehearsalBatchPolicy policy);

    /**
     * Persists one liveness/progress heartbeat and observes cancellation, deadline, or lease loss.
     *
     * <p>The heartbeat does not extend the lease: claim already reserves the immutable compiled
     * plan timeout plus commit reserve, and renewal must not turn a bounded plan into unbounded
     * execution. A cancellation or deadline decision terminalizes the batch in the same database
     * transaction before it is returned.</p>
     */
    ExecutionControlCheckpoint checkpointExecution(
            Lease lease,
            int nextCaseIndex,
            ScenarioRehearsalBatchPolicy policy);

    /**
     * Checkpoints one verified item and either queues the next item or publishes a terminal job.
     */
    ScenarioRehearsalBatchJob completeItem(
            Lease lease,
            ItemCompletion completion,
            ScenarioRehearsalBatchPolicy policy);

    /**
     * Releases a retryable infrastructure failure or terminalizes the exhausted item.
     */
    ScenarioRehearsalBatchJob retryItem(
            Lease lease,
            String failureCode,
            ScenarioRehearsalBatchPolicy policy);

    /** Terminalizes one non-retryable infrastructure or governance failure immediately. */
    ScenarioRehearsalBatchJob failItem(
            Lease lease,
            String failureCode,
            ScenarioRehearsalBatchPolicy policy);

    /** Claims the oldest eligible evidence-finalization intent in one regional partition. */
    FinalizationAcquisition claimFinalization(
            String region,
            String environmentId,
            String ownerId,
            ScenarioRehearsalBatchFinalizationPolicy policy);

    /** Commits exact prepared evidence and advances the job to its frozen terminal projection. */
    ScenarioRehearsalBatchJob completeFinalization(
            FinalizationClaim claim,
            ScenarioRehearsalBatchEvidencePublisher
                    .PreparedFinalization prepared);

    /** Releases one live claim into retry backoff or durable quarantine. */
    FinalizationSnapshot releaseFinalization(
            FinalizationClaim claim,
            ScenarioRehearsalBatchFinalizationException.Reason
                    failure,
            ScenarioRehearsalBatchFinalizationPolicy policy);

    /** Reads one integrity-verified finalization projection inside exact scope. */
    Optional<FinalizationSnapshot> findFinalization(
            CapabilitySnapshot.Scope scope,
            String jobId);

    /**
     * Re-queues one exactly fenced quarantined finalization and renews its retention floor.
     *
     * <p>The intent replacement, job projection, immutable remediation receipt, lifecycle fact,
     * and protected-operation success audit must commit in one transaction. Exact command replay
     * returns the original receipt without repeating a state mutation or lifecycle fact; the
     * replaying protected API call still receives its own access audit.</p>
     */
    FinalizationRemediationResult remediateFinalization(
            FinalizationRemediation remediation,
            ScenarioRehearsalBatchPolicy policy,
            MirrorOperationObservability.Observation observation);

    /** Requests exactly replayable cooperative cancellation. */
    SubmissionResult cancel(
            Cancellation cancellation,
            ScenarioRehearsalBatchPolicy policy);

    /**
     * Applies cancellation and commits its mandatory protected-operation success audit.
     *
     * <p>Durable implementations must override this method so the success audit and cancellation
     * transition share one transaction.</p>
     */
    default SubmissionResult cancel(
            Cancellation cancellation,
            ScenarioRehearsalBatchPolicy policy,
            MirrorOperationObservability.Observation observation) {
        SubmissionResult result = cancel(cancellation, policy);
        Objects.requireNonNull(observation, "observation")
                .succeeded(result.job().jobId());
        return result;
    }

    /** Finds one integrity-verified job inside its exact scope. */
    Optional<ScenarioRehearsalBatchJob> find(
            CapabilitySnapshot.Scope scope,
            String jobId,
            ScenarioRehearsalBatchPolicy policy);

    /** Returns one stable bounded item page inside the exact scope. */
    ScenarioRehearsalBatchItemPage page(
            CapabilitySnapshot.Scope scope,
            String jobId,
            int startIndex,
            int limit,
            ScenarioRehearsalBatchPolicy policy);

    private static String required(String value, String field) {
        String normalized = normalized(value);
        if (normalized.isBlank()
                || normalized.length() > 512) {
            throw new IllegalArgumentException(
                    field + " must not be blank");
        }
        return normalized;
    }

    private static String fingerprint(
            String value, String field) {
        String normalized = normalized(value);
        if (!FINALIZATION_FINGERPRINT.matcher(
                normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be canonical SHA-256");
        }
        return normalized;
    }

    private static String optionalFingerprint(
            String value, String field) {
        String normalized = normalized(value);
        if (!normalized.isBlank()
                && !FINALIZATION_FINGERPRINT.matcher(
                normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be blank or canonical SHA-256");
        }
        return normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
