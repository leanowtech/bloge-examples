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
 * @param caseType governance intent retained when the case becomes an immutable common suite asset
 */
public record GatewayGraphContractTestCase(
        String schemaVersion,
        String name,
        String description,
        Map<String, Object> context,
        List<GatewayGraphResourceMock> resourceMocks,
        String outputNode,
        List<GatewayGraphTestAssertion> assertions,
        Map<String, List<GatewayGraphTestAssertion>> nodeAssertions,
        CaseType caseType
) {
    public static final String SCHEMA_VERSION = "bloge.gatewayGraphContractTestCase.v1";

    /** Governance intent shared with the common TestSuite protocol during catalog migration. */
    public enum CaseType {
        GOLDEN,
        NEGATIVE,
        BOUNDARY,
        REGRESSION
    }

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
        caseType = caseType == null ? CaseType.REGRESSION : caseType;
    }

    /**
     * Preserves the original canonical constructor and defaults legacy rows to regression intent.
     */
    public GatewayGraphContractTestCase(String schemaVersion,
                                        String name,
                                        String description,
                                        Map<String, Object> context,
                                        List<GatewayGraphResourceMock> resourceMocks,
                                        String outputNode,
                                        List<GatewayGraphTestAssertion> assertions,
                                        Map<String, List<GatewayGraphTestAssertion>> nodeAssertions) {
        this(schemaVersion, name, description, context, resourceMocks, outputNode, assertions,
                nodeAssertions, CaseType.REGRESSION);
    }

    /**
     * Convenience constructor for table rows.
     */
    public GatewayGraphContractTestCase(String name,
                                        Map<String, Object> context,
                                        List<GatewayGraphResourceMock> resourceMocks,
                                        String outputNode,
                                        List<GatewayGraphTestAssertion> assertions) {
        this(SCHEMA_VERSION, name, "", context, resourceMocks, outputNode, assertions, Map.of(),
                CaseType.REGRESSION);
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
        this(SCHEMA_VERSION, name, "", context, resourceMocks, outputNode, assertions, nodeAssertions,
                CaseType.REGRESSION);
    }

    /**
     * Creates a table row with an explicit governance intent.
     *
     * @param caseType case intent used by common suite coverage
     * @param name display name
     * @param context graph context input
     * @param resourceMocks mocked downstream resource responses
     * @param outputNode optional terminal node override
     * @param assertions output assertions
     */
    public GatewayGraphContractTestCase(CaseType caseType,
                                        String name,
                                        Map<String, Object> context,
                                        List<GatewayGraphResourceMock> resourceMocks,
                                        String outputNode,
                                        List<GatewayGraphTestAssertion> assertions) {
        this(SCHEMA_VERSION, name, "", context, resourceMocks, outputNode, assertions, Map.of(), caseType);
    }
}
