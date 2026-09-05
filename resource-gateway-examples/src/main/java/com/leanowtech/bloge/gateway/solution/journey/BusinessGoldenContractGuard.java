package com.leanowtech.bloge.gateway.solution.journey;

import com.fasterxml.jackson.databind.JsonNode;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStateRepository;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.solution.SolutionEntityRegistry;

import java.util.Objects;

/** Validates that a protected business case still names the exact current business contracts. */
public final class BusinessGoldenContractGuard {
    private BusinessGoldenContractGuard() { }

    /** Returns true only when every recorded Feature or Instruction fingerprint is current. */
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
            String fingerprint = coordinate.path("contractFingerprint").asText();
            if (kind.isBlank() || ref.isBlank() || fingerprint.isBlank()
                    || states.find(scopeKey, kind, ref)
                    .filter(asset -> fingerprint.equals(
                            asset.data().path("contractFingerprint").asText()))
                    .isEmpty()) return false;
        }
        return true;
    }

    /** Rejects stale case review or execution without revealing changed business material. */
    public static void requireCurrent(AgentTddStateRepository states, String scopeKey, JsonNode row) {
        if (!isCurrent(states, scopeKey, row)) throw new AgentTddToolException(
                "GOLDEN_CASE_STALE", "A referenced business contract changed after the case was proposed.");
    }
}
