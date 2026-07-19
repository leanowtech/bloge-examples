package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Challenge-bound request for one immutable page of an external archive snapshot.
 *
 * <p>The first page has an empty snapshot and object cursor. A continuation pins the snapshot id,
 * exact last object id, and monotonically increasing page sequence returned by the preceding page.
 * The request is read-only and deliberately has no deletion or retention-mutation field.</p>
 *
 * @param schemaVersion request protocol version
 * @param requestFingerprint canonical request-material fingerprint
 * @param trustDomain independently configured archive trust domain
 * @param archiveSetId stable external archive-set identity
 * @param authorityId exact authority being inventoried
 * @param snapshotId empty for page zero, otherwise the pinned remote snapshot
 * @param afterObjectId empty for page zero, otherwise the exclusive object cursor
 * @param pageSequence zero-based page sequence inside the snapshot
 * @param maximumItems maximum items requested for this page
 * @param challenge unpadded base64url 256-bit request entropy
 * @param requestedAt whole-second request time
 * @param expiresAt exclusive short response-admission deadline
 */
public record TestSuiteStabilityObservationExternalArchiveInventoryRequest(
        String schemaVersion,
        String requestFingerprint,
        String trustDomain,
        String archiveSetId,
        String authorityId,
        String snapshotId,
        String afterObjectId,
        long pageSequence,
        int maximumItems,
        String challenge,
        Instant requestedAt,
        Instant expiresAt) {
    /** Current external archive inventory-request generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityObservationExternalArchiveInventoryRequest.v1";
    /** Largest accepted challenge and response-admission window. */
    public static final Duration MAXIMUM_LIFETIME = Duration.ofSeconds(60);
    /** Largest page requested from one authority. */
    public static final int MAXIMUM_ITEMS = 500;

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern SNAPSHOT_ID = Pattern.compile(
            "stability-observation-external-inventory-[a-f0-9]{64}");
    private static final Pattern OBJECT_ID =
            Pattern.compile("stability-observation-worm-[a-f0-9]{64}");
    private static final Pattern CHALLENGE = Pattern.compile("[A-Za-z0-9_-]{43}");

    /** Canonical request material excluding only its self fingerprint. */
    public record Material(
            String schemaVersion,
            String trustDomain,
            String archiveSetId,
            String authorityId,
            String snapshotId,
            String afterObjectId,
            long pageSequence,
            int maximumItems,
            String challenge,
            Instant requestedAt,
            Instant expiresAt) {
    }

    /** Enforces a bounded first-page or exact continuation cursor. */
    public TestSuiteStabilityObservationExternalArchiveInventoryRequest {
        schemaVersion = normalized(schemaVersion);
        requestFingerprint = normalized(requestFingerprint);
        trustDomain = normalized(trustDomain);
        archiveSetId = normalized(archiveSetId);
        authorityId = normalized(authorityId);
        snapshotId = normalized(snapshotId);
        afterObjectId = normalized(afterObjectId);
        challenge = normalized(challenge);
        boolean firstPage = pageSequence == 0 && snapshotId.isEmpty()
                && afterObjectId.isEmpty();
        boolean continuation = pageSequence > 0
                && SNAPSHOT_ID.matcher(snapshotId).matches()
                && OBJECT_ID.matcher(afterObjectId).matches();
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !FINGERPRINT.matcher(requestFingerprint).matches()
                || !IDENTIFIER.matcher(trustDomain).matches()
                || !IDENTIFIER.matcher(archiveSetId).matches()
                || !IDENTIFIER.matcher(authorityId).matches()
                || (!firstPage && !continuation)
                || pageSequence < 0
                || maximumItems < 1 || maximumItems > MAXIMUM_ITEMS
                || !CHALLENGE.matcher(challenge).matches()
                || requestedAt == null || expiresAt == null
                || requestedAt.getNano() != 0 || expiresAt.getNano() != 0
                || !expiresAt.isAfter(requestedAt)
                || Duration.between(requestedAt, expiresAt)
                .compareTo(MAXIMUM_LIFETIME) > 0) {
            throw new IllegalArgumentException(
                    "Invalid external observation-archive inventory request");
        }
    }

    /**
     * Creates and fingerprints one first-page or continuation request.
     *
     * @param objectMapper canonical protocol mapper
     * @param trustDomain independently configured archive trust domain
     * @param archiveSetId stable archive-set identity
     * @param authorityId exact authority to query
     * @param snapshotId empty for the first page or the pinned snapshot id
     * @param afterObjectId empty for the first page or the exclusive object cursor
     * @param pageSequence zero-based page sequence
     * @param maximumItems bounded requested page size
     * @param challenge fresh 256-bit request entropy
     * @param requestedAt whole-second request time
     * @param expiresAt exclusive response-admission deadline
     * @return canonical challenge-bound inventory request
     */
    public static TestSuiteStabilityObservationExternalArchiveInventoryRequest create(
            ObjectMapper objectMapper,
            String trustDomain,
            String archiveSetId,
            String authorityId,
            String snapshotId,
            String afterObjectId,
            long pageSequence,
            int maximumItems,
            String challenge,
            Instant requestedAt,
            Instant expiresAt) {
        Material material = new Material(SCHEMA_VERSION, trustDomain, archiveSetId,
                authorityId, snapshotId, afterObjectId, pageSequence, maximumItems,
                challenge, requestedAt, expiresAt);
        return new TestSuiteStabilityObservationExternalArchiveInventoryRequest(
                material.schemaVersion(),
                ProtocolFingerprint.of(Objects.requireNonNull(objectMapper, "objectMapper"),
                        material),
                material.trustDomain(), material.archiveSetId(), material.authorityId(),
                material.snapshotId(), material.afterObjectId(), material.pageSequence(),
                material.maximumItems(), material.challenge(), material.requestedAt(),
                material.expiresAt());
    }

    /** @return exact canonical material protected by {@link #requestFingerprint()} */
    public Material material() {
        return new Material(schemaVersion, trustDomain, archiveSetId, authorityId, snapshotId,
                afterObjectId, pageSequence, maximumItems, challenge, requestedAt, expiresAt);
    }

    /** @return whether the claimed request fingerprint covers every request field */
    public boolean fingerprintVerified(ObjectMapper objectMapper) {
        return requestFingerprint.equals(ProtocolFingerprint.of(
                Objects.requireNonNull(objectMapper, "objectMapper"), material()));
    }

    /** @return exactly 32 decoded challenge bytes */
    public byte[] challengeBytes() {
        byte[] decoded = Base64.getUrlDecoder().decode(challenge);
        if (decoded.length != 32) {
            throw new IllegalStateException("External inventory challenge is not 256-bit");
        }
        return decoded;
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
