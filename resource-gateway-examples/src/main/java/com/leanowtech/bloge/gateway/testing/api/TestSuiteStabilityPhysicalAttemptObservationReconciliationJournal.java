package com.leanowtech.bloge.gateway.testing.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Database-clock work authority for physical-attempt lifecycle reconciliation.
 *
 * <p>The start journal is the immutable source of reconciliation targets. Implementations discover
 * that source in bounded pages, then use a database lease to hand at most one target to a caller.
 * Provider I/O happens outside the claim transaction. Completion is fenced by the exact owner,
 * opaque token, lease epoch, and lease deadline.</p>
 *
 * <p>A local timeout or an authenticated non-confirming observation consumes an uncertainty
 * budget but never proves that the physical attempt did not start. Process-local saturation and
 * resolver outages use a separate backpressure result that does not consume this business
 * uncertainty budget. A hard reconciliation horizon eventually quarantines unresolved work for
 * operator review without projecting queue, slot, cancellation, or natural-terminal state.</p>
 */
public interface TestSuiteStabilityPhysicalAttemptObservationReconciliationJournal {

    /**
     * Returns the immutable lifecycle policy used by every replica of this journal.
     *
     * @return exact bounded policy
     */
    Policy policy();

    /**
     * Discovers a bounded source page and claims one due target using database time.
     *
     * @param ownerId stable replica/worker identity, never a user credential
     * @return one fenced target or empty when no due work is available
     */
    Optional<Claim> claimNext(String ownerId);

    /**
     * Atomically releases, terminalizes, or quarantines one exact leased target.
     *
     * <p>Exact response-loss replay returns {@link CompletionStatus#REPLAYED}. A different result
     * for the same lease is rejected. Results accepted by the observation journal remain durable
     * even if this completion loses its lease; a later claimant must inspect the positive state
     * floor before making another provider call.</p>
     *
     * @param lease exact database fence returned by {@link #claimNext(String)}
     * @param result closed payload-free reconciliation outcome
     * @return persisted target transition
     */
    Completion complete(Lease lease, Result result);

    /**
     * Returns aggregate database-clock backlog state without target, tenant, or provider identity.
     *
     * @return fixed-cardinality reconciliation observation
     */
    Snapshot snapshot();

    /** Durable reconciliation target lifecycle. */
    enum TargetStatus {
        /** Target is eligible once its database-clock retry time arrives. */
        READY,
        /** One exact worker owns a live reconciliation lease. */
        LEASED,
        /** A verified positive observation established provider terminal state. */
        TERMINAL,
        /** Automatic reconciliation stopped without inventing a remote lifecycle fact. */
        QUARANTINED
    }

    /** Closed result classes that never carry adapter diagnostics or business payloads. */
    enum ResultKind {
        /** A verified START_PENDING or RUNNING fact requires a later steady-state observation. */
        POSITIVE_ACTIVE,
        /** A verified TERMINAL fact closes only this reconciliation target. */
        POSITIVE_TERMINAL,
        /** A previously retained TERMINAL floor closes a recovered lease without provider I/O. */
        RETAINED_TERMINAL,
        /** A signed NOT_OBSERVED or INDETERMINATE result remains uncertain. */
        NON_CONFIRMING,
        /** Timeout or provider failure left the remote result unknown. */
        REMOTE_UNCERTAIN,
        /** Local capacity or resolver availability delayed work without provider evidence. */
        LOCAL_BACKPRESSURE,
        /** Integrity, binding, or policy failure requires explicit operator repair. */
        PERMANENT_FAILURE
    }

    /** Completion disposition returned to the reconciler. */
    enum CompletionStatus {
        /** Target returned to the durable queue with a database-clock retry time. */
        RESCHEDULED,
        /** Target closed after a verified provider terminal observation. */
        TERMINAL,
        /** Target exhausted policy or encountered a permanent fail-closed condition. */
        QUARANTINED,
        /** The exact lease/result completion was already committed. */
        REPLAYED
    }

