package com.leanowtech.bloge.gateway.gateway;

import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.List;
import java.util.Map;

/**
 * Result for one resource graph contract-test case.
 *
 * @param caseName case display name
 * @param passed whether the case passed all gates and assertions
 * @param graphSuccess whether the graph execution itself succeeded
 * @param outputNode selected output node
 * @param output selected output payload
 * @param outputConformsToSchema whether output matched the graph output schema
 * @param mockedResourceInvocations observed mocked resource calls
 * @param statusMap graph node status map
 * @param diagnostics diagnostics emitted by validation, execution, and assertions
 * @param assertionCount assertions evaluated for this case
 */
public record GatewayGraphContractTestCaseResult(
        String caseName,
        boolean passed,
        boolean graphSuccess,
        String outputNode,
        Object output,
        boolean outputConformsToSchema,
        List<GatewayGraphResourceInvocation> mockedResourceInvocations,
        Map<String, NodeStatus> statusMap,
        List<VisualDiagnostic> diagnostics,
        int assertionCount
) {
    /**
     * Creates a case result.
     */
    public GatewayGraphContractTestCaseResult {
        caseName = caseName == null ? "" : caseName;
        outputNode = outputNode == null ? "" : outputNode;
        mockedResourceInvocations = mockedResourceInvocations == null
                ? List.of()
                : List.copyOf(mockedResourceInvocations);
        statusMap = statusMap == null ? Map.of() : Map.copyOf(statusMap);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
