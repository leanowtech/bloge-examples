package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyService.RecoveryExecutionResult;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyService.RecoveryStatus;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One-lane bounded background scheduler for bootstrap-root ceremony recovery.
 *
 * <p>Every poll delegates discovery and acquisition to the database-atomic recovery operation, so
 * this process-local timer is never the retry or fencing authority. A single fixed-delay daemon lane
 * prevents local overlap; multiple replicas remain safe because only the database can issue the
 * execution fence. Poll failures are counted without retaining exception text, proposal identity,
 * signer identity, payload, or provider endpoint.</p>
 *
 * <p>The scheduler does not own the ceremony service. Embedders must close this scheduler first and
 * then close the service so no new poll can enter heartbeat or signer supervisors during shutdown.</p>
 */
public final class ExternalSequenceAnchorBootstrapRootCeremonyRecoveryScheduler
        implements AutoCloseable {

    private final ExternalSequenceAnchorBootstrapRootCeremonyService service;
    private final String workerId;
    private final long leaseDurationSeconds;
    private final ExternalSequenceAnchorBootstrapRootAuthorityResolver authorityResolver;
    private final SchedulePolicy policy;
    private final ScheduledThreadPoolExecutor executor;
    private final ScheduledFuture<?> pollTask;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong pollCount = new AtomicLong();
    private final AtomicLong executedCount = new AtomicLong();
    private final AtomicLong pollFailureCount = new AtomicLong();
    private volatile boolean active;
    private volatile RecoveryStatus lastStatus;

    /**
     * Starts one fixed-delay daemon lane with the default schedule policy.
     *
     * @param service durable ceremony coordinator
     * @param workerId stable pre-authenticated recovery worker identity
     * @param leaseDurationSeconds auto-renewed database lease from 3 through 300 seconds
     * @param authorityResolver exact approved-cohort runtime adapter resolver
     */
    public ExternalSequenceAnchorBootstrapRootCeremonyRecoveryScheduler(
            ExternalSequenceAnchorBootstrapRootCeremonyService service,
            String workerId,
            long leaseDurationSeconds,
            ExternalSequenceAnchorBootstrapRootAuthorityResolver authorityResolver) {
        this(service, workerId, leaseDurationSeconds, authorityResolver,
                SchedulePolicy.DEFAULT);
    }

    /**
     * Starts one fixed-delay daemon lane with an explicit bounded schedule policy.
     *
     * @param service durable ceremony coordinator
     * @param workerId stable pre-authenticated recovery worker identity
     * @param leaseDurationSeconds auto-renewed database lease from 3 through 300 seconds
     * @param authorityResolver exact approved-cohort runtime adapter resolver
     * @param policy process-local polling and shutdown bounds
     */
    public ExternalSequenceAnchorBootstrapRootCeremonyRecoveryScheduler(
            ExternalSequenceAnchorBootstrapRootCeremonyService service,
            String workerId,
            long leaseDurationSeconds,
            ExternalSequenceAnchorBootstrapRootAuthorityResolver authorityResolver,
            SchedulePolicy policy) {
        this.service = Objects.requireNonNull(service, "service");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.leaseDurationSeconds = leaseDurationSeconds;
        this.authorityResolver = Objects.requireNonNull(
                authorityResolver, "authorityResolver");
        this.policy = Objects.requireNonNull(policy, "policy");
        new ExternalSequenceAnchorBootstrapRootCeremonyJournal.RecoveryAcquisitionCommand(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.RecoveryAcquisitionCommand
                        .SCHEMA_VERSION,
                workerId, leaseDurationSeconds);
        if (leaseDurationSeconds < 3L) {
            throw new IllegalArgumentException(
                    "Ceremony auto-heartbeat lease must be at least three seconds");
        }
        this.executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = Thread.ofPlatform().daemon(true)
                    .name("bootstrap-root-ceremony-recovery").unstarted(runnable);
            thread.setUncaughtExceptionHandler((ignored, failure) ->
                    pollFailureCount.incrementAndGet());
            return thread;
        });
        this.executor.setRemoveOnCancelPolicy(true);
        this.executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        this.pollTask = executor.scheduleWithFixedDelay(this::pollSafely,
                policy.initialDelay().toNanos(), policy.pollInterval().toNanos(),
                TimeUnit.NANOSECONDS);
    }

    /**
     * Runs one synchronous poll, primarily for deterministic embedding and verification.
     *
     * <p>The method is synchronized with the background lane, preserving one local recovery call at
     * a time. It remains subject to the ceremony service's signer deadlines and lease fence.</p>
     *
     * @return database-authoritative wait or execution result
     */
    public synchronized RecoveryExecutionResult runOnce() {
        if (closed.get()) {
            throw new IllegalStateException("Bootstrap-root recovery scheduler is closed");
        }
        active = true;
        pollCount.incrementAndGet();
        try {
            RecoveryExecutionResult result = service.recover(
                    workerId, leaseDurationSeconds, authorityResolver);
            lastStatus = result.status();
            if (result.status() == RecoveryStatus.EXECUTED) {
                executedCount.incrementAndGet();
            }
            return result;
        } finally {
            active = false;
        }
    }

    /**
     * Returns a payload-free process-local operational projection.
     *
     * @return immutable counters and current lane state
     */
    public Snapshot snapshot() {
        return new Snapshot(closed.get(), active, pollCount.get(), executedCount.get(),
                pollFailureCount.get(), lastStatus);
    }

    /** Stops new polls, waits for a bounded drain, then interrupts the local daemon lane. */
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
            pollFailureCount.incrementAndGet();
        }
    }

    /**
     * Bounded process-local scheduling policy.
     *
     * @param initialDelay delay before the first poll, usable for replica rollout jitter
     * @param pollInterval fixed delay after a poll completes
     * @param drainTimeout graceful shutdown wait before interruption
     */
    public record SchedulePolicy(
            Duration initialDelay,
            Duration pollInterval,
            Duration drainTimeout) {

        /** Conservative default that leaves retry timing to the database policy. */
        public static final SchedulePolicy DEFAULT = new SchedulePolicy(
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5));

        /** Enforces finite scheduler and shutdown durations. */
        public SchedulePolicy {
            initialDelay = Objects.requireNonNull(initialDelay, "initialDelay");
            pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
            drainTimeout = Objects.requireNonNull(drainTimeout, "drainTimeout");
            if (initialDelay.isNegative() || initialDelay.compareTo(Duration.ofDays(1)) > 0
                    || pollInterval.compareTo(Duration.ofMillis(100)) < 0
                    || pollInterval.compareTo(Duration.ofHours(1)) > 0
                    || drainTimeout.isNegative()
                    || drainTimeout.compareTo(Duration.ofMinutes(1)) > 0) {
                throw new IllegalArgumentException(
                        "Bootstrap-root recovery schedule policy is invalid");
            }
        }
    }

    /**
     * Payload-free scheduler snapshot.
     *
     * @param closed whether this scheduler rejects new polls
     * @param active whether one local recovery call is in progress
     * @param pollCount started polls
     * @param executedCount polls that acquired and ran an attempt
     * @param pollFailureCount scheduler-level uncaught runtime failures
     * @param lastStatus most recent completed service status, or {@code null} before completion
     */
    public record Snapshot(
            boolean closed,
            boolean active,
            long pollCount,
            long executedCount,
            long pollFailureCount,
            RecoveryStatus lastStatus) {

        /** Enforces monotonic non-negative aggregate counters. */
        public Snapshot {
            if (pollCount < 0L || executedCount < 0L || pollFailureCount < 0L
                    || executedCount > pollCount) {
                throw new IllegalArgumentException(
                        "Bootstrap-root recovery scheduler snapshot is invalid");
            }
        }
    }
}
