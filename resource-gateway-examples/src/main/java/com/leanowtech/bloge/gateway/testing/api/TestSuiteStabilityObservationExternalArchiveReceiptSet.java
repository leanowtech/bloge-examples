package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Canonical multi-copy external WORM acknowledgement persisted before local active-row deletion.
 *
 * <p>The set retains the complete challenge-bound request and every independently signed accepted
 * receipt. Its own fingerprint is a local integrity envelope; trust comes from independently
 * verifying each external signature and the authority/failure-domain copy policy.</p>
 *
 * @param schemaVersion receipt-set protocol version
 * @param receiptSetId deterministic identity over request and ordered receipt identities
 * @param request exact challenge-bound external archive request
 * @param requiredCopies minimum independent receipts required by policy
 * @param receipts sorted independently signed authority receipts
 * @param confirmedAt local confirmation time before database commit
 * @param receiptSetFingerprint canonical complete set fingerprint excluding itself
 */
public record TestSuiteStabilityObservationExternalArchiveReceiptSet(
        String schemaVersion,
        String receiptSetId,
        TestSuiteStabilityObservationExternalArchiveRequest request,
        int requiredCopies,
        List<TestSuiteStabilityObservationExternalArchiveReceipt> receipts,
        Instant confirmedAt,
        String receiptSetFingerprint) {
    /** Current external archive receipt-set generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityObservationExternalArchiveReceiptSet.v1";
    /** Largest independently acknowledged copy set accepted by this protocol generation. */
    public static final int MAXIMUM_RECEIPTS = 16;

    private static final Pattern RECEIPT_SET_ID = Pattern.compile(
            "stability-observation-external-archive-receipts-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates exact request binding and independent copy topology. */
    public TestSuiteStabilityObservationExternalArchiveReceiptSet {
        schemaVersion = normalized(schemaVersion);
        receiptSetId = normalized(receiptSetId);
        receipts = receipts == null ? List.of() : List.copyOf(receipts);
        receiptSetFingerprint = normalized(receiptSetFingerprint);
        boolean topology = requiredCopies >= 1 && receipts.size() >= requiredCopies
                && receipts.size() <= MAXIMUM_RECEIPTS;
        HashSet<String> authorities = new HashSet<>();
        HashSet<String> domains = new HashSet<>();
        String previousAuthority = "";
        for (TestSuiteStabilityObservationExternalArchiveReceipt receipt : receipts) {
            if (receipt == null || request == null
                    || !request.requestFingerprint().equals(receipt.requestFingerprint())
                    || !request.trustDomain().equals(receipt.trustDomain())
                    || !request.archiveSetId().equals(receipt.archiveSetId())
                    || !request.retirement().evidence().retirementId()
                    .equals(receipt.retirementId())
                    || !request.retirement().retirementFingerprint()
                    .equals(receipt.retirementFingerprint())
                    || !request.retirement().evidence().archiveSegment().segmentId()
                    .equals(receipt.segmentId())
                    || !request.retirement().evidence().archiveSegment().segmentFingerprint()
                    .equals(receipt.segmentFingerprint())
                    || !request.retirement().evidence().retentionPolicyFingerprint()
                    .equals(receipt.retentionPolicyFingerprint())
                    || receipt.retainUntil().isBefore(request.retainUntil())
                    || !authorities.add(receipt.authorityId())
                    || !domains.add(receipt.failureDomain())
                    || previousAuthority.compareTo(receipt.authorityId()) >= 0) {
                topology = false;
                break;
            }
            previousAuthority = receipt.authorityId();
        }
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !RECEIPT_SET_ID.matcher(receiptSetId).matches()
                || request == null || !topology || confirmedAt == null
                || confirmedAt.isBefore(request.requestedAt())
                || !confirmedAt.isBefore(request.expiresAt())
                || receipts.stream().anyMatch(receipt ->
                receipt.issuedAt().isBefore(request.requestedAt())
                        || confirmedAt.isBefore(receipt.issuedAt())
                        || !confirmedAt.isBefore(receipt.expiresAt()))
                || !FINGERPRINT.matcher(receiptSetFingerprint).matches()) {
            throw new IllegalArgumentException(
                    "Invalid external observation-archive receipt set");
        }
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
