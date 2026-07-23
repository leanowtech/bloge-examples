package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable encrypted data-plane boundary for stateful mirror sessions.
 *
 * <p>Implementations must scope every lookup by the complete enterprise namespace, use a
 * database-coordinated lease fence for mutable reads, and atomically commit encrypted payload,
 * current head metadata, and the corresponding payload-free success audit. A successful
 * {@link #compareAndSet(CommitCommand)} return is the durable commit point.</p>
 */
public interface MirrorSessionStateStore {

    /**
     * Creates a session or returns the original descriptor for an exact create retry.
     *
     * @param command exact create identity and sealed initial payload
     * @return created or replayed descriptor
     */
    CreateResult create(CreateCommand command);

    /**
     * Reads a payload-free descriptor inside the exact scope.
     *
     * @param scope authenticated enterprise namespace
     * @param sessionId stable session identity
     * @return descriptor, or empty without revealing a cross-scope session
     */
    Optional<MirrorSessionDescriptor> find(
            CapabilitySnapshot.Scope scope, String sessionId);

    /**
     * Acquires or renews a database-clock owner lease and decrypts the exact current aggregate.
     *
     * @param command exact scope, session, owner, and bounded lease duration
     * @return lease-fenced payload and current descriptor
     */
    ClaimedSession claim(ClaimCommand command);

    /**
     * Atomically replaces the current encrypted state under an exact lease and state fence.
     *
     * @param command lease, expected state fingerprint, and sealed candidate aggregate
     * @return durable current descriptor
     */
    CommitResult compareAndSet(CommitCommand command);

    /**
     * Irreversibly clears the encrypted payload and marks the descriptor destroyed.
     *
     * @param scope authenticated enterprise namespace
     * @param sessionId stable session identity
     * @return first or idempotent terminal result
     */
    DestroyResult destroy(CapabilitySnapshot.Scope scope, String sessionId);

    /**
     * Reads recent payload-free operation facts for diagnostics and evidence projection.
     *
     * @param scope exact enterprise namespace
     * @param sessionId stable session identity
     * @param limit bounded result limit
     * @return newest operation facts first
     */
    List<OperationAudit> recentAudit(
            CapabilitySnapshot.Scope scope, String sessionId, int limit);

    /**
     * Probes local data-plane connectivity and encryption-key availability without decrypting a
     * customer payload.
     *
     * @return {@code true} only when new writes can be encrypted and the store is reachable
     */
    boolean ready();

    /** Create command accepted only after protocol and authenticated-scope admission. */
    record CreateCommand(
            String requestId,
            String requestFingerprint,
            MirrorSessionPayload payload
    ) {
        /** Validates exact create coordinates. */
        public CreateCommand {
            requestId = required(requestId, "requestId");
            requestFingerprint = fingerprint(
                    requestFingerprint, "requestFingerprint");
            payload = Objects.requireNonNull(payload, "payload");
        }
    }

    /** Session creation disposition. */
    enum CreateDisposition {
        CREATED,
        REPLAYED
    }

    /** Durable session creation result. */
    record CreateResult(
            CreateDisposition disposition,
            MirrorSessionDescriptor descriptor
    ) {
        /** Validates a complete result. */
        public CreateResult {
            disposition = Objects.requireNonNull(disposition, "disposition");
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
        }
    }

    /** Database-clock lease request. */
    record ClaimCommand(
            CapabilitySnapshot.Scope scope,
            String sessionId,
            String ownerId,
            long leaseDurationSeconds
    ) {
        /** Enforces bounded owner and lease coordinates. */
        public ClaimCommand {
            scope = Objects.requireNonNull(scope, "scope");
            sessionId = required(sessionId, "sessionId");
            ownerId = required(ownerId, "ownerId");
            if (ownerId.length() > 256
                    || leaseDurationSeconds < 1
                    || leaseDurationSeconds > 300) {
                throw new IllegalArgumentException(
                        "session lease owner or duration is invalid");
            }
        }
    }

    /**
     * Opaque internal lease fence.
     *
     * <p>This record must never be serialized by the public Session API.</p>
     */
    record Lease(
            CapabilitySnapshot.Scope scope,
            String sessionId,
            String ownerId,
            long fence,
            Instant expiresAt
    ) {
        /** Validates one positive lease generation. */
        public Lease {
            scope = Objects.requireNonNull(scope, "scope");
            sessionId = required(sessionId, "sessionId");
            ownerId = required(ownerId, "ownerId");
            if (fence < 1) {
                throw new IllegalArgumentException("lease fence must be positive");
            }
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    /** Lease-fenced decrypted current session. */
    record ClaimedSession(
            Lease lease,
            MirrorSessionPayload payload,
            MirrorSessionDescriptor descriptor
    ) {
        /** Validates exact state and descriptor alignment. */
        public ClaimedSession {
            lease = Objects.requireNonNull(lease, "lease");
            payload = Objects.requireNonNull(payload, "payload");
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
            if (!lease.scope().equals(descriptor.scope())
                    || !lease.sessionId().equals(descriptor.sessionId())
                    || !payload.state().fingerprint().equals(
                    descriptor.stateFingerprint())) {
                throw new IllegalArgumentException(
                        "claimed session lease, payload, and descriptor do not align");
            }
        }
    }

    /** Exact durable CAS command. */
    record CommitCommand(
            Lease lease,
            String expectedStateFingerprint,
            MirrorSessionPayload candidate
    ) {
        /** Validates an exact state fence and candidate. */
        public CommitCommand {
            lease = Objects.requireNonNull(lease, "lease");
            expectedStateFingerprint = fingerprint(
                    expectedStateFingerprint, "expectedStateFingerprint");
            candidate = Objects.requireNonNull(candidate, "candidate");
        }
    }

    /** Successful durable commit result. */
    record CommitResult(MirrorSessionDescriptor descriptor) {
        /** Validates one complete commit projection. */
        public CommitResult {
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
        }
    }

    /** Explicit destruction disposition. */
    enum DestroyDisposition {
        DESTROYED,
        ALREADY_DESTROYED,
        EXPIRED
    }

    /** Terminal destruction result. */
    record DestroyResult(
            DestroyDisposition disposition,
            MirrorSessionDescriptor descriptor
    ) {
        /** Validates one terminal result. */
        public DestroyResult {
            disposition = Objects.requireNonNull(disposition, "disposition");
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
            if (descriptor.status() == MirrorSessionDescriptor.Status.ACTIVE) {
                throw new IllegalArgumentException(
                        "destroy result requires a terminal descriptor");
            }
        }
    }

    /** Payload-free durable operation fact. */
    record OperationAudit(
            long sequence,
            Instant observedAt,
            String sessionId,
            Operation operation,
            Outcome outcome,
            String reasonCode,
            long stateRevision,
            String stateFingerprint
    ) {
        /** Validates bounded payload-free audit fields. */
        public OperationAudit {
            if (sequence < 1 || stateRevision < 0) {
                throw new IllegalArgumentException(
                        "operation audit sequence and revision are invalid");
            }
            observedAt = Objects.requireNonNull(observedAt, "observedAt");
            sessionId = required(sessionId, "sessionId");
            operation = Objects.requireNonNull(operation, "operation");
            outcome = Objects.requireNonNull(outcome, "outcome");
            reasonCode = reasonCode == null ? "" : reasonCode.trim();
            stateFingerprint = fingerprint(
                    stateFingerprint, "stateFingerprint");
        }
    }

    /** Fixed-cardinality state-store operation vocabulary. */
    enum Operation {
        CREATE,
        COMMIT,
        EXPIRE,
        DESTROY
    }

    /** Fixed-cardinality state-store outcome vocabulary. */
    enum Outcome {
        SUCCEEDED
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String fingerprint(String value, String field) {
        String normalized = required(value, field);
        if (!normalized.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    field + " must be a canonical SHA-256 value");
        }
        return normalized;
    }
}
