package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable authority for physical-attempt observation commands and signed lifecycle facts.
 *
 * <p>Each observation command has an immutable prepare/accept lifecycle. Independently, the
 * journal retains the latest coherent positive state for an attempt. Authenticated
 * {@code NOT_OBSERVED} or {@code INDETERMINATE} receipts are durable command results but never
 * replace a positive attempt state.</p>
 *
 * <p>Unlike start dispatch, observation remains legal after queue lease loss because its purpose
 * is to reconcile a possibly orphaned physical side effect. The original durable start command,
 * exact provider binding, database time, process fence, and attempt revision still have to pass
 * before provider I/O.</p>
 */
public interface TestSuiteStabilityPhysicalAttemptObservationJournal {

    /**
     * Freezes one exact lifecycle observation before provider invocation.
     *
     * @param command content-addressed observation of a retained start command
     * @param descriptor exact provider generation selected for this observation
     * @return newly prepared or exactly replayed command entry
     */
    Preparation prepare(
            TestSuiteStabilityPhysicalAttemptObservationCommand command,
            TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor descriptor);

    /**
     * Re-authorizes one prepared observation using database time and current state fences.
     *
     * @param commandId exact prepared observation command
     * @throws ConflictException when the command is absent, expired, incompatible, or stale
     */
    void authorizeInvocation(String commandId);

    /**
     * Verifies and atomically accepts one provider lifecycle observation.
     *
     * <p>Provider sequence advancement, immutable command acceptance, and any positive attempt
     * state transition commit in one transaction. A local timeout leaves the command prepared.</p>
     *
     * @param commandId exact prepared observation command
     * @param attestation untrusted detached provider response
     * @return positive, non-confirming, or exactly replayed acceptance
     */
    Acceptance accept(
            String commandId,
            TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation attestation);

    /**
     * Resolves one integrity-verified command entry inside its exact scope.
     *
     * @param tenantId exact caller tenant
     * @param environmentId exact {@code test} or {@code staging} environment
     * @param commandId content-addressed observation command
     * @return validated scoped entry, otherwise empty
     */
    Optional<Entry> find(String tenantId, String environmentId, String commandId);

    /**
     * Resolves the latest integrity-verified positive state for one physical attempt.
     *
     * @param tenantId exact caller tenant
     * @param environmentId exact {@code test} or {@code staging} environment
     * @param attemptId exact content-addressed physical attempt
     * @return latest positive state; empty before any positive observation
     */
    Optional<PositiveState> latestPositive(
            String tenantId, String environmentId, String attemptId);

    /** Immutable lifecycle for one observation command. */
    enum Status {
        /** Command is frozen but no provider response has been accepted. */
        PREPARED,
        /** A verified receipt carries a coherent positive attempt state. */
        POSITIVE,
        /** A verified receipt remains non-confirming and did not alter positive state. */
        NON_CONFIRMING
    }

    /** Preparation result vocabulary. */
    enum PreparationStatus {
        /** This transaction created the immutable observation command. */
        PREPARED,
        /** The exact command/provider binding was already retained. */
        REPLAYED
    }

    /** Receipt-acceptance result vocabulary. */
    enum AcceptanceStatus {
        /** A new positive lifecycle fact was accepted. */
        POSITIVE,
        /** A new authenticated non-confirming observation was accepted. */
        NON_CONFIRMING,
        /** The exact attestation was already retained. */
        REPLAYED
    }

    /**
     * Immutable result of one prepare command.
     *
     * @param status whether this call created or replayed the command
     * @param entry exact retained command projection
     */
    record Preparation(PreparationStatus status, Entry entry) {
        /** Requires a result consistent with the command lifecycle. */
        public Preparation {
            status = Objects.requireNonNull(status, "status");
            entry = Objects.requireNonNull(entry, "entry");
            if (status == PreparationStatus.PREPARED && entry.status() != Status.PREPARED) {
                throw new IllegalArgumentException(
                        "New physical-attempt observation must be PREPARED");
            }
        }
    }

