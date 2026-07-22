package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable authority for provider-confirmed attempt-cancellation commands and receipts.
 *
 * <p>Preparation freezes one exact command/provider binding before the external call. Acceptance
 * re-verifies the detached provider attestation against database time and advances a monotonic
 * provider sequence floor in the same transaction as the terminal journal update. Implementations
 * must preserve exact replay while rejecting command mutation, terminal rewrite, and sequence
 * rollback across replicas.</p>
 */
public interface TestSuiteStabilityAttemptCancellationJournal {

    /**
     * Freezes one cancellation command before provider invocation.
     *
     * @param command exact content-addressed command
     * @param descriptor exact provider generation selected for this command
     * @return newly prepared or exactly replayed journal entry
     */
    Preparation prepare(
            TestSuiteStabilityAttemptCancellationCommand command,
            TestSuiteStabilityAttemptCancellationAuthority.Descriptor descriptor);

    /**
     * Re-authorizes one prepared provider invocation against current database time.
     *
     * <p>Exact preparation replay intentionally remains readable after its original deadline, so
     * callers must invoke this method immediately before producing the provider side effect. An
     * implementation must require an exact {@link Status#PREPARED} entry and reject an elapsed or
     * insufficient confirmation window without mutating the retained fact.</p>
     *
     * @param commandId exact prepared cancellation command
     * @throws ConflictException when the command is absent, terminal, expired, or no longer has
     *         enough database-time window for its frozen provider descriptor
     */
    void authorizeInvocation(String commandId);

    /**
     * Verifies and durably accepts one provider response.
     *
     * @param commandId previously prepared command id
     * @param attestation untrusted detached provider response
     * @return confirmed, non-confirming, or exactly replayed journal entry
     */
    Acceptance accept(
            String commandId,
            TestSuiteStabilityAttemptCancellationReceipt.Attestation attestation);

    /**
     * Resolves one integrity-verified entry only inside its exact tenant/environment scope.
     *
     * <p>An absent command and a command owned by another scope are deliberately indistinguishable
     * to the caller.</p>
     *
     * @param tenantId exact caller tenant
     * @param environmentId exact {@code test} or {@code staging} environment
     * @param commandId content-addressed cancellation command id
     * @return the validated entry when it belongs to the exact scope, otherwise empty
     */
    Optional<Entry> find(String tenantId, String environmentId, String commandId);

    /**
     * Resolves the cancellation fact bound to one exact physical-attempt fence.
     *
     * <p>This lookup exists for recovery paths that retain the physical attempt but do not retain
     * its cancellation command id. Implementations must use an indexed exact lookup, preserve
     * tenant/environment non-disclosure, validate the complete retained entry, and report
     * ambiguity or retained-integrity failure as {@link AttemptLookupStatus#CONFLICT}. Storage
     * unavailability must still be raised as an exception; it is not proof conflict.</p>
     *
     * @param tenantId exact caller tenant
     * @param environmentId exact {@code test} or {@code staging} environment
     * @param attemptId exact content-addressed physical attempt
     * @param leaseEpoch exact durable queue ownership generation
     * @return closed found, absent, or conflicting lookup result
     */
    AttemptLookup findByAttempt(
            String tenantId, String environmentId, String attemptId, long leaseEpoch);

    /** Closed outcome for an exact physical-attempt cancellation lookup. */
    enum AttemptLookupStatus {
        /** One integrity-verified retained entry was found. */
        FOUND,
        /** No entry exists in the exact visible scope. */
        ABSENT,
        /** Retained candidates are ambiguous or fail integrity verification. */
        CONFLICT
    }

    /** Fixed-cardinality reason for an exact physical-attempt cancellation lookup. */
    enum AttemptLookupReason {
        /** No failure applies to a found entry. */
        NONE,
        /** No exact retained entry is visible. */
        NOT_RETAINED,
        /** More than one retained row claims the exact physical-attempt fence. */
        AMBIGUOUS,
        /** A retained row or its provider continuity proof failed verification. */
        INTEGRITY_CONFLICT
    }

