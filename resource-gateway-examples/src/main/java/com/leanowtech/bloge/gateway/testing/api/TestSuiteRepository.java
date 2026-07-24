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

    /**
     * Creates one v2 revision in the complete enterprise scope.
     *
     * <p>The default exists only as a fail-closed compatibility bridge for third-party v1
     * adapters. A production repository should override this method with a full-scope storage
     * key; the default cannot create a scoped value through a legacy adapter.</p>
     */
    default StoredTestSuite create(
            TestingArtifactScope scope, StoredTestSuite testSuite) {
        throw new TestSuiteIntegrityException();
    }

    /**
     * Resolves one exact v2 revision by every ownership dimension.
     *
     * <p>The compatibility bridge filters a legacy adapter result by its embedded v2 scope.
     * Native repositories override this method so projects may reuse ids without collisions.</p>
     */
    default Optional<StoredTestSuite> find(
            TestingArtifactScope scope, String suiteId, long revision) {
        if (scope == null) {
            return Optional.empty();
        }
        return find(scope.tenantId(), scope.environmentId(), suiteId, revision)
                .filter(StoredTestSuite::enterpriseScoped)
                .filter(stored -> scope.equals(stored.scope()));
    }
}
