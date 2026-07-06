package com.leanowtech.bloge.gateway.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.model.NodeSpec;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.gateway.operator.HttpResourceInput;
import com.leanowtech.bloge.gateway.operator.HttpResourceOutput;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Runs table-driven, schema-gated contract tests for built-in resource gateway graphs.
 *
 * <p>The production graph topology and BLOGE engine still execute normally. Only descriptor-backed
 * {@code httpResource} calls are substituted with deterministic mock rows supplied by the suite.
 * This preserves decision-table, transform, branch, retry, and fallback behavior while avoiding
 * outbound downstream API calls.</p>
 */
@Service
public class GatewayGraphContractTestService {

    private final GatewayGraphService graphService;
    private final ObjectMapper objectMapper;

    /**
     * @param graphService resource graph service
     * @param objectMapper JSON mapper for assertion evaluation
     */
    public GatewayGraphContractTestService(GatewayGraphService graphService, ObjectMapper objectMapper) {
        this.graphService = graphService;
        this.objectMapper = objectMapper;
    }

    /**
     * Runs a resource graph contract-test suite.
     *
     * @param request suite request
     * @return suite result
     */
    public GatewayGraphContractTestSuiteResult run(GatewayGraphContractTestSuiteRequest request) {
        GatewayGraphContractTestSuiteRequest safeRequest = request == null
                ? new GatewayGraphContractTestSuiteRequest("", List.of())
                : request;
        List<VisualDiagnostic> suiteDiagnostics = validateSuiteRequest(safeRequest);
        if (!suiteDiagnostics.isEmpty()) {
            return suiteResult(safeRequest.graphName(), List.of(), suiteDiagnostics);
        }

        Graph graph;
        GatewayGraphContract contract;
        try {
            graph = graphService.requireGraph(safeRequest.graphName());
            contract = graphService.requireContract(safeRequest.graphName());
        } catch (IllegalArgumentException ex) {
            return suiteResult(safeRequest.graphName(), List.of(), List.of(VisualDiagnostic.error(
                    "gateway.graphContractTest.graphUnknown",
                    ex.getMessage(),
                    "/graphName")));
        }

        List<GatewayGraphContractTestCaseResult> results = new ArrayList<>();
        for (GatewayGraphContractTestCase testCase : safeRequest.cases()) {
            results.add(runCase(graph, contract, testCase));
        }
        return suiteResult(contract.graphName(), results, List.of());
    }

    private GatewayGraphContractTestCaseResult runCase(Graph graph,
                                                       GatewayGraphContract contract,
                                                       GatewayGraphContractTestCase testCase) {
        GatewayGraphContractTestCase safeCase = testCase == null
                ? new GatewayGraphContractTestCase("", Map.of(), List.of(), "", List.of(), Map.of())
                : testCase;
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        diagnostics.addAll(validateCaseHeader(safeCase));
        diagnostics.addAll(VisualSchemaValidator.validateValue(contract.inputSchema(), safeCase.context(),
                "/context"));
        if (diagnostics.stream().anyMatch(VisualDiagnostic::error)) {
            return caseResult(safeCase, false, false, "", null, false, List.of(), Map.of(), diagnostics);
        }

        MockHttpResourceOperator mockResource = new MockHttpResourceOperator(safeCase.resourceMocks());
        GraphResult result;
        try {
            result = graphService.engine().executeWithOperators(
                    graph,
                    new GraphContext(safeCase.context()),
                    httpResourceOverrides(graph, mockResource));
        } catch (RuntimeException ex) {
            diagnostics.add(VisualDiagnostic.error("gateway.graphContractTest.graphExecutionException",
                    "Contract-test graph execution failed before producing a result: %s"
                            .formatted(ex.getMessage()),
                    "/graph"));
            diagnostics.addAll(resourceMockDiagnostics(mockResource));
            return caseResult(safeCase, false, false, "", null, false,
                    mockResource.invocations(), Map.of(), diagnostics);
        }

        diagnostics.addAll(resourceMockDiagnostics(mockResource));
        if (!result.isSuccess()) {
            result.errors().forEach(error -> diagnostics.add(VisualDiagnostic.error(
                    "gateway.graphContractTest.graphExecutionFailed",
                    "%s: %s".formatted(error.nodeId(), error.exception().getMessage()),
                    "/graph")));
        }

        String outputNode = selectedOutputNode(contract, safeCase, result);
        Object output = outputNode.isBlank()
                ? null
                : result.findOutput(outputNode, Object.class).orElse(null);
        if (outputNode.isBlank()) {
            diagnostics.add(VisualDiagnostic.error("gateway.graphContractTest.outputNodeMissing",
                    "No output node was selected and none of the contract outputNodes produced output.",
                    "/outputNode"));
        } else if (output == null) {
            diagnostics.add(VisualDiagnostic.error("gateway.graphContractTest.outputMissing",
                    "Output node '%s' did not produce an output.".formatted(outputNode),
                    "/outputNode"));
        }

        List<VisualDiagnostic> outputSchemaDiagnostics = output == null
                ? List.of()
                : VisualSchemaValidator.validateValue(contract.outputSchema(), output, "/output");
        diagnostics.addAll(outputSchemaDiagnostics);
        diagnostics.addAll(outputAssertionDiagnostics(safeCase, output));
        diagnostics.addAll(nodeAssertionDiagnostics(safeCase, result));

        boolean outputConforms = output != null && outputSchemaDiagnostics.isEmpty();
        boolean passed = result.isSuccess()
                && outputConforms
                && diagnostics.stream().noneMatch(VisualDiagnostic::error);
        return caseResult(safeCase, passed, result.isSuccess(), outputNode, output, outputConforms,
                mockResource.invocations(), result.statusMap(), diagnostics, assertionCount(safeCase));
    }

