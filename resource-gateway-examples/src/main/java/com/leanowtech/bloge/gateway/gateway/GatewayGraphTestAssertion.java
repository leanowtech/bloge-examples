package com.leanowtech.bloge.gateway.gateway;

/**
 * One assertion evaluated against a resource graph contract-test output.
 *
 * @param mode assertion mode
 * @param path JSON Pointer path for path-scoped assertions
 * @param expectedValue expected value for equality or schema assertions
 */
public record GatewayGraphTestAssertion(
        Mode mode,
        String path,
        Object expectedValue
) {
    /**
     * Supported resource graph contract-test assertion modes.
     */
    public enum Mode {
        OUTPUT_EQUALS,
        OUTPUT_MATCHES_SCHEMA,
        PATH_EQUALS,
        PATH_EXISTS,
        PATH_ABSENT
    }

    /**
     * Creates an assertion.
     */
    public GatewayGraphTestAssertion {
        mode = mode == null ? Mode.OUTPUT_EQUALS : mode;
        path = path == null ? "" : path.trim();
    }
}
