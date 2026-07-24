package com.leanowtech.bloge.gateway.integration.mirror;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Bounded process-local scheduler for one regional Scenario rehearsal batch partition.
 *
 * <p>Each lane performs one synchronous worker turn at a time. Cross-replica capacity, tenant
 * fairness, retry, recovery, cancellation, and stale-owner fencing remain database-authoritative;
 * this scheduler only supplies bounded local concurrency and lifecycle management. Closing first
 * prevents new claims, then allows current turns to drain for a bounded interval. Interruption
 * after that interval is best effort and never replaces the durable owner/epoch/expiry fence.</p>
 */
public final class ScenarioRehearsalBatchScheduler
        implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(
            ScenarioRehearsalBatchScheduler.class);
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

    private final ScenarioRehearsalBatchWorker worker;
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
     * Starts bounded fixed-delay lanes for one exact region and non-production environment.
     *
     * @param worker evidence-verifying single-item worker
     * @param region exact server-owned regional partition
     * @param environmentId exact {@code test} or {@code staging} partition
     * @param instanceId stable opaque replica identity
     * @param maximumPollers process-local concurrent lanes
     * @param initialDelay delay before the first poll
     * @param pollInterval fixed delay after each completed turn
     * @param drainTimeout graceful shutdown wait before best-effort interruption
     */
    public ScenarioRehearsalBatchScheduler(
            ScenarioRehearsalBatchWorker worker,
            String region,
            String environmentId,
            String instanceId,
            int maximumPollers,
            Duration initialDelay,
            Duration pollInterval,
            Duration drainTimeout) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.region = boundedIdentifier(
                region, "region", 64).toLowerCase(Locale.ROOT);
        this.environmentId = nonProductionEnvironment(
                environmentId);
        this.instanceId = boundedIdentifier(
                instanceId, "instanceId", 255);
        if (maximumPollers < 1 || maximumPollers > 256) {
            throw new IllegalArgumentException(
                    "Scenario batch scheduler pollers must be between 1 and 256");
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
        AtomicInteger threadSequence = new AtomicInteger();
        executor = new ScheduledThreadPoolExecutor(
                maximumPollers,
                task -> {
                    Thread thread = new Thread(
                            task,
                            "resource-gateway-scenario-batch-"
                                    + threadSequence.incrementAndGet());
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

    /** @return exact regional partition served by this process */
    public String region() {
        return region;
    }

    /** @return exact non-production environment served by this process */
    public String environmentId() {
        return environmentId;
    }

    /** @return current local worker calls, never durable queue cardinality */
    public int activePolls() {
        return activePolls.get();
    }

    /** @return whether all configured lanes can still schedule work */
    public boolean ready() {
        return !closed
                && !executor.isShutdown()
                && lanes.stream().noneMatch(
                future -> future.isCancelled()
                        || future.isDone());
    }

    /**
     * Stops new claims and waits a bounded interval for already claimed turns.
     *
     * <p>A timed-out close does not invent a retry or terminal result. The database lease
     * eventually permits another replica to recover the stable child request.</p>
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
            ScenarioRehearsalBatchWorker.Turn turn =
                    worker.runOnce(
                            region,
                            environmentId,
                            ownerId(lane));
            if (turn == null
                    && failureLogged.compareAndSet(false, true)) {
                log.warn(
                        "Scenario batch worker returned no bounded turn result; "
                                + "further scheduler failures are suppressed");
            }
        } catch (RuntimeException unavailable) {
            if (failureLogged.compareAndSet(false, true)) {
                log.warn(
                        "Scenario batch scheduler poll failed before a bounded "
                                + "turn result was produced; further failures are suppressed");
            }
        } finally {
            activePolls.decrementAndGet();
        }
    }

    private String ownerId(int lane) {
        return instanceId + "/lane-" + (lane + 1);
    }

    private static String nonProductionEnvironment(
            String value) {
        String normalized = boundedIdentifier(
                value,
                "environmentId",
                255).toLowerCase(Locale.ROOT);
        if (!java.util.Set.of(
                "test", "staging").contains(normalized)) {
            throw new IllegalArgumentException(
                    "Scenario batch scheduler environment must be test or staging");
        }
        return normalized;
    }

    private static String boundedIdentifier(
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
                    field + " is outside the bounded Scenario batch scheduler policy");
        }
        return result;
    }
}
