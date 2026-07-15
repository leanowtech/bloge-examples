package com.leanowtech.bloge.gateway.testing.api;

import java.util.Optional;

/** Independent persistence boundary for sanitized test-run evidence. */
public interface TestRunRepository {
    /** Creates one terminal run record. Run identifiers are globally unique. */
    TestRunRecord create(TestRunRecord record);

    /** Resolves a run only inside the verified tenant and environment scope. */
    Optional<TestRunRecord> find(String tenantId, String environmentId, String runId);
}
