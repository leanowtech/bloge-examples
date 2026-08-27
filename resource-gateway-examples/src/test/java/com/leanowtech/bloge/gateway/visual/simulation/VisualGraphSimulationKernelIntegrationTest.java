package com.leanowtech.bloge.gateway.visual.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.DefaultVisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.runtime.VisualDslRunRequest;
import com.leanowtech.bloge.gateway.visual.runtime.VisualDslRunResponse;
import com.leanowtech.bloge.gateway.visual.runtime.VisualDslRunner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualDslRunnerFactory;
import com.leanowtech.bloge.gateway.visual.runtime.VisualSimulationExecutor;
import com.leanowtech.bloge.gateway.visualadapter.DynamicGatewayComposerVisualDslRunner;
import com.leanowtech.bloge.gateway.visualadapter.VisualSimulationKernelAdapter;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Parity coverage for the legacy visual runner and the default kernel-backed path. */
class VisualGraphSimulationKernelIntegrationTest {

    @Test
    void standInGraphHasEquivalentSemanticResult() {
        DefaultVisualOperatorCatalog catalog = catalog(
                VisualCatalogTestSupport.designOnlyEligibilityOperator("integer"));
        assertEquivalent(catalog, eligibilityDraft(), Map.of(), Map.of());
        assertThat(kernel(catalog).simulate(eligibilityDraft(), Map.of(), "").nodeFidelity())
                .containsEntry("eligibility", "OUTPUT_LEVEL");
    }

    @Test
    void nonResourceCannotRequestProtocolOrTransportFidelity() {
        DefaultVisualOperatorCatalog catalog = catalog(
                VisualCatalogTestSupport.designOnlyEligibilityOperator("integer"));
        NodeFixture fixture = new NodeFixture(
                Map.of("resourceId", "profile", "statusCode", 200, "rawBody", "{}"),
                null, null, NodeFixture.ResourceFidelity.PROTOCOL_DERIVED);

        VisualGraphSimulationResponse response = kernel(catalog).simulate(
                eligibilityDraft(), Map.of(), "", Map.of("eligibility", fixture));

        assertThat(response.success()).isFalse();
        assertThat(response.errors()).contains("Resource fidelity is invalid for this operator.");
        assertThat(response.nodeFidelity()).doesNotContainKey("eligibility");
    }

    @Test
    void pureTransformAndStandInGraphHasEquivalentSemanticResult() {
        OperatorDefinition primitive = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition standIn = copyAsDesignOnly(primitive, "risk:standin");
        DefaultVisualOperatorCatalog catalog = catalog(primitive, standIn);
        GraphDraft draft = new GraphDraft(
                "", "", 0, "mixedGraph", "", "", "", "", null,
                List.of(
                        new GraphDraft.DraftNode(
                                "transform", "risk:eligibility", "",
                                Map.of(
                                        "score", GraphDraft.Binding.contextPath("score"),
                                        "amount", GraphDraft.Binding.contextPath("amount")),
                                Map.of(), null),
                        new GraphDraft.DraftNode(
                                "standin", "risk:standin", "", Map.of(), Map.of(), null)),
                List.of(), Map.of(), new GraphDraft.OutputSelection("standin", ""));

        assertEquivalent(catalog, draft, Map.of("score", 720, "amount", 250_000), Map.of());
    }

