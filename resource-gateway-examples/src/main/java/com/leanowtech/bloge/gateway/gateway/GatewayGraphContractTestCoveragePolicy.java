package com.leanowtech.bloge.gateway.gateway;

import java.util.List;

/**
 * Minimum evidence policy for a resource graph contract-test suite.
 *
 * @param minCases minimum test cases required
 * @param minInputSchemaValidated minimum cases that must pass input schema validation
 * @param minContractOutputSchemaValidated minimum cases whose output must pass graph output schema validation
 * @param minMockedResourceCalls minimum mocked downstream resource invocations expected
 * @param minAssertionCount minimum output/node assertions expected
 * @param requiredOutputNodes output nodes that must be selected by at least one passing case
 */
public record GatewayGraphContractTestCoveragePolicy(
        int minCases,
        int minInputSchemaValidated,
        int minContractOutputSchemaValidated,
        int minMockedResourceCalls,
        int minAssertionCount,
        List<String> requiredOutputNodes
) {
    /**
     * Creates a policy.
     */
    public GatewayGraphContractTestCoveragePolicy {
        minCases = Math.max(minCases, 0);
        minInputSchemaValidated = Math.max(minInputSchemaValidated, 0);
        minContractOutputSchemaValidated = Math.max(minContractOutputSchemaValidated, 0);
        minMockedResourceCalls = Math.max(minMockedResourceCalls, 0);
        minAssertionCount = Math.max(minAssertionCount, 0);
        requiredOutputNodes = requiredOutputNodes == null
                ? List.of()
                : requiredOutputNodes.stream()
                        .filter(nodeId -> nodeId != null && !nodeId.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList();
    }

    /**
     * @return a policy that imposes no additional coverage threshold
     */
    public static GatewayGraphContractTestCoveragePolicy none() {
        return new GatewayGraphContractTestCoveragePolicy(0, 0, 0, 0, 0, List.of());
    }
}
