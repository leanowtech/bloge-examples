package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/** Canonical content-addressing boundary for durable Scenario batch job projections. */
public final class ScenarioRehearsalBatchIntegrity {
    /** Maximum canonical bytes admitted for one public job projection. */
    public static final int MAXIMUM_JOB_BYTES = 256 * 1024;

    private ScenarioRehearsalBatchIntegrity() {
    }

    /** @return projection sealed with its canonical mutable-state fingerprint */
    public static ScenarioRehearsalBatchJob seal(
            ObjectMapper mapper,
            ScenarioRehearsalBatchJob job) {
        Objects.requireNonNull(mapper, "mapper");
        ScenarioRehearsalBatchJob material =
                Objects.requireNonNull(job, "job")
                        .withRecordFingerprint("");
        String fingerprint = ProtocolFingerprint.ofBounded(
                mapper, material, MAXIMUM_JOB_BYTES);
        return material.withRecordFingerprint(fingerprint);
    }

    /** Verifies one public job projection in constant time. */
    public static void verify(
            ObjectMapper mapper,
            ScenarioRehearsalBatchJob job) {
        if (job == null
                || job.recordFingerprint().isBlank()
                || !constantTimeEquals(
                job.recordFingerprint(),
                seal(mapper, job).recordFingerprint())) {
            throw new IllegalArgumentException(
                    "Scenario rehearsal batch job fingerprint mismatch");
        }
    }

    private static boolean constantTimeEquals(
            String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }
}
