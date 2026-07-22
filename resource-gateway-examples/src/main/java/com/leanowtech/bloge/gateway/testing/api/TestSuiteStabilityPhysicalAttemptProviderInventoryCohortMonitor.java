package com.leanowtech.bloge.gateway.testing.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns one process-start lease in the signed physical provider-inventory cohort.
 *
 * <p>Construction performs an immediate heartbeat before background scheduling. Shutdown removes
 * only this process-start row, preserving collision visibility for another live process claiming
 * the same replica identity. Background failures do not invent readiness; the durable lease
 * naturally expires and subsequent gate reads remain authoritative.</p>
 */
public final class TestSuiteStabilityPhysicalAttemptProviderInventoryCohortMonitor
        implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(
            TestSuiteStabilityPhysicalAttemptProviderInventoryCohortMonitor.class);

    private final TestSuiteStabilityPhysicalAttemptProviderInventoryCohortRepository repository;
    private final String startupId;
    private final Duration heartbeatInterval;
    private final ScheduledThreadPoolExecutor scheduler;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean heartbeatFailureLogged = new AtomicBoolean();

    /**
     * Registers this process start and begins fixed-delay lease renewal.
     *
     * @param repository durable cohort repository
     * @param heartbeatInterval bounded renewal interval shorter than the repository lease
     */
    public TestSuiteStabilityPhysicalAttemptProviderInventoryCohortMonitor(
            TestSuiteStabilityPhysicalAttemptProviderInventoryCohortRepository repository,
            Duration heartbeatInterval) {
        this(repository, heartbeatInterval, UUID.randomUUID().toString(), true);
    }

    TestSuiteStabilityPhysicalAttemptProviderInventoryCohortMonitor(
            TestSuiteStabilityPhysicalAttemptProviderInventoryCohortRepository repository,
            Duration heartbeatInterval,
            String startupId,
            boolean startScheduler) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.heartbeatInterval = bounded(heartbeatInterval, repository.leaseDuration());
        this.startupId = normalizedStartupId(startupId);
        repository.heartbeat(this.startupId);
        this.scheduler = startScheduler ? scheduler() : null;
    }

    /** Returns the private process-start identity for lifecycle diagnostics and tests. */
    String startupId() {
        return startupId;
    }

    /** Renews synchronously; intended for deterministic runtime probes and tests. */
    boolean heartbeatNow() {
        if (closed.get()) {
            return false;
        }
        repository.heartbeat(startupId);
        heartbeatFailureLogged.set(false);
        return true;
    }

    /** Withdraws this exact process start and terminates background renewal. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(
                        Math.min(1_000L, heartbeatInterval.toMillis()),
                        TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        repository.withdraw(startupId);
    }

    private ScheduledThreadPoolExecutor scheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task,
                    "resource-gateway-physical-provider-cohort-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        long intervalMillis = heartbeatInterval.toMillis();
        long initialDelayMillis = ThreadLocalRandom.current().nextLong(
                Math.max(1L, intervalMillis / 2L), intervalMillis + 1L);
        executor.scheduleWithFixedDelay(this::heartbeatSafely, initialDelayMillis,
                intervalMillis, TimeUnit.MILLISECONDS);
        return executor;
    }

    private void heartbeatSafely() {
        try {
            heartbeatNow();
        } catch (RuntimeException failure) {
            if (heartbeatFailureLogged.compareAndSet(false, true)) {
                log.warn("Physical provider-inventory cohort heartbeat failed; "
                        + "the durable local lease will expire unless renewal recovers");
            }
        }
    }

    private static Duration bounded(Duration value, Duration leaseDuration) {
        Duration interval = Objects.requireNonNull(value, "heartbeatInterval");
        Duration lease = Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (interval.compareTo(Duration.ofMillis(250)) < 0
                || interval.compareTo(Duration.ofMinutes(5)) > 0
                || interval.multipliedBy(2).compareTo(lease) > 0) {
            throw new IllegalArgumentException(
                    "Physical provider cohort heartbeat interval is invalid");
        }
        return interval;
    }

    private static String normalizedStartupId(String value) {
        try {
            return UUID.fromString(Objects.requireNonNullElse(value, "").trim()).toString();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Physical provider cohort startup identity is invalid", invalid);
        }
    }
}
