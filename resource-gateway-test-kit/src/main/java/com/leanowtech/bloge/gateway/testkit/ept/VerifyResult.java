package com.leanowtech.bloge.gateway.testkit.ept;

import java.util.Objects;

/**
 * Read-only verification result for EPT verify round-trip.
 *
 * <p>Zero-write: no file is created, no lock is acquired, no Store is called.</p>
 *
 * @param verified true if all pins match
 * @param stableRequestId verified stable request identity
 * @param transactionId verified transaction identity
 * @param computedB0RawFingerprint recomputed raw fingerprint of b0-inner-manifest.json
 * @param computedB0CanonicalFingerprint recomputed canonical fingerprint
 * @param computedB0ClosureFingerprint recomputed B0 closure fingerprint
 * @param computedB1ReceiptFingerprint recomputed B1 receipt fingerprint
 * @param computedR1Fingerprint recomputed R1 fingerprint
 * @param computedAuthorityFingerprint recomputed authority fingerprint
 * @param computedAuthorityEpoch recomputed authority epoch
 * @param mismatchCode stable failure code when verified is false, null otherwise
 */
public record VerifyResult(
        boolean verified,
        String stableRequestId,
        String transactionId,
        String computedB0RawFingerprint,
        String computedB0CanonicalFingerprint,
        String computedB0ClosureFingerprint,
        String computedB1ReceiptFingerprint,
        String computedR1Fingerprint,
        String computedAuthorityFingerprint,
        long computedAuthorityEpoch,
        String mismatchCode) {

    /**
     * Creates a passing verification result with all recomputed fingerprints.
     *
     * @param stableRequestId verified stable request identity
     * @param transactionId verified transaction identity
     * @param b0Raw recomputed b0 raw fingerprint
     * @param b0Canonical recomputed b0 canonical fingerprint
     * @param b0Closure recomputed b0 closure fingerprint
     * @param b1 recomputed b1 receipt fingerprint
     * @param r1 recomputed r1 fingerprint
     * @param authorityFingerprint recomputed authority fingerprint
     * @param authorityEpoch recomputed authority epoch
     * @return passing verification result
     */
    public static VerifyResult pass(String stableRequestId,
                                    String transactionId,
                                    String b0Raw,
                                    String b0Canonical,
                                    String b0Closure,
                                    String b1,
                                    String r1,
                                    String authorityFingerprint,
                                    long authorityEpoch) {
        return new VerifyResult(true, stableRequestId, transactionId,
                b0Raw, b0Canonical, b0Closure, b1, r1,
                authorityFingerprint, authorityEpoch, null);
    }

    /**
     * Creates a failing verification result with a stable mismatch code.
     *
     * @param stableRequestId verified stable request identity
     * @param transactionId verified transaction identity
     * @param mismatchCode stable failure code
     * @return failing verification result
     */
    public static VerifyResult fail(String stableRequestId,
                                    String transactionId,
                                    String mismatchCode) {
        return new VerifyResult(false, stableRequestId, transactionId,
                null, null, null, null, null, null, 0,
                Objects.requireNonNull(mismatchCode, "mismatchCode required"));
    }
}
