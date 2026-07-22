package com.leanowtech.bloge.gateway.testing.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded process-local scheduler for durable physical-attempt terminal projections.
 *
 * <p>Each fixed-delay lane performs at most one synchronous worker call. Durable database claims
 * and lease fencing coordinate replicas; the scheduler owns only local wake-up and bounded
 * shutdown. Telemetry failures are isolated from work, while an unexpected worker failure remains
 * visible in the immutable health snapshot.</p>
 */
public final class TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler
        implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler.class);

    private final TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker worker;
    private final TestSuiteStabilityPhysicalAttemptTerminalProjectionTelemetry telemetry;
    private final Policy policy;
    private final ScheduledThreadPoolExecutor executor;
    private final List<ScheduledFuture<?>> lanes;
    private final Object snapshotLock = new Object();
    private final AtomicBoolean telemetryFailureLogged = new AtomicBoolean();
    private final AtomicInteger activePolls = new AtomicInteger();
    private long pollCount;
    private long unexpectedPollCount;
    private TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Outcome lastOutcome;
    private TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.LocalDisposition
            lastLocalDisposition;
    private boolean lastPollFailed;
    private volatile boolean closed;

    /**
     * Starts fixed-delay lanes with an inert telemetry adapter.
     *
     * @param worker fully guarded single-poll worker
     * @param policy bounded local scheduling and shutdown policy
     */
    public TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker worker,
            Policy policy) {
        this(worker, policy,
                TestSuiteStabilityPhysicalAttemptTerminalProjectionTelemetry.noop());
    }

    /**
     * Starts fixed-delay lanes immediately after construction.
     *
     * @param worker fully guarded single-poll worker
     * @param policy bounded local scheduling and shutdown policy
     * @param telemetry fixed-cardinality payload-free metric adapter
     */
    public TestSuiteStabilityPhysicalAttemptTerminalProjectionScheduler(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker worker,
            Policy policy,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionTelemetry telemetry) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        AtomicInteger threadSequence = new AtomicInteger();
        executor = new ScheduledThreadPoolExecutor(policy.maximumPollers(), task ->
                Thread.ofPlatform().daemon(true)
                        .name("resource-gateway-physical-attempt-terminal-projection-poller-"
                                + threadSequence.incrementAndGet())
                        .unstarted(task));
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        List<ScheduledFuture<?>> scheduled = new ArrayList<>(policy.maximumPollers());
        for (int lane = 0; lane < policy.maximumPollers(); lane++) {
            long staggerMillis = Math.min(policy.pollInterval().toMillis() - 1L,
                    lane * Math.max(1L,
                            policy.pollInterval().toMillis() / policy.maximumPollers()));
            scheduled.add(executor.scheduleWithFixedDelay(this::poll,
                    Math.addExact(policy.initialDelay().toMillis(), staggerMillis),
                    policy.pollInterval().toMillis(), TimeUnit.MILLISECONDS));
        }
        lanes = List.copyOf(scheduled);
        observeTelemetry(telemetry::workerStarted);
    }

    /**
     * Captures payload-free local lifecycle and latest poll state.
     *
     * @return immutable scheduler observation
     */
    public Snapshot snapshot() {
        synchronized (snapshotLock) {
            return new Snapshot(Snapshot.SCHEMA_VERSION, policy, pollCount,
                    unexpectedPollCount, activePolls.get(), Optional.ofNullable(lastOutcome),
                    Optional.ofNullable(lastLocalDisposition), lastPollFailed, closed);
        }
    }

    /**
     * Stops new polls, drains active calls for a bounded interval, then requests interruption.
     *
     * <p>Database lease expiry and fencing, not thread interruption, prevent stale completion.</p>
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        lanes.forEach(future -> future.cancel(false));
        executor.shutdown();
        try {
            if (!executor.awaitTermination(
                    policy.drainTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(Math.min(1_000L,
                        policy.drainTimeout().toMillis()), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            observeTelemetry(() -> telemetry.workerStopped(activePolls.get()));
        }
    }

    private void poll() {
        if (closed) {
            return;
        }
        int active = activePolls.incrementAndGet();
        observeTelemetry(() -> telemetry.activePolls(active));
        try {
            TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Execution result =
                    worker.processNext();
            if (result == null) {
                unexpected();
                log.warn("Physical-attempt terminal projection worker returned no bounded "
                        + "result");
            } else {
                synchronized (snapshotLock) {
                    pollCount++;
                    lastOutcome = result.outcome();
                    lastLocalDisposition = result.localDisposition();
                    lastPollFailed = false;
                }
                observeTelemetry(() -> telemetry.recordPoll(result));
            }
        } catch (RuntimeException failure) {
            unexpected();
            log.warn("Physical-attempt terminal projection worker failed before a bounded "
                    + "result was produced");
        } finally {
            int remaining = activePolls.decrementAndGet();
            observeTelemetry(() -> telemetry.activePolls(remaining));
        }
    }

    private void unexpected() {
        synchronized (snapshotLock) {
            pollCount++;
            unexpectedPollCount++;
            lastPollFailed = true;
        }
        observeTelemetry(telemetry::recordUnexpectedPoll);
    }

    private void observeTelemetry(Runnable observation) {
        try {
            observation.run();
        } catch (RuntimeException unavailable) {
            if (telemetryFailureLogged.compareAndSet(false, true)) {
                log.warn("Physical-attempt terminal projection telemetry update failed; "
                        + "further metric failures are suppressed for this scheduler lifecycle");
            }
        }
    }

    /**
     * Bounded process-local polling policy.
     *
     * @param maximumPollers fixed local lane count from 1 through 32
     * @param initialDelay delay before first poll from zero through five minutes
     * @param pollInterval fixed delay from 100 ms through one minute
     * @param drainTimeout graceful shutdown wait from 100 ms through one minute
     */
    public record Policy(
            int maximumPollers,
            Duration initialDelay,
            Duration pollInterval,
            Duration drainTimeout) {

        /** Default two lanes, five-second startup and polling delays, and five-second drain. */
        public static final Policy DEFAULT = new Policy(2, Duration.ofSeconds(5),
                Duration.ofSeconds(5), Duration.ofSeconds(5));

        /** Enforces finite millisecond-exact local limits. */
        public Policy {
            initialDelay = bounded(initialDelay, "initialDelay", Duration.ZERO,
                    Duration.ofMinutes(5));
            pollInterval = bounded(pollInterval, "pollInterval", Duration.ofMillis(100),
                    Duration.ofMinutes(1));
            drainTimeout = bounded(drainTimeout, "drainTimeout", Duration.ofMillis(100),
                    Duration.ofMinutes(1));
            if (maximumPollers < 1 || maximumPollers > 32) {
                throw new IllegalArgumentException(
                        "Physical-attempt terminal projection pollers are invalid");
            }
        }

        private static Duration bounded(
                Duration value, String name, Duration minimum, Duration maximum) {
            Duration required = Objects.requireNonNull(value, name);
            if (required.compareTo(minimum) < 0 || required.compareTo(maximum) > 0
                    || !required.equals(Duration.ofMillis(required.toMillis()))) {
                throw new IllegalArgumentException(
                        "Physical-attempt terminal projection " + name + " is invalid");
            }
            return required;
        }
    }

    /**
     * Immutable aggregate scheduler observation.
     *
     * @param schemaVersion exact snapshot generation
     * @param policy immutable local scheduling policy
     * @param pollCount completed bounded or unexpected polls
     * @param unexpectedPollCount polls throwing or returning no bounded result
     * @param activePolls current synchronous worker calls
     * @param lastOutcome latest bounded worker outcome when any
     * @param lastLocalDisposition latest bounded local disposition when any
     * @param lastPollFailed whether the latest poll was unexpected
     * @param closed whether new polls are forbidden
     */
    public record Snapshot(
            String schemaVersion,
            Policy policy,
            long pollCount,
            long unexpectedPollCount,
            int activePolls,
            Optional<TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Outcome>
                    lastOutcome,
            Optional<TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.LocalDisposition>
                    lastLocalDisposition,
            boolean lastPollFailed,
            boolean closed) {

        /** Exact local terminal-projection scheduler snapshot generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityPhysicalAttemptTerminalProjectionSchedulerSnapshot.v1";

        /** Enforces consistent bounded lifecycle state. */
        public Snapshot {
            schemaVersion = schemaVersion == null ? "" : schemaVersion.trim();
            policy = Objects.requireNonNull(policy, "policy");
            lastOutcome = Objects.requireNonNull(lastOutcome, "lastOutcome");
            lastLocalDisposition = Objects.requireNonNull(
                    lastLocalDisposition, "lastLocalDisposition");
            if (!SCHEMA_VERSION.equals(schemaVersion) || pollCount < 0
                    || unexpectedPollCount < 0 || unexpectedPollCount > pollCount
                    || activePolls < 0 || activePolls > policy.maximumPollers()
                    || lastOutcome.isPresent() != lastLocalDisposition.isPresent()
                    || lastPollFailed && unexpectedPollCount == 0
                    || pollCount == 0 && (lastOutcome.isPresent() || lastPollFailed)) {
                throw new IllegalArgumentException(
                        "Physical-attempt terminal projection scheduler snapshot is invalid");
            }
        }

        /**
         * Returns all stable snapshot fields as a fixed aggregate map for diagnostics.
         *
         * @return identity-free snapshot details
         */
        public Map<String, Object> details() {
            return Map.of(
                    "pollCount", pollCount,
                    "unexpectedPollCount", unexpectedPollCount,
                    "activePolls", activePolls,
                    "lastOutcome", lastOutcome.map(Enum::name).orElse("NOT_POLLED"),
                    "lastLocalDisposition",
                    lastLocalDisposition.map(Enum::name).orElse("NOT_POLLED"),
                    "lastPollFailed", lastPollFailed,
                    "closed", closed);
        }
    }
}
