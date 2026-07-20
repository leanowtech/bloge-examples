package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootPublicationService.ExecutionResult;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootPublicationService.ExecutionStatus;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One-lane bounded background scheduler for bootstrap-root publication.
 *
 * <p>The scheduler is only a process-local wake-up mechanism. Ordering, eligibility, retry time,
 * attempt budget, quarantine, and claim fencing remain database authoritative. One fixed-delay
 * daemon lane prevents local overlap, while multiple replicas safely compete through the durable
 * outbox.</p>
 *
 * <p>This scheduler does not own the publication service. Embedders close the scheduler first,
 * then the service, so no new poll can enter the publisher supervisor during shutdown.</p>
 */
public final class ExternalSequenceAnchorBootstrapRootPublicationScheduler
        implements AutoCloseable {

    private final ExternalSequenceAnchorBootstrapRootPublicationService service;
    private final String workerId;
    private final long leaseDurationSeconds;
    private final SchedulePolicy policy;
    private final ScheduledThreadPoolExecutor executor;
    private final ScheduledFuture<?> pollTask;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong pollCount = new AtomicLong();
    private final AtomicLong completionCount = new AtomicLong();
    private final AtomicLong quarantineCount = new AtomicLong();
    private final AtomicLong boundedFailureCount = new AtomicLong();
    private final AtomicLong pollFailureCount = new AtomicLong();

    private volatile boolean active;
    private volatile boolean lastPollFailed;
    private volatile ExecutionStatus lastStatus;

    /**
     * Starts one fixed-delay daemon lane with the default schedule policy.
     *
     * @param service database-fenced publication service
     * @param workerId stable pre-authenticated publisher worker identity
     * @param leaseDurationSeconds lease compatible with the service's call deadline
     */
    public ExternalSequenceAnchorBootstrapRootPublicationScheduler(
            ExternalSequenceAnchorBootstrapRootPublicationService service,
            String workerId,
            long leaseDurationSeconds) {
        this(service, workerId, leaseDurationSeconds, SchedulePolicy.DEFAULT);
    }

    /**
     * Starts one fixed-delay daemon lane with explicit bounded scheduling policy.
     *
     * @param service database-fenced publication service
     * @param workerId stable pre-authenticated publisher worker identity
     * @param leaseDurationSeconds lease compatible with the service's call deadline
     * @param policy process-local polling and shutdown bounds
     */
    public ExternalSequenceAnchorBootstrapRootPublicationScheduler(
            ExternalSequenceAnchorBootstrapRootPublicationService service,
            String workerId,
            long leaseDurationSeconds,
            SchedulePolicy policy) {
        this.service = Objects.requireNonNull(service, "service");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.leaseDurationSeconds = leaseDurationSeconds;
        this.policy = Objects.requireNonNull(policy, "policy");
        new ExternalSequenceAnchorBootstrapRootPublicationOutbox
                .PublicationAcquisitionCommand(
                ExternalSequenceAnchorBootstrapRootPublicationOutbox
                        .PublicationAcquisitionCommand.SCHEMA_VERSION,
                workerId, leaseDurationSeconds);
        if (leaseDurationSeconds < service.minimumLeaseDurationSeconds()) {
            throw new IllegalArgumentException(
                    "Bootstrap-root publication scheduler lease is shorter than its call margin");
        }
        this.executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = Thread.ofPlatform().daemon(true)
                    .name("bootstrap-root-publication").unstarted(runnable);
            thread.setUncaughtExceptionHandler((ignored, failure) -> {
                pollFailureCount.incrementAndGet();
                lastPollFailed = true;
            });
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        pollTask = executor.scheduleWithFixedDelay(this::pollSafely,
                policy.initialDelay().toNanos(), policy.pollInterval().toNanos(),
                TimeUnit.NANOSECONDS);
    }

    /**
     * Runs one synchronous poll while preserving the local one-lane invariant.
     *
     * @return one database-authoritative publication result
     */
    public synchronized ExecutionResult runOnce() {
        if (closed.get()) {
            throw new IllegalStateException(
                    "Bootstrap-root publication scheduler is closed");
        }
        active = true;
        pollCount.incrementAndGet();
        try {
            ExecutionResult result = service.publishNext(workerId, leaseDurationSeconds);
            lastPollFailed = false;
            lastStatus = result.status();
            switch (result.status()) {
                case PUBLISHED, IDEMPOTENT_REPLAY -> completionCount.incrementAndGet();
                case AUTHENTICATED_CONFLICT, QUARANTINED ->
                        quarantineCount.incrementAndGet();
                case PUBLISHER_UNAVAILABLE, RESPONSE_INVALID, RECEIPT_CONFLICT,
                        CONTROL_UNAVAILABLE -> boundedFailureCount.incrementAndGet();
                default -> {
                    // Database wait and fence outcomes are not scheduler failures.
                }
            }
            return result;
        } catch (RuntimeException failure) {
            pollFailureCount.incrementAndGet();
            lastPollFailed = true;
            throw failure;
        } finally {
            active = false;
        }
    }

    /**
     * Returns payload-free monotonic counters and current local lane state.
     *
     * @return immutable scheduler projection
     */
    public Snapshot snapshot() {
        // Read the volatile publication flag first: true then observes its preceding count update.
        boolean latestPollFailed = lastPollFailed;
        long failures = pollFailureCount.get();
        return new Snapshot(Snapshot.SCHEMA_VERSION, closed.get(), active,
                pollCount.get(), completionCount.get(), quarantineCount.get(),
                boundedFailureCount.get(), failures, latestPollFailed,
                lastStatus);
    }

    /** Stops new polls, waits for bounded drain, then interrupts the local daemon lane. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        pollTask.cancel(false);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(policy.drainTimeout().toNanos(),
                    TimeUnit.NANOSECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void pollSafely() {
        if (closed.get()) {
            return;
        }
        try {
            runOnce();
        } catch (RuntimeException failure) {
            // runOnce already recorded the bounded aggregate failure.
        } catch (Error fatal) {
            // Scheduled futures capture Error before a thread uncaught-exception handler sees it.
            pollFailureCount.incrementAndGet();
            lastPollFailed = true;
            throw fatal;
        }
    }

    /**
     * Bounded process-local scheduling policy.
     *
     * @param initialDelay delay before first poll, usable for replica rollout jitter
     * @param pollInterval fixed delay after one poll completes
     * @param drainTimeout graceful shutdown wait before interruption
     */
    public record SchedulePolicy(
            Duration initialDelay,
            Duration pollInterval,
            Duration drainTimeout) {

        /** Conservative default that leaves retry timing to the database. */
        public static final SchedulePolicy DEFAULT = new SchedulePolicy(
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5));

        /** Enforces finite scheduler and shutdown durations. */
        public SchedulePolicy {
            initialDelay = Objects.requireNonNull(initialDelay, "initialDelay");
            pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
            drainTimeout = Objects.requireNonNull(drainTimeout, "drainTimeout");
            if (initialDelay.isNegative()
                    || initialDelay.compareTo(Duration.ofDays(1)) > 0
                    || pollInterval.compareTo(Duration.ofMillis(100)) < 0
                    || pollInterval.compareTo(Duration.ofHours(1)) > 0
                    || drainTimeout.isNegative()
                    || drainTimeout.compareTo(Duration.ofMinutes(1)) > 0) {
                throw new IllegalArgumentException(
                        "Bootstrap-root publication schedule policy is invalid");
            }
        }
    }

    /**
     * Payload-free scheduler projection.
     *
     * @param schemaVersion snapshot protocol generation
     * @param closed whether new polls are rejected
     * @param active whether one local poll is in progress
     * @param pollCount started polls
     * @param completionCount published or exact-replay completions
     * @param quarantineCount conflict or already-quarantined observations
     * @param boundedFailureCount publisher, response, receipt, or control failures
     * @param pollFailureCount scheduler-level uncaught runtime failures
     * @param lastPollFailed whether the latest synchronous or scheduled poll threw
     * @param lastStatus most recent completed status, absent before completion
     */
    public record Snapshot(
            String schemaVersion,
            boolean closed,
            boolean active,
            long pollCount,
            long completionCount,
            long quarantineCount,
            long boundedFailureCount,
            long pollFailureCount,
            boolean lastPollFailed,
            ExecutionStatus lastStatus) {

        /** Current publication scheduler snapshot generation. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootPublicationSchedulerSnapshot.v2";

        /** Enforces monotonic non-negative aggregate counters. */
        public Snapshot {
            schemaVersion = schemaVersion == null ? "" : schemaVersion.trim();
            if (!SCHEMA_VERSION.equals(schemaVersion) || pollCount < 0L
                    || completionCount < 0L || quarantineCount < 0L
                    || boundedFailureCount < 0L || pollFailureCount < 0L
                    || lastPollFailed && pollFailureCount == 0L
                    || completionCount + quarantineCount + boundedFailureCount
                    > pollCount) {
                throw new IllegalArgumentException(
                        "Bootstrap-root publication scheduler snapshot is invalid");
            }
        }
    }
}
