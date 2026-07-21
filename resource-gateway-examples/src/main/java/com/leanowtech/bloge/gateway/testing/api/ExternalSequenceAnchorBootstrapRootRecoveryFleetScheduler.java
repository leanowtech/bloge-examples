package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.CycleResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One bounded fixed-delay scheduler for a process-local bootstrap-root recovery fleet worker.
 *
 * <p>The scheduler owns one daemon timer lane and serializes scheduled and explicit polls. It does
 * not rediscover work, retry a failed root set, or issue an execution fence: those decisions remain
 * inside each lane's database-backed ceremony journal. A completed fleet cycle may contain
 * isolated lane failures; only a cycle that throws is a scheduler poll failure.</p>
 *
 * <p>Health-oriented staleness is explicit. A cycle that exceeds the configured duration budget,
 * or an idle scheduler that misses a complete fixed-delay grace interval, is marked overdue. This
 * is an observation fence, not unsafe thread termination. Provider cancellation and process
 * isolation remain responsibilities of the lane runtime.</p>
 *
 * <p>The scheduler does not own the fleet worker, inventory, services, or resolvers. Close this
 * scheduler first, then the worker, and finally caller-owned lane resources.</p>
 */
public final class ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler
        implements AutoCloseable {

    private final ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker worker;
    private final SchedulePolicy policy;
    private final Clock clock;
    private final Instant startedAt;
    private final Object cycleMonitor = new Object();
    private final Object closeMonitor = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledThreadPoolExecutor executor;
    private final ScheduledFuture<?> pollTask;
    private volatile Metrics metrics = Metrics.initial();
    private boolean closeComplete;

    /**
     * Starts one fixed-delay fleet lane with conservative defaults.
     *
     * @param worker already-composed bounded recovery fleet worker
     */
    public ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker worker) {
        this(worker, SchedulePolicy.DEFAULT);
    }

    /**
     * Starts one fixed-delay fleet lane with explicit scheduling and staleness bounds.
     *
     * @param worker already-composed bounded recovery fleet worker
     * @param policy local wake-up, cycle-health, and shutdown policy
     */
    public ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker worker,
            SchedulePolicy policy) {
        this(worker, policy, Clock.systemUTC(), true);
    }

    ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker worker,
            SchedulePolicy policy,
            Clock clock,
            boolean startScheduler) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.startedAt = clock.instant();
        if (startScheduler) {
            this.executor = scheduler();
            this.pollTask = executor.scheduleWithFixedDelay(this::pollSafely,
                    policy.initialDelay().toNanos(), policy.pollInterval().toNanos(),
                    TimeUnit.NANOSECONDS);
        } else {
            this.executor = null;
            this.pollTask = null;
        }
    }

    /**
     * Runs one serialized bounded fleet cycle.
     *
     * <p>An explicit call and the background timer share the same monitor, so one process never
     * overlaps fleet cycles. Calls admitted before close finish before close returns; calls waiting
     * behind an admitted cycle observe the closed gate and fail before touching the worker.</p>
     *
     * @return exact bounded cycle result from the worker
     */
    public CycleResult runOnce() {
        requireOpen();
        synchronized (cycleMonitor) {
            requireOpen();
            Instant beganAt = clock.instant();
            metrics = metrics.started(beganAt);
            try {
                CycleResult result = Objects.requireNonNull(
                        worker.runCycle(), "fleet cycle result");
                metrics = metrics.completed(result, clock.instant());
                return result;
            } catch (RuntimeException | Error failure) {
                metrics = metrics.failed(clock.instant());
                throw failure;
            }
        }
    }

    /**
     * Returns a coherent payload-free scheduler projection without inventory access.
     *
     * @return immutable aggregate counters, latest cycle shape, and staleness state
     */
    public Snapshot snapshot() {
        Metrics observed = metrics;
        return observed.snapshot(closed.get(), overdue(observed, clock.instant()), policy);
    }

    /**
     * Closes cycle admission, waits for an admitted cycle, and stops the local timer lane.
     *
     * <p>Concurrent close callers return only after the first close has completed. The worker and
     * all lane resources remain caller-owned and are not closed here. Reentrant close from the
     * active cycle thread is rejected because it cannot satisfy the quiescence contract.</p>
     */
    @Override
    public void close() {
        if (Thread.holdsLock(cycleMonitor)) {
            throw new IllegalStateException(
                    "Bootstrap-root recovery fleet scheduler cannot close reentrantly");
        }
        if (!closed.compareAndSet(false, true)) {
            awaitCloseCompletion();
            return;
        }
        try {
            if (pollTask != null) {
                pollTask.cancel(false);
            }
            if (executor != null) {
                executor.shutdown();
            }
            synchronized (cycleMonitor) {
                // Waiting for the monitor is the admitted-cycle quiescence barrier.
            }
            if (executor != null && !executor.awaitTermination(
                    policy.drainTimeout().toNanos(), TimeUnit.NANOSECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            if (executor != null) {
                executor.shutdownNow();
            }
            Thread.currentThread().interrupt();
        } finally {
            synchronized (closeMonitor) {
                closeComplete = true;
                closeMonitor.notifyAll();
            }
        }
    }

    private void awaitCloseCompletion() {
        boolean interrupted = false;
        synchronized (closeMonitor) {
            while (!closeComplete) {
                try {
                    closeMonitor.wait();
                } catch (InterruptedException interruption) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private ScheduledThreadPoolExecutor scheduler() {
        ScheduledThreadPoolExecutor scheduled = new ScheduledThreadPoolExecutor(1, runnable ->
                Thread.ofPlatform().daemon(true)
                        .name("bootstrap-root-recovery-fleet").unstarted(runnable));
        scheduled.setRemoveOnCancelPolicy(true);
        scheduled.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduled.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return scheduled;
    }

    private void pollSafely() {
        if (closed.get()) {
            return;
        }
        try {
            runOnce();
        } catch (RuntimeException failure) {
            // runOnce already published a bounded failure; later fixed-delay polls may recover.
        } catch (Error fatal) {
            // The failure is published before the scheduled future terminates permanently.
            throw fatal;
        }
    }

    private boolean overdue(Metrics observed, Instant now) {
        if (closed.get()) {
            return false;
        }
        if (observed.active()) {
            return !now.isBefore(observed.lastPollStartedAt()
                    .plus(policy.maximumCycleDuration()));
        }
        Instant nextDue = observed.pollCount() == 0L
                ? startedAt.plus(policy.initialDelay())
                : observed.lastPollCompletedAt().plus(policy.pollInterval());
        return !now.isBefore(nextDue.plus(policy.pollInterval()));
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException(
                    "Bootstrap-root recovery fleet scheduler is closed");
        }
    }

    private record Metrics(
            boolean active,
            long pollCount,
            long completedPollCount,
            long pollFailureCount,
            boolean lastPollFailed,
            long latestInventoryGeneration,
            int latestAttemptedLanes,
            long latestAcquiredLanes,
            long latestFailedLanes,
            Instant lastPollStartedAt,
            Instant lastPollCompletedAt) {

        private static Metrics initial() {
            return new Metrics(false, 0L, 0L, 0L, false,
                    0L, 0, 0L, 0L, null, null);
        }

        private Metrics started(Instant now) {
            return new Metrics(true, pollCount + 1L, completedPollCount,
                    pollFailureCount, lastPollFailed, latestInventoryGeneration,
                    latestAttemptedLanes, latestAcquiredLanes, latestFailedLanes,
                    now, lastPollCompletedAt);
        }

        private Metrics completed(CycleResult result, Instant now) {
            Instant completedAt = completionTime(now);
            return new Metrics(false, pollCount, completedPollCount + 1L,
                    pollFailureCount, false, result.inventoryGeneration(),
                    result.attemptedCount(), result.acquiredCount(), result.failedCount(),
                    lastPollStartedAt, completedAt);
        }

        private Metrics failed(Instant now) {
            Instant completedAt = completionTime(now);
            return new Metrics(false, pollCount, completedPollCount,
                    pollFailureCount + 1L, true, latestInventoryGeneration,
                    latestAttemptedLanes, latestAcquiredLanes, latestFailedLanes,
                    lastPollStartedAt, completedAt);
        }

        private Instant completionTime(Instant now) {
            return now.isBefore(lastPollStartedAt) ? lastPollStartedAt : now;
        }

        private Snapshot snapshot(
                boolean isClosed, boolean isOverdue, SchedulePolicy policy) {
            return new Snapshot(Snapshot.SCHEMA_VERSION, isClosed, active, isOverdue,
                    pollCount, completedPollCount, pollFailureCount, lastPollFailed,
                    latestInventoryGeneration, latestAttemptedLanes,
                    latestAcquiredLanes, latestFailedLanes, latestFailedLanes > 0L,
                    lastPollStartedAt, lastPollCompletedAt,
                    policy.pollInterval().toMillis(),
                    policy.maximumCycleDuration().toMillis());
        }
    }

    /**
     * Bounded local scheduling and staleness policy.
     *
     * @param initialDelay delay before the first background cycle, usable for rollout jitter
     * @param pollInterval fixed delay after a completed background cycle
     * @param maximumCycleDuration health budget for one bounded fleet cycle
     * @param drainTimeout timer-thread termination wait after cycle quiescence
     */
    public record SchedulePolicy(
            Duration initialDelay,
            Duration pollInterval,
            Duration maximumCycleDuration,
            Duration drainTimeout) {

        /** Conservative defaults for a database-fenced fleet lane. */
        public static final SchedulePolicy DEFAULT = new SchedulePolicy(
                Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofMinutes(10), Duration.ofSeconds(5));

        /** Enforces finite wake-up, health, and shutdown bounds. */
        public SchedulePolicy {
            initialDelay = Objects.requireNonNull(initialDelay, "initialDelay");
            pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
            maximumCycleDuration = Objects.requireNonNull(
                    maximumCycleDuration, "maximumCycleDuration");
            drainTimeout = Objects.requireNonNull(drainTimeout, "drainTimeout");
            if (initialDelay.isNegative()
                    || initialDelay.compareTo(Duration.ofDays(1)) > 0
                    || pollInterval.compareTo(Duration.ofMillis(100)) < 0
                    || pollInterval.compareTo(Duration.ofHours(1)) > 0
                    || maximumCycleDuration.compareTo(Duration.ofSeconds(1)) < 0
                    || maximumCycleDuration.compareTo(Duration.ofDays(1)) > 0
                    || drainTimeout.isNegative()
                    || drainTimeout.compareTo(Duration.ofMinutes(1)) > 0) {
                throw new IllegalArgumentException(
                        "Bootstrap-root recovery fleet schedule policy is invalid");
            }
        }
    }

    /**
     * Coherent aggregate-only fleet scheduler projection.
     *
     * @param schemaVersion snapshot protocol generation
     * @param closed whether cycle admission has closed
     * @param active whether one admitted fleet cycle is running
     * @param overdue whether the timer or active cycle exceeded its health budget
     * @param pollCount admitted polls
     * @param completedPollCount polls that returned a bounded cycle result
     * @param pollFailureCount polls that threw a runtime or fatal failure
     * @param lastPollFailed whether the latest finished poll threw
     * @param latestInventoryGeneration latest successfully completed inventory generation
     * @param latestAttemptedLanes lanes visited by the latest completed cycle
     * @param latestAcquiredLanes lanes that acquired a durable attempt in that cycle
     * @param latestFailedLanes isolated runtime-failing lanes in that cycle
     * @param latestCycleHadLaneFailures whether the latest completed cycle isolated failures
     * @param lastPollStartedAt latest admitted poll time, or {@code null} before polling
     * @param lastPollCompletedAt latest poll completion time, or {@code null} while never finished
     * @param pollIntervalMillis configured fixed-delay interval
     * @param maximumCycleDurationMillis configured active-cycle health budget
     */
    public record Snapshot(
            String schemaVersion,
            boolean closed,
            boolean active,
            boolean overdue,
            long pollCount,
            long completedPollCount,
            long pollFailureCount,
            boolean lastPollFailed,
            long latestInventoryGeneration,
            int latestAttemptedLanes,
            long latestAcquiredLanes,
            long latestFailedLanes,
            boolean latestCycleHadLaneFailures,
            Instant lastPollStartedAt,
            Instant lastPollCompletedAt,
            long pollIntervalMillis,
            long maximumCycleDurationMillis) {

        /** Current fleet scheduler snapshot generation. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootRecoveryFleetSchedulerSnapshot.v1";

        /** Rejects impossible counter, lifecycle, and latest-cycle combinations. */
        public Snapshot {
            schemaVersion = schemaVersion == null ? "" : schemaVersion.trim();
            long finished = completedPollCount + pollFailureCount;
            boolean latestCycleAbsent = latestInventoryGeneration == 0L;
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || pollCount < 0L || completedPollCount < 0L || pollFailureCount < 0L
                    || finished > pollCount || pollCount - finished != (active ? 1L : 0L)
                    || lastPollFailed && pollFailureCount == 0L
                    || latestInventoryGeneration < 0L || latestAttemptedLanes < 0
                    || latestAttemptedLanes
                    > ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.MAXIMUM_LANES
                    || latestAcquiredLanes < 0L
                    || latestAcquiredLanes > latestAttemptedLanes
                    || latestFailedLanes < 0L || latestFailedLanes > latestAttemptedLanes
                    || latestAcquiredLanes + latestFailedLanes > latestAttemptedLanes
                    || latestCycleHadLaneFailures != (latestFailedLanes > 0L)
                    || latestCycleAbsent != (completedPollCount == 0L)
                    || latestCycleAbsent && (latestAttemptedLanes != 0
                    || latestAcquiredLanes != 0L || latestFailedLanes != 0L)
                    || pollCount == 0L != (lastPollStartedAt == null)
                    || finished == 0L != (lastPollCompletedAt == null)
                    || !active && lastPollStartedAt != null && lastPollCompletedAt != null
                    && lastPollCompletedAt.isBefore(lastPollStartedAt)
                    || pollIntervalMillis < 100L || maximumCycleDurationMillis < 1_000L) {
                throw new IllegalArgumentException(
                        "Bootstrap-root recovery fleet scheduler snapshot is invalid");
            }
        }
    }
}
