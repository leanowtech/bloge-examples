package com.leanowtech.bloge.gateway.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.context.NodeResults;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.model.NodeSpec;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.operator.HttpResourceInput;
import com.leanowtech.bloge.gateway.operator.HttpResourceOutput;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.GraphExecutionTargetSnapshot;
import com.leanowtech.bloge.gateway.testing.runtime.ResourceFixtureRuntime;
import com.leanowtech.bloge.gateway.testing.runtime.TestExecutionRequest;
import com.leanowtech.bloge.gateway.testing.runtime.TestExecutionResult;
import com.leanowtech.bloge.gateway.testing.runtime.TestRunService;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContractRegistry;
import com.leanowtech.bloge.gateway.visual.simulation.JsonSchemaSampleGenerator;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runs table-driven, schema-gated contract tests for built-in resource gateway graphs.
 *
 * <p>The production graph artifact executes through the unified execution-data-control kernel on
 * a fresh, run-scoped BLOGE engine. Descriptor-backed {@code httpResource} calls are replaced by
 * schema-gated fixture rules, while decision-table, transform, branch, retry, and fallback logic
 * remains real. The isolated engine deliberately shares no application interceptor, listener,
 * durable-store, cache, quota, or circuit-breaker state.</p>
 */
@Service
public class GatewayGraphContractTestService {

    private final GatewayGraphService graphService;
    private final ObjectMapper objectMapper;
    private final JsonSchemaSampleGenerator sampleGenerator;
    private final ResourceDesignContractRegistry resourceContracts;
    private final OperatorRegistry operatorRegistry;
    private final ResourceRegistry resourceRegistry;
    private final BlgeExpressionEvaluator expressionEvaluator;

    /**
     * @param graphService resource graph service
     * @param objectMapper JSON mapper for assertion evaluation
     */
    public GatewayGraphContractTestService(GatewayGraphService graphService, ObjectMapper objectMapper) {
        this(graphService, objectMapper, new JsonSchemaSampleGenerator(), null, null, null);
    }

    /**
     * @param graphService resource graph service
     * @param objectMapper JSON mapper for assertion evaluation
     * @param sampleGenerator deterministic JSON Schema sample generator
     * @param resourceContracts resource design contract registry used for mock payload drafts
     */
    public GatewayGraphContractTestService(GatewayGraphService graphService,
                                           ObjectMapper objectMapper,
                                           JsonSchemaSampleGenerator sampleGenerator,
                                           ResourceDesignContractRegistry resourceContracts) {
        this(graphService, objectMapper, sampleGenerator, resourceContracts, null, null);
    }

    /**
     * Creates the production adapter over the shared execution data-control kernel.
     *
     * @param graphService graph catalog and compatibility output resolver
     * @param objectMapper JSON mapper
     * @param sampleGenerator schema-based draft generator
     * @param resourceContracts authoring-time resource contracts
     * @param resourceRegistry executable resource descriptor registry
     * @param expressionEvaluator BLOGE expression evaluator used by F2/F3 resource fixtures
     */
    @Autowired
    public GatewayGraphContractTestService(GatewayGraphService graphService,
                                           ObjectMapper objectMapper,
                                           JsonSchemaSampleGenerator sampleGenerator,
                                           ResourceDesignContractRegistry resourceContracts,
                                           ResourceRegistry resourceRegistry,
                                           BlgeExpressionEvaluator expressionEvaluator) {
        this.graphService = graphService;
        this.objectMapper = objectMapper;
        this.sampleGenerator = sampleGenerator == null ? new JsonSchemaSampleGenerator() : sampleGenerator;
        this.resourceContracts = resourceContracts;
        this.operatorRegistry = graphService.engine().operatorRegistry().orElseThrow(() ->
                new IllegalStateException("Graph engine does not expose its operator registry."));
        this.resourceRegistry = resourceRegistry;
        this.expressionEvaluator = expressionEvaluator;
    }

