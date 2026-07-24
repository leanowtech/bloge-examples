package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Optional;

/**
 * Append-only repository for independently verified terminal Scenario batch evidence.
 */
public interface ScenarioRehearsalBatchEvidenceRepository {
    /**
     * Creates one exact signed bundle or returns its idempotent existing value.
     *
     * @param bundle complete independently verified portable evidence
     * @return inserted or exact existing bundle
     * @throws ScenarioRehearsalBatchEvidenceStoreException when material conflicts, fails
     *         verification, or cannot be verified by the current authority
     */
    ScenarioRehearsalBatchEvidenceBundle create(
            ScenarioRehearsalBatchEvidenceBundle bundle);

    /**
     * Finds one signed bundle inside the exact enterprise scope.
     *
     * @param scope complete authenticated enterprise scope
     * @param jobId canonical batch identity
     * @return exact verified bundle when present
     * @throws ScenarioRehearsalBatchEvidenceStoreException when stored material is corrupt or the
     *         verification authority is unavailable
     */
    Optional<ScenarioRehearsalBatchEvidenceBundle> find(
            CapabilitySnapshot.Scope scope, String jobId);
}
