package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.gateway.GatewayGraphContract;

import java.util.Map;

/**
 * Discoverable frozen target material required before authoring or rebasing a fixture bundle.
 *
 * @param schemaVersion descriptor schema version
 * @param target current graph target and composite fingerprint
 * @param contract formal graph-level input/output contract
 * @param resourceDependencyFingerprints conservatively captured descriptor dependencies
 * @param dependencyPolicy dependency capture policy used by this protocol version
 * @param certificationEligible whether this target can issue certifiable evidence
 * @param certificationGaps reasons the target is restricted to exploratory evidence
 */
public record TestGraphTargetDescriptor(
        String schemaVersion,
        TestExecutionApiRequest.Target target,
        GatewayGraphContract contract,
        Map<String, String> resourceDependencyFingerprints,
        String dependencyPolicy,
        boolean certificationEligible,
        java.util.List<String> certificationGaps
) {
    public static final String SCHEMA_VERSION = "bloge.testGraphTargetDescriptor.v1";

    public TestGraphTargetDescriptor {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion.trim();
        resourceDependencyFingerprints = resourceDependencyFingerprints == null
                ? Map.of() : Map.copyOf(resourceDependencyFingerprints);
        dependencyPolicy = dependencyPolicy == null ? "" : dependencyPolicy.trim();
        certificationGaps = certificationGaps == null ? java.util.List.of()
                : java.util.List.copyOf(certificationGaps);
    }
}
