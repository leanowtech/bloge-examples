package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorStateBaselineResolver;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorStateException;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorStateTransactionEngine;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Authenticated application boundary for stateful mirror session lifecycle and commands.
 *
 * <p>The service binds every operation to the verified enterprise identity, serializes commands
 * per session inside one replica, claims a database-clock lease, executes the pure virtual
 * transaction kernel, and publishes the candidate through a lease-fenced state CAS. No production
 * resource, credential, operator registry, or caller-selected baseline can enter this boundary.</p>
 */
public final class MirrorSessionIntegrationService {
    private final ObjectMapper mapper;
    private final MirrorSessionStateStore store;
    private final MirrorStateBaselineResolver baselineResolver;
    private final Clock clock;
    private final String ownerId;
    private final long leaseDurationSeconds;
    private final MirrorSessionCommandAdmission commandAdmission;
    private final MirrorSessionCapacityTelemetry capacityTelemetry;
    private final ConcurrentHashMap<SessionKey, LockReference> sessionLocks =
            new ConcurrentHashMap<>();

    /**
     * Creates the protected Session application service.
     *
     * @param mapper canonical protocol mapper
     * @param store dedicated encrypted data-plane store
     * @param baselineResolver governed copy-on-write baseline authority
     * @param clock server clock for kernel expiry admission
     * @param instanceId unique replica identity; blank generates a process identity
     * @param leaseDurationSeconds bounded database lease duration
     */
    public MirrorSessionIntegrationService(
            ObjectMapper mapper,
            MirrorSessionStateStore store,
            MirrorStateBaselineResolver baselineResolver,
            Clock clock,
            String instanceId,
            long leaseDurationSeconds) {
        this(mapper, store, baselineResolver, clock, instanceId,
                leaseDurationSeconds,
                new MirrorSessionCommandAdmission(
                        64, MirrorSessionCapacityTelemetry.noop()),
                MirrorSessionCapacityTelemetry.noop());
    }

    /**
     * Creates the protected Session application service with explicit local backpressure.
     *
     * @param mapper canonical protocol mapper
     * @param store dedicated encrypted data-plane store
     * @param baselineResolver governed copy-on-write baseline authority
     * @param clock server clock for kernel expiry admission
     * @param instanceId unique replica identity; blank generates a process identity
     * @param leaseDurationSeconds bounded database lease duration
     * @param commandAdmission fair replica-local command admission boundary
     * @param capacityTelemetry fixed-cardinality capacity telemetry sink
     */
    public MirrorSessionIntegrationService(
            ObjectMapper mapper,
            MirrorSessionStateStore store,
            MirrorStateBaselineResolver baselineResolver,
            Clock clock,
            String instanceId,
            long leaseDurationSeconds,
            MirrorSessionCommandAdmission commandAdmission,
            MirrorSessionCapacityTelemetry capacityTelemetry) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.store = Objects.requireNonNull(store, "store");
        this.baselineResolver = Objects.requireNonNull(
                baselineResolver, "baselineResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
        String configured = instanceId == null ? "" : instanceId.trim();
        this.ownerId = configured.isBlank()
                ? "mirror-state-" + UUID.randomUUID() : configured;
        if (ownerId.length() > 256
                || leaseDurationSeconds < 1
                || leaseDurationSeconds > 300) {
            throw new IllegalArgumentException(
                    "mirror session instance or lease configuration is invalid");
        }
        this.leaseDurationSeconds = leaseDurationSeconds;
        this.commandAdmission = Objects.requireNonNull(
                commandAdmission, "commandAdmission");
        this.capacityTelemetry = Objects.requireNonNull(
                capacityTelemetry, "capacityTelemetry");
    }

