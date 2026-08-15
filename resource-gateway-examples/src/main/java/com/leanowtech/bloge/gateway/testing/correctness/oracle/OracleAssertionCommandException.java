package com.leanowtech.bloge.gateway.testing.correctness.oracle;

/** Stable application error surfaced by Oracle and Assertion Set commands. */
public final class OracleAssertionCommandException extends RuntimeException {

    private final String code;

    public OracleAssertionCommandException(String code, String message) {
        super(message);
        this.code = code == null ? "RG.CORRECTNESS.ORACLE_ASSERTION_INVALID" : code.trim();
    }

    public String code() {
        return code;
    }
}
