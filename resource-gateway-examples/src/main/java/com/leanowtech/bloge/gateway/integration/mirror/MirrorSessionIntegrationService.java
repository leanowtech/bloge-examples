package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorStateBaselineResolver;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorStateException;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorStateRunSession;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorStateTransactionEngine;

import java.time.Clock;
import java.time.Instant;
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
    private static final String CORRELATION_FINGERPRINT_DOMAIN =
            "resourceGateway.mirrorStateWriteAttempt.correlation.v1";
    private static final int MAXIMUM_CORRELATION_MATERIAL_BYTES =
            16 * 1_024;
    private final ObjectMapper mapper;
    private final MirrorSessionStateStore store;
    private final MirrorStateBaselineResolver baselineResolver;
    private final Clock clock;
    private final String ownerId;
    private final long leaseDurationSeconds;
    private final MirrorSessionCommandAdmission commandAdmission;
    private final MirrorSessionCapacityTelemetry capacityTelemetry;
    private final MirrorSessionCheckpointIntegrityService checkpointIntegrity;
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
                MirrorSessionCapacityTelemetry.noop(),
                new MirrorSessionCheckpointIntegrityService(
                        mapper,
                        com.leanowtech.bloge.gateway.visual.runtime
                                .VisualEvidenceSigner.unavailable(),
                        clock));
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
        this(mapper, store, baselineResolver, clock, instanceId,
                leaseDurationSeconds, commandAdmission,
                capacityTelemetry,
                new MirrorSessionCheckpointIntegrityService(
                        mapper,
                        com.leanowtech.bloge.gateway.visual.runtime
                                .VisualEvidenceSigner.unavailable(),
                        clock));
    }

    /**
     * Creates the protected Session application service with checkpoint signing and recovery
     * admission.
     *
     * @param mapper canonical protocol mapper
     * @param store dedicated encrypted data-plane store
     * @param baselineResolver governed copy-on-write baseline authority
     * @param clock server clock for kernel expiry and recovery admission
     * @param instanceId unique replica identity; blank generates a process identity
     * @param leaseDurationSeconds bounded database lease duration
     * @param commandAdmission fair replica-local command admission boundary
     * @param capacityTelemetry fixed-cardinality capacity telemetry sink
     * @param checkpointIntegrity signed checkpoint integrity boundary
     */
    public MirrorSessionIntegrationService(
            ObjectMapper mapper,
            MirrorSessionStateStore store,
            MirrorStateBaselineResolver baselineResolver,
            Clock clock,
            String instanceId,
            long leaseDurationSeconds,
            MirrorSessionCommandAdmission commandAdmission,
            MirrorSessionCapacityTelemetry capacityTelemetry,
            MirrorSessionCheckpointIntegrityService checkpointIntegrity) {
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
        this.checkpointIntegrity = Objects.requireNonNull(
                checkpointIntegrity, "checkpointIntegrity");
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
     * Reads one durable payload-free write-attempt outcome for recovery or governance evidence.
     *
     * @param sessionId exact Session identity
     * @param attemptId deterministic attempt identity
     * @param identity verified enterprise identity
     * @return canonical in-progress or terminal journal record
     */
    public MirrorStateWriteAttempt writeAttempt(
            String sessionId,
            String attemptId,
            IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope scope = requireScope(identity);
        try {
            return store.findWriteAttempt(
                    scope,
                    normalizeSessionId(sessionId, identity),
                    normalizeAttemptId(attemptId, identity))
                    .orElseThrow(() -> notFound(identity));
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (MirrorSessionStateStoreException failure) {
            throw storeProblem(identity, failure);
        } catch (IllegalArgumentException invalid) {
            throw badRequest(
                    identity,
                    "RG.MIRROR.SESSION.WRITE_ATTEMPT_ID_INVALID",
                    "The mirror Session write-attempt id is invalid.");
        }
    }

    /**
     * Creates a signed payload-free checkpoint over one transactional durable Session state head.
     *
     * <p>The encrypted business aggregate never leaves the data plane. The returned bundle pins
     * only exact dependency, revision, logical-time, and fingerprint coordinates and can be used
     * after a process or worker restart while the same durable state head remains current.</p>
     *
     * @param sessionId stable Session identity
     * @param identity verified enterprise identity
     * @return independently verified checkpoint bundle
     */
    public MirrorSessionCheckpointBundle checkpoint(
            String sessionId, IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope scope = requireScope(identity);
        try {
            String id = normalizeSessionId(sessionId, identity);
            return checkpointIntegrity.seal(
                    store.checkpointSnapshot(
                            new MirrorSessionStateStore.SnapshotCommand(
                                    scope, id)));
        } catch (MirrorSessionCheckpointException failure) {
            throw checkpointProblem(identity, failure);
        } catch (MirrorSessionStateStoreException failure) {
            throw storeProblem(identity, failure);
        } catch (IllegalArgumentException invalid) {
            throw badRequest(identity,
                    "RG.MIRROR.SESSION.CHECKPOINT_REQUEST_INVALID",
                    "The mirror Session checkpoint request is invalid.");
        }
    }

    /**
     * Admits a signed checkpoint only when the original physical data plane and exact Session head
     * still exist unchanged.
     *
     * <p>This operation supports process and worker continuation; it is not a payload restore API.
     * A changed revision, dependency, data-plane generation, destroyed Session, expired Session,
     * invalid signature, or unavailable signer is rejected before a run binding is issued.</p>
     *
     * @param sessionId exact path-selected Session identity
     * @param bundle signed payload-free checkpoint
     * @param identity verified enterprise identity
     * @return content-addressed exact run binding and current descriptor
     */
    public MirrorSessionRecoveryResult recover(
            String sessionId,
            MirrorSessionCheckpointBundle bundle,
            IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope scope = requireScope(identity);
        if (bundle == null) {
            throw badRequest(identity,
                    "RG.MIRROR.SESSION.CHECKPOINT_INVALID",
                    "A complete signed mirror Session checkpoint is required.");
        }
        try {
            String id = normalizeSessionId(sessionId, identity);
            MirrorSessionCheckpointIntegrityService.Verification verification =
                    checkpointIntegrity.verify(bundle);
            if (verification
                    == MirrorSessionCheckpointIntegrityService.Verification
                    .UNAVAILABLE) {
                throw new MirrorSessionCheckpointException(
                        MirrorSessionCheckpointException.Code
                                .SIGNER_UNAVAILABLE);
            }
            if (verification
                    != MirrorSessionCheckpointIntegrityService.Verification
                    .VERIFIED) {
                throw new MirrorSessionCheckpointException(
                        MirrorSessionCheckpointException.Code.INVALID);
            }
            MirrorSessionCheckpoint checkpoint = bundle.checkpoint();
            if (!scope.equals(checkpoint.scope())
                    || !id.equals(checkpoint.sessionId())) {
                throw notFound(identity);
            }
            MirrorSessionStateStore.CheckpointSnapshot current =
                    store.checkpointSnapshot(
                            new MirrorSessionStateStore.SnapshotCommand(
                                    scope, id));
            checkpointIntegrity.verifyCurrent(bundle, current);
            MirrorSessionDescriptor descriptor =
                    current.snapshot().descriptor();
            Instant recoveredAt = clock.instant();
            MirrorSessionRecoveryResult result =
                    new MirrorSessionRecoveryResult(
                            "", "recovery-" + UUID.randomUUID(),
                            checkpoint.checkpointId(),
                            checkpoint.fingerprint(),
                            current.generation().fingerprint(),
                            descriptor,
                            new MirrorSessionRunBinding(
                                    descriptor.sessionId(),
                                    descriptor.stateFingerprint()),
                            recoveredAt, "");
            return checkpointIntegrity.sealRecoveryResult(result);
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (MirrorSessionCheckpointException failure) {
            throw checkpointProblem(identity, failure);
        } catch (MirrorSessionStateStoreException failure) {
            throw storeProblem(identity, failure);
        } catch (IllegalArgumentException invalid) {
            throw badRequest(identity,
                    "RG.MIRROR.SESSION.CHECKPOINT_INVALID",
                    "The signed mirror Session checkpoint is invalid.");
        }
    }

    /**
     * Freezes one active session state head for a complete mirror DAG run.
     *
     * <p>The method resolves the session only inside the authenticated enterprise scope, then
     * verifies both the persisted plan generation and the caller-reviewed state head. The returned
     * aggregate is detached from later commits and should be reused by every node in that run.</p>
     *
     * @param binding payload-free session and expected-state coordinates
     * @param expectedPlanFingerprint exact plan generation being executed
     * @param identity verified enterprise identity
     * @return immutable payload and descriptor aligned to one state revision
     */
    public MirrorSessionStateStore.SessionSnapshot snapshotForRun(
            MirrorSessionRunBinding binding,
            String expectedPlanFingerprint,
            IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope scope = requireScope(identity);
        if (binding == null) {
            throw badRequest(identity,
                    "RG.MIRROR.SESSION.RUN_BINDING_INVALID",
                    "A complete mirror session run binding is required.");
        }
        try {
            String sessionId = normalizeSessionId(
                    binding.sessionId(), identity);
            String planFingerprint = canonicalFingerprint(
                    expectedPlanFingerprint, "expectedPlanFingerprint");
            MirrorSessionStateStore.SessionSnapshot snapshot =
                    store.snapshot(new MirrorSessionStateStore.SnapshotCommand(
                            scope, sessionId));
            if (!planFingerprint.equals(
                    snapshot.payload().state().planFingerprint())) {
                throw new IntegrationProblemException(
                        IntegrationProblem.conflict(
                                "RG.MIRROR.SESSION.PLAN_CONFLICT",
                                "The mirror session belongs to a different plan generation.",
                                identity.correlationId(),
                                Map.of("sessionPlanFingerprint",
                                        snapshot.payload().state()
                                                .planFingerprint())));
            }
            if (!binding.expectedStateFingerprint().equals(
                    snapshot.descriptor().stateFingerprint())) {
                throw new IntegrationProblemException(
                        IntegrationProblem.retryableConflict(
                                MirrorSessionStateStoreException.Code
                                        .STATE_CONFLICT.wireCode(),
                                "The mirror session changed after the caller reviewed it.",
                                identity.correlationId(),
                                Map.of(
                                        "currentStateFingerprint",
                                        snapshot.descriptor()
                                                .stateFingerprint(),
                                        "retryAfterSeconds", 1)));
            }
            return snapshot;
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (MirrorSessionStateStoreException failure) {
            throw storeProblem(identity, failure);
        } catch (IllegalArgumentException invalid) {
            throw badRequest(identity,
                    "RG.MIRROR.SESSION.RUN_BINDING_INVALID",
                    "The mirror session run binding is invalid.");
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
        WriteAttemptContext attemptContext =
                directAttemptContext(request, identity);
        MirrorStateRunSession.CommandResult result =
                commandResult(
                        sessionId, request,
                        attemptContext, identity);
        return new MirrorSessionCommandResult(
                MirrorSessionCommandResult.SCHEMA_VERSION,
                result.descriptor(), result.receipt(),
                result.replayed());
    }

    /**
     * Executes one graph-embedded virtual write and returns the newly visible Session aggregate.
     *
     * <p>This method is intentionally package-scoped to the authenticated mirror-run adapter. It
     * shares the same backpressure, per-session serialization, database lease, exact replay, and
     * CAS commit path as the public command API, but additionally returns the already verified
     * payload needed by downstream state resolvers in the same DAG run.</p>
     *
     * @param sessionId exact authenticated Session identity
     * @param writeEffectRef exact effect selected by the plan capability
     * @param input detached graph-node input
     * @param expectedStateFingerprint exact current run head
     * @param attemptContext exact durable graph execution coordinate
     * @param identity verified enterprise identity
     * @return durable command result and complete newly visible Session aggregate
     */
    MirrorStateRunSession.CommandResult commandForRun(
            String sessionId,
            MirrorArtifactRef writeEffectRef,
            Map<String, Object> input,
            String expectedStateFingerprint,
            MirrorStateRunSession.AttemptContext attemptContext,
            IntegrationRequestContext identity) {
        return commandResult(
                sessionId,
                new MirrorSessionCommandRequest(
                        MirrorSessionCommandRequest.SCHEMA_VERSION,
                        writeEffectRef, expectedStateFingerprint, input),
                new WriteAttemptContext(
                        attemptContext.coordinate(),
                        attemptContext.requestFingerprint()),
                identity);
    }

    /**
     * Compatibility adapter for isolated tests that do not assemble the durable attempt journal.
     */
    MirrorStateRunSession.CommandResult commandForRun(
            String sessionId,
            MirrorArtifactRef writeEffectRef,
            Map<String, Object> input,
            String expectedStateFingerprint,
            IntegrationRequestContext identity) {
        return commandResult(
                sessionId,
                new MirrorSessionCommandRequest(
                        MirrorSessionCommandRequest.SCHEMA_VERSION,
                        writeEffectRef, expectedStateFingerprint,
                        input),
                null, identity);
    }

    private MirrorStateRunSession.CommandResult commandResult(
            String sessionId,
            MirrorSessionCommandRequest request,
            WriteAttemptContext attemptContext,
            IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope scope = requireScope(identity);
        if (request == null) {
            throw badRequest(identity, "RG.MIRROR.SESSION.COMMAND_INVALID",
                    "A complete stateful mirror command is required.");
        }
        if (attemptContext != null) {
            boolean reconciliationReady;
            try {
                reconciliationReady =
                        store.writeAttemptReconciliationReady();
            } catch (RuntimeException unavailable) {
                reconciliationReady = false;
            }
            if (!reconciliationReady) {
                throw storeProblem(
                        identity,
                        new MirrorSessionStateStoreException(
                                MirrorSessionStateStoreException.Code
                                        .UNAVAILABLE,
                                5));
            }
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
                    return executeCommand(
                            key, request, attemptContext,
                            identity);
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

    private MirrorStateRunSession.CommandResult executeCommand(
            SessionKey key,
            MirrorSessionCommandRequest request,
            WriteAttemptContext attemptContext,
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
            return executeClaimedCommand(
                    claimed, request,
                    attemptContext, identity);
        } finally {
            releaseLease(claimed.lease());
        }
    }

    private MirrorStateRunSession.CommandResult executeClaimedCommand(
            MirrorSessionStateStore.ClaimedSession claimed,
            MirrorSessionCommandRequest request,
            WriteAttemptContext attemptContext,
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
        AtomicReference<MirrorSessionPayload> committedPayload =
                new AtomicReference<>();
        AtomicReference<MirrorSessionStateStoreException> storeFailure =
                new AtomicReference<>();
        AtomicReference<String> writeAttemptId =
                new AtomicReference<>("");
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
                                        sealed,
                                        writeAttemptId.get())));
                        committedPayload.set(sealed);
                    } catch (MirrorSessionStateStoreException failure) {
                        if (recoverCommittedAttempt(
                                claimed, writeAttemptId.get(),
                                candidate, committed,
                                committedPayload)) {
                            return;
                        }
                        storeFailure.set(failure);
                        throw failure;
                    }
                });
        SessionStateSpace.TransactionReceipt receipt;
        try {
            receipt = engine.execute(
                    effect,
                    request.input(),
                    new MirrorStateTransactionEngine
                            .CommandLifecycle() {
                        @Override
                        public void beforeNew(
                                SessionStateSpace current,
                                MirrorStateTransactionEngine
                                        .CommandIdentity commandIdentity) {
                            if (!request.expectedStateFingerprint()
                                    .isBlank()
                                    && !request
                                    .expectedStateFingerprint()
                                    .equals(current.fingerprint())) {
                                throw new MirrorSessionStateStoreException(
                                        MirrorSessionStateStoreException.Code
                                                .STATE_CONFLICT,
                                        1);
                            }
                            beginWriteAttempt(
                                    claimed, attemptContext,
                                    current, commandIdentity,
                                    writeAttemptId);
                        }

                        @Override
                        public void onReplay(
                                SessionStateSpace current,
                                MirrorStateTransactionEngine
                                        .CommandIdentity commandIdentity,
                                SessionStateSpace.TransactionReceipt
                                        originalReceipt) {
                            beginWriteAttempt(
                                    claimed, attemptContext,
                                    current, commandIdentity,
                                    writeAttemptId);
                            completeReplayAttempt(
                                    claimed, current,
                                    originalReceipt,
                                    writeAttemptId.get());
                        }
                    });
        } catch (MirrorSessionStateStoreException failure) {
            throw storeProblem(identity, failure);
        } catch (MirrorStateException failure) {
            if (storeFailure.get() != null) {
                throw storeProblem(identity, storeFailure.get());
            }
            completeRejectedAttempt(
                    claimed, payload.state(),
                    writeAttemptId.get(), failure);
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
        MirrorSessionPayload resultingPayload = replayed
                ? payload : Objects.requireNonNull(
                committedPayload.get(),
                "new command did not produce a sealed payload");
        return new MirrorStateRunSession.CommandResult(
                descriptor, resultingPayload, receipt, replayed);
    }

    private void beginWriteAttempt(
            MirrorSessionStateStore.ClaimedSession claimed,
            WriteAttemptContext attemptContext,
            SessionStateSpace current,
            MirrorStateTransactionEngine.CommandIdentity commandIdentity,
            AtomicReference<String> writeAttemptId) {
        if (attemptContext == null) {
            return;
        }
        String attemptId =
                MirrorStateWriteAttemptIntegrity.attemptId(
                        mapper, claimed.lease().scope(),
                        claimed.lease().sessionId(),
                        attemptContext.coordinate(),
                        commandIdentity.writeEffectRef(),
                        attemptContext.requestFingerprint());
        writeAttemptId.set(attemptId);
        store.beginWriteAttempt(
                new MirrorSessionStateStore
                        .BeginWriteAttemptCommand(
                        claimed.lease(), attemptId,
                        attemptContext.coordinate(),
                        current.planFingerprint(),
                        commandIdentity.writeEffectRef(),
                        attemptContext.requestFingerprint(),
                        commandIdentity.commandFingerprint(),
                        current.stateRevision(),
                        current.worldFingerprint(),
                        current.fingerprint()));
    }

    private void completeReplayAttempt(
            MirrorSessionStateStore.ClaimedSession claimed,
            SessionStateSpace current,
            SessionStateSpace.TransactionReceipt receipt,
            String writeAttemptId) {
        if (writeAttemptId.isBlank()) {
            return;
        }
        store.completeWriteAttempt(
                new MirrorSessionStateStore
                        .CompleteWriteAttemptCommand(
                        claimed.lease(), writeAttemptId,
                        MirrorStateWriteOutcomeRunEvidence
                                .WriteOutcome.REPLAYED,
                        MirrorStateWriteOutcomeRunEvidence
                                .WriteStage.COMPLETED,
                        current.stateRevision(),
                        current.worldFingerprint(),
                        current.fingerprint(),
                        receipt.fingerprint(),
                        false, "", ""));
    }

    private void completeRejectedAttempt(
            MirrorSessionStateStore.ClaimedSession claimed,
            SessionStateSpace current,
            String writeAttemptId,
            MirrorStateException failure) {
        if (writeAttemptId.isBlank()
                || "RG.MIRROR.STATE.COMMIT_FAILED".equals(
                failure.code())) {
            return;
        }
        try {
            store.completeWriteAttempt(
                    new MirrorSessionStateStore
                            .CompleteWriteAttemptCommand(
                            claimed.lease(), writeAttemptId,
                            MirrorStateWriteOutcomeRunEvidence
                                    .WriteOutcome.REJECTED,
                            MirrorStateWriteOutcomeRunEvidence
                                    .WriteStage
                                    .COMMAND_EVALUATION,
                            current.stateRevision(),
                            current.worldFingerprint(),
                            current.fingerprint(),
                            "", false, failure.code(),
                            "MIRROR_STATE_WRITE"));
        } catch (RuntimeException unavailable) {
            // The in-progress intent remains recoverable after its database lease expires.
        }
    }

    private boolean recoverCommittedAttempt(
            MirrorSessionStateStore.ClaimedSession claimed,
            String writeAttemptId,
            SessionStateSpace candidate,
            AtomicReference<MirrorSessionStateStore.CommitResult> committed,
            AtomicReference<MirrorSessionPayload> committedPayload) {
        if (writeAttemptId.isBlank()) {
            return false;
        }
        try {
            Optional<MirrorStateWriteAttempt> attempt =
                    store.findWriteAttempt(
                            claimed.lease().scope(),
                            claimed.lease().sessionId(),
                            writeAttemptId);
            if (attempt.isEmpty()
                    || attempt.orElseThrow().outcome()
                    != MirrorStateWriteOutcomeRunEvidence
                    .WriteOutcome.COMMITTED) {
                return false;
            }
            MirrorSessionStateStore.SessionSnapshot snapshot =
                    store.snapshot(
                            new MirrorSessionStateStore.SnapshotCommand(
                                    claimed.lease().scope(),
                                    claimed.lease().sessionId()));
            if (!snapshot.payload().state().fingerprint().equals(
                    candidate.fingerprint())) {
                return false;
            }
            committed.set(new MirrorSessionStateStore.CommitResult(
                    snapshot.descriptor()));
            committedPayload.set(snapshot.payload());
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private WriteAttemptContext directAttemptContext(
            MirrorSessionCommandRequest request,
            IntegrationRequestContext identity) {
        if (request == null || identity == null) {
            return null;
        }
        String requestFingerprint =
                ProtocolFingerprint.ofBounded(
                        mapper, request,
                        MirrorSessionProtocolIntegrity
                                .MAXIMUM_PAYLOAD_BYTES);
        String executionRequestId = "command-"
                + UUID.randomUUID();
        return new WriteAttemptContext(
                new MirrorStateWriteAttempt.Coordinate(
                        MirrorStateWriteAttempt.ExecutionKind
                                .SESSION_COMMAND,
                        executionRequestId, 1,
                        "session-command-api",
                        "/session-command",
                        correlationFingerprint(
                                identity.correlationId()),
                        1, 1),
                requestFingerprint);
    }

    private String correlationFingerprint(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            return "";
        }
        return ProtocolFingerprint.ofBounded(
                mapper,
                Map.of(
                        "domain", CORRELATION_FINGERPRINT_DOMAIN,
                        "value", normalized),
                MAXIMUM_CORRELATION_MATERIAL_BYTES);
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

    private static String normalizeAttemptId(
            String attemptId,
            IntegrationRequestContext identity) {
        String normalized = attemptId == null
                ? "" : attemptId.trim();
        if (!normalized.matches(
                "attempt-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw badRequest(
                    identity,
                    "RG.MIRROR.SESSION.WRITE_ATTEMPT_ID_INVALID",
                    "The mirror Session write-attempt id is invalid.");
        }
        return normalized;
    }

    private static String canonicalFingerprint(
            String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    field + " must be a canonical SHA-256 value");
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

    private static IntegrationProblemException checkpointProblem(
            IntegrationRequestContext identity,
            MirrorSessionCheckpointException failure) {
        return switch (failure.code()) {
            case SIGNER_UNAVAILABLE ->
                    new IntegrationProblemException(
                            IntegrationProblem.serviceUnavailable(
                                    "RG.MIRROR.SESSION.CHECKPOINT_SIGNER_UNAVAILABLE",
                                    "The checkpoint signing authority cannot establish recovery trust.",
                                    identity.correlationId(),
                                    Map.of("retryAfterSeconds", 5)));
            case INVALID ->
                    badRequest(identity,
                            "RG.MIRROR.SESSION.CHECKPOINT_INVALID",
                            "The signed mirror Session checkpoint is invalid.");
            case GENERATION_CONFLICT ->
                    new IntegrationProblemException(
                            IntegrationProblem.conflict(
                                    "RG.MIRROR.SESSION.CHECKPOINT_GENERATION_CONFLICT",
                                    "The checkpoint belongs to a different Session data-plane generation.",
                                    identity.correlationId(), Map.of()));
            case DEPENDENCY_CONFLICT ->
                    new IntegrationProblemException(
                            IntegrationProblem.conflict(
                                    "RG.MIRROR.SESSION.CHECKPOINT_DEPENDENCY_CONFLICT",
                                    "The checkpoint executable dependency closure no longer matches.",
                                    identity.correlationId(), Map.of()));
            case STATE_CONFLICT ->
                    new IntegrationProblemException(
                            IntegrationProblem.conflict(
                                    "RG.MIRROR.SESSION.CHECKPOINT_STATE_CONFLICT",
                                    "The Session state head changed after the checkpoint was created.",
                                    identity.correlationId(), Map.of()));
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

    private record WriteAttemptContext(
            MirrorStateWriteAttempt.Coordinate coordinate,
            String requestFingerprint
    ) {
        private WriteAttemptContext {
            coordinate = Objects.requireNonNull(
                    coordinate, "coordinate");
            requestFingerprint = canonicalFingerprint(
                    requestFingerprint,
                    "requestFingerprint");
        }
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