    /**
     * Closed, payload-free result of one physical-attempt cancellation lookup.
     *
     * @param status exact lookup outcome
     * @param reason fixed-cardinality lookup reason
     * @param entry present only for one verified retained entry
     */
    record AttemptLookup(
            AttemptLookupStatus status,
            AttemptLookupReason reason,
            Optional<Entry> entry) {

        /** Enforces the found, absent, and conflict truth table. */
        public AttemptLookup {
            status = Objects.requireNonNull(status, "status");
            reason = Objects.requireNonNull(reason, "reason");
            entry = Objects.requireNonNull(entry, "entry");
            if (status == AttemptLookupStatus.FOUND
                    && (reason != AttemptLookupReason.NONE || entry.isEmpty())
                    || status == AttemptLookupStatus.ABSENT
                    && (reason != AttemptLookupReason.NOT_RETAINED || entry.isPresent())
                    || status == AttemptLookupStatus.CONFLICT
                    && (reason != AttemptLookupReason.AMBIGUOUS
                    && reason != AttemptLookupReason.INTEGRITY_CONFLICT
                    || entry.isPresent())) {
                throw new IllegalArgumentException(
                        "Invalid attempt cancellation lookup result");
            }
        }

        /**
         * Creates a successful exact-attempt lookup.
         *
         * @param entry one verified exact retained entry
         * @return found result
         */
        public static AttemptLookup found(Entry entry) {
            return new AttemptLookup(AttemptLookupStatus.FOUND, AttemptLookupReason.NONE,
                    Optional.of(Objects.requireNonNull(entry, "entry")));
        }

        /**
         * Creates an exact-scope absence result.
         *
         * @return exact-scope absence
         */
        public static AttemptLookup absent() {
            return new AttemptLookup(AttemptLookupStatus.ABSENT,
                    AttemptLookupReason.NOT_RETAINED, Optional.empty());
        }

        /**
         * Creates a permanent lookup conflict.
         *
         * @param reason ambiguity or retained-integrity conflict
         * @return permanent conflicting lookup
         */
        public static AttemptLookup conflict(AttemptLookupReason reason) {
            return new AttemptLookup(AttemptLookupStatus.CONFLICT, reason, Optional.empty());
        }
    }

    /** Durable journal lifecycle. */
    enum Status {
        /** Command is frozen but no provider response has been accepted. */
        PREPARED,
        /** A verified receipt proves the exact isolated attempt is terminal. */
        CONFIRMED,
        /** A verified `NOT_FOUND` or `REJECTED` receipt does not prove termination. */
        UNCONFIRMED
    }

    /** Preparation result vocabulary. */
    enum PreparationStatus {
        /** This transaction created the immutable command record. */
        PREPARED,
        /** The exact command/provider binding was already retained. */
        REPLAYED
    }

    /** Receipt acceptance result vocabulary. */
    enum AcceptanceStatus {
        /** A new provider-confirmed terminal receipt was committed. */
        CONFIRMED,
        /** A new signed but non-confirming receipt was committed. */
        UNCONFIRMED,
        /** The exact terminal attestation was already retained. */
        REPLAYED
    }

    /**
     * Immutable result of one prepare command.
     *
     * @param status whether this call created or exactly replayed the retained command
     * @param entry exact retained journal projection
     */
    record Preparation(PreparationStatus status, Entry entry) {
        /** Requires a prepared or already terminal exact entry. */
        public Preparation {
            status = Objects.requireNonNull(status, "status");
            entry = Objects.requireNonNull(entry, "entry");
            if (status == PreparationStatus.PREPARED && entry.status() != Status.PREPARED) {
                throw new IllegalArgumentException(
                        "New cancellation journal preparation must be PREPARED");
            }
        }
    }

