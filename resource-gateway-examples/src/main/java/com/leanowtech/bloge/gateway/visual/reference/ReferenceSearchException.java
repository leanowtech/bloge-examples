package com.leanowtech.bloge.gateway.visual.reference;

/** Stable domain error for bounded cursor searches. */
public final class ReferenceSearchException extends RuntimeException {
    private final Code code;

    public ReferenceSearchException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        CURSOR_STALE("RG.REFERENCE.CURSOR_STALE"),
        QUERY_FINGERPRINT_MISMATCH("RG.REFERENCE.QUERY_FINGERPRINT_MISMATCH");

        private final String wireCode;

        Code(String wireCode) {
            this.wireCode = wireCode;
        }

        public String wireCode() {
            return wireCode;
        }
    }
}
