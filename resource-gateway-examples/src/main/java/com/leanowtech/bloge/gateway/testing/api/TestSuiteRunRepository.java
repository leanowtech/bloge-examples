package com.leanowtech.bloge.gateway.testing.api;

import java.util.Optional;

/** Independent persistence boundary for suite-run checkpoints and terminal aggregate evidence. */
public interface TestSuiteRunRepository {
    /** Creates the initial RUNNING checkpoint and reserves the scoped idempotency key. */
    TestSuiteRunRecord create(TestSuiteRunRecord record);

    /** Replaces the latest checkpoint for an existing suite run. */
    TestSuiteRunRecord update(TestSuiteRunRecord record);

    /** Resolves one aggregate run only in the verified tenant and environment scope. */
    Optional<TestSuiteRunRecord> find(String tenantId, String environmentId, String suiteRunId);

    /** Resolves an idempotent retry only in the verified tenant and environment scope. */
    Optional<TestSuiteRunRecord> findByClientRequestId(String tenantId, String environmentId,
                                                       String clientRequestId);
}
