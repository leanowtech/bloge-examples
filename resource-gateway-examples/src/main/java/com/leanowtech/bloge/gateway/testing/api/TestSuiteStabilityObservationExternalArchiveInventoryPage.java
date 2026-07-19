package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Signed page from one pinned, immutable external archive inventory snapshot.
 *
 * <p>Every page repeats the snapshot id, time, total object count, and complete ordered root. The
 * request pins its page sequence and exclusive cursor. A reconciler may publish missing-object
 * findings only after all page item fingerprints reproduce the signed count and root.</p>
 *
 * @param schemaVersion inventory-page protocol version
 * @param pageFingerprint canonical signed page-material fingerprint
 * @param request exact challenge-bound request answered by this page
 * @param authorityId configured external archive authority
 * @param failureDomain configured independent failure domain
 * @param keyId configured Ed25519 verification key
 * @param snapshotId deterministic complete-snapshot identity
 * @param snapshotAt whole-second immutable snapshot boundary
 * @param snapshotObjectCount complete object count in the snapshot
 * @param snapshotRoot complete order-sensitive item-fingerprint root
 * @param items unique items in strict object-id order after the request cursor
 * @param nextAfterObjectId exclusive cursor for the next page, empty when complete
 * @param complete whether this is the terminal page
 * @param issuedAt whole-second response issue time
 * @param expiresAt exclusive short response-admission deadline
 * @param algorithm signature algorithm, fixed to Ed25519
 * @param signature base64 detached signature over {@code pageFingerprint}
 */
public record TestSuiteStabilityObservationExternalArchiveInventoryPage(
        String schemaVersion,
        String pageFingerprint,
        TestSuiteStabilityObservationExternalArchiveInventoryRequest request,
        String authorityId,
        String failureDomain,
        String keyId,
        String snapshotId,
        Instant snapshotAt,
        long snapshotObjectCount,
        String snapshotRoot,
        List<TestSuiteStabilityObservationExternalArchiveInventoryItem> items,
        String nextAfterObjectId,
        boolean complete,
        Instant issuedAt,
        Instant expiresAt,
        String algorithm,
        String signature) {
    /** Current signed external archive inventory-page generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityObservationExternalArchiveInventoryPage.v1";
    /** Largest declared snapshot accepted by this protocol generation. */
    public static final long MAXIMUM_SNAPSHOT_OBJECTS = 1_000_000_000L;

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern SNAPSHOT_ID = Pattern.compile(
            "stability-observation-external-inventory-[a-f0-9]{64}");
    private static final Pattern OBJECT_ID =
            Pattern.compile("stability-observation-worm-[a-f0-9]{64}");

    /** Canonical page material signed by one inventory authority. */
    public record Material(
            String schemaVersion,
            String requestFingerprint,
            String authorityId,
            String failureDomain,
            String keyId,
            String snapshotId,
            Instant snapshotAt,
            long snapshotObjectCount,
            String snapshotRoot,
            List<TestSuiteStabilityObservationExternalArchiveInventoryItem> items,
            String nextAfterObjectId,
            boolean complete,
            Instant issuedAt,
            Instant expiresAt,
            String algorithm) {
        /** Freezes the item list before canonical serialization. */
        public Material {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /** Enforces strict ordered paging and short-lived signed-response shape. */
    public TestSuiteStabilityObservationExternalArchiveInventoryPage {
        schemaVersion = normalized(schemaVersion);
        pageFingerprint = normalized(pageFingerprint);
        authorityId = normalized(authorityId);
        failureDomain = normalized(failureDomain);
        keyId = normalized(keyId);
        snapshotId = normalized(snapshotId);
        snapshotRoot = normalized(snapshotRoot);
        items = items == null ? List.of() : List.copyOf(items);
        nextAfterObjectId = normalized(nextAfterObjectId);
        algorithm = normalized(algorithm);
        signature = normalized(signature);
        boolean validSignature;
        try {
            validSignature = Base64.getDecoder().decode(signature).length == 64;
        } catch (IllegalArgumentException malformed) {
            validSignature = false;
        }
        boolean orderValid = request != null && items.size() <= request.maximumItems();
        String previousObjectId = request == null ? "" : request.afterObjectId();
        for (TestSuiteStabilityObservationExternalArchiveInventoryItem item : items) {
            if (item == null || previousObjectId.compareTo(item.objectId()) >= 0
                    || snapshotAt == null || item.storedAt().isAfter(snapshotAt)) {
                orderValid = false;
                break;
            }
            previousObjectId = item.objectId();
        }
        boolean terminalShape = complete && nextAfterObjectId.isEmpty();
        boolean continuationShape = !complete && !items.isEmpty()
                && OBJECT_ID.matcher(nextAfterObjectId).matches()
                && nextAfterObjectId.equals(items.getLast().objectId());
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !FINGERPRINT.matcher(pageFingerprint).matches()
                || request == null || !request.authorityId().equals(authorityId)
                || !IDENTIFIER.matcher(authorityId).matches()
                || !IDENTIFIER.matcher(failureDomain).matches()
                || !IDENTIFIER.matcher(keyId).matches()
                || !SNAPSHOT_ID.matcher(snapshotId).matches()
                || snapshotAt == null || snapshotAt.getNano() != 0
                || snapshotObjectCount < 0
                || snapshotObjectCount > MAXIMUM_SNAPSHOT_OBJECTS
                || snapshotObjectCount < items.size()
                || !FINGERPRINT.matcher(snapshotRoot).matches()
                || !orderValid || (!terminalShape && !continuationShape)
                || issuedAt == null || expiresAt == null
                || issuedAt.getNano() != 0 || expiresAt.getNano() != 0
                || issuedAt.isBefore(snapshotAt)
                || issuedAt.isBefore(request.requestedAt())
                || !expiresAt.isAfter(issuedAt)
                || expiresAt.isAfter(request.expiresAt())
                || Duration.between(issuedAt, expiresAt)
                .compareTo(TestSuiteStabilityObservationExternalArchiveInventoryRequest
                        .MAXIMUM_LIFETIME) > 0
                || !"Ed25519".equals(algorithm) || !validSignature) {
            throw new IllegalArgumentException(
                    "Invalid external observation-archive inventory page");
        }
    }

    /** @return exact canonical material protected by the detached signature */
    public Material material() {
        return new Material(schemaVersion, request.requestFingerprint(), authorityId,
                failureDomain, keyId, snapshotId, snapshotAt, snapshotObjectCount,
                snapshotRoot, items, nextAfterObjectId, complete, issuedAt, expiresAt,
                algorithm);
    }

    /** @return whether request, items, and claimed page fingerprint are canonical */
    public boolean fingerprintVerified(ObjectMapper objectMapper) {
        if (!request.fingerprintVerified(objectMapper)
                || items.stream().anyMatch(item -> !item.fingerprintVerified(objectMapper))) {
            return false;
        }
        return pageFingerprint.equals(ProtocolFingerprint.of(
                Objects.requireNonNull(objectMapper, "objectMapper"), material()));
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
