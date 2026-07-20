package com.leanowtech.bloge.gateway.testing.api;

import java.util.Optional;

/**
 * Registry boundary for immutable, governed test-suite revisions.
 *
 * <p>Implementations return independently owned canonical snapshots and fail closed when indexed
 * envelope fields no longer match serialized suite content. Consumers still verify at their own
 * trust transition because alternate adapters are not implicitly trusted.</p>
 */
public interface TestSuiteRepository {
    /**
     * Creates an immutable revision, or returns the content-equivalent existing revision.
     * First-write creation provenance remains authoritative for idempotent retries.
     */
    StoredTestSuite create(StoredTestSuite testSuite);

    /** Resolves one exact revision in the verified tenant and environment scope. */
    Optional<StoredTestSuite> find(String tenantId, String environmentId, String suiteId, long revision);
}