    @Test
    void multipleStandInsAndPureNodesHaveEquivalentClassificationAndHonorOutputOverride() {
        OperatorDefinition primitive = VisualCatalogTestSupport.eligibilityOperator("integer");
        OperatorDefinition firstStandIn = copyAsDesignOnly(primitive, "risk:firstStandin");
        OperatorDefinition secondStandIn = copyAsDesignOnly(primitive, "risk:secondStandin");
        DefaultVisualOperatorCatalog catalog = catalog(primitive, firstStandIn, secondStandIn);
        GraphDraft draft = new GraphDraft(
                "", "", 0, "mixedGraph", "", "", "", "", null,
                List.of(
                        new GraphDraft.DraftNode(
                                "pure", "risk:eligibility", "", Map.of(
                                        "score", GraphDraft.Binding.contextPath("score"),
                                        "amount", GraphDraft.Binding.contextPath("amount")), Map.of(), null),
                        new GraphDraft.DraftNode("first", "risk:firstStandin", "", eligibilityInputs(), Map.of(), null),
                        new GraphDraft.DraftNode("second", "risk:secondStandin", "", eligibilityInputs(), Map.of(), null)),
                List.of(), Map.of(), new GraphDraft.OutputSelection("second", ""));

        VisualGraphSimulationResponse legacy = legacyOracle(catalog)
                .simulate(draft, Map.of("score", 720, "amount", 250_000), "pure");
        VisualGraphSimulationResponse kernel = kernel(catalog)
                .simulate(draft, Map.of("score", 720, "amount", 250_000), "pure");

        assertResponsesEquivalent(legacy, kernel);
        assertThat(kernel.outputNode()).isEqualTo("pure");
        assertThat(kernel.mockedNodeIds()).containsExactly("first", "second");
        assertThat(kernel.realNodeIds()).containsExactly("pure");
    }

    @Test
    void pinnedPrimitiveHasEquivalentSemanticResult() {
        DefaultVisualOperatorCatalog catalog = catalog(
                VisualCatalogTestSupport.eligibilityOperator("integer"));
        Map<String, NodeFixture> fixture = Map.of(
                "eligibility", new NodeFixture(Map.of("eligible", false, "ruleId", "PINNED")));
        assertEquivalent(catalog, eligibilityDraft(), Map.of("score", 720, "amount", 250_000), fixture);
    }

    @Test
    void expectedInputMismatchHasEquivalentVisualSemantics() {
        DefaultVisualOperatorCatalog catalog = catalog(
                VisualCatalogTestSupport.eligibilityOperator("integer"));
        Map<String, NodeFixture> fixture = Map.of("eligibility", new NodeFixture(
                Map.of("eligible", true, "ruleId", "PINNED"),
                Map.of("score", 680, "amount", 250_000)));

        VisualGraphSimulationResponse legacy = legacyOracle(catalog).simulate(
                eligibilityDraft(), Map.of("score", 720, "amount", 250_000), "", fixture);
        VisualGraphSimulationResponse kernel = kernel(catalog).simulate(
                eligibilityDraft(), Map.of("score", 720, "amount", 250_000), "", fixture);

        assertResponsesEquivalent(legacy, kernel);
        assertThat(kernel.success()).isFalse();
        assertThat(kernel.output()).isEqualTo(legacy.output());
        assertThat(kernel.mockedNodeIds()).isEqualTo(legacy.mockedNodeIds());
        assertThat(kernel.realNodeIds()).isEqualTo(legacy.realNodeIds());
        assertThat(kernel.diagnostics()).extracting(diagnostic -> diagnostic.code())
                .contains("visual.simulate.inputAssertionMismatch");
        assertThat(kernel.errors()).anyMatch(error -> error.contains("input assertion failed"));
    }

    @Test
    void requestFixtureOverridesPersistedFixtureOnKernelPath() {
        DefaultVisualOperatorCatalog catalog = catalog(
                VisualCatalogTestSupport.designOnlyEligibilityOperator("integer"));
        GraphDraft draft = eligibilityDraft().withNodeFixtures(Map.of("eligibility",
                new GraphDraft.NodeFixture(Map.of("eligible", false, "ruleId", "PERSISTED"))));
        Map<String, NodeFixture> requestFixture = Map.of("eligibility",
                new NodeFixture(Map.of("eligible", true, "ruleId", "REQUEST")));

        VisualGraphSimulationResponse legacy = legacyOracle(catalog)
                .simulate(draft, Map.of(), "", requestFixture);
        VisualGraphSimulationResponse kernel = kernel(catalog).simulate(draft, Map.of(), "", requestFixture);

        assertResponsesEquivalent(legacy, kernel);
        assertThat(kernel.output()).isEqualTo(Map.of("eligible", true, "ruleId", "REQUEST"));
        assertThat(kernel.mockedNodeIds()).doesNotContain("__sim_eligibility");
        assertThat(kernel.realNodeIds()).doesNotContain("__sim_eligibility");
    }

