package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Independent persistence boundary for suite-run checkpoints and terminal aggregate evidence. */
public interface TestSuiteRunRepository {
    /**
     * Returns the persistence authority's current time for cross-instance lease decisions.
     * Implementations backed by a database must use database time rather than an application clock.
     *
     * @return authoritative current instant
     */
    default Instant currentTime() {
        return Instant.now();
    }

    /**
     * Creates the initial RUNNING checkpoint and reserves the scoped idempotency key.
     *
     * @param record initial aggregate checkpoint
     * @param lease process ownership persisted atomically with the checkpoint
     * @return created checkpoint
     */
    TestSuiteRunRecord create(TestSuiteRunRecord record, TestSuiteRunLease lease);

    /**
     * Replaces the latest checkpoint while the same runtime still owns a non-expired lease.
     * The write also renews the lease and advances the database-owned checkpoint version.
     *
     * @param record replacement aggregate checkpoint or terminal evidence
     * @param lease renewed same-owner claim
     * @param observedAt persistence-authoritative comparison time
     * @return stored replacement record
     */
    TestSuiteRunRecord update(TestSuiteRunRecord record, TestSuiteRunLease lease, Instant observedAt);

    /**
     * Renews active ownership without rewriting aggregate evidence.
     *
     * @param tenantId verified tenant scope
     * @param environmentId verified non-production environment
     * @param suiteRunId server-minted aggregate run id
     * @param ownerId expected process-instance owner
     * @param expiresAt requested new exclusive lease deadline
     * @param observedAt persistence-authoritative comparison time
     * @return {@code false} when ownership changed, expired, or the run is already terminal
     */
    boolean renewLease(String tenantId, String environmentId, String suiteRunId, String ownerId,
                       Instant expiresAt, Instant observedAt);

    /**
     * Returns a bounded oldest-first batch of expired RUNNING checkpoints across test scopes.
     *
     * @param observedAt persistence-authoritative sweep time
     * @param limit maximum number of candidates
     * @return version-fenced abandoned checkpoint snapshots
     */
    List<AbandonedTestSuiteRun> findAbandoned(Instant observedAt, int limit);

    /**
     * Terminalizes an abandoned checkpoint only if its status, version, and expired lease still match.
     *
     * @param abandoned version-fenced candidate observed by a previous scan
     * @param terminal fail-closed terminal evidence derived from that candidate
     * @param observedAt persistence-authoritative reconciliation time
     * @return {@code true} when this caller won the reconciliation race
     */
    boolean reconcileAbandoned(AbandonedTestSuiteRun abandoned, TestSuiteRunRecord terminal,
                               Instant observedAt);

    /**
     * Resolves one aggregate run only in the verified tenant and environment scope.
     *
     * @param tenantId verified tenant scope
     * @param environmentId verified environment scope
     * @param suiteRunId server-minted aggregate run id
     * @return matching unexpired record, if present
     */
    Optional<TestSuiteRunRecord> find(String tenantId, String environmentId, String suiteRunId);

    /**
     * Resolves an idempotent retry only in the verified tenant and environment scope.
     *
     * @param tenantId verified tenant scope
     * @param environmentId verified environment scope
     * @param clientRequestId caller idempotency key
     * @return matching unexpired record, if present
     */
    Optional<TestSuiteRunRecord> findByClientRequestId(String tenantId, String environmentId,
                                                       String clientRequestId);
}
