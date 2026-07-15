package com.leanowtech.bloge.gateway.testing.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Maintains process-instance leases while synchronous suite cases are executing.
 *
 * <p>Heartbeat writes advance the same database fence used by checkpoints. A process crash stops
 * heartbeats naturally, while a transiently slow child remains owned. A failed heartbeat marks the
 * local guard uncertain so the runner will not schedule another child.</p>
 */
public final class TestSuiteRunLeaseCoordinator implements AutoCloseable {
    private final TestSuiteRunRepository repository;
    private final String ownerId;
    private final Duration leaseDuration;
    private final Duration heartbeatInterval;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;

    /**
     * Creates an active process-wide coordinator with a single daemon heartbeat thread.
     *
     * @param repository durable lease and checkpoint store
     * @param ownerId process-instance owner id; blank generates a UUID-backed id
     * @param leaseDuration ownership duration renewed by heartbeats and checkpoints
     * @param heartbeatInterval delay between ownership renewals
     */
    public TestSuiteRunLeaseCoordinator(TestSuiteRunRepository repository, String ownerId,
                                        Duration leaseDuration, Duration heartbeatInterval) {
        this(repository, ownerId, leaseDuration, heartbeatInterval, null, true);
    }

    TestSuiteRunLeaseCoordinator(TestSuiteRunRepository repository, String ownerId,
                                 Duration leaseDuration, Duration heartbeatInterval,
                                 Clock clock, boolean active) {
        this.repository = active ? Objects.requireNonNull(repository, "repository") : repository;
        this.ownerId = normalized(ownerId).isBlank()
                ? "suite-runner-" + UUID.randomUUID() : normalized(ownerId);
        this.leaseDuration = validDuration(leaseDuration, Duration.ofSeconds(30));
        this.heartbeatInterval = validDuration(heartbeatInterval, Duration.ofSeconds(5));
        if (active && this.heartbeatInterval.compareTo(this.leaseDuration) >= 0) {
            throw new IllegalArgumentException("Suite-run heartbeat interval must be shorter than its lease");
        }
        this.clock = clock;
        this.scheduler = active ? Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "resource-gateway-suite-run-heartbeat");
            thread.setDaemon(true);
            return thread;
        }) : null;
    }

    /**
     * Creates a non-scheduling coordinator for deterministic unit tests and embedded direct use.
     * Production composition uses the active constructor.
     *
     * @param leaseDuration lease duration attached to direct in-process writes
     * @return coordinator that creates leases but schedules no heartbeat
     */
    public static TestSuiteRunLeaseCoordinator passive(Duration leaseDuration) {
        return new TestSuiteRunLeaseCoordinator(null, "in-process-suite-runner", leaseDuration,
                Duration.ofSeconds(1), Clock.systemUTC(), false);
    }

    /** Creates a fresh lease to persist atomically with the initial RUNNING checkpoint.
     *
     * @return same-process ownership with a new database-authoritative deadline
     */
    public TestSuiteRunLease newLease() {
        return newLease(now());
    }

    /**
     * Starts periodic ownership renewal for an already-created suite run.
     *
     * @param record scoped suite-run identity to renew
     * @return closeable local ownership guard
     */
    public LeaseGuard monitor(TestSuiteRunRecord record) {
        Objects.requireNonNull(record, "record");
        LeaseGuard guard = new LeaseGuard(record);
        if (scheduler != null) {
            guard.future = scheduler.scheduleWithFixedDelay(guard::heartbeat,
                    heartbeatInterval.toMillis(), heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
        }
        return guard;
    }

    /** Stops accepting new heartbeat tasks and interrupts no caller-owned suite execution. */
    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** One locally monitored ownership claim. */
    public final class LeaseGuard implements AutoCloseable {
        private final TestSuiteRunRecord record;
        private final AtomicBoolean held = new AtomicBoolean(true);
        private volatile ScheduledFuture<?> future;

        private LeaseGuard(TestSuiteRunRecord record) {
            this.record = record;
        }

        /** Reports the latest locally observed ownership state.
         *
         * @return {@code true} until a heartbeat proves ownership lost or uncertain
         */
        public boolean held() {
            return held.get();
        }

        /** Creates a fresh same-owner lease for a checkpoint or terminal compare-and-set.
         *
         * @return renewable ownership claim
         */
        public TestSuiteRunLease renewal() {
            return newLease();
        }

        private void heartbeat() {
            try {
                Instant observedAt = now();
                TestSuiteRunLease renewal = newLease(observedAt);
                if (!repository.renewLease(record.tenantId(), record.environmentId(),
                        record.suiteRunId(), renewal.ownerId(), renewal.expiresAt(), observedAt)) {
                    held.set(false);
                    close();
                }
            } catch (RuntimeException unavailable) {
                held.set(false);
                close();
            }
        }

        /** Stops only this run's heartbeat. */
        @Override
        public void close() {
            ScheduledFuture<?> scheduled = future;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
        }
    }

    private static Duration validDuration(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private Instant now() {
        return clock == null ? repository.currentTime() : clock.instant();
    }

    private TestSuiteRunLease newLease(Instant observedAt) {
        return new TestSuiteRunLease(ownerId, observedAt.plus(leaseDuration));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
