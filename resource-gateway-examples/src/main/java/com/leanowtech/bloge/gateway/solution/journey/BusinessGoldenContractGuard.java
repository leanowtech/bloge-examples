package com.leanowtech.bloge.gateway.solution.journey;

import com.fasterxml.jackson.databind.JsonNode;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStateRepository;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;

import java.util.Objects;

/**
 * Validates that a protected business case still names the exact current business contracts.
 * Entity revisions are deliberately ignored: mutable implementation bindings may advance while
 * the stable semantic key and business contract fingerprint remain approved.
 */
public final class BusinessGoldenContractGuard {
    private BusinessGoldenContractGuard() { }

    /**
     * Returns true only when every recorded Feature or Instruction business identity is current.
     *
     * @return {@code false} for a changed semantic key or business contract fingerprint, regardless
     * of whether the entity reference remained stable
     */
    public static boolean isCurrent(AgentTddStateRepository states, String scopeKey, JsonNode row) {
        Objects.requireNonNull(states, "states");
        JsonNode vector = row.path("businessContractVector");
        if (!vector.isArray() || vector.isEmpty()) return false;
        for (JsonNode coordinate : vector) {
            String kind = switch (coordinate.path("assetKind").asText()) {
                case "FEATURE" -> SolutionEntityRegistry.FEATURE;
                case "INSTRUCTION" -> SolutionEntityRegistry.INSTRUCTION;
                default -> "";
            };
            String ref = coordinate.path("assetRef").asText();
            String semanticKey = coordinate.path("semanticKey").asText();
            String fingerprint = coordinate.path("contractFingerprint").asText();
            if (semanticKey.isBlank() || fingerprint.isBlank()) return false;
            if (!kind.isBlank() && !ref.isBlank()) {
                if (states.find(scopeKey, kind, ref)
                        .filter(asset -> matches(asset, semanticKey, fingerprint)).isEmpty()) return false;
                continue;
            }
            boolean current = java.util.stream.Stream.concat(
                            states.list(scopeKey, SolutionEntityRegistry.FEATURE).stream(),
                            states.list(scopeKey, SolutionEntityRegistry.INSTRUCTION).stream())
                    .anyMatch(asset -> matches(asset, semanticKey, fingerprint));
            if (!current) return false;
        }
        return true;
    }

    private static boolean matches(
            com.leanowtech.bloge.gateway.agenttdd.AgentTddStoredAsset asset,
            String semanticKey,
            String fingerprint) {
        return fingerprint.equals(asset.data().path("contractFingerprint").asText())
                && semanticKey.equals(asset.data()
                .at("/contract/businessDefinition/semanticKey").asText());
    }

    /** Rejects stale case review or execution without revealing changed business material. */
    public static void requireCurrent(AgentTddStateRepository states, String scopeKey, JsonNode row) {
        if (!isCurrent(states, scopeKey, row)) throw new AgentTddToolException(
                "GOLDEN_CASE_STALE", "A referenced business contract changed after the case was proposed.");
    }
}
