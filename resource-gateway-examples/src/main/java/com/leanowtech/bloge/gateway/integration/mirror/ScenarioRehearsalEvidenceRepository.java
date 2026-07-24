package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Optional;

/**
 * Append-only repository for independently verified Scenario rehearsal evidence.
 */
public interface ScenarioRehearsalEvidenceRepository {
    /**
     * Creates one exact signed bundle or returns its idempotent existing value.
     *
     * @throws ScenarioRehearsalEvidenceStoreException when material conflicts, fails verification,
     *         or cannot be verified by the current authority
     */
    ScenarioRehearsalEvidenceBundle create(
            ScenarioRehearsalEvidenceBundle bundle);

    /**
     * Finds one signed bundle inside the exact enterprise scope.
     *
     * @throws ScenarioRehearsalEvidenceStoreException when stored material is corrupt or the
     *         verification authority is unavailable
     */
    Optional<ScenarioRehearsalEvidenceBundle> find(
            CapabilitySnapshot.Scope scope, String runId);
}
