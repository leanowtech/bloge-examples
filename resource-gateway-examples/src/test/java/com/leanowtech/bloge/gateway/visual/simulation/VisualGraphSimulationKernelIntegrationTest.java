package com.leanowtech.bloge.gateway.visual.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.DefaultVisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Parity coverage for the legacy visual runner and the default kernel-backed path. */
class VisualGraphSimulationKernelIntegrationTest {

    @Test
    void standInGraphHasEquivalentSemanticResult() {
        DefaultVisualOperatorCatalog catalog = catalog(
                VisualCatalogTestSupport.designOnlyEligibilityOperator("integer"));
        assertEquivalent(catalog, eligibilityDraft(), Map.of(), Map.of());
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

        assertThat(semantic(kernel)).isEqualTo(semantic(legacy));
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

        assertThat(kernel.output()).isEqualTo(legacy.output());
        assertThat(kernel.output()).isEqualTo(Map.of("eligible", true, "ruleId", "REQUEST"));
        assertThat(kernel.mockedNodeIds()).doesNotContain("__sim_eligibility");
        assertThat(kernel.realNodeIds()).doesNotContain("__sim_eligibility");
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
    void kernelExecutorIsBoundedByServiceRunTimeout() throws InterruptedException {
        DefaultVisualOperatorCatalog catalog = catalog(
                VisualCatalogTestSupport.designOnlyEligibilityOperator("integer"));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch blocked = new CountDownLatch(1);
        VisualSimulationExecutor blockingExecutor = plan -> {
            started.countDown();
            try {
                blocked.await();
            } catch (InterruptedException ex) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return null;
        };
        VisualGraphSimulationService service = new VisualGraphSimulationService(
                new GraphDraftValidator(catalog), catalog, new JsonSchemaSampleGenerator(), null,
                blockingExecutor, Duration.ofMillis(100),
                VisualProductionAdmissionPolicy.nonProductionTest());

        VisualGraphSimulationResponse response;
        try {
            response = service.simulate(eligibilityDraft(), Map.of(), "");
        } finally {
            blocked.countDown();
        }

        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(response.validated()).isTrue();
        assertThat(response.compiled()).isFalse();
        assertThat(response.success()).isFalse();
        assertThat(response.elapsedMs()).isEqualTo(100);
        assertThat(response.diagnostics()).extracting(diagnostic -> diagnostic.code())
                .contains("visual.simulate.timeout");
        assertThat(response.errors()).containsExactly("Simulation exceeded the execution timeout.");
    }

    private static void assertEquivalent(DefaultVisualOperatorCatalog catalog,
                                         GraphDraft draft,
                                         Map<String, Object> context,
                                         Map<String, NodeFixture> fixtures) {
        VisualGraphSimulationResponse legacy = legacyOracle(catalog)
                .simulate(draft, context, "", fixtures);
        VisualGraphSimulationResponse kernel = kernel(catalog).simulate(draft, context, "", fixtures);
        assertThat(semantic(kernel)).isEqualTo(semantic(legacy));
        assertThat(kernel.mockedNodeIds()).allMatch(id -> !id.startsWith("__sim_"));
        assertThat(kernel.realNodeIds()).allMatch(id -> !id.startsWith("__sim_"));
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
}