    /**
     * Bounded reconciliation policy owned by the composition root.
     *
     * @param leaseDuration database lease from one second through ten minutes
     * @param activePollDelay delay after a verified non-terminal positive state
     * @param initialRetryDelay first uncertainty/backpressure delay
     * @param maximumRetryDelay exponential-backoff ceiling
     * @param maximumConsecutiveUncertainty uncertainty budget from 1 through 100
     * @param maximumHorizon total automatic reconciliation horizon from one minute through 30 days
     * @param discoveryPageSize missing-target discovery bound from 1 through 1000
     */
    record Policy(
            Duration leaseDuration,
            Duration activePollDelay,
            Duration initialRetryDelay,
            Duration maximumRetryDelay,
            int maximumConsecutiveUncertainty,
            Duration maximumHorizon,
            int discoveryPageSize) {

        /** Enforces millisecond-exact, non-clamped operational bounds. */
        public Policy {
            leaseDuration = exact(leaseDuration, "leaseDuration");
            activePollDelay = exact(activePollDelay, "activePollDelay");
            initialRetryDelay = exact(initialRetryDelay, "initialRetryDelay");
            maximumRetryDelay = exact(maximumRetryDelay, "maximumRetryDelay");
            maximumHorizon = exact(maximumHorizon, "maximumHorizon");
            if (leaseDuration.compareTo(Duration.ofSeconds(1)) < 0
                    || leaseDuration.compareTo(Duration.ofMinutes(10)) > 0
                    || activePollDelay.compareTo(Duration.ofMillis(100)) < 0
                    || activePollDelay.compareTo(Duration.ofHours(1)) > 0
                    || initialRetryDelay.compareTo(Duration.ofMillis(100)) < 0
                    || initialRetryDelay.compareTo(Duration.ofHours(1)) > 0
                    || maximumRetryDelay.compareTo(initialRetryDelay) < 0
                    || maximumRetryDelay.compareTo(Duration.ofDays(1)) > 0
                    || maximumConsecutiveUncertainty < 1
                    || maximumConsecutiveUncertainty > 100
                    || maximumHorizon.compareTo(Duration.ofMinutes(1)) < 0
                    || maximumHorizon.compareTo(Duration.ofDays(30)) > 0
                    || discoveryPageSize < 1 || discoveryPageSize > 1000) {
                throw new IllegalArgumentException(
                        "Invalid physical-attempt reconciliation policy");
            }
        }

        private static Duration exact(Duration value, String field) {
            Duration required = Objects.requireNonNull(value, field);
            if (required.isNegative() || required.isZero()
                    || required.toNanos() % 1_000_000 != 0) {
                throw new IllegalArgumentException(field + " must be positive millisecond exact");
            }
            return required;
        }
    }

    /**
     * Opaque lease fence for one reconciliation target.
     *
     * @param attemptId content-addressed physical attempt
     * @param ownerId exact worker identity
     * @param token unpredictable non-credential lease token
     * @param epoch positive monotonic target lease generation
     * @param claimedAt database claim time
     * @param leaseUntil exclusive database lease deadline
     * @param fenceFingerprint complete claim-fence commitment
     */
    record Lease(
            String attemptId,
            String ownerId,
            String token,
            long epoch,
            Instant claimedAt,
            Instant leaseUntil,
            String fenceFingerprint) {

        /** Rejects malformed or non-millisecond lease projections. */
        public Lease {
            attemptId = required(attemptId, "attemptId");
            ownerId = required(ownerId, "ownerId");
            token = required(token, "token");
            claimedAt = exactInstant(claimedAt, "claimedAt");
            leaseUntil = exactInstant(leaseUntil, "leaseUntil");
            fenceFingerprint = required(fenceFingerprint, "fenceFingerprint");
            try {
                UUID.fromString(token);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("Invalid reconciliation lease token");
            }
            if (!attemptId.matches("stability-attempt-[a-f0-9]{64}")
                    || epoch < 1 || !leaseUntil.isAfter(claimedAt)
                    || !fenceFingerprint.matches("sha256:[a-f0-9]{64}")) {
                throw new IllegalArgumentException("Invalid reconciliation lease");
            }
        }
    }

    /**
     * Complete work projection returned under one lease.
     *
     * @param lease exact database fence
     * @param startCommand immutable source start command
     * @param automaticAttempts provider-facing reconciliation attempts already completed
     * @param consecutiveUncertainty current provider-uncertainty streak
     * @param firstPreparedAt original durable start preparation time
     */
    record Claim(
            Lease lease,
            TestSuiteStabilityPhysicalAttemptStartCommand startCommand,
            long automaticAttempts,
            int consecutiveUncertainty,
            Instant firstPreparedAt) {

        /** Enforces an exact attempt binding and non-negative counters. */
        public Claim {
            lease = Objects.requireNonNull(lease, "lease");
            startCommand = Objects.requireNonNull(startCommand, "startCommand");
            firstPreparedAt = exactInstant(firstPreparedAt, "firstPreparedAt");
            if (!lease.attemptId().equals(startCommand.identity().attemptId())
                    || automaticAttempts < 0 || consecutiveUncertainty < 0
                    || firstPreparedAt.isAfter(lease.claimedAt())) {
                throw new IllegalArgumentException("Invalid reconciliation claim");
            }
        }
    }

    /**
     * Payload-free result of one claimed reconciliation attempt.
     *
     * @param kind closed semantic result
     * @param observationCommandId exact command used for provider-facing outcomes; optional only
     *        for permanent failures that occurred before provider observation
     */
    record Result(ResultKind kind, String observationCommandId) {

