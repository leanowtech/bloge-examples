package com.leanowtech.bloge.gateway.testing.correctness.fixture;

/** Stable protected-material failure without payload-bearing diagnostics. */
public final class FixtureMaterialCommandException extends RuntimeException {

    private final int status;
    private final String code;

    public FixtureMaterialCommandException(int status, String code, String message) {
        super(message);
        if (status < 400 || status > 599) {
            throw new IllegalArgumentException("Fixture material failure status is invalid");
        }
        this.status = status;
        this.code = code == null || code.isBlank()
                ? "RG.CORRECTNESS.FIXTURE_MATERIAL_FAILED" : code.trim();
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
