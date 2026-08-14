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

/** Bounded process-local scheduler over database-fenced production outcome source streams. */
public final class AuthoritativeOutcomeSourceScheduler implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(
            AuthoritativeOutcomeSourceScheduler.class);
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,511}");
    private static final Set<String> RESERVED_PRODUCTION_ENVIRONMENTS =
            Set.of("prod", "production", "live");

    private final AuthoritativeOutcomeSourceWorker worker;
    private final String region;
    private final String environmentId;
    private final String instanceId;
    private final Duration drainTimeout;
    private final ScheduledThreadPoolExecutor executor;
    private final List<ScheduledFuture<?>> lanes;
    private final AtomicInteger activePolls = new AtomicInteger();
    private final AtomicBoolean failureLogged = new AtomicBoolean();
    private volatile boolean closed;

    /** Starts fixed-delay lanes for one exact non-production mirror partition. */
    public AuthoritativeOutcomeSourceScheduler(
            AuthoritativeOutcomeSourceWorker worker,
            String region,
            String environmentId,
            String instanceId,
            int maximumPollers,
            Duration initialDelay,
            Duration pollInterval,
            Duration drainTimeout) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.region = identifier(region, "region", 96).toLowerCase(Locale.ROOT);
        this.environmentId = environment(environmentId);
        this.instanceId = identifier(instanceId, "instanceId", 255);
        if (maximumPollers < 1 || maximumPollers > 64) {
            throw new IllegalArgumentException("outcome source pollers must be between 1 and 64");
        }
        Duration first = duration(
                initialDelay, Duration.ZERO, Duration.ofMinutes(5), true, "initialDelay");
        Duration interval = duration(
                pollInterval, Duration.ofMillis(100), Duration.ofMinutes(1), false,
                "pollInterval");
        this.drainTimeout = duration(
                drainTimeout, Duration.ofSeconds(1), Duration.ofHours(1), false,
                "drainTimeout");
        AtomicInteger sequence = new AtomicInteger();
        executor = new ScheduledThreadPoolExecutor(maximumPollers, task -> {
            Thread thread = new Thread(
                    task, "resource-gateway-outcome-source-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        List<ScheduledFuture<?>> scheduled = new ArrayList<>(maximumPollers);
        for (int lane = 0; lane < maximumPollers; lane++) {
            int index = lane;
            long stagger = Math.min(
                    Math.max(0L, interval.toMillis() - 1L),
                    lane * Math.max(1L, interval.toMillis() / maximumPollers));
            scheduled.add(executor.scheduleWithFixedDelay(
                    () -> poll(index), Math.addExact(first.toMillis(), stagger),
                    interval.toMillis(), TimeUnit.MILLISECONDS));
        }
        lanes = List.copyOf(scheduled);
    }

    /** @return whether all configured local lanes can still schedule work */
    public boolean ready() {
        return !closed && !executor.isShutdown()
                && lanes.stream().noneMatch(value -> value.isCancelled() || value.isDone());
    }

    /** @return current process-local source worker turns */
    public int activePolls() {
        return activePolls.get();
    }

    /** Stops new claims and drains bounded in-flight work. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        lanes.forEach(value -> value.cancel(false));
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

    private void poll(int lane) {
        if (closed) {
            return;
        }
        activePolls.incrementAndGet();
        try {
            var claim = worker.runOne(
                    region, environmentId, instanceId + "/lane-" + (lane + 1));
            if (claim == null && failureLogged.compareAndSet(false, true)) {
                log.warn("Outcome source worker returned no bounded claim; further failures are suppressed");
            } else if (claim != null) {
                failureLogged.set(false);
            }
        } catch (RuntimeException unavailable) {
            if (failureLogged.compareAndSet(false, true)) {
                log.warn("Outcome source scheduler failed before a bounded result; further failures are suppressed");
            }
        } finally {
            activePolls.decrementAndGet();
        }
    }

    private static String environment(String value) {
        String exact = identifier(value, "environmentId", 255).toLowerCase(Locale.ROOT);
        if (RESERVED_PRODUCTION_ENVIRONMENTS.contains(exact)) {
            throw new IllegalArgumentException(
                    "outcome source scheduler cannot target a reserved production environment");
        }
        return exact;
    }

    private static String identifier(String value, String field, int maximum) {
        String exact = Objects.requireNonNullElse(value, "").trim();
        if (exact.length() > maximum || !IDENTIFIER.matcher(exact).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    private static Duration duration(
            Duration value, Duration minimum, Duration maximum,
            boolean zeroAllowed, String field) {
        Duration exact = Objects.requireNonNull(value, field);
        if (exact.isNegative() || !zeroAllowed && exact.isZero()
                || exact.compareTo(minimum) < 0 || exact.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    field + " is outside the bounded outcome source scheduler policy");
        }
        return exact;
    }
}
