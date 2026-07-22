package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Durable authority that closes a verified physical attempt and its queue slot atomically.
 *
 * <p>The implementation must revalidate every exact source reference, choose one queue winner,
 * append an immutable projection, and release or transfer capacity in one database transaction.
 * Non-confirming facts and local timeouts are never accepted as closure.</p>
 */
public interface TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal {

    /**
     * Projects one exact terminal source chain into its durable queue job.
     *
     * @param command content-addressed source chain and expected winner
     * @param policy active queue policy used for retry and retention decisions
     * @return newly committed or exactly replayed terminal projection
     */
    Projection project(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCommand command,
            TestSuiteStabilityQueuePolicy policy);

    /**
     * Resolves one integrity-verified projection inside its exact scope.
     *
     * @param tenantId exact caller tenant
     * @param environmentId exact isolated environment
     * @param projectionId exact content-addressed projection
     * @return validated projection, otherwise empty
     */
    Optional<Entry> find(String tenantId, String environmentId, String projectionId);

    /** Projection command disposition. */
    enum ProjectionStatus {
        /** This transaction committed a new physical-attempt closure. */
        PROJECTED,
        /** The exact command already committed and was returned unchanged. */
        REPLAYED
    }

    /** Queue outcome produced by one terminal closure. */
    enum QueueDecision {
        /** A retry is eligible under a new future lease epoch. */
        REQUEUED,
        /** Verified signed parent success won. */
        SUCCEEDED,
        /** Retry exhaustion or a non-retryable attempt failure won. */
        FAILED,
        /** Provider-confirmed cancellation won. */
        CANCELLED,
        /** Provider or parent deadline won. */
        EXPIRED
    }

    /**
     * Immutable result of one projection command.
     *
     * @param status whether the command committed or replayed
     * @param entry exact retained projection
     */
    record Projection(ProjectionStatus status, Entry entry) {
        /** Requires a complete retained projection. */
        public Projection {
            status = Objects.requireNonNull(status, "status");
            entry = Objects.requireNonNull(entry, "entry");
        }
    }

    /**
     * Payload-free queue result frozen by the projection transaction.
     *
     * @param jobId exact queue job
     * @param status exact resulting queue status
     * @param retryCount resulting retry count
     * @param nextEligibleAt retry eligibility or retained prior value
     * @param leaseEpoch last closed lease generation
     * @param terminalStabilityRunId signed parent run only on success
     * @param terminalEvidenceFingerprint signed parent evidence only on success
     * @param failureCode bounded stable diagnostic
     * @param jobRecordFingerprint exact resulting queue-row commitment
     */
    record QueueResult(
            String jobId,
            TestSuiteStabilityJobRecord.Status status,
            int retryCount,
            Instant nextEligibleAt,
            long leaseEpoch,
            String terminalStabilityRunId,
            String terminalEvidenceFingerprint,
            String failureCode,
            String jobRecordFingerprint) {

        /** Enforces a payload-free and unambiguous terminal-or-retry shape. */
        public QueueResult {
            jobId = required(jobId, "jobId");
            status = Objects.requireNonNull(status, "status");
            nextEligibleAt = Objects.requireNonNull(nextEligibleAt, "nextEligibleAt");
            terminalStabilityRunId = normalized(terminalStabilityRunId);
            terminalEvidenceFingerprint = normalized(terminalEvidenceFingerprint);
            failureCode = normalized(failureCode);
            jobRecordFingerprint = required(jobRecordFingerprint, "jobRecordFingerprint");
            boolean success = status == TestSuiteStabilityJobRecord.Status.SUCCEEDED;
            if (!jobId.matches("stability-job-[a-f0-9]{64}")
                    || !Set.of(TestSuiteStabilityJobRecord.Status.QUEUED,
                    TestSuiteStabilityJobRecord.Status.SUCCEEDED,
                    TestSuiteStabilityJobRecord.Status.FAILED,
                    TestSuiteStabilityJobRecord.Status.CANCELLED,
                    TestSuiteStabilityJobRecord.Status.EXPIRED).contains(status)
                    || retryCount < 0 || leaseEpoch < 1
                    || success != (!terminalStabilityRunId.isBlank()
                    && terminalEvidenceFingerprint.matches("sha256:[a-f0-9]{64}"))
                    || !success && (!terminalStabilityRunId.isEmpty()
                    || !terminalEvidenceFingerprint.isEmpty())
                    || !jobRecordFingerprint.matches("sha256:[a-f0-9]{64}")) {
                throw new IllegalArgumentException(
                        "Invalid physical-attempt terminal queue result");
            }
        }
    }

