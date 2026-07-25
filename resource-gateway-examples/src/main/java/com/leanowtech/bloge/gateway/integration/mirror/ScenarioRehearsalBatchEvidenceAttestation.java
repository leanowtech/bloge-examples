package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Domain-separated detached signature over one terminal Scenario batch evidence index.
 *
 * @param schemaVersion exact attestation protocol version
 * @param signatureStatus signature production and verification state
 * @param jobId stable full-scope batch identity
 * @param requestFingerprint exact original request content address
 * @param manifestFingerprint exact immutable execution closure
 * @param terminalJobFingerprint exact terminal job projection
 * @param indexFingerprint complete ordered terminal index identity
 * @param signedAt signing time included in signed material
 * @param keyId verification key identifier
 * @param algorithm detached signature algorithm
 * @param signature base64 detached signature
 * @param independentlyVerifiable derived complete-signature claim
 */
public record ScenarioRehearsalBatchEvidenceAttestation(
        String schemaVersion,
        SignatureStatus signatureStatus,
        String jobId,
        String requestFingerprint,
        String manifestFingerprint,
        String terminalJobFingerprint,
        String indexFingerprint,
        Instant signedAt,
        String keyId,
        String algorithm,
        String signature,
        boolean independentlyVerifiable
) {
    /** Legacy v1 Scenario batch evidence-attestation version. */
    public static final String V1_SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalBatchEvidenceAttestation.v1";
    /** Current v2 Scenario batch evidence-attestation version. */
    public static final String V2_SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalBatchEvidenceAttestation.v2";
    /** Current Scenario batch evidence-attestation version. */
    public static final String SCHEMA_VERSION =
            V2_SCHEMA_VERSION;
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");

    /** Persisted signature trust state. */
    public enum SignatureStatus {
        VERIFIED,
        VERIFICATION_UNAVAILABLE
    }

    /** Validates identity and derives the independent-verification claim. */
    public ScenarioRehearsalBatchEvidenceAttestation {
        schemaVersion = schemaVersion == null
                || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!V1_SCHEMA_VERSION.equals(schemaVersion)
                && !V2_SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported Scenario batch evidence attestation version");
        }
        signatureStatus = signatureStatus == null
                ? SignatureStatus.VERIFICATION_UNAVAILABLE
                : signatureStatus;
        jobId = required(jobId, "jobId", 512);
        if (!ScenarioRehearsalBatchIdentity.hasCanonicalShape(jobId)) {
            throw new IllegalArgumentException(
                    "jobId must be a canonical Scenario batch identity");
        }
        requestFingerprint = fingerprint(
                requestFingerprint, "requestFingerprint");
        manifestFingerprint = fingerprint(
                manifestFingerprint, "manifestFingerprint");
        terminalJobFingerprint = fingerprint(
                terminalJobFingerprint, "terminalJobFingerprint");
        indexFingerprint = fingerprint(
                indexFingerprint, "indexFingerprint");
        signedAt = signedAt == null ? Instant.EPOCH : signedAt;
        keyId = bounded(keyId, "keyId", 1_024);
        algorithm = bounded(algorithm, "algorithm", 64);
        signature = bounded(signature, "signature", 16_384);
        independentlyVerifiable =
                signatureStatus == SignatureStatus.VERIFIED
                        && !Instant.EPOCH.equals(signedAt)
                        && !keyId.isBlank()
                        && !algorithm.isBlank()
                        && !signature.isBlank();
        if (signatureStatus == SignatureStatus.VERIFIED
                && (!independentlyVerifiable
                || !"Ed25519".equals(algorithm))) {
            throw new IllegalArgumentException(
                    "verified Scenario batch evidence requires a complete Ed25519 signature");
        }
        if (signatureStatus == SignatureStatus.VERIFICATION_UNAVAILABLE
                && (!Instant.EPOCH.equals(signedAt)
                || !keyId.isBlank()
                || !algorithm.isBlank()
                || !signature.isBlank()
                || independentlyVerifiable)) {
            throw new IllegalArgumentException(
                    "unavailable Scenario batch evidence cannot claim a signature");
        }
    }

    /**
     * Creates a fail-closed manifest when signing cannot establish integrity.
     *
     * @param index complete content-addressed terminal index
     * @return identity-bound manifest without a signature claim
     */
    public static ScenarioRehearsalBatchEvidenceAttestation unavailable(
            ScenarioRehearsalBatchEvidenceIndex index) {
        ScenarioRehearsalBatchEvidenceIndex exact =
                java.util.Objects.requireNonNull(index, "index");
        return new ScenarioRehearsalBatchEvidenceAttestation(
                ScenarioRehearsalBatchEvidenceIndex
                        .V1_SCHEMA_VERSION
                        .equals(exact.schemaVersion())
                        ? V1_SCHEMA_VERSION
                        : V2_SCHEMA_VERSION,
                SignatureStatus.VERIFICATION_UNAVAILABLE,
                exact.job().jobId(),
                exact.job().requestFingerprint(),
                exact.manifest().manifestFingerprint(),
                exact.job().recordFingerprint(),
                exact.indexFingerprint(),
                Instant.EPOCH,
                "",
                "",
                "",
                false);
    }

    /** Keeps detached signature material out of ordinary logs. */
    @Override
    public String toString() {
        return "ScenarioRehearsalBatchEvidenceAttestation[jobId="
                + jobId + ", signatureStatus=" + signatureStatus
                + ", keyId=" + keyId + ", signedAt=" + signedAt + "]";
    }

    private static String fingerprint(String value, String field) {
        String normalized = normalized(value);
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be a canonical SHA-256 value");
        }
        return normalized;
    }

    private static String required(
            String value, String field, int maximumLength) {
        String normalized = normalized(value);
        if (normalized.isBlank()
                || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(
                    field + " must be non-blank and bounded");
        }
        return normalized;
    }

    private static String bounded(
            String value, String field, int maximumLength) {
        String normalized = normalized(value);
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(
                    field + " exceeds its length limit");
        }
        return normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
