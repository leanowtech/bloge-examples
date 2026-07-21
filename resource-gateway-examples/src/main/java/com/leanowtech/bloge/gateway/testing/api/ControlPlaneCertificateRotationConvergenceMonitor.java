package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Publishes exact local certificate-rotation state and supplies leased fleet admission decisions.
 *
 * <p>Database I/O is confined to lifecycle callbacks and the heartbeat lane. Request threads and
 * the durable generation floor consume only a cached decision whose local expiry is derived from
 * the database snapshot's remaining lease duration. A stalled scheduler, unavailable database,
 * expired member lease, process close, generation fork or local state drift therefore closes both
 * activation and request admission without putting provider I/O on the request path.</p>
 *
 * <p>A successor may activate only after every governed slot has staged the exact event and the
 * database observation time reaches the signed activation instant. The new generation may serve
 * only after every slot reports {@code ACTIVE}. {@code FENCED_QUORUM} remains protocol-only until
 * an independent deployment authority proves absent replicas have been removed from traffic.</p>
 */
public final class ControlPlaneCertificateRotationConvergenceMonitor implements
        ControlPlaneCertificateRotationLifecycle,
        ControlPlaneCertificateRotationActivationAuthority,
        AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(
            ControlPlaneCertificateRotationConvergenceMonitor.class);
    private static final Pattern STATUS = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private final ControlPlaneCertificateRotationConvergenceRepository repository;
    private final ControlPlaneCertificateRotationFleetPolicy policy;
    private final String policyFingerprint;
    private final Clock clock;
    private final Map<String, Lane> lanes = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor scheduler;
    private final AtomicBoolean failureLogged = new AtomicBoolean();

    private volatile boolean closed;

    /**
     * Starts one process-local convergence heartbeat lane using the system UTC clock.
     *
     * @param repository database-clock convergence authority
     * @param policy immutable deployment fleet policy
     * @param objectMapper canonical policy fingerprint mapper
     */
    public ControlPlaneCertificateRotationConvergenceMonitor(
            ControlPlaneCertificateRotationConvergenceRepository repository,
            ControlPlaneCertificateRotationFleetPolicy policy,
            ObjectMapper objectMapper) {
        this(repository, policy, objectMapper, Clock.systemUTC(), true);
    }

    /**
     * Starts one process-local convergence heartbeat lane with an explicit local lease clock.
     *
     * @param repository database-clock convergence authority
     * @param policy immutable deployment fleet policy
     * @param objectMapper canonical policy fingerprint mapper
     * @param clock local clock used only for bounded cached-decision expiry
     */
    public ControlPlaneCertificateRotationConvergenceMonitor(
            ControlPlaneCertificateRotationConvergenceRepository repository,
            ControlPlaneCertificateRotationFleetPolicy policy,
            ObjectMapper objectMapper,
            Clock clock) {
        this(repository, policy, objectMapper, clock, true);
    }

    /** Package-visible scheduler-independent constructor for deterministic tests. */
    ControlPlaneCertificateRotationConvergenceMonitor(
            ControlPlaneCertificateRotationConvergenceRepository repository,
            ControlPlaneCertificateRotationFleetPolicy policy,
            ObjectMapper objectMapper,
            Clock clock,
            boolean startScheduler) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.policyFingerprint = policy.sharedPolicyFingerprint(
                Objects.requireNonNull(objectMapper, "objectMapper"));
        this.clock = Objects.requireNonNull(clock, "clock");
        if (policy.activationMode()
                != ControlPlaneCertificateRotationFleetPolicy.ActivationMode.ALL_REPLICAS) {
            throw invalid("Certificate rotation quorum activation lacks a serving fence");
        }
        if (policy.expectedInstanceIds().size() > 1
                && !policy.inventoryAttestation().externallyAttested()) {
            throw invalid("Multi-replica certificate rotation inventory is not attested");
        }
        if (policy.inventoryAttestation().externallyAttested()
                && !policy.inventoryAttestation().expiresAt().isAfter(
                clock.instant().plus(policy.leaseDuration()))) {
            throw invalid("Certificate rotation inventory expires before one full lease");
        }
        scheduler = startScheduler ? scheduler() : null;
    }

    /**
     * Registers one exact live transport before durable state is reconstructed.
     *
     * <p>This compatibility overload can gate the live transport but cannot advance a gated
     * durable floor. Product composition must use {@link #registerTarget(String,
     * RotatingControlPlaneHttpTransport, ControlPlaneCertificateRotationFloor)}.</p>
     *
     * @param targetId stable product target identity
     * @param transport live generation transport
     */
    public void registerTarget(
            String targetId,
            RotatingControlPlaneHttpTransport transport) {
        registerTarget(targetId, transport, null);
    }

    /**
     * Registers one exact live transport and its durable generation authority.
     *
     * @param targetId stable product target identity
     * @param transport live generation transport
     * @param floor durable floor consuming this monitor's cached activation decision
     */
    public void registerTarget(
            String targetId,
            RotatingControlPlaneHttpTransport transport,
            ControlPlaneCertificateRotationFloor floor) {
        String required = requireTarget(targetId);
        if (closed || lanes.putIfAbsent(required,
                new Lane(required, Objects.requireNonNull(transport, "transport"), floor))
                != null) {
            throw invalid("Certificate rotation convergence target is already registered");
        }
    }

    /**
     * Returns a non-blocking activation and serving gate bound to one stable target.
     *
     * @param targetId stable product target identity
     * @return cached fail-closed gate
     */
    public RotatingControlPlaneHttpTransport.ActivationGate activationGate(String targetId) {
        String required = requireTarget(targetId);
        return new RotatingControlPlaneHttpTransport.ActivationGate() {
            @Override
            public boolean activationPermitted(long generation, Instant activateAt) {
                Lane lane = lanes.get(required);
                return lane != null && exact(lane, generation, activateAt)
                        && lane.durableActivated && fresh(lane);
            }

            @Override
            public boolean servingPermitted(long generation) {
                Lane lane = lanes.get(required);
                if (closed || lane == null) {
                    return false;
                }
                Expected expected = lane.expected;
                if (expected == null) {
                    return true;
                }
                if (!fresh(lane)) {
                    return false;
                }
                if (generation == expected.rotation().generation()) {
                    return lane.publishedState
                            == ControlPlaneCertificateRotationConvergenceRepository
                            .ReplicaState.ACTIVE
                            && lane.snapshot != null && lane.snapshot.converged();
                }
                return generation + 1 == expected.rotation().generation()
                        && (lane.publishedState
                        == ControlPlaneCertificateRotationConvergenceRepository
                        .ReplicaState.STAGED || lane.publishedState
                        == ControlPlaneCertificateRotationConvergenceRepository
                        .ReplicaState.FAILED);
            }
        };
    }

    /** Reconstructs one signed active floor head and requires fresh all-replica proof. */
    public void restoreActive(
            ControlPlaneCertificateRotationConvergenceRepository.ExpectedRotation expected) {
        Lane lane = prepareExpected(expected);
        lane.durableActivated = true;
        lane.desiredState = ControlPlaneCertificateRotationConvergenceRepository
                .ReplicaState.ACTIVE;
        publish(lane);
    }

    /** Reconstructs one durable pending floor head without admitting activation. */
    public void restorePending(
            ControlPlaneCertificateRotationConvergenceRepository.ExpectedRotation expected) {
        Lane lane = prepareExpected(expected);
        lane.desiredState = ControlPlaneCertificateRotationConvergenceRepository
                .ReplicaState.STAGED;
        publish(lane);
        reconcileAndPublish(lane);
    }

    /** Installs one verified event identity before a due event mutates the live transport. */
    @Override
    public void prepare(ControlPlaneCertificateRotationEvent event) {
        prepareExpected(expected(event));
    }

    /** Publishes the exact state produced by the local transport and reconciles its successor. */
    @Override
    public void applied(ControlPlaneCertificateRotationEvent event) {
        Lane lane = exactLane(expected(event));
        observeLocalState(lane, "LOCAL_STATE_DIVERGED");
        publish(lane);
        reconcileAndPublish(lane);
    }

    /** Publishes a bounded failure while preserving the retryable expected rotation identity. */
    @Override
    public void failed(ControlPlaneCertificateRotationEvent event, String failureCode) {
        Lane lane = exactLane(expected(event));
        lane.desiredState = ControlPlaneCertificateRotationConvergenceRepository
                .ReplicaState.FAILED;
        lane.failureCode = boundedFailureCode(failureCode);
        publish(lane);
    }

    /**
     * Evaluates a due durable successor using only the current local decision lease.
     *
     * @param rotation exact signed rotation identity
     * @return true only when database time and all-replica staging permit activation
     */
    @Override
    public boolean activationPermitted(
            ControlPlaneCertificateRotationConvergenceRepository.ExpectedRotation rotation) {
        if (closed || rotation == null) {
            return false;
        }
        Lane lane = lanes.get(rotation.targetId());
        return lane != null && lane.expected != null
                && lane.expected.rotation().equals(rotation) && fresh(lane)
                && lane.snapshot != null && lane.snapshot.activationPermitted()
                && !lane.snapshot.observedAt().isBefore(rotation.activateAt());
    }

    /** Publishes all current lanes and advances newly admitted durable and live generations. */
    public void refreshNow() {
        if (!closed) {
            lanes.values().forEach(this::reconcileAndPublish);
        }
    }

    /** @return fixed-cardinality state without target, event, material or process identities */
    public Descriptor descriptor() {
        int tracked = 0;
        int staged = 0;
        int active = 0;
        int blocked = 0;
        boolean available = !closed;
        boolean converged = !lanes.isEmpty();
        boolean servingReady = !closed && !lanes.isEmpty();
        String status = closed ? "CLOSED" : "IDLE";
        for (Lane lane : lanes.values()) {
            if (lane.expected == null) {
                continue;
            }
            tracked++;
            boolean fresh = fresh(lane);
            boolean laneConverged = fresh && lane.snapshot != null
                    && lane.snapshot.converged()
                    && lane.publishedState
                    == ControlPlaneCertificateRotationConvergenceRepository.ReplicaState.ACTIVE;
            staged += lane.publishedState
                    == ControlPlaneCertificateRotationConvergenceRepository.ReplicaState.STAGED
                    ? 1 : 0;
            active += lane.publishedState
                    == ControlPlaneCertificateRotationConvergenceRepository.ReplicaState.ACTIVE
                    ? 1 : 0;
            available &= fresh;
            converged &= laneConverged;
            servingReady &= fresh && (laneConverged || lane.publishedState
                    == ControlPlaneCertificateRotationConvergenceRepository.ReplicaState.STAGED
                    || lane.publishedState
                    == ControlPlaneCertificateRotationConvergenceRepository.ReplicaState.FAILED);
            if (!fresh || lane.publishedState
                    == ControlPlaneCertificateRotationConvergenceRepository.ReplicaState.FAILED
                    || !laneConverged && lane.publishedState
                    == ControlPlaneCertificateRotationConvergenceRepository.ReplicaState.ACTIVE) {
                blocked++;
            }
            String laneStatus = !fresh ? "CONVERGENCE_LEASE_UNAVAILABLE"
                    : laneConverged ? "CONVERGED"
                    : lane.snapshot == null ? "LOCAL_STATE_UNPUBLISHED"
                    : lane.snapshot.status();
            status = primaryStatus(status, laneStatus);
        }
        if (tracked == 0) {
            converged = false;
        }
        return new Descriptor(Descriptor.SCHEMA_VERSION, available, true,
                converged, servingReady, lanes.size(), tracked, staged, active, blocked, status);
    }

    /** Stops publication, closes every gate and best-effort withdraws this process membership. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(
                        Math.min(1_000L, policy.heartbeatInterval().toMillis()),
                        TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            repository.withdraw(policy.instanceId(), policy.startupId());
        } catch (RuntimeException ignored) {
            // The database-clock lease remains the crash-safe withdrawal boundary.
        }
    }

    private Lane prepareExpected(
            ControlPlaneCertificateRotationConvergenceRepository.ExpectedRotation expected) {
        Lane lane = lanes.get(Objects.requireNonNull(expected, "expectedRotation").targetId());
        if (lane == null || closed) {
            throw invalid("Certificate rotation convergence target is not registered");
        }
        synchronized (lane) {
            if (lane.expected != null && lane.expected.rotation().equals(expected)) {
                return lane;
            }
            if (lane.expected != null && (lane.publishedExpected == null
                    || !lane.expected.equals(lane.publishedExpected)
                    || lane.publishedState
                    != ControlPlaneCertificateRotationConvergenceRepository.ReplicaState.ACTIVE
                    || expected.generation()
                    != lane.publishedExpected.rotation().generation() + 1)) {
                throw invalid("Certificate rotation convergence generation is not consecutive");
            }
            lane.expected = new Expected(expected);
            lane.desiredState = null;
            lane.failureCode = "";
            lane.pendingAcknowledgement = null;
            lane.snapshot = null;
            lane.localDecisionExpiresAt = Instant.EPOCH;
            lane.durableActivated = false;
            return lane;
        }
    }

    private Lane exactLane(
            ControlPlaneCertificateRotationConvergenceRepository.ExpectedRotation expected) {
        Lane lane = lanes.get(expected.targetId());
        if (lane == null || lane.expected == null
                || !lane.expected.rotation().equals(expected)) {
            throw invalid("Certificate rotation convergence event is not prepared");
        }
        return lane;
    }

    private void reconcileAndPublish(Lane lane) {
        if (lane.expected == null || closed) {
            return;
        }
        try {
            observeLocalState(lane, "LOCAL_STATE_DIVERGED");
            publish(lane);
            if (lane.desiredState
                    == ControlPlaneCertificateRotationConvergenceRepository.ReplicaState.STAGED
                    && activationPermitted(lane.expected.rotation())) {
                if (lane.floor != null) {
                    ControlPlaneCertificateRotationFloor.Snapshot durable =
                            lane.floor.snapshot(lane.targetId);
                    if (durable.activeGeneration() != lane.expected.rotation().generation()) {
                        throw new IllegalStateException(
                                "Durable certificate rotation floor did not activate");
                    }
                }
                lane.durableActivated = true;
                lane.transport.reconcileGeneration();
                observeLocalState(lane, "LOCAL_STATE_DIVERGED");
                publish(lane);
            }
        } catch (RuntimeException unavailable) {
            if (lane.durableActivated) {
                lane.desiredState = ControlPlaneCertificateRotationConvergenceRepository
                        .ReplicaState.FAILED;
                lane.failureCode = "LOCAL_ACTIVATION_FAILED";
                publish(lane);
                return;
            }
            if (failureLogged.compareAndSet(false, true)) {
                log.warn("Certificate-rotation convergence heartbeat failed; activation and "
                        + "request admission remain lease-fenced");
            }
        }
    }

    private void observeLocalState(Lane lane, String failureCode) {
        long generation = lane.expected.rotation().generation();
        long active = lane.transport.localActiveGeneration();
        OptionalLong pending = lane.transport.pendingGeneration();
        if (active == generation && pending.isEmpty()) {
            lane.desiredState = ControlPlaneCertificateRotationConvergenceRepository
                    .ReplicaState.ACTIVE;
            lane.failureCode = "";
        } else if (active + 1 == generation && pending.isPresent()
                && pending.getAsLong() == generation) {
            lane.desiredState = ControlPlaneCertificateRotationConvergenceRepository
                    .ReplicaState.STAGED;
            lane.failureCode = "";
        } else {
            lane.desiredState = ControlPlaneCertificateRotationConvergenceRepository
                    .ReplicaState.FAILED;
            lane.failureCode = failureCode;
        }
    }

    private void publish(Lane lane) {
        ControlPlaneCertificateRotationConvergenceRepository.Acknowledgement acknowledgement;
        synchronized (lane) {
            if (closed || lane.expected == null || lane.desiredState == null) {
                return;
            }
            acknowledgement = acknowledgement(lane);
            lane.pendingAcknowledgement = acknowledgement;
        }
        try {
            ControlPlaneCertificateRotationConvergenceRepository.Snapshot snapshot =
                    repository.acknowledge(acknowledgement);
            Duration databaseRemaining = Duration.between(
                    snapshot.observedAt(), snapshot.nextLeaseExpiryAt());
            Duration localMaximum = policy.heartbeatInterval().multipliedBy(2);
            Duration validity = databaseRemaining.compareTo(localMaximum) < 0
                    ? databaseRemaining : localMaximum;
            if (validity.isNegative() || validity.isZero()) {
                throw new IllegalStateException(
                        "Certificate rotation convergence lease has no validity");
            }
            synchronized (lane) {
                if (!acknowledgement.equals(lane.pendingAcknowledgement)) {
                    return;
                }
                lane.sequence = acknowledgement.sequence();
                lane.publishedExpected = lane.expected;
                lane.publishedState = acknowledgement.state();
                lane.publishedFailureCode = acknowledgement.failureCode();
                lane.pendingAcknowledgement = null;
                lane.snapshot = snapshot;
                lane.localDecisionExpiresAt = clock.instant().plus(validity);
            }
            failureLogged.set(false);
        } catch (RuntimeException unavailable) {
            if (failureLogged.compareAndSet(false, true)) {
                log.warn("Certificate-rotation convergence publication failed; cached leases "
                        + "will expire closed");
            }
        }
    }

    private ControlPlaneCertificateRotationConvergenceRepository.Acknowledgement acknowledgement(
            Lane lane) {
        if (lane.pendingAcknowledgement != null
                && lane.pendingAcknowledgement.expectedRotation().equals(
                lane.expected.rotation())
                && lane.pendingAcknowledgement.state() == lane.desiredState
                && lane.pendingAcknowledgement.failureCode().equals(lane.failureCode)) {
            return lane.pendingAcknowledgement;
        }
        boolean renewal = lane.publishedExpected != null
                && lane.publishedExpected.equals(lane.expected)
                && lane.publishedState == lane.desiredState
                && lane.publishedFailureCode.equals(lane.failureCode);
        long sequence = renewal ? lane.sequence : lane.sequence + 1;
        return new ControlPlaneCertificateRotationConvergenceRepository.Acknowledgement(
                ControlPlaneCertificateRotationConvergenceRepository.Acknowledgement
                        .SCHEMA_VERSION,
                policy.deploymentScopeId(), policy.fleetId(), policy.instanceId(),
                policy.startupId(), policy.artifactFingerprint(), policyFingerprint,
                policy.protocolVersion(), sequence, lane.expected.rotation(),
                lane.desiredState, lane.failureCode);
    }

    private boolean fresh(Lane lane) {
        return !closed && lane.snapshot != null
                && clock.instant().isBefore(lane.localDecisionExpiresAt);
    }

    private static boolean exact(Lane lane, long generation, Instant activateAt) {
        Expected expected = lane.expected;
        return expected != null && expected.rotation().generation() == generation
                && expected.rotation().activateAt().equals(activateAt);
    }

    private static ControlPlaneCertificateRotationConvergenceRepository.ExpectedRotation expected(
            ControlPlaneCertificateRotationEvent event) {
        ControlPlaneCertificateRotationEvent required = Objects.requireNonNull(event, "event");
        ControlPlaneCertificateRotationEvent.Material material = required.material();
        return new ControlPlaneCertificateRotationConvergenceRepository.ExpectedRotation(
                material.targetId(), material.generation(), material.eventId(),
                required.materialFingerprint(), material.settingsFingerprint(),
                material.activateAt());
    }

    private ScheduledThreadPoolExecutor scheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task,
                    "resource-gateway-certificate-rotation-convergence-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        long interval = policy.heartbeatInterval().toMillis();
        long initialDelay = ThreadLocalRandom.current().nextLong(
                Math.max(1L, interval / 2L), interval + 1L);
        executor.scheduleWithFixedDelay(this::refreshSafely, initialDelay, interval,
                TimeUnit.MILLISECONDS);
        return executor;
    }

    private void refreshSafely() {
        try {
            refreshNow();
        } catch (RuntimeException unexpected) {
            if (failureLogged.compareAndSet(false, true)) {
                log.warn("Certificate-rotation convergence scheduler failed; cached leases "
                        + "will expire closed");
            }
        }
    }

    private static String boundedFailureCode(String value) {
        String normalized = normalized(value);
        return ControlPlaneCertificateRotationConvergenceRepository.FAILURE_CODE
                .matcher(normalized).matches() ? normalized : "LOCAL_ROTATION_FAILED";
    }

    private static String primaryStatus(String current, String candidate) {
        int currentPriority = statusPriority(current);
        int candidatePriority = statusPriority(candidate);
        if (candidatePriority != currentPriority) {
            return candidatePriority > currentPriority ? candidate : current;
        }
        return candidate.compareTo(current) < 0 ? candidate : current;
    }

    private static int statusPriority(String status) {
        return switch (status) {
            case "CLOSED" -> 6;
            case "CONVERGENCE_LEASE_UNAVAILABLE" -> 5;
            case "LOCAL_STATE_UNPUBLISHED" -> 4;
            case "IDLE" -> 1;
            case "CONVERGED" -> 2;
            case "ACTIVATION_PERMITTED" -> 3;
            default -> 4;
        };
    }

    private static String requireTarget(String targetId) {
        String target = normalized(targetId);
        if (!ControlPlaneCertificateRotationTargets.contains(target)) {
            throw invalid("Certificate rotation convergence target is invalid");
        }
        return target;
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    /**
     * Fixed-cardinality, material-free fleet readiness projection.
     *
     * @param schemaVersion descriptor protocol generation
     * @param available whether every tracked target has a current local decision lease
     * @param activationModeSupported whether the configured mode has a real serving fence
     * @param replicaConvergenceProven whether every tracked target has all-replica active proof
     * @param servingReady whether tracked targets are staged or fully converged active
     * @param registeredTargetCount local product target count
     * @param trackedRotationCount signed rotation head count
     * @param stagedTargetCount locally published staged target count
     * @param activeTargetCount locally published active target count
     * @param blockedTargetCount active targets lacking convergence or targets lacking a lease
     * @param status primary bounded readiness state
     */
    public record Descriptor(
            String schemaVersion,
            boolean available,
            boolean activationModeSupported,
            boolean replicaConvergenceProven,
            boolean servingReady,
            int registeredTargetCount,
            int trackedRotationCount,
            int stagedTargetCount,
            int activeTargetCount,
            int blockedTargetCount,
            String status) {

        /** Current private monitor descriptor generation. */
        public static final String SCHEMA_VERSION =
                "bloge.controlPlaneCertificateRotationConvergenceMonitorDescriptor.v1";

        /** Validates bounded aggregate relationships. */
        public Descriptor {
            schemaVersion = normalized(schemaVersion);
            status = normalized(status);
            int maximum = ControlPlaneCertificateRotationTargets.values().size();
            if (!SCHEMA_VERSION.equals(schemaVersion) || !STATUS.matcher(status).matches()
                    || !bounded(registeredTargetCount, maximum)
                    || !bounded(trackedRotationCount, registeredTargetCount)
                    || !bounded(stagedTargetCount, trackedRotationCount)
                    || !bounded(activeTargetCount, trackedRotationCount)
                    || stagedTargetCount + activeTargetCount > trackedRotationCount
                    || !bounded(blockedTargetCount, trackedRotationCount)
                    || replicaConvergenceProven
                    && (trackedRotationCount == 0
                    || activeTargetCount != trackedRotationCount
                    || blockedTargetCount != 0)
                    || servingReady && !available) {
                throw invalid("Certificate rotation convergence descriptor is invalid");
            }
        }

        private static boolean bounded(int value, int maximum) {
            return value >= 0 && value <= maximum;
        }
    }

    private record Expected(
            ControlPlaneCertificateRotationConvergenceRepository.ExpectedRotation rotation) {
    }

    private static final class Lane {
        private final String targetId;
        private final RotatingControlPlaneHttpTransport transport;
        private final ControlPlaneCertificateRotationFloor floor;
        private volatile Expected expected;
        private volatile Expected publishedExpected;
        private volatile ControlPlaneCertificateRotationConvergenceRepository.ReplicaState
                desiredState;
        private volatile ControlPlaneCertificateRotationConvergenceRepository.ReplicaState
                publishedState;
        private volatile String failureCode = "";
        private volatile String publishedFailureCode = "";
        private volatile long sequence;
        private volatile ControlPlaneCertificateRotationConvergenceRepository.Acknowledgement
                pendingAcknowledgement;
        private volatile ControlPlaneCertificateRotationConvergenceRepository.Snapshot snapshot;
        private volatile Instant localDecisionExpiresAt = Instant.EPOCH;
        private volatile boolean durableActivated;

        private Lane(
                String targetId,
                RotatingControlPlaneHttpTransport transport,
                ControlPlaneCertificateRotationFloor floor) {
            this.targetId = targetId;
            this.transport = transport;
            this.floor = floor;
        }
    }
}
