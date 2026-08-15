package com.leanowtech.bloge.gateway.testing.correctness.coverage;

/** Stable application error used by later HTTP command adapters. */
public final class CoverageCommandException extends RuntimeException {

    private final String code;

    public CoverageCommandException(String code, String message) {
        super(message);
        this.code = code == null ? "RG.CORRECTNESS.COVERAGE_COMMAND_FAILED" : code.trim();
    }

    public String code() {
        return code;
    }
}
