package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Shared admission guard for baseline pressure, concurrency, and circuit state.
 *
 * <p>The guard is intentionally independent from the durable job lease. The job lease prevents
 * two workers from publishing the same logical sample; this guard prevents multiple replicas from
 * exceeding an external system's physical start and concurrency budgets. Production
 * implementations must keep state in a shared authority such as the Resource Gateway database,
 * not in process-local counters.</p>
 */
public interface ReadOnlyShadowExecutionGuard {
    /** @return whether the shared budget and circuit authority can currently make decisions */
    boolean ready();

    /**
     * Acquires one fenced logical execution position.
     *
     * @param permit stable job execution coordinates
     * @param admission exact online authority decision and frozen limits
     * @return closeable guard lease
     * @throws ReadOnlyShadowDataPlane.Failure when budget or circuit policy denies execution
     */
    Lease acquire(
            ReadOnlyShadowDataPlane.Permit permit,
            ReadOnlyShadowAccessAuthority.Admission admission);

    /**
     * Server-owned shared pressure and breaker bounds carried by an online sampling decision.
     *
     * @param maximumConcurrent maximum active logical executions for the governed binding
     * @param maximumStartsPerWindow maximum new logical executions in one fixed window
     * @param startWindow fixed start-rate accounting window
     * @param circuitFailureThreshold consecutive counted failures before opening
     * @param circuitCoolDown minimum open interval before one probe may be admitted
     */
    record Limits(
            int maximumConcurrent,
            int maximumStartsPerWindow,
            Duration startWindow,
            int circuitFailureThreshold,
            Duration circuitCoolDown
    ) {
        /** Validates conservative finite enterprise execution bounds. */
        public Limits {
            startWindow = Objects.requireNonNull(
                    startWindow, "startWindow");
            circuitCoolDown = Objects.requireNonNull(
                    circuitCoolDown, "circuitCoolDown");
            if (maximumConcurrent < 1
                    || maximumConcurrent > 10_000
                    || maximumStartsPerWindow < 1
                    || maximumStartsPerWindow > 10_000_000
                    || startWindow.isNegative()
                    || startWindow.isZero()
                    || startWindow.compareTo(
                    Duration.ofHours(24)) > 0
                    || circuitFailureThreshold < 1
                    || circuitFailureThreshold > 10_000
                    || circuitCoolDown.isNegative()
                    || circuitCoolDown.isZero()
                    || circuitCoolDown.compareTo(
                    Duration.ofHours(24)) > 0) {
                throw new IllegalArgumentException(
                        "read-only Shadow execution limits are invalid");
            }
        }
    }

    /**
     * Fenced shared execution lease.
     *
     * <p>Exactly one terminal callback should be attempted. Closing without a terminal callback
     * must not fabricate success; a durable implementation leaves the lease recoverable only
     * after its expiry.</p>
     */
    interface Lease extends AutoCloseable {
        /**
         * Advances the shared lease no further than the current durable job lease.
         *
         * @param leaseExpiresAt replacement exclusive expiry
         */
        void renew(Instant leaseExpiresAt);

        /** Records successful execution and closes any half-open probe. */
        void succeeded();

        /**
         * Records one classified execution failure.
         *
         * @param reason stable payload-free failure class
         */
        void failed(ReadOnlyShadowDataPlane.FailureReason reason);

        /** Releases process-local resources; durable state remains authoritative. */
        @Override
        void close();
    }

    /** Creates a fail-closed placeholder for deployments without a shared guard. */
    static ReadOnlyShadowExecutionGuard unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Fail-closed singleton. */
    final class Unavailable implements ReadOnlyShadowExecutionGuard {
        private static final Unavailable INSTANCE =
                new Unavailable();

        private Unavailable() {
        }

        @Override
        public boolean ready() {
            return false;
        }

        @Override
        public Lease acquire(
                ReadOnlyShadowDataPlane.Permit permit,
                ReadOnlyShadowAccessAuthority.Admission admission) {
            Objects.requireNonNull(permit, "permit");
            Objects.requireNonNull(admission, "admission");
            throw new ReadOnlyShadowDataPlane.Failure(
                    ReadOnlyShadowDataPlane.FailureReason
                            .ADMISSION_AUTHORITY_UNAVAILABLE);
        }
    }
}
