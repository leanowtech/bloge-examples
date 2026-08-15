package com.leanowtech.bloge.gateway.testing.correctness.fixture;

/** Stable Fixture catalog lifecycle failure. */
public final class FixtureCatalogCommandException extends RuntimeException {

    private final String code;

    public FixtureCatalogCommandException(String code, String message) {
        super(message);
        this.code = code == null || code.isBlank()
                ? "RG.CORRECTNESS.FIXTURE_COMMAND_FAILED" : code.trim();
    }

    public String code() {
        return code;
    }
}
