package com.leanowtech.bloge.gateway.gateway;

import java.util.List;

/**
 * Lightweight contract-test suite catalog row.
 *
 * @param suiteId stable suite id
 * @param displayName human-readable name
 * @param graphName target graph name
 * @param caseCount number of table rows
 * @param tags suite tags
 * @param coveragePolicy minimum evidence policy
 */
public record GatewayGraphContractTestSuiteSummary(
        String suiteId,
        String displayName,
        String graphName,
        int caseCount,
        List<String> tags,
        GatewayGraphContractTestCoveragePolicy coveragePolicy
) {
    /**
     * Creates a summary row.
     */
    public GatewayGraphContractTestSuiteSummary {
        suiteId = suiteId == null ? "" : suiteId;
        displayName = displayName == null ? "" : displayName;
        graphName = graphName == null ? "" : graphName;
        caseCount = Math.max(caseCount, 0);
        tags = tags == null ? List.of() : List.copyOf(tags);
        coveragePolicy = coveragePolicy == null
                ? GatewayGraphContractTestCoveragePolicy.none()
                : coveragePolicy;
    }

    /**
     * @param suite stored suite
     * @return summary row
     */
    public static GatewayGraphContractTestSuiteSummary from(GatewayGraphContractTestSuite suite) {
        GatewayGraphContractTestSuiteRequest request = suite.request();
        return new GatewayGraphContractTestSuiteSummary(
                suite.suiteId(),
                suite.displayName(),
                request.graphName(),
                request.cases().size(),
                suite.tags(),
                suite.coveragePolicy());
    }
}
