package com.leanowtech.bloge.gateway.testing.api;

import java.util.Optional;

/**
 * Independent persistence boundary for sanitized test-run evidence.
 *
 * <p>Implementations return independently owned canonical snapshots, verify signatures before new
 * writes, and bind indexed storage columns to serialized evidence on every read. Consumers verify
 * again at their own trust transition because alternate adapters are not implicitly trusted.</p>
 */
public interface TestRunRepository {
    /** Creates one verified terminal run snapshot. Run identifiers are globally unique. */
    TestRunRecord create(TestRunRecord record);

    /** Resolves and verifies a run only inside the exact tenant and environment scope. */
    Optional<TestRunRecord> find(String tenantId, String environmentId, String runId);
}
