package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Durable work authority separating a known physical terminal fact from queue projection.
 *
 * <p>A terminal observation and its reconciliation completion are not themselves proof that the
 * queue projection committed. The reconciliation transaction uses {@link #boundRegister(Trigger)}
 * to create an independently claimable work fact in the same local database transaction. This
 * closes the crash window in which a best-effort tail call could be lost after the observation
 * target became terminal.</p>
 *
 * <p>The journal is payload-free. It retains only immutable source references, lease fences,
 * fixed-cardinality outcomes, and projection identities. Provider I/O, proof resolution, queue
 * mutation, and slot release remain outside this registration authority.</p>
 */
public interface TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal {

    /**
     * Returns the exact lease and retry policy shared by this worker authority.
     *
     * @return immutable worker policy
     */
    Policy policy();

    /**
     * Binds one exact work registration to a future test-runtime database transaction.
     *
     * <p>The returned mutation must use only the supplied JDBC facade. Exact replay is a no-op;
     * another trigger for the same physical attempt is a permanent conflict.</p>
     *
     * @param trigger content-addressed terminal source and reconciliation completion
     * @return transaction-participating registration mutation
     */
    TestRuntimeTransactionMutation boundRegister(Trigger trigger);

    /**
     * Claims one database-clock-due item or takes over one expired lease.
     *
     * @param ownerId stable replica and worker identity, never a user credential
     * @return one fenced claim, otherwise empty
     */
    Optional<Claim> claimNext(String ownerId);

    /**
     * Completes one exact worker execution under its database lease.
     *
     * <p>Success closes the work, proof-pending and unavailable results reschedule with bounded
     * exponential delay, and a permanent conflict quarantines it. Exact response-loss replay is
     * returned without another transition.</p>
     *
     * @param lease exact database fence returned by {@link #claimNext(String)}
     * @param result payload-free coordinator result projection
     * @return persisted work transition
     */
    Completion complete(Lease lease, Result result);

    /**
     * Resolves one integrity-verified work row inside its exact caller scope.
     *
     * @param tenantId exact tenant scope
     * @param environmentId exact {@code test} or {@code staging} environment
     * @param attemptId exact physical attempt
     * @return validated work row, otherwise empty
     */
    Optional<Entry> find(String tenantId, String environmentId, String attemptId);

    /**
     * Returns aggregate database-clock backlog state without tenant or attempt identity.
     *
     * @return fixed-cardinality worker snapshot
     */
    Snapshot snapshot();

    /** Durable projection-work lifecycle. */
    enum Status {
        /** Work is eligible when its database-clock retry time arrives. */
        READY,
        /** One exact worker owns a live projection lease. */
        LEASED,
        /** A new or replayed exact terminal projection was verified. */
        COMPLETED,
        /** Automatic projection stopped after a permanent conflict. */
        QUARANTINED
    }

    /** Closed persisted worker result vocabulary. */
    enum ResultKind {
        /** No worker result has been attempted. */
        NONE,
        /** A new exact terminal projection committed. */
        PROJECTED,
        /** The exact terminal projection was already committed. */
        REPLAYED,
        /** Cancellation or parent-success proof is not authoritative yet. */
        PROOF_PENDING,
        /** A source, proof, or projection authority was temporarily unavailable. */
        UNAVAILABLE,
        /** A source, proof, or projection authority permanently rejected closure. */
        PERMANENT_CONFLICT
    }

    /** Work completion disposition returned to a caller. */
    enum CompletionStatus {
        /** A projected or replayed exact queue closure completed the work. */
        COMPLETED,
        /** Proof or infrastructure availability delayed the next attempt. */
        RESCHEDULED,
        /** A permanent source, proof, or projection conflict stopped automation. */
        QUARANTINED,
        /** The exact lease and result were already committed. */
        REPLAYED
    }

    /**
     * Database lease and retry policy shared by every worker replica.
     *
     * @param leaseDuration live claim duration from one second through ten minutes
     * @param initialProofPendingDelay first delay while an authoritative proof is pending
     * @param initialUnavailableDelay first delay after infrastructure unavailability
     * @param maximumRetryDelay exponential retry ceiling
     * @param claimInspectionLimit maximum raced candidates inspected by one claim call
     */
    record Policy(
            Duration leaseDuration,
            Duration initialProofPendingDelay,
            Duration initialUnavailableDelay,
            Duration maximumRetryDelay,
            int claimInspectionLimit) {

        /** Conservative default for registration-only composition and local examples. */
        public static final Policy DEFAULT = new Policy(
                Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofMinutes(5), 32);

        /** Enforces millisecond-exact operational bounds without silent clamping. */
        public Policy {
            leaseDuration = exactDuration(leaseDuration, "leaseDuration");
            initialProofPendingDelay = exactDuration(
                    initialProofPendingDelay, "initialProofPendingDelay");
            initialUnavailableDelay = exactDuration(
                    initialUnavailableDelay, "initialUnavailableDelay");
            maximumRetryDelay = exactDuration(maximumRetryDelay, "maximumRetryDelay");
            if (leaseDuration.compareTo(Duration.ofSeconds(1)) < 0
                    || leaseDuration.compareTo(Duration.ofMinutes(10)) > 0
                    || initialProofPendingDelay.compareTo(Duration.ofMillis(100)) < 0
                    || initialProofPendingDelay.compareTo(Duration.ofHours(1)) > 0
                    || initialUnavailableDelay.compareTo(Duration.ofMillis(100)) < 0
                    || initialUnavailableDelay.compareTo(Duration.ofHours(1)) > 0
                    || maximumRetryDelay.compareTo(initialProofPendingDelay) < 0
                    || maximumRetryDelay.compareTo(initialUnavailableDelay) < 0
                    || maximumRetryDelay.compareTo(Duration.ofDays(1)) > 0
                    || claimInspectionLimit < 1 || claimInspectionLimit > 1000) {
                throw new IllegalArgumentException(
                        "Invalid physical-attempt terminal projection-work policy");
            }
        }
    }

    /**
     * Opaque database lease fencing one work execution.
     *
     * @param workId exact content-addressed work identity
     * @param attemptId exact physical attempt
     * @param ownerId stable worker identity
     * @param token unpredictable non-credential token
     * @param epoch positive monotonic claim generation
     * @param claimedAt database claim time
     * @param leaseUntil exclusive database lease deadline
     * @param fenceFingerprint complete lease commitment
     */
    record Lease(
            String workId,
            String attemptId,
            String ownerId,
            String token,
            long epoch,
            Instant claimedAt,
            Instant leaseUntil,
            String fenceFingerprint) {

        /** Enforces an exact millisecond lease shape. */
        public Lease {
            workId = required(workId, "workId");
            attemptId = required(attemptId, "attemptId");
            ownerId = required(ownerId, "ownerId");
            token = required(token, "token");
            claimedAt = exactInstant(claimedAt, "claimedAt");
            leaseUntil = exactInstant(leaseUntil, "leaseUntil");
            fenceFingerprint = required(fenceFingerprint, "fenceFingerprint");
            try {
                UUID.fromString(token);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException(
                        "Invalid physical-attempt terminal projection-work lease token");
            }
            if (!workId.matches("stability-attempt-terminal-work-[a-f0-9]{64}")
                    || !attemptId.matches("stability-attempt-[a-f0-9]{64}")
                    || !Trigger.IDENTIFIER.matcher(ownerId).matches()
                    || epoch < 1 || !leaseUntil.isAfter(claimedAt)
                    || !fenceFingerprint.matches("sha256:[a-f0-9]{64}")) {
                throw new IllegalArgumentException(
                        "Invalid physical-attempt terminal projection-work lease");
            }
        }
    }

    /**
     * One claimed work item and its retry history.
     *
     * @param lease exact database fence
     * @param trigger immutable terminal source
     * @param executionAttempts previously completed worker executions
     * @param consecutiveProofPending current proof-pending streak
     * @param consecutiveUnavailable current infrastructure-unavailable streak
     * @param registeredAt original database registration time
     */
    record Claim(
            Lease lease,
            Trigger trigger,
            long executionAttempts,
            int consecutiveProofPending,
            int consecutiveUnavailable,
            Instant registeredAt) {

        /** Enforces an exact work, attempt, and non-negative counter binding. */
        public Claim {
            lease = Objects.requireNonNull(lease, "lease");
            trigger = Objects.requireNonNull(trigger, "trigger");
            registeredAt = exactInstant(registeredAt, "registeredAt");
            if (!lease.workId().equals(trigger.workId())
                    || !lease.attemptId().equals(trigger.attemptId())
                    || executionAttempts < 0 || consecutiveProofPending < 0
                    || consecutiveUnavailable < 0
                    || registeredAt.isAfter(lease.claimedAt())) {
                throw new IllegalArgumentException(
                        "Invalid physical-attempt terminal projection-work claim");
            }
        }
    }

    /**
     * Payload-free durable projection of one coordinator result.
     *
     * @param schemaVersion exact result generation
     * @param kind closed worker result
     * @param failureReason fixed-cardinality coordinator reason
     * @param proofReason proof detail only for proof pending or proof conflict
     * @param projectionConflictReason journal detail only for projection conflict
     * @param projectionId exact projection only for success
     * @param projectionRecordFingerprint exact retained projection commitment only for success
     */
    record Result(
            String schemaVersion,
            ResultKind kind,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                    failureReason,
            Optional<TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Reason>
                    proofReason,
            Optional<TestSuiteStabilityPhysicalAttemptTerminalProjectionJournal.ConflictReason>
                    projectionConflictReason,
            String projectionId,
            String projectionRecordFingerprint) {

        /** Exact terminal projection-work result generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityPhysicalAttemptTerminalProjectionWorkResult.v1";
        private static final Pattern PROJECTION_ID = Pattern.compile(
                "stability-attempt-terminal-project-[a-f0-9]{64}");
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

        /** Enforces the coordinator result truth table without carrying its source payload. */
        public Result {
            schemaVersion = required(schemaVersion, "schemaVersion");
            kind = Objects.requireNonNull(kind, "kind");
            failureReason = Objects.requireNonNull(failureReason, "failureReason");
            proofReason = Objects.requireNonNull(proofReason, "proofReason");
            projectionConflictReason = Objects.requireNonNull(
                    projectionConflictReason, "projectionConflictReason");
            projectionId = normalized(projectionId);
            projectionRecordFingerprint = normalized(projectionRecordFingerprint);
            boolean success = kind == ResultKind.PROJECTED || kind == ResultKind.REPLAYED;
            boolean proofOutcome = kind == ResultKind.PROOF_PENDING
                    || failureReason
                    == TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator
                    .FailureReason.PROOF_CONFLICT;
            boolean projectionConflict = failureReason
                    == TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator
                    .FailureReason.PROJECTION_CONFLICT;
            if (!SCHEMA_VERSION.equals(schemaVersion) || kind == ResultKind.NONE
                    || success != (failureReason
                    == TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator
                    .FailureReason.NONE
                    && PROJECTION_ID.matcher(projectionId).matches()
                    && FINGERPRINT.matcher(projectionRecordFingerprint).matches())
                    || !success && (!projectionId.isEmpty()
                    || !projectionRecordFingerprint.isEmpty())
                    || success && (!proofReason.isEmpty()
                    || !projectionConflictReason.isEmpty())
                    || proofOutcome != proofReason.isPresent()
                    || projectionConflict != projectionConflictReason.isPresent()
                    || kind == ResultKind.PROOF_PENDING
                    && failureReason
                    != TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator
                    .FailureReason.PROOF_NOT_READY
                    || kind == ResultKind.UNAVAILABLE && !unavailable(failureReason)
                    || kind == ResultKind.PERMANENT_CONFLICT && !permanent(failureReason)) {
                throw new IllegalArgumentException(
                        "Invalid physical-attempt terminal projection-work result");
            }
        }

        /**
         * Projects the complete coordinator result into its durable payload-free shape.
         *
         * @param attempt exact coordinator result
         * @return validated durable worker result
         */
        public static Result from(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Attempt attempt) {
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Attempt exact =
                    Objects.requireNonNull(attempt, "attempt");
            ResultKind kind = ResultKind.valueOf(exact.stage().name());
            String projectionId = exact.projection().map(value ->
                    value.entry().command().projectionId()).orElse("");
            String projectionFingerprint = exact.projection().map(value ->
                    value.entry().recordFingerprint()).orElse("");
            return new Result(SCHEMA_VERSION, kind, exact.failureReason(), exact.proofReason(),
                    exact.projectionConflictReason(), projectionId, projectionFingerprint);
        }

        /**
         * Creates a retryable result for a bounded worker-side projection outage.
         *
         * <p>This factory is used only when the coordinator call could not produce an
         * authoritative result, for example after local timeout or saturation. It cannot create
         * a business conflict or successful projection.</p>
         *
         * @param reason exact retryable coordinator failure classification
         * @return validated payload-free unavailable result
         */
        public static Result temporarilyUnavailable(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                        reason) {
            return new Result(SCHEMA_VERSION, ResultKind.UNAVAILABLE, reason,
                    Optional.empty(), Optional.empty(), "", "");
        }

        /**
         * Reconstructs canonical result material for exact response-loss replay.
         *
         * @return immutable ordered result commitment material
         */
        public Map<String, Object> canonicalMaterial() {
            Map<String, Object> material = new LinkedHashMap<>();
            material.put("schemaVersion", schemaVersion);
            material.put("kind", kind);
            material.put("failureReason", failureReason);
            material.put("proofReason", proofReason.map(Enum::name).orElse(""));
            material.put("projectionConflictReason",
                    projectionConflictReason.map(Enum::name).orElse(""));
            material.put("projectionId", projectionId);
            material.put("projectionRecordFingerprint", projectionRecordFingerprint);
            return Map.copyOf(material);
        }

        private static boolean unavailable(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                        reason) {
            return Set.of(
                    TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                            .SOURCE_UNAVAILABLE,
                    TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                            .PROOF_RESOLUTION_UNAVAILABLE,
                    TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                            .PROJECTION_UNAVAILABLE,
                    TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                            .PROJECTION_CONTRACT_VIOLATION).contains(reason);
        }

        private static boolean permanent(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                        reason) {
            return Set.of(
                    TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                            .SOURCE_NOT_RETAINED,
                    TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                            .TERMINAL_NOT_CONFIRMED,
                    TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                            .SOURCE_CHAIN_CONFLICT,
                    TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                            .PROOF_CONFLICT,
                    TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                            .PROJECTION_CONFLICT).contains(reason);
        }
    }

    /**
     * Persisted completion projection returned under one lease.
     *
     * @param status caller-visible completion disposition
     * @param workStatus resulting durable lifecycle
     * @param executionAttempts completed worker executions after this result
     * @param consecutiveProofPending proof-pending streak after this result
     * @param consecutiveUnavailable unavailable streak after this result
     * @param nextAttemptAt next database-clock eligibility only when ready
     * @param result exact payload-free result
     * @param completedAt database transition time
     */
    record Completion(
            CompletionStatus status,
            Status workStatus,
            long executionAttempts,
            int consecutiveProofPending,
            int consecutiveUnavailable,
            Optional<Instant> nextAttemptAt,
            Result result,
            Instant completedAt) {

        /** Enforces completion, lifecycle, result, and retry-time consistency. */
        public Completion {
            status = Objects.requireNonNull(status, "status");
            workStatus = Objects.requireNonNull(workStatus, "workStatus");
            nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt")
                    .map(value -> exactInstant(value, "nextAttemptAt"));
            result = Objects.requireNonNull(result, "result");
            completedAt = exactInstant(completedAt, "completedAt");
            boolean success = result.kind() == ResultKind.PROJECTED
                    || result.kind() == ResultKind.REPLAYED;
            boolean retry = result.kind() == ResultKind.PROOF_PENDING
                    || result.kind() == ResultKind.UNAVAILABLE;
            if (executionAttempts < 1 || consecutiveProofPending < 0
                    || consecutiveUnavailable < 0
                    || (workStatus == Status.READY) != nextAttemptAt.isPresent()
                    || nextAttemptAt.isPresent()
                    && !nextAttemptAt.orElseThrow().isAfter(completedAt)
                    || status == CompletionStatus.COMPLETED
                    && (workStatus != Status.COMPLETED || !success)
                    || status == CompletionStatus.RESCHEDULED
                    && (workStatus != Status.READY || !retry)
                    || status == CompletionStatus.QUARANTINED
                    && (workStatus != Status.QUARANTINED
                    || result.kind() != ResultKind.PERMANENT_CONFLICT)
                    || status == CompletionStatus.REPLAYED
                    && (workStatus == Status.LEASED
                    || workStatus == Status.COMPLETED && !success
                    || workStatus == Status.READY && !retry
                    || workStatus == Status.QUARANTINED
                    && result.kind() != ResultKind.PERMANENT_CONFLICT)) {
                throw new IllegalArgumentException(
                        "Invalid physical-attempt terminal projection-work completion");
            }
        }
    }

    /**
     * Aggregate payload-free worker observation.
     *
     * @param observedAt database observation time
     * @param ready ready row count
     * @param leased leased row count
     * @param completed completed row count
     * @param quarantined quarantined row count
     * @param dueReady currently due ready count
     * @param expiredLeases currently expired lease count
     * @param oldestActionableAt oldest due or expired database time
     */
    record Snapshot(
            Instant observedAt,
            long ready,
            long leased,
            long completed,
            long quarantined,
            long dueReady,
            long expiredLeases,
            Optional<Instant> oldestActionableAt) {

        /** Enforces non-negative aggregate counts and exact database time. */
        public Snapshot {
            observedAt = exactInstant(observedAt, "observedAt");
            oldestActionableAt = Objects.requireNonNull(
                    oldestActionableAt, "oldestActionableAt")
                    .map(value -> exactInstant(value, "oldestActionableAt"));
            if (ready < 0 || leased < 0 || completed < 0 || quarantined < 0
                    || dueReady < 0 || expiredLeases < 0 || dueReady > ready
                    || expiredLeases > leased
                    || oldestActionableAt.isPresent()
                    != (dueReady + expiredLeases > 0)) {
                throw new IllegalArgumentException(
                        "Invalid physical-attempt terminal projection-work snapshot");
            }
        }
    }

    /**
     * Content-addressed work registration trigger.
     *
     * @param schemaVersion exact trigger generation
     * @param workId content-addressed work identity
     * @param triggerFingerprint canonical trigger-material commitment
     * @param tenantId exact tenant scope
     * @param environmentId exact isolated environment
     * @param attemptId exact physical attempt
     * @param observationCommandId terminal observation command retained by reconciliation
     * @param reconciliationResultFingerprint exact terminal completion commitment
     */
    record Trigger(
            String schemaVersion,
            String workId,
            String triggerFingerprint,
            String tenantId,
            String environmentId,
            String attemptId,
            String observationCommandId,
            String reconciliationResultFingerprint) {

        /** Exact terminal projection-work trigger generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityPhysicalAttemptTerminalProjectionWorkTrigger.v1";
        private static final Pattern WORK_ID = Pattern.compile(
                "stability-attempt-terminal-work-[a-f0-9]{64}");
        private static final Pattern ATTEMPT_ID = Pattern.compile(
                "stability-attempt-[a-f0-9]{64}");
        private static final Pattern OBSERVATION_COMMAND_ID = Pattern.compile(
                "stability-attempt-observe-[a-f0-9]{64}");
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
        private static final Pattern IDENTIFIER = Pattern.compile(
                "[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

        /** Enforces a scoped content-addressed terminal source reference. */
        public Trigger {
            schemaVersion = required(schemaVersion, "schemaVersion");
            workId = required(workId, "workId");
            triggerFingerprint = required(triggerFingerprint, "triggerFingerprint");
            tenantId = required(tenantId, "tenantId");
            environmentId = required(environmentId, "environmentId");
            attemptId = required(attemptId, "attemptId");
            observationCommandId = required(
                    observationCommandId, "observationCommandId");
            reconciliationResultFingerprint = required(
                    reconciliationResultFingerprint, "reconciliationResultFingerprint");
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !WORK_ID.matcher(workId).matches()
                    || !FINGERPRINT.matcher(triggerFingerprint).matches()
                    || !workId.equals("stability-attempt-terminal-work-"
                    + triggerFingerprint.substring("sha256:".length()))
                    || !IDENTIFIER.matcher(tenantId).matches()
                    || !Set.of("test", "staging").contains(environmentId)
                    || !ATTEMPT_ID.matcher(attemptId).matches()
                    || !OBSERVATION_COMMAND_ID.matcher(observationCommandId).matches()
                    || !FINGERPRINT.matcher(reconciliationResultFingerprint).matches()) {
                throw new IllegalArgumentException(
                        "Invalid physical-attempt terminal projection-work trigger");
            }
        }

        /**
         * Derives an exact trigger from one committed terminal reconciliation result.
         *
         * @param objectMapper canonical protocol mapper
         * @param tenantId exact tenant scope
         * @param environmentId exact isolated environment
         * @param attemptId exact physical attempt
         * @param observationCommandId exact terminal observation command
         * @param reconciliationResultFingerprint exact completion result commitment
         * @return immutable content-addressed work trigger
         */
        public static Trigger create(
                ObjectMapper objectMapper,
                String tenantId,
                String environmentId,
                String attemptId,
                String observationCommandId,
                String reconciliationResultFingerprint) {
            Map<String, Object> material = material(
                    tenantId, environmentId, attemptId, observationCommandId,
                    reconciliationResultFingerprint);
            String fingerprint = ProtocolFingerprint.of(
                    Objects.requireNonNull(objectMapper, "objectMapper"), material);
            return new Trigger(SCHEMA_VERSION,
                    "stability-attempt-terminal-work-"
                            + fingerprint.substring("sha256:".length()),
                    fingerprint, tenantId, environmentId, attemptId, observationCommandId,
                    reconciliationResultFingerprint);
        }

        /**
         * Reconstructs the canonical trigger material.
         *
         * @return exact source references excluding derived id and fingerprint
         */
        public Map<String, Object> canonicalMaterial() {
            return material(tenantId, environmentId, attemptId, observationCommandId,
                    reconciliationResultFingerprint);
        }

        private static Map<String, Object> material(
                String tenantId,
                String environmentId,
                String attemptId,
                String observationCommandId,
                String reconciliationResultFingerprint) {
            Map<String, Object> material = new LinkedHashMap<>();
            material.put("schemaVersion", SCHEMA_VERSION);
            material.put("tenantId", tenantId);
            material.put("environmentId", environmentId);
            material.put("attemptId", attemptId);
            material.put("observationCommandId", observationCommandId);
            material.put("reconciliationResultFingerprint",
                    reconciliationResultFingerprint);
            return Map.copyOf(material);
        }
    }

    /**
     * Complete payload-free durable projection-work row.
     *
     * @param schemaVersion exact row generation
     * @param trigger immutable work source
     * @param status durable lifecycle
     * @param nextAttemptAt database-clock eligibility, or epoch when not ready
     * @param leaseOwner worker owner only while leased
     * @param leaseToken opaque UUID token only while leased
     * @param leaseEpoch monotonic claim generation
     * @param leaseClaimedAt database claim time, or epoch when not leased
     * @param leaseUntil exclusive lease deadline, or epoch when not leased
     * @param executionAttempts completed worker executions
     * @param consecutiveProofPending current proof-pending streak
     * @param consecutiveUnavailable current infrastructure-unavailable streak
     * @param lastResultKind last persisted worker result
     * @param lastFailureReason last fixed-cardinality coordinator reason
     * @param projectionId exact committed projection only when completed
     * @param lastResultFingerprint exact leased-result commitment, or empty before execution
     * @param registeredAt database registration time
     * @param updatedAt database last-transition time
     * @param recordFingerprint whole-row integrity commitment
     */
    record Entry(
            String schemaVersion,
            Trigger trigger,
            Status status,
            Instant nextAttemptAt,
            String leaseOwner,
            String leaseToken,
            long leaseEpoch,
            Instant leaseClaimedAt,
            Instant leaseUntil,
            long executionAttempts,
            int consecutiveProofPending,
            int consecutiveUnavailable,
            ResultKind lastResultKind,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                    lastFailureReason,
            String projectionId,
            String lastResultFingerprint,
            Instant registeredAt,
            Instant updatedAt,
            String recordFingerprint) {

        /** Exact terminal projection-work row generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityPhysicalAttemptTerminalProjectionWorkEntry.v1";
        private static final Pattern PROJECTION_ID = Pattern.compile(
                "stability-attempt-terminal-project-[a-f0-9]{64}");
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

        /** Enforces the ready, leased, completed, and quarantined truth table. */
        public Entry {
            schemaVersion = required(schemaVersion, "schemaVersion");
            trigger = Objects.requireNonNull(trigger, "trigger");
            status = Objects.requireNonNull(status, "status");
            nextAttemptAt = exactInstant(nextAttemptAt, "nextAttemptAt");
            leaseOwner = normalized(leaseOwner);
            leaseToken = normalized(leaseToken);
            leaseClaimedAt = exactInstant(leaseClaimedAt, "leaseClaimedAt");
            leaseUntil = exactInstant(leaseUntil, "leaseUntil");
            lastResultKind = Objects.requireNonNull(lastResultKind, "lastResultKind");
            lastFailureReason = Objects.requireNonNull(
                    lastFailureReason, "lastFailureReason");
            projectionId = normalized(projectionId);
            lastResultFingerprint = normalized(lastResultFingerprint);
            registeredAt = exactInstant(registeredAt, "registeredAt");
            updatedAt = exactInstant(updatedAt, "updatedAt");
            recordFingerprint = required(recordFingerprint, "recordFingerprint");
            boolean leased = status == Status.LEASED;
            boolean ready = status == Status.READY;
            boolean completed = status == Status.COMPLETED;
            boolean quarantined = status == Status.QUARANTINED;
            boolean leaseShape = !leaseOwner.isEmpty() && !leaseToken.isEmpty()
                    && leaseEpoch >= 1 && leaseUntil.isAfter(leaseClaimedAt);
            boolean projectionShape = PROJECTION_ID.matcher(projectionId).matches();
            boolean resultShape = lastResultKind != ResultKind.NONE
                    && FINGERPRINT.matcher(lastResultFingerprint).matches();
            boolean successResult = lastResultKind == ResultKind.PROJECTED
                    || lastResultKind == ResultKind.REPLAYED;
            boolean retryResult = lastResultKind == ResultKind.PROOF_PENDING
                    || lastResultKind == ResultKind.UNAVAILABLE;
            boolean resultReasonShape = switch (lastResultKind) {
                case NONE, PROJECTED, REPLAYED -> lastFailureReason
                        == TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator
                        .FailureReason.NONE;
                case PROOF_PENDING -> lastFailureReason
                        == TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator
                        .FailureReason.PROOF_NOT_READY;
                case UNAVAILABLE -> Result.unavailable(lastFailureReason);
                case PERMANENT_CONFLICT -> Result.permanent(lastFailureReason);
            };
            boolean counterShape = switch (lastResultKind) {
                case PROOF_PENDING -> consecutiveProofPending > 0
                        && consecutiveUnavailable == 0;
                case UNAVAILABLE -> consecutiveProofPending == 0
                        && consecutiveUnavailable > 0;
                case NONE, PROJECTED, REPLAYED, PERMANENT_CONFLICT ->
                        consecutiveProofPending == 0 && consecutiveUnavailable == 0;
            };
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || executionAttempts < 0 || consecutiveProofPending < 0
                    || consecutiveUnavailable < 0 || leaseEpoch < 0
                    || updatedAt.isBefore(registeredAt)
                    || !leaseOwner.isEmpty()
                    && !Trigger.IDENTIFIER.matcher(leaseOwner).matches()
                    || ready != nextAttemptAt.isAfter(Instant.EPOCH)
                    || leased != leaseShape
                    || !leased && (!leaseOwner.isEmpty() || !leaseToken.isEmpty()
                    || !leaseClaimedAt.equals(Instant.EPOCH)
                    || !leaseUntil.equals(Instant.EPOCH))
                    || completed != projectionShape
                    || quarantined && projectionShape
                    || resultShape != (executionAttempts > 0)
                    || !resultReasonShape || !counterShape
                    || executionAttempts == 0 && lastResultKind != ResultKind.NONE
                    || executionAttempts > 0 && (ready || leased) && !retryResult
                    || completed && !successResult
                    || quarantined && lastResultKind != ResultKind.PERMANENT_CONFLICT
                    || !FINGERPRINT.matcher(recordFingerprint).matches()) {
                throw new IllegalArgumentException(
                        "Invalid physical-attempt terminal projection-work entry");
            }
            if (leased) {
                try {
                    UUID.fromString(leaseToken);
                } catch (IllegalArgumentException invalid) {
                    throw new IllegalArgumentException(
                            "Invalid physical-attempt terminal projection-work lease");
                }
            }
        }
    }

    /** Stable fail-closed registration and read conflicts. */
    enum ConflictReason {
        /** The same attempt is already bound to another immutable terminal source. */
        IDEMPOTENCY_CONFLICT,
        /** The worker no longer owns the exact live database lease. */
        LEASE_LOST,
        /** The same lease was already completed with another exact result. */
        RESULT_CONFLICT,
        /** A retained work trigger or row failed content-integrity validation. */
        INTEGRITY_FAILURE
    }

    /** Payload-free work-journal conflict. */
    final class ConflictException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        /** Stable machine-readable reason retained without payload details. */
        private final ConflictReason reason;

        /**
         * Creates a stable work-journal conflict.
         *
         * @param reason exact closed conflict reason
         */
        public ConflictException(ConflictReason reason) {
            super("Suite-stability physical-attempt terminal projection-work conflict: "
                    + Objects.requireNonNull(reason, "reason"));
            this.reason = reason;
        }

        /**
         * Returns the exact machine-stable conflict reason.
         *
         * @return closed conflict reason
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

    private static Duration exactDuration(Duration value, String field) {
        Duration required = Objects.requireNonNull(value, field);
        if (required.isNegative() || required.isZero()
                || required.toNanos() % 1_000_000 != 0) {
            throw new IllegalArgumentException(field + " must be positive millisecond exact");
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