    /**
     * Immutable result of one receipt acceptance.
     *
     * @param status whether this call confirmed, failed to confirm, or replayed a terminal receipt
     * @param entry exact retained terminal projection
     */
    record Acceptance(AcceptanceStatus status, Entry entry) {
        /** Requires an outcome consistent with the retained terminal entry. */
        public Acceptance {
            status = Objects.requireNonNull(status, "status");
            entry = Objects.requireNonNull(entry, "entry");
            if (entry.status() == Status.PREPARED
                    || status == AcceptanceStatus.CONFIRMED
                    && entry.status() != Status.CONFIRMED
                    || status == AcceptanceStatus.UNCONFIRMED
                    && entry.status() != Status.UNCONFIRMED) {
                throw new IllegalArgumentException(
                        "Cancellation journal acceptance is inconsistent");
            }
        }
    }

    /**
     * Complete payload-free durable journal projection.
     *
     * @param schemaVersion exact projection generation
     * @param command immutable cancellation command
     * @param descriptor frozen provider generation
     * @param status durable lifecycle
     * @param attestation absent only while prepared
     * @param preparedAt database preparation time
     * @param updatedAt database last-transition time
     * @param recordFingerprint whole-row integrity commitment
     */
    record Entry(
            String schemaVersion,
            TestSuiteStabilityAttemptCancellationCommand command,
            TestSuiteStabilityAttemptCancellationAuthority.Descriptor descriptor,
            Status status,
            Optional<TestSuiteStabilityAttemptCancellationReceipt.Attestation> attestation,
            Instant preparedAt,
            Instant updatedAt,
            String recordFingerprint) {

        /** Exact durable journal projection generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityAttemptCancellationJournalEntry.v1";

        /** Enforces the PREPARED/terminal attestation truth table. */
        public Entry {
            schemaVersion = schemaVersion == null ? "" : schemaVersion.trim();
            command = Objects.requireNonNull(command, "command");
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
            status = Objects.requireNonNull(status, "status");
            attestation = Objects.requireNonNull(attestation, "attestation");
            preparedAt = Objects.requireNonNull(preparedAt, "preparedAt");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
            recordFingerprint = recordFingerprint == null ? "" : recordFingerprint.trim();
            boolean terminal = status != Status.PREPARED;
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || terminal != attestation.isPresent()
                    || updatedAt.isBefore(preparedAt)
                    || preparedAt.getNano() % 1_000_000 != 0
                    || updatedAt.getNano() % 1_000_000 != 0
                    || !recordFingerprint.matches("sha256:[a-f0-9]{64}")) {
                throw new IllegalArgumentException(
                        "Invalid suite-stability attempt cancellation journal entry");
            }
            if (attestation.isPresent()) {
                boolean confirmed = attestation.get().receipt().terminationConfirmed();
                if (confirmed != (status == Status.CONFIRMED)) {
                    throw new IllegalArgumentException(
                            "Cancellation journal terminal status contradicts receipt");
                }
            }
        }
    }

    /** Closed conflict reasons suitable for stable application mapping. */
    enum ConflictReason {
        /** The same attempt/epoch is already bound to another command. */
        ATTEMPT_COMMAND_CONFLICT,
        /** A retained command id or terminal attestation was reused with different content. */
        IDEMPOTENCY_CONFLICT,
        /** The provider sequence did not advance its durable deployment floor. */
        PROVIDER_SEQUENCE_ROLLBACK,
        /** The command was absent or no longer eligible for provider acceptance. */
        COMMAND_NOT_PREPARED,
        /** Database time is outside the command's admissible invocation window. */
        COMMAND_EXPIRED,
        /** The frozen provider descriptor cannot satisfy this command. */
        PROVIDER_INCOMPATIBLE
    }

    /** Stable journal conflict that intentionally excludes command/provider payload. */
    final class ConflictException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        /** Closed machine-readable failure class. */
        private final ConflictReason reason;

        /**
         * Creates a payload-free journal conflict.
         *
         * @param reason exact fail-closed conflict class
         */
        public ConflictException(ConflictReason reason) {
            super("Suite-stability attempt cancellation journal conflict: "
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
}
