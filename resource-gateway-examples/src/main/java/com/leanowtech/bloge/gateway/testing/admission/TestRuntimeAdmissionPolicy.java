package com.leanowtech.bloge.gateway.testing.admission;

import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Duration;

/**
 * Versioned limits and lease behavior for distributed test-runtime admission.
 *
 * <p>The generation is part of the database protocol. A newer generation can take ownership of
 * one tenant only after that tenant has no live permits; an older generation can never overwrite a
 * newer subject policy. This turns rolling configuration drift into an explicit fail-closed state
 * instead of letting replicas enforce contradictory limits.</p>
 *
 * @param generation monotonically increasing operator-controlled policy generation
 * @param tenantMaxActive maximum concurrent admitted executions per tenant and environment
 * @param suiteMaxActive maximum concurrent runs of one suite per tenant and environment
 * @param operatorMaxActive maximum concurrent runs using one operator per tenant and environment
 * @param dependencyMaxActive maximum concurrent runs using one dependency per tenant and environment
 * @param leaseDuration database-clock ownership duration renewed while admitted work is live
 * @param heartbeatInterval renewal interval, strictly shorter than the lease duration
 */
public record TestRuntimeAdmissionPolicy(
        long generation,
        long tenantMaxActive,
        long suiteMaxActive,
        long operatorMaxActive,
        long dependencyMaxActive,
        Duration leaseDuration,
        Duration heartbeatInterval) {

    private static final long MAXIMUM_LIMIT = 1_000_000;
    private static final Duration MINIMUM_LEASE = Duration.ofSeconds(2);
    private static final Duration MAXIMUM_LEASE = Duration.ofHours(1);

    /** Stable quota dimensions enforced as one all-or-nothing admission set. */
    public enum Dimension {
        /** All admitted work in one tenant and non-production environment. */
        TENANT,
        /** Concurrent aggregate executions of one immutable suite identity. */
        SUITE,
        /** Concurrent executions whose frozen invocation closure uses one operator. */
        OPERATOR,
        /** Concurrent executions whose frozen closure may use one external dependency. */
        DEPENDENCY
    }

    /** Validates bounded limits and a renewable database-time lease. */
    public TestRuntimeAdmissionPolicy {
        if (generation <= 0) {
            throw new IllegalArgumentException("Admission policy generation must be positive");
        }
        boundedLimit(tenantMaxActive, "tenantMaxActive");
        boundedLimit(suiteMaxActive, "suiteMaxActive");
        boundedLimit(operatorMaxActive, "operatorMaxActive");
        boundedLimit(dependencyMaxActive, "dependencyMaxActive");
        leaseDuration = positive(leaseDuration, "leaseDuration");
        heartbeatInterval = positive(heartbeatInterval, "heartbeatInterval");
        if (leaseDuration.compareTo(MINIMUM_LEASE) < 0
                || leaseDuration.compareTo(MAXIMUM_LEASE) > 0
                || leaseDuration.toMillis() % 1_000 != 0) {
            throw new IllegalArgumentException(
                    "Admission leaseDuration must be an integral 2 seconds to one hour");
        }
        if (heartbeatInterval.toMillis() % 1_000 != 0) {
            throw new IllegalArgumentException(
                    "Admission heartbeatInterval must use integral seconds");
        }
        if (heartbeatInterval.compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException(
                    "Admission heartbeatInterval must be shorter than leaseDuration");
        }
    }

    /**
     * Returns the active-run limit for a closed quota dimension.
     *
     * @param dimension quota dimension
     * @return positive active-run limit
     */
    public long limit(Dimension dimension) {
        return switch (java.util.Objects.requireNonNull(dimension, "dimension")) {
            case TENANT -> tenantMaxActive;
            case SUITE -> suiteMaxActive;
            case OPERATOR -> operatorMaxActive;
            case DEPENDENCY -> dependencyMaxActive;
        };
    }

    /**
     * Computes the credential-free identity persisted on every permit.
     *
     * @return canonical SHA-256 policy fingerprint
     */
    public String fingerprint() {
        return ProtocolFingerprint.ofText(String.join("|",
                "bloge.testRuntimeAdmissionPolicy.v1",
                Long.toString(generation),
                Long.toString(tenantMaxActive),
                Long.toString(suiteMaxActive),
                Long.toString(operatorMaxActive),
                Long.toString(dependencyMaxActive),
                Long.toString(leaseDuration.toSeconds()),
                Long.toString(heartbeatInterval.toSeconds())));
    }

    private static void boundedLimit(long value, String name) {
        if (value <= 0 || value > MAXIMUM_LIMIT) {
            throw new IllegalArgumentException(
                    name + " must be between 1 and " + MAXIMUM_LIMIT);
        }
    }

    private static Duration positive(Duration value, String name) {
        Duration result = java.util.Objects.requireNonNull(value, name);
        if (result.isZero() || result.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return result;
    }
}
