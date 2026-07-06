package com.leanowtech.bloge.gateway.gateway;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One table row for resource graph contract testing.
 *
 * @param schemaVersion case schema version
 * @param name display name
 * @param description optional description
 * @param context graph context input
 * @param resourceMocks mocked downstream resource responses
 * @param outputNode optional terminal node override
 * @param assertions output assertions
 * @param nodeAssertions node-scoped assertions keyed by graph node id
 */
public record GatewayGraphContractTestCase(
        String schemaVersion,
        String name,
        String description,
        Map<String, Object> context,
        List<GatewayGraphResourceMock> resourceMocks,
        String outputNode,
        List<GatewayGraphTestAssertion> assertions,
        Map<String, List<GatewayGraphTestAssertion>> nodeAssertions
) {
    public static final String SCHEMA_VERSION = "bloge.gatewayGraphContractTestCase.v1";

    /**
     * Creates a test case.
     */
    public GatewayGraphContractTestCase {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        name = name == null || name.isBlank() ? "Contract test case" : name.trim();
        description = description == null ? "" : description;
        context = context == null ? Map.of() : new LinkedHashMap<>(context);
        resourceMocks = resourceMocks == null ? List.of() : List.copyOf(resourceMocks);
        outputNode = outputNode == null ? "" : outputNode.trim();
        assertions = assertions == null ? List.of() : List.copyOf(assertions);
        if (nodeAssertions == null || nodeAssertions.isEmpty()) {
            nodeAssertions = Map.of();
        } else {
            Map<String, List<GatewayGraphTestAssertion>> normalized = new LinkedHashMap<>();
            nodeAssertions.forEach((nodeId, nodeAssertionsForNode) -> normalized.put(
                    nodeId == null ? "" : nodeId.trim(),
                    nodeAssertionsForNode == null ? List.of() : List.copyOf(nodeAssertionsForNode)));
            nodeAssertions = Map.copyOf(normalized);
        }
    }

    /**
     * Convenience constructor for table rows.
     */
    public GatewayGraphContractTestCase(String name,
                                        Map<String, Object> context,
                                        List<GatewayGraphResourceMock> resourceMocks,
                                        String outputNode,
                                        List<GatewayGraphTestAssertion> assertions) {
        this(SCHEMA_VERSION, name, "", context, resourceMocks, outputNode, assertions, Map.of());
    }

    /**
     * Convenience constructor for table rows with node-level assertions.
     */
    public GatewayGraphContractTestCase(String name,
                                        Map<String, Object> context,
                                        List<GatewayGraphResourceMock> resourceMocks,
                                        String outputNode,
                                        List<GatewayGraphTestAssertion> assertions,
                                        Map<String, List<GatewayGraphTestAssertion>> nodeAssertions) {
        this(SCHEMA_VERSION, name, "", context, resourceMocks, outputNode, assertions, nodeAssertions);
    }
}