    @Test
    void explicitNullFixtureHasEquivalentMockedOutput() {
        DefaultVisualOperatorCatalog catalog = catalog(
                VisualCatalogTestSupport.designOnlyEligibilityOperator("integer"));

        VisualGraphSimulationResponse legacy = legacyOracle(catalog).simulate(
                eligibilityDraft(), Map.of(), "",
                Map.of("eligibility", new NodeFixture(null)));
        VisualGraphSimulationResponse kernel = kernel(catalog).simulate(
                eligibilityDraft(), Map.of(), "",
                Map.of("eligibility", new NodeFixture(null)));

        assertResponsesEquivalent(legacy, kernel);
        assertThat(kernel.output()).isNull();
        assertThat(kernel.mockedNodeIds()).containsExactly("eligibility");
        assertThat(kernel.realNodeIds()).isEmpty();
    }

    @Test
    void nodeCapRejectionHasEquivalentPublicSemantics() {
        DefaultVisualOperatorCatalog catalog = catalog(
                VisualCatalogTestSupport.designOnlyEligibilityOperator("integer"));
        List<GraphDraft.DraftNode> nodes = IntStream.rangeClosed(
                        0, VisualGraphSimulationService.MAX_SIMULATION_NODES)
                .mapToObj(i -> new GraphDraft.DraftNode(
                        "n" + i, "risk:eligibility", "", Map.of(), Map.of(), null))
                .toList();
        GraphDraft draft = new GraphDraft(
                "", "", 0, "tooBig", "", "", "", "", null,
                nodes, List.of(), Map.of(), new GraphDraft.OutputSelection("n0", ""));

        assertBlockedEquivalent(catalog, draft, Map.of(), Map.of(),
                "visual.simulate.nodeCapExceeded");
    }

    @Test
    void edgeCapRejectionHasEquivalentPublicSemantics() {
        DefaultVisualOperatorCatalog catalog = catalog(
                VisualCatalogTestSupport.designOnlyEligibilityOperator("integer"));
        List<GraphDraft.DraftEdge> edges = IntStream.range(0,
                        VisualGraphSimulationService.MAX_SIMULATION_EDGES + 1)
                .mapToObj(i -> new GraphDraft.DraftEdge(
                        "e" + i, "data",
                        new GraphDraft.Endpoint("source", "output", ""),
                        new GraphDraft.Endpoint("target", "inputs", "")))
                .toList();
        GraphDraft draft = new GraphDraft(
                "", "", 0, "tooManyEdges", "", "", "", "", null,
                List.of(new GraphDraft.DraftNode(
                        "eligibility", "risk:eligibility", "", Map.of(), Map.of(), null)),
                edges, Map.of(), new GraphDraft.OutputSelection("eligibility", ""));

        assertBlockedEquivalent(catalog, draft, Map.of(), Map.of(),
                "visual.simulate.edgeCapExceeded");
    }

    @Test
    void validationFailureHasEquivalentDiagnosticsAndErrors() {
        DefaultVisualOperatorCatalog catalog = catalog(
                VisualCatalogTestSupport.eligibilityOperator("integer"));
        GraphDraft draft = new GraphDraft(
                "", "", 0, "invalidOutput", "", "", "", "", null,
                eligibilityDraft().nodes(), List.of(), Map.of(),
                new GraphDraft.OutputSelection("eligibility", "missing"));

        assertBlockedEquivalent(catalog, draft,
                Map.of("score", 720, "amount", 250_000), Map.of(),
                "visual.output.unknownPath");
    }

