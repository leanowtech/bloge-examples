package com.leanowtech.bloge.gateway.gateway;

import java.util.List;

/**
 * Batch-run row for one stored suite.
 *
 * @param suiteId stable suite id
 * @param displayName human-readable suite name
 * @param graphName target graph name
 * @param tags suite tags
 * @param result suite execution result
 */
public record GatewayGraphContractTestSuiteRunResult(
        String suiteId,
        String displayName,
        String graphName,
        List<String> tags,
        GatewayGraphContractTestSuiteResult result
) {
    /**
     * Creates a batch-run row.
     */
    public GatewayGraphContractTestSuiteRunResult {
        suiteId = suiteId == null ? "" : suiteId;
        displayName = displayName == null ? "" : displayName;
        graphName = graphName == null ? "" : graphName;
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
