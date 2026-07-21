package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable authority for physical-attempt start commands and provider attestations.
 *
 * <p>Preparation freezes one exact command/provider binding before external I/O. Invocation
 * authorization revalidates the exact reservation and live queue lease using database time.
 * Acceptance independently verifies the detached provider attestation and advances a monotonic
 * deployment sequence in the same transaction as the terminal journal update.</p>
 *
 * <p>A local timeout or adapter failure never mutates a prepared entry. Only a verified
 * {@code STARTED} or {@code ALREADY_STARTED} receipt confirms start; a verified
 * {@code REJECTED} receipt is retained as authenticated non-confirmation.</p>
 */
public interface TestSuiteStabilityPhysicalAttemptStartJournal {

    /**
     * Freezes one exact start command before provider invocation.
     *
     * @param command content-addressed command for a reserved physical attempt
     * @param descriptor exact provider generation selected for this command
     * @return newly prepared or exactly replayed journal entry
     */
    Preparation prepare(
            TestSuiteStabilityPhysicalAttemptStartCommand command,
            TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor descriptor);

    /**
     * Re-authorizes one prepared provider invocation against database time and the live queue.
     *
     * <p>Exact preparation replay remains readable after lease loss or deadline expiry. Callers
     * must invoke this method immediately before the provider side effect. Implementations must
     * bind the retained command to an integrity-verified reservation and the exact current job
     * owner, positive lease epoch, request fingerprint, lease expiry, and job deadline.</p>
     *
     * @param commandId exact prepared start command
     * @throws ConflictException when the command is absent, terminal, expired, incompatible, or
     *         no longer owns an active reserved queue fence
     */
    void authorizeInvocation(String commandId);

    /**
     * Verifies and durably accepts one provider response.
     *
     * <p>Acceptance records a valid observed provider fact even if the queue lease changed after
     * dispatch. This prevents a real isolated runtime from becoming invisible to reconciliation.</p>
     *
     * @param commandId previously prepared command id
     * @param attestation untrusted detached provider response
     * @return confirming, non-confirming, or exactly replayed journal entry
     */
    Acceptance accept(
            String commandId,
            TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation attestation);

    /**
     * Resolves one integrity-verified entry only inside its exact tenant/environment scope.
     *
     * @param tenantId exact caller tenant
     * @param environmentId exact {@code test} or {@code staging} environment
     * @param commandId content-addressed start command id
     * @return validated entry for the exact scope, otherwise empty
     */
    Optional<Entry> find(String tenantId, String environmentId, String commandId);

    /** Durable start-journal lifecycle. */
    enum Status {
        /** Command is frozen but no provider response has been accepted. */
        PREPARED,
        /** A verified receipt proves that the exact isolated attempt started. */
        CONFIRMED,
        /** A verified rejection does not prove start or non-start. */
        UNCONFIRMED
    }

    /** Preparation result vocabulary. */
    enum PreparationStatus {
        /** This transaction created the immutable start command. */
        PREPARED,
        /** The exact command/provider binding was already retained. */
        REPLAYED
    }

    /** Receipt-acceptance result vocabulary. */
    enum AcceptanceStatus {
        /** A new provider-confirmed start receipt was committed. */
        CONFIRMED,
        /** A new signed but non-confirming rejection was committed. */
        UNCONFIRMED,
        /** The exact terminal attestation was already retained. */
        REPLAYED
    }

    /**
     * Immutable result of one prepare command.
     *
     * @param status whether this call created or replayed the retained command
     * @param entry exact retained journal projection
     */
    record Preparation(PreparationStatus status, Entry entry) {
        /** Requires a result consistent with the retained lifecycle. */
        public Preparation {
            status = Objects.requireNonNull(status, "status");
            entry = Objects.requireNonNull(entry, "entry");
            if (status == PreparationStatus.PREPARED && entry.status() != Status.PREPARED) {
                throw new IllegalArgumentException(
                        "New physical-attempt start preparation must be PREPARED");
            }
        }
    }