    @Test
    void dslGenerationFailureHasEquivalentDiagnosticsAndErrors() {
        DefaultVisualOperatorCatalog catalog = catalog(
                VisualCatalogTestSupport.designOnlyEligibilityOperator("integer"));
        GraphDraft draft = new GraphDraft(
                "", "", 0, "invalidConfig", "", "", "", "", null,
                List.of(new GraphDraft.DraftNode(
                        "eligibility", "risk:eligibility", "",
                        Map.of("score", GraphDraft.Binding.contextPath("score"),
                                "amount", GraphDraft.Binding.contextPath("amount")),
                        Map.of("bad-key", "value"), null)),
                List.of(), Map.of(), new GraphDraft.OutputSelection("eligibility", ""));

        assertBlockedEquivalent(catalog, draft,
                Map.of("score", 720, "amount", 250_000), Map.of(),
                "visual.codegen.configKey.invalid");
    }

    @Test
    void kernelPathRejectsProductionAdmissionBeforeInvokingExecutor() {
        DefaultVisualOperatorCatalog catalog = catalog(
                VisualCatalogTestSupport.designOnlyEligibilityOperator("integer"));
        AtomicInteger executorInvocations = new AtomicInteger();
        VisualSimulationExecutor executor = plan -> {
            executorInvocations.incrementAndGet();
            throw new AssertionError("production simulation must not invoke the kernel executor");
        };
        VisualProductionAdmissionPolicy productionPolicy =
                new VisualProductionAdmissionPolicy(true, "staging");
        VisualGraphSimulationService service = new VisualGraphSimulationService(
                new GraphDraftValidator(catalog), catalog, new JsonSchemaSampleGenerator(), null,
                executor, Duration.ofSeconds(1), productionPolicy);

        assertThatThrownBy(() -> service.simulate(eligibilityDraft(),
                Map.of("secretBusinessPayload", "must-not-run"), ""))
                .isInstanceOf(VisualSimulationProductionAdmissionException.class)
                .hasMessage(VisualSimulationProductionAdmissionException.TITLE);
        assertThat(executorInvocations).hasValue(0);
    }

