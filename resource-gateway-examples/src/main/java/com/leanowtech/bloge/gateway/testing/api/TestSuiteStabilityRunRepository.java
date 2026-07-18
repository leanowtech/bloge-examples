package com.leanowtech.bloge.gateway.testing.api;

import java.util.Optional;

/** Independent terminal store for signed suite-stability analyses. */
public interface TestSuiteStabilityRunRepository {
    /**
     * Creates one immutable terminal analysis and reserves its scoped idempotency key.
     *
     * @param record complete signed analysis
     * @return stored immutable record
     */
    TestSuiteStabilityRunRecord create(TestSuiteStabilityRunRecord record);

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
