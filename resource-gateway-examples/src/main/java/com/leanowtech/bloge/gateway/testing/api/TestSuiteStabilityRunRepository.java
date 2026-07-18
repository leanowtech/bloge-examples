package com.leanowtech.bloge.gateway.testing.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Database-authoritative execution fence and terminal store for suite-stability analyses. */
public interface TestSuiteStabilityRunRepository {
    /**
     * Returns persistence-authoritative time for lease and retention decisions.
     *
     * @return database time for durable adapters
     */
    default Instant currentTime() {
        return Instant.now();
    }

    /**
     * Atomically replays a terminal result, observes a live owner, or acquires an expired/new fence.
     *
     * @param request exact scope, intent, owner, and lease duration
     * @return one unambiguous claim outcome
     */
    TestSuiteStabilityLeaseClaim claim(TestSuiteStabilityLeaseRequest request);

    /**
     * Renews only the exact database-clock-live owner and epoch.
     *
     * @param lease caller's latest exact fence
     * @param leaseDuration bounded successor duration
     * @return renewed successor, or empty after expiry, takeover, completion, or release
     */
    Optional<TestSuiteStabilityExecutionLease> renew(
            TestSuiteStabilityExecutionLease lease, Duration leaseDuration);

    /**
     * Releases exact live ownership after a local failure; expiry handles crashes and store outages.
     *
     * @param lease exact owner fence
     * @return true only when this caller deleted its still-live lease
     */
    boolean release(TestSuiteStabilityExecutionLease lease);

    /**
     * Atomically verifies a live fence, inserts signed terminal evidence, and consumes the lease.
     *
     * @param record complete signed terminal analysis
     * @param lease exact live owner fence
     * @return stored immutable record
     */
    TestSuiteStabilityRunRecord complete(
            TestSuiteStabilityRunRecord record, TestSuiteStabilityExecutionLease lease);

    /**
     * Deletes a bounded oldest-first page of expired orphan leases.
     *
     * @param limit maximum rows to delete
     * @return number of deleted rows
     */
    int purgeExpiredLeases(int limit);

    /**
     * Resolves one retained analysis inside the verified scope.
     *
     * @param tenantId verified tenant id
     * @param environmentId verified environment id
     * @param stabilityRunId deterministic analysis id
     * @return retained analysis, if present
     */
    Optional<TestSuiteStabilityRunRecord> find(
            String tenantId, String environmentId, String stabilityRunId);

    /**
     * Resolves a retained idempotent result inside the verified scope.
     *
     * @param tenantId verified tenant id
     * @param environmentId verified environment id
     * @param clientRequestId caller parent idempotency key
     * @return retained analysis, if present
     */
    Optional<TestSuiteStabilityRunRecord> findByClientRequestId(
            String tenantId, String environmentId, String clientRequestId);
}
