package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable authority for reserving physical-attempt identities under exact live queue fences.
 *
 * <p>A reservation is a dispatch precondition, not evidence that an isolated runtime started.
 * Implementations must serialize one identity per job lease epoch, validate the current queue
 * owner against authoritative time, preserve exact replay, and fail closed after cancellation,
 * deadline, lease loss, or intent mutation.</p>
 */
public interface TestSuiteStabilityPhysicalAttemptRegistry {

    /**
     * Reserves one exact physical-attempt identity before provider dispatch.
     *
     * @param identity content-addressed queue/runtime binding
     * @return newly retained or exactly replayed reservation
     */
    Reservation reserve(TestSuiteStabilityPhysicalAttemptIdentity identity);

    /**
     * Re-authorizes one retained identity immediately before the provider dispatch side effect.
     *
     * <p>Reservation replay intentionally preserves history after its queue fence stops being
     * live. Dispatchers must therefore call this operation after reservation and immediately
     * before invoking the isolated runtime provider.</p>
     *
     * @param attemptId exact retained physical-attempt identity
     * @throws ConflictException when the reservation is absent or its queue fence is no longer
     *         active
     */
    void authorizeDispatch(String attemptId);

    /**
     * Resolves one integrity-verified reservation only inside its exact caller scope.
     *
     * @param tenantId exact tenant scope
     * @param environmentId exact {@code test} or {@code staging} environment
     * @param attemptId content-addressed attempt identity
     * @return validated reservation when it belongs to the exact scope, otherwise empty
     */
    Optional<Entry> find(String tenantId, String environmentId, String attemptId);

    /** Reservation command disposition. */
    enum ReservationStatus {
        /** This transaction froze a new queue/runtime identity. */
        RESERVED,
        /** The exact identity was already retained under the same live fence. */
        REPLAYED
    }

    /**
     * Result of one serialized reservation command.
     *
     * @param status whether the call created or replayed the durable fact
     * @param entry exact retained reservation
     */
    record Reservation(ReservationStatus status, Entry entry) {
        /** Requires a complete retained result. */
        public Reservation {
            status = Objects.requireNonNull(status, "status");
            entry = Objects.requireNonNull(entry, "entry");
        }
    }

    /**
     * Complete payload-free durable reservation projection.
     *
     * @param schemaVersion exact projection generation
     * @param identity immutable physical-attempt identity
     * @param reservedAt database-authoritative reservation time
     * @param recordFingerprint whole-row integrity commitment
     */
    record Entry(
            String schemaVersion,
            TestSuiteStabilityPhysicalAttemptIdentity identity,
            Instant reservedAt,
            String recordFingerprint) {

        /** Exact reservation projection generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityPhysicalAttemptRegistryEntry.v1";

        /** Enforces an exact millisecond database fact and integrity commitment shape. */
        public Entry {
            schemaVersion = schemaVersion == null ? "" : schemaVersion.trim();
            identity = Objects.requireNonNull(identity, "identity");
            reservedAt = Objects.requireNonNull(reservedAt, "reservedAt");
            recordFingerprint = recordFingerprint == null ? "" : recordFingerprint.trim();
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || reservedAt.getNano() % 1_000_000 != 0
                    || !recordFingerprint.matches("sha256:[a-f0-9]{64}")) {
                throw new IllegalArgumentException(
                        "Invalid suite-stability physical-attempt registry entry");
            }
        }
    }

    /** Closed reservation conflicts suitable for stable metrics and application mapping. */
    enum ConflictReason {
        /** The queue job is absent, non-running, expired, cancelled, or owned by another fence. */
        LEASE_NOT_ACTIVE,
        /** The same job lease epoch already belongs to another physical-attempt identity. */
        FENCE_CONFLICT,
        /** A retained attempt id was reused with different semantic content. */
        IDEMPOTENCY_CONFLICT,
        /** The requested physical-attempt reservation does not exist. */
        ATTEMPT_NOT_RESERVED
    }

    /** Payload-free stable reservation conflict. */
    final class ConflictException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        /** Stable machine-readable conflict classification. */
        private final ConflictReason reason;

        /**
         * Creates a stable conflict without retaining job or runtime data.
         *
         * @param reason exact closed conflict class
         */
        public ConflictException(ConflictReason reason) {
            super("Suite-stability physical-attempt reservation conflict: "
                    + Objects.requireNonNull(reason, "reason"));
            this.reason = reason;
        }

        /**
         * Returns the closed failure class.
         *
         * @return exact machine-stable conflict reason
         */
        public ConflictReason reason() {
            return reason;
        }
    }
}
