package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Domain-separated detached signature over one complete {@link MirrorRunEvidence} value.
 *
 * @param schemaVersion attestation protocol version
 * @param signatureStatus signature production and verification state
 * @param runId exact terminal run identity
 * @param planFingerprint exact mirror plan admitted by the run
 * @param evidenceFingerprint canonical fingerprint of the complete payload-free evidence
 * @param signedAt signing time included in the signed material
 * @param keyId verification key identifier
 * @param algorithm detached signature algorithm
 * @param signature base64 detached signature
 * @param independentlyVerifiable derived complete-signature claim
 */
public record MirrorEvidenceAttestation(
        String schemaVersion,
        SignatureStatus signatureStatus,
        String runId,
        String planFingerprint,
        String evidenceFingerprint,
        Instant signedAt,
        String keyId,
        String algorithm,
        String signature,
        boolean independentlyVerifiable
) {
    /** Legacy mirror-evidence attestation version. */
    public static final String SCHEMA_VERSION_V1 =
            "resourceGateway.mirrorEvidenceAttestation.v1";
    /** Current mirror-evidence attestation version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.mirrorEvidenceAttestation.v2";
    /** Stateful mirror-evidence attestation version with a distinct signature domain. */
    public static final String STATEFUL_SCHEMA_VERSION =
            "resourceGateway.mirrorEvidenceAttestation.v3";
    /** Read/write stateful attestation version with a distinct signature domain. */
    public static final String READ_WRITE_SCHEMA_VERSION =
            "resourceGateway.mirrorEvidenceAttestation.v4";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Persisted signature trust state. */
    public enum SignatureStatus {
        VERIFIED,
        VERIFICATION_UNAVAILABLE
    }

    /** Normalizes the manifest and derives its independent-verification claim. */
    public MirrorEvidenceAttestation {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)
                && !SCHEMA_VERSION_V1.equals(schemaVersion)
                && !STATEFUL_SCHEMA_VERSION.equals(schemaVersion)
                && !READ_WRITE_SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported mirror evidence attestation version");
        }
        signatureStatus = signatureStatus == null
                ? SignatureStatus.VERIFICATION_UNAVAILABLE : signatureStatus;
        runId = required(runId, "runId", 512);
        planFingerprint = requiredFingerprint(planFingerprint, "planFingerprint");
        evidenceFingerprint = requiredFingerprint(evidenceFingerprint, "evidenceFingerprint");
        signedAt = signedAt == null ? Instant.EPOCH : signedAt;
        keyId = bounded(keyId, "keyId", 1_024);
        algorithm = bounded(algorithm, "algorithm", 64);
        signature = bounded(signature, "signature", 16_384);
        boolean completeIdentity = fingerprint(planFingerprint) && fingerprint(evidenceFingerprint);
        independentlyVerifiable = signatureStatus == SignatureStatus.VERIFIED
                && completeIdentity && !Instant.EPOCH.equals(signedAt) && !keyId.isBlank()
                && !algorithm.isBlank() && !signature.isBlank();
        if (signatureStatus == SignatureStatus.VERIFIED && !independentlyVerifiable) {
            throw new IllegalArgumentException(
                    "verified mirror evidence requires a complete signature manifest");
        }
        if (signatureStatus == SignatureStatus.VERIFIED && !"Ed25519".equals(algorithm)) {
            throw new IllegalArgumentException("mirror evidence signatures must use Ed25519");
        }
        if (signatureStatus == SignatureStatus.VERIFICATION_UNAVAILABLE
                && (!completeIdentity || !Instant.EPOCH.equals(signedAt) || !keyId.isBlank()
                || !algorithm.isBlank() || !signature.isBlank() || independentlyVerifiable)) {
            throw new IllegalArgumentException(
                    "unavailable mirror evidence must retain identity without a signature claim");
        }
    }

    /**
     * Creates a fail-closed manifest when signing or immediate verification is unavailable.
     *
     * @param evidence complete payload-free evidence
     * @param evidenceFingerprint canonical evidence fingerprint
     * @return unavailable attestation bound to the exact run and plan
     */
    public static MirrorEvidenceAttestation unavailable(
            MirrorRunEvidence evidence, String evidenceFingerprint) {
        if (evidence == null) {
            throw new IllegalArgumentException("mirror run evidence is required");
        }
        String version = switch (evidence.schemaVersion()) {
            case MirrorRunEvidence.SCHEMA_VERSION_V1 -> SCHEMA_VERSION_V1;
            case MirrorRunEvidence.STATEFUL_SCHEMA_VERSION -> STATEFUL_SCHEMA_VERSION;
            case MirrorRunEvidence.READ_WRITE_SCHEMA_VERSION -> READ_WRITE_SCHEMA_VERSION;
            default -> SCHEMA_VERSION;
        };
        return new MirrorEvidenceAttestation(version, SignatureStatus.VERIFICATION_UNAVAILABLE,
                evidence.runId(), evidence.planFingerprint(), evidenceFingerprint,
                Instant.EPOCH, "", "", "", false);
    }

    /** Prevents detached signatures and complete fingerprint material from expanding generic logs. */
    @Override
    public String toString() {
        return "MirrorEvidenceAttestation[runId=" + runId + ", signatureStatus="
                + signatureStatus + ", keyId=" + keyId + ", algorithm=" + algorithm
                + ", signedAt=" + signedAt + "]";
    }

    private static boolean fingerprint(String value) {
        return FINGERPRINT.matcher(value).matches();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static String requiredFingerprint(String value, String field) {
        String normalized = normalized(value);
        if (!fingerprint(normalized)) {
            throw new IllegalArgumentException(field + " must be a canonical SHA-256 value");
        }
        return normalized;
    }

    private static String required(String value, String field, int maximumLength) {
        String normalized = normalized(value);
        if (normalized.isBlank() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " must be non-blank and bounded");
        }
        return normalized;
    }

    private static String bounded(String value, String field, int maximumLength) {
        String normalized = normalized(value);
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " exceeds its length limit");
        }
        return normalized;
    }
}
