package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Challenge-bound request to store one complete signed floor retirement in external WORM storage.
 *
 * <p>The request embeds the payload-free signed retirement rather than a caller-projected object
 * reference. Its canonical fingerprint therefore commits the exact compact archive, retirement
 * policy identity, immutable retention deadline, trust domain, archive set, and replay window.</p>
 *
 * @param schemaVersion request protocol version
 * @param requestFingerprint canonical material fingerprint
 * @param trustDomain independently configured archive trust domain
 * @param archiveSetId stable external archive-set identity
 * @param retirement complete signed retirement to store
 * @param retainUntil minimum immutable retention deadline
 * @param challenge unpadded base64url 256-bit request entropy
 * @param requestedAt whole-second request creation time
 * @param expiresAt exclusive short receipt-admission deadline
 */
public record TestSuiteStabilityObservationExternalArchiveRequest(
        String schemaVersion,
        String requestFingerprint,
        String trustDomain,
        String archiveSetId,
        TestSuiteStabilityObservationFloorRetirement retirement,
        Instant retainUntil,
        String challenge,
        Instant requestedAt,
        Instant expiresAt) {
    /** Current external archive request generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityObservationExternalArchiveRequest.v1";
    /** Largest accepted challenge/receipt admission window. */
    public static final Duration MAXIMUM_LIFETIME = Duration.ofSeconds(60);

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern CHALLENGE = Pattern.compile("[A-Za-z0-9_-]{43}");

    /** Canonical request material excluding only its self fingerprint. */
    public record Material(
            String schemaVersion,
            String trustDomain,
            String archiveSetId,
            TestSuiteStabilityObservationFloorRetirement retirement,
            Instant retainUntil,
            String challenge,
            Instant requestedAt,
            Instant expiresAt) {
    }

    /** Enforces an exact short-lived request without trusting its claimed fingerprint. */
    public TestSuiteStabilityObservationExternalArchiveRequest {
        schemaVersion = normalized(schemaVersion);
        requestFingerprint = normalized(requestFingerprint);
        trustDomain = normalized(trustDomain);
        archiveSetId = normalized(archiveSetId);
        challenge = normalized(challenge);
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !FINGERPRINT.matcher(requestFingerprint).matches()
                || !IDENTIFIER.matcher(trustDomain).matches()
                || !IDENTIFIER.matcher(archiveSetId).matches()
                || retirement == null || retainUntil == null
                || !retainUntil.isAfter(retirement.evidence().retiredAt())
                || !CHALLENGE.matcher(challenge).matches()
                || requestedAt == null || expiresAt == null
                || requestedAt.getNano() != 0 || expiresAt.getNano() != 0
                || !expiresAt.isAfter(requestedAt)
                || Duration.between(requestedAt, expiresAt)
                .compareTo(MAXIMUM_LIFETIME) > 0) {
            throw new IllegalArgumentException(
                    "Invalid external observation-archive request");
        }
    }

    /**
     * Creates one request and derives its canonical fingerprint.
     *
     * @param objectMapper canonical protocol mapper
     * @param trustDomain independently configured archive trust domain
     * @param archiveSetId stable archive-set identity
     * @param retirement complete signed retirement
     * @param retainUntil minimum immutable retention deadline
     * @param challenge fresh 256-bit request entropy
     * @param requestedAt whole-second request time
     * @param expiresAt exclusive short admission deadline
     * @return canonical challenge-bound request
     */
    public static TestSuiteStabilityObservationExternalArchiveRequest create(
            ObjectMapper objectMapper,
            String trustDomain,
            String archiveSetId,
            TestSuiteStabilityObservationFloorRetirement retirement,
            Instant retainUntil,
            String challenge,
            Instant requestedAt,
            Instant expiresAt) {
        Material material = new Material(SCHEMA_VERSION, trustDomain, archiveSetId,
                retirement, retainUntil, challenge, requestedAt, expiresAt);
        return new TestSuiteStabilityObservationExternalArchiveRequest(
                material.schemaVersion(),
                ProtocolFingerprint.of(Objects.requireNonNull(objectMapper, "objectMapper"),
                        material),
                material.trustDomain(), material.archiveSetId(), material.retirement(),
                material.retainUntil(), material.challenge(), material.requestedAt(),
                material.expiresAt());
    }

    /** @return exact canonical material protected by {@link #requestFingerprint()} */
    public Material material() {
        return new Material(schemaVersion, trustDomain, archiveSetId, retirement,
                retainUntil, challenge, requestedAt, expiresAt);
    }

    /** @return whether the claimed request fingerprint covers every request field */
    public boolean fingerprintVerified(ObjectMapper objectMapper) {
        return requestFingerprint.equals(ProtocolFingerprint.of(
                Objects.requireNonNull(objectMapper, "objectMapper"), material()));
    }

    /**
     * Validates and returns decoded challenge bytes for protocol test authorities.
     *
     * @return exactly 32 challenge bytes
     */
    public byte[] challengeBytes() {
        byte[] decoded = Base64.getUrlDecoder().decode(challenge);
        if (decoded.length != 32) {
            throw new IllegalStateException("External archive challenge is not 256-bit");
        }
        return decoded;
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
