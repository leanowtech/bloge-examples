package com.leanowtech.bloge.gateway.testing.domain;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Domain-separated detached signature over one exact lifecycle page and its ordered retirements.
 *
 * @param schemaVersion exact attestation generation
 * @param signatureStatus closed producer verification status
 * @param lifecyclePageId deterministic page identity
 * @param requestFingerprint canonical request identity
 * @param pageFingerprint canonical complete-page identity
 * @param scopeFingerprint exact-suite ledger scope
 * @param startingFloorFingerprint page starting floor
 * @param terminalFloorFingerprint page terminal floor
 * @param currentFloorFingerprint snapshot current floor pin
 * @param headFingerprint snapshot current head pin
 * @param retirementRefs ordered retirement identities and fingerprints
 * @param signedAt producer signature time
 * @param keyId detached signing key identity
 * @param algorithm detached signature algorithm
 * @param signature base64 detached signature
 * @param independentlyVerifiable whether external key material can verify the signature
 */
public record TestSuiteStabilityObservationLedgerLifecycleAttestation(
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
        List<RetirementRef> retirementRefs,
        Instant signedAt,
        String keyId,
        String algorithm,
        String signature,
        boolean independentlyVerifiable
) {
    /** Current lifecycle-page attestation generation. */
    public static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityObservationLedgerLifecycleAttestation.v1";
    private static final Pattern PAGE_ID = Pattern.compile(
            "stability-observation-lifecycle-page-[a-f0-9]{64}");
    private static final Pattern RETIREMENT_ID = Pattern.compile(
            "stability-observation-retirement-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates one complete verified detached lifecycle-page signature. */
    public TestSuiteStabilityObservationLedgerLifecycleAttestation {
        schemaVersion = normalized(schemaVersion);
        lifecyclePageId = normalized(lifecyclePageId);
        requestFingerprint = normalized(requestFingerprint);
        pageFingerprint = normalized(pageFingerprint);
        scopeFingerprint = normalized(scopeFingerprint);
        startingFloorFingerprint = normalized(startingFloorFingerprint);
        terminalFloorFingerprint = normalized(terminalFloorFingerprint);
        currentFloorFingerprint = normalized(currentFloorFingerprint);
        headFingerprint = normalized(headFingerprint);
        retirementRefs = retirementRefs == null ? List.of() : List.copyOf(retirementRefs);
        keyId = normalized(keyId);
        algorithm = normalized(algorithm);
        signature = normalized(signature);
        if (!SCHEMA_VERSION.equals(schemaVersion) || signatureStatus != SignatureStatus.VERIFIED
                || !PAGE_ID.matcher(lifecyclePageId).matches()
                || !fingerprint(requestFingerprint) || !fingerprint(pageFingerprint)
                || !fingerprint(scopeFingerprint) || !fingerprint(startingFloorFingerprint)
                || !fingerprint(terminalFloorFingerprint)
                || !fingerprint(currentFloorFingerprint) || !fingerprint(headFingerprint)
                || retirementRefs.stream().anyMatch(value -> value == null)
                || signedAt == null || Instant.EPOCH.equals(signedAt)
                || keyId.isBlank() || algorithm.isBlank() || signature.isBlank()
                || !independentlyVerifiable) {
            throw new IllegalArgumentException(
                    "Complete verified lifecycle-page attestation is required");
        }
    }

    /** Closed producer-side signature status. */
    public enum SignatureStatus {
        /** Signature was generated and immediately verified. */
        VERIFIED
    }

    /**
     * Ordered compact identity of one complete retirement carried by the page.
     *
     * @param retirementGeneration exact contiguous generation
     * @param retirementId deterministic retirement identity
     * @param retirementFingerprint complete retirement-record fingerprint
     */
    public record RetirementRef(
            long retirementGeneration,
            String retirementId,
            String retirementFingerprint
    ) {
        /** Validates one positive, complete retirement reference. */
        public RetirementRef {
            retirementId = normalized(retirementId);
            retirementFingerprint = normalized(retirementFingerprint);
            if (retirementGeneration < 1 || !RETIREMENT_ID.matcher(retirementId).matches()
                    || !fingerprint(retirementFingerprint)) {
                throw new IllegalArgumentException(
                        "Complete lifecycle retirement reference is required");
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
