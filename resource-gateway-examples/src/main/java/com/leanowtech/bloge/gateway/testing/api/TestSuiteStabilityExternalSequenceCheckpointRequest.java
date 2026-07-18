package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Challenge-bound request to an external sequence compare-and-append notary.
 *
 * <p>The fresh 256-bit challenge and short whole-second window prevent an old accepted receipt
 * from authorizing a database backup rollback. The claimed request fingerprint covers every field
 * except itself and is echoed inside each independently signed receipt.</p>
 *
 * @param schemaVersion request protocol version
 * @param requestFingerprint canonical {@link Material} fingerprint
 * @param trustDomain independently configured notary trust domain
 * @param anchorSetId stable external notary-set identity
 * @param head exact private stream candidate
 * @param challenge unpadded base64url 256-bit request entropy
 * @param requestedAt whole-second request creation time
 * @param expiresAt exclusive short request deadline
 */
public record TestSuiteStabilityExternalSequenceCheckpointRequest(
        String schemaVersion,
        String requestFingerprint,
        String trustDomain,
        String anchorSetId,
        TestSuiteStabilityExternalSequenceAnchor.Head head,
        String challenge,
        Instant requestedAt,
        Instant expiresAt) {

    /** Current challenge-bound request protocol. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityExternalSequenceCheckpointRequest.v1";
    /** Maximum lifetime accepted by the wire value. */
    public static final Duration MAXIMUM_LIFETIME = Duration.ofSeconds(60);

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Canonical material covered by {@link #requestFingerprint()}. */
    public record Material(
            String schemaVersion,
            String trustDomain,
            String anchorSetId,
            TestSuiteStabilityExternalSequenceAnchor.Head head,
            String challenge,
            Instant requestedAt,
            Instant expiresAt) {
    }

    /** Normalizes identity and rejects replay-prone time or challenge shapes. */
    public TestSuiteStabilityExternalSequenceCheckpointRequest {
        schemaVersion = normalized(schemaVersion);
        requestFingerprint = normalized(requestFingerprint);
        trustDomain = normalized(trustDomain);
        anchorSetId = normalized(anchorSetId);
        challenge = normalized(challenge);
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !FINGERPRINT.matcher(requestFingerprint).matches()
                || !IDENTIFIER.matcher(trustDomain).matches()
                || !IDENTIFIER.matcher(anchorSetId).matches() || head == null
                || !validChallenge(challenge) || !wholeSecond(requestedAt)
                || !wholeSecond(expiresAt) || !expiresAt.isAfter(requestedAt)
                || Duration.between(requestedAt, expiresAt).compareTo(MAXIMUM_LIFETIME) > 0) {
            throw new IllegalArgumentException("Invalid external checkpoint request");
        }
    }

    /** Creates and fingerprints a complete request. */
    public static TestSuiteStabilityExternalSequenceCheckpointRequest create(
            ObjectMapper objectMapper,
            String trustDomain,
            String anchorSetId,
            TestSuiteStabilityExternalSequenceAnchor.Head head,
            String challenge,
            Instant requestedAt,
            Instant expiresAt) {
        Instant canonicalRequestedAt = Objects.requireNonNull(
                requestedAt, "requestedAt").truncatedTo(ChronoUnit.SECONDS);
        Instant canonicalExpiresAt = Objects.requireNonNull(
                expiresAt, "expiresAt").truncatedTo(ChronoUnit.SECONDS);
        Material material = new Material(SCHEMA_VERSION, normalized(trustDomain),
                normalized(anchorSetId), Objects.requireNonNull(head, "head"),
                normalized(challenge), canonicalRequestedAt, canonicalExpiresAt);
        String fingerprint = ProtocolFingerprint.of(
                Objects.requireNonNull(objectMapper, "objectMapper"), material);
        return new TestSuiteStabilityExternalSequenceCheckpointRequest(
                SCHEMA_VERSION, fingerprint, material.trustDomain(), material.anchorSetId(),
                material.head(), material.challenge(), material.requestedAt(),
                material.expiresAt());
    }

    /** @return exact canonical material represented by this request */
    public Material material() {
        return new Material(schemaVersion, trustDomain, anchorSetId, head,
                challenge, requestedAt, expiresAt);
    }

    /** @return true only when the claimed identity covers every request field */
    public boolean fingerprintVerified(ObjectMapper objectMapper) {
        return requestFingerprint.equals(ProtocolFingerprint.of(
                Objects.requireNonNull(objectMapper, "objectMapper"), material()));
    }

    private static boolean validChallenge(String value) {
        try {
            return value.length() == 43 && Base64.getUrlDecoder().decode(value).length == 32;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean wholeSecond(Instant value) {
        return value != null && value.getNano() == 0;
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