    /**
     * Creates or exactly replays one initial session.
     *
     * @param request strict sealed session create command
     * @param identity verified enterprise identity
     * @return payload-free durable session descriptor
     */
    public MirrorSessionDescriptor create(
            MirrorSessionCreateRequest request,
            IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope scope = requireScope(identity);
        if (request == null || !scope.equals(request.payload().state().scope())) {
            throw notFound(identity);
        }
        try {
            normalizeSessionId(
                    request.payload().state().sessionId(), identity);
            MirrorSessionProtocolIntegrity.verifyInitial(
                    mapper, request.payload(), clock.instant());
            MirrorSessionStateStore.CreateResult created = store.create(
                    new MirrorSessionStateStore.CreateCommand(
                            request.requestId(),
                            MirrorSessionProtocolIntegrity.createFingerprint(
                                    mapper, request),
                            request.payload()));
            capacityTelemetry.record(
                    MirrorSessionCapacityTelemetry.Boundary.DATA_PLANE,
                    MirrorSessionCapacityTelemetry.Decision.ADMITTED);
            return created.descriptor();
        } catch (MirrorSessionStateStoreException failure) {
            throw storeProblem(identity, failure);
        } catch (IllegalArgumentException invalid) {
            throw badRequest(identity, "RG.MIRROR.SESSION.CREATE_INVALID",
                    "The stateful mirror session aggregate is invalid.");
        }
    }

    /**
     * Reads one payload-free descriptor in the exact authenticated scope.
     *
     * @param sessionId stable session identity
     * @param identity verified enterprise identity
     * @return current descriptor
     */
    public MirrorSessionDescriptor find(
            String sessionId, IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope scope = requireScope(identity);
        try {
            return store.find(scope, sessionId)
                    .orElseThrow(() -> notFound(identity));
        } catch (MirrorSessionStateStoreException failure) {
            throw storeProblem(identity, failure);
        } catch (IllegalArgumentException invalid) {
            throw badRequest(identity, "RG.MIRROR.SESSION.ID_INVALID",
                    "The mirror session id is invalid.");
        }
    }

    /**
     * Executes or exactly replays one admitted virtual write transaction.
     *
     * <p>Exact idempotency-journal replay precedes the optional expected-state fence. This lets a
     * caller safely repeat an ambiguous request after the first commit advanced the state. The
     * fence still runs before baseline resolution or mutation for every genuinely new command.</p>
     *
     * @param sessionId stable session identity
     * @param request strict effect and input command
     * @param identity verified enterprise identity
     * @return newly committed or original receipt with the current descriptor
     */
    public MirrorSessionCommandResult command(
            String sessionId,
            MirrorSessionCommandRequest request,
            IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope scope = requireScope(identity);
        if (request == null) {
            throw badRequest(identity, "RG.MIRROR.SESSION.COMMAND_INVALID",
                    "A complete stateful mirror command is required.");
        }
        SessionKey key = new SessionKey(scope, normalizeSessionId(
                sessionId, identity));
        MirrorSessionCommandAdmission.Permit permit =
                commandAdmission.tryAcquire()
                        .orElseThrow(() ->
                                localCapacityProblem(identity));
        try {
            LockReference reference = retainLock(key);
            try {
                reference.lock().lock();
                try {
                    return executeCommand(key, request, identity);
                } finally {
                    reference.lock().unlock();
                }
            } finally {
                releaseLock(key, reference);
            }
        } finally {
            permit.close();
        }
    }

    /**
     * Irreversibly destroys one session payload.
     *
     * @param sessionId stable session identity
     * @param identity verified enterprise identity
     * @return terminal payload-free descriptor
     */
    public MirrorSessionDescriptor destroy(
            String sessionId, IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope scope = requireScope(identity);
        try {
            return store.destroy(scope, sessionId).descriptor();
        } catch (MirrorSessionStateStoreException failure) {
            throw storeProblem(identity, failure);
        } catch (IllegalArgumentException invalid) {
            throw badRequest(identity, "RG.MIRROR.SESSION.ID_INVALID",
                    "The mirror session id is invalid.");
        }
    }

    private MirrorSessionCommandResult executeCommand(
            SessionKey key,
            MirrorSessionCommandRequest request,
            IntegrationRequestContext identity) {
        MirrorSessionStateStore.ClaimedSession claimed;
        try {
            claimed = store.claim(new MirrorSessionStateStore.ClaimCommand(
                    key.scope(), key.sessionId(), ownerId,
                    leaseDurationSeconds));
        } catch (MirrorSessionStateStoreException failure) {
            throw storeProblem(identity, failure);
        }
        try {
            return executeClaimedCommand(claimed, request, identity);
        } finally {
            releaseLease(claimed.lease());
        }
    }

