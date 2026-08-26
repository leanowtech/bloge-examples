package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

/** Verifies the E1 payload-free projection against a typed test run and optional asset bindings. */
public final class TestRunControlEvidenceVerifier {
    private static final String RESERVED_METADATA_KEY = "functionControlEvidence";

    private TestRunControlEvidenceVerifier() {
    }

    /**
     * Returns the projection carried by a run, or {@code null} for historical ordinary runs.
     * The run/target/execution-plan binding is always checked when a projection is present.
     * @param run typed test run
     * @return verified projection, or null for an ordinary historical run
     */
    public static TestRunControlEvidenceProjection verify(TestRun run) {
        if (run == null) throw invalid();
        JsonNode response = run.rawResponse();
        if (response == null || !response.isObject()) return null;
        JsonNode evidence = response.path("evidence");
        JsonNode metadata = evidence.path("metadata");
        if (metadata.isMissingNode() || metadata.isNull()) return null;
        if (!metadata.isObject() || metadata.has(RESERVED_METADATA_KEY)) throw invalid();
        JsonNode projection = metadata.get("controlEvidenceProjection");
        if (projection == null || projection.isNull()) return null;
        TestRunControlEvidenceProjection result =
                TestRunControlEvidenceProjection.from(projection);
        if (!run.runId().equals(result.runId())
                || !run.targetFingerprint().equals(result.targetFingerprint())
                || !run.planFingerprint().equals(result.executionPlanFingerprint())) throw invalid();
        return result;
    }

    /** Verifies the run projection and the exact scenario/world/function asset bindings.
     * @param run typed test run
     * @param scenarioFingerprint expected scenario fingerprint, or empty
     * @param worldFingerprint expected world fingerprint, or empty
     * @param functionPlanFingerprint expected function plan fingerprint, or empty
     * @return verified payload-free projection
     */
    public static TestRunControlEvidenceProjection verify(TestRun run,
                                                          String scenarioFingerprint,
                                                          String worldFingerprint,
                                                          String functionPlanFingerprint) {
        TestRunControlEvidenceProjection projection = verify(run);
        if (projection == null) throw invalid();
        if (!safeOptionalFingerprint(scenarioFingerprint).equals(projection.scenarioFingerprint())
                || !safeOptionalFingerprint(worldFingerprint).equals(projection.worldFingerprint())
                || !safeOptionalFingerprint(functionPlanFingerprint)
                .equals(projection.functionPlanFingerprint())) throw invalid();
        return projection;
    }

    private static String safeFingerprint(String value) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) throw invalid();
        return value;
    }

    private static String safeOptionalFingerprint(String value) {
        if (value == null || value.isBlank()) return "";
        return safeFingerprint(value);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid test-run control evidence binding");
    }
}
