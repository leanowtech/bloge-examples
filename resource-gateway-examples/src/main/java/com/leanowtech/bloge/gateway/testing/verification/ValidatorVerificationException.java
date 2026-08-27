package com.leanowtech.bloge.gateway.testing.verification;

/** Payload-free configuration error for the validator self-proof boundary. */
public final class ValidatorVerificationException extends IllegalArgumentException {
    public enum Code {
        INVALID_INPUT,
        FINGERPRINT_MISMATCH,
        CORPUS_SHAPE_INVALID
    }

    private final Code code;

    public ValidatorVerificationException(Code code) {
        super("RG.VERIFICATION." + require(code).name());
        this.code = code;
    }

    public Code code() {
        return code;
    }

    private static Code require(Code code) {
        if (code == null) {
            throw new IllegalArgumentException("RG.VERIFICATION.INVALID_INPUT");
        }
        return code;
    }
}
