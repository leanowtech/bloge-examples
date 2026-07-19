package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveInventoryItem;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveInventoryPage;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveReceipt;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveReceiptSet;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Canonical identities and ordered hash-chain roots for external archive inventory snapshots. */
public final class TestSuiteStabilityObservationExternalArchiveInventoryIntegrity {
    /** Domain-separated root before the first ordered inventory item. */
    public static final String EMPTY_ROOT = ProtocolFingerprint.ofText(
            "bloge.testSuiteStabilityObservationExternalArchiveInventoryRoot.v1:empty");

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

    private TestSuiteStabilityObservationExternalArchiveInventoryIntegrity() {
    }

    /** Ordered chain link that prevents commutative or concatenation ambiguity. */
    public record RootLink(
            String schemaVersion,
            String previousRoot,
            String itemFingerprint) {
        /** Current ordered inventory-root link generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityObservationExternalArchiveInventoryRootLink.v1";

        /** Rejects roots or items outside the closed fingerprint vocabulary. */
        public RootLink {
            schemaVersion = normalized(schemaVersion);
            previousRoot = normalized(previousRoot);
            itemFingerprint = normalized(itemFingerprint);
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !FINGERPRINT.matcher(previousRoot).matches()
                    || !FINGERPRINT.matcher(itemFingerprint).matches()) {
                throw new IllegalArgumentException("Invalid external inventory root link");
            }
        }
    }

    /** Canonical material from which a provider snapshot id is derived. */
    public record SnapshotIdentity(
            String schemaVersion,
            String trustDomain,
            String archiveSetId,
            String authorityId,
            String failureDomain,
            Instant snapshotAt,
            long objectCount,
            String root) {
        /** Current deterministic inventory snapshot-identity generation. */
        public static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityObservationExternalArchiveInventorySnapshot.v1";

        /** Enforces complete topology and bounded snapshot material before identity derivation. */
        public SnapshotIdentity {
            schemaVersion = normalized(schemaVersion);
            trustDomain = normalized(trustDomain);
            archiveSetId = normalized(archiveSetId);
            authorityId = normalized(authorityId);
            failureDomain = normalized(failureDomain);
            root = normalized(root);
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !IDENTIFIER.matcher(trustDomain).matches()
                    || !IDENTIFIER.matcher(archiveSetId).matches()
                    || !IDENTIFIER.matcher(authorityId).matches()
                    || !IDENTIFIER.matcher(failureDomain).matches()
                    || snapshotAt == null || snapshotAt.getNano() != 0
                    || objectCount < 0
                    || objectCount
                    > TestSuiteStabilityObservationExternalArchiveInventoryPage
                    .MAXIMUM_SNAPSHOT_OBJECTS
                    || !FINGERPRINT.matcher(root).matches()) {
                throw new IllegalArgumentException("Invalid external inventory snapshot identity");
            }
        }
    }

    /**
     * Appends one verified item fingerprint to an ordered snapshot root.
     *
     * @param objectMapper canonical protocol mapper
     * @param previousRoot previous chain root, or {@link #EMPTY_ROOT}
     * @param item next ordered inventory item
     * @return successor root
     */
    public static String append(
            ObjectMapper objectMapper,
            String previousRoot,
            TestSuiteStabilityObservationExternalArchiveInventoryItem item) {
        Objects.requireNonNull(item, "item");
        if (!item.fingerprintVerified(objectMapper)) {
            throw new IllegalArgumentException("Canonical external inventory item is required");
        }
        return ProtocolFingerprint.of(Objects.requireNonNull(objectMapper, "objectMapper"),
                new RootLink(RootLink.SCHEMA_VERSION, previousRoot, item.itemFingerprint()));
    }

    /**
     * Computes the order-sensitive root for a complete sorted inventory.
     *
     * @param objectMapper canonical protocol mapper
     * @param items complete items in strict object-id order
     * @return final chain root, or {@link #EMPTY_ROOT} for an empty inventory
     */
    public static String root(
            ObjectMapper objectMapper,
            List<TestSuiteStabilityObservationExternalArchiveInventoryItem> items) {
        String root = EMPTY_ROOT;
        String previousObjectId = "";
        for (TestSuiteStabilityObservationExternalArchiveInventoryItem item
                : Objects.requireNonNull(items, "items")) {
            if (item == null || previousObjectId.compareTo(item.objectId()) >= 0) {
                throw new IllegalArgumentException(
                        "External inventory items must be unique and sorted");
            }
            root = append(objectMapper, root, item);
            previousObjectId = item.objectId();
        }
        return root;
    }

    /**
     * Projects one committed external receipt into the exact payload-free inventory item expected
     * from that authority.
     *
     * <p>The object commitment is derived rather than trusted from a second stored value. This
     * gives the durable reconciler one canonical local comparison record while retaining no retired
     * observation payload or credential.</p>
     *
     * @param objectMapper canonical protocol mapper
     * @param receiptSet complete committed receipt set
     * @param receipt exact member receipt to project
     * @return canonical expected inventory item
     */
    public static TestSuiteStabilityObservationExternalArchiveInventoryItem expectedItem(
            ObjectMapper objectMapper,
            TestSuiteStabilityObservationExternalArchiveReceiptSet receiptSet,
            TestSuiteStabilityObservationExternalArchiveReceipt receipt) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(receiptSet, "receiptSet");
        Objects.requireNonNull(receipt, "receipt");
        if (!TestSuiteStabilityObservationExternalArchiveIntegrity.valid(
                objectMapper, receiptSet) || !receiptSet.receipts().contains(receipt)) {
            throw new IllegalArgumentException(
                    "Canonical member of an external archive receipt set is required");
        }
        String commitment = TestSuiteStabilityObservationExternalArchiveIntegrity.objectCommitment(
                objectMapper, receiptSet.request().retirement(), receipt.retainUntil());
        TestSuiteStabilityObservationExternalArchiveInventoryItem.Material material =
                new TestSuiteStabilityObservationExternalArchiveInventoryItem.Material(
                        TestSuiteStabilityObservationExternalArchiveInventoryItem.SCHEMA_VERSION,
                        receipt.objectId(), commitment, receipt.retirementId(),
                        receipt.retirementFingerprint(), receipt.segmentId(),
                        receipt.segmentFingerprint(), receipt.retentionPolicyFingerprint(),
                        receipt.retainUntil(), receipt.storedAt());
        return new TestSuiteStabilityObservationExternalArchiveInventoryItem(
                material.schemaVersion(), ProtocolFingerprint.of(objectMapper, material),
                material.objectId(), material.objectCommitment(), material.retirementId(),
                material.retirementFingerprint(), material.segmentId(),
                material.segmentFingerprint(), material.retentionPolicyFingerprint(),
                material.retainUntil(), material.storedAt());
    }

    /**
     * Derives a snapshot id from exact topology, time, count, and ordered root.
     *
     * @param objectMapper canonical protocol mapper
     * @param trustDomain archive trust domain
     * @param archiveSetId archive-set identity
     * @param authorityId inventory authority
     * @param failureDomain configured failure domain
     * @param snapshotAt whole-second snapshot boundary
     * @param objectCount complete object count
     * @param root complete ordered inventory root
     * @return deterministic snapshot id
     */
    public static String snapshotId(
            ObjectMapper objectMapper,
            String trustDomain,
            String archiveSetId,
            String authorityId,
            String failureDomain,
            Instant snapshotAt,
            long objectCount,
            String root) {
        SnapshotIdentity identity = new SnapshotIdentity(
                SnapshotIdentity.SCHEMA_VERSION, trustDomain, archiveSetId, authorityId,
                failureDomain, snapshotAt, objectCount, root);
        String fingerprint = ProtocolFingerprint.of(
                Objects.requireNonNull(objectMapper, "objectMapper"), identity);
        return "stability-observation-external-inventory-"
                + fingerprint.substring("sha256:".length());
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
