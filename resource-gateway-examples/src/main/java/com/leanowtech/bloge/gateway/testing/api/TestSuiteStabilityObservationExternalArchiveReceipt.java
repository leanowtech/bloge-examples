package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Independently signed acknowledgement for one externally immutable floor-retirement object.
 *
 * <p>The trusted authority attests that the exact retirement and nested compact archive are stored
 * outside Resource Gateway, in compliance retention mode, and cannot be overwritten or deleted
 * before {@code retainUntil}. The receipt is short-lived for commit admission; expiration does not
 * shorten the separately signed object-retention deadline.</p>
 *
 * @param schemaVersion receipt protocol version
 * @param receiptFingerprint canonical signed material fingerprint
 * @param requestFingerprint exact challenge-bound request identity
 * @param trustDomain expected external archive trust domain
 * @param archiveSetId expected external archive set
 * @param authorityId configured independent authority
 * @param failureDomain configured independent failure domain
 * @param keyId configured Ed25519 verification key
 * @param objectId deterministic immutable object identity
 * @param retirementId exact stored retirement identity
 * @param retirementFingerprint exact complete retirement fingerprint
 * @param segmentId exact nested archive segment identity
 * @param segmentFingerprint exact nested archive fingerprint
 * @param retentionPolicyFingerprint immutable retirement policy identity
 * @param retainUntil authority-enforced immutable deadline
 * @param storedAt external object commit time
 * @param issuedAt whole-second receipt creation time
 * @param expiresAt exclusive short receipt-admission deadline
 * @param retentionMode fixed compliance retention mode
 * @param externallyDurable authority assertion that storage is outside Gateway's database
 * @param writeOnce authority assertion that the object identity cannot be overwritten
 * @param deleteBeforeRetentionDenied authority assertion that early delete is denied
 * @param algorithm signature algorithm, fixed to Ed25519
 * @param signature base64 detached signature over {@code receiptFingerprint}
 */
public record TestSuiteStabilityObservationExternalArchiveReceipt(
        String schemaVersion,
        String receiptFingerprint,
        String requestFingerprint,
        String trustDomain,
        String archiveSetId,
        String authorityId,
        String failureDomain,
        String keyId,
        String objectId,
        String retirementId,
        String retirementFingerprint,
        String segmentId,
        String segmentFingerprint,
        String retentionPolicyFingerprint,
        Instant retainUntil,
        Instant storedAt,
        Instant issuedAt,
        Instant expiresAt,
        RetentionMode retentionMode,
        boolean externallyDurable,
        boolean writeOnce,
        boolean deleteBeforeRetentionDenied,
        String algorithm,
        String signature) {
    /** Current signed external archive receipt generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityObservationExternalArchiveReceipt.v1";
    /** Largest accepted receipt admission lifetime. */
    public static final Duration MAXIMUM_LIFETIME = Duration.ofSeconds(60);

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern RETIREMENT_ID =
            Pattern.compile("stability-observation-retirement-[a-f0-9]{64}");
    private static final Pattern SEGMENT_ID =
            Pattern.compile("stability-observation-archive-[a-f0-9]{64}");
    private static final Pattern OBJECT_ID =
            Pattern.compile("stability-observation-worm-[a-f0-9]{64}");

    /** Closed immutable-retention mode accepted by the deletion gate. */
    public enum RetentionMode {
        /** Retention cannot be shortened or bypassed by ordinary privileged users. */
        COMPLIANCE
    }

    /** Canonical receipt material signed by one external authority. */
    public record Material(
            String schemaVersion,
            String requestFingerprint,
            String trustDomain,
            String archiveSetId,
            String authorityId,
            String failureDomain,
            String keyId,
            String objectId,
            String retirementId,
            String retirementFingerprint,
            String segmentId,
            String segmentFingerprint,
            String retentionPolicyFingerprint,
            Instant retainUntil,
            Instant storedAt,
            Instant issuedAt,
            Instant expiresAt,
            RetentionMode retentionMode,
            boolean externallyDurable,
            boolean writeOnce,
            boolean deleteBeforeRetentionDenied,
            String algorithm) {
    }

    /** Enforces complete accepted-receipt shape without trusting its signature. */
    public TestSuiteStabilityObservationExternalArchiveReceipt {
        schemaVersion = normalized(schemaVersion);
        receiptFingerprint = normalized(receiptFingerprint);
        requestFingerprint = normalized(requestFingerprint);
        trustDomain = normalized(trustDomain);
        archiveSetId = normalized(archiveSetId);
        authorityId = normalized(authorityId);
        failureDomain = normalized(failureDomain);
        keyId = normalized(keyId);
        objectId = normalized(objectId);
        retirementId = normalized(retirementId);
        retirementFingerprint = normalized(retirementFingerprint);
        segmentId = normalized(segmentId);
        segmentFingerprint = normalized(segmentFingerprint);
        retentionPolicyFingerprint = normalized(retentionPolicyFingerprint);
        algorithm = normalized(algorithm);
        signature = normalized(signature);
        boolean validSignature;
        try {
            validSignature = Base64.getDecoder().decode(signature).length == 64;
        } catch (IllegalArgumentException malformed) {
            validSignature = false;
        }
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !FINGERPRINT.matcher(receiptFingerprint).matches()
                || !FINGERPRINT.matcher(requestFingerprint).matches()
                || !IDENTIFIER.matcher(trustDomain).matches()
                || !IDENTIFIER.matcher(archiveSetId).matches()
                || !IDENTIFIER.matcher(authorityId).matches()
                || !IDENTIFIER.matcher(failureDomain).matches()
                || !IDENTIFIER.matcher(keyId).matches()
                || !OBJECT_ID.matcher(objectId).matches()
                || !RETIREMENT_ID.matcher(retirementId).matches()
                || !FINGERPRINT.matcher(retirementFingerprint).matches()
                || !SEGMENT_ID.matcher(segmentId).matches()
                || !FINGERPRINT.matcher(segmentFingerprint).matches()
                || !FINGERPRINT.matcher(retentionPolicyFingerprint).matches()
                || retainUntil == null || storedAt == null || issuedAt == null
                || expiresAt == null || issuedAt.getNano() != 0 || expiresAt.getNano() != 0
                || storedAt.isAfter(issuedAt) || !retainUntil.isAfter(storedAt)
                || !expiresAt.isAfter(issuedAt)
                || Duration.between(issuedAt, expiresAt)
                .compareTo(MAXIMUM_LIFETIME) > 0
                || retentionMode != RetentionMode.COMPLIANCE
                || !externallyDurable || !writeOnce || !deleteBeforeRetentionDenied
                || !"Ed25519".equals(algorithm) || !validSignature) {
            throw new IllegalArgumentException(
                    "Invalid external observation-archive receipt");
        }
    }

    /** @return exact canonical material protected by the detached signature */
    public Material material() {
        return new Material(schemaVersion, requestFingerprint, trustDomain, archiveSetId,
                authorityId, failureDomain, keyId, objectId, retirementId,
                retirementFingerprint, segmentId, segmentFingerprint,
                retentionPolicyFingerprint, retainUntil, storedAt, issuedAt, expiresAt,
                retentionMode, externallyDurable, writeOnce, deleteBeforeRetentionDenied,
                algorithm);
    }

    /** @return whether the claimed receipt fingerprint covers every signed field */
    public boolean fingerprintVerified(ObjectMapper objectMapper) {
        return receiptFingerprint.equals(ProtocolFingerprint.of(
                Objects.requireNonNull(objectMapper, "objectMapper"), material()));
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