    private MirrorSessionCommandResult executeClaimedCommand(
            MirrorSessionStateStore.ClaimedSession claimed,
            MirrorSessionCommandRequest request,
            IntegrationRequestContext identity) {
        MirrorSessionPayload payload = claimed.payload();
        WriteEffectSpec effect = payload.writeEffects().stream()
                .filter(candidate -> WriteEffectSpecIntegrity.reference(candidate)
                        .equals(request.writeEffectRef()))
                .findFirst()
                .orElseThrow(() -> new IntegrationProblemException(
                        IntegrationProblem.conflict(
                                "RG.MIRROR.SESSION.WRITE_EFFECT_NOT_ADMITTED",
                                "The requested write effect is not admitted by this session.",
                                identity.correlationId(), Map.of())));
        int receiptCount = payload.state().processedCommands().size();
        AtomicReference<MirrorSessionStateStore.CommitResult> committed =
                new AtomicReference<>();
        AtomicReference<MirrorSessionStateStoreException> storeFailure =
                new AtomicReference<>();
        MirrorStateTransactionEngine engine = new MirrorStateTransactionEngine(
                mapper, payload.stateModel(), payload.state(),
                baselineResolver, clock,
                (expected, candidate) -> {
                    try {
                        MirrorSessionPayload sealed =
                                MirrorSessionProtocolIntegrity.seal(
                                        mapper, payload.withState(candidate));
                        committed.set(store.compareAndSet(
                                new MirrorSessionStateStore.CommitCommand(
                                        claimed.lease(),
                                        expected.fingerprint(),
                                        sealed)));
                    } catch (MirrorSessionStateStoreException failure) {
                        storeFailure.set(failure);
                        throw failure;
                    }
                });
        SessionStateSpace.TransactionReceipt receipt;
        try {
            receipt = engine.execute(
                    effect,
                    request.input(),
                    current -> {
                        if (!request.expectedStateFingerprint().isBlank()
                                && !request.expectedStateFingerprint().equals(
                                current.fingerprint())) {
                            throw new MirrorSessionStateStoreException(
                                    MirrorSessionStateStoreException.Code
                                            .STATE_CONFLICT,
                                    1);
                        }
                    });
        } catch (MirrorSessionStateStoreException failure) {
            throw storeProblem(identity, failure);
        } catch (MirrorStateException failure) {
            if (storeFailure.get() != null) {
                throw storeProblem(identity, storeFailure.get());
            }
            throw stateProblem(identity, failure);
        }
        boolean replayed =
                engine.snapshot().processedCommands().size() == receiptCount;
        MirrorSessionDescriptor descriptor = replayed
                ? claimed.descriptor()
                : Objects.requireNonNull(
                        committed.get(), "new command did not commit").descriptor();
        if (!replayed) {
            capacityTelemetry.record(
                    MirrorSessionCapacityTelemetry.Boundary.DATA_PLANE,
                    MirrorSessionCapacityTelemetry.Decision.ADMITTED);
        }
        return new MirrorSessionCommandResult(
                MirrorSessionCommandResult.SCHEMA_VERSION,
                descriptor, receipt, replayed);
    }

    private void releaseLease(MirrorSessionStateStore.Lease lease) {
        try {
            store.release(lease);
        } catch (RuntimeException ignored) {
            // Bounded lease expiry remains the recovery path after a committed result or failure.
        }
    }

    private LockReference retainLock(SessionKey key) {
        return sessionLocks.compute(key, (ignored, existing) -> {
            LockReference reference = existing == null
                    ? new LockReference(new ReentrantLock(true), 0) : existing;
            return reference.retain();
        });
    }

    private void releaseLock(SessionKey key, LockReference reference) {
        sessionLocks.computeIfPresent(key, (ignored, current) -> {
            if (current.lock() != reference.lock()) {
                return current;
            }
            return current.references() == 1 ? null : current.release();
        });
    }

    private static CapabilitySnapshot.Scope requireScope(
            IntegrationRequestContext identity) {
        return MirrorPlanIntegrationService.requireMirrorIdentity(identity);
    }

