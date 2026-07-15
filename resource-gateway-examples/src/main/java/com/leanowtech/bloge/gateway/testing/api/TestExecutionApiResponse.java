package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.TestEvidenceIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;

/**
 * Public result for a controlled graph execution.
 *
 * @param schemaVersion response schema version
 * @param runId persistent test-run identifier
 * @param target frozen target identity and fingerprint
 * @param fixtureBundleRef resolved inline or stored fixture provenance
 * @param plan server-compiled immutable plan; absent when request validation fails before compilation
 * @param integrity detached signature and projection manifest
 * @param evidence sanitized evidence projected to the requested verbosity
 */
public record TestExecutionApiResponse(
        String schemaVersion,
        String runId,
        TestExecutionApiRequest.Target target,
        ResolvedFixtureBundleRef fixtureBundleRef,
        EffectiveExecutionPlan plan,
        TestEvidenceIntegrity integrity,
        TestRunEvidence evidence
) {
    /** Historical response without a detached evidence-integrity manifest. */
    public static final String SCHEMA_VERSION_V1 = "bloge.testExecutionResponse.v1";
    /** Current public execution response version. */
    public static final String SCHEMA_VERSION = "bloge.testExecutionResponse.v2";

    /**
     * Creates a compatibility response for tests and v1 adapters that do not supply integrity.
     *
     * @param schemaVersion response schema version
     * @param runId persistent test-run identifier
     * @param target frozen target identity
     * @param fixtureBundleRef exact fixture provenance
     * @param plan immutable effective plan
     * @param evidence projected evidence
     */
    public TestExecutionApiResponse(String schemaVersion, String runId,
                                    TestExecutionApiRequest.Target target,
                                    ResolvedFixtureBundleRef fixtureBundleRef,
                                    EffectiveExecutionPlan plan,
                                    TestRunEvidence evidence) {
        this(schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION_V1 : schemaVersion,
                runId, target, fixtureBundleRef, plan,
                TestEvidenceIntegrity.unsigned(), evidence);
    }

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
        integrity = integrity == null ? TestEvidenceIntegrity.unsigned() : integrity;
    }
}