    /**
     * Immutable terminal-projection journal entry.
     *
     * @param schemaVersion exact projection generation
     * @param command immutable source-chain command
     * @param decision committed queue decision
     * @param queueResult payload-free resulting queue projection
     * @param projectedAt database commit time
     * @param recordFingerprint whole-row projection commitment
     */
    record Entry(
            String schemaVersion,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCommand command,
            QueueDecision decision,
            QueueResult queueResult,
            Instant projectedAt,
            String recordFingerprint) {

        /** Exact durable terminal-projection generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityPhysicalAttemptTerminalProjectionEntry.v1";

        /** Enforces decision and resulting queue-state consistency. */
        public Entry {
            schemaVersion = required(schemaVersion, "schemaVersion");
            command = Objects.requireNonNull(command, "command");
            decision = Objects.requireNonNull(decision, "decision");
            queueResult = Objects.requireNonNull(queueResult, "queueResult");
            projectedAt = exactInstant(projectedAt, "projectedAt");
            recordFingerprint = required(recordFingerprint, "recordFingerprint");
            TestSuiteStabilityJobRecord.Status expected = switch (decision) {
                case REQUEUED -> TestSuiteStabilityJobRecord.Status.QUEUED;
                case SUCCEEDED -> TestSuiteStabilityJobRecord.Status.SUCCEEDED;
                case FAILED -> TestSuiteStabilityJobRecord.Status.FAILED;
                case CANCELLED -> TestSuiteStabilityJobRecord.Status.CANCELLED;
                case EXPIRED -> TestSuiteStabilityJobRecord.Status.EXPIRED;
            };
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !queueResult.jobId().equals(command.jobId())
                    || queueResult.leaseEpoch() != command.leaseEpoch()
                    || queueResult.status() != expected
                    || !recordFingerprint.matches("sha256:[a-f0-9]{64}")) {
                throw new IllegalArgumentException(
                        "Invalid physical-attempt terminal projection entry");
            }
        }
    }

    /** Stable fail-closed conflict classes. */
    enum ConflictReason {
        /** The projection id or attempt closure was reused with changed material. */
        IDEMPOTENCY_CONFLICT,
        /** A required reservation, start, observation, or state floor is absent. */
        SOURCE_NOT_RETAINED,
        /** A retained source no longer matches the exact command fingerprints. */
        SOURCE_CHANGED,
        /** The latest positive state is not a provider-confirmed terminal fact. */
        TERMINAL_NOT_CONFIRMED,
        /** A cancelled terminal fact lacks an exact confirmed cancellation receipt. */
        CANCELLATION_PROOF_REQUIRED,
        /** The cancellation proof contradicts the terminal observation. */
        CANCELLATION_PROOF_CONFLICT,
        /** The queue job is absent from the exact authorized scope. */
        JOB_NOT_FOUND,
        /** The queue job no longer belongs to the projected physical lease epoch. */
        JOB_FENCE_CHANGED,
        /** The queue job already has an independently committed terminal winner. */
        JOB_ALREADY_TERMINAL,
        /** Signed parent evidence or parent stop authority rejected the winner. */
        PARENT_CONFLICT,
        /** Physical-attempt fencing or projection storage is not enabled. */
        CAPABILITY_DISABLED
    }

    /** Payload-free terminal-projection conflict. */
    final class ConflictException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        /** Stable machine-readable projection failure class. */
        private final ConflictReason reason;

        /**
         * Creates a stable conflict without source or business payload.
         *
         * @param reason exact conflict class
         */
        public ConflictException(ConflictReason reason) {
            super("Suite-stability physical-attempt terminal projection conflict: "
                    + Objects.requireNonNull(reason, "reason"));
            this.reason = reason;
        }

        /**
         * Returns the stable machine conflict class.
         *
         * @return exact fail-closed reason
         */
        public ConflictReason reason() {
            return reason;
        }
    }

    private static Instant exactInstant(Instant value, String field) {
        Instant required = Objects.requireNonNull(value, field);
        if (required.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException(field + " must be millisecond exact");
        }
        return required;
    }

    private static String required(String value, String field) {
        String normalized = normalized(value);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
