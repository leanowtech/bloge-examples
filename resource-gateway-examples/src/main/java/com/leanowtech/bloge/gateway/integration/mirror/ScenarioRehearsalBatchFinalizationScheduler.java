package com.leanowtech.bloge.gateway.integration.mirror;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Bounded process-local scheduler for one regional batch-evidence finalization partition.
 *
 * <p>This scheduler is intentionally separate from DAG execution lanes. Slow or unavailable KMS
 * and verification dependencies therefore consume only the explicitly configured finalization
 * budget. Cross-replica ownership, retries, stale takeover, and quarantine remain
 * database-authoritative.</p>
 */
public final class ScenarioRehearsalBatchFinalizationScheduler
        implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(
            ScenarioRehearsalBatchFinalizationScheduler.class);
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

    private final ScenarioRehearsalBatchFinalizationWorker worker;
    private final String region;
    private final String environmentId;
    private final String instanceId;
    private final Duration drainTimeout;
    private final ScheduledThreadPoolExecutor executor;
    private final List<ScheduledFuture<?>> lanes;
    private final AtomicInteger activePolls = new AtomicInteger();
    private final AtomicBoolean failureLogged = new AtomicBoolean();
    private volatile boolean closed;

    /**
     * Starts bounded fixed-delay finalization lanes for one exact non-production partition.
     *
     * @param worker one durable finalization turn
     * @param region exact server-owned regional partition
     * @param environmentId exact test or staging partition
     * @param instanceId stable opaque replica identity
     * @param maximumPollers maximum concurrent local KMS preparations
     * @param initialDelay delay before the first poll
     * @param pollInterval fixed delay after each completed turn
     * @param drainTimeout graceful shutdown bound
     */
    public ScenarioRehearsalBatchFinalizationScheduler(
            ScenarioRehearsalBatchFinalizationWorker worker,
            String region,
            String environmentId,
            String instanceId,
            int maximumPollers,
            Duration initialDelay,
            Duration pollInterval,
            Duration drainTimeout) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.region = identifier(
                region, "region", 64).toLowerCase(Locale.ROOT);
        this.environmentId = nonProductionEnvironment(
                environmentId);
        this.instanceId = identifier(
                instanceId, "instanceId", 255);
        if (maximumPollers < 1 || maximumPollers > 32) {
            throw new IllegalArgumentException(
                    "Scenario batch finalization pollers must be between 1 and 32");
        }
        Duration firstDelay = bounded(
                initialDelay,
                "initialDelay",
                Duration.ZERO,
                Duration.ofMinutes(5),
                true);
        Duration interval = bounded(
                pollInterval,
                "pollInterval",
                Duration.ofMillis(100),
                Duration.ofMinutes(1),
                false);
        this.drainTimeout = bounded(
                drainTimeout,
                "drainTimeout",
                Duration.ofSeconds(1),
                Duration.ofHours(1),
                false);
        AtomicInteger sequence = new AtomicInteger();
        executor = new ScheduledThreadPoolExecutor(
                maximumPollers,
                task -> {
                    Thread thread = new Thread(
                            task,
                            "resource-gateway-scenario-batch-finalizer-"
                                    + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        List<ScheduledFuture<?>> scheduled =
                new ArrayList<>(maximumPollers);
        for (int lane = 0; lane < maximumPollers; lane++) {
            int laneIndex = lane;
            long staggerMillis = Math.min(
                    Math.max(0L, interval.toMillis() - 1L),
                    lane * Math.max(
                            1L,
                            interval.toMillis()
                                    / maximumPollers));
            scheduled.add(
                    executor.scheduleWithFixedDelay(
                            () -> poll(laneIndex),
                            Math.addExact(
                                    firstDelay.toMillis(),
                                    staggerMillis),
                            interval.toMillis(),
                            TimeUnit.MILLISECONDS));
        }
        lanes = List.copyOf(scheduled);
    }

    /** @return exact region served by this scheduler */
    public String region() {
        return region;
    }

    /** @return exact environment served by this scheduler */
    public String environmentId() {
        return environmentId;
    }

    /** @return current process-local KMS preparation calls */
    public int activePolls() {
        return activePolls.get();
    }

    /** @return whether every configured lane remains schedulable */
    public boolean ready() {
        return !closed
                && !executor.isShutdown()
                && lanes.stream().noneMatch(
                future -> future.isCancelled()
                        || future.isDone());
    }

    /** Stops new claims and drains current finalization attempts within the configured bound. */
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
                    drainTimeout.toMillis(),
                    TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(
                        Math.min(
                                1_000L,
                                drainTimeout.toMillis()),
                        TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void poll(int lane) {
        if (closed) {
            return;
        }
        activePolls.incrementAndGet();
        try {
            ScenarioRehearsalBatchFinalizationWorker.Turn turn =
                    worker.runOnce(
                            region,
                            environmentId,
                            ownerId(lane));
            if (turn.disposition()
                    == ScenarioRehearsalBatchFinalizationWorker
                    .Disposition.CONTROL_UNAVAILABLE
                    && failureLogged.compareAndSet(false, true)) {
                log.warn(
                        "Scenario batch finalization control is unavailable; "
                                + "further scheduler failures are suppressed");
            }
        } catch (RuntimeException unavailable) {
            if (failureLogged.compareAndSet(false, true)) {
                log.warn(
                        "Scenario batch finalization scheduler poll failed; "
                                + "further failures are suppressed");
            }
        } finally {
            activePolls.decrementAndGet();
        }
    }

    private String ownerId(int lane) {
        return instanceId + "/finalizer-" + (lane + 1);
    }

    private static String nonProductionEnvironment(
            String value) {
        String normalized = identifier(
                value,
                "environmentId",
                255).toLowerCase(Locale.ROOT);
        if (!Set.of("test", "staging").contains(normalized)) {
            throw new IllegalArgumentException(
                    "Scenario batch finalization scheduler environment must be test or staging");
        }
        return normalized;
    }

    private static String identifier(
            String value,
            String field,
            int maximumLength) {
        String normalized =
                value == null ? "" : value.trim();
        if (normalized.length() > maximumLength
                || !IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return normalized;
    }

    private static Duration bounded(
            Duration value,
            String field,
            Duration minimum,
            Duration maximum,
            boolean zeroAllowed) {
        Duration result = Objects.requireNonNull(
                value, field);
        if (result.isNegative()
                || !zeroAllowed && result.isZero()
                || result.compareTo(minimum) < 0
                || result.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    field + " is outside the bounded finalization scheduler policy");
        }
        return result;
    }
}
