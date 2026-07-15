package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Typed, payload-free projection of one frozen operator binding and its testability contract.
 *
 * @param operatorRef registered operator reference
 * @param fingerprint composite target fingerprint
 * @param implementationFingerprint executable class-byte fingerprint
 * @param runtimeBindingStateFingerprint behavior-relevant configured-state fingerprint
 * @param schemaFingerprint composite input/output schema fingerprint
 * @param executionModel binding execution model
 * @param sideEffectType declared side-effect type
 * @param idempotency declared idempotency
 * @param testabilityClass baseline executable/conditional/opaque classification
 * @param resourceDependencyFingerprints frozen resource dependencies
 * @param dependencyPolicy dependency capture policy
 * @param executionSupported whether the v1 server can execute the binding
 * @param certificationEligible whether a conforming stored fixture may certify a run
 * @param certificationRequirements fixture requirements for certification
 * @param certificationGaps binding-level certification blockers
 * @param rawResponse defensive copy of the full discovery response
 */
public record OperatorTargetDescriptor(
        String operatorRef,
        String fingerprint,
        String implementationFingerprint,
        String runtimeBindingStateFingerprint,
        String schemaFingerprint,
        String executionModel,
        String sideEffectType,
        String idempotency,
        String testabilityClass,
        Map<String, String> resourceDependencyFingerprints,
        String dependencyPolicy,
        boolean executionSupported,
        boolean certificationEligible,
        List<String> certificationRequirements,
        List<String> certificationGaps,
        JsonNode rawResponse
) {
    /** Normalizes collections and protects the raw response at construction time. */
    public OperatorTargetDescriptor {
        resourceDependencyFingerprints = resourceDependencyFingerprints == null
                ? Map.of() : Map.copyOf(resourceDependencyFingerprints);
        certificationRequirements = certificationRequirements == null
                ? List.of() : List.copyOf(certificationRequirements);
        certificationGaps = certificationGaps == null ? List.of() : List.copyOf(certificationGaps);
        rawResponse = rawResponse == null ? null : rawResponse.deepCopy();
    }

    /** @return defensive copy of the complete decoded response */
    @Override
    public JsonNode rawResponse() {
        return rawResponse == null ? null : rawResponse.deepCopy();
    }

    /** Creates an immutable typed projection from a version-checked response. */
    static OperatorTargetDescriptor from(JsonNode response) {
        JsonNode target = response.path("target");
        Map<String, String> dependencies = new TreeMap<>();
        response.path("resourceDependencyFingerprints").fields()
                .forEachRemaining(entry -> dependencies.put(entry.getKey(), entry.getValue().asText()));
        List<String> requirements = new ArrayList<>();
        response.path("certificationRequirements").forEach(value -> requirements.add(value.asText()));
        List<String> gaps = new ArrayList<>();
        response.path("certificationGaps").forEach(value -> gaps.add(value.asText()));
        return new OperatorTargetDescriptor(target.path("id").asText(), target.path("fingerprint").asText(),
                response.path("implementationFingerprint").asText(),
                response.path("runtimeBindingStateFingerprint").asText(),
                response.path("schemaFingerprint").asText(), response.path("executionModel").asText(),
                response.path("sideEffectType").asText(), response.path("idempotency").asText(),
                response.path("testabilityClass").asText(), Map.copyOf(dependencies),
                response.path("dependencyPolicy").asText(), response.path("executionSupported").asBoolean(),
                response.path("certificationEligible").asBoolean(), List.copyOf(requirements),
                List.copyOf(gaps), response.deepCopy());
    }
}
