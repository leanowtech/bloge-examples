package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.Status;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler.Snapshot;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.RuntimeSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.scheduling.annotation.Scheduled;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-local progress SLO for one assembled bootstrap-root recovery fleet.
 *
 * <p>The monitor reads only immutable authority, worker, and scheduler projections. It performs no
 * inventory snapshot, lane, database, network, provider, or payload operation. An attested
 * authority observation brackets the worker and scheduler reads through
 * {@link ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability}; mixed generations and local
 * read failures therefore fail closed.</p>
 *
 * <p>Current runtime failure is evaluated independently from cumulative poll, cycle, and lane
 * failure ratios. A newly started healthy runtime may initialize inside a bounded grace window;
 * after that window it must have a recent successful poll. The resulting assessment is a
 * versioned, identity-free operational protocol suitable for Actuator, metrics, and alert rules.
 * It is intentionally process-local and does not claim cross-replica convergence.</p>
 */
public final class ExternalSequenceAnchorBootstrapRootRecoveryFleetSloMonitor
        implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetSloMonitor.class);

    private final ObservationReader observations;
    private final ExternalSequenceAnchorBootstrapRootRecoveryFleetTelemetry telemetry;
    private final Policy policy;
    private final Clock clock;
    private final Instant startedAt;
    private final AtomicReference<Assessment> latest = new AtomicReference<>();

    /**
     * Creates a monitor over one already-assembled local recovery fleet.
     *
     * @param inventory caller-owned local inventory or externally attested authority
     * @param worker bounded local recovery worker
     * @param scheduler fixed-delay local scheduler
     * @param telemetry bounded-cardinality metric adapter
     * @param policy explicit progress and aggregate failure policy
     */
    public ExternalSequenceAnchorBootstrapRootRecoveryFleetSloMonitor(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory inventory,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker worker,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler scheduler,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetTelemetry telemetry,
            Policy policy) {
        this(reader(inventory, worker, scheduler), telemetry, policy, Clock.systemUTC());
    }

    ExternalSequenceAnchorBootstrapRootRecoveryFleetSloMonitor(
            ObservationReader observations,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetTelemetry telemetry,
            Policy policy,
            Clock clock) {
        this.observations = Objects.requireNonNull(observations, "observations");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        startedAt = clock.instant();
    }

    /** Refreshes the payload-free assessment from one coherent local projection. */
    @Scheduled(fixedDelayString = "${gateway.testing.external-sequence-anchor."
            + "bootstrap-root-recovery-fleet-slo.observation-interval-millis:30000}")
    public void refresh() {
        Assessment assessment;
        try {
            Projection projection = Objects.requireNonNull(
                    observations.read(), "recovery fleet projection");
            assessment = assess(projection, clock.instant());
        } catch (RuntimeException unavailable) {
            assessment = Assessment.unavailable(policy.descriptor());
            log.warn("Bootstrap-root recovery fleet SLO observation failed; "
                    + "health remains fail-closed until a local projection succeeds");
        }
        latest.set(assessment);
        telemetry.observe(assessment);
    }

    /**
     * Returns the latest versioned assessment, observing once on first access.
     *
     * @return identity-free local SLO assessment
     */
    public Assessment assessment() {
        Assessment assessment = latest.get();
        if (assessment == null) {
            refresh();
            assessment = latest.get();
        }
        return assessment;
    }

    /** Returns aggregate Actuator health without identities, errors, or business payloads. */
    @Override
    public Health health() {
        Assessment assessment = assessment();
        Health.Builder health = Health.status(healthStatus(assessment.state()))
                .withDetail("schemaVersion", assessment.schemaVersion())
                .withDetail("state", assessment.state().name())
                .withDetail("runtimeStatus", assessment.runtimeStatus().name())
                .withDetail("violations", assessment.violations())
                .withDetail("policy", assessment.policy());
        if (assessment.observedAt() != null) {
            health.withDetail("observedAt", assessment.observedAt().toString())
                    .withDetail("inventoryGeneration", assessment.inventoryGeneration())
                    .withDetail("laneCount", assessment.laneCount())
                    .withDetail("pollCount", assessment.pollCount())
                    .withDetail("completedPollCount", assessment.completedPollCount())
                    .withDetail("pollFailureCount", assessment.pollFailureCount())
                    .withDetail("pollFailureBasisPoints",
                            assessment.pollFailureBasisPoints())
                    .withDetail("cycleCount", assessment.cycleCount())
                    .withDetail("cycleFailureCount", assessment.cycleFailureCount())
                    .withDetail("cycleFailureBasisPoints",
                            assessment.cycleFailureBasisPoints())
                    .withDetail("laneAttemptCount", assessment.laneAttemptCount())
                    .withDetail("laneFailureCount", assessment.laneFailureCount())
                    .withDetail("laneFailureBasisPoints",
                            assessment.laneFailureBasisPoints())
                    .withDetail("lastPollSuccessAgeMillis",
                            assessment.lastPollSuccessAgeMillis());
        }
        return health.build();
    }

    private Assessment assess(Projection projection, Instant observedAt) {
        var capability = projection.capability();
        RuntimeSnapshot worker = projection.worker();
        Snapshot scheduler = projection.scheduler();
        if (capability.status() == Status.UNAVAILABLE
                || capability.status() == Status.DISABLED
                || capability.status() == Status.INCOMPLETE_COMPOSITION
                || capability.status() == Status.AMBIGUOUS_COMPOSITION
                || worker == null || scheduler == null) {
            return Assessment.unavailable(policy.descriptor());
        }

        List<Violation> violations = new ArrayList<>();
        State forcedState = currentViolations(capability.status(), violations);
        if (capability.externallyAttested()
                && (capability.pollCount() != scheduler.pollCount()
                || capability.pollFailureCount() != scheduler.pollFailureCount()
                || capability.cycleCount() != worker.cycleCount()
                || capability.cycleFailureCount() != worker.cycleFailureCount())) {
            violations.add(Violation.SNAPSHOT_INCONSISTENT);
        }

        long successAgeMillis = successAgeMillis(scheduler, observedAt);
        boolean initializing = false;
        if (scheduler.completedPollCount() == 0L) {
            Duration startupAge = nonNegativeAge(startedAt, observedAt);
            if (violations.isEmpty() && startupAge.compareTo(policy.startupGrace()) <= 0) {
                initializing = true;
            } else if (startupAge.compareTo(policy.startupGrace()) > 0) {
                violations.add(Violation.POLL_NEVER_SUCCEEDED);
            }
        } else if (!scheduler.active() && successAgeMillis >= 0L
                && successAgeMillis > policy.maximumPollSuccessAge().toMillis()) {
            violations.add(Violation.POLL_SUCCESS_STALE);
        }

        int pollRate = basisPoints(scheduler.pollFailureCount(), scheduler.pollCount());
        int cycleRate = basisPoints(worker.cycleFailureCount(), worker.cycleCount());
        int laneRate = basisPoints(worker.laneFailureCount(), worker.laneAttemptCount());
        rateViolation(scheduler.pollCount(), policy.minimumSamples(), pollRate,
                policy.maximumPollFailureBasisPoints(),
                Violation.POLL_FAILURE_RATE_EXCEEDED, violations);
        rateViolation(worker.cycleCount(), policy.minimumSamples(), cycleRate,
                policy.maximumCycleFailureBasisPoints(),
                Violation.CYCLE_FAILURE_RATE_EXCEEDED, violations);
        rateViolation(worker.laneAttemptCount(), policy.minimumSamples(), laneRate,
                policy.maximumLaneFailureBasisPoints(),
                Violation.LANE_FAILURE_RATE_EXCEEDED, violations);

        List<Violation> canonical = violations.stream().distinct()
                .sorted(Comparator.comparingInt(Enum::ordinal)).toList();
        State state = forcedState == State.CLOSED
                ? State.CLOSED
                : !canonical.isEmpty() ? State.SLO_VIOLATED
                : initializing ? State.INITIALIZING : State.HEALTHY;
        return new Assessment(
                Assessment.SCHEMA_VERSION, state, canonical, observedAt,
                capability.status(), capability.inventoryGeneration(), capability.laneCount(),
                scheduler.pollCount(), scheduler.completedPollCount(),
                scheduler.pollFailureCount(), pollRate,
                worker.cycleCount(), worker.cycleFailureCount(), cycleRate,
                worker.laneAttemptCount(), worker.laneFailureCount(), laneRate,
                successAgeMillis, policy.descriptor());
    }

    private static State currentViolations(Status status, List<Violation> violations) {
        switch (status) {
            case READY -> {
                return null;
            }
            case UNATTESTED_INVENTORY -> violations.add(Violation.UNATTESTED_INVENTORY);
            case INVENTORY_UNAVAILABLE -> violations.add(Violation.INVENTORY_UNAVAILABLE);
            case RUNTIME_CLOSED -> {
                violations.add(Violation.RUNTIME_CLOSED);
                return State.CLOSED;
            }
            case SCHEDULER_STALLED -> violations.add(Violation.SCHEDULER_STALLED);
            case SCHEDULER_FAILED -> violations.add(Violation.SCHEDULER_FAILED);
            case CYCLE_FAILED -> violations.add(Violation.CYCLE_FAILED);
            case LANE_FAILURES -> violations.add(Violation.LATEST_LANE_FAILURES);
            case INCONSISTENT -> violations.add(Violation.SNAPSHOT_INCONSISTENT);
            case DISABLED, INCOMPLETE_COMPOSITION, AMBIGUOUS_COMPOSITION, UNAVAILABLE ->
                    violations.add(Violation.OBSERVATION_UNAVAILABLE);
        }
        return null;
    }

    private static void rateViolation(
            long samples,
            int minimumSamples,
            int observedBasisPoints,
            int maximumBasisPoints,
            Violation violation,
            List<Violation> violations) {
        if (samples >= minimumSamples
                && observedBasisPoints > maximumBasisPoints) {
            violations.add(violation);
        }
    }

    private static long successAgeMillis(Snapshot scheduler, Instant observedAt) {
        if (scheduler.completedPollCount() == 0L || scheduler.lastPollFailed()
                || scheduler.lastPollCompletedAt() == null) {
            return -1L;
        }
        return nonNegativeAge(scheduler.lastPollCompletedAt(), observedAt).toMillis();
    }

    private static Duration nonNegativeAge(Instant earlier, Instant later) {
        Duration duration = Duration.between(earlier, later);
        return duration.isNegative() ? Duration.ZERO : duration;
    }

    private static int basisPoints(long failures, long attempts) {
        if (attempts == 0L) {
            return 0;
        }
        return BigInteger.valueOf(failures).multiply(BigInteger.valueOf(10_000L))
                .divide(BigInteger.valueOf(attempts)).intValueExact();
    }

    private static org.springframework.boot.actuate.health.Status healthStatus(State state) {
        return switch (state) {
            case HEALTHY -> org.springframework.boot.actuate.health.Status.UP;
            case INITIALIZING -> org.springframework.boot.actuate.health.Status.UNKNOWN;
            case SLO_VIOLATED -> org.springframework.boot.actuate.health.Status.OUT_OF_SERVICE;
            case CLOSED, OBSERVATION_UNAVAILABLE ->
                    org.springframework.boot.actuate.health.Status.DOWN;
        };
    }

    private static ObservationReader reader(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory inventory,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker worker,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler scheduler) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(scheduler, "scheduler");
        if (inventory instanceof
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority authority) {
            return () -> {
                AtomicReference<RuntimeSnapshot> workerSnapshot = new AtomicReference<>();
                AtomicReference<Snapshot> schedulerSnapshot = new AtomicReference<>();
                var capability = ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.project(
                        authority::observation, authority::descriptor,
                        () -> capture(workerSnapshot, worker.runtimeSnapshot()),
                        () -> capture(schedulerSnapshot, scheduler.snapshot()));
                return new Projection(capability, workerSnapshot.get(), schedulerSnapshot.get());
            };
        }
        return () -> new Projection(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability.unattested(),
                worker.runtimeSnapshot(), scheduler.snapshot());
    }

    private static <T> T capture(AtomicReference<T> reference, T value) {
        T present = Objects.requireNonNull(value, "projection snapshot");
        reference.set(present);
        return present;
    }

    @FunctionalInterface
    interface ObservationReader {
        Projection read();
    }

    record Projection(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetCapability capability,
            RuntimeSnapshot worker,
            Snapshot scheduler) {

        Projection {
            capability = Objects.requireNonNull(capability, "capability");
        }
    }

    /** Stable aggregate health states suitable for readiness and alert policy. */
    public enum State {
        /** Current runtime truth and cumulative reliability satisfy policy. */
        HEALTHY,
        /** No poll has succeeded yet, but the bounded startup grace remains open. */
        INITIALIZING,
        /** Current runtime truth, freshness, or a mature failure ratio violates policy. */
        SLO_VIOLATED,
        /** Worker or scheduler lifecycle has closed. */
        CLOSED,
        /** A coherent local projection could not be produced. */
        OBSERVATION_UNAVAILABLE
    }

    /** Stable machine-readable SLO failures; enum names are the external codes. */
    public enum Violation {
        /** Fleet inventory lacks an external attestation authority. */
        UNATTESTED_INVENTORY,
        /** Externally attested inventory is not currently admissible. */
        INVENTORY_UNAVAILABLE,
        /** Worker or scheduler lifecycle has closed. */
        RUNTIME_CLOSED,
        /** Scheduler exceeded its bounded progress budget. */
        SCHEDULER_STALLED,
        /** Latest scheduler poll terminated with a local failure. */
        SCHEDULER_FAILED,
        /** Latest worker cycle terminated on a fleet-wide invariant or fatal failure. */
        CYCLE_FAILED,
        /** Latest completed cycle isolated one or more lane failures. */
        LATEST_LANE_FAILURES,
        /** Authority, worker, and scheduler projections were not one coherent observation. */
        SNAPSHOT_INCONSISTENT,
        /** Local assessment inputs could not be read or validated. */
        OBSERVATION_UNAVAILABLE,
        /** No poll completed successfully after startup grace. */
        POLL_NEVER_SUCCEEDED,
        /** Latest known successful poll is older than policy. */
        POLL_SUCCESS_STALE,
        /** Mature cumulative scheduler failure ratio exceeds policy. */
        POLL_FAILURE_RATE_EXCEEDED,
        /** Mature cumulative worker-cycle failure ratio exceeds policy. */
        CYCLE_FAILURE_RATE_EXCEEDED,
        /** Mature cumulative lane failure ratio exceeds policy. */
        LANE_FAILURE_RATE_EXCEEDED
    }

    /**
     * Progress and cumulative-reliability policy.
     *
     * @param startupGrace local process grace before a missing success violates SLO
     * @param maximumPollSuccessAge oldest acceptable latest successful poll
     * @param minimumSamples minimum denominator before any cumulative ratio is enforced
     * @param maximumPollFailureBasisPoints maximum inclusive scheduler failure ratio
     * @param maximumCycleFailureBasisPoints maximum inclusive worker-cycle failure ratio
     * @param maximumLaneFailureBasisPoints maximum inclusive lane failure ratio
     */
    public record Policy(
            Duration startupGrace,
            Duration maximumPollSuccessAge,
            int minimumSamples,
            int maximumPollFailureBasisPoints,
            int maximumCycleFailureBasisPoints,
            int maximumLaneFailureBasisPoints) {

        /** Enforces finite time windows, bounded sample counts, and valid basis points. */
        public Policy {
            boundedDuration(startupGrace, Duration.ofDays(1), "startupGrace");
            boundedDuration(maximumPollSuccessAge, Duration.ofDays(7),
                    "maximumPollSuccessAge");
            if (minimumSamples < 1 || minimumSamples > 1_000_000
                    || !basisPoints(maximumPollFailureBasisPoints)
                    || !basisPoints(maximumCycleFailureBasisPoints)
                    || !basisPoints(maximumLaneFailureBasisPoints)) {
                throw new IllegalArgumentException(
                        "Bootstrap-root recovery fleet SLO policy is invalid");
            }
        }

        /**
         * Returns the serialized, duration-in-milliseconds policy used by an assessment.
         *
         * @return immutable protocol policy descriptor
         */
        public PolicyDescriptor descriptor() {
            return new PolicyDescriptor(
                    startupGrace.toMillis(), maximumPollSuccessAge.toMillis(), minimumSamples,
                    maximumPollFailureBasisPoints, maximumCycleFailureBasisPoints,
                    maximumLaneFailureBasisPoints);
        }

        private static void boundedDuration(Duration value, Duration maximum, String name) {
            Duration present = Objects.requireNonNull(value, name);
            if (present.isZero() || present.isNegative() || present.compareTo(maximum) > 0) {
                throw new IllegalArgumentException(
                        "Bootstrap-root recovery fleet SLO policy is invalid");
            }
        }

        private static boolean basisPoints(int value) {
            return value >= 0 && value <= 10_000;
        }
    }

    /**
     * Serialized SLO policy carried by each assessment.
     *
     * @param startupGraceMillis startup grace in milliseconds
     * @param maximumPollSuccessAgeMillis maximum successful-poll age in milliseconds
     * @param minimumSamples ratio-enforcement sample floor
     * @param maximumPollFailureBasisPoints inclusive scheduler failure threshold
     * @param maximumCycleFailureBasisPoints inclusive worker-cycle failure threshold
     * @param maximumLaneFailureBasisPoints inclusive lane failure threshold
     */
    public record PolicyDescriptor(
            long startupGraceMillis,
            long maximumPollSuccessAgeMillis,
            int minimumSamples,
            int maximumPollFailureBasisPoints,
            int maximumCycleFailureBasisPoints,
            int maximumLaneFailureBasisPoints) {

        /** Reuses the executable policy validator for wire-level policy integrity. */
        public PolicyDescriptor {
            new Policy(Duration.ofMillis(startupGraceMillis),
                    Duration.ofMillis(maximumPollSuccessAgeMillis), minimumSamples,
                    maximumPollFailureBasisPoints, maximumCycleFailureBasisPoints,
                    maximumLaneFailureBasisPoints);
        }
    }

    /**
     * Versioned identity-free recovery-fleet SLO assessment.
     *
     * @param schemaVersion assessment protocol generation
     * @param state aggregate local SLO state
     * @param violations canonical stable violation codes
     * @param observedAt local observation time, absent only when observation is unavailable
     * @param runtimeStatus exact current capability status
     * @param inventoryGeneration current externally attested generation, or zero when unattested
     * @param laneCount current externally attested lane count, or zero when unattested
     * @param pollCount admitted scheduler polls
     * @param completedPollCount successful bounded scheduler polls
     * @param pollFailureCount failed scheduler polls
     * @param pollFailureBasisPoints cumulative scheduler failure ratio
     * @param cycleCount admitted worker cycles
     * @param cycleFailureCount failed worker cycles
     * @param cycleFailureBasisPoints cumulative worker-cycle failure ratio
     * @param laneAttemptCount attempted recovery lanes
     * @param laneFailureCount isolated lane failures
     * @param laneFailureBasisPoints cumulative lane failure ratio
     * @param lastPollSuccessAgeMillis age of latest successful poll, or minus one when unknown
     * @param policy exact policy used for this assessment
     */
    public record Assessment(
            String schemaVersion,
            State state,
            List<Violation> violations,
            Instant observedAt,
            Status runtimeStatus,
            long inventoryGeneration,
            int laneCount,
            long pollCount,
            long completedPollCount,
            long pollFailureCount,
            int pollFailureBasisPoints,
            long cycleCount,
            long cycleFailureCount,
            int cycleFailureBasisPoints,
            long laneAttemptCount,
            long laneFailureCount,
            int laneFailureBasisPoints,
            long lastPollSuccessAgeMillis,
            PolicyDescriptor policy) {

        /** Current recovery-fleet SLO assessment protocol generation. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetSloAssessment.v1";

        /** Enforces canonical violations and coherent known or unavailable metric shapes. */
        public Assessment {
            schemaVersion = Objects.requireNonNullElse(schemaVersion, "").trim();
            state = Objects.requireNonNull(state, "state");
            violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
            runtimeStatus = Objects.requireNonNull(runtimeStatus, "runtimeStatus");
            policy = Objects.requireNonNull(policy, "policy");
            List<Violation> canonical = violations.stream()
                    .peek(value -> Objects.requireNonNull(value, "violation"))
                    .distinct().sorted(Comparator.comparingInt(Enum::ordinal)).toList();
            boolean unavailable = state == State.OBSERVATION_UNAVAILABLE;
            boolean unknownMetrics = inventoryGeneration == -1L && laneCount == -1
                    && pollCount == -1L && completedPollCount == -1L
                    && pollFailureCount == -1L && pollFailureBasisPoints == -1
                    && cycleCount == -1L && cycleFailureCount == -1L
                    && cycleFailureBasisPoints == -1 && laneAttemptCount == -1L
                    && laneFailureCount == -1L && laneFailureBasisPoints == -1
                    && lastPollSuccessAgeMillis == -1L;
            boolean healthyShape = (state == State.HEALTHY || state == State.INITIALIZING)
                    && violations.isEmpty() && runtimeStatus == Status.READY;
            boolean violatedShape = state == State.SLO_VIOLATED && !violations.isEmpty();
            boolean closedShape = state == State.CLOSED
                    && runtimeStatus == Status.RUNTIME_CLOSED
                    && violations.contains(Violation.RUNTIME_CLOSED);
            boolean unavailableShape = unavailable && observedAt == null
                    && runtimeStatus == Status.UNAVAILABLE
                    && violations.equals(List.of(Violation.OBSERVATION_UNAVAILABLE))
                    && unknownMetrics;
            boolean unavailableCodeShape = violations.contains(
                    Violation.OBSERVATION_UNAVAILABLE) == unavailable
                    && (runtimeStatus == Status.UNAVAILABLE) == unavailable;
            boolean knownMetrics = observedAt != null && inventoryGeneration >= 0L
                    && laneCount >= 0 && pollCount >= 0L
                    && completedPollCount >= 0L && pollFailureCount >= 0L
                    && completedPollCount + pollFailureCount <= pollCount
                    && pollFailureBasisPoints == basisPoints(pollFailureCount, pollCount)
                    && cycleCount >= 0L && cycleFailureCount >= 0L
                    && cycleFailureCount <= cycleCount
                    && cycleFailureBasisPoints == basisPoints(cycleFailureCount, cycleCount)
                    && laneAttemptCount >= 0L && laneFailureCount >= 0L
                    && laneFailureCount <= laneAttemptCount
                    && laneFailureBasisPoints == basisPoints(
                    laneFailureCount, laneAttemptCount)
                    && lastPollSuccessAgeMillis >= -1L;
            if (!SCHEMA_VERSION.equals(schemaVersion) || !violations.equals(canonical)
                    || !(healthyShape || violatedShape || closedShape || unavailableShape)
                    || !unavailableCodeShape || unavailable != unknownMetrics
                    || !unavailable && !knownMetrics) {
                throw new IllegalArgumentException(
                        "Bootstrap-root recovery fleet SLO assessment is invalid");
            }
        }

        private static Assessment unavailable(PolicyDescriptor policy) {
            return new Assessment(SCHEMA_VERSION, State.OBSERVATION_UNAVAILABLE,
                    List.of(Violation.OBSERVATION_UNAVAILABLE), null, Status.UNAVAILABLE,
                    -1L, -1, -1L, -1L, -1L, -1, -1L, -1L, -1,
                    -1L, -1L, -1, -1L, policy);
        }
    }
}
