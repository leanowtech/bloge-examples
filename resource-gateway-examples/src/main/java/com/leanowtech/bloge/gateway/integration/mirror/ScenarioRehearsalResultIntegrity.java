package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/** Canonical content-addressing boundary for Scenario case and aggregate results. */
public final class ScenarioRehearsalResultIntegrity {
    /** Maximum canonical bytes admitted for one case result. */
    public static final int MAXIMUM_CASE_BYTES = 512 * 1024;
    /** Maximum canonical bytes admitted for one complete aggregate result. */
    public static final int MAXIMUM_AGGREGATE_BYTES = 160 * 1024 * 1024;

    private ScenarioRehearsalResultIntegrity() {
    }

    /** @return sealed immutable case result */
    public static ScenarioCaseRehearsalResult sealCase(
            ObjectMapper mapper,
            ScenarioCaseRehearsalResult result) {
        Objects.requireNonNull(mapper, "mapper");
        ScenarioCaseRehearsalResult material =
                Objects.requireNonNull(result, "result").withFingerprint("");
        return material.withFingerprint(
                ProtocolFingerprint.ofBounded(
                        mapper, material, MAXIMUM_CASE_BYTES));
    }

    /** Verifies one case-result content address. */
    public static void verifyCase(
            ObjectMapper mapper,
            ScenarioCaseRehearsalResult result) {
        if (result == null
                || result.resultFingerprint().isBlank()
                || !constantTimeEquals(
                result.resultFingerprint(),
                sealCase(mapper, result).resultFingerprint())) {
            throw new IllegalArgumentException(
                    "scenario case rehearsal result fingerprint mismatch");
        }
        result.assertionResults().forEach(assertion ->
                ScenarioHandlingAssertionResultIntegrity.verify(
                        mapper, assertion));
    }

    /** @return sealed immutable aggregate result */
    public static ScenarioRehearsalResult seal(
            ObjectMapper mapper,
            ScenarioRehearsalResult result) {
        Objects.requireNonNull(mapper, "mapper");
        ScenarioRehearsalResult material =
                Objects.requireNonNull(result, "result").withFingerprint("");
        return material.withFingerprint(
                ProtocolFingerprint.ofBounded(
                        mapper, material, MAXIMUM_AGGREGATE_BYTES));
    }

    /** Verifies the aggregate and every nested case and assertion result. */
    public static void verify(
            ObjectMapper mapper,
            ScenarioRehearsalResult result) {
        if (result == null
                || result.resultFingerprint().isBlank()
                || !constantTimeEquals(
                result.resultFingerprint(),
                seal(mapper, result).resultFingerprint())) {
            throw new IllegalArgumentException(
                    "scenario rehearsal result fingerprint mismatch");
        }
        result.caseResults().forEach(value -> verifyCase(mapper, value));
    }

    /** @return exact SCENARIO_REHEARSAL_RESULT reference for a sealed aggregate */
    public static MirrorArtifactRef reference(
            ScenarioRehearsalResult result) {
        if (result == null || result.resultFingerprint().isBlank()) {
            throw new IllegalArgumentException(
                    "scenario rehearsal result must be sealed before reference");
        }
        return new MirrorArtifactRef(
                "SCENARIO_REHEARSAL_RESULT",
                result.requestId(),
                result.compiledPlanRef().revision(),
                result.resultFingerprint());
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }
}
