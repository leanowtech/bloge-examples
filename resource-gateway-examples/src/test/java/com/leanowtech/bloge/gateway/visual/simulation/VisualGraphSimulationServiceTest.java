package com.leanowtech.bloge.gateway.visual.simulation;

import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.DefaultVisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.catalog.VisualCatalogTestSupport;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.runtime.VisualDecisionTable;
import com.leanowtech.bloge.gateway.visual.runtime.VisualDslRunRequest;
import com.leanowtech.bloge.gateway.visual.runtime.VisualDslRunResponse;
import com.leanowtech.bloge.gateway.visual.runtime.VisualDslRunner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualDslRunnerFactory;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunLayout;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import com.leanowtech.bloge.gateway.visualadapter.DynamicGatewayComposerVisualDslRunner;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link VisualGraphSimulationService}: design-only graphs run via mocks,
 * DSL-primitive nodes run for real (hybrid), and resource caps are enforced.
 */
class VisualGraphSimulationServiceTest {

    @Test
    void simulatesDesignOnlyGraphByMockingTheOperator() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer"));
        VisualGraphSimulationService service = simulationService(catalog);

        GraphDraft draft = eligibilityDraft();

        VisualGraphSimulationResponse response = service.simulate(draft, Map.of(), "");

        // The design-only operator cannot run normally, but simulate makes the graph executable.
        assertThat(response.validated()).isTrue();
        assertThat(response.compiled()).isTrue();
        assertThat(response.success()).isTrue();
        assertThat(response.errors()).isEmpty();
        assertThat(response.mockedNodeIds()).containsExactly("eligibility");
        assertThat(response.realNodeIds()).isEmpty();
        assertThat(response.generatedDsl()).contains("__sim_eligibility");
        // Output is synthesized from the declared output schema {eligible: boolean, ruleId: string}.
        assertThat(response.output()).isEqualTo(Map.of("eligible", false, "ruleId", "string"));
        assertThat(response.terminalOutputConforms()).isTrue();
    }

    @Test
    void runsDslPrimitiveNodesForRealInHybridMode() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        VisualGraphSimulationService service = simulationService(catalog);

        GraphDraft draft = eligibilityDraft();

        VisualGraphSimulationResponse response = service.simulate(
                draft, Map.of("score", 720, "amount", 250_000), "");

        // The eligibility operator lowers to a transform (a DSL primitive), so it executes for real.
        assertThat(response.success()).isTrue();
        assertThat(response.realNodeIds()).containsExactly("eligibility");
        assertThat(response.mockedNodeIds()).isEmpty();
        assertThat(response.generatedDsl()).doesNotContain("__sim_");
        assertThat(response.output()).isEqualTo(Map.of("eligible", true, "ruleId", "ELIGIBILITY_V1"));
    }

    @Test
    void blocksSimulationWhenNodeCapExceeded() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer"));
        VisualGraphSimulationService service = simulationService(catalog);

        List<GraphDraft.DraftNode> nodes = new ArrayList<>();
        for (int i = 0; i <= VisualGraphSimulationService.MAX_SIMULATION_NODES; i++) {
            nodes.add(new GraphDraft.DraftNode("n" + i, "risk:eligibility", "", Map.of(), Map.of(), null));
        }
        GraphDraft draft = new GraphDraft(
                "", "", 0, "tooBig", "", "", "", "", null,
                nodes, List.of(), Map.of(),
                new GraphDraft.OutputSelection("n0", ""));

        VisualGraphSimulationResponse response = service.simulate(draft, Map.of(), "");

        assertThat(response.validated()).isFalse();
        assertThat(response.success()).isFalse();
        assertThat(response.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.code())
                        .isEqualTo("visual.simulate.nodeCapExceeded"));
    }

    @Test
    void fixtureOutputOverridesSchemaSampleForMockedNode() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer"));
        VisualGraphSimulationService service = simulationService(catalog);

        Map<String, Object> pinnedOutput = Map.of("eligible", true, "ruleId", "PINNED_V1");
        VisualGraphSimulationResponse response = service.simulate(
                eligibilityDraft(), Map.of(), "",
                Map.of("eligibility", new NodeFixture(pinnedOutput)));

        assertThat(response.success()).isTrue();
        assertThat(response.mockedNodeIds()).containsExactly("eligibility");
        // The author-pinned output takes precedence over the schema-synthesized sample.
        assertThat(response.output()).isEqualTo(pinnedOutput);
    }

    @Test
    void syntheticStandInDoesNotCompileVisualInputsOrDecisionConfig() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer"));
        VisualGraphSimulationService service = simulationService(catalog);
        GraphDraft draft = new GraphDraft(
                "", "", 0, "decisionFixture", "", "", "", "", null,
                List.of(new GraphDraft.DraftNode(
                        "eligibility", "risk:eligibility", "",
                        Map.of("score", GraphDraft.Binding.contextPath("inputs.score"),
                                "amount", GraphDraft.Binding.contextPath("inputs.amount")),
                        Map.of("conditionColumns", List.of(Map.of("key", "score")),
                                "otherwise", Map.of("decision", "decline")), null)),
                List.of(), Map.of(), new GraphDraft.OutputSelection("eligibility", ""));

        VisualGraphSimulationResponse response = service.simulate(
                draft, Map.of(), "", Map.of("eligibility", new NodeFixture(Map.of(
                        "eligible", true, "ruleId", "RETURN"))));

        assertThat(response.validated()).isTrue();
        assertThat(response.compiled()).isTrue();
        assertThat(response.success()).isTrue();
        assertThat(response.mockedNodeIds()).containsExactly("eligibility");
        assertThat(response.output()).isEqualTo(Map.of("eligible", true, "ruleId", "RETURN"));
    }

    @Test
    void fixtureShapeMakesResourcePayloadPathsCompilerVisible() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLoanApplicantResource();
        VisualGraphSimulationService service = simulationService(catalog);
        GraphDraft draft = new GraphDraft(
                "", "", 0, "resourcePayloadProjection", "", "", "", "",
                SchemaEnvelope.opaque(),
                SchemaEnvelope.object(Map.of("score", Map.of("type", "integer")), List.of("score")),
                List.of(
                        new GraphDraft.DraftNode(
                                "applicant",
                                "resource:" + VisualCatalogTestSupport.RESOURCE_ID,
                                "",
                                Map.of("applicantId", GraphDraft.Binding.constant("applicant-1001")),
                                Map.of(),
                                null
                        ),
                        new GraphDraft.DraftNode(
                                "response",
                                "bloge:transform",
                                "",
                                Map.of(),
                                Map.of("assignments", Map.of(
                                        "score", "applicant.output.payload.score"
                                )),
                                null
                        )
                ),
                List.of(),
                Map.of(),
                Map.of(),
                new GraphDraft.OutputSelection("response", ""),
                Map.of(),
                Map.of(),
                null
        );

        VisualGraphSimulationResponse response = service.simulate(
                draft,
                Map.of(),
                "",
                Map.of("applicant", new NodeFixture(Map.of(
                        "payload", Map.of("score", 728, "segment", "prime")
                )))
        );

        assertThat(response.success()).isTrue();
        assertThat(response.output()).isEqualTo(Map.of("score", 728));
        assertThat(response.diagnostics())
                .as("fixture-backed resource paths must be checked against the observed fixture shape")
                .noneMatch(diagnostic -> "bloge.dsl".equals(diagnostic.code()));
    }

    @Test
    void validatesWrappedSinglePortTerminalOutputAtThePortBoundary() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLoanApplicantResource();
        VisualGraphSimulationService service = simulationService(catalog);
        GraphDraft draft = new GraphDraft(
                "", "", 0, "resourceOperatorScenario", "", "", "", "",
                SchemaEnvelope.opaque(),
                SchemaEnvelope.opaque(),
                List.of(new GraphDraft.DraftNode(
                        "operator",
                        "resource:" + VisualCatalogTestSupport.RESOURCE_ID,
                        "",
                        Map.of("applicantId", GraphDraft.Binding.constant("applicant-1001")),
                        Map.of(),
                        null
                )),
                List.of(),
                Map.of(),
                Map.of(),
                new GraphDraft.OutputSelection("operator", ""),
                Map.of(),
                Map.of(),
                null
        );

        VisualGraphSimulationResponse valid = service.simulate(
                draft,
                Map.of(),
                "",
                Map.of("operator", new NodeFixture(Map.of(
                        "payload", Map.of("score", 728, "segment", "prime")
                )))
        );
        VisualGraphSimulationResponse invalid = service.simulate(
                draft,
                Map.of(),
                "",
                Map.of("operator", new NodeFixture(Map.of(
                        "payload", Map.of("score", "not-an-integer", "segment", "prime")
                )))
        );

        assertThat(valid.terminalOutputConforms()).isTrue();
        assertThat(invalid.terminalOutputConforms()).isFalse();
    }

    @Test
    void fixturePinForcesMockOverRealPrimitive() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        VisualGraphSimulationService service = simulationService(catalog);

        Map<String, Object> pinnedOutput = Map.of("eligible", false, "ruleId", "FORCED");
        VisualGraphSimulationResponse response = service.simulate(
                eligibilityDraft(), Map.of("score", 720, "amount", 250_000), "",
                Map.of("eligibility", new NodeFixture(pinnedOutput)));

        // Even though the node would normally run for real (a transform), the pin forces a mock.
        assertThat(response.mockedNodeIds()).containsExactly("eligibility");
        assertThat(response.realNodeIds()).isEmpty();
        assertThat(response.output()).isEqualTo(pinnedOutput);
    }

    @Test
    void fixtureExpectedInputPassesWhenStandInObservesMatchingInput() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        VisualGraphSimulationService service = simulationService(catalog);

        Map<String, Object> pinnedOutput = Map.of("eligible", true, "ruleId", "PINNED_V1");
        Map<String, Object> expectedInput = Map.of("score", 720, "amount", 250_000);

        VisualGraphSimulationResponse response = service.simulate(
                eligibilityDraft(), expectedInput, "",
                Map.of("eligibility", new NodeFixture(pinnedOutput, expectedInput)));

        assertThat(response.success()).isTrue();
        assertThat(response.errors()).isEmpty();
        assertThat(response.diagnostics())
                .noneMatch(diagnostic -> "visual.simulate.inputAssertionMismatch".equals(diagnostic.code()));
    }

    @Test
    void fixtureExpectedInputMismatchBlocksSimulationWithoutChangingPinnedOutput() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        VisualGraphSimulationService service = simulationService(catalog);

        Map<String, Object> pinnedOutput = Map.of("eligible", true, "ruleId", "PINNED_V1");
        Map<String, Object> actualContext = Map.of("score", 720, "amount", 250_000);
        Map<String, Object> expectedInput = Map.of("score", 680, "amount", 250_000);

        VisualGraphSimulationResponse response = service.simulate(
                eligibilityDraft(), actualContext, "",
                Map.of("eligibility", new NodeFixture(pinnedOutput, expectedInput)));

        assertThat(response.success()).isFalse();
        assertThat(response.output()).isEqualTo(pinnedOutput);
        assertThat(response.errors()).anySatisfy(error -> assertThat(error)
                .contains("eligibility")
                .contains("input assertion failed"));
        assertThat(response.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("visual.simulate.inputAssertionMismatch");
                    assertThat(diagnostic.target()).isEqualTo("/fixtures/eligibility/expectedInput");
                });
    }

    @Test
    void persistedDraftFixtureForcesMockOverRealPrimitive() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.eligibilityLibrary("integer"));
        VisualGraphSimulationService service = simulationService(catalog);

        Map<String, Object> pinnedOutput = Map.of("eligible", false, "ruleId", "DRAFT_PIN");
        GraphDraft draft = eligibilityDraft().withNodeFixtures(
                Map.of("eligibility", new GraphDraft.NodeFixture(pinnedOutput)));

        VisualGraphSimulationResponse response = service.simulate(
                draft, Map.of("score", 720, "amount", 250_000), "");

        assertThat(response.success()).isTrue();
        assertThat(response.mockedNodeIds()).containsExactly("eligibility");
        assertThat(response.realNodeIds()).isEmpty();
        assertThat(response.output()).isEqualTo(pinnedOutput);
    }

    @Test
    void requestFixtureOverridesPersistedDraftFixture() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer"));
        VisualGraphSimulationService service = simulationService(catalog);

        GraphDraft draft = eligibilityDraft().withNodeFixtures(Map.of(
                "eligibility", new GraphDraft.NodeFixture(Map.of("eligible", false, "ruleId", "DRAFT_PIN"))));
        Map<String, Object> requestOutput = Map.of("eligible", true, "ruleId", "REQUEST_PIN");

        VisualGraphSimulationResponse response = service.simulate(
                draft, Map.of(), "",
                Map.of("eligibility", new NodeFixture(requestOutput)));

        assertThat(response.success()).isTrue();
        assertThat(response.mockedNodeIds()).containsExactly("eligibility");
        assertThat(response.output()).isEqualTo(requestOutput);
    }

    @Test
    void persistedGovernedFidelityIsNotDowngradedToLegacyOutputLevel() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer"));
        VisualGraphSimulationService service = simulationService(catalog);
        GraphDraft draft = eligibilityDraft().withNodeFixtures(Map.of("eligibility",
                new GraphDraft.NodeFixture(Map.of("eligible", true, "ruleId", "PIN"), null,
                        new GraphDraft.GovernedFixtureRef("fixture", 1,
                                "sha256:" + "a".repeat(64)),
                        GraphDraft.NodeFixture.ResourceFidelity.PROTOCOL_DERIVED)));

        VisualGraphSimulationResponse response = service.simulate(draft, Map.of(), "");

        assertThat(response.success()).isFalse();
        assertThat(response.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.code())
                        .isEqualTo("visual.simulate.resourceFidelity.nonResource"));
    }

    @Test
    void simulationTimeoutReturnsDiagnosticInsteadOfHanging() {
        DefaultVisualOperatorCatalog catalog = VisualCatalogTestSupport.catalogWithLibrary(
                VisualCatalogTestSupport.designOnlyEligibilityLibrary("integer"));
        VisualGraphSimulationService service = new VisualGraphSimulationService(
                new GraphDraftValidator(catalog),
                catalog,
                new JsonSchemaSampleGenerator(),
                new SlowVisualDslRunnerFactory(),
                Duration.ofMillis(25));

        VisualGraphSimulationResponse response = service.simulate(eligibilityDraft(), Map.of(), "");

        assertThat(response.validated()).isTrue();
        assertThat(response.compiled()).isFalse();
        assertThat(response.success()).isFalse();
        assertThat(response.elapsedMs()).isEqualTo(25L);
        assertThat(response.mockedNodeIds()).containsExactly("eligibility");
        assertThat(response.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.code())
                        .isEqualTo("visual.simulate.timeout"));
        assertThat(response.errors()).contains("Simulation exceeded the execution timeout.");
    }

    private static VisualGraphSimulationService simulationService(DefaultVisualOperatorCatalog catalog) {
        return new VisualGraphSimulationService(
                new GraphDraftValidator(catalog),
                catalog,
                new JsonSchemaSampleGenerator(),
                new DynamicGatewayComposerVisualDslRunner(new DefaultOperatorRegistry()));
    }

    private static GraphDraft eligibilityDraft() {
        return new GraphDraft(
                "", "", 0, "eligibilityPolicy", "", "", "", "", null,
                List.of(new GraphDraft.DraftNode(
                        "eligibility",
                        "risk:eligibility",
                        "",
                        Map.of(
                                "score", GraphDraft.Binding.contextPath("score"),
                                "amount", GraphDraft.Binding.contextPath("amount")),
                        Map.of(),
                        null)),
                List.of(),
                Map.of(),
                new GraphDraft.OutputSelection("eligibility", ""));
    }

    private static final class SlowVisualDslRunnerFactory implements VisualDslRunnerFactory {

        @Override
        public VisualDslRunner forRegistry(OperatorRegistry registry) {
            return new SlowVisualDslRunner();
        }
    }

    private static final class SlowVisualDslRunner implements VisualDslRunner {

        @Override
        public VisualDslRunResponse run(VisualDslRunRequest request) {
            try {
                Thread.sleep(Duration.ofSeconds(5));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return new VisualDslRunResponse(
                    true, true, "slowGraph", request.outputNode(), null,
                    Map.of(), Map.of(), 0, Map.of(), List.of(), List.of(),
                    (VisualRunLayout) null, (VisualDecisionTable) null);
        }

        @Override
        public List<VisualDslRunResponse.Diagnostic> compileDiagnostics(String dsl) {
            return List.of();
        }
    }
}
