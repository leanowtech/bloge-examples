package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;

/**
 * Public result for a controlled graph execution.
 *
 * @param schemaVersion response schema version
 * @param runId persistent test-run identifier
 * @param target frozen target identity and fingerprint
 * @param fixtureBundleRef resolved inline or stored fixture provenance
 * @param plan server-compiled immutable plan; absent when request validation fails before compilation
 * @param evidence sanitized evidence projected to the requested verbosity
 */
public record TestExecutionApiResponse(
        String schemaVersion,
        String runId,
        TestExecutionApiRequest.Target target,
        ResolvedFixtureBundleRef fixtureBundleRef,
        EffectiveExecutionPlan plan,
        TestRunEvidence evidence
) {
    /** Current public execution response version. */
    public static final String SCHEMA_VERSION = "bloge.testExecutionResponse.v1";

    /**
     * @param source {@code INLINE} or {@code STORED}
     * @param fixtureBundleId stable fixture id
     * @param revision immutable revision
     * @param fingerprint canonical fixture fingerprint
     */
    public record ResolvedFixtureBundleRef(
            String source,
            String fixtureBundleId,
            long revision,
            String fingerprint
    ) {
    }

    /** Applies protocol defaults. */
    public TestExecutionApiResponse {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        runId = runId == null ? "" : runId.trim();
    }
}