    private List<VisualDiagnostic> validateSuiteRequest(GatewayGraphContractTestSuiteRequest request) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (!GatewayGraphContractTestSuiteRequest.SCHEMA_VERSION.equals(request.schemaVersion())) {
            diagnostics.add(VisualDiagnostic.error("gateway.graphContractTest.schemaVersionUnsupported",
                    "Contract test suite schemaVersion '%s' is unsupported; expected '%s'."
                            .formatted(request.schemaVersion(), GatewayGraphContractTestSuiteRequest.SCHEMA_VERSION),
                    "/schemaVersion"));
        }
        if (request.graphName().isBlank()) {
            diagnostics.add(VisualDiagnostic.error("gateway.graphContractTest.graphNameMissing",
                    "graphName is required.", "/graphName"));
        }
        if (request.cases().isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("gateway.graphContractTest.noCases",
                    "At least one table test case is required.", "/cases"));
        }
        return diagnostics;
    }

    private List<VisualDiagnostic> validateCaseHeader(GatewayGraphContractTestCase testCase) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (!GatewayGraphContractTestCase.SCHEMA_VERSION.equals(testCase.schemaVersion())) {
            diagnostics.add(VisualDiagnostic.error("gateway.graphContractTest.caseSchemaVersionUnsupported",
                    "Contract test case schemaVersion '%s' is unsupported; expected '%s'."
                            .formatted(testCase.schemaVersion(), GatewayGraphContractTestCase.SCHEMA_VERSION),
                    "/schemaVersion"));
        }
        for (int i = 0; i < testCase.resourceMocks().size(); i++) {
            GatewayGraphResourceMock mock = testCase.resourceMocks().get(i);
            if (mock.resourceId().isBlank()) {
                diagnostics.add(VisualDiagnostic.error("gateway.graphContractTest.mockResourceIdMissing",
                        "Mock resource row %d must declare resourceId.".formatted(i),
                        "/resourceMocks/%d/resourceId".formatted(i)));
            }
        }
        testCase.nodeAssertions().keySet().stream()
                .filter(String::isBlank)
                .forEach(nodeId -> diagnostics.add(VisualDiagnostic.error(
                        "gateway.graphContractTest.nodeAssertionNodeMissing",
                        "Node assertion blocks must be keyed by a non-blank node id.",
                        "/nodeAssertions")));
        return diagnostics;
    }

    private List<VisualDiagnostic> resourceMockDiagnostics(MockHttpResourceOperator mockResource) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        mockResource.unusedRequiredMocks().forEach(mock -> diagnostics.add(VisualDiagnostic.error(
                "gateway.graphContractTest.mockResourceNotUsed",
                "Required mock resource '%s' with expectedParams %s was not called."
                        .formatted(mock.resourceId(), mock.expectedParams()),
                "/resourceMocks")));
        return diagnostics;
    }

    private String selectedOutputNode(GatewayGraphContract contract,
                                      GatewayGraphContractTestCase testCase,
                                      GraphResult result) {
        if (!testCase.outputNode().isBlank()) {
            return testCase.outputNode();
        }
        return contract.outputNodes().stream()
                .filter(node -> result.findOutput(node, Object.class).isPresent())
                .findFirst()
                .orElse(contract.outputNodes().isEmpty() ? "" : contract.outputNodes().getFirst());
    }

    private static Map<String, Operator<?, ?>> httpResourceOverrides(Graph graph,
                                                                     MockHttpResourceOperator mockResource) {
        return graph.nodes().values().stream()
                .filter(node -> "httpResource".equals(node.operatorRef()))
                .collect(Collectors.toMap(NodeSpec::id, node -> mockResource));
    }

    private List<VisualDiagnostic> outputAssertionDiagnostics(GatewayGraphContractTestCase testCase, Object output) {
        return assertionDiagnostics(testCase.name(), testCase.assertions(), output, "/assertions");
    }

    private List<VisualDiagnostic> nodeAssertionDiagnostics(GatewayGraphContractTestCase testCase,
                                                            GraphResult result) {
        if (testCase.nodeAssertions().isEmpty()) {
            return List.of();
        }
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        testCase.nodeAssertions().forEach((nodeId, assertions) -> {
            String targetPrefix = "/nodeAssertions/" + escapeJsonPointer(nodeId);
            Object nodeOutput = result.findOutput(nodeId, Object.class).orElse(null);
            if (nodeOutput == null) {
                diagnostics.add(VisualDiagnostic.error(
                        "gateway.graphContractTest.nodeOutputMissing",
                        "Contract test case '%s' expected node '%s' to produce output for nodeAssertions."
                                .formatted(testCase.name(), nodeId),
                        targetPrefix));
                return;
            }
            diagnostics.addAll(assertionDiagnostics(
                    "%s node '%s'".formatted(testCase.name(), nodeId),
                    assertions,
                    nodeOutput,
                    targetPrefix));
        });
        return diagnostics;
    }

    private List<VisualDiagnostic> assertionDiagnostics(String subject,
                                                        List<GatewayGraphTestAssertion> assertions,
                                                        Object output,
                                                        String targetPrefix) {
        if (assertions.isEmpty()) {
            return List.of();
        }
        JsonNode outputNode = objectMapper.valueToTree(output);
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        for (int i = 0; i < assertions.size(); i++) {
            GatewayGraphTestAssertion assertion = assertions.get(i);
            String target = "%s/%d".formatted(targetPrefix, i);
            diagnostics.addAll(assertionDiagnostics(subject, assertion, output, outputNode, target));
        }
        return diagnostics;
    }

    private List<VisualDiagnostic> assertionDiagnostics(String subject,
                                                        GatewayGraphTestAssertion assertion,
                                                        Object output,
                                                        JsonNode outputNode,
                                                        String target) {
        if (assertion.mode() == GatewayGraphTestAssertion.Mode.OUTPUT_EQUALS) {
            return jsonEquals(assertion.expectedValue(), output)
                    ? List.of()
                    : List.of(assertionFailed(subject, "output equals expected value", target + "/expectedValue"));
        }
        if (assertion.mode() == GatewayGraphTestAssertion.Mode.OUTPUT_MATCHES_SCHEMA) {
            return schemaAssertionDiagnostics(subject, assertion, output, target + "/expectedValue");
        }
        if (!validJsonPointer(assertion.path())) {
            return List.of(VisualDiagnostic.error("gateway.graphContractTest.assertionInvalidPath",
                    "Contract test case '%s' assertion path '%s' is not a JSON Pointer."
                            .formatted(subject, assertion.path()),
                    target + "/path"));
        }
        JsonNode actualValue = outputNode.at(assertion.path());
        return switch (assertion.mode()) {
            case PATH_EQUALS -> jsonEquals(assertion.expectedValue(), actualValue)
                    ? List.of()
                    : List.of(assertionFailed(subject,
                            "path '%s' equals expected value".formatted(assertion.path()),
                            target + "/expectedValue"));
            case PATH_EXISTS -> actualValue.isMissingNode()
                    ? List.of(assertionFailed(subject,
                            "path '%s' exists".formatted(assertion.path()),
                            target + "/path"))
                    : List.of();
            case PATH_ABSENT -> actualValue.isMissingNode()
                    ? List.of()
                    : List.of(assertionFailed(subject,
                            "path '%s' is absent".formatted(assertion.path()),
                            target + "/path"));
            case OUTPUT_EQUALS, OUTPUT_MATCHES_SCHEMA -> List.of();
        };
    }

    private List<VisualDiagnostic> schemaAssertionDiagnostics(String subject,
                                                              GatewayGraphTestAssertion assertion,
                                                              Object output,
                                                              String target) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        Optional<SchemaEnvelope> schema = assertionSchemaEnvelope(assertion.expectedValue(), target, diagnostics);
        if (diagnostics.stream().anyMatch(VisualDiagnostic::error) || schema.isEmpty()) {
            return diagnostics;
        }
        return VisualSchemaValidator.validateValue(schema.get(), output, target).stream()
                .map(diagnostic -> VisualDiagnostic.error("gateway.graphContractTest.schemaAssertionFailed",
                        "Contract test case '%s' target output does not satisfy assertion schema."
                                .formatted(subject),
                        diagnostic.target()))
                .toList();
    }

    private Optional<SchemaEnvelope> assertionSchemaEnvelope(Object value,
                                                            String target,
                                                            List<VisualDiagnostic> diagnostics) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            diagnostics.add(VisualDiagnostic.error("gateway.graphContractTest.assertionSchemaInvalid",
                    "Schema assertion expectedValue must be a JSON schema object or SchemaEnvelope.",
                    target));
            return Optional.empty();
        }
        try {
            Map<String, Object> schemaMap = stringKeyMap(rawMap);
            SchemaEnvelope envelope = schemaMap.get("schema") instanceof Map<?, ?>
                    ? objectMapper.convertValue(schemaMap, SchemaEnvelope.class)
                    : new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schemaMap);
            diagnostics.addAll(VisualSchemaValidator.validateEnvelope(envelope, target));
            return Optional.of(envelope);
        } catch (IllegalArgumentException ex) {
            diagnostics.add(VisualDiagnostic.error("gateway.graphContractTest.assertionSchemaInvalid",
                    "Schema assertion expectedValue could not be parsed as a visual schema.",
                    target));
            return Optional.empty();
        }
    }

    private VisualDiagnostic assertionFailed(String caseName, String expectation, String target) {
        return VisualDiagnostic.error("gateway.graphContractTest.assertionFailed",
                "Contract test case '%s' assertion failed: expected %s.".formatted(caseName, expectation),
                target);
    }

    private boolean jsonEquals(Object expected, Object actual) {
        JsonNode expectedNode = objectMapper.valueToTree(expected);
        JsonNode actualNode = objectMapper.valueToTree(actual);
        return expectedNode.equals(actualNode);
    }

    private boolean jsonEquals(Object expected, JsonNode actual) {
        JsonNode expectedNode = objectMapper.valueToTree(expected);
        return expectedNode.equals(actual);
    }

    private static boolean validJsonPointer(String path) {
        return path == null || path.isBlank() || path.startsWith("/");
    }

    private static Map<String, Object> stringKeyMap(Map<?, ?> rawMap) {
        Map<String, Object> values = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> values.put(String.valueOf(key), value));
        return values;
    }

    private GatewayGraphContractTestCaseResult caseResult(GatewayGraphContractTestCase testCase,
                                                          boolean passed,
                                                          boolean graphSuccess,
                                                          String outputNode,
                                                          Object output,
                                                          boolean outputConforms,
                                                          List<GatewayGraphResourceInvocation> invocations,
                                                          Map<String, NodeStatus> statusMap,
                                                          List<VisualDiagnostic> diagnostics) {
        return caseResult(testCase, passed, graphSuccess, outputNode, output, outputConforms,
                invocations, statusMap, diagnostics, 0);
    }

    private GatewayGraphContractTestCaseResult caseResult(GatewayGraphContractTestCase testCase,
                                                          boolean passed,
                                                          boolean graphSuccess,
                                                          String outputNode,
                                                          Object output,
                                                          boolean outputConforms,
                                                          List<GatewayGraphResourceInvocation> invocations,
                                                          Map<String, NodeStatus> statusMap,
                                                          List<VisualDiagnostic> diagnostics,
                                                          int assertionCount) {
        return new GatewayGraphContractTestCaseResult(testCase.name(), passed, graphSuccess, outputNode, output,
                outputConforms, invocations, statusMap, diagnostics, assertionCount);
    }

    private GatewayGraphContractTestSuiteResult suiteResult(
            String graphName,
            List<GatewayGraphContractTestCaseResult> results,
            List<VisualDiagnostic> diagnostics) {
        int totalCases = results.size();
        int passedCases = (int) results.stream().filter(GatewayGraphContractTestCaseResult::passed).count();
        int failedCases = totalCases - passedCases;
        int inputSchemaValidated = (int) results.stream()
                .filter(result -> !hasDiagnostic(result.diagnostics(), "visual.context.schemaMismatch"))
                .count();
        int outputSchemaValidated = (int) results.stream()
                .filter(GatewayGraphContractTestCaseResult::outputConformsToSchema)
                .count();
        int mockedCalls = results.stream()
                .mapToInt(result -> result.mockedResourceInvocations().size())
                .sum();
        int assertions = results.stream()
                .mapToInt(GatewayGraphContractTestCaseResult::assertionCount)
                .sum();
        boolean passed = diagnostics.stream().noneMatch(VisualDiagnostic::error)
                && totalCases > 0
                && failedCases == 0;
        return new GatewayGraphContractTestSuiteResult(
                GatewayGraphContractTestSuiteResult.SCHEMA_VERSION,
                graphName,
                passed,
                totalCases,
                passedCases,
                failedCases,
                new GatewayGraphContractTestSuiteResult.Coverage(
                        inputSchemaValidated,
                        outputSchemaValidated,
                        mockedCalls,
                        assertions),
                results,
                diagnostics);
    }

    private static boolean hasDiagnostic(List<VisualDiagnostic> diagnostics, String code) {
        return diagnostics != null
                && diagnostics.stream().anyMatch(diagnostic -> diagnostic != null && code.equals(diagnostic.code()));
    }

    private static int assertionCount(GatewayGraphContractTestCase testCase) {
        int outputAssertions = testCase.assertions().size();
        int nodeAssertions = testCase.nodeAssertions().values().stream()
                .mapToInt(List::size)
                .sum();
        return outputAssertions + nodeAssertions;
    }

    private static String escapeJsonPointer(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    private static class MockHttpResourceOperator implements Operator<Object, HttpResourceOutput> {

        private final List<GatewayGraphResourceMock> mocks;
        private final boolean[] consumed;
        private final List<GatewayGraphResourceInvocation> invocations = new ArrayList<>();

        MockHttpResourceOperator(List<GatewayGraphResourceMock> mocks) {
            this.mocks = mocks == null ? List.of() : List.copyOf(mocks);
            this.consumed = new boolean[this.mocks.size()];
        }

        @Override
        public synchronized HttpResourceOutput execute(Object input, OperatorContext ctx) {
            ResourceCall call = ResourceCall.from(input);
            Match match = match(call);
            invocations.add(new GatewayGraphResourceInvocation(call.resourceId(), call.params(), match.matched()));
            if (!match.matched()) {
                throw new IllegalArgumentException("No mock resource row matched resourceId '%s' and params %s."
                        .formatted(call.resourceId(), call.params()));
            }
            GatewayGraphResourceMock mock = mocks.get(match.index());
            consumed[match.index()] = true;
            return new HttpResourceOutput(
                    call.resourceId(),
                    mock.statusCode(),
                    mock.payload(),
                    mock.rawBody(),
                    Duration.ofMillis(mock.durationMs()),
                    mock.success());
        }

        synchronized List<GatewayGraphResourceInvocation> invocations() {
            return List.copyOf(invocations);
        }

        synchronized List<GatewayGraphResourceMock> unusedRequiredMocks() {
            List<GatewayGraphResourceMock> unused = new ArrayList<>();
            for (int i = 0; i < mocks.size(); i++) {
                if (!consumed[i] && mocks.get(i).required()) {
                    unused.add(mocks.get(i));
                }
            }
            return unused;
        }

        private Match match(ResourceCall call) {
            for (int i = 0; i < mocks.size(); i++) {
                if (consumed[i]) {
                    continue;
                }
                GatewayGraphResourceMock mock = mocks.get(i);
                if (!mock.resourceId().equals(call.resourceId())) {
                    continue;
                }
                if (mock.expectedParams().isEmpty() || Objects.equals(mock.expectedParams(), call.params())) {
                    return new Match(i, true);
                }
            }
            return new Match(-1, false);
        }
    }

    private record Match(int index, boolean matched) {}

    private record ResourceCall(String resourceId, Map<String, Object> params) {

        static ResourceCall from(Object input) {
            if (input instanceof HttpResourceInput typed) {
                return new ResourceCall(typed.resourceId(), typed.params() == null ? Map.of() : typed.params());
            }
            if (input instanceof Map<?, ?> map) {
                return new ResourceCall(requiredString(map, "resourceId"), objectMap(map.get("params")));
            }
            throw new IllegalArgumentException("Mock httpResource input must be a map, but was "
                    + (input == null ? "null" : input.getClass().getName()));
        }

        private static String requiredString(Map<?, ?> map, String key) {
            Object value = map.get(key);
            String text = value == null ? "" : String.valueOf(value).trim();
            if (text.isBlank()) {
                throw new IllegalArgumentException("Mock httpResource input missing required field: " + key);
            }
            return text;
        }

        private static Map<String, Object> objectMap(Object raw) {
            if (raw == null) {
                return Map.of();
            }
            if (!(raw instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("Mock httpResource params must be a map.");
            }
            Map<String, Object> values = new LinkedHashMap<>();
            map.forEach((key, value) -> values.put(String.valueOf(key), value));
            return values;
        }
    }
}
