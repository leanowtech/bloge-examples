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
 * Bounded process-local scheduler for one durable Shadow region/environment partition.
 *
 * <p>Each lane runs one synchronous worker turn. Cross-replica admission, ordering, recovery,
 * retry, deadline, and fencing remain database-authoritative. Shutdown stops new claims, drains
 * current turns for a bounded interval, then relies on lease expiry for any interrupted owner.</p>
 */
public final class ReadOnlyShadowJobScheduler
        implements AutoCloseable {
    private static final Logger log =
            LoggerFactory.getLogger(
                    ReadOnlyShadowJobScheduler.class);
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,511}");
    private static final Set<String>
            RESERVED_PRODUCTION_ENVIRONMENTS =
            Set.of("prod", "production", "live");

    private final ReadOnlyShadowJobWorker worker;
    private final String region;
    private final String environmentId;
    private final String instanceId;
    private final Duration drainTimeout;
    private final ScheduledThreadPoolExecutor executor;
    private final List<ScheduledFuture<?>> lanes;
    private final AtomicInteger activePolls =
            new AtomicInteger();
    private final AtomicBoolean failureLogged =
            new AtomicBoolean();
    private volatile boolean closed;

    /**
     * Starts fixed-delay worker lanes for one exact non-production partition.
     *
     * @param worker owner/epoch fenced one-step worker
     * @param region exact server-owned regional partition
     * @param environmentId exact enterprise non-production partition
     * @param instanceId stable opaque replica identity
     * @param maximumPollers process-local concurrent lanes
     * @param initialDelay delay before the first poll
     * @param pollInterval fixed delay after each completed turn
     * @param drainTimeout graceful shutdown wait
     */
    public ReadOnlyShadowJobScheduler(
            ReadOnlyShadowJobWorker worker,
            String region,
            String environmentId,
            String instanceId,
            int maximumPollers,
            Duration initialDelay,
            Duration pollInterval,
            Duration drainTimeout) {
        this.worker = Objects.requireNonNull(
                worker, "worker");
        this.region = identifier(
                region, "region", 96)
                .toLowerCase(Locale.ROOT);
        this.environmentId = environment(
                environmentId);
        this.instanceId = identifier(
                instanceId, "instanceId", 255);
        if (maximumPollers < 1
                || maximumPollers > 64) {
            throw new IllegalArgumentException(
                    "Shadow scheduler pollers must be between 1 and 64");
        }
        Duration first = duration(
                initialDelay,
                Duration.ZERO,
                Duration.ofMinutes(5),
                true,
                "initialDelay");
        Duration interval = duration(
                pollInterval,
                Duration.ofMillis(100),
                Duration.ofMinutes(1),
                false,
                "pollInterval");
        this.drainTimeout = duration(
                drainTimeout,
                Duration.ofSeconds(1),
                Duration.ofHours(1),
                false,
                "drainTimeout");
        AtomicInteger threadSequence =
                new AtomicInteger();
        executor = new ScheduledThreadPoolExecutor(
                maximumPollers,
                task -> {
                    Thread thread = new Thread(
                            task,
                            "resource-gateway-shadow-"
                                    + threadSequence
                                    .incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(
                false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(
                false);
        List<ScheduledFuture<?>> scheduled =
                new ArrayList<>(maximumPollers);
        for (int lane = 0;
             lane < maximumPollers;
             lane++) {
            int laneIndex = lane;
            long stagger = Math.min(
                    Math.max(
                            0L,
                            interval.toMillis() - 1L),
                    lane * Math.max(
                            1L,
                            interval.toMillis()
                                    / maximumPollers));
            scheduled.add(
                    executor.scheduleWithFixedDelay(
                            () -> poll(laneIndex),
                            Math.addExact(
                                    first.toMillis(),
                                    stagger),
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

    /** @return current process-local worker calls */
    public int activePolls() {
        return activePolls.get();
    }

    /** @return whether every configured local lane can still schedule work */
    public boolean ready() {
        return !closed
                && !executor.isShutdown()
                && lanes.stream().noneMatch(
                future -> future.isCancelled()
                        || future.isDone());
    }

    /** Stops new claims and drains current turns for the configured bounded interval. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        lanes.forEach(
                future -> future.cancel(false));
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
            ReadOnlyShadowJobRepository.Claim claim =
                    worker.runOne(
                            region,
                            environmentId,
                            ownerId(lane));
            if (claim == null
                    && failureLogged.compareAndSet(
                    false, true)) {
                log.warn(
                        "Shadow worker returned no bounded claim; further scheduler failures are suppressed");
            }
        } catch (RuntimeException unavailable) {
            if (failureLogged.compareAndSet(
                    false, true)) {
                log.warn(
                        "Shadow scheduler poll failed before a bounded result; further failures are suppressed");
            }
        } finally {
            activePolls.decrementAndGet();
        }
    }

    private String ownerId(int lane) {
        return instanceId + "/lane-"
                + (lane + 1);
    }

    private static String environment(
            String value) {
        String normalized = identifier(
                value,
                "environmentId",
                255).toLowerCase(Locale.ROOT);
        if (RESERVED_PRODUCTION_ENVIRONMENTS
                .contains(normalized)) {
            throw new IllegalArgumentException(
                    "Shadow scheduler environment cannot be a reserved production alias");
        }
        return normalized;
    }

    private static String identifier(
            String value,
            String field,
            int maximum) {
        String normalized = value == null
                ? "" : value.trim();
        if (normalized.length() > maximum
                || !IDENTIFIER.matcher(
                normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return normalized;
    }

    private static Duration duration(
            Duration value,
            Duration minimum,
            Duration maximum,
            boolean zeroAllowed,
            String field) {
        Duration exact = Objects.requireNonNull(
                value, field);
        if (exact.isNegative()
                || !zeroAllowed && exact.isZero()
                || exact.compareTo(minimum) < 0
                || exact.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    field + " is outside the bounded Shadow scheduler policy");
        }
        return exact;
    }
}
