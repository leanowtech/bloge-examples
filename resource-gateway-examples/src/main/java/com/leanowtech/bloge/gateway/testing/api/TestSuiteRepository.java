package com.leanowtech.bloge.gateway.testing.api;

import java.util.Optional;

/** Registry boundary for immutable, governed test-suite revisions. */
public interface TestSuiteRepository {
    /** Creates an immutable revision, or returns the content-equivalent existing revision. */
    StoredTestSuite create(StoredTestSuite testSuite);

    /** Resolves one exact revision in the verified tenant and environment scope. */
    Optional<StoredTestSuite> find(String tenantId, String environmentId, String suiteId, long revision);
}
