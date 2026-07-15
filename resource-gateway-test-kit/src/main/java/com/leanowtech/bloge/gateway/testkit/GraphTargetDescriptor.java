package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Typed projection of target identity, dependency fingerprints, and certification readiness.
 *
 * @param graphId registered graph id
 * @param fingerprint frozen composite target fingerprint
 * @param resourceDependencyFingerprints resource descriptor ids to frozen fingerprints
 * @param dependencyPolicy dependency capture policy
 * @param certificationEligible whether the target can issue certifiable evidence
 * @param certificationGaps bounded reasons certification is unavailable
 * @param rawResponse defensive copy of the complete decoded response
 */
public record GraphTargetDescriptor(
        String graphId,
        String fingerprint,
        Map<String, String> resourceDependencyFingerprints,
        String dependencyPolicy,
        boolean certificationEligible,
        List<String> certificationGaps,
        JsonNode rawResponse
) {
    /** Normalizes collections and protects the raw response at construction time. */
    public GraphTargetDescriptor {
        resourceDependencyFingerprints = resourceDependencyFingerprints == null
                ? Map.of() : Map.copyOf(resourceDependencyFingerprints);
        certificationGaps = certificationGaps == null ? List.of() : List.copyOf(certificationGaps);
        rawResponse = rawResponse == null ? null : rawResponse.deepCopy();
    }

    /**
     * Returns the complete decoded discovery response without exposing mutable internal state.
     *
     * @return defensive copy of the decoded response
     */
    @Override
    public JsonNode rawResponse() {
        return rawResponse == null ? null : rawResponse.deepCopy();
    }

    /** Creates a defensive immutable projection from a validated protocol response. */
    static GraphTargetDescriptor from(JsonNode response) {
        JsonNode target = response.path("target");
        Map<String, String> dependencies = new TreeMap<>();
        response.path("resourceDependencyFingerprints").fields()
                .forEachRemaining(entry -> dependencies.put(entry.getKey(), entry.getValue().asText()));
        List<String> gaps = new ArrayList<>();
        response.path("certificationGaps").forEach(value -> gaps.add(value.asText()));
        return new GraphTargetDescriptor(target.path("id").asText(), target.path("fingerprint").asText(),
                Map.copyOf(dependencies), response.path("dependencyPolicy").asText(),
                response.path("certificationEligible").asBoolean(), List.copyOf(gaps), response.deepCopy());
    }
}
