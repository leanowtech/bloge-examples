package com.leanowtech.bloge.gateway.visual.testing;

import java.util.List;

/**
 * Batch row for one stored operator contract-test suite execution.
 *
 * @param suiteId stable suite id
 * @param displayName human-readable suite name
 * @param operatorRef tested operator reference
 * @param tags suite tags
 * @param result suite result
 */
public record VisualOperatorContractTestSuiteRunResult(
        String suiteId,
        String displayName,
        String operatorRef,
        List<String> tags,
        VisualOperatorContractTestSuiteResult result
) {
    /**
     * Creates a batch row.
     */
    public VisualOperatorContractTestSuiteRunResult {
        suiteId = suiteId == null ? "" : suiteId;
        displayName = displayName == null ? "" : displayName;
        operatorRef = operatorRef == null ? "" : operatorRef;
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
