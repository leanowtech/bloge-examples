package com.leanowtech.bloge.gateway.testing.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Maintains cross-replica ownership while one synchronous stability horizon is executing.
 *
 * <p>Background renewal protects long child attempts. {@link LeaseGuard#checkpoint()} performs an
 * additional synchronous database renewal before terminal publication, closing the window where
 * a local heartbeat has not yet observed expiry and takeover. A failed or ambiguous renewal is
 * terminal for the local guard.</p>
 */
public final class TestSuiteStabilityLeaseCoordinator implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(
            TestSuiteStabilityLeaseCoordinator.class);

    private final TestSuiteStabilityRunRepository repository;
    private final Duration leaseDuration;
    private final Duration heartbeatInterval;
    private final String processOwnerPrefix;
    private final ScheduledExecutorService scheduler;
    private final Object lifecycleMonitor = new Object();
    private final Set<LeaseGuard> activeGuards =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    /**
     * Creates an active process-wide coordinator with one daemon heartbeat thread.
     *
     * @param repository database-authoritative stability store
     * @param processOwnerId stable process-instance identity; blank generates an opaque UUID
     * @param leaseDuration database-clock ownership duration
     * @param heartbeatInterval fixed delay between renewals, at most one-third of the lease
     */
    public TestSuiteStabilityLeaseCoordinator(
            TestSuiteStabilityRunRepository repository,
            String processOwnerId,
            Duration leaseDuration,
            Duration heartbeatInterval) {
        this(repository, processOwnerId, leaseDuration, heartbeatInterval, true);
    }

    private TestSuiteStabilityLeaseCoordinator(
            TestSuiteStabilityRunRepository repository,
            String processOwnerId,
            Duration leaseDuration,
            Duration heartbeatInterval,
            boolean active) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.leaseDuration = boundedLease(leaseDuration);
        this.heartbeatInterval = boundedHeartbeat(heartbeatInterval, this.leaseDuration);
        String normalizedOwner = processOwnerId == null ? "" : processOwnerId.trim();
        this.processOwnerPrefix = normalizedOwner.isBlank()
                ? "stability-runner-" + UUID.randomUUID() : normalizedOwner;
        if (processOwnerPrefix.length() > 210
                || !processOwnerPrefix.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]*")) {
            throw new IllegalArgumentException("Stability process owner id is invalid");
        }
        this.scheduler = active ? Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "resource-gateway-stability-heartbeat");
            thread.setDaemon(true);
            return thread;
        }) : null;
    }

    /**
     * Creates a non-scheduling coordinator for deterministic focused tests.
     *
     * <p>The terminal checkpoint remains a real synchronous repository renewal; only periodic
     * background timing is disabled.</p>
     *
     * @param repository repository exercised by the service under test
     * @param leaseDuration bounded test lease
     * @return passive coordinator
     */
    public static TestSuiteStabilityLeaseCoordinator passive(
            TestSuiteStabilityRunRepository repository,
            Duration leaseDuration) {
        return new TestSuiteStabilityLeaseCoordinator(repository, "test-stability-runner",
                leaseDuration, Duration.ofSeconds(1), false);
    }

    /**
     * Creates an opaque owner unique to one local HTTP invocation.
     *
     * @return bounded owner identity suitable for a lease request
     */
    public String newInvocationOwner() {
        return processOwnerPrefix + '-' + UUID.randomUUID();
    }

    /**
     * Binds one exact stability intent to a fresh local invocation owner and configured duration.
     *
     * @param stabilityRunId deterministic parent run identity
     * @param tenantId verified tenant scope
     * @param environmentId verified non-production environment
     * @param clientRequestId caller-stable idempotency identity
     * @param requestFingerprint canonical immutable request fingerprint
     * @param suiteRef exact immutable suite revision
     * @param classification frozen suite data classification
     * @param plannedAttempts precommitted horizon
     * @param progressRetention sliding recoverable-progress retention
     * @return complete persistence claim request
     */
    public TestSuiteStabilityLeaseRequest request(
            String stabilityRunId,
            String tenantId,
            String environmentId,
            String clientRequestId,
            String requestFingerprint,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            String classification,
            int plannedAttempts,
            Duration progressRetention) {
        if (closed) {
            throw new LeaseLostException("Suite-stability lease coordinator is closed");
        }
        return new TestSuiteStabilityLeaseRequest(stabilityRunId, tenantId, environmentId,
                clientRequestId, requestFingerprint, suiteRef, classification, plannedAttempts,
                newInvocationOwner(), leaseDuration, progressRetention);
    }

    /**
     * Starts background renewal for an already-acquired exact fence.
     *
     * @param lease acquired database lease
     * @return closeable local guard
     */
    public LeaseGuard monitor(TestSuiteStabilityExecutionLease lease) {
        synchronized (lifecycleMonitor) {
            if (closed) {
                throw new LeaseLostException("Suite-stability lease coordinator is closed");
            }
            LeaseGuard guard = new LeaseGuard(Objects.requireNonNull(lease, "lease"));
            activeGuards.add(guard);
            return guard;
        }
    }

    /** Invalidates local guards, releases exact leases, and stops future heartbeat work. */
    @Override
    public void close() {
        List<LeaseGuard> guards;
        synchronized (lifecycleMonitor) {
            if (closed) {
                return;
            }
            closed = true;
            guards = List.copyOf(activeGuards);
        }
        guards.forEach(LeaseGuard::shutdown);
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** Exact local ownership guard for one stability execution. */
    public final class LeaseGuard implements AutoCloseable {
        private TestSuiteStabilityExecutionLease lease;
        private final ScheduledFuture<?> heartbeat;
        private boolean lost;
        private boolean consumed;
        private boolean closed;

        private LeaseGuard(TestSuiteStabilityExecutionLease lease) {
            this.lease = lease;
            heartbeat = scheduler == null ? null : scheduler.scheduleWithFixedDelay(
                    this::heartbeat, heartbeatInterval.toMillis(),
                    heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
        }

        /**
         * Synchronizes with database time and returns the renewed exact fence.
         *
         * @return renewed lease required by atomic terminal completion
         * @throws LeaseLostException after expiry, takeover, release, completion, or store ambiguity
         */
        public synchronized TestSuiteStabilityExecutionLease checkpoint() {
            if (closed || consumed || lost) {
                throw new LeaseLostException(
                        "Suite-stability execution lease is no longer publishable");
            }
            try {
                lease = repository.renew(lease, leaseDuration).orElseThrow(() ->
                        new LeaseLostException(
                                "Suite-stability execution lease was expired or superseded"));
                return lease;
            } catch (LeaseLostException failure) {
                lost = true;
                throw failure;
            } catch (RuntimeException unavailable) {
                lost = true;
                throw new LeaseLostException(
                        "Suite-stability execution lease could not be verified", unavailable);
            }
        }

        /**
         * Atomically journals the next verified source attempt and renews this exact fence.
         *
         * @param attempt next contiguous source reference
         * @param progressRetention sliding durable-progress retention
         * @return durable successor progress
         * @throws LeaseLostException after any ambiguous, stale, or rejected checkpoint
         */
        public synchronized TestSuiteStabilityExecutionProgress checkpoint(
                TestSuiteStabilityExecutionProgress.AttemptReference attempt,
                Duration progressRetention) {
            if (closed || consumed || lost) {
                throw new LeaseLostException(
                        "Suite-stability execution lease is no longer checkpointable");
            }
            try {
                TestSuiteStabilityProgressCheckpoint checkpoint = repository.checkpoint(
                        lease, attempt, leaseDuration, progressRetention);
                lease = checkpoint.lease();
                return checkpoint.progress();
            } catch (TestSuiteStabilityRunConflictException rejected) {
                lost = true;
                throw new LeaseLostException(
                        "Suite-stability progress checkpoint was rejected", rejected);
            } catch (RuntimeException unavailable) {
                lost = true;
                throw new LeaseLostException(
                        "Suite-stability progress checkpoint could not be verified", unavailable);
            }
        }

        /** Marks a successfully consumed lease so close does not issue a redundant release. */
        public synchronized void consumed() {
            consumed = true;
        }

        private synchronized void heartbeat() {
            if (closed || consumed || lost) {
                return;
            }
            try {
                lease = repository.renew(lease, leaseDuration).orElseGet(() -> {
                    lost = true;
                    return lease;
                });
            } catch (RuntimeException unavailable) {
                lost = true;
                log.warn("Suite-stability heartbeat failed; local publication is fail-closed");
            }
        }

        /**
         * Cancels renewal and releases exact live ownership after local failure.
         *
         * <p>Release failure is deliberately non-throwing because database expiry remains the
         * recovery authority and must not mask the primary execution failure.</p>
         */
        @Override
        public synchronized void close() {
            terminate(false);
        }

        private synchronized void shutdown() {
            lost = true;
            terminate(true);
        }

        private void terminate(boolean shuttingDown) {
            if (closed) {
                return;
            }
            closed = true;
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            activeGuards.remove(this);
            if (consumed) {
                return;
            }
            try {
                repository.release(lease);
            } catch (RuntimeException unavailable) {
                log.warn(shuttingDown
                        ? "Suite-stability shutdown release failed; database expiry will recover it"
                        : "Suite-stability lease release failed; database expiry will recover it");
            }
        }
    }

    /** Raised when terminal evidence can no longer be tied to live exact ownership. */
    public static final class LeaseLostException extends RuntimeException {
        /** @param message bounded non-payload failure description */
        public LeaseLostException(String message) {
            super(message);
        }

        /**
         * @param message bounded non-payload failure description
         * @param cause internal persistence failure
         */
        public LeaseLostException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static Duration boundedLease(Duration value) {
        if (value == null
                || value.compareTo(TestSuiteStabilityLeaseRequest.MINIMUM_LEASE) < 0
                || value.compareTo(TestSuiteStabilityLeaseRequest.MAXIMUM_LEASE) > 0
                || value.toMillis() % 1_000 != 0) {
            throw new IllegalArgumentException(
                    "Stability lease must be a whole 5 to 3600 seconds");
        }
        return value;
    }

    private static Duration boundedHeartbeat(Duration value, Duration lease) {
        if (value == null || value.compareTo(Duration.ofSeconds(1)) < 0
                || value.toMillis() % 1_000 != 0
                || value.multipliedBy(3).compareTo(lease) > 0) {
            throw new IllegalArgumentException(
                    "Stability heartbeat must be a whole second and at most one-third of its lease");
        }
        return value;
    }
}
