package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveReceipt;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveReceiptSet;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationFloorRetirement;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Canonical identities and structural closure for external observation-archive admission. */
public final class TestSuiteStabilityObservationExternalArchiveIntegrity {
    private static final String OBJECT_ID_PREFIX = "stability-observation-worm-";
    private static final String RECEIPT_SET_ID_PREFIX =
            "stability-observation-external-archive-receipts-";

    private TestSuiteStabilityObservationExternalArchiveIntegrity() {
    }

    /**
     * Derives the immutable external object id from the exact complete retirement.
     *
     * @param objectMapper canonical protocol mapper
     * @param retirement complete signed retirement
     * @return deterministic external WORM object identity
     */
    public static String objectId(
            ObjectMapper objectMapper,
            TestSuiteStabilityObservationFloorRetirement retirement) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(retirement, "retirement");
        String fingerprint = ProtocolFingerprint.of(objectMapper, new ObjectIdentity(
                retirement.evidence().retirementId(), retirement.retirementFingerprint(),
                retirement.evidence().archiveSegment().segmentId(),
                retirement.evidence().archiveSegment().segmentFingerprint(),
                retirement.evidence().retentionPolicyFingerprint()));
        return OBJECT_ID_PREFIX + fingerprint.substring("sha256:".length());
    }

    /**
     * Derives the receipt-set id from the request and ordered external receipt identities.
     *
     * @param objectMapper canonical protocol mapper
     * @param request exact challenge-bound archive request
     * @param requiredCopies committed copy threshold
     * @param receipts authority-id-sorted accepted receipts
     * @return deterministic receipt-set identity
     */
    public static String receiptSetId(
            ObjectMapper objectMapper,
            TestSuiteStabilityObservationExternalArchiveRequest request,
            int requiredCopies,
            List<TestSuiteStabilityObservationExternalArchiveReceipt> receipts) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(request, "request");
        List<ReceiptRef> refs = refs(receipts);
        String fingerprint = ProtocolFingerprint.of(objectMapper, new ReceiptSetIdentity(
                TestSuiteStabilityObservationExternalArchiveReceiptSet.SCHEMA_VERSION,
                request.requestFingerprint(), requiredCopies, refs));
        return RECEIPT_SET_ID_PREFIX + fingerprint.substring("sha256:".length());
    }

    /**
     * Recomputes the complete receipt-set fingerprint excluding its self field.
     *
     * @param objectMapper canonical protocol mapper
     * @param receiptSet complete external archive receipt set
     * @return lowercase SHA-256 protocol fingerprint
     */
    public static String receiptSetFingerprint(
            ObjectMapper objectMapper,
            TestSuiteStabilityObservationExternalArchiveReceiptSet receiptSet) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(receiptSet, "receiptSet");
        return ProtocolFingerprint.of(objectMapper, new ReceiptSetMaterial(
                receiptSet.schemaVersion(), receiptSet.receiptSetId(), receiptSet.request(),
                receiptSet.requiredCopies(), receiptSet.receipts(), receiptSet.confirmedAt()));
    }

    /**
     * Validates every canonical fingerprint and exact retirement/archive/policy binding.
     *
     * <p>This method does not establish external trust. The configured archive authority must
     * independently verify every detached receipt signature and authority policy before commit.</p>
     *
     * @param objectMapper canonical protocol mapper
     * @param receiptSet candidate receipt set
     * @return whether all non-cryptographic canonical closure checks pass
     */
    public static boolean valid(
            ObjectMapper objectMapper,
            TestSuiteStabilityObservationExternalArchiveReceiptSet receiptSet) {
        try {
            if (receiptSet == null
                    || !receiptSet.request().fingerprintVerified(objectMapper)
                    || !TestSuiteStabilityObservationFloorRetirementIntegrity.valid(
                    objectMapper, receiptSet.request().retirement())
                    || !receiptSet.receiptSetId().equals(receiptSetId(
                    objectMapper, receiptSet.request(), receiptSet.requiredCopies(),
                    receiptSet.receipts()))
                    || !receiptSet.receiptSetFingerprint().equals(
                    receiptSetFingerprint(objectMapper, receiptSet))) {
                return false;
            }
            String expectedObjectId = objectId(
                    objectMapper, receiptSet.request().retirement());
            for (TestSuiteStabilityObservationExternalArchiveReceipt receipt
                    : receiptSet.receipts()) {
                if (!receipt.fingerprintVerified(objectMapper)
                        || !expectedObjectId.equals(receipt.objectId())) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /**
     * Creates a canonical set after an authority has verified every accepted signature.
     *
     * @param objectMapper canonical protocol mapper
     * @param request exact challenge-bound request
     * @param requiredCopies committed independent copy threshold
     * @param receipts accepted receipts sorted by authority id
     * @param confirmedAt local confirmation time inside the request window
     * @return canonical receipt set
     */
    public static TestSuiteStabilityObservationExternalArchiveReceiptSet sealSet(
            ObjectMapper objectMapper,
            TestSuiteStabilityObservationExternalArchiveRequest request,
            int requiredCopies,
            List<TestSuiteStabilityObservationExternalArchiveReceipt> receipts,
            Instant confirmedAt) {
        String setId = receiptSetId(objectMapper, request, requiredCopies, receipts);
        TestSuiteStabilityObservationExternalArchiveReceiptSet unsigned =
                new TestSuiteStabilityObservationExternalArchiveReceiptSet(
                        TestSuiteStabilityObservationExternalArchiveReceiptSet.SCHEMA_VERSION,
                        setId, request, requiredCopies, receipts, confirmedAt,
                        "sha256:" + "0".repeat(64));
        return new TestSuiteStabilityObservationExternalArchiveReceiptSet(
                unsigned.schemaVersion(), unsigned.receiptSetId(), unsigned.request(),
                unsigned.requiredCopies(), unsigned.receipts(), unsigned.confirmedAt(),
                receiptSetFingerprint(objectMapper, unsigned));
    }

    private static List<ReceiptRef> refs(
            List<TestSuiteStabilityObservationExternalArchiveReceipt> receipts) {
        return List.copyOf(Objects.requireNonNull(receipts, "receipts")).stream()
                .map(receipt -> new ReceiptRef(
                        receipt.authorityId(), receipt.failureDomain(),
                        receipt.receiptFingerprint()))
                .toList();
    }

    private record ObjectIdentity(
            String retirementId,
            String retirementFingerprint,
            String segmentId,
            String segmentFingerprint,
            String retentionPolicyFingerprint) {
    }

    private record ReceiptRef(
            String authorityId,
            String failureDomain,
            String receiptFingerprint) {
    }

    private record ReceiptSetIdentity(
            String schemaVersion,
            String requestFingerprint,
            int requiredCopies,
            List<ReceiptRef> receipts) {
    }

    private record ReceiptSetMaterial(
            String schemaVersion,
            String receiptSetId,
            TestSuiteStabilityObservationExternalArchiveRequest request,
            int requiredCopies,
            List<TestSuiteStabilityObservationExternalArchiveReceipt> receipts,
            Instant confirmedAt) {
    }
}
