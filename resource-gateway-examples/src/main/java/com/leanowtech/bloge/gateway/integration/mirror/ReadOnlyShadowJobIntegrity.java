package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;

/** Canonical request and mutable-projection integrity boundary for durable Shadow jobs. */
public final class ReadOnlyShadowJobIntegrity {
    /** Maximum canonical request bytes accepted by durable admission. */
    public static final int MAXIMUM_REQUEST_BYTES =
            256 * 1024;
    /** Maximum canonical public-job bytes accepted by persistence. */
    public static final int MAXIMUM_JOB_BYTES =
            256 * 1024;

    private ReadOnlyShadowJobIntegrity() {
    }

    /** @return canonical immutable submission fingerprint */
    public static String requestFingerprint(
            ObjectMapper mapper,
            ReadOnlyShadowJobRequest request) {
        return ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                Objects.requireNonNull(request, "request"),
                MAXIMUM_REQUEST_BYTES);
    }

    /** @return deterministic job identity derived from the complete request, including scope */
    public static String jobId(String requestFingerprint) {
        String exact = Objects.requireNonNull(
                requestFingerprint, "requestFingerprint");
        if (!exact.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    "requestFingerprint must be canonical SHA-256");
        }
        return "shadow-" + exact.substring(
                "sha256:".length());
    }

    /**
     * Content-addresses an opaque worker identity before it enters the public lifecycle journal.
     *
     * @return stable owner correlation fingerprint, or blank when no lease owner exists
     */
    public static String ownerFingerprint(
            ObjectMapper mapper,
            String ownerId) {
        String exact = ownerId == null
                ? "" : ownerId.trim();
        if (exact.isBlank()) {
            return "";
        }
        if (!ReadOnlyShadowJobRepository.OWNER_ID
                .matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    "ownerId is invalid");
        }
        return ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                Map.of("ownerId", exact),
                1_024);
    }

    /** @return job projection sealed with its canonical mutable-state fingerprint */
    public static ReadOnlyShadowJob seal(
            ObjectMapper mapper,
            ReadOnlyShadowJob job) {
        ReadOnlyShadowJob material =
                Objects.requireNonNull(job, "job")
                        .withRecordFingerprint("");
        String fingerprint = ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                material,
                MAXIMUM_JOB_BYTES);
        return material.withRecordFingerprint(
                fingerprint);
    }

    /** Verifies a stored job projection without trusting its duplicated index columns. */
    public static void verify(
            ObjectMapper mapper,
            ReadOnlyShadowJob job) {
        if (job == null
                || job.recordFingerprint().isBlank()
                || !MessageDigest.isEqual(
                job.recordFingerprint().getBytes(
                        StandardCharsets.US_ASCII),
                seal(mapper, job)
                        .recordFingerprint()
                        .getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException(
                    "read-only Shadow job fingerprint mismatch");
        }
    }
}