        /** Enforces command evidence for every provider-facing result. */
        public Result {
            kind = Objects.requireNonNull(kind, "kind");
            observationCommandId = normalized(observationCommandId);
            boolean commandRequired = switch (kind) {
                case POSITIVE_ACTIVE, POSITIVE_TERMINAL, RETAINED_TERMINAL,
                        NON_CONFIRMING, REMOTE_UNCERTAIN -> true;
                case LOCAL_BACKPRESSURE, PERMANENT_FAILURE -> false;
            };
            if (commandRequired && observationCommandId.isEmpty()
                    || kind == ResultKind.LOCAL_BACKPRESSURE
                    && !observationCommandId.isEmpty()
                    || !observationCommandId.isEmpty()
                    && !observationCommandId.matches(
                    "stability-attempt-observe-[a-f0-9]{64}")) {
                throw new IllegalArgumentException("Invalid reconciliation result");
            }
        }
    }

    /**
     * Persisted completion projection.
     *
     * @param status exact completion disposition
     * @param targetStatus resulting durable target lifecycle
     * @param automaticAttempts provider-facing attempts after this completion
     * @param consecutiveUncertainty uncertainty streak after this completion
     * @param nextAttemptAt next database-clock eligibility, absent for terminal/quarantined work
     * @param completedAt database commit time
     */
    record Completion(
            CompletionStatus status,
            TargetStatus targetStatus,
            long automaticAttempts,
            int consecutiveUncertainty,
            Optional<Instant> nextAttemptAt,
            Instant completedAt) {

        /** Enforces completion/status timing consistency. */
        public Completion {
            status = Objects.requireNonNull(status, "status");
            targetStatus = Objects.requireNonNull(targetStatus, "targetStatus");
            nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt")
                    .map(value -> exactInstant(value, "nextAttemptAt"));
            completedAt = exactInstant(completedAt, "completedAt");
            boolean rescheduled = targetStatus == TargetStatus.READY;
            if (automaticAttempts < 0 || consecutiveUncertainty < 0
                    || rescheduled != nextAttemptAt.isPresent()
                    || status == CompletionStatus.RESCHEDULED && !rescheduled
                    || status == CompletionStatus.TERMINAL
                    && targetStatus != TargetStatus.TERMINAL
                    || status == CompletionStatus.QUARANTINED
                    && targetStatus != TargetStatus.QUARANTINED
                    || nextAttemptAt.isPresent()
                    && nextAttemptAt.orElseThrow().isBefore(completedAt)) {
                throw new IllegalArgumentException("Invalid reconciliation completion");
            }
        }
    }

    /**
     * Fixed-cardinality database observation of reconciliation work.
     *
     * @param databaseTime observation time from the database
     * @param ready ready targets, including future backoff
     * @param leased currently leased targets
     * @param terminal terminal observation targets
     * @param quarantined fail-closed targets requiring review
     * @param due ready targets currently eligible
     * @param expiredLeases leased targets eligible for takeover
     * @param undiscoveredSources retained starts not yet projected by bounded discovery
     * @param oldestDueAt oldest due/takeover time, otherwise empty
     */
    record Snapshot(
            Instant databaseTime,
            long ready,
            long leased,
            long terminal,
            long quarantined,
            long due,
            long expiredLeases,
            long undiscoveredSources,
            Optional<Instant> oldestDueAt) {

        /** Enforces non-negative aggregate counters and due-time consistency. */
        public Snapshot {
            databaseTime = exactInstant(databaseTime, "databaseTime");
            oldestDueAt = Objects.requireNonNull(oldestDueAt, "oldestDueAt")
                    .map(value -> exactInstant(value, "oldestDueAt"));
            if (ready < 0 || leased < 0 || terminal < 0 || quarantined < 0
                    || due < 0 || expiredLeases < 0 || undiscoveredSources < 0 || due > ready
                    || expiredLeases > leased
                    || oldestDueAt.isPresent() != (due + expiredLeases > 0)
                    || oldestDueAt.isPresent()
                    && oldestDueAt.orElseThrow().isAfter(databaseTime)) {
                throw new IllegalArgumentException("Invalid reconciliation snapshot");
            }
        }
    }

    /** Stable fail-closed journal conflict without target or provider identity. */
    final class ConflictException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        /** Closed reconciliation conflict class. */
        public enum Reason {
            /** Lease is stale, expired, or owned by another worker. */
            LEASE_LOST,
            /** The same lease was completed with a different semantic result. */
            RESULT_CONFLICT,
            /** Durable source or target state failed integrity validation. */
            INTEGRITY_FAILURE
        }

        /** Exact machine-stable conflict class. */
        private final Reason reason;

        /**
         * Creates a payload-free reconciliation conflict.
         *
         * @param reason exact machine-stable conflict class
         */
        public ConflictException(Reason reason) {
            super("Suite-stability physical-attempt reconciliation conflict: "
                    + Objects.requireNonNull(reason, "reason"));
            this.reason = reason;
        }

        /**
         * Returns the closed conflict class without target or provider data.
         *
         * @return exact machine-stable conflict class
         */
        public Reason reason() {
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
