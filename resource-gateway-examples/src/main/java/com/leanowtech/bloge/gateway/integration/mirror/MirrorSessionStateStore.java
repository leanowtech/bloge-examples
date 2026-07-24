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
     * Reads and verifies the immutable identity of the durable Session data-plane generation.
     *
     * <p>The identity must survive process and replica restarts against the same database and must
     * change when a new independent store is initialized. Encryption-key rotation must not change
     * this value because decrypt-only keys can preserve the same durable Session generation. A
     * full database clone preserves the generation and requires a separate deployment-ownership
     * fence to prevent split brain.</p>
     *
     * @return current durable store generation
     */
    MirrorSessionStoreGeneration generation();

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
     * Decrypts one active aggregate as an immutable run snapshot without acquiring or renewing a
     * writer lease.
     *
     * <p>The implementation must read the payload and descriptor from one database transaction.
     * A caller can therefore reuse the returned value throughout one DAG run and observe one
     * state revision even if another run commits later.</p>
     *
     * @param command exact enterprise namespace and session identity
     * @return payload and descriptor aligned to one durable state head
     */
    SessionSnapshot snapshot(SnapshotCommand command);

    /**
     * Reads the durable store generation and one active Session state head in the same database
     * transaction.
     *
     * <p>This is the only material admitted to checkpoint creation and recovery. Implementations
     * must not compose it from independent {@link #generation()} and {@link #snapshot} calls,
     * because a data-plane replacement between those reads would create a mixed-generation
     * recovery proof.</p>
     *
     * @param command exact enterprise namespace and Session identity
     * @return generation and immutable payload/descriptor snapshot from one transaction
     */
    CheckpointSnapshot checkpointSnapshot(SnapshotCommand command);

    /**
     * Acquires or renews a database-clock owner lease and decrypts the exact current aggregate.
     *
     * @param command exact scope, session, owner, and bounded lease duration
     * @return lease-fenced payload and current descriptor
     */
    ClaimedSession claim(ClaimCommand command);

    /**
     * Releases one exact lease generation without changing the state head.
     *
     * <p>A stale owner or superseded fence must return {@code false} and must never clear a newer
     * lease. Lease expiry remains the recovery path when release cannot reach the store.</p>
     *
     * @param lease exact internal lease generation
     * @return {@code true} only when that exact live lease was cleared
     */
    boolean release(Lease lease);

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
     * Erases one bounded oldest-first page of due active session payloads.
     *
     * <p>Implementations must coordinate competing replicas and commit each terminal descriptor,
     * ciphertext erasure, lease release, and success audit atomically.</p>
     *
     * @param limit positive bounded page size
     * @return number of sessions terminalized by this sweep
     */
    int expireDue(int limit);

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
     * Reads a payload-free database-authoritative global capacity observation.
     *
     * <p>The active count excludes expired sessions, while retained bytes continue to include
     * their payload until terminal erasure is materialized. The snapshot is suitable for health
     * and low-cardinality telemetry, not as an admission decision; implementations must make
     * admission atomically with the mutation.</p>
     *
     * @return current global usage and configured hard limits
     */
    CapacitySnapshot capacity();

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

    /** Coordinates one lease-free immutable session read. */
    record SnapshotCommand(
            CapabilitySnapshot.Scope scope,
            String sessionId
    ) {
        /** Validates exact enterprise and session coordinates. */
        public SnapshotCommand {
            scope = Objects.requireNonNull(scope, "scope");
            sessionId = required(sessionId, "sessionId");
        }
    }

    /**
     * One immutable state head supplied to a complete DAG run.
     *
     * <p>The snapshot intentionally excludes lease metadata and must not be exposed through
     * payload-free session management responses.</p>
     */
    record SessionSnapshot(
            MirrorSessionPayload payload,
            MirrorSessionDescriptor descriptor
    ) {
        /** Validates payload, scope, identity, state head, and active lifecycle alignment. */
        public SessionSnapshot {
            payload = Objects.requireNonNull(payload, "payload");
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
            SessionStateSpace state = payload.state();
            if (descriptor.status() != MirrorSessionDescriptor.Status.ACTIVE
                    || !state.scope().equals(descriptor.scope())
                    || !state.sessionId().equals(descriptor.sessionId())
                    || state.stateRevision() != descriptor.stateRevision()
                    || !state.worldFingerprint().equals(
                    descriptor.worldFingerprint())
                    || !state.fingerprint().equals(
                    descriptor.stateFingerprint())) {
                throw new IllegalArgumentException(
                        "session snapshot payload and descriptor do not align");
            }
        }
    }

    /**
     * Exact transactional material for checkpoint creation or recovery admission.
     *
     * @param generation immutable durable data-plane generation
     * @param snapshot exact active Session state head
     */
    record CheckpointSnapshot(
            MirrorSessionStoreGeneration generation,
            SessionSnapshot snapshot
    ) {
        /** Validates complete immutable checkpoint material. */
        public CheckpointSnapshot {
            generation = Objects.requireNonNull(
                    generation, "generation");
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
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

    /**
     * Payload-free global capacity observation.
     *
     * @param activeSessions active, non-expired sessions
     * @param retainedPayloadBytes canonical serialized payload bytes not yet erased
     * @param expiredRetainedPayloadBytes retained bytes belonging to expired sessions
     * @param maximumActiveSessions configured global active-session limit
     * @param maximumRetainedPayloadBytes configured global retained-byte limit
     */
    record CapacitySnapshot(
            long activeSessions,
            long retainedPayloadBytes,
            long expiredRetainedPayloadBytes,
            long maximumActiveSessions,
            long maximumRetainedPayloadBytes
    ) {
        /** Validates non-negative usage under positive configured limits. */
        public CapacitySnapshot {
            if (activeSessions < 0
                    || retainedPayloadBytes < 0
                    || expiredRetainedPayloadBytes < 0
                    || expiredRetainedPayloadBytes > retainedPayloadBytes
                    || maximumActiveSessions < 1
                    || maximumRetainedPayloadBytes < 1) {
                throw new IllegalArgumentException(
                        "mirror session capacity snapshot is invalid");
            }
        }

        /**
         * @return whether both global dimensions are below their hard limits; an actual payload
         * still requires atomic admission against its byte size and scope limits
         */
        public boolean admissionAvailable() {
            return activeSessions < maximumActiveSessions
                    && retainedPayloadBytes
                    < maximumRetainedPayloadBytes;
        }
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