    @Test
    void legacyAndKernelTimeoutsHaveEquivalentResponseSemantics() throws InterruptedException {
        DefaultVisualOperatorCatalog catalog = catalog(
                VisualCatalogTestSupport.designOnlyEligibilityOperator("integer"));
        Duration timeout = Duration.ofMillis(100);
        CountDownLatch legacyStarted = new CountDownLatch(1);
        CountDownLatch legacyInterrupted = new CountDownLatch(1);
        CountDownLatch legacyRelease = new CountDownLatch(1);
        CountDownLatch kernelStarted = new CountDownLatch(1);
        CountDownLatch kernelInterrupted = new CountDownLatch(1);
        CountDownLatch kernelRelease = new CountDownLatch(1);
        VisualGraphSimulationService legacy = new VisualGraphSimulationService(
                new GraphDraftValidator(catalog), catalog, new JsonSchemaSampleGenerator(),
                blockingRunnerFactory(legacyStarted, legacyInterrupted, legacyRelease), timeout);
        VisualSimulationExecutor blockingExecutor = blockingExecutor(
                kernelStarted, kernelInterrupted, kernelRelease);
        VisualGraphSimulationService kernel = new VisualGraphSimulationService(
                new GraphDraftValidator(catalog), catalog, new JsonSchemaSampleGenerator(), null,
                blockingExecutor, timeout,
                VisualProductionAdmissionPolicy.nonProductionTest());

        VisualGraphSimulationResponse legacyResponse;
        VisualGraphSimulationResponse kernelResponse;
        try {
            legacyResponse = legacy.simulate(eligibilityDraft(), Map.of(), "");
            kernelResponse = kernel.simulate(eligibilityDraft(), Map.of(), "");
        } finally {
            legacyRelease.countDown();
            kernelRelease.countDown();
        }

        assertThat(legacyStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(kernelStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(legacyInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(kernelInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
        assertResponsesEquivalent(legacyResponse, kernelResponse);
        assertTimeoutResponse(legacyResponse, timeout);
        assertTimeoutResponse(kernelResponse, timeout);
    }

    @Test
    void unexpectedRunnerAndKernelExceptionsAreSanitizedAndEquivalent() {
        DefaultVisualOperatorCatalog catalog = catalog(
                VisualCatalogTestSupport.designOnlyEligibilityOperator("integer"));
        String secret = "sensitive-runner-detail";
        VisualGraphSimulationService legacy = new VisualGraphSimulationService(
                new GraphDraftValidator(catalog), catalog, new JsonSchemaSampleGenerator(),
                throwingRunnerFactory(secret), Duration.ofSeconds(1));
        VisualGraphSimulationService kernel = new VisualGraphSimulationService(
                new GraphDraftValidator(catalog), catalog, new JsonSchemaSampleGenerator(), null,
                plan -> {
                    throw new IllegalStateException(secret);
                }, Duration.ofSeconds(1), VisualProductionAdmissionPolicy.nonProductionTest());

        VisualGraphSimulationResponse legacyResponse = legacy.simulate(eligibilityDraft(), Map.of(), "");
        VisualGraphSimulationResponse kernelResponse = kernel.simulate(eligibilityDraft(), Map.of(), "");

        assertResponsesEquivalent(legacyResponse, kernelResponse);
        assertThat(legacyResponse.diagnostics()).extracting(diagnostic -> diagnostic.code())
                .containsExactly("visual.simulate.runnerFailed");
        assertThat(legacyResponse.diagnostics()).extracting(diagnostic -> diagnostic.message())
                .allMatch(message -> !message.contains(secret));
        assertThat(legacyResponse.errors()).allMatch(error -> !error.contains(secret));
    }

    private static void assertEquivalent(DefaultVisualOperatorCatalog catalog,
                                         GraphDraft draft,
                                         Map<String, Object> context,
                                         Map<String, NodeFixture> fixtures) {
        VisualGraphSimulationResponse legacy = legacyOracle(catalog)
                .simulate(draft, context, "", fixtures);
        VisualGraphSimulationResponse kernel = kernel(catalog).simulate(draft, context, "", fixtures);
        assertResponsesEquivalent(legacy, kernel);
        assertThat(kernel.mockedNodeIds()).allMatch(id -> !id.startsWith("__sim_"));
        assertThat(kernel.realNodeIds()).allMatch(id -> !id.startsWith("__sim_"));
    }

    private static void assertBlockedEquivalent(DefaultVisualOperatorCatalog catalog,
                                                GraphDraft draft,
                                                Map<String, Object> context,
                                                Map<String, NodeFixture> fixtures,
                                                String diagnosticCode) {
        VisualGraphSimulationResponse legacy = legacyOracle(catalog)
                .simulate(draft, context, "", fixtures);
        VisualGraphSimulationResponse kernel = kernel(catalog)
                .simulate(draft, context, "", fixtures);

        assertResponsesEquivalent(legacy, kernel);
        assertThat(legacy.diagnostics()).extracting(diagnostic -> diagnostic.code())
                .contains(diagnosticCode);
    }

    private static void assertResponsesEquivalent(VisualGraphSimulationResponse legacy,
                                                   VisualGraphSimulationResponse kernel) {
        assertThat(semantic(kernel)).isEqualTo(semantic(legacy));
        assertThat(kernel.diagnostics()).extracting(diagnostic -> diagnostic.code())
                .containsExactlyElementsOf(legacy.diagnostics().stream()
                        .map(diagnostic -> diagnostic.code()).toList());
        assertThat(kernel.errors()).isEqualTo(legacy.errors());
    }

    private static void assertTimeoutResponse(VisualGraphSimulationResponse response,
                                              Duration timeout) {
        assertThat(response.validated()).isTrue();
        assertThat(response.compiled()).isFalse();
        assertThat(response.success()).isFalse();
        assertThat(response.elapsedMs()).isEqualTo(timeout.toMillis());
        assertThat(response.diagnostics()).extracting(diagnostic -> diagnostic.code())
                .containsExactly("visual.simulate.timeout");
        assertThat(response.errors()).containsExactly("Simulation exceeded the execution timeout.");
    }

    private static Map<String, Object> semantic(VisualGraphSimulationResponse response) {
        Map<String, Object> semantic = new LinkedHashMap<>();
        semantic.put("validated", response.validated());
        semantic.put("compiled", response.compiled());
        semantic.put("success", response.success());
        semantic.put("graphName", response.graphName());
        semantic.put("outputNode", response.outputNode());
        semantic.put("output", response.output());
        semantic.put("results", response.results());
        semantic.put("statusMap", response.statusMap());
        semantic.put("mockedNodeIds", response.mockedNodeIds());
        semantic.put("realNodeIds", response.realNodeIds());
        semantic.put("terminalOutputConforms", response.terminalOutputConforms());
        semantic.put("generatedDsl", response.generatedDsl());
        return semantic;
    }

    private static VisualGraphSimulationService legacyOracle(DefaultVisualOperatorCatalog catalog) {
        VisualDslRunnerFactory runner = new DynamicGatewayComposerVisualDslRunner(
                new DefaultOperatorRegistry());
        // The exact four-argument constructor is the retained legacy execution oracle.
        return new VisualGraphSimulationService(new GraphDraftValidator(catalog), catalog,
                new JsonSchemaSampleGenerator(), runner);
    }

    private static VisualGraphSimulationService kernel(DefaultVisualOperatorCatalog catalog) {
        VisualSimulationExecutor executor = new VisualSimulationKernelAdapter(new ObjectMapper());
        return new VisualGraphSimulationService(new GraphDraftValidator(catalog), catalog,
                new JsonSchemaSampleGenerator(), null, executor, Duration.ofSeconds(10),
                VisualProductionAdmissionPolicy.nonProductionTest());
    }

    private static DefaultVisualOperatorCatalog catalog(OperatorDefinition... operators) {
        return VisualCatalogTestSupport.catalogWithLibrary(new OperatorLibrary(
                "bloge.visualOperatorLibrary.v1", "integration", "integration", "1.0.0", "test",
                "ACTIVE", List.of(operators)));
    }

    private static OperatorDefinition copyAsDesignOnly(OperatorDefinition base, String operatorRef) {
        return new OperatorDefinition(base.schemaVersion(), operatorRef, base.operatorVersion(),
                base.display(), base.source(), base.ports(), base.configSchema(), base.capabilities(),
                base.policy(), new OperatorDefinition.Lowering("design", "", Map.of()),
                base.diagnostics());
    }

    private static VisualDslRunnerFactory blockingRunnerFactory(CountDownLatch started,
                                                                CountDownLatch interrupted,
                                                                CountDownLatch release) {
        return registry -> new VisualDslRunner() {
            @Override
            public VisualDslRunResponse run(VisualDslRunRequest request) {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException ex) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return null;
            }

            @Override
            public List<VisualDslRunResponse.Diagnostic> compileDiagnostics(String dsl) {
                return List.of();
            }
        };
    }

    private static VisualSimulationExecutor blockingExecutor(CountDownLatch started,
                                                              CountDownLatch interrupted,
                                                              CountDownLatch release) {
        return plan -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException ex) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return null;
        };
    }

    private static VisualDslRunnerFactory throwingRunnerFactory(String message) {
        return registry -> new VisualDslRunner() {
            @Override
            public VisualDslRunResponse run(VisualDslRunRequest request) {
                throw new IllegalStateException(message);
            }

            @Override
            public List<VisualDslRunResponse.Diagnostic> compileDiagnostics(String dsl) {
                return List.of();
            }
        };
    }

    private static GraphDraft eligibilityDraft() {
        return new GraphDraft(
                "", "", 0, "eligibilityPolicy", "", "", "", "", null,
                List.of(new GraphDraft.DraftNode(
                        "eligibility", "risk:eligibility", "",
                        Map.of(
                                "score", GraphDraft.Binding.contextPath("score"),
                                "amount", GraphDraft.Binding.contextPath("amount")),
                        Map.of(), null)),
                List.of(), Map.of(), new GraphDraft.OutputSelection("eligibility", ""));
    }

    private static Map<String, GraphDraft.Binding> eligibilityInputs() {
        return Map.of(
                "score", GraphDraft.Binding.contextPath("score"),
                "amount", GraphDraft.Binding.contextPath("amount"));
    }
}