    /**
     * Immutable result of one receipt acceptance.
     *
     * @param status whether this call confirmed, failed to confirm, or replayed a response
     * @param entry exact retained terminal projection
     */
    record Acceptance(AcceptanceStatus status, Entry entry) {
        /** Requires an acceptance consistent with the retained terminal entry. */
        public Acceptance {
            status = Objects.requireNonNull(status, "status");
            entry = Objects.requireNonNull(entry, "entry");
            if (entry.status() == Status.PREPARED
                    || status == AcceptanceStatus.CONFIRMED
                    && entry.status() != Status.CONFIRMED
                    || status == AcceptanceStatus.UNCONFIRMED
                    && entry.status() != Status.UNCONFIRMED) {
                throw new IllegalArgumentException(
                        "Physical-attempt start acceptance is inconsistent");
            }
        }
    }

    /**
     * Complete payload-free durable start-journal projection.
     *
     * @param schemaVersion exact projection generation
     * @param command immutable physical-attempt start command
     * @param descriptor frozen provider generation
     * @param status durable lifecycle
     * @param attestation absent only while prepared
     * @param preparedAt database preparation time
     * @param updatedAt database last-transition time
     * @param recordFingerprint whole-row integrity commitment
     */
    record Entry(
            String schemaVersion,
            TestSuiteStabilityPhysicalAttemptStartCommand command,
            TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor descriptor,
            Status status,
            Optional<TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation> attestation,
            Instant preparedAt,
            Instant updatedAt,
            String recordFingerprint) {

        /** Exact durable start-journal projection generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityPhysicalAttemptStartJournalEntry.v1";

        /** Enforces the PREPARED/terminal attestation truth table. */
        public Entry {
            schemaVersion = normalized(schemaVersion);
            command = Objects.requireNonNull(command, "command");
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
            status = Objects.requireNonNull(status, "status");
            attestation = Objects.requireNonNull(attestation, "attestation");
            preparedAt = Objects.requireNonNull(preparedAt, "preparedAt");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
            recordFingerprint = normalized(recordFingerprint);
            boolean terminal = status != Status.PREPARED;
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || terminal != attestation.isPresent()
                    || updatedAt.isBefore(preparedAt)
                    || preparedAt.getNano() % 1_000_000 != 0
                    || updatedAt.getNano() % 1_000_000 != 0
                    || !recordFingerprint.matches("sha256:[a-f0-9]{64}")) {
                throw new IllegalArgumentException(
                        "Invalid physical-attempt start journal entry");
            }
            if (attestation.isPresent()) {
                boolean confirmed = attestation.get().receipt().startConfirmed();
                if (confirmed != (status == Status.CONFIRMED)) {
                    throw new IllegalArgumentException(
                            "Physical-attempt start status contradicts receipt");
                }
            }
        }
    }

    /** Closed conflict reasons suitable for stable application mapping. */
    enum ConflictReason {
        /** The same attempt and lease epoch are already bound to another start command. */
        ATTEMPT_COMMAND_CONFLICT,
        /** A retained command id or terminal attestation was reused with different content. */
        IDEMPOTENCY_CONFLICT,
        /** The provider sequence did not advance its durable deployment floor. */
        PROVIDER_SEQUENCE_ROLLBACK,
        /** The command was absent or no longer eligible for provider invocation. */
        COMMAND_NOT_PREPARED,
        /** Database time is outside the command's admissible invocation window. */
        COMMAND_EXPIRED,
        /** The provider claims start before the command was durably prepared. */
        START_PRECEDES_PREPARATION,
        /** The frozen provider cannot satisfy the command or its remaining time window. */
        PROVIDER_INCOMPATIBLE,
        /** The exact reservation or active queue lease is absent, stale, or corrupted. */
        RESERVATION_NOT_ACTIVE
    }

    /** Stable payload-free journal conflict. */
    final class ConflictException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        /** Closed machine-readable failure class. */
        private final ConflictReason reason;

        /**
         * Creates a closed start-journal conflict.
         *
         * @param reason exact fail-closed conflict class
         */
        public ConflictException(ConflictReason reason) {
            super("Suite-stability physical-attempt start journal conflict: "
                    + Objects.requireNonNull(reason, "reason"));
            this.reason = reason;
        }

        /**
         * Returns the closed failure class without exposing command or provider content.
         *
         * @return exact machine-stable conflict reason
         */
        public ConflictReason reason() {
            return reason;
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
