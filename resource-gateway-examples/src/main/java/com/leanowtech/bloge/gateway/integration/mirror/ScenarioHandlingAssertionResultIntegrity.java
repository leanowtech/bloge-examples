package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/** Canonical content-addressing boundary for payload-free handling-assertion results. */
public final class ScenarioHandlingAssertionResultIntegrity {
    /** Maximum canonical bytes admitted for one assertion result. */
    public static final int MAXIMUM_BYTES = 256 * 1024;

    private ScenarioHandlingAssertionResultIntegrity() {
    }

    /**
     * Seals one evaluator-produced assertion result.
     *
     * @param mapper canonical protocol mapper
     * @param result unsealed result material
     * @return sealed immutable result
     */
    public static ScenarioHandlingAssertionResult seal(
            ObjectMapper mapper,
            ScenarioHandlingAssertionResult result) {
        Objects.requireNonNull(mapper, "mapper");
        ScenarioHandlingAssertionResult material =
                Objects.requireNonNull(result, "result").withFingerprint("");
        return material.withFingerprint(
                ProtocolFingerprint.ofBounded(
                        mapper, material, MAXIMUM_BYTES));
    }

    /**
     * Independently recomputes one result fingerprint.
     *
     * @param mapper canonical protocol mapper
     * @param result sealed result
     */
    public static void verify(
            ObjectMapper mapper,
            ScenarioHandlingAssertionResult result) {
        if (result == null || result.resultFingerprint().isBlank()
                || !constantTimeEquals(
                result.resultFingerprint(),
                seal(mapper, result).resultFingerprint())) {
            throw new IllegalArgumentException(
                    "scenario handling assertion result fingerprint mismatch");
        }
    }

    /** @return exact SCENARIO_ASSERTION_RESULT reference for a sealed result */
    public static MirrorArtifactRef reference(
            ScenarioHandlingAssertionResult result) {
        if (result == null || result.resultFingerprint().isBlank()) {
            throw new IllegalArgumentException(
                    "scenario handling assertion result must be sealed before reference");
        }
        return new MirrorArtifactRef(
                "SCENARIO_ASSERTION_RESULT",
                result.runId() + ":" + result.assertionRef().id(),
                result.assertionRef().revision(),
                result.resultFingerprint());
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }
}
