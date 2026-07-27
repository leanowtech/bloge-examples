package com.leanowtech.bloge.gateway.authoring.scenario;

import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;

/**
 * Server-authoritative Contract coordinate used by Scenario authoring clients.
 *
 * <p>The projection prevents a browser or offline host from guessing canonical fingerprints after
 * Graph persistence has added revision metadata and resolved operator snapshots.</p>
 *
 * @param schemaVersion projection protocol version
 * @param scope verified enterprise scope
 * @param contract Contract projected from the exact retained Graph revision
 * @param contractFingerprint canonical complete Contract fingerprint
 */
public record ScenarioContractProjection(
        String schemaVersion,
        ScenarioDraftSet.EnterpriseScope scope,
        ContractDraft contract,
        String contractFingerprint
) {
    /** Current authoritative projection protocol. */
    public static final String SCHEMA_VERSION = "bloge.scenarioContractProjection.v1";

    /** Normalizes protocol identity and rejects an incomplete projection. */
    public ScenarioContractProjection {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        contractFingerprint = contractFingerprint == null ? "" : contractFingerprint.trim();
        if (scope == null || contract == null || contract.target().fingerprint().isBlank()
                || contractFingerprint.isBlank()) {
            throw new IllegalArgumentException("Scenario Contract projection requires exact coordinates");
        }
    }
}
