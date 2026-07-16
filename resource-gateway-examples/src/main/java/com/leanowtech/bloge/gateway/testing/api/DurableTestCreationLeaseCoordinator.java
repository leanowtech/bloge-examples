package com.leanowtech.bloge.gateway.testing.api;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Maintains the exact database-fenced preparation lease for fresh durable executions.
 *
 * <p>One daemon scheduler may monitor multiple synchronous request threads. Every heartbeat replaces
 * the locally held reservation with the repository-issued successor fingerprint. Callers must
 * {@link LeaseGuard#freeze()} before commit or deterministic rejection; freeze waits for an in-flight
 * heartbeat and returns the only reservation that may still mutate the command. Any heartbeat
 * failure makes ownership uncertain and therefore prevents staged BLOGE state from committing.</p>
 */
public final class DurableTestCreationLeaseCoordinator implements AutoCloseable {

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Duration MINIMUM_LEASE = Duration.ofSeconds(3);
    private static final Duration MAXIMUM_LEASE = Duration.ofHours(1);

    private final DurableTestExecutionCheckpointRepository repository;
    private final String ownerId;
    private final Duration leaseDuration;
    private final Duration heartbeatInterval;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycleLock = new Object();
    private final Set<LeaseGuard> activeGuards = ConcurrentHashMap.newKeySet();

    /**
     * Creates an active process-wide coordinator with one daemon heartbeat thread.
     *
     * @param repository durable creation command authority
     * @param ownerId stable service-instance identity; blank generates a UUID-backed identity
     * @param leaseDuration whole-second preparation lease from three seconds through one hour
     * @param heartbeatInterval delay of at least one millisecond and no greater than one third of
     *     the lease
     */
    public DurableTestCreationLeaseCoordinator(
            DurableTestExecutionCheckpointRepository repository,
            String ownerId,
            Duration leaseDuration,
            Duration heartbeatInterval) {
        this(repository, ownerId, leaseDuration, heartbeatInterval, true);
    }

    DurableTestCreationLeaseCoordinator(
            DurableTestExecutionCheckpointRepository repository,
            String ownerId,
            Duration leaseDuration,
            Duration heartbeatInterval,
            boolean active) {
        this.repository = active ? Objects.requireNonNull(repository, "repository") : repository;
        String normalizedOwner = normalized(ownerId);
        this.ownerId = normalizedOwner.isEmpty()
                ? "durable-creation-" + UUID.randomUUID() : requiredOwner(normalizedOwner);
        this.leaseDuration = requiredLease(leaseDuration);
        this.heartbeatInterval = requiredHeartbeat(heartbeatInterval, this.leaseDuration);
        this.scheduler = active ? Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "resource-gateway-durable-creation-heartbeat");
            thread.setDaemon(true);
            return thread;
        }) : null;
    }

    static DurableTestCreationLeaseCoordinator passive(
            String ownerId,
            Duration leaseDuration) {
        return new DurableTestCreationLeaseCoordinator(
                null, ownerId, leaseDuration, leaseDuration.dividedBy(3), false);
    }

    /**
     * Returns the process identity used for creation reservations.
     *
     * @return bounded stable owner id
     */
    public String ownerId() {
        return ownerId;
    }

    /**
     * Returns the lease duration used for reservation and every successor heartbeat.
     *
     * @return bounded whole-second preparation lease
     */
    public Duration leaseDuration() {
        return leaseDuration;
    }

    /**
     * Starts monitoring one newly acquired pending reservation.
     *
     * @param reservation exact reservation owned by this service instance
     * @return closeable guard whose freeze result is required for commit or rejection
     */
    public LeaseGuard monitor(
            DurableTestExecutionCheckpointRepository.InitialCreationReservation reservation) {
        var required = Objects.requireNonNull(reservation, "reservation");
        if (required.state()
                != DurableTestExecutionCheckpointRepository.InitialCreationState.PENDING
                || !ownerId.equals(required.ownerId())) {
            throw new IllegalArgumentException(
                    "Only this coordinator's pending creation reservation can be monitored");
        }
        LeaseGuard guard = new LeaseGuard(required);
        synchronized (lifecycleLock) {
            if (closed.get()) {
                throw new IllegalStateException("Durable creation lease coordinator is closed");
            }
            activeGuards.add(guard);
            if (scheduler != null) {
                try {
                    guard.future = scheduler.scheduleWithFixedDelay(
                            guard::heartbeat,
                            heartbeatInterval.toMillis(),
                            heartbeatInterval.toMillis(),
                            TimeUnit.MILLISECONDS);
                    if (!guard.held()) {
                        guard.cancelFuture();
                    }
                } catch (RuntimeException schedulingFailure) {
                    guard.invalidate(schedulingFailure);
                    throw schedulingFailure;
                }
            }
        }
        return guard;
    }

    /** Stops future heartbeats without interrupting caller-owned graph execution. */
    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            IllegalStateException shutdown = new IllegalStateException(
                    "Durable creation lease coordinator closed during preparation");
            for (LeaseGuard guard : List.copyOf(activeGuards)) {
                guard.invalidate(shutdown);
            }
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
        }
    }

    /** One locally monitored exact preparation fence. */
    public final class LeaseGuard implements AutoCloseable {
        private final Object lock = new Object();
        private DurableTestExecutionCheckpointRepository.InitialCreationReservation current;
        private RuntimeException failure;
        private boolean stopped;
        private volatile ScheduledFuture<?> future;

        private LeaseGuard(
                DurableTestExecutionCheckpointRepository.InitialCreationReservation reservation) {
            this.current = reservation;
        }

        /**
         * Reports whether no heartbeat has yet made local ownership uncertain.
         *
         * @return {@code true} while the guard may still be frozen for mutation
         */
        public boolean held() {
            synchronized (lock) {
                return !stopped && failure == null;
            }
        }

        /**
         * Stops renewal, waits for an in-flight heartbeat, and returns the latest exact fence.
         *
         * <p>The method is idempotent after a successful freeze. If any heartbeat failed, it always
         * throws and never returns an older reservation.</p>
         *
         * @return latest repository-issued pending reservation
         * @throws LeaseLostException when ownership is stale or uncertain
         */
        public DurableTestExecutionCheckpointRepository.InitialCreationReservation freeze() {
            DurableTestExecutionCheckpointRepository.InitialCreationReservation frozen;
            RuntimeException heartbeatFailure;
            synchronized (lifecycleLock) {
                synchronized (lock) {
                    stopped = true;
                    frozen = current;
                    heartbeatFailure = failure;
                }
                activeGuards.remove(this);
            }
            cancelFuture();
            if (heartbeatFailure != null) {
                throw new LeaseLostException(
                        "Durable creation preparation ownership became uncertain",
                        heartbeatFailure);
            }
            return frozen;
        }

        private void heartbeat() {
            synchronized (lock) {
                if (stopped) {
                    return;
                }
                try {
                    current = repository.heartbeatInitialCreation(current, leaseDuration);
                } catch (RuntimeException unavailableOrLost) {
                    failure = unavailableOrLost;
                    stopped = true;
                }
            }
            if (failure != null) {
                activeGuards.remove(this);
                cancelFuture();
            }
        }

        /** Stops renewal without asserting that the last known fence is still live. */
        @Override
        public void close() {
            synchronized (lock) {
                stopped = true;
            }
            activeGuards.remove(this);
            cancelFuture();
        }

        private void invalidate(RuntimeException cause) {
            synchronized (lock) {
                if (failure == null) {
                    failure = cause;
                }
                stopped = true;
            }
            activeGuards.remove(this);
            cancelFuture();
        }

        private void cancelFuture() {
            ScheduledFuture<?> scheduled = future;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
        }
    }

    /** Raised when a guarded request must discard staged state because lease ownership is uncertain. */
    public static final class LeaseLostException extends IllegalStateException {
        /**
         * Creates one local fail-closed ownership signal.
         *
         * @param message payload-free diagnostic
         * @param cause internal repository or scheduling failure
         */
        public LeaseLostException(String message, RuntimeException cause) {
            super(message, cause);
        }
    }

    private static Duration requiredLease(Duration value) {
        Duration required = Objects.requireNonNull(value, "leaseDuration");
        if (required.compareTo(MINIMUM_LEASE) < 0
                || required.compareTo(MAXIMUM_LEASE) > 0
                || required.getNano() != 0) {
            throw new IllegalArgumentException(
                    "Creation lease must be whole seconds from three seconds through one hour");
        }
        return required;
    }

    private static Duration requiredHeartbeat(Duration value, Duration lease) {
        Duration required = Objects.requireNonNull(value, "heartbeatInterval");
        if (required.isZero() || required.isNegative() || required.toMillis() < 1
                || required.compareTo(lease.dividedBy(3)) > 0) {
            throw new IllegalArgumentException(
                    "Creation heartbeat interval must be at least one millisecond and no greater than one third of the lease");
        }
        return required;
    }

    private static String requiredOwner(String value) {
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("ownerId must be a bounded stable identifier");
        }
        return value;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