    /**
     * Runs a resource graph contract-test suite.
     *
     * @param request suite request
     * @return suite result
     */
    public GatewayGraphContractTestSuiteResult run(GatewayGraphContractTestSuiteRequest request) {
        return run(request, GatewayGraphContractTestCoveragePolicy.none(),
                TestExecutionRequest.FixtureSource.INLINE);
    }

    /**
     * Runs a stored resource graph contract-test suite.
     *
     * @param suite stored suite
     * @return suite result
     */
    public GatewayGraphContractTestSuiteResult run(GatewayGraphContractTestSuite suite) {
        GatewayGraphContractTestSuite safeSuite = suite == null
                ? new GatewayGraphContractTestSuite("", "", "", List.of(),
                        new GatewayGraphContractTestSuiteRequest("", List.of()),
                        GatewayGraphContractTestCoveragePolicy.none())
                : suite;
        return run(safeSuite.request(), safeSuite.coveragePolicy(),
                TestExecutionRequest.FixtureSource.STORED);
    }

    /**
     * Runs all selected stored suites and returns an aggregate result.
     *
     * @param suites suites to run
     * @return batch result
     */
    public GatewayGraphContractTestBatchResult runAll(Collection<GatewayGraphContractTestSuite> suites) {
        Collection<GatewayGraphContractTestSuite> safeSuites = suites == null ? List.of() : suites;
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (safeSuites.isEmpty()) {
            diagnostics.add(VisualDiagnostic.error(
                    "gateway.graphContractTest.batchNoSuites",
                    "At least one stored contract-test suite is required for a batch run.",
                    "/suites"));
        }

        List<GatewayGraphContractTestSuiteRunResult> results = new ArrayList<>();
        for (GatewayGraphContractTestSuite suite : safeSuites) {
            GatewayGraphContractTestSuite safeSuite = suite == null
                    ? new GatewayGraphContractTestSuite("", "", "", List.of(),
                            new GatewayGraphContractTestSuiteRequest("", List.of()),
                            GatewayGraphContractTestCoveragePolicy.none())
                    : suite;
            GatewayGraphContractTestSuiteResult result = run(safeSuite);
            results.add(new GatewayGraphContractTestSuiteRunResult(
                    safeSuite.suiteId(),
                    safeSuite.displayName(),
                    safeSuite.request().graphName(),
                    safeSuite.tags(),
                    result));
        }

        int totalSuites = results.size();
        int passedSuites = (int) results.stream()
                .filter(row -> row.result() != null && row.result().passed())
                .count();
        int failedSuites = totalSuites - passedSuites;
        int totalCases = results.stream()
                .map(GatewayGraphContractTestSuiteRunResult::result)
                .filter(Objects::nonNull)
                .mapToInt(GatewayGraphContractTestSuiteResult::totalCases)
                .sum();
        int passedCases = results.stream()
                .map(GatewayGraphContractTestSuiteRunResult::result)
                .filter(Objects::nonNull)
                .mapToInt(GatewayGraphContractTestSuiteResult::passedCases)
                .sum();
        int failedCases = results.stream()
                .map(GatewayGraphContractTestSuiteRunResult::result)
                .filter(Objects::nonNull)
                .mapToInt(GatewayGraphContractTestSuiteResult::failedCases)
                .sum();
        GatewayGraphContractTestSuiteResult.Coverage coverage = aggregateCoverage(results);
        boolean passed = diagnostics.stream().noneMatch(VisualDiagnostic::error)
                && totalSuites > 0
                && failedSuites == 0;
        return new GatewayGraphContractTestBatchResult(
                GatewayGraphContractTestBatchResult.SCHEMA_VERSION,
                passed,
                totalSuites,
                passedSuites,
                failedSuites,
                totalCases,
                passedCases,
                failedCases,
                coverage,
                results,
                diagnostics);
    }

