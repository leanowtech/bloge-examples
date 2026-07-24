package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Database-authoritative durable queue for multi-plan Scenario rehearsals.
 *
 * <p>Implementations must serialize admission, fairness cursor movement, claim, cancellation,
 * retry, and item completion per region and environment. All deadlines and lease decisions use
 * database time. One lease authorizes exactly one manifest item, so a crashed worker can replay
 * the stable child request without rerunning already checkpointed items.</p>
 */
public interface ScenarioRehearsalBatchRepository {

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
        /** Validates exact scope, job, and bounded command identity. */
        public Cancellation {
            scope = Objects.requireNonNull(scope, "scope");
            jobId = required(jobId, "jobId");
            commandId = required(commandId, "commandId");
            reasonCode = required(reasonCode, "reasonCode")
                    .toUpperCase(java.util.Locale.ROOT);
        }
    }

    /** Submits or exactly replays one resolved batch under database capacity policy. */
    SubmissionResult submit(
            Submission submission,
            ScenarioRehearsalBatchPolicy policy);

    /** Claims at most one item using tenant rotation and aged priority. */
    Claim claimNext(
            String region,
            String environmentId,
            String ownerId,
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

    /** Requests exactly replayable cooperative cancellation. */
    SubmissionResult cancel(
            Cancellation cancellation,
            ScenarioRehearsalBatchPolicy policy);

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
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be blank");
        }
        return normalized;
    }
}
