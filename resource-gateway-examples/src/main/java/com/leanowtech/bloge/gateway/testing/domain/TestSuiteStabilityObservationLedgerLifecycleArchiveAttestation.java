package com.leanowtech.bloge.gateway.testing.domain;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Domain-separated v2 signature over a lifecycle page and its external archive proof closure.
 *
 * @param schemaVersion exact receipt-aware attestation generation
 * @param signatureStatus closed producer verification status
 * @param lifecyclePageId deterministic page identity
 * @param requestFingerprint canonical request identity
 * @param pageFingerprint canonical complete-page identity
 * @param scopeFingerprint exact-suite ledger scope
 * @param startingFloorFingerprint page starting floor
 * @param terminalFloorFingerprint page terminal floor
 * @param currentFloorFingerprint snapshot current floor pin
 * @param headFingerprint snapshot current head pin
 * @param archiveRefs ordered retirement and receipt-set closure
 * @param signedAt producer signature time
 * @param keyId detached signing key identity
 * @param algorithm detached signature algorithm
 * @param signature base64 detached signature
 * @param independentlyVerifiable whether external key material can verify the signature
 */
public record TestSuiteStabilityObservationLedgerLifecycleArchiveAttestation(
        String schemaVersion,
        SignatureStatus signatureStatus,
        String lifecyclePageId,
        String requestFingerprint,
        String pageFingerprint,
        String scopeFingerprint,
        String startingFloorFingerprint,
        String terminalFloorFingerprint,
        String currentFloorFingerprint,
        String headFingerprint,
        List<ArchiveRef> archiveRefs,
        Instant signedAt,
        String keyId,
        String algorithm,
        String signature,
        boolean independentlyVerifiable
) {
    /** Current receipt-aware lifecycle-page attestation generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityObservationLedgerLifecycleAttestation.v2";
    private static final Pattern PAGE_ID = Pattern.compile(
            "stability-observation-lifecycle-page-[a-f0-9]{64}");
    private static final Pattern RETIREMENT_ID = Pattern.compile(
            "stability-observation-retirement-[a-f0-9]{64}");
    private static final Pattern RECEIPT_SET_ID = Pattern.compile(
            "stability-observation-external-archive-receipts-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates one complete verified v2 detached lifecycle signature. */
    public TestSuiteStabilityObservationLedgerLifecycleArchiveAttestation {
        schemaVersion = normalized(schemaVersion);
        lifecyclePageId = normalized(lifecyclePageId);
        requestFingerprint = normalized(requestFingerprint);
        pageFingerprint = normalized(pageFingerprint);
        scopeFingerprint = normalized(scopeFingerprint);
        startingFloorFingerprint = normalized(startingFloorFingerprint);
        terminalFloorFingerprint = normalized(terminalFloorFingerprint);
        currentFloorFingerprint = normalized(currentFloorFingerprint);
        headFingerprint = normalized(headFingerprint);
        archiveRefs = archiveRefs == null ? List.of() : List.copyOf(archiveRefs);
        keyId = normalized(keyId);
        algorithm = normalized(algorithm);
        signature = normalized(signature);
        if (!SCHEMA_VERSION.equals(schemaVersion) || signatureStatus != SignatureStatus.VERIFIED
                || !PAGE_ID.matcher(lifecyclePageId).matches()
                || !fingerprint(requestFingerprint) || !fingerprint(pageFingerprint)
                || !fingerprint(scopeFingerprint) || !fingerprint(startingFloorFingerprint)
                || !fingerprint(terminalFloorFingerprint)
                || !fingerprint(currentFloorFingerprint) || !fingerprint(headFingerprint)
                || archiveRefs.size() > 10 || archiveRefs.stream().anyMatch(value -> value == null)
                || signedAt == null || Instant.EPOCH.equals(signedAt)
                || keyId.isBlank() || !"Ed25519".equals(algorithm) || signature.isBlank()
                || !independentlyVerifiable) {
            throw new IllegalArgumentException(
                    "Complete verified receipt-aware lifecycle attestation is required");
        }
    }

    /** Closed producer-side signature status. */
    public enum SignatureStatus {
        /** Signature was generated and immediately verified. */
        VERIFIED
    }

    /**
     * Ordered identity of one signed retirement and the exact external proof that admitted it.
     *
     * @param retirementGeneration exact contiguous generation
     * @param retirementId deterministic retirement identity
     * @param retirementFingerprint complete retirement-record fingerprint
     * @param receiptSetId deterministic external receipt-set identity
     * @param receiptSetFingerprint complete external receipt-set fingerprint
     * @param requiredCopies committed independent copy threshold
     * @param receiptCount exact number of signed receipts retained in the set
     */
    public record ArchiveRef(
            long retirementGeneration,
            String retirementId,
            String retirementFingerprint,
            String receiptSetId,
            String receiptSetFingerprint,
            int requiredCopies,
            int receiptCount
    ) {
        /** Validates one complete bounded archive-proof reference. */
        public ArchiveRef {
            retirementId = normalized(retirementId);
            retirementFingerprint = normalized(retirementFingerprint);
            receiptSetId = normalized(receiptSetId);
            receiptSetFingerprint = normalized(receiptSetFingerprint);
            if (retirementGeneration < 1 || !RETIREMENT_ID.matcher(retirementId).matches()
                    || !fingerprint(retirementFingerprint)
                    || !RECEIPT_SET_ID.matcher(receiptSetId).matches()
                    || !fingerprint(receiptSetFingerprint)
                    || requiredCopies < 1 || receiptCount < requiredCopies
                    || receiptCount > 16) {
                throw new IllegalArgumentException(
                        "Complete lifecycle archive reference is required");
            }
        }
    }

    private static boolean fingerprint(String value) {
        return FINGERPRINT.matcher(normalized(value)).matches();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
