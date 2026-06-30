package com.leanowtech.bloge.gateway.visual.golden;

/**
 * One assertion evaluated against a golden case run output.
 *
 * @param mode assertion mode
 * @param path JSON Pointer path for path-scoped assertions
 * @param expectedValue expected value for equality assertions
 */
public record VisualGraphGoldenAssertion(
        Mode mode,
        String path,
        Object expectedValue
) {
    /**
     * Supported golden assertion modes.
     */
    public enum Mode {
        OUTPUT_EQUALS,
        PATH_EQUALS,
        PATH_EXISTS,
        PATH_ABSENT
    }

    /**
     * Creates an assertion.
     */
    public VisualGraphGoldenAssertion {
        mode = mode == null ? Mode.OUTPUT_EQUALS : mode;
        path = path == null ? "" : path.trim();
    }
}
