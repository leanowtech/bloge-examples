package com.leanowtech.bloge.gateway.testing.api;

import java.util.List;
import java.util.Map;

/**
 * Discoverable operator binding, schema and testability contract used before fixture authoring.
 *
 * @param schemaVersion descriptor schema version
 * @param target current operator target and composite fingerprint
 * @param implementationFingerprint executable class-byte fingerprint
 * @param runtimeBindingStateFingerprint behavior-relevant configured-state fingerprint
 * @param schemaFingerprint composite input/output schema fingerprint
 * @param inputSchema BLOGE input schema representation
 * @param outputSchema BLOGE output schema representation
 * @param executionModel binding execution model
 * @param sideEffectType declared side-effect type
 * @param idempotency declared idempotency
 * @param sideEffectProtocol declared external-write protocol
 * @param testabilityClass baseline testability classification
 * @param resourceDependencyFingerprints frozen descriptor dependencies
 * @param dependencyPolicy dependency capture policy
 * @param executionSupported whether v1 can execute this binding
 * @param certificationEligible whether a conforming stored fixture may certify the run
 * @param certificationRequirements fixture requirements for certifiable evidence
 * @param certificationGaps binding-level blockers that fixtures cannot repair
 */
public record TestOperatorTargetDescriptor(
        String schemaVersion,
        TestExecutionApiRequest.Target target,
        String implementationFingerprint,
        String runtimeBindingStateFingerprint,
        String schemaFingerprint,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        String executionModel,
        String sideEffectType,
        String idempotency,
        Map<String, Object> sideEffectProtocol,
        String testabilityClass,
        Map<String, String> resourceDependencyFingerprints,
        String dependencyPolicy,
        boolean executionSupported,
        boolean certificationEligible,
        List<String> certificationRequirements,
        List<String> certificationGaps
) {
    /** Current public operator target descriptor version. */
    public static final String SCHEMA_VERSION = "bloge.testOperatorTargetDescriptor.v1";

    /** Defensively freezes nested protocol collections. */
    public TestOperatorTargetDescriptor {
        schemaVersion = normalized(schemaVersion).isBlank() ? SCHEMA_VERSION : normalized(schemaVersion);
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        outputSchema = outputSchema == null ? Map.of() : Map.copyOf(outputSchema);
        sideEffectProtocol = sideEffectProtocol == null ? Map.of() : Map.copyOf(sideEffectProtocol);
        resourceDependencyFingerprints = resourceDependencyFingerprints == null
                ? Map.of() : Map.copyOf(resourceDependencyFingerprints);
        certificationRequirements = certificationRequirements == null
                ? List.of() : List.copyOf(certificationRequirements);
        certificationGaps = certificationGaps == null ? List.of() : List.copyOf(certificationGaps);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
