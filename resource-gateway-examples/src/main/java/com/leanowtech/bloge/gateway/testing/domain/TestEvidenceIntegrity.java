package com.leanowtech.bloge.gateway.testing.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Cryptographic integrity manifest for one sanitized test-run evidence value.
 *
 * <p>The signature covers a domain-separated canonical envelope containing
 * {@link #evidenceFingerprint} and {@link #signedAt}; the evidence fingerprint is computed from the
 * complete persisted {@link TestRunEvidence}. Summary and standard responses retain that seal as
 * lineage but cannot claim independent verification because their payload is a projection of the
 * signed value.</p>
 *
 * @param schemaVersion integrity-manifest protocol version
 * @param evidenceFingerprint canonical fingerprint of the complete persisted evidence
 * @param signatureStatus signature production and verification state
 * @param keyId verification-key identifier
 * @param algorithm signature algorithm
 * @param signedAt signing time included in the signed canonical envelope
 * @param signature base64-encoded detached signature
 * @param projection response evidence projection
 * @param projectionFingerprint canonical fingerprint of the evidence value in this response
 * @param independentlyVerifiable whether this response contains the exact complete signed evidence
 */
public record TestEvidenceIntegrity(
        String schemaVersion,
        String evidenceFingerprint,
        SignatureStatus signatureStatus,
        String keyId,
        String algorithm,
        Instant signedAt,
        String signature,
        Projection projection,
        String projectionFingerprint,
        boolean independentlyVerifiable
) {
    /** Current test-evidence integrity protocol version. */
    public static final String SCHEMA_VERSION = "bloge.testEvidenceIntegrity.v1";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Persisted signature state exposed without leaking provider diagnostics. */
    public enum SignatureStatus {
        /** The detached signature was verified against the advertised evidence fingerprint. */
        VERIFIED,
        /** Historical evidence has no detached signature. */
        UNSIGNED,
        /** The signing or verification authority could not establish a trusted signature. */
        VERIFICATION_UNAVAILABLE
    }

    /** Evidence projection carried by the enclosing execution response. */
    public enum Projection {
        /** Terminal state and bounded aggregate facts only. */
        SUMMARY,
        /** Summary plus payload-free node and edge facts. */
        STANDARD,
        /** Complete sanitized evidence used to compute {@link #evidenceFingerprint}. */
        FULL
    }

    /** Normalizes protocol values and derives the independent-verification claim. */
    public TestEvidenceIntegrity {
        schemaVersion = defaulted(schemaVersion, SCHEMA_VERSION);
        evidenceFingerprint = normalized(evidenceFingerprint);
        signatureStatus = signatureStatus == null ? SignatureStatus.UNSIGNED : signatureStatus;
        keyId = normalized(keyId);
        algorithm = normalized(algorithm);
        signedAt = signedAt == null ? Instant.EPOCH : signedAt;
        signature = normalized(signature);
        projection = projection == null ? Projection.FULL : projection;
        projectionFingerprint = normalized(projectionFingerprint);
        independentlyVerifiable = signatureStatus == SignatureStatus.VERIFIED
                && projection == Projection.FULL
                && evidenceFingerprint.equals(projectionFingerprint);
        if (signatureStatus == SignatureStatus.VERIFIED
                && (!fingerprint(evidenceFingerprint) || keyId.isBlank()
                || algorithm.isBlank() || signature.isBlank() || Instant.EPOCH.equals(signedAt))) {
            throw new IllegalArgumentException("Verified test evidence requires a complete signature manifest");
        }
        if (!projectionFingerprint.isBlank() && !fingerprint(projectionFingerprint)) {
            throw new IllegalArgumentException("projectionFingerprint must be a canonical SHA-256 fingerprint");
        }
    }

    /**
     * Creates the historical compatibility state for evidence without a signature manifest.
     *
     * @return unsigned full-evidence integrity manifest
     */
    public static TestEvidenceIntegrity unsigned() {
        return new TestEvidenceIntegrity("", "", SignatureStatus.UNSIGNED, "", "",
                Instant.EPOCH, "", Projection.FULL, "", false);
    }

    /**
     * Creates a fail-closed manifest when the signing authority could not establish integrity.
     *
     * @param evidenceFingerprint canonical complete evidence fingerprint
     * @return unavailable full-evidence integrity manifest
     */
    public static TestEvidenceIntegrity unavailable(String evidenceFingerprint) {
        return new TestEvidenceIntegrity("", evidenceFingerprint,
                SignatureStatus.VERIFICATION_UNAVAILABLE, "", "", Instant.EPOCH, "",
                Projection.FULL, evidenceFingerprint, false);
    }

    /**
     * Retains the full-evidence signature while identifying the response projection exactly.
     *
     * @param selectedProjection response projection
     * @param selectedProjectionFingerprint canonical response evidence fingerprint
     * @return immutable projected integrity manifest
     */
    public TestEvidenceIntegrity withProjection(Projection selectedProjection,
                                                String selectedProjectionFingerprint) {
        return new TestEvidenceIntegrity(schemaVersion, evidenceFingerprint, signatureStatus,
                keyId, algorithm, signedAt, signature,
                Objects.requireNonNull(selectedProjection, "selectedProjection"),
                selectedProjectionFingerprint, false);
    }

    /**
     * Indicates whether a detached signature is present and previously verified by the producer.
     *
     * @return true only for a complete verified signature manifest
     */
    public boolean signed() {
        return signatureStatus == SignatureStatus.VERIFIED
                && !keyId.isBlank() && !signature.isBlank();
    }

    private static boolean fingerprint(String value) {
        return FINGERPRINT.matcher(value).matches();
    }

    private static String defaulted(String value, String fallback) {
        String safe = normalized(value);
        return safe.isBlank() ? fallback : safe;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
