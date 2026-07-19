package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Payload-free immutable-object metadata in one signed external inventory snapshot.
 *
 * <p>The item contains only identities, fingerprints, and lifecycle times required to compare a
 * remote WORM object with committed local receipt sets. It contains neither retired observations
 * nor credentials.</p>
 *
 * @param schemaVersion inventory-item protocol version
 * @param itemFingerprint canonical item-material fingerprint
 * @param objectId deterministic immutable object identity
 * @param objectCommitment retention-bearing object commitment
 * @param retirementId exact signed retirement identity
 * @param retirementFingerprint exact signed retirement fingerprint
 * @param segmentId exact compact archive-segment identity
 * @param segmentFingerprint exact compact archive-segment fingerprint
 * @param retentionPolicyFingerprint immutable retirement-policy identity
 * @param retainUntil authority-enforced immutable retention deadline
 * @param storedAt external object commit time
 */
public record TestSuiteStabilityObservationExternalArchiveInventoryItem(
        String schemaVersion,
        String itemFingerprint,
        String objectId,
        String objectCommitment,
        String retirementId,
        String retirementFingerprint,
        String segmentId,
        String segmentFingerprint,
        String retentionPolicyFingerprint,
        Instant retainUntil,
        Instant storedAt) {
    /** Current external archive inventory-item generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityObservationExternalArchiveInventoryItem.v1";

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern OBJECT_ID =
            Pattern.compile("stability-observation-worm-[a-f0-9]{64}");
    private static final Pattern RETIREMENT_ID =
            Pattern.compile("stability-observation-retirement-[a-f0-9]{64}");
    private static final Pattern SEGMENT_ID =
            Pattern.compile("stability-observation-archive-[a-f0-9]{64}");

    /** Canonical inventory-item material excluding only its self fingerprint. */
    public record Material(
            String schemaVersion,
            String objectId,
            String objectCommitment,
            String retirementId,
            String retirementFingerprint,
            String segmentId,
            String segmentFingerprint,
            String retentionPolicyFingerprint,
            Instant retainUntil,
            Instant storedAt) {
    }

    /** Rejects malformed identity, fingerprint, and retention metadata. */
    public TestSuiteStabilityObservationExternalArchiveInventoryItem {
        schemaVersion = normalized(schemaVersion);
        itemFingerprint = normalized(itemFingerprint);
        objectId = normalized(objectId);
        objectCommitment = normalized(objectCommitment);
        retirementId = normalized(retirementId);
        retirementFingerprint = normalized(retirementFingerprint);
        segmentId = normalized(segmentId);
        segmentFingerprint = normalized(segmentFingerprint);
        retentionPolicyFingerprint = normalized(retentionPolicyFingerprint);
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !FINGERPRINT.matcher(itemFingerprint).matches()
                || !OBJECT_ID.matcher(objectId).matches()
                || !FINGERPRINT.matcher(objectCommitment).matches()
                || !RETIREMENT_ID.matcher(retirementId).matches()
                || !FINGERPRINT.matcher(retirementFingerprint).matches()
                || !SEGMENT_ID.matcher(segmentId).matches()
                || !FINGERPRINT.matcher(segmentFingerprint).matches()
                || !FINGERPRINT.matcher(retentionPolicyFingerprint).matches()
                || retainUntil == null || storedAt == null
                || !retainUntil.isAfter(storedAt)) {
            throw new IllegalArgumentException(
                    "Invalid external observation-archive inventory item");
        }
    }

    /** @return exact canonical material protected by {@link #itemFingerprint()} */
    public Material material() {
        return new Material(schemaVersion, objectId, objectCommitment, retirementId,
                retirementFingerprint, segmentId, segmentFingerprint,
                retentionPolicyFingerprint, retainUntil, storedAt);
    }

    /** @return whether the claimed item fingerprint covers every item field */
    public boolean fingerprintVerified(ObjectMapper objectMapper) {
        return itemFingerprint.equals(ProtocolFingerprint.of(
                Objects.requireNonNull(objectMapper, "objectMapper"), material()));
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