    /**
     * Generates an editable graph contract-test suite from formal graph/resource schemas.
     *
     * @param request draft request
     * @return generated draft
     */
    public GatewayGraphContractTestDraftResponse draft(GatewayGraphContractTestDraftRequest request) {
        GatewayGraphContractTestDraftRequest safeRequest = request == null
                ? new GatewayGraphContractTestDraftRequest("", "", "", Map.of(), Map.of())
                : request;
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (!GatewayGraphContractTestDraftRequest.SCHEMA_VERSION.equals(safeRequest.schemaVersion())) {
            diagnostics.add(VisualDiagnostic.error("gateway.graphContractTest.draftSchemaVersionUnsupported",
                    "Contract-test draft schemaVersion '%s' is unsupported; expected '%s'."
                            .formatted(safeRequest.schemaVersion(),
                                    GatewayGraphContractTestDraftRequest.SCHEMA_VERSION),
                    "/schemaVersion"));
        }
        if (safeRequest.graphName().isBlank()) {
            diagnostics.add(VisualDiagnostic.error("gateway.graphContractTest.graphNameMissing",
                    "graphName is required.", "/graphName"));
        }
        if (diagnostics.stream().anyMatch(VisualDiagnostic::error)) {
            return new GatewayGraphContractTestDraftResponse(
                    safeRequest.graphName(),
                    null,
                    new GatewayGraphContractTestSuiteRequest(safeRequest.graphName(), List.of()),
                    diagnostics);
        }

        Graph graph;
        GatewayGraphContract contract;
        try {
            graph = graphService.requireGraph(safeRequest.graphName());
            contract = graphService.requireContract(safeRequest.graphName());
        } catch (IllegalArgumentException ex) {
            diagnostics.add(VisualDiagnostic.error("gateway.graphContractTest.graphUnknown",
                    ex.getMessage(), "/graphName"));
            return new GatewayGraphContractTestDraftResponse(
                    safeRequest.graphName(),
                    null,
                    new GatewayGraphContractTestSuiteRequest(safeRequest.graphName(), List.of()),
                    diagnostics);
        }

        Map<String, Object> context = generatedContext(contract, safeRequest.contextOverrides());
        List<GatewayGraphResourceMock> mocks = generatedResourceMocks(graph, context,
                safeRequest.resourcePayloadOverrides(), diagnostics);
        GatewayGraphContractTestCase testCase = new GatewayGraphContractTestCase(
                safeRequest.caseName(),
                context,
                mocks,
                safeRequest.outputNode(),
                List.of(new GatewayGraphTestAssertion(
                        GatewayGraphTestAssertion.Mode.OUTPUT_MATCHES_SCHEMA,
                        "",
                        contract.outputSchema().schema())));
        return new GatewayGraphContractTestDraftResponse(
                contract.graphName(),
                contract,
                new GatewayGraphContractTestSuiteRequest(contract.graphName(), List.of(testCase)),
                diagnostics);
    }

