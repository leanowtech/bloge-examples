package com.leanowtech.bloge.gateway.testkit.ept;

/**
 * Receipt verification failure.  The {@code code} field is the stable identity;
 * the message deliberately omits payload content to prevent accidental leakage.
 *
 * <p>Known codes:
 * <ul>
 *   <li>{@code INVALID_B1_RECEIPT} — B1 receipt failed any verification check</li>
 *   <li>{@code INVALID_R1_OUTER_COMMITMENT} — R1 receipt failed any verification check</li>
 *   <li>{@code B1_RECEIPT_SIZE_EXCEEDED} — sealed B1 exceeds MAX_SIZE_BYTES</li>
 *   <li>{@code R1_RECEIPT_SIZE_EXCEEDED} — sealed R1 exceeds MAX_SIZE_BYTES</li>
 *   <li>{@code B1_SEAL_INTERNAL} — unexpected error during B1 seal</li>
 *   <li>{@code R1_SEAL_INTERNAL} — unexpected error during R1 seal</li>
 * </ul>
 */
final class ReceiptException extends RuntimeException {
    private final String code;

    ReceiptException(String code, String message) {
        super(message);
        this.code = code;
    }

    ReceiptException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** Stable failure identity, never null. */
    String code() { return code; }
}
