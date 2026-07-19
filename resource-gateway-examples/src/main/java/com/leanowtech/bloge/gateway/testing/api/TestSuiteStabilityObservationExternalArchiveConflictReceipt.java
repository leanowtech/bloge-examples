package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Signed proof that an external authority already binds an object id to different material.
 *
 * <p>An HTTP 409 response is safety-significant only when this receipt verifies against a
 * configured authority key and exact request. The observed commitment is opaque and payload-free;
 * it proves non-equality without returning the conflicting retirement or archive content.</p>
 *
 * @param schemaVersion conflict-receipt protocol version
 * @param conflictFingerprint canonical signed material fingerprint
 * @param requestFingerprint exact challenge-bound request identity
 * @param trustDomain expected external archive trust domain
 * @param archiveSetId expected external archive set
 * @param authorityId configured independent authority
 * @param failureDomain configured independent failure domain
 * @param keyId configured Ed25519 verification key
 * @param objectId deterministic immutable object identity
 * @param expectedObjectCommitment commitment derived from the current request
 * @param observedObjectCommitment different commitment already bound by the authority
 * @param issuedAt whole-second conflict observation time
 * @param expiresAt exclusive short conflict-admission deadline
 * @param algorithm signature algorithm, fixed to Ed25519
 * @param signature base64 detached signature over {@code conflictFingerprint}
 */
public record TestSuiteStabilityObservationExternalArchiveConflictReceipt(
        String schemaVersion,
        String conflictFingerprint,
        String requestFingerprint,
        String trustDomain,
        String archiveSetId,
        String authorityId,
        String failureDomain,
        String keyId,
        String objectId,
        String expectedObjectCommitment,
        String observedObjectCommitment,
        Instant issuedAt,
        Instant expiresAt,
        String algorithm,
        String signature) {
    /** Current signed immutable-conflict receipt generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityObservationExternalArchiveConflictReceipt.v1";

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern OBJECT_ID =
            Pattern.compile("stability-observation-worm-[a-f0-9]{64}");

    /** Canonical conflict material signed by one external authority. */
    public record Material(
            String schemaVersion,
            String requestFingerprint,
            String trustDomain,
            String archiveSetId,
            String authorityId,
            String failureDomain,
            String keyId,
            String objectId,
            String expectedObjectCommitment,
            String observedObjectCommitment,
            Instant issuedAt,
            Instant expiresAt,
            String algorithm) {
    }

    /** Rejects unsigned, self-equal, malformed, or long-lived conflict assertions. */
    public TestSuiteStabilityObservationExternalArchiveConflictReceipt {
        schemaVersion = normalized(schemaVersion);
        conflictFingerprint = normalized(conflictFingerprint);
        requestFingerprint = normalized(requestFingerprint);
        trustDomain = normalized(trustDomain);
        archiveSetId = normalized(archiveSetId);
        authorityId = normalized(authorityId);
        failureDomain = normalized(failureDomain);
        keyId = normalized(keyId);
        objectId = normalized(objectId);
        expectedObjectCommitment = normalized(expectedObjectCommitment);
        observedObjectCommitment = normalized(observedObjectCommitment);
        algorithm = normalized(algorithm);
        signature = normalized(signature);
        boolean validSignature;
        try {
            validSignature = Base64.getDecoder().decode(signature).length == 64;
        } catch (IllegalArgumentException malformed) {
            validSignature = false;
        }
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !FINGERPRINT.matcher(conflictFingerprint).matches()
                || !FINGERPRINT.matcher(requestFingerprint).matches()
                || !IDENTIFIER.matcher(trustDomain).matches()
                || !IDENTIFIER.matcher(archiveSetId).matches()
                || !IDENTIFIER.matcher(authorityId).matches()
                || !IDENTIFIER.matcher(failureDomain).matches()
                || !IDENTIFIER.matcher(keyId).matches()
                || !OBJECT_ID.matcher(objectId).matches()
                || !FINGERPRINT.matcher(expectedObjectCommitment).matches()
                || !FINGERPRINT.matcher(observedObjectCommitment).matches()
                || expectedObjectCommitment.equals(observedObjectCommitment)
                || issuedAt == null || expiresAt == null
                || issuedAt.getNano() != 0 || expiresAt.getNano() != 0
                || !expiresAt.isAfter(issuedAt)
                || Duration.between(issuedAt, expiresAt).compareTo(
                TestSuiteStabilityObservationExternalArchiveRequest.MAXIMUM_LIFETIME) > 0
                || !"Ed25519".equals(algorithm) || !validSignature) {
            throw new IllegalArgumentException(
                    "Invalid external observation-archive conflict receipt");
        }
    }

    /** @return exact canonical material protected by the detached signature */
    public Material material() {
        return new Material(schemaVersion, requestFingerprint, trustDomain, archiveSetId,
                authorityId, failureDomain, keyId, objectId, expectedObjectCommitment,
                observedObjectCommitment, issuedAt, expiresAt, algorithm);
    }

    /** @return whether the claimed fingerprint covers every conflict field */
    public boolean fingerprintVerified(ObjectMapper objectMapper) {
        return conflictFingerprint.equals(ProtocolFingerprint.of(
                Objects.requireNonNull(objectMapper, "objectMapper"), material()));
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
