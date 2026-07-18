package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Independently signed result from one external compare-and-append notary.
 *
 * <p>An authenticated conflict is a safety signal, not a retryable availability failure. The
 * receipt binds the fresh request fingerprint, candidate head, observed external head, authority
 * and failure-domain identity, and a short validity window.</p>
 *
 * @param schemaVersion receipt protocol version
 * @param receiptFingerprint canonical signed material fingerprint
 * @param requestFingerprint exact challenge-bound request identity
 * @param trustDomain expected external trust domain
 * @param anchorSetId expected notary set
 * @param authorityId configured independent notary authority
 * @param failureDomain configured independent failure domain
 * @param keyId configured Ed25519 verification key
 * @param decision accepted/idempotent head or authenticated conflict
 * @param candidateSequence requested candidate sequence
 * @param candidateHeadFingerprint requested candidate head
 * @param observedSequence notary's resulting or conflicting current sequence
 * @param observedHeadFingerprint notary's resulting or conflicting current head, blank at zero
 * @param issuedAt whole-second receipt creation time
 * @param expiresAt exclusive short receipt validity deadline
 * @param algorithm signature algorithm, fixed to Ed25519
 * @param signature base64 detached signature over {@code receiptFingerprint}
 */
public record TestSuiteStabilityExternalSequenceCheckpointReceipt(
        String schemaVersion,
        String receiptFingerprint,
        String requestFingerprint,
        String trustDomain,
        String anchorSetId,
        String authorityId,
        String failureDomain,
        String keyId,
        Decision decision,
        long candidateSequence,
        String candidateHeadFingerprint,
        long observedSequence,
        String observedHeadFingerprint,
        Instant issuedAt,
        Instant expiresAt,
        String algorithm,
        String signature) {

    /** Current signed receipt protocol. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityExternalSequenceCheckpointReceipt.v1";
    /** Maximum signed receipt lifetime. */
    public static final Duration MAXIMUM_LIFETIME = Duration.ofSeconds(60);

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Closed notary outcome. */
    public enum Decision {
        ACCEPTED,
        CONFLICT
    }

    /** Canonical material signed by the configured notary key. */
    public record Material(
            String schemaVersion,
            String requestFingerprint,
            String trustDomain,
            String anchorSetId,
            String authorityId,
            String failureDomain,
            String keyId,
            Decision decision,
            long candidateSequence,
            String candidateHeadFingerprint,
            long observedSequence,
            String observedHeadFingerprint,
            Instant issuedAt,
            Instant expiresAt,
            String algorithm) {
    }

    /** Enforces canonical signed outcome shape without trusting its signature. */
    public TestSuiteStabilityExternalSequenceCheckpointReceipt {
        schemaVersion = normalized(schemaVersion);
        receiptFingerprint = normalized(receiptFingerprint);
        requestFingerprint = normalized(requestFingerprint);
        trustDomain = normalized(trustDomain);
        anchorSetId = normalized(anchorSetId);
        authorityId = normalized(authorityId);
        failureDomain = normalized(failureDomain);
        keyId = normalized(keyId);
        candidateHeadFingerprint = normalized(candidateHeadFingerprint);
        observedHeadFingerprint = normalized(observedHeadFingerprint);
        algorithm = normalized(algorithm);
        signature = normalized(signature);
        boolean observedShape = observedSequence == 0 && observedHeadFingerprint.isEmpty()
                || observedSequence > 0 && FINGERPRINT.matcher(observedHeadFingerprint).matches();
        boolean acceptedShape = decision != Decision.ACCEPTED
                || observedSequence == candidateSequence
                && observedHeadFingerprint.equals(candidateHeadFingerprint);
        boolean conflictShape = decision != Decision.CONFLICT
                || observedSequence != candidateSequence
                || !observedHeadFingerprint.equals(candidateHeadFingerprint);
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
                || !IDENTIFIER.matcher(anchorSetId).matches()
                || !IDENTIFIER.matcher(authorityId).matches()
                || !IDENTIFIER.matcher(failureDomain).matches()
                || !IDENTIFIER.matcher(keyId).matches() || decision == null
                || candidateSequence < 1
                || !FINGERPRINT.matcher(candidateHeadFingerprint).matches()
                || !observedShape || !acceptedShape || !conflictShape
                || issuedAt == null || expiresAt == null || issuedAt.getNano() != 0
                || expiresAt.getNano() != 0 || !expiresAt.isAfter(issuedAt)
                || Duration.between(issuedAt, expiresAt).compareTo(MAXIMUM_LIFETIME) > 0
                || !"Ed25519".equals(algorithm) || !validSignature) {
            throw new IllegalArgumentException("Invalid external checkpoint receipt");
        }
    }

    /** @return exact canonical material protected by the detached signature */
    public Material material() {
        return new Material(schemaVersion, requestFingerprint, trustDomain, anchorSetId,
                authorityId, failureDomain, keyId, decision, candidateSequence,
                candidateHeadFingerprint, observedSequence, observedHeadFingerprint,
                issuedAt, expiresAt, algorithm);
    }

    /** @return true only when the claimed receipt identity covers every signed field */
    public boolean fingerprintVerified(ObjectMapper objectMapper) {
        return receiptFingerprint.equals(ProtocolFingerprint.of(
                Objects.requireNonNull(objectMapper, "objectMapper"), material()));
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
