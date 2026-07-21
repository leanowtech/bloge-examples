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
     * Verifies and durably accepts one provider response.
     *
     * @param commandId previously prepared command id
     * @param attestation untrusted detached provider response
     * @return confirmed, non-confirming, or exactly replayed journal entry
     */
    Acceptance accept(
            String commandId,
            TestSuiteStabilityAttemptCancellationReceipt.Attestation attestation);

    /** Resolves one integrity-verified entry only inside its exact tenant/environment scope. */
    Optional<Entry> find(String tenantId, String environmentId, String commandId);

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

    /** Immutable result of one prepare command. */
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

    /** Immutable result of one receipt acceptance. */
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
        private final ConflictReason reason;

        /** @param reason exact fail-closed conflict class */
        public ConflictException(ConflictReason reason) {
            super("Suite-stability attempt cancellation journal conflict: "
                    + Objects.requireNonNull(reason, "reason"));
            this.reason = reason;
        }

        /** @return exact machine-stable conflict reason */
        public ConflictReason reason() {
            return reason;
        }
    }
}
