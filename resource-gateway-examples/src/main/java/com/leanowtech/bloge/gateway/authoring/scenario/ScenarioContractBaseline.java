package com.leanowtech.bloge.gateway.authoring.scenario;

import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;

import java.time.Instant;

/**
 * Immutable Contract snapshot captured with one accepted Scenario revision.
 *
 * <p>The snapshot is stored beside, rather than inside, {@link ScenarioDraftSet} so the v1
 * authoring wire contract remains backward compatible. It is an internal comparison source and is
 * never accepted from an authoring request.</p>
 *
 * @param schemaVersion baseline storage format
 * @param scenarioDraftSetId stable Scenario asset id
 * @param scenarioRevision exact retained Scenario revision
 * @param contractFingerprint canonical fingerprint of {@code contract}
 * @param contract exact Contract accepted when the Scenario revision was stored
 * @param savedAt server persistence time
 * @param savedBy verified workload actor
 */
public record ScenarioContractBaseline(
        String schemaVersion,
        String scenarioDraftSetId,
        long scenarioRevision,
        String contractFingerprint,
        ContractDraft contract,
        Instant savedAt,
        String savedBy
) {
    /** Internal immutable snapshot format. */
    public static final String SCHEMA_VERSION = "bloge.scenarioContractBaseline.v1";

    /** Normalizes server-owned metadata. */
    public ScenarioContractBaseline {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        scenarioDraftSetId = scenarioDraftSetId == null ? "" : scenarioDraftSetId.trim();
        scenarioRevision = Math.max(0, scenarioRevision);
        contractFingerprint = contractFingerprint == null ? "" : contractFingerprint.trim();
        savedBy = savedBy == null ? "" : savedBy.trim();
    }
}
