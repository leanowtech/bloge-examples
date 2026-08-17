package com.leanowtech.bloge.gateway.capabilitystudio;

/** Stable HTTP read model for the non-production Feature Rehearsal experience. */
public record CapabilityStudioFeatureRehearsalProjection(
        String schemaVersion,
        Scenario scenario,
        Graph graph,
        Run run,
        CapabilityStudioDataLensProjection dataLens) {

    public static final String SCHEMA_VERSION =
            "resource-gateway.capability-studio.feature-rehearsal.v1";

    public CapabilityStudioFeatureRehearsalProjection {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported Feature Rehearsal schema version");
        }
    }

    public record Scenario(String id, String name, String expectedResult) {
    }

    public record Graph(String id, String fingerprint) {
    }

    public record Run(
            String runId,
            String status,
            String semanticFingerprint,
            int realExternalCallCount,
            String bindingMode) {
    }
}
