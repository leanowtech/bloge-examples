package com.leanowtech.bloge.gateway.testing.api;

import java.time.Duration;

/**
 * Durable membership authority for the signed physical provider-inventory cohort.
 *
 * <p>The repository obtains scope, cohort, expected replica set, and inventory generation from
 * the verified publication source. Callers may identify only their own process start; they cannot
 * inject or narrow the expected fleet.</p>
 */
public interface TestSuiteStabilityPhysicalAttemptProviderInventoryCohortRepository
        extends TestSuiteStabilityPhysicalAttemptProviderInventoryCohortGate {

    /**
     * Renews the local process lease against the currently signed publication generation.
     *
     * @param startupId unique identity of this process start
     * @return aggregate cohort observation at the database transaction boundary
     */
    Observation heartbeat(String startupId);

    /**
     * Returns the database lease duration used to validate monitor renewal cadence.
     *
     * @return bounded live-member lease duration
     */
    Duration leaseDuration();

    /**
     * Withdraws the local process start without affecting another concurrent start.
     *
     * @param startupId exact local process-start identity
     */
    void withdraw(String startupId);
}
