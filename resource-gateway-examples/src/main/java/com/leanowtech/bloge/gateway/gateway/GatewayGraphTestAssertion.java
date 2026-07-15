package com.leanowtech.bloge.gateway.gateway;

/**
 * One assertion evaluated against a resource graph contract-test output.
 *
 * @param mode assertion mode
 * @param path JSON Pointer path for path-scoped assertions
 * @param expectedValue expected value for equality or schema assertions
 * @param numericTolerance optional inclusive absolute tolerance for numeric equality assertions
 */
public record GatewayGraphTestAssertion(
        Mode mode,
        String path,
        Object expectedValue,
        Double numericTolerance
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
        if (numericTolerance != null
                && (!Double.isFinite(numericTolerance) || numericTolerance < 0)) {
            throw new IllegalArgumentException("numericTolerance must be finite and non-negative.");
        }
        if (numericTolerance != null
                && mode != Mode.OUTPUT_EQUALS && mode != Mode.PATH_EQUALS) {
            throw new IllegalArgumentException(
                    "numericTolerance is supported only for numeric equality assertions.");
        }
        if (numericTolerance != null && !(expectedValue instanceof Number)) {
            throw new IllegalArgumentException(
                    "numericTolerance requires a numeric expectedValue.");
        }
    }

    /**
     * Preserves the v1 assertion constructor while defaulting to exact comparison.
     *
     * @param mode assertion mode
     * @param path JSON Pointer path
     * @param expectedValue expected value
     */
    public GatewayGraphTestAssertion(Mode mode, String path, Object expectedValue) {
        this(mode, path, expectedValue, null);
    }
}
