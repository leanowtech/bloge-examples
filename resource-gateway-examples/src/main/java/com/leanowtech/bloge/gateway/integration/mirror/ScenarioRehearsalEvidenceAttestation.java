package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Domain-separated detached signature over one complete Scenario rehearsal result.
 *
 * @param schemaVersion exact attestation protocol version
 * @param signatureStatus signature production and verification state
 * @param runId stable aggregate run identity
 * @param requestId caller idempotency identity
 * @param compiledPlanFingerprint exact compiled rehearsal generation
 * @param resultFingerprint canonical fingerprint of the complete payload-free result
 * @param signedAt signing time included in the signed material
 * @param keyId verification key identifier
 * @param algorithm detached signature algorithm
 * @param signature base64 detached signature
 * @param independentlyVerifiable derived complete-signature claim
 */
public record ScenarioRehearsalEvidenceAttestation(
        String schemaVersion,
        SignatureStatus signatureStatus,
        String runId,
        String requestId,
        String compiledPlanFingerprint,
        String resultFingerprint,
        Instant signedAt,
        String keyId,
        String algorithm,
        String signature,
        boolean independentlyVerifiable
) {
    /** Current Scenario rehearsal evidence-attestation version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalEvidenceAttestation.v1";
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern RUN_ID =
            Pattern.compile("scenario-[a-f0-9]{64}");

    /** Persisted signature trust state. */
    public enum SignatureStatus {
        VERIFIED,
        VERIFICATION_UNAVAILABLE
    }

    /** Validates identity and derives the independent-verification claim. */
    public ScenarioRehearsalEvidenceAttestation {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported Scenario rehearsal evidence attestation version");
        }
        signatureStatus = signatureStatus == null
                ? SignatureStatus.VERIFICATION_UNAVAILABLE : signatureStatus;
        runId = normalized(runId);
        if (!RUN_ID.matcher(runId).matches()) {
            throw new IllegalArgumentException(
                    "runId must be a canonical Scenario aggregate identity");
        }
        requestId = required(requestId, "requestId", 256);
        compiledPlanFingerprint = fingerprint(
                compiledPlanFingerprint, "compiledPlanFingerprint");
        resultFingerprint = fingerprint(
                resultFingerprint, "resultFingerprint");
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
                    "verified Scenario evidence requires a complete Ed25519 signature");
        }
        if (signatureStatus == SignatureStatus.VERIFICATION_UNAVAILABLE
                && (!Instant.EPOCH.equals(signedAt)
                || !keyId.isBlank()
                || !algorithm.isBlank()
                || !signature.isBlank()
                || independentlyVerifiable)) {
            throw new IllegalArgumentException(
                    "unavailable Scenario evidence cannot claim a signature");
        }
    }

    /**
     * Creates a fail-closed manifest when signing cannot establish integrity.
     *
     * @param runId stable aggregate run identity
     * @param result complete payload-free aggregate
     * @return identity-bound manifest without a signature claim
     */
    public static ScenarioRehearsalEvidenceAttestation unavailable(
            String runId, ScenarioRehearsalResult result) {
        if (result == null) {
            throw new IllegalArgumentException(
                    "Scenario rehearsal result is required");
        }
        String resultFingerprint = FINGERPRINT.matcher(
                result.resultFingerprint()).matches()
                ? result.resultFingerprint()
                : "sha256:" + "0".repeat(64);
        return new ScenarioRehearsalEvidenceAttestation(
                SCHEMA_VERSION,
                SignatureStatus.VERIFICATION_UNAVAILABLE,
                runId,
                result.requestId(),
                result.compiledPlanRef().fingerprint(),
                resultFingerprint,
                Instant.EPOCH,
                "",
                "",
                "",
                false);
    }

    /** Keeps detached signature material out of ordinary logs. */
    @Override
    public String toString() {
        return "ScenarioRehearsalEvidenceAttestation[runId="
                + runId + ", signatureStatus=" + signatureStatus
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