    private static String normalizeSessionId(
            String sessionId, IntegrationRequestContext identity) {
        String normalized = sessionId == null ? "" : sessionId.trim();
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9@._:-]{0,511}")) {
            throw badRequest(identity, "RG.MIRROR.SESSION.ID_INVALID",
                    "The mirror session id is invalid.");
        }
        return normalized;
    }

    private IntegrationProblemException storeProblem(
            IntegrationRequestContext identity,
            MirrorSessionStateStoreException failure) {
        if (failure.code()
                == MirrorSessionStateStoreException.Code.CAPACITY_EXCEEDED) {
            capacityTelemetry.record(
                    MirrorSessionCapacityTelemetry.Boundary.DATA_PLANE,
                    MirrorSessionCapacityTelemetry.Decision.REJECTED);
        }
        String code = failure.code().wireCode();
        Map<String, Object> retry = failure.code().retryable()
                ? Map.of("retryAfterSeconds", failure.retryAfterSeconds())
                : Map.of();
        return switch (failure.code()) {
            case NOT_FOUND -> notFound(identity);
            case GONE -> new IntegrationProblemException(IntegrationProblem.gone(
                    code, "The mirror session is no longer active.",
                    identity.correlationId(), Map.of()));
            case CREATE_CONFLICT, SESSION_ID_CONFLICT ->
                    new IntegrationProblemException(IntegrationProblem.conflict(
                            code,
                            "The idempotency key or session id identifies different inputs.",
                            identity.correlationId(), Map.of()));
            case LEASE_BUSY, LEASE_LOST, STATE_CONFLICT ->
                    new IntegrationProblemException(
                            IntegrationProblem.retryableConflict(
                                    code,
                                    "The mirror session changed concurrently; retry from its current descriptor.",
                                    identity.correlationId(), retry));
            case CAPACITY_EXCEEDED ->
                    new IntegrationProblemException(
                            IntegrationProblem.tooManyRequests(
                                    code,
                                    "The mirror state data plane is at its admission limit.",
                                    identity.correlationId(), retry));
            case CORRUPT, UNAVAILABLE ->
                    new IntegrationProblemException(
                            IntegrationProblem.serviceUnavailable(
                                    code,
                                    "The mirror state data plane cannot safely serve this request.",
                                    identity.correlationId(), retry));
        };
    }

    private static IntegrationProblemException localCapacityProblem(
            IntegrationRequestContext identity) {
        MirrorSessionStateStoreException.Code capacity =
                MirrorSessionStateStoreException.Code.CAPACITY_EXCEEDED;
        return new IntegrationProblemException(
                IntegrationProblem.tooManyRequests(
                        capacity.wireCode(),
                        "The mirror session command executor is at its admission limit.",
                        identity.correlationId(),
                        Map.of("retryAfterSeconds", 1)));
    }

    private static IntegrationProblemException stateProblem(
            IntegrationRequestContext identity, MirrorStateException failure) {
        if ("RG.MIRROR.STATE.SESSION_EXPIRED".equals(failure.code())) {
            return new IntegrationProblemException(IntegrationProblem.gone(
                    failure.code(), "The mirror session has expired.",
                    identity.correlationId(), Map.of()));
        }
        if ("RG.MIRROR.STATE.COMMIT_FAILED".equals(failure.code())
                || failure.code().endsWith("_UNAVAILABLE")) {
            return new IntegrationProblemException(
                    IntegrationProblem.serviceUnavailable(
                            failure.code(),
                            "The virtual state transition could not be finalized safely.",
                            identity.correlationId(),
                            Map.of("retryAfterSeconds", 1)));
        }
        return new IntegrationProblemException(IntegrationProblem.conflict(
                failure.code(),
                "The virtual state transition was rejected.",
                identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException notFound(
            IntegrationRequestContext identity) {
        return new IntegrationProblemException(IntegrationProblem.notFound(
                MirrorSessionStateStoreException.Code.NOT_FOUND.wireCode(),
                "The mirror session was not found in the authorized scope.",
                identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException badRequest(
            IntegrationRequestContext identity, String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                code, title, identity.correlationId(), Map.of()));
    }

    private record SessionKey(
            CapabilitySnapshot.Scope scope,
            String sessionId
    ) {
    }

    private record LockReference(
            ReentrantLock lock,
            int references
    ) {
        private LockReference retain() {
            return new LockReference(lock, Math.addExact(references, 1));
        }

        private LockReference release() {
            return new LockReference(lock, references - 1);
        }
    }
}
