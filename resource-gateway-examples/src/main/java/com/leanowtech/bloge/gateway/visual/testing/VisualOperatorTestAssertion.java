package com.leanowtech.bloge.gateway.visual.testing;

/**
 * One assertion evaluated against a mocked operator output port.
 *
 * @param mode assertion mode
 * @param path JSON Pointer path for path-scoped assertions
 * @param expectedValue expected value for equality or schema assertions
 */
public record VisualOperatorTestAssertion(
        Mode mode,
        String path,
        Object expectedValue
) {
    /**
     * Supported operator table-test assertion modes.
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
    public VisualOperatorTestAssertion {
        mode = mode == null ? Mode.OUTPUT_EQUALS : mode;
        path = path == null ? "" : path.trim();
    }
}
