package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveInventoryItem;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Objects;

/**
 * Canonical whole-record fingerprints for staged external inventory pages and items.
 *
 * <p>The signed inventory protocol protects business material, but staging adds database control
 * columns such as cycle identity, page sequence, commit time, and the exact persisted page JSON.
 * Retention and reconciliation must protect those columns too, otherwise a corrupted staging row
 * could be consumed or deleted while its protocol-level fingerprint still looks valid.</p>
 */
final class ExternalArchiveInventoryStagingIntegrity {
    private static final String PAGE_SCHEMA =
            "bloge.testSuiteStabilityObservationExternalInventoryPageRow.v1";
    private static final String ITEM_SCHEMA =
            "bloge.testSuiteStabilityObservationExternalInventoryItemRow.v1";

    private ExternalArchiveInventoryStagingIntegrity() {
    }

    /** Returns the canonical fingerprint over every persisted inventory-page column. */
    static String pageFingerprint(
            ObjectMapper objectMapper,
            String cycleId,
            long pageSequence,
            String authorityId,
            String trustDomain,
            String archiveSetId,
            String failureDomain,
            String requestFingerprint,
            String pageFingerprint,
            String snapshotId,
            Instant snapshotAt,
            long snapshotObjectCount,
            String snapshotRoot,
            String afterObjectId,
            String nextAfterObjectId,
            int itemCount,
            boolean complete,
            Instant issuedAt,
            Instant expiresAt,
            Instant committedAt,
            String pageJson) {
        return ProtocolFingerprint.of(Objects.requireNonNull(objectMapper, "objectMapper"),
                new PageRowMaterial(PAGE_SCHEMA, cycleId, pageSequence, authorityId,
                        trustDomain, archiveSetId, failureDomain, requestFingerprint,
                        pageFingerprint, snapshotId, snapshotAt, snapshotObjectCount,
                        snapshotRoot, afterObjectId, nextAfterObjectId, itemCount, complete,
                        issuedAt, expiresAt, committedAt,
                        ProtocolFingerprint.ofText(Objects.requireNonNull(pageJson, "pageJson"))));
    }

    /** Returns the canonical fingerprint over every persisted inventory-item column. */
    static String itemFingerprint(
            ObjectMapper objectMapper,
            String cycleId,
            long pageSequence,
            TestSuiteStabilityObservationExternalArchiveInventoryItem item,
            Instant committedAt) {
        return ProtocolFingerprint.of(Objects.requireNonNull(objectMapper, "objectMapper"),
                new ItemRowMaterial(ITEM_SCHEMA, cycleId, pageSequence,
                        Objects.requireNonNull(item, "item"), committedAt));
    }

    private record PageRowMaterial(
            String schemaVersion,
            String cycleId,
            long pageSequence,
            String authorityId,
            String trustDomain,
            String archiveSetId,
            String failureDomain,
            String requestFingerprint,
            String pageFingerprint,
            String snapshotId,
            Instant snapshotAt,
            long snapshotObjectCount,
            String snapshotRoot,
            String afterObjectId,
            String nextAfterObjectId,
            int itemCount,
            boolean complete,
            Instant issuedAt,
            Instant expiresAt,
            Instant committedAt,
            String pageJsonFingerprint) {
    }

    private record ItemRowMaterial(
            String schemaVersion,
            String cycleId,
            long pageSequence,
            TestSuiteStabilityObservationExternalArchiveInventoryItem item,
            Instant committedAt) {
    }
}
