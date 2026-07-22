package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

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
     * Resolves one integrity-verified work row inside its exact caller scope.
     *
     * @param tenantId exact tenant scope
     * @param environmentId exact {@code test} or {@code staging} environment
     * @param attemptId exact physical attempt
     * @return validated work row, otherwise empty
     */
    Optional<Entry> find(String tenantId, String environmentId, String attemptId);

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
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || executionAttempts < 0 || consecutiveProofPending < 0
                    || consecutiveUnavailable < 0 || leaseEpoch < 0
                    || updatedAt.isBefore(registeredAt)
                    || ready != nextAttemptAt.isAfter(Instant.EPOCH)
                    || leased != leaseShape
                    || !leased && (!leaseOwner.isEmpty() || !leaseToken.isEmpty()
                    || !leaseClaimedAt.equals(Instant.EPOCH)
                    || !leaseUntil.equals(Instant.EPOCH))
                    || completed != projectionShape
                    || quarantined && projectionShape
                    || resultShape != (executionAttempts > 0)
                    || lastResultKind == ResultKind.NONE
                    && (lastFailureReason
                    != TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator
                    .FailureReason.NONE || !projectionId.isEmpty())
                    || completed && lastResultKind != ResultKind.PROJECTED
                    && lastResultKind != ResultKind.REPLAYED
                    || completed && lastFailureReason
                    != TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator
                    .FailureReason.NONE
                    || quarantined && lastResultKind != ResultKind.PERMANENT_CONFLICT
                    || quarantined && lastFailureReason
                    == TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator
                    .FailureReason.NONE
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
