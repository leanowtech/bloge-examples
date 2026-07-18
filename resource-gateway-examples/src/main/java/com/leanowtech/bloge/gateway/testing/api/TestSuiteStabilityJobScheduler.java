package com.leanowtech.bloge.gateway.testing.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded process-local scheduler for the single-poll suite-stability worker.
 *
 * <p>Each lane owns one fixed-delay loop and therefore at most one synchronous worker call. A
 * graceful close stops new polls, permits current calls to drain for a bounded interval, then
 * interrupts remaining threads. Queue and parent fencing remain authoritative when an operator
 * ignores interruption.</p>
 */
public final class TestSuiteStabilityJobScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(
            TestSuiteStabilityJobScheduler.class);

    private final TestSuiteStabilityJobWorker worker;
    private final Duration drainTimeout;
    private final ScheduledThreadPoolExecutor executor;
    private final List<ScheduledFuture<?>> lanes;
    private final AtomicInteger activePolls = new AtomicInteger();
    private volatile boolean closed;

    /**
     * Starts bounded fixed-delay worker lanes immediately after construction.
     *
     * @param worker fully guarded single-poll worker
     * @param environments enabled exact non-production queues
     * @param maximumPollers process-local polling lanes
     * @param initialDelay delay before the first poll in each lane
     * @param pollInterval fixed delay after one lane finishes a poll
     * @param drainTimeout graceful shutdown wait before interruption
     */
    public TestSuiteStabilityJobScheduler(
            TestSuiteStabilityJobWorker worker,
            Set<String> environments,
            int maximumPollers,
            Duration initialDelay,
            Duration pollInterval,
            Duration drainTimeout) {
        this.worker = Objects.requireNonNull(worker, "worker");
        List<String> queues = environments(environments);
        if (maximumPollers <= 0 || maximumPollers > 1_024) {
            throw new IllegalArgumentException(
                    "Stability worker pollers must be between 1 and 1024");
        }
        if (maximumPollers < queues.size()) {
            throw new IllegalArgumentException(
                    "Every enabled stability queue requires at least one polling lane");
        }
        Duration firstDelay = bounded(initialDelay, "initialDelay",
                Duration.ZERO, Duration.ofMinutes(5), true);
        Duration interval = bounded(pollInterval, "pollInterval",
                Duration.ofMillis(100), Duration.ofMinutes(1), false);
        this.drainTimeout = bounded(drainTimeout, "drainTimeout",
                Duration.ofSeconds(1), Duration.ofHours(1), false);
        AtomicInteger threadSequence = new AtomicInteger();
        executor = new ScheduledThreadPoolExecutor(maximumPollers, task -> {
            Thread thread = new Thread(task,
                    "resource-gateway-stability-worker-" + threadSequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        List<ScheduledFuture<?>> scheduled = new ArrayList<>(maximumPollers);
        for (int lane = 0; lane < maximumPollers; lane++) {
            String environment = queues.get(lane % queues.size());
            long staggerMillis = Math.min(interval.toMillis() - 1L,
                    lane * Math.max(1L, interval.toMillis() / maximumPollers));
            scheduled.add(executor.scheduleWithFixedDelay(
                    () -> poll(environment),
                    Math.addExact(firstDelay.toMillis(), staggerMillis),
                    interval.toMillis(), TimeUnit.MILLISECONDS));
        }
        lanes = List.copyOf(scheduled);
    }

    /** @return current local worker calls, never a queue cardinality */
    public int activePolls() {
        return activePolls.get();
    }

    /** @return whether shutdown has forbidden all future scheduling */
    public boolean closed() {
        return closed;
    }

    /**
     * Stops new polls and waits a bounded interval for current calls to return.
     *
     * <p>Interruption after the drain deadline is best effort. Durable lease expiry and fencing,
     * not Java thread interruption, prevent stale publication.</p>
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
            if (!executor.awaitTermination(drainTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(
                        Math.min(1_000L, drainTimeout.toMillis()), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void poll(String environment) {
        if (closed) {
            return;
        }
        activePolls.incrementAndGet();
        try {
            worker.processNext(environment);
        } catch (RuntimeException failure) {
            log.warn("Suite-stability worker poll failed before a bounded result was produced");
        } finally {
            activePolls.decrementAndGet();
        }
    }

    private static List<String> environments(Set<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            values.stream()
                    .map(value -> value == null ? "" : value.trim().toLowerCase(
                            java.util.Locale.ROOT))
                    .filter(value -> !value.isBlank())
                    .forEach(normalized::add);
        }
        if (normalized.isEmpty()
                || !Set.of("test", "staging").containsAll(normalized)) {
            throw new IllegalArgumentException(
                    "Stability scheduler environments must be test and/or staging");
        }
        return List.copyOf(normalized);
    }

    private static Duration bounded(
            Duration value,
            String name,
            Duration minimum,
            Duration maximum,
            boolean inclusiveZero) {
        Duration result = Objects.requireNonNull(value, name);
        if (result.isNegative()
                || !inclusiveZero && result.isZero()
                || result.compareTo(minimum) < 0
                || result.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    name + " is outside the bounded scheduler policy");
        }
        return result;
    }
}