    private GatewayGraphContractTestSuiteResult run(GatewayGraphContractTestSuiteRequest request,
                                                    GatewayGraphContractTestCoveragePolicy coveragePolicy,
                                                    TestExecutionRequest.FixtureSource fixtureSource) {
        GatewayGraphContractTestSuiteRequest safeRequest = request == null
                ? new GatewayGraphContractTestSuiteRequest("", List.of())
                : request;
        List<VisualDiagnostic> suiteDiagnostics = validateSuiteRequest(safeRequest);
        if (!suiteDiagnostics.isEmpty()) {
            return suiteResult(safeRequest.graphName(), List.of(), suiteDiagnostics, coveragePolicy);
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
                    "/graphName")), coveragePolicy);
        }

        List<GatewayGraphContractTestCaseResult> results = new ArrayList<>();
        for (GatewayGraphContractTestCase testCase : safeRequest.cases()) {
            results.add(runCase(graph, contract, testCase, fixtureSource));
        }
        return suiteResult(contract.graphName(), results, List.of(), coveragePolicy);
    }

    private GatewayGraphContractTestCaseResult runCase(Graph graph,
                                                       GatewayGraphContract contract,
                                                       GatewayGraphContractTestCase testCase,
                                                       TestExecutionRequest.FixtureSource fixtureSource) {
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

        GraphExecutionTargetSnapshot target = targetSnapshot(graph);
        FixtureBundle fixtureBundle = contractFixtureBundle(safeCase, contract, target.fingerprint());
        TestRunService runService = runService(target);
        TestExecutionResult execution = runService.execute(new TestExecutionRequest(
                graph,
                new GraphContext(safeCase.context()),
                fixtureBundle,
                "GRAPH_CONTRACT_TEST",
                target.fingerprint(),
                fixtureSource,
                Map.of("adapter", "GatewayGraphContractTestService", "caseName", safeCase.name()),
                target.certificationEligible()));
        GraphResult result = execution.graphResult();
        List<GatewayGraphResourceInvocation> invocations = resourceInvocations(execution.evidence());
        diagnostics.addAll(resourceMockDiagnostics(safeCase.resourceMocks(), execution.evidence()));
        diagnostics.addAll(executionEvidenceDiagnostics(execution.evidence()));
        if (result == null) {
            diagnostics.add(VisualDiagnostic.error("gateway.graphContractTest.graphExecutionException",
                    "Contract-test graph execution failed before producing a result: %s"
                            .formatted(String.join("; ", execution.evidence().diagnostics())),
                    "/graph"));
            return caseResult(safeCase, false, false, "", null, false,
                    invocations, Map.of(), diagnostics, 0,
                    GatewayGraphContractTestCaseResult.EvidenceSummary.from(execution.evidence()));
        }

        if (!result.isSuccess()) {
            result.errors().forEach(error -> diagnostics.add(VisualDiagnostic.error(
                    "gateway.graphContractTest.graphExecutionFailed",
                    "%s: %s".formatted(error.nodeId(), error.exception().getMessage()),
                    "/graph")));
        }

        GatewayGraphOutput terminalOutput = safeCase.outputNode().isBlank()
                ? graphService.resolveOutput(contract.graphName(), result)
                : graphService.resolveOutput(contract.graphName(), result, safeCase.outputNode());
        String outputNode = terminalOutput.outputNode();
        Object output = terminalOutput.output();
        List<VisualDiagnostic> outputDiagnostics = terminalOutput.diagnostics();
        diagnostics.addAll(outputDiagnostics);
        diagnostics.addAll(outputAssertionDiagnostics(safeCase, output));
        diagnostics.addAll(nodeAssertionDiagnostics(safeCase, result));

        boolean outputConforms = terminalOutput.valid();
        boolean passed = result.isSuccess()
                && outputConforms
                && diagnostics.stream().noneMatch(VisualDiagnostic::error);
        return caseResult(safeCase, passed, result.isSuccess(), outputNode, output, outputConforms,
                invocations, result.statusMap(), diagnostics, assertionCount(safeCase),
                GatewayGraphContractTestCaseResult.EvidenceSummary.from(execution.evidence()));
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

    private List<VisualDiagnostic> resourceMockDiagnostics(List<GatewayGraphResourceMock> mocks,
                                                           TestRunEvidence evidence) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        Map<String, TestRunEvidence.FixtureConsumption> consumptions = evidence.fixtureConsumptions().stream()
                .collect(Collectors.toMap(TestRunEvidence.FixtureConsumption::ruleId, item -> item));
        for (int i = 0; i < mocks.size(); i++) {
            GatewayGraphResourceMock mock = mocks.get(i);
            TestRunEvidence.FixtureConsumption consumption = consumptions.get("resource-mock-" + i);
            if (mock.required() && (consumption == null || consumption.uses() == 0)) {
                diagnostics.add(VisualDiagnostic.error(
                        "gateway.graphContractTest.mockResourceNotUsed",
                        "Required mock resource '%s' with expectedParams %s was not called."
                                .formatted(mock.resourceId(), mock.expectedParams()),
                        "/resourceMocks"));
            }
        }
        return diagnostics;
    }

    private FixtureBundle contractFixtureBundle(GatewayGraphContractTestCase testCase,
                                                GatewayGraphContract contract,
                                                String targetFingerprint) {
        List<FixtureRule> rules = new ArrayList<>();
        for (int i = 0; i < testCase.resourceMocks().size(); i++) {
            GatewayGraphResourceMock mock = testCase.resourceMocks().get(i);
            FixtureRule.Selector selector = FixtureRule.Selector.resource(mock.resourceId());
            if (!mock.expectedParams().isEmpty()) {
                selector = selector.matching(FixtureRule.Match.pathEquals("/params", mock.expectedParams()));
            }
            FixtureRule.Behavior behavior = resourceBehavior(mock);
            FixtureRule.Consumption consumption = new FixtureRule.Consumption(
                    mock.required(), mock.minUses(), mock.maxUses(),
                    FixtureRule.ExhaustedAction.FAIL, FixtureRule.UnmatchedAction.FAIL);
            rules.add(new FixtureRule(FixtureRule.SCHEMA_VERSION, "resource-mock-" + i,
                    selector, behavior, consumption, FixtureRule.SchemaCheck.strict()));
        }
        return new FixtureBundle(FixtureBundle.SCHEMA_VERSION,
                "gateway-contract:" + testCase.name(), 1, targetFingerprint, "INTERNAL",
                null, null, rules, kernelAssertions(testCase, contract),
                Map.of("adapter", "gateway-graph-contract-v1"));
    }

    private FixtureRule.Behavior resourceBehavior(GatewayGraphResourceMock mock) {
        if (mock.fixtureMode() == GatewayGraphResourceMock.FixtureMode.OUTPUT_LEVEL) {
            return FixtureRule.Behavior.returning(new HttpResourceOutput(
                    mock.resourceId(), mock.statusCode(), mock.payload(), mock.rawBody(),
                    Duration.ofMillis(mock.durationMs()), mock.success()));
        }
        FixtureRule.DoubleBoundary boundary = mock.fixtureMode()
                == GatewayGraphResourceMock.FixtureMode.TRANSPORT_LEVEL
                ? FixtureRule.DoubleBoundary.TRANSPORT
                : FixtureRule.DoubleBoundary.NODE;
        return FixtureRule.Behavior.protocolResponse(mock.rawBody(), mock.statusCode(),
                mock.responseHeaders(), boundary);
    }

    private List<FixtureBundle.Assertion> kernelAssertions(GatewayGraphContractTestCase testCase,
                                                           GatewayGraphContract contract) {
        List<FixtureBundle.Assertion> assertions = new ArrayList<>();
        String outputNode = testCase.outputNode().isBlank()
                ? contract.outputNodes().stream().findFirst().orElse("")
                : testCase.outputNode();
        assertions.add(new FixtureBundle.Assertion("OUTPUT_PATH", outputNode, "", "MATCHES_SCHEMA",
                contract.outputSchema().schema(), null));
        testCase.assertions().forEach(assertion -> assertions.add(kernelAssertion(
                "OUTPUT_PATH", outputNode, assertion)));
        testCase.nodeAssertions().forEach((nodeId, values) -> values.forEach(assertion ->
                assertions.add(kernelAssertion("NODE_OUTPUT", nodeId, assertion))));
        return List.copyOf(assertions);
    }

    private static FixtureBundle.Assertion kernelAssertion(String scope, String nodeId,
                                                            GatewayGraphTestAssertion assertion) {
        return switch (assertion.mode()) {
            case OUTPUT_EQUALS -> new FixtureBundle.Assertion(
                    scope, nodeId, "", "EQUALS", assertion.expectedValue(), null);
            case OUTPUT_MATCHES_SCHEMA -> new FixtureBundle.Assertion(
                    scope, nodeId, "", "MATCHES_SCHEMA", assertion.expectedValue(), null);
            case PATH_EQUALS -> new FixtureBundle.Assertion(
                    scope, nodeId, assertion.path(), "EQUALS", assertion.expectedValue(), null);
            case PATH_EXISTS -> new FixtureBundle.Assertion(
                    scope, nodeId, assertion.path(), "EXISTS", null, null);
            case PATH_ABSENT -> new FixtureBundle.Assertion(
                    scope, nodeId, assertion.path(), "ABSENT", null, null);
        };
    }

    private GraphExecutionTargetSnapshot targetSnapshot(Graph graph) {
        return GraphExecutionTargetSnapshot.capture(objectMapper, graph, resourceRegistry);
    }

    private TestRunService runService(GraphExecutionTargetSnapshot target) {
        ResourceFixtureRuntime runtime = expressionEvaluator == null
                ? null : new ResourceFixtureRuntime(target.resourceRegistry(), expressionEvaluator, objectMapper);
        return new TestRunService(operatorRegistry, objectMapper, runtime);
    }

    private static List<VisualDiagnostic> executionEvidenceDiagnostics(TestRunEvidence evidence) {
        if (evidence == null || evidence.status() == TestRunEvidence.Status.PASSED) {
            return List.of();
        }
        String detail = evidence.diagnostics().isEmpty()
                ? evidence.status().name()
                : evidence.status() + ": " + boundedDiagnostics(evidence.diagnostics());
        return List.of(VisualDiagnostic.error(
                "gateway.graphContractTest.controlledExecutionFailed",
                "Controlled test execution did not pass: " + detail,
                "/evidence/status"));
    }

    private static String boundedDiagnostics(List<String> diagnostics) {
        String detail = diagnostics.stream().limit(10).collect(Collectors.joining("; "));
        if (detail.length() <= 2_000) {
            return detail;
        }
        return detail.substring(0, 1_997) + "...";
    }

    private static List<GatewayGraphResourceInvocation> resourceInvocations(TestRunEvidence evidence) {
        List<GatewayGraphResourceInvocation> invocations = new ArrayList<>();
        evidence.nodeTrace().stream()
                .filter(trace -> "httpResource".equals(trace.operatorRef()))
                .forEach(trace -> {
                    try {
                        ResourceCall call = ResourceCall.from(trace.input());
                        invocations.add(new GatewayGraphResourceInvocation(call.resourceId(), call.params(),
                                "MOCKED".equals(trace.status()) && trace.errorCode().isBlank()));
                    } catch (RuntimeException ignored) {
                        // The unified evidence retains the malformed input; the compatibility view cannot project it.
                    }
                });
        return List.copyOf(invocations);
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
                invocations, statusMap, diagnostics, 0,
                GatewayGraphContractTestCaseResult.EvidenceSummary.empty());
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
                                                          int assertionCount,
                                                          GatewayGraphContractTestCaseResult.EvidenceSummary evidence) {
        return new GatewayGraphContractTestCaseResult(testCase.name(), passed, graphSuccess, outputNode, output,
                outputConforms, invocations, statusMap, diagnostics, assertionCount, evidence);
    }

    private GatewayGraphContractTestSuiteResult suiteResult(
            String graphName,
            List<GatewayGraphContractTestCaseResult> results,
            List<VisualDiagnostic> diagnostics) {
        return suiteResult(graphName, results, diagnostics, GatewayGraphContractTestCoveragePolicy.none());
    }

    private GatewayGraphContractTestSuiteResult suiteResult(
            String graphName,
            List<GatewayGraphContractTestCaseResult> results,
            List<VisualDiagnostic> diagnostics,
            GatewayGraphContractTestCoveragePolicy coveragePolicy) {
        int totalCases = results.size();
        int passedCases = (int) results.stream().filter(GatewayGraphContractTestCaseResult::passed).count();
        int failedCases = totalCases - passedCases;
        int inputSchemaValidated = (int) results.stream()
                .filter(result -> !hasContextSchemaError(result.diagnostics()))
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
        GatewayGraphContractTestSuiteResult.Coverage coverage = new GatewayGraphContractTestSuiteResult.Coverage(
                inputSchemaValidated,
                outputSchemaValidated,
                mockedCalls,
                assertions);
        GatewayGraphContractTestPolicyResult policyResult = evaluateCoveragePolicy(
                coveragePolicy,
                totalCases,
                results,
                coverage);
        boolean passed = diagnostics.stream().noneMatch(VisualDiagnostic::error)
                && totalCases > 0
                && failedCases == 0
                && policyResult.passed();
        return new GatewayGraphContractTestSuiteResult(
                GatewayGraphContractTestSuiteResult.SCHEMA_VERSION,
                graphName,
                passed,
                totalCases,
                passedCases,
                failedCases,
                coverage,
                policyResult,
                results,
                diagnostics);
    }

    private GatewayGraphContractTestPolicyResult evaluateCoveragePolicy(
            GatewayGraphContractTestCoveragePolicy policy,
            int totalCases,
            List<GatewayGraphContractTestCaseResult> results,
            GatewayGraphContractTestSuiteResult.Coverage coverage) {
        GatewayGraphContractTestCoveragePolicy safePolicy = policy == null
                ? GatewayGraphContractTestCoveragePolicy.none()
                : policy;
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        addMinDiagnostic(diagnostics, "cases", totalCases, safePolicy.minCases(), "/coverage/minCases");
        addMinDiagnostic(diagnostics, "input schema validations", coverage.inputSchemaValidated(),
                safePolicy.minInputSchemaValidated(), "/coverage/minInputSchemaValidated");
        addMinDiagnostic(diagnostics, "output schema validations", coverage.contractOutputSchemaValidated(),
                safePolicy.minContractOutputSchemaValidated(), "/coverage/minContractOutputSchemaValidated");
        addMinDiagnostic(diagnostics, "mocked resource calls", coverage.mockedResourceCalls(),
                safePolicy.minMockedResourceCalls(), "/coverage/minMockedResourceCalls");
        addMinDiagnostic(diagnostics, "assertions", coverage.assertionCount(),
                safePolicy.minAssertionCount(), "/coverage/minAssertionCount");

        Set<String> passingOutputNodes = results.stream()
                .filter(GatewayGraphContractTestCaseResult::passed)
                .map(GatewayGraphContractTestCaseResult::outputNode)
                .filter(outputNode -> outputNode != null && !outputNode.isBlank())
                .collect(Collectors.toSet());
        for (String requiredOutputNode : safePolicy.requiredOutputNodes()) {
            if (!passingOutputNodes.contains(requiredOutputNode)) {
                diagnostics.add(VisualDiagnostic.error(
                        "gateway.graphContractTest.coveragePolicyFailed",
                        "Coverage policy expected at least one passing case for output node '%s'."
                                .formatted(requiredOutputNode),
                        "/coverage/requiredOutputNodes"));
            }
        }
        return diagnostics.isEmpty()
                ? GatewayGraphContractTestPolicyResult.passing()
                : new GatewayGraphContractTestPolicyResult(false, diagnostics);
    }

    private static void addMinDiagnostic(List<VisualDiagnostic> diagnostics,
                                         String label,
                                         int actual,
                                         int expected,
                                         String target) {
        if (expected <= 0 || actual >= expected) {
            return;
        }
        diagnostics.add(VisualDiagnostic.error(
                "gateway.graphContractTest.coveragePolicyFailed",
                "Coverage policy expected at least %d %s but observed %d."
                        .formatted(expected, label, actual),
                target));
    }

    private static GatewayGraphContractTestSuiteResult.Coverage aggregateCoverage(
            List<GatewayGraphContractTestSuiteRunResult> results) {
        int input = 0;
        int output = 0;
        int mocked = 0;
        int assertions = 0;
        for (GatewayGraphContractTestSuiteRunResult row : results) {
            if (row.result() == null || row.result().coverage() == null) {
                continue;
            }
            input += row.result().coverage().inputSchemaValidated();
            output += row.result().coverage().contractOutputSchemaValidated();
            mocked += row.result().coverage().mockedResourceCalls();
            assertions += row.result().coverage().assertionCount();
        }
        return new GatewayGraphContractTestSuiteResult.Coverage(input, output, mocked, assertions);
    }

    private static boolean hasContextSchemaError(List<VisualDiagnostic> diagnostics) {
        return diagnostics != null
                && diagnostics.stream().anyMatch(diagnostic -> diagnostic != null
                        && diagnostic.error()
                        && diagnostic.target().startsWith("/context"));
    }

    private static int assertionCount(GatewayGraphContractTestCase testCase) {
        int outputAssertions = testCase.assertions().size();
        int nodeAssertions = testCase.nodeAssertions().values().stream()
                .mapToInt(List::size)
                .sum();
        return outputAssertions + nodeAssertions;
    }

    private Map<String, Object> generatedContext(GatewayGraphContract contract, Map<String, Object> overrides) {
        Object generated = sampleGenerator.generate(contract.inputSchema());
        Map<String, Object> context = generated instanceof Map<?, ?> map ? stringKeyMap(map) : new LinkedHashMap<>();
        overrides.forEach(context::put);
        return context;
    }

    private List<GatewayGraphResourceMock> generatedResourceMocks(Graph graph,
                                                                  Map<String, Object> context,
                                                                  Map<String, Object> payloadOverrides,
                                                                  List<VisualDiagnostic> diagnostics) {
        List<GatewayGraphResourceMock> mocks = new ArrayList<>();
        NodeResults emptyResults = new NodeResults();
        GraphContext graphContext = new GraphContext(context);
        for (NodeSpec node : orderedHttpResourceNodes(graph)) {
            String target = "/resourceMocks/" + mocks.size();
            ResourceCall call;
            try {
                Object input = node.inputAssembler() == null
                        ? Map.of()
                        : node.inputAssembler().assemble(emptyResults, graphContext);
                call = ResourceCall.from(input);
            } catch (RuntimeException ex) {
                diagnostics.add(VisualDiagnostic.warning(
                        "gateway.graphContractTest.mockDraftInputUnresolved",
                        "Could not generate mock row for httpResource node '%s': %s"
                                .formatted(node.id(), ex.getMessage()),
                        target));
                continue;
            }
            if (call.resourceId().isBlank()) {
                diagnostics.add(VisualDiagnostic.warning(
                        "gateway.graphContractTest.mockDraftResourceUnknown",
                        "Could not resolve resourceId for httpResource node '%s'."
                                .formatted(node.id()),
                        target + "/resourceId"));
                continue;
            }
            Object payload = payloadFor(call.resourceId(), payloadOverrides, diagnostics, target + "/payload");
            mocks.add(new GatewayGraphResourceMock(
                    call.resourceId(),
                    call.params(),
                    payload,
                    200,
                    rawJson(payload),
                    0,
                    true,
                    false));
        }
        return mocks;
    }

    private Object payloadFor(String resourceId,
                              Map<String, Object> payloadOverrides,
                              List<VisualDiagnostic> diagnostics,
                              String target) {
        if (payloadOverrides.containsKey(resourceId)) {
            return payloadOverrides.get(resourceId);
        }
        Optional<ResourceDesignContract> contract = resourceContracts == null
                ? Optional.empty()
                : resourceContracts.findByResourceId(resourceId);
        if (contract.isEmpty()) {
            diagnostics.add(VisualDiagnostic.warning(
                    "gateway.graphContractTest.resourceContractMissing",
                    "No resource design contract found for '%s'; generated mock payload is empty."
                            .formatted(resourceId),
                    target));
            return Map.of();
        }
        Object generated = sampleGenerator.generate(contract.get().responseSchema());
        return generated == null ? Map.of() : generated;
    }

    private List<NodeSpec> orderedHttpResourceNodes(Graph graph) {
        Map<String, NodeSpec> nodes = graph.nodes();
        List<NodeSpec> ordered = new ArrayList<>();
        graph.topologicalOrder().stream()
                .map(nodes::get)
                .filter(Objects::nonNull)
                .filter(node -> "httpResource".equals(node.operatorRef()))
                .forEach(ordered::add);
        nodes.values().stream()
                .filter(node -> "httpResource".equals(node.operatorRef()))
                .filter(node -> !ordered.contains(node))
                .sorted((left, right) -> left.id().compareTo(right.id()))
                .forEach(ordered::add);
        return ordered;
    }

    private String rawJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (RuntimeException ex) {
            return "";
        } catch (java.io.IOException ex) {
            return "";
        }
    }

    private static String escapeJsonPointer(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

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