    /**
     * Immutable result of one accepted provider response.
     *
     * @param status whether the response was positive, non-confirming, or replayed
     * @param entry exact retained accepted command projection
     */
    record Acceptance(AcceptanceStatus status, Entry entry) {
        /** Requires an acceptance consistent with the retained command state. */
        public Acceptance {
            status = Objects.requireNonNull(status, "status");
            entry = Objects.requireNonNull(entry, "entry");
            if (entry.status() == Status.PREPARED
                    || status == AcceptanceStatus.POSITIVE
                    && entry.status() != Status.POSITIVE
                    || status == AcceptanceStatus.NON_CONFIRMING
                    && entry.status() != Status.NON_CONFIRMING) {
                throw new IllegalArgumentException(
                        "Physical-attempt observation acceptance is inconsistent");
            }
        }
    }

    /**
     * Complete payload-free durable observation-command projection.
     *
     * @param schemaVersion exact projection generation
     * @param command immutable physical-attempt observation command
     * @param descriptor frozen provider generation
     * @param status durable command lifecycle
     * @param attestation absent only while prepared
     * @param preparedAt database preparation time
     * @param updatedAt database last-transition time
     * @param recordFingerprint whole-row integrity commitment
     */
    record Entry(
            String schemaVersion,
            TestSuiteStabilityPhysicalAttemptObservationCommand command,
            TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor descriptor,
            Status status,
            Optional<TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation>
                    attestation,
            Instant preparedAt,
            Instant updatedAt,
            String recordFingerprint) {

        /** Exact durable observation-command projection generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityPhysicalAttemptObservationJournalEntry.v1";

        /** Enforces the prepared/accepted attestation truth table. */
        public Entry {
            schemaVersion = normalized(schemaVersion);
            command = Objects.requireNonNull(command, "command");
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
            status = Objects.requireNonNull(status, "status");
            attestation = Objects.requireNonNull(attestation, "attestation");
            preparedAt = exactInstant(preparedAt, "preparedAt");
            updatedAt = exactInstant(updatedAt, "updatedAt");
            recordFingerprint = normalized(recordFingerprint);
            boolean accepted = status != Status.PREPARED;
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || accepted != attestation.isPresent()
                    || updatedAt.isBefore(preparedAt)
                    || !recordFingerprint.matches("sha256:[a-f0-9]{64}")) {
                throw new IllegalArgumentException(
                        "Invalid physical-attempt observation journal entry");
            }
            if (attestation.isPresent()) {
                boolean positive = attestation.get().receipt().state()
                        != TestSuiteStabilityPhysicalAttemptObservationReceipt.State
                        .NOT_OBSERVED
                        && attestation.get().receipt().state()
                        != TestSuiteStabilityPhysicalAttemptObservationReceipt.State
                        .INDETERMINATE;
                if (positive != (status == Status.POSITIVE)) {
                    throw new IllegalArgumentException(
                            "Physical-attempt observation status contradicts receipt");
                }
            }
        }
    }

    /**
     * Latest coherent positive lifecycle state retained independently of negative observations.
     *
     * @param schemaVersion exact projection generation
     * @param tenantId exact tenant scope
     * @param environmentId exact isolated environment
     * @param attemptId exact physical attempt
     * @param identityFingerprint reserved physical identity commitment
     * @param startCommandId exact original start command
     * @param startCommandFingerprint original start-command commitment
     * @param observationCommandId command that established this state
     * @param attestationFingerprint detached observation-attestation commitment
     * @param receipt exact verified positive lifecycle receipt
     * @param acceptedAt database acceptance time
     * @param recordFingerprint whole-row state-floor commitment
     */
    record PositiveState(
            String schemaVersion,
            String tenantId,
            String environmentId,
            String attemptId,
            String identityFingerprint,
            String startCommandId,
            String startCommandFingerprint,
            String observationCommandId,
            String attestationFingerprint,
            TestSuiteStabilityPhysicalAttemptObservationReceipt receipt,
            Instant acceptedAt,
            String recordFingerprint) {

        /** Exact durable positive-state projection generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityPhysicalAttemptPositiveState.v1";

        /** Requires a positive, exactly bound, millisecond state projection. */
        public PositiveState {
            schemaVersion = normalized(schemaVersion);
            tenantId = required(tenantId, "tenantId");
            environmentId = normalized(environmentId);
            attemptId = required(attemptId, "attemptId");
            identityFingerprint = required(identityFingerprint, "identityFingerprint");
            startCommandId = required(startCommandId, "startCommandId");
            startCommandFingerprint = required(
                    startCommandFingerprint, "startCommandFingerprint");
            observationCommandId = required(
                    observationCommandId, "observationCommandId");
            attestationFingerprint = required(
                    attestationFingerprint, "attestationFingerprint");
            receipt = Objects.requireNonNull(receipt, "receipt");
            acceptedAt = exactInstant(acceptedAt, "acceptedAt");
            recordFingerprint = required(recordFingerprint, "recordFingerprint");
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !java.util.Set.of("test", "staging").contains(environmentId)
                    || !attemptId.matches("stability-attempt-[a-f0-9]{64}")
                    || !identityFingerprint.matches("sha256:[a-f0-9]{64}")
                    || !startCommandId.matches("stability-attempt-start-[a-f0-9]{64}")
                    || !startCommandFingerprint.matches("sha256:[a-f0-9]{64}")
                    || !observationCommandId.matches(
                    "stability-attempt-observe-[a-f0-9]{64}")
                    || !attestationFingerprint.matches("sha256:[a-f0-9]{64}")
                    || !recordFingerprint.matches("sha256:[a-f0-9]{64}")
                    || !receipt.attemptId().equals(attemptId)
                    || !receipt.identityFingerprint().equals(identityFingerprint)
                    || !receipt.startCommandId().equals(startCommandId)
                    || !receipt.startCommandFingerprint().equals(
                    startCommandFingerprint)
                    || !receipt.commandId().equals(observationCommandId)
                    || receipt.state()
                    == TestSuiteStabilityPhysicalAttemptObservationReceipt.State.NOT_OBSERVED
                    || receipt.state()
                    == TestSuiteStabilityPhysicalAttemptObservationReceipt.State.INDETERMINATE) {
                throw new IllegalArgumentException(
                        "Invalid physical-attempt positive-state projection");
            }
        }
    }

    /** Closed conflict reasons suitable for stable application mapping. */
    enum ConflictReason {
        /** A retained command id or attestation was reused with different content. */
        IDEMPOTENCY_CONFLICT,
        /** Another unexpired observation for the same attempt is already prepared. */
        OBSERVATION_IN_FLIGHT,
        /** The command is absent or no longer eligible for provider invocation. */
        COMMAND_NOT_PREPARED,
        /** Database time lies outside the observation command window. */
        COMMAND_EXPIRED,
        /** The frozen provider cannot satisfy the observation or remaining time window. */
        PROVIDER_INCOMPATIBLE,
        /** The exact original start command is absent, corrupted, or differently scoped. */
        START_COMMAND_NOT_RETAINED,
        /** The command no longer binds the latest positive process and revision floor. */
        STATE_FENCE_CHANGED,
        /** The observation predates its durable preparation. */
        OBSERVATION_PRECEDES_PREPARATION,
        /** Provider observation sequence did not advance its durable deployment floor. */
        PROVIDER_SEQUENCE_ROLLBACK,
        /** A positive receipt regressed the accepted per-attempt revision. */
        ATTEMPT_REVISION_ROLLBACK,
        /** A positive receipt names a different already-confirmed process. */
        PROCESS_IDENTITY_CONFLICT,
        /** A positive receipt regressed the accepted lifecycle state. */
        LIFECYCLE_STATE_ROLLBACK,
        /** A retained terminal fact was rewritten. */
        TERMINAL_STATE_CONFLICT
    }

    /** Stable payload-free observation-journal conflict. */
    final class ConflictException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        /** Closed machine-readable failure class. */
        private final ConflictReason reason;

        /**
         * Creates a closed observation-journal conflict.
         *
         * @param reason exact fail-closed conflict class
         */
        public ConflictException(ConflictReason reason) {
            super("Suite-stability physical-attempt observation journal conflict: "
                    + Objects.requireNonNull(reason, "reason"));
            this.reason = reason;
        }

        /**
         * Returns the closed failure class without payload or provider diagnostics.
         *
         * @return exact machine-stable conflict reason
         */
        public ConflictReason reason() {
            return reason;
        }
    }

    private static String required(String value, String field) {
        String normalized = normalized(value);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static Instant exactInstant(Instant value, String field) {
        Instant required = Objects.requireNonNull(value, field);
        if (required.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException(field + " must be millisecond exact");
        }
        return required;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
