package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestRecoveryDispatch;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Maintains a rotating database-fenced dispatch while one terminal recovery executes.
 *
 * <p>Monitoring first performs a synchronous authenticated heartbeat, so BLOGE execution never
 * starts under a nearly expired caller fence. Later heartbeats replace the complete local
 * checkpoint/dispatch pair only after proving that scope, authorization, deterministic state,
 * fixture cursor, and BLOGE engine closure did not change. {@link LeaseGuard#freeze()} waits for an
 * in-flight renewal and returns the sole successor dispatch that may enter the terminal CAS.</p>
 */
public final class DurableTestRecoveryLeaseCoordinator implements AutoCloseable {

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Duration MINIMUM_LEASE = Duration.ofSeconds(3);
    private static final Duration MAXIMUM_LEASE = Duration.ofHours(1);

    private final DurableTestRecoveryHeartbeatService heartbeats;
    private final Duration leaseDuration;
    private final Duration heartbeatInterval;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycleLock = new Object();
    private final Set<LeaseGuard> activeGuards = ConcurrentHashMap.newKeySet();

    /**
     * Creates one process-wide automatic recovery-heartbeat coordinator.
     *
     * @param heartbeats authenticated issued-dispatch heartbeat boundary
     * @param heartbeatInterval delay of at least one millisecond and no greater than one third of
     *     the configured recovery lease
     */
    public DurableTestRecoveryLeaseCoordinator(
            DurableTestRecoveryHeartbeatService heartbeats,
            Duration heartbeatInterval) {
        this(heartbeats, heartbeatInterval, true);
    }

    private DurableTestRecoveryLeaseCoordinator(
            DurableTestRecoveryHeartbeatService heartbeats,
            Duration heartbeatInterval,
            boolean active) {
        this.heartbeats = active ? Objects.requireNonNull(heartbeats, "heartbeats") : heartbeats;
        this.leaseDuration = active
                ? requiredLease(heartbeats.leaseDuration()) : Duration.ofMinutes(2);
        this.heartbeatInterval = requiredHeartbeat(heartbeatInterval, leaseDuration);
        this.scheduler = active ? Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "resource-gateway-durable-recovery-heartbeat");
            thread.setDaemon(true);
            return thread;
        }) : null;
    }

    static DurableTestRecoveryLeaseCoordinator passive() {
        return new DurableTestRecoveryLeaseCoordinator(
                null, Duration.ofSeconds(30), false);
    }

    /**
     * Starts one automatic worker lease after synchronously obtaining a fresh dispatch.
     *
     * @param source exact issued dispatch selected by the authenticated terminal request
     * @param checkpoint integrity-verified live checkpoint controlled by {@code source}
     * @param operationFingerprint canonical terminal-operation identity used for heartbeat keys
     * @param identity authenticated principal that owns the issued dispatch
     * @return guard carrying the fresh execution checkpoint and latest committable dispatch
     * @throws LeaseLostException when initial renewal or local scheduling cannot establish ownership
     */
    public LeaseGuard monitor(
            DurableTestRecoveryDispatch source,
            DurableTestExecutionCheckpoint checkpoint,
            String operationFingerprint,
            IntegrationRequestContext identity) {
        DurableTestRecoveryDispatch requiredSource = Objects.requireNonNull(source, "source");
        DurableTestExecutionCheckpoint requiredCheckpoint =
                Objects.requireNonNull(checkpoint, "checkpoint");
        IntegrationRequestContext requiredIdentity = Objects.requireNonNull(identity, "identity");
        String requiredOperation = requiredFingerprint(
                operationFingerprint, "operationFingerprint");
        if (!requiredSource.agreesWith(requiredCheckpoint)) {
            throw new IllegalArgumentException(
                    "Source recovery dispatch must agree with its live checkpoint");
        }
        ensureOpen();
        var initial = new DurableTestExecutionCheckpointRepository.RecoveryHeartbeatResult(
                requiredCheckpoint, requiredSource, false);
        if (scheduler != null) {
            initial = renew(initial, requiredOperation, requiredIdentity);
        }
        LeaseGuard guard = new LeaseGuard(initial, requiredOperation, requiredIdentity);
        synchronized (lifecycleLock) {
            if (closed.get()) {
                throw lost("Durable recovery ownership became uncertain before monitoring",
                        new IllegalStateException(
                                "Durable recovery lease coordinator is closed"));
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
                    throw lost("Durable recovery heartbeat scheduling failed",
                            schedulingFailure);
                }
            }
        }
        return guard;
    }

    /** Invalidates every recovery that has not crossed its terminal commit boundary. */
    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            IllegalStateException shutdown = new IllegalStateException(
                    "Durable recovery lease coordinator closed during execution");
            for (LeaseGuard guard : List.copyOf(activeGuards)) {
                guard.invalidate(shutdown);
            }
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
        }
    }

    private void ensureOpen() {
        synchronized (lifecycleLock) {
            if (closed.get()) {
                throw lost("Durable recovery ownership became uncertain before renewal",
                        new IllegalStateException(
                                "Durable recovery lease coordinator is closed"));
            }
        }
    }

    private DurableTestExecutionCheckpointRepository.RecoveryHeartbeatResult renew(
            DurableTestExecutionCheckpointRepository.RecoveryHeartbeatResult previous,
            String operationFingerprint,
            IntegrationRequestContext identity) {
        DurableTestExecutionCheckpointRepository.RecoveryHeartbeatResult successor;
        try {
            successor = heartbeats.renewIssuedDispatch(
                    previous.dispatch(), DurableTestRecoveryCommandKeys.automaticHeartbeat(
                            operationFingerprint, previous.dispatch().revision()), identity);
        } catch (RuntimeException unavailableOrLost) {
            throw lost("Durable recovery preparation ownership became uncertain",
                    unavailableOrLost);
        }
        try {
            requireSuccessor(previous, successor);
        } catch (RuntimeException invalidSuccessor) {
            throw lost("Durable recovery heartbeat returned an invalid successor",
                    invalidSuccessor);
        }
        return successor;
    }

    private static void requireSuccessor(
            DurableTestExecutionCheckpointRepository.RecoveryHeartbeatResult previous,
            DurableTestExecutionCheckpointRepository.RecoveryHeartbeatResult successor) {
        Objects.requireNonNull(successor, "successor");
        DurableTestExecutionCheckpoint before = previous.checkpoint();
        DurableTestExecutionCheckpoint after = successor.checkpoint();
        DurableTestRecoveryDispatch source = previous.dispatch();
        DurableTestRecoveryDispatch next = successor.dispatch();
        if (!next.agreesWith(after)) {
            throw new IllegalStateException(
                    "Recovery heartbeat successor dispatch does not match its checkpoint");
        }
        if (!source.authorization().equals(next.authorization())) {
            throw new IllegalStateException(
                    "Recovery heartbeat changed frozen authorization");
        }
        if (!before.scope().equals(after.scope())
                || !before.runId().equals(after.runId())
                || !before.engineExecutionId().equals(after.engineExecutionId())) {
            throw new IllegalStateException(
                    "Recovery heartbeat changed execution identity");
        }
        if (!Objects.equals(before.dependencies(), after.dependencies())) {
            throw new IllegalStateException(
                    "Recovery heartbeat changed frozen dependencies");
        }
        if (!Objects.equals(before.fixtureConsumptionState(),
                after.fixtureConsumptionState())) {
            throw new IllegalStateException(
                    "Recovery heartbeat changed frozen fixture state");
        }
        if (!Objects.equals(before.executionServiceState(),
                after.executionServiceState())) {
            throw new IllegalStateException(
                    "Recovery heartbeat changed frozen provider state");
        }
        if (!Objects.equals(before.engineState(), after.engineState())) {
            throw new IllegalStateException(
                    "Recovery heartbeat changed frozen engine state");
        }
        DurableTestExecutionCheckpoint.Lifecycle oldLifecycle = before.lifecycle();
        DurableTestExecutionCheckpoint.Lifecycle newLifecycle = after.lifecycle();
        if (oldLifecycle.status() != DurableTestExecutionCheckpoint.Status.RESUMING
                || newLifecycle.status() != DurableTestExecutionCheckpoint.Status.RESUMING
                || !oldLifecycle.ownerId().equals(newLifecycle.ownerId())
                || oldLifecycle.leaseEpoch() != newLifecycle.leaseEpoch()
                || oldLifecycle.revision() == Long.MAX_VALUE
                || newLifecycle.revision() != oldLifecycle.revision() + 1
                || !oldLifecycle.createdAt().equals(newLifecycle.createdAt())
                || !newLifecycle.updatedAt().isAfter(oldLifecycle.updatedAt())
                || !newLifecycle.leaseExpiresAt().isAfter(
                oldLifecycle.leaseExpiresAt())) {
            throw new IllegalStateException(
                    "Recovery heartbeat violated monotonic lease succession");
        }
    }

    private static LeaseLostException lost(String message, RuntimeException cause) {
        return new LeaseLostException(message, cause);
    }

    /** One automatically renewed recovery fence retained until terminal commit preparation. */
    public final class LeaseGuard implements AutoCloseable {
        private final Object lock = new Object();
        private final String operationFingerprint;
        private final IntegrationRequestContext identity;
        private DurableTestExecutionCheckpointRepository.RecoveryHeartbeatResult current;
        private RuntimeException failure;
        private boolean stopped;
        private volatile ScheduledFuture<?> future;

        private LeaseGuard(
                DurableTestExecutionCheckpointRepository.RecoveryHeartbeatResult initial,
                String operationFingerprint,
                IntegrationRequestContext identity) {
            this.current = initial;
            this.operationFingerprint = operationFingerprint;
            this.identity = identity;
        }

        /**
         * Reports whether this guard can still cross the terminal commit boundary.
         *
         * @return whether ownership may still be frozen for a terminal mutation
         */
        public boolean held() {
            synchronized (lock) {
                return !stopped && failure == null;
            }
        }

        /**
         * Returns the fresh checkpoint from which the isolated BLOGE session must start.
         *
         * @return integrity-preserving heartbeat successor checkpoint
         * @throws LeaseLostException when renewal already failed
         */
        public DurableTestExecutionCheckpoint executionCheckpoint() {
            synchronized (lock) {
                throwIfFailed();
                return current.checkpoint();
            }
        }

        /**
         * Stops renewal and returns the latest exact checkpoint/dispatch pair for terminal CAS.
         *
         * @return latest repository-issued heartbeat result
         * @throws LeaseLostException when ownership is stale or uncertain
         */
        public DurableTestExecutionCheckpointRepository.RecoveryHeartbeatResult freeze() {
            DurableTestExecutionCheckpointRepository.RecoveryHeartbeatResult frozen;
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
                if (heartbeatFailure instanceof LeaseLostException leaseLost) {
                    throw leaseLost;
                }
                throw lost("Durable recovery preparation ownership became uncertain",
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
                    current = renew(current, operationFingerprint, identity);
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

        /** Stops renewal without asserting that the last known dispatch remains live. */
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

        private void throwIfFailed() {
            if (failure instanceof LeaseLostException leaseLost) {
                throw leaseLost;
            }
            if (failure != null) {
                throw lost("Durable recovery preparation ownership became uncertain", failure);
            }
        }

        private void cancelFuture() {
            ScheduledFuture<?> scheduled = future;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
        }
    }

    /** Raised when a worker must discard staged recovery because its dispatch is uncertain. */
    public static final class LeaseLostException extends IllegalStateException {
        /**
         * Creates one payload-free local ownership failure.
         *
         * @param message stable diagnostic without business payload
         * @param cause internal heartbeat, validation, or lifecycle failure
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
                    "Automatic recovery lease must be whole seconds from three seconds through one hour");
        }
        return required;
    }

    private static Duration requiredHeartbeat(Duration value, Duration lease) {
        Duration required = Objects.requireNonNull(value, "heartbeatInterval");
        if (required.isZero() || required.isNegative() || required.toMillis() < 1
                || required.compareTo(lease.dividedBy(3)) > 0) {
            throw new IllegalArgumentException(
                    "Automatic recovery heartbeat interval must be at least one millisecond and no greater than one third of the lease");
        }
        return required;
    }

    private static String requiredFingerprint(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be a canonical SHA-256 fingerprint");
        }
        return normalized;
    }
}
