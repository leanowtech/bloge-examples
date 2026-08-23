package com.leanowtech.bloge.gateway.testkit.ept;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Closed expected pins for EPT verify round-trip.
 *
 * <p>Contains all fingerprints required for complete B0+B1+R1+authority verification.
 * The authorityFingerprint and authorityEpoch are provided by FencingAuthority.</p>
 *
 * <p>B0-only recovery verification is handled by an internal method, not this record.</p>
 *
 * @param stableRequestId stable request identity without nonce
 * @param transactionId transaction identity including nonce
 * @param b0RawFingerprint expected raw fingerprint of b0-inner-manifest.json
 * @param b0CanonicalFingerprint expected canonical fingerprint of b0-inner-manifest.json
 * @param b0ClosureFingerprint expected B0 closure fingerprint
 * @param b1ReceiptFingerprint expected B1 Store immutable receipt fingerprint
 * @param r1Fingerprint expected R1 final commitment fingerprint
 * @param authorityFingerprint expected FencingAuthority token fingerprint
 * @param authorityEpoch positive epoch assigned by FencingAuthority
 */
public record ExpectedPins(
        String stableRequestId,
        String transactionId,
        String b0RawFingerprint,
        String b0CanonicalFingerprint,
        String b0ClosureFingerprint,
        String b1ReceiptFingerprint,
        String r1Fingerprint,
        String authorityFingerprint,
        long authorityEpoch) {

    public static final String MESSAGE_VERSION =
            "resource-gateway.capability-studio.evidence-publication-transaction.v1";

    private static final Pattern FINGERPRINT =
            Pattern.compile("^sha256:[0-9a-f]{64}$");

    /** Validates all nine fingerprint fields and authorityEpoch. */
    public ExpectedPins {
        requireFingerprint(stableRequestId, "stableRequestId");
        requireFingerprint(transactionId, "transactionId");
        requireFingerprint(b0RawFingerprint, "b0RawFingerprint");
        requireFingerprint(b0CanonicalFingerprint, "b0CanonicalFingerprint");
        requireFingerprint(b0ClosureFingerprint, "b0ClosureFingerprint");
        requireFingerprint(b1ReceiptFingerprint, "b1ReceiptFingerprint");
        requireFingerprint(r1Fingerprint, "r1Fingerprint");
        requireFingerprint(authorityFingerprint, "authorityFingerprint");
        if (authorityEpoch <= 0) {
            throw new IllegalArgumentException("authorityEpoch must be > 0");
        }
    }

    private static void requireFingerprint(String value, String field) {
        if (value == null || !FINGERPRINT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    field + " must be a valid sha256:fingerprint (64 hex chars)");
        }
    }
}
